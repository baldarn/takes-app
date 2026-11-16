package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The refinement of a person mask, the person hint of Chroma Key, Color
 * Isolation's subject and Quantization's face detail. The original's own
 * cases are from Tests/VideoEffectGPUTests.swift, with its pictures (320 ×
 * 180) and tolerances; the others are hand computations of the arithmetic of
 * YouRec/VideoEffects/MetalVideoEffects.swift and VideoEffects.metal.
 */
class MaskRefinementTest {
    private val m = MaskRefinement
    private val c = VideoEffectFormulasC
    private val math = VideoEffectShaderMath
    private fun v(x: Double, y: Double, z: Double) = EffectVector(x, y, z)

    private fun assertVector(message: String, expected: EffectVector, actual: EffectVector, within: Double = 1e-6) {
        for (i in 0..2) assertEquals("$message, channel $i: $actual", expected[i], actual[i], within)
    }

    // YouRec/VideoEffects/MetalVideoEffects.swift:383-389, :395
    @Test fun thePlanCapsTheWorkingSizeAndBoundsTheBlur() {
        fun same(expected: MaskRefinement.Plan, actual: MaskRefinement.Plan) {
            assertEquals(listOf(expected.width, expected.height, expected.radius), listOf(actual.width, actual.height, actual.radius))
            for ((e, a) in listOf(expected.scale to actual.scale, expected.sigma to actual.sigma, expected.black to actual.black, expected.white to actual.white)) {
                assertEquals(e, a, 1e-12)
            }
        }
        same(MaskRefinement.Plan(1024, 768, .5, 2, .475, .055, .945), m.plan(2048, 1536, .3, person = true))
        same(MaskRefinement.Plan(384, 288, .1875, 1, .475, .055, .945), m.plan(2048, 1536, .3, lite = true, person = true))
        // Other masks: half the size, and the coefficients blurred by 1 + softness.
        m.plan(2048, 1536, .3).let {
            assertEquals(512, it.width)
            assertEquals(384, it.height)
            assertEquals(1.3, it.sigma, 1e-12)
        }
        m.plan(2048, 1536, .3, lite = true).let {
            assertEquals(256, it.width)
            assertEquals("int(3.8 · 1536 / 1080 · 0.125) is 0, held at 1", 1, it.radius)
        }
        // A small picture is not enlarged; softness 1 clips at 0.02 and 0.98.
        m.plan(320, 180, 1.0, person = true).let {
            assertEquals(320, it.width)
            assertEquals(180, it.height)
            assertEquals(1, it.radius)
            assertEquals(1.0, it.sigma, 1e-12)
            assertEquals(.02, it.black, 1e-12)
            assertEquals(.98, it.white, 1e-12)
        }
        // Two pixels at least.
        assertEquals(2, m.plan(4000, 1, 0.0).height)
    }

    // YouRec/VideoEffects/VideoEffects.metal:454-459
    @Test fun theGuidedFilterCoefficientsAreTheWindowsRegression() {
        val guide = EffectMask.of(4, 1) { x, _ -> if (x < 2) 0.0 else 1.0 }
        val stats = m.guidedStats(guide, guide, 4, 1, 1)
        // At pixel 1 the window reads 0, 0, 1 in three rows: means 1/3, variance and covariance 2/9.
        val a = (2.0 / 9) / (2.0 / 9 + .001)
        assertEquals(a, stats[1, 0].x, 1e-7)
        assertEquals(1.0 / 3 - a / 3, stats[1, 0].y, 1e-7)
        // A flat guide gives no slope: the mask's mean.
        val flat = m.guidedStats(EffectMask.flat(5, 5, .4), EffectMask.of(5, 5) { x, _ -> x / 4.0 }, 5, 5, 1)
        assertEquals(0.0, flat[2, 2].x, 1e-7)
        assertEquals(.5, flat[2, 2].y, 1e-7)
        // The window is held at six pixels.
        val wide = EffectMask.of(20, 1) { x, _ -> if (x == 0) 1.0 else 0.0 }
        assertEquals(0.0, m.guidedStats(EffectMask.flat(20, 1, .2), wide, 20, 1, 9)[7, 0].y, 1e-12)
        assertTrue(m.guidedStats(EffectMask.flat(20, 1, .2), wide, 20, 1, 9)[6, 0].y > 0)
        // Applied: a · srgb(luma) + b, held inside 0…1. Metal:460-462.
        val coefficients = EffectImage.flat(2, 2, v(2.0, -.5, 0.0))
        val applied = m.guidedApply(EffectMask.flat(2, 2, .2), coefficients)
        assertEquals(math.clamp(2 * math.srgb(.2) - .5, 0.0, 1.0), applied[1, 1], 1e-6)
    }

