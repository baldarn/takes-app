package app.takes.core

/**
 * What an edit of the track timeline applies to: pictures, sounds, and
 * optionally a [range] of the timeline that limits it to the part of them
 * inside it. The ids are explicit: a range never means "everything there".
 * YouRec/TrackTimeline.swift:228-239.
 */
data class TimelineSelection(
    val video: Set<String> = emptySet(),
    val audio: Set<String> = emptySet(),
    val range: EditInterval? = null,
) {
    val ids: Set<String> get() = video + audio
    val isEmpty: Boolean get() = video.isEmpty() && audio.isEmpty()
    val title: String get() = if (video.isEmpty()) "Audio" else if (audio.isEmpty()) "Video" else "Video + audio"

    /** "2 videos + 1 audio part". */
    val countTitle: String
        get() = listOfNotNull(
            if (video.isEmpty()) null else "${video.size} video${if (video.size == 1) "" else "s"}",
            if (audio.isEmpty()) null else "${audio.size} audio part${if (audio.size == 1) "" else "s"}",
        ).joinToString(" + ")
}

/** A timeline with the items of it that an edit made, kept or selected. */
data class SelectedTimeline(val timeline: TrackTimeline, val selection: TimelineSelection)

/** Why a selection cannot be linked, with the original's words. YouRec/TrackTimeline.swift:278-289. */
enum class TimelineLinkError(val message: String) {
    TooFew("Tap a part to select it, then long-press other video or audio parts to add them. Long-press again to remove a part from the selection."),
    NonContiguous("Non-contiguous parts selected. Each video or audio sequence must meet edge to edge, without gaps or overlaps. Move the parts together with Snap, then try Link again."),
    NoIntersection("The selected video and audio do not overlap in time. Move them to a shared interval before linking."),

    /** Not a fault: the picture and the sound cover different times, and linking only what they share has to be asked for. */
    ConfirmationRequired("Video and audio cover different times. Link only their intersection?"),
    TransitionBoundary("The shared time starts or ends inside a transition. Adjust or remove that transition before linking the intersection."),
}

/** What linking answers: the result, or why not. The original throws the error. */
sealed interface TimelineLinkOutcome<out T> {
    data class Done<T>(val value: T) : TimelineLinkOutcome<T>
    data class Refused(val error: TimelineLinkError) : TimelineLinkOutcome<Nothing>

    val valueOrNull: T? get() = (this as? Done<T>)?.value
    val errorOrNull: TimelineLinkError? get() = (this as? Refused)?.error
}

/**
 * What a link would join: the [selection] with everything it is already
 * linked to, the stretch its pictures cover and the stretch its sounds cover.
 * When both are there and differ, only what they share can be linked, and
 * that has to be confirmed. YouRec/TrackTimeline.swift:290-296.
 */
data class TimelineLinkPlan(val selection: TimelineSelection, val video: EditInterval?, val audio: EditInterval?) {
    val needsIntersection: Boolean get() = video != null && audio != null && video != audio
    val intersection: EditInterval? get() = if (video != null && audio != null) video.intersection(audio) else null
}

/** The selection with everything it is linked to. See [TrackLinking.expanded]. */
fun TrackTimeline.expanded(selection: TimelineSelection): TimelineSelection = TrackLinking.expanded(this, selection)

/** The item [id] with what it is linked to. See [TrackLinking.selection]. */
fun TrackTimeline.selection(id: String, linked: Boolean = true, range: EditInterval? = null): TimelineSelection = TrackLinking.selection(this, id, linked, range)

/** Those of [ids] that are on the timeline, with what they are linked to. */
fun TrackTimeline.selection(ids: Set<String>): TimelineSelection = TrackLinking.selection(this, ids)

/** Every picture and every sound. */
fun TrackTimeline.entireSelection(range: EditInterval? = null): TimelineSelection = TrackLinking.entireSelection(this, range)

/** The stretch [selection] covers. See [TrackLinking.interval]. */
fun TrackTimeline.interval(selection: TimelineSelection): EditInterval? = TrackLinking.interval(this, selection)

