package app.takes.speech

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads the plain kind of WAV file the test recordings are: 16-bit PCM. Channels are averaged. */
object Wav {
    fun read(file: File): Pair<FloatArray, Int> {
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.getInt(0) == 0x46464952 && bytes.getInt(8) == 0x45564157) { "$file is not a WAV file" }
        var position = 12
        var rate = 0
        var channels = 0
        while (position + 8 <= bytes.limit()) {
            val id = bytes.getInt(position)
            val size = bytes.getInt(position + 4)
            val body = position + 8
            if (id == 0x20746d66) { // "fmt "
                require(bytes.getShort(body).toInt() == 1 && bytes.getShort(body + 14).toInt() == 16) { "$file is not 16-bit PCM" }
                channels = bytes.getShort(body + 2).toInt()
                rate = bytes.getInt(body + 4)
            } else if (id == 0x61746164) { // "data"
                val frames = minOf(size, bytes.limit() - body) / (2 * channels)
                val samples = FloatArray(frames) { frame ->
                    var sum = 0f
                    for (channel in 0 until channels) sum += bytes.getShort(body + (frame * channels + channel) * 2) / 32768f
                    sum / channels
                }
                return samples to rate
            }
            position = body + size + (size and 1)
        }
        error("$file has no audio")
    }
}
