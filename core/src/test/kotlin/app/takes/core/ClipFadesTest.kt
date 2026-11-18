package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fades of a clip and of a sound, saved and cut as the original saves and
 * cuts them (TrackTimeline.swift:132-151, 188-203): any length up to the
 * item's own, and a cut inside a fade leaving both pieces its level and slope.
 */
class ClipFadesTest {
    private val take = SourceMedia(id = "S1", filename = "take.mp4", duration = 20.0, recordedAt = 5)
    private val music = SourceMedia(id = "S2", filename = "music.m4a", duration = 30.0, recordedAt = 6, audioOnly = true)
    private val clip = TimelineClip(id = "C1", sourceID = "S1", start = 0.0, end = 10.0)
    private val tiny = 1e-9

    private fun faded(audio: ClipAudio, clip: TimelineClip = this.clip) = EditState(listOf(clip.copy(audio = audio)))

    /** The level the fades alone leave [time] seconds into the movie. */
    private fun level(state: EditState, time: Double): Double {
        val at = TimelineEditing.locate(state.clips, time)!!
        return TimelineEditing.soundLevel(state.clips, at.index, at.offset)
    }

    // MARK: The saved form

    @Test fun aFadeThatWasNeverCutIsWrittenAsBefore() {
        val json = documentJson.encodeToString(ClipAudio.serializer(), ClipAudio(volumeDB = -6.5, fadeIn = 1.7, fadeOut = 0.5))
        assertEquals("""{"volumeDB":-6.5,"muted":false,"fadeIn":1.7,"fadeOut":0.5,"denoise":0.0}""", json)
        assertEquals(ClipAudio(volumeDB = -6.5, fadeIn = 1.7, fadeOut = 0.5), documentJson.decodeFromString(ClipAudio.serializer(), json))
    }

    @Test fun aCutFadeSavesHowMuchOfItIsBehindTheEdge() {
        val cut = ClipAudio(fadeIn = 1.7, fadeInElapsed = 0.6, fadeOut = 2.0, fadeOutElapsed = 0.25)
        val json = documentJson.encodeToString(ClipAudio.serializer(), cut)
        assertEquals("""{"volumeDB":0.0,"muted":false,"fadeIn":1.7,"fadeOut":2.0,"denoise":0.0,"fadeInElapsed":0.6,"fadeOutElapsed":0.25}""", json)
        assertEquals(cut, documentJson.decodeFromString(ClipAudio.serializer(), json))
        assertEquals(AudioEdgeFades(AudioEdgeFade(1.7, 0.6), AudioEdgeFade(2.0, 0.25)), cut.fades)
        assertEquals(cut, ClipAudio().withFades(cut.fades))
        // No fade and a fade never cut have nothing more to save.
        assertEquals(ClipAudio(fadeIn = 1.0), ClipAudio().withFades(AudioEdgeFades(AudioEdgeFade(1.0), null)))
        assertEquals(AudioEdgeFades(), ClipAudio().fades)
    }

    /** TrackTimeline.swift:137, 1184. */
    @Test fun whatIsBehindTheEdgeIsLessThanTheFade() {
        assertTrue(ClipAudio(fadeIn = 1.0, fadeInElapsed = 0.999).valid)
        assertTrue(ClipAudio(fadeOut = 1.0, fadeOutElapsed = 0.0).valid)
        assertTrue(!ClipAudio(fadeIn = 1.0, fadeInElapsed = 1.0).valid)
        assertTrue(!ClipAudio(fadeOut = 1.0, fadeOutElapsed = -0.1).valid)
        assertTrue(!ClipAudio(fadeInElapsed = 0.5).valid)
        assertTrue(!ClipAudio(fadeIn = 1.0, fadeInElapsed = Double.NaN).valid)
        // The level keeps the original's saved range, wider than the grid it is chosen on.
        assertTrue(ClipAudio(volumeDB = -60.0).valid && ClipAudio(volumeDB = -47.3).valid && !ClipAudio(volumeDB = -60.5).valid)
    }

    // MARK: Schema

