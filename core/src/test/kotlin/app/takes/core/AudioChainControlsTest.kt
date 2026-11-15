package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Faders, texts and chain editing, from the original's YouRec/AudioChainControls.swift and YouRec/TrackEditors.swift. */
class AudioChainControlsTest {
    private fun gain(db: Double, id: String = newID()) = AudioEffect(id = id, kind = AudioEffectKind.Gain, amount = db)
    private fun lowCut(hz: Double) = AudioEffect(kind = AudioEffectKind.HighPass, frequency = hz)
    private fun eq(db: Double, hz: Double = 1800.0) = AudioEffect(kind = AudioEffectKind.Equalizer, amount = db, frequency = hz)
    private fun neural(kind: AudioEffectKind, amount: Double) = AudioEffect.make(kind).copy(amount = amount)

    // Fader position, AudioChainControls.swift:34-38.

    @Test fun whereTheFaderSits() {
        assertEquals(0.5, AudioFader.position(gain(0.0)), 0.0)
        assertEquals(0.0, AudioFader.position(gain(-24.0)), 0.0)
        assertEquals(1.0, AudioFader.position(eq(24.0)), 0.0)
        assertEquals(0.75, AudioFader.position(eq(12.0)), 0.0)
        // The low-cut runs from 20 Hz to 500 Hz by pitch: ln(f/20) / ln(25).
        assertEquals(0.0, AudioFader.position(lowCut(20.0)), 0.0)
        assertEquals(0.43067655807339306, AudioFader.position(lowCut(80.0)), 1e-15)
        assertEquals(0.5, AudioFader.position(lowCut(100.0)), 1e-15)
        assertEquals(1.0, AudioFader.position(lowCut(500.0)), 1e-15)
        assertEquals(0.35, AudioFader.position(neural(AudioEffectKind.StudioVoice, 0.35)), 0.0)
        assertEquals(1.0, AudioFader.position(neural(AudioEffectKind.VoiceIsolation, 1.0)), 0.0)
        // Settings outside the fader's travel sit at its ends.
        assertEquals(1.0, AudioFader.position(lowCut(2000.0)), 0.0)
        assertEquals(0.0, AudioFader.position(lowCut(5.0)), 0.0)
        assertEquals(1.0, AudioFader.position(gain(40.0)), 0.0)
        assertEquals(0.0, AudioFader.position(neural(AudioEffectKind.StudioRestoration, -1.0)), 0.0)
    }

    // The setting a position stands for, AudioChainControls.swift:39-46.

    @Test fun whatAPositionStandsFor() {
        assertEquals(-24.0, AudioFader.setting(gain(0.0), 0.0).amount, 0.0)
        assertEquals(0.0, AudioFader.setting(gain(5.0), 0.5).amount, 0.0)
        assertEquals(12.0, AudioFader.setting(eq(0.0), 0.75).amount, 0.0)
        assertEquals(24.0, AudioFader.setting(gain(0.0), 1.0).amount, 0.0)
        // Tenths of a decibel.
        assertEquals(4.8, AudioFader.setting(gain(0.0), 0.6).amount, 0.0)
        assertEquals(-7.7, AudioFader.setting(gain(0.0), 0.34).amount, 0.0)
        // Whole Hz: 20 × 25^n.
        assertEquals(20.0, AudioFader.setting(lowCut(80.0), 0.0).frequency, 0.0)
        assertEquals(45.0, AudioFader.setting(lowCut(80.0), 0.25).frequency, 0.0)
        assertEquals(100.0, AudioFader.setting(lowCut(80.0), 0.5).frequency, 0.0)
        assertEquals(224.0, AudioFader.setting(lowCut(80.0), 0.75).frequency, 0.0)
        assertEquals(500.0, AudioFader.setting(lowCut(80.0), 1.0).frequency, 0.0)
        // Whole percent.
        assertEquals(0.35, AudioFader.setting(neural(AudioEffectKind.StudioVoice, 1.0), 0.354).amount, 0.0)
        assertEquals(0.36, AudioFader.setting(neural(AudioEffectKind.StudioVoice, 1.0), 0.356).amount, 0.0)
        // Past the ends is the end.
        assertEquals(24.0, AudioFader.setting(gain(0.0), 2.0).amount, 0.0)
        assertEquals(20.0, AudioFader.setting(lowCut(80.0), -1.0).frequency, 0.0)
        assertEquals(1.0, AudioFader.setting(neural(AudioEffectKind.VoiceIsolation, 0.0), 1.5).amount, 0.0)
    }

