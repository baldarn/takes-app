package app.takes.stabilization

import java.nio.ByteBuffer

/**
 * The camera motion between consecutive frames of a clip, measured by the iPhone app's own
 * estimator (`YouRec/StabilizationMotion.cpp`, compiled here unchanged): grid-distributed
 * Shi–Tomasi corners, pyramidal Lucas–Kanade with a forward/backward check, and a RANSAC
 * similarity weighted toward the edges of the frame.
 *
 * Frames are reduced to [width] × [height], the analysis size, before they are compared,
 * as the original reduces them (`YouRec/VideoStabilizer.swift:129-174`). Feed them in order
 * with [push]; [close] frees the native memory.
 */
class MotionEstimator(val width: Int, val height: Int) : AutoCloseable {
    private var state = create(width, height)
    private val result = DoubleArray(7)

    init {
        require(width > 0 && height > 0) { "analysis size $width × $height" }
        if (state == 0L) throw OutOfMemoryError("motion estimator")
    }

    /**
     * Takes the next frame from the luma [plane] of a decoded picture: the visible part from
     * ([left], [top]), [sourceWidth] × [sourceHeight] samples, [rowStride] bytes between rows
     * and [sampleStride] between samples, of which byte [high] is read (1 for the 16-bit
     * samples of a 10-bit picture). [fullRange] says the luma is 0…255 and not 16…235.
     * [plane] must be a direct buffer. Returns the motion from the previous frame to this one,
     * or null for the first frame. With [estimate] false the frame is only kept as the one the
     * next is compared with, and null is returned.
     */
    @Synchronized
    fun push(
        plane: ByteBuffer, rowStride: Int, sampleStride: Int, high: Int, left: Int, top: Int, sourceWidth: Int, sourceHeight: Int,
        fullRange: Boolean, estimate: Boolean = true,
    ): Motion? {
        check(state != 0L) { "the motion estimator is closed" }
        require(plane.isDirect) { "the plane must be a direct buffer" }
        return when (push(state, plane, rowStride, sampleStride, high, left, top, sourceWidth, sourceHeight, fullRange, estimate, result)) {
            1 -> Motion.of(result)
            0 -> null
            else -> throw IllegalArgumentException("unreadable plane of $sourceWidth × $sourceHeight")
        }
    }

    /** The last frame pushed, at the analysis size. */
    @Synchronized
    fun lastFrame(): ByteArray = ByteArray(width * height).also { last(state, it) }

    @Synchronized
    override fun close() {
        if (state != 0L) destroy(state)
        state = 0
    }

    /**
     * The original's `YRMotion`: a similarity from the previous frame to the current one, in
     * pixels of the analysis size about the centre of the frame, top-left origin. Only a
     * [valid] one says anything.
     */
    data class Motion(
        val x: Double, val y: Double, val angle: Double, val logScale: Double,
        val inlierRatio: Double, val inliers: Int, val valid: Boolean,
    ) {
        companion object {
            internal fun of(values: DoubleArray) =
                Motion(values[0], values[1], values[2], values[3], values[4], values[5].toInt(), values[6] != 0.0)
        }
    }

    companion object {
        init { System.loadLibrary("stabilization_jni") }

        /** `yr_estimate_motion` on two frames of [width] × [height] already at the analysis size. */
        fun estimate(previous: ByteArray, current: ByteArray, width: Int, height: Int): Motion {
            require(previous.size >= width * height && current.size >= width * height) { "frames smaller than $width × $height" }
            return DoubleArray(7).also { estimate(previous, current, width, height, it) }.let(Motion::of)
        }

        @JvmStatic private external fun create(width: Int, height: Int): Long
        @JvmStatic private external fun destroy(state: Long)
        @JvmStatic private external fun push(
            state: Long, plane: ByteBuffer, rowStride: Int, sample: Int, high: Int, left: Int, top: Int, width: Int, height: Int,
            fullRange: Boolean, estimate: Boolean, result: DoubleArray,
        ): Int
        @JvmStatic private external fun last(state: Long, into: ByteArray)
        @JvmStatic private external fun estimate(previous: ByteArray, current: ByteArray, width: Int, height: Int, result: DoubleArray)
    }
}
