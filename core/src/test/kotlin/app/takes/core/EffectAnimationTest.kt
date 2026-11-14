package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectAnimationTest {
    private val frame = 1.0 / 30
    private val wholeClip = listOf(EffectPlaybackSpan(0.0, 4.0, 1.0))

    private fun animation(a: EffectPose, b: EffectPose, spans: List<EffectPlaybackSpan> = wholeClip) =
        EffectAnimation(a, spans.sumOf { it.duration }).copy(b = b, timing = EffectTiming(EffectCurve.RiseHoldReturn, 1.0, 1.0)).anchored(spans, 0.0)

    // Translated from EffectAnimationChecks.swift.

    @Test fun oneSecondToBThreeAtBOneBackToA() {
        val timing = EffectTiming(EffectCurve.RiseHoldReturn, rise = 1.0, release = 1.0)
        fun f(t: Double) = timing.fraction(t, 5.0, frame)
        assertEquals(0.0, f(0.0), 0.0)
        assertEquals(0.5, f(0.5), 0.0)
        assertEquals(1.0, f(1.0), 0.0)
        assertEquals(1.0, f(4.0), 0.0)
        assertTrue(f(4.5) < 0.5)
        assertEquals(0.0, f(5 - frame), 0.0)
    }

    @Test fun riseAndHoldReachesBEarlyAndHoldsToTheEnd() {
        val timing = EffectTiming(EffectCurve.RiseAndHold, rise = 1.0, release = 1.0)
        assertEquals(1.0, timing.fraction(4.99, 5.0, frame), 0.0)
        assertEquals(1.0, timing.endpoint(EffectEndpoint.B, 5.0, frame), 0.0)
    }

    @Test fun editingBFollowsThePeakButScrubbingPinsAFrame() {
        for (rise in listOf(0.5, 1.0, 2.0)) {
            val timing = EffectTiming(EffectCurve.RiseAndHold, rise = rise, release = 1.0)
            // Dragging the peak across an inspected frame must keep the playhead there.
            assertEquals(1.0, timing.previewPosition(1.0, null, 5.0, frame), 0.0)
            // Explicitly editing B follows the peak to its new frame.
            assertEquals(rise, timing.previewPosition(1.0, EffectEndpoint.B, 5.0, frame), 0.0)
        }
    }

    @Test fun everyCurveStaysBoundedOnShortSelections() {
        for (curve in EffectCurve.entries) for (rise in listOf(0.5, 1.0, 2.0)) {
            val timing = EffectTiming(curve, rise = rise, release = 1.0)
            for (length in listOf(frame, 0.1, 1.0, 5.0, 500.0)) for (t in listOf(-0.5, 0.0, length / 8, length / 2, length - frame, length + 1)) {
                val value = timing.fraction(t, length, frame)
                assertTrue("$curve $length $t -> $value", value in 0.0..1.0)
            }
        }
    }

    @Test fun animatedCropColorAndImageValidateAndSurviveSaving() {
        val crop = CropSettings().let { it.copy(motion = animation(it.pose, CropSettings(x = 0.65, y = 0.42, zoom = 2.0, rotation = 8.0).pose)) }
        val grade = ColorSettings().let { it.copy(motion = animation(it.pose, ColorSettings(look = ColorLook.Mono).pose)) }
        val start = ImageOverlay(filename = "image-animation-overlay.png", start = 0.0, end = 4.0, x = 0.2)
        val image = start.copy(motion = animation(start.pose, start.copy(x = 0.82, y = 0.75, width = 0.8, opacity = 0.5).pose))
        assertTrue(crop.valid)
        assertTrue(grade.valid && grade.motion!!.valid(grade.pose) { grade.applying(it).valid })
        assertTrue(image.valid)
        assertEquals(crop, documentJson.decodeFromString(CropSettings.serializer(), documentJson.encodeToString(CropSettings.serializer(), crop)))
        assertEquals(grade, documentJson.decodeFromString(ColorSettings.serializer(), documentJson.encodeToString(ColorSettings.serializer(), grade)))
        assertEquals(image, documentJson.decodeFromString(ImageOverlay.serializer(), documentJson.encodeToString(ImageOverlay.serializer(), image)))
        // The original compares frames at these times with the fixed A and B pictures.
        for (t in listOf(0.0, 4 - frame)) {
            assertEquals(crop.copy(motion = null), crop.at(t))
            assertEquals(grade.motion!!.a, grade.motion!!.pose(t))
        }
        for (t in listOf(1.0, 2.0, 3.0)) {
            assertEquals(crop.applying(crop.motion!!.b), crop.at(t))
            assertEquals(ColorLook.Mono, grade.applying(grade.motion!!.pose(t)).look)
            assertEquals(image.applying(image.motion!!.b), image.applying(image.motion!!.pose(t)))
        }
    }

    // Own tests.

    @Test fun plainCurvesRunFromTheFirstFrameToTheLast() {
        for (curve in listOf(EffectCurve.Linear, EffectCurve.EaseIn, EffectCurve.EaseOut, EffectCurve.Smooth)) {
            val timing = EffectTiming(curve)
            assertEquals(0.0, timing.fraction(0.0, 2.0, frame), 0.0)
            // The last frame shows B: there is no frame at the duration itself.
            assertEquals(1.0, timing.fraction(2 - frame, 2.0, frame), 1e-12)
            assertEquals(2 - frame, timing.endpoint(EffectEndpoint.B, 2.0, frame), 0.0)
            assertEquals(0.0, timing.endpoint(EffectEndpoint.A, 2.0, frame), 0.0)
        }
        val half = (2 - frame) / 2
        assertEquals(0.5, EffectTiming(EffectCurve.Linear).fraction(half, 2.0, frame), 1e-12)
        assertEquals(0.125, EffectTiming(EffectCurve.EaseIn).fraction(half, 2.0, frame), 1e-12)
        assertEquals(0.875, EffectTiming(EffectCurve.EaseOut).fraction(half, 2.0, frame), 1e-12)
        assertEquals(0.5, EffectTiming(EffectCurve.Smooth).fraction(half, 2.0, frame), 1e-12)
    }

    @Test fun riseAndReleaseAreFittedIntoTheDuration() {
        val timing = EffectTiming(EffectCurve.RiseHoldReturn, rise = 3.0, release = 3.0)
        assertEquals(EffectTiming.Phases(3.0, 0.0, 1.0), timing.phases(4.0))
        assertEquals(EffectTiming.Phases(2.0, 0.0, 0.0), timing.phases(2.0))
        assertEquals(EffectTiming.Phases(3.0, 7.0, 0.0), timing.copy(curve = EffectCurve.RiseAndHold).phases(10.0))
        assertEquals(EffectTiming.Phases(0.0, 0.0, 0.0), timing.phases(-1.0))
    }

    @Test fun rampShapesTheRiseAndTheReturn() {
        val timing = EffectTiming(EffectCurve.RiseHoldReturn, rise = 1.0, release = 1.0, ramp = RampShape.EaseIn)
        assertEquals(0.125, timing.fraction(0.5, 5.0, frame), 1e-12)
        for (shape in RampShape.entries) {
            assertEquals(0.0, shape.sample(0.0), 0.0)
            assertEquals(1.0, shape.sample(1.0), 0.0)
        }
    }

    @Test fun valuesInterpolateByKindOfControl() {
        val motion = EffectAnimation(
            a = EffectPose(mapOf("x" to 0.2, "zoom" to 1.0, "rotation" to 170.0)),
            b = EffectPose(mapOf("x" to 0.6, "zoom" to 4.0, "rotation" to -170.0)),
            duration = 1.0,
        )
        assertEquals(0.4, motion.value("x", 0.5), 1e-12)
        // Halfway between 1× and 4× is 2×, not 2.5×.
        assertEquals(2.0, motion.value("zoom", 0.5), 1e-12)
        // 170° to −170° is 20° through the half turn, not 340° back through zero.
        assertEquals(180.0, kotlin.math.abs(motion.value("rotation", 0.5)), 1e-9)
        assertEquals(175.0, motion.value("rotation", 0.25), 1e-9)
        assertEquals(-175.0, motion.value("rotation", 0.75), 1e-9)
        // A control only one end knows starts from zero.
        assertEquals(0.0, motion.a["missing"], 0.0)
    }

    @Test fun poseBetweenEndsCarriesOnlyNumbers() {
        val grade = ColorSettings(look = ColorLook.Warm, amount = 0.0)
        val motion = EffectAnimation(grade.pose, 2.0).copy(b = grade.copy(amount = 1.0).pose)
        val middle = motion.pose((2 - frame) / 2)
        assertEquals(0.5, middle["amount"], 1e-12)
        assertNull(middle.look)
        assertEquals(ColorLook.Warm, motion.pose(0.0).look)
        assertEquals(grade.pose.values.keys, middle.values.keys)
    }

    @Test fun clockFollowsSpeedAndSkipsRemovedFootage() {
        // Source 0–2 at 2×, then 3–4 at half speed: one second, then two.
        val spans = listOf(EffectPlaybackSpan(0.0, 2.0, 2.0), EffectPlaybackSpan(3.0, 4.0, 0.5))
        val motion = EffectAnimation(EffectPose(mapOf("x" to 0.0)), 3.0).copy(b = EffectPose(mapOf("x" to 1.0))).anchored(spans, 0.0)
        assertEquals(
            listOf(StrobeTimePoint(0.0, 0.0), StrobeTimePoint(2.0, 1.0), StrobeTimePoint(3.0, 1.0), StrobeTimePoint(4.0, 3.0)),
            motion.clock,
        )
        assertEquals(0.5, motion.elapsed(1.0), 1e-12)
        assertEquals(1.0, motion.elapsed(2.5), 1e-12)
        assertEquals(2.0, motion.elapsed(3.5), 1e-12)
        assertEquals(0.0, motion.elapsed(-1.0), 0.0)
        assertEquals(3.0, motion.elapsed(9.0), 0.0)
        // Adjacent spans share their boundary point.
        assertEquals(3, motion.anchored(listOf(EffectPlaybackSpan(0.0, 1.0, 1.0), EffectPlaybackSpan(1.0, 2.0, 2.0)), 0.5).clock.size)
        // Without a clock, source seconds are the clock.
        assertEquals(1.25, motion.copy(clock = emptyList()).elapsed(1.25), 0.0)
    }

    @Test fun splittingKeepsTheSameMotionOnBothPieces() {
        // Both pieces keep the motion unchanged, so each samples the clock of the whole.
        val crop = CropSettings().let { it.copy(motion = animation(it.pose, CropSettings(zoom = 2.0).pose)) }
        val shown = crop.at(0.5)
        assertEquals(crop.motion!!.value("zoom", 0.5), shown.zoom, 1e-12)
        assertNull(shown.motion)
        assertEquals(crop.id, shown.id)
    }

    @Test fun heldFrameRunsTheMotionOverItsOwnDuration() {
        val motion = EffectAnimation(EffectPose(mapOf("x" to 0.0)), 2.0).anchored(FrozenFrameSegment(sourceTime = 1.5, duration = 2.0, offset = 0.5), 0.25)
        assertEquals(listOf(StrobeTimePoint(2.0, 0.25), StrobeTimePoint(4.0, 2.25)), motion.clock)
        assertTrue(FrozenFrameSegment(1.5, 2.0).valid)
        assertFalse(FrozenFrameSegment(1.5, 10.5).valid)
        assertFalse(FrozenFrameSegment(-1.0, 1.0).valid)
    }

    @Test fun reapplyingUnchangedSettingsKeepsTheExistingClock() {
        val existing = animation(EffectPose(mapOf("x" to 0.0)), EffectPose(mapOf("x" to 1.0)))
        val edited = existing.copy(clock = emptyList())
        assertSame(existing, edited.placed(wholeClip, 2.0, existing))
        val changed = edited.with(EffectEndpoint.B, EffectPose(mapOf("x" to 0.5)))
        assertEquals(2.0, changed.placed(wholeClip, 2.0, existing).clock.first().elapsed, 0.0)
        assertEquals(0.5, changed[EffectEndpoint.B]["x"], 0.0)
    }

    @Test fun validationRejectsBrokenMotions() {
        val crop = CropSettings()
        val good = animation(crop.pose, CropSettings(zoom = 2.0).pose)
        fun valid(motion: EffectAnimation) = crop.copy(motion = motion).valid
        assertTrue(valid(good))
        assertFalse(valid(good.copy(duration = 0.0)))
        assertFalse(valid(good.copy(duration = Double.NaN)))
        assertFalse(valid(good.copy(frame = 0.0)))
        assertFalse(valid(good.copy(timing = good.timing.copy(rise = -1.0))))
        assertFalse(valid(good.copy(timing = good.timing.copy(release = Double.POSITIVE_INFINITY))))
        // The clock must move forward in source time and never back in elapsed time.
        assertFalse(valid(good.copy(clock = listOf(StrobeTimePoint(1.0, 0.0), StrobeTimePoint(1.0, 1.0)))))
        assertFalse(valid(good.copy(clock = listOf(StrobeTimePoint(0.0, 1.0), StrobeTimePoint(1.0, 0.0)))))
        // An end outside the crop's own ranges.
        assertFalse(valid(good.copy(b = CropSettings(zoom = 9.0).pose)))
        assertFalse(valid(good.copy(a = CropSettings(rotation = 181.0).pose)))
        // Both ends hold exactly the crop's controls.
        assertFalse(valid(good.copy(b = EffectPose(good.b.values - "rotation"))))
        assertFalse(valid(good.copy(b = EffectPose(good.b.values + ("opacity" to 1.0)))))
        assertFalse(valid(good.copy(b = EffectPose(good.b.values + ("x" to Double.NaN)))))
        // Only a color pose may carry a look or a LUT.
        assertFalse(valid(good.copy(b = good.b.copy(look = ColorLook.Mono))))
        val grade = ColorSettings()
        val lut = ImportedLUT("effect-lut-a.cube", "A")
        assertTrue(EffectAnimation(grade.pose, 1.0).copy(b = grade.pose.copy(lut = lut)).valid(grade.pose) { grade.applying(it).valid })
        assertFalse(EffectAnimation(grade.pose, 1.0).copy(b = grade.pose.copy(lut = lut.copy(filename = "../a.cube"))).valid(grade.pose) { true })
    }

    @Test fun colorRangesAndIdentity() {
        assertTrue(ColorSettings().isIdentity)
        assertFalse(ColorSettings(warmth = 0.2).isIdentity)
        assertEquals("Color balance", ColorSettings(warmth = 0.2).title)
        assertEquals("Mono", ColorSettings(look = ColorLook.Mono).title)
        assertFalse(ColorSettings(amount = 1.1).valid)
        assertFalse(ColorSettings(exposure = 4.5).valid)
        assertFalse(ColorSettings(localRecovery = -0.1).valid)
        assertFalse(ColorSettings(tint = Double.NaN).valid)
        val lut = ImportedLUT("effect-lut-a.cube", "A")
        val moving = ColorSettings().let { it.copy(imported = lut, motion = EffectAnimation(it.pose.copy(lut = lut), 1.0)) }
        assertEquals(listOf(lut, lut, lut), moving.lutAssets)
    }

    @Test fun imageRectangleKeepsItsAspectOnTheShorterEdge() {
        val image = ImageOverlay(filename = "a.png", x = 0.75, y = 0.25)
        // Bottom-up coordinates: a quarter from the top is three quarters up.
        assertEquals(Rect(480.0 - 72.0, 270.0 - 36.0, 144.0, 72.0), image.rect(Size(200.0, 100.0), Size(640.0, 360.0)))
        assertFalse(image.copy(width = 0.02).valid)
        assertFalse(image.copy(opacity = 1.5).valid)
        assertFalse(image.copy(end = 0.0).valid)
    }

    @Test fun motionWritesTheOriginalFields() {
        val motion = EffectAnimation(EffectPose(mapOf("x" to 0.5)), 2.0).anchored(listOf(EffectPlaybackSpan(0.0, 2.0, 1.0)), 0.0)
        val text = documentJson.encodeToString(EffectAnimation.serializer(), motion.copy(frame = 0.04))
        assertEquals(
            """{"a":{"values":{"x":0.5}},"b":{"values":{"x":0.5}},"timing":{"curve":"linear","rise":1.0,"release":1.0,"ramp":"linear"},""" +
                """"duration":2.0,"frame":0.04,"clock":[{"source":0.0,"elapsed":0.0},{"source":2.0,"elapsed":2.0}]}""",
            text,
        )
        val color = documentJson.encodeToString(EffectPose.serializer(), ColorSettings(look = ColorLook.GoldenHour, imported = ImportedLUT("effect-lut-a.cube", "A")).pose)
        assertTrue(color.contains(""""look":"goldenHour"""") && color.contains(""""lut":{"filename":"effect-lut-a.cube","name":"A","input":"rec709"}"""))
    }
}
