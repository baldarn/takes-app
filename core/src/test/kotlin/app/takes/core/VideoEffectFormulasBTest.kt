package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.min
import kotlin.random.Random

/**
 * The reference formulas of the second batch of video effects and the passes
 * they read.
 *
 * The pinned values ([VideoEffectFormulaPinsB]) hold each formula at black,
 * white, mid grey, the primaries and three skin tones, and the weave, the
 * leaks, the zoom's move and the kernels at chosen frames and seconds. The
 * blur is held to a direct convolution. The other tests are the original's,
 * from Tests/VideoEffectGPUTests.swift and Tests/VideoEffectModelTests.swift,
 * run on the formulas.
 */
class VideoEffectFormulasBTest {
    private val math = VideoEffectShaderMath
    private val b = VideoEffectFormulasB
    private val passes = VideoEffectPasses
    private val pins = VideoEffectFormulaPinsB
    private val inputs = VideoEffectFormulaPins.inputs
    private val tolerance = 1e-12

    private fun number(value: Double) = EffectValue.Number(value)
    private fun toggle(value: Boolean) = EffectValue.Toggle(value)
    private fun choice(value: String) = EffectValue.Choice(value)

    private fun assertVector(message: String, expected: EffectVector, actual: EffectVector, within: Double = tolerance) {
        for (i in 0..2) assertEquals("$message, channel $i: $actual", expected[i], actual[i], within)
    }

    private fun held(table: String, pinned: List<VideoEffectFormulaPinsB.Pin>, formula: (EffectVector, List<Double>) -> EffectVector) {
        assertTrue(pinned.size >= 2)
        for (pin in pinned) {
            assertEquals(inputs.size, pin.outputs.size)
            for ((input, expected) in inputs.zip(pin.outputs)) {
                val out = formula(input.second, pin.parameters)
                assertVector("$table ${pin.parameters} on ${input.first}", expected, out)
                assertEquals("$table at no intensity", input.second, VideoEffectFormulas.mixed(input.second, out, 0.0))
            }
        }
    }

    private fun highlight(c: EffectVector, threshold: Double, softness: Double) = c * math.smoothstep(threshold - softness, threshold + softness, math.luma(c))

    /** The block of one effect alone at a source second, as [EffectFrame.of] makes it. */
    private fun uniforms(effect: VideoEffectSettings, time: Double = 0.5, frame: Int = 17): EffectUniforms =
        EffectFrame.of(listOf(effect), time, frame).single()

    private fun gradient(width: Int, height: Int) = EffectImage.of(width, height) { x, y -> EffectVector(x / (width - 1.0), y / (height - 1.0), .25) }

    private fun noisy(width: Int, height: Int, seed: Int) = Random(seed).let { r -> EffectImage.of(width, height) { _, _ -> EffectVector(r.nextDouble(), r.nextDouble(), r.nextDouble()) } }

    // Metal:375-376.
    @Test fun halationIsPinned() {
        held("halation", pins.halation) { c, p ->
            val h = highlight(c, .75, .1)
            b.halation(c, h, h, h, p[0], p[1], p[2], p[3].toInt())
        }
    }

    // Metal:367-370.
    @Test fun widescreenIsPinned() {
        held("widescreen", pins.widescreen) { c, p -> b.widescreen(c, highlight(c, .9, .05), false, p[0], p[1].toInt()) }
        for ((_, c) in inputs) {
            assertEquals(EffectVector.ZERO, b.widescreen(c, c, true))
            assertVector("blur fill", c * .6, b.widescreen(c, c, true, blurFill = true, background = c))
        }
    }

    // Metal:377-378, :379-381.
    @Test fun theFilmsArePinned() {
        held("16mm", pins.sixteenMMTone) { c, p -> b.sixteenMMTone(c, c * .8 + .05, p[0], p[1] > .5) }
        held("Super 8", pins.superEightTone) { c, p -> b.superEightTone(c, p[0]) }
        held("16mm grain", pins.sixteenMMGrain) { c, p ->
            val mono = p[3] > .5
            val n = VideoEffectGrain.sampled(5, 3, 64, 36, 17, b.sixteenMMGrainSizes[p[1].toInt()])
            val tone = b.sixteenMMTone(c, c * .8 + .05, p[2], mono)
            if (mono) VideoEffectGrain.apply(tone, n.w, p[0]) else VideoEffectGrain.apply(tone, n.xyz, p[0])
        }
        held("Super 8 grain", pins.superEightGrain) { c, p ->
            VideoEffectGrain.apply(b.superEightTone(c, p[0]), VideoEffectGrain.sampled(5, 3, 64, 36, 17, b.SUPER_EIGHT_GRAIN_SIZE).xyz, p[1])
        }
        for ((u, v, r, expected) in pins.gates) assertEquals("gate $u $v $r", expected, b.gate(Point(u, v), r), tolerance)
        assertEquals(1.0, b.gate(Point(.5, .5), b.SIXTEEN_MM_GATE), 0.0)
        assertEquals(0.0, b.gate(Point(0.0, 0.0), b.SUPER_EIGHT_GATE), 0.0)
    }

