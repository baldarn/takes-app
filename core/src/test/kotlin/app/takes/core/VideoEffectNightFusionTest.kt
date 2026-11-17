package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.random.Random

/**
 * Night fix's Medium and Precise modes: the guides, the gates, the window,
 * the flow and the fusion. The pinned sizes were computed outside this code
 * with the original's arithmetic; the flow's and the noise's checks are the
 * original's own (Tests/NightFixTests.swift:44-60, :108-118).
 */
class VideoEffectNightFusionTest {
    private val nf = VideoEffectNightFusion

    /** Soft blobs, light and dark, of 6 to 20 pixels, scattered at random over a dark ground. */
    private val blobs = Random(7).let { r -> List(160) { doubleArrayOf(r.nextDouble(-40.0, 440.0), r.nextDouble(-40.0, 300.0), r.nextDouble(6.0, 20.0), r.nextDouble(-.06, .1)) } }

    /** A dark textured scene. */
    private fun scene(x: Double, y: Double): Double =
        .06 + blobs.sumOf { (bx, by, r, a) ->
            val d2 = (x - bx) * (x - bx) + (y - by) * (y - by)
            if (d2 > 16 * r * r) 0.0 else a * exp(-d2 / (2 * r * r))
        }.coerceAtLeast(-.05)

    private fun frame(width: Int, height: Int, dx: Double = 0.0, dy: Double = 0.0, noise: Double = 0.0, seed: Int = 0): EffectImage {
        val random = Random(seed)
        return EffectImage.of(width, height) { x, y ->
            val v = scene(x - dx, y - dy)
            fun n() = if (noise > 0) random.nextDouble(-noise, noise) else 0.0
            EffectVector(v + n(), v * .9 + n(), v * 1.1 + n())
        }
    }

    @Test fun guidesAreAtMostAQuarterAndTheModesLimit() {
        // NightFix.swift:241-242, YouRec/VideoEffects/VideoEffectModel.swift:17-18
        assertEquals(256 to 144, nf.guideSize(1920, 1080, NightFixMode.Medium))
        assertEquals(480 to 270, nf.guideSize(1920, 1080, NightFixMode.Precise))
        assertEquals(256 to 144, nf.guideSize(3840, 2160, NightFixMode.Medium))
        assertEquals(512 to 288, nf.guideSize(3840, 2160, NightFixMode.Precise))
        assertEquals(270 to 480, nf.guideSize(1080, 1920, NightFixMode.Precise))
        assertEquals(96 to 64, nf.guideSize(384, 256, NightFixMode.Precise))
        assertEquals(48 to 32, nf.guideSize(192, 108, NightFixMode.Medium))
        assertEquals(1, NightFixMode.Medium.radius)
        assertEquals(4, NightFixMode.Precise.radius)
        assertEquals(0, NightFixMode.Fast.radius)
    }

    @Test fun aClipIsFusedInTheWidestModeOfItsEffects() {
        // NightFix.swift:14, :65-70
        fun night(mode: String, start: Double = 0.0, end: Double = 10.0, intensity: Double = 1.0, reduction: Double = 1.0, enabled: Boolean = true) =
            VideoEffectSettings(VideoEffectID.NightFix, start, end).with("mode", EffectValue.Choice(mode))
                .with("intensity", EffectValue.Number(intensity)).with("reduction", EffectValue.Number(reduction)).copy(enabled = enabled)
        assertEquals(null, nf.mode(null, 0.0, 5.0))
        assertEquals(null, nf.mode(listOf(night("Fast")), 0.0, 5.0))
        assertEquals(NightFixMode.Medium, nf.mode(listOf(night("Medium")), 0.0, 5.0))
        assertEquals(NightFixMode.Precise, nf.mode(listOf(night("Medium"), night("Precise", 2.0, 3.0)), 0.0, 5.0))
        assertEquals(NightFixMode.Medium, nf.mode(listOf(night("Medium"), night("Precise", 6.0, 8.0)), 0.0, 5.0))
        assertEquals(null, nf.mode(listOf(night("Precise", enabled = false)), 0.0, 5.0))
        assertEquals(null, nf.mode(listOf(night("Precise", intensity = 0.0)), 0.0, 5.0))
        assertEquals(null, nf.mode(listOf(night("Precise", reduction = 0.0)), 0.0, 5.0))
        // A keyframe with some reduction is enough.
        val keyed = night("Precise", reduction = 0.0).let { e ->
            e.copy(parameters = e.parameters.map { if (it.id == "reduction") it.copy(keyframes = listOf(EffectKeyframe(time = 1.0, value = EffectValue.Number(.5)))) else it })
        }
        assertEquals(NightFixMode.Precise, nf.mode(listOf(keyed), 0.0, 5.0))
    }

