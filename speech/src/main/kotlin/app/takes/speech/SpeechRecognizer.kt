package app.takes.speech

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File

/** One recognized word with the punctuation written after it. Seconds from the first sample given. */
data class Word(val text: String, val start: Double, val end: Double)

/**
 * What was said.
 *
 * [words] is null when the model gives no word times, which is the case for every model in
 * [SpeechModel]'s list: then only [text] is known, and where each word falls has to be
 * estimated by the caller. When it is not null the times come from the model's attention
 * (which sound each piece of text was read from); they are measured, not spread evenly.
 * Nothing recognized gives an empty [text] and an empty list.
 *
 * [language] is the code of the language Whisper wrote in ("it", "en"), the forced one or
 * the one detected in the first window; empty when nothing was recognized. Qwen3-ASR does not
 * say what it detected: with it this is the forced language, or empty.
 */
data class Transcript(val text: String, val words: List<Word>?, val language: String)

/**
 * Speech to text on the device, through sherpa-onnx, with the recognizer [model] asks for.
 *
 * Lifetime and memory: creating one reads the whole model into memory, and recognizing needs
 * more than loading; the figures for each model, on a computer and on a phone, are in the
 * README. Create it when a transcription starts, use it for all the audio of that job and
 * [close] it afterwards; it holds native memory that the garbage collector does not see.
 *
 * Threads: every call blocks until done, so never call from the main thread. One call runs
 * at a time (the others wait); inside, the recognizer uses [threads] threads. A call cannot
 * be interrupted: to make cancellation responsive, hand it short pieces (the app's 12 s
 * plan) and check between them.
 *
 * @param modelFolder holds the files of [model]; check [SpeechModel.installed] first.
 * @param language a two-letter code ("it", "en", "fr", "de", "es", "pt", "zh", "ja", "ko",
 *   "ru", "ar" and the others the model knows), or null to let it detect the language.
 * @throws IllegalArgumentException when a file is missing or is not a model. That is all the
 *   protection there is: a damaged token table gives empty transcripts or makes sherpa-onnx
 *   end the whole process. Only load a folder for which [SpeechModel.installed] is true.
 */