    // Tests/VideoEffectGPUTests.swift:375-376; YouRec/VideoEffects/VideoEffects.metal:463-467
    @Test fun personCoverageDropsSkirtsAndKeepsTheHalfContour() {
        val guide = EffectImage.flat(80, 45, v(.3, .3, .3))
        for ((coverage, expected) in listOf(.01 to 0.0, .5 to .5, 1.0 to 1.0, 0.0 to 0.0)) {
            val refined = m.refinedMask(EffectMask.flat(80, 45, coverage), guide, .3, person = true)
            assertEquals("coverage $coverage", expected, refined[40, 20], 1e-6)
        }
        // Not a person: the skirt stays.
        assertEquals(.01, m.refinedMask(EffectMask.flat(80, 45, .01), guide, .3)[40, 20], 1e-6)
        assertEquals(math.smoothstep(.055, .945, .7), m.personCoverage(EffectMask.flat(1, 1, .7), .055, .945)[0, 0], 1e-6)
    }

    @Test fun refinementSnapsToTheGuideAndGrows() {
        // A blurry mask on a sharp edge becomes sharp where the guide is.
        val guide = EffectImage.of(40, 20) { x, _ -> if (x < 20) v(.02, .02, .02) else v(.8, .8, .8) }
        val blurry = EffectMask.of(40, 20) { x, _ -> math.clamp((x - 14) / 12.0, 0.0, 1.0) }
        val refined = m.refinedMask(blurry, guide, 0.0, person = true)
        assertEquals(0.0, refined[10, 10], 1e-6)
        assertEquals(1.0, refined[30, 10], 1e-6)
        // Grow is in pixels at 1080 of the guide's shorter side: 5 of them on 216 is 1 pixel.
        val dot = EffectMask.of(21, 216) { x, y -> if (x == 10 && y == 100) 1.0 else 0.0 }
        val grown = m.morph(dot, 5.0 * 216 / 1080)
        assertEquals(1.0, grown[11, 101], 1e-6)
        assertEquals(0.0, grown[12, 100], 1e-6)
    }

    // YouRec/VideoEffects/MetalVideoEffects.swift:328-333; YouRec/VideoEffects/VideoEffects.metal:481-490
    @Test fun morphologyStepsInPixelsOfTheSizeItDraws() {
        // The mask is read between its own pixels; the steps are output pixels.
        val half = EffectMask.of(80, 45) { x, _ -> if (x < 40) 1.0 else 0.0 }
        val dilated = m.morph(half, 1.8)
        assertEquals(1.0, dilated[40, 20], 1e-6)
        assertEquals(.8, dilated[41, 20], 1e-6)
        assertEquals(0.0, dilated[42, 20], 1e-6)
        val eroded = m.morph(half, -1.35)
        assertEquals(1.0, eroded[37, 20], 1e-6)
        assertEquals(.65, eroded[38, 20], 1e-6)
        assertEquals(0.0, eroded[39, 20], 1e-6)
        // To another size: a 16 × 16 mask drawn at 80 × 45.
        val small = m.morph(EffectMask.flat(16, 16, 1.0), 1.8, 80, 45)
        assertEquals(80, small.width)
        assertEquals(45, small.height)
        assertEquals(1.0, small[0, 44], 0.0)
    }

    // YouRec/VideoEffects/MetalVideoEffects.swift:513-519; YouRec/VideoEffects/VideoEffects.metal:474-479
    @Test fun thePersonHintBoundsTheKey() {
        assertEquals(0.333251953125, c.halfFloat(1.0 / 3), 0.0)
        assertEquals(0.0999755859375, c.halfFloat(.1), 0.0)
        assertEquals(1.0, c.halfFloat(1.0), 0.0)
        assertEquals("subnormal steps of 2^-24", 3 * Math.scalb(1.0, -24), c.halfFloat(3.2 * Math.scalb(1.0, -24)), 0.0)
        assertEquals(0.0, c.hinted(.3, .004, 1.0), 0.0)
        assertEquals(1.0, c.hinted(.21, .5, .996), 0.0)
        assertEquals(c.halfFloat(.19), c.hinted(.19, .5, .996), 0.0)
        assertEquals(c.halfFloat(.7), c.hinted(.7, .5, .995), 0.0)
        // A quarter of the frame, an eighth at lite: 4 % and 3 % of that height.
        val person = EffectMask.flat(16, 16, 1.0)
        c.personHint(person, 320, 180).let {
            assertEquals(80, it.dilated.width)
            assertEquals(45, it.eroded.height)
        }
        assertEquals(40, c.personHint(person, 320, 180, lite = true).dilated.width)
        val half = EffectMask.of(80, 45) { x, _ -> if (x < 40) 1.0 else 0.0 }
        val hint = c.personHint(half, 320, 180)
        assertEquals(m.morph(half, 45 * .04), hint.dilated)
        assertEquals(m.morph(half, -45 * .03), hint.eroded)
    }

