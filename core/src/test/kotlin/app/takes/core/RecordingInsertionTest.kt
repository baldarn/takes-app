package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Recording at the playhead of an edited project ("Record here").
 *
 * Tests marked TRANSLATED carry over an expectation of the iPhone app's
 * RecordingHistoryTests, ProjectRecoveryTests or VideoEffectIntegrationTests;
 * the others are this port's own.
 */
class RecordingInsertionTest {
    private fun take(name: String, duration: Double) = SourceMedia(filename = "$name.mp4", duration = duration)

    private fun reloaded(project: Project): Project {
        val files = HashMap<String, ByteArray>()
        val manifest = project.manifest { name, data -> files[name] = data }
        return Project.readManifest(manifest) { files.getValue(it) }
    }

    private fun sources(project: Project) = project.edit.clips.map { it.sourceID }
    private fun ranges(project: Project) = project.edit.clips.map { it.start to it.end }

    private val a = take("a", 20.0)
    private val b = take("b", 3.0)
    private val c = take("c", 2.0)
    private val d = take("d", 1.0)

    // MARK: Translated from the iPhone app

    /** TRANSLATED, RecordingHistoryTests: "All recorded insertion segments form one ordered undo step". */
    @Test fun aRunOfTakesIsOneOrderedUndoStep() {
        var project = Project("Record here").addRecording(a).beginRecordingInsertion(10.0).addRecording(b).addRecording(c)
        project.validate()
        assertEquals(2, project.history.size)
        assertEquals(listOf(a.id, b.id, c.id, a.id), sources(project))
        // "Undoing Record here preserves every original recording", after a reopen.
        project = reloaded(project).undo()
        assertEquals(1, project.edit.clips.size)
        assertEquals(3, project.sources.size)
        project = project.redo()
        assertEquals(listOf(a.id, b.id, c.id, a.id), sources(project))
    }

    /**
     * TRANSLATED, RecordingHistoryTests: "An inspector opened before Record
     * here recovery reports a conflict" and "A stale draft never overwrites a
     * recovered inserted recording".
     */
    @Test fun aDraftOlderThanAnInsertedTakeIsRefused() {
        var project = Project("Late record-here recovery").addRecording(a)
        val stale = project.edit
        project = project.beginRecordingInsertion(2.0).addRecording(b)
        val recovered = project.edit
        assertFalse(project.canCommit(stale))
        // The draft changed something, so only the refusal keeps it out.
        project = project.commit(stale.copy(clips = emptyList()))
        assertEquals(recovered, project.edit)
        // A state read after the take is an ordinary edit again.
        assertTrue(project.canCommit(project.edit))
        assertEquals(listOf(a.id, b.id), sources(project.commit(TimelineEditing.delete(project.edit, 2)!!)))
    }

    /** TRANSLATED, RecordingHistoryTests: "Recording at the front preserves the existing picture" (the canvas reference has no counterpart here). */
    @Test fun recordingAtTheFrontComesFirst() {
        val project = Project("Canvas history").addRecording(a).beginRecordingInsertion(0.0).addRecording(b)
        assertEquals(listOf(b.id, a.id), sources(project))
        assertEquals(listOf(0.0 to 3.0, 0.0 to 20.0), ranges(project))
    }

    /**
     * TRANSLATED, RecordingHistoryTests: "Recorded segments match an ordered
     * import after reload" and "Record here remains a single Undo", for the
     * start and the end. The original's gap site has no counterpart: this
     * timeline has no gaps.
     */
    @Test fun takesContinueInOrderAfterAReload() {
        for (atEnd in listOf(false, true)) {
            var project = Project("Sites").addRecording(a)
            val base = project.edit.clips
            val begins = if (atEnd) 20.0 else 0.0
            project = project.beginRecordingInsertion(begins).addRecording(c)
            project = reloaded(project).addRecording(b).endRecordingInsertion()
            val clips = project.edit.clips
            assertEquals(begins, TimelineEditing.clipStart(clips, clips.indexOfFirst { it.sourceID == c.id }), 1e-9)
            assertEquals(begins + 2, TimelineEditing.clipStart(clips, clips.indexOfFirst { it.sourceID == b.id }), 1e-9)
            project.validate()
            assertEquals(base, project.undo().edit.clips)
        }
    }

