package app.takes.core

/**
 * The short stretches the editor plays to check something: a quick look
 * around the playhead, a cut before it is made, a transition, an effect.
 * Each is kept inside what there is to play.
 */
object ReviewWindows {
    /** Seconds played before and after the playhead, or before and after a cut. */
    const val REVIEW = 1.0

    /** Seconds played before and after a transition when it is being edited. */
    const val TRANSITION_MARGIN = 0.65

    /** Seconds heard or sampled before and after the playhead while adjusting an effect. */
    const val EFFECT_REACH = 5.0

    /** A second tap on Play within this many seconds is a quick review; otherwise the first tap plays. */
    const val DOUBLE_TAP = 0.27

    /** Kept short of the very end, where a player stops by itself. */
    const val END_MARGIN = 0.01

    /** Play from [start] to [end], then put the playhead back at [returnTo]. */
    data class Review(val start: Double, val end: Double, val returnTo: Double)

    /**
     * Quick review: a second before the playhead to a second after it, then
     * back where it was. [at] is the playhead before any review began, so a
     * review asked for during another one is around the same place.
     */
    fun quick(at: Double, duration: Double): Review {
        val anchor = minOf(duration, maxOf(0.0, at))
        return Review(maxOf(0.0, anchor - REVIEW), minOf(duration, anchor + REVIEW), anchor)
    }

    /**
     * Previewing a removal before it is made. It plays the movie as it would
     * be without the removed stretch, across the place where the stretch
     * began: [rangeStart] is that place and [remaining] is how long the movie
     * would be. Afterwards the playhead goes back to [playhead] in the movie
     * as it is. Null when nothing would be left to play.
     */
    fun cut(rangeStart: Double, remaining: Double, playhead: Double): Review? {
        if (!(remaining > 0)) return null
        val start = maxOf(0.0, rangeStart - REVIEW)
        return Review(start, maxOf(start, minOf(remaining - END_MARGIN, rangeStart + REVIEW)), playhead)
    }

    /** What the transition editor plays and scrubs: the transition's whole stretch from [rangeStart] to [rangeEnd] and a little on each side. */
    fun transition(rangeStart: Double, rangeEnd: Double, duration: Double): ClosedFloatingPointRange<Double> =
        maxOf(0.0, rangeStart - TRANSITION_MARGIN)..minOf(duration, rangeEnd + TRANSITION_MARGIN)

    /**
     * What an effect being adjusted is tried on: [EFFECT_REACH] seconds on
     * each side of the playhead, never leaving the selected stretch. A
     * playhead outside the selection counts as its nearer end.
     */
    fun effect(position: Double, selection: ClosedFloatingPointRange<Double>): ClosedFloatingPointRange<Double> {
        val anchor = minOf(selection.endInclusive, maxOf(selection.start, position))
        return maxOf(selection.start, anchor - EFFECT_REACH)..minOf(selection.endInclusive, anchor + EFFECT_REACH)
    }
}
