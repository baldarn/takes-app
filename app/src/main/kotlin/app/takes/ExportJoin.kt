@file:OptIn(UnstableApi::class)

package app.takes

import app.takes.core.ExportProblem
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4OrientationData
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import app.takes.core.ExportProgress
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * What a rendered file holds on one track, read without decoding: enough to tell a whole section from one
 * that was cut short. A file the transformer was stopped in opens and plays, so only its count of frames
 * says whether it is complete.
 */
class ExportTrack(val format: MediaFormat, val samples: Int, val startsOnKey: Boolean, val largest: Int) {
    /**
     * What two sections must agree on to be joined by copying: an MP4 track holds the codec's setup data
     * once, and one size, profile and color.
     */
    val shape: String
        get() = SHAPE.mapNotNull { key -> if (format.containsKey(key)) "$key=" + (runCatching { format.getInteger(key).toString() }.getOrElse { format.getString(key) }) else null }
            .joinToString(" ") + (0..2).joinToString("") { index ->
            format.getByteBuffer("csd-$index")?.let { buffer -> " " + ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }.joinToString("") { "%02x".format(it) } }.orEmpty()
        }

    companion object {
        /** Room for a sample whose size nothing states. */
        private const val UNKNOWN_SIZE = 16 shl 20

        private val SHAPE = listOf(
            MediaFormat.KEY_MIME, MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT, MediaFormat.KEY_ROTATION, MediaFormat.KEY_PROFILE, MediaFormat.KEY_LEVEL,
            MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.KEY_COLOR_RANGE,
        )

        /** The track of [file] whose type begins with [kind] ("video/", "audio/"), or null when the file cannot be read or has none. */
        fun read(file: File, kind: String): ExportTrack? {
            val extractor = MediaExtractor()
            return try {
                extractor.setDataSource(file.path)
                val index = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(kind) == true } ?: return null
                extractor.selectTrack(index)
                var samples = 0
                var startsOnKey = false
                var largest = 0
                // Until no sample is left, which the track's number says: a time may be negative, a sound track
                // begins before zero by its encoder's lead-in.
                while (extractor.sampleTrackIndex >= 0) {
                    if (samples == 0) startsOnKey = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                    samples += 1
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) largest = maxOf(largest, extractor.sampleSize.toInt())
                    extractor.advance()
                }
                val format = extractor.getTrackFormat(index)
                // Before Android 9 a sample's size is not told ahead: the largest the track states, or room for any.
                if (largest == 0) largest = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else UNKNOWN_SIZE
                ExportTrack(format, samples, startsOnKey, largest)
            } catch (_: Exception) {
                null
            } finally {
                extractor.release()
            }
        }
    }
}

/** Joins the picture sections of an export and its sound into the movie, without encoding anything. */
object ExportJoin {
    /** One track being read, a sample at a time, over one file or several end to end, with the time each sample gets in the movie. */
    private class Reader(private val files: List<File>, private val kind: String, largest: Int, private val retime: (file: Int, time: Long) -> Long) {
        private var file = -1
        private var extractor: MediaExtractor? = null
        val buffer: ByteBuffer = ByteBuffer.allocateDirect(largest)
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
            while (extractor != null && extractor!!.sampleTrackIndex < 0) open()
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

        fun close() {
            extractor?.release()
            extractor = null
        }
    }

    /**
     * Writes the [pictures] end to end into [output] with the [sound] beside them. Picture number n of the
     * movie is given the time n / [framesPerSecond], whatever time its section gave it, so nothing adds up
     * over many sections; the sound keeps its own times. [frames] is what each section holds and [largest]
     * the size of the largest sample of all the files.
     *
     * [progress] is told the share of the step that is done, [writing] being the last of it, when the movie's
     * index is written; [active] is asked between samples and stops the join by throwing.
     */
    fun join(
        pictures: List<File>, frames: List<Int>, framesPerSecond: Int, sound: File, largest: Int, output: File,
        progress: (Double) -> Unit, writing: () -> Unit, active: () -> Unit,
    ) {
        val before = frames.runningFold(0) { sum, count -> sum + count }
        fun instant(frame: Int) = Math.round(frame * 1e6 / framesPerSecond)
        val picture = ExportTrack.read(pictures.first(), "video/") ?: throw ExportProblem(ExportProblem.Reason.SavedSectionUnreadable)
        val voice = ExportTrack.read(sound, "audio/") ?: throw ExportProblem(ExportProblem.Reason.SavedSoundUnreadable)
        val muxer = Mp4Muxer.Builder(FileOutputStream(output)).build()
        var video: Reader? = null
        var audio: Reader? = null
        var written = false
        try {
            // In the order the one pass of a transformer writes them: the sound, then the picture.
            val soundTrack = muxer.addTrack(MediaFormatUtil.createFormatFromMediaFormat(voice.format))
            val pictureTrack = muxer.addTrack(MediaFormatUtil.createFormatFromMediaFormat(picture.format))
            // An upright movie is encoded lying down and marked as turned. The mark belongs to the file, not to
            // the track's format, and is carried over from the sections.
            val turned = if (picture.format.containsKey(MediaFormat.KEY_ROTATION)) picture.format.getInteger(MediaFormat.KEY_ROTATION) else 0
            if (turned != 0) muxer.addMetadataEntry(Mp4OrientationData(turned))
            video = Reader(pictures, "video/", largest) { file, time -> instant(before[file] + Math.round(time * framesPerSecond / 1e6).toInt()) }
            audio = Reader(listOf(sound), "audio/", largest) { _, time -> time }
            val total = before.last().toDouble()
            // Until the sound and the picture are both written. The movie ends with its picture, as when it was
            // made in one pass, whose sound is as long as this one.
            while (!video.ended || !audio.ended) {
                if (!audio.ended && audio.time <= video.time) {
                    muxer.writeSampleData(soundTrack, audio.buffer, BufferInfo(audio.time, audio.buffer.remaining(), C.BUFFER_FLAG_KEY_FRAME))
                    audio.next()
                } else {
                    muxer.writeSampleData(pictureTrack, video.buffer, BufferInfo(video.time, video.buffer.remaining(), if (video.key) C.BUFFER_FLAG_KEY_FRAME else 0))
                    video.next()
                    if (video.count % ExportProgress.ASSEMBLY_REPORT_EVERY == 0) {
                        active()
                        progress(ExportProgress.assemblyWrite(video.count, total))
                    }
                }
            }
            if (video.count != before.last()) throw ExportProblem(ExportProblem.Reason.SectionsNotWhole)
            // An empty sample marked as the end tells the muxer how long the last picture lasts.
            muxer.writeSampleData(pictureTrack, ByteBuffer.allocateDirect(0), BufferInfo(instant(before.last()), 0, C.BUFFER_FLAG_END_OF_STREAM))
            writing()
            written = true
        } finally {
            video?.close()
            audio?.close()
            // Closing writes the index. After a failure the file is given up, and what closing it says with it.
            if (written) muxer.close() else runCatching { muxer.close() }
        }
    }
}