    @Test fun theFaderOnlyChangesItsOwnSetting() {
        val band = eq(3.0, 2500.0).copy(enabled = false)
        assertEquals(band.copy(amount = 12.0), AudioFader.setting(band, 0.75))
        val cut = lowCut(80.0).copy(amount = 7.0)
        assertEquals(cut.copy(frequency = 100.0), AudioFader.setting(cut, 0.5))
        val model = AudioEffect.make(AudioEffectKind.VoiceIsolation).copy(model = AudioModel.DeepFilter, frequency = 300.0)
        assertEquals(model.copy(amount = 0.5), AudioFader.setting(model, 0.5))
    }

    @Test fun everySettingTheFaderCanShowComesBackFromItsPosition() {
        for (tenths in -240..240) {
            val effect = gain(tenths / 10.0)
            assertEquals(effect, AudioFader.setting(effect, AudioFader.position(effect)))
        }
        for (hz in 20..500) {
            val effect = lowCut(hz.toDouble())
            assertEquals(effect, AudioFader.setting(effect, AudioFader.position(effect)))
        }
        for (percent in 0..100) {
            val effect = neural(AudioEffectKind.StudioVoice, percent / 100.0)
            assertEquals(effect, AudioFader.setting(effect, AudioFader.position(effect)))
        }
        // And a position comes back from its setting to within the fader's grain.
        for (step in 0..1000) {
            val position = step / 1000.0
            assertEquals(position, AudioFader.position(AudioFader.setting(gain(0.0), position)), 0.05 / 48 + 1e-12)
            assertEquals(position, AudioFader.position(AudioFader.setting(neural(AudioEffectKind.VoiceIsolation, 0.0), position)), 0.005 + 1e-12)
        }
    }

    // The voice EQ's frequency slider, AudioChainControls.swift:419-420.

    @Test fun theEQFrequencySliderRunsFrom80HzTo12kHzByPitch() {
        assertEquals(80.0, AudioFader.eqFrequency(0.0), 0.0)
        assertEquals(280.0, AudioFader.eqFrequency(0.25), 0.0)
        assertEquals(980.0, AudioFader.eqFrequency(0.5), 0.0)
        assertEquals(3429.0, AudioFader.eqFrequency(0.75), 0.0)
        assertEquals(12000.0, AudioFader.eqFrequency(1.0), 0.0)
        assertEquals(0.0, AudioFader.eqFrequencyPosition(80.0), 0.0)
        assertEquals(0.6213813471674642, AudioFader.eqFrequencyPosition(1800.0), 1e-15)
        assertEquals(1.0, AudioFader.eqFrequencyPosition(12000.0), 1e-15)
        assertEquals(0.0, AudioFader.eqFrequencyPosition(30.0), 0.0)
        for (hz in 80..12000) assertEquals(hz.toDouble(), AudioFader.eqFrequency(AudioFader.eqFrequencyPosition(hz.toDouble())), 0.0)
    }

    // Dragging, AudioChainControls.swift:356-364.

