package app.takes.core

import app.takes.core.CoverFramePool.Interval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CoverFramePoolTest {
    // VideoToolsTests.swift:324: two overlapping layers and a later clip, with a gap between.
    private val footage = listOf(Interval(0.0, 1.0), Interval(0.5, 2.0), Interval(20.0, 21.0))

    // VideoToolsTests.swift:325-326.
    @Test fun overlappingPictureCountsOnceAndGapsAreSkipped() {
        val pool = CoverFramePool(footage, listOf(Interval(1.0, 2.0), Interval(12.0, 20.5)))
        // Thirty pictures in 1…2 s and fifteen in 20…20.5 s, on the thirtieth-of-a-second grid.
        assertEquals(45, pool.count)
        assertEquals(1.0, pool.time(0), 0.0)
        assertEquals(1.0 + 29.0 / 30, pool.time(29), 1e-12)
        assertEquals(20.0, pool.time(30), 0.0)
        assertEquals(20.0 + 14.0 / 30, pool.time(44), 1e-12)
        assertThrows(IndexOutOfBoundsException::class.java) { pool.time(45) }
    }

    // VideoToolsTests.swift:327-337: a hundred updates, each replacing all nine choices.
    @Test fun nineOrderedChoicesAreAllReplacedByAnUpdate() {
        val pool = CoverFramePool(footage, listOf(Interval(1.0, 2.0), Interval(12.0, 20.5)))
        val random = Random(161)
        var previous = emptyList<Int>()
        repeat(100) {
            val picks = pool.picks(previous, random)
            assertEquals(9, picks.size)
            assertTrue("Ordered", picks.zipWithNext().all { (a, b) -> a < b })
            assertTrue("Every choice is replaced", previous.zip(picks).all { (a, b) -> a != b })
            picks.forEachIndexed { band, pick ->
                assertTrue("One from each ninth", pick in band * 5 until (band + 1) * 5)
                val time = pool.time(pick)
                assertTrue("Only selected footage, before its end", (time >= 1.0 && time < 2.0) || (time >= 20.0 && time < 20.5))
            }
            previous = picks
        }
    }

    // CoverFramePool.swift:39-46: over many updates every picture of a band is offered.
    @Test fun everyPictureCanBeChosen() {
        val pool = CoverFramePool(footage, listOf(Interval(1.0, 2.0), Interval(12.0, 20.5)))
        val random = Random(7)
        val seen = mutableSetOf<Int>()
        var previous = emptyList<Int>()
        repeat(400) {
            previous = pool.picks(previous, random)
            seen += previous
        }
        assertEquals((0 until 45).toSet(), seen)
    }

    // VideoToolsTests.swift:339-340.
    @Test fun aRangeOfSixPicturesShowsAllSix() {
        val short = CoverFramePool(footage, listOf(Interval(0.0, 0.2)))
        assertEquals(6, short.count)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), short.picks(emptyList(), Random(1)))
        // Nothing else to offer: an update shows the same six (CoverFramePool.swift:43).
        assertEquals(listOf(0, 1, 2, 3, 4, 5), short.picks(listOf(0, 1, 2, 3, 4, 5), Random(2)))
    }

    // CoverFramePool.swift:40: nine bands at most; ten pictures spread over them, one band holding two.
    @Test fun tenPicturesStillGiveNineChoices() {
        val pool = CoverFramePool(footage, listOf(Interval(0.0, 1.0 / 3)))
        assertEquals(10, pool.count)
        val random = Random(3)
        repeat(50) {
            val picks = pool.picks(emptyList(), random)
            assertEquals(9, picks.size)
            assertTrue(picks.zipWithNext().all { (a, b) -> a < b })
        }
    }

    // VideoToolsTests.swift:341-342 and CoverFramePool.swift:19-21: a range between two grid points still has its own picture.
    @Test fun aSubframeSelectionStaysInsideItsRange() {
        val subframe = CoverFramePool(footage, listOf(Interval(0.040, 0.045)))
        assertEquals(1, subframe.count)
        assertEquals(9_600L, subframe.ticks(0))
        assertEquals(0.040, subframe.time(0), 0.0)
        assertEquals(listOf(0), subframe.picks())
    }

    // CoverFramePool.swift:19-21: a range that starts off the grid begins at the next grid point.
    @Test fun picturesLieOnTheThirtiethOfASecondGrid() {
        val pool = CoverFramePool(footage, listOf(Interval(0.05, 0.2)))
        // 0.05 s is between the grid points 1/30 and 2/30; the end, 0.2 s, is not part of the range.
        assertEquals(4, pool.count)
        assertEquals(listOf(16_000L, 24_000L, 32_000L, 40_000L), (0 until 4).map(pool::ticks))
    }

    // VideoToolsTests.swift:343-344.
    @Test fun aGapHasNoCandidates() {
        val empty = CoverFramePool(footage, listOf(Interval(5.0, 7.0)))
        assertEquals(0, empty.count)
        assertEquals(emptyList<Int>(), empty.picks())
        assertEquals(0, CoverFramePool(emptyList(), listOf(Interval(0.0, 10.0))).count)
        assertEquals(0, CoverFramePool(footage, emptyList()).count)
    }

    // CoverFramePool.swift:29-36: unordered, touching, overlapping and empty ranges are tidied first.
    @Test fun scopesMayOverlapAndComeInAnyOrder() {
        val tidy = CoverFramePool(footage, listOf(Interval(0.0, 2.0)))
        val messy = CoverFramePool(footage, listOf(Interval(1.0, 2.0), Interval(0.0, 0.5), Interval(0.5, 1.2), Interval(1.5, 1.5), Interval(3.0, 2.5)))
        assertEquals(60, tidy.count)
        assertEquals(tidy.count, messy.count)
        assertEquals((0 until 60).map(tidy::ticks), (0 until 60).map(messy::ticks))
    }

    // CoverPicker.swift:31 and 34-40: the whole project as scope, over the picture of the clips.
    @Test fun theClipsOfAnEditGiveThePicture() {
        val clips = listOf(
            TimelineClip(sourceID = "A", start = 1.0, end = 3.0),
            TimelineClip(sourceID = "B", start = 0.0, end = 1.5),
        )
        assertEquals(listOf(Interval(0.0, 2.0), Interval(2.0, 3.5)), CoverFramePool.picture(clips))
        assertEquals(listOf(Interval(0.0, 3.5)), CoverFramePool.whole(clips))
        val pool = CoverFramePool(CoverFramePool.picture(clips), CoverFramePool.whole(clips))
        assertEquals(105, pool.count)
        assertEquals(3.5 - 1.0 / 30, pool.time(104), 1e-12)
        assertEquals(0, CoverFramePool(CoverFramePool.picture(emptyList()), CoverFramePool.whole(emptyList())).count)
    }

    // VideoToolsTests.swift:340-344: Update in the detail always finds another frame of the pool.
    @Test fun theDetailsUpdateChoosesAnotherFrame() {
        val short = CoverFramePool(footage, listOf(Interval(0.0, 0.2)))
        val random = Random(7)
        for (pick in 0 until short.count) repeat(20) {
            val next = short.randomAlternative(pick, random)
            assertTrue(next in 0 until short.count && next != pick)
        }
        // VideoToolsTests.swift:349-351: a one-frame range keeps its only frame.
        val subframe = CoverFramePool(footage, listOf(Interval(0.040, 0.045)))
        assertEquals(1, subframe.count)
        assertEquals(0, subframe.randomAlternative(0))
    }

    // VideoToolsTests.swift:345-348: the frames follow the composition's clock and skip gaps.
    @Test fun framesFollowTheCompositionsClock() {
        val faster = CoverFramePool(footage, listOf(Interval(0.0, 0.1), Interval(20.0, 20.05)), frameDuration = 1.0 / 60)
        assertEquals(9, faster.count)
        assertEquals(1.0 / 60, faster.time(1), 1e-12)
        assertEquals(20.0, faster.time(6), 0.0)
        // A clock that says nothing usable is the thirtieth of a second.
        assertEquals(CoverFramePool(footage, listOf(Interval(0.0, 1.0))).count, CoverFramePool(footage, listOf(Interval(0.0, 1.0)), Double.NaN).count)
    }
}
