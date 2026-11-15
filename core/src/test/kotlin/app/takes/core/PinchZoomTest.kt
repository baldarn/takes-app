package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln

/**
 * The pinch on the recorder's picture and the numbers of its zoom controls.
 *
 * The original covers the pinch with interface tests only, so these are the
 * port's own, from the formulas of YouRec/RecorderZoomViewport.swift:151-153,
 * 174-186, 216-222 and 302, YouRec/RecorderView.swift:942, 1049-1053, 1078
 * and 1164-1165, and YouRec/CaptureController.swift:1786.
 */
class PinchZoomTest {
    private fun zoom(span: Double, start: Double = 1.0, startSpan: Double = 100.0, minimum: Double = 0.5, maximum: Double = 10.0) =
        PinchZoom.zoom(start, startSpan, span, minimum, maximum)

    // MARK: The mapping

    @Test fun theFirstTravelOfThePinchChangesNothing() {
        // ln(102.2 / 100) is 0.0218, inside the dead zone of 0.022; the same holds pinching in.
        for (span in listOf(100.0, 101.0, 102.2, 99.0, 97.9)) assertEquals(1.0, zoom(span), 0.0)
        assertTrue(zoom(102.3) > 1.0)
        assertTrue(zoom(97.8) < 1.0)
        assertEquals(0.0, PinchZoom.eased(0.022), 0.0)
        assertEquals(0.0, PinchZoom.eased(-0.022), 0.0)
    }

    @Test fun theZoomEasesInAndThenFollowsTheFingers() {
        // Halfway through the ease, 0.022 + 0.033 / 2 = 0.0385 stops, the smooth step is one half.
        assertEquals(0.0385 * 0.5, PinchZoom.eased(0.0385), 1e-15)
        assertEquals(-0.0385 * 0.5, PinchZoom.eased(-0.0385), 1e-15)
        assertEquals(exp(0.01925), zoom(100 * exp(0.0385)), 1e-12)
        // Past 0.055 stops, a span 5.65 % larger, the zoom is the ratio of the spans.
        assertEquals(0.055, PinchZoom.eased(0.055), 1e-15)
        assertEquals(1.2, zoom(120.0), 1e-12)
        assertEquals(2.0, zoom(200.0), 1e-12)
        assertEquals(0.5, zoom(50.0), 1e-12)
        assertEquals(6.0, zoom(300.0, start = 3.0, startSpan = 150.0), 1e-12)
    }

    @Test fun theZoomNeverStepsOrTurnsBack() {
        var previous = zoom(40.0)
        var span = 40.0
        while (span < 400.0) {
            span += 0.25
            val next = zoom(span)
            assertTrue(next >= previous)
            // Easing in, the zoom catches up with the fingers at up to two and a half times their pace, never in a step.
            assertTrue(ln(next / previous) <= 3 * ln(span / (span - 0.25)))
            previous = next
        }
    }

    @Test fun theZoomStaysInsideTheLensAndIsMeasuredFromTheStart() {
        assertEquals(10.0, zoom(900.0, start = 4.0), 0.0)
        assertEquals(0.5, zoom(10.0), 0.0)
        // Past the limit, easing off a little still asks for more than the lens has: the zoom does not reverse.
        assertEquals(10.0, zoom(880.0, start = 4.0), 0.0)
        // Two fingers on one spot are a span of one point, not of none.
        assertEquals(PinchZoom.stops(1.0, 50.0), PinchZoom.stops(0.0, 50.0), 0.0)
        assertEquals(0.0, PinchZoom.stops(0.2, 0.9), 0.0)
    }

    @Test fun aPinchBeginsAfterThreePointsOfSpanOrTravel() {
        assertFalse(PinchZoom.begins(100.0, 103.0, 0.0))
        assertTrue(PinchZoom.begins(100.0, 103.1, 0.0))
        assertTrue(PinchZoom.begins(100.0, 96.9, 0.0))
        assertFalse(PinchZoom.begins(100.0, 100.0, 3.0))
        assertTrue(PinchZoom.begins(100.0, 100.0, 3.1))
    }

    // MARK: Instant and timed pinches

    @Test fun anInstantPinchAppliesEachChangeAndSavesOnRelease() {
        var pinch = PinchZoom.begin(zoom = 1.0, span = 100.0, seconds = 0.0, minimum = 0.5, maximum = 10.0)
        assertFalse(pinch.timed)
        var step = pinch.moved(101.0)
        assertNull(step.apply)
        assertNull(step.pinch.released())
        step = step.pinch.moved(150.0)
        assertEquals(1.5, step.apply!!, 1e-12)
        pinch = step.pinch
        assertEquals(1.5, pinch.scale, 1e-12)
        // The same span again changes nothing more.
        assertNull(pinch.moved(150.0).apply)
        assertEquals(1.5, pinch.released()!!, 1e-12)
        // Back where it began the camera was still moved, so the release saves the zoom.
        step = pinch.moved(100.0)
        assertEquals(1.0, step.apply!!, 0.0)
        assertFalse(step.pinch.changed)
        assertEquals(1.0, step.pinch.released()!!, 0.0)
    }

