package app.takes.core

import kotlin.random.Random

/**
 * The pictures a cover can be chosen from: every thirtieth of a second of
 * the movie that shows footage and lies in the part the person is looking
 * at. Gaps in the picture are skipped, footage that overlaps counts once,
 * and the pool stays a handful of numbers even for a project of hours.
 *
 * Nine of them are offered at a time, one from each ninth of the pool, in
 * the order of the movie.
 */
class CoverFramePool(picture: List<Interval>, scope: List<Interval>) {
    /** A stretch of the movie in seconds; the end is not part of it. */
    data class Interval(val start: Double, val end: Double) {
        internal val startTicks get() = ExportSections.ticks(start)
        internal val endTicks get() = ExportSections.ticks(end)
    }

    private class Span(val first: Long, val count: Int)
    private class Ticks(val start: Long, var end: Long)

    private val spans: List<Span>

    /** How many pictures there are to choose from. */
    val count: Int

    init {
        val pictures = merged(picture)
        val scopes = merged(scope)
        val found = mutableListOf<Span>()
        var i = 0
        var j = 0
        while (i < pictures.size && j < scopes.size) {
            val start = maxOf(pictures[i].start, scopes[j].start)
            val end = minOf(pictures[i].end, scopes[j].end)
            if (end > start) {
                // The first picture on the thirtieth-of-a-second grid, or the start itself in a stretch too short to hold one.
                val gridStart = (start + STEP - 1) / STEP * STEP
                val first = if (gridStart < end) gridStart else start
                found += Span(first, 1 + ((end - 1 - first) / STEP).toInt())
            }
            if (pictures[i].end < scopes[j].end) i += 1 else j += 1
        }
        spans = found
        count = found.sumOf { it.count }
    }

    /**
     * Up to [CHOICES] indices into the pool, rising. With [previous], the
     * choices shown before, every one is replaced by another picture of its
     * own band wherever the band has more than one.
     */
    fun picks(previous: List<Int> = emptyList(), random: Random = Random.Default): List<Int> {
        val bands = minOf(count, CHOICES)
        return (0 until bands).map { band ->
            val start = band * count / bands
            val end = (band + 1) * count / bands
            if (end - start <= 1 || band !in previous.indices) return@map random.nextInt(start, end)
            val pick = random.nextInt(start, end - 1)
            if (pick < previous[band]) pick else pick + 1
        }
    }

    /** The movie second of picture [index]. */
    fun time(index: Int): Double = ticks(index).toDouble() / ExportSections.TICKS_PER_SECOND

    internal fun ticks(index: Int): Long {
        var offset = index
        for (span in spans) {
            if (offset < span.count) return span.first + offset * STEP
            offset -= span.count
        }
        throw IndexOutOfBoundsException("Cover index outside the sampled range")
    }

    companion object {
        const val CHOICES = 9
        private const val STEP = ExportSections.TICKS_PER_SECOND / 30

        /** Where the clips of an edit show their picture. Clips that dissolve into each other overlap, which the pool allows. */
        fun picture(clips: List<TimelineClip>): List<Interval> = clips.indices.map { index ->
            val start = TimelineEditing.clipStart(clips, index)
            Interval(start, start + clips[index].duration)
        }

        /** The whole movie as the scope. */
        fun whole(clips: List<TimelineClip>): List<Interval> = listOf(Interval(0.0, TimelineEditing.duration(clips)))

        private fun merged(ranges: List<Interval>): List<Ticks> {
            val result = mutableListOf<Ticks>()
            for (range in ranges.map { Ticks(it.startTicks, it.endTicks) }.sortedBy { it.start }) {
                if (range.end <= range.start) continue
                val last = result.lastOrNull()
                if (last != null && range.start <= last.end) last.end = maxOf(last.end, range.end) else result += range
            }
            return result
        }
    }
}
