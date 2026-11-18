package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests/TitleDesignTests.swift:402-453 (overlayTimingTests), against YouRec/VideoTools.swift:280-347. */
class EditorRangeTimingTest {
    private val frame = PauseDetector.FRAME
    private val start = EditorRangeTiming.Edge.Start
    private val end = EditorRangeTiming.Edge.End

    @Test fun openingPositionPrefersTheReadableMiddleOfTheCoveredFootage() {
        val timing = EditorRangeTiming(3.125..6.125, 1.25..70.38)
        val fade = TitleMotion(entrance = TitleMove.Fade, inSeconds = 5.0)
        val readable = fade.readableRange(timing.range)
        val opening = timing.openingPosition(listOf(1.25..70.38), readable)
        // Long entrance must open fully readable.
        assertTrue(timing.range.start + opening in readable && fade.pose(opening, timing.duration).opacity == 1.0)
        // Static overlay opens halfway through its range.
        assertEquals(1.5, timing.openingPosition(listOf(1.25..70.38), null), 0.0)
        // Previewing only the entrance stays on its most visible in-range frame.
        val split = EditorRangeTiming(3.125..4.125, timing.range)
        assertEquals(1 - frame, split.openingPosition(listOf(split.range), readable), 0.000001)
        // The poster should not land in a gap.
        val sparse = EditorRangeTiming(0.0..10.0, 0.0..10.0)
        assertEquals(7.0, sparse.openingPosition(listOf(0.0..1.0, 7.0..10.0), null), 0.0)
    }

    @Test fun bothThumbsShareOneFixedRuler() {
        var trim = EditorRangeTiming(4.0..12.0, 2.0..22.0)
        val fixedEnd = trim.fraction(end)
        trim = trim.setting(start, trim.time(0.25, start))
        // Moving Start must leave End on the same tick of a fixed ruler.
        assertEquals(7.0..12.0, trim.range)
        assertEquals(fixedEnd, trim.fraction(end), 0.0)
        val fixedStart = trim.fraction(start)
        trim = trim.setting(end, trim.time(0.75, end))
        // Moving End must leave Start at its absolute time and thumb position.
        assertEquals(7.0..17.0, trim.range)
        assertEquals(fixedStart, trim.fraction(start), 0.0)
        // Start shows its first frame and End its last included frame.
        assertEquals(7.0, trim.previewTime(start), 0.0)
        assertEquals(17 - frame, trim.previewTime(end), 0.00001)
    }

    @Test fun durationPresetsNeverMoveStartOrRoundTheLength() {
        var timing = EditorRangeTiming(3.125..6.125, 1.25..70.38)
        for (seconds in EditorDurationPresets.wholeSeconds) {
            assertTrue(EditorDurationPresets.fits(seconds, timing.available))
            timing = timing.setting(end, timing.range.start + seconds)
            assertEquals(3.125..(3.125 + seconds), timing.range)
        }
        timing = timing.setting(start, 62.0)
        // Overlong presets must not change Start.
        assertTrue(!EditorDurationPresets.fits(9.0, timing.available) && EditorDurationPresets.fits(8.0, timing.available))
        timing = timing.setting(end, timing.time(1.0, end))
        // A fractional final boundary must be reachable.
        assertEquals(timing.bounds.endInclusive, timing.range.endInclusive, 0.0)
        timing = timing.setting(end, timing.time(0.0, end))
        // Dragging End past Start must preserve a frame.
        assertEquals(frame, timing.duration, 0.000001)
    }

    @Test fun tinyTrimRangeStaysInsideItsBounds() {
        for (length in listOf(0.0, frame / 2, frame, frame * 1.4)) {
            val bounds = 8.125..(8.125 + length)
            var short = EditorRangeTiming(bounds, bounds)
            for (edge in EditorRangeTiming.Edge.entries) for (fraction in listOf(0.0, 0.1, 0.95, 1.0)) {
                short = short.setting(edge, short.time(fraction, edge))
                assertTrue(short.range.start in bounds && short.range.endInclusive in bounds && short.duration + 0.000001 >= minOf(length, frame))
            }
        }
    }
}
