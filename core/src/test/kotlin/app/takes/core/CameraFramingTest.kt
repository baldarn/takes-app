package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * The single camera's framing: crop, follow reserve and the pan of a pinch.
 * Tests marked TRANSLATED carry over the geometry of the iPhone app's
 * SingleCameraFollowTests (Tests/SingleCameraFollowTests.swift:136-158 and
 * 211-220) against YouRec/DualFaceDetector.swift:129-195 and
 * YouRec/VideoEffects/CaptureZoomHistory.swift:5-32; their pixel checks and
 * the Vision detection stay with the app. The others are this port's own,
 * on YouRec/RecorderZoomViewport.swift:151-174.
 */
class CameraFramingTest {
    private val center = Point(0.375, 0.75)

    /** TRANSLATED SingleCameraFollowTests.swift:138-148: a pan picks the right part of the lens on either camera, at every turn. */
    @Test fun aCentreSetByHandCropsTheLensAtEveryTurnAndMirror() {
        for (angle in listOf(0.0, 90.0, 180.0, 270.0)) for (mirror in listOf(false, true)) {
            val zoom = SingleCameraCrop.zoom(inputZoom = 1.0, selectedZoom = 2.0, minimumZoom = 1.0, reserve = 1.0)
            val crop = SingleCameraCrop.crop(DualCameraLayout.Framing(zoom = zoom), center, 1.0, 1.0, angle, mirror)
            val target = CameraFraming.oriented(center, angle, mirror)
            assertEquals(target.x, crop.rectangle.midX, 1e-6)
            assertEquals(target.y, crop.rectangle.midY, 1e-6)
            val restored = CameraFraming.sensorPoint(target, angle, mirror)
            assertTrue(hypot(restored.x - center.x, restored.y - center.y) < 1e-6)
            val visible = SingleCameraCrop.visible(crop.rectangle, 1.0, 1.0, angle, mirror)
            assertEquals(2.0, visible.zoom, 1e-9)
            assertEquals(center.x, visible.center.x, 1e-9)
            assertEquals(center.y, visible.center.y, 1e-9)
        }
    }

    /** TRANSLATED SingleCameraFollowTests.swift:152-155: during a lens handoff the crop is clamped to the pixels there are. */
    @Test fun theCropIsClampedDuringAHardwareZoom() {
        val zoom = SingleCameraCrop.zoom(inputZoom = 1.3, selectedZoom = 2.0, minimumZoom = 1.0, reserve = 1.0)
        val crop = SingleCameraCrop.crop(DualCameraLayout.Framing(zoom = zoom), center, 1.3, 1.0, 0.0, false).rectangle
        assertEquals(0.65, crop.width, 1e-6)
        assertTrue(crop.minX >= 0 && crop.maxY <= 1 + 1e-12)
    }

    /** TRANSLATED SingleCameraFollowTests.swift:156-158: at the widest there is no crop to make. */
    @Test fun atTheWidestThereIsNoCrop() {
        assertEquals(1.0, SingleCameraCrop.zoom(inputZoom = 1.0, selectedZoom = null, minimumZoom = 1.0, reserve = 1.0), 0.0)
        val crop = SingleCameraCrop.crop(DualCameraLayout.Framing(), null, 1.0, 1.0, 0.0, false)
        assertEquals(SingleCameraCrop.FULL_FRAME, crop.rectangle)
    }

    /** TRANSLATED SingleCameraFollowTests.swift:211-216: a follow at 2x uses that framing with no further crop. */
    @Test fun followingAtTwoTimesAddsNoCrop() {
        var reserve = 1.0
        repeat(30) { reserve = SingleCameraCrop.reserve(reserve, following = true, manual = false, dt = 1.0 / 30) }
        assertEquals(SingleCameraCrop.MAXIMUM_RESERVE, reserve, 1e-12)
        assertEquals(2.0, SingleCameraCrop.zoom(1.0, 2.0, 1.0, reserve), 0.0)
        assertEquals(1.2, SingleCameraCrop.zoom(1.0, null, 1.0, reserve), 1e-12)
    }

    @Test fun theReserveEasesOutAndAHandCentreHasNone() {
        var reserve = 1.2
        repeat(10) { reserve = SingleCameraCrop.reserve(reserve, following = false, manual = false, dt = 1.0 / 30) }
        assertEquals(1.0, reserve, 1e-12)
        assertEquals(1.0, SingleCameraCrop.reserve(1.2, following = true, manual = true, dt = 0.1), 0.0)
        assertEquals(0.1, SingleCameraCrop.elapsed(0.0, 1.0), 0.0)
        assertEquals(1.0 / 30, SingleCameraCrop.elapsed(null, 5.0), 1e-12)
        assertTrue(SingleCameraCrop.restarts(true, 2.0, 1.0))
        assertTrue(SingleCameraCrop.restarts(true, 1.0, 1.6))
        assertFalse(SingleCameraCrop.restarts(true, 1.0, 1.4))
    }

    @Test fun aCentreSetByHandMovesTheFollowFramingSoFollowingEasesOutOfIt() {
        val crop = SingleCameraCrop.crop(DualCameraLayout.Framing(zoom = 2.0), Point(0.25, 0.25), 1.0, 1.0, 0.0, false)
        assertEquals(0.0, crop.framing.x, 1e-12)
        assertEquals(0.0, crop.framing.y, 1e-12)
        assertEquals(Point(0.25, 0.25), CameraFraming.clamped(Point(0.0, 0.0), 0.5))
    }

    private val image = Rect(0.0, 0.0, 400.0, 300.0)

