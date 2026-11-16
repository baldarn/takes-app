@file:OptIn(UnstableApi::class)

package app.takes.pixels

import android.app.Activity
import android.app.Instrumentation
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.HandlerThread
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
import app.takes.core.TimelineEditing
import app.takes.core.TrackRenderPlan
import app.takes.core.VideoEffectID
import app.takes.core.VideoEffectSamplePlan
import app.takes.core.VideoEffectSequenceClock
import app.takes.core.VideoEffectSettings
import app.takes.core.VideoEffectTiming
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Exports of the time effects on the emulator, with the time of every frame
 * checked: a synthetic recording whose frames show their own numbers is
 * exported through the real export ([ExportMovie]) with Undercranking, Step
 * Printing and a Freeze Frame, and each frame of the movie read back is the
 * source frame the core says it shows at that second.
 *
 *     adb shell am instrument -w app.takes.dev.test/app.takes.pixels.TimeExportRunner
 *
 * The recording is made in the test's own cache folder; no project of the app is touched.
 */
class TimeExportRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    private val thread = HandlerThread("time-export").apply { start() }

    override fun onStart() {
        super.onStart()
        val report = StringBuilder()
        var failed = 0
        try {
            val folder = File(targetContext.cacheDir, "time-export").apply { deleteRecursively(); mkdirs() }
            val source = numbered(File(folder, "numbered.mp4"), FRAMES)
            for ((name, state, expected) in cases()) {
                val line = StringBuilder()
                try {
                    val frames = export(state, source, folder)
                    val duration = state.duration
                    val count = ExportSections.pictures(duration, RATE.toInt())
                    var exact = 0
                    var worst = 0
                    val wrong = mutableListOf<String>()
                    if (frames.size != count) wrong += "${frames.size} frames for $count"
                    frames.forEachIndexed { index, (timeUs, number) ->
                        val stamp = Math.round(index * 1e6 / RATE)
                        if (abs(timeUs - stamp) > 1) wrong += "frame $index at $timeUs µs, not $stamp"
                        val want = expected(index / RATE)
                        val off = abs(number - want)
                        if (off == 0) exact++
                        worst = maxOf(worst, off)
                        if (off > 1 && wrong.size < 12) wrong += "frame $index (${"%.3f".format(index / RATE)} s) shows source frame $number, the core $want"
                    }
                    // The picture changes on the same frames of the movie as the core's: a hold begins and ends where the core's does.
                    val changes = (1 until frames.size).filter { frames[it].second != frames[it - 1].second }
                    val wanted = (1 until frames.size).filter { expected(it / RATE) != expected((it - 1) / RATE) }
                    if (changes != wanted) wrong += "the picture changes at frames ${changes.take(24)}…, the core's at ${wanted.take(24)}…"
                    line.append(if (wrong.isEmpty()) "ok    " else "FAIL  ").append(name)
                        .append("\n        ${frames.size} frames, $exact exactly the core's frame, worst ${worst} frame(s) off")
                        .append("\n        shown: ").append(frames.joinToString(" ") { it.second.toString() })
                    for (problem in wrong) line.append("\n        ! ").append(problem)
                    if (wrong.isNotEmpty()) failed++
                } catch (problem: Throwable) {
                    failed++
                    line.append("FAIL  ").append(name).append('\n').append(Log.getStackTraceString(problem))
                }
                Log.i("TimeExport", line.toString())
                report.append(line).append('\n')
            }
            folder.deleteRecursively()
        } catch (problem: Throwable) {
            failed++
            report.append("FAIL  the tests could not be set up\n").append(Log.getStackTraceString(problem)).append('\n')
        }
        report.append(if (failed == 0) "\nOK" else "\nFAILURES!!! $failed")
        finish(Activity.RESULT_OK, Bundle().apply { putString(REPORT_KEY_STREAMRESULT, report.toString()) })
    }

    private data class TimeCase(val name: String, val state: EditState, val expected: (Double) -> Int)

    private fun effect(kind: VideoEffectID, start: Double, end: Double, values: Map<String, Any>): VideoEffectSettings {
        var made = VideoEffectSettings(kind, start, end)
        for ((id, value) in values) made = made.with(id, when (value) {
            is Boolean -> EffectValue.Toggle(value)
            is String -> EffectValue.Choice(value)
            else -> EffectValue.Number((value as Number).toDouble())
        })
        return made
    }

    /** The source frame the frame at movie second [time] shows, by the core, for one clip starting at zero. */
    private fun frameOf(clip: TimelineClip, time: Double): Int {
        val clock = VideoEffectSequenceClock.of(clip)
        val offset = time.coerceIn(0.0, clip.duration)
        val effects = clip.videoEffects.orEmpty()
        val sourceTime = clip.sourceTime(offset)
        val plan = VideoEffectSamplePlan.make(effects, sourceTime, RATE, clock)
        if (plan != null && plan.times.size > 1) return minOf(FRAMES - 1, ceil(plan.times.first() * RATE - 0.0001).toInt())
        return (VideoEffectTiming.shown(clip, offset, RATE, clock).sourceTime * RATE).roundToInt()
    }

    private fun cases(): List<TimeCase> {
        val sped = TimelineClip(id = "U", sourceID = "S", start = 0.0, end = 6.0, videoEffects = listOf(effect(VideoEffectID.Undercranking, 0.0, 6.0, mapOf("speed" to 1.5, "fps" to 12.0, "flicker" to 0.0, "weave" to 0.0))))
        val part = TimelineClip(id = "P", sourceID = "S", start = 0.0, end = 6.0, videoEffects = listOf(effect(VideoEffectID.Undercranking, 2.0, 4.0, mapOf("speed" to 1.5, "fps" to 16.0, "flicker" to 0.0, "weave" to 0.0))))
        val stepped = TimelineClip(id = "T", sourceID = "S", start = 0.0, end = 4.0, videoEffects = listOf(effect(VideoEffectID.StepPrinting, 0.0, 4.0, mapOf("step" to 3.0, "smear" to 0.0, "speed" to 0.5))))
        val (frozen, _) = TimelineEditing.freezing(EditState(listOf(TimelineClip(id = "F", sourceID = "S", start = 0.0, end = 6.0))), 2.0)!!
        val holdStart = TimelineEditing.clipStart(frozen.clips, 1)
        val resumes = TimelineEditing.clipStart(frozen.clips, 2)
        return listOf(
            TimeCase("Undercranking at 12 a second, sped up 1.5", EditState(listOf(sped))) { frameOf(sped, it) },
            TimeCase("Undercranking at 16 on part of the clip", EditState(listOf(part))) { frameOf(part, it) },
            TimeCase("Step Printing of three frames at half speed", EditState(listOf(stepped))) { frameOf(stepped, it) },
            TimeCase("Freeze Frame at two seconds", frozen) { time ->
                when {
                    time < holdStart -> (time * RATE).roundToInt()
                    time < resumes -> (frozen.clips[1].frozenFrame!!.sourceTime * RATE).roundToInt()
                    else -> ((frozen.clips[2].start + time - resumes) * RATE).roundToInt()
                }
            },
        )
    }

    /** The movie of [state] exported section by section, read back: each frame's time and the source frame it shows. */
    private fun export(state: EditState, source: File, folder: File): List<Pair<Long, Int>> {
        val plan = TrackRenderPlan.of(state)
        val movie = ExportMovie(
            targetContext, plan, RATE.toInt(), mapOf("S" to source), WIDTH to HEIGHT,
            sizes = mapOf("S" to (WIDTH to HEIGHT)), rates = mapOf("S" to RATE), black = blackStill(targetContext), luts = folder,
        )
        val result = ArrayList<Pair<Long, Int>>()
        for (section in ExportSections.sections(plan.duration)) {
            val file = File(folder, "section-${section.index}.mp4")
            render(movie.picture(section), file)
            val origin = Math.round(section.start * 1e6)
            result += read(file).map { (time, number) -> time + origin to number }
            file.delete()
        }
        return result
    }

    private fun render(composition: Composition, file: File) {
        val done = CountDownLatch(1)
        var failure: ExportException? = null
        android.os.Handler(thread.looper).post {
            Transformer.Builder(targetContext).setLooper(thread.looper).addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) = done.countDown()
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    failure = exportException
                    done.countDown()
                }
            }).build().start(composition, file.path)
        }
        done.await()
        failure?.let { throw it }
    }

    /** Every frame of [file] in order: its time in microseconds and the number its bars spell. */
    private fun read(file: File): List<Pair<Long, Int>> {
        val extractor = MediaExtractor().apply { setDataSource(file.path) }
        val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        decoder.configure(format, null, null, 0)
        decoder.start()
        val frames = ArrayList<Pair<Long, Int>>()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        while (true) {
            if (!inputDone) {
                val index = decoder.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val size = extractor.readSampleData(decoder.getInputBuffer(index)!!, 0)
                    if (size < 0) { decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance() }
                }
            }
            val out = decoder.dequeueOutputBuffer(info, 10_000)
            if (out >= 0) {
                if (info.size > 0) {
                    val image = decoder.getOutputImage(out)!!
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    var number = 0
                    for (bit in 0 until BITS) {
                        val x = bit * image.width / BITS + image.width / BITS / 2
                        val y = image.height / 4
                        val luma = buffer.get(y * plane.rowStride + x * plane.pixelStride).toInt() and 0xff
                        if (luma > 128) number = number or (1 shl bit)
                    }
                    frames += info.presentationTimeUs to number
                    image.close()
                }
                decoder.releaseOutputBuffer(out, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        decoder.stop()
        decoder.release()
        extractor.release()
        return frames.sortedBy { it.first }
    }

    /** A recording of [count] frames at 30 a second whose frames spell their own number in bars across the top. */
    private fun numbered(file: File, count: Int): File {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, RATE.toInt())
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var fed = 0
        val info = MediaCodec.BufferInfo()
        while (true) {
            if (fed <= count) {
                val index = encoder.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    if (fed == count) {
                        encoder.queueInputBuffer(index, 0, 0, fed * 1_000_000L / RATE.toLong(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        val image = encoder.getInputImage(index)!!
                        for ((p, plane) in image.planes.withIndex()) {
                            val w = if (p == 0) WIDTH else WIDTH / 2
                            val h = if (p == 0) HEIGHT else HEIGHT / 2
                            for (y in 0 until h) for (x in 0 until w) {
                                val value = if (p > 0) 128 else if (y < h / 2) (if ((fed shr (x * BITS / w)) and 1 == 1) 235 else 16) else 128
                                plane.buffer.put(y * plane.rowStride + x * plane.pixelStride, value.toByte())
                            }
                        }
                        encoder.queueInputBuffer(index, 0, WIDTH * HEIGHT * 3 / 2, Math.round(fed * 1e6 / RATE), 0)
                    }
                    fed++
                }
            }
            val out = encoder.dequeueOutputBuffer(info, 10_000)
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = muxer.addTrack(encoder.outputFormat); muxer.start() }
            else if (out >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) muxer.writeSampleData(track, encoder.getOutputBuffer(out)!!, info)
                encoder.releaseOutputBuffer(out, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        encoder.stop()
        encoder.release()
        muxer.stop()
        muxer.release()
        return file
    }

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 192
        const val RATE = 30.0
        const val FRAMES = 240
        const val BITS = 8
    }
}
