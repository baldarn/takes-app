package app.takes

import app.takes.rnnoise.ChannelDenoiser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * Noise reduction for a recording's sound. The cleaned sound is made once per
 * recording and amount, as a 48 kHz WAV in the project's `Cache` folder; the
 * recording itself is never changed. Preview and export play the cleaned file
 * in place of the recording's own sound.
 */
object Denoiser {
    private val working = Dispatchers.Default.limitedParallelism(1)

    private fun cached(file: File, amount: Double) =
        File(File(file.parentFile, "Cache"), "${file.name}.clean-${(amount * 100).roundToInt()}.wav")

    /** The cleaned sound, if it has already been made. */
    fun ready(file: File, amount: Double): File? = cached(file, amount).takeIf { it.isFile }

    /** Makes the cleaned sound if needed. Null when the recording has no sound that can be cleaned. */
    suspend fun process(file: File, amount: Double, progress: (Float) -> Unit = {}): File? = withContext(working) {
        ready(file, amount)?.let { return@withContext it }
        val output = cached(file, amount)
        output.parentFile?.mkdirs()
        // Hidden until complete: a half-written file must never be played.
        val partial = File(output.parentFile, ".${output.name}.tmp")
        try {
            val context = coroutineContext
            if (render(file, amount.toFloat(), partial, progress) { context.ensureActive() } && partial.renameTo(output)) output else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null // no native library for this processor: the recorded sound is used
        } finally {
            partial.delete()
        }
    }

    /** Cleaned samples of one channel waiting for the other channels, so frames can be written interleaved. */
    private class Pending {
        var samples = FloatArray(8192)
        var size = 0
        val take = { block: FloatArray, count: Int ->
            if (size + count > samples.size) samples = samples.copyOf(maxOf(samples.size * 2, size + count))
            System.arraycopy(block, 0, samples, size, count)
            size += count
        }
    }

    private fun render(file: File, amount: Float, target: File, progress: (Float) -> Unit, checkpoint: () -> Unit): Boolean {
        var denoisers = emptyList<ChannelDenoiser>()
        val output = BufferedOutputStream(FileOutputStream(target), 1 shl 16)
        try {
            var inputs = emptyList<Channel48k>()
            var pending = emptyList<Pending>()
            var length = 0L
            var bytes = ByteBuffer.allocate(0)
            var frames = 0L
            output.write(ByteArray(44)) // the header is filled in at the end

            // Writes every frame all channels have delivered.
            fun drain() {
                val ready = pending.minOf { it.size }
                if (ready == 0) return
                val channels = pending.size
                if (bytes.capacity() < ready * channels * 2) bytes = ByteBuffer.allocate(ready * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
                bytes.clear()
                for (frame in 0 until ready) for (channel in pending) {
                    bytes.putShort((channel.samples[frame] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
                }
                output.write(bytes.array(), 0, ready * channels * 2)
                frames += ready
                for (channel in pending) {
                    System.arraycopy(channel.samples, ready, channel.samples, 0, channel.size - ready)
                    channel.size -= ready
                }
            }

            val decoded = decodeAudio(
                file, checkpoint,
                begin = { format ->
                    length = format.durationUs
                    inputs = List(format.channels) { Channel48k(it, format.channels, format.sampleRate) }
                    denoisers = List(format.channels) { ChannelDenoiser(amount) } // each channel has its own state, as in the original
                    pending = List(format.channels) { Pending() }
                },
                samples = { block, time ->
                    for (channel in inputs.indices) {
                        val count = inputs[channel].read(block)
                        denoisers[channel].write(inputs[channel].samples, 0, count, pending[channel].take)
                    }
                    drain()
                    if (length > 0) progress((time.toFloat() / length).coerceIn(0f, 1f))
                },
            )
            if (decoded != true) return false
            for (channel in denoisers.indices) denoisers[channel].finish(pending[channel].take)
            drain()
            output.close()
            if (frames == 0L) return false
            writeHeader(target, denoisers.size, frames)
            progress(1f)
            return true
        } finally {
            runCatching { output.close() }
            denoisers.forEach { runCatching { it.close() } }
        }
    }

    private fun writeHeader(file: File, channels: Int, frames: Long) {
        val data = frames * channels * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt((36 + data).toInt()).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
        header.putInt(Channel48k.RATE).putInt(Channel48k.RATE * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        header.put("data".toByteArray()).putInt(data.toInt())
        RandomAccessFile(file, "rw").use { it.seek(0); it.write(header.array()) }
    }
}
