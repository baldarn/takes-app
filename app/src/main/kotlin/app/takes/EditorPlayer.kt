@file:OptIn(UnstableApi::class)

package app.takes

import androidx.compose.runtime.setValue
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import kotlin.math.log10
import app.takes.core.AudioItem
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import android.os.Build
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.takes.core.EditState
import app.takes.core.LayerPlan
import app.takes.core.RenderPlan
import app.takes.core.TimelineEditing
import app.takes.core.TransitionScene
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import java.io.File
import kotlin.math.roundToInt

/**
 * Plays the edit, addressed by timeline seconds, as its [RenderPlan] arranges
 * it: nothing about the edit is decided here. Clips that follow one another
 * without a break form a chain that one player runs as a gapless playlist.
 * Most transitions need two pictures at once, and a freeze needs the frame it
 * holds while the next clip waits, so chains alternate between two decks, like
 * the two sequences of an export. Where no clip plays at all (a hold on a
 * color, a freeze) the clock runs on by itself.
 */
internal class EditorPlayer(private val context: Context) {
    private val sessions = context.getSystemService(AudioManager::class.java)

    /**
     * A player for each lane an edit drawn by picture layers decodes at once ([EditRendering.LANES]); a sequence
     * plays on the first two, its decks.
     */
    val lanes = List(EditRendering.LANES) {
        ExoPlayer.Builder(context, DeckRenderers(context)).build().apply {
            audioSessionId = sessions.generateAudioSessionId()
            // A clip with noise reduction carries two sounds, recorded and cleaned (a WAV): play the cleaned one.
            trackSelectionParameters = trackSelectionParameters.buildUpon().setPreferredAudioMimeType(MimeTypes.AUDIO_RAW).build()
        }
    }
    val decks: List<ExoPlayer> = lanes.subList(0, RenderPlan.DECKS)
    private val sources = DefaultMediaSourceFactory(context)

    /** The clip as something to play: its own file, or its picture merged with its cleaned sound when that plays in place of the recorded one. */
    private fun source(clip: RenderPlan.Picture, file: File, clean: File?): MediaSource {
        if (clean == null) return sources.createMediaSource(clippedItem(clip, file))
        val merged = MergingMediaSource(
            /* adjustPeriodTimeOffsets = */ true, /* clipDurations = */ true,
            sources.createMediaSource(MediaItem.fromUri(Uri.fromFile(file))), sources.createMediaSource(MediaItem.fromUri(Uri.fromFile(clean))),
        )
        return ClippingMediaSource.Builder(merged)
            .setStartPositionUs(clip.sourceStartUs).setEndPositionUs(clip.sourceEndUs).build()
    }
    // A player's volume stops at 1; a boosted clip is previewed with an enhancer on its audio session.
    private val boosts = lanes.map { runCatching { LoudnessEnhancer(it.audioSessionId) }.getOrNull() }

    // Sound on its own tracks: one audio-only player per track, holding the item that is
    // playing there or the next one to come, so it can start on time.
    private val tracks = List(AudioItem.TRACKS) { ExoPlayer.Builder(context).build().apply { audioSessionId = sessions.generateAudioSessionId() } }
    private val trackBoosts = tracks.map { runCatching { LoudnessEnhancer(it.audioSessionId) }.getOrNull() }
    private val cued = arrayOfNulls<AudioItem>(AudioItem.TRACKS)
    private var files = emptyMap<String, File>()

    private fun apply(player: ExoPlayer, boost: LoudnessEnhancer?, level: Double) {
        player.volume = level.coerceIn(0.0, 1.0).toFloat()
        boost?.let {
            runCatching {
                if (level > 1) it.setTargetGain((2000 * log10(level)).roundToInt())
                it.enabled = level > 1
            }
        }
    }

    /** Sets how loud a deck plays, as a factor of the recorded sound. */
    fun setSound(deck: Int, level: Double) = apply(lanes[deck], boosts[deck], level)

    /** Sets how loud an audio track plays. */
    fun setTrackSound(track: Int, level: Double) = apply(tracks[track], trackBoosts[track], level)

    /** Lines a track up with [time]: inside its item, or waiting at the start of the next one. */
    private fun cue(track: Int, time: Double, play: Boolean) {
        val player = tracks[track]
        val item = plan.cued(track, time)
        if (item == null) { player.pause(); cued[track] = null; return }
        val inside = time >= item.start
        if (cued[track] != item) {
            cued[track] = item
            player.setMediaItem(clippedItem(item, files.getValue(item.sourceID)), if (inside) ((time - item.start) * 1000).toLong() else 0L)
            player.prepare()
        } else {
            player.seekTo(if (inside) ((time - item.start) * 1000).toLong() else 0L)
        }
        if (player.playbackParameters.speed != speed.toFloat()) player.playbackParameters = PlaybackParameters(speed.toFloat())
        player.playWhenReady = play && inside
    }
    var playing by mutableStateOf(false); private set

