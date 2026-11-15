package app.takes.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Where things are in each transition style at a given progress: the numbers
 * a renderer needs, without any rendering. Progress is 0 at the first clip
 * and 1 at the second, as [TransitionTiming.progress] gives it.
 *
 * Canvas coordinates are the original's: the origin at the bottom left, y
 * upward. A top-down renderer flips y (`canvas.height - y`).
 */
object TransitionGeometry {
    /** The ease every moving style applies to progress: slow at both ends. */
    fun eased(progress: Double): Double {
        val t = minOf(1.0, maxOf(0.0, progress))
        return t * t * (3 - 2 * t)
    }

    /**
     * The edge of a wipe. It is a band 2·[feather] wide, centered on [center]
     * and perpendicular to ([dx], [dy]), the direction it travels. Behind it
     * (towards [p0]) is the second clip; ahead of it (beyond [p1]) the first.
     */
    data class WipeEdge(val dx: Double, val dy: Double, val span: Double, val feather: Double, val center: Point, val p0: Point, val p1: Point) {
        /** How much of the second clip shows at [point]: 1 behind the edge, 0 ahead, a linear ramp across it. */
        fun secondShare(point: Point): Double {
            val along = (point.x - p0.x) * dx + (point.y - p0.y) * dy
            return minOf(1.0, maxOf(0.0, 1 - along / (2 * feather)))
        }
    }

    fun wipe(settings: TransitionSettings, progress: Double, canvas: Size): WipeEdge {
        val angle = settings.angle * Math.PI / 180
        val dx = cos(angle)
        // Clockwise on screen is downward, which is negative in bottom-up coordinates.
        val dy = -sin(angle)
        // The canvas measured along the direction of travel.
        val span = abs(dx) * canvas.width + abs(dy) * canvas.height
        val feather = if (settings.hardEdge == true) 0.5 else maxOf(1.0, span * 0.035)
        // The band starts wholly off one side and ends wholly off the other.
        val distance = (span + 2 * feather) * eased(progress) - span / 2 - feather
        val center = Point(canvas.width / 2 + dx * distance, canvas.height / 2 + dy * distance)
        return WipeEdge(
            dx, dy, span, feather, center,
            Point(center.x - dx * feather, center.y - dy * feather), Point(center.x + dx * feather, center.y + dy * feather),
        )
    }

    /**
     * The two halves of a split screen: the first clip's on the left or on
     * top, taking [TransitionSettings.divider] of the canvas.
     */
    fun splitRects(settings: TransitionSettings, canvas: Size): Pair<Rect, Rect> {
        if (settings.axis == TransitionAxis.Horizontal) {
            val split = canvas.width * settings.divider
            return Rect(0.0, 0.0, split, canvas.height) to Rect(split, 0.0, canvas.width - split, canvas.height)
        }
        val split = canvas.height * settings.divider
        return Rect(0.0, canvas.height - split, canvas.width, split) to Rect(0.0, 0.0, canvas.width, canvas.height - split)
    }

    /** The thin gray line drawn over the divider. */
    fun splitLine(settings: TransitionSettings, canvas: Size): Rect {
        val first = splitRects(settings, canvas).first
        val lineWidth = maxOf(1.0, minOf(canvas.width, canvas.height) * 0.002)
        return if (settings.axis == TransitionAxis.Horizontal) Rect(first.maxX - lineWidth / 2, 0.0, lineWidth, canvas.height)
        else Rect(0.0, first.minY - lineWidth / 2, canvas.width, lineWidth)
    }

    /** Shares of the first clip alone, the side-by-side pair and the second clip alone; they add up to 1. */
    data class SplitMix(val first: Double, val pair: Double, val second: Double)

    /** Brief, restrained entry/exit; most of the interval holds both views. Progress is not eased here. */
    fun splitMix(progress: Double): SplitMix {
        val t = minOf(1.0, maxOf(0.0, progress))
        if (t < SPLIT_EDGE) return SplitMix(1 - t / SPLIT_EDGE, t / SPLIT_EDGE, 0.0)
        if (t > 1 - SPLIT_EDGE) return ((t - 1 + SPLIT_EDGE) / SPLIT_EDGE).let { SplitMix(0.0, 1 - it, it) }
        return SplitMix(0.0, 1.0, 0.0)
    }

    /** A clip placed in its half: the part of the source shown (top-down source coordinates) and how much it is scaled. */
    data class Framed(val crop: Rect, val scale: Double)

    fun framed(framing: TransitionFraming, source: Size, viewport: Rect): Framed {
        val crop = framing.crop(source, viewport.size)
        return Framed(crop, viewport.width / maxOf(0.001, crop.width))
    }