    private fun pan(mid: Point, previousCenter: Point = CameraFraming.MIDDLE, wasFollowing: Boolean = false, angle: Double = 0.0, mirrored: Boolean = false) =
        PinchPan.step(image, 1.0, 2.0, previousCenter, 2.0, Point(200.0, 150.0), mid, Point(200.0, 150.0), 100.0, 100.0, angle, mirrored, Affine.IDENTITY, wasFollowing, false)

    @Test fun thePointUnderTheFingersStaysUnderThem() {
        val step = pan(Point(240.0, 150.0))
        assertEquals(0.45, step.center.x, 1e-12)
        assertEquals(0.5, step.center.y, 1e-12)
        assertTrue(step.dragged)
        // At 2x the lens point under the fingers' new place is the one under their old place.
        assertEquals(0.5, step.center.x + (240.0 - 200.0) / 400 * 0.5, 1e-12)
        val mirrored = pan(Point(240.0, 150.0), mirrored = true)
        assertEquals(0.55, mirrored.center.x, 1e-12)
    }

    @Test fun aSmallTravelLeavesTheFaceFollowInCharge() {
        val step = pan(Point(205.0, 150.0), previousCenter = Point(0.6, 0.4), wasFollowing = true)
        assertFalse(step.dragged)
        assertEquals(Point(0.6, 0.4), step.center)
        assertTrue(pan(Point(400.0, 150.0)).center.x >= 0.25)
    }

    @Test fun aChangeIsSavedPastHalfAPercentOrThreeQuartersOfAPoint() {
        assertFalse(PinchPan.changed(image, 1.0, 2.0, CameraFraming.MIDDLE, 2.005, CameraFraming.MIDDLE, 0.0, false, Affine.IDENTITY))
        assertTrue(PinchPan.changed(image, 1.0, 2.0, CameraFraming.MIDDLE, 2.02, CameraFraming.MIDDLE, 0.0, false, Affine.IDENTITY))
        assertTrue(PinchPan.changed(image, 1.0, 2.0, CameraFraming.MIDDLE, 2.0, Point(0.501, 0.5), 0.0, false, Affine.IDENTITY))
    }

    // YouRec/RecorderZoomViewport.swift:198-206
    @Test fun theBadgeAsksToZoomInAtTheWidestAndSaysWhenTheFollowIsTakenOver() {
        assertEquals(PinchPan.Badge.ZoomInToPan, PinchPan.badge(dragged = true, wasFollowing = false, zoom = 1.0, minimumZoom = 1.0, changed = false))
        assertEquals(PinchPan.Badge.Zoom, PinchPan.badge(dragged = true, wasFollowing = false, zoom = 2.0, minimumZoom = 1.0, changed = true))
        assertEquals(PinchPan.Badge.Zoom, PinchPan.badge(dragged = false, wasFollowing = true, zoom = 2.0, minimumZoom = 1.0, changed = false))
        assertEquals(PinchPan.Badge.Manual, PinchPan.badge(dragged = true, wasFollowing = true, zoom = 2.0, minimumZoom = 1.0, changed = true))
        // At the widest a follow taken over still asks to zoom in first.
        assertEquals(PinchPan.Badge.ZoomInToPan, PinchPan.badge(dragged = true, wasFollowing = true, zoom = 1.0, minimumZoom = 1.0, changed = false))
    }

    // YouRec/CaptureController.swift:1745-1752
    @Test fun aSavedPanComesBackClampedOnlyWhenTheCameraIsZoomedIn() {
        assertEquals(Point(0.3, 0.6), PinchPan.restored(0.3, 0.6, 2.0, 1.0, following = false, nativeZoom = false))
        // Clamped to the crop of half the lens.
        assertEquals(Point(0.25, 0.75), PinchPan.restored(0.0, 1.0, 2.0, 1.0, following = false, nativeZoom = false))
        assertEquals(null, PinchPan.restored(0.3, 0.6, 1.0, 1.0, following = false, nativeZoom = false))
        assertEquals(null, PinchPan.restored(0.3, 0.6, 2.0, 1.0, following = true, nativeZoom = false))
        assertEquals(null, PinchPan.restored(0.3, 0.6, 2.0, 1.0, following = false, nativeZoom = true))
        assertEquals(null, PinchPan.restored(null, 0.6, 2.0, 1.0, following = false, nativeZoom = false))
        assertEquals(null, PinchPan.restored(0.5, 0.5, 2.0, 1.0, following = false, nativeZoom = false))
    }

    // YouRec/CaptureController.swift:913-924, 2205-2214
    @Test fun aCentreIsGivenUpAtTheWidestOrNearTheMiddle() {
        assertTrue(PinchPan.releases(Point(0.3, 0.3), 1.0, 1.0))
        assertTrue(PinchPan.releases(Point(0.5005, 0.5), 2.0, 1.0))
        assertFalse(PinchPan.releases(Point(0.502, 0.5), 2.0, 1.0))
    }

    // YouRec/ZoomTransition.swift:64-69
    @Test fun aTimedMovePansOnTheZoomsCurve() {
        val move = ZoomTransition(1.0, 4.0, 0.0, 2.0, Point(0.5, 0.5), Point(0.3, 0.7))
        assertEquals(Point(0.5, 0.5), move.center(-1.0))
        assertEquals(0.4, move.center(1.0)!!.x, 1e-12)
        assertEquals(0.6, move.center(1.0)!!.y, 1e-12)
        assertEquals(Point(0.3, 0.7), move.center(2.0))
        assertEquals(null, ZoomTransition(1.0, 4.0, 0.0, 2.0).center(1.0))
    }
}
