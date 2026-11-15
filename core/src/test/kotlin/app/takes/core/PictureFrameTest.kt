package app.takes.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureFrameTest {
    private val landscape = Size(1920.0, 1080.0)
    private val portrait = Size(1080.0, 1920.0)
    private val frame = PauseDetector.FRAME

    private fun assertRect(expected: Rect, actual: Rect, tolerance: Double = 1e-6) {
        assertEquals("x of $actual", expected.x, actual.x, tolerance)
        assertEquals("y of $actual", expected.y, actual.y, tolerance)
        assertEquals("width of $actual", expected.width, actual.width, tolerance)
        assertEquals("height of $actual", expected.height, actual.height, tolerance)
    }

    private fun assertPoint(x: Double, y: Double, actual: Point, tolerance: Double = 1e-6) {
        assertEquals("x of $actual", x, actual.x, tolerance)
        assertEquals("y of $actual", y, actual.y, tolerance)
    }

    /** The same picture, within rounding of the source second. */
    private fun assertSameFrame(expected: PictureFrame, actual: PictureFrame) {
        assertEquals(expected.sourceTime, actual.sourceTime, 1e-9)
        assertEquals(expected.fraction ?: -1.0, actual.fraction ?: -1.0, 1e-9)
        assertRect(expected.window, actual.window)
        assertEquals(expected.rotation, actual.rotation, 1e-9)
        assertEquals(expected.scale, actual.scale, 1e-9)
        for ((e, a) in expected.corners.zip(actual.corners)) assertPoint(e.x, e.y, a)
    }

    // MARK: A fixed framing

    // VideoTools.swift:23-47: at zoom 1 with a matching shape the window is the whole source and the transform does nothing.
    @Test fun identityCropShowsTheWholePictureUnchanged() {
        val shown = PictureFraming.frame(CropSettings(), 0.0, landscape, landscape)
        assertRect(Rect(0.0, 0.0, 1920.0, 1080.0), shown.window)
        assertRect(Rect(0.0, 0.0, 1920.0, 1080.0), shown.footprint)
        assertEquals(1.0, shown.scale, 1e-12)
        assertEquals(0.0, shown.rotation, 0.0)
        assertNull(shown.fraction)
        assertPoint(0.0, 0.0, Point.ZERO.applying(shown.transform))
        assertPoint(1920.0, 1080.0, Point(1920.0, 1080.0).applying(shown.transform))
        assertTrue(shown.fills)
    }

    // MediaRenderer.swift:297-300: a clip with no crop is fitted whole and centered, leaving bars in another shape.
    @Test fun uncroppedClipIsFittedInsideTheFrame() {
        val same = PictureFraming.frame(null, 0.0, landscape, landscape)
        assertEquals(Affine.IDENTITY, same.transform)
        assertTrue(same.fills)
        val tall = PictureFraming.frame(null, 0.0, landscape, portrait)
        assertNull(tall.crop)
        assertEquals(0.5625, tall.scale, 1e-12)
        assertRect(Rect(0.0, 0.0, 1920.0, 1080.0), tall.window)
        assertPoint(0.0, 656.25, Point.ZERO.applying(tall.transform))
        assertPoint(1080.0, 1263.75, Point(1920.0, 1080.0).applying(tall.transform))
        assertFalse(tall.fills)
    }

    // VideoTools.swift:100-112 (shapes) and :23-32 (window): what each shape cuts from a 1920×1080 take.
    @Test fun eachShapeCutsItsWindowFromALandscapeTake() {
        val expected = mapOf(
            OutputShape.Original to (Size(1920.0, 1080.0) to Rect(0.0, 0.0, 1920.0, 1080.0)),
            OutputShape.Landscape to (Size(1920.0, 1080.0) to Rect(0.0, 0.0, 1920.0, 1080.0)),
            OutputShape.Portrait to (Size(1080.0, 1920.0) to Rect(656.25, 0.0, 607.5, 1080.0)),
            OutputShape.Square to (Size(1080.0, 1080.0) to Rect(420.0, 0.0, 1080.0, 1080.0)),
            OutputShape.Feed to (Size(1080.0, 1350.0) to Rect(528.0, 0.0, 864.0, 1080.0)),
        )
        for (shape in OutputShape.entries) {
            val (canvas, window) = expected.getValue(shape)
            assertEquals(shape.title, canvas, OutputShape.canvas(landscape, shape.aspect))
            val shown = PictureFraming.frame(CropSettings(), 0.0, landscape, canvas)
            assertRect(window, shown.window)
            assertEquals(shape.title, canvas.width / window.width, shown.scale, 1e-9)
            assertTrue(shown.fills)
        }
    }

    // MARK: Rotation

    /**
     * VideoTools.swift:25-27: scale = max((cw·|cos| + ch·|sin|) / sw, (cw·|sin| + ch·|cos|) / sh) · zoom.
     * At zoom 1 that is the smallest magnification with no empty corner.
     */
    @Test fun minimumMagnificationThatFillsTheCornersOfATurnedFrame() {
        // Canvas to angle to (canvas pixels per source pixel, the same relative to upright), from a 1920×1080 take.
        val cases = listOf(
            Triple(Size(1920.0, 1080.0), 0.0, 1.0 to 1.0),
            Triple(Size(1920.0, 1080.0), 15.0, 1.426048573138 to 1.426048573138),
            Triple(Size(1920.0, 1080.0), 45.0, 1.964185503296 to 1.964185503296),
            Triple(Size(1920.0, 1080.0), 90.0, 1.777777777778 to 1.777777777778),
            Triple(Size(1080.0, 1920.0), 0.0, 1.777777777778 to 1.0),
            Triple(Size(1080.0, 1920.0), 15.0, 1.976020514061 to 1.111511539159),
            Triple(Size(1080.0, 1920.0), 45.0, 1.964185503296 to 1.104854345604),
            // A quarter turn shows the whole landscape take in a portrait frame: wider than upright.
            Triple(Size(1080.0, 1920.0), 90.0, 1.0 to 0.5625),
            Triple(Size(1080.0, 1080.0), 0.0, 1.0 to 1.0),
            Triple(Size(1080.0, 1080.0), 15.0, 1.224744871392 to 1.224744871392),
            Triple(Size(1080.0, 1080.0), 45.0, 1.414213562373 to 1.414213562373),
            Triple(Size(1080.0, 1080.0), 90.0, 1.0 to 1.0),
            Triple(Size(1080.0, 1350.0), 0.0, 1.25 to 1.0),
            Triple(Size(1080.0, 1350.0), 15.0, 1.466226327964 to 1.172981062371),
            Triple(Size(1080.0, 1350.0), 45.0, 1.590990257670 to 1.272792206136),
            Triple(Size(1080.0, 1350.0), 90.0, 1.0 to 0.8),
        )
        for ((canvas, angle, expected) in cases) {
            val label = "$canvas at $angle°"
            assertEquals(label, expected.first, PictureFraming.coverScale(angle, landscape, canvas), 1e-9)
            assertEquals(label, expected.second, PictureFraming.rotationZoom(angle, landscape, canvas), 1e-9)
            // The sign of the angle does not matter, and the frame reports the same magnification.
            assertEquals(label, expected.first, PictureFraming.coverScale(-angle, landscape, canvas), 1e-9)
            assertEquals(label, expected.first, PictureFraming.frame(CropSettings(rotation = angle), 0.0, landscape, canvas).scale, 1e-9)
        }
        // A portrait take mirrors the landscape numbers.
        assertEquals(1.964185503296, PictureFraming.coverScale(45.0, portrait, landscape), 1e-9)
        assertEquals(1.0, PictureFraming.coverScale(90.0, portrait, landscape), 1e-9)
        assertEquals(1.777777777778, PictureFraming.coverScale(90.0, portrait, portrait), 1e-9)
        assertEquals(0.0, PictureFraming.coverScale(45.0, Size(0.0, 0.0), landscape), 0.0)
    }

    // VideoTools.swift:23-38: the window keeps the canvas's shape; its turned footprint is what must fit the source.
    @Test fun turnedWindowAndFootprintInALandscapeFrame() {
        val cases = mapOf(
            0.0 to (Rect(0.0, 0.0, 1920.0, 1080.0) to Rect(0.0, 0.0, 1920.0, 1080.0)),
            15.0 to (Rect(286.811149295, 161.331271479, 1346.377701410, 757.337457043) to Rect(211.742824408, 0.0, 1496.514351183, 1080.0)),
            45.0 to (Rect(471.247792844, 265.076883475, 977.504414312, 549.846233051) to Rect(420.0, 0.0, 1080.0, 1080.0)),
            90.0 to (Rect(420.0, 236.25, 1080.0, 607.5) to Rect(656.25, 0.0, 607.5, 1080.0)),
        )
        for ((angle, expected) in cases) {
            val shown = PictureFraming.frame(CropSettings(rotation = angle), 0.0, landscape, landscape)
            assertRect(expected.first, shown.window)
            assertRect(expected.second, shown.footprint)
            assertEquals(angle, shown.rotation, 0.0)
            // The footprint touches the source: this is the widest framing without an empty corner.
            assertEquals(1080.0, shown.footprint.height, 1e-6)
            assertTrue(Rect(-1e-6, -1e-6, 1920.0 + 2e-6, 1080.0 + 2e-6).contains(shown.footprint))
        }
    }

    // VideoTools.swift:39-47: translate the window's center to the origin, rotate, scale, center on the canvas.
    @Test fun cornersOfATurnedFrameLieOnTheEdgeOfTheSource() {
        val shown = PictureFraming.frame(CropSettings(rotation = 15.0), 0.0, landscape, landscape)
        val (topLeft, topRight, bottomLeft, bottomRight) = shown.corners
        assertPoint(211.742824408, 348.468191026, topLeft)
        assertPoint(1512.243818140, 0.0, topRight)
        assertPoint(407.756181860, 1080.0, bottomLeft)
        assertPoint(1708.257175592, 731.531808974, bottomRight)
        // VideoToolsTests.swift:424: a panned, zoomed, turned crop in a portrait frame.
        val framed = PictureFraming.frame(CropSettings(x = 0.75, y = 0.3, zoom = 2.0, rotation = 17.0), 0.0, landscape, portrait)
        assertEquals(3.984938097314, framed.scale, 1e-9)
        assertPoint(1440.0, 324.0, Point(framed.window.midX, framed.window.midY))
        assertPoint(1239.976465057, 133.238731792, framed.corners[0])
        assertPoint(1499.154678424, 54.0, framed.corners[1])
        assertPoint(1380.845321576, 594.0, framed.corners[2])
        assertPoint(1640.023534943, 514.761268208, framed.corners[3])
    }

    // VideoTools.swift:10: "Clockwise degrees". In top-down pixels a positive angle turns the picture clockwise on screen.
    @Test fun positiveRotationTurnsThePictureClockwise() {
        val shown = PictureFraming.frame(CropSettings(rotation = 90.0), 0.0, landscape, landscape)
        // The top of the picture ends on the right of the frame, its right at the bottom.
        assertPoint(1920.0, 540.0, Point(960.0, 0.0).applying(shown.transform))
        assertPoint(960.0, 1080.0, Point(1263.75, 540.0).applying(shown.transform))
        assertPoint(960.0, 540.0, Point(960.0, 540.0).applying(shown.transform))
    }

    // VideoToolCompositor.swift:155-156 and MediaRenderer.swift:302: the mirror flips the finished frame, after the crop.
    @Test fun mirrorFlipsTheFrameAfterTheCrop() {
        val crop = CropSettings(x = 0.75, y = 0.25, zoom = 2.0)
        val plain = PictureFraming.frame(crop, 0.0, landscape, landscape)
        val flipped = PictureFraming.frame(crop, 0.0, landscape, landscape, MirrorSettings(horizontal = true))
        assertRect(Rect(960.0, 0.0, 960.0, 540.0), plain.window)
        assertEquals(plain.window, flipped.window)
        assertPoint(0.0, 0.0, Point(960.0, 0.0).applying(plain.transform))
        assertPoint(1920.0, 0.0, Point(960.0, 0.0).applying(flipped.transform))
        assertPoint(0.0, 1080.0, Point(1920.0, 540.0).applying(flipped.transform))
        val upsideDown = PictureFraming.frame(null, 0.0, landscape, landscape, MirrorSettings(vertical = true))
        assertPoint(0.0, 1080.0, Point.ZERO.applying(upsideDown.transform))
    }

    // MARK: Motion

    private val clip = TimelineClip(id = "C", sourceID = "S", start = 0.0, end = 4.0)
    private val start = CropSettings(id = "K")
    private val finish = CropSettings(id = "K", x = 0.65, y = 0.42, zoom = 2.0, rotation = 8.0)

    private fun moving(curve: EffectCurve = EffectCurve.Linear, on: TimelineClip = clip): CropSettings {
        val spans = SpeedMapping.spans(on, on.speed)
        val motion = EffectAnimation(start.pose, SpeedMapping.duration(spans))
            .copy(b = finish.pose, timing = EffectTiming(curve = curve))
            .anchored(spans, 0.0)
        return start.copy(motion = motion)
    }

    /**
     * EffectAnimation.swift:63-70 (curves over the first to the last frame)
     * and :166-174 (position linear, zoom in ratios, angle the short way).
     * The poses are EffectAnimationChecks.swift:55's A and B.
     */
    @Test fun motionAtTheStartAQuarterHalfAndTheEndWithEachCurve() {
        val last = 4.0 - frame
        // Share of the way, x, y, zoom, rotation, canvas pixels per source pixel.
        val expected = mapOf(
            EffectCurve.Linear to listOf(
                listOf(0.0, 0.5, 0.5, 1.0, 0.0, 1.0),
                listOf(0.25, 0.5375, 0.48, 1.189207115003, 2.0, 1.262265312895),
                listOf(0.5, 0.575, 0.46, 1.414213562373, 4.0, 1.586147366960),
                listOf(1.0, 0.65, 0.42, 2.0, 8.0, 2.475373829786),
            ),
            EffectCurve.EaseIn to listOf(
                listOf(0.0, 0.5, 0.5, 1.0, 0.0, 1.0),
                listOf(0.015625, 0.50234375, 0.49875, 1.010889286052, 0.125, 1.014807620852),
                listOf(0.125, 0.51875, 0.49, 1.090507732665, 1.0, 1.124176281586),
                listOf(1.0, 0.65, 0.42, 2.0, 8.0, 2.475373829786),
            ),
            EffectCurve.EaseOut to listOf(
                listOf(0.0, 0.5, 0.5, 1.0, 0.0, 1.0),
                listOf(0.578125, 0.58671875, 0.45375, 1.492907728291, 4.625, 1.702053419756),
                listOf(0.875, 0.63125, 0.43, 1.834008086409, 7.0, 2.217687643872),
                listOf(1.0, 0.65, 0.42, 2.0, 8.0, 2.475373829786),
            ),
            EffectCurve.Smooth to listOf(
                listOf(0.0, 0.5, 0.5, 1.0, 0.0, 1.0),
                listOf(0.15625, 0.5234375, 0.4875, 1.114386742596, 1.25, 1.157339715260),
                listOf(0.5, 0.575, 0.46, 1.414213562373, 4.0, 1.586147366960),
                listOf(1.0, 0.65, 0.42, 2.0, 8.0, 2.475373829786),
            ),
        )
        for ((curve, rows) in expected) {
            val crop = moving(curve)
            for ((index, t) in listOf(0.0, 0.25, 0.5, 1.0).withIndex()) {
                val shown = PictureFraming.frame(clip.copy(crop = crop), t * last, landscape, landscape)
                val (fraction, x, y, zoom, rotation) = rows[index]
                val label = "$curve at $t"
                assertEquals(label, fraction, shown.fraction!!, 1e-9)
                assertEquals(label, x, shown.crop!!.x, 1e-9)
                assertEquals(label, y, shown.crop!!.y, 1e-9)
                assertEquals(label, zoom, shown.crop!!.zoom, 1e-9)
                assertEquals(label, rotation, shown.crop!!.rotation, 1e-9)
                assertEquals(label, rows[index][5], shown.scale, 1e-9)
                // None of these framings is pushed back by an edge: the center shown is the one asked for.
                assertPoint(x * 1920, y * 1080, Point(shown.window.midX, shown.window.midY))
                assertNull(shown.crop!!.motion)
                assertEquals("K", shown.crop!!.id)
            }
        }
        // The ends are exactly the stored poses, and the motion holds past the last frame.
        val crop = moving()
        assertEquals(start, PictureFraming.frame(crop, 0.0, landscape, landscape).crop)
        assertEquals(finish, PictureFraming.frame(crop, 4.0, landscape, landscape).crop)
        assertEquals(finish, PictureFraming.frame(crop, 9.0, landscape, landscape).crop)
    }

    // EffectAnimationChecks.swift:44 and :134-139: one second to B, B from 1 to 3, back at A on the last frame.
    @Test fun riseHoldAndReturnShowsBInTheMiddleAndAAtBothEnds() {
        val motion = moving().motion!!.copy(timing = EffectTiming(curve = EffectCurve.RiseHoldReturn, rise = 1.0, release = 1.0))
        val crop = start.copy(motion = motion)
        for (t in listOf(0.0, 4.0 - frame)) assertEquals(start, PictureFraming.frame(crop, t, landscape, landscape).crop)
        for (t in listOf(1.0, 2.0, 3.0)) assertEquals(finish, PictureFraming.frame(crop, t, landscape, landscape).crop)
        assertEquals(0.5, PictureFraming.frame(crop, 0.5, landscape, landscape).fraction!!, 1e-9)
    }

    // MARK: Speed, split, trim

    /**
     * EffectAnimation.swift:109-127: the clock ties elapsed seconds to source
     * seconds when the motion is placed. Nothing rewrites it afterwards, so a
     * later speed change plays the same move over the same footage in half
     * the time (VideoToolCompositor.swift:173 samples at the source second).
     */
    @Test fun atTwiceTheSpeedTheSameFootageShowsTheSameFraming() {
        val state = EditState(listOf(clip.copy(crop = moving())))
        val fast = TimelineEditing.setSpeed(state, 0, 2.0)!!.clips[0]
        assertEquals(2.0, fast.duration, 1e-12)
        assertEquals(state.clips[0].crop, fast.crop)
        for (offset in listOf(0.0, 0.5, 1.0, 1.5)) {
            val before = PictureFraming.frame(state.clips[0], offset * 2, landscape, landscape)
            val after = PictureFraming.frame(fast, offset, landscape, landscape)
            assertEquals(offset * 2, after.sourceTime, 1e-12)
            assertEquals(before, after)
        }
        // Its last frame is two source frames before the end, so a plain curve stops a hair short of B.
        val last = PictureFraming.frame(fast, 2.0 - frame, landscape, landscape)
        assertEquals(118.0 / 119, last.fraction!!, 1e-9)
    }

    // EffectAnimation.swift:119-124: a motion placed on a clip already at 2× runs over its two timeline seconds.
    @Test fun motionMadeOnAFastClipRunsOverItsShorterDuration() {
        val fast = clip.copy(speed = SpeedSettings(id = "V", rate = 2.0))
        val crop = moving(on = fast)
        assertEquals(2.0, crop.motion!!.duration, 1e-12)
        assertEquals(listOf(StrobeTimePoint(0.0, 0.0), StrobeTimePoint(4.0, 2.0)), crop.motion!!.clock)
        val shown = PictureFraming.frame(fast.copy(crop = crop), 1.0, landscape, landscape)
        assertEquals(2.0, shown.sourceTime, 1e-12)
        assertEquals(1.0 / (2.0 - frame), shown.fraction!!, 1e-9)
        assertEquals(finish, PictureFraming.frame(fast.copy(crop = crop), 2.0 - frame, landscape, landscape).crop)
    }

    // EffectAnimationChecks.swift:76-79 and :131: both pieces keep the same motion, and no frame changes.
    @Test fun splitKeepsTheMotionAndEveryFrame() {
        val state = EditState(listOf(clip.copy(crop = moving())))
        val pieces = TimelineEditing.split(state, 1.5)!!.clips
        assertEquals(2, pieces.size)
        // VideoToolsTests.swift:429-430: splitting preserves the crop's identity too.
        assertEquals(state.clips[0].crop, pieces[0].crop)
        assertEquals(state.clips[0].crop, pieces[1].crop)
        for (t in listOf(0.0, 0.5, 1.0, 2.0, 3.0, 3.5, 4.0 - frame)) {
            val whole = PictureFraming.frame(state.clips[0], t, landscape, landscape)
            val piece = if (t < 1.5) PictureFraming.frame(pieces[0], t, landscape, landscape) else PictureFraming.frame(pieces[1], t - 1.5, landscape, landscape)
            assertSameFrame(whole, piece)
        }
        assertEquals(2.0 / (4.0 - frame), PictureFraming.frame(pieces[1], 0.5, landscape, landscape).fraction!!, 1e-9)
    }

    // EffectAnimation.swift:136-139: outside the clock the motion holds its first or last moment.
    @Test fun trimmingMovesThroughTheMotionAndExtendingHoldsItsEnds() {
        val state = EditState(listOf(clip.copy(crop = moving())))
        val shorter = TimelineEditing.trim(state, 0, 1.0, 3.0, 4.0)!!.clips[0]
        assertEquals(state.clips[0].crop, shorter.crop)
        assertEquals(PictureFraming.frame(state.clips[0], 1.0, landscape, landscape), PictureFraming.frame(shorter, 0.0, landscape, landscape))
        assertEquals(PictureFraming.frame(state.clips[0], 2.5, landscape, landscape), PictureFraming.frame(shorter, 1.5, landscape, landscape))
        // A motion made on the middle two seconds, then the clip opened up to the whole take.
        val middle = clip.copy(start = 1.0, end = 3.0)
        val longer = clip.copy(crop = moving(on = middle))
        assertEquals(listOf(StrobeTimePoint(1.0, 0.0), StrobeTimePoint(3.0, 2.0)), longer.crop!!.motion!!.clock)
        assertEquals(start, PictureFraming.frame(longer, 0.5, landscape, landscape).crop)
        assertEquals(finish, PictureFraming.frame(longer, 3.5, landscape, landscape).crop)
        assertEquals(1.0 / (2.0 - frame), PictureFraming.frame(longer, 2.0, landscape, landscape).fraction!!, 1e-9)
    }

    // VideoTools.swift:264-272: with footage removed from inside a clip, the offset skips the removed source seconds.
    @Test fun motionTimeSkipsRemovedFootage() {
        val cuts = listOf(SourceInterval(1.0, 2.0))
        assertEquals(0.5, PictureFraming.motionTime(clip, 0.5, cuts), 1e-12)
        assertEquals(2.5, PictureFraming.motionTime(clip, 1.5, cuts), 1e-12)
        assertEquals(1.5, PictureFraming.motionTime(clip, 1.5), 1e-12)
        assertEquals(3.0, PictureFraming.motionTime(clip.copy(speed = SpeedSettings(rate = 2.0)), 1.5), 1e-12)
    }

    // VideoToolCompositor.swift:169-172: a held frame's motion runs on from the hold's own start.
    @Test fun heldFrameKeepsItsMotionRunning() {
        val hold = FrozenFrameSegment(sourceTime = 1.5, duration = 2.0, offset = 0.5)
        assertEquals(2.0, PictureFraming.motionTime(hold, 0.0), 1e-12)
        assertEquals(3.25, PictureFraming.motionTime(hold, 1.25), 1e-12)
        assertEquals(2.0, PictureFraming.motionTime(hold, -1.0), 1e-12)
        val motion = EffectAnimation(start.pose, 2.0).copy(b = finish.pose).anchored(hold, 0.0)
        assertEquals(finish, PictureFraming.frame(start.copy(motion = motion), PictureFraming.motionTime(hold, 2.0 - frame), landscape, landscape).crop)
    }

    // MARK: Forms a renderer takes

    // Own test: the same transform in a vertex shader's coordinates and in android.graphics.Matrix order.
    @Test fun transformInNormalizedCoordinatesAndMatrixOrder() {
        val whole = PictureFraming.frame(CropSettings(), 0.0, landscape, landscape).transform.normalized(landscape, landscape)
        for ((actual, expected) in listOf(whole.a to 1.0, whole.b to 0.0, whole.c to 0.0, whole.d to 1.0, whole.tx to 0.0, whole.ty to 0.0)) {
            assertEquals(expected, actual, 1e-12)
        }
        val shown = PictureFraming.frame(CropSettings(x = 0.75, y = 0.3, zoom = 2.0, rotation = 17.0), 0.0, landscape, portrait)
        val normalized = shown.transform.normalized(landscape, portrait)
        for (pixel in listOf(Point(0.0, 0.0), Point(1920.0, 0.0), Point(700.0, 900.0), Point(1440.0, 324.0))) {
            val canvasPixel = pixel.applying(shown.transform)
            val device = Point(pixel.x / 960 - 1, 1 - pixel.y / 540).applying(normalized)
            assertPoint(canvasPixel.x / 540 - 1, 1 - canvasPixel.y / 960, device, 1e-9)
        }
        // The center of the window is the center of the frame.
        assertPoint(0.0, 0.0, Point(1440.0 / 960 - 1, 1 - 324.0 / 540).applying(normalized), 1e-9)
        val t = shown.transform
        assertArrayEquals(
            floatArrayOf(t.a.toFloat(), t.c.toFloat(), t.tx.toFloat(), t.b.toFloat(), t.d.toFloat(), t.ty.toFloat(), 0f, 0f, 1f),
            t.matrixValues(), 0f,
        )
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), Affine.IDENTITY.matrixValues(), 0f)
    }
}