class SpeechRecognizer(
    private val modelFolder: File,
    val model: SpeechModel,
    val language: String? = null,
    val threads: Int = minOf(4, Runtime.getRuntime().availableProcessors()),
) : AutoCloseable {
    private var config: OfflineRecognizerConfig
    private var recognizer: OfflineRecognizer?

    init {
        for (file in model.files) require(File(modelFolder, file.name).isFile) { "Missing ${file.name} in $modelFolder" }
        hint(language) // refuses a language the model does not know before anything is loaded
        fun path(part: String) = File(modelFolder, model.file(part).name).path
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = when (model.kind) {
                SpeechModel.Kind.Whisper -> OfflineModelConfig(
                    whisper = whisper(language),
                    tokens = path(SpeechModel.TOKENS),
                    modelType = "whisper",
                    numThreads = threads,
                )
                SpeechModel.Kind.Qwen3Asr -> OfflineModelConfig(
                    qwen3Asr = OfflineQwen3AsrModelConfig(
                        convFrontend = path(SpeechModel.FRONTEND),
                        encoder = path(SpeechModel.ENCODER),
                        decoder = path(SpeechModel.DECODER),
                        tokenizer = File(modelFolder, SpeechModel.TOKENIZER).path,
                        maxNewTokens = QWEN_NEW_TOKENS,
                    ),
                    tokens = "",
                    numThreads = threads,
                )
            },
        )
        // No AssetManager: the model is read from files, on a phone and on a computer alike.
        recognizer = try {
            OfflineRecognizer(null, config)
        } catch (failure: Exception) {
            throw IllegalArgumentException("Cannot load the model in $modelFolder: ${failure.message}", failure)
        }
    }

    /** What the model is told for [language]: Whisper its code, Qwen3-ASR the language's English name. */
    private fun hint(language: String?): String {
        require(language == null || model.multilingual || language == "en") { "This model only knows English" }
        return when (model.kind) {
            // Empty makes sherpa-onnx detect the language; an English-only model has no such choice.
            SpeechModel.Kind.Whisper -> language ?: if (model.multilingual) "" else "en"
            SpeechModel.Kind.Qwen3Asr -> if (language == null) "" else requireNotNull(QWEN_LANGUAGES[language]) { "Qwen3-ASR does not know the language $language" }
        }
    }

    private fun whisper(language: String?) = OfflineWhisperModelConfig(
        encoder = File(modelFolder, model.file(SpeechModel.ENCODER).name).path,
        decoder = File(modelFolder, model.file(SpeechModel.DECODER).name).path,
        language = hint(language),
        task = "transcribe",
        tailPaddings = TAIL_PADDING_FRAMES,
        // Ignored, with one line in the log, by a model exported without attention outputs.
        enableTokenTimestamps = true,
    )

    /**
     * Transcribes [samples]: one channel, floats in −1…1, at [sampleRate] (16 kHz is what
     * the models hear; anything else is resampled inside sherpa-onnx).
     *
     * Whisper reads at most 30 seconds at once and sherpa-onnx silently drops what is beyond;
     * Qwen3-ASR has a limit of its own on sound and text together. Longer audio is therefore
     * cut into windows of at most [MAX_WINDOW_SECONDS], each at the quietest moment of its
     * last third, and the results are joined; word times stay relative to the first sample.
     * A cut can still fall inside a word when nobody pauses, and each window is recognized
     * without the context of the one before. Callers that know where the pauses are (the
     * app's speech plan) do better by cutting themselves.
     *
     * [language] replaces the constructor's choice for this call: null detects again, in
     * every window separately.
     * A piece without any signal gives an empty text. Background noise without speech
     * usually does too, but Whisper can invent a few words for it.
     */
    @Synchronized
    fun transcribe(samples: FloatArray, sampleRate: Int = SAMPLE_RATE, language: String? = this.language): Transcript {
        val recognizer = checkNotNull(recognizer) { "Closed" }
        require(sampleRate > 0) { "Sample rate must be positive" }

        val hint = hint(language)
        // Whisper's language belongs to the recognizer; Qwen3-ASR takes it with each piece.
        if (model.kind == SpeechModel.Kind.Whisper) {
            val wanted = whisper(language)
            if (wanted != config.modelConfig.whisper) {
                config = config.copy(modelConfig = config.modelConfig.copy(whisper = wanted))
                recognizer.setConfig(config)
            }
        }

        val texts = mutableListOf<String>()
        val words = mutableListOf<Word>()
        var spoken = ""
        var untimed = false
        if (samples.isNotEmpty()) for ((from, to) in windows(samples, sampleRate)) {
            // Whisper invents words for pure silence ("1.0" in a test), so a piece with no
            // signal at all is not shown to it. Room noise is not silence: what is speech
            // is the caller's decision.
            if (level(samples, from, to) < SILENCE) continue
            val stream = recognizer.createStream()
            val result = try {
                // The same words the iPhone app puts after the sound: "language Italian".
                if (model.kind == SpeechModel.Kind.Qwen3Asr && hint.isNotEmpty()) stream.setOption("language", hint)
                stream.acceptWaveform(if (from == 0 && to == samples.size) samples else samples.copyOfRange(from, to), sampleRate)
                recognizer.decode(stream)
                recognizer.getResult(stream)
            } finally {
                stream.release()
            }
            val text = result.text.trim()
            if (text.isEmpty()) continue
            if (spoken.isEmpty()) spoken = result.lang.removePrefix("<|").removeSuffix("|>").ifEmpty { language.orEmpty() }
            texts += text
            val timed = words(result, from.toDouble() / sampleRate, to.toDouble() / sampleRate)
            if (timed == null) untimed = true else words += tightened(timed, samples, sampleRate)
        }
        return Transcript(texts.joinToString(" "), if (untimed) null else words, spoken)
    }

    /** Frees the model. The recognizer cannot be used afterwards. Safe to call twice. */
    @Synchronized
    override fun close() {
        recognizer?.release()
        recognizer = null
    }

    companion object {
        /** The rate the models work at. Audio at this rate is not resampled. */
        const val SAMPLE_RATE = 16_000

        /**
         * The longest piece handed to a model at once. Whisper's own limit is 30 s, less the
         * padding it needs to notice the end. Qwen3-ASR as exported holds 512 tokens of sound
         * and text together, 13 for each second of sound: 24 s leave room for 170 of text.
         */
        const val MAX_WINDOW_SECONDS = 24.0

        // The iPhone app's limit on the text of one piece (`maxTokens: 256`); sherpa-onnx's own is 128.
        private const val QWEN_NEW_TOKENS = 256

        /** The languages Qwen3-ASR was trained on, by code, as it expects them named. */
        internal val QWEN_LANGUAGES = mapOf(
            "zh" to "Chinese", "en" to "English", "yue" to "Cantonese", "ar" to "Arabic", "de" to "German", "fr" to "French",
            "es" to "Spanish", "pt" to "Portuguese", "id" to "Indonesian", "it" to "Italian", "ko" to "Korean", "ru" to "Russian",
            "th" to "Thai", "vi" to "Vietnamese", "ja" to "Japanese", "tr" to "Turkish", "hi" to "Hindi", "ms" to "Malay",
            "nl" to "Dutch", "sv" to "Swedish", "da" to "Danish", "fi" to "Finnish", "pl" to "Polish", "cs" to "Czech",
            "fil" to "Filipino", "fa" to "Persian", "el" to "Greek", "hu" to "Hungarian", "mk" to "Macedonian", "ro" to "Romanian",
        )

        // Whisper was trained on 30 s windows filled up with silence, and needs some of that
        // silence after the last word to notice that the speech ended. sherpa-onnx's default
        // is 10 s, which is all computed; its documentation suggests 3 s for multilingual models.
        private const val TAIL_PADDING_FRAMES = 300

        // Mean square of −70 dB of full scale: below any microphone's own noise.
        private const val SILENCE = 1e-7

        private fun level(samples: FloatArray, from: Int, to: Int): Double {
            var sum = 0.0
            for (i in from until to) sum += samples[i].toDouble() * samples[i]
            return sum / maxOf(1, to - from)
        }

        /**
         * Sample ranges of at most [MAX_WINDOW_SECONDS]. A long recording is cut where the
         * 100 ms around the cut are quietest, looking only at the last third of each window.
         */
        internal fun windows(samples: FloatArray, sampleRate: Int): List<Pair<Int, Int>> {
            val limit = (MAX_WINDOW_SECONDS * sampleRate).toInt()
            val result = mutableListOf<Pair<Int, Int>>()
            val slice = maxOf(1, sampleRate / 100)
            var from = 0
            while (samples.size - from > limit) {
                // Energy of 10 ms slices in the last third; the cut goes in the middle of the quietest five.
                val first = from + limit * 2 / 3
                val count = (from + limit - first) / slice
                val energy = DoubleArray(count) { i ->
                    var sum = 0.0
                    val begin = first + i * slice
                    for (k in begin until begin + slice) sum += samples[k].toDouble() * samples[k]
                    sum
                }
                var best = 0
                var least = Double.POSITIVE_INFINITY
                var running = 0.0
                for (i in 0 until count) {
                    running += energy[i]
                    if (i >= 10) running -= energy[i - 10]
                    if (i >= 9 && running < least) { least = running; best = i - 4 }
                }
                val cut = if (count < 10) from + limit else first + best * slice
                result += from to cut
                from = cut
            }
            result += from to samples.size
            return result
        }

        /**
         * Words from Whisper's tokens. A token that begins with a space begins a word; the
         * others (the rest of a long word, punctuation) continue it. In writing without
         * spaces (Chinese, Japanese, Thai) every token becomes a word.
         *
         * Returns null when the model gave no time for each token. [offset] and [limit] are
         * where the recognized piece begins and ends, in seconds.
         */
        internal fun words(result: OfflineRecognizerResult, offset: Double, limit: Double): List<Word>? {
            val tokens = result.tokens
            val starts = result.timestamps
            if (tokens.isEmpty() || starts.size != tokens.size) return null

            // The start of a token is where the model began to read it from the sound. The
            // durations sherpa-onnx adds are only the distance to the next start.
            class Group(var text: String, val start: Double)
            val groups = mutableListOf<Group>()
            for (i in tokens.indices) {
                val token = tokens[i]
                if (token.isEmpty()) continue
                val last = groups.lastOrNull()
                // Punctuation stays with the word before it, also where words are not spaced.
                val continues = last != null && (token.isBlank() || !token[0].isWhitespace() &&
                    (token.none(Char::isLetterOrDigit) || !(unspaced(token.codePointAt(0)) || unspaced(last.text.codePointBefore(last.text.length)))))
                if (continues) last!!.text += token else groups += Group(token, offset + starts[i])
            }
            groups.removeAll { it.text.isBlank() }

            // Times in order and inside the audio, whatever the alignment produced. A word
            // lasts until the next one starts; [tightened] then takes the silence out.
            var floor = offset
            val words = ArrayList<Word>(groups.size)
            for ((i, group) in groups.withIndex()) {
                val start = group.start.coerceIn(floor, limit)
                val end = if (i + 1 < groups.size) groups[i + 1].start.coerceIn(start, limit) else limit
                words += Word(group.text.trim(), start, end)
                floor = start
            }
            // The text is the authority on spelling: a letter of several bytes that Whisper
            // split over two tokens is whole there and broken in the tokens.
            val spelled = result.text.trim().split(Regex("\\s+"))
            if (spelled.size == words.size) for (i in words.indices) words[i] = words[i].copy(text = spelled[i])
            return words
        }

        /**
         * The alignment says where a word starts and nothing about where it ends, so a pause
         * is counted into a word next to it, and the silence before the first word into that
         * word. This moves the edges of each word inward to where there is sound: 20 ms
         * slices, quiet when under the same adaptive gate the app's speech plan uses.
         *
         * Whisper tends to start a word too early when a pause precedes it: the word then
         * holds the end of the word before, the pause, and itself. So when a word has at
         * most 0.3 s of sound, then at least 0.2 s of quiet, then sound again, it starts
         * after the quiet and the word before it gets the sound before. A guess, right for
         * the recordings tried; a held consonant of 0.2 s inside a word would fool it.
         *
         * A word never grows beyond its neighbors, and a word that is quiet throughout is
         * left as it was.
         */
        internal fun tightened(words: List<Word>, samples: FloatArray, sampleRate: Int): List<Word> {
            if (words.isEmpty()) return words
            val step = maxOf(1, sampleRate / 50)
            val seconds = step.toDouble() / sampleRate
            val first = (words.first().start / seconds).toInt().coerceIn(0, samples.size / step)
            val last = Math.ceil(words.last().end / seconds).toInt().coerceIn(first, samples.size / step)
            if (last == first) return words
            val db = DoubleArray(last - first) { i -> 10 * Math.log10(maxOf(1e-18, level(samples, (first + i) * step, (first + i + 1) * step))) }
            val sorted = db.sortedArray()
            val gate = maxOf(-52.0, minOf(-35.0, sorted[minOf(sorted.size - 1, sorted.size / 5)] + 9))
            val result = ArrayList<Word>(words.size)
            for (word in words) {
                val from = ((word.start / seconds).toInt() - first).coerceIn(0, db.size)
                val to = (Math.ceil(word.end / seconds).toInt() - first).coerceIn(from, db.size)
                var loudFirst = -1
                var loudLast = -1
                for (i in from until to) if (db[i] > gate) { if (loudFirst < 0) loudFirst = i; loudLast = i }
                if (loudFirst < 0) { result += word; continue }

                val previous = result.lastOrNull()
                if (previous != null && previous.end > word.start - 0.08) {
                    var quiet = 0
                    for (i in loudFirst..loudLast) {
                        if (db[i] <= gate) { quiet++; continue }
                        val pause = i - quiet
                        if (quiet >= PAUSE_SLICES && pause - loudFirst <= LEFTOVER_SLICES) {
                            val end = minOf((first + pause) * seconds + 0.06, (first + i) * seconds - 0.04)
                            if (end > previous.end) result[result.lastIndex] = previous.copy(end = end)
                            loudFirst = i
                        }
                        if (quiet >= PAUSE_SLICES) break
                        quiet = 0
                    }
                }
                // A little wider than the gate: a word begins and fades below it.
                val start = maxOf(word.start, result.lastOrNull()?.end ?: 0.0, (first + loudFirst) * seconds - 0.04)
                val end = minOf(word.end, (first + loudLast + 1) * seconds + 0.06)
                result += if (end > start) word.copy(start = start, end = end) else word
            }
            return result
        }

        private const val PAUSE_SLICES = 10
        private const val LEFTOVER_SLICES = 15

        private fun unspaced(point: Int): Boolean = when (Character.UnicodeScript.of(point)) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.THAI, Character.UnicodeScript.LAO, Character.UnicodeScript.KHMER, Character.UnicodeScript.MYANMAR -> true
            else -> false
        }
    }
}
