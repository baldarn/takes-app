package app.takes

import android.util.LruCache
import app.takes.core.SourceWaveform
import app.takes.rnnoise.RNNoise
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The measurements pause detection works on: for every 10 ms of a recording,
 * its peak, its energy in the louder channel, and how likely it is to be a
 * voice. Made on request and kept in memory for the recordings last used.
 */
object PauseLevels {
    private val cache = LruCache<String, SourceWaveform>(4)
    private val working = Dispatchers.Default.limitedParallelism(1)
    private const val SLICE = RNNoise.FRAME_SIZE // 480 samples at 48 kHz: 10 ms, and one RNNoise frame

    /** Null when the recording has no sound to measure. */
    suspend fun measure(file: File, progress: (Float) -> Unit = {}): SourceWaveform? {
        cache.get(file.path)?.let { return it }
        return withContext(working) {
            try {
                val context = coroutineContext
                analyze(file, progress) { context.ensureActive() }?.also { cache.put(file.path, it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    private class Growing {
        var values = FloatArray(4096)
        var size = 0
        fun add(value: Float) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = value
        }
    }

    private fun analyze(file: File, progress: (Float) -> Unit, checkpoint: () -> Unit): SourceWaveform? {
        // Without the native library there is no voice measure; pauses are then found by level alone.
        val voice = try { RNNoise() } catch (_: LinkageError) { null }
        try {
            var inputs = emptyList<Channel48k>()
            var waiting = emptyList<Growing>() // samples of each channel not yet in a whole slice
            var length = 0L
            val peaks = Growing()
            val energy = Growing()
            val speech = Growing()
            val mix = FloatArray(SLICE)
            val scratch = FloatArray(SLICE)

            fun slice(valid: Int) {
                var peak = 0f
                var loudest = 0.0
                mix.fill(0f)
                for (channel in waiting) {
                    var sum = 0.0
                    for (index in 0 until valid) {
                        val sample = channel.values[index]
                        peak = maxOf(peak, abs(sample))
                        sum += sample * sample
                        mix[index] += sample / waiting.size
                    }
                    loudest = maxOf(loudest, sum / SLICE)
                }
                peaks.add(minOf(1f, peak))
                energy.add(sqrt(loudest).toFloat())
                speech.add(voice?.process(mix, 0, scratch, 0) ?: 0f)
            }

            val decoded = decodeAudio(
                file, checkpoint,
                begin = { format ->
                    length = format.durationUs
                    inputs = List(format.channels) { Channel48k(it, format.channels, format.sampleRate) }
                    waiting = List(format.channels) { Growing() }
                },
                samples = { block, time ->
                    for (channel in inputs.indices) {
                        val count = inputs[channel].read(block)
                        for (index in 0 until count) waiting[channel].add(inputs[channel].samples[index])
                    }
                    while (waiting.all { it.size >= SLICE }) {
                        slice(SLICE)
                        for (channel in waiting) {
                            System.arraycopy(channel.values, SLICE, channel.values, 0, channel.size - SLICE)
                            channel.size -= SLICE
                        }
                    }
                    if (length > 0) progress((time.toFloat() / length).coerceIn(0f, 1f))
                },
            )
            if (decoded != true) return null
            val left = waiting.minOfOrNull { it.size } ?: 0
            if (left > 0) slice(left)
            if (peaks.size == 0) return null
            return SourceWaveform(
                duration = peaks.size * SLICE.toDouble() / Channel48k.RATE,
                peaks = peaks.values.copyOf(peaks.size),
                rms = energy.values.copyOf(energy.size),
                speech = if (voice == null) null else ByteArray(speech.size) { (speech.values[it] * 255).toInt().coerceIn(0, 255).toByte() },
            )
        } finally {
            runCatching { voice?.close() }
        }
    }
}
