package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSpeechTest {
    /** Ten seconds of faint room tone in 20 ms slices, loud in the given slice ranges. */
    private fun levels(vararg loud: IntRange) = FloatArray(500) { i -> if (loud.any { i in it }) 0.1f else 0.0001f }

    // Translated from the original app's SubtitleTests.swift.

    @Test fun silenceDoesNotInvokeTheRecognizer() {
        assertTrue(SubtitleSpeechPlan.chunks(FloatArray(2000), 40.0).isEmpty())
    }

    @Test fun continuousSpeechStaysInBoundedChunks() {
        val continuous = SubtitleSpeechPlan.chunks(FloatArray(5000) { 0.1f }, 100.0)
        assertTrue(continuous.size >= 9)
        assertTrue(continuous.all { it.range.end - it.range.start <= 12.001 })
    }

    // The tests below are this port's own.

    @Test fun continuousSpeechIsCoveredWithoutGapsOrOverlaps() {
        val continuous = SubtitleSpeechPlan.chunks(FloatArray(5000) { 0.1f }, 100.0)
        assertEquals(0.0, continuous.first().range.start, 0.0)
        assertEquals(100.0, continuous.last().range.end, 0.0)
        assertTrue(continuous.zipWithNext().all { (a, b) -> a.range.end == b.range.start })
        assertTrue(continuous.all { it.speech == listOf(it.range) })
    }

    @Test fun anIslandIsWidenedAroundTheLoudSlices() {
        val chunks = SubtitleSpeechPlan.chunks(levels(50 until 150), 10.0)
        assertEquals(1, chunks.size)
        assertEquals(0.92, chunks[0].range.start, 1e-9)
        assertEquals(3.1, chunks[0].range.end, 1e-9)
        assertEquals(listOf(chunks[0].range), chunks[0].speech)
    }

    @Test fun aShortPauseKeepsTwoIslandsInOneChunk() {
        val chunks = SubtitleSpeechPlan.chunks(levels(50 until 150, 170 until 270), 10.0)
        assertEquals(1, chunks.size)
        assertEquals(0.92, chunks[0].range.start, 1e-9)
        assertEquals(5.5, chunks[0].range.end, 1e-9)
        // The pause stays out of the islands, so no word is timed inside it.
        assertEquals(2, chunks[0].speech.size)
        assertEquals(3.1, chunks[0].speech[0].end, 1e-9)
        assertEquals(3.32, chunks[0].speech[1].start, 1e-9)
    }

    @Test fun aLongPauseStartsANewChunk() {
        val chunks = SubtitleSpeechPlan.chunks(levels(50 until 150, 400 until 450), 10.0)
        assertEquals(2, chunks.size)
        assertEquals(7.92, chunks[1].range.start, 1e-9)
        assertEquals(9.1, chunks[1].range.end, 1e-9)
    }

    @Test fun aClickIsNotSpeech() {
        // Two loud slices are too short to be a word.
        assertTrue(SubtitleSpeechPlan.chunks(levels(300 until 302), 10.0).isEmpty())
        assertEquals(1, SubtitleSpeechPlan.chunks(levels(300 until 303), 10.0).size)
    }

    @Test fun islandsNeverPassTheEndOfTheSound() {
        val chunks = SubtitleSpeechPlan.chunks(levels(450 until 500), 10.0)
        assertEquals(10.0, chunks.single().range.end, 0.0)
        assertTrue(SubtitleSpeechPlan.chunks(FloatArray(0), 10.0).isEmpty())
        assertTrue(SubtitleSpeechPlan.chunks(levels(50 until 150), 0.0).isEmpty())
    }

    @Test fun longSpeechIsSplitAtItsQuietestMoment() {
        // Fifteen seconds of speech with a softer syllable around 10 s.
        val speech = FloatArray(1000) { i -> if (i >= 750) 0.0001f else if (i in 498..502) 0.02f else 0.1f }
        val chunks = SubtitleSpeechPlan.chunks(speech, 20.0)
        assertEquals(2, chunks.size)
        assertEquals(10.0, chunks[0].range.end, 1e-9)
        assertEquals(chunks[0].range.end, chunks[1].range.start, 0.0)
    }
}
