package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.math.roundToInt

class HdrToneMapTest {
    /** A display's 8-bit sRGB level of linear [l], as the original's test reads its pixels (Tests/VideoEffectIntegrationTests.swift:26-33). */
    private fun srgb8(l: Double): Int {
        val v = l.coerceIn(0.0, 1.0)
        return (255 * (if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055)).roundToInt()
    }

    /** The grey of HLG's signal [e], in display light from reference white, tone-mapped or held to SDR white. */
    private fun grey(e: Double, mapped: Boolean): Double {
        val light = HdrToneMap.hlgDisplay(doubleArrayOf(e, e, e))
        return if (mapped) HdrToneMap.toneMapped(light)[1] else light[1].coerceAtMost(1.0)
    }

    // YouRec/VideoEffects/MetalVideoEffects.swift:213-215: camera HLG uses 1,000 nits over a 203-nit white.
    @Test fun theSourceHeadroomIsTheOriginals() {
        assertEquals(1000.0 / 203.0, HdrToneMap.SOURCE_HEADROOM, 0.0)
        // HLG's reference white, 75 % of the signal (ITU-R BT.2408), is display light 1.
        assertEquals(1.0, HdrToneMap.hlgDisplay(doubleArrayOf(0.75, 0.75, 0.75))[1], 0.002)
        // The peak of the signal is the source headroom.
        assertEquals(HdrToneMap.SOURCE_HEADROOM, HdrToneMap.hlgDisplay(doubleArrayOf(1.0, 1.0, 1.0))[1], 1e-6)
    }

    // ITU-R BT.2390-10 section 5.4.1: the EETF ends at the target's peak and is the identity below its knee.
    @Test fun theCurveTakesTheHeadroomToWhiteAndLeavesShadowsAlone() {
        assertEquals(1.0, HdrToneMap.eetf(HdrToneMap.SOURCE_HEADROOM), 1e-9)
        assertEquals(1.0, HdrToneMap.eetf(10.0), 1e-9)
        assertEquals(0.0, HdrToneMap.eetf(0.0), 0.0)
        for (light in listOf(0.001, 0.01, 0.1, 0.25, 0.4)) assertEquals(light, HdrToneMap.eetf(light), 1e-9)
        // Monotonic and continuous across the knee and up to the peak.
        var last = 0.0
        for (step in 1..2000) {
            val value = HdrToneMap.eetf(step / 2000.0 * HdrToneMap.SOURCE_HEADROOM)
            assertTrue("rises at step $step", value >= last)
            assertTrue("no jump at step $step", value - last < 0.01)
            last = value
        }
        // With no headroom there is nothing to compress.
        assertEquals(0.5, HdrToneMap.eetf(0.5, 1.0), 0.0)
    }

    // Tests/VideoEffectIntegrationTests.swift:7-40: a transfer-tagged HLG ramp from 0 to 1, read as a display's 8-bit
    // sRGB. Its bright detail stays distinct from display white, which the clamp the port used to apply loses.
    @Test fun anHlgRampKeepsItsHighlightDetail() {
        val width = 256
        val ramp = (0 until width).map { it / (width - 1.0) }
        val mapped = ramp.map { srgb8(grey(it, mapped = true)) }
        val clamped = ramp.map { srgb8(grey(it, mapped = false)) }
        // The top of the ramp is display white (VideoEffectIntegrationTests.swift:35).
        assertTrue(mapped.last() > 250)
        // Shadows and midtones are those of the clamp: the curve starts above 0.43 of reference white.
        for (x in 0 until width / 2) assertEquals("x $x", clamped[x], mapped[x])
        // Monotonic.
        for (x in 1 until width) assertTrue("x $x", mapped[x] >= mapped[x - 1])
        // Above reference white the clamp is white throughout; the tone map keeps detail, rising to the end.
        val white = (0.75 * (width - 1)).roundToInt() + 1
        assertTrue(clamped.subList(white, width).all { it == 255 })
        assertTrue(mapped[white] < 245)
        assertTrue(mapped.subList(white, width).toSet().size >= 12)
        // HLG's reference white comes down a little to make room for the highlights.
        assertTrue(srgb8(grey(0.75, mapped = true)) in 220..235)
    }

    // The shader's conversion (app/HdrMovie.kt `hlgToDisplaySdr`) on colours: hue kept, nothing past white, black stays black.
    @Test fun coloursKeepTheirHueAndStayInside() {
        assertEquals(listOf(0.0, 0.0, 0.0), HdrToneMap.hlgToSdr(doubleArrayOf(0.0, 0.0, 0.0)).toList())
        val white = HdrToneMap.hlgToSdr(doubleArrayOf(1.0, 1.0, 1.0))
        for (c in white) assertEquals(1.0, c, 1e-6)
        // A bright orange keeps red above green above blue, every channel inside 0…1.
        val orange = HdrToneMap.hlgToSdr(doubleArrayOf(1.0, 0.8, 0.4))
        assertTrue(orange[0] > orange[1] && orange[1] > orange[2])
        for (c in orange) assertTrue(c in 0.0..1.0)
        // The ratio of the light of two channels is kept by the tone map itself.
        val light = HdrToneMap.hlgDisplay(doubleArrayOf(0.95, 0.7, 0.5))
        val mapped = HdrToneMap.toneMapped(light)
        assertEquals(light[1] / light[0], mapped[1] / mapped[0], 1e-9)
        assertTrue(mapped.max() <= 1.0)
    }

    // The constants written into the export's shader (app/HdrMovie.kt `eetf`).
    @Test fun theShadersConstantsAreTheCurves() {
        assertEquals(0.75182710, HdrToneMap.pq(1000.0), 1e-8)
        assertEquals(0.77237025, HdrToneMap.pq(203.0) / HdrToneMap.pq(1000.0), 1e-8)
    }
}
