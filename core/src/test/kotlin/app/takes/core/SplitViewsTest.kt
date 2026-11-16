package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SplitViewsTest {
    private val canvas = Size(200.0, 100.0)
    private val whole = Rect(0.0, 0.0, 200.0, 100.0)
    private val side = TransitionSettings(style = TransitionStyle.Split, divider = 0.35)

    private fun assertRect(expected: Rect, actual: Rect) {
        assertEquals(expected.x, actual.x, 1e-9); assertEquals(expected.y, actual.y, 1e-9)
        assertEquals(expected.width, actual.width, 1e-9); assertEquals(expected.height, actual.height, 1e-9)
    }

    /** Where the view's point ([x], [y]) is drawn once placed. */
    private fun SplitViews.Placed.map(x: Double, y: Double) = x * scale + translationX to y * scale + translationY

    // YouRec/TransitionRenderer.swift:253-255 and :284-292: each clip cropped by its framing and scaled into its half.
    @Test fun eachClipFillsItsHalfWithTheCropItsFramingPicks() {
        val drawing = SplitViews.of(TransitionPicture.split(side, 0.5, canvas), canvas, whole, whole)
        val first = drawing.first.placed!!
        // A 70 × 100 half of a 200 × 100 picture at the default framing: its middle 70 columns, unscaled.
        assertEquals(SplitViews.Placed(Rect(0.0, 0.0, 70.0, 100.0), 1.0, -65.0, 0.0), first)
        val second = drawing.second.placed!!
        assertEquals(SplitViews.Placed(Rect(70.0, 0.0, 130.0, 100.0), 1.0, 35.0, 0.0), second)
    }

    @Test fun aFittedClipZoomedAndPannedLandsOnItsHalf() {
        // A clip fitted with bars at the sides, framed panned fully left and zoomed in twice.
        val content = Rect(20.0, 0.0, 160.0, 100.0)
        val settings = side.copy(second = TransitionFraming(x = 0.0, y = 0.3, zoom = 2.0))
        val frame = TransitionPicture.split(settings, 0.5, canvas)
        val framed = frame.secondFramed(content.size)
        val placed = SplitViews.of(frame, canvas, content, content).second.placed!!
        assertEquals(framed.scale, placed.scale, 1e-9)
        // The crop's corners, in the view, land on the half's corners.
        val (left, top) = placed.map(content.x + framed.crop.minX, content.y + framed.crop.minY)
        val (right, bottom) = placed.map(content.x + framed.crop.maxX, content.y + framed.crop.maxY)
        assertEquals(70.0, left, 1e-9); assertEquals(0.0, top, 1e-9)
        assertEquals(200.0, right, 1e-9); assertEquals(100.0, bottom, 1e-9)
    }

    // YouRec/TransitionRenderer.swift:281-282 and :258-259: top / bottom, the first clip on top, the line at the divider.
    @Test fun aStackedSplitIsCountedFromTheTop() {
        val drawing = SplitViews.of(TransitionPicture.split(side.copy(axis = TransitionAxis.Vertical), 0.5, canvas), canvas, whole, whole)
        assertRect(Rect(0.0, 0.0, 200.0, 35.0), drawing.first.placed!!.shown)
        assertRect(Rect(0.0, 35.0, 200.0, 65.0), drawing.second.placed!!.shown)
        assertRect(Rect(0.0, 34.5, 200.0, 1.0), drawing.line)
        assertRect(Rect(69.5, 0.0, 1.0, 100.0), SplitViews.of(TransitionPicture.split(side, 0.5, canvas), canvas, whole, whole).line)
    }

    // YouRec/TransitionRenderer.swift:261-264: a dissolve into the pair over the first 15 %, out of it over the last.
    @Test fun thePictureAloneIsWholeWhileItOutweighsThePair() {
        fun at(progress: Double) = SplitViews.of(TransitionPicture.split(side, progress, canvas), canvas, whole, whole)
        val start = at(0.0)
        assertNull(start.first.placed); assertEquals(1.0, start.first.alpha, 0.0)
        assertEquals(0.0, start.second.alpha, 0.0); assertEquals(0.0, start.lineAlpha, 0.0)
        val entering = at(0.05)
        assertNull(entering.first.placed); assertEquals(0, entering.first.rank)
        assertNotNull(entering.second.placed); assertEquals(1, entering.second.rank)
        assertEquals(1.0 / 3, entering.second.alpha, 1e-9); assertEquals(1.0 / 3, entering.lineAlpha, 1e-9)
        // From half way into the entry to half way into the exit, the pair alone.
        for (progress in listOf(0.08, 0.1, 0.5, 0.9, 0.92)) {
            val pair = at(progress)
            assertNotNull(pair.first.placed); assertNotNull(pair.second.placed)
            assertEquals(1.0, pair.first.alpha, 0.0); assertEquals(1.0, pair.second.alpha, 0.0); assertEquals(1.0, pair.lineAlpha, 0.0)
        }
        val leaving = at(0.95)
        assertNull(leaving.second.placed); assertEquals(0, leaving.second.rank)
        assertEquals(1, leaving.first.rank); assertEquals(1.0 / 3, leaving.first.alpha, 1e-9)
        val end = at(1.0)
        assertNull(end.second.placed); assertEquals(0.0, end.first.alpha, 0.0)
    }
}
