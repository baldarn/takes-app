package app.takes.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * The two-camera layouts, their masks, face choice and follow. Tests marked
 * TRANSLATED carry over the iPhone app's dualLayoutTests
 * (Tests/CoreTests.swift:481-555) and DualCameraMaskTests
 * (Tests/DualCameraMaskTests.swift:9-84), against
 * YouRec/DualCameraLayout.swift and YouRec/DualCameraMasks.swift. The mask
 * test rendered the composite on the GPU; here the coverage of each feed is
 * checked at every pixel's centre instead.
 */
class DualCameraLayoutTest {
    private val json = Json
    private fun roundTrip(layout: DualCameraLayout) = json.decodeFromString(DualCameraLayout.serializer(), json.encodeToString(DualCameraLayout.serializer(), layout))
    private fun Rect.containsRect(other: Rect, tolerance: Double = 1e-9) =
        other.minX >= minX - tolerance && other.minY >= minY - tolerance && other.maxX <= maxX + tolerance && other.maxY <= maxY + tolerance
    private fun canvasOf(size: Size) = Rect(0.0, 0.0, size.width, size.height)

    private val leftFace = Rect(0.15, 0.35, 0.15, 0.2)

    /** TRANSLATED CoreTests.swift:482-483. */
    @Test fun framesOfTheTwoCamerasPairWithinHalfAFrame() {
        assertEquals(DualFramePairing.Action.Pair, DualFramePairing.action(1.0, 1.012))
        assertEquals(DualFramePairing.Action.DropFirst, DualFramePairing.action(1.0, 1.1))
        assertEquals(DualFramePairing.Action.DropSecond, DualFramePairing.action(1.1, 1.0))
    }

    /** TRANSLATED CoreTests.swift:484-497. */
    @Test fun theFaceFollowedIsKeptThroughALargerFaceAndShortLosses() {
        val distractor = Rect(0.65, 0.25, 0.3, 0.4)
        var target = FaceTarget().observing(listOf(leftFace), 0.0)
        target = target.observing(listOf(leftFace.copy(x = leftFace.x + 0.02), distractor), 0.1)
        assertTrue(target.box!!.midX < 0.4)
        target = target.observing(listOf(distractor), 0.2)
        assertTrue(target.box!!.midX < 0.4)
        assertNull(target.current(0.6))
        target = target.observing(listOf(distractor), 1.1)
        assertEquals(distractor.midX, target.box!!.midX, 1e-9)
        assertEquals(distractor.midY, target.box!!.midY, 1e-9)
        val edge = FaceTarget().observing(listOf(Rect(-0.05, 0.3, 0.25, 0.3)), 0.0).box!!
        assertEquals(0.0, edge.minX, 0.0)
        assertEquals(0.2, edge.width, 1e-9)
        assertEquals(0.3, edge.height, 1e-9)
    }

    /** TRANSLATED CoreTests.swift:497-508. */
    @Test fun followUsesOnlyTheSparePictureAndHoldsWhenTheFaceIsLost() {
        val source = Size(1920.0, 1080.0)
        val region = Rect(0.0, 0.0, 960.0, 1080.0)
        var framing = DualCameraLayout.Framing()
        repeat(120) { framing = FaceFraming.follow(leftFace, source, region, framing, 1.0 / 30) }
        val placed = DualCameraLayout.placement(source, region, framing)
        assertTrue(framing.x < 0.1)
        assertEquals(0.5, framing.y, 0.0)
        assertEquals(1.0, framing.zoom, 0.0)
        assertTrue(placed.containsRect(region))
        assertEquals(framing, FaceFraming.follow(null, source, region, framing, 0.1))
        val full = canvasOf(source)
        assertEquals(DualCameraLayout.Framing(), FaceFraming.follow(leftFace, source, full, DualCameraLayout.Framing(), 0.1))
        val zoomed = DualCameraLayout.Framing(zoom = 2.0)
        val firstStep = FaceFraming.follow(leftFace, source, full, zoomed, 1.0 / 30)
        assertTrue(abs(firstStep.x - zoomed.x) < 0.05)
        assertEquals(2.0, firstStep.zoom, 0.0)
    }

