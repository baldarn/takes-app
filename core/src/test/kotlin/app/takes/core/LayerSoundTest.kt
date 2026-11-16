package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sound of an edit drawn by picture layers ([LayerSound]): each picture's own, on its lane, and the sound tracks. */
class LayerSoundTest {
    /** [timeline] with a copy of its first picture as [id], from [start] for [seconds], above it and with no sound of its own. */
    private fun overlaid(timeline: TrackTimeline, id: String, start: Double, seconds: Double): TrackTimeline {
        val content = timeline.video.first().content.copy(id = id, start = 1.0, end = 1.0 + seconds)
        return timeline.copy(video = timeline.video + VideoTimelineItem.of("occurrence-$id", EditTime.of(start), content, 10))
    }

    private fun layered(state: EditState): Pair<EditState, LayerSound> {
        val render = EditRender.of(state, 3) as EditRender.Layers
        return state to LayerSound(render.plan, state.clips, state.audio.orEmpty())
    }

    @Test fun eachPictureSoundsOnItsLaneAndAPictureWithoutSoundIsSilent() {
        val faded = TrackFixtures.plain().let { it.copy(clips = it.clips.mapIndexed { index, clip -> if (index == 0) clip.copy(audio = ClipAudio(volumeDB = -6.0)) else clip }) }
        val (state, sound) = layered(overlaid(faded.toTimeline(), "O", 2.0, 3.0).toEdit())
        assertTrue(!state.isSequence)
        val plan = sound.plan
        val overlay = plan.pictures.single { it.id == "O" }
        assertEquals(1, overlay.lane)
        val voices = sound.voices { true }
        assertEquals("The overlay has no sound of its own", listOf(0), voices.map { it.number })
        val heard = voices.single().entries.filterIsInstance<RenderPlan.Entry.Clip>().map { it.index }
        assertEquals(plan.pictures.filter { it.lane == 0 }.map { it.index }, heard)
        assertEquals(0.0, sound.laneLevel(1, 3.0), 0.0)
        // The first picture plays at its own level, the others at theirs.
        val first = plan.pictures.first { it.lane == 0 && it.start == 0.0 }
        assertEquals(0.5012, sound.laneLevel(0, 3.0), 0.001)
        assertEquals(1.0, sound.laneLevel(0, first.end + 1.0), 1e-9)
        val envelope = sound.clipEnvelope(first.index)!!
        assertEquals(first.start, envelope.start, 1e-9)
        assertEquals(first.end, envelope.end, 1e-6)
        assertNull(sound.clipEnvelope(overlay.index))
        // Every voice entry plays where its picture does.
        var at = 0.0
        for (entry in voices.single().entries) {
            when (entry) {
                is RenderPlan.Entry.Gap -> at += entry.micros / 1e6
                is RenderPlan.Entry.Clip -> { assertEquals(plan.pictures[entry.index].start, at, 1e-6); at = plan.pictures[entry.index].end }
                else -> error("Only clips and gaps")
            }
        }
        assertEquals(plan.duration, at, 1e-6)
    }

    @Test fun theSoundTracksPlayAsForASequenceAndEndWithThePicture() {
        val music = AudioItem(sourceID = TrackFixtures.music.id, start = 1.0, sourceStart = 0.0, sourceEnd = 100.0)
        val (state, sound) = layered(overlaid(TrackFixtures.plain().copy(audio = listOf(music)).toTimeline(), "O", 2.0, 3.0).toEdit())
        assertEquals(1, state.audio.orEmpty().size)
        val track = sound.soundtracks.single()
        val item = track.entries.filterIsInstance<RenderPlan.Entry.Sound>().single()
        assertEquals(sound.plan.duration - 1.0, item.seconds, 1e-6)
        assertTrue(sound.trackLevel(0, 2.0) > 0.99)
        assertEquals(0.0, sound.trackLevel(0, 0.5), 0.0)
    }
}
