@file:OptIn(UnstableApi::class)

package app.takes

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import app.takes.core.WaveformBuilder
import kotlin.math.sqrt
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.Icons
import androidx.compose.ui.semantics.Role
import java.util.Date
import java.text.DateFormat
import app.takes.core.UnusedTakes
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import app.takes.core.TextOverlay
import android.view.LayoutInflater
import androidx.compose.ui.draw.alpha
import app.takes.core.TransitionSettings
import app.takes.core.TransitionStyle
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import app.takes.core.ClipAudio
import kotlin.math.log10
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import app.takes.core.AudioItem
import android.net.Uri
import androidx.compose.runtime.mutableIntStateOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import kotlin.math.roundToLong
import app.takes.core.PauseDetector
import app.takes.core.PauseSettings
import app.takes.core.SourceInterval
import app.takes.core.SourceWaveform
import app.takes.core.SubtitleEditing
import android.os.Build
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import app.takes.core.ColorLook
import app.takes.core.ColorSettings
import app.takes.core.ImportedLUT
import app.takes.core.CropSettings
import app.takes.core.Rect as FrameRect
import app.takes.core.Size as FrameSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import android.app.Activity
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.takes.core.EditState
import app.takes.core.ExportSettings
import app.takes.core.Project
import app.takes.core.TimelineClip
import app.takes.core.TimelineEditing
import app.takes.core.TitleMotionRanges
import app.takes.core.TransitionFrame
import app.takes.core.TransitionScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

private val Panel = Color(0xFF1B1D21)
private val Muted = Color(0xFFA0A4AB)
private val TileSize = 56.dp
private val WaveHeight = 30.dp
private val WaveColor = Color(0xFF7DB7FF)
private val TitleColor = Color(0xFFFFD54A)
private val BookmarkColor = Color(0xFFFF7A45)
private val SoundColor = Color(0xFF7BD389)
private val TrackHeight = 26.dp
private val StripTop = 17.dp

private fun preciseLabel(seconds: Double): String {
    val tenths = (seconds.coerceAtLeast(0.0) * 10).toLong()
    return "%d:%02d.%d".format(tenths / 600, tenths / 10 % 60, tenths % 10)
}

/**
 * Plays the edit, addressed by timeline seconds. Clips that follow one another
 * without a break form a chain that one player runs as a gapless playlist.
 * Most transitions need two pictures at once, and a freeze needs the frame it
 * holds while the next clip waits, so chains alternate between two decks, like
 * the two sequences of an export. Where no clip plays at all (a hold on a
 * color, a freeze) the clock runs on by itself.
 */
private class EditorPlayer(private val context: Context) {
    private val sessions = context.getSystemService(AudioManager::class.java)
    val decks = List(2) {
        ExoPlayer.Builder(context).build().apply {
            audioSessionId = sessions.generateAudioSessionId()
            // A clip with noise reduction carries two sounds, recorded and cleaned (a WAV): play the cleaned one.
            trackSelectionParameters = trackSelectionParameters.buildUpon().setPreferredAudioMimeType(MimeTypes.AUDIO_RAW).build()
        }
    }
    private val sources = DefaultMediaSourceFactory(context)

    /** The clip as something to play: its own file, or its picture merged with its cleaned sound when that is ready. */
    private fun source(clip: TimelineClip, file: File): MediaSource {
        val clean = clip.audio?.denoise?.takeIf { it > 0 }?.let { Denoiser.ready(file, it) }
            ?: return sources.createMediaSource(clippedItem(clip, file))
        val merged = MergingMediaSource(
            /* adjustPeriodTimeOffsets = */ true, /* clipDurations = */ true,
            sources.createMediaSource(MediaItem.fromUri(Uri.fromFile(file))), sources.createMediaSource(MediaItem.fromUri(Uri.fromFile(clean))),
        )
        return ClippingMediaSource.Builder(merged)
            .setStartPositionUs((clip.start * 1e6).roundToLong()).setEndPositionUs((clip.end * 1e6).roundToLong()).build()
    }
    // A player's volume stops at 1; a boosted clip is previewed with an enhancer on its audio session.
    private val boosts = decks.map { runCatching { LoudnessEnhancer(it.audioSessionId) }.getOrNull() }

    // Sound on its own tracks: one audio-only player per track, holding the item that is
    // playing there or the next one to come, so it can start on time.
    private val tracks = List(AudioItem.TRACKS) { ExoPlayer.Builder(context).build().apply { audioSessionId = sessions.generateAudioSessionId() } }
    private val trackBoosts = tracks.map { runCatching { LoudnessEnhancer(it.audioSessionId) }.getOrNull() }
    private val cued = arrayOfNulls<AudioItem>(AudioItem.TRACKS)
    private var sounds = emptyList<AudioItem>()
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
    fun setSound(deck: Int, level: Double) = apply(decks[deck], boosts[deck], level)

    /** Sets how loud an audio track plays. */
    fun setTrackSound(track: Int, level: Double) = apply(tracks[track], trackBoosts[track], level)

    /** Lines a track up with [time]: inside its item, or waiting at the start of the next one. */
    private fun cue(track: Int, time: Double, play: Boolean) {
        val player = tracks[track]
        val item = TimelineEditing.audioAt(sounds, track, time) ?: sounds.filter { it.track == track && it.start > time }.minByOrNull { it.start }
        if (item == null) { player.pause(); cued[track] = null; return }
        val inside = time >= item.start
        if (cued[track] != item) {
            cued[track] = item
            player.setMediaItem(clippedItem(item, files.getValue(item.sourceID)), if (inside) ((time - item.start) * 1000).toLong() else 0L)
            player.prepare()
        } else {
            player.seekTo(if (inside) ((time - item.start) * 1000).toLong() else 0L)
        }
        player.playWhenReady = play && inside
    }
    var playing by mutableStateOf(false); private set

    private var clips = emptyList<TimelineClip>()
    private var chains = emptyList<TimelineEditing.Chain>()
    private var items = List(2) { emptyList<Int>() } // clip index of each playlist item, per deck
    private var duration = 0.0
    private var time = 0.0      // where the movie is
    private var ticked = 0L     // when it was last advanced, for the stretches no deck is the clock of

    fun load(clips: List<TimelineClip>, sounds: List<AudioItem>, files: Map<String, File>, time: Double) {
        pause()
        this.sounds = sounds
        this.files = files
        cued.fill(null)
        this.clips = clips
        chains = TimelineEditing.chains(clips)
        duration = TimelineEditing.duration(clips)
        items = List(2) { deck -> chains.filterIndexed { chain, _ -> chain % 2 == deck }.flatMap { it.first..it.last } }
        decks.forEachIndexed { deck, player ->
            player.setMediaSources(items[deck].map { source(clips[it], files.getValue(clips[it].sourceID)) })
            player.prepare()
        }
        seek(time)
    }

    /** Plays the deck at its clip's speed, keeping the pitch. */
    private fun pace(player: ExoPlayer, clip: Int) {
        val rate = clips.getOrNull(clip)?.rate?.toFloat() ?: 1f
        if (player.playbackParameters.speed != rate) player.playbackParameters = PlaybackParameters(rate)
    }

    /** The chain [deck] is inside at [time], and the clip of it that is playing. */
    private fun playing(deck: Int, time: Double): Pair<TimelineEditing.Chain, Int>? {
        val chain = chains.withIndex().lastOrNull { it.index % 2 == deck && time >= it.value.start && time < it.value.end }?.value ?: return null
        return chain to ((chain.first..chain.last).lastOrNull { TimelineEditing.clipStart(clips, it) <= time } ?: chain.first)
    }

    /** The frame [deck] has to keep showing at [time] though its clip is over: the one a still holds. */
    private fun held(deck: Int, time: Double): TransitionScene.Source.Held? = TransitionScene.moment(clips, time)
        .let { listOf(it.first, it.second) }.filterIsInstance<TransitionScene.Source.Held>().firstOrNull { it.deck == deck }

    // A deck stops by itself on the last frame of its chain: what follows in its playlist belongs to a later moment.
    private fun stopAtChainEnd(deck: Int) {
        val player = decks[deck]
        val clip = items[deck].getOrNull(player.currentMediaItemIndex)
        val last = clip != null && chains.any { it.last == clip }
        if (player.pauseAtEndOfMediaItems != last) player.pauseAtEndOfMediaItems = last
    }

    /** Puts every deck where [time] needs it: inside its chain, on the frame it holds, or parked at the start of its next chain. */
    fun seek(time: Double) {
        if (clips.isEmpty()) return
        this.time = time.coerceIn(0.0, duration)
        decks.forEachIndexed { deck, player ->
            val active = playing(deck, time)
            val frozen = held(deck, time)
            if (active != null) {
                val clip = active.second
                val offset = (time - TimelineEditing.clipStart(clips, clip)).coerceIn(0.0, clips[clip].duration)
                // A deck's position runs in recorded seconds; a clip at another speed covers them faster or slower.
                player.seekTo(items[deck].indexOf(clip), (offset * clips[clip].rate * 1000).toLong())
                pace(player, clip)
            } else if (frozen != null) {
                player.seekTo(items[deck].indexOf(frozen.index), (frozen.offset * clips[frozen.index].rate * 1000).toLong())
            } else {
                chains.withIndex().firstOrNull { it.index % 2 == deck && it.value.start > time }?.let { player.seekTo(items[deck].indexOf(it.value.first), 0) }
            }
            stopAtChainEnd(deck)
        }
        for (track in tracks.indices) cue(track, time, play = false)
    }

