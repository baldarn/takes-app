package app.takes.core

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * The numbers of the editor's voice recorder, "Record now" in Add audio
 * (YouRec/VoiceRecorder.swift:97-414): every take is 48 kHz mono 16-bit PCM,
 * how long the next take may last on the free storage, the level shown under
 * the clock, and where the finished recording goes on the timeline.
 */
object VoiceRecording {
    /** The recorder's format (VoiceRecorder.swift:121-122). */
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 1
    const val BYTES_PER_FRAME = 2

    /** Bytes a second of a take (VoiceRecorder.swift:110). */
    const val BYTES_PER_SECOND = SAMPLE_RATE * BYTES_PER_FRAME.toDouble()

    /** Room kept for the files' headers beside the takes (VoiceRecorder.swift:111). */
    const val HEADER_ROOM = 65_536L

    /** The shortest take the free storage must allow before recording starts (VoiceRecorder.swift:115). */
    const val MINIMUM_SECONDS = 1.0

    /** The quietest average level the meter shows, in dBFS (VoiceRecorder.swift:44). */
    const val METER_FLOOR_DB = -55.0

    /**
     * The longest the next take may last, in seconds, with [free] bytes free
     * above [floor] and [recorded] seconds already taken: room is kept to join
     * the takes before it and to add the finished file to the project, so a
     * first take is counted twice and a later one three times
     * (VoiceRecorder.swift:110-114).
     */
    fun maxSeconds(free: Long, floor: Long, recorded: Double, firstTake: Boolean): Double {
        val reserveForTakes = (recorded * BYTES_PER_SECOND).toLong() * 2 + HEADER_ROOM
        val copies = if (firstTake) 2.0 else 3.0
        return (free - floor - reserveForTakes) / (copies * BYTES_PER_SECOND)
    }

    /** Whether there is room for a take at all (VoiceRecorder.swift:115). */
    fun canRecord(maxSeconds: Double): Boolean = maxSeconds >= MINIMUM_SECONDS

    /**
     * The fraction of the level bar for an average power of [decibels] dBFS:
     * −55 dB and below is empty, 0 dB full (VoiceRecorder.swift:41-45).
     */
    fun level(decibels: Double): Double = ((decibels - METER_FLOOR_DB) / -METER_FLOOR_DB).coerceIn(0.0, 1.0)

    /** The average power in dBFS of [count] 16-bit samples, as the original's meter averages them; −160 for silence. */
    fun averagePower(samples: ShortArray, count: Int = samples.size): Double {
        if (count <= 0) return SILENCE_DB
        var sum = 0.0
        for (index in 0 until count) {
            val value = samples[index] / 32768.0
            sum += value * value
        }
        val rms = kotlin.math.sqrt(sum / count)
        return if (rms <= 0) SILENCE_DB else maxOf(SILENCE_DB, 20 * kotlin.math.log10(rms))
    }

    /** What AVAudioRecorder reports for silence. */
    const val SILENCE_DB = -160.0

    /** Seconds in [frames] frames of a take. */
    fun seconds(frames: Long): Double = frames / SAMPLE_RATE.toDouble()

    /**
     * Where the recording goes: at the playhead, kept inside the movie
     * (ProjectStore.swift:472). The same place for an imported sound.
     */
    fun position(playhead: Double, movieDuration: Double): Double =
        if (!playhead.isFinite()) 0.0 else minOf(maxOf(0.0, movieDuration), maxOf(0.0, playhead))

    /** The record button's words: Stop while recording, Record more once there is a take (VoiceRecorder.swift:388-391). */
    enum class RecordTitle { Record, RecordMore, Stop }

    fun recordTitle(recording: Boolean, hasAudio: Boolean): RecordTitle = when {
        recording -> RecordTitle.Stop
        hasAudio -> RecordTitle.RecordMore
        else -> RecordTitle.Record
    }
}

/**
 * A WAV file of the voice recorder's format. A take is written with a header
 * that names no length, then [finish] writes its length once the take has
 * stopped. Takes are joined by copying their samples one after another under
 * one header: every take has the same format, so nothing is converted
 * (VoiceRecorder.swift:3-4, 205-235).
 */
object WaveFile {
    const val HEADER_BYTES = 44

