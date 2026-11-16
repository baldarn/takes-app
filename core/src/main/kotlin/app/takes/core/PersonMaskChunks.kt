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
 * Each chunk is one file, `c<index>.masks`, in a folder of the project's `Cache/` named by [key]. Every frame
 * has two masks as 8-bit rows: the raw one (the model's person probability) and the one smoothed over time by
 * [PersonMaskSmoother] with the motion gate, which Auto Background reads (the original's `masks` and
 * `autoBackgroundMasks`, filter [FILTER], :356-372). Each is compressed on its own so that any one can be read
 * without the others: Deflate stands in for the original's LZFSE (:114). The original keeps the same in two
 * files, a JSON index and a data file (:54-160); one file is written whole and renamed into place, so a reader
 * sees a chunk complete or not at all.
 *
 * A mask is in the coordinates of the recording's stored frames, before the rotation that turns them upright,
 * as the original's are (VideoEffectAssets.swift:270-276): it is laid on the decoded frame as it is, and the
 * frame's rotation, crop and mirror are applied once, later, to both.
 */
object PersonMaskChunks {
    /** Seconds of source time in a chunk (VideoEffectCaptureAnalysis.swift:324, :336). */
    const val SECONDS = 10.0

    /** Seconds read before a chunk so that a temporal filter has settled at its start (:336, :519). */
    const val WARM_UP = 2.0

    /** Of the stored format and the analysis behind it: a change makes every chunk be made again. */
    const val VERSION = 2

    /** The largest side a stored mask may have (:186, :268). */
    const val MAXIMUM_SIDE = 1024

    /** What the smoothed masks of a chunk are: the motion-gated EMA of Auto Background (:356). */
    const val FILTER = "auto-background-ema-v1"

    private const val MAGIC = 0x544B504D // "TKPM"

    /** Of the file: 1 held the raw masks only, 2 the smoothed ones beside them. */
    const val FORMAT = 2
    private const val MAXIMUM_FRAMES = 2_000_000
    private const val MAXIMUM_BYTES = 2_000_000

    /** A raw person mask: [rows] holds [width] × [height] bytes, row by row, 0 background to 255 person. */
    class Mask(val time: Double, val width: Int, val height: Int, val rows: ByteArray) {
        /** The person's probability at pixel ([x], [y]), 0 to 1. */
        fun alpha(x: Int, y: Int): Float = (rows[y * width + x].toInt() and 255) / 255f
    }

    /** The chunk that holds source time [time] (:324, :485). */
    fun chunk(time: Double): Int = max(0, floor(time / SECONDS).toInt())

    /**
     * The source seconds of chunk [index] of a recording of [duration] seconds: its masks are of the frames in
     * [start, end), and its frames are read from [warm] (:336).
     */
    data class Span(val index: Int, val warm: Double, val start: Double, val end: Double) {
        /** Whether a frame at [time] is one whose mask the chunk keeps (:370). */
        fun keeps(time: Double): Boolean = time + 0.000001 >= start && time < end
    }

    fun span(index: Int, duration: Double): Span {
        val start = index * SECONDS
        return Span(index, max(0.0, start - WARM_UP), start, min(duration, start + SECONDS))
    }

    /** The chunks that cover [from] to [to] of a recording of [duration] seconds, the whole of it by default (:323-324). */
    fun chunks(duration: Double, from: Double? = null, to: Double? = null): IntRange {
        val lower = max(0.0, from ?: 0.0)
        val upper = min(duration, to ?: duration)
        val first = max(0, floor(lower / SECONDS).toInt())
        val last = max(first, floor(max(lower, upper - 0.000001) / SECONDS).toInt())
        return first..last
    }

    /**
     * The name of the folder of a recording's masks: the SHA-256 of what they depend on, as the original names
     * its own (:315-320). [model] names the model (its file's SHA-256) and [compute] how it was run; [captured]
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


    /**
     * Collects the masks of one chunk and writes them in one go. The file: "TKPM", [FORMAT], the chunk's index,
     * start and end, the masks' width and height, [FILTER] (modified UTF-8), the frame count, then for each frame
     * its time and the compressed sizes of its raw and smoothed masks, then the masks, raw before smoothed, frame
     * after frame. Big-endian.
     */
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

        /**
         * Adds the masks of the frame at [time], later than the last one: [raw] from the model and [smoothed] from
         * the smoother, each [width] × [height] bytes.
         */
        fun append(time: Double, raw: ByteArray, smoothed: ByteArray) {
            require(raw.size == width * height && smoothed.size == width * height) { "${raw.size} and ${smoothed.size} bytes for $width × $height" }
            require(time.isFinite() && time >= 0 && time > (times.lastOrNull() ?: -1.0)) { "frame at $time after ${times.lastOrNull()}" }
            val rawSize = pack(raw)
            val smoothedSize = pack(smoothed)
            times += time
            sizes += rawSize
            sizes += smoothedSize
        }

        private fun pack(rows: ByteArray): Int {
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
            return written
        }

        /** The whole file. */
        fun bytes(): ByteArray {
            val out = ByteArrayOutputStream(96 + 16 * times.size + data.size())
            DataOutputStream(out).apply {
                writeInt(MAGIC)
                writeInt(FORMAT)
                writeInt(span.index)
                writeDouble(span.start)
                writeDouble(span.end)
                writeInt(width)
                writeInt(height)
                writeUTF(FILTER)
                writeInt(times.size)
                for (i in times.indices) {
                    writeDouble(times[i])
                    writeInt(sizes[2 * i])
                    writeInt(sizes[2 * i + 1])
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
     * One chunk's file, its index checked as the original checks its own (:175-196): a damaged or foreign file,
     * or one of another [FORMAT] or [FILTER], throws [ProjectException] and is not used.
     */
    class Chunk(file: File) : Closeable {
        private val input = RandomAccessFile(file, "r")
        val index: Int
        val start: Double
        val end: Double
        val width: Int
        val height: Int
        val filter: String
        val times: DoubleArray
        // Frame i's raw mask is entry 2i, its smoothed one 2i + 1.
        private val offsets: LongArray
        private val sizes: IntArray
        private val last = arrayOfNulls<Mask>(2)
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
                filter = input.readUTF()
                val count = input.readInt()
                val header = input.filePointer
                if (index < 0 || !start.isFinite() || !end.isFinite() || width !in 1..MAXIMUM_SIDE || height !in 1..MAXIMUM_SIDE ||
                    filter != FILTER || count !in 0..MAXIMUM_FRAMES || length < header + 16L * count
                ) invalid()
                times = DoubleArray(count)
                offsets = LongArray(2 * count)
                sizes = IntArray(2 * count)
                var offset = header + 16L * count
                var previous = Double.NEGATIVE_INFINITY
                for (i in 0 until count) {
                    val time = input.readDouble()
                    if (!time.isFinite() || time < 0 || time <= previous) invalid()
                    times[i] = time
                    previous = time
                    for (kind in 0..1) {
                        val size = input.readInt()
                        if (size !in 1 until MAXIMUM_BYTES) invalid()
                        offsets[2 * i + kind] = offset
                        sizes[2 * i + kind] = size
                        offset += size
                    }
                }
                if (offset != length) invalid()
            } catch (problem: Exception) {
                input.close()
                throw if (problem is ProjectException) problem else ProjectException(ProjectException.Reason.InvalidDocument, problem)
            }
        }

        val count: Int get() = times.size

        /** The frame shown at [time]: the last at or before it, the first before them all (:264-265). */
        fun frame(time: Double): Int {
            var low = 0
            var high = times.size
            while (low < high) {
                val middle = (low + high) / 2
                if (times[middle] <= time) low = middle + 1 else high = middle
            }
            return max(0, low - 1)
        }

        /** The mask shown at [time], raw or [smoothed] (:260-265), or null in a chunk without frames. */
        fun mask(time: Double, smoothed: Boolean = false): Mask? = if (times.isEmpty()) null else decode(frame(time), smoothed)

        /** The mask of frame [index], raw or [smoothed]. */
        fun decode(index: Int, smoothed: Boolean = false): Mask {
            val kind = if (smoothed) 1 else 0
            last[kind]?.let { if (it.time == times[index]) return it }
            val entry = 2 * index + kind
            val packed = ByteArray(sizes[entry])
            synchronized(input) {
                input.seek(offsets[entry])
                input.readFully(packed)
            }
            val rows = ByteArray(width * height)
            inflater.reset()
            inflater.setInput(packed)
            val n = try { inflater.inflate(rows) } catch (problem: Exception) { throw ProjectException(ProjectException.Reason.InvalidDocument, problem) }
            if (n != rows.size || !inflater.finished()) invalid()
            return Mask(times[index], width, height, rows).also { last[kind] = it }
        }

        override fun close() {
            inflater.end()
            input.close()
        }

        private fun invalid(): Nothing = throw ProjectException(ProjectException.Reason.InvalidDocument)
    }

    /**
     * The chunk in [file] if it is there and whole, of this [FORMAT] and [FILTER], with at least one frame; null
     * otherwise, and it is to be made again (:343, :383).
     */
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
     * reader does (:483-501). A chunk not made yet gives null, and is looked for again on the next call.
     * Not for use by two threads at once.
     */
    class Reader(val directory: File) : Closeable {
        private var current: Chunk? = null

        /** The raw mask shown at [time], or with [smoothed] the motion-gated EMA one Auto Background reads (:496-501). */
        fun mask(time: Double, smoothed: Boolean = false): Mask? {
            val index = chunk(time)
            val open = current?.takeIf { it.index == index } ?: run {
                current?.close()
                current = null
                open(file(directory, index))?.also { current = it } ?: return null
            }
            return open.mask(time, smoothed)
        }

        /** The smoothed mask shown at [time]: [mask] with smoothed. */
        fun smoothed(time: Double): Mask? = mask(time, smoothed = true)

        override fun close() {
            current?.close()
            current = null
        }
    }

    /**
     * Runs [PersonMaskSmoother] over the raw masks of a chunk as they come, warm-up first, and gives each frame's
     * smoothed mask as 8-bit rows: what the analysis stores beside the raw one. It starts afresh at [Span.warm], as
     * the original's analysis starts a smoother for each chunk and its reader replays from two seconds before the
     * chunk (:358, :515-519).
     */
    class Smoothing {
        private val smoother = PersonMaskSmoother()

        /**
         * The smoothed mask of [raw] at [time], 8-bit. [guideLuma] is the frame's linear luma at the mask's size,
         * in the same orientation; [geometry] names the frame's shape, and a change of it starts afresh.
         */
        fun push(raw: ByteArray, width: Int, height: Int, guideLuma: FloatArray, time: Double, geometry: String): ByteArray {
            val mask = MaskPlane(width, height, FloatArray(width * height) { (raw[it].toInt() and 255) / 255f })
            val out = smoother.push(mask, MaskPlane(width, height, guideLuma), time, geometry, motionGate = true)
            // The smoother's output is already on the 8-bit steps.
            return ByteArray(width * height) { Math.rint(out.data[it] * 255.0).toInt().toByte() }
        }
    }
}
