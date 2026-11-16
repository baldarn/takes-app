package app.takes.core

import kotlin.math.abs

/**
 * How a function that edits a sequence of clips ([TimelineEditing],
 * [RangeEditing], [SubtitleEditing]) runs on the track model while the edit
 * a project saves is still a sequence (docs/TRACKS-PLAN.md, S2-B): the edit
 * is converted to a timeline ([timeline]), an operation of the track model
 * is run on it, and what the operation changed is written back into the
 * sequence ([edit]).
 *
 * The way back is not [TrackSequenceView.sequence], which builds a sequence
 * anew and so rewrites what no operation touched (a track of a sound item,
 * a transition that does not play, a second that is not on a tick). Here the
 * timeline before and the timeline after are both read as a sequence, and
 * only what differs between the two readings is written into the edit the
 * function was given. What the operation left alone keeps the bytes it has.
 *
 * Until the timeline is what a project saves (S3b), three things of the
 * sequence form hold whatever an operation answers, and are said here once:
 *
 *  - A picture has no place of its own: the clips follow one another in the
 *    order the timeline keeps its pictures, and where each plays is the layout's
 *    ([TimelineEditing.layout]). So time an operation leaves empty closes,
 *    and two pictures it leaves overlapping play one after the other. The
 *    kinds `removed-transition-leaves-its-time`,
 *    `close-that-holds-pushes-nothing` and
 *    `unlinked-picture-does-not-ripple` of docs/TRACKS-PARITY-DECISIONS.md
 *    are kept by this alone, until S4 and S6.
 *  - A sound of its own is on one of two tracks ([place]): `third-sound` and
 *    `sound-tracks-are-laid-anew`, until S5.
 *  - What the captions were timed for is the fingerprint the edit has saved,
 *    by today's formula: `captions-fingerprint`, until S8.
 */
internal object TrackWrapping {
    /** A timeline read as a sequence: its pictures as clips, its sounds that follow no picture as sound items, the look of its captions. */
    class Seen(val clips: List<TimelineClip>, val items: List<AudioItem>, val subtitles: SubtitleSettings?)

    private class Converted(val state: EditState, val timeline: TrackTimeline) {
        val seen: Seen by lazy { checkNotNull(seen(timeline)) { "A converted edit is a sequence" } }
    }

    // The conversion of the edit asked about last: a wrapper that makes several steps, and a caller that tries an edit before making it, ask for the same one.
    @Volatile private var converted: Converted? = null

    private fun converted(state: EditState): Converted {
        converted?.let { if (it.state === state) return it }
        return Converted(state, state.toTimeline()).also { converted = it }
    }

    /** [state] as a timeline ([TrackConversion.timeline]). */
    fun timeline(state: EditState): TrackTimeline = converted(state).timeline

    /** [state] with the fit of every transition in play saved, as every edit that moves footage starts ([TimelineEditing.fitted]); the same instance when none was missing. */
    fun fitted(state: EditState): EditState = if (!state.isSequence) state else TimelineEditing.fitted(state.clips).let { if (it === state.clips) state else state.copy(clips = it) }

    private fun ClipAudio.normalized(): ClipAudio? = takeIf { it != ClipAudio() }

    /**
     * The noise reduction of a clip's sound: the amount of its first chain.
     * Kept until S7 (`noise-reduction-stays-on-its-footage`): a clip has one
     * amount for all of it, so the amount stays the clip's when a trim
     * reveals footage the chain does not reach.
     */
    private fun denoise(sound: AudioTimelineItem): Double =
        sound.enhancements.firstOrNull()?.effects?.firstOrNull { it.kind == AudioEffectKind.VoiceIsolation }?.amount ?: 0.0

    private fun clipAudio(sound: AudioTimelineItem, denoise: Double): ClipAudio =
        TrackConversion.withFades(ClipAudio(sound.volumeDB, sound.muted, denoise = denoise), sound.fadeIn, sound.fadeOut)

