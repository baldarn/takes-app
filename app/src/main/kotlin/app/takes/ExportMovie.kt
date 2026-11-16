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
import app.takes.core.ExportSections
import app.takes.core.RenderPlan
import app.takes.core.Size as FrameSize
import app.takes.core.TransitionLayout
import java.io.File
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

/** What an export renders a movie from: its frame rate, the picture of any section of it, and the sound of all of it. */
interface MovieRender {
    /** The movie's frames a second. */
    val rate: Int

    /** The picture of [section], without sound: exactly its frames, the first at zero. */
    fun picture(section: ExportSections.Section): Composition

    /** The sound of the whole movie, without picture. */
    fun sound(): Composition
}

/**
 * One movie as the export renders it: the sound of all of it in one pass, and the picture of any twelve
 * seconds of it. What every stretch is made from is the movie's [RenderPlan], so that a section holds exactly
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
 * has given its own: the export then stopped with "Asset loader error". The sound of every clip plays from
 * sequences of sound alone ([RenderPlan.voices]), which wait instead.
 *
 * [plan] is the plan of the edit, made with the clips whose cleaned sound is in [clean] (by index), and
 * [ceiling] the most frames a second the settings allow. [files] gives each source's file, [canvas] the
 * movie's size, [sizes] the upright size of each recording, [rates] the frames a second each states, [voiced]
 * whether it has sound, [black] a black picture for the stretches without a clip, and [luts] the folder the
 * color tables are kept in.
 */
