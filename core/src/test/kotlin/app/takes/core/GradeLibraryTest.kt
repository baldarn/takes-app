package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GradeLibraryTest {
    private val film = ImportedLUT("effect-lut-aa.cube", "Film")
    private val log = ImportedLUT("effect-lut-bb.cube", "Log to 709")

    // YouRec/ColorGradeControls.swift:11 and 87-93: looks are selected and marked by their raw value, LUTs by their file.
    @Test fun idsAreTheOriginals() {
        assertEquals("goldenHour", GradeLibrary.id(ColorLook.GoldenHour))
        assertEquals("natural", GradeLibrary.id(ColorSettings()))
        assertEquals("effect-lut-aa.cube", GradeLibrary.id(ColorSettings(look = ColorLook.Warm, imported = film)))
    }

    // YouRec/EditorTools.swift:560, 572-575: an import is listed once; a star turns a favourite on and off.
    @Test fun importsAndFavourites() {
        val library = GradeLibrary().importing(film).importing(log).importing(film.copy(name = "Again"))
        assertEquals(listOf(film, log), library.imported)
        val starred = library.toggled("cinema").toggled(film.filename)
        assertEquals(setOf("cinema", film.filename), starred.favorites)
        assertEquals(setOf(film.filename), starred.toggled("cinema").favorites)
    }

    // YouRec/ColorGradeControls.swift:85-96: Built-in shows the looks, Imported the LUTs, Favorites the starred of both.
    @Test fun eachTabShowsItsOwn() {
        val library = GradeLibrary().importing(film).importing(log).toggled("mono").toggled(log.filename)
        assertEquals(ColorLook.entries, library.looks(GradeLibrary.Category.BuiltIn))
        assertTrue(library.luts(GradeLibrary.Category.BuiltIn).isEmpty())
        assertTrue(library.looks(GradeLibrary.Category.Imported).isEmpty())
        assertEquals(listOf(film, log), library.luts(GradeLibrary.Category.Imported))
        assertEquals(listOf(ColorLook.Mono), library.looks(GradeLibrary.Category.Favorites))
        assertEquals(listOf(log), library.luts(GradeLibrary.Category.Favorites))
    }

    // YouRec/ColorGradeControls.swift:103-107: what a LUT expects is kept in the library for its next use.
    @Test fun whatALutExpectsIsKept() {
        val library = GradeLibrary().importing(film).importing(log).expecting(log.copy(input = LUTInput.AppleLog2))
        assertEquals(listOf(LUTInput.Rec709, LUTInput.AppleLog2), library.imported.map { it.input })
    }

    // YouRec/ColorGrading.swift:144-150: written as JSON, read back the same; an unreadable file is an empty library.
    @Test fun theFileReadsBackAndABadOneIsEmpty() {
        val library = GradeLibrary().importing(film).importing(log.copy(input = LUTInput.AppleLog)).toggled("ink")
        assertEquals(library, GradeLibrary.decoded(library.encoded()))
        assertEquals(GradeLibrary(), GradeLibrary.decoded("not json"))
        assertEquals(GradeLibrary(), GradeLibrary.decoded(null))
        // A reference outside the library's own names is dropped (ColorGrading.swift:16-20).
        val bad = """{"imported":[{"filename":"../escape.cube","name":"x"},{"filename":"effect-lut-aa.cube","name":"Film"}],"favorites":["mono"]}"""
        assertEquals(GradeLibrary(listOf(film), setOf("mono")), GradeLibrary.decoded(bad))
    }
}
