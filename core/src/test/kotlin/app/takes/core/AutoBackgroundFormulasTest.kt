package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Auto Background: the background estimate, the keyed pixel, match colour and
 * the backgrounds. The original's own cases are from
 * Tests/VideoEffectGPUTests.swift, with its pictures (320 × 180) and
 * tolerances; the others are hand computations of the arithmetic of
 * YouRec/VideoEffects/VideoEffects.metal and MetalVideoEffects.swift.
 */
class AutoBackgroundFormulasTest {
    private val a = AutoBackgroundFormulas
    private val math = VideoEffectShaderMath
    private fun v(x: Double, y: Double, z: Double) = EffectVector(x, y, z)

    private fun assertVector(message: String, expected: EffectVector, actual: EffectVector, within: Double = 1e-6) {
        for (i in 0..2) assertEquals("$message, channel $i: $actual", expected[i], actual[i], within)
    }

    private fun block(effect: VideoEffectSettings) =
        EffectFrame.of(listOf(effect), 0.0, 0, inputs = mapOf(effect.id to EffectFrameInputs(personMask = true))).single()

    // YouRec/VideoEffects/VideoEffects.metal:401-415
    @Test fun theKeyedPixelUnmixesTheEstimate() {
        val inputs = ChromaKeyInputs(v(.1, .2, .3), v(.2, .2, .2), v(1.0, 1.0, 1.0))
        // straight = (c − 0.5 · screen) / 0.5 = (0.8, 0.6, 0.4); over the background, and the wrap at the band's peak.
        assertVector("half", v(.4 + .05 + .0625, .3 + .1 + .0625, .2 + .15 + .0625), a.autoBackground(v(.5, .4, .3), .5, inputs), 1e-12)
        assertVector("no wrap", v(.45, .4, .35), a.autoBackground(v(.5, .4, .3), .5, inputs, wrap = 0.0), 1e-12)
        assertVector("clear is the background", v(.1, .2, .3), a.autoBackground(v(.9, .9, .9), 0.0, inputs), 1e-12)
        assertVector("whole is the straight colour", v(.9, .8, .7), a.autoBackground(v(.9, .8, .7), 1.0, inputs), 1e-12)
        assertVector("matte", v(.25, .25, .25), a.autoBackground(v(.9, .8, .7), .25, inputs, view = 1), 0.0)
        assertVector("original", v(.9, .8, .7), a.autoBackground(v(.9, .8, .7), .25, inputs, view = 2), 0.0)
    }

    // YouRec/VideoEffects/VideoEffects.metal:409-413, :548-552; YouRec/VideoEffects/MetalVideoEffects.swift:354-361, :536-541
    @Test fun matchColorIsTheBackgroundOverTheSubjectsMean() {
        val reference = MatchColorReference(v(.3, .15, .1), .5, v(.2, .3, .6))
        // Mean (0.6, 0.3, 0.2): ratios held at (0.5, 1, 2), half of the way there.
        assertVector("match", v(.75, 1.0, 1.5) * .4, a.matchColor(v(.4, .4, .4), reference, .5), 1e-12)
        assertVector("no subject", v(.4, .4, .4), a.matchColor(v(.4, .4, .4), reference.copy(coverage = .0005), 1.0), 0.0)
        assertVector("dark mean held at 0.01", v(.2, .2, .2) * 2.0, a.matchColor(v(.2, .2, .2), MatchColorReference(EffectVector.ZERO, 1.0, v(.5, .5, .5)), 1.0), 1e-12)
        // The subject's weight: smoothstep(0.5, 0.95, mask) at 64 × 36.
        val weighted = a.subjectWeight(EffectImage.flat(8, 8, v(.4, .2, .1)), EffectMask.flat(8, 8, .725))
        assertEquals(64, weighted.width)
        assertEquals(36, weighted.height)
        assertEquals(.5, weighted.weight[10, 10].x, 1e-7)
        assertVector("weighted", v(.2, .1, .05), weighted.color[10, 10], 1e-7)
        assertVector("a flat mean", v(.3, .2, .1), a.average(EffectImage.flat(320, 180, v(.3, .2, .1))), 1e-6)
        assertVector("1 × 1 is itself", v(.3, .2, .1), a.average(EffectImage.flat(1, 1, v(.3, .2, .1))), 1e-7)
        // Chroma Key matches after the despill.
        val inputs = ChromaKeyInputs(v(.1, .1, .1), v(0.0, 1.0, 0.0), EffectVector.ZERO)
        assertVector(
            "chroma key", a.matchColor(v(.4, .4, .4), reference, .5),
            VideoEffectFormulasC.chromaKey(v(.4, .4, .4), 1.0, inputs, despill = 0.0, match = reference, matchStrength = .5), 1e-12,
        )
    }

