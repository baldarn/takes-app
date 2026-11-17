@file:OptIn(UnstableApi::class)

package app.takes

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Codec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * The priming of an AAC encoder: how many frames of its first output come before the first frame it was given.
 * A movie's sound track begins with them, and its edit list says how many to skip, as AVFoundation writes them
 * for the original.
 *
 * Media3 writes the edit list from what the encoder's format says of its delay, and for Android's own AAC encoder
 * (`c2.android.aac.encoder`) it says 1600 frames whatever the encoder does: on a Galaxy A54 that encoder primes
 * 2048 frames at 48 kHz, so the sound of every export lay 448 frames (9.3 ms) late. Here the priming is measured
 * on the encoder itself, once a process for each configuration: a click is encoded and decoded again, and where it
 * comes out says it. Android's decoders give AAC's first frame as its first output, as ffmpeg does.
 */
object AacPriming {
    private val measured = ConcurrentHashMap<String, Int>()
    private const val CLICK = 4096
    private const val LENGTH = 16 * 1024

    /** The priming in frames of the encoder [name] at [rate] Hz in [channels] channels and [bitrate] bit/s, or null when it cannot be measured. */
    fun frames(name: String, rate: Int, channels: Int, bitrate: Int): Int? {
        val key = "$name $rate $channels $bitrate"
        measured[key]?.let { return it }
        return runCatching { measure(name, rate, channels, bitrate) }.getOrNull()?.also { measured[key] = it }
    }

    private fun measure(name: String, rate: Int, channels: Int, bitrate: Int): Int? {
        val packets = mutableListOf<ByteArray>()
        val encoded = encode(name, rate, channels, bitrate, packets) ?: return null
        val decoded = decode(encoded, packets) ?: return null
        var best = 0
        for (frame in decoded.indices) if (abs(decoded[frame]) > abs(decoded[best])) best = frame
        if (abs(decoded[best]) < 0.1f) return null
        return (best - CLICK).takeIf { it in 0..8192 }
    }

    /** The click encoded by [name], its packets into [packets]; the encoder's output format. */
    private fun encode(name: String, rate: Int, channels: Int, bitrate: Int, packets: MutableList<ByteArray>): MediaFormat? {
        val pcm = ByteBuffer.allocate(LENGTH * channels * 2).order(ByteOrder.nativeOrder())
        repeat(channels) { pcm.putShort((CLICK * channels + it) * 2, 16000) }
        val encoder = MediaCodec.createByCodecName(name)
        try {
            encoder.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            return run(encoder, { input, at ->
                val bytes = minOf(input.remaining(), pcm.capacity() - at).let { it - it % (2 * channels) }
                if (bytes <= 0) -1 else {
                    input.put(pcm.array(), at, bytes)
                    bytes
                }
            }, { at -> at * 1_000_000L / (2L * channels * rate) }) { output, info ->
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) packets += ByteArray(info.size).also { output.get(it) }
            }
        } finally {
            runCatching { encoder.stop() }
            encoder.release()
        }
    }

    /** The first channel of [packets] decoded, from their first frame, whatever time the decoder gives it. */
    private fun decode(format: MediaFormat, packets: List<ByteArray>): FloatArray? {
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val samples = FloatArray(LENGTH + 8192)
        var written = 0
        try {
            decoder.configure(format, null, null, 0)
            decoder.start()
            var next = 0
            run(decoder, { input, _ ->
                if (next >= packets.size) -1 else {
                    input.put(packets[next++])
                    packets[next - 1].size
                }
            }, { 0L }) { output, info ->
                val channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val shorts = output.order(ByteOrder.nativeOrder()).asShortBuffer()
                for (frame in 0 until info.size / 2 / channels) if (written < samples.size) samples[written++] = shorts.get(frame * channels) / 32768f
            } ?: return null
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
        }
        return samples.copyOf(written)
    }

    /**
     * Runs [codec] until its end: [fill] puts the next input into a buffer from the given byte and says how many
     * bytes, or -1 at the end, [time] gives an input's time from its first byte, and [take] is given each output.
     */
    private fun run(codec: MediaCodec, fill: (ByteBuffer, Int) -> Int, time: (Int) -> Long, take: (ByteBuffer, MediaCodec.BufferInfo) -> Unit): MediaFormat? {
        val info = MediaCodec.BufferInfo()
        var consumed = 0
        var inputEnded = false
        var format: MediaFormat? = null
        repeat(5000) {
            if (!inputEnded) {
                val index = codec.dequeueInputBuffer(1000)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!
                    val bytes = fill(buffer, consumed)
                    if (bytes < 0) {
                        codec.queueInputBuffer(index, 0, 0, time(consumed), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        codec.queueInputBuffer(index, 0, bytes, time(consumed), 0)
                        consumed += bytes
                    }
                }
            }
            val index = codec.dequeueOutputBuffer(info, 1000)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) format = codec.outputFormat
            if (index >= 0) {
                val output = codec.getOutputBuffer(index)!!
                output.position(info.offset).limit(info.offset + info.size)
                take(output, info)
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return format ?: codec.outputFormat
            }
        }
        return null
    }
}

/**
 * An AAC encoder of Media3 whose output format states the priming it was measured to have ([AacPriming]), from
 * which Media3 times the samples and writes the edit list; one that cannot be measured keeps what Media3 says.
 */
class PrimedAudioEncoder(private val codec: Codec) : Codec by codec {
    private val priming: Int? by lazy {
        val configured = codec.configurationFormat
        if (configured.sampleMimeType != androidx.media3.common.MimeTypes.AUDIO_AAC) null
        else AacPriming.frames(codec.name, configured.sampleRate, configured.channelCount, configured.bitrate.takeIf { it > 0 } ?: 131072)
    }

    override fun getOutputFormat(): Format? = codec.outputFormat?.let { format -> priming?.let { format.buildUpon().setEncoderDelay(it).build() } ?: format }
}