    /** Readers before schema 15 held a fade to half its item and knew nothing of a cut one: they must refuse, not play it wrongly. */
    @Test fun aLongOrCutFadeRaisesTheSchemaSoOlderReadersRefuse() {
        assertEquals(4, clip.copy(audio = ClipAudio(volumeDB = -6.5, fadeIn = 5.0, fadeOut = 5.0)).schema)
        assertEquals(15, clip.copy(audio = ClipAudio(fadeIn = 5.1)).schema)
        assertEquals(15, clip.copy(audio = ClipAudio(fadeOut = 10.0)).schema)
        assertEquals(15, clip.copy(audio = ClipAudio(fadeIn = 1.0, fadeInElapsed = 0.5)).schema)
        assertEquals(15, clip.copy(audio = ClipAudio(fadeOut = 1.0, fadeOutElapsed = 0.5)).schema)
        // Half of what the clip lasts in the movie, not of its footage.
        assertEquals(15, clip.copy(audio = ClipAudio(fadeIn = 3.0), speed = SpeedSettings(rate = 2.0)).schema)
        assertEquals(26, Project.LATEST_READABLE_SCHEMA) // the timeline and timelines that are no sequence came after, held frames and time effects after them, then the mirror (20) and the movie's shape (21), then pauses kept on the clip (22), then pictures over a clip (23), then stabilisation (24), then cards of a label and a headline (25), then effects on part of a clip or across pieces (26)

        val base = Project("Trip", id = "P", createdAt = 1).addRecording(take, now = 2).addSource(music, now = 2)
        val plain = LegacyProject.commit(base, TimelineEditing.setAudio(base.edit, 0, ClipAudio(volumeDB = -6.5, fadeIn = 1.7))!!, now = 3)
        assertEquals(4, plain.schemaVersion)
        val long = LegacyProject.commit(plain, TimelineEditing.setAudio(plain.edit, 0, ClipAudio(fadeIn = 12.0))!!, now = 4)
        assertEquals(15, long.schemaVersion)
        long.validate()
        val files = mutableMapOf<String, ByteArray>()
        val manifest = long.manifest { name, data -> files[name] = data }
        assertEquals(long.edit.clips, Project.readManifest(manifest) { files.getValue(it) }.edit.clips)
        // What a reader that knows one schema less makes of it.
        val older = manifest.decodeToString().replaceFirst("\"schemaVersion\":15", "\"schemaVersion\":${Project.LATEST_READABLE_SCHEMA + 1}")
        try { Project.readManifest(older.toByteArray()) { files.getValue(it) }; org.junit.Assert.fail("opened") } catch (refused: ProjectException) {
            assertEquals(ProjectException.Reason.NewerVersion, refused.reason)
        }

        // A split inside a fade, and a sound on an audio track, count the same.
        assertEquals(15, LegacyProject.commit(plain, TimelineEditing.split(plain.edit, 1.0)!!, now = 4).schemaVersion)
        assertEquals(4, LegacyProject.commit(plain, TimelineEditing.split(plain.edit, 5.0)!!, now = 4).schemaVersion)
        val sound = TimelineEditing.addAudio(plain.edit, "S2", 30.0, 0.0)!!
        val item = sound.audio!!.single()
        assertEquals(5, LegacyProject.commit(plain, TimelineEditing.setAudioItemSound(sound, item.id, ClipAudio(fadeOut = 15.0))!!, now = 4).schemaVersion)
        assertEquals(15, LegacyProject.commit(plain, TimelineEditing.setAudioItemSound(sound, item.id, ClipAudio(fadeOut = 15.5))!!, now = 4).schemaVersion)
        assertEquals(15, LegacyProject.commit(plain, TimelineEditing.trimAudio(TimelineEditing.setAudioItemSound(sound, item.id, ClipAudio(fadeIn = 2.0))!!, item.id, 1.0, leading = true)!!, now = 4).schemaVersion)
    }

    /** A take recorded into the middle of a fade cuts it: the step then holds a cut fade. */
    @Test fun aTakeInsertedInsideAFadeRaisesTheSchema() {
        val base = Project("Trip", id = "P", createdAt = 1).addRecording(take, now = 2)
        val faded = LegacyProject.commit(base, TimelineEditing.setAudio(base.edit, 0, ClipAudio(fadeIn = 4.0))!!, now = 3)
        val more = SourceMedia(id = "S3", filename = "more.mp4", duration = 5.0, recordedAt = 7)
        val inserted = LegacyProject.addRecording(faded.beginRecordingInsertion(2.0), more, now = 4)
        assertEquals(ClipAudio(fadeIn = 4.0, fadeInElapsed = 2.0), inserted.edit.clips[2].audio)
        assertEquals(15, inserted.schemaVersion)
        inserted.validate()
    }

    // MARK: Cuts