/** See [TrackLinking.unlinked]. */
fun TrackTimeline.unlinked(selection: TimelineSelection): TrackTimeline = TrackLinking.unlinked(this, selection)

/** See [TrackLinking.linkPlan]. */
fun TrackTimeline.linkPlan(selection: TimelineSelection): TimelineLinkOutcome<TimelineLinkPlan> = TrackLinking.linkPlan(this, selection)

/** See [TrackLinking.joining]. */
fun TrackTimeline.joining(selection: TimelineSelection, intersectionOnly: Boolean = false): TimelineLinkOutcome<SelectedTimeline> =
    TrackLinking.joining(this, selection, intersectionOnly)

/** See [TrackLinking.syncOffset]. */
fun TrackTimeline.syncOffset(audio: String): EditTime? = TrackLinking.syncOffset(this, audio)

/** See [TrackLinking.restoringSync]. */
fun TrackTimeline.restoringSync(audio: String): TrackTimeline = TrackLinking.restoringSync(this, audio)

/**
 * Selecting, linking and unlinking on the track timeline, and the repair
 * that goes with them: Restore sync.
 *
 * A link records which items are selected, moved and deleted together, and
 * nothing else: it does not say that a sound is in sync with a picture, and
 * making or removing one moves nothing and changes no sample. Every function
 * answers a new timeline that the prune leaves as it is.
 *
 * YouRec/TrackTimeline.swift:540-639 and :1605-1615.
 */
object TrackLinking {
    // MARK: Selection

    /** [selection] with every item that shares a link with one of its items. The range is kept. */
    fun expanded(timeline: TrackTimeline, selection: TimelineSelection): TimelineSelection {
        if (timeline.links.isEmpty()) return selection
        val ids = selection.ids
        val members = HashSet<String>()
        for (group in timeline.links) if (group.members.any { it in ids }) members += group.members
        if (members.isEmpty()) return selection
        return selection.copy(
            video = selection.video + timeline.video.filter { it.id in members }.map { it.id },
            audio = selection.audio + timeline.audio.filter { it.id in members }.map { it.id },
        )
    }

    /** The item [id] with the members of its link, or alone when [linked] is false. Ids that are not on the timeline are left out. */
    fun selection(timeline: TrackTimeline, id: String, linked: Boolean = true, range: EditInterval? = null): TimelineSelection {
        val members = (if (linked) timeline.links.firstOrNull { id in it.members }?.members else null) ?: setOf(id)
        return TimelineSelection(
            timeline.video.filter { it.id in members }.mapTo(LinkedHashSet()) { it.id },
            timeline.audio.filter { it.id in members }.mapTo(LinkedHashSet()) { it.id },
            range,
        )
    }

    /** The pictures and sounds that play one of the [sources]. */
    fun items(timeline: TrackTimeline, sources: Set<String>): TimelineSelection = TimelineSelection(
        timeline.video.filter { it.content.sourceID in sources }.mapTo(LinkedHashSet()) { it.id },
        timeline.audio.filter { it.sourceID in sources }.mapTo(LinkedHashSet()) { it.id },
    )

    fun entireSelection(timeline: TrackTimeline, range: EditInterval? = null): TimelineSelection =
        TimelineSelection(timeline.video.mapTo(LinkedHashSet()) { it.id }, timeline.audio.mapTo(LinkedHashSet()) { it.id }, range)

    /** Those of [ids] that are pictures or sounds of the timeline, with what they are linked to. */
    fun selection(timeline: TrackTimeline, ids: Set<String>): TimelineSelection = expanded(
        timeline,
        TimelineSelection(
            timeline.video.filter { it.id in ids }.mapTo(LinkedHashSet()) { it.id },
            timeline.audio.filter { it.id in ids }.mapTo(LinkedHashSet()) { it.id },
        ),
    )

    /**
     * A long press on [id] while [selected] is chosen: its whole link is
     * added, or taken out when all of it was already there.
     */
    fun toggling(timeline: TrackTimeline, id: String, selected: Set<String>): Set<String> {
        val group = selection(timeline, id).ids
        return if (selected.containsAll(group)) selected - group else selected + group
    }

