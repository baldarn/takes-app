package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/** The sample tables of an MP4 file read without a media service (ISO/IEC 14496-12: moov, trak, mdia, hdlr, minf, stbl, stsz, stss). */
class Mp4SamplesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { all, part -> all + part }
        return ByteArrayOutputStream().also { DataOutputStream(it).run { writeInt(8 + body.size); writeBytes(type); write(body) } }.toByteArray()
    }

    private fun ints(vararg values: Int): ByteArray = ByteArrayOutputStream().also { out -> DataOutputStream(out).run { values.forEach(::writeInt) } }.toByteArray()

    private fun hdlr(handler: String) = box("hdlr", ints(0, 0), handler.toByteArray(), ints(0, 0, 0), byteArrayOf(0))

    private fun track(handler: String, sizes: IntArray, keys: IntArray?, fixed: Int = 0): ByteArray {
        val stsz = box("stsz", ints(0, fixed, sizes.size), if (fixed == 0) ints(*sizes) else ByteArray(0))
        val stss = keys?.let { box("stss", ints(0, it.size, *it)) } ?: ByteArray(0)
        return box("trak", box("tkhd", ints(0)), box("mdia", box("mdhd", ints(0)), hdlr(handler), box("minf", box("stbl", box("stsd", ints(0, 0)), stsz, stss))))
    }

    private fun file(vararg boxes: ByteArray): File = temporary.newFile().also { it.writeBytes(boxes.fold(ByteArray(0)) { all, box -> all + box }) }

    @Test fun readsTheCountTheFirstKeyAndTheLargestSample() {
        val movie = file(
            box("ftyp", "isom".toByteArray(), ints(0)),
            box("free", ByteArray(100)),
            box("moov", box("mvhd", ints(0)), track("soun", intArrayOf(10, 12, 9), null), track("vide", intArrayOf(5000, 300, 7000, 200), intArrayOf(1, 3))),
            box("mdat", ByteArray(64)),
        )
        assertEquals(Mp4Samples.Track(4, true, 7000), Mp4Samples.read(movie, Mp4Samples.VIDEO))
        // No table of key frames: every sample is one.
        assertEquals(Mp4Samples.Track(3, true, 12), Mp4Samples.read(movie, Mp4Samples.SOUND))
    }

    @Test fun aSectionThatDoesNotOpenOnAKeyFrameIsSaidSo() {
        val movie = file(box("moov", track("vide", intArrayOf(300, 7000), intArrayOf(2))))
        assertEquals(Mp4Samples.Track(2, false, 7000), Mp4Samples.read(movie, Mp4Samples.VIDEO))
    }

    @Test fun samplesOfOneSizeAndTheIndexAfterTheData() {
        val movie = file(box("ftyp", "isom".toByteArray()), box("mdat", ByteArray(32)), box("moov", track("soun", IntArray(5), null, fixed = 6)))
        assertEquals(Mp4Samples.Track(5, true, 6), Mp4Samples.read(movie, Mp4Samples.SOUND))
    }

    @Test fun noTrackOfTheKindOrABrokenFileIsNothing() {
        val sound = file(box("moov", track("soun", intArrayOf(4), null)))
        assertNull(Mp4Samples.read(sound, Mp4Samples.VIDEO))
        // An interrupted write: the index is cut short.
        val whole = box("moov", track("vide", intArrayOf(1, 2, 3), null))
        assertNull(Mp4Samples.read(file(whole.copyOf(whole.size - 6)), Mp4Samples.VIDEO))
        assertNull(Mp4Samples.read(file(), Mp4Samples.VIDEO))
        assertNull(Mp4Samples.read(File(temporary.root, "missing.mp4"), Mp4Samples.VIDEO))
    }
}
