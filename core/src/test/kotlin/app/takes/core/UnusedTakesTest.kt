package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Unused takes" list of the Add video panel, and inserting a selection.
 *
 * Tests marked TRANSLATED carry over an expectation of the iPhone app's
 * RecordingHistoryTests or VideoEffectIntegrationTests; the others are this
 * port's own.
 */
class UnusedTakesTest {
    private fun take(name: String, duration: Double) = SourceMedia(filename = "$name.mp4", duration = duration)

    private fun reloaded(project: Project): Project {
        val files = HashMap<String, ByteArray>()
        val manifest = project.manifest { name, data -> files[name] = data }
        return Project.readManifest(manifest) { files.getValue(it) }
    }

    private fun sources(project: Project) = project.edit.clips.map { it.sourceID }
    private fun ranges(project: Project) = project.edit.clips.map { it.start to it.end }
    private fun unused(project: Project) = UnusedTakes.of(project).map { it.id }

    private val a = take("a", 20.0)
    private val b = take("b", 3.0)
    private val c = take("c", 2.0)
    private val d = take("d", 1.0)
    private val music = SourceMedia(filename = "music.m4a", duration = 30.0, audioOnly = true)

    /** a, b, c and d recorded in that order, with b and d then deleted from the timeline. */
    private fun edited(): Project {
        var project = Project("x").addRecording(a).addRecording(b).addRecording(c).addRecording(d)
        project = project.commit(TimelineEditing.delete(project.edit, 3)!!)
        return project.commit(TimelineEditing.delete(project.edit, 1)!!)
    }

    // MARK: Which takes are unused

    @Test fun aNewProjectAndAProjectUsingEverythingHaveNone() {
        assertTrue(UnusedTakes.of(Project("x")).isEmpty())
        assertTrue(UnusedTakes.of(Project("x").addRecording(a).addRecording(b)).isEmpty())
    }

    @Test fun aTakeDeletedFromTheTimelineIsUnused() {
        val project = edited()
        assertEquals(listOf(a.id, c.id), sources(project))
        assertEquals(listOf(b, d), UnusedTakes.of(project))
    }

    @Test fun theyComeInTheOrderTheProjectHoldsThemWhateverTheirDatesOrNames() {
        val late = take("z", 1.0).copy(recordedAt = 9_000, originalName = "Alpha")
        val early = take("y", 9.0).copy(recordedAt = 1_000, originalName = "Zulu")
        var project = Project("x").addRecording(a).addRecording(late).addRecording(early)
        project = project.commit(project.edit.copy(clips = project.edit.clips.take(1)))
        assertEquals(listOf(late, early), UnusedTakes.of(project))
    }

    @Test fun aTakeOnTheTimelineSeveralTimesIsUsedUntilItsLastPieceGoes() {
        var project = Project("x").addRecording(a).addRecording(b)
        project = project.commit(TimelineEditing.split(project.edit, 5.0)!!)
        project = project.commit(TimelineEditing.split(project.edit, 12.0)!!)
        assertEquals(listOf(a.id, a.id, a.id, b.id), sources(project))
        assertTrue(unused(project).isEmpty())
        project = project.commit(TimelineEditing.delete(project.edit, 0)!!)
        project = project.commit(TimelineEditing.delete(project.edit, 0)!!)
        assertTrue(unused(project).isEmpty())
        project = project.commit(TimelineEditing.delete(project.edit, 0)!!)
        assertEquals(listOf(a.id), unused(project))
    }

    @Test fun aTrimmedTakeIsStillUsed() {
        var project = Project("x").addRecording(a)
        project = project.commit(TimelineEditing.trim(project.edit, 0, 8.0, 9.0, 20.0)!!)
        assertTrue(unused(project).isEmpty())
    }

