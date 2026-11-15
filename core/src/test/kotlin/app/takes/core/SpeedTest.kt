package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTest {
    private val clip = TimelineClip(sourceID = "A", start = 0.0, end = 4.0)

    // Translated from VideoToolsTests.swift ("Tool persistence and timing"), reduced to the
    // mapping the original's speed-aware split and ripple removal are built on.

    @Test fun speedAwareSplitUsesSourceCoordinatesAndPreservesDuration() {
        val spans = SpeedMapping.spans(clip, SpeedSettings(rate = 2.0))
        assertEquals(2.0, SpeedMapping.duration(spans), 0.00001)
        // Splitting at 0.8 s on the timeline cuts the source at 1.6 s.
        val cut = SpeedMapping.sourceTime(0.8, spans, clip.end)
        assertEquals(1.6, cut, 0.00001)
        val left = SpeedMapping.spans(clip.copy(end = cut), SpeedSettings(rate = 2.0))
        val right = SpeedMapping.spans(clip.copy(start = cut), SpeedSettings(rate = 2.0))
        assertEquals(2.0, SpeedMapping.duration(left) + SpeedMapping.duration(right), 0.00001)
    }

    @Test fun rippleRemovalMapsThroughSpeed() {
        val spans = SpeedMapping.spans(clip, SpeedSettings(rate = 2.0))
        // Removing 0.5–1.5 s of the timeline removes source 1–3 and leaves one second.
        val from = SpeedMapping.sourceTime(0.5, spans, clip.end)
        val to = SpeedMapping.sourceTime(1.5, spans, clip.end)
        assertEquals(1.0, from, 0.0)
        assertEquals(3.0, to, 0.0)
        assertEquals(1.0, SpeedMapping.duration(SpeedMapping.spans(clip, SpeedSettings(rate = 2.0), listOf(SourceInterval(from, to)))), 0.0)
    }

    @Test fun exportDurationMatchesTheTimeline() {
        for (rate in listOf(0.5, 2.0)) assertEquals(4 / rate, SpeedMapping.duration(SpeedMapping.spans(clip, SpeedSettings(rate = rate))), 0.0)
    }

    // Own tests.

    @Test fun noSpeedPlaysAsRecorded() {
        val trimmed = clip.copy(start = 1.0, end = 3.0)
        val spans = SpeedMapping.spans(trimmed, null)
        assertEquals(listOf(EffectPlaybackSpan(1.0, 3.0, 1.0)), spans)
        assertEquals(2.5, SpeedMapping.sourceTime(1.5, spans, trimmed.end), 0.0)
        assertEquals(1.5, SpeedMapping.timelineTime(2.5, spans), 0.0)
    }

    @Test fun sourceAndTimelineTimeAreInverses() {
        for (rate in listOf(0.25, 0.5, 1.0, 1.5, 4.0)) {
            val trimmed = clip.copy(start = 0.5, end = 3.5)
            val spans = SpeedMapping.spans(trimmed, SpeedSettings(rate = rate))
            for (offset in listOf(0.0, 0.1, 0.37, 3.0 / rate)) {
                assertEquals(offset, SpeedMapping.timelineTime(SpeedMapping.sourceTime(offset, spans, trimmed.end), spans), 1e-9)
            }
        }
    }

    @Test fun timesOutsideTheClipStayAtItsEnds() {
        val trimmed = clip.copy(start = 1.0, end = 3.0)
        val spans = SpeedMapping.spans(trimmed, SpeedSettings(rate = 2.0))
        assertEquals(1.0, SpeedMapping.sourceTime(-5.0, spans, trimmed.end), 0.0)
        assertEquals(3.0, SpeedMapping.sourceTime(50.0, spans, trimmed.end), 0.0)
        assertEquals(0.0, SpeedMapping.timelineTime(0.2, spans), 0.0)
        assertEquals(1.0, SpeedMapping.timelineTime(3.9, spans), 0.0)
        assertEquals(3.0, SpeedMapping.sourceTime(0.5, emptyList(), trimmed.end), 0.0)
    }

    @Test fun removedFootageIsSkippedAtSpeed() {
        // Source 1–2 is cut; the rest plays at 2×: 0–1 takes 0.5 s, 2–4 takes 1 s.
        val spans = SpeedMapping.spans(clip, SpeedSettings(rate = 2.0), listOf(SourceInterval(1.0, 2.0)))
        assertEquals(listOf(EffectPlaybackSpan(0.0, 1.0, 2.0), EffectPlaybackSpan(2.0, 4.0, 2.0)), spans)
        assertEquals(1.5, SpeedMapping.duration(spans), 0.0)
        assertEquals(0.5, SpeedMapping.sourceTime(0.25, spans, clip.end), 0.0)
        // The join belongs to the footage after it.
        assertEquals(2.0, SpeedMapping.sourceTime(0.5, spans, clip.end), 0.0)
        assertEquals(3.0, SpeedMapping.sourceTime(1.0, spans, clip.end), 0.0)
        // A removed second maps to where it was cut.
        assertEquals(0.5, SpeedMapping.timelineTime(1.5, spans), 0.0)
        assertEquals(1.0, SpeedMapping.timelineTime(3.0, spans), 0.0)
    }

    @Test fun heldFrameMapsEverythingToOneSourceSecond() {
        val hold = FrozenFrameSegment(sourceTime = 1.5, duration = 2.0)
        assertEquals(1.5, SpeedMapping.sourceTime(hold), 0.0)
        assertEquals(0.0, SpeedMapping.timelineTime(1.5, hold), 0.0)
        assertEquals(2.0, SpeedMapping.timelineTime(1.6, hold), 0.0)
    }

    @Test fun ratesAreLimited() {
        assertTrue(SpeedSettings(rate = 0.25).valid && SpeedSettings(rate = 4.0).valid)
        assertFalse(SpeedSettings(rate = 0.24).valid)
        assertFalse(SpeedSettings(rate = 4.01).valid)
        assertFalse(SpeedSettings(rate = Double.NaN).valid)
        // Playback tolerates more than one setting may ask for, for speed effects on top.
        assertEquals(6.0, SpeedMapping.rate(SpeedSettings(rate = 4.0), sequenceRate = 2.0), 0.0)
        assertEquals(0.125, SpeedMapping.rate(SpeedSettings(rate = 0.25), sequenceRate = 0.25), 0.0)
        assertEquals(1.0, SpeedMapping.rate(null), 0.0)
    }

    @Test fun speedWritesTheOriginalFields() {
        val text = documentJson.encodeToString(SpeedSettings.serializer(), SpeedSettings(id = "A", rate = 2.0))
        assertEquals("""{"id":"A","rate":2.0}""", text)
        assertEquals(SpeedSettings("A", 2.0), documentJson.decodeFromString(SpeedSettings.serializer(), """{"id":"A","rate":2}"""))
    }

    // Own tests: speed as a setting on a timeline clip.
    @Test fun aFasterClipTakesLessTimeAndKeepsItsFootage() {
        val a = TimelineClip(sourceID = "A", start = 2.0, end = 10.0)
        val b = TimelineClip(sourceID = "B", start = 0.0, end = 4.0)
        val state = EditState(listOf(a, b))
        val fast = TimelineEditing.setSpeed(state, 0, 2.0)!!
        assertEquals(4.0, fast.clips[0].duration, 0.0)
        assertEquals(8.0, fast.duration, 0.0)
        assertEquals(4.0, TimelineEditing.clipStart(fast.clips, 1), 0.0)
        assertEquals(2.0 to 10.0, fast.clips[0].let { it.start to it.end })     // the source range is untouched
        assertEquals(6.0, fast.clips[0].sourceTime(2.0), 0.0)
        assertEquals(2.0, fast.clips[0].offset(6.0), 0.0)
        val slow = TimelineEditing.setSpeed(state, 0, 0.5)!!
        assertEquals(16.0, slow.clips[0].duration, 0.0)
        assertNull(TimelineEditing.setSpeed(state, 0, 1.0))                       // already normal
        assertNull(TimelineEditing.setSpeed(state, 0, 5.0))                       // outside the limits
        assertNull(TimelineEditing.setSpeed(fast, 0, 2.0))
        assertNull(TimelineEditing.setSpeed(fast, 0, 1.0)!!.clips[0].speed)
        assertEquals(fast.clips[0].speed!!.id, TimelineEditing.setSpeed(fast, 0, 3.0)!!.clips[0].speed!!.id)
    }

    @Test fun editsOnAFastClipLandOnTheRightFootage() {
        val clip = TimelineClip(sourceID = "A", start = 2.0, end = 10.0)
        var state = TimelineEditing.setSpeed(EditState(listOf(clip)), 0, 2.0)!!
        state = TimelineEditing.setText(state, 0, TextOverlay(text = "T", start = 6.0, end = 8.0))!!
        state = TimelineEditing.toggleBookmark(state, 0, 9.0)!!
        // Source 6–8 shows from timeline 2.0 to 3.0; the mark at source 9 shows at 3.5.
        assertEquals(1, TimelineEditing.titlesAt(state.clips, 2.5).size)
        assertEquals(0, TimelineEditing.titlesAt(state.clips, 1.9).size)
        assertEquals(listOf(3.5), TimelineEditing.bookmarkTimes(state.clips))
        // A split one second in cuts the source at 4, and both pieces keep the speed.
        val split = TimelineEditing.split(state, 1.0)!!.clips
        assertEquals(listOf(2.0 to 4.0, 4.0 to 10.0), split.map { it.start to it.end })
        assertEquals(listOf(1.0, 3.0), split.map { it.duration })
        // Removing two source seconds from a 2x clip shortens it by one.
        val cut = TimelineEditing.removeIntervals(state, 0, listOf(SourceInterval(4.0, 6.0)))!!
        assertEquals(3.0, cut.duration, 0.0)
        var project = Project("x").addRecording(SourceMedia(filename = "a.mp4", duration = 10.0))
        project = LegacyProject.commit(project, TimelineEditing.setSpeed(project.edit, 0, 0.5)!!)
        assertEquals(9, project.schemaVersion)
        assertEquals(20.0, project.edit.duration, 0.0)
        project.validate()
        val files = mutableMapOf<String, ByteArray>()
        val manifest = project.manifest { name, data -> files[name] = data }
        assertEquals(project, Project.readManifest(manifest) { files.getValue(it) })
    }
}
