package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CropEditingTest {
    private val landscape = Size(1920.0, 1080.0)
    private val portrait = Size(1080.0, 1920.0)
    private val frame = PauseDetector.FRAME

    /** A 400×680 view showing a landscape frame: the window is 360 wide, 0.1875 view units per canvas pixel. */
    private val viewport = CropViewport(Size(400.0, 680.0), landscape, landscape)

    private fun assertPoint(x: Double, y: Double, actual: Point, tolerance: Double = 1e-9) {
        assertEquals("x of $actual", x, actual.x, tolerance)
        assertEquals("y of $actual", y, actual.y, tolerance)
    }

    // MARK: The picture area

    // CropEditor.swift:332 (the frame fitted 20 points inside the view) and :559-564 (400×680 gives 360×640 at 20, 20).
    @Test fun windowIsTheFrameFittedInsideTheViewWithAMargin() {
        assertEquals(Rect(20.0, 20.0, 360.0, 640.0), CropViewport(Size(400.0, 680.0), landscape, portrait).window)
        assertEquals(Rect(20.0, 238.75, 360.0, 202.5), viewport.window)
        assertEquals(Rect(98.75, 20.0, 202.5, 360.0), CropViewport(Size(400.0, 400.0), landscape, portrait).window)
        assertEquals(Rect.ZERO, CropViewport(Size(40.0, 40.0), landscape, landscape).window)
    }

    // CropEditor.swift:333-339: the crop's transform, then the mirror, then scaled and moved into the window.
    @Test fun footageIsDrawnWithTheWindowsCenterOnTheFramesCenter() {
        val crop = CropSettings(x = 0.75, y = 0.3, zoom = 2.0, rotation = 17.0)
        val window = viewport.window
        val area = crop.rect(landscape, landscape)
        assertPoint(window.midX, window.midY, Point(area.midX, area.midY).applying(viewport.transform(crop)))
        // Upright at zoom 2 a source pixel is 0.375 view units, whichever way it is mirrored.
        assertPoint(266.6666666667, 0.0, viewport.sourceDelta(Point(100.0, 0.0), CropSettings(zoom = 2.0)))
        assertPoint(-266.6666666667, 0.0, viewport.copy(mirror = MirrorSettings(horizontal = true)).sourceDelta(Point(100.0, 0.0), CropSettings(zoom = 2.0)))
    }

    // MARK: Drag

    // CropEditor.swift:445-460.
    @Test fun dragMovesThePictureWithTheFingerAfterEightPoints() {
        val start = CropSettings(zoom = 2.0)
        assertNull(CropGestures.panChanged(start, Point(5.0, 5.0), viewport))
        assertNull(CropGestures.panChanged(start, Point(7.9, 0.0), viewport))
        assertNotNull(CropGestures.panChanged(start, Point(8.0, 0.0), viewport))
        // 37.5 view units to the right are 100 source pixels: the window moves left over the footage.
        val moved = CropGestures.panChanged(start, Point(37.5, 0.0), viewport)!!
        assertEquals(0.5 - 100.0 / 1920, moved.x, 1e-12)
        assertEquals(0.5, moved.y, 0.0)
        assertEquals(2.0, moved.zoom, 0.0)
        val down = CropGestures.panChanged(start, Point(0.0, -37.5), viewport)!!
        assertEquals(0.5 + 100.0 / 1080, down.y, 1e-12)
        assertEquals(0.5, down.x, 0.0)
        // Mirrored, the footage still follows the finger, so the stored center moves the other way.
        val mirrored = CropGestures.panChanged(start, Point(37.5, 0.0), viewport.copy(mirror = MirrorSettings(horizontal = true)))!!
        assertEquals(0.5 + 100.0 / 1920, mirrored.x, 1e-12)
    }

    // CropEditor.swift:454-459: the drag starts from the center really shown, and stores what is shown.
    @Test fun dragAtAnEdgeStopsThereAndReversesAtOnce() {
        // Asked for 0.98; at zoom 2 the furthest center that can be shown is 0.75.
        val start = CropSettings(x = 0.98, y = 0.5, zoom = 2.0)
        val pushed = CropGestures.panChanged(start, Point(-300.0, 0.0), viewport)!!
        assertEquals(0.75, pushed.x, 1e-12)
        val back = CropGestures.panChanged(start, Point(37.5, 0.0), viewport)!!
        assertEquals(0.75 - 100.0 / 1920, back.x, 1e-12)
        // At zoom 1 in a matching frame there is nowhere to go.
        val fixed = CropGestures.panChanged(CropSettings(), Point(100.0, 60.0), viewport)!!
        assertEquals(0.5, fixed.x, 1e-12)
        assertEquals(0.5, fixed.y, 1e-12)
    }

    // CropEditor.swift:340-343: the finger distance goes back through the rotation.
    @Test fun dragOnATurnedPictureMovesAlongTheTurnedAxes() {
        // A quarter turn: source right is down on screen, 1/3 view unit per source pixel.
        val start = CropSettings(rotation = 90.0)
        val moved = CropGestures.panChanged(start, Point(0.0, 30.0), viewport)!!
        assertEquals(0.5 - 90.0 / 1920, moved.x, 1e-9)
        // The axis the finger did not move along keeps its stored value.
        assertEquals(0.5, moved.y, 0.0)
        assertEquals(90.0, moved.rotation, 0.0)
    }

    // MARK: Pinch

    // CropEditor.swift:428-444.
    @Test fun pinchZoomsAroundThePointBetweenTheFingers() {
        val center = Point(200.0, 340.0)
        val centered = CropGestures.pinchChanged(CropGestures.pinchBegan(CropSettings(), center, 1.0, viewport), center, 2.0, viewport)
        assertEquals(2.0, centered.zoom, 1e-12)
        assertEquals(0.5, centered.x, 1e-12)
        assertEquals(0.5, centered.y, 1e-12)
        // A quarter of the way in from the top left of the window: source pixel 480, 270.
        val focus = Point(110.0, 289.375)
        val pinch = CropGestures.pinchBegan(CropSettings(), focus, 1.0, viewport)
        assertPoint(480.0, 270.0, pinch.anchor)
        val zoomed = CropGestures.pinchChanged(pinch, focus, 2.0, viewport)
        assertEquals(2.0, zoomed.zoom, 1e-12)
        assertEquals(0.375, zoomed.x, 1e-12)
        assertEquals(0.375, zoomed.y, 1e-12)
        // The pixel that was under the fingers still is.
        assertPoint(focus.x, focus.y, pinch.anchor.applying(viewport.transform(zoomed)))
    }

    // CropEditor.swift:435 and :439: the zoom is relative to the recognizer's scale when the pinch began, within 1…8.
    @Test fun pinchIsRelativeToItsStartAndStaysWithinTheZoomLimits() {
        val center = Point(200.0, 340.0)
        val pinch = CropGestures.pinchBegan(CropSettings(zoom = 2.0), center, 1.5, viewport)
        assertEquals(4.0, CropGestures.pinchChanged(pinch, center, 3.0, viewport).zoom, 1e-12)
        assertEquals(8.0, CropGestures.pinchChanged(pinch, center, 100.0, viewport).zoom, 0.0)
        assertEquals(1.0, CropGestures.pinchChanged(pinch, center, 0.01, viewport).zoom, 0.0)
        // It starts from the crop as shown, not from a center that cannot be reached.
        assertEquals(0.75, CropGestures.pinchBegan(CropSettings(x = 0.98, zoom = 2.0), center, 1.0, viewport).crop.x, 1e-12)
    }

    // CropEditor.swift:440-443: two fingers moving together pan, by the same amount as one finger.
    @Test fun twoFingersMovingTogetherPan() {
        val pinch = CropGestures.pinchBegan(CropSettings(zoom = 2.0), Point(200.0, 340.0), 1.0, viewport)
        val moved = CropGestures.pinchChanged(pinch, Point(237.5, 340.0), 1.0, viewport)
        assertEquals(0.5 - 100.0 / 1920, moved.x, 1e-12)
        assertEquals(0.5, moved.y, 1e-12)
        // Pushed past the edge it stores the last center that can be shown.
        assertEquals(0.25, CropGestures.pinchChanged(pinch, Point(900.0, 340.0), 1.0, viewport).x, 1e-12)
    }

    // Own test: the twist is this port's addition; the original turns the picture with the slider only.
    @Test fun twistTurnsAroundThePointBetweenTheFingers() {
        val focus = Point(170.0, 320.0)
        val pinch = CropGestures.pinchBegan(CropSettings(zoom = 3.0), focus, 1.0, viewport)
        val turned = CropGestures.pinchChanged(pinch, focus, 1.0, viewport, twist = 30.0)
        assertEquals(30.0, turned.rotation, 0.0)
        assertEquals(3.0, turned.zoom, 0.0)
        assertPoint(focus.x, focus.y, pinch.anchor.applying(viewport.transform(turned)))
        // A small twist is held upright, and no twist leaves the angle alone.
        assertEquals(0.0, CropGestures.pinchChanged(pinch, focus, 1.0, viewport, twist = 0.3).rotation, 0.0)
        val tilted = CropGestures.pinchBegan(CropSettings(zoom = 3.0, rotation = 0.2), focus, 1.0, viewport)
        assertEquals(0.2, CropGestures.pinchChanged(tilted, focus, 1.2, viewport).rotation, 1e-9)
    }

    // MARK: Rotation

    // CropEditor.swift:194-197: under half a degree is upright. Lines 243-252: the angle resets, the button adds 90°.
    @Test fun rotationSnapsToUprightAndTurnsInQuarters() {
        val crop = CropSettings(zoom = 2.0)
        assertEquals(0.0, CropGestures.rotated(crop, 0.4).rotation, 0.0)
        assertEquals(0.0, CropGestures.rotated(crop, -0.49).rotation, 0.0)
        assertEquals(0.5, CropGestures.rotated(crop, 0.5).rotation, 0.0)
        assertEquals(-0.5, CropGestures.rotated(crop, -0.5).rotation, 0.0)
        assertEquals(15.0, CropGestures.rotated(crop, 15.0).rotation, 0.0)
        assertEquals(45.0, CropGestures.rotated(crop, 45.0).rotation, 0.0)
        // No other angle attracts in the original.
        assertEquals(89.7, CropGestures.rotated(crop, 89.7).rotation, 1e-9)
        // The zoom setting is kept; the window shrinks instead (VideoTools.swift:25-27).
        assertEquals(2.0, CropGestures.rotated(crop, 45.0).zoom, 0.0)
        // VideoTools.swift:14-19: a positive half turn stays +180, then the angle wraps.
        var turning = crop
        val seen = List(4) { turning = CropGestures.quarterTurn(turning); turning.rotation }
        assertEquals(listOf(90.0, 180.0, -90.0, 0.0), seen)
        assertEquals(-135.0, CropGestures.quarterTurn(crop.copy(rotation = 135.0)).rotation, 0.0)
    }

    // Own test: detents other than upright are available to a twist gesture, off by default.
    @Test fun otherDetentsAreOptional() {
        val quarters = listOf(0.0, 90.0, 180.0, -90.0)
        assertEquals(89.7, CropGestures.snappedRotation(89.7), 1e-9)
        assertEquals(90.0, CropGestures.snappedRotation(89.7, quarters), 0.0)
        assertEquals(180.0, CropGestures.snappedRotation(-179.8, quarters), 0.0)
        assertEquals(-90.0, CropGestures.snappedRotation(270.3, quarters), 0.0)
        assertEquals(88.0, CropGestures.snappedRotation(88.0, quarters), 1e-9)
        assertEquals(90.0, CropGestures.snappedRotation(88.0, quarters, threshold = 3.0), 0.0)
        assertEquals(-170.0, CropGestures.snappedRotation(190.0), 1e-9)
    }

    // MARK: Controls

    // CropEditor.swift:164-168.
    @Test fun adjustmentsStayInsideTheRangesAProjectMayHold() {
        val limited = CropGestures.limited(CropSettings(id = "K", x = 1.4, y = -0.2, zoom = 11.0, rotation = 270.0))
        assertEquals(CropSettings(id = "K", x = 1.0, y = 0.0, zoom = 8.0, rotation = -90.0), limited)
        assertTrue(limited.valid)
        assertEquals(1.0, CropGestures.zoomed(CropSettings(), 0.2).zoom, 0.0)
    }

    // CropEditor.swift:423-424.
    @Test fun accessibilityStepsZoomByATenth() {
        assertEquals(1.1, CropGestures.zoomStepped(CropSettings(), closer = true).zoom, 1e-12)
        assertEquals(2.0, CropGestures.zoomStepped(CropSettings(zoom = 2.2), closer = false).zoom, 1e-12)
        assertEquals(8.0, CropGestures.zoomStepped(CropSettings(zoom = 7.9), closer = true).zoom, 0.0)
        assertEquals(1.0, CropGestures.zoomStepped(CropSettings(), closer = false).zoom, 0.0)
    }

    /**
     * CropEditor.swift:171-178, checked there at :532-535: zooming keeps a
     * preferred center another clip may still show, and moving across an edge
     * stores a visible position without changing the other axis.
     */
    @Test fun positionSliderStoresAVisibleCenterOnItsOwnAxisOnly() {
        val crop = CropSettings(y = 0.25, zoom = 1.2)
        val moved = CropGestures.moved(crop, x = -1.0, source = landscape, canvas = landscape)
        assertEquals(1 / 2.4, moved.x, 0.00001)
        assertEquals(0.25, moved.y, 0.0)
        // CropEditor.swift:536-537: in a portrait frame that position is still in reach.
        assertEquals(1 / 2.4, moved.clamped(landscape, portrait).x, 0.00001)
        val lowered = CropGestures.moved(crop, y = 0.0, source = landscape, canvas = landscape)
        assertEquals(1 / 2.4, lowered.y, 0.00001)
        assertEquals(0.5, lowered.x, 0.0)
    }

    // CropEditor.swift:179-183 and :267-270.
    @Test fun positionSlidersCoverWhatCanBeShown() {
        val crop = CropSettings(zoom = 1.2)
        val across = CropGestures.positionRange(crop, horizontal = true, source = landscape, canvas = landscape)
        assertEquals(1 / 2.4, across.start, 1e-12)
        assertEquals(1 - 1 / 2.4, across.endInclusive, 1e-12)
        assertFalse(CropGestures.positionLocked(crop, horizontal = true, source = landscape, canvas = landscape))
        // At zoom 1 the window spans the source: the slider is disabled over its whole range.
        assertEquals(0.0..1.0, CropGestures.positionRange(CropSettings(), horizontal = true, source = landscape, canvas = landscape))
        assertTrue(CropGestures.positionLocked(CropSettings(), horizontal = true, source = landscape, canvas = landscape))
        // A portrait frame on a landscape take can move across but not down.
        assertFalse(CropGestures.positionLocked(CropSettings(), horizontal = true, source = landscape, canvas = portrait))
        assertTrue(CropGestures.positionLocked(CropSettings(), horizontal = false, source = landscape, canvas = portrait))
        val sideways = CropGestures.positionRange(CropSettings(), horizontal = true, source = landscape, canvas = portrait)
        assertEquals(607.5 / 1920 / 2, sideways.start, 1e-12)
    }

    // CropEditor.swift:208.
    @Test fun resetKeepsOnlyTheIdentity() {
        assertEquals(CropSettings(id = "K"), CropGestures.reset(CropSettings(id = "K", x = 0.2, y = 0.9, zoom = 3.0, rotation = 40.0)))
    }

    // MARK: Shape

    // CropEditor.swift:92 and :226-236; :498 pins 1080×1920 for a portrait shape on a 1920×1080 project.
    @Test fun shapeDecidesTheCanvasAndTheStoredOutputSize() {
        assertEquals(portrait, CropShape.canvas(9.0 / 16, landscape, null))
        assertEquals(landscape, CropShape.canvas(null, landscape, portrait))
        // A chosen shape keeps the short edge of the size already pinned.
        assertEquals(Size(720.0, 720.0), CropShape.canvas(1.0, landscape, Size(720.0, 1280.0)))
        // "Original" with no crop left frees the size; with a crop it pins the footage's own canvas.
        assertNull(CropShape.outputSize(OutputShape.Original, anyCrop = false, changed = false, current = portrait, canvas = landscape))
        assertEquals(landscape, CropShape.outputSize(OutputShape.Original, anyCrop = true, changed = false, current = portrait, canvas = landscape))
        assertEquals(portrait, CropShape.outputSize(OutputShape.Portrait, anyCrop = false, changed = false, current = null, canvas = portrait))
        assertEquals(landscape, CropShape.outputSize(null, anyCrop = true, changed = true, current = null, canvas = landscape))
        // CropEditor.swift:507: nothing chosen and nothing changed leaves the size as it was.
        assertEquals(portrait, CropShape.outputSize(null, anyCrop = false, changed = false, current = portrait, canvas = landscape))
    }

    // MARK: The tool's state

    private val clip = TimelineClip(id = "C", sourceID = "S", start = 0.0, end = 4.0)
    private val spans = SpeedMapping.spans(clip, null)

    // CropEditor.swift:209-226, checked at :490, :493-497 and :505-507.
    @Test fun applyingStoresOnlyARealCrop() {
        val untouched = CropDraft.opening(null, id = "K")
        assertNull(untouched.applied(null, spans, 0.0, landscape, landscape))
        val framed = untouched.adjusted(CropSettings(x = 0.7, y = 0.4, zoom = 1.7, rotation = 17.0))
        val stored = framed.applied(null, spans, 0.0, landscape, portrait)
        assertEquals(CropSettings(id = "K", x = 0.7, y = 0.4, zoom = 1.7, rotation = 17.0), stored)
        // Opening and applying an unchanged crop changes nothing.
        assertEquals(stored, CropDraft.opening(stored).applied(stored, spans, 0.0, landscape, portrait))
        assertNull(CropDraft.opening(stored).applied(stored, spans, 0.0, landscape, portrait, removing = true))
        // Dragged back to the whole picture it is no crop any more; in another shape it still is one.
        val neutral = CropDraft.opening(stored).adjusted(CropSettings())
        assertNull(neutral.applied(stored, spans, 0.0, landscape, landscape))
        assertEquals(CropSettings(id = "K"), neutral.applied(stored, spans, 0.0, landscape, portrait))
        // Without the source's size nothing can be called neutral.
        assertEquals(CropSettings(id = "K"), neutral.applied(stored, spans, 0.0, null, landscape))
        // Values from a control are limited on the way in.
        assertEquals(8.0, untouched.adjusted(CropSettings(zoom = 20.0)).crop.zoom, 0.0)
    }

    // CropEditor.swift:32-47: choosing an end creates the motion; the controls then edit that end.
    @Test fun choosingAnEndStartsAMotionAndEditsThatEnd() {
        val opened = CropDraft.opening(null, id = "K")
        val onB = opened.selecting(EffectEndpoint.B, 4.0)
        assertEquals(opened.crop.pose, onB.animation!!.a)
        assertEquals(opened.crop.pose, onB.animation!!.b)
        assertEquals(4.0, onB.animation!!.duration, 0.0)
        assertEquals(EffectEndpoint.B, onB.endpoint)
        assertEquals(EffectEndpoint.B, onB.previewEndpoint)
        val closer = onB.adjusted(CropSettings(x = 0.65, y = 0.42, zoom = 2.0, rotation = 8.0))
        assertEquals(2.0, closer.captured!!.b["zoom"], 0.0)
        assertEquals(1.0, closer.captured!!.a["zoom"], 0.0)
        // Going to A shows A again and keeps what was set on B.
        val onA = closer.selecting(EffectEndpoint.A, 4.0)
        assertEquals(CropSettings(id = "K"), onA.crop)
        assertEquals(2.0, onA.animation!!.b["zoom"], 0.0)
        val done = onA.finishing()
        assertNull(done.endpoint)
        assertNull(done.previewEndpoint)
        assertEquals(onA.animation, done.animation)
    }

    // CropEditor.swift:214-222: the stored crop is end A, carrying the motion tied to the clip.
    @Test fun applyingAMotionStoresEndAAndTheClock() {
        val draft = CropDraft.opening(null, id = "K").selecting(EffectEndpoint.B, 4.0)
            .adjusted(CropSettings(x = 0.65, y = 0.42, zoom = 2.0, rotation = 8.0)).finishing()
        // End A shows the whole picture, but a moving crop is always kept.
        val stored = draft.applied(null, spans, 0.0, landscape, landscape)!!
        assertEquals(CropSettings(id = "K"), stored.copy(motion = null))
        assertEquals(listOf(StrobeTimePoint(0.0, 0.0), StrobeTimePoint(4.0, 4.0)), stored.motion!!.clock)
        assertEquals(CropSettings(id = "K", x = 0.65, y = 0.42, zoom = 2.0, rotation = 8.0), stored.at(4.0))
        assertTrue(stored.valid)
        // A clip that starts later in the selection begins that far into the motion.
        val later = draft.applied(null, spans, 1.5, landscape, landscape)!!
        assertEquals(listOf(StrobeTimePoint(0.0, 1.5), StrobeTimePoint(4.0, 5.5)), later.motion!!.clock)
        // Opening it again and applying, or nudging nothing, keeps the very same motion and clock.
        assertEquals(later, CropDraft.opening(later).applied(later, spans, 0.0, landscape, landscape))
        val same = CropDraft.opening(later).adjusted(later.copy(motion = null)).applied(later, spans, 0.0, landscape, landscape)!!
        assertSame(later.motion, same.motion)
        // A changed end is tied to the clip afresh.
        val changed = CropDraft.opening(later).adjusted(CropSettings(zoom = 1.5)).applied(later, spans, 0.0, landscape, landscape)!!
        assertEquals(1.5, changed.zoom, 0.0)
        assertEquals(0.0, changed.motion!!.clock.first().elapsed, 0.0)
        assertEquals(2.0, changed.motion!!.b["zoom"], 0.0)
    }

    // CropEditor.swift:48-70 and EffectAnimation.swift:53-62: the preview parks on the chosen end.
    @Test fun previewParksOnTheEndAndFollowsItsPeak() {
        val onB = CropDraft.opening(null).selecting(EffectEndpoint.B, 4.0).adjusted(CropSettings(zoom = 2.0))
        assertEquals(4.0 - frame, onB.previewPosition(1.25, 4.0), 1e-12)
        assertEquals(0.0, onB.selecting(EffectEndpoint.A, 4.0).previewPosition(1.25, 4.0), 0.0)
        val rising = onB.retimed(EffectTiming(curve = EffectCurve.RiseAndHold, rise = 1.0), 4.0)
        assertEquals(1.0, rising.previewPosition(1.25, 4.0), 1e-9)
        assertTrue(rising.animationChanged)
        // The same timing again is not a change.
        assertSame(rising, rising.retimed(rising.animation!!.timing, 4.0))
        // Scrubbing pins the position; the next adjustment returns to the end last edited.
        val scrubbed = rising.scrubbed()
        assertEquals(1.25, scrubbed.previewPosition(1.25, 4.0), 0.0)
        assertEquals(EffectEndpoint.B, scrubbed.adjusted(CropSettings(zoom = 3.0)).previewEndpoint)
        assertEquals(3.0, scrubbed.adjusted(CropSettings(zoom = 3.0)).captured!!.b["zoom"], 0.0)
        // Without a motion there is nothing to park on or retime.
        val plain = CropDraft.opening(null)
        assertEquals(1.25, plain.previewPosition(1.25, 4.0), 0.0)
        assertSame(plain, plain.retimed(EffectTiming(curve = EffectCurve.Smooth), 4.0))
    }

    // CropEditor.swift:198-207: parked on an end the picture shows that end; after scrubbing, the motion at that moment.
    @Test fun pictureShowsTheEndOrTheMotionAtThePlayhead() {
        val onB = CropDraft.opening(null, id = "K").selecting(EffectEndpoint.B, 4.0).adjusted(CropSettings(zoom = 2.0))
        val half = (4.0 - frame) / 2
        assertEquals(2.0, onB.displayed(half, spans, 0.0, null).zoom, 0.0)
        val between = onB.scrubbed().displayed(half, spans, 0.0, null)
        assertEquals(1.414213562373, between.zoom, 1e-9)
        assertEquals("K", between.id)
        assertNull(between.motion)
        assertEquals(CropSettings(id = "K"), CropDraft.opening(null, id = "K").displayed(half, spans, 0.0, null))
    }

    // CropEditor.swift:49-53: removing the motion leaves the framing on screen as a fixed crop.
    @Test fun removingTheMotionKeepsTheFramingOnScreen() {
        val onB = CropDraft.opening(null, id = "K").selecting(EffectEndpoint.B, 4.0).adjusted(CropSettings(zoom = 2.0))
        val stored = onB.applied(null, spans, 0.0, landscape, landscape)!!
        val removed = CropDraft.opening(stored).selecting(EffectEndpoint.B, 4.0).removingAnimation()
        assertNull(removed.animation)
        assertEquals(CropSettings(id = "K", zoom = 2.0), removed.applied(stored, spans, 0.0, landscape, landscape))
    }
}
