package app.takes.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The local highlight recovery of log footage, against the arithmetic of the original's filter
 * (YouRec/LogColor.swift:129-180) and its description (docs/log-and-luts.md:61-77): radius 60 at a 1080-pixel short
 * edge, epsilon 0.12, zero an exact bypass, shadows untouched, bright areas compressed with their texture kept.
 */
class LocalRecoveryTest {
    /** YouRec/LogColor.swift:170: the same neighbourhood at proxy and export sizes. */
    @Test fun theRadiusScalesWithTheShortEdge() {
        assertEquals(60, LocalRecovery.radius(1920, 1080))
        assertEquals(60, LocalRecovery.radius(1080, 1920))
        assertEquals(120, LocalRecovery.radius(3840, 2160))
        assertEquals(15, LocalRecovery.radius(480, 270))
        assertEquals(10, LocalRecovery.radius(320, 180))
        assertEquals(1, LocalRecovery.radius(8, 8))
    }

    /** The port's blocks: none up to a radius of 15, so a proxy's box is exact, then an eighth of the radius. */
    @Test fun blocksStartPastARadiusOf15() {
        for (radius in 1..15) assertEquals(1, LocalRecovery.block(radius))
        assertEquals(2, LocalRecovery.block(16))
        assertEquals(7, LocalRecovery.block(60))
        assertEquals(15, LocalRecovery.block(120))
    }

    /** YouRec/LogColor.swift:171-175: a box of 2r + 1 over the frame clamped to its extent. */
    @Test fun theBoxMeanExtendsTheEdges() {
        val width = 7
        val height = 5
        val plane = DoubleArray(width * height) { (it * 37 % 11).toDouble() }
        for (radius in listOf(1, 2, 4)) {
            val fast = LocalRecovery.boxMean(plane, width, height, radius)
            for (y in 0 until height) for (x in 0 until width) {
                var sum = 0.0
                for (dy in -radius..radius) for (dx in -radius..radius) {
                    sum += plane[(y + dy).coerceIn(0, height - 1) * width + (x + dx).coerceIn(0, width - 1)]
                }
                assertEquals(sum / ((2 * radius + 1) * (2 * radius + 1)), fast[y * width + x], 1e-9)
            }
        }
    }

    /** YouRec/LogColor.swift:141-146. */
    @Test fun theCoefficientsFollowTheVariance() {
        assertArrayEquals(doubleArrayOf(0.0, -2.0), LocalRecovery.coefficients(-2.0, 4.0), 1e-12)
        // Variance 0.12 is half way.
        val half = LocalRecovery.coefficients(1.0, 1.12)
        assertEquals(0.5, half[0], 1e-12)
        assertEquals(0.5, half[1], 1e-12)
    }

    /** YouRec/LogColor.swift:168: zero, a negative or a non-finite amount leaves the picture as it is. */
    @Test fun zeroIsAnExactBypass() {
        val scene = DoubleArray(4 * 4 * 3) { (it % 7) * 0.9 }
        for (amount in listOf(0.0, -0.5, Double.NaN)) assertArrayEquals(scene, LocalRecovery.apply(scene, 4, 4, amount), 0.0)
        assertEquals(0.0, LocalRecovery.strength(ColorSettings()), 0.0)
        assertEquals(1.0, LocalRecovery.strength(ColorSettings(localRecovery = 1.0)), 0.0)
    }

    /** YouRec/LogColor.swift:149-165: an even area below the knee is unchanged at any strength. */
    @Test fun shadowsAndMidtonesAreUntouched() {
        for (y in listOf(0.0, 0.01, 0.18, 0.3, 0.35)) {
            val l = LocalRecovery.logLuminance(y)
            val c = LocalRecovery.coefficients(l, l * l)
            assertEquals(1.0, LocalRecovery.gain(y, c[0], c[1], 1.0), 1e-9)
        }
    }

    /** An even bright area is brought down under the viewing shoulder, more with more strength, never below the knee. */
    @Test fun brightAreasAreCompressed() {
        for (y in listOf(0.8, 2.0, 4.0, 16.0)) {
            val l = LocalRecovery.logLuminance(y)
            val c = LocalRecovery.coefficients(l, l * l)
            val full = LocalRecovery.target(y, c[0], c[1], 1.0)
            val half = LocalRecovery.target(y, c[0], c[1], 0.5)
            assertEquals(y, LocalRecovery.target(y, c[0], c[1], 0.0), y * 1e-9)
            assertTrue("$y: full $full half $half", full < half && half < y && full > 0.35)
        }
    }

    /**
     * docs/log-and-luts.md:70-74: the bright illumination is compressed and the detail reattached at unity, so stripes
     * on a bright wall keep more of their difference on screen than the viewing shoulder alone leaves them.
     */
    @Test fun textureOnABrightWallIsKept() {
        val width = 64
        val height = 64
        val luminance = DoubleArray(width * height) { if ((it % width) / 2 % 2 == 0) 3.0 else 5.0 }
        val gains = LocalRecovery.gains(luminance, width, height, 1.0)
        fun shown(y: Double) = LogColor.display(doubleArrayOf(y, y, y))[0]
        val plain = shown(5.0) - shown(3.0)
        val x = width / 2 + height / 2 * width
        val recovered = shown(luminance[x + 2] * gains[x + 2]) - shown(luminance[x] * gains[x])
        assertTrue("plain $plain recovered $recovered", recovered > plain * 2)
        // And the wall as a whole is darker than the viewing shoulder shows it.
        assertTrue(shown(luminance[x + 2] * gains[x + 2]) < shown(5.0))
    }

