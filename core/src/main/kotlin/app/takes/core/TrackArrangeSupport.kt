package app.takes.core

import kotlin.math.abs

/**
 * What the arranging operations ([TrackLinking], [TrackMove],
 * [TrackPlacements], [TrackClipboard]) need from the basic editing operations
 * and from the transitions of the track timeline, ported here so that those
 * files stand by themselves. Nothing outside the arranging operations calls
 * this; each function is to be replaced by the public one of the same meaning
 * once the basic operations are in the core, and this file deleted.
 *
 *  - [union], [videoCoverage]: YouRec/TrackTimeline.swift:828-835, YouRec/TimelineEditing.swift:36-38.
 *  - [split], [scoped]: YouRec/TrackTimeline.swift:1007-1094.
 *  - [remove]: YouRec/TrackTimeline.swift:771-827.
 *  - [insertTime]: YouRec/TrackTimeline.swift:1145-1161.
 *  - [joins]: the joins of `TimelineLayout(timeline:)`, YouRec/Transitions.swift:271-332.
 *  - [restoringStraightCut]: YouRec/Transitions.swift:335-340, :388-417, the removal only.
 */
internal object TrackArrangeSupport {
    /** [ranges] in order, with those that touch or overlap made one. Empty ones are dropped. */
    fun union(ranges: List<EditInterval>): List<EditInterval> {
        val result = ArrayList<EditInterval>()
        for (range in ranges.sortedBy { it.start }) {
            if (range.isEmpty) continue
            val last = result.lastOrNull()
            if (last != null && range.start <= last.end) result[result.lastIndex] = last.copy(end = maxOf(last.end, range.end)) else result += range
        }
        return result
    }

    /** Where the timeline has picture: its pictures and the stretches its transitions occupy. */
    fun videoCoverage(timeline: TrackTimeline): List<EditInterval> =
        union(timeline.video.map { it.interval } + timeline.generated.map { it.interval })

    /** A timeline after a cut, with the id of the later piece of each item that was cut, by the id of the item. */
    class Split(val timeline: TrackTimeline, val children: Map<String, String>)

    /**
     * Cuts the items of [selection] (with what they are linked to) that play
     * across [time]. The earlier piece keeps the item's id. Links, followers,
     * transitions and placements go with the pieces: a transition out of a
     * cut picture leaves from its later piece, a link becomes one link per
     * side, a placement is kept on both sides.
     */
    fun split(timeline: TrackTimeline, time: EditTime, selection: TimelineSelection): Split {
        val chosen = TrackLinking.expanded(timeline, selection)
        val children = LinkedHashMap<String, String>()
        val pictures = ArrayList<VideoTimelineItem>(timeline.video.size + 1)
        for (item in timeline.video) {
            val local = time - item.start
            val source = if (item.id in chosen.video && time > item.start && time < item.end) item.mapping.source(local) else null
            if (source == null) {
                pictures += item
                continue
            }
            val right = item.copy(
                content = item.content.copy(id = newID(), start = source),
                start = time,
                localOrigin = item.localOrigin + local,
                mapping = item.mapping.sliced(EditInterval(local, item.mapping.duration)),
            )
            children[item.id] = right.id
            pictures += item.copy(content = item.content.copy(end = source), mapping = item.mapping.sliced(EditInterval(EditTime.ZERO, local)))
            pictures += right
        }
        val sounds = ArrayList<AudioTimelineItem>(timeline.audio.size + 1)
        for (item in timeline.audio) {
            if (item.id !in chosen.audio || time <= item.start || time >= item.end) {
                sounds += item
                continue
            }
            val local = time - item.start
            val id = newID()
            sounds += item.sliced(EditInterval(EditTime.ZERO, local), item.id)
            sounds += item.sliced(EditInterval(local, item.mapping.duration), id)
            children[item.id] = id
        }
        for (index in sounds.indices) {
            val sound = sounds[index]
            val child = sound.followsVideo?.let { children[it] } ?: continue
            if (sound.start >= time) sounds[index] = sound.copy(followsVideo = child)
        }
        val starts = HashMap<String, EditTime>()
        for (item in pictures) starts.putIfAbsent(item.id, item.start)
        for (item in sounds) starts.putIfAbsent(item.id, item.start)
        val ids = chosen.ids
        val links = ArrayList<TimelineLink>()
        for (group in timeline.links) {
            // Cutting another lane must not undo a link.
            if (group.members.none { it in ids }) {
                links += group
                continue
            }
            val left = LinkedHashSet<String>()
            val right = LinkedHashSet<String>()
            for (id in group.members) {
                val child = children[id]
                if (child != null) {
                    left += id
                    right += child
                } else if ((starts[id] ?: EditTime.ZERO) >= time) right += id else left += id
            }
            if (left.size >= 2) links += TimelineLink(group.id, left)
            if (right.size >= 2) links += TimelineLink(if (left.isEmpty()) group.id else newID(), right)
        }
        val transitions = timeline.transitions.map { relation -> relation.outgoing?.let { children[it] }?.let { relation.copy(outgoing = it) } ?: relation }
        val placements = timeline.placements + TrackPlacements.carriedAcrossSplit(timeline.placements, children)
        return Split(timeline.copy(video = pictures, audio = sounds, links = links, transitions = transitions, placements = placements).pruned(), children)
    }

