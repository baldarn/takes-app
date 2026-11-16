package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineZoomTest {
    private val width = 400.0

    @Test fun theWholeProjectFitsBetweenTheEdges() {
        assertEquals((400 - 96) / 120.0, TimelineZoom.minimum(width, 120.0), 1e-9)
        // A movie under a second is measured as one second long.
        assertEquals(304.0, TimelineZoom.minimum(width, 0.4), 1e-9)
    }

    @Test fun aScaleStaysBetweenTheWholeProjectAndTheMaximum() {
        assertEquals(TimelineZoom.minimum(width, 600.0), TimelineZoom.clamped(0.01, width, 600.0), 1e-9)
        assertEquals(720.0, TimelineZoom.clamped(5000.0, width, 600.0), 1e-9)
        assertEquals(50.0, TimelineZoom.clamped(50.0, width, 600.0), 1e-9)
    }

    @Test fun theMenuSpansShowThatMuchAcrossTheWidth() {
        assertEquals(400.0 / 300, TimelineZoom.scale(TimelineZoom.Span.FiveMinutes, width, 900.0), 1e-9)
        assertEquals(400.0 / 60, TimelineZoom.scale(TimelineZoom.Span.OneMinute, width, 900.0), 1e-9)
        assertEquals(40.0, TimelineZoom.scale(TimelineZoom.Span.TenSeconds, width, 900.0), 1e-9)
        assertEquals(400.0, TimelineZoom.scale(TimelineZoom.Span.OneSecond, width, 900.0), 1e-9)
        assertEquals(TimelineZoom.Span.TenSeconds, TimelineZoom.span(40.0, width, 900.0))
        assertEquals(TimelineZoom.Span.Whole, TimelineZoom.span(TimelineZoom.minimum(width, 900.0), width, 900.0))
        assertNull(TimelineZoom.span(41.0, width, 900.0))
    }

    @Test fun aPinchScalesFromWhereItBegan() {
        assertEquals(100.0, TimelineZoom.pinched(50.0, 2.0, width, 60.0), 1e-9)
        assertEquals(720.0, TimelineZoom.pinched(500.0, 3.0, width, 60.0), 1e-9)
        assertEquals(TimelineZoom.minimum(width, 60.0), TimelineZoom.pinched(10.0, 0.1, width, 60.0), 1e-9)
    }

    @Test fun rulerMarksAreAtLeastFortyFourPointsApart() {
        assertEquals(0.1, TimelineZoom.rulerStep(720.0), 0.0)
        assertEquals(0.5, TimelineZoom.rulerStep(100.0), 0.0)
        assertEquals(1.0, TimelineZoom.rulerStep(44.0), 0.0)
        assertEquals(60.0, TimelineZoom.rulerStep(1.0), 0.0)
        assertEquals(7200.0, TimelineZoom.rulerStep(0.001), 0.0)
        assertEquals(listOf(0.0, 0.1, 0.2, 0.3), TimelineZoom.rulerMarks(0.05, 0.3, 0.1))
        assertEquals(listOf(10.0, 15.0, 20.0), TimelineZoom.rulerMarks(12.0, 24.0, 5.0))
    }

    @Test fun rulerLabelsReadAsTheOriginals() {
        assertEquals("0", TimelineZoom.rulerLabel(0.0))
        assertEquals("2.5", TimelineZoom.rulerLabel(2.5))
        assertEquals("30", TimelineZoom.rulerLabel(30.0))
        assertEquals("1:00", TimelineZoom.rulerLabel(60.0))
        assertEquals("12:05", TimelineZoom.rulerLabel(725.0))
    }
}
