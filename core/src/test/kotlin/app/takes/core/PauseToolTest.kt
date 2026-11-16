package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Pauses tool's arithmetic, as the original's tool workspace (YouRec/EditorTools.swift at faf1815). */
class PauseToolTest {
    private val tiny = 1e-9
    private fun clip(id: String, end: Double = 10.0, start: Double = 0.0) = TimelineClip(id = id, sourceID = "S", start = start, end = end)
    private val two = EditState(listOf(clip("A"), clip("B", end = 6.0)))
    private val settings = PauseSettings(before = 0.1, after = 0.2)

    /** EditorTools.swift:618-640: each scoped clip gets its pauses, the cuts padded and merged; one without a pause gets none. */
    @Test fun theEditGivesEachClipItsPausesAndOnlyItsOwn() {
        val choices = listOf(PauseChoice("A", SourceInterval(2.0, 3.0)), PauseChoice("A", SourceInterval(5.0, 6.0), enabled = false))
        val made = PauseTool.state(two, listOf("A", "B"), settings, choices, mapOf("A" to -44.0), "P1")
        val a = made.clips[0].pauses!!
        assertEquals("P1", a.id)
        assertEquals(-44.0, a.thresholdDB, tiny) // automatic: the level the clip was scanned at
        assertEquals(listOf(DetectedPause(SourceInterval(2.0, 3.0)), DetectedPause(SourceInterval(5.0, 6.0), enabled = false)), a.candidates)
        assertEquals(SourceInterval(0.0, 10.0), a.analyzed)
        assertEquals(1, a.cuts.size)
        assertEquals(2.1, a.cuts[0].start, tiny)
        assertEquals(2.8, a.cuts[0].end, tiny)
        assertNull(made.clips[1].pauses)
        assertEquals(9.3, made.clips[0].duration, tiny)
        assertEquals(1 to 0.7, PauseTool.summary(made, listOf("A", "B")).let { it.first to Math.round(it.second * 1e9) / 1e9 })
        // A manual level is the one set.
        assertEquals(-30.0, PauseTool.state(two, listOf("A"), settings.copy(levelMode = PauseLevelMode.Manual, thresholdDB = -30.0), choices, mapOf("A" to -44.0), "P1").clips[0].pauses!!.thresholdDB, tiny)
        // Removing puts every pause back.
        val removed = PauseTool.state(made, listOf("A", "B"), settings, choices, emptyMap(), "P1", removing = true)
        assertEquals(listOf(10.0, 6.0), removed.clips.map { it.duration })
        assertTrue(removed.clips.all { it.pauses == null })
    }

    /** EditorTools.swift:444-447, :1033-1041: the tool opens on the saved settings and choices; cuts saved without candidates come back widened by their padding. */
    @Test fun theToolOpensAgainOnWhatWasSaved() {
        val choices = listOf(PauseChoice("A", SourceInterval(2.0, 3.0)), PauseChoice("A", SourceInterval(5.0, 6.0), enabled = false))
        val made = PauseTool.state(two, listOf("A", "B"), settings, choices, mapOf("A" to -44.0), "P1")
        val (opened, levels) = PauseTool.opened(made, listOf("A", "B"))
        assertEquals(choices, opened)
        assertEquals(mapOf("A" to -44.0), levels)
        assertEquals(made.clips[0].pauses, PauseTool.settings(made, listOf("A", "B")))
        assertTrue(PauseTool.saved(made, listOf("B", "A")))
        assertFalse(PauseTool.saved(made, listOf("B")))
        assertEquals(PauseSettings().copy(id = "X"), PauseTool.settings(two, listOf("A")).copy(id = "X"))
        val legacy = two.copy(clips = listOf(clip("A").copy(pauses = PauseSettings(before = 0.1, after = 0.2, cuts = listOf(SourceInterval(2.1, 2.8))))))
        val back = PauseTool.opened(legacy, listOf("A")).first.single()
        assertEquals(2.0, back.raw.start, tiny)
        assertEquals(3.0, back.raw.end, tiny)
    }

