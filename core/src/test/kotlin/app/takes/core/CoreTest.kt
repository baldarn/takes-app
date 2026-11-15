package app.takes.core

import app.takes.core.ExportSettings.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

class ProjectTest {
    private fun take(name: String, duration: Double) = SourceMedia(filename = "$name.mp4", duration = duration)

    @Test fun takesAppendInCaptureOrder() {
        val a = take("a", 2.0); val b = take("b", 3.5)
        val project = Project("Trip").addRecording(a).addRecording(b)
        assertEquals(listOf(a.id, b.id), project.edit.clips.map { it.sourceID })
        assertEquals(5.5, project.edit.duration, 1e-9)
        assertEquals(5.5, project.capturedDuration, 1e-9)
        assertFalse(project.canUndo)
        project.validate()
    }

    @Test fun recordingIsOutsideUndo() {
        val a = take("a", 4.0); val b = take("b", 2.0)
        var project = Project("Trip").addRecording(a)
        val clip = project.edit.clips[0]
        project = project.commit(project.edit.copy(clips = listOf(clip.copy(end = 1.0))))
        project = project.addRecording(b)
        assertEquals(listOf(1.0, 2.0), project.edit.clips.map { it.duration })
        project = project.undo()
        // Undo restores the trimmed take and keeps the later recording.
        assertEquals(listOf(4.0, 2.0), project.edit.clips.map { it.duration })
        project = project.redo()
        assertEquals(listOf(1.0, 2.0), project.edit.clips.map { it.duration })
        assertFalse(project.canRedo)
        project.validate()
    }

    @Test fun staleEditKeepsTakesRecordedMeanwhile() {
        val a = take("a", 4.0); val b = take("b", 2.0)
        var project = Project("Trip").addRecording(a)
        val stale = project.edit
        project = project.addRecording(b)
        project = project.commit(stale.copy(clips = emptyList()))
        assertEquals(listOf(b.id), project.edit.clips.map { it.sourceID })
        assertEquals(project, project.commit(project.edit))
    }

    @Test fun commitDropsRedo() {
        val a = take("a", 4.0)
        var project = Project("Trip").addRecording(a)
        val clip = project.edit.clips[0]
        project = project.commit(EditState(listOf(clip.copy(end = 1.0)), 1)).undo()
        assertTrue(project.canRedo)
        project = project.commit(EditState(listOf(clip.copy(end = 2.0)), 1))
        assertFalse(project.canRedo)
        assertEquals(2, project.snapshots.size)
    }

    @Test fun validationRejectsUnsafeDocuments() {
        fun rejected(project: Project) { assertThrows(ProjectException::class.java) { project.validate() } }
        val a = take("a", 2.0)
        rejected(Project("x").addRecording(a.copy(filename = "../a.mp4")))
        rejected(Project("x").addRecording(a.copy(filename = ".hidden.mp4")))
        rejected(Project("x").addRecording(a.copy(filename = "project.json")))
        rejected(Project("x").addRecording(a.copy(duration = 0.0)))
        rejected(Project("x").addRecording(a.copy(duration = Double.NaN)))
        rejected(Project("x").addRecording(a).addRecording(a))
        rejected(Project("x").addRecording(a).addRecording(take("A", 1.0).copy(filename = "A.MP4")))
        rejected(Project("x").addRecording(a.copy(events = listOf(CaptureEvent(time = 9.0, kind = "camera", detail = "")))))
        rejected(Project("x", schemaVersion = Project.LATEST_READABLE_SCHEMA + 1))
        rejected(Project("x").copy(historyCursor = 1))
        val project = Project("x").addRecording(a)
        rejected(project.commit(EditState(listOf(project.edit.clips[0].copy(end = 5.0)), 1)))
        rejected(project.copy(captureLedger = listOf(TimelineClip(sourceID = "missing", start = 0.0, end = 1.0))))
    }

