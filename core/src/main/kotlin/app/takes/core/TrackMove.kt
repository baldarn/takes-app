package app.takes.core

/**
 * What Move takes along with the selection: only it, also what joins it on
 * the left, on the right, or both. YouRec/TrackTimeline.swift:241-246.
 */
enum class TimelineMoveScope {
    Selected, Left, Right, Both;

    val includesLeft: Boolean get() = this == Left || this == Both
    val includesRight: Boolean get() = this == Right || this == Both
}

/**
 * What each [TimelineMoveScope] moves, found once when Move begins.
 * YouRec/TrackTimeline.swift:253-276.
 */
data class TimelineMoveChoices(
    val selected: TimelineSelection = TimelineSelection(),
    val left: TimelineSelection = TimelineSelection(),
    val right: TimelineSelection = TimelineSelection(),
    val both: TimelineSelection = TimelineSelection(),
) {
    operator fun get(scope: TimelineMoveScope): TimelineSelection = when (scope) {
        TimelineMoveScope.Selected -> selected
        TimelineMoveScope.Left -> left
        TimelineMoveScope.Right -> right
        TimelineMoveScope.Both -> both
    }

    /** False for a scope that would move nothing more than a narrower one does. */
    fun available(scope: TimelineMoveScope): Boolean = when (scope) {
        TimelineMoveScope.Selected -> true
        TimelineMoveScope.Left -> left != selected
        TimelineMoveScope.Right -> right != selected
        TimelineMoveScope.Both -> both != left && both != right
    }
}

/**
 * The stops a moved group can be put on with the step buttons. [anchor] is
 * where the material that was picked begins, and stays the point that is
 * aligned even when the move takes neighbours along; [times] are the places
 * it can go, in order. They are found on the timeline as it was when Move
 * began, so moving the draft never makes another stop.
 * YouRec/TrackTimeline.swift:298-330.
 */
data class TimelineMoveBoundaries(val anchor: EditTime = EditTime.ZERO, val times: List<EditTime> = emptyList()) {
    fun previous(time: EditTime): EditTime? = times.lastOrNull { it < time }
    fun next(time: EditTime): EditTime? = times.firstOrNull { it > time }
}

/**
 * A dashed line of the boundary picker: the stops [times] that lie within
 * [TrackMove.MARKER_GROUPING] points of the first of them on screen, drawn
 * from [firstX] to [lastX]. YouRec/EditorTimeline.swift:77-100.
 */
data class TimelineMoveMarker(val firstX: Double, val lastX: Double, val times: List<EditTime>) {
    val center: Double get() = (firstX + lastX) / 2
}

/** An edge of the moved group, at [edge] before the move, held on the point [target]. YouRec/TrackTimeline.swift:332-335. */
data class TimelineMoveSnap(val target: EditTime, val edge: EditTime)

/** How far the group moves, and the snap holding it, to pass as the latch of the next call. YouRec/TrackTimeline.swift:336-339. */
data class TimelineMoveResult(val delta: EditTime, val snap: TimelineMoveSnap? = null)

/** See [TrackMove.choices]. */
fun TrackTimeline.moveChoices(selected: TimelineSelection): TimelineMoveChoices = TrackMove.choices(this, selected)

/** See [TrackMove.moved]. */
fun TrackTimeline.moved(selection: TimelineSelection, delta: EditTime): TrackTimeline = TrackMove.moved(this, selection, delta)

/** See [TrackMove.snapped]. */
fun TrackTimeline.snappedMove(
    selection: TimelineSelection,
    requested: EditTime,
    scale: Double,
    latch: TimelineMoveSnap? = null,
    extra: List<Double> = emptyList(),
): TimelineMoveResult = TrackMove.snapped(this, selection, requested, scale, latch, extra)

/** See [TrackMove.snapTarget]. */
fun TrackTimeline.snapTarget(time: Double, scale: Double, excluding: Set<String> = emptySet(), extra: List<Double> = emptyList()): Double? =
    TrackMove.snapTarget(this, time, scale, excluding, extra)