    /** True when [selection] is exactly the members of one link. */
    fun isSingleLink(timeline: TrackTimeline, selection: TimelineSelection): Boolean {
        val ids = selection.ids
        return timeline.links.any { it.members == ids }
    }

    /** The range of [selection] when it has one; otherwise from the first start to the last end of its items. Null for no items. */
    fun interval(timeline: TrackTimeline, selection: TimelineSelection): EditInterval? {
        selection.range?.let { return it }
        var start: EditTime? = null
        var end: EditTime? = null
        fun take(interval: EditInterval) {
            start = start?.let { minOf(it, interval.start) } ?: interval.start
            end = end?.let { maxOf(it, interval.end) } ?: interval.end
        }
        for (item in timeline.video) if (item.id in selection.video) take(item.interval)
        for (item in timeline.audio) if (item.id in selection.audio) take(item.interval)
        return EditInterval(start ?: return null, end ?: return null)
    }

    // MARK: Unlink and Link

    /**
     * Removes every link that holds an item of [selection]; the sounds of
     * those links stop following their pictures. No timing, sample or setting
     * changes. YouRec/TrackTimeline.swift:575-579.
     */
    fun unlinked(timeline: TrackTimeline, selection: TimelineSelection): TrackTimeline {
        val ids = selection.ids
        val (removed, kept) = timeline.links.partition { link -> link.members.any { it in ids } }
        if (removed.isEmpty()) return timeline
        val members = removed.flatMapTo(HashSet()) { it.members }
        return timeline.copy(
            audio = timeline.audio.map { if (it.id in members && it.followsVideo != null) it.copy(followsVideo = null) else it },
            links = kept,
        )
    }

    private class Refusal(val error: TimelineLinkError) : RuntimeException(null, null, false, false)

    /**
     * What linking [selection] would join, or why it cannot be linked.
     *
     * The pictures must be one unbroken run, each ending on the tick the
     * next begins, and so must the sounds; pictures that overlap count as a
     * run only across a transition from one into the other. Then the two
     * stretches are compared: when they differ they must at least share some
     * time, and that shared time must not begin or end inside a transition
     * between two of the selected pictures.
     *
     * YouRec/TrackTimeline.swift:583-612.
     */
    fun linkPlan(timeline: TrackTimeline, selection: TimelineSelection): TimelineLinkOutcome<TimelineLinkPlan> =
        try {
            TimelineLinkOutcome.Done(plan(timeline, selection))
        } catch (refusal: Refusal) {
            TimelineLinkOutcome.Refused(refusal.error)
        }

    private fun plan(timeline: TrackTimeline, selection: TimelineSelection): TimelineLinkPlan {
        val chosen = expanded(timeline, selection)
        if (chosen.ids.size < 2 || chosen.range != null) throw Refusal(TimelineLinkError.TooFew)
        /** The stretch of [parts], each with the picture a transition would name, when they are one run. */
        fun chain(parts: List<Pair<String?, EditInterval>>): EditInterval? {
            val sorted = parts.sortedBy { it.second.start }
            val first = sorted.firstOrNull() ?: return null
            for (index in 0 until sorted.size - 1) {
                val (a, left) = sorted[index]
                val (b, right) = sorted[index + 1]
                if (left.end == right.start) continue
                // A transition joins two parts in sequence although its handles overlap. Stacked pictures are not a run.
                val joined = left.end > right.start && a != null && b != null && timeline.transitions.any { it.outgoing == a && it.incoming == b }
                if (!joined) throw Refusal(TimelineLinkError.NonContiguous)
            }
            return EditInterval(first.second.start, sorted.maxOf { it.second.end })
        }
        val plan = TimelineLinkPlan(
            chosen,
            chain(timeline.video.filter { it.id in chosen.video }.map { it.id to it.interval }),
            chain(timeline.audio.filter { it.id in chosen.audio }.map { it.followsVideo to it.interval }),
        )
        if (plan.needsIntersection) {
            val shared = plan.intersection ?: throw Refusal(TimelineLinkError.NoIntersection)
            for (relation in timeline.transitions) {
                val a = timeline.video.firstOrNull { it.id == relation.outgoing && it.id in chosen.video } ?: continue
                val b = timeline.video.firstOrNull { it.id == relation.incoming && it.id in chosen.video } ?: continue
                if (listOf(shared.start, shared.end).any { b.start < it && it < a.end }) throw Refusal(TimelineLinkError.TransitionBoundary)
            }
        }
        return plan
    }

