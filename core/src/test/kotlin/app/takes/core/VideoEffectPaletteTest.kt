package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Quantization: the palette analysis, the palette a frame is drawn with, the
 * smoothing and the shader's branch. The analysis tests are the original's
 * (Tests/VideoEffectModelTests.swift:196-209); the pinned values were
 * computed outside this code by a second transliteration of the Swift and
 * Metal text in double precision.
 */
class VideoEffectPaletteTest {
    private val analysis = EffectPaletteAnalysis
    private val q = VideoEffectQuantization
    private val inputs = VideoEffectFormulaPins.inputs
    private fun v(x: Double, y: Double, z: Double) = EffectVector(x, y, z)

    private fun assertVector(message: String, expected: EffectVector, actual: EffectVector, within: Double = 1e-9) {
        for (i in 0..2) assertEquals("$message, channel $i: $actual", expected[i], actual[i], within)
    }

    private fun assertRGB(message: String, expected: EffectRGB, actual: EffectRGB, within: Double = 1e-9) =
        assertVector(message, v(expected.red, expected.green, expected.blue), v(actual.red, actual.green, actual.blue), within)

    @Test fun randomNumbersAreSplitMix() {
        val zero = EffectRandom(0uL)
        listOf(0.8833108082136426, 0.43152799704850997, 0.026433771592597743).forEach { assertEquals(it, zero.next(), 0.0) }
        val other = EffectRandom(23uL)
        listOf(0.9095436334855602, 0.5985117483027795, 0.016697768206800023).forEach { assertEquals(it, other.next(), 0.0) }
    }

    @Test fun paletteSamplingIsDeterministicAndPinned() {
        val samples = (0 until 2304).map { i -> v((i % 64) / 63.0, (i / 64) / 35.0, 0.1) }
        val a = analysis.analyze(samples, 4, seed = 23uL)
        val b = analysis.analyze(samples, 4, seed = 23uL)
        assertEquals("Deterministic palette sampling", a, b)
        assertEquals(4, a.colors.size)
        val expected = listOf(
            EffectRGB(0.6813273731087396, 0.2646510009492946, 0.3290275691211922),
            EffectRGB(0.6074095063849542, 0.539025521670597, 0.321739058813285),
            EffectRGB(0.7720780486613831, 0.7219480543792979, 0.34533036082559126),
            EffectRGB(0.7286262093700935, 0.9254024365164248, 0.3732171250921049),
        )
        expected.zip(a.colors).forEach { (e, c) -> assertRGB("k-means from seed 23", e, c) }
        listOf(0.2706726877701019, 0.46917284334463705, 0.6699485159390826).zip(a.thresholds).forEach { (e, t) -> assertEquals(e, t, 1e-12) }
        assertTrue(a.valid)

        // From a previous palette of as many colours, over 0.1 s: k-means from its colours, then the move towards them.
        val scene = List(30) { v(.9, .2, .1) } + List(30) { v(.1, .3, .8) } + List(40) { v(.5, .5, .5) }
        val next = analysis.analyze(scene, 3, EffectPalette(a.colors.take(3), a.thresholds.take(2)), 0.1, 5uL)
        listOf(
            EffectRGB(0.3187646008917898, 0.5683772338182693, 0.897703755376847),
            EffectRGB(0.9479949037304894, 0.4681656298046875, 0.3241106666718072),
            EffectRGB(0.7353570059507774, 0.7353569765607536, 0.7353568970073973),
        ).zip(next.colors).forEach { (e, c) -> assertRGB("k-means from the previous palette", e, c) }
        assertEquals(0.32734973788160004, next.thresholds[0], 1e-12)
        assertEquals(0.5, next.thresholds[1], 1e-12)
    }

    @Test fun aNeutralOpeningFrameDoesNotTrapColourClusters() {
        val flat = analysis.analyze(List(60) { v(.25, .25, .25) }, 3)
        val lights = listOf(v(1.0, 0.0, 0.0), v(0.0, 1.0, 0.0), v(0.0, 0.0, 1.0))
        val colored = analysis.analyze(lights, 3, flat)
        for (light in lights) {
            val expected = EffectColorMath.prepared(light)
            assertTrue("A neutral opening frame must not trap color clusters", colored.colors.any { EffectColorMath.linear(it).distance(expected) < 0.005 })
        }
    }

