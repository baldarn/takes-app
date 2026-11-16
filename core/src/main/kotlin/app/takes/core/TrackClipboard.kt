package app.takes.core

import kotlin.math.abs

/**
 * What was copied or cut: a timeline of its own that begins at zero, with the
 * links, transitions, placements and bookmarks that lie wholly inside it.
 * [insertsTime] is true when it holds a picture linked with a sound: pasting
 * that opens the sequence, while a picture or a sound by itself is laid over
 * what is there. YouRec/TrackTimeline.swift:1457-1461.
 */
data class TimelineClipboard(val timeline: TrackTimeline, val insertsTime: Boolean) {
    val isEmpty: Boolean get() = timeline.video.isEmpty() && timeline.audio.isEmpty()
}

/**
 * A place a clipboard can be pasted, on the exact clock. Where a cut of the
 * picture and a cut of independent sound fall on one tick, they are two
 * targets, and the user chooses which sequence is opened.
 * YouRec/TimelinePaste.swift:5-36.
 */
data class TimelinePasteTarget(val kind: Kind, val time: EditTime, val anchor: Anchor, val transition: String? = null) {
    /** The sequence the target belongs to: the picture, the sounds that are linked to no picture, or the timeline as a whole. */
    enum class Kind { Picture, Sound, Project }

    sealed interface Anchor {
        /** The cut between two items. */
        data class Between(val left: String, val right: String) : Anchor

        data object Start : Anchor
        data object End : Anchor

        /** The stretch without picture after the picture ending at the target's time. */
        data object Gap : Anchor
    }

    /**
     * True when the playhead at [playhead] is on this target: within
     * [REACH] ticks of it, or on the first frame of a 30 a second display
     * at or after it, which is where stepping to the next change stops when
     * the cut lies between two frames.
     */
    fun isAt(playhead: EditTime): Boolean {
        val displayFrame = (time.ticks + FRAME_TICKS - 1) / FRAME_TICKS * FRAME_TICKS
        return abs(time.ticks - playhead.ticks) < REACH || playhead.ticks == displayFrame
    }

    /**
     * What the target is called, for the editor to put in its own words:
     * [Label.Key.Start], [Label.Key.End] and [Label.Key.Gap] alone, or
     * [Label.Key.Video] and [Label.Key.Audio] with the numbers of the two
     * items, from 1 in the order the timeline keeps them (0 for one that is
     * not there). [label] is the original's English.
     */
    fun labelKey(timeline: TrackTimeline): Label = when (anchor) {
        Anchor.Start -> Label(Label.Key.Start)
        Anchor.End -> Label(Label.Key.End)
        Anchor.Gap -> Label(Label.Key.Gap)
        is Anchor.Between -> {
            val picture = kind == Kind.Picture
            val ids = if (picture) timeline.video.map { it.id } else timeline.audio.map { it.id }
            Label(if (picture) Label.Key.Video else Label.Key.Audio, ids.indexOf(anchor.left) + 1, ids.indexOf(anchor.right) + 1)
        }
    }

    /** See [labelKey]: [left] and [right] are 0 for the keys that take no numbers. */
    data class Label(val key: Key, val left: Int = 0, val right: Int = 0) {
        enum class Key { Start, End, Gap, Video, Audio }
    }

    /** "Start", "End", "Gap", or the two items by their numbers: "Video · V2 | V3". */
    fun label(timeline: TrackTimeline): String = when (anchor) {
        Anchor.Start -> "Start"
        Anchor.End -> "End"
        Anchor.Gap -> "Gap"
        is Anchor.Between -> {
            val picture = kind == Kind.Picture
            val ids = if (picture) timeline.video.map { it.id } else timeline.audio.map { it.id }
            val prefix = if (picture) "V" else "A"
            fun number(id: String) = ids.indexOf(id).let { if (it >= 0) prefix + (it + 1) else prefix }
            (if (picture) "Video" else "Audio") + " · " + number(anchor.left) + " | " + number(anchor.right)
        }
    }

    companion object {
        /** YouRec/TimelinePaste.swift:21. */
        const val REACH = 24L

        /** One frame of the display clock, 1/30 s (YouRec/TimelinePaste.swift:19). */
        const val FRAME_TICKS = EditTime.SCALE / 30
    }
}

