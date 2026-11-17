@file:OptIn(UnstableApi::class, ExperimentalApi::class)

package app.takes

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import app.takes.core.ColorSettings
import app.takes.core.ExportSections
import app.takes.core.LayerPlan
import app.takes.core.RenderPlan
import app.takes.core.Size as FrameSize
import app.takes.core.TimelineTransition
import app.takes.core.TransitionScene
import java.io.File

/** A picture of a layer plan as a player or export item, cut to the footage it plays, as [clippedItem] cuts a clip of a render plan. */
fun clippedItem(picture: LayerPlan.Picture, file: File, startUs: Long = picture.sourceStartUs, endUs: Long = picture.sourceEndUs): MediaItem = MediaItem.Builder()
    .setUri(Uri.fromFile(file))
    .setMediaId(picture.id)
    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionUs(startUs).setEndPositionUs(endUs).build())
    .build()

/**
 * One movie of an edit drawn by picture layers ([LayerPlan]) as the export
 * renders it, as [ExportMovie] renders a sequence: the clock first, then one
 * picture sequence per lane ([LayerPlan.section]), black stills where a lane
 * plays nothing, laid in a column or, past the GPU's largest texture
 * ([columns] 2), in a grid by [DecksCompositor]; [LayersEffect] makes each
 * frame from them, back to front over black. A picture's color, crop, video
 * effects are drawn on its own lane and its titles by [LayersEffect] over its
 * picture, both before it is moved into its window, so its titles shrink with
 * it as in the original (YouRec/TrackRenderer.swift:409-411); the captions go
 * over the whole frame.
 *
 * The sound is the audio engine's mix of the timeline ([mixedSound]).
 */
class LayerMovie(
    private val context: Context, planned: LayerPlan, ceiling: Int, private val files: Map<String, File>,
    val canvas: Pair<Int, Int>, private val columns: Int, private val sizes: Map<String, Pair<Int, Int>?>, private val rates: Map<String, Double?>,
    private val black: File, private val luts: File,
    /** The HDR transfer of each recording that is HDR ([HdrMovie.transfer]): any makes the movie HDR. */
    private val transfers: Map<String, Int> = emptyMap(),
    /** The corrections of the stabilised clips, by clip id ([StabilizationFrames.plans]), and the clockwise turn of each recording. */
    private val stabilization: Map<String, app.takes.core.StabilizationPlan> = emptyMap(),
    private val rotations: Map<String, Int> = emptyMap(),
) : MovieRender {
    // The frame a still holds is found on the movie's grid, as the original's export finds it (LayerPlan.atFrameRate).
    private val plan = planned.atFrameRate(TransitionScene.frameRate(ceiling).toDouble())

    private val lanes = plan.lanes

    override val hdr = plan.pictures.any { it.sourceID in transfers }

    override val rate = TransitionScene.frameRate(ceiling)

    private val perTransition = { _: TimelineTransition -> rate.toDouble() }
    private val picture = Size(canvas.first, canvas.second)
    private val frame = FrameSize(canvas.first.toDouble(), canvas.second.toDouble())

    private fun fit() = Presentation.createForWidthAndHeight(canvas.first, canvas.second, Presentation.LAYOUT_SCALE_TO_FIT)

    private fun size(sourceID: String): FrameSize? = sizes[sourceID]?.let { FrameSize(it.first.toDouble(), it.second.toDouble()) }

    /** A part of a picture as the plan cuts it, on a lane that begins at second [origin] of the movie. */
    private fun piece(part: RenderPlan.Part.Footage, origin: Double): EditedMediaItem {
        val shown = plan.pictures[part.index]
        val clip = shown.content
        val begins = shown.start - origin
        val effects = buildList<Effect> {
            // In an HDR movie each picture ends in HLG, as a sequence's clips do (ExportMovie).
            val color = ClipColor(this, hdr, transfers[shown.sourceID])
            LogFootage.profile(files[shown.sourceID]).let { log -> if (shown.color != null || log != null) color.look(shown.color ?: ColorSettings(), luts, log, clip, begins) }
            if (clip.videoEffects.isNullOrEmpty() && !printsSteps(clip)) color.inHdr()
            val source = size(shown.sourceID)
            // A stabilised clip's frame is corrected before it is framed (YouRec/VideoToolCompositor.swift:158-161).
            stabilization[clip.id]?.let { add(StabilizationEffect(clip, begins, it, rotations[shown.sourceID] ?: 0)) }
            if ((shown.crop != null || clip.mirror != null) && source != null) add(FramingEffect(clip, begins, source, frame))
            add(fit())
            val sourceFPS = rates[shown.sourceID]?.takeIf { it.isFinite() && it > 0 } ?: 30.0
            if (holdsFrames(clip)) add(TimeHoldEffect(clip, begins, rate.toDouble()))
            if (!clip.videoEffects.isNullOrEmpty()) {
                color.asSdr()
                add(VideoEffectChain(clip, begins, rate.toDouble(), sourceFPS = sourceFPS))
            }
            if (printsSteps(clip)) {
                color.asSdr()
                add(StepPrintingEffect(clip, begins, sourceFPS))
            }
            color.inHdr()
        }
        val timed = TimedPiece.of(clip, shown.spans, part, files[shown.sourceID])
        val item = MediaItem.Builder().setUri(Uri.fromFile(files.getValue(shown.sourceID))).setMediaId(shown.id)
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionUs(timed.startUs).setEndPositionUs(timed.endUs).build()).build()
        return EditedMediaItem.Builder(item).setRemoveAudio(true).setEffects(Effects(emptyList(), effects)).apply { timed.speed?.let(::setSpeed) }.build()
    }

    override fun picture(section: ExportSections.Section): Composition {
        val drawing = plan.section((section.startTicks / ExportSections.frameTicks(rate)).toInt(), ExportSections.pictures(section.lengthTicks, rate), rate)
        val opens = drawing.opens
        val clock = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
            .addItem(still(black, Math.round((drawing.closes - opens) * 1e6 / rate), rate, listOf(FrameTimeEffect { maxOf(0, it - CLOCK_LEAD) }))).build()
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
        val content = { index: Int -> RenderPlan.content(frame, size(plan.pictures[index].sourceID), plan.pictures[index].crop != null) }
        val drawn = buildList<Effect> {
            add(FrameTimeEffect { Math.round((opens + Math.round(it * rate / 1e6)) * 1e6 / rate) })
            add(LayersEffect(plan, picture, lanes, columns, perTransition, content, titled = true, hdr = hdr, folder = luts))
            plan.subtitles?.let { add(OverlayEffect(listOf(SubtitleOverlay(plan.captions, it, canvas.first, canvas.second, context.assets, hdr)))) }
            add(SectionFramesEffect(drawing.first, drawing.count, rate))
            if (hdr) add(TenBitGate())
        }
        return Composition.Builder(listOf(clock) + sequences)
            .setVideoCompositorSettings(DecksCompositor(picture, lanes, columns))
            .setEffects(Effects(emptyList(), drawn))
            // An SDR movie tone-maps HDR footage; an HDR one carries its HLG through ([HdrMovie]).
            .setHdrMode(if (hdr) Composition.HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR else Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
    }

    private companion object {
        /** As [ExportMovie]'s: how much earlier than its own time a frame of the clock asks the lanes for theirs, in microseconds. */
        const val CLOCK_LEAD = 3L
    }
}
