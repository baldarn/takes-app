package app.takes

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import app.takes.core.AudioGainMix
import app.takes.core.AudioGainSelection
import app.takes.core.AudioHeadroom
import app.takes.core.AudioLevels
import app.takes.core.EditInterval
import app.takes.core.EditState
import app.takes.core.SoundEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * How far the sound a level is being chosen for can go before the movie's
 * mix clips, measured as the original measures it (AudioGain.swift:190-246):
 * the mix of the chosen sound alone at 0 dB and the mix of everything else,
 * sample by sample at 48 kHz over the chosen stretch of the movie, each
 * through the same envelopes the export applies ([SoundEnvelope],
 * [AudioGainMix]).
 *
 * The original reads both mixes from its player's composition. Here nothing
 * renders a mix outside an export, so the recordings are decoded and mixed
 * for the measurement alone: a clip that plays faster or slower is read at
 * its rate without its pitch being kept, and sound at another sample rate is
 * resampled linearly, which is close to the export for levels and not a copy
 * of its samples.
 */
object Headroom {
    private val working = Dispatchers.IO.limitedParallelism(1)

    /** One run of sound and the file and second of it that plays at the run's start. */
    private class Voice(val index: Int, val file: File, val source: Double, val rate: Double)

    /**
     * The headroom of the clips and sounds [chosen] (by id) in [state] over
     * [range] seconds of the movie. An empty measurement ([AudioHeadroom.silent])
     * when the chosen sound is silent or muted there; null when a recording
     * cannot be decoded. [files] gives each source's file; [clean] the cleaned
     * sound of a clip that has noise reduction, when it has been made.
     */
    suspend fun measure(
        state: EditState, files: Map<String, File>, chosen: Set<String>, range: ClosedFloatingPointRange<Double>,
        clean: (File, Double) -> File? = Denoiser::ready,
    ): AudioHeadroom? = withContext(working) {
        val frames = AudioHeadroom.frames(range, state.duration) ?: return@withContext AudioHeadroom()
        val segments = SoundEnvelope.segments(state)
        val selection = AudioGainSelection(chosen, EditInterval.of(range.start, range.endInclusive))
        val selected = AudioGainMix.replacing(segments, selection, AudioGainMix.Mode.Selected)
        val remainder = AudioGainMix.replacing(segments, selection, AudioGainMix.Mode.Remainder)
        val clips = state.clips.associateBy { it.id }
        val items = state.audio.orEmpty().associateBy { it.id }
        val rate = AudioLevels.MEASURE_RATE.toDouble()
        val from = frames.first / rate
        val until = (frames.last + 1) / rate
        val voices = segments.mapIndexedNotNull { index, segment ->
            if (segment.end <= from || segment.start >= until) return@mapIndexedNotNull null
            val clip = clips[segment.owner]
            val item = items[segment.owner]
            when {
                clip != null -> files[clip.sourceID]?.let { file ->
                    Voice(index, clip.audio?.denoise?.takeIf { it > 0 }?.let { clean(file, it) } ?: file, clip.start, clip.rate)
                }
                item != null -> files[item.sourceID]?.let { Voice(index, it, item.sourceStart, 1.0) }
                else -> null
            }
        }
        val readers = arrayOfNulls<PcmReader>(voices.size)
        var result = AudioHeadroom()
        val block = 4096
        val own = FloatArray(block * 2)
        val rest = FloatArray(block * 2)
        try {
            var frame = frames.first
            while (frame <= frames.last) {
                coroutineContext.ensureActive()
                val count = minOf(block.toLong(), frames.last - frame + 1).toInt()
                own.fill(0f, 0, count * 2); rest.fill(0f, 0, count * 2)
                voices.forEachIndexed { number, voice ->
                    val segment = segments[voice.index]
                    val begins = frame / rate
                    if (segment.end <= begins || segment.start >= (frame + count) / rate) return@forEachIndexed
                    val a = selected[voice.index]
                    val b = remainder[voice.index]
                    for (offset in 0 until count) {
                        val time = (frame + offset) / rate
                        if (time < segment.start || time >= segment.end) continue
                        val reader = readers[number] ?: PcmReader.open(voice.file, voice.source + (time - segment.start) * voice.rate).also { readers[number] = it }
                            ?: return@withContext null
                        if (!reader.seek(voice.source + (time - segment.start) * voice.rate)) continue // past the end of the recording: silence
                        val gain = a.gain(time).toFloat()
                        val other = b.gain(time).toFloat()
                        val left = reader.sample(0)
                        val right = reader.sample(1)
                        own[offset * 2] += left * gain; own[offset * 2 + 1] += right * gain
                        rest[offset * 2] += left * other; rest[offset * 2 + 1] += right * other
                    }
                    if (segment.end <= (frame + count) / rate) { readers[number]?.close(); readers[number] = PcmReader.ENDED }
                }
                result = result.including(own, rest, count * 2)
                frame += count
            }
        } catch (problem: java.io.IOException) {
            return@withContext null
        } catch (problem: IllegalStateException) {
            return@withContext null // a decoder gave up
        } finally {
            readers.forEach { it?.close() }
        }
        result
    }
}

