@file:OptIn(UnstableApi::class)

package app.takes

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import app.takes.core.ExportAudioLevel
import app.takes.core.toTimeline
import app.takes.core.AudioLevels
import app.takes.core.DecodedFrames
import app.takes.core.LiveAudioGain
import app.takes.core.TrackMixEngine
import app.takes.core.TrackMixPiece
import app.takes.core.TrackMixPlan
import app.takes.core.TrackMixRetime
import app.takes.core.TrackMixRetimed
import app.takes.core.TrackMixRun
import app.takes.core.TrackMixSource
import app.takes.core.TrackMixer
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A file's sound read forwards as a [TrackMixSource]: decoded from a little
 * before [from] seconds, converted to [rate] and [channels] (one channel
 * given to both, or the first two of more; for one channel, the average of
 * all), frame 0 at second 0 of the file as its decoder times it, or [offset]
 * seconds earlier for a file that holds a stretch of its source from there.
 *
 * A file with no sound, or one whose sound cannot be decoded, reads as
 * silence; [failed] tells the second apart.
 */
class DecodedSource(file: File, private val rate: Int, private val channels: Int, from: Double, private val offset: Double = 0.0) : TrackMixSource {
    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var ended = false
    private var idle = 0

    private var sourceChannels = 0
    private var float = false

    /** What has been decoded, read at the engine's rate and channels. */
    private val frames = DecodedFrames(rate, channels)
    private var block = FloatArray(0)

    /** True when the file has sound that could not be decoded. */
    var failed = false
        private set