    /** TRANSLATED, VideoEffectIntegrationTests: a take recorded at a place in a project with nothing on its timeline. */
    @Test fun insertingIntoAnEmptyTimeline() {
        val project = Project("Inserted capture").beginRecordingInsertion(0.0).addRecording(b)
        assertEquals(listOf(b.id), sources(project))
        assertTrue(project.undo().edit.clips.isEmpty())
        project.validate()
    }

    @get:Rule val temporary = TemporaryFolder()

    /** TRANSLATED, ProjectRecoveryTests: "Record here lost its insertion anchor". The place is saved before the first take exists. */
    @Test fun thePlaceSurvivesOnDiskBeforeTheFirstTake() {
        val disk = ProjectDisk(File(temporary.root, "Projects"))
        val project = Project("Recovery").addRecording(a).beginRecordingInsertion(1.0)
        disk.save(project)
        val reopened = disk.read(disk.folder(project.id))
        assertEquals(1.0, reopened.recordingInsertion!!, 0.0)
        assertEquals(project, reopened)
        assertEquals(listOf(a.id, b.id, a.id), sources(reopened.addRecording(b)))
    }

    // MARK: Where the take lands

    @Test fun insideAClipTheClipIsSplitAroundTheTake() {
        val project = Project("x").addRecording(a).beginRecordingInsertion(10.0).addRecording(b)
        assertEquals(listOf(a.id, b.id, a.id), sources(project))
        assertEquals(listOf(0.0 to 10.0, 0.0 to 3.0, 10.0 to 20.0), ranges(project))
        assertEquals(23.0, project.edit.duration, 1e-9)
        // Every clip on the timeline is its own clip.
        assertEquals(3, project.edit.clips.map { it.id }.toSet().size)
        assertEquals(13.0, project.recordingInsertion!!, 1e-9)
        project.validate()
    }

    @Test fun atABoundaryNothingIsSplit() {
        var project = Project("x").addRecording(a).addRecording(d)
        val before = project.edit.clips
        project = project.beginRecordingInsertion(20.0).addRecording(b)
        assertEquals(listOf(before[0], project.edit.clips[1], before[1]), project.edit.clips)
        assertEquals(b.id, project.edit.clips[1].sourceID)
        assertEquals(23.0, project.recordingInsertion!!, 1e-9)
    }

    @Test fun atTheVeryStartAndTheVeryEnd() {
        val base = Project("x").addRecording(a).addRecording(d)
        assertEquals(listOf(b.id, a.id, d.id), sources(base.beginRecordingInsertion(0.0).addRecording(b)))
        val end = base.beginRecordingInsertion(21.0).addRecording(b)
        assertEquals(listOf(a.id, d.id, b.id), sources(end))
        assertEquals(24.0, end.recordingInsertion!!, 1e-9)
        // A place past the end is the end.
        assertEquals(listOf(a.id, d.id, b.id), sources(base.beginRecordingInsertion(500.0).addRecording(b)))
        // Even at the end it is an edit step, unlike a plain recording.
        assertTrue(end.canUndo)
        assertEquals(listOf(a.id, d.id), sources(end.undo()))
    }

    @Test fun aPlaceNearAnEdgeCutsThere() {
        // Since S4 a piece of any length is left, as in the original (`split-refused-near-an-edge`).
        val base = Project("x").addRecording(a).addRecording(d)
        assertEquals(listOf(a.id, b.id, a.id, d.id), sources(base.beginRecordingInsertion(0.05).addRecording(b)))
        assertEquals(listOf(0.0 to 0.05, 0.0 to 3.0, 0.05 to 20.0, 0.0 to 1.0), ranges(base.beginRecordingInsertion(0.05).addRecording(b)))
        assertEquals(listOf(0.0 to 19.95, 0.0 to 3.0, 19.95 to 20.0, 0.0 to 1.0), ranges(base.beginRecordingInsertion(19.95).addRecording(b)))
        // On the join itself nothing is cut.
        assertEquals(listOf(a.id, b.id, d.id), sources(base.beginRecordingInsertion(20.0).addRecording(b)))
    }

