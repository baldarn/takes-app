package app.takes.speech

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The real recognizer on real speech: sherpa-onnx built for this computer, loaded through
 * the same Kotlin classes the phone uses, with the models fetched by [SpeechModelFetcher]
 * from where the app would fetch them.
 *
 * Needs `-Pspeech.models=<folder>` (see the README); skipped without it. The recordings are
 * the test files published with sherpa-onnx's models: LibriSpeech English, and one Italian
 * sentence. What is printed is what was recognized and measured.
 */
class SpeechRecognizerTest {
    private val models: File? = System.getProperty("speech.models")?.let(::File)

    private fun folder(model: SpeechModel, name: String): File {
        assumeTrue("No -Pspeech.models=<folder>: recognition tests skipped", models != null)
        val folder = File(models!!, name)
        if (!model.installed(folder)) {
            val started = System.nanoTime()
            SpeechModelFetcher.download(model, folder, HttpSource)
            println("Fetched ${model.id}: ${model.bytes} bytes in ${(System.nanoTime() - started) / 1_000_000} ms")
        }
        return folder
    }

    private fun tiny() = SpeechRecognizer(folder(SpeechModel.WhisperTiny, "tiny"), null, SpeechModel.WhisperTiny)
    private fun base(language: String? = null) = SpeechRecognizer(folder(SpeechModel.WhisperBase, "base"), language, SpeechModel.WhisperBase)

    /** A recording at 16 kHz, downloaded once and checked. */
    private fun recording(name: String): FloatArray {
        assumeTrue("No -Pspeech.models=<folder>: recognition tests skipped", models != null)
        val (url, hash) = RECORDINGS.getValue(name)
        val file = File(models!!, "recordings/$name")
        if (!file.isFile || SpeechModel.sha256(file) != hash) {
            file.parentFile!!.mkdirs()
            HttpSource.open(url, 0).stream.use { input -> file.outputStream().use { input.copyTo(it) } }
            assertEquals(hash, SpeechModel.sha256(file))
        }
        val (samples, rate) = Wav.read(file)
        assertEquals(16000, rate)
        return samples
    }

