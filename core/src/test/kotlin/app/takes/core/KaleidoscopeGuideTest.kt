package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// YouRec/VideoEffects/KaleidoscopeGuide.swift:1-210, YouRec/VideoEffects/VideoEffectMath.swift:7-22.
class KaleidoscopeGuideTest {
    // A 16:9 picture fitted in a 400 × 300 view: 400 × 225, 37.5 down.
    private val view = Size(400.0, 300.0)
    private val fitted = Affine(400.0, 0.0, 0.0, 225.0, 0.0, 37.5)

    private fun assertPoint(expected: Point, actual: Point, tolerance: Double = 1e-9) {
        assertEquals("x", expected.x, actual.x, tolerance)
        assertEquals("y", expected.y, actual.y, tolerance)
    }

    // KaleidoscopeGuide.swift:14-22.
    @Test fun thePoseIsReadFromTheControlsAndReadOut() {
        val effect = VideoEffectSettings(VideoEffectID.Kaleidoscope)
            .with("centerX", EffectValue.Number(0.25)).with("splitAngle", EffectValue.Number(30.0))
            .with("sourcePanY", EffectValue.Number(-0.5)).with("sourceAngle", EffectValue.Number(-12.4))
        val pose = KaleidoscopePose.of(effect)
        assertEquals(KaleidoscopePose(Point(0.25, 0.5), 30.0, Point(0.0, -0.5), -12.4), pose)
        assertEquals("-12°", KaleidoscopePose.display("sourceAngle", pose.sourceDegrees))
        assertEquals("25%", KaleidoscopePose.display("centerX", 0.25))
        assertEquals("-50%", KaleidoscopePose.display("sourcePanY", -0.5))
        assertEquals("-1%", KaleidoscopePose.display("sourcePanY", -0.005))
        assertEquals(mapOf("sourcePanX" to 0.0, "sourcePanY" to -0.5), pose.parameters(KaleidoscopeTarget.Source))
        assertEquals(mapOf("splitAngle" to 30.0), pose.parameters(KaleidoscopeTarget.Rotation))
    }

    // KaleidoscopeGuide.swift:34-36, 61-75.
    @Test fun nudgesAreHeldInsideTheirRanges() {
        val pose = KaleidoscopePose(Point(0.99, 0.01), 0.0, Point(0.99, -0.99), 179.5)
        assertEquals(Point(1.0, -1.0), pose.offsetSource(0.02, -0.02).sourcePan)
        assertEquals(Point(1.0, 0.0), pose.nudgeCenter(0.02, -0.02).center)
        assertEquals(-179.5, pose.turningSource(1.0).sourceDegrees, 1e-12)
        assertEquals(-180.0, KaleidoscopePose(degrees = 179.0).turningSplit(1.0).degrees, 1e-12)
    }

    // VideoEffectMath.swift:7-22: the first wedge is not turned, an odd one of an even count is reflected, the others turned back.
    @Test fun theDragTransformUndoesTheWedge() {
        val size = Size(400.0, 225.0)
        val center = Point(0.5, 0.5)
        // On the centre: only the source's turn undone.
        val still = KaleidoscopeGuide.dragTransform(center, center, size, 0.0, 4, 90.0)
        assertPoint(Point(0.0, -1.0), Point(1.0, 0.0).applying(still))
        // Four divisions, axis straight up: the first wedge is from up to right.
        val first = KaleidoscopeGuide.dragTransform(Point(0.6, 0.3), center, size, 0.0, 4)
        assertPoint(Point(1.0, 0.0), Point(1.0, 0.0).applying(first))
        // Right and down is the second wedge, a reflection: its determinant is −1.
        val second = KaleidoscopeGuide.dragTransform(Point(0.6, 0.7), center, size, 0.0, 4)
        assertEquals(-1.0, second.a * second.d - second.b * second.c, 1e-12)
        // A reflection about the boundary between them, the x axis: a drag right stays right, a drag down goes up.
        assertPoint(Point(1.0, 0.0), Point(1.0, 0.0).applying(second))
        assertPoint(Point(0.0, -1.0), Point(0.0, 1.0).applying(second))
        // Three divisions: every wedge is a turn, the second turned back by a third.
        val odd = KaleidoscopeGuide.dragTransform(Point(0.5, 0.9), center, size, 0.0, 3)
        val third = 2 * PI / 3
        assertPoint(Point(cos(-third), sin(-third)), Point(1.0, 0.0).applying(odd))
    }

