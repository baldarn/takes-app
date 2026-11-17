package app.takes.effects

import android.app.Activity
import android.app.Instrumentation
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import app.takes.Exporter
import app.takes.ProjectStore
import app.takes.Stabilizer
import app.takes.core.Project
import app.takes.core.StabilizationLevel
import app.takes.core.StabilizationPath
import app.takes.core.TimelineEditing
import java.io.File

/**
 * Stabilisation of a recording, as the app makes it: the camera path measured by the original's estimator
 * ([Stabilizer]), its cache, the plan of each level, and the export of the clip at one level. The time the
 * measurement took, how much of the clip was tracked, each level's crop and the exported movie's path are reported;
 * the movie's shake is measured on a computer from it (docs/STABILIZATION-PROBE.md). The project is deleted
 * afterwards unless `-e keep true`.
 *
 *     adb shell am instrument -w -e clip shake.mp4 -e level standard app.takes.dev.test/app.takes.effects.StabilizationRunner
 *
 * The clip is read from /data/local/tmp. `-e split true` cuts the clip in two first: both pieces must use the
 * one path measured for the whole take.
 */
class StabilizationRunner : Instrumentation() {
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
            val name = arguments.getString("clip") ?: error("-e clip <name>")
            val level = StabilizationLevel.entries.first { it.id == (arguments.getString("level") ?: "standard") }
            store = main { ProjectStore(targetContext) }
            val made = main { store.create() } ?: error("No project")
            main { store.importVideos(made.id, listOf(Uri.fromFile(File("/data/local/tmp", name)))) }
            waitFor(120, "the import") { store.importing == null && store.projects.first { it.id == made.id }.edit.clips.isNotEmpty() }
            val imported = main { store.projects.first { it.id == made.id } }
            project = imported
            val folder = store.disk.folder(imported.id)
            val source = imported.sources.single()
            val clip = imported.edit.clips.single()
            // Measured once, then read back from the cache.
            var started = SystemClock.elapsedRealtime()
            val analysis = Stabilizer.analysis(source, folder, clip.start, clip.end)!!
            val measured = SystemClock.elapsedRealtime() - started
            say("ok    measured %.2f s of %d × %d in %.1f s: %d frames, %.1f%% tracked, %d segments".format(
                analysis.duration, analysis.width, analysis.height, measured / 1000.0, analysis.samples.size,
                analysis.trackingFraction * 100, analysis.samples.last().segment + 1,
            ))
            check(Stabilizer.cacheFile(folder, File(folder, source.filename), clip.start, clip.end).isFile) { "not cached" }
            for (each in StabilizationLevel.entries) {
                val plan = StabilizationPath.make(analysis, each)
                say("ok    ${each.id}: crop zoom %.3f×, %d%%, limited %s".format(plan.zoom, plan.cropPercent, plan.strengthLimited))
            }
            var edit = TimelineEditing.setStabilization(imported.edit, 0, level)!!
            if (arguments.getString("split") == "true") edit = TimelineEditing.split(edit, clip.duration / 2)!!
            main { store.commit(made.id, edit) }
            started = SystemClock.elapsedRealtime()
            val pieces = main { store.projects.first { it.id == made.id } }.edit.clips.map { Stabilizer.plan(source, it, folder)!! }
            say("ok    plans of ${pieces.size} piece(s) in %d ms, crop zoom %s".format(SystemClock.elapsedRealtime() - started, pieces.map { "%.3f".format(it.zoom) }))
            check(pieces.all { it.zoom == pieces[0].zoom && it.samples.size == analysis.samples.size }) { "the pieces were measured apart" }
            check(Stabilizer.directory(folder).listFiles { it: File -> it.extension == "json" }!!.size == 1) { "more than one path kept" }
            val exporter = main { Exporter(targetContext, store) }
            val current = main { store.projects.first { it.id == made.id } }
            project = current
            started = SystemClock.elapsedRealtime()
            main { exporter.start(current) }
            waitFor(900, "the export") { exporter.status == null }
            val movie = main { exporter.result } ?: error("No movie: ${main { store.error }}")
            say("ok    exported at ${level.id} in %.1f s: %s".format((SystemClock.elapsedRealtime() - started) / 1000.0, movie.path))
        } catch (problem: Throwable) {
            failed++
            say("FAIL  " + Log.getStackTraceString(problem))
        } finally {
            if (arguments.getString("keep") != "true") project?.let { done -> store?.let { main { it.delete(done) } } }
        }
        report.append(if (failed == 0) "\nOK" else "\nFAILURES!!! $failed")
        finish(Activity.RESULT_OK, Bundle().apply { putString(REPORT_KEY_STREAMRESULT, report.toString()) })
    }

    private companion object {
        const val TAG = "StabilizationRun"
    }
}
