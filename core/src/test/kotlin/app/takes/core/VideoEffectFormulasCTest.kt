package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chroma Key's spatial keyer and the time effects' pixels. The pinned values
 * were computed outside this code by a second transliteration of the
 * shader's branches in double precision; the keyed colours are each of
 * [VideoEffectFormulaPins.inputs] 60 % over 40 % of the screen, so the key
 * is partial and the despill acts.
 */
class VideoEffectFormulasCTest {
    private val c = VideoEffectFormulasC
    private val inputs = VideoEffectFormulaPins.inputs
    private fun v(x: Double, y: Double, z: Double) = EffectVector(x, y, z)
    private class K(val alpha: Double, val out: EffectVector)

    private fun assertVector(message: String, expected: EffectVector, actual: EffectVector, within: Double = 1e-12) {
        for (i in 0..2) assertEquals("$message, channel $i: $actual", expected[i], actual[i], within)
    }

    private val greenKey = listOf(
        K(0.6470588235294118, v(0.13251339138902724, 0.11204765842531567, 0.07158017409812362)), // black
        K(0.6470588235294118, v(0.7291913855633848, 0.7275503522783134, 0.6704728388229094)), // white
        K(0.6470588235294118, v(0.26016752757263073, 0.24406823387475668, 0.19974800901888448)), // mid grey
        K(1.0, v(0.6040091302299476, 0.24153093554213512, 0.007842659406495289)), // red
        K(0.0, v(0.3, 0.2, 0.1)), // green
        K(1.0, v(0.004009130229947615, 0.24153093554213512, 0.6078426594064953)), // blue
        K(0.9513406907872968, v(0.42494908683938765, 0.3659575841781244, 0.16492174116815186)), // light skin
        K(0.8180408989339344, v(0.27462542699389736, 0.23379427467175376, 0.10903378040769393)), // medium skin
        K(0.706593844294278, v(0.1796549255966479, 0.15190941925995124, 0.08436102929571575)), // dark skin
        K(1.0, v(0.2978361797479079, 0.5503380377916536, 0.6157850092458154)), // sky
        K(0.6470588235294118, v(0.7111913855633848, 0.7095503522783134, 0.6524728388229094)), // near white
    )
    private val blueKey = listOf(
        K(0.4285714285714286, v(0.23020408163265305, 0.1877551020408163, 0.14530612244897959)), // black
        K(0.4285714285714286, v(0.6587755102040816, 0.616326530612245, 0.5738775510204082)), // white
        K(0.42857142857142844, v(0.3552001944934212, 0.3076083577587273, 0.15373080673831901)), // mid grey
        K(1.0, v(0.608, 0.020000000000000004, 0.27999999999999997)), // red
        K(1.0, v(0.008, 0.62, 0.27999999999999997)), // green
        K(0.0, v(0.3, 0.2, 0.1)), // blue
        K(0.8665774132608423, v(0.45607729351928, 0.3027334899068501, 0.38138320756868443)), // light skin
        K(0.6520281394182138, v(0.35101317459596393, 0.24264897402411037, 0.21318526975057522)), // medium skin
        K(0.49582318686539484, v(0.2730888550638728, 0.20114694114273643, 0.1404114008369944)), // dark skin
        K(0.0, v(0.3, 0.2, 0.1)), // sky
        K(0.4285714285714286, v(0.6587755102040816, 0.616326530612245, 0.5738775510204082)), // near white
    )
    private val print = listOf(
        v(0.0, 0.0, 0.0), // black
        v(0.9818300801129647, 0.9818300801129647, 0.9818300801129647), // white
        v(0.2101520301071407, 0.2101520301071407, 0.2101520301071407), // mid grey
        v(0.9431754298589172, 0.010436853751600816, 0.010436853751600816), // red
        v(0.035110243664839634, 0.9678488197721561, 0.035110243664839634), // green
        v(0.0035444065892078045, 0.0035444065892078045, 0.9362829826965242), // blue
        v(0.6161828413006022, 0.37532466996035657, 0.2381800716897111), // light skin
        v(0.30679302699087846, 0.16883927319143002, 0.10339513924333221), // medium skin
        v(0.10238878695183867, 0.05046963294341721, 0.033893635738061464), // dark skin
        v(0.4525505205911083, 0.6857351646179374, 0.9655567374501324), // sky
        v(0.9523751777095757, 0.9523751777095757, 0.9523751777095757), // near white
    )

