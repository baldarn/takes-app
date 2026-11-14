package app.takes.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.pow

/** The two ends of a motion. */
enum class EffectEndpoint { A, B }

@Serializable
enum class EffectCurve(val title: String) {
    @SerialName("linear") Linear("Linear"),
    @SerialName("easeIn") EaseIn("Ease in"),
    @SerialName("easeOut") EaseOut("Ease out"),
    @SerialName("smooth") Smooth("Ease in & out"),

    /** Reaches B after the rise and stays there. */
    @SerialName("riseAndHold") RiseAndHold("Rise & hold"),

    /** Reaches B, stays, and comes back to A by the end. */
    @SerialName("riseHoldReturn") RiseHoldReturn("Rise, hold & return");

    val hasRise: Boolean get() = this == RiseAndHold || this == RiseHoldReturn
}

/** How the rise and the return of a [EffectCurve] with a rise are eased. */
@Serializable
enum class RampShape(val title: String) {
    @SerialName("linear") Linear("Linear"),
    @SerialName("easeIn") EaseIn("Ease in"),
    @SerialName("easeOut") EaseOut("Ease out"),
    @SerialName("smooth") Smooth("Ease in & out");

    fun sample(x: Double): Double = when (this) {
        Linear -> x
        EaseIn -> x * x * x
        EaseOut -> 1 - (1 - x).pow(3)
        Smooth -> x * x * (3 - 2 * x)
    }
}

@Serializable
data class EffectTiming(
    val curve: EffectCurve = EffectCurve.Linear,
    val rise: Double = 1.0,
    val release: Double = 1.0,
    val ramp: RampShape = RampShape.Linear,
) {
    data class Phases(val rise: Double, val hold: Double, val release: Double)

    /** The requested rise and release, fitted into [duration]; the rise wins when both do not fit. */
    fun phases(duration: Double): Phases {
        val d = maxOf(0.0, duration)
        val up = minOf(d, maxOf(0.0, rise))
        val down = if (curve == EffectCurve.RiseHoldReturn) minOf(d - up, maxOf(0.0, release)) else 0.0
        return Phases(up, maxOf(0.0, d - up - down), down)
    }

    /** The elapsed time at which an endpoint can be seen: A at the start, B at its peak, on a whole frame. */
    fun endpoint(endpoint: EffectEndpoint, duration: Double, frame: Double): Double {
        if (endpoint != EffectEndpoint.B) return 0.0
        val last = maxOf(0.0, duration - frame)
        return if (curve.hasRise) minOf(last, ceil(phases(duration).rise / frame - 0.000001) * frame) else last
    }

    /**
     * Explicitly choosing B follows its peak as the timing changes. Scrubbing
     * pins a frame even if the user later drags the peak across that frame.
     */
    fun previewPosition(position: Double, following: EffectEndpoint?, duration: Double, frame: Double): Double =
        following?.let { endpoint(it, duration, frame) } ?: position

    /** How far from A (0) to B (1) the motion is, [elapsed] seconds in. The last frame is the end of the motion. */
    fun fraction(elapsed: Double, duration: Double, frame: Double): Double {
        val last = maxOf(0.0, duration - frame)
        val x = minOf(1.0, maxOf(0.0, elapsed / maxOf(0.000001, last)))
        return when (curve) {
            EffectCurve.Linear -> x
            EffectCurve.EaseIn -> x * x * x
            EffectCurve.EaseOut -> 1 - (1 - x).pow(3)
            EffectCurve.Smooth -> x * x * (3 - 2 * x)
            EffectCurve.RiseAndHold, EffectCurve.RiseHoldReturn -> {
                val p = phases(duration)
                val peak = minOf(last, p.rise)
                if (elapsed < peak && peak > 0) return ramp.sample(maxOf(0.0, elapsed / peak))
                if (curve == EffectCurve.RiseHoldReturn && p.release > 0) {
                    if (elapsed >= last && peak < last) return 0.0
                    val falling = minOf(last, maxOf(peak, duration - p.release))
                    if (elapsed > falling) return 1 - ramp.sample(minOf(1.0, (elapsed - falling) / maxOf(0.000001, last - falling)))
                }
                1.0
            }
        }
    }
}

/**
 * Only animatable controls are captured. Media identity, scope, and layer order
 * continue to belong to the tool that owns the animation.
 *
 * [look] and [lut] are carried by color poses only; they are not interpolated.
 */
@Serializable
data class EffectPose(
    val values: Map<String, Double>,
    val look: ColorLook? = null,
    val lut: ImportedLUT? = null,
) {
    /** A missing control reads as zero. */
    operator fun get(key: String): Double = values[key] ?: 0.0
}

/** One point of a motion's clock: at this source second, this much of the motion has elapsed. */
@Serializable
data class StrobeTimePoint(val source: Double, val elapsed: Double)

/**
 * A move between two poses, A and B, of one tool. The owner's own fields stay
 * as they were; while [EffectAnimation] is set it decides what is shown.
 *
 * The motion is sampled by source second, through [clock], so it follows its
 * footage through trims, splits and speed changes without being rewritten.
 */
