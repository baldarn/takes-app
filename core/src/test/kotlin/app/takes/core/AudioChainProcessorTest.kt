package app.takes.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The filters of the "Enhance audio" chain against the original's own tests
 * (Tests/CoreTests.swift:161-178, Tests/DeepFilterTests.swift:187-214) and
 * its formulas (YouRec/AudioDSP.swift:13-29).
 */
class AudioChainProcessorTest {
    private fun gain(db: Double, enabled: Boolean = true) = AudioEffect(kind = AudioEffectKind.Gain, enabled = enabled, amount = db)
    private fun lowCut(hz: Double, enabled: Boolean = true) = AudioEffect(kind = AudioEffectKind.HighPass, enabled = enabled, frequency = hz)
    private fun eq(db: Double, hz: Double) = AudioEffect(kind = AudioEffectKind.Equalizer, amount = db, frequency = hz)

    private fun db(ratio: Double) = 20 * log10(max(1e-12, ratio))
    private fun rms(x: FloatArray) = sqrt(x.sumOf { it.toDouble() * it.toDouble() } / max(1, x.size))
    private fun same(a: FloatArray, b: FloatArray) = a.size == b.size && a.indices.all { a[it].toRawBits() == b[it].toRawBits() }

    /** DeepFilterTests.swift:187-196: two seconds of 40 Hz, 1.8 kHz and 9 kHz together, each at 0.03. */
    private val tone = FloatArray(96000) { i ->
        val t = i / 48000.0
        listOf(40.0, 1800.0, 9000.0).fold(0.0) { sum, hz -> sum + 0.03 * sin(2 * PI * hz * t) }.toFloat()
    }

    /** DeepFilterTests.swift:205-209: how much of [hz] is in the middle second of [x]. */
    private fun amplitude(x: FloatArray, hz: Double): Double {
        var re = 0.0; var im = 0.0
        for (i in 24000 until 72000) { val a = 2 * PI * hz * i / 48000; re += x[i] * cos(a); im += x[i] * sin(a) }
        return hypot(re, im) / 24000
    }

    private fun assertCoefficients(expected: BiquadCoefficients, actual: BiquadCoefficients) {
        assertEquals(expected.b0, actual.b0, 1e-14); assertEquals(expected.b1, actual.b1, 1e-14); assertEquals(expected.b2, actual.b2, 1e-14)
        assertEquals(expected.a1, actual.a1, 1e-14); assertEquals(expected.a2, actual.a2, 1e-14)
    }

    // Coefficients. The expected numbers are AudioDSP.swift:13-29 worked out in double precision outside this code.

    @Test fun outputGainIsAPlainMultiplier() {
        // AudioDSP.swift:18: b0 = 10^(dB/20); everything else stays at the pass-through values of AudioDSP.swift:7-8.
        assertCoefficients(BiquadCoefficients(b0 = 0.5011872336272722), BiquadCoefficients.of(gain(-6.0)))
        assertCoefficients(BiquadCoefficients(b0 = 7.943282347242816), BiquadCoefficients.of(gain(18.0)))
        assertEquals(BiquadCoefficients(1.0, 0.0, 0.0, 0.0, 0.0), BiquadCoefficients.of(gain(0.0)))
    }

    @Test fun lowCutIsAButterworthHighPass() {
        // AudioDSP.swift:19-22, at the default 80 Hz cutoff (Project.swift:136) and at 200 Hz.
        assertCoefficients(
            BiquadCoefficients(0.9926225427561189, -1.9852450855122379, 0.9926225427561189, -1.9851906578962613, 0.9852995131282146),
            BiquadCoefficients.of(AudioEffect.make(AudioEffectKind.HighPass)),
        )
        assertCoefficients(
            BiquadCoefficients(0.9816582684032611, -1.9633165368065222, 0.9816582684032611, -1.9629800893893394, 0.9636529842237054),
            BiquadCoefficients.of(lowCut(200.0)),
        )
    }

