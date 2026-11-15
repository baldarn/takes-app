package app.takes.core

import app.takes.core.ExportCheckpoint.Ending
import app.takes.core.ExportCheckpoint.Failure
import app.takes.core.ExportProgress.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExportCheckpointTest {
    @get:Rule val temporary = TemporaryFolder()
    private val folder get() = File(temporary.root, "project")
    private val exact = 1e-12

    private val source = SourceMedia(filename = "source.mp4", duration = 4.0)

    /** CoreTests.swift:865: eight clips of 0.1…3.99, a movie of 31.12 s that does not end on a frame. */
    private fun project(clips: Int = 8): Project =
        Project(title = "Interrupted export", sources = listOf(source), createdAt = 1_000)
            .commit(EditState(List(clips) { TimelineClip(sourceID = source.id, start = 0.1, end = 3.99) }), now = 2_000)

    private fun saveSections(checkpoint: ExportCheckpoint, vararg indices: Int) {
        for (index in indices) checkpoint.section(index).writeText("picture")
    }

    // ---- Sections (ExportCheckpoint.swift:23, 112-139) ----

    // ExportCheckpoint.swift:23, 111 and 115-117: twelve seconds, the last section whatever is left.
    @Test fun aMovieIsCutIntoTwelveSecondSections() {
        assertEquals(12, ExportSections.SECONDS)
        assertEquals(emptyList<ExportSections.Section>(), ExportSections.sections(0.0))
        assertEquals(listOf(0.0 to 5.0), ExportSections.sections(5.0).map { it.start to it.length })
        assertEquals(listOf(0.0 to 12.0), ExportSections.sections(12.0).map { it.start to it.length })
        assertEquals(listOf(0.0 to 12.0, 12.0 to 6.0), ExportSections.sections(18.0).map { it.start to it.length })
        val long = ExportSections.sections(31.12)
        assertEquals(listOf(0, 1, 2), long.map { it.index })
        assertEquals(listOf(0.0, 12.0, 24.0), long.map { it.start })
        assertEquals(listOf(12.0, 12.0, 7.12), long.map { it.length })
        assertEquals(31.12, long.last().end, exact)
        assertEquals(300, ExportSections.sections(3600.0).size)
    }

    // TrackTimeline.swift:6-9: the edit clock has 240 000 ticks a second and rounds to the nearest.
    @Test fun timesAreCountedInTicks() {
        assertEquals(240_000L, ExportSections.ticks(1.0))
        assertEquals(7_468_800L, ExportSections.ticks(31.12))
        assertEquals(9_600L, ExportSections.ticks(0.040))
        assertEquals(1L, ExportSections.ticks(0.0000025))
        assertEquals(0L, ExportSections.ticks(Double.NaN))
        assertEquals(604_800L * 240_000, ExportSections.ticks(1e12))
    }

    // CoreTests.swift:992: ceil(duration × rate − 0.00001) pictures in the movie, at every rate (Project.swift:663).
    @Test fun aMovieHoldsOnePictureForEveryFrameTickItCovers() {
        assertEquals(ExportSettings.FRAME_RATES, listOf(24, 25, 30, 50, 60))
        assertEquals(listOf(288, 300, 360, 600, 720), ExportSettings.FRAME_RATES.map { ExportSections.pictures(12.0, it) })
        // 31.12 s: 746.88, 778, 933.6, 1556 and 1867.2 frame lengths.
        assertEquals(listOf(747, 778, 934, 1556, 1868), ExportSettings.FRAME_RATES.map { ExportSections.pictures(31.12, it) })
        // CoreTests.swift:1024: 18 s at 60 fps is 1080 pictures.
        assertEquals(1080, ExportSections.pictures(18.0, 60))
        // The sections of a movie hold exactly its pictures between them.
        for (rate in ExportSettings.FRAME_RATES) {
            assertEquals(ExportSections.pictures(31.12, rate), ExportSections.sections(31.12).sumOf { ExportSections.pictures(it.lengthTicks, rate) })
        }
        assertThrows(IllegalArgumentException::class.java) { ExportSections.frameTicks(7) }
    }

    // ExportCheckpoint.swift:163-164 and CoreTests.swift:1017-1025: 719 of 720 pictures is not a finished section.
    @Test fun aSectionOnePictureShortIsEncodedAgain() {
        val twelve = ExportSections.ticks(12.0)
        assertFalse(ExportSections.isComplete(719, twelve, 60))
        assertTrue(ExportSections.isComplete(720, twelve, 60))
        assertFalse("One picture too many", ExportSections.isComplete(721, twelve, 60))
        assertFalse(ExportSections.isComplete(359, twelve, 30))
        assertTrue(ExportSections.isComplete(360, twelve, 30))
        // The last 7.12 s at 30 fps are 213.6 frame lengths: 214 pictures cover them, 213 do not.
        val last = ExportSections.ticks(7.12)
        assertFalse(ExportSections.isComplete(213, last, 30))
        assertTrue(ExportSections.isComplete(214, last, 30))
        assertFalse(ExportSections.isComplete(215, last, 30))
    }

    // ExportCheckpoint.swift:270-272: what the join accepts from each section.
    @Test fun sectionsAreJoinedOnlyWhenTheyFit() {
        val twelve = ExportSections.ticks(12.0)
        assertTrue(ExportSections.canJoin(360, twelve, 30, last = false))
        assertFalse("A middle section must be exactly full", ExportSections.canJoin(359, twelve, 30, last = false))
        assertTrue("The last one may be a picture short", ExportSections.canJoin(359, twelve, 30, last = true))
        assertFalse(ExportSections.canJoin(358, twelve, 30, last = true))
        assertFalse(ExportSections.canJoin(0, 4_000, 30, last = true))
    }

    // ExportCheckpoint.swift:77-83.
    @Test fun savedSecondsCountWholeSectionsUpToTheMoviesLength() {
        assertEquals(0.0, ExportSections.savedSeconds(0, 31.12), 0.0)
        assertEquals(12.0, ExportSections.savedSeconds(1, 31.12), 0.0)
        assertEquals(24.0, ExportSections.savedSeconds(2, 31.12), 0.0)
        assertEquals("The last section is shorter", 31.12, ExportSections.savedSeconds(3, 31.12), 0.0)
        assertEquals("Files beyond the movie do not count", 31.12, ExportSections.savedSeconds(9, 31.12), 0.0)
        assertEquals(5.0, ExportSections.savedSeconds(1, 5.0), 0.0)
        assertEquals(0.0, ExportSections.savedSeconds(4, 0.0), 0.0)
    }

    // MediaRenderer.swift:795: the finished movie may differ by 0.12 s, or by two pictures if that is more.
    @Test fun theFinishedMovieMustHaveTheRightLength() {
        assertTrue(ExportSections.durationMatches(31.23, 31.12, 30))
        assertFalse(ExportSections.durationMatches(31.25, 31.12, 30))
        assertTrue(ExportSections.durationMatches(31.01, 31.12, 60))
        assertFalse(ExportSections.durationMatches(30.99, 31.12, 60))
        // At 24 fps two pictures are 0.0833 s, still less than 0.12 s.
        assertTrue(ExportSections.durationMatches(10.119, 10.0, 24))
        assertFalse(ExportSections.durationMatches(10.121, 10.0, 24))
        assertFalse(ExportSections.durationMatches(Double.NaN, 10.0, 30))
        assertFalse(ExportSections.durationMatches(Double.POSITIVE_INFINITY, 10.0, 30))
        // Sound recorded at 8 kHz ends on packets of 0.128 s: the movie of a 7.833 s take came out 7.992 s long.
        assertTrue(ExportSections.durationMatches(7.992, 7.833, 30, soundPacket = 1024 / 8000.0))
        assertFalse(ExportSections.durationMatches(7.992, 7.833, 30))
        assertFalse(ExportSections.durationMatches(8.2, 7.833, 30, soundPacket = 1024 / 8000.0))
        // At 48 kHz the allowance is the same as without it.
        assertFalse(ExportSections.durationMatches(10.121, 10.0, 24, soundPacket = 1024 / 48000.0))
    }

    // MediaRenderer.swift:802-805: the last picture is read back too, unless the movie is a blink.
    @Test fun theLastPictureIsReadBack() {
        assertEquals(31.12 - 1.0 / 30, ExportSections.lastPictureTime(31.13, 31.12, 30)!!, exact)
        assertEquals(31.10 - 1.0 / 60, ExportSections.lastPictureTime(31.10, 31.12, 60)!!, exact)
        assertEquals(0.0, ExportSections.lastPictureTime(0.11, 0.01, 24)!!, 0.0)
        assertNull(ExportSections.lastPictureTime(0.1, 0.1, 30))
    }

    // ---- The saved work on disk ----

    // ExportCheckpoint.swift:26.
    @Test fun theSavedWorkLivesInAHiddenFolderOfExports() {
        val checkpoint = ExportCheckpoint(folder)
        assertEquals(File(folder, "Exports/.paused-export"), checkpoint.directory)
        assertEquals(File(folder, "Exports/.paused-export/picture-3.mp4"), checkpoint.section(3))
        assertEquals(File(folder, "Exports/.paused-export/sound.mp4"), checkpoint.audioFile)
    }

    // ExportCheckpoint.swift:29-44 and CoreTests.swift:892.
    @Test fun reopeningTheSameProjectFindsItsExport() {
        val project = project()
        val checkpoint = ExportCheckpoint(folder)
        assertFalse("Nothing saved yet", checkpoint.matches(project))
        checkpoint.prepare(project)
        saveSections(checkpoint, 0)
        assertTrue(ExportCheckpoint(folder).matches(project))
        // Preparing again keeps the sections.
        ExportCheckpoint(folder).prepare(project)
        assertTrue(checkpoint.section(0).isFile)
    }

    // ExportCheckpoint.swift:6-18: the ticket's fields, under the original's names.
    @Test fun theTicketNamesTheProjectStateItWasMadeFrom() {
        val project = project()
        val checkpoint = ExportCheckpoint(folder)
        checkpoint.prepare(project)
        assertEquals(
            """{"projectID":"${project.id}","revision":2000,"historyCursor":1}""",
            File(checkpoint.directory, "ticket.json").readText(),
        )
        val chosen = project.copy(exportSettings = ExportSettings(ExportSettings.Codec.HEVC, ExportSettings.Resolution.UltraHD, 60))
        checkpoint.prepare(chosen)
        assertEquals(
            """{"projectID":"${project.id}","revision":2000,"historyCursor":1,"settings":{"codec":"H.265","resolution":"4K","frameRate":60}}""",
            File(checkpoint.directory, "ticket.json").readText(),
        )
    }

    // ExportCheckpoint.swift:17: a ticket without settings stands for the default format.
    @Test fun aTicketWithoutSettingsMeansTheDefaultFormat() {
        val project = project()
        val checkpoint = ExportCheckpoint(folder)
        checkpoint.directory.mkdirs()
        File(checkpoint.directory, "ticket.json").writeText("""{"projectID":"${project.id}","revision":2000,"historyCursor":1}""")
        assertTrue(checkpoint.matches(project))
        assertFalse(checkpoint.matches(project.copy(exportSettings = ExportSettings(frameRate = 60))))
    }

    // CoreTests.swift:922-923 and 926-931: an undo makes the saved sections stale, and preparing removes them.
    @Test fun aChangedTimelineCannotReuseTheSections() {
        val project = project()
        val checkpoint = ExportCheckpoint(folder)
        checkpoint.prepare(project)
        saveSections(checkpoint, 0, 1)
        checkpoint.savePeak(0.5f)
        val undone = project.undo(now = 3_000)
        assertFalse(checkpoint.matches(undone))
        checkpoint.prepare(undone)
        assertTrue(checkpoint.matches(undone))
        assertEquals(0.0, checkpoint.savedSeconds(32.0), 0.0)
        assertNull(checkpoint.measuredPeak)
    }

    // ExportCheckpoint.swift:6-18: every field of the ticket invalidates on its own.
    @Test fun anyDifferenceInTheProjectStateDiscardsTheSavedWork() {
        val project = project()
        val checkpoint = ExportCheckpoint(folder)
        checkpoint.prepare(project)
        val another = Project(title = "Another", sources = project.sources, snapshots = project.snapshots, historyCursor = 1, createdAt = 1_000, updatedAt = 2_000)
        assertFalse("Another project", checkpoint.matches(another))
        assertFalse("A rename saves the project again", checkpoint.matches(project.renamed("Renamed", now = 2_001)))
        assertFalse("A further edit", checkpoint.matches(project.commit(EditState(project.edit.clips.dropLast(1)), now = 2_002)))
        assertFalse("The same step saved at another time", checkpoint.matches(project.copy(updatedAt = 2_003)))
        assertFalse("Another step of the history at the same time", checkpoint.matches(project.copy(historyCursor = 0)))
        assertTrue(checkpoint.matches(project))
    }

    // CoreTests.swift:1000-1002: a change of format cannot reuse sections encoded for another.
    @Test fun aChangeOfFormatDiscardsTheSavedWork() {
        val project = project().copy(exportSettings = ExportSettings(resolution = ExportSettings.Resolution.FullHD, frameRate = 25))
        val checkpoint = ExportCheckpoint(folder)
        checkpoint.prepare(project)
        for (changed in listOf(
            project.exportSettings.copy(resolution = ExportSettings.Resolution.UltraHD),
            project.exportSettings.copy(frameRate = 50),
            project.exportSettings.copy(codec = ExportSettings.Codec.HEVC),
            ExportSettings(),
        )) assertFalse(changed.toString(), checkpoint.matches(project.copy(exportSettings = changed)))
        assertTrue(checkpoint.matches(project))
    }

    @Test fun aDamagedTicketMatchesNothing() {
        val project = project()
        val checkpoint = ExportCheckpoint(folder)
        checkpoint.prepare(project)
        File(checkpoint.directory, "ticket.json").writeText("{")
        assertFalse(checkpoint.matches(project))
        saveSections(checkpoint, 0)
        checkpoint.prepare(project)
        assertTrue(checkpoint.matches(project))
        assertFalse("Sections of an unknown state are not trusted", checkpoint.section(0).exists())
    }

    // ExportCheckpoint.swift:77-83: only an unbroken run of sections from the first counts.
    @Test fun savedSecondsStopAtTheFirstMissingSection() {
        val project = project()
        val total = project.edit.duration
        assertEquals(31.12, total, 1e-9)
        val checkpoint = ExportCheckpoint(folder)
        checkpoint.prepare(project)
        assertEquals(0.0, checkpoint.savedSeconds(total), 0.0)
        saveSections(checkpoint, 1)
        assertEquals("Section 0 is missing", 0.0, checkpoint.savedSeconds(total), 0.0)
        assertEquals(listOf(0, 2), checkpoint.remaining(total).map { it.index })
        saveSections(checkpoint, 0)
        assertEquals(24.0, checkpoint.savedSeconds(total), 0.0)
        assertEquals(listOf(2), checkpoint.remaining(total).map { it.index })
        saveSections(checkpoint, 2, 3)
        assertEquals(total, checkpoint.savedSeconds(total), 0.0)
        assertEquals(3, checkpoint.completeSections(total))
        assertTrue(checkpoint.remaining(total).isEmpty())
    }

    // ExportCheckpoint.swift:85-92 and CoreTests.swift:876, 895-897.
    @Test fun aResumedExportStartsWhereTheSavedWorkLeavesTheBar() {
        val project = project()
        val total = project.edit.duration
        val checkpoint = ExportCheckpoint(folder)
        checkpoint.prepare(project)
        assertEquals("Fresh export opens at zero", ExportProgress.of(Step.Opening), checkpoint.startingProgress(total))

        checkpoint.savePeak(0.7f)
        var start = checkpoint.startingProgress(total)
        assertEquals(Step.Opening, start.step)
        assertEquals("The peak is measured", 0.09, start.fraction, exact)

        checkpoint.audioFile.writeText("sound")
        start = checkpoint.startingProgress(total)
        assertEquals("The sound is encoded", 0.14, start.fraction, exact)

        saveSections(checkpoint, 0)
        start = checkpoint.startingProgress(total)
        assertEquals(Step.Opening, start.step)
        assertEquals(ExportProgress.of(Step.Picture, 12.0 / total).fraction, start.fraction, 0.0)
        assertEquals(0.14 + 0.76 * 12.0 / 31.12, start.fraction, 1e-9)
        assertEquals(ExportProgress.Label.Reopening, start.label)

        saveSections(checkpoint, 1, 2)
        assertEquals("Everything is rendered; joining remains", 0.90, checkpoint.startingProgress(total).fraction, exact)
        assertEquals(ExportProgress.of(Step.Opening), checkpoint.startingProgress(0.0))
    }

    // ExportCheckpoint.swift:66-73.
    @Test fun theMeasuredPeakIsKeptAsOneNumber() {
        val checkpoint = ExportCheckpoint(folder)
        checkpoint.prepare(project())
        assertNull(checkpoint.measuredPeak)
        checkpoint.savePeak(1.25f)
        assertEquals("1.25", File(checkpoint.directory, "peak.json").readText())
        assertEquals(1.25f, checkpoint.measuredPeak)
        File(checkpoint.directory, "peak.json").writeText("loud")
        assertNull(checkpoint.measuredPeak)
    }

    // ExportCheckpoint.swift:46-51.
    @Test fun thePauseReasonIsKeptUntilItIsCleared() {
        val checkpoint = ExportCheckpoint(folder)
        checkpoint.prepare(project())
        assertNull(checkpoint.pauseReason)
        checkpoint.pauseReason = "Free about 2 GB more space." + ExportCheckpoint.FREE_SPACE_HINT
        assertEquals("Free about 2 GB more space. Free space, then resume the export.", ExportCheckpoint(folder).pauseReason)
        checkpoint.pauseReason = null
        assertNull(checkpoint.pauseReason)
        assertFalse(File(checkpoint.directory, "pause-reason.txt").exists())
    }

    // ExportCheckpoint.swift:52-54.
    @Test fun discardingRemovesEverything() {
        val checkpoint = ExportCheckpoint(folder)
        checkpoint.discard() // nothing there yet
        checkpoint.prepare(project())
        saveSections(checkpoint, 0)
        checkpoint.audioFile.writeText("sound")
        checkpoint.discard()
        assertFalse(checkpoint.directory.exists())
        assertTrue("The Exports folder itself stays", File(folder, "Exports").isDirectory)
    }

    // ExportCheckpoint.swift:58-64 and CoreTests.swift:924-926.
    @Test fun finishedSectionsLeaveTheSlotWithoutBeingDeleted() {
        val project = project()
        val checkpoint = ExportCheckpoint(folder)
        checkpoint.prepare(project)
        saveSections(checkpoint, 0)
        val detached = checkpoint.detachCompletedFiles()
        assertEquals(File(folder, "Processing"), detached.parentFile)
        assertTrue(File(detached, "picture-0.mp4").isFile)
        assertFalse(checkpoint.directory.exists())
        checkpoint.prepare(project)
        assertTrue(checkpoint.matches(project))
        assertEquals(0.0, checkpoint.savedSeconds(31.12), 0.0)
    }

    @Test fun aFinishedStageIsMovedInWhole() {
        val checkpoint = ExportCheckpoint(folder)
        checkpoint.prepare(project())
        val partial = File(folder, "Processing/encoding.mp4").apply { parentFile.mkdirs(); writeText("picture") }
        checkpoint.publish(partial, checkpoint.section(0))
        assertFalse(partial.exists())
        assertEquals(12.0, checkpoint.savedSeconds(31.12), 0.0)
    }

    // EditorController.swift:633-648.
    @Test fun aPausedExportIsOfferedAgainWhenItStillFits() {
        val project = project()
        val checkpoint = ExportCheckpoint(folder)
        assertNull("Nothing was paused", checkpoint.restore(project, exported = false))
        checkpoint.prepare(project)
        saveSections(checkpoint, 0)
        checkpoint.pauseReason = "Full."
        assertEquals(ExportCheckpoint.Paused(12.0, "Full."), ExportCheckpoint(folder).restore(project, exported = false))
        assertTrue(checkpoint.directory.isDirectory)

        // The same edit was exported meanwhile: nothing is left to resume.
        assertNull(checkpoint.restore(project, exported = true))
        assertFalse(checkpoint.directory.exists())
    }

    // EditorController.swift:636-642: an edit made while paused throws the saved work away.
    @Test fun aPausedExportOfAnEarlierEditIsRemoved() {
        val project = project()
        val checkpoint = ExportCheckpoint(folder)
        checkpoint.prepare(project)
        saveSections(checkpoint, 0, 1)
        assertNull(checkpoint.restore(project.undo(now = 9_000), exported = false))
        assertFalse(checkpoint.directory.exists())
    }

    // EditorController.swift:692-698 and 860-866.
    @Test fun theSavedWorkOutlivesAPauseButNotACancel() {
        val project = project()
        val checkpoint = ExportCheckpoint(folder)
        checkpoint.prepare(project)
        saveSections(checkpoint, 0)
        assertEquals(Ending.Paused, checkpoint.finish(paused = true, published = false))
        assertTrue(checkpoint.section(0).isFile)
        // Publishing may win a race with a pause (EditorController.swift:746-748).
        assertEquals(Ending.Published, checkpoint.finish(paused = true, published = true))
        assertEquals(Ending.Published, checkpoint.finish(paused = false, published = true))
        assertTrue("Retiring the exports takes it away, not the ending", checkpoint.section(0).isFile)
        assertEquals(Ending.Abandoned, checkpoint.finish(paused = false, published = false))
        assertFalse(checkpoint.directory.exists())
    }

    // EditorController.swift:755-765.
    @Test fun aFailureIsSortedByItsCause() {
        assertEquals(Failure.Cancelled, ExportCheckpoint.failure("Stopped", cancelled = true, outOfSpace = true, foreground = false))
        assertEquals(
            Failure.PausedForSpace("No space left. Free space, then resume the export."),
            ExportCheckpoint.failure("No space left.", cancelled = false, outOfSpace = true, foreground = true),
        )
        assertEquals(
            "Space comes before the background",
            Failure.PausedForSpace("Full. Free space, then resume the export."),
            ExportCheckpoint.failure("Full.", cancelled = false, outOfSpace = true, foreground = false),
        )
        assertEquals(Failure.PausedInBackground, ExportCheckpoint.failure("Encoder lost", cancelled = false, outOfSpace = false, foreground = false))
        assertEquals(Failure.Failed("Encoder lost"), ExportCheckpoint.failure("Encoder lost", cancelled = false, outOfSpace = false, foreground = true))
        assertEquals(
            "An error that follows a pause is not shown",
            Failure.Cancelled, ExportCheckpoint.failure("Encoder lost", cancelled = false, outOfSpace = false, foreground = true, paused = true),
        )
    }

    // ProjectStore.swift:136-141: working files are reclaimed on launch; exports and a paused export are not.
    @Test fun workingFilesAreReclaimedButNotThePausedExport() {
        val project = project()
        val checkpoint = ExportCheckpoint(folder)
        checkpoint.prepare(project)
        saveSections(checkpoint, 0)
        File(folder, "Processing/half.mp4").apply { parentFile.mkdirs(); writeText("half") }
        File(folder, "Exports/movie.mp4").writeText("movie")
        File(folder, source.filename).writeText("take")
        ExportCheckpoint.reclaim(folder)
        assertFalse(File(folder, "Processing").exists())
        assertTrue(File(folder, "Exports/movie.mp4").isFile)
        assertTrue(File(folder, source.filename).isFile)
        assertEquals(12.0, checkpoint.savedSeconds(31.12), 0.0)
        ExportCheckpoint.reclaim(folder) // nothing to do
    }
}