    @Test fun theWindowIsTheNeighboursWithinTheFootage() {
        // NightFix.swift:281
        assertEquals(listOf(1, 2, 3, 4), nf.window(0, 20, 4))
        assertEquals(listOf(1, 2, 3, 4, 6, 7, 8, 9), nf.window(5, 20, 4))
        assertEquals(listOf(17, 18), nf.window(19, 20, 2))
        assertEquals(listOf(4, 6), nf.window(5, 20, 1))
        assertEquals(emptyList<Int>(), nf.window(0, 1, 4))
    }

    @Test fun theGuideIsTheSquareRootOfSmoothedLumaInEightBits() {
        // VideoEffects.metal:647-655: a flat picture's guide is sqrt(luma), to eight bits.
        val flat = EffectImage.flat(64, 48, EffectVector(.2, .1, .4))
        val guide = nf.guide(flat, 32, 32)
        val luma = .2126 * .2 + .7152 * .1 + .0722 * .4
        val expected = Math.round(kotlin.math.sqrt(luma) * 255) / 255.0
        for (v in guide.plane.data) assertEquals(expected, v.toDouble(), 1e-6)
        assertEquals(expected, guide.mean, 1e-6)
        // NightFix.swift:257-260: a grid every max(1, side / 16) pixels.
        assertEquals(16 * 16, guide.signature.size)
        assertEquals(16 * 16, nf.guide(EffectImage.flat(200, 100, EffectVector.ZERO), 48, 32).signature.size)
        assertEquals(1.0, nf.guide(EffectImage.flat(8, 8, EffectVector(2.0, 2.0, 2.0)), 32, 32).mean, 0.0)
    }

    @Test fun exposureAndSceneChangesAreNotFused() {
        // NightFix.swift:284-286
        val picture = frame(128, 96)
        val a = nf.guide(picture, 32, 32)
        val moved = nf.guide(frame(128, 96, 1.0, 0.0), 32, 32)
        assertTrue("${a.mean - moved.mean}, ${a.difference(moved)}", nf.comparable(a, moved))
        val brighter = EffectImage.of(128, 96) { x, y -> picture[x, y] * 2.5 }
        val b = nf.guide(brighter, 32, 32)
        assertTrue(abs(a.mean - b.mean) > nf.MEAN_GATE)
        assertFalse(nf.comparable(a, b))
        // Another picture: blocks of light and dark where the scene was.
        val other = EffectImage.of(128, 96) { x, y -> if ((x / 16 + y / 16) % 2 == 0) EffectVector(.25, .25, .25) else EffectVector(.01, .01, .01) }
        val c = nf.guide(other, 32, 32)
        assertTrue("${a.mean - c.mean}, ${a.difference(c)}", a.difference(c) > nf.SIGNATURE_GATE)
        assertFalse(nf.comparable(a, c))
        assertEquals(22.0 / 255, nf.SIGNATURE_GATE, 0.0)
        assertEquals(0.075, nf.MEAN_GATE, 0.0)
    }

    @Test fun theFlowFindsAKnownTranslation() {
        // Tests/NightFixTests.swift:44-60: a 384 × 256 picture moved eight pixels right and four down is two and one
        // pixels of its 96 × 64 guide; the median of the flow 12 pixels in from the edges within 0.6 of them.
        for (mode in listOf(NightFixMode.Medium, NightFixMode.Precise)) {
            val (gw, gh) = nf.guideSize(384, 256, mode)
            val first = nf.guide(frame(384, 256), gw, gh)
            val second = nf.guide(frame(384, 256, 8.0, 4.0), gw, gh)
            val flow = nf.flow(first, second, mode)
            val xs = ArrayList<Double>()
            val ys = ArrayList<Double>()
            for (y in 12 until gh - 12) for (x in 12 until gw - 12) { xs += flow[x, y].x; ys += flow[x, y].y }
            xs.sort()
            ys.sort()
            val x = xs[xs.size / 2]
            val y = ys[ys.size / 2]
            assertEquals("$mode across", 2.0, x, 0.6)
            assertEquals("$mode down", 1.0, y, 0.6)
            // The port's estimator does better than the original's bound.
            assertEquals("$mode across", 2.0, x, 0.1)
            assertEquals("$mode down", 1.0, y, 0.1)
            // And the flow back agrees: forward + backward where the pixel lands is about nothing.
            val back = nf.flow(second, first, mode)
            val disagreements = ArrayList<Double>()
            for (y0 in 12 until gh - 12) for (x0 in 12 until gw - 12) {
                val f = flow[x0, y0]
                val b = back.sample(Point((x0 + .5 + f.x) / gw, (y0 + .5 + f.y) / gh))
                disagreements += kotlin.math.hypot(f.x + b.x, f.y + b.y)
            }
            disagreements.sort()
            assertTrue("$mode disagreement $disagreements", disagreements[disagreements.size / 2] < .1 && disagreements.last() < 1.0)
        }
    }