    /** TrackMediaTests.swift:570-573: "Splitting midway through a fade preserves its level and slope". */
    @Test fun aSplitInsideAFadeInKeepsItsLevelAndSlope() {
        val state = faded(ClipAudio(volumeDB = -6.0, fadeIn = 1.0, fadeOut = 2.0))
        val split = TimelineEditing.split(state, 0.4)!!
        assertEquals(ClipAudio(volumeDB = -6.0, fadeIn = 1.0), split.clips[0].audio)
        assertEquals(ClipAudio(volumeDB = -6.0, fadeIn = 1.0, fadeInElapsed = 0.4, fadeOut = 2.0), split.clips[1].audio)
        assertEquals(0.4, split.clips[1].audio!!.fades.fadeIn!!.gain(0.0), 0.00001)
        // The same level at every moment as before the cut, on both sides of it.
        for (time in listOf(0.0, 0.2, 0.399, 0.4, 0.401, 0.7, 1.0, 5.0, 8.5, 9.999)) assertEquals("at $time", level(state, time), level(split, time), tiny)
    }

    @Test fun aSplitInsideAFadeOutKeepsItsLevelAndSlope() {
        val state = faded(ClipAudio(fadeIn = 1.0, fadeOut = 1.7))
        val split = TimelineEditing.split(state, 9.0)!!
        assertEquals(ClipAudio(fadeIn = 1.0, fadeOut = 1.7, fadeOutElapsed = 1.0), split.clips[0].audio)
        assertEquals(ClipAudio(fadeOut = 1.7), split.clips[1].audio)
        for (time in listOf(0.5, 8.2, 8.3, 8.65, 8.999, 9.0, 9.001, 9.5, 9.99)) assertEquals("at $time", level(state, time), level(split, time), tiny)
        // Cut again inside what is left: the pieces still add up.
        val twice = TimelineEditing.split(split, 8.5)!!
        assertEquals(1.5, twice.clips[0].audio!!.fadeOutElapsed!!, tiny)
        assertEquals(ClipAudio(fadeOut = 1.7, fadeOutElapsed = 1.0), twice.clips[1].audio)
        for (time in listOf(8.2, 8.4, 8.5, 8.7, 9.0, 9.5)) assertEquals("at $time", level(state, time), level(twice, time), tiny)
    }

    /** A cut past a fade leaves the piece without it, and a piece with nothing left to say about its sound saves nothing. */
    @Test fun aSplitOutsideAFadeLeavesItWhole() {
        val split = TimelineEditing.split(faded(ClipAudio(fadeIn = 1.0)), 1.0)!!
        assertEquals(ClipAudio(fadeIn = 1.0), split.clips[0].audio)
        assertNull(split.clips[1].audio)
        val both = TimelineEditing.split(faded(ClipAudio(fadeIn = 6.0, fadeOut = 6.0)), 5.0)!!
        assertEquals(ClipAudio(fadeIn = 6.0, fadeOut = 6.0, fadeOutElapsed = 5.0), both.clips[0].audio)
        assertEquals(ClipAudio(fadeIn = 6.0, fadeInElapsed = 5.0, fadeOut = 6.0), both.clips[1].audio)
    }

    /** Fades are in seconds of the movie: on a clip played twice as fast a cut two seconds in is two seconds of fade. */
    @Test fun aSplitOfAFasterClipCountsMovieSeconds() {
        val fast = clip.copy(speed = SpeedSettings(rate = 2.0), audio = ClipAudio(fadeIn = 4.0, fadeOut = 1.0))
        val state = EditState(listOf(fast))
        val split = TimelineEditing.split(state, 2.0)!!
        assertEquals(4.0, split.clips[0].end, tiny)
        assertEquals(ClipAudio(fadeIn = 4.0, fadeInElapsed = 2.0, fadeOut = 1.0), split.clips[1].audio)
        for (time in listOf(0.5, 1.99, 2.0, 2.5, 4.5, 4.9)) assertEquals("at $time", level(state, time), level(split, time), tiny)
    }

