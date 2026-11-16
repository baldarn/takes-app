package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The time effects in the playback map: what Undercranking and Step Printing
 * do to a clip's length, its source clock, its timeline map, its plan and
 * its sound, and the held frame a Freeze Frame puts in, with its sound Cut,
 * Continue or Fade out over hold. YouRec/VideoEffects/VideoEffectTiming.swift,
 * YouRec/TrackTimeline.swift:49-79, YouRec/MediaRenderer.swift:497-504.
 */
class TimeEffectPlaybackTest {
    private val take = SourceMedia(id = "S1", filename = "take.mp4", duration = 20.0, recordedAt = 5)

    private fun clip(vararg effects: VideoEffectSettings, start: Double = 0.0, end: Double = 6.0, speed: SpeedSettings? = null) =
        TimelineClip(id = "C1", sourceID = "S1", start = start, end = end, speed = speed, videoEffects = effects.toList().ifEmpty { null })

    private fun undercranking(start: Double = 0.0, end: Double = 6.0, speed: Double = 1.25, mute: Boolean = false) =
        VideoEffectSettings(VideoEffectID.Undercranking, start, end).with("speed", EffectValue.Number(speed)).with("mute", EffectValue.Toggle(mute))

    private fun near(expected: Double, actual: Double, tolerance: Double = 1e-9) = assertEquals(expected, actual, tolerance)

    @Test fun aClipWithoutTimeEffectsKeepsItsArithmetic() {
        val plain = clip(speed = SpeedSettings(rate = 2.0))
        assertFalse(plain.timed)
        assertEquals(3.0, plain.duration, 0.0)
        assertEquals(listOf(EffectPlaybackSpan(0.0, 6.0, 2.0)), plain.playbackSpans)
        assertEquals(SourcePlaybackMap.single(EditTime.of(3.0), 0.0, 6.0), SourcePlaybackMap.of(plain, EditTime.of(3.0)))
    }

    @Test fun undercrankingOverTheWholeClipIsOneStretchAtItsSpeed() {
        val sped = clip(undercranking(speed = 1.25), speed = SpeedSettings(rate = 2.0))
        assertTrue(sped.timed)
        near(6.0 / 2.5, sped.duration)
        near(3.0, sped.sourceTime(1.2))
        near(1.2, sped.offset(3.0))
        val plan = RenderPlan.of(EditState(listOf(sped)))
        near(2.5, plan.pictures[0].rate)
        near(2.4, plan.duration)
        val timeline = EditState(listOf(sped)).toTimeline()
        assertEquals(1, timeline.video[0].mapping.runs.size)
        assertEquals(EditTime.of(2.4), timeline.video[0].mapping.duration)
    }

    @Test fun undercrankingOverPartOfTheClipPlaysThreeStretches() {
        val sped = clip(undercranking(2.0, 4.0, speed = 1.5))
        near(2.0 + 2.0 / 1.5 + 2.0, sped.duration)
        assertEquals(listOf(1.0, 1.5, 1.0), sped.playbackSpans.map { it.rate })
        near(1.0, sped.sourceTime(1.0))
        near(3.5, sped.sourceTime(2.0 + 1.5 / 1.5))
        near(5.0, sped.sourceTime(2.0 + 2.0 / 1.5 + 1.0))
        for (second in listOf(0.5, 2.5, 3.9, 5.5)) near(second, sped.sourceTime(sped.offset(second)))

        val map = SourcePlaybackMap.of(sped)
        assertEquals(3, map.runs.size)
        assertEquals(EditTime.of(sped.duration), map.duration)
        near(3.0, map.source(EditTime.of(2.0 + 1.0 / 1.5))!!, 1e-6)

        val picture = RenderPlan.of(EditState(listOf(sped))).pictures[0]
        assertEquals(3, picture.spans.size)
        for (offset in listOf(0.0, 1.0, 2.5, 3.2, 4.9)) {
            near(sped.sourceTime(offset), picture.sourceStart + picture.position(offset))
            near(offset, picture.offset(picture.position(offset)))
        }
        assertEquals(1.5, PicturePlayback.rate(picture.spans, 3.0), 0.0)
        assertEquals(1.0, PicturePlayback.rate(picture.spans, 4.5), 0.0)
        assertEquals(2.0, PicturePlayback.nextChange(picture.spans, 0.5)!!, 0.0)
        assertNull(PicturePlayback.nextChange(picture.spans, 4.5))

        // The layers' plan of the same timeline plays the same stretches.
        val layers = LayerPlan.of(EditState(listOf(sped)).toTimeline(), 4) as LayerPlan.Outcome.Planned
        val layered = layers.plan.pictures[0]
        for (offset in listOf(0.0, 1.0, 2.5, 3.2, 4.9)) near(sped.sourceTime(offset), layered.sourceStart + layered.position(offset), 1e-6)
    }