    @Test fun theFlowFollowsALargerMotion() {
        // Twenty pixels of the picture, five of the guide: the pyramid's coarse levels find it.
        val (gw, gh) = nf.guideSize(384, 256, NightFixMode.Precise)
        val flow = nf.flow(nf.guide(frame(384, 256), gw, gh), nf.guide(frame(384, 256, -20.0, 12.0), gw, gh), NightFixMode.Precise)
        val center = flow[gw / 2, gh / 2]
        assertEquals(-5.0, center.x, 0.25)
        assertEquals(3.0, center.y, 0.25)
    }

    @Test fun aFlatWindowKeepsTheMotionItWasGiven() {
        val flat = VideoEffectNightFusion.Plane.of(16, 16) { _, _ -> .5 }
        val u = VideoEffectNightFusion.Plane.of(16, 16) { _, _ -> 1.5 }
        val v = VideoEffectNightFusion.Plane.of(16, 16) { _, _ -> -.5 }
        val (nu, nv) = nf.step(flat, flat, u, v)
        for (i in nu.data.indices) { assertEquals(1.5, nu.data[i].toDouble(), 1e-6); assertEquals(-.5, nv.data[i].toDouble(), 1e-6) }
    }

    @Test fun theWeightIsTheOriginals() {
        // VideoEffects.metal:672
        assertEquals(1.0, nf.weight(0.0, EffectVector.ZERO, 0), 0.0)
        val d = EffectVector(.01, -.02, .005)
        val expected = exp(-.5 * .5 / .64) * exp(-(.01 * .01 + .02 * .02 + .005 * .005) / (3 * (6.0 / 255 * 1.5).pow(2))) * exp(-.5 * (2 / 3.0).pow(2))
        assertEquals(expected, nf.weight(.5, d, -2), 1e-15)
        assertEquals(exp(-.5 * (4 / 3.0).pow(2)), nf.weight(0.0, EffectVector.ZERO, 4), 1e-15)
    }

    @Test fun oneNeighbourAveragesInLinearLight() {
        // VideoEffects.metal:656-679: two flat frames with no motion. The fused value is the mean of the two in linear
        // light, the neighbour weighed by its difference and distance.
        val a = EffectImage.flat(40, 30, EffectVector(.1, .1, .1))
        val b = EffectImage.flat(40, 30, EffectVector(.12, .1, .1))
        val zero = VideoEffectNightFusion.Plane(32, 32)
        val still = VideoEffectNightFusion.Flow(32, 32, zero, zero)
        val fused = nf.fuse(a, listOf(VideoEffectNightFusion.Neighbour(b, still, still, 1)), 32, 32)
        val w = nf.weight(0.0, EffectVector(-.02, 0.0, 0.0), 1)
        val r = VideoEffectNightFix.encode((VideoEffectNightFix.decode(.1) + w * VideoEffectNightFix.decode(.12)) / (1 + w))
        assertEquals(r, fused[20, 15].x, 1e-6)
        assertEquals(.1, fused[20, 15].y, 1e-6)
    }

    @Test fun observationsOffThePictureOrDisagreeingAreLeftOut() {
        val a = EffectImage.flat(40, 30, EffectVector(.1, .1, .1))
        val b = EffectImage.flat(40, 30, EffectVector(.3, .3, .3))
        val zero = VideoEffectNightFusion.Plane(32, 32)
        // Out of the picture: a flow of 40 guide pixels.
        val far = VideoEffectNightFusion.Flow(32, 32, VideoEffectNightFusion.Plane.of(32, 32) { _, _ -> 40.0 }, zero)
        val still = VideoEffectNightFusion.Flow(32, 32, zero, zero)
        assertEquals(.1, nf.fuse(a, listOf(VideoEffectNightFusion.Neighbour(b, far, still, 1)), 32, 32)[20, 15].x, 1e-6)
        // The way back disagrees by more than three guide pixels.
        val wrong = VideoEffectNightFusion.Flow(32, 32, VideoEffectNightFusion.Plane.of(32, 32) { _, _ -> 3.5 }, zero)
        assertEquals(.1, nf.fuse(a, listOf(VideoEffectNightFusion.Neighbour(b, still, wrong, 1)), 32, 32)[20, 15].x, 1e-6)
        // Not a number.
        val broken = VideoEffectNightFusion.Flow(32, 32, VideoEffectNightFusion.Plane.of(32, 32) { _, _ -> Double.NaN }, zero)
        assertEquals(.1, nf.fuse(a, listOf(VideoEffectNightFusion.Neighbour(b, broken, still, 1)), 32, 32)[20, 15].x, 1e-6)
    }