    @Test fun aDraggedFaderCatchesOnTheStartingValueAndTheEnds() {
        // The gain and the EQ catch within 0.4 dB of 0 dB.
        assertEquals(0.5, AudioFader.drag(AudioEffectKind.Gain, 0.3, 0.195), 0.0)
        assertEquals(0.5, AudioFader.drag(AudioEffectKind.Equalizer, 0.6, -0.092), 0.0)
        assertEquals(0.49, AudioFader.drag(AudioEffectKind.Gain, 0.3, 0.19), 1e-15)
        assertEquals(0.5, AudioFader.snap(AudioEffectKind.Gain, 0.5 + 0.4 / 48)!!, 0.0)
        assertNull(AudioFader.snap(AudioEffectKind.Gain, 0.5 + 0.41 / 48))
        // The others catch within 1% of the height: the low-cut at 80 Hz, the models at their starting amounts.
        assertEquals(0.43067655807339306, AudioFader.drag(AudioEffectKind.HighPass, 0.2, 0.225), 1e-15)
        assertEquals(0.35, AudioFader.drag(AudioEffectKind.StudioVoice, 0.3, 0.045), 0.0)
        assertEquals(1.0, AudioFader.drag(AudioEffectKind.VoiceIsolation, 0.9, 0.095), 0.0)
        assertNull(AudioFader.snap(AudioEffectKind.StudioVoice, 0.37))
        // The ends.
        assertEquals(1.0, AudioFader.drag(AudioEffectKind.Gain, 0.3, 5.0), 0.0)
        assertEquals(0.0, AudioFader.drag(AudioEffectKind.HighPass, 0.3, -5.0), 0.0)
        assertEquals(0.0, AudioFader.snap(AudioEffectKind.StudioVoice, -0.2)!!, 0.0)
        // Anywhere else the level follows the finger.
        assertEquals(0.2, AudioFader.drag(AudioEffectKind.Gain, 0.3, -0.1), 1e-15)
        assertEquals(0.5, AudioFader.center(AudioEffectKind.Equalizer), 0.0)
    }

    // Accessibility steps, AudioChainControls.swift:366-372.

    @Test fun oneStepIsAPercentASemitoneOrHalfADecibel() {
        assertEquals(0.01, AudioFader.step(AudioEffectKind.StudioRestoration), 0.0)
        assertEquals(0.01794485658639138, AudioFader.step(AudioEffectKind.HighPass), 1e-15)
        assertEquals(0.5 / 48, AudioFader.step(AudioEffectKind.Gain), 0.0)
        assertEquals(0.5, AudioFader.stepped(gain(0.0), 0.5, 1).amount, 0.0)
        assertEquals(-1.0, AudioFader.stepped(gain(0.0), 0.5, -2).amount, 0.0)
        // Twelve semitones up is an octave.
        assertEquals(160.0, AudioFader.stepped(lowCut(80.0), AudioFader.position(lowCut(80.0)), 12).frequency, 0.0)
        assertEquals(0.36, AudioFader.stepped(neural(AudioEffectKind.StudioVoice, 0.35), 0.35, 1).amount, 0.0)
        assertEquals(24.0, AudioFader.stepped(gain(24.0), 1.0, 1).amount, 0.0)
    }

    // Texts, AudioChainControls.swift:47-55.

    @Test fun howAFrequencyIsWritten() {
        assertEquals("80 Hz", AudioFader.frequencyText(80.0))
        assertEquals("999 Hz", AudioFader.frequencyText(999.0))
        assertEquals("1.0 kHz", AudioFader.frequencyText(1000.0))
        assertEquals("1.8 kHz", AudioFader.frequencyText(1800.0))
        assertEquals("12.0 kHz", AudioFader.frequencyText(12000.0))
        // Halves go to the even digit.
        assertEquals("1.2 kHz", AudioFader.frequencyText(1250.0))
        assertEquals("1.4 kHz", AudioFader.frequencyText(1350.0))
        assertEquals("1.2 kHz", AudioFader.frequencyText(1150.0))
        assertEquals("", AudioFader.frequencyText(Double.NaN))
    }

