package app.takes.probe.segmentation

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Runs one scenario and writes what it measured to `out/report.txt` in the app's files folder.
 * `in/` holds the models and the footage that `tools/run.sh` pushes:
 *
 *     am instrument -w -e scenario <name> [-e key value ...] app.takes.probe.segmentation/.SegmentationProbe
 *
 * - `info`: the device.
 * - `compare`: `-e models modnet:cpu:1024,selfie:gpu,...` over `-e frames 120` frames of
 *   `-e clip <file in in/>` from `-e from 0`, every frame in turn. Per model: load time and memory,
 *   milliseconds per frame (pre- and post-processing included, decoding not), how much the mask
 *   changes from one frame to the next, how much of it is soft, and its agreement with the first
 *   model listed. Every `-e every 30`th frame is saved as `out/<clip>-<frame>.png`: the frame,
 *   then each model's mask over it, in the order listed.
 * - `heat`: `-e model selfie:gpu` on the first `-e pool 30` frames of `-e clip`, round and round
 *   for `-e seconds 120`, reporting frames a second and the phone's heat every 10 s.
 */
class SegmentationProbe : Instrumentation() {
    init {
        java.util.Locale.setDefault(java.util.Locale.ROOT)
    }

    private lateinit var arguments: Bundle
    private lateinit var input: File
    private lateinit var output: File
    private val lines = mutableListOf<String>()

    override fun onCreate(arguments: Bundle) {
        this.arguments = arguments
        start()
    }

    private fun say(line: String) {
        Log.i("TakesSegmentation", line)
        synchronized(lines) { lines += line }
    }

    private fun text(name: String, fallback: String) = arguments.getString(name) ?: fallback
    private fun number(name: String, fallback: Int) = arguments.getString(name)?.toInt() ?: fallback

    override fun onStart() {
        input = File(targetContext.filesDir, "in")
        output = File(targetContext.filesDir, "out").apply { mkdirs() }
        val scenario = text("scenario", "info")
        say("== $scenario " + arguments.keySet().filter { it != "scenario" }.sorted().joinToString(" ") { "$it=${arguments.getString(it)}" })
        try {
            info()
            when (scenario) {
                "info" -> Unit
                "compare" -> compare()
                "heat" -> heat()
                else -> say("unknown scenario")
            }
        } catch (problem: Throwable) {
            say("FAILED " + Log.getStackTraceString(problem))
        }
        File(output, "report.txt").appendText(lines.joinToString("\n", postfix = "\n"))
        finish(Activity.RESULT_OK, Bundle().apply { putString("stream", lines.joinToString("\n", postfix = "\n")) })
    }

    private fun info() {
        say("device ${Build.MANUFACTURER} ${Build.MODEL} (${Build.HARDWARE}, ${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "?"}), Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}, ABIs ${Build.SUPPORTED_ABIS.joinToString()}")
        say("heat ${thermal()}")
    }

    // --- Heat and memory ---

    private val power by lazy { targetContext.getSystemService(PowerManager::class.java) }

    private fun thermal(): String {
        val status = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus.toString() else "?"
        val headroom = if (Build.VERSION.SDK_INT >= 30) "%.2f".format(power.getThermalHeadroom(10)) else "?"
        val battery = targetContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.let { it / 10.0 }
        return "thermal=$status headroom10s=$headroom battery=${battery}C"
    }

    private fun pssMb(): Int = (Debug.getPss() / 1024).toInt()

    // --- Frames ---

