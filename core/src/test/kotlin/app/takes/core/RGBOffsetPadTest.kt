package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.sqrt

// YouRec/VideoEffects/RGBOffsetPads.swift:3-69.
class RGBOffsetPadTest {
    private fun assertPoint(expected: Point, actual: Point) {
        assertEquals(expected.x, actual.x, 1e-12)
        assertEquals(expected.y, actual.y, 1e-12)
    }

    // RGBOffsetPads.swift:36-40: inside the circle a cross stays where it is put.
    @Test fun aCrossInsideThePadIsTheOffset() {
        assertPoint(Point(0.3, -0.4), RGBOffsetPad.place(Point(0.3, -0.4), mirrored = false))
        assertPoint(Point(0.0, 0.0), RGBOffsetPad.place(Point(0.0, 0.0), mirrored = false))
        assertPoint(Point(-0.6, 0.8), RGBOffsetPad.place(Point(-0.6, 0.8), mirrored = false))
    }

    // RGBOffsetPads.swift:37-38: past the edge it is brought back onto the circle along its direction.
    @Test fun aCrossPastTheEdgeIsOnTheCircle() {
        val placed = RGBOffsetPad.place(Point(3.0, 4.0), mirrored = false)
        assertPoint(Point(0.6, 0.8), placed)
        assertEquals(1.0, hypot(placed.x, placed.y), 1e-12)
        assertPoint(Point(-1.0, 0.0), RGBOffsetPad.place(Point(-2.5, 0.0), mirrored = false))
        val corner = RGBOffsetPad.place(Point(1.0, 1.0), mirrored = false)
        assertPoint(Point(1 / sqrt(2.0), 1 / sqrt(2.0)), corner)
    }

    // RGBOffsetPads.swift:11-16, 33, 38: a mirrored preview turns the x axis both ways.
    @Test fun aMirroredPadTurnsX() {
        assertPoint(Point(-0.25, 0.5), RGBOffsetPad.displayed(0.25, 0.5, mirrored = true))
        assertPoint(Point(-0.25, 0.5), RGBOffsetPad.place(Point(0.25, 0.5), mirrored = true))
        val offset = RGBOffsetPad.place(Point(0.4, -0.1), mirrored = true)
        assertPoint(Point(0.4, -0.1), RGBOffsetPad.displayed(offset.x, offset.y, mirrored = true))
    }

    // RGBOffsetPads.swift:34: "X 12% · Y -3%", in whole percents of the cross as drawn.
    @Test fun theReadingIsInWholePercents() {
        assertEquals(Pair(12, -3), RGBOffsetPad.reading(0.123, -0.031, mirrored = false))
        assertEquals(Pair(-50, 100), RGBOffsetPad.reading(0.5, 1.0, mirrored = true))
    }
}