    @Test fun onlyTheCurrentEditCountsNotTheRestOfTheHistory() {
        val project = edited()
        // Every older step still shows b; the current one does not.
        assertTrue(project.history.dropLast(1).all { state -> state.clips.any { it.sourceID == b.id } })
        assertEquals(listOf(b.id, d.id), unused(project))
        // Undo moves the current edit, and the list with it.
        assertEquals(listOf(d.id), unused(project.undo()))
        assertTrue(unused(project.undo().undo()).isEmpty())
        // b is on the timeline only in a step that Redo would reach.
        assertEquals(listOf(b.id, d.id), unused(project.undo().redo()))
    }

    @Test fun aTakeRecordedAtAPlaceAndUndoneIsUnused() {
        val project = Project("x").addRecording(a).beginRecordingInsertion(10.0).addRecording(b).endRecordingInsertion()
        assertTrue(unused(project).isEmpty())
        assertEquals(listOf(b.id), unused(project.undo()))
    }

    @Test fun soundFilesAreNeverTakes() {
        // On no track at all.
        var project = edited().addSource(music)
        assertEquals(listOf(b.id, d.id), unused(project))
        // And placed on a sound track.
        project = project.commit(TimelineEditing.addAudio(project.edit, music.id, music.duration, 0.0)!!)
        assertEquals(listOf(b.id, d.id), unused(project))
        // A project with sound only has no unused takes.
        assertTrue(UnusedTakes.of(Project("x").addSource(music)).isEmpty())
    }

    @Test fun importedFootageCountsLikeARecording() {
        val imported = take("import", 6.0).copy(originalName = "IMG_0042.MOV")
        var project = Project("x").addRecording(a).addRecording(imported)
        project = project.commit(TimelineEditing.delete(project.edit, 1)!!)
        assertEquals(listOf(imported), UnusedTakes.of(project))
    }

    @Test fun anImportedLutIsNotASource() {
        var project = edited()
        val graded = ColorSettings(imported = ImportedLUT("effect-lut-0a1b.cube", "Film"))
        project = project.commit(TimelineEditing.setColor(project.edit, 0, graded)!!)
        assertEquals(listOf("effect-lut-0a1b.cube"), project.effectFiles)
        assertEquals(listOf(b.id, d.id), unused(project))
    }

    /** The picture decides: footage left on the timeline only as unlinked sound has no clip, so it is offered. */
    @Test fun footagePlayingOnlyOnASoundTrackIsUnused() {
        var project = Project("x").addRecording(a).addRecording(b)
        val sound = AudioItem(sourceID = b.id, start = 0.0, sourceStart = 0.0, sourceEnd = b.duration)
        project = project.commit(project.edit.copy(clips = project.edit.clips.take(1), audio = listOf(sound)))
        project.validate()
        assertEquals(listOf(b.id), unused(project))
    }

    @Test fun theListSurvivesSavingAndReading() {
        val project = edited()
        assertEquals(UnusedTakes.of(project), UnusedTakes.of(reloaded(project)))
    }

    /** TRANSLATED, RecordingHistoryTests: "Loading never guesses that an unused source should return". */
    @Test fun readingAProjectNeverPutsAnUnusedTakeBack() {
        val project = reloaded(edited())
        assertEquals(listOf(a.id, c.id), sources(project))
        assertEquals(4, project.sources.size)
        // Nor does an ordinary edit, or a new recording.
        val trimmed = project.commit(TimelineEditing.trim(project.edit, 0, 0.0, 5.0, 20.0)!!)
        assertEquals(listOf(a.id, c.id), sources(trimmed))
        val e = take("e", 4.0)
        assertEquals(listOf(a.id, c.id, e.id), sources(trimmed.addRecording(e)))
        assertEquals(listOf(b.id, d.id), unused(trimmed.addRecording(e)))
    }

    // MARK: Adding a selection

    /** TRANSLATED, RecordingHistoryTests: "Unused takes reuse their originals in the chosen order". */
    @Test fun theSelectionGoesInInTheChosenOrderReusingTheOriginals() {
        val project = edited()
        val added = UnusedTakes.add(project, listOf(d, b), 0.0)!!
        assertEquals(listOf(d.id, b.id, a.id, c.id), sources(added.project))
        assertEquals(listOf(0.0 to 1.0, 0.0 to 3.0, 0.0 to 20.0, 0.0 to 2.0), ranges(added.project))
        assertEquals(project.sources, added.project.sources)
        assertEquals(0.0, added.start, 0.0)
        assertTrue(unused(added.project).isEmpty())
        added.project.validate()
        assertEquals(added.project, reloaded(added.project))
    }

