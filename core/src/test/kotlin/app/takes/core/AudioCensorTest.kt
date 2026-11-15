package app.takes.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Censored sound against the original app: YouRec/AudioCensor.swift, the
 * edits of YouRec/TrackTimeline.swift and the censor tests of
 * Tests/TrackMediaTests.swift:649-783. Those tests work on a 4 s sound at
 * 48 kHz censored from 1 s to 2 s; so do these.
 */
class AudioCensorTest {
    private val tiny = 1e-9
    private val rate = 48_000.0
    private val settings = AudioCensorSettings()
    private val item = EditInterval.of(0.0, 4.0)
    private val effect = "EFFECT"

    private fun censored(range: EditInterval? = EditInterval.of(1.0, 2.0)) = AudioCensor.censored(emptyList(), item, settings, range, effect)

    private fun sound(id: String, regions: List<AudioCensorRegion>, start: Double = 0.0, volumeDB: Double = 0.0, muted: Boolean = false) =
        AudioCensorItem(id, EditTime.of(start), regions, volumeDB, muted)

    private fun spans(regions: List<AudioCensorRegion>, start: Double = 0.0): List<AudioCensorSpan> =
        sound("a", regions, start).let { AudioCensor.spans(listOf(it), it) }

    // MARK: Tone

    @Test fun theToneIs1kHzAt18DecibelsBelowFullScale() {
        // AudioCensor.swift:7-10, 12.
        assertEquals(1000.0, settings.frequency, 0.0)
        assertEquals(-18.0, settings.levelDB, 0.0)
        assertEquals(CensorWaveform.Sine, settings.waveform)
        assertEquals(CensorEnvelope.Short, settings.envelope)
        assertEquals(0.12589254117941673, settings.amplitude, tiny)
        // AudioCensor.swift:11: 100…4000 Hz, −36…−6 dB.
        assertTrue(settings.valid)
        assertTrue(settings.copy(frequency = 100.0, levelDB = -36.0).valid)
        assertTrue(settings.copy(frequency = 4000.0, levelDB = -6.0).valid)
        assertFalse(settings.copy(frequency = 99.0).valid)
        assertFalse(settings.copy(frequency = 4001.0).valid)
        assertFalse(settings.copy(levelDB = -5.5).valid)
        assertFalse(settings.copy(levelDB = -36.5).valid)
        assertFalse(settings.copy(frequency = Double.NaN).valid)
    }

    @Test fun theSineStartsAtZeroAndTakes48SamplesPerCycle() {
        // sin(2π · frac(time · frequency)), AudioCensor.swift:18-19.
        fun sample(frame: Int) = settings.oscillator(frame / rate, rate)
        assertEquals(0.0, sample(0), tiny)
        assertEquals(0.5, sample(4), tiny)
        assertEquals(sqrt(3.0) / 2, sample(8), tiny)
        assertEquals(1.0, sample(12), tiny)
        assertEquals(0.0, sample(24), tiny)
        assertEquals(-1.0, sample(36), tiny)
        for (frame in 0 until 480) assertEquals(sin(2 * PI * frame / 48), sample(frame), tiny)
        // The phase comes from the time alone: an hour in, the same cycle.
        assertEquals(sample(4), settings.oscillator(3600 + 4 / rate, rate), 1e-7)
        // Before the phase origin the wave simply runs backwards.
        assertEquals(-0.5, sample(-4), tiny)
    }

    @Test fun theSquareIsRoundedAtItsSteps() {
        // AudioCensor.swift:20-28: ±1 with a polynomial step dt = frequency / sampleRate of a cycle wide.
        val square = settings.copy(waveform = CensorWaveform.Square)
        fun sample(frame: Int) = square.oscillator(frame / rate, rate)
        assertEquals(0.0, sample(0), tiny) // halfway up the rising step
        assertEquals(1.0, sample(6), tiny)
        assertEquals(1.0, sample(12), tiny)
        assertEquals(1.0, sample(18), tiny)
        assertEquals(0.0, sample(24), tiny) // halfway down the falling step
        assertEquals(-1.0, sample(30), tiny)
        assertEquals(-1.0, sample(36), tiny)
        // Inside the step, x = t / dt: 1 + (2x − x² − 1). At 250 Hz the step is one sample wide; half a sample in, x = 0.5.
        val slow = square.copy(frequency = 250.0)
        assertEquals(0.75, slow.oscillator(0.5 / rate, rate), tiny)
        assertEquals(-0.75, slow.oscillator(-0.5 / rate, rate), tiny)
        assertEquals(1.0, slow.oscillator(48 / rate, rate), tiny)
        val coarse = square.copy(frequency = 4000.0) // 12 samples a cycle, dt = 1/12
        assertEquals(0.0, coarse.oscillator(0.0, rate), tiny)
        assertEquals(1.0, coarse.oscillator(3 / rate, rate), tiny)
    }

