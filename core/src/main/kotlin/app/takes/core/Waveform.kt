package app.takes.core

import java.io.ByteArrayOutputStream
import java.nio.ShortBuffer
import kotlin.math.abs

/**
 * Reduces 16-bit PCM to one peak per fixed slice of time, so a long recording
 * can be drawn without keeping its audio in memory. Each peak is 0–255.
 */
class WaveformBuilder(private val sampleRate: Int, private val channels: Int, private val peaksPerSecond: Int = PEAKS_PER_SECOND) {
    private val peaks = ByteArrayOutputStream()
    private var frame = 0L
    private var channel = 0
    private var peak = 0

    init { require(sampleRate > 0 && channels > 0 && peaksPerSecond > 0) }

    fun add(samples: ShortBuffer) {
        while (samples.hasRemaining()) {
            val level = minOf(abs(samples.get().toInt()), Short.MAX_VALUE.toInt())
            if (level > peak) peak = level
            if (++channel < channels) continue
            channel = 0
            frame++
            // Integer slice boundaries: no drift over hours of audio.
            if (frame * peaksPerSecond / sampleRate > peaks.size()) flush()
        }
    }

    private fun flush() {
        peaks.write(peak * 255 / Short.MAX_VALUE)
        peak = 0
    }

    fun finish(): ByteArray {
        if (frame * peaksPerSecond % sampleRate != 0L) flush()
        return peaks.toByteArray()
    }

    companion object {
        const val PEAKS_PER_SECOND = 50

        /** Loudest peak between two source times, 0–1. */
        fun level(peaks: ByteArray, from: Double, to: Double, peaksPerSecond: Int = PEAKS_PER_SECOND): Float {
            val first = (from * peaksPerSecond).toInt().coerceAtLeast(0)
            val last = (to * peaksPerSecond).toInt().coerceAtMost(peaks.lastIndex)
            var loudest = 0
            for (index in first..last) loudest = maxOf(loudest, peaks[index].toInt() and 0xFF)
            return loudest / 255f
        }
    }
}
