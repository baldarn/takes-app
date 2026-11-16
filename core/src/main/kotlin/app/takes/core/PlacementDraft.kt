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
 * the lower. YouRec/TrackEditors.swift:440-841.
 *
 * The windows in [region] are fixed poses: what the controls and the
 * gestures change. While the pictures move ([motions]), each window is the
 * end of its motion being edited, and Apply writes the motion's A end into
 * the window with the motion beside it, its clock tied to the picture's
 * footage from the start of the overlap (`state()`, :543-565).
 *
 * @property members the pictures, back to front as the timeline orders them.
 * @property initial the region the tool opened with.
 * @property region the region as it is now.
 * @property inheritedSeed the set had no region of its own and [initial] is not what is drawn without one: Apply saves it even unchanged.
 * @property selected the picture the gestures and the buttons work on.
 * @property explicitPick [selected] was chosen from the list, so it stays chosen while a touch is inside it, even under another.
 * @property reframe "Reframe inside window": a drag moves the picture inside its window and a pinch zooms it, the window stays.
 * @property lower the first timeline second the tool previews: where the pictures start to play together.
 * @property upper the last: a frame before they stop.
 * @property motions the motion of each picture that moves, by id; every picture has one once an end has been chosen.
 * @property endpoint the end of the motions being edited, shown in the menu.
 * @property previewEndpoint the end the preview is parked on. Scrubbing or playing clears it.
 * @property lastEndpoint the end last chosen, which a change of a window edits when nothing is chosen.
 */
