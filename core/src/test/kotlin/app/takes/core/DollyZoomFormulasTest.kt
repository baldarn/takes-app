package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dolly Zoom in the editor: hand computations of the arithmetic of
 * YouRec/VideoEffects/VideoEffects.metal case 8 (:366-369) and of the
 * pictures MetalVideoEffects.swift draws before it (:492-499), and the
 * effect's slots as the original's catalogue orders them
 * (VideoEffectCatalog.swift:268-275).
 */
class DollyZoomFormulasTest {
    private val d = DollyZoomFormulas
    private fun v(x: Double, y: Double, z: Double) = EffectVector(x, y, z)

    private fun assertVector(message: String, expected: EffectVector, actual: EffectVector, within: Double = 1e-6) {
        for (i in 0..2) assertEquals("$message, channel $i: $actual", expected[i], actual[i], within)
    }

    private fun dolly(values: Map<String, EffectValue> = emptyMap()): VideoEffectSettings =
        values.entries.fold(VideoEffectSettings(VideoEffectID.DollyZoom, start = 2.0, end = 10.0)) { effect, (id, value) -> effect.with(id, value) }

    private fun block(effect: VideoEffectSettings, time: Double) =
        EffectFrame.of(listOf(effect), time, 0, inputs = mapOf(effect.id to EffectFrameInputs(personMask = true))).single()

    // VideoEffects.metal:367
    @Test fun theMoveEasesInAndOutOverItsDuration() {
        assertEquals(0.0, d.progress(0.0, 2.5), 0.0)
        assertEquals(4 * .25 * .25 * .25, d.progress(.625, 2.5), 1e-12)
        assertEquals(.5, d.progress(1.25, 2.5), 1e-12)
        assertEquals(1 - .5 * .5 * .5 / 2, d.progress(1.875, 2.5), 1e-12)
        assertEquals("held at the end", 1.0, d.progress(9.0, 2.5), 0.0)
        assertEquals("linear", .25, d.progress(.625, 2.5, linear = true), 1e-12)
        assertEquals("a duration of nothing is a thousandth", 1.0, d.progress(.001, 0.0), 0.0)
    }

    // VideoEffects.metal:368
    @Test fun stretchGrowsTheBackgroundAndCompressBringsItBack() {
        assertEquals(1.0, d.scale(0.0, .3), 0.0)
        assertEquals(1.3, d.scale(1.0, .3), 1e-12)
        assertEquals(1.3, d.scale(0.0, .3, compress = true), 1e-12)
        assertEquals(1.0, d.scale(1.0, .3, compress = true), 0.0)
        assertEquals(1.15, d.scale(.5, .3, compress = true), 1e-12)
    }

    // VideoEffectCatalog.swift:268-275; VideoEffects.metal:367-368, MetalVideoEffects.swift:641-643
    @Test fun theSlotsAreTheCataloguesAndTheClockStartsWithTheEffect() {
        val u = block(dolly(mapOf("direction" to EffectValue.Choice("Compress"), "strength" to EffectValue.Number(.4), "easing" to EffectValue.Choice("Linear"),
            "targetX" to EffectValue.Number(.3), "targetY" to EffectValue.Number(.6))), 3.0)
        assertEquals(1.0, u.p[0].x, 0.0)
        assertEquals(.4, u.p[1].x, 1e-12)
        assertEquals(2.5, u.p[2].x, 1e-12)
        assertEquals(1.0, u.p[3].x, 0.0)
        assertEquals(.3, u.p[4].x, 1e-12)
        assertEquals(.6, u.p[5].x, 1e-12)
        assertEquals("a camera effect has no intensity", 1.0, u.intensity, 0.0)
        // A second into a linear Compress of 2.5 s: 1 + 0.4 · (1 − 0.4).
        assertEquals(1.24, d.scale(u), 1e-12)
        // The default: Stretch by 0.3 with the cubic, half way at 1.25 s.
        assertEquals(1.15, d.scale(block(dolly(), 3.25)), 1e-12)
    }

    // VideoEffects.metal:369
    @Test fun theBackgroundIsReadMagnifiedAboutTheSubject() {
        val target = Point(.5, .4)
        assertEquals(target, d.backgroundPoint(target, target, 1.7))
        assertEquals(Point(.75, .4), d.backgroundPoint(Point(1.0, .4), target, 2.0))
        assertEquals(Point(.5, .2), d.backgroundPoint(Point(.5, 0.0), target, 2.0))
        assertVector("subject", v(.9, .8, .7), d.dollyZoom(v(.9, .8, .7), v(.1, .2, .3), 1.0), 0.0)
        assertVector("background", v(.1, .2, .3), d.dollyZoom(v(.9, .8, .7), v(.1, .2, .3), 0.0), 0.0)
        assertVector("edge", v(.5, .5, .5), d.dollyZoom(v(.9, .8, .7), v(.1, .2, .3), .5), 1e-12)
    }

