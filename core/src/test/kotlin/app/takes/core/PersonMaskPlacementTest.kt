package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PersonMaskPlacementTest {
    private fun assertPoint(expected: Point, actual: Point) {
        assertEquals(expected.x, actual.x, 1e-9)
        assertEquals(expected.y, actual.y, 1e-9)
    }

    /**
     * The stored grid's sample (gx, gy) lands on the upright grid at index first + gx·second + gy·third
     * (app/src/main/kotlin/app/takes/PersonMasks.kt, `turn`): its centre must come back to the same sample.
     */
    @Test
    fun uprightPositionsGoBackToTheSampleTheyCameFrom() {
        val side = 8
        for ((rotation, turn) in listOf(
            0 to Triple(0, 1, side), 90 to Triple(side - 1, side, -1),
            180 to Triple(side * side - 1, -1, -side), 270 to Triple((side - 1) * side, -side, 1),
        )) {
            for (gy in 0 until side) for (gx in 0 until side) {
                val index = turn.first + gx * turn.second + gy * turn.third
                val upright = Point((index % side + .5) / side, (index / side + .5) / side)
                assertPoint(Point((gx + .5) / side, (gy + .5) / side), upright.applying(PersonMaskPlacement.uprightToStored(rotation)))
            }
        }
    }

    @Test
    fun aFittedClipPutsTheMaskWhereThePictureIs() {
        // A 1080 × 1920 upright recording (stored 1920 × 1080, turned 90°) fitted to a 1920 × 1080 movie: pillarboxed.
        val clip = TimelineClip(sourceID = "s", start = 0.0, end = 10.0)
        val toMask = PersonMaskPlacement.toMask(clip, 0.0, Size(1080.0, 1920.0), Size(1920.0, 1080.0), 90)
        val scale = 1080.0 / 1920
        val left = (1920 - 1080 * scale) / 2
        // The upright picture's top left corner and its centre.
        assertPoint(Point(0.0, 1.0), Point(left, 0.0).applying(toMask))
        assertPoint(Point(.5, .5), Point(960.0, 540.0).applying(toMask))
        // Its top right corner is the stored frame's top left (a quarter turn clockwise brings stored top left to the right).
        assertPoint(Point(0.0, 0.0), Point(1920 - left, 0.0).applying(toMask))
        // The bars are outside the mask.
        val bar = Point(left / 2, 540.0).applying(toMask)
        assert(bar.y > 1.0) { bar }
    }

    @Test
    fun aMirroredClipReadsTheMaskMirrored() {
        val clip = TimelineClip(sourceID = "s", start = 0.0, end = 10.0, mirror = MirrorSettings(horizontal = true))
        val toMask = PersonMaskPlacement.toMask(clip, 0.0, Size(1920.0, 1080.0), Size(1920.0, 1080.0), 0)
        assertPoint(Point(1.0, 0.0), Point(0.0, 0.0).applying(toMask))
        assertPoint(Point(.25, .5), Point(1440.0, 540.0).applying(toMask))
    }
}