    /** The review speed of this playback, preview only: the clips' own speeds times this, the pitch kept. */
    private var speed = 1.0

    private var plan = RenderPlan.of(EditState())
    private val clips get() = plan.pictures
    private val chains get() = plan.chains
    private var items = List(2) { emptyList<Int>() } // clip index of each playlist item, per deck
    private val duration get() = plan.duration
    private var loaded: Any? = null
    private var time = 0.0      // where the movie is
    private var ticked = 0L     // when it was last advanced, for the stretches no deck is the clock of

    /**
     * A freeze whose outgoing clip plays on under it for its sound (a still
     * with an audio timing of its own): the picture of [deck] stays on the
     * frame the clip showed before [from], until [until]. That frame is on
     * screen from [shown], and is second [source] of [file].
     */
    private class Sounding(val deck: Int, val from: Double, val until: Double, val shown: Double, val file: File, val source: Double) {
        /** The frame it holds, once read from the recording. */
        @Volatile var picture: Bitmap? = null
    }
    @Volatile private var sounding = emptyList<Sounding>()
    private val reading = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** The frames such freezes hold, drawn over the decks while their players go on with the sound. */
    val heldPictures = HeldPictures()
    // The deck is standing on the frame this freeze holds: a seek put it there while nothing played.
    private val parked = arrayOfNulls<Sounding>(2)

    /**
     * Takes the [plan] of the edit to play, with each source's file in [files] and the cleaned sound of the
     * clips that play it in [clean], by index, and goes to [time].
     */
    fun load(plan: RenderPlan, files: Map<String, File>, clean: Map<Int, File>, time: Double) {
        pause()
        if (layered != null) {
            layered = null
            laneMedia = null
            loaded = null
            // A lane past the decks plays only for an edit drawn by layers.
            for (lane in lanes.drop(decks.size)) if (lane.mediaItemCount > 0) lane.clearMediaItems()
        }
        this.plan = plan
        this.files = files
        cued.fill(null)
        items = plan.decks.map { it.clips }
        sounding = plan.freezes.filter { it.sounding }.map { Sounding(it.deck, it.from, it.until, it.shown, files.getValue(clips[it.clip].sourceID), it.sourceTime) }
        for (deck in decks.indices) { heldPictures.drop(deck); parked[deck] = null }
        // The frames themselves, read ahead: a deck that is playing shows a frame near the freeze, not always the one before it.
        for (freeze in sounding) reading.execute { if (freeze in sounding) freeze.picture = heldFrame(freeze.file, freeze.source) }
        // What the decks hold. A change that leaves it as it is, such as a title or the angle of a wipe, needs no new playlists.
        val media = items to clips.map { clip -> listOf(clip.id, clip.sourceID, clip.sourceStart, clip.sourceEnd, clip.rate, clean[clip.index]) }
        if (media != loaded) {
            loaded = media
            decks.forEachIndexed { deck, player ->
                player.setMediaSources(items[deck].map { source(clips[it], files.getValue(clips[it].sourceID), clean[it]) })
                player.prepare()
            }
        }
        seek(time)
    }

    /** The edit drawn by picture layers that is loaded, null for a sequence; and what its lanes hold. */
    private var layered: LanePlayback? = null
    private var laneMedia: Any? = null

    /**
     * Takes an edit drawn by picture layers ([plan]) to play, one player a lane, with each source's file in [files],
     * and goes to [time]. [sounds] plays the sound tracks: a render plan of the edit's sound items alone.
     */
    fun load(plan: LayerPlan, sounds: RenderPlan, files: Map<String, File>, time: Double) {
        pause()
        this.plan = sounds
        this.files = files
        cued.fill(null)
        sounding = emptyList()
        for (deck in decks.indices) { heldPictures.drop(deck); parked[deck] = null }
        loaded = null
        val playback = LanePlayback(lanes, plan) { speed }
        playback.prepare(files, laneMedia)
        laneMedia = playback.media
        layered = playback
        seek(time)
    }

    /** Takes nothing to play: an edit that cannot be drawn. */
    fun unload() {
        pause()
        layered = null
        laneMedia = null
        loaded = null
        plan = RenderPlan.of(EditState())
        cued.fill(null)
        sounding = emptyList()
        for (deck in decks.indices) { heldPictures.drop(deck); parked[deck] = null }
        lanes.forEach { if (it.mediaItemCount > 0) it.clearMediaItems() }
        tracks.forEach { if (it.mediaItemCount > 0) it.clearMediaItems() }
    }

