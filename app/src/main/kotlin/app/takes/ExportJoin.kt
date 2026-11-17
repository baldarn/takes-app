@file:OptIn(UnstableApi::class)

package app.takes

import app.takes.core.ExportProblem
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4OrientationData
import androidx.media3.muxer.AnnexBToAvccConverter
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.ByteBufferAllocator
import androidx.media3.muxer.Mp4Muxer
import app.takes.core.ExportProgress
import app.takes.core.Mp4Samples
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

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

        /**
         * The track of [file] whose type begins with [kind] ("video/", "audio/"), or null when the file cannot be read or has none.
         * Its samples are counted from the file's sample tables ([Mp4Samples]), else one at a time by the extractor
         * ([counted]).
         */
        fun read(file: File, kind: String): ExportTrack? {
            val extractor = MediaExtractor()
            return try {
                extractor.setDataSource(file.path)
                val index = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(kind) == true } ?: return null
                Mp4Samples.read(file, if (kind == "video/") Mp4Samples.VIDEO else Mp4Samples.SOUND)?.let { table ->
                    return ExportTrack(extractor.getTrackFormat(index), table.samples, table.startsOnKey, table.largest)
                }
                counted(extractor, index)
            } catch (_: Exception) {
                null
            } finally {
                extractor.release()
            }
        }

        /** As [read], the samples counted one at a time by the extractor: to check [Mp4Samples] against. */
        fun counted(file: File, kind: String): ExportTrack? {
            val extractor = MediaExtractor()
            return try {
                extractor.setDataSource(file.path)
                val index = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(kind) == true } ?: return null
                counted(extractor, index)
            } catch (_: Exception) {
                null
            } finally {
                extractor.release()
            }
        }

        private fun counted(extractor: MediaExtractor, index: Int): ExportTrack {
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
            return ExportTrack(format, samples, startsOnKey, largest)
        }
    }
}

/** Joins the picture sections of an export and its sound into the movie, without encoding anything. */
object ExportJoin {
    /** Whether samples are read from the files' tables where they can be; false to compare with the extractor's, in a test. */
    @VisibleForTesting internal var readsTables = true

    /** A sample that is already as the file holds it: nothing to turn. */
    private object AsWritten : AnnexBToAvccConverter {
        override fun process(input: ByteBuffer): ByteBuffer = input
        override fun process(input: ByteBuffer, allocator: ByteBufferAllocator): ByteBuffer = input
    }

    /** One track being read, a sample at a time, over one file or several end to end, with the time each sample gets in the movie. */
    private abstract class Reader(largest: Int) {
        val buffer: ByteBuffer = ByteBuffer.allocateDirect(largest)
        var time = Long.MAX_VALUE; protected set
        var key = false; protected set
        var count = 0; private set
        abstract val ended: Boolean

        fun next() {
            count += 1
            advance()
        }

        protected abstract fun advance()
        abstract fun close()
    }

    /**
     * The samples as the system's extractor gives them, from its media service: one call across processes a sample,
     * and the picture in Annex B, which the muxer turns back into what the files hold.
     */
    private class Extracted(private val files: List<File>, private val kind: String, largest: Int, private val retime: (file: Int, time: Long) -> Long) : Reader(largest) {
        private var file = -1
        private var extractor: MediaExtractor? = null

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

        override val ended: Boolean get() = extractor == null

        override fun advance() {
            extractor!!.advance()
            load()
        }

        override fun close() {
            extractor?.release()
            extractor = null
        }
    }

    /**
     * The samples read from the files themselves, where their sample tables say they lie ([Mp4Samples.table]), as
     * the files hold them. On a Galaxy A54 the extractor's calls took two seconds of the join of a 73-second movie.
     */
    private class Tabled(private val files: List<File>, private val tables: List<Mp4Samples.Table>, largest: Int, private val retime: (file: Int, time: Long) -> Long) : Reader(largest) {
        private var file = 0
        private var sample = 0
        private var channel: FileChannel? = files.firstOrNull()?.let { FileInputStream(it).channel }

        init { load() }

        private fun load() {
            while (file < files.size && sample >= tables[file].count) {
                channel?.close()
                file += 1
                sample = 0
                channel = files.getOrNull(file)?.let { FileInputStream(it).channel }
            }
            val reading = channel
            if (file >= files.size || reading == null) { time = Long.MAX_VALUE; return }
            val table = tables[file]
            buffer.clear().limit(table.sizes[sample])
            var at = table.offsets[sample]
            while (buffer.hasRemaining()) {
                val read = reading.read(buffer, at)
                if (read < 0) throw ExportProblem(ExportProblem.Reason.SavedSectionUnreadable)
                at += read
            }
            buffer.flip()
            time = retime(file, table.micros[sample])
            key = table.keys[sample]
        }

        override val ended: Boolean get() = file >= files.size

        override fun advance() {
            sample += 1
            load()
        }

        override fun close() {
            channel?.close()
            channel = null
            file = files.size
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
        // The samples straight from the files where their tables can be read, else through the extractor.
        val pictureTables = pictures.map { Mp4Samples.table(it, Mp4Samples.VIDEO) }.takeIf { tables -> readsTables && tables.all { it != null } }?.filterNotNull()
        val soundTable = Mp4Samples.table(sound, Mp4Samples.SOUND)?.takeIf { readsTables }
        val muxer = Mp4Muxer.Builder(FileOutputStream(output)).apply {
            // A picture read from the files is as the muxer writes it, its units preceded by their lengths, and goes in as it is.
            if (pictureTables != null) setAnnexBToAvccConverter(AsWritten)
        }.build()
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
            val frame = { file: Int, time: Long -> instant(before[file] + Math.round(time * framesPerSecond / 1e6).toInt()) }
            video = if (pictureTables != null) Tabled(pictures, pictureTables, largest, frame) else Extracted(pictures, "video/", largest, frame)
            audio = if (soundTable != null) Tabled(listOf(sound), listOf(soundTable), largest) { _, time -> time } else Extracted(listOf(sound), "audio/", largest) { _, time -> time }
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
