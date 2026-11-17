@file:OptIn(UnstableApi::class)

package app.takes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import app.takes.core.ColorGrade
import app.takes.core.ColorSettings
import app.takes.core.EffectBackgroundLoop
import app.takes.core.EffectBackgroundSound
import app.takes.core.TrackMixPlan
import app.takes.core.TrackTimeline
import app.takes.core.EffectColorMath
import app.takes.core.VideoEffectFormulasC
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.floor
import kotlin.math.roundToInt
import app.takes.core.Size as GeometrySize

/**
 * A Chroma Key's clean plate as the effects read it: the picture of the empty screen, upright, in the clip's grade
 * as the frames are ([picture], sRGB-encoded), for a recording standing [upright] at that size in pixels.
 */
class CleanPlate(val picture: Bitmap, val upright: GeometrySize)

/**
 * Clean plates read from their files, kept while they are used: at most [LONG_SIDE] pixels on the longer side, the
 * plate being blurred by 8 pixels at 1080 where it is used; through the clip's colour as the original passes the
 * plate through it (VideoEffectAssets.swift:492-512), the grade's table read between its points as the export reads it.
 */
internal object CleanPlates {
    private const val LONG_SIDE = 1280
    private val kept = ConcurrentHashMap<String, Result<CleanPlate?>>()
    private val working: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val background = Executors.newSingleThreadExecutor { Thread(it, "Clean plates").apply { isDaemon = true } }

    /**
     * The plate in [file] for a recording standing [upright], graded by [color] (an imported table in [folder]); null
     * when there is none, when its shape is not the recording's (VideoEffectCleanPlate.swift:27-30, Assets:497), or,
     * without [wait], until it is read, [ready] called then.
     */
    fun plate(file: File, upright: GeometrySize, color: ColorSettings?, folder: File?, wait: Boolean, ready: () -> Unit = {}): CleanPlate? {
        if (!file.isFile) return null
        val grade = color?.takeIf { !it.isIdentity }?.copy(id = "", motion = null)
        val key = listOf(file.path, file.lastModified(), upright.width, upright.height, grade, folder?.path).joinToString("|")
        kept[key]?.let { return it.getOrNull() }
        if (wait) return make(file, upright, grade, folder).also { keep(key, it) }
        if (working.add(key)) background.execute {
            try {
                keep(key, make(file, upright, grade, folder))
            } finally {
                working.remove(key)
            }
            ready()
        }
        return null
    }

    private fun keep(key: String, plate: CleanPlate?) {
        if (kept.size > 4) kept.clear()
        kept[key] = Result.success(plate)
    }

    private fun make(file: File, upright: GeometrySize, grade: ColorSettings?, folder: File?): CleanPlate? = try {
        decode(file, LONG_SIDE)?.let { picture ->
            if (!VideoEffectFormulasC.cleanPlateCompatible(GeometrySize(picture.width.toDouble(), picture.height.toDouble()), upright)) null
            else CleanPlate(if (grade == null) picture else graded(picture, grade, folder), upright)
        }
    } catch (problem: Exception) {
        android.util.Log.w("Takes", "clean plate ${file.name}", problem)
        null
    }

