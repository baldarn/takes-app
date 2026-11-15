package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleEditingTest {
    private val take = SourceMedia(id = "A", filename = "a.mp4", duration = 20.0)
    private val other = SourceMedia(id = "B", filename = "b.mp4", duration = 6.0)
    private val project = Project("x").addRecording(take).addRecording(other)

    private fun word(text: String, start: Double, end: Double) = SubtitleWord(text = text, start = start, end = end)

    // Two sentences in A (1–3 s and 10–12 s), one in B (1–2 s).
    private val heard = mapOf(
        "A" to listOf(word("Good", 1.0, 1.5), word("morning", 1.5, 2.2), word("everyone.", 2.2, 3.0), word("See", 10.0, 10.6), word("you", 10.6, 11.2), word("soon.", 11.2, 12.0)),
        "B" to listOf(word("Hello", 1.0, 1.5), word("there.", 1.5, 2.0)),
    )
    private val transcribed = SubtitleEditing.install(project.edit, heard, "English")

    private fun shown(state: EditState) = SubtitleEditing.cues(state).map { Triple(it.text, it.start, it.end) }
    private fun assertShown(expected: List<Triple<String, Double, Double>>, state: EditState) {
        val actual = shown(state)
        assertEquals(expected.map { it.first }, actual.map { it.first })
        expected.zip(actual).forEach { (want, got) ->
            assertEquals(want.second, got.second, 1e-9)
            assertEquals(want.third, got.third, 1e-9)
        }
    }

    // MARK: Storage

    @Test fun aProjectWithoutSubtitlesKeepsItsSchemaAndWritesNothingNew() {
        val cut = project.commit(TimelineEditing.split(project.edit, 4.0)!!)
        assertEquals(1, cut.schemaVersion)
        val files = mutableListOf<String>()
        val manifest = cut.manifest { _, data -> files += data.decodeToString() }.decodeToString()
        assertFalse((files + manifest).any { "subtitle" in it })
    }

    @Test fun subtitlesRaiseTheSchemaSoOlderReadersRefuse() {
        val saved = project.commit(transcribed)
        assertEquals(11, saved.schemaVersion)
        assertEquals(Project.LATEST_READABLE_SCHEMA, saved.schemaVersion)
        // A reader that stops at 10, as before this feature, refuses it as it refuses any newer schema.
        assertThrows(ProjectException::class.java) { saved.copy(schemaVersion = Project.LATEST_READABLE_SCHEMA + 1).validate() }
        assertEquals(11, transcribed.clips[0].schema)
        assertEquals(1, transcribed.clips[0].copy(subtitleWords = null).schema)
        // Settings alone, with every caption deleted, are still something an old reader would drop.
        val empty = transcribed.copy(clips = transcribed.clips.map { it.copy(subtitleWords = null) })
        assertEquals(11, project.commit(empty).schemaVersion)
    }

    @Test fun subtitlesSurviveSavingAndReading() {
        val saved = project.commit(transcribed)
        val files = mutableMapOf<String, ByteArray>()
        val manifest = saved.manifest { name, data -> files[name] = data }
        val read = Project.readManifest(manifest) { files.getValue(it) }
        assertEquals(saved.edit, read.edit)
        assertEquals(shown(saved.edit), shown(read.edit))
        assertEquals("English", read.edit.subtitles?.language)
        assertTrue("subtitleWords" in files.values.last().decodeToString())
    }

    @Test fun transcribingIsOneUndoStep() {
        val saved = project.commit(transcribed)
        assertEquals(project.historyCursor + 1, saved.historyCursor)
        val undone = saved.undo()
        assertNull(undone.edit.subtitles)
        assertTrue(SubtitleEditing.cues(undone.edit).isEmpty())
        assertTrue(undone.edit.clips.all { it.subtitleWords == null })
        assertEquals(shown(saved.edit), shown(undone.redo().edit))
    }

    @Test fun aStyleChangeAloneIsAnUndoStep() {
        val saved = project.commit(transcribed)
        val styled = SubtitleEditing.restyle(saved.edit, saved.edit.subtitles!!.copy(style = SubtitleSettings.Style.Highlight, color = SubtitleSettings.Color.Yellow))!!
        val next = saved.commit(styled)
        assertEquals(saved.historyCursor + 1, next.historyCursor)
        assertEquals(SubtitleSettings.Style.Highlight, next.edit.subtitles?.style)
        assertEquals(SubtitleSettings.Style.Box, next.undo().edit.subtitles?.style)
        // The same settings again change nothing.
        assertEquals(next, next.commit(next.edit))
        assertNull(SubtitleEditing.restyle(next.edit, next.edit.subtitles!!))
        assertNull(SubtitleEditing.restyle(next.edit, next.edit.subtitles!!.copy(size = 0.9)))
        assertNull(SubtitleEditing.restyle(project.edit, SubtitleSettings()))
    }

    @Test fun aTakeRecordedLaterKeepsTheSubtitles() {
        val saved = project.commit(transcribed)
        val more = saved.addRecording(SourceMedia(id = "C", filename = "c.mp4", duration = 3.0))
        assertEquals(3, more.edit.clips.size)
        assertEquals(shown(saved.edit), shown(more.edit))
        val inserted = saved.beginRecordingInsertion(0.0).addRecording(SourceMedia(id = "D", filename = "d.mp4", duration = 3.0))
        assertEquals("English", inserted.edit.subtitles?.language)
        assertEquals(shown(saved.edit).map { it.first }, shown(inserted.edit).map { it.first })
        assertEquals(4.0, shown(inserted.edit)[0].second, 1e-9) // moved three seconds later with its footage
    }

    @Test fun invalidSubtitlesAreRefused() {
        fun rejected(state: EditState) {
            val saved = project.commit(transcribed)
            val broken = saved.copy(snapshots = saved.snapshots.dropLast(1) + EditSnapshot(state.copy(captureBase = null), saved.captureLedger.size))
            assertThrows(ProjectException::class.java) { broken.validate() }
        }
        fun withWords(change: (List<SubtitleWord>) -> List<SubtitleWord>) =
            transcribed.copy(clips = transcribed.clips.mapIndexed { index, clip -> if (index == 0) clip.copy(subtitleWords = change(clip.subtitleWords!!)) else clip })
        project.commit(transcribed).validate()
        rejected(withWords { it + it[0] })                                   // the same id twice
        rejected(withWords { it + word("late", 19.0, 21.0) })                // past the end of the recording
        rejected(withWords { it + word("backwards", 3.0, 2.0) })
        rejected(withWords { it + word("nowhere", Double.NaN, 2.0) })
        rejected(withWords { it + word("", 5.0, 6.0) })
        rejected(withWords { it + word("x".repeat(4001), 5.0, 6.0) })
        rejected(transcribed.copy(subtitles = transcribed.subtitles!!.copy(maxWords = 0)))
        rejected(transcribed.copy(subtitles = transcribed.subtitles!!.copy(y = 1.5)))
    }

    // MARK: Captions follow their footage

    @Test fun captionsAreWhereTheWordsAreSaid() {
        assertShown(listOf(Triple("Good morning everyone.", 1.0, 3.0), Triple("See you soon.", 10.0, 12.0), Triple("Hello there.", 21.0, 22.0)), transcribed)
    }

    @Test fun aSplitKeepsEveryCaptionAndCutsTheOneUnderIt() {
        val between = TimelineEditing.split(transcribed, 5.0)!!
        assertShown(shown(transcribed), between)
        // Through "morning": each piece shows the words it plays.
        val through = TimelineEditing.split(transcribed, 1.8)!!
        assertShown(listOf(Triple("Good morning", 1.0, 1.8), Triple("morning everyone.", 1.8, 3.0), Triple("See you soon.", 10.0, 12.0), Triple("Hello there.", 21.0, 22.0)), through)
    }

    @Test fun aTrimMovesLaterCaptionsAndHidesWhatIsCutOff() {
        // Two seconds off the front: the first sentence loses its first second and a half.
        val front = TimelineEditing.trim(transcribed, 0, 2.0, 20.0, 20.0)!!
        assertShown(listOf(Triple("morning everyone.", 0.0, 1.0), Triple("See you soon.", 8.0, 10.0), Triple("Hello there.", 19.0, 20.0)), front)
        // Off the end: the second sentence goes, and comes back when the trim is undone by widening again.
        val end = TimelineEditing.trim(transcribed, 0, 0.0, 8.0, 20.0)!!
        assertShown(listOf(Triple("Good morning everyone.", 1.0, 3.0), Triple("Hello there.", 9.0, 10.0)), end)
        assertShown(shown(transcribed), TimelineEditing.trim(end, 0, 0.0, 20.0, 20.0)!!)
    }

    @Test fun movingAndDeletingClipsTakesTheirCaptionsAlong() {
        val moved = TimelineEditing.move(transcribed, 1, 0)!!
        assertShown(listOf(Triple("Hello there.", 1.0, 2.0), Triple("Good morning everyone.", 7.0, 9.0), Triple("See you soon.", 16.0, 18.0)), moved)
        assertShown(listOf(Triple("Hello there.", 1.0, 2.0)), TimelineEditing.delete(transcribed, 0)!!)
    }

    @Test fun aFasterClipShowsItsCaptionsSoonerAndShorter() {
        val fast = TimelineEditing.setSpeed(transcribed, 0, 2.0)!!
        assertShown(listOf(Triple("Good morning everyone.", 0.5, 1.5), Triple("See you soon.", 5.0, 6.0), Triple("Hello there.", 11.0, 12.0)), fast)
        val slow = TimelineEditing.setSpeed(TimelineEditing.trim(transcribed, 0, 1.0, 20.0, 20.0)!!, 0, 0.5)!!
        assertShown(listOf(Triple("Good morning everyone.", 0.0, 4.0), Triple("See you soon.", 18.0, 22.0), Triple("Hello there.", 39.0, 40.0)), slow)
    }

    @Test fun aMutedClipShowsNoCaptions() {
        val muted = TimelineEditing.setAudio(transcribed, 0, ClipAudio(muted = true))!!
        assertShown(listOf(Triple("Hello there.", 21.0, 22.0)), muted)
        // The words are still there when the sound comes back.
        assertShown(shown(transcribed), TimelineEditing.setAudio(muted, 0, ClipAudio())!!)
    }

    @Test fun pausesCutOutLeaveTheCaptionsOfWhatStays() {
        val cut = TimelineEditing.removeIntervals(transcribed, 0, listOf(SourceInterval(3.5, 9.5)))!!
        assertShown(listOf(Triple("Good morning everyone.", 1.0, 3.0), Triple("See you soon.", 4.0, 6.0), Triple("Hello there.", 15.0, 16.0)), cut)
    }

    @Test fun captionsOfBothClipsShowDuringADissolve() {
        // B's sentence at 1–2 s of B; a 2 s dissolve pulls B two seconds under the end of A.
        val late = SubtitleEditing.install(project.edit, mapOf("A" to listOf(word("Goodbye.", 18.5, 19.5)), "B" to heard.getValue("B")), "English")
        val joined = TimelineEditing.setTransition(late, 0, 2.0, ClipTransition.Style.Dissolve)!!
        val cues = SubtitleEditing.cues(joined)
        assertEquals(listOf("Goodbye.", "Hello there."), cues.map { it.text })
        assertEquals(19.0, cues[1].start, 1e-9)
        assertEquals(listOf("Goodbye.", "Hello there."), SubtitleEditing.active(cues, 19.2).map { it.text })
        assertEquals(listOf("Goodbye."), SubtitleEditing.active(cues, 18.6).map { it.text })
        assertEquals(listOf("Hello there."), SubtitleEditing.active(cues, 19.5).map { it.text }) // the end is not included
        assertTrue(SubtitleEditing.active(cues, 5.0).isEmpty())
    }

    @Test fun noSettingsMeansNoCaptions() {
        assertTrue(SubtitleEditing.cues(transcribed.copy(subtitles = null)).isEmpty())
        assertEquals("Good morning everyone. See you soon. Hello there.", SubtitleEditing.transcript(transcribed))
    }

    // MARK: Transcription

    @Test fun footageIsListenedToOnceAndInOnePiece() {
        val a = TimelineClip(sourceID = "A", start = 0.0, end = 5.0)
        val clips = listOf(
            TimelineClip(sourceID = "B", start = 1.0, end = 3.0), a, a.copy(id = newID(), start = 5.0, end = 9.0),   // a split
            a.copy(id = newID(), start = 2.0, end = 4.0),                                                            // the same footage again
            a.copy(id = newID(), start = 12.0, end = 15.0), a.copy(id = newID(), start = 16.0, end = 18.0, audio = ClipAudio(muted = true)),
        )
        assertEquals(
            listOf(SubtitleStretch("B", 1.0, 3.0), SubtitleStretch("A", 0.0, 9.0), SubtitleStretch("A", 12.0, 15.0)),
            SubtitleEditing.stretches(clips),
        )
        assertTrue(SubtitleEditing.stretches(emptyList()).isEmpty())
    }

    @Test fun eachClipOwnsTheWordsItPlays() {
        val first = TimelineEditing.split(project.edit, 1.8)!!                       // through "morning" (1.5–2.2): its middle is in the second piece
        val state = first.copy(clips = first.clips + first.clips[0].copy(id = newID()))   // and the first piece once more
        val installed = SubtitleEditing.install(state, heard, "Italian")
        assertEquals(listOf("Good"), installed.clips[0].subtitleWords!!.map { it.text })
        assertEquals(listOf("morning", "everyone.", "See", "you", "soon."), installed.clips[1].subtitleWords!!.map { it.text })
        assertEquals(1.8, installed.clips[1].subtitleWords!![0].start, 0.0)         // cut at the clip's edge
        assertEquals(listOf("Good"), installed.clips[3].subtitleWords!!.map { it.text })
        // Copies, not the same word twice.
        assertNotEquals(installed.clips[0].subtitleWords!![0].id, installed.clips[3].subtitleWords!![0].id)
        val corrected = SubtitleEditing.correct(installed, SubtitleEditing.cues(installed).first { it.audioID == installed.clips[3].id }, "Well")!!
        assertEquals(listOf("Good"), corrected.clips[0].subtitleWords!!.map { it.text })
        assertEquals(listOf("Well"), corrected.clips[3].subtitleWords!!.map { it.text })
        assertEquals("Italian", installed.subtitles?.language)
        project.commit(installed).validate()
    }

    @Test fun transcribingAgainReplacesEverythingAndKeepsTheLook() {
        val styled = SubtitleEditing.restyle(transcribed, transcribed.subtitles!!.copy(color = SubtitleSettings.Color.Mint, maxWords = 2))!!
        val again = SubtitleEditing.install(styled, mapOf("A" to listOf(word("One", 1.0, 1.4), word("two", 1.4, 1.8), word("three", 1.8, 2.2))), "Auto")
        assertEquals(listOf("One two", "three"), SubtitleEditing.cues(again).map { it.text })
        assertNull(again.clips[1].subtitleWords)
        assertEquals(SubtitleSettings.Color.Mint, again.subtitles?.color)
        assertEquals(styled.subtitles?.id, again.subtitles?.id)
    }

    @Test fun mutedClipsAndUnknownRecordingsGetNoWords() {
        val muted = TimelineEditing.setAudio(project.edit, 1, ClipAudio(muted = true))!!
        val installed = SubtitleEditing.install(muted, heard + ("Z" to listOf(word("ghost", 0.0, 1.0))), "Auto")
        assertNull(installed.clips[1].subtitleWords)
        assertEquals(6, installed.clips[0].subtitleWords!!.size)
    }

    // MARK: Editing captions

    @Test fun groupingFollowsTheWordLimitUntilTheFootageChanges() {
        assertTrue(SubtitleEditing.groupingAvailable(transcribed))
        val two = SubtitleEditing.regroup(transcribed, 2)!!
        assertEquals(listOf("Good morning", "everyone.", "See you", "soon.", "Hello there."), SubtitleEditing.cues(two).map { it.text })
        assertEquals(2, two.subtitles?.maxWords)
        assertTrue(SubtitleEditing.groupingAvailable(two))
        assertNull(SubtitleEditing.regroup(two, 2))
        assertNull(SubtitleEditing.regroup(two, 0))
        assertNull(SubtitleEditing.regroup(two, 17))
        assertNull(SubtitleEditing.regroup(project.edit, 3))

        val saved = project.commit(two)
        val cut = saved.commit(TimelineEditing.split(saved.edit, 5.0)!!)
        assertFalse(SubtitleEditing.groupingAvailable(cut.edit))
        assertNull(SubtitleEditing.regroup(cut.edit, 4))
        assertEquals(SubtitleEditing.cues(two).map { it.text }, SubtitleEditing.cues(cut.edit).map { it.text }) // still shown, still editable
        assertTrue(SubtitleEditing.groupingAvailable(cut.undo().edit))
    }

    @Test fun theFingerprintIgnoresLookAndLevelButNotStructure() {
        val clips = transcribed.clips
        val print = SubtitleEditing.fingerprint(clips)
        assertEquals(64, print.length)
        assertEquals(print, SubtitleEditing.fingerprint(TimelineEditing.setAudio(transcribed, 0, ClipAudio(volumeDB = -3.0))!!.clips))
        assertEquals(print, SubtitleEditing.fingerprint(TimelineEditing.setCrop(transcribed, 0, CropSettings(zoom = 2.0))!!.clips))
        assertEquals(print, SubtitleEditing.fingerprint(SubtitleEditing.correct(transcribed, SubtitleEditing.cues(transcribed)[0], "Good day")!!.clips))
        for (changed in listOf(
            TimelineEditing.move(transcribed, 0, 1)!!, TimelineEditing.trim(transcribed, 0, 0.5, 20.0, 20.0)!!, TimelineEditing.setSpeed(transcribed, 0, 2.0)!!,
            TimelineEditing.setAudio(transcribed, 0, ClipAudio(muted = true))!!, TimelineEditing.delete(transcribed, 1)!!,
            TimelineEditing.setTransition(transcribed, 0, 1.0, ClipTransition.Style.Dissolve)!!,
        )) assertNotEquals(print, SubtitleEditing.fingerprint(changed.clips))
    }

    @Test fun correctingACaptionChangesOnlyThatCaption() {
        val cues = SubtitleEditing.cues(transcribed)
        val fixed = SubtitleEditing.correct(transcribed, cues[1], "See you later.")!!
        assertEquals(listOf("Good morning everyone.", "See you later.", "Hello there."), SubtitleEditing.cues(fixed).map { it.text })
        // Unchanged words keep their times and the caption keeps its place.
        assertEquals(10.0, SubtitleEditing.cues(fixed)[1].start, 1e-9)
        assertEquals(12.0, SubtitleEditing.cues(fixed)[1].end, 1e-9)
        assertEquals(transcribed.clips[0].subtitleWords!!.take(5), fixed.clips[0].subtitleWords!!.take(5))
        assertNull(SubtitleEditing.correct(transcribed, cues[1], "See you soon."))
        assertNull(SubtitleEditing.correct(transcribed, cues[1].copy(audioID = "nobody"), "x"))
        assertNull(SubtitleEditing.correct(fixed, cues[1].copy(wordIDs = listOf("gone")), "x"))
        project.commit(fixed).validate()
    }

    @Test fun deletingACaptionLeavesTheOthers() {
        val cues = SubtitleEditing.cues(transcribed)
        val less = SubtitleEditing.delete(transcribed, cues[0])!!
        assertEquals(listOf("See you soon.", "Hello there."), SubtitleEditing.cues(less).map { it.text })
        val none = SubtitleEditing.delete(less, SubtitleEditing.cues(less)[1])!!
        assertNull(none.clips[1].subtitleWords)
        assertEquals(SubtitleEditing.delete(transcribed, cues[0]), SubtitleEditing.correct(transcribed, cues[0], "   "))
    }

    @Test fun removingAllSubtitlesLeavesNothingAndCanBeUndone() {
        val saved = project.commit(transcribed)
        val removed = SubtitleEditing.remove(saved.edit)
        assertNull(removed.subtitles)
        assertTrue(removed.clips.all { it.subtitleWords == null })
        val next = saved.commit(removed)
        assertEquals(shown(saved.edit), shown(next.undo().edit))
        assertEquals(project.edit.clips, removed.clips)
    }

    // MARK: Placement

    private val portrait = SubtitlePlacement.canvas(1080.0, 1920.0)

    @Test fun theLayoutCanvasHasAShortSideOf1080() {
        assertEquals(Size(1080.0, 1920.0), SubtitlePlacement.canvas(2160.0, 3840.0))
        assertEquals(Size(1920.0, 1080.0), SubtitlePlacement.canvas(1280.0, 720.0))
        assertEquals(Size(1.0, 1.0), SubtitlePlacement.canvas(0.0, 0.0)) // nothing to lay out on, and no division by zero
    }

    @Test fun theSeriesStaysInsideThePicture() {
        val placement = SubtitlePlacement(portrait, Size(900.0, 200.0))
        // 0.94 of the width is the room: 1080 × 0.94 / 900 times the reference size.
        assertEquals(0.052 * 1080 * 0.94 / 900, placement.maximumSize, 1e-12)
        assertEquals(0.012, placement.minimumSize, 0.0)
        val big = placement.constrained(SubtitleSettings(size = 0.3, y = 1.0, x = 0.0))
        assertEquals(placement.maximumSize, big.size, 0.0)
        val scale = big.size / 0.052
        assertEquals(1 - (200 * scale / 1920 / 2 + 0.025), big.y, 1e-12)
        assertEquals(900 * scale / 1080 / 2 + 0.025, big.x!!, 1e-12)
        assertEquals(0.012, placement.constrained(SubtitleSettings(size = 0.001)).size, 0.0)
        // The default position is already inside.
        val usual = placement.constrained(SubtitleSettings())
        assertEquals(0.86, usual.y, 0.0)
        assertEquals(0.5, usual.x!!, 0.0)
        assertTrue(usual.valid)
        // A box bigger than the canvas still yields a valid size.
        assertEquals(0.0001, SubtitlePlacement(portrait, Size(1e9, 10.0)).maximumSize, 0.0)
    }

    @Test fun captionsShowingTogetherAreStackedAroundThePosition() {
        val placement = SubtitlePlacement(portrait, Size(300.0, 212.0))
        val settings = SubtitleSettings(size = 0.052, y = 0.5)
        assertEquals(listOf(0.5 - 100.0 / 1920 / 2), placement.tops(listOf(100.0), settings))
        val two = placement.tops(listOf(100.0, 100.0), settings)
        assertEquals(0.5 - 212.0 / 1920 / 2, two[0], 1e-12)
        assertEquals(two[0] + 112.0 / 1920, two[1], 1e-12)
        // Twice the size: twice the distances.
        val large = placement.tops(listOf(100.0, 100.0), settings.copy(size = 0.104))
        assertEquals(2 * (two[1] - two[0]), large[1] - large[0], 1e-12)
        assertTrue(placement.tops(emptyList(), settings).isEmpty())
    }

    @Test fun theLargestBoxCountsCaptionsShowingTogether() {
        fun cue(text: String, start: Double, end: Double) = SubtitleCue(text, "clip", emptyList(), text, start, end)
        val sizes = mapOf("wide" to Size(800.0, 90.0), "tall" to Size(300.0, 200.0), "small" to Size(100.0, 50.0))
        val apart = listOf(cue("wide", 0.0, 1.0), cue("tall", 1.0, 2.0), cue("small", 3.0, 4.0))
        assertEquals(Size(800.0, 200.0), SubtitlePlacement.largest(apart) { sizes.getValue(it) })   // one ends as the next starts: never together
        val together = listOf(cue("wide", 0.0, 2.0), cue("tall", 1.0, 3.0))
        assertEquals(Size(800.0, 302.0), SubtitlePlacement.largest(together) { sizes.getValue(it) })
        assertEquals(Size(1.0, 1.0), SubtitlePlacement.largest(emptyList()) { Size(5.0, 5.0) })
    }
}
