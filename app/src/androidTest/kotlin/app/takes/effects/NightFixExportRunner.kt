@file:OptIn(UnstableApi::class)

package app.takes.effects

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import app.takes.ExportMovie
import app.takes.blackStill
import app.takes.core.EditState
import app.takes.core.EffectValue
import app.takes.core.ExportSections
import app.takes.core.TimelineClip
import app.takes.core.TrackRenderPlan
import app.takes.core.VideoEffectID
import app.takes.core.VideoEffectSettings
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * Exports of a recording with Night fix in each mode through the real export ([ExportMovie]), beside the same clip
 * with no effect: each must finish, and the time each takes is reported, a frame and over the plain export. A few
 * frames of each movie are saved side by side as `night-<second>.png` in the app's files/night-export, for a look.
 *
 *     adb shell am instrument -w -e clip dark.mp4 -e from 0 -e to 4 app.takes.dev.test/app.takes.effects.NightFixExportRunner
 *
 * The clip is taken from the app's files/night-export, else from /data/local/tmp. `-e modes Fast,Medium` exports
 * those modes only; `-e keep true` keeps the movies beside the sheets.
 */
class NightFixExportRunner : Instrumentation() {
    private lateinit var arguments: Bundle
    private val thread = HandlerThread("night-export").apply { start() }

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
            val folder = File(targetContext.filesDir, "night-export").apply { mkdirs() }
            val name = arguments.getString("clip") ?: error("-e clip <name>")
            val clip = File(folder, name)
            if (!clip.isFile) File("/data/local/tmp", name).takeIf { it.canRead() }?.copyTo(clip) ?: error("No $name")
            val from = arguments.getString("from")?.toDouble() ?: 0.0
            val to = arguments.getString("to")?.toDouble() ?: 4.0
            val (size, rate) = MediaMetadataRetriever().run {
                try {
                    setDataSource(clip.path)
                    val w = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
                    val h = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
                    val turned = (extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0) % 180 != 0
                    val frames = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toDouble()
                    val seconds = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toDouble() / 1000
                    (if (turned) h to w else w to h) to (frames?.div(seconds) ?: 30.0)
                } finally {
                    release()
                }
            }
            val fps = Math.round(rate).toInt()
            say("${clip.name}: ${size.first}×${size.second} at %.2f a second, %.1f–%.1f s".format(rate, from, to))
            val frames = (to - from) * rate
            val looks = HashMap<String, List<Bitmap>>()
            var plain: Long? = null
            for ((label, effect) in cases()) {
                try {
                    val state = EditState(listOf(TimelineClip(id = "C", sourceID = "S", start = from, end = to, videoEffects = effect?.let { listOf(it.copy(start = from, end = to)) })))
                    val movie = File(folder, "$label.mp4")
                    val took = export(state, clip, folder, size, rate, fps, movie)
                    if (effect == null) plain = took
                    val over = plain?.takeIf { effect != null }?.let { ", %.1f ms a frame over the plain export".format((took - it) / frames) } ?: ""
                    say("ok    $label: %.1f s, %.1f ms a frame$over".format(took / 1000.0, took / frames))
                    looks[label] = SECONDS.filter { it < to - from }.map { second -> frame(movie, second) }
                    if (arguments.getString("keep") != "true") movie.delete()
                } catch (problem: Throwable) {
                    failed++
                    say("FAIL  $label\n" + Log.getStackTraceString(problem))
                }
            }
            for ((index, second) in SECONDS.withIndex()) {
                val row = cases().mapNotNull { looks[it.first]?.getOrNull(index) }
                if (row.isEmpty()) continue
                val sheet = Bitmap.createBitmap(row.sumOf { it.width }, row.maxOf { it.height }, Bitmap.Config.ARGB_8888)
                var x = 0f
                Canvas(sheet).apply { for (picture in row) { drawBitmap(picture, x, 0f, null); x += picture.width } }
                File(folder, "night-$second.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            say("sheets in ${folder.path}: ${cases().joinToString(", ") { it.first }}")
        } catch (problem: Throwable) {
            failed++
            say("FAIL  the export could not be set up\n" + Log.getStackTraceString(problem))
        }
        report.append(if (failed == 0) "\nOK" else "\nFAILURES!!! $failed")
        finish(Activity.RESULT_OK, Bundle().apply { putString(REPORT_KEY_STREAMRESULT, report.toString()) })
    }

    /** The recipes, by label: none, then Night fix at its defaults in each mode asked for. */
    private fun cases(): List<Pair<String, VideoEffectSettings?>> {
        val modes = arguments.getString("modes")?.split(',') ?: listOf("Fast", "Medium", "Precise")
        return listOf("plain" to null) + modes.map { mode ->
            mode.lowercase() to VideoEffectSettings(VideoEffectID.NightFix).with("mode", EffectValue.Choice(mode))
        }
    }

    /** Exports [state] into [movie] (one section), in milliseconds. */
    private fun export(state: EditState, source: File, folder: File, size: Pair<Int, Int>, rate: Double, fps: Int, movie: File): Long {
        val plan = TrackRenderPlan.of(state)
        val render = ExportMovie(
            targetContext, plan, fps, mapOf("S" to source), size,
            sizes = mapOf("S" to size), rates = mapOf("S" to rate), black = blackStill(targetContext), luts = folder,
        )
        val section = ExportSections.sections(plan.duration).single()
        val started = SystemClock.elapsedRealtime()
        val done = CountDownLatch(1)
        var failure: ExportException? = null
        android.os.Handler(thread.looper).post {
            Transformer.Builder(targetContext).setLooper(thread.looper).addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) = done.countDown()
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    failure = exportException
                    done.countDown()
                }
            }).build().start(render.picture(section), movie.path)
        }
        done.await()
        failure?.let { throw it }
        return SystemClock.elapsedRealtime() - started
    }

    /** The frame of [movie] at [second], at most 540 pixels high. */
    private fun frame(movie: File, second: Double): Bitmap = MediaMetadataRetriever().run {
        try {
            setDataSource(movie.path)
            val picture = getFrameAtTime((second * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)!!
            val scale = minOf(1.0, 540.0 / picture.height)
            Bitmap.createScaledBitmap(picture, (picture.width * scale).toInt(), (picture.height * scale).toInt(), true)
        } finally {
            release()
        }
    }

    private companion object {
        const val TAG = "NightFixExport"
        val SECONDS = listOf(0.5, 1.5, 3.0)
    }
}
