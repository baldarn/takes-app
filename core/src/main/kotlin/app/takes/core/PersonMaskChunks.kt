package app.takes.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The person masks of a recording on disk, as the original's mask analysis keeps them
 * (YouRec/VideoEffects/VideoEffectCaptureAnalysis.swift:312-397, read by SourcePersonMaskReader at :440-529):
 * fixed ten-second chunks of source time, each analysed on its own after the same two-second warm-up, so a
 * long recording is analysed only where it is used and in any order.
 *
 * Each chunk is one file, `c<index>.masks`, in a folder of the project's `Cache/` named by [key]. The file holds
 * the raw masks (the model's person probability, not smoothed) as 8-bit rows, each frame compressed on its own
 * so that any one can be read without the others: Deflate stands in for the original's LZFSE (:111-112). The
 * original keeps the same in two files, a JSON index and a data file (:54-160); one file is written whole and
 * renamed into place, so a reader sees a chunk complete or not at all.
 *
 * A mask is in the coordinates of the recording's stored frames, before the rotation that turns them upright,
 * as the original's are (VideoEffectAssets.swift:270-276): it is laid on the decoded frame as it is, and the
 * frame's rotation, crop and mirror are applied once, later, to both.
 */
object PersonMaskChunks {
    /** Seconds of source time in a chunk (VideoEffectCaptureAnalysis.swift:317,325). */
    const val SECONDS = 10.0

    /** Seconds read before a chunk so that a temporal filter has settled at its start (:325, :500). */
    const val WARM_UP = 2.0

    /** Of the stored format and the analysis behind it: a change makes every chunk be made again. */
    const val VERSION = 1

    /** The largest side a stored mask may have (:177, :255). */
    const val MAXIMUM_SIDE = 1024

    private const val MAGIC = 0x544B504D // "TKPM"
    private const val FORMAT = 1
    private const val MAXIMUM_FRAMES = 2_000_000
    private const val MAXIMUM_BYTES = 2_000_000

    /** A raw person mask: [rows] holds [width] × [height] bytes, row by row, 0 background to 255 person. */
    class Mask(val time: Double, val width: Int, val height: Int, val rows: ByteArray) {
        /** The person's probability at pixel ([x], [y]), 0 to 1. */
        fun alpha(x: Int, y: Int): Float = (rows[y * width + x].toInt() and 255) / 255f
    }

    /** The chunk that holds source time [time] (:317, :481). */
    fun chunk(time: Double): Int = max(0, floor(time / SECONDS).toInt())

    /**
     * The source seconds of chunk [index] of a recording of [duration] seconds: its masks are of the frames in
     * [start, end), and its frames are read from [warm] (:325).
     */
    data class Span(val index: Int, val warm: Double, val start: Double, val end: Double) {
        /** Whether a frame at [time] is one whose mask the chunk keeps (:356). */
        fun keeps(time: Double): Boolean = time + 0.000001 >= start && time < end
    }

    fun span(index: Int, duration: Double): Span {
        val start = index * SECONDS
        return Span(index, max(0.0, start - WARM_UP), start, min(duration, start + SECONDS))
    }

    /** The chunks that cover [from] to [to] of a recording of [duration] seconds, the whole of it by default (:316-317). */
    fun chunks(duration: Double, from: Double? = null, to: Double? = null): IntRange {
        val lower = max(0.0, from ?: 0.0)
        val upper = min(duration, to ?: duration)
        val first = max(0, floor(lower / SECONDS).toInt())
        val last = max(first, floor(max(lower, upper - 0.000001) / SECONDS).toInt())
        return first..last
    }

    /**
     * The name of the folder of a recording's masks: the SHA-256 of what they depend on, as the original names
     * its own (:315-319). [model] names the model (its file's SHA-256) and [compute] how it was run; [captured]
     * is the analysis recorded with the take, which the original's key also holds and Takes does not make yet.
     *
     * The original keys the whole SourceMedia, effects included, so every change to an effect of the clip
     * analysed it again; the raw masks depend only on the recording, so only what names the recording is kept.
     */
    fun key(source: SourceMedia, model: String, compute: String, captured: String? = null): String {
        // Keys in order, as the original's encoder sorts them.
        val json = buildJsonObject {
            put("captured", captured?.let(::JsonPrimitive) ?: JsonNull)
            put("compute", JsonPrimitive(compute))
            put("model", JsonPrimitive(model))
            put("source", buildJsonObject {
                put("duration", JsonPrimitive(source.duration))
                put("filename", JsonPrimitive(source.filename))
                put("id", JsonPrimitive(source.id))
                put("sha256", source.sha256?.let(::JsonPrimitive) ?: JsonNull)
            })
            put("version", JsonPrimitive(VERSION))
        }
        return sha256(json.toString().toByteArray())
    }

    /** The folder of the masks named [key] in the project folder [project]: `Cache/masks/<key>` (:319). */
    fun directory(project: File, key: String): File = File(File(File(project, "Cache"), "masks"), key)

    /** The file of chunk [index] in [directory]. */
    fun file(directory: File, index: Int): File = File(directory, "c$index.masks")

    /** Collects the masks of one chunk and writes them in one go. */
    class Writer(val span: Span, val width: Int, val height: Int) {
        init {
            require(width in 1..MAXIMUM_SIDE && height in 1..MAXIMUM_SIDE) { "mask of $width × $height" }
        }

        private val times = mutableListOf<Double>()
        private val sizes = mutableListOf<Int>()
        private val data = ByteArrayOutputStream()
        private val deflater = Deflater(Deflater.BEST_SPEED)
        private val buffer = ByteArray(1 shl 16)

        val count: Int get() = times.size

        /** Adds the mask of the frame at [time], later than the last one; [rows] is [width] × [height] bytes. */
        fun append(time: Double, rows: ByteArray) {
            require(rows.size == width * height) { "${rows.size} bytes for $width × $height" }
            require(time.isFinite() && time >= 0 && time > (times.lastOrNull() ?: -1.0)) { "frame at $time after ${times.lastOrNull()}" }
            deflater.reset()
            deflater.setInput(rows)
            deflater.finish()
            var written = 0
            while (!deflater.finished()) {
                val n = deflater.deflate(buffer)
                data.write(buffer, 0, n)
                written += n
            }
            check(written in 1 until MAXIMUM_BYTES) { "mask of $written bytes" }
            times += time
            sizes += written
        }

        /** The whole file. */
        fun bytes(): ByteArray {
            val out = ByteArrayOutputStream(64 + 12 * times.size + data.size())
            DataOutputStream(out).apply {
                writeInt(MAGIC)
                writeInt(FORMAT)
                writeInt(span.index)
                writeDouble(span.start)
                writeDouble(span.end)
                writeInt(width)
                writeInt(height)
                writeInt(times.size)
                for (i in times.indices) {
                    writeDouble(times[i])
                    writeInt(sizes[i])
                }
                flush()
            }
            data.writeTo(out)
            return out.toByteArray()
        }

        /** Writes the chunk to [file], whole or not at all. */
        fun write(file: File) {
            deflater.end()
            file.parentFile?.mkdirs()
            ProjectDisk.writeAtomically(bytes(), file)
        }
    }

    /**
     * One chunk's file, its index checked as the original checks its own (:172-185): a damaged or foreign file
     * throws [ProjectException] and is not used.
     */
    class Chunk(file: File) : Closeable {
        private val input = RandomAccessFile(file, "r")
        val index: Int
        val start: Double
        val end: Double
        val width: Int
        val height: Int
        val times: DoubleArray
        private val offsets: LongArray
        private val sizes: IntArray
        private var last: Mask? = null
        private val inflater = Inflater()

        init {
            try {
                val length = input.length()
                if (length < 40 || input.readInt() != MAGIC || input.readInt() != FORMAT) invalid()
                index = input.readInt()
                start = input.readDouble()
                end = input.readDouble()
                width = input.readInt()
                height = input.readInt()
                val count = input.readInt()
                if (index < 0 || !start.isFinite() || !end.isFinite() || width !in 1..MAXIMUM_SIDE || height !in 1..MAXIMUM_SIDE ||
                    count !in 0..MAXIMUM_FRAMES || length < 40 + 12L * count
                ) invalid()
                times = DoubleArray(count)
                offsets = LongArray(count)
                sizes = IntArray(count)
                var offset = 40 + 12L * count
                var previous = Double.NEGATIVE_INFINITY
                for (i in 0 until count) {
                    val time = input.readDouble()
                    val size = input.readInt()
                    if (!time.isFinite() || time < 0 || time <= previous || size !in 1 until MAXIMUM_BYTES) invalid()
                    times[i] = time
                    offsets[i] = offset
                    sizes[i] = size
                    offset += size
                    previous = time
                }
                if (offset != length) invalid()
            } catch (problem: Exception) {
                input.close()
                throw if (problem is ProjectException) problem else ProjectException(ProjectException.Reason.InvalidDocument, problem)
            }
        }

        val count: Int get() = times.size

        /** The frame shown at [time]: the last at or before it, the first before them all (:250-253). */
        fun frame(time: Double): Int {
            var low = 0
            var high = times.size
            while (low < high) {
                val middle = (low + high) / 2
                if (times[middle] <= time) low = middle + 1 else high = middle
            }
            return max(0, low - 1)
        }

        /** The mask shown at [time], or null in a chunk without frames. */
        fun mask(time: Double): Mask? = if (times.isEmpty()) null else decode(frame(time))

        /** The mask of frame [index]. */
        fun decode(index: Int): Mask {
            last?.let { if (it.time == times[index]) return it }
            val packed = ByteArray(sizes[index])
            synchronized(input) {
                input.seek(offsets[index])
                input.readFully(packed)
            }
            val rows = ByteArray(width * height)
            inflater.reset()
            inflater.setInput(packed)
            val n = try { inflater.inflate(rows) } catch (problem: Exception) { throw ProjectException(ProjectException.Reason.InvalidDocument, problem) }
            if (n != rows.size || !inflater.finished()) invalid()
            return Mask(times[index], width, height, rows).also { last = it }
        }

        override fun close() {
            inflater.end()
            input.close()
        }

        private fun invalid(): Nothing = throw ProjectException(ProjectException.Reason.InvalidDocument)
    }

    /** The chunk in [file] if it is there and whole, with at least one mask; null otherwise (:337, :383). */
    fun open(file: File): Chunk? {
        if (!file.isFile) return null
        val chunk = try { Chunk(file) } catch (_: ProjectException) { return null } catch (_: java.io.IOException) { return null }
        if (chunk.count == 0) {
            chunk.close()
            return null
        }
        return chunk
    }

    /**
     * Reads the masks of one recording from [directory] by source time, a chunk at a time as the original's
     * reader does (:477-489). A chunk not made yet gives null, and is looked for again on the next call.
     * Not for use by two threads at once.
     */
    class Reader(val directory: File) : Closeable {
        private var current: Chunk? = null

        fun mask(time: Double): Mask? {
            val index = chunk(time)
            val open = current?.takeIf { it.index == index } ?: run {
                current?.close()
                current = null
                open(file(directory, index))?.also { current = it } ?: return null
            }
            return open.mask(time)
        }

        override fun close() {
            current?.close()
            current = null
        }
    }
}