    // Tests/VideoEffectGPUTests.swift:351-358
    @Test fun matchColorShiftsTheSubjectTowardTheBackgroundsLight() {
        val neutral = EffectImage.flat(320, 180, v(.2, .3, .6))
        val match = VideoEffectSettings(VideoEffectID.AutoBackground).with("matchColor", EffectValue.Toggle(true)).with("matchStrength", EffectValue.Number(1.0))
        val person = EffectImage.flat(320, 180, v(.6, .3, .2))
        val out = a.pass(block(match), person, EffectMask.flat(16, 16, 1.0), neutral)
        val p = out[0, 0]
        assertTrue("Match color shifts the subject toward background illumination", p.x < .4 && p.z > .3)
        // By hand: (0.6, 0.3, 0.2) × clamp((0.2, 0.3, 0.6) / (0.6, 0.3, 0.2), 0.5, 2).
        assertVector("pinned", v(.3, .3, .4), p)
        assertVector("pinned", v(.3, .3, .4), out[200, 120])
    }

    // Tests/VideoEffectGPUTests.swift:359-378
    @Test fun estimateErrorsAreBoundedByCoverageAndSkirtsGo() {
        val neutral = EffectImage.flat(320, 180, v(.2, .3, .6))
        val window = EffectImage.of(320, 180) { x, _ -> if (x in 106..112) v(.98, .98, .98) else v(.05, .05, .05) }
        for (coverage in listOf(.01, .1, .5, 1.0)) {
            val silhouette = EffectMask.flat(80, 45, coverage)
            val replacement = VideoEffectSettings(VideoEffectID.AutoBackground).with("wrap", EffectValue.Number(0.0)).with("background", EffectValue.Choice("Solid"))
            val output = a.pass(block(replacement), window, silhouette, neutral)
            val alpha = a.pass(block(replacement.with("view", EffectValue.Choice("Matte"))), window, silhouette, neutral)
            for (y in 0 until 180 step 3) for (x in 0 until 320) {
                val al = alpha[x, y].x
                for (channel in 0..2) {
                    val base = neutral[x, y][channel] * (1 - al)
                    val out = output[x, y][channel]
                    assertTrue("Background estimate errors cannot exceed the person's coverage", out >= base - .003 && out <= base + al + .003)
                }
            }
            if (coverage == .01) assertTrue("Low-confidence mask skirts are transparent instead of adding padding", alpha[0, 0].x < .001)
            if (coverage == .5) assertEquals("Edge cleanup retains the half-coverage contour", .5, alpha[0, 0].x, .01)
        }
    }

    // YouRec/VideoEffects/MetalVideoEffects.swift:334-353; YouRec/VideoEffects/VideoEffects.metal:491-503
    @Test fun theEstimateIsTheKnownBackground() {
        val red = v(.8, .1, .1)
        val blue = v(.1, .2, .9)
        val picture = EffectImage.of(64, 36) { x, _ -> if (x < 32) red else blue }
        val mask = EffectMask.of(64, 36) { x, _ -> if (x < 32) 1.0 else 0.0 }
        val weighted = a.backgroundWeight(picture, mask)
        assertEquals(16, weighted.width)
        assertEquals(9, weighted.height)
        assertEquals(0.0, weighted.weight[7, 4].x, 0.0)
        assertEquals(1.0, weighted.weight[8, 4].x, 0.0)
        // Blurred by 4 · 36 / 1080 (held at σ 0.25): beside the edge the weight is e^-8 / (1 + 2e^-8), under 0.001.
        val estimate = a.backgroundEstimate(picture, mask)
        for (x in 0 until 16) assertVector("column $x", if (x < 8) red else blue, estimate[x, 4])
        // With the fill the pyramid carries the known background into the hole.
        val filled = a.backgroundEstimate(picture, mask, fill = true)
        for (x in 0 until 16) assertVector("filled column $x", blue, filled[x, 4], 1e-5)
        // Nothing known: the picture itself.
        assertEquals(EffectImage.of(16, 9) { x, y -> picture.sample(Point((x + .5) / 16, (y + .5) / 9)) }, a.backgroundEstimate(picture, EffectMask.flat(4, 4, 1.0)))
    }