    fun play(time: Double) {
        if (clips.isEmpty()) return
        seek(time) // line both decks up again
        for (deck in decks.indices) if (playing(deck, time) != null) decks[deck].play()
        for (track in tracks.indices) cue(track, time, play = true)
        ticked = System.nanoTime()
        playing = true
    }

    fun pause() {
        decks.forEach { it.pause() }
        tracks.forEach { it.pause() }
        playing = false
    }

    /** Advances the hand-over between decks and returns the timeline time. Call once per frame while playing. */
    fun tick(): Double {
        if (clips.isEmpty()) return 0.0
        val now = System.nanoTime()
        val passed = ((now - ticked) / 1e9).coerceIn(0.0, 0.1)
        ticked = now
        // The clock is the deck that began playing last; where none plays, time runs on by itself.
        val leading = chains.withIndex().lastOrNull { time >= it.value.start && time < it.value.end }
        time = if (leading == null) time + passed else {
            val chain = leading.value
            val deck = leading.index % 2
            val player = decks[deck]
            val clip = items[deck].getOrNull(player.currentMediaItemIndex)
            when {
                // A deck parked elsewhere, or one that ran past its chain: its chain is over for it.
                clip == null || clip > chain.last -> chain.end
                clip < chain.first -> time + passed
                // Stopped on the last frame of the chain.
                !player.playWhenReady && clip == chain.last && player.playbackState == Player.STATE_READY &&
                    player.currentPosition >= player.duration - 30 -> chain.end
                player.playbackState == Player.STATE_ENDED -> chain.end
                else -> (TimelineEditing.clipStart(clips, clip) + player.currentPosition.coerceAtLeast(0) / 1000.0 / clips[clip].rate).coerceAtMost(chain.end)
            }
        }
        if (time >= duration) {
            time = duration
            pause()
            return time
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
                val next = chains.withIndex().firstOrNull { it.index % 2 == deck && it.value.start > time }?.value
                if (next != null && clip != next.first) {
                    player.pause()
                    player.seekTo(items[deck].indexOf(next.first), 0)
                }
            }
            stopAtChainEnd(deck)
        }
        // Audio tracks: start the waiting item when its moment comes, then line up the next.
        if (playing) for (track in tracks.indices) {
            val item = cued[track]
            when {
                item == null -> Unit
                time >= item.end -> cue(track, time, play = true)
                time >= item.start && !tracks[track].playWhenReady -> tracks[track].play()
            }
        }
        return time
    }

    fun release() {
        (boosts + trackBoosts).forEach { runCatching { it?.release() } }
        tracks.forEach { it.release() }
        decks.forEach { it.release() }
    }
}

/** Timeline thumbnails, decoded off the main thread and kept for reuse. */
private object Thumbnails {
    private val cache = LruCache<String, Bitmap>(300)
    private val decoding = Dispatchers.IO.limitedParallelism(2)

