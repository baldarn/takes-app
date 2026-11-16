@file:OptIn(UnstableApi::class)

package app.takes

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import app.takes.core.TrackMixEngine
import app.takes.core.TrackMixPiece
import app.takes.core.TrackMixPlan
import app.takes.core.TrackMixer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * The preview's sound: the audio engine playing the mix plan of the edit into
 * one [AudioTrack], as the export mixes it, every level, fade, cut fade,
 * transition ramp, chain and censor sample by sample, any number of sounds at
 * once. It follows the picture's clock ([follow]): when what is heard drifts
 * from the picture by more than [DRIFT] seconds it starts again from the
 * picture's second. At another review speed the mix goes through Sonic, its
 * pitch kept.
 *
 * The mix is made on a thread of its own a little ahead of what is heard
 * ([AHEAD]); a piece's recording is opened as its first frame is mixed.
 */
internal class MixPlayback {
    private val rate = MIX_RATE
    private val channels = 2
    private val lock = Object()
    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
        .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT), (AHEAD * rate).toInt() * channels * 4))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    // Guarded by [lock]. The engines themselves are used by the mixing thread alone.
    private var engine: TrackMixEngine? = null
    private val retired = ArrayList<TrackMixEngine>()
    /** The plan of [engine], the files it reads, and new levels for it that the mixing thread has not taken yet ([TrackMixEngine.adopt]). */
    private var loaded: TrackMixPlan? = null
    private var loadedFiles: Map<String, File>? = null
    private var levels: TrackMixPlan? = null

    /** Whether [a] and [b] differ in their levels alone. */
    private fun same(a: TrackMixPlan, b: TrackMixPlan): Boolean =
        a.duration == b.duration && a.lanes.size == b.lanes.size && a.lanes.zip(b.lanes).all { (x, y) ->
            x.pieces.size == y.pieces.size && x.pieces.zip(y.pieces).all { (p, q) -> p == q.copy(envelope = p.envelope) }
        }
    private var playing = false
    private var speed = 1.0
    private var generation = 0
    private var alive = true
    /** The frame the mix starts again from, for the mixing thread to seek to. */
    private var seekTo: Long? = null
    /** The second of the movie the first frame written since the last start is. */
    private var origin = 0.0
    /** How long the output takes from a frame written to the frame heard, in seconds: the mix starts that much ahead of the picture. */
    private var latency = INITIAL_LATENCY
    private var calibrated = false
    private var started = 0L

    private val thread = Thread(::run, "Preview mix").apply { priority = Thread.MAX_PRIORITY; start() }

    /** Takes the mix [plan] to play, read from [files] or the processed sound [processed] gives; playing, it goes on from where it is heard. */
    fun load(plan: TrackMixPlan, files: Map<String, File>, processed: (TrackMixPiece) -> File?) {
        synchronized(lock) {
            // Only the levels changed, as when one is tried while the movie plays: the mix takes them where it is.
            if (engine != null && loaded?.let { same(it, plan) } == true && files == loadedFiles) {
                levels = plan
                loaded = plan
                return
            }
        }
        val next = audioEngine(plan, files, processed, rate, channels)
        synchronized(lock) {
            loaded = plan
            loadedFiles = files
            levels = null
            engine?.let { retired += it }
            engine = next
            if (playing) restart(heard())
            lock.notifyAll()
        }
    }

    /** Plays from [time] of the movie at [speed] times its pace. */
    fun play(time: Double, speed: Double = 1.0) {
        synchronized(lock) {
            this.speed = speed
            playing = true
            restart(time)
            lock.notifyAll()
        }
    }

    fun pause() {
        synchronized(lock) {
            if (!playing) return
            playing = false
            generation++
            runCatching { track.pause(); track.flush() }
        }
    }

    /** Goes to [time]; while playing, the sound starts again from there. */
    fun seek(time: Double) {
        synchronized(lock) { if (playing) restart(time) }
    }

    /** Keeps the sound with the picture, which is at [time]: call once per frame while playing. */
    fun follow(time: Double) {
        synchronized(lock) {
            if (!playing) return
            // Until the output reports where it is, it is taken to be where it began.
            if (System.nanoTime() - started < SETTLING) return
            val off = time - heard()
            // The first time after the output has settled, a smaller drift is worth a start again: the latency is then known.
            val calibrating = !calibrated
            calibrated = true
            if (abs(off) > (if (calibrating) CALIBRATION else DRIFT)) {
                // What is heard is later than what is written by the output's latency: it is learnt from the drift.
                latency = (latency + off / speed).coerceIn(0.0, MAXIMUM_LATENCY)
                restart(time)
            }
        }
    }

    /** The second of the movie being heard now, from the output's own timestamp. */
    private fun heard(): Double {
        val stamp = AudioTimestamp()
        val frames = if (track.getTimestamp(stamp)) stamp.framePosition + (System.nanoTime() - stamp.nanoTime) * rate / 1_000_000_000L
        else track.playbackHeadPosition.toLong()
        return origin + maxOf(0L, frames) * speed / rate
    }

    // Under [lock].
    private fun restart(time: Double) {
        generation++
        runCatching { track.pause(); track.flush() }
        origin = (time + latency * speed).coerceAtLeast(0.0)
        seekTo = TrackMixer.firstFrame(app.takes.core.EditTime.of(origin), rate)
        started = System.nanoTime()
        runCatching { track.play() }
    }

    private fun run() {
        val block = 1024
        val mixed = FloatArray(block * channels)
        var pending = FloatArray(block * channels * 4)
        var pendingSize = 0
        var pendingAt = 0
        var mine = -1
        var sonic: SonicAudioProcessor? = null
        val input = ByteBuffer.allocateDirect(block * channels * 4).order(ByteOrder.nativeOrder())
        val closing = ArrayList<TrackMixEngine>()
        while (true) {
            var seek: Long? = null
            var retune: TrackMixPlan? = null
            synchronized(lock) {
                while (alive && (!playing || engine == null) && retired.isEmpty()) lock.wait()
                closing += retired
                retired.clear()
                if (!alive) { closing += listOfNotNull(engine); engine = null }
            }
            closing.forEach { runCatching { it.close() } }
            closing.clear()
            val mix = synchronized(lock) {
                if (!alive) return
                if (!playing || engine == null) return@synchronized null
                if (mine != generation) {
                    mine = generation
                    pendingSize = 0; pendingAt = 0
                    seek = seekTo
                    seekTo = null
                    sonic = if (speed == 1.0) null else SonicAudioProcessor().apply {
                        setSpeed(speed.toFloat())
                        configure(AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_FLOAT))
                        flush(AudioProcessor.StreamMetadata.DEFAULT)
                    }
                }
                retune = levels
                levels = null
                engine
            } ?: continue
            retune?.let { mix.adopt(it) }
            seek?.let { mix.seek(it) }
            if (pendingAt >= pendingSize) {
                pendingSize = 0; pendingAt = 0
                // Past the end of the mix it is silence, until the picture stops.
                mix.render(mixed, block)
                val stretch = sonic
                if (stretch == null) {
                    System.arraycopy(mixed, 0, pending, 0, block * channels)
                    pendingSize = block * channels
                } else {
                    input.clear()
                    input.asFloatBuffer().put(mixed, 0, block * channels)
                    input.limit(block * channels * 4)
                    while (input.hasRemaining()) {
                        stretch.queueInput(input)
                        val output = stretch.output.order(ByteOrder.nativeOrder())
                        val floats = output.asFloatBuffer()
                        val count = floats.remaining()
                        if (pending.size < pendingSize + count) pending = pending.copyOf(maxOf(pending.size * 2, pendingSize + count))
                        floats.get(pending, pendingSize, count)
                        output.position(output.limit())
                        pendingSize += count
                    }
                }
            }
            if (pendingAt >= pendingSize) continue
            // Written under the lock, so that nothing mixed before a start again reaches the output after it.
            val written = synchronized(lock) {
                if (mine != generation) -1 else track.write(pending, pendingAt, pendingSize - pendingAt, AudioTrack.WRITE_NON_BLOCKING)
            }
            if (written > 0) pendingAt += written else if (written == 0) Thread.sleep(4)
        }
    }

    fun release() {
        synchronized(lock) {
            alive = false
            playing = false
            lock.notifyAll()
        }
        thread.join(1000)
        runCatching { track.release() }
    }

    private companion object {
        /** How far ahead of what is heard the mix is made, in seconds. */
        const val AHEAD = 0.25

        /** How far the sound may drift from the picture before it starts again from the picture's second. */
        const val DRIFT = 0.06

        /** How far it may be from the picture when the output's latency is first measured. */
        const val CALIBRATION = 0.02

        /** The output's latency taken before it is measured, and the most it is taken to be, in seconds. */
        const val INITIAL_LATENCY = 0.1
        const val MAXIMUM_LATENCY = 1.0

        /** How long after a start the output is left to settle before its place is compared with the picture's, in nanoseconds. */
        const val SETTLING = 400_000_000L
    }
}