    @Test fun aSpedUpClipIsSplitAtThePictureShowing() {
        var project = Project("x").addRecording(a)
        project = project.commit(TimelineEditing.setSpeed(project.edit, 0, 2.0)!!)
        // Twice as fast: timeline second 4 shows source second 8.
        project = project.beginRecordingInsertion(4.0).addRecording(b)
        assertEquals(listOf(0.0 to 8.0, 0.0 to 3.0, 8.0 to 20.0), ranges(project))
        assertEquals(7.0, project.recordingInsertion!!, 1e-9)
        assertEquals(listOf(2.0, 1.0, 2.0), project.edit.clips.map { it.rate })
    }

    @Test fun whatTheSplitClipCarriesStaysOnItsPieces() {
        var project = Project("x").addRecording(a)
        var edit = TimelineEditing.setText(project.edit, 0, TextOverlay(text = "Early", start = 1.0, end = 3.0))!!
        edit = TimelineEditing.setText(edit, 0, TextOverlay(text = "Late", start = 15.0, end = 17.0))!!
        edit = TimelineEditing.toggleBookmark(edit, 0, 12.0)!!
        project = project.commit(edit).beginRecordingInsertion(10.0).addRecording(b)
        val clips = project.edit.clips
        // Both pieces keep both titles, as the original's split does; each shows the one over its own footage.
        assertEquals(listOf("Early", "Late"), clips[0].texts!!.map { it.text })
        assertNull(clips[0].bookmarks)
        assertNull(clips[1].texts)
        assertEquals(listOf("Early", "Late"), clips[2].texts!!.map { it.text })
        assertEquals(listOf("Early"), TimelineEditing.textsAt(clips[0], 2.0).map { it.text })
        assertEquals(listOf("Late"), TimelineEditing.textsAt(clips[2], 16.0).map { it.text })
        assertEquals(listOf(true, false), clips[0].texts!!.map { TimelineEditing.titleRange(clips, 0, it) != null })
        assertEquals(listOf(false, true), clips[2].texts!!.map { TimelineEditing.titleRange(clips, 2, it) != null })
        assertEquals(listOf(12.0), clips[2].bookmarks!!.map { it.time })
        // The mark is still over the same picture, now after the take.
        assertEquals(listOf(15.0), TimelineEditing.bookmarkTimes(clips))
    }

    @Test fun marksMadeWhileRecordingArriveOnTheInsertedTake() {
        val mark = CaptureEvent(time = 1.0, kind = Project.BOOKMARK_EVENT, detail = "")
        val marked = b.copy(events = listOf(mark, CaptureEvent(time = 2.0, kind = "camera", detail = "")))
        var project = Project("x").addRecording(a).beginRecordingInsertion(10.0).addRecording(marked)
        assertEquals(listOf(Bookmark(id = mark.id, time = 1.0)), project.edit.clips[1].bookmarks)
        assertEquals(listOf(11.0), TimelineEditing.bookmarkTimes(project.edit.clips))
        // A later take of the run rewrites the step and must not lose them.
        project = project.addRecording(c)
        assertEquals(listOf(Bookmark(id = mark.id, time = 1.0)), project.edit.clips[1].bookmarks)
        project.validate()
        assertEquals(project, reloaded(project))
    }

    @Test fun soundOnItsOwnTracksStaysWhereItIs() {
        val music = SourceMedia(filename = "music.m4a", duration = 30.0, audioOnly = true)
        var project = Project("x").addRecording(a).addSource(music)
        project = project.commit(TimelineEditing.addAudio(project.edit, music.id, music.duration, 2.0)!!)
        val sound = project.edit.audio
        project = project.beginRecordingInsertion(10.0).addRecording(b).addRecording(c)
        assertEquals(sound, project.edit.audio)
        project.validate()
    }

    // MARK: Transitions at the place

    private fun joined(style: TransitionStyle): Project {
        val x = take("x", 4.0); val y = take("y", 4.0)
        val project = Project("x").addRecording(x).addRecording(y)
        return project.commit(TimelineEditing.setTransition(project.edit, 0, 1.0, style)!!)
    }

