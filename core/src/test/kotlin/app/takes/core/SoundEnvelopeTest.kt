package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The level of the clips' and the sounds' samples, built from the edit as
 * the original builds it for its tracks (TrackRenderer.swift:265-296) and
 * combined as its tap combines it (CutAudioEnvelope.swift:30-55).
 */
class SoundEnvelopeTest {
    private val a = TimelineClip(id = "A", sourceID = "S1", start = 0.0, end = 4.0)
    private val b = TimelineClip(id = "B", sourceID = "S2", start = 1.0, end = 4.0)
    private val c = TimelineClip(id = "C", sourceID = "S3", start = 0.0, end = 2.0)
    private val clips = listOf(a, b, c)
    private val fade = AudioLevels.CUT_FADE
    private val tiny = 1e-9

    private fun gain(clips: List<TimelineClip>, index: Int, time: Double) = SoundEnvelope.clip(clips, index)?.gain(time) ?: 0.0

    /** CutAudioEnvelope.swift:32: 8 ms up from silence after a cut and 8 ms down to it before the next, on every run. */
    @Test fun aPlainCutFadesEightMillisecondsOnEachSide() {
        val first = SoundEnvelope.clip(clips, 0)!!
        assertEquals(0.0, first.start, 0.0); assertEquals(4.0, first.end, 0.0)
        assertEquals(fade, first.fadeIn, 0.0); assertEquals(fade, first.fadeOut, 0.0)
        assertEquals(0.0, gain(clips, 0, 0.0), 0.0)
        assertEquals(0.5, gain(clips, 0, 0.004), tiny)
        assertEquals(1.0, gain(clips, 0, 0.008), tiny)
        assertEquals(1.0, gain(clips, 0, 2.0), 0.0)
        assertEquals(0.5, gain(clips, 0, 3.996), tiny)
        assertEquals(0.0, gain(clips, 0, 4.0), 0.0)
        // The next clip comes up over the following 8 ms: silence at the cut itself, nothing overlapping.
        assertEquals(0.25, gain(clips, 1, 4.002), tiny)
        assertEquals(1.0, gain(clips, 1, 4.008), tiny)
        assertEquals(0.5, gain(clips, 2, 8.996), tiny)   // and the movie ends on one
        // The editor's level is the same without those fades.
        for (time in listOf(0.0, 0.004, 2.0, 3.996)) assertEquals(1.0, TimelineEditing.soundLevel(clips, 0, time), 0.0)
        assertEquals(1.0, TimelineEditing.soundLevel(clips, 0, 4.0), 0.0)
        assertEquals(1.0, TimelineEditing.soundLevel(clips, 1, 0.0), 0.0)
    }

    /** TrackRenderer.swift:281-296: sound that carries on across a cut has no fade there, and its level slides over 10 ms. */
    @Test fun aSplitNobodyTrimmedIsNotHeard() {
        val split = TimelineEditing.split(EditState(clips), 2.0)!!.clips
        assertTrue(SoundEnvelope.continues(split, 0))
        val (left, right) = SoundEnvelope.clip(split, 0)!! to SoundEnvelope.clip(split, 1)!!
        assertEquals(fade, left.fadeIn, 0.0); assertEquals(0.0, left.fadeOut, 0.0)
        assertEquals(0.0, right.fadeIn, 0.0); assertEquals(fade, right.fadeOut, 0.0)
        assertEquals("A", right.initialOwner); assertEquals(1.0, right.initialVolume!!, 0.0)
        for (time in listOf(1.99, 1.999, 1.9999999)) assertEquals(1.0, gain(split, 0, time), 0.0)
        for (time in listOf(2.0, 2.001, 2.5)) assertEquals(1.0, gain(split, 1, time), 0.0)

        // A level on the second piece alone comes in over 10 ms from the first piece's, never through silence.
        val quiet = TimelineEditing.setAudio(EditState(split), 1, ClipAudio(volumeDB = -6.0))!!.clips
        val half = AudioLevels.linear(-6.0)
        assertTrue(SoundEnvelope.continues(quiet, 0))
        assertEquals(1.0, gain(quiet, 1, 2.0), tiny)
        assertEquals((1 + half) / 2, gain(quiet, 1, 2.005), tiny)
        assertEquals(half, gain(quiet, 1, 2.01), tiny)
        assertEquals(half, gain(quiet, 1, 3.0), tiny)

        // Not the same sound: trimmed apart, another speed, one muted, one cleaned, another recording.
        fun broken(change: (TimelineClip) -> TimelineClip) = !SoundEnvelope.continues(listOf(split[0], change(split[1])) + split.drop(2), 0)
        assertTrue(broken { it.copy(start = it.start + 0.5) })
        assertTrue(broken { it.copy(speed = SpeedSettings(rate = 2.0)) })
        assertTrue(broken { it.copy(audio = ClipAudio(muted = true)) })
        assertTrue(broken { it.copy(audio = ClipAudio(denoise = 0.5)) })
        assertTrue(broken { it.copy(sourceID = "S9") })
        assertTrue(!SoundEnvelope.continues(clips, 0) && !SoundEnvelope.continues(clips, 2) && !SoundEnvelope.continues(clips, 7))
        val trimmed = listOf(split[0], split[1].copy(start = split[1].start + 0.5)) + split.drop(2)
        assertEquals(0.5, gain(trimmed, 0, 2.0 - fade / 2), tiny)
        assertEquals(0.5, gain(trimmed, 1, 2.0 + fade / 2), tiny)
    }