    /** EditorTools.swift:385-387, :963-967, :1013-1019: a Keep survives a new scan that finds the pause again, a little moved. */
    @Test fun aKeptPauseStaysKeptThroughANewScan() {
        val kept = PauseTool.keeping(emptyList(), SourceInterval(5.0, 6.0), enabled = false)
        assertEquals(listOf(SourceInterval(5.0, 6.0)), kept)
        val again = PauseTool.found("A", listOf(SourceInterval(2.0, 3.0), SourceInterval(5.2, 6.1)), kept)
        assertEquals(listOf(true, false), again.map { it.enabled })
        // Set to Cut again, it is forgotten.
        assertEquals(emptyList<SourceInterval>(), PauseTool.keeping(kept, SourceInterval(5.2, 6.1), enabled = true))
        assertTrue(PauseTool.same(SourceInterval(0.0, 1.0), SourceInterval(0.4, 2.0)))
        assertFalse(PauseTool.same(SourceInterval(0.0, 1.0), SourceInterval(0.5, 2.0)))
    }

    /** EditorTools.swift:980-989: the padding is applied to what was found; the level and the shortest pause ask for a scan. */
    @Test fun onlyTheLevelAndTheShortestPauseScanAgain() {
        assertFalse(PauseTool.rescans(settings, settings.copy(before = 0.3, after = 0.0)))
        assertTrue(PauseTool.rescans(settings, settings.copy(minimum = 0.4)))
        assertTrue(PauseTool.rescans(settings, settings.copy(thresholdDB = -20.0)))
        assertTrue(PauseTool.rescans(settings, settings.copy(levelMode = PauseLevelMode.Manual)))
    }

    /** EditorTools.swift:990-1007, :1020-1026: what a pause cuts, where it plays in the movie, and the stretch Listen plays. */
    @Test fun aPausePlaysWhereItsClipPlaysIt() {
        val choice = PauseChoice("B", SourceInterval(2.0, 3.0))
        assertEquals(0.7, PauseTool.cutSeconds(two.clips[1], choice.raw, settings), tiny)
        assertEquals(0.0, PauseTool.cutSeconds(two.clips[1], choice.raw, settings.copy(before = 0.6, after = 0.6)), tiny)
        // Before: the whole pause, 10 s in for the second clip.
        val before = PauseTool.range(two, choice, settings, original = true)
        assertEquals(12.0, before.start, tiny)
        assertEquals(13.0, before.endInclusive, tiny)
        // After: where it is cut, what is left of it around the cut.
        val made = PauseTool.state(two, listOf("B"), settings, listOf(choice), emptyMap(), "P")
        val after = PauseTool.range(made, choice, settings, original = false)
        assertEquals(12.1, after.start, tiny)
        assertEquals(12.1, after.endInclusive, tiny)
        // A kept pause plays whole, from where its first second shows.
        val keptRange = PauseTool.range(made, choice.copy(enabled = false), settings, original = false)
        assertEquals(12.0, keptRange.start, tiny)
        val listen = PauseTool.listening(after, made.duration)
        assertEquals(11.35, listen.start, tiny)
        assertEquals(12.85, listen.endInclusive, tiny)
        assertEquals(0.0, PauseTool.listening(0.2..0.5, 10.0).start, tiny)
        assertEquals(10.0, PauseTool.listening(9.5..9.9, 10.0).endInclusive, tiny)
    }

    @Test fun afterAScanTheToolStaysOnTheNearestPauseOfTheSameClip() {
        val choices = listOf(PauseChoice("A", SourceInterval(1.0, 2.0)), PauseChoice("B", SourceInterval(4.0, 5.0)), PauseChoice("A", SourceInterval(6.0, 7.0)))
        assertEquals(2, PauseTool.nearest(choices, PauseChoice("A", SourceInterval(5.0, 6.0))))
        assertNull(PauseTool.nearest(choices, null))
        assertNull(PauseTool.nearest(choices, PauseChoice("C", SourceInterval(5.0, 6.0))))
    }
}
