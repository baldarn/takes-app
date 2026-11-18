package app.takes.core.yourec

import app.takes.core.CaptureEffectsFile
import app.takes.core.ProjectDisk
import app.takes.core.ProjectException
import app.takes.core.ProjectRestore
import app.takes.core.ProjectRestoreException
import app.takes.core.Size
import app.takes.core.VideoEffectID
import app.takes.core.sha256
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Random

private typealias Reason = ProjectRestoreException.Reason

/** A ZIP made by YouRec's "Zip project", opened by Takes (docs/IOS-INTEROP.md, 3.5). */
class YouRecRestoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root get() = File(temporary.root, "Projects")
    private fun uid(n: Int) = "5A1E0000-0000-4000-8000-%012X".format(n)
    private val mov1 = "1731400000000-${uid(0x5001)}.mov"
    private val mov2 = "1731400004000-${uid(0x5002)}.mov"

    private fun bytes(count: Int, seed: Long) = ByteArray(count).also { Random(seed).nextBytes(it) }

    /** The files a fixture's ZIP holds: the media the original names, with made-up bytes. */
    private fun media(case: String, override: Map<String, ByteArray> = emptyMap()): Map<String, ByteArray> {
        val fixture = YouRecFixtures.load(case)
        val names = YouRecCodec.import(fixture.manifest, { fixture.steps.getValue(it) }).mediaFiles
        return names.withIndex().associate { (index, name) -> name to (override[name] ?: bytes(1000 + index, index.toLong())) }
    }

    private fun archive(
        case: String,
        media: Map<String, ByteArray> = media(case),
        extra: Map<String, ByteArray> = emptyMap(),
        vouched: Map<String, ByteArray> = media + extra.filterKeys { it.startsWith("Recovery/") },
        version: Int = if (extra.keys.any { it.startsWith("Recovery/") }) 2 else 1,
    ): ByteArray = YouRecFixtures.zip(YouRecFixtures.load(case), media, YouRecFixtures.integrity(vouched.mapValues { sha256(it.value) }, version), extra)

    private fun restore(data: ByteArray, probe: ProjectRestore.MediaProbe = ProjectRestore.MediaProbe()) =
        ProjectRestore.restoreArchive(ByteArrayInputStream(data), root, Long.MAX_VALUE, now = 42, probe = probe)

    /** Refused for [reason], leaving the library as it was. */
    private fun refused(reason: Reason, data: ByteArray) {
        fun library() = root.list().orEmpty().filter { !it.startsWith(".") }.toSet()
        val before = library()
        val error = assertThrows(ProjectRestoreException::class.java) { restore(data) }
        assertEquals(reason, error.reason)
        assertEquals(before, library())
    }

    @Test fun aZipOfTheOriginalBecomesANewProject() {
        val files = media("takes")
        val restored = restore(archive("takes", files))
        assertTrue(restored.fromYouRec)
        val project = restored.project
        assertEquals(42, project.updatedAt)
        assertFalse(project.id == uid(0xFFF))
        assertEquals(listOf(YouRecNote(YouRecNote.Kind.RecordingRunEnded)), restored.notes)
        // Each recording carries the hash the archive vouched for.
        assertEquals(files.getValue(mov1).let(::sha256), project.sources[0].sha256)
        val folder = ProjectDisk(root).folder(project.id)
        assertArrayEquals(files.getValue(mov2), File(folder, mov2).readBytes())
        // Saved in Takes' form: the original's steps are gone, and nothing of the journey stays.
        val history = File(folder, "History").list()!!.toSet()
        assertEquals(project.snapshots.map { it.filename() }.toSet(), history)
        assertTrue(YouRecFixtures.load("takes").steps.keys.none { it in history })
        assertFalse(File(folder, "integrity.json").exists())
        // It opens as any project.
        assertEquals(project, ProjectDisk(root).loadAll().projects.single())
    }

    @Test fun theOriginalsTimelineFileIsAllowedAndLeftBehind() {
        val restored = restore(archive("takes", extra = mapOf("Timeline.otio" to "{}".toByteArray())))
        assertFalse(File(ProjectDisk(root).folder(restored.project.id), "Timeline.otio").exists())
        // Only under the name the original gives it.
        refused(Reason.NotABackup, archive("takes", extra = mapOf("Timeline-2.otio" to "{}".toByteArray())))
    }

    @Test fun anEntryTheProjectDoesNotNameIsRefused() {
        refused(Reason.NotABackup, archive("takes", extra = mapOf("notes.txt" to byteArrayOf(1))))
        refused(Reason.NotABackup, archive("takes", extra = mapOf("extra.mov" to byteArrayOf(1))))
        // Vouched for, but not the project's.
        val files = media("takes")
        refused(Reason.NotABackup, archive("takes", files, vouched = files + ("extra.mov" to byteArrayOf(1))))
        // A recording the hashes do not vouch for.
        refused(Reason.NotABackup, archive("takes", files, vouched = files - mov1))
        // A step no history names.
        val stray = "{}".toByteArray()
        refused(Reason.NotABackup, archive("takes", extra = mapOf("History/${sha256(stray)}.json" to stray)))
    }

    @Test fun recoveryFilesComeWithTheProject() {
        val group = uid(0x0DD)
        val movie = bytes(5000, 99)
        val events = """[{"detail":"","id":"${uid(1)}","kind":"bookmark","time":1}]""".toByteArray()
        val recovery = mapOf("Recovery/$group/take.mov" to movie, "Recovery/$group/take.events.json" to events)
        val restored = restore(archive("takes", extra = recovery))
        val disk = ProjectDisk(root)
        val folder = File(disk.folder(restored.project.id), "Recovery/$group")
        assertArrayEquals(movie, File(folder, "take.mov").readBytes())
        assertArrayEquals(events, File(folder, "take.events.json").readBytes())
        assertEquals(listOf(File(folder, "take.mov")), disk.recoveryFiles(restored.project))
    }

    @Test fun recoveryFilesNeedTheSecondVersionAndTheirHashes() {
        val recovery = mapOf("Recovery/${uid(0x0DD)}/take.mov" to bytes(5000, 99))
        refused(Reason.NotABackup, archive("takes", extra = recovery, version = 1))
        // Not vouched for.
        refused(Reason.NotABackup, archive("takes", extra = recovery, vouched = media("takes")))
        // Vouched for but not there.
        refused(Reason.NotABackup, archive("takes", extra = emptyMap(), vouched = media("takes") + recovery, version = 2))
        // Damaged: a recovery file is never left out quietly.
        val files = media("takes")
        refused(Reason.DamagedMedia, archive("takes", files, extra = recovery, vouched = files + recovery.mapValues { bytes(5000, 1) }))
        // Version 2 without recovery files is the original's too.
        assertTrue(restore(archive("takes", version = 2)).fromYouRec)
    }

    @Test fun missingOrDamagedMediaIsLeftOutAsTheOriginalDoes() {
        val files = media("effects")
        val image = files.keys.first { it.startsWith("image-") }
        val lut = files.keys.first { it.startsWith("effect-lut-") }
        // A take and a LUT missing, a picture whose bytes changed.
        val restored = restore(archive("effects", files - mov2 - lut + (image to bytes(10, 5)), vouched = files))
        val folder = ProjectDisk(root).folder(restored.project.id)
        assertEquals(
            listOf(YouRecNote(YouRecNote.Kind.MissingMedia, listOf(mov2)), YouRecNote(YouRecNote.Kind.DamagedMedia, listOf(image))),
            restored.notes,
        )
        assertFalse(File(folder, image).exists())
        assertFalse(File(folder, mov2).exists())
        // The take stays, to be found again by its hash.
        assertEquals(sha256(files.getValue(mov2)), restored.project.sources[1].sha256)
    }

    @Test fun theEffectsATakeWasRecordedWithGoBesideItsMovie() {
        val files = media("effects")
        val restored = restore(archive("effects", files))
        val folder = ProjectDisk(root).folder(restored.project.id)
        val effects = CaptureEffectsFile.read(File(folder, mov1), 4.0)!!
        assertEquals(VideoEffectID.CrashZoom, effects.single().kind)
        // Its analysis, which only the take names, came along.
        val analysis = effects.single().analysisFile!!
        assertArrayEquals(files.getValue(analysis), File(folder, analysis).readBytes())
    }

    @Test fun voiceRecordingsBecomeWav() {
        val samples = bytes(9600, 3).also { if (it.size % 2 != 0) error("odd") }
        val voice = "${uid(0x5004)}.caf"
        val caf = LinearPcmTest().caf(samples, 48_000.0, 1, 16, flags = 2)
        val files = media("audio", mapOf(voice to caf))
        val restored = restore(archive("audio", files))
        val source = restored.project.sources[1]
        assertEquals("${uid(0x5004)}.wav", source.filename)
        val folder = ProjectDisk(root).folder(restored.project.id)
        val wav = File(folder, source.filename).readBytes()
        assertEquals(sha256(wav), source.sha256)
        assertArrayEquals(samples, wav.copyOfRange(44, wav.size))
        assertFalse(File(folder, voice).exists())
        assertEquals(true, restored.project.sources[0].isStillPhoto)
        assertTrue(restored.notes.isEmpty())

        // Compressed sound is left out, to be found again.
        val aac = LinearPcmTest().caf(samples, 48_000.0, 1, 16, flags = 2, format = "aac ")
        val other = restore(archive("audio", media("audio", mapOf(voice to aac))))
        assertEquals(listOf(YouRecNote(YouRecNote.Kind.AudioNotConverted, listOf(voice))), other.notes)
        assertNull(other.project.sources[1].sha256)
        assertFalse(File(ProjectDisk(root).folder(other.project.id), other.project.sources[1].filename).exists())
    }

    @Test fun theAppProbesTheMedia() {
        val files = media("canvas")
        val probe = ProjectRestore.MediaProbe(size = { if (it.name == mov2) Size(720.0, 1280.0) else null }, playable = { it.name != mov1 })
        val restored = restore(archive("canvas", files), probe)
        assertEquals(Size(720.0, 1280.0), restored.project.snapshots[0].state.outputSize)
        assertEquals(
            listOf(YouRecNote(YouRecNote.Kind.CanvasFixed, listOf(mov2)), YouRecNote(YouRecNote.Kind.UnplayableVideo, listOf(mov1))),
            restored.notes,
        )
    }

    @Test fun aManifestTheOriginalWouldRefuseIsRefused() {
        val fixture = YouRecFixtures.load("takes")
        val broken = YouRecFixtures.Fixture(fixture.manifest.decodeToString().replace("\"schemaVersion\":6", "\"schemaVersion\":17").toByteArray(), fixture.steps)
        val files = media("takes")
        val data = YouRecFixtures.zip(broken, files, YouRecFixtures.integrity(files.mapValues { sha256(it.value) }, 1))
        val error = assertThrows(ProjectException::class.java) { restore(data) }
        assertEquals(ProjectException.Reason.InvalidDocument, error.reason)
    }
}
