package app.takes

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import app.takes.core.PersonMaskChunks
import app.takes.core.SourceMedia
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.TensorFlowLite
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * The person masks of a recording, which stand in for the original's Vision analysis
 * (YouRec/VideoEffects/VideoEffectCaptureAnalysis.swift:312-397, VideoEffectAssets.swift:239-301) for Auto
 * Background, Color Isolation's subject, Chroma Key's person hint and Quantization's face detail.
 *
 * The frames are decoded in order with MediaCodec, a ten-second chunk of source time at a time, and each is given
 * upright to MediaPipe's selfie segmenter on LiteRT's CPU (docs/SEGMENTATION-PROBE.md). The raw masks are stored
 * by [PersonMaskChunks] in the project's `Cache/masks/<key>/` and read back by source time with [reader].
 *
 * A mask is stored in the coordinates of the decoded frame, before its rotation, as the original's Vision provider
 * returns it (VideoEffectAssets.swift:270-276), so that it is laid on the frame as it comes from the decoder and the
 * frame's rotation is applied once, later, to both. It is the model's own [SIDE] × [SIDE] samples, only turned back:
 * they span the whole visible frame, stretched as the model saw it, so the mask is read with the frame's own
 * normalised coordinates. Resampled to the frame's shape it would hold nothing more (the model sees 256 × 256) and,
 * measured on the A54, take 16 KB a frame at 512 × 288 against 6 KB here.
 */
object PersonMasks {
    /** The model, in the app's assets: provenance in res/raw/credits.txt. */
    const val MODEL = "selfie_standard.tflite"

    /** The side of the model's square input and output, and of a stored mask. */
    const val SIDE = 256

    /** How the model is run, part of the masks' key. */
    const val COMPUTE = "litert-1.4.2-cpu-4"

    private const val THREADS = 4

    /**
     * Whether this phone can make masks. LiteRT's native library is not there for every processor the app installs
     * on, and the four effects that need a mask are hidden where it is missing. The library is loaded once, here.
     */
    fun available(): Boolean = runtime

    private val runtime: Boolean by lazy {
        try {
            TensorFlowLite.init()
            true
        } catch (_: LinkageError) {
            false // no native library for this processor
        } catch (_: Exception) {
            false
        }
    }

    /** The masks found or made: the [chunks] of [directory] that hold masks; [complete] when every one asked for does. */
    class Analysis(val directory: File, val chunks: List<Int>, val complete: Boolean)

    private class Model(val buffer: MappedByteBuffer, val revision: String)

    @Volatile private var model: Model? = null