    @Test fun theTriangleStopsAtItsSeventeenthHarmonic() {
        // AudioCensor.swift:30-37: odd harmonics ±1/n² up to min(17, 0.45 · sampleRate / frequency), divided by their sum.
        val triangle = settings.copy(waveform = CensorWaveform.Triangle)
        fun sample(frame: Int) = triangle.oscillator(frame / rate, rate)
        assertEquals(0.0, sample(0), tiny)
        assertEquals(1.0, sample(12), tiny) // every harmonic peaks together
        assertEquals(0.0, sample(24), tiny)
        assertEquals(-1.0, sample(36), tiny)
        val harmonics = listOf(1, 3, 5, 7, 9, 11, 13, 15, 17)
        val scale = harmonics.sumOf { 1.0 / (it * it) }
        val expected = harmonics.sumOf { (if ((it / 2) % 2 == 0) 1.0 else -1.0) / (it * it) * sin(it * 2 * PI * 5 / 48) } / scale
        assertEquals(expected, sample(5), tiny)
        // Close to the straight line of a triangle wave: 5/12 of the way up.
        assertEquals(5.0 / 12, sample(5), 0.03)
        // At 4 kHz only harmonics up to 0.45 · 48000 / 4000 = 5.4 fit: 1, 3 and 5.
        val high = triangle.copy(frequency = 4000.0)
        val few = listOf(1, 3, 5)
        val highExpected = few.sumOf { (if ((it / 2) % 2 == 0) 1.0 else -1.0) / (it * it) * sin(it * 2 * PI / 12) } / few.sumOf { 1.0 / (it * it) }
        assertEquals(highExpected, high.oscillator(1 / rate, rate), tiny)
    }

    @Test fun everyWaveformHasABoundedUsefulPeak() {
        // Tests/TrackMediaTests.swift:779-783.
        for (waveform in CensorWaveform.entries) {
            val value = settings.copy(waveform = waveform)
            val peak = (0 until 4800).maxOf { abs(value.oscillator(it / rate, rate)) }
            assertTrue("$waveform $peak", peak <= 1.000001 && peak > 0.6)
        }
    }

    // MARK: Envelopes

    @Test fun theToneRisesAndFallsSmoothlyOver8Milliseconds() {
        // AudioCensor.swift:39-44: smoothstep over min(duration / 2, 0.008), or 0.06 when soft.
        assertEquals(0.0, settings.envelopeValue(0.0, 1.0), 0.0)
        assertEquals(0.15625, settings.envelopeValue(0.002, 1.0), tiny) // x = 0.25: x²(3 − 2x)
        assertEquals(0.5, settings.envelopeValue(0.004, 1.0), tiny)
        assertEquals(1.0, settings.envelopeValue(0.008, 1.0), tiny)
        assertEquals(1.0, settings.envelopeValue(0.5, 1.0), 0.0)
        assertEquals(0.5, settings.envelopeValue(0.996, 1.0), tiny)
        assertEquals(0.0, settings.envelopeValue(1.0, 1.0), 0.0)
        assertEquals(0.0, settings.envelopeValue(-0.001, 1.0), 0.0)
        val soft = settings.copy(envelope = CensorEnvelope.Soft)
        assertEquals(0.5, soft.envelopeValue(0.03, 1.0), tiny)
        assertEquals(1.0, soft.envelopeValue(0.06, 1.0), tiny)
        assertEquals(0.5, soft.envelopeValue(0.97, 1.0), tiny)
        // A censor shorter than two fades gives each half of itself.
        assertEquals(0.5, settings.envelopeValue(0.0025, 0.01), tiny)
        assertEquals(1.0, settings.envelopeValue(0.005, 0.01), tiny)
    }

    @Test fun thePulsedToneBeeps180MillisecondsEveryQuarterSecond() {
        // AudioCensor.swift:45-48.
        val pulse = settings.copy(envelope = CensorEnvelope.Pulse)
        assertEquals(1.0, pulse.envelopeValue(0.1, 2.0), tiny)
        assertEquals(0.5, pulse.envelopeValue(0.176, 2.0), 1e-6)
        assertEquals(0.0, pulse.envelopeValue(0.18, 2.0), tiny)
        assertEquals(0.0, pulse.envelopeValue(0.2, 2.0), 0.0)
        assertEquals(0.5, pulse.envelopeValue(0.254, 2.0), 1e-6)
        assertEquals(1.0, pulse.envelopeValue(0.35, 2.0), tiny)
        // Tests/TrackMediaTests.swift:766-769: frames 58320..<58800 of a censor starting at frame 48000 are silent.
        for (frame in 10_320 until 10_800) assertEquals(0.0, pulse.envelopeValue(frame / rate, 1.0), 0.0)
    }

