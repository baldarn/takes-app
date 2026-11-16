package app.takes.core

/**
 * A stretch of the timeline where the editor offers Placement: more than one
 * picture shows there, and they are not just the two pictures of a
 * transition, which is edited through its own control. [video] is back to
 * front; [placementID] is the region saved for exactly those pictures, if
 * any. YouRec/VideoTools.swift:612-622.
 */
data class PlacementSpot(val interval: EditInterval, val video: List<String>, val placementID: String?) {
    val participants: Set<String> get() = video.toSet()
}

object PlacementSpots {
    /** Every stretch where Placement is offered, in time order. */
    fun of(timeline: TrackTimeline): List<PlacementSpot> = timeline.compile().mapNotNull { part ->
        if (part.video.size < 2) return@mapNotNull null
        val pair = part.placementID == null && part.video.size == 2 && timeline.transitions.any { relation ->
            relation.id in part.transitionIDs && setOfNotNull(relation.outgoing, relation.incoming) == part.video.toSet()
        }
        if (pair) null else PlacementSpot(part.interval, part.video, part.placementID)
    }

    /** The stretch holding timeline second [time], if Placement is offered there. */
    fun at(timeline: TrackTimeline, time: Double): PlacementSpot? {
        if (!time.isFinite()) return null
        val at = EditTime.of(time)
        return of(timeline).firstOrNull { at in it.interval }
    }
}

/**
 * The Placement tool while it is open: the windows of one set of pictures,
 * not saved until Apply. It starts from the region saved for the set, or
 * from one made from the windows the pictures already have elsewhere
 * ([TrackPlacements.seed]). Apply saves the region in place of the set's,
 * and a transition between two of the pictures goes; Remove takes the
 * set's region away, so the pictures are drawn whole, the upper covering
 * the lower. YouRec/TrackEditors.swift:440-841 without edge crop, reframing
 * and motion.
 *
 * @property members the pictures, back to front as the timeline orders them.
 * @property initial the region the tool opened with.
 * @property region the region as it is now.
 * @property inheritedSeed the set had no region of its own and [initial] is not what is drawn without one: Apply saves it even unchanged.
 * @property selected the picture the gestures and the buttons work on.
 * @property explicitPick [selected] was chosen from the list, so it stays chosen while a touch is inside it, even under another.
 */
data class PlacementDraft(
    val members: List<String>,
    val initial: VideoPlacementRegion,
    val region: VideoPlacementRegion,
    val inheritedSeed: Boolean,
    val selected: String?,
    val explicitPick: Boolean = false,
) {
    val participants: Set<String> get() = region.participants

    /** The pictures back to front as the region draws them. */
    val order: List<String> get() = VideoPlacement.ordered(members, region.videos)

    /** The window of the selected picture. */
    val current: VideoPlacement? get() = selected?.let { region.videos[it] }

    /** "front", "back" or the place from the back, for the picture [id]: 0 is the back. */
    fun depth(id: String): Int = order.indexOf(id)

    /** [id] chosen; [explicit] when from the list rather than by a touch. */
    fun selecting(id: String?, explicit: Boolean = false): PlacementDraft =
        if (id == null || id !in region.videos) copy(explicitPick = false) else copy(selected = id, explicitPick = explicit)

    /** The window of [id] replaced by what [change] makes of it. */
    fun changing(id: String? = selected, change: (VideoPlacement) -> VideoPlacement): PlacementDraft {
        val target = id ?: return this
        val value = region.videos[target] ?: return this
        val next = change(value)
        return if (next == value) this else copy(region = region.copy(videos = region.videos + (target to next)))
    }

    /** A ready-made arrangement, back to front in the order drawn now. A layout resets framing and Fit. */
    fun laidOut(kind: PlacementLayout): PlacementDraft {
        val ids = order
        val videos = LinkedHashMap(region.videos)
        ids.forEachIndexed { index, id -> videos[id] = VideoPlacement.layout(kind, index, ids.size) }
        return copy(region = region.copy(videos = videos))
    }

    fun broughtToFront(): PlacementDraft = changing { PlacementEditing.broughtToFront(it, region.videos.values) }

    fun sentToBack(): PlacementDraft = changing { PlacementEditing.sentToBack(it, region.videos.values) }

    fun filling(fill: Boolean): PlacementDraft = changing { it.copy(fill = fill) }

    /** The picture centered in its window again, at its own size. */
    fun centered(): PlacementDraft = changing { it.copy(framing = TransitionFraming()) }

    /** Whether Apply would replace a transition between two of the pictures. */
    fun replacesTransition(timeline: TrackTimeline): Boolean = timeline.transitions.any { relation ->
        val ids = setOfNotNull(relation.outgoing, relation.incoming)
        ids.size > 1 && participants.containsAll(ids)
    }

    /** Apply on [timeline]: the region saved, or [timeline] itself when nothing would change. */
    fun applied(timeline: TrackTimeline): TrackTimeline =
        if (!inheritedSeed && region == initial) timeline else TrackPlacements.setting(timeline, region)

    /** Remove on [timeline]: the set's region gone. */
    fun removed(timeline: TrackTimeline): TrackTimeline = TrackPlacements.removing(timeline, participants)

    companion object {
        /**
         * The tool opened on the pictures [participants] of [timeline], at
         * timeline second [position]. The picture in front is selected. Null
         * for fewer than two pictures of the timeline.
         */
        fun open(timeline: TrackTimeline, participants: Set<String>, position: Double): PlacementDraft? {
            val members = TrackPlacements.members(timeline, participants).map { it.id }
            if (members.size < 2) return null
            val ids = members.toSet()
            val seed = TrackPlacements.seed(timeline, ids, EditTime.of(if (position.isFinite()) position else 0.0))
            val inherited = timeline.placements.none { it.participants == ids } && seed.videos != TrackPlacements.defaults(members)
            return PlacementDraft(members, seed, seed, inherited, VideoPlacement.ordered(members, seed.videos).last())
        }
    }
}
