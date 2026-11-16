package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MovieLengthTest {
    private val clip = TimelineClip(id = "C", sourceID = "A", start = 0.0, end = 3.0)
    private val past = AudioItem(id = "M", sourceID = "B", start = 1.0, sourceStart = 2.0, sourceEnd = 7.0, audio = ClipAudio(fadeOut = 1.0))
    private val late = AudioItem(id = "L", sourceID = "B", start = 3.5, sourceStart = 0.0, sourceEnd = 1.0, track = 1)
    private val state = EditState(listOf(clip), audio = listOf(past, late))

    @Test fun aProjectKeepsThePicturesLengthUntilItTakesItsSounds() {
        assertEquals(3.0, MovieLength.of(state, adopted = false), 1e-9)
        assertEquals(6.0, MovieLength.of(state, adopted = true), 1e-9)
        assertEquals(3.0, MovieLength.pastPicture(state), 1e-9)
        assertEquals(6.0, TrackMix.plan(state.toTimeline()).duration.seconds, 1e-6)
    }

    @Test fun endingWithThePictureCutsTheSoundThereAndLeavesOutWhatBeginsLater() {
        val trimmed = MovieLength.trimmed(state)!!
        assertEquals(listOf("M"), trimmed.audio!!.map { it.id })
        assertEquals(4.0, trimmed.audio!!.single().sourceEnd, 1e-9)
        assertEquals(3.0, trimmed.audio!!.single().end, 1e-9)
        assertEquals(0.0, MovieLength.pastPicture(trimmed), 1e-9)
        assertEquals(3.0, TrackMix.plan(trimmed.toTimeline()).duration.seconds, 1e-6)
        assertNull(MovieLength.trimmed(trimmed))
    }
}
