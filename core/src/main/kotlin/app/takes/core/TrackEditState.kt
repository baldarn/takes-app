package app.takes.core

/** The edit this timeline is: the sequence it is when it is one ([TrackSequenceForm.sequence]), otherwise the timeline itself ([TrackEditState]). */
fun TrackTimeline.toEdit(captureBase: Int? = null): EditState = TrackEditState.edit(this, captureBase)

/**
 * Why an editing function of the sequence form is not available while the
 * edit is not a sequence ([EditState.isSequence] false). The editor disables
 * the tool and may say [reason]; each entry names the functions it covers.
 */
enum class SequenceOnly(val reason: String) {
    /** [TimelineEditing.move]: clips are put somewhere else by their number in the row. */
    Reorder("Pictures are not in one row here. Use Move to put a picture somewhere else."),

    /** [TimelineEditing.setJoin], [TimelineEditing.setTransition]: a join is numbered in the row. */
    Transition("Transitions are set between pictures that follow one another in one row, and this edit has pictures over one another or apart."),

    /** [RangeEditing.copy], [RangeEditing.cut], [RangeEditing.paste]: the clipboard holds a row of clips. */
    Clipboard("Copy, Cut and Paste hold a row of clips, and this edit has pictures over one another or apart."),
}

/**
 * The edit of a project when it is a track timeline that no sequence of
 * clips can be (docs/TRACKS-PLAN.md, S9 and S10): pictures over one another
 * after a Move, time with nothing in it.
 *
 * Such an edit is an [EditState] whose [EditState.layered] is the timeline,
 * the truth. Its [EditState.clips] are the timeline's pictures read as clips,
 * in the order they begin, each with what a clip holds
 * ([TrackSequenceView.clips]); its [EditState.audio] the sounds that follow no
 * picture and play in one run, on the first of the two tracks free for them,
 * with the ids they have on the timeline; its [EditState.subtitles] the
 * timeline's. That view is what the screens read: they find a clip by its id
 * or by its number in [EditState.clips], never by where a sequence would put
 * it. A tool that changes clips' recipes and writes them into
 * [EditState.clips] (color, framing, titles, effects) is merged into the
 * timeline as the original's `replaceVideoContents` merges it
 * ([TrackSequenceView.merging]); so is a change of [EditState.subtitles].
 * A change of [EditState.audio] by itself is not: the sound items are
 * changed through [TimelineEditing].
 *
 * An edit that is a sequence never carries a timeline here: everything about
 * it, its bytes on disk included, is what it always was. An operation that
 * leaves a timeline that is a sequence again gives a sequence ([edit]).
 *
 * How each editing function behaves on such an edit is said on the function
 * and listed in docs/TRACKS-PLAN.md ("Timeline edits, as built"): it runs the
 * track model's operation on the timeline directly, or answers null for a
 * reason of [SequenceOnly].
 */
object TrackEditState {
    /** [timeline] as an edit: a sequence when it is one, else the timeline with its view. */
    fun edit(timeline: TrackTimeline, captureBase: Int? = null): EditState {
        TrackSequenceForm.sequence(timeline)?.let { return if (it.captureBase == captureBase) it else it.copy(captureBase = captureBase) }
        return layered(timeline, captureBase)
    }

    /** [timeline] as an edit that is not a sequence, whatever it is. */
    internal fun layered(timeline: TrackTimeline, captureBase: Int? = null): EditState =
        EditState(TrackSequenceView.clips(timeline), captureBase, items(timeline), timeline.subtitles, layered = timeline)

    /** The sounds of [timeline] that follow no picture and play in one run, as sound items on the first track free, in the order they begin. */
    private fun items(timeline: TrackTimeline): List<AudioItem>? {
        val result = ArrayList<AudioItem>()
        for (sound in timeline.audio.sortedBy { it.start }) {
            if (sound.followsVideo != null) continue
            val run = sound.mapping.runs.singleOrNull() ?: continue
            val audio = TrackConversion.withFades(ClipAudio(sound.volumeDB, sound.muted), sound.fadeIn, sound.fadeOut).takeIf { it != ClipAudio() }
            TrackWrapping.place(result, AudioItem(sound.id, sound.sourceID, sound.start.seconds, run.sourceStart, run.sourceEnd, audio = audio))?.let { result += it }
        }
        return result.ifEmpty { null }
    }

    /** The timeline of [state], whose [EditState.layered] is [timeline], with what a tool wrote into its view merged in. */
    internal fun merged(state: EditState, timeline: TrackTimeline): TrackTimeline {
        var result = timeline
        if (state.clips != TrackSequenceView.clips(timeline)) result = TrackSequenceView.merging(result, state.clips)
        if (state.subtitles != timeline.subtitles) result = result.copy(subtitles = state.subtitles)
        return result
    }

    /** [reason] when [state] is not a sequence, the reason the tool is off; null when it is available. */
    fun unavailable(state: EditState, reason: SequenceOnly): SequenceOnly? = reason.takeUnless { state.isSequence }

    /** [operation] on the timeline of [state], as an edit; null when it answers null or changes nothing. */
    internal fun editing(state: EditState, operation: (TrackTimeline) -> TrackTimeline?): EditState? {
        val timeline = state.toTimeline()
        val next = operation(timeline) ?: return null
        if (next === timeline || next == timeline) return null
        return next.toEdit(state.captureBase)
    }

    // MARK: Gaps

    /** Every stretch before the end where nothing plays on any lane ([TrackEditing.gaps]): what Close gap removes. None for a sequence of clips. */
    fun gaps(state: EditState): List<TimeRange> = TrackEditing.gaps(state.toTimeline()).map { TimeRange(it.start.seconds, it.end.seconds) }