    @Test fun exportSizeFitsTheFormatInEitherOrientation() {
        assertEquals(1080 to 1920, Resolution.Project.size(1080, 1920))
        assertEquals(720 to 1280, Resolution.HD.size(1080, 1920))
        assertEquals(1280 to 720, Resolution.HD.size(1920, 1080))
        assertEquals(2160 to 3840, Resolution.UltraHD.size(1080, 1920))
        assertEquals(1440 to 1080, Resolution.FullHD.size(640, 480))   // 4:3 is limited by its short side
        assertEquals(1920 to 800, Resolution.FullHD.size(2400, 1000))  // wide is limited by its long side
        assertEquals(720 to 720, Resolution.HD.size(999, 999))
        assertEquals(0 to 0, Resolution.HD.size(0, 0))
        assertTrue(listOf(Resolution.HD, Resolution.FullHD, Resolution.UltraHD).all { format -> format.size(1081, 1923).let { it.first % 2 == 0 && it.second % 2 == 0 } })
    }

    @Test fun exportSettingsAreSavedWithTheProject() {
        val settings = ExportSettings(ExportSettings.Codec.HEVC, ExportSettings.Resolution.HD, 24)
        val project = Project("Trip").addRecording(take("a", 2.0)).copy(exportSettings = settings)
        val files = mutableMapOf<String, ByteArray>()
        val manifest = project.manifest { name, data -> files[name] = data }
        assertTrue(manifest.decodeToString().contains("\"codec\":\"H.265\""))
        assertEquals(settings, Project.readManifest(manifest) { files.getValue(it) }.exportSettings)
        // Projects saved before export settings existed open with the defaults.
        val old = manifest.decodeToString().replace(Regex(",\"exportSettings\":\\{[^}]*\\}"), "").toByteArray()
        assertEquals(ExportSettings(), Project.readManifest(old) { files.getValue(it) }.exportSettings)
        assertThrows(ProjectException::class.java) { project.copy(exportSettings = settings.copy(frameRate = 31)).validate() }
    }

    @Test fun recoveredEventsStayInsideTheTake() {
        val events = listOf(-1.0, 0.0, 1.5, 2.0, Double.NaN).map { CaptureEvent(time = it, kind = "camera", detail = "") }
        assertEquals(listOf(0.0, 1.5), CaptureEvent.recovered(events, 2.0).map { it.time })
    }
}

class ProjectDiskTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun disk() = ProjectDisk(File(temporary.root, "Projects"))
    private fun take(name: String, duration: Double) = SourceMedia(filename = "$name.mp4", duration = duration)

    @Test fun recordPauseStopReopenContinue() {
        val disk = disk()
        var project = Project("Trip").addRecording(take("a", 2.0)).addRecording(take("b", 3.0))
        disk.save(project)
        project = disk.loadAll().projects.single()
        project = project.addRecording(take("c", 1.0))
        disk.save(project)
        val reopened = disk.loadAll().projects.single()
        assertEquals(project, reopened)
        assertEquals(listOf("a.mp4", "b.mp4", "c.mp4"), reopened.edit.clips.map { clip -> reopened.sources.first { it.id == clip.sourceID }.filename })
        assertEquals(6.0, reopened.edit.duration, 1e-9)
    }

    @Test fun historyIsContentAddressedAndSurvivesReload() {
        val disk = disk()
        var project = Project("Trip").addRecording(take("a", 4.0))
        disk.save(project)
        project = project.commit(EditState(listOf(project.edit.clips[0].copy(end = 1.0)), 1))
        disk.save(project)
        val history = File(disk.folder(project.id), "History")
        assertEquals(2, history.listFiles()!!.size)
        assertTrue(history.listFiles()!!.all { Project.isSnapshotName(it.name) })
        val stamps = history.listFiles()!!.associate { it.name to it.lastModified() }
        disk.save(project.renamed("Trip 2"))
        assertEquals(stamps, history.listFiles()!!.associate { it.name to it.lastModified() })
        val reopened = disk.read(disk.folder(project.id))
        assertEquals("Trip 2", reopened.title)
        assertTrue(reopened.canUndo)
        assertEquals(4.0, reopened.undo().edit.duration, 1e-9)
    }

    @Test fun damagedHeadFallsBackToPreviousRevision() {
        val disk = disk()
        val first = Project("Trip").addRecording(take("a", 4.0))
        disk.save(first)
        disk.save(first.addRecording(take("b", 1.0)))
        File(disk.folder(first.id), "project.json").writeText("{ truncated")
        assertEquals(first, disk.read(disk.folder(first.id)))
    }

    /**
     * A project saved by a later version must be refused, never opened from an
     * older revision: the saves that would follow push the newer work out of
     * the kept revisions.
     */
    @Test fun aHeadFromANewerVersionIsRefusedAndNeverReplacedByARevision() {
        val disk = disk()
        val first = Project("Trip").addRecording(take("a", 4.0))
        disk.save(first)
        disk.save(first.addRecording(take("b", 1.0)))
        val head = File(disk.folder(first.id), "project.json")
        val newer = head.readText().replaceFirst(Regex("\"schemaVersion\"\\s*:\\s*\\d+"), "\"schemaVersion\": ${Project.LATEST_READABLE_SCHEMA + 1}")
        assertTrue(newer != head.readText())
        head.writeText(newer)
        val files = disk.folder(first.id).walkTopDown().filter { it.isFile }.associate { it.path to it.readBytes().toList() }
        val refused = runCatching { disk.read(disk.folder(first.id)) }.exceptionOrNull() as? ProjectException
        assertEquals(ProjectException.Reason.NewerVersion, refused?.reason)
        val library = disk.loadAll()
        assertTrue(library.projects.isEmpty())
        assertEquals(ProjectException.Reason.NewerVersion.message, library.unavailable[first.id])
        // Nothing on disk was touched by looking.
        assertEquals(files, disk.folder(first.id).walkTopDown().filter { it.isFile }.associate { it.path to it.readBytes().toList() })
    }

    @Test fun onlyADocumentThatSaysSoIsNewer() {
        assertTrue(Project.isNewer("{\"schemaVersion\": ${Project.LATEST_READABLE_SCHEMA + 1}}".toByteArray()))
        assertTrue(!Project.isNewer("{\"schemaVersion\": ${Project.LATEST_READABLE_SCHEMA}}".toByteArray()))
        assertTrue(!Project.isNewer("{ truncated".toByteArray()))
        assertTrue(!Project.isNewer("[]".toByteArray()))
    }

    @Test fun tamperedSnapshotIsReportedNotLoaded() {
        val disk = disk()
        val project = Project("Trip").addRecording(take("a", 4.0))
        disk.save(project)
        File(disk.folder(project.id), "History").listFiles()!!.single().writeText("{\"state\":{\"clips\":[]},\"captureCursor\":0} ")
        val library = disk.loadAll()
        assertTrue(library.projects.isEmpty())
        assertNotNull(library.unavailable[project.id])
        // Saving again repairs the snapshot from memory.
        disk.save(project)
        assertEquals(project, disk.loadAll().projects.single())
    }

    @Test fun invalidProjectIsNeverWritten() {
        val disk = disk()
        assertThrows(ProjectException::class.java) { disk.save(Project("x").addRecording(take("a", -1.0))) }
        assertTrue(disk.loadAll().projects.isEmpty())
    }

    @Test fun deleteRestoreErase() {
        val disk = disk()
        val project = Project("Trip").addRecording(take("a", 4.0))
        disk.save(project)
        File(disk.folder(project.id), "a.mp4").writeText("video")
        disk.moveToDeleted(project, now = 1234)
        assertTrue(disk.loadAll().projects.isEmpty())
        assertEquals(project, disk.loadAll(disk.deletedRoot).projects.single())
        assertEquals(ProjectDeletion(project.id, 1234), disk.deletionRecord(project.id))
        disk.restoreDeleted(project.id)
        assertEquals(project, disk.loadAll().projects.single())
        assertNull(disk.deletionRecord(project.id))
        assertEquals("video", File(disk.folder(project.id), "a.mp4").readText())
        disk.moveToDeleted(project)
        disk.eraseDeleted(project.id)
        assertTrue(disk.deletedRoot.listFiles()!!.isEmpty())
    }

    @Test fun interruptedPurgeFinishes() {
        val disk = disk()
        val pending = File(disk.deletedRoot, ".purging-abc").apply { mkdirs() }
        File(pending, "a.mp4").writeText("video")
        disk.finishPendingDeletions()
        assertFalse(pending.exists())
    }

    @Test fun findsRecordingsMissingFromTheManifest() {
        val disk = disk()
        val project = Project("Trip").addRecording(take("a", 4.0))
        disk.save(project)
        val folder = disk.folder(project.id)
        for (name in listOf("a.mp4", "orphan.mp4", ".partial.mp4", "notes.txt")) File(folder, name).writeText("x")
        assertEquals(listOf("orphan.mp4"), disk.unindexedRecordings(project).map { it.name })
    }
}

