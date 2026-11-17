@file:OptIn(UnstableApi::class, ExperimentalApi::class)

package app.takes

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.MediaItem
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
import app.takes.core.ColorSettings
import app.takes.core.ExportSections
import app.takes.core.RenderPlan
import app.takes.core.Size as FrameSize
import app.takes.core.TransitionLayout
import java.io.File

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
        private var drawn = false
        override fun queueInputFrame(glObjectsProvider: GlObjectsProvider, inputTexture: GlTextureInfo, presentationTimeUs: Long) {
            val index = Math.round(presentationTimeUs * framesPerSecond / 1e6).toInt() - first
            if (!drawn) { drawn = true; ExportTimes.mark("drawn") }
            if (index == 0) ExportTimes.mark("first")
            if (index == count - 1) ExportTimes.mark("last")
            if (index in 0 until count) super.queueInputFrame(glObjectsProvider, inputTexture, Math.round(index * 1e6 / framesPerSecond)) else {
                inputListener.onInputFrameProcessed(inputTexture)
                inputListener.onReadyToAcceptInputFrame()
            }
        }
    }
}

/** Marks of an export's phases, for the measurement of docs/EXPORT-SECTIONS-PROBE.md section 10 (ExportSectionsRunner); nothing listens in the app. */
object ExportTimes {
    @Volatile var listener: ((String) -> Unit)? = null
    fun mark(what: String) { listener?.invoke(what) }
}

/** What an export renders a movie's picture from: its frame rate and the picture of any section of it. Its sound is the audio engine's ([mixedSound]). */
interface MovieRender {
    /** The movie's frames a second. */
    val rate: Int

    /** Whether the movie is HDR, in HLG ([HdrMovie]). */
    val hdr: Boolean get() = false

    /** The picture of [section], without sound: exactly its frames, the first at zero. */
    fun picture(section: ExportSections.Section): Composition

}

/**
 * The sound of a whole movie, without picture, for the encoder: the mix the audio engine wrote to [mix]
 * ([writeMix]), one item on one sequence. It is made once: sound encoded twelve seconds at a time does not
 * join, each run of the encoder beginning and ending with frames of its own.
 */
fun mixedSound(mix: File): Composition = Composition.Builder(
    listOf(EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)).addItem(EditedMediaItem.Builder(MediaItem.fromUri(android.net.Uri.fromFile(mix))).build()).build()),
).build()

/**
 * One movie's picture as the export renders it: any twelve seconds of it. What every stretch is made from is the movie's [RenderPlan], so that a section holds exactly
 * the frames the whole movie has in those seconds; here the plan is turned into what Media3 renders, and
 * nothing about the edit is decided.
 *
 * The frames come from a clock: a sequence of its own, first, that holds one still at the movie's frame
 * rate. Media3 composes one frame for every frame of its first sequence, at that frame's time, and takes
 * from each of the others the frame nearest to it. The clips are on one deck, or, where neighbors play at
 * once or with a pause between them, their chains alternate between two ([RenderPlan.stretch]); the decks are
 * laid one above the other and the transition effect makes each frame from them. The clock is not drawn
 * (DecksCompositor).
 *
 * The decks carry the picture alone. A deck that carries sound and opens on a still asks Media3 for its
 * silence the moment its picture is taken, and Media3 has none to give until the first sequence with sound
 * has given its own: the export then stopped with "Asset loader error". The sound of the movie is the audio
 * engine's, mixed beside it into one file ([mixedSound]).
 *
 * [plan] is the plan of the edit and [ceiling] the most frames a second the settings allow. [files] gives
 * each source's file, [canvas] the movie's size, [sizes] the upright size of each recording, [rates] the
 * frames a second each states, [black] a black picture for the stretches without a clip, and [luts] the folder the
 * color tables are kept in.
 */
