package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recorder's microphone meter.
 *
 * The original has no test for it; these are the port's own, from
 * YouRec/CaptureController.swift:2664-2669, 2676, 2836 and 2875-2878,
 * YouRec/RecorderView.swift:925-931 and 1364-1365, and
 * YouRec/LiveMicrophone.swift:44.
 */
class LevelMeterTest {
    // MARK: Decibels

    @Test fun aPeakIsReadInDecibelsBelowFullScale() {
        assertEquals(0.0, LevelMeter.decibels(1.0), 0.0)
        assertEquals(-6.0206, LevelMeter.decibels(0.5), 1e-4)
        assertEquals(-20.0, LevelMeter.decibels(0.1), 1e-12)
        assertEquals(-60.0, LevelMeter.decibels(0.001), 1e-12)
        // Anything quieter than a hundred-millionth, and silence itself, is −160.
        assertEquals(-160.0, LevelMeter.decibels(0.0), 1e-12)
        assertEquals(-160.0, LevelMeter.decibels(1e-12), 1e-12)
        assertEquals(LevelMeter.SILENCE, LevelMeter.decibels(LevelMeter.QUIETEST_PEAK), 1e-12)
    }

    // MARK: Attack and release

    @Test fun theLevelRisesAtOnceAndFallsADecibelAndAHalfPerBuffer() {
        var meter = LevelMeter()
        assertEquals(-160.0, meter.level, 0.0)
        meter = meter.heard(0.1, 0.0)
        assertEquals(-20.0, meter.level, 1e-12)
        // Quieter buffers: the level comes down by 1.5 dB each, not to their own level.
        meter = meter.heard(0.001, 0.02)
        assertEquals(-21.5, meter.level, 1e-12)
        meter = meter.heard(0.0, 0.04)
        assertEquals(-23.0, meter.level, 1e-12)
        // A louder buffer is shown in full straight away.
        meter = meter.heard(0.5, 0.06)
        assertEquals(LevelMeter.decibels(0.5), meter.level, 0.0)
        // A negative sample is as loud as a positive one.
        assertEquals(-20.0, LevelMeter().heard(-0.1, 0.0).level, 1e-12)
        // The fall stops at the buffers' own level.
        meter = LevelMeter().heard(1.0, 0.0)
        repeat(40) { meter = meter.heard(0.01, 0.0) }
        assertEquals(-40.0, meter.level, 1e-9)
    }

    @Test fun theOriginalsBufferFallsSeventyDecibelsASecond() {
        // 1024 frames at 48 kHz last 21.3 ms: 46.875 buffers a second, 1.5 dB each.
        assertEquals(70.3125, LevelMeter.releaseOver(1.0), 1e-12)
        assertEquals(LevelMeter.RELEASE, LevelMeter.releaseOver(1024.0 / 48_000), 1e-12)
        assertEquals(0.0, LevelMeter.releaseOver(-1.0), 0.0)
        // A source that reports ten times a second falls as far in one report as the original in 4.7 buffers.
        val meter = LevelMeter().heard(1.0, 0.0).heard(0.0, 0.1, LevelMeter.releaseOver(0.1))
        assertEquals(-7.03125, meter.level, 1e-12)
    }

    // MARK: Clipping and no signal

    @Test fun clippingShowsForAThirdOfASecondAfterAPeak() {
        var meter = LevelMeter().heard(0.987, 1.0)
        assertFalse(meter.reading(1.0).clipping)
        meter = meter.heard(0.988, 2.0)
        assertTrue(meter.reading(2.0).clipping)
        // Quieter buffers do not take the mark away early.
        meter = meter.heard(0.2, 2.2)
        assertTrue(meter.reading(2.34).clipping)
        assertFalse(meter.reading(2.35).clipping)
        assertTrue(LevelMeter().heard(-1.0, 0.0).reading(0.1).clipping)
        assertEquals(LevelMeter.Label.Clipping, meter.reading(2.3).label)
        assertEquals(LevelMeter.Label.Level, meter.reading(2.5).label)
    }