    @Test fun theRecordedSoundDucksOver5Milliseconds() {
        // AudioCensor.swift:80-83.
        val span = AudioCensorSpan(1.0, 2.0, 1.0, 1.0, 2.0, settings, true)
        assertEquals(1.0, span.originalGain(0.99), 0.0)
        assertEquals(1.0, span.originalGain(0.995), tiny)
        assertEquals(0.5, span.originalGain(0.9975), 1e-9)
        assertEquals(0.0, span.originalGain(1.0), 0.0)
        assertEquals(0.0, span.originalGain(1.999), 0.0)
        assertEquals(0.0, span.originalGain(2.0), 0.0)
        assertEquals(0.5, span.originalGain(2.0025), 1e-9)
        assertEquals(1.0, span.originalGain(2.005), 1e-9)
        // AudioCensor.swift:84-88: no tone outside the span, or from a span that does not own it.
        assertEquals(0.0, span.tone(0.999, rate), 0.0)
        assertEquals(0.0, span.tone(2.0, rate), 0.0)
        assertEquals(0.0, span.copy(emitsTone = false).tone(1.5, rate), 0.0)
        assertEquals(settings.amplitude, span.tone(1.5 + 12 / rate, rate), 1e-9)
    }

    // MARK: Censoring

    @Test fun aCensorOverARangeKeepsTheRangesClock() {
        // AudioCensor.swift:95-96, 106-108; Tests/TrackMediaTests.swift:660-664.
        val region = censored().single()
        assertEquals(AudioCensorRegion(effect, EditInterval.of(1.0, 2.0), EditTime.of(1.0), settings, EditInterval.of(1.0, 2.0)), region)
        // Without a range the whole item is censored and the censor's own interval is its envelope.
        val whole = censored(null).single()
        assertEquals(AudioCensorRegion(effect, EditInterval.of(0.0, 4.0), EditTime.ZERO, settings, null), whole)
        // A range that misses the item changes nothing.
        assertEquals(emptyList<AudioCensorRegion>(), censored(EditInterval.of(5.0, 6.0)))
        val existing = censored()
        assertEquals(existing, AudioCensor.censored(existing, item, settings, EditInterval.of(4.0, 6.0)))
        // The item's place on the timeline is taken off: the same range on an item starting at 0.5.
        val later = AudioCensor.censored(emptyList(), EditInterval.of(0.5, 4.5), settings, EditInterval.of(1.0, 2.0), effect).single()
        assertEquals(EditInterval.of(0.5, 1.5), later.interval)
        assertEquals(EditTime.of(0.5), later.phaseOrigin)
    }

    @Test fun clipsBeginningInsideOneCensorShareItsClock() {
        // Tests/TrackMediaTests.swift:708-715: sounds at 0 s and 1 s, censored 0.5…3.5.
        val range = EditInterval.of(0.5, 3.5)
        val first = sound("a", AudioCensor.censored(emptyList(), EditInterval.of(0.0, 4.0), settings, range, effect))
        val second = sound("b", AudioCensor.censored(emptyList(), EditInterval.of(1.0, 5.0), settings, range, effect), start = 1.0)
        assertEquals(EditInterval.of(0.0, 2.5), second.censors.single().interval)
        assertEquals(EditInterval.of(-0.5, 2.5), second.censors.single().envelopeInterval)
        assertEquals(EditTime.of(-0.5), second.censors.single().phaseOrigin)
        val all = listOf(first, second)
        val spans = all.flatMap { AudioCensor.spans(all, it) }
        assertTrue(spans.isNotEmpty())
        for (span in spans) {
            assertEquals(0.5, span.phaseOrigin, 0.00001)
            assertEquals(0.5, span.envelopeStart, 0.00001)
            assertEquals(3.5, span.envelopeEnd, 0.00001)
        }
    }

