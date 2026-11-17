package app.takes.effects

import android.app.Activity
import android.app.Instrumentation
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import app.takes.Exporter
import app.takes.HdrMovie
import app.takes.ProjectStore
import app.takes.core.ColorLook
import app.takes.core.ColorSettings
import app.takes.core.EditState
import app.takes.core.Project
import app.takes.core.TextOverlay
import app.takes.core.TimelineEditing
import app.takes.core.TransitionSettings
import app.takes.core.TransitionStyle
import java.io.File

/**
 * A whole export, as the app makes it ([Exporter]: the sections, the sound, the join, the checks): a project of one
 * recording, either as it was imported or cut in two pieces joined by a dissolve with a title on the first and a look
 * on the second. The movie's path and the time the export took are reported; the project is deleted afterwards
 * unless `-e keep true`.
 *
 *     adb shell am instrument -w -e clip hlg.mp4 -e case joined app.takes.dev.test/app.takes.effects.ProjectExportRunner
 *
 * The clip is read from /data/local/tmp. `-e case plain` exports the recording as imported; `-e runs <n>` exports it
 * n times (each from the start), for the times; `-e fallback true` takes the way of a device that writes no HDR
 * movie ([HdrMovie.refuse]), which must then export in SDR; `-e case dolly` exports its first four seconds under a
 * Dolly Zoom, which reads the person masks. The whole recording can also be exported with one thing on it, to see
 * what each does to an HDR movie: `-e case title` (a title), `look` (the Warm look), `night` (a Night fix in Fast),
 * `nightMedium` (in Medium, whose frames are fused) or `effect` (`-e effect <VideoEffectID>`, Halation by default);
 * `-e case sdrJoin -e sdr <name>` imports a second recording from /data/local/tmp after it and joins the two with a
 * dissolve; `-e case backgroundSound -e background <name>` puts a Chroma Key on it whose Video background is that
 * movie from /data/local/tmp, with its Background sound on.
 */
