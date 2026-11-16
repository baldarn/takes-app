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
import java.io.File

class PersonMaskChunksTest {
    @get:Rule val temporary = TemporaryFolder()

    private val source = SourceMedia(id = "A", filename = "take.mp4", duration = 25.0, sha256 = "abc")

    @Test fun chunkOfASourceTime() {
        // YouRec/VideoEffects/VideoEffectCaptureAnalysis.swift:481: max(0, floor(stamp / 10))
        assertEquals(0, PersonMaskChunks.chunk(0.0))
        assertEquals(0, PersonMaskChunks.chunk(9.999))
        assertEquals(1, PersonMaskChunks.chunk(10.0))
        assertEquals(2, PersonMaskChunks.chunk(24.5))
        assertEquals(0, PersonMaskChunks.chunk(-1.0))
    }

    @Test fun spanOfAChunkWithItsWarmUp() {
        // YouRec/VideoEffects/VideoEffectCaptureAnalysis.swift:325: begin = chunk·10, end = min(duration, begin+10), warm = max(0, begin-2)
        assertEquals(PersonMaskChunks.Span(0, 0.0, 0.0, 10.0), PersonMaskChunks.span(0, 25.0))
        assertEquals(PersonMaskChunks.Span(1, 8.0, 10.0, 20.0), PersonMaskChunks.span(1, 25.0))
        assertEquals(PersonMaskChunks.Span(2, 18.0, 20.0, 25.0), PersonMaskChunks.span(2, 25.0))
    }

    @Test fun aChunkKeepsTheFramesOfItsOwnTenSeconds() {
        // YouRec/VideoEffects/VideoEffectCaptureAnalysis.swift:356: time+0.000001 >= begin && time < end
        val span = PersonMaskChunks.span(1, 25.0)
        assertFalse(span.keeps(8.0))
        assertFalse(span.keeps(9.99))
        assertTrue(span.keeps(9.9999995))
        assertTrue(span.keeps(10.0))
        assertTrue(span.keeps(19.99))
        assertFalse(span.keeps(20.0))
    }

    @Test fun chunksOfARange() {
        // YouRec/VideoEffects/VideoEffectCaptureAnalysis.swift:316-317
        assertEquals(0..2, PersonMaskChunks.chunks(25.0))
        assertEquals(0..1, PersonMaskChunks.chunks(20.0))
        assertEquals(1..1, PersonMaskChunks.chunks(25.0, 10.0, 20.0))
        assertEquals(1..2, PersonMaskChunks.chunks(25.0, 12.0, 40.0))
        assertEquals(0..0, PersonMaskChunks.chunks(0.0))
        assertEquals(2..2, PersonMaskChunks.chunks(25.0, 22.0, 21.0))
    }

