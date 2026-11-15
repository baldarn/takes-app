package app.takes.core

import kotlin.math.abs

/** Why a track timeline cannot be written as a sequence of clips with two sound tracks. */
enum class SequenceObstacle(val meaning: String) {
    Placement("pictures that play together have windows"),
    Bookmark("a bookmark is on a sound, on a held frame or on the timeline itself"),
    Gap("there is time without picture before a picture"),
    Overlap("two pictures play at once without a transition between them"),
    Timing("a picture or a sound plays in several runs, or a sound of its own plays at another speed"),
    Sound("a sound has a chain of effects other than one noise reduction over all of it, or a censor"),
    Words("a sound of its own has words"),
    SoundTracks("more than two sounds of their own play at once"),
    Links("a link is not one picture with its own sound"),
    Transition("a transition is not between two pictures that follow one another, or does not play"),
    Identity("two items have the same id"),
}

/** What the sequence form says another way, with the same picture and the same sound. */
enum class SequenceNote(val meaning: String) {
    SilentPicture("a picture without sound is a muted clip"),
    HiddenBookmark("a bookmark whose footage or sound is not on the timeline is left out"),
    Ticks("a time differs by a tick or two from the timeline's"),
}

/**
 * A track timeline and the sequence that plays the same, when there is one.
 * [state] is null when there is none, and [obstacle] says why.
 */
data class SequenceResult(
    val timeline: TrackTimeline,
    val state: EditState? = null,
    val obstacle: SequenceObstacle? = null,
    val detail: String = "",
    val notes: Set<SequenceNote> = emptySet(),
)

/**
 * The sequence view of the track timeline: what lets the code that edits a
 * sequence of clips (`TimelineEditing`, `RangeEditing`, `SubtitleEditing`,
 * `AudioUnlinking`) run on the track model and stay as it is seen from
 * outside. docs/TRACKS-PLAN.md, 3.3 and S2.
 *
 * Three things:
 *
 *  - **The view** ([clips], [merging]). The pictures as clips, each with what
 *    a clip of the sequence form holds and the timeline keeps elsewhere: its
 *    transitions (on relations), the level, mute, fades and noise reduction
 *    of its sound, its bookmarks and its words. [merging] takes clips a tool
 *    changed and writes each change where it lives. It writes only what
 *    changed, so what the view does not show (a censor, a fade that goes on
 *    across a cut, a sound of another picture, a placement) is left as it is.
 *    The original's are `projectedClips` and `replaceVideoContents`
 *    (YouRec/TrackTimeline.swift:1259-1267, :1339-1437), which carry the
 *    transitions alone.
 *
 *  - **Back to a sequence** ([sequence]). The sequence whose conversion
 *    ([TrackConversion.timeline]) plays what the timeline plays, or the
 *    reason there is none. Unlike [TrackSequenceForm.sequence], which is an
 *    exact inverse and answers only for a timeline that came out of the
 *    conversion, this one looks through what an edit of the track model
 *    leaves different without being heard or seen: the ids of new pieces,
 *    occurrences, layers of pictures that do not overlap, the order things
 *    are kept in, a tick of rounding.
 *
 *  - **Each function of today, through the track model** ([through] and the
 *    functions after it): the state is converted, the operation of the track
 *    model that the function is to become is run, and the result is brought
 *    back. Null where the operation does not apply, as today's functions
 *    answer.
 */
object TrackSequenceView {
    /** Times of a converted sequence and of a timeline that are this many ticks apart are the same place. */
    const val TICKS = 2L

    private const val SAME_FACTOR = 0.000001
    private const val SAME_SECOND = 0.000000001

    // MARK: The view

    /** The sound of each picture, by the picture's id: the one that follows it, or else the one that was unlinked from it. */
    fun sounds(timeline: TrackTimeline): Map<String, AudioTimelineItem> {
        val result = LinkedHashMap<String, AudioTimelineItem>()
        val taken = HashSet<String>()
        for (sound in timeline.audio) {
            val picture = sound.followsVideo ?: continue
            if (result.putIfAbsent(picture, sound) == null) taken += sound.id
        }
        val loose = timeline.audio.filter { it.followsVideo == null }
        // The shape the conversion gives a sound unlinked from its clip: the clip's counterpart, following nothing.
        for (picture in timeline.video) {
            if (picture.id in result) continue
            val own = loose.firstOrNull { it.id == TrackTimeline.counterpart(picture.id) && it.sourceID == picture.content.sourceID && it.id !in taken } ?: continue
            result[picture.id] = own
            taken += own.id
        }
        // A piece cut from such a sound belongs to the piece of the picture that plays the same footage.
        for (picture in timeline.chronologicalVideo) {
            if (picture.id in result) continue
            val piece = loose.firstOrNull { sound ->
                sound.id !in taken && sound.occurrenceID == picture.occurrenceID && sound.sourceID == picture.content.sourceID &&
                    (sound.mapping.runs.firstOrNull()?.sourceStart ?: return@firstOrNull false) < picture.content.end &&
                    sound.mapping.runs.last().sourceEnd > picture.content.start
            } ?: continue
            result[picture.id] = piece
            taken += piece.id
        }
        return result
    }