class ProjectExportRunner : Instrumentation() {
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
            HdrMovie.refuse(arguments.getString("fallback") == "true")
            val name = arguments.getString("clip") ?: error("-e clip <name>")
            val clip = File("/data/local/tmp", name)
            store = main { ProjectStore(targetContext) }
            val made = main { store.create() } ?: error("No project")
            main { store.importVideos(made.id, listOf(Uri.fromFile(clip))) }
            waitFor(60, "the import") { store.importing == null && store.projects.first { it.id == made.id }.edit.clips.isNotEmpty() }
            val imported = main { store.projects.first { it.id == made.id } }
            project = imported
            if (arguments.getString("case") == "dolly") {
                // The first four seconds under a Dolly Zoom that stretches by a half over three: its person masks
                // analysed by the export, the background magnified about the middle. `-e effect autoBackground` puts
                // an Auto Background there instead, `-e effect none` nothing.
                val whole = imported.edit.clips.single()
                val dolly = app.takes.core.VideoEffectSettings(app.takes.core.VideoEffectID.DollyZoom, start = whole.start, end = whole.start + 4.0)
                    .with("strength", app.takes.core.EffectValue.Number(.5)).with("duration", app.takes.core.EffectValue.Number(3.0))
                val effects = when (arguments.getString("effect")) {
                    "none" -> null
                    "autoBackground" -> listOf(app.takes.core.VideoEffectSettings(app.takes.core.VideoEffectID.AutoBackground, start = whole.start, end = whole.start + 4.0))
                    else -> listOf(dolly)
                }
                main { store.commit(made.id, EditState(listOf(whole.copy(end = minOf(whole.end, whole.start + 4.0), videoEffects = effects)))) }
            } else if (arguments.getString("case") in setOf("title", "look", "night", "nightMedium", "effect")) {
                val whole = imported.edit.clips.single()
                val clip = when (arguments.getString("case")) {
                    "title" -> whole.copy(texts = listOf(TextOverlay(id = "T", text = "HDR", y = 0.3, size = 0.2, start = whole.start, end = whole.end)))
                    "look" -> whole.copy(color = ColorSettings(look = ColorLook.Warm))
                    "night" -> whole.copy(videoEffects = listOf(app.takes.core.VideoEffectSettings(app.takes.core.VideoEffectID.NightFix, start = whole.start, end = whole.end)))
                    "nightMedium" -> whole.copy(videoEffects = listOf(app.takes.core.VideoEffectSettings(app.takes.core.VideoEffectID.NightFix, start = whole.start, end = whole.end).with("mode", app.takes.core.EffectValue.Choice("Medium"))))
                    else -> whole.copy(videoEffects = listOf(app.takes.core.VideoEffectSettings(app.takes.core.VideoEffectID.valueOf(arguments.getString("effect") ?: "Halation"), start = whole.start, end = whole.end)))
                }
                main { store.commit(made.id, EditState(listOf(clip))) }
            } else if (arguments.getString("case") == "backgroundSound") {
                // A Chroma Key over the whole recording with a Video background, the movie given with `-e background
                // <name>`, and its Background sound on: the movie loops under the subject and its sound is mixed in.
                val movie = File("/data/local/tmp", arguments.getString("background") ?: error("-e background <name>"))
                movie.copyTo(File(store.disk.folder(made.id), "background.mp4"), overwrite = true)
                val whole = imported.edit.clips.single()
                val key = app.takes.core.VideoEffectSettings(app.takes.core.VideoEffectID.ChromaKey, start = whole.start, end = whole.end)
                    .with("background", app.takes.core.EffectValue.Choice("Video")).with("media", app.takes.core.EffectValue.Media("background.mp4"))
                    .with("backgroundAudio", app.takes.core.EffectValue.Toggle(true))
                main { store.commit(made.id, EditState(listOf(whole.copy(videoEffects = listOf(key))))) }
            } else if (arguments.getString("case") == "sdrJoin") {
                // The recording, then a dissolve into an SDR recording given with `-e sdr <name>`.
                main { store.importVideos(made.id, listOf(Uri.fromFile(File("/data/local/tmp", arguments.getString("sdr") ?: error("-e sdr <name>"))))) }
                waitFor(60, "the second import") { store.importing == null && store.projects.first { it.id == made.id }.edit.clips.size == 2 }
                val two = main { store.projects.first { it.id == made.id } }.edit
                val joined = TimelineEditing.setJoin(two, 1, TransitionSettings(style = TransitionStyle.Dissolve, duration = 1.0))!!
                main { store.commit(made.id, joined) }
            } else if (arguments.getString("case") != "plain") {
                val whole = imported.edit.clips.single()
                val first = whole.copy(id = "A", start = 0.0, end = minOf(3.0, whole.end), texts = listOf(TextOverlay(id = "T", text = "HDR", y = 0.3, size = 0.2, start = 0.0, end = 3.0)))
                val second = whole.copy(id = "B", start = minOf(2.0, whole.end / 2), color = ColorSettings(look = ColorLook.Warm))
                val joined = TimelineEditing.setJoin(EditState(listOf(first, second)), 1, TransitionSettings(style = TransitionStyle.Dissolve, duration = 1.0))!!
                main { store.commit(made.id, joined) }
            }
            val exporter = main { Exporter(targetContext, store) }
            val runs = arguments.getString("runs")?.toInt() ?: 1
            repeat(runs) { run ->
                val current = main { store.projects.first { it.id == made.id } }
                project = current
                // Each run from the start: the previous movie's receipt would otherwise be offered.
                File(store.disk.folder(current.id), "Exports").deleteRecursively()
                val started = SystemClock.elapsedRealtime()
                main { exporter.start(current) }
                waitFor(600, "the export") { exporter.status == null }
                val took = SystemClock.elapsedRealtime() - started
                val movie = main { exporter.result } ?: error("No movie: ${main { store.error }}")
                say("ok    run ${run + 1}: %.1f s, %s; %s".format(took / 1000.0, movie.path, format(movie)))
            }
        } catch (problem: Throwable) {
            failed++
            say("FAIL  " + Log.getStackTraceString(problem))
        } finally {
            if (arguments.getString("keep") != "true") project?.let { done -> store?.let { main { it.delete(done) } } }
        }
        report.append(if (failed == 0) "\nOK" else "\nFAILURES!!! $failed")
        finish(Activity.RESULT_OK, Bundle().apply { putString(REPORT_KEY_STREAMRESULT, report.toString()) })
    }

    /** The picture's format of [movie] as its container states it. */
    private fun format(movie: File): String {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(movie.path)
            val format = (0 until extractor.trackCount).map(extractor::getTrackFormat).first { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            fun number(key: String) = if (format.containsKey(key)) format.getInteger(key).toString() else "-"
            "${format.getString(MediaFormat.KEY_MIME)} profile=${number(MediaFormat.KEY_PROFILE)} transfer=${number(MediaFormat.KEY_COLOR_TRANSFER)}"
        } finally {
            extractor.release()
        }
    }

    private companion object {
        const val TAG = "ProjectExport"
    }
}
