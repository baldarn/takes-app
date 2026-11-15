package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Sound levels against the original app: YouRec/AudioGain.swift,
 * YouRec/CutAudioEnvelope.swift, the fades of YouRec/TrackTimeline.swift:133-151
 * and the numbers its tests pin in Tests/TrackMediaTests.swift.
 */
class AudioLevelsTest {
    private val tiny = 1e-9
    private val rate = 48_000.0

    // MARK: Edit clock

    @Test fun editTimesAreWholeTicksOf240000ths() {
        // TrackTimeline.swift:6, 9-10: scale 240 000, rounded to the nearest tick, clamped to a week, 0 when not finite.
        assertEquals(360_000L, EditTime.of(1.5).ticks)
        assertEquals(1.5, EditTime.of(1.5).seconds, 0.0)
        assertEquals(1.0, roundedAwayFromZero(0.5), 0.0)
        assertEquals(-1.0, roundedAwayFromZero(-0.5), 0.0)
        assertEquals(3.0, roundedAwayFromZero(2.5), 0.0)
        assertEquals(1L, EditTime.of(1.0 / 240_000).ticks)
        assertEquals(0L, EditTime.of(0.4 / 240_000).ticks)
        assertEquals(604_800L * 240_000, EditTime.of(1e12).ticks)
        assertEquals(-604_800L * 240_000, EditTime.of(-1e12).ticks)
        assertEquals(EditTime.ZERO, EditTime.of(Double.NaN))
        assertEquals(EditTime.ZERO, EditTime.of(Double.POSITIVE_INFINITY))
        assertEquals(EditTime.of(0.4), EditTime.of(1.0) - EditTime.of(0.6))
    }

    @Test fun intervalsAreHalfOpenAndIntersect() {
        // TrackTimeline.swift:22-29.
        val interval = EditInterval.of(1.0, 2.0)
        assertTrue(EditTime.of(1.0) in interval)
        assertFalse(EditTime.of(2.0) in interval)
        assertEquals(EditInterval.of(1.5, 2.0), interval.intersection(EditInterval.of(1.5, 4.0)))
        assertNull(interval.intersection(EditInterval.of(2.0, 3.0)))
        assertEquals(1.0..1.0, EditInterval.of(1.0, 0.5).seconds)
    }

    @Test fun editTimesAreSavedAsTicks() {
        // Swift's synthesized Codable of `var ticks: Int64` and of `start`/`end`, TrackTimeline.swift:7, 18-19.
        assertEquals("""{"ticks":360000}""", documentJson.encodeToString(EditTime.serializer(), EditTime.of(1.5)))
        assertEquals("""{"start":{"ticks":240000},"end":{"ticks":480000}}""", documentJson.encodeToString(EditInterval.serializer(), EditInterval.of(1.0, 2.0)))
        assertEquals(EditInterval.of(1.0, 2.0), documentJson.decodeFromString(EditInterval.serializer(), """{"start":{"ticks":240000},"end":{"ticks":480000}}"""))
    }

    // MARK: Decibels

    @Test fun decibelsAndFactorsConvertBothWays() {
        // pow(10, db / 20), AudioGain.swift:59, 139, 212; 20 * log10(gain), AudioGain.swift:109.
        assertEquals(1.0, AudioLevels.linear(0.0), 0.0)
        assertEquals(10.0, AudioLevels.linear(20.0), tiny)
        assertEquals(1.9952623149688795, AudioLevels.linear(6.0), tiny)
        assertEquals(0.12589254117941673, AudioLevels.linear(-18.0), tiny)
        assertEquals(Double.NEGATIVE_INFINITY, AudioLevels.decibels(0.0), 0.0)
        var level = AudioGainGrid.MINIMUM_DB
        while (level <= AudioGainGrid.MAXIMUM_DB) {
            assertEquals(level, AudioLevels.decibels(AudioLevels.linear(level)), tiny)
            // The existing clip sound uses the same formula.
            assertEquals(ClipAudio(volumeDB = level).gain, AudioLevels.linear(level), 0.0)
            level += AudioGainGrid.STEP_DB
        }
    }

    @Test fun levelsAreShownWithASignAndOneDecimal() {
        // String(format: "%+.1f dB", value), AudioGain.swift:294, QuickVolumeControl.swift:35, 68, EditorView.swift:974.
        assertEquals("+0.0 dB", AudioLevels.format(0.0))
        assertEquals("+6.0 dB", AudioLevels.format(6.0))
        assertEquals("-12.5 dB", AudioLevels.format(-12.5))
        assertEquals("+12.0 dB", AudioLevels.format(12.0))
    }

    @Test fun thePeakIsTheLargestAbsoluteSample() {
        // AudioGain.swift:186-189: a sample that is not a number fails the measurement.
        assertEquals(0.7f, AudioLevels.peak(floatArrayOf(0.1f, -0.7f, 0.3f))!!, 0f)
        assertEquals(0.9f, AudioLevels.peak(floatArrayOf(0.1f, -0.7f), peak = 0.9f)!!, 0f)
        assertEquals(0.1f, AudioLevels.peak(floatArrayOf(0.1f, -0.7f), count = 1)!!, 0f)
        assertNull(AudioLevels.peak(floatArrayOf(0.1f, Float.NaN)))
    }

