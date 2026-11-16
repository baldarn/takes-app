package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * From the sequence a project saved up to release 0.9.1 to the track timeline
 * it saves since (docs/TRACKS-PLAN.md, S3b): the golden projects, which are
 * what 0.9.1 wrote ([LegacyProject]), opened, edited, undone into, archived,
 * restored and copied by this version.
 *
 * With `TAKES_MIGRATION_OUT` set to a folder, the projects of
 * [writeProjectsForAnOlderCore] are left there, for a core of an older
 * release to try to open.
 */
class TrackMigrationTest {
    @get:Rule val temporary = TemporaryFolder()

    private val goldens = GoldenProjects.all.filter { !it.large }

    /** Every file under [folder] by its path inside it. */
    private fun files(folder: File): Map<String, List<Byte>> =
        folder.walkTopDown().filter { it.isFile }.associate { it.relativeTo(folder).path to it.readBytes().toList() }

    private fun history(folder: File): Map<String, List<Byte>> = files(File(folder, "History"))

    private fun revisions(folder: File): List<File> = File(folder, ".revisions").listFiles().orEmpty().filter { it.isFile && it.extension == "json" }

    /** A project saved as 0.9.1 saved it, with a revision of its manifest from the save before. */
    private fun savedAsBefore(golden: Golden, disk: ProjectDisk): File {
        val project = golden.project
        if (project.canUndo) disk.save(project.undo(project.updatedAt))
        disk.save(project)
        for (name in project.mediaFiles) File(disk.folder(project.id), name).writeBytes(("media " + name).toByteArray())
        return disk.folder(project.id)
    }

    /** One edit of [project] that changes what is seen. */
    private fun edited(project: Project, now: Long = project.updatedAt + 1): Project {
        val edit = project.edit
        val next = if (edit.clips.isEmpty()) error("${project.title} has no clips")
        else TimelineEditing.setColor(edit, 0, ColorSettings(id = "MIGRATED", exposure = 0.375)) ?: TimelineEditing.setColor(edit, 0, ColorSettings(id = "MIGRATED", exposure = -0.25))!!
        return project.commit(next, now).also { check(it !== project) }
    }

    @Test fun anOpenedProjectThatIsNotEditedKeepsEveryByte() {
        val disk = ProjectDisk(File(temporary.root, "Projects"))
        for (golden in goldens) {
            val folder = savedAsBefore(golden, disk)
            val before = files(folder)
            val read = disk.read(folder)
            assertEquals(golden.name, golden.project, read)
            // The app saves what it opened, and that writes nothing.
            disk.save(read)
            disk.save(read.renamed(read.title, read.updatedAt))
            assertEquals(golden.name, before, files(folder))
            // In memory a step read is converted, and still names its file by its old bytes.
            val (_, steps) = GoldenRecord.saved(read)
            assertEquals(golden.name, history(folder).keys, steps.keys)
        }
        assertEquals(goldens.size, disk.loadAll().projects.size)
    }

    @Test fun aProjectEditedOnceKeepsItsOldStepsAddsOneAndPinsItsManifest() {
        val disk = ProjectDisk(File(temporary.root, "Projects"))
        for (golden in goldens) {
            val folder = savedAsBefore(golden, disk)
            val head = File(folder, "project.json").readBytes()
            val oldHistory = history(folder)
            val oldRevisions = revisions(folder).map { it.readBytes().toList() }
            val project = disk.read(folder)
            val changed = edited(project)
            disk.save(changed)

            // Every old step as it was, and one new one: the timeline alone.
            val now = history(folder)
            assertEquals(golden.name, oldHistory, now.filterKeys { it in oldHistory })
            val added = now.keys - oldHistory.keys
            assertEquals(golden.name, 1, added.size)
            val step = now.getValue(added.single()).toByteArray().decodeToString()
            assertTrue(golden.name, step.startsWith("""{"state":{"timeline":{"video":["""))
            assertTrue(golden.name, !step.contains(""""clips":"""))
            assertEquals(golden.name, TrackTimeline.SCHEMA, Project.schemaOf(File(folder, "project.json").readBytes()))

            // The manifest it had, and the revisions before it, are pinned where no older version looks.
            val pinned = disk.pinnedManifests(changed.id).map { it.readBytes().toList() }.toSet()
            assertTrue(golden.name, head.toList() in pinned)
            assertTrue(golden.name, oldRevisions.all { it in pinned })
            assertEquals(golden.name, "schema-${golden.project.schemaVersion}-${sha256(head)}.json", disk.pinnedManifests(changed.id).first { it.readBytes().contentEquals(head) }.name)
            // And no revision is one a version before the timeline can read.
            assertTrue(golden.name, revisions(folder).all { (Project.schemaOf(it.readBytes()) ?: 0) >= TrackTimeline.SCHEMA })
            // The pinned manifest is the whole project as it was.
            val load = { name: String -> File(File(folder, "History"), name).readBytes() }
            assertEquals(golden.name, golden.project, Project.readManifest(head, load))

            // Read back, the new step is the edit that was committed.
            val read = disk.read(folder)
            assertEquals(golden.name, changed, read)
            assertNotNull(read.snapshots.last().timeline)

            // A second edit rotates revisions as before, all of the timeline's schema.
            disk.save(edited(read.undo(read.updatedAt + 1), read.updatedAt + 2))
            assertTrue(golden.name, revisions(folder).isNotEmpty() && revisions(folder).all { (Project.schemaOf(it.readBytes()) ?: 0) >= TrackTimeline.SCHEMA })
            assertEquals(golden.name, oldHistory, history(folder).filterKeys { it in oldHistory })
        }
    }

