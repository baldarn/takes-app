package app.takes.sound

import android.app.Activity
import android.app.Instrumentation
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import app.takes.Exporter
import app.takes.ProjectStore
import app.takes.core.EditState
import app.takes.core.Project
import app.takes.core.TimelineEditing
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * A whole export, as the app makes it ([Exporter]), of a sound file put under a recording and cut so that a piece
 * of it begins partway into the file: the music of [DecodedSourceRunner] (a 44.1 kHz mono AAC file of a tone with
 * a click every half second) imported at second 0 as Add audio does, then split at 7.033 s (`-e case split`) or
 * trimmed to begin at 5.833 s (`-e case trim`), or left whole (`-e case whole`). The movie's sound is decoded and
 * must be free of silence from the sound's first second to its last, and its clicks must lie on the half seconds.
 *
 *     adb shell am instrument -w -e clip clip.mp4 -e music music.m4a -e case split app.takes.dev.test/app.takes.sound.SoundItemExportRunner
 *
 * Both files are read from /data/local/tmp; the recording must last as long as the music. The project is deleted
 * afterwards unless `-e keep true`.
 */
class SoundItemExportRunner : Instrumentation() {
    private lateinit var arguments: Bundle

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle()
        start()
    }

    private fun <T> main(block: () -> T): T {
        var value: Any? = null
        runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun waitFor(seconds: Int, what: String, done: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + seconds * 1000L
        while (!main(done)) {
            check(SystemClock.elapsedRealtime() < until) { "Timed out waiting for $what" }
            Thread.sleep(100)
        }
    }

    override fun onStart() {
        super.onStart()
        val report = StringBuilder()
        var failed = 0
        fun say(line: String) {
            Log.i(TAG, line)
            report.append(line).append('\n')
        }
        var store: ProjectStore? = null
        var project: Project? = null
        try {
            val clip = File("/data/local/tmp", arguments.getString("clip") ?: error("-e clip <name>"))
            val music = File("/data/local/tmp", arguments.getString("music") ?: error("-e music <name>"))
            val case = arguments.getString("case") ?: "split"
            store = main { ProjectStore(targetContext) }
            val made = main { store.create() } ?: error("No project")
            project = made
            main { store.importVideos(made.id, listOf(Uri.fromFile(clip))) }
            waitFor(60, "the import") { store.importing == null && store.projects.first { it.id == made.id }.edit.clips.isNotEmpty() }
            main { store.importAudio(made.id, Uri.fromFile(music), 0.0) }
            waitFor(60, "the sound") { store.importing == null && store.projects.first { it.id == made.id }.edit.audio.orEmpty().isNotEmpty() }
            val edit = main { store.projects.first { it.id == made.id }.edit }
            val sound = edit.audio!!.single()
            val cut: EditState = when (case) {
                "split" -> TimelineEditing.split(edit, 7.033) ?: error("No split")
                "trim" -> TimelineEditing.trimAudio(edit, sound.id, 5.833, leading = true) ?: error("No trim")
                else -> edit
            }
            main { store.commit(made.id, cut) }
            say("sound: " + cut.audio.orEmpty().joinToString { "%.3f–%.3f from %.3f".format(it.start, it.end, it.sourceStart) })
            val exporter = main { Exporter(targetContext, store) }
            val current = main { store.projects.first { it.id == made.id } }
            project = current
            main { exporter.start(current) }
            waitFor(600, "the export") { exporter.status == null }
            val movie = main { exporter.result } ?: error("No movie: ${main { store.error }}")
            val start = cut.audio.orEmpty().minOf { it.start }
            val end = cut.audio.orEmpty().maxOf { it.end }
            val (line, ok) = measure(movie, start, end)
            if (!ok) failed++
            say((if (ok) "ok    " else "FAIL  ") + "$case: $line; ${movie.path}")
        } catch (problem: Throwable) {
            failed++
            say("FAIL  " + Log.getStackTraceString(problem))
        } finally {
            if (arguments.getString("keep") != "true") project?.let { done -> store?.let { main { it.delete(done) } } }
        }
        report.append(if (failed == 0) "\nOK" else "\nFAILURES!!! $failed")
        finish(Activity.RESULT_OK, Bundle().apply { putString(REPORT_KEY_STREAMRESULT, report.toString()) })
    }

    /** The movie's sound between seconds [start] and [end]: its silences of more than 5 ms and where its clicks fall. */
    private fun measure(movie: File, start: Double, end: Double): Pair<String, Boolean> {
        val (samples, rate) = decode(movie)
        val first = (start * rate).toInt()
        val last = minOf(samples.size, (end * rate).toInt())
        // Clicks: where each falls from its half second. The sound of every export lies a few milliseconds late
        // in the movie, the same throughout (its encoding, after the mix), so that delay is measured and allowed.
        val late = mutableListOf<Double>()
        var frame = first
        while (frame < last) {
            if (abs(samples[frame]) > 0.5f) {
                var best = frame
                val stop = minOf(last, frame + rate / 100)
                for (f in frame until stop) if (abs(samples[f]) > abs(samples[best])) best = f
                val at = best.toDouble() / rate
                late += (at - Math.round(at * 2) / 2.0) * 1000
                frame = stop
            } else frame++
        }
        val delay = late.sorted().getOrElse(late.size / 2) { 0.0 }
        // Silence: runs of more than 5 ms with nothing in them, from where the sound is heard.
        val gaps = mutableListOf<String>()
        var quiet = 0
        for (at in first + ((delay + 1) * rate / 1000).toInt().coerceAtLeast(0)..last) {
            if (at < last && abs(samples[at]) < 1e-3f) quiet++
            else {
                if (quiet > rate / 200) gaps += "%.3f–%.3f".format((at - quiet).toDouble() / rate, at.toDouble() / rate)
                quiet = 0
            }
        }
        val spread = (late.maxOrNull() ?: 0.0) - (late.minOrNull() ?: 0.0)
        val ok = gaps.isEmpty() && late.size >= ((end - start) * 2).toInt() - 1 && spread < 1.0 && delay in -1.0..25.0
        return "%d Hz; silence %s; %d clicks, %.1f ms late, %.1f ms apart".format(rate, gaps.ifEmpty { listOf("none") }, late.size, delay, spread) to ok
    }

    /** The first channel of [movie]'s sound, decoded, with its rate; frame 0 at second 0 as the decoder times it. */
    private fun decode(movie: File): Pair<FloatArray, Int> {
        val extractor = MediaExtractor()
        extractor.setDataSource(movie.path)
        val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
        val format = extractor.getTrackFormat(track)
        extractor.selectTrack(track)
        val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(format, null, null, 0)
        decoder.start()
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var samples = FloatArray(rate * 30)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val input = decoder.dequeueInputBuffer(10_000)
                    if (input >= 0) {
                        val read = extractor.readSampleData(decoder.getInputBuffer(input)!!, 0)
                        if (read < 0) { decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { decoder.queueInputBuffer(input, 0, read, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val output = decoder.dequeueOutputBuffer(info, 10_000)
                if (output >= 0) {
                    if (info.size > 0) {
                        val shorts = decoder.getOutputBuffer(output)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val frames = info.size / 2 / channels
                        val at = Math.round(info.presentationTimeUs / 1e6 * rate).toInt()
                        if (at + frames > samples.size) samples = samples.copyOf(maxOf(samples.size * 2, at + frames))
                        for (f in 0 until frames) if (at + f >= 0) samples[at + f] = shorts.get(f * channels) / 32768f
                    }
                    decoder.releaseOutputBuffer(output, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            decoder.stop(); decoder.release(); extractor.release()
        }
        return samples to rate
    }

    private companion object {
        const val TAG = "SoundItemExport"
    }
}