    // YouRec/VideoEffects/VideoEffectAssets.swift:604-613
    @Test fun studioAndWindowAreTheAuthoredScenes() {
        val light = EffectColorMath.linear(a.STUDIO_LIGHT)
        val dark = EffectColorMath.linear(a.STUDIO_DARK)
        val studio = a.studioBackground(100, 100)
        // Pixel (29, 29) is at (29.5, 70.5) in Core Image's coordinates, √0.5 from the light's centre at (30, 70).
        assertVector("near the light", math.mix(light, dark, sqrt(.5) / 80), studio[29, 29], 1e-6)
        assertVector("past 80 % of the width", dark, studio[99, 99], 1e-6)
        val window = a.studioBackground(100, 100, window = true)
        val pane = EffectColorMath.linear(a.WINDOW_PANE)
        assertVector("pane", pane, window[70, 50], 1e-6)
        assertVector("between the panes", studio[75, 50], window[75, 50], 0.0)
        assertVector("second pane, top row", pane, window[85, 17], 1e-6)
        assertVector("above it", studio[85, 16], window[85, 16], 0.0)
        assertVector("below it", studio[85, 82], window[85, 82], 0.0)
        assertVector("second pane, bottom row", pane, window[85, 81], 1e-6)
    }

    // YouRec/VideoEffects/VideoEffectAssets.swift:587-627
    @Test fun theBackgroundIsChosenAsTheOriginal() {
        val input = EffectImage.of(40, 24) { x, y -> v(x / 39.0, y / 23.0, .3) }
        fun u(mode: String, kind: VideoEffectID = VideoEffectID.AutoBackground, brightness: Double = 0.0) =
            block(VideoEffectSettings(kind).with("background", EffectValue.Choice(mode)).with("backgroundBrightness", EffectValue.Number(brightness)))
        val color = EffectColorMath.linear(EffectRGB.hex("#20252D"))
        a.background(u("Solid", brightness = 1.0), input).let {
            assertEquals(1, it.width)
            assertVector("solid", color * 2.0, it[0, 0])
        }
        a.background(u("Image"), input).let {
            assertEquals("no media: the colour over the frame", 40, it.width)
            assertVector("no media", color, it[5, 5])
        }
        val still = EffectImage.flat(4, 4, v(.5, .4, .3))
        assertEquals(VideoEffectFormulasC.imageBackground(still, 40, 24), a.background(u("Video"), input, still))
        assertEquals("portrait blur", VideoEffectFormulasC.blurOriginalBackground(input, radius = 12.0), a.background(u("Blur original"), input))
        assertEquals("20 for a key", VideoEffectFormulasC.blurOriginalBackground(input), a.background(u("Blur original", VideoEffectID.ChromaKey), input))
        val studio = a.studioBackground(40, 24)
        assertVector("studio a stop up", studio[3, 3] * 2.0, a.background(u("Studio", brightness = 1.0), input)[3, 3])
        assertEquals(a.studioBackground(40, 24, window = true), a.background(u("Window"), input))
    }

    // YouRec/VideoEffects/MetalVideoEffects.swift:503-505
    @Test fun theWrapReadsTheBlurredBackground() {
        val input = EffectImage.flat(40, 24, v(.2, .2, .2))
        val background = EffectImage.of(40, 24) { x, _ -> v(x / 39.0, 0.0, 0.0) }
        val effect = VideoEffectSettings(VideoEffectID.AutoBackground)
        assertEquals(VideoEffectPasses.blur(background, 40 * .02, 10, 6), a.wrapImage(block(effect), input, background))
        assertEquals(background, a.wrapImage(block(effect.with("background", EffectValue.Choice("Solid"))), input, background))
        assertEquals(background, a.wrapImage(block(effect.with("wrap", EffectValue.Number(0.0))), input, background))
    }
}