@Serializable
data class EffectAnimation(
    val a: EffectPose,
    val b: EffectPose,
    val timing: EffectTiming = EffectTiming(),
    val duration: Double,
    val frame: Double = PauseDetector.FRAME,
    val clock: List<StrobeTimePoint> = emptyList(),
) {
    /** A motion that does not move yet: both ends are [pose]. */
    constructor(pose: EffectPose, duration: Double) : this(a = pose, b = pose, duration = duration)

    operator fun get(endpoint: EffectEndpoint): EffectPose = if (endpoint == EffectEndpoint.A) a else b

    fun with(endpoint: EffectEndpoint, pose: EffectPose): EffectAnimation =
        if (endpoint == EffectEndpoint.A) copy(a = pose) else copy(b = pose)

    /** Ties the clock to a clip's playback, beginning [offset] seconds into the motion. */
    fun anchored(spans: List<EffectPlaybackSpan>, offset: Double): EffectAnimation {
        val points = mutableListOf<StrobeTimePoint>()
        var time = offset
        for (span in spans) {
            if (points.lastOrNull()?.source != span.start) points += StrobeTimePoint(span.start, time)
            time += span.duration
            points += StrobeTimePoint(span.end, time)
        }
        return copy(clock = points)
    }

    /** Ties the clock to a held frame, which plays no source time of its own. */
    fun anchored(hold: FrozenFrameSegment, offset: Double): EffectAnimation {
        val start = hold.sourceTime + hold.offset
        return copy(clock = listOf(StrobeTimePoint(start, offset), StrobeTimePoint(start + hold.duration, offset + hold.duration)))
    }

    /** Re-applying unchanged settings must not move an existing motion's clock. */
    fun placed(spans: List<EffectPlaybackSpan>, offset: Double, replacing: EffectAnimation?): EffectAnimation =
        if (replacing != null && hasSameSettings(replacing)) replacing else anchored(spans, offset)

    fun hasSameSettings(other: EffectAnimation): Boolean = a == other.a && b == other.b && timing == other.timing

    /** Seconds of the motion that have run when the source is at [source]. Without a clock, source time is the clock. */
    fun elapsed(source: Double): Double {
        val first = clock.firstOrNull() ?: return source
        val last = clock.last()
        if (source <= first.source) return first.elapsed
        if (source >= last.source) return last.elapsed
        var lower = 0
        var upper = clock.size - 1
        while (upper - lower > 1) {
            val mid = (upper + lower) / 2
            if (clock[mid].source < source) lower = mid else upper = mid
        }
        val l = clock[lower]
        val r = clock[upper]
        return l.elapsed + (r.elapsed - l.elapsed) * (source - l.source) / (r.source - l.source)
    }

    fun fraction(source: Double): Double = timing.fraction(elapsed(source), duration, frame)

    /**
     * [template] is the owner's current pose: both ends must hold exactly its
     * controls. [accepts] checks the owner's own ranges on each end.
     */
    fun valid(template: EffectPose, accepts: (EffectPose) -> Boolean): Boolean =
        duration.isFinite() && duration > 0 && frame.isFinite() && frame > 0 &&
            timing.rise.isFinite() && timing.rise >= 0 && timing.release.isFinite() && timing.release >= 0 &&
            clock.all { it.source.isFinite() && it.elapsed.isFinite() } &&
            clock.zipWithNext().all { (earlier, later) -> earlier.source < later.source && earlier.elapsed <= later.elapsed } &&
            listOf(a, b).all { p ->
                p.values.keys == template.values.keys && p.values.values.all { it.isFinite() } &&
                    (p.lut?.valid ?: true) && (template.look != null || (p.look == null && p.lut == null)) && accepts(p)
            }

    /** One control, [fraction] of the way from A to B. */
    fun value(key: String, fraction: Double): Double {
        val start = a[key]
        val end = b[key]
        // Angles take the short way round.
        if (key == "rotation" || key == "hue") {
            return CropSettings.normalizedRotation(start + fraction * CropSettings.normalizedRotation(end - start))
        }
        // Magnification is perceived in ratios: equal steps in time multiply it equally.
        if (key == "zoom" && start > 0 && end > 0) return start * (end / start).pow(fraction)
        return start + (end - start) * fraction
    }

    /** The pose shown at a source second. In between, only the numbers move; a look or LUT belongs to an end. */
    fun pose(source: Double): EffectPose {
        val f = fraction(source)
        if (f <= 0) return a
        if (f >= 1) return b
        return EffectPose(a.values.keys.associateWith { value(it, f) })
    }
}

/** The controls of a crop that can move. */
val CropSettings.pose: EffectPose
    get() = EffectPose(mapOf("x" to x, "y" to y, "zoom" to zoom, "rotation" to rotation))

/** This crop fixed at [pose], no longer moving. */
fun CropSettings.applying(pose: EffectPose): CropSettings =
    copy(x = pose["x"], y = pose["y"], zoom = pose["zoom"], rotation = pose["rotation"], motion = null)

/** The crop shown at a source second: its motion sampled there, or itself when it does not move. */
fun CropSettings.at(source: Double): CropSettings = motion?.let { applying(it.pose(source)) } ?: this

/** Whether a pose is a crop a saved project may hold. */
fun CropSettings.Companion.accepts(pose: EffectPose): Boolean =
    pose["x"] in 0.0..1.0 && pose["y"] in 0.0..1.0 && pose["zoom"] in MINIMUM_ZOOM..MAXIMUM_ZOOM && pose["rotation"] in -180.0..180.0