/**
 * A file's sound read forwards from a chosen second, as normalized samples
 * at any position between two of its own, for at most two channels. A
 * recording with one channel gives it for both.
 */
internal open class PcmReader private constructor(private val extractor: MediaExtractor?, private val codec: MediaCodec?) {
    private val info = MediaCodec.BufferInfo()
    private var sampleRate = 0
    private var channels = 0
    private var block = ShortArray(0)
    private var frames = 0          // frames in the block
    private var first = Long.MIN_VALUE // source frame of the block's first one
    private var before = ShortArray(2) // the frame before the block
    private var inputDone = false
    private var ended = extractor == null
    private var idle = 0
    private var position = 0.0      // the source frame asked for, between two of the block

    /** Moves to [seconds] of the recording, never backwards. False past its end. */
    fun seek(seconds: Double): Boolean {
        if (ended && frames == 0) return false
        while (true) {
            if (sampleRate > 0) {
                position = seconds * sampleRate
                // The frame after the position must be in the block, or the block is the last.
                if (position < first + frames - 1 || ended) return position < first + frames
            }
            if (!next()) { ended = true; if (sampleRate == 0) return false }
        }
    }

    /** The sample of [channel] at the position of the last [seek], between the two frames around it. */
    fun sample(channel: Int): Float {
        val index = Math.floor(position).toLong()
        val c = if (channel < channels) channel else 0
        fun at(frame: Long): Short = when {
            frame < first -> before[c]
            frame >= first + frames -> if (frames > 0) block[(frames - 1) * channels + c] else before[c]
            else -> block[(frame - first).toInt() * channels + c]
        }
        val a = at(index).toFloat()
        val b = at(index + 1).toFloat()
        return (a + (b - a) * (position - index).toFloat()) / 32768f
    }

    /** Decodes the next block. False at the end of the recording. */
    private fun next(): Boolean {
        val decoder = codec ?: return false
        val source = extractor ?: return false
        while (true) {
            if (!inputDone) {
                val input = decoder.dequeueInputBuffer(10_000)
                if (input >= 0) {
                    val size = source.readSampleData(decoder.getInputBuffer(input)!!, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(input, 0, size, source.sampleTime, 0)
                        source.advance()
                    }
                }
            }
            val output = decoder.dequeueOutputBuffer(info, 10_000)
            if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val pcm = decoder.outputFormat
                if (pcm.containsKey(MediaFormat.KEY_PCM_ENCODING) && pcm.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT) {
                    throw java.io.IOException("Sound is not decoded to 16 bits")
                }
                if (sampleRate == 0) {
                    sampleRate = pcm.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = pcm.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    before = ShortArray(channels)
                }
            } else if (output >= 0) {
                idle = 0
                val last = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                var filled = false
                if (info.size > 0 && sampleRate > 0) {
                    val buffer = decoder.getOutputBuffer(output)!!
                    buffer.position(info.offset).limit(info.offset + info.size)
                    val samples = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val count = samples.remaining() / channels
                    if (frames > 0) for (c in 0 until channels) before[c] = block[(frames - 1) * channels + c]
                    if (block.size < count * channels) block = ShortArray(count * channels)
                    samples.get(block, 0, count * channels)
                    // The first block says where the recording really begins; the rest follow it sample by sample.
                    first = if (first == Long.MIN_VALUE) Math.round(info.presentationTimeUs / 1e6 * sampleRate) else first + frames
                    frames = count
                    filled = true
                }
                decoder.releaseOutputBuffer(output, false)
                if (filled) return true
                if (last) return false
            } else if (inputDone && ++idle > 500) {
                return false // the decoder never signalled the end
            }
        }
    }

    open fun close() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor?.release() }
    }

    companion object {
        /** A reader with nothing left to give, standing for one that was closed. */
        val ENDED: PcmReader = object : PcmReader(null, null) { override fun close() {} }

        /** Opens [file]'s first audio track a little before [seconds]. Null when it has no sound that can be decoded. */
        fun open(file: File, seconds: Double): PcmReader? {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.path)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: run { extractor.release(); return ENDED } // no sound at all is silence, not a failure
                val format = extractor.getTrackFormat(track)
                extractor.selectTrack(track)
                // A little early: the first frames a decoder gives after a jump are not yet at their level.
                extractor.seekTo(maxOf(0L, ((seconds - 0.1) * 1e6).toLong()), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                decoder.configure(format, null, null, 0)
                decoder.start()
                return PcmReader(extractor, decoder)
            } catch (problem: Exception) {
                extractor.release()
                return null
            }
        }
    }
}
