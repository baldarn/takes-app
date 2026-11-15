@file:OptIn(UnstableApi::class, ExperimentalApi::class)

package app.takes

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.PassthroughShaderProgram
import androidx.media3.effect.Presentation
import androidx.media3.effect.SingleColorLut
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import app.takes.core.AudioItem
import app.takes.core.ExportSections
import app.takes.core.Project
import app.takes.core.Rect as FrameRect
import app.takes.core.Size as FrameSize
import app.takes.core.SoundEnvelope
import app.takes.core.SubtitleEditing
import app.takes.core.TimelineClip
import app.takes.core.TimelineEditing
import app.takes.core.TransitionLayout
import app.takes.core.TransitionPicture
import app.takes.core.TransitionScene
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Lets through the frames of one section of the movie and no other, and gives them the times of a movie
 * that begins with the section.
 *
 * A section is rendered with some of the movie before it and after it ([ExportMovie.picture]), so that what
 * its first and last frames show is what they show in the whole movie. Those frames are drawn and not
 * encoded: they stop here. The frames arrive with the movie's own times, on its frame grid; the section's
 * are [first] and the [count] - 1 after it, at [framesPerSecond].
 */
class SectionFramesEffect(private val first: Int, private val count: Int, private val framesPerSecond: Int) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = object : PassthroughShaderProgram() {
        override fun queueInputFrame(glObjectsProvider: GlObjectsProvider, inputTexture: GlTextureInfo, presentationTimeUs: Long) {
            val index = Math.round(presentationTimeUs * framesPerSecond / 1e6).toInt() - first
            if (index in 0 until count) super.queueInputFrame(glObjectsProvider, inputTexture, Math.round(index * 1e6 / framesPerSecond)) else {
                inputListener.onInputFrameProcessed(inputTexture)
                inputListener.onReadyToAcceptInputFrame()
            }
        }
    }
}

/**
 * One movie as the export renders it: the sound of all of it in one pass, and the picture of any twelve
 * seconds of it. What every stretch is made from is described once, here, so that a section holds exactly
 * the frames the whole movie has in those seconds.
 *
 * The frames come from a clock: a sequence of its own, first, that holds one still at the movie's frame
 * rate. Media3 composes one frame for every frame of its first sequence, at that frame's time, and takes
 * from each of the others the frame nearest to it. The clips are on one deck, or, where neighbors play at
 * once or with a pause between them, their chains alternate between two; the decks are laid one above the
 * other and the transition effect makes each frame from them. The clock is not drawn (DecksCompositor).
 *
 * The decks carry the picture alone. A deck that carries sound and opens on a still asks Media3 for its
 * silence the moment its picture is taken, and Media3 has none to give until the first sequence with sound
 * has given its own: the export then stopped with "Asset loader error". The sound of every clip plays from
 * sequences of sound alone, which wait instead.
 *
 * [files] gives each source's file, [canvas] the movie's size, [sizes] the upright size of each recording,
 * [rates] the frames a second each states, [voiced] whether it has sound, [clean] the noise-reduced sound
 * of the clips that have it (by index), [black] a black picture for the stretches without a clip, and
 * [luts] the folder the color tables are kept in.
 */
