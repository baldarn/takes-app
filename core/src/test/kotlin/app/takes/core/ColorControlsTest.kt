package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorControlsTest {
    // YouRec/ColorGradeControls.swift:25, 63, 70-72, 152: the ranges and steps of the sliders.
    @Test fun rangesAndStepsAreTheOriginals() {
        assertEquals(listOf(0.0, 1.0, 0.01), ColorControl.Strength.let { listOf(it.low, it.high, it.step) })
        // ColorGradeControls.swift:41: local highlight recovery.
        assertEquals(listOf(0.0, 1.0, 0.01), ColorControl.LocalRecovery.let { listOf(it.low, it.high, it.step) })
        assertEquals(listOf(-1.0, 1.0, 0.01), ColorControl.Highlights.let { listOf(it.low, it.high, it.step) })
        assertEquals(listOf(-4.0, 4.0, 0.05), ColorControl.Exposure.let { listOf(it.low, it.high, it.step) })
        assertEquals(listOf(-1.0, 1.0, 0.05), ColorControl.Warmth.let { listOf(it.low, it.high, it.step) })
        assertEquals(listOf(-1.0, 1.0, 0.05), ColorControl.Tint.let { listOf(it.low, it.high, it.step) })
    }

    // Every value a slider gives is one a saved grade may hold (YouRec/VideoTools.swift:151-157).
    @Test fun theEndsOfEverySliderAreValidGrades() {
        val low = ColorSettings(
            amount = ColorControl.Strength.stepped(-1.0), localRecovery = ColorControl.LocalRecovery.stepped(-1.0), exposure = ColorControl.Exposure.stepped(-9.0),
            highlights = ColorControl.Highlights.stepped(-2.0), warmth = ColorControl.Warmth.stepped(-2.0), tint = ColorControl.Tint.stepped(-2.0),
        )
        val high = ColorSettings(
            amount = ColorControl.Strength.stepped(2.0), localRecovery = ColorControl.LocalRecovery.stepped(2.0), exposure = ColorControl.Exposure.stepped(9.0),
            highlights = ColorControl.Highlights.stepped(2.0), warmth = ColorControl.Warmth.stepped(2.0), tint = ColorControl.Tint.stepped(2.0),
        )
        assertEquals(-4.0, low.exposure!!, 0.0)
        assertEquals(4.0, high.exposure!!, 0.0)
        assertTrue(low.valid && high.valid)
    }

    @Test fun valuesLandOnTheGridWithoutNoise() {
        assertEquals(0.15, ColorControl.Exposure.stepped(0.1499), 0.0)
        assertEquals(-3.95, ColorControl.Exposure.stepped(-3.96), 0.0)
        assertEquals(0.37, ColorControl.Highlights.stepped(0.3712), 0.0)
        assertEquals(0.0, ColorControl.Tint.stepped(0.02), 0.0)
    }

    // ColorGradeControls.swift:27, 57, 151: how each value is written beside its slider.
    @Test fun labelsAreWrittenAsTheOriginalWritesThem() {
        assertEquals("75%", ColorControl.Strength.label(0.75))
        // ColorGradeControls.swift:37.
        assertEquals("100%", ColorControl.LocalRecovery.label(1.0))
        assertEquals("37%", ColorControl.LocalRecovery.label(0.37))
        assertEquals("+30", ColorControl.Highlights.label(0.3))
        assertEquals("-1.50 EV", ColorControl.Exposure.label(-1.5))
        assertEquals("+0.25", ColorControl.Warmth.label(0.25))
        assertEquals("-0.05", ColorControl.Tint.label(-0.05))
    }
}
