package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the effects of a clip come to on one frame, against the original's
 * renderer: YouRec/VideoEffects/MetalVideoEffects.swift, `render` (:559-572,
 * :592-593, :627-638) and `uniforms` (:268-291).
 */
class VideoEffectFrameTest {
    private fun number(value: Double) = EffectValue.Number(value)
    private fun toggle(value: Boolean) = EffectValue.Toggle(value)
    private fun choice(value: String) = EffectValue.Choice(value)
    private fun effect(kind: VideoEffectID, start: Double = 0.0, end: Double = VideoEffectSettings.MAXIMUM_TIME) = VideoEffectSettings(kind, start, end)
    private fun kinds(frame: List<EffectUniforms>) = frame.map { it.effect }

    // :559-572, :592-593.
    @Test fun theEffectsAreDrawnKeyCameraTimeColorQuantizationTextureFrame() {
        val added = listOf(
            VideoEffectID.Widescreen, VideoEffectID.Halation, VideoEffectID.Quantization, VideoEffectID.Kaleidoscope, VideoEffectID.Noir,
            VideoEffectID.Undercranking, VideoEffectID.CrashZoom, VideoEffectID.ColorCorrection, VideoEffectID.AutoBackground,
            VideoEffectID.SilentTint, VideoEffectID.NightFix, VideoEffectID.SixteenMM, VideoEffectID.ChromaKey, VideoEffectID.StepPrinting,
            VideoEffectID.FreezeFrame, VideoEffectID.BleachBypass,
        ).map { effect(it) }
        assertEquals(
            listOf(
                VideoEffectID.AutoBackground, VideoEffectID.ChromaKey,
                VideoEffectID.CrashZoom, VideoEffectID.Kaleidoscope,
                // The picture terms of the time effects come here, before the colour; in the applied list the family is last.
                VideoEffectID.Undercranking, VideoEffectID.FreezeFrame,
                VideoEffectID.ColorCorrection, VideoEffectID.Noir, VideoEffectID.SilentTint, VideoEffectID.BleachBypass,
                VideoEffectID.Quantization,
                VideoEffectID.Halation, VideoEffectID.SixteenMM,
                VideoEffectID.Widescreen,
            ),
            kinds(EffectFrame.of(added, 1.0, 30)),
        )
        // Night fix and Step Printing are not stages of this chain.
        assertTrue(EffectFrame.of(listOf(effect(VideoEffectID.NightFix), effect(VideoEffectID.StepPrinting)), 1.0, 30).isEmpty())
        // Two of a kind keep the order they were added in.
        val first = effect(VideoEffectID.Noir)
        val second = effect(VideoEffectID.Noir)
        val between = effect(VideoEffectID.SilentTint)
        assertEquals(listOf(second.id, between.id, first.id), EffectFrame.of(listOf(second, between, first), 0.0, 0).map { it.id })
        assertTrue(EffectFrame.of(emptyList(), 0.0, 0).isEmpty())
    }

    // :560-561, :572; VideoEffectModel.swift:194-196.
    @Test fun onlyTheEffectsThatAreOnAtTheSecondAreDrawn() {
        val noir = effect(VideoEffectID.Noir, 2.0, 5.0)
        val fading = effect(VideoEffectID.SilentTint).withKeyframe("intensity", 3.0, number(1.0)).withKeyframe("intensity", 4.0, number(0.0))
        val zoom = effect(VideoEffectID.Kaleidoscope, 0.0, 3.0)
        val all = listOf(noir, fading, zoom, effect(VideoEffectID.BleachBypass).copy(enabled = false))
        assertEquals(listOf(VideoEffectID.Kaleidoscope, VideoEffectID.SilentTint), kinds(EffectFrame.of(all, 1.0, 0)))
        assertEquals(listOf(VideoEffectID.Kaleidoscope, VideoEffectID.Noir, VideoEffectID.SilentTint), kinds(EffectFrame.of(all, 2.0, 0)))
        assertEquals(listOf(VideoEffectID.Noir, VideoEffectID.SilentTint), kinds(EffectFrame.of(all, 3.0, 0)))
        assertEquals(listOf(VideoEffectID.Noir), kinds(EffectFrame.of(all, 4.0, 0)))
        assertTrue(EffectFrame.of(all, 5.0, 0).isEmpty())
        // The intensity a shader mixes with is the one of the second.
        assertEquals(0.5, EffectFrame.of(all, 3.5, 0).last().intensity, 1e-12)
        // A camera effect has no intensity control and mixes fully.
        assertEquals(1.0, EffectFrame.of(all, 1.0, 0).first().intensity, 0.0)
    }