    @Test fun voiceEQIsAPeakingBand() {
        // AudioDSP.swift:23-28.
        assertCoefficients(
            BiquadCoefficients(1.0759646295693013, -1.7963051624268662, 0.7713828919297383, -1.7963051624268662, 0.8473475214990397),
            BiquadCoefficients.of(eq(6.0, 1800.0)),
        )
        // At a quarter of the sample rate cos(w) is zero: the middle terms vanish.
        assertCoefficients(
            BiquadCoefficients(2.6568505148299555, 0.0, -0.8800114159967034, 0.0, 0.7768390988332521),
            BiquadCoefficients.of(eq(24.0, 12000.0)),
        )
        // With no boost the band is transparent: numerator and denominator are the same.
        val flat = BiquadCoefficients.of(AudioEffect.make(AudioEffectKind.Equalizer))
        assertEquals(1.0, flat.b0, 0.0); assertEquals(flat.a1, flat.b1, 0.0); assertEquals(flat.a2, flat.b2, 0.0)
    }

    @Test fun theFrequencyIsHeldBetween20HzAnd45PercentOfTheSampleRate() {
        // AudioDSP.swift:13.
        assertEquals(BiquadCoefficients.of(eq(-12.0, 21600.0)), BiquadCoefficients.of(eq(-12.0, 30000.0)))
        assertCoefficients(
            BiquadCoefficients(0.8235496862547871, 1.4538980872511709, 0.7051692303820911, 1.4538980872511709, 0.5287189166368784),
            BiquadCoefficients.of(eq(-12.0, 30000.0)),
        )
        assertEquals(BiquadCoefficients.of(lowCut(20.0)), BiquadCoefficients.of(lowCut(3.0)))
        assertEquals(BiquadCoefficients.of(lowCut(44100 * 0.45), 44100.0), BiquadCoefficients.of(lowCut(20000.0), 44100.0))
    }

    @Test fun aModelStageIsNotAFilter() {
        // AudioDSP.swift:17.
        for (kind in listOf(AudioEffectKind.VoiceIsolation, AudioEffectKind.StudioVoice, AudioEffectKind.StudioRestoration, AudioEffectKind.Unavailable("futureDereverbV9"))) {
            try { AudioChainProcessor(listOf(AudioEffect(kind = kind))); fail("${kind.rawValue} was accepted") } catch (_: IllegalArgumentException) {}
            // Bypassed, it is left out like any other stage.
            assertEquals(0.25f, AudioChainProcessor(listOf(AudioEffect(kind = kind, enabled = false))).sample(0.25f, 0), 0f)
        }
    }

    // The original's processor tests, CoreTests.swift:161-178.

    @Test fun bypassIsBitExact() {
        // CoreTests.swift:161-165.
        val bypass = AudioChainProcessor(listOf(lowCut(1800.0, enabled = false)))
        for (index in 0 until 5000) {
            val input = (sin(index * 0.17) * 0.8).toFloat()
            assertEquals(input.toRawBits(), bypass.sample(input, 0).toRawBits())
        }
    }

    @Test fun repeatedStagesCompoundIndependently() {
        // CoreTests.swift:166-168.
        val chain = AudioChainProcessor(listOf(gain(-6.0), gain(3.0)))
        val expected = (0.5 * 10.0.pow(-3.0 / 20)).toFloat()
        assertTrue(abs(chain.sample(0.5f, 0) - expected) < 0.000001f)
    }

    @Test fun lowCutRemovesDCAndChannelsKeepTheirOwnState() {
        // CoreTests.swift:169-173.
        val dc = AudioChainProcessor(listOf(AudioEffect.make(AudioEffectKind.HighPass)))
        var tail = 0f
        repeat(48000) { tail = dc.sample(0.25f, 0) }
        assertTrue(abs(tail) < 0.000001f)
        assertEquals(0f, dc.sample(0f, 1), 0f)
    }

