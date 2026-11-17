package app.takes.sound

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import app.takes.SubtitleModel
import app.takes.decodeAudio
import app.takes.deepfilter.DeepFilterModel
import app.takes.deepfilter.deepFilter
import app.takes.speech.SpeechModel
import app.takes.speech.SpeechRecognizer
import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * DeepFilterNet3 on a real recording, timed, in the same process as sherpa-onnx's recognizers: the two
 * share the one ONNX Runtime the app carries (speech/ and deepfilter/ READMEs).
 *
 *     adb push take.mp4 /data/local/tmp/front.mp4
 *     adb shell am instrument -w -e clip /data/local/tmp/front.mp4 app.takes.dev.test/app.takes.sound.DeepFilterRunner
 *
 * The first `-e seconds` (60) of the clip's sound, each channel cleaned on its own at amount 1 as the
 * export does. Before it, every speech model the development build has installed, or that lies in the folder
 * `-e speech` names (`<folder>/tiny`, as the speech module's tests leave them), transcribes the first 20
 * seconds of the first channel; after it, the first of those models transcribes the cleaned channel. Without
 * a model, sherpa-onnx's library is only loaded. It reports the ONNX Runtime libraries in the APK, which must
 * be one, how fast DeepFilterNet3 runs against real time, and how much quieter the pauses get.
 */
class DeepFilterRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle()
        start()
    }

    private lateinit var arguments: Bundle

    override fun onStart() {
        super.onStart()
        val report = StringBuilder()
        var failed = 0
        fun say(line: String) {
            Log.i(TAG, line)
            report.append(line).append('\n')
        }
        try {
            val clip = File(arguments.getString("clip") ?: "/data/local/tmp/front.mp4")
            val seconds = arguments.getString("seconds")?.toDouble() ?: 60.0
            val channels = decode(clip, seconds)
            val rate = channels.rate
            say("${clip.name}: ${channels.samples.size} channels at $rate Hz, %.1f s".format(channels.samples[0].size.toDouble() / rate))
            check(rate == 48_000) { "DeepFilterNet3 takes 48 kHz; resample first" }

            val extra = arguments.getString("speech")?.let(::File)
            val speech = listOf(SpeechModel.WhisperTiny, SpeechModel.WhisperBase, SpeechModel.WhisperSmall, SpeechModel.Qwen3Asr).mapNotNull { model ->
                listOfNotNull(SubtitleModel.folder(targetContext, model), extra?.let { File(it, model.folder) })
                    .firstOrNull { model.installed(it) }?.let { model to it }
            }
            if (speech.isEmpty()) {
                System.loadLibrary("sherpa-onnx-jni")
                say("no speech model installed: sherpa-onnx's library loaded only")
            }
            val twenty = minOf(channels.samples[0].size, 20 * rate)
            fun transcribe(label: String, samples: FloatArray, models: List<Pair<SpeechModel, File>>) {
                for ((model, folder) in models) {
                    val started = SystemClock.elapsedRealtime()
                    SpeechRecognizer(folder, model).use { recognizer ->
                        val text = recognizer.transcribe(samples.copyOf(twenty), rate).text
                        say("$label, ${model.name}: ${SystemClock.elapsedRealtime() - started} ms, \"${text.take(200)}\"")
                    }
                }
            }
            transcribe("speech before, as recorded", channels.samples[0], speech)

            var started = SystemClock.elapsedRealtime()
            val model = DeepFilterModel.bundled() ?: error("this build carries no DeepFilterNet3")
            say("DeepFilterNet3 loaded in ${SystemClock.elapsedRealtime() - started} ms")
            val cleaned = channels.samples.map { it.copyOf() }
            started = SystemClock.elapsedRealtime()
            for (channel in cleaned) deepFilter(model, channel, 1f)
            val took = (SystemClock.elapsedRealtime() - started) / 1000.0
            val audio = channels.samples[0].size.toDouble() / rate * channels.samples.size
            say("DeepFilterNet3: %.1f s of audio (all channels) in %.2f s, %.1f× real time".format(audio, took, audio / took))
            for ((index, channel) in cleaned.withIndex()) {
                val dry = channels.samples[index]
                if (channel.any { !it.isFinite() }) { failed++; say("channel $index: not a number in the output") }
                say("channel %d: level %.1f → %.1f dBFS, quietest tenth of 100 ms blocks %.1f → %.1f dBFS".format(
                    index, db(rms(dry)), db(rms(channel)), db(quietest(dry)), db(quietest(channel))))
            }
            model.close()
            transcribe("speech after, cleaned", cleaned[0], speech.take(1))

            // sherpa-onnx's library and this module's both load the one libonnxruntime.so; no other carries it.
            val abi = android.os.Build.SUPPORTED_ABIS[0]
            val libraries = java.util.zip.ZipFile(targetContext.applicationInfo.sourceDir).use { apk ->
                apk.entries().toList().map { it.name }.filter { it.startsWith("lib/$abi/") }
            }
            say("libraries for $abi: ${libraries.joinToString { it.substringAfterLast('/') }}")
            if (libraries.count { "onnxruntime" in it } != 1) { failed++; say("expected one ONNX Runtime library") }
        } catch (problem: Throwable) {
            failed++
            say(Log.getStackTraceString(problem))
        }
        report.append(if (failed == 0) "\nOK" else "\nFAILURES!!! $failed")
        finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString(Instrumentation.REPORT_KEY_STREAMRESULT, report.toString() + "\n")
        })
    }

    private class Channels(val rate: Int, val samples: List<FloatArray>)

    /** The first [seconds] of [file]'s sound, one float array a channel. */
    private fun decode(file: File, seconds: Double): Channels {
        var rate = 0
        var count = 0
        var out: List<FloatArray> = emptyList()
        var limit = 0
        val done = decodeAudio(file, begin = { format ->
            rate = format.sampleRate
            limit = (seconds * rate).toInt()
            out = List(format.channels) { FloatArray(limit) }
        }, until = seconds) { block, _ ->
            val channels = out.size
            while (block.remaining() >= channels && count < limit) {
                for (channel in 0 until channels) out[channel][count] = block.get() / 32768f
                count++
            }
        }
        check(done != null && out.isNotEmpty()) { "no sound decoded from $file" }
        return Channels(rate, out.map { it.copyOf(count) })
    }

    private fun rms(samples: FloatArray, from: Int = 0, to: Int = samples.size): Double {
        var sum = 0.0
        for (i in from until to) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / maxOf(1, to - from))
    }

    /** The level of the quietest tenth of 100 ms blocks: what is left in the pauses. */
    private fun quietest(samples: FloatArray): Double {
        val block = 4800
        val levels = (0 until samples.size / block).map { rms(samples, it * block, (it + 1) * block) }.sorted()
        return if (levels.isEmpty()) 0.0 else levels.take(maxOf(1, levels.size / 10)).average()
    }

    private fun db(level: Double) = 20 * log10(maxOf(level, 1e-9))

    private companion object {
        const val TAG = "DeepFilterRunner"
    }
}