    @Test fun censoringAgainCutsBackWhatWasThere() {
        // AudioCensor.swift:98-102.
        val retuned = settings.copy(frequency = 440.0)
        val result = AudioCensor.censored(censored(), item, retuned, EditInterval.of(1.5, 3.0), "SECOND")
        assertEquals(2, result.size)
        assertEquals(AudioCensorRegion(effect, EditInterval.of(1.0, 1.5), EditTime.of(1.0), settings, EditInterval.of(1.0, 1.5)), result[0])
        assertEquals(AudioCensorRegion("SECOND", EditInterval.of(1.5, 3.0), EditTime.of(1.5), retuned, EditInterval.of(1.5, 3.0)), result[1])
        // A censor in the middle of another leaves both of its sides, sorted.
        val middle = AudioCensor.censored(censored(), item, retuned, EditInterval.of(1.25, 1.5), "SECOND")
        assertEquals(listOf(EditInterval.of(1.0, 1.25), EditInterval.of(1.25, 1.5), EditInterval.of(1.5, 2.0)), middle.map { it.interval })
        assertEquals(listOf(effect, "SECOND", effect), middle.map { it.id })
        assertEquals(EditInterval.of(1.5, 2.0), middle[2].envelopeInterval)
        assertTrue(AudioCensor.valid(middle, item.duration))
        // Removing: no settings. Only the named censor when one is named.
        assertEquals(emptyList<AudioCensorRegion>(), AudioCensor.censored(censored(), item, null))
        assertEquals(censored(), AudioCensor.censored(censored(), item, null, removingOnly = "OTHER"))
        assertEquals(listOf(effect, effect), AudioCensor.censored(middle, item, null, removingOnly = "SECOND").map { it.id })
    }

    // MARK: Edits

    @Test fun splittingKeepsTheCensorOnBothHalves() {
        // AudioCensor.swift:58-65; Tests/TrackMediaTests.swift:670-672.
        val cut = EditTime.of(1.5)
        val left = AudioCensor.sliced(censored(), EditInterval(EditTime.ZERO, cut))
        val right = AudioCensor.sliced(censored(), EditInterval(cut, item.end))
        assertEquals(listOf(AudioCensorRegion(effect, EditInterval.of(1.0, 1.5), EditTime.of(1.0), settings, EditInterval.of(1.0, 2.0))), left)
        assertEquals(listOf(AudioCensorRegion(effect, EditInterval.of(0.0, 0.5), EditTime.of(-0.5), settings, EditInterval.of(-0.5, 0.5))), right)
        assertTrue(AudioCensor.valid(left, cut))
        assertTrue(AudioCensor.valid(right, item.end - cut))
        // Tests/TrackMediaTests.swift:673-675: removing it from the right half leaves the left one alone.
        assertEquals(emptyList<AudioCensorRegion>(), AudioCensor.censored(right, EditInterval(cut, item.end), null, removingOnly = effect))
        // Tests/TrackMediaTests.swift:676-677: moving the left half by 2 s moves its censor to 3 s.
        assertEquals(3.0, spans(left, start = 2.0)[0].start, 0.0)
    }

    @Test fun aSplitInsideACensorDoesNotRestartItsTone() {
        // Tests/TrackMediaTests.swift:754-759.
        val cut = EditTime.of(1.5)
        val whole = sound("a", censored())
        val left = sound("a", AudioCensor.sliced(censored(), EditInterval(EditTime.ZERO, cut)))
        val right = sound("b", AudioCensor.sliced(censored(), EditInterval(cut, item.end)), start = 1.5)
        val before = AudioCensor.spans(listOf(whole), whole)
        val halves = listOf(left, right)
        for (frame in 47_000 until 97_000 step 7) {
            val time = frame / rate
            val pieces = if (time < 1.5) AudioCensor.spans(halves, left) else AudioCensor.spans(halves, right)
            assertEquals(AudioCensor.tone(time, before, rate), AudioCensor.tone(time, pieces, rate), 1e-12)
        }
        val pulsed = settings.copy(envelope = CensorEnvelope.Pulse, waveform = CensorWaveform.Triangle)
        val one = AudioCensor.censored(emptyList(), item, pulsed, EditInterval.of(1.0, 2.0), effect)
        val second = sound("b", AudioCensor.sliced(one, EditInterval(cut, item.end)), start = 1.5)
        for (frame in 72_000 until 96_000 step 5) {
            val time = frame / rate
            assertEquals(AudioCensor.tone(time, spans(one), rate), AudioCensor.tone(time, AudioCensor.spans(listOf(second), second), rate), 1e-12)
        }
    }

    @Test fun trimmingKeepsOnlyWhatIsLeftOfACensor() {
        // TrackTimeline.swift:196-203: a trim is the same slice as a split.
        val head = AudioCensor.sliced(censored(), EditInterval.of(1.2, 4.0)).single()
        assertEquals(EditInterval.of(0.0, 0.8), head.interval)
        assertEquals(EditTime.of(-0.2), head.phaseOrigin)
        assertEquals(EditInterval.of(-0.2, 0.8), head.envelopeInterval)
        val tail = AudioCensor.sliced(censored(), EditInterval.of(0.0, 1.75)).single()
        assertEquals(EditInterval.of(1.0, 1.75), tail.interval)
        assertEquals(EditInterval.of(1.0, 2.0), tail.envelopeInterval)
        assertEquals(emptyList<AudioCensorRegion>(), AudioCensor.sliced(censored(), EditInterval.of(2.0, 4.0)))
        assertEquals(emptyList<AudioCensorRegion>(), AudioCensor.sliced(censored(), EditInterval.of(0.0, 1.0)))
        // The piece plays what the uncut item played at the same moments.
        val time = 1.5
        assertEquals(AudioCensor.tone(time, spans(censored()), rate), AudioCensor.tone(time, spans(listOf(head), start = 1.2), rate), 1e-12)
    }

