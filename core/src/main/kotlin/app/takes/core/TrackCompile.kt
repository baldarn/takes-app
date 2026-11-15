package app.takes.core

import java.util.TreeMap

/**
 * One stretch of the timeline between two edges, with what plays in it: the
 * pictures showing, back to front; the sounds, in the timeline's order; the
 * placement that belongs to exactly those pictures; the transitions under way.
 * Derived, never saved. YouRec/TrackTimeline.swift:477-483.
 */
data class CompiledTimelineInterval(
    val interval: EditInterval,
    val video: List<String>,
    val audio: List<String>,
    val placementID: String?,
    val transitionIDs: List<String>,
)

/**
 * An item's row on the drawn timeline. [interval] is the card that shows;
 * [contentInterval] is the whole item, which reaches under a transition.
 * [number] counts the item among the pictures, or among the sounds, from 1.
 * YouRec/TrackTimeline.swift:1679-1691.
 */
data class TimelineLane(
    val id: String,
    val interval: EditInterval,
    val contentInterval: EditInterval,
    val source: String,
    val mapping: SourcePlaybackMap,
    val row: Int,
    val number: Int,
    val audio: Boolean,
) {
    val trimsLeft: Boolean get() = interval.start == contentInterval.start
    val trimsRight: Boolean get() = interval.end == contentInterval.end
}

/** What plays in each stretch of the timeline. See [TrackCompile.compile]. */
fun TrackTimeline.compile(): List<CompiledTimelineInterval> = TrackCompile.compile(this)

/** The rows of the drawn timeline. See [TrackCompile.lanes]. */
fun TrackTimeline.lanes(moving: Set<String>? = null): List<TimelineLane> = TrackCompile.lanes(this, moving)

/**
 * What the renderer, navigation and drawing read from a timeline. The
 * original filters every item at every edge and compares every item with
 * those already placed; these give the same answers in one pass over sorted
 * edges and with one search per row, so a long project costs no more per
 * item than a short one.
 */
object TrackCompile {
    /**
     * The timeline cut at every edge: where an item starts or ends, where a
     * chain or a censor of a sound starts or ends, where a transition's
     * stretch, its picture or one of its sound ramps starts or ends. From
     * zero to the end without a hole; stretches where nothing plays are
     * there, empty. YouRec/TrackTimeline.swift:969-1000.
     */
    fun compile(timeline: TrackTimeline): List<CompiledTimelineInterval> {
        val videoStart = LongArray(timeline.video.size) { timeline.video[it].start.ticks }
        val videoEnd = LongArray(timeline.video.size) { timeline.video[it].end.ticks }
        val audioStart = LongArray(timeline.audio.size) { timeline.audio[it].start.ticks }
        val audioEnd = LongArray(timeline.audio.size) { timeline.audio[it].end.ticks }
        val duration = maxOf(videoEnd.maxOrNull() ?: 0L, audioEnd.maxOrNull() ?: 0L, timeline.generated.maxOfOrNull { it.interval.end.ticks } ?: 0L, 0L)
        val regions = timeline.audio.sumOf { it.enhancements.size + it.censors.size }
        val edges = LongArray(2 + 2 * (timeline.video.size + timeline.audio.size + regions + timeline.generated.size) + 8 * timeline.transitions.size)
        var filled = 0
        fun edge(ticks: Long) { edges[filled++] = ticks }
        edge(0L)
        edge(duration)
        for (index in videoStart.indices) { edge(videoStart[index]); edge(videoEnd[index]) }
        timeline.audio.forEachIndexed { index, item ->
            edge(audioStart[index]); edge(audioEnd[index])
            for (region in item.enhancements) { edge(audioStart[index] + region.interval.start.ticks); edge(audioStart[index] + region.interval.end.ticks) }
            for (region in item.censors) { edge(audioStart[index] + region.interval.start.ticks); edge(audioStart[index] + region.interval.end.ticks) }
        }
        for (region in timeline.generated) { edge(region.interval.start.ticks); edge(region.interval.end.ticks) }
        // Each transition's stretch, on the tick clock and not held at zero: that is where it counts as under way.
        val lower = LongArray(timeline.transitions.size)
        val upper = LongArray(timeline.transitions.size)
        timeline.transitions.forEachIndexed { index, relation ->
            val timing = relation.timing
            val origin = relation.origin.ticks
            lower[index] = origin + EditTime.of(timing.lower).ticks
            upper[index] = origin + EditTime.of(timing.upper).ticks
            edge(maxOf(0L, lower[index])); edge(maxOf(0L, upper[index]))
            edge(maxOf(0L, origin)); edge(maxOf(0L, origin + EditTime.of(timing.videoDuration).ticks))
            edge(maxOf(0L, origin + EditTime.of(timing.audioIn.start).ticks)); edge(maxOf(0L, origin + EditTime.of(timing.audioIn.endInclusive).ticks))
            edge(maxOf(0L, origin + EditTime.of(timing.audioOut.start).ticks)); edge(maxOf(0L, origin + EditTime.of(timing.audioOut.endInclusive).ticks))
        }
        edges.sort()
        // The edges from zero to the end, each once.
        var count = 0
        for (index in edges.indices) {
            val value = edges[index]
            if (value < 0 || value > duration || (count > 0 && edges[count - 1] == value)) continue
            edges[count++] = value
        }

        val pictures = Sweep(videoStart, videoEnd)
        val sounds = Sweep(audioStart, audioEnd)
        // A transition that names no picture is never under way.
        val related = timeline.transitions.indices.filter { timeline.transitions[it].outgoing != null || timeline.transitions[it].incoming != null }
        val relations = Sweep(LongArray(related.size) { lower[related[it]] }, LongArray(related.size) { upper[related[it]] })
        val placements = HashMap<Set<String>, String>()
        for (region in timeline.placements) placements.putIfAbsent(region.participants, region.id)

        val result = ArrayList<CompiledTimelineInterval>(count)
        var video = emptyList<String>()
        var audio = emptyList<String>()
        var under = emptyList<String>()
        var placement: String? = null
        for (index in 0 until count - 1) {
            val a = edges[index]
            if (pictures.advance(a)) {
                video = pictures.active.map { timeline.video[it] }.sortedWith(VideoTimelineItem.backToFront).map { it.id }
                placement = if (placements.isEmpty()) null else placements[video.toSet()]
            }
            if (sounds.advance(a)) audio = sounds.active.sorted().map { timeline.audio[it].id }
            if (relations.advance(a)) under = relations.active.sorted().map { timeline.transitions[related[it]].id }
            result += CompiledTimelineInterval(EditInterval(EditTime(a), EditTime(edges[index + 1])), video, audio, placement, under)
        }
        return result
    }

