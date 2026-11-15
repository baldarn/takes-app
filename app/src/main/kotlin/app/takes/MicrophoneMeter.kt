package app.takes

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.SystemClock
import app.takes.core.LevelMeter
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * The level of the microphone, for the recorder's meter, as the original runs it: every buffer of sound
 * moves a [LevelMeter], and the screen reads it once per frame.
 *
 * The sound comes from two places, never both at once. While a segment is written the camera library holds
 * the microphone and reports the peak of its latest buffer with each status ([reported]). The rest of the
 * time the recorder is on screen this class listens itself ([listen]), with the source the next segment
 * will record from, and keeps nothing of what it hears but the peak of each buffer.
 */
class MicrophoneMeter {
    private val lock = Any()
    private var meter = LevelMeter()
    private var reportedAt = 0.0
    private var listener: Listener? = null

    private class Listener(val source: Int, val record: AudioRecord) {
        @Volatile var running = true
        var thread: Thread? = null
    }

    private fun now() = SystemClock.elapsedRealtimeNanos() / 1e9

    /** What the meter shows now. */
    fun reading(): LevelMeter.Reading = synchronized(lock) { meter.reading(now()) }

    /** Whether a take asked for at [requestedAt], in seconds of the elapsed-time clock, has gone without sound for too long. */
    fun silentTooLong(requestedAt: Double): Boolean = synchronized(lock) { meter.shouldPause(now(), requestedAt) }

    /**
     * The camera library's report of the peak of its latest buffer, with full scale at 1. It comes with each
     * frame written and not with each buffer, so the level falls by what the original's buffers would have
     * taken off in the time since the report before.
     */
    fun reported(peak: Double) {
        val now = now()
        synchronized(lock) {
            val release = if (reportedAt == 0.0) LevelMeter.RELEASE else LevelMeter.releaseOver(now - reportedAt)
            reportedAt = now
            meter = meter.heard(peak, now, release)
        }
    }

    /** A segment begins or has ended: the next report is the first of its run. */
    fun newRun() = synchronized(lock) { reportedAt = 0.0 }

    val listening: Boolean get() = listener != null

    /**
     * Listens to the microphone through [source] until [stopListening]. The caller has the permission to
     * record. Buffers are the size the original asks for, so the level falls at its pace.
     */
    @SuppressLint("MissingPermission")
    fun listen(source: Int) {
        if (listener?.source == source) return
        stopListening()
        val record = runCatching {
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(LevelMeter.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
            val minimum = AudioRecord.getMinBufferSize(LevelMeter.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord.Builder().setAudioSource(source).setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minimum, LevelMeter.BUFFER_FRAMES * 4)).build()
                .also { if (it.state != AudioRecord.STATE_INITIALIZED) { it.release(); error("not initialized") } }
        }.getOrNull() ?: return
        val next = Listener(source, record)
        next.thread = thread(name = "microphone-meter") {
            val buffer = ShortArray(LevelMeter.BUFFER_FRAMES)
            runCatching {
                record.startRecording()
                while (next.running) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read <= 0) break
                    var peak = 0
                    for (index in 0 until read) peak = maxOf(peak, abs(buffer[index].toInt()))
                    // Full scale as the original has it for 16 bit samples.
                    val heard = peak / 32768.0
                    if (next.running) synchronized(lock) { meter = meter.heard(heard, now()) }
                }
            }
            runCatching { record.release() }
        }
        listener = next
    }

    /** Lets the microphone go before the recorder takes it; returns once nothing more is read. */
    fun stopListening() {
        val current = listener ?: return
        listener = null
        current.running = false
        runCatching { current.record.stop() }
        runCatching { current.thread?.join(300) }
    }
}
