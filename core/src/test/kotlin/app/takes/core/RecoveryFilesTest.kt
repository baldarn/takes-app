package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Recordings that could not be read: moved aside with what was saved beside them, listed, and deleted
 * only when the person says so (YouRec/Project.swift:726-752, YouRec/ProjectStore.swift:568-572).
 */
class RecoveryFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun disk() = ProjectDisk(File(temporary.root, "Projects"))
    private fun take(name: String, duration: Double) = SourceMedia(filename = "$name.mp4", duration = duration)

    @Test fun anUnreadableRecordingIsMovedAsideWithItsEvents() {
        val disk = disk()
        val project = Project("Trip").addRecording(take("a", 4.0))
        disk.save(project)
        val folder = disk.folder(project.id)
        File(folder, "a.mp4").writeText("movie")
        File(folder, "a.events.json").writeText("[]")
        File(folder, "broken.mp4").writeText("half a movie")
        File(folder, "broken.events.json").writeText("[1]")
        File(folder, "brokenly.mp4").writeText("another")
        assertTrue(disk.recoveryFiles(project).isEmpty())

        disk.quarantineRecording(File(folder, "broken.mp4"), project)

        val kept = disk.recoveryFiles(project).single()
        assertEquals("broken.mp4", kept.name)
        assertEquals("half a movie", kept.readText())
        assertEquals("[1]", File(kept.parentFile, "broken.events.json").readText())
        assertEquals(File(folder, "Recovery"), kept.parentFile.parentFile)
        // Gone from the project's folder, so the next launch does not try it again; the rest is untouched.
        assertFalse(File(folder, "broken.mp4").exists())
        assertFalse(File(folder, "broken.events.json").exists())
        assertEquals(listOf("brokenly.mp4"), disk.unindexedRecordings(project).map { it.name })
        assertEquals("movie", File(folder, "a.mp4").readText())
        assertEquals("[]", File(folder, "a.events.json").readText())
        // The project itself is as it was.
        assertEquals(project, disk.loadAll().projects.single())
    }

    @Test fun aRecordingOfTheProjectIsNeverMovedAside() {
        val disk = disk()
        val project = Project("Trip").addRecording(take("a", 4.0))
        disk.save(project)
        val folder = disk.folder(project.id)
        File(folder, "a.mp4").writeText("movie")
        disk.quarantineRecording(File(folder, "a.mp4"), project)
        disk.quarantineRecording(File(folder, "missing.mp4"), project)
        disk.quarantineRecording(File(temporary.root, "elsewhere.mp4").apply { writeText("x") }, project)
        assertTrue(File(folder, "a.mp4").exists())
        assertTrue(File(temporary.root, "elsewhere.mp4").exists())
        assertTrue(disk.recoveryFiles(project).isEmpty())
    }

    @Test fun preservedFilesAreListedInOrderAndDeletedOneByOne() {
        val disk = disk()
        val project = Project("Trip")
        disk.save(project)
        val folder = disk.folder(project.id)
        for (name in listOf("one.mp4", "two.mov")) File(folder, name).writeText(name)
        File(folder, "one.events.json").writeText("[]")
        disk.quarantineRecording(File(folder, "one.mp4"), project)
        disk.quarantineRecording(File(folder, "two.mov"), project)
        val files = disk.recoveryFiles(project)
        assertEquals(setOf("one.mp4", "two.mov"), files.map { it.name }.toSet())
        assertEquals(files.sortedBy { it.path }, files)

        val one = files.first { it.name == "one.mp4" }
        disk.removeRecoveryFile(one, project)
        assertFalse(one.parentFile.exists())
        assertEquals(listOf("two.mov"), disk.recoveryFiles(project).map { it.name })
        // A file that is not one of the preserved ones is not deleted.
        val outside = File(folder, "keep.mp4").apply { writeText("x") }
        disk.removeRecoveryFile(outside, project)
        assertTrue(outside.exists())
    }
}
