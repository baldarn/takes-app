@file:OptIn(UnstableApi::class)

package app.takes.sound

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import app.takes.core.AudioEnvelope
import app.takes.core.AudioEnvelopeSegment
import app.takes.core.AudioLevels
import app.takes.core.RenderPlan
import app.takes.core.SoundEnvelope
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.roundToInt

// What the export's sound was made with before the audio engine, kept for the sound gate ([legacySound]).

/** A sound of a render plan's sound tracks as an export item, cut to the part of its source the movie plays. */
fun clippedItem(sound: RenderPlan.Entry.Sound, file: File): MediaItem = MediaItem.Builder()
    .setUri(Uri.fromFile(file))
    .setMediaId(sound.item.id)
    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionUs(sound.sourceStartUs).setEndPositionUs(sound.sourceEndUs).build())
    .build()

/**
 * Applies a clip's or a sound's level in an export, sample by sample, as the
 * original's tap does (CutAudioEnvelope.swift:160-183): [segment] is its run
 * of sound on the movie's clock ([SoundEnvelope]), with its own fades, its
 * share in a transition and its volume, and [begins] the movie second of the
 * first sample handed over. A null [segment] is silence: a muted clip.
 *
 * The few milliseconds of fade at a cut ([AudioLevels.CUT_FADE]) are on the
 * first and the last samples of sound that really arrive, on every run.
 * Media3 cuts sound on the frames of its codec and fills what is missing of
 * an item with silence, so a run begins and ends some milliseconds off its
 * place on the clock; and it decodes every item afresh, so sound never
 * carries on across a cut as it does in the original. A fade timed by the
 * clock would miss the end of the run, and a cut left without one, where the
 * original has none, would click. The newest samples are therefore held back
 * until it is known whether silence or the end follows them.
 *
 * As in the original, 16-bit samples stop at full scale and floating point
 * ones are left as they come out, so nothing below full scale is changed.
 */
class SoundProcessor(segment: AudioEnvelopeSegment?, private val begins: Double) : BaseAudioProcessor() {
    // The fades of the cuts are placed here: the rest of the run's level follows the clock, past its end as well.
    private val envelope = AudioEnvelope(listOfNotNull(
        segment?.copy(fadeIn = 0.0, fadeOut = 0.0, end = Double.POSITIVE_INFINITY, audibleEnd = Double.POSITIVE_INFINITY),
    ))
    private var frames = 0L
    private var gains = DoubleArray(0)
    /** Frames since the first one that was not silence; -1 before it. */
    private var sounding = -1L
    /** How many frames of silence the samples end with so far. */
    private var quiet = 0
    /**
     * The newest samples, already at their level, kept until it is known what
     * follows them: twice the length of a fade, so that when that much
     * silence has come the sound before it is still here to be faded.
     */
    private var held = ByteArray(0)
    private var holding = 0
    private var scratch = ByteArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    /** How many frames the fade of a cut is long. */
    private fun fadeFrames(format: AudioProcessor.AudioFormat) = maxOf(1, (format.sampleRate * AudioLevels.CUT_FADE).roundToInt())

    /** Whether the frame at byte [position] of [buffer] is digital silence. */
    private fun silent(buffer: ByteBuffer, position: Int, format: AudioProcessor.AudioFormat): Boolean {
        val width = format.bytesPerFrame / format.channelCount
        for (channel in 0 until format.channelCount) {
            val at = position + channel * width
            if (if (format.encoding == C.ENCODING_PCM_16BIT) buffer.getShort(at).toInt() != 0 else buffer.getFloat(at) != 0f) return false
        }
        return true
    }

    /** The frames of [samples] from [from] up to [until] go down to silence, which is what follows them. */
    private fun fadeOut(samples: ByteBuffer, from: Int, until: Int, format: AudioProcessor.AudioFormat) {
        val whole = format.encoding == C.ENCODING_PCM_16BIT
        val length = fadeFrames(format)
        val width = format.bytesPerFrame / format.channelCount
        for (frame in maxOf(0, from) until until) {
            val gain = (until - frame).toDouble() / length
            for (channel in 0 until format.channelCount) {
                val at = frame * format.bytesPerFrame + channel * width
                if (whole) samples.putShort(at, (samples.getShort(at) * gain).roundToInt().toShort()) else samples.putFloat(at, (samples.getFloat(at) * gain).toFloat())
            }
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val format = inputAudioFormat
        val whole = format.encoding == C.ENCODING_PCM_16BIT
        val count = inputBuffer.remaining() / format.bytesPerFrame
        val fade = fadeFrames(format)
        if (gains.size < count) gains = DoubleArray(count)
        envelope.fill(begins + frames.toDouble() / format.sampleRate, count, format.sampleRate.toDouble(), gains)
        // What was held, then the new samples at their level.
        val before = holding / format.bytesPerFrame
        val size = holding + count * format.bytesPerFrame
        if (scratch.size < size) scratch = ByteArray(size)
        val all = ByteBuffer.wrap(scratch, 0, size).order(java.nio.ByteOrder.nativeOrder())
        all.put(held, 0, holding)
        for (frame in 0 until count) {
            var gain = gains[frame]
            // The sound comes up from its first sample that is one, not from where the clock puts it.
            if (sounding < 0 && !silent(inputBuffer, inputBuffer.position(), format)) sounding = 0
            if (sounding in 0 until fade) gain *= sounding.toDouble() / fade
            if (sounding >= 0) sounding++
            val at = all.position()
            repeat(format.channelCount) {
                if (whole) all.putShort((inputBuffer.short * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                else all.putFloat((inputBuffer.float * gain).toFloat())
            }
            // Silence as long as a fade, where there was sound, is where the sound ends: of the run, filled up to its
            // length, or of the recording. What came just before it is still held, and fades.
            quiet = if (silent(all, at, format)) quiet + 1 else 0
            if (quiet == fade) fadeOut(all, before + frame + 1 - 2 * fade, before + frame + 1 - fade, format)
        }
        frames += count
        // A buffer is whole frames; nothing of it is left behind.
        inputBuffer.position(inputBuffer.limit())
        val keep = minOf(size, 2 * fade * format.bytesPerFrame)
        val output = replaceOutputBuffer(size - keep)
        output.put(scratch, 0, size - keep)
        output.flip()
        if (held.size < keep) held = ByteArray(2 * fade * format.bytesPerFrame)
        System.arraycopy(scratch, size - keep, held, 0, keep)
        holding = keep
    }

    /**
     * Once the run is over and everything before them has been taken, its
     * last samples go down to silence. (Not when the end is announced: what
     * was handed out last may still be waiting to be read.)
     */
    override fun getOutput(): ByteBuffer {
        if (super.isEnded() && holding > 0) {
            val format = inputAudioFormat
            val fade = fadeFrames(format)
            // The last of the sound: before the silence it ends with, when that is shorter than a fade and so not yet faded.
            val last = holding / format.bytesPerFrame - if (quiet < fade) quiet else 0
            if (quiet < fade) fadeOut(ByteBuffer.wrap(held, 0, holding).order(java.nio.ByteOrder.nativeOrder()), last - fade, last, format)
            val output = replaceOutputBuffer(holding)
            output.put(held, 0, holding)
            output.flip()
            holding = 0
        }
        return super.getOutput()
    }

    override fun isEnded(): Boolean = super.isEnded() && holding == 0

    override fun onFlush() { frames = 0; holding = 0; sounding = -1; quiet = 0; envelope.prepare() }

    override fun onReset() { frames = 0; holding = 0; sounding = -1; quiet = 0 }
}