    /** [picture] through the table of [grade], read between its points. */
    private fun graded(picture: Bitmap, grade: ColorSettings, folder: File?): Bitmap {
        val table = ColorLuts.table(grade, folder ?: File("."))
        val n = ColorGrade.DIMENSION
        val last = (n - 1).toFloat()
        val pixels = IntArray(picture.width * picture.height)
        picture.getPixels(pixels, 0, picture.width, 0, 0, picture.width, picture.height)
        val out = FloatArray(3)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16 and 0xff) / 255f * last
            val g = (c shr 8 and 0xff) / 255f * last
            val b = (c and 0xff) / 255f * last
            val r0 = minOf(r.toInt(), n - 2)
            val g0 = minOf(g.toInt(), n - 2)
            val b0 = minOf(b.toInt(), n - 2)
            val fr = r - r0
            val fg = g - g0
            val fb = b - b0
            for (k in 0..2) {
                fun at(x: Int, y: Int, z: Int) = table[((z * n + y) * n + x) * 3 + k]
                val c00 = at(r0, g0, b0) + (at(r0 + 1, g0, b0) - at(r0, g0, b0)) * fr
                val c10 = at(r0, g0 + 1, b0) + (at(r0 + 1, g0 + 1, b0) - at(r0, g0 + 1, b0)) * fr
                val c01 = at(r0, g0, b0 + 1) + (at(r0 + 1, g0, b0 + 1) - at(r0, g0, b0 + 1)) * fr
                val c11 = at(r0, g0 + 1, b0 + 1) + (at(r0 + 1, g0 + 1, b0 + 1) - at(r0, g0 + 1, b0 + 1)) * fr
                val c0 = c00 + (c10 - c00) * fg
                val c1 = c01 + (c11 - c01) * fg
                out[k] = c0 + (c1 - c0) * fb
            }
            pixels[i] = (0xff shl 24) or (level(out[0]) shl 16) or (level(out[1]) shl 8) or level(out[2])
        }
        return Bitmap.createBitmap(pixels, picture.width, picture.height, Bitmap.Config.ARGB_8888)
    }

    private fun level(value: Float): Int = (value.coerceIn(0f, 1f) * 255 + .5f).toInt()

    /** A picture from its file, upright as the phone shows it, at most [longSide] pixels on its longer side. */
    fun decode(file: File, longSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longSide) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
        val turn = when (runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrNull()) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        return if (turn == 0f) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(turn) }, true)
    }

    /** The size of the picture in [file] as the phone shows it, upright; null when it is not one. */
    fun size(file: File): GeometrySize? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val turned = when (runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrNull()) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE -> true
            else -> false
        }
        return if (turned) GeometrySize(bounds.outHeight.toDouble(), bounds.outWidth.toDouble()) else GeometrySize(bounds.outWidth.toDouble(), bounds.outHeight.toDouble())
    }
}

/** A picture uploaded as a texture once while it is the same: rows from the bottom, as the chain's textures. */
internal class Upload {
    private var bitmap: Bitmap? = null
    private var texture = 0
    private var pixels = IntArray(0)
    private var bytes: ByteBuffer = ByteBuffer.allocateDirect(0)

    fun source(picture: Bitmap): EffectSource {
        if (picture !== bitmap || texture == 0) {
            if (texture == 0) texture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            val count = picture.width * picture.height
            if (bytes.capacity() != count * 4) bytes = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
            // Four bytes a pixel, R, G, B, A, as an int each read in the platform's order, rows turned over at once.
            if (pixels.size != count) pixels = IntArray(count)
            picture.getPixels(pixels, picture.width * (picture.height - 1), -picture.width, 0, 0, picture.width, picture.height)
            for (i in 0 until count) {
                val c = pixels[i]
                // ARGB to the bytes R, G, B, A in memory, whatever the platform's order.
                pixels[i] = if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
                    (0xff shl 24) or ((c and 0xff) shl 16) or (c and 0xff00) or ((c shr 16) and 0xff)
                } else {
                    ((c shr 16 and 0xff) shl 24) or ((c shr 8 and 0xff) shl 16) or ((c and 0xff) shl 8) or 0xff
                }
            }
            bytes.clear()
            bytes.asIntBuffer().put(pixels)
            bytes.position(0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            for (filter in listOf(GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_TEXTURE_MAG_FILTER)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, filter, GLES20.GL_LINEAR)
            for (wrap in listOf(GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_TEXTURE_WRAP_T)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, wrap, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, picture.width, picture.height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
            GlUtil.checkGlError()
            bitmap = picture
        }
        return EffectSource(texture, picture.width, picture.height, encoded = true)
    }

    fun release() {
        if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        texture = 0
        bitmap = null
    }
}

/**
 * The movies behind the subjects of Chroma Keys and Auto Backgrounds: each looped from its effect's start, its last
 * moments dissolved into its first ([EffectBackgroundLoop]), read in order by two decoders, the head and the tail, as
 * the original's `EffectBackgroundMovie` (VideoEffectAssets.swift:638-660). Frames are pictures upright at most
 * [longSide] pixels on their longer side. With [wait] (an export) a frame is read when it is asked for; without (the
 * preview, the recorder) in the background, the last one read given meanwhile and [ready] called when another is.
 */
internal class BackgroundMovies(private val wait: Boolean, private val longSide: Int, private val ready: (() -> Unit)? = null) : AutoCloseable {
    private val open = LinkedHashMap<String, BackgroundMovie?>()
    private val worker = if (wait) null else Executors.newSingleThreadExecutor { Thread(it, "Background movies").apply { isDaemon = true } }
    private val latest = ConcurrentHashMap<String, Bitmap>()
    private var wanted: Pair<File, Double>? = null
    private var done: Pair<File, Double>? = null
    private var working = false
    private var closed = false

