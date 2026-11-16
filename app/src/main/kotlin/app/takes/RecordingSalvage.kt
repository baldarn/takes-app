package app.takes

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import app.takes.core.RecoveryRules
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Reading a recording that may be damaged, and copying what of it can be read,
 * as the original's RecordingSalvage (YouRec/RecordingSalvage.swift:49-257).
 * Never changes the recording itself.
 *
 * Android's MediaExtractor and MediaMuxer stand in for AVFoundation's reader
 * and writer: the samples are checked by reading them, not by decoding them,
 * and a repaired copy is an MPEG-4 movie.
 */
internal object RecordingSalvage {
    /** A readable recording: how long it lasts, and whether it is sound only. */
    data class Media(val duration: Double, val audioOnly: Boolean)

    /** The recording cannot be read, or nothing of it could be copied. */
    class Unreadable : IOException("The recording could not be read.")

    /** The repair copy would go past its budget, or into the storage's floor. */
    class OutOfSpace(message: String?) : IOException(message)

    private const val SECOND = 1_000_000L

    /**
     * The recording as it is, when it opens with a picture or a sound and each
     * of them gives a sample at its start and 0.4 s before its end
     * (YouRec/RecordingSalvage.swift:85-114). Throws [Unreadable] otherwise.
     */
    fun inspect(file: File): Media {
        val extractor = MediaExtractor()
        try {
            try {
                extractor.setDataSource(file.path)
            } catch (_: Exception) {
                throw Unreadable()
            }
            val tracks = (0 until extractor.trackCount).map { it to extractor.getTrackFormat(it) }
            val video = tracks.firstOrNull { it.second.mime.startsWith("video/") }
            val audio = tracks.firstOrNull { it.second.mime.startsWith("audio/") }
            if (video == null && audio == null) throw Unreadable()
            // A fragmented movie may give its tracks no length: then they last as long as the movie.
            val whole = mediaDuration(file)
            val spans = listOfNotNull(video, audio).map { (index, format) ->
                val span = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / SECOND.toDouble() else whole ?: 0.0
                if (!RecoveryRules.plausibleDuration(span)) throw Unreadable()
                for (offset in RecoveryRules.probeOffsets(span)) if (!sampleAt(extractor, index, (offset * SECOND).toLong())) throw Unreadable()
                span
            }
            // The movie lasts as long as its longest track, as AVFoundation's asset does.
            val duration = whole ?: spans.max()
            if (!RecoveryRules.plausibleDuration(duration)) throw Unreadable()
            return Media(duration, audioOnly = video == null)
        } finally {
            extractor.release()
        }
    }