    /** TRANSLATED, RecordingHistoryTests: "One Undo restores the exact previous edit" and "One Redo restores the complete sequence". */
    @Test fun theWholeSelectionIsOneUndoStep() {
        val project = edited()
        val added = UnusedTakes.add(project, listOf(d, b), 10.0)!!.project
        assertEquals(project.snapshots.size + 1, added.snapshots.size)
        assertEquals(project.historyCursor + 1, added.historyCursor)
        val undone = added.undo()
        assertEquals(project.edit, undone.edit)
        assertEquals(listOf(b.id, d.id), unused(undone))
        assertEquals(added.edit, undone.redo().edit)
        // After a reopen too.
        assertEquals(project.edit, reloaded(added).undo().edit)
    }

    /** TRANSLATED, RecordingHistoryTests: "Middle insertion respects selection order" and "Only the original clip is split, once at the playhead". */
    @Test fun insideAClipTheClipIsSplitOnceAroundTheSelection() {
        val added = UnusedTakes.add(edited(), listOf(b, d), 1.25)!!
        assertEquals(listOf(a.id, b.id, d.id, a.id, c.id), sources(added.project))
        assertEquals(listOf(0.0 to 1.25, 0.0 to 3.0, 0.0 to 1.0, 1.25 to 20.0, 0.0 to 2.0), ranges(added.project))
        assertEquals(1.25, added.start, 1e-9)
        assertEquals(26.0, added.project.edit.duration, 1e-9)
        assertEquals(5, added.project.edit.clips.map { it.id }.toSet().size)
    }

    @Test fun atTheStartAtAJoinAndAtTheEnd() {
        val project = edited()
        assertEquals(listOf(b.id, a.id, c.id), sources(UnusedTakes.add(project, listOf(b), 0.0)!!.project))
        val join = UnusedTakes.add(project, listOf(b), 20.0)!!
        assertEquals(listOf(a.id, b.id, c.id), sources(join.project))
        assertEquals(20.0, join.start, 1e-9)
        val end = UnusedTakes.add(project, listOf(b, d), project.edit.duration)!!
        assertEquals(listOf(a.id, c.id, b.id, d.id), sources(end.project))
        assertEquals(22.0, end.start, 1e-9)
        // A place past the end is the end, and one before the start is the start.
        assertEquals(end.project.edit.clips.map { it.sourceID }, sources(UnusedTakes.add(project, listOf(b, d), 500.0)!!.project))
        assertEquals(listOf(b.id, a.id, c.id), sources(UnusedTakes.add(project, listOf(b), -4.0)!!.project))
    }

    @Test fun tooCloseToAnEdgeGoesToThatEdge() {
        val project = edited()
        val added = UnusedTakes.add(project, listOf(b), 19.95)!!
        assertEquals(listOf(0.0 to 20.0, 0.0 to 3.0, 0.0 to 2.0), ranges(added.project))
        // The reported start is where the take landed, not where it was asked for.
        assertEquals(20.0, added.start, 1e-9)
    }

    @Test fun intoAnEmptyTimeline() {
        var project = Project("x").addRecording(a).addRecording(b)
        project = project.commit(project.edit.copy(clips = emptyList()))
        assertEquals(listOf(a.id, b.id), unused(project))
        val added = UnusedTakes.add(project, listOf(b, a), 7.0)!!
        assertEquals(listOf(b.id, a.id), sources(added.project))
        assertEquals(0.0, added.start, 0.0)
        assertTrue(added.project.undo().edit.clips.isEmpty())
    }

