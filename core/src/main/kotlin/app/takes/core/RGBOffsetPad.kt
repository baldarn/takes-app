package app.takes.core

import kotlin.math.floor
import kotlin.math.hypot

/**
 * The arithmetic of RGB Offset's pads: a cross for each channel inside a
 * circle whose edge is an offset of 1, the most the effect moves a channel.
 * YouRec/VideoEffects/RGBOffsetPads.swift:3-69.
 *
 * The offsets are in the recording's axes; the recorder's selfie preview can
 * show them mirrored, so a pad is drawn and dragged as the picture is shown
 * ([mirrored]: the x axis turned), where the original takes the orientation
 * of its preview's projection (RGBOffsetPads.swift:9-16). The editor's preview
 * is never mirrored.
 */
object RGBOffsetPad {
    /** How far a step of a screen reader's Move action takes the cross. RGBOffsetPads.swift:63-66. */
    const val STEP = 0.03

    /** Where the cross of a channel is drawn, −1…1 from the pad's centre, for its offset [x], [y]. RGBOffsetPads.swift:33. */
    fun displayed(x: Double, y: Double, mirrored: Boolean): Point = Point(if (mirrored) -x else x, y)

    /**
     * The offset for a cross put at [point] on the pad (−1…1 from its centre, as drawn): a point
     * past the edge is brought back onto it, and each number held to −1…1. RGBOffsetPads.swift:36-40.
     */
    fun place(point: Point, mirrored: Boolean): Point {
        val radius = maxOf(1.0, hypot(point.x, point.y))
        val x = point.x / radius
        val y = point.y / radius
        return Point((if (mirrored) -x else x).coerceIn(-1.0, 1.0), y.coerceIn(-1.0, 1.0))
    }

    /** What a screen reader says of a cross: its place in whole percents, as drawn. RGBOffsetPads.swift:34. */
    fun reading(x: Double, y: Double, mirrored: Boolean): Pair<Int, Int> {
        val shown = displayed(x, y, mirrored)
        // Swift's rounded(): halves away from zero.
        fun percent(v: Double) = (if (v < 0) -floor(-v * 100 + .5) else floor(v * 100 + .5)).toInt()
        return Pair(percent(shown.x), percent(shown.y))
    }
}
