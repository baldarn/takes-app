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
 * `-e warm true` segments each chunk's two-second warm-up too. `-e look 2,5,8` draws the export's chain with the
 * effects that read the person on the frames at those seconds (under 10), with these masks, and saves each
 * as `look-<second>.png` beside the clip: the frame, Auto Background (Blur original, then Studio), its matte,
 * Color Isolation's subject, Quantization's face detail, Dolly Zoom stretched by a half.
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
            // A build that is not debuggable has no run-as: the clip is taken from /data/local/tmp then.
            if (!clip.isFile) File("/data/local/tmp", clip.name).takeIf { it.canRead() }?.copyTo(clip.also { folder.mkdirs() })
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
            arguments.getString("look")?.split(",")?.map { it.trim().toDouble() }?.let { seconds -> look(clip, folder, source, seconds, ::say) }
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

    /** The effects that read the person on real frames, through the export's chain, with the masks just made. */
    private fun look(clip: File, folder: File, source: SourceMedia, seconds: List<Double>, say: (String) -> Unit) {
        val retriever = MediaMetadataRetriever().apply { setDataSource(clip.path) }
        val bench = app.takes.pixels.Bench(targetContext)
        fun effect(kind: app.takes.core.VideoEffectID, vararg values: Pair<String, app.takes.core.EffectValue>): app.takes.core.VideoEffectSettings =
            values.fold(app.takes.core.VideoEffectSettings(kind, 0.0, 10.0)) { e, (id, v) -> e.with(id, v) }
        val choice = { v: String -> app.takes.core.EffectValue.Choice(v) }
        val looks = listOf(
            effect(app.takes.core.VideoEffectID.AutoBackground),
            effect(app.takes.core.VideoEffectID.AutoBackground, "background" to choice("Studio")),
            effect(app.takes.core.VideoEffectID.AutoBackground, "view" to choice("Matte")),
            effect(app.takes.core.VideoEffectID.ColorIsolation, "mode" to choice("The subject")),
            effect(app.takes.core.VideoEffectID.Quantization, "colors" to app.takes.core.EffectValue.Number(4.0), "palette" to choice("Pop 1")),
            // Stretched by a half, at its end from 2.5 s.
            effect(app.takes.core.VideoEffectID.DollyZoom, "strength" to app.takes.core.EffectValue.Number(.5)),
        )
        try {
            for (second in seconds) {
                val frame = retriever.getScaledFrameAtTime((second * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST, 540, 960)!!
                val width = frame.width
                val height = frame.height
                val input = app.takes.pixels.Picture(width, height) { x, y -> frame.getPixel(x, y) }
                val sheet = android.graphics.Bitmap.createBitmap(width * (looks.size + 1), height, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(sheet)
                canvas.drawBitmap(frame, 0f, 0f, null)
                looks.forEachIndexed { i, settings ->
                    val clipOf = app.takes.core.TimelineClip(id = "C", sourceID = "S", start = 0.0, end = 10.0, videoEffects = listOf(settings))
                    val assets = app.takes.SourceAssets(clip, folder, clipOf, wait = true, context = targetContext, media = source)
                    val stage = app.takes.pixels.EffectStage(bench, listOf(settings), width, height, assets)
                    val started = SystemClock.elapsedRealtime()
                    val out = stage.frame(input, (second * 1e6).toLong())
                    val took = SystemClock.elapsedRealtime() - started
                    stage.release()
                    canvas.drawBitmap(android.graphics.Bitmap.createBitmap(IntArray(out.pixels.size) { out.pixels[it] or 0xff000000.toInt() }, width, height, android.graphics.Bitmap.Config.ARGB_8888), (width * (i + 1)).toFloat(), 0f, null)
                    say("look %.1f s %s: %d ms".format(second, settings.kind, took))
                }
                File(folder, "look-${second}.png").outputStream().use { sheet.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        } finally {
            bench.release()
            retriever.release()
        }
    }

    private companion object {
        const val TAG = "PersonMaskRunner"
    }
}
