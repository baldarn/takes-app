package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Free storage before a segment and during a take.
 *
 * The test marked TRANSLATED carries over the "Storage reserve bounds" and
 * "Refuse an allocation that uses the safety margin" expectations of the
 * iPhone app's Tests/ProjectRecoveryTests.swift:145-153. The others are this
 * port's own, from YouRec/ProjectRecovery.swift:421-423 and 452-464 and
 * YouRec/CaptureController.swift:1401-1403, 1430-1431 and 2682-2692.
 */
class RecordingSpaceTest {
    private val original = RecordingSpace.ORIGINAL
    private val flat = RecordingSpace.ANDROID_FLAT

    // MARK: The original's numbers

    /** TRANSLATED: Tests/ProjectRecoveryTests.swift:145-153. */
    @Test fun storageReserveBounds() {
        assertEquals(512_000_000L, original.floor(16_000_000_000))
        assertEquals(2_000_000_000L, original.floor(256_000_000_000))
        // An allocation of everything that is free uses the safety margin and is refused.
        val free = 40_000_000_000L
        val total = 128_000_000_000L
        assertTrue(free > original.room(free, total))
        assertEquals(free - 2_000_000_000L, original.room(free, total))
    }

    @Test fun theFloorIsTwoPercentBetweenHalfAGigabyteAndTwo() {
        assertEquals(512_000_000L, original.floor(0))
        assertEquals(512_000_000L, original.floor(25_600_000_000))
        assertEquals(640_000_000L, original.floor(32_000_000_000))
        assertEquals(1_280_000_000L, original.floor(64_000_000_000))
        assertEquals(2_000_000_000L, original.floor(100_000_000_000))
        assertEquals(2_000_000_000L, original.floor(128_000_000_000))
    }

    @Test fun aSegmentStartsWithSeventyFiveMegabytesAboveTheFloorAndAHundredAndSeventyIn4K() {
        val total = 128_000_000_000L
        // On a 128 GB phone the original records at 1080p from 2.075 GB free.
        assertFalse(original.canStart(2_074_999_999, total, ultraHD = false))
        assertTrue(original.canStart(2_075_000_000, total, ultraHD = false))
        assertFalse(original.canStart(2_169_999_999, total, ultraHD = true))
        assertTrue(original.canStart(2_170_000_000, total, ultraHD = true))
        assertEquals(1L, original.shortBy(2_074_999_999, total, ultraHD = false))
        assertEquals(0L, original.shortBy(2_075_000_000, total, ultraHD = false))
        assertEquals(0L, original.shortBy(50_000_000_000, total, ultraHD = true))
        // With the floor already used, what is missing is the floor's share too.
        assertEquals(1_075_000_000L, original.shortBy(1_000_000_000, total, ultraHD = false))
        assertEquals(-1_000_000_000L, original.room(1_000_000_000, total))
    }

    @Test fun aTakeIsPausedUnderThirtyTwoMegabytesPlusFifteenSecondsAboveTheFloor() {
        val total = 128_000_000_000L
        // Before anything is measured the pace is taken as 2 MB a second at 1080p and 7 in 4K.
        val hd = original.begin(now = 100.0, ultraHD = false)
        assertEquals(RecordingSpace.Pace(100.0, 0, 2_000_000.0), hd)
        assertEquals(7_000_000.0, original.begin(100.0, ultraHD = true).bytesPerSecond, 0.0)
        assertEquals(62_000_000.0, original.pauseBelow(hd.bytesPerSecond), 0.0)
        assertEquals(137_000_000.0, original.pauseBelow(7_000_000.0), 0.0)
        assertFalse(original.shouldPause(2_062_000_000, total, hd.bytesPerSecond))
        assertTrue(original.shouldPause(2_061_999_999, total, hd.bytesPerSecond))
        assertTrue(original.shouldPause(2_136_999_999, total, 7_000_000.0))
        assertFalse(original.shouldPause(2_137_000_000, total, 7_000_000.0))
        // A storage that cannot be read pauses the take.
        assertTrue(original.shouldPause(null, total, 0.0))
    }

