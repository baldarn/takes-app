package app.takes.core

import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * A measurement of a camera path stopped part way, as the original keeps it
 * (YouRec/VideoStabilizer.swift:9-13): the path so far, the last frame
 * measured at the analysis size, which the next one is compared with, and
 * that frame's time from the stretch's start. Written beside the path it
 * will become while a saved edit's stabilisation is measured, so that an
 * interrupted measurement goes on where it stopped (:52-70, :134-149, :191-197).
 *
 * The original writes it as a binary property list; this port writes its own
 * bytes ([encoded]), a file no other app reads.
 */
class StabilizationCheckpoint(val path: StabilizationAnalysis, val previousFrame: ByteArray, val timestamp: Double) {
    /**
     * Whether a measurement of a recording encoded at [width] × [height],
     * over a stretch of [duration] seconds and compared at [analysisWidth] ×
     * [analysisHeight], goes on from here: the same recording and stretch, a
     * valid path whose last frame is the one kept (:134-141).
     */
    fun resumes(width: Int, height: Int, duration: Double, analysisWidth: Int, analysisHeight: Int): Boolean =
        path.width == width && path.height == height && path.duration == duration && path.valid &&
            path.samples.lastOrNull()?.time == timestamp && previousFrame.size == analysisWidth * analysisHeight

    fun encoded(): ByteArray {
        val json = path.encoded()
        return ByteBuffer.allocate(MAGIC.size + 4 + 8 + 4 + previousFrame.size + json.size)
            .put(MAGIC).putInt(FORMAT).putDouble(timestamp).putInt(previousFrame.size).put(previousFrame).put(json).array()
    }

    companion object {
        private val MAGIC = "TKSC".toByteArray()
        private const val FORMAT = 1

        /** A checkpoint from its bytes; null for anything else. */
        fun decode(bytes: ByteArray): StabilizationCheckpoint? = runCatching {
            val buffer = ByteBuffer.wrap(bytes)
            val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
            if (!magic.contentEquals(MAGIC) || buffer.getInt() != FORMAT) return null
            val timestamp = buffer.getDouble()
            val frame = buffer.getInt()
            if (frame < 0 || frame > buffer.remaining()) return null
            val previous = ByteArray(frame).also { buffer.get(it) }
            val json = ByteArray(buffer.remaining()).also { buffer.get(it) }
            StabilizationCheckpoint(StabilizationAnalysis.decode(json) ?: return null, previous, timestamp)
        }.getOrNull()

        /** Where reading starts again: a quarter of a second before the frame kept, whose time is skipped through (:144-146). */
        fun resumeFrom(timestamp: Double): Double = max(0.0, timestamp - 0.25)

        /** The first checkpoint of a measurement that begins, or goes on, at [measured] seconds: five seconds on (:149). */
        fun first(measured: Double): Double = max(0.0, measured) + 5

        /** The checkpoint after one written at [time]: every 5 s at first, up to every 30 s in a long take (:197). */
        fun next(time: Double): Double = time + max(5.0, min(30.0, time / 24))
    }
}
