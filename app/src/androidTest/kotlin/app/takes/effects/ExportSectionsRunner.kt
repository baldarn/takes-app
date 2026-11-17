@file:OptIn(UnstableApi::class)

package app.takes.effects

import android.app.Activity
import android.app.Instrumentation
import android.net.Uri
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
import app.takes.ExportTimes
import app.takes.ExportTrack
import app.takes.Exporter
import app.takes.ProjectStore
import app.takes.blackStill
import app.takes.displaySize
import app.takes.frameRate
import app.takes.movieShape
import app.takes.movieTransformer
import app.takes.core.EditRender
import app.takes.core.EditState
import app.takes.core.ExportSections
import app.takes.core.Project
import app.takes.core.TimelineEditing
import app.takes.core.TransitionSettings
import app.takes.core.TransitionStyle
import androidx.media3.common.MimeTypes
import app.takes.EditRendering
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * The time an export takes in sections against one pass, phase by phase (issue #117): a 73-second edit of four
 * pieces of one recording, as plain cuts (`-e case plain`) or joined by a dissolve, a wipe and a fade
 * (`-e case transitions`).
 *
 *     adb shell am instrument -w -e clip front.mp4 -e case plain -e mode export app.takes.dev.test/app.takes.effects.ExportSectionsRunner
 *
 * `-e mode export` runs the app's whole export ([Exporter]) and reports the marks of [ExportTimes] for every
 * section. `-e mode picture -e lengths 12,24,0` renders only the picture, the movie cut into sections of each length
 * in seconds (0: one pass), each section as the export renders it ([ExportMovie.picture]), and reports each phase.
 * `-e runs <n>` repeats each; `-e pause <seconds>` pauses the export that long after it starts, as leaving the app does,
 * and resumes it. The clip is read from /data/local/tmp.
 */
class ExportSectionsRunner : Instrumentation() {
    private lateinit var arguments: Bundle
    private val thread = HandlerThread("sections-runner").apply { start() }

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

    private val marks = mutableListOf<Pair<Long, String>>()

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
        ExportTimes.listener = { what -> synchronized(marks) { marks += SystemClock.elapsedRealtimeNanos() to what } }
        try {
            val name = arguments.getString("clip") ?: "front.mp4"
            store = main { ProjectStore(targetContext) }
            val made = main { store.create() } ?: error("No project")
            main { store.importVideos(made.id, listOf(Uri.fromFile(File("/data/local/tmp", name)))) }
            waitFor(120, "the import") { store.importing == null && store.projects.first { it.id == made.id }.edit.clips.isNotEmpty() }
            val whole = main { store.projects.first { it.id == made.id } }.edit.clips.single()
            // Four pieces, 73.5 s, cut off the section boundaries.
            val cuts = listOf(0.5 to 19.0, 3.2 to 22.7, 1.1 to 20.6, 4.0 to 20.0)
            var edit = EditState(cuts.mapIndexed { index, (from, to) -> whole.copy(id = "C$index", start = from, end = minOf(to, whole.end)) })
            if (arguments.getString("case") == "transitions") {
                val styles = listOf(TransitionStyle.Dissolve, TransitionStyle.Wipe, TransitionStyle.FadeColor)
                styles.forEachIndexed { join, style -> edit = TimelineEditing.setJoin(edit, join + 1, TransitionSettings(style = style, duration = 1.0))!! }
            }
            main { store.commit(made.id, edit) }
            project = main { store.projects.first { it.id == made.id } }
            val runs = arguments.getString("runs")?.toInt() ?: 1
            say("edit: ${project!!.edit.duration} s, case ${arguments.getString("case") ?: "plain"}, clip $name")
            when (arguments.getString("mode") ?: "export") {
                "export" -> repeat(runs) { run ->
                    val exporter = main { Exporter(targetContext, store) }
                    val current = main { store.projects.first { it.id == made.id } }
                    File(store.disk.folder(current.id), "Exports").deleteRecursively()
                    synchronized(marks) { marks.clear() }
                    val started = SystemClock.elapsedRealtimeNanos()
                    main { exporter.start(current) }
                    // A pause in the middle, as when the app leaves the front, then the export resumed.
                    arguments.getString("pause")?.toDouble()?.let { seconds ->
                        Thread.sleep(Math.round(seconds * 1000))
                        main { exporter.pause() }
                        waitFor(60, "the pause") { exporter.status == null }
                        val paused = main { exporter.paused } ?: error("Not paused")
                        val kept = File(store.disk.folder(current.id), "Exports/.paused-export").list().orEmpty().sorted()
                        say("paused after %.1f s with %.0f s saved: %s".format(seconds, paused.savedSeconds, kept))
                        main { exporter.start(current) }
                    }
                    waitFor(900, "the export") { exporter.status == null }
                    val took = (SystemClock.elapsedRealtimeNanos() - started) / 1e9
                    val movie = main { exporter.result } ?: error("No movie: ${main { store.error }}")
                    val frames = ExportTrack.read(movie, "video/")?.samples
                    say("export run ${run + 1}: %.2f s, %d frames".format(took, frames))
                    phases(started).forEach(::say)
                    say("  " + times(movie, current.edit.duration, ::say))
                    say("  sound ${digest(movie, "audio/")}, picture ${digest(movie, "video/")}")
                }
                else -> pictures(project!!, store, runs, ::say)
            }
        } catch (problem: Throwable) {
            failed++
            say("FAIL  " + Log.getStackTraceString(problem))
        } finally {
            ExportTimes.listener = null
            if (arguments.getString("keep") != "true") project?.let { done -> store?.let { main { it.delete(done) } } }
        }
        report.append(if (failed == 0) "\nOK" else "\nFAILURES!!! $failed")
        finish(Activity.RESULT_OK, Bundle().apply { putString(REPORT_KEY_STREAMRESULT, report.toString()) })
    }

    /**
     * Whether [movie] holds every frame of a movie of [duration] seconds at n / fps, and whether its sample tables
     * ([app.takes.core.Mp4Samples]) say what the extractor counts.
     */
    private fun times(movie: File, duration: Double, say: (String) -> Unit): String {
        val extractor = android.media.MediaExtractor()
        extractor.setDataSource(movie.path)
        val index = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)!!.startsWith("video/") }
        val fps = 30
        extractor.selectTrack(index)
        var n = 0
        var off = 0
        while (extractor.sampleTrackIndex >= 0) {
            if (Math.abs(extractor.sampleTime - Math.round(n * 1e6 / fps)) > 1) off++
            n++
            extractor.advance()
        }
        extractor.release()
        val tables = listOf("video/", "audio/").map { kind -> ExportTrack.read(movie, kind)!!.let { Triple(it.samples, it.startsOnKey, it.largest) } }
        val counted = listOf("video/", "audio/").map { kind -> ExportTrack.counted(movie, kind)!!.let { Triple(it.samples, it.startsOnKey, it.largest) } }
        say(compare(movie, "video/"))
        say(compare(movie, "audio/"))
        return "frames $n of ${ExportSections.pictures(duration, fps)}, $off off the grid; tables $tables, counted $counted" + if (tables != counted) " DIFFER" else ""
    }

    /** The samples of [file]'s track of [kind] as the extractor gives them: their count and a digest of their times, flags and bytes. */
    private fun digest(file: File, kind: String): String {
        val extractor = android.media.MediaExtractor()
        extractor.setDataSource(file.path)
        extractor.selectTrack((0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)!!.startsWith(kind) })
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = java.nio.ByteBuffer.allocate(8 shl 20)
        var n = 0
        while (extractor.sampleTrackIndex >= 0) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            digest.update(buffer.array(), 0, size)
            digest.update("${extractor.sampleTime}/${extractor.sampleFlags}".toByteArray())
            n++
            extractor.advance()
        }
        extractor.release()
        return "$n " + digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    /** Where [file]'s sample tables ([app.takes.core.Mp4Samples.table]) disagree with the extractor on a sample's time, size or key, or "same". */
    private fun compare(file: File, kind: String): String {
        val table = app.takes.core.Mp4Samples.table(file, if (kind == "video/") app.takes.core.Mp4Samples.VIDEO else app.takes.core.Mp4Samples.SOUND) ?: return "no table"
        val extractor = android.media.MediaExtractor()
        extractor.setDataSource(file.path)
        extractor.selectTrack((0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)!!.startsWith(kind) })
        var n = 0
        val wrong = mutableListOf<String>()
        while (extractor.sampleTrackIndex >= 0) {
            val key = extractor.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC != 0
            if (n >= table.count || table.micros[n] != extractor.sampleTime || table.sizes[n].toLong() != extractor.sampleSize || table.keys[n] != key) {
                if (wrong.size < 3) wrong += "#$n ${extractor.sampleTime}/${extractor.sampleSize}/$key against " + if (n < table.count) "${table.micros[n]}/${table.sizes[n]}/${table.keys[n]}" else "none"
            }
            n++
            extractor.advance()
        }
        extractor.release()
        return "${file.name} $kind: $n samples, table ${table.count}, " + if (wrong.isEmpty() && n == table.count) "same" else "DIFFER $wrong"
    }

    /** The marks since [started], a line a section: each phase's time from the section's start, in ms. */
    private fun phases(started: Long): List<String> {
        val all = synchronized(marks) { marks.toList() }
        val lines = mutableListOf<String>()
        var line = StringBuilder()
        var opened = started
        for ((at, what) in all) {
            if (what.startsWith("section") || what.startsWith("run")) {
                if (line.isNotEmpty()) lines += line.toString()
                opened = at
                line = StringBuilder("  %-12s at %6.2f s:".format(what, (at - started) / 1e9))
            } else if (what.startsWith("encoder") || what in setOf("built", "started", "drawn", "first", "last", "completed", "saved")) {
                line.append(" %s +%d".format(what, (at - opened) / 1_000_000))
            } else {
                if (line.isNotEmpty()) { lines += line.toString(); line = StringBuilder() }
                lines += "  %-10s at %6.2f s".format(what, (at - started) / 1e9)
            }
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines
    }

    /** The picture alone, cut into sections of each length asked for, rendered one after the other as the export does. */
    private fun pictures(project: Project, store: ProjectStore, runs: Int, say: (String) -> Unit) {
        val plan = (EditRender.of(project.edit, EditRendering.LANES) as EditRender.Sequence).plan
        val files = project.sources.associate { it.id to store.file(project, it) }
        val canvas = movieShape(project.edit, files[plan.pictures.first().sourceID])!!.let { project.exportSettings.resolution.size(it.first, it.second) }
        val used = plan.pictures.map { it.sourceID }.distinct()
        val movie = ExportMovie(
            targetContext, plan, project.exportSettings.frameRate, files, canvas,
            sizes = used.associateWith { files[it]?.let(::displaySize) }, rates = used.associateWith { files[it]?.let(::frameRate) },
            black = blackStill(targetContext), luts = store.disk.folder(project.id),
        )
        val folder = File(targetContext.cacheDir, "sections-runner").apply { deleteRecursively(); mkdirs() }
        val lengths = (arguments.getString("lengths") ?: "12,0").split(",").map { it.trim().toInt() }
        repeat(runs) { run ->
            for (length in lengths) {
                val total = ExportSections.ticks(plan.duration)
                val step = if (length == 0) total else length * ExportSections.TICKS_PER_SECOND
                val sections = generateSequence(0L) { it + step }.takeWhile { it < total }.mapIndexed { index, start -> ExportSections.Section(index, start, minOf(step, total - start)) }.toList()
                synchronized(marks) { marks.clear() }
                val started = SystemClock.elapsedRealtimeNanos()
                var frames = 0
                val parts = mutableListOf<File>()
                for (section in sections) {
                    ExportTimes.mark("section ${section.index}")
                    val composition = movie.picture(section)
                    ExportTimes.mark("built")
                    val file = File(folder, "part-${section.index}.mp4")
                    render(composition, file, movie.rate)
                    val track = ExportTrack.read(file, "video/")!!
                    check(ExportSections.isComplete(track.samples, section.lengthTicks, movie.rate)) { "section ${section.index}: ${track.samples} frames" }
                    frames += track.samples
                    ExportTimes.mark("saved")
                    parts += file
                }
                val took = (SystemClock.elapsedRealtimeNanos() - started) / 1e9
                say("picture run ${run + 1}, sections of ${if (length == 0) "the whole movie" else "$length s"}: %.2f s, %d sections, %d frames".format(took, sections.size, frames))
                phases(started).forEach(say)
                if (arguments.getString("join") == "true") join(parts, movie.rate, folder, plan.duration, say)
                parts.forEach { it.delete() }
            }
        }
    }

    /** The join of [parts] with a sound, timed, and the reading of the parts alone. */
    private fun join(parts: List<File>, rate: Int, folder: File, duration: Double, say: (String) -> Unit) {
        val sound = File(folder, "sound.mp4")
        if (!sound.isFile) {
            val clip = File("/data/local/tmp", arguments.getString("clip") ?: "front.mp4")
            val item = androidx.media3.transformer.EditedMediaItem.Builder(
                androidx.media3.common.MediaItem.Builder().setUri(Uri.fromFile(clip)).setClippingConfiguration(
                    androidx.media3.common.MediaItem.ClippingConfiguration.Builder().setEndPositionUs(Math.round(duration / 3 * 1e6)).build(),
                ).build(),
            ).setRemoveVideo(true).build()
            // As long as the movie: the clip's sound three times.
            render(Composition.Builder(listOf(androidx.media3.transformer.EditedMediaItemSequence.Builder(setOf(androidx.media3.common.C.TRACK_TYPE_AUDIO)).addItem(item).addItem(item).addItem(item).build())).build(), sound, rate)
        }
        val tracks = parts.map { ExportTrack.read(it, "video/")!! }
        var t = SystemClock.elapsedRealtimeNanos()
        val buffer = java.nio.ByteBuffer.allocateDirect(tracks.maxOf { it.largest })
        var bytes = 0L
        for (part in parts) {
            val extractor = android.media.MediaExtractor()
            extractor.setDataSource(part.path)
            extractor.selectTrack((0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)!!.startsWith("video/") })
            while (extractor.sampleTrackIndex >= 0) { buffer.clear(); bytes += extractor.readSampleData(buffer, 0); extractor.advance() }
            extractor.release()
        }
        say("  read the parts: %.2f s, %.1f MB".format((SystemClock.elapsedRealtimeNanos() - t) / 1e9, bytes / 1e6))
        t = SystemClock.elapsedRealtimeNanos()
        java.io.FileOutputStream(File(folder, "raw.bin")).channel.use { out ->
            for (part in parts) {
                val extractor = android.media.MediaExtractor()
                extractor.setDataSource(part.path)
                extractor.selectTrack((0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)!!.startsWith("video/") })
                while (extractor.sampleTrackIndex >= 0) { buffer.clear(); buffer.limit(extractor.readSampleData(buffer, 0)); out.write(buffer); extractor.advance() }
                extractor.release()
            }
        }
        say("  read and write raw: %.2f s".format((SystemClock.elapsedRealtimeNanos() - t) / 1e9))
        File(folder, "raw.bin").delete()
        parts.forEach { say("  " + compare(it, "video/")) }
        say("  " + compare(sound, "audio/"))
        val joined = File(folder, "joined.mp4")
        t = SystemClock.elapsedRealtimeNanos()
        app.takes.ExportJoin.join(parts, tracks.map { it.samples }, rate, sound, maxOf(tracks.maxOf { it.largest }, 1 shl 16), joined, {}, {}, {})
        say("  join: %.2f s, %.1f MB, %s".format((SystemClock.elapsedRealtimeNanos() - t) / 1e9, joined.length() / 1e6, "${ExportTrack.read(joined, "video/")?.samples} + ${ExportTrack.read(joined, "audio/")?.samples} samples"))
        val reference = File(folder, "reference.mp4")
        app.takes.ExportJoin.readsTables = false
        t = SystemClock.elapsedRealtimeNanos()
        try {
            app.takes.ExportJoin.join(parts, tracks.map { it.samples }, rate, sound, maxOf(tracks.maxOf { it.largest }, 1 shl 16), reference, {}, {}, {})
        } finally {
            app.takes.ExportJoin.readsTables = true
        }
        say("  join through the extractor: %.2f s".format((SystemClock.elapsedRealtimeNanos() - t) / 1e9))
        for (kind in listOf("video/", "audio/")) say("  $kind joined ${digest(joined, kind)}, through the extractor ${digest(reference, kind)}")
        reference.delete()
        t = SystemClock.elapsedRealtimeNanos()
        app.takes.ExportCheck.verify(joined, duration, rate) {}
        say("  check: %.2f s".format((SystemClock.elapsedRealtimeNanos() - t) / 1e9))
        joined.delete()
    }

    private fun render(composition: Composition, file: File, rate: Int) {
        val done = CountDownLatch(1)
        var failure: ExportException? = null
        android.os.Handler(thread.looper).post {
            movieTransformer(targetContext, MimeTypes.VIDEO_H264, rate).setLooper(thread.looper).addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) { ExportTimes.mark("completed"); done.countDown() }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { failure = exportException; done.countDown() }
            }).build().start(composition, file.path)
            ExportTimes.mark("started")
        }
        done.await()
        failure?.let { throw it }
    }

    private companion object {
        const val TAG = "ExportSections"
    }
}