    /**
     * [timeline] read as a sequence, whatever places its pictures have: the
     * clips in the order the timeline keeps its pictures, which is the order
     * of the clips after a conversion and which every operation keeps or
     * says, each with its transitions, the settings of the
     * sound that follows it, its bookmarks and its words; and every sound
     * that follows no picture as a sound item. Null when such a sound plays
     * in several runs, which no sound item does.
     */
    fun seen(timeline: TrackTimeline): Seen? {
        val pictures = timeline.video
        val sounds = TrackSequenceView.sounds(timeline)
        val openings = HashMap<String, TimelineTransition>()
        val leavings = HashMap<String, TimelineTransition>()
        for (relation in timeline.transitions) {
            if (relation.outgoing == null) relation.incoming?.let { openings.putIfAbsent(it, relation) }
            relation.outgoing?.let { leavings.putIfAbsent(it, relation) }
        }
        // A bookmark is on the piece of its occurrence that plays its second, or the piece before it.
        val marks = HashMap<String, MutableList<Bookmark>>()
        if (timeline.bookmarks.isNotEmpty()) {
            val pieces = HashMap<String, MutableList<VideoTimelineItem>>()
            for (picture in timeline.video) pieces.getOrPut(picture.occurrenceID) { mutableListOf() } += picture
            for (bookmark in timeline.bookmarks) {
                val anchor = bookmark.anchor as? BookmarkAnchor.Source ?: continue
                val same = pieces[anchor.occurrence].orEmpty().filter { it.content.sourceID == anchor.source }.sortedBy { it.content.start }
                val owner = same.lastOrNull { it.content.start <= anchor.time } ?: same.firstOrNull() ?: continue
                marks.getOrPut(owner.id) { mutableListOf() } += Bookmark(bookmark.id, anchor.time, bookmark.label.ifEmpty { null })
            }
        }
        val own = HashMap<String, String>()
        val clips = pictures.map { picture ->
            val sound = sounds[picture.id]
            val audio = if (sound?.followsVideo == picture.id) clipAudio(sound, denoise(sound)) else ClipAudio(muted = true)
            if (sound != null) own[sound.id] = picture.id
            val opening = openings[picture.id]
            val leaving = leavings[picture.id]
            picture.content.copy(
                openingTransition = opening?.settings, openingTransitionFit = opening?.let { TransitionFit(it.factor) },
                transition = leaving?.settings?.copy(nextClipID = leaving.incoming), transitionFit = leaving?.let { TransitionFit(it.factor) },
                audio = audio.normalized(), bookmarks = marks[picture.id], subtitleWords = sound?.subtitleWords,
            )
        }
        val aliases = HashMap<String, String>()
        for (picture in pictures) aliases[TrackTimeline.alias(picture.id)] = picture.id
        val items = ArrayList<AudioItem>()
        for (sound in timeline.audio) {
            if (sound.followsVideo != null) continue
            val run = sound.mapping.runs.singleOrNull() ?: return null
            items += AudioItem(own[sound.id] ?: aliases[sound.id] ?: sound.id, sound.sourceID, sound.start.seconds, run.sourceStart, run.sourceEnd, audio = clipAudio(sound, 0.0).normalized())
        }
        return Seen(clips, items, timeline.subtitles)
    }

    /** The first track where [item] overlaps none of [items], its own first; null when both are busy there. Kept until S5 (`third-sound`, `sound-tracks-are-laid-anew`). */
    fun place(items: List<AudioItem>, item: AudioItem): AudioItem? =
        (listOf(item.track) + (0 until AudioItem.TRACKS)).distinct().filter { it in 0 until AudioItem.TRACKS }.map { item.copy(track = it) }
            .firstOrNull { candidate -> items.none { it.id != candidate.id && it.track == candidate.track && it.start < candidate.end && candidate.start < it.end } }