    /** TRANSLATED CoreTests.swift:509-529. */
    @Test fun regionsStayInTheCanvasAndFeedsFillThemUnstretched() {
        for (size in listOf(Size(1920.0, 1080.0), Size(1080.0, 1920.0))) {
            for (mode in DualCameraLayout.Mode.entries) {
                for (amount in listOf(-1.0, 0.15, 0.5, 0.85, 2.0)) {
                    val layout = DualCameraLayout(mode = mode, verticalDivider = amount, horizontalDivider = amount, insetX = amount, insetY = amount, insetSize = amount).constrained()
                    val canvas = canvasOf(size)
                    val regions = layout.regions(size)
                    assertTrue(regions.all { canvas.containsRect(it) && it.width > 0 && it.height > 0 })
                    if (mode == DualCameraLayout.Mode.SideBySide || mode == DualCameraLayout.Mode.TopAndBottom) {
                        assertEquals(size.width * size.height, regions.sumOf { it.width * it.height }, 0.001)
                    } else if (mode == DualCameraLayout.Mode.Floating) {
                        assertEquals(regions[1].width, regions[1].height, 1e-9)
                    }
                    assertEquals(regions, layout.copy(swapped = !layout.swapped).regions(size))
                    assertEquals(layout, roundTrip(layout))
                    for (region in regions) for (zoom in listOf(1.0, 4.0)) {
                        val placed = DualCameraLayout.placement(size, region, DualCameraLayout.Framing(0.8, 0.1, zoom))
                        assertTrue(placed.containsRect(region, 1e-6))
                        assertEquals(size.width / size.height, placed.width / placed.height, 0.0001)
                    }
                }
            }
        }
    }

    /** TRANSLATED CoreTests.swift:530-555. */
    @Test fun insetPinchesResizeAlongTheirAxis() {
        assertEquals(DualCameraLayout.InsetResizeAxis.Width, DualCameraLayout.insetResizeAxis(100.0, 15.0))
        assertEquals(DualCameraLayout.InsetResizeAxis.Height, DualCameraLayout.insetResizeAxis(-12.0, 100.0))
        assertEquals(DualCameraLayout.InsetResizeAxis.Proportional, DualCameraLayout.insetResizeAxis(80.0, -70.0))
        for (canvas in listOf(Size(1080.0, 1920.0), Size(1920.0, 1080.0))) {
            var layout = DualCameraLayout()
            val square = layout.regions(canvas)[1]
            layout = layout.resizingInset(1.5, DualCameraLayout.InsetResizeAxis.Width, canvas)
            val wide = layout.regions(canvas)[1]
            assertEquals(square.width * 1.5, wide.width, 0.001)
            assertEquals(square.height, wide.height, 0.001)
            layout = layout.resizingInset(0.75, DualCameraLayout.InsetResizeAxis.Height, canvas)
            val short = layout.regions(canvas)[1]
            assertEquals(wide.width, short.width, 0.001)
            assertEquals(wide.height * 0.75, short.height, 0.001)
            val ratio = short.width / short.height
            for (factor in listOf(1.2, 100.0, 0.0001, 2.0)) {
                layout = layout.resizingInset(factor, DualCameraLayout.InsetResizeAxis.Proportional, canvas)
                val rect = layout.regions(canvas)[1]
                assertEquals(ratio, rect.width / rect.height, 0.001)
                assertTrue(canvasOf(canvas).containsRect(rect, 1e-6))
                val placed = DualCameraLayout.placement(canvas, rect, DualCameraLayout.Framing())
                assertTrue(placed.containsRect(rect, 1e-6))
                assertEquals(canvas.width / canvas.height, placed.width / placed.height, 0.001)
            }
            assertEquals(layout, roundTrip(layout))
        }
    }

    /** TRANSLATED DualCameraMaskTests.swift:13-34. */
    @Test fun theDiagonalMaskAgreesWithTouchOwnership() {
        for (size in listOf(Size(320.0, 180.0), Size(180.0, 320.0))) {
            for (flipped in listOf(false, true)) {
                var layout = DualCameraLayout(mode = DualCameraLayout.Mode.Diagonal, diagonalFlipped = flipped)
                for (y in 0 until size.height.toInt()) for (x in 0 until size.width.toInt()) {
                    val point = Point(x + 0.5, y + 0.5)
                    val c = DualCameraMasks.coverage(point, layout, size)
                    assertEquals(1.0, c.first + c.separator + c.second, 1e-12)
                    val owners = (0..1).filter { layout.contains(point, it, size) }
                    assertEquals(1, owners.size)
                    if (abs(layout.diagonalDistance(point, size)) > 3) {
                        assertEquals(1.0, if (owners[0] == 0) c.first else c.second, 0.0)
                    }
                }
                val old = layout
                layout = layout.flippingDiagonal().flippingDiagonal()
                assertEquals(old, layout)
                assertEquals(layout, roundTrip(layout))
            }
        }
    }

