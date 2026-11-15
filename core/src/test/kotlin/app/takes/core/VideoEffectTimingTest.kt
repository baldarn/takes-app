package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The time effects: Freeze Frame's hold, Undercranking's cadence and retime,
 * Step Printing's blocks. The checks of Tests/VideoEffectIntegrationTests.swift
 * (the timeline and the frames it shows) run here on the maths.
 */
class VideoEffectTimingTest {
    private val timing = VideoEffectTiming
    private fun number(value: Double) = EffectValue.Number(value)
    private fun all(start: Double, end: Double) = listOf(SourceInterval(start, end))

    @Test fun withoutTimeEffectsTheClipPlaysAtItsSpeed() {
        val spans = timing.playbackSpans(emptyList(), 1.0, 5.0, SpeedSettings(rate = 2.0), listOf(SourceInterval(1.0, 2.0), SourceInterval(3.0, 5.0)))
        assertEquals(listOf(EffectPlaybackSpan(1.0, 2.0, 2.0), EffectPlaybackSpan(3.0, 5.0, 2.0)), spans)
    }

    // VideoEffectIntegrationTests.swift:285-301.
    @Test fun slowStepPrintingRetimesAndRepeatsItsBlock() {
        val step = VideoEffectSettings(VideoEffectID.StepPrinting, 0.0, 4.0).with("speed", number(0.5))
        val spans = timing.playbackSpans(listOf(step), 0.0, 4.0, null, all(0.0, 4.0))
        assertEquals("Slow step printing retimes the timeline at half speed", 8.0, SpeedMapping.duration(spans), 1e-12)
        val clock = VideoEffectSequenceClock.of(listOf(step), 0.0, 4.0)
        val frames = VideoEffectFrameIndex((0 until 120).map { it / 30.0 })
        fun plan(output: Double) = VideoEffectSamplePlan.make(listOf(step), SpeedMapping.sourceTime(output, spans, 4.0), 30.0, clock, frames)!!
        assertEquals("Step printing repeats the same accumulated source block", plan(1.0).times, plan(1.1).times)
        assertEquals(listOf(15 / 30.0, 16 / 30.0, 17 / 30.0), plan(1.0).times)
        assertTrue(plan(1.0).firstAtOrAfter)
        assertEquals(4.0, plan(1.0).before, 0.0)
    }

    @Test fun stepBlocksCountFromTheEffectsStartAndStayInsideTheClip() {
        val step = VideoEffectSettings(VideoEffectID.StepPrinting, 0.1, 4.0).with("step", number(4.0))
        val frames = VideoEffectFrameIndex((0 until 120).map { it / 30.0 })
        val clock = VideoEffectSequenceClock.of(listOf(step), 0.5, 1.0)
        // The effect's first frame is frame 3; blocks are 3-6, 7-10, ...; the clip keeps frames 15 to 29.
        assertEquals(listOf(15, 16, 17, 18).map { it / 30.0 }, frames.block(0.5, 0.1, 4, 0.5, 1.0, false))
        assertEquals(listOf(19, 20, 21, 22).map { it / 30.0 }, VideoEffectSamplePlan.make(listOf(step), 20 / 30.0, 30.0, clock, frames)!!.times)
        assertEquals(listOf(27, 28, 29, 29).map { it / 30.0 }, VideoEffectSamplePlan.make(listOf(step), 28 / 30.0, 30.0, clock, frames)!!.times)
        // A clip shorter than a frame repeats the frame under its start.
        assertEquals(List(4) { 15 / 30.0 }, frames.block(0.51, 0.1, 4, 0.51, 0.52, false))
    }

    @Test fun withoutAnIndexTheBlockReadsHalfAFrameEarly() {
        val step = VideoEffectSettings(VideoEffectID.StepPrinting, 0.0, 4.0)
        val clock = VideoEffectSequenceClock.of(listOf(step), 0.0, 4.0)
        val plan = VideoEffectSamplePlan.make(listOf(step), 1.0, 30.0, clock)!!
        assertEquals(listOf(29.5, 30.5, 31.5).map { it / 30.0 }, plan.times)
    }

