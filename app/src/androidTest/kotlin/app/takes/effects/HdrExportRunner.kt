@file:OptIn(UnstableApi::class)

package app.takes.effects

import android.app.Activity
import android.app.Instrumentation
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import app.takes.ExportMovie
import app.takes.HdrMovie
import app.takes.LayerMovie
import app.takes.MovieRender
import app.takes.blackStill
import app.takes.movieTransformer
import app.takes.core.ColorLook
import app.takes.core.ColorSettings
import app.takes.core.EditState
import app.takes.core.EditTime
import app.takes.core.LayerPlan
import app.takes.core.PlacementLayout
import app.takes.core.ExportSections
import app.takes.core.TextOverlay
import app.takes.core.TimelineClip
import app.takes.core.TimelineEditing
import app.takes.core.TrackRenderPlan
import app.takes.core.TransitionSettings
import app.takes.core.TransitionStyle
import app.takes.core.VideoPlacement
import app.takes.core.VideoPlacementRegion
import app.takes.core.VideoTimelineItem
import app.takes.core.compile
import app.takes.core.toTimeline
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * Exports of an HDR recording through the real export ([ExportMovie]) and the real encoder setup ([movieTransformer]):
 * the clip alone, and two pieces of it joined by a transition with a title on the first and a colour look on the
 * second; and those two under a picture of the clip in the corner, drawn by picture layers ([LayerMovie]). Each movie is kept in the app's files/hdr-export as `<case>.mp4`, and its picture's format is reported: an
 * HDR movie must be H.265 in HLG. The time each export takes is reported.
 *
 *     adb shell am instrument -w -e clip hlg.mp4 app.takes.dev.test/app.takes.effects.HdrExportRunner
 *
 * The clip is taken from the app's files/hdr-export, else from /data/local/tmp. `-e sdr <name>` adds a second,
 * SDR, recording: the cases are then also exported with it in place of the HDR one (an SDR movie, for the time it
 * takes) and with the two joined (an HDR movie with SDR footage in it). `-e style <name>` picks the transition
 * (Dissolve by default), `-e look <name>` the look (Warm), `-e only <text>` the cases whose name holds the text.
 * `-e fallback true` takes the way of a device that writes no HDR movie ([HdrMovie.refuse]): every movie must then
 * be SDR, in the chosen codec.
 */
