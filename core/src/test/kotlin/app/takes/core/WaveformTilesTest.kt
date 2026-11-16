package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The detailed waveform tiles and the peak pyramid, against
 * YouRec/WaveformDetail.swift:4-66 and YouRec/EditorTimeline.swift:778-796
 * and 840-846. The original has no test of its own for them; these are the
 * port's, with a sine, silence and a last bin that is not filled.
 */
class WaveformTilesTest {
    private val rate = 48000.0

    private fun sine(seconds: Double, frequency: Double, amplitude: Float, channels: Int = 2) =
        FloatArray((seconds * rate).toInt() * channels) { i -> (amplitude * sin(2 * PI * frequency * (i / channels) / rate)).toFloat() }

    @Test fun aSineFillsEveryBinWithItsExtremes() {
        // 250 Hz: one cycle per four bins, so every four bins hold a crest and a trough.
        val tile = WaveformTile.build(0, sine(4.0, 250.0, 0.5f), 2, rate, 0.0)
        assertEquals(4000, tile.low.size)
        for (bin in 0 until 4000 step 4) {
            val (lo, hi) = tile.envelope(bin / 1000.0, (bin + 4) / 1000.0)!!
            assertEquals(-0.5f, lo, 0.001f)
            assertEquals(0.5f, hi, 0.001f)
        }
        val (lo, hi) = tile.envelope(0.0, 4.0)!!
        assertEquals(-0.5f, lo, 0.0001f)
        assertEquals(0.5f, hi, 0.0001f)
    }

    @Test fun silenceIsZeroAndATimeOutsideTheTileHasNoEnvelope() {
        val tile = WaveformTile.build(1, FloatArray(48000 * 2), 2, rate, 4.0)
        assertEquals(4.0, tile.start, 0.0)
        assertEquals(0f to 0f, tile.envelope(4.5, 4.6))
        assertNull(tile.envelope(3.9, 4.1))
        assertNull(tile.envelope(8.0, 8.1))
        assertEquals(1, WaveformTile.index(4.0))
    }

    @Test fun samplesThatDoNotFillTheLastBinStillCount() {
        // 1 ms of sound from 3.9985 s: it ends half way through the last bin.
        val tile = WaveformTile.build(0, FloatArray(48) { 0.25f }, 1, rate, 3.9985)
        assertEquals(0.25f, tile.high[3999], 0f)
        assertEquals(0.25f, tile.high[3998], 0f)
        assertEquals(0f, tile.high[3997], 0f)
        assertEquals(0.25f, tile.envelope(3.9995, 3.9999)!!.second, 0f)
    }

    @Test fun readsInTurnBuildOneTileAndColumnsCrossIntoTheNext() {
        val first = WaveformTile.Builder(0).add(FloatArray(480) { 0.1f }, 1, rate, 3.99).add(FloatArray(480) { -0.3f }, 1, rate, 1.0).tile()
        assertEquals(-0.3f, first.envelope(1.0, 1.01)!!.first, 0f)
        val second = WaveformTile.build(1, FloatArray(480) { 0.7f }, 1, rate, 4.0)
        val tiles = mapOf(0 to first, 1 to second)
        assertEquals(0f to 0.7f, WaveformTile.envelope(tiles::get, 3.995, 4.005))
        assertEquals(0f to 0.1f, WaveformTile.envelope({ if (it == 0) first else null }, 3.995, 4.005))
    }

    @Test fun aCoarserLevelKeepsTheExtremesOfEachPair() {
        val tile = WaveformTile(0.0, floatArrayOf(-0.1f, -0.5f, -0.2f), floatArrayOf(0.3f, 0.1f, 0.4f))
        val coarse = tile.coarser()
        assertTrue(floatArrayOf(-0.5f, -0.2f) contentEquals coarse.low)
        assertTrue(floatArrayOf(0.3f, 0.4f) contentEquals coarse.high)
    }

    @Test fun thePyramidFindsTheLoudestPeakOfAnySpan() {
        val peaks = FloatArray(100) { (it % 17) / 20f }
        val pyramid = PeakPyramid(peaks)
        assertEquals(1, pyramid.levels.last().size)
        for (a in 0 until 100 step 7) for (b in a + 1..100 step 5) {
            val expected = (a until b).maxOf { peaks[it] }
            assertEquals("$a..$b", expected, pyramid.peak(a.toDouble(), b.toDouble(), 100.0), 0f)
        }
        assertEquals(peaks[42], pyramid.peak(42.0, 42.2, 100.0), 0f)
        assertEquals(0f, PeakPyramid(FloatArray(0)).peak(0.0, 1.0, 1.0), 0f)
    }
}
