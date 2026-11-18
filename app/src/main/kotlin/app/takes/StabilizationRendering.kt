@file:OptIn(UnstableApi::class)

package app.takes

import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import app.takes.core.Affine
import app.takes.core.SourceMedia
import app.takes.core.StabilizationLevel
import app.takes.core.StabilizationPlan
import app.takes.core.TimelineClip
import app.takes.core.matrixValues
import app.takes.core.normalized
import app.takes.core.stabilizationEnd
import app.takes.core.stabilizationStart
import app.takes.core.framingAt
import app.takes.core.scopedFraming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import app.takes.core.Size as FrameSize

/**
 * A stabilised clip's picture in the export: each frame moved, turned and zoomed by the correction of the source
 * frame it is, before it is framed and fitted, as the original corrects the frame before its framing
 * (YouRec/VideoToolCompositor.swift:158-161). The picture keeps its size; the zoom of the plan keeps its corners
 * filled.
 *
 * [begins] is where the clip starts in the movie, as for [FramingEffect]; [rotation] is the clockwise turn that puts
 * the recording upright, which Media3 has applied before any effect.
 */
class StabilizationEffect(
    private val clip: TimelineClip, private val begins: Double, private val plan: StabilizationPlan, private val rotation: Int,
) : MatrixTransformation {
    private val matrix = android.graphics.Matrix()
    private val upright = if (rotation % 180 == 0) plan.size else FrameSize(plan.size.height, plan.size.width)

    override fun getMatrix(presentationTimeUs: Long): android.graphics.Matrix {
        // The source frame this is: the one shown that far into the clip, the held one of a freeze.
        val source = clip.sourceTime((presentationTimeUs / 1e6 - begins).coerceIn(0.0, clip.duration))
        matrix.setValues(StabilizationFrames.correction(plan, clip, source, rotation).normalized(upright, upright).matrixValues())
        return matrix
    }
}

/**
 * A stabilised clip's picture in the export, corrected and framed in one step into the movie's frame of [canvas],
 * as the original draws it (YouRec/VideoToolCompositor.swift:141, 158-162): the correction of the source frame, then
 * the clip's framing, its crop and flip or the whole picture fitted, and only then cut to the frame. A clip of another
 * shape than the frame shows the zoomed footage in what would be its bars, as the original's and as the preview's.
 *
 * [source] is the recording's upright size; [begins] and [rotation] as for [StabilizationEffect].
 */
class StabilizedFramingEffect(
    private val clip: TimelineClip, private val begins: Double, private val plan: StabilizationPlan, private val rotation: Int,
    private val source: FrameSize, private val canvas: FrameSize,
) : MatrixTransformation {
    private val matrix = android.graphics.Matrix()

    override fun configure(inputWidth: Int, inputHeight: Int): androidx.media3.common.util.Size = androidx.media3.common.util.Size(canvas.width.toInt(), canvas.height.toInt())

    override fun getMatrix(presentationTimeUs: Long): android.graphics.Matrix {
        val offset = (presentationTimeUs / 1e6 - begins).coerceIn(0.0, clip.duration)
        val frame = app.takes.core.PictureFraming.frame(clip, offset, source, canvas)
        val correction = StabilizationFrames.correction(plan, clip, clip.sourceTime(offset), rotation)
        // Media3 moves the corners of the picture in −1…1 coordinates, y upward, and draws what stays inside the frame.
        matrix.setValues(correction.concatenating(frame.transform).normalized(source, canvas).matrixValues())
        return matrix
    }
}

object StabilizationFrames {
    /** The correction of the frame at source second [source] of [clip] in upright pixels: from its plan, timed from the reference start. */
    fun correction(plan: StabilizationPlan, clip: TimelineClip, source: Double, rotation: Int): Affine =
        // None outside the stabilisation's active range or windows (YouRec/VideoToolCompositor.swift:221-222).
        if (clip.scopedFraming && clip.framingAt(source).stabilization == null) Affine.IDENTITY
        else plan.uprightTransform(source - clip.stabilizationStart, rotation)

    /** The clips that are stabilised, with their plan where it is measured, for an export. Waits for every one. */
    suspend fun plans(
        clips: List<TimelineClip>, sources: Map<String, SourceMedia>, folder: File, progress: (Double) -> Unit,
    ): Map<String, StabilizationPlan> = withContext(Dispatchers.IO) {
        val wanted = clips.filter { (it.stabilization?.level ?: StabilizationLevel.Off) != StabilizationLevel.Off }
        val context = currentCoroutineContext()
        buildMap {
            wanted.forEachIndexed { index, clip ->
                val source = sources[clip.sourceID] ?: return@forEachIndexed
                Stabilizer.plan(source, clip, folder, progress = { progress((index + it) / wanted.size) }, cancel = { !context.isActive })
                    ?.let { put(clip.id, it) }
            }
        }
    }
}

/**
 * The stabilisation the editor's preview draws: the plan of every stabilised clip once its path is measured, which
 * is done in the background, a clip at a time, while the picture plays unstabilised (YouRec/EditorController.swift:
 * 510-526); and, while the Stabilize tool is open, the plan it previews for the clips it works on.
 */