    @Test fun thePaceIsTheFastestSeenAndNeverLessThanTheAssumedOne() {
        var pace = original.begin(now = 100.0, ultraHD = false)
        // 1080p at 12.4 Mbit/s writes 1.55 MB a second: slower than assumed, the assumption stays.
        pace = pace.sampled(105.0, 7_750_000)
        assertEquals(RecordingSpace.Pace(105.0, 7_750_000, 2_000_000.0), pace)
        // A busy stretch of 3 MB a second raises it, and a calm one after does not lower it again.
        pace = pace.sampled(110.0, 22_750_000)
        assertEquals(3_000_000.0, pace.bytesPerSecond, 0.0)
        pace = pace.sampled(115.0, 23_750_000)
        assertEquals(RecordingSpace.Pace(115.0, 23_750_000, 3_000_000.0), pace)
        assertEquals(77_000_000.0, original.pauseBelow(pace.bytesPerSecond), 0.0)
        // A clock that did not move gives no pace; a file that shrank counts as no growth.
        assertEquals(pace, pace.sampled(115.0, 99_000_000))
        assertEquals(RecordingSpace.Pace(120.0, 0, 3_000_000.0), pace.sampled(120.0, 0))
    }

    @Test fun theStorageIsLookedAtEveryFiveSeconds() {
        assertTrue(original.isDue(now = 3.0, lastCheck = Double.NEGATIVE_INFINITY))
        assertFalse(original.isDue(now = 15.0, lastCheck = 10.0))
        assertTrue(original.isDue(now = 15.1, lastCheck = 10.0))
    }

    // MARK: The flat figures recommended for Android

    @Test fun theFlatFiguresKeepNoFloorAndAskForThreeHundredMebibytesOrSix() {
        val total = 128_000_000_000L
        assertEquals(0L, flat.floor(total))
        assertEquals(0L, flat.floor(0))
        assertEquals(314_572_800L, flat.startBytes(ultraHD = false))
        assertEquals(629_145_600L, flat.startBytes(ultraHD = true))
        assertTrue(flat.canStart(314_572_800, total, ultraHD = false))
        assertFalse(flat.canStart(314_572_799, total, ultraHD = false))
        assertFalse(flat.canStart(629_145_599, total, ultraHD = true))
        assertTrue(flat.canStart(629_145_600, total, ultraHD = true))
        // A phone that is simply fairly full records: 1 GB free on 128 GB, which the original refuses.
        assertTrue(flat.canStart(1_000_000_000, total, ultraHD = true))
        assertFalse(original.canStart(1_000_000_000, total, ultraHD = false))
    }

    @Test fun theFlatFiguresPauseATakeAsTheOriginalDoesCountedFromEmpty() {
        val total = 128_000_000_000L
        val pace = flat.begin(now = 0.0, ultraHD = false)
        assertEquals(2_000_000.0, pace.bytesPerSecond, 0.0)
        assertFalse(flat.shouldPause(62_000_000, total, pace.bytesPerSecond))
        assertTrue(flat.shouldPause(61_999_999, total, pace.bytesPerSecond))
        assertTrue(flat.shouldPause(136_999_999, total, flat.begin(0.0, ultraHD = true).bytesPerSecond))
        assertEquals(original.checkInterval, flat.checkInterval, 0.0)
    }

    @Test fun theNumbersAreParametersOfOneRule() {
        // The same rule with a fixed floor, here the 50 MiB under which CameraX ends a recording by itself.
        val guarded = flat.copy(floorMinimum = 52_428_800, floorMaximum = 52_428_800)
        assertEquals(52_428_800L, guarded.floor(128_000_000_000))
        assertTrue(guarded.shouldPause(52_428_800L + 61_999_999, 128_000_000_000, 2_000_000.0))
        assertFalse(guarded.shouldPause(52_428_800L + 62_000_000, 128_000_000_000, 2_000_000.0))
        assertEquals(4_194_304L, RecordingSpace.RESERVE_BYTES)
    }
}