    @Test fun stepPrintingAtHalfSpeedTakesTwiceAsLong() {
        val stepped = clip(VideoEffectSettings(VideoEffectID.StepPrinting, 0.0, 6.0).with("speed", EffectValue.Number(0.5)))
        near(12.0, stepped.duration)
        near(12.0, EditState(listOf(stepped)).duration)
    }

    @Test fun aSpeedChangeMovesTheClipsAfterIt() {
        val first = clip(undercranking(speed = 1.5))
        val second = TimelineClip(id = "C2", sourceID = "S1", start = 10.0, end = 12.0)
        val state = EditState(listOf(first, second))
        near(4.0, TimelineEditing.clipStart(state.clips, 1))
        near(6.0, state.duration)
    }

    @Test fun muteSilencesTheStretchItCoversAndTheMixLeavesItOut() {
        val muted = clip(undercranking(2.0, 4.0, speed = 1.0, mute = true))
        val runs = SourcePlaybackMap.of(muted).runs
        assertEquals(listOf(false, true, false), runs.map { it.silent })
        val timeline = EditState(listOf(muted)).toTimeline()
        val sound = timeline.audio.single()
        assertEquals(listOf(false, true, false), sound.mapping.runs.map { it.silent })
        val plan = TrackMix.plan(timeline)
        val heard = plan.pieces.map { EditInterval(it.start, it.end) }
        assertEquals(listOf(EditInterval.of(0.0, 2.0), EditInterval.of(4.0, 6.0)), heard)
        // The retimed stretch of an unmuted clip is heard at its speed, the pitch kept.
        val sped = TrackMix.plan(EditState(listOf(clip(undercranking(2.0, 4.0, speed = 1.5)))).toTimeline())
        val runsHeard = sped.pieces.flatMap { it.runs }
        assertTrue(runsHeard.toString(), runsHeard.any { abs(it.rate - 1.5) < 1e-3 && abs(it.sourceStart - 2.0) < 1e-6 && abs(it.sourceEnd - 4.0) < 1e-6 })
        near(4.0 + 2.0 / 1.5, sped.duration.seconds, 1e-6)
    }

    @Test fun aFreezeFrameIsPutInAtThePlayhead() {
        val state = EditState(listOf(TimelineClip(id = "C1", sourceID = "S1", start = 0.0, end = 6.0)))
        val (frozen, effectID) = TimelineEditing.freezing(state, 2.01)!!
        assertEquals(3, frozen.clips.size)
        val hold = frozen.clips[1]
        val segment = hold.frozenFrame!!
        near(2.0, segment.sourceTime)
        near(3.0, segment.duration)
        near(2.0, hold.start)
        near(2.0 + 1.0 / 30, hold.end)
        assertEquals(VideoEffectID.FreezeFrame, hold.videoEffects!!.single().kind)
        assertEquals(effectID, hold.videoEffects!!.single().id)
        near(3.0, hold.duration)
        near(2.0, hold.sourceTime(1.7))
        near(9.0, frozen.duration)
        // The footage resumes after the hold, where it was cut.
        near(2.0, frozen.clips[2].start)
        near(5.0, TimelineEditing.clipStart(frozen.clips, 2))
        // A held frame is one hold in the map, silent with its sound cut.
        val run = frozen.toTimeline().video[1].mapping.runs.single()
        assertTrue(run.isHold && run.silent)
        assertEquals(EditTime.of(3.0), run.duration)
        // Inside a transition there is no single frame to hold; a held frame is not held again.
        assertNull(TimelineEditing.freezing(frozen, 3.0))
    }

