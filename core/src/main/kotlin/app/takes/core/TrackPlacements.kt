package app.takes.core

/**
 * The windows of pictures that play together, as relations of the track
 * timeline.
 *
 * A [VideoPlacementRegion] belongs to one exact set of pictures and is in
 * force wherever exactly those play at once; where that is, is derived from
 * the pictures and never stored. Pictures that overlap without a region are
 * drawn whole, the upper covering the lower. So moving, cutting or copying
 * pictures changes which sets exist, and these functions say what becomes of
 * the windows: a new set takes the windows of the pictures it shares with an
 * old one, a cut keeps the windows on both sides, a copy keeps those of the
 * pictures copied. The window itself, and editing it, are [VideoPlacement]
 * and [PlacementEditing].
 *
 * YouRec/TrackTimeline.swift:917-968, :1064-1076; YouRec/TimelinePaste.swift:111-119;
 * YouRec/TrackEditors.swift:548-562; YouRec/VideoTools.swift:521.
 */
object TrackPlacements {
    /** A window is taken from a region that plays this close to the place asked about, in seconds. */
    private const val AT_HAND = 0.00001

    /** The windows pictures have with no region: each whole, in the order given, back to front. */
    fun defaults(backToFront: List<String>): Map<String, VideoPlacement> =
        backToFront.withIndex().associate { (rank, id) -> id to VideoPlacement(order = rank) }

    /** The pictures of [participants] that are on the timeline, back to front. */
    fun members(timeline: TrackTimeline, participants: Set<String>): List<VideoTimelineItem> =
        timeline.video.filter { it.id in participants }.sortedWith(VideoTimelineItem.backToFront)

    /**
     * The region the pictures [participants] start from, at [position]: the
     * one saved for exactly that set, or a new one made from the others.
     *
     * A new set must not reset windows that are already arranged. Each
     * picture takes its window from the region that shares the most pictures
     * with the set (then the one playing nearest to [position], then by id),
     * provided that region shares at least two pictures with the set, or
     * plays at [position], or the picture is one of those being [moving];
     * otherwise it is whole. The order in front and behind starts from the
     * timeline's and takes, among the pictures of each such region, the
     * order that region gives them.
     */
    fun seed(timeline: TrackTimeline, participants: Set<String>, position: EditTime, moving: Set<String> = emptySet()): VideoPlacementRegion {
        timeline.placements.firstOrNull { it.participants == participants }?.let { return it }
        val members = members(timeline, participants)
        val byID = HashMap<String, VideoTimelineItem>()
        for (item in timeline.video) byID.putIfAbsent(item.id, item)
        /** How far from [position] the pictures of [region] all play at once, in seconds; 0 inside. */
        fun distance(region: VideoPlacementRegion): Double {
            val items = region.participants.mapNotNull { byID[it] }
            val lower = items.maxOfOrNull { it.start } ?: EditTime.ZERO
            val upper = items.minOfOrNull { it.end } ?: EditTime.ZERO
            return maxOf(0.0, lower.seconds - position.seconds, position.seconds - upper.seconds)
        }
        class Candidate(val region: VideoPlacementRegion, val shared: Int, val distance: Double)
        val candidates = timeline.placements.mapNotNull { region ->
            val shared = region.participants.count { it in participants }
            if (shared == 0) null else Candidate(region, shared, distance(region))
        }.sortedWith(compareByDescending<Candidate> { it.shared }.thenBy { it.distance }.thenBy { it.region.id })
        val values = LinkedHashMap<String, VideoPlacement>()
        for (item in members) {
            values[item.id] = candidates.firstOrNull {
                it.region.videos[item.id] != null && (item.id in moving || it.shared > 1 || it.distance < AT_HAND)
            }?.region?.videos?.get(item.id) ?: VideoPlacement()
        }
        val order = members.mapTo(ArrayList()) { it.id }
        for (candidate in candidates.asReversed()) {
            val slots = order.indices.filter { order[it] in candidate.region.participants }
            val shared = VideoPlacement.ordered(slots.map { order[it] }, candidate.region.videos)
            for ((slot, id) in slots.zip(shared)) order[slot] = id
        }
        order.forEachIndexed { rank, id -> values[id]?.let { values[id] = it.copy(order = rank) } }
        return VideoPlacementRegion(participants = participants, videos = values)
    }

