package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PausesTest {
    private val clip = TimelineClip(sourceID = "A", start = 0.0, end = 4.0)
    private val defaults = PauseSettings()
    private val zeroPadding = PauseSettings(before = 0.0, after = 0.0)

    // A short word (bins 190–209) between two pauses, inside louder speech.
    private val energy = FloatArray(400) { if (it in 190 until 210) 0.08f else if (it in 100 until 300) 0.01f else 0.24f }
    private val noisy = SourceWaveform(4.0, FloatArray(400) { energy[it] * 3 }, rms = energy)

    /** A noisy gap that never drops below the energy threshold, with a quiet word in it. */
    private fun noisyRoom() = FloatArray(400) { 0.04f }.also {
        for (i in 0 until 75) it[i] = 0.3f
        for (i in 190 until 211) it[i] = 0.003f
        for (i in 330 until 400) it[i] = 0.24f
    }

    private fun voice() = ByteArray(400) { 8 }.also {
        for (i in 0 until 75) it[i] = 235.toByte()
        for (i in 190 until 211) it[i] = 210.toByte()
        for (i in 330 until 400) it[i] = 240.toByte()
    }

    private fun candidates(raw: List<SourceInterval>) = raw.map { DetectedPause(it) }

    @Test fun fullyQuietClipIsOneReviewablePause() {
        val quiet = SourceWaveform(4.0, FloatArray(400) { 0.001f })
        val raw = PauseDetector.raw(quiet, clip, PauseSettings(levelMode = null))
        assertEquals(1, raw.size)
        // Fully quiet footage keeps exactly one selectable frame with zero padding.
        val quietCuts = PauseDetector.effectiveCuts(candidates(raw), clip, zeroPadding)
        assertEquals(4 - PauseDetector.FRAME, quietCuts.sumOf { it.duration }, 1e-6)
        // With padding, both values are honored.
        val protected = PauseDetector.effectiveCuts(candidates(raw), clip, defaults)
        assertEquals(3.7, protected.sumOf { it.duration }, 1e-6)
    }

    @Test fun paddingSwallowedPausesAreNotRemovals() {
        val short = listOf(DetectedPause(SourceInterval(1.0, 1.2)))
        assertTrue(PauseDetector.effectiveCuts(short, clip, defaults).isEmpty())
    }

    @Test fun overlappingCandidatesNeverDoubleCount() {
        val overlapping = listOf(DetectedPause(SourceInterval(1.0, 3.0)), DetectedPause(SourceInterval(2.0, 3.5)))
        assertEquals(listOf(SourceInterval(1.0, 3.5)), PauseDetector.effectiveCuts(overlapping, clip, zeroPadding))
    }

    @Test fun keptCandidatesAreNotCut() {
        val choices = listOf(DetectedPause(SourceInterval(1.0, 2.0), enabled = false), DetectedPause(SourceInterval(2.5, 3.5)))
        assertEquals(listOf(SourceInterval(2.5, 3.5)), PauseDetector.effectiveCuts(choices, clip, zeroPadding))
    }

    @Test fun continuousSpeechHasNoPause() {
        val speech = SourceWaveform(4.0, FloatArray(400) { 0.2f })
        assertTrue(PauseDetector.raw(speech, clip, PauseSettings(levelMode = null)).isEmpty())
    }

    @Test fun rawBoundariesAlignWithQuietWindows() {
        val split = SourceWaveform(4.0, FloatArray(400) { if (it in 100 until 250) 0.001f else 0.2f })
        assertEquals(listOf(SourceInterval(1.0, 2.5)), PauseDetector.raw(split, clip, PauseSettings(levelMode = null)))
    }

    @Test fun automaticLevelFollowsRoomTone() {
        // Headroom above room tone, well below speech.
        assertEquals(-32.0, PauseDetector.levelDB(noisy, defaults), 0.0)
        val cuts = PauseDetector.raw(noisy, clip, defaults)
        // Room tone is detected without cutting the short word between pauses.
        assertEquals(2, cuts.size)
        assertTrue(cuts[0].end <= 1.9 + 0.0001 && cuts[1].start >= 2.1 - 0.0001)
    }

    @Test fun manualThresholdIsHonored() {
        val fixed = PauseSettings(thresholdDB = -50.0, levelMode = PauseLevelMode.Manual)
        assertEquals(-50.0, PauseDetector.levelDB(noisy, fixed), 0.0)
        assertTrue(PauseDetector.raw(noisy, clip, fixed).isEmpty())
    }

    @Test fun savedSettingsWithoutLevelModeStillUsePeaks() {
        val stored = documentJson.encodeToString(PauseSettings.serializer(), defaults)
        assertTrue(stored.contains("\"levelMode\":\"automatic\""))
        assertEquals(defaults, documentJson.decodeFromString(PauseSettings.serializer(), stored))
        val legacy = documentJson.decodeFromString(PauseSettings.serializer(), stored.replace(",\"levelMode\":\"automatic\"", ""))
        assertNull(legacy.levelMode)
        assertEquals(defaults.copy(levelMode = null), legacy)
        assertFalse(documentJson.encodeToString(PauseSettings.serializer(), legacy).contains("levelMode"))
        assertTrue(PauseDetector.raw(noisy, clip, legacy).isEmpty())
    }

    @Test fun automaticDetectionNeverTreatsPeaksAsRMS() {
        val peaksOnly = SourceWaveform(4.0, energy)
        assertTrue(PauseDetector.raw(peaksOnly, clip, defaults).isEmpty())
    }

    @Test fun mostlyQuietTakeNeedsASustainedPassageAsReference() {
        val roomTone = FloatArray(1200) { 0.014f }
        roomTone[800] = 0.24f
        fun sparse(levels: FloatArray) = PauseDetector.raw(
            SourceWaveform(12.0, levels, rms = levels), TimelineClip(sourceID = "A", start = 0.0, end = 12.0), defaults,
        )
        // One click cannot calibrate an otherwise continuous signal as speech.
        assertTrue(sparse(roomTone).isEmpty())
        for (i in 780 until 830) roomTone[i] = 0.24f
        // A brief spoken passage is still protected.
        assertEquals(2, sparse(roomTone).size)
    }

    @Test fun speechProtectsAQuietWordBetweenNoisyPauses() {
        val levels = noisyRoom()
        val dialogue = SourceWaveform(4.0, levels, rms = levels, speech = voice())
        val cuts = PauseDetector.raw(dialogue, clip, defaults)
        assertEquals(2, cuts.size)
        assertEquals(0.75, cuts[0].start, 1e-6); assertEquals(1.9, cuts[0].end, 1e-6)
        assertEquals(2.11, cuts[1].start, 1e-6); assertEquals(3.3, cuts[1].end, 1e-6)
        // Manual mode uses the selected dB threshold, even with speech probabilities.
        val manual = PauseSettings(thresholdDB = PauseDetector.levelDB(dialogue, defaults), levelMode = PauseLevelMode.Manual)
        assertTrue(PauseDetector.raw(dialogue, clip, manual).isEmpty())
    }

    @Test fun unclassifiedSyllableIsKeptButANoiseSpikeIsNot() {
        val missedOnset = noisyRoom()
        for (i in 166 until 179) missedOnset[i] = 0.12f
        missedOnset[110] = 0.12f
        val withOnset = SourceWaveform(4.0, missedOnset, rms = missedOnset, speech = voice())
        val protected = PauseDetector.raw(withOnset, clip, defaults)
        assertTrue(protected.any { it.start < 1.10 && it.end > 1.12 })
        assertFalse(protected.any { it.start < 1.77 && it.end > 1.68 })
    }

    @Test fun ambientTakeWithoutDialogueIsNotASpeechPause() {
        val levels = noisyRoom()
        val ambient = SourceWaveform(4.0, levels, rms = levels, speech = ByteArray(400) { 4 })
        assertTrue(PauseDetector.raw(ambient, clip, defaults).isEmpty())
    }

    // The checks below have no counterpart in the original tests.

    @Test fun detectionStaysInsideTheClip() {
        val quiet = SourceWaveform(4.0, FloatArray(400) { 0.001f })
        val part = TimelineClip(sourceID = "A", start = 1.0, end = 3.0)
        assertEquals(listOf(SourceInterval(1.0, 3.0)), PauseDetector.raw(quiet, part, PauseSettings(levelMode = null)))
        val padded = PauseDetector.detect(quiet, part, PauseSettings(levelMode = null)).single()
        assertEquals(1.12, padded.start, 1e-9); assertEquals(2.82, padded.end, 1e-9)
        assertTrue(PauseDetector.raw(SourceWaveform(0.0, FloatArray(0)), part, PauseSettings(levelMode = null)).isEmpty())
    }

    @Test fun minimumAndPaddingAreMeasuredInPlayedTime() {
        // One source second of silence lasts half a second at double speed.
        val split = SourceWaveform(4.0, FloatArray(400) { if (it in 100 until 200) 0.001f else 0.2f })
        val legacy = PauseSettings(levelMode = null)
        assertEquals(1, PauseDetector.raw(split, clip, legacy).size)
        assertTrue(PauseDetector.raw(split, clip, legacy, rate = 2.0).isEmpty())
        val raw = SourceInterval(1.0, 3.0)
        assertEquals(1.24, PauseDetector.padded(raw, 2.0, defaults).start, 1e-9)
        val padded = PauseDetector.padded(raw, clip, defaults, rate = 2.0)
        assertEquals(1.24, padded.start, 1e-9); assertEquals(2.64, padded.end, 1e-9)
    }

    @Test fun keptIntervalsAreWhatTheCutsLeave() {
        val cuts = listOf(SourceInterval(2.0, 3.0), SourceInterval(-1.0, 0.5), SourceInterval(2.5, 3.5), SourceInterval(9.0, 10.0))
        assertEquals(listOf(SourceInterval(0.5, 2.0), SourceInterval(3.5, 4.0)), PauseDetector.keptIntervals(clip, cuts))
        assertEquals(listOf(SourceInterval(0.0, 4.0)), PauseDetector.keptIntervals(clip, emptyList()))
    }

    @Test fun builderPeaksWorkWithAManualPeakThreshold() {
        // 50 peaks per second, 0–255: one second of speech, two of silence, one of speech.
        val bytes = ByteArray(200) { if (it in 50 until 150) 1 else 200.toByte() }
        val wave = SourceWaveform.fromPeaks(bytes)
        assertEquals(4.0, wave.duration, 0.0)
        assertEquals(200 / 255f, wave.peak(0.5), 0f)
        assertEquals(listOf(SourceInterval(1.0, 3.0)), PauseDetector.raw(wave, clip, PauseSettings(levelMode = null)))
        // Without RMS the automatic mode finds nothing rather than guessing.
        assertTrue(PauseDetector.raw(wave, clip, defaults).isEmpty())
    }
}
