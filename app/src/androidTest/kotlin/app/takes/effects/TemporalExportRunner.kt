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
import app.takes.core.EffectRGB
import app.takes.core.EffectValue
import app.takes.core.ExportSections
import app.takes.core.TimelineClip
import app.takes.core.TrackRenderPlan
import app.takes.core.VideoEffectID
import app.takes.core.VideoEffectSettings
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * Exports of a real recording with the temporal matte (algorithm 2) of Color Isolation and Chroma Key, through the
 * real export ([ExportMovie]), against the same recipes with the single-frame matte (algorithm 1): each must finish,
 * and the time each takes is reported. A few frames of each movie are saved side by side as `temporal-<second>.png`
 * in the app's files/temporal-export, for a look.
 *
 *     adb shell am instrument -w -e clip front.mp4 -e from 2 -e to 7 app.takes.dev.test/app.takes.effects.TemporalExportRunner
 *
 * The clip is taken from the app's files/temporal-export, else from /data/local/tmp. `-e fps <rate>` exports at
 * that frame rate (30 by default); `-e plain true` exports the clip with no effect, to read the movie's frame times;
 * `-e keep true` keeps the movies beside the sheets.
 */
class TemporalExportRunner : Instrumentation() {
    private lateinit var arguments: Bundle
    private val fps get() = arguments.getString("fps")?.toInt() ?: 30
    private val thread = HandlerThread("temporal-export").apply { start() }

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
            val folder = File(targetContext.filesDir, "temporal-export").apply { mkdirs() }
            val name = arguments.getString("clip") ?: error("-e clip <name>")
            val clip = File(folder, name)
            if (!clip.isFile) File("/data/local/tmp", name).takeIf { it.canRead() }?.copyTo(clip) ?: error("No $name")
            val from = arguments.getString("from")?.toDouble() ?: 2.0
            val to = arguments.getString("to")?.toDouble() ?: 7.0
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
            say("${clip.name}: ${size.first}×${size.second} at %.2f a second, %.1f–%.1f s".format(rate, from, to))
            val looks = HashMap<String, List<Bitmap>>()
            for ((label, effect) in cases()) {
                try {
                    val state = EditState(listOf(TimelineClip(id = "C", sourceID = "S", start = from, end = to, videoEffects = effect?.let { listOf(it.copy(start = from, end = to)) })))
                    val movie = File(folder, "$label.mp4")
                    val took = export(state, clip, folder, size, rate, movie)
                    val frames = (to - from) * rate
                    say("ok    $label: %.1f s, %.1f ms a frame".format(took / 1000.0, took / frames))
                    looks[label] = SECONDS.map { second -> frame(movie, second) }
                    if (arguments.getString("keep") != "true") movie.delete()
                } catch (problem: Throwable) {
                    failed++
                    say("FAIL  $label\n" + Log.getStackTraceString(problem))
                }
            }
            for ((index, second) in SECONDS.withIndex()) {
                val row = cases().mapNotNull { looks[it.first]?.get(index) }
                if (row.isEmpty()) continue
                val sheet = Bitmap.createBitmap(row.sumOf { it.width }, row.maxOf { it.height }, Bitmap.Config.ARGB_8888)
                var x = 0f
                Canvas(sheet).apply { for (picture in row) { drawBitmap(picture, x, 0f, null); x += picture.width } }
                File(folder, "temporal-$second.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            say("sheets in ${folder.path}: ${cases().joinToString(", ") { it.first }}")
        } catch (problem: Throwable) {
            failed++
            say("FAIL  the export could not be set up\n" + Log.getStackTraceString(problem))
        }
        report.append(if (failed == 0) "\nOK" else "\nFAILURES!!! $failed")
        finish(Activity.RESULT_OK, Bundle().apply { putString(REPORT_KEY_STREAMRESULT, report.toString()) })
    }

    private fun effect(kind: VideoEffectID, algorithm: Int, values: Map<String, Any>): VideoEffectSettings {
        var made = VideoEffectSettings(kind).copy(algorithm = algorithm)
        for ((id, value) in values) made = made.with(id, when (value) {
            is Boolean -> EffectValue.Toggle(value)
            is String -> EffectValue.Choice(value)
            is EffectRGB -> EffectValue.Color(value)
            else -> EffectValue.Number((value as Number).toDouble())
        })
        return made
    }

    /** The recipes, by label: Color Isolation of the skin's hue and Chroma Key of the wall, with each matte. */
    private fun cases(): List<Pair<String, VideoEffectSettings?>> {
        if (arguments.getString("plain") == "true") return listOf("plain-$fps" to null)
        val isolation = mapOf("color" to EffectRGB.hex(arguments.getString("hue") ?: "#C08060"), "range" to 18.0)
        val key = mapOf("autoScreen" to false, "screenColor" to EffectRGB.hex(arguments.getString("screen") ?: "#B0B0A8"), "view" to "Matte", "softness" to 1.0)
        return listOf(
            "isolation-1" to effect(VideoEffectID.ColorIsolation, 1, isolation),
            "isolation-2" to effect(VideoEffectID.ColorIsolation, 2, isolation),
            "key-1" to effect(VideoEffectID.ChromaKey, 1, key),
            "key-2" to effect(VideoEffectID.ChromaKey, 2, key),
        )
    }

    /** Exports [state] into [movie] (one section), in milliseconds. */
    private fun export(state: EditState, source: File, folder: File, size: Pair<Int, Int>, rate: Double, movie: File): Long {
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
        const val TAG = "TemporalExport"
        val SECONDS = listOf(1.0, 2.5, 4.0)
    }
}
