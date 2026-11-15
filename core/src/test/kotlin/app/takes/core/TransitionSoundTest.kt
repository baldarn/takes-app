package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The level of each side's sound through a transition, against the original:
 * the ranges come from `TransitionTiming` (YouRec/Transitions.swift:112-156)
 * and the ramp from `AudioEnvelopeSegment.gain` (YouRec/CutAudioEnvelope.swift:36-37):
 *
 *     gain *= clamp01((time − in.lower) / max(0.008, in.upper − in.lower))
 *     gain *= clamp01((out.upper − time) / max(0.008, out.upper − out.lower))
 */
class TransitionSoundTest {
    private val tiny = 1e-9

    private fun levels(settings: TransitionSettings, elapsed: Double, factor: Double = 1.0, edge: Int = 0) = TransitionSound.levels(settings, elapsed, factor, edge)

    private fun assertLevels(first: Double, second: Double, actual: TransitionSound.Levels) {
        assertEquals("first", first, actual.first, tiny)
        assertEquals("second", second, actual.second, tiny)
    }

    @Test fun aDissolveCrossfadesLinearlyOverItsLength() {
        // audioIn = audioOut = offset…offset + blend with blend = duration + hold, Transitions.swift:128, 148.
        val dissolve = TransitionSettings(duration = 1.0)
        assertLevels(1.0, 0.0, levels(dissolve, -0.5))
        assertLevels(1.0, 0.0, levels(dissolve, 0.0))
        assertLevels(0.75, 0.25, levels(dissolve, 0.25))
        assertLevels(0.5, 0.5, levels(dissolve, 0.5))
        assertLevels(0.0, 1.0, levels(dissolve, 1.0))
        assertLevels(0.0, 1.0, levels(dissolve, 4.0))
    }

    @Test fun theMovingStylesShareTheDissolvesCrossfade() {
        for (style in listOf(TransitionStyle.Wipe, TransitionStyle.Split, TransitionStyle.Hitchcock, TransitionStyle.Whip, TransitionStyle.FlashCut, TransitionStyle.Defocus)) {
            assertLevels(0.75, 0.25, levels(TransitionSettings(style = style, duration = 1.0), 0.25))
        }
    }

    @Test fun aHoldStretchesTheCrossfade() {
        // defaultAudioBlend = duration + holdDuration, Transitions.swift:67.
        val held = TransitionSettings(duration = 1.0, hold = 2.0)
        assertLevels(0.5, 0.5, levels(held, 1.5))
        assertLevels(0.25, 0.75, levels(held, 2.25))
        // A flash cut has no hold, Transitions.swift:13, 65.
        assertLevels(0.5, 0.5, levels(TransitionSettings(style = TransitionStyle.FlashCut, duration = 1.0, hold = 2.0), 0.5))
    }

