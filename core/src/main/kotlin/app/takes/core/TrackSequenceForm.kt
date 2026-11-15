package app.takes.core

import kotlin.math.abs

/**
 * The timeline as a sequence of clips with two sound tracks, or null when it
 * is not one. See [TrackSequenceForm].
 */
fun TrackTimeline.toSequence(): EditState? = TrackSequenceForm.sequence(this)

/**
 * Back from a track timeline to the sequence form a project saves today.
 *
 * It is an inverse, not a view. A timeline is *sequential* when it could have
 * come out of [TrackConversion.timeline]: pictures one after another, meeting
 * exactly or through a transition between neighbours; each picture with its
 * following sound, or with the sound unlinked from it; no placements; only
 * bookmarks on a picture's footage; other sounds whole pieces at recorded
 * speed, without words or regions, fitting on two tracks. For those, and only
 * those, [sequence] answers; converting its answer gives the timeline back.
 * That is how it decides: it builds the one state the timeline could have come
 * from, converts it, and compares.
 *
 * What the sequence form cannot hold comes back changed, the same way every
 * time (converting there and back twice changes nothing more):
 *
 *  - settings of a transition that was not in play are gone: invalid ones, a
 *    transition naming a clip that is no longer next, an opening on a clip
 *    that a transition leads into;
 *  - every transition in play has its fit saved ([TimelineEditing.fitted]);
 *  - a fade is no longer than half its item, which is all that was heard of it;
 *  - sound settings that say nothing, an empty list of bookmarks and an empty
 *    label are null; a sound item's noise reduction, which did nothing, is gone;
 *  - a sound item begins on a tick (it moves by at most half of one), or
 *    exactly where a clip or the sound before it ends when that is the same tick;
 *  - sound items are on the first track that is free, in the order they begin;
 *  - the sound unlinked from a clip comes before the other items that begin
 *    after it, and its muted clip no longer remembers the level and fades it
 *    had before unlinking;
 *  - a caption fingerprint that no longer matched is empty.
 *
 * `captureBase` is not part of either form's saved edit and is not carried.
 */
object TrackSequenceForm {
    /** True when [timeline] is one sequence of clips with at most two tracks of sound: when [sequence] answers. */
    fun isSequential(timeline: TrackTimeline): Boolean = sequence(timeline) != null