    /** A range cuts a clip with lent footage around a short piece: the fades are cut where the clip is all the same. */
    @Test fun aRangeCutsAFadeWhereTheClipIsCut() {
        val state = faded(ClipAudio(fadeIn = 2.0, fadeOut = 2.0))
        val scoped = RangeEditing.scoped(state, EditScope.Range(TimeRange(0.1, 9.9)))
        assertEquals(3, scoped.state.clips.size)
        assertEquals(ClipAudio(fadeIn = 2.0), scoped.state.clips[0].audio)
        assertEquals(ClipAudio(fadeIn = 2.0, fadeInElapsed = 0.1, fadeOut = 2.0, fadeOutElapsed = 0.1), scoped.state.clips[1].audio!!.let {
            it.copy(fadeInElapsed = Math.round(it.fadeInElapsed!! * 1e6) / 1e6, fadeOutElapsed = Math.round(it.fadeOutElapsed!! * 1e6) / 1e6)
        })
        assertEquals(ClipAudio(fadeOut = 2.0), scoped.state.clips[2].audio)
        for (time in listOf(0.05, 0.1, 0.5, 1.9, 5.0, 8.5, 9.9, 9.95)) assertEquals("at $time", level(state, time), level(scoped.state, time), 1e-6)
        // Deleting the middle leaves the two ends, each with its own fade.
        val removed = RangeEditing.delete(state, EditScope.Range(TimeRange(1.0, 9.0)))!!.state
        assertEquals(listOf(ClipAudio(fadeIn = 2.0), ClipAudio(fadeOut = 2.0)), removed.clips.map { it.audio })
        assertEquals(0.5, level(removed, 1.0), 1e-6)   // the second piece goes on from half way down
        assertEquals(0.5, level(removed, 0.999), 1e-3) // where the first had come half way up
    }

    // MARK: Trims

    /** A trim is a cut whose other side is removed (EditorView.swift:1241-1257); extending gives the fade back (TrackTimeline.swift:1650-1655). */
    @Test fun aTrimIntoAFadeLeavesTheRestOfItAndExtendingGivesItBack() {
        val state = faded(ClipAudio(fadeIn = 2.0, fadeOut = 3.0))
        val head = TimelineEditing.trim(state, 0, 0.5, 10.0, 20.0)!!
        assertEquals(ClipAudio(fadeIn = 2.0, fadeInElapsed = 0.5, fadeOut = 3.0), head.clips[0].audio)
        assertEquals(0.25, level(head, 0.0), tiny)
        assertEquals(1.0, level(head, 1.5), tiny)
        // The head of the first clip has no room before it: extending reveals nothing there (TrackTimeline.swift:1636).
        assertNull(TimelineEditing.trim(head, 0, 0.2, 10.0, 20.0))
        // After another clip it reveals over that one, and the fade comes back from where it was cut.
        val after = EditState(listOf(TimelineClip(id = "C0", sourceID = "S1", start = 10.0, end = 12.0), clip.copy(audio = ClipAudio(fadeIn = 2.0, fadeOut = 3.0))))
        val cut = TimelineEditing.trim(after, 1, 0.5, 10.0, 20.0)!!
        val back = TimelineEditing.trim(cut, 1, 0.2, 10.0, 20.0)!!
        assertEquals(0.2, back.clips.first { it.id == clip.id }.audio!!.fadeInElapsed!!, tiny)
        val further = TimelineEditing.trim(back, back.clips.indexOfFirst { it.id == clip.id }, 0.0, 10.0, 20.0)!!
        assertEquals(ClipAudio(fadeIn = 2.0, fadeOut = 3.0), further.clips.first { it.id == clip.id }.audio)

        val tail = TimelineEditing.trim(state, 0, 0.0, 8.5, 20.0)!!
        assertEquals(ClipAudio(fadeIn = 2.0, fadeOut = 3.0, fadeOutElapsed = 1.5), tail.clips[0].audio)
        assertEquals(0.5, level(tail, 8.4999999), 1e-6)
        // Extended past where the fade was cut, it ends in silence again and no later.
        val longer = TimelineEditing.trim(tail, 0, 0.0, 12.0, 20.0)!!
        assertEquals(ClipAudio(fadeIn = 2.0, fadeOut = 3.0), longer.clips[0].audio)
        // Trimmed past the whole fade, the fade is gone.
        assertNull(TimelineEditing.trim(faded(ClipAudio(fadeIn = 2.0)), 0, 2.0, 10.0, 20.0)!!.clips[0].audio)
        // A clip without fades is trimmed as before.
        assertEquals(ClipAudio(volumeDB = -3.0), TimelineEditing.trim(faded(ClipAudio(volumeDB = -3.0)), 0, 2.0, 10.0, 20.0)!!.clips[0].audio)
    }

