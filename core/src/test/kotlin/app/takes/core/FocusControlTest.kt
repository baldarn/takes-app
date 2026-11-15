package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The focus controls of the recorder. The original covers them with interface
 * tests only; these are the port's own, from the formulas of
 * YouRec/RecorderView.swift:959, 972, 979 and 995-999 and
 * YouRec/CaptureController.swift:2441 and 2705.
 */
class FocusControlTest {
    @Test fun theButtonNamesTheStateOfTheLens() {
        assertEquals(FocusControl.Mode.Fixed, FocusControl.mode(autofocus = false, adjustable = false, locked = false))
        assertEquals(FocusControl.Mode.Fixed, FocusControl.mode(autofocus = false, adjustable = false, locked = true))
        assertEquals(FocusControl.Mode.Auto, FocusControl.mode(autofocus = true, adjustable = true, locked = false))
        assertEquals(FocusControl.Mode.Manual, FocusControl.mode(autofocus = true, adjustable = true, locked = true))
        assertEquals(FocusControl.Mode.Locked, FocusControl.mode(autofocus = true, adjustable = false, locked = true))
        // A lens that is only set by hand is never fixed.
        assertEquals(FocusControl.Mode.Manual, FocusControl.mode(autofocus = false, adjustable = true, locked = true))
    }

    @Test fun aTapChangesModeOnlyWhereTheLensCanBeLockedAndFocusesByItself() {
        assertTrue(FocusControl.canToggle(lockSupported = true, autofocus = true))
        assertFalse(FocusControl.canToggle(lockSupported = false, autofocus = true))
        assertFalse(FocusControl.canToggle(lockSupported = true, autofocus = false))
    }

    @Test fun aShortSwipeIsFineAndALongOneFaster() {
        assertEquals(0.0, FocusControl.swipeAmount(0.0), 0.0)
        assertEquals(20.0 / 320, FocusControl.swipeAmount(20.0), 1e-12)
        assertEquals(0.125, FocusControl.swipeAmount(40.0), 1e-12)
        // 40 fine points, then 80 at twice the speed.
        assertEquals(0.125 + 0.5, FocusControl.swipeAmount(120.0), 1e-12)
        assertEquals(FocusControl.swipeAmount(120.0), FocusControl.swipeAmount(-120.0), 0.0)
    }

    @Test fun upFocusesFartherAndDownNearer() {
        assertEquals(0.5 + 0.125, FocusControl.swiped(0.5, -40.0), 1e-12)
        assertEquals(0.5 - 0.125, FocusControl.swiped(0.5, 40.0), 1e-12)
        assertEquals(0.5, FocusControl.swiped(0.5, 0.0), 0.0)
    }

    @Test fun theLensStopsAtItsEnds() {
        assertEquals(1.0, FocusControl.swiped(0.9, -400.0), 0.0)
        assertEquals(0.0, FocusControl.swiped(0.1, 400.0), 0.0)
        assertEquals(1.0, FocusControl.bounded(3.0), 0.0)
        assertEquals(0.0, FocusControl.bounded(-1.0), 0.0)
        assertEquals(FocusControl.DEFAULT, FocusControl.bounded(Double.NaN), 0.0)
    }

    @Test fun thePositionIsGivenAsAPercentage() {
        assertEquals(0, FocusControl.percent(0.0))
        assertEquals(42, FocusControl.percent(0.424))
        assertEquals(43, FocusControl.percent(0.426))
        assertEquals(100, FocusControl.percent(1.2))
    }

    @Test fun thePositionRunsFromTheNearestFocusToInfinity() {
        // A lens that focuses down to 10 cm: 10 dioptres at 0, infinity at 1.
        assertEquals(10.0, FocusControl.dioptres(0.0, 10.0), 0.0)
        assertEquals(0.0, FocusControl.dioptres(1.0, 10.0), 0.0)
        assertEquals(2.5, FocusControl.dioptres(0.75, 10.0), 1e-12)
        assertEquals(0.75, FocusControl.position(2.5, 10.0), 1e-12)
        for (position in listOf(0.0, 0.1, 0.5, 0.975, 1.0)) {
            assertEquals(position, FocusControl.position(FocusControl.dioptres(position, 7.3), 7.3), 1e-12)
        }
    }

    @Test fun aReadingOutsideTheScaleIsKeptInside() {
        assertEquals(0.0, FocusControl.position(12.0, 10.0), 0.0)
        assertEquals(1.0, FocusControl.position(-0.1, 10.0), 0.0)
        // A lens that reports no nearest focus has no scale to read.
        assertEquals(FocusControl.DEFAULT, FocusControl.position(1.0, 0.0), 0.0)
        assertEquals(0.0, FocusControl.dioptres(0.3, 0.0), 0.0)
    }

    @Test fun theBoxStaysUntilTheLensSettlesWithinItsLimits() {
        assertFalse(FocusControl.boxEnds(0.5, settled = true))
        assertTrue(FocusControl.boxEnds(0.85, settled = true))
        assertFalse(FocusControl.boxEnds(3.9, settled = false))
        assertTrue(FocusControl.boxEnds(4.0, settled = false))
    }
}
