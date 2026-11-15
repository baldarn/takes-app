package app.takes.core

import kotlin.math.abs

/**
 * Unlinks a clip's sound from its picture, so the sound can be cut and moved
 * on its own (J and L cuts), and links it back.
 *
 * A clip owns its sound, so "unlinked" is expressed with what a project can
 * already hold:
 *
 *  - the clip is muted, and keeps its other sound settings as they were;
 *  - an [AudioItem] with **the clip's id** plays the clip's source range from
 *    the clip's place on the timeline, with the clip's level, mute and fades.
 *
 * The shared id is the link: a clip's sound is detached exactly when the clip
 * is muted and an audio item has its id. Nothing new is saved, so a version of
 * the app that knows nothing about unlinking plays the project correctly: it
 * sees a muted clip and a sound item.
 *
 * As in the original app, an unlinked component does not follow the other.
 * The item sits at an absolute timeline second: trimming, deleting, moving or
 * changing the speed of any clip, this one included, moves the picture and
 * leaves the item where it is. [syncOffset] says how far the two have drifted
 * and [restoreSync] puts the sound back under its picture.
 *
 * Titles and bookmarks belong to the picture and stay on the clip.
 */
object AudioUnlinking {
    /** Times closer than this are the same place: well under one audio sample. */
    const val TOLERANCE = 1e-6

    /** Why a clip's sound cannot be unlinked. */
    enum class Refusal {
        NoClip,
        /** Its sound is already on a track of its own, or an item already uses the clip's id. */
        AlreadyDetached,
        /** The clip plays faster or slower than recorded; sound on its own track has no speed. */
        Speed,
        /** Noise reduction is on and sound on its own track would not be cleaned. */
        NoiseReduction,
        /** Both sound tracks are busy somewhere under the clip. */
        TracksBusy,
    }

    private fun ClipAudio.normalized(): ClipAudio? = takeIf { it != ClipAudio() }

    private fun EditState.withAudio(items: List<AudioItem>) = copy(audio = items.sortedBy { it.start }.ifEmpty { null })

    private fun EditState.withClip(index: Int, clip: TimelineClip) = copy(clips = clips.toMutableList().also { it[index] = clip })

    private fun same(a: Double, b: Double) = abs(a - b) <= TOLERANCE

    /**
     * Seconds over which the transitions at the clip's two ends bring its
     * sound in and out: the length of the ramp of the join before it and of
     * the join after it ([TransitionSound]).
     */
    private fun ramps(clips: List<TimelineClip>, index: Int): Pair<Double, Double> =
        (TimelineEditing.join(clips, index)?.timing?.audioIn?.let { it.endInclusive - it.start } ?: 0.0) to
            (TimelineEditing.join(clips, index + 1)?.timing?.audioOut?.let { it.endInclusive - it.start } ?: 0.0)

    /** One fade standing for the clip's own fade and its transition's ramp at the same end. */
    private fun baked(fade: Double, ramp: Double) = maxOf(fade, ramp)

    // MARK: Lookup

    /** The audio item named after the clip at [index], whether or not the clip is muted. */
    private fun namesake(state: EditState, index: Int): AudioItem? =
        state.clips.getOrNull(index)?.let { clip -> state.audio.orEmpty().firstOrNull { it.id == clip.id } }

    /** The item playing the sound of the clip at [index], or null while the clip plays its own sound. */
    fun detachedSound(state: EditState, index: Int): AudioItem? =
        namesake(state, index)?.takeIf { state.clips[index].audio?.muted == true }

    fun isDetached(state: EditState, index: Int): Boolean = detachedSound(state, index) != null

    /** The clip whose detached sound the item [id] is, for selecting the picture from its waveform. */
    fun clipIndex(state: EditState, id: String): Int? =
        state.clips.indexOfFirst { it.id == id }.takeIf { it >= 0 && isDetached(state, it) }

    // MARK: Unlink

    /**
     * Null when the clip at [index] can be unlinked. [itemsReduceNoise] says
     * whether the player and exporter clean the sound of audio items the way
     * they clean a clip's; while they do not, a clip with noise reduction is
     * refused rather than silently losing it.
     */
    fun refusal(state: EditState, index: Int, itemsReduceNoise: Boolean = false): Refusal? {
        val clip = state.clips.getOrNull(index) ?: return Refusal.NoClip
        if (namesake(state, index) != null) return Refusal.AlreadyDetached
        if (clip.rate != 1.0) return Refusal.Speed
        if ((clip.audio?.denoise ?: 0.0) > 0 && !itemsReduceNoise) return Refusal.NoiseReduction
        if (item(state, index) == null) return Refusal.TracksBusy
        return null
    }

    /** The item that would carry the clip's sound, on the first free track; null when both are busy under the clip. */
    private fun item(state: EditState, index: Int): AudioItem? {
        val clip = state.clips[index]
        val sound = clip.audio ?: ClipAudio()
        val (rampIn, rampOut) = ramps(state.clips, index)
        val item = AudioItem(
            id = clip.id, sourceID = clip.sourceID, start = TimelineEditing.clipStart(state.clips, index),
            sourceStart = clip.start, sourceEnd = clip.end,
            // The sound no longer follows the picture's transitions, so their ramps become its own fades.
            audio = sound.copy(fadeIn = baked(sound.fadeIn, rampIn), fadeOut = baked(sound.fadeOut, rampOut)).normalized(),
        )
        val items = state.audio.orEmpty()
        return (0 until AudioItem.TRACKS).map { item.copy(track = it) }
            .firstOrNull { placed -> items.none { it.track == placed.track && it.start < placed.end && placed.start < it.end } }
    }