    /**
     * [selection] made exact: with a range, its items are cut at both ends of
     * the range and the answer selects the pieces inside it, with no range.
     * Without a range, the timeline as it is and the selection with what it
     * is linked to.
     */
    fun scoped(timeline: TrackTimeline, selection: TimelineSelection): SelectedTimeline {
        val chosen = TrackLinking.expanded(timeline, selection)
        val range = chosen.range ?: return SelectedTimeline(timeline, chosen)
        fun with(targets: TimelineSelection, children: Map<String, String>) = targets.copy(
            video = targets.video + children.filterKeys { it in targets.video }.values,
            audio = targets.audio + children.filterKeys { it in targets.audio }.values,
        )
        val upper = split(timeline, range.end, chosen)
        var targets = with(chosen, upper.children)
        val lower = split(upper.timeline, range.start, targets)
        targets = with(targets, lower.children)
        val result = lower.timeline
        return SelectedTimeline(
            result,
            TimelineSelection(
                result.video.filter { it.id in targets.video && it.start >= range.start && it.end <= range.end }.mapTo(LinkedHashSet()) { it.id },
                result.audio.filter { it.id in targets.audio && it.start >= range.start && it.end <= range.end }.mapTo(LinkedHashSet()) { it.id },
            ),
        )
    }

    /** A timeline after a deletion, and where the playhead goes; null when none was given. */
    class Removal(val timeline: TrackTimeline, val position: EditTime?)

    /**
     * Deletes the items of [selection]. A link deleted whole, picture and
     * sound, closes the time its pictures leave: the linked pictures and
     * sounds after it move earlier. A picture or a sound deleted by itself
     * leaves a gap, and nothing unlinked ever moves. [preserving] is the
     * playhead: the answer is where what was under it is now, or the cut it
     * falls on when that was deleted.
     */
    fun remove(timeline: TrackTimeline, selection: TimelineSelection, preserving: EditTime? = null): Removal {
        if (selection.isEmpty) return Removal(timeline, preserving)
        val ids = selection.ids
        val oldVideo = timeline.video
        val oldAudio = timeline.audio
        val oldDuration = timeline.duration
        // Only links deleted whole close their time. A range is cut before this is called.
        val complete = timeline.links.filter { link -> link.members.any { it in selection.video } && link.members.any { it in selection.audio } && ids.containsAll(link.members) }
        val removedVideo = complete.flatMapTo(HashSet()) { it.members }.apply { retainAll(selection.video) }
        val closing = union(oldVideo.filter { it.id in removedVideo }.map { it.interval })
        val audioIDs = oldAudio.mapTo(HashSet()) { it.id }
        val videoIDs = oldVideo.mapTo(HashSet()) { it.id }
        val downstream = timeline.links.filter { link -> link.members.none { it in ids } && link.members.any { it in audioIDs } && link.members.any { it in videoIDs } }
        val downstreamIDs = downstream.flatMapTo(HashSet()) { it.members }
        val remaining = oldVideo.filter { it.id !in selection.video && it.id in downstreamIDs }
        // Only the time that is newly free is closed, the handles of removed transitions included.
        val closed = closing.mapNotNull { range ->
            val before = remaining.filter { it.start < range.start }.maxOfOrNull { it.end } ?: range.start
            val after = remaining.filter { it.start >= range.start }.minOfOrNull { it.start } ?: range.end
            val start = maxOf(range.start, before)
            val end = minOf(range.end, after)
            if (end > start) EditInterval(start, end) else null
        }
        var video = oldVideo.filter { it.id !in selection.video }
        var audio = oldAudio.filter { it.id !in selection.audio }
        val shift = HashMap<String, EditTime>()
        for (group in downstream) {
            val anchor = oldVideo.filter { it.id in group.members }.minOfOrNull { it.start }
                ?: audio.filter { it.id in group.members }.minOfOrNull { it.start } ?: EditTime.ZERO
            val amount = EditTime(closed.filter { it.start <= anchor }.sumOf { it.duration.ticks })
            for (member in group.members) shift[member] = amount
        }
        video = video.map { item -> shift[item.id]?.let { item.copy(start = item.start - it) } ?: item }
        audio = audio.map { item -> shift[item.id]?.let { item.copy(start = item.start - it) } ?: item }
        val before = HashMap<String, EditTime>()
        for (item in oldVideo) before.putIfAbsent(item.id, item.start)
        val after = HashMap<String, EditTime>()
        for (item in video) after.putIfAbsent(item.id, item.start)
        val transitions = timeline.transitions.map { relation ->
            val owner = relation.outgoing ?: relation.incoming
            val prior = owner?.let { before[it] }
            val current = owner?.let { after[it] }
            if (prior != null && current != null) relation.copy(origin = relation.origin + current - prior) else relation
        }
        val result = timeline.copy(video = video, audio = audio, transitions = transitions).pruned()
        val position = preserving ?: return Removal(result, null)
        val removedTime = EditTime(closed.sumOf { minOf(it.duration, maxOf(EditTime.ZERO, position - it.start)).ticks })
        val duration = result.duration
        if (closed.any { position in it }) return Removal(result, minOf(duration, maxOf(EditTime.ZERO, position - removedTime)))
        fun holds(interval: EditInterval) = position in interval || (position == oldDuration && interval.end == position)
        // What shows stays under the playhead, even when another layer moved. Sound decides only where there was no picture.
        var mapped: EditTime? = null
        val picture = oldVideo.filter { holds(it.interval) }.maxByOrNull { it.layer }
        if (picture != null) {
            result.video.firstOrNull { it.id == picture.id }?.let { mapped = position + it.start - picture.start }
        } else {
            val sound = oldAudio.firstOrNull { holds(it.interval) }
            if (sound != null) result.audio.firstOrNull { it.id == sound.id }?.let { mapped = position + it.start - sound.start }
        }
        return Removal(result, minOf(duration, maxOf(EditTime.ZERO, mapped ?: (position - removedTime))))
    }