    @Test fun blackAndWhiteStepsFollowTheScene() {
        val dark = (0 until 100).map { v(it / 1000.0, it / 1000.0, it / 1000.0) }
        assertTrue("B&W threshold follows scene median, not 0.5", analysis.analyze(dark, 2).thresholds[0] < 0.1)
        // The steps are the quantiles of the prepared luma.
        val ramp = (0 until 100).map { v(it / 99.0, it / 99.0, it / 99.0) }
        val ys = ramp.map { EffectColorMath.luminance(EffectColorMath.prepared(it)) }.sorted()
        assertEquals(listOf(ys[25], ys[50], ys[75]), analysis.analyze(ramp, 4).thresholds)
        assertEquals(EffectPalette(listOf(EffectRGB(0.0, 0.0, 0.0), EffectRGB(1.0, 1.0, 1.0)), listOf(0.5)), analysis.analyze(emptyList(), 4))
    }

    @Test fun aSceneCutResetsTheAverage() {
        val red = analysis.analyze(List(50) { v(.8, .1, .1) } + List(50) { v(.1, .1, .1) }, 2, seed = 1uL)
        val same = analysis.analyze(List(50) { v(.8, .1, .1) } + List(50) { v(.1, .1, .1) }, 2, red, 0.2)
        red.colors.zip(same.colors).forEach { (a, b) -> assertRGB("The same scene keeps its palette", a, b, 1e-6) }
        val blue = analysis.analyze(List(50) { v(.1, .1, .9) } + List(50) { v(.1, .1, .1) }, 2, red, 0.2)
        val fresh = analysis.analyze(List(50) { v(.1, .1, .9) } + List(50) { v(.1, .1, .1) }, 2, red, 1000.0)
        blue.colors.zip(fresh.colors).forEach { (a, b) -> assertRGB("A cut takes the new colours at once", b, a, 1e-12) }
    }

    @Test fun thePopPalettesSampleDarkToLight() {
        assertEquals(listOf("Pop 1", "Pop 2", "Pop 3"), analysis.pop.map { it.name })
        for (preset in analysis.pop) {
            assertEquals(16, preset.colors.size)
            val all = preset.sampled(16)
            val lightness = all.map { EffectColorMath.lab(EffectColorMath.linear(it)).x }
            assertEquals(lightness.sorted(), lightness)
            assertEquals(listOf(all.first(), all.last()), preset.sampled(2))
            assertEquals(listOf(all[0], all[5], all[10], all[15]), preset.sampled(4))
        }
        assertEquals(EffectRGB.hex("#141326"), analysis.pop[0].sampled(2)[0])
    }

    @Test fun theFramesPaletteFollowsTheChoices() {
        val analyzed = EffectPalette(listOf(EffectRGB(0.1, 0.1, 0.1), EffectRGB(.5, .2, .2), EffectRGB(.3, .6, .3), EffectRGB(.9, .9, .9)), listOf(.1, .3, .6))
        val effect = VideoEffectSettings(VideoEffectID.Quantization)
        assertEquals(analyzed, analysis.displayPalette(effect, 0.0, analyzed))
        assertNull("A palette of another size is not drawn", analysis.displayPalette(effect.with("colors", EffectValue.Number(3.0)), 0.0, analyzed))
        val pop = analysis.displayPalette(effect.with("palette", EffectValue.Choice("Pop 2")), 0.0, analyzed)!!
        assertEquals(analysis.pop[1].sampled(4), pop.colors)
        assertEquals(analyzed.thresholds, pop.thresholds)
        val locked = EffectPalette(listOf(EffectRGB(0.0, 0.0, 0.0), EffectRGB(.2, .2, .2), EffectRGB(.4, .4, .4), EffectRGB(1.0, 1.0, 1.0)), listOf(.2, .4, .8))
        val lock = effect.with("lockPalette", EffectValue.Toggle(true)).copy(lockedPalette = locked)
        assertEquals(locked, analysis.displayPalette(lock, 0.0, analyzed))
        assertEquals(locked, analysis.displayPalette(lock.with("monochrome", EffectValue.Toggle(true)), 0.0, null))
        val saved = effect.with("palette", EffectValue.Choice("Saved")).copy(palette = listOf(EffectRGB(1.0, 1.0, 1.0), EffectRGB(0.0, 0.0, 0.0)))
        // VideoEffectIntegrationTests.swift:203: a saved palette is drawn before any analysis, with midpoint steps.
        val immediately = analysis.displayPalette(saved.with("colors", EffectValue.Number(2.0)), 0.0, null)!!
        assertEquals("A saved palette of the size asked for is drawn as saved", listOf(EffectRGB(1.0, 1.0, 1.0), EffectRGB(0.0, 0.0, 0.0)), immediately.colors)
        assertEquals(listOf(0.5), immediately.thresholds)
    }

