package app.takes.core

import kotlin.math.abs

/**
 * The two edges of a range being trimmed, on the editor's clock: a title, a
 * picture, an effect's range or the marked range of a selection, inside
 * [bounds]. A source boundary can be between frames. The original's
 * `EditorRangeTiming` (YouRec/VideoTools.swift:280-347), which replaced its
 * `OverlayTiming`: both thumbs now share one ruler over [bounds], so moving
 * one edge never moves the other's thumb.
 */
class EditorRangeTiming private constructor(
    val bounds: ClosedFloatingPointRange<Double>,
    val range: ClosedFloatingPointRange<Double>,
) {
    enum class Edge { Start, End }

    val duration: Double get() = range.endInclusive - range.start
    val available: Double get() = bounds.endInclusive - range.start

    /**
     * Seconds into the range of the paused frame to show when the range is
     * opened: from the footage the overlay will actually cover, the middle, or
     * the middle of [readable], the stretch where a moving title is fully
     * shown, when the middle is outside it. A title across a cut can have its
     * readable hold on the other clip (YouRec/VideoTools.swift:288-304).
     */
    fun openingPosition(footage: List<ClosedFloatingPointRange<Double>>, readable: ClosedFloatingPointRange<Double>?): Double {
        val middle = range.start + duration / 2
        val preferred = readable ?: range
        val target = if (middle in preferred) middle else (preferred.start + preferred.endInclusive) / 2
        var best: Double? = null
        for (clip in footage) {
            val first = maxOf(range.start, clip.start)
            val end = minOf(range.endInclusive, clip.endInclusive)
            if (!(first < end)) continue
            val last = maxOf(first, end - PauseDetector.FRAME)
            val candidate = minOf(last, maxOf(first, target))
            if (best == null || abs(candidate - target) < abs(best - target)) best = candidate
        }
        return (best ?: range.start) - range.start
    }

    /** Where [edge] may go: a frame from the other edge where there is room (YouRec/VideoTools.swift:312-317). */
    fun limits(edge: Edge): ClosedFloatingPointRange<Double> = when (edge) {
        Edge.Start -> bounds.start..maxOf(bounds.start, range.endInclusive - PauseDetector.FRAME)
        Edge.End -> minOf(bounds.endInclusive, range.start + PauseDetector.FRAME)..bounds.endInclusive
    }

    fun boundary(edge: Edge): Double = if (edge == Edge.Start) range.start else range.endInclusive

    /** The frame to show while [edge] moves: the first frame of the range, or its last included one (YouRec/VideoTools.swift:321-323). */
    fun previewTime(edge: Edge): Double =
        if (edge == Edge.Start) range.start else maxOf(range.start, range.endInclusive - PauseDetector.FRAME)

    /** Where [edge]'s thumb is on the ruler over [bounds], 0…1 (YouRec/VideoTools.swift:325-328). */
    fun fraction(edge: Edge): Double {
        val span = bounds.endInclusive - bounds.start
        return if (span > 0) (boundary(edge) - bounds.start) / span else 0.0
    }

    /**
     * The time for a thumb at [fraction] of the ruler: whole frames from the
     * start of [bounds], kept inside [edge]'s [limits], whose ends are
     * reachable exactly (YouRec/VideoTools.swift:330-338).
     */
    fun time(fraction: Double, edge: Edge): Double {
        val limits = limits(edge)
        if (fraction <= 0) return limits.start
        if (fraction >= 1) return limits.endInclusive
        val offset = (bounds.endInclusive - bounds.start) * fraction
        val frames = roundedAwayFromZero(offset / PauseDetector.FRAME)
        return minOf(limits.endInclusive, maxOf(limits.start, bounds.start + frames * PauseDetector.FRAME))
    }

    /** The timing with [edge] moved to [value], kept inside its [limits] (YouRec/VideoTools.swift:340-345). */
    fun setting(edge: Edge, value: Double): EditorRangeTiming {
        if (!value.isFinite()) return this
        val limits = limits(edge)
        val time = minOf(limits.endInclusive, maxOf(limits.start, value))
        return EditorRangeTiming(bounds, if (edge == Edge.Start) time..range.endInclusive else range.start..time)
    }

    override fun equals(other: Any?): Boolean = other is EditorRangeTiming && other.bounds == bounds && other.range == range
    override fun hashCode(): Int = bounds.hashCode() * 31 + range.hashCode()

    companion object {
        /** [range] is brought inside [bounds] (YouRec/VideoTools.swift:306-310). */
        operator fun invoke(range: ClosedFloatingPointRange<Double>, bounds: ClosedFloatingPointRange<Double>): EditorRangeTiming {
            val start = minOf(bounds.endInclusive, maxOf(bounds.start, range.start))
            return EditorRangeTiming(bounds, start..minOf(bounds.endInclusive, maxOf(start, range.endInclusive)))
        }
    }
}
