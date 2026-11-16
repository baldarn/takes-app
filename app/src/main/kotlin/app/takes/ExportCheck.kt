package app.takes

import app.takes.core.ExportProblem
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import app.takes.core.ExportSections
import app.takes.core.RenderPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/** What an export checks about its own movie: that the finished file is the movie that was asked for. How loud its sound gets the audio engine measures as it mixes ([writeMix]). */
object ExportCheck {
    /** How long one packet of [movie]'s encoded sound lasts, in seconds: 1024 samples at its rate. Zero when it cannot be read. */
    private fun soundPacket(movie: File): Double {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(movie.path)
            (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?.takeIf { it.containsKey(MediaFormat.KEY_SAMPLE_RATE) }?.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                ?.takeIf { it > 0 }?.let { 1024.0 / it } ?: 0.0
        } catch (_: Exception) {
            0.0
        } finally {
            extractor.release()
        }
    }

    /**
     * Whether the picture of [movie] at [seconds] decodes: its decoder, from the key frame before it, gives a
     * picture at that time or after it.
     */
    private fun decodes(movie: File, seconds: Double): Boolean {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        return try {
            extractor.setDataSource(movie.path)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val target = Math.round(seconds * 1e6)
            extractor.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also { decoder = it }
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var fed = false
            val until = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < until) {
                if (!fed) {
                    val input = codec.dequeueInputBuffer(10_000)
                    if (input >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(input)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            fed = true
                        } else {
                            codec.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val output = codec.dequeueOutputBuffer(info, 10_000)
                if (output >= 0) {
                    val shown = info.size > 0 && info.presentationTimeUs >= target - 1000
                    codec.releaseOutputBuffer(output, false)
                    if (shown) return true
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return false
                }
            }
            false
        } catch (_: Exception) {
            false
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            extractor.release()
        }
    }

    /**
     * Proves that [movie] is the movie of [expected] seconds at [framesPerSecond] that was asked for, as the
     * original does before it publishes one: it has picture and sound, its length is the edit's within the
     * tolerance ([ExportSections.durationMatches]), and its first and its last picture decode
     * ([ExportSections.lastPictureTime]). Throws when it is not; [halfway] is told when the first picture is read.
     */
    fun verify(movie: File, expected: Double, framesPerSecond: Int, halfway: () -> Unit) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(movie.path)
            val actual = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { it / 1000.0 }
            if (actual == null || !ExportSections.durationMatches(actual, expected, framesPerSecond, soundPacket(movie))) throw ExportProblem(ExportProblem.Reason.WrongLength)
            if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes" || retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != "yes") {
                throw ExportProblem(ExportProblem.Reason.Incomplete)
            }
            fun picture(seconds: Double): Bitmap? {
                val micros = Math.round(seconds * 1e6)
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) retriever.getScaledFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST, 96, 96)
                else retriever.getFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST)
            }
            // The system's frame reader gives up on ten-bit HDR pictures it has to decode many frames to reach (seen
            // on a Galaxy A54): such a picture is then decoded here.
            val first = picture(0.0)
            if (first != null) first.recycle() else if (!decodes(movie, 0.0)) throw ExportProblem(ExportProblem.Reason.FirstPicture)
            halfway()
            ExportSections.lastPictureTime(actual, expected, framesPerSecond)?.let { last ->
                val shown = picture(last)
                if (shown != null) shown.recycle() else if (!decodes(movie, last)) throw ExportProblem(ExportProblem.Reason.LastPicture)
            }
        } catch (problem: IOException) {
            throw problem
        } catch (problem: Exception) {
            throw ExportProblem(ExportProblem.Reason.Unreadable, problem)
        } finally {
            runCatching { retriever.release() }
        }
    }
}