    /** TRANSLATED DualCameraMaskTests.swift:35-84. */
    @Test fun theRoundAndRectangularInsetsResizeWithinTheCanvas() {
        for (size in listOf(Size(320.0, 180.0), Size(180.0, 320.0))) {
            val bounds = canvasOf(size)
            var layout = DualCameraLayout(roundInset = true, insetAspect = 2.0, insetSize = 0.6)
            for (axis in DualCameraLayout.InsetResizeAxis.entries) {
                layout = layout.resizingInset(1.1, axis, size)
                val rect = layout.regions(size)[1]
                assertEquals(rect.width, rect.height, 0.0)
                assertTrue(bounds.containsRect(rect))
            }
            layout = layout.resizingInset(100.0, DualCameraLayout.InsetResizeAxis.Proportional, size)
            val rect = layout.regions(size)[1]
            for (y in 0 until size.height.toInt()) for (x in 0 until size.width.toInt()) {
                val point = Point(x + 0.5, y + 0.5)
                val distance = hypot(point.x - rect.midX, point.y - rect.midY) - rect.width / 2
                val c = DualCameraMasks.coverage(point, layout, size)
                assertEquals(1.0, c.first + c.second, 1e-12)
                if (abs(distance) > 2) {
                    val inside = layout.contains(point, 1, size)
                    assertEquals(distance < 0, inside)
                    assertEquals(1.0, if (inside) c.second else c.first, 0.0)
                }
            }
            layout = layout.copy(roundInset = false).constrained()
            val restored = layout.regions(size)[1]
            assertTrue(restored.width > restored.height)
            for (round in listOf(false, true)) {
                var expanded = DualCameraLayout(roundInset = round).resizingInset(100.0, DualCameraLayout.InsetResizeAxis.Proportional, size)
                val full = expanded.regions(size)[1]
                assertEquals(min(size.width, size.height), full.width, 1e-6)
                assertEquals(full.width, full.height, 1e-9)
                expanded = expanded.resizingInset(0.9, DualCameraLayout.InsetResizeAxis.Proportional, size)
                assertEquals(full.width * 0.9, expanded.regions(size)[1].width, 1e-6)
            }
            var expanded = DualCameraLayout()
                .resizingInset(100.0, DualCameraLayout.InsetResizeAxis.Width, size)
                .resizingInset(100.0, DualCameraLayout.InsetResizeAxis.Height, size)
                .constrained()
            val full = expanded.regions(size)[1]
            assertEquals(size.width, full.width, 1e-6)
            assertEquals(size.height, full.height, 1e-6)
            assertTrue(bounds.containsRect(full, 1e-6))
            assertEquals(expanded, roundTrip(expanded))
            val rotated = Size(size.height, size.width)
            val before = expanded.regions(rotated)[1]
            assertTrue(canvasOf(rotated).containsRect(before, 1e-6))
            assertEquals(full.width / full.height, before.width / before.height, 1e-6)
            expanded = expanded.resizingInset(0.9, DualCameraLayout.InsetResizeAxis.Proportional, rotated)
            assertEquals(before.width * 0.9, expanded.regions(rotated)[1].width, 1e-6)
            var framing = DualCameraLayout.Framing(zoom = 2.0)
            val face = Rect(0.45, 0.45, 0.1, 0.1)
            repeat(120) { framing = FaceFraming.follow(face, size, bounds, framing, 1.0 / 30, Point(0.5, 0.25)) }
            val placed = DualCameraLayout.placement(size, bounds, framing)
            assertTrue(placed.minY + face.midY * placed.height < size.height * 0.31)
        }
    }

    @Test fun anOldPreferenceWithoutTheOptionalFieldsReads() {
        val layout = json.decodeFromString(DualCameraLayout.serializer(), """{"mode":"sideBySide","verticalDivider":0.4}""")
        assertEquals(DualCameraLayout.Mode.SideBySide, layout.mode)
        assertNull(layout.roundInset)
        assertNotNull(layout.first)
        assertEquals("Top right to bottom left", layout.diagonalTitle)
    }
}