    @Test fun insideADissolveTheTakeGoesBetweenTheTwoClips() {
        val base = joined(TransitionStyle.Dissolve)
        assertEquals(7.0, base.edit.duration, 1e-9)
        // 3.5 is inside the overlap, where a split is refused: both clips are showing.
        val project = base.beginRecordingInsertion(3.5).addRecording(b)
        assertEquals(listOf(0.0 to 4.0, 0.0 to 3.0, 0.0 to 4.0), ranges(project))
        // The dissolve joined those two clips; it does not pass to the take.
        assertTrue(project.edit.clips.all { it.transition == null })
        assertEquals(11.0, project.edit.duration, 1e-9)
        assertEquals(7.0, project.recordingInsertion!!, 1e-9)
        project.validate()
        // Undo brings the dissolve back with the join.
        assertEquals(base.edit.clips, project.undo().edit.clips)
        assertEquals(1.0, TimelineEditing.overlap(project.undo().edit.clips, 0), 1e-9)
    }

    @Test fun aFadeOnTheJoinIsRemovedNotPassedToTheTake() {
        val base = joined(TransitionStyle.FadeColor)
        val project = base.beginRecordingInsertion(4.0).addRecording(b)
        assertEquals(listOf(0.0 to 4.0, 0.0 to 3.0, 0.0 to 4.0), ranges(project))
        assertTrue(project.edit.clips.all { it.transition == null })
        assertNull(TimelineEditing.transition(project.edit.clips, 0))
        assertEquals(1.0, TimelineEditing.soundLevel(project.edit.clips, 1, 0.0), 0.0)
        assertEquals(base.edit.clips, project.undo().edit.clips)
    }

    @Test fun aJoinAwayFromThePlaceIsKept() {
        val base = joined(TransitionStyle.FadeColor)
        val join = base.edit.clips[0].transition!!
        // Inside the first clip: the join to the second clip follows the piece that now precedes it.
        val project = base.beginRecordingInsertion(2.0).addRecording(b)
        assertEquals(listOf(0.0 to 2.0, 0.0 to 3.0, 2.0 to 4.0, 0.0 to 4.0), ranges(project))
        assertEquals(listOf(null, null, join, null), project.edit.clips.map { it.transition })
        assertEquals(join, TimelineEditing.transition(project.edit.clips, 2))
        // Inside the second clip, the join stays where it was.
        val later = base.beginRecordingInsertion(6.0).addRecording(b)
        assertEquals(join, TimelineEditing.transition(later.edit.clips, 0))
        later.validate()
    }

    // MARK: History

    @Test fun threeTakesStayInOrderAndUndoTogether() {
        var project = Project("x").addRecording(a)
        val before = project.edit.clips
        project = project.beginRecordingInsertion(10.0).addRecording(b).addRecording(c).addRecording(d)
        assertEquals(listOf(a.id, b.id, c.id, d.id, a.id), sources(project))
        assertEquals(listOf(0.0 to 10.0, 0.0 to 3.0, 0.0 to 2.0, 0.0 to 1.0, 10.0 to 20.0), ranges(project))
        assertEquals(16.0, project.recordingInsertion!!, 1e-9)
        assertEquals(2, project.snapshots.size)
        assertEquals(1, project.historyCursor)
        assertEquals(1, project.recordingInsertionStep)
        project.validate()

        val undone = project.undo()
        assertEquals(before, undone.edit.clips)
        assertFalse(undone.canUndo)
        // The footage is still the project's.
        assertEquals(listOf(a.id, b.id, c.id, d.id), undone.sources.map { it.id })
        assertEquals(26.0, undone.capturedDuration, 1e-9)

        val redone = undone.redo()
        assertEquals(project.edit, redone.edit)
        assertFalse(redone.canRedo)
    }

    @Test fun anInsertedTakeIsInNoOtherHistoryStep() {
        var project = Project("x").addRecording(a)
        project = project.commit(TimelineEditing.trim(project.edit, 0, 0.0, 12.0, 20.0)!!)
        project = project.beginRecordingInsertion(5.0).addRecording(b)
        // Unlike an appended take, which every step gains.
        assertEquals(listOf(listOf(a.id), listOf(a.id), listOf(a.id, b.id, a.id)), project.history.map { state -> state.clips.map { it.sourceID } })
        assertEquals(listOf(20.0, 12.0, 15.0), project.history.map { it.duration })
    }