class ExportMovie(
    private val context: Context, private val plan: RenderPlan, ceiling: Int, private val files: Map<String, File>, val canvas: Pair<Int, Int>,
    private val sizes: Map<String, Pair<Int, Int>?>, rates: Map<String, Double?>, private val voiced: Map<String, Boolean>,
    private val clean: Map<Int, File>, private val black: File, private val luts: File,
) : MovieRender {
    private val decks = plan.sequences.size

    /** The movie's length in seconds. */
    val total = plan.duration

    /**
     * The movie's frames a second: the rate of its fastest footage under the ceiling of the settings
     * ([RenderPlan.frameRate]). A flash cut counts its frames in it.
     */
    override val rate = plan.frameRate(plan.pictures.map { rates[it.sourceID] }, ceiling)

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
            if (plan.clips[part.index].videoEffects.orEmpty().any { it.kind == app.takes.core.VideoEffectID.NightFix }) add(NightFixEffect(plan.clips[part.index], shown.start - origin)) // on the raw frame
            // Color on the footage itself, before it is fitted: the bars around a clip of another shape stay black.
            shown.color?.let { add(SingleColorLut.createFromCube(ColorLuts.cube(it, luts))) }
            // A crop fills the frame with part of the footage, frame by frame; fitting then has nothing left to do.
            val source = size(shown.sourceID)
            if (shown.crop != null && source != null) add(FramingEffect(plan.clips[part.index], shown.start - origin, source, frame))
            add(fit())
            // The clip's video effects on the picture as it lies in the frame, bars included; titles come after.
            if (!plan.clips[part.index].videoEffects.isNullOrEmpty()) add(VideoEffectChain(plan.clips[part.index], shown.start - origin, rate.toDouble(), SourceAssets(files[shown.sourceID], luts, plan.clips[part.index])))
        }
        val item = MediaItem.Builder().setUri(android.net.Uri.fromFile(files.getValue(shown.sourceID))).setMediaId(shown.id)
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionUs(part.sourceStartUs).setEndPositionUs(part.sourceEndUs).build()).build()
        // Speed comes first in Media3, so the effects after it are timed in movie seconds.
        return EditedMediaItem.Builder(item).setRemoveAudio(true).setEffects(Effects(emptyList(), picture)).apply { steadySpeed(shown.rate)?.let(::setSpeed) }.build()
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
        // Titles and subtitles are drawn once, over the finished picture; subtitles on top.
        val written = listOfNotNull(
            if (plan.titled) TitleOverlay(plan, canvas.first, canvas.second, context.assets, perSecond) else null,
            plan.subtitles?.let { SubtitleOverlay(plan.captions, it, canvas.first, canvas.second) },
        )
        // Where each clip's own picture lies in the frame: all of it when cropped, else fitted inside it.
        val content = { index: Int -> RenderPlan.content(frame, size(plan.pictures[index].sourceID), plan.pictures[index].crop != null) }
        val drawn = buildList<Effect> {
            // The clock's times are put on the movie's frame grid, at the movie's own time, before anything reads
            // them: Media3 counts them in a frame length it keeps with seven digits, and they fall behind the grid
            // by a microsecond every thousand frames.
            add(FrameTimeEffect { Math.round((opens + Math.round(it * rate / 1e6)) * 1e6 / rate) })
            add(TransitionEffect(plan, picture, decks, perSecond, content))
            if (written.isNotEmpty()) add(OverlayEffect(written))
            add(SectionFramesEffect(drawing.first, drawing.count, rate))
        }
        return Composition.Builder(listOf(clock) + sequences)
            .setVideoCompositorSettings(DecksCompositor(picture, decks))
            .setEffects(Effects(emptyList(), drawn))
            // The movie is always SDR, so HDR footage from a phone camera is tone-mapped.
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
    }

    /**
     * The sound of the whole movie, without picture. It is made once: sound encoded twelve seconds at a time
     * does not join, each run of the encoder beginning and ending with frames of its own.
     *
     * Sound on its own tracks plays from one sequence a track, silent between its sounds; the movie ends with
     * its picture, so later sound is left out ([RenderPlan.soundtracks]). The clips' sound plays at the clips'
     * own times, the cleaned sound of a clip that has it in place of the recorded one; clips that dissolve into
     * each other overlap, so chains alternate ([RenderPlan.voices]). Every run follows its own level and fades
     * and any transition at its ends, sample by sample, and fades for a few milliseconds at every cut.
     */
    override fun sound(): Composition {
        fun sequence(entries: List<RenderPlan.Entry>): EditedMediaItemSequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)).apply {
            for (entry in entries) when (entry) {
                is RenderPlan.Entry.Gap -> addGap(entry.micros)
                is RenderPlan.Entry.Sound -> addItem(
                    EditedMediaItem.Builder(clippedItem(entry, files.getValue(entry.item.sourceID))).setRemoveVideo(true)
                        .setEffects(Effects(listOf<AudioProcessor>(SoundProcessor(plan.soundEnvelope(entry.item), entry.start)), emptyList()))
                        .build(),
                )
                is RenderPlan.Entry.Clip -> plan.pictures[entry.index].let { heard ->
                    addItem(
                        EditedMediaItem.Builder(clippedItem(heard, clean[heard.index] ?: files.getValue(heard.sourceID))).setRemoveVideo(true)
                            .setEffects(Effects(listOf<AudioProcessor>(SoundProcessor(plan.clipEnvelope(heard.index), heard.start)), emptyList()))
                            .apply { steadySpeed(heard.rate)?.let(::setSpeed) }
                            .build(),
                    )
                }
            }
        }.build()
        val heard = plan.soundtracks + plan.voices { voiced[it] == true }
        // A movie with no sound anywhere still has a sound track, silent.
        val silence = listOfNotNull((total * 1e6).roundToLong().takeIf { it > 0 }?.let { RenderPlan.Entry.Gap(0.0, it) })
        return Composition.Builder(heard.map { sequence(it.entries) }.ifEmpty { listOf(sequence(silence)) }).build()
    }

    private companion object {
        /** How much earlier than its own time a frame of the clock asks the decks for theirs, in microseconds ([FrameTimeEffect]). */
        const val CLOCK_LEAD = 3L
    }
}