    /** The items that are under way as time goes forward: those with `start ≤ time < end`. */
    private class Sweep(start: LongArray, private val end: LongArray) {
        private val order = start.indices.sortedBy { start[it] }.toIntArray()
        private val starts = LongArray(order.size) { start[order[it]] }
        private var next = 0
        val active = ArrayList<Int>()

        /** Moves to [time], which never goes back. True when [active] changed. */
        fun advance(time: Long): Boolean {
            var changed = false
            while (next < order.size && starts[next] <= time) { active += order[next++]; changed = true }
            var kept = 0
            for (index in active.indices) {
                val item = active[index]
                if (end[item] > time) active[kept++] = item
            }
            if (kept < active.size) {
                active.subList(kept, active.size).clear()
                changed = true
            }
            return changed
        }
    }

    /**
     * The card each item shows. A transition between two pictures is drawn as
     * a join, not as two layers: where the two really overlap, each card ends
     * at the transition's boundary. Time a transition adds between them (a
     * hold on a color, a freeze) keeps its length. The sounds that follow the
     * two pictures fold the same way; a sound that was unlinked or cut on its
     * own stays whole. YouRec/TrackTimeline.swift:1692-1719.
     */
    private fun displayIntervals(timeline: TrackTimeline, folding: Boolean): HashMap<String, EditInterval> {
        val visible = HashMap<String, EditInterval>()
        for (item in timeline.video) visible[item.id] = item.interval
        for (item in timeline.audio) visible[item.id] = item.interval
        if (!folding) return visible
        fun fold(a: String, b: String, time: EditTime) {
            val left = visible[a] ?: return
            val right = visible[b] ?: return
            val overlap = left.intersection(right) ?: return
            val cut = minOf(overlap.end, maxOf(overlap.start, time))
            if (cut <= left.start || cut >= right.end) return
            visible[a] = left.copy(end = cut)
            visible[b] = right.copy(start = cut)
        }
        val pictures = HashMap<String, VideoTimelineItem>()
        for (item in timeline.video) pictures.putIfAbsent(item.id, item)
        val followers = HashMap<String, MutableList<AudioTimelineItem>>()
        for (item in timeline.audio) item.followsVideo?.let { followers.getOrPut(it) { mutableListOf() } += item }
        val links = HashMap<String, MutableList<TimelineLink>>()
        for (link in timeline.links) for (member in link.members) links.getOrPut(member) { mutableListOf() } += link
        fun following(picture: VideoTimelineItem) =
            followers[picture.id].orEmpty().filter { sound -> sound.interval == picture.interval && links[picture.id].orEmpty().any { sound.id in it.members } }
        for (relation in timeline.transitions) {
            val a = relation.outgoing?.let { pictures[it] } ?: continue
            val b = relation.incoming?.let { pictures[it] } ?: continue
            val time = EditTime.of(relation.origin.seconds + relation.timing.cut)
            fold(a.id, b.id, time)
            val first = following(a)
            val second = following(b)
            if (first.size == 1 && second.size == 1) fold(first[0].id, second[0].id, time)
        }
        return visible
    }

