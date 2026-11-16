package app.takes.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.Deflater

class PersonMaskChunksTest {
    @get:Rule val temporary = TemporaryFolder()

    private val source = SourceMedia(id = "A", filename = "take.mp4", duration = 25.0, sha256 = "abc")

    @Test fun chunkOfASourceTime() {
        // YouRec/VideoEffects/VideoEffectCaptureAnalysis.swift:485: max(0, floor(stamp / 10))
        assertEquals(0, PersonMaskChunks.chunk(0.0))
        assertEquals(0, PersonMaskChunks.chunk(9.999))
        assertEquals(1, PersonMaskChunks.chunk(10.0))
        assertEquals(2, PersonMaskChunks.chunk(24.5))
        assertEquals(0, PersonMaskChunks.chunk(-1.0))
    }

    @Test fun spanOfAChunkWithItsWarmUp() {
        // YouRec/VideoEffects/VideoEffectCaptureAnalysis.swift:336: begin = chunk·10, end = min(duration, begin+10), warm = max(0, begin-2)
        assertEquals(PersonMaskChunks.Span(0, 0.0, 0.0, 10.0), PersonMaskChunks.span(0, 25.0))
        assertEquals(PersonMaskChunks.Span(1, 8.0, 10.0, 20.0), PersonMaskChunks.span(1, 25.0))
        assertEquals(PersonMaskChunks.Span(2, 18.0, 20.0, 25.0), PersonMaskChunks.span(2, 25.0))
    }

    @Test fun aChunkKeepsTheFramesOfItsOwnTenSeconds() {
        // YouRec/VideoEffects/VideoEffectCaptureAnalysis.swift:370: time+0.000001 >= begin && time < end
        val span = PersonMaskChunks.span(1, 25.0)
        assertFalse(span.keeps(8.0))
        assertFalse(span.keeps(9.99))
        assertTrue(span.keeps(9.9999995))
        assertTrue(span.keeps(10.0))
        assertTrue(span.keeps(19.99))
        assertFalse(span.keeps(20.0))
    }

    @Test fun chunksOfARange() {
        // YouRec/VideoEffects/VideoEffectCaptureAnalysis.swift:323-324
        assertEquals(0..2, PersonMaskChunks.chunks(25.0))
        assertEquals(0..1, PersonMaskChunks.chunks(20.0))
        assertEquals(1..1, PersonMaskChunks.chunks(25.0, 10.0, 20.0))
        assertEquals(1..2, PersonMaskChunks.chunks(25.0, 12.0, 40.0))
        assertEquals(0..0, PersonMaskChunks.chunks(0.0))
        assertEquals(2..2, PersonMaskChunks.chunks(25.0, 22.0, 21.0))
        // A single chunk asked for by its start, as the effects' assets ask: from = to = span.start.
        assertEquals(1..1, PersonMaskChunks.chunks(25.0, 10.0, 10.0))
    }

    @Test fun keyFollowsWhatTheMasksDependOn() {
        // YouRec/VideoEffects/VideoEffectCaptureAnalysis.swift:315-320: the source, a version, the model's revision, how it runs, the captured analysis
        val key = PersonMaskChunks.key(source, "model-1", "cpu")
        assertEquals(64, key.length)
        assertEquals(key, PersonMaskChunks.key(source.copy(), "model-1", "cpu"))
        assertNotEquals(key, PersonMaskChunks.key(source, "model-2", "cpu"))
        assertNotEquals(key, PersonMaskChunks.key(source, "model-1", "gpu"))
        assertNotEquals(key, PersonMaskChunks.key(source, "model-1", "cpu", "effect.json"))
        assertNotEquals(key, PersonMaskChunks.key(source.copy(sha256 = "def"), "model-1", "cpu"))
        assertNotEquals(key, PersonMaskChunks.key(source.copy(duration = 26.0), "model-1", "cpu"))
        assertNotEquals(key, PersonMaskChunks.key(source.copy(filename = "other.mp4"), "model-1", "cpu"))
        // What does not change the recording does not change its masks.
        assertEquals(key, PersonMaskChunks.key(source.copy(originalName = "Holiday.mov", recordedAt = 5), "model-1", "cpu"))
    }