    /**
     * As the original does: after an Undo the run's step is no longer the
     * newest one in use, so the next take begins a new step on the timeline
     * as it is now, at the place the run had reached, and Redo is gone.
     */
    @Test fun undoThenAnotherTakeStartsANewStep() {
        var project = Project("x").addRecording(a).beginRecordingInsertion(10.0).addRecording(b).undo()
        assertEquals(13.0, project.recordingInsertion!!, 1e-9)
        project = project.addRecording(c)
        assertEquals(listOf(a.id, c.id, a.id), sources(project))
        assertEquals(listOf(0.0 to 13.0, 0.0 to 2.0, 13.0 to 20.0), ranges(project))
        assertFalse(project.canRedo)
        assertEquals(2, project.snapshots.size)
        assertEquals(1, project.recordingInsertionStep)
        // The undone take stays among the recordings without coming back to the timeline.
        assertEquals(listOf(a.id, b.id, c.id), project.sources.map { it.id })
        assertTrue(project.history.none { state -> state.clips.any { it.sourceID == b.id } })
        // The run goes on from there as one step.
        project = project.addRecording(d)
        assertEquals(listOf(a.id, c.id, d.id, a.id), sources(project))
        assertEquals(2, project.snapshots.size)
        assertEquals(listOf(a.id), sources(project.undo()))
        project.validate()
        assertEquals(project, reloaded(project))
    }

    /** Undo and Redo back onto the run's step: the step is the newest again, so the run continues in it. */
    @Test fun undoThenRedoThenAnotherTakeContinuesTheRun() {
        var project = Project("x").addRecording(a).beginRecordingInsertion(10.0).addRecording(b).undo().redo()
        project = project.addRecording(c)
        assertEquals(listOf(a.id, b.id, c.id, a.id), sources(project))
        assertEquals(2, project.snapshots.size)
        assertEquals(listOf(a.id), sources(project.undo()))
    }

    /** As the original does: an edit between takes ends the shared step; the next take is a step after that edit. */
    @Test fun anEditBetweenTakesSeparatesTheSteps() {
        var project = Project("x").addRecording(a).beginRecordingInsertion(10.0).addRecording(b)
        // Remove the second piece of the split clip: the place, second 13, is now the end.
        project = project.commit(TimelineEditing.delete(project.edit, 2)!!)
        project = project.addRecording(c)
        assertEquals(listOf(a.id, b.id, c.id), sources(project))
        assertEquals(15.0, project.recordingInsertion!!, 1e-9)
        assertEquals(4, project.snapshots.size)
        assertEquals(3, project.recordingInsertionStep)
        project.validate()
        // Each of the three is undone by itself.
        project = project.undo()
        assertEquals(listOf(a.id, b.id), sources(project))
        project = project.undo()
        assertEquals(listOf(a.id, b.id, a.id), sources(project))
        project = project.undo()
        assertEquals(listOf(a.id), sources(project))
    }

    /** An edit undone back onto the run's step leaves a Redo behind it, so the next take is a new step that replaces that Redo. */
    @Test fun aTakeAfterAnUndoneEditReplacesTheRedo() {
        var project = Project("x").addRecording(a).beginRecordingInsertion(10.0).addRecording(b)
        project = project.commit(TimelineEditing.delete(project.edit, 2)!!).undo()
        assertTrue(project.canRedo)
        project = project.addRecording(c)
        assertEquals(listOf(a.id, b.id, c.id, a.id), sources(project))
        assertFalse(project.canRedo)
        assertEquals(3, project.snapshots.size)
        assertEquals(listOf(a.id, b.id, a.id), sources(project.undo()))
    }

    @Test fun beginningAgainStartsANewStepAtTheNewPlace() {
        var project = Project("x").addRecording(a).beginRecordingInsertion(10.0).addRecording(b)
        project = project.beginRecordingInsertion(0.0).addRecording(c)
        assertEquals(listOf(c.id, a.id, b.id, a.id), sources(project))
        assertEquals(3, project.snapshots.size)
        assertEquals(listOf(a.id, b.id, a.id), sources(project.undo()))
    }

    @Test fun endingTheInsertionReturnsToAppending() {
        var project = Project("x").addRecording(a).beginRecordingInsertion(10.0).addRecording(b).endRecordingInsertion()
        assertNull(project.recordingInsertion)
        assertNull(project.recordingInsertionStep)
        project = project.addRecording(c)
        assertEquals(listOf(a.id, b.id, a.id, c.id), sources(project))
        // The appended take is no step of its own and is in every step; the inserted one is undone.
        assertEquals(2, project.snapshots.size)
        assertEquals(listOf(a.id, c.id), sources(project.undo()))
        assertEquals(listOf(a.id, b.id, a.id, c.id), sources(project.undo().redo()))
        project.validate()
        assertEquals(project, reloaded(project))
    }