    @Test fun theKeyerIsPinned() {
        val green = EffectColorMath.linear(EffectRGB(.1, .8, .15))
        val blue = v(.02, .05, .7)
        val background = v(.3, .2, .1)
        val wrap = v(.4, .5, .6)
        for ((screen, case) in listOf(green to 0, blue to 1)) {
            val pins = if (case == 0) greenKey else blueKey
            for ((input, pin) in inputs.zip(pins)) {
                val keyed = input.second * .6 + screen * .4
                val a = if (case == 0) c.keyAlpha(keyed, screen) else c.keyAlpha(keyed, screen, 2, 1.5, .3, .1, .8)
                assertEquals("alpha of ${input.first}", pin.alpha, a, 1e-12)
                val out = if (case == 0) {
                    c.chromaKey(keyed, a, ChromaKeyInputs(background, screen, wrap))
                } else {
                    c.chromaKey(keyed, a, ChromaKeyInputs(background, screen, wrap), 2, .5, .6)
                }
                assertVector("key of ${input.first}", pin.out, out)
                assertEquals(keyed, VideoEffectFormulas.mixed(keyed, out, 0.0))
                assertEquals(EffectVector(a), c.chromaKey(keyed, a, ChromaKeyInputs(background, screen, wrap), view = 1))
                assertEquals(keyed, c.chromaKey(keyed, a, ChromaKeyInputs(background, screen, wrap), view = 2))
            }
        }
        assertTrue("Auto picks the bluer screen", c.blueScreen(blue, 0))
        assertFalse(c.blueScreen(blue, 1))
        assertTrue(c.blueScreen(green, 2))
    }

    @Test fun theBorderGivesTheScreen() {
        val screen = v(.05, .6, .1)
        val picture = EffectImage.of(32, 18) { x, y -> if (x in 8..23 && y in 4..13) v(.6, .4, .3) else screen + (x % 3) * .01 }
        val reference = c.screenReference(picture, v(0.0, 1.0, 0.0))
        assertEquals(screen.x + .01, reference.x, .002)
        assertEquals(screen.y + .01, reference.y, .002)
        val grey = EffectImage.flat(16, 9, v(.4, .4, .4))
        assertEquals("Without a screen in the border the manual colour stays", v(0.0, 1.0, 0.0), c.screenReference(grey, v(0.0, 1.0, 0.0)))
        val blue = EffectImage.flat(16, 9, v(.1, .1, .7))
        assertVector("A blue border gives a blue screen", v(.1, .1, .7), c.screenReference(blue, v(0.0, 1.0, 0.0)), 1e-6)
    }

    @Test fun morphologyGrowsAndShrinksByTheRadius() {
        val dot = EffectImage.of(15, 15) { x, y -> EffectVector(if (x == 7 && y == 7) 1.0 else 0.0) }
        val grown = c.morph(dot, 2.0)
        for (y in 0 until 15) for (x in 0 until 15) {
            val inside = kotlin.math.abs(x - 7) <= 2 && kotlin.math.abs(y - 7) <= 2
            assertEquals("grown at $x $y", if (inside) 1.0 else 0.0, grown[x, y].x, 1e-6)
        }
        val shrunk = c.morph(grown, -1.0)
        for (y in 0 until 15) for (x in 0 until 15) {
            val inside = kotlin.math.abs(x - 7) <= 1 && kotlin.math.abs(y - 7) <= 1
            assertEquals("shrunk at $x $y", if (inside) 1.0 else 0.0, shrunk[x, y].x, 1e-6)
        }
        // A fractional radius reads between pixels at its end.
        val half = c.morphology(dot, 1.5, true)
        assertEquals(.5, half[5, 7].x, 1e-9)
        assertEquals(1.0, half[6, 7].x, 1e-9)
    }

