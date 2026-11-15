package app.takes.core

/**
 * How the recorder follows the phone as it is turned. The original lets its whole interface turn and
 * holds it from the first Record to Stop (YouRec/RecorderView.swift:341-390); here the screen stays
 * portrait, and what turns is each control, in its place, and the frame the take is recorded in.
 *
 * Angles are those of the phone's orientation sensor: 0 with the phone upright, 90 with its left side
 * up, 180 upside down, 270 with its right side up. A quarter is one of those four.
 */
object DeviceTurn {
    /** Degrees past the middle between two quarters the phone must go before the other quarter is taken. */
    const val SLACK = 10

    /**
     * The quarter the phone is held in, given the sensor's [degrees] and the quarter taken [before].
     * The nearest one, except that near the middle between two the one taken before is kept, so that a
     * phone held diagonally does not flip back and forth. A reading that is not an angle keeps [before]:
     * the sensor gives none while the phone lies flat.
     */
    fun quarter(degrees: Int, before: Int): Int {
        if (degrees < 0) return before
        val angle = degrees % 360
        val nearest = (angle + 45) / 90 % 4 * 90
        if (nearest == before) return before
        // How far the phone is from the quarter it was in, the short way round.
        val away = minOf((angle - before + 360) % 360, (before - angle + 360) % 360)
        return if (away <= 45 + SLACK) before else nearest
    }

    /** Degrees, clockwise on the screen, a control is turned by to stand upright for a phone held in [quarter]. */
    fun controls(quarter: Int): Int = (360 - quarter % 360) % 360

    /** Whether a control stands across the screen: the phone is on one of its long sides. */
    fun sideways(quarter: Int): Boolean = quarter % 180 != 0

    /**
     * The angle to move to from [from] so that a control turns to [target] the short way round:
     * [from] itself may have gone past a full turn. Half a turn goes clockwise.
     */
    fun shortest(from: Float, target: Int): Float {
        val delta = ((target - from) % 360 + 540) % 360 - 180
        return from + if (delta == -180f) 180f else delta
    }
}