    // MARK: Grid

    @Test fun gainSnapsToHalfDecibelsBetweenMinus40And12() {
        // Tests/TrackMediaTests.swift:286.
        assertEquals(1.0, AudioGainGrid.snap(1.24), 0.0)
        assertEquals(1.5, AudioGainGrid.snap(1.26), 0.0)
        assertEquals(12.0, AudioGainGrid.snap(90.0), 0.0)
        // min(12, max(-40, (db * 2).rounded() / 2)), AudioGain.swift:7; rounded() goes away from zero at a half.
        assertEquals(-40.0, AudioGainGrid.snap(-90.0), 0.0)
        assertEquals(0.5, AudioGainGrid.snap(0.25), 0.0)
        assertEquals(-0.5, AudioGainGrid.snap(-0.25), 0.0)
        assertEquals(0.0, AudioGainGrid.snap(0.24), 0.0)
        assertEquals(0.0, AudioGainGrid.snap(-0.24), 0.0)
        assertEquals(-39.5, AudioGainGrid.snap(-39.6), 0.0)
        var level = AudioGainGrid.MINIMUM_DB
        while (level <= AudioGainGrid.MAXIMUM_DB) { assertEquals(level, AudioGainGrid.snap(level), 0.0); level += AudioGainGrid.STEP_DB }
    }

    @Test fun aCommonLevelNeedsEveryPartWithinAThousandth() {
        // AudioGain.swift:8-11.
        assertEquals(-3.0, AudioGainGrid.common(listOf(-3.0, -3.0, -3.0004))!!, 0.0)
        assertNull(AudioGainGrid.common(listOf(-3.0, -2.5)))
        assertNull(AudioGainGrid.common(emptyList()))
    }

    @Test fun theStepperMovesHalfADecibelAndStartsMixedLevelsFromTheirBounds() {
        // TrackEditors.swift:276, 376-378.
        assertEquals(0.5, AudioGainGrid.stepped(0.0, 0.5, 0.0..0.0), 0.0)
        assertEquals(12.0, AudioGainGrid.stepped(12.0, 0.5, 12.0..12.0), 0.0)
        assertEquals(3.0, AudioGainGrid.stepped(null, 0.5, -6.0..3.0), 0.0)
        assertEquals(-6.0, AudioGainGrid.stepped(null, -0.5, -6.0..3.0), 0.0)
    }

    // MARK: Drag

    @Test fun aDragMovesAnEighthOfADecibelPerPointWithADetent() {
        // AudioGain.swift:23-28: raw = initial + distance * 0.125; a level holds within 0.4 dB of it, 0 dB within 0.6.
        val drag = AudioGainDrag.begin(listOf(0.0))
        assertEquals(0.0, drag.initial!!, 0.0)
        assertEquals(0.0, drag.updated(4.0).value!!, 0.0) // raw 0.5: still inside the zero detent
        assertEquals(0.5, drag.updated(5.0).value!!, 0.0) // raw 0.625
        assertEquals(-0.5, drag.updated(-5.0).value!!, 0.0)
        val half = drag.updated(5.0)
        assertEquals(0.5, half.updated(6.0).value!!, 0.0) // raw 0.75 snaps to 1 but is within 0.4 of 0.5
        assertEquals(1.0, half.updated(8.0).value!!, 0.0) // raw 1.0
        assertEquals(0.0, half.updated(0.0).value!!, 0.0)
        assertEquals(12.0, drag.updated(1000.0).value!!, 0.0)
        assertEquals(-40.0, drag.updated(-1000.0).value!!, 0.0)
    }

    @Test fun distanceAndDecibelsRoundTripOnTheGrid() {
        // 0.125 dB per point, AudioGain.swift:24.
        assertEquals(4.0, AudioGainDrag.distance(AudioGainGrid.STEP_DB), 0.0)
        var level = AudioGainGrid.MINIMUM_DB
        while (level <= AudioGainGrid.MAXIMUM_DB) {
            assertEquals(level, AudioGainDrag.decibels(AudioGainDrag.distance(level)), 0.0)
            // From 0 dB every level but the two inside its detent is reached at exactly its own distance.
            if (abs(level) >= 1) assertEquals(level, AudioGainDrag.begin(listOf(0.0)).updated(AudioGainDrag.distance(level)).value!!, 0.0)
            level += AudioGainGrid.STEP_DB
        }
        // From any other level the nearest neighbours are reached too: 0.5 is outside the 0.4 detent.
        val drag = AudioGainDrag.begin(listOf(-6.0))
        assertEquals(-5.5, drag.updated(4.0).value!!, 0.0)
        assertEquals(-6.5, drag.updated(-4.0).value!!, 0.0)
        assertEquals(-6.0, drag.updated(3.0).value!!, 0.0)
    }

