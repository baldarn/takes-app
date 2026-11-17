package app.takes.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * The checkpoint of a measurement stopped part way, from the original's
 * VideoStabilizer.measure (YouRec/VideoStabilizer.swift:119-206): what it
 * keeps (:191-196), what it accepts again (:134-141), where it reads again
 * (:144-146) and when it writes (:149, :197). The original has no test of its
 * own for it; these replay a measurement stopped and resumed against the same
 * one run through.
 */
class StabilizationCheckpointTest {
    private val width = 1920
    private val height = 1080
    private val duration = 12.0

    /** The frames of a 30 fps stretch with a shake, one lost to tracking and one late, as the measurement sees them. */
    private val frames: List<Pair<Double, StabilizationMeasure.Motion?>> = (0 until 360).map { index ->
        val time = index / 30.0 + if (index >= 200) 0.2 else 0.0 // a gap of more than 0.15 s before frame 200
        time to if (index == 0) null else StabilizationMeasure.Motion(sin(index * 0.7) * 2, sin(index * 0.3), 0.001 * sin(index * 0.5), 0.0002, valid = index != 120)
    }.filter { it.first < duration }

    private fun measured(from: StabilizationMeasure, frames: List<Pair<Double, StabilizationMeasure.Motion?>>): StabilizationMeasure {
        for ((time, motion) in frames) if (from.accepts(time)) from.add(time, motion)
        return from
    }

    @Test fun aResumedMeasurementMakesTheSamePath() {
        val whole = measured(StabilizationMeasure(width, height, duration), frames).analysis()!!
        val stop = frames.indexOfFirst { it.first >= StabilizationCheckpoint.first(-1.0) }
        val part = measured(StabilizationMeasure(width, height, duration), frames.take(stop + 1))
        val frame = ByteArray(part.analysisWidth * part.analysisHeight) { it.toByte() }
        val kept = StabilizationCheckpoint.decode(part.checkpoint(frame)!!.encoded())!!
        assertTrue(kept.resumes(width, height, duration, part.analysisWidth, part.analysisHeight))
        assertArrayEquals(frame, kept.previousFrame)
        assertEquals(frames[stop].first, kept.timestamp, 0.0)
        // Reading again from a quarter of a second before: the frames up to the one kept are skipped (:144-146, :159).
        val from = StabilizationCheckpoint.resumeFrom(kept.timestamp)
        assertEquals(kept.timestamp - 0.25, from, 1e-12)
        val resumed = measured(StabilizationMeasure.resuming(kept), frames.filter { it.first >= from })
        assertEquals(whole, resumed.analysis())
    }

    @Test fun aCheckpointOfAnotherStretchOrRecordingIsNotResumed() {
        val part = measured(StabilizationMeasure(width, height, duration), frames.take(200))
        val kept = part.checkpoint(ByteArray(part.analysisWidth * part.analysisHeight))!!
        assertFalse(kept.resumes(1280, height, duration, part.analysisWidth, part.analysisHeight))
        assertFalse(kept.resumes(width, height, duration + 0.5, part.analysisWidth, part.analysisHeight))
        assertFalse(kept.resumes(width, height, duration, part.analysisWidth, part.analysisHeight + 1))
        // Its last frame must be the one kept.
        assertFalse(StabilizationCheckpoint(kept.path, kept.previousFrame, kept.timestamp - 0.01).resumes(width, height, duration, part.analysisWidth, part.analysisHeight))
        assertFalse(StabilizationCheckpoint(kept.path.copy(samples = emptyList()), kept.previousFrame, kept.timestamp).resumes(width, height, duration, part.analysisWidth, part.analysisHeight))
    }

    @Test fun anythingElseIsNoCheckpoint() {
        assertNull(StabilizationCheckpoint.decode(ByteArray(0)))
        assertNull(StabilizationCheckpoint.decode("TKSC not a checkpoint".toByteArray()))
        val part = measured(StabilizationMeasure(width, height, duration), frames.take(10))
        val bytes = part.checkpoint(ByteArray(16))!!.encoded()
        assertNull(StabilizationCheckpoint.decode(bytes.copyOf(bytes.size - 3)))
    }

    /** VideoStabilizer.swift:149, :197: five seconds after where it begins, then every 5 s growing to every 30 s. */
    @Test fun checkpointsAreWrittenEveryFiveSecondsGrowingToThirty() {
        assertEquals(5.0, StabilizationCheckpoint.first(Double.NEGATIVE_INFINITY), 0.0)
        assertEquals(47.0, StabilizationCheckpoint.first(42.0), 0.0)
        assertEquals(10.0, StabilizationCheckpoint.next(5.0), 0.0)
        assertEquals(240.0 + 10.0, StabilizationCheckpoint.next(240.0), 0.0)
        assertEquals(1200.0 + 30.0, StabilizationCheckpoint.next(1200.0), 0.0)
        assertEquals(0.0, StabilizationCheckpoint.resumeFrom(0.1), 0.0)
    }
}