internal class PreviewStabilization(private val folder: File) {
    /** The plans measured, by [key]. */
    var plans by mutableStateOf<Map<String, StabilizationPlan>>(emptyMap()); private set

    /** How far the measuring has got, 0…1, while it runs. */
    var progress by mutableStateOf<Double?>(null); private set

    /** What the tool previews, in place of the clips' own: their candidate clip and its plan, by clip id. */
    var trial by mutableStateOf<Map<String, Trial>>(emptyMap())

    /** The clips the tool previews unstabilised (Before). */
    var before by mutableStateOf(false)

    /** A clip as the tool would leave it, with the plan of the window it measured, from [from] to [to] source seconds. */
    class Trial(val clip: TimelineClip, val plan: StabilizationPlan?, val from: Double, val to: Double)

    private val rotations = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** The clockwise turn of the recording of [source]. */
    fun rotation(source: SourceMedia): Int = rotations.getOrPut(source.id) { PersonMasks.rotation(File(folder, source.filename)) }

    /** Measures the clips of [clips] that are stabilised and have no plan yet; the others' plans are let go. */
    suspend fun follow(clips: List<TimelineClip>, sources: Map<String, SourceMedia>) {
        val wanted = clips.filter { (it.stabilization?.level ?: StabilizationLevel.Off) != StabilizationLevel.Off && it.sourceID in sources }
        plans = plans.filterKeys { key -> wanted.any { key(it) == key } }
        val context = currentCoroutineContext()
        try {
            wanted.forEachIndexed { index, clip ->
                val source = sources.getValue(clip.sourceID)
                withContext(Dispatchers.IO) { rotation(source) }
                // Already measured: no progress shown.
                val kept = withContext(Dispatchers.IO) { Stabilizer.plan(source, clip, folder, generate = false) }
                val plan = kept ?: run {
                    progress = index.toDouble() / wanted.size
                    withContext(Dispatchers.IO) {
                        Stabilizer.plan(source, clip, folder, progress = { value -> progress = (index + value) / wanted.size }, cancel = { !context.isActive })
                    }
                }
                if (plan != null) plans = plans + (key(clip) to plan)
            }
        } catch (_: java.util.concurrent.CancellationException) {
            // A newer edit is being followed.
        } catch (problem: Exception) {
            android.util.Log.w("Stabilization", "measuring failed", problem)
        } finally {
            progress = null
        }
    }

    /**
     * The correction [clip] shows at source second [source], or null when it shows none: its own plan, or the tool's
     * trial for it, which corrects only the window it measured.
     */
    fun correction(clip: TimelineClip, sourceMedia: SourceMedia?, source: Double): Affine? {
        val rotation = sourceMedia?.let { rotations[it.id] } ?: return null
        trial[clip.id]?.let { trial ->
            if (before) return null
            val plan = trial.plan ?: return null
            if (source < trial.from || source > trial.to) return null
            return StabilizationFrames.correction(plan, trial.clip, source, rotation)
        }
        val plan = plans[key(clip)] ?: return null
        if (source < clip.stabilizationStart - 0.5 || source > clip.stabilizationEnd + 0.5) return null
        return StabilizationFrames.correction(plan, clip, source, rotation)
    }

    /** Whether [clip] is drawn with a correction at some point. */
    fun corrects(clip: TimelineClip): Boolean = trial[clip.id]?.plan?.let { !before } ?: (key(clip) in plans)

    /**
     * The framing a deck shows of [shown], its clip and where it begins in the movie, with the clip's correction:
     * [base], its crop and flip, or the whole picture fitted, corrected first. [sent] is the source second of the
     * frame the deck's view shows, which chooses the correction while it lies in the clip; else the playhead's
     * second, from [playhead]. [base] itself when the clip is not corrected.
     */
    fun framing(
        shown: Pair<TimelineClip, Double>?, size: FrameSize?, media: SourceMedia?, sent: () -> Double?, playhead: () -> Double,
        base: ((FrameSize) -> app.takes.core.PictureFrame)?,
    ): ((FrameSize) -> app.takes.core.PictureFrame)? {
        val (clip, start) = shown ?: return base
        if (size == null || media == null || !corrects(clip)) return base
        return { frame ->
            val framed = base?.invoke(frame) ?: app.takes.core.PictureFraming.frame(null, 0.0, size, frame)
            val played = clip.sourceTime((playhead() - start).coerceIn(0.0, clip.duration))
            // A held frame is the one second it holds; a frame sent from elsewhere (another clip on the deck) is not this clip's.
            val source = if (clip.frozenFrame != null) played else sent()?.takeIf { it >= clip.start - 0.1 && it <= clip.end + 0.1 } ?: played
            correction(clip, media, source)?.let { framed.stabilized(it) } ?: framed
        }
    }

    /** What a plan is measured for: the clip, its level and its reference range. */
    private fun key(clip: TimelineClip) = "${clip.id}|${clip.stabilization?.level?.id}|${clip.stabilizationStart}|${clip.stabilizationEnd}"
}
