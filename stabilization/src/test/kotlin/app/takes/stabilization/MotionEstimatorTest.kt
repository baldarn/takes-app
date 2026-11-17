package app.takes.stabilization

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.Random
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Runs the original's motion estimator, built for this computer, through the JNI bridge the
 * app uses, and the original's own test of it unchanged.
 */
class MotionEstimatorTest {
    /** Tests/StabilizationMotionTests.cpp, compiled as it is in the original's repository. */
    @Test fun theOriginalsOwnTestPasses() {
        val program = File(System.getProperty("stabilization.motionTests"))
        assertTrue("$program was not built", program.canExecute())
        val run = ProcessBuilder(program.path).redirectErrorStream(true).start()
        val said = run.inputStream.bufferedReader().readText()
        print(said)
        assertEquals(said, 0, run.waitFor())
        assertTrue(said, said.contains("PASS: subpixel translation/rotation/zoom tracking"))
    }

    /** Discs of random grey on grey, as the original's test draws its scene (Tests/StabilizationMotionTests.cpp:9-10). */
    private fun scene(width: Int, height: Int, seed: Long = 731): DoubleArray {
        val random = Random(seed)
        val picture = DoubleArray(width * height) { 100.0 }
        repeat(width * height * 600 / (640 * 360)) {
            val x = random.nextInt(width)
            val y = random.nextInt(height)
            val r = (2 + random.nextInt(14)) * width / 640
            val c = 30.0 + random.nextInt(200)
            for (j in -r..r) for (i in -r..r) if (x + i in 0 until width && y + j in 0 until height) picture[(y + j) * width + x + i] = c
        }
        return picture
    }

    private fun sample(picture: DoubleArray, width: Int, height: Int, x: Double, y: Double): Double {
        val ix = floor(x).toInt()
        val iy = floor(y).toInt()
        if (ix < 0 || iy < 0 || ix >= width - 1 || iy >= height - 1) return 100.0
        val u = x - ix
        val v = y - iy
        return (picture[iy * width + ix] * (1 - u) + picture[iy * width + ix + 1] * u) * (1 - v) +
            (picture[(iy + 1) * width + ix] * (1 - u) + picture[(iy + 1) * width + ix + 1] * u) * v
    }

    /** [picture] moved by ([dx], [dy]) and turned by [angle] about its centre, as the original's test moves it (:12-14). */
    private fun moved(picture: DoubleArray, width: Int, height: Int, dx: Double, dy: Double, angle: Double): DoubleArray =
        DoubleArray(width * height) { index ->
            val cx = index % width - width / 2.0 - dx
            val cy = index / width - height / 2.0 - dy
            sample(picture, width, height, cos(angle) * cx + sin(angle) * cy + width / 2.0, -sin(angle) * cx + cos(angle) * cy + height / 2.0)
        }

    private fun bytes(picture: DoubleArray) = ByteArray(picture.size) { Math.round(picture[it].coerceIn(0.0, 255.0)).toInt().toByte() }

    @Test fun estimatesAMoveAndATurnAtTheAnalysisSize() {
        val (w, h) = 640 to 360
        val a = scene(w, h)
        for (angle in listOf(0.0, 0.015, -0.025)) {
            val m = MotionEstimator.estimate(bytes(a), bytes(moved(a, w, h, 6.0, -4.0, angle)), w, h)
            println("angle $angle: $m")
            assertTrue(m.valid)
            assertEquals(6.0, m.x, 0.3)
            assertEquals(-4.0, m.y, 0.3)
            assertEquals(angle, m.angle, 0.002)
        }
        val blank = ByteArray(w * h) { 128.toByte() }
        assertFalse(MotionEstimator.estimate(blank, blank, w, h).valid)
    }

    /** A decoded 1920 × 1080 picture, limited range, in a plane with padding, as a decoder gives it. */
    private fun plane(picture: DoubleArray, width: Int, height: Int, rowStride: Int, sample: Int, high: Int, left: Int, top: Int): ByteBuffer {
        val rows = top + height
        val buffer = ByteBuffer.allocateDirect(rowStride * rows)
        for (y in 0 until height) for (x in 0 until width) {
            val limited = 16 + picture[y * width + x] * 219 / 255
            buffer.put((top + y) * rowStride + (left + x) * sample + high, Math.round(limited).toInt().toByte())
        }
        return buffer
    }

    @Test fun reducesADecodedPictureToTheAnalysisSizeAndMeasuresItsMotion() {
        val (w, h) = 1920 to 1080
        val a = scene(w, h, seed = 9)
        val b = moved(a, w, h, 15.0, 9.0, 0.01)
        for ((sample, high) in listOf(1 to 0, 2 to 1)) {
            MotionEstimator(640, 360).use { estimator ->
                val stride = w * sample + 64
                assertNull(estimator.push(plane(a, w, h, stride, sample, high, 2, 4), stride, sample, high, 2, 4, w, h, fullRange = false))
                // The limited range is stretched back: a reduced sample is the average of the three by three it covers.
                val reduced = estimator.lastFrame()
                for (at in listOf(0, 1000, 640 * 200 + 300)) {
                    val x = at % 640 * 3
                    val y = at / 640 * 3
                    val average = (0 until 9).sumOf { a[(y + it / 3) * w + x + it % 3] } / 9
                    assertEquals(average, (reduced[at].toInt() and 0xFF).toDouble(), 1.0)
                }
                val m = estimator.push(plane(b, w, h, stride, sample, high, 2, 4), stride, sample, high, 2, 4, w, h, fullRange = false)!!
                println("1920 × 1080 reduced to 640 × 360, ${sample * 8}-bit samples: $m")
                assertTrue(m.valid)
                // A third of the move, at a third of the size.
                assertEquals(5.0, m.x, 0.2)
                assertEquals(3.0, m.y, 0.2)
                assertEquals(0.01, m.angle, 0.002)
            }
        }
    }

    @Test fun anAreaReductionKeepsTheAverage() {
        MotionEstimator(64, 64).use { estimator ->
            // Columns of 0 and 200 three wide: each reduced sample covers two of them.
            val w = 384
            val buffer = ByteBuffer.allocateDirect(w * w)
            for (y in 0 until w) for (x in 0 until w) buffer.put(y * w + x, (if (x / 3 % 2 == 0) 0 else 200).toByte())
            estimator.push(buffer, w, 1, 0, 0, 0, w, w, fullRange = true)
            assertArrayEquals(ByteArray(64 * 64) { 100 }, estimator.lastFrame())
        }
    }
}