    @Test fun theAddedTakeIsInNoOtherHistoryStepAndLaterRecordingsStillAppend() {
        var project = UnusedTakes.add(edited(), listOf(b), 0.0)!!.project
        val before = project.history.dropLast(1).map { state -> state.clips.map { it.sourceID } }
        assertEquals(edited().history.map { state -> state.clips.map { it.sourceID } }, before)
        val e = take("e", 4.0)
        project = project.addRecording(e)
        assertEquals(listOf(b.id, a.id, c.id, e.id), sources(project))
        assertEquals(listOf(a.id, c.id, e.id), sources(project.undo()))
        project.validate()
    }

    @Test fun aTransitionOnTheJoinItLandsOnIsRemovedAndComesBackWithUndo() {
        var project = edited()
        project = project.commit(TimelineEditing.setTransition(project.edit, 0, 1.0, ClipTransition.Style.FadeBlack)!!)
        val added = UnusedTakes.add(project, listOf(b), 20.0)!!.project
        assertTrue(added.edit.clips.all { it.transition == null })
        assertEquals(project.edit.clips, added.undo().edit.clips)
    }

    @Test fun soundOnItsOwnTracksStaysWhereItIs() {
        var project = edited().addSource(music)
        project = project.commit(TimelineEditing.addAudio(project.edit, music.id, music.duration, 2.0)!!)
        val added = UnusedTakes.add(project, listOf(b), 0.0)!!.project
        assertEquals(project.edit.audio, added.edit.audio)
        added.validate()
    }

    @Test fun theTakeArrivesWholeAndPlain() {
        // Whatever the clip carried when it was removed is not brought back.
        var project = Project("x").addRecording(a).addRecording(b)
        project = project.commit(TimelineEditing.trim(project.edit, 1, 1.0, 2.0, 3.0)!!)
        project = project.commit(TimelineEditing.setSpeed(project.edit, 1, 2.0)!!)
        project = project.commit(TimelineEditing.delete(project.edit, 1)!!)
        val clip = UnusedTakes.add(project, listOf(b), 20.0)!!.project.edit.clips[1]
        assertEquals(TimelineClip(id = clip.id, sourceID = b.id, start = 0.0, end = 3.0), clip)
    }

    @Test fun theProjectsRecordOfTheTakeDecidesItsLength() {
        val stale = b.copy(duration = 99.0)
        val added = UnusedTakes.add(edited(), listOf(stale), 0.0)!!.project
        assertEquals(0.0 to 3.0, ranges(added)[0])
        added.validate()
    }

    @Test fun aTakeChosenTwiceGoesInTwice() {
        val added = UnusedTakes.add(edited(), listOf(b, b), 22.0)!!.project
        assertEquals(listOf(a.id, c.id, b.id, b.id), sources(added))
        assertEquals(4, added.edit.clips.map { it.id }.toSet().size)
        added.validate()
    }

    @Test fun whatCannotBeAddedChangesNothing() {
        val project = edited()
        assertNull(UnusedTakes.add(project, emptyList(), 0.0))
        assertNull(UnusedTakes.add(project, listOf(b), Double.NaN))
        assertNull(UnusedTakes.add(project, listOf(b), Double.POSITIVE_INFINITY))
        // Footage of another project.
        assertNull(UnusedTakes.add(project, listOf(b, take("stranger", 1.0)), 0.0))
        // A sound file is not a take.
        val withMusic = project.addSource(music)
        assertNull(UnusedTakes.add(withMusic, listOf(music), 0.0))
        assertFalse(project.canRedo)
    }

    @Test fun addingAfterAnUndoReplacesTheRedo() {
        val project = edited().undo()
        assertTrue(project.canRedo)
        val added = UnusedTakes.add(project, listOf(d), 0.0)!!.project
        assertFalse(added.canRedo)
        assertEquals(listOf(d.id, a.id, b.id, c.id), sources(added))
        assertEquals(project.edit, added.undo().edit)
    }

    @Test fun theSchemaStaysReadableByOlderVersions() {
        assertEquals(1, UnusedTakes.add(edited(), listOf(b, d), 5.0)!!.project.schemaVersion)
    }
}