    @Test fun aCensorFollowsItsSoundThroughASpeedChange() {
        // Tests/TrackMediaTests.swift:719-723: 4 s at twice the speed, censor 1…2 becomes 0.5…1.
        val clip = TimelineClip(sourceID = "s", start = 0.0, end = 4.0)
        val faster = AudioCensor.retimed(censored(), SpeedMapping.spans(clip, null), SpeedMapping.spans(clip, SpeedSettings(rate = 2.0)))
        assertEquals(EditInterval.of(0.5, 1.0), faster.single().interval)
        // TrackTimeline.swift:1322: the tone starts afresh in the retimed region.
        assertEquals(EditTime.of(0.5), faster.single().phaseOrigin)
        assertNull(faster.single().envelopeInterval)
        assertEquals(effect, faster.single().id)
        assertTrue(AudioCensor.valid(faster, EditTime.of(2.0)))
        // And back, at half speed from there.
        val back = AudioCensor.retimed(faster, SpeedMapping.spans(clip, SpeedSettings(rate = 2.0)), SpeedMapping.spans(clip, null))
        assertEquals(EditInterval.of(1.0, 2.0), back.single().interval)
        val slower = AudioCensor.retimed(censored(), SpeedMapping.spans(clip, null), SpeedMapping.spans(clip, SpeedSettings(rate = 0.25)))
        assertEquals(EditInterval.of(4.0, 8.0), slower.single().interval)
        // A clip that does not start at source second 0.
        val late = TimelineClip(sourceID = "s", start = 10.0, end = 14.0)
        assertEquals(EditInterval.of(0.5, 1.0), AudioCensor.retimed(censored(), SpeedMapping.spans(late, null), SpeedMapping.spans(late, SpeedSettings(rate = 2.0))).single().interval)
    }

    @Test fun aCensorFollowsItsSoundWhenFootageIsCutOut() {
        // TrackTimeline.swift:1311-1333: regions are rebuilt from the source seconds they covered, then joined where they touch.
        val old = listOf(EffectPlaybackSpan(0.0, 4.0, 1.0))
        val region = AudioCensor.censored(emptyList(), item, settings, EditInterval.of(1.0, 3.0), effect)
        // Source 1.5…2.5 removed from inside the censor: what is left of it touches and stays one region.
        val inside = AudioCensor.retimed(region, old, listOf(EffectPlaybackSpan(0.0, 1.5, 1.0), EffectPlaybackSpan(2.5, 4.0, 1.0)))
        assertEquals(listOf(EditInterval.of(1.0, 2.0)), inside.map { it.interval })
        // Source 0…2 removed: only the second half of the censor is still played, now at the start.
        val head = AudioCensor.retimed(region, old, listOf(EffectPlaybackSpan(2.0, 4.0, 1.0)))
        assertEquals(listOf(EditInterval.of(0.0, 1.0)), head.map { it.interval })
        // All of the censored sound removed.
        assertEquals(emptyList<AudioCensorRegion>(), AudioCensor.retimed(region, old, listOf(EffectPlaybackSpan(0.0, 1.0, 1.0), EffectPlaybackSpan(3.0, 4.0, 1.0))))
        // Two censors with different tones stay two.
        val two = AudioCensor.censored(region, item, settings.copy(frequency = 440.0), EditInterval.of(2.0, 3.0), "SECOND")
        val kept = AudioCensor.retimed(two, old, listOf(EffectPlaybackSpan(0.0, 4.0, 2.0)))
        assertEquals(listOf(EditInterval.of(0.5, 1.0), EditInterval.of(1.0, 1.5)), kept.map { it.interval })
        assertEquals(listOf(effect, "SECOND"), kept.map { it.id })
    }

    @Test fun aPastedCensorHasItsOwnIdentity() {
        // Tests/TrackMediaTests.swift:679-682.
        val names = HashMap<String, String>()
        val pasted = AudioCensor.copied(censored() + censored(EditInterval.of(3.0, 4.0)), fresh = { names.getOrPut(it) { newID() } })
        assertNotEquals(effect, pasted[0].id)
        // Pieces of one censor stay pieces of one censor.
        assertEquals(pasted[0].id, pasted[1].id)
        assertEquals(censored().single().copy(id = pasted[0].id), pasted[0])
    }

