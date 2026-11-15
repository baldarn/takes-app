package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Night fix in its Fast mode. On a flat field the cleanup has nothing to
 * take, so the pinned values (computed outside this code from the shader's
 * text) are the brightness curve and the mix alone.
 */
class VideoEffectNightFixTest {
    private val nf = VideoEffectNightFix
    private val inputs = VideoEffectFormulaPins.inputs
    private fun v(x: Double, y: Double, z: Double) = EffectVector(x, y, z)

    private fun assertVector(message: String, expected: EffectVector, actual: EffectVector, within: Double) {
        for (i in 0..2) assertEquals("$message, channel $i: $actual", expected[i], actual[i], within)
    }

    private fun effect(intensity: Double = 1.0, reduction: Double = 1.0, brightness: Double = 2.5) = VideoEffectSettings(VideoEffectID.NightFix)
        .with("intensity", EffectValue.Number(intensity)).with("reduction", EffectValue.Number(reduction)).with("brightness", EffectValue.Number(brightness))

    private val lifted = listOf(
        v(0.0, 0.0, 0.0), // black
        v(1.0, 1.0, 1.0), // white
        v(0.7784729138619435, 0.7784729138619435, 0.7784729138619435), // mid grey
        v(1.6596034325749913, 0.0, 0.0), // red
        v(0.0, 1.1403974847995555, 0.0), // green
        v(0.0, 0.0, 2.0048299759243045), // blue
        v(1.0998695708668107, 0.8503961940230238, 0.662806216515336), // light skin
        v(0.9696923028299207, 0.7079874221950871, 0.5368629235662108), // medium skin
        v(0.682391537937806, 0.46129995266449797, 0.36278535341565454), // dark skin
        v(0.786570925331172, 0.9813683090779981, 1.1694627505864525), // sky
        v(0.9973068066241362, 0.9973068066241362, 0.9973068066241362), // near white
    )
    private val halfLifted = listOf(
        v(0.0, 0.0, 0.0), // black
        v(1.0, 1.0, 1.0), // white
        v(0.5240545503160029, 0.5240545503160029, 0.5240545503160029), // mid grey
        v(1.1482306802212083, 0.0, 0.0), // red
        v(0.0, 1.040159504183143, 0.0), // green
        v(0.0, 0.0, 1.1929995168228686), // blue
        v(0.8764920168329, 0.673501388463289, 0.5208638289293485), // light skin
        v(0.6490861330414905, 0.4658923441148055, 0.34610495151456766), // medium skin
        v(0.37407356911497347, 0.24021931005482952, 0.18057615958051373), // dark skin
        v(0.7016675502008618, 0.8777888969713443, 1.0478499405096176), // sky
        v(0.9887457649187523, 0.9887457649187523, 0.9887457649187523), // near white
    )

    @Test fun theEncodingRoundTrips() {
        for (x in listOf(0.0, 0.001, 0.018, 0.2, 0.5, 1.0, 2.0)) assertEquals(x, nf.decode(nf.encode(x)), 1e-12)
        assertEquals(0.045, nf.encode(0.01), 1e-12)
        assertEquals(0.0, nf.encode(-1.0), 0.0)
    }

    @Test fun flatFieldsArePinned() {
        for ((pins, mix, stops) in listOf(Triple(lifted, 1.0, 2.5), Triple(halfLifted, 0.5, 1.0))) {
            for ((input, expected) in inputs.zip(pins)) {
                val raw = EffectImage.flat(6, 4, nf.encode(input.second))
                val out = nf.fast(raw, effect(mix, 1.0, stops), 0.0)
                for (y in 0 until 4) for (x in 0 until 6) assertVector("${input.first} at $stops stops, mix $mix", expected, out[x, y], 2e-5)
            }
        }
    }

    @Test fun nothingChangesWithoutIntensityOrWork() {
        val raw = EffectImage.of(8, 6) { x, y -> v(x / 7.0, y / 5.0, .3) }
        assertSame(raw, nf.fast(raw, effect(intensity = 0.0), 0.0))
        assertSame(raw, nf.fast(raw, effect(reduction = 0.0, brightness = 0.0), 0.0))
    }

    @Test fun theCleanupTakesNoiseAndKeepsLuma() {
        val random = Random(4)
        val raw = EffectImage.of(32, 24) { _, _ -> nf.encode(v(.02, .02, .02)) + v(random.nextDouble(-.02, .02), random.nextDouble(-.02, .02), random.nextDouble(-.02, .02)) }
        fun spread(image: EffectImage): Double {
            val values = (0 until 24).flatMap { y -> (0 until 32).map { x -> image[x, y].x } }
            val mean = values.average()
            return values.sumOf { (it - mean) * (it - mean) } / values.size
        }
        val cleaned = nf.adjust(raw, 1.0, 0.0, 0.5)
        assertTrue("Noise reduction lowers the variance", spread(cleaned) < spread(raw) * .8)
        val chroma = nf.chroma(cleaned, 1.0, true)
        for (y in 0 until 24) for (x in 0 until 32) assertEquals("Luma is kept", nf.y(cleaned[x, y]), nf.y(chroma[x, y]), 1e-5)
        // An edge survives the shrink: its variance is far above the noise.
        val edge = EffectImage.of(16, 4) { x, _ -> if (x < 8) v(.1, .1, .1) else v(.6, .6, .6) }
        val kept = nf.adjust(edge, 1.0, 0.0, 0.5)
        assertTrue(kept[7, 1].x < .2 && kept[8, 1].x > .5)
    }
}