    suspend fun frame(file: File, time: Double): Bitmap? {
        val key = file.path + "@" + (time * 2).roundToInt()
        cache.get(key)?.let { return it }
        return withContext(decoding) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                retriever.getFrameAtTime((time * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { frame ->
                    val scale = 160f / maxOf(frame.width, frame.height)
                    if (scale < 1) Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true) else frame
                }?.also { cache.put(key, it) }
            } catch (_: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }
}

@Composable
fun EditorScreen(store: ProjectStore, exporter: Exporter, modifier: Modifier = Modifier, record: () -> Unit = {}) {
    val project = store.selected
    if (project == null || project.sources.isEmpty()) {
        Box(modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
            Text(stringResource(if (project == null) R.string.editor_no_project else R.string.editor_no_recordings), color = Muted)
        }
        return
    }
    key(project.id) { Editor(project, store, exporter, modifier, record) }
}

@Composable
private fun Editor(project: Project, store: ProjectStore, exporter: Exporter, modifier: Modifier, record: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val edit = project.edit
    val clips = edit.clips
    val duration = edit.duration
    val files = remember(project.sources) { project.sources.associate { it.id to store.file(project, it) } }
    val editor = remember { EditorPlayer(context) }
    var playhead by remember { mutableDoubleStateOf(0.0) }
    var scale by remember { mutableFloatStateOf(48f) } // dp of timeline per second
    val pixelsPerSecond = with(LocalDensity.current) { scale.dp.toPx() }
    val scroll = rememberScrollState()

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) editor.pause() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); editor.release() }
    }
    // Watching the movie or waiting for an export must not let the screen turn off.
    val view = LocalView.current
    // The Subtitles tool: the edit it is working on, shown in the preview and not saved until applied.
    var subtitling by remember { mutableStateOf<EditState?>(null) }
    var subtitleWork by remember { mutableStateOf(false) } // downloading the model or transcribing
    // The Title tool: the title it is working on, shown in the preview and not saved until confirmed.
    var titling by remember { mutableStateOf<Titling?>(null) }
    // Where a preview of a title's motion stops by itself.
    var auditionEnd by remember { mutableStateOf<Double?>(null) }
    val busy = editor.playing || exporter.progress != null || subtitleWork
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }
    val sounds = edit.audio.orEmpty()
    // Noise reduction runs in the background, one recording at a time. Until a clip's
    // cleaned sound is ready the preview plays the recorded sound; then the players reload.
    val cleaning = clips.mapNotNull { clip -> clip.audio?.denoise?.takeIf { it > 0 }?.let { files.getValue(clip.sourceID) to it } }.distinct()
    var cleaned by remember { mutableIntStateOf(0) }
    var cleaningProgress by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(cleaning) {
        try {
            for ((file, amount) in cleaning) {
                if (Denoiser.ready(file, amount) != null) continue
                cleaningProgress = 0f
                if (Denoiser.process(file, amount) { cleaningProgress = it } != null) cleaned++
            }
        } finally {
            cleaningProgress = null
        }
    }
    LaunchedEffect(clips, sounds, cleaned) {
        playhead = playhead.coerceIn(0.0, duration)
        editor.load(clips, sounds, files, playhead)
    }
    // The timeline scrolls under a fixed playhead. Playback and edits move the
    // scroll position; a drag moves the playhead and seeks.
    LaunchedEffect(clips, pixelsPerSecond, editor.playing) {
        withFrameNanos { } // lay out the new clip widths first
        scroll.scrollTo((playhead.coerceIn(0.0, duration) * pixelsPerSecond).roundToInt())
        while (editor.playing) {
            withFrameNanos { }
            playhead = editor.tick().coerceIn(0.0, duration)
            scroll.scrollTo((playhead * pixelsPerSecond).roundToInt())
            auditionEnd?.let { if (playhead >= it) { auditionEnd = null; editor.pause() } }
        }
    }
    LaunchedEffect(clips, pixelsPerSecond) {
        snapshotFlow { scroll.value }.drop(1).collect { pixels ->
            val time = (pixels / pixelsPerSecond).toDouble().coerceIn(0.0, duration)
            // Our own scrollTo lands within half a pixel of the playhead.
            if (!editor.playing && abs(time - playhead) > 0.6 / pixelsPerSecond) {
                playhead = time
                editor.seek(time)
            }
        }
    }

    // Lambdas, not local functions: references to local functions compare
    // equal across recompositions, so a long-lived gesture would keep the first.
    val commit = { next: EditState?, time: Double ->
        if (next != null) {
            editor.pause()
            playhead = time
            store.commit(project.id, next)
        }
    }
    val location = TimelineEditing.locate(clips, playhead)

    // Trim handles change the clip under the playhead. The draft is shown in
    // the timeline while dragging and becomes one undo step on release.
    val durations = remember(project.sources) { project.sources.associate { it.id to it.duration } }
    var trim by remember { mutableStateOf<Trim?>(null) }
    val shown = trim?.takeIf { it.index in clips.indices }?.let { draft -> clips.toMutableList().also { it[draft.index] = draft.clip } } ?: clips
    val dragTrim = drag@{ pixels: Float ->
        val draft = trim ?: return@drag
        val original = clips.getOrNull(draft.index) ?: return@drag
        val delta = pixels / pixelsPerSecond * original.rate // the strip is drawn in timeline seconds
        val limit = durations[original.sourceID] ?: original.end
        val next = if (draft.leading) TimelineEditing.trimmed(original, original.start + delta, original.end, limit)
        else TimelineEditing.trimmed(original, original.start, original.end + delta, limit)
        trim = draft.copy(clip = next)
        // Show the frame at the moving edge while it is inside the current clip.
        val edge = if (draft.leading) next.start else next.end - 0.03
        if (edge in original.start..original.end) editor.seek(TimelineEditing.clipStart(clips, draft.index) + original.offset(edge))
    }
    val endTrim = end@{
        val draft = trim ?: return@end
        trim = null
        val original = clips.getOrNull(draft.index) ?: return@end
        val base = TimelineEditing.clipStart(clips, draft.index)
        val next = TimelineEditing.trim(edit, draft.index, draft.clip.start, draft.clip.end, durations[original.sourceID] ?: original.end)
        if (next == null) { editor.seek(playhead); return@end }
        // Keep the same frame under the playhead when it survives the trim.
        val offset = (playhead - base - original.offset(draft.clip.start)).coerceIn(0.0, draft.clip.duration - 0.01)
        val moved = base + offset - playhead
        commit(next, base + offset)
        scroll.dispatchRawDelta((moved * pixelsPerSecond).toFloat()) // no one-frame jump before the scroll catches up
    }

    // What the playhead shows on top of the picture: titles and the fade of a transition.
    val canvas by produceState<Pair<Int, Int>?>(null, clips.firstOrNull()?.sourceID) {
        value = clips.firstOrNull()?.let { files[it.sourceID] }?.let { withContext(Dispatchers.IO) { displaySize(it) } }
    }
    // The upright size of each recording, for framing.
    val sizes by produceState(emptyMap<String, FrameSize>(), project.sources) {
        value = withContext(Dispatchers.IO) {
            project.recordings.mapNotNull { source ->
                displaySize(store.file(project, source))?.let { source.id to FrameSize(it.first.toDouble(), it.second.toDouble()) }
            }.toMap()
        }
    }
    // Reframing in progress: the clip being framed and the framing not saved yet, shown live.
    var cropping by remember { mutableStateOf<Pair<String, CropSettings>?>(null) }
    var pacing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val unusedFailed = stringResource(R.string.unused_failed)
    // The unused takes chosen so far, in the order they will play; null while the list is closed.
    var choosing by remember { mutableStateOf<List<String>?>(null) }
    val current = location?.let { clips[it.index] }
    val sourceTime = if (location != null && current != null) current.sourceTime(location.offset) else 0.0
    val titles = current?.let { TimelineEditing.textsAt(it, sourceTime) }.orEmpty()
    // Which deck shows each clip, and the clip each deck is playing now or holding the last frame of.
    val deckOf = remember(clips) { TransitionScene.decks(clips) }
    val deckPlaying = List(2) { deck ->
        clips.indices.lastOrNull { deckOf[it] == deck && playhead >= TimelineEditing.clipStart(clips, it) && playhead < TimelineEditing.clipStart(clips, it) + clips[it].duration }
    }
    val deckClips = List(2) { deck ->
        deckPlaying[deck]
            ?: TransitionScene.moment(clips, playhead, decks = deckOf).let { listOf(it.first, it.second) }.firstNotNullOfOrNull { (it as? TransitionScene.Source.Held)?.takeIf { held -> held.deck == deck }?.index }
            ?: location?.index?.takeIf { deckOf[it] == deck }
    }
    // What each deck sounds like: its clip's own level and what the transitions at its ends leave of it.
    val deckSounds = List(2) { deck ->
        deckPlaying[deck]?.let { TimelineEditing.soundLevel(clips, it, playhead - TimelineEditing.clipStart(clips, it)) } ?: 0.0
    }
    // The color each deck shows.
    val folder = remember(project.id) { store.disk.folder(project.id) }
    val deckColors = deckClips.map { index -> index?.let { clips[it].color } }
    // The framing each deck shows, with the size of the recording it applies to.
    val deckFrames = deckClips.map { index ->
        val clip = index?.let { clips[it] }
        val crop = clip?.let { cropping?.takeIf { (id, _) -> id == clip.id }?.second ?: clip.crop }
        val size = clip?.let { sizes[it.sourceID] }
        if (crop != null && size != null) crop to size else null
    }
    SideEffect {
        deckSounds.forEachIndexed(editor::setSound)
        for (track in 0 until AudioItem.TRACKS) {
            editor.setTrackSound(track, TimelineEditing.audioAt(sounds, track, playhead)?.let { TimelineEditing.soundLevel(it, playhead - it.start) } ?: 0.0)
        }
    }
    // The titles under the playhead, with the one being worked on as it is now.
    val titled = titling?.let { work -> TimelineEditing.setTitle(edit, clips.indexOfFirst { it.id == work.clipID }, work.title)?.clips } ?: clips
    val titleRanges = remember(titled) { TitleMotionRanges(titled) }
    val shownTitles = titleRanges.shown(titled, playhead)
    val titlePainter = remember { TitlePainter(context.assets) }
    // Taking a color from the picture: the preview as it is on screen, without the titles over it.
    var previewBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var sampling by remember { mutableStateOf(false) }
    val samplePicture: suspend () -> Bitmap? = sample@{
        val window = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()?.window
        val area = previewBounds
        if (window == null || area == null || area.width < 2 || area.height < 2) return@sample null
        sampling = true
        try {
            repeat(2) { withFrameNanos { } } // let the titles leave the screen
            suspendCancellableCoroutine { waiting ->
                val picture = Bitmap.createBitmap(area.width.toInt(), area.height.toInt(), Bitmap.Config.ARGB_8888)
                val part = android.graphics.Rect(area.left.toInt(), area.top.toInt(), area.left.toInt() + picture.width, area.top.toInt() + picture.height)
                try {
                    PixelCopy.request(window, part, picture, { result -> waiting.resume(picture.takeIf { result == PixelCopy.SUCCESS }) }, Handler(Looper.getMainLooper()))
                } catch (_: IllegalArgumentException) {
                    waiting.resume(null)
                }
            }
        } finally {
            sampling = false
        }
    }
    // Subtitles, laid out once for the movie's shape and drawn by the code the export uses.
    val captioned = subtitling ?: edit
    val captions = remember(captioned.clips, captioned.subtitles == null) { SubtitleEditing.cues(captioned) }
    val captionLayout = remember(captions, captioned.subtitles?.style, captioned.subtitles?.resolvedFont, canvas) {
        val look = captioned.subtitles
        val shape = canvas
        if (look == null || shape == null || captions.isEmpty()) null else SubtitleLayout(captions, look, shape.first, shape.second)
    }
    val scope = rememberCoroutineScope()
    val jump = { time: Double ->
        editor.pause()
        playhead = time
        editor.seek(time)
        scope.launch { scroll.scrollTo((time * pixelsPerSecond).roundToInt()) }
        Unit
    }
    var choosingExport by remember { mutableStateOf(false) }
    var findingPauses by remember { mutableStateOf(false) }
    var editingColor by remember { mutableStateOf(false) }
    var editingAudio by remember { mutableStateOf(false) }
    var chosenSound by remember { mutableStateOf<String?>(null) }   // the audio item being edited
    var shapingSound by remember { mutableStateOf(false) }
    val pickSound = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) store.importAudio(project.id, uri, playhead)
    }
    val soundName = stringResource(R.string.editor_sound_default_name)
    val names = remember(project.sources, soundName) { project.sources.associate { it.id to (it.originalName ?: soundName) } }
    var editingTransition by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(project.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = { editor.pause(); store.undo(project.id) }, enabled = project.canUndo && subtitling == null && titling == null) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.editor_undo)) }
            TextButton(onClick = { editor.pause(); store.redo(project.id) }, enabled = project.canRedo && subtitling == null && titling == null) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.editor_redo)) }
            Button(onClick = { editor.pause(); choosingExport = true }, enabled = clips.isNotEmpty() && subtitling == null && titling == null) { Text(stringResource(R.string.editor_export)) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), Alignment.Center) {
            // The export canvas: the first clip's shape. Other clips fit inside it.
            Box((canvas?.let { Modifier.aspectRatio(it.first.toFloat() / it.second) } ?: Modifier.fillMaxSize()).clipToBounds().onGloballyPositioned { previewBounds = it.boundsInWindow() }) {
                val effects = deckColors.map { color ->
                    remember(color, folder) {
                        if (color != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ColorLuts.previewEffect(color, folder).asComposeRenderEffect() else null
                    }
                }
                DeckPictures(
                    editor.decks, effects, deckFrames,
                    moment = { frame -> TransitionScene.moment(clips, playhead, frame, deckOf) },
                    // Where a clip's own picture lies in the frame: all of it when cropped, else fitted inside it.
                    content = { index, frame ->
                        val source = sizes[clips[index].sourceID]
                        val framed = cropping?.first == clips[index].id || clips[index].crop != null
                        if (framed || source == null) FrameRect(0.0, 0.0, frame.width, frame.height) else {
                            val fitted = minOf(frame.width / source.width, frame.height / source.height)
                            FrameRect((frame.width - source.width * fitted) / 2, (frame.height - source.height * fitted) / 2, source.width * fitted, source.height * fitted)
                        }
                    },
                )
                // While a title is being worked on, a drag on the picture moves it and a pinch resizes it.
                val work by rememberUpdatedState(titling)
                var dragged by remember { mutableStateOf<Offset?>(null) } // where the drag is, before snapping to the middle
                val moving = if (titling == null) Modifier else Modifier.pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val now = work ?: return@detectTransformGestures
                        fun snapped(value: Float) = if (abs(value - 0.5f) < 0.015f) 0.5 else value.toDouble()
                        val from = dragged?.takeIf { snapped(it.x) == now.title.x && snapped(it.y) == now.title.y } ?: Offset(now.title.x.toFloat(), now.title.y.toFloat())
                        val to = Offset((from.x + pan.x / size.width).coerceIn(0f, 1f), (from.y + pan.y / size.height).coerceIn(0f, 1f))
                        dragged = to
                        titling = now.copy(title = now.title.copy(x = snapped(to.x), y = snapped(to.y), size = (now.title.size * zoom).coerceIn(0.02, 0.3)))
                    }
                }
                Canvas(Modifier.fillMaxSize().then(moving)) {
                    if (sampling) return@Canvas
                    drawIntoCanvas { target ->
                        titlePainter.draw(target.nativeCanvas, size.width, size.height, shownTitles)
                        captioned.subtitles?.let { captionLayout?.draw(target.nativeCanvas, size.width, size.height, playhead, it) }
                    }
                }
            }
            if (clips.isEmpty()) Text(stringResource(R.string.editor_timeline_empty), color = Muted)
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("${preciseLabel(playhead)} / ${preciseLabel(duration)}", color = Color.White, fontSize = 14.sp)
            val tight = PaddingValues(horizontal = 6.dp)
            val small = Modifier.defaultMinSize(minWidth = 40.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Bookmarks: step to the previous or next one, and set or clear one at the playhead.
                val before = TimelineEditing.previousBookmark(clips, playhead)
                val after = TimelineEditing.nextBookmark(clips, playhead)
                val under = location?.let { TimelineEditing.bookmarkAt(clips, it.index, sourceTime) }
                val previousLabel = stringResource(R.string.editor_bookmark_previous)
                val toggleLabel = stringResource(if (under == null) R.string.editor_bookmark_add else R.string.editor_bookmark_remove)
                val nextLabel = stringResource(R.string.editor_bookmark_next)
                TextButton(onClick = { before?.let(jump) }, small.semantics { contentDescription = previousLabel }, enabled = before != null, contentPadding = tight) { Icon(Icons.Filled.SkipPrevious, null) }
                TextButton(
                    onClick = { location?.let { commit(TimelineEditing.toggleBookmark(edit, it.index, sourceTime), playhead) } },
                    small.semantics { contentDescription = toggleLabel }, enabled = location != null, contentPadding = tight,
                ) { Icon(if (under == null) Icons.Outlined.BookmarkAdd else Icons.Filled.Bookmark, null, tint = if (location != null) BookmarkColor else LocalContentColor.current) }
                TextButton(onClick = { after?.let(jump) }, small.semantics { contentDescription = nextLabel }, enabled = after != null, contentPadding = tight) { Icon(Icons.Filled.SkipNext, null) }
            }
            TextButton(
                onClick = {
                    auditionEnd = null
                    if (editor.playing) editor.pause() else editor.play(if (playhead >= duration - 0.05) 0.0 else playhead)
                },
                enabled = clips.isNotEmpty(), contentPadding = tight,
            ) { Icon(if (editor.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(if (editor.playing) R.string.editor_pause else R.string.common_play), Modifier.size(32.dp)) }
            Row {
                val zoomOutLabel = stringResource(R.string.editor_zoom_timeline_out)
                val zoomInLabel = stringResource(R.string.editor_zoom_timeline_in)
                TextButton(onClick = { scale = (scale / 1.5f).coerceAtLeast(6f) }, small, contentPadding = tight) { Text("−", fontSize = 20.sp, modifier = Modifier.semantics { contentDescription = zoomOutLabel }) }
                TextButton(onClick = { scale = (scale * 1.5f).coerceAtMost(480f) }, small, contentPadding = tight) { Text("+", fontSize = 20.sp, modifier = Modifier.semantics { contentDescription = zoomInLabel }) }
            }
        }
        cleaningProgress?.let {
            Text(stringResource(R.string.editor_reducing_noise, (it * 100).roundToInt()), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, bottom = 2.dp))
        }
        Timeline(
            shown, files, sounds, names, { editor.pause(); if (subtitling == null && titling == null) chosenSound = it }, trim?.index ?: location?.index, trim, pixelsPerSecond,
            shift = trim?.takeIf { it.leading }?.let { ((it.clip.start - clips[it.index].start) * pixelsPerSecond).toFloat() } ?: 0f,
            scroll = scroll, touched = { editor.pause() },
            beginTrim = { leading -> if (subtitling == null && titling == null) location?.let { trim = Trim(it.index, leading, clips[it.index]) } },
            dragTrim = dragTrim, endTrim = endTrim,
        )
        val framing = cropping
        if (framing != null) {
            CropPanel(
                framing.second, removable = clips.firstOrNull { it.id == framing.first }?.crop != null,
                change = { cropping = framing.first to it },
                done = { crop ->
                    val index = clips.indexOfFirst { it.id == framing.first }
                    cropping = null
                    commit(TimelineEditing.setCrop(edit, index, crop), playhead)
                },
                cancel = { cropping = null },
            )
        } else if (titling != null) {
            val work = titling!!
            val index = clips.indexOfFirst { it.id == work.clipID }
            val clip = clips.getOrNull(index)
            if (clip == null) titling = null else TitlePanel(
                draft = work.title, existing = work.existing, change = { titling = work.copy(title = it) },
                // An existing title may reach past a clip that was trimmed or split after it was placed.
                bounds = minOf(clip.start, work.title.start)..maxOf(clip.end, work.title.end), rate = clip.rate,
                timelineTime = { TimelineEditing.clipStart(clips, index) + clip.offset(it) },
                replay = { exit, title ->
                    // On the clips as they would be with the title as it is at this moment.
                    val moved = TimelineEditing.setTitle(edit, index, title)?.clips ?: titled
                    val shows = TimelineEditing.clipStart(clips, index) + clip.offset(maxOf(clip.start, title.start))
                    val run = TitleMotionRanges(moved).range(title, shows)
                    val motion = title.motion
                    if (run != null && motion != null && title.text.isNotBlank()) {
                        val window = motion.previewWindow(exit, run.endInclusive - run.start)
                        val from = (run.start + window.start).coerceIn(0.0, duration)
                        val to = (run.start + window.endInclusive).coerceIn(0.0, duration)
                        if (to > from) {
                            auditionEnd = to
                            playhead = from
                            editor.play(from)
                        }
                    }
                },
                sample = samplePicture,
                // One undo step for everything done in the tool. A motion that does nothing is not saved.
                done = {
                    val title = work.title.copy(text = work.title.text.trim(), motion = work.title.motion?.takeIf { it.active })
                    titling = null
                    auditionEnd = null
                    commit(TimelineEditing.setTitle(edit, index, title), playhead)
                },
                remove = {
                    titling = null
                    auditionEnd = null
                    commit(TimelineEditing.removeTitle(edit, index, work.title.id), playhead)
                },
                cancel = { editor.pause(); auditionEnd = null; titling = null },
                modifier = Modifier.weight(1.5f),
            )
        } else if (subtitling != null) {
            SubtitlesPanel(
                saved = edit, draft = subtitling ?: edit, change = { subtitling = it },
                files = files, layout = captionLayout, playhead = playhead, playing = editor.playing,
                seek = jump, working = { subtitleWork = it },
                // One undo step for everything done in the tool.
                apply = { val next = subtitling; subtitling = null; if (next != null && next != edit) commit(next, playhead) },
                close = { subtitling = null },
                modifier = Modifier.weight(1.5f),
            )
        } else {
            // Tools act on the clip under the playhead. Short rows, so nothing is cut off at any font size.
            val tight = PaddingValues(horizontal = 8.dp)
            val compact = Modifier.defaultMinSize(minWidth = 48.dp)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), Arrangement.SpaceEvenly) {
                TextButton(onClick = { commit(TimelineEditing.split(edit, playhead), playhead) }, compact,
                    enabled = TimelineEditing.split(edit, playhead) != null, contentPadding = tight) { Text(stringResource(R.string.editor_split)) }
                TextButton(onClick = { editor.pause(); editingAudio = true }, compact, enabled = location != null, contentPadding = tight) { Text(stringResource(R.string.editor_sound)) }
                TextButton(onClick = { editor.pause(); editingColor = true }, compact, enabled = location != null, contentPadding = tight) { Text(stringResource(R.string.editor_color)) }
                TextButton(onClick = { editor.pause(); current?.let { cropping = it.id to (it.crop ?: CropSettings()) } }, compact,
                    enabled = current != null && sizes[current.sourceID] != null, contentPadding = tight) { Text(stringResource(R.string.editor_crop)) }
                TextButton(onClick = { editor.pause(); pacing = true }, compact, enabled = location != null, contentPadding = tight) { Text(stringResource(R.string.editor_speed)) }
            }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                TextButton(
                    onClick = {
                        editor.pause()
                        current?.let { clip ->
                            val existing = titles.firstOrNull()
                            // A new title starts at the playhead and stays three seconds of the movie, or as long as the clip lasts.
                            val end = minOf(clip.end, sourceTime + 3 * clip.rate)
                            val start = if (end - sourceTime < 0.1) maxOf(clip.start, end - 0.5 * clip.rate) else sourceTime
                            titling = Titling(clip.id, existing ?: TextOverlay(text = "", start = start, end = end), existing)
                        }
                    },
                    compact, enabled = location != null, contentPadding = tight,
                ) { Text(stringResource(R.string.editor_title)) }
                TextButton(onClick = { editor.pause(); editingTransition = true }, compact,
                    enabled = location != null && location.index < clips.lastIndex, contentPadding = tight) { Text(stringResource(R.string.editor_transition)) }
                TextButton(onClick = { editor.pause(); findingPauses = true }, compact, enabled = location != null, contentPadding = tight) { Text(stringResource(R.string.editor_pauses)) }
                TextButton(onClick = { editor.pause(); pickSound.launch("audio/*") }, compact, enabled = clips.isNotEmpty(), contentPadding = tight) { Text(stringResource(R.string.editor_add_audio)) }
                TextButton(onClick = { editor.pause(); subtitling = edit }, compact, enabled = clips.isNotEmpty(), contentPadding = tight) {
                    Icon(Icons.Outlined.Subtitles, stringResource(R.string.editor_subtitles), tint = if (edit.subtitles != null) TitleColor else LocalContentColor.current)
                }
            }
            if (adding) {
                val unused = UnusedTakes.of(project)
                AlertDialog(
                    onDismissRequest = { adding = false },
                    title = { Text(stringResource(R.string.add_video_title, timeLabel(playhead))) },
                    text = {
                        Column {
                            TextButton(onClick = { adding = false; store.recordHere(project.id, playhead); record() }) {
                                Icon(Icons.Filled.FiberManualRecord, null, Modifier.size(18.dp), tint = RecordRed)
                                Text(stringResource(R.string.editor_record_here), Modifier.padding(start = 8.dp))
                            }
                            if (unused.isNotEmpty()) TextButton(onClick = { adding = false; choosing = emptyList() }) {
                                Icon(Icons.Outlined.VideoLibrary, null, Modifier.size(18.dp))
                                Text(stringResource(R.string.add_video_unused, unused.size), Modifier.padding(start = 8.dp))
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.common_cancel)) } },
                )
            }
            choosing?.let { chosen ->
                val unused = UnusedTakes.of(project)
                AlertDialog(
                    onDismissRequest = { choosing = null },
                    title = { Text(stringResource(R.string.unused_title)) },
                    text = {
                        // Tap to add a take to the playback order, tap again to take it out.
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            Text(stringResource(R.string.unused_note), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                            for (take in unused) {
                                val place = chosen.indexOf(take.id)
                                val file = store.file(project, take)
                                val image by produceState<Bitmap?>(null, file) { value = Thumbnails.frame(file, 0.0) }
                                Row(
                                    Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { choosing = if (place >= 0) chosen - take.id else chosen + take.id }.padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.size(56.dp).background(Color(0xFF2A2D33), RoundedCornerShape(6.dp)).clipToBounds()) {
                                        image?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                    }
                                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                        Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(take.recordedAt)), maxLines = 2)
                                        Text(preciseLabel(take.duration), color = Muted, fontSize = 13.sp)
                                    }
                                    if (place >= 0) Text("${place + 1}", fontWeight = FontWeight.Bold, color = BookmarkColor, modifier = Modifier.padding(horizontal = 8.dp))
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(enabled = chosen.isNotEmpty(), onClick = {
                            val takes = chosen.mapNotNull { id -> unused.firstOrNull { it.id == id } }
                            choosing = null
                            store.addUnused(project.id, takes, playhead)?.let { start -> playhead = start; editor.seek(start) }
                                ?: run { store.error = unusedFailed }
                        }) { Text(if (chosen.isEmpty()) stringResource(R.string.unused_insert) else stringResource(R.string.unused_insert_count, chosen.size)) }
                    },
                    dismissButton = { TextButton(onClick = { choosing = null }) { Text(stringResource(R.string.common_cancel)) } },
                )
            }
            if (pacing && location != null && current != null) {
                AlertDialog(
                    onDismissRequest = { pacing = false },
                    title = { Text(stringResource(R.string.speed_title)) },
                    text = {
                        Column {
                            // Two rows, so every speed is in view on a narrow phone.
                            for (rates in listOf(listOf(0.25, 0.5, 0.75), listOf(1.0, 1.5, 2.0, 4.0))) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (rate in rates) {
                                    FilterChip(current.rate == rate, onClick = {
                                        // The same frame stays under the playhead.
                                        val frame = current.sourceTime(location.offset)
                                        val next = TimelineEditing.setSpeed(edit, location.index, rate)
                                        commit(next, next?.let { TimelineEditing.clipStart(it.clips, location.index) + it.clips[location.index].offset(frame) } ?: playhead)
                                        pacing = false
                                    }, label = { Text(speedLabel(rate)) })
                                }
                            }
                            Text(stringResource(R.string.speed_note), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                        }
                    },
                    confirmButton = { TextButton(onClick = { pacing = false }) { Text(stringResource(R.string.common_cancel)) } },
                )
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), Arrangement.SpaceEvenly) {
                for ((label, step) in listOf(stringResource(R.string.editor_move_earlier) to -1, stringResource(R.string.editor_move_later) to 1)) {
                    val description = stringResource(if (step < 0) R.string.editor_move_clip_earlier else R.string.editor_move_clip_later)
                    val target = location?.let { it.index + step }
                    TextButton(
                        onClick = {
                            val next = TimelineEditing.move(edit, location!!.index, target!!)
                            commit(next, next?.let { TimelineEditing.clipStart(it.clips, target) + location.offset } ?: playhead)
                        },
                        compact.semantics { contentDescription = description },
                        enabled = target != null && target in clips.indices, contentPadding = tight,
                    ) { Text(label) }
                }
                // New takes go in at the playhead; Stop in the recorder ends the run.
                TextButton(onClick = { editor.pause(); adding = true }, compact, contentPadding = tight) { Text(stringResource(R.string.editor_add_video)) }
                TextButton(onClick = { location?.let { commit(TimelineEditing.delete(edit, it.index), TimelineEditing.clipStart(clips, it.index)) } }, compact,
                    enabled = location != null, contentPadding = tight) { Icon(Icons.Outlined.Delete, stringResource(R.string.common_delete), tint = if (location != null) RecordRed else LocalContentColor.current) }
            }
        }
    }

    store.importing?.let { step ->
        AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text(stringResource(R.string.common_importing)) }, text = { Text(step) })
    }
    if (editingColor && location != null && current != null) {
        ColorDialog(
            current.color ?: ColorSettings(),
            importLut = { uri -> store.importLut(project.id, uri) },
            save = { commit(TimelineEditing.setColor(edit, location.index, it), playhead); editingColor = false },
            dismiss = { editingColor = false },
        )
    }
    sounds.firstOrNull { it.id == chosenSound }?.let { item ->
        if (shapingSound) {
            SoundDialog(
                item.audio ?: ClipAudio(), canReduceNoise = false,
                save = { commit(TimelineEditing.setAudioItemSound(edit, item.id, it), playhead); shapingSound = false; chosenSound = null },
                dismiss = { shapingSound = false },
            )
        } else {
            AudioItemDialog(
                name = names[item.sourceID] ?: soundName, item = item,
                moved = TimelineEditing.moveAudio(edit, item.id, playhead),
                startHere = TimelineEditing.trimAudio(edit, item.id, playhead, leading = true),
                endHere = TimelineEditing.trimAudio(edit, item.id, playhead, leading = false),
                removed = TimelineEditing.removeAudio(edit, item.id),
                apply = { commit(it, playhead); chosenSound = null },
                shape = { shapingSound = true },
                dismiss = { chosenSound = null },
            )
        }
    }

    if (findingPauses && location != null && current != null) {
        PausesDialog(
            files.getValue(current.sourceID), current,
            remove = { cuts ->
                commit(TimelineEditing.removeIntervals(edit, location.index, cuts), TimelineEditing.clipStart(clips, location.index))
                findingPauses = false
            },
            dismiss = { findingPauses = false },
        )
    }
    if (editingAudio && location != null && current != null) {
        SoundDialog(
            current.audio ?: ClipAudio(), canReduceNoise = true,
            save = { commit(TimelineEditing.setAudio(edit, location.index, it), playhead); editingAudio = false },
            dismiss = { editingAudio = false },
        )
    }
    if (choosingExport) {
        ExportSettingsDialog(
            project.exportSettings, canvas,
            start = { settings ->
                choosingExport = false
                store.setExportSettings(project.id, settings)
                exporter.start(project.copy(exportSettings = settings))
            },
            dismiss = { choosingExport = false },
        )
    }
    if (editingTransition && location != null) {
        TransitionDialog(
            current = TimelineEditing.transition(clips, location.index),
            choose = { seconds, style ->
                // The timeline changes length; stay at the start of the clip being joined.
                commit(TimelineEditing.setTransition(edit, location.index, seconds, style), TimelineEditing.clipStart(clips, location.index) + minOf(location.offset, clips[location.index].duration / 2))
                editingTransition = false
            },
            dismiss = { editingTransition = false },
        )
    }

    ExportDialogs(exporter, context) { store.error = it }
}

