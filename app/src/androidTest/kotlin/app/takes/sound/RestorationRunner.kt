package app.takes.sound

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import app.takes.Denoiser
import app.takes.RestorationModelStore
import app.takes.core.AudioEffect
import app.takes.core.AudioEffectKind
import app.takes.restoration.RestorationModels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The two speech restoration stages on a real recording, through the app's own path: the models downloaded
 * as Enhance audio downloads them (into the development build's own folder, where the app then finds them),
 * then each stage alone made for the whole clip by [Denoiser.process], as the export makes it, timed; then
 * the same stopped half way and made again, which must resume from its kept windows and give the same file.
 *
 *     adb push speech.m4a /data/local/tmp/speech.m4a
 *     adb shell am instrument -w -e clip /data/local/tmp/speech.m4a -e download true app.takes.dev.test/app.takes.sound.RestorationRunner
 *
 * `-e only studioVoice` or `studioRestoration` runs one. It reports how fast each runs against real time and
 * the process's peak resident memory (VmHWM) after each.
 */
class RestorationRunner : Instrumentation() {
    private lateinit var arguments: Bundle

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        super.onStart()
        val report = StringBuilder()
        var failed = 0
        fun say(line: String) {
            Log.i(TAG, line)
            report.append(line).append('\n')
        }
        try {
            RestorationModelStore.init(targetContext)
            val source = File(arguments.getString("clip") ?: "/data/local/tmp/speech.m4a")
            val folder = File(targetContext.cacheDir, "restoration-runner/take").apply { deleteRecursively(); mkdirs() }
            val clip = File(folder, source.name)
            source.copyTo(clip, overwrite = true)
            val only = arguments.getString("only")
            say("${clip.name}: memory at start ${peak()}")
            for (kind in listOf(AudioEffectKind.StudioVoice, AudioEffectKind.StudioRestoration)) {
                if (only != null && only != kind.rawValue) continue
                val model = RestorationModels.of(kind)!!
                if (!RestorationModelStore.installed(model)) {
                    check(arguments.getString("download") == "true") { "${model.name} is not downloaded; add -e download true" }
                    val started = SystemClock.elapsedRealtime()
                    var shown = -1
                    runBlocking(Dispatchers.IO) {
                        RestorationModelStore.download(model) { done ->
                            val percent = (done * 10 / model.bytes).toInt()
                            if (percent != shown) { shown = percent; Log.i(TAG, "${model.name}: ${done / 1_000_000} of ${model.bytes / 1_000_000} MB") }
                        }
                    }
                    say("${model.name}: downloaded and checked, ${model.bytes} bytes in ${(SystemClock.elapsedRealtime() - started) / 1000} s")
                }
                val effects = listOf(AudioEffect.make(kind))
                File(folder, "Cache").deleteRecursively()
                var started = SystemClock.elapsedRealtime()
                val whole = runBlocking { Denoiser.process(clip, effects) } ?: error("${kind.title} was not made")
                val took = (SystemClock.elapsedRealtime() - started) / 1000.0
                val (frames, channels) = read(whole)
                val seconds = frames / 48_000.0
                say("%s (%s): %.1f s of sound, %d channels, in %.1f s, %.2f× real time; peak memory %s".format(
                    kind.title, model.name, seconds, channels, took, seconds / took, peak()))
                val expected = Math.round(27.676313 * 48_000)
                if (source.name == "speech.m4a" && kotlin.math.abs(frames - expected) > 2048) { failed++; say("  $frames frames, expected about $expected") }

                // Stopped half way, then made again: the windows kept are used, and the sound is the same.
                val first = whole.readBytes()
                whole.delete()
                File(whole.path + ".priority").delete()
                runBlocking {
                    var half = false
                    val job = async(Dispatchers.Default) { Denoiser.process(clip, effects) { if (it > 0.6f) half = true } }
                    while (!half && job.isActive) kotlinx.coroutines.delay(50)
                    try { job.cancelAndJoin() } catch (_: CancellationException) {}
                }
                val kept = File(folder, "Cache").listFiles().orEmpty().filter { it.isDirectory }.sumOf { it.listFiles().orEmpty().size }
                started = SystemClock.elapsedRealtime()
                val again = runBlocking { Denoiser.process(clip, effects) } ?: error("${kind.title} was not made again")
                val resumed = (SystemClock.elapsedRealtime() - started) / 1000.0
                val same = again.readBytes().contentEquals(first)
                say("  stopped half way with $kept windows kept, made again in %.1f s: %s".format(resumed, if (same) "the same file" else "A DIFFERENT FILE"))
                if (!same) failed++
                val left = File(folder, "Cache").listFiles().orEmpty().count { it.isDirectory }
                if (left != 0) { failed++; say("  $left folders of windows left after the whole recording was made") }
            }
        } catch (problem: Throwable) {
            failed++
            say(Log.getStackTraceString(problem))
        }
        report.append(if (failed == 0) "\nOK" else "\nFAILURES!!! $failed")
        finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString(REPORT_KEY_STREAMRESULT, report.toString() + "\n")
        })
    }

    /** The frames and channels of a 16-bit WAV written by [Denoiser]; a stage that made a sample that is not a number fails instead. */
    private fun read(file: File): Pair<Long, Int> {
        val header = ByteArray(44)
        RandomAccessFile(file, "r").use { it.readFully(header) }
        val channels = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getShort(22).toInt()
        return (file.length() - 44) / (2L * channels) to channels
    }

    /** The process's peak and current resident memory. */
    private fun peak(): String {
        val status = File("/proc/self/status").readLines()
        fun field(name: String) = status.firstOrNull { it.startsWith(name) }?.substringAfter(':')?.trim() ?: "?"
        return "VmHWM ${field("VmHWM")}, VmRSS ${field("VmRSS")}"
    }

    private companion object {
        const val TAG = "RestorationRunner"
    }
}
