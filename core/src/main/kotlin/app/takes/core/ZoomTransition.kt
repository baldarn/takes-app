package app.takes.core

import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Timed zoom move. Interpolates log magnification with a quintic ease, whose
 * speed and acceleration reach zero at both ends. Driven by elapsed time, so
 * dropped callbacks never extend the selected duration.
 */
data class ZoomTransition(val start: Double, val target: Double, val began: Double, val duration: Double) {
    fun isFinished(time: Double): Boolean = duration <= 0 || time >= began + duration

    private fun progress(time: Double): Double {
        if (isFinished(time)) return 1.0
        if (time <= began) return 0.0
        val t = (time - began) / duration
        return t * t * t * (10 + t * (-15 + 6 * t))
    }

    fun value(time: Double): Double {
        if (isFinished(time)) return target
        if (time <= began) return start
        return 2.0.pow(log2(start) + log2(target / start) * progress(time))
    }

    companion object {
        val durationPresets = listOf(0.0, 0.5, 1.0, 2.0, 3.0, 5.0, 10.0, 15.0, 30.0)

        fun durationLabel(seconds: Double): String =
            if (seconds == 0.0) "Instant" else trimmed(seconds) + " s"

        fun boundedDuration(value: Double): Double {
            if (!value.isFinite()) return 0.0
            return (Math.round(value * 2) / 2.0).coerceIn(0.0, 30.0)
        }

        /** Presets the active camera can really reach; never advertise more. */
        fun presets(minimum: Double, maximum: Double): List<Double> {
            val wide = if (minimum < 0.95) listOf(minimum) else emptyList()
            return wide + listOf(1.0, 2.0, 3.0, 5.0, 10.0).filter { it >= minimum - 0.001 && it <= maximum + 0.001 }
        }

        fun zoomLabel(zoom: Double): String = trimmed((zoom * 10).roundToInt() / 10.0) + "×"

        private fun trimmed(value: Double): String =
            if (value == Math.floor(value)) value.toLong().toString() else value.toString()
    }
}