    @Test fun theMostExtremeEQStaysStable() {
        // CoreTests.swift:174-178.
        val extreme = AudioChainProcessor(listOf(eq(24.0, 12000.0)))
        for (index in 0 until 96000) assertTrue(extreme.sample(sin(index.toDouble()).toFloat(), index % 2).isFinite())
    }

    // Measured on sine waves, DeepFilterTests.swift:199-214.

    @Test fun outputGainIsItsDecibels() {
        // DeepFilterTests.swift:199-202.
        for (amount in listOf(-18.0, -6.0, 0.0, 6.0, 18.0)) {
            val out = AudioChain.render(listOf(gain(amount)), tone)
            assertEquals(amount, db(rms(out) / rms(tone)), 0.0001)
        }
        // 0 dB multiplies by exactly one: the samples come back as they went in.
        assertTrue(same(tone, AudioChain.render(listOf(gain(0.0)), tone)))
    }

    @Test fun aDisabledGainIsBitExact() {
        // DeepFilterTests.swift:203-204.
        val out = AudioChain.render(listOf(gain(18.0, enabled = false)), tone)
        assertTrue(same(tone, out))
        assertNotSame(tone, out)
    }

    @Test fun lowCutRemovesRumbleAndKeepsTheSpeechBand() {
        // DeepFilterTests.swift:210-212.
        val out = AudioChain.render(listOf(lowCut(200.0)), tone)
        val cut = db(amplitude(out, 40.0) / amplitude(tone, 40.0))
        val pass = db(amplitude(out, 1800.0) / amplitude(tone, 1800.0))
        assertTrue("40 Hz at $cut dB", cut < -27)
        assertTrue("1.8 kHz at $pass dB", abs(pass) < 0.01)
        // The filter's own curve: 12 dB an octave, 3 dB down at the cutoff.
        assertEquals(-27.9667, cut, 0.01)
        assertEquals(-3.0103, db(BiquadCoefficients.of(lowCut(200.0)).response(200.0)), 0.0001)
        assertEquals(-3.0103, db(BiquadCoefficients.of(lowCut(80.0)).response(80.0)), 0.0001)
        assertEquals(-12.3046, db(BiquadCoefficients.of(lowCut(80.0)).response(40.0)), 0.0001)
    }

    @Test fun voiceEQDeliversItsGainAtItsFrequency() {
        // DeepFilterTests.swift:213-214.
        val out = AudioChain.render(listOf(eq(6.0, 1800.0)), tone)
        assertEquals(6.0, db(amplitude(out, 1800.0) / amplitude(tone, 1800.0)), 0.01)
        // Away from the band it leaves the sound nearly alone.
        assertEquals(0.0032, db(amplitude(out, 40.0) / amplitude(tone, 40.0)), 0.001)
        assertEquals(0.2083, db(amplitude(out, 9000.0) / amplitude(tone, 9000.0)), 0.001)
        // A cut is the mirror image of the boost.
        assertEquals(-6.0, db(BiquadCoefficients.of(eq(-6.0, 1800.0)).response(1800.0)), 1e-9)
        assertEquals(24.0, db(BiquadCoefficients.of(eq(24.0, 12000.0)).response(12000.0)), 1e-9)
    }

    @Test fun theMeasuredToneMatchesTheCurveOfTheCoefficients() {
        for ((effect, hz) in listOf(lowCut(200.0) to 100.0, lowCut(80.0) to 60.0, eq(-9.0, 3000.0) to 2000.0, eq(12.0, 500.0) to 700.0)) {
            val sine = FloatArray(96000) { (0.25 * sin(2 * PI * hz * it / 48000)).toFloat() }
            val out = AudioChain.render(listOf(effect), sine)
            assertEquals(db(BiquadCoefficients.of(effect).response(hz)), db(amplitude(out, hz) / amplitude(sine, hz)), 0.001)
        }
    }

    // Blocks, channels and the peak.