    // :562-565; Tests/VideoEffectGPUTests.swift:422-433.
    @Test fun aNegativeStrobeIsDrawnOnItsNegativeFramesOnly() {
        val strobe = effect(VideoEffectID.NegativeStrobe, 1.0, 2.0)
            .with("frequency", number(15.0)).with("timedBurst", toggle(true)).with("burstLength", number(0.2))
        for (f in listOf(0, 1, 2, 5, 6, 3, -1, 30)) {
            val frame = EffectFrame.of(listOf(strobe), 1 + f / 30.0, 30 + f)
            assertEquals("frame $f", f in listOf(1, 3, 5), frame.isNotEmpty())
        }
        // On a held frame the clock is the time it has been held.
        val free = strobe.with("timedBurst", toggle(false))
        assertTrue(EffectFrame.of(listOf(free), 1.5, 0, holdTime = 0.0).isEmpty())
        assertEquals(1, EffectFrame.of(listOf(free), 1.5, 0, holdTime = 1 / 30.0).size)
        // Half the movie's rate is the fastest it changes: at 24 frames a second, 15 cycles become 12.
        assertEquals(listOf(false, true, false, true), (0..3).map { EffectFrame.of(listOf(free), 1 + it / 24.0, it, frameRate = 24.0).isNotEmpty() })
        // The block carries nothing the shader's branch reads but the intensity: it inverts.
        val drawn = EffectFrame.of(listOf(free.with("intensity", number(0.4))), 1 + 1 / 30.0, 31).single()
        assertEquals(24, drawn.kind)
        assertEquals(0.4, drawn.intensity, 0.0)
        assertEquals(15.0, drawn.number("frequency"), 0.0)
    }

