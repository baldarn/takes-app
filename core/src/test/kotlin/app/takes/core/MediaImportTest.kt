package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class MediaImportTest {
    // YouRec/AddVideoPanel.swift:111: several files are added in the order of their names, as Finder sorts them.
    @Test fun filesAreAddedInTheOrderOfTheirNames() {
        val names = listOf("Clip 10.mov", "clip 2.mov", "Clip 1.mov", "b.mp4", "A.mp4", "clip 02b.mov", "Été.mov", "Ete 2.mov")
        assertEquals(
            listOf("A.mp4", "b.mp4", "Clip 1.mov", "clip 2.mov", "clip 02b.mov", "Clip 10.mov", "Ete 2.mov", "Été.mov"),
            MediaImport.byName(names, Locale.ENGLISH) { it },
        )
    }

    @Test fun equalNamesKeepTheirOrder() {
        val items = listOf("x" to 1, "x" to 2, "a" to 3)
        assertEquals(listOf(3, 1, 2), MediaImport.byName(items, Locale.ENGLISH) { it.first }.map { it.second })
    }
}