/**
 * Move mode on the track timeline: what moves together, where it can go,
 * and the move itself.
 *
 * A move shifts a group of items by one offset and changes nothing else: it
 * never trims, overwrites or pushes another item. Moving onto other pictures
 * makes an overlap, with the moved pictures in front.
 *
 * YouRec/TrackTimeline.swift:640-742, :301-330, :1470-1528.
 */
object TrackMove {
    /** An item at a cut, with the occurrence it is a piece of. */
    data class CutNeighbor(val id: String, val occurrence: String)

    /**
     * Who continues into whom at one cut, where several items end and
     * several begin on the same tick (on different layers). The two halves
     * of one occurrence are matched first; what is left is paired in the
     * order given. YouRec/TrackTimeline.swift:640-653.
     */
    fun neighbors(outgoing: List<CutNeighbor>, incoming: List<CutNeighbor>): List<Pair<String, String>> {
        val available = incoming.toMutableList()
        val unmatched = ArrayList<CutNeighbor>()
        val pairs = ArrayList<Pair<String, String>>()
        for (part in outgoing) {
            val index = available.indexOfFirst { it.occurrence == part.occurrence }
            if (index >= 0) pairs += part.id to available.removeAt(index).id else unmatched += part
        }
        for ((left, right) in unmatched.zip(available)) pairs += left.id to right.id
        return pairs
    }

    /**
     * What Move can take along with [selected], for each scope.
     *
     * The selection always moves with what it is linked to. To the left and
     * to the right it recruits its neighbours, and theirs in turn: the
     * picture that ends on the tick this one begins (or the other way), the
     * picture a transition joins it to, and, for a sound that is in no link
     * with a picture, the sound that meets it. Every recruit brings its own
     * link. A gap of one tick stops the walk; a picture that merely overlaps
     * is another layer and is not a neighbour; pictures do not recruit sounds
     * that happen to be cut at the same time, nor the reverse.
     *
     * YouRec/TrackTimeline.swift:655-699.
     */
    fun choices(timeline: TrackTimeline, selected: TimelineSelection): TimelineMoveChoices {
        val seed = TrackLinking.expanded(timeline, selected)
        val pictures = timeline.video.mapTo(HashSet()) { it.id }
        val sounds = timeline.audio.mapTo(HashSet()) { it.id }
        val linkedToPicture = HashSet<String>()
        val groupByMember = HashMap<String, TimelineLink>()
        for (group in timeline.links) {
            if (group.members.any { it in pictures }) linkedToPicture += group.members
            for (member in group.members) groupByMember[member] = group
        }
        // A transition owns the cut on that side of each of its two pictures.
        val previous = HashMap<String, String>()
        val next = HashMap<String, String>()
        fun join(a: String, b: String) {
            previous[b] = a
            next[a] = b
        }
        for (relation in timeline.transitions) {
            val a = relation.outgoing ?: continue
            val b = relation.incoming ?: continue
            join(a, b)
        }
        // A cut between pictures and a cut between independent sounds on the same tick are different joins.
        val starts = LinkedHashMap<Pair<Boolean, EditTime>, MutableList<CutNeighbor>>()
        val ends = LinkedHashMap<Pair<Boolean, EditTime>, MutableList<CutNeighbor>>()
        for (picture in timeline.video.sortedWith(VideoTimelineItem.backToFront)) {
            val part = CutNeighbor(picture.id, picture.occurrenceID)
            if (previous[picture.id] == null) starts.getOrPut(false to picture.start) { mutableListOf() } += part
            if (next[picture.id] == null) ends.getOrPut(false to picture.end) { mutableListOf() } += part
        }
        for (sound in timeline.audio) {
            if (sound.id in linkedToPicture) continue
            val part = CutNeighbor(sound.id, sound.occurrenceID)
            starts.getOrPut(true to sound.start) { mutableListOf() } += part
            ends.getOrPut(true to sound.end) { mutableListOf() } += part
        }
        for ((cut, outgoing) in ends) {
            for ((a, b) in neighbors(outgoing, starts[cut].orEmpty())) join(a, b)
        }
        fun following(neighbors: Map<String, String>): TimelineSelection {
            val members = LinkedHashSet(seed.ids)
            val pending = ArrayDeque(members)
            val visited = HashSet<String>()
            fun include(id: String) {
                if (members.add(id)) pending.addLast(id)
            }
            while (true) {
                val id = pending.removeLastOrNull() ?: break
                groupByMember[id]?.let { group -> if (visited.add(group.id)) group.members.forEach(::include) }
                neighbors[id]?.let(::include)
            }
            return TimelineSelection(members.filterTo(LinkedHashSet()) { it in pictures }, members.filterTo(LinkedHashSet()) { it in sounds })
        }
        val left = following(previous)
        val right = following(next)
        return TimelineMoveChoices(seed, left, right, TimelineSelection(left.video + right.video, left.audio + right.audio))
    }

