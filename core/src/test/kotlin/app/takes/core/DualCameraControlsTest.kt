package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two cameras' picture, gestures and pairs, against YouRec/MultiCameraCapture.swift:93-108, 171-180, 272-302
 * and YouRec/RecorderView.swift:652-681, 1386-1531. The original has no tests of its own for these; the numbers
 * come from its arithmetic.
 */
class DualCameraControlsTest {
    private val portrait = DualCameraControls.canvas(landscape = false)

    @Test fun thePictureIs1080By1920StandingAndTurnsWithTheInterface() {
        // MultiCameraCapture.swift:176
        assertEquals(Size(1080.0, 1920.0), portrait)
        assertEquals(Size(1920.0, 1080.0), DualCameraControls.canvas(landscape = true))
    }

    @Test fun theLayoutIsKeptAndAnUnreadableOneGivesTheDefault() {
        // CaptureController.swift:89-96, 950: decoded, constrained, else DualCameraLayout()
        val layout = DualCameraLayout(mode = DualCameraLayout.Mode.Diagonal, swapped = true, roundInset = true, first = DualCameraLayout.Framing(0.2, 0.7, 2.0))
        assertEquals(layout, DualCameraControls.decode(DualCameraControls.encode(layout)))
        assertEquals(DualCameraLayout(), DualCameraControls.decode(null))
        assertEquals(DualCameraLayout(), DualCameraControls.decode("{not json"))
        assertEquals(0.85, DualCameraControls.decode("""{"mode":"sideBySide","verticalDivider":3}""").verticalDivider, 0.0)
    }

    @Test fun swapExchangesTheFeedsAndKeepsTheGeometry() {
        // MultiCameraCapture.swift:245, 285: source = swapped ? 1-slot : slot
        val layout = DualCameraLayout(mode = DualCameraLayout.Mode.SideBySide, verticalDivider = 0.3)
        assertEquals(0, DualCameraControls.feed(layout, 0))
        assertEquals(1, DualCameraControls.feed(layout.copy(swapped = true), 0))
        assertEquals(layout.regions(portrait), layout.copy(swapped = true).regions(portrait))
    }

    @Test fun draggingTheInsetMovesItAndStaysOnTheCanvas() {
        // RecorderView.swift:1503-1505: the inset's centre moves with the finger.
        val layout = DualCameraLayout()
        val before = layout.regions(portrait)[1]
        val moved = DualCameraControls.dragged(layout, 1, -100.0, 50.0, portrait, mirrored = false)
        val after = moved.regions(portrait)[1]
        assertEquals(before.midX - 100, after.midX, 1e-9)
        assertEquals(before.midY + 50, after.midY, 1e-9)
        // Far past the edge: clamped to the canvas, never off it (DualCameraLayout.swift:100).
        val far = DualCameraControls.dragged(layout, 1, 5000.0, -5000.0, portrait, mirrored = false).regions(portrait)[1]
        assertEquals(portrait.width, far.maxX, 1e-9)
        assertEquals(0.0, far.minY, 1e-9)
        // The feeds' framing is left alone.
        assertEquals(layout.first, moved.first)
        assertEquals(layout.second, moved.second)
    }

    @Test fun draggingAPicturePansItsFeedWithinTheSparePicture() {
        // RecorderView.swift:1506-1514: f.x -= dx / (placed.width - region.width), with the canvas as the source.
        val layout = DualCameraLayout(mode = DualCameraLayout.Mode.SideBySide, second = DualCameraLayout.Framing(zoom = 2.0))
        val region = layout.regions(portrait)[1]
        val placed = DualCameraLayout.placement(portrait, region, layout.second)
        val panned = DualCameraControls.dragged(layout, 1, 30.0, -20.0, portrait, mirrored = false)
        assertEquals(0.5 - 30 / (placed.width - region.width), panned.second.x, 1e-12)
        assertEquals(0.5 + 20 / (placed.height - region.height), panned.second.y, 1e-12)
        assertEquals(layout.first, panned.first)
        // A mirrored picture pans the other way across.
        val mirrored = DualCameraControls.dragged(layout, 1, 30.0, 0.0, portrait, mirrored = true)
        assertEquals(0.5 + 30 / (placed.width - region.width), mirrored.second.x, 1e-12)
        // Swapped, the second slot shows the first feed.
        val swapped = DualCameraControls.dragged(layout.copy(swapped = true, first = DualCameraLayout.Framing(zoom = 2.0)), 1, 30.0, 0.0, portrait, mirrored = false)
        assertTrue(swapped.first.x < 0.5)
        // The pan stops at the edge of the picture.
        assertEquals(0.0, DualCameraControls.dragged(layout, 1, 1e6, 0.0, portrait, mirrored = false).second.x, 0.0)
    }

    @Test fun aPinchResizesTheInsetOrZoomsAFeedFromOneToFour() {
        // RecorderView.swift:1517-1527
        val layout = DualCameraLayout()
        val canvas = portrait
        assertEquals(layout.resizingInset(1.5, DualCameraLayout.InsetResizeAxis.Width, canvas),
            DualCameraControls.pinched(layout, 1, 1.5, DualCameraLayout.InsetResizeAxis.Width, canvas))
        val zoomed = DualCameraControls.pinched(layout, 0, 1.5, DualCameraLayout.InsetResizeAxis.Proportional, canvas)
        assertEquals(1.5, zoomed.first.zoom, 1e-12)
        assertEquals(4.0, DualCameraControls.pinched(zoomed, 0, 10.0, DualCameraLayout.InsetResizeAxis.Proportional, canvas).first.zoom, 0.0)
        assertEquals(1.0, DualCameraControls.pinched(zoomed, 0, 0.1, DualCameraLayout.InsetResizeAxis.Proportional, canvas).first.zoom, 0.0)
        val split = layout.copy(mode = DualCameraLayout.Mode.TopAndBottom, swapped = true)
        assertEquals(2.0, DualCameraControls.pinched(split, 1, 2.0, DualCameraLayout.InsetResizeAxis.Proportional, canvas).first.zoom, 1e-12)
    }

