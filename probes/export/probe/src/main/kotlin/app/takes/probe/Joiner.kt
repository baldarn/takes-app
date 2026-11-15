@file:OptIn(UnstableApi::class)

package app.takes.probe

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** What a file holds, read without decoding: enough to tell a whole section from a broken one. */
class Track(
    val format: MediaFormat, val samples: Int, val keys: Int, val firstKey: Boolean, val firstTime: Long, val lastTime: Long,
    val reordered: Boolean, val bytes: Long,
) {
    /** The codec's setup data, which a joined movie can hold only once: sections must agree on it. */
    val setup: String
        get() = (0..2).mapNotNull { index -> format.getByteBuffer("csd-$index")?.let { buffer ->
            val copy = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
            MessageDigest.getInstance("SHA-1").digest(copy).take(6).joinToString("") { "%02x".format(it) }
        } }.joinToString("+")

    /** The properties that must be equal across sections, as text. */
    val shape: String
        get() = listOf(
            MediaFormat.KEY_MIME, MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT, MediaFormat.KEY_ROTATION, MediaFormat.KEY_PROFILE, MediaFormat.KEY_LEVEL,
            MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.KEY_COLOR_RANGE, MediaFormat.KEY_SAMPLE_RATE, MediaFormat.KEY_CHANNEL_COUNT,
        ).mapNotNull { key -> if (format.containsKey(key)) "$key=" + (runCatching { format.getInteger(key).toString() }.getOrElse { format.getString(key) }) else null }
            .joinToString(" ")
}

object Joiner {
    private const val LARGEST_SAMPLE = 8 shl 20

    /** Reads the track of [file] whose type begins with [kind], or null when the file cannot be read or has none. */
    fun track(file: File, kind: String): Track? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            val index = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(kind) == true } ?: return null
            extractor.selectTrack(index)
            var samples = 0
            var keys = 0
            var firstKey = false
            var first = -1L
            var last = -1L
            var highest = Long.MIN_VALUE
            var reordered = false
            var bytes = 0L
            while (extractor.sampleSize >= 0) { // a time may be negative: a sound track begins before zero by its encoder's lead-in
                val key = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                if (samples == 0) { firstKey = key; first = extractor.sampleTime }
                if (extractor.sampleTime < highest) reordered = true
                highest = maxOf(highest, extractor.sampleTime)
                last = highest
                if (key) keys += 1
                samples += 1
                bytes += extractor.sampleSize
                extractor.advance()
            }
            Track(extractor.getTrackFormat(index), samples, keys, firstKey, first, last, reordered, bytes)
        } catch (_: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    private interface Sink {
        fun add(format: MediaFormat): Int
        fun begin() {}
        fun write(track: Int, buffer: ByteBuffer, time: Long, key: Boolean)
        fun end(track: Int, time: Long) {}
        fun close()
    }

    private class Media3Sink(file: File) : Sink {
        private val muxer = Mp4Muxer.Builder(FileOutputStream(file)).build()
        override fun add(format: MediaFormat) = muxer.addTrack(MediaFormatUtil.createFormatFromMediaFormat(format))
        override fun write(track: Int, buffer: ByteBuffer, time: Long, key: Boolean) =
            muxer.writeSampleData(track, buffer, BufferInfo(time, buffer.remaining(), if (key) C.BUFFER_FLAG_KEY_FRAME else 0))
        // An empty sample marked as the end tells the muxer how long the last picture lasts.
        override fun end(track: Int, time: Long) = muxer.writeSampleData(track, ByteBuffer.allocateDirect(0), BufferInfo(time, 0, C.BUFFER_FLAG_END_OF_STREAM))
        override fun close() = muxer.close()
    }

    private class FrameworkSink(file: File) : Sink {
        private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private val info = MediaCodec.BufferInfo()
        override fun add(format: MediaFormat) = muxer.addTrack(format)
        override fun begin() = muxer.start()
        override fun write(track: Int, buffer: ByteBuffer, time: Long, key: Boolean) {
            info.set(buffer.position(), buffer.remaining(), time, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            muxer.writeSampleData(track, buffer, info)
        }
        override fun close() { muxer.stop(); muxer.release() }
    }

    /** A track being read, one sample at a time, with the time each sample gets in the movie. */
    private class Reader(private val files: List<File>, private val kind: String, private val retime: (file: Int, time: Long) -> Long) {
        private var file = -1
        private var extractor: MediaExtractor? = null
        val buffer: ByteBuffer = ByteBuffer.allocateDirect(LARGEST_SAMPLE)
        var time = Long.MAX_VALUE; private set
        var key = false; private set
        var count = 0; private set

        init { open(); load() }

        private fun open() {
            extractor?.release()
            extractor = null
            file += 1
            if (file >= files.size) return
            extractor = MediaExtractor().also { opened ->
                opened.setDataSource(files[file].path)
                opened.selectTrack((0 until opened.trackCount).first { opened.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith(kind) })
            }
        }

        private fun load() {
            while (extractor != null && extractor!!.sampleSize < 0) open()
            val current = extractor
            if (current == null) { time = Long.MAX_VALUE; return }
            buffer.clear()
            val size = current.readSampleData(buffer, 0)
            buffer.position(0).limit(size)
            time = retime(file, current.sampleTime)
            key = current.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
        }

        val ended: Boolean get() = extractor == null

        fun next() {
            count += 1
            extractor!!.advance()
            load()
        }
    }

    /**
     * Writes the picture sections end to end into [output] without encoding anything, with the sound
     * beside them. Picture number n of the movie is given the time n / [framesPerSecond], whatever
     * time its section gave it, so nothing drifts over many sections. [frames] is what each section
     * is expected to hold. The sound is one file for the whole movie, or one file per section
     * ([soundStarts] then says where each begins).
     */
    fun join(pictures: List<File>, frames: List<Int>, framesPerSecond: Int, sound: List<File>, soundStarts: List<Long>, output: File, framework: Boolean): Pair<Int, Int> {
        val before = frames.runningFold(0) { sum, count -> sum + count }
        fun instant(frame: Int) = Math.round(frame * 1e6 / framesPerSecond)
        val sink: Sink = if (framework) FrameworkSink(output) else Media3Sink(output)
        val picture = sink.add(track(pictures.first(), "video/")!!.format)
        val audio = if (sound.isEmpty()) -1 else sink.add(track(sound.first(), "audio/")!!.format)
        sink.begin()
        val video = Reader(pictures, "video/") { file, time -> instant(before[file] + Math.round(time * framesPerSecond / 1e6).toInt()) }
        val voice = if (sound.isEmpty()) null else Reader(sound, "audio/") { file, time -> soundStarts[file] + time }
        while (!video.ended || voice?.ended == false) {
            if (voice != null && !voice.ended && voice.time <= video.time) {
                sink.write(audio, voice.buffer, voice.time, true)
                voice.next()
            } else {
                sink.write(picture, video.buffer, video.time, video.key)
                video.next()
            }
        }
        sink.end(picture, instant(before.last()))
        sink.close()
        return video.count to (voice?.count ?: 0)
    }
}