    private fun key(view: String = "Matte", hint: Boolean = true) =
        VideoEffectSettings(VideoEffectID.ChromaKey).copy(algorithm = 1)
            .with("autoScreen", EffectValue.Toggle(false)).with("personHint", EffectValue.Toggle(hint))
            .with("softness", EffectValue.Number(0.0)).with("despill", EffectValue.Number(0.0)).with("wrap", EffectValue.Number(0.0))
            .with("view", EffectValue.Choice(view))

    private fun gradient() = EffectImage.of(320, 180) { x, y -> v(x / 319.0, y / 179.0, .15) }

    // Tests/VideoEffectGPUTests.swift:303-324
    @Test fun thePersonHintFillsTheInteriorAndRemovesTheOutside() {
        val original = EffectImage.flat(320, 180, v(.15, .7, .1))
        val blue = EffectImage.flat(320, 180, v(0.0, 0.0, 1.0))
        val hinted = key()
        val u = EffectFrame.of(listOf(hinted), 0.0, 0, inputs = mapOf(hinted.id to EffectFrameInputs(personMask = true))).single()
        val full = EffectMask.flat(16, 16, 1.0)
        val filled = c.chromaKeyPass(u, original, blue, person = full)
        for (y in 0 until 180 step 7) for (x in 0 until 320 step 7) assertTrue("Person holdout fills a partially keyed interior at $x $y: ${filled[x, y]}", filled[x, y].x > .99)
        val empty = EffectMask.flat(16, 16, 0.0)
        val outside = c.chromaKeyPass(u, gradient(), blue, person = empty)
        for (y in 0 until 180) for (x in 0 until 320) assertTrue("Person garbage matte removes material outside the subject", outside[x, y].x < .001)
        // Without the frame's mask the hint is not drawn: the plain key, (0.45 − 0.05) / 0.85.
        val plain = c.chromaKeyPass(EffectFrame.of(listOf(key()), 0.0, 0).single(), original, blue)
        assertEquals(.4 / .85, plain[3, 3].x, 1e-6)
    }

