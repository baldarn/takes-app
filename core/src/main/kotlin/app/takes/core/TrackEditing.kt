package app.takes.core

import kotlin.math.abs

/**
 * The pictures as clips, in the order they start, each with the transitions
 * that belong to it put back on it: its own opening, and what leads out of it
 * with the clip it leads into and the fit it is saved at. Sound, bookmarks and
 * words stay where the timeline keeps them. This is what the tools that edit a
 * clip's recipe read, and what [TrackEditing.replacingVideoContents] compares
 * their answer with. YouRec/TrackTimeline.swift:1259-1267.
 */
val TrackTimeline.projectedClips: List<TimelineClip>
    get() {
        val openings = HashMap<String, TimelineTransition>()
        val leavings = HashMap<String, TimelineTransition>()
        for (relation in transitions) {
            if (relation.outgoing == null) relation.incoming?.let { openings.putIfAbsent(it, relation) }
            relation.outgoing?.let { leavings.putIfAbsent(it, relation) }
        }
        return chronologicalVideo.map { item ->
            val opening = openings[item.id]
            val leaving = leavings[item.id]
            val content = item.content
            val bare = content.transition == null && content.openingTransition == null && content.transitionFit == null && content.openingTransitionFit == null
            if (opening == null && leaving == null && bare) content
            else content.copy(
                openingTransition = opening?.settings,
                openingTransitionFit = opening?.let { TransitionFit(it.factor) },
                transition = leaving?.settings?.copy(nextClipID = leaving.incoming),
                transitionFit = leaving?.let { TransitionFit(it.factor) },
            )
        }
    }

/** Where the timeline has picture: its items and the stretches of its transitions, merged. YouRec/TimelineEditing.swift:35-39. */
val TrackTimeline.videoCoverage: List<EditInterval>
    get() = TrackEditing.union(video.map { it.interval } + generated.map { it.interval })

/** Cuts the chosen items at [time]. See [TrackEditing.split]. */
fun TrackTimeline.split(time: EditTime, selection: TimelineSelection): TrackEditing.Split = TrackEditing.split(this, time, selection)

/** Cuts at both ends of the selection's range. See [TrackEditing.scoped]. */
fun TrackTimeline.scoped(selection: TimelineSelection): SelectedTimeline = TrackEditing.scoped(this, selection)

/** Deletes the selection. See [TrackEditing.removing]. */
fun TrackTimeline.removing(selection: TimelineSelection, preserving: EditTime? = null): TrackEditing.Removal =
    TrackEditing.removing(this, selection, preserving)

/** Opens [duration] of empty time at [time]. See [TrackEditing.insertingTime]. */
fun TrackTimeline.insertingTime(duration: EditTime, time: EditTime, atPictureGap: Boolean = false): TrackTimeline =
    TrackEditing.insertingTime(this, duration, time, atPictureGap)

/** The empty stretch around [time]. See [TrackEditing.gap]. */
fun TrackTimeline.gap(time: EditTime): EditInterval? = TrackEditing.gap(this, time)

/** Removes the empty time around [time]. See [TrackEditing.closingGap]. */
fun TrackTimeline.closingGap(time: EditTime): TrackTimeline = TrackEditing.closingGap(this, time)

/** Reveals trimmed footage. See [TrackEditing.extending]. */
fun TrackTimeline.extending(selection: TimelineSelection, left: Boolean, by: EditTime, sources: List<SourceMedia>): TrackTimeline =
    TrackEditing.extending(this, selection, left, by, sources)

/** Merges edited clips. See [TrackEditing.replacingVideoContents]. */
fun TrackTimeline.replacingVideoContents(clips: List<TimelineClip>): TrackTimeline = TrackEditing.replacingVideoContents(this, clips)

/** Gives following sounds their picture's timing. See [TrackEditing.synchronizingFollowers]. */
fun TrackTimeline.synchronizingFollowers(): TrackTimeline = TrackEditing.synchronizingFollowers(this)

/**
 * The editing operations of the track timeline: split, scope, delete, insert,
 * extend, close a gap, and the merge of edited recipes with its ripple. Ports
 * of the original's, function by function (YouRec/TrackTimeline.swift from
 * line 540, YouRec/TimelineEditing.swift); each names its lines.
 *
 * Every operation takes a timeline and answers a new one; none changes its
 * argument. Each ends with the prune ([TrackTimeline.pruned]), so what comes
 * out is at rest. There is no magnetic track: what ripples, and when, is
 * stated by each operation.
 *
 * What a clip of this port does not have yet (a held frame, pause cuts on the
 * clip, video effects that retime or mute) is left out where the original
 * reads it, and said so at the place.
 */
object TrackEditing {
    /** The shortest stretch an edit of a range acts on: one frame of the original's 30 per second (YouRec/TimelineEditing.swift:42). */
    const val FRAME = 1.0 / 30

    /** [ranges] in order, with those that touch or overlap made one; empty ones dropped. YouRec/TrackTimeline.swift:828-835. */
    fun union(ranges: List<EditInterval>): List<EditInterval> {
        val result = ArrayList<EditInterval>()
        for (range in ranges.sortedBy { it.start }) {
            if (range.isEmpty) continue
            val last = result.lastOrNull()
            if (last != null && range.start <= last.end) result[result.lastIndex] = last.copy(end = maxOf(last.end, range.end)) else result += range
        }
        return result
    }

    // MARK: Split and scope

    /** A timeline after a cut, and for each item that was cut the id of its later piece; the earlier piece keeps the item's id. */
    data class Split(val timeline: TrackTimeline, val children: Map<String, String>)

    /**
     * Cuts the items of [selection], and what they are linked to, that [time]
     * falls strictly inside. A picture's earlier piece keeps its id and its
     * recipe up to the cut; the later one is a copy under a new id that begins
     * there, in the same occurrence. A sound is cut the same way, with what is
     * left of its fades, censors and chains on each side
     * ([AudioTimelineItem.sliced]); both pieces keep the words.
     *
     * What follows the pieces: a sound that followed a cut picture follows the
     * piece it now starts with; a link becomes one link per side (the earlier
     * side keeps its id); a transition out of a cut picture leads out of its
     * later piece; a placement of a cut picture is also given to the later
     * pieces, and the prune keeps whichever still overlap.
     *
     * YouRec/TrackTimeline.swift:1007-1079. The original also cuts a held
     * frame by its length; a clip here holds no frame.
     */
    fun split(timeline: TrackTimeline, time: EditTime, selection: TimelineSelection): Split {
        val chosen = TrackLinking.expanded(timeline, selection)
        val children = LinkedHashMap<String, String>()
        val video = ArrayList<VideoTimelineItem>(timeline.video.size + 1)
        for (item in timeline.video) {
            if (item.id !in chosen.video || time <= item.start || time >= item.end) { video += item; continue }
            val local = time - item.start
            val source = item.mapping.source(local)
            if (source == null) { video += item; continue }
            val right = newID()
            video += item.copy(content = item.content.copy(end = source), mapping = item.mapping.sliced(EditInterval(EditTime.ZERO, local)))
            video += item.copy(
                content = item.content.copy(id = right, start = source), start = time, localOrigin = item.localOrigin + local,
                mapping = item.mapping.sliced(EditInterval(local, item.mapping.duration)),
            )
            children[item.id] = right
        }
        val audio = ArrayList<AudioTimelineItem>(timeline.audio.size + 1)
        for (item in timeline.audio) {
            if (item.id !in chosen.audio || time <= item.start || time >= item.end) { audio += item; continue }
            val local = time - item.start
            val right = newID()
            audio += item.sliced(EditInterval(EditTime.ZERO, local), item.id)
            audio += item.sliced(EditInterval(local, item.mapping.duration), right)
            children[item.id] = right
        }
        if (children.isEmpty()) return Split(timeline.pruned(), children)
        for (index in audio.indices) {
            val child = audio[index].followsVideo?.let { children[it] } ?: continue
            if (audio[index].start >= time) audio[index] = audio[index].copy(followsVideo = child)
        }
        val starts = HashMap<String, EditTime>()
        for (item in video) starts.putIfAbsent(item.id, item.start)
        for (item in audio) starts.putIfAbsent(item.id, item.start)
        val ids = chosen.ids
        val links = ArrayList<TimelineLink>()
        for (group in timeline.links) {
            // Cutting another lane must not undo a link.
            if (group.members.none { it in ids }) { links += group; continue }
            val left = LinkedHashSet<String>()
            val right = LinkedHashSet<String>()
            for (id in group.members) {
                val child = children[id]
                if (child != null) { left += id; right += child }
                else if ((starts[id] ?: EditTime.ZERO) >= time) right += id else left += id
            }
            if (left.size >= 2) links += TimelineLink(group.id, left)
            if (right.size >= 2) links += TimelineLink(if (left.isEmpty()) group.id else newID(), right)
        }
        val transitions = timeline.transitions.map { relation -> relation.outgoing?.let { children[it] }?.let { relation.copy(outgoing = it) } ?: relation }
        // Every later piece starts at the same cut. A placement can live on either side; a mix of both never overlaps.
        val later = TrackPlacements.carriedAcrossSplit(timeline.placements, children)
        return Split(timeline.copy(video = video, audio = audio, links = links, transitions = transitions, placements = timeline.placements + later).pruned(), children)
    }