    private fun key(view: String = "Result", grow: Double = 0.0, softness: Double = 0.0, background: String = "Solid", intensity: Double = 1.0) =
        VideoEffectSettings(VideoEffectID.ChromaKey).copy(algorithm = 1)
            .with("autoScreen", EffectValue.Toggle(false)).with("screenColor", EffectValue.Color(EffectRGB(.1, .8, .15)))
            .with("view", EffectValue.Choice(view)).with("grow", EffectValue.Number(grow)).with("softness", EffectValue.Number(softness))
            .with("background", EffectValue.Choice(background)).with("intensity", EffectValue.Number(intensity))

    private val screen = EffectColorMath.linear(EffectRGB(.1, .8, .15))
    private val subject = EffectImage.of(24, 16) { x, y -> if (x in 8..15 && y in 4..11) v(.6, .4, .3) else screen }

    @Test fun thePassKeysOverASolidBackground() {
        val effect = key()
        val u = EffectFrame.of(listOf(effect), 0.5, 0).single()
        val solid = EffectImage.flat(1, 1, c.solidBackground(EffectRGB(.2, .3, .4), 1.0))
        val out = c.chromaKeyPass(u, subject, solid)
        assertVector("screen becomes background", EffectColorMath.linear(EffectRGB(.2, .3, .4)) * 2.0, out[1, 1], 1e-6)
        assertVector("subject is kept", v(.6, .4, .3), out[10, 6], 1e-6)
        val matte = c.chromaKeyPass(EffectFrame.of(listOf(key(view = "Matte")), 0.5, 0).single(), subject, solid)
        assertEquals(0.0, matte[1, 1].x, 1e-6)
        assertEquals(1.0, matte[10, 6].x, 1e-6)
        assertEquals("At no intensity the picture comes back", subject, c.chromaKeyPass(EffectFrame.of(listOf(key(intensity = 0.0)), 0.5, 0).singleOrNull() ?: u.copy(intensity = 0.0), subject, solid))
        // Grow is in pixels at 1080: five of them on this 16-pixel-high picture reach 5 · 16 / 1080 of a pixel out.
        val grownMatte = c.matte(subject, { screen }, EffectFrame.of(listOf(key(grow = 5.0)), 0.5, 0).single())
        assertEquals(5 * 16 / 1080.0, grownMatte[7, 6].x, 1e-6)
        assertEquals(0.0, grownMatte[6, 6].x, 1e-6)
        val soft = c.matte(subject, { screen }, EffectFrame.of(listOf(key(softness = 1.0)), 0.5, 0).single())
        assertEquals("Softness is a Gaussian of 1.5 pixels at 1080", VideoEffectPasses.blur(matte.let { m -> EffectImage.of(24, 16) { x, y -> EffectVector(m[x, y].x) } }, 1.5 * 16 / 1080), soft)
        assertTrue(soft[7, 6].x > 0)
    }

    @Test fun backgroundsArePlacedAsTheOriginal() {
        val image = EffectImage.of(2, 1) { x, _ -> if (x == 0) v(1.0, 0.0, 0.0) else v(0.0, 0.0, 1.0) }
        val filled = c.imageBackground(image, 4, 4)
        // Fill scales by 4 to 8 × 4, centred: pixel 0 reads the image at 0.3125 of its width, pixel 3 at 0.6875.
        assertVector("fill", v(.875, 0.0, .125), filled[0, 2], 1e-6)
        assertVector("fill", v(.125, 0.0, .875), filled[3, 2], 1e-6)
        val fitted = c.imageBackground(image, 4, 4, fill = false)
        assertVector("fit leaves black bars", EffectVector.ZERO, fitted[0, 0], 0.0)
        assertVector("fit", v(1.0, 0.0, 0.0), fitted[0, 1], 1e-6)
        val left = c.imageBackground(image, 4, 4, x = 0.0)
        assertVector("X 0 lays the image's left edge on the picture's", v(.625, 0.0, .375), left[3, 2], 1e-6)
        val brighter = c.imageBackground(EffectImage.flat(2, 2, v(.25, .25, .25)), 2, 2, brightness = 1.0)
        assertVector("a stop brighter", v(.5, .5, .5), brighter[0, 0], 1e-9)
        val flat = EffectImage.flat(20, 12, v(.3, .2, .1))
        assertVector("blurring a flat picture keeps it", v(.3, .2, .1), c.blurOriginalBackground(flat)[5, 5], 1e-6)
    }

