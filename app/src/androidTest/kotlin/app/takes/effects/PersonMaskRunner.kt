package app.takes.effects

import android.app.Activity
import android.app.Instrumentation
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import app.takes.PersonMasks
import app.takes.core.PersonMaskChunks
import app.takes.core.SourceMedia
import java.io.File

/**
 * The person-mask analysis on a real recording, timed: the clip named by `-e clip` in the development build's
 * `files/person-masks/` folder, which stands for a project folder; its masks go to `Cache/masks/` beside it.
 *
 *     adb shell run-as app.takes.dev mkdir -p files/person-masks
 *     adb shell run-as app.takes.dev cp /data/local/tmp/take.mp4 files/person-masks/
 *     adb shell am instrument -w -e clip take.mp4 app.takes.dev.test/app.takes.effects.PersonMaskRunner
 *
 * It reports whether LiteRT loads, frames a second, the chunks' sizes on disk, and that a second analysis finds
 * every chunk made and the reader gives a mask for every frame. `-e keep true` leaves the masks for a look;
 * `-e warm true` segments each chunk's two-second warm-up too.
 */
class PersonMaskRunner : Instrumentation() {
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
            say("LiteRT available: ${PersonMasks.available()} (${System.getProperty("os.arch")})")
            val folder = File(targetContext.filesDir, "person-masks")
            val clip = File(folder, arguments.getString("clip") ?: error("-e clip <name in files/person-masks>"))
            val duration = MediaMetadataRetriever().run {
                try {
                    setDataSource(clip.path)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toDouble() / 1000
                } finally {
                    release()
                }
            }
            val source = SourceMedia(id = "RUNNER", filename = clip.name, duration = duration)
            File(folder, "Cache").deleteRecursively()
            var reported = -1
            val started = SystemClock.elapsedRealtime()
            // With -e warm true the warm-up frames are segmented too, as a filter over time will have them.
            var warmUp = 0
            val watch: ((PersonMaskChunks.Mask, Boolean) -> Unit)? =
                if (arguments.getString("warm") == "true") { _, kept -> if (!kept) warmUp++ } else null
            val analysis = PersonMasks.analyse(targetContext, source, folder, progress = {
                val tenth = (it * 10).toInt()
                if (tenth != reported) { reported = tenth; Log.i(TAG, "progress ${tenth * 10} %") }
            }, frame = watch)
            val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
            if (watch != null) say("warm-up frames segmented and not kept: $warmUp")
            var frames = 0
            var bytes = 0L
            var mean = 0.0
            for (index in analysis.chunks) {
                val file = PersonMaskChunks.file(analysis.directory, index)
                PersonMaskChunks.open(file)!!.use { chunk ->
                    frames += chunk.count
                    bytes += file.length()
                    val middle = chunk.decode(chunk.count / 2)
                    mean = middle.rows.sumOf { (it.toInt() and 255).toLong() } / (255.0 * middle.rows.size)
                    say("chunk %d: %d masks of %d×%d, %d KB (%.1f KB a mask), middle mask %.0f %% person".format(
                        index, chunk.count, chunk.width, chunk.height, file.length() / 1024, file.length() / 1024.0 / chunk.count, mean * 100))
                }
            }
            say("%s: %.1f s, %d masks in %.1f s, %.1f frames a second, %d KB on disk (%.1f KB a second of video)".format(
                clip.name, duration, frames, seconds, frames / seconds, bytes / 1024, bytes / 1024.0 / duration))
            if (!analysis.complete || analysis.chunks != PersonMaskChunks.chunks(duration).toList()) { failed++; say("not every chunk was made") }

            val again = SystemClock.elapsedRealtime()
            val cached = PersonMasks.analyse(targetContext, source, folder)
            say("second analysis: ${cached.chunks.size} chunks found in ${SystemClock.elapsedRealtime() - again} ms")
            if (cached.chunks != analysis.chunks) failed++
            var missing = 0
            PersonMasks.reader(targetContext, source, folder).use { reader ->
                var time = 0.0
                while (time < duration) {
                    if (reader.mask(time) == null) missing++
                    time += 0.1
                }
            }
            say("reader: $missing of ${(duration / 0.1).toInt()} times without a mask")
            if (missing > 0) failed++
            if (arguments.getString("keep") != "true") File(folder, "Cache").deleteRecursively()
        } catch (problem: Throwable) {
            failed++
            say(Log.getStackTraceString(problem))
        }
        report.append(if (failed == 0) "\nOK" else "\nFAILURES!!! $failed")
        finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString(Instrumentation.REPORT_KEY_STREAMRESULT, report.toString() + "\n")
        })
    }

    private companion object {
        const val TAG = "PersonMaskRunner"
    }
}
