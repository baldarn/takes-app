package app.takes.sound

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.util.Log
import app.takes.DecodedSource
import app.takes.MIX_RATE
import java.io.File
import kotlin.math.abs

/**
 * A sound file read by [DecodedSource] from places partway into it, as a sound item cut or trimmed there plays:
 * a 44.1 kHz mono AAC file of a 440 Hz tone with a click every half second, read at the mix's 48 kHz in two
 * channels a block at a time from each place. Every reading must be free of silence, and its clicks must lie on
 * the half seconds of the file.
 *
 *     adb shell am instrument -w -e clip music.m4a app.takes.dev.test/app.takes.sound.DecodedSourceRunner
 *
 * The clip is read from /data/local/tmp (made with ffmpeg: `aevalsrc='0.3*sin(2*PI*440*t)+if(lt(mod(n\,22050)\,4)\,0.6\,0)':s=44100`,
 * `-ac 1 -c:a aac`).
 */
class DecodedSourceRunner : Instrumentation() {
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
        try {
            val file = File("/data/local/tmp", arguments.getString("clip") ?: error("-e clip <name>"))
            for (from in listOf(0.0, 0.5, 1.0, 3.21, 5.833, 7.033, 12.4)) {
                val (line, ok) = read(file, from)
                if (!ok) failed++
                Log.i(TAG, line)
                report.append(line).append('\n')
            }
        } catch (problem: Throwable) {
            failed++
            report.append(Log.getStackTraceString(problem))
        }
        report.append(if (failed == 0) "\nOK" else "\nFAILURES!!! $failed")
        finish(Activity.RESULT_OK, Bundle().apply { putString(REPORT_KEY_STREAMRESULT, report.toString()) })
    }

    /** Two seconds read from [from]: what silence there is and where the clicks fall. */
    private fun read(file: File, from: Double): Pair<String, Boolean> {
        val rate = MIX_RATE
        val first = Math.round(from * rate)
        val total = 2 * rate
        val samples = FloatArray(total * 2)
        DecodedSource(file, rate, 2, from).use { source ->
            var done = 0
            while (done < total) {
                val count = minOf(BLOCK, total - done)
                source.read(first + done, count, samples, done)
                done += count
            }
        }
        // Silence: runs of more than 5 ms with nothing in them.
        val gaps = mutableListOf<String>()
        var quiet = 0
        for (frame in 0..total) {
            if (frame < total && abs(samples[frame * 2]) < 1e-4f) quiet++
            else {
                if (quiet > rate / 200) gaps += "%.3f–%.3f".format((first + frame - quiet).toDouble() / rate, (first + frame).toDouble() / rate)
                quiet = 0
            }
        }
        // Clicks: the loudest frame of each stretch over the tone's level.
        val clicks = mutableListOf<Double>()
        var frame = 0
        while (frame < total) {
            if (abs(samples[frame * 2]) > 0.5f) {
                var best = frame
                val end = minOf(total, frame + rate / 100)
                for (f in frame until end) if (abs(samples[f * 2]) > abs(samples[best * 2])) best = f
                clicks += (first + best).toDouble() / rate
                frame = end
            } else frame++
        }
        val late = clicks.map { (it - Math.round(it * 2) / 2.0) * 1000 }
        val ok = gaps.isEmpty() && clicks.isNotEmpty() && late.all { abs(it) < 2.0 }
        val line = "%s from %.3f: silence %s; clicks %s".format(
            if (ok) "ok  " else "FAIL", from, gaps.ifEmpty { listOf("none") },
            clicks.zip(late).joinToString { (at, ms) -> "%.3f (%+.1f ms)".format(at, ms) },
        )
        return line to ok
    }

    private companion object {
        const val TAG = "DecodedSource"
        const val BLOCK = 1024
    }
}
