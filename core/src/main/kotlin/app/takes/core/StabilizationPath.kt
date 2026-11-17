package app.takes.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Video stabilisation of a clip from its measured camera motion, as the
 * original's `YouRec/StabilizationPath.swift:4-196`: the levels with their
 * smoothing and crop limits, the pose algebra, the zoom a correction needs so
 * no corner shows black, and the path made from an analysis, with one crop for
 * the whole analysed range. Measuring the motion is the app's (the original
 * does it in plain C++, `YouRec/StabilizationMotion.cpp`); this is what turns
 * it into a transform per frame.
 */
@Serializable
enum class StabilizationLevel(
    /** Seconds of the Gaussian that smooths the camera's path. */
    val sigma: Double,
    /** The largest crop zoom the level may use. */
    val maximumZoom: Double,
    val detail: String,
) {
    @SerialName("off") Off(0.0, 1.0, "Keep the original camera movement."),
    @SerialName("light") Light(0.12, 1.045, "Soften small shakes. Keep more of the frame."),
    @SerialName("standard") Standard(0.28, 1.10, "Smooth handheld movement with a balanced crop."),
    @SerialName("strong") Strong(0.5, 1.18, "Steadier movement with a tighter crop."),
    ;

    /** The original's raw value, as saved. */
    val id: String get() = name.lowercase()
}

/**
 * A clip's stabilisation. The reference range is the analysed range of the
 * take, kept through splits and ripple cuts so the pieces share one path and
 * one crop, with no new analysis and no jump in framing. Saved as the
 * original saves it (YouRec/StabilizationPath.swift:24-32).
 */
@Serializable
data class StabilizationSettings(
    val level: StabilizationLevel,
    val version: Int = 1,
    val id: String? = null,
    val referenceStart: Double? = null,
    val referenceEnd: Double? = null,
) {
    /** Whether a project may hold it, on a recording of [sourceDuration] seconds (YouRec/Project.swift:513-517). */
    fun valid(sourceDuration: Double): Boolean {
        if (version != 1) return false
        val start = referenceStart
        val end = referenceEnd
        if (start == null || end == null) return start == null && end == null
        return start.isFinite() && end.isFinite() && start >= 0 && end > start && end <= sourceDuration + 0.001
    }

    companion object {
        /** The version of the settings this port reads and writes, as the original's (YouRec/VideoStabilizer.swift:90). */
        const val VERSION = 1

        /** The start of the range to analyse for a clip from [start] to [end] with [current] settings. */
        fun rangeStart(start: Double, current: StabilizationSettings?): Double = min(start, current?.referenceStart ?: start)

        fun rangeEnd(end: Double, current: StabilizationSettings?): Double = max(end, current?.referenceEnd ?: end)

        /** The settings a clip from [start] to [end] gets for [level], keeping its reference range. */
        fun stabilizing(level: StabilizationLevel, start: Double, end: Double, current: StabilizationSettings?) =
            StabilizationSettings(level, referenceStart = rangeStart(start, current), referenceEnd = rangeEnd(end, current))
    }
}

/** The start of the range a clip's stabilisation is measured over: its own, or the reference it keeps from before a split or a trim (YouRec/StabilizationPath.swift:35). */
val TimelineClip.stabilizationStart: Double get() = StabilizationSettings.rangeStart(start, stabilization)

/** The end of that range (:36). */
val TimelineClip.stabilizationEnd: Double get() = StabilizationSettings.rangeEnd(end, stabilization)

/** The settings the clip gets for [level], keeping its reference range (:37-39). */
fun TimelineClip.stabilizing(level: StabilizationLevel): StabilizationSettings = StabilizationSettings.stabilizing(level, start, end, stabilization)

/** A similarity about the centre of the encoded (unrotated) frame. */
@Serializable
data class StabilizationPose(val x: Double = 0.0, val y: Double = 0.0, val angle: Double = 0.0, val logScale: Double = 0.0) {
    val scale: Double get() = exp(logScale)

    /** [other] first, then this. */
    fun applying(other: StabilizationPose): StabilizationPose {
        val a = scale * cos(angle)
        val b = scale * sin(angle)
        return StabilizationPose(a * other.x - b * other.y + x, b * other.x + a * other.y + y, angle + other.angle, logScale + other.logScale)
    }

    val inverse: StabilizationPose
        get() {
            val a = cos(angle) / scale
            val b = sin(angle) / scale
            return StabilizationPose(-a * x - b * y, b * x - a * y, -angle, -logScale)
        }

    fun attenuated(amount: Double) = StabilizationPose(x * amount, y * amount, angle * amount, logScale * amount)

