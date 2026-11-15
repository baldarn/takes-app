package app.takes.core

/**
 * How loud each side of a transition is. The shape comes from
 * [TransitionTiming.audioIn] and [TransitionTiming.audioOut], which already
 * hold everything the settings say about sound: the style's default (one
 * crossfade as long as the picture effect; down, silence, up around a fade
 * through color; a cut on each side of a freeze), a split screen keeping the
 * first or the second clip's sound, and a custom start offset and blend
 * (a negative offset is a J-cut, an offset past the picture an L-cut).
 *
 * Ramps are linear in amplitude and never shorter than [MINIMUM_RAMP], so a
 * blend of zero ("Cut") is still a few milliseconds long. A level multiplies
 * the clip's own level, its own fades and anything else shaping its sound.
 *
 * Only sound that follows a picture clip is shaped; sound detached from its
 * picture is not.
 */
object TransitionSound {
    /** The shortest ramp, in seconds. */
    const val MINIMUM_RAMP = 0.008

    /** The level of each side, 0 to 1. A side that does not exist (the first at an opening, the second at a close) is 0. */
    data class Levels(val first: Double, val second: Double)

    /** The level of a sound coming up over [ramp], at [time] on the ramp's own clock: 0 before it, 1 after. */
    fun rising(time: Double, ramp: ClosedFloatingPointRange<Double>): Double =
        clamped((time - ramp.start) / maxOf(MINIMUM_RAMP, ramp.endInclusive - ramp.start))

    /** The level of a sound going down over [ramp]: 1 before it, 0 from its end. A ramp shorter than the minimum is stretched backwards. */
    fun falling(time: Double, ramp: ClosedFloatingPointRange<Double>): Double =
        clamped((ramp.endInclusive - time) / maxOf(MINIMUM_RAMP, ramp.endInclusive - ramp.start))

    /** The level of the outgoing clip's sound [elapsed] seconds after the transition's origin. */
    fun outgoing(timing: TransitionTiming, elapsed: Double): Double = falling(elapsed, timing.audioOut)

    /** The level of the incoming clip's sound [elapsed] seconds after the transition's origin. */
    fun incoming(timing: TransitionTiming, elapsed: Double): Double = rising(elapsed, timing.audioIn)

    /**
     * Both levels [elapsed] seconds after the origin of a transition with the
     * fitted [timing]. [edge] must be the one the timing was made with.
     */
    fun levels(timing: TransitionTiming, elapsed: Double, edge: Int = 0): Levels =
        Levels(if (edge == -1) 0.0 else outgoing(timing, elapsed), if (edge == 1) 0.0 else incoming(timing, elapsed))

    /** Both levels straight from the settings, fitted by [factor]. */
    fun levels(settings: TransitionSettings, elapsed: Double, factor: Double = 1.0, edge: Int = 0): Levels =
        levels(TransitionTiming.of(settings, factor, edge), elapsed, edge)

    /** The timeline seconds over which the incoming clip's sound comes up, for a transition whose picture effect starts at [origin]. */
    fun rampIn(timing: TransitionTiming, origin: Double): ClosedFloatingPointRange<Double> =
        (origin + timing.audioIn.start)..(origin + timing.audioIn.endInclusive)

    /** The timeline seconds over which the outgoing clip's sound goes down. */
    fun rampOut(timing: TransitionTiming, origin: Double): ClosedFloatingPointRange<Double> =
        (origin + timing.audioOut.start)..(origin + timing.audioOut.endInclusive)

    /**
     * The share of a clip's sound left by its transitions at timeline second
     * [time]: [entering] is the ramp of the transition into the clip
     * ([rampIn]), [leaving] the ramp of the one out of it ([rampOut]); null
     * where the clip has a plain cut.
     */
    fun clipLevel(time: Double, entering: ClosedFloatingPointRange<Double>?, leaving: ClosedFloatingPointRange<Double>?): Double =
        (entering?.let { rising(time, it) } ?: 1.0) * (leaving?.let { falling(time, it) } ?: 1.0)

    private fun clamped(value: Double) = minOf(1.0, maxOf(0.0, value))
}
