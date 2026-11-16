package app.takes.core

/**
 * One use of Move on the track timeline, from the button to Done or Cancel:
 * what moves, how far, the snap that holds it and the draft the preview
 * shows.
 *
 * What moves is the selection with what it is linked to, and, by [scope],
 * what joins it on the left, on the right or on both sides, following cuts
 * and transitions up to the first gap ([TrackMove.choices]). The scope can be
 * changed at any time: the move asked for stays, so trying a wider group that
 * hits time zero and going back gives the move that was there before.
 *
 * The session never changes the project. While the finger drags, [draft] is
 * the timeline to draw and to preview; however many drags, nudges, stops and
 * changes of scope there are, [done] gives one timeline to commit, one Undo
 * step, and [cancel] gives back the timeline Move began on, the very same.
 *
 * The editor logic of the original, YouRec/EditorView.swift:1162-1239
 * (`beginMove`, `changeMoveScope`, `placeMove`, `updateMove`, `finishMove`).
 *
 * @property original the timeline when Move began; what Cancel gives back.
 * @property base [original] cut at the ends of the selection's range, if it had one. Moves are measured on it.
 * @property choices what each scope moves, found on [base] when Move began.
 * @property scope the scope in use; [moving] is what it moves.
 * @property boundaries the stops the group of [scope] can be put on, found on [base]: they never move with the draft.
 * @property playhead the playhead when Move began, in seconds: a snap point, and where Cancel puts it back.
 * @property scale the timeline's scale when Move began, units a second: what Cancel puts back.
 * @property offset how far [moving] is moved in [draft], snapped and held at zero.
 * @property requested the move last asked for by a drag, a nudge or a stop, before the scope held it at zero; a change of scope asks for it again.
 * @property dragOrigin [offset] when the current drag began; a drag's translation adds to it.
 * @property snap the edge held on a point, to keep it there while the finger stays near.
 * @property draft [base] with [moving] moved by [offset]: what the preview shows.
 * @property viewportEnd where the timeline is drawn to while the group was put on a stop: the longer of the timeline and the draft, so a stop beyond the end of a shortened draft stays reachable. Null until a stop is chosen.
 */