    /**
     * Opens [duration] of empty time at [time]: what plays across it is cut
     * and everything from there on moves later. At a gap in the picture
     * ([atPictureGap]) only pictures are cut, so a sound running under the
     * gap goes on through it.
     */
    fun insertTime(timeline: TrackTimeline, duration: EditTime, time: EditTime, atPictureGap: Boolean = false): TrackTimeline {
        if (duration <= EditTime.ZERO) return timeline
        val scope = if (atPictureGap) TimelineSelection(video = timeline.video.filter { it.start >= time }.mapTo(LinkedHashSet()) { it.id }) else TrackLinking.entireSelection(timeline)
        val cut = split(timeline, time, scope).timeline
        return cut.copy(
            video = cut.video.map { if (it.start >= time) it.copy(start = it.start + duration) else it },
            audio = cut.audio.map { if (it.start >= time) it.copy(start = it.start + duration) else it },
            bookmarks = TrackBookmarks.shiftingProjectMarks(cut.bookmarks, time, duration),
            transitions = cut.transitions.map { if (it.origin >= time) it.copy(origin = it.origin + duration) else it },
            generated = cut.generated.map { if (it.interval.start >= time) it.copy(interval = EditInterval(it.interval.start + duration, it.interval.end + duration)) else it },
        ).pruned()
    }

    /**
     * A boundary of the picture sequence as the layout of the timeline finds
     * it: between [outgoingID] and [incomingID], at [time] seconds, with the
     * id of the [transition] on it. [selectable] is false where two pictures
     * that follow one another in time do not meet. [edge] is −1 at an
     * opening, +1 at a close.
     */
    class Join(val index: Int, val time: Double, val transition: String?, val selectable: Boolean, val edge: Int, val outgoingID: String?, val incomingID: String?)