    @Test fun aFreezeOnTheTimeEffectsOfItsClipKeepsNone() {
        val state = EditState(listOf(clip(undercranking(speed = 1.0), VideoEffectSettings(VideoEffectID.Noir, 0.0, 6.0))))
        val hold = TimelineEditing.freezing(state, 3.0)!!.first.clips.single { it.frozenFrame != null }
        assertEquals(listOf(VideoEffectID.Noir, VideoEffectID.FreezeFrame), hold.videoEffects!!.map { it.kind })
    }

    private fun frozenWith(audio: String): EditState {
        val (frozen, id) = TimelineEditing.freezing(EditState(listOf(TimelineClip(id = "C1", sourceID = "S1", start = 0.0, end = 6.0))), 2.0)!!
        val hold = frozen.clips[1]
        val effects = hold.videoEffects!!.map { if (it.id == id) it.with("audio", EffectValue.Choice(audio)) else it }
        return VideoEffectEditing.setEffects(frozen, mapOf(hold.id to effects)) ?: frozen
    }

    @Test fun cutLeavesTheHoldSilent() {
        val plan = TrackMix.plan(frozenWith("Cut").toTimeline())
        assertTrue(plan.pieces.none { it.start < EditTime.of(5.0) && it.end > EditTime.of(2.0) })
        assertEquals(2, plan.pieces.size)
    }

    @Test fun continuePlaysTheSoundOnFromTheHeldSecond() {
        val timeline = frozenWith("Continue").toTimeline()
        assertFalse(timeline.video[1].mapping.runs.single().silent)
        val plan = TrackMix.plan(timeline)
        val held = plan.pieces.single { it.start == EditTime.of(2.0) }
        val run = held.runs.single()
        near(2.0, run.sourceStart)
        near(5.0, run.sourceEnd, 1e-6)
        assertTrue(run.atSpeed)
        assertNull(held.envelope.endVolume)
        // The words repeat when the footage resumes: the piece after the hold starts again at second 2.
        near(2.0, plan.pieces.single { it.start == EditTime.of(5.0) }.runs.first().sourceStart)
    }

    @Test fun fadeOutOverHoldFallsToSilenceOverThePiece() {
        val plan = TrackMix.plan(frozenWith("Fade out over hold").toTimeline())
        val held = plan.pieces.single { it.start == EditTime.of(2.0) }
        assertEquals(0.0, held.envelope.endVolume!!, 0.0)
        near(0.5, held.envelope.level(3.5))
        // Mixed: a constant source is heard at half its level halfway through the hold.
        val samples = TrackMixer.mix(plan, 1000, 1) { FloatArray(20_000) { 0.5f } }
        assertEquals(0.25, samples[3500].toDouble(), 0.01)
        assertEquals(0.0, samples[4990].toDouble(), 0.01)
        assertEquals(0.5, samples[1000].toDouble(), 0.01)
    }

    @Test fun theHoldDurationIsTheFreezeFramesAndTakingItOffTakesTheHold() {
        val (frozen, id) = TimelineEditing.freezing(EditState(listOf(TimelineClip(id = "C1", sourceID = "S1", start = 0.0, end = 6.0))), 2.0)!!
        val hold = frozen.clips[1]
        val longer = VideoEffectEditing.setEffects(frozen, mapOf(hold.id to hold.videoEffects!!.map { if (it.id == id) it.with("hold", EffectValue.Number(5.0)) else it }))!!
        near(5.0, longer.clips[1].frozenFrame!!.duration)
        near(11.0, longer.duration)
        val gone = VideoEffectEditing.setEffects(frozen, mapOf(hold.id to emptyList()))!!
        assertTrue(gone.clips.none { it.frozenFrame != null })
        assertEquals(2, gone.clips.size)
        near(6.0, gone.duration)
    }

