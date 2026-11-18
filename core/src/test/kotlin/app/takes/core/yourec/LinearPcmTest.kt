package app.takes.core.yourec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

class LinearPcmTest {
    @get:Rule val temporary = TemporaryFolder()

    /** A CAF as Core Audio writes one: `desc`, then `data` with its edit count (Apple's CAF specification). */
    internal fun caf(samples: ByteArray, rate: Double, channels: Int, bits: Int, flags: Int, format: String = "lpcm", dataSize: Long? = null): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).apply {
                writeBytes("caff"); writeShort(1); writeShort(0)
                writeBytes("desc"); writeLong(32)
                writeDouble(rate); writeBytes(format); writeInt(flags)
                writeInt(channels * bits / 8); writeInt(1); writeInt(channels); writeInt(bits)
                // A chunk the reader passes over.
                writeBytes("chan"); writeLong(12); write(ByteArray(12))
                writeBytes("data"); writeLong(dataSize ?: (samples.size + 4L)); writeInt(0)
                write(samples)
            }
        }.toByteArray()

    private fun aiff(samples: ByteArray, channels: Int, bits: Int, compression: String? = null): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).apply {
            val common = if (compression == null) 18 else 24
            writeBytes("FORM"); writeInt(4 + 8 + common + 8 + 8 + samples.size)
            writeBytes(if (compression == null) "AIFF" else "AIFC")
            writeBytes("COMM"); writeInt(common)
            writeShort(channels); writeInt(samples.size / channels / (bits / 8)); writeShort(bits)
            // 48000 as an 80-bit extended number.
            writeShort(0x400E); writeLong(0xBB80000000000000uL.toLong())
            if (compression != null) { writeBytes(compression); writeByte(0); writeByte(0) }
            writeBytes("SSND"); writeInt(8 + samples.size); writeInt(0); writeInt(0)
            write(samples)
        }
    }.toByteArray()

    private fun wav(samples: ByteArray, rate: Int, channels: Int, bits: Int, float: Boolean = false): ByteArray = ByteArrayOutputStream().also { bytes ->
        fun int16(value: Int) { bytes.write(value and 0xFF); bytes.write(value shr 8 and 0xFF) }
        fun int32(value: Int) { for (shift in 0 until 32 step 8) bytes.write(value shr shift and 0xFF) }
        bytes.write("RIFF".toByteArray()); int32(36 + samples.size); bytes.write("WAVE".toByteArray())
        bytes.write("fmt ".toByteArray()); int32(16); int16(if (float) 3 else 1); int16(channels)
        int32(rate); int32(rate * channels * bits / 8); int16(channels * bits / 8); int16(bits)
        bytes.write("data".toByteArray()); int32(samples.size); bytes.write(samples)
    }.toByteArray()

    private fun convert(input: ByteArray, name: String = "voice.caf"): ByteArray? {
        val source = File(temporary.root, name).apply { writeBytes(input) }
        val target = File(temporary.root, "out.wav")
        target.delete()
        return if (LinearPcm.toWav(source, target)) target.readBytes() else null.also { assertFalse(target.exists()) }
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test fun theOriginalsVoiceRecordingsKeepTheirSamples() {
        // 16-bit little-endian mono at 48 kHz (YouRec/VoiceRecorder.swift:115-117).
        val samples = bytes(1, 2, 3, 4, 0xFF, 0x7F)
        assertArrayEquals(wav(samples, 48_000, 1, 16), convert(caf(samples, 48_000.0, 1, 16, flags = 2)))
        // A size of -1 runs to the end of the file.
        assertArrayEquals(wav(samples, 48_000, 1, 16), convert(caf(samples, 48_000.0, 1, 16, flags = 2, dataSize = -1)))
        // A sample cut short at the end is left out.
        assertArrayEquals(wav(samples, 48_000, 1, 16), convert(caf(samples + bytes(9), 48_000.0, 1, 16, flags = 2, dataSize = -1)))
    }

    @Test fun bigEndianSamplesAreSwapped() {
        assertArrayEquals(wav(bytes(3, 2, 1, 6, 5, 4), 44_100, 2, 24), convert(caf(bytes(1, 2, 3, 4, 5, 6), 44_100.0, 2, 24, flags = 0)))
        assertArrayEquals(wav(bytes(2, 1, 4, 3), 48_000, 2, 16), convert(aiff(bytes(1, 2, 3, 4), 2, 16), "voice.aif"))
        assertArrayEquals(wav(bytes(1, 2, 3, 4), 48_000, 2, 16), convert(aiff(bytes(1, 2, 3, 4), 2, 16, "sowt"), "voice.aifc"))
        // 8-bit samples are signed in CAF and unsigned in WAV.
        assertArrayEquals(wav(bytes(0x80, 0x7F, 0xFF), 8_000, 1, 8), convert(caf(bytes(0, 0xFF, 0x7F), 8_000.0, 1, 8, flags = 0)))
    }

    @Test fun floatsBecomeThirtyTwoBitFloats() {
        val single = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(0.5f).array()
        val double = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putDouble(0.5).array()
        assertArrayEquals(wav(single, 48_000, 1, 32, float = true), convert(caf(single, 48_000.0, 1, 32, flags = 3)))
        assertArrayEquals(wav(single, 48_000, 1, 32, float = true), convert(caf(double, 48_000.0, 1, 64, flags = 3)))
    }

    @Test fun compressedSoundIsNotConverted() {
        assertTrue(convert(caf(bytes(1, 2, 3, 4), 48_000.0, 1, 16, flags = 2, format = "aac ")) == null)
        assertTrue(convert(aiff(bytes(1, 2, 3, 4), 1, 16, "ima4"), "voice.aifc") == null)
        assertTrue(convert("not a sound".toByteArray()) == null)
        assertTrue(convert(ByteArray(0)) == null)
    }
}
