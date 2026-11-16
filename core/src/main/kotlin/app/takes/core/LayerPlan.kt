package app.takes.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * What the preview and the export draw from a track timeline whose pictures
 * may play over one another: the plan of picture layers (docs/TRACKS-PLAN.md,
 * S9; docs/PIP-AND-EFFECTS-PLAN.md, 1.2 and 1.4). Where [RenderPlan] reads a
 * timeline back as a sequence of clips on two alternating decks, this one is
 * made from the timeline's own stretches ([TrackTimeline.compile]) and its
 * ticks, as the original's compiler and compositor make a frame:
 *
 *  - each picture decodes on a lane of its own, the first one free when it
 *    begins (YouRec/TrackRenderer.swift:81-87). A still keeps its lane until
 *    the frame it holds is no longer shown. Lanes are as many as pictures
 *    play at once, and never more than the limit a device gives;
 *  - in each [Stretch], the pictures back to front by layer and by the order
 *    of the placement that belongs to exactly those pictures
 *    ([VideoPlacement.ordered]), with that placement and the transitions under
 *    way, each a relation between two named pictures, not a join between
 *    clip numbers (YouRec/TrackTimeline.swift:969-1000);
 *  - a frame ([moment]) starts from black. Back to front, a pair in transition
 *    is mixed once, at its upper member; any other picture is drawn whole,
 *    moved into its window when the placement gives it one, the window's
 *    motion taken at the source second it shows
 *    (YouRec/VideoToolCompositor.swift:272-316). A pair one of whose pictures
 *    is off its own time is drawn in its place, over the pictures before and
 *    after it, as a sequence shows it; nothing behind a pair shows. The titles of a picture are
 *    drawn inside its layer, before its window (YouRec/TrackRenderer.swift:409-411);
 *    the captions over the whole frame, last.
 *
 * For a timeline that is a sequence, this plan describes the movie
 * [RenderPlan] does (LayerPlanTest): the same pictures at the same times, to
 * within a tick, on lanes that never hold two at once; the same transitions
 * at the same factors; the same freezes and the same endings.
 *
 * Sound is not part of it: the sound of a timeline stays with [RenderPlan]
 * until the stages of sound lanes.
 */
