package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The reference formulas of the first batch of video effects.
 *
 * The pinned values ([VideoEffectFormulaPins]) hold each formula at black,
 * white, mid grey, the primaries and three skin tones, at its defaults and at
 * other settings. The other tests are the original's own, from
 * Tests/VideoEffectGPUTests.swift, which runs them on the GPU: here they run
 * on the formulas, with the original's pictures and tolerances.
 */
class VideoEffectFormulasTest {
    private val math = VideoEffectShaderMath
    private val formulas = VideoEffectFormulas
    private val pins = VideoEffectFormulaPins
    private val tolerance = 1e-11

    private fun number(value: Double) = EffectValue.Number(value)
    private fun toggle(value: Boolean) = EffectValue.Toggle(value)
    private fun choice(value: String) = EffectValue.Choice(value)

    private fun assertVector(message: String, expected: EffectVector, actual: EffectVector, within: Double = tolerance) {
        for (i in 0..2) assertEquals("$message, channel $i: $actual", expected[i], actual[i], within)
    }

    private fun held(table: String, pinned: List<VideoEffectFormulaPins.Pin>, formula: (EffectVector, List<Double>) -> EffectVector) {
        assertTrue(pinned.size >= 3 || table == "negative")
        for (pin in pinned) {
            assertEquals(pins.inputs.size, pin.outputs.size)
            for ((input, expected) in pins.inputs.zip(pin.outputs)) {
                val out = formula(input.second, pin.parameters)
                assertVector("$table ${pin.parameters} on ${input.first}", expected, out)
                // What the shader writes: the result held inside 0…1, and at half intensity half way from the input to that.
                assertVector("$table at full intensity", expected.clamped(), formulas.mixed(input.second, out, 1.0))
                assertVector("$table at half intensity", (input.second + expected.clamped()) / 2.0, formulas.mixed(input.second, out, 0.5))
                // A zero-intensity image effect is identity, exactly (docs/VIDEO-EFFECTS.md:80-81).
                assertEquals("$table at no intensity", input.second, formulas.mixed(input.second, out, 0.0))
                assertTrue(out.x.isFinite() && out.y.isFinite() && out.z.isFinite())
            }
        }
    }

    private val skins = listOf(EffectRGB(0.82, 0.65, 0.52), EffectRGB(0.60, 0.45, 0.35), EffectRGB(0.36, 0.25, 0.20))

    // The pinned inputs are the colours they are named for.
    @Test fun thePinnedColours() {
        val byName = pins.inputs.toMap()
        assertEquals(EffectVector(EffectColorMath.linear(0.5)), byName["mid grey"])
        assertEquals(skins.map(EffectColorMath::linear), listOf(byName["light skin"], byName["medium skin"], byName["dark skin"]))
        assertEquals(11, pins.inputs.size)
    }

    // YouRec/VideoEffects/VideoEffects.metal:13-33, :154-157.
    @Test fun theShadersHelpers() {
        for ((c, expected) in pins.hsv) assertVector("hsv $c", expected, math.hsv(c), 1e-14)
        for ((c, lab, back) in pins.lab) {
            assertVector("lab $c", lab, math.lab(c), 1e-14)
            assertVector("unlab $c", back, math.unlab(math.lab(c)), 1e-13)
            // The shader's Oklab is the processor's (VideoEffectMath.swift:63-74) with another cube root.
            assertVector("lab $c", EffectColorMath.lab(c), math.lab(c), 1e-14)
        }
        for ((given, expected) in pins.curves) {
            assertEquals("sCurve $given", expected[0], math.sCurve(given[0], given[1]), 1e-14)
            assertEquals("curve $given", expected[1], math.curve(given[0], given[1]), 1e-14)
        }
        for ((n, expected) in pins.hash32) assertEquals(expected, math.hash32(n.toUInt()).toLong())
        for ((given, expected) in pins.random01) assertEquals(expected, math.random01(given[0].toUInt(), given[1].toUInt(), given[2].toUInt()), 0.0)
        for (i in 0..1000) {
            val x = i / 1000.0
            // The shader's sRGB curve is the processor's, selected the other way round.
            assertEquals(EffectColorMath.encoded(x), math.srgb(x), 0.0)
            assertEquals(EffectColorMath.linear(x), math.lin(x), 0.0)
        }
        assertEquals(0.25, math.hueDistance(0.9, 0.15), 1e-15)
        assertEquals(0.5, math.smoothstep(0.0, 1.0, 0.5), 0.0)
        assertEquals(0.0, math.smoothstep(0.2, 0.4, 0.1), 0.0)
        assertEquals(1.0, math.smoothstep(0.2, 0.4, 0.5), 0.0)
        assertEquals(0.75, math.fract(-0.25), 0.0)
        assertEquals(0.2126 + 0.0722, math.luma(EffectVector(1.0, 0.0, 1.0)), 1e-15)
    }