    @Test fun theDividerFollowsTheFingerBetween15And85Percent() {
        // RecorderView.swift:1408-1414, DualCameraLayout.swift:69
        val side = DualCameraLayout(mode = DualCameraLayout.Mode.SideBySide)
        assertEquals(0.6, DualCameraControls.divided(side, 0.5, 108.0, 1080.0).verticalDivider, 1e-12)
        assertEquals(0.85, DualCameraControls.divided(side, 0.5, 1000.0, 1080.0).verticalDivider, 0.0)
        val top = DualCameraLayout(mode = DualCameraLayout.Mode.TopAndBottom)
        assertEquals(0.15, DualCameraControls.divided(top, 0.5, -1500.0, 1920.0).horizontalDivider, 0.0)
    }

    @Test fun theSeparatorIsTwoPixelsOverTheBoundary() {
        // MultiCameraCapture.swift:299-301
        val side = DualCameraLayout(mode = DualCameraLayout.Mode.SideBySide, verticalDivider = 0.25)
        assertEquals(Rect(269.0, 0.0, 2.0, 1920.0), DualCameraControls.separator(side, portrait))
        val top = DualCameraLayout(mode = DualCameraLayout.Mode.TopAndBottom)
        assertEquals(Rect(0.0, 959.0, 1080.0, 2.0), DualCameraControls.separator(top, portrait))
        assertNull(DualCameraControls.separator(DualCameraLayout(), portrait))
        assertNull(DualCameraControls.separator(DualCameraLayout(mode = DualCameraLayout.Mode.Diagonal), portrait))
    }

    @Test fun aFeedFillsItsSlotAndAMirroredOneIsFlippedAboutTheSlot() {
        // MultiCameraCapture.swift:287-295: placement, then a flip about the clip's middle.
        val layout = DualCameraLayout(mode = DualCameraLayout.Mode.SideBySide)
        val feed = Size(1080.0, 1920.0)
        val region = layout.regions(portrait)[0]
        val left = DualCameraControls.feedPoint(layout, 0, Point(region.minX, region.midY), feed, portrait, mirrored = false)
        val right = DualCameraControls.feedPoint(layout, 0, Point(region.maxX, region.midY), feed, portrait, mirrored = false)
        // 540 wide out of a 9:16 feed scaled to 1920 tall: the middle half of the feed's width, centred.
        assertEquals(0.25, left.x, 1e-12)
        assertEquals(0.75, right.x, 1e-12)
        assertEquals(0.5, left.y, 1e-12)
        val flipped = DualCameraControls.feedPoint(layout, 0, Point(region.minX, region.midY), feed, portrait, mirrored = true)
        assertEquals(0.75, flipped.x, 1e-12)
    }

    private val rear = DualCameraControls.Camera("0", front = false, lens = DualCameraControls.Lens.Main)
    private val wide = DualCameraControls.Camera("2", front = false, lens = DualCameraControls.Lens.Ultrawide)
    private val front = DualCameraControls.Camera("1", front = true, lens = DualCameraControls.Lens.Main)

    @Test fun pairsAreThoseThePhoneRunsTogetherFrontPairsFirst() {
        // MultiCameraCapture.swift:93-108
        val pairs = DualCameraControls.pairs(listOf(front, wide, rear), listOf(setOf("0", "1"), setOf("1", "2"), setOf("0", "2")))
        assertEquals(listOf("0|1", "2|1", "0|2"), pairs.map { it.id })
        assertEquals(listOf("0|1"), DualCameraControls.pairs(listOf(front, rear, wide), listOf(setOf("1", "0"))).map { it.id })
        assertTrue(DualCameraControls.pairs(listOf(front, rear), emptyList()).isEmpty())
    }

    @Test fun theMenusOfferOnlyPossiblePartnersAndChoosingKeepsTheOtherSlot() {
        // RecorderView.swift:661-681
        val pairs = DualCameraControls.pairs(listOf(front, wide, rear), listOf(setOf("0", "1"), setOf("1", "2")))
        assertEquals(listOf("0", "2", "1"), DualCameraControls.cameras(pairs).map { it.id })
        assertEquals(listOf("1"), DualCameraControls.partners(pairs, rear).map { it.id })
        assertEquals(listOf("0", "2"), DualCameraControls.partners(pairs, front).map { it.id })
        val current = pairs.first { it.id == "0|1" }
        // The ultrawide in the first slot keeps the front camera in the second.
        val (next, swapped) = DualCameraControls.choose(pairs, current, swapped = false, camera = wide, firstSlot = true)!!
        assertEquals("2|1", next.id)
        assertEquals(false, swapped)
        // The front camera in the first slot: the pair it is in, shown swapped.
        val (same, turned) = DualCameraControls.choose(pairs, current, swapped = false, camera = front, firstSlot = true)!!
        assertEquals("0|1", same.id)
        assertEquals(true, turned)
        assertEquals(front to rear, DualCameraControls.slots(current, swapped = true))
    }
}