    /** Upright frames of the clip, by index; [MediaMetadataRetriever] turns them by the track's rotation. */
    private class Frames(file: File) : AutoCloseable {
        private val retriever = MediaMetadataRetriever().apply { setDataSource(file.absolutePath) }
        val count = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toInt() ?: 0
        val rate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
        val describe = "${retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)}x${retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)} " +
            "rotation ${retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)} frames $count"
        fun at(index: Int): Bitmap = requireNotNull(retriever.getFrameAtIndex(index, MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 })) { "no frame $index" }
        override fun close() = retriever.release()
    }

    // --- Compare ---

    private class Tally(val segmenter: Segmenter) {
        val times = mutableListOf<Double>()
        var previous: FloatArray? = null
        var change = 0.0 // mean |Δ alpha| from the frame before
        var flips = 0.0 // fraction of pixels crossing 0.5 from the frame before
        var changes = 0
        var soft = 0.0 // fraction of pixels between 0.05 and 0.95
        var person = 0.0 // fraction of pixels above 0.5
        var agreement = 0.0 // intersection over union with the first model at 0.5
        var meanDifference = 0.0 // mean |alpha − first model's|
        var frames = 0
    }

    private fun compare() {
        val clip = File(input, text("clip", "clip.mp4"))
        val total = number("frames", 120)
        val from = number("from", 0)
        val every = number("every", 30)
        val name = clip.nameWithoutExtension
        val models = File(input, "models")
        Frames(clip).use { frames ->
            say("clip ${clip.name} ${frames.describe}")
            val tallies = text("models", "selfie:gpu").split(",").map { spec ->
                val before = pssMb()
                val start = SystemClock.elapsedRealtimeNanos()
                val segmenter = segmenter(spec.trim(), models)
                val loaded = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                val first = frames.at(from)
                val warm = SystemClock.elapsedRealtimeNanos()
                val mask = segmenter.segment(first)
                say("model ${segmenter.label}: load %.0f ms, first frame %.0f ms, mask ${mask.width}x${mask.height}, memory +%d MB${(segmenter as? LiteSegmenter)?.shapes?.let { ", $it" } ?: ""}".format(loaded, (SystemClock.elapsedRealtimeNanos() - warm) / 1e6, pssMb() - before))
                first.recycle()
                Tally(segmenter)
            }
            var decoding = 0.0
            val last = minOf(frames.count, from + total)
            for (index in from until last) {
                val t = SystemClock.elapsedRealtimeNanos()
                val frame = frames.at(index)
                decoding += (SystemClock.elapsedRealtimeNanos() - t) / 1e6
                val gw: Int
                val gh: Int
                if (frame.width >= frame.height) { gw = 320; gh = max(1, (320f * frame.height / frame.width).roundToInt()) } else { gh = 320; gw = max(1, (320f * frame.width / frame.height).roundToInt()) }
                var reference: FloatArray? = null
                val masks = mutableListOf<Mask>()
                for (tally in tallies) {
                    val s = SystemClock.elapsedRealtimeNanos()
                    val mask = tally.segmenter.segment(frame)
                    tally.times += (SystemClock.elapsedRealtimeNanos() - s) / 1e6
                    masks += mask
                    val grid = mask.resized(gw, gh)
                    tally.frames++
                    tally.soft += grid.count { it > .05f && it < .95f }.toDouble() / grid.size
                    tally.person += grid.count { it > .5f }.toDouble() / grid.size
                    tally.previous?.let { before ->
                        tally.change += grid.indices.sumOf { abs(grid[it] - before[it]).toDouble() } / grid.size
                        tally.flips += grid.indices.count { (grid[it] > .5f) != (before[it] > .5f) }.toDouble() / grid.size
                        tally.changes++
                    }
                    tally.previous = grid
                    val first = reference
                    if (first == null) {
                        reference = grid
                    } else {
                        val both = grid.indices.count { grid[it] > .5f && first[it] > .5f }
                        val either = grid.indices.count { grid[it] > .5f || first[it] > .5f }
                        tally.agreement += if (either == 0) 1.0 else both.toDouble() / either
                        tally.meanDifference += grid.indices.sumOf { abs(grid[it] - first[it]).toDouble() } / grid.size
                    }
                }
                if ((index - from) % every == 0) save(File(output, "$name-$index.png"), frame, masks)
                frame.recycle()
            }
            val count = last - from
            say("frames $count, decoding %.1f ms a frame (not counted below)".format(decoding / max(1, count)))
            for ((k, tally) in tallies.withIndex()) {
                val sorted = tally.times.sorted()
                fun quantile(q: Double) = sorted[((sorted.size - 1) * q).roundToInt()]
                val n = max(1, tally.frames)
                say(
                    "${tally.segmenter.label}: mean %.1f ms, p50 %.1f, p90 %.1f, max %.1f; frame to frame |Δ| %.4f, flips %.4f; soft %.3f; person %.3f".format(
                        sorted.average(), quantile(.5), quantile(.9), sorted.last(), tally.change / max(1, tally.changes), tally.flips / max(1, tally.changes), tally.soft / n, tally.person / n,
                    ) + if (k == 0) " (reference)" else "; vs ${tallies[0].segmenter.label}: IoU %.3f, mean |Δ| %.4f".format(tally.agreement / n, tally.meanDifference / n),
                )
            }
            say("memory after ${pssMb()} MB, heat ${thermal()}")
            tallies.forEach { it.segmenter.close() }
        }
    }

    /** The frame, then each mask laid over it (background darkened and tinted blue), side by side, at most 640 px high each. */
    private fun save(file: File, frame: Bitmap, masks: List<Mask>) {
        val scale = minOf(1f, 640f / frame.height, 640f / frame.width)
        val w = max(1, (frame.width * scale).roundToInt())
        val h = max(1, (frame.height * scale).roundToInt())
        val small = Bitmap.createScaledBitmap(frame, w, h, true)
        val sheet = Bitmap.createBitmap(w * (masks.size + 1), h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawBitmap(small, 0f, 0f, null)
        val pixels = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
        for ((k, mask) in masks.withIndex()) {
            val alpha = mask.resized(w, h)
            val over = IntArray(w * h) { i ->
                val a = alpha[i].coerceIn(0f, 1f)
                val p = pixels[i]
                fun mix(c: Int, back: Int) = (c * a + back * (1 - a)).roundToInt()
                Color.rgb(mix(p shr 16 and 255, 10), mix(p shr 8 and 255, 30), mix(p and 255, 90))
            }
            canvas.drawBitmap(Bitmap.createBitmap(over, w, h, Bitmap.Config.ARGB_8888), (w * (k + 1)).toFloat(), 0f, Paint())
        }
        file.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        small.recycle()
        sheet.recycle()
    }

    // --- Heat ---

    private fun heat() {
        val clip = File(input, text("clip", "clip.mp4"))
        val seconds = number("seconds", 120)
        val pool = Frames(clip).use { frames -> say("clip ${clip.name} ${frames.describe}"); List(minOf(number("pool", 30), frames.count)) { frames.at(it) } }
        segmenter(text("model", "selfie:gpu"), File(input, "models")).use { segmenter ->
            say("model ${segmenter.label}, heat ${thermal()}")
            val start = SystemClock.elapsedRealtime()
            var mark = start
            var done = 0
            var index = 0
            while (SystemClock.elapsedRealtime() - start < seconds * 1000L) {
                segmenter.segment(pool[index++ % pool.size])
                done++
                val now = SystemClock.elapsedRealtime()
                if (now - mark >= 10_000) {
                    say("%3d s: %.1f frames a second, %s".format((now - start) / 1000, done * 1000.0 / (now - mark), thermal()))
                    mark = now
                    done = 0
                }
            }
        }
        pool.forEach { it.recycle() }
    }
}