    /**
     * Links [selection]: one new link holds all of it, and the links its
     * items were in lose them (and go when under two members are left).
     * Nothing moves. Where picture and sound cover different times the answer
     * is [TimelineLinkError.ConfirmationRequired] until [intersectionOnly]
     * is true; then the items are cut at the ends of the time they share and
     * only the pieces inside it are linked and selected.
     *
     * A sound that plays the same occurrence of the same source as a linked
     * picture, at the same place and with the same timing, follows that
     * picture again, as it did before it was unlinked.
     *
     * YouRec/TrackTimeline.swift:614-639.
     */
    fun joining(timeline: TrackTimeline, selection: TimelineSelection, intersectionOnly: Boolean = false): TimelineLinkOutcome<SelectedTimeline> {
        val plan = try {
            plan(timeline, selection)
        } catch (refusal: Refusal) {
            return TimelineLinkOutcome.Refused(refusal.error)
        }
        if (plan.needsIntersection && !intersectionOnly) return TimelineLinkOutcome.Refused(TimelineLinkError.ConfirmationRequired)
        val target = if (plan.needsIntersection) plan.selection.copy(range = plan.intersection) else plan.selection
        val scoped = TrackEditing.scoped(timeline, target)
        val chosen = scoped.selection
        val members = chosen.ids
        val links = scoped.timeline.links.mapNotNull { group ->
            val remaining = group.members - members
            if (remaining.size >= 2) group.copy(members = remaining) else null
        } + TimelineLink(members = members)
        val pictures = scoped.timeline.video.filter { it.id in chosen.video }
        val audio = scoped.timeline.audio.map { sound ->
            if (sound.id !in chosen.audio || sound.followsVideo != null) return@map sound
            val picture = pictures.firstOrNull {
                it.occurrenceID == sound.occurrenceID && it.content.sourceID == sound.sourceID && it.start == sound.start && it.mapping == sound.mapping
            }
            if (picture != null) sound.copy(followsVideo = picture.id) else sound
        }
        return TimelineLinkOutcome.Done(SelectedTimeline(scoped.timeline.copy(audio = audio, links = links).pruned(), chosen))
    }

    // MARK: Restore sync

    /**
     * How far the sound [audio] is from where it was recorded against its
     * picture: its start, less the place where a picture of the same
     * occurrence shows the source second the sound begins with. Positive when
     * the sound is late. Null when no picture of its occurrence shows that
     * second. YouRec/TrackTimeline.swift:1605-1610.
     */
    fun syncOffset(timeline: TrackTimeline, audio: String): EditTime? {
        val sound = timeline.audio.firstOrNull { it.id == audio } ?: return null
        val sourceTime = sound.mapping.runs.firstOrNull()?.sourceStart ?: return null
        for (picture in timeline.video) {
            if (picture.occurrenceID != sound.occurrenceID) continue
            val local = picture.mapping.local(sourceTime) ?: continue
            return sound.start - picture.start - local
        }
        return null
    }

    /**
     * Moves the sound [audio] back in sync with its picture, never before
     * zero. It is not linked again: that is asked for separately.
     * YouRec/TrackTimeline.swift:1611-1615.
     */
    fun restoringSync(timeline: TrackTimeline, audio: String): TrackTimeline {
        val offset = syncOffset(timeline, audio) ?: return timeline
        return timeline.copy(
            audio = timeline.audio.map { if (it.id == audio) it.copy(start = maxOf(EditTime.ZERO, it.start - offset)) else it },
        ).pruned()
    }
}