    @Test fun feedingInBlocksIsTheSameAsFeedingWhole() {
        val chain = listOf(lowCut(120.0), eq(4.0, 2500.0), gain(-3.0))
        val whole = tone.copyOf().also { AudioChainProcessor(chain, channels = 1).process(it, 0) }
        val blocks = tone.copyOf()
        val processor = AudioChainProcessor(chain, channels = 1)
        var offset = 0
        var size = 1
        while (offset < blocks.size) {
            val count = minOf(size, blocks.size - offset)
            processor.process(blocks, 0, offset, count)
            offset += count
            size = size * 3 + 7
        }
        assertTrue(same(whole, blocks))
    }

    @Test fun interleavedFramesAreTheChannelsSideBySide() {
        val chain = listOf(lowCut(300.0), eq(-5.0, 1000.0))
        val left = FloatArray(4800) { sin(it * 0.05).toFloat() }
        val right = FloatArray(4800) { cos(it * 0.31).toFloat() * 0.5f }
        val separate = listOf(left, right).map { samples -> samples.copyOf().also { AudioChainProcessor(chain, channels = 1).process(it, 0) } }
        val interleaved = FloatArray(9600) { if (it % 2 == 0) left[it / 2] else right[it / 2] }
        AudioChainProcessor(chain).processInterleaved(interleaved)
        for (frame in 0 until 4800) {
            assertEquals(separate[0][frame], interleaved[frame * 2], 0f)
            assertEquals(separate[1][frame], interleaved[frame * 2 + 1], 0f)
        }
        // One channel's sound never reaches the other, and the chain run stage by stage agrees to the last digit or so.
        val rendered = AudioChain.render(chain, listOf(left, right)).channels
        assertTrue(same(AudioChain.render(chain, left), rendered[0]))
        assertArrayEquals(separate[0], rendered[0], 1e-6f)
        assertArrayEquals(separate[1], rendered[1], 1e-6f)
    }

    @Test fun thePeakIsTheLoudestSampleAndWhatIsNotANumberBecomesSilence() {
        // AudioDSP.swift:43-49.
        val processor = AudioChainProcessor(listOf(gain(6.0)))
        processor.sample(0.1f, 0); processor.sample(-0.4f, 1); processor.sample(0.2f, 0)
        assertEquals((0.4 * 10.0.pow(6.0 / 20)).toFloat(), processor.peak, 1e-6f)
        val silent = AudioChainProcessor(emptyList())
        assertEquals(0f, silent.sample(Float.NaN, 0), 0f)
        assertEquals(0f, silent.sample(Float.POSITIVE_INFINITY, 0), 0f)
        assertEquals(0f, silent.peak, 0f)
        assertEquals(0.75f, AudioChain.peak(listOf(floatArrayOf(0.1f, -0.75f), floatArrayOf(0.5f, 0f))), 0f)
    }

    // The whole chain, models included.

    /** Stands in for a model: halves the sound, blended by the stage's amount. */
    private class Halving(val known: Set<AudioModel> = setOf(AudioModel.RNNoise)) : AudioModelStage {
        val seen = mutableListOf<AudioEffect>()
        override fun supports(effect: AudioEffect) = effect.model in known
        override fun process(effect: AudioEffect, channels: List<FloatArray>, progress: (Double) -> Unit): List<FloatArray> {
            seen += effect
            progress(1.0)
            return channels.map { dry -> AudioChain.mix(dry, FloatArray(dry.size) { dry[it] * 0.5f }, effect.amount.toFloat()) }
        }
    }

