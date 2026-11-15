package app.takes.core

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * How the timeline is drawn across the screen: one calculation for zooming,
 * fitting, the zoom presets and finding the time under a finger.
 *
 * Lengths are in the screen's density-independent unit (points on the
 * iPhone, dp here) and [requestedScale] is that many of them per second.
 * When the whole movie fits, both of its ends are on screen with room beside
 * each for an add button, and the playhead moves across the view. Otherwise
 * the playhead stays in the middle and the movie scrolls under it.
 */
data class TimelineViewport(val width: Double, val duration: Double, val requestedScale: Double) {
    /** The scale at which the whole movie fits. Zooming out stops here. */
    val minimumScale: Double get() = maxOf(0.000001, (width - 2 * EDGE_INSET) / maxOf(1.0, duration))

    /** The scale in use: [requestedScale] kept between [minimumScale] and [MAXIMUM_SCALE]. */
    val scale: Double get() = maxOf(minimumScale, minOf(MAXIMUM_SCALE, requestedScale))

    val fits: Boolean get() = duration * scale <= width - 2 * EDGE_INSET + 0.5

    /** Empty room before time zero and after the end. */
    val inset: Double get() = if (fits) EDGE_INSET else width / 2

    val contentWidth: Double get() = maxOf(width, duration * scale + 2 * inset)

    /** Where [time] is drawn when the content is scrolled by [offset]. */
    fun x(time: Double, offset: Double): Double = time * scale + inset - offset

    /** The time drawn at [x], kept inside the movie. */
    fun time(x: Double, offset: Double): Double = minOf(duration, maxOf(0.0, (x + offset - inset) / scale))

    /** The time under the fixed playhead when a movie that does not fit is scrolled by [offset]. Not kept inside the movie. */
    fun scrubTime(offset: Double): Double = offset / scale

    /** The zoom menu: each entry's name and scale. A scale matches an entry when closer than [PRESET_MATCH] to it. */
    val presets: List<Pair<String, Double>>
        get() = listOf(
            "Whole project" to minimumScale, "5 minutes" to width / 300, "1 minute" to width / 60,
            "10 seconds" to width / 10, "1 second" to width,
        )

    companion object {
        /** Room at each end at whole-movie fit: a 44-unit add button and a little air. */
        const val EDGE_INSET = 48.0
        const val MAXIMUM_SCALE = 720.0

        /** The scale a project opens at. */
        const val DEFAULT_SCALE = 60.0
        const val PRESET_MATCH = 0.0000001
    }
}

/**
 * Snapping: the playhead and a dragged piece are pulled to nearby edges.
 * Reach is measured on screen, not in time, so zooming in makes snapping finer:
 * [scale] is always the viewport's units per second.
 *
 * Times are compared on the original's clock of 240 000 ticks per second, so
 * a snapped edge meets its target exactly.
 */
object TimelineSnapping {
    /** The playhead snaps to a point closer than this on screen. */
    const val PLAYHEAD_REACH = 8.0

    /** A dragged edge takes hold of a point closer than this. */
    const val MOVE_ACQUIRE = 10.0

    /** Once held, it stays until the finger is further than this. */
    const val MOVE_HOLD = 18.0

    /** A held snap gives way when another point is under the finger and nearer by more than this. */
    const val MOVE_YIELD = 1.0

    private const val SCALE = 240_000.0
    private const val LONGEST = 604_800.0

    private fun ticks(seconds: Double): Long =
        if (seconds.isFinite()) (seconds.coerceIn(-LONGEST, LONGEST) * SCALE).roundToLong() else 0L

    private fun seconds(ticks: Long): Double = ticks / SCALE