    /** The bookmarks on footage, by the id of the picture each belongs to: the piece of its occurrence that plays its second, or the piece before it. */
    private fun marks(timeline: TrackTimeline): Pair<Map<String, List<TimelineBookmark>>, List<TimelineBookmark>> {
        val owned = LinkedHashMap<String, MutableList<TimelineBookmark>>()
        val loose = ArrayList<TimelineBookmark>()
        for (bookmark in timeline.bookmarks) {
            val anchor = bookmark.anchor as? BookmarkAnchor.Source
            val pieces = if (anchor == null) emptyList() else timeline.video.filter { it.occurrenceID == anchor.occurrence && it.content.sourceID == anchor.source }.sortedBy { it.content.start }
            val owner = pieces.lastOrNull { it.content.start <= anchor!!.time } ?: pieces.firstOrNull()
            if (owner == null) loose += bookmark else owned.getOrPut(owner.id) { mutableListOf() } += bookmark
        }
        return owned to loose
    }

    private fun ClipAudio.normalized(): ClipAudio? = takeIf { it != ClipAudio() }

    /** The noise reduction a sound's chains stand for: 0 with none, the amount of one stage over the whole item, null for anything else. */
    private fun denoise(sound: AudioTimelineItem): Double? {
        if (sound.enhancements.isEmpty()) return 0.0
        val region = sound.enhancements.singleOrNull() ?: return null
        val effect = region.effects.singleOrNull() ?: return null
        if (region.interval != EditInterval(EditTime.ZERO, sound.mapping.duration)) return null
        return effect.amount.takeIf { effect.kind == AudioEffectKind.VoiceIsolation && it > 0 }
    }

    private fun clipAudio(sound: AudioTimelineItem, denoise: Double): ClipAudio =
        TrackConversion.withFades(ClipAudio(sound.volumeDB, sound.muted, denoise = denoise), sound.fadeIn, sound.fadeOut)

    /**
     * The pictures as clips, in the order they start, each with its
     * transitions, the settings of its sound, its bookmarks and its words.
     * A picture whose sound is unlinked, or that has none, is a muted clip.
     * It always answers, whatever the timeline holds.
     */
    fun clips(timeline: TrackTimeline): List<TimelineClip> {
        val sounds = sounds(timeline)
        val marks = marks(timeline).first
        return timeline.projectedClips.map { clip ->
            val sound = sounds[clip.id]
            val audio = if (sound?.followsVideo == clip.id) clipAudio(sound, denoise(sound) ?: 0.0) else ClipAudio(muted = true)
            clip.copy(
                audio = audio.normalized(),
                bookmarks = marks[clip.id]?.map { Bookmark(it.id, (it.anchor as BookmarkAnchor.Source).time, it.label.ifEmpty { null }) },
                subtitleWords = sound?.subtitleWords,
            )
        }
    }

    private fun bare(clip: TimelineClip) = clip.copy(audio = null, bookmarks = null, subtitleWords = null)

    /**
     * Merges clips a tool edited ([clips], changed) back into the timeline.
     * The recipes, their order, clips that are gone and clips that are new
     * go through [TrackEditing.replacingVideoContents], with its ripple. Then,
     * for each clip that was there before, only what the tool changed is
     * written: the level, mute, fades and noise reduction to the sound that
     * follows the picture (a fade as the conversion writes one); the words to
     * the picture's sound; the bookmarks to
     * the timeline, on the picture's occurrence. A change to the sound of a
     * clip whose sound is unlinked or gone has nowhere to go and is dropped.
     */
    fun merging(timeline: TrackTimeline, clips: List<TimelineClip>): TrackTimeline {
        val before = HashMap<String, TimelineClip>()
        for (clip in clips(timeline)) before.putIfAbsent(clip.id, clip)
        var result = timeline.replacingVideoContents(clips.map { if (it.id in before) bare(it) else it })
        val sounds = sounds(result)
        val owned = marks(result).first
        val audio = result.audio.toMutableList()
        var bookmarks = result.bookmarks
        for (clip in clips) {
            val old = before[clip.id] ?: continue
            val picture = result.video.firstOrNull { it.id == clip.id } ?: continue
            val sound = sounds[clip.id]
            val index = sound?.let { own -> audio.indexOfFirst { it.id == own.id } } ?: -1
            if (index >= 0 && clip.subtitleWords != old.subtitleWords) audio[index] = audio[index].copy(subtitleWords = clip.subtitleWords)
            if (index >= 0 && clip.audio != old.audio && sound?.followsVideo == clip.id) {
                val next = clip.audio ?: ClipAudio()
                val was = old.audio ?: ClipAudio()
                var item = audio[index].copy(volumeDB = next.volumeDB, muted = next.muted)
                val fades = TrackConversion.fades(next)
                if (fades.fadeIn != TrackConversion.fades(was).fadeIn) item = item.copy(fadeIn = fades.fadeIn)
                if (fades.fadeOut != TrackConversion.fades(was).fadeOut) item = item.copy(fadeOut = fades.fadeOut)
                if (next.denoise != was.denoise) item = item.copy(enhancements = noiseReduction(item, next.denoise))
                audio[index] = item
            }
            if (clip.bookmarks.orEmpty() != old.bookmarks.orEmpty()) {
                val mine = owned[clip.id].orEmpty().mapTo(HashSet()) { it.id }
                val wanted = clip.bookmarks.orEmpty().map { TimelineBookmark(it.id, it.label ?: "", BookmarkAnchor.Source(picture.occurrenceID, picture.content.sourceID, it.time)) }
                val kept = wanted.associateBy { it.id }
                val seen = HashSet<String>()
                bookmarks = bookmarks.mapNotNull { mark -> if (mark.id !in mine) mark else kept[mark.id]?.also { seen += it.id } } + wanted.filter { it.id !in seen }
            }
        }
        result = result.copy(audio = audio, bookmarks = bookmarks)
        return if (result == timeline) timeline else result
    }