    // Metal:115-122: the weave is a function of the frame number alone.
    @Test fun theWeaveIsPinned() {
        val size = Size(1920.0, 1080.0)
        for ((frame, x, y) in pins.sixteenMMWeave) {
            val move = b.sixteenMMWeave(frame.toInt(), 1.0, size)
            assertEquals("16mm x at $frame", x, move.x, tolerance)
            assertEquals("16mm y at $frame", y, move.y, tolerance)
            assertEquals(Point(0.0, 0.0), b.sixteenMMWeave(frame.toInt(), 0.0, size).let { Point(it.x + 0.0, it.y + 0.0) })
        }
        for ((frame, x, y) in pins.superEightWeave) {
            val move = b.superEightWeave(frame.toInt(), 1.0, size)
            assertEquals("Super 8 x at $frame", x, move.x, tolerance)
            assertEquals("Super 8 y at $frame", y, move.y, tolerance)
        }
        // The noise is −1…1, so 16mm reaches 2 px across and down at 1080, Super 8 2 across and 3 down.
        for (frame in 0..2000) {
            val a = b.sixteenMMWeave(frame, 1.0, size)
            val s = b.superEightWeave(frame, 1.0, size)
            assertTrue(abs(a.x) <= 2 && abs(a.y) <= 2 && abs(s.x) <= 2 && abs(s.y) <= 3)
        }
        // A picture twice the size moves twice as far, the same fraction of it.
        val small = b.sixteenMMWeave(7, .5, Size(960.0, 540.0))
        val large = b.sixteenMMWeave(7, .5, size)
        assertEquals(large.x / 2, small.x, 1e-15)
        assertEquals((.25 - .5) / 1.006 + .5, b.sixteenMMPoint(Point(.25, .5), size, 0, 0.0).x, 1e-15)
    }

    // Metal:382.
    @Test fun theLightLeaksArePinned() {
        for ((t, pulse, left) in pins.leaks) {
            val leak = b.superEightLeak(t)
            assertEquals("pulse at $t", pulse, leak.pulse, tolerance)
            if (pulse > 0) assertEquals("side at $t", left > .5, leak.fromLeft)
        }
        // One leak in every six seconds, at most 1.5 s long, inside the cycle.
        for (cycle in 0 until 50) {
            val lit = (0 until 600).map { b.superEightLeak(cycle * 6 + it / 100.0).pulse > 0 }
            val first = lit.indexOf(true)
            val last = lit.lastIndexOf(true)
            assertTrue(first >= 0 && lit.subList(first, last + 1).all { it } && (last - first) / 100.0 <= 1.5)
        }
        val leak = LightLeak(1.0, true)
        assertVector("at the edge", EffectVector(1.0, .22, .035), b.superEightLeakLight(Point(0.0, .3), leak, 1.0))
        assertVector("across", EffectVector(1.0, .22, .035) * exp(-7.0), b.superEightLeakLight(Point(0.0, .3), LightLeak(1.0, false), 1.0))
    }

