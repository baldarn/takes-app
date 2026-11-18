package app.takes.core

import kotlin.math.abs

/**
 * Where a title, a picture or the caption series is dragged to, in shares of
 * the frame from its top left (YouRec/VideoTools.swift:250-258).
 */
object OverlayPlacement {
    /** A position this near the middle snaps to it while dragged: 1.5 % of the frame. */
    const val SNAP = 0.015

    /** [value] on one axis, snapped to the middle when it is near it, else kept inside the frame. */
    fun snapped(value: Double): Double = if (abs(value - 0.5) < SNAP) 0.5 else minOf(1.0, maxOf(0.0, value))

    /** The centre ([x], [y]) where a drag leaves it. Each axis snaps on its own. */
    fun snappedCenter(x: Double, y: Double): Pair<Double, Double> = snapped(x) to snapped(y)
}

/**
 * The paused frame a title or the caption series is first shown on: over its
 * footage and, for a moving one, where it is readable rather than arriving or
 * leaving (YouRec/EditorTools.swift:308-320, YouRec/SubtitleEditor.swift:68-79).
 */
object OverlayOpening {
    /**
     * The timeline second the Title tool opens [title] on, the title showing
     * over [range] of [clips] and [footage] being the stretches of the movie
     * it may cover. [clips] carry the title as it is being edited; its motion
     * runs on the clock [TitleMotionRanges] gives it there, else over [range].
     */
    fun title(
        clips: List<TimelineClip>, title: TextOverlay, range: ClosedFloatingPointRange<Double>,
        footage: List<ClosedFloatingPointRange<Double>>,
    ): Double {
        val timing = EditorRangeTiming(range, range)
        val clock = TitleMotionRanges(clips).range(title, range.start) ?: range
        val readable = title.motion?.readableRange(clock)
        return timing.range.start + timing.openingPosition(footage, readable)
    }

    /**
     * The timeline second the Subtitles tool opens on, working on [range] of
     * the movie: of the readable frames of each caption, the one nearest the
     * middle of the range; the start of the range when there is no caption.
     */
    fun captions(cues: List<SubtitleCue>, range: ClosedFloatingPointRange<Double>, motion: TitleMotion?): Double {
        val middle = (range.start + range.endInclusive) / 2
        var best: Double? = null
        for (cue in cues) {
            val clock = cue.start..cue.end
            val timing = EditorRangeTiming(clock, range)
            val time = timing.range.start + timing.openingPosition(listOf(clock), motion?.readableRange(clock))
            if (best == null || abs(time - middle) < abs(best - middle)) best = time
        }
        return best ?: range.start
    }
}
