package app.takes.core

import kotlin.math.abs

/**
 * The level of every clip's and every sound's samples over the movie, built
 * the way the original builds it for its tracks (TrackRenderer.swift:265-296):
 * one [AudioEnvelopeSegment] for each uninterrupted run of sound, carrying
 * the few milliseconds of fade at a cut, the ramps of the transitions at the
 * clip's two ends, the fades the person put on it and its volume.
 * [AudioEnvelopeSegment.gain] combines them as the original does: the cut
 * fades and the person's fades each as the lower of their two ends, then
 * multiplied with the transitions' ramps and the volume.
 *
 * The export applies a segment to every sample ([AudioEnvelope]); the editor
 * reads the same segment for the level it gives its players, without the
 * fades at cuts ([TimelineEditing.soundLevel]).
 */
object SoundEnvelope {
    /** Places closer than this are the same: one tick of the original's clock (TrackRenderer.swift:283). */
    private const val SAME_PLACE = 1.0 / EditTime.SCALE

    /** Source seconds closer than this are the same (TrackRenderer.swift:284). */
    private const val SAME_SOURCE = 0.00001

    private fun ramps(clips: List<TimelineClip>, index: Int) =
        TimelineEditing.entering(clips, index)?.audioIn to TimelineEditing.leaving(clips, index)?.audioOut

    /**
     * Whether the sound of the clip at [index] carries on into the next one,
     * so that the cut between them is not heard: both sound, they play the
     * same recording at the same speed, the second picks it up where the
     * first left it, and the same transitions shape both, which between two
     * pieces of a split clip means none at either's other end
     * (TrackRenderer.swift:281-288). Such a cut has no fade; the level alone
     * slides from one piece's to the other's.
     */
    fun continues(clips: List<TimelineClip>, index: Int): Boolean {
        val clip = clips.getOrNull(index) ?: return false
        val next = clips.getOrNull(index + 1) ?: return false
        if (clip.audio?.muted == true || next.audio?.muted == true) return false
        if (clip.sourceID != next.sourceID || abs(clip.end - next.start) >= SAME_SOURCE || abs(clip.rate - next.rate) >= SAME_SOURCE) return false
        // Cleaned sound is another file than the recorded one.
        if ((clip.audio?.denoise ?: 0.0) != (next.audio?.denoise ?: 0.0)) return false
        if (ramps(clips, index) != ramps(clips, index + 1)) return false
        return abs(TimelineEditing.clipStart(clips, index) + clip.duration - TimelineEditing.clipStart(clips, index + 1)) < SAME_PLACE
    }

    /** The same for two sounds on one audio track. */
    fun continues(item: AudioItem, next: AudioItem): Boolean =
        item.track == next.track && item.sourceID == next.sourceID && item.audio?.muted != true && next.audio?.muted != true &&
            abs(item.end - next.start) < SAME_PLACE && abs(item.sourceEnd - next.sourceStart) < SAME_SOURCE

    /**
     * The level of the clip at [index] while it plays, on the movie's clock.
     * Null for a muted clip, which the original leaves out of the mix.
     * Without [cutFades] a cut is as abrupt as the footage: what a player's
     * volume can follow.
     */
    fun clip(clips: List<TimelineClip>, index: Int, cutFades: Boolean = true): AudioEnvelopeSegment? {
        val clip = clips.getOrNull(index) ?: return null
        val audio = clip.audio ?: ClipAudio()
        if (audio.muted) return null
        val start = TimelineEditing.clipStart(clips, index)
        val previous = clips.getOrNull(index - 1)?.takeIf { continues(clips, index - 1) }
        val (entering, leaving) = ramps(clips, index)
        val fades = audio.fades
        return AudioEnvelopeSegment(
            start = start, end = start + clip.duration,
            fadeIn = if (previous != null || !cutFades) 0.0 else AudioLevels.CUT_FADE,
            fadeOut = if (!cutFades || continues(clips, index)) 0.0 else AudioLevels.CUT_FADE,
            inRamp = entering, outRamp = leaving, userFadeIn = fades.fadeIn, userFadeOut = fades.fadeOut,
            owner = clip.id, initialOwner = previous?.id, initialVolume = previous?.let { (it.audio ?: ClipAudio()).gain }, volume = audio.gain,
        )
    }

    /**
     * The level of a sound on an audio track, among [items]. [movieEnd] is
     * where the movie ends: this port leaves out sound past its last picture,
     * and what is cut off there ends on the fade of a cut too.
     */
    fun item(items: List<AudioItem>, item: AudioItem, movieEnd: Double = Double.POSITIVE_INFINITY, cutFades: Boolean = true): AudioEnvelopeSegment? {
        val audio = item.audio ?: ClipAudio()
        if (audio.muted) return null
        val previous = items.firstOrNull { it.id != item.id && continues(it, item) }
        val fades = audio.fades
        return AudioEnvelopeSegment(
            start = item.start, end = item.end,
            fadeIn = if (previous != null || !cutFades) 0.0 else AudioLevels.CUT_FADE,
            fadeOut = if (!cutFades || items.any { it.id != item.id && continues(item, it) }) 0.0 else AudioLevels.CUT_FADE,
            audibleEnd = if (cutFades) movieEnd else Double.POSITIVE_INFINITY,
            userFadeIn = fades.fadeIn, userFadeOut = fades.fadeOut,
            owner = item.id, initialOwner = previous?.id, initialVolume = previous?.let { (it.audio ?: ClipAudio()).gain }, volume = audio.gain,
        )
    }

    /** Every run of sound in [state], clips first: what [AudioGainMix] and [AudioHeadroom] work on. */
    fun segments(state: EditState, cutFades: Boolean = true): List<AudioEnvelopeSegment> {
        val items = state.audio.orEmpty()
        return state.clips.indices.mapNotNull { clip(state.clips, it, cutFades) } + items.mapNotNull { item(items, it, state.duration, cutFades) }
    }
}
