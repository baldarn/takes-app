package app.takes

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import app.takes.core.EditState
import app.takes.core.ExportSections
import app.takes.core.SoundEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/** What an export checks about its own movie: how loud its sound gets, and that the finished file is the movie that was asked for. */
object ExportCheck {
    private const val RATE = 48_000.0

    /**
     * The loudest sample of the movie's mix, 1 being full scale, measured before anything is encoded: the
     * encoded sound cannot say, it is cut off at full scale. Null when a recording cannot be decoded, and the
     * movie then makes no claim about its sound.
     *
     * The recordings are decoded and mixed for the measurement alone, through the envelopes the export applies
     * ([SoundEnvelope]), as the Volume tool measures its headroom ([Headroom]): a clip at another speed is read
     * at its rate without its pitch being kept, which is close to the export for levels and not a copy of its
     * samples. [files] gives each source's file and [clean] the noise-reduced sound of a clip that has it.
     */
    suspend fun peak(state: EditState, files: Map<String, File>, clean: (File, Double) -> File? = Denoiser::ready, progress: (Double) -> Unit = {}): Float? = withContext(Dispatchers.IO) {
        val segments = SoundEnvelope.segments(state)
        val clips = state.clips.associateBy { it.id }
        val items = state.audio.orEmpty().associateBy { it.id }
        class Voice(val index: Int, val file: File, val source: Double, val rate: Double)
        val voices = segments.mapIndexedNotNull { index, segment ->
            val clip = clips[segment.owner]
            val item = items[segment.owner]
            when {
                clip != null -> files[clip.sourceID]?.let { file -> Voice(index, clip.audio?.denoise?.takeIf { it > 0 }?.let { clean(file, it) } ?: file, clip.start, clip.rate) }
                item != null -> files[item.sourceID]?.let { Voice(index, it, item.sourceStart, 1.0) }
                else -> null
            }
        }
        val total = Math.ceil(state.duration * RATE).toLong()
        val readers = arrayOfNulls<PcmReader>(voices.size)
        val block = 4096
        val mix = FloatArray(block * 2)
        var peak = 0f
        try {
            var frame = 0L
            while (frame < total) {
                coroutineContext.ensureActive()
                val count = minOf(block.toLong(), total - frame).toInt()
                mix.fill(0f, 0, count * 2)
                voices.forEachIndexed { number, voice ->
                    val segment = segments[voice.index]
                    if (segment.end <= frame / RATE || segment.start >= (frame + count) / RATE) return@forEachIndexed
                    for (offset in 0 until count) {
                        val time = (frame + offset) / RATE
                        if (time < segment.start || time >= segment.end) continue
                        val reader = readers[number] ?: PcmReader.open(voice.file, voice.source + (time - segment.start) * voice.rate).also { readers[number] = it }
                            ?: return@withContext null
                        if (!reader.seek(voice.source + (time - segment.start) * voice.rate)) continue // past the end of the recording: silence
                        val gain = segment.gain(time).toFloat()
                        mix[offset * 2] += reader.sample(0) * gain
                        mix[offset * 2 + 1] += reader.sample(1) * gain
                    }
                    if (segment.end <= (frame + count) / RATE) { readers[number]?.close(); readers[number] = PcmReader.ENDED }
                }
                for (index in 0 until count * 2) peak = maxOf(peak, abs(mix[index]))
                frame += count
                progress(frame.toDouble() / total)
            }
        } catch (_: IOException) {
            return@withContext null
        } catch (_: IllegalStateException) {
            return@withContext null // a decoder gave up
        } finally {
            readers.forEach { it?.close() }
        }
        peak
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
            if (actual == null || !ExportSections.durationMatches(actual, expected, framesPerSecond)) throw IOException("The exported movie has the wrong length.")
            if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes" || retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != "yes") {
                throw IOException("The exported movie is not complete.")
            }
            fun picture(seconds: Double): Bitmap? {
                val micros = Math.round(seconds * 1e6)
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) retriever.getScaledFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST, 96, 96)
                else retriever.getFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST)
            }
            (picture(0.0) ?: throw IOException("The first picture of the exported movie cannot be read.")).recycle()
            halfway()
            ExportSections.lastPictureTime(actual, expected, framesPerSecond)?.let { last ->
                (picture(last) ?: throw IOException("The last picture of the exported movie cannot be read.")).recycle()
            }
        } catch (problem: IOException) {
            throw problem
        } catch (problem: Exception) {
            throw IOException("The exported movie cannot be read.", problem)
        } finally {
            runCatching { retriever.release() }
        }
    }
}
