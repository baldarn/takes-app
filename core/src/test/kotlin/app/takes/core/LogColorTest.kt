package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Log colour and the Highlights value. Tests marked TRANSLATED carry over the
 * iPhone app's videoToolsTests (Tests/VideoToolsTests.swift:79-117) against
 * YouRec/LogColor.swift:61-191 and YouRec/VideoToolCompositor.swift:500-511.
 * The original measured rendered pixels in linear light; these run the same
 * formulas on one colour.
 */
class LogColorTest {
    private fun grey(v: Double) = doubleArrayOf(v, v, v)

    /** The grade of a log recording up to the look: balance, highlights, viewing. */
    private fun viewedLog(settings: ColorSettings, scene: DoubleArray): DoubleArray {
        val exposed = DoubleArray(3) { scene[it] * Math.pow(2.0, settings.exposure ?: 0.0) }
        return LogColor.display(LogColor.highlights(exposed, settings.highlights ?: 0.0))
    }

    /** TRANSLATED VideoToolsTests.swift:79-81. */
    @Test fun theAppleLogCurveRoundTrips() {
        for (value in listOf(-0.05, 0.0, 0.005, 0.01, 0.18, 1.0, 8.0)) {
            assertEquals(value, LogColor.decodeValue(LogColor.encodeValue(value)), 0.00001)
        }
    }

    /** TRANSLATED VideoToolsTests.swift:82-87. */
    @Test fun theViewingTransformKeepsMiddleGreyAndSeparatesHighlights() {
        val bright = LogColor.display(grey(4.0))[0]
        val brighter = LogColor.display(grey(8.0))[0]
        assertTrue(brighter - bright > 0.01)
        assertEquals(0.18, LogColor.display(grey(0.18))[0], 0.0001)
    }

    /** TRANSLATED VideoToolsTests.swift:88-98. */
    @Test fun highlightsAreMonotonicAndLeaveShadowsAndMidtones() {
        for (amount in listOf(-1.0, -0.5, 0.0, 0.5, 1.0)) {
            var previous = -1.0
            for (index in 0..256) {
                val adjusted = LogColor.highlights(grey(index / 16.0), amount)[0]
                assertTrue(adjusted.isFinite() && adjusted > previous)
                previous = adjusted
            }
            for (value in listOf(0.0, 0.02, 0.18, 0.3, 0.35)) {
                assertEquals(value, LogColor.highlights(grey(value), amount)[0], 0.0001)
            }
        }
    }

    /** TRANSLATED VideoToolsTests.swift:99-107. */
    @Test fun recoveryRestoresHeadroomWithoutFlatteningWhite() {
        val bright = LogColor.display(grey(4.0))[0]
        val recovered = viewedLog(ColorSettings(highlights = -1.0), grey(4.0))[0]
        assertTrue("recovered $recovered bright $bright", recovered < bright - 0.1 && recovered > 0.6)
        assertEquals(0.18, viewedLog(ColorSettings(highlights = -1.0), grey(0.18))[0], 0.0001)
        assertFalse(ColorSettings(highlights = -0.5).isIdentity)
        assertTrue(ColorSettings(highlights = -0.5).valid)
        assertFalse(ColorSettings(highlights = Double.NaN).valid)
        assertFalse(ColorSettings(highlights = 2.0).valid)
    }

    /** TRANSLATED VideoToolsTests.swift:108-116. */
    @Test fun logCurveAndGamutRoundTripAndHighlightsAreDisplayMapped() {
        val scene = doubleArrayOf(0.18, 0.5, 2.0)
        for (profile in listOf(LUTInput.AppleLog, LUTInput.AppleLog2)) {
            val decoded = LogColor.decode(LogColor.encode(scene, profile), profile)
            for (i in 0 until 3) assertEquals(scene[i], decoded[i], 0.006)
            assertTrue(LogColor.display(scene)[2] < 2)
        }
    }

    @Test fun thePrimariesMatricesKeepWhite() {
        for (profile in LUTInput.entries) {
            val m = LogColor.matrix(profile)
            for (row in 0 until 3) assertEquals(1.0, m[row * 3] + m[row * 3 + 1] + m[row * 3 + 2], 1e-9)
        }
        val m = LogColor.matrix(LUTInput.AppleLog)
        // Rec.2020 to Rec.709, the standard matrix's first row.
        assertEquals(1.6605, m[0], 0.0005)
        assertEquals(-0.5876, m[1], 0.0005)
        assertEquals(-0.0728, m[2], 0.0005)
    }

    @Test fun aLogLutGetsTheSceneInItsProfile() {
        val scene = doubleArrayOf(0.18, 0.18, 0.18)
        val log = LogColor.lutInput(scene, scene, LUTInput.AppleLog)
        assertEquals(LogColor.encodeValue(0.18), log[0], 1e-9)
        assertEquals(LogColor.toDisplay(0.18), LogColor.lutInput(scene, scene, LUTInput.Rec709)[0], 1e-12)
    }

    /**
     * Colours pinned from the formula of LogColor.swift:110-119: a grey of
     * linear 0.5 (luminance 0.5, 0.15 above the knee) through the grade.
     * Recovery maps it to 0.35 + ln(1 + 6a·0.15)/(6a); a lift to
     * 0.35 + 0.15·(1 + 2a·0.15/0.5).
     */
    @Test fun highlightsPinnedAtFourStrengths() {
        val input = LogColor.toDisplay(0.5)
        for ((amount, expected) in listOf(-1.0 to 0.456976, -0.5 to 0.473855, 0.5 to 0.545, 1.0 to 0.59)) {
            val out = ColorGrade.apply(ColorSettings(highlights = amount), null, input, input, input)
            assertEquals("amount $amount", expected, LogColor.toLinear(out[0]), 0.000001)
        }
    }

    /** The grade of issue 76: Highlights now changes the shared table, at its place before the look. */
    @Test fun theGradeAppliesHighlights() {
        val none = ColorGrade.table(ColorSettings())
        assertTrue(none contentEquals ColorGrade.table(ColorSettings(highlights = 0.0)))
        val white = ColorGrade.apply(ColorSettings(highlights = -1.0), null, 1.0, 1.0, 1.0)
        assertTrue(white[0] < 0.95)
        val middle = LogColor.toDisplay(0.18)
        assertEquals(middle, ColorGrade.apply(ColorSettings(highlights = -1.0), null, middle, middle, middle)[0], 1e-12)
        val lifted = ColorGrade.apply(ColorSettings(highlights = 1.0), null, 0.8, 0.8, 0.8)
        assertTrue(lifted[0] > 0.8)
        val looked = ColorGrade.apply(ColorSettings(look = ColorLook.Warm, amount = 0.0, highlights = -1.0), null, 1.0, 1.0, 1.0)
        assertEquals(white[0], looked[0], 1e-12)
        assertTrue(abs(white[0] - white[2]) < 1e-12)
    }
}