    /** The chains of [item] with noise reduction at [amount] over all of it, as the conversion writes it; none for 0. */
    private fun noiseReduction(item: AudioTimelineItem, amount: Double): List<AudioEnhancementRegion> =
        if (amount <= 0) emptyList()
        else listOf(AudioEnhancementRegion(item.id, EditInterval(EditTime.ZERO, item.mapping.duration), listOf(AudioEffect.make(AudioEffectKind.VoiceIsolation, item.id).copy(amount = amount))))

    // MARK: Back to a sequence

    private fun refused(timeline: TrackTimeline, obstacle: SequenceObstacle, detail: String = "") = SequenceResult(timeline, null, obstacle, detail)

    /** A sound as a sound item: one run at recorded speed, with nothing on it the item cannot hold. Null with the obstacle otherwise. */
    private fun item(sound: AudioTimelineItem, id: String, words: Boolean): Pair<AudioItem?, SequenceObstacle?> {
        val run = sound.mapping.runs.singleOrNull() ?: return null to SequenceObstacle.Timing
        if (run.isHold || abs(run.duration.ticks - EditTime.of(run.sourceEnd - run.sourceStart).ticks) > TICKS) return null to SequenceObstacle.Timing
        if (!words && !sound.subtitleWords.isNullOrEmpty()) return null to SequenceObstacle.Words
        if (sound.enhancements.isNotEmpty() || sound.censors.isNotEmpty()) return null to SequenceObstacle.Sound
        return AudioItem(id, sound.sourceID, sound.start.seconds, run.sourceStart, run.sourceEnd, audio = clipAudio(sound, 0.0).normalized()) to null
    }

    /**
     * The sequence that plays what [timeline] plays, or why there is none.
     * It builds the one state the timeline could be, converts it, and
     * compares the two: every picture where the sequence puts it, every
     * transition between the clips it joins with the fit it has, every sound
     * with its picture or on one of two tracks.
     */
    fun sequence(timeline: TrackTimeline): SequenceResult {
        if (timeline.placements.isNotEmpty()) return refused(timeline, SequenceObstacle.Placement)
        val pictures = timeline.chronologicalVideo
        if (timeline.allIDs.size != timeline.video.size + timeline.audio.size) return refused(timeline, SequenceObstacle.Identity)
        val (marks, loose) = marks(timeline)
        val notes = LinkedHashSet<SequenceNote>()
        if (loose.isNotEmpty()) {
            // A mark that shows somewhere has to be on a clip; one that shows nowhere is kept by the timeline alone.
            val shown = timeline.resolvedBookmarks.mapTo(HashSet()) { it.id }
            loose.firstOrNull { it.id in shown }?.let { return refused(timeline, SequenceObstacle.Bookmark, it.id) }
            notes += SequenceNote.HiddenBookmark
        }
        val sounds = sounds(timeline)
        val openings = HashMap<String, TimelineTransition>()
        val leavings = HashMap<String, TimelineTransition>()
        for (relation in timeline.transitions) {
            if (relation.outgoing == null) relation.incoming?.let { openings.putIfAbsent(it, relation) }
            relation.outgoing?.let { leavings.putIfAbsent(it, relation) }
        }
        val clips = ArrayList<TimelineClip>(pictures.size)
        val items = ArrayList<AudioItem>()
        val own = HashSet<String>()
        for (picture in pictures) {
            val run = picture.mapping.runs.singleOrNull()
            if (run == null || run.isHold || abs(run.sourceStart - picture.content.start) > SAME_SECOND || abs(run.sourceEnd - picture.content.end) > SAME_SECOND) {
                return refused(timeline, SequenceObstacle.Timing, picture.id)
            }
            val sound = sounds[picture.id]
            val audio: ClipAudio
            if (sound == null) {
                notes += SequenceNote.SilentPicture
                audio = ClipAudio(muted = true)
            } else if (sound.followsVideo == picture.id) {
                own += sound.id
                if (sound.start != picture.start || sound.mapping != picture.mapping) return refused(timeline, SequenceObstacle.Timing, sound.id)
                if (sound.censors.isNotEmpty()) return refused(timeline, SequenceObstacle.Sound, sound.id)
                audio = clipAudio(sound, denoise(sound) ?: return refused(timeline, SequenceObstacle.Sound, sound.id))
            } else {
                own += sound.id
                val (made, obstacle) = item(sound, picture.id, words = true)
                items += made ?: return refused(timeline, obstacle!!, sound.id)
                audio = ClipAudio(muted = true)
            }
            val opening = openings[picture.id]
            val leaving = leavings[picture.id]
            clips += picture.content.copy(
                openingTransition = opening?.settings, openingTransitionFit = opening?.let { TransitionFit(it.factor) },
                transition = leaving?.settings?.copy(nextClipID = leaving.incoming), transitionFit = leaving?.let { TransitionFit(it.factor) },
                audio = audio.normalized(),
                bookmarks = marks[picture.id]?.map { Bookmark(it.id, (it.anchor as BookmarkAnchor.Source).time, it.label.ifEmpty { null }) },
                subtitleWords = sound?.subtitleWords,
            )
        }
        val pictureIDs = pictures.mapTo(HashSet()) { it.id }
        val aliases = pictures.associate { TrackTimeline.alias(it.id) to it.id }
        for (sound in timeline.audio) {
            if (sound.id in own) continue
            if (sound.followsVideo != null) return refused(timeline, SequenceObstacle.Links, sound.id)
            val id = aliases[sound.id] ?: sound.id
            if (id in pictureIDs && aliases[sound.id] == null) return refused(timeline, SequenceObstacle.Identity, sound.id)
            val (made, obstacle) = item(sound, id, words = false)
            items += made ?: return refused(timeline, obstacle!!, sound.id)
        }
        // The first track that is free, in the order they begin; a tick of overlap between two pieces that meet is no overlap.
        val slack = TICKS.toDouble() / EditTime.SCALE
        val placed = ArrayList<AudioItem>()
        for (item in items.sortedBy { it.start }) {
            placed += (0 until AudioItem.TRACKS).map { item.copy(track = it) }
                .firstOrNull { candidate -> placed.none { it.track == candidate.track && it.start < candidate.end - slack && candidate.start < it.end - slack } }
                ?: return refused(timeline, SequenceObstacle.SoundTracks, item.id)
        }
        // A link is one picture with the sound that follows it, and each of those has one.
        val linked = pictures.mapNotNull { picture -> sounds[picture.id]?.takeIf { it.followsVideo == picture.id }?.let { setOf(picture.id, it.id) } }.toSet()
        if (timeline.links.size != linked.size || timeline.links.any { it.members !in linked }) return refused(timeline, SequenceObstacle.Links)

        val subtitles = timeline.subtitles?.let { settings ->
            val matched = settings.timingFingerprint.isNotEmpty() && settings.timingFingerprint == TrackConversion.timingFingerprint(timeline)
            settings.copy(timingFingerprint = if (matched) SubtitleEditing.fingerprint(clips) else "")
        }
        val state = EditState(clips, audio = placed.ifEmpty { null }, subtitles = subtitles)
        val made = TrackConversion.timeline(state)

        fun apart(a: EditTime, b: EditTime): Boolean {
            if (a != b) notes += SequenceNote.Ticks
            return abs(a.ticks - b.ticks) > TICKS
        }
        for ((index, picture) in pictures.withIndex()) {
            val other = made.video[index]
            if (apart(other.start, picture.start)) return refused(timeline, if (picture.start > other.start) SequenceObstacle.Gap else SequenceObstacle.Overlap, picture.id)
            if (apart(other.mapping.duration, picture.mapping.duration)) return refused(timeline, SequenceObstacle.Timing, picture.id)
        }
        if (made.transitions.size != timeline.transitions.size) return refused(timeline, SequenceObstacle.Transition)
        if (made.bookmarks.size != timeline.bookmarks.size - loose.size) return refused(timeline, SequenceObstacle.Bookmark)
        for (relation in timeline.transitions) {
            val other = made.transitions.firstOrNull { it.outgoing == relation.outgoing && it.incoming == relation.incoming }
                ?: return refused(timeline, SequenceObstacle.Transition, relation.id)
            if (abs(other.factor - relation.factor) > SAME_FACTOR || apart(other.origin, relation.origin)) return refused(timeline, SequenceObstacle.Transition, relation.id)
        }
        return SequenceResult(timeline, state, notes = notes)
    }

