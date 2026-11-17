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
        assertEquals(GradeLibrary(imported = listOf(film), favorites = setOf("mono")), GradeLibrary.decoded(bad))
    }

    // YouRec/EditorTools.swift:565-570: a grade is saved under its name trimmed to 80 characters; an empty name saves nothing.
    @Test fun gradesAreSavedUnderTheirName() {
        val grade = ColorSettings(look = ColorLook.Cinema, amount = 0.6, exposure = -1.5, warmth = 0.2, highlights = -0.3, imported = film)
        val library = GradeLibrary().saving("  Evening\n", grade)!!
        assertEquals("Evening", library.grades.single().name)
        assertEquals(grade, library.grades.single().settings)
        assertEquals(null, library.saving(" \n ", grade))
        assertEquals(80, library.saving("x".repeat(100), grade)!!.grades.last().name.length)
        assertTrue(library.looks(GradeLibrary.Category.Grades).isEmpty() && library.luts(GradeLibrary.Category.Grades).isEmpty())
        assertEquals(library, GradeLibrary.decoded(library.encoded()))
    }

    // YouRec/EditorTools.swift:544-553: choosing a grade takes its look, LUT, strength and balance, not the clip's grade id or motion.
    @Test fun aGradeIsTakenOnTheGradeBeingEdited() {
        val saved = SavedGrade(name = "Cool", settings = ColorSettings(id = "other", look = ColorLook.Cool, amount = 0.5, tint = 0.1, imported = log))
        val current = ColorSettings(id = "mine", look = ColorLook.Warm, exposure = 2.0)
        assertEquals(ColorSettings(id = "mine", look = ColorLook.Cool, amount = 0.5, tint = 0.1, imported = log), saved.applied(current))
    }

    // A grade that is not valid in the file is left out (YouRec/VideoTools.swift:151-157).
    @Test fun anInvalidGradeIsLeftOut() {
        val good = GradeLibrary().saving("Good", ColorSettings(exposure = 1.0))!!
        val bad = good.copy(grades = good.grades + SavedGrade(name = "Bad", settings = ColorSettings(exposure = 9.0)))
        assertEquals(good, GradeLibrary.decoded(bad.encoded()))
    }
}