    @Test fun smearSharesTheBlockLikeTheCompositorsDissolves() {
        for (count in 2..6) for (smear in listOf(0.0, 0.3, 0.7, 1.0)) {
            val plan = VideoEffectSamplePlan(List(count) { it.toDouble() }, smear)
            assertEquals(1.0, plan.weights.sum(), 1e-12)
            plan.weights.zip(plan.dissolved).forEach { (a, b) -> assertEquals("$count frames at smear $smear", a, b, 1e-12) }
            if (smear > 0) assertEquals(1 - smear + smear / count, plan.weights[0], 1e-12) else assertEquals(listOf(1.0) + List(count - 1) { 0.0 }, plan.weights)
        }
    }

    @Test fun animatedSpeedIsSubdividedWhereItChanges() {
        val crank = VideoEffectSettings(VideoEffectID.Undercranking, 0.0, 2.0).withKeyframe("speed", 0.0, number(1.0)).withKeyframe("speed", 2.0, number(1.5))
        val spans = timing.playbackSpans(listOf(crank), 0.0, 2.0, null, all(0.0, 2.0))
        assertTrue(spans.size > 8)
        spans.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start, 1e-12) }
        spans.forEach { assertTrue(abs(kotlin.math.ln(crank.number("speed", it.end - 1e-7) / crank.number("speed", it.start + 1e-7))) <= 0.01 || it.end - it.start <= 1.0 / 30) }
        // A split cannot move the grid: the second half's spans are the first's restricted to it.
        val half = timing.playbackSpans(listOf(crank), 1.0, 2.0, null, all(1.0, 2.0))
        assertEquals(spans.filter { it.end > 1.0 }.map { it.copy(start = maxOf(1.0, it.start)) }, half)
    }

    @Test fun undercrankingSpeedsUpAndMutes() {
        val crank = VideoEffectSettings(VideoEffectID.Undercranking, 0.0, 4.0).with("mute", EffectValue.Toggle(true))
        val spans = timing.playbackSpans(listOf(crank), 0.0, 4.0, null, all(0.0, 4.0))
        assertEquals(4.0 / 1.25, SpeedMapping.duration(spans), 1e-12)
        assertEquals(EffectSound.SILENT, timing.heard(listOf(crank), spans, 0.0, 1.0, 4.0))
        val loud = crank.with("mute", EffectValue.Toggle(false))
        assertEquals(EffectSound(1.25), timing.heard(listOf(loud), spans, 0.0, 1.0, 4.0))
    }

    // VideoEffectModelTests.swift:210-213 on the shown frame, at 16 frames a second on a 30 fps movie.
    @Test fun undercrankingHoldsFlickerAndWeaveWithTheCadence() {
        val crank = VideoEffectSettings(VideoEffectID.Undercranking, 0.0, 4.0).with("speed", number(1.0))
        val clock = VideoEffectSequenceClock.of(listOf(crank), 0.0, 4.0)
        val size = Size(1920.0, 1080.0)
        val moments = (0 until 30).map { timing.undercranking(crank, listOf(crank), clock, it / 30.0, 30.0, size) }
        assertTrue(moments.all { it.held })
        assertEquals(16, moments.map { it.sourceTime }.toSet().size)
        for ((a, b) in moments.zipWithNext()) if (a.sourceTime == b.sourceTime) {
            assertEquals(a.flicker, b.flicker, 0.0)
            assertEquals(a.weave, b.weave)
        }
        moments.forEach {
            assertTrue(it.flicker in 1 - .08 * .5..1 + .08 * .5)
            assertEquals(VideoEffectTime.firstFrame(clock.cadence.hold(it.sourceTime)!!.boundary, 30.0), it.sourceTime, 1e-9)
        }
        // The weave is the noise's, scaled to the picture: none without weave, at most two and three pixels at 1080.
        assertEquals(Point(0.0, 0.0), timing.undercrankingWeave(5, 0.0, size).let { Point(abs(it.x), abs(it.y)) })
        (0 until 50).map { timing.undercrankingWeave(it, 1.0, size) }.forEach { assertTrue(abs(it.x) <= 2 && abs(it.y) <= 3) }
        assertEquals(1.0, timing.undercrankingFlicker(7, 0.0), 0.0)
        assertEquals(2, timing.undercrankingFrame(0.13, 16.0))
    }

    @Test fun outsideTheCadenceNothingIsHeld() {
        val crank = VideoEffectSettings(VideoEffectID.Undercranking, 1.0, 2.0)
        val clock = VideoEffectSequenceClock.of(listOf(crank), 0.0, 4.0)
        assertNull(VideoEffectSamplePlan.make(listOf(crank), 0.5, 30.0, clock))
        assertFalse(timing.undercranking(crank, listOf(crank), clock, 0.5, 30.0, Size(100.0, 100.0)).held)
        assertEquals(1.0, VideoEffectSamplePlan.make(listOf(crank), 1.0, 30.0, clock)!!.times.single(), 1e-12)
    }

    // VideoEffectIntegrationTests.swift:270-284 on the hold itself.
    @Test fun freezeFrameHoldsPushesInAndPlaysItsAudioChoice() {
        val effect = VideoEffectSettings(VideoEffectID.FreezeFrame, 2.0, 2.0 + 1 / 30.0)
        val hold = timing.freezeHold(2.0, effect)
        assertEquals("A new freeze holds for three seconds", FrozenFrameSegment(2.0, 3.0), hold)
        assertEquals(2.0, timing.freezeSourceTime(0.0, 4.0, 2.0, 1 / 30.0), 0.0)
        assertEquals(4 - 1 / 30.0, timing.freezeSourceTime(0.0, 4.0, 4.0, 1 / 30.0), 1e-12)
        assertEquals(VideoEffectSamplePlan(listOf(2.0), 0.0), VideoEffectSamplePlan.make(listOf(effect), 2.5, 30.0, VideoEffectSequenceClock.of(listOf(effect), 2.0, 2.04), frozen = hold))

        val start = timing.freezeShown(hold, effect, 0.0, 10.0)
        val middle = timing.freezeShown(hold, effect, 1.5, 10.0)
        val end = timing.freezeShown(hold, effect, 3.0, 10.0)
        assertEquals(1.0, start.zoom, 0.0)
        assertEquals(1.025, middle.zoom, 1e-12)
        assertEquals(1.05, end.zoom, 1e-12)
        assertEquals(2.0, middle.sourceTime, 0.0)
        assertFalse(middle.print)
        assertEquals(345, middle.grainFrame)
        // A split hold's later piece continues the push from its offset.
        val later = timing.freezeShown(hold.copy(duration = 1.5, offset = 1.5), effect, 0.0)
        assertEquals(middle.zoom, later.zoom, 0.0)
        assertTrue(timing.freezeShown(hold, effect.with("style", EffectValue.Choice("Print")), 1.0).print)

        assertEquals("Cut silences the hold", EffectSound.SILENT, timing.freezeHeard(hold, effect, 1.0))
        val continued = effect.with("audio", EffectValue.Choice("Continue"))
        assertEquals("Continue plays on from the held second", EffectSound(3.0), timing.freezeHeard(hold, continued, 1.0))
        val faded = effect.with("audio", EffectValue.Choice("Fade out over hold"))
        assertEquals(EffectSound(2.0, 1.0), timing.freezeHeard(hold, faded, 0.0))
        assertEquals(EffectSound(3.5, 0.5), timing.freezeHeard(hold, faded, 1.5))
        assertEquals(EffectSound.SILENT, timing.freezeHeard(hold, faded, 3.0))
        assertTrue(VideoEffectSettings.mutesSourceAudio(listOf(effect), 2.0, frozen = true))
    }
}