    @Test fun mixedLevelsStayUntouchedForTwelvePoints() {
        // AudioGain.swift:23-26; the bounds are the quietest and loudest part, EditorTimeline.swift:634.
        val drag = AudioGainDrag.begin(listOf(-6.0, 3.0))
        assertNull(drag.initial)
        assertEquals(-6.0..3.0, drag.levels)
        assertNull(drag.updated(11.0).value)
        assertNull(drag.updated(-11.9).value)
        assertEquals(3.0, drag.updated(12.0).value!!, 0.0)
        assertEquals(4.0, drag.updated(20.0).value!!, 0.0)
        assertEquals(-6.0, drag.updated(-12.0).value!!, 0.0)
        assertEquals(-7.0, drag.updated(-20.0).value!!, 0.0)
    }

    @Test fun restingAtTheScreenEdgeKeepsStepping() {
        // AudioGain.swift:30-33, EditorTimeline.swift:641, 658: half a decibel every 0.12 s within 22 points of the edge.
        assertEquals(0.5, AudioGainDrag.edgeStep(10.0, 0.0, 800.0), 0.0)
        assertEquals(-0.5, AudioGainDrag.edgeStep(790.0, 0.0, 800.0), 0.0)
        assertEquals(0.0, AudioGainDrag.edgeStep(22.0, 0.0, 800.0), 0.0)
        assertEquals(0.12, AudioGainDrag.EDGE_INTERVAL, 0.0)
        val drag = AudioGainDrag.begin(listOf(0.0)).updated(40.0)
        assertEquals(5.0, drag.value!!, 0.0)
        val stepped = drag.steppedAtEdge(0.5).steppedAtEdge(0.5)
        assertEquals(6.0, stepped.value!!, 0.0)
        assertEquals(1.0, stepped.continued, 0.0)
        // The steps stay when the finger moves again.
        assertEquals(6.0, stepped.updated(40.0).value!!, 0.0)
        assertEquals(5.5, stepped.updated(36.0).value!!, 0.0)
        // At the end of the grid a step changes nothing.
        val top = AudioGainDrag.begin(listOf(12.0))
        assertSame(top, top.steppedAtEdge(0.5))
        // Mixed levels start from their bound, and the dead zone no longer applies.
        val mixed = AudioGainDrag.begin(listOf(-6.0, 3.0)).steppedAtEdge(0.5)
        assertEquals(3.5, mixed.value!!, 0.0)
        assertEquals(3.5, mixed.updated(0.0).value!!, 0.0)
        assertEquals(-6.5, AudioGainDrag.begin(listOf(-6.0, 3.0)).steppedAtEdge(-0.5).value!!, 0.0)
    }

    @Test fun aDragSavesOnlyAChangedLevelThatWasNotCancelled() {
        // EditorTimeline.swift:647 (80 points sideways cancel), 685.
        assertFalse(AudioGainDrag.cancels(80.0))
        assertTrue(AudioGainDrag.cancels(-80.5))
        val drag = AudioGainDrag.begin(listOf(0.0))
        assertNull(drag.committed())
        assertNull(drag.updated(40.0).updated(0.0).committed())
        assertEquals(5.0, drag.updated(40.0).committed()!!, 0.0)
        assertNull(drag.updated(40.0).committed(cancelled = true))
        assertNull(AudioGainDrag.begin(listOf(-6.0, 3.0)).updated(5.0).committed())
    }

    // MARK: Headroom

    @Test fun headroomCountsTheOtherTracks() {
        // Tests/TrackMediaTests.swift:181-187.
        val bounds = AudioHeadroom().including(0.5, 0.25)
        assertFalse(bounds.clips(3.0)); assertTrue(bounds.clips(4.0))
        val cancellation = AudioHeadroom().including(-0.5, 1.2)
        assertTrue(cancellation.clips(-12.0)); assertFalse(cancellation.clips(0.0))
        val silentSelected = AudioHeadroom().including(0.0, 1.2)
        assertTrue(silentSelected.clips(-40.0))
        // -1 <= S*g + U <= 1, AudioGain.swift:203-209: S = 0.5, U = 0.25 leaves g in -2.5…1.5.
        assertEquals(0.0, bounds.minimum, 0.0)
        assertEquals(1.5, bounds.maximum, tiny)
        assertEquals(1, bounds.samples)
        assertEquals(0.4, cancellation.minimum, tiny)
        assertEquals(4.4, cancellation.maximum, tiny)
        assertEquals(0, silentSelected.samples)
    }