    @Test fun aTimedPinchOnlyComposesAndRunsOnRelease() {
        val pinch = PinchZoom.begin(zoom = 2.0, span = 120.0, seconds = 3.2, minimum = 1.0, maximum = 8.0)
        assertTrue(pinch.timed)
        // The time is kept on the half-second grid of the zoom transition.
        assertEquals(3.0, pinch.seconds, 0.0)
        val step = pinch.moved(240.0)
        assertNull(step.apply)
        assertEquals(4.0, step.pinch.zoom, 1e-12)
        assertEquals(2.0, step.pinch.scale, 1e-12)
        assertEquals(4.0, step.pinch.released()!!, 1e-12)
        // The move then follows the curve the presets use.
        val move = ZoomTransition(pinch.startZoom, step.pinch.released()!!, 10.0, step.pinch.seconds)
        assertEquals(2.0, move.value(10.0), 0.0)
        assertEquals(4.0, move.value(13.0), 0.0)
        // A pinch that ends where it began asks for no move.
        assertNull(step.pinch.moved(120.0).pinch.released())
        // A change of scale of half a percent or less is not a move either.
        assertNull(pinch.copy(zoom = 2.009).released())
        assertEquals(2.011, pinch.copy(zoom = 2.011).released()!!, 0.0)
    }

    // MARK: Presets and read-out

    @Test fun presetsAreTheOriginalsListInsideTheLens() {
        assertEquals(listOf(0.5, 1.0, 2.0, 3.0, 5.0, 6.0), PinchZoom.presets(0.5, 10.0))
        assertEquals(listOf(1.0, 2.0, 3.0, 5.0, 6.0), PinchZoom.presets(1.0, 8.0))
        assertEquals(listOf(1.0, 2.0, 3.0), PinchZoom.presets(0.6, 3.0))
        assertEquals(listOf(1.0), PinchZoom.presets(1.0, 1.0))
        // The original's camera counts from its widest lens: 1 to 20 shown at half is 0.5× to 10×.
        assertEquals(listOf(0.5, 1.0, 2.0, 3.0, 5.0, 6.0), PinchZoom.presets(1.0, 20.0, display = 0.5))
        assertEquals(10.0, PinchZoom.maximum(30.0), 0.0)
        assertEquals(8.0, PinchZoom.maximum(8.0), 0.0)
    }

    @Test fun aPresetIsSelectedWithinFourHundredths() {
        assertTrue(PinchZoom.isSelected(1.0, 1.03))
        assertTrue(PinchZoom.isSelected(1.0, 0.97))
        assertFalse(PinchZoom.isSelected(1.0, 1.05))
        assertFalse(PinchZoom.isSelected(2.0, 1.0))
        assertTrue(PinchZoom.isSelected(0.5, 1.02, display = 0.5))
    }

    @Test fun theReadOutAlwaysHasOneDecimal() {
        assertEquals("1.0×", PinchZoom.readout(1.0))
        assertEquals("0.5×", PinchZoom.readout(0.5))
        assertEquals("2.0×", PinchZoom.readout(2.04))
        assertEquals("10.0×", PinchZoom.readout(10.0))
        assertEquals("0.5×", PinchZoom.readout(1.0, display = 0.5))
    }

    // MARK: Zoom time by dragging, and the picture's place

    @Test fun draggingTheZoomTimeMovesHalfASecondEveryFourteenPoints() {
        assertEquals(1.5, PinchZoom.draggedDuration(1.0, -14.0), 0.0)
        assertEquals(1.0, PinchZoom.draggedDuration(1.0, -13.9), 0.0)
        assertEquals(1.5, PinchZoom.draggedDuration(1.0, -27.9), 0.0)
        assertEquals(2.0, PinchZoom.draggedDuration(1.0, -28.0), 0.0)
        assertEquals(0.5, PinchZoom.draggedDuration(1.0, 14.0), 0.0)
        assertEquals(1.0, PinchZoom.draggedDuration(1.0, 13.9), 0.0)
        assertEquals(0.0, PinchZoom.draggedDuration(1.0, 100.0), 0.0)
        assertEquals(30.0, PinchZoom.draggedDuration(29.5, -100.0), 0.0)
    }

    @Test fun thePictureFitsOrFillsTheView() {
        val bounds = Size(400.0, 800.0)
        val fit = PinchZoom.pictureRect(bounds, Size(1080.0, 1920.0), fill = false)
        assertEquals(400.0, fit.width, 1e-9)
        assertEquals(400.0 * 1920 / 1080, fit.height, 1e-9)
        assertEquals(0.0, fit.x, 1e-9)
        assertEquals((800 - 400.0 * 1920 / 1080) / 2, fit.y, 1e-9)
        val fill = PinchZoom.pictureRect(bounds, Size(1080.0, 1920.0), fill = true)
        assertEquals(450.0, fill.width, 1e-9)
        assertEquals(800.0, fill.height, 1e-9)
        assertEquals(-25.0, fill.x, 1e-9)
        assertEquals(Rect(0.0, 0.0, 400.0, 800.0), PinchZoom.pictureRect(bounds, null, fill = false))
        assertEquals(Rect(0.0, 0.0, 400.0, 800.0), PinchZoom.pictureRect(bounds, Size(0.0, 1920.0), fill = true))
    }
}