    /**
     * What things snap to, in order and without repeats: the start and the
     * end of every clip and of every sound on the audio tracks, every
     * bookmark, the start of the movie, and [extra] (the playhead, when a
     * piece is dragged). Whatever is being dragged is left out with
     * [excludingClips] and [excludingAudio], bookmarks of a dragged clip
     * included: nothing snaps to itself.
     */
    fun points(
        state: EditState,
        excludingClips: Set<String> = emptySet(),
        excludingAudio: Set<String> = emptySet(),
        extra: List<Double> = emptyList(),
    ): List<Double> {
        val clips = state.clips
        val points = sortedSetOf(0L)
        clips.forEachIndexed { index, clip ->
            if (clip.id in excludingClips) return@forEachIndexed
            val start = TimelineEditing.clipStart(clips, index)
            points += ticks(start)
            points += ticks(start + clip.duration)
            for (mark in clip.bookmarks.orEmpty()) if (mark.time >= clip.start && mark.time < clip.end) points += ticks(start + clip.offset(mark.time))
        }
        for (item in state.audio.orEmpty()) {
            if (item.id in excludingAudio) continue
            points += ticks(item.start)
            points += ticks(item.end)
        }
        for (time in extra) if (time.isFinite()) points += ticks(time)
        return points.map(::seconds)
    }

    /** Only the boundaries between clips, with the start and the end of the movie: what the ends of a marked range snap to. */
    fun joins(clips: List<TimelineClip>): List<Double> =
        (0..clips.size).map { TimelineEditing.clipStart(clips, it) }.distinct()

    /**
     * The point of [points] nearest to [time], if it is within
     * [PLAYHEAD_REACH] on screen. Of two equally near, the earlier.
     */
    fun target(time: Double, scale: Double, points: List<Double>): Double? {
        val nearest = points.minByOrNull { abs(it - time) } ?: return null
        return nearest.takeIf { abs(it - time) * scale <= PLAYHEAD_REACH }
    }

    /** [time], or the point it snaps to. For the playhead when a scrub ends. */
    fun snap(time: Double, scale: Double, points: List<Double>): Double = target(time, scale, points) ?: time

    /** An edge of the dragged piece, at [edge] before the drag, held on the point at [target]. */
    data class MoveSnap(val target: Double, val edge: Double)

    /** How far the piece moves, and the snap holding it, to pass as the latch of the next call. */
    data class MoveResult(val delta: Double, val snap: MoveSnap?)

    /**
     * A drag of a piece whose edges were at [edges] before the drag began,
     * asked to move by [requested] seconds, against [targets] (from [points],
     * without the piece itself).
     *
     * The piece never goes before the start of the movie. The edge nearest to
     * a target takes hold of it within [MOVE_ACQUIRE]. Pass the previous
     * result's snap as [latch]: a held snap stays until the finger is
     * [MOVE_HOLD] away, so small movements do not shake it loose, unless
     * another target is under the finger and nearer by more than [MOVE_YIELD].
     */
    fun move(edges: List<Double>, requested: Double, scale: Double, targets: List<Double>, latch: MoveSnap? = null): MoveResult {
        val own = edges.map(::ticks).toSortedSet().toList()
        val start = own.firstOrNull() ?: return MoveResult(0.0, null)
        val points = targets.map(::ticks).toSortedSet().toList()
        val minimum = -start
        val delta = maxOf(minimum, ticks(requested))
        val perSecond = maxOf(0.000001, scale)
        var best: Pair<Long, Long>? = null
        var distance = Double.POSITIVE_INFINITY
        for (edge in own) {
            val position = edge + delta
            val lower = points.binarySearch(position).let { if (it < 0) -it - 1 else it }
            for (index in intArrayOf(lower - 1, lower)) {
                val target = points.getOrNull(index) ?: continue
                if (target - edge < minimum) continue
                val gap = abs(seconds(position - target)) * perSecond
                if (gap <= MOVE_ACQUIRE && gap < distance) { best = target to edge; distance = gap }
            }
        }
        if (latch != null) {
            val heldTarget = ticks(latch.target)
            val heldEdge = ticks(latch.edge)
            if (heldEdge in own && heldTarget in points && heldTarget - heldEdge >= minimum) {
                val held = abs(seconds(heldEdge + delta - heldTarget)) * perSecond
                // Holding on must not pull past another cut under the finger.
                if (held <= MOVE_HOLD && (best == null || held <= distance + MOVE_YIELD)) {
                    return MoveResult(seconds(heldTarget - heldEdge), MoveSnap(seconds(heldTarget), seconds(heldEdge)))
                }
            }
        }
        val found = best ?: return MoveResult(seconds(delta), null)
        return MoveResult(seconds(found.first - found.second), MoveSnap(seconds(found.first), seconds(found.second)))
    }
}