    /**
     * Every item's row: pictures first, the one in front on top, then sounds
     * from row 1 at the least. A picture goes under every picture it plays
     * together with, except the one it is joined to by a transition: those
     * two blend, so a long chain of dissolves alternates between two rows
     * instead of taking one more per cut. A sound goes on the first row that
     * is free; the sounds of pictures in front come first, then by start.
     *
     * While [moving] items are being placed, they have rows of their own
     * above the rest of their kind until the move ends, and nothing is folded.
     *
     * YouRec/TrackTimeline.swift:1721-1778.
     */
    fun lanes(timeline: TrackTimeline, moving: Set<String>? = null): List<TimelineLane> {
        val result = ArrayList<TimelineLane>(timeline.video.size + timeline.audio.size)
        val visible = displayIntervals(timeline, folding = moving == null && timeline.transitions.isNotEmpty())
        val layers = HashMap<String, Int>()
        for (item in timeline.video) layers[item.id] = item.layer
        val linkedLayers = HashMap<String, Int>()
        for (link in timeline.links) {
            val layer = link.members.mapNotNull { layers[it] }.maxOrNull() ?: continue
            for (member in link.members) linkedLayers[member] = layer
        }
        val partners = HashMap<String, MutableSet<String>>()
        for (relation in timeline.transitions) {
            val a = relation.outgoing ?: continue
            val b = relation.incoming ?: continue
            partners.getOrPut(a) { mutableSetOf() } += b
            partners.getOrPut(b) { mutableSetOf() } += a
        }

        fun pack(items: List<TimelineLane>, base: Int): Int {
            // Cards on one row never overlap, so each row is its cards by where they start.
            val rows = ArrayList<TreeMap<Long, TimelineLane>>()
            /** A card on [row] that overlaps [item] and that [item] may not share a row with or sit level with; [blending] lets a transition partner pass. */
            fun blocked(row: TreeMap<Long, TimelineLane>, item: TimelineLane, blending: Boolean): Boolean {
                val from = item.interval.start.ticks
                val to = item.interval.end.ticks
                if (to <= from) return false
                val mine = if (blending) partners[item.id] else null
                row.floorEntry(from)?.value?.let { if (it.interval.end.ticks > from && mine?.contains(it.id) != true) return true }
                for (other in row.subMap(from, false, to, false).values) if (mine?.contains(other.id) != true) return true
                return false
            }
            for (item in items) {
                // The lowest row a picture may take: under every picture it overlaps and does not blend with.
                val minimum = if (item.audio) 0 else (rows.indices.lastOrNull { blocked(rows[it], item, blending = true) } ?: -1) + 1
                val row = (minimum until rows.size).firstOrNull { !blocked(rows[it], item, blending = false) } ?: rows.size
                if (row == rows.size) rows += TreeMap<Long, TimelineLane>()
                if (!item.interval.isEmpty) rows[row][item.interval.start.ticks] = item
                result += item.copy(row = base + row)
            }
            return rows.size
        }

        val video = timeline.video.withIndex().sortedWith { a, b -> VideoTimelineItem.backToFront.compare(b.value, a.value) }.map { (index, item) ->
            TimelineLane(item.id, visible.getValue(item.id), item.interval, item.content.sourceID, item.mapping, 0, index + 1, false)
        }
        val audio = timeline.audio.withIndex()
            .sortedWith(compareByDescending<IndexedValue<AudioTimelineItem>> { linkedLayers[it.value.id] ?: -1 }.thenBy { it.value.start }.thenBy { it.value.id })
            .map { (index, item) -> TimelineLane(item.id, visible.getValue(item.id), item.interval, item.sourceID, item.mapping, 0, index + 1, true) }
        var base = 0
        for (items in listOf(video, audio)) {
            if (items.firstOrNull()?.audio == true) base = maxOf(1, base)
            if (moving != null) {
                base += pack(items.filter { it.id in moving }, base)
                base += pack(items.filter { it.id !in moving }, base)
            } else base += pack(items, base)
        }
        return result
    }
}