    @Test fun savedRegionsMustFitTheirItem() {
        // TrackTimeline.swift:1233-1239.
        val duration = EditTime.of(4.0)
        val region = censored().single()
        assertTrue(AudioCensor.valid(listOf(region), duration))
        assertTrue(AudioCensor.valid(emptyList(), duration))
        assertFalse(AudioCensor.valid(listOf(region), EditTime.of(1.5)))
        assertFalse(AudioCensor.valid(listOf(region, region.copy(interval = EditInterval.of(1.5, 2.5), envelopeInterval = null)), duration))
        assertFalse(AudioCensor.valid(listOf(region.copy(interval = EditInterval.of(2.0, 2.0), envelopeInterval = null)), duration))
        assertFalse(AudioCensor.valid(listOf(region.copy(interval = EditInterval.of(-1.0, 2.0), envelopeInterval = null)), duration))
        assertFalse(AudioCensor.valid(listOf(region.copy(settings = settings.copy(frequency = 50.0))), duration))
        // The envelope is the whole censor: it cannot be smaller than a piece of it.
        assertFalse(AudioCensor.valid(listOf(region.copy(envelopeInterval = EditInterval.of(1.2, 2.0))), duration))
        assertFalse(AudioCensor.valid(listOf(region.copy(envelopeInterval = EditInterval.of(1.0, 1.8))), duration))
        assertTrue(AudioCensor.valid(listOf(region.copy(envelopeInterval = EditInterval.of(-3.0, 9.0))), duration))
    }

    // MARK: Who plays the tone

    @Test fun oneCensorAcrossOverlappingSoundsBeepsOnce() {
        // AudioCensor.swift:114-130; Tests/TrackMediaTests.swift:683-688.
        val first = sound("a", censored())
        val second = sound("b", censored())
        var all = listOf(first, second)
        assertEquals(1, all.flatMap { AudioCensor.spans(all, it) }.count { it.emitsTone })
        // Equally loud: the earlier in the list plays it.
        assertTrue(AudioCensor.spans(all, first)[0].emitsTone)
        assertFalse(AudioCensor.spans(all, second)[0].emitsTone)
        // Both are still silenced.
        assertEquals(0.0, AudioCensor.originalGain(1.5, AudioCensor.spans(all, second)), 0.0)
        // The loudest plays it, whatever the order.
        val quiet = first.copy(volumeDB = -18.0)
        all = listOf(quiet, second)
        assertFalse(AudioCensor.spans(all, quiet)[0].emitsTone)
        assertTrue(AudioCensor.spans(all, second)[0].emitsTone)
        // AudioCensor.swift:120: a muted sound never takes the tone away from one that is heard.
        val muted = first.copy(volumeDB = 6.0, muted = true)
        all = listOf(muted, second)
        assertTrue(AudioCensor.spans(all, second)[0].emitsTone)
    }

    @Test fun aDifferentToneOrCensorIsHeardOnItsOwn() {
        // Tests/TrackMediaTests.swift:716-718: retuning one of two overlapping pieces leaves two tones.
        val first = sound("a", censored())
        val retuned = sound("b", AudioCensor.censored(censored(), item, settings.copy(frequency = 2000.0), EditInterval.of(1.0, 2.0), effect))
        var all = listOf(first, retuned)
        assertEquals(2, all.flatMap { AudioCensor.spans(all, it) }.count { it.emitsTone })
        // Two separate censors over the same moment are two tones as well.
        val other = sound("b", AudioCensor.censored(emptyList(), item, settings, EditInterval.of(1.0, 2.0), "OTHER"))
        all = listOf(first, other)
        assertEquals(2, all.flatMap { AudioCensor.spans(all, it) }.count { it.emitsTone })
    }