    /**
     * Moves the sound of the clip at [index] to an audio item under it. What
     * is heard does not change: same samples at the same moments, same level,
     * mute and fades. A transition at either end stops shaping the sound, so
     * its ramp is written into the item as a fade of the same length; only
     * where the clip also has a fade of its own at that end is the result
     * approximate (the longer of the two is used, where the clip multiplied
     * them). Null when [refusal] gives a reason.
     */
    fun unlink(state: EditState, index: Int, itemsReduceNoise: Boolean = false): EditState? {
        if (refusal(state, index, itemsReduceNoise) != null) return null
        val clip = state.clips[index]
        val item = item(state, index) ?: return null
        // The clip remembers its level and fades for [relink]; noise reduction moves with the sound.
        val silent = (clip.audio ?: ClipAudio()).copy(muted = true, denoise = 0.0)
        return state.withClip(index, clip.copy(audio = silent)).withAudio(state.audio.orEmpty() + item)
    }

    // MARK: Relink

    /**
     * True when the detached sound of the clip at [index] still lies exactly
     * under its picture: same source, same range, same place, recorded speed.
     */
    fun matches(state: EditState, index: Int): Boolean {
        val item = detachedSound(state, index) ?: return false
        val clip = state.clips[index]
        return clip.rate == 1.0 && item.sourceID == clip.sourceID && same(item.sourceStart, clip.start) &&
            same(item.sourceEnd, clip.end) && same(item.start, TimelineEditing.clipStart(state.clips, index))
    }

    /**
     * Gives the clip at [index] its sound back and removes the item, when the
     * two still [matches]; null otherwise. Linking never moves or trims
     * anything, so a displaced or cut sound has to be put back first.
     *
     * The clip takes the item's level, mute and noise reduction. A fade the
     * item still has from a transition at unlink returns to being the
     * transition's, and the clip gets back the fade it had before; any other
     * fade is the user's and becomes the clip's own, on top of the transition.
     */
    fun relink(state: EditState, index: Int): EditState? {
        if (!matches(state, index)) return null
        val item = detachedSound(state, index) ?: return null
        val clip = state.clips[index]
        val kept = clip.audio ?: ClipAudio()
        val sound = item.audio ?: ClipAudio()
        val (rampIn, rampOut) = ramps(state.clips, index)
        val audio = sound.copy(
            fadeIn = if (sound.fadeIn == baked(kept.fadeIn, rampIn)) kept.fadeIn else sound.fadeIn,
            fadeOut = if (sound.fadeOut == baked(kept.fadeOut, rampOut)) kept.fadeOut else sound.fadeOut,
        )
        return state.withClip(index, clip.copy(audio = audio.normalized())).withAudio(state.audio.orEmpty().filter { it.id != item.id })
    }

    // MARK: Sync

    /**
     * Seconds the detached sound of the clip at [index] plays after its
     * picture; negative when it plays early, 0 in sync. Null when the sound is
     * not detached, or the clip's speed was changed and no place is in sync.
     * A trimmed item is in sync when what is left of it plays at its moment.
     */
    fun syncOffset(state: EditState, index: Int): Double? {
        val item = detachedSound(state, index) ?: return null
        val clip = state.clips[index]
        if (clip.rate != 1.0 || item.sourceID != clip.sourceID) return null
        // Where source second 0 falls on the timeline, for the sound and for the picture.
        val offset = (item.start - item.sourceStart) - (TimelineEditing.clipStart(state.clips, index) - clip.start)
        return if (abs(offset) <= TOLERANCE) 0.0 else offset
    }

    /**
     * Moves the detached sound of the clip at [index] back under its picture
     * without changing its cut, as after an earlier clip was trimmed. Null
     * when it is in sync already, would begin before the timeline does, or
     * both tracks are busy there.
     */
    fun restoreSync(state: EditState, index: Int): EditState? {
        val item = detachedSound(state, index) ?: return null
        val offset = syncOffset(state, index)?.takeIf { it != 0.0 } ?: return null
        return TimelineEditing.moveAudio(state, item.id, item.start - offset)
    }

    // MARK: Settings

    /**
     * Sets how the clip at [index] sounds, wherever its sound is: on the item
     * when it is detached, on the clip otherwise. Unmuting a clip with
     * [TimelineEditing.setAudio] while its sound is detached would play the
     * sound twice.
     */
    fun setSound(state: EditState, index: Int, audio: ClipAudio, itemsReduceNoise: Boolean = false): EditState? {
        val item = detachedSound(state, index) ?: return TimelineEditing.setAudio(state, index, audio)
        if (audio.denoise > 0 && !itemsReduceNoise) return null
        return TimelineEditing.setAudioItemSound(state, item.id, audio)
    }

    // MARK: Validation

    /**
     * What a saved state must satisfy for the pairing to mean anything: an
     * audio item that has a clip's id plays that clip's source, and the clip
     * is muted, so its sound is never heard twice.
     */
    fun valid(state: EditState): Boolean {
        val items = state.audio.orEmpty().associateBy { it.id }
        return state.clips.all { clip ->
            val item = items[clip.id] ?: return@all true
            item.sourceID == clip.sourceID && clip.audio?.muted == true
        }
    }
}
