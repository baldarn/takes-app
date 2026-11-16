package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The recovery rules. Tests marked TRANSLATED carry over the iPhone app's
 * projectRecoveryTests (Tests/ProjectRecoveryTests.swift:68-148) against
 * YouRec/ProjectRecovery.swift:299-464 and YouRec/RecordingSalvage.swift:54-152.
 * The original repaired a real fragmented movie cut at 90 %; here the boxes
 * are built by hand, and the repair itself stays with the app.
 */
class RecoveryRulesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun box(type: String, body: Int, extended: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        val size = (if (extended) 16 else 8) + body
        fun int(value: Long, bytes: Int) { for (i in bytes - 1 downTo 0) out.write(((value shr (8 * i)) and 0xFF).toInt()) }
        int(if (extended) 1 else size.toLong(), 4)
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        if (extended) int(size.toLong(), 8)
        out.write(ByteArray(body))
        return out.toByteArray()
    }

    private fun movie(vararg boxes: ByteArray) = boxes.fold(ByteArray(0)) { a, b -> a + b }

    private val ftyp = box("ftyp", 12)
    private val moov = box("moov", 100)
    private fun fragment(n: Int) = box("moof", 40) + box("mdat", n)

    /** TRANSLATED ProjectRecoveryTests.swift:145-148. */
    @Test fun theStorageFloorIsBounded() {
        assertEquals(512_000_000L, RecoveryRules.floor(16_000_000_000))
        assertEquals(2_000_000_000L, RecoveryRules.floor(256_000_000_000))
        assertEquals(1_000_000_000L, RecoveryRules.floor(50_000_000_000))
    }

    /** TRANSLATED ProjectRecoveryTests.swift:149-153: an allocation that would use the margin is refused. */
    @Test fun writingIntoTheFloorIsRefusedWithTheAmountToFree() {
        val total = 64_000_000_000L
        val free = 2_000_000_000L
        assertNull(RecoveryRules.shortOfSpace(free - 1_280_000_000, free, total) { "$it B" })
        val message = RecoveryRules.shortOfSpace(free, free, total) { "$it B" }!!
        assertTrue(message, message.startsWith("Free about 1280000000 B more space"))
        assertTrue(RecoveryRules.shortOfSpace(-1, free, total) { "$it" } != null)
    }

    /** TRANSLATED ProjectRecoveryTests.swift:136-144: a torn movie keeps the fragments that closed. */
    @Test fun theCompleteStartOfATornMovieEndsAtTheLastClosedFragment() {
        val whole = movie(ftyp, moov, fragment(500), fragment(500), fragment(500))
        val torn = whole.copyOf(whole.size * 90 / 100)
        val expected = (ftyp.size + moov.size + 2 * fragment(500).size).toLong()
        assertEquals(expected, RecoveryRules.completePrefix(torn))
        val file = File(temporary.root, "torn.mov").apply { writeBytes(torn) }
        assertEquals(expected, RecoveryRules.completePrefix(file))
        assertTrue(file.readBytes() contentEquals torn)
    }

    @Test fun aMovieWithNothingToRepairOrNothingToKeepHasNoStart() {
        assertNull(RecoveryRules.completePrefix(movie(ftyp, moov, fragment(10))))
        assertNull(RecoveryRules.completePrefix(movie(ftyp, fragment(10), fragment(10)).copyOf(80)))
        val noMoov = movie(ftyp, fragment(10), fragment(10))
        assertNull(RecoveryRules.completePrefix(noMoov.copyOf(noMoov.size - 1)))
        val broken = movie(ftyp, moov, fragment(10), box("abcd", 4).also { it[3] = 3 }, fragment(10))
        assertEquals((ftyp.size + moov.size + fragment(10).size).toLong(), RecoveryRules.completePrefix(broken))
        val extended = movie(ftyp, moov, box("moof", 8, extended = true), box("mdat", 20, extended = true), box("free", 3))
        assertEquals((extended.size - 11).toLong(), RecoveryRules.completePrefix(extended))
    }

    @Test fun salvageBudgetsAndUnpublishedCopies() {
        assertEquals(1000L + 100 + 1_048_576, RecoveryRules.copyBudget(1000))
        assertTrue(RecoveryRules.isUnpublishedCopy("a".repeat(64) + ".mov"))
        assertFalse(RecoveryRules.isUnpublishedCopy("g".repeat(64) + ".mov"))
        assertFalse(RecoveryRules.isUnpublishedCopy("a".repeat(63) + ".mov"))
        assertFalse(RecoveryRules.isUnpublishedCopy("a".repeat(64) + ".mp4"))
        assertTrue(RecoveryRules.plausibleDuration(4.0))
        assertFalse(RecoveryRules.plausibleDuration(0.0))
        assertFalse(RecoveryRules.plausibleDuration(700_000.0))
        assertEquals(listOf(0.0, 3.6), RecoveryRules.probeOffsets(4.0).map { Math.round(it * 10) / 10.0 })
        assertEquals(listOf(0.0), RecoveryRules.probeOffsets(0.3))
    }

    /** TRANSLATED ProjectRecoveryTests.swift:78-81 and 99-101: a receipt reads only beside its own segment. */
    @Test fun aTakeReceiptNamesItsOwnRecording() {
        val project = "0B3F9B1C-8A4E-4F3D-9E2A-1C2D3E4F5A6B"
        val segment = "6F1E2D3C-4B5A-4978-8695-A4B3C2D1E0F9"
        val name = "123-$segment.mov"
        assertTrue(RecoveryRules.receiptMatches(1, project, project.lowercase(), name, name, 0, segment))
        assertFalse(RecoveryRules.receiptMatches(2, project, project, name, name, 0, segment))
        assertFalse(RecoveryRules.receiptMatches(1, project, segment, name, name, 0, segment))
        assertFalse(RecoveryRules.receiptMatches(1, project, project, name, "other.mov", 0, segment))
        assertFalse(RecoveryRules.receiptMatches(1, project, project, name, name, -1, segment))
        assertFalse(RecoveryRules.receiptMatches(1, project, project, name, name, 0, project))
        assertTrue(RecoveryRules.receiptMatches(1, project, project, "1-$segment.mp4", "1-$segment.mp4", 0, segment.lowercase(), "mp4"))
        assertTrue(RecoveryRules.isReclaimableReserve("123-$segment.reserve", live = false))
        assertFalse(RecoveryRules.isReclaimableReserve("123-$segment.reserve", live = true))
        assertFalse(RecoveryRules.isReclaimableReserve("123-$segment.json", live = false))
        assertFalse(RecoveryRules.isReclaimableReserve("short.reserve", live = false))
    }

    /** TRANSLATED ProjectRecoveryTests.swift:68-75: a durable draft is offered until it is saved. */
    @Test fun aPendingDraftIsOfferedOnlyOnTopOfItsBase() {
        val base = "head".toByteArray()
        val draft = "draft".toByteArray()
        assertTrue(RecoveryRules.draftApplies(base, draft, sha256(base)))
        assertFalse(RecoveryRules.draftApplies(draft, draft, sha256(base)))
        assertFalse(RecoveryRules.draftApplies("other".toByteArray(), draft, sha256(base)))
        assertTrue(RecoveryRules.draftApplies(null, draft, null))
        assertTrue(RecoveryRules.draftCommitted(draft.copyOf(), draft))
        assertFalse(RecoveryRules.draftCommitted(base, draft))
    }
}