    /**
     * The timeline cut at both ends of [selection]'s range, with the pieces
     * of the selected items (and what they are linked to) that lie inside it,
     * as a selection without a range. With no range nothing is cut and the
     * selection is only expanded. YouRec/TrackTimeline.swift:1080-1094.
     */
    fun scoped(timeline: TrackTimeline, selection: TimelineSelection): SelectedTimeline {
        val chosen = TrackLinking.expanded(timeline, selection)
        val range = chosen.range ?: return SelectedTimeline(timeline, chosen)
        var video = chosen.video
        var audio = chosen.audio
        fun adopt(children: Map<String, String>) {
            video = video + children.filterKeys { it in video }.values
            audio = audio + children.filterKeys { it in audio }.values
        }
        val upper = split(timeline, range.end, chosen)
        adopt(upper.children)
        val lower = split(upper.timeline, range.start, TimelineSelection(video, audio, range))
        adopt(lower.children)
        val result = lower.timeline
        return SelectedTimeline(
            result,
            TimelineSelection(
                result.video.filter { it.id in video && it.start >= range.start && it.end <= range.end }.mapTo(LinkedHashSet()) { it.id },
                result.audio.filter { it.id in audio && it.start >= range.start && it.end <= range.end }.mapTo(LinkedHashSet()) { it.id },
            ),
        )
    }

    /**
     * Finds a marked scope again on [timeline] after a tool cut or retimed
     * the items it was made on in [previous]: the items of the same
     * occurrence and source that still play some of the footage the scope
     * covered, and, when the scope had a range, the stretch that footage now
     * takes. A second use of the same source is another occurrence and is not
     * found. YouRec/TrackTimeline.swift:1097-1144.
     */
    fun remapping(timeline: TrackTimeline, selection: TimelineSelection, previous: TrackTimeline): TimelineSelection {
        val ranges = ArrayList<EditInterval>()
        fun scopedMap(map: SourcePlaybackMap, start: EditTime): SourcePlaybackMap =
            selection.range?.let { map.sliced(EditInterval(it.start - start, it.end - start)) } ?: map
        fun scopeOrigin(origin: EditTime, start: EditTime, duration: EditTime): EditTime =
            origin + (selection.range?.let { minOf(duration, maxOf(EditTime.ZERO, it.start - start)) } ?: EditTime.ZERO)
        fun match(old: SourcePlaybackMap, new: SourcePlaybackMap, oldOrigin: EditTime, newOrigin: EditTime, start: EditTime): Boolean {
            var found = false
            var local = EditTime.ZERO
            for (run in new.runs) {
                var sourceLocal = oldOrigin
                for (source in old.runs) {
                    val from = sourceLocal
                    sourceLocal += source.duration
                    if (run.isHold || source.isHold) {
                        if (run.isHold && source.isHold && run.sourceStart == source.sourceStart) {
                            EditInterval(from, from + source.duration).intersection(EditInterval(newOrigin + local, newOrigin + local + run.duration))?.let { part ->
                                ranges += EditInterval(start + part.start - newOrigin, start + part.end - newOrigin)
                                found = true
                            }
                        }
                        continue
                    }
                    val a = maxOf(source.sourceStart, run.sourceStart)
                    val b = minOf(source.sourceEnd, run.sourceEnd)
                    if (b <= a + 0.0000001) continue
                    val scale = run.duration.seconds / (run.sourceEnd - run.sourceStart)
                    ranges += EditInterval(start + local + EditTime.of((a - run.sourceStart) * scale), start + local + EditTime.of((b - run.sourceStart) * scale))
                    found = true
                }
                local += run.duration
            }
            return found
        }
        val pictures = previous.video.filter { it.id in selection.video }
        val sounds = previous.audio.filter { it.id in selection.audio }
        val video = timeline.video.filter { item ->
            pictures.any {
                it.occurrenceID == item.occurrenceID && it.content.sourceID == item.content.sourceID &&
                    match(scopedMap(it.mapping, it.start), item.mapping, scopeOrigin(it.localOrigin, it.start, it.mapping.duration), item.localOrigin, item.start)
            }
        }.mapTo(LinkedHashSet()) { it.id }
        val audio = timeline.audio.filter { item ->
            sounds.any {
                it.occurrenceID == item.occurrenceID && it.sourceID == item.sourceID &&
                    match(scopedMap(it.mapping, it.start), item.mapping, scopeOrigin(it.localOrigin, it.start, it.mapping.duration), item.localOrigin, item.start)
            }
        }.mapTo(LinkedHashSet()) { it.id }
        val range = if (selection.range == null || ranges.isEmpty()) null else EditInterval(ranges.minOf { it.start }, ranges.maxOf { it.end })
        return TimelineSelection(video, audio, range)
    }

    // MARK: Delete

    /** A timeline after a deletion, and where the playhead goes; null when none was asked for. */
    data class Removal(val timeline: TrackTimeline, val position: EditTime?)

