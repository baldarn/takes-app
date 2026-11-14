package app.takes

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.LruCache
import app.takes.core.ProjectDisk
import app.takes.core.WaveformBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder

/**
 * Audio peaks of a recording, decoded once and kept in the project's `Cache`
 * folder. Empty for a recording without sound; null when it cannot be decoded.
 */
object Waveforms {
    private val memory = LruCache<String, ByteArray>(64)
    private val decoding = Dispatchers.IO.limitedParallelism(1)

    suspend fun peaks(file: File): ByteArray? {
        memory.get(file.path)?.let { return it }
        return withContext(decoding) {
            val cache = File(File(file.parentFile, "Cache"), file.name + ".peaks")
            val peaks = if (cache.isFile) cache.readBytes() else runCatching { decode(file) }.getOrNull()?.also {
                cache.parentFile?.mkdirs()
                runCatching { ProjectDisk.writeAtomically(it, cache) }
            }
            peaks?.also { memory.put(file.path, it) }
        }
    }

    private fun decode(file: File): ByteArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return ByteArray(0)
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            var builder: WaveformBuilder? = null
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0
            while (true) {
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
                    builder = WaveformBuilder(pcm.getInteger(MediaFormat.KEY_SAMPLE_RATE), pcm.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                } else if (output >= 0) {
                    idle = 0
                    if (info.size > 0) {
                        val buffer = decoder.getOutputBuffer(output)!!
                        buffer.position(info.offset).limit(info.offset + info.size)
                        builder?.add(buffer.order(ByteOrder.nativeOrder()).asShortBuffer())
                    }
                    decoder.releaseOutputBuffer(output, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (inputDone && ++idle > 500) {
                    return null // the decoder never signalled the end
                }
            }
            return builder?.finish() ?: ByteArray(0)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