    /** [wanted] when [value] is the same place to within a tick: a second an edit was given is saved as given, not as the tick nearest to it. Kept until S3b (`split-on-the-grid`). */
    fun exactly(value: Double, wanted: Double, slack: Double = TransitionLayout.TICK): Double = if (abs(value - wanted) <= slack) wanted else value

    private fun <T> pick(saved: T, old: T, now: T): T = if (now == old) saved else now

    private fun marks(saved: List<Bookmark>?, old: List<Bookmark>?, now: List<Bookmark>?): List<Bookmark>? {
        if (now.orEmpty().toSet() == old.orEmpty().toSet()) return saved
        val wanted = now.orEmpty().associateBy { it.id }
        // One that goes leaves the others in the order they are in; one that comes puts them in the order of their footage.
        val kept = saved.orEmpty().mapNotNull { wanted[it.id] }
        return (if (kept.size == wanted.size) kept else now.orEmpty().sortedBy { it.time }).ifEmpty { null }
    }

    private fun merged(saved: TimelineClip, old: TimelineClip, now: TimelineClip): TimelineClip = saved.copy(
        sourceID = now.sourceID,
        start = pick(saved.start, old.start, now.start), end = pick(saved.end, old.end, now.end),
        transition = pick(saved.transition, old.transition, now.transition), transitionFit = pick(saved.transitionFit, old.transitionFit, now.transitionFit),
        openingTransition = pick(saved.openingTransition, old.openingTransition, now.openingTransition),
        openingTransitionFit = pick(saved.openingTransitionFit, old.openingTransitionFit, now.openingTransitionFit),
        texts = pick(saved.texts, old.texts, now.texts), bookmarks = marks(saved.bookmarks, old.bookmarks, now.bookmarks),
        audio = pick(saved.audio, old.audio, now.audio), color = pick(saved.color, old.color, now.color), crop = pick(saved.crop, old.crop, now.crop),
        speed = pick(saved.speed, old.speed, now.speed), subtitleWords = pick(saved.subtitleWords, old.subtitleWords, now.subtitleWords),
        videoEffects = pick(saved.videoEffects, old.videoEffects, now.videoEffects),
    )

    /**
     * [state], whose timeline is [before], with what differs in [after]
     * written into it. A clip, a sound item and the look of the captions that
     * read the same before and after are the ones [state] has, untouched.
     *
     * [sound] false leaves the sound items as they are, whatever the
     * operation did to them: kept until S5 (`split-cuts-sound-items`,
     * `scope-cuts-sound-items`, `insert-moves-sound-items`), an edit of the
     * picture neither cuts nor moves a sound of its own.
     * A sound item that is new or has moved is given to [settled], which
     * says what the caller knows better than the timeline (the second it was
     * put at, not the tick), and then put on a track ([place]).
     *
     * Null when [after] holds a sound no sound item can be, or a sound item
     * that fits on neither track.
     */
    fun back(
        state: EditState, before: Seen, after: TrackTimeline, sound: Boolean = true,
        settled: (AudioItem) -> AudioItem = { it },
    ): EditState? {
        val now = seen(after) ?: return null
        val saved = HashMap<String, TimelineClip>()
        for (clip in state.clips) saved.putIfAbsent(clip.id, clip)
        val old = HashMap<String, TimelineClip>()
        for (clip in before.clips) old.putIfAbsent(clip.id, clip)
        val clips = now.clips.map { clip ->
            val mine = saved[clip.id]
            val was = old[clip.id]
            if (mine == null || was == null) clip else if (clip == was) mine else merged(mine, was, clip)
        }
        val sameClips = clips.size == state.clips.size && clips.indices.all { clips[it] === state.clips[it] }

        var audio = state.audio
        if (sound && now.items != before.items) {
            val mine = state.audio.orEmpty().associateBy { it.id }
            val was = before.items.associateBy { it.id }
            val wanted = now.items.associateBy { it.id }
            val moved = LinkedHashSet<String>()
            val result = ArrayList<AudioItem>()
            fun written(item: AudioItem): AudioItem {
                val kept = mine[item.id]
                val read = was[item.id]
                if (kept == null || read == null) { moved += item.id; return item }
                if (item == read) return kept
                if (item.start != read.start || item.sourceStart != read.sourceStart || item.sourceEnd != read.sourceEnd) moved += item.id
                return kept.copy(
                    sourceID = item.sourceID, start = pick(kept.start, read.start, item.start), sourceStart = pick(kept.sourceStart, read.sourceStart, item.sourceStart),
                    sourceEnd = pick(kept.sourceEnd, read.sourceEnd, item.sourceEnd), audio = pick(kept.audio, read.audio, item.audio),
                )
            }
            for (item in state.audio.orEmpty()) wanted[item.id]?.let { result += written(it) }
            for (item in now.items) if (item.id !in mine) result += written(item)
            for (index in result.indices) if (result[index].id in moved) result[index] = place(result.filterIndexed { other, _ -> other != index }, settled(result[index])) ?: return null
            audio = result.sortedBy { it.start }.ifEmpty { null }
        }

        // Kept until S8 (`captions-fingerprint`): what the captions were timed for is what the edit has saved.
        val subtitles = if (now.subtitles?.copy(timingFingerprint = "") == before.subtitles?.copy(timingFingerprint = "")) state.subtitles
        else now.subtitles?.copy(timingFingerprint = state.subtitles?.timingFingerprint ?: "")

        return if (sameClips && audio === state.audio && subtitles === state.subtitles) state else state.copy(clips = clips, audio = audio, subtitles = subtitles)
    }