    // MARK: Through the track model

    /**
     * [state] as a timeline, [operation] on it, and the result brought back.
     * Null when the operation answers null or changes nothing: it does not
     * apply.
     */
    fun through(state: EditState, operation: (TrackTimeline) -> TrackTimeline?): SequenceResult? {
        val timeline = state.toTimeline()
        val next = operation(timeline) ?: return null
        return if (next == timeline) null else sequence(next)
    }

    /** As [through], for an operation that always answers. */
    fun always(state: EditState, operation: (TrackTimeline) -> TrackTimeline): SequenceResult = sequence(operation(state.toTimeline()))

    /**
     * An edit of the clips' recipes, made by the code that edits clips
     * ([edit], on the view) and merged back: titles, color, framing, and
     * whatever else changes a clip and nothing around it.
     */
    fun editingClips(state: EditState, edit: (EditState) -> EditState?): SequenceResult? = through(state) { timeline ->
        val view = EditState(clips(timeline), audio = state.audio, subtitles = state.subtitles)
        edit(view)?.let { merging(timeline, it.clips) }
    }

    /** The id the sound item [id] of [state] has on the converted timeline. */
    fun soundID(state: EditState, id: String): String {
        val clip = state.clips.firstOrNull { it.id == id } ?: return id
        val item = state.audio.orEmpty().firstOrNull { it.id == id }
        return if (clip.audio?.muted == true && item?.sourceID == clip.sourceID) TrackTimeline.counterpart(id) else TrackTimeline.alias(id)
    }

    private fun selection(state: EditState, targets: EditTargets, range: EditInterval? = null) =
        TimelineSelection(targets.clips, targets.audio.mapTo(LinkedHashSet()) { soundID(state, it) }, range)

    private fun time(seconds: Double) = EditTime.of(seconds)

    // MARK: TimelineEditing

    /** [TimelineEditing.split]: the cut at the playhead, on the 1/30 s grid, of everything under it ([TrackEditing.splitAtPlayhead]). */
    fun split(state: EditState, time: Double): SequenceResult? =
        if (!time.isFinite()) null else through(state) { TrackEditing.splitAtPlayhead(it, time) }

    /** [TimelineEditing.delete]: the deletion of the clip with what it is linked to ([TrackEditing.removing]). */
    fun delete(state: EditState, index: Int): SequenceResult? {
        val id = state.clips.getOrNull(index)?.id ?: return null
        return through(state) { it.removing(it.selection(id)).timeline }
    }