    @Test fun undoAndRedoAcrossTheBoundaryGiveTheEditsTheyWere() {
        for (golden in goldens) {
            val project = golden.project
            val changed = edited(project)
            val files = HashMap<String, ByteArray>()
            val read = Project.readManifest(changed.manifest { name, data -> files[name] = data }) { files.getValue(it) }
            for (current in listOf(changed, read)) {
                val back = current.undo()
                assertEquals(golden.name, project.edit, back.edit)
                assertEquals(golden.name, project.history, back.history.take(project.snapshots.size))
                assertEquals(golden.name, changed.edit, back.redo().edit)
                // Undone into an old step and edited from there: one more timeline step, the old ones as they were.
                if (project.canUndo) {
                    val earlier = back.undo()
                    val branched = edited(earlier, current.updatedAt + 5)
                    assertEquals(golden.name, project.snapshots.take(project.historyCursor), branched.snapshots.take(project.historyCursor))
                    assertNotNull(golden.name, branched.snapshots.last().timeline)
                    assertEquals(golden.name, project.history[project.historyCursor - 1], branched.undo().edit)
                }
            }
        }
    }

    @Test fun anArchiveRestoresAndACopyDuplicatesTheEditsAsTheyWere() {
        val root = File(temporary.root, "Projects")
        val disk = ProjectDisk(root)
        for (golden in goldens) {
            val folder = savedAsBefore(golden, disk)
            val changed = edited(disk.read(folder))
            disk.save(changed)
            val zip = ByteArrayOutputStream()
            ProjectArchive.write(folder, changed, zip)
            val restored = ProjectRestore.restore(ByteArrayInputStream(zip.toByteArray()), root, Long.MAX_VALUE)
            val copy = ProjectDuplicate.duplicate(changed, root, "Copy")
            for (other in listOf(restored, copy, disk.read(disk.folder(restored.id)), disk.read(disk.folder(copy.id)))) {
                assertEquals(golden.name, changed.snapshots, other.snapshots)
                assertEquals(golden.name, changed.historyCursor, other.historyCursor)
                assertEquals(golden.name, changed.history, other.history)
                assertEquals(golden.name, changed.snapshots.map { it.data().toList() }, other.snapshots.map { it.data().toList() })
            }
            // The steps keep their files: the old ones by their old bytes.
            assertEquals(golden.name, history(folder), history(disk.folder(restored.id)))
            assertEquals(golden.name, history(folder), history(disk.folder(copy.id)))
        }
    }

    /** The edit of every step, saved as a timeline and read back, renders as it did ([TrackRenderPlan]). */
    @Test fun everyStepSavedAsATimelineRendersAsItDid() {
        val moved = mutableListOf<String>()
        for (golden in goldens) {
            golden.project.history.forEachIndexed { index, state ->
                val step = EditSnapshot.committed(state, 0)
                assertNotNull("${golden.name} step $index", step.timeline)
                val read = EditSnapshot.decoded(step.data())
                assertEquals("${golden.name} step $index", step, read)
                assertEquals("${golden.name} step $index", step.timeline, state.toTimeline())
                // Saved again it is the same step, with the same bytes.
                assertEquals("${golden.name} step $index", step.data().toList(), EditSnapshot.committed(read.state, 0).data().toList())
                // Which of the two sound tracks carries a sound item is not saved (D4): they are laid by first fit, and the mix is the same.
                val before = soundTracksAsOne(GoldenRecord.plan(TrackRenderPlan.of(state)))
                val after = soundTracksAsOne(GoldenRecord.plan(TrackRenderPlan.of(read.state)))
                if (before != after) moved += "${golden.name} step $index\n" + before.lines().zip(after.lines()).filter { it.first != it.second }.take(6).joinToString("\n") { "  ${it.first}\n  ${it.second}" } +
                    "\n  ${state.audio}\n  ${read.state.audio}"
            }
        }
        assertEquals(emptyList<String>(), moved)
    }