class ZoomTransitionTest {
    @Test fun reachesTargetOnTimeWithoutOvershoot() {
        for ((start, target) in listOf(1.0 to 8.0, 8.0 to 1.0, 0.5 to 1.0, 3.0 to 3.0)) {
            for (duration in listOf(0.5, 1.0, 5.0, 30.0)) {
                val ramp = ZoomTransition(start, target, 100.0, duration)
                assertTrue(ramp.value(99.0) == start)
                assertTrue(ramp.value(100.0) == start)
                assertTrue(ramp.value(100 + duration) == target)
                assertTrue(ramp.value(110 + duration) == target)
                assertFalse(ramp.isFinished(100 + duration * 0.9))
                assertTrue(ramp.isFinished(100 + duration))
                var previous = start
                for (tick in 1..600) {
                    val value = ramp.value(100 + duration * tick / 600)
                    assertTrue(value >= min(start, target) - 1e-12 && value <= max(start, target) + 1e-12)
                    assertTrue(if (target >= start) value >= previous - 1e-12 else value <= previous + 1e-12)
                    previous = value
                }
                if (target != start) {
                    fun delta(a: Double, b: Double) = abs(log2(ramp.value(100 + b * duration) / ramp.value(100 + a * duration)))
                    assertTrue(delta(0.9, 1.0) < delta(0.8, 0.9))
                    assertTrue(delta(0.8, 0.9) < delta(0.7, 0.8))
                    assertTrue(delta(0.0, 0.1) < delta(0.1, 0.2))
                    assertTrue(delta(0.99, 1.0) < delta(0.49, 0.5) / 100)
                }
                val interruptedAt = 100 + duration * 0.37
                val applied = ramp.value(interruptedAt)
                val replacement = ZoomTransition(applied, start, interruptedAt, 1.0)
                assertTrue(replacement.value(interruptedAt) == applied)
                assertTrue(replacement.value(interruptedAt + 1) == start)
            }
        }
        assertTrue(ZoomTransition(1.0, 3.0, 0.0, 0.0).value(0.0) == 3.0)
    }

    @Test fun durationsAndPresets() {
        assertEquals(0.0, ZoomTransition.boundedDuration(-1.0), 0.0)
        assertEquals(30.0, ZoomTransition.boundedDuration(31.0), 0.0)
        assertEquals(5.5, ZoomTransition.boundedDuration(5.4), 0.0)
        assertEquals(0.0, ZoomTransition.boundedDuration(Double.NaN), 0.0)
        assertEquals("Instant", ZoomTransition.durationLabel(0.0))
        assertEquals("0.5 s", ZoomTransition.durationLabel(0.5))
        assertEquals("2 s", ZoomTransition.durationLabel(2.0))
        assertEquals(listOf(1.0, 2.0, 3.0, 5.0), ZoomTransition.presets(1.0, 8.0))
        assertEquals(listOf(0.6, 1.0, 2.0, 3.0, 5.0, 10.0), ZoomTransition.presets(0.6, 10.0))
        assertEquals(listOf(1.0), ZoomTransition.presets(1.0, 1.0))
        assertEquals("0.6×", ZoomTransition.zoomLabel(0.6))
        assertEquals("2×", ZoomTransition.zoomLabel(2.04))
    }
}
