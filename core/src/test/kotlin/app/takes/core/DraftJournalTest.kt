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
 * The journal of a save that failed, against YouRec/ProjectRecovery.swift:356-397 and YouRec/Project.swift:855.
 * The original has no test of its own for it; the rules are its source's.
 */
class DraftJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun disk() = ProjectDisk(File(temporary.root, "Projects"))
    private val take = SourceMedia(filename = "a.mp4", duration = 4.0)

    /** YouRec/ProjectRecovery.swift:370-397: a journalled draft comes back, whole, on top of the save it was made on. */
    @Test fun aJournalledDraftComesBackOnTopOfItsSave() {
        val disk = disk()
        val saved = Project("Trip").addRecording(take)
        disk.save(saved)
        val draft = saved.commit(TimelineEditing.split(saved.edit, 2.0)!!).renamed("Trip, cut")
        disk.journalDraft(draft)
        val pending = File(disk.folder(saved.id), ProjectDisk.PENDING_EDIT)
        // Only the snapshots History lacks are written into the journal.
        val journalled = File(pending, "History").list().orEmpty().toSet()
        val history = File(disk.folder(saved.id), "History").list().orEmpty().toSet()
        assertTrue(journalled.isNotEmpty())
        assertTrue(journalled.none { it in history })
        assertEquals(draft, disk.pendingDraft(saved.id))
        // The saved project itself is untouched.
        assertEquals(saved, disk.loadAll().projects.single())
    }

    /** YouRec/ProjectRecovery.swift:389: a draft made on top of another save is not offered. */
    @Test fun aDraftMadeOnAnotherSaveIsNotOffered() {
        val disk = disk()
        val saved = Project("Trip").addRecording(take)
        disk.save(saved)
        disk.journalDraft(saved.renamed("Draft"))
        // Saved since, by another way: the draft no longer sits on the head.
        disk.save(saved.renamed("Elsewhere"))
        assertNull(disk.pendingDraft(saved.id))
    }

    /** YouRec/Project.swift:855, YouRec/ProjectRecovery.swift:360-369: saving the draft's own manifest clears the journal. */
    @Test fun savingTheDraftClearsTheJournal() {
        val disk = disk()
        val saved = Project("Trip").addRecording(take)
        disk.save(saved)
        val draft = saved.renamed("Draft")
        disk.journalDraft(draft)
        val pending = File(disk.folder(saved.id), ProjectDisk.PENDING_EDIT)
        // A save of something else leaves the journal.
        disk.save(saved.renamed("Other"))
        assertTrue(pending.exists())
        disk.save(draft)
        assertFalse(pending.exists())
        assertNull(disk.pendingDraft(saved.id))
    }

    @Test fun noJournalNoDraft() {
        val disk = disk()
        val saved = Project("Trip")
        disk.save(saved)
        assertNull(disk.pendingDraft(saved.id))
    }
}