    @Test fun aPlaceThatIsNotANumberChangesNothing() {
        val project = Project("x").addRecording(a)
        assertEquals(project, project.beginRecordingInsertion(Double.NaN))
        assertEquals(project, project.beginRecordingInsertion(Double.POSITIVE_INFINITY))
        assertEquals(0.0, project.beginRecordingInsertion(-3.0).recordingInsertion!!, 0.0)
    }

    // MARK: Saved format

    @Test fun aRunContinuesAfterTheProjectIsSavedAndReadBack() {
        var project = Project("x").addRecording(a).beginRecordingInsertion(10.0).addRecording(b)
        val reopened = reloaded(project)
        assertEquals(project, reopened)
        assertEquals(13.0, reopened.recordingInsertion!!, 1e-9)
        assertEquals(1, reopened.recordingInsertionStep)
        assertEquals(setOf(1), reopened.captureInsertions)
        project = reopened.addRecording(c)
        assertEquals(listOf(a.id, b.id, c.id, a.id), sources(project))
        assertEquals(2, project.snapshots.size)
        assertEquals(listOf(a.id), sources(reloaded(project).undo()))
    }

    @Test fun onlyAnInsertedTakeRaisesTheSchema() {
        val plain = Project("x").addRecording(a)
        assertEquals(1, plain.schemaVersion)
        // Choosing a place changes nothing an older reader could misread.
        assertEquals(1, plain.beginRecordingInsertion(10.0).schemaVersion)
        // An older reader would replay the take at the end of every history step; 0.9.1 wrote 10 for it.
        assertEquals(10, LegacyProject.addRecording(plain.beginRecordingInsertion(10.0), b, 0).schemaVersion)
        // Now the step is a timeline, as every edit step is.
        assertEquals(TrackTimeline.SCHEMA, plain.beginRecordingInsertion(10.0).addRecording(b).schemaVersion)
        assertEquals(21, Project.LATEST_READABLE_SCHEMA) // subtitles, title designs, the nine transitions, turned or moving crops, fades cut by a split and the timeline and timelines that are no sequence came after, held frames and time effects after them, then the mirror (20) and the movie's shape (21)
    }

    @Test fun aProjectWithoutInsertionsIsSavedAsBefore() {
        var project = Project("x").addRecording(a).addRecording(b)
        project = project.commit(TimelineEditing.split(project.edit, 5.0)!!)
        val text = project.manifest { _, _ -> }.decodeToString()
        for (key in listOf("captureInsertions", "recordingInsertion", "recordingInsertionStep")) assertFalse(key, text.contains(key))
        // And after a run has ended, only the list of inserted takes remains.
        val ended = project.beginRecordingInsertion(1.0).addRecording(c).endRecordingInsertion().manifest { _, _ -> }.decodeToString()
        assertTrue(ended.contains("\"captureInsertions\":[2]"))
        assertFalse(ended.contains("recordingInsertion"))
    }

    /** A manifest exactly as the previous version wrote it: a ledger of plain clips and none of the new keys. */
    @Test fun aProjectSavedByThePreviousFormatLoadsAndBehavesAsBefore() {
        val first = """{"state":{"clips":[]},"captureCursor":0}""".toByteArray()
        val second = """{"state":{"clips":[{"id":"C1","sourceID":"S1","start":0.0,"end":1.5}]},"captureCursor":1}""".toByteArray()
        val files = listOf(first, second).associateBy { sha256(it) + ".json" }
        val manifest = """
            {"schemaVersion":1,"id":"P1","title":"Old","createdAt":1,"updatedAt":2,
             "sources":[{"id":"S1","filename":"a.mp4","duration":4.0,"recordedAt":1},{"id":"S2","filename":"b.mp4","duration":2.0,"recordedAt":1}],
             "historyCursor":1,
             "captureLedger":[{"id":"C1","sourceID":"S1","start":0.0,"end":4.0},{"id":"C2","sourceID":"S2","start":0.0,"end":2.0}],
             "snapshotFiles":[${files.keys.joinToString(",") { "\"$it\"" }}],
             "exportSettings":{"codec":"H.264","resolution":"Project size","frameRate":30}}
        """.trimIndent().toByteArray()
        var project = Project.readManifest(manifest) { files.getValue(it) }
        assertTrue(project.captureInsertions.isEmpty())
        assertNull(project.recordingInsertion)
        assertNull(project.recordingInsertionStep)
        // The trimmed first take, then the take recorded after that edit.
        assertEquals(listOf(1.5, 2.0), project.edit.clips.map { it.duration })
        assertEquals(listOf(4.0, 2.0), project.undo().edit.clips.map { it.duration })
        // A new recording is still appended to every step, outside Undo, and the format stays readable by the old version.
        project = project.addRecording(c)
        assertEquals(listOf(1.5, 2.0, 2.0), project.edit.clips.map { it.duration })
        assertEquals(listOf(4.0, 2.0, 2.0), project.undo().edit.clips.map { it.duration })
        assertEquals(2, project.snapshots.size)
        assertEquals(1, project.schemaVersion)
        val saved = project.manifest { _, _ -> }.decodeToString()
        assertFalse(saved.contains("Insertion"))
        assertEquals(project, reloaded(project))
    }

