package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The selected project across launches and deletions, from YouRec/ProjectStore.swift:150-154, 256-271 and YouRec/ContentView.swift:341. */
class ProjectSelectionTest {
    /** YouRec/ProjectStore.swift:150-152: the saved selection, while its project is in the library. */
    @Test fun theSavedSelectionComesBack() {
        assertEquals("b", ProjectSelection.atLaunch("b", listOf("a", "b", "c")))
    }

    /** YouRec/ProjectStore.swift:153: a selection whose project has gone gives way to the most recent one. */
    @Test fun aMissingSelectionFallsBackToTheMostRecent() {
        assertEquals("a", ProjectSelection.atLaunch("gone", listOf("a", "b")))
        assertEquals("a", ProjectSelection.atLaunch(null, listOf("a", "b")))
        assertNull(ProjectSelection.atLaunch("gone", emptyList()))
    }

    /** YouRec/ProjectStore.swift:269: deleting the selected project selects the most recent one left. */
    @Test fun deletingTheSelectedProjectSelectsTheMostRecentLeft() {
        assertEquals("b", ProjectSelection.afterRemoving("a", "a", listOf("b", "c")))
        assertNull(ProjectSelection.afterRemoving("a", "a", emptyList()))
        // Another project's deletion leaves the selection alone.
        assertEquals("c", ProjectSelection.afterRemoving("c", "a", listOf("b", "c")))
    }

    /** YouRec/ContentView.swift:341: an empty library gets a project at launch. */
    @Test fun anEmptyLibraryGetsAProjectAtLaunch() {
        assertTrue(ProjectSelection.createsAtLaunch(emptyList()))
        assertFalse(ProjectSelection.createsAtLaunch(listOf("a")))
    }
}