    /**
     * [TimelineEditing.move]: the clip with what it is linked to is lifted
     * out ([TrackClipboard.cut]) and put back, under the ids it has, where
     * the clip now at [to] begins ([TrackClipboard.preparing]). A transition
     * on the cut it goes into is removed with its straight cut restored; the
     * clip's own opening and close go with it.
     */
    fun move(state: EditState, from: Int, to: Int): SequenceResult? {
        if (from !in state.clips.indices || to !in state.clips.indices || from == to) return null
        val id = state.clips[from].id
        return through(state) { timeline ->
            val lifted = TrackClipboard.cut(timeline, timeline.selection(id), EditTime.ZERO) ?: return@through null
            val rest = lifted.timeline
            val order = rest.chronologicalVideo
            val targets = rest.pasteTargets().filter { it.kind == TimelinePasteTarget.Kind.Picture }
            val target = when {
                order.isEmpty() -> null
                to == 0 -> targets.firstOrNull { it.anchor == TimelinePasteTarget.Anchor.Start }
                to >= order.size -> targets.firstOrNull { it.anchor == TimelinePasteTarget.Anchor.End }
                else -> targets.firstOrNull { it.anchor == TimelinePasteTarget.Anchor.Between(order[to - 1].id, order[to].id) } ?: return@through null
            }
            val prepared = TrackClipboard.preparing(rest, lifted.clipboard, target?.time ?: EditTime.ZERO, target)
            val base = prepared.timeline
            val piece = lifted.clipboard.timeline
            val front = (base.video.maxOfOrNull { it.layer } ?: 0) + 1
            base.copy(
                video = (base.video + piece.video.map { it.copy(start = it.start + prepared.position, layer = front + it.layer) }).sortedBy { it.start },
                audio = base.audio + piece.audio.map { it.copy(start = it.start + prepared.position) },
                links = base.links + piece.links,
                transitions = base.transitions + piece.transitions.map { it.copy(origin = it.origin + prepared.position) },
                bookmarks = base.bookmarks + piece.bookmarks.filter { mark -> base.bookmarks.none { it.id == mark.id } },
            ).pruned()
        }
    }

    /** [TimelineEditing.insert]: new footage at [time] ([TrackEditing.inserting]). Every clip put in gets an id of its own. */
    fun insert(state: EditState, clips: List<TimelineClip>, time: Double): SequenceResult? {
        if (clips.isEmpty() || !time.isFinite()) return null
        val ids = clips.mapTo(HashSet()) { it.id }
        if (ids.size != clips.size || state.clips.any { it.id in ids }) return null
        return through(state) { TrackEditing.inserting(it, clips, time) }
    }

    /**
     * [TimelineEditing.trim]: the clip with new source bounds, merged as an
     * edited recipe ([TrackEditing.settingSourceBounds]), which is the path
     * the original's own trim takes until transitions stay with their clips
     * (docs/TRACKS-PLAN.md, 2.2.1).
     */
    fun trim(state: EditState, index: Int, start: Double, end: Double, sourceDuration: Double): SequenceResult? {
        val clip = state.clips.getOrNull(index) ?: return null
        val next = TimelineEditing.trimmed(clip, start, end, sourceDuration)
        if (next == clip) return null
        return through(state) { TrackEditing.settingSourceBounds(it, clip.id, next.start, next.end) }
    }

    /**
     * A trim by a handle, as the original's editor makes it
     * ([TrackEditing.trimming]): inwards a deletion of the trimmed part,
     * outwards a reveal. [delta] is in timeline seconds at the [left] or the
     * right edge of the clip.
     */
    fun trimHandle(state: EditState, index: Int, left: Boolean, delta: Double, sources: List<SourceMedia>): SequenceResult? {
        val id = state.clips.getOrNull(index)?.id ?: return null
        return through(state) { TrackEditing.trimming(it, id, left, delta, sources)?.timeline }
    }

    /** [TimelineEditing.removeIntervals]: each stretch of source time is cut out and deleted ([TrackEditing.removingSource]). */
    fun removeIntervals(state: EditState, index: Int, cuts: List<SourceInterval>): SequenceResult? {
        val id = state.clips.getOrNull(index)?.id ?: return null
        return through(state) { TrackEditing.removingSource(it, id, cuts) }
    }

    /** The two clips of join [join] as [TimelineEditing] numbers it, by id: null on the side an opening or a close does not have. Null when there is no such join. */
    fun joinClips(clips: List<TimelineClip>, join: Int): Pair<String?, String?>? {
        val count = clips.size
        return when {
            count == 0 || join < 0 -> null
            join == 0 -> null to clips[0].id
            join < count -> clips[join - 1].id to clips[join].id
            join == count -> clips[count - 1].id to null
            join < 2 * count -> null to clips[join - count].id
            join < 3 * count - 1 -> clips[join - 2 * count].id to null
            else -> null
        }
    }

    /**
     * [TimelineEditing.setJoin]: the join is found by its two clips among the
     * joins of the timeline ([TrackLayout]) and the transition set there
     * ([TrackTransitions.setting]); null settings remove it and bring the
     * clips back together ([TrackTransitions.restoringStraightCut]).
     */
    fun setJoin(state: EditState, join: Int, settings: TransitionSettings?): SequenceResult? {
        val (outgoing, incoming) = joinClips(state.clips, join) ?: return null
        if (settings != null && !settings.valid) return null
        return through(state) { timeline ->
            val found = TrackLayout.of(timeline).joins.firstOrNull { it.outgoingID == outgoing && it.incomingID == incoming } ?: return@through null
            if (settings == null) TrackTransitions.restoringStraightCut(timeline, found.index) else TrackTransitions.setting(timeline, settings, found.index)
        }
    }