    // KaleidoscopeGuide.swift:77-100.
    @Test fun theLayoutFollowsThePicture() {
        val layout = present(KaleidoscopeGuide.layout(KaleidoscopePose(), 2, fitted, view))
        assertPoint(Point(200.0, 150.0), layout.pivot)
        assertEquals(Rect(0.0, 37.5, 400.0, 225.0), layout.visible)
        assertEquals(hypot(400.0, 225.0), layout.reach, 1e-9)
        // Two divisions at 0°: the boundaries go up and down.
        assertPoint(Point(0.0, -1.0), layout.directions[0])
        assertPoint(Point(0.0, 1.0), layout.directions[1])
        // The handle 67.5 points (0.3 of 225) up the first boundary, inside the picture less 22 points.
        assertPoint(Point(200.0, 82.5), layout.handle)
        // The arc starts on the first boundary, 24 points out, and ends on the next.
        assertPoint(Point(200.0, 126.0), layout.arc.first())
        assertPoint(Point(200.0, 174.0), layout.arc.last())
        // A picture off the view has no guide.
        assertNull(KaleidoscopeGuide.layout(KaleidoscopePose(), 2, Affine(400.0, 0.0, 0.0, 225.0, 500.0, 0.0), view))
    }

    // KaleidoscopeGuide.swift:92-97: a handle that would fall off the picture is brought inside it.
    @Test fun theHandleStaysReachable() {
        val layout = present(KaleidoscopeGuide.layout(KaleidoscopePose(center = Point(0.5, 0.05)), 2, fitted, view))
        assertTrue(layout.handle.y >= 37.5 + 22 && layout.handle.y <= 262.5 - 22)
        // The first boundary points up, off the picture; the second, down, has room.
        assertPoint(Point(200.0, 48.75 + 67.5), layout.handle)
        val corner = present(KaleidoscopeGuide.layout(KaleidoscopePose(center = Point(0.0, 0.0), degrees = -45.0), 1, fitted, view))
        assertTrue(corner.handle.x >= 22 && corner.handle.y >= 37.5 + 22)
    }

    // KaleidoscopeGuide.swift:164-189.
    @Test fun aDragMovesWhatItLandsOn() {
        val pose = KaleidoscopePose()
        val layout = present(KaleidoscopeGuide.layout(pose, 2, fitted, view))
        assertEquals(KaleidoscopeTarget.Center, KaleidoscopeGuide.target(Point(210.0, 150.0), layout))
        assertEquals(KaleidoscopeTarget.Rotation, KaleidoscopeGuide.target(Point(205.0, 90.0), layout))
        assertEquals(KaleidoscopeTarget.Source, KaleidoscopeGuide.target(Point(300.0, 200.0), layout))
        // The centre follows the finger, held inside the picture.
        val (center, moveCenter) = KaleidoscopeGuide.drag(pose, 2, fitted, layout, Point(200.0, 150.0))
        assertEquals(KaleidoscopeTarget.Center, center)
        assertEquals(Point(0.75, 0.5), moveCenter(Point(300.0, 150.0))!!.center)
        assertEquals(Point(1.0, 0.0), moveCenter(Point(900.0, -400.0))!!.center)
        // The handle taken round a quarter turn turns the split by 90°.
        val (rotation, turn) = KaleidoscopeGuide.drag(pose, 2, fitted, layout, layout.handle)
        assertEquals(KaleidoscopeTarget.Rotation, rotation)
        assertEquals(90.0, turn(Point(270.0, 150.0))!!.degrees, 1e-9)
        assertNull(turn(Point(203.0, 152.0)))
        // On the first wedge, the right half, a drag pans the source with the finger.
        val (source, pan) = KaleidoscopeGuide.drag(pose, 2, fitted, layout, Point(300.0, 150.0))
        assertEquals(KaleidoscopeTarget.Source, source)
        assertPoint(Point(0.1, 0.0), pan(Point(340.0, 150.0))!!.sourcePan)
        // On the mirrored half a drag right pans it left.
        val (_, mirrored) = KaleidoscopeGuide.drag(pose, 2, fitted, layout, Point(100.0, 150.0))
        assertPoint(Point(-0.1, 0.0), mirrored(Point(140.0, 150.0))!!.sourcePan)
    }

