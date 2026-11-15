package app.takes.core

/** Sets or removes the transition at a join. See [TrackTransitions.setting]. */
fun TrackTimeline.settingTransition(value: TransitionSettings?, index: Int): TrackTimeline = TrackTransitions.setting(this, value, index)

/** Removes the transition at a join and closes the timeline again. See [TrackTransitions.restoringStraightCut]. */
fun TrackTimeline.restoringStraightCut(index: Int): TrackTimeline = TrackTransitions.restoringStraightCut(this, index)

/**
 * Transitions on the stored form of the track timeline: a relation between
 * pictures by id, with the place and the fit it was given when it was set.
 *
 * The fit is worked out here and nowhere else: when a transition is set
 * ([setting]). Every other edit keeps it, with three exceptions that belong
 * to those edits and are stated there (docs/TRANSITIONS-FIT.md, section 2):
 *
 *  - the prune ([TrackTimeline.pruned]) finds the factor again when the two
 *    pictures no longer overlap as it says, and removes a transition whose
 *    pictures are gone, lack the footage, or are not where it needs them;
 *  - a change of speed scales both transitions of the clip together when
 *    they no longer fit it, and a trim that takes their footage removes them
 *    ([TrackEditing.replacingVideoContents]);
 *  - an opening and a close have one picture and stay with it: they go where
 *    it goes, are never dropped for want of footage, and give way only to a
 *    transition set on the same side of their picture.
 *
 * A join is named by its number in [TrackLayout]: join i, up to the number of
 * pictures, is the one into the picture that starts i-th (0 the opening of
 * the first, the last the close of the picture that ends last); a transition
 * that is on none of those places has a number after them.
 */
object TrackTransitions {
    private const val FIT_SLACK = 0.00000001

    /**
     * Sets [value] at join [index], or removes what is there when null.
     *
     *  1. The relation between exactly the join's two pictures is removed.
     *     With null that is all: every picture stays where it is
     *     ([restoringStraightCut] closes the timeline again).
     *  2. A picture has one transition in and one out: every relation leaving
     *     the outgoing picture or entering the incoming one is removed, so a
     *     transition between two pictures replaces an opening or a close on
     *     those sides.
     *  3. The settings are kept as entered and fitted from their full length
     *     into the footage the transitions on the far sides of the two
     *     pictures leave free. Those keep their fit and are never shortened
     *     to make room, so the order transitions are set in matters. Under
     *     one frame of picture nothing is added, and the removals of 1 and 2
     *     stay.
     *  4. The outgoing picture does not move. The transition starts where its
     *     footage ends; an opening starts where its picture does, held at
     *     zero so sound that leads it is reserved there. The incoming picture
     *     moves to where the transition wants it, with every linked picture
     *     that starts at or after it, their sounds, and the relations wholly
     *     among them.
     *  5. A placement of exactly the two pictures goes: they are joined now,
     *     not arranged.
     *
     * The same timeline when the join does not exist, when it is a place
     * between pictures that do not meet, or when [value] is out of range
     * (the original does not check the last; a timeline with such settings
     * is one a project refuses).
     *
     * YouRec/Transitions.swift:335-386.
     */
    fun setting(timeline: TrackTimeline, value: TransitionSettings?, index: Int): TrackTimeline {
        val boundary = TrackLayout.of(timeline).joins.firstOrNull { it.index == index }?.takeIf { it.selectable } ?: return timeline
        if (value != null && !value.valid) return timeline
        val left = boundary.outgoingID?.let { id -> timeline.video.firstOrNull { it.id == id } }
        val right = boundary.incomingID?.let { id -> timeline.video.firstOrNull { it.id == id } }
        var edit = timeline.copy(transitions = timeline.transitions.filter { !(it.outgoing == left?.id && it.incoming == right?.id) }).pruned()
        if (value == null) return edit
        // One transition in and one out for each picture: a new one takes the place of an opening or a close on the same side.
        edit = edit.copy(transitions = edit.transitions.filter { relation -> !((left != null && relation.outgoing == left.id) || (right != null && relation.incoming == right.id)) })
        val settings = value.copy(nextClipID = right?.id)
        // What the neighbouring transitions hold is not free: two stretches must never cross.
        val incoming = left?.let { item -> edit.transitions.firstOrNull { it.incoming == item.id } }
        val outgoing = right?.let { item -> edit.transitions.firstOrNull { it.outgoing == item.id } }
        val leftFree = maxOf(0.0, (left?.mapping?.duration?.seconds ?: EditTime.LIMIT) - (incoming?.timing?.rightCost ?: 0.0))
        val rightFree = maxOf(0.0, (right?.mapping?.duration?.seconds ?: EditTime.LIMIT) - (outgoing?.timing?.leftCost ?: 0.0))
        val edge = if (left == null) -1 else if (right == null) 1 else 0
        fun fits(factor: Double): Boolean {
            val t = TransitionTiming.of(settings, factor, edge)
            return t.leftCost <= leftFree + FIT_SLACK && t.rightCost <= rightFree + FIT_SLACK
        }
        var factor = 1.0
        if (!fits(1.0)) {
            var lo = 0.0
            var hi = 1.0
            repeat(TransitionLayout.FIT_STEPS) {
                val mid = (lo + hi) / 2
                if (fits(mid)) lo = mid else hi = mid
            }
            factor = lo
        }
        val t = TransitionTiming.of(settings, factor, edge)
        // The original answers here without pruning, which leaves the stretch of a fade the slot rule just removed.
        if (t.videoDuration < TrackEditing.FRAME - 0.000001) return edit.pruned()
        // An opening whose sound leads the picture keeps that lead at zero instead of moving the first picture before it.
        val origin = left?.let { it.end - EditTime.of(t.leftEnd) } ?: maxOf(EditTime.ZERO, (right?.start ?: EditTime.ZERO) - EditTime.of(t.rightStart))
        if (right != null) {
            val delta = origin + EditTime.of(t.rightStart) - right.start
            edit = shiftingTail(edit, shiftTargets(edit, right, left), delta)
        }
        val participants = setOfNotNull(left?.id, right?.id)
        edit = edit.copy(
            transitions = edit.transitions + TimelineTransition(left?.id, right?.id, settings, origin, factor),
            placements = edit.placements.filter { it.participants != participants },
        )
        return edit.pruned().synchronizingFollowers()
    }