    /** The frame of the movie in [file] at [time] seconds of the effect's clock; null before one is read, or when it cannot be. */
    fun frame(file: File, time: Double): Bitmap? {
        if (wait) return synchronized(this) { if (closed) null else movie(file)?.image(time) }
        synchronized(this) {
            wanted = file to time
            if (!working && !closed && wanted != done) {
                working = true
                worker!!.execute(::read)
            }
        }
        return latest[file.path]
    }

    private fun read() {
        while (true) {
            val asked = synchronized(this) {
                val next = wanted
                if (closed || next == null || next == done) {
                    working = false
                    return
                }
                next
            }
            val picture = try {
                movie(asked.first)?.image(asked.second)
            } catch (problem: Exception) {
                android.util.Log.w("Takes", "background movie ${asked.first.name}", problem)
                null
            }
            synchronized(this) { done = asked }
            if (picture != null && latest.put(asked.first.path, picture) !== picture) ready?.invoke()
        }
    }

    /** The movie of [file], opened once; at most two are kept open. */
    private fun movie(file: File): BackgroundMovie? {
        if (open.containsKey(file.path)) return open[file.path]
        while (open.size >= 2) open.remove(open.keys.first())?.close()
        val made = try {
            BackgroundMovie(file, longSide, reuse = wait)
        } catch (problem: Exception) {
            android.util.Log.w("Takes", "background movie ${file.name}", problem)
            null
        }
        open[file.path] = made
        return made
    }

    override fun close() {
        fun shut() = synchronized(this) {
            closed = true
            open.values.forEach { it?.close() }
            open.clear()
        }
        if (worker == null) shut() else {
            synchronized(this) { closed = true }
            worker.execute { shut() }
            worker.shutdown()
        }
    }
}

/** One movie behind a subject, looped: [EffectBackgroundLoop] over its length, two readers for the overlap. */
private class BackgroundMovie(private val file: File, private val longSide: Int, private val reuse: Boolean) : AutoCloseable {
    private val head = MovieReader(file, longSide, reuse)
    private var tail: MovieReader? = null
    // Over the asset's length, as its sound loops (VideoEffectAssets.swift:646).
    private val loop = EffectBackgroundLoop(movieLength(file).takeIf { it > 0.001 } ?: head.duration)
    private val blended = arrayOfNulls<Bitmap>(2)
    private var turn = 0

    fun image(time: Double): Bitmap? {
        val phase = loop.phase(time)
        val incoming = head.frame(phase.incoming) ?: return null
        val from = phase.outgoing ?: return incoming
        val outgoing = (tail ?: MovieReader(file, longSide, reuse).also { tail = it }).frame(from) ?: return incoming
        if (outgoing.width != incoming.width || outgoing.height != incoming.height) return incoming
        return dissolve(outgoing, incoming, phase.blend)
    }

    /** CIDissolveTransition from [from] to [to] by [blend], in linear light (VideoEffectAssets.swift:654-656). */
    private fun dissolve(from: Bitmap, to: Bitmap, blend: Double): Bitmap {
        val count = from.width * from.height
        val a = IntArray(count).also { from.getPixels(it, 0, from.width, 0, 0, from.width, from.height) }
        val b = IntArray(count).also { to.getPixels(it, 0, to.width, 0, 0, to.width, to.height) }
        val t = blend.toFloat()
        for (i in 0 until count) {
            var c = 0xff shl 24
            for (shift in intArrayOf(16, 8, 0)) {
                val x = LINEAR[a[i] shr shift and 0xff] * (1 - t) + LINEAR[b[i] shr shift and 0xff] * t
                c = c or (ENCODED[(x.coerceIn(0f, 1f) * (ENCODED.size - 1)).roundToInt()] shl shift)
            }
            a[i] = c
        }
        val target = if (reuse) {
            turn = 1 - turn
            blended[turn]?.takeIf { it.width == from.width && it.height == from.height } ?: Bitmap.createBitmap(from.width, from.height, Bitmap.Config.ARGB_8888).also { blended[turn] = it }
        } else {
            Bitmap.createBitmap(from.width, from.height, Bitmap.Config.ARGB_8888)
        }
        target.setPixels(a, 0, from.width, 0, 0, from.width, from.height)
        return target
    }