    private fun seconds(samples: FloatArray) = samples.size / 16000.0
    private fun silence(seconds: Double) = FloatArray((seconds * 16000).toInt())
    private fun letters(text: String) = text.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }

    /** Word error rate: substitutions, insertions and deletions over the length of the reference. */
    private fun errors(reference: String, found: String): Double {
        val a = letters(reference)
        val b = letters(found)
        var row = IntArray(b.size + 1) { it }
        for (i in 1..a.size) {
            val next = IntArray(b.size + 1)
            next[0] = i
            for (j in 1..b.size) next[j] = minOf(row[j] + 1, next[j - 1] + 1, row[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            row = next
        }
        return row[b.size].toDouble() / a.size
    }

    private fun show(label: String, samples: FloatArray, transcript: Transcript, reference: String? = null) {
        println("$label (%.1f s, language ${transcript.language})".format(seconds(samples)))
        println("    ${transcript.text}")
        if (reference != null) println("    word error rate %.1f %%".format(100 * errors(reference, transcript.text)))
        transcript.words?.let { words -> println("    " + words.joinToString(" ") { "%s[%.2f–%.2f]".format(it.text, it.start, it.end) }) }
    }

    @Test fun missingFilesAreReportedBeforeAnythingNativeRuns() {
        val empty = Files.createTempDirectory("speech-empty").toFile()
        try {
            val failure = assertThrows(IllegalArgumentException::class.java) { SpeechRecognizer(empty) }
            assertTrue(failure.message!!.startsWith("Missing base-encoder.int8.onnx"))
        } finally {
            empty.deleteRecursively()
        }
    }

    @Test fun filesThatAreNotAModelAreRefused() {
        // Needs sherpa-onnx itself, and no model.
        val folder = Files.createTempDirectory("speech-damaged").toFile()
        try {
            // Only the networks are damaged. A token table that is not text makes sherpa-onnx
            // end the whole process (seen here: exit code 255), which no test can survive
            // and no app either: verify the files before loading them.
            for (file in SpeechModel.WhisperBase.files) File(folder, file.name).writeBytes(ByteArray(4096) { (it * 31).toByte() })
            File(folder, SpeechModel.WhisperBase.tokens.name).writeText("IQ== 0\nIg== 1\n")
            val failure = assertThrows(IllegalArgumentException::class.java) { SpeechRecognizer(folder) }
            assertTrue(failure.message!!.startsWith("Cannot load the model in "))
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test fun recognizesEnglish() {
        for ((name, make, limit) in listOf(Triple("tiny", ::tiny, 0.20), Triple("base", { base() }, 0.10))) make().use { recognizer ->
            for (file in listOf("0.wav", "1.wav")) {
                val samples = recording(file)
                val transcript = recognizer.transcribe(samples)
                show("$name $file", samples, transcript, REFERENCE.getValue(file))
                assertEquals("en", transcript.language)
                assertTrue(errors(REFERENCE.getValue(file), transcript.text) <= limit)
                // The published models have no attention outputs, so no word times.
                assertNull(transcript.words)
            }
            // The short sentence comes out word for word.
            assertEquals(letters(REFERENCE.getValue("0.wav")), letters(recognizer.transcribe(recording("0.wav")).text))
        }
    }

    @Test fun recognizesItalianWithoutBeingTold() {
        for ((name, make) in listOf("tiny" to ::tiny, "base" to { base() })) make().use { recognizer ->
            val samples = recording("it.wav")
            val transcript = recognizer.transcribe(samples)
            show("$name it.wav detected", samples, transcript, ITALIAN)
            assertEquals("it", transcript.language)
            assertEquals(letters(ITALIAN), letters(transcript.text))
        }
    }

    @Test fun theLanguageCanBeForcedForARecognizerOrForACall() {
        val italian = recording("it.wav")
        val english = recording("0.wav")
        base("it").use { recognizer ->
            val forced = recognizer.transcribe(italian)
            show("base it.wav forced it", italian, forced, ITALIAN)
            assertEquals("it", forced.language)
            assertEquals(letters(ITALIAN), letters(forced.text))

            // The wrong language: Whisper writes what it hears as if it were English.
            val wrong = recognizer.transcribe(italian, language = "en")
            show("base it.wav forced en", italian, wrong)
            assertEquals("en", wrong.language)
            assertNotEquals(letters(ITALIAN), letters(wrong.text))

            // English forced to Italian comes out translated or mangled, not as the English it is.
            val mangled = recognizer.transcribe(english)
            show("base 0.wav forced it", english, mangled)
            assertEquals("it", mangled.language)

            // Detection for one call, on the same recognizer.
            val detected = recognizer.transcribe(english, language = null)
            show("base 0.wav detected", english, detected, REFERENCE.getValue("0.wav"))
            assertEquals("en", detected.language)
            assertTrue(errors(REFERENCE.getValue("0.wav"), detected.text) <= 0.1)

            // And the recognizer's own choice is back afterwards.
            assertEquals(forced.text, recognizer.transcribe(italian).text)
        }
    }

    @Test fun anotherSampleRateIsAccepted() {
        assumeTrue(models != null)
        val (url, hash) = RECORDINGS.getValue("8k.wav")
        val file = File(models!!, "recordings/8k.wav")
        if (!file.isFile || SpeechModel.sha256(file) != hash) {
            file.parentFile!!.mkdirs()
            HttpSource.open(url, 0).stream.use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        val (samples, rate) = Wav.read(file)
        assertEquals(8000, rate)
        base().use { recognizer ->
            val transcript = recognizer.transcribe(samples, rate)
            println("base 8k.wav at 8 kHz\n    ${transcript.text}")
            assertTrue(errors(REFERENCE.getValue("8k.wav"), transcript.text) <= 0.2)
        }
    }

    @Test fun audioLongerThanWhisperReadsIsCutAndJoined() {
        val a = recording("0.wav")
        val b = recording("1.wav")
        val samples = a + silence(0.6) + b + silence(0.6) + a + silence(0.6) + b
        val reference = listOf("0.wav", "1.wav", "0.wav", "1.wav").joinToString(" ") { REFERENCE.getValue(it) }
        assertTrue(seconds(samples) > 45)
        val folder = folder(SpeechModel.WhisperBase, "base")

        // sherpa-onnx by itself reads the first 30 seconds and drops the rest without an error.
        val raw = OfflineRecognizer(null, OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
            whisper = OfflineWhisperModelConfig(encoder = "$folder/base-encoder.int8.onnx", decoder = "$folder/base-decoder.int8.onnx", language = "en"),
            tokens = "$folder/base-tokens.txt", modelType = "whisper", numThreads = 4,
        )))
        val stream = raw.createStream()
        stream.acceptWaveform(samples, 16000)
        raw.decode(stream)
        val truncated = raw.getResult(stream).text
        stream.release()
        raw.release()
        println("sherpa-onnx alone, %.1f s in one call: ${letters(truncated).size} of ${letters(reference).size} words\n    $truncated".format(seconds(samples)))
        assertTrue(letters(truncated).size < letters(reference).size * 0.75)

        base().use { recognizer ->
            assertEquals(3, SpeechRecognizer.windows(samples, 16000).size)
            val transcript = recognizer.transcribe(samples)
            show("base, the same through SpeechRecognizer", samples, transcript, reference)
            assertTrue(errors(reference, transcript.text) <= 0.1)
            assertEquals(listOf("soul", "in", "heaven"), letters(transcript.text).takeLast(3))
        }
    }

    @Test fun nothingAndSilence() {
        base().use { recognizer ->
            val nothing = Transcript("", emptyList(), "")
            assertEquals(nothing, recognizer.transcribe(FloatArray(0)))
            // Whisper itself answers "1.0" in Norwegian to this.
            assertEquals(nothing, recognizer.transcribe(silence(3.0)))
            val short = recognizer.transcribe(recording("0.wav").copyOf(800))
            println("base on 50 ms of sound: \"${short.text}\"")
            // Quiet hiss is not skipped; what comes out of it is Whisper's.
            val random = java.util.Random(1)
            val hiss = recognizer.transcribe(FloatArray(3 * 16000) { (random.nextGaussian() * 0.003).toFloat() })
            println("base on 3 s of hiss at −50 dB: \"${hiss.text}\"")
        }
    }

    @Test fun aClosedRecognizerRefusesWork() {
        val recognizer = tiny()
        recognizer.close()
        recognizer.close()
        assertThrows(IllegalStateException::class.java) { recognizer.transcribe(silence(1.0)) }
    }

    /** Resident memory of this process in MB, from Linux; -1 elsewhere. */
    private fun resident(peak: Boolean = false): Long = runCatching {
        File("/proc/self/status").readLines().first { it.startsWith(if (peak) "VmHWM:" else "VmRSS:") }.split(Regex("\\s+"))[1].toLong() / 1024
    }.getOrDefault(-1)

    @Test fun speedAndMemoryOnThisComputer() {
        val a = recording("0.wav")
        val b = recording("1.wav")
        val long = a + silence(0.6) + b + silence(0.6) + a + silence(0.6) + b
        // What the app will hand over: pieces of at most 12 s.
        val twelve = b.copyOf(12 * 16000)
        println("Desktop figures: ${Runtime.getRuntime().availableProcessors()} logical processors, ${System.getProperty("os.arch")}. A phone is slower.")
        for ((name, model) in listOf("tiny" to SpeechModel.WhisperTiny, "base" to SpeechModel.WhisperBase)) {
            val folder = folder(model, name)
            for (threads in listOf(4, 1)) {
                System.gc()
                val before = resident()
                var started = System.nanoTime()
                SpeechRecognizer(folder, "en", model, threads).use { recognizer ->
                    val load = (System.nanoTime() - started) / 1e6
                    val loaded = resident()
                    val lines = mutableListOf<String>()
                    var afterTwelve = 0L
                    for ((label, samples) in listOf("12 s" to twelve, "16.7 s" to b, "48.5 s" to long)) {
                        val times = (1..3).map {
                            started = System.nanoTime()
                            recognizer.transcribe(samples)
                            (System.nanoTime() - started) / 1e9
                        }.sorted()
                        if (afterTwelve == 0L) afterTwelve = resident()
                        lines += "%s in %.2f s (%.3f of real time, %.0f× faster)".format(label, times[1], times[1] / seconds(samples), seconds(samples) / times[1])
                    }
                    println("$name, $threads thread(s): loaded in %.0f ms; ${lines.joinToString("; ")}".format(load))
                    println("    resident memory: $before MB before, $loaded MB loaded, $afterTwelve MB after 12 s pieces, ${resident()} MB after 24 s windows; peak of the whole test process so far ${resident(peak = true)} MB")
                }
            }
        }
    }

    // Word times need a model exported with attention outputs, which nobody trustworthy
    // publishes; -Pspeech.timedModel=<folder> names one (see the README).
    private fun timed(): SpeechRecognizer {
        val folder = System.getProperty("speech.timedModel")?.let(::File)
        assumeTrue("No -Pspeech.timedModel=<folder>: word time tests skipped", folder != null)
        return SpeechRecognizer(folder!!, null, SpeechModel.WhisperBase)
    }

    /** Where 20 ms slices are louder than −40 dB of full scale: start of the first, end of the last. */
    private fun sound(samples: FloatArray): Pair<Double, Double> {
        val loud = (0 until samples.size / 320).filter { i ->
            var sum = 0.0
            for (k in i * 320 until (i + 1) * 320) sum += samples[k] * samples[k]
            20 * log10(maxOf(1e-9, sqrt(sum / 320))) > -40
        }
        return loud.first() * 0.02 to (loud.last() + 1) * 0.02
    }

    private fun checkOrder(words: List<Word>, duration: Double) {
        for ((i, word) in words.withIndex()) {
            assertTrue("$word", word.start >= 0 && word.end <= duration + 1e-6 && word.end >= word.start)
            if (i > 0) assertTrue("${words[i - 1]} $word", word.start >= words[i - 1].end - 1e-6)
        }
    }

    @Test fun wordTimesAreOrderedAndInsideTheAudio() {
        timed().use { recognizer ->
            for (file in listOf("0.wav", "1.wav", "it.wav")) {
                val samples = recording(file)
                val transcript = recognizer.transcribe(samples)
                show("base with attention, $file", samples, transcript)
                val words = transcript.words!!
                assertEquals(transcript.text, words.joinToString(" ") { it.text })
                checkOrder(words, seconds(samples))
                // The first word begins where the sound begins and the last ends where it ends.
                val (from, to) = sound(samples)
                println("    sound from %.2f to %.2f s".format(from, to))
                assertEquals(from, words.first().start, 0.15)
                assertEquals(to, words.last().end, 0.25)
                // Nobody says a word in under 40 ms or holds one for two seconds here.
                for (word in words) assertTrue("$word", word.end - word.start in 0.04..2.0)
            }
        }
    }

    @Test fun wordTimesFollowAPause() {
        timed().use { recognizer ->
            val a = recording("0.wav")
            val b = recording("1.wav").copyOf(13 * 16000)
            val alone = recognizer.transcribe(b, language = "en").words!!
            val samples = a + silence(2.0) + b
            val transcript = recognizer.transcribe(samples, language = "en")
            show("base with attention, 0.wav, 2 s of silence, 13 s of 1.wav", samples, transcript)
            val words = transcript.words!!
            checkOrder(words, seconds(samples))
            assertEquals(18 + alone.size, words.size)
            // The sentence before the silence ends before it and the one after starts after it.
            val second = seconds(a) + 2.0
            assertEquals(sound(a).second, words[17].end, 0.25)
            assertEquals(second + sound(b).first, words[18].start, 0.15)
            // Every word of the second part is where it is when that part is recognized alone.
            val drift = alone.indices.map { words[18 + it].start - second - alone[it].start }
            println("    the second part against itself alone, word by word: " + drift.joinToString(" ") { "%+.2f".format(it) })
            // Most words within a few hundredths; one or two near the start move more.
            assertTrue(drift.map(Math::abs).sorted()[drift.size / 2] <= 0.05)
            for (difference in drift) assertEquals(0.0, difference, 0.3)
        }
    }

    @Test fun wordTimesContinueAcrossWindows() {
        timed().use { recognizer ->
            val a = recording("0.wav")
            val b = recording("1.wav")
            val samples = a + silence(0.6) + b + silence(0.6) + a + silence(0.6) + b
            val transcript = recognizer.transcribe(samples, language = "en")
            show("base with attention, 48.5 s", samples, transcript)
            val words = transcript.words!!
            checkOrder(words, seconds(samples))
            assertEquals(transcript.text, words.joinToString(" ") { it.text })
            // The last sentence starts where it was put.
            val start = seconds(samples) - seconds(b)
            val god = words.last { letters(it.text) == listOf("god") }
            assertEquals(start + sound(b).first, god.start, 0.2)
        }
    }

    private companion object {
        const val TINY = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/65176e2deb88badc814a94058666cadccc29b61c/test_wavs"
        val RECORDINGS = mapOf(
            "0.wav" to ("$TINY/0.wav" to "6bc58a4efdf20daac252b6b1502632601a71efe0308f6757dc1eda34891a7e4f"),
            "1.wav" to ("$TINY/1.wav" to "5143a6ba93c4b274e2c4ac22deb75c2c48936c853f0519add1de828b6c79cc5a"),
            "8k.wav" to ("$TINY/8k.wav" to "f6f3c8b33e2534cdc154fe773ad2750f1f6a2ca5096179cdf037ae782456613e"),
            "it.wav" to ("https://huggingface.co/csukuangfj/sherpa-onnx-nemo-fast-conformer-ctc-be-de-en-es-fr-hr-it-pl-ru-uk-20k/resolve/0ef311bd5a950818b5332b294aadb680f90a447f/test_wavs/it-italian.wav" to "c93a277437f34bdb704d6fb5da99fba87146fcb6fcbd5b8fbfd725758722c7c5"),
        )

        // From test_wavs/trans.txt of the model repository.
        val REFERENCE = mapOf(
            "0.wav" to "AFTER EARLY NIGHTFALL THE YELLOW LAMPS WOULD LIGHT UP HERE AND THERE THE SQUALID QUARTER OF THE BROTHELS",
            "1.wav" to "GOD AS A DIRECT CONSEQUENCE OF THE SIN WHICH MAN THUS PUNISHED HAD GIVEN HER A LOVELY CHILD WHOSE PLACE WAS ON THAT SAME DISHONOURED BOSOM TO CONNECT HER PARENT FOR EVER WITH THE RACE AND DESCENT OF MORTALS AND TO BE FINALLY A BLESSED SOUL IN HEAVEN",
            "8k.wav" to "YET THESE THOUGHTS AFFECTED HESTER PRYNNE LESS WITH HOPE THAN APPREHENSION",
        )

        // What the speaker says; the model repository has no transcript for this file.
        const val ITALIAN = "Gli uomini sono turbati non dalle cose, ma dal modo in cui le vedono."
    }
}