/** See [TrackClipboard.copied]. */
fun TrackTimeline.copied(targets: TimelineSelection): TimelineClipboard = TrackClipboard.copied(this, targets)

/** See [TrackClipboard.pasteTargets]. */
fun TrackTimeline.pasteTargets(): List<TimelinePasteTarget> = TrackClipboard.pasteTargets(this)

/** See [TrackClipboard.pasting]. */
fun TrackTimeline.pasting(clipboard: TimelineClipboard, point: EditTime, target: TimelinePasteTarget? = null): SelectedTimeline =
    TrackClipboard.pasting(this, clipboard, point, target)

/**
 * Copy, Cut and Paste on the track timeline.
 *
 * YouRec/TrackTimeline.swift:1529-1584 and YouRec/TimelinePaste.swift:38-194.
 */
object TrackClipboard {
    /** What a cut leaves: the timeline without the pieces, the pieces, and where the playhead goes. */
    data class Cut(val timeline: TrackTimeline, val clipboard: TimelineClipboard, val position: EditTime)

    /**
     * The places a clipboard can go.
     *
     *  - Every cut of the picture sequence where two pictures meet, or are
     *    joined by a transition (then with the transition's id).
     *  - Every cut between two sounds that are linked to no picture.
     *  - The start and the end of the picture.
     *  - The start and the end of the timeline, where those are not the
     *    picture's: sound can begin before the first picture and go on after
     *    the last. With no picture at all they belong to the sound.
     */
    fun pasteTargets(timeline: TrackTimeline): List<TimelinePasteTarget> {
        val cuts = ArrayList<TimelinePasteTarget>()
        for (join in TrackLayout.of(timeline).joins) {
            if (!join.selectable) continue
            val left = join.outgoingID ?: continue
            val right = join.incomingID ?: continue
            cuts += TimelinePasteTarget(TimelinePasteTarget.Kind.Picture, EditTime.of(join.time), TimelinePasteTarget.Anchor.Between(left, right), join.transition?.id)
        }
        val pictureIDs = timeline.video.mapTo(HashSet()) { it.id }
        val linked = HashSet<String>()
        for (group in timeline.links) if (group.members.any { it in pictureIDs }) linked += group.members
        val independent = timeline.audio.filter { it.id !in linked }
        val starts = independent.groupBy { it.start }
        val ends = independent.groupBy { it.end }
        for (time in ends.keys.sorted()) {
            val right = starts[time] ?: continue
            val pairs = TrackMove.neighbors(
                ends.getValue(time).map { TrackMove.CutNeighbor(it.id, it.occurrenceID) },
                right.map { TrackMove.CutNeighbor(it.id, it.occurrenceID) },
            )
            for ((a, b) in pairs) cuts += TimelinePasteTarget(TimelinePasteTarget.Kind.Sound, time, TimelinePasteTarget.Anchor.Between(a, b))
        }
        if (timeline.video.isEmpty() && timeline.audio.isEmpty()) return cuts
        val coverage = timeline.videoCoverage
        val first = coverage.firstOrNull()
        val last = coverage.lastOrNull()
        if (first != null && last != null) {
            cuts += TimelinePasteTarget(TimelinePasteTarget.Kind.Picture, first.start, TimelinePasteTarget.Anchor.Start)
            cuts += TimelinePasteTarget(TimelinePasteTarget.Kind.Picture, last.end, TimelinePasteTarget.Anchor.End)
        }
        val outer = if (timeline.video.isEmpty()) TimelinePasteTarget.Kind.Sound else TimelinePasteTarget.Kind.Project
        val duration = timeline.duration
        if (first?.start != EditTime.ZERO) cuts += TimelinePasteTarget(outer, EditTime.ZERO, TimelinePasteTarget.Anchor.Start)
        if (last?.end != duration) cuts += TimelinePasteTarget(outer, duration, TimelinePasteTarget.Anchor.End)
        return cuts
    }

    /**
     * The targets the playhead at [playhead] is on ([TimelinePasteTarget.isAt]),
     * in the order of [pasteTargets]: none pastes at the playhead, one is
     * the Paste button's, several are the menu of Paste. While playing or
     * moving the editor offers none. YouRec/EditorView.swift:631-632, :775-798.
     */
    fun targetsAt(timeline: TrackTimeline, playhead: EditTime, targets: List<TimelinePasteTarget> = pasteTargets(timeline)): List<TimelinePasteTarget> =
        targets.filter { it.isAt(playhead) }