    // Metal:87-94, :361-366.
    @Test fun crashZoomsMoveIsPinned() {
        for (row in pins.crashZoom) {
            val (t, zoom, speed, hold) = row
            val state = b.crashZoom(t, zoom, speed, hold, row[4] > .5, row[5].takeIf { it >= 0 })
            assertEquals("zoom at $row", row[6], state.zoom, tolerance)
            assertEquals("velocity at $row", row[7], state.velocity, tolerance)
        }
        // The shader's move is the forward geometry the core gives later effects' targets (VideoEffectMath.swift:224-247).
        var effect = VideoEffectSettings(VideoEffectID.CrashZoom, 0.0, 10.0).with("trackFace", toggle(false)).with("return", toggle(true))
        for (i in 0..400) {
            val t = i / 100.0
            val u = uniforms(effect, t)
            assertEquals(EffectFrame.crashZoomScale(effect, t), b.crashZoom(u.time, u.p[0].x, u.p[1].x, u.p[2].x, u.p[3].x > .5).zoom, 1e-12)
        }
        effect = effect.copy(releaseTime = 2.1)
        for (i in 0..400) {
            val t = i / 100.0
            val u = uniforms(effect, t)
            assertEquals(EffectFrame.crashZoomScale(effect, t), b.crashZoom(u.time, u.p[0].x, u.p[1].x, u.p[2].x, false, u.p[39].x).zoom, 1e-12)
        }
        // The blur's eight taps run through the point, from −½ to +½ of the reach, along the line from the target.
        val taps = b.crashZoomTaps(Point(.7, .4), Point(.5, .5), .1)
        assertEquals(8, taps.size)
        assertEquals(.7 - .2 * .05, taps.first().x, 1e-15)
        assertEquals(.7 + .2 * .05, taps.last().x, 1e-15)
        assertEquals(.12, b.crashZoomBlur(1e6, 1.0), 0.0)
        assertEquals(0.0, b.crashZoomBlur(5.0, 0.0), 0.0)
    }

    // VideoEffectGPUTests.swift:574-579.
    @Test fun crashZoomKeepsItsPhaseAndManualReturnWhenRecordStartsMidMove() {
        val crash = VideoEffectSettings(VideoEffectID.CrashZoom, 0.0, 10.0).with("trackFace", toggle(false)).copy(releaseTime = 2.1)
        val carried = crash.copy(phaseOffset = 2.0, releaseTime = 0.1)
        val input = noisy(24, 16, 3)
        for (time in listOf(0.0, 0.05, 0.15, 0.25)) {
            val full = b.pass(uniforms(crash, 2 + time), input)
            val resumed = b.pass(uniforms(carried, time), input)
            assertEquals(full, resumed)
        }
    }

    // VideoEffectGPUTests.swift:586-597: chained moves land where the targets' geometry says.
    @Test fun chainedCrashZoomsAgreeWithTheirGeometry() {
        val crash = VideoEffectSettings(VideoEffectID.CrashZoom, 0.0, 10.0).with("trackFace", toggle(false))
        val framing = crash.with("zoom", number(1.5)).with("motionBlur", number(0.0))
        val aimed = crash.with("targetX", number(.25)).with("targetY", number(.6)).with("motionBlur", number(0.0))
        val width = 64
        val height = 36
        // A ramp: a bilinear read of it is the linear function itself, away from the edges.
        val ramp = EffectImage.of(width, height) { x, y -> EffectVector((x + .5) / width, (y + .5) / height, 0.0) }
        for (chain in listOf(listOf(aimed, framing), listOf(framing, aimed))) {
            val blocks = EffectFrame.of(chain, 1.0, 30)
            var picture = ramp
            for (u in blocks) picture = b.pass(u, picture)
            // Forward: each move scales about its target, the second's target already moved by the first.
            var scale = 1.0
            var ox = 0.0
            var oy = 0.0
            for (u in blocks) {
                val z = b.crashZoom(u.time, u.p[0].x, u.p[1].x, u.p[2].x, u.p[3].x > .5).zoom
                val tx = u.p[5].x
                val ty = u.p[6].x
                scale *= z
                ox = z * ox + tx * (1 - z)
                oy = z * oy + ty * (1 - z)
            }
            for (x in listOf(width / 3, width / 2, width * 2 / 3)) {
                val uv = ramp.uv(x, height / 2)
                val expected = Point((uv.x - ox) / scale, (uv.y - oy) / scale)
                val actual = picture[x, height / 2]
                assertEquals(expected.x, actual.x, 1e-5)
                assertEquals(expected.y, actual.y, 1e-5)
            }
        }
    }