    @Test fun howAFaderValueIsWritten() {
        assertEquals("35%", AudioFader.valueText(AudioEffect.make(AudioEffectKind.StudioVoice)))
        assertEquals("100%", AudioFader.valueText(AudioEffect.make(AudioEffectKind.VoiceIsolation)))
        assertEquals("0%", AudioFader.valueText(neural(AudioEffectKind.StudioRestoration, 0.0)))
        assertEquals("7%", AudioFader.valueText(neural(AudioEffectKind.StudioVoice, 0.07)))
        assertEquals("80 Hz", AudioFader.valueText(AudioEffect.make(AudioEffectKind.HighPass)))
        assertEquals("+0.0 dB", AudioFader.valueText(AudioEffect.make(AudioEffectKind.Gain)))
        assertEquals("+3.0 dB", AudioFader.valueText(gain(3.0)))
        assertEquals("-6.0 dB", AudioFader.valueText(gain(-6.0)))
        assertEquals("+4.8 dB", AudioFader.valueText(eq(4.8)))
        assertEquals("-24.0 dB", AudioFader.valueText(eq(-24.0)))
        // AudioChainControls.swift:334, Tests/EditorUITests.swift:7600.
        assertEquals("-6.0 dB, bypassed", AudioFader.spokenValue(gain(-6.0).copy(enabled = false)))
        assertEquals("-6.0 dB", AudioFader.spokenValue(gain(-6.0)))
    }

    @Test fun namesAndColorsOfTheColumns() {
        // AudioChainControls.swift:7-17.
        assertEquals(listOf("Denoise", "Detail", "Studio", "Low-cut", "EQ", "Gain"), AudioEffectKind.all.map { AudioFader.stripName(it) })
        assertEquals("Unknown", AudioFader.stripName(AudioEffectKind.Unavailable("x")))
        // AudioChainControls.swift:29-30.
        assertEquals(listOf("Amount", "Amount", "Amount", "Cutoff", "Gain", "Gain"), AudioEffectKind.all.map { AudioFader.primaryLabel(it) })
        assertEquals(
            listOf("Noise reduction amount", "Speech detail restoration amount", "Studio restoration amount", "Low-cut filter cutoff", "Voice EQ gain", "Output gain"),
            AudioEffectKind.all.map { AudioFader.faderLabel(it) },
        )
        // AudioChainControls.swift:290-296.
        assertEquals("RNNoise", AudioFader.detailText(AudioEffect.make(AudioEffectKind.VoiceIsolation)))
        assertEquals("DFN3", AudioFader.detailText(AudioEffect.make(AudioEffectKind.VoiceIsolation).copy(model = AudioModel.DeepFilter)))
        assertEquals("LavaSR", AudioFader.detailText(AudioEffect.make(AudioEffectKind.StudioVoice)))
        assertEquals("VoiceFixer", AudioFader.detailText(AudioEffect.make(AudioEffectKind.StudioRestoration)))
        assertEquals("2.5 kHz", AudioFader.detailText(eq(0.0, 2500.0)))
        assertEquals("Cutoff", AudioFader.detailText(lowCut(80.0)))
        assertNull(AudioFader.detailText(gain(0.0)))
        // AudioChainControls.swift:18-28, and :313 for which faders have a centre line.
        assertEquals(listOf(0x2ED4B0, 0xFAB34A, 0x73B5FA, 0xBA87F7, 0x57A8FF, 0xFF7080), AudioEffectKind.all.map { AudioFader.stripColor(it) })
        assertEquals(listOf(false, false, false, false, true, true), AudioEffectKind.all.map { AudioFader.isBipolar(it) })
    }

    // Editing the chain.

    private val a = gain(1.0, "A"); private val b = gain(2.0, "B"); private val c = gain(3.0, "C"); private val d = gain(4.0, "D")
    private fun ids(effects: List<AudioEffect>?) = effects?.joinToString("") { it.id }

    @Test fun aNewStageGoesLastWithItsStartingSettings() {
        // TrackEditors.swift:311.
        val added = AudioChainEditing.add(listOf(a, b), AudioEffectKind.HighPass)
        assertEquals(listOf(a, b), added.take(2))
        assertEquals(AudioEffect.make(AudioEffectKind.HighPass, added[2].id), added[2])
        // The same kind may be added again: each is its own stage.
        val twice = AudioChainEditing.add(added, AudioEffectKind.HighPass)
        assertFalse(twice[2].id == twice[3].id)
    }