    /**
     * Deletes the items of [selection], which is taken as given: it is not
     * expanded, and a range is cut before this ([scoped]).
     *
     * The gap rules. Only a link removed whole, picture and sound together,
     * closes the time it leaves; a picture or a sound deleted on its own
     * leaves a gap. What closes is the time the removed linked pictures
     * covered, held back to where pictures that stay (those in a link of
     * picture and sound) still reach, so the handles of a removed transition
     * close too and nothing that stays is overlapped. Every link of picture
     * and sound that stays moves earlier, as one group, by the closed time at
     * or before its first picture. Sound and pictures that are in no such
     * link do not move.
     *
     * [preserving] is where the playhead was; the answer is where what was
     * under it is now: at the cut when it was inside closed time, with its
     * picture (the one in front) or else its sound when that moved, and
     * otherwise where it was.
     *
     * YouRec/TrackTimeline.swift:771-827.
     */
    fun removing(timeline: TrackTimeline, selection: TimelineSelection, preserving: EditTime? = null): Removal {
        if (selection.isEmpty) return Removal(timeline, preserving)
        val oldVideo = timeline.video
        val oldAudio = timeline.audio
        val oldDuration = timeline.duration
        val ids = selection.ids
        // Only links removed whole count.
        val complete = timeline.links.filter { link -> link.members.any { it in selection.video } && link.members.any { it in selection.audio } && ids.containsAll(link.members) }
        val removedVideo = complete.flatMapTo(HashSet()) { it.members }.also { it.retainAll(selection.video) }
        val closing = union(oldVideo.filter { it.id in removedVideo }.map { it.interval })
        val audioIDs = oldAudio.mapTo(HashSet()) { it.id }
        val videoIDs = oldVideo.mapTo(HashSet()) { it.id }
        val downstreamGroups = timeline.links.filter { link -> link.members.none { it in ids } && link.members.any { it in audioIDs } && link.members.any { it in videoIDs } }
        val downstreamIDs = downstreamGroups.flatMapTo(HashSet()) { it.members }
        val remainingVideo = oldVideo.filter { it.id !in selection.video && it.id in downstreamIDs }
        val closedGaps = closing.mapNotNull { range ->
            val before = remainingVideo.filter { it.start < range.start }.maxOfOrNull { it.end } ?: range.start
            val after = remainingVideo.filter { it.start >= range.start }.minOfOrNull { it.start } ?: range.end
            val start = maxOf(range.start, before)
            val end = minOf(range.end, after)
            if (end > start) EditInterval(start, end) else null
        }
        var video = oldVideo.filter { it.id !in selection.video }
        var audio = oldAudio.filter { it.id !in selection.audio }
        if (closedGaps.isNotEmpty()) {
            val pictureStart = HashMap<String, EditTime>()
            for (item in oldVideo) pictureStart.putIfAbsent(item.id, item.start)
            val soundStart = HashMap<String, EditTime>()
            for (item in audio) soundStart.putIfAbsent(item.id, item.start)
            val shift = HashMap<String, EditTime>()
            for (group in downstreamGroups) {
                val anchor = group.members.mapNotNull { pictureStart[it] }.minOrNull() ?: group.members.mapNotNull { soundStart[it] }.minOrNull() ?: EditTime.ZERO
                val amount = EditTime(closedGaps.filter { it.start <= anchor }.sumOf { it.duration.ticks })
                if (amount != EditTime.ZERO) for (member in group.members) shift[member] = amount
            }
            video = video.map { item -> shift[item.id]?.let { item.copy(start = item.start - it) } ?: item }
            audio = audio.map { item -> shift[item.id]?.let { item.copy(start = item.start - it) } ?: item }
        }
        val prior = HashMap<String, EditTime>()
        for (item in oldVideo) prior.putIfAbsent(item.id, item.start)
        val current = HashMap<String, EditTime>()
        for (item in video) current.putIfAbsent(item.id, item.start)
        val transitions = timeline.transitions.map { relation ->
            val owner = relation.outgoing ?: relation.incoming
            val was = prior[owner]
            val now = current[owner]
            if (was != null && now != null && was != now) relation.copy(origin = relation.origin + now - was) else relation
        }
        val result = timeline.copy(video = video, audio = audio, transitions = transitions).pruned()
        val position = preserving ?: return Removal(result, null)
        val removedTime = EditTime(closedGaps.sumOf { minOf(it.duration, maxOf(EditTime.ZERO, position - it.start)).ticks })
        if (closedGaps.any { position in it }) return Removal(result, minOf(result.duration, maxOf(EditTime.ZERO, position - removedTime)))
        fun holds(interval: EditInterval) = position in interval || (position == oldDuration && interval.end == position)
        // The picture showing stays under the playhead even when another layer rippled; a sound only where there was no picture.
        var mapped: EditTime? = null
        val picture = oldVideo.filter { holds(it.interval) }.maxByOrNull { it.layer }
        if (picture != null) {
            result.video.firstOrNull { it.id == picture.id }?.let { mapped = position + it.start - picture.start }
        } else {
            val sound = oldAudio.firstOrNull { holds(it.interval) }
            if (sound != null) result.audio.firstOrNull { it.id == sound.id }?.let { mapped = position + it.start - sound.start }
        }
        return Removal(result, minOf(result.duration, maxOf(EditTime.ZERO, mapped ?: (position - removedTime))))
    }

    /** The stretch around [time] where nothing plays on any lane, between what is before it and what is after; null where something plays or past the end. YouRec/TrackTimeline.swift:1588-1594. */
    fun gap(timeline: TrackTimeline, time: EditTime): EditInterval? {
        val occupied = union(timeline.video.map { it.interval } + timeline.audio.map { it.interval } + timeline.generated.map { it.interval })
        if (time < EditTime.ZERO || time >= timeline.duration || occupied.any { time in it }) return null
        val start = occupied.filter { it.end <= time }.maxOfOrNull { it.end } ?: EditTime.ZERO
        val end = occupied.filter { it.start > time }.minOfOrNull { it.start } ?: timeline.duration
        return if (end > start) EditInterval(start, end) else null
    }

    /**
     * Removes the gap around [time]: everything after it, on every lane,
     * comes earlier by its length. Marks on the timeline itself that were in
     * or after it come with it, to no earlier than where it began. The same
     * timeline when there is no gap there. YouRec/TrackTimeline.swift:1595-1604.
     */
    fun closingGap(timeline: TrackTimeline, time: EditTime): TrackTimeline {
        val gap = gap(timeline, time) ?: return timeline
        return timeline.copy(
            video = timeline.video.map { if (it.start >= gap.end) it.copy(start = it.start - gap.duration) else it },
            audio = timeline.audio.map { if (it.start >= gap.end) it.copy(start = it.start - gap.duration) else it },
            transitions = timeline.transitions.map { if (it.origin >= gap.end) it.copy(origin = it.origin - gap.duration) else it },
            bookmarks = timeline.bookmarks.map { mark ->
                val anchor = mark.anchor
                if (anchor is BookmarkAnchor.Project && anchor.time >= gap.start) mark.copy(anchor = BookmarkAnchor.Project(maxOf(gap.start, anchor.time - gap.duration))) else mark
            },
        ).pruned()
    }

    // MARK: Insert

