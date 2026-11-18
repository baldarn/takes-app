package app.takes.core.yourec

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlin.math.roundToLong

/**
 * Sound the iPhone saves as CAF or AIFF, which Android does not play, written
 * again as WAV with the same samples (docs/IOS-INTEROP.md, 4.2). Only linear
 * PCM is rewritten: the samples are copied, swapped to little-endian where
 * they were big-endian, and 64-bit floats become 32-bit ones, the widest
 * Android reads. The original's voice recordings are 16-bit little-endian PCM
 * in CAF (YouRec/VoiceRecorder.swift:115-117). Anything compressed, such as
 * AAC or Apple Lossless in a CAF, is not converted.
 */
object LinearPcm {
    /** The extensions of the files [toWav] reads. */
    val EXTENSIONS = setOf("caf", "aif", "aiff", "aifc")

    /** How the samples of a file are laid out. */
    private data class Format(
        val sampleRate: Double,
        val channels: Int,
        val bits: Int,
        val float: Boolean,
        val bigEndian: Boolean,
        /** Where the samples begin in the file, and how many bytes they take; -1 to the end of the file. */
        val offset: Long,
        val length: Long,
    ) {
        val bytesPerSample: Int get() = bits / 8
        val valid: Boolean
            get() = sampleRate.isFinite() && sampleRate >= 1 && sampleRate <= 1_000_000 && channels in 1..32 &&
                (if (float) bits == 32 || bits == 64 else bits == 8 || bits == 16 || bits == 24 || bits == 32)
    }

    /**
     * Writes the samples of [input] as a WAV file at [output]. False, with
     * nothing left at [output], when [input] is not linear PCM in CAF or AIFF.
     */
    fun toWav(input: File, output: File): Boolean {
        val format = try { format(input) } catch (_: Exception) { null }
        if (format == null || !format.valid) return false
        val available = input.length() - format.offset
        val frame = format.channels * format.bytesPerSample
        val length = (if (format.length < 0) available else minOf(format.length, available)) / frame * frame
        val outBits = if (format.float) 32 else format.bits
        val outLength = length / format.bytesPerSample * (outBits / 8)
        if (length <= 0 || outLength > 0xFFFF_FFFFL - 36) return false
        try {
            BufferedOutputStream(FileOutputStream(output), 1 shl 16).use { out ->
                header(out, format, outBits, outLength)
                BufferedInputStream(FileInputStream(input), 1 shl 16).use { stream ->
                    skipFully(stream, format.offset)
                    copySamples(stream, out, format, length)
                }
            }
            return true
        } catch (error: Exception) {
            output.delete()
            throw error
        }
    }

    private fun format(file: File): Format? = RandomAccessFile(file, "r").use { data ->
        val magic = ByteArray(4).also { data.readFully(it) }.decodeToString()
        when (magic) {
            "caff" -> caf(data)
            "FORM" -> aiff(data)
            else -> null
        }
    }

    /** Core Audio Format: big-endian chunks, `desc` then `data` (Apple's CAF specification). */
    private fun caf(data: RandomAccessFile): Format? {
        data.seek(8)
        var description: Format? = null
        while (data.filePointer + 12 <= data.length()) {
            val type = ByteArray(4).also { data.readFully(it) }.decodeToString()
            val size = data.readLong()
            val start = data.filePointer
            when (type) {
                "desc" -> {
                    val rate = data.readDouble()
                    val id = ByteArray(4).also { data.readFully(it) }.decodeToString()
                    val flags = data.readInt()
                    val bytesPerPacket = data.readInt()
                    val framesPerPacket = data.readInt()
                    val channels = data.readInt()
                    val bits = data.readInt()
                    if (id != "lpcm" || framesPerPacket != 1 || bits <= 0 || bits % 8 != 0 || bytesPerPacket != channels * bits / 8) return null
                    description = Format(rate, channels, bits, float = flags and 1 != 0, bigEndian = flags and 2 == 0, offset = 0, length = 0)
                }
                "data" -> {
                    val found = description ?: return null
                    // The edit count comes first; a size of -1 runs to the end of the file.
                    return found.copy(offset = start + 4, length = if (size < 0) -1 else size - 4)
                }
            }
            if (size < 0) return null
            data.seek(start + size)
        }
        return null
    }

