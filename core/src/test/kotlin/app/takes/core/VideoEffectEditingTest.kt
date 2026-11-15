package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoEffectEditingTest {
    private val clips = listOf(
        TimelineClip(id = "A", sourceID = "S1", start = 0.0, end = 4.0),
        TimelineClip(id = "B", sourceID = "S1", start = 6.0, end = 9.0),
    )
    private val state = EditState(clips = clips)
    private val noir = VideoEffectSettings(VideoEffectID.Noir, start = 6.0, end = 9.0)

    @Test fun putsTheListOnTheNamedClipOnly() {
        val next = VideoEffectEditing.setEffects(state, mapOf("B" to listOf(noir)))!!
        assertNull(next.clips[0].videoEffects)
        assertEquals(listOf(noir), next.clips[1].videoEffects)
        assertEquals(state.clips.map { it.id }, next.clips.map { it.id })
    }

    @Test fun anEmptyListTakesTheEffectsOff() {
        val on = VideoEffectEditing.setEffects(state, mapOf("B" to listOf(noir)))!!
        val off = VideoEffectEditing.setEffects(on, mapOf("B" to emptyList()))!!
        assertNull(off.clips[1].videoEffects)
    }

    @Test fun nothingChangedIsNull() {
        assertNull(VideoEffectEditing.setEffects(state, mapOf("A" to emptyList())))
        assertNull(VideoEffectEditing.setEffects(state, emptyMap()))
        val on = VideoEffectEditing.setEffects(state, mapOf("B" to listOf(noir)))!!
        assertNull(VideoEffectEditing.setEffects(on, mapOf("B" to listOf(noir))))
    }
}