    // :566-568; Tests/VideoEffectGPUTests.swift:408-410, "Centered RGB pads preserve the source".
    @Test fun anRGBOffsetIsDrawnOnlyWhileAChannelIsOffItsPlace() {
        val centred = effect(VideoEffectID.RgbOffset)
        assertTrue(EffectFrame.of(listOf(centred), 0.0, 0).isEmpty())
        val moved = centred.with("blueY", number(-0.25))
        val block = EffectFrame.of(listOf(moved), 0.0, 0).single()
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0, 0.0, -0.25), block.p.take(6).map { it.x })
        val keyed = centred.withKeyframe("redX", 1.0, number(0.0)).withKeyframe("redX", 2.0, number(0.5))
        assertTrue(EffectFrame.of(listOf(keyed), 1.0, 0).isEmpty())
        assertEquals(0.25, EffectFrame.of(listOf(keyed), 1.5, 0).single().number("redX"), 1e-12)
    }

    // :272-284. The shader's own indices are in YouRec/VideoEffects/VideoEffects.metal:259-299, :332-356.
    @Test fun theParametersArePackedInTheCataloguesOrder() {
        val correction = effect(VideoEffectID.ColorCorrection)
            .with("exposure", number(0.3)).with("contrast", number(1.3)).with("shadows", number(0.3)).with("highlights", number(-0.4))
            .with("blacks", number(0.1)).with("whites", number(-0.1)).with("pivot", number(0.4)).with("temperature", number(0.2))
            .with("tint", number(-0.1)).with("saturation", number(1.15)).with("vibrance", number(0.4)).with("hue", number(12.0))
            .with("intensity", number(0.5))
        val block = EffectFrame.of(listOf(correction), 0.0, 7).single()
        // colorCorrection() reads p[0] exposure … p[11] hue, with the pivot at p[6].
        assertEquals(listOf(0.3, 1.3, 0.3, -0.4, 0.1, -0.1, 0.4, 0.2, -0.1, 1.15, 0.4, 12.0), block.p.take(12).map { it.x })
        assertTrue(block.p.drop(12).all { it == EffectUniformVector() })
        assertEquals(40, block.p.size)
        assertEquals(20, block.kind)
        assertEquals(0.5, block.intensity, 0.0)
        assertEquals(7, block.frame)
        assertEquals(0, block.flags)
        assertEquals(0, block.count)
        assertEquals(30.0, block.fps, 0.0)
        // A choice is its index among the catalogue's options; a toggle is 1 or 0.
        val tint = EffectFrame.of(listOf(effect(VideoEffectID.SilentTint).with("tint", choice("Sepia")).with("mode", choice("Tone"))), 0.0, 0).single()
        assertEquals(listOf(4.0, 1.0, 1.15), tint.p.take(3).map { it.x })
        assertEquals(listOf(1.0, 0.0), EffectFrame.of(listOf(effect(VideoEffectID.SilentTint)), 0.0, 0).single().p.take(2).map { it.x })
        val noir = EffectFrame.of(listOf(effect(VideoEffectID.Noir)), 0.0, 0).single()
        assertEquals(listOf(1.5, 0.12, 1.0, 0.0), noir.p.take(4).map { it.x })
        val night = EffectFrame.of(listOf(effect(VideoEffectID.DayForNight).with("moonlight", toggle(false))), 0.0, 0).single()
        assertEquals(listOf(0.6, 0.7, 0.0), night.p.take(3).map { it.x })
        val iris = EffectFrame.of(listOf(effect(VideoEffectID.Expressionism).with("iris", choice("Circle")).with("tint", toggle(true))), 0.0, 0).single()
        assertEquals(listOf(2.2, 0.7, 1.0, 1.0), iris.p.take(4).map { it.x })
        assertEquals(listOf(1.4, 0.6, 0.2), EffectFrame.of(listOf(effect(VideoEffectID.ThreeStrip)), 0.0, 0).single().p.take(3).map { it.x })
        assertEquals(0.7, EffectFrame.of(listOf(effect(VideoEffectID.BleachBypass)), 0.0, 0).single().p[0].x, 0.0)
        val mirror = EffectFrame.of(listOf(effect(VideoEffectID.Kaleidoscope).with("divisions", number(5.0)).with("sourceAngle", number(-30.0))), 0.0, 0).single()
        assertEquals(listOf(5.0, 0.5, 0.5, 0.0, 0.0, 0.0, -30.0), mirror.p.take(7).map { it.x })
        assertEquals(22, mirror.kind)
        // A colour is its linear RGB with 1 in the fourth place.
        val isolation = EffectFrame.of(listOf(effect(VideoEffectID.ColorIsolation).copy(algorithm = 1)), 0.0, 0).single()
        val red = EffectColorMath.linear(EffectRGB.hex("#C0392B"))
        assertEquals(EffectUniformVector(red.x, red.y, red.z, 1.0), isolation.p[1])
        assertEquals(isolation.p[1], isolation.vector("color"))
        assertEquals(listOf(0.0, 22.0, 0.4), listOf(isolation.p[0].x, isolation.p[2].x, isolation.p[3].x))
        assertEquals(0, isolation.flags)
        // A file leaves its slot at zero.
        val key = EffectFrame.of(listOf(effect(VideoEffectID.ChromaKey).with("cleanPlate", EffectValue.Media("plate.png"))), 0.0, 0).single()
        assertEquals(EffectUniformVector(), key.p[12])
        assertEquals(26, VideoEffectID.ChromaKey.descriptor.parameters.size)
        assertEquals(0.3, key.number("matchStrength"), 0.0)
        assertEquals(key.p[25].x, key.number("matchStrength"), 0.0)
        assertEquals(0.0, key.number("nothing"), 0.0)
    }

    // :270-271, :284, :636-638.
    @Test fun theClockTheFlagsAndTheRelease() {
        val noir = effect(VideoEffectID.Noir, 2.0, 5.0)
        val block = EffectFrame.of(listOf(noir), 3.25, 97, frameRate = 25.0).single()
        assertEquals(1.25, block.time, 1e-12)
        assertEquals(3.0, block.duration, 0.0)
        assertEquals(25.0, block.fps, 0.0)
        assertEquals(97, block.frame)
        assertEquals(noir.id, block.id)
        // A carried phase is added to the effect's clock.
        val strobe = effect(VideoEffectID.NegativeStrobe, 4.0, 9.0).with("frequency", number(15.0)).copy(phaseOffset = 2.0)
        assertEquals(2.0 + 1.0 / 30, EffectFrame.of(listOf(strobe), 4 + 1.0 / 30, 0).single().time, 1e-12)
        // A Freeze Frame's clock is the time held; any other effect on a held frame keeps its source clock.
        val hold = effect(VideoEffectID.FreezeFrame, 6.0, 6.0 + 1.0 / 30)
        val held = EffectFrame.of(listOf(hold, effect(VideoEffectID.Noir, 5.0, 8.0)), 6.0, 240, holdTime = 1.5)
        assertEquals(listOf(1.5, 1.0), held.map { it.time })
        assertEquals(0.0, EffectFrame.of(listOf(hold), 6.0, 240).single().time, 0.0)
        // New Color Isolation and Chroma Key recipes ask for the temporal matte; saved older ones do not.
        assertEquals(EffectUniforms.FLAG_TEMPORAL_MATTE, EffectFrame.of(listOf(effect(VideoEffectID.ColorIsolation)), 0.0, 0).single().flags)
        assertEquals(0, EffectFrame.of(listOf(effect(VideoEffectID.ColorIsolation).copy(algorithm = null)), 0.0, 0).single().flags)
        // A released Crash Zoom: the flag, and the release on the effect's clock in slot 39.
        val crash = effect(VideoEffectID.CrashZoom, 3.0, 8.0).copy(releaseTime = 6.0, phaseOffset = 0.5)
        val released = EffectFrame.of(listOf(crash), 4.0, 0).single()
        assertEquals(EffectUniforms.FLAG_RELEASE, released.flags)
        assertEquals(3.5, released.p[39].x, 0.0)
        assertEquals(1.5, released.time, 0.0)
        assertEquals(0.0, EffectFrame.of(listOf(crash.copy(releaseTime = 1.0, phaseOffset = 0.0)), 4.0, 0).single().p[39].x, 0.0)
        // What the frame brings.
        val quant = effect(VideoEffectID.Quantization)
        val palette = EffectPalette(listOf(EffectRGB(0.0, 0.0, 0.0), EffectRGB(1.0, 0.0, 0.0), EffectRGB(1.0, 1.0, 1.0)), listOf(0.2, 0.6))
        val given = EffectFrame.of(
            listOf(quant), 0.0, 0,
            inputs = mapOf(quant.id to EffectFrameInputs(personMask = true, cleanPlate = true, depthMap = true, palette = palette)),
        ).single()
        assertEquals(1 or 2 or 16, given.flags)
        assertEquals(3, given.count)
        assertEquals(EffectColorMath.lab(EffectVector(1.0, 0.0, 0.0)), given.palette[1])
        assertEquals(listOf(0.2, 0.6), given.thresholds)
        // The block as the shader's memory holds it after the header: p[40], palette[16], thresholds[4].
        val floats = given.vectors()
        assertEquals(240, floats.size)
        assertEquals(4f, floats[0], 0f)
        assertEquals(0.5f, floats[3 * 4], 0f)
        assertEquals(given.palette[1].x.toFloat(), floats[41 * 4], 0f)
        assertEquals(1f, floats[41 * 4 + 3], 0f)
        assertEquals(0f, floats[43 * 4 + 3], 0f)
        assertEquals(listOf(0.2f, 0.6f, 0f), listOf(floats[56 * 4], floats[56 * 4 + 1], floats[56 * 4 + 2]))
    }

    // :272-275, :631-634; VideoEffectModel.swift:233-236.
    @Test fun keyframesAndTheMotionAreResolvedAtTheSecond() {
        val keyed = effect(VideoEffectID.Kaleidoscope)
            .withKeyframe("splitAngle", 0.0, number(170.0)).withKeyframe("splitAngle", 1.0, number(-170.0))
            .withKeyframe("divisions", 0.0, number(2.0)).withKeyframe("divisions", 1.0, number(9.0))
            .withKeyframe("centerX", 0.0, number(0.2)).withKeyframe("centerX", 1.0, number(0.6))
        val half = EffectFrame.of(listOf(keyed), 0.5, 15).single()
        assertEquals(2.0, half.number("divisions"), 0.0)
        assertEquals(-180.0, half.number("splitAngle"), 0.0)
        assertEquals(0.4, half.number("centerX"), 1e-12)
        assertEquals(9.0, EffectFrame.of(listOf(keyed), 1.0, 30).single().number("divisions"), 0.0)
        val picked = effect(VideoEffectID.SilentTint).withKeyframe("tint", 0.0, choice("Night Blue")).withKeyframe("tint", 2.0, choice("Fire Red"))
        assertEquals(0.0, EffectFrame.of(listOf(picked), 1.99, 0).single().number("tint"), 0.0)
        assertEquals(2.0, EffectFrame.of(listOf(picked), 2.0, 0).single().number("tint"), 0.0)
        val colour = effect(VideoEffectID.ColorIsolation)
            .withKeyframe("color", 0.0, EffectValue.Color(EffectRGB(1.0, 0.0, 0.0))).withKeyframe("color", 2.0, EffectValue.Color(EffectRGB(0.0, 0.0, 1.0)))
        // A colour moves in sRGB, as the person keyed it, and is made linear afterwards.
        assertEquals(EffectColorMath.linear(EffectRGB(0.5, 0.0, 0.5)), EffectFrame.of(listOf(colour), 1.0, 0).single().vector("color").xyz)
        val correction = effect(VideoEffectID.ColorCorrection, 0.0, 4.0)
        val b = correction.with("exposure", number(1.0)).with("intensity", number(0.0)).correctionPose(0.0)
        val moving = correction.copy(motion = EffectAnimation(correction.correctionPose(0.0), 4.0).copy(b = b))
        val middle = EffectFrame.of(listOf(moving), (4.0 - PauseDetector.FRAME) / 2, 0).single()
        assertEquals(0.5, middle.number("exposure"), 1e-12)
        assertEquals(0.5, middle.intensity, 1e-12)
        // The motion ends at zero intensity: nothing is drawn there.
        assertTrue(EffectFrame.of(listOf(moving), 3.99, 0).isEmpty())
    }

    // :629-632, :660; YouRec/VideoEffects/VideoEffectMath.swift:224-247.
    @Test fun aCrashZoomMovesTheTargetOfTheCameraMoveAfterIt() {
        val first = effect(VideoEffectID.CrashZoom, 0.0, 10.0).with("trackFace", toggle(false)).with("targetX", number(0.25)).with("targetY", number(0.75))
        val second = effect(VideoEffectID.CrashZoom, 0.0, 10.0).with("trackFace", toggle(false)).with("targetX", number(0.5)).with("targetY", number(0.5))
        // Past the push and its overshoot, the first holds at 2.5 times about (0.25, 0.75).
        assertEquals(2.5, EffectFrame.crashZoomScale(first, 1.0), 0.0)
        val frame = EffectFrame.of(listOf(first, second), 1.0, 30)
        assertEquals(listOf(0.25, 0.75), listOf(frame[0].number("targetX"), frame[0].number("targetY")))
        // The second aims at where the middle of the picture has gone: 2.5 × 0.5 + 0.25 × (1 − 2.5), and the same from 0.75.
        assertEquals(0.875, frame[1].number("targetX"), 1e-12)
        assertEquals(0.125, frame[1].number("targetY"), 1e-12)
        // The curve: a fifth-power push, a small overshoot, the hold, and a smooth return when asked.
        assertEquals(1.0, EffectFrame.crashZoomScale(first, 0.0), 0.0)
        assertEquals(1 + 1.5 * 0.5.let { it * it * it * it * it }, EffectFrame.crashZoomScale(first, 0.09), 1e-12)
        assertEquals(2.5 * 1.03, EffectFrame.crashZoomScale(first, 0.18 + 0.04), 1e-9)
        val back = first.with("return", toggle(true))
        assertEquals(2.5, EffectFrame.crashZoomScale(back, 1.2), 0.0)
        assertEquals(1.75, EffectFrame.crashZoomScale(back, 0.18 + 0.08 + 1.0 + 0.09), 1e-9)
        assertEquals(1.0, EffectFrame.crashZoomScale(back, 5.0), 0.0)
        assertEquals(1.0, EffectFrame.crashZoomScale(first.copy(releaseTime = 2.0), 3.0), 0.0)
        // A Kaleidoscope is no camera move in this sense: its centre is not a target.
        assertEquals(0.5, EffectFrame.of(listOf(first, effect(VideoEffectID.Kaleidoscope)), 1.0, 30)[1].number("centerX"), 0.0)
    }

    // Tests/VideoEffectModelTests.swift:210-213, :226-236, :258-260; VideoEffectMath.swift:163-179.
    @Test fun theFrameArithmetic() {
        for (fps in listOf(12.0, 16.0, 18.0, 24.0)) {
            // Exact cadence at 30fps output.
            assertEquals(fps.toInt(), (0 until 30).map { VideoEffectTime.heldTime(it / 30.0, fps) }.toSet().size)
        }
        for (frame in 0 until 300) {
            val nominal = frame / 30.0
            val jitter = if (frame == 0) 0.0 else (if (frame % 2 == 0) 0.00004 else -0.00004) - frame * 0.00000000033
            // Camera clock jitter cannot change grain seeds.
            assertEquals(
                VideoEffectTime.frameIndex(VideoEffectTime.captureTime(nominal), 30.0),
                VideoEffectTime.frameIndex(VideoEffectTime.captureTime(nominal + jitter), 30.0),
            )
            assertEquals(frame, VideoEffectTime.frameIndex(nominal, 30.0))
        }
        assertEquals(0, VideoEffectTime.frameIndex(-1.0, 30.0))
        assertEquals(0.5, VideoEffectTime.firstFrame(0.49, 10.0), 1e-12)
        assertEquals(0.5, VideoEffectTime.firstFrame(0.5, 10.0), 1e-12)
        assertEquals(0.5, VideoEffectTime.cubic(0.5), 0.0)
        assertEquals(0.0, VideoEffectTime.cubic(-1.0), 0.0)
        assertEquals(1.0, VideoEffectTime.cubic(2.0), 0.0)
        assertEquals(4 * 0.25 * 0.25 * 0.25, VideoEffectTime.cubic(0.25), 0.0)
        val first = VideoEffectTime.dollyTarget(1.0, 0.4, 0.2, 1.0..4.0)
        val settled = VideoEffectTime.dollyTarget(first, 0.4, 0.4, 1.0..4.0)
        // Dolly controller does not undo its zoom when subject height settles.
        assertTrue(first == 2.0 && settled == 2.0)
        assertEquals(1.5, VideoEffectTime.dollyTarget(1.5, 0.4, 0.0, 1.0..4.0), 0.0)
    }
}