    @Test fun aStretchIsDividedWhereTheOwnerChanges() {
        // AudioCensor.swift:124-128: the points of every overlapping piece cut the span.
        val range = EditInterval.of(1.0, 3.0)
        val loud = sound("b", AudioCensor.censored(emptyList(), EditInterval.of(2.0, 2.5), settings, range, effect), start = 2.0, volumeDB = 3.0)
        val bed = sound("a", AudioCensor.censored(emptyList(), item, settings, range, effect))
        val all = listOf(bed, loud)
        val spans = AudioCensor.spans(all, bed)
        assertEquals(listOf(1.0 to 2.0, 2.0 to 2.5, 2.5 to 3.0), spans.map { it.start to it.end })
        assertEquals(listOf(true, false, true), spans.map { it.emitsTone })
        assertEquals(listOf(true), AudioCensor.spans(all, loud).map { it.emitsTone })
        // Exactly one tone at every moment, and the same one throughout.
        for (time in listOf(1.25, 2.25, 2.75)) {
            val tones = all.flatMap { AudioCensor.spans(all, it) }.filter { it.emitsTone && time >= it.start && time < it.end }
            assertEquals(1, tones.size)
            assertEquals(1.0, tones[0].phaseOrigin, 0.0)
        }
        // Tests/TrackMediaTests.swift:697-706: a bed and two split halves, one tone at 1.25 s and at 1.75 s.
        val cut = EditTime.of(1.5)
        val left = sound("l", AudioCensor.sliced(censored(), EditInterval(EditTime.ZERO, cut)))
        val right = sound("r", AudioCensor.sliced(censored(), EditInterval(cut, item.end)), start = 1.5)
        val three = listOf(left, right, sound("bed", censored()))
        for (time in listOf(1.25, 1.75)) {
            assertEquals(1, three.flatMap { AudioCensor.spans(three, it) }.count { it.emitsTone && it.start <= time && time < it.end })
        }
    }

    // MARK: Rendering

    @Test fun theCensoredSignalIsTheExactTone() {
        // Tests/TrackMediaTests.swift:762-765: frames 57600..<86400 are amplitude · sin((frame − 48000) · 2π · 1000 / 48000).
        val samples = FloatArray(192_000) { 0.25f * sin(it * 2 * PI * 200 / rate).toFloat() }
        val clean = samples.copyOf()
        AudioCensor.render(samples, 1, rate, 0.0, spans(censored()))
        for (frame in 57_600 until 86_400) {
            val expected = settings.amplitude * sin((frame - 48_000) * 2 * PI * 1000 / rate)
            assertEquals(expected, samples[frame].toDouble(), 0.00002)
        }
        // Tests/TrackMediaTests.swift:761: sound outside the censor is unchanged.
        for (frame in 4800 until 43_200) assertEquals(clean[frame], samples[frame], 0f)
        for (frame in 96_241 until 192_000) assertEquals(clean[frame], samples[frame], 0f)
        // The first cycle: the tone under its 8 ms rise, x = frame / 384, over a silenced source.
        for (frame in 0..48) {
            val x = frame / 384.0
            val expected = settings.amplitude * (x * x * (3 - 2 * x)) * sin(2 * PI * frame / 48)
            assertEquals("frame $frame", expected, samples[48_000 + frame].toDouble(), 1e-7)
        }
        assertEquals(0.0f, samples[48_000], 0f)
        // amplitude · smoothstep(12 / 384) · 1 = 0.12589254 · 0.00286865234375.
        assertEquals(0.000361141934, samples[48_012].toDouble(), 1e-9)
        // The source ducks over the 5 ms before: half of it 120 frames ahead.
        assertEquals(clean[47_880] * 0.5, samples[47_880].toDouble(), 1e-6)
        assertEquals(clean[47_760], samples[47_760], 1e-7f)
    }

    @Test fun renderingDoesNotDependOnHowBuffersAreCut() {
        // Tests/TrackMediaTests.swift:760: "Censor PCM is independent of decoder callback order".
        val spans = spans(censored())
        val whole = FloatArray(2 * 96_000) { 0.1f }
        AudioCensor.render(whole, 2, rate, 0.5, spans)
        val pieces = FloatArray(2 * 96_000) { 0.1f }
        var frame = 0
        for (size in generateSequence(1) { it * 3 % 4099 + 1 }) {
            val count = minOf(size, 96_000 - frame)
            val buffer = pieces.copyOfRange(frame * 2, (frame + count) * 2)
            AudioCensor.render(buffer, 2, rate, 0.5 + frame / rate, spans)
            buffer.copyInto(pieces, frame * 2)
            frame += count
            if (frame == 96_000) break
        }
        // The same to within the rounding of a buffer's start time.
        assertArrayEquals(whole, pieces, 1e-6f)
        // Both channels carry the same tone.
        for (index in 0 until 96_000) assertEquals(whole[index * 2], whole[index * 2 + 1], 0f)
        assertEquals(0.1f, whole[0], 0f)
    }