    /** The 44-byte header of a PCM WAV file holding [dataBytes] bytes of samples. */
    fun header(
        dataBytes: Long,
        sampleRate: Int = VoiceRecording.SAMPLE_RATE,
        channels: Int = VoiceRecording.CHANNELS,
        bitsPerSample: Int = VoiceRecording.BYTES_PER_FRAME * 8,
    ): ByteArray {
        require(dataBytes in 0..MAXIMUM_DATA) { "A WAV file holds at most 4 GB" }
        val blockAlign = channels * bitsPerSample / 8
        val bytes = ByteArray(HEADER_BYTES)
        fun ascii(at: Int, text: String) = text.forEachIndexed { i, c -> bytes[at + i] = c.code.toByte() }
        fun int32(at: Int, value: Long) { for (i in 0 until 4) bytes[at + i] = (value shr (8 * i)).toByte() }
        fun int16(at: Int, value: Int) { for (i in 0 until 2) bytes[at + i] = (value shr (8 * i)).toByte() }
        ascii(0, "RIFF"); int32(4, 36 + dataBytes); ascii(8, "WAVE")
        ascii(12, "fmt "); int32(16, 16); int16(20, 1); int16(22, channels)
        int32(24, sampleRate.toLong()); int32(28, sampleRate.toLong() * blockAlign); int16(32, blockAlign); int16(34, bitsPerSample)
        ascii(36, "data"); int32(40, dataBytes)
        return bytes
    }

    /** The largest data chunk a WAV header can name. */
    const val MAXIMUM_DATA = 0xFFFF_FFFFL - 36

    /** Writes the length of the samples into the header of [file], which they follow; returns their frames. */
    fun finish(file: File): Long {
        RandomAccessFile(file, "rw").use { raf ->
            val data = maxOf(0L, raf.length() - HEADER_BYTES)
            raf.seek(0)
            raf.write(header(data))
            return data / VoiceRecording.BYTES_PER_FRAME
        }
    }

    /** The bytes of samples in [file], as its header names them; null when it is not a WAV file of the recorder's format. */
    fun dataBytes(file: File): Long? {
        if (file.length() < HEADER_BYTES) return null
        val head = ByteArray(HEADER_BYTES)
        RandomAccessFile(file, "r").use { it.readFully(head) }
        val expected = header(0)
        // Everything but the two lengths is the recorder's format.
        for (i in 0 until HEADER_BYTES) if (i !in 4..7 && i !in 40..43 && head[i] != expected[i]) return null
        val data = (0 until 4).fold(0L) { sum, i -> sum or ((head[40 + i].toLong() and 0xFF) shl (8 * i)) }
        return if (data > file.length() - HEADER_BYTES) null else data
    }

    /**
     * Joins the takes [parts] into [output], in order, in bounded chunks
     * (VoiceRecorder.swift:205-235). [proceed] is asked between chunks and
     * stops the join by throwing; a half-written [output] is removed.
     */
    fun join(parts: List<File>, output: File, proceed: () -> Unit = {}) {
        require(parts.isNotEmpty()) { "Nothing to join" }
        try {
            val lengths = parts.map { dataBytes(it) ?: throw IOException("Not a voice take: ${it.name}") }
            output.parentFile?.mkdirs()
            output.outputStream().buffered(CHUNK).use { out ->
                out.write(header(lengths.sum()))
                val buffer = ByteArray(CHUNK)
                for ((part, length) in parts.zip(lengths)) {
                    part.inputStream().use { input ->
                        var skip = HEADER_BYTES.toLong()
                        while (skip > 0) skip -= input.skip(skip).also { if (it <= 0) throw IOException("The take ${part.name} has no samples") }
                        var left = length
                        while (left > 0) {
                            proceed()
                            val read = input.read(buffer, 0, minOf(CHUNK.toLong(), left).toInt())
                            if (read <= 0) throw IOException("The take ${part.name} ended early")
                            out.write(buffer, 0, read)
                            left -= read
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        }
    }

    /** The original reads and writes 32 768 frames at a time (VoiceRecorder.swift:217). */
    private const val CHUNK = 32_768 * VoiceRecording.BYTES_PER_FRAME
}
