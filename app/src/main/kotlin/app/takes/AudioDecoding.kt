package app.takes

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder
import java.nio.ShortBuffer

/** What a file's sound decodes to. */
class DecodedFormat(val sampleRate: Int, val channels: Int, val durationUs: Long)

/**
 * Decodes a file's first audio track to interleaved 16-bit PCM, in order.
 * [begin] is called once before any samples; [samples] receives each decoded
 * block and its presentation time. [checkpoint] runs between blocks and may
 * throw to stop.
 *
 * With [from] it starts at the sync sample at or before that second, and it
 * stops once a block begins past [until] seconds, which counts as the end.
 *
 * Returns true when the track was decoded to its end, false when the file has
 * no sound, and null when its sound cannot be decoded this way.
 */
fun decodeAudio(
    file: File, checkpoint: () -> Unit = {}, begin: (DecodedFormat) -> Unit, from: Double = 0.0, until: Double = Double.POSITIVE_INFINITY,
    samples: (ShortBuffer, Long) -> Unit,
): Boolean? {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    try {
        extractor.setDataSource(file.path)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return false
        val format = extractor.getTrackFormat(track)
        val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        extractor.selectTrack(track)
        if (from > 0) extractor.seekTo((from * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec = decoder
        decoder.configure(format, null, null, 0)
        decoder.start()
        val info = MediaCodec.BufferInfo()
        var begun = false
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
            val output = decoder.dequeueOutputBuffer(info, 10_000)
            if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val pcm = decoder.outputFormat
                if (pcm.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                    pcm.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT) return null
                // A format change in the middle of a track is not handled; the first one stands.
                if (!begun) {
                    begun = true
                    begin(DecodedFormat(pcm.getInteger(MediaFormat.KEY_SAMPLE_RATE), pcm.getInteger(MediaFormat.KEY_CHANNEL_COUNT), duration))
                }
            } else if (output >= 0) {
                idle = 0
                if (info.size > 0 && begun && info.presentationTimeUs > until * 1e6) {
                    decoder.releaseOutputBuffer(output, false)
                    return true
                }
                if (info.size > 0 && begun) {
                    val buffer = decoder.getOutputBuffer(output)!!
                    buffer.position(info.offset).limit(info.offset + info.size)
                    samples(buffer.order(ByteOrder.nativeOrder()).asShortBuffer(), info.presentationTimeUs)
                }
                decoder.releaseOutputBuffer(output, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return begun
            } else if (inputDone && ++idle > 500) {
                return null // the decoder never signalled the end
            }
        }
    } finally {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        extractor.release()
    }
}

/**
 * One channel of a decoded track as normalized floats at 48 kHz, the rate
 * RNNoise works at. Sound at another rate is resampled linearly: enough for
 * speech analysis and cleaning, not for music mastering.
 */
class Channel48k(private val channel: Int, private val channels: Int, inputRate: Int) {
    private val step = inputRate.toDouble() / RATE
    private val direct = inputRate == RATE
    private var position = 0.0 // of the next output sample, in input samples from the start of the block
    private var last = 0f
    private var primed = false
    private var input = FloatArray(0)

    /** The samples produced by the latest [read]. */
    var samples = FloatArray(0); private set

    /** Takes this channel out of an interleaved block. Returns how many of [samples] are valid. */
    fun read(block: ShortBuffer): Int {
        val count = block.remaining() / channels
        val start = block.position()
        if (direct) {
            if (samples.size < count) samples = FloatArray(count)
            for (frame in 0 until count) samples[frame] = block.get(start + frame * channels + channel) / 32768f
            return count
        }
        if (count == 0) return 0
        if (input.size < count) input = FloatArray(count)
        for (frame in 0 until count) input[frame] = block.get(start + frame * channels + channel) / 32768f
        if (!primed) { last = input[0]; primed = true }
        val room = (count / step).toInt() + 2
        if (samples.size < room) samples = FloatArray(room)
        var written = 0
        while (true) {
            val index = Math.floor(position).toInt()
            if (index + 1 >= count) break
            val before = if (index < 0) last else input[index]
            samples[written++] = before + (input[index + 1] - before) * (position - index).toFloat()
            position += step
        }
        last = input[count - 1]
        position -= count
        return written
    }

    companion object {
        const val RATE = 48_000
    }
}
