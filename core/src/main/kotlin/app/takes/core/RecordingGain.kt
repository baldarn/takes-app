package app.takes.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The recorder's input gain, as the original's `RecordingGain`
 * (YouRec/CaptureAudio.swift:142-185): a level from −40 to +12 dB on a half
 * decibel grid, applied to a copy of each buffer before it is encoded and
 * metered. A change ramps over 20 ms so it does not click; nothing limits the
 * result, so a gain that clips shows as clipping on the meter. At unity the
 * buffer passes through untouched.
 *
 * One instance per take, on the thread that handles the sound. Samples are
 * interleaved floats, 1.0 being full scale.
 */
class RecordingGain {
    private var current = 1f
    private var target = 1f
    private var remainingSeconds = 0.0

    fun set(db: Double) {
        val next = 10.0.pow(normalized(db) / 20).toFloat()
        if (next == target) return
        target = next
        remainingSeconds = RAMP_SECONDS
    }

    /** The buffer with the gain applied: [samples] itself at unity, otherwise a new array. */
    fun process(samples: FloatArray, channels: Int, sampleRate: Double): FloatArray {
        if (current == 1f && target == 1f) return samples
        val frames = samples.size / channels
        val output = FloatArray(frames * channels)
        var remaining = roundedAwayFromZero(remainingSeconds * sampleRate).toInt()
        for (frame in 0 until frames) {
            if (remaining > 0) {
                current += (target - current) / remaining
                remaining--
            } else {
                current = target
            }
            for (channel in 0 until channels) {
                val index = frame * channels + channel
                val value = samples[index]
                output[index] = if (value.isFinite()) value * current else 0f
            }
        }
        remainingSeconds = remaining / sampleRate
        return output
    }

    /** 16-bit samples as the recorder reads them, scaled as the original does. */
    fun process(samples: ShortArray, channels: Int, sampleRate: Double): FloatArray =
        process(FloatArray(samples.size) { samples[it] / 32768f }, channels, sampleRate)

    companion object {
        const val MINIMUM_DB = -40.0
        const val MAXIMUM_DB = 12.0
        const val RAMP_SECONDS = 0.020

        /** While the slider is dragged, a gain this close to 0 dB is 0 dB (YouRec/RecorderView.swift:1584-1586). */
        const val SNAP_DB = 1.0

        /** The accessibility step of the slider, as its grid (RecorderView.swift:1568-1571). */
        const val STEP_DB = 0.5

        /** What the caption under the gain says (RecorderView.swift:1563). */
        enum class Level { Original, Reduced, Boosted }

        fun level(db: Double): Level = when {
            db == 0.0 -> Level.Original
            db < 0 -> Level.Reduced
            else -> Level.Boosted
        }

        /**
         * The gain a drag of the slider to [proposed] gives: on the grid, and 0 dB within [SNAP_DB] of it
         * (RecorderView.swift:1581-1589).
         */
        fun dragged(proposed: Double): Double {
            val value = normalized(proposed)
            return if (kotlin.math.abs(value) <= SNAP_DB) 0.0 else value
        }

        /** The gain one accessibility step up ([up]) or down from [db] gives, with no snap (RecorderView.swift:1568-1571). */
        fun stepped(db: Double, up: Boolean): Double = normalized(db + if (up) STEP_DB else -STEP_DB)

        /** On the half-decibel grid within range; anything not finite is 0 dB. */
        fun normalized(db: Double): Double =
            if (db.isFinite()) min(MAXIMUM_DB, max(MINIMUM_DB, roundedAwayFromZero(db * 2) / 2)) else 0.0
    }
}
