package app.takes.core

/**
 * One use of Move on the track timeline, from the button to Done or Cancel:
 * what moves, how far, the snap that holds it and the draft the preview
 * shows. Only the "selected" scope for now: the selection with what it is
 * linked to.
 *
 * The session never changes the project. While the finger drags, [draft] is
 * the timeline to draw and to preview; however many drags there are, [done]
 * gives one timeline to commit, one Undo step, and [cancel] gives back the
 * timeline Move began on, the very same.
 *
 * The editor logic of the original, YouRec/EditorView.swift:1162-1239
 * (`beginMove`, `updateMove`, `previewMove`, `finishMove`), without the
 * other scopes, the boundary picker and the edge scroll.
 *
 * @property original the timeline when Move began; what Cancel gives back.
 * @property base [original] cut at the ends of the selection's range, if it had one. Moves are measured on it.
 * @property moving what moves: the selection, scoped, with what it is linked to.
 * @property playhead the playhead when Move began, in seconds: a snap point, and where Cancel puts it back.
 * @property scale the timeline's scale when Move began, units a second: what Cancel puts back.
 * @property offset how far [moving] is moved in [draft], snapped and held at zero.
 * @property dragOrigin [offset] when the current drag began; a drag's translation adds to it.
 * @property snap the edge held on a point, to keep it there while the finger stays near.
 * @property draft [base] with [moving] moved by [offset]: what the preview shows.
 */
data class TrackMoveSession private constructor(
    val original: TrackTimeline,
    val base: TrackTimeline,
    val moving: TimelineSelection,
    val playhead: Double,
    val scale: Double,
    val offset: EditTime = EditTime.ZERO,
    val dragOrigin: EditTime = EditTime.ZERO,
    val snap: TimelineMoveSnap? = null,
    val draft: TrackTimeline = base,
) {
    /** How a session ends. */
    sealed interface Outcome {
        /** Commit [timeline] as one edit. */
        data class Moved(val timeline: TrackTimeline) : Outcome

        /** Nothing moved: nothing to commit, the project stays as it is. */
        data object Unchanged : Outcome

        /** The timeline changed under the session (an Undo, a sync): nothing to commit, select again. */
        data object Stale : Outcome
    }

    /** Where the moved group is in [draft]. */
    val interval: EditInterval? get() = TrackLinking.interval(draft, moving)

    /** The stops the moved group can be put on, found on [base]. */
    val boundaries: TimelineMoveBoundaries get() = TrackMove.boundaries(base, moving, moving)

    /** True while [timeline] is still the one this session began on. */
    fun isCurrent(timeline: TrackTimeline): Boolean = timeline === original || timeline == original

    /**
     * The group moved by [offset] seconds from where it began, held at zero,
     * snapped when [snapping] (stationary edges, bookmarks, zero and the
     * playhead) at [scale] units a second, with the held snap kept while the
     * finger stays near. EditorView.swift:1200-1215.
     */
    fun movedTo(offset: Double, snapping: Boolean = true, scale: Double = this.scale): TrackMoveSession {
        val start = TrackLinking.interval(base, moving)?.start ?: return this
        val floor = EditTime.ZERO - start
        var delta = maxOf(floor, EditTime.of(offset))
        var held: TimelineMoveSnap? = null
        if (snapping) {
            val result = TrackMove.snapped(base, moving, delta, scale, snap, listOf(playhead))
            delta = result.delta
            held = result.snap
        }
        val amount = maxOf(floor, delta)
        return copy(offset = amount, snap = held, draft = if (amount == this.offset) draft else TrackMove.moved(base, moving, amount))
    }

    /** The finger moved [translation] seconds from where this drag began. EditorView.swift:602. */
    fun dragged(translation: Double, snapping: Boolean = true, scale: Double = this.scale): TrackMoveSession =
        movedTo(dragOrigin.seconds + translation, snapping, scale)

    /** The finger lifted: the next drag starts from here. Reload the preview from [draft]. EditorView.swift:602. */
    fun dragEnded(): TrackMoveSession = copy(dragOrigin = offset)

    /** The system took the gesture away: back to where this drag began, unsnapped. EditorView.swift:603. */
    fun dragCancelled(): TrackMoveSession = movedTo(dragOrigin.seconds, snapping = false)

    /**
     * Done, against the project's timeline as it is now. One edit however
     * many drags there were; none when the group is back where it was or the
     * timeline changed meanwhile. The cuts made for a range are part of the
     * edit only when something moved. EditorView.swift:1224-1239.
     */
    fun done(current: TrackTimeline): Outcome = when {
        !isCurrent(current) -> Outcome.Stale
        offset == EditTime.ZERO -> Outcome.Unchanged
        else -> Outcome.Moved(draft)
    }

    /** Cancel: the timeline Move began on, untouched; put the playhead back at [playhead] and the scale at [scale]. */
    fun cancel(): TrackTimeline = original

    companion object {
        /**
         * Move begins on [selection] of [timeline], with the playhead at
         * [playhead] seconds and the timeline drawn at [scale] units a second.
         * Null when nothing would move. EditorView.swift:1162-1172.
         */
        fun begin(timeline: TrackTimeline, selection: TimelineSelection, playhead: Double, scale: Double): TrackMoveSession? {
            val scoped = TrackEditing.scoped(timeline, selection)
            val moving = TrackLinking.expanded(scoped.timeline, scoped.selection)
            if (moving.isEmpty || TrackLinking.interval(scoped.timeline, moving) == null) return null
            return TrackMoveSession(timeline, scoped.timeline, moving, playhead, scale)
        }
    }
}