    @Test fun aHalfScaleSignalClipsJustAbove6Decibels() {
        // Tests/TrackMediaTests.swift:197: !clips(6) && clips(6.5); 283: two of them, !clips(-0.5) && clips(0.5).
        val half = AudioHeadroom().including(0.5, 0.0).including(-0.5, 0.0)
        assertFalse(half.clips(6.0)); assertTrue(half.clips(6.5))
        val doubled = AudioHeadroom().including(0.5, 0.5)
        assertFalse(doubled.clips(-0.5)); assertTrue(doubled.clips(0.5))
        // AudioDiagnostics.swift:199 and Tests/TrackMediaTests.swift:500: nothing measured never clips.
        assertTrue(AudioHeadroom().silent)
        assertFalse(AudioHeadroom().clips(12.0))
        // AudioGain.swift:206: a sample that is not a number clips at every level.
        assertTrue(AudioHeadroom().including(Double.NaN, 0.0).clips(-40.0))
    }

    @Test fun headroomOverBuffersEqualsSampleBySample() {
        val random = Random(7)
        val selected = FloatArray(4096) { if (it % 50 == 0) 0f else random.nextFloat() * 1.6f - 0.8f }
        val remainder = FloatArray(4096) { random.nextFloat() * 0.6f - 0.3f }
        var expected = AudioHeadroom()
        for (index in selected.indices) expected = expected.including(selected[index].toDouble(), remainder[index].toDouble())
        assertEquals(expected, AudioHeadroom().including(selected, remainder))
        assertEquals(expected, AudioHeadroom().including(selected, remainder, 1000).including(selected.copyOfRange(1000, 4096), remainder.copyOfRange(1000, 4096)))
        assertTrue(expected.samples in 4000..4096)
    }

    @Test fun headroomIsMeasuredOverTheSelectionAndItsRamp() {
        // AudioGain.swift:221, 233: max(0, lower) … min(duration, upper + 0.01), in frames at 48 kHz.
        assertEquals(48_000L until 144_480L, AudioHeadroom.frames(1.0..3.0, 4.0))
        assertEquals(0L until 192_000L, AudioHeadroom.frames(-1.0..9.0, 4.0))
        // Tests/TrackMediaTests.swift:295: a selection past the end of the mix fails safely.
        assertNull(AudioHeadroom.frames(12.0..18.0, 4.0))
        assertNull(AudioHeadroom.frames(1.0..3.0, Double.NaN))
    }

    @Test fun theClippingWarningWaitsForAChosenLevel() {
        // TrackEditors.swift:279, 282: !muted && (!mixedVolume || volumeChosen) && headroom?.clips(at: volume) == true.
        val headroom = AudioHeadroom().including(0.5, 0.0)
        assertTrue(AudioLevels.warnsClipping(6.5, headroom))
        assertFalse(AudioLevels.warnsClipping(6.0, headroom))
        assertFalse(AudioLevels.warnsClipping(6.5, null))
        assertFalse(AudioLevels.warnsClipping(6.5, headroom, muted = true))
        assertFalse(AudioLevels.warnsClipping(6.5, headroom, mixed = true))
        assertTrue(AudioLevels.warnsClipping(6.5, headroom, mixed = true, chosen = true))
    }

    // MARK: Fades on an item

    @Test fun aSplitInsideAFadeKeepsItsLevelAndSlope() {
        // Tests/TrackMediaTests.swift:553, 570-573: a 1 s fade in, cut at 0.4 s, begins the later piece at 0.4.
        val fade = AudioEdgeFade.of(1.0, EditTime.of(1.5))!!
        val after = fade.trimming(EditTime.of(0.4))!!
        assertEquals(0.4, after.gain(0.0), 0.00001)
        assertEquals(0.7, after.gain(0.3), 0.00001)
        assertEquals(1.0, after.gain(0.6), 0.00001)
        assertEquals(0.6, after.remaining(4.0), 0.00001)
        // TrackTimeline.swift:140-144: nothing is left once the whole fade is cut off.
        assertNull(fade.trimming(EditTime.of(1.0)))
        // TrackTimeline.swift:151: clamp01((elapsed + distance) / seconds).
        assertEquals(0.0, fade.gain(-1.0), 0.0)
        assertEquals(0.25, fade.gain(0.25), 0.0)
        assertEquals(1.0, fade.gain(3.0), 0.0)
    }

    @Test fun aFadeIsNoLongerThanItsItem() {
        // TrackTimeline.swift:191-195.
        assertEquals(AudioEdgeFade(1.2), AudioEdgeFade.of(1.2, EditTime.of(4.0)))
        assertEquals(AudioEdgeFade(4.0), AudioEdgeFade.of(9.0, EditTime.of(4.0)))
        assertNull(AudioEdgeFade.of(0.0, EditTime.of(4.0)))
        assertNull(AudioEdgeFade.of(-1.0, EditTime.of(4.0)))
        // TrackTimeline.swift:138.
        assertTrue(AudioEdgeFade(1.0, 0.4).valid)
        assertFalse(AudioEdgeFade(0.0).valid)
        assertFalse(AudioEdgeFade(1.0, 1.0).valid)
        assertFalse(AudioEdgeFade(604_801.0).valid)
    }