    /**
     * The joins of the timeline: one before each picture in the order they
     * start and one after the picture that ends last, then every transition
     * that is not on one of those, then the plain cut that an opening on a
     * picture would otherwise hide.
     */
    fun joins(timeline: TrackTimeline): List<Join> {
        val pictures = timeline.chronologicalVideo
        if (pictures.isEmpty()) return emptyList()
        val byID = HashMap<String, VideoTimelineItem>()
        for (item in pictures) byID.putIfAbsent(item.id, item)
        val byEnd = pictures.groupBy { it.end }
        val incoming = HashMap<String, TimelineTransition>()
        for (relation in timeline.transitions) relation.incoming?.let { incoming.putIfAbsent(it, relation) }
        val last = pictures.maxByOrNull { it.end }!!
        /** The picture [right] follows at a plain cut: the nearest one under it, or else the lowest one above. */
        fun ending(right: VideoTimelineItem): VideoTimelineItem? {
            val ending = byEnd[right.start].orEmpty().filter { it.id != right.id }
            return ending.filter { it.layer <= right.layer }.maxWithOrNull(VideoTimelineItem.backToFront) ?: ending.minWithOrNull(VideoTimelineItem.backToFront)
        }
        val built = ArrayList<Join>()
        val placed = HashSet<String>()
        for (index in 0..pictures.size) {
            val right = pictures.getOrNull(index)
            val relation = if (right != null) incoming[right.id] else timeline.transitions.firstOrNull { it.incoming == null && it.outgoing == last.id }
            val left = when {
                relation != null -> relation.outgoing?.let { byID[it] }
                right != null && index > 0 -> ending(right) ?: pictures[index - 1]
                right == null -> last
                else -> null
            }
            val edge = if (left == null) -1 else if (right == null) 1 else 0
            val timing = TransitionTiming.of(relation?.settings, relation?.factor ?: 1.0, edge)
            val origin = relation?.origin?.seconds ?: left?.end?.seconds ?: right?.start?.seconds ?: 0.0
            val point = when {
                relation == null -> origin
                edge == -1 -> right!!.start.seconds
                edge == 1 -> left!!.end.seconds
                else -> origin + timing.cut
            }
            val selectable = relation != null || (left == null && right?.start == EditTime.ZERO) || right == null || left?.end == right.start
            built += Join(index, point, relation?.id, selectable, edge, left?.id, right?.id)
            relation?.let { placed += it.id }
        }
        // A picture that was moved can leave its transition away from any boundary of the sequence. It stays reachable by what it is.
        for (relation in timeline.transitions) {
            if (!placed.add(relation.id)) continue
            val left = relation.outgoing?.let { byID[it] }
            val right = relation.incoming?.let { byID[it] }
            val edge = if (left == null) -1 else if (right == null) 1 else 0
            val timing = TransitionTiming.of(relation.settings, relation.factor, edge)
            val origin = relation.origin.seconds
            val time = if (edge == -1) maxOf(0.0, origin) else if (edge == 1) maxOf(0.0, origin + timing.upper) else origin + timing.cut
            built += Join(built.size, time, relation.id, true, edge, left?.id, right?.id)
        }
        // An opening belongs to its picture; it must not hide a cut that is really there.
        for (right in pictures) {
            if (timeline.transitions.none { it.incoming == right.id && it.outgoing == null }) continue
            val left = ending(right) ?: continue
            if (built.any { it.outgoingID == left.id && it.incomingID == right.id }) continue
            built += Join(built.size, right.start.seconds, null, true, 0, left.id, right.id)
        }
        return built
    }

    /** The selectable join at [time] seconds, one between two pictures before an opening or a close. */
    fun boundary(joins: List<Join>, time: Double): Join? {
        val matches = joins.filter { it.selectable && abs(it.time - time) < 0.0001 }
        return matches.firstOrNull { it.edge == 0 } ?: matches.firstOrNull()
    }

    /**
     * Removes the transition [id]. Between two pictures, the second goes back
     * to where the first ends, with everything linked that comes after it, so
     * the footage the transition used is all played again.
     */
    fun restoringStraightCut(timeline: TrackTimeline, id: String): TrackTimeline {
        val join = joins(timeline).firstOrNull { it.transition == id } ?: return timeline
        val left = timeline.video.firstOrNull { it.id == join.outgoingID }
        val right = timeline.video.firstOrNull { it.id == join.incomingID }
        val removed = timeline.copy(transitions = timeline.transitions.filter { !(it.outgoing == left?.id && it.incoming == right?.id) }).pruned()
        if (left == null || right == null) return removed
        val linked = removed.links.flatMapTo(HashSet()) { it.members }
        val pictures = removed.video.filter { it.id != left.id && (it.id == right.id || (it.id in linked && it.start >= right.start)) }.mapTo(HashSet()) { it.id }
        val moving = HashSet(pictures)
        for (sound in removed.audio) {
            if (sound.followsVideo == left.id) continue
            val follows = sound.followsVideo
            if (if (follows != null) follows in pictures else sound.id in linked && sound.start >= right.start) moving += sound.id
        }
        val delta = left.end - right.start
        return removed.copy(
            video = removed.video.map { if (it.id in moving) it.copy(start = it.start + delta) else it },
            audio = removed.audio.map { if (it.id in moving) it.copy(start = it.start + delta) else it },
            transitions = removed.transitions.map { relation ->
                if (listOfNotNull(relation.outgoing, relation.incoming).all { it in moving }) relation.copy(origin = relation.origin + delta) else relation
            },
        ).pruned()
    }
}