    /**
     * Opens [duration] of empty time at [time]: what crosses [time] is cut
     * there, and every item that starts at or after it moves later, with the
     * transitions and the marks on the timeline that are at or after it.
     *
     * [atPictureGap] is for new video put beside existing picture: only the
     * pictures that start at or after [time] (and the sound linked to them)
     * are cut, so a soundtrack running under a picture gap is not cut in two.
     *
     * YouRec/TrackTimeline.swift:1145-1161.
     */
    fun insertingTime(timeline: TrackTimeline, duration: EditTime, time: EditTime, atPictureGap: Boolean = false): TrackTimeline {
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
     * Puts [clips] into the timeline as new footage, in order, one after
     * another, each a picture with its following sound and their link. Every
     * copy gets identities of its own, for the clip and for everything on it
     * that a tool edits by id, so editing a copy never edits what it was
     * copied from; the transitions the clips carried are not brought along.
     *
     * Without a [site] they go in at [time], kept inside the timeline, and
     * everything from there on moves later by their length. With one they go
     * beside existing picture ([InsertionSites.placement]): free picture time
     * is used first and later footage moves only by what does not fit.
     * They come in front of every picture already there. The sources in
     * [pictureOnlySources] get no sound.
     *
     * YouRec/TimelineEditing.swift:86-127. The original's clip carries no
     * sound settings, bookmarks or words; those a clip carries here go where
     * the conversion puts them, the bookmarks and words under new ids.
     */
    fun inserting(
        timeline: TrackTimeline, clips: List<TimelineClip>, time: Double, site: VideoInsertionSite? = null,
        pictureOnlySources: Set<String> = emptySet(),
    ): TrackTimeline {
        if (clips.isEmpty()) return timeline
        val at = minOf(timeline.duration.seconds, maxOf(0.0, time))
        val ids = HashMap<String, String>()
        fun fresh(old: String): String = ids.getOrPut(old) { newID() }
        val inserted = clips.map { original ->
            original.copy(
                id = newID(), openingTransition = null, transition = null, openingTransitionFit = null, transitionFit = null,
                speed = original.speed?.let { it.copy(id = fresh(it.id)) },
                color = original.color?.let { it.copy(id = fresh(it.id)) },
                crop = original.crop?.let { it.copy(id = fresh(it.id)) },
                texts = original.texts?.map { it.copy(id = fresh(it.id)) },
                bookmarks = original.bookmarks?.map { it.copy(id = newID()) },
                subtitleWords = original.subtitleWords?.map { it.copy(id = fresh(it.id), phraseID = fresh(it.phraseID)) },
            )
        }
        var addition = EditState(inserted).toTimeline()
        if (pictureOnlySources.isNotEmpty()) {
            val silent = addition.video.filter { it.content.sourceID in pictureOnlySources }.mapTo(HashSet()) { it.id }
            addition = addition.copy(
                audio = addition.audio.filter { it.followsVideo !in silent },
                links = addition.links.filter { link -> link.members.none { it in silent } },
            )
        }
        val point = EditTime.of(at)
        val length = addition.duration
        val start: EditTime
        val rippleAt: EditTime
        val ripple: EditTime
        if (site == null) { start = point; rippleAt = point; ripple = length } else {
            val placement = InsertionSites.placement(site, timeline.videoCoverage.map { InsertionSites.Span(it.start.seconds, it.end.seconds) }, length.seconds)
            start = EditTime.of(placement.start); rippleAt = EditTime.of(placement.rippleAt); ripple = EditTime.of(placement.ripple)
        }
        val opened = insertingTime(timeline, ripple, rippleAt, atPictureGap = site != null)
        val layer = (opened.video.maxOfOrNull { it.layer } ?: 0) + 1
        return opened.copy(
            video = (opened.video + addition.video.map { it.copy(start = it.start + start, layer = it.layer + layer) }).sortedBy { it.start },
            audio = opened.audio + addition.audio.map { it.copy(start = it.start + start) },
            links = opened.links + addition.links,
            bookmarks = opened.bookmarks + addition.bookmarks,
        )
    }

    // MARK: At the playhead, and on a range

    /**
     * The cut the playhead makes at second [time], put on the 1/30 s grid as
     * the original puts it. Refused, with the same timeline, strictly inside
     * a transition. When items are selected and the cut falls inside one of
     * them (or what it is linked to), only those are cut: that is how sound
     * that was unlinked is cut on its own. Otherwise everything under the
     * playhead is cut. YouRec/TimelineEditing.swift:45-62.
     */
    fun splitAtPlayhead(timeline: TrackTimeline, time: Double, selectedItems: Set<String> = emptySet()): TrackTimeline {
        val point = EditTime.of(RangeEditing.quantize(time))
        TrackLayout.of(timeline).transition(time)?.let { if (time > it.range.start + 0.00001 && time < it.range.endInclusive - 0.00001) return timeline }
        if (selectedItems.isNotEmpty()) {
            val selected = TrackLinking.selection(timeline, selectedItems)
            val cutsVideo = timeline.video.any { it.id in selected.video && it.start < point && point < it.end }
            val cutsAudio = timeline.audio.any { it.id in selected.audio && it.start < point && point < it.end }
            if (cutsVideo || cutsAudio) return split(timeline, point, selected).timeline
        }
        return split(timeline, point, TrackLinking.entireSelection(timeline)).timeline
    }

    /** A timeline after a range was taken out, and the recipes of the pictures that were in it. */
    data class Cut(val timeline: TrackTimeline, val clips: List<TimelineClip>)

    /**
     * Takes the seconds [start]…[end] out of every lane. An end within a
     * frame of an item's edge is put on that edge, so a range marked at a cut
     * that is on no grid takes the whole piece. Under one frame nothing
     * happens. YouRec/TimelineEditing.swift:66-80.
     */
    fun removing(timeline: TrackTimeline, start: Double, end: Double): Cut {
        if (end - start < FRAME - 0.00001) return Cut(timeline, emptyList())
        val boundaries = (timeline.video.flatMap { listOf(it.start.seconds, it.end.seconds) } + timeline.audio.flatMap { listOf(it.start.seconds, it.end.seconds) }).toSet()
        fun aligned(time: Double): Double = boundaries.minByOrNull { abs(it - time) }?.takeIf { abs(it - time) < FRAME } ?: time
        val a = aligned(start)
        val b = aligned(end)
        if (b <= a) return Cut(timeline, emptyList())
        val scope = scoped(timeline, TrackLinking.entireSelection(timeline, EditInterval.of(a, b)))
        val copied = scope.timeline.video.filter { it.id in scope.selection.video }.map { it.content }
        return Cut(removing(scope.timeline, scope.selection).timeline, copied)
    }

    /**
     * A trim by a handle of the item [id], with what it is linked to:
     * [delta] seconds at its [left] or right edge. Outwards it reveals
     * footage ([extending]) and nothing else moves. Inwards it is a deletion
     * of the trimmed part of this occurrence, never of another lane, leaving
     * at least one frame; a linked picture and sound close the gap, as any
     * deletion of them does. Null when nothing changes.
     * YouRec/EditorView.swift:1241-1258.
     */
    fun trimming(timeline: TrackTimeline, id: String, left: Boolean, delta: Double, sources: List<SourceMedia>, preserving: EditTime? = null): Removal? {
        val chosen = TrackLinking.selection(timeline, id)
        val interval = TrackLinking.interval(timeline, chosen) ?: return null
        if ((left && delta < 0) || (!left && delta > 0)) {
            val extended = extending(timeline, chosen, left, EditTime.of(abs(delta)), sources)
            return if (extended == timeline) null else Removal(extended, preserving)
        }
        val from = interval.start.seconds
        val to = interval.end.seconds
        val point = if (left) minOf(to - FRAME, maxOf(from, from + delta)) else maxOf(from + FRAME, minOf(to, to + delta))
        val lower = if (left) from else point
        val upper = if (left) point else to
        if (upper - lower <= 0.00001) return null
        val scope = scoped(timeline, chosen.copy(range = EditInterval.of(lower, upper)))
        return removing(scope.timeline, scope.selection, preserving)
    }

    /**
     * Takes stretches of source time, such as detected pauses, out of the
     * picture [id] and what it is linked to: each is cut out and deleted, so
     * a linked picture and sound close the gap and later linked footage comes
     * earlier. The pieces stay separate items of the same occurrence. Null
     * when [id] is no picture or none of [cuts] lies in what it plays.
     *
     * The original keeps one clip with a pause setting and one map of several
     * runs (YouRec/TrackTimeline.swift:49-64, :1376-1385). A clip here has no
     * such setting, so pauses make pieces, as this port's editor does today
     * (docs/TRACKS-PLAN.md, 2.4, N3); the ripple is the deletion's.
     */
    fun removingSource(timeline: TrackTimeline, id: String, cuts: List<SourceInterval>): TrackTimeline? {
        val picture = timeline.video.firstOrNull { it.id == id } ?: return null
        var result = timeline
        // From the last, so the piece that keeps the id still holds every earlier cut.
        for (cut in cuts.sortedByDescending { it.start }) {
            val item = result.video.firstOrNull { it.id == picture.id } ?: break
            val first = item.mapping.runs.firstOrNull() ?: break
            val last = item.mapping.runs.last()
            if (cut.end <= first.sourceStart || cut.start >= last.sourceEnd || cut.end <= cut.start) continue
            val from = if (cut.start <= first.sourceStart) EditTime.ZERO else item.mapping.local(cut.start) ?: continue
            val to = if (cut.end >= last.sourceEnd) item.mapping.duration else item.mapping.local(cut.end) ?: continue
            if (to <= from) continue
            val scope = scoped(result, TrackLinking.selection(result, item.id, range = EditInterval(item.start + from, item.start + to)))
            result = removing(scope.timeline, scope.selection).timeline
        }
        return result.takeIf { it != timeline }
    }

    // MARK: Extend

    /**
     * Reveals footage that was trimmed off the [left] or right edge of the
     * selected items and what they are linked to, by at most [requested]:
     * by what every one of them still has in its source, and, on the left, by
     * the room before the first of them. Nothing else moves, so revealing
     * into time that is taken makes an overlap, and a picture that comes to
     * overlap another this way comes in front of it.
     *
     * A sound that follows a selected picture takes the picture's new timing;
     * any other grows at the rate of its edge. A fade at the edge that was
     * cut into gets back what the revealed time gives it. On the left, the
     * chains and censors of a sound that does not follow stay on the same
     * sound, so their times move later by what was revealed.
     *
     * YouRec/TrackTimeline.swift:1618-1675.
     */
    fun extending(timeline: TrackTimeline, selection: TimelineSelection, left: Boolean, requested: EditTime, sources: List<SourceMedia>): TrackTimeline {
        val target = TrackLinking.expanded(timeline, selection)
        val lengths = HashMap<String, Double>()
        for (source in sources) lengths.putIfAbsent(source.id, source.duration)
        fun available(map: SourcePlaybackMap, start: EditTime, source: String): EditTime {
            val run = (if (left) map.runs.firstOrNull() else map.runs.lastOrNull()) ?: return EditTime.ZERO
            val length = lengths[source]
            if (run.isHold || length == null) return EditTime.ZERO
            val rate = (run.sourceEnd - run.sourceStart) / run.duration.seconds
            return if (left) minOf(start, EditTime.of(run.sourceStart / rate)) else EditTime.of(maxOf(0.0, length - run.sourceEnd) / rate)
        }
        fun revealMap(item: VideoTimelineItem): SourcePlaybackMap {
            val length = lengths[item.content.sourceID] ?: return SourcePlaybackMap(emptyList())
            val from = if (left) 0.0 else item.mapping.runs.lastOrNull()?.sourceEnd ?: item.content.end
            val to = if (left) item.mapping.runs.firstOrNull()?.sourceStart ?: from else length
            return if (to > from) SourcePlaybackMap.of(item.content.copy(start = from, end = to)) else SourcePlaybackMap(emptyList())
        }
        fun follows(item: AudioTimelineItem) = item.followsVideo?.let { it in target.video } ?: false
        val revealed = HashMap<String, SourcePlaybackMap>()
        var amount = requested
        for (item in timeline.video) {
            if (item.id !in target.video) continue
            val map = revealed.getOrPut(item.id) { revealMap(item) }
            amount = minOf(amount, if (left) minOf(item.start, map.duration) else map.duration)
        }
        for (item in timeline.audio) {
            if (item.id in target.audio && !follows(item)) amount = minOf(amount, available(item.mapping, item.start, item.sourceID))
        }
        if (amount <= EditTime.ZERO) return timeline
        val video = timeline.video.map { item ->
            if (item.id !in target.video) return@map item
            val map = revealed.getValue(item.id)
            val part = map.sliced(if (left) EditInterval(map.duration - amount, map.duration) else EditInterval(EditTime.ZERO, amount))
            val runs = if (left) part.runs + item.mapping.runs else item.mapping.runs + part.runs
            item.copy(
                start = if (left) item.start - amount else item.start,
                localOrigin = if (left) item.localOrigin - amount else item.localOrigin,
                mapping = SourcePlaybackMap(runs),
                content = item.content.copy(start = runs.first().sourceStart, end = runs.last().sourceEnd),
            )
        }
        val audio = timeline.audio.map { original ->
            if (original.id !in target.audio) return@map original
            var item = original
            if (left) item.fadeIn?.let { item = item.copy(fadeIn = it.copy(elapsed = maxOf(0.0, it.elapsed - amount.seconds))) }
            else item.fadeOut?.let { item = item.copy(fadeOut = it.copy(elapsed = maxOf(0.0, it.elapsed - amount.seconds))) }
            val own = !follows(original)
            if (own) {
                val run = if (left) item.mapping.runs.first() else item.mapping.runs.last()
                val change = amount.seconds * (run.sourceEnd - run.sourceStart) / run.duration.seconds
                val added = PlaybackRun(amount, if (left) maxOf(0.0, run.sourceStart - change) else run.sourceEnd, if (left) run.sourceStart else run.sourceEnd + change, false)
                item = item.copy(mapping = SourcePlaybackMap(if (left) listOf(added) + item.mapping.runs else item.mapping.runs + added))
            }
            if (left) {
                item = item.copy(start = item.start - amount, localOrigin = item.localOrigin - amount)
                if (own) {
                    item = item.copy(enhancements = item.enhancements.map { it.copy(interval = EditInterval(it.interval.start + amount, it.interval.end + amount)) })
                        .withCensors(item.censors.map { region ->
                            region.copy(
                                interval = EditInterval(region.interval.start + amount, region.interval.end + amount), phaseOrigin = region.phaseOrigin + amount,
                                envelopeInterval = region.envelopeInterval?.let { EditInterval(it.start + amount, it.end + amount) },
                            )
                        })
                }
            }
            item
        }
        var result = synchronizingFollowers(timeline.copy(video = video, audio = audio))
        result = result.copy(video = TrackMove.promotingNewOverlaps(result.video, target.video, timeline.video))
        return TrackPlacements.retainingAcrossNewOverlaps(result, target.video).pruned()
    }

    // MARK: Followers

    /**
     * [map] with its runs cut where [clip]'s playback changes and each run's
     * silence written from the clip: a hold is silent, and footage is silent
     * where the clip mutes its source. YouRec/TrackTimeline.swift:65-79.
     * A clip here plays one stretch at one rate and mutes none of it, so this
     * only writes what is already so; it is kept as the place the rule lives.
     */
    private fun applyingMute(map: SourcePlaybackMap, clip: TimelineClip): SourcePlaybackMap {
        val boundaries = SpeedMapping.spans(clip, clip.speed).flatMap { listOf(it.start, it.end) }
        val result = ArrayList<PlaybackRun>(map.runs.size)
        for (run in map.runs) {
            if (run.isHold) { result += if (run.silent) run else run.copy(silent = true); continue }
            val points = (listOf(run.sourceStart, run.sourceEnd) + boundaries.filter { it > run.sourceStart && it < run.sourceEnd }).distinct().sorted()
            var prior = EditTime.ZERO
            for ((a, b) in points.zipWithNext()) {
                val end = if (b == run.sourceEnd) run.duration else EditTime.of(run.duration.seconds * (b - run.sourceStart) / (run.sourceEnd - run.sourceStart))
                if (end > prior) result += PlaybackRun(end - prior, a, b, false)
                prior = end
            }
        }
        return if (result == map.runs) map else SourcePlaybackMap(result)
    }

    /** Every picture's map, and the map of every sound that follows one, with silence as the picture's recipe says. YouRec/TrackTimeline.swift:1268-1274. */
    fun restoringSourceMute(timeline: TrackTimeline): TrackTimeline {
        val video = timeline.video.map { item -> applyingMute(item.mapping, item.content).let { if (it === item.mapping) item else item.copy(mapping = it) } }
        val pictures = HashMap<String, VideoTimelineItem>()
        for (item in video) pictures.putIfAbsent(item.id, item)
        val audio = timeline.audio.map { item ->
            val picture = item.followsVideo?.let { pictures[it] } ?: return@map item
            applyingMute(item.mapping, picture.content).let { if (it === item.mapping) item else item.copy(mapping = it) }
        }
        return timeline.copy(video = video, audio = audio)
    }

    /** Where [interval] of an item timed by [prior] is on the same item timed by [next]: one stretch per run of [next] that still plays some of its source, in the order of [next]. */
    private fun rebased(interval: EditInterval, prior: SourcePlaybackMap, next: SourcePlaybackMap): List<EditInterval> {
        val sourceRuns = prior.sliced(interval).runs
        val result = ArrayList<EditInterval>()
        var cursor = EditTime.ZERO
        for (run in next.runs) {
            for (old in sourceRuns) {
                if (old.isHold || run.isHold) continue
                val a = maxOf(old.sourceStart, run.sourceStart)
                val b = minOf(old.sourceEnd, run.sourceEnd)
                if (b <= a) continue
                val length = run.sourceEnd - run.sourceStart
                val moved = EditInterval(cursor + EditTime.of(run.duration.seconds * (a - run.sourceStart) / length), cursor + EditTime.of(run.duration.seconds * (b - run.sourceStart) / length))
                if (!moved.isEmpty) result += moved
            }
            cursor += run.duration
        }
        return result
    }

    /**
     * Every sound that follows a picture takes the picture's start and
     * timing. When the timing changed, what the sound carries goes with the
     * recorded sound: a fade is squeezed to a shorter item and never
     * stretched; each chain and each censor moves to where its source seconds
     * now play, is dropped where they no longer play, and is in several
     * pieces where footage between was removed (pieces that touch are one
     * again). A censor's tone starts afresh in every piece, since its length
     * changed. YouRec/TrackTimeline.swift:1275-1336.
     */
    fun synchronizingFollowers(timeline: TrackTimeline): TrackTimeline {
        val muted = restoringSourceMute(timeline)
        val pictures = HashMap<String, VideoTimelineItem>()
        for (item in muted.video) pictures.putIfAbsent(item.id, item)
        val audio = muted.audio.map { sound ->
            val item = sound.followsVideo?.let { pictures[it] } ?: return@map sound
            val prior = sound.mapping
            if (prior == item.mapping) return@map if (sound.start == item.start) sound else sound.copy(start = item.start)
            val moved = sound.enhancements.flatMap { region -> rebased(region.interval, prior, item.mapping).map { region.copy(interval = it) } }
            val merged = ArrayList<AudioEnhancementRegion>()
            for (region in moved.sortedBy { it.interval.start }) {
                val last = merged.lastOrNull()
                if (last != null && last.id == region.id && last.effects == region.effects && last.interval.end == region.interval.start) {
                    merged[merged.lastIndex] = last.copy(interval = EditInterval(last.interval.start, region.interval.end))
                } else merged += region
            }
            val seen = HashSet<String>()
            for (index in merged.indices) {
                if (!seen.add(merged[index].id)) merged[index] = merged[index].copy(id = newID().also { seen += it })
            }
            val censors = sound.censors.flatMap { region ->
                rebased(region.interval, prior, item.mapping).map { region.copy(interval = it, phaseOrigin = it.start, envelopeInterval = null) }
            }
            val mergedCensors = ArrayList<AudioCensorRegion>()
            for (region in censors.sortedBy { it.interval.start }) {
                val last = mergedCensors.lastOrNull()
                if (last != null && last.id == region.id && last.settings == region.settings && last.interval.end == region.interval.start) {
                    mergedCensors[mergedCensors.lastIndex] = last.copy(interval = EditInterval(last.interval.start, region.interval.end))
                } else mergedCensors += region
            }
            sound.copy(
                start = item.start, mapping = item.mapping,
                fadeIn = sound.fadeIn?.fitted(prior.duration, item.mapping.duration),
                fadeOut = sound.fadeOut?.fitted(prior.duration, item.mapping.duration),
                enhancements = merged,
            ).withCensors(mergedCensors)
        }
        return muted.copy(audio = audio)
    }

    // MARK: Recipes: trim, speed

    /**
     * Merges clips a tool edited ([TrackTimeline.projectedClips], changed)
     * back into the timeline. A clip that is gone is deleted with what it is
     * linked to; a new one is put after the clip before it in [clips], with
     * time opened for it; the others get their recipe.
     *
     * A recipe whose timing changed (source bounds, speed) gives its picture a
     * new map, and, when the length changed and the picture is in a link, the
     * original's ripple:
     *
     *  - When the transition into the picture and the one out of it together
     *    cost more than it now lasts: after a change of source bounds both are
     *    removed, as a trim promises; after a change of speed, which keeps the
     *    footage, both are scaled by the largest factor that fits.
     *  - The picture is put where the transition into it wants it.
     *  - What comes after it moves by how far its end moved, or, across a
     *    transition out of it, by what keeps that transition in place: the
     *    pictures of the links of picture and sound that start at or after
     *    its old end (and the one it leads into), their sounds, and linked
     *    sound without a picture from there on.
     *  - A picture that plays over a lower one is an overlay: retiming it
     *    moves only the chain of pictures its transitions lead into, never
     *    the sequence under it.
     *
     * A picture that is in no link ripples nothing, and comes in front of
     * what it newly overlaps. The order of [clips] becomes the order pictures
     * are kept in; it never moves a transition to another neighbour. A
     * transition is removed only when its clip had it and now has none.
     * Sounds that follow then take their pictures' timing
     * ([synchronizingFollowers]).
     *
     * YouRec/TrackTimeline.swift:1339-1437.
     */
    fun replacingVideoContents(timeline: TrackTimeline, clips: List<TimelineClip>): TrackTimeline {
        val previous = timeline.projectedClips
        if (clips == previous) return timeline
        val previousItems = timeline.video
        val retimed = LinkedHashSet<String>()
        val oldIDs = timeline.video.mapTo(HashSet()) { it.id }
        val newIDs = clips.mapTo(HashSet()) { it.id }
        var base = timeline
        val removed = oldIDs - newIDs
        if (removed.isNotEmpty()) {
            val video = LinkedHashSet<String>()
            val audio = LinkedHashSet<String>()
            for (id in removed) TrackLinking.selection(timeline, id).let { video += it.video; audio += it.audio }
            base = removing(timeline, TimelineSelection(video, audio)).timeline
        }
        val video = base.video.toMutableList()
        val audio = base.audio.toMutableList()
        val transitions = base.transitions.toMutableList()
        val generated = base.generated.toMutableList()
        val links = base.links
        // Nothing in this pass adds, removes or reorders a picture.
        val places = HashMap<String, Int>()
        video.forEachIndexed { index, item -> places.putIfAbsent(item.id, index) }
        for (content in clips) {
            val index = places[content.id] ?: continue
            val before = video[index]
            val clean = content.copy(
                openingTransition = null, transition = null, openingTransitionFit = null, transitionFit = null,
                audio = null, bookmarks = null, subtitleWords = null,
            )
            val derived = SourcePlaybackMap.of(clean)
            val timingChanged = derived != SourcePlaybackMap.of(before.content)
            val difference = if (timingChanged) derived.duration - before.mapping.duration else EditTime.ZERO
            video[index] = before.copy(content = clean, mapping = if (timingChanged) derived else before.mapping)
            if (difference == EditTime.ZERO) continue
            retimed += content.id
            val group = links.firstOrNull { content.id in it.members } ?: continue
            val originalIncoming = transitions.firstOrNull { it.incoming == content.id }
            val originalOutgoing = transitions.firstOrNull { it.outgoing == content.id }
            var incoming = originalIncoming
            var outgoing = originalOutgoing
            fun timing(relation: TimelineTransition?, scale: Double = 1.0): TransitionTiming =
                if (relation == null) TransitionTiming() else TransitionTiming.of(relation.settings, relation.factor * scale, relation.edge)
            fun cost(scale: Double) = timing(incoming, scale).rightCost + timing(outgoing, scale).leftCost
            if (cost(1.0) > derived.duration.seconds) {
                if (clean.start != before.content.start || clean.end != before.content.end) {
                    // A trim that takes the footage a transition needs removes the transition.
                    val gone = setOfNotNull(incoming?.id, outgoing?.id)
                    transitions.removeAll { it.id in gone }
                    incoming = null
                    outgoing = null
                } else {
                    // A change of speed keeps the footage: both sides are fitted together before the next picture moves.
                    var lo = 0.0
                    var hi = 1.0
                    repeat(TransitionLayout.FIT_STEPS) {
                        val mid = (lo + hi) / 2
                        if (cost(mid) <= derived.duration.seconds) lo = mid else hi = mid
                    }
                    incoming = incoming?.let { it.copy(factor = it.factor * lo) }
                    outgoing = outgoing?.let { it.copy(factor = it.factor * lo) }
                    for (updated in listOfNotNull(incoming, outgoing)) {
                        val at = transitions.indexOfFirst { it.id == updated.id }
                        if (at >= 0) transitions[at] = updated
                    }
                }
            }
            if (originalIncoming != null) {
                val t = timing(incoming)
                val leading = video.firstOrNull { it.id == originalIncoming.outgoing }
                val start = if (leading != null) leading.end - EditTime.of(t.leftEnd) + EditTime.of(t.rightStart)
                else incoming?.let { maxOf(EditTime.ZERO, it.origin + EditTime.of(t.rightStart)) } ?: EditTime.ZERO
                video[index] = video[index].copy(start = start)
            }
            var delta = video[index].end - before.end
            if (originalOutgoing != null) {
                video.firstOrNull { it.id == originalOutgoing.incoming }?.let { next ->
                    val t = timing(outgoing)
                    delta = video[index].end - EditTime.of(t.leftEnd) + EditTime.of(t.rightStart) - next.start
                }
            }
            val soundIDs = audio.mapTo(HashSet()) { it.id }
            val pictureIDs = video.mapTo(HashSet()) { it.id }
            val downstream: Set<String> = if (group.members.none { it in soundIDs }) group.members
            else links.filter { link -> link.members.any { it in soundIDs } && link.members.any { it in pictureIDs } }.flatMapTo(HashSet()) { it.members }
            // A picture playing over a lower one is not ahead of the sequence. The pictures its transitions lead into are the exception.
            val nextPicture = originalOutgoing?.incoming
            val isOverlay = previousItems.any { it.id != content.id && it.id != originalIncoming?.outgoing && it.layer < before.layer && before.start in it.interval }
            val chain = HashSet<String>()
            var next = nextPicture
            while (true) {
                val id = next ?: break
                if (!chain.add(id)) break
                next = transitions.firstOrNull { it.outgoing == id }?.incoming
            }
            val moving = video.filter { it.id != content.id && it.id in downstream && (if (isOverlay) it.id in chain else (it.start >= before.end || it.id == nextPicture)) }
                .mapTo(HashSet()) { it.id }
            for (i in video.indices) if (video[i].id in moving) video[i] = video[i].copy(start = video[i].start + delta)
            for (i in audio.indices) {
                val sound = audio[i]
                if (sound.id !in downstream || sound.followsVideo == content.id) continue
                if (sound.followsVideo?.let { it in moving } ?: (!isOverlay && sound.start >= before.end)) audio[i] = sound.copy(start = sound.start + delta)
            }
            fun moves(relation: TimelineTransition) = relation.outgoing == content.id || (relation.incoming?.let { it in moving } == true)
            val owners = transitions.associateBy { it.id }
            for (i in generated.indices) {
                val region = generated[i]
                if ((!isOverlay && region.interval.start >= before.end) || owners[region.owner]?.let(::moves) == true) {
                    generated[i] = region.copy(interval = EditInterval(region.interval.start + delta, region.interval.end + delta))
                }
            }
            for (i in transitions.indices) {
                val relation = transitions[i]
                if ((!isOverlay && relation.origin >= before.end) || moves(relation)) transitions[i] = relation.copy(origin = relation.origin + delta)
            }
        }
        var result = base.copy(video = video, audio = audio, transitions = transitions, generated = generated)
        clips.forEachIndexed { index, clip ->
            if (clip.id in oldIDs) return@forEachIndexed
            val position = if (index > 0) result.video.firstOrNull { it.id == clips[index - 1].id }?.end ?: result.duration else EditTime.ZERO
            result = insertingTime(result, EditTime.of(clip.duration), position)
            val added = EditState(listOf(clip.copy(openingTransition = null, transition = null, openingTransitionFit = null, transitionFit = null))).toTimeline()
            val layer = (result.video.maxOfOrNull { it.layer } ?: -1) + 1
            result = result.copy(
                video = result.video + added.video.map { it.copy(start = it.start + position, layer = layer) },
                audio = result.audio + added.audio.map { it.copy(start = it.start + position) },
                links = result.links + added.links,
                bookmarks = result.bookmarks + added.bookmarks,
            )
        }
        val ordering = HashMap<String, Int>()
        clips.forEachIndexed { index, clip -> ordering.putIfAbsent(clip.id, index) }
        var kept = result.transitions
        // Only a removal a tool made is honoured. Putting clips in another order never gives a transition to another neighbour.
        val earlier = HashMap<String, TimelineClip>()
        for (clip in previous) earlier.putIfAbsent(clip.id, clip)
        for (clip in clips) {
            val old = earlier[clip.id] ?: continue
            if (old.openingTransition != null && clip.openingTransition == null) kept = kept.filter { !(it.incoming == clip.id && it.outgoing == null) }
            if (old.transition != null && clip.transition == null) kept = kept.filter { it.outgoing != clip.id }
        }
        result = result.copy(video = result.video.sortedBy { ordering[it.id] ?: Int.MAX_VALUE }, transitions = kept)
        result = result.copy(video = TrackMove.promotingNewOverlaps(result.video, retimed, previousItems))
        result = synchronizingFollowers(result)
        return TrackPlacements.retainingAcrossNewOverlaps(result, retimed).pruned()
    }

    /** The picture [id] played at [speed], or as recorded when null, with the ripple of [replacingVideoContents]. Null when [id] is no picture or nothing changes. */
    fun settingSpeed(timeline: TrackTimeline, id: String, speed: SpeedSettings?): TrackTimeline? {
        val clips = timeline.projectedClips
        if (clips.none { it.id == id } || (speed != null && !speed.valid)) return null
        return replacingVideoContents(timeline, clips.map { if (it.id == id) it.copy(speed = speed) else it }).takeIf { it != timeline }
    }

    /** The picture [id] with the source bounds [start]…[end], with the ripple of [replacingVideoContents]. Null when [id] is no picture or nothing changes. */
    fun settingSourceBounds(timeline: TrackTimeline, id: String, start: Double, end: Double): TrackTimeline? {
        val clips = timeline.projectedClips
        if (clips.none { it.id == id } || !start.isFinite() || !end.isFinite() || start < 0 || end <= start) return null
        return replacingVideoContents(timeline, clips.map { if (it.id == id) it.copy(start = start, end = end) else it }).takeIf { it != timeline }
    }
}

/**
 * One boundary of the track timeline, with or without a transition: the
 * original's `TransitionJoin` (YouRec/Transitions.swift:175-194). Unlike
 * [TransitionLayout.Join] it names its pictures by id, since pictures here
 * need not follow one another, and says whether a transition can be set on it.
 */
data class TrackJoin(
    val index: Int,
    /** The moment that counts as the boundary: where the playhead snaps and the marker sits. */
    val time: Double,
    /** The whole affected stretch, picture and sound. */
    val range: ClosedFloatingPointRange<Double>,
    /** The longest duration worth offering for this join. */
    val maximum: Double,
    val transition: TransitionSettings?,
    /** Timeline second where the picture effect starts. */
    val origin: Double = 0.0,
    val timing: TransitionTiming = TransitionTiming(),
    val factor: Double = 1.0,
    /** False for a place between pictures that do not meet: there is nothing to join. */
    val selectable: Boolean = true,
    /** −1 for an opening, +1 for a close, 0 between two pictures. */
    val edge: Int = 0,
    val outgoingID: String? = null,
    val incomingID: String? = null,
) {
    val videoRange: ClosedFloatingPointRange<Double> get() = origin..(origin + timing.videoDuration)
    val audioIn: ClosedFloatingPointRange<Double> get() = (origin + timing.audioIn.start)..(origin + timing.audioIn.endInclusive)
    val audioOut: ClosedFloatingPointRange<Double> get() = (origin + timing.audioOut.start)..(origin + timing.audioOut.endInclusive)
}

/** Where a picture plays, in the order pictures start. YouRec/Transitions.swift:164-174. */
data class TrackPlacement(
    val clip: TimelineClip,
    val index: Int,
    val start: Double,
    /** Seconds it overlaps the picture a transition leads in from, and the one a transition leads out to. */
    val incoming: Double,
    val outgoing: Double,
    /** The boundaries on either side: the stretch this picture owns. */
    val lower: Double,
    val upper: Double,
) {
    val end: Double get() = start + clip.duration
    val range: ClosedFloatingPointRange<Double> get() = lower..maxOf(lower, upper)
}

/**
 * The joins and placements of a track timeline, in playing order, for the
 * controls that think in a sequence: the transition editor, paste targets,
 * the lanes. Read from the stored form: a transition's place and fit are the
 * relation's, nothing is fitted here.
 *
 * Join i, for i up to the number of pictures, is the one into the picture
 * that starts i-th, and the last of those the close of the picture that ends
 * last. After them come the transitions that are on none of those places (an
 * opening or a close that went with a picture that was moved, a transition
 * between pictures that are not neighbours in time), each under a number of
 * its own, and then the plain cut a picture with an opening still makes with
 * the picture that ends where it starts.
 *
 * YouRec/Transitions.swift:271-332, with the queries of :254-268.
 */
class TrackLayout(val placements: List<TrackPlacement>, val joins: List<TrackJoin>, val duration: Double) {
    /** The join at [time] that a transition can be set on; one between two pictures before an opening or a close. */
    fun boundary(time: Double): TrackJoin? {
        val matches = joins.filter { it.selectable && abs(it.time - time) < 0.0001 }
        return matches.firstOrNull { it.edge == 0 } ?: matches.firstOrNull()
    }

