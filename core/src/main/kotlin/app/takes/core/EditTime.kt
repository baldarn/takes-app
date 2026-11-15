package app.takes.core

import kotlinx.serialization.Serializable

/**
 * A time on the original app's edit clock: whole ticks of 1/240 000 s, so
 * sums and differences of edit times are exact. Saved as `{"ticks": n}`,
 * as the original saves it.
 *
 * The rest of this core keeps times as seconds; this type is for the audio
 * data the original stores in ticks (fades cut by a split, censored regions).
 */
@Serializable
data class EditTime(val ticks: Long) : Comparable<EditTime> {
    val seconds: Double get() = ticks.toDouble() / SCALE

    operator fun plus(other: EditTime) = EditTime(ticks + other.ticks)
    operator fun minus(other: EditTime) = EditTime(ticks - other.ticks)
    override fun compareTo(other: EditTime): Int = ticks.compareTo(other.ticks)

    companion object {
        const val SCALE = 240_000L

        /** The longest time either way, in seconds: one week. */
        const val LIMIT = 604_800.0

        val ZERO = EditTime(0)

        /** The nearest tick to [seconds], kept within a week either way; 0 for a time that is not a number. */
        fun of(seconds: Double): EditTime =
            if (seconds.isFinite()) EditTime(roundedAwayFromZero(minOf(LIMIT, maxOf(-LIMIT, seconds)) * SCALE).toLong()) else ZERO
    }
}

/** From [start] up to, not including, [end]. Saved as `{"start": {"ticks": n}, "end": {"ticks": n}}`. */
@Serializable
data class EditInterval(val start: EditTime, val end: EditTime) {
    val duration: EditTime get() = end - start
    val isEmpty: Boolean get() = end <= start

    /** The same stretch in seconds; an inverted interval is its start alone. */
    val seconds: ClosedFloatingPointRange<Double> get() = start.seconds..maxOf(start.seconds, end.seconds)

    operator fun contains(time: EditTime): Boolean = start <= time && time < end

    /** What the two share, or null when that is nothing. */
    fun intersection(other: EditInterval): EditInterval? =
        EditInterval(maxOf(start, other.start), minOf(end, other.end)).takeIf { !it.isEmpty }

    companion object {
        fun of(start: Double, end: Double) = EditInterval(EditTime.of(start), EditTime.of(end))
    }
}