    @Test fun aDroppedStageTakesThePlaceOfTheOneItIsDroppedOn() {
        // AudioChainControls.swift:144-150.
        val chain = listOf(a, b, c, d)
        assertEquals("BCAD", ids(AudioChainEditing.move(chain, "A", "C")))
        assertEquals("DABC", ids(AudioChainEditing.move(chain, "D", "A")))
        assertEquals("BACD", ids(AudioChainEditing.move(chain, "A", "B")))
        assertEquals("ACBD", ids(AudioChainEditing.move(chain, "C", "B")))
        assertNull(AudioChainEditing.move(chain, "B", "B"))
        assertNull(AudioChainEditing.move(chain, "B", "Z"))
        assertNull(AudioChainEditing.move(chain, "Z", "B"))
        // Settings travel with their stage.
        assertEquals(listOf(b, c, a, d), AudioChainEditing.move(chain, "A", "C"))
    }

    @Test fun earlierAndLaterSwapNeighbors() {
        // AudioChainControls.swift:131-133, 437-440.
        val chain = listOf(a, b, c)
        assertEquals("BAC", ids(AudioChainEditing.shift(chain, 0, 1)))
        assertEquals("ACB", ids(AudioChainEditing.shift(chain, 2, -1)))
        assertNull(AudioChainEditing.shift(chain, 0, -1))
        assertNull(AudioChainEditing.shift(chain, 2, 1))
        assertFalse(AudioChainEditing.canShift(chain, 0, -1))
        assertTrue(AudioChainEditing.canShift(chain, 1, -1))
        assertFalse(AudioChainEditing.canShift(listOf(a), 0, 1))
    }

    @Test fun removingSelectsTheStageThatTakesItsPlace() {
        // AudioChainControls.swift:134-137.
        val chain = listOf(a, b, c)
        assertEquals(listOf(a, c) to "C", AudioChainEditing.remove(chain, 1))
        assertEquals(listOf(a, b) to "B", AudioChainEditing.remove(chain, 2))
        assertEquals(listOf(b, c) to "B", AudioChainEditing.remove(chain, 0))
        assertEquals(emptyList<AudioEffect>() to null, AudioChainEditing.remove(listOf(a), 0))
    }

    @Test fun replacingBypassingAndResettingKeepTheStage() {
        // AudioChainControls.swift:140-143.
        assertEquals(listOf(a, b.copy(amount = 9.0), c), AudioChainEditing.replace(listOf(a, b, c), b.copy(amount = 9.0)))
        assertNull(AudioChainEditing.replace(listOf(a, c), b))
        // AudioChainControls.swift:205.
        assertFalse(AudioChainEditing.toggled(a).enabled)
        assertEquals(a, AudioChainEditing.toggled(AudioChainEditing.toggled(a)))
        // AudioChainControls.swift:442: back to the starting settings; on or bypassed and the chosen model stay.
        val changed = AudioEffect.make(AudioEffectKind.VoiceIsolation, "N").copy(amount = 0.4, enabled = false, model = AudioModel.DeepFilter)
        assertEquals(AudioEffect("N", AudioEffectKind.VoiceIsolation, false, 1.0, 1800.0, 1, AudioModel.DeepFilter), AudioChainEditing.reset(changed))
        assertEquals(AudioEffect.make(AudioEffectKind.HighPass, "H"), AudioChainEditing.reset(AudioEffect("H", AudioEffectKind.HighPass, true, 3.0, 310.0)))
        assertEquals(AudioEffect.make(AudioEffectKind.Equalizer, "E"), AudioChainEditing.reset(AudioEffect("E", AudioEffectKind.Equalizer, true, -9.0, 5000.0)))
    }