/** A trim in progress: the clip being changed and its uncommitted bounds. */
private data class Trim(val index: Int, val leading: Boolean, val clip: TimelineClip)

@Composable
private fun Timeline(
    clips: List<TimelineClip>, files: Map<String, File>, sounds: List<AudioItem>, names: Map<String, String>, chooseSound: (String) -> Unit,
    current: Int?, trim: Trim?,
    pixelsPerSecond: Float, shift: Float, scroll: ScrollState,
    touched: () -> Unit, beginTrim: (leading: Boolean) -> Unit, dragTrim: (pixels: Float) -> Unit, endTrim: () -> Unit,
) {
    val density = LocalDensity.current
    // The gesture below outlives recompositions; always call the latest callbacks.
    val onTouched by rememberUpdatedState(touched)
    val onBeginTrim by rememberUpdatedState(beginTrim)
    val onDragTrim by rememberUpdatedState(dragTrim)
    val onEndTrim by rememberUpdatedState(endTrim)
    // Where a touch grabs a trim handle of the current clip instead of scrolling.
    val handle by rememberUpdatedState { x: Float, half: Float ->
        current?.takeIf { it in clips.indices }?.let { index ->
            val left = half + (TimelineEditing.clipStart(clips, index) * pixelsPerSecond).toFloat() + shift - scroll.value
            val right = left + (clips[index].duration * pixelsPerSecond).toFloat()
            // Just the visible grip and a little around it; the rest of the clip scrolls.
            val (outside, inside) = with(density) { 6.dp.toPx() to 14.dp.toPx() }
            val leading = x in (left - outside)..(left + inside)
            val trailing = x in (right - inside)..(right + outside)
            when {
                leading && trailing -> abs(x - left) < abs(x - right)
                leading -> true
                trailing -> false
                else -> null
            }
        }
    }
    BoxWithConstraints(
        // Clips at a fixed height from the top; audio tracks appear under them when there is sound.
        Modifier.fillMaxWidth().height(120.dp + if (sounds.isEmpty()) 0.dp else TrackHeight * AudioItem.TRACKS).background(Panel).pointerInput(Unit) {
            // The handles move under the finger, so the drag is tracked here,
            // in a coordinate space that stays still.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                onTouched() // scrubbing pauses playback; it stays paused after release
                // Above and below the clips the timeline only scrolls.
                if (down.position.y < StripTop.toPx() || down.position.y > (StripTop + TileSize + WaveHeight).toPx()) return@awaitEachGesture
                val leading = handle(down.position.x, size.width / 2f) ?: return@awaitEachGesture
                down.consume()
                onBeginTrim(leading)
                try {
                    var total = 0f
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        total += change.positionChange().x
                        change.consume()
                        if (!change.pressed) break
                        onDragTrim(total)
                    }
                } finally {
                    onEndTrim()
                }
            }
        },
    ) {
        val viewport = constraints.maxWidth
        Column(Modifier.horizontalScroll(scroll).padding(horizontal = maxWidth / 2).padding(top = StripTop).fillMaxHeight()) {
            val width = with(density) { (TimelineEditing.duration(clips) * pixelsPerSecond).toFloat().toDp() }
            // Clips sit at their timeline position; a dissolve draws the incoming clip over the end of the previous one.
            Box(Modifier.width(width).height(TileSize + WaveHeight).offset { IntOffset(shift.roundToInt(), 0) }, Alignment.CenterStart) {
                clips.forEachIndexed { index, clip ->
                    val offset = (TimelineEditing.clipStart(clips, index) * pixelsPerSecond).toFloat()
                    key(clip.id) {
                        ClipStrip(
                            clip, files.getValue(clip.sourceID), index == current,
                            fades = index < clips.lastIndex && TimelineEditing.join(clips, index + 1)?.let { it.timing.overlap <= 0 } == true,
                            dissolve = (TimelineEditing.overlap(clips, index - 1) * pixelsPerSecond).toFloat(),
                            offset, pixelsPerSecond, viewport, scroll,
                        )
                    }
                }
            }
            // Sound on its own tracks. It stays where it is when the clips above are edited.
            val soundName = stringResource(R.string.editor_sound_default_name)
            val mutedNote = stringResource(R.string.sound_muted)
            val editSoundLabel = stringResource(R.string.editor_edit_sound)
            if (sounds.isNotEmpty()) for (track in 0 until AudioItem.TRACKS) {
                Box(Modifier.width(width).height(TrackHeight).padding(top = 3.dp)) {
                    for (item in sounds) {
                        if (item.track != track) continue
                        key(item.id) {
                            val note = item.audio?.let { if (it.muted) " · $mutedNote" else if (it.volumeDB != 0.0) " · ${it.volumeDB.roundToInt()} dB" else "" } ?: ""
                            Text(
                                (names[item.sourceID] ?: soundName) + note, color = Color.Black, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.offset { IntOffset((item.start * pixelsPerSecond).roundToInt(), 0) }
                                    .width(with(density) { (item.duration * pixelsPerSecond).toFloat().toDp() }).fillMaxHeight()
                                    .background(SoundColor, RoundedCornerShape(4.dp))
                                    .clickable(onClickLabel = editSoundLabel) { chooseSound(item.id) }.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.Center).width(2.dp).fillMaxHeight().background(Color.White))
        if (clips.isNotEmpty()) {
            val label = trim?.let { stringResource(R.string.editor_clip_duration, it.index + 1, preciseLabel(it.clip.duration)) }
                ?: stringResource(R.string.editor_clip_position, (current ?: 0) + 1, clips.size)
            Text(label, color = if (trim != null) Color.White else Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp))
        }
    }
}

@Composable
private fun ClipStrip(
    clip: TimelineClip, file: File, current: Boolean, fades: Boolean, dissolve: Float, offset: Float,
    pixelsPerSecond: Float, viewport: Int, scroll: ScrollState,
) {
    val density = LocalDensity.current
    val width = (clip.duration * pixelsPerSecond).toFloat()
    val tile = with(density) { TileSize.toPx() }
    val count = ceil(width / tile).toInt()
    // Only the tiles near the screen are composed and decoded.
    val visible by remember(offset, count, tile, viewport) {
        derivedStateOf {
            val first = ((scroll.value - viewport - offset) / tile).toInt().coerceIn(0, count)
            val last = ceil((scroll.value + viewport - offset) / tile).toInt().coerceIn(0, count)
            first until last
        }
    }
    val peaks by produceState<ByteArray?>(null, file) { value = Waveforms.peaks(file) }
    val loudness = (clip.audio?.gain ?: 1.0).toFloat()
    Box(
        Modifier.offset { IntOffset(offset.roundToInt(), 0) }.width(with(density) { width.toDp() })
            .border(if (current) 2.dp else 1.dp, if (current) Color.White else Color.Black),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(TileSize).clipToBounds().background(Color(0xFF2A2D33))) {
                for (index in visible) {
                    key(index) {
                        val time = clip.sourceTime((index + 0.5) * tile / pixelsPerSecond).coerceAtMost(clip.end)
                        val image by produceState<Bitmap?>(null, file, (time * 2).roundToInt()) { value = Thumbnails.frame(file, time) }
                        image?.let {
                            Image(it.asImageBitmap(), null, Modifier.offset { IntOffset((index * tile).roundToInt(), 0) }.size(TileSize), contentScale = ContentScale.Crop)
                        }
                    }
                }
            }
            Canvas(Modifier.fillMaxWidth().height(WaveHeight).background(Color(0xFF14161A))) {
                val levels = peaks ?: return@Canvas
                val step = 3.dp.toPx()
                // Draw only the columns near the screen.
                var x = ((scroll.value - viewport - offset).coerceAtLeast(0f) / step).toInt() * step
                val last = minOf(size.width, scroll.value + viewport - offset)
                while (x < last) {
                    val from = clip.sourceTime((x / pixelsPerSecond).toDouble())
                    val level = WaveformBuilder.level(levels, from, minOf(clip.end, from + step / pixelsPerSecond * clip.rate))
                    // Drawn at the clip's own level: flat when muted, taller when boosted.
                    val bar = (sqrt(level * loudness) * size.height).coerceIn(1.dp.toPx(), size.height)
                    drawRect(if (loudness == 0f) Muted else WaveColor, Offset(x, (size.height - bar) / 2), Size(step * 0.6f, bar))
                    x += step
                }
            }
        }
        // Titles as labelled bars over the footage they cover.
        for (title in clip.texts.orEmpty()) {
            val from = maxOf(title.start, clip.start)
            val to = minOf(title.end, clip.end)
            if (to <= from) continue
            Text(
                title.text, color = Color.Black, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Clip,
                modifier = Modifier.offset { IntOffset((clip.offset(from) * pixelsPerSecond).roundToInt(), 0) }
                    .width(with(density) { ((to - from) / clip.rate * pixelsPerSecond).toFloat().toDp() })
                    .background(TitleColor, RoundedCornerShape(3.dp))
                    // Clear of the trim grip when the title starts with the clip.
                    .padding(start = if (current && from == clip.start) 13.dp else 3.dp, end = 3.dp),
            )
        }
        // What has been done to the picture, named over it.
        listOfNotNull(clip.color?.label(), stringResource(R.string.editor_tag_crop).takeIf { clip.crop != null }, clip.speed?.let { speedLabel(it.rate) }).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { tags ->
            Text(tags, color = Color.Black, fontSize = 10.sp, maxLines = 1,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 12.dp).background(Color(0xFFB6C2FF), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp))
        }
        // What has been done to the sound, where the waveform is.
        clip.audio?.let { audio ->
            val note = listOfNotNull(
                if (audio.muted) stringResource(R.string.sound_muted) else audio.volumeDB.takeIf { it != 0.0 }?.let { (if (it > 0) "+" else "") + "${it.roundToInt()} dB" },
                stringResource(R.string.editor_tag_fade).takeIf { audio.fadeIn > 0 || audio.fadeOut > 0 },
                stringResource(R.string.editor_tag_clean).takeIf { audio.denoise > 0 },
            ).joinToString(" · ")
            if (note.isNotEmpty()) {
                Text(note, color = Color.White, fontSize = 10.sp, maxLines = 1,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp).background(Color(0xCC000000), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp))
            }
        }
        // Bookmarks as flags on the footage they mark.
        for (mark in clip.bookmarks.orEmpty()) {
            if (mark.time < clip.start || mark.time >= clip.end) continue
            Box(Modifier.offset { IntOffset((clip.offset(mark.time) * pixelsPerSecond).roundToInt() - 1.dp.roundToPx(), 0) }
                .width(3.dp).height(TileSize + WaveHeight).background(BookmarkColor))
        }
        // The part of this clip that dissolves in over the previous one.
        if (dissolve > 0) {
            Box(Modifier.width(with(density) { dissolve.toDp() }).height(TileSize + WaveHeight).background(Color.White.copy(alpha = 0.35f)))
            Text(stringResource(R.string.transition_tag_dissolve), color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp).background(Color.White, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp))
        }
        // A fade into the next clip.
        if (fades) {
            Text(stringResource(R.string.transition_tag_fade), color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp).background(Color.White, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp))
        }
        if (current) {
            val grip = Modifier.width(minOf(10.dp, with(density) { (width / 2).toDp() })).height(TileSize + WaveHeight).background(Color.White, RoundedCornerShape(3.dp))
            Box(grip.align(Alignment.CenterStart))
            Box(grip.align(Alignment.CenterEnd))
        }
    }
}

