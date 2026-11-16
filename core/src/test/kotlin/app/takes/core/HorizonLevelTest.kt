package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * The level horizon. Every test is TRANSLATED from the iPhone app's
 * checkHorizon (Tests/SingleCameraFollowTests.swift:259-335) against
 * YouRec/HorizonLevel.swift:8-148; the sideways picture is built as luma
 * rather than a pixel buffer.
 */
class HorizonLevelTest {
    private val rad = PI / 180
    private val width = 192
    private val height = 108

    private fun edge(angle: Double) = ByteArray(width * height) { i ->
        val edge = (height / 2).toDouble() + (i % width - width / 2) * tan(angle * rad)
        (if ((i / width).toDouble() >= edge) 200 else 30).toByte()
    }

    /** SingleCameraFollowTests.swift:261-274. */
    @Test fun theFixedCropCoversEveryCornerThroughoutTheRange() {
        assertEquals(9.0, HorizonFraming.DEFAULT_DEGREES, 0.0)
        assertEquals(3.0, HorizonFraming.degrees(3.0), 0.0)
        for (size in listOf(Size(1920.0, 1080.0), Size(1080.0, 1920.0))) {
            for (limit in listOf(0.5, 3.0, 10.0)) {
                val scale = HorizonFraming.scale(limit, size)
                for (roll in listOf(-limit, 0.0, limit)) {
                    val map = HorizonFraming(roll * rad, scale).sourceTransform(size)
                    for (point in listOf(Point.ZERO, Point(0.0, 1.0), Point(1.0, 0.0), Point(1.0, 1.0))) {
                        val input = point.applying(map)
                        assertTrue(input.x >= -1e-8 && input.y >= -1e-8 && input.x <= 1.00000001 && input.y <= 1.00000001)
                    }
                }
            }
        }
    }

    /** SingleCameraFollowTests.swift:275-284. */
    @Test fun gravityFollowsTheDeliveredAxesAndALockedQuarterTurn() {
        for (rotation in listOf(0.0, 90.0, 180.0, 270.0)) {
            val a = 5 * rad - (rotation - 90) * rad
            for (mirrored in listOf(false, true)) {
                val roll = HorizonEstimate.roll(-sin(a), -cos(a), rotation, mirrored)!!
                assertEquals((if (mirrored) -5 else 5) * rad, roll, 1e-7)
                val locked = HorizonEstimate.roll(-sin(a + PI / 2), -cos(a + PI / 2), rotation, mirrored)!!
                assertEquals(roll, locked, 1e-7)
            }
        }
        assertNull(HorizonEstimate.roll(0.02, 0.01, 90.0, false))
    }

    /** SingleCameraFollowTests.swift:285-315. */
    @Test fun aLongEdgeGivesItsAngleAndSteepDiagonalsAreIgnored() {
        for (degrees in listOf(-5.0, 0.0, 5.0)) {
            val measured = HorizonLine.estimate(edge(degrees), width, height)!!
            assertTrue("$degrees gave ${measured / rad}", abs(measured / rad - degrees) <= 0.8)
        }
        val upright = edge(5.0)
        // A landscape sensor picture for a portrait two-camera picture: 108 wide, 192 high.
        val sensor = ByteArray(width * height)
        for (y in 0 until width) for (x in 0 until height) sensor[y * height + x] = upright[x * width + width - 1 - y]
        val portrait = HorizonLine.estimateSideways(sensor, height, width)!!
        assertTrue(abs(portrait / rad - 5) <= 0.8)
        assertNull(HorizonLine.estimate(edge(26.0), width, height))
    }

    /** SingleCameraFollowTests.swift:316-334. */
    @Test fun theVisibleHorizonRefinesGravityAndTheLimitEasesIn() {
        val estimate = HorizonEstimate()
        for (i in 0 until 120) {
            val t = i / 30.0
            if (i % 9 == 0) estimate.observe(4 * rad, 3 * rad, t)
            estimate.correction(3 * rad, t, 10.0, false)
        }
        assertEquals(4.0, estimate.correction(3 * rad, 4.0, 10.0, false) / rad, 0.1)
        estimate.observe(null, 3 * rad, 4.01)
        assertTrue(estimate.correction(3 * rad, 4.02, 10.0, false) > 3.9 * rad)
        for (i in 1..100) estimate.correction(-20 * rad, 4 + i / 30.0, 3.0, true)
        val limited = estimate.correction(-20 * rad, 7.4, 3.0, true)
        assertTrue(limited >= -3 * rad && limited < -2.95 * rad)
        val reduced = estimate.correction(-20 * rad, 7.41, 0.5, true)
        assertTrue(abs(reduced) <= 0.5 * rad)
        for (i in 1..100) estimate.correction(null, 7.4 + i / 30.0, 3.0, false)
        assertTrue(abs(estimate.correction(null, 11.0, 3.0, false)) < 0.01 * rad)
    }

    @Test fun theScreenAndImageTransformsTurnTheSameWay() {
        val level = HorizonFraming(0.1, 1.2)
        val p = Point(1.0, 0.0).applying(level.screenTransform(false))
        assertEquals(1.2 * cos(-0.1), p.x, 1e-12)
        assertEquals(1.2 * sin(-0.1), p.y, 1e-12)
        val extent = Rect(0.0, 0.0, 100.0, 50.0)
        val center = Point(50.0, 25.0).applying(level.imageTransform(extent))
        assertEquals(50.0, center.x, 1e-9)
        assertEquals(25.0, center.y, 1e-9)
    }
}
