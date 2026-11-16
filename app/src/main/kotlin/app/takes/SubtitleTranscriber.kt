package app.takes

import android.content.Context
import android.os.SystemClock
import app.takes.core.SourceInterval
import app.takes.core.SubtitleCheckpoint
import app.takes.core.SubtitleScratch
import app.takes.core.SubtitleSpeechAudio
import app.takes.core.SubtitleSpeechPlan
import app.takes.core.SubtitleStretch
import app.takes.core.SubtitleTiming
import app.takes.core.SubtitleWord
import app.takes.core.EditInterval
import app.takes.core.TrackCaptions
import app.takes.core.TrackMix
import app.takes.core.TrackSubtitles
import app.takes.core.TrackTimeline
import app.takes.speech.SpeechModel
import app.takes.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * Turns the speech of an edit into words with times, on the device.
 *
 * As in the original (YouRec/SubtitleTranscriber.swift:85-96), it listens to
 * the edited mix of a range of the timeline as it plays, made by the audio
 * engine: speed, levels, fades, chains and censors in it, voice-overs and
 * music included ([TrackCaptions.heard]). The mix is written once to 16 kHz
 * mono in a temporary file ([SubtitleScratch]), never held whole in memory.
 * An energy gate finds where someone speaks; the recognizer gets those
 * stretches in pieces of at most twelve seconds and returns their text, and
 * the words are placed inside the voiced parts of each piece. Times are in
 * seconds from the range's start; [TrackCaptions.installing] puts each word on
 * the loudest sound playing under it, so the words then follow that sound.
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

    /** The words heard, in seconds from the range's start, the language of the first piece when the model tells it, and what the job took. */
    class Result(val words: List<SubtitleWord>, val language: String, val speechSeconds: Double, val loadingSeconds: Double, val recognizingSeconds: Double)

    const val RATE = SubtitleSpeechAudio.RATE
    private const val SLICE = 320 // 20 ms: one loudness measure
    private val working = Dispatchers.Default.limitedParallelism(1)

    /** Wall seconds per second of speech on this device, from the last job, for the first estimate of the next. */
    private var pace: Double? = null

    /** The mix as 16 kHz mono floats on disk, with its loudness every 20 ms. */
    private class Sound(val file: File, val samples: Long, val levels: FloatArray)

    private class Piece(val range: SourceInterval, val speech: List<SourceInterval>)

    /** The name the words of the mix have in a checkpoint. */
    private const val MIX = "mix"


    /**
     * [language] is a two-letter code, or null to detect it: then every piece is
     * recognized without a hint, as the original does, and the model decides
     * piece by piece. Cancelling the coroutine stops between two pieces.
     *
     * After every piece what is done is handed to [save]; a job given that
     * [resume] skips the pieces it had finished, when the footage, the
     * language and the pieces are still the same ([SubtitleCheckpoint.reusable]).
     */
    suspend fun transcribe(
        context: Context, model: SpeechModel, timeline: TrackTimeline, range: EditInterval, files: Map<String, File>, language: String?,
        resume: SubtitleCheckpoint? = null, save: (SubtitleCheckpoint) -> Unit = {}, progress: (Float, Step) -> Unit,
    ): Result = withContext(working) {
        val job = coroutineContext
        // What is listened to, named by what the captions are timed for, so a checkpoint is taken up only for the same mix.
        val stretches = listOf(SubtitleStretch(TrackSubtitles.fingerprint(timeline), range.start.seconds, range.end.seconds))
        if (TrackCaptions.heard(timeline, range).isEmpty()) throw Failure(Failure.Reason.NoSound)
        // What a job that died with the app left behind goes first; a job that is running keeps its own.
        SubtitleScratch.clear(context.cacheDir)
        val scratch = SubtitleScratch.open(context.cacheDir)
        try {
            progress(0f, Step.Reading)
            val sound = read(timeline, range, files, File(scratch, "mix.pcm"), { job.ensureActive() }) { progress(READING * it, Step.Reading) }
                ?: throw Failure(Failure.Reason.NoSound)
            val pieces = SubtitleSpeechPlan.chunks(sound.levels, sound.samples.toDouble() / RATE).map { Piece(it.range, it.speech) }
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
            val plan = pieces.map { SubtitleStretch(MIX, it.range.start, it.range.end) }
            val skipped = SubtitleCheckpoint.reusable(resume, stretches, language, plan)
            val kept = resume?.takeIf { skipped > 0 }
            val words = mutableMapOf<String, MutableList<SubtitleWord>>()
            kept?.words?.forEach { (source, list) -> words[source] = list.toMutableList() }
            var written = kept?.written ?: ""
            val before = pieces.take(skipped).sumOf { it.range.duration } // what the checkpoint had done
            val total = pieces.drop(skipped).sumOf { it.range.duration }
            var done = 0.0
            val started = SystemClock.elapsedRealtime()
            fun elapsed() = (SystemClock.elapsedRealtime() - started) / 1000.0
            try {
                pieces.forEachIndexed { index, piece ->
                    if (index < skipped) return@forEachIndexed
                    job.ensureActive()
                    val rate = if (done > 0) elapsed() / done else pace
                    progress(READING + (1 - READING) * ((before + done) / (before + total)).toFloat(), Step.Section(index + 1, pieces.size, rate?.let { it * (total - done) }))
                    val transcript = recognizer.transcribe(samples(sound, piece.range), RATE, language)
                    if (written.isEmpty()) written = transcript.language
                    val timed = transcript.words
                    words.getOrPut(MIX) { mutableListOf() } += if (timed != null) {
                        timed.map { SubtitleWord(text = it.text, start = piece.range.start + it.start, end = piece.range.start + it.end) }
                    } else {
                        SubtitleTiming.estimate(SubtitleSpeechAudio.spoken(transcript.text), piece.speech)
                    }
                    done += piece.range.duration
                    pace = elapsed() / done
                    save(SubtitleCheckpoint(stretches, language, plan, index + 1, words.mapValues { it.value.toList() }, written))
                }
            } catch (problem: OutOfMemoryError) {
                throw Failure(Failure.Reason.Memory)
            } finally {
                recognizer.close() // its memory is native: the garbage collector does not see it
                SubtitleModel.running(context, null)
            }
            job.ensureActive()
            val heard = words[MIX].orEmpty()
            if (heard.isEmpty()) throw Failure(Failure.Reason.NoWords)
            progress(1f, Step.Section(pieces.size, pieces.size, 0.0))
            Result(heard, written, total, loaded, elapsed())
        } finally {
            SubtitleScratch.close(scratch)
        }
    }

    /**
     * Writes the mix of [range] of [timeline] into [target] at 16 kHz mono, as the audio engine makes it at
     * 48 kHz in one channel, each sample the average of the three it covers. Null when no recording could be read.
     */
    private fun read(timeline: TrackTimeline, range: EditInterval, files: Map<String, File>, target: File, checkpoint: () -> Unit, progress: (Float) -> Unit): Sound? {
        val voiced = HashMap<String, Boolean>()
        val plan = TrackMix.plan(timeline, voiced = { source -> voiced.getOrPut(source) { files[source]?.let(::hasSound) == true } })
        val failures = ArrayList<String>()
        val step = MIX_RATE / RATE
        BufferedOutputStream(FileOutputStream(target), 1 shl 16).use { output ->
            audioEngine(plan, files, Denoiser.input(files), MIX_RATE, 1, failures = failures).use { engine ->
                val frames = engine.frames(range.start.seconds, range.end.seconds)
                if (frames.isEmpty()) return null
                engine.seek(frames.first)
                val block = 4800 // 100 ms, a whole number of 16 kHz samples
                val mixed = FloatArray(block)
                val bytes = ByteBuffer.allocate(block / step * 4).order(ByteOrder.LITTLE_ENDIAN)
                var levels = FloatArray(4096)
                var measured = 0
                var energy = 0.0
                var counted = 0
                var written = 0L
                var frame = frames.first
                while (frame <= frames.last) {
                    checkpoint()
                    val count = minOf(block.toLong(), frames.last - frame + 1).toInt() / step * step
                    if (count == 0) break
                    engine.render(mixed, count)
                    bytes.clear()
                    for (index in 0 until count / step) {
                        var sum = 0f
                        for (part in 0 until step) sum += mixed[index * step + part]
                        val sample = (sum / step).takeIf { it.isFinite() } ?: 0f
                        bytes.putFloat(sample)
                        energy += sample * sample
                        if (++counted == SLICE) {
                            if (measured == levels.size) levels = levels.copyOf(measured * 2)
                            levels[measured++] = sqrt(energy / SLICE).toFloat()
                            energy = 0.0
                            counted = 0
                        }
                    }
                    output.write(bytes.array(), 0, count / step * 4)
                    written += count / step
                    frame += count
                    progress(((frame - frames.first).toFloat() / (frames.last - frames.first + 1)).coerceIn(0f, 1f))
                }
                // Every recording that plays here unreadable: nothing was heard.
                if (failures.isNotEmpty() && failures.toSet() == plan.pieces.map { it.sourceID }.toSet()) return null
                if (counted > 0) {
                    if (measured == levels.size) levels = levels.copyOf(measured + 1)
                    levels[measured++] = sqrt(energy / counted).toFloat()
                }
                return Sound(target, written, levels.copyOf(measured))
            }
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