    /**
     * [selection], with what it is linked to, moved by [delta]. All of it
     * moves by one offset, held so that its earliest item does not go before
     * zero; items are never held one by one, which would undo their sync. A
     * range of the selection is not looked at: cut the items first.
     *
     * A transition moves with its pictures when all of them move. Pictures
     * moved onto others they did not overlap before come to the front
     * ([promotingNewOverlaps]), and take their windows along
     * ([TrackPlacements.retainingAcrossNewOverlaps]). What no longer holds is
     * left to the prune: a transition one of whose pictures stayed behind, a
     * placement whose pictures no longer play together.
     *
     * YouRec/TrackTimeline.swift:701-725.
     */
    fun moved(timeline: TrackTimeline, selection: TimelineSelection, delta: EditTime): TrackTimeline {
        val chosen = TrackLinking.expanded(timeline, selection)
        val range = TrackLinking.interval(timeline, TimelineSelection(chosen.video, chosen.audio)) ?: return timeline
        val amount = maxOf(EditTime.ZERO - range.start, delta)
        if (amount == EditTime.ZERO) return timeline
        val video = promotingNewOverlaps(
            timeline.video.map { if (it.id in chosen.video) it.copy(start = it.start + amount) else it },
            chosen.video, timeline.video,
        )
        val audio = timeline.audio.map { if (it.id in chosen.audio) it.copy(start = it.start + amount) else it }
        fun moves(relation: TimelineTransition) = listOfNotNull(relation.outgoing, relation.incoming).all { it in chosen.video }
        val owners = HashMap<String, TimelineTransition>()
        for (relation in timeline.transitions) owners.putIfAbsent(relation.id, relation)
        val shifted = timeline.copy(
            video = video,
            audio = audio,
            transitions = timeline.transitions.map { if (moves(it)) it.copy(origin = it.origin + amount) else it },
            generated = timeline.generated.map { region ->
                if (owners[region.owner]?.let(::moves) == true) region.copy(interval = EditInterval(region.interval.start + amount, region.interval.end + amount)) else region
            },
        )
        return TrackPlacements.retainingAcrossNewOverlaps(shifted, chosen.video).pruned()
    }

    /**
     * [video] with the pictures [selected] brought to the front when one of
     * them now overlaps a picture, not among them, that it did not overlap in
     * [previous]. They keep their order among themselves. Changing an
     * overlap that was already there swaps nothing: adjusting the timing of
     * a covered picture must not bring it forward.
     * YouRec/TrackTimeline.swift:726-739.
     */
    fun promotingNewOverlaps(video: List<VideoTimelineItem>, selected: Set<String>, previous: List<VideoTimelineItem>): List<VideoTimelineItem> {
        val old = HashMap<String, EditInterval>()
        for (item in previous) old.putIfAbsent(item.id, item.interval)
        val stationary = video.filter { it.id !in selected }
        val fresh = video.any { item ->
            item.id in selected && stationary.any { other ->
                item.interval.intersection(other.interval) != null && old[item.id]?.intersection(old[other.id] ?: other.interval) == null
            }
        }
        if (!fresh) return video
        val front = (video.maxOfOrNull { it.layer } ?: 0) + 1
        val rank = video.filter { it.id in selected }.sortedWith(VideoTimelineItem.backToFront).withIndex().associate { it.value.id to it.index }
        return video.map { item -> rank[item.id]?.let { item.copy(layer = front + it) } ?: item }
    }