    private fun model(context: Context): Model = model ?: synchronized(this) {
        model ?: context.assets.openFd(MODEL).use { asset ->
            // Mapped where it lies in the APK, which stores it uncompressed (noCompress in build.gradle.kts).
            val buffer = FileInputStream(asset.fileDescriptor).channel.use { it.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.declaredLength) }
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(buffer.duplicate())
            Model(buffer, digest.digest().joinToString("") { "%02x".format(it) })
        }.also { model = it }
    }

    /** The folder of the masks of [source], in the project folder [folder]. */
    fun directory(context: Context, source: SourceMedia, folder: File): File =
        PersonMaskChunks.directory(folder, PersonMaskChunks.key(source, model(context).revision, COMPUTE))

    /** Reads the masks of [source] by source time; a chunk not analysed yet gives null. Needs no native library. */
    fun reader(context: Context, source: SourceMedia, folder: File): PersonMaskChunks.Reader =
        PersonMaskChunks.Reader(directory(context, source, folder))

    private val claims = ConcurrentHashMap<String, Any>()

    /**
     * Makes the masks of [source] (in the project folder [folder]) from [from] to [to] seconds, the whole recording
     * by default, chunk by chunk; chunks already on disk are kept. Blocks: call it off the main thread. With
     * [generate] false nothing is made, and the result says which chunks are there (:311-397).
     *
     * [progress] goes from 0 to 1. [cancel] is asked at every frame; when it says so the work stops with a
     * [CancellationException] and the chunk being made is not written. [frame] sees every mask made, the
     * warm-up's too (its second argument false): the frames of the two seconds before a chunk are segmented only
     * when it is given, for a filter over time that needs them to settle (:325).
     */
    fun analyse(
        context: Context, source: SourceMedia, folder: File, from: Double? = null, to: Double? = null, generate: Boolean = true,
        progress: (Float) -> Unit = {}, cancel: () -> Boolean = { false },
        frame: ((PersonMaskChunks.Mask, Boolean) -> Unit)? = null,
    ): Analysis {
        val directory = directory(context, source, folder)
        val wanted = PersonMaskChunks.chunks(source.duration, from, to)
        val made = mutableListOf<Int>()
        var complete = true
        var segmenter: Segmenter? = null
        var movie: Movie? = null
        try {
            wanted.forEachIndexed { done, index ->
                if (cancel()) throw CancellationException()
                val file = PersonMaskChunks.file(directory, index)
                // Two analyses of the same chunk (the preview's and the export's) make it once.
                synchronized(claims.computeIfAbsent(file.path) { Any() }) {
                    PersonMaskChunks.open(file)?.use { made += index; return@forEachIndexed }
                    if (!generate) {
                        complete = false
                        return@forEachIndexed
                    }
                    check(available()) { "LiteRT has no native library for this processor" }
                    val opened = movie ?: Movie(File(folder, source.filename)).also { movie = it }
                    val model = segmenter ?: Segmenter(model(context).buffer).also { segmenter = it }
                    val span = PersonMaskChunks.span(index, source.duration)
                    val writer = opened.read(span, frame != null, model, cancel, frame) { part -> progress((done + part) / wanted.count().toFloat()) }
                    if (writer == null || writer.count == 0) throw IllegalStateException("no frames in ${span.start}…${span.end} s of ${source.filename}")
                    writer.write(file)
                    made += index
                }
            }
        } finally {
            segmenter?.close()
            movie?.close()
        }
        progress(1f)
        return Analysis(directory, made, complete)
    }

    /** The selfie segmenter: an upright 256 × 256 RGB frame in 0…1 to the person's probability at each pixel. */
    private class Segmenter(model: ByteBuffer) : AutoCloseable {
        private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(THREADS))
        val input: ByteBuffer = ByteBuffer.allocateDirect(SIDE * SIDE * 3 * 4).order(ByteOrder.nativeOrder())
        private val output = ByteBuffer.allocateDirect(SIDE * SIDE * 4).order(ByteOrder.nativeOrder())
        val alpha = FloatArray(SIDE * SIDE)

        /** Runs the model on [input], into [alpha]. */
        fun run() {
            input.rewind()
            output.rewind()
            interpreter.run(input, output)
            output.rewind()
            output.asFloatBuffer().get(alpha)
        }

        override fun close() = interpreter.close()
    }

    /** One recording's video track, decoded a chunk at a time. */
    private class Movie(file: File) : AutoCloseable {
        private val extractor = MediaExtractor().apply { setDataSource(file.path) }
        private val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: throw IllegalArgumentException("no video in ${file.name}")
        private val format: MediaFormat = extractor.getTrackFormat(track).also { extractor.selectTrack(track) }

        /** Clockwise degrees that turn a stored frame upright. */
        private val rotation: Int = run {
            val degrees = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else
                MediaMetadataRetriever().run {
                    try {
                        setDataSource(file.path)
                        extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    } catch (_: Exception) {
                        0
                    } finally {
                        release()
                    }
                }
            ((degrees / 90.0).roundToInt() * 90 % 360 + 360) % 360
        }

        // Y′CbCr to R′G′B′ by the matrix and range the track declares; BT.709 limited range when it says nothing.
        private val full = format.containsKey(MediaFormat.KEY_COLOR_RANGE) && format.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
        private val kr: Float
        private val kb: Float

        init {
            val standard = if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) format.getInteger(MediaFormat.KEY_COLOR_STANDARD) else 0
            when (standard) {
                MediaFormat.COLOR_STANDARD_BT601_PAL, MediaFormat.COLOR_STANDARD_BT601_NTSC -> { kr = .299f; kb = .114f }
                MediaFormat.COLOR_STANDARD_BT2020 -> { kr = .2627f; kb = .0593f }
                else -> { kr = .2126f; kb = .0722f }
            }
        }

        /**
         * Decodes the frames of [span], from its warm-up when [warm], segments each and keeps those of the chunk.
         * Null when the track has no frame there.
         */
        fun read(
            span: PersonMaskChunks.Span, warm: Boolean, segmenter: Segmenter, cancel: () -> Boolean,
            frame: ((PersonMaskChunks.Mask, Boolean) -> Unit)?, progress: (Float) -> Unit,
        ): PersonMaskChunks.Writer? {
            val begin = if (warm) span.warm else span.start
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            var writer: PersonMaskChunks.Writer? = null
            try {
                // The track's format is this reader's own copy: it asks the decoder for frames it can read as YUV planes.
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                codec.configure(format, null, null, 0)
                codec.start()
                extractor.seekTo((begin * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val info = MediaCodec.BufferInfo()
                // Frames later in time can come earlier in the file: read a little past the end of the chunk.
                val lastSample = ((span.end + 1.0) * 1e6).toLong()
                var fed = false
                var last = -1.0
                while (true) {
                    if (cancel()) throw CancellationException()
                    // Every free input slot is filled before waiting for a frame, so the decoder works while the model does.
                    while (!fed) {
                        val slot = codec.dequeueInputBuffer(0)
                        if (slot < 0) break
                        val buffer = codec.getInputBuffer(slot)!!
                        val size = extractor.readSampleData(buffer, 0)
                        val time = extractor.sampleTime
                        if (size < 0 || time > lastSample) {
                            codec.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            fed = true
                        } else {
                            codec.queueInputBuffer(slot, 0, size, time, 0)
                            extractor.advance()
                        }
                    }
                    val out = codec.dequeueOutputBuffer(info, 5_000)
                    if (out < 0) continue
                    val time = info.presentationTimeUs / 1e6
                    val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || time >= span.end
                    if (info.size > 0 && !end && time + 0.000001 >= begin && time > last) {
                        val image = codec.getOutputImage(out)
                        if (image != null) {
                            val rows = image.use { segment(it, segmenter) }
                            val kept = span.keeps(time)
                            val mask = PersonMaskChunks.Mask(time, SIDE, SIDE, rows)
                            frame?.invoke(mask, kept)
                            if (kept) {
                                (writer ?: PersonMaskChunks.Writer(span, SIDE, SIDE).also { writer = it }).append(time, rows)
                            }
                            last = time
                            progress(((time - span.start) / (span.end - span.start)).toFloat().coerceIn(0f, 1f))
                        }
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (end) break
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            return writer
        }


        /** The mask of [image], in its own coordinates: [SIDE] × [SIDE] over its visible part. */
        private fun segment(image: Image, segmenter: Segmenter): ByteArray {
            val crop = image.cropRect
            val planes = image.planes
            val y = planes[0]
            val u = planes[1]
            val v = planes[2]
            // 10-bit frames come as 16-bit samples (P010), their high byte last: that byte is read.
            val high = if (y.pixelStride == 2) 1 else 0
            val key = longArrayOf(crop.left.toLong(), crop.top.toLong(), crop.width().toLong(), crop.height().toLong(),
                y.rowStride.toLong(), y.pixelStride.toLong(), u.rowStride.toLong(), u.pixelStride.toLong(), v.rowStride.toLong(), v.pixelStride.toLong())
            if (!key.contentEquals(layout)) {
                // Where each of the model's samples falls on the stored frame: three by three luma taps over its footprint, one chroma tap.
                for (g in 0 until SIDE) {
                    for (k in 0 until 3) {
                        val f = (g + (2 * k + 1) / 6.0) / SIDE
                        val x = (crop.left + f * crop.width()).toInt().coerceIn(crop.left, crop.right - 1)
                        val row = (crop.top + f * crop.height()).toInt().coerceIn(crop.top, crop.bottom - 1)
                        lumaColumn[g * 3 + k] = x * y.pixelStride
                        lumaRow[g * 3 + k] = row * y.rowStride + high
                    }
                    val x = (crop.left + (g + .5) / SIDE * crop.width()).toInt().coerceIn(crop.left, crop.right - 1) / 2
                    val row = (crop.top + (g + .5) / SIDE * crop.height()).toInt().coerceIn(crop.top, crop.bottom - 1) / 2
                    uColumn[g] = x * u.pixelStride
                    uRow[g] = row * u.rowStride + high
                    vColumn[g] = x * v.pixelStride
                    vRow[g] = row * v.rowStride + high
                }
                layout = key
            }
            val yBytes = copy(y.buffer, 0)
            val uBytes = copy(u.buffer, 1)
            val vBytes = copy(v.buffer, 2)
            val kg = 1f - kr - kb
            val scale = if (full) 1f / 255f else 1f / 219f
            val offset = if (full) 0f else 16f
            val chroma = if (full) 1f / 255f else 1f / 224f
            // Sample (gx, gy) of the stored frame's grid is where the upright frame has it: at a + gx·dx + gy·dy.
            val (a, dx, dy) = turn
            val rgb = pixels
            for (gy in 0 until SIDE) {
                val r0 = lumaRow[gy * 3]
                val r1 = lumaRow[gy * 3 + 1]
                val r2 = lumaRow[gy * 3 + 2]
                val ur = uRow[gy]
                val vr = vRow[gy]
                for (gx in 0 until SIDE) {
                    val c0 = lumaColumn[gx * 3]
                    val c1 = lumaColumn[gx * 3 + 1]
                    val c2 = lumaColumn[gx * 3 + 2]
                    val luma = (yBytes[r0 + c0].toInt() and 255) + (yBytes[r0 + c1].toInt() and 255) + (yBytes[r0 + c2].toInt() and 255) +
                        (yBytes[r1 + c0].toInt() and 255) + (yBytes[r1 + c1].toInt() and 255) + (yBytes[r1 + c2].toInt() and 255) +
                        (yBytes[r2 + c0].toInt() and 255) + (yBytes[r2 + c1].toInt() and 255) + (yBytes[r2 + c2].toInt() and 255)
                    val l = (luma / 9f - offset) * scale
                    val pb = ((uBytes[ur + uColumn[gx]].toInt() and 255) - 128) * chroma
                    val pr = ((vBytes[vr + vColumn[gx]].toInt() and 255) - 128) * chroma
                    val r = l + 2 * (1 - kr) * pr
                    val b = l + 2 * (1 - kb) * pb
                    val g = (l - kr * r - kb * b) / kg
                    val at = 3 * (a + gx * dx + gy * dy)
                    rgb[at] = r.coerceIn(0f, 1f)
                    rgb[at + 1] = g.coerceIn(0f, 1f)
                    rgb[at + 2] = b.coerceIn(0f, 1f)
                }
            }
            segmenter.input.rewind()
            segmenter.input.asFloatBuffer().put(rgb)
            segmenter.run()
            // Back to the stored frame's grid, as bytes.
            val alpha = segmenter.alpha
            val rows = ByteArray(SIDE * SIDE)
            for (gy in 0 until SIDE) for (gx in 0 until SIDE) {
                rows[gy * SIDE + gx] = (alpha[a + gx * dx + gy * dy].coerceIn(0f, 1f) * 255f + .5f).toInt().toByte()
            }
            return rows
        }

        // The upright frame's index of the stored frame's sample (gx, gy) is turn.first + gx·turn.second + gy·turn.third.
        private val turn: Triple<Int, Int, Int> = when (rotation) {
            90 -> Triple(SIDE - 1, SIDE, -1)
            180 -> Triple(SIDE * SIDE - 1, -1, -SIDE)
            270 -> Triple((SIDE - 1) * SIDE, -SIDE, 1)
            else -> Triple(0, 1, SIDE)
        }
        private var layout = LongArray(0)
        private val lumaColumn = IntArray(SIDE * 3)
        private val lumaRow = IntArray(SIDE * 3)
        private val uColumn = IntArray(SIDE)
        private val uRow = IntArray(SIDE)
        private val vColumn = IntArray(SIDE)
        private val vRow = IntArray(SIDE)
        private val pixels = FloatArray(SIDE * SIDE * 3)
        private val planes = arrayOfNulls<ByteArray>(3)

        /** The bytes of a plane, copied in one go: reading a direct buffer byte by byte is several times slower. */
        private fun copy(buffer: ByteBuffer, plane: Int): ByteArray {
            val source = buffer.duplicate().apply { rewind() }
            val bytes = planes[plane]?.takeIf { it.size >= source.remaining() } ?: ByteArray(source.remaining()).also { planes[plane] = it }
            source.get(bytes, 0, source.remaining())
            return bytes
        }

        override fun close() = extractor.release()
    }
}