    override fun close() {
        head.close()
        tail?.close()
    }

    private companion object {
        val LINEAR = FloatArray(256) { EffectColorMath.linear(it / 255.0).toFloat() }
        val ENCODED = IntArray(4096) { (EffectColorMath.encoded(it / 4095.0) * 255 + .5).toInt().coerceIn(0, 255) }
    }
}

/**
 * A movie's video decoded in order with MediaCodec, read by time: the frame shown at a second is the last one that
 * begins at or before it, the first before that. Going back, or more than two seconds ahead, seeks to the key frame
 * before. Each frame is turned upright and made at most [longSide] pixels on its longer side; with [reuse] two
 * pictures take turns, for a reader whose frames are used before the next is asked for.
 */
private class MovieReader(file: File, private val longSide: Int, private val reuse: Boolean) : AutoCloseable {
    private val extractor = MediaExtractor().apply { setDataSource(file.path) }
    private val track = (0 until extractor.trackCount).firstOrNull {
        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
    } ?: run { extractor.release(); throw IllegalArgumentException("no video in ${file.name}") }
    private val format: MediaFormat = extractor.getTrackFormat(track).also { extractor.selectTrack(track) }

    val duration: Double
    private val rotation: Int
    private val full = format.containsKey(MediaFormat.KEY_COLOR_RANGE) && format.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
    private val kr: Float
    private val kb: Float
    private val codec: MediaCodec