    fun transform(size: Size, zoom: Double = 1.0): Affine {
        val a = scale * cos(angle) * zoom
        val b = scale * sin(angle) * zoom
        val cx = size.width / 2
        val cy = size.height / 2
        return Affine(a, b, -b, a, cx - a * cx + b * cy + x * zoom, cy - b * cx - a * cy + y * zoom)
    }

    /**
     * The zoom that keeps every corner of the output inside the corrected
     * picture: exact corner inclusion, stronger than a bounding box, which can
     * leave black triangles. Infinite when no zoom can.
     */
    fun requiredZoom(size: Size): Double {
        val c = cos(angle)
        val s = sin(angle)
        val hw = size.width / 2
        val hh = size.height / 2
        val dx = scale * hw - abs(c * x + s * y)
        val dy = scale * hh - abs(-s * x + c * y)
        if (dx <= 0 || dy <= 0) return Double.POSITIVE_INFINITY
        return maxOf(1.0, (abs(c) * hw + abs(s) * hh) / dx, (abs(s) * hw + abs(c) * hh) / dy)
    }

    val valid: Boolean get() = listOf(x, y, angle, logScale).all { it.isFinite() } && abs(logScale) < 8

    companion object {
        val IDENTITY = StabilizationPose()
    }
}

/** The camera's pose at a time of the source; a new [segment] starts at a cut or a tracking reset. */
@Serializable
data class StabilizationSample(val time: Double, val pose: StabilizationPose, val segment: Int)

/**
 * The measured camera path of a stretch of a recording: its encoded (unrotated) size, the
 * stretch's length, and a pose for every source frame in it, timed from the stretch's start.
 * Kept in the project's cache as the original keeps it (YouRec/VideoStabilizer.swift:36-49).
 */
@Serializable
data class StabilizationAnalysis(
    val width: Int,
    val height: Int,
    val duration: Double,
    val samples: List<StabilizationSample>,
    val trackedFrames: Int,
) {
    val trackingFraction: Double get() = trackedFrames.toDouble() / max(1, samples.size - 1)

    val valid: Boolean
        get() = width > 0 && height > 0 && duration.isFinite() && duration > 0 && samples.isNotEmpty() &&
            samples.all { it.time.isFinite() && it.time >= 0 && it.time < duration + 0.001 && it.pose.valid } &&
            samples.zipWithNext().all { (a, b) -> a.time < b.time && a.segment <= b.segment }

    companion object {
        const val VERSION = "yourec-stabilization-2"

        /**
         * The name of the cached analysis of the stretch [start]…[end] of the recording named by
         * [fingerprint]: the hash of the analysis version, the recording and the stretch, as the
         * original names it (YouRec/VideoStabilizer.swift:33-35).
         */
        fun key(fingerprint: String, start: Double, end: Double): String = sha256("$VERSION|$fingerprint|$start|$end".toByteArray())

        fun decode(data: ByteArray): StabilizationAnalysis? =
            runCatching { documentJson.decodeFromString(serializer(), data.decodeToString()) }.getOrNull()
    }

    fun encoded(): ByteArray = documentJson.encodeToString(serializer(), this).toByteArray()
}

data class StabilizationPlan(
    val size: Size,
    val samples: List<StabilizationSample>,
    val zoom: Double,
    /** Whether a large bump made the correction weaker somewhere. */
    val strengthLimited: Boolean,
    val trackingFraction: Double,
) {
    val cropPercent: Int get() = roundedAwayFromZero((1 - 1 / zoom) * 100).toInt()

    /**
     * The transform of the source frame shown at [time]: the frame whose time
     * precedes it, as the player holds it, not an interpolated frame that does
     * not exist.
     */
    fun transform(time: Double): Affine {
        val first = samples.firstOrNull() ?: return Affine.IDENTITY
        if (time <= first.time) return first.pose.transform(size, zoom)
        var low = 0
        var high = samples.size
        while (low < high) {
            val middle = (low + high) / 2
            if (samples[middle].time <= time + 0.000001) low = middle + 1 else high = middle
        }
        return samples[max(0, low - 1)].pose.transform(size, zoom)
    }

    /**
     * [transform] for the picture turned upright by [rotation] clockwise degrees (0, 90, 180
     * or 270), as the port's renderers receive it: the original corrects the encoded frame
     * before its orientation is applied (YouRec/VideoToolCompositor.swift:158-161), which is
     * this correction carried through the turn. Upright pixels, top-left origin.
     */
    fun uprightTransform(time: Double, rotation: Int): Affine {
        val correction = transform(time)
        val turn = upright(rotation)
        return if (turn == Affine.IDENTITY) correction else turn.inverted().concatenating(correction).concatenating(turn)
    }

    /** Encoded pixels to upright pixels for a picture of [size] turned by [rotation] clockwise degrees. */
    fun upright(rotation: Int): Affine = when (((rotation % 360) + 360) % 360) {
        90 -> Affine(0.0, 1.0, -1.0, 0.0, size.height, 0.0)
        180 -> Affine(-1.0, 0.0, 0.0, -1.0, size.width, size.height)
        270 -> Affine(0.0, -1.0, 1.0, 0.0, 0.0, size.width)
        else -> Affine.IDENTITY
    }

    /**
     * The plan of a sample that starts [offset] seconds after the clip's reference start, timed
     * from that start: the tool's preview measures only a window around the playhead and its
     * corrections keep the clip's clock (YouRec/VideoStabilizer.swift:98-110).
     */
    fun shifted(offset: Double): StabilizationPlan = copy(samples = samples.map { it.copy(time = it.time + offset) })
}