    /** AIFF and AIFF-C: big-endian chunks, `COMM` and `SSND`, each padded to an even length. */
    private fun aiff(data: RandomAccessFile): Format? {
        data.seek(8)
        val form = ByteArray(4).also { data.readFully(it) }.decodeToString()
        if (form != "AIFF" && form != "AIFC") return null
        var common: Format? = null
        var sound: Pair<Long, Long>? = null
        while (data.filePointer + 8 <= data.length()) {
            val type = ByteArray(4).also { data.readFully(it) }.decodeToString()
            val size = data.readInt().toLong() and 0xFFFF_FFFFL
            val start = data.filePointer
            when (type) {
                "COMM" -> {
                    val channels = data.readShort().toInt()
                    data.readInt() // frames
                    val bits = data.readShort().toInt()
                    val rate = extended(data)
                    var float = false
                    var bigEndian = true
                    if (form == "AIFC") {
                        when (ByteArray(4).also { data.readFully(it) }.decodeToString()) {
                            "NONE", "twos" -> {}
                            "sowt" -> bigEndian = false
                            "fl32", "FL32", "fl64", "FL64" -> float = true
                            else -> return null
                        }
                    }
                    // Sample sizes that are not whole bytes are stored in the next whole byte, left-justified.
                    val stored = (bits + 7) / 8 * 8
                    common = Format(rate, channels, stored, float, bigEndian, 0, 0)
                }
                "SSND" -> {
                    val offset = data.readInt().toLong() and 0xFFFF_FFFFL
                    sound = (start + 8 + offset) to (size - 8 - offset)
                }
            }
            data.seek(start + size + (size and 1))
        }
        val (offset, length) = sound ?: return null
        return common?.copy(offset = offset, length = length)
    }

    /** An IEEE 754 80-bit extended number, as AIFF gives its sample rate. */
    private fun extended(data: RandomAccessFile): Double {
        val exponentWord = data.readShort().toInt() and 0xFFFF
        val mantissa = data.readLong()
        val sign = if (exponentWord and 0x8000 != 0) -1.0 else 1.0
        val exponent = (exponentWord and 0x7FFF) - 16383 - 63
        val unsigned = (mantissa ushr 1).toDouble() * 2 + (mantissa and 1)
        return sign * unsigned * Math.pow(2.0, exponent.toDouble())
    }

    private fun header(out: OutputStream, format: Format, bits: Int, length: Long) {
        val fmt = if (format.float) 3 else 1
        val blockAlign = format.channels * bits / 8
        val rate = format.sampleRate.roundToLong()
        out.write("RIFF".toByteArray()); int32(out, 36 + length)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); int32(out, 16)
        int16(out, fmt); int16(out, format.channels)
        int32(out, rate); int32(out, rate * blockAlign)
        int16(out, blockAlign); int16(out, bits)
        out.write("data".toByteArray()); int32(out, length)
    }

    private fun copySamples(input: InputStream, out: OutputStream, format: Format, length: Long) {
        val size = format.bytesPerSample
        val block = ByteArray(size * 16_384)
        val converted = ByteArray(block.size)
        val data = DataInputStream(input)
        var remaining = length
        while (remaining > 0) {
            val count = minOf(block.size.toLong(), remaining).toInt()
            data.readFully(block, 0, count)
            remaining -= count
            var written = 0
            for (start in 0 until count step size) {
                // Little-endian, whatever the file had.
                if (format.bigEndian) for (index in 0 until size / 2) {
                    val swap = block[start + index]; block[start + index] = block[start + size - 1 - index]; block[start + size - 1 - index] = swap
                }
                when {
                    format.float && size == 8 -> {
                        var raw = 0L
                        for (index in 7 downTo 0) raw = (raw shl 8) or (block[start + index].toLong() and 0xFF)
                        val single = java.lang.Float.floatToRawIntBits(java.lang.Double.longBitsToDouble(raw).toFloat())
                        for (shift in 0 until 32 step 8) converted[written++] = (single shr shift).toByte()
                    }
                    // WAV's 8-bit samples are unsigned; those of CAF and AIFF are signed.
                    !format.float && size == 1 -> converted[written++] = (block[start].toInt() xor 0x80).toByte()
                    else -> { System.arraycopy(block, start, converted, written, size); written += size }
                }
            }
            out.write(converted, 0, written)
        }
    }

    private fun int16(out: OutputStream, value: Int) {
        out.write(value and 0xFF); out.write((value shr 8) and 0xFF)
    }

    private fun int32(out: OutputStream, value: Long) {
        for (shift in 0 until 32 step 8) out.write(((value shr shift) and 0xFF).toInt())
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped <= 0) { if (input.read() < 0) throw EOFException() else left -= 1 } else left -= skipped
        }
    }
}
