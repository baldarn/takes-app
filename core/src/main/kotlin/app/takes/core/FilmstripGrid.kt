package app.takes.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Where the timeline samples a clip's thumbnails, as the original's grid since
 * 57e5d91 (YouRec/Filmstrip.swift:3-40). The sample clock belongs to the
 * footage: splitting a clip or moving it on the timeline does not restart the
 * grid for the pictures after the cut. A tile of [tileWidth] points at
 * [pointsPerSecond] covers a power of two of frames at [fps].
 */
class FilmstripGrid(tileWidth: Double, pointsPerSecond: Double, val fps: Double) {
    /** Seconds of the timeline a tile covers. */
    val step: Double = 2.0.pow(log2(maxOf(1.0, tileWidth * fps / pointsPerSecond)).roundToInt()) / fps

    /** The start of the tile that holds timeline second [time], on the grid that begins at [origin]. */
    fun slot(time: Double, origin: Double): Double = origin + floor((time - origin) / step + 1e-8) * step

    /**
     * The frame a tile from [start] to [end] shows: its first, or its middle
     * when [center]; a sliver shorter than a frame shows the frame already on
     * screen, never one of the clip after it; never past the last frame of
     * [duration] seconds.
     */
    fun sample(start: Double, end: Double, center: Boolean, duration: Double): EditTime {
        val first = ceil(start * fps - 1e-7)
        val last = ceil(end * fps - 1e-7) - 1
        val preferred = if (center) floor((start + end) * 0.5 * fps) else first
        val frame = minOf(last, maxOf(first, preferred))
        val finalFrame = ceil(duration * fps - 1e-7) - 1
        return EditTime.of(maxOf(0.0, minOf(finalFrame, frame)) / fps)
    }

    /** A tile from [start] to [end] cut at [edges] no closer than 12 points to one another or to its ends. */
    fun boundaries(start: Double, end: Double, edges: List<Double>, pointsPerSecond: Double): List<Double> {
        val minimum = 12 / pointsPerSecond
        val result = mutableListOf(start)
        for (edge in edges) if (edge - result.last() >= minimum && end - edge >= minimum) result += edge
        result += end
        return result
    }
}

/**
 * The thumbnails of a clip on the grid of its footage (YouRec/EditorTimeline.swift:1548-1572 at 57e5d91), for a
 * timeline that draws each clip's own frames: the tiles from [from] to [to] seconds into a clip that begins at timeline
 * second [start] and lasts [duration], on the grid that begins where its footage would ([origin]), each cut to the clip
 * and showing its first frame, in seconds into the clip.
 */
object FilmstripTiles {
    /** The frame rate of the grid when the footage's is not known, as the original's (YouRec/EditorTimeline.swift:1447). */
    const val FPS = 30.0

    /** A tile from [from] to [to] seconds into the clip, showing the frame [at] seconds into it. */
    data class Tile(val from: Double, val to: Double, val at: Double)

    fun of(grid: FilmstripGrid, start: Double, duration: Double, origin: Double, from: Double, to: Double): List<Tile> {
        val tiles = ArrayList<Tile>()
        val end = start + duration
        var slot = grid.slot(start + maxOf(0.0, from), origin)
        while (slot < start + minOf(duration, to) && tiles.size < 4096) {
            val lo = maxOf(start, slot)
            val hi = minOf(end, slot + grid.step)
            if (hi > lo) tiles += Tile(lo - start, hi - start, (grid.sample(lo, hi, center = false, duration = end).seconds - start).coerceIn(0.0, duration))
            slot += grid.step
        }
        return tiles
    }
}