    /** Highlights' luminance curve and its inverse (YouRec/LogColor.swift:111-121). */
    @Test fun highlightsInvert() {
        for (amount in listOf(-1.0, -0.4, 0.0, 0.3, 1.0)) for (y in listOf(0.0, 0.2, 0.35, 0.5, 1.0, 4.0, 20.0)) {
            val mapped = LogColor.highlightsLuminance(y, amount)
            assertEquals(LogColor.highlights(doubleArrayOf(y, y, y), amount)[0], mapped, 1e-12)
            assertEquals("$amount $y", y, LogColor.highlightsInverse(mapped, amount), 1e-9 * maxOf(1.0, y))
        }
    }

    /**
     * The port's way of drawing it: the code values recoded so that the grade's table gives the balanced light with
     * the recovery's gain on it, through exposure, warmth, tint and Highlights, in both log profiles.
     */
    @Test fun recodedCodesGiveTheRecoveredLight() {
        val grades = listOf(
            ColorSettings(localRecovery = 1.0),
            ColorSettings(localRecovery = 1.0, exposure = 0.7, warmth = 0.4, tint = -0.3),
            ColorSettings(localRecovery = 0.6, highlights = -0.6),
            ColorSettings(localRecovery = 1.0, highlights = 0.5, exposure = -0.5),
        )
        val codes = listOf(doubleArrayOf(0.62, 0.6, 0.58), doubleArrayOf(0.8, 0.75, 0.7), doubleArrayOf(0.9, 0.5, 0.3), doubleArrayOf(0.3, 0.32, 0.35))
        for (profile in listOf(LUTInput.AppleLog, LUTInput.AppleLog2)) for (settings in grades) for (code in codes) for (gain in listOf(0.4, 0.8, 1.0, 1.3)) {
            val balanced = ColorGrade.logBalanced(settings, profile, code[0], code[1], code[2])
            val recoded = LocalRecovery.recode(code, settings, profile, gain)
            val drawn = ColorGrade.logBalanced(settings, profile, recoded[0], recoded[1], recoded[2])
            for (c in 0..2) assertEquals("$profile $settings $gain", balanced[c] * gain, drawn[c], 1e-7 * maxOf(1.0, abs(balanced[c])))
        }
    }

    /** A whole frame recoded and graded by the table's arithmetic is the original's grade with the recovery on its light. */
    @Test fun aRecodedFrameIsTheOriginalsGrade() {
        val width = 24
        val height = 16
        // A log frame: a bright sky over a grey ground, each with some texture.
        val scene = DoubleArray(width * height * 3) { i ->
            val p = i / 3
            val y = p / width
            val x = p % width
            (if (y < height / 2) 4.0 else 0.18) * (1 + 0.2 * ((x + y) % 3 - 1)) * (if (i % 3 == 2) 0.9 else 1.0)
        }
        val profile = LUTInput.AppleLog
        val codes = DoubleArray(scene.size) { i -> LogColor.encode(doubleArrayOf(scene[i / 3 * 3], scene[i / 3 * 3 + 1], scene[i / 3 * 3 + 2]), profile)[i % 3] }
        val settings = ColorSettings(localRecovery = 1.0, exposure = 0.3, highlights = -0.2)
        val recoded = LocalRecovery.recodeFrame(codes, width, height, settings, profile)
        val balanced = DoubleArray(scene.size) { i -> ColorGrade.logBalanced(settings, profile, codes[i / 3 * 3], codes[i / 3 * 3 + 1], codes[i / 3 * 3 + 2])[i % 3] }
        val recovered = LocalRecovery.apply(balanced, width, height, 1.0)
        for (p in 0 until width * height) {
            val drawn = ColorGrade.applyLog(settings, null, null, profile, recoded[p * 3], recoded[p * 3 + 1], recoded[p * 3 + 2])
            val wanted = ColorGrade.fromLogBalanced(settings, null, null, doubleArrayOf(recovered[p * 3], recovered[p * 3 + 1], recovered[p * 3 + 2]))
            for (c in 0..2) assertEquals(wanted[c], drawn[c], 1e-6)
        }
        // The sky is darker than without the recovery, the ground the same.
        val sky = ColorGrade.applyLog(settings, null, null, profile, recoded[0], recoded[1], recoded[2])
        val plainSky = ColorGrade.applyLog(settings, null, null, profile, codes[0], codes[1], codes[2])
        assertTrue(sky[1] < plainSky[1])
        val last = (width * height - 1) * 3
        val ground = ColorGrade.applyLog(settings, null, null, profile, recoded[last], recoded[last + 1], recoded[last + 2])
        val plainGround = ColorGrade.applyLog(settings, null, null, profile, codes[last], codes[last + 1], codes[last + 2])
        for (c in 0..2) assertEquals(plainGround[c], ground[c], 1e-6)
        // Without a recovery the frame is its codes.
        assertArrayEquals(codes, LocalRecovery.recodeFrame(codes, width, height, settings.copy(localRecovery = 0.0), profile), 0.0)
    }
}