/**
 * The camera path being measured, frame by frame, as the original's `measure` builds it
 * (YouRec/VideoStabilizer.swift:119-206): the motion between consecutive frames, scaled from
 * the analysis size to the encoded size, is chained into a pose for each frame; a frame that
 * cannot be tracked, comes more than 0.15 s after the one before, or would make the pose
 * invalid starts a new segment from the identity.
 *
 * [width] × [height] is the encoded size of the recording, [duration] the length of the stretch
 * measured. A frame is first offered with [accepts], at its time from the stretch's start; only
 * an accepted frame is compared with the previous one and given to [add].
 */
class StabilizationMeasure(val width: Int, val height: Int, val duration: Double) {
    /** The size frames are compared at: the longer side at most 640, each at least 64 (:129-130). */
    val analysisWidth: Int
    val analysisHeight: Int

    init {
        val ratio = min(1.0, 640.0 / max(width, height))
        analysisWidth = max(64, roundedAwayFromZero(width * ratio).toInt())
        analysisHeight = max(64, roundedAwayFromZero(height * ratio).toInt())
    }

    /** The motion from the previous frame to the current one, as the estimator gives it at the analysis size. */
    data class Motion(val x: Double, val y: Double, val angle: Double, val logScale: Double, val valid: Boolean)

    private val samples = ArrayList<StabilizationSample>()
    private var previousTime = Double.NEGATIVE_INFINITY
    private var pose = StabilizationPose.IDENTITY
    private var segment = 0
    private var tracked = 0

    /** How many frames are measured. */
    val count: Int get() = samples.size

    /** Whether a frame [time] seconds into the stretch is measured: one inside it and after the last (:153-159). */
    fun accepts(time: Double): Boolean = time.isFinite() && time < duration && time > previousTime

    /** Adds the frame at [time], with the [motion] from the previous frame (null for the first). */
    fun add(time: Double, motion: Motion?) {
        check(accepts(time)) { "frame at $time s is not measured" }
        if (samples.isNotEmpty()) {
            if (motion != null && motion.valid && time - previousTime < 0.15) {
                // Uniform analysis scaling preserves a similarity; the translation is scaled by each side's own ratio (:180-183).
                val step = StabilizationPose(motion.x * width / analysisWidth, motion.y * height / analysisHeight, motion.angle, motion.logScale)
                val next = step.applying(pose)
                if (next.valid) {
                    pose = next
                    tracked++
                } else {
                    segment++
                    pose = StabilizationPose.IDENTITY
                }
            } else {
                segment++
                pose = StabilizationPose.IDENTITY
            }
        }
        samples.add(StabilizationSample(if (samples.isEmpty()) 0.0 else time, pose, segment))
        previousTime = time
    }

    /** The analysis of the frames added; null when there were none (:203). */
    fun analysis(): StabilizationAnalysis? =
        if (samples.isEmpty()) null else StabilizationAnalysis(width, height, duration, samples.toList(), tracked)

    /** The time of the last frame added, from the stretch's start; negative before the first. */
    val measured: Double get() = previousTime

    /** What to keep to go on later: the path so far and [previousFrame], the last frame added at the analysis size (:191-196). */
    fun checkpoint(previousFrame: ByteArray): StabilizationCheckpoint? = analysis()?.let { StabilizationCheckpoint(it, previousFrame, previousTime) }