data class TrackMoveSession private constructor(
    val original: TrackTimeline,
    val base: TrackTimeline,
    val choices: TimelineMoveChoices,
    val scope: TimelineMoveScope,
    val boundaries: TimelineMoveBoundaries,
    val playhead: Double,
    val scale: Double,
    val offset: EditTime = EditTime.ZERO,
    val requested: EditTime = EditTime.ZERO,
    val dragOrigin: EditTime = EditTime.ZERO,
    val snap: TimelineMoveSnap? = null,
    val draft: TrackTimeline = base,
    val viewportEnd: EditTime? = null,
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

    /** What moves: the group of [scope]. */
    val moving: TimelineSelection get() = choices[scope]

    /** What was picked: the selection with what it is linked to. Its start is [TimelineMoveBoundaries.anchor]. */
    val picked: TimelineSelection get() = choices.selected

    /** Where the moved group is in [draft]. */
    val interval: EditInterval? get() = TrackLinking.interval(draft, moving)

    /** Where the picked material begins in [draft]: the time the Move bar shows as "Selection start". */
    val anchor: EditTime get() = boundaries.anchor + offset

    /** The stop before [anchor], for "Move to previous cut"; null at the first. */
    val previousStop: EditTime? get() = boundaries.previous(anchor)

    /** The stop after [anchor], for "Move to next cut"; null at the last. */
    val nextStop: EditTime? get() = boundaries.next(anchor)

    /** False when there is no stop to choose, and the boundary picker is off (YouRec/EditorView.swift:721-724). */
    val canPickBoundary: Boolean get() = previousStop != null || nextStop != null

    /** The scopes the Move bar offers: the others would move nothing more than a narrower one ([TimelineMoveChoices.available]). */
    val scopes: List<TimelineMoveScope> get() = TimelineMoveScope.entries.filter(choices::available)

    /** How long the timeline is drawn while moving: [viewportEnd] once a stop was chosen, else the timeline Move measures on. YouRec/EditorTimeline.swift:24. */
    val viewportDuration: EditTime get() = viewportEnd ?: base.duration

    /** True while [timeline] is still the one this session began on. */
    fun isCurrent(timeline: TrackTimeline): Boolean = timeline === original || timeline == original

    /**
     * The group moved by [offset] seconds from where it began, held so that
     * it does not go before zero, snapped when [snapping] (stationary edges,
     * bookmarks, zero and the playhead) at [scale] units a second, with the
     * held snap kept while the finger stays near; without [snapping] no snap
     * is held. EditorView.swift:1200-1215.
     */
    fun movedTo(offset: Double, snapping: Boolean = true, scale: Double = this.scale): TrackMoveSession =
        updated(EditTime.of(offset), snapping, scale, changingScope = false)

    private fun updated(asked: EditTime, snapping: Boolean, scale: Double, changingScope: Boolean): TrackMoveSession {
        val start = TrackLinking.interval(base, moving)?.start ?: return this
        val floor = EditTime.ZERO - start
        var delta = maxOf(floor, asked)
        var held: TimelineMoveSnap? = null
        if (snapping) {
            val result = TrackMove.snapped(base, moving, delta, scale, snap, listOf(playhead))
            delta = result.delta
            held = result.snap
        }
        val amount = maxOf(floor, delta)
        val moved = if (amount == offset && !changingScope) draft else TrackMove.moved(base, moving, amount)
        return copy(
            offset = amount,
            // Trying a wider group can hit time zero; going back gives the drag that was asked for.
            requested = if (changingScope) requested else amount,
            snap = held,
            draft = moved,
            viewportEnd = if (changingScope && viewportEnd != null) maxOf(original.duration, moved.duration) else viewportEnd,
        )
    }

    /** The finger moved [translation] seconds from where this drag began. EditorView.swift:602. */
    fun dragged(translation: Double, snapping: Boolean = true, scale: Double = this.scale): TrackMoveSession =
        movedTo(dragOrigin.seconds + translation, snapping, scale)

    /** The finger lifted: the next drag starts from here. Reload the preview from [draft]. EditorView.swift:602. */
    fun dragEnded(): TrackMoveSession = copy(dragOrigin = offset)

    /** The system took the gesture away: back to where this drag began, unsnapped. EditorView.swift:603. */
    fun dragCancelled(): TrackMoveSession = movedTo(dragOrigin.seconds, snapping = false)

    /**
     * The group [frames] frames of [TimelineEditing]'s 1/30 s later (earlier
     * when negative), unsnapped and held at zero, as the frame nudges of the
     * design notes (docs/EDITOR-TRACKS-DESIGN.md:106-110). The next drag
     * starts from there.
     */
    fun nudged(frames: Int): TrackMoveSession {
        if (frames == 0) return this
        return movedTo(offset.seconds + frames * FRAME, snapping = false).dragEnded()
    }

    /**
     * Moves the group of [scope] instead, by the move last asked for, held at
     * zero and unsnapped; the stops are found again for it. The same session
     * for the scope in use or one that is not offered. Reload the preview when
     * [requested] is not zero. EditorView.swift:1173-1183.
     */
    fun changingScope(scope: TimelineMoveScope): TrackMoveSession {
        if (scope == this.scope || !choices.available(scope)) return this
        val widened = copy(scope = scope, boundaries = TrackMove.boundaries(base, choices.selected, choices[scope]))
        return widened.updated(requested, snapping = false, scale = scale, changingScope = true).dragEnded()
    }

    /**
     * Puts the picked material's start on the stop [time], one of
     * [boundaries]: the group moves there unsnapped, the stop is then held
     * as a snap, and the timeline is drawn to [viewportEnd] so the stop stays
     * in reach. Seek the playhead to [time] and reload the preview. Null when
     * [time] is not a stop. EditorView.swift:1184-1198.
     */
    fun placed(time: EditTime): TrackMoveSession? {
        if (time !in boundaries.times) return null
        val moved = movedTo((time - boundaries.anchor).seconds, snapping = false).dragEnded()
        return moved.copy(snap = TimelineMoveSnap(time, boundaries.anchor), viewportEnd = maxOf(original.duration, moved.draft.duration))
    }

    /** [placed] on [previousStop]: "Move to previous cut". */
    fun toPreviousStop(): TrackMoveSession? = previousStop?.let(::placed)

    /** [placed] on [nextStop]: "Move to next cut". */
    fun toNextStop(): TrackMoveSession? = nextStop?.let(::placed)

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
        /** One frame of the nudges, in seconds. */
        const val FRAME = 1.0 / 30

        /**
         * Move begins on [selection] of [timeline], with the playhead at
         * [playhead] seconds and the timeline drawn at [scale] units a second,
         * on the "selected" scope. Null when nothing would move.
         * EditorView.swift:1162-1172.
         */
        fun begin(timeline: TrackTimeline, selection: TimelineSelection, playhead: Double, scale: Double): TrackMoveSession? {
            val scoped = TrackEditing.scoped(timeline, selection)
            if (scoped.selection.isEmpty) return null
            val choices = TrackMove.choices(scoped.timeline, scoped.selection)
            if (choices.selected.isEmpty || TrackLinking.interval(scoped.timeline, choices.selected) == null) return null
            val boundaries = TrackMove.boundaries(scoped.timeline, choices.selected, choices.selected)
            return TrackMoveSession(timeline, scoped.timeline, choices, TimelineMoveScope.Selected, boundaries, playhead, scale)
        }
    }
}
