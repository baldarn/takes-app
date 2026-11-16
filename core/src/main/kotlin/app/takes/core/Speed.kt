package app.takes.core

import kotlinx.serialization.Serializable

/** A constant playback rate for a clip: 2 plays twice as fast and takes half the time. */
@Serializable
data class SpeedSettings(val id: String = newID(), val rate: Double = 1.0) {
    /** The range a saved project may hold. */
    val valid: Boolean get() = rate.isFinite() && rate in MINIMUM_RATE..MAXIMUM_RATE

    companion object {
        const val MINIMUM_RATE = 0.25
        const val MAXIMUM_RATE = 4.0

        /** The step of the Speed tool's slider, as the original's (EditorTools.swift:1312). */
        const val STEP = 0.05

        /** [rate] on the slider's grid of twentieths ([STEP]) and inside its range, free of rounding noise. */
        fun stepped(rate: Double): Double =
            (Math.round(rate * 20) / 20.0).coerceIn(MINIMUM_RATE, MAXIMUM_RATE)
    }
}

/**
 * All tool timing is anchored to immutable source media. Timeline time can
 * change after a speed edit, pause removal, split or reorder without losing it.
 *
 * These functions are the one source clock for duration, seeking, audio and
 * the picture: they convert between a clip's source seconds and the seconds it
 * takes on the timeline.
 */
object SpeedMapping {
    /**
     * The rate a clip really plays at. [sequenceRate] is the product of speed
     * effects active on top of the clip's own setting; the limits are wider
     * than a single setting's so the two can combine.
     */
    fun rate(speed: SpeedSettings?, sequenceRate: Double = 1.0): Double =
        minOf(MAXIMUM_PLAYBACK_RATE, maxOf(MINIMUM_PLAYBACK_RATE, (speed?.rate ?: 1.0) * sequenceRate))

    /** What a clip plays: its source range without [cuts], all at one rate. */
    fun spans(clip: TimelineClip, speed: SpeedSettings?, cuts: List<SourceInterval> = emptyList()): List<EffectPlaybackSpan> {
        val rate = rate(speed)
        return PauseDetector.keptIntervals(clip, cuts).map { EffectPlaybackSpan(it.start, it.end, rate) }
    }

    /** Seconds the spans take on the timeline. */
    fun duration(spans: List<EffectPlaybackSpan>): Double = spans.sumOf { it.duration }

    /**
     * The source second showing [timelineOffset] seconds into the clip. Past
     * the end it is the end of the last span; with nothing to play it is [end].
     */
    fun sourceTime(timelineOffset: Double, spans: List<EffectPlaybackSpan>, end: Double): Double {
        var remaining = maxOf(0.0, timelineOffset)
        spans.forEachIndexed { index, span ->
            // A boundary belongs to the span that starts there, within rounding.
            if (remaining < span.duration - 0.000001 || index == spans.lastIndex) return minOf(span.end, span.start + remaining * span.rate)
            remaining -= span.duration
        }
        return end
    }

    /** Seconds into the clip at which the source second [time] plays. Removed footage maps to where it was cut. */
    fun timelineTime(time: Double, spans: List<EffectPlaybackSpan>): Double =
        spans.fold(0.0) { total, span -> total + maxOf(0.0, minOf(span.end, time) - span.start) / span.rate }

    /** A held frame shows one source second for its whole length. */
    fun sourceTime(hold: FrozenFrameSegment): Double = hold.sourceTime

    /** On a held frame everything up to it is at its start and everything after at its end. */
    fun timelineTime(time: Double, hold: FrozenFrameSegment): Double = if (time <= hold.sourceTime) 0.0 else hold.duration

    const val MINIMUM_PLAYBACK_RATE = 0.125
    const val MAXIMUM_PLAYBACK_RATE = 6.0
}
