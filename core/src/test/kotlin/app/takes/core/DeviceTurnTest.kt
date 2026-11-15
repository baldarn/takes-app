package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the recorder's controls follow the phone. The port's own: the original lets the system turn its interface. */
class DeviceTurnTest {
    @Test fun thePhoneIsInTheNearestQuarter() {
        assertEquals(0, DeviceTurn.quarter(0, 0))
        assertEquals(0, DeviceTurn.quarter(359, 0))
        assertEquals(90, DeviceTurn.quarter(90, 0))
        assertEquals(180, DeviceTurn.quarter(180, 0))
        assertEquals(270, DeviceTurn.quarter(270, 0))
        assertEquals(90, DeviceTurn.quarter(100, 180))
        assertEquals(0, DeviceTurn.quarter(350, 270))
        assertEquals(270, DeviceTurn.quarter(280, 0))
        // The sensor may count past a full turn.
        assertEquals(90, DeviceTurn.quarter(450, 0))
    }

    @Test fun aPhoneHeldDiagonallyKeepsItsQuarter() {
        // Past the middle, and not yet by the slack: nothing changes.
        assertEquals(0, DeviceTurn.quarter(50, 0))
        assertEquals(0, DeviceTurn.quarter(55, 0))
        assertEquals(90, DeviceTurn.quarter(56, 0))
        // On the way back the same slack holds the other one.
        assertEquals(90, DeviceTurn.quarter(40, 90))
        assertEquals(90, DeviceTurn.quarter(35, 90))
        assertEquals(0, DeviceTurn.quarter(34, 90))
        // Across the top, where the angle starts again.
        assertEquals(0, DeviceTurn.quarter(305, 0))
        assertEquals(270, DeviceTurn.quarter(304, 0))
        assertEquals(270, DeviceTurn.quarter(325, 270))
        assertEquals(0, DeviceTurn.quarter(326, 270))
    }

    @Test fun aPhoneLyingFlatKeepsItsQuarter() {
        // The sensor's "unknown".
        assertEquals(0, DeviceTurn.quarter(-1, 0))
        assertEquals(270, DeviceTurn.quarter(-1, 270))
    }

    @Test fun controlsTurnAgainstThePhone() {
        assertEquals(0, DeviceTurn.controls(0))
        // Left side up: the phone was turned clockwise, the controls turn back.
        assertEquals(270, DeviceTurn.controls(90))
        assertEquals(180, DeviceTurn.controls(180))
        assertEquals(90, DeviceTurn.controls(270))
        assertFalse(DeviceTurn.sideways(0))
        assertTrue(DeviceTurn.sideways(90))
        assertFalse(DeviceTurn.sideways(180))
        assertTrue(DeviceTurn.sideways(270))
    }

    @Test fun controlsTurnTheShortWayRound() {
        assertEquals(90f, DeviceTurn.shortest(0f, 90), 0f)
        assertEquals(-90f, DeviceTurn.shortest(0f, 270), 0f)
        assertEquals(360f, DeviceTurn.shortest(270f, 0), 0f)
        assertEquals(-180f, DeviceTurn.shortest(-90f, 180), 0f)
        assertEquals(180f, DeviceTurn.shortest(0f, 180), 0f)
        assertEquals(0f, DeviceTurn.shortest(0f, 0), 0f)
        // From an angle that has gone round more than once.
        assertEquals(720f, DeviceTurn.shortest(630f, 0), 0f)
        assertEquals(-360f, DeviceTurn.shortest(-270f, 0), 0f)
    }
}