    @Test fun aFadeIsSqueezedIntoAShorterItemAndNeverStretched() {
        // TrackTimeline.swift:144-150, used when a speed change shortens the item, TrackTimeline.swift:1282-1283.
        val fade = AudioEdgeFade(1.0)
        assertEquals(fade, fade.fitted(EditTime.of(4.0), EditTime.of(2.0)))
        assertEquals(fade, fade.fitted(EditTime.of(4.0), EditTime.of(8.0)))
        assertEquals(AudioEdgeFade(0.5), fade.fitted(EditTime.of(4.0), EditTime.of(0.5)))
        // A fade already partly behind the edge keeps its proportions: 0.6 s were heard, 0.3 s are left.
        val fitted = AudioEdgeFade(1.0, 0.4).fitted(EditTime.of(4.0), EditTime.of(0.3))
        assertEquals(0.5, fitted.seconds, tiny)
        assertEquals(0.2, fitted.elapsed, tiny)
        assertEquals(0.3, fitted.remaining(0.3), tiny)
    }

    @Test fun bothPiecesOfASplitKeepTheirOwnEdge() {
        // TrackTimeline.swift:201-202: fadeIn.trimming(part.start), fadeOut.trimming(duration - part.end).
        val length = EditTime.of(4.0)
        val fades = AudioEdgeFades(AudioEdgeFade(1.0), AudioEdgeFade(1.0))
        val cut = EditTime.of(0.4)
        assertEquals(AudioEdgeFades(AudioEdgeFade(1.0), null), fades.sliced(EditInterval(EditTime.ZERO, cut), length))
        assertEquals(AudioEdgeFades(AudioEdgeFade(1.0, 0.4), AudioEdgeFade(1.0)), fades.sliced(EditInterval(cut, length), length))
        // A cut inside the fade out leaves the earlier piece ending partway down.
        val early = fades.sliced(EditInterval(EditTime.ZERO, EditTime.of(3.5)), length)
        assertEquals(0.5, early.gain(3.5, 3.5), tiny)
        assertEquals(1.0, early.gain(3.0, 3.5), tiny)
        // CutAudioEnvelope.swift:38: the lower of the two fades.
        assertEquals(0.25, fades.gain(0.25, 4.0), tiny)
        assertEquals(0.25, fades.gain(3.75, 4.0), tiny)
        assertEquals(1.0, fades.gain(2.0, 4.0), tiny)
        assertEquals(1.0, AudioEdgeFades().gain(0.0, 4.0), 0.0)
    }

    @Test fun fadesAreSavedWithTheOriginalsNames() {
        // `let seconds`, `var elapsed`, TrackTimeline.swift:135-136; `fadeIn`, `fadeOut`, TrackTimeline.swift:176-177.
        assertEquals("""{"seconds":1.0,"elapsed":0.4}""", documentJson.encodeToString(AudioEdgeFade.serializer(), AudioEdgeFade(1.0, 0.4)))
        assertEquals(AudioEdgeFade(2.0), documentJson.decodeFromString(AudioEdgeFade.serializer(), """{"seconds":2,"elapsed":0}"""))
        val fades = AudioEdgeFades(fadeOut = AudioEdgeFade(1.0))
        assertEquals("""{"fadeOut":{"seconds":1.0,"elapsed":0.0}}""", documentJson.encodeToString(AudioEdgeFades.serializer(), fades))
        assertEquals(fades, documentJson.decodeFromString(AudioEdgeFades.serializer(), documentJson.encodeToString(AudioEdgeFades.serializer(), fades)))
    }

    @Test fun oneItemCarriesEachFade() {
        // Tests/TrackMediaTests.swift:550-552: three pieces of 4 s cut at 1.5 and 2.5: the first fades in, the last out.
        val pieces = listOf(EditInterval.of(0.0, 1.5), EditInterval.of(1.5, 2.5), EditInterval.of(2.5, 4.0))
        assertEquals(0, AudioFadeEdge.FadeIn.index(pieces) { true })
        assertEquals(2, AudioFadeEdge.FadeOut.index(pieces) { true })
        assertEquals(1, AudioFadeEdge.FadeIn.index(pieces) { it > 0 })
        assertNull(AudioFadeEdge.FadeOut.index(pieces) { false })
        // Tests/TrackMediaTests.swift:565-567: all three at 0, the lowest number wins both ties.
        val tied = listOf(EditInterval.of(0.0, 1.5), EditInterval.of(0.0, 1.0), EditInterval.of(0.0, 1.5))
        assertEquals(0, AudioFadeEdge.FadeIn.index(tied) { true })
        assertEquals(0, AudioFadeEdge.FadeOut.index(tied) { true })
        // Tests/TrackMediaTests.swift:568-569: the latest end wins even if a shorter piece starts later.
        val later = listOf(EditInterval.of(0.0, 1.5), EditInterval.of(0.4, 1.4), EditInterval.of(0.1, 1.6))
        assertEquals(2, AudioFadeEdge.FadeOut.index(later) { true })
    }

