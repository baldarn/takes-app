package app.takes.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The library of imported fonts and the copies projects keep, after the
 * original's test of them (Tests/TitleDesignTests.swift:296-370), with its own
 * fixtures: one TrueType and one OpenType face, and a collection of two of each
 * (Tests/Fixtures/Fonts).
 */
class ImportedFontsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun fixture(name: String) = javaClass.getResourceAsStream("/fonts/$name")!!.readBytes()
    private val ttf = "YouRec-QA-Triangle.ttf"
    private val otf = "YouRec-QA-Ink.otf"
    private val ttc = "YouRec-QA-Triangle-Family.ttc"
    private val otc = "YouRec-QA-Ink-Family.otc"

    private val library get() = FontLibrary(File(temporary.root, "Library"))
    private fun add(library: FontLibrary, name: String) = library.add(fixture(name), name)

    // Tests/TitleDesignTests.swift:300-304
    @Test fun allFourFormatsAndEveryFaceOfACollection() {
        val fonts = library
        val faces = listOf(ttf, otf, ttc, otc).map { add(fonts, it).first.faces }
        assertEquals(listOf(1, 1, 2, 2), faces.map { it.size })
        assertEquals(listOf("YouRecQATriangle-Regular", "YouRecQATriangle-Bold"), faces[2].map { it.reference.postScriptName })
        assertEquals(listOf("YouRec QA Ink Regular", "YouRec QA Ink Bold"), faces[3].map { it.name })
        val digest = sha256(fixture(otc))
        assertEquals("import:$digest:1:YouRecQAInk-Bold", faces[3][1].id)
        assertEquals("font-$digest.sfnt", faces[3][1].reference.filename)
        // A face with one weight offers no choice and starts at 400 (YouRec/ImportedFonts.swift:62-71).
        assertTrue(faces.flatten().all { it.weights.isEmpty() && it.defaultWeight == 400 })
    }

    // Tests/TitleDesignTests.swift:305-312
    @Test fun theSameBytesUnderAnotherNameAreTheFileAlreadyThere() {
        val fonts = library
        val (first, added) = add(fonts, ttf)
        assertTrue(added)
        val (again, addedAgain) = fonts.add(fixture(ttf), "Same font.ttf")
        assertFalse(addedAgain)
        assertEquals(first, again)
        assertEquals(ttf, again.filename)
    }

    @Test fun whatIsNotAFontIsRefusedAndAddsNothing() {
        val fonts = library
        val invalid = assertThrows(ImportedFontException::class.java) { fonts.add("invalid".toByteArray(), "Same font.ttf") }
        assertEquals(ImportedFontException.Reason.Invalid, invalid.reason)
        assertEquals("This file could not be read as a font.", invalid.message)
        // Cut short: the tables point past the end.
        assertThrows(ImportedFontException::class.java) { fonts.add(fixture(ttf).copyOf(600), "Short.ttf") }
        val unsupported = assertThrows(ImportedFontException::class.java) { fonts.add(fixture(ttf), "Triangle.woff") }
        assertEquals(ImportedFontException.Reason.Unsupported, unsupported.reason)
        assertEquals("Choose a TTF, OTF, TTC, or OTC font file.", unsupported.message)
        assertTrue(fonts.files().isEmpty())
    }

    // Tests/TitleDesignTests.swift:313-320
    @Test fun theLibrarySurvivesReopeningInTheOrderOfItsFaces() {
        val fonts = library
        for (name in listOf(ttf, otf, ttc, otc)) add(fonts, name)
        // An empty folder left by an interrupted import is not a file of the library.
        File(fonts.root, "0".repeat(64)).mkdirs()
        val cold = FontLibrary(fonts.root)
        val files = cold.files()
        assertEquals(listOf(otc, otf, ttc, ttf), files.map { it.filename })
        for (file in files) for (face in file.faces) assertEquals(face, cold.resolve(face.id, null)?.second)
    }

    @Test fun aFileChangedOnDiskIsNoLongerInTheLibrary() {
        val fonts = library
        val file = add(fonts, ttf).first
        val stored = fonts.file(file.id)!!
        stored.writeBytes(fixture(otf))
        stored.setLastModified(stored.lastModified() + 5_000)
        assertNull(fonts.resolve(file.faces[0].id, null))
        assertTrue(fonts.files().isEmpty())
    }

    // Tests/TitleDesignTests.swift:321-325 with the variable Caveat the app bundles: 400 to 700 on its weight axis.
    @Test fun aVariableFaceOffersTheStopsOfItsWeightAxis() {
        val caveat = File("../app/src/main/assets/TitleFonts/Caveat-Regular.ttf").readBytes()
        val face = library.add(caveat, "Caveat[wght].ttf").first.faces.single()
        assertEquals(listOf(400, 500, 600, 700), face.weights)
        assertEquals(400, face.defaultWeight)
        assertEquals("Caveat-Regular", face.reference.postScriptName)
    }

    // YouRec/ImportedFonts.swift:62-71
    @Test fun weightStopsFollowTheOriginal() {
        assertEquals(emptyList<Int>() to 400, FontFaces.weights(null))
        assertEquals(listOf(100, 200, 300, 400, 500, 600, 700, 800, 900, 1000) to 400, FontFaces.weights(Triple(1.0, 400.0, 1000.0)))
        assertEquals(listOf(250, 300, 350, 400, 500, 600, 650) to 350, FontFaces.weights(Triple(250.9, 350.5, 650.0)))
        assertEquals(listOf(900, 1000) to 1000, FontFaces.weights(Triple(900.0, 1200.0, 1500.0)))
        assertEquals(emptyList<Int>() to 400, FontFaces.weights(Triple(1100.0, 1100.0, 1200.0)))
    }

    @Test fun anIdKeepsTheFirst140BytesOfTheName() {
        val face = FontFaces.faces(fixture(ttf), "a".repeat(64)).single()
        assertEquals("import:${"a".repeat(64)}:0:YouRecQATriangle-Regular", face.id)
        assertEquals(ImportedFontReference("a".repeat(64), 0, "YouRecQATriangle-Regular"), ImportedFontReference.parse(face.id))
    }

    /** A movie with one take, a title in the TrueType face and captions in the second face of the OpenType collection. */
    private fun lettered(fonts: FontLibrary): Triple<Project, String, String> {
        val title = add(fonts, ttf).first.faces[0].id
        val caption = add(fonts, otc).first.faces[1].id
        val take = SourceMedia(filename = "title-black.mp4", duration = 3.0)
        var project = Project("Imported lettering").addRecording(take)
        val edit = project.edit
        val text = TextOverlay(text = "ABC", start = 0.0, end = 3.0, x = 0.5, y = 0.22, size = 0.16, appearance = TitleDesign(face = title, shadow = false))
        val subtitles = SubtitleSettings(appearance = buildJsonObject { put("face", caption) })
        project = project.commit(edit.copy(clips = edit.clips.map { it.copy(texts = listOf(text)) }, subtitles = subtitles))
        return Triple(project, title, caption)
    }

    // Tests/TitleDesignTests.swift:327-370
    @Test fun aProjectKeepsItsFontsThroughRemovalUndoDuplicateAndZip() {
        val fonts = library
        val disk = ProjectDisk(File(temporary.root, "Projects"), fonts)
        val (lettered, title, caption) = lettered(fonts)
        val files = listOf(title, caption).map { ImportedFontReference.parse(it)!!.filename }.sorted()
        assertEquals(files, lettered.fontFiles)
        assertEquals(listOf("title-black.mp4") + files, lettered.mediaFiles)
        disk.save(lettered)
        val folder = disk.folder(lettered.id)
        File(folder, "title-black.mp4").writeBytes(ByteArray(2_000) { it.toByte() })
        for (name in files) assertTrue(File(folder, name).isFile)

        // Taken out of the library, the faces still come from the project's copies.
        for (file in fonts.files()) fonts.remove(file.id)
        assertTrue(fonts.files().isEmpty())
        for (id in listOf(title, caption)) {
            assertNull(fonts.resolve(id, null))
            assertEquals(ImportedFontReference.parse(id)!!.postScriptName, fonts.resolve(id, folder)?.second?.reference?.postScriptName)
        }

        // Fonts only an undone step uses travel with a duplicate and a ZIP.
        val undone = lettered.undo()
        disk.save(undone)
        val copy = ProjectDuplicate.duplicate(undone, disk.root, "Font history copy")
        for (name in files) assertArrayEquals(File(folder, name).readBytes(), File(disk.folder(copy.id), name).readBytes())
        val zip = ByteArrayOutputStream().also { ProjectArchive.write(disk.folder(copy.id), copy, it) }.toByteArray()
        assertTrue(entries(zip).keys.containsAll(files))
        val restored = ProjectRestore.restore(ByteArrayInputStream(zip), disk.root, Long.MAX_VALUE).redo()
        assertEquals(files, restored.fontFiles)
        for (name in files) assertTrue(File(disk.folder(restored.id), name).isFile)
        assertEquals(caption, fonts.resolve(caption, disk.folder(restored.id))?.second?.id)
    }

    @Test fun aSaveWithoutImportedFontsWritesWhatItAlwaysDid() {
        val take = SourceMedia(filename = "take.mp4", duration = 3.0)
        val project = Project("Plain").addRecording(take)
        val plain = ProjectDisk(File(temporary.root, "A"))
        val withFonts = ProjectDisk(File(temporary.root, "B"), library)
        plain.save(project)
        withFonts.save(project)
        assertEquals(emptyList<String>(), project.fontFiles)
        assertEquals(listOf("take.mp4"), project.mediaFiles)
        val a = plain.folder(project.id)
        val b = withFonts.folder(project.id)
        assertEquals(a.walkTopDown().map { it.relativeTo(a).path }.sorted().toList(), b.walkTopDown().map { it.relativeTo(b).path }.sorted().toList())
        assertArrayEquals(File(a, "project.json").readBytes(), File(b, "project.json").readBytes())
    }

    // YouRec/ProjectArchive.swift:227-231: a font copy already missing stays behind and the backup still restores.
    @Test fun aMissingFontCopyIsLeftOutOfTheZipAndTheTitleFallsBack() {
        val fonts = library
        val disk = ProjectDisk(File(temporary.root, "Projects"), fonts)
        val (lettered, title, _) = lettered(fonts)
        disk.save(lettered)
        val folder = disk.folder(lettered.id)
        File(folder, "title-black.mp4").writeBytes(ByteArray(10))
        val missing = ImportedFontReference.parse(title)!!.filename
        File(folder, missing).delete()
        val zip = ByteArrayOutputStream().also { ProjectArchive.write(folder, lettered, it) }.toByteArray()
        assertFalse(missing in entries(zip))
        val restored = ProjectRestore.restore(ByteArrayInputStream(zip), File(temporary.root, "Other"), Long.MAX_VALUE)
        val other = File(File(temporary.root, "Other"), restored.id)
        assertFalse(File(other, missing).exists())
        assertNull(FontLibrary(File(temporary.root, "Empty")).resolve(title, other))
        // The recording itself is still required.
        File(folder, "title-black.mp4").delete()
        assertThrows(ProjectException::class.java) { ProjectArchive.write(folder, lettered, ByteArrayOutputStream()) }
    }

    @Test fun aFontCopyWhoseBytesAreNotItsNameIsRefusedOnRestore() {
        val fonts = library
        val disk = ProjectDisk(File(temporary.root, "Projects"), fonts)
        val (lettered, title, _) = lettered(fonts)
        disk.save(lettered)
        val folder = disk.folder(lettered.id)
        File(folder, "title-black.mp4").writeBytes(ByteArray(10))
        val name = ImportedFontReference.parse(title)!!.filename
        // Other bytes under the name, with an integrity file that vouches for them.
        File(folder, name).writeBytes(fixture(otf))
        val zip = ByteArrayOutputStream().also { ProjectArchive.write(folder, lettered, it) }.toByteArray()
        val error = assertThrows(ProjectRestoreException::class.java) {
            ProjectRestore.restore(ByteArrayInputStream(zip), File(temporary.root, "Other"), Long.MAX_VALUE)
        }
        assertEquals(ProjectRestoreException.Reason.DamagedMedia, error.reason)
        // A font entry the project does not name is refused like any other.
        val extra = entries(zip).toMutableMap().apply { put("font-${"b".repeat(64)}.sfnt", fixture(ttf)) }
        val refused = assertThrows(ProjectRestoreException::class.java) {
            ProjectRestore.restore(ByteArrayInputStream(zip(extra)), File(temporary.root, "Other"), Long.MAX_VALUE)
        }
        assertEquals(ProjectRestoreException.Reason.NotABackup, refused.reason)
    }

    @Test fun removingAFontKeepsWhatAProjectAlreadyHolds() {
        val fonts = library
        val file = add(fonts, ttc).first
        val folder = File(temporary.root, "Project").apply { mkdirs() }
        fonts.preserve(listOf(file.faces[0].reference.filename, "font-${"c".repeat(64)}.sfnt"), folder)
        assertEquals(listOf(file.faces[0].reference.filename), folder.list()!!.toList())
        fonts.remove(file.id)
        assertNull(fonts.file(file.id))
        assertNotNull(fonts.resolve(file.faces[1].id, folder))
        assertEquals(file.faces, FontLibrary(File(temporary.root, "Empty")).contents(File(folder, file.faces[0].reference.filename), file.id))
    }

    private fun entries(zip: ByteArray): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            while (true) { val entry = input.nextEntry ?: break; result[entry.name] = input.readBytes() }
        }
        return result
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> for ((name, data) in entries) { zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() } }
    }.toByteArray()
}