    @Test fun aHeldFrameIsSavedWithItsFieldsAndTheTimeEffectsSchema() {
        val hold = TimelineClip(id = "H", sourceID = "S1", start = 2.0, end = 2.0 + 1.0 / 30, frozenFrame = FrozenFrameSegment(2.0, 3.0),
            videoEffects = listOf(VideoEffectSettings(VideoEffectID.FreezeFrame, 2.0, 2.0 + 1.0 / 30)))
        val json = documentJson.encodeToString(TimelineClip.serializer(), hold)
        assertTrue(json, json.contains("\"frozenFrame\":{\"sourceTime\":2.0,\"duration\":3.0,\"offset\":0.0}"))
        assertEquals(hold, documentJson.decodeFromString(TimelineClip.serializer(), json))
        assertEquals(19, hold.schema)
        assertEquals(19, clip(undercranking()).schema)
        assertEquals(16, clip(VideoEffectSettings(VideoEffectID.Noir, 0.0, 6.0)).schema)

        val base = Project("Trip", id = "P", createdAt = 1).addRecording(take, now = 2)
        val (frozen, _) = TimelineEditing.freezing(base.edit, 2.0)!!
        val saved = base.commit(frozen, now = 3)
        assertEquals(19, saved.schemaVersion)
        assertEquals(19, saved.snapshots[saved.historyCursor].schema)
        // A project's number is the highest of its steps: a later step without time effects leaves it at 19.
        val plainer = saved.commit(EditState(saved.edit.clips.filter { it.frozenFrame == null }), now = 4)
        assertEquals(TrackTimeline.SCHEMA, plainer.snapshots[plainer.historyCursor].schema)
        assertEquals(19, plainer.schemaVersion)
        saved.validate()
        val files = mutableMapOf<String, ByteArray>()
        val manifest = saved.manifest { name, data -> files[name] = data }
        val reopened = Project.readManifest(manifest) { files.getValue(it) }
        assertEquals(saved.edit.clips, reopened.edit.clips)
        assertNotNull(reopened.edit.clips[1].frozenFrame)
        // A hold that is not valid is refused.
        val broken = saved.commit(EditState(saved.edit.clips.map { it.copy(frozenFrame = it.frozenFrame?.copy(duration = 20.0)) }), now = 4)
        try { broken.validate(); throw AssertionError("valid") } catch (_: ProjectException) {}
    }

    @Test fun theShownSecondHoldsWithTheCadence() {
        val sped = clip(VideoEffectSettings(VideoEffectID.Undercranking, 0.0, 6.0).with("fps", EffectValue.Number(12.0)).with("speed", EffectValue.Number(1.0)))
        val clock = VideoEffectSequenceClock.of(sped)
        assertTrue(clock.cadenced)
        // At 12 a second on 30 fps footage, frames 0 to 2 (to the boundary at 1/12) show frame 0.
        val shown = (0 until 6).map { VideoEffectTiming.shown(sped, it / 30.0, 30.0, clock).sourceTime }
        near(0.0, shown[0]); near(0.0, shown[1]); near(0.0, shown[2])
        near(3.0 / 30, shown[3])
        assertEquals(VideoEffectTiming.heldFrame(sped, 0.0, clock), VideoEffectTiming.heldFrame(sped, 2.0 / 30, clock))
        assertFalse(VideoEffectTiming.heldFrame(sped, 0.0, clock) == VideoEffectTiming.heldFrame(sped, 3.0 / 30, clock))
        // Each held frame is the first frame at or after its boundary, as the core's sample plan reads it.
        for (frame in 0 until 60) {
            val time = frame / 30.0
            val plan = VideoEffectSamplePlan.make(sped.videoEffects!!, time, 30.0, clock)!!
            near(VideoEffectTime.firstFrame(plan.times.first(), 30.0), VideoEffectTiming.shown(sped, time, 30.0, clock).sourceTime, 1e-6)
        }
        val hold = TimelineClip(id = "H", sourceID = "S1", start = 2.0, end = 2.1, frozenFrame = FrozenFrameSegment(2.0, 3.0, offset = 0.5))
        val frozenShown = VideoEffectTiming.shown(hold, 1.0, 30.0, VideoEffectSequenceClock.of(hold))
        near(2.0, frozenShown.sourceTime)
        near(1.5, frozenShown.holdTime!!)
    }
}
