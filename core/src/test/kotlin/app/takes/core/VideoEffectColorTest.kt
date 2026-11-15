package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The colour conversions of the video effects against the values the
 * original's formulas give (YouRec/VideoEffects/VideoEffectMath.swift:56-80).
 * The pinned numbers were computed from those formulas outside this code.
 */
class VideoEffectColorTest {
    private fun assertVector(expected: EffectVector, actual: EffectVector, tolerance: Double) {
        for (i in 0..2) assertEquals("component $i of $actual", expected[i], actual[i], tolerance)
    }

    // VideoEffectMath.swift:59-60.
    @Test fun theSRGBCurveIsPinned() {
        assertEquals(0.0, EffectColorMath.linear(0.0), 0.0)
        assertEquals(1.0, EffectColorMath.linear(1.0), 1e-15)
        assertEquals(0.21404114048223255, EffectColorMath.linear(0.5), 1e-15)
        assertEquals(0.0031308049535603713, EffectColorMath.linear(0.04045), 1e-18)
        assertEquals(0.0, EffectColorMath.encoded(0.0), 0.0)
        assertEquals(1.0, EffectColorMath.encoded(1.0), 1e-15)
        assertEquals(0.7353569830524495, EffectColorMath.encoded(0.5), 1e-15)
        assertEquals(0.46135612950044164, EffectColorMath.encoded(0.18), 1e-15)
        assertEquals(0.040449936, EffectColorMath.encoded(0.0031308), 1e-15)
        // Below the knee both are a straight line; a negative value stays on it.
        assertEquals(-0.1292, EffectColorMath.encoded(-0.01), 1e-15)
        assertEquals(-0.01 / 12.92, EffectColorMath.linear(-0.01), 1e-18)
    }

    @Test fun sRGBAndLinearReturnTheStartingValueWithinOneBillionth() {
        for (i in 0..1000) {
            val x = i / 1000.0
            assertEquals(x, EffectColorMath.encoded(EffectColorMath.linear(x)), 1e-9)
            assertEquals(x, EffectColorMath.linear(EffectColorMath.encoded(x)), 1e-9)
        }
    }

    // VideoEffectMath.swift:63-68. Black, white and the primaries, linear RGB to Oklab.
    @Test fun oklabOfBlackWhiteAndThePrimariesIsPinned() {
        assertVector(EffectVector(0.0, 0.0, 0.0), EffectColorMath.lab(EffectVector(0.0, 0.0, 0.0)), 0.0)
        assertVector(EffectVector(0.9999999934735462, 8.095285553011422e-11, 3.727390762708893e-08), EffectColorMath.lab(EffectVector(1.0, 1.0, 1.0)), 1e-14)
        assertVector(EffectVector(0.6279553606145515, 0.22486306106597398, 0.12584629853073503), EffectColorMath.lab(EffectVector(1.0, 0.0, 0.0)), 1e-14)
        assertVector(EffectVector(0.8664396115356692, -0.2338875741879073, 0.17949847989672973), EffectColorMath.lab(EffectVector(0.0, 1.0, 0.0)), 1e-14)
        assertVector(EffectVector(0.4520137183853428, -0.03245698416876386, -0.31152814767837517), EffectColorMath.lab(EffectVector(0.0, 0.0, 1.0)), 1e-14)
        // The primaries are the same in sRGB and in linear light.
        assertVector(EffectColorMath.lab(EffectVector(1.0, 0.0, 0.0)), EffectColorMath.lab(EffectRGB(1.0, 0.0, 0.0)), 0.0)
    }

    // VideoEffectMath.swift:69-74. Oklab back to linear RGB, with the original's rounded inverse matrices.
    @Test fun linearFromOklabOfBlackWhiteAndThePrimariesIsPinned() {
        fun back(r: Double, g: Double, b: Double) = EffectColorMath.linearFromLab(EffectColorMath.lab(EffectVector(r, g, b)))
        assertVector(EffectVector(0.0, 0.0, 0.0), back(0.0, 0.0, 0.0), 0.0)
        assertVector(EffectVector(1.000000069533121, 0.9999999802873053, 0.9999997387154016), back(1.0, 1.0, 1.0), 1e-13)
        assertVector(EffectVector(1.0000000004038363, -4.965820674801558e-09, -3.0466503736503725e-08), back(1.0, 0.0, 0.0), 1e-13)
        assertVector(EffectVector(7.272492522081286e-08, 0.9999999708281588, -1.0005049949635136e-07), back(0.0, 1.0, 0.0), 1e-13)
        assertVector(EffectVector(1.7097265547771912e-09, 7.498798193505962e-09, 0.9999999204714656), back(0.0, 0.0, 1.0), 1e-13)
    }