    companion object {
        /**
         * The measurement [checkpoint] stopped, going on from its last frame: its path, the pose and
         * segment of that frame and the frames tracked (:139-141). Check [StabilizationCheckpoint.resumes] first.
         */
        fun resuming(checkpoint: StabilizationCheckpoint): StabilizationMeasure {
            val path = checkpoint.path
            val last = path.samples.last()
            return StabilizationMeasure(path.width, path.height, path.duration).apply {
                samples.addAll(path.samples)
                previousTime = checkpoint.timestamp
                pose = last.pose
                segment = last.segment
                tracked = path.trackedFrames
            }
        }

        /**
         * The time of a frame shown at source second [presentation] in the stretch from [start]:
         * one shown before the stretch counts from its start (:153).
         */
        fun time(presentation: Double, start: Double): Double = max(0.0, presentation - start)
    }
}

object StabilizationPath {
    private const val FADE_SECONDS = 0.3
    private const val BUMP_SECONDS = 0.7

    /** [checkCancelled] is called now and then and may throw to stop. */
    fun make(analysis: StabilizationAnalysis, level: StabilizationLevel, checkCancelled: () -> Unit = {}): StabilizationPlan {
        val size = Size(analysis.width.toDouble(), analysis.height.toDouble())
        if (level == StabilizationLevel.Off) {
            return StabilizationPlan(size, emptyList(), 1.0, false, analysis.trackingFraction)
        }
        val source = analysis.samples
        val sigma = level.sigma
        val radius = sigma * 3
        val corrections = ArrayList<StabilizationSample>(source.size)
        // Gaussian local-linear regression keeps deliberate constant-speed
        // pans, also at the ends of a clip.
        var left = 0
        var right = 0
        var segmentStart = 0
        var segmentEnd = 0
        val sums = DoubleArray(4)
        val timeSums = DoubleArray(4)
        for (i in source.indices) {
            if (i % 128 == 0) checkCancelled()
            val current = source[i]
            if (i == 0 || source[i - 1].segment != current.segment) {
                segmentStart = i
                segmentEnd = i + 1
                while (segmentEnd < source.size && source[segmentEnd].segment == current.segment) segmentEnd++
            }
            while (left < i && (source[left].time < current.time - radius || source[left].segment != current.segment)) left++
            right = max(i + 1, right)
            while (right < source.size && source[right].segment == current.segment && source[right].time <= current.time + radius) right++
            var sw = 0.0
            var st = 0.0
            var stt = 0.0
            sums.fill(0.0)
            timeSums.fill(0.0)
            for (j in left until right) {
                // A segment change can leave the old right edge behind: never
                // borrow the first frame of the next shot.
                if (source[j].segment != current.segment) continue
                val dt = source[j].time - current.time
                val w = exp(-0.5 * (dt / sigma) * (dt / sigma))
                sw += w; st += w * dt; stt += w * dt * dt
                // Smooth the camera's position in the reference coordinates,
                // not the displacement in a zooming image.
                val p = source[j].pose.inverse
                val values = doubleArrayOf(p.x, p.y, p.angle, p.logScale)
                for (k in 0 until 4) {
                    sums[k] += w * values[k]
                    timeSums[k] += w * dt * values[k]
                }
            }
            val determinant = sw * stt - st * st
            val values = DoubleArray(4) { k ->
                if (determinant > 1e-10) (sums[k] * stt - timeSums[k] * st) / determinant else sums[k] / max(sw, 1e-10)
            }
            // A recorded zoom is deliberate: correct translation and rotation only.
            val smooth = StabilizationPose(values[0], values[1], values[2], current.pose.inverse.logScale).inverse
            var confidence = 1.0
            if (segmentStart > 0) confidence = min(confidence, fade(current.time - source[segmentStart].time))
            if (segmentEnd < source.size) confidence = min(confidence, fade(source[segmentEnd - 1].time - current.time))
            val correction = smooth.applying(current.pose.inverse).attenuated(confidence)
            corrections.add(StabilizationSample(current.time, correction, current.segment))
        }
        // One crop for the whole reference range, weakened smoothly around
        // large bumps, so one outlier cannot weaken a whole take.
        val pixelMargin = 1 + 3.0 / min(analysis.width, analysis.height)
        val allowed = corrections.map { sample ->
            if (sample.pose.requiredZoom(size) * pixelMargin <= level.maximumZoom) return@map 1.0
            var low = 0.0
            var high = 1.0
            repeat(24) {
                val middle = (low + high) / 2
                if (sample.pose.attenuated(middle).requiredZoom(size) * pixelMargin <= level.maximumZoom) low = middle else high = middle
            }
            low
        }
        var strengthLimited = false
        var zoom = 1.0
        left = 0
        right = 0
        for (i in corrections.indices) {
            checkCancelled()
            val t = corrections[i].time
            while (left < i && corrections[left].time < t - BUMP_SECONDS) left++
            right = max(i + 1, right)
            while (right < corrections.size && corrections[right].time <= t + BUMP_SECONDS) right++
            var strength = allowed[i]
            for (j in left until right) {
                if (allowed[j] >= 1 || corrections[j].segment != corrections[i].segment) continue
                val d = min(1.0, abs(t - corrections[j].time) / BUMP_SECONDS)
                strength = min(strength, allowed[j] + (1 - allowed[j]) * d * d * (3 - 2 * d))
            }
            strengthLimited = strengthLimited || strength < 0.97
            corrections[i] = corrections[i].copy(pose = corrections[i].pose.attenuated(strength))
            zoom = max(zoom, corrections[i].pose.requiredZoom(size))
        }
        // No pointless crop on a still or untrackable clip.
        if (zoom > 1.00001) zoom = min(level.maximumZoom, zoom * pixelMargin)
        return StabilizationPlan(size, corrections, zoom, strengthLimited, analysis.trackingFraction)
    }

