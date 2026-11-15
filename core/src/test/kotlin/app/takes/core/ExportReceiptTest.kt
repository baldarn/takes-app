package app.takes.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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

class ExportReceiptTest {
    @get:Rule val temporary = TemporaryFolder()
    private val folder get() = File(temporary.root, "project")
    private val exports get() = File(folder, "Exports")
    private val processing get() = File(folder, "Processing").apply { mkdirs() }

    private val take = SourceMedia(filename = "take.mp4", duration = 10.0, recordedAt = 1)
    private val unused = SourceMedia(filename = "unused.mp4", duration = 10.0, recordedAt = 2)
    private val music = SourceMedia(filename = "music.m4a", duration = 30.0, recordedAt = 3, audioOnly = true)

    private fun project(): Project = Project(title = "Trip", sources = listOf(take, unused, music), createdAt = 1_000).commit(
        EditState(
            listOf(TimelineClip(sourceID = take.id, start = 0.0, end = 4.0), TimelineClip(sourceID = take.id, start = 5.0, end = 9.0)),
            audio = listOf(AudioItem(sourceID = music.id, start = 0.0, sourceStart = 0.0, sourceEnd = 8.0)),
        ),
        now = 2_000,
    )

    private fun receipt(project: Project, name: String, now: Long, peak: Float? = 0.5f) = ExportReceipts.receipt(
        project, name, mapOf(take.id to "a".repeat(64), music.id to "b".repeat(64)), 1920, 1080, ExportSettings.Codec.H264, peak, now,
    )

    /** Renders nothing: a movie of [bytes] bytes appears under Processing and is published. */
    private fun export(project: Project, now: Long, bytes: Int = 10): ExportReceipt {
        val destination = ExportReceipts.newMovie(folder)
        val rendered = File(processing, "rendered-$now.mp4").apply { writeBytes(ByteArray(bytes)) }
        return receipt(project, destination.name, now).also { ExportReceipts.publish(it, rendered, destination) }
    }

    // ---- The audio peak warning (Project.swift:691-695) ----

