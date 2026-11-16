package app.takes.core

import kotlin.math.abs
import kotlin.math.floor

/**
 * How much of the movie the timeline shows across its width, as the
 * original's viewport and span menu have it (TrackTimeline.swift:1442-1455,
 * EditorTimeline.swift:186-191, :577-582, :998-1001). A scale is in points
 * (here dp) per second of the movie.
 */
object TimelineZoom {
    /** The closest the timeline comes: 720 points for each second. */
    const val MAXIMUM = 720.0

    /** Room left at each edge when the whole project is in view. */
    const val EDGE_INSET = 48.0

    /** The spans the menu offers. */
    enum class Span { Whole, FiveMinutes, OneMinute, TenSeconds, OneSecond }

    /** The scale at which the whole of a movie of [duration] seconds fits in [width], and the furthest the timeline goes out. */
    fun minimum(width: Double, duration: Double): Double = maxOf(0.000001, (width - 2 * EDGE_INSET) / maxOf(1.0, duration))

    /** [requested] kept between the whole project and [MAXIMUM]. */
    fun clamped(requested: Double, width: Double, duration: Double): Double = maxOf(minimum(width, duration), minOf(MAXIMUM, requested))

    /** The scale of each span of the menu, for a timeline [width] wide. */
    fun scale(span: Span, width: Double, duration: Double): Double = when (span) {
        Span.Whole -> minimum(width, duration)
        Span.FiveMinutes -> width / 300
        Span.OneMinute -> width / 60
        Span.TenSeconds -> width / 10
        Span.OneSecond -> width
    }

    /** The span the timeline is at, null for a scale none of them gives. */
    fun span(scale: Double, width: Double, duration: Double): Span? =
        Span.entries.firstOrNull { abs(scale - scale(it, width, duration)) < 0.0000001 }

    /** The scale a pinch that began at [start] reaches when the fingers are [factor] times as far apart. */
    fun pinched(start: Double, factor: Double, width: Double, duration: Double): Double = clamped(start * factor, width, duration)

    /** Seconds between two marks of the ruler: the smallest of the original's steps at least 44 points apart. */
    fun rulerStep(scale: Double): Double = RULER_STEPS.firstOrNull { it * scale >= 44 } ?: 7200.0

    /** The marks of the ruler from [start] to [end] seconds, at [step]. */
    fun rulerMarks(start: Double, end: Double, step: Double): List<Double> {
        if (!(step > 0) || end < start) return emptyList()
        val first = floor(start / step).toLong()
        val last = floor(end / step + 1e-9).toLong()
        // Counted in steps, so a mark is a whole number of steps and not a sum that drifts.
        return (first..last).map { Math.round(it * step * 1000) / 1000.0 }
    }

    /** What a mark of the ruler reads: seconds under a minute, else minutes and seconds. */
    fun rulerLabel(time: Double): String =
        if (time < 60) (if (time % 1.0 == 0.0) time.toLong().toString() else time.toString())
        else "%d:%02d".format(time.toLong() / 60, time.toLong() % 60)

    private val RULER_STEPS = listOf(0.1, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0, 300.0, 600.0, 1800.0, 3600.0)
}
