package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recorder's input gain. Tests marked TRANSLATED carry over the iPhone
 * app's captureAudioTests (Tests/CoreTests.swift:595-612) against
 * YouRec/CaptureAudio.swift:142-185.
 */
class RecordingGainTest {
    private fun stereo(frames: Int) = FloatArray(frames * 2) { if (it % 2 == 0) 0.4f else -0.2f }

    /** TRANSLATED CoreTests.swift:595-611, at every sample rate the original tries. */
    @Test fun gainShowsClippingAndAResetRestoresBothChannelsAfterTwentyMilliseconds() {
        for (rate in listOf(8000.0, 16000.0, 24000.0, 48000.0)) {
            val frames = (rate * 0.04).toInt()
            val source = stereo(frames)
            val gain = RecordingGain()
            gain.set(12.0)
            val loud = gain.process(source, 2, rate)
            assertTrue(loud[(frames - 1) * 2] > 1.5f)
            gain.set(0.0)
            val reset = gain.process(source, 2, rate)
            assertEquals(0.4f, reset[(frames - 1) * 2], 0.00001f)
            assertEquals(-0.2f, reset[(frames - 1) * 2 + 1], 0.00001f)
            assertEquals(source.size, reset.size)
            assertEquals(0.4f, source[0], 0f)
        }
    }

    @Test fun unityPassesTheBufferThrough() {
        val source = stereo(10)
        assertSame(source, RecordingGain().process(source, 2, 48000.0))
    }

    @Test fun theRampIsSpreadOverTwentyMilliseconds() {
        val gain = RecordingGain()
        gain.set(6.0)
        val out = gain.process(FloatArray(2000) { 1f }, 1, 48000.0)
        val full = 10.0.pow(6.0 / 20).toFloat()
        assertTrue(out[0] > 1f && out[0] < 1.01f)
        assertTrue(out[900] < full)
        assertEquals(full, out[960], 1e-5f)
        assertEquals(0f, gain.process(floatArrayOf(Float.NaN), 1, 48000.0)[0], 0f)
    }

    @Test fun levelsSitOnAHalfDecibelGridWithinRange() {
        assertEquals(1.5, RecordingGain.normalized(1.3), 0.0)
        assertEquals(-40.0, RecordingGain.normalized(-100.0), 0.0)
        assertEquals(12.0, RecordingGain.normalized(30.0), 0.0)
        assertEquals(0.0, RecordingGain.normalized(Double.NaN), 0.0)
        assertEquals(0.5f, RecordingGain().apply { set(0.0) }.process(shortArrayOf(16384), 1, 48000.0)[0], 0f)
    }

    private fun Double.pow(x: Double) = Math.pow(this, x)
}