    /** [TimelineEditing.setTransition]: [setJoin] with the settings the join has, or new ones, at [style] and [duration]. */
    fun setTransition(state: EditState, index: Int, duration: Double?, style: TransitionStyle): SequenceResult? {
        if (index !in 0 until state.clips.size - 1) return null
        if (duration != null && (!duration.isFinite() || duration <= 0)) return null
        val current = state.toTimeline().transitions.firstOrNull { it.outgoing == state.clips[index].id && it.incoming == state.clips[index + 1].id }?.settings
        return setJoin(state, index + 1, duration?.let { (current ?: TransitionSettings()).copy(style = style, duration = it) })
    }

    /**
     * [TimelineEditing.toggleBookmark]: the bookmark of the clip within
     * reach of [sourceTime] is removed ([TrackBookmarks.removing]), or one is
     * added at the place on the timeline where the clip shows that second,
     * attached to what plays there ([TrackBookmarks.adding]).
     */
    fun toggleBookmark(state: EditState, index: Int, sourceTime: Double): SequenceResult? {
        val clip = state.clips.getOrNull(index) ?: return null
        if (!sourceTime.isFinite() || sourceTime < clip.start || sourceTime > clip.end) return null
        return through(state) { timeline ->
            val picture = timeline.video.first { it.id == clip.id }
            val existing = marks(timeline).first[clip.id].orEmpty()
                .map { it to abs((it.anchor as BookmarkAnchor.Source).time - sourceTime) }
                .filter { it.second <= TimelineEditing.BOOKMARK_REACH }.minByOrNull { it.second }?.first
            if (existing != null) return@through TrackBookmarks.removing(timeline, existing.id)
            val local = picture.mapping.local(sourceTime) ?: picture.mapping.duration
            TrackBookmarks.adding(timeline, picture.start + local, clip.id)
        }
    }

    /** The sound [id] at the level, mute and fades of [audio], set as the track model sets them ([TrackAudio.settingVolume], [TrackAudio.settingFade]): only a fade that changes is set. */
    private fun sounding(timeline: TrackTimeline, id: String, audio: ClipAudio, was: ClipAudio): TrackTimeline {
        var result = TrackAudio.settingVolume(timeline, audio.volumeDB, audio.muted, TimelineSelection(audio = setOf(id)))
        if (audio.fadeIn != was.fadeIn) result = TrackAudio.settingFade(result, id, AudioFadeEdge.FadeIn, audio.fadeIn)
        if (audio.fadeOut != was.fadeOut) result = TrackAudio.settingFade(result, id, AudioFadeEdge.FadeOut, audio.fadeOut)
        return result
    }

    /**
     * [TimelineEditing.setAudio]: the level, mute and fades of the sound that
     * follows the clip, and its noise reduction as one chain over all of it
     * ([TrackAudio.enhancing]). Null for a clip whose sound is unlinked or
     * gone: it has no sound to set.
     */
    fun setAudio(state: EditState, index: Int, audio: ClipAudio): SequenceResult? {
        val clip = state.clips.getOrNull(index) ?: return null
        if (!audio.valid) return null
        return through(state) { timeline ->
            val sound = sounds(timeline)[clip.id]?.takeIf { it.followsVideo == clip.id } ?: return@through null
            val was = clipAudio(sound, denoise(sound) ?: 0.0)
            var result = sounding(timeline, sound.id, audio, was)
            if (audio.denoise != was.denoise) {
                val chain = if (audio.denoise > 0) listOf(AudioEffect.make(AudioEffectKind.VoiceIsolation, sound.id).copy(amount = audio.denoise)) else emptyList()
                result = TrackAudio.enhancing(result, chain, TimelineSelection(audio = setOf(sound.id)), sound.id)
            }
            result
        }
    }

    /** [TimelineEditing.addAudio]: a whole sound laid over what is there ([TrackAudio.inserting]). */
    fun addAudio(state: EditState, sourceID: String, sourceDuration: Double, at: Double): SequenceResult? {
        if (!at.isFinite() || at < 0 || !sourceDuration.isFinite() || sourceDuration < TimelineEditing.MINIMUM_CLIP) return null
        return through(state) { TrackAudio.inserting(it, SourceMedia(sourceID, "", sourceDuration, 0L, audioOnly = true), time(at)).timeline }
    }

    private fun sound(state: EditState, timeline: TrackTimeline, id: String): AudioTimelineItem? {
        if (state.audio.orEmpty().none { it.id == id }) return null
        val name = soundID(state, id)
        return timeline.audio.firstOrNull { it.id == name }
    }

    /** [TimelineEditing.moveAudio]: the sound moved to begin at [start] ([TrackMove.moved]). */
    fun moveAudio(state: EditState, id: String, start: Double): SequenceResult? {
        if (!start.isFinite() || start < 0) return null
        return through(state) { timeline ->
            val sound = sound(state, timeline, id) ?: return@through null
            timeline.moved(TimelineSelection(audio = setOf(sound.id)), time(start) - sound.start)
        }
    }

    /** [TimelineEditing.trimAudio]: the part of the sound before or after [time] is cut off and deleted ([TrackEditing.scoped], [TrackEditing.removing]). */
    fun trimAudio(state: EditState, id: String, time: Double, leading: Boolean): SequenceResult? {
        if (!time.isFinite()) return null
        return through(state) { timeline ->
            val sound = sound(state, timeline, id) ?: return@through null
            val at = time(time)
            if (at <= sound.start || at >= sound.end) return@through null
            val scope = timeline.scoped(TimelineSelection(audio = setOf(sound.id), range = if (leading) EditInterval(sound.start, at) else EditInterval(at, sound.end)))
            scope.timeline.removing(scope.selection).timeline
        }
    }