    private fun fade(seconds: Double): Double {
        val x = min(1.0, max(0.0, seconds / FADE_SECONDS))
        return x * x * (3 - 2 * x)
    }
}

/**
 * The stretch of the movie a tool previews before it measures the whole of its footage: ten seconds around the
 * playhead inside what it applies to (YouRec/EffectApplication.swift:9-18).
 */
object EffectPreviewWindow {
    fun range(position: Double, selection: ClosedFloatingPointRange<Double>): ClosedFloatingPointRange<Double> {
        val anchor = min(selection.endInclusive, max(selection.start, position))
        return max(selection.start, anchor - 5)..min(selection.endInclusive, anchor + 5)
    }

    /** A clip of the Stabilize tool, with the second of the movie it begins at. */
    data class Piece(val clip: TimelineClip, val start: Double)

    /**
     * What the Stabilize tool previews of [pieces], the clips it works on in the order they play: ten seconds of
     * them joined end to end, around [anchor] (YouRec/EditorTools.swift:472-482, :731-743). [window] is in seconds
     * of that joined sequence, and [parts] the stretch of each clip inside it.
     */
    data class Sample(val window: ClosedFloatingPointRange<Double>, val parts: List<Part>)

    /**
     * The part of [clip] the preview measures: [from]…[to] seconds into the clip, which begins at second [start]
     * of the movie, showing source seconds [sourceFrom]…[sourceTo] (EffectApplication.swift:20-41).
     */
    data class Part(val clip: TimelineClip, val start: Double, val from: Double, val to: Double, val sourceFrom: Double, val sourceTo: Double)

    /**
     * The sample of [pieces] around the playhead at [position]: the anchor is the playhead where it is in one of
     * them, else the nearest edge of the nearest one, counted in the clips joined end to end (EditorTools.swift:472-482).
     * A clip outside the window is left out; a held frame is measured whole (EffectApplication.swift:34-41).
     */
    fun sample(pieces: List<Piece>, position: Double): Sample {
        var offset = 0.0
        var nearest = Double.POSITIVE_INFINITY
        var anchor = 0.0
        for (piece in pieces) {
            val local = min(piece.clip.duration, max(0.0, position - piece.start))
            val distance = abs(position - (piece.start + local))
            if (distance < nearest) { nearest = distance; anchor = offset + local }
            offset += piece.clip.duration
        }
        val window = range(anchor, 0.0..offset)
        val parts = ArrayList<Part>()
        var at = 0.0
        for (piece in pieces) {
            val clip = piece.clip
            val from = max(0.0, window.start - at)
            val to = min(clip.duration, window.endInclusive - at)
            at += clip.duration
            if (to <= from) continue
            val (a, b) = if (clip.frozenFrame != null) 0.0 to clip.duration else from to to
            val sourceFrom = if (clip.frozenFrame != null) clip.start else clip.sourceTime(a)
            val sourceTo = if (clip.frozenFrame != null) clip.end else clip.sourceTime(b)
            if (sourceTo > sourceFrom) parts += Part(clip, piece.start, a, b, sourceFrom, sourceTo)
        }
        return Sample(window, parts)
    }

    /** The length of [range] as the label says it, "10" or "0.4": a decimal only under a second, halves to even as Foundation formats (:14-17). */
    fun seconds(range: ClosedFloatingPointRange<Double>): String {
        val seconds = range.endInclusive - range.start
        return java.math.BigDecimal(seconds).setScale(if (seconds < 1) 1 else 0, java.math.RoundingMode.HALF_EVEN).toPlainString()
    }
}