    @Test fun aSoundOnAnAudioTrackIsCutTheSameWay() {
        val item = AudioItem(id = "A", sourceID = "S2", start = 2.0, sourceStart = 0.0, sourceEnd = 10.0, audio = ClipAudio(volumeDB = 3.0, fadeIn = 1.7, fadeOut = 4.0))
        val state = EditState(listOf(clip.copy(end = 20.0)), audio = listOf(item))
        val leading = TimelineEditing.trimAudio(state, "A", 3.0, leading = true)!!.audio!!.single()
        assertEquals(ClipAudio(volumeDB = 3.0, fadeIn = 1.7, fadeInElapsed = 1.0, fadeOut = 4.0), leading.audio)
        assertEquals(TimelineEditing.soundLevel(item, 1.2), TimelineEditing.soundLevel(leading, 0.2), tiny)
        val trailing = TimelineEditing.trimAudio(state, "A", 10.0, leading = false)!!.audio!!.single()
        assertEquals(ClipAudio(volumeDB = 3.0, fadeIn = 1.7, fadeOut = 4.0, fadeOutElapsed = 2.0), trailing.audio)
        assertEquals(TimelineEditing.soundLevel(item, 7.5), TimelineEditing.soundLevel(trailing, 7.5), tiny)

        // A range deleted out of its middle leaves two pieces that go on as one did.
        val carved = RangeEditing.delete(state, EditScope.Range(TimeRange(2.5, 9.0), EditTargets(audio = setOf("A"))))!!.state.audio!!
        assertEquals(ClipAudio(volumeDB = 3.0, fadeIn = 1.7), carved[0].audio)
        assertEquals(ClipAudio(volumeDB = 3.0, fadeOut = 4.0), carved[1].audio)
        val inside = RangeEditing.delete(state, EditScope.Range(TimeRange(2.0, 3.0), EditTargets(audio = setOf("A"))))!!.state.audio!!.single()
        assertEquals(1.0, inside.audio!!.fadeInElapsed!!, tiny)
    }

    // MARK: Changes of length

    /** TrackTimeline.swift:144-149, 1282-1283: a fade is squeezed into an item made shorter than it, never stretched. */
    @Test fun aFasterClipSqueezesAFadeThatNoLongerFits() {
        val state = faded(ClipAudio(fadeIn = 8.0, fadeOut = 1.0))
        val fast = TimelineEditing.setSpeed(state, 0, 2.0)!!.clips[0]
        assertEquals(5.0, fast.duration, tiny)
        assertEquals(ClipAudio(fadeIn = 5.0, fadeOut = 1.0), fast.audio)
        // One that still fits keeps its length, and slowing down gives nothing back.
        val slow = TimelineEditing.setSpeed(EditState(listOf(fast)), 0, 0.5)!!.clips[0]
        assertEquals(ClipAudio(fadeIn = 5.0, fadeOut = 1.0), slow.audio)
        assertEquals(ClipAudio(fadeIn = 2.0), TimelineEditing.setSpeed(faded(ClipAudio(fadeIn = 2.0)), 0, 2.0)!!.clips[0].audio)
    }

    /** A clip without its pauses is one item in the original: its fades run on across the pieces that are left. */
    @Test fun removingPausesKeepsTheFadesOfTheWholeClip() {
        val state = faded(ClipAudio(fadeIn = 3.0, fadeOut = 2.0))
        val cut = TimelineEditing.removeIntervals(state, 0, listOf(SourceInterval(2.0, 4.0), SourceInterval(6.0, 7.0)))!!
        assertEquals(listOf(2.0, 2.0, 3.0), cut.clips.map { it.duration })
        assertEquals(ClipAudio(fadeIn = 3.0), cut.clips[0].audio)
        assertEquals(ClipAudio(fadeIn = 3.0, fadeInElapsed = 2.0), cut.clips[1].audio)
        assertEquals(ClipAudio(fadeOut = 2.0), cut.clips[2].audio)
        // One fade in over the first three seconds of what is left, one fade out over its last two.
        for ((time, expected) in listOf(0.0 to 0.0, 1.5 to 0.5, 2.0 to 2.0 / 3, 2.5 to 2.5 / 3, 3.0 to 1.0, 4.5 to 1.0, 5.5 to 0.75, 6.5 to 0.25)) {
            assertEquals("at $time", expected, level(cut, time), 1e-6)
        }
        assertNotNull(cut.clips[1].audio!!.fadeInElapsed)
    }
}
