package app.takes.core

import kotlin.math.abs

/**
 * A ceiling on the frame rate of footage going into a movie: which frames of
 * a clip are kept so that no more than [framesPerSecond] remain. Frames are
 * never invented, and footage at or below the ceiling keeps every frame.
 *
 * A frame is kept when it lies nearer to one frame interval after the last
 * kept frame than the next frame is expected to, the next frame being
 * expected as far after this one as this one came after the one before. The
 * choice is made as each frame arrives, so a clip's last frame is decided
 * like any other and is not held back waiting for a frame that never comes.
 */
class FrameCeiling(framesPerSecond: Double) {
    private val interval = (1_000_000 / framesPerSecond).toLong()
    private var kept = UNSET
    private var previous = UNSET

    /** Whether the frame at [timeUs] microseconds stays. Call once per frame, in order. */
    fun keeps(timeUs: Long): Boolean {
        if (kept == UNSET) {
            kept = timeUs
            previous = timeUs
            return true
        }
        val expected = timeUs + (timeUs - previous)
        previous = timeUs
        val stays = abs(timeUs - kept - interval) <= abs(expected - kept - interval)
        if (stays) kept = timeUs
        return stays
    }

    /** Starts again for another clip. */
    fun reset() {
        kept = UNSET
        previous = UNSET
    }

    companion object {
        private const val UNSET = Long.MIN_VALUE

        /**
         * The frames per second left of footage at [footage] frames per
         * second under a ceiling of [ceiling]: every frame when the footage
         * is at or below it, else one frame in as many as bring it nearest to
         * the ceiling (60 under 30 leaves 30, 50 under 30 leaves 25).
         */
        fun rate(footage: Double, ceiling: Double): Double =
            if (!(footage > 0) || !(ceiling > 0)) footage else footage / maxOf(1.0, Math.rint(footage / ceiling))
    }
}
