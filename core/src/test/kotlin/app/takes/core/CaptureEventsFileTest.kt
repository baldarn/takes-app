package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The events of a take saved beside its movie. This port's own tests, from
 * YouRec/CaptureSegmentWriter.swift:116-122 (the file and its name) and
 * YouRec/ProjectStore.swift:351-359 (reading it back when a take is indexed).
 */
class CaptureEventsFileTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun movie() = File(temporary.root, "1700000000000-take.mp4")

    @Test fun fileIsNamedAfterTheMovie() {
        assertEquals(File(temporary.root, "1700000000000-take.events.json"), CaptureEventsFile.beside(movie()))
    }

    @Test fun eventsComeBackAsWritten() {
        val events = listOf(
            CaptureEvent(time = 1.5, kind = Project.BOOKMARK_EVENT, detail = ""),
            CaptureEvent(time = 4.0, kind = "camera", detail = "Front"),
        )
        CaptureEventsFile.write(events, movie())
        assertEquals(events, CaptureEventsFile.read(movie(), 10.0))
    }

    @Test fun eachWriteReplacesTheList() {
        val first = CaptureEvent(time = 1.0, kind = Project.BOOKMARK_EVENT, detail = "")
        val second = CaptureEvent(time = 2.0, kind = Project.BOOKMARK_EVENT, detail = "")
        CaptureEventsFile.write(listOf(first), movie())
        CaptureEventsFile.write(listOf(first, second), movie())
        assertEquals(listOf(first, second), CaptureEventsFile.read(movie(), 10.0))
        // Nothing but the events file is left in the folder.
        assertEquals(listOf("1700000000000-take.events.json"), temporary.root.list().orEmpty().toList())
    }

    /** A take that was interrupted is shorter than what was marked in it. */
    @Test fun eventsPastTheEndOfTheMovieAreLeftOut() {
        val inside = CaptureEvent(time = 3.0, kind = Project.BOOKMARK_EVENT, detail = "")
        val after = CaptureEvent(time = 9.0, kind = Project.BOOKMARK_EVENT, detail = "")
        CaptureEventsFile.write(listOf(inside, after), movie())
        assertEquals(listOf(inside), CaptureEventsFile.read(movie(), 8.0))
        assertNull(CaptureEventsFile.read(movie(), 2.0))
    }

    @Test fun noFileAndADamagedFileGiveNothingAndAreLeftAlone() {
        assertNull(CaptureEventsFile.read(movie(), 10.0))
        val file = CaptureEventsFile.beside(movie())
        file.writeText("[{\"id\":\"a\",\"time\":")
        assertNull(CaptureEventsFile.read(movie(), 10.0))
        assertTrue(file.isFile)
    }

    /** The list the original app writes: an uppercase UUID, and keys this port does not know are ignored. */
    @Test fun readsTheOriginalsShape() {
        CaptureEventsFile.beside(movie()).writeText(
            """[{"id":"5B0A4E2C-7C1D-4F0B-9E57-0C6C2F3A1D11","time":2.25,"kind":"bookmark","detail":"Bookmark","later":true}]""",
        )
        val events = CaptureEventsFile.read(movie(), 10.0)!!
        assertEquals(1, events.size)
        assertEquals("5B0A4E2C-7C1D-4F0B-9E57-0C6C2F3A1D11", events[0].id)
        assertEquals(2.25, events[0].time, 0.0)
        assertEquals(Project.BOOKMARK_EVENT, events[0].kind)
    }

    @Test fun deleteRemovesTheFile() {
        CaptureEventsFile.write(listOf(CaptureEvent(time = 1.0, kind = Project.BOOKMARK_EVENT, detail = "")), movie())
        CaptureEventsFile.delete(movie())
        assertFalse(CaptureEventsFile.beside(movie()).exists())
    }
}
