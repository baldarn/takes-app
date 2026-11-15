package app.takes.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Random

class ProjectDuplicateTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root get() = File(temporary.root, "Projects")
    private fun disk() = ProjectDisk(root)

    /** Incompressible, like real media, and different for every file. */
    private fun bytes(count: Int, seed: Long) = ByteArray(count).also { Random(seed).nextBytes(it) }

    private val take = SourceMedia(filename = "take one.mp4", duration = 4.0)
    private val scene = SourceMedia(filename = "scene.mp4", duration = 4.0)
    private val music = SourceMedia(filename = "music.m4a", duration = 30.0, audioOnly = true)
    private val lut = ImportedLUT("effect-lut-0a1b.cube", "Film")
    // The first one takes more than one read to copy.
    private val contents = mapOf(
        take.filename to bytes(2_500_000, 1), scene.filename to bytes(70_000, 2),
        music.filename to bytes(1, 3), lut.filename to "LUT_3D_SIZE 2\n".toByteArray(),
    )

    private fun save(project: Project): Project {
        disk().save(project)
        for (name in project.mediaFiles) File(disk().folder(project.id), name).writeBytes(contents.getValue(name))
        return project
    }

    /** Two takes with a dissolve between them, made and last changed at known times. */
    private fun trip(): Project {
        val project = Project("Trip", createdAt = 10).addRecording(take, now = 11).addRecording(scene, now = 12)
        return project.commit(TimelineEditing.setTransition(project.edit, 0, 1.0, ClipTransition.Style.Dissolve)!!, now = 13)
    }

    private fun duplicate(project: Project, title: String = "Trip copy", maximumBytes: Long = Long.MAX_VALUE, progress: (Long, Long) -> Unit = { _, _ -> }) =
        ProjectDuplicate.duplicate(project, root, title, maximumBytes, now = 42, progress = progress)

    /** Every file and folder beside the projects too: nothing may escape the library either. */
    private fun everything(): List<String> = temporary.root.walkTopDown().sortedBy { it.path }
        .map { it.relativeTo(temporary.root).path + if (it.isFile) " " + sha256(it.readBytes()) else "/" }.toList()

    /** A copy that fails changes nothing on disk and leaves no work folder behind. */
    private fun <T : Throwable> refused(type: Class<T>, restoring: () -> Unit = {}, copy: () -> Unit): T {
        val before = everything()
        val error = assertThrows(type) { copy() }
        restoring()
        assertEquals(before, everything())
        return error
    }

    @Test fun copyIsTheSameProjectWithItsOwnIdentity() {
        val project = save(trip())
        val before = everything()
        val copy = duplicate(project)

        assertNotEquals(project.id, copy.id)
        assertEquals(project.copy(id = copy.id, title = "Trip copy", createdAt = 42, updatedAt = 42), copy)
        assertEquals(project.edit, copy.edit)
        // The takes and clips are the same ones: only the project is new.
        assertEquals(project.sources, copy.sources)
        // What is on disk is what was returned, in a folder named for it.
        val folder = disk().folder(copy.id)
        assertEquals(copy, disk().read(folder))
        assertEquals(listOf("History", "project.json", "scene.mp4", "take one.mp4"), folder.list()!!.sorted())
        assertEquals(project.snapshots.map { sha256(it.data()) + ".json" }.toSet(), File(folder, "History").list()!!.toSet())
        for (name in project.mediaFiles) assertArrayEquals(contents[name], File(folder, name).readBytes())

        // The original is as it was, and the library has both, the copy first.
        assertEquals(before, everything().filter { !it.startsWith("Projects/${copy.id}") })
        assertEquals(setOf(project.id, copy.id), root.list()!!.toSet())
        val library = disk().loadAll()
        assertEquals(listOf(copy, project), library.projects)
        assertTrue(library.unavailable.isEmpty())
    }

    @Test fun historyUndoAndRedoComeAlong() {
        var project = trip()
        project = project.commit(TimelineEditing.setTransition(project.edit, 0, 0.5, ClipTransition.Style.FadeBlack)!!)
        project = save(project.undo().copy(exportSettings = ExportSettings(ExportSettings.Codec.HEVC, ExportSettings.Resolution.FullHD, 25)))
        assertTrue(project.canUndo && project.canRedo)

        val copy = duplicate(project)
        assertEquals(project.history, copy.history)
        assertEquals(project.historyCursor, copy.historyCursor)
        assertEquals(project.exportSettings, copy.exportSettings)
        assertTrue(copy.canUndo && copy.canRedo)

        // Redo in the copy cannot change the original.
        disk().save(disk().read(disk().folder(copy.id)).redo())
        val unchanged = disk().read(disk().folder(project.id))
        assertEquals(project, unchanged)
        assertTrue(unchanged.canRedo)
        assertEquals(project.redo().edit, disk().read(disk().folder(copy.id)).edit)
    }

    @Test fun copyStartsItsOwnRecordings() {
        // In the middle of inserting takes at the playhead, one already in.
        val project = save(Project("Trip").addRecording(take).beginRecordingInsertion(1.0).addRecording(scene))
        assertTrue(project.recordingInsertion != null && project.recordingInsertionStep != null)

        val copy = duplicate(project)
        assertNull(copy.recordingInsertion)
        assertNull(copy.recordingInsertionStep)
        val saved = disk().read(disk().folder(copy.id))
        assertNull(saved.recordingInsertion)
        assertNull(saved.recordingInsertionStep)
        // The take that was inserted stays where it is, and Undo still takes it off.
        assertEquals(project.captureInsertions, copy.captureInsertions)
        assertEquals(project.history, copy.history)
        assertEquals(project.undo().edit, copy.undo().edit)
        // The next take of the copy goes at the end; the original goes on inserting.
        val next = SourceMedia(filename = "next.mp4", duration = 2.0)
        assertEquals(next.id, copy.addRecording(next).edit.clips.last().sourceID)
        assertNotEquals(next.id, project.addRecording(next).edit.clips.last().sourceID)
        assertEquals(project, disk().read(disk().folder(project.id)))
    }

    @Test fun mediaIsCopiedNotShared() {
        val project = save(trip())
        val copy = duplicate(project)
        val mine = File(disk().folder(project.id), take.filename)
        val theirs = File(disk().folder(copy.id), take.filename)
        assertFalse(Files.isSymbolicLink(theirs.toPath()))
        assertFalse(Files.isSameFile(mine.toPath(), theirs.toPath()))
        assertEquals(1, Files.getAttribute(theirs.toPath(), "unix:nlink"))

        // Writing over the original's recording, then erasing the project, leaves the copy whole.
        mine.writeBytes(bytes(10, 9))
        assertArrayEquals(contents[take.filename], theirs.readBytes())
        disk().moveToDeleted(project)
        disk().eraseDeleted(project.id)
        assertEquals(listOf(copy), disk().loadAll().projects)
        for (name in copy.mediaFiles) assertArrayEquals(contents[name], File(disk().folder(copy.id), name).readBytes())
    }

    @Test fun soundAndLutsComeAlongEvenWhenOnlyUndoRefersToThem() {
        var project = trip().addSource(music)
        project = project.commit(TimelineEditing.addAudio(project.edit, music.id, music.duration, 2.0)!!)
        project = save(project.commit(TimelineEditing.setColor(project.edit, 0, ColorSettings(imported = lut))!!).undo())
        assertEquals(listOf(lut.filename), project.effectFiles)

        val copy = duplicate(project)
        val folder = disk().folder(copy.id)
        assertEquals(project.edit.audio, disk().read(folder).edit.audio)
        assertEquals(listOf("History", lut.filename, "music.m4a", "project.json", "scene.mp4", "take one.mp4"), folder.list()!!.sorted())
        for (name in project.mediaFiles) assertArrayEquals(contents[name], File(folder, name).readBytes())
    }

    @Test fun onlyWhatTheProjectNamesIsCopied() {
        val project = save(trip())
        // A second save keeps a revision of the manifest.
        disk().save(project.renamed("Trip to the sea", now = 14))
        val source = disk().folder(project.id)
        assertTrue(File(source, ".revisions").list()!!.isNotEmpty())
        for (name in listOf("Cache/preview.mp4", "Exports/master.mp4", "unfinished.mp4", "notes.txt")) {
            File(source, name).also { it.parentFile.mkdirs() }.writeBytes(bytes(100, 5))
        }

        val copy = duplicate(project.renamed("Trip to the sea", now = 14))
        assertEquals(listOf("History", "project.json", "scene.mp4", "take one.mp4"), disk().folder(copy.id).list()!!.sorted())
        // A take the original has not indexed yet is still its own to index.
        assertEquals(listOf("unfinished.mp4"), disk().unindexedRecordings(project).map { it.name })
        assertTrue(disk().unindexedRecordings(copy).isEmpty())
    }

    @Test fun editsNotSavedYetComeAlong() {
        val saved = save(trip())
        val edited = saved.commit(TimelineEditing.setTransition(saved.edit, 0, null)!!, now = 20)
        val copy = duplicate(edited)
        assertEquals(edited.history, disk().read(disk().folder(copy.id)).history)
        // The original on disk is still the one that was saved.
        assertEquals(saved, disk().read(disk().folder(saved.id)))
    }

    @Test fun missingFileStaysMissingInBoth() {
        val project = save(trip().addSource(music))
        File(disk().folder(project.id), music.filename).delete()
        val copy = duplicate(project)
        assertEquals(project.sources, copy.sources)
        assertEquals(listOf("History", "project.json", "scene.mp4", "take one.mp4"), disk().folder(copy.id).list()!!.sorted())
        assertEquals(copy, disk().read(disk().folder(copy.id)))
    }

    @Test fun emptyProjectDuplicatesEvenBeforeItIsSaved() {
        val project = Project("Empty")
        assertFalse(root.exists())
        val copy = duplicate(project, "Empty copy")
        assertNotEquals(project.id, copy.id)
        assertEquals(0.0, copy.edit.duration, 0.0)
        assertEquals(project.copy(id = copy.id, title = "Empty copy", createdAt = 42, updatedAt = 42), disk().read(disk().folder(copy.id)))
        assertEquals(listOf(copy.id), root.list()!!.toList())
    }

    @Test fun duplicatingTwiceGivesTwoProjects() {
        val project = save(trip())
        val first = duplicate(project, ProjectDuplicate.title(project.title, listOf(project.title)))
        val second = duplicate(project, ProjectDuplicate.title(project.title, listOf(project.title, first.title)))
        assertEquals(3, setOf(project.id, first.id, second.id).size)
        assertEquals(listOf("Trip copy", "Trip copy 2"), listOf(first.title, second.title))
        assertEquals(3, disk().loadAll().projects.size)
        // A copy duplicates again, numbered beside the others.
        val third = duplicate(first, ProjectDuplicate.title(first.title, disk().loadAll().projects.map { it.title }))
        assertEquals(first.copy(id = third.id, title = "Trip copy 3"), third)
        for (name in third.mediaFiles) assertArrayEquals(contents[name], File(disk().folder(third.id), name).readBytes())
    }

    @Test fun copyIsNamedAfterTheOriginal() {
        assertEquals("Trip copy", ProjectDuplicate.title("Trip", emptyList()))
        assertEquals("Trip copy", ProjectDuplicate.title("Trip", listOf("Trip", "Other copy")))
        assertEquals("Trip copy 2", ProjectDuplicate.title("Trip", listOf("Trip", "Trip copy")))
        assertEquals("Trip copy 4", ProjectDuplicate.title("Trip", listOf("Trip copy 3", "Trip copy", "Trip copy 2")))
        // The first free one, even when a later number is taken.
        assertEquals("Trip copy", ProjectDuplicate.title("Trip", listOf("Trip copy 2")))
        assertEquals("Trip copy 2", ProjectDuplicate.title("Trip", listOf("Trip copy", "Trip copy 3")))
        // Titles that differ only in case are the same title.
        assertEquals("Trip copy 2", ProjectDuplicate.title("Trip", listOf("TRIP COPY")))
        assertEquals(" copy", ProjectDuplicate.title("", emptyList()))
    }

    @Test fun copyOfACopyIsNumberedBesideIt() {
        assertEquals("Trip copy 2", ProjectDuplicate.title("Trip copy", listOf("Trip", "Trip copy")))
        assertEquals("Trip copy 3", ProjectDuplicate.title("Trip copy 2", listOf("Trip", "Trip copy", "Trip copy 2")))
        // The number of the one copied does not matter, only which are free.
        assertEquals("Trip copy", ProjectDuplicate.title("Trip copy 7", listOf("Trip copy 7")))
        assertEquals("Trip copy", ProjectDuplicate.title("Trip COPY 12", emptyList()))
        // Only at the very end, and only the whole word.
        assertEquals("Trip copy 2 final copy", ProjectDuplicate.title("Trip copy 2 final", emptyList()))
        assertEquals("Photocopy copy", ProjectDuplicate.title("Photocopy", emptyList()))
        assertEquals("Trip copy two copy", ProjectDuplicate.title("Trip copy two", emptyList()))
        // One suffix is taken off, not every one.
        assertEquals("Trip copy copy", ProjectDuplicate.title("Trip copy copy", emptyList()))
        // A title that is only the word is a title, not a suffix.
        assertEquals("copy copy", ProjectDuplicate.title("copy", emptyList()))
    }

    @Test fun longTitlesMakeRoomForTheSuffix() {
        val long = "x".repeat(200)
        assertEquals("x".repeat(115) + " copy", ProjectDuplicate.title(long, emptyList()))
        assertEquals("x".repeat(113) + " copy 2", ProjectDuplicate.title(long, listOf("x".repeat(115) + " copy")))
        assertEquals(120, ProjectDuplicate.title("x".repeat(120), emptyList()).length)
        // Short enough already: nothing is cut.
        assertEquals("x".repeat(115) + " copy", ProjectDuplicate.title("x".repeat(115), emptyList()))
        // A character made of two is dropped whole rather than cut in half.
        val clapper = "🎬"
        assertEquals("x".repeat(114) + " copy", ProjectDuplicate.title("x".repeat(114) + clapper + "y", emptyList()))
        assertEquals("x".repeat(113) + clapper + " copy", ProjectDuplicate.title("x".repeat(113) + clapper + "y", emptyList()))
    }

    @Test fun suffixCanBeInThePersonsLanguage() {
        assertEquals("Viaggio copia", ProjectDuplicate.title("Viaggio", listOf("Viaggio"), "copia"))
        assertEquals("Viaggio copia 2", ProjectDuplicate.title("Viaggio copia", listOf("Viaggio", "Viaggio copia"), "copia"))
        // Nothing in the word is read as a pattern.
        assertEquals("Trip copy c.py", ProjectDuplicate.title("Trip copy", emptyList(), "c.py"))
        assertEquals("Trip (1) 2", ProjectDuplicate.title("Trip (1)", listOf("Trip (1)"), "(1)"))
    }

    @Test fun linkInPlaceOfAFileIsRefused() {
        val project = save(trip().addSource(music))
        val outside = File(temporary.root, "private.txt").also { it.writeBytes(bytes(10, 4)) }
        val file = File(disk().folder(project.id), music.filename)
        file.delete()
        Files.createSymbolicLink(file.toPath(), outside.toPath())
        assertEquals(ProjectException.Reason.InvalidDocument, refused(ProjectException::class.java) { duplicate(project) }.reason)
        // A link to nowhere is not a missing file either.
        outside.delete()
        assertEquals(ProjectException.Reason.InvalidDocument, refused(ProjectException::class.java) { duplicate(project) }.reason)
        // Nor is a folder.
        file.delete()
        file.mkdirs()
        assertEquals(ProjectException.Reason.InvalidDocument, refused(ProjectException::class.java) { duplicate(project) }.reason)
    }

    @Test fun invalidProjectIsRefused() {
        val project = save(trip())
        val broken = project.copy(historyCursor = 9)
        assertEquals(ProjectException.Reason.InvalidDocument, refused(ProjectException::class.java) { duplicate(broken) }.reason)
    }

    @Test fun failureHalfwayLeavesNoProjectBehind() {
        val project = save(trip().addSource(music))
        val later = File(disk().folder(project.id), music.filename)
        var copied = 0L
        // The first recording is across when a later file stops being readable.
        refused(IOException::class.java, restoring = { later.writeBytes(contents.getValue(music.filename)) }) {
            duplicate(project) { done, _ -> copied = done; if (done > 0) later.delete() }
        }
        assertTrue(copied >= contents.getValue(take.filename).size)
        assertEquals(listOf(project), disk().loadAll().projects)
        // And it can be tried again.
        assertEquals(2, duplicate(project).let { disk().loadAll().projects.size })
    }

    @Test fun progressFollowsTheMediaAndCanStopTheCopy() {
        val project = save(trip().addSource(music))
        val total = project.mediaFiles.sumOf { contents.getValue(it).size.toLong() }
        val seen = mutableListOf<Long>()
        duplicate(project) { copied, all -> assertEquals(total, all); seen += copied }
        assertEquals(0L, seen.first())
        assertEquals(total, seen.last())
        assertEquals(seen.sorted(), seen)
        // More than one step for a recording larger than one read.
        assertTrue(seen.distinct().size > project.mediaFiles.size)

        class Stopped : RuntimeException()
        for (limit in listOf(0L, 1L, total / 2, total)) {
            refused(Stopped::class.java) { duplicate(project) { copied, _ -> if (copied >= limit) throw Stopped() } }
        }
        assertEquals(2, disk().loadAll().projects.size)
    }

    @Test fun copyNeedsRoomForTheMedia() {
        val project = save(trip())
        val total = project.mediaFiles.sumOf { contents.getValue(it).size.toLong() }
        var reported = false
        val error = refused(ProjectDuplicateException::class.java) { duplicate(project, maximumBytes = total - 1) { _, _ -> reported = true } }
        assertEquals(ProjectDuplicateException.Reason.NotEnoughSpace, error.reason)
        // Known before anything is written.
        assertFalse(reported)
        assertEquals(listOf(project), disk().loadAll().projects)
        duplicate(project, maximumBytes = total)
        assertEquals(2, disk().loadAll().projects.size)
    }

    @Test fun roomIsCountedAsItIsReallyCopied() {
        val project = save(trip())
        val total = project.mediaFiles.sumOf { contents.getValue(it).size.toLong() }
        val later = File(disk().folder(project.id), scene.filename)
        var grown = false
        // A recording that grows after it was measured does not get past the cap.
        val error = refused(ProjectDuplicateException::class.java, restoring = { later.writeBytes(contents.getValue(scene.filename)) }) {
            duplicate(project, maximumBytes = total) { copied, _ ->
                if (copied > 0 && !grown) { grown = true; later.appendBytes(bytes(10, 8)) }
            }
        }
        assertEquals(ProjectDuplicateException.Reason.NotEnoughSpace, error.reason)
        assertTrue(grown)
    }

    @Test fun workOfAnInterruptedCopyIsCleared() {
        val project = save(trip())
        // What a copy leaves when the app is stopped in the middle of it.
        val stale = File(root, ".duplicate-0b0e5c2e-0000-4000-8000-000000000000-1").also { File(it, "ID/History").mkdirs() }
        File(stale, "ID/take one.mp4").writeBytes(bytes(100, 7))
        // Hidden, so it was never taken for a project.
        assertEquals(listOf(project), disk().loadAll().projects)
        ProjectDuplicate.discardInterrupted(root)
        assertFalse(stale.exists())
        assertEquals(listOf(project.id), root.list()!!.toList())

        stale.mkdirs()
        // The work of a restore is not this one's to clear.
        val restoring = File(root, ".restore-0b0e5c2e-0000-4000-8000-000000000000-1").also { it.mkdirs() }
        val copy = duplicate(project)
        assertEquals(setOf(project.id, copy.id, restoring.name), root.list()!!.toSet())
    }

    @Test fun recentlyDeletedProjectsAreLeftAlone() {
        val project = save(trip())
        val other = save(trip().renamed("Gone"))
        disk().moveToDeleted(other)
        val deleted = File(disk().deletedRoot, other.id)
        val before = deleted.walkTopDown().map { it.path }.toList()

        val copy = duplicate(project)
        assertNotEquals(other.id, copy.id)
        assertEquals(before, deleted.walkTopDown().map { it.path }.toList())
        // Both can be in the library again.
        disk().restoreDeleted(other.id)
        assertEquals(setOf(project.id, other.id, copy.id), disk().loadAll().projects.map { it.id }.toSet())
    }
}