    // Project.swift:692: no warning at or below 1.0001, nor without a finite measurement.
    @Test fun aPeakAtFullScaleIsNotAWarning() {
        for (peak in listOf(null, 0f, 0.5f, 1f, 1.0001f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertNull(peak.toString(), ExportReceipt.AudioWarning.of(peak))
        }
        assertNotNull(ExportReceipt.AudioWarning.of(1.0002f))
    }

    // Project.swift:693: 20·log10(peak), rounded up to a tenth of a decibel, never below 0.1.
    @Test fun theWarningSaysHowFarOverFullScale() {
        assertEquals("0.1", ExportReceipt.AudioWarning.of(1.0002f)!!.decibels) // 0.0017 dB
        assertEquals("0.1", ExportReceipt.AudioWarning.of(1.01f)!!.decibels)   // 0.086 dB
        assertEquals("0.9", ExportReceipt.AudioWarning.of(1.1f)!!.decibels)    // 0.828 dB
        assertEquals("3.6", ExportReceipt.AudioWarning.of(1.5f)!!.decibels)    // 3.522 dB
        assertEquals("6.1", ExportReceipt.AudioWarning.of(2f)!!.decibels)      // 6.021 dB
        assertEquals("12.1", ExportReceipt.AudioWarning.of(4f)!!.decibels)     // 12.041 dB
        assertEquals("20.0", ExportReceipt.AudioWarning.of(10f)!!.decibels)    // exactly 20 dB
        assertEquals(6.1, ExportReceipt.AudioWarning.of(2f)!!.excessDB, 1e-12)
    }

    // Project.swift:694 and TrackMediaTests.swift:531-540.
    @Test fun theWarningBelongsToTheReceiptOfTheMeasuredMovie() {
        val project = project()
        val loud = receipt(project, "loud.mp4", 1, peak = 2f)
        assertEquals("Peaks are 6.1 dB above full scale. Lower Volume in the editor and export again.", loud.audioWarning!!.text)
        assertNull("Exported again more quietly", receipt(project, "quiet.mp4", 2, peak = 0.9f).audioWarning)
        assertNull("An unmeasured export cannot produce an unverified warning", loud.copy(audioPeak = null).audioWarning)
    }

    // ---- What a receipt records (Project.swift:672-690, MediaRenderer.swift:808-811) ----

    @Test fun aReceiptRecordsTheEditItsSourcesAndTheFormat() {
        val project = project().copy(exportSettings = ExportSettings(ExportSettings.Codec.H264, ExportSettings.Resolution.FullHD, 50))
        val receipt = ExportReceipts.receipt(project, "movie.mp4", mapOf(take.id to "a".repeat(64)), 1920, 1080, ExportSettings.Codec.HEVC, 0.8f, now = 5_000, id = "ID")
        assertEquals(1, receipt.schemaVersion)
        assertEquals("ID", receipt.id)
        assertEquals(5_000, receipt.createdAt)
        assertEquals("movie.mp4", receipt.filename)
        assertEquals(project.id, receipt.projectID)
        assertEquals("Trip", receipt.projectTitle)
        assertEquals(1, receipt.historyRevision)
        assertEquals(project.edit.clips, receipt.edit.clips)
        assertEquals(project.edit.audio, receipt.edit.audio)
        assertEquals("Only what the edit plays, in the project's order", listOf(take, music), receipt.sources)
        assertEquals(50, receipt.framesPerSecond)
        assertEquals(project.exportSettings, receipt.settings)
        assertEquals("The codec the movie really has", ExportSettings.Codec.HEVC, receipt.codec)
        assertEquals(8.0, receipt.duration, 1e-9)
    }

    // Project.swift:672-690: the stored field names.
    @Test fun aReceiptIsStoredUnderTheOriginalsFieldNames() {
        val project = project()
        val receipt = receipt(project, "movie.mp4", 5_000)
        val text = documentJson.encodeToString(ExportReceipt.serializer(), receipt)
        assertEquals(
            setOf(
                "schemaVersion", "id", "createdAt", "filename", "projectID", "projectTitle", "historyRevision", "edit", "sources",
                "sourceChecksums", "width", "height", "framesPerSecond", "settings", "codec", "audioPeak",
            ),
            Json.parseToJsonElement(text).jsonObject.keys,
        )
        assertEquals(receipt, documentJson.decodeFromString(ExportReceipt.serializer(), text))
    }

    // Project.swift:685-690: receipts written before a field existed are still read.
    @Test fun anOlderReceiptIsStillRead() {
        val old = documentJson.decodeFromString(
            ExportReceipt.serializer(),
            """{"schemaVersion":1,"id":"A","createdAt":7,"filename":"old.mp4","projectID":"P","projectTitle":"Old","historyRevision":0,
               "edit":{"clips":[]},"sources":[],"sourceChecksums":{},"width":640,"height":360,"someFutureField":true}""",
        )
        assertEquals(30, old.framesPerSecond)
        assertNull(old.settings)
        assertNull(old.codec)
        assertNull(old.audioPeak)
        assertNull(old.audioWarning)
    }

    // ---- Publishing and listing (MediaRenderer.swift:738, 812-826; EditorController.swift:789-799) ----

    @Test fun publishingPutsTheReceiptBesideTheMovie() {
        val project = project()
        val published = export(project, now = 5_000, bytes = 1234)
        val movie = ExportReceipts.movie(folder, published)
        assertTrue(movie.isFile)
        assertEquals(exports, movie.parentFile)
        assertTrue(Regex("[0-9A-F-]{36}\\.mp4").matches(movie.name))
        assertEquals(File(exports, movie.nameWithoutExtension + ".json"), ExportReceipts.receiptFile(movie))
        assertEquals(1234L, ExportReceipts.bytes(folder, published))
        assertEquals("Nothing is left in Processing", emptyList<String>(), processing.list()!!.toList())
        assertEquals(listOf(published), ExportReceipts.list(folder))
    }

    // MediaRenderer.swift:738: neither the movie nor its receipt may be overwritten.
    @Test fun aTakenNameIsRefused() {
        val project = project()
        val first = export(project, now = 5_000)
        val movie = ExportReceipts.movie(folder, first)
        val rendered = File(processing, "again.mp4").apply { writeText("again") }
        assertFalse(ExportReceipts.isFree(movie))
        assertThrows(FileAlreadyExistsException::class.java) { ExportReceipts.publish(first, rendered, movie) }
        // A receipt alone also takes the name.
        val orphan = File(exports, "orphan.mp4")
        File(exports, "orphan.json").writeText("{}")
        assertThrows(FileAlreadyExistsException::class.java) { ExportReceipts.publish(first, rendered, orphan) }
        assertTrue(rendered.isFile)
    }

    // MediaRenderer.swift:817-824: if the movie cannot be moved, its receipt is taken back.
    @Test fun aFailedPublishLeavesNoReceipt() {
        val project = project()
        val destination = ExportReceipts.newMovie(folder)
        val missing = File(processing, "never-rendered.mp4")
        assertThrows(Exception::class.java) { ExportReceipts.publish(receipt(project, destination.name, 1), missing, destination) }
        assertFalse(ExportReceipts.receiptFile(destination).exists())
        assertFalse(destination.exists())
        assertEquals(emptyList<ExportReceipt>(), ExportReceipts.list(folder))
    }

    // EditorController.swift:789-799.
    @Test fun theListIsNewestFirstAndOnlyOfMoviesThatExist() {
        val project = project()
        assertEquals("No Exports folder yet", emptyList<ExportReceipt>(), ExportReceipts.list(folder))
        val old = export(project, now = 1_000)
        val new = export(project, now = 3_000)
        val middle = export(project, now = 2_000)
        assertEquals(listOf(new, middle, old), ExportReceipts.list(folder))

        // A receipt whose movie is gone, an unreadable one, and one pointing outside the folder are ignored.
        ExportReceipts.movie(folder, middle).delete()
        File(exports, "broken.json").writeText("{")
        File(folder, "take.mp4").writeText("take")
        File(exports, "escape.json").writeText(documentJson.encodeToString(ExportReceipt.serializer(), old.copy(id = "E", filename = "../take.mp4")))
        // The saved work of a paused export is not an export.
        ExportCheckpoint(folder).prepare(project)
        assertEquals(listOf(new, old), ExportReceipts.list(folder))
    }

    // EditorController.swift:800-807.
    @Test fun theCurrentEditIsExportedOnlyWhileNothingChanges() {
        val project = project()
        val published = export(project, now = 5_000)
        val receipts = ExportReceipts.list(folder)
        assertEquals(published, ExportReceipts.matching(project, receipts, folder))
        assertEquals("Reading the project again changes nothing", published, ExportReceipts.matching(project.copy(), receipts, folder))
        assertEquals("A rename does not change the movie", published, ExportReceipts.matching(project.renamed("Journey", 9_000), receipts, folder))
        assertEquals("Nor does a recording the edit does not use", published,
            ExportReceipts.matching(project.addSource(SourceMedia(filename = "later.m4a", duration = 3.0)), receipts, folder))

        val trimmed = project.commit(EditState(project.edit.clips.dropLast(1), audio = project.edit.audio), now = 6_000)
        assertNull("Another edit", ExportReceipts.matching(trimmed, receipts, folder))
        assertEquals("Undo brings the exported edit back", published, ExportReceipts.matching(trimmed.undo(7_000), receipts, folder))
        assertNull("Another format", ExportReceipts.matching(project.copy(exportSettings = ExportSettings(frameRate = 60)), receipts, folder))
        val repaired = project.copy(sources = project.sources.map { if (it.id == take.id) it.copy(sha256 = "c".repeat(64)) else it })
        assertNull("A recording that is no longer the same", ExportReceipts.matching(repaired, receipts, folder))
        ExportReceipts.movie(folder, published).delete()
        assertNull("The movie is gone", ExportReceipts.matching(project, receipts, folder))
    }

    // EditorController.swift:803: a receipt without settings was made in the default format.
    @Test fun aReceiptWithoutSettingsMatchesTheDefaultFormat() {
        val project = project()
        val published = export(project, now = 5_000)
        val old = listOf(published.copy(settings = null))
        assertEquals(old.single(), ExportReceipts.matching(project, old, folder))
        assertNull(ExportReceipts.matching(project.copy(exportSettings = ExportSettings(codec = ExportSettings.Codec.HEVC)), old, folder))
    }

    // EditorController.swift:808-813 and MovieExportPanel.swift:150-155: the newest movie is offered as a previous export.
    @Test fun withoutACurrentExportTheNewestIsOfferedAsPrevious() {
        val project = project()
        assertNull(ExportReceipts.select(project, emptyList(), folder))
        val old = export(project, now = 1_000)
        val edited = project.commit(EditState(project.edit.clips.dropLast(1), audio = project.edit.audio), now = 6_000)
        val new = export(edited, now = 3_000)
        val receipts = ExportReceipts.list(folder)
        assertEquals(ExportReceipts.Selection(new, current = true), ExportReceipts.select(edited, receipts, folder))
        assertEquals("The matching one wins over a newer one", ExportReceipts.Selection(old, current = true), ExportReceipts.select(project, receipts, folder))
        val further = edited.commit(EditState(edited.edit.clips, audio = null), now = 7_000)
        assertEquals(ExportReceipts.Selection(new, current = false), ExportReceipts.select(further, receipts, folder))
    }

    // ---- Pruning (EditorController.swift:749-751, 819-836) ----

    @Test fun aNewMovieRetiresTheEarlierOnes() {
        val project = project()
        val first = export(project, now = 1_000)
        val second = export(project, now = 2_000)
        val previous = ExportReceipts.list(folder)
        val checkpoint = ExportCheckpoint(folder)
        checkpoint.prepare(project)
        checkpoint.section(0).writeText("picture")
        val published = export(project, now = 3_000)

        val retired = ExportReceipts.retire(previous, folder, checkpoint)
        assertEquals(setOf(first.id, second.id), retired.ids)
        assertEquals(listOf(published), ExportReceipts.afterPublishing(published, previous, retired.ids))
        assertEquals("Only the newest export is kept", listOf(published), ExportReceipts.list(folder))
        assertFalse("The used checkpoint goes with them", checkpoint.directory.exists())

        // Everything retired waits in one folder under Processing, to be deleted in the background.
        val scratch = retired.scratch!!
        assertEquals(processing, scratch.parentFile)
        assertEquals(
            setOf("picture-0.mp4", "ticket.json", first.id + ".mp4", first.id + ".json", second.id + ".mp4", second.id + ".json"),
            scratch.list()!!.toSet(),
        )
        ExportCheckpoint.reclaim(folder)
        assertTrue(ExportReceipts.movie(folder, published).isFile)
        assertEquals(listOf(published), ExportReceipts.list(folder))
    }

    // EditorController.swift:827: a movie still being saved elsewhere is kept.
    @Test fun aMovieInUseIsNotRetired() {
        val project = project()
        val busy = export(project, now = 1_000)
        val idle = export(project, now = 2_000)
        val previous = ExportReceipts.list(folder)
        val checkpoint = ExportCheckpoint(folder).apply { prepare(project) }
        val published = export(project, now = 3_000)
        val retired = ExportReceipts.retire(previous, folder, checkpoint) { it == ExportReceipts.movie(folder, busy) }
        assertEquals(setOf(idle.id), retired.ids)
        assertEquals(listOf(published, busy), ExportReceipts.afterPublishing(published, previous, retired.ids))
        assertEquals(listOf(published, busy), ExportReceipts.list(folder))
    }

    // EditorController.swift:822: if the checkpoint cannot be moved, every movie stays where it is.
    @Test fun nothingIsRetiredWhenTheCheckpointCannotBeMoved() {
        val project = project()
        val first = export(project, now = 1_000)
        val previous = ExportReceipts.list(folder)
        val published = export(project, now = 3_000)
        // No checkpoint folder exists, so there is nothing to detach.
        val retired = ExportReceipts.retire(previous, folder, ExportCheckpoint(folder))
        assertEquals(ExportReceipts.Retired(emptySet(), null), retired)
        assertEquals(listOf(published, first), ExportReceipts.afterPublishing(published, previous, retired.ids))
        assertEquals(listOf(published, first), ExportReceipts.list(folder))
    }
}