    init {
        val retriever = MediaMetadataRetriever()
        var degrees = 0
        var length = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1e6 else 0.0
        try {
            retriever.setDataSource(file.path)
            degrees = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION)
            else retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (length <= 0) length = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0) / 1000
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }
        rotation = ((degrees / 90.0).roundToInt() * 90 % 360 + 360) % 360
        if (!(length > 0.001)) {
            extractor.release()
            throw IllegalArgumentException("no length in ${file.name}")
        }
        duration = length
        val standard = if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) format.getInteger(MediaFormat.KEY_COLOR_STANDARD) else 0
        when (standard) {
            MediaFormat.COLOR_STANDARD_BT601_PAL, MediaFormat.COLOR_STANDARD_BT601_NTSC -> { kr = .299f; kb = .114f }
            MediaFormat.COLOR_STANDARD_BT2020 -> { kr = .2627f; kb = .0593f }
            else -> { kr = .2126f; kb = .0722f }
        }
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        try {
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (problem: Exception) {
            codec.release()
            extractor.release()
            throw problem
        }
    }

    private class Held(val index: Int, val time: Double)

    private val info = MediaCodec.BufferInfo()
    private var fed = false
    private var finished = false
    private var started = false
    // The last frame at or before the time asked for, not yet made into a picture, and the first one after it.
    private var candidate: Held? = null
    private var ahead: Held? = null
    private var shown: Bitmap? = null
    private var shownTime = Double.NEGATIVE_INFINITY
    private val pictures = arrayOfNulls<Bitmap>(2)
    private var turn = 0
    private var colours = IntArray(0)

    fun frame(time: Double): Bitmap? {
        val t = time + 1e-4
        if (!started && time > 2.0 || started && (t < shownTime - 1e-6 || t > shownTime + 2.0 && (ahead?.time ?: Double.MAX_VALUE) <= t)) seek(time)
        started = true
        var idle = 0
        while (true) {
            val next = ahead
            if (next != null) {
                if (next.time > t) break
                candidate?.let { codec.releaseOutputBuffer(it.index, false) }
                candidate = next
                ahead = null
                continue
            }
            if (finished) break
            feed()
            val index = codec.dequeueOutputBuffer(info, 10_000)
            if (index >= 0) {
                idle = 0
                val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.size > 0) ahead = Held(index, info.presentationTimeUs / 1e6) else codec.releaseOutputBuffer(index, false)
                if (end) finished = true
            } else if (++idle > 300) {
                break
            }
        }
        candidate?.let {
            picture(it)
            codec.releaseOutputBuffer(it.index, false)
            candidate = null
        }
        // Before the first frame: the first frame.
        if (shown == null) ahead?.let { picture(it) }
        return shown
    }

    private fun feed() {
        while (!fed) {
            val slot = codec.dequeueInputBuffer(0)
            if (slot < 0) return
            val buffer = codec.getInputBuffer(slot) ?: return
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) {
                codec.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                fed = true
            } else {
                codec.queueInputBuffer(slot, 0, size, extractor.sampleTime, 0)
                extractor.advance()
            }
        }
    }

    private fun seek(time: Double) {
        codec.flush()
        candidate = null
        ahead = null
        fed = false
        finished = false
        shown = null
        shownTime = Double.NEGATIVE_INFINITY
        extractor.seekTo((time * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    }

    /** Makes the frame of [held] the one shown. */
    private fun picture(held: Held) {
        val image = codec.getOutputImage(held.index) ?: return
        shown = image.use { convert(it) }
        shownTime = held.time
    }

    /** The frame upright, at most [longSide] on its longer side: Y′CbCr to R′G′B′ by the track's matrix and range. */
    private fun convert(image: Image): Bitmap {
        val crop = image.cropRect
        val sw = crop.width()
        val sh = crop.height()
        val turned = rotation % 180 != 0
        val uw = if (turned) sh else sw
        val uh = if (turned) sw else sh
        val scale = minOf(1.0, longSide.toDouble() / maxOf(uw, uh))
        val ow = maxOf(1, (uw * scale).roundToInt())
        val oh = maxOf(1, (uh * scale).roundToInt())
        val planes = image.planes
        val y = bytes(planes[0].buffer, 0)
        val u = bytes(planes[1].buffer, 1)
        val v = bytes(planes[2].buffer, 2)
        val yRow = planes[0].rowStride
        val yPixel = planes[0].pixelStride
        val uRow = planes[1].rowStride
        val uPixel = planes[1].pixelStride
        val vRow = planes[2].rowStride
        val vPixel = planes[2].pixelStride
        if (colours.size != ow * oh) colours = IntArray(ow * oh)
        val gr = 2 * (1 - kr)
        val gb = 2 * (1 - kb)
        val kg = 1 - kr - kb
        for (oy in 0 until oh) {
            val uy = (oy + .5) / oh * uh
            for (ox in 0 until ow) {
                val ux = (ox + .5) / ow * uw
                // From the upright picture back to the stored one, which turns [rotation] degrees clockwise to stand.
                val sx: Double
                val sy: Double
                when (rotation) {
                    90 -> { sx = uy; sy = sh - ux }
                    180 -> { sx = sw - ux; sy = sh - uy }
                    270 -> { sx = sw - uy; sy = ux }
                    else -> { sx = ux; sy = uy }
                }
                val ix = floor(sx).toInt().coerceIn(0, sw - 1) + crop.left
                val iy = floor(sy).toInt().coerceIn(0, sh - 1) + crop.top
                val luma = y[iy * yRow + ix * yPixel].toInt() and 0xff
                val cb = u[(iy / 2) * uRow + (ix / 2) * uPixel].toInt() and 0xff
                val cr = v[(iy / 2) * vRow + (ix / 2) * vPixel].toInt() and 0xff
                val l = if (full) luma / 255f else (luma - 16) / 219f
                val pb = if (full) (cb - 128) / 255f else (cb - 128) / 224f
                val pr = if (full) (cr - 128) / 255f else (cr - 128) / 224f
                val r = l + gr * pr
                val b = l + gb * pb
                val g = (l - kr * r - kb * b) / kg
                colours[oy * ow + ox] = (0xff shl 24) or (level(r) shl 16) or (level(g) shl 8) or level(b)
            }
        }
        val target = if (reuse) {
            turn = 1 - turn
            pictures[turn]?.takeIf { it.width == ow && it.height == oh } ?: Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888).also { pictures[turn] = it }
        } else {
            Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        }
        target.setPixels(colours, 0, ow, 0, 0, ow, oh)
        return target
    }

    private fun level(value: Float): Int = (value.coerceIn(0f, 1f) * 255 + .5f).toInt()

    private val planeBytes = arrayOfNulls<ByteArray>(3)

    /** The bytes of a plane, copied in one go: reading a direct buffer byte by byte is several times slower. */
    private fun bytes(buffer: ByteBuffer, plane: Int): ByteArray {
        val source = buffer.duplicate().apply { rewind() }
        val bytes = planeBytes[plane]?.takeIf { it.size >= source.remaining() } ?: ByteArray(source.remaining()).also { planeBytes[plane] = it }
        source.get(bytes, 0, source.remaining())
        return bytes
    }

    override fun close() {
        runCatching { codec.stop() }
        codec.release()
        extractor.release()
    }
}