    // KaleidoscopeGuide.swift:191-206, 247-256.
    @Test fun aTwistTurnsTheSourceAndSettlesAtZero() {
        val pose = KaleidoscopePose()
        val layout = present(KaleidoscopeGuide.layout(pose, 2, fitted, view))
        val onFirst = KaleidoscopeGuide.twist(pose, 2, fitted, layout, Point(300.0, 150.0))
        assertEquals(30.0, onFirst(PI / 6).sourceDegrees, 1e-9)
        assertEquals(0.0, onFirst(PI / 180 * 1.5).sourceDegrees, 0.0)
        // On a reflected wedge the turn is reversed, and again in a mirrored view.
        val onSecond = KaleidoscopeGuide.twist(pose, 2, fitted, layout, Point(100.0, 150.0))
        assertEquals(-30.0, onSecond(PI / 6).sourceDegrees, 1e-9)
        val mirroredView = Affine(-400.0, 0.0, 0.0, 225.0, 400.0, 37.5)
        val mirroredLayout = present(KaleidoscopeGuide.layout(pose, 2, mirroredView, view))
        assertEquals(-30.0, KaleidoscopeGuide.twist(pose, 2, mirroredView, mirroredLayout, Point(100.0, 150.0))(PI / 6).sourceDegrees, 1e-9)
        // A twist counts from 2°, then every change of more than 0.002 radians with the fingers apart.
        assertTrue(!KaleidoscopeGuide.twistCounts(0.03, 0.0, false, 100.0))
        assertTrue(KaleidoscopeGuide.twistCounts(0.04, 0.0, false, 100.0))
        assertTrue(KaleidoscopeGuide.twistCounts(0.01, 0.0, true, 100.0))
        assertTrue(!KaleidoscopeGuide.twistCounts(0.5, 0.4995, true, 100.0))
        assertTrue(!KaleidoscopeGuide.twistCounts(0.5, 0.0, true, 5.0))
    }

    // The editor's preview: the view's own square, or a framing's enlargement, turn and shift about its middle.
    @Test fun theViewTransformFollowsTheFraming() {
        assertEquals(Affine.scale(400.0, 300.0), KaleidoscopeGuide.viewTransform(400.0, 300.0))
        val framed = KaleidoscopeGuide.viewTransform(400.0, 300.0, ViewLayer(2.0, 2.0, 90.0, 10.0, 0.0))
        // The middle stays in the middle, shifted; the top left corner goes to the top right, twice as far.
        assertPoint(Point(210.0, 150.0), Point(0.5, 0.5).applying(framed))
        assertPoint(Point(210.0 + 300.0, 150.0 - 400.0), Point(0.0, 0.0).applying(framed))
        // A flip across reverses x.
        assertPoint(Point(400.0, 0.0), Point(0.0, 0.0).applying(KaleidoscopeGuide.viewTransform(400.0, 300.0, ViewLayer(-1.0, 1.0, 0.0, 0.0, 0.0))))
    }

    private fun <T> present(value: T?): T { assertNotNull(value); return value!! }
}