    /** Plays the deck at its clip's speed, keeping the pitch. */
    private fun pace(player: ExoPlayer, clip: Int) {
        val rate = ((clips.getOrNull(clip)?.rate ?: 1.0) * speed).toFloat()
        if (player.playbackParameters.speed != rate) player.playbackParameters = PlaybackParameters(rate)
    }

    /** The chain [deck] is inside at [time], and the clip of it that is playing. */
    private fun playing(deck: Int, time: Double): Pair<TimelineEditing.Chain, Int>? = plan.playing(deck, time)?.let { (chain, clip) -> chains[chain] to clip }

    /** The frame [deck] has to keep showing at [time] though its clip is over: the one a still holds. */
    private fun held(deck: Int, time: Double): TransitionScene.Source.Held? = plan.held(deck, time)

    // A deck stops by itself on the last frame of its chain: what follows in its playlist belongs to a later moment.
    private fun stopAtChainEnd(deck: Int) {
        val player = decks[deck]
        val clip = items[deck].getOrNull(player.currentMediaItemIndex)
        val last = clip != null && chains.any { it.last == clip }
        if (player.pauseAtEndOfMediaItems != last) player.pauseAtEndOfMediaItems = last
    }

    /**
     * Puts every deck where [time] needs it: inside its chain, on the frame it holds, or parked at the start of its next chain.
     * A deck whose clip plays on under a freeze stands on the frozen frame; when it is [starting] to play it goes
     * to where its sound is, and the frame it stood on is drawn over it.
     */
    fun seek(time: Double, starting: Boolean = false) {
        layered?.let { this.time = it.seek(time, starting); for (track in tracks.indices) cue(track, this.time, play = false); return }
        if (clips.isEmpty()) return
        this.time = time.coerceIn(0.0, duration)
        decks.forEachIndexed { deck, player ->
            val active = playing(deck, time)
            val frozen = held(deck, time)
            val under = sounding.firstOrNull { it.deck == deck && time >= it.from && time < it.until }
            if (starting && under != null) {
                // Standing on the frozen frame: keep it before the deck moves. A frame already kept for this freeze stays.
                if (heldPictures.freeze(deck) !== under) under.picture?.let { heldPictures.put(deck, under, it) }
                if (parked[deck] === under && heldPictures.freeze(deck) !== under) heldPictures.keep(deck, under)
                if (heldPictures.freeze(deck) === under) heldPictures.show(deck)
            } else heldPictures.drop(deck)
            parked[deck] = under.takeIf { !starting && frozen != null }
            if (active != null && frozen != null && !starting) {
                player.seekTo(items[deck].indexOf(frozen.index), (frozen.offset * clips[frozen.index].rate * 1000).toLong())
                pace(player, frozen.index)
            } else if (active != null) {
                val clip = active.second
                val offset = (time - clips[clip].start).coerceIn(0.0, clips[clip].duration)
                // A deck's position runs in recorded seconds; a clip at another speed covers them faster or slower.
                player.seekTo(items[deck].indexOf(clip), (offset * clips[clip].rate * 1000).toLong())
                pace(player, clip)
            } else if (frozen != null) {
                player.seekTo(items[deck].indexOf(frozen.index), (frozen.offset * clips[frozen.index].rate * 1000).toLong())
            } else {
                plan.coming(deck, time)?.let { player.seekTo(items[deck].indexOf(chains[it].first), 0) }
            }
            stopAtChainEnd(deck)
        }
        for (track in tracks.indices) cue(track, time, play = false)
    }

    /** Plays from [time], at [speed] times the edit's own pace. */
    fun play(time: Double, speed: Double = 1.0) {
        layered?.let { layers ->
            this.speed = speed
            this.time = layers.seek(time, starting = true)
            layers.start()
            for (track in tracks.indices) cue(track, this.time, play = true)
            ticked = System.nanoTime()
            playing = true
            return
        }
        if (clips.isEmpty()) return
        this.speed = speed
        seek(time, starting = true) // line both decks up again
        for (deck in decks.indices) if (playing(deck, time) != null) decks[deck].play()
        for (track in tracks.indices) cue(track, time, play = true)
        ticked = System.nanoTime()
        playing = true
    }

    fun pause() {
        lanes.forEach { it.pause() }
        tracks.forEach { it.pause() }
        playing = false
    }