    init {
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            if (track == null) ended = true else {
                val format = extractor.getTrackFormat(track)
                extractor.selectTrack(track)
                // A little early: the first frames a decoder gives after a jump are not yet at their level.
                // Near the start, from the first sample, without a jump: AAC's priming lies before second 0
                // (an edit list), and the decoder drops its encoder delay from what it is given first, so a jump
                // to second 0 would lose the sound's first 1024 frames in place of the priming.
                val start = from - offset - LEAD
                if (start > 0) extractor.seekTo((start * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                decoder.configure(format, null, null, 0)
                decoder.start()
                codec = decoder
            }
        } catch (problem: Exception) {
            failed = true
            ended = true
        }
    }

    override fun read(first: Long, count: Int, into: FloatArray, offset: Int) {
        frames.read(first, count, into, offset) { decode(); !ended }
    }

    /** Decodes the next block into [frames]; at the end of the track marks it [ended]. */
    private fun decode() {
        val decoder = codec ?: run { ended = true; return }
        try {
            while (true) {
                if (!inputDone) {
                    val input = decoder.dequeueInputBuffer(10_000)
                    if (input >= 0) {
                        val read = extractor.readSampleData(decoder.getInputBuffer(input)!!, 0)
                        if (read < 0) {
                            decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(input, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val output = decoder.dequeueOutputBuffer(info, 10_000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val pcm = decoder.outputFormat
                    val encoding = if (pcm.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcm.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    if (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT) throw IOException("Sound is not decoded to 16 bits or floats")
                    // A format change in the middle of a track is not handled; the first one stands.
                    if (frames.sourceRate == 0) {
                        sourceChannels = pcm.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        float = encoding == AudioFormat.ENCODING_PCM_FLOAT
                        frames.format(pcm.getInteger(MediaFormat.KEY_SAMPLE_RATE), sourceChannels)
                    }
                } else if (output >= 0) {
                    idle = 0
                    val last = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    var filled = false
                    if (info.size > 0 && frames.sourceRate > 0) {
                        val buffer = decoder.getOutputBuffer(output)!!
                        buffer.position(info.offset).limit(info.offset + info.size)
                        take(buffer.order(ByteOrder.nativeOrder()), info.presentationTimeUs)
                        filled = true
                    }
                    decoder.releaseOutputBuffer(output, false)
                    if (last) { ended = true; return }
                    if (filled) return
                } else if (inputDone && ++idle > 500) {
                    ended = true // the decoder never signalled the end
                    return
                }
            }
        } catch (problem: Exception) {
            if (problem is IOException || problem is IllegalStateException) { failed = true; ended = true } else throw problem
        }
    }

    private fun take(bytes: ByteBuffer, time: Long) {
        val count = if (float) bytes.remaining() / 4 else bytes.remaining() / 2
        if (block.size < count) block = FloatArray(count)
        if (float) bytes.asFloatBuffer().get(block, 0, count)
        else { val shorts = bytes.asShortBuffer(); for (i in 0 until count) block[i] = shorts.get(i) / 32768f }
        frames.add(block, count / sourceChannels, time / 1e6 + offset)
    }

    override fun close() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor.release() }
        codec = null
    }

    private companion object {
        const val LEAD = 0.2
    }
}

/**
 * A run at another speed with its pitch kept, as Media3 plays a clip at
 * another speed (Sonic): the source from the run's place, through Sonic at the
 * run's rate, taking exactly as many frames as the run covers on the clock.
 */
class SonicRetime : TrackMixRetime {
    override fun open(source: TrackMixSource, run: TrackMixRun, skip: Long, sampleRate: Int, channels: Int): TrackMixRetimed = Retimed(source, run, skip, sampleRate, channels)

    private class Retimed(private val source: TrackMixSource, run: TrackMixRun, skip: Long, sampleRate: Int, private val channels: Int) : TrackMixRetimed {
        private val sonic = SonicAudioProcessor().apply {
            setSpeed(run.rate.toFloat())
            configure(AudioProcessor.AudioFormat(sampleRate, channels, androidx.media3.common.C.ENCODING_PCM_FLOAT))
            flush(AudioProcessor.StreamMetadata.DEFAULT)
        }
        // The source frame the run is at after [skip] frames of it.
        private var next = Math.round((run.sourceStart + skip.toDouble() / sampleRate * run.rate) * sampleRate)
        private val end = Math.round(run.sourceEnd * sampleRate)
        private val block = FloatArray(BLOCK * channels)
        private val input = ByteBuffer.allocateDirect(BLOCK * channels * 4).order(ByteOrder.nativeOrder())
        private var pending = FloatArray(0)
        private var pendingSize = 0
        private var drained = false

        override fun read(count: Int, into: FloatArray, offset: Int) {
            while (pendingSize < count && !drained) feed()
            val taken = minOf(count, pendingSize)
            System.arraycopy(pending, 0, into, offset * channels, taken * channels)
            java.util.Arrays.fill(into, (offset + taken) * channels, (offset + count) * channels, 0f)
            System.arraycopy(pending, taken * channels, pending, 0, (pendingSize - taken) * channels)
            pendingSize -= taken
        }

        private fun feed() {
            if (next < end) {
                val frames = minOf(BLOCK.toLong(), end - next).toInt()
                source.read(next, frames, block, 0)
                next += frames
                input.clear()
                input.asFloatBuffer().put(block, 0, frames * channels)
                input.limit(frames * channels * 4)
                while (input.hasRemaining()) { sonic.queueInput(input); collect() }
            } else if (!sonic.isEnded) {
                sonic.queueEndOfStream()
                collect()
                if (sonic.isEnded) drained = true
            } else drained = true
            collect()
        }

        private fun collect() {
            val output = sonic.output
            if (!output.hasRemaining()) return
            val floats = output.order(ByteOrder.nativeOrder()).asFloatBuffer()
            val frames = floats.remaining() / channels
            if (pending.size < (pendingSize + frames) * channels) pending = pending.copyOf(maxOf(pending.size * 2, (pendingSize + frames) * channels))
            floats.get(pending, pendingSize * channels, frames * channels)
            output.position(output.limit())
            pendingSize += frames
        }

        override fun close() = sonic.reset()

        companion object {
            const val BLOCK = 2048
        }
    }
}

/**
 * The app's audio engine on a project's files: the mix of [plan] at [rate]
 * with [channels], each piece read from the processed sound of its chain
 * ([processed]) when that has been made, else from its recording ([files]),
 * runs at another speed with their pitch kept.
 */
fun audioEngine(
    plan: TrackMixPlan, files: Map<String, File>, processed: (TrackMixPiece) -> ProcessedSound?, rate: Int = MIX_RATE, channels: Int = 2,
    live: LiveAudioGain? = null, failures: MutableList<String>? = null,
): TrackMixEngine = TrackMixEngine(
    plan, rate, channels,
    open = { piece ->
        val sound = (if (piece.chain.isEmpty()) null else processed(piece)) ?: files[piece.sourceID]?.let { ProcessedSound(it) }
        sound?.let { DecodedSource(it.file, rate, channels, piece.runs.first().sourceStart, it.offset).also { source -> if (source.failed) failures?.add(piece.sourceID) } }
    },
    stretch = SonicRetime(), live = live,
)

/** The sound a piece plays: [file], whose frame 0 is second [offset] of the piece's source. */
class ProcessedSound(val file: File, val offset: Double = 0.0)

/** The rate the app mixes at: the rate of every chain's processed sound and of the export. */
const val MIX_RATE = 48_000

/** What [writeMix] gives: the loudest sample of the mix, 1 being full scale, and whether a recording could not be decoded. */
class MixResult(val peak: Float, val complete: Boolean)

/**
 * Writes the whole mix of [engine] to [target] as a 16-bit WAV, samples past
 * full scale stopped there, and tells [progress] how far it is, 0 to 1.
 * [checkpoint] runs between blocks and may throw to stop. The peak is
 * measured before the samples are stopped, in the frames of [measured] only
 * when it is given ([ExportAudioLevel.frameSpans]), else in all of them.
 */
fun writeMix(
    engine: TrackMixEngine, target: File, failures: List<String>, checkpoint: () -> Unit = {}, progress: (Double) -> Unit = {}, fadeEnd: Boolean = false,
    measured: List<LongRange>? = null,
): MixResult {
    val channels = engine.channels
    val block = 4096
    val mix = FloatArray(block * channels)
    val bytes = ByteBuffer.allocate(block * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
    var peak = 0f
    // A mix cut short of its sound ends with the fade of a cut, not a click.
    val fading = if (fadeEnd) maxOf(1L, Math.round(engine.sampleRate * AudioLevels.CUT_FADE)) else 0L
    engine.seek(0)
    BufferedOutputStream(FileOutputStream(target), 1 shl 16).use { output ->
        output.write(ByteArray(44))
        while (true) {
            checkpoint()
            val from = engine.position
            val done = engine.render(mix, block)
            if (done == 0) break
            for (frame in 0 until done) {
                val left = engine.frames - (from + frame)
                if (left <= fading) for (c in 0 until channels) mix[frame * channels + c] *= ((left - 1).toFloat() / fading).coerceAtLeast(0f)
            }
            bytes.clear()
            if (measured != null) peak = ExportAudioLevel.peak(mix, from, done, channels, measured, peak)
            for (index in 0 until done * channels) {
                val value = mix[index]
                if (measured == null) peak = maxOf(peak, abs(value))
                bytes.putShort((value * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
            }
            output.write(bytes.array(), 0, done * channels * 2)
            progress(engine.position.toDouble() / maxOf(1L, engine.frames))
        }
    }
    writeWavHeader(target, engine.sampleRate, channels, engine.frames)
    return MixResult(peak, failures.isEmpty())
}

/** Fills in the header of a 16-bit WAV of [frames] frames written after 44 bytes left for it. */
fun writeWavHeader(file: File, rate: Int, channels: Int, frames: Long) {
    val data = frames * channels * 2
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray()).putInt((36 + data).toInt()).put("WAVE".toByteArray())
    header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
    header.putInt(rate).putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
    header.put("data".toByteArray()).putInt(data.toInt())
    RandomAccessFile(file, "rw").use { it.seek(0); it.write(header.array()) }
}

/** The frames of [engine]'s mix in the seconds [from] to [until], for the recognizer and the measurements. */
fun TrackMixEngine.frames(from: Double, until: Double): LongRange =
    TrackMixer.firstFrame(app.takes.core.EditTime.of(from), sampleRate).coerceIn(0, frames) until
        TrackMixer.firstFrame(app.takes.core.EditTime.of(until), sampleRate).coerceIn(0, frames)

/**
 * The sound of a movie as the export mixes it: the mix [plan] of its timeline, read from the recordings in
 * [files] or the processed sound of their chains ([Denoiser]).
 */
class MovieSound(val plan: TrackMixPlan, private val files: Map<String, File>) {
    /**
     * How long the movie is, its picture lasting [picture] seconds: as long as its longest item, as in the
     * original, when [withSound]; else, for a project not yet shown that length ([app.takes.core.MovieLength]),
     * the picture's. A mix within a millisecond of the picture is the picture's length, which its frames decide.
     */
    fun duration(picture: Double, withSound: Boolean = true): Double =
        plan.duration.seconds.takeIf { withSound && it > picture + app.takes.core.MovieLength.MARGIN } ?: picture

    /**
     * Writes the mix of the movie, [duration] seconds of it, to [target] ([writeMix]), its peak measured in the
     * stretches [measured] only (YouRec/AudioGain.swift:213-265).
     */
    fun write(duration: Double, target: File, measured: List<app.takes.core.EditInterval>, checkpoint: () -> Unit, progress: (Double) -> Unit): MixResult {
        val failures = ArrayList<String>()
        val whole = plan.copy(duration = app.takes.core.EditTime.of(duration))
        val cut = whole.duration < plan.duration
        return audioEngine(whole, files, Denoiser.input(files), failures = failures).use { engine ->
            writeMix(engine, target, failures, checkpoint, progress, fadeEnd = cut, measured = ExportAudioLevel.frameSpans(measured, duration, engine.sampleRate))
        }
    }

    /**
     * Where the export measures how loud the mix of [project] gets: where its edit can raise a level, the sound of
     * the effects' background movies counted among the sounds that overlap (YouRec/AudioGain.swift:201-208).
     */
    fun checkRanges(project: app.takes.core.Project): List<app.takes.core.EditInterval> = ExportAudioLevel.checkRanges(
        project.edit.toTimeline(), project.sources,
        plan.pieces.filter { app.takes.core.EffectBackgroundSound.file(it.sourceID) != null }.map { it.interval },
    )
}