    @Test fun stagesRunInOrderEachOnTheResultOfTheOneBefore() {
        // DeepFilterTests.swift:234: a model first, then low-cut, EQ and gain.
        val chain = listOf(AudioEffect.make(AudioEffectKind.VoiceIsolation), lowCut(80.0), eq(3.0, 1800.0), gain(-6.0))
        val models = Halving()
        val steps = mutableListOf<Double>()
        val out = AudioChain.render(chain, listOf(tone), models) { steps += it }
        // Each filter stage starts with no memory, so one processor over the three is the same thing.
        val expected = FloatArray(tone.size) { tone[it] + 1f * (tone[it] * 0.5f - tone[it]) }
        for (effect in chain.drop(1)) AudioChainProcessor(listOf(effect), channels = 1).process(expected, 0)
        assertTrue(same(expected, out.channels[0]))
        assertEquals(AudioChain.peak(listOf(expected)), out.peak, 0f)
        assertEquals(listOf(chain[0]), models.seen)
        assertEquals(listOf(0.25, 0.5, 0.75, 1.0, 1.0), steps)
    }

    @Test fun reorderingChangesWhatAModelHears() {
        val heard = mutableListOf<Float>()
        val listening = object : AudioModelStage {
            override fun supports(effect: AudioEffect) = true
            override fun process(effect: AudioEffect, channels: List<FloatArray>, progress: (Double) -> Unit): List<FloatArray> {
                heard += AudioChain.peak(channels)
                return channels.map { it.copyOf() }
            }
        }
        val model = AudioEffect.make(AudioEffectKind.VoiceIsolation)
        val input = floatArrayOf(0.5f, -0.25f)
        AudioChain.render(listOf(model, gain(-6.0)), listOf(input), listening)
        AudioChain.render(listOf(gain(-6.0), model), listOf(input), listening)
        assertEquals(0.5f, heard[0], 0f)
        assertEquals((0.5 * 10.0.pow(-6.0 / 20)).toFloat(), heard[1], 1e-7f)
    }

    @Test fun reorderingFiltersWithMemoryChangesTheSamples() {
        // A clipped model between two filters is not the same as after both.
        val clipping = object : AudioModelStage {
            override fun supports(effect: AudioEffect) = true
            override fun process(effect: AudioEffect, channels: List<FloatArray>, progress: (Double) -> Unit) =
                channels.map { samples -> FloatArray(samples.size) { samples[it].coerceIn(-0.02f, 0.02f) } }
        }
        val model = AudioEffect.make(AudioEffectKind.StudioRestoration)
        val first = AudioChain.render(listOf(model, lowCut(200.0), gain(6.0)), listOf(tone), clipping)
        val last = AudioChain.render(listOf(lowCut(200.0), gain(6.0), model), listOf(tone), clipping)
        assertFalse(same(first.channels[0], last.channels[0]))
        assertEquals(0.02f, last.peak, 0f)
        assertTrue(first.peak > 0.02f)
    }

    @Test fun aModelTurnedDownToNothingIsSkippedAndNeedsNoModel() {
        // OfflineAudioPipeline.swift:44, CoreTests.swift:402-404.
        val zero = AudioEffect.make(AudioEffectKind.VoiceIsolation).copy(amount = 0.0, model = null)
        assertEquals(emptyList<AudioEffect>(), AudioChain.active(listOf(zero, gain(3.0, enabled = false))))
        assertTrue(same(tone, AudioChain.render(listOf(zero), tone)))
        // A filter at its neutral setting still runs, as in the original.
        val neutral = gain(0.0)
        assertEquals(listOf(neutral), AudioChain.active(listOf(neutral)))
    }

