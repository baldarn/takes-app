package app.takes.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.URLDecoder
import java.nio.file.Files
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class ProjectArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun disk() = ProjectDisk(File(temporary.root, "Projects"))

    /** Incompressible, like real media, and different for every file. */
    private fun bytes(count: Int, seed: Long) = ByteArray(count).also { Random(seed).nextBytes(it) }

    private val take = SourceMedia(filename = "take one.mp4", duration = 4.0)
    private val scene = SourceMedia(filename = "scene.mp4", duration = 4.0)
    private val music = SourceMedia(filename = "music.m4a", duration = 30.0, audioOnly = true)
    private val contents = mapOf(take.filename to bytes(3_000_000, 1), scene.filename to bytes(70_000, 2), music.filename to bytes(1, 3))

    /** Two takes joined by a dissolve, music under them, and one earlier step of history. */
    private fun saved(): Pair<Project, File> {
        val disk = disk()
        var project = Project("Trip").addRecording(take).addRecording(scene).addSource(music)
        project = project.commit(TimelineEditing.setTransition(project.edit, 0, 1.0, TransitionStyle.Dissolve)!!)
        project = project.commit(TimelineEditing.addAudio(project.edit, music.id, music.duration, 2.0)!!)
        disk.save(project)
        val folder = disk.folder(project.id)
        for ((name, data) in contents) File(folder, name).writeBytes(data)
        // Not part of a project's identity.
        File(folder, "Cache").mkdirs(); File(folder, "Cache/thumbnail.jpg").writeBytes(bytes(10, 4))
        File(folder, "Exports").mkdirs(); File(folder, "Exports/Trip.mp4").writeBytes(bytes(10, 5))
        File(folder, "unindexed.mp4").writeBytes(bytes(10, 6))
        // A second save of a changed manifest leaves a revision behind.
        project = project.renamed("Trip to the sea")
        disk.save(project)
        return project to folder
    }

    private fun archive(project: Project, folder: File, progress: (Long, Long) -> Unit = { _, _ -> }): ByteArray =
        ByteArrayOutputStream().also { ProjectArchive.write(folder, project, it, progress) }.toByteArray()

    private fun unzip(data: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(data)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                assertFalse(entry.isDirectory)
                assertEquals(null, entries.put(entry.name, zip.readBytes()))
            }
        }
        return entries
    }

    @Test fun archiveHoldsTheProjectItsHistoryItsMediaAndTheTimeline() {
        val (project, folder) = saved()
        assertTrue(File(folder, ".revisions").isDirectory)
        val entries = unzip(archive(project, folder))

        val history = File(folder, "History").list()!!.sorted()
        assertEquals(3, history.size)
        assertEquals(
            (listOf("project.json", "Timeline.otio", "take one.mp4", "scene.mp4", "music.m4a", "integrity.json") + history.map { "History/$it" }).sorted(),
            entries.keys.sorted(),
        )
        assertEquals("project.json", entries.keys.first())
        assertEquals("integrity.json", entries.keys.last())

        // The same bytes the project has on disk.
        assertArrayEquals(File(folder, "project.json").readBytes(), entries["project.json"])
        for (name in history) assertArrayEquals(File(folder, "History/$name").readBytes(), entries["History/$name"])
        for ((name, data) in contents) {
            assertEquals(data.size, entries.getValue(name).size)
            assertArrayEquals(data, entries[name])
        }
        assertEquals(OpenTimeline.text(project), entries.getValue("Timeline.otio").decodeToString())

        // What was archived reads back as the same project.
        val restored = Project.readManifest(entries.getValue("project.json")) { entries.getValue("History/$it") }
        assertEquals(project, restored)
    }

    @Test fun integrityListsTheHashOfEveryMediaFile() {
        val (project, folder) = saved()
        val integrity = Json.parseToJsonElement(unzip(archive(project, folder)).getValue("integrity.json").decodeToString()).jsonObject
        assertEquals("1", integrity.getValue("version").jsonPrimitive.content)
        val hashes = integrity.getValue("sha256").jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals(contents.mapValues { sha256(it.value) }, hashes)
    }

    @Test fun timelineMediaResolvesToEntriesBesideIt() {
        val (project, folder) = saved()
        val entries = unzip(archive(project, folder))
        val document = Json.parseToJsonElement(entries.getValue("Timeline.otio").decodeToString()).jsonObject
        val tracks = document.getValue("tracks").jsonObject.getValue("children").jsonArray.map { it.jsonObject }
        assertEquals(listOf("Audio 1", "Audio 2", "Video 1"), tracks.map { it.getValue("name").jsonPrimitive.content })
        fun JsonObject.schema() = getValue("OTIO_SCHEMA").jsonPrimitive.content
        val clips = tracks.flatMap { track -> track.getValue("children").jsonArray.map { it.jsonObject } }.filter { it.schema() == "Clip.2" }
        val urls = clips.map { it.getValue("media_references").jsonObject.getValue("DEFAULT_MEDIA").jsonObject.getValue("target_url").jsonPrimitive.content }
        assertEquals(5, urls.size)
        assertTrue(urls.contains("take%20one.mp4"))
        // Relative to the document, which is at the top of the archive.
        assertEquals(contents.keys, urls.map { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.toSet())
        for (url in urls) assertTrue(url, URLDecoder.decode(url.replace("+", "%2B"), "UTF-8") in entries)
        assertEquals(listOf("Clip.2", "Transition.1", "Clip.2"), tracks.last().getValue("children").jsonArray.map { it.jsonObject.schema() })
    }

    @Test fun mediaIsCopiedNotRecompressedAndJsonIsCompressed() {
        val (project, folder) = saved()
        val file = temporary.newFile("Trip.zip")
        file.outputStream().use { ProjectArchive.write(folder, project, it) }
        ZipFile(file).use { zip ->
            val movie = zip.getEntry("take one.mp4")
            assertEquals(3_000_000L, movie.size)
            // Level 0 adds five bytes for each block and takes nothing away.
            assertTrue(movie.compressedSize in movie.size..movie.size + 1024)
            assertEquals(project.updatedAt / 2000, movie.time / 2000)
            val timeline = zip.getEntry("Timeline.otio")
            assertEquals(ZipEntry.DEFLATED, timeline.method)
            assertTrue(timeline.compressedSize < timeline.size / 2)
            assertArrayEquals(contents["scene.mp4"], zip.getInputStream(zip.getEntry("scene.mp4")).readBytes())
        }
        // Everything is in the entries: the archive is barely larger than its media.
        assertTrue(file.length() < contents.values.sumOf { it.size } + 20_000)
    }

    @Test fun progressCountsMediaBytesUpToTheTotal() {
        val (project, folder) = saved()
        val reports = mutableListOf<Pair<Long, Long>>()
        archive(project, folder) { written, total -> reports += written to total }
        val total = contents.values.sumOf { it.size }.toLong()
        assertEquals(0L to total, reports.first())
        assertEquals(total to total, reports.last())
        assertTrue(reports.all { it.second == total })
        assertEquals(reports.map { it.first }.sorted(), reports.map { it.first })
        // A large file reports as it goes, not once at its end.
        assertTrue(reports.size > contents.size + 1)
    }

    @Test fun callerKeepsItsStream() {
        val (project, folder) = saved()
        var closed = false
        val output = object : ByteArrayOutputStream() { override fun close() { closed = true } }
        ProjectArchive.write(folder, project, output)
        assertFalse(closed)
        assertEquals(6 + 3, unzip(output.toByteArray()).size)
    }

    @Test fun missingMediaIsRefusedBeforeAnythingIsWritten() {
        val (project, folder) = saved()
        assertTrue(File(folder, "scene.mp4").delete())
        val output = ByteArrayOutputStream()
        val error = assertThrows(ProjectException::class.java) { ProjectArchive.write(folder, project, output) }
        assertEquals(ProjectException.Reason.MissingMedia, error.reason)
        assertEquals(0, output.size())
        // A folder with the name of a recording is not a recording either.
        assertTrue(File(folder, "scene.mp4").mkdir())
        assertThrows(ProjectException::class.java) { ProjectArchive.write(folder, project, output) }
        assertEquals(0, output.size())
    }

    @Test fun symbolicLinksAreNeverFollowed() {
        val (project, folder) = saved()
        val secret = temporary.newFile("secret.txt").also { it.writeText("outside the project") }
        val media = File(folder, "scene.mp4")
        assertTrue(media.delete())
        Files.createSymbolicLink(media.toPath(), secret.toPath())
        val output = ByteArrayOutputStream()
        val error = assertThrows(ProjectException::class.java) { ProjectArchive.write(folder, project, output) }
        assertEquals(ProjectException.Reason.MissingMedia, error.reason)
        assertEquals(0, output.size())
        // Not even to a file inside the project.
        assertTrue(media.delete())
        Files.createSymbolicLink(media.toPath(), File(folder, "take one.mp4").toPath())
        assertThrows(ProjectException::class.java) { ProjectArchive.write(folder, project, output) }
    }

    @Test fun invalidProjectIsRefused() {
        val (project, folder) = saved()
        val escaping = project.copy(sources = project.sources + SourceMedia(filename = "../outside.mp4", duration = 1.0))
        assertThrows(ProjectException::class.java) { ProjectArchive.write(folder, escaping, ByteArrayOutputStream()) }
    }

    @Test fun mediaThatChangesDuringTheExportFailsIt() {
        val (project, folder) = saved()
        var grown = false
        assertThrows(IOException::class.java) {
            archive(project, folder) { written, _ ->
                if (written > 0 && !grown) { grown = true; File(folder, "take one.mp4").appendBytes(bytes(100, 7)) }
            }
        }
    }

    @Test fun failingStreamAndProgressStopTheExport() {
        val (project, folder) = saved()
        val full = object : OutputStream() {
            var room = 100_000
            override fun write(byte: Int) { if (--room < 0) throw IOException("No space left") }
        }
        assertThrows(IOException::class.java) { ProjectArchive.write(folder, project, full) }
        class Cancelled : RuntimeException()
        assertThrows(Cancelled::class.java) { archive(project, folder) { written, _ -> if (written > 1_000_000) throw Cancelled() } }
    }

    @Test fun emptyProjectStillArchives() {
        val disk = disk()
        val project = Project("Empty")
        disk.save(project)
        val entries = unzip(archive(project, disk.folder(project.id)))
        assertEquals(listOf("project.json", "History/" + File(disk.folder(project.id), "History").list()!!.single(), "Timeline.otio", "integrity.json"), entries.keys.toList())
    }
}