    @Test fun whichStageIsSelected() {
        // AudioChainControls.swift:67.
        assertEquals(b, AudioChainEditing.selected(listOf(a, b), "B"))
        assertEquals(a, AudioChainEditing.selected(listOf(a, b), null))
        assertEquals(a, AudioChainEditing.selected(listOf(a, b), "Z"))
        assertNull(AudioChainEditing.selected(emptyList(), "A"))
        // AudioChainControls.swift:122-125.
        assertEquals("C", AudioChainEditing.selection(listOf(a, b), listOf(a, b, c), "A"))
        assertEquals("A", AudioChainEditing.selection(listOf(a, b), listOf(a), "B"))
        assertEquals("B", AudioChainEditing.selection(listOf(a, b), listOf(b, a), "B"))
        assertNull(AudioChainEditing.selection(listOf(a), emptyList(), "A"))
    }

    @Test fun aSelectionWithDifferentChainsIsMixed() {
        // TrackEditors.swift:213-219.
        val first = listOf(a, b)
        assertEquals(first, AudioChainEditing.opening(listOf(first, listOf(c)), unprocessed = false).effects)
        assertTrue(AudioChainEditing.opening(listOf(first, listOf(c)), unprocessed = false).mixed)
        assertFalse(AudioChainEditing.opening(listOf(first, listOf(a, b)), unprocessed = false).mixed)
        assertTrue(AudioChainEditing.opening(listOf(first), unprocessed = true).mixed)
        val untouched = AudioChainEditing.opening(emptyList(), unprocessed = true)
        assertFalse(untouched.mixed)
        assertEquals(emptyList<AudioEffect>(), untouched.effects)
    }

    // Listening with and without the chain, TrackEditors.swift:174-185, 221-233, 362-394.

    @Test fun bypassPlaysTheSelectionWithoutTheChainAndAnyChangeSwitchesItOff() {
        val listening = AudioChainComparison(listOf(a, b))
        assertEquals(listOf(a, b), listening.playing)
        val bypassed = listening.togglingBypass()
        assertTrue(bypassed.bypass)
        assertEquals(emptyList<AudioEffect>(), bypassed.playing)
        assertEquals(listOf(a, b), bypassed.effects)
        assertEquals(listening, bypassed.togglingBypass())
        val changed = bypassed.changing(listOf(a))
        assertFalse(changed.bypass)
        assertEquals(listOf(a), changed.playing)
    }

    @Test fun levelsAreMatchedUnlessAnOutputGainIsOn() {
        val measured = AudioListeningLevels.Gains(original = 1f, processed = 0.5f)
        val low = AudioEffect.make(AudioEffectKind.HighPass)
        val matched = AudioChainComparison(listOf(low), gains = measured)
        assertTrue(matched.matching)
        assertTrue(matched.needsMeasuring)
        assertEquals(0.5f, matched.listeningVolume, 0f)
        assertEquals(1f, matched.togglingBypass().listeningVolume, 0f)
        assertFalse(matched.togglingBypass().needsMeasuring)
        // OfflineAudioPipeline.swift:261-263: an enabled output gain is heard as it is.
        val withGain = matched.changing(listOf(low, a))
        assertFalse(withGain.allowsMatching)
        assertFalse(withGain.matching)
        assertFalse(withGain.needsMeasuring)
        assertEquals(1f, withGain.listeningVolume, 0f)
        assertTrue(withGain.matchListeningLevels)
        // Bypassed, the gain no longer suspends matching.
        assertTrue(withGain.changing(listOf(low, a.copy(enabled = false))).matching)
        // Nothing enabled: nothing to measure.
        assertFalse(AudioChainComparison(listOf(low.copy(enabled = false))).needsMeasuring)
        assertFalse(AudioChainComparison().needsMeasuring)
        // TrackEditors.swift:350-353: the switch forgets what was measured.
        val off = matched.matching(false)
        assertEquals(AudioListeningLevels.Gains(1f, 1f), off.gains)
        assertEquals(1f, off.listeningVolume, 0f)
        assertEquals(AudioChainComparison(listOf(low)), off.matching(true))
    }
}