    @Test fun anEnabledStageThatCannotBePlayedStopsTheChainBeforeAnyWork() {
        val models = Halving()
        fun refused(effects: List<AudioEffect>, with: AudioModelStage? = models) = try {
            AudioChain.render(effects, listOf(tone), with); false
        } catch (error: AudioChainException) {
            error.reason == AudioChainException.Reason.UnavailableModel
        }
        val isolation = AudioEffect.make(AudioEffectKind.VoiceIsolation)
        // CoreTests.swift:420-424: a model stage saved without its model.
        assertTrue(refused(listOf(isolation.copy(model = null))))
        // CoreTests.swift:425-426: bypassed, it no longer matters.
        assertFalse(refused(listOf(isolation.copy(model = null, enabled = false))))
        // No models at all, a model this device lacks, a kind or version from the future.
        assertTrue(refused(listOf(isolation), with = null))
        assertTrue(refused(listOf(gain(-6.0), isolation.copy(model = AudioModel.DeepFilter))))
        assertTrue(refused(listOf(AudioEffect(kind = AudioEffectKind.Unavailable("futureDereverbV9"), amount = 42.0))))
        assertTrue(refused(listOf(gain(1.0).copy(implementationVersion = 2))))
        assertEquals(emptyList<AudioEffect>(), models.seen)
        assertTrue(AudioChain.playable(isolation, models))
        assertFalse(AudioChain.playable(isolation.copy(model = AudioModel.DeepFilter), models))
        assertTrue(AudioChain.playable(isolation.copy(model = AudioModel.DeepFilter), Halving(setOf(AudioModel.DeepFilter))))
        assertTrue(AudioChain.playable(gain(3.0)))
    }

    @Test fun aModelMustReturnWhatItWasGiven() {
        val short = object : AudioModelStage {
            override fun supports(effect: AudioEffect) = true
            override fun process(effect: AudioEffect, channels: List<FloatArray>, progress: (Double) -> Unit) = channels.map { it.copyOf(it.size - 1) }
        }
        for (attempt in listOf<() -> Unit>(
            { AudioChain.render(listOf(AudioEffect.make(AudioEffectKind.StudioVoice)), listOf(tone), short) },
            { AudioChain.render(emptyList(), listOf(FloatArray(4), FloatArray(5))) },
            { AudioChain.render(emptyList(), emptyList<FloatArray>()) },
        )) {
            try { attempt(); fail() } catch (error: AudioChainException) { assertEquals(AudioChainException.Reason.InvalidFormat, error.reason) }
        }
    }

    @Test fun amountIsAnExactBlendOfTheRecordingAndTheModel() {
        // OfflineAudioPipeline.swift:164; DeepFilterTests.swift:227-229: 35% is dry + 0.35 × (wet − dry).
        val dry = floatArrayOf(0.5f, -0.25f, 0f, 1f)
        val wet = floatArrayOf(0.1f, 0.25f, 0.3f, -1f)
        assertTrue(same(FloatArray(4) { dry[it] + 0.35f * (wet[it] - dry[it]) }, AudioChain.mix(dry, wet, 0.35f)))
        assertTrue(same(dry, AudioChain.mix(dry, wet, 0f)))
        assertArrayEquals(wet, AudioChain.mix(dry, wet, 1f), 1e-7f)
        val studio = AudioEffect.make(AudioEffectKind.StudioVoice)
        val out = AudioChain.render(listOf(studio), dry, Halving(setOf(AudioModel.Lava)))
        assertTrue(same(FloatArray(4) { dry[it] + 0.35f * (dry[it] * 0.5f - dry[it]) }, out))
    }

    @Test fun theInputIsNeverChanged() {
        val input = tone.copyOf()
        AudioChain.render(listOf(lowCut(200.0), gain(6.0)), listOf(input))
        assertTrue(same(tone, input))
    }

    @Test fun bypassedStagesDoNotChangeTheNameOfTheSound() {
        // TrackRenderer.swift:27-30.
        val low = lowCut(100.0); val level = gain(-3.0)
        val name = AudioChain.signature(listOf(low, level))
        assertEquals(name, AudioChain.signature(listOf(low, eq(6.0, 1800.0).copy(enabled = false), level)))
        assertFalse(name == AudioChain.signature(listOf(level, low)))
        assertFalse(name == AudioChain.signature(listOf(low, level.copy(amount = -3.1))))
        assertFalse(name == AudioChain.signature(listOf(low)))
        assertEquals(AudioChain.signature(emptyList()), AudioChain.signature(listOf(low.copy(enabled = false))))
    }
}