    fun sequence(timeline: TrackTimeline): EditState? {
        if (timeline.placements.isNotEmpty()) return null
        val pictures = timeline.chronologicalVideo
        val sounds = timeline.audio.associateBy { it.id }
        if (sounds.size != timeline.audio.size || pictures.map { it.id }.toSet().size != pictures.size) return null

        val marks = LinkedHashMap<String, MutableList<Bookmark>>()
        for (bookmark in timeline.bookmarks) {
            val anchor = bookmark.anchor as? BookmarkAnchor.Source ?: return null
            marks.getOrPut(anchor.occurrence) { mutableListOf() } += Bookmark(bookmark.id, anchor.time, bookmark.label.ifEmpty { null })
        }

        val own = mutableSetOf<String>()
        val unlinked = mutableListOf<Pair<AudioTimelineItem, String>>()
        val clips = pictures.map { picture ->
            val sound = sounds[TrackTimeline.counterpart(picture.id)] ?: return null
            own += sound.id
            val opening = timeline.transitions.firstOrNull { it.incoming == picture.id && it.outgoing == null }
            val leaving = timeline.transitions.firstOrNull { it.outgoing == picture.id }
            val linked = sound.followsVideo == picture.id
            if (!linked) unlinked += sound to picture.id
            picture.content.copy(
                openingTransition = opening?.settings,
                openingTransitionFit = opening?.let { TransitionFit(it.factor) },
                // What a clip has saved for its end names the clip it leads into (YouRec/TrackTimeline.swift:1262-1264).
                transition = leaving?.settings?.copy(nextClipID = leaving.incoming),
                transitionFit = leaving?.let { TransitionFit(it.factor) },
                audio = if (linked) clipAudio(sound, denoise(sound) ?: return null) else ClipAudio(muted = true),
                bookmarks = marks[picture.id],
                subtitleWords = sound.subtitleWords,
            )
        }

        // Seconds for the sounds that follow no picture. A tick is all the timeline keeps of where one begins; where
        // that tick is a clip's edge or the end of the sound before, the exact second is that one's.
        val layout = TimelineEditing.layout(clips)
        val edges = HashMap<Long, Double>()
        pictures.forEachIndexed { index, picture ->
            edges[picture.start.ticks] = layout.placements[index].start
            edges.putIfAbsent(picture.end.ticks, layout.placements[index].end)
        }
        // The end of a sound comes first: two pieces of one sound that meet must not overlap by a rounding on one track.
        val ends = HashMap<Long, Double>()
        val aliases = pictures.associate { TrackTimeline.alias(it.id) to it.id }
        val free = unlinked + timeline.audio.filter { it.id !in own }.map { it to (aliases[it.id] ?: it.id) }
        val placed = mutableListOf<AudioItem>()
        val made = HashMap<String, AudioItem>()
        for ((sound, id) in free.sortedBy { it.first.start }) {
            val run = sound.mapping.runs.singleOrNull() ?: return null
            val start = ends[sound.start.ticks] ?: edges[sound.start.ticks] ?: sound.start.seconds
            val item = AudioItem(id, sound.sourceID, start, run.sourceStart, run.sourceEnd, audio = clipAudio(sound, 0.0))
            val fitted = (0 until AudioItem.TRACKS).map { item.copy(track = it) }
                .firstOrNull { candidate -> placed.none { it.track == candidate.track && it.start < candidate.end && candidate.start < it.end } } ?: return null
            placed += fitted
            made[sound.id] = fitted
            ends.putIfAbsent(sound.end.ticks, fitted.end)
        }
        // In the timeline's order, with each unlinked sound before the first other item that begins after it.
        val audio = timeline.audio.filter { it.id !in own }.map { made.getValue(it.id) }.toMutableList()
        for ((sound, _) in unlinked) {
            val item = made.getValue(sound.id)
            val index = audio.indexOfFirst { it.start > item.start }
            audio.add(if (index < 0) audio.size else index, item)
        }

        val subtitles = timeline.subtitles?.let { settings ->
            val matched = settings.timingFingerprint.isNotEmpty() && settings.timingFingerprint == TrackConversion.timingFingerprint(timeline)
            settings.copy(timingFingerprint = if (matched) SubtitleEditing.fingerprint(clips) else "")
        }
        val state = EditState(clips, audio = audio.ifEmpty { null }, subtitles = subtitles)
        return state.takeIf { same(TrackConversion.timeline(it), timeline) }
    }

    private fun clipAudio(sound: AudioTimelineItem, denoise: Double): ClipAudio? =
        ClipAudio(sound.volumeDB, sound.muted, TrackConversion.fadeSeconds(sound.fadeIn), TrackConversion.fadeSeconds(sound.fadeOut), denoise).takeIf { it != ClipAudio() }

    /** The noise reduction a clip's sound stands for: 0 with no chain, the amount of the one stage of its one region, null for anything else. */
    private fun denoise(sound: AudioTimelineItem): Double? {
        if (sound.enhancements.isEmpty()) return 0.0
        val effect = sound.enhancements.singleOrNull()?.effects?.singleOrNull() ?: return null
        return effect.amount.takeIf { effect.kind == AudioEffectKind.VoiceIsolation && it > 0 }
    }

    /**
     * Whether [made], converted from the state built, is [saved]. Everything
     * is compared exactly but the tick a sound that follows no picture begins
     * on and its length in ticks: seconds were made from those ticks and
     * rounded again, so each may be one tick off.
     */
    private fun same(made: TrackTimeline, saved: TrackTimeline): Boolean {
        if (made.copy(audio = emptyList(), subtitles = null) != saved.copy(audio = emptyList(), subtitles = null)) return false
        if (made.audio.size != saved.audio.size) return false
        for ((a, b) in made.audio.zip(saved.audio)) {
            if (a == b) continue
            if (a.followsVideo != null || b.followsVideo != null) return false
            val runA = a.mapping.runs.singleOrNull() ?: return false
            val runB = b.mapping.runs.singleOrNull() ?: return false
            if (a.copy(start = b.start, mapping = b.mapping) != b || runA.copy(duration = runB.duration) != runB) return false
            if (abs(a.start.ticks - b.start.ticks) > 1 || abs(runA.duration.ticks - runB.duration.ticks) > 1) return false
        }
        val a = made.subtitles
        val b = saved.subtitles
        if (a?.copy(timingFingerprint = "") != b?.copy(timingFingerprint = "")) return false
        if (a == null || b == null) return true
        // A fingerprint is either the timeline's own or none.
        return if (a.timingFingerprint.isEmpty()) b.timingFingerprint.isEmpty() else b.timingFingerprint == TrackConversion.timingFingerprint(saved)
    }
}
