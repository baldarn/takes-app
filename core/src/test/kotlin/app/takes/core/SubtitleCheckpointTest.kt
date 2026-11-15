package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Test

// The original reuses a checkpoint only when range, language and plan are unchanged (YouRec/SubtitleTranscriber.swift:45-50).
class SubtitleCheckpointTest {
    private val stretches = listOf(SubtitleStretch("A", 0.0, 30.0), SubtitleStretch("B", 2.0, 8.0))
    private val plan = listOf(SubtitleStretch("A", 0.0, 12.0), SubtitleStretch("A", 12.0, 24.0), SubtitleStretch("A", 24.0, 30.0), SubtitleStretch("B", 2.0, 8.0))
    private val saved = SubtitleCheckpoint(stretches, "it", plan, 2, mapOf("A" to listOf(SubtitleWord(text = "Ciao", start = 1.0, end = 1.4))))

    @Test fun theSameFootageLanguageAndPlanGoOnAfterTheFinishedPieces() {
        assertEquals(2, saved.reusable(stretches, "it", plan))
        assertEquals(2, SubtitleCheckpoint.reusable(saved, stretches.toList(), "it", plan.toList()))
    }

    @Test fun anotherLanguageStartsAgain() {
        assertEquals(0, saved.reusable(stretches, "en", plan))
        assertEquals(0, saved.reusable(stretches, null, plan))
    }

    @Test fun otherFootageStartsAgain() {
        assertEquals(0, saved.reusable(stretches.dropLast(1), "it", plan))
        assertEquals(0, saved.reusable(listOf(SubtitleStretch("A", 0.0, 29.0), stretches[1]), "it", plan))
    }

    @Test fun anotherPlanStartsAgain() {
        // The same footage cut into other pieces, as when its sound was replaced.
        assertEquals(0, saved.reusable(stretches, "it", plan.take(2) + SubtitleStretch("A", 24.0, 30.0)))
    }

    @Test fun noCheckpointOrOneThatDoesNotFitStartsFromTheFirstPiece() {
        assertEquals(0, SubtitleCheckpoint.reusable(null, stretches, "it", plan))
        assertEquals(0, saved.copy(completed = plan.size + 1).reusable(stretches, "it", plan))
        assertEquals(plan.size, saved.copy(completed = plan.size).reusable(stretches, "it", plan))
    }
}