    @Test fun keyNamesTheVersionWithTheSmoothedMasks() {
        // YouRec/VideoEffects/VideoEffectCaptureAnalysis.swift:316: the version is part of the key, so chunks
        // of the raw-only version 1 are in another folder and are made again with their smoothed masks.
        assertEquals(2, PersonMaskChunks.VERSION)
        assertEquals(2, PersonMaskChunks.FORMAT)
        val json = """{"captured":null,"compute":"cpu","model":"model-1","source":{"duration":25.0,"filename":"take.mp4","id":"A","sha256":"abc"},"version":2}"""
        assertEquals(sha256(json.toByteArray()), PersonMaskChunks.key(source, "model-1", "cpu"))
        assertNotEquals(sha256(json.replace("\"version\":2", "\"version\":1").toByteArray()), PersonMaskChunks.key(source, "model-1", "cpu"))
    }

    @Test fun directoryIsInTheProjectsCache() {
        // YouRec/VideoEffects/VideoEffectCaptureAnalysis.swift:319: <cache>/masks/<key>
        val project = File("/projects/P")
        assertEquals(File("/projects/P/Cache/masks/k"), PersonMaskChunks.directory(project, "k"))
        assertEquals(File("/projects/P/Cache/masks/k/c3.masks"), PersonMaskChunks.file(PersonMaskChunks.directory(project, "k"), 3))
    }

    private fun rows(width: Int, height: Int, seed: Int) = ByteArray(width * height) { ((it * seed) % 256).toByte() }

    @Test fun roundTripOfRawAndSmoothedMasks() {
        // YouRec/VideoEffects/VideoEffectCaptureAnalysis.swift:356, :371: each frame keeps its raw mask and the
        // smoothed one under the filter "auto-background-ema-v1"; :260-265 reads either by time.
        val span = PersonMaskChunks.span(1, 25.0)
        val writer = PersonMaskChunks.Writer(span, 256, 256)
        val times = listOf(10.0, 10.0333, 10.0667)
        times.forEachIndexed { i, time -> writer.append(time, rows(256, 256, i + 1), rows(256, 256, i + 7)) }
        val file = PersonMaskChunks.file(temporary.newFolder("masks"), 1)
        writer.write(file)
        PersonMaskChunks.open(file)!!.use { chunk ->
            assertEquals(1, chunk.index)
            assertEquals(10.0, chunk.start, 0.0)
            assertEquals(20.0, chunk.end, 0.0)
            assertEquals(256, chunk.width)
            assertEquals(256, chunk.height)
            assertEquals("auto-background-ema-v1", chunk.filter)
            assertArrayEquals(times.toDoubleArray(), chunk.times, 0.0)
            times.forEachIndexed { i, time ->
                val raw = chunk.mask(time)!!
                val smoothed = chunk.mask(time, smoothed = true)!!
                assertEquals(time, raw.time, 0.0)
                assertEquals(time, smoothed.time, 0.0)
                assertArrayEquals(rows(256, 256, i + 1), raw.rows)
                assertArrayEquals(rows(256, 256, i + 7), smoothed.rows)
                // Read in the other order too: each kind has its own cached frame.
                assertArrayEquals(rows(256, 256, i + 1), chunk.decode(i).rows)
            }
        }
        PersonMaskChunks.Reader(file.parentFile).use { reader ->
            assertArrayEquals(rows(256, 256, 2), reader.mask(10.05)!!.rows)
            assertArrayEquals(rows(256, 256, 8), reader.mask(10.05, smoothed = true)!!.rows)
            assertArrayEquals(rows(256, 256, 8), reader.smoothed(10.05)!!.rows)
        }
    }