    /**
     * Removes the transition at join [index] and brings the incoming picture,
     * and what moves with it, back to where the outgoing one ends: the plain
     * cut the two had, with all the footage of both. For a join without both
     * pictures (an opening, a close) it only removes.
     * YouRec/Transitions.swift:387-396.
     */
    fun restoringStraightCut(timeline: TrackTimeline, index: Int): TrackTimeline {
        val join = TrackLayout.of(timeline).joins.firstOrNull { it.index == index }
        val left = join?.outgoingID?.let { id -> timeline.video.firstOrNull { it.id == id } }
        val right = join?.incomingID?.let { id -> timeline.video.firstOrNull { it.id == id } }
        val removed = setting(timeline, null, index)
        if (left == null || right == null) return removed
        return shiftingTail(removed, shiftTargets(removed, right, left), left.end - right.start).pruned()
    }

    /**
     * Removes the transition [id], wherever its pictures are, restoring the
     * plain cut ([restoringStraightCut]). The same timeline when there is no
     * such transition. This is the editor's Remove
     * (YouRec/VideoTools.swift:522-525).
     */
    fun removing(timeline: TrackTimeline, id: String): TrackTimeline {
        val join = TrackLayout.of(timeline).joins.firstOrNull { it.transition?.id == id } ?: return timeline
        return restoringStraightCut(timeline, join.index)
    }

    /**
     * What moves when the picture [right] is put somewhere else by its
     * transition: itself, every linked picture that starts at or after it,
     * the sounds that follow those, and linked sound without a picture from
     * there on. Never [left], the picture the transition leads out of, nor
     * the sound that follows it. YouRec/Transitions.swift:400-410.
     */
    private fun shiftTargets(timeline: TrackTimeline, right: VideoTimelineItem, left: VideoTimelineItem?): Set<String> {
        val linked = timeline.links.flatMapTo(HashSet()) { it.members }
        val moving = timeline.video.filter { it.id != left?.id && (it.id == right.id || (it.id in linked && it.start >= right.start)) }.mapTo(HashSet()) { it.id }
        val pictures = HashSet(moving)
        for (sound in timeline.audio) {
            if (left != null && sound.followsVideo == left.id) continue
            val follows = sound.followsVideo
            if (if (follows != null) follows in pictures else (sound.id in linked && sound.start >= right.start)) moving += sound.id
        }
        return moving
    }

    /** The items [moving] put later by [delta], with the transitions that are wholly among them. YouRec/Transitions.swift:411-418. */
    private fun shiftingTail(timeline: TrackTimeline, moving: Set<String>, delta: EditTime): TrackTimeline {
        if (delta == EditTime.ZERO || moving.isEmpty()) return timeline
        return timeline.copy(
            video = timeline.video.map { if (it.id in moving) it.copy(start = it.start + delta) else it },
            audio = timeline.audio.map { if (it.id in moving) it.copy(start = it.start + delta) else it },
            transitions = timeline.transitions.map { relation ->
                if (listOfNotNull(relation.outgoing, relation.incoming).all { it in moving }) relation.copy(origin = relation.origin + delta) else relation
            },
        )
    }
}