    /**
     * The brightness key of the Hitchcock style. The share of the second clip
     * at a pixel is [scale]·luma + [bias], limited to 0–1, where luma is the
     * first clip's perceptual brightness limited to 0–1.
     */
    data class LuminanceKey(val scale: Double, val bias: Double) {
        fun secondShare(luma: Double): Double = minOf(1.0, maxOf(0.0, scale * minOf(1.0, maxOf(0.0, luma)) + bias))

        /** Rec. 709 weights on sRGB-encoded components: perceptual brightness, not linear-light energy. */
        fun secondShare(red: Double, green: Double, blue: Double): Double {
            fun limited(value: Double) = minOf(1.0, maxOf(0.0, value))
            return minOf(1.0, maxOf(0.0, scale * (LUMA_RED * limited(red) + LUMA_GREEN * limited(green) + LUMA_BLUE * limited(blue)) + bias))
        }
    }

    fun hitchcock(settings: TransitionSettings, progress: Double): LuminanceKey {
        // The threshold sweeps from just below black to just above white, so both ends are clean.
        val threshold = eased(progress) * (1 + 2 * HITCHCOCK_FEATHER) - HITCHCOCK_FEATHER
        val highlights = settings.highlightsFirst == true
        return LuminanceKey(
            (if (highlights) 1.0 else -1.0) / HITCHCOCK_FEATHER,
            (if (highlights) threshold - 1 else threshold) / HITCHCOCK_FEATHER + 0.5,
        )
    }

    /**
     * A whip pan: both clips slide along one axis, blurred along it, while
     * they cross-dissolve by [mix]. Offsets are in canvas pixels in the
     * direction of travel: to the right, or downward on screen.
     */
    data class Whip(val vertical: Boolean, val firstOffset: Double, val secondOffset: Double, val blurRadius: Double, val mix: Double)

    fun whip(settings: TransitionSettings, progress: Double, canvas: Size): Whip {
        val t = minOf(1.0, maxOf(0.0, progress))
        val vertical = settings.axis == TransitionAxis.Vertical
        val distance = (if (vertical) canvas.height else canvas.width) * 0.65 * (if (settings.reverse) -1.0 else 1.0)
        val e = eased(t)
        // The first clip leaves backwards as the second arrives from ahead; the blur peaks midway.
        return Whip(vertical, -e * distance, (1 - e) * distance, minOf(canvas.width, canvas.height) * 0.12 * sin(Math.PI * t), e)
    }

    /**
     * Whether a flash cut shows the next clip at [progress]. [duration] is the
     * transition's real length when it was fitted to shorter footage.
     */
    fun flashShowsSecond(settings: TransitionSettings, progress: Double, duration: Double? = null, framesPerSecond: Double = 30.0): Boolean {
        val t = minOf(1.0, maxOf(0.0, progress))
        if (t <= 0) return false
        if (t >= 1) return true
        val length = duration ?: settings.duration
        val frame = floor(t * length * framesPerSecond + 0.000001).toInt()
        return frame in settings.flashFrames(length, framesPerSecond)
    }

    /**
     * Defocus: both clips are blurred by an optical (disc) blur of
     * [blurRadius] canvas pixels, and the second replaces the first by [mix]
     * in the middle third, where the blur hides it.
     */
    data class Defocus(val blurRadius: Double, val mix: Double)

    fun defocus(settings: TransitionSettings, progress: Double, canvas: Size): Defocus {
        val t = minOf(1.0, maxOf(0.0, progress))
        return Defocus(
            minOf(canvas.width, canvas.height) * 0.05 * (settings.blurAmount ?: DEFAULT_BLUR_AMOUNT) * sin(Math.PI * t),
            minOf(1.0, maxOf(0.0, (t - 0.35) / 0.3)),
        )
    }

    /**
     * The factor to shrink a picture by before a large blur. Preserve full
     * detail near the sharp endpoints; large optical kernels need fewer
     * pixels. The blur radius remains in output-picture units, so it is
     * multiplied by this factor too.
     */
    fun blurScale(radius: Double, canvas: Size): Double =
        minOf(1.0, maxOf(960 / maxOf(canvas.width, canvas.height), 2 / maxOf(2.0, radius)))

    const val SPLIT_EDGE = 0.15
    const val HITCHCOCK_FEATHER = 0.08
    const val DEFAULT_BLUR_AMOUNT = 0.75
    const val LUMA_RED = 0.2126
    const val LUMA_GREEN = 0.7152
    const val LUMA_BLUE = 0.0722
}