class ExportMovie(
    private val context: Context, private val plan: RenderPlan, ceiling: Int, private val files: Map<String, File>, val canvas: Pair<Int, Int>,
    private val sizes: Map<String, Pair<Int, Int>?>, private val rates: Map<String, Double?>, private val black: File, private val luts: File,
    /** The recordings, by id: the person masks are kept by them. */
    private val media: Map<String, app.takes.core.SourceMedia> = emptyMap(),
    /** The HDR transfer of each recording that is HDR ([HdrMovie.transfer]): any makes the movie HDR. */
    private val transfers: Map<String, Int> = emptyMap(),
) : MovieRender {
    override val hdr = plan.pictures.any { it.sourceID in transfers }

    private val decks = plan.sequences.size

    /** The movie's length in seconds. */
    val total = plan.duration

    /**
     * The movie's frames a second: the rate of its fastest footage under the ceiling of the settings
     * ([RenderPlan.frameRate]). A flash cut counts its frames in it.
     */
    override val rate = plan.frameRate(ceiling)

    /** The frames of the whole movie. */
    val frames = ExportSections.pictures(total, rate)

    /** The frames a section holds. */
    fun frames(section: ExportSections.Section): Int = ExportSections.pictures(section.lengthTicks, rate)

    private val perSecond = { _: TransitionLayout.Join -> rate.toDouble() }
    private val picture = Size(canvas.first, canvas.second)
    private val frame = FrameSize(canvas.first.toDouble(), canvas.second.toDouble())

    // One for every item: a Presentation keeps the matrix it worked out for the size of its last input, and
    // two items of different shapes that play at once, one in each sequence, would be drawn with each other's.
    private fun fit() = Presentation.createForWidthAndHeight(canvas.first, canvas.second, Presentation.LAYOUT_SCALE_TO_FIT)

    private fun size(sourceID: String): FrameSize? = sizes[sourceID]?.let { FrameSize(it.first.toDouble(), it.second.toDouble()) }

    /** A part of a clip as the plan cuts it, on a deck that begins at second [origin] of the movie. */
    private fun piece(part: RenderPlan.Part.Footage, origin: Double): EditedMediaItem {
        val shown = plan.pictures[part.index]
        val picture = buildList<Effect> {
            val color = ClipColor(this, hdr, transfers[shown.sourceID])
            if (plan.clips[part.index].videoEffects.orEmpty().any { it.kind == app.takes.core.VideoEffectID.NightFix }) {
                color.asSdr()
                add(NightFixEffect(plan.clips[part.index], shown.start - origin)) // on the raw frame
            }
            // Color on the footage itself, before it is fitted: the bars around a clip of another shape stay black.
            LogFootage.profile(files[shown.sourceID]).let { log -> if (shown.color != null || log != null) color.look(shown.color ?: ColorSettings(), luts, log) }
            val clip = plan.clips[part.index]
            // Before the fitting where nothing after it needs SDR: an effect after a deck's Presentation is best avoided (docs/EXPORT-TWO-SEQUENCES.md, section 8).
            if (clip.videoEffects.isNullOrEmpty() && !printsSteps(clip)) color.inHdr()
            // A crop fills the frame with part of the footage, frame by frame; fitting then has nothing left to do.
            val source = size(shown.sourceID)
            if ((shown.crop != null || clip.mirror != null) && source != null) add(FramingEffect(clip, shown.start - origin, source, frame))
            add(fit())
            val sourceFPS = rates[shown.sourceID]?.takeIf { it.isFinite() && it > 0 } ?: 30.0
            // A held frame, of a freeze or a cadence, is kept before the effects, which draw it at the second it holds.
            if (holdsFrames(clip)) add(TimeHoldEffect(clip, shown.start - origin, rate.toDouble()))
            // The clip's video effects on the picture as it lies in the frame, bars included; titles come after.
            if (!clip.videoEffects.isNullOrEmpty()) {
                color.asSdr()
                add(VideoEffectChain(clip, shown.start - origin, rate.toDouble(), SourceAssets(files[shown.sourceID], luts, clip, context = context, media = media[shown.sourceID]), sourceFPS))
            }
            // Step Printing blends the frames of each block as the chain drew them.
            if (printsSteps(clip)) {
                color.asSdr()
                add(StepPrintingEffect(clip, shown.start - origin, sourceFPS))
            }
            color.inHdr()
        }
        val timed = TimedPiece.of(plan.clips[part.index], shown.spans, part, files[shown.sourceID])
        val item = MediaItem.Builder().setUri(android.net.Uri.fromFile(files.getValue(shown.sourceID))).setMediaId(shown.id)
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionUs(timed.startUs).setEndPositionUs(timed.endUs).build()).build()
        // Speed comes first in Media3, so the effects after it are timed in movie seconds.
        return EditedMediaItem.Builder(item).setRemoveAudio(true).setEffects(Effects(emptyList(), picture)).apply { timed.speed?.let(::setSpeed) }.build()
    }

    /**
     * The picture of [section], without sound: exactly [frames] of it, the first at zero.
     *
     * It is the movie from a little before the section to a little after it ([RenderPlan.section]), drawn as
     * the whole movie is drawn, with the frames outside the section left out before the encoder
     * ([SectionFramesEffect]). Every effect is told the movie's own time, so a transition, a title or a moving
     * crop does in a section what it does in the whole movie.
     */
    override fun picture(section: ExportSections.Section): Composition {
        val drawing = plan.section((section.startTicks / ExportSections.frameTicks(rate)).toInt(), frames(section), rate)
        val opens = drawing.opens
        // The clock holds a whole number of frames, so the last frame of a movie may begin where its clips have ended.
        val clock = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
            .addItem(still(black, Math.round((drawing.closes - opens) * 1e6 / rate), rate, listOf(FrameTimeEffect { maxOf(0, it - CLOCK_LEAD) }))).build()
        // Where a deck has no clip it shows a black still, fitted like a clip. Media3's own gap
        // gives frames of 16 by 16 pixels: at every change between those and a clip's frames the deck's
        // textures were made again while the compositor still held frames in the old ones, which came out
        // black (a clip's last frame) or were drawn over too soon (a clip two frames ahead of its time).
        val sequences = drawing.stretch.decks.map { parts ->
            EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO)).apply {
                for (part in parts) addItem(
                    when (part) {
                        is RenderPlan.Part.Black -> still(black, part.micros, rate, listOf(fit()))
                        is RenderPlan.Part.Footage -> piece(part, drawing.stretch.from)
                    },
                )
            }.build()
        }
        // Titles are drawn by the transition effect, with the picture of their clip; subtitles once, over the finished picture.
        val written = listOfNotNull(plan.subtitles?.let { SubtitleOverlay(plan.captions, it, canvas.first, canvas.second, context.assets, hdr) })
        // Where each clip's own picture lies in the frame: all of it when cropped, else fitted inside it.
        val content = { index: Int -> RenderPlan.content(frame, size(plan.pictures[index].sourceID), plan.pictures[index].crop != null) }
        val drawn = buildList<Effect> {
            // The clock's times are put on the movie's frame grid, at the movie's own time, before anything reads
            // them: Media3 counts them in a frame length it keeps with seven digits, and they fall behind the grid
            // by a microsecond every thousand frames.
            add(FrameTimeEffect { Math.round((opens + Math.round(it * rate / 1e6)) * 1e6 / rate) })
            add(TransitionEffect(plan, picture, decks, perSecond, content, titled = plan.titled, hdr = hdr))
            if (written.isNotEmpty()) add(OverlayEffect(written))
            add(SectionFramesEffect(drawing.first, drawing.count, rate))
            if (hdr) add(TenBitGate())
        }
        return Composition.Builder(listOf(clock) + sequences)
            .setVideoCompositorSettings(DecksCompositor(picture, decks))
            .setEffects(Effects(emptyList(), drawn))
            // An SDR movie tone-maps HDR footage; an HDR one carries its HLG through ([HdrMovie]).
            .setHdrMode(if (hdr) Composition.HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR else Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
    }

    private companion object {
        /** How much earlier than its own time a frame of the clock asks the decks for theirs, in microseconds ([FrameTimeEffect]). */
        const val CLOCK_LEAD = 3L
    }
}
