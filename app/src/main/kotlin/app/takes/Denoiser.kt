package app.takes

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import app.takes.rnnoise.ChannelDenoiser
import app.takes.rnnoise.RNNoise
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

    /** Linear resampling to 48 kHz for sound recorded at another rate. Enough for speech; not for music mastering. */
    private class Resampler(inputRate: Int) {
        private val step = inputRate.toDouble() / RNNoise.SAMPLE_RATE
        private var position = 0.0 // of the next output sample, in input samples from the start of the chunk
        private var last = 0f
        private var primed = false
        var output = FloatArray(0)

        fun process(input: FloatArray, count: Int): Int {
            if (count == 0) return 0
            if (!primed) { last = input[0]; primed = true }
            val room = (count / step).toInt() + 2
            if (output.size < room) output = FloatArray(room)
            var written = 0
            while (true) {
                val index = Math.floor(position).toInt()
                if (index + 1 >= count) break
                val before = if (index < 0) last else input[index]
                val fraction = (position - index).toFloat()
                output[written++] = before + (input[index + 1] - before) * fraction
                position += step
            }
            last = input[count - 1]
            position -= count
            return written
        }
    }

    private fun render(file: File, amount: Float, target: File, progress: (Float) -> Unit, checkpoint: () -> Unit): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var denoisers = emptyList<ChannelDenoiser>()
        var stream: BufferedOutputStream? = null
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            val format = extractor.getTrackFormat(track)
            val length = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            extractor.selectTrack(track)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            var channels = 0
            var resamplers: List<Resampler>? = null
            var pending = emptyList<Pending>()
            var chunk = FloatArray(0)
            var bytes = ByteBuffer.allocate(0)
            var frames = 0L
            val output = BufferedOutputStream(FileOutputStream(target), 1 shl 16)
            stream = output
            output.write(ByteArray(44)) // the header is filled in at the end

            // Writes every frame all channels have delivered.
            fun drain() {
                val ready = pending.minOf { it.size }
                if (ready == 0) return
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

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0
            while (true) {
                checkpoint()
                if (!inputDone) {
                    val input = decoder.dequeueInputBuffer(10_000)
                    if (input >= 0) {
                        val size = extractor.readSampleData(decoder.getInputBuffer(input)!!, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val pcm = decoder.outputFormat
                    if (pcm.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        pcm.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT) return false
                    if (channels == 0) {
                        channels = pcm.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val rate = pcm.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        denoisers = List(channels) { ChannelDenoiser(amount) } // each channel has its own state, as in the original
                        pending = List(channels) { Pending() }
                        resamplers = if (rate == RNNoise.SAMPLE_RATE) null else List(channels) { Resampler(rate) }
                    }
                } else if (index >= 0) {
                    idle = 0
                    if (info.size > 0 && channels > 0) {
                        val buffer = decoder.getOutputBuffer(index)!!
                        buffer.position(info.offset).limit(info.offset + info.size)
                        val samples = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val count = samples.remaining() / channels
                        if (chunk.size < count) chunk = FloatArray(count)
                        for (channel in 0 until channels) {
                            for (frame in 0 until count) chunk[frame] = samples.get(samples.position() + frame * channels + channel) / 32768f
                            val resampler = resamplers?.get(channel)
                            if (resampler == null) denoisers[channel].write(chunk, 0, count, pending[channel].take)
                            else {
                                val resampled = resampler.process(chunk, count)
                                denoisers[channel].write(resampler.output, 0, resampled, pending[channel].take)
                            }
                        }
                        drain()
                        if (length > 0) progress((info.presentationTimeUs.toFloat() / length).coerceIn(0f, 1f))
                    }
                    decoder.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (inputDone && ++idle > 500) {
                    return false // the decoder never signalled the end
                }
            }
            if (channels == 0) return false
            for (channel in 0 until channels) denoisers[channel].finish(pending[channel].take)
            drain()
            output.close()
            stream = null
            if (frames == 0L) return false
            writeHeader(target, channels, frames)
            progress(1f)
            return true
        } finally {
            runCatching { stream?.close() }
            denoisers.forEach { runCatching { it.close() } }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun writeHeader(file: File, channels: Int, frames: Long) {
        val data = frames * channels * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt((36 + data).toInt()).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
        header.putInt(RNNoise.SAMPLE_RATE).putInt(RNNoise.SAMPLE_RATE * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        header.put("data".toByteArray()).putInt(data.toInt())
        RandomAccessFile(file, "rw").use { it.seek(0); it.write(header.array()) }
    }
}