    @Test fun keyFollowsWhatTheMasksDependOn() {
        // YouRec/VideoEffects/VideoEffectCaptureAnalysis.swift:316-319: the source, a version, the model's revision, how it runs, the captured analysis
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

    @Test fun directoryIsInTheProjectsCache() {
        // YouRec/VideoEffects/VideoEffectCaptureAnalysis.swift:319: <cache>/masks/<key>
        val project = File("/projects/P")
        assertEquals(File("/projects/P/Cache/masks/k"), PersonMaskChunks.directory(project, "k"))
        assertEquals(File("/projects/P/Cache/masks/k/c3.masks"), PersonMaskChunks.file(PersonMaskChunks.directory(project, "k"), 3))
    }

    private fun rows(width: Int, height: Int, seed: Int) = ByteArray(width * height) { ((it * seed) % 256).toByte() }

    @Test fun roundTrip() {
        val span = PersonMaskChunks.span(1, 25.0)
        val writer = PersonMaskChunks.Writer(span, 512, 288)
        val times = listOf(10.0, 10.0333, 10.0667)
        times.forEachIndexed { i, time -> writer.append(time, rows(512, 288, i + 1)) }
        val file = PersonMaskChunks.file(temporary.newFolder("masks"), 1)
        writer.write(file)
        PersonMaskChunks.open(file)!!.use { chunk ->
            assertEquals(1, chunk.index)
            assertEquals(10.0, chunk.start, 0.0)
            assertEquals(20.0, chunk.end, 0.0)
            assertEquals(512, chunk.width)
            assertEquals(288, chunk.height)
            assertArrayEquals(times.toDoubleArray(), chunk.times, 0.0)
            times.forEachIndexed { i, time ->
                val mask = chunk.mask(time)!!
                assertEquals(time, mask.time, 0.0)
                assertArrayEquals(rows(512, 288, i + 1), mask.rows)
            }
        }
    }

    @Test fun maskShownAtATimeIsTheLastAtOrBeforeIt() {
        // YouRec/VideoEffects/VideoEffectCaptureAnalysis.swift:250-253: the first frame before them all
        val writer = PersonMaskChunks.Writer(PersonMaskChunks.span(0, 25.0), 2, 1)
        writer.append(0.5, byteArrayOf(1, 1))
        writer.append(1.0, byteArrayOf(2, 2))
        writer.append(1.5, byteArrayOf(3, 3))
        val file = File(temporary.root, "c0.masks")
        writer.write(file)
        PersonMaskChunks.open(file)!!.use { chunk ->
            assertEquals(1.toByte(), chunk.mask(0.0)!!.rows[0])
            assertEquals(1.toByte(), chunk.mask(0.99)!!.rows[0])
            assertEquals(2.toByte(), chunk.mask(1.0)!!.rows[0])
            assertEquals(3.toByte(), chunk.mask(9.0)!!.rows[0])
            assertEquals(255 / 255f, PersonMaskChunks.Mask(0.0, 1, 1, byteArrayOf(-1)).alpha(0, 0), 0f)
        }
    }

    @Test fun writerRefusesFramesOutOfOrderAndMasksTooLarge() {
        // YouRec/VideoEffects/VideoEffectCaptureAnalysis.swift:177: times increase, sides 1…1024
        val writer = PersonMaskChunks.Writer(PersonMaskChunks.span(0, 25.0), 1, 1)
        writer.append(1.0, byteArrayOf(0))
        assertTrue(runCatching { writer.append(1.0, byteArrayOf(0)) }.isFailure)
        assertTrue(runCatching { writer.append(2.0, byteArrayOf(0, 0)) }.isFailure)
        assertTrue(runCatching { PersonMaskChunks.Writer(PersonMaskChunks.span(0, 25.0), 1025, 1) }.isFailure)
    }

    @Test fun damagedOrEmptyChunksAreNotUsed() {
        // YouRec/VideoEffects/VideoEffectCaptureAnalysis.swift:337: a chunk counts only if it is readable and has masks
        val folder = temporary.newFolder("masks")
        assertNull(PersonMaskChunks.open(File(folder, "missing.masks")))
        val empty = File(folder, "c0.masks")
        PersonMaskChunks.Writer(PersonMaskChunks.span(0, 25.0), 4, 4).write(empty)
        assertNull(PersonMaskChunks.open(empty))
        val whole = PersonMaskChunks.Writer(PersonMaskChunks.span(0, 25.0), 4, 4).apply { append(0.0, ByteArray(16)) }.bytes()
        val cut = File(folder, "c1.masks").apply { writeBytes(whole.copyOf(whole.size - 1)) }
        assertNull(PersonMaskChunks.open(cut))
        val foreign = File(folder, "c2.masks").apply { writeBytes(ByteArray(64)) }
        assertNull(PersonMaskChunks.open(foreign))
    }

    @Test fun readerFollowsSourceTimeAcrossChunksAndWaitsForMissingOnes() {
        // YouRec/VideoEffects/VideoEffectCaptureAnalysis.swift:477-489: the chunk of the time, opened when the time moves into it
        val folder = temporary.newFolder("masks")
        for (index in listOf(0, 2)) {
            val span = PersonMaskChunks.span(index, 25.0)
            PersonMaskChunks.Writer(span, 1, 1).apply { append(span.start, byteArrayOf(index.toByte())) }.write(PersonMaskChunks.file(folder, index))
        }
        PersonMaskChunks.Reader(folder).use { reader ->
            assertEquals(0.toByte(), reader.mask(5.0)!!.rows[0])
            assertNull(reader.mask(15.0))
            assertEquals(2.toByte(), reader.mask(24.0)!!.rows[0])
            PersonMaskChunks.Writer(PersonMaskChunks.span(1, 25.0), 1, 1).apply { append(10.0, byteArrayOf(1)) }.write(PersonMaskChunks.file(folder, 1))
            assertEquals(1.toByte(), reader.mask(15.0)!!.rows[0])
        }
    }
}