    /**
     * True when pasting [clipboard] at [playhead] on [targets] (those
     * [targetsAt] gives) undoes a transition, which the Paste button says
     * ("Replaces transition"): the clipboard opens the picture sequence,
     * because it is between two pictures or, with no target, inserts time,
     * and the playhead is inside a transition or a target carries one.
     * YouRec/EditorView.swift:775-780.
     */
    fun replacesTransition(timeline: TrackTimeline, clipboard: TimelineClipboard, playhead: EditTime, targets: List<TimelinePasteTarget>): Boolean {
        if (clipboard.timeline.video.isEmpty()) return false
        val opens = if (targets.isEmpty()) clipboard.insertsTime else targets.any { it.anchor is TimelinePasteTarget.Anchor.Between }
        if (!opens) return false
        return timeline.generated.any { it.interval.start < playhead && playhead < it.interval.end } || targets.any { it.transition != null }
    }

    /**
     * The target of "Paste here" on an add button of the timeline: the
     * picture's start or end (the timeline's when there is no picture), or
     * the gap the button is in. With an empty timeline the start has no
     * target and pastes at zero. YouRec/EditorView.swift:871-879.
     */
    fun target(timeline: TrackTimeline, site: VideoInsertionSite, targets: List<TimelinePasteTarget> = pasteTargets(timeline)): TimelinePasteTarget? = when (site) {
        VideoInsertionSite.Start -> targets.firstOrNull { it.kind != TimelinePasteTarget.Kind.Project && it.anchor == TimelinePasteTarget.Anchor.Start }
        VideoInsertionSite.End -> targets.firstOrNull { it.kind != TimelinePasteTarget.Kind.Project && it.anchor == TimelinePasteTarget.Anchor.End }
        is VideoInsertionSite.Gap -> TimelinePasteTarget(TimelinePasteTarget.Kind.Picture, EditTime.of(site.after), TimelinePasteTarget.Anchor.Gap)
    }

    /**
     * The items [selected] as a clipboard: only they are kept, with the
     * relations that still hold among them. Bookmarks attached to them come
     * along; those on the timeline itself do not. The fragment begins at
     * zero. Empty time between selections that have nothing to do with one
     * another is taken out, while overlaps, and the offsets inside a link,
     * stay; with [keepGaps] (a marked range) the gaps stay too. The caption
     * settings come along only with words.
     *
     * [selected] is taken as it is, with no range: cut the items first.
     * YouRec/TimelinePaste.swift:70-109.
     */
    fun fragment(timeline: TrackTimeline, selected: TimelineSelection, keepGaps: Boolean): TimelineClipboard {
        var fragment = timeline.copy(
            video = timeline.video.filter { it.id in selected.video },
            audio = timeline.audio.filter { it.id in selected.audio },
            placements = TrackPlacements.keeping(timeline, selected.video),
        ).pruned()
        val visible = fragment.resolvedBookmarks.mapTo(HashSet()) { it.id }
        fragment = fragment.copy(bookmarks = fragment.bookmarks.filter { it.anchor !is BookmarkAnchor.Project && it.id in visible })
        val spans = LinkedHashMap<String, EditInterval>()
        for (item in fragment.video) spans[item.id] = item.interval
        for (item in fragment.audio) spans[item.id] = item.interval
        val occupied = ArrayList(spans.values)
        fragment.generated.mapTo(occupied) { it.interval }
        for (group in fragment.links) {
            val ranges = group.members.mapNotNull { spans[it] }
            if (ranges.isNotEmpty()) occupied += EditInterval(ranges.minOf { it.start }, ranges.maxOf { it.end })
        }
        var islands = TrackEditing.union(occupied)
        if (keepGaps && islands.isNotEmpty()) islands = listOf(EditInterval(islands.first().start, islands.last().end))
        if (islands.isNotEmpty()) {
            var island = 0
            var offset = EditTime.ZERO - islands[0].start
            val starts = HashMap<String, EditTime>()
            for ((id, range) in spans.entries.sortedBy { it.value.start }) {
                while (island + 1 < islands.size && range.start >= islands[island + 1].start) {
                    offset -= islands[island + 1].start - islands[island].end
                    island += 1
                }
                starts[id] = range.start + offset
            }
            fragment = fragment.copy(
                video = fragment.video.map { it.copy(start = starts.getValue(it.id)) },
                audio = fragment.audio.map { it.copy(start = starts.getValue(it.id)) },
            ).pruned()
        }
        if (fragment.audio.none { !it.subtitleWords.isNullOrEmpty() }) fragment = fragment.copy(subtitles = null)
        val pictureIDs = fragment.video.mapTo(HashSet()) { it.id }
        val soundIDs = fragment.audio.mapTo(HashSet()) { it.id }
        val inserts = fragment.links.any { link -> link.members.any { it in pictureIDs } && link.members.any { it in soundIDs } }
        return TimelineClipboard(fragment, inserts)
    }

