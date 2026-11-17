package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trimming of processed sound, against YouRec/SourceMediaCache.swift:68-122, 344, 446-452. The original
 * has no test of its own for it; the numbers are its source's.
 */
class ProcessingCacheTest {
    private val gb = 1_000_000_000L

    /** YouRec/SourceMediaCache.swift:106-108: a tenth of what is free and of what is held, within 2 and 8 GB. */
    @Test fun theQuotaIsATenthOfTheFreeAndTheHeldWithin2And8GB() {
        assertEquals(2 * gb, ProcessingCache.quota(10 * gb, 1 * gb))
        assertEquals(5 * gb, ProcessingCache.quota(40 * gb, 10 * gb))
        assertEquals(8 * gb, ProcessingCache.quota(200 * gb, 10 * gb))
    }

    /** YouRec/SourceMediaCache.swift:344, 452: eight bytes a frame at 48 kHz for each stage and three more. */
    @Test fun theRoomMadeBeforeProcessing() {
        assertEquals(10L * 48_000 * 8 * 5, ProcessingCache.required(10.0, 2))
        assertEquals(0L, ProcessingCache.required(-1.0, 2))
    }

    /** YouRec/SourceMediaCache.swift:446-450: a hundred for each model, ten more when the last stage is one. */
    @Test fun aModelsResultIsWorthMoreThanItsToneChanges() {
        assertEquals(110, ProcessingCache.priority(listOf(true)))
        assertEquals(100, ProcessingCache.priority(listOf(true, false)))
        assertEquals(210, ProcessingCache.priority(listOf(true, false, true)))
        assertEquals(0, ProcessingCache.priority(listOf(false, false)))
        assertEquals(0, ProcessingCache.priority(emptyList()))
    }

    @Test fun theProcessedFilesAreKnownByName() {
        assertTrue(ProcessingCache.isProcessed("1700-ab.mp4.clean-80.wav"))
        assertTrue(ProcessingCache.isProcessed("import-1-x.mov.chain-0123456789abcdef01234567.wav"))
        assertTrue(ProcessingCache.isProcessed("import-1-x.mov.chain-0123456789abcdef01234567.window-0-12000000.wav"))
        assertTrue(ProcessingCache.isProcessed("t.mp4.clean-100.window-5000000-17000000.wav"))
        // What it is not: a hidden file being written, waveforms, covers, masks, the recordings themselves.
        assertFalse(ProcessingCache.isProcessed(".t.mp4.clean-80.wav.tmp"))
        assertFalse(ProcessingCache.isProcessed(".t.mp4.clean-80.wav"))
        assertFalse(ProcessingCache.isProcessed("t.mp4.peaks"))
        assertFalse(ProcessingCache.isProcessed("t.mp4.chain-0123.wav"))
        assertFalse(ProcessingCache.isProcessed("t.mp4"))
        assertTrue(ProcessingCache.isWorking(".t.mp4.clean-80.wav.tmp"))
        assertFalse(ProcessingCache.isWorking("t.mp4.clean-80.wav"))
    }

    /** YouRec/SourceMediaCache.swift:109-120: the cheapest first, then the oldest, never one in use, down to the quota. */
    @Test fun theCheapestAndOldestGoFirstDownToTheQuota() {
        val entries = listOf(
            ProcessingCache.Entry("a", 1 * gb, modified = 1, priority = 110),
            ProcessingCache.Entry("window-old", 1 * gb, modified = 2, priority = 0),
            ProcessingCache.Entry("window-new", 1 * gb, modified = 9, priority = 0),
            ProcessingCache.Entry("b", 1 * gb, modified = 0, priority = 100),
            ProcessingCache.Entry("in use", 1 * gb, modified = 0, priority = 0, pinned = true),
        )
        // 5 GB held, 10 GB free: the quota is 2 GB (a tenth of each is 1.5 GB).
        val trim = ProcessingCache.trim(entries, 10 * gb, 0)
        assertEquals(listOf("window-old", "window-new", "b"), trim.remove.map { it.path })
        assertTrue(trim.enough)
    }

    /** YouRec/SourceMediaCache.swift:110-122: within the quota, files still go until what is asked and the margin are free. */
    @Test fun roomForWhatIsAboutToBeWritten() {
        val entries = listOf(
            ProcessingCache.Entry("a", 300_000_000, modified = 1, priority = 0),
            ProcessingCache.Entry("b", 300_000_000, modified = 2, priority = 0),
        )
        assertEquals(emptyList<ProcessingCache.Entry>(), ProcessingCache.trim(entries, 1 * gb, 100_000_000).remove)
        val tight = ProcessingCache.trim(entries, 500_000_000, 400_000_000)
        assertEquals(listOf("a"), tight.remove.map { it.path })
        assertTrue(tight.enough)
        // Not enough even with everything gone: the processing is refused.
        val short = ProcessingCache.trim(entries, 100_000_000, 2 * gb)
        assertEquals(listOf("a", "b"), short.remove.map { it.path })
        assertFalse(short.enough)
    }
}