    /**
     * The stops for moving [moving] when [picked] is what was chosen.
     *
     * Zero, where the picked material begins, the end of the timeline, and
     * both edges of every item that does not move, as it is drawn: two
     * pictures joined by a transition meet at its join, not at the ends of
     * their handles. The join of a transition is a stop too, unless both its
     * pictures move. Stops that would put the group before zero or past a
     * week are left out.
     */
    fun boundaries(timeline: TrackTimeline, picked: TimelineSelection, moving: TimelineSelection): TimelineMoveBoundaries {
        val original = TrackLinking.interval(timeline, picked) ?: return TimelineMoveBoundaries()
        val group = TrackLinking.interval(timeline, moving) ?: return TimelineMoveBoundaries()
        val members = moving.ids
        val stops = sortedSetOf(EditTime.ZERO, original.start, timeline.duration)
        // The rows of the drawn timeline already fold a transition into a join.
        for (lane in timeline.lanes()) {
            if (lane.id in members) continue
            stops += lane.interval.start
            stops += lane.interval.end
        }
        if (timeline.transitions.isNotEmpty()) {
            for (join in TrackLayout.of(timeline).joins) {
                if (join.transition == null) continue
                if (listOfNotNull(join.outgoingID, join.incomingID).any { it !in members }) stops += EditTime.of(join.time)
            }
        }
        val earliest = original.start - group.start
        val latest = original.start + EditTime.of(EditTime.LIMIT) - group.end
        return TimelineMoveBoundaries(original.start, stops.filter { earliest <= it && it <= latest })
    }

    /**
     * What things snap to, in order and each once: both edges of every
     * picture and sound not among [excluding], every bookmark that is not
     * attached to one of those, zero, and [extra] seconds (the playhead).
     * Nothing snaps to itself. YouRec/TrackTimeline.swift:1470-1487.
     */
    fun snapPoints(timeline: TrackTimeline, excluding: Set<String> = emptySet(), extra: List<Double> = emptyList()): List<EditTime> {
        val points = sortedSetOf(EditTime.ZERO)
        for (item in timeline.video) if (item.id !in excluding) { points += item.start; points += item.end }
        for (item in timeline.audio) if (item.id !in excluding) { points += item.start; points += item.end }
        for (resolved in timeline.resolvedBookmarks) {
            val owner = if (excluding.isEmpty()) null else TrackBookmarks.owner(timeline, resolved.bookmark)
            if (owner == null || owner !in excluding) points += resolved.time
        }
        for (time in extra) points += EditTime.of(time)
        return points.toList()
    }

    /** The snap point nearest to [time] seconds when it is within [TimelineSnapping.PLAYHEAD_REACH] on screen at [scale] units a second. */
    fun snapTarget(timeline: TrackTimeline, time: Double, scale: Double, excluding: Set<String> = emptySet(), extra: List<Double> = emptyList()): Double? =
        TimelineSnapping.target(time, scale, snapPoints(timeline, excluding, extra).map { it.seconds })

    /** [time], or the point it snaps to. */
    fun snap(timeline: TrackTimeline, time: Double, scale: Double, excluding: Set<String> = emptySet(), extra: List<Double> = emptyList()): Double =
        snapTarget(timeline, time, scale, excluding, extra) ?: time

