package app.takes.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/** The original's listening-level tests (Tests/CoreTests.swift:360-384) and its preview range (Tests/TrackMediaTests.swift:83-85). */
class AudioListeningLevelsTest {
    /** CoreTests.swift:362-366: one recording played whole, or the part of it in [range]. */
    private fun gains(original: List<Float>, processed: List<Float>, range: ClosedFloatingPointRange<Double>? = null): AudioListeningLevels.Gains {
        val played = range ?: 0.0..(original.size * AudioListeningLevels.WINDOW)
        return AudioListeningLevels.gains(listOf(EffectPlaybackSpan(played.start, played.endInclusive, 1.0)), original.toFloatArray(), processed.toFloatArray())
    }

    @Test fun aLouderProcessedVoiceIsTurnedDown() {
        // CoreTests.swift:367-368: +6 dB.
        val louder = gains(listOf(0.01f, 0.01f), listOf(0.04f, 0.04f))
        assertEquals(1f, louder.original, 0f)
        assertTrue(abs(louder.processed - 0.5f) < 0.0001f)
    }

    @Test fun aQuieterProcessedVoiceTurnsTheOriginalDown() {
        // CoreTests.swift:369-370.
        val quieter = gains(listOf(0.01f, 0.01f), listOf(0.0025f, 0.0025f))
        assertTrue(abs(quieter.original - 0.5f) < 0.0001f)
        assertEquals(1f, quieter.processed, 0f)
    }

    @Test fun removedNoiseNeverTurnsTheOriginalDown() {
        // CoreTests.swift:371-372: near-silence left by the denoiser.
        assertEquals(AudioListeningLevels.Gains(1f, 1f), gains(listOf(0.0000015f, 0.0000015f, 0.0000015f), listOf(0.00000025f, 0.000000001f, 0.0000000001f)))
        // CoreTests.swift:373-374: strong suppression, residual above the silence floor.
        assertEquals(AudioListeningLevels.Gains(1f, 1f), gains(listOf(0.01f, 0.01f), listOf(0.00001f, 0.00001f)))
    }

    @Test fun onlyTheSurvivingVoiceIsMatchedAndTrimsAreFollowed() {
        // CoreTests.swift:375-378.
        val original = List(10) { 0.01f } + listOf(0.01f, 0.01f)
        val processed = List(10) { 0.00000001f } + listOf(0.04f, 0.04f)
        val mixed = gains(original, processed)
        assertEquals(1f, mixed.original, 0f)
        assertTrue(abs(mixed.processed - 0.5f) < 0.0001f)
        // CoreTests.swift:379-380: with the speech trimmed away only noise is left.
        assertEquals(AudioListeningLevels.Gains(1f, 1f), gains(original, processed, 0.0..4.0))
    }

    @Test fun silenceKeepsBothAsTheyAre() {
        // CoreTests.swift:381-382.
        assertEquals(AudioListeningLevels.Gains(1f, 1f), gains(listOf(0f, 0f), listOf(0f, 0f)))
        assertEquals(AudioListeningLevels.Gains(1f, 1f), AudioListeningLevels.gains(emptyList<AudioListeningLevels.Window>()))
    }

    @Test fun neitherIsTurnedDownBelowAnEighth() {
        // OfflineAudioPipeline.swift:253: 99 times the energy is within the 20 dB gate; an eighth is −18 dB.
        val loud = AudioListeningLevels.gains(floatArrayOf(0.99f), floatArrayOf(0.01f))
        assertEquals(0.125f, loud.original, 0f)
        assertEquals(1f, loud.processed, 0f)
    }

    @Test fun windowsCountForAsLongAsTheyPlay() {
        // OfflineAudioPipeline.swift:229-233. The second window is heard for a quarter of its length.
        val original = floatArrayOf(0.01f, 0.04f)
        val processed = floatArrayOf(0.01f, 0.01f)
        val part = AudioListeningLevels.gains(listOf(EffectPlaybackSpan(0.0, 0.5, 1.0)), original, processed)
        // Energy in use: (0.01 × 0.4 + 0.04 × 0.1) against (0.01 × 0.5).
        assertEquals(Math.sqrt(0.005 / 0.008).toFloat(), part.original, 1e-6f)
        assertEquals(1f, part.processed, 0f)
        // Playing faster shortens every window alike and changes nothing.
        assertEquals(part, AudioListeningLevels.gains(listOf(EffectPlaybackSpan(0.0, 0.5, 2.0)), original, processed))
        // Windows past the measured sound are ignored.
        assertEquals(part, AudioListeningLevels.gains(listOf(EffectPlaybackSpan(0.0, 0.5, 1.0), EffectPlaybackSpan(4.0, 9.0, 1.0)), original, processed))
        // Two versions of the same stretch, window by window (OfflineAudioPipeline.swift:298-300).
        val whole = AudioListeningLevels.gains(original, processed)
        assertEquals(Math.sqrt(0.02 / 0.05).toFloat(), whole.original, 1e-6f)
    }

    @Test fun energiesAreMeansOverFourTenthsOfASecond() {
        // OfflineAudioPipeline.swift:277-294: 0.4 s of stereo at 48 kHz is 38400 samples.
        val samples = FloatArray(38400 * 2 + 1000) { if (it < 38400) 0.1f else if (it < 76800) -0.5f else 0.25f }
        val energies = AudioListeningLevels.energies(samples)
        assertArrayEquals(floatArrayOf(0.01f, 0.25f, 0.0625f), energies, 1e-7f)
        assertEquals(0, AudioListeningLevels.energies(FloatArray(0)).size)
        assertEquals(2, AudioListeningLevels.energies(FloatArray(19200 + 1), channels = 1).size)
        try {
            AudioListeningLevels.energies(floatArrayOf(0f, Float.NaN)); fail()
        } catch (error: AudioChainException) {
            assertEquals(AudioChainException.Reason.InvalidFormat, error.reason)
        }
    }

    @Test fun anOutputGainSuspendsMatching() {
        // OfflineAudioPipeline.swift:261-263.
        val gain = AudioEffect(kind = AudioEffectKind.Gain, amount = 6.0)
        assertFalse(AudioListeningLevels.allowsMatching(listOf(AudioEffect.make(AudioEffectKind.HighPass), gain)))
        assertTrue(AudioListeningLevels.allowsMatching(listOf(gain.copy(enabled = false))))
        assertTrue(AudioListeningLevels.allowsMatching(listOf(AudioEffect.make(AudioEffectKind.Equalizer).copy(amount = 6.0))))
        assertTrue(AudioListeningLevels.allowsMatching(emptyList()))
    }

    @Test fun thePreviewIsFiveSecondsEitherSideOfThePlayheadInsideTheSelection() {
        // TrackMediaTests.swift:83-85.
        assertEquals(25.0..35.0, AudioListeningLevels.listeningRange(30.0, 0.0..60.0))
        assertEquals(0.0..5.0, AudioListeningLevels.listeningRange(0.0, 0.0..60.0))
        assertEquals(32.0..34.0, AudioListeningLevels.listeningRange(33.0, 32.0..34.0))
        // A playhead outside the selection listens from its nearest end.
        assertEquals(55.0..60.0, AudioListeningLevels.listeningRange(90.0, 0.0..60.0))
    }
}