class ExportMovie(
    private val context: Context, private val project: Project, private val files: Map<String, File>, val canvas: Pair<Int, Int>,
    private val sizes: Map<String, Pair<Int, Int>?>, rates: Map<String, Double?>, private val voiced: Map<String, Boolean>,
    private val clean: Map<Int, File>, private val black: File, private val luts: File,
) {
    private val clips = project.edit.clips
    private val chains = TimelineEditing.chains(clips)
    private val decks = minOf(chains.size, 2)

    /** The movie's length in seconds. */
    val total = TimelineEditing.duration(clips)

    /**
     * The movie's frames a second: the rate of its fastest footage under the ceiling of the settings
     * ([TransitionScene.frameRate]). A flash cut counts its frames in it.
     */
    val rate = TransitionScene.frameRate(clips.map { rates[it.sourceID] }, project.exportSettings.frameRate)

    /** The frames of the whole movie. */
    val frames = ExportSections.pictures(total, rate)

    /** The frames a section holds. */
    fun frames(section: ExportSections.Section): Int = ExportSections.pictures(section.lengthTicks, rate)

    private val perSecond = { _: TransitionLayout.Join -> rate.toDouble() }
    private val picture = Size(canvas.first, canvas.second)

    // One for every item: a Presentation keeps the matrix it worked out for the size of its last input, and
    // two items of different shapes that play at once, one in each sequence, would be drawn with each other's.
    private fun fit() = Presentation.createForWidthAndHeight(canvas.first, canvas.second, Presentation.LAYOUT_SCALE_TO_FIT)

    // The stills of the movie: from the frame a freeze holds until the freeze ends.
    private val freezes = TimelineEditing.layout(clips).let { layout ->
        layout.all.filter { it.transition != null && it.outgoing != null && TransitionLayout.longEnough(it.timing) }.mapNotNull { join ->
            val placement = layout.placements[join.outgoing!!]
            TransitionPicture.heldFrame(join.timing, join.origin, placement.start, placement.duration, TransitionScene.FRAMES_PER_SECOND)?.let { it.shown to it.until }
        }
    }

    /**
     * The first frame to draw for a section that begins at frame [first]. Half a second before it, so that
     * the frame nearest to the section's first one may be one that began before it, and so that a frame the
     * transition effect keeps in its last frames before a moment is among those it has seen. A section that
     * begins inside a freeze begins before the frame the freeze holds, which the deck does not give again.
     */
    private fun lead(first: Int): Int {
        val begins = first.toDouble() / rate
        val held = freezes.filter { (shown, until) -> shown - MARGIN < begins && begins < until + MARGIN }.minOfOrNull { it.first }
        val from = minOf(begins, held ?: begins) - MARGIN
        return maxOf(0, minOf(first, Math.floor(from * rate).toInt()))
    }

    /** The clip at [index] between the seconds [from] and [to] of the movie, on a deck that begins at [origin]. */
    private fun piece(index: Int, from: Double, to: Double, origin: Double): EditedMediaItem {
        val clip = clips[index]
        val begins = TimelineEditing.clipStart(clips, index)
        val picture = buildList<Effect> {
            // Color on the footage itself, before it is fitted: the bars around a clip of another shape stay black.
            clip.color?.let { add(SingleColorLut.createFromCube(ColorLuts.cube(it, luts))) }
            // A crop fills the frame with part of the footage, frame by frame; fitting then has nothing left to do.
            val source = sizes[clip.sourceID]
            if (clip.crop != null && source != null) {
                add(FramingEffect(clip, begins - origin, FrameSize(source.first.toDouble(), source.second.toDouble()), FrameSize(canvas.first.toDouble(), canvas.second.toDouble())))
            }
            add(fit())
        }
        // The whole clip is cut where the edit cuts it; a part of it where the stretch does, never later than
        // the frame that begins there (cutMicros).
        val starts = if (from <= begins) cutMicros(clip.start) else cutMicros(clip.start + (from - begins) * clip.rate)
        val ends = if (to >= begins + clip.duration) cutMicros(clip.end) else cutMicros(clip.start + (to - begins) * clip.rate)
        val item = MediaItem.Builder().setUri(android.net.Uri.fromFile(files.getValue(clip.sourceID))).setMediaId(clip.id)
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionUs(starts).setEndPositionUs(maxOf(starts + 1, ends)).build()).build()
        // Speed comes first in Media3, so the effects after it are timed in movie seconds.
        return EditedMediaItem.Builder(item).setRemoveAudio(true).setEffects(Effects(emptyList(), picture)).apply { steadySpeed(clip)?.let(::setSpeed) }.build()
    }

    /**
     * The picture of [section], without sound: exactly [frames] of it, the first at zero.
     *
     * It is the movie from a little before the section to a little after it, drawn as the whole movie is
     * drawn, with the frames outside the section left out before the encoder ([SectionFramesEffect]). Every
     * effect is told the movie's own time, so a transition, a title or a moving crop does in a section what
     * it does in the whole movie.
     */
    fun picture(section: ExportSections.Section): Composition {
        val first = (section.startTicks / ExportSections.frameTicks(rate)).toInt()
        val count = frames(section)
        val opens = lead(first)
        val closes = minOf(frames, first + count + ceil(MARGIN * rate).toInt())
        val from = opens.toDouble() / rate
        // The clock holds a whole number of frames, so the last frame of a movie may begin where its clips have ended.
        val to = if (closes == frames) total else closes.toDouble() / rate
        val clock = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
            .addItem(still(black, Math.round((closes - opens) * 1e6 / rate), rate, listOf(FrameTimeEffect { maxOf(0, it - CLOCK_LEAD) }))).build()
        val sequences = List(decks) { EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO)) }
        val filled = DoubleArray(decks) { from }
        // Where a deck has no clip it shows a black still, fitted like a clip. Media3's own gap
        // gives frames of 16 by 16 pixels: at every change between those and a clip's frames the deck's
        // textures were made again while the compositor still held frames in the old ones, which came out
        // black (a clip's last frame) or were drawn over too soon (a clip two frames ahead of its time).
        fun gap(deck: Int, until: Double) {
            val micros = ((until - filled[deck]) * 1e6).roundToLong()
            if (micros > 0) sequences[deck].addItem(still(black, micros, rate, listOf(fit())))
            filled[deck] = maxOf(filled[deck], until)
        }
        chains.forEachIndexed { number, chain ->
            val deck = number % decks
            for (index in chain.first..chain.last) {
                val begins = TimelineEditing.clipStart(clips, index)
                val ends = begins + clips[index].duration
                val starts = maxOf(begins, from)
                val stops = minOf(ends, to)
                if (stops - starts < SHORTEST) continue
                gap(deck, starts)
                sequences[deck].addItem(piece(index, starts, stops, from))
                filled[deck] = stops
            }
        }
        for (deck in 0 until decks) gap(deck, to)
        // Titles and subtitles are drawn once, over the finished picture; subtitles on top.
        val captions = SubtitleEditing.cues(project.edit)
        val written = listOfNotNull(
            if (clips.any { !it.texts.isNullOrEmpty() }) TitleOverlay(clips, canvas.first, canvas.second, context.assets, perSecond) else null,
            project.edit.subtitles?.takeIf { captions.isNotEmpty() }?.let { SubtitleOverlay(captions, it, canvas.first, canvas.second) },
        )
        // Where each clip's own picture lies in the frame: all of it when cropped, else fitted inside it.
        val frame = FrameSize(canvas.first.toDouble(), canvas.second.toDouble())
        val content = { index: Int ->
            val source = sizes[clips[index].sourceID]
            if (clips[index].crop != null || source == null) FrameRect(0.0, 0.0, frame.width, frame.height) else {
                val scale = minOf(frame.width / source.first, frame.height / source.second)
                FrameRect((frame.width - source.first * scale) / 2, (frame.height - source.second * scale) / 2, source.first * scale, source.second * scale)
            }
        }
        val drawn = buildList<Effect> {
            // The clock's times are put on the movie's frame grid, at the movie's own time, before anything reads
            // them: Media3 counts them in a frame length it keeps with seven digits, and they fall behind the grid
            // by a microsecond every thousand frames.
            add(FrameTimeEffect { Math.round((opens + Math.round(it * rate / 1e6)) * 1e6 / rate) })
            add(TransitionEffect(clips, picture, decks, perSecond, content))
            if (written.isNotEmpty()) add(OverlayEffect(written))
            add(SectionFramesEffect(first, count, rate))
        }
        return Composition.Builder(listOf(clock) + sequences.map { it.build() })
            .setVideoCompositorSettings(DecksCompositor(picture, decks))
            .setEffects(Effects(emptyList(), drawn))
            // The movie is always SDR, so HDR footage from a phone camera is tone-mapped.
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
    }

    /**
     * The sound of the whole movie, without picture. It is made once: sound encoded twelve seconds at a time
     * does not join, each run of the encoder beginning and ending with frames of its own.
     */
    fun sound(): Composition {
        val sounds = project.edit.audio.orEmpty()
        fun sequence(fill: (EditedMediaItemSequence.Builder, gap: (Double) -> Unit, filled: (Double) -> Unit) -> Unit): EditedMediaItemSequence {
            val sequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
            var filled = 0.0
            val gap = { until: Double ->
                val micros = ((until - filled) * 1e6).roundToLong()
                if (micros > 0) sequence.addGap(micros)
            }
            fill(sequence, gap) { filled = it }
            gap(total)
            return sequence.build()
        }
        // Sound on its own tracks: one sequence per track, silent between items. The movie ends with its
        // picture, so later sound is left out.
        val soundtracks = (0 until AudioItem.TRACKS).mapNotNull { track ->
            val items = sounds.filter { it.track == track && it.start < total - 0.01 }.sortedBy { it.start }
            if (items.isEmpty()) return@mapNotNull null
            sequence { sequence, gap, filled ->
                for (item in items) {
                    gap(item.start)
                    val seconds = minOf(item.end, total) - item.start
                    sequence.addItem(
                        EditedMediaItem.Builder(clippedItem(item, files.getValue(item.sourceID), seconds)).setRemoveVideo(true)
                            .setEffects(Effects(listOf<AudioProcessor>(SoundProcessor(SoundEnvelope.item(sounds, item, total), item.start)), emptyList()))
                            .build(),
                    )
                    filled(item.start + seconds)
                }
            }
        }
        // The clips' sound at the clips' own times: the cleaned sound of a clip that has it, in place of the
        // recorded one. It follows the clip's own level and fades and any transition at its ends, sample by
        // sample, and fades for a few milliseconds at every cut. Clips that dissolve into each other overlap,
        // so chains alternate.
        val heard = clips.indices.filter { it in clean || voiced[clips[it].sourceID] == true }
        val voices = (0..1).mapNotNull { parity ->
            val indices = heard.filter { index -> chains.indexOfFirst { index in it.first..it.last } % 2 == parity }
            if (indices.isEmpty()) return@mapNotNull null
            sequence { sequence, gap, filled ->
                for (index in indices) {
                    val clip = clips[index]
                    val begins = TimelineEditing.clipStart(clips, index)
                    gap(begins)
                    sequence.addItem(
                        EditedMediaItem.Builder(clippedItem(clip, clean[index] ?: files.getValue(clip.sourceID))).setRemoveVideo(true)
                            .setEffects(Effects(listOf<AudioProcessor>(SoundProcessor(SoundEnvelope.clip(clips, index), begins)), emptyList()))
                            .apply { steadySpeed(clip)?.let(::setSpeed) }
                            .build(),
                    )
                    filled(begins + clip.duration)
                }
            }
        }
        // A movie with no sound anywhere still has a sound track, silent.
        val all = (soundtracks + voices).ifEmpty { listOf(sequence { _, _, _ -> }) }
        return Composition.Builder(all).build()
    }

    private companion object {
        /** How much earlier than its own time a frame of the clock asks the decks for theirs, in microseconds ([FrameTimeEffect]). */
        const val CLOCK_LEAD = 3L

        /** How much of the movie is drawn before and after a section, in seconds. */
        const val MARGIN = 0.5

        /** A part of a clip shorter than this many seconds is left out of a stretch: it holds no frame. */
        const val SHORTEST = 0.000002
    }
}
