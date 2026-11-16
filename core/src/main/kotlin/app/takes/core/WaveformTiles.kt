package app.takes.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The detailed waveform the timeline draws when zoomed in, as the original's
 * `WaveformTile` (YouRec/WaveformDetail.swift:4-17, built at 36-66): four
 * seconds of sound in 1000 bins a second, each holding the lowest and highest
 * sample of any channel, zero where nothing was heard. Only the tiles on
 * screen are decoded; [envelope] gives what a column of pixels draws.
 */
class WaveformTile(val start: Double, val low: FloatArray, val high: FloatArray) {
    /** The lowest and highest sample from [from] to [to], seconds of the source; none outside this tile. */
    fun envelope(from: Double, to: Double): Pair<Float, Float>? {
        if (from < start || from >= start + SECONDS) return null
        val a = max(0, ((from - start) * BINS_PER_SECOND).toInt())
        val b = min(low.size, max(a + 1, ceil((to - start) * BINS_PER_SECOND).toInt()))
        if (a >= b) return null
        var lo = low[a]
        var hi = high[a]
        for (i in a + 1 until b) { lo = min(lo, low[i]); hi = max(hi, high[i]) }
        return lo to hi
    }

    /** The same span with half the bins: each the extremes of two. */
    fun coarser(): WaveformTile {
        val count = (low.size + 1) / 2
        return WaveformTile(start,
            FloatArray(count) { min(low[2 * it], low[min(low.size - 1, 2 * it + 1)]) },
            FloatArray(count) { max(high[2 * it], high[min(high.size - 1, 2 * it + 1)]) })
    }

    companion object {
        const val SECONDS = 4.0
        const val BINS_PER_SECOND = 1000.0
        const val COUNT = 4000

        /** The tile that holds [time]. */
        fun index(time: Double): Int = max(0, (time / SECONDS).toInt())

        /**
         * Builds tile [index] from interleaved samples at [sampleRate] whose
         * first frame is at [origin] seconds of the source; frames outside the
         * tile are left out. A tile can be built from several reads in turn
         * with [Builder].
         */
        fun build(index: Int, samples: FloatArray, channels: Int, sampleRate: Double, origin: Double): WaveformTile =
            Builder(index).add(samples, channels, sampleRate, origin).tile()

        /**
         * The envelope drawn at a column from [from] to [to], as the timeline
         * reads it (YouRec/EditorTimeline.swift:840-846): from the tile of
         * [from], joined with the next tile's when the column crosses into it.
         */
        fun envelope(tiles: (Int) -> WaveformTile?, from: Double, to: Double): Pair<Float, Float>? {
            val index = (from / SECONDS).toInt()
            val detail = tiles(index)?.envelope(from, max(from, to)) ?: return null
            if (to >= (index + 1) * SECONDS) {
                val next = tiles(index + 1)?.envelope((index + 1) * SECONDS, to)
                if (next != null) return min(detail.first, next.first) to max(detail.second, next.second)
            }
            return detail
        }
    }

    class Builder(index: Int) {
        private val start = index * SECONDS
        private val low = FloatArray(COUNT)
        private val high = FloatArray(COUNT)

        fun add(samples: FloatArray, channels: Int, sampleRate: Double, origin: Double): Builder {
            val offset = origin - start
            for (frame in 0 until samples.size / channels) {
                val bin = floor((offset + frame / sampleRate) * BINS_PER_SECOND + 1e-7).toInt()
                if (bin < 0 || bin >= COUNT) continue
                for (c in 0 until channels) {
                    val v = samples[frame * channels + c]
                    if (v < low[bin]) low[bin] = v
                    if (v > high[bin]) high[bin] = v
                }
            }
            return this
        }

        fun tile() = WaveformTile(start, low.copyOf(), high.copyOf())
    }
}

/**
 * The overview waveform's peaks in halving levels, so the loudest peak of
 * any span is found in a few steps however far the timeline is zoomed out
 * (YouRec/EditorTimeline.swift:778-796).
 */
class PeakPyramid(peaks: FloatArray) {
    val levels: List<FloatArray>

    init {
        val built = mutableListOf(peaks)
        while (built.last().size > 1) {
            val last = built.last()
            built.add(FloatArray((last.size + 1) / 2) { max(last[2 * it], last[min(last.size - 1, 2 * it + 1)]) })
        }
        levels = built
    }

    val count: Int get() = levels[0].size

    /** The loudest peak from [from] to [to] seconds of a waveform [duration] long; at least one peak is read. */
    fun peak(from: Double, to: Double, duration: Double): Float {
        if (count == 0 || duration <= 0) return 0f
        val a = max(0, min(count - 1, (from / duration * count).toInt()))
        val b = max(a + 1, min(count, (to / duration * count).toInt()))
        var cursor = a
        var result = 0f
        while (cursor < b) {
            var level = 0
            while (level + 1 < levels.size && cursor % (1 shl (level + 1)) == 0 && cursor + (1 shl (level + 1)) <= b) level++
            result = max(result, levels[level][cursor shr level])
            cursor += 1 shl level
        }
        return result
    }
}