    /**
     * A copy of [targets]: its items with what they are linked to, or, with
     * a range, the part of them inside it. The timeline is not changed.
     * YouRec/TrackTimeline.swift:1529-1532.
     */
    fun copied(timeline: TrackTimeline, targets: TimelineSelection): TimelineClipboard {
        val scoped = TrackEditing.scoped(timeline, targets)
        return fragment(scoped.timeline, scoped.selection, keepGaps = targets.range != null)
    }

    /**
     * Lifts [targets] out: the clipboard holds the very pieces that are
     * deleted. The deletion is an ordinary one: a link taken whole closes its
     * time, a picture or a sound by itself leaves a gap. [point] is the
     * playhead. Null when there is nothing to lift.
     * YouRec/TimelinePaste.swift:123-134.
     */
    fun cut(timeline: TrackTimeline, targets: TimelineSelection, point: EditTime): Cut? {
        val scoped = TrackEditing.scoped(timeline, targets)
        val clip = fragment(scoped.timeline, scoped.selection, keepGaps = targets.range != null)
        if (clip.isEmpty) return null
        val staying = scoped.timeline.video.mapTo(LinkedHashSet()) { it.id }.apply { removeAll(scoped.selection.video) }
        val removal = TrackEditing.removing(scoped.timeline.copy(placements = TrackPlacements.keeping(scoped.timeline, staying)), scoped.selection, point)
        val copiedMarks = clip.timeline.bookmarks.mapTo(HashSet()) { it.id }
        val remainingMarks = removal.timeline.resolvedBookmarks.mapTo(HashSet()) { it.id }
        val result = removal.timeline.copy(bookmarks = removal.timeline.bookmarks.filter { !(it.id in copiedMarks && it.id !in remainingMarks) })
        return Cut(result, clip, removal.position ?: point)
    }

    /** A timeline made ready for a paste, and where the pasted material begins. */
    class Prepared(val timeline: TrackTimeline, val position: EditTime)

