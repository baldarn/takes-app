@file:OptIn(UnstableApi::class)

package app.takes.probe

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import app.takes.core.ExportSections
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * Runs one scenario and writes what it measured to `report.txt` beside the movies it made, in the
 * app's files folder (`in/` holds the footage of `tools/make-clips.sh`, `out/` the results).
 *
 *     am instrument -w -e scenario <name> [-e fps 30] [...] app.takes.probe/.ProbeInstrumentation
 *
 * Scenarios: `whole` (one pass, `-e timing decks|clock`), `sections` (the movie in twelve-second
 * sections, joined), `cancel` and `hang` (an export stopped midway), `inspect` (`-e file`), `two`
 * (two sequences as the probe draws them: `-e first 30 -e second 60 -e cut <microseconds>`), `app`
 * (the app's own composition and transition effect: `-e clips three|still`, `-e ceiling 30|60`).
 */
class ProbeInstrumentation : Instrumentation() {
    private lateinit var arguments: Bundle
    private lateinit var input: File
    private lateinit var output: File
    private val lines = mutableListOf<String>()

    override fun onCreate(arguments: Bundle) {
        this.arguments = arguments
        start()
    }

    private fun say(line: String) {
        Log.i("TakesProbe", line)
        lines += line
    }

    private fun text(name: String, fallback: String) = arguments.getString(name) ?: fallback
    private fun number(name: String, fallback: Long) = arguments.getString(name)?.toLong() ?: fallback

    override fun onStart() {
        val folder = targetContext.filesDir
        input = File(folder, "in")
        output = File(folder, "out").apply { mkdirs() }
        val scenario = text("scenario", "whole")
        say("== $scenario " + arguments.keySet().filter { it != "scenario" }.sorted().joinToString(" ") { "$it=${arguments.getString(it)}" })
        try {
            when (scenario) {
                "whole" -> whole()
                "sections" -> sections()
                "cancel" -> cancel()
                "hang" -> hang()
                "inspect" -> inspect(File(output, text("file", "hung.mp4")))
                "two" -> two()
                "app" -> app()
                else -> say("unknown scenario")
            }
        } catch (problem: Throwable) {
            say("FAILED " + Log.getStackTraceString(problem))
        }
        File(output, "report.txt").appendText(lines.joinToString("\n", postfix = "\n"))
        finish(Activity.RESULT_OK, Bundle().apply { putString("stream", lines.joinToString("\n", postfix = "\n")) })
    }

    // --- The movie of the sections probe ---

    private val fps get() = number("fps", 30).toInt()
    private val tag get() = text("tag", "")

    /** `-e filler no` leaves Media3's own gaps where a deck is empty, as the app does today. */
    private val filler get() = if (text("filler", "yes") == "yes") Filler(still(), fps, later) else null

    /** How many microseconds later than their own the decks' frame times are made ([LaterEffect]); `-e later 0` leaves them. */
    private val later get() = number("later", 3)
    private val canvas get() = text("canvas", "640x360").split("x").let { Size(it[0].toInt(), it[1].toInt()) }

    /**
     * Three pieces on two decks with two overlaps, one across the first section boundary; the second
     * boundary falls where only the second deck plays and the third where only the first does. The
     * movie is forty seconds and nine frames long, or what `-e total` says, in microseconds.
     */
    private fun movie(): Plan {
        val second = 1_000_000L
        val total = number("total", 40 * second + Math.round(9e6 / fps))
        val left = File(input, "L$fps.mp4")
        val right = File(input, "R$fps.mp4")
        return Plan(
            listOf(
                Piece(0, left, 1 * second, 0, 13 * second),
                Piece(1, right, 2 * second, 11 * second, 19 * second),
                Piece(0, left, 3 * second, 29 * second, total - 29 * second),
            ),
            total, File(input, "music.m4a").takeIf { text("music", "yes") == "yes" },
        )
    }

    /** A black still of 16 by 16 pixels, the size of the frames Media3 fills a gap with. */
    private fun still(): File = File(output, "clock.png").also { file ->
        if (!file.isFile) file.outputStream().use { Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun micros(ticks: Long) = ticks * 1_000_000 / ExportSections.TICKS_PER_SECOND

    private fun whole() {
        val plan = movie()
        val frames = ExportSections.pictures(plan.total / 1e6, fps)
        val timing = if (text("timing", "clock") == "clock") Timing.Clock(still(), fps, frames) else Timing.Decks
        val tracks = Tracks.valueOf(text("tracks", "Both"))
        val file = File(output, text("name", "whole-${text("timing", "clock")}-$fps.mp4"))
        val run = export(Compositions.of(plan, 0, plan.total, canvas, timing, tracks, fps.takeIf { text("overlay", "yes") == "yes" }, filler.takeIf { timing is Timing.Clock }), file)
        say("whole ${file.name}: ${run.describe()} expected $frames pictures")
        inspect(file)
    }

    private fun sections() {
        val plan = movie()
        val sections = ExportSections.sections(plan.total / 1e6)
        val began = SystemClock.elapsedRealtime()
        // The sound of the whole movie, once.
        val sound = File(output, "sound-$fps$tag.mp4")
        val soundRun = export(Compositions.of(plan, 0, plan.total, canvas, Timing.Decks, Tracks.Audio, null), sound)
        say("sound ${sound.name}: ${soundRun.describe()}")
        inspect(sound)
        // The picture, a section at a time; then the same sections with their own sound, for the join that does not work.
        val pictures = mutableListOf<File>()
        val withSound = mutableListOf<File>()
        val counts = mutableListOf<Int>()
        var pictureMillis = 0L
        for (kind in listOf(Tracks.Video, Tracks.Both)) for (section in sections) {
            if (kind == Tracks.Both && text("both", "yes") != "yes") continue
            val frames = ExportSections.pictures(section.lengthTicks, fps)
            val from = micros(section.startTicks)
            val to = micros(section.startTicks + section.lengthTicks)
            val file = File(output, (if (kind == Tracks.Video) "picture" else "both") + "-$fps$tag-${section.index}.mp4")
            val run = export(Compositions.of(plan, from, to, canvas, Timing.Clock(still(), fps, frames), kind, fps, filler), file)
            val track = Joiner.track(file, "video/")
            val complete = track != null && ExportSections.isComplete(track.samples, section.lengthTicks, fps)
            val joins = track != null && ExportSections.canJoin(track.samples, section.lengthTicks, fps, section.index == sections.lastIndex)
            say("section ${file.name} [$from, $to): ${run.describe()} expected $frames pictures; isComplete=$complete canJoin=$joins")
            inspect(file)
            if (kind == Tracks.Video) { pictures += file; counts += frames; pictureMillis += run.millis } else withSound += file
        }
        say("setup equal across sections: " + (pictures.mapNotNull { Joiner.track(it, "video/") }.map { it.setup + " " + it.shape }.distinct().size == 1))
        for (framework in listOf(false, true)) {
            val joined = File(output, "joined-$fps$tag" + (if (framework) "-framework" else "") + ".mp4")
            val joining = SystemClock.elapsedRealtime()
            val written = Joiner.join(pictures, counts, fps, listOf(sound), listOf(0L), joined, framework)
            say("join ${joined.name}: ${SystemClock.elapsedRealtime() - joining} ms, pictures ${written.first}, sound samples ${written.second}")
            inspect(joined)
            if (!framework) say("sections in all: ${joining - began + (SystemClock.elapsedRealtime() - joining)} ms from the first step to the joined movie, the sections with sound left out; picture alone $pictureMillis ms, sound ${soundRun.millis} ms")
        }
        if (withSound.isNotEmpty()) {
            val joined = File(output, "joined-own-sound-$fps$tag.mp4")
            try {
                val written = Joiner.join(withSound, counts, fps, withSound, sections.map { micros(it.startTicks) }, joined, false)
                say("join ${joined.name}: pictures ${written.first}, sound samples ${written.second}")
                inspect(joined)
            } catch (problem: Exception) {
                say("join ${joined.name} failed: $problem")
            }
        }
    }

    private fun cancel() {
        val plan = movie()
        val frames = ExportSections.pictures(plan.total / 1e6, fps)
        val file = File(output, "cancelled.mp4")
        val run = export(Compositions.of(plan, 0, plan.total, canvas, Timing.Clock(still(), fps, frames), Tracks.Video, fps, filler), file, cancelAfter = number("after", 1500))
        say("cancelled after ${number("after", 1500)} ms: ${run.describe()}")
        inspect(file)
    }

    /** Starts an export and never returns: the process is killed from outside, and `inspect` looks at what it left. */
    private fun hang() {
        val plan = movie()
        val frames = ExportSections.pictures(plan.total / 1e6, fps)
        val file = File(output, "hung.mp4")
        file.delete()
        export(Compositions.of(plan, 0, plan.total, canvas, Timing.Clock(still(), fps, frames), Tracks.Video, fps, filler), file, cancelAfter = null, patience = 3_600_000)
    }

    private fun inspect(file: File) {
        if (!file.exists()) { say("  ${file.name}: no file"); return }
        val picture = Joiner.track(file, "video/")
        val sound = Joiner.track(file, "audio/")
        if (picture == null && sound == null) { say("  ${file.name}: ${file.length()} bytes, NOT READABLE as a movie"); return }
        say("  ${file.name}: ${file.length()} bytes")
        picture?.let { say("    picture: ${it.samples} samples, ${it.keys} key, first key=${it.firstKey}, times ${it.firstTime}..${it.lastTime}, reordered=${it.reordered}, setup ${it.setup}, ${it.shape}") }
        sound?.let { say("    sound: ${it.samples} samples, times ${it.firstTime}..${it.lastTime}, setup ${it.setup}, ${it.shape}") }
    }

    // --- The two-sequence defect ---

    /**
     * The first deck plays a clip until `cut` (three seconds unless said), the second a clip from two seconds to
     * nine, the first again from five to eight seconds after `cut`: in between, the second deck plays alone.
     */
    private fun two() {
        val second = 1_000_000L
        val cut = number("cut", 3 * second)
        val first = File(input, "L${number("first", 30)}.mp4")
        val other = File(input, "R${number("second", 60)}.mp4")
        val total = cut + 8 * second
        val plan = Plan(
            listOf(
                Piece(0, first, 1 * second, 0, cut),
                Piece(1, other, 2 * second, 2 * second, 7 * second),
                Piece(0, first, 8 * second, cut + 5 * second, 3 * second),
            ),
            total, null,
        )
        val clocked = text("timing", "decks") == "clock"
        val frames = ExportSections.pictures(total / 1e6, fps)
        val timing = if (clocked) Timing.Clock(still(), fps, frames) else Timing.Decks
        val filled = text("filler", if (clocked) "yes" else "no") == "yes"
        val file = File(output, text("name", "two-${number("first", 30)}-${number("second", 60)}-$cut-" + (if (clocked) "clock$fps" else "decks") + (if (filled) "-filler" else "") + ".mp4"))
        val run = export(Compositions.of(plan, 0, total, canvas, timing, Tracks.Both, null, if (filled) Filler(still(), fps, later) else null), file)
        say("two ${file.name}: ${run.describe()}" + if (clocked) " expected $frames pictures" else "")
        inspect(file)
    }

    /**
     * The app's composition ([AppMirror]) of one of two edits. `three`: a clip on the first deck, a
     * dissolve of one second into a clip on the second, a dissolve of one second into a third clip,
     * on the first deck again; `cut` is the length of the first clip in microseconds. `still`: one
     * clip with a freeze of one second as its opening transition (`-e dissolve` seconds to leave it).
     */
    private fun app() {
        val first = number("first", 30).toInt()
        val other = number("second", 60).toInt()
        val ceiling = number("ceiling", 60).toInt()
        val left = File(input, "L$first.mp4")
        val right = File(input, "R$other.mp4")
        val kind = text("clips", "three")
        val clips: List<app.takes.core.TimelineClip>
        val files: List<File>
        val footage: List<Double>
        if (kind == "still") {
            val opening = app.takes.core.TransitionSettings(
                style = app.takes.core.TransitionStyle.Still, duration = 1.0, stillDissolve = arguments.getString("dissolve")?.toDouble(),
            )
            clips = listOf(app.takes.core.TimelineClip(sourceID = "left", start = 1.0, end = 5.0, openingTransition = opening))
            files = listOf(left)
            footage = listOf(first.toDouble())
        } else {
            val c = app.takes.core.TimelineClip(sourceID = "left", start = 8.0, end = 11.0)
            val b = app.takes.core.TimelineClip(sourceID = "right", start = 2.0, end = 9.0, transition = app.takes.core.TransitionSettings(nextClipID = c.id, duration = 1.0))
            val a = app.takes.core.TimelineClip(sourceID = "left", start = 1.0, end = 1.0 + number("cut", 3_000_000) / 1e6, transition = app.takes.core.TransitionSettings(nextClipID = b.id, duration = 1.0))
            clips = listOf(a, b, c)
            files = listOf(left, right, left)
            footage = listOf(first.toDouble(), other.toDouble(), first.toDouble())
        }
        val total = app.takes.core.TimelineEditing.duration(clips)
        val layout = app.takes.core.TimelineEditing.layout(clips)
        say("app $kind: movie of $total s; clips at " + layout.placements.joinToString { "%.6f..%.6f".format(it.start, it.end) } +
            "; chains " + app.takes.core.TimelineEditing.chains(clips).joinToString { "${it.first}-${it.last} %.6f..%.6f".format(it.start, it.end) })
        if (kind == "still") for (time in listOf(0.0, 0.5, 0.99, 1.0, 1.2)) say("  scene at $time s: " + app.takes.core.TransitionScene.moment(clips, time).let { "${it.first} | ${it.second} | ${it.frame}" })
        val clocked = text("timing", "decks") == "clock"
        val clock = if (clocked) Timing.Clock(still(), fps, ExportSections.pictures(total, fps)) else null
        val file = File(output, text("name", "app-$kind-$first-$other-${number("cut", 3_000_000)}-" + (if (clocked) "clock$fps" else "decks$ceiling") + ".mp4"))
        val run = export(AppMirror.composition(clips, files, footage, canvas, ceiling, clock, later), file)
        say("app ${file.name}: ${run.describe()}" + if (clock != null) " expected ${clock.frames} pictures" else "")
        inspect(file)
    }

    // --- Running the transformer ---

    private class Run(val millis: Long, val result: ExportResult?, val error: ExportException?, val cancelled: Boolean) {
        fun describe(): String = when {
            cancelled -> "cancelled at $millis ms"
            error != null -> "ERROR ${error.errorCodeName}: ${error.message} (${error.cause}) after $millis ms"
            else -> "$millis ms, ${result!!.videoFrameCount} pictures, ${result.approximateDurationMs} ms long, ${result.fileSizeBytes} bytes, picture by ${result.videoEncoderName}, sound by ${result.audioEncoderName}"
        }
    }

    private fun export(composition: Composition, file: File, cancelAfter: Long? = null, patience: Long = 600_000): Run {
        val done = CountDownLatch(1)
        var result: ExportResult? = null
        var error: ExportException? = null
        var cancelled = false
        val began = SystemClock.elapsedRealtime()
        val main = Handler(Looper.getMainLooper())
        main.post {
            val transformer = Transformer.Builder(targetContext)
                .setVideoMimeType(if (text("codec", "h264") == "hevc") MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { result = exportResult; done.countDown() }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { error = exportException; done.countDown() }
                }).build()
            transformer.start(composition, file.path)
            if (cancelAfter != null) main.postDelayed({ if (done.count > 0) { transformer.cancel(); cancelled = true; done.countDown() } }, cancelAfter)
        }
        done.await(patience, java.util.concurrent.TimeUnit.MILLISECONDS)
        return Run(SystemClock.elapsedRealtime() - began, result, error, cancelled)
    }
}