    /**
     * After the pictures [moving] were moved, lengthened or retimed: every
     * set of pictures they now play with that has no region gets one from
     * [seed], unless that would only say what is drawn anyway. The seeds are
     * taken from the timeline as it is given, so one new region does not
     * colour the next. Nothing is done while the timeline has no regions.
     */
    fun retainingAcrossNewOverlaps(timeline: TrackTimeline, moving: Set<String>): TrackTimeline {
        if (timeline.placements.isEmpty() || moving.isEmpty()) return timeline
        val seen = timeline.placements.mapTo(HashSet()) { it.participants }
        val added = ArrayList<VideoPlacementRegion>()
        for (interval in timeline.compile()) {
            if (interval.video.size < 2 || interval.video.none { it in moving }) continue
            val ids = interval.video.toSet()
            if (!seen.add(ids)) continue
            val seed = seed(timeline, ids, interval.interval.start, moving)
            if (seed.videos != defaults(interval.video)) added += seed
        }
        return if (added.isEmpty()) timeline else timeline.copy(placements = timeline.placements + added)
    }

    /**
     * The stretch to show while the windows of [participants] are edited:
     * where exactly those pictures play, with the cuts that only sounds make
     * taken out, but never across a time where other pictures show. Of
     * several, the one holding [position] (seconds), else the first that
     * reaches into [preferred], else the first.
     */
    fun previewInterval(timeline: TrackTimeline, participants: Set<String>, position: Double, preferred: ClosedFloatingPointRange<Double>): EditInterval? {
        if (participants.size < 2) return null
        val spans = ArrayList<EditInterval>()
        // The stretches come in order and without holes, so those that touch are neighbours.
        for (part in timeline.compile()) {
            if (part.video.size != participants.size || part.video.toSet() != participants) continue
            val last = spans.lastOrNull()
            if (last != null && part.interval.start <= last.end) spans[spans.lastIndex] = last.copy(end = maxOf(last.end, part.interval.end)) else spans += part.interval
        }
        val wanted = EditInterval.of(preferred.start, preferred.endInclusive)
        return spans.firstOrNull { EditTime.of(position) in it } ?: spans.firstOrNull { it.intersection(wanted) != null } ?: spans.firstOrNull()
    }

    /**
     * The regions that are left when only the pictures [ids] are: those
     * whose pictures are all among them, as they are, and for each other
     * region that keeps at least two, a new one with the windows of those.
     */
    fun keeping(timeline: TrackTimeline, ids: Set<String>): List<VideoPlacementRegion> {
        val kept = timeline.placements.filterTo(ArrayList()) { ids.containsAll(it.participants) }
        for (region in timeline.placements) {
            val shared = region.participants.filterTo(LinkedHashSet()) { it in ids }
            if (shared.size < 2 || kept.any { it.participants == shared }) continue
            kept += VideoPlacementRegion(participants = shared, videos = region.videos.filterKeys { it in shared })
        }
        return kept
    }

    /**
     * The regions a cut adds. [children] is the later piece of each picture
     * that was cut, by the picture's id. Every later piece begins at the cut,
     * so a region of cut pictures goes on after it among their later pieces
     * and the pictures that were not cut, with the same windows; the saved
     * region stays for the earlier pieces. The prune then drops whichever of
     * the two no longer plays together.
     */
    fun carriedAcrossSplit(placements: List<VideoPlacementRegion>, children: Map<String, String>): List<VideoPlacementRegion> =
        placements.mapNotNull { region ->
            if (region.participants.none { it in children }) return@mapNotNull null
            VideoPlacementRegion(
                participants = region.participants.mapTo(LinkedHashSet()) { children[it] ?: it },
                videos = region.videos.entries.associate { (id, value) -> (children[id] ?: id) to value },
            )
        }

    /** The pictures of [region] back to front as it draws them: by its order, and by the timeline's where it gives two the same. */
    fun order(timeline: TrackTimeline, region: VideoPlacementRegion): List<String> =
        VideoPlacement.ordered(members(timeline, region.participants).map { it.id }, region.videos)

    /**
     * Saves [region] for its set of pictures, in place of the one that set
     * had. A placement and a transition are two treatments of the same
     * shared time: a transition between two of the pictures goes.
     */
    fun setting(timeline: TrackTimeline, region: VideoPlacementRegion): TrackTimeline = timeline.copy(
        placements = timeline.placements.filter { it.participants != region.participants } + region,
        transitions = timeline.transitions.filter { relation ->
            val ids = setOfNotNull(relation.outgoing, relation.incoming)
            !(ids.size > 1 && region.participants.containsAll(ids))
        },
    ).pruned()

    /** Removes the region [id]: its pictures are drawn whole again, the upper covering the lower. */
    fun removing(timeline: TrackTimeline, id: String): TrackTimeline =
        if (timeline.placements.none { it.id == id }) timeline else timeline.copy(placements = timeline.placements.filter { it.id != id })

    /** Removes the region of exactly the pictures [participants]. */
    fun removing(timeline: TrackTimeline, participants: Set<String>): TrackTimeline =
        if (timeline.placements.none { it.participants == participants }) timeline else timeline.copy(placements = timeline.placements.filter { it.participants != participants })
}