    /**
     * TrackRenderer.swift:286: the runs on the two sides of a cut carry on only when the same ramps shape both. A piece of
     * a split clip has the transition at its own outer end and the other piece has none, so such a cut gets its fades.
     */
    @Test fun aSplitBesideATransitionKeepsItsCutFades() {
        val joined = TimelineEditing.setTransition(EditState(clips), 0, 1.0, TransitionStyle.Dissolve)!!
        val split = TimelineEditing.split(joined, 1.0)!!.clips
        assertTrue(!SoundEnvelope.continues(split, 0))
        assertEquals(0.5, gain(split, 0, 1.0 - fade / 2), tiny)
        assertEquals(0.5, gain(split, 1, 1.0 + fade / 2), tiny)
    }

    /** CutAudioEnvelope.swift:36-39: the ramps of the transitions multiply, with the fades and the volume, exactly as before. */
    @Test fun transitionsRampTheSoundAsTheyDid() {
        for (style in listOf(TransitionStyle.Dissolve, TransitionStyle.FadeColor)) {
            val joined = TimelineEditing.setTransition(EditState(clips), 0, 1.0, style)!!
            val shaped = TimelineEditing.setAudio(joined, 0, ClipAudio(volumeDB = 6.0, fadeIn = 1.7))!!.clips
            val join = TimelineEditing.join(shaped, 1)!!
            val boosted = AudioLevels.linear(6.0)
            var compared = 0
            var time = 0.0
            while (time < TimelineEditing.duration(shaped)) {
                for (index in shaped.indices) {
                    val start = TimelineEditing.clipStart(shaped, index)
                    if (time < start || time >= start + shaped[index].duration) continue
                    val audio = shaped[index].audio ?: ClipAudio()
                    val expected = audio.gain * audio.fades.gain(time - start, shaped[index].duration) *
                        TransitionSound.clipLevel(time, TimelineEditing.entering(shaped, index)?.audioIn, TimelineEditing.leaving(shaped, index)?.audioOut)
                    assertEquals("$style clip $index at $time", expected, TimelineEditing.soundLevel(shaped, index, time - start), tiny)
                    // The export differs from it only within 8 ms of a cut, where it is lower.
                    val exported = gain(shaped, index, time)
                    val edge = minOf(time - start, start + shaped[index].duration - time)
                    if (edge >= fade) assertEquals("$style clip $index at $time", expected, exported, tiny) else assertTrue(exported <= expected + tiny)
                    compared++
                }
                time += 0.0173
            }
            assertTrue(compared > 400)
            assertEquals(join.audioOut, SoundEnvelope.clip(shaped, 0)!!.outRamp)
            assertEquals(join.audioIn, SoundEnvelope.clip(shaped, 1)!!.inRamp)
            assertEquals(boosted * TransitionSound.falling(join.time, join.audioOut), gain(shaped, 0, join.time), tiny)
        }
    }

    /** CutAudioEnvelope.swift:38: the person's two fades are the lower of the two, times everything else. */
    @Test fun fadesOfAnyLengthAndCutOnesShapeTheSound() {
        val shaped = listOf(a.copy(audio = ClipAudio(volumeDB = -6.5, fadeIn = 1.7, fadeOut = 4.0)), b, c)
        val level = AudioLevels.linear(-6.5)
        for (time in listOf(0.17, 0.34, 0.85, 1.36, 1.7)) assertEquals("at $time", level * minOf(time / 1.7, (4 - time) / 4.0), gain(shaped, 0, time), tiny)
        assertEquals(level * 0.25, gain(shaped, 0, 3.0), tiny)
        val cut = listOf(a.copy(audio = ClipAudio(fadeIn = 1.7, fadeInElapsed = 0.6)), b, c)
        assertEquals((0.6 + 0.5) / 1.7, gain(cut, 0, 0.5), tiny)
        assertEquals(0.5 * 0.6 / 1.7, TimelineEditing.soundLevel(cut, 0, 0.0) * 0.5, tiny)
        assertEquals(0.5 * (0.6 + 0.004) / 1.7, gain(cut, 0, 0.004), tiny)   // the fade of the cut on top
    }

    /** TrackRenderer.swift:221, 265: a muted item is left out of the mix. */
    @Test fun aMutedClipHasNoSound() {
        val muted = listOf(a.copy(audio = ClipAudio(volumeDB = 6.0, muted = true)), b, c)
        assertNull(SoundEnvelope.clip(muted, 0))
        assertNull(SoundEnvelope.clip(muted, 9))
        assertEquals(0.0, TimelineEditing.soundLevel(muted, 0, 1.0), 0.0)
        assertEquals(0.0, TimelineEditing.soundLevel(muted, 9, 1.0), 0.0)
        assertNull(SoundEnvelope.item(emptyList(), AudioItem(sourceID = "M", start = 0.0, sourceStart = 0.0, sourceEnd = 2.0, audio = ClipAudio(muted = true))))
    }