    @Test fun theToneFollowsItsItemsLevel() {
        // CutAudioEnvelope.swift:132-137: the item's gain multiplies the tone as it does the recorded sound.
        val spans = spans(censored())
        val full = FloatArray(4800) { 0.2f }
        val half = FloatArray(4800) { 0.2f }
        val gains = DoubleArray(4800) { 0.5 }
        AudioCensor.render(full, 1, rate, 0.95, spans)
        AudioCensor.render(half, 1, rate, 0.95, spans, gains = gains)
        for (index in full.indices) assertEquals(full[index] * 0.5f, half[index], 1e-7f)
        assertEquals(0.1f, half[0], 0f)
        // A muted item is silent, tone included.
        val muted = FloatArray(4800) { 0.2f }
        AudioCensor.render(muted, 1, rate, 0.95, spans, gains = DoubleArray(4800))
        assertTrue(muted.all { it == 0f })
        // With an item's envelope: a 1 s fade out ending at 2 s halves the tone at 1.5 s.
        val envelope = AudioEnvelope(listOf(AudioEnvelopeSegment(0.0, 2.0, userFadeOut = AudioEdgeFade(1.0))))
        val faded = FloatArray(480)
        envelope.fill(1.5, 480, rate, gains)
        AudioCensor.render(faded, 1, rate, 1.5, spans, gains = gains)
        assertEquals(0.5 * settings.amplitude, faded[12].toDouble(), 0.0001)
    }

    @Test fun sixteenBitSamplesAreScaledToFullScaleAndClamped() {
        // CutAudioEnvelope.swift:193: Int16(clamp(sample · gain + tone · Int16.max)), truncated towards zero.
        val spans = spans(censored())
        val samples = ShortArray(960) { 1000 }
        AudioCensor.render(samples, 2, rate, 1.5, spans)
        for (frame in 0 until 480) {
            val expected = (settings.amplitude * sin(2 * PI * frame / 48) * 32767).toInt()
            assertTrue("frame $frame", abs(samples[frame * 2] - expected) <= 1)
            assertEquals(samples[frame * 2], samples[frame * 2 + 1])
        }
        assertEquals(4125, samples[24].toInt()) // 0.12589254 · 32767 = 4125.1
        // Outside the censor the samples pass through; a boost cannot go past full scale.
        val loud = ShortArray(4) { if (it < 2) 30_000 else -30_000 }
        AudioCensor.render(loud, 1, rate, 3.0, spans, gains = DoubleArray(4) { 2.0 })
        assertArrayEquals(shortArrayOf(Short.MAX_VALUE, Short.MAX_VALUE, Short.MIN_VALUE, Short.MIN_VALUE), loud)
    }

    // MARK: Saving

    @Test fun regionsAreSavedWithTheOriginalsNames() {
        // AudioCensor.swift:3-4 (raw values), 7-10, 53-57; times as ticks, TrackTimeline.swift:7, 18-19.
        val region = censored().single()
        val saved = documentJson.encodeToString(AudioCensorRegion.serializer(), region)
        assertEquals(
            """{"id":"EFFECT","interval":{"start":{"ticks":240000},"end":{"ticks":480000}},"phaseOrigin":{"ticks":240000},""" +
                """"settings":{"frequency":1000.0,"levelDB":-18.0,"waveform":"sine","envelope":"short"},""" +
                """"envelopeInterval":{"start":{"ticks":240000},"end":{"ticks":480000}}}""",
            saved,
        )
        assertEquals(region, documentJson.decodeFromString(AudioCensorRegion.serializer(), saved))
        // A region with no envelope of its own saves none.
        val whole = documentJson.encodeToString(AudioCensorRegion.serializer(), censored(null).single())
        assertFalse(whole.contains("envelopeInterval"))
        assertEquals(censored(null).single(), documentJson.decodeFromString(AudioCensorRegion.serializer(), whole))
        for (waveform in CensorWaveform.entries) for (envelope in CensorEnvelope.entries) {
            val tone = AudioCensorSettings(440.0, -9.5, waveform, envelope)
            assertEquals(tone, documentJson.decodeFromString(AudioCensorSettings.serializer(), documentJson.encodeToString(AudioCensorSettings.serializer(), tone)))
        }
    }

    @Test fun aRegionWrittenByTheOriginalIsRead() {
        // What Swift's JSONEncoder writes for an AudioCensorRegion: whole numbers without a decimal point, an upper-case UUID.
        val original = """{"id":"5F0C6A2E-0E0B-4B0C-9A53-6B1E0E3C7A11","interval":{"start":{"ticks":120000},"end":{"ticks":360000}},""" +
            """"phaseOrigin":{"ticks":-120000},"settings":{"frequency":440,"levelDB":-12,"waveform":"triangle","envelope":"pulse"}}"""
        val region = documentJson.decodeFromString(AudioCensorRegion.serializer(), original)
        assertEquals("5F0C6A2E-0E0B-4B0C-9A53-6B1E0E3C7A11", region.id)
        assertEquals(EditInterval.of(0.5, 1.5), region.interval)
        assertEquals(EditTime.of(-0.5), region.phaseOrigin)
        assertEquals(AudioCensorSettings(440.0, -12.0, CensorWaveform.Triangle, CensorEnvelope.Pulse), region.settings)
        assertNull(region.envelopeInterval)
    }
}
