package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A project recovered from the original recordings of another, against
 * YouRec/ProjectStore.swift:747-805 and YouRec/RecordingSalvage.swift:13-46.
 * The original has no test of its own for it; the numbers are its source's.
 */
class RecoveredProjectTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun take(name: String, duration: Double = 4.0, audio: Boolean = false, originalName: String? = null) =
        SourceMedia(filename = name, duration = duration, recordedAt = 1_000L, audioOnly = if (audio) true else null, originalName = originalName)

    /** YouRec/RecordingSalvage.swift:13-46: sources first, then unlisted media of the folder, then what was set aside. */
    @Test fun theOriginalsAreTheSourcesTheUnlistedMediaAndWhatWasSetAside() {
        val folder = temporary.newFolder("project")
        val a = take("b-take.mp4")
        val b = take("a-sound.wav", audio = true)
        val project = Project("Trip", sources = listOf(a, b))
        listOf("b-take.mp4", "a-sound.wav", "z-left.mp4", "c-left.m4a", ".hidden.mp4", "effect-1.mov", "picture.jpg", "project.json")
            .forEach { File(folder, it).writeText("x") }
        val aside = File(temporary.newFolder("project", "Recovery", "abc"), "torn.mp4").apply { writeText("x") }
        val originals = RecoveredProject.originals(project, folder, listOf(aside))
        assertEquals(listOf("b-take.mp4", "a-sound.wav", "c-left.m4a", "z-left.mp4", "torn.mp4"), originals.map { it.file.name })
        assertEquals(listOf(a, b, null, null, null), originals.map { it.source })
    }

    /** YouRec/RecordingSalvage.swift:8: a recording is called by its imported name, else by its file's. */
    @Test fun aRecordingIsCalledByItsImportedNameElseItsFile() {
        assertEquals("Holiday.mov", RecoveredProject.Original(File("x.mp4"), take("x.mp4", originalName = "Holiday.mov")).title)
        assertEquals("x.mp4", RecoveredProject.Original(File("x.mp4"), take("x.mp4")).title)
        assertEquals("torn.mp4", RecoveredProject.Original(File("torn.mp4")).title)
    }

    /**
     * YouRec/ProjectStore.swift:753-759, 529: the folder's own files always go,
     * a set-aside one only while its automatic repaired copy is not a source,
     * and only the chosen one when there is a choice.
     */
    @Test fun whatIsCopied() {
        val folder = temporary.newFolder("p")
        val recovery = File(File(folder, "Recovery"), "k")
        val repaired = File(recovery, "repaired.mp4")
        val torn = File(recovery, "torn.mp4")
        val copy = RecoveredProject.automaticCopy("repaired.mp4")
        assertTrue(copy.startsWith("recovered-") && copy.endsWith(".mov") && copy.length == "recovered-".length + 64 + 4)
        val originals = listOf(
            RecoveredProject.Original(File(folder, "a.mp4"), take("a.mp4")),
            RecoveredProject.Original(File(folder, copy), take(copy)),
            RecoveredProject.Original(repaired),
            RecoveredProject.Original(torn),
        )
        assertEquals(listOf("a.mp4", copy, "torn.mp4"), RecoveredProject.chosen(originals, folder).map { it.file.name })
        assertEquals(listOf(repaired), RecoveredProject.chosen(originals, folder, selected = repaired).map { it.file })
        assertEquals(emptyList<File>(), RecoveredProject.chosen(originals, folder, selected = File(folder, "gone.mp4")).map { it.file })
    }

    /** YouRec/ProjectStore.swift:763 with YouRec/RecordingSalvage.swift:54-57: each file, a tenth more and a megabyte. */
    @Test fun theSpaceForTheCopyIsEachFilesSalvageBudget() {
        assertEquals(1_000_000 + 100_000 + 1_048_576 + 50 + 5 + 1_048_576L, RecoveredProject.estimate(listOf(1_000_000L, 50L)))
        assertEquals(0L, RecoveredProject.estimate(emptyList()))
    }

    /** YouRec/ProjectStore.swift:765: 95 characters of the title, then " · Recovered"; "Recordings" with no project. */
    @Test fun theTitleIsTheOldOneRecovered() {
        assertEquals("Trip · Recovered", RecoveredProject.title("Trip", " · Recovered"))
        assertEquals("Recordings · Recovered", RecoveredProject.title(null, " · Recovered"))
        val long = "a".repeat(120)
        assertEquals("a".repeat(95) + " · Recuperato", RecoveredProject.title(long, " · Recuperato"))
        // A character made of two is not cut in half.
        val emoji = "a".repeat(94) + "😀" + "b"
        assertEquals("a".repeat(94) + " · Recovered", RecoveredProject.title(emoji, " · Recovered"))
    }

    /** YouRec/ProjectStore.swift:788-790: the take's date, or now for a file the project did not list, and its name kept. */
    @Test fun aRecoveredSourceKeepsTheTakesDateAndName() {
        val listed = RecoveredProject.Original(File("t.mp4"), take("t.mp4", originalName = "N".repeat(300)))
        val source = RecoveredProject.source(listed, "ID", "ID.mp4", 2.5, audioOnly = false, now = 9_000L)
        assertEquals(SourceMedia("ID", "ID.mp4", 2.5, 1_000L, originalName = "N".repeat(255)), source)
        val loose = RecoveredProject.source(RecoveredProject.Original(File("torn.mp4")), "ID2", "ID2.mp4", 1.0, audioOnly = true, now = 9_000L)
        assertEquals(SourceMedia("ID2", "ID2.mp4", 1.0, 9_000L, audioOnly = true, originalName = "torn.mp4"), loose)
        assertNull(source.events)
    }

    /**
     * YouRec/ProjectStore.swift:791-794: footage goes on at the end of the
     * timeline as a take does; a sound at the end of the edit on its own track,
     * as one Undo step.
     */
    @Test fun footageGoesOnTheTimelineAndSoundAtItsEnd() {
        var project = Project("Trip · Recovered")
        val first = SourceMedia("A", "A.mp4", 3.0)
        val sound = SourceMedia("S", "S.m4a", 2.0, audioOnly = true)
        val second = SourceMedia("B", "B.mov", 1.5)
        project = RecoveredProject.adding(project, first)
        project = RecoveredProject.adding(project, sound)
        project = RecoveredProject.adding(project, second)
        assertEquals(listOf("A", "S", "B"), project.sources.map { it.id })
        assertEquals(listOf("A", "B"), project.edit.clips.map { it.sourceID })
        assertEquals(listOf(0.0 to 3.0, 0.0 to 1.5), project.edit.clips.map { it.start to it.end })
        val audio = project.edit.audio.orEmpty().single()
        assertEquals("S", audio.sourceID)
        assertEquals(3.0, audio.start, 1e-9)
        assertEquals(2.0, audio.duration, 1e-9)
        // The footage is outside Undo, as a take is; the sound is one step.
        assertEquals(1, project.historyCursor)
        project.validate()
    }

    /** YouRec/ProjectStore.swift:801-803. */
    @Test fun theReportIsTheOriginals() {
        assertEquals("Created “Trip · Recovered” with 2 recordings.", RecoveredProject.message(RecoveredProject.Outcome("Trip · Recovered", 2, 0, 0)))
        assertEquals(
            "Created “T” with 1 recordings. Recovered readable portions of 1 files. 2 unreadable files remain with the original project.",
            RecoveredProject.message(RecoveredProject.Outcome("T", 1, 1, 2)),
        )
    }
}