    /**
     * [state] after [operation] ran on its timeline. Null when the operation
     * answers null, when it changes nothing a sequence shows, or when what it
     * leaves cannot be written back ([back]): it does not apply.
     *
     * An edit that is not a sequence ([EditState.layered]) is its timeline:
     * what the operation leaves is the edit, as it is ([TrackEditState.edit]),
     * and [sound] and [settled], which say how a sequence keeps what the
     * operation did, do not apply.
     */
    fun edit(
        state: EditState, sound: Boolean = true,
        settled: (AudioItem) -> AudioItem = { it }, operation: (TrackTimeline) -> TrackTimeline?,
    ): EditState? {
        val from = converted(state)
        if (!state.isSequence) {
            val next = operation(from.timeline) ?: return null
            return if (next === from.timeline || next == from.timeline) null else next.toEdit(state.captureBase)
        }
        val next = operation(from.timeline) ?: return null
        if (next === from.timeline) return null
        return back(state, from.seen, next, sound, settled)?.takeIf { it !== state }
    }

    /**
     * The sound [id] at the level and mute of [audio] ([TrackAudio.settingVolume]), and with each fade that changes from
     * [was] as the conversion writes a fade ([TrackConversion.fades]): the seconds asked for, also when they are more
     * than the item lasts, which is how such a fade is played and saved today.
     */
    fun sounding(timeline: TrackTimeline, id: String, audio: ClipAudio, was: ClipAudio): TrackTimeline {
        val result = TrackAudio.settingVolume(timeline, audio.volumeDB, audio.muted, TimelineSelection(audio = setOf(id)))
        val fades = TrackConversion.fades(audio)
        val before = TrackConversion.fades(was)
        if (fades == before) return result
        return result.copy(audio = result.audio.map { item ->
            if (item.id != id) item else item.copy(fadeIn = if (fades.fadeIn != before.fadeIn) fades.fadeIn else item.fadeIn, fadeOut = if (fades.fadeOut != before.fadeOut) fades.fadeOut else item.fadeOut)
        })
    }

    /** The id the sound item [id] of [state] has on the timeline ([TrackSequenceView.soundID]). */
    fun soundID(state: EditState, id: String): String = TrackSequenceView.soundID(state, id)
}