    @Test fun aCleanPlateMustHaveTheFramesShape() {
        assertTrue(c.cleanPlateCompatible(Size(1920.0, 1080.0), Size(1280.0, 720.0)))
        assertFalse(c.cleanPlateCompatible(Size(1080.0, 1920.0), Size(1280.0, 720.0)))
        assertNull(c.cleanPlate(EffectImage.flat(9, 16, v(.1, .5, .1)), 16, 9))
        val plate = c.cleanPlate(EffectImage.flat(32, 18, v(.1, .5, .1)), 16, 9)!!
        assertVector("flat plate", v(.1, .5, .1), plate[3, 3], 1e-6)
        // With a plate the screen is the plate under each pixel.
        val u = EffectFrame.of(listOf(key()), 0.5, 0, inputs = mapOf(key().id to EffectFrameInputs(cleanPlate = true))).single()
        val pictures = c.chromaKeyPictures(u, subject, EffectImage.flat(1, 1, EffectVector.ZERO), EffectImage.flat(24, 16, screen))
        assertVector("the plate is the screen", screen, pictures.screen(Point(.1, .1)), 1e-6)
    }

    @Test fun printAndPushInArePinned() {
        val uv = Point(.8, .3)
        for ((input, expected) in inputs.zip(print)) assertVector("print of ${input.first}", expected, c.freezeFramePrint(input.second, uv))
        assertEquals(Point(.8, .3), c.freezeFramePoint(uv, 0.0))
        val pushed = c.freezeFramePoint(uv, 3.0, 3.0, 0.1)
        assertEquals(.5 + .3 / 1.1, pushed.x, 1e-12)
        assertEquals(c.freezeFramePoint(uv, 10.0, 3.0, 0.1), pushed)
    }

    @Test fun timePixelsReadTheirMovedPoints() {
        val picture = EffectImage.of(12, 8) { x, y -> v(x / 11.0, y / 7.0, .3) }
        val clean = VideoEffectSettings(VideoEffectID.FreezeFrame, 0.0, 1.0).with("push", EffectValue.Number(0.0))
        val held = c.timePass(EffectFrame.of(listOf(clean), 0.5, 3, holdTime = 1.0).single(), picture)
        for (y in 0 until 8) for (x in 0 until 12) assertVector("A clean hold without push-in is the frame", picture[x, y], held[x, y], 1e-6)
        val crank = VideoEffectSettings(VideoEffectID.Undercranking, 0.0, 4.0).with("flicker", EffectValue.Number(0.0)).with("weave", EffectValue.Number(0.0))
        val u = EffectFrame.of(listOf(crank), 0.5, 3).single()
        val out = c.timePass(u, picture)
        val uv = picture.uv(2, 5)
        assertVector("magnified by 1.01", picture.sample(Point((uv.x - .5) / 1.01 + .5, (uv.y - .5) / 1.01 + .5)), out[2, 5], 1e-6)
        val flickering = EffectFrame.of(listOf(crank.with("flicker", EffectValue.Number(1.0))), 0.5, 3).single()
        val frame = VideoEffectTiming.undercrankingFrame(0.5, 16.0)
        assertVector("flicker", (out[2, 5] * VideoEffectTiming.undercrankingFlicker(frame, 1.0)).clamped(), c.timePass(flickering, picture)[2, 5], 1e-6)
        assertEquals(v(.2, .4, .6), c.stepPrinting(v(.2, .4, .6), v(1.0, 1.0, 1.0), 0.0))
    }
}
