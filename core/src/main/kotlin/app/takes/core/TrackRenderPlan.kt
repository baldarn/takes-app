package app.takes.core

import kotlin.math.abs

/**
 * The render plan of an edit, made from its track timeline
 * (docs/TRACKS-PLAN.md, S3a): the second builder of [RenderPlan], beside
 * [RenderPlan.of], which reads the saved sequence of clips.
 *
 * A [RenderPlan] is a plan of a sequence: its clips are numbered, its times
 * are the seconds of [TimelineEditing.layout], and what it answers about a
 * moment ([RenderPlan.moment], [RenderPlan.clipLevel], [RenderPlan.titlesAt])
 * it works out from its clips. So a timeline is planned by reading it as the
 * sequence it is and planning that. The original's renderer does the same
 * for a timeline that "is exactly one ordinary linked sequence" with no
 * transition, caption or level in it: it hands the projected clips to the
 * renderer it had before (YouRec/TrackRenderer.swift:44-68). Every other
 * timeline it composes from the ticks; here that waits for S3b, see below.
 * What is read from the timeline:
 *
 *  - the clips, in the order the timeline keeps its pictures, each with the
 *    recipe of its picture (footage, speed, color, crop, titles), the
 *    transitions that are relations of the timeline with the fit each has
 *    saved, and the level, mute, fades, noise reduction and words of the
 *    sound that follows it ([TrackWrapping.seen]);
 *  - the sounds that follow no picture, as sound items;
 *  - the look of the captions.
 *
 * What is not read is where the timeline puts its items, in ticks. The
 * layout of the clips read gives every second of the plan, and it is the
 * layout the timeline was converted from, bit for bit: a transition plays at
 * the factor its relation has. The ticks are that layout rounded
 * ([TrackConversion]), so a plan made of them would differ from today's in
 * its last digits, and S3a changes nothing that is seen or heard. They become
 * what is planned when they are what is saved (S3b).
 *
 * A timeline that is not a sequence (pictures with time between them or over
 * one another, a placement, a third sound at once) has no plan yet: a
 * [RenderPlan] has two decks and no place for it. None arises before S4.
 */
object TrackRenderPlan {
    /**
     * The plan of [timeline]: that of the sequence it is
     * ([TrackSequenceForm.sequence]), or null when it is not one. [cleaned]
     * as in [RenderPlan.of], counting the pictures in the order they play.
     *
     * A timeline keeps less than a saved sequence does, so the plan of the
     * timeline an edit converts to is not in every field the plan of the
     * edit: a sound that follows no picture begins on a tick, up to half of
     * one from its second, the two sound tracks are laid anew, and the
     * captions remember the timeline they were timed for. Nothing of that is
     * seen or heard. [of] an edit keeps those as they are saved.
     */
    fun of(timeline: TrackTimeline, cleaned: Set<Int>? = null): RenderPlan? = timeline.toSequence()?.let { RenderPlan.of(it, cleaned) }

    /**
     * The plan of [state], made from the timeline it converts to: equal to
     * [RenderPlan.of] of the same edit, field for field. This is the call the
     * preview and the export make while a project still saves a sequence.
     *
     * Three things a timeline does not hold are taken from [state] as they
     * are saved, each only where the timeline says the same to within what
     * it can hold, and each until the stage that adopts the timeline's own:
     *
     *  - the second a sound item begins at, which the timeline rounds to a
     *    tick, its track and its place in the list (S3b for the second, S5
     *    for the tracks: `sound-tracks-are-laid-anew`);
     *  - what the captions were timed for (S8: `captions-fingerprint`);
     *  - of a clip, what the conversion does not carry and no renderer shows:
     *    settings of a transition that does not play, a fit that was never
     *    saved (the timeline has the one the layout finds), sound settings
     *    that say nothing, and what a muted clip remembers of its sound
     *    while that sound is unlinked. The plan is worked out from the clips
     *    the timeline reads as, and [RenderPlan.clips] are the saved ones.
     */
    fun of(state: EditState, cleaned: Set<Int>? = null): RenderPlan {
        val timeline = TrackWrapping.timeline(state)
        val seen = checkNotNull(TrackWrapping.seen(timeline)) { "A converted edit is a sequence" }
        val clips = clips(timeline, seen.clips, state.clips)
        val audio = items(seen.items, state.audio)
        val subtitles = if (seen.subtitles?.copy(timingFingerprint = "") == state.subtitles?.copy(timingFingerprint = "")) state.subtitles else seen.subtitles
        // The timeline reads as the edit that is saved: the usual case, and then the two builders are given the same.
        if (clips === state.clips && audio === state.audio && subtitles === state.subtitles) return RenderPlan.of(state, cleaned)
        val plan = RenderPlan.of(state.copy(clips = clips, audio = audio, subtitles = subtitles), cleaned)
        return if (clips.size == state.clips.size) plan.copy(clips = state.clips) else plan
    }

    /**
     * Whether two sound settings are heard the same: the level, the mute, the
     * fades as the timeline keeps them ([ClipAudio.fades]) and, with
     * [denoise], the noise reduction. Settings that say nothing are saved as
     * none or written out, and a fade that was never cut with or without the
     * nothing that lies behind its edge.
     */
    private fun same(one: ClipAudio?, other: ClipAudio?, denoise: Boolean): Boolean {
        val a = one ?: ClipAudio()
        val b = other ?: ClipAudio()
        return a.volumeDB == b.volumeDB && a.muted == b.muted && a.fades == b.fades && (!denoise || a.denoise == b.denoise)
    }

    /**
     * The clips [read] from [timeline], with the sound settings of [saved]
     * where the timeline has nothing else to say: settings that say the same
     * in another way ([same]), and those of a clip whose sound follows it no
     * longer, which the clip only remembers. [saved] itself when every clip
     * then is the one saved.
     */
    private fun clips(timeline: TrackTimeline, read: List<TimelineClip>, saved: List<TimelineClip>): List<TimelineClip> {
        if (read.size != saved.size) return read
        val followed = timeline.audio.mapNotNullTo(HashSet()) { it.followsVideo }
        var same = true
        val result = read.mapIndexed { index, clip ->
            val kept = saved[index]
            if (clip == kept) return@mapIndexed kept
            val unsaid = if (clip.id in followed) same(clip.audio, kept.audio, denoise = true) else kept.audio?.muted == true
            val merged = if (unsaid) clip.copy(audio = kept.audio) else clip
            if (merged == kept) kept else merged.also { same = false }
        }
        return if (same) saved else result
    }

    /**
     * The sound items [read] from the timeline: [saved] itself when each is
     * one of them to within a tick, with its settings but for a noise
     * reduction, which does nothing on a sound item and which the timeline
     * leaves out. Otherwise the timeline's own, each on the first track free.
     */
    private fun items(read: List<AudioItem>, saved: List<AudioItem>?): List<AudioItem>? {
        val kept = saved.orEmpty()
        val found = HashMap<String, AudioItem>()
        for (item in read) found.putIfAbsent(item.id, item)
        fun one(item: AudioItem, other: AudioItem) = item.sourceID == other.sourceID && item.sourceStart == other.sourceStart && item.sourceEnd == other.sourceEnd &&
            abs(item.start - other.start) <= TransitionLayout.TICK && same(item.audio, other.audio, denoise = false)
        if (read.size == kept.size && found.size == read.size && kept.all { item -> found[item.id]?.let { one(it, item) } == true }) return saved
        val placed = ArrayList<AudioItem>()
        for (item in read.sortedBy { it.start }) placed += TrackWrapping.place(placed, item) ?: item
        return placed.ifEmpty { null }
    }
}