    // Metal:364-365.
    @Test fun theBarsFollowTheRatio() {
        val wide = Size(1920.0, 1080.0)
        // 2.39:1 on 16:9: bars above and below, the picture 1920 / 2.39 high.
        val kept = (0 until 1080).count { !b.widescreenBar(Point(.5, (it + .5) / 1080), wide, 0) }
        assertEquals(1920 / 2.39, kept.toDouble(), 1.0)
        assertFalse(b.widescreenBar(Point(0.001, .5), wide, 0))
        // 1.85:1 is still wider than 16:9; 1.33:1 is narrower, with bars at the sides.
        assertTrue(b.widescreenBar(Point(.5, .01), wide, 2))
        val columns = (0 until 1920).count { !b.widescreenBar(Point((it + .5) / 1920, .5), wide, 3) }
        assertEquals(1080 * 1.33, columns.toDouble(), 1.0)
        assertFalse(b.widescreenBar(Point(.5, .01), wide, 3))
        // A portrait picture letterboxes at every ratio.
        assertTrue((0..3).all { b.widescreenBar(Point(.5, .02), Size(1080.0, 1920.0), it) })
    }

    // VideoEffectGPUTests.swift:654-663: without depth the oval bokeh changes nothing.
    @Test fun widescreenWithoutDepthIgnoresTheOvalBokeh() {
        val input = noisy(32, 18, 5)
        val plain = VideoEffectSettings(VideoEffectID.Widescreen).with("flares", number(0.0))
        val bokeh = plain.with("ovalBokeh", toggle(true))
        assertEquals(b.pass(uniforms(plain), input), b.pass(uniforms(bokeh), input))
    }

    // Each effect's whole pass on a flat field is its pinned formula with the passes' pictures holding the field's values.
    @Test fun flatFieldsGiveTheFormulas() {
        val width = 64
        val height = 36
        val x = 30
        val y = 18
        val uv = Point((x + .5) / width, (y + .5) / height)
        for ((name, c) in inputs) {
            val field = EffectImage.flat(width, height, c)
            val f = field[0, 0]
            fun at(effect: VideoEffectSettings, time: Double = .5) = b.pass(uniforms(effect, time), field)[x, y]
            val hal = VideoEffectSettings(VideoEffectID.Halation)
            val h = highlight(f, .75, .1)
            assertVector("halation $name", b.halation(f, h, h, h).clamped(), at(hal), 1e-6)
            val ws = VideoEffectSettings(VideoEffectID.Widescreen).with("bars", choice("Blur fill"))
            assertVector("widescreen $name", b.widescreen(f, highlight(f, .9, .05), false).clamped(), at(ws), 1e-6)
            assertVector("widescreen bar $name", f * .6, b.pass(uniforms(ws), field)[x, 0], 1e-6)
            val film = VideoEffectSettings(VideoEffectID.SixteenMM).with("frameEdge", toggle(true))
            val noise16 = VideoEffectGrain.sampled(x, y, width, height, 17, 1.5).xyz
            assertVector("16mm $name", VideoEffectGrain.apply(b.sixteenMMTone(f, f), noise16, .5), at(film), 1e-6)
            val mono = film.with("color", choice("B&W")).with("grainSize", choice("Coarse"))
            val noiseMono = VideoEffectGrain.sampled(x, y, width, height, 17, 2.2).w
            assertVector("16mm B&W $name", VideoEffectGrain.apply(b.sixteenMMTone(f, f, mono = true), noiseMono, .5), at(mono), 1e-6)
            val s8 = VideoEffectSettings(VideoEffectID.SuperEight).with("leaks", number(1.0))
            val noise8 = VideoEffectGrain.sampled(x, y, width, height, 17, 2.2).xyz
            val lit = VideoEffectGrain.apply(b.superEightTone(f), noise8, .7) + b.superEightLeakLight(uv, b.superEightLeak(7.3), 1.0)
            assertTrue(b.superEightLeak(7.3).pulse > .9)
            assertVector("Super 8 $name", lit.clamped(), at(s8, 7.3), 1e-6)
        }
    }

    // A zero-intensity image effect is identity, exactly (docs/VIDEO-EFFECTS.md:80-81).
    @Test fun noIntensityIsIdentity() {
        val input = noisy(40, 24, 9)
        for (kind in listOf(VideoEffectID.Halation, VideoEffectID.SixteenMM, VideoEffectID.SuperEight, VideoEffectID.Widescreen)) {
            val effect = VideoEffectSettings(kind).with("intensity", number(0.0))
            val u = EffectFrame.of(listOf(effect.with("intensity", number(1.0))), 7.3, 17).single().copy(intensity = 0.0)
            assertEquals(kind.toString(), input, b.pass(u, input))
            // [EffectFrame.of] does not draw it at all.
            assertTrue(EffectFrame.of(listOf(effect), 0.5, 17).isEmpty())
        }
        // Crash Zoom has no intensity: at its first instant it is the identity map.
        val crash = VideoEffectSettings(VideoEffectID.CrashZoom).with("motionBlur", number(0.0))
        val u = uniforms(crash, 0.0)
        assertEquals(1.0, u.intensity, 0.0)
        val out = b.pass(u, input)
        for (y in 0 until 24) for (x in 0 until 40) assertVector("crash at rest", input[x, y], out[x, y], 1e-6)
    }