    /** [TimelineEditing.setAudioItemSound]: the level, mute and fades of a sound of its own. */
    fun setAudioItemSound(state: EditState, id: String, audio: ClipAudio): SequenceResult? {
        if (!audio.valid) return null
        return through(state) { timeline ->
            val sound = sound(state, timeline, id) ?: return@through null
            sounding(timeline, sound.id, audio, clipAudio(sound, 0.0))
        }
    }

    /** [TimelineEditing.removeAudio]: the sound deleted by itself, which moves nothing. */
    fun removeAudio(state: EditState, id: String): SequenceResult? = through(state) { timeline ->
        val sound = sound(state, timeline, id) ?: return@through null
        timeline.removing(TimelineSelection(audio = setOf(sound.id))).timeline
    }

    /** [TimelineEditing.setSpeed]: the clip at [rate], with the ripple of an edited recipe ([TrackEditing.settingSpeed]). */
    fun setSpeed(state: EditState, index: Int, rate: Double): SequenceResult? {
        val clip = state.clips.getOrNull(index) ?: return null
        val speed = if (rate == 1.0) null else (clip.speed ?: SpeedSettings()).copy(rate = rate)
        if (speed?.valid == false || speed == clip.speed) return null
        return through(state) { TrackEditing.settingSpeed(it, clip.id, speed) }
    }

    // MARK: RangeEditing

    /** A result with where the playhead goes. */
    data class Removed(val result: SequenceResult, val position: Double?)

    private fun bounded(range: TimeRange): TimeRange? = TimeRange(maxOf(0.0, range.start), range.end).takeIf { range.valid && it.valid }

    /**
     * Second [seconds] of the sequence as a time of [timeline]: the edge of an
     * item when it is within [TICKS] of one. A cut of the sequence is a sum of
     * seconds, and on the timeline a sum of lengths each rounded to a tick, so
     * the same cut can come out a tick apart; taken as it is, a range marked
     * on a cut would slice a piece one tick long off the item beside it.
     */
    fun onTimeline(timeline: TrackTimeline, seconds: Double): EditTime {
        val time = EditTime.of(seconds)
        var nearest = time
        var distance = TICKS + 1
        fun consider(edge: EditTime) {
            val apart = abs(edge.ticks - time.ticks)
            if (apart < distance) { nearest = edge; distance = apart }
        }
        for (item in timeline.video) { consider(item.start); consider(item.end) }
        for (item in timeline.audio) { consider(item.start); consider(item.end) }
        return nearest
    }

    /** What [scope] names on the timeline of [state]: whole items, or with a range the part of them inside it. Null for a range the editor does not act on. */
    fun selection(state: EditState, timeline: TrackTimeline, scope: EditScope): TimelineSelection? = when (scope) {
        EditScope.Whole -> timeline.entireSelection()
        is EditScope.Selected -> timeline.expanded(selection(state, scope.targets))
        is EditScope.Range -> bounded(scope.range)?.let { range ->
            val interval = EditInterval(onTimeline(timeline, range.start), onTimeline(timeline, range.end))
            scope.targets?.let { selection(state, it, interval) } ?: timeline.entireSelection(interval)
        }
    }

    /**
     * [RangeEditing.delete], as the original's editor deletes
     * (YouRec/EditorView.swift:1125-1130): the items are cut at the two ends
     * of a range and the pieces deleted; a selection and the whole are
     * deleted as they are. Linked picture and sound close their time; a
     * picture or a sound by itself leaves a gap.
     */
    fun delete(state: EditState, scope: EditScope, playhead: Double? = null): Removed? {
        val timeline = state.toTimeline()
        val position = playhead?.takeIf { it.isFinite() }?.let(::time)
        val scoped = timeline.scoped(selection(state, timeline, scope) ?: return null)
        val removal = scoped.timeline.removing(scoped.selection, position)
        if (removal.timeline == timeline) return null
        return Removed(sequence(removal.timeline), removal.position?.seconds)
    }

    /** What was copied, and the same as a sequence that begins at zero. */
    data class Copied(val clipboard: TimelineClipboard, val fragment: SequenceResult)

    /** [RangeEditing.copy] ([TrackClipboard.copied]). Null when nothing is covered. */
    fun copy(state: EditState, scope: EditScope): Copied? {
        val timeline = state.toTimeline()
        val clipboard = timeline.copied(selection(state, timeline, scope) ?: return null)
        return if (clipboard.isEmpty) null else Copied(clipboard, sequence(clipboard.timeline))
    }

    /** What a cut leaves, what it lifted, and where the playhead goes. */
    data class CutOut(val result: SequenceResult, val copied: Copied, val position: Double?)

    /** [RangeEditing.cut] ([TrackClipboard.cut]). */
    fun cut(state: EditState, scope: EditScope, playhead: Double? = null): CutOut? {
        val timeline = state.toTimeline()
        val cut = TrackClipboard.cut(timeline, selection(state, timeline, scope) ?: return null, time(playhead?.takeIf { it.isFinite() } ?: 0.0)) ?: return null
        return CutOut(sequence(cut.timeline), Copied(cut.clipboard, sequence(cut.clipboard.timeline)), cut.position.seconds.takeIf { playhead != null })
    }

    /** What a paste leaves, the ids of what it added, and where that begins. */
    data class Pasted(val result: SequenceResult, val selection: TimelineSelection, val start: Double?)

    /** [RangeEditing.paste] at the playhead ([TrackClipboard.pasting]). */
    fun paste(state: EditState, clipboard: TimelineClipboard, at: Double): Pasted? {
        if (clipboard.isEmpty || !at.isFinite()) return null
        val timeline = state.toTimeline()
        val pasted = timeline.pasting(clipboard, time(at))
        val start = (pasted.timeline.video.filter { it.id in pasted.selection.video }.map { it.start } +
            pasted.timeline.audio.filter { it.id in pasted.selection.audio }.map { it.start }).minOrNull()
        return Pasted(sequence(pasted.timeline), pasted.selection, start?.seconds)
    }

