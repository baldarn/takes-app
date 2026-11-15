package app.takes

import android.content.Context
import android.os.SystemClock
import app.takes.core.SourceInterval
import app.takes.core.SubtitleEditing
import app.takes.core.SubtitleSpeechAudio
import app.takes.core.SubtitleSpeechPlan
import app.takes.core.SubtitleTiming
import app.takes.core.SubtitleWord
import app.takes.core.TimelineClip
import app.takes.speech.SpeechModel
import app.takes.speech.SpeechRecognizer
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
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * Turns the speech of an edit into words with times, on the device.
 *
 * Each recording the edit plays is decoded once to 16 kHz mono in a temporary
 * file, never held whole in memory. An energy gate finds where someone speaks;
 * the recognizer gets those stretches in pieces of at most twelve seconds and
 * returns their text, and the words are placed inside the voiced parts of each
 * piece. Times are in the recording's own seconds, so the words stay with the
 * footage however it is cut afterwards.
 *
 * The original listens to the finished mix of its timeline. This port listens
 * to each clip's recording as it was made: at its natural speed, without the
 * clip's level, fades or noise reduction, and without the audio tracks.
 */
object SubtitleTranscriber {
    /** What the job is doing, for the progress line. */
    sealed interface Step {
        data object Reading : Step
        data object Loading : Step

        /** Recognizing piece [index] of [count]; [remaining] seconds are left, once that can be estimated. */
        data class Section(val index: Int, val count: Int, val remaining: Double?) : Step
    }

    /** Why there are no subtitles to show for. The captions the project had are untouched. */
    class Failure(val reason: Reason) : Exception(reason.name) {
        /** [Model]: its files could not be loaded. [Memory]: the phone has too little memory free for it. */
        enum class Reason { NoSound, NoSpeech, NoWords, Model, Memory }
    }

    /** The words by recording id, the language of the first piece when the model tells it, and what the job took. */
    class Result(val words: Map<String, List<SubtitleWord>>, val language: String, val speechSeconds: Double, val loadingSeconds: Double, val recognizingSeconds: Double)

    const val RATE = SubtitleSpeechAudio.RATE
    private const val SLICE = 320 // 20 ms: one loudness measure
    private val working = Dispatchers.Default.limitedParallelism(1)

    /** Wall seconds per second of speech on this device, from the last job, for the first estimate of the next. */
    private var pace: Double? = null

    /** One recording as 16 kHz mono floats on disk, with its loudness every 20 ms. */
    private class Sound(val file: File, val samples: Long, val levels: FloatArray)

    private class Piece(val source: String, val range: SourceInterval, val speech: List<SourceInterval>)

    /**
     * [language] is a two-letter code, or null to detect it: then every piece is
     * recognized without a hint, as the original does, and the model decides
     * piece by piece. Cancelling the coroutine stops between two pieces.
     */
    suspend fun transcribe(
        context: Context, model: SpeechModel, clips: List<TimelineClip>, files: Map<String, File>, language: String?, progress: (Float, Step) -> Unit,
    ): Result = withContext(working) {
        val job = coroutineContext
        val stretches = SubtitleEditing.stretches(clips)
        val scratch = File(context.cacheDir, "subtitles-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            progress(0f, Step.Reading)
            val sources = stretches.map { it.sourceID }.distinct()
            val sounds = mutableMapOf<String, Sound>()
            sources.forEachIndexed { index, id ->
                val file = files[id] ?: return@forEachIndexed
                read(file, File(scratch, "$index.pcm"), { job.ensureActive() }) { progress(READING * (index + it) / sources.size, Step.Reading) }?.let { sounds[id] = it }
            }
            if (sounds.isEmpty()) throw Failure(Failure.Reason.NoSound)

            val pieces = stretches.flatMap { stretch ->
                val sound = sounds[stretch.sourceID] ?: return@flatMap emptyList()
                // Whole slices, so a piece's times are exact multiples of the measure.
                val first = (stretch.start * RATE / SLICE).toInt().coerceIn(0, sound.levels.size)
                val last = Math.ceil(stretch.end * RATE / SLICE).toInt().coerceIn(first, sound.levels.size)
                val begins = first.toDouble() * SLICE / RATE
                val length = minOf((last - first).toDouble() * SLICE / RATE, sound.samples.toDouble() / RATE - begins)
                SubtitleSpeechPlan.chunks(sound.levels.copyOfRange(first, last), length).map { chunk ->
                    fun moved(part: SourceInterval) = SourceInterval(part.start + begins, part.end + begins)
                    Piece(stretch.sourceID, moved(chunk.range), chunk.speech.map(::moved))
                }
            }
            if (pieces.isEmpty()) throw Failure(Failure.Reason.NoSpeech)

            progress(READING, Step.Loading)
            job.ensureActive()
            // A model larger than the memory at hand does not fail politely: Android ends the
            // app. So it is not tried, and the note left here tells the next start if it happened anyway.
            if (!SubtitleModel.hasMemory(context, model)) throw Failure(Failure.Reason.Memory)
            SubtitleModel.running(context, model)
            val loading = SystemClock.elapsedRealtime()
            val recognizer = try {
                SpeechRecognizer(SubtitleModel.folder(context, model), model, language)
            } catch (problem: Exception) {
                SubtitleModel.running(context, null)
                throw Failure(Failure.Reason.Model)
            } catch (problem: LinkageError) {
                SubtitleModel.running(context, null)
                throw Failure(Failure.Reason.Model) // no native library for this processor
            } catch (problem: OutOfMemoryError) {
                SubtitleModel.running(context, null)
                throw Failure(Failure.Reason.Memory)
            }
            val loaded = (SystemClock.elapsedRealtime() - loading) / 1000.0
            val words = mutableMapOf<String, MutableList<SubtitleWord>>()
            var written = ""
            val total = pieces.sumOf { it.range.duration }
            var done = 0.0
            val started = SystemClock.elapsedRealtime()
            fun elapsed() = (SystemClock.elapsedRealtime() - started) / 1000.0
            try {
                pieces.forEachIndexed { index, piece ->
                    job.ensureActive()
                    val rate = if (done > 0) elapsed() / done else pace
                    progress(READING + (1 - READING) * (done / total).toFloat(), Step.Section(index + 1, pieces.size, rate?.let { it * (total - done) }))
                    val sound = sounds.getValue(piece.source)
                    val transcript = recognizer.transcribe(samples(sound, piece.range), RATE, language)
                    if (written.isEmpty()) written = transcript.language
                    val timed = transcript.words
                    words.getOrPut(piece.source) { mutableListOf() } += if (timed != null) {
                        timed.map { SubtitleWord(text = it.text, start = piece.range.start + it.start, end = piece.range.start + it.end) }
                    } else {
                        SubtitleTiming.estimate(SubtitleSpeechAudio.spoken(transcript.text), piece.speech)
                    }
                    done += piece.range.duration
                    pace = elapsed() / done
                }
            } catch (problem: OutOfMemoryError) {
                throw Failure(Failure.Reason.Memory)
            } finally {
                recognizer.close() // its memory is native: the garbage collector does not see it
                SubtitleModel.running(context, null)
            }
            job.ensureActive()
            if (words.values.all { it.isEmpty() }) throw Failure(Failure.Reason.NoWords)
            progress(1f, Step.Section(pieces.size, pieces.size, 0.0))
            Result(words, written, total, loaded, elapsed())
        } finally {
            scratch.deleteRecursively()
        }
    }