    // 16mm and Super 8 read the picture where the weave moves it.
    @Test fun theFilmsReadThroughTheirWeave() {
        val width = 64
        val height = 36
        val ramp = EffectImage.of(width, height) { x, y -> EffectVector((x + .5) / width, (y + .5) / height, .5) }
        val film = VideoEffectSettings(VideoEffectID.SixteenMM).with("grain", number(0.0)).with("softness", number(0.0)).with("weave", number(1.0))
        val u = uniforms(film, frame = 7)
        val out = b.pass(u, ramp)
        val point = b.sixteenMMPoint(ramp.uv(32, 18), ramp.size, 7, 1.0)
        val expected = b.sixteenMMTone(EffectVector(point.x, point.y, .5), EffectVector(point.x, point.y, .5), 0.0)
        assertVector("16mm weave", expected, out[32, 18], 1e-6)
        // Super 8's red is read further from the centre than its green, its blue nearer.
        val at = b.superEightChannels(Point(.75, .25), Size(1080.0, 1080.0), Point(.75, .25))
        assertEquals(.75 + .25 / 1080, at.red.x, 1e-15)
        assertEquals(.25 - .25 / 1080, at.red.y, 1e-15)
        assertEquals(.75 - .25 / 1080, at.blue.x, 1e-15)
    }

    // Renderer:308-326; the kernels' pinned weights, and what they mean.
    @Test fun theKernelsArePinned() {
        for ((given, offsets, weights) in pins.kernels) {
            val kernel = passes.gaussianKernel(given[0])
            assertEquals(given[1], kernel.sigma, 0.0)
            assertEquals(given[2].toInt(), kernel.radius)
            assertEquals(given[3], kernel.center, tolerance)
            assertEquals(offsets.size, kernel.offsets.size)
            for (i in offsets.indices) {
                assertEquals(offsets[i], kernel.offsets[i], tolerance)
                assertEquals(weights[i], kernel.weights[i], tolerance)
            }
            assertEquals(1.0, kernel.center + 2 * kernel.weights.sum(), 1e-14)
            assertEquals(1.0, kernel.taps[0] + 2 * kernel.taps.drop(1).sum(), 1e-14)
            assertTrue(kernel.offsets.size <= 24)
        }
        assertEquals(48, passes.gaussianKernel(100.0).radius)
    }

    // The paired bilinear taps are the direct convolution, inside the picture and at its held edges.
    @Test fun theBlurIsTheConvolution() {
        val input = noisy(37, 23, 1)
        for (sigma in listOf(0.1, 0.45, 1.0, 1.5, 2.7, 6.0)) {
            val kernel = passes.gaussianKernel(sigma)
            for (horizontal in listOf(true, false)) {
                val paired = passes.gaussian(input, kernel, horizontal)
                val direct = passes.convolve(input, kernel, horizontal)
                for (y in 0 until input.height) for (x in 0 until input.width) {
                    assertVector("sigma $sigma at $x,$y", direct[x, y], paired[x, y], 1e-6)
                }
            }
            // The whole blur, with no reduction, is the two convolutions.
            val plan = passes.blurPlan(input.width, input.height, sigma)
            assertFalse(plan.reduced)
            val both = passes.convolve(passes.convolve(input, kernel, true), kernel, false)
            val blurred = passes.blur(input, sigma)
            for (y in 0 until input.height) for (x in 0 until input.width) assertVector("blur $sigma", both[x, y], blurred[x, y], 2e-6)
            val sideways = passes.convolve(passes.convolve(input, kernel, true), kernel, true)
            val flares = passes.blur(input, sigma, horizontalOnly = true)
            for (y in 0 until input.height) for (x in 0 until input.width) assertVector("across $sigma", sideways[x, y], flares[x, y], 2e-6)
        }
    }