/**
 * How long a movie is as the original's asset says (`AVAsset.duration`, VideoEffectAssets.swift:646,
 * VideoEffectBackgroundAudio.swift:53): its longest track, else the container's length; 0 when unreadable.
 */
internal fun movieLength(file: File): Double {
    var length = 0.0
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.path)
        for (track in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(track)
            if (format.containsKey(MediaFormat.KEY_DURATION)) length = maxOf(length, format.getLong(MediaFormat.KEY_DURATION) / 1e6)
        }
    } catch (_: Exception) {
    } finally {
        runCatching { extractor.release() }
    }
    if (length > 0) return length
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.path)
        (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0) / 1000
    } catch (_: Exception) {
        0.0
    } finally {
        runCatching { retriever.release() }
    }
}

/**
 * The sound of the effects' background movies in the mix ([EffectBackgroundSound]): which movies of the project's
 * folder have sound, read once and kept, and the files the mix reads them from.
 */
internal object BackgroundSounds {
    private val kept = ConcurrentHashMap<String, Result<EffectBackgroundSound.Movie?>>()

    /** The movies of [names] in [folder] that have sound, read from their files: call off the main thread. */
    fun movies(folder: File, names: Set<String>): Map<String, EffectBackgroundSound.Movie> =
        names.mapNotNull { name -> movie(File(folder, name))?.let { name to it } }.toMap()

    /** The movies the mix of [timeline] may play from [folder] ([movies]): call off the main thread. */
    fun movies(folder: File, timeline: TrackTimeline): Map<String, EffectBackgroundSound.Movie> = movies(folder, EffectBackgroundSound.files(timeline))

    /** [files] and the background movies of [movies] in [folder] under the sources the mix reads them by. */
    fun files(files: Map<String, File>, folder: File, movies: Map<String, EffectBackgroundSound.Movie>): Map<String, File> =
        if (movies.isEmpty()) files else files + movies.keys.associate { EffectBackgroundSound.sourceID(it) to File(folder, it) }

    /** The mix of [timeline] with its background movies' sound, and the files it reads: call off the main thread. */
    fun mix(base: TrackMixPlan, timeline: TrackTimeline, files: Map<String, File>, folder: File): Pair<TrackMixPlan, Map<String, File>> {
        val movies = movies(folder, timeline)
        return EffectBackgroundSound.plan(base, timeline, movies) to files(files, folder, movies)
    }

    /** A movie's length and the stretch its sound covers (the sound track's own length from 0); null without sound. */
    private fun movie(file: File): EffectBackgroundSound.Movie? {
        if (!file.isFile) return null
        val key = "${file.path}:${file.lastModified()}:${file.length()}"
        kept[key]?.let { return it.getOrNull() }
        val movie = try {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.path)
                val sound = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                val length = movieLength(file)
                if (sound == null || !(length > 0.001)) null else {
                    val end = if (sound.containsKey(MediaFormat.KEY_DURATION)) sound.getLong(MediaFormat.KEY_DURATION) / 1e6 else length
                    EffectBackgroundSound.Movie(length, 0.0, end)
                }
            } finally {
                runCatching { extractor.release() }
            }
        } catch (problem: Exception) {
            android.util.Log.w("Takes", "background sound of ${file.name}", problem)
            null
        }
        if (kept.size > 16) kept.clear()
        kept[key] = Result.success(movie)
        return movie
    }
}