    @Test fun maskShownAtATimeIsTheLastAtOrBeforeIt() {
        // YouRec/VideoEffects/VideoEffectCaptureAnalysis.swift:264-265: the first frame before them all
        val writer = PersonMaskChunks.Writer(PersonMaskChunks.span(0, 25.0), 2, 1)
        writer.append(0.5, byteArrayOf(1, 1), byteArrayOf(11, 11))
        writer.append(1.0, byteArrayOf(2, 2), byteArrayOf(12, 12))
        writer.append(1.5, byteArrayOf(3, 3), byteArrayOf(13, 13))
        val file = File(temporary.root, "c0.masks")
        writer.write(file)
        PersonMaskChunks.open(file)!!.use { chunk ->
            assertEquals(1.toByte(), chunk.mask(0.0)!!.rows[0])
            assertEquals(1.toByte(), chunk.mask(0.99)!!.rows[0])
            assertEquals(2.toByte(), chunk.mask(1.0)!!.rows[0])
            assertEquals(12.toByte(), chunk.mask(1.0, smoothed = true)!!.rows[0])
            assertEquals(3.toByte(), chunk.mask(9.0)!!.rows[0])
            assertEquals(13.toByte(), chunk.mask(9.0, smoothed = true)!!.rows[0])
            assertEquals(255 / 255f, PersonMaskChunks.Mask(0.0, 1, 1, byteArrayOf(-1)).alpha(0, 0), 0f)
        }
    }

    @Test fun writerRefusesFramesOutOfOrderAndMasksTooLarge() {
        // YouRec/VideoEffects/VideoEffectCaptureAnalysis.swift:186: times increase, sides 1…1024
        val writer = PersonMaskChunks.Writer(PersonMaskChunks.span(0, 25.0), 1, 1)
        writer.append(1.0, byteArrayOf(0), byteArrayOf(0))
        assertTrue(runCatching { writer.append(1.0, byteArrayOf(0), byteArrayOf(0)) }.isFailure)
        assertTrue(runCatching { writer.append(2.0, byteArrayOf(0, 0), byteArrayOf(0)) }.isFailure)
        assertTrue(runCatching { writer.append(2.0, byteArrayOf(0), byteArrayOf(0, 0)) }.isFailure)
        assertTrue(runCatching { PersonMaskChunks.Writer(PersonMaskChunks.span(0, 25.0), 1025, 1) }.isFailure)
    }

    @Test fun damagedOrEmptyChunksAreNotUsed() {
        // YouRec/VideoEffects/VideoEffectCaptureAnalysis.swift:343: a chunk counts only if it is readable and has both kinds of mask
        val folder = temporary.newFolder("masks")
        assertNull(PersonMaskChunks.open(File(folder, "missing.masks")))
        val empty = File(folder, "c0.masks")
        PersonMaskChunks.Writer(PersonMaskChunks.span(0, 25.0), 4, 4).write(empty)
        assertNull(PersonMaskChunks.open(empty))
        val whole = PersonMaskChunks.Writer(PersonMaskChunks.span(0, 25.0), 4, 4).apply { append(0.0, ByteArray(16), ByteArray(16)) }.bytes()
        val cut = File(folder, "c1.masks").apply { writeBytes(whole.copyOf(whole.size - 1)) }
        assertNull(PersonMaskChunks.open(cut))
        val foreign = File(folder, "c2.masks").apply { writeBytes(ByteArray(64)) }
        assertNull(PersonMaskChunks.open(foreign))
    }