data class LayerPlan(
    val timeline: TrackTimeline,
    /** The length of the movie, in seconds: that of its picture and of what its transitions add, as [RenderPlan.duration]. */
    val duration: Double,
    /** The lanes the pictures decode on: as many as play at once. */
    val lanes: Int,
    /** Every picture, in the timeline's order: [Picture.index] is its place in [TrackTimeline.video]. */
    val pictures: List<Picture>,
    /** The timeline cut at every edge ([TrackTimeline.compile]), each with what it draws. */
    val stretches: List<Stretch>,
    /** The transitions that are drawn: long enough to hold a frame, and naming a picture. */
    val transitions: List<TimelineTransition>,
    val freezes: List<Freeze>,
    val endings: List<RenderPlan.Ending>,
    /** The titles of each picture, over the seconds of the movie it shows them; [RenderPlan.TitleSpan.clip] is the picture's index. */
    val titles: List<RenderPlan.TitleSpan>,
    /** How the captions look; null when the movie has none. */
    val subtitles: SubtitleSettings?,
    val captions: List<SubtitleCue>,
) {
    /**
     * One picture as something to decode: where it plays in the movie, the
     * footage it plays with the cuts a player is given ([RenderPlan.cutMicros]),
     * and the lane it decodes on. [content] is its recipe: color, crop, titles.
     */
    data class Picture(
        val index: Int,
        val id: String,
        val sourceID: String,
        val lane: Int,
        val interval: EditInterval,
        val sourceStart: Double,
        val sourceEnd: Double,
        val sourceStartUs: Long,
        val sourceEndUs: Long,
        val rate: Double,
        val content: TimelineClip,
        /** Until when it keeps its lane: its end, or the end of a still holding its last frame. */
        val occupied: EditTime,
        /** The stretches of footage it plays, each at one rate ([PicturePlayback]); one at [rate] for a picture at one speed. */
        val spans: List<EffectPlaybackSpan> = listOf(EffectPlaybackSpan(sourceStart, sourceEnd, rate)),
    ) {
        /** Seconds of footage past [sourceStart], [offset] seconds into the picture. */
        fun position(offset: Double): Double = PicturePlayback.position(spans, offset)

        /** Seconds into the picture at [position] seconds of footage past [sourceStart]. */
        fun offset(position: Double): Double = PicturePlayback.offset(spans, position)

        val start: Double get() = interval.start.seconds
        val duration: Double get() = interval.duration.seconds
        val end: Double get() = interval.end.seconds
        val color: ColorSettings? get() = content.color
        val crop: CropSettings? get() = content.crop
    }

    /**
     * A frame a still holds, as [RenderPlan.Freeze]: from [from] to [until]
     * the picture at [picture] shows the frame it showed at [shown], [offset]
     * seconds into it and second [sourceTime] of its footage. [held] is the
     * same stretch in ticks, where the picture keeps its lane.
     */
    data class Freeze(
        val transition: String, val picture: Int, val lane: Int, val held: EditInterval,
        val from: Double, val until: Double, val shown: Double, val offset: Double, val sourceTime: Double, val sounding: Boolean,
    )

    /**
     * One stretch of the movie. [order] is the pictures it draws back to
     * front, by their index: those the timeline shows there, a still holding
     * a frame, and the pictures of a transition under way. [placement] is the
     * region of exactly the pictures the timeline shows there, when there is
     * one. [transitions] are those under way that are drawn; two that share a
     * picture are not mixed at once, and the first in the timeline's order is.
     */
    data class Stretch(
        val interval: EditInterval,
        val order: List<Int>,
        val placement: VideoPlacementRegion?,
        val transitions: List<TimelineTransition>,
    ) {
        val from: Double get() = interval.start.seconds
        val to: Double get() = interval.end.seconds
    }

    /** One layer of a frame, composited over those behind it. [titles] are drawn inside it, with their opacity, before any window. */
    sealed interface Layer {
        val titles: List<Pair<TextOverlay, Double>>

        /**
         * A picture drawn whole, at the size of the canvas: running, or held on
         * a frame ([TransitionScene.Source], whose deck is the lane). [window] is
         * where it is moved after that; null fills the canvas.
         */
        data class Whole(
            val picture: Int, val source: TransitionScene.Source, val window: PlacedPicture?,
            override val titles: List<Pair<TextOverlay, Double>>,
        ) : Layer

        /** Two sides of [transition] mixed into one, as [TransitionScene.Moment] mixes them; an opening or a close has a fill on its other side. */
        data class Mixed(
            val transition: TimelineTransition, val first: TransitionScene.Source, val second: TransitionScene.Source,
            val frame: TransitionFrame, val firstShare: Double, val secondShare: Double,
            override val titles: List<Pair<TextOverlay, Double>>,
        ) : Layer {
            /** The sides drawn, as [TransitionScene.Moment.sources]. */
            val sources: List<TransitionScene.Source>
                get() = when (frame) {
                    TransitionFrame.First -> listOf(first)
                    TransitionFrame.Second -> listOf(second)
                    is TransitionFrame.FadeColor -> listOfNotNull(first.takeIf { frame.first > 0 }, second.takeIf { frame.second > 0 })
                    else -> listOf(first, second)
                }
        }
    }

    /** One frame: black, with [layers] over it back to front, and [captions] over all. */
    data class Moment(val time: Double, val stretch: Stretch?, val layers: List<Layer>, val captions: List<SubtitleCue>) {
        /** Every title of the frame, back to front. */
        val titles: List<Pair<TextOverlay, Double>> get() = layers.flatMap { it.titles }
    }

    /** What a plan comes to: planned, or refused because more pictures play at once than the device can decode. */
    sealed interface Outcome {
        data class Planned(val plan: LayerPlan) : Outcome

        /** In [at] the pictures [pictures] would play at once, more than [limit]. */
        data class TooManyLayers(val at: EditInterval, val pictures: List<String>, val limit: Int) : Outcome
    }

    // MARK: Lanes

    /** The lane each picture decodes on, by index. */
    val laneOf: List<Int> get() = pictures.map { it.lane }

    /** The picture whose own time on [lane] holds [time]; null where the lane plays none. */
    fun onLane(lane: Int, time: Double): Picture? = pictures.lastOrNull { it.lane == lane && time >= it.start && time < it.end }

    /** Whether anything needs the compositor: more than one lane, a transition, or a window. */
    val composites: Boolean get() = lanes > 1 || transitions.isNotEmpty() || timeline.placements.isNotEmpty()

    /** Whether a frame [width] by [height] can be drawn on a GPU whose largest texture is [largestSide] pixels a side, with the lanes one above the other. */
    fun fits(width: Int, height: Int, largestSide: Int): Boolean = lanes <= 1 || (width <= largestSide && height.toLong() * lanes <= largestSide)

    // MARK: The picture of a moment

    /** The stretch that holds second [time] of the movie; the last one of the picture at its end or after it, null before it or when nothing plays. */
    fun at(time: Double): Stretch? {
        if (stretches.isEmpty() || !time.isFinite() || time < 0) return null
        val at = if (time >= duration) duration - TransitionLayout.TICK else time
        var low = 0
        var high = stretches.lastIndex
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (stretches[middle].from <= at) low = middle else high = middle - 1
        }
        return stretches[low]
    }

    /** The window [picture] is moved into at [time] in [stretch], its motion taken at [sourceTime]: null when it fills the canvas. */
    fun window(stretch: Stretch, picture: Int, sourceTime: Double, canvas: Size): PlacedPicture? =
        stretch.placement?.videos?.get(pictures[picture].id)?.let { PlacementRendering.placed(it.at(sourceTime), canvas) }

    /**
     * What the movie shows at [time]: the layers of the frame, back to front.
     * [frames] is the movie's frame rate during a transition, which a flash
     * cut counts in ([TransitionScene.moment]).
     */
    fun moment(
        time: Double, canvas: Size = Size(1920.0, 1080.0),
        frames: (TimelineTransition) -> Double = { TransitionScene.FRAMES_PER_SECOND },
    ): Moment {
        val captions = SubtitleEditing.active(captions, time)
        val stretch = at(time) ?: return Moment(time, null, emptyList(), captions)
        val at = time.coerceIn(0.0, duration)
        // Each pair is mixed once, after the picture it is anchored to: its upper member. While one of the two is off its own
        // time (an opening or a close longer than its picture, a hold between two), the pair goes on showing that picture's
        // frame in its place, in front of the pictures before and after it that never play over it, as a sequence shows it.
        val anchored = HashMap<Int, MutableList<TimelineTransition>>()
        val members = HashSet<Int>()
        for (relation in stretch.transitions) listOfNotNull(relation.outgoing, relation.incoming).mapNotNullTo(members) { index[it] }
        for (relation in stretch.transitions) {
            val sides = listOfNotNull(relation.outgoing, relation.incoming).mapNotNull { index[it] }
            val away = sides.filter { side ->
                val picture = pictures[side]
                val held = frozen[side]?.let { at >= it.from && at < it.until } == true
                !held && (at < picture.start || at >= picture.end)
            }
            // A picture that never plays over the one held is its neighbour in a sequence, and is covered; one that plays over it stays over it.
            fun neighbour(other: Int) = other !in members && away.none { pictures[it].interval.intersection(pictures[other].interval) != null }
            val anchor = stretch.order.lastOrNull { it in sides || (away.isNotEmpty() && neighbour(it)) }
            anchor?.let { anchored.getOrPut(it) { mutableListOf() } += relation }
        }
        fun running(picture: Int): TransitionScene.Source.Clip =
            pictures[picture].let { TransitionScene.Source.Clip(picture, it.lane, (at - it.start).coerceIn(0.0, it.duration)) }
        fun titles(source: TransitionScene.Source, share: Double): List<Pair<TextOverlay, Double>> {
            if (share <= 0) return emptyList()
            val (picture, offset) = when (source) {
                is TransitionScene.Source.Clip -> source.index to source.offset
                is TransitionScene.Source.Held -> source.index to source.offset
                is TransitionScene.Source.Fill -> return emptyList()
            }
            val clip = pictures[picture].content
            return TimelineEditing.textsAt(clip, clip.sourceTime(offset)).map { it to share }
        }
        val layers = ArrayList<Layer>()
        for (picture in stretch.order) {
            if (picture !in members) {
                val freeze = frozen[picture]
                val source = if (freeze != null && at >= freeze.from && at < freeze.until) {
                    TransitionScene.Source.Held(picture, pictures[picture].lane, freeze.offset, freeze.from)
                } else running(picture)
                val offset = (source as? TransitionScene.Source.Held)?.offset ?: (source as TransitionScene.Source.Clip).offset
                val sourceTime = pictures[picture].content.sourceTime(offset)
                layers += Layer.Whole(picture, source, window(stretch, picture, sourceTime, canvas), titles(source, 1.0))
            }
            for (relation in anchored[picture].orEmpty()) layers += mixed(relation, at, canvas, frames, ::running, ::titles)
        }
        // A transition fills the canvas: nothing behind it shows.
        val covering = layers.indexOfLast { it is Layer.Mixed }
        if (covering > 0) layers.subList(0, covering).clear()
        return Moment(time, stretch, layers, captions)
    }

    private fun mixed(
        relation: TimelineTransition, at: Double, canvas: Size, frames: (TimelineTransition) -> Double,
        running: (Int) -> TransitionScene.Source.Clip, titles: (TransitionScene.Source, Double) -> List<Pair<TextOverlay, Double>>,
    ): Layer.Mixed {
        val settings = relation.settings
        val timing = relation.timing
        val progress = timing.progress(at - relation.origin.seconds)
        val frame = TransitionPicture.frame(
            settings, progress, canvas, relation.edge, timing.videoDuration,
            if (settings.style == TransitionStyle.FlashCut) frames(relation) else TransitionScene.FRAMES_PER_SECOND,
        )
        val fill = TransitionScene.Source.Fill(TransitionPicture.fill(settings))
        val first: TransitionScene.Source = relation.outgoing?.let { index[it] }?.let { picture ->
            val freeze = frozen[picture]?.takeIf { it.transition == relation.id }
            if (freeze != null && at >= freeze.from) TransitionScene.Source.Held(picture, pictures[picture].lane, freeze.offset, freeze.from) else running(picture)
        } ?: fill
        val second: TransitionScene.Source = relation.incoming?.let { index[it] }?.let(running) ?: fill
        val (firstShare, secondShare) = TransitionScene.shares(frame, progress)
        val written = titles(first, firstShare) + if (second == first) emptyList() else titles(second, secondShare)
        return Layer.Mixed(relation, first, second, frame, firstShare, secondShare, written)
    }

    /** The titles showing at [time], each with its opacity, back to front. */
    fun titlesAt(time: Double, frames: (TimelineTransition) -> Double = { TransitionScene.FRAMES_PER_SECOND }): List<Pair<TextOverlay, Double>> =
        moment(time, frames = frames).titles

    /** The captions showing at [time]. */
    fun captionsAt(time: Double): List<SubtitleCue> = SubtitleEditing.active(captions, time)

    private val index: Map<String, Int> by lazy { HashMap<String, Int>().also { map -> pictures.forEach { map.putIfAbsent(it.id, it.index) } } }

    private val frozen: Map<Int, Freeze> by lazy { HashMap<Int, Freeze>().also { map -> freezes.forEach { map.putIfAbsent(it.picture, it) } } }

    // MARK: The picture of an export

    /** The picture sequences of an export, one per lane: its pictures at their times, black between them. */
    val sequences: List<RenderPlan.Sequence>
        get() = (0 until lanes).map { lane ->
            val entries = mutableListOf<RenderPlan.Entry>()
            var filled = 0.0
            fun gap(until: Double) {
                val micros = ((until - filled) * 1e6).roundToLong()
                if (micros > 0) entries += RenderPlan.Entry.Gap(filled, micros)
            }
            for (picture in onLanes[lane]) {
                gap(picture.start)
                entries += RenderPlan.Entry.Clip(picture.index)
                filled = picture.end
            }
            gap(duration)
            RenderPlan.Sequence(RenderPlan.Role.Picture, lane, entries)
        }

    private val onLanes: List<List<Picture>> by lazy {
        List(lanes) { lane -> pictures.filter { it.lane == lane }.sortedWith(compareBy<Picture> { it.interval.start }.thenBy { it.index }) }
    }

    /**
     * What the lanes hold from second [from] to second [to] of the movie, as
     * [RenderPlan.stretch] does for its two decks: a picture partly in the
     * stretch is cut where the stretch cuts it, a part shorter than
     * [RenderPlan.SHORTEST_PART] is left out, and a lane is black where it
     * plays nothing. [RenderPlan.Part.Footage.index] is the picture's index.
     */
    fun stretch(from: Double, to: Double): RenderPlan.Stretch {
        val parts = List(lanes) { mutableListOf<RenderPlan.Part>() }
        for (lane in 0 until lanes) {
            var filled = from
            fun black(until: Double) {
                val micros = ((until - filled) * 1e6).roundToLong()
                if (micros > 0) parts[lane] += RenderPlan.Part.Black(filled, micros)
                filled = maxOf(filled, until)
            }
            for (picture in onLanes[lane]) {
                val begins = picture.start
                val ends = picture.end
                val starts = maxOf(begins, from)
                val stops = minOf(ends, to)
                if (stops - starts < RenderPlan.SHORTEST_PART) continue
                black(starts)
                val first = if (starts <= begins) picture.sourceStartUs else RenderPlan.cutMicros(picture.sourceStart + picture.position(starts - begins))
                val last = if (stops >= ends) picture.sourceEndUs else RenderPlan.cutMicros(picture.sourceStart + picture.position(stops - begins))
                parts[lane] += RenderPlan.Part.Footage(picture.index, starts, stops, first, maxOf(first + 1, last))
                filled = stops
            }
            black(to)
        }
        return RenderPlan.Stretch(from, to, parts)
    }

    /** The first frame to draw for a section from frame [first], as [RenderPlan.section] finds it: before a frame a still holds. */
    private fun lead(first: Int, rate: Int): Int {
        val margin = RenderPlan.SECTION_MARGIN
        val begins = first.toDouble() / rate
        val held = freezes.filter { it.shown - margin < begins && begins < it.until + margin }.minOfOrNull { it.shown }
        val from = minOf(begins, held ?: begins) - margin
        return maxOf(0, minOf(first, floor(from * rate).toInt()))
    }

    /** The section of [count] frames from frame [first] of a movie of [rate] frames a second, as [RenderPlan.section], with the lanes for decks. */
    fun section(first: Int, count: Int, rate: Int): RenderPlan.Section {
        val frames = ExportSections.pictures(duration, rate)
        val opens = lead(first, rate)
        val closes = minOf(frames, first + count + ceil(RenderPlan.SECTION_MARGIN * rate).toInt())
        return RenderPlan.Section(first, count, opens, closes, stretch(opens.toDouble() / rate, if (closes == frames) duration else closes.toDouble() / rate))
    }

    companion object {
        /** Pictures that overlap by no more than this many ticks meet, as [TimelineEditing.chains] has it: a lane passes from one to the other. */
        const val MEETING_TICKS = 2L

        /** A run of pictures on a lane goes on where the next begins this close to where the last ends, in seconds, as [TimelineEditing.chains] has it. */
        private const val RUN_SLACK = 2 * TransitionLayout.TICK

        /** The plan of [timeline], or null when more than [limit] pictures play at once. */
        fun planned(timeline: TrackTimeline, limit: Int): LayerPlan? = (of(timeline, limit) as? Outcome.Planned)?.plan

        /**
         * The plan of [timeline] on at most [limit] lanes: the pictures a
         * device can decode at once, which it says itself. Refused where more
         * play at once, a still counting until its frame is no longer held.
         */
        fun of(timeline: TrackTimeline, limit: Int): Outcome {
            require(limit >= 1) { "A device decodes at least one picture" }
            val video = timeline.video
            val inPlay = timeline.transitions.filter { (it.outgoing != null || it.incoming != null) && TransitionLayout.longEnough(it.timing) }
            val byID = HashMap<String, Int>()
            video.forEachIndexed { index, item -> byID.putIfAbsent(item.id, index) }

            // The frame each still holds, and how long its picture keeps its lane for it.
            val held = HashMap<Int, Pair<TimelineTransition, TransitionPicture.HeldFrame>>()
            val occupied = Array(video.size) { video[it].end }
            for (relation in inPlay) {
                val picture = relation.outgoing?.let { byID[it] } ?: continue
                if (picture in held) continue
                val item = video[picture]
                val frame = TransitionPicture.heldFrame(relation.timing, relation.origin.seconds, item.start.seconds, item.mapping.duration.seconds, TransitionScene.FRAMES_PER_SECOND) ?: continue
                held[picture] = relation to frame
                occupied[picture] = maxOf(item.end, relation.origin + EditTime.of(relation.timing.videoDuration))
            }

            // Each picture on the first lane free when it begins (YouRec/TrackRenderer.swift:81-87).
            val lane = IntArray(video.size)
            val ends = ArrayList<EditTime>()
            for (picture in video.indices.sortedWith(compareBy<Int> { video[it].start }.thenBy { it })) {
                val start = video[picture].start
                // Two pictures that meet to within the rounding of their seconds to ticks follow one another.
                val free = ends.indexOfFirst { it.ticks <= start.ticks + MEETING_TICKS }
                if (free >= 0) {
                    lane[picture] = free
                    ends[free] = occupied[picture]
                    continue
                }
                if (ends.size == limit) {
                    val together = video.indices.filter { it != picture && video[it].start <= start && occupied[it].ticks > start.ticks + MEETING_TICKS } + picture
                    val until = together.minOf { occupied[it] }
                    return Outcome.TooManyLayers(EditInterval(start, until), together.sortedWith(compareBy<Int> { video[it].start }.thenBy { it }).map { video[it].id }, limit)
                }
                lane[picture] = ends.size
                ends += occupied[picture]
            }

            val pictures = video.mapIndexed { index, item ->
                val runs = item.mapping.runs
                val spans = PicturePlayback.spans(item.content, runs)
                val sourceStart = spans.firstOrNull()?.start ?: item.content.start
                val sourceEnd = spans.lastOrNull()?.end ?: item.content.end
                val rate = if (runs.size == 1 && !runs[0].isHold && item.content.frozenFrame == null && !item.content.timed) item.content.rate
                else PicturePlayback.steady(spans) ?: item.content.rate
                Picture(
                    index, item.id, item.content.sourceID, lane[index], item.interval, sourceStart, sourceEnd,
                    RenderPlan.cutMicros(sourceStart), RenderPlan.cutMicros(sourceEnd), rate, item.content, occupied[index],
                    spans = if (spans.isEmpty() || (runs.size == 1 && rate == item.content.rate && !runs[0].isHold)) listOf(EffectPlaybackSpan(sourceStart, sourceEnd, rate)) else spans,
                )
            }
            val freezes = held.entries.sortedWith(compareBy({ it.value.second.from }, { it.key })).map { (picture, pair) ->
                val (relation, frame) = pair
                val item = video[picture]
                val from = maxOf(item.start, minOf(item.end, relation.origin))
                Freeze(
                    relation.id, picture, lane[picture], EditInterval(from, occupied[picture]), frame.from, frame.until, frame.shown, frame.offset,
                    item.content.sourceTime(frame.offset), sounding = item.end.seconds > frame.from + 0.001,
                )
            }

            val pictured = maxOf(video.maxOfOrNull { it.end } ?: EditTime.ZERO, timeline.generated.maxOfOrNull { it.interval.end } ?: EditTime.ZERO)
            val relations = HashMap<String, TimelineTransition>()
            for (relation in inPlay) relations.putIfAbsent(relation.id, relation)
            val placements = HashMap<String, VideoPlacementRegion>()
            for (region in timeline.placements) placements.putIfAbsent(region.id, region)
            val backToFront = Comparator<Int> { a, b -> VideoTimelineItem.backToFront.compare(video[a], video[b]) }.thenBy { it }

            val stretches = timeline.compile().filter { it.interval.start < pictured }.map { part ->
                val at = part.interval.start
                val drawn = LinkedHashSet<Int>()
                part.video.mapNotNullTo(drawn) { byID[it] }
                for (freeze in freezes) if (freeze.held.start <= at && at < freeze.held.end) drawn += freeze.picture
                val under = ArrayList<TimelineTransition>()
                val taken = HashSet<Int>()
                for (id in part.transitionIDs) {
                    val relation = relations[id] ?: continue
                    val sides = listOfNotNull(relation.outgoing, relation.incoming).mapNotNull { byID[it] }
                    if (sides.isEmpty() || sides.any { it in taken }) continue
                    taken += sides
                    drawn += sides
                    under += relation
                }
                val placement = part.placementID?.let { placements[it] }
                val sorted = drawn.sortedWith(backToFront)
                val order = VideoPlacement.ordered(sorted.map { video[it].id }, placement?.videos.orEmpty()).map { byID.getValue(it) }
                Stretch(EditInterval(at, minOf(part.interval.end, pictured)), order, placement, under)
            }

            // A lane's run of pictures ends where the next on it does not follow straight on.
            val endings = ArrayList<RenderPlan.Ending>()
            for (number in ends.indices) {
                val own = pictures.filter { it.lane == number }.sortedWith(compareBy<Picture> { it.interval.start }.thenBy { it.index })
                own.forEachIndexed { at, picture ->
                    val next = own.getOrNull(at + 1)
                    if (next == null || kotlin.math.abs(next.start - picture.end) > RUN_SLACK) endings += RenderPlan.Ending(number, picture.end)
                }
            }
            endings.sortWith(compareBy<RenderPlan.Ending> { it.at }.thenBy { it.deck })

            val titles = pictures.flatMap { picture ->
                val clip = picture.content
                clip.texts.orEmpty().mapNotNull { title ->
                    val from = maxOf(clip.start, title.start)
                    val to = minOf(clip.end, title.end)
                    if (to <= from) null else RenderPlan.TitleSpan(picture.index, title, picture.start + clip.offset(from), picture.start + clip.offset(to))
                }
            }
            val captions = TrackSubtitles.cues(timeline)
            return Outcome.Planned(
                LayerPlan(
                    timeline, pictured.seconds, ends.size, pictures, stretches, inPlay, freezes, endings, titles,
                    timeline.subtitles?.takeIf { captions.isNotEmpty() }, captions,
                ),
            )
        }
    }
}