    private fun soundTracksAsOne(plan: String): String {
        val (tracks, rest) = plan.lines().partition { it.startsWith("sequence Soundtrack ") }
        return (rest + tracks.map { it.replace(Regex("^sequence Soundtrack \\d+"), "sequence Soundtrack") }.sorted()).joinToString("\n")
    }

    @Test fun anExportOfTheEditBeforeStillMatchesTheEditSavedAsATimeline() {
        val folder = temporary.newFolder("P")
        for (golden in goldens) {
            val project = golden.project
            val receipt = ExportReceipts.receipt(project, "${golden.name}.mp4", emptyMap(), 1920, 1080, ExportSettings.Codec.H264, null)
            ExportReceipts.exports(folder).mkdirs()
            ExportReceipts.movie(folder, receipt).writeBytes(byteArrayOf(0))
            // The same edit, committed now: its numbers settled as the timeline keeps them.
            val step = EditSnapshot.committed(project.edit, project.captureLedger.size)
            val again = project.copy(snapshots = project.snapshots.take(project.historyCursor + 1) + step, historyCursor = project.historyCursor + 1)
            assertEquals(golden.name, receipt, ExportReceipts.matching(again, listOf(receipt), folder))
            assertNull(golden.name, ExportReceipts.matching(edited(project), listOf(receipt), folder))
        }
    }

    @Test fun committingTheSameEditAgainIsNoStep() {
        for (golden in goldens) {
            val changed = edited(golden.project)
            assertTrue(golden.name, changed.commit(changed.edit) === changed)
            assertTrue(golden.name, golden.project.commit(golden.project.edit) === golden.project)
        }
    }

    /**
     * The budgets of docs/TRACKS-PLAN.md, 7.2, on the long golden project
     * (2,000 clips, 300 steps): a commit and its save under 100 ms. Measured
     * here on the host, after a warm-up, the best of five.
     */
    @Test fun aCommitOfTheLongProjectAndItsSaveAreWithinBudget() {
        val long = GoldenProjects.all.single { it.large }.project
        val disk = ProjectDisk(File(temporary.root, "Projects"))
        disk.save(long)
        val folder = disk.folder(long.id)
        var project = disk.read(folder)
        fun best(times: Int, work: () -> Unit): Double = (1..times).minOf { val start = System.nanoTime(); work(); (System.nanoTime() - start) / 1e6 }
        var exposure = 0.0
        fun commitAndSave(): Double {
            exposure += 0.01
            // The edit is made by the editing functions before the commit, and is not the commit's cost.
            val next = TimelineEditing.setColor(project.edit, 0, ColorSettings(id = "LONG", exposure = exposure))!!
            val start = System.nanoTime()
            project = project.commit(next, project.updatedAt + 1)
            disk.save(project)
            return (System.nanoTime() - start) / 1e6
        }
        repeat(3) { commitAndSave() }
        val commit = (1..5).minOf { commitAndSave() }
        val open = best(3) { disk.read(folder) }
        println("Long project: commit and save %.1f ms, open %.1f ms".format(commit, open))
        // A wall-clock budget fails by chance on a shared machine; it is enforced only where someone asks (TAKES_STRICT_TIMING=1).
        if (System.getenv("TAKES_STRICT_TIMING") == "1") assertTrue("commit and save took $commit ms", commit < 100)
    }

    /**
     * Leaves, under `TAKES_MIGRATION_OUT`, three projects as folders of a
     * library: one saved as 0.9.1 saved it (`unedited`), the same edited once
     * by this version (`edited`), and the manifest pinned at that edit put
     * back as the head of a copy (`pinned`).
     */
    @Test fun writeProjectsForAnOlderCore() {
        val out = System.getenv("TAKES_MIGRATION_OUT")?.let(::File) ?: return
        out.deleteRecursively()
        out.mkdirs()
        // One that release 0.6 could read (its newest schema was 13), with a history.
        val golden = goldens.filter { it.project.schemaVersion <= 13 && it.project.canUndo }.maxBy { it.project.snapshots.size }
        File(out, "golden.txt").writeText(golden.name)
        for (name in listOf("unedited", "edited", "pinned")) {
            val disk = ProjectDisk(File(out, name))
            val folder = savedAsBefore(golden, disk)
            if (name == "unedited") continue
            disk.save(edited(disk.read(folder)))
            if (name == "pinned") {
                val pinned = disk.pinnedManifests(golden.project.id).first { Project.schemaOf(it.readBytes()) == golden.project.schemaVersion && Project.readManifest(it.readBytes()) { n -> File(File(folder, "History"), n).readBytes() } == golden.project }
                pinned.copyTo(File(folder, "project.json"), overwrite = true)
            }
        }
    }
}