    // MARK: Fades at cuts

    @Test fun aCutFadesOver384SamplesAt48kHz() {
        // fadeIn/fadeOut 0.008, TrackRenderer.swift:291; clamp01(min((t - start) / fadeIn, (end - t) / fadeOut)), CutAudioEnvelope.swift:31-32.
        assertEquals(0.008, AudioLevels.CUT_FADE, 0.0)
        val segment = AudioEnvelopeSegment(start = 1.0, end = 2.0)
        for (frame in 0..500) {
            val expected = minOf(1.0, frame / 384.0)
            assertEquals("in $frame", expected, segment.gain((48_000 + frame) / rate), tiny)
            // The run ends before its last frame's successor: frame 96 000 is outside.
            assertEquals("out $frame", if (frame == 0) 0.0 else expected, segment.gain((96_000 - frame) / rate), tiny)
        }
        assertEquals(0.0, segment.gain(48_000 / rate), 0.0)
        assertEquals(1.0 / 384, segment.gain(48_001 / rate), tiny)
        assertEquals(0.5, segment.gain(48_192 / rate), tiny)
        assertEquals(1.0, segment.gain(48_384 / rate), tiny)
        assertEquals(0.0, segment.gain(47_999 / rate), 0.0)
        assertEquals(0.0, segment.gain(2.5), 0.0)
        // TrackRenderer.swift:290-291: sound that continues from the run before has no fade between the two.
        assertEquals(1.0, segment.copy(fadeIn = 0.0).gain(1.0), 0.0)
        assertEquals(1.0, AudioEnvelopeSegment(0.0, 1.0, fadeOut = 0.0).gain(47_999 / rate), 0.0)
    }

    @Test fun theCutEnvelopeDipsToSilenceAtEveryCut() {
        // CutAudioEnvelope.swift:85, 139, 146-147: min(time, duration - time) / 0.008, |boundary - time| / 0.008.
        val envelope = CutEnvelope.of(listOf(1.0, 1.0, 2.0)) { it == 1 }
        assertEquals(4.0, envelope.duration, 0.0)
        for (frame in 0..500) {
            val expected = minOf(1.0, frame / 384.0)
            assertEquals(expected, envelope.gain(frame / rate), tiny)
            assertEquals(expected, envelope.gain((192_000 - frame) / rate), tiny)
            assertEquals(expected, envelope.gain((48_000 + frame) / rate), tiny)
            assertEquals(expected, envelope.gain((48_000 - frame) / rate), tiny)
            // The second join continues the same sound: no dip.
            assertEquals(1.0, envelope.gain((96_000 + frame) / rate), 0.0)
        }
        assertEquals(0.0, envelope.gain(-0.1), 0.0)
        assertEquals(0.0, envelope.gain(4.1), 0.0)
    }

    @Test fun soundContinuesAcrossASplitNobodyTrimmed() {
        // VideoEffectTiming.swift:29-31.
        val first = TimelineClip(sourceID = "a", start = 0.0, end = 2.0)
        assertTrue(CutEnvelope.continues(first, TimelineClip(sourceID = "a", start = 2.0, end = 4.0)))
        assertFalse(CutEnvelope.continues(first, TimelineClip(sourceID = "a", start = 2.1, end = 4.0)))
        assertFalse(CutEnvelope.continues(first, TimelineClip(sourceID = "b", start = 2.0, end = 4.0)))
        assertFalse(CutEnvelope.continues(first, TimelineClip(sourceID = "a", start = 2.0, end = 4.0, audio = ClipAudio(muted = true))))
    }

    @Test fun theEnvelopeOverThousandsOfCutsEqualsTheNaiveOne() {
        // "Binary search makes even thousands of cuts inexpensive per sample", CutAudioEnvelope.swift:140-148.
        val random = Random(42)
        val durations = List(3000) { 0.004 + random.nextDouble() * 0.5 }
        val joined = List(3000) { random.nextInt(5) == 0 }
        val envelope = CutEnvelope.of(durations) { joined[it] }
        val boundaries = ArrayList<Double>()
        var cursor = 0.0
        durations.forEachIndexed { index, duration -> if (index > 0 && !joined[index - 1]) boundaries += cursor; cursor += duration }
        assertTrue(boundaries.size > 2000)
        fun naive(time: Double): Double {
            var gain = minOf(1.0, maxOf(0.0, minOf(time, cursor - time) / 0.008))
            for (boundary in boundaries) gain = minOf(gain, abs(boundary - time) / 0.008)
            return gain
        }
        repeat(20_000) {
            val time = -0.01 + random.nextDouble() * (cursor + 0.02)
            assertEquals(naive(time), envelope.gain(time), 0.0)
        }
        // On and right beside the cuts themselves, where the search has to pick the right neighbour.
        for (boundary in boundaries) for (offset in listOf(-1 / rate, 0.0, 1 / rate)) {
            assertEquals(naive(boundary + offset), envelope.gain(boundary + offset), 0.0)
        }
        assertEquals(0.0, envelope.gain(boundaries[1000]), 0.0)
    }