data class PlacementDraft(
    val members: List<String>,
    val initial: VideoPlacementRegion,
    val region: VideoPlacementRegion,
    val inheritedSeed: Boolean,
    val selected: String?,
    val explicitPick: Boolean = false,
    val reframe: Boolean = false,
    val lower: Double = 0.0,
    val upper: Double = lower,
    val motions: Map<String, EffectAnimation> = emptyMap(),
    val endpoint: EffectEndpoint? = null,
    val previewEndpoint: EffectEndpoint? = null,
    val lastEndpoint: EffectEndpoint = EffectEndpoint.A,
) {
    val participants: Set<String> get() = region.participants

    /** The pictures back to front as the region draws them. */
    val order: List<String> get() = VideoPlacement.ordered(members, region.videos)

    /** The window of the selected picture. */
    val current: VideoPlacement? get() = selected?.let { region.videos[it] }

    /** How long the motions run: the whole time the pictures play together (`motionDuration`, :500). */
    val motionDuration: Double get() = upper - lower + FRAME

    /** The motion the controls show: the selected picture's, or any (`menuMotion`, :501). */
    val menuMotion: EffectAnimation? get() = selected?.let { motions[it] } ?: motions.values.firstOrNull()

    /** "front", "back" or the place from the back, for the picture [id]: 0 is the back. */
    fun depth(id: String): Int = order.indexOf(id)

    /** [id] chosen; [explicit] when from the list rather than by a touch. */
    fun selecting(id: String?, explicit: Boolean = false): PlacementDraft =
        if (id == null || id !in region.videos) copy(explicitPick = false) else copy(selected = id, explicitPick = explicit)

    /**
     * The windows replaced by [videos]. With the pictures moving, a change
     * made while the preview is not on an end edits the end last chosen and
     * goes back to it (`onChange(of: placement.videos)`, :725-729).
     */
    private fun withVideos(videos: Map<String, VideoPlacement>): PlacementDraft {
        val next = copy(region = region.copy(videos = videos))
        return if (motions.isNotEmpty() && previewEndpoint == null) next.copy(endpoint = lastEndpoint, previewEndpoint = lastEndpoint) else next
    }

    /** The window of [id] replaced by what [change] makes of it. */
    fun changing(id: String? = selected, change: (VideoPlacement) -> VideoPlacement): PlacementDraft {
        val target = id ?: return this
        val value = region.videos[target] ?: return this
        val next = change(value)
        return if (next == value) this else withVideos(region.videos + (target to next))
    }

    /** A ready-made arrangement, back to front in the order drawn now. A layout resets crop, framing and Fit. */
    fun laidOut(kind: PlacementLayout): PlacementDraft {
        val ids = order
        val videos = LinkedHashMap(region.videos)
        ids.forEachIndexed { index, id -> videos[id] = VideoPlacement.layout(kind, index, ids.size) }
        return withVideos(videos)
    }

    fun broughtToFront(): PlacementDraft = changing { PlacementEditing.broughtToFront(it, region.videos.values) }

    fun sentToBack(): PlacementDraft = changing { PlacementEditing.sentToBack(it, region.videos.values) }

    fun filling(fill: Boolean): PlacementDraft = changing { it.copy(fill = fill) }

    /** The picture centered in its window again, at its own size. */
    fun centered(): PlacementDraft = changing { it.copy(framing = TransitionFraming()) }

    /** "Reset crop": the hidden margins shown again, framing and order kept. */
    fun cropReset(): PlacementDraft = changing { it.copy(edgeCrop = null) }

    /** "Reframe inside window" on or off. */
    fun reframing(on: Boolean): PlacementDraft = copy(reframe = on)

    // MARK: Motion (YouRec/TrackEditors.swift:500-542)

    /** The motions with the window on screen written into the end being edited (`captureEndpoints`). */
    fun captured(): Map<String, EffectAnimation> = motions.mapValues { (id, motion) ->
        region.videos[id]?.let { motion.with(endpoint ?: lastEndpoint, it.pose) } ?: motion
    }

    /**
     * Starts editing the end [end] of every picture's motion; the first time,
     * each picture gets a motion whose two ends are its window now. The
     * windows show that end and the preview parks on it.
     */
    fun selectingEnd(end: EffectEndpoint): PlacementDraft {
        val captured = HashMap(captured())
        val videos = LinkedHashMap(region.videos)
        for (id in members) {
            val value = videos[id] ?: continue
            val motion = captured[id] ?: EffectAnimation(value.pose, motionDuration)
            captured[id] = motion
            videos[id] = value.applying(motion[end])
        }
        return copy(region = region.copy(videos = videos), motions = captured, endpoint = end, previewEndpoint = end, lastEndpoint = end)
    }

    /** "Done": keeps the motions and leaves the ends. */
    fun finishing(): PlacementDraft = copy(motions = captured(), endpoint = null, previewEndpoint = null)

    /** "Remove animation": the windows on screen stay, fixed. */
    fun removingAnimation(): PlacementDraft = copy(
        motions = emptyMap(), endpoint = null, previewEndpoint = null,
        region = region.copy(videos = region.videos.mapValues { it.value.copy(motion = null) }),
    )

    /** A change of curve, rise, return or ramp, for every picture's motion. */
    fun retimed(timing: EffectTiming): PlacementDraft {
        val motion = menuMotion ?: return this
        if (timing == motion.timing) return this
        return copy(motions = captured().mapValues { it.value.copy(timing = timing) })
    }

    /** The preview moved by hand or played: it no longer stands on an end. */
    fun scrubbed(): PlacementDraft = if (previewEndpoint == null) this else copy(previewEndpoint = null)

    /** Playing stopped: back to the end being edited, if one is (`finishPreview`, :765-771). */
    fun stopped(): PlacementDraft = if (endpoint != null && previewEndpoint == null) copy(previewEndpoint = endpoint) else this

    /**
     * The timeline second the preview stands at while parked on an end of the
     * motion, which follows the peak as the timing changes; null when it is
     * not parked (`returnToEndpoint`, :518-524).
     */
    fun parkedAt(): Double? {
        val end = previewEndpoint ?: return null
        val motion = menuMotion ?: return null
        return lower + motion.timing.endpoint(end, motionDuration, motion.frame)
    }

    /** Where Play preview starts with the playhead at [time]: the overlap's start when an end is being edited or the playhead is past it. */
    fun playFrom(time: Double): Double = if (endpoint != null || time >= upper - 0.02 || time < lower) lower else time

    /** [time] kept inside the stretch the tool previews. */
    fun within(time: Double): Double = minOf(upper, maxOf(lower, time))

    /**
     * The region Apply saves on [timeline]: each moving picture's window at
     * the A end of its motion, the motion tied to the picture's footage so
     * that it starts with the overlap. A motion whose ends and timing did
     * not change keeps its clock.
     */
    fun saved(timeline: TrackTimeline): VideoPlacementRegion {
        if (motions.isEmpty()) return region
        val captured = captured()
        val items = timeline.video.associateBy { it.id }
        val videos = LinkedHashMap(region.videos)
        for (id in members) {
            val motion = captured[id]?.copy(duration = motionDuration) ?: continue
            val value = videos[id] ?: continue
            val item = items[id] ?: continue
            videos[id] = value.applying(motion.a).copy(motion = motion.placed(item.content.playbackSpans, item.start.seconds - lower, initial.videos[id]?.motion))
        }
        return region.copy(videos = videos)
    }

    /** Whether Apply would replace a transition between two of the pictures. */
    fun replacesTransition(timeline: TrackTimeline): Boolean = timeline.transitions.any { relation ->
        val ids = setOfNotNull(relation.outgoing, relation.incoming)
        ids.size > 1 && participants.containsAll(ids)
    }

    /** Apply on [timeline]: the region saved, or [timeline] itself when nothing would change. */
    fun applied(timeline: TrackTimeline): TrackTimeline {
        val saved = saved(timeline)
        return if (!inheritedSeed && saved == initial) timeline else TrackPlacements.setting(timeline, saved)
    }

    /** Remove on [timeline]: the set's region gone. */
    fun removed(timeline: TrackTimeline): TrackTimeline = TrackPlacements.removing(timeline, participants)

    companion object {
        /** A frame of the preview, in seconds (YouRec/TimelineEditing.swift:42). */
        const val FRAME = 1.0 / 30

        /**
         * The tool opened on the pictures [participants] of [timeline], at
         * timeline second [position]. The picture in front is selected. Null
         * for fewer than two pictures of the timeline. It previews the
         * stretch where exactly those pictures play, the one holding
         * [position] if any (`placementPreviewInterval`, :494-498).
         */
        fun open(timeline: TrackTimeline, participants: Set<String>, position: Double): PlacementDraft? {
            val members = TrackPlacements.members(timeline, participants).map { it.id }
            if (members.size < 2) return null
            val ids = members.toSet()
            val at = if (position.isFinite()) position else 0.0
            val seed = TrackPlacements.seed(timeline, ids, EditTime.of(at))
            val inherited = timeline.placements.none { it.participants == ids } && seed.videos != TrackPlacements.defaults(members)
            val interval = TrackPlacements.previewInterval(timeline, ids, at, at..at)
            val lower = interval?.start?.seconds ?: at
            val upper = maxOf(lower, (interval?.end?.seconds ?: at) - FRAME)
            return PlacementDraft(
                members, seed, seed, inherited, VideoPlacement.ordered(members, seed.videos).last(),
                lower = lower, upper = upper, motions = seed.videos.mapNotNull { (id, value) -> value.motion?.let { id to it } }.toMap(),
            )
        }
    }
}