    private fun variance(image: EffectImage, x0: Int, y0: Int, size: Int): Double {
        val values = (y0 until y0 + size).flatMap { y -> (x0 until x0 + size).map { x -> image[x, y].x } }
        val mean = values.average()
        return values.sumOf { (it - mean) * (it - mean) } / values.size
    }

    @Test fun mediumAndPreciseReduceTheNoiseOfAStillSurface() {
        // Tests/NightFixTests.swift:108-118: on a stationary surface of 384 × 256, in a region of 45 × 45 from (20, 20),
        // Medium leaves less than 0.9 of the noise's variance and Precise less than 0.85. A flat dark grey with
        // noise of its own in every frame.
        val frames = (0 until 9).map { i ->
            val random = Random(100 + i)
            EffectImage.of(384, 256) { _, _ -> val n = random.nextDouble(-.03, .03); EffectVector(.12 + n, .12 + n, .12 + n) }
        }
        val raw = variance(frames[4], 20, 20, 45)
        val medium = variance(nf.frame(frames, 4, NightFixMode.Medium), 20, 20, 45)
        val precise = variance(nf.frame(frames, 4, NightFixMode.Precise), 20, 20, 45)
        assertTrue("Medium $medium of $raw", medium < raw * .9)
        assertTrue("Precise $precise of $raw", precise < raw * .85)
        assertTrue("Precise $precise, Medium $medium", precise < medium)
    }

    @Test fun aMovingSceneIsAlignedNotSmeared() {
        // A textured scene panning three pixels a frame, with noise: the fused frame is nearer the clean frame than
        // the noisy one, where a fusion without motion would smear the texture.
        val clean = frame(192, 128, 3.0 * 2)
        val frames = (0 until 5).map { frame(192, 128, 3.0 * it, 0.0, .02, it) }
        val fused = nf.frame(frames, 2, NightFixMode.Precise)
        fun error(image: EffectImage): Double {
            var sum = 0.0
            for (y in 16 until 112) for (x in 16 until 176) sum += (image[x, y].x - clean[x, y].x).pow(2)
            return sum
        }
        val zero = VideoEffectNightFusion.Plane(48, 32)
        val still = VideoEffectNightFusion.Flow(48, 32, zero, zero)
        val smeared = nf.fuse(frames[2], listOf(0, 1, 3, 4).map { VideoEffectNightFusion.Neighbour(frames[it], still, still, it - 2) }, 48, 32)
        assertTrue("fused ${error(fused)} against raw ${error(frames[2])}", error(fused) < error(frames[2]) * .7)
        assertTrue("fused ${error(fused)} against smeared ${error(smeared)}", error(fused) < error(smeared))
    }

    @Test fun fastFusesNothing() {
        val frames = (0 until 3).map { frame(64, 48, it.toDouble(), 0.0, .02, it) }
        assertSame(frames[1], nf.frame(frames, 1, NightFixMode.Fast))
    }

    @Test fun theRestorationTakesTheFusedPictureByTheReduction() {
        // VideoEffects.metal:685-686: up to a reduction of 1 the fused picture replaces the raw one in the adjust pass.
        val raw = EffectImage.flat(16, 12, VideoEffectNightFix.encode(EffectVector(.02, .02, .02)))
        val fused = EffectImage.flat(16, 12, VideoEffectNightFix.encode(EffectVector(.03, .03, .03)))
        val effect = VideoEffectSettings(VideoEffectID.NightFix).with("brightness", EffectValue.Number(0.0))
        val full = VideoEffectNightFix.restore(raw, fused, effect.with("reduction", EffectValue.Number(1.0)), 0.0)
        assertEquals(VideoEffectNightFix.encode(.03), full[8, 6].x, 1e-5)
        val half = VideoEffectNightFix.restore(raw, fused, effect.with("reduction", EffectValue.Number(.5)), 0.0)
        assertEquals((VideoEffectNightFix.encode(.02) + VideoEffectNightFix.encode(.03)) / 2, half[8, 6].x, 1e-5)
        assertSame(raw, VideoEffectNightFix.restore(raw, fused, effect.with("reduction", EffectValue.Number(0.0)), 0.0))
        assertEquals(VideoEffectNightFix.fast(raw, effect, 0.0), VideoEffectNightFix.restore(raw, raw, effect, 0.0))
    }
}