    @Test fun savingFewerColoursKeepsTheLoneSaturatedOne() {
        val greys = listOf(0.1, 0.3, 0.5, 0.7, 0.9).map { EffectRGB(it, it, it) }
        val red = EffectRGB(1.0, 0.0, 0.0)
        val chosen = analysis.sample(greys + red, 3)
        assertTrue(red in chosen)
        assertEquals(chosen.sortedBy { EffectColorMath.lab(EffectColorMath.linear(it)).x }, chosen)
        assertEquals(listOf(greys[0], greys[0], greys[1], greys[1]), analysis.sample(greys.take(2), 4))
    }

    @Test fun theTrackIsLockedAndSeekIndependent() {
        val lockKeys = VideoEffectSettings(VideoEffectID.Quantization).withKeyframe("lockPalette", 1.0, EffectValue.Toggle(true)).withKeyframe("lockPalette", 2.0, EffectValue.Toggle(false))
        val frames = (0 until 90).map { it / 30.0 }
        val times = analysis.analysisTimes(0.0, 3.0, lockKeys.parameters.first { it.id == "lockPalette" }.keyframes.map { it.time }, frames)
        assertEquals(15, times.size)
        assertEquals(0.0, times[0], 0.0)
        assertEquals(6 / 30.0, times[1], 1e-12)
        fun scene(t: Double) = (0 until 64).map { i -> v((i % 8) / 7.0 * t / 3, (i / 8) / 7.0, .2) }
        val track = analysis.track(lockKeys, times) { scene(it) }
        assertEquals(analysis.track(lockKeys, times) { scene(it) }, track)
        assertEquals("Palette locks across its interval", track.palette(1.1), track.palette(1.9))
        assertTrue("and resumes after an Unlock keyframe", track.frames.any { it.time >= 2.0 })
        assertEquals(track.frames.first().palette, track.palette(0.1))
        assertTrue(analysis.liveAnalyses(12) && !analysis.liveAnalyses(13))
    }

    @Test fun bayerMatricesAreTheOriginals() {
        val expected = listOf(
            listOf(0.03125, 0.53125, 0.15625, 0.65625),
            listOf(0.78125, 0.28125, 0.90625, 0.40625),
            listOf(0.21875, 0.71875, 0.09375, 0.59375),
            listOf(0.96875, 0.46875, 0.84375, 0.34375),
        )
        for (y in 0..3) for (x in 0..3) assertEquals(expected[y][x], q.bayer(x, y, 4), 0.0)
        assertEquals((0 until 64).map { (it + .5) / 64 }, (0 until 64).map { q.bayer(it % 8, it / 8, 8) }.sorted())
    }

