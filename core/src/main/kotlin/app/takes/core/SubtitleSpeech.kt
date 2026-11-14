package app.takes.core

import kotlin.math.log10

/**
 * One piece of sound to hand to the recognizer. [range] is what is transcribed;
 * [speech] are the voiced islands inside it, where its words are then placed.
 * Seconds from the beginning of the measured levels.
 */
data class SubtitleSpeechChunk(val range: SourceInterval, val speech: List<SourceInterval>)

object SubtitleSpeechPlan {
    /** The recognizer never receives more than this many seconds at once. */
    const val LIMIT = 12.0

    /**
     * Plans the transcription from [levels]: one RMS value, 0–1 of full scale,
     * per [step] seconds of the mix (20 ms in the original).
     *
     * An energy gate identifies timing anchors, not speakers. The recognizer
     * identifies speech. The adaptive floor is capped so a continuously spoken
     * passage does not classify its quieter syllables as background.
     */
    fun chunks(levels: FloatArray, duration: Double, step: Double = 0.02): List<SubtitleSpeechChunk> {
        if (levels.isEmpty() || !(duration > 0)) return emptyList()
        val db = DoubleArray(levels.size) { 20 * log10(maxOf(1e-9, levels[it].toDouble())) }
        val sorted = db.sortedArray()
        val floor = sorted[minOf(sorted.size - 1, sorted.size / 5)]
        val threshold = maxOf(-52.0, minOf(-35.0, floor + 9))

        // An island is at least three loud slices and ends after nine quiet ones.
        // It is widened a little, because a word starts and fades below the gate.
        val islands = mutableListOf<SourceInterval>()
        var begin: Int? = null
        var last = 0
        for (i in db.indices) {
            if (db[i] > threshold) {
                if (begin == null) begin = i
                last = i
            }
            val start = begin
            if (start != null && (i - last > 8 || i == db.size - 1)) {
                if (last - start >= 2) {
                    val a = maxOf(0.0, start * step - 0.08)
                    val b = minOf(duration, (last + 1) * step + 0.1)
                    val prior = islands.lastOrNull()
                    if (prior != null && a <= prior.end) islands[islands.lastIndex] = SourceInterval(prior.start, b)
                    else if (b > a) islands += SourceInterval(a, b)
                }
                begin = null
            }
        }

        // Split long unbroken speech near the quietest 100 ms in a bounded
        // window. This keeps decoder context and activation memory bounded.
        val bounded = mutableListOf<SourceInterval>()
        for (island in islands) {
            var a = island.start
            while (island.end - a > LIMIT) {
                val lower = minOf(db.size - 1, ((a + 8) / step).toInt())
                val upper = minOf(db.size - 1, ((a + LIMIT) / step).toInt())
                var best = lower
                var energy = Double.POSITIVE_INFINITY
                for (i in lower..upper) {
                    var value = 0.0
                    for (k in maxOf(0, i - 2)..minOf(db.size - 1, i + 2)) value += db[k]
                    if (value < energy) { energy = value; best = i }
                }
                val b = maxOf(a + 8, best * step)
                bounded += SourceInterval(a, b)
                a = b
            }
            if (island.end > a) bounded += SourceInterval(a, island.end)
        }

        // Islands separated by a short pause are transcribed together: the
        // recognizer does better with the context of the neighboring words.
        val result = mutableListOf<SubtitleSpeechChunk>()
        for (island in bounded) {
            val previous = result.lastOrNull()
            if (previous != null && island.end - previous.range.start <= LIMIT) {
                val gap = island.start - previous.range.end
                if (gap < 0.7 || (gap < 1.3 && previous.range.end - previous.range.start < 3)) {
                    result[result.lastIndex] = SubtitleSpeechChunk(SourceInterval(previous.range.start, island.end), previous.speech + island)
                    continue
                }
            }
            result += SubtitleSpeechChunk(island, listOf(island))
        }
        return result
    }
}