/** The look names of the core module, which only has them in English. */
private val ColorLook.label: Int
    get() = when (this) {
        ColorLook.Natural -> R.string.color_look_natural
        ColorLook.Warm -> R.string.color_look_warm
        ColorLook.Cool -> R.string.color_look_cool
        ColorLook.Cinema -> R.string.color_look_cinema
        ColorLook.Mono -> R.string.color_look_mono
        ColorLook.Portrait -> R.string.color_look_portrait
        ColorLook.Daylight -> R.string.color_look_daylight
        ColorLook.GoldenHour -> R.string.color_look_golden_hour
        ColorLook.MutedFilm -> R.string.color_look_muted_film
        ColorLook.RichContrast -> R.string.color_look_rich_contrast
        ColorLook.Pastel -> R.string.color_look_pastel
        ColorLook.Night -> R.string.color_look_night
        ColorLook.Silver -> R.string.color_look_silver
        ColorLook.Ink -> R.string.color_look_ink
    }

/** As [ColorSettings.title], from the resources. */
@Composable
private fun ColorSettings.label(): String =
    imported?.name ?: stringResource(if (look == ColorLook.Natural && hasBalance) R.string.color_balance else look.label)

@Composable
private fun <T> Choices(label: String, options: List<Pair<String, T>>, selected: T, choose: (T) -> Unit) {
    Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), Arrangement.spacedBy(8.dp)) {
        options.forEach { (name, value) -> FilterChip(selected = value == selected, onClick = { choose(value) }, label = { Text(name) }) }
    }
}

