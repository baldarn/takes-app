package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Take receipts and the indexing of a take found after the app stopped, against
 * YouRec/ProjectRecovery.swift:229-353 and YouRec/Project.swift:375-393.
 */
class TakeReceiptTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun take(id: String = newID(), duration: Double = 3.0) = SourceMedia(id = id, filename = "100-$id.mp4", duration = duration)

    private fun movie(folder: File, segment: String) = File(folder, "100-$segment.mp4")

    /** YouRec/ProjectRecovery.swift:255-317: a receipt and its reserve beside the movie, read back only for that movie. */
    @Test fun aReceiptIsReadBackForItsOwnMovie() {
        val project = Project("Trip")
        val folder = temporary.newFolder(project.id)
        val segment = newID()
        val file = movie(folder, segment)
        val receipt = TakeReceipt.of(project, file, takeID = newID(), segmentID = segment, sequence = 0, started = 42L, gainDB = 3.0)
        TakeReceipt.write(receipt, file)
        assertEquals(TakeReceipt.RESERVE_BYTES.toLong(), TakeReceipt.reserve(file).length())
        assertEquals(receipt, TakeReceipt.read(file, project.id))
        assertNull(receipt.insertion)
        assertNull(receipt.revision)
        // Another project's, or another movie's: refused.
        assertThrows(ProjectException::class.java) { TakeReceipt.read(file, newID()) }
        val other = movie(folder, newID())
        TakeReceipt.location(file).copyTo(TakeReceipt.location(other))
        assertThrows(ProjectException::class.java) { TakeReceipt.read(other, project.id) }
        TakeReceipt.remove(file)
        assertNull(TakeReceipt.read(file, project.id))
        assertFalse(TakeReceipt.reserve(file).exists())
    }

    /** YouRec/ProjectRecovery.swift:327-345: reserves no segment holds go; one without its movie counts. */
    @Test fun abandonedReservesAreReclaimed() {
        val folder = temporary.newFolder("p")
        val project = Project("Trip")
        val kept = movie(folder, newID()).apply { writeText("movie") }
        val lost = movie(folder, newID())
        val live = movie(folder, newID())
        for (file in listOf(kept, lost, live)) TakeReceipt.write(TakeReceipt.of(project, file, newID(), file.nameWithoutExtension.substringAfter('-'), 0, 0, 0.0), file)
        assertEquals(1, TakeReceipt.reclaimAbandonedReserves(folder, live = setOf(TakeReceipt.reserve(live))))
        assertFalse(TakeReceipt.reserve(kept).exists())
        assertFalse(TakeReceipt.reserve(lost).exists())
        assertTrue(TakeReceipt.reserve(live).exists())
        // The receipts themselves stay, for the takes to be indexed by.
        assertTrue(TakeReceipt.location(kept).exists())
    }

    /** YouRec/Project.swift:384: a take that was going to the end goes there, whatever place takes go to now. */
    @Test fun aTakeForTheEndGoesToTheEnd() {
        val first = take()
        val base = Project("Trip").addRecording(first)
        val late = take()
        val receipt = TakeReceipt.of(base, File("100-${late.id}.mp4"), newID(), late.id, 0, 0, 2.0)
        val moved = base.beginRecordingInsertion(1.0)
        val indexed = moved.indexTake(late, receipt, uncertain = false)
        assertTrue(indexed.placed)
        assertEquals(listOf(first.id, late.id), indexed.project.edit.clips.map { it.sourceID })
        assertEquals(1.0, indexed.project.recordingInsertion)
        // The clip has the take's recording gain as its level.
        assertEquals(RecordingGain.normalized(2.0), indexed.project.edit.clips.last().audio?.volumeDB ?: 0.0, 1e-9)
    }

    /** YouRec/Project.swift:385-391: a take going into the edit goes there while the edit is as it was. */
    @Test fun aTakeGoesIntoTheEditOnlyWhileItIsAsItWas() {
        val first = take(duration = 10.0)
        val project = Project("Trip").addRecording(first).beginRecordingInsertion(4.0)
        val late = take()
        val receipt = TakeReceipt.of(project, File("100-${late.id}.mp4"), newID(), late.id, 0, 0, 0.0)
        assertEquals(4.0, receipt.insertion)
        val placed = project.indexTake(late, receipt, uncertain = false)
        assertTrue(placed.placed)
        assertEquals(listOf(first.id, late.id, first.id), placed.project.edit.clips.map { it.sourceID })
        // Edited since: the take only joins the recordings.
        val edited = project.commit(TimelineEditing.split(project.edit, 2.0)!!)
        val kept = edited.indexTake(late, receipt, uncertain = false)
        assertFalse(kept.placed)
        assertTrue(kept.project.sources.any { it.id == late.id })
        assertEquals(edited.edit, kept.project.edit)
    }

    /** YouRec/Project.swift:376-381: known, sound, uncertain or already indexed: only joins the recordings, or nothing. */
    @Test fun uncertainTakesOnlyJoinTheRecordings() {
        val project = Project("Trip").addRecording(take())
        val late = take()
        val receipt = TakeReceipt.of(project, File("100-${late.id}.mp4"), newID(), late.id, 0, 0, 0.0)
        assertFalse(project.indexTake(late, receipt, uncertain = true).placed)
        assertFalse(project.indexTake(late, receipt.copy(indexed = true), uncertain = false).placed)
        assertEquals(1, project.indexTake(late, receipt, uncertain = true).project.edit.clips.size)
        val sound = late.copy(audioOnly = true)
        assertFalse(project.indexTake(sound, null, uncertain = false).placed)
        val known = project.indexTake(project.sources.single(), null, uncertain = false)
        assertEquals(project, known.project)
        // Another segment's receipt is refused.
        assertThrows(ProjectException::class.java) { project.indexTake(late, receipt.copy(segmentID = newID()), uncertain = false) }
        // Without a receipt, the take goes where the next one would, as before.
        assertTrue(project.indexTake(late, null, uncertain = false).placed)
    }
}
