package app.takes.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private typealias Reason = ProjectRestoreException.Reason

class ProjectRestoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root get() = File(temporary.root, "Projects")
    private fun disk() = ProjectDisk(root)

    /** Incompressible, like real media, and different for every file. */
    private fun bytes(count: Int, seed: Long) = ByteArray(count).also { Random(seed).nextBytes(it) }

    private val take = SourceMedia(filename = "take one.mp4", duration = 4.0)
    private val scene = SourceMedia(filename = "scene.mp4", duration = 4.0)
    private val music = SourceMedia(filename = "music.m4a", duration = 30.0, audioOnly = true)
    private val lut = ImportedLUT("effect-lut-0a1b.cube", "Film")
    private val contents = mapOf(
        take.filename to bytes(1_500_000, 1), scene.filename to bytes(70_000, 2),
        music.filename to bytes(1, 3), lut.filename to "LUT_3D_SIZE 2\n".toByteArray(),
    )

    private fun save(project: Project): Project {
        disk().save(project)
        for (name in project.mediaFiles) File(disk().folder(project.id), name).writeBytes(contents.getValue(name))
        return project
    }

    private fun archive(project: Project): ByteArray =
        ByteArrayOutputStream().also { ProjectArchive.write(disk().folder(project.id), project, it) }.toByteArray()

    /** Two takes with a dissolve between them. */
    private fun trip(): Project {
        val project = Project("Trip").addRecording(take).addRecording(scene)
        return project.commit(TimelineEditing.setTransition(project.edit, 0, 1.0, TransitionStyle.Dissolve)!!)
    }

    private fun restore(data: ByteArray, maximumBytes: Long = Long.MAX_VALUE, limits: ProjectRestore.Limits = ProjectRestore.Limits()) =
        ProjectRestore.restore(ByteArrayInputStream(data), root, maximumBytes, limits, now = 42)

    /** The archive as it was written: the hash of each file becomes part of its source. */
    private fun expected(project: Project, restored: Project) = project.copy(
        id = restored.id, updatedAt = 42,
        sources = project.sources.map { it.copy(sha256 = sha256(contents.getValue(it.filename))) },
    )

    private fun unzip(data: ByteArray): LinkedHashMap<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(data)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes() }
        }
        return entries
    }

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            for ((name, data) in entries) { zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
        }
    }.toByteArray()

    private fun zip(entries: Map<String, ByteArray>) = zip(entries.toList())

    /** Every file and folder beside the projects too: nothing may escape the library either. */
    private fun everything(): List<String> = temporary.root.walkTopDown().sortedBy { it.path }
        .map { it.relativeTo(temporary.root).path + if (it.isFile) " " + sha256(it.readBytes()) else "/" }.toList()

    /** A refused archive changes nothing on disk and leaves no work folder behind. */
    private fun <T : Throwable> refused(type: Class<T>, data: ByteArray, maximumBytes: Long = Long.MAX_VALUE, limits: ProjectRestore.Limits = ProjectRestore.Limits()): T {
        root.mkdirs()
        val before = everything()
        val error = assertThrows(type) { restore(data, maximumBytes, limits) }
        assertEquals(before, everything())
        return error
    }

    private fun refused(reason: Reason, data: ByteArray, maximumBytes: Long = Long.MAX_VALUE, limits: ProjectRestore.Limits = ProjectRestore.Limits()) =
        assertEquals(reason, refused(ProjectRestoreException::class.java, data, maximumBytes, limits).reason)

    private fun invalid(data: ByteArray) =
        assertEquals(ProjectException.Reason.InvalidDocument, refused(ProjectException::class.java, data).reason)

    @Test fun restoredProjectIsACopyWithItsOwnId() {
        val project = save(trip())
        val before = everything()
        val restored = restore(archive(project))

        assertNotEquals(project.id, restored.id)
        assertEquals(expected(project, restored), restored)
        assertEquals(project.edit, restored.edit)
        // What is on disk is what was returned, in a folder named for it.
        val folder = disk().folder(restored.id)
        assertEquals(restored, disk().read(folder))
        // The media, the manifest and its history: no timeline, no hashes, no revision of another project.
        assertEquals(listOf("History", "project.json", "scene.mp4", "take one.mp4"), folder.list()!!.sorted())
        assertEquals(project.snapshots.map { sha256(it.data()) + ".json" }.toSet(), File(folder, "History").list()!!.toSet())
        for (name in project.mediaFiles) assertArrayEquals(contents[name], File(folder, name).readBytes())

        // The original is as it was, and the library has both.
        assertEquals(before, everything().filter { !it.startsWith("Projects/${restored.id}") })
        assertEquals(setOf(project.id, restored.id), root.list()!!.toSet())
        val library = disk().loadAll()
        assertEquals(setOf(project, restored), library.projects.toSet())
        assertTrue(library.unavailable.isEmpty())
    }

    @Test fun historyUndoAndRedoComeBack() {
        var project = trip()
        project = project.commit(TimelineEditing.setTransition(project.edit, 0, 0.5, TransitionStyle.FadeColor)!!)
        project = save(project.undo().renamed("Trip to the sea"))
        assertTrue(project.canUndo && project.canRedo)

        val restored = restore(archive(project))
        assertEquals(project.history, restored.history)
        assertEquals(project.historyCursor, restored.historyCursor)
        assertTrue(restored.canUndo && restored.canRedo)
        assertEquals(project.redo().edit, disk().read(disk().folder(restored.id)).redo().edit)
        assertEquals(expected(project, restored), restored)
    }

    @Test fun importedLutComesBackEvenWhenOnlyUndoRefersToIt() {
        var project = Project("Graded").addRecording(take)
        project = save(project.commit(TimelineEditing.setColor(project.edit, 0, ColorSettings(imported = lut))!!).undo())
        assertEquals(listOf(lut.filename), project.effectFiles)

        val restored = restore(archive(project))
        assertEquals(expected(project, restored), restored)
        assertEquals(restored, disk().read(disk().folder(restored.id)))
        assertArrayEquals(contents[lut.filename], File(disk().folder(restored.id), lut.filename).readBytes())
    }

    @Test fun soundOnItsOwnTracksComesBack() {
        var project = trip().addSource(music)
        project = save(project.commit(TimelineEditing.addAudio(project.edit, music.id, music.duration, 2.0)!!))
        assertEquals(1, project.edit.audio!!.size)

        val restored = restore(archive(project))
        assertEquals(expected(project, restored), restored)
        assertEquals(project.edit.audio, disk().read(disk().folder(restored.id)).edit.audio)
        assertArrayEquals(contents[music.filename], File(disk().folder(restored.id), music.filename).readBytes())
        // The hash that was checked travels with an imported sound too.
        assertEquals(sha256(contents.getValue(music.filename)), restored.sources.last().sha256)
    }

    @Test fun restoringTwiceGivesTwoProjects() {
        val project = save(trip())
        val data = archive(project)
        val first = restore(data)
        val second = restore(data)
        assertEquals(3, setOf(project.id, first.id, second.id).size)
        assertEquals(first, disk().read(disk().folder(first.id)))
        assertEquals(second, disk().read(disk().folder(second.id)))
        assertEquals(3, disk().loadAll().projects.size)
        // A restored project archives and restores again.
        val third = restore(archive(first))
        assertEquals(first.copy(id = third.id), third)
    }

    @Test fun emptyProjectRestoresIntoAFolderThatIsNotThereYet() {
        val project = Project("Empty")
        val other = ProjectDisk(File(temporary.root, "Elsewhere"))
        other.save(project)
        val data = ByteArrayOutputStream().also { ProjectArchive.write(other.folder(project.id), project, it) }.toByteArray()
        assertFalse(root.exists())
        val restored = restore(data)
        assertEquals(project.copy(id = restored.id, updatedAt = 42), disk().read(disk().folder(restored.id)))
        assertEquals(listOf(restored.id), root.list()!!.toList())
    }

    @Test fun entriesMayComeInAnyOrderAndWithoutTheTimeline() {
        val project = save(trip())
        val entries = unzip(archive(project))
        // The iPhone app writes the history before the manifest.
        val restored = restore(zip(entries.toList().sortedBy { !it.first.startsWith("History/") }.reversed()))
        assertEquals(expected(project, restored), restored)
        // An archive is still one without the timeline for other editors.
        val bare = restore(zip(entries - "Timeline.otio"))
        assertEquals(expected(project, bare), bare)
    }

    @Test fun namesThatLeaveTheFolderAreRefused() {
        val entries = unzip(archive(save(trip())))
        val uuid = "0A8E2C41-7D0B-4B7E-9D0C-0A1B2C3D4E5F"
        val names = listOf(
            "../escape.mp4", "/escape.mp4", "..", ".", "a/../escape.mp4", "History/../escape.mp4", "folder/escape.mp4",
            "back\\slash.mp4", "..\\escape.mp4", "C:\\escape.mp4", ".hidden", "History", "history", "History/escape.mp4",
            "History/" + "A".repeat(64) + ".json", "History/x/" + "a".repeat(64) + ".json", "Recovery/$uuid/take.mov",
            "x".repeat(256), "nul\u0000.mp4",
        )
        for (name in names) refused(Reason.NotABackup, zip(entries.toList() + (name to byteArrayOf(1))))
        // Before anything else in the archive, too.
        refused(Reason.NotABackup, zip(listOf("../escape.mp4" to byteArrayOf(1)) + entries.toList()))
        assertFalse(File(temporary.root, "escape.mp4").exists())
    }

    @Test fun foldersAreNotEntries() {
        val entries = unzip(archive(save(trip()))).toList()
        for (name in listOf("History/", "Media/")) {
            val data = ByteArrayOutputStream().also { output ->
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry(name)); zip.closeEntry()
                    for ((entry, bytes) in entries) { zip.putNextEntry(ZipEntry(entry)); zip.write(bytes); zip.closeEntry() }
                }
            }.toByteArray()
            refused(Reason.NotABackup, data)
        }
    }

    @Test fun entriesTheProjectDoesNotNameAreRefused() {
        val entries = unzip(archive(save(trip())))
        refused(Reason.NotABackup, zip(entries + ("notes.txt" to byteArrayOf(1))))
        refused(Reason.NotABackup, zip(entries + ("extra.mp4" to bytes(10, 9))))
        // A second timeline is not the one this project would be exported with.
        refused(Reason.NotABackup, zip(entries + ("Timeline-2.otio" to byteArrayOf(1))))
        // A well-formed snapshot that no step of the history uses.
        val stray = EditSnapshot(EditState(), 0).data() + " ".toByteArray()
        refused(Reason.NotABackup, zip(entries + ("History/" + sha256(stray) + ".json" to stray)))
    }

    @Test fun twoEntriesWithOneNameAreRefused() {
        val entries = unzip(archive(save(trip())))
        refused(Reason.NotABackup, zip(entries + ("Scene.mp4" to entries.getValue("scene.mp4"))))
        refused(Reason.NotABackup, zip(entries + ("PROJECT.JSON" to entries.getValue("project.json"))))
        val snapshot = entries.keys.first { it.startsWith("History/") }
        refused(Reason.NotABackup, zip(entries + (snapshot.replace("History", "HISTORY") to entries.getValue(snapshot))))
    }

    @Test fun missingMediaIsRefused() {
        var project = Project("Graded").addRecording(take).addRecording(scene)
        project = save(project.commit(TimelineEditing.setColor(project.edit, 0, ColorSettings(imported = lut))!!))
        val entries = unzip(archive(project))
        for (name in listOf(scene.filename, lut.filename)) {
            val error = refused(ProjectException::class.java, zip(entries - name))
            assertEquals(ProjectException.Reason.MissingMedia, error.reason)
        }
    }

    @Test fun mediaThatDoesNotMatchItsHashIsRefused() {
        var project = Project("Graded").addRecording(take).addRecording(scene)
        project = save(project.commit(TimelineEditing.setColor(project.edit, 0, ColorSettings(imported = lut))!!))
        val entries = unzip(archive(project))
        // Rewritten with a correct ZIP checksum, so only the hash can tell.
        val changed = entries.getValue(scene.filename).clone().also { it[100] = (it[100] + 1).toByte() }
        refused(Reason.DamagedMedia, zip(entries + (scene.filename to changed)))
        refused(Reason.DamagedMedia, zip(entries + (scene.filename to changed.copyOf(changed.size - 1))))
        refused(Reason.DamagedMedia, zip(entries + (lut.filename to "LUT_3D_SIZE 3\n".toByteArray())))
        val integrity = entries.getValue("integrity.json").decodeToString()
        val hash = sha256(contents.getValue(take.filename))
        refused(Reason.DamagedMedia, zip(entries + ("integrity.json" to integrity.replace(hash, hash.reversed()).toByteArray())))
    }

    @Test fun hashesMustCoverTheRecordingsAndNothingElse() {
        val entries = unzip(archive(save(trip())))
        fun integrity(text: String) = zip(entries + ("integrity.json" to text.toByteArray()))
        val takeHash = sha256(contents.getValue(take.filename))
        val sceneHash = sha256(contents.getValue(scene.filename))
        // Every recording has to be vouched for.
        refused(Reason.NotABackup, integrity("""{"version":1,"sha256":{"take one.mp4":"$takeHash"}}"""))
        refused(Reason.NotABackup, integrity("""{"version":1,"sha256":{"take one.mp4":"$takeHash","scene.mp4":"$sceneHash","other.mp4":"$sceneHash"}}"""))
        // Version 2 is the iPhone app's archive with recovery files.
        refused(Reason.NotABackup, integrity("""{"version":2,"sha256":{"take one.mp4":"$takeHash","scene.mp4":"$sceneHash"}}"""))
        refused(Reason.Damaged, integrity("not json"))
        refused(Reason.Damaged, zip(entries - "integrity.json"))
        refused(Reason.Damaged, zip(entries - "project.json"))
    }

    @Test fun tamperedHistoryIsRefused() {
        val entries = unzip(archive(save(trip())))
        val snapshot = entries.keys.last { it.startsWith("History/") }
        // Its name is the hash of what it held.
        invalid(zip(entries + (snapshot to entries.getValue(snapshot).decodeToString().replace("dissolve", "fadeBlack").toByteArray())))
        invalid(zip(entries - snapshot))
        invalid(zip(entries + ("project.json" to "{}".toByteArray())))
        // A manifest that reads but does not validate: the cursor points past the history.
        val manifest = entries.getValue("project.json").decodeToString()
        assertTrue(manifest.contains("\"historyCursor\":1"))
        invalid(zip(entries + ("project.json" to manifest.replace("\"historyCursor\":1", "\"historyCursor\":7").toByteArray())))
    }

    @Test fun oversizedJsonIsRefused() {
        val entries = unzip(archive(save(trip())))
        // Spaces around a document are still JSON, and compress to almost nothing.
        fun padded(name: String, size: Int) = entries.getValue(name) + ByteArray(size - entries.getValue(name).size) { ' '.code.toByte() }
        val defaults = ProjectRestore.Limits()
        assertEquals(20_000_000L, defaults.manifestBytes)
        refused(Reason.TooLarge, zip(entries + ("project.json" to padded("project.json", 20_000_001))))
        refused(Reason.TooLarge, zip(entries + ("integrity.json" to padded("integrity.json", 20_000_001))))
        // At the limit it is only a large manifest.
        val restored = restore(zip(entries + ("project.json" to padded("project.json", 20_000_000))))
        assertEquals(restored, disk().read(disk().folder(restored.id)))

        val snapshot = entries.keys.first { it.startsWith("History/") }
        val size = entries.getValue(snapshot).size.toLong()
        refused(Reason.TooLarge, zip(entries), limits = defaults.copy(snapshotBytes = size - 1))
        // Each snapshot fits, all of them together do not.
        val history = entries.filterKeys { it.startsWith("History/") }.values.sumOf { it.size }.toLong()
        assertEquals(2, entries.keys.count { it.startsWith("History/") })
        refused(Reason.TooLarge, zip(entries), limits = defaults.copy(historyBytes = history - 1))
        restore(zip(entries), limits = defaults.copy(snapshotBytes = entries.filterKeys { it.startsWith("History/") }.values.maxOf { it.size }.toLong(), historyBytes = history))
    }

    @Test fun tooManyEntriesAreRefused() {
        val entries = unzip(archive(save(trip())))
        assertEquals(7, entries.size)
        refused(Reason.TooLarge, zip(entries), limits = ProjectRestore.Limits(entries = 6))
        restore(zip(entries), limits = ProjectRestore.Limits(entries = 7))
        assertEquals(20_000, ProjectRestore.Limits().entries)
    }

    @Test fun totalSizeIsCappedByWhatIsReallyUnpacked() {
        val data = archive(save(trip()))
        val total = unzip(data).values.sumOf { it.size }.toLong()
        refused(Reason.NotEnoughSpace, data, maximumBytes = total - 1)
        refused(Reason.NotEnoughSpace, data, maximumBytes = 0)
        restore(data, maximumBytes = total)

        // 200 MB that travel as 200 KB: the size an entry declares is not asked.
        val bomb = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("bomb.mp4"))
                val zeros = ByteArray(1 shl 20)
                repeat(200) { zip.write(zeros) }
                zip.closeEntry()
            }
        }.toByteArray()
        assertTrue(bomb.size < 1_000_000)
        refused(Reason.NotEnoughSpace, bomb, maximumBytes = 5_000_000)
    }

    @Test fun truncatedArchiveIsRefusedWhereverItIsCut() {
        val project = save(trip())
        val data = archive(project)
        val entries = unzip(data)
        // The list of entries that ends a ZIP is not needed to read it front to back.
        val directory = data.size - 22 - entries.keys.sumOf { 46 + it.toByteArray().size }
        val cuts = (1..40).map { directory * it / 41 } + listOf(100, directory - 100, directory - 1)
        for (cut in cuts) {
            val error = refused(Exception::class.java, data.copyOf(cut))
            assertTrue("$cut: $error", error is ProjectRestoreException && error.reason == Reason.Damaged)
        }
        // Too short to tell what it was.
        refused(Reason.NotABackup, data.copyOf(1))
        refused(Reason.NotABackup, data.copyOf(29))
        // Cut cleanly between two entries: what is missing is the list of hashes.
        refused(Reason.Damaged, zip(entries.toList().dropLast(1)))
        refused(Reason.Damaged, zip(entries.toList().take(1)))
        // Only that closing list is missing: every entry was read and every hash checked.
        val restored = restore(data.copyOf(directory + 10))
        assertEquals(expected(project, restored), restored)
    }

    @Test fun damagedBytesInsideTheArchiveAreRefused() {
        val data = archive(save(trip()))
        // In the middle of the first recording; the ZIP's own checksum notices.
        val damaged = data.clone().also { it[data.size / 2] = (it[data.size / 2] + 1).toByte() }
        refused(Reason.Damaged, damaged)
    }

    @Test fun whatIsNotAnArchiveIsRefused() {
        refused(Reason.NotABackup, ByteArray(0))
        refused(Reason.NotABackup, bytes(10_000, 8))
        refused(Reason.NotABackup, "{\"title\":\"Trip\"}".toByteArray())
        refused(Reason.NotABackup, zip(emptyList()))
        assertEquals("This file is not a project backup.", Reason.NotABackup.message)
    }

    @Test fun progressFollowsTheArchiveAndCanStopIt() {
        val data = archive(save(trip()))
        val reports = mutableListOf<Long>()
        ProjectRestore.restore(ByteArrayInputStream(data), root, Long.MAX_VALUE) { reports += it }
        assertEquals(reports.sorted(), reports)
        // Bytes of the file itself: the end of it is the list of entries, which is not read.
        assertTrue(reports.last() <= data.size && reports.last() > data.size - 2_000)
        assertTrue(reports.first() < 100_000)
        // A large recording reports as it goes.
        assertTrue(reports.size > 10)

        class Cancelled : RuntimeException()
        val before = everything()
        assertThrows(Cancelled::class.java) {
            ProjectRestore.restore(ByteArrayInputStream(data), root, Long.MAX_VALUE) { if (it > 1_000_000) throw Cancelled() }
        }
        assertEquals(before, everything())
    }

    @Test fun cancelAtAnyReportLeavesNothingBehind() {
        // Cancel on the Projects screen throws from the progress report, as the original's Task.checkCancellation().
        val data = archive(save(trip()))
        val before = everything()
        var reports = 0
        val restored = ProjectRestore.restore(ByteArrayInputStream(data), root, Long.MAX_VALUE) { reports++ }
        File(root, restored.id).deleteRecursively()
        assertEquals(before, everything())
        for (at in reportsToCancelAt(reports)) {
            var seen = 0
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                ProjectRestore.restore(ByteArrayInputStream(data), root, Long.MAX_VALUE) { if (seen++ == at) throw java.util.concurrent.CancellationException() }
            }
            assertEquals(before, everything())
        }
    }

    @Test fun failingStreamStopsTheRestoreAndStaysOpen() {
        val data = archive(save(trip()))
        var closed = false
        val input = object : ByteArrayInputStream(data) { override fun close() { closed = true } }
        ProjectRestore.restore(input, root, Long.MAX_VALUE)
        assertFalse(closed)

        val before = everything()
        val failing = object : ByteArrayInputStream(data) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (pos > 1_000_000) throw IOException("The document is no longer available")
                return super.read(bytes, offset, length)
            }
        }
        // Not a damaged backup: the file could not be read.
        val error = assertThrows(IOException::class.java) { ProjectRestore.restore(failing, root, Long.MAX_VALUE) }
        assertEquals("The document is no longer available", error.message)
        assertEquals(before, everything())
    }

    @Test fun workOfAnInterruptedRestoreIsCleared() {
        val project = save(trip())
        // What a restore leaves when the app is stopped in the middle of it.
        val stale = File(root, ".restore-0b0e5c2e-0000-4000-8000-000000000000-1").also { File(it, "ID/History").mkdirs() }
        File(stale, "ID/take one.mp4").writeBytes(bytes(100, 7))
        // Hidden, so it was never taken for a project.
        assertEquals(listOf(project), disk().loadAll().projects)
        ProjectRestore.discardInterrupted(root)
        assertFalse(stale.exists())
        assertEquals(listOf(project.id), root.list()!!.toList())

        stale.mkdirs()
        val restored = restore(archive(project))
        assertEquals(setOf(project.id, restored.id), root.list()!!.toSet())
    }

    @Test fun recentlyDeletedProjectsAreLeftAlone() {
        val project = save(trip())
        val data = archive(project)
        disk().moveToDeleted(project)
        val deleted = File(disk().deletedRoot, project.id)
        val before = deleted.walkTopDown().map { it.path }.toList()

        val restored = restore(data)
        assertNotEquals(project.id, restored.id)
        assertEquals(before, deleted.walkTopDown().map { it.path }.toList())
        // Both can be in the library again.
        disk().restoreDeleted(project.id)
        assertEquals(setOf(project.id, restored.id), disk().loadAll().projects.map { it.id }.toSet())
    }
}