    /** Every stretch before the end without picture ([TrackEditing.pictureGaps]): where the movie is black. */
    fun pictureGaps(state: EditState): List<TimeRange> = TrackEditing.pictureGaps(state.toTimeline()).map { TimeRange(it.start.seconds, it.end.seconds) }

    /**
     * Close gap at timeline second [time]: everything after the gap there, on
     * every lane, comes earlier by its length ([TrackEditing.closingGap]).
     * One edit, a sequence again when that is what is left. Null where there
     * is no gap.
     */
    fun closingGap(state: EditState, time: Double): EditState? {
        if (!time.isFinite()) return null
        return editing(state) { it.closingGap(EditTime.of(time)) }
    }

    // MARK: Where things are

    /** Timeline second where the clip at [index] of [state] begins: its picture's start on a timeline, the layout's in a sequence. */
    fun clipStart(state: EditState, index: Int): Double? {
        val clip = state.clips.getOrNull(index) ?: return null
        val timeline = state.layered ?: return TimelineEditing.clipStart(state.clips, index)
        return timeline.video.firstOrNull { it.id == clip.id }?.start?.seconds
    }

    /** The start of every clip of [state], as [clipStart]. */
    internal fun starts(state: EditState): List<Double> {
        val timeline = state.layered ?: return state.clips.indices.map { TimelineEditing.clipStart(state.clips, it) }
        val starts = HashMap<String, Double>()
        for (picture in timeline.video) starts.putIfAbsent(picture.id, picture.start.seconds)
        return state.clips.map { starts[it.id] ?: 0.0 }
    }

    /**
     * The clip in front at timeline second [time] and the seconds into it:
     * [TimelineEditing.locate] in a sequence, the picture of the highest
     * layer playing there on a timeline. Null where no picture plays.
     */
    fun locate(state: EditState, time: Double): TimelineEditing.Location? {
        val timeline = state.layered ?: return TimelineEditing.locate(state.clips, time)
        val at = EditTime.of(time)
        val picture = timeline.video.filter { at >= it.start && at < it.end }.maxByOrNull { it.layer } ?: return null
        val index = state.clips.indexOfFirst { it.id == picture.id }.takeIf { it >= 0 } ?: return null
        return TimelineEditing.Location(index, time - picture.start.seconds)
    }

    /** Timeline seconds of every bookmark, in order: [TimelineEditing.bookmarkTimes] in a sequence, the timeline's own marks otherwise. */
    fun bookmarkTimes(state: EditState): List<Double> {
        val timeline = state.layered ?: return TimelineEditing.bookmarkTimes(state.clips)
        return timeline.resolvedBookmarks.map { it.time.seconds }.sorted()
    }

    // MARK: The functions of the sequence form, on a timeline

    /** [TimelineEditing.insert] on a timeline: time is opened at [time] and [clips] go in there, in front, with the ids they have. */
    internal fun insert(state: EditState, clips: List<TimelineClip>, time: Double): EditState? = editing(state) { timeline ->
        val point = EditTime.of(time.coerceIn(0.0, state.duration))
        val addition = EditState(clips).toTimeline()
        val opened = timeline.insertingTime(addition.duration, point)
        val front = (opened.video.maxOfOrNull { it.layer } ?: 0) + 1
        opened.copy(
            video = (opened.video + addition.video.map { it.copy(start = it.start + point, layer = it.layer + front) }).sortedBy { it.start },
            audio = opened.audio + addition.audio.map { it.copy(start = it.start + point) },
            links = opened.links + addition.links,
            bookmarks = opened.bookmarks + addition.bookmarks,
            transitions = opened.transitions + addition.transitions.map { it.copy(origin = it.origin + point) },
        ).pruned()
    }

    /**
     * [clips] after everything on the timeline of [state], where its picture
     * ends, without moving or cutting anything: the takes recorded since a
     * step was made ([Project.edit]). In front of every picture there.
     */
    internal fun appending(state: EditState, clips: List<TimelineClip>, captureBase: Int?): EditState {
        val timeline = state.toTimeline()
        if (clips.isEmpty()) return if (state.captureBase == captureBase) state else state.copy(captureBase = captureBase)
        val point = timeline.video.maxOfOrNull { it.end } ?: EditTime.ZERO
        val addition = EditState(clips).toTimeline()
        val front = (timeline.video.maxOfOrNull { it.layer } ?: 0) + 1
        return timeline.copy(
            video = timeline.video + addition.video.map { it.copy(start = it.start + point, layer = it.layer + front) },
            audio = timeline.audio + addition.audio.map { it.copy(start = it.start + point) },
            links = timeline.links + addition.links,
            bookmarks = timeline.bookmarks + addition.bookmarks,
            transitions = timeline.transitions + addition.transitions.map { it.copy(origin = it.origin + point) },
        ).pruned().toEdit(captureBase)
    }

    /** [RangeEditing.markTime] on a timeline: on the frame grid, or on the edge of an item near enough at [pointsPerSecond]. */
    internal fun markTime(state: EditState, time: Double, pointsPerSecond: Double?): Double {
        val timeline = state.toTimeline()
        val mark = pointsPerSecond?.let { TrackMove.snapTarget(timeline, time, it) } ?: RangeEditing.quantize(time)
        return mark.coerceIn(0.0, state.duration)
    }

    /** [RangeEditing.interval] on a timeline: the marked range inside the movie, else where the picture [clipID] plays, else the whole movie. */
    internal fun interval(state: EditState, range: TimeRange?, clipID: String?): TimeRange {
        if (range != null) return TimeRange(maxOf(0.0, range.start), minOf(state.duration, range.end))
        val picture = state.toTimeline().video.firstOrNull { it.id == clipID } ?: return TimeRange(0.0, state.duration)
        return TimeRange(picture.start.seconds, picture.end.seconds)
    }
}