    @Test fun noSignalShowsAfterEightTenthsOfASecondWithoutSound() {
        val never = LevelMeter().reading(100.0)
        assertFalse(never.arriving)
        assertEquals(-160.0, never.level, 0.0)
        assertEquals(LevelMeter.Label.NoSignal, never.label)
        val meter = LevelMeter().heard(0.5, 10.0)
        assertTrue(meter.reading(10.79).arriving)
        assertEquals(LevelMeter.decibels(0.5), meter.reading(10.79).level, 0.0)
        // The held level is not shown once sound stops arriving.
        val silent = meter.reading(10.8)
        assertFalse(silent.arriving)
        assertEquals(-160.0, silent.level, 0.0)
        assertEquals(0, silent.bars)
        // Digital silence that keeps arriving is a signal: the microphone is there.
        assertTrue(LevelMeter().heard(0.0, 5.0).reading(5.1).arriving)
    }

    @Test fun aChangeOfGainClearsTheLevelAndTheClippingMark() {
        val meter = LevelMeter().heard(1.0, 3.0).cleared()
        assertEquals(-160.0, meter.level, 0.0)
        assertFalse(meter.reading(3.1).clipping)
        // Sound was still arriving.
        assertTrue(meter.reading(3.1).arriving)
    }

    @Test fun aTakeIsPausedAfterASecondWithoutSound() {
        val meter = LevelMeter().heard(0.2, 20.0)
        assertFalse(meter.shouldPause(now = 21.0, requestedAt = 10.0))
        assertTrue(meter.shouldPause(now = 21.01, requestedAt = 10.0))
        // Not in the first second of a take, when the microphone may not have delivered yet.
        assertFalse(LevelMeter().shouldPause(now = 30.5, requestedAt = 30.0))
        assertTrue(LevelMeter().shouldPause(now = 31.1, requestedAt = 30.0))
        // A microphone that is restarting gets three seconds.
        assertFalse(meter.shouldPause(now = 23.0, requestedAt = 10.0, restarting = 20.5))
        assertTrue(meter.shouldPause(now = 23.5, requestedAt = 10.0, restarting = 20.5))
    }

    // MARK: What is drawn

    @Test fun sevenBarsLightFromMinusFiftyFourInStepsOfEight() {
        assertEquals(listOf(-54.0, -46.0, -38.0, -30.0, -22.0, -14.0, -6.0), (0 until LevelMeter.BARS).map { LevelMeter.barThreshold(it) })
        assertEquals(0, LevelMeter.bars(-160.0))
        // A bar lights above its threshold, not at it.
        assertEquals(0, LevelMeter.bars(-54.0))
        assertEquals(1, LevelMeter.bars(-53.9))
        assertEquals(4, LevelMeter.bars(-25.0))
        assertEquals(6, LevelMeter.bars(-6.0))
        assertEquals(7, LevelMeter.bars(-5.9))
        assertEquals(7, LevelMeter.bars(3.0))
        assertEquals(listOf(false, false, false, false, false, false, true), (0 until LevelMeter.BARS).map { LevelMeter.isRed(it) })
        assertEquals(5, LevelMeter().heard(0.1, 0.0).reading(0.0).bars)
    }

    @Test fun theGainPanelShowsSixtyDecibelsAndWholeNumbers() {
        assertEquals(0.0, LevelMeter.fraction(-160.0), 0.0)
        assertEquals(0.0, LevelMeter.fraction(-60.0), 0.0)
        assertEquals(0.5, LevelMeter.fraction(-30.0), 1e-12)
        assertEquals(1.0, LevelMeter.fraction(0.0), 0.0)
        assertEquals(1.0, LevelMeter.fraction(4.0), 0.0)
        assertEquals("-18 dBFS", LevelMeter.format(-18.2))
        assertEquals("-160 dBFS", LevelMeter.format(-160.0))
        assertEquals(0.5, LevelMeter().heard(0.0316227766, 0.0).reading(0.0).fraction, 1e-6)
    }
}