    // YouRec/VideoEffects/VideoEffects.metal:351-353; YouRec/VideoEffects/MetalVideoEffects.swift:483-484
    @Test fun theSubjectIsTheRefinedPersonMask() {
        // Tests/VideoEffectGPUTests.swift:382-394, :503-504: a top-half mask keeps the top subject; no vertical flip.
        val asymmetric = EffectPicture(320, 180) { _, y -> if (y < 90) v(.6, .1, .05) else v(.1, .5, .2) }
        val top = EffectMask.of(320, 180) { _, y -> if (y < 90) 1.0 else 0.0 }
        val isolation = VideoEffectSettings(VideoEffectID.ColorIsolation).copy(algorithm = 1).with("mode", EffectValue.Choice("The subject"))
        val u = EffectFrame.of(listOf(isolation), 0.0, 0, inputs = mapOf(isolation.id to EffectFrameInputs(personMask = true))).single()
        val isolated = VideoEffectFormulas.pass(u, asymmetric, person = top)
        val kept = isolated[160, 45]
        val removed = isolated[160, 135]
        assertTrue("CV top-half mask keeps the top subject", kept.x - kept.y > .4)
        assertTrue("no vertical flip", kotlin.math.abs(removed.x - removed.y) < .005)
        // The pass reads the mask refined with the effect's softness, as a person's.
        val refined = MaskRefinement.refinedMask(top, EffectMask.luma(asymmetric), .4, person = true)
        assertEquals(refined, VideoEffectFormulas.subjectMask(u, asymmetric, top))
        assertVector("edge", VideoEffectFormulas.pixel(u, asymmetric, 160, 89) { refined.sample(it) }, isolated[160, 89], 0.0)
        // Without a mask it cannot be drawn.
        var threw = false
        try {
            VideoEffectFormulas.pass(u, asymmetric)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    private val palette = EffectPalette(
        listOf(EffectRGB(.1, .1, .1), EffectRGB(.8, .2, .2), EffectRGB(.2, .7, .3), EffectRGB(.9, .9, .9)),
        listOf(.1, .3, .6),
    )

    private fun nearest(c: EffectVector, lab: List<EffectVector>): EffectVector =
        math.unlab(lab.minBy { math.lab(c.clamped()).distanceSquared(it) })

    // YouRec/VideoEffects/VideoEffects.metal:358-359; YouRec/VideoEffects/MetalVideoEffects.swift:459-461
    @Test fun faceDetailSharpensUnderThePerson() {
        val q = VideoEffectQuantization
        val effect = VideoEffectSettings(VideoEffectID.Quantization).with("smoothing", EffectValue.Number(0.0))
        val picture = EffectImage.of(32, 18) { x, y -> v(.2 + x / 60.0, .3 + (x % 3) * .15, .1 + y / 40.0) }
        val person = EffectMask.of(32, 18) { x, _ -> x / 31.0 }
        val u = EffectFrame.of(listOf(effect), 0.0, 0, inputs = mapOf(effect.id to EffectFrameInputs(personMask = true, palette = palette))).single()
        assertTrue(q.drawsFaceDetail(u))
        val blurred = q.faceDetailBlur(picture)
        assertEquals("half the size", 16, blurred.width)
        assertEquals(VideoEffectPasses.blur(picture, 8 * 18 / 1080.0, 16, 9), blurred)
        val out = q.pass(u, picture, person = person)
        for ((x, y) in listOf(0 to 0, 31 to 5, 17 to 9, 8 to 17)) {
            val c0 = picture[x, y]
            val l = math.luma(c0)
            val contrasted = c0 + .2 * (l * l * (3 - 2 * l) - l)
            val sharpened = contrasted + (contrasted - blurred.sample(picture.uv(x, y))) * (.4 * person[x, y])
            assertVector("pixel $x $y", nearest(sharpened, u.palette).clamped(), out[x, y], 1e-6)
        }
        // Without the frame's mask, or with five colours, there is no face detail.
        val plain = EffectFrame.of(listOf(effect), 0.0, 0, inputs = mapOf(effect.id to EffectFrameInputs(palette = palette))).single()
        assertEquals(q.pass(plain, picture), q.pass(plain, picture, person = person))
        assertFalse(q.drawsFaceDetail(plain))
        val five = effect.with("colors", EffectValue.Number(5.0))
        assertFalse(q.drawsFaceDetail(EffectFrame.of(listOf(five), 0.0, 0, inputs = mapOf(five.id to EffectFrameInputs(personMask = true, palette = palette))).single()))
        // A mask of zero leaves the colour alone.
        assertEquals(q.pass(plain, picture), q.pass(u, picture, person = EffectMask.flat(32, 18, 0.0)))
    }

    // YouRec/VideoEffects/VideoEffectAssets.swift:346-353, YouRec/VideoEffects/MetalVideoEffects.swift:634
    @Test fun whichEffectsReadThePerson() {
        val p = VideoEffectPersonInputs
        assertTrue(p.needsPerson(VideoEffectSettings(VideoEffectID.AutoBackground), 0.0))
        assertTrue(p.needsPerson(VideoEffectSettings(VideoEffectID.DollyZoom), 0.0))
        assertFalse(p.needsPerson(VideoEffectSettings(VideoEffectID.ColorIsolation), 0.0))
        assertTrue(p.needsPerson(VideoEffectSettings(VideoEffectID.ColorIsolation).with("mode", EffectValue.Choice("The subject")), 0.0))
        assertTrue("face detail is on by default at four colours", p.needsPerson(VideoEffectSettings(VideoEffectID.Quantization), 0.0))
        assertFalse(p.needsPerson(VideoEffectSettings(VideoEffectID.Quantization).with("colors", EffectValue.Number(5.0)), 0.0))
        assertFalse(p.needsPerson(VideoEffectSettings(VideoEffectID.Quantization).with("faceDetail", EffectValue.Toggle(false)), 0.0))
        assertFalse(p.needsPerson(VideoEffectSettings(VideoEffectID.ChromaKey), 0.0))
        assertTrue(p.needsPerson(VideoEffectSettings(VideoEffectID.ChromaKey).with("personHint", EffectValue.Toggle(true)), 0.0))
        assertFalse(p.needsPerson(VideoEffectSettings(VideoEffectID.Noir), 0.0))
        assertFalse("off", p.needsPerson(VideoEffectSettings(VideoEffectID.AutoBackground).copy(enabled = false), 0.0))
        assertFalse("outside its range", p.needsPerson(VideoEffectSettings(VideoEffectID.AutoBackground, 1.0, 2.0), 2.0))
        assertTrue(p.needsPerson(listOf(VideoEffectSettings(VideoEffectID.Noir), VideoEffectSettings(VideoEffectID.AutoBackground, 1.0, 2.0)), 1.0))
        assertFalse(p.needsPerson(emptyList(), 0.0))
        assertTrue(p.requiresPerson(VideoEffectSettings(VideoEffectID.DollyZoom), 0.0))
        assertFalse(p.requiresPerson(VideoEffectSettings(VideoEffectID.DollyZoom), 0.0, hasDepth = true))
        assertFalse(p.requiresPerson(VideoEffectSettings(VideoEffectID.Quantization), 0.0))
    }
}