    private val nearest = listOf(
        v(0.003935939750893501, 0.003935939502162235, 0.01002282390842196), // black
        v(0.8900054636880588, 0.7874122704474125, 0.6038271513244053), // white
        v(0.03310478906182967, 0.3185467699983371, 0.07323891232979693), // mid grey
        v(0.6038273418331106, 0.03310476275849873, 0.019606623820654465), // red
        v(0.8900054636880588, 0.7874122704474125, 0.6038271513244053), // green
        v(0.003935939750893501, 0.003935939502162235, 0.01002282390842196), // blue
        v(0.8900054636880588, 0.7874122704474125, 0.6038271513244053), // light skin
        v(0.6038273418331106, 0.03310476275849873, 0.019606623820654465), // medium skin
        v(0.003935939750893501, 0.003935939502162235, 0.01002282390842196), // dark skin
        v(0.8900054636880588, 0.7874122704474125, 0.6038271513244053), // sky
        v(0.8900054636880588, 0.7874122704474125, 0.6038271513244053), // near white
    )
    private val bands = listOf(
        v(0.003935939750893501, 0.003935939502162235, 0.01002282390842196), // black
        v(0.8900054636880588, 0.7874122704474125, 0.6038271513244053), // white
        v(0.6038273418331106, 0.03310476275849873, 0.019606623820654465), // mid grey
        v(0.6038273418331106, 0.03310476275849873, 0.019606623820654465), // red
        v(0.8900054636880588, 0.7874122704474125, 0.6038271513244053), // green
        v(0.6038273418331106, 0.03310476275849873, 0.019606623820654465), // blue
        v(0.03310478906182967, 0.3185467699983371, 0.07323891232979693), // light skin
        v(0.6038273418331106, 0.03310476275849873, 0.019606623820654465), // medium skin
        v(0.6038273418331106, 0.03310476275849873, 0.019606623820654465), // dark skin
        v(0.8900054636880588, 0.7874122704474125, 0.6038271513244053), // sky
        v(0.8900054636880588, 0.7874122704474125, 0.6038271513244053), // near white
    )
    private val monochrome = listOf(
        v(0.0, 0.0, 0.0), // black
        v(1.0, 1.0, 1.0), // white
        v(0.09084171118340768, 0.09084171118340768, 0.09084171118340768), // mid grey
        v(0.09084171118340768, 0.09084171118340768, 0.09084171118340768), // red
        v(1.0, 1.0, 1.0), // green
        v(0.09084171118340768, 0.09084171118340768, 0.09084171118340768), // blue
        v(0.4019777798321958, 0.4019777798321958, 0.4019777798321958), // light skin
        v(0.09084171118340768, 0.09084171118340768, 0.09084171118340768), // medium skin
        v(0.09084171118340768, 0.09084171118340768, 0.09084171118340768), // dark skin
        v(1.0, 1.0, 1.0), // sky
        v(1.0, 1.0, 1.0), // near white
    )

    private val pinnedPalette = listOf(EffectRGB(0.05, 0.05, 0.1), EffectRGB(0.8, 0.2, 0.15), EffectRGB(0.2, 0.6, 0.3), EffectRGB(0.95, 0.9, 0.8))
    private val pinnedSteps = listOf(0.05, 0.3, 0.6)

    @Test fun quantizationIsPinned() {
        val lab = pinnedPalette.map { EffectColorMath.lab(EffectColorMath.linear(it)) }
        for ((label, pins, mono, choice) in listOf(
            Case("nearest", nearest, false, 0), Case("bands", bands, false, 1), Case("monochrome", monochrome, true, 0),
        )) {
            for ((input, expected) in inputs.zip(pins)) {
                val out = q.quantize(input.second, 5, 3, lab, pinnedSteps, mono, choice)
                assertVector("$label on ${input.first}", expected, out)
                assertEquals("$label at no intensity", input.second, VideoEffectFormulas.mixed(input.second, out, 0.0))
            }
        }
        assertEquals("Fewer than two colours leave the picture", inputs[6].second, q.quantize(inputs[6].second, 0, 0, lab.take(1), emptyList()))
    }

    private data class Case(val label: String, val pins: List<EffectVector>, val mono: Boolean, val choice: Int)