    fun placement(time: Double): TrackPlacement? =
        placements.firstOrNull { time >= it.lower && time < it.upper }
            ?: placements.lastOrNull()?.takeIf { abs(time - duration) < 0.00001 && it.upper == duration }

    /** The transition whose stretch contains [time]. */
    fun transition(time: Double): TrackJoin? = joins.firstOrNull { it.transition != null && time >= it.range.start && time < it.range.endInclusive }

    /** The joins at the two ends of [placement]: a transition before a plain cut, and a cut of no length where there is neither. */
    fun joins(placement: TrackPlacement): Pair<TrackJoin, TrackJoin> {
        fun join(incoming: Boolean): TrackJoin {
            val candidates = joins.filter { (if (incoming) it.incomingID else it.outgoingID) == placement.clip.id }
            candidates.firstOrNull { it.transition != null }?.let { return it }
            candidates.firstOrNull()?.let { return it }
            val time = if (incoming) placement.start else placement.end
            return TrackJoin(placement.index + (if (incoming) 0 else 1), time, time..time, 0.0, null)
        }
        return join(true) to join(false)
    }

    companion object {
        private const val LIMIT = EditTime.LIMIT

        fun of(timeline: TrackTimeline): TrackLayout {
            val pictures = timeline.chronologicalVideo
            val duration = timeline.duration.seconds
            if (pictures.isEmpty()) return TrackLayout(emptyList(), emptyList(), duration)
            val clips = timeline.projectedClips
            val byID = HashMap<String, VideoTimelineItem>()
            for (item in pictures) byID.putIfAbsent(item.id, item)
            val ending = HashMap<Long, MutableList<VideoTimelineItem>>()
            for (item in pictures) ending.getOrPut(item.end.ticks) { mutableListOf() } += item
            val into = HashMap<String, TimelineTransition>()
            for (relation in timeline.transitions) relation.incoming?.let { into.putIfAbsent(it, relation) }
            // The picture that ends where [right] starts: the nearest one at or under its layer, else the lowest above it.
            fun meeting(right: VideoTimelineItem): VideoTimelineItem? {
                val candidates = ending[right.start.ticks].orEmpty().filter { it.id != right.id }
                return candidates.filter { it.layer <= right.layer }.maxWithOrNull(VideoTimelineItem.backToFront) ?: candidates.minWithOrNull(VideoTimelineItem.backToFront)
            }
            fun maximum(left: VideoTimelineItem?, right: VideoTimelineItem?) =
                minOf(left?.mapping?.duration?.seconds ?: LIMIT, right?.mapping?.duration?.seconds ?: LIMIT)
            val built = ArrayList<TrackJoin>(pictures.size + 1)
            val placed = HashSet<String>()
            val last = pictures.maxByOrNull { it.end }!!
            for (index in 0..pictures.size) {
                val right = pictures.getOrNull(index)
                val relation = if (right != null) into[right.id] else timeline.transitions.firstOrNull { it.incoming == null && it.outgoing == last.id }
                val left = when {
                    relation != null -> relation.outgoing?.let { byID[it] }
                    right != null && index > 0 -> meeting(right) ?: pictures[index - 1]
                    else -> if (right == null) last else null
                }
                val edge = if (left == null) -1 else if (right == null) 1 else 0
                val timing = TransitionTiming.of(relation?.settings, relation?.factor ?: 1.0, edge)
                val origin = relation?.origin?.seconds ?: left?.end?.seconds ?: right?.start?.seconds ?: 0.0
                val point = if (relation == null) origin else if (edge == -1) right!!.start.seconds else if (edge == 1) left!!.end.seconds else origin + timing.cut
                relation?.let { placed += it.id }
                built += TrackJoin(
                    index, point, maxOf(0.0, origin + timing.lower)..maxOf(0.0, origin + timing.upper), maximum(left, right),
                    relation?.settings, origin, timing, relation?.factor ?: 1.0,
                    selectable = relation != null || (left == null && right?.start == EditTime.ZERO) || right == null || left?.end == right.start,
                    edge = edge, outgoingID = left?.id, incomingID = right?.id,
                )
            }
            // A picture that was moved can leave its fade or its transition away from any place of the sequence. Each stays editable by what it is.
            for (relation in timeline.transitions) {
                if (relation.id in placed) continue
                placed += relation.id
                val left = relation.outgoing?.let { byID[it] }
                val right = relation.incoming?.let { byID[it] }
                val edge = if (left == null) -1 else if (right == null) 1 else 0
                val timing = TransitionTiming.of(relation.settings, relation.factor, edge)
                val origin = relation.origin.seconds
                val end = maxOf(0.0, origin + timing.upper)
                built += TrackJoin(
                    built.size, if (edge == -1) maxOf(0.0, origin) else if (edge == 1) end else origin + timing.cut,
                    maxOf(0.0, origin + timing.lower)..end, maximum(left, right), relation.settings, origin, timing, relation.factor,
                    edge = edge, outgoingID = left?.id, incomingID = right?.id,
                )
            }
            // An opening belongs to its picture; it must not hide the cut the picture makes with the one before it.
            val joined = built.mapTo(HashSet()) { it.outgoingID to it.incomingID }
            for (right in pictures) {
                if (timeline.transitions.none { it.incoming == right.id && it.outgoing == null }) continue
                val left = meeting(right) ?: continue
                if (!joined.add(left.id to right.id)) continue
                val time = right.start.seconds
                built += TrackJoin(built.size, time, time..time, maximum(left, right), null, outgoingID = left.id, incomingID = right.id)
            }
            val entering = HashMap<String, TrackJoin>()
            val leaving = HashMap<String, TrackJoin>()
            for (join in built) {
                if (join.transition == null) continue
                join.incomingID?.let { entering.putIfAbsent(it, join) }
                join.outgoingID?.let { leaving.putIfAbsent(it, join) }
            }
            val placements = pictures.mapIndexed { index, item ->
                val incoming = entering[item.id]
                val outgoing = leaving[item.id]
                TrackPlacement(
                    clips[index], index, item.start.seconds, maxOf(0.0, incoming?.timing?.overlap ?: 0.0), maxOf(0.0, outgoing?.timing?.overlap ?: 0.0),
                    incoming?.time ?: item.start.seconds, outgoing?.time ?: item.end.seconds,
                )
            }
            return TrackLayout(placements, built, duration)
        }
    }
}
