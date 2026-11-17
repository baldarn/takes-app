package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Where a segment's movie stands on the recorder's clocks (C/MovieClock.kt). The original's writer gives its frames
 * the movie's times (YouRec/VideoEffects/LiveVideoEffects.swift:354-358, 641-646); here they are found from the
 * recorder's reports of the length written, with the numbers of a take on a Galaxy A54: the recorder's Start 84.5 ms
 * before the movie's first frame, a report at the earliest 102 ms after the frame it counts, the preview drawing a
 * frame about 100 ms after it is taken, the camera's frames 33.29 to 33.35 ms apart.
 */
class MovieClockTest {
    private val origin = 199_035_293_955_628L
    private val first = origin + 84_501_423L

    /** The camera's frames from half a second before the Start, 33.3 ms apart give or take 30 µs. */
    private fun frames(count: Int = 200, jitter: Long = 30_000L, seed: Int = 1): List<Long> {
        val random = Random(seed)
        val times = mutableListOf<Long>()
        var t = 0L
        repeat(count) {
            times += t
            t += 33_333_333L + if (jitter == 0L) 0L else random.nextLong(-jitter, jitter + 1)
        }
        // The first frame is the 16th the camera took.
        return times.map { it - times[15] + first }
    }

    /** Feeds [clock] the camera's [times] and the recorder's report of each frame of the movie, as they come. */
    private fun record(clock: MovieClock, times: List<Long>, firstIndex: Int = 15, latency: (Int) -> Long = { 102_000_000L + it % 5 * 9_000_000L }) {
        clock.start(origin)
        var reported = 0
        for ((i, t) in times.withIndex()) {
            clock.captured(t)
            // The reports of the frames taken a tenth of a second ago or more, in microseconds as the recorder counts.
            while (firstIndex + reported < times.size && times[firstIndex + reported] + latency(reported) <= t) {
                val frame = times[firstIndex + reported]
                clock.reported(frame + latency(reported), (frame / 1000 - times[firstIndex] / 1000) * 1000)
                reported++
            }
            if (i % 3 == 0) clock.drawn(t, t + 100_000_000L + i % 7 * 1_000_000L)
        }
    }

    @Test fun findsTheFirstFrameAmongTheCamerasFrames() {
        val clock = MovieClock()
        record(clock, frames())
        assertTrue(clock.measured)
        assertEquals(first, clock.firstFrame)
        assertEquals(0.084501423, clock.takenLead, 1e-9)
        // And the preview's drawing: the median of the latest delays, 103 ms.
        assertEquals(0.084501423 + 0.103, clock.drawnLead, 1e-9)
    }

    @Test fun findsItWhateverTheJitter() {
        for (seed in 1..20) {
            val clock = MovieClock()
            record(clock, frames(seed = seed, jitter = 5_000L))
            assertEquals("seed $seed", first, clock.firstFrame)
        }
    }

    // With no times of the camera's frames, or frames evenly spaced, the first frame is the earliest the reports
    // allow less 90 ms, as before: 12 ms late here.
    @Test fun withoutTheFramesTheReportsBoundIt() {
        val bare = MovieClock()
        bare.start(origin)
        for ((i, t) in frames().drop(15).withIndex()) bare.reported(t + 102_000_000L + i % 5 * 9_000_000L, t - first)
        assertFalse(bare.measured)
        assertEquals(first + 12_000_000L, bare.firstFrame)
        assertEquals(0.096501423, bare.takenLead, 1e-9)

        val even = MovieClock()
        record(even, frames(jitter = 0L))
        assertFalse(even.measured)
        assertEquals((first + 12_000_000L).toDouble(), even.firstFrame!!.toDouble(), 1_000.0)
    }

    // The camera's frames on another clock than the recorder's reports: none of them can be the first.
    @Test fun framesOnAnotherClockAreNotTaken() {
        val clock = MovieClock()
        clock.start(origin)
        val times = frames()
        for (t in times) clock.captured(t - 7_000_000_000L)
        for ((i, t) in times.drop(15).withIndex()) clock.reported(t + 102_000_000L + i % 5 * 9_000_000L, t / 1000 * 1000 - first / 1000 * 1000)
        assertFalse(clock.measured)
        assertEquals((first + 12_000_000L).toDouble(), clock.firstFrame!!.toDouble(), 1_000.0)
    }

    @Test fun nothingBeforeTheStart() {
        val clock = MovieClock()
        clock.reported(origin + 500_000_000L, 100_000_000L)
        assertEquals(0.0, clock.takenLead, 0.0)
        assertEquals(0.0, clock.drawnLead, 0.0)
    }
}