    /** A chunk as format 1 wrote it: raw masks only, no filter. */
    private fun formatOne(span: PersonMaskChunks.Span, time: Double, raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED).apply { setInput(raw); finish() }
        val packed = ByteArray(1024).let { it.copyOf(deflater.deflate(it)) }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(0x544B504D); writeInt(1); writeInt(span.index); writeDouble(span.start); writeDouble(span.end)
            writeInt(1); writeInt(raw.size); writeInt(1); writeDouble(time); writeInt(packed.size); write(packed)
        }
        return out.toByteArray()
    }

    @Test fun chunksOfTheRawOnlyFormatAreMadeAgain() {
        // YouRec/VideoEffects/VideoEffectCaptureAnalysis.swift:343: without its smoothed masks a chunk is analysed again
        val folder = temporary.newFolder("masks")
        val old = File(folder, "c0.masks").apply { writeBytes(formatOne(PersonMaskChunks.span(0, 25.0), 0.0, ByteArray(4))) }
        assertNull(PersonMaskChunks.open(old))
        PersonMaskChunks.Reader(folder).use { assertNull(it.mask(1.0)) }
    }

    @Test fun readerFollowsSourceTimeAcrossChunksAndWaitsForMissingOnes() {
        // YouRec/VideoEffects/VideoEffectCaptureAnalysis.swift:483-501: the chunk of the time, opened when the time moves into it
        val folder = temporary.newFolder("masks")
        for (index in listOf(0, 2)) {
            val span = PersonMaskChunks.span(index, 25.0)
            PersonMaskChunks.Writer(span, 1, 1).apply { append(span.start, byteArrayOf(index.toByte()), byteArrayOf(index.toByte())) }
                .write(PersonMaskChunks.file(folder, index))
        }
        PersonMaskChunks.Reader(folder).use { reader ->
            assertEquals(0.toByte(), reader.mask(5.0)!!.rows[0])
            assertNull(reader.mask(15.0))
            assertNull(reader.mask(15.0, smoothed = true))
            assertEquals(2.toByte(), reader.mask(24.0)!!.rows[0])
            PersonMaskChunks.Writer(PersonMaskChunks.span(1, 25.0), 1, 1).apply { append(10.0, byteArrayOf(1), byteArrayOf(1)) }
                .write(PersonMaskChunks.file(folder, 1))
            assertEquals(1.toByte(), reader.mask(15.0)!!.rows[0])
        }
    }

    @Test fun smoothingIsTheSmoothersMotionGatedStreamAsBytes() {
        // YouRec/VideoEffects/VideoEffectCaptureAnalysis.swift:358, :369: one smoother a chunk, pushed every frame
        // with the motion gate; MetalVideoEffects.swift:741: the EMA weight 0.3 at 30 Hz.
        val smoothing = PersonMaskChunks.Smoothing()
        val luma = FloatArray(4) { .5f }
        val first = smoothing.push(ByteArray(4) { 0 }, 2, 2, luma, 8.0, "90:1920x1080")
        assertArrayEquals(ByteArray(4), first)
        // A small step of the mask on a still picture is mixed in by 0.3.
        val second = smoothing.push(ByteArray(4) { 30 }, 2, 2, luma, 8.0 + 1 / 30.0, "90:1920x1080")
        assertTrue(second.all { (it.toInt() and 255) in 8..10 })
        // The same, given to the core smoother directly.
        val reference = PersonMaskSmoother()
        reference.push(MaskPlane.flat(2, 2, 0f), MaskPlane(2, 2, luma), 8.0, "g", motionGate = true)
        val expected = reference.push(MaskPlane.flat(2, 2, 30 / 255f), MaskPlane(2, 2, luma), 8.0 + 1 / 30.0, "g", motionGate = true)
        assertArrayEquals(ByteArray(4) { Math.rint(expected.data[it] * 255.0).toInt().toByte() }, second)
        // A step larger than the gate (0.15) is let through at once; another geometry starts afresh.
        assertTrue(smoothing.push(ByteArray(4) { -1 }, 2, 2, luma, 8.0 + 2 / 30.0, "90:1920x1080").all { it == (-1).toByte() })
        assertArrayEquals(ByteArray(4) { 30 }, smoothing.push(ByteArray(4) { 30 }, 2, 2, luma, 8.0 + 3 / 30.0, "0:1080x1920"))
    }
}