    /** Whether track [index] gives a sample with data at or after [time] microseconds. */
    private fun sampleAt(extractor: MediaExtractor, index: Int, time: Long): Boolean = try {
        for (other in 0 until extractor.trackCount) extractor.unselectTrack(other)
        extractor.selectTrack(index)
        extractor.seekTo(time, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        // From the key frame before, on to the first sample at the time asked.
        while (extractor.sampleTrackIndex == index && extractor.sampleTime < time && extractor.advance()) Unit
        val buffer = ByteBuffer.allocate(bufferSize(extractor.getTrackFormat(index)))
        extractor.sampleTrackIndex == index && extractor.readSampleData(buffer, 0) > 0
    } catch (_: Exception) {
        false
    }

    private val MediaFormat.mime: String get() = getString(MediaFormat.KEY_MIME).orEmpty()

    /** Room for the largest sample of a track: what its format says, and at least a megabyte. */
    private fun bufferSize(format: MediaFormat): Int =
        maxOf(if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0, 1 shl 20)

    /**
     * A readable copy of [source] at [destination] (YouRec/RecordingSalvage.swift:182-256):
     * the complete start of a torn fragmented movie when it has one, otherwise
     * every sample that can still be read, track by track, on its own timing,
     * so an intact sound survives a damaged picture. [checkCancelled] throws
     * to stop; nothing is left behind then. [shortOfSpace] says why [bytes]
     * cannot be written where the copy goes, or null when they can.
     */
    fun repair(source: File, destination: File, checkCancelled: () -> Unit, shortOfSpace: (bytes: Long) -> String?): Media {
        if (destination.exists()) return inspect(destination)
        val budget = RecoveryRules.copyBudget(source.length())
        shortOfSpace(budget)?.let { throw OutOfSpace(it) }
        val temp = scratch(destination)
        try {
            try {
                return copyPlayablePrefix(source, temp, destination, checkCancelled, shortOfSpace)
            } catch (stop: OutOfSpace) {
                throw stop
            } catch (_: Unreadable) {
            } catch (_: IOException) {
            }
            temp.delete()
            try {
                remux(source, temp, budget, checkCancelled, shortOfSpace)
            } catch (_: IllegalStateException) {
                // What the muxer refuses, or cannot finish, is a recording that cannot be copied.
                throw Unreadable()
            } catch (_: IllegalArgumentException) {
                throw Unreadable()
            }
            val media = inspect(temp)
            if (!temp.renameTo(destination)) throw IOException("Cannot move ${temp.path}")
            return media
        } finally {
            temp.delete()
            temp.parentFile?.takeIf { it.list().isNullOrEmpty() }?.delete()
        }
    }

    /**
     * The file a copy for [destination] is written to until it is complete,
     * in the hidden `.repair-work` folder beside it, named for the
     * destination (YouRec/RecordingSalvage.swift:74-81).
     */
    private fun scratch(destination: File): File {
        val directory = File(destination.parentFile, ".repair-work")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create ${directory.path}")
        val digest = MessageDigest.getInstance("SHA-256").digest(destination.name.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "$digest.mov").also { it.delete() }
    }

    /** The complete start of a torn fragmented movie, copied and checked (YouRec/RecordingSalvage.swift:118-180). */
    private fun copyPlayablePrefix(source: File, temp: File, destination: File, checkCancelled: () -> Unit, shortOfSpace: (Long) -> String?): Media {
        val count = RecoveryRules.completePrefix(source, checkCancelled) ?: throw Unreadable()
        source.inputStream().use { input ->
            temp.outputStream().use { output ->
                val buffer = ByteArray(1 shl 20)
                var remaining = count
                while (remaining > 0) {
                    checkCancelled()
                    val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (read <= 0) throw Unreadable()
                    output.write(buffer, 0, read)
                    remaining -= read
                    // Every 16 MB, the floor is checked again.
                    if (remaining % 16_777_216 < read) shortOfSpace(0)?.let { throw OutOfSpace(it) }
                }
                output.fd.sync()
            }
        }
        val media = inspect(temp)
        if (!temp.renameTo(destination)) throw IOException("Cannot move ${temp.path}")
        return media
    }

    private class Stream(val extractor: MediaExtractor, val track: Int, val buffer: ByteBuffer, var time: Long)

    /**
     * Every sample of [source]'s pictures and sounds that can still be read,
     * passed through unchanged into a new movie (YouRec/RecordingSalvage.swift:186-256).
     * A track ends at its first sample that cannot be read. The copy starts at
     * the earliest sample, as the original's session does.
     */
    private fun remux(source: File, temp: File, budget: Long, checkCancelled: () -> Unit, shortOfSpace: (Long) -> String?) {
        val probe = MediaExtractor()
        val formats = try {
            probe.setDataSource(source.path)
            (0 until probe.trackCount).map { probe.getTrackFormat(it) }
        } catch (_: Exception) {
            throw Unreadable()
        } finally {
            probe.release()
        }
        val streams = mutableListOf<Stream>()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            val output = MediaMuxer(temp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = output
            for ((index, format) in formats.withIndex()) {
                val mime = format.mime
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                // A reader of its own for each track, as in the original.
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(source.path)
                    extractor.selectTrack(index)
                    val buffer = ByteBuffer.allocate(bufferSize(format))
                    // A track whose first sample cannot be read is left out. A sound's first may be early, before 0, by its encoder's delay.
                    if (extractor.sampleTrackIndex != index || extractor.readSampleData(buffer, 0) <= 0) { extractor.release(); continue }
                    if (mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_ROTATION)) output.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
                    val track = try { output.addTrack(format) } catch (_: Exception) { extractor.release(); continue }
                    streams += Stream(extractor, track, buffer, extractor.sampleTime)
                } catch (_: Exception) {
                    extractor.release()
                }
            }
            if (streams.isEmpty()) throw Unreadable()
            val first = streams.minOf { it.time }
            output.start()
            started = true
            val info = MediaCodec.BufferInfo()
            val live = streams.toMutableList()
            var lastCheck = System.nanoTime()
            var written = 0
            while (live.isNotEmpty()) {
                checkCancelled()
                if (System.nanoTime() - lastCheck >= 1_000_000_000L) {
                    shortOfSpace(0)?.let { throw OutOfSpace(it) }
                    if (temp.length() > budget) throw OutOfSpace(shortOfSpace(budget))
                    lastCheck = System.nanoTime()
                }
                // The earliest sample next, so the tracks stay interleaved.
                val stream = live.minBy { it.time }
                val extractor = stream.extractor
                val size = try { extractor.readSampleData(stream.buffer, 0) } catch (_: Exception) { -1 }
                if (size < 0) { live.remove(stream); continue }
                info.set(0, size, extractor.sampleTime - first, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                output.writeSampleData(stream.track, stream.buffer, info)
                written++
                val more = try { extractor.advance() } catch (_: Exception) { false }
                if (!more || extractor.sampleTrackIndex < 0) live.remove(stream) else stream.time = extractor.sampleTime
            }
            if (written == 0) throw Unreadable()
            output.stop()
            started = false
        } finally {
            streams.forEach { it.extractor.release() }
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }
}