/**
 * Reframes a clip in place of the tools, so the picture stays visible and
 * follows the sliders. A crop fills the movie's frame with part of the clip.
 */
@Composable
private fun CropPanel(crop: CropSettings, removable: Boolean, change: (CropSettings) -> Unit, done: (CropSettings?) -> Unit, cancel: () -> Unit) {
    @Composable
    fun Level(label: String, shown: String, value: Double, range: ClosedFloatingPointRange<Float>, set: (Double) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$label  $shown", color = Color.White, fontSize = 13.sp, modifier = Modifier.width(120.dp))
            Slider(value = value.toFloat(), onValueChange = { set(it.toDouble()) }, valueRange = range,
                modifier = Modifier.weight(1f).semantics { contentDescription = label })
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Level(stringResource(R.string.crop_zoom), "%.1f×".format(crop.zoom), crop.zoom, 1f..CropSettings.MAXIMUM_ZOOM.toFloat()) { change(crop.copy(zoom = (it * 10).roundToInt() / 10.0)) }
        Level(stringResource(R.string.crop_left_right), "${(crop.x * 100).roundToInt()}", crop.x, 0f..1f) { change(crop.copy(x = (it * 100).roundToInt() / 100.0)) }
        Level(stringResource(R.string.crop_up_down), "${(crop.y * 100).roundToInt()}", crop.y, 0f..1f) { change(crop.copy(y = (it * 100).roundToInt() / 100.0)) }
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
            TextButton(onClick = cancel) { Text(stringResource(R.string.common_cancel)) }
            if (removable) TextButton(onClick = { done(null) }) { Text(stringResource(R.string.crop_remove), color = RecordRed) }
            Button(onClick = { done(crop) }) { Text(stringResource(R.string.common_done)) }
        }
    }
}

