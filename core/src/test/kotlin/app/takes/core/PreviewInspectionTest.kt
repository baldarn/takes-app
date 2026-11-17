package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewInspectionTest {
    // A 16:9 picture in a 400 × 300 view: fitted at 400 × 225 (EditorView.swift:1530).
    private val bounds = Size(400.0, 300.0)
    private val fit = PreviewInspection.fitted(Size(1920.0, 1080.0), bounds)

    @Test fun thePictureIsFittedInTheView() {
        assertEquals(Size(400.0, 225.0), fit)
        assertEquals(Size(168.75, 300.0), PreviewInspection.fitted(Size(1080.0, 1920.0), bounds))
        assertEquals(bounds, PreviewInspection.fitted(null, bounds))
    }

    // EditorView.swift:1592-1597: the zoom stays within 1…8 and the point under the fingers stays under them.
    @Test fun aPinchMagnifiesAboutTheFingers() {
        val pinched = PreviewInspection().pinched(2.0, 300.0, 150.0, 0.0, 0.0, bounds, fit)
        assertEquals(2.0, pinched.zoom, 1e-12)
        // The point 100 px right of the centre is now 200 px right of the magnified centre: the picture moves 100 px left.
        assertEquals(-100.0, pinched.x, 1e-12)
        assertEquals(0.0, pinched.y, 1e-12)
        assertTrue(pinched.zoomed)
        assertEquals(8.0, PreviewInspection().pinched(20.0, 200.0, 150.0, 0.0, 0.0, bounds, fit).zoom, 0.0)
        val out = pinched.pinched(0.1, 200.0, 150.0, 0.0, 0.0, bounds, fit)
        assertEquals(PreviewInspection(), out)
        assertFalse(out.zoomed)
    }

    // EditorView.swift:1531-1533: never past the edges: at 2×, 800 × 450 in 400 × 300 moves at most 200 and 75.
    @Test fun thePictureStaysOverTheView() {
        val zoomed = PreviewInspection(2.0)
        assertEquals(PreviewInspection(2.0, 200.0, -75.0), zoomed.panned(500.0, -500.0, bounds, fit))
        assertEquals(PreviewInspection(2.0, 30.0, 20.0), zoomed.panned(30.0, 20.0, bounds, fit))
        // At 1× a drag moves nothing; and a picture narrower than the view stays centred across.
        assertEquals(PreviewInspection(), PreviewInspection().panned(50.0, 50.0, bounds, fit))
        val tall = PreviewInspection.fitted(Size(1080.0, 1920.0), bounds)
        assertEquals(0.0, PreviewInspection(2.0).panned(90.0, 0.0, bounds, tall).x, 0.0)
    }

    // EditorView.swift:1471-1474, 1601: the screen reader's steps of 1.5, where the picture is.
    @Test fun screenReaderStepsKeepThePlace() {
        val stepped = PreviewInspection(2.0, 100.0, 0.0).stepped(PreviewInspection.STEP, bounds, fit)
        assertEquals(3.0, stepped.zoom, 1e-12)
        assertEquals(100.0, stepped.x, 1e-12)
        // Back to 1×, it can no longer be off centre.
        assertEquals(PreviewInspection(), PreviewInspection(1.4, 20.0, 5.0).stepped(1 / PreviewInspection.STEP, bounds, fit))
    }
}
