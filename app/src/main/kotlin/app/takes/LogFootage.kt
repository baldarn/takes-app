package app.takes

import android.util.LruCache
import app.takes.core.LUTInput
import java.io.File
import java.io.RandomAccessFile

/**
 * Tells a recording in Apple Log from its file, as the original tells it
 * from the format of its video track (YouRec/LogColor.swift:46-60), so its
 * picture is decoded to display before any grade ([app.takes.core.ColorGrade.applyLog]).
 *
 * Android's media framework does not report a log transfer function: a
 * MediaFormat knows only the standard transfers (SDR, HLG, PQ). The original
 * reads the format's `LogTransferFunction`, named "AppleLog" or "AppleLog2",
 * from the track's sample description, which lives in the movie's header;
 * so the header is read here and the name looked for in it. A file whose
 * header names neither is taken as an ordinary recording. How the name is
 * written there has not been checked against a real Apple Log recording.
 */
object LogFootage {
    private val known = LruCache<String, Result>(64)

    private class Result(val profile: LUTInput?)

    /** The largest movie header read: a phone's is a few hundred kilobytes even for long takes. */
    private const val LARGEST_HEADER = 64L shl 20

    private val LOG2 = "AppleLog2".toByteArray()
    private val LOG = "AppleLog".toByteArray()

    /** The log profile of the recording in [file], or null for an ordinary one or one that cannot be read. */
    fun profile(file: File?): LUTInput? {
        if (file == null || !file.isFile) return null
        val key = "${file.path}:${file.length()}:${file.lastModified()}"
        known.get(key)?.let { return it.profile }
        val found = runCatching { header(file)?.let(::named) }.getOrNull()
        known.put(key, Result(found))
        return found
    }

    /** The profile a movie header names, Apple Log 2 before Apple Log since its name begins with the other. */
    internal fun named(header: ByteArray): LUTInput? = when {
        contains(header, LOG2) -> LUTInput.AppleLog2
        contains(header, LOG) -> LUTInput.AppleLog
        else -> null
    }

    private fun contains(data: ByteArray, token: ByteArray): Boolean {
        outer@ for (start in 0..data.size - token.size) {
            for (i in token.indices) if (data[start + i] != token[i]) continue@outer
            return true
        }
        return false
    }

    /** The `moov` box of an MP4 or QuickTime file, read whole; null when there is none or it is too large. */
    private fun header(file: File): ByteArray? = RandomAccessFile(file, "r").use { input ->
        var at = 0L
        val length = input.length()
        val head = ByteArray(16)
        while (at + 8 <= length) {
            input.seek(at)
            input.readFully(head, 0, 8)
            var size = ((head[0].toLong() and 0xff) shl 24) or ((head[1].toLong() and 0xff) shl 16) or ((head[2].toLong() and 0xff) shl 8) or (head[3].toLong() and 0xff)
            val type = String(head, 4, 4, Charsets.US_ASCII)
            var body = 8L
            if (size == 1L) {
                input.readFully(head, 8, 8)
                size = (8 until 16).fold(0L) { value, i -> (value shl 8) or (head[i].toLong() and 0xff) }
                body = 16L
            } else if (size == 0L) size = length - at
            if (size < body) return@use null
            if (type == "moov") {
                if (size - body > LARGEST_HEADER) return@use null
                return@use ByteArray((size - body).toInt()).also { input.seek(at + body); input.readFully(it) }
            }
            at += size
        }
        null
    }
}