    /**
     * A drag of [selection], with what it is linked to, asked to move by
     * [requested]: how far it goes once snapping has had its say. Every
     * edge of every moved item can take hold of a point; the distances and
     * the held snap are [TimelineSnapping.move]'s. Pass the previous result's
     * snap as [latch]. YouRec/TrackTimeline.swift:1492-1525.
     */
    fun snapped(
        timeline: TrackTimeline,
        selection: TimelineSelection,
        requested: EditTime,
        scale: Double,
        latch: TimelineMoveSnap? = null,
        extra: List<Double> = emptyList(),
    ): TimelineMoveResult {
        val chosen = TrackLinking.expanded(timeline, selection)
        val edges = ArrayList<Double>()
        for (item in timeline.video) if (item.id in chosen.video) { edges += item.start.seconds; edges += item.end.seconds }
        for (item in timeline.audio) if (item.id in chosen.audio) { edges += item.start.seconds; edges += item.end.seconds }
        if (edges.isEmpty()) return TimelineMoveResult(EditTime.ZERO)
        val targets = snapPoints(timeline, chosen.ids, extra).map { it.seconds }
        val result = TimelineSnapping.move(edges, requested.seconds, scale, targets, latch?.let { TimelineSnapping.MoveSnap(it.target.seconds, it.edge.seconds) })
        return TimelineMoveResult(EditTime.of(result.delta), result.snap?.let { TimelineMoveSnap(EditTime.of(it.target), EditTime.of(it.edge)) })
    }

    // MARK: The boundary picker

    /** Stops closer than this on screen, in points, are one marker. YouRec/EditorTimeline.swift:94. */
    const val MARKER_GROUPING = 22.0

    /** How far beside a marker a tap still picks it, in points. YouRec/EditorTimeline.swift:548. */
    const val MARKER_REACH = 22.0

    /**
     * The markers of [times] (in order, as [TimelineMoveBoundaries.times])
     * that are on a screen [width] points wide, where [x] gives a time's
     * place on it. Stops that would be drawn within [MARKER_GROUPING] of the
     * first of a marker join it. YouRec/EditorTimeline.swift:83-99.
     */
    fun markers(times: List<EditTime>, width: Double, x: (EditTime) -> Double): List<TimelineMoveMarker> {
        val result = ArrayList<TimelineMoveMarker>()
        for (time in times) {
            val at = x(time)
            if (at < 0) continue
            if (at > width) break
            val last = result.lastOrNull()
            if (last != null && at - last.firstX < MARKER_GROUPING) result[result.lastIndex] = last.copy(lastX = at, times = last.times + time)
            else result += TimelineMoveMarker(at, at, listOf(time))
        }
        return result
    }

    /**
     * The stops a tap at [x] points picks: those of the marker whose middle
     * is nearest, when the tap is within [MARKER_REACH] of it. One stop is
     * placed at once ([TrackMoveSession.placed]); several are offered in a
     * list, "Choose a start time". Null when the tap is on no marker.
     * YouRec/EditorTimeline.swift:546-551, YouRec/EditorView.swift:591-595.
     */
    fun picked(markers: List<TimelineMoveMarker>, x: Double): List<EditTime>? {
        val closest = markers.minByOrNull { kotlin.math.abs(it.center - x) } ?: return null
        return closest.times.takeIf { x >= closest.firstX - MARKER_REACH && x <= closest.lastX + MARKER_REACH }
    }

    /**
     * The label of the stop [time] among [neighbors] when the editor's
     * ordinary time would not tell it apart: another of them rounds to the
     * same frame of 1/30 s. Then minutes and seconds to the microsecond,
     * "0:04.000002"; null when the ordinary label will do.
     * YouRec/EditorTimeline.swift:1059-1067.
     */
    fun preciseLabel(time: EditTime, neighbors: List<EditTime>): String? {
        val perFrame = EditTime.SCALE / 30
        val frame = (time.ticks + perFrame / 2) / perFrame
        if (neighbors.none { it != time && (it.ticks + perFrame / 2) / perFrame == frame }) return null
        val minutes = time.ticks / (60 * EditTime.SCALE)
        val seconds = (time.ticks % (60 * EditTime.SCALE)).toDouble() / EditTime.SCALE
        return String.format(java.util.Locale.ROOT, "%d:%09.6f", minutes, seconds)
    }
}