    @Test fun ditheringSpreadsAGreyOverTwoColours() {
        val lab = listOf(EffectRGB(0.0, 0.0, 0.0), EffectRGB(1.0, 1.0, 1.0)).map { EffectColorMath.lab(EffectColorMath.linear(it)) }
        val grey = v(.18, .18, .18)
        val plain = (0 until 16).map { q.quantize(grey, it % 4, it / 4, lab, listOf(.5)) }.toSet()
        val dithered = (0 until 64).map { q.quantize(grey, it % 8, it / 8, lab, listOf(.5), dithering = 2) }
        assertEquals(1, plain.size)
        assertEquals(2, dithered.toSet().size)
    }

    @Test fun smoothingKeepsFlatsAndEdges() {
        val flat = EffectImage.flat(12, 8, v(.3, .2, .1))
        val smooth = q.bilateral(flat, 12, 8, 3.0, true)
        for (y in 0 until 8) for (x in 0 until 12) assertVector("A flat picture stays flat", v(.3, .2, .1), smooth[x, y], 1e-6)
        val edge = EffectImage.of(16, 4) { x, _ -> if (x < 8) v(0.0, 0.0, 0.0) else v(1.0, 1.0, 1.0) }
        val kept = q.bilateral(edge, 16, 4, 3.0, true)
        assertTrue("An edge is kept", kept[7, 1].x < 1e-6 && kept[8, 1].x > 1 - 1e-6)
        val plan = q.smoothingPlan(3840, 2160, 0.5)
        assertEquals(VideoEffectQuantization.SmoothingPlan(1920, 1080, 3.0), plan)
        q.smoothingPlan(640, 360, 0.5).let {
            assertEquals(640, it.width)
            assertEquals(360, it.height)
            assertEquals(1.0, it.sigma, 1e-12)
        }
        assertEquals(VideoEffectQuantization.SmoothingPlan(960, 540, 1.5), q.smoothingPlan(1920, 1080, 0.5, lite = true))
        assertNull(q.smoothed(flat, 0.0))
        // One tap: a direct sum of the weights.
        val ramp = EffectImage.of(9, 1) { x, _ -> v(x / 8.0, x / 8.0, x / 8.0) }
        val one = q.bilateral(ramp, 9, 1, 1.0, true)[4, 0]
        var sum = 0.0
        var weight = 0.0
        for (i in -2..2) {
            val c = ramp[4 + i, 0]
            val w = kotlin.math.exp(-(i * i) / 2.0 - VideoEffectShaderMath.srgb(c).distanceSquared(VideoEffectShaderMath.srgb(ramp[4, 0])) / .02)
            sum += c.x * w
            weight += w
        }
        assertEquals(sum / weight, one.x, 1e-6)
    }

    @Test fun thePassReadsTheFramesPalette() {
        val effect = VideoEffectSettings(VideoEffectID.Quantization).with("smoothing", EffectValue.Number(0.0))
        val picture = EffectImage.of(8, 6) { x, y -> inputs[(x + y) % inputs.size].second }
        val palette = EffectPalette(pinnedPalette, pinnedSteps)
        val u = EffectFrame.of(listOf(effect), 0.5, 3, inputs = mapOf(effect.id to EffectFrameInputs(palette = palette))).single()
        assertEquals(4, u.count)
        val out = q.pass(u, picture)
        for (y in 0 until 6) for (x in 0 until 8) {
            assertVector("pixel $x $y", q.quantize(picture[x, y], x, y, u.palette, u.thresholds).clamped(), out[x, y], 1e-6)
        }
        val none = EffectFrame.of(listOf(effect), 0.5, 3).single()
        assertEquals("Without a palette the picture is kept", picture, q.pass(none, picture))
        val half = EffectFrame.of(listOf(effect.with("intensity", EffectValue.Number(0.5))), 0.5, 3, inputs = mapOf(effect.id to EffectFrameInputs(palette = palette))).single()
        assertVector("Half intensity", VideoEffectShaderMath.mix(picture[2, 1], out[2, 1], .5), q.pass(half, picture)[2, 1], 1e-6)
    }
}