class HdrExportRunner : Instrumentation() {
    private lateinit var arguments: Bundle
    private val thread = HandlerThread("hdr-export").apply { start() }

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
            val folder = File(targetContext.filesDir, "hdr-export").apply { mkdirs() }
            fun recording(name: String) = File(folder, name).also { if (!it.isFile) File("/data/local/tmp", name).takeIf { it.canRead() }?.copyTo(it) ?: error("No $name") }
            // The way of a device that writes no HDR: the movie must come out SDR, tone-mapped.
            HdrMovie.refuse(arguments.getString("fallback") == "true")
            say("HDR movies ${if (HdrMovie.supported()) "written" else "not written: SDR, tone-mapped"}")
            val hdr = recording(arguments.getString("clip") ?: error("-e clip <name>"))
            val sdr = arguments.getString("sdr")?.let(::recording)
            val style = TransitionStyle.valueOf(arguments.getString("style") ?: "Dissolve")
            val look = ColorLook.valueOf(arguments.getString("look") ?: "Warm")
            val sources = listOfNotNull("H" to hdr, sdr?.let { "S" to it }).toMap()
            for ((id, file) in sources) say("$id ${file.name}: transfer ${HdrMovie.transfer(file)}, ${size(file)}")
            val cases = buildList {
                add("plain" to listOf(piece("H", 0.0, 4.0)))
                add("joined" to joined(piece("H", 0.0, 3.0, title = true), piece("H", 2.0, 6.0, look = look), style))
                // The same over a picture in the corner: an edit drawn by picture layers ([LayerMovie]).
                add("layered" to joined(piece("H", 0.0, 3.0, title = true), piece("H", 2.0, 6.0, look = look), style))
                if (sdr != null) {
                    add("sdr-plain" to listOf(piece("S", 0.0, 4.0)))
                    add("sdr-joined" to joined(piece("S", 0.0, 3.0, title = true), piece("S", 2.0, 6.0, look = look), style))
                    add("mixed" to joined(piece("S", 0.0, 3.0, title = true), piece("H", 2.0, 6.0, look = look), style))
                }
            }.filter { (name, _) -> arguments.getString("only")?.let { it in name } ?: true }
            for ((name, clips) in cases) {
                try {
                    val movie = File(folder, "$name.mp4")
                    val took = if (name == "layered") exportLayers(EditState(clips), sources, folder, movie) else export(EditState(clips), sources, folder, movie)
                    val format = format(movie)
                    say("ok    $name: %.1f s; %s".format(took / 1000.0, format))
                    val wanted = clips.any { it.sourceID == "H" } && HdrMovie.supported()
                    val isHdr = "transfer=${MediaFormat.COLOR_TRANSFER_HLG}" in format && MimeTypes.VIDEO_H265 in format
                    check(isHdr == wanted) { "$name: HDR ${if (wanted) "wanted" else "not wanted"}, the movie is $format" }
                } catch (problem: Throwable) {
                    failed++
                    say("FAIL  $name\n" + Log.getStackTraceString(problem))
                }
            }
            say("movies in ${folder.path}")
        } catch (problem: Throwable) {
            failed++
            say("FAIL  the exports could not be set up\n" + Log.getStackTraceString(problem))
        }
        report.append(if (failed == 0) "\nOK" else "\nFAILURES!!! $failed")
        finish(Activity.RESULT_OK, Bundle().apply { putString(REPORT_KEY_STREAMRESULT, report.toString()) })
    }

    private fun piece(source: String, from: Double, to: Double, title: Boolean = false, look: ColorLook? = null) = TimelineClip(
        id = "$source-$from", sourceID = source, start = from, end = to,
        texts = if (title) listOf(TextOverlay(id = "T", text = "HDR", x = 0.5, y = 0.3, size = 0.2, start = from, end = to)) else null,
        color = look?.let { ColorSettings(look = it) },
    )

    private fun joined(first: TimelineClip, second: TimelineClip, style: TransitionStyle): List<TimelineClip> =
        TimelineEditing.setJoin(EditState(listOf(first, second)), 1, TransitionSettings(style = style, duration = 1.0))!!.clips

    private fun size(file: File): Pair<Int, Int> = MediaMetadataRetriever().run {
        try {
            setDataSource(file.path)
            val w = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val h = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            val turned = (extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0) % 180 != 0
            if (turned) h to w else w to h
        } finally {
            release()
        }
    }

    /** The picture's format of [movie] as its container states it. */
    private fun format(movie: File): String {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(movie.path)
            val format = (0 until extractor.trackCount).map(extractor::getTrackFormat).first { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            fun number(key: String) = if (format.containsKey(key)) format.getInteger(key).toString() else "-"
            "${format.getString(MediaFormat.KEY_MIME)} profile=${number(MediaFormat.KEY_PROFILE)} standard=${number(MediaFormat.KEY_COLOR_STANDARD)} transfer=${number(MediaFormat.KEY_COLOR_TRANSFER)} range=${number(MediaFormat.KEY_COLOR_RANGE)}"
        } finally {
            extractor.release()
        }
    }

    /** Exports [state] into [movie] (one section), in milliseconds. */
    private fun export(state: EditState, sources: Map<String, File>, folder: File, movie: File): Long {
        val plan = TrackRenderPlan.of(state)
        val sizes = sources.mapValues { size(it.value) }
        val first = sizes.getValue(state.clips.first().sourceID)
        val render = ExportMovie(
            targetContext, plan, 30, sources, first, sizes = sizes, rates = sources.mapValues { 30.0 }, black = blackStill(targetContext), luts = folder,
            transfers = HdrMovie.transfers(sources),
        )
        return render(render, ExportSections.sections(plan.duration).single(), movie)
    }

    /**
     * Exports [state] with a picture of its first source over it in the bottom right corner for its whole length, so
     * that it is drawn by picture layers ([LayerMovie]), into [movie] (one section), in milliseconds.
     */
    private fun exportLayers(state: EditState, sources: Map<String, File>, folder: File, movie: File): Long {
        val timeline = state.toTimeline()
        val source = state.clips.first().sourceID
        val length = TimelineEditing.duration(state.clips)
        val over = timeline.copy(video = timeline.video + VideoTimelineItem.of("occurrence-O", EditTime.of(0.0), TimelineClip(id = "O", sourceID = source, start = 0.0, end = length), 10))
        val corner = VideoPlacement.layout(PlacementLayout.PictureInPicture, 1, 2).copy(order = 10)
        val together = over.compile().map { it.video.toSet() }.filter { "O" in it && it.size > 1 }.distinct()
        val placed = over.copy(placements = together.mapIndexed { number, ids -> VideoPlacementRegion(id = "R$number", participants = ids, videos = ids.associateWith { if (it == "O") corner else VideoPlacement() }) })
        val plan = LayerPlan.planned(placed, 3) ?: error("More pictures at once than three lanes")
        val sizes = sources.mapValues { size(it.value) }
        val render = LayerMovie(
            targetContext, plan, 30, sources, sizes.getValue(source), 1, sizes = sizes, rates = sources.mapValues { 30.0 }, black = blackStill(targetContext), luts = folder,
            transfers = HdrMovie.transfers(sources),
        )
        return render(render, ExportSections.sections(plan.duration).single(), movie)
    }

    /** Renders [section] of [render] into [movie], in milliseconds. */
    private fun render(render: MovieRender, section: ExportSections.Section, movie: File): Long {
        val started = SystemClock.elapsedRealtime()
        val done = CountDownLatch(1)
        var failure: ExportException? = null
        android.os.Handler(thread.looper).post {
            movieTransformer(targetContext, MimeTypes.VIDEO_H264, render.rate, render.hdr).setLooper(thread.looper).addListener(object : androidx.media3.transformer.Transformer.Listener {
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

    private companion object {
        const val TAG = "HdrExport"
    }
}
