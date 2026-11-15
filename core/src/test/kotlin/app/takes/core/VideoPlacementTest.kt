package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VideoPlacementTest {
    private val canvas = Size(1920.0, 1080.0)
    private val touchCanvas = Size(300.0, 168.75)
    private val bounds = Rect(0.0, 0.0, 1920.0, 1080.0)

    private val original = VideoPlacement(x = 0.6, y = 0.4, width = 0.8, height = 0.6, framing = TransitionFraming(x = 0.2, y = 0.7, zoom = 2.0), fill = true, order = 3)

    private fun assertRect(expected: Rect, actual: Rect, tolerance: Double = 1e-6) {
        assertEquals("x of $actual", expected.x, actual.x, tolerance)
        assertEquals("y of $actual", expected.y, actual.y, tolerance)
        assertEquals("width of $actual", expected.width, actual.width, tolerance)
        assertEquals("height of $actual", expected.height, actual.height, tolerance)
    }

    // MARK: Layouts

    // TrackTimeline.swift:408-420, offered at TrackEditors.swift:674-677. Windows on a 1920×1080 canvas.
    @Test fun layoutsGiveEachPictureItsWindow() {
        fun window(kind: PlacementLayout, index: Int, count: Int) = VideoPlacement.layout(kind, index, count).window(canvas)
        assertEquals(listOf("Cover", "Side by side", "Top and bottom", "Picture in picture"), PlacementLayout.entries.map { it.title })
        // Cover: every picture fills the canvas; only the order tells them apart.
        for (index in 0..2) assertEquals(VideoPlacement(order = index), VideoPlacement.layout(PlacementLayout.Cover, index, 3))
        assertRect(Rect(0.0, 0.0, 960.0, 1080.0), window(PlacementLayout.SideBySide, 0, 2))
        assertRect(Rect(960.0, 0.0, 960.0, 1080.0), window(PlacementLayout.SideBySide, 1, 2))
        assertRect(Rect(1280.0, 0.0, 640.0, 1080.0), window(PlacementLayout.SideBySide, 2, 3))
        assertRect(Rect(0.0, 0.0, 1920.0, 540.0), window(PlacementLayout.TopAndBottom, 0, 2))
        assertRect(Rect(0.0, 540.0, 1920.0, 540.0), window(PlacementLayout.TopAndBottom, 1, 2))
        // Picture in picture: the first covers, the rest are 32% windows 6% from the bottom right, stacked upward 2% apart.
        assertRect(Rect(0.0, 0.0, 1920.0, 1080.0), window(PlacementLayout.PictureInPicture, 0, 2))
        assertRect(Rect(1190.4, 669.6, 614.4, 345.6), window(PlacementLayout.PictureInPicture, 1, 2))
        assertRect(Rect(1190.4, 302.4, 614.4, 345.6), window(PlacementLayout.PictureInPicture, 2, 3))
        // Three small windows share the height, so they shrink to 28.67%.
        assertRect(Rect(1254.4, 43.2, 550.4, 309.6), window(PlacementLayout.PictureInPicture, 3, 4))
        // From four small windows on they form a grid, filled from the bottom right.
        assertRect(Rect(1190.4, 669.6, 614.4, 345.6), window(PlacementLayout.PictureInPicture, 1, 5))
        assertRect(Rect(537.6, 302.4, 614.4, 345.6), window(PlacementLayout.PictureInPicture, 4, 5))
        assertRect(Rect(665.6, 374.4, 550.4, 309.6), window(PlacementLayout.PictureInPicture, 5, 6))
        for (kind in PlacementLayout.entries) for (index in 0..3) {
            val value = VideoPlacement.layout(kind, index, 4)
            assertEquals(index, value.order)
            assertTrue(value.fill && value.edgeCrop == null && value.framing == TransitionFraming() && value.valid)
        }
    }

    // TrackTimelineTests.swift:236-239.
    @Test fun pictureInPictureKeepsEveryWindowOnTheCanvas() {
        for (count in 2..24) for (index in 1 until count) {
            val pip = VideoPlacement.layout(PlacementLayout.PictureInPicture, index, count).visibleWindow(canvas)
            assertTrue("$index of $count: $pip", Rect(-1e-9, -1e-9, 1920.0 + 2e-9, 1080.0 + 2e-9).contains(pip))
        }
    }

    // MARK: Edge crop

    // TrackTimelineTests.swift:193-206.
    @Test fun eachEdgeCropsOnlyItsOwnMargin() {
        val full = original.window(Size(1.0, 1.0))
        val value = original
            .cropped(PlacementCropEdge.Left, full.minX + full.width * 0.2)
            .cropped(PlacementCropEdge.Right, full.maxX - full.width * 0.1)
            .cropped(PlacementCropEdge.Top, full.minY + full.height * 0.15)
            .cropped(PlacementCropEdge.Bottom, full.maxY - full.height * 0.25)
        val visible = value.visibleWindow(canvas)
        assertEquals(original.width * canvas.width * 0.7, visible.width, 0.00001)
        assertEquals(original.height * canvas.height * 0.6, visible.height, 0.00001)
        for ((edge, margin) in listOf(PlacementCropEdge.Left to 0.2, PlacementCropEdge.Right to 0.1, PlacementCropEdge.Top to 0.15, PlacementCropEdge.Bottom to 0.25)) {
            assertEquals(margin, value.edgeCrop!![edge], 1e-9)
        }
        // Edge cropping never changes picture scale, position, fit or fill, framing or order.
        assertEquals(original, value.copy(edgeCrop = null))
        // TrackTimelineTests.swift:253-254: moving and resizing keep the margins and the framing.
        val moved = value.copy(x = value.x + 0.1, y = value.y - 0.1, width = value.width * 1.5, height = value.height * 1.5)
        assertEquals(value.edgeCrop, moved.edgeCrop)
        assertEquals(value.framing, moved.framing)
    }

    // TrackTimelineTests.swift:207-211.
    @Test fun fittedPictureIsCroppedFromItsOwnEdges() {
        var fit = original.copy(fill = false)
        val fitted = fit.pictureWindow(canvas)
        assertEquals(canvas.width / canvas.height, fitted.width / fitted.height, 0.00001)
        // The 0.8 × 0.6 window holds a 0.6 × 0.6 picture.
        assertRect(Rect(576.0, 108.0, 1152.0, 648.0), fitted)
        fit = fit.cropped(PlacementCropEdge.Left, (fitted.minX + fitted.width * 0.2) / canvas.width, canvas)
        assertEquals(fitted.minX + fitted.width * 0.2, fit.visibleWindow(canvas).minX, 0.00001)
    }

    // TrackTimelineTests.swift:212-219.
    @Test fun touchCropLeavesAMovableWindow() {
        val small = original.cropped(PlacementCropEdge.Left, 10.0, touchCanvas, minimumPoints = 44.0)
        assertEquals(44.0, small.visibleWindow(touchCanvas).width, 0.00001)
        // An already-small crop neither grows nor shrinks further when touched.
        val narrow = original.copy(fill = false, edgeCrop = PlacementEdgeCrop(top = 0.4, bottom = 0.35))
        val top = narrow.visibleWindow(touchCanvas).minY / touchCanvas.height
        assertEquals(narrow, narrow.cropped(PlacementCropEdge.Top, top + 0.01, touchCanvas, minimumPoints = 44.0))
    }

    // TrackTimelineTests.swift:246-252.
    @Test fun oppositeEdgesCannotCrossAndDraggingOutRestoresTheWindow() {
        var clamped = original
            .cropped(PlacementCropEdge.Left, 0.28).cropped(PlacementCropEdge.Right, 0.9)
            .cropped(PlacementCropEdge.Top, 0.2).cropped(PlacementCropEdge.Bottom, 0.6)
        for (edge in PlacementCropEdge.entries) {
            clamped = clamped.cropped(edge, if (edge == PlacementCropEdge.Left || edge == PlacementCropEdge.Top) 10.0 else -10.0)
            assertTrue("$edge: ${clamped.edgeCrop}", clamped.edgeCrop!!.valid)
        }
        // With no touch minimum, 2% of the picture is left each way.
        assertEquals(1 - PlacementEdgeCrop.MINIMUM_SPAN, clamped.edgeCrop!!.left + clamped.edgeCrop!!.right, 1e-9)
        for (edge in PlacementCropEdge.entries) {
            clamped = clamped.cropped(edge, if (edge == PlacementCropEdge.Left || edge == PlacementCropEdge.Top) -10.0 else 10.0)
        }
        assertEquals(original, clamped)
        assertEquals(original, original.cropped(PlacementCropEdge.Left, Double.NaN))
    }

    // TrackTimeline.swift:366-370; TrackTimelineTests.swift:322-323 rejects margins of 0.8 and 0.7.
    @Test fun marginsMustLeaveSomePicture() {
        assertTrue(PlacementEdgeCrop().isEmpty && PlacementEdgeCrop().valid)
        assertTrue(PlacementEdgeCrop(left = 0.5, right = 0.48).valid)
        assertFalse(PlacementEdgeCrop(left = 0.8, right = 0.7).valid)
        assertFalse(PlacementEdgeCrop(top = 0.5, bottom = 0.49).valid)
        assertFalse(PlacementEdgeCrop(left = -0.1).valid)
        assertFalse(PlacementEdgeCrop(top = Double.NaN).valid)
        assertFalse(original.copy(edgeCrop = PlacementEdgeCrop(left = 0.8, right = 0.7)).valid)
    }

    // MARK: Reframing

    // TrackTimelineTests.swift:220-235.
    @Test fun reframeFollowsTheFingerInFitAndFillAtEveryZoom() {
        for (fill in listOf(false, true)) for (zoom in listOf(1.0, 2.0, 5.0)) {
            var framed = original.copy(fill = fill, framing = TransitionFraming(0.5, 0.5, zoom))
            val window = framed.window(touchCanvas)
            fun crop() = framed.framing.crop(touchCanvas, if (fill) window.size else touchCanvas)
            val before = crop()
            val scale = if (fill) maxOf(window.width / before.width, window.height / before.height) else minOf(window.width / before.width, window.height / before.height)
            framed = framed.reframed(Point(4.0, -3.0), touchCanvas)
            val after = crop()
            val expectedX = minOf(4.0, before.minX * scale)
            val expectedY = minOf(3.0, (touchCanvas.height - before.maxY) * scale)
            assertEquals("fill $fill zoom $zoom", expectedX, (before.midX - after.midX) * scale, 0.00001)
            assertEquals("fill $fill zoom $zoom", expectedY, (after.midY - before.midY) * scale, 0.00001)
            // Reversing at an image edge responds immediately, without a hidden dead zone.
            framed = framed.reframed(Point(10000.0, 10000.0), touchCanvas).reframed(Point(-2.0, -2.0), touchCanvas)
            val reversed = crop()
            assertEquals("fill $fill zoom $zoom", minOf(2.0, (touchCanvas.width - reversed.width) * scale), reversed.minX * scale, 0.00001)
        }
    }

    // TrackEditors.swift:592-596 and :613.
    @Test fun reframeGesturesMoveAndZoomThePictureInsideItsWindow() {
        val start = VideoPlacement.layout(PlacementLayout.PictureInPicture, 1, 2).copy(framing = TransitionFraming(zoom = 2.0))
        val dragged = PlacementEditing.reframed(start, start, Point(9.6, 0.0), touchCanvas)
        // The window is 96 wide and shows 150 picture units at zoom 2: 0.64 on screen per unit.
        assertEquals(0.5 - 15.0 / 300, dragged.framing.x, 1e-9)
        assertEquals(0.5, dragged.framing.y, 1e-9)
        assertEquals(start.copy(framing = dragged.framing), dragged)
        assertEquals(8.0, PlacementEditing.zoomed(start, start, 5.0).framing.zoom, 0.0)
        assertEquals(3.0, PlacementEditing.zoomed(start, start, 1.5).framing.zoom, 1e-12)
        assertEquals(1.0, PlacementEditing.zoomed(start, start, 0.1).framing.zoom, 0.0)
    }

    // MARK: Moving and resizing a window

    // TrackEditors.swift:819-830: a 44-point strip stays on the canvas, and 0.012 of the canvas snaps.
    @Test fun windowSnapsToTheCanvasAndStaysReachable() {
        val whole = VideoPlacement()
        assertEquals(0.5, PlacementEditing.constrained(whole.copy(x = 0.505), touchCanvas).x, 0.0)
        assertEquals(0.5, PlacementEditing.constrained(whole.copy(x = 0.4885), touchCanvas).x, 0.0)
        assertEquals(0.52, PlacementEditing.constrained(whole.copy(x = 0.52), touchCanvas).x, 0.0)
        assertEquals(0.487, PlacementEditing.constrained(whole.copy(x = 0.487), touchCanvas).x, 0.0)
        // A 32% window, x 0.62…0.94 in its preset: flush right is 0.84, flush left 0.16, centered 0.5.
        val pip = VideoPlacement.layout(PlacementLayout.PictureInPicture, 1, 2)
        assertEquals(0.84, PlacementEditing.constrained(pip.copy(x = 0.835), touchCanvas).x, 1e-12)
        assertEquals(0.16, PlacementEditing.constrained(pip.copy(x = 0.17), touchCanvas).x, 1e-12)
        assertEquals(0.5, PlacementEditing.constrained(pip.copy(y = 0.49), touchCanvas).y, 1e-12)
        assertEquals(0.84, PlacementEditing.constrained(pip.copy(y = 0.845), touchCanvas).y, 1e-12)
        assertEquals(0.7, PlacementEditing.constrained(pip.copy(x = 0.7), touchCanvas).x, 0.0)
        // Thrown off the canvas, 44 of the 300 points stay visible.
        val right = PlacementEditing.constrained(pip.copy(x = 2.0), touchCanvas)
        assertEquals(300.0 - 44.0, right.visibleWindow(touchCanvas).minX, 1e-9)
        val left = PlacementEditing.constrained(pip.copy(x = -2.0), touchCanvas)
        assertEquals(44.0, left.visibleWindow(touchCanvas).maxX, 1e-9)
        // The margins move the snapping edges with the visible window.
        val cut = pip.copy(edgeCrop = PlacementEdgeCrop(right = 0.5))
        assertEquals(1.0, PlacementEditing.constrained(cut.copy(x = 0.995), touchCanvas).visibleWindow(touchCanvas).maxX / 300, 1e-9)
    }

    // TrackEditors.swift:597-601.
    @Test fun draggingMovesTheWindowByTheFingerDistance() {
        val start = VideoPlacement.layout(PlacementLayout.PictureInPicture, 1, 2)
        val moved = PlacementEditing.moved(start, start, Point(-30.0, -33.75), touchCanvas)
        assertEquals(0.68, moved.x, 1e-12)
        assertEquals(0.58, moved.y, 1e-12)
        // The distance is always measured from where the drag began, whatever is on screen now.
        val again = PlacementEditing.moved(start, moved, Point(-30.0, -33.75), touchCanvas)
        assertEquals(moved.x, again.x, 1e-12)
        assertEquals(moved.y, again.y, 1e-12)
        // Dragged to within 0.012 of the right edge, it lands flush with it.
        val flush = PlacementEditing.moved(start, moved, Point(17.0, 0.0), touchCanvas)
        assertEquals(0.84, flush.x, 1e-12)
        assertEquals(0.78, flush.y, 1e-12)
    }

    // TrackEditors.swift:615-621: between a touchable size and three times the canvas, around the visible center.
    @Test fun pinchingResizesTheWindowAroundItsCenter() {
        val start = VideoPlacement.layout(PlacementLayout.PictureInPicture, 1, 2).copy(x = 0.6, y = 0.6)
        val doubled = PlacementEditing.resized(start, start, 2.0, touchCanvas)
        assertEquals(0.64, doubled.width, 1e-12)
        assertEquals(0.64, doubled.height, 1e-12)
        assertEquals(0.6, doubled.x, 1e-12)
        assertEquals(0.6, doubled.y, 1e-12)
        // The window is 96 × 54 points: it cannot go below 44 points high.
        val smallest = PlacementEditing.resized(start, start, 0.1, touchCanvas)
        assertEquals(44.0, smallest.visibleWindow(touchCanvas).height, 1e-9)
        assertEquals(44.0 / 54 * 0.32, smallest.width, 1e-12)
        val largest = PlacementEditing.resized(start, start, 100.0, touchCanvas)
        assertEquals(3.0, largest.width, 1e-12)
        assertTrue(largest.valid)
    }

    // MARK: Edge handles

    // TrackEditors.swift:985-996: five points from the picture's own edge, the canvas's edges or its center.
    @Test fun edgeHandleSnapsToThePictureAndTheCanvas() {
        val start = VideoPlacement()
        assertEquals(0.5, PlacementEditing.edgeDragged(start, PlacementCropEdge.Left, 0.51, touchCanvas).edgeCrop!!.left, 1e-12)
        assertEquals(0.3, PlacementEditing.edgeDragged(start, PlacementCropEdge.Left, 0.3, touchCanvas).edgeCrop!!.left, 1e-12)
        assertNull(PlacementEditing.edgeDragged(start, PlacementCropEdge.Left, 0.016, touchCanvas).edgeCrop)
        assertEquals(0.017, PlacementEditing.edgeDragged(start, PlacementCropEdge.Left, 0.017, touchCanvas).edgeCrop!!.left, 1e-12)
        // Vertically the five points are a larger share of the shorter canvas.
        assertEquals(0.5, PlacementEditing.edgeDragged(start, PlacementCropEdge.Bottom, 0.525, touchCanvas).edgeCrop!!.bottom, 1e-12)
        // A small window snaps its handle back to its own edge, and to the canvas center when it crosses it.
        val pip = VideoPlacement(x = 0.5, y = 0.5, width = 0.4, height = 0.4)
        assertNull(PlacementEditing.edgeDragged(pip, PlacementCropEdge.Right, 0.69, touchCanvas).edgeCrop)
        assertEquals(0.5, PlacementEditing.edgeDragged(pip, PlacementCropEdge.Right, 0.51, touchCanvas).edgeCrop!!.right, 1e-12)
        // TrackEditors.swift:639: a drag never leaves less than 44 points.
        val most = PlacementEditing.edgeDragged(start, PlacementCropEdge.Left, 0.99, touchCanvas)
        assertEquals(44.0, most.visibleWindow(touchCanvas).width, 1e-9)
    }

    // TrackEditors.swift:647-651 and :1030-1031.
    @Test fun accessibilityStepsMoveAMarginByTwoPercent() {
        val start = VideoPlacement()
        val once = PlacementEditing.edgeStepped(start, PlacementCropEdge.Left, PlacementEditing.EDGE_STEP, touchCanvas)
        assertEquals(0.02, once.edgeCrop!!.left, 1e-12)
        val twice = PlacementEditing.edgeStepped(once, PlacementCropEdge.Left, PlacementEditing.EDGE_STEP, touchCanvas)
        assertEquals(0.04, twice.edgeCrop!!.left, 1e-12)
        assertEquals(0.02, PlacementEditing.edgeStepped(twice, PlacementCropEdge.Left, -PlacementEditing.EDGE_STEP, touchCanvas).edgeCrop!!.left, 1e-12)
        assertNull(PlacementEditing.edgeStepped(once, PlacementCropEdge.Left, -PlacementEditing.EDGE_STEP, touchCanvas).edgeCrop)
        assertEquals(0.02, PlacementEditing.edgeStepped(start, PlacementCropEdge.Bottom, 0.02, touchCanvas).edgeCrop!!.bottom, 1e-12)
    }

    // TrackEditors.swift:947-968: handles sit mid-edge, reach 30 points outward and 14 inward (7 on a small window), 22 sideways.
    @Test fun handlesSitOnTheVisibleEdgesAndReachOutward() {
        val whole = VideoPlacement()
        assertEquals(
            mapOf(
                PlacementCropEdge.Left to Point(0.0, 84.375), PlacementCropEdge.Right to Point(300.0, 84.375),
                PlacementCropEdge.Top to Point(150.0, 0.0), PlacementCropEdge.Bottom to Point(150.0, 168.75),
            ),
            PlacementEditing.handles(whole, touchCanvas),
        )
        assertEquals(PlacementCropEdge.Left, PlacementEditing.handle(whole, Point(14.0, 84.375), touchCanvas))
        assertNull(PlacementEditing.handle(whole, Point(15.0, 84.375), touchCanvas))
        assertEquals(PlacementCropEdge.Left, PlacementEditing.handle(whole, Point(-30.0, 84.375), touchCanvas))
        assertNull(PlacementEditing.handle(whole, Point(-31.0, 84.375), touchCanvas))
        assertEquals(PlacementCropEdge.Left, PlacementEditing.handle(whole, Point(5.0, 106.375), touchCanvas))
        assertNull(PlacementEditing.handle(whole, Point(5.0, 107.0), touchCanvas))
        assertEquals(PlacementCropEdge.Bottom, PlacementEditing.handle(whole, Point(150.0, 160.0), touchCanvas))
        // A window narrower than 88 points keeps more of its inside for moving.
        val pip = VideoPlacement(x = 0.5, y = 0.5, width = 0.2, height = 0.6)
        assertEquals(Point(120.0, 84.375), PlacementEditing.handles(pip, touchCanvas).getValue(PlacementCropEdge.Left))
        assertEquals(PlacementCropEdge.Left, PlacementEditing.handle(pip, Point(127.0, 84.375), touchCanvas))
        assertNull(PlacementEditing.handle(pip, Point(128.0, 84.375), touchCanvas))
        // Partly off the canvas the handles stay on it, at the middle of what can be seen.
        val off = VideoPlacement(x = 1.0, y = 0.5, width = 0.4, height = 0.4)
        assertEquals(Point(300.0, 84.375), PlacementEditing.handles(off, touchCanvas).getValue(PlacementCropEdge.Right))
        assertEquals(Point(270.0, 50.625), PlacementEditing.handles(off, touchCanvas).getValue(PlacementCropEdge.Top))
    }

    // MARK: Order and picking

    // TrackTimeline.swift:433-440; TrackTimelineTests.swift:262-263 and TrackMediaTests.swift:394-400.
    @Test fun orderIsBackToFrontAndTiesKeepTheTimelineOrder() {
        val ids = listOf("a", "b", "c")
        assertEquals(ids, VideoPlacement.ordered(ids, mapOf("a" to original, "b" to original, "c" to original)))
        assertEquals(ids, VideoPlacement.ordered(ids, emptyMap()))
        assertEquals(listOf("b", "a"), VideoPlacement.ordered(listOf("a", "b"), mapOf("a" to VideoPlacement(order = 10), "b" to VideoPlacement(order = 0))))
        // A picture without settings takes its place in the timeline as its depth.
        assertEquals(listOf("a", "c", "b"), VideoPlacement.ordered(ids, mapOf("b" to VideoPlacement(order = 5))))
        // TrackEditors.swift:682-683.
        val all = listOf(VideoPlacement(order = 0), VideoPlacement(order = 1), VideoPlacement(order = 4))
        assertEquals(5, PlacementEditing.broughtToFront(all[0], all).order)
        assertEquals(-1, PlacementEditing.sentToBack(all[2], all).order)
    }

    // TrackEditors.swift:773-792.
    @Test fun touchPicksTheFrontmostPictureUnlessOneWasChosenByHand() {
        val ids = listOf("base", "pip")
        val settings = mapOf("base" to VideoPlacement.layout(PlacementLayout.PictureInPicture, 0, 2), "pip" to VideoPlacement.layout(PlacementLayout.PictureInPicture, 1, 2))
        val inside = Point(1500.0, 800.0)
        val outside = Point(300.0, 300.0)
        assertEquals("pip", PlacementEditing.hit(ids, settings, inside, canvas))
        assertEquals("base", PlacementEditing.hit(ids, settings, outside, canvas))
        assertNull(PlacementEditing.hit(ids, settings, Point(-5.0, 300.0), canvas))
        // A picture chosen by hand stays the target while touched, even under another.
        assertEquals("base", PlacementEditing.target(ids, settings, "base", explicitPick = true, point = inside, canvas = canvas))
        assertEquals("pip", PlacementEditing.target(ids, settings, "base", explicitPick = false, point = inside, canvas = canvas))
        // A pinch that starts just beside the small selected window still resizes it.
        val beside = Point(1170.0, 800.0)
        assertEquals("base", PlacementEditing.target(ids, settings, "pip", explicitPick = false, point = beside, canvas = canvas))
        assertEquals("pip", PlacementEditing.target(ids, settings, "pip", explicitPick = false, point = beside, canvas = canvas, pinching = true))
        assertEquals("base", PlacementEditing.target(ids, settings, "pip", explicitPick = false, point = outside, canvas = canvas, pinching = true))
    }

    // MARK: Rendering

    // TrackRenderer.swift:317-329, in top-down pixels.
    @Test fun placedPictureIsFramedScaledAndClipped() {
        val pip = PlacementRendering.placed(VideoPlacement.layout(PlacementLayout.PictureInPicture, 1, 2), canvas)
        assertRect(Rect(0.0, 0.0, 1920.0, 1080.0), pip.source)
        assertRect(Rect(1190.4, 669.6, 614.4, 345.6), pip.window)
        assertRect(Rect(1190.4, 669.6, 614.4, 345.6), pip.clip)
        assertEquals(0.32, pip.scale, 1e-12)
        assertEquals(Point(1190.4, 669.6).x, Point.ZERO.applying(pip.transform).x, 1e-9)
        assertEquals(669.6, Point.ZERO.applying(pip.transform).y, 1e-9)
        // Side by side, filling: the middle half of the picture at full size.
        val half = PlacementRendering.placed(VideoPlacement.layout(PlacementLayout.SideBySide, 0, 2), canvas)
        assertRect(Rect(480.0, 0.0, 960.0, 1080.0), half.source)
        assertEquals(1.0, half.scale, 1e-12)
        assertRect(Rect(0.0, 0.0, 960.0, 1080.0), half.clip)
        // Fitted instead: the whole picture at half size, bars above and below, clipped to the picture.
        val fitted = VideoPlacement.layout(PlacementLayout.SideBySide, 0, 2).copy(fill = false)
        val whole = PlacementRendering.placed(fitted, canvas)
        assertRect(Rect(0.0, 0.0, 1920.0, 1080.0), whole.source)
        assertEquals(0.5, whole.scale, 1e-12)
        assertRect(Rect(0.0, 270.0, 960.0, 540.0), whole.clip)
        // Fitted and zoomed in, the picture keeps its place and shows less of the source.
        val closer = PlacementRendering.placed(fitted.copy(framing = TransitionFraming(zoom = 2.0)), canvas)
        assertRect(Rect(480.0, 270.0, 960.0, 540.0), closer.source)
        assertEquals(1.0, closer.scale, 1e-12)
        assertRect(Rect(0.0, 270.0, 960.0, 540.0), closer.clip)
        // TrackMediaTests.swift:403-407: margins of 0.2, 0.15, 0.2, 0.1 on a covering picture show 32…136 × 18…81 of 160 × 90.
        val cropped = PlacementRendering.placed(VideoPlacement(edgeCrop = PlacementEdgeCrop(left = 0.2, right = 0.15, top = 0.2, bottom = 0.1)), Size(160.0, 90.0))
        assertRect(Rect(32.0, 18.0, 104.0, 63.0), cropped.clip)
        assertEquals(1.0, cropped.scale, 1e-12)
        assertRect(Rect(0.0, 0.0, 160.0, 90.0), cropped.source)
        // Off the canvas nothing is drawn; half off, only what is on it.
        assertEquals(Rect.ZERO, PlacementRendering.placed(VideoPlacement(x = 3.0), canvas).clip)
        assertRect(Rect(960.0, 0.0, 960.0, 1080.0), PlacementRendering.placed(VideoPlacement(x = 1.0), canvas).clip)
    }

    // MARK: Motion and saving

    // EffectAnimation.swift:200-225 and EffectAnimationChecks.swift:175-193.
    @Test fun placementMovesBetweenTwoPoses() {
        val first = VideoPlacement.layout(PlacementLayout.PictureInPicture, 1, 2).copy(order = 1)
        val last = first.copy(x = 0.72, y = 0.6, width = 0.5, height = 0.55, framing = first.framing.copy(zoom = 1.7))
        assertEquals(setOf("x", "y", "width", "height", "fx", "fy", "zoom", "left", "right", "top", "bottom"), first.pose.values.keys)
        assertEquals(last, first.applying(last.pose))
        val clip = TimelineClip(sourceID = "S", start = 0.0, end = 4.0)
        val motion = EffectAnimation(first.pose, 4.0)
            .copy(b = last.pose, timing = EffectTiming(curve = EffectCurve.RiseHoldReturn, rise = 1.0, release = 1.0))
            .anchored(SpeedMapping.spans(clip, null), 0.0)
        val moving = first.copy(motion = motion)
        assertTrue(moving.valid)
        assertEquals(first, moving.at(0.0))
        assertEquals(last, moving.at(2.0))
        val between = moving.at(0.5)
        assertEquals((first.x + last.x) / 2, between.x, 1e-9)
        assertEquals((first.width + last.width) / 2, between.width, 1e-9)
        // The picture's zoom moves in ratios, like a crop's.
        assertEquals(Math.sqrt(1.7), between.framing.zoom, 1e-9)
        assertNull(between.motion)
        assertEquals(1, between.order)
        // Margins move too, and a pose with none drops the crop.
        val cut = first.copy(edgeCrop = PlacementEdgeCrop(left = 0.2))
        assertEquals(0.2, cut.pose["left"], 0.0)
        assertNull(cut.applying(first.pose).edgeCrop)
        assertEquals(cut, first.applying(cut.pose))
    }

    // TrackTimeline.swift:1203-1208.
    @Test fun validationRangesMatchTheOriginal() {
        assertTrue(VideoPlacement().valid && original.valid)
        assertTrue(VideoPlacement(x = -2.0, y = 3.0, width = 0.01, height = 3.0, framing = TransitionFraming(0.0, 1.0, 8.0)).valid)
        assertFalse(VideoPlacement(x = -2.1).valid)
        assertFalse(VideoPlacement(y = 3.1).valid)
        assertFalse(VideoPlacement(width = 0.009).valid)
        assertFalse(VideoPlacement(height = 3.1).valid)
        assertFalse(VideoPlacement(framing = TransitionFraming(zoom = 8.1)).valid)
        assertFalse(VideoPlacement(framing = TransitionFraming(x = 1.1)).valid)
        assertFalse(VideoPlacement(x = Double.NaN).valid)
        // Both ends of a motion are held to the same ranges.
        val wide = VideoPlacement().copy(width = 3.5)
        assertFalse(VideoPlacement(motion = EffectAnimation(VideoPlacement().pose, 2.0).copy(b = wide.pose)).valid)
    }

    // TrackTimelineTests.swift:240-245: margins round-trip, and placements saved without them open uncropped.
    @Test fun placementReadsAndWritesTheOriginalFields() {
        val text = documentJson.encodeToString(VideoPlacement.serializer(), VideoPlacement())
        assertEquals("""{"x":0.5,"y":0.5,"width":1.0,"height":1.0,"framing":{"x":0.5,"y":0.5,"zoom":1.0},"fill":true,"order":0}""", text)
        val value = original.copy(edgeCrop = PlacementEdgeCrop(left = 0.2, right = 0.1, top = 0.15, bottom = 0.25))
        val saved = documentJson.encodeToString(VideoPlacement.serializer(), value)
        assertTrue(saved.endsWith(""""fill":true,"order":3,"edgeCrop":{"left":0.2,"right":0.1,"top":0.15,"bottom":0.25}}"""))
        assertEquals(value, documentJson.decodeFromString(VideoPlacement.serializer(), saved))
        val existing = documentJson.decodeFromString(VideoPlacement.serializer(), documentJson.encodeToString(VideoPlacement.serializer(), original))
        assertNull(existing.edgeCrop)
        assertEquals(original, existing)
        assertTrue(abs(existing.framing.zoom - 2.0) < 1e-12)
    }
}