    /** A subject in the middle third before a background that darkens from left to right. */
    private val picture = EffectImage.of(96, 54) { x, _ -> if (x in 32 until 64) v(.8, .6, .5) else v(.9 - .8 * x / 95.0, .4, .2) }
    private val person = EffectMask.of(96, 54) { x, _ -> if (x in 32 until 64) 1.0 else 0.0 }

    // MetalVideoEffects.swift:492-499; VideoEffects.metal:366-369
    @Test fun aPassKeepsTheSubjectAndMagnifiesTheFilledBackground() {
        val u = block(dolly(mapOf("easing" to EffectValue.Choice("Linear"))), 2.0 + 2.5)
        assertEquals(1.3, d.scale(u), 1e-12)
        val pictures = d.pictures(picture, person)
        assertEquals(VideoEffectPasses.quarter(96), pictures.estimate.width)
        // The hole the subject leaves is filled with the background beside it: no subject colour in it.
        val middle = pictures.estimate[12, 6]
        assertTrue("filled: $middle", kotlin.math.abs(middle.y - .4) < 1e-3 && kotlin.math.abs(middle.z - .2) < 1e-3)
        val drawn = d.pass(u, picture, person)
        // Inside the subject, the subject.
        assertVector("subject", picture[48, 27], drawn[48, 27], 1e-6)
        // On the background, the estimate read magnified by 1.3 about the middle.
        val at = d.backgroundPoint(picture.uv(4, 27), Point(.5, .5), 1.3)
        assertVector("background", pictures.estimate.sample(at), drawn[4, 27], 1e-6)
        // Read nearer the middle than the pixel is: lighter, as the ramp is there.
        assertTrue(drawn[4, 27].x < pictures.estimate.sample(picture.uv(4, 27)).x)
        // Each pixel the mixture of the two by the refined mask.
        for (x in listOf(0, 20, 31, 32, 40, 63, 64, 80)) {
            val uv = picture.uv(x, 10)
            val expected = d.dollyZoom(picture[x, 10], pictures.estimate.sample(d.backgroundPoint(uv, Point(.5, .5), 1.3)), pictures.mask.sample(uv))
            assertVector("column $x", expected.clamped(), drawn[x, 10], 1e-6)
        }
    }

    // MetalVideoEffects.swift:634
    @Test fun withoutAPersonThePictureIsLeftAsItIs() {
        assertSame(picture, d.pass(block(dolly(), 3.0), picture, null))
    }

    @Test fun theOriginalReadsThePersonForADollyZoom() {
        assertTrue(VideoEffectPersonInputs.needsPerson(dolly(), 3.0))
        assertTrue(VideoEffectPersonInputs.requiresPerson(dolly(), 3.0))
    }

    // MediaRenderer.swift:361-404: the masks the export has made before it draws.
    @Test fun theExportMakesTheMasksOfTheSecondsThatReadThePerson() {
        val clip = TimelineClip(sourceID = "S", start = 1.0, end = 8.0)
        assertEquals(null, VideoEffectPersonInputs.personSpan(clip))
        assertEquals(2.0..8.0, VideoEffectPersonInputs.personSpan(clip.copy(videoEffects = listOf(dolly()))))
        assertEquals(null, VideoEffectPersonInputs.personSpan(clip.copy(videoEffects = listOf(dolly().copy(enabled = false)))))
        // Outside the clip, nothing.
        assertEquals(null, VideoEffectPersonInputs.personSpan(clip.copy(videoEffects = listOf(VideoEffectSettings(VideoEffectID.DollyZoom, start = 8.0, end = 9.0)))))
        // Color Isolation reads the person only on "The subject", here from its keyframe at 5 s; with it, the span of both.
        val isolation = VideoEffectSettings(VideoEffectID.ColorIsolation, start = 0.0, end = 6.0).let { e ->
            e.copy(parameters = e.parameters.map { p -> if (p.id == "mode") p.copy(keyframes = listOf(EffectKeyframe(time = 5.0, value = EffectValue.Choice("The subject")))) else p })
        }
        assertTrue(VideoEffectPersonInputs.needsPerson(isolation, 5.5))
        assertEquals(1.0..6.0, VideoEffectPersonInputs.personSpan(clip.copy(videoEffects = listOf(isolation))))
        assertEquals(null, VideoEffectPersonInputs.personSpan(clip.copy(videoEffects = listOf(VideoEffectSettings(VideoEffectID.ColorIsolation)))))
        assertEquals(1.0..8.0, VideoEffectPersonInputs.personSpan(clip.copy(videoEffects = listOf(isolation, dolly()))))
    }
}
