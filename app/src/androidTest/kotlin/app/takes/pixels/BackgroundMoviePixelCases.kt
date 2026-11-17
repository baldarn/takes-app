package app.takes.pixels

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import app.takes.EffectAssets
import app.takes.core.EffectBackgroundLoop
import app.takes.core.EffectValue
import app.takes.core.VideoEffectID
import app.takes.core.VideoEffectSettings
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * A Chroma Key's and an Auto Background's Video background in the export: a movie of grey frames, each a level of its
 * own, played behind a green screen from the effect's start and looped over its length, the end dissolved into the
 * start (VideoEffectAssets.swift:592-598, 638-660, VideoEffectBackgroundAudio.swift:7-15). Where the screen is, the
 * frame shows the movie's frame of that second, or the two of the overlap mixed in linear light.
 */
class BackgroundMoviePixelCases(private val bench: Bench, private val width: Int = 256, private val height: Int = 144) {
    private val folder = File(bench.context.cacheDir, "background-movies").apply { mkdirs() }

    /** The level of frame [i]'s Y′: far apart, so that a frame mistaken for its neighbour shows. */
    private fun luma(i: Int): Int = 16 + 6 * i

    /** The level a frame of luma [y] shows, its grey decoded in the limited range. */
    private fun level(y: Int): Int = ((y - 16) * 255.0 / 219).roundToInt().coerceIn(0, 255)

    private val movie: File by lazy { greys(File(folder, "greys.mp4"), FRAMES) }

    /** [count] frames at 30 a second, each grey of [luma] across the picture. */
    private fun greys(file: File, count: Int): File {
        if (file.isFile) file.delete()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, MOVIE_WIDTH, MOVIE_HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
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
                        encoder.queueInputBuffer(index, 0, 0, Math.round(fed * 1e6 / 30), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        val image = encoder.getInputImage(index)!!
                        for ((p, plane) in image.planes.withIndex()) {
                            val w = if (p == 0) MOVIE_WIDTH else MOVIE_WIDTH / 2
                            val h = if (p == 0) MOVIE_HEIGHT else MOVIE_HEIGHT / 2
                            val value = if (p == 0) luma(fed) else 128
                            for (y in 0 until h) for (x in 0 until w) plane.buffer.put(y * plane.rowStride + x * plane.pixelStride, value.toByte())
                        }
                        encoder.queueInputBuffer(index, 0, MOVIE_WIDTH * MOVIE_HEIGHT * 3 / 2, Math.round(fed * 1e6 / 30), 0)
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

    /** The movie's length as its track says, which the loop is laid on. */
    private fun duration(file: File): Double {
        val extractor = MediaExtractor().apply { setDataSource(file.path) }
        try {
            val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }.first { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            return format.getLong(MediaFormat.KEY_DURATION) / 1e6
        } finally {
            extractor.release()
        }
    }

    /** A green screen, brightest at the top, with a grey square in the middle for a subject. */
    private val screen = Picture(width, height) { x, y ->
        val inside = abs(x - width / 2) < width / 6 && abs(y - height / 2) < height / 4
        if (inside) rgb(128, 120, 110) else {
            val shade = 1 - 0.2 * y / height
            rgb((40 * shade).toInt(), (200 * shade).toInt(), (60 * shade).toInt())
        }
    }

    private val assets = object : EffectAssets {
        override fun movie(effect: VideoEffectSettings): File = movie
    }

    /** The grey of the movie at second [time] of the effect's clock: the frame begun then, or the two of the overlap mixed. */
    private fun expected(time: Double): Int {
        val phase = EffectBackgroundLoop(duration(movie)).phase(time)
        fun frame(at: Double) = level(luma(minOf(FRAMES - 1, floor(at * 30 + 1e-3).toInt())))
        val incoming = frame(phase.incoming)
        val outgoing = phase.outgoing ?: return incoming
        return (EffectBackgroundLoop.dissolve(frame(outgoing) / 255.0, incoming / 255.0, phase.blend) * 255).roundToInt()
    }

    private fun case(name: String, kind: VideoEffectID, start: Double, time: Double): Case = Case(name) {
        var effect = VideoEffectSettings(kind, start = start)
            .with("background", EffectValue.Choice("Video")).with("media", EffectValue.Media(movie.name)).with("wrap", EffectValue.Number(0.0))
        if (kind == VideoEffectID.ChromaKey) effect = effect.copy(algorithm = 1).with("softness", EffectValue.Number(0.0))
        val stage = EffectStage(bench, listOf(effect), width, height, assets)
        try {
            val drawn = stage.frame(screen, (time * 1e6).roundToLong())
            val want = expected(EffectBackgroundLoop.time(effect, time))
            // The screen's corners, where the matte is nothing: the movie's frame as it is.
            val corners = listOf(drawn.at(1, 1), drawn.at(width - 2, 1), drawn.at(1, height / 8), drawn.at(width - 2, height / 8))
            val worst = corners.maxOf { c -> (0..2).maxOf { abs(((c shr (16 - 8 * it)) and 0xff) - want) } }
            note("at ${"%.3f".format(time)} s the movie's grey $want, drawn #%06x, worst $worst levels".format(corners.first()))
            check(worst <= ALLOWED) { "The background is $worst levels from the movie's frame there, grey $want" }
        } finally {
            stage.release()
        }
    }

    fun all(): List<Case> {
        val chroma = VideoEffectID.ChromaKey
        return listOf(
            case("Background movie: Chroma Key, half a second in", chroma, 0.0, 0.5),
            case("Background movie: Chroma Key, from the effect's start", chroma, 2.0, 2.3),
            // A second movie of 30 frames: the overlap dissolves its end into its start, then the second pass plays.
            case("Background movie: Chroma Key, the loop's overlap", chroma, 0.0, 0.88),
            case("Background movie: Chroma Key, the second pass", chroma, 0.0, 1.3),
        )
    }

    private companion object {
        const val FRAMES = 30
        const val MOVIE_WIDTH = 320
        const val MOVIE_HEIGHT = 192

        /** A coded grey, decoded, placed and drawn: a level of rounding at each step. */
        const val ALLOWED = 3
    }
}