/** The clip's color: a look or an imported LUT at some strength, over exposure, warmth and tint. */
@Composable
private fun ColorDialog(saved: ColorSettings, importLut: suspend (Uri) -> ImportedLUT?, save: (ColorSettings) -> Unit, dismiss: () -> Unit) {
    var color by remember { mutableStateOf(saved) }
    val scope = rememberCoroutineScope()
    val pickLut = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch { importLut(uri)?.let { color = color.copy(imported = it) } }
    }
    @Composable
    fun Level(label: String, value: Double, range: ClosedFloatingPointRange<Float>, shown: String, change: (Double) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), Arrangement.SpaceBetween) {
            Text(label, color = Muted, fontSize = 13.sp)
            Text(shown, color = Color.White, fontSize = 13.sp)
        }
        // Steps of a twentieth of the range: fine enough to see, coarse enough to hit zero again.
        Slider(value = value.toFloat(), onValueChange = { change((it * 20 / (range.endInclusive - range.start)).roundToInt() * (range.endInclusive - range.start) / 20.0) },
            valueRange = range, modifier = Modifier.semantics { contentDescription = label })
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.color_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val lut = color.imported
                if (lut == null) {
                    Choices(stringResource(R.string.color_look), ColorLook.entries.map { stringResource(it.label) to it }, color.look) { color = color.copy(look = it) }
                } else {
                    Text(stringResource(R.string.color_lut_name, lut.name), color = Color.White, modifier = Modifier.padding(top = 12.dp))
                }
                Row {
                    TextButton(onClick = { pickLut.launch("*/*") }) { Text(stringResource(if (lut == null) R.string.color_import_lut else R.string.color_choose_another_lut)) }
                    if (lut != null) TextButton(onClick = { color = color.copy(imported = null) }) { Text(stringResource(R.string.color_use_look)) }
                }
                Level(stringResource(R.string.color_strength), color.amount, 0f..1f, "${(color.amount * 100).roundToInt()}%") { color = color.copy(amount = it) }
                // A correction left at zero is stored as nothing, as in the original.
                val exposure = color.exposure ?: 0.0
                val warmth = color.warmth ?: 0.0
                val tint = color.tint ?: 0.0
                Level(stringResource(R.string.color_exposure), exposure, -2f..2f, "%+.1f".format(exposure)) { color = color.copy(exposure = it.takeIf { it != 0.0 }) }
                Level(stringResource(R.string.color_warmth), warmth, -1f..1f, "%+.0f".format(warmth * 100)) { color = color.copy(warmth = it.takeIf { it != 0.0 }) }
                Level(stringResource(R.string.color_tint), tint, -1f..1f, "%+.0f".format(tint * 100)) { color = color.copy(tint = it.takeIf { it != 0.0 }) }
            }
        },
        confirmButton = { TextButton(onClick = { save(color) }) { Text(stringResource(R.string.common_save)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { color = ColorSettings(id = color.id) }, enabled = !color.isIdentity) { Text(stringResource(R.string.common_reset)) }
                TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}

/**
 * Finds the pauses in the clip under the playhead and offers to cut them out.
 * The threshold between speech and silence is estimated from the recording.
 */
@Composable
private fun PausesDialog(file: File, clip: TimelineClip, remove: (List<SourceInterval>) -> Unit, dismiss: () -> Unit) {
    var minimum by remember { mutableDoubleStateOf(0.6) }
    var progress by remember { mutableFloatStateOf(0f) }
    // null while measuring; then the measurements, or nothing when the clip has no usable sound.
    val measured by produceState<Pair<SourceWaveform?, Boolean>>(null to false, file) {
        value = PauseLevels.measure(file) { progress = it } to true
    }
    val wave = measured.first
    val settings = PauseSettings(minimum = minimum)
    val cuts = remember(wave, minimum, clip) { wave?.let { PauseDetector.detect(it, clip, settings) }.orEmpty() }
    val shorter = clip.duration - PauseDetector.keptIntervals(clip, cuts).filter { it.duration >= TimelineEditing.MINIMUM_CLIP }.sumOf { it.duration }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.pauses_title)) },
        text = {
            Column {
                when {
                    !measured.second -> {
                        Text(stringResource(R.string.pauses_listening), color = Color.White)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                    }
                    wave == null -> Text(stringResource(R.string.pauses_no_sound), color = Color.White)
                    else -> {
                        Text(
                            if (cuts.isEmpty()) stringResource(R.string.pauses_none_found)
                            else pluralStringResource(R.plurals.pauses_found, cuts.size, cuts.size, preciseLabel(shorter)),
                            color = Color.White,
                        )
                        Text(stringResource(R.string.pauses_silence_level, PauseDetector.levelDB(wave, settings).roundToInt()), color = Muted, fontSize = 13.sp)
                        Choices(stringResource(R.string.pauses_shortest), listOf("0.4 s" to 0.4, "0.6 s" to 0.6, "1 s" to 1.0, "2 s" to 2.0), minimum) { minimum = it }
                        Text(stringResource(R.string.pauses_note), color = Muted, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { remove(cuts) }, enabled = cuts.isNotEmpty() && shorter > 0) { Text(stringResource(R.string.common_remove)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** What can be done to a sound on an audio track. Each edit is given ready, or null when it does not apply at the playhead. */
@Composable
private fun AudioItemDialog(
    name: String, item: AudioItem, moved: EditState?, startHere: EditState?, endHere: EditState?, removed: EditState?,
    apply: (EditState?) -> Unit, shape: () -> Unit, dismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text(stringResource(R.string.sound_item_range, preciseLabel(item.start), preciseLabel(item.end), item.track + 1), color = Muted, fontSize = 13.sp)
                TextButton(onClick = shape) { Text(stringResource(R.string.sound_volume_and_fades)) }
                TextButton(onClick = { apply(moved) }, enabled = moved != null) { Text(stringResource(R.string.sound_move_to_playhead)) }
                TextButton(onClick = { apply(startHere) }, enabled = startHere != null) { Text(stringResource(R.string.sound_cut_before)) }
                TextButton(onClick = { apply(endHere) }, enabled = endHere != null) { Text(stringResource(R.string.sound_cut_after)) }
                TextButton(onClick = { apply(removed) }) { Text(stringResource(R.string.common_remove), color = RecordRed) }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_done)) } },
    )
}

/** The clip's own sound: level from silence to a 12 dB boost, mute, and a fade at each end. */
@Composable
private fun SoundDialog(saved: ClipAudio, canReduceNoise: Boolean, save: (ClipAudio) -> Unit, dismiss: () -> Unit) {
    var audio by remember { mutableStateOf(saved) }
    val fades = listOf(stringResource(R.string.sound_fade_none) to 0.0, "0.5 s" to 0.5, "1 s" to 1.0, "2 s" to 2.0, "3 s" to 3.0)
    val silent = stringResource(R.string.sound_silent)
    val volumeLabel = stringResource(R.string.sound_volume)
    val muteLabel = stringResource(R.string.sound_mute)
    fun label(decibels: Double) = when {
        decibels <= ClipAudio.MINIMUM_DB -> silent
        decibels > 0 -> "+${decibels.roundToInt()} dB"
        else -> "${decibels.roundToInt()} dB"
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.sound_dialog_title)) },
        text = {
            Column {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text(stringResource(R.string.sound_volume_value, if (audio.muted) stringResource(R.string.sound_muted) else label(audio.volumeDB)), color = Color.White)
                    TextButton(onClick = { audio = audio.copy(volumeDB = 0.0) }, enabled = audio.volumeDB != 0.0) { Text(stringResource(R.string.common_reset)) }
                }
                // Whole decibels: the range is wide and finer steps are not audible.
                Slider(
                    value = audio.volumeDB.toFloat(), onValueChange = { audio = audio.copy(volumeDB = it.roundToInt().toDouble()) },
                    valueRange = ClipAudio.MINIMUM_DB.toFloat()..ClipAudio.MAXIMUM_DB.toFloat(), enabled = !audio.muted,
                    modifier = Modifier.semantics { contentDescription = volumeLabel },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = audio.muted, onCheckedChange = { audio = audio.copy(muted = it) }, modifier = Modifier.semantics { contentDescription = muteLabel })
                    Text("  " + stringResource(R.string.sound_mute_clip), color = Color.White)
                }
                Choices(stringResource(R.string.sound_fade_in), fades, audio.fadeIn) { audio = audio.copy(fadeIn = it) }
                Choices(stringResource(R.string.sound_fade_out), fades, audio.fadeOut) { audio = audio.copy(fadeOut = it) }
                if (canReduceNoise) {
                    Choices(stringResource(R.string.sound_noise_reduction), listOf(stringResource(R.string.sound_noise_off) to 0.0, stringResource(R.string.sound_noise_light) to 0.5, stringResource(R.string.sound_noise_full) to 1.0), audio.denoise) { audio = audio.copy(denoise = it) }
                    Text(stringResource(R.string.sound_noise_note), color = Muted, fontSize = 13.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = { save(audio) }) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** A title being worked on in the Title tool: on which clip, as it is now, and as it was saved (null when new). */
private data class Titling(val clipID: String, val title: TextOverlay, val existing: TextOverlay?)

/** The choices are remembered in the project. [canvas] is the shape of the first clip. */
@Composable
private fun ExportSettingsDialog(saved: ExportSettings, canvas: Pair<Int, Int>?, start: (ExportSettings) -> Unit, dismiss: () -> Unit) {
    var settings by remember { mutableStateOf(saved) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.export_title)) },
        text = {
            Column {
                Choices(stringResource(R.string.export_size), listOf(
                    stringResource(R.string.export_size_original) to ExportSettings.Resolution.Project, "720p" to ExportSettings.Resolution.HD,
                    "1080p" to ExportSettings.Resolution.FullHD, "4K" to ExportSettings.Resolution.UltraHD,
                ), settings.resolution) { settings = settings.copy(resolution = it) }
                canvas?.let { settings.resolution.size(it.first, it.second) }?.let { (width, height) ->
                    Text(stringResource(R.string.export_pixels, width, height), color = Muted, fontSize = 13.sp)
                }
                // Only the limits the exporter can really honor.
                Choices(stringResource(R.string.export_frame_rate), listOf(stringResource(R.string.export_up_to, 30) to 30, stringResource(R.string.export_up_to, 60) to 60), settings.frameRate) { settings = settings.copy(frameRate = it) }
                Text(stringResource(R.string.export_frame_rate_note), color = Muted, fontSize = 13.sp)
                Choices(stringResource(R.string.export_format), listOf("H.264" to ExportSettings.Codec.H264, stringResource(R.string.export_h265_smaller) to ExportSettings.Codec.HEVC), settings.codec) {
                    settings = settings.copy(codec = it)
                }
            }
        },
        confirmButton = { Button(onClick = { start(settings) }) { Text(stringResource(R.string.editor_export)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun TransitionDialog(current: TransitionSettings?, choose: (Double?, TransitionStyle) -> Unit, dismiss: () -> Unit) {
    var style by remember { mutableStateOf(current?.style ?: TransitionStyle.Dissolve) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.transition_title)) },
        text = {
            Column {
                Choices(stringResource(R.string.transition_style), listOf(stringResource(R.string.transition_cross_dissolve) to TransitionStyle.Dissolve, stringResource(R.string.transition_fade_black) to TransitionStyle.FadeColor), style) { style = it }
                Text(
                    stringResource(if (style == TransitionStyle.Dissolve) R.string.transition_dissolve_note else R.string.transition_fade_note),
                    color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp),
                )
                for ((label, seconds) in listOf<Pair<String, Double?>>(stringResource(R.string.transition_none) to null, "0.3 s" to 0.3, "0.5 s" to 0.5, "1 s" to 1.0, "2 s" to 2.0)) {
                    val selected = if (seconds == null) current == null else current?.duration == seconds && current.style == style
                    TextButton(onClick = { choose(seconds, style) }) { Text((if (selected) "● " else "○ ") + label, fontSize = 16.sp) }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun ExportDialogs(exporter: Exporter, context: Context, report: (String) -> Unit) {
    exporter.progress?.let { progress ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.export_progress, (progress * 100).roundToInt())) },
            text = { LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = exporter::cancel) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    exporter.result?.let { file ->
        val uri = remember(file) { FileProvider.getUriForFile(context, "${context.packageName}.files", file) }
        fun open(intent: Intent) = try {
            context.startActivity(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            report(context.getString(R.string.export_open_failed))
        }
        AlertDialog(
            onDismissRequest = { exporter.result = null },
            title = { Text(stringResource(R.string.export_done_title)) },
            text = {
                Column {
                    Text(file.name, color = Muted, fontSize = 13.sp)
                    TextButton(onClick = { open(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")) }) { Text(stringResource(R.string.common_play)) }
                    TextButton(onClick = {
                        open(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri), null))
                    }) { Text(stringResource(R.string.export_share)) }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        TextButton(onClick = { exporter.saveToGallery(file) }) { Text(stringResource(R.string.export_save_to_gallery)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { exporter.result = null }) { Text(stringResource(R.string.common_done)) } },
        )
    }
}

/** A playback rate as the person reads it: 0.5×, 2×. */
private fun speedLabel(rate: Double): String = (if (rate % 1.0 == 0.0) rate.toInt().toString() else rate.toString()) + "×"