    // VideoEffectGPUTests.swift:246-265: paired bilinear Gaussian taps retain the original normalised kernel.
    @Test fun theBlurOfAnImpulseIsTheKernel() {
        val width = 128
        val height = 100
        val cx = 64
        val cy = 50
        val impulse = EffectImage.of(width, height) { x, y -> if (x == cx && y == cy) EffectVector.ONE else EffectVector.ZERO }
        for (sigma in listOf(0.25, 0.45, 1.5, 8.0, 14.0)) {
            val actual = passes.blur(impulse, sigma)
            val radius = min(48, ceil(sigma * 3).toInt())
            val weights = (0..radius).map { exp(-(it * it).toDouble() / (2 * sigma * sigma)) }
            val total = weights[0] + 2 * weights.drop(1).sum()
            for (y in -radius..radius) for (x in -radius..radius) {
                val expected = weights[abs(x)] * weights[abs(y)] / (total * total)
                assertEquals("sigma $sigma at $x,$y", expected, actual[cx + x, cy + y].x, 0.001)
            }
        }
    }

    // Metal:507-520, :438-453.
    @Test fun reductionsAverageTheArea() {
        val input = noisy(32, 24, 4)
        for ((w, h) in listOf(8 to 6, 16 to 12, 11 to 7, 32 to 24)) {
            val reduced = passes.reduce(input, w, h)
            if (32 % w == 0 && 24 % h == 0) {
                val sx = 32 / w
                val sy = 24 / h
                for (y in 0 until h) for (x in 0 until w) {
                    var sum = EffectVector.ZERO
                    for (j in 0 until sy) for (i in 0 until sx) sum += input[x * sx + i, y * sy + j]
                    assertVector("box $w x $h", sum / (sx * sy).toDouble(), reduced[x, y], 1e-6)
                }
            }
            // A flat picture stays flat at any size.
            val flat = passes.reduce(EffectImage.flat(32, 24, EffectVector(.3, .6, .9)), w, h)
            for (y in 0 until h) for (x in 0 until w) assertVector("flat $w x $h", EffectVector(.3, .6, .9), flat[x, y], 1e-6)
        }
        // A single bright pixel is not lost between the quarter-size centres: its sixteenth survives.
        val dark = EffectImage.of(16, 16) { x, y -> if (x == 5 && y == 9) EffectVector(1.0, .9, .8) else EffectVector(.02) }
        val small = passes.highlights(dark, 4, 4, .75, .1)
        assertVector("practical", EffectVector(1.0, .9, .8) / 16.0, small[1, 2], 1e-7)
        assertEquals(EffectVector.ZERO, small[0, 0])
        // An odd size reads between pixels.
        val odd = passes.highlights(EffectImage.flat(10, 6, EffectVector.ONE), 3, 2, .9, .05)
        assertVector("odd", EffectVector.ONE, odd[1, 1], 1e-6)
    }

    // Renderer:292-307, :459-482: the sizes the passes are drawn at.
    @Test fun theBlursAreDrawnAtTheOriginalsSizes() {
        // Halation at 1920 x 1080 blurs its 480 x 270 highlights by 4.8, 2.88 and 19.2; the last at 350 x 196.
        val glow = passes.blurPlan(480, 270, 1920 * 0.01 / 4)
        assertEquals(listOf(480, 270, 0), listOf(glow.width, glow.height, if (glow.reduced) 1 else 0))
        assertEquals(4.8, glow.kernel.sigma, 1e-12)
        val wide = passes.blurPlan(480, 270, 1920 * 0.04 / 4)
        assertEquals(listOf(350, 196), listOf(wide.width, wide.height))
        assertTrue(wide.reduced)
        assertEquals(14.0, wide.kernel.sigma, 1e-9)
        // Widescreen's flares: a 20-pixel sigma at the quarter size, worked at 336 wide.
        val flares = passes.blurPlan(480, 270, 1920.0 / 24 / 4)
        assertEquals(336, flares.width)
        // The blur fill: 12 at 1080 to a quarter of 1920 x 1080, sigma 3 there.
        val fill = passes.blurPlan(1920, 1080, 12.0, 480, 270)
        assertEquals(listOf(480, 270), listOf(fill.width, fill.height))
        assertEquals(3.0, fill.kernel.sigma, 1e-12)
        // 16mm at 4K: 3 pixels, no reduction; Super 8 lite at 1080: 2 pixels on a half picture, sigma 1.
        assertFalse(passes.blurPlan(3840, 2160, 1.5 * 2).reduced)
        assertEquals(1.0, passes.blurPlan(1920, 1080, 2.0, 960, 540).kernel.sigma, 1e-12)
        assertEquals(2, passes.quarter(5))
        val aux = passes.halation(EffectImage.flat(64, 36, EffectVector(.9)))
        assertEquals(listOf(16, 9), listOf(aux.aux!!.width, aux.aux!!.height))
        assertNull(passes.sixteenMM(EffectImage.flat(8, 8, EffectVector.ONE), 0.0).aux)
    }