    @Test fun aFadeGoesDownThenIsSilentThenComesUp() {
        // audioOut = 0…blend/2, audioIn = blend/2 + hold…blend + hold with blend = duration, Transitions.swift:145-147.
        val fade = TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0, hold = 2.0)
        assertLevels(1.0, 0.0, levels(fade, 0.0))
        assertLevels(0.5, 0.0, levels(fade, 0.25))
        assertLevels(0.0, 0.0, levels(fade, 0.5))
        assertLevels(0.0, 0.0, levels(fade, 1.5))
        assertLevels(0.0, 0.0, levels(fade, 2.5))
        assertLevels(0.0, 0.5, levels(fade, 2.75))
        assertLevels(0.0, 1.0, levels(fade, 3.0))
    }

    @Test fun aStillCutsTheSoundOnBothSidesOfTheFreeze() {
        // defaultAudioBlend is 0 for a still (Transitions.swift:67): out 0…0, in hold…hold, each an 8 ms ramp.
        val freeze = TransitionSettings(style = TransitionStyle.Still, duration = 2.0, stillDissolve = 0.5)
        assertLevels(1.0, 0.0, levels(freeze, -0.008))
        assertLevels(0.5, 0.0, levels(freeze, -0.004))
        assertLevels(0.0, 0.0, levels(freeze, 0.0))
        assertLevels(0.0, 0.0, levels(freeze, 1.0))
        assertLevels(0.0, 0.0, levels(freeze, 2.0))
        assertLevels(0.0, 0.5, levels(freeze, 2.004))
        assertLevels(0.0, 1.0, levels(freeze, 2.008))
    }

    @Test fun aRampIsNeverShorterThanEightMilliseconds() {
        // max(0.008, upper − lower), CutAudioEnvelope.swift:36-37. "Cut" is an audio blend of 0.
        val cut = TransitionSettings(duration = 1.0, audioBlend = 0.0)
        assertEquals(0.0..0.0, TransitionTiming.of(cut).audioIn)
        // The outgoing sound ends at the cut; the incoming one starts there.
        assertLevels(1.0, 0.0, levels(cut, -0.008))
        assertLevels(0.25, 0.0, levels(cut, -0.002))
        assertLevels(0.0, 0.0, levels(cut, 0.0))
        assertLevels(0.0, 0.75, levels(cut, 0.006))
        assertLevels(0.0, 1.0, levels(cut, 0.008))
        assertEquals(0.008, TransitionSound.MINIMUM_RAMP, 0.0)
        // A blend just over the minimum is used as it is.
        assertLevels(0.5, 0.5, levels(TransitionSettings(duration = 1.0, audioBlend = 0.016), 0.008))
    }

    @Test fun aSplitScreenCanKeepOneClipsSound() {
        // first: offset = max(0, videoDuration − 0.008), blend = min(0.008, videoDuration); second: blend alone, Transitions.swift:129-132.
        val first = TransitionSettings(style = TransitionStyle.Split, duration = 1.0, audio = TransitionAudio.First)
        assertEquals(0.992, TransitionTiming.of(first).audioOut.start, tiny); assertEquals(1.0, TransitionTiming.of(first).audioOut.endInclusive, tiny)
        assertLevels(1.0, 0.0, levels(first, 0.5))
        assertLevels(0.5, 0.5, levels(first, 0.996))
        assertLevels(0.0, 1.0, levels(first, 1.0))
        val second = first.copy(audio = TransitionAudio.Second)
        assertLevels(1.0, 0.0, levels(second, 0.0))
        assertLevels(0.5, 0.5, levels(second, 0.004))
        assertLevels(0.0, 1.0, levels(second, 0.5))
        // With a hold the first clip's sound lasts through it.
        assertLevels(1.0, 0.0, levels(first.copy(hold = 2.0), 2.9))
        // Custom sound overrides the choice.
        assertLevels(0.5, 0.5, levels(first.copy(audioBlend = 1.0), 0.5))
        // Other styles ignore it.
        assertLevels(0.5, 0.5, levels(first.copy(style = TransitionStyle.Dissolve), 0.5))
    }

    @Test fun aJCutBringsTheNextSoundInBeforeThePicture() {
        // Offset −1, blend 0.5 for every style: "Every style permits a continuous custom audio crossfade" and
        // "J-cut finishes its audio blend before the picture transition", TransitionTests.swift:327-334.
        for (style in TransitionStyle.entries) {
            val lead = TransitionSettings(style = style, duration = 1.0, audioOffset = -1.0, audioBlend = 0.5)
            val timing = TransitionTiming.of(lead)
            assertEquals(timing.audioIn, timing.audioOut)
            assertLevels(1.0, 0.0, levels(lead, -1.0))
            assertLevels(0.5, 0.5, levels(lead, -0.75))
            assertLevels(0.0, 1.0, levels(lead, -0.5))
            // The picture has not begun to change and the next clip is already all that is heard.
            assertLevels(0.0, 1.0, levels(lead, 0.0))
        }
    }

    @Test fun anLCutKeepsTheOutgoingSoundPastThePicture() {
        // Offset 1.5, blend 0.5: "L-cut retains outgoing sound after the picture change", TransitionTests.swift:327, 335.
        for (style in TransitionStyle.entries) {
            val trail = TransitionSettings(style = style, duration = 1.0, audioOffset = 1.5, audioBlend = 0.5)
            assertLevels(1.0, 0.0, levels(trail, 1.2))
            assertLevels(0.5, 0.5, levels(trail, 1.75))
            assertLevels(0.0, 1.0, levels(trail, 2.0))
        }
    }

    @Test fun fittingScalesTheSoundWithThePicture() {
        // offset and blend are both multiplied by the factor, Transitions.swift:127-128.
        val long = TransitionSettings(duration = 3.0)
        assertLevels(0.5, 0.5, levels(long, 1.0, factor = 2.0 / 3))
        val lead = TransitionSettings(duration = 2.0, audioOffset = -1.0, audioBlend = 1.0)
        assertLevels(0.5, 0.5, levels(lead, -0.25, factor = 0.5))
        // A fade's color hold is not shortened, so the silence stays as long, Transitions.swift:121.
        val fade = TransitionSettings(style = TransitionStyle.FadeColor, duration = 2.0, hold = 2.0)
        assertLevels(0.0, 0.5, levels(fade, 2.75, factor = 0.5))
    }

    @Test fun anOpeningOnlyBringsSoundUpAndAClosingOnlyTakesItDown() {
        val dissolve = TransitionSettings(duration = 1.0)
        assertLevels(0.0, 0.25, levels(dissolve, 0.25, edge = -1))
        assertLevels(0.0, 1.0, levels(dissolve, 1.0, edge = -1))
        assertLevels(0.75, 0.0, levels(dissolve, 0.25, edge = 1))
        assertLevels(0.0, 0.0, levels(dissolve, 1.0, edge = 1))
        // A fade at an edge blends over duration + hold, Transitions.swift:128.
        val fade = TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0, hold = 2.0)
        assertLevels(0.0, 0.5, levels(fade, 1.5, edge = -1))
        assertLevels(0.5, 0.0, levels(fade, 1.5, edge = 1))
        // An opening still is silent while black is held, then cuts in: begin = offset + hold, Transitions.swift:134.
        val freeze = TransitionSettings(style = TransitionStyle.Still, duration = 1.0)
        assertLevels(0.0, 0.0, levels(freeze, 1.0, edge = -1))
        assertLevels(0.0, 1.0, levels(freeze, 1.008, edge = -1))
    }

    @Test fun rampsAreOnTheTimelineFromTheOrigin() {
        // inRamp = origin + audioIn, outRamp = origin + audioOut, TrackRenderer.swift:275-281.
        val timing = TransitionTiming.of(TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0, hold = 2.0))
        assertEquals(3.5..4.0, TransitionSound.rampOut(timing, 3.5))
        assertEquals(6.0..6.5, TransitionSound.rampIn(timing, 3.5))
        // A clip between a dissolve in over 3…4 and a fade out over 6.5…7.
        assertEquals(0.5, TransitionSound.clipLevel(3.5, 3.0..4.0, 6.5..7.0), tiny)
        assertEquals(1.0, TransitionSound.clipLevel(5.0, 3.0..4.0, 6.5..7.0), tiny)
        assertEquals(0.5, TransitionSound.clipLevel(6.75, 3.0..4.0, 6.5..7.0), tiny)
        assertEquals(1.0, TransitionSound.clipLevel(5.0, null, null), 0.0)
        // Two ramps that meet multiply, CutAudioEnvelope.swift:36-37.
        assertEquals(0.25, TransitionSound.clipLevel(1.0, 0.0..2.0, 0.0..2.0), tiny)
    }

    // What the app plays today, for the two styles it has.

    private fun pair(style: ClipTransition.Style, duration: Double, audio: ClipAudio? = null): List<TimelineClip> {
        val second = TimelineClip(id = "B", sourceID = "S", start = 0.0, end = 4.0)
        return listOf(TimelineClip(id = "A", sourceID = "S", start = 0.0, end = 4.0, transition = ClipTransition(nextClipID = "B", style = style, duration = duration), audio = audio), second)
    }

    /** The new envelope for the same two clips, laid out as the original lays them out. */
    private fun envelope(settings: TransitionSettings, time: Double, clip: Int): Double {
        val join = TransitionLayout.sequence(listOf(4.0, 4.0), listOf(null, settings, null)).joins[1]
        return if (clip == 0) TransitionSound.clipLevel(time, null, join.audioOut) else TransitionSound.clipLevel(time, join.audioIn, null)
    }

    @Test fun aDefaultDissolveSoundsAsItDoesToday() {
        val clips = pair(ClipTransition.Style.Dissolve, 1.0)
        val settings = TransitionSettings(style = TransitionStyle.Dissolve, duration = 1.0)
        assertEquals(TimelineEditing.duration(clips), TransitionLayout.sequence(listOf(4.0, 4.0), listOf(null, settings, null)).duration, 0.0)
        var time = 0.0
        while (time <= 7.0) {
            if (time <= 4.0) assertEquals("first at $time", TimelineEditing.soundLevel(clips, 0, time), envelope(settings, time, 0), tiny)
            if (time >= 3.0) assertEquals("second at $time", TimelineEditing.soundLevel(clips, 1, time - 3.0), envelope(settings, time, 1), tiny)
            time += 1.0 / 64
        }
        // A quarter of the way through the overlap.
        assertEquals(0.75, TimelineEditing.soundLevel(clips, 0, 3.25), tiny)
        assertEquals(0.25, TimelineEditing.soundLevel(clips, 1, 0.25), tiny)
    }

    @Test fun aDefaultFadeThroughBlackSoundsAsItDoesToday() {
        val clips = pair(ClipTransition.Style.FadeBlack, 1.0)
        val settings = TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0)
        assertEquals(TimelineEditing.duration(clips), TransitionLayout.sequence(listOf(4.0, 4.0), listOf(null, settings, null)).duration, 0.0)
        var time = 0.0
        while (time <= 8.0) {
            if (time <= 4.0) assertEquals("first at $time", TimelineEditing.soundLevel(clips, 0, time), envelope(settings, time, 0), tiny)
            if (time >= 4.0) assertEquals("second at $time", TimelineEditing.soundLevel(clips, 1, time - 4.0), envelope(settings, time, 1), tiny)
            time += 1.0 / 64
        }
        assertEquals(0.5, TimelineEditing.soundLevel(clips, 0, 3.75), tiny)
    }

    @Test fun shortClipsAreWhereTodaysSoundAndTheOriginalsPartWays() {
        // Today a dissolve takes at most half of either clip; the original lets it take all the free footage.
        val first = TimelineClip(id = "A", sourceID = "S", start = 0.0, end = 1.0, transition = ClipTransition(nextClipID = "B", style = ClipTransition.Style.Dissolve, duration = 2.0))
        val clips = listOf(first, TimelineClip(id = "B", sourceID = "S", start = 0.0, end = 4.0))
        assertEquals(0.5, TimelineEditing.overlap(clips, 0), 0.0)
        val join = TransitionLayout.sequence(listOf(1.0, 4.0), listOf(null, TransitionSettings(duration = 2.0), null)).joins[1]
        assertEquals(1.0, join.timing.overlap, 1e-9)
    }
}