    /**
     * Makes room for [clipboard] and says where it goes.
     *
     * Without a [target], at [point], kept inside the timeline: a clipboard
     * that inserts time opens the picture sequence there, any other is laid
     * over what is there.
     *
     * With one:
     *  - at the start, or in a gap, a clipboard with picture uses the free
     *    time before the picture after it and moves later pictures only by
     *    what does not fit ([InsertionSites.placement]); a project target
     *    puts it at zero. Sound alone at the start of the sound sequence
     *    moves everything later until it fits before the first sound;
     *  - at the end, nothing moves;
     *  - between two items, a clipboard with picture opens the picture
     *    sequence there; sound alone, on a sound target, moves the sounds
     *    that follow in that run later. On a picture or project target sound
     *    is laid over.
     *
     * YouRec/TimelinePaste.swift:136-172.
     */
    fun preparing(timeline: TrackTimeline, clipboard: TimelineClipboard, point: EditTime, target: TimelinePasteTarget?): Prepared {
        val length = clipboard.timeline.duration
        if (target == null) {
            val position = maxOf(EditTime.ZERO, minOf(timeline.duration, point))
            return if (clipboard.insertsTime) insertingPictureTime(timeline, length, position) else Prepared(timeline, position)
        }
        val hasPicture = clipboard.timeline.video.isNotEmpty()
        val picture = TimelinePasteTarget.Kind.Picture
        val sound = TimelinePasteTarget.Kind.Sound
        return when (val anchor = target.anchor) {
            TimelinePasteTarget.Anchor.Start, TimelinePasteTarget.Anchor.Gap -> when {
                hasPicture -> {
                    val gap = anchor == TimelinePasteTarget.Anchor.Gap
                    val site = if (gap) VideoInsertionSite.Gap(target.time.seconds) else VideoInsertionSite.Start
                    val coverage = timeline.videoCoverage.map { InsertionSites.Span(it.start.seconds, it.end.seconds) }
                    val placement = InsertionSites.placement(site, coverage, length.seconds)
                    val ripple = EditTime.of(placement.ripple)
                    val destination = if (target.kind == TimelinePasteTarget.Kind.Project) EditTime.ZERO else EditTime.of(placement.start)
                    val made = when {
                        ripple <= EditTime.ZERO -> timeline
                        gap -> TrackEditing.insertingTime(timeline, ripple, EditTime.of(placement.rippleAt), atPictureGap = true)
                        else -> TrackMove.moved(timeline, TimelineSelection(video = timeline.video.mapTo(LinkedHashSet()) { it.id }), ripple)
                    }
                    Prepared(made, destination)
                }
                target.kind == sound -> {
                    val leadingSilence = timeline.audio.minOfOrNull { it.start } ?: EditTime.ZERO
                    Prepared(TrackMove.moved(timeline, TrackLinking.entireSelection(timeline), maxOf(EditTime.ZERO, length - leadingSilence)), target.time)
                }
                else -> Prepared(timeline, target.time)
            }
            TimelinePasteTarget.Anchor.End -> Prepared(timeline, target.time)
            is TimelinePasteTarget.Anchor.Between -> when {
                hasPicture -> insertingPictureTime(timeline, length, target.time, if (target.kind == picture) anchor.right else null, target.transition)
                target.kind == sound -> {
                    val destination = target.time
                    val chain = TrackMove.choices(timeline, TrackLinking.selection(timeline, anchor.right)).right.audio
                    val tail = timeline.audio.filter { it.id in chain && it.start >= destination }.mapTo(LinkedHashSet()) { it.id }
                    val cut = TrackEditing.split(timeline, destination, TimelineSelection(audio = tail)).timeline
                    Prepared(cut.copy(audio = cut.audio.map { if (it.id in tail) it.copy(start = it.start + length) else it }), destination)
                }
                else -> Prepared(timeline, target.time)
            }
        }
    }

    /**
     * Opens [length] in the picture sequence at [point]: the pictures are
     * cut there, and those from there on move later with what they are
     * linked to. Sound that is linked to no picture stays where it is and
     * plays on through. A transition that [point] falls inside, and the one
     * named by [transition], are removed first with their straight cut
     * restored, so both pictures get all their footage back; then the place
     * is where [pictureOnRight] begins.
     * YouRec/TimelinePaste.swift:176-194.
     */
    private fun insertingPictureTime(timeline: TrackTimeline, length: EditTime, point: EditTime, pictureOnRight: String? = null, transition: String? = null): Prepared {
        val broken = timeline.generated.filter { it.interval.start < point && point < it.interval.end }.mapTo(HashSet()) { it.owner }
        transition?.let { broken += it }
        var result = timeline
        for (id in timeline.transitions.map { it.id }) if (id in broken) result = TrackTransitions.removing(result, id)
        val position = pictureOnRight?.let { id -> result.video.firstOrNull { it.id == id }?.start } ?: point
        result = TrackEditing.split(result, position, TimelineSelection(video = result.video.mapTo(LinkedHashSet()) { it.id })).timeline
        val tail = TrackLinking.expanded(result, TimelineSelection(video = result.video.filter { it.start >= position }.mapTo(LinkedHashSet()) { it.id }))
        return Prepared(
            result.copy(
                video = result.video.map { if (it.id in tail.video) it.copy(start = it.start + length) else it },
                audio = result.audio.map { if (it.id in tail.audio) it.copy(start = it.start + length) else it },
                bookmarks = TrackBookmarks.shiftingProjectMarks(result.bookmarks, position, length),
            ),
            position,
        )
    }