    /** The edit cut at the ends of a range, and the clips a tool applies to, in playing order. */
    data class Scoped(val result: SequenceResult, val clips: List<String>)

    /** [RangeEditing.scoped]: the items are cut at the two ends of the range, wherever those fall ([TrackEditing.scoped]). */
    fun scoped(state: EditState, scope: EditScope): Scoped {
        val timeline = state.toTimeline()
        val scoped = selection(state, timeline, scope)?.let { timeline.scoped(it) } ?: SelectedTimeline(timeline, TimelineSelection())
        return Scoped(sequence(scoped.timeline), scoped.timeline.chronologicalVideo.map { it.id }.filter { it in scoped.selection.video })
    }

    // MARK: SubtitleEditing

    /** [SubtitleEditing.cues], from the words on the sounds; a caption names the clip whose sound it is on, or else its sound. */
    fun cues(state: EditState): List<SubtitleCue> {
        val timeline = state.toTimeline()
        val owner = sounds(timeline).entries.associate { it.value.id to it.key }
        return TrackSubtitles.cues(timeline).map { it.copy(audioID = owner[it.audioID] ?: it.audioID) }
    }

    fun transcript(state: EditState): String = TrackSubtitles.transcript(state.toTimeline())

    fun groupingAvailable(state: EditState): Boolean = TrackSubtitles.groupingAvailable(state.toTimeline())

    fun stretches(state: EditState): List<SubtitleStretch> = TrackSubtitles.stretches(state.toTimeline())

    /** [SubtitleEditing.install]: the words heard in each recording, on every sound that plays them ([TrackSubtitles.installingSourceWords]). */
    fun install(state: EditState, words: Map<String, List<SubtitleWord>>, language: String): SequenceResult =
        always(state) { TrackSubtitles.installingSourceWords(it, words, language) }

    fun remove(state: EditState): SequenceResult = always(state) { TrackSubtitles.removing(it) }

    /** [SubtitleEditing.restyle]: the look alone; what the captions were timed for stays what the timeline says. */
    fun restyle(state: EditState, settings: SubtitleSettings): SequenceResult? = through(state) { timeline ->
        if (state.subtitles == null || settings == state.subtitles) null
        else TrackSubtitles.restyling(timeline, settings.copy(timingFingerprint = timeline.subtitles?.timingFingerprint ?: ""))
    }

    fun regroup(state: EditState, count: Int): SequenceResult? = through(state) { TrackSubtitles.regrouping(it, count) }

    /** [SubtitleEditing.correct]: [cue] names a clip; its words are on the clip's sound. */
    fun correct(state: EditState, cue: SubtitleCue, text: String): SequenceResult? = through(state) { timeline ->
        val sound = sounds(timeline)[cue.audioID] ?: timeline.audio.firstOrNull { it.id == cue.audioID } ?: return@through null
        TrackSubtitles.correcting(timeline, cue.copy(audioID = sound.id), text)
    }

    fun delete(state: EditState, cue: SubtitleCue): SequenceResult? = correct(state, cue, "")

    // MARK: AudioUnlinking

    /** [AudioUnlinking.unlink]: the link of the clip is removed and nothing else changes ([TrackLinking.unlinked]). */
    fun unlink(state: EditState, index: Int): SequenceResult? {
        val id = state.clips.getOrNull(index)?.id ?: return null
        return through(state) { it.unlinked(it.selection(id)) }
    }

    /** [AudioUnlinking.relink]: the clip and the sound unlinked from it are linked again ([TrackLinking.joining]). Null when they cannot be. */
    fun relink(state: EditState, index: Int): SequenceResult? {
        val id = state.clips.getOrNull(index)?.id ?: return null
        return through(state) { timeline ->
            val sound = sounds(timeline)[id]?.takeIf { it.followsVideo == null } ?: return@through null
            timeline.joining(TimelineSelection(setOf(id), setOf(sound.id))).valueOrNull?.timeline
        }
    }

    /** [AudioUnlinking.syncOffset] ([TrackLinking.syncOffset]): seconds the unlinked sound of the clip plays after its picture. */
    fun syncOffset(state: EditState, index: Int): Double? {
        val id = state.clips.getOrNull(index)?.id ?: return null
        val timeline = state.toTimeline()
        val sound = sounds(timeline)[id]?.takeIf { it.followsVideo == null } ?: return null
        return timeline.syncOffset(sound.id)?.seconds
    }

    /** [AudioUnlinking.restoreSync] ([TrackLinking.restoringSync]). */
    fun restoreSync(state: EditState, index: Int): SequenceResult? {
        val id = state.clips.getOrNull(index)?.id ?: return null
        return through(state) { timeline ->
            val sound = sounds(timeline)[id]?.takeIf { it.followsVideo == null } ?: return@through null
            timeline.restoringSync(sound.id)
        }
    }

    /** [AudioUnlinking.setSound]: on the sound of the clip, wherever it is. */
    fun setSound(state: EditState, index: Int, audio: ClipAudio): SequenceResult? {
        val id = state.clips.getOrNull(index)?.id ?: return null
        if (!audio.valid) return null
        val unlinked = sounds(state.toTimeline())[id]?.takeIf { it.followsVideo == null } ?: return setAudio(state, index, audio)
        return through(state) { sounding(it, unlinked.id, audio, clipAudio(unlinked, 0.0)) }
    }
}