    // VideoEffectModelTests.swift:214-256: Super 8 and Undercranking cadence.
    @Test fun cadenceChoosesTheSourceFrames() {
        val super8 = VideoEffectSettings(VideoEffectID.SuperEight, 0.0, 4.0)
        val under = VideoEffectSettings(VideoEffectID.Undercranking, 0.0, 4.0).with("speed", number(1.0))
        for (fps in listOf(30.0, 60.0)) {
            val cadence = VideoEffectCadenceClock(listOf(super8))
            var previous = -1
            var held = 0.0
            for (frame in 0 until (fps * 4).toInt()) {
                val time = frame / fps
                val hold = cadence.hold(time)!!
                if (hold.index != previous) {
                    held = time
                    previous = hold.index
                }
                assertEquals("Super 8 export chooses the same first source frame as live capture at $fps", held, VideoEffectTime.firstFrame(hold.boundary, fps), 0.00001)
                assertEquals(VideoEffectTime.firstFrame(hold.boundary, fps), cadence.shownTime(time, fps), 0.0)
            }
            // 18 different frames a second.
            assertEquals(18, (0 until fps.toInt()).map { cadence.hold(it / fps)!!.index }.toSet().size)
        }
        for (fps in listOf(12.0, 16.0, 18.0)) {
            val effect = under.copy(end = 12.0).with("fps", number(fps))
            val clock = VideoEffectCadenceClock(listOf(effect))
            for (frame in 0 until 300) {
                val nominal = frame / 30.0
                val jitter = if (frame == 0) 0.0 else (if (frame % 2 == 0) 0.00004 else -0.00004) - frame * 0.00000000033
                val live = VideoEffectTime.captureTime(nominal + jitter)
                val file = VideoEffectTime.captureTime(nominal)
                assertEquals("Camera clock jitter cannot change held frames", clock.hold(file)!!.index, clock.hold(live)!!.index)
            }
        }
        val animated = super8.withKeyframe("fps", 0.0, number(24.0)).withKeyframe("fps", 1.37, number(18.0))
        val rates = (0 until 40).map { VideoEffectCadenceClock.Rate(it / 10.0, (it + 1) / 10.0, 1 + it / 80.0) }
        for (effects in listOf(listOf(animated), listOf(animated, under), listOf(under, animated))) {
            val clock = VideoEffectCadenceClock(effects, rates)
            var lastIndex = -1
            var lastTime = -1.0
            for (frame in 0 until 240) {
                val time = frame / 60.0
                val hold = clock.hold(time)!!
                assertTrue("Animated cadence and speed never rewind the chosen frame", hold.index >= lastIndex && hold.boundary >= lastTime - 0.00001 && hold.boundary <= time + 0.00001)
                lastIndex = hold.index
                lastTime = hold.boundary
            }
        }
        val combined = VideoEffectCadenceClock(listOf(animated, under))
        val single = VideoEffectCadenceClock(listOf(under))
        for (frame in 0 until 120) {
            val t = frame / 30.0
            assertEquals("The slower cadence governs a combined look", single.hold(t)!!.boundary, combined.hold(t)!!.boundary, 0.00001)
        }
        // Outside the effect, disabled, or at no intensity there is no cadence.
        val clock = VideoEffectCadenceClock(listOf(super8))
        assertNull(clock.hold(4.0))
        assertEquals(4.5, clock.shownTime(4.5, 30.0), 0.0)
        assertNull(VideoEffectCadenceClock(listOf(super8.copy(enabled = false))).hold(1.0))
        assertNull(VideoEffectCadenceClock(listOf(super8.with("intensity", number(0.0)))).hold(1.0))
        // A later stretch starts on a whole frame and holds from its own start.
        val later = VideoEffectCadenceClock(listOf(super8.copy(end = 1.0), super8.copy(start = 2.03, end = 3.0)))
        assertEquals(2.03, later.hold(2.04)!!.boundary, 1e-12)
        assertEquals(18, later.hold(2.04)!!.index)
    }
}
