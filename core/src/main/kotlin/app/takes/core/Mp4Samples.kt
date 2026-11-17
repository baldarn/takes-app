package app.takes.core

import java.io.File
import java.io.RandomAccessFile

/**
 * What the sample tables of an MP4 file say of its first track of one kind, read from the file itself.
 *
 * An export reads every section it saves, the sound and the finished movie to count their samples. Android's
 * MediaExtractor gives a sample at a time from a media service, a call across processes for each: on a Galaxy A54
 * counting the samples of a 73-second movie and its sound that way took about a second and a half, and an export
 * counted them several times. The tables hold the same numbers: the count and the size of every sample (`stsz`)
 * and which are key frames (`stss`, none meaning all are).
 */
object Mp4Samples {
    /** The samples of a track: how many, whether the first is a key frame, the size of the largest in bytes. */
    data class Track(val samples: Int, val startsOnKey: Boolean, val largest: Int)

    /** Handler types of `hdlr`. */
    const val VIDEO = "vide"
    const val SOUND = "soun"

    /**
     * The first track of [handler] ([VIDEO], [SOUND]) in [file], or null when the file has none or its tables are
     * not the plain ones read here (compact sizes, fragments, a box that runs past the end of the file).
     */
    fun read(file: File, handler: String): Track? = try {
        RandomAccessFile(file, "r").use { input ->
            val moov = children(input, 0, input.length()).firstOrNull { it.type == "moov" } ?: return null
            children(input, moov.body, moov.end).filter { it.type == "trak" }.firstNotNullOfOrNull { trak -> track(input, trak, handler) }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Every sample of a track: where it lies in the file ([offsets], [sizes]), whether it is a key frame ([keys]) and
     * when it is shown, in microseconds ([micros]): its decoding time and composition offset (`stts`, `ctts`) less
     * the start of its edit (`elst`), so the lead-in of encoded sound comes out before zero.
     */
    class Table(val offsets: LongArray, val sizes: IntArray, val keys: BooleanArray, val micros: LongArray) {
        val count: Int get() = sizes.size
    }

    /**
     * The sample table of the first track of [handler] in [file], or null when the file has none or its tables are
     * not the plain ones read here: then [read]'s numbers may still be had, and the samples from a media extractor.
     */
    fun table(file: File, handler: String): Table? = try {
        RandomAccessFile(file, "r").use { input ->
            val moov = children(input, 0, input.length()).firstOrNull { it.type == "moov" } ?: return null
            val mvhd = child(input, moov, "mvhd") ?: return null
            input.seek(mvhd.body)
            val movieScale = if (input.readInt() ushr 24 == 1) { input.skipBytes(16); input.readInt() } else { input.skipBytes(8); input.readInt() }
            val trak = children(input, moov.body, moov.end).filter { it.type == "trak" }.firstOrNull { trak ->
                child(input, trak, "mdia")?.let { child(input, it, "hdlr") }?.let { input.seek(it.body + 8); ByteArray(4).also(input::readFully).toString(Charsets.ISO_8859_1) == handler } == true
            } ?: return null
            table(input, trak, movieScale)
        }
    } catch (_: Exception) {
        null
    }

    private fun table(input: RandomAccessFile, trak: Box, movieScale: Int): Table? {
        val mdia = child(input, trak, "mdia") ?: return null
        val mdhd = child(input, mdia, "mdhd") ?: return null
        input.seek(mdhd.body)
        val scale = if (input.readInt() ushr 24 == 1) { input.skipBytes(16); input.readInt() } else { input.skipBytes(8); input.readInt() }
        if (scale <= 0) return null
        val stbl = child(input, mdia, "minf")?.let { child(input, it, "stbl") } ?: return null
        fun entries(type: String): Pair<Box, Int>? = child(input, stbl, type)?.let { box -> input.seek(box.body + 4); box to input.readInt() }

        // Sizes.
        val (stsz, _) = entries("stsz") ?: return null
        input.seek(stsz.body + 4)
        val fixed = input.readInt()
        val count = input.readInt()
        val sizes = IntArray(count) { if (fixed != 0) fixed else input.readInt() }
        // Key frames: none listed means all are.
        val keys = BooleanArray(count) { true }
        entries("stss")?.let { (_, listed) -> keys.fill(false); repeat(listed) { input.readInt().let { number -> if (number in 1..count) keys[number - 1] = true } } }
        // Where each chunk begins, and how many samples each holds.
        val chunks = entries("stco")?.let { (_, n) -> LongArray(n) { input.readInt().toLong() and 0xFFFFFFFFL } }
            ?: entries("co64")?.let { (_, n) -> LongArray(n) { input.readLong() } } ?: return null
        val (_, runs) = entries("stsc") ?: return null
        val firstChunks = IntArray(runs)
        val perChunk = IntArray(runs)
        for (run in 0 until runs) { firstChunks[run] = input.readInt(); perChunk[run] = input.readInt(); input.readInt() }
        val offsets = LongArray(count)
        var sample = 0
        for (run in 0 until runs) {
            val last = if (run + 1 < runs) firstChunks[run + 1] - 1 else chunks.size
            for (chunk in firstChunks[run]..last) {
                var at = chunks.getOrNull(chunk - 1) ?: return null
                repeat(perChunk[run]) {
                    if (sample >= count) return null
                    offsets[sample] = at
                    at += sizes[sample]
                    sample += 1
                }
            }
        }
        if (sample != count) return null
        // Times, in the track's own ticks: decoding, then composition.
        val ticks = LongArray(count)
        val (_, steps) = entries("stts") ?: return null
        sample = 0
        var time = 0L
        repeat(steps) {
            val many = input.readInt()
            val delta = input.readInt().toLong() and 0xFFFFFFFFL
            repeat(many) { if (sample < count) { ticks[sample++] = time; time += delta } }
        }
        if (sample != count) return null
        entries("ctts")?.let { (box, shifts) ->
            input.seek(box.body)
            val signed = input.readInt() ushr 24 == 1
            input.readInt()
            sample = 0
            repeat(shifts) {
                val many = input.readInt()
                val shift = input.readInt().let { if (signed) it.toLong() else it.toLong() and 0xFFFFFFFFL }
                repeat(many) { if (sample < count) ticks[sample++] += shift }
            }
        }
        // The edit: an empty one first delays the track, the next says where in it the movie begins. Anything more is not read here.
        var delay = 0L
        var start = 0L
        child(input, trak, "edts")?.let { edts -> child(input, edts, "elst") }?.let { elst ->
            input.seek(elst.body)
            val long = input.readInt() ushr 24 == 1
            val edits = input.readInt()
            var used = 0
            repeat(edits) {
                val duration = if (long) input.readLong() else input.readInt().toLong() and 0xFFFFFFFFL
                val media = if (long) input.readLong() else input.readInt().toLong()
                input.readInt() // rate
                when {
                    media == -1L && used == 0 -> delay += duration
                    used == 0 -> { start = media; used = 1 }
                    else -> return null
                }
            }
            if (movieScale <= 0) return null
        }
        val delayTicks = if (delay == 0L) 0L else delay * scale / movieScale
        val micros = LongArray(count) { (ticks[it] - start + delayTicks) * 1_000_000L / scale }
        return Table(offsets, sizes, keys, micros)
    }

    private class Box(val type: String, val body: Long, val end: Long)

    /** The boxes from [from] to [to]; null for a box whose size does not fit. */
    private fun children(input: RandomAccessFile, from: Long, to: Long): List<Box> {
        val boxes = mutableListOf<Box>()
        var at = from
        while (at + 8 <= to) {
            input.seek(at)
            val small = input.readInt().toLong() and 0xFFFFFFFFL
            val type = ByteArray(4).also { input.readFully(it) }.toString(Charsets.ISO_8859_1)
            val (size, header) = when (small) {
                0L -> (to - at) to 8L
                1L -> input.readLong() to 16L
                else -> small to 8L
            }
            if (size < header || at + size > to) throw IllegalStateException("Box $type runs past its parent")
            boxes += Box(type, at + header, at + size)
            at += size
        }
        return boxes
    }

    private fun child(input: RandomAccessFile, parent: Box, type: String): Box? = children(input, parent.body, parent.end).firstOrNull { it.type == type }

    private fun track(input: RandomAccessFile, trak: Box, handler: String): Track? {
        val mdia = child(input, trak, "mdia") ?: return null
        val hdlr = child(input, mdia, "hdlr") ?: return null
        input.seek(hdlr.body + 8) // version and flags, pre_defined
        if (ByteArray(4).also { input.readFully(it) }.toString(Charsets.ISO_8859_1) != handler) return null
        val stbl = child(input, mdia, "minf")?.let { child(input, it, "stbl") } ?: throw IllegalStateException("No sample table")
        val stsz = child(input, stbl, "stsz") ?: throw IllegalStateException("No sample sizes")
        input.seek(stsz.body + 4) // version and flags
        val fixed = input.readInt()
        val count = input.readInt()
        if (count < 0) throw IllegalStateException("Sample count")
        var largest = fixed
        if (fixed == 0) {
            if (stsz.body + 12 + 4L * count > stsz.end) throw IllegalStateException("Sample sizes run past their box")
            val sizes = ByteArray(4 * count).also { input.readFully(it) }
            for (index in 0 until count) {
                val at = 4 * index
                val size = (sizes[at].toInt() and 0xFF shl 24) or (sizes[at + 1].toInt() and 0xFF shl 16) or (sizes[at + 2].toInt() and 0xFF shl 8) or (sizes[at + 3].toInt() and 0xFF)
                if (size > largest) largest = size
            }
        }
        // Without a table of key frames every sample is one.
        val stss = child(input, stbl, "stss")
        val startsOnKey = if (stss == null) count > 0 else {
            input.seek(stss.body + 4)
            input.readInt() > 0 && input.readInt() == 1
        }
        return Track(count, startsOnKey, largest)
    }
}