    @Test fun theRunAtATimeIsFoundAmongThousands() {
        // CutAudioEnvelope.swift:123-129: the first run whose end is after the time; none past the last.
        val random = Random(3)
        val segments = ArrayList<AudioEnvelopeSegment>()
        var cursor = 0.5
        repeat(2500) {
            val length = 0.02 + random.nextDouble()
            // Some runs leave a gap of silence before them.
            if (random.nextInt(4) == 0) cursor += random.nextDouble()
            segments += AudioEnvelopeSegment(cursor, cursor + length, volume = 0.25 + random.nextDouble())
            cursor += length
        }
        val envelope = AudioEnvelope(segments)
        assertEquals(cursor, envelope.duration, 0.0)
        repeat(20_000) {
            val time = random.nextDouble() * (cursor + 1)
            assertEquals(segments.firstOrNull { it.end > time }, envelope.segment(time))
            assertEquals(segments.sumOf { it.gain(time) }, envelope.gain(time), 0.0)
        }
        assertEquals(0.0, envelope.gain(0.25), 0.0)
        assertNull(envelope.segment(cursor))
        val gains = DoubleArray(480)
        envelope.fill(segments[10].start, 480, rate, gains)
        for (frame in 0 until 480) assertEquals(segments[10].gain(segments[10].start + frame / rate), gains[frame], 0.0)
    }

    // MARK: Everything else shaping a run

    @Test fun volumeFadesAndTransitionsMultiply() {
        val segment = AudioEnvelopeSegment(start = 10.0, end = 14.0, clipStart = 10.0, clipEnd = 14.0)
        // CutAudioEnvelope.swift:54: the item's volume scales the envelope.
        assertEquals(0.5, segment.copy(volume = 0.5).gain(12.0), tiny)
        // CutAudioEnvelope.swift:38: the user's fades are measured from the item's edges.
        val faded = segment.copy(userFadeIn = AudioEdgeFade(1.0), userFadeOut = AudioEdgeFade(2.0))
        assertEquals(0.4, faded.gain(10.4), tiny)
        assertEquals(0.25, faded.gain(13.5), tiny)
        assertEquals(1.0, faded.gain(11.5), tiny)
        // CutAudioEnvelope.swift:36-37: the same ramps as TransitionSound, multiplied.
        val ramped = segment.copy(inRamp = 10.0..10.5, outRamp = 13.0..14.0)
        assertEquals(TransitionSound.rising(10.25, 10.0..10.5), ramped.gain(10.25), tiny)
        assertEquals(0.5, ramped.gain(10.25), tiny)
        assertEquals(0.25, ramped.gain(13.75), tiny)
        assertEquals(TransitionSound.clipLevel(12.0, 10.0..10.5, 13.0..14.0), ramped.gain(12.0), tiny)
        // CutAudioEnvelope.swift:33-34: fades of the picture's own length limit rather than multiply.
        val transition = segment.copy(transitionIn = 1.0, transitionOut = 0.5)
        assertEquals(0.25, transition.gain(10.25), tiny)
        assertEquals(0.5, transition.gain(13.75), tiny)
        // CutAudioEnvelope.swift:35: 8 ms either side of what is audible.
        val audible = segment.copy(audibleStart = 11.0, audibleEnd = 13.0)
        assertEquals(0.0, audible.gain(10.9), 0.0)
        assertEquals(0.5, audible.gain(11.004), 1e-6)
        assertEquals(0.5, audible.gain(12.996), 1e-6)
        // CutAudioEnvelope.swift:39: a volume sliding across the run.
        assertEquals(0.5, segment.copy(volume = 0.25, endVolume = 0.75).gain(12.0), tiny)
        // CutAudioEnvelope.swift:49-53: continuing sound slides from the volume before over 10 ms.
        val continued = segment.copy(fadeIn = 0.0, initialVolume = 0.2, volume = 1.0)
        assertEquals(0.2, continued.gain(10.0), tiny)
        assertEquals(0.6, continued.gain(10.005), 1e-6)
        assertEquals(1.0, continued.gain(10.01), 1e-6)
    }