    /** Advances the hand-over between decks and returns the timeline time. Call once per frame while playing. */
    fun tick(): Double {
        val now = System.nanoTime()
        val passed = ((now - ticked) / 1e9).coerceIn(0.0, 0.1)
        layered?.let { layers ->
            ticked = now
            time = layers.tick(passed)
            if (time >= layers.plan.duration) { time = layers.plan.duration; pause(); return time }
            if (playing) followTracks()
            return time
        }
        if (clips.isEmpty()) return 0.0
        ticked = now
        // The clock is the deck that began playing last; where none plays, time runs on by itself.
        val leading = plan.leading(time)
        time = if (leading == null) time + passed * speed else {
            val chain = chains[leading]
            val deck = clips[chain.first].deck
            val player = decks[deck]
            val clip = items[deck].getOrNull(player.currentMediaItemIndex)
            when {
                // A deck parked elsewhere, or one that ran past its chain: its chain is over for it.
                clip == null || clip > chain.last -> chain.end
                clip < chain.first -> time + passed * speed
                // Stopped on the last frame of the chain.
                !player.playWhenReady && clip == chain.last && player.playbackState == Player.STATE_READY &&
                    player.currentPosition >= player.duration - 30 -> chain.end
                player.playbackState == Player.STATE_ENDED -> chain.end
                else -> (clips[clip].start + player.currentPosition.coerceAtLeast(0) / 1000.0 / clips[clip].rate).coerceAtMost(chain.end)
            }
        }
        if (time >= duration) {
            time = duration
            pause()
            return time
        }
        // A freeze whose clip plays on: its frame stays over the deck, from the moment it comes on screen, so the
        // deck never shows a later one. Should the frame not be read yet, the picture the deck showed last stands in for it.
        for (freeze in sounding) when {
            time >= freeze.shown - KEEP_AHEAD && time < freeze.shown -> if (freeze.picture == null) heldPictures.keep(freeze.deck, freeze)
            time >= freeze.shown && time < freeze.until -> {
                val picture = freeze.picture
                if (picture != null) heldPictures.put(freeze.deck, freeze, picture) else if (heldPictures.freeze(freeze.deck) !== freeze) heldPictures.keep(freeze.deck, freeze)
                heldPictures.show(freeze.deck)
            }
            else -> heldPictures.drop(freeze.deck, freeze)
        }
        decks.forEachIndexed { deck, player ->
            val active = playing(deck, time)
            val clip = items[deck].getOrNull(player.currentMediaItemIndex)
            if (active != null) {
                val (chain, _) = active
                // A chain whose moment has come starts from where its deck was parked.
                if (clip == null || clip !in chain.first..chain.last) {
                    player.seekTo(items[deck].indexOf(chain.first), ((time - chain.start) * clips[chain.first].rate * 1000).toLong().coerceAtLeast(0))
                }
                if (!player.playWhenReady && !(clip == chain.last && player.currentPosition >= player.duration - 30)) player.play()
                // Each deck follows the speed of the clip it has reached.
                items[deck].getOrNull(player.currentMediaItemIndex)?.let { pace(player, it) }
            } else if (held(deck, time) == null) {
                // The deck that finished waits at the start of its next chain, once its last frame is no longer shown.
                val next = plan.coming(deck, time)?.let { chains[it] }
                if (next != null && clip != next.first) {
                    player.pause()
                    player.seekTo(items[deck].indexOf(next.first), 0)
                }
            }
            stopAtChainEnd(deck)
        }
        if (playing) followTracks()
        return time
    }

    // Audio tracks: start the waiting item when its moment comes, then line up the next.
    private fun followTracks() {
        for (track in tracks.indices) {
            val item = cued[track]
            when {
                item == null -> Unit
                time >= item.end -> cue(track, time, play = true)
                time >= item.start && !tracks[track].playWhenReady -> tracks[track].play()
            }
        }
    }

    private companion object {
        /** How long before a freeze the deck's picture is taken, each frame replacing the one before, in seconds. */
        const val KEEP_AHEAD = 0.1

        /** The longer side of a frame read for a freeze, in pixels: no more than a screen shows. */
        const val HELD_SIDE = 1280

        /** The frame of [file] showing at source second [time], upright. */
        fun heldFrame(file: File, time: Double): Bitmap? {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.path)
                val micros = (time * 1e6).toLong()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) retriever.getScaledFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST, HELD_SIDE, HELD_SIDE)
                else retriever.getFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST)
            } catch (_: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    fun release() {
        reading.shutdownNow()
        (boosts + trackBoosts).forEach { runCatching { it?.release() } }
        tracks.forEach { it.release() }
        lanes.forEach { it.release() }
    }
}
