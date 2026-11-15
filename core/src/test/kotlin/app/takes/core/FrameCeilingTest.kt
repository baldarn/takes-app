package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameCeilingTest {
    /** The frames of [count] at [rate] frames per second that a ceiling of [ceiling] keeps, by their number. */
    private fun kept(rate: Double, count: Int, ceiling: Double = 30.0): List<Int> {
        val frames = FrameCeiling(ceiling)
        // Times as a container gives them: cut to whole microseconds.
        return (0 until count).filter { frames.keeps((it * 1_000_000.0 / rate).toLong()) }
    }

    @Test
    fun footageAtTheCeilingKeepsEveryFrameIncludingTheLast() {
        assertEquals((0 until 120).toList(), kept(30.0, 120))
        assertEquals((0 until 97).toList(), kept(29.97, 97))
    }

    @Test
    fun footageBelowTheCeilingKeepsEveryFrame() {
        assertEquals((0 until 48).toList(), kept(24.0, 48))
        assertEquals((0 until 50).toList(), kept(25.0, 50))
    }

    @Test
    fun footageAtTwiceTheCeilingKeepsEveryOtherFrame() {
        assertEquals((0 until 120 step 2).toList(), kept(60.0, 120))
        assertEquals((0 until 120 step 2).toList(), kept(120.0, 120, 60.0))
    }

    @Test
    fun footageAtFourTimesTheCeilingKeepsEveryFourthFrame() {
        assertEquals((0 until 120 step 4).toList(), kept(120.0, 120))
    }

    @Test
    fun fiftyFramesASecondBecomeTwentyFive() {
        // No whole number of 50ths makes a 30th: the nearest is every other frame, as Media3's own limit chooses.
        assertEquals((0 until 50 step 2).toList(), kept(50.0, 50))
    }

    @Test
    fun unevenFrameTimesAroundTheCeilingAreAllKept() {
        val frames = FrameCeiling(30.0)
        val times = listOf(0L, 28_000, 66_000, 95_000, 133_333, 170_000, 199_000)
        assertEquals(times, times.filter { frames.keeps(it) })
    }

    @Test
    fun everyClipStartsAgain() {
        val frames = FrameCeiling(30.0)
        assertEquals(listOf(true, false, true), listOf(0L, 16_666, 33_333).map { frames.keeps(it) })
        frames.reset()
        assertEquals(listOf(true, false, true), listOf(5_000_000L, 5_016_666, 5_033_333).map { frames.keeps(it) })
    }
}