    /**
     * Tests/VideoEffectModelTests.swift:195-197, "Oklab round trip", at the
     * original's tolerance. The original's two matrices are each written to
     * ten digits and are not exact inverses of one another: the worst error
     * over the cube is 2.6e-7, so one billionth is not reachable with its
     * constants. This pins what they do reach.
     */
    @Test fun oklabReturnsTheStartingColourWithinTheOriginalsTolerance() {
        for (rgb in listOf(EffectVector(0.1, 0.4, 0.8), EffectVector(1.0, 0.0, 0.0), EffectVector(0.25, 0.25, 0.25))) {
            assertTrue(rgb.distance(EffectColorMath.linearFromLab(EffectColorMath.lab(rgb))) < 0.000001)
        }
        var worst = 0.0
        for (r in 0..16) for (g in 0..16) for (b in 0..16) {
            val c = EffectVector(r / 16.0, g / 16.0, b / 16.0)
            val back = EffectColorMath.linearFromLab(EffectColorMath.lab(c))
            for (i in 0..2) worst = maxOf(worst, abs(back[i] - c[i]))
        }
        assertTrue("worst $worst", worst < 3e-7)
        assertTrue("worst $worst", worst > 1e-9)
    }

    @Test fun sRGBThroughOklabAndBack() {
        for (c in listOf(EffectRGB(0.82, 0.65, 0.52), EffectRGB(0.60, 0.45, 0.35), EffectRGB(0.36, 0.25, 0.20), EffectRGB(0.5, 0.5, 0.5))) {
            val back = EffectColorMath.encodedFromLab(EffectColorMath.lab(c))
            assertVector(EffectVector(c.red, c.green, c.blue), back, 1e-6)
            val held = EffectColorMath.rgb(EffectColorMath.linearFromLab(EffectColorMath.lab(c)))
            assertTrue(held.valid)
            assertEquals(c.red, held.red, 1e-6)
        }
    }

    // VideoEffectMath.swift:62, :75-79.
    @Test fun luminanceHeldColoursAndThePreparedSample() {
        assertEquals(0.2126, EffectColorMath.luminance(EffectVector(1.0, 0.0, 0.0)), 0.0)
        assertEquals(1.0, EffectColorMath.luminance(EffectVector(1.0, 1.0, 1.0)), 1e-15)
        assertEquals(EffectRGB(1.0, 0.0, 0.5 * 12.92 * 0.001), EffectColorMath.rgb(EffectVector(2.0, -1.0, 0.0005)))
        // y = 0.25: s = 0.25² × 2.5 = 0.15625, target = 0.25 + (0.15625 − 0.25) × 0.2 = 0.23125.
        assertVector(EffectVector(0.23125), EffectColorMath.prepared(EffectVector(0.25)), 1e-15)
        // Pure red: y = 0.2126, so the shift is (y²(3 − 2y) − y) × 0.2 = −0.0192444466…; green and blue are held at zero.
        assertVector(EffectVector(0.9807555534496, 0.0, 0.0), EffectColorMath.prepared(EffectVector(1.0, 0.0, 0.0)), 1e-12)
    }

    // VideoEffectModel.swift:55-63.
    @Test fun aColourReadsFromHexAndKnowsWhenItIsOne() {
        assertEquals(EffectRGB(192 / 255.0, 57 / 255.0, 43 / 255.0), EffectRGB.hex("#C0392B"))
        assertEquals(EffectRGB(0.0, 1.0, 0.0), EffectRGB.hex("00ff00"))
        assertEquals(EffectRGB(0.0, 0.0, 0.0), EffectRGB.hex("not a colour"))
        assertTrue(EffectRGB(0.0, 0.5, 1.0).valid)
        assertFalse(EffectRGB(0.0, 1.01, 1.0).valid)
        assertFalse(EffectRGB(Double.NaN, 0.0, 0.0).valid)
    }
}