    /** Decodes [file] into [target]. Null when it has no sound that can be read. */
    private fun read(file: File, target: File, checkpoint: () -> Unit, progress: (Float) -> Unit): Sound? {
        val output = BufferedOutputStream(FileOutputStream(target), 1 shl 16)
        try {
            var mono: SubtitleSpeechAudio? = null
            var length = 0L
            var written = 0L
            var levels = FloatArray(4096)
            var measured = 0
            var energy = 0.0
            var counted = 0
            var bytes = ByteBuffer.allocate(0)

            fun write(samples: FloatArray, count: Int) {
                if (bytes.capacity() < count * 4) bytes = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
                bytes.clear()
                for (index in 0 until count) {
                    val sample = samples[index].takeIf { it.isFinite() } ?: 0f
                    bytes.putFloat(sample)
                    energy += sample * sample
                    if (++counted == SLICE) {
                        if (measured == levels.size) levels = levels.copyOf(measured * 2)
                        levels[measured++] = sqrt(energy / SLICE).toFloat()
                        energy = 0.0
                        counted = 0
                    }
                }
                output.write(bytes.array(), 0, count * 4)
                written += count
            }

            val decoded = try {
                decodeAudio(
                    file, checkpoint,
                    begin = { format ->
                        length = format.durationUs
                        mono = SubtitleSpeechAudio(format.channels, format.sampleRate)
                    },
                    samples = { block, time ->
                        // Sound that begins after the picture keeps its place: silence comes first.
                        if (written == 0L && time > 0) {
                            val silence = FloatArray(RATE)
                            var missing = minOf(time, 3_600_000_000L) * RATE / 1_000_000
                            while (missing > 0) { val count = minOf(missing, silence.size.toLong()).toInt(); write(silence, count); missing -= count }
                        }
                        val channel = mono!!
                        val count = channel.read(block) // first: reading may replace the array
                        write(channel.samples, count)
                        if (length > 0) progress((time.toFloat() / length).coerceIn(0f, 1f))
                    },
                )
            } catch (stopped: CancellationException) {
                throw stopped // it is an IllegalStateException too, and must not pass for a broken file
            } catch (problem: IllegalStateException) {
                null // a decoder that gave up in the middle
            } catch (problem: java.io.IOException) {
                null
            }
            output.close()
            if (decoded != true || written == 0L) return null
            if (counted > 0) {
                if (measured == levels.size) levels = levels.copyOf(measured + 1)
                levels[measured++] = sqrt(energy / counted).toFloat()
            }
            return Sound(target, written, levels.copyOf(measured))
        } finally {
            runCatching { output.close() }
        }
    }

    private fun samples(sound: Sound, range: SourceInterval): FloatArray {
        val first = Math.round(range.start * RATE).coerceIn(0L, sound.samples)
        val count = Math.round(range.duration * RATE).coerceIn(0L, sound.samples - first).toInt()
        val bytes = ByteArray(count * 4)
        RandomAccessFile(sound.file, "r").use { it.seek(first * 4); it.readFully(bytes) }
        val floats = FloatArray(count)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
        return floats
    }

    private const val READING = 0.1f
}