    @Test fun validationCoversTheInsertionState() {
        fun rejected(project: Project) { assertThrows(ProjectException::class.java) { project.validate() } }
        val project = Project("x").addRecording(a).beginRecordingInsertion(10.0).addRecording(b)
        project.validate()
        rejected(project.copy(recordingInsertion = Double.NaN))
        rejected(project.copy(recordingInsertion = -1.0))
        rejected(project.copy(recordingInsertion = 604801.0))
        rejected(project.copy(recordingInsertionStep = 2))
        rejected(project.copy(recordingInsertionStep = -1))
        rejected(project.copy(captureInsertions = setOf(2)))
        rejected(project.copy(captureInsertions = setOf(-1)))
        // A document that marks the take as appended would show it twice in its own step.
        rejected(project.copy(captureInsertions = emptySet(), snapshots = project.snapshots.map { it.copy(captureCursor = 0) }))
        // Read back from a file, the same checks apply.
        val files = HashMap<String, ByteArray>()
        val manifest = project.manifest { name, data -> files[name] = data }.decodeToString()
        assertTrue(manifest.contains("\"captureInsertions\":[1]"))
        assertThrows(ProjectException::class.java) {
            Project.readManifest(manifest.replace("\"captureInsertions\":[1]", "\"captureInsertions\":[7]").toByteArray()) { files.getValue(it) }
        }
    }

    // MARK: The timeline operation by itself

    @Test fun insertRefusesWhatItCannotPlace() {
        val x = TimelineClip(sourceID = "X", start = 0.0, end = 4.0)
        val y = TimelineClip(sourceID = "Y", start = 0.0, end = 2.0)
        val state = EditState(listOf(x), captureBase = 1)
        assertNull(TimelineEditing.insert(state, emptyList(), 1.0))
        assertNull(TimelineEditing.insert(state, listOf(y), Double.NaN))
        // A clip is on the timeline once.
        assertNull(TimelineEditing.insert(state, listOf(x), 1.0))
        assertNull(TimelineEditing.insert(state, listOf(y, y), 1.0))
        val inserted = TimelineEditing.insert(state, listOf(y), 1.0)!!
        assertEquals(listOf("X", "Y", "X"), inserted.clips.map { it.sourceID })
        // The rest of the state is untouched.
        assertEquals(1, inserted.captureBase)
        assertEquals(6.0, inserted.duration, 1e-9)
    }

    @Test fun insertedClipsKeepTheJoinsAmongThemselvesOnly() {
        val x = TimelineClip(sourceID = "X", start = 0.0, end = 4.0)
        val z = TimelineClip(sourceID = "Z", start = 0.0, end = 2.0)
        // The join to a clip that is not coming along is dropped; the one between the two inserted clips stays.
        val y = TimelineClip(sourceID = "Y", start = 0.0, end = 2.0, transition = TransitionSettings(nextClipID = z.id))
        val last = z.copy(transition = TransitionSettings(nextClipID = "elsewhere"))
        val inserted = TimelineEditing.insert(EditState(listOf(x)), listOf(y, last), 4.0)!!
        assertEquals(listOf(x, y, z), inserted.clips)
    }
}