    @Test fun aSoundOnAnAudioTrackFadesAtItsEdgesAndWhereTheMovieEnds() {
        val item = AudioItem(id = "M", sourceID = "S9", start = 1.0, sourceStart = 3.0, sourceEnd = 9.0, track = 1, audio = ClipAudio(volumeDB = 3.5, fadeIn = 1.7, fadeOut = 0.5))
        val level = AudioLevels.linear(3.5)
        val segment = SoundEnvelope.item(listOf(item), item)!!
        assertEquals(1.0, segment.start, 0.0); assertEquals(7.0, segment.end, 0.0)
        assertEquals(0.0, segment.gain(1.0), 0.0)
        assertEquals(level * 0.5 * 0.004 / 1.7, segment.gain(1.004), tiny)   // half way up the fade of the cut as well
        assertEquals(level * 0.85 / 1.7, segment.gain(1.85), tiny)
        assertEquals(level, segment.gain(4.0), tiny)
        assertEquals(level * 0.5, segment.gain(6.75), tiny)
        assertEquals(level * 0.85 / 1.7, TimelineEditing.soundLevel(item, 0.85), tiny)
        assertEquals(0.0, TimelineEditing.soundLevel(item, 6.0), 1e-12)
        // The movie ends at 5: what is cut off there fades in 8 ms rather than clicking.
        val ending = SoundEnvelope.item(listOf(item), item, movieEnd = 5.0)!!
        assertEquals(level, ending.gain(4.99), tiny)
        assertEquals(level * 0.5, ending.gain(4.996), tiny)
        assertEquals(level, TimelineEditing.soundLevel(item, 3.996), tiny)

        // Two pieces of one sound, cut and left together, play as one; apart, each has its fades.
        val state = EditState(listOf(a.copy(end = 20.0)), audio = listOf(item.copy(audio = null)))
        val pieces = RangeEditing.delete(state, EditScope.Range(TimeRange(3.0, 3.0 + 1.0 / 30), EditTargets(audio = setOf("M"))))!!.state.audio!!
        assertTrue(!SoundEnvelope.continues(pieces[0], pieces[1]))
        val together = listOf(pieces[0], pieces[1].copy(start = pieces[0].end, sourceStart = pieces[0].sourceEnd, sourceEnd = pieces[0].sourceEnd + 1))
        assertTrue(SoundEnvelope.continues(together[0], together[1]))
        assertEquals(0.0, SoundEnvelope.item(together, together[0])!!.fadeOut, 0.0)
        assertEquals(0.0, SoundEnvelope.item(together, together[1])!!.fadeIn, 0.0)
        assertEquals(1.0, SoundEnvelope.item(together, together[1])!!.gain(together[1].start), 0.0)
        assertTrue(!SoundEnvelope.continues(together[0], together[1].copy(track = 0)))
        assertEquals(2, SoundEnvelope.segments(state.copy(audio = together)).count { it.owner != "A" })
    }

    /** What the export hands its samples: the same numbers through [AudioEnvelope.fill], one per frame. */
    @Test fun everySampleGetsItsOwnLevel() {
        val shaped = listOf(a.copy(audio = ClipAudio(volumeDB = 12.0, fadeIn = 1.7)), b, c)
        val segment = SoundEnvelope.clip(shaped, 0)!!
        val envelope = AudioEnvelope(listOf(segment))
        val gains = DoubleArray(4800)
        envelope.fill(0.5, gains.size, 48_000.0, gains)
        for (frame in gains.indices) assertEquals(segment.gain(0.5 + frame / 48_000.0), gains[frame], 0.0)
        assertTrue(gains.toList().zipWithNext().all { (before, after) -> after > before })
        assertEquals(AudioLevels.linear(12.0) * 0.5 / 1.7, gains[0], tiny)
    }

    /** AudioGain.swift:141-155: the selected sound alone at 0 dB and everything else, for [AudioHeadroom]. */
    @Test fun theSegmentsOfAnEditAreWhatHeadroomIsMeasuredOn() {
        val state = EditState(listOf(a.copy(audio = ClipAudio(volumeDB = 6.0)), b))
        val segments = SoundEnvelope.segments(state)
        val selection = AudioGainSelection(setOf("A"))
        val selected = AudioGainMix.replacing(segments, selection, AudioGainMix.Mode.Selected)
        val remainder = AudioGainMix.replacing(segments, selection, AudioGainMix.Mode.Remainder)
        assertEquals(1.0, selected[0].gain(2.0), tiny); assertEquals(0.0, selected[1].gain(5.0), tiny)
        assertEquals(0.0, remainder[0].gain(2.0), tiny); assertEquals(1.0, remainder[1].gain(5.0), tiny)
        assertEquals(AudioLevels.linear(-3.0), AudioGainMix.replacing(segments, selection, AudioGainMix.Mode.Audition(-3.0))[0].gain(2.0), tiny)
    }
}