    // VideoEffects.metal:258-303.
    @Test fun colorCorrectionIsPinned() = held("colorCorrection", pins.colorCorrection) { c, p ->
        formulas.colorCorrection(c, p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8], p[9], p[10], p[11])
    }

    // VideoEffects.metal:332-336.
    @Test fun silentTintIsPinned() = held("silentTint", pins.silentTint) { c, p -> formulas.silentTint(c, p[0].toInt(), p[1] >= .5, p[2]) }

    // VideoEffects.metal:337-345.
    @Test fun threeStripIsPinned() = held("threeStrip", pins.threeStrip) { c, p -> formulas.threeStrip(c, p[0], p[1], p[2]) }

    // VideoEffects.metal:346.
    @Test fun bleachBypassIsPinned() = held("bleachBypass", pins.bleachBypass) { c, p -> formulas.bleachBypass(c, p[0]) }

    // VideoEffects.metal:347-350.
    @Test fun dayForNightIsPinned() = held("dayForNight", pins.dayForNight) { c, p -> formulas.dayForNight(c, Point(p[3], p[4]), p[0], p[1], p[2] > .5) }

    // VideoEffects.metal:351-353.
    @Test fun colorIsolationIsPinned() = held("colorIsolation", pins.colorIsolation) { c, p ->
        formulas.colorIsolation(c, EffectVector(p[0], p[1], p[2]), p[3], p[4])
    }

    // VideoEffects.metal:354, :137-142.
    @Test fun noirIsPinnedWithoutAndWithItsGrain() {
        held("noir", pins.noir) { c, p -> formulas.noir(c, p[0], p[1], p[2] > .5) }
        held("noirGrain", pins.noirGrain) { c, p -> VideoEffectGrain.apply(formulas.noir(c), p[0], VideoEffectFormulas.NOIR_GRAIN) }
    }

    // VideoEffects.metal:355-356.
    @Test fun expressionismIsPinned() = held("expressionism", pins.expressionism) { c, p ->
        formulas.expressionism(c, Point(p[4], p[5]), Size(p[6], p[7]), p[0], p[1], p[2] > .5, p[3] > .5)
    }

    // VideoEffects.metal:326-330.
    @Test fun theNegativeIsPinned() = held("negative", pins.negative) { c, _ -> formulas.negative(c) }

    // VideoEffects.metal:320-325.
    @Test fun rgbOffsetReadsEachChannelFromItsOwnPlace() {
        for ((p, expected) in pins.rgbOffset) {
            val s = formulas.rgbOffset(Point(p[0], p[1]), Size(p[2], p[3]), Point(p[4], p[5]), Point(p[6], p[7]), Point(p[8], p[9]))
            assertEquals("$p", expected, listOf(s.red.x, s.red.y, s.green.x, s.green.y, s.blue.x, s.blue.y))
        }
        // An offset of 1 is 12 % of the shorter side, whichever way the picture is turned.
        val wide = formulas.rgbOffset(Point(0.5, 0.5), Size(1920.0, 1080.0), Point(1.0, 1.0), Point(0.0, 0.0), Point(-1.0, -1.0))
        assertEquals(0.5 - 0.12 * 1080 / 1920, wide.red.x, 1e-15)
        assertEquals(0.5 - 0.12, wide.red.y, 1e-15)
        assertEquals(Point(0.5, 0.5), wide.green)
        assertEquals(0.5 + 0.12, wide.blue.y, 1e-15)
    }

    // VideoEffects.metal:54-86.
    @Test fun theKaleidoscopesMapIsPinned() {
        assertEquals(96, pins.kaleidoscope.size)
        for ((p, expected) in pins.kaleidoscope) {
            val point = formulas.kaleidoscope(Point(p[0], p[1]), Size(p[2], p[3]), p[4], p[5], p[6], p[7], p[8], p[9], p[10])
            assertEquals("$p x", expected[0], point.x, 1e-12)
            assertEquals("$p y", expected[1], point.y, 1e-12)
            assertTrue(point.x in 0.0..1.0 && point.y in 0.0..1.0)
        }
        // The centre itself, and the kept sector, show themselves.
        assertEquals(Point(0.5, 0.5), formulas.kaleidoscope(Point(0.5, 0.5), Size(1920.0, 1080.0)))
        assertEquals(Point(0.8, 0.3), formulas.kaleidoscope(Point(0.8, 0.3), Size(1920.0, 1080.0)))
        // Outside 2…9 the count is held, and a count between two whole ones is the nearer.
        val uv = Point(0.2, 0.3)
        val size = Size(320.0, 180.0)
        assertEquals(formulas.kaleidoscope(uv, size, 2.0), formulas.kaleidoscope(uv, size, 0.0))
        assertEquals(formulas.kaleidoscope(uv, size, 9.0), formulas.kaleidoscope(uv, size, 12.0))
        assertEquals(formulas.kaleidoscope(uv, size, 3.0), formulas.kaleidoscope(uv, size, 2.5))
    }

    // VideoEffects.metal:32-33, :554-559: the preview's monochrome lattice is the export's texture, level for level.
    @Test fun theMonochromeLevelsAreTheLatticesWAsTheExportStoresIt() {
        for (frame in listOf(0, 1, 15, 4097, Int.MAX_VALUE)) {
            val (columns, rows) = 97 to 61
            val levels = VideoEffectGrain.monochromeLevels(columns, rows, frame)
            for (y in 0 until rows) for (x in 0 until columns) {
                val expected = ((VideoEffectGrain.lattice(x, y, frame).w * 0.5 + 0.5) * 255).roundToInt()
                assertEquals("$x, $y of frame $frame", expected, levels[y * columns + x].toInt() and 0xff)
            }
        }
    }

    // VideoEffects.metal:554-559, :137-142; YouRec/VideoEffects/MetalVideoEffects.swift:445-448.
    @Test fun theGrainsLatticeIsTheSameEverywhere() {
        for ((given, expected) in pins.lattice) {
            val value = VideoEffectGrain.lattice(given[0], given[1], given[2])
            assertEquals("$given", expected, listOf(value.x, value.y, value.z, value.w))
            assertTrue(listOf(value.x, value.y, value.z, value.w).all { it >= -1 && it < 1 })
        }
        // One point more than the picture needs at 1080 on its shorter side, per grain size.
        assertEquals(Pair(1921, 1081), VideoEffectGrain.latticeSize(1920, 1080, 1.0))
        assertEquals(Pair(1921, 1081), VideoEffectGrain.latticeSize(3840, 2160, 1.0))
        assertEquals(Pair(1081, 1921), VideoEffectGrain.latticeSize(540, 960, 1.0))
        assertEquals(Pair(874, 492), VideoEffectGrain.latticeSize(1920, 1080, 2.2))
        // At 1080 a pixel sits on a lattice point; at 2160 every other one does and the ones between are half way.
        for ((x, y) in listOf(0 to 0, 5 to 7, 1919 to 1079)) {
            assertEquals(VideoEffectGrain.lattice(x, y, 15), VideoEffectGrain.sampled(x, y, 1920, 1080, 15, 1.0))
            assertEquals(VideoEffectGrain.lattice(x, y, 15), VideoEffectGrain.sampled(2 * x, 2 * y, 3840, 2160, 15, 1.0))
        }
        val between = VideoEffectGrain.sampled(11, 14, 3840, 2160, 15, 1.0)
        assertEquals((VideoEffectGrain.lattice(5, 7, 15).w + VideoEffectGrain.lattice(6, 7, 15).w) / 2, between.w, 1e-15)
        // A different frame is a different lattice.
        assertTrue(VideoEffectGrain.lattice(5, 7, 15) != VideoEffectGrain.lattice(5, 7, 16))
        // Grain leaves black and white alone, and with no noise leaves everything alone.
        assertEquals(EffectVector.ZERO, VideoEffectGrain.apply(EffectVector.ZERO, 1.0, 1.0))
        assertEquals(EffectVector.ONE, VideoEffectGrain.apply(EffectVector.ONE, -1.0, 1.0))
        assertEquals(EffectVector(0.3, 0.2, 0.1), VideoEffectGrain.apply(EffectVector(0.3, 0.2, 0.1), 0.0, 1.0))
        // Colour grain moves each channel by its own noise.
        val coloured = VideoEffectGrain.apply(EffectVector(0.2), EffectVector(1.0, 0.0, -1.0), 0.5)
        assertTrue(coloured.x > 0.2 && coloured.y == 0.2 && coloured.z < 0.2)
    }

    // ---- The original's GPU tests, on the formulas. Tests/VideoEffectGPUTests.swift.

    private val width = 320
    private val height = 180

    /** :13, the test picture: red rising across, green rising down, a little blue. */
    private val gradient = EffectPicture(width, height) { x, y -> EffectVector(x / (width - 1.0), y / (height - 1.0), 0.15) }
    private val ramp = EffectPicture(width, 1) { x, _ -> EffectVector(x / (width - 1.0)) }

    private fun block(effect: VideoEffectSettings, time: Double = 0.0, frame: Int = 0): EffectUniforms = EffectFrame.of(listOf(effect), time, frame).single()
    private fun drawn(effect: VideoEffectSettings, picture: EffectPicture): EffectPicture =
        EffectFrame.of(listOf(effect), 0.0, 0).fold(picture) { current, u -> formulas.pass(u, current) }

    private fun pixels(picture: EffectPicture): List<Double> =
        (0 until picture.height).flatMap { y -> (0 until picture.width).flatMap { x -> picture[x, y].let { listOf(it.x, it.y, it.z) } } }

    // :20-66.
    @Test fun colorCorrectionKeepsWhatTheOriginalAsksOfIt() {
        val neutral = VideoEffectSettings(VideoEffectID.ColorCorrection)
        val source = pixels(gradient)
        val neutralPixels = pixels(drawn(neutral, gradient))
        // The formula returns its input untouched; what is left is the rounding of reading a picture at its pixel centres.
        assertTrue("Neutral correction leaves the full RGB gradient unchanged", source.zip(neutralPixels).all { (a, b) -> abs(a - b) < 1e-14 })
        for ((_, c) in pins.inputs) assertEquals(c, formulas.colorCorrection(c, pivot = 0.2))
        assertEquals("Pivot alone is exactly inert", neutralPixels, pixels(drawn(neutral.with("pivot", number(0.2)), gradient)))
        for (definition in neutral.kind.descriptor.parameters) {
            val slider = definition.kind as EffectParameterKind.Slider
            for (endpoint in listOf(slider.min, slider.max)) {
                val grade = neutral.with(definition.id, number(endpoint))
                val output = pixels(drawn(grade, gradient))
                assertTrue("Bounded grading at ${definition.id} $endpoint", output.all { it.isFinite() && it >= 0 && it <= 1 })
                if (definition.id != "pivot") {
                    assertTrue("Control has a visible effect: ${definition.id}", output.zip(neutralPixels).any { (a, b) -> abs(a - b) > 0.005 })
                }
                if (definition.id in ColorCorrectionControls.light || definition.id in ColorCorrectionControls.advanced) {
                    val gray = drawn(grade, ramp)
                    for (x in 1 until width) assertTrue("Tone ramp never reverses for ${definition.id}", gray[x, 0].x >= gray[x - 1, 0].x - 0.001)
                    for (x in 0 until width) {
                        assertTrue("Tone controls keep neutral grays neutral", abs(gray[x, 0].x - gray[x, 0].y) < 0.001 && abs(gray[x, 0].x - gray[x, 0].z) < 0.001)
                    }
                }
            }
        }
        for ((id, value) in listOf("exposure" to 1.0, "whites" to 1.0, "blacks" to -1.0)) {
            val output = drawn(neutral.with(id, number(value)), ramp)
            val unique = (0 until width).map { output[it, 0].x }.toSet()
            assertTrue("Strong $id preserves distinct gray levels instead of clipping: ${unique.size}", unique.size > 300)
        }
        val redGradient = EffectPicture(width, 1) { x, _ -> (0.7 * x / (width - 1.0)).let { EffectVector(0.7, it, it) } }
        val redResult = drawn(neutral.with("saturation", number(2.0)), redGradient)
        // Saturation preserves vivid gradients instead of flattening them at the gamut edge.
        assertTrue((0 until width).map { redResult[it, 0].y }.toSet().size > 300)
        for (saturation in listOf(0.0, 1.0, 2.0)) {
            val grade = neutral.with("saturation", number(saturation)).with("vibrance", number(1.0)).with("hue", number(75.0))
            val gray = pixels(drawn(grade, ramp))
            assertTrue("Color controls cannot tint a gray ramp", gray.zip(pixels(ramp)).all { (a, b) -> abs(a - b) < 0.001 })
        }
        val bw = drawn(neutral.with("saturation", number(0.0)), gradient)
        for (y in 0 until height) for (x in 0 until width) {
            assertTrue("Zero saturation is monochrome", abs(bw[x, y].x - bw[x, y].y) < 0.001 && abs(bw[x, y].x - bw[x, y].z) < 0.001)
        }
        // One exposure stop doubles middle-gray linear light.
        assertEquals(0.36, formulas.colorCorrection(EffectVector(0.18), exposure = 1.0).x, 0.001)
    }

    // :67-77.
    @Test fun colorCorrectionBlendsWithItsOwnInputInLinearLight() {
        var combo = VideoEffectSettings(VideoEffectID.ColorCorrection)
        val settings = listOf(
            "exposure" to 0.3, "contrast" to 1.3, "shadows" to 0.3, "highlights" to -0.4, "blacks" to 0.1, "whites" to -0.1,
            "temperature" to 0.2, "tint" to -0.1, "saturation" to 1.15, "vibrance" to 0.4, "hue" to 12.0,
        )
        for ((id, value) in settings) combo = combo.with(id, number(value))
        val small = EffectPicture(32, 18) { x, y -> gradient[x * 10, y * 10] }
        val graded = pixels(drawn(combo, small))
        assertEquals("Correction is deterministic", graded, pixels(drawn(combo, small)))
        val halfway = pixels(drawn(combo.with("intensity", number(0.5)), small))
        val source = pixels(small)
        for (i in graded.indices) assertEquals((source[i] + graded[i]) / 2, halfway[i], 1e-12)
        assertTrue(graded.zip(source).any { (a, b) -> abs(a - b) > 0.05 })
    }

    // :298-302. Amber is #D9A441.
    @Test fun silentTintIsTheDyeTimesTheToneInLinearLight() {
        val neutralContrast = VideoEffectSettings(VideoEffectID.SilentTint).with("contrast", number(1.0))
        val p = formulas.pixel(block(neutralContrast), EffectPicture(4, 4) { _, _ -> EffectVector(0.5) }, 1, 1)
        val tint = EffectColorMath.linear(EffectRGB.hex("#D9A441"))
        assertEquals(0.5 * tint.x, p.x, 0.001)
        assertEquals(0.5 * tint.y, p.y, 0.001)
        // The five dyes are these colours, to the six digits the shader writes them with.
        val dyes = listOf("#3E6FA8", "#D9A441", "#B8432F", "#6E8F4E", "#8A6A45").map(EffectRGB::hex)
        for ((dye, written) in dyes.zip(VideoEffectFormulas.silentTints)) {
            assertVector("dye", EffectVector(dye.red, dye.green, dye.blue), written, 1e-6)
        }
    }

    private fun hue(color: EffectRGB): Double {
        val r = color.red
        val g = color.green
        val b = color.blue
        val high = maxOf(r, g, b)
        val low = minOf(r, g, b)
        val span = high - low
        if (!(span > 1e-8)) return 0.0
        val value = if (high == r) (g - b) / span else if (high == g) (b - r) / span + 2 else (r - g) / span + 4
        return (value * 60 + 360).rem(360.0)
    }

    // :538-560.
    @Test fun skinKeepsItsHueAndItsShadows() {
        for (skin in skins) {
            val c = EffectColorMath.linear(skin)
            val encoded = EffectColorMath.rgb(formulas.mixed(c, formulas.threeStrip(c), 1.0))
            val difference = abs(hue(encoded) - hue(skin))
            assertTrue("Three-Strip stays within eight degrees of each reference skin hue", minOf(difference, 360 - difference) <= 8)
            for (out in listOf(formulas.silentTint(c), formulas.noir(c), formulas.expressionism(c, Point(0.5, 0.5), Size(320.0, 180.0)))) {
                assertTrue("Default looks retain shadow detail in all three skin patches", out.x + out.y + out.z > 0.0005)
            }
        }
    }

    // :561-564.
    @Test fun aTinyIntensityIsNearlyTheSource() {
        val tiny = VideoEffectSettings(VideoEffectID.SilentTint).with("intensity", number(0.001))
        val small = EffectPicture(32, 18) { x, y -> gradient[x * 10, y * 10] }
        val near = pixels(drawn(tiny, small))
        assertTrue(near.zip(pixels(small)).all { (a, b) -> abs(a - b) < 0.002 })
        // And at zero nothing is drawn at all.
        assertTrue(EffectFrame.of(listOf(tiny.with("intensity", number(0.0))), 0.0, 0).isEmpty())
    }

    /** :399-405. A dark picture with a brighter rectangle. */
    private val chart = EffectPicture(width, height) { x, y ->
        if (y in 70 until 92 && x in 120 until 145) EffectVector(0.8, 0.65, 0.45) else EffectVector(0.1 + 0.2 * x / width, 0.12, 0.2 * y / height)
    }

    // :406-421.
    @Test fun rgbOffsetDisplacesEachChannelIndependently() {
        var offset = VideoEffectSettings(VideoEffectID.RgbOffset)
        // Centered RGB pads preserve the source: nothing is drawn.
        assertEquals(pixels(chart), pixels(drawn(offset, chart)))
        val shifts = listOf(12 to 0, -4 to 10, 2 to -9)
        for ((channel, displacement) in RGBChannel.entries.zip(shifts)) {
            offset = offset.with(channel.x, number(displacement.first / (0.12 * height)))
            offset = offset.with(channel.y, number(displacement.second / (0.12 * height)))
        }
        val shifted = drawn(offset, chart)
        // Positive Y in the shader moves content down.
        for (c in 0..2) for (y in 0 until height) for (x in 0 until width) {
            val sourceX = maxOf(0, minOf(width - 1, x - shifts[c].first))
            val sourceY = maxOf(0, minOf(height - 1, y - shifts[c].second))
            assertEquals("Independent channel displacement $c at $x, $y", chart[sourceX, sourceY][c], shifted[x, y][c], 0.002)
        }
    }

    // :422-433.
    @Test fun theStrobeShowsTheNegativeOnItsNegativeFrames() {
        val strobe = VideoEffectSettings(VideoEffectID.NegativeStrobe, start = 1.0, end = 2.0)
            .with("frequency", number(15.0)).with("timedBurst", toggle(true)).with("burstLength", number(0.2))
        val reference = chart[130, 80]
        for (f in listOf(0, 1, 2, 5, 6, 3, -1, 30)) {
            val rendered = EffectFrame.of(listOf(strobe), 1 + f / 30.0, 30 + f).fold(chart) { current, u -> formulas.pass(u, current) }[130, 80]
            val negative = f in listOf(1, 3, 5)
            for (c in 0..2) {
                val v = reference[c]
                val code = 1.055 * v.pow(1 / 2.4) - 0.055
                val inverse = 1 - code
                val expected = if (inverse <= 0.04045) inverse / 12.92 else ((inverse + 0.055) / 1.055).pow(2.4)
                assertEquals("Positive/negative phase at frame $f", if (negative) expected else v, rendered[c], 0.002)
            }
        }
        // Mid-gray stays mid-gray: the negative of sRGB one half is itself.
        assertVector("grey", EffectVector(EffectColorMath.linear(0.5)), formulas.negative(EffectVector(EffectColorMath.linear(0.5))), 1e-15)
        assertVector("black", EffectVector.ONE, formulas.negative(EffectVector.ZERO), 1e-15)
        assertVector("above white", EffectVector.ZERO, formulas.negative(EffectVector(1.0, 2.0, 1.5)), 1e-15)
    }

    // :434-477. The expected colour is read from the source wedge directly, independent of the inverse polar mapping.
    @Test fun theKaleidoscopeFollowsTheOffCentreRotatedSourceWedge() {
        for ((w, h) in listOf(width to height, height to width)) {
            val picture = EffectPicture(w, h) { x, y -> EffectVector(x / (w - 1.0), y / (h - 1.0), 0.15) }
            val centerX = w * 0.56
            val centerY = h * 0.42
            // The original's picture has its rows from the bottom; this one from the top, as the shader's.
            fun at(image: EffectPicture, x: Int, y: Int) = image[x, y]
            fun probe(image: EffectPicture, theta: Double) = at(image, (centerX + 64 * cos(theta)).toInt(), (centerY + 64 * sin(theta)).toInt())
            fun reflect(x: Double, length: Int): Int {
                val period = 2.0 * length
                val phase = x - period * floor(x / period)
                return minOf(length - 1, minOf(phase, period - phase).toInt())
            }
            for (count in 2..9) {
                var effect = VideoEffectSettings(VideoEffectID.Kaleidoscope)
                    .with("divisions", number(count.toDouble())).with("centerX", number(0.56)).with("centerY", number(0.42)).with("splitAngle", number(31.0))
                val rendered = drawn(effect, picture)
                effect = effect.with("sourcePanX", number(0.81)).with("sourcePanY", number(-0.77))
                val shifted = drawn(effect, picture)
                effect = effect.with("sourceAngle", number(73.0))
                val turned = drawn(effect, picture)
                val span = 2 * PI / count
                val base = -59.0 * PI / 180
                for (fraction in listOf(0.18, 0.7)) {
                    val theta = base + fraction * span
                    val expected = probe(picture, theta)
                    val movedSource = at(picture, reflect(centerX + 64 * cos(theta) - 0.81 * w, w), reflect(centerY + 64 * sin(theta) + 0.77 * h, h))
                    val turnedTheta = theta - 73 * PI / 180
                    val turnedSource = at(picture, reflect(centerX + 64 * cos(turnedTheta) - 0.81 * w, w), reflect(centerY + 64 * sin(turnedTheta) + 0.77 * h, h))
                    for (sector in 0 until count) {
                        val local = if (count % 2 == 0 && sector % 2 != 0) 1 - fraction else fraction
                        val where = base + (sector + local) * span
                        val message = "sector $sector/$count, $w×$h"
                        val actual = probe(rendered, where)
                        assertTrue("Kaleidoscope $message, follows the off-center rotated source wedge", abs(actual.x - expected.x) < 0.012 && abs(actual.y - expected.y) < 0.012)
                        val moved = probe(shifted, where)
                        assertTrue("Pan samples the translated picture including reflected borders in $message", abs(moved.x - movedSource.x) < 0.012 && abs(moved.y - movedSource.y) < 0.012)
                        val rotated = probe(turned, where)
                        assertTrue("Source rotation samples the turned and panned picture inside the same $message", abs(rotated.x - turnedSource.x) < 0.012 && abs(rotated.y - turnedSource.y) < 0.012)
                    }
                }
            }
        }
    }

    // :478-500.
    @Test fun theKaleidoscopeMirrorsAndKeepsTheSourcesLight() {
        // A mirrored sample keeps its radiometry, also above white, at every strength.
        for (color in listOf(EffectVector(0.18, 0.08, 0.03), EffectVector(3.2, 1.6, 0.6))) {
            val patch = EffectPicture(16, 9) { _, _ -> color }
            for (intensity in listOf(0.4, 1.0)) {
                val mirror = VideoEffectSettings(VideoEffectID.Kaleidoscope).with("intensity", number(intensity))
                // The Kaleidoscope is a camera effect: it has no intensity control, and the block's is 1.
                assertEquals(1.0, block(mirror).intensity, 0.0)
                assertVector("patch", color, drawn(mirror, patch)[8, 4], 1e-12)
            }
            val half = block(VideoEffectSettings(VideoEffectID.Kaleidoscope)).copy(intensity = 0.4)
            assertVector("patch at 0.4", color, formulas.pixel(half, patch, 8, 4), 1e-12)
        }
        // Two divisions make one true vertical mirror: the right half supplies the picture.
        val vertical = drawn(VideoEffectSettings(VideoEffectID.Kaleidoscope), gradient)
        for (y in listOf(8, height / 2, height - 9)) {
            val preserved = gradient[width - 18, y]
            for (x in listOf(17, width - 18)) assertVector("mirror at $x, $y", preserved, vertical[x, y], 0.002)
        }
        // Below full strength the shader mixes the mirrored picture with the one under it.
        val mixedBlock = block(VideoEffectSettings(VideoEffectID.Kaleidoscope)).copy(intensity = 0.25)
        assertVector("mixed", gradient[17, 8] + (gradient[width - 18, 8] - gradient[17, 8]) * 0.25, formulas.pixel(mixedBlock, gradient, 17, 8), 1e-12)
    }

    // VideoEffects.metal:304-418: the pass as a whole, on the blocks of a frame.
    @Test fun aPassDrawsWhatItsFormulaSays() {
        val small = EffectPicture(16, 9) { x, y -> gradient[x * 20, y * 20] }
        fun check(effect: VideoEffectSettings, formula: (EffectVector, Point) -> EffectVector) {
            val u = block(effect, frame = 15)
            val out = formulas.pass(u, small)
            for (y in 0 until 9) for (x in 0 until 16) {
                val c = small[x, y]
                assertVector("${effect.kind} at $x, $y", formulas.mixed(c, formula(c, small.uv(x, y)), u.intensity), out[x, y], 1e-15)
            }
        }
        val size = small.size
        check(VideoEffectSettings(VideoEffectID.ThreeStrip).with("intensity", number(0.6))) { c, _ -> formulas.threeStrip(c) }
        check(VideoEffectSettings(VideoEffectID.BleachBypass).with("strength", number(0.3))) { c, _ -> formulas.bleachBypass(c, 0.3) }
        check(VideoEffectSettings(VideoEffectID.DayForNight).with("moonlight", toggle(false))) { c, uv -> formulas.dayForNight(c, uv, moonlight = false) }
        check(VideoEffectSettings(VideoEffectID.SilentTint).with("tint", choice("Sepia")).with("mode", choice("Tone"))) { c, _ -> formulas.silentTint(c, 4, true) }
        check(VideoEffectSettings(VideoEffectID.Expressionism).with("iris", choice("Circle")).with("tint", toggle(true))) { c, uv ->
            formulas.expressionism(c, uv, size, circle = true, tint = true)
        }
        check(VideoEffectSettings(VideoEffectID.Noir).with("grain", toggle(false)).with("shadowColor", toggle(true))) { c, _ -> formulas.noir(c, shadowColor = true) }
        check(VideoEffectSettings(VideoEffectID.ColorIsolation).copy(algorithm = 1)) { c, _ -> formulas.colorIsolation(c, EffectColorMath.linear(EffectRGB.hex("#C0392B"))) }
        // Noir with its grain: the lattice of the block's frame under each pixel.
        val grainy = block(VideoEffectSettings(VideoEffectID.Noir), frame = 15)
        val out = formulas.pass(grainy, small)
        for (y in 0 until 9) for (x in 0 until 16) {
            val noise = VideoEffectGrain.sampled(x, y, 16, 9, 15, 1.0).w
            assertVector("grain at $x, $y", VideoEffectGrain.apply(formulas.noir(small[x, y]), noise, 0.25).clamped(), out[x, y], 1e-15)
        }
        assertTrue(pixels(out) != pixels(formulas.pass(grainy.copy(frame = 16), small)))
        // A Color Isolation made with the temporal matte, or keeping the subject, reads a matte it must be given.
        val temporal = block(VideoEffectSettings(VideoEffectID.ColorIsolation))
        assertThrows(IllegalArgumentException::class.java) { formulas.pixel(temporal, small, 0, 0) }
        assertVector("matte", formulas.colorIsolation(small[3, 3], 0.25), formulas.pixel(temporal, small, 3, 3) { 0.25 }, 1e-15)
        val subject = block(VideoEffectSettings(VideoEffectID.ColorIsolation).copy(algorithm = 1).with("mode", choice("The subject")))
        assertVector("subject", small[3, 3], formulas.pixel(subject, small, 3, 3) { 1.0 }, 1e-15)
        // An effect of a later batch has no formula here.
        assertThrows(IllegalArgumentException::class.java) { formulas.pixel(block(VideoEffectSettings(VideoEffectID.Halation)), small, 0, 0) }
        assertEquals(11, VideoEffectFormulas.referenced.size)
        // A picture is read between its pixels and held at its edges.
        assertEquals(small[0, 0], small.sample(Point(-1.0, -1.0)))
        assertEquals(small[15, 8], small.sample(Point(2.0, 2.0)))
        assertVector("between", (small[3, 4] + small[4, 4]) / 2.0, small.sample(Point(4.0 / 16, 4.5 / 9)), 1e-15)
    }

    // docs/VIDEO-EFFECTS.md:77-81: a chain is each effect's pass on the picture the one before made.
    @Test fun aChainIsDrawnInThePipelinesOrder() {
        val small = EffectPicture(16, 9) { x, y -> gradient[x * 20, y * 20] }
        val noir = VideoEffectSettings(VideoEffectID.Noir).with("grain", toggle(false))
        val correction = VideoEffectSettings(VideoEffectID.ColorCorrection).with("exposure", number(1.0))
        val mirror = VideoEffectSettings(VideoEffectID.Kaleidoscope)
        val chain = EffectFrame.of(listOf(noir, correction, mirror), 0.0, 0)
        assertEquals(listOf(VideoEffectID.Kaleidoscope, VideoEffectID.ColorCorrection, VideoEffectID.Noir), chain.map { it.effect })
        val out = chain.fold(small) { current, u -> formulas.pass(u, current) }
        val mirrored = formulas.pass(chain[0], small)
        for (y in 0 until 9) for (x in 0 until 16) {
            val expected = formulas.noir(formulas.colorCorrection(mirrored[x, y], exposure = 1.0).clamped()).clamped()
            assertVector("chain at $x, $y", expected, out[x, y], 1e-15)
        }
    }
}