    @Test fun selectedAndRemainderMixesSplitTheSoundForHeadroom() {
        // AudioGain.swift:125-149.
        val segments = listOf(
            AudioEnvelopeSegment(0.0, 4.0, owner = "a", volume = 0.5),
            AudioEnvelopeSegment(0.0, 4.0, owner = "b", volume = 0.8),
        )
        val whole = AudioGainSelection(setOf("a"))
        val selected = AudioGainMix.replacing(segments, whole, AudioGainMix.Mode.Selected)
        val remainder = AudioGainMix.replacing(segments, whole, AudioGainMix.Mode.Remainder)
        assertEquals(1.0, selected[0].gain(2.0), tiny) // the selected sound at 0 dB, whatever its saved volume
        assertEquals(0.0, selected[1].gain(2.0), tiny)
        assertEquals(0.0, remainder[0].gain(2.0), tiny)
        assertEquals(0.8, remainder[1].gain(2.0), tiny)
        // The cut fades still apply to both mixes.
        assertEquals(0.5, selected[0].gain(0.004), 1e-6)
        val audition = AudioGainMix.replacing(segments, whole, AudioGainMix.Mode.Audition(-6.0))
        assertEquals(AudioLevels.linear(-6.0), audition[0].gain(2.0), tiny)
        assertEquals(0.8, audition[1].gain(2.0), tiny)

        // A marked range: 10 ms ramps at its two ends, never a fade through silence, CutAudioEnvelope.swift:40-47.
        val ranged = AudioGainSelection(setOf("a"), EditInterval.of(1.0, 3.0))
        val inside = AudioGainMix.replacing(segments, ranged, AudioGainMix.Mode.Selected)[0]
        val outside = AudioGainMix.replacing(segments, ranged, AudioGainMix.Mode.Remainder)[0]
        assertEquals(0.0, inside.gain(0.5), tiny); assertEquals(0.5, outside.gain(0.5), tiny)
        assertEquals(1.0, inside.gain(2.0), tiny); assertEquals(0.0, outside.gain(2.0), tiny)
        assertEquals(0.5, inside.gain(1.005), 1e-6); assertEquals(0.25, outside.gain(1.005), 1e-6)
        assertEquals(0.5, inside.gain(3.005), 1e-6); assertEquals(0.25, outside.gain(3.005), 1e-6)
        assertEquals(0.0, inside.gain(3.01), 1e-6); assertEquals(0.5, outside.gain(3.01), 1e-6)
        // Saving 6 dB over the range gives the selected part times its factor plus the rest.
        val boosted = AudioGainMix.replacing(segments, ranged, AudioGainMix.Mode.Audition(6.0))[0]
        for (time in listOf(0.5, 1.003, 2.0, 3.007, 3.5)) {
            assertEquals(inside.gain(time) * AudioLevels.linear(6.0) + outside.gain(time), boosted.gain(time), tiny)
        }
    }

    @Test fun aLevelBeingTriedRampsInAndOutOver10Milliseconds() {
        // CutAudioEnvelope.swift:101-119: 480 frames at 48 kHz, on the selected sound only.
        val live = LiveAudioGain()
        val selection = AudioGainSelection(setOf("a"))
        val mine = AudioEnvelope(listOf(AudioEnvelopeSegment(0.0, 10.0, owner = "a", volume = 0.5)), live)
        val other = AudioEnvelope(listOf(AudioEnvelopeSegment(0.0, 10.0, owner = "b", volume = 0.5)), live)
        val gains = DoubleArray(480)
        val untouched = DoubleArray(480)
        mine.fill(1.0, 480, rate, gains)
        assertTrue(gains.all { it == 0.5 })

        val target = AudioLevels.linear(-6.0)
        live.update(-6.0, selection)
        mine.fill(2.0, 480, rate, gains); other.fill(2.0, 480, rate, untouched)
        // scale goes 1 → 0 and offset 0 → target, one 480th of what is left per frame.
        assertEquals(0.5 * (479.0 / 480) + target / 480, gains[0], tiny)
        assertEquals(0.5 * 0.5 + target * 0.5, gains[239], tiny)
        assertEquals(target, gains[479], tiny)
        assertTrue(untouched.all { it == 0.5 })
        mine.fill(2.01, 480, rate, gains)
        assertTrue(gains.all { abs(it - target) < tiny })

        // Letting go ramps back to the saved level.
        live.update(null, AudioGainSelection())
        mine.fill(3.0, 480, rate, gains)
        assertEquals(0.5 / 480 + target * (479.0 / 480), gains[0], tiny)
        assertEquals(0.5, gains[479], tiny)

        // A processor made while a level is being tried starts on it, CutAudioEnvelope.swift:167-168.
        live.update(3.0, selection)
        assertEquals(AudioLevels.linear(3.0), AudioEnvelope(listOf(AudioEnvelopeSegment(0.0, 10.0, owner = "a")), live).gain(5.0), tiny)
        assertEquals(3, live.snapshot().revision)
    }

    @Test fun theClockKeepsBuffersWhoseTimeIsMissing() {
        // Tests/VideoEffectIntegrationTests.swift:558-562.
        val clock = AudioEnvelopeClock()
        val first = clock.start(2.0, 480, rate, 0.0)
        val missing = clock.start(Double.NaN, 480, rate, 0.0)
        val sought = clock.start(5.0, 480, rate, 0.0)
        assertEquals(2.0, first, 0.0)
        assertEquals(2.01, missing, 1e-9)
        assertEquals(5.0, sought, 0.0)
        // CutAudioEnvelope.swift:63: with nothing before, the fallback.
        assertEquals(7.0, AudioEnvelopeClock().start(Double.NaN, 480, rate, 7.0), 0.0)
    }
}