    /**
     * Pastes [clipboard] at [target], or at [point] with none
     * ([preparing]), and answers the timeline with what was added, selected.
     *
     * Every paste is a copy of its own: the items, their occurrences, the
     * settings and titles of the pictures, the words, censors and chains of
     * the sounds, the links, transitions, placements and bookmarks all get
     * new ids, so editing one copy never edits another or the original. The
     * pasted pictures go in front of everything, keeping their order among
     * themselves. Caption settings are taken from the clipboard only when
     * the timeline has none, and then as not yet matching the timing.
     *
     * An empty clipboard changes nothing.
     * YouRec/TrackTimeline.swift:1533-1584.
     */
    fun pasting(timeline: TrackTimeline, clipboard: TimelineClipboard, point: EditTime, target: TimelinePasteTarget? = null): SelectedTimeline {
        if (clipboard.isEmpty) return SelectedTimeline(timeline, TimelineSelection())
        val addition = clipboard.timeline
        val identifiers = HashMap<String, String>()
        fun fresh(old: String): String = identifiers.getOrPut(old) { newID() }
        val prepared = preparing(timeline, clipboard, point, target)
        val base = prepared.timeline
        val position = prepared.position
        val front = (base.video.maxOfOrNull { it.layer } ?: 0) + 1
        val layers = addition.video.map { it.layer }.toSortedSet().withIndex().associate { it.value to front + it.index }
        val video = addition.video.map { item ->
            val content = item.content
            item.copy(
                occurrenceID = fresh(item.occurrenceID),
                start = item.start + position,
                layer = layers.getValue(item.layer),
                content = content.copy(
                    id = fresh(item.id),
                    speed = content.speed?.let { it.copy(id = fresh(it.id)) },
                    crop = content.crop?.let { it.copy(id = fresh(it.id)) },
                    mirror = content.mirror?.let { it.copy(id = fresh(it.id)) },
                    color = content.color?.let { it.copy(id = fresh(it.id)) },
                    texts = content.texts?.map { it.copy(id = fresh(it.id)) },
                ),
            )
        }
        val audio = addition.audio.map { item ->
            item.copy(
                id = fresh(item.id),
                occurrenceID = fresh(item.occurrenceID),
                followsVideo = item.followsVideo?.let(::fresh),
                start = item.start + position,
                subtitleWords = item.subtitleWords?.map { it.copy(id = fresh(it.id), phraseID = fresh(it.phraseID)) },
                enhancements = item.enhancements.map { it.copy(id = fresh(it.id)) },
                censorRegions = item.censorRegions?.map { it.copy(id = fresh(it.id)) },
            )
        }
        val links = addition.links.map { TimelineLink(members = it.members.mapTo(LinkedHashSet(), ::fresh)) }
        val transitions = addition.transitions.map { relation ->
            relation.copy(
                // The settings name the next picture for the sequence form; that is the copy now.
                settings = relation.settings.copy(id = fresh(relation.id), nextClipID = relation.settings.nextClipID?.let { identifiers[it] ?: it }),
                outgoing = relation.outgoing?.let(::fresh),
                incoming = relation.incoming?.let(::fresh),
                origin = relation.origin + position,
            )
        }
        val placements = addition.placements.map { region ->
            VideoPlacementRegion(participants = region.participants.mapTo(LinkedHashSet(), ::fresh), videos = region.videos.entries.associate { fresh(it.key) to it.value })
        }
        val bookmarks = addition.bookmarks.map { mark ->
            mark.copy(
                id = newID(),
                anchor = when (val anchor = mark.anchor) {
                    is BookmarkAnchor.Source -> anchor.copy(occurrence = fresh(anchor.occurrence))
                    is BookmarkAnchor.Local -> anchor.copy(occurrence = fresh(anchor.occurrence))
                    is BookmarkAnchor.Project -> BookmarkAnchor.Project(anchor.time + position)
                },
            )
        }
        val result = base.copy(
            video = (base.video + video).sortedBy { it.start },
            audio = base.audio + audio,
            links = base.links + links,
            transitions = base.transitions + transitions,
            placements = base.placements + placements,
            bookmarks = base.bookmarks + bookmarks,
            subtitles = base.subtitles ?: addition.subtitles?.copy(timingFingerprint = ""),
        ).pruned()
        return SelectedTimeline(result, TimelineSelection(video.mapTo(LinkedHashSet()) { it.id }, audio.mapTo(LinkedHashSet()) { it.id }))
    }
}
