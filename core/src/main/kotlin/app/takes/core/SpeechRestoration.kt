package app.takes.core

import kotlin.math.abs
import kotlin.math.min

/**
 * The network of Speech detail restoration, LavaSR v2: [LavaDsp.features] of a window in, its spectrum out,
 * `[1025][375]` real and imaginary parts (YouRec/StudioVoiceProcessor.swift:117-124). The app runs it with
 * ONNX Runtime; the tests give a stand-in.
 */
fun interface SpeechDetailNetwork {
    fun spectrum(mel: FloatArray, real: FloatArray, imaginary: FloatArray)
}

/**
 * The networks of Studio restoration, VoiceFixer's analysis and vocoder together: [VoiceFixerDsp.features] of a
 * window in, [VoiceFixerDsp.OUTPUT] samples at 44.1 kHz out, the vocoder's padding included
 * (YouRec/StudioRestorationProcessor.swift:290-300).
 */
fun interface StudioRestorationNetwork {
    fun restore(mel: FloatArray, audio: FloatArray)
}

/**
 * Where the windows a restoration stage has made of one channel are kept, so that a run stopped half way
 * starts again where it stopped, as the original keeps them in its resume folder
 * (YouRec/StudioVoiceProcessor.swift:60-82, YouRec/StudioRestorationProcessor.swift:249-255, 272-275).
 * [position] is the window's first sample in the stage's stream; a window is [count] samples.
 */
interface RestorationWindowStore {
    fun load(position: Long, count: Int): FloatArray?
    fun save(position: Long, samples: FloatArray, count: Int)
}

/**
 * The last windows the channels of a stage made, for the others of the same recording: a channel that hears
 * exactly the same window uses the result instead of running the network again, as the original does for
 * the dual mono of the built-in microphone (YouRec/StudioVoiceProcessor.swift:89-95). Real stereo goes on
 * through separate predictions. One per stage per recording, shared by its channels.
 */
class RestorationTwin {
    private class Kept(val position: Long, val window: FloatArray, val result: FloatArray)

    // The last few: a channel finishing makes its final windows one after another before the next channel does.
    private val kept = ArrayDeque<Kept>()

    internal fun find(position: Long, window: FloatArray, count: Int): FloatArray? =
        kept.firstOrNull { it.position == position && it.result.size == count && it.window.contentEquals(window) }?.result

    internal fun keep(position: Long, window: FloatArray, result: FloatArray, count: Int) {
        kept.removeAll { it.position == position }
        kept.addLast(Kept(position, window.copyOf(), result.copyOf(count)))
        while (kept.size > KEPT) kept.removeFirst()
    }

    private companion object {
        const val KEPT = 4
    }
}

/**
 * Cuts one channel, as it arrives, into the overlapping windows a restoration network hears: window k holds
 * [context] samples from [stride] × k − [margin], and gives [stride] samples from [stride] × k, plus [extra]
 * beyond them for a cross-fade. Samples before the first and after the last are the recording reflected at
 * its ends, bouncing as often as a very short one needs (YouRec/StudioVoiceProcessor.swift:96-107,
 * YouRec/StudioRestorationProcessor.swift:194-204). A window is made as soon as its samples have all
 * arrived; the last ones, which reach past the end, when [finish] says where the end is.
 */
class ReflectedWindows(val context: Int, val margin: Int, val stride: Int, val extra: Int = 0) {
    init { require(context > 0 && margin >= 0 && stride > 0 && extra >= 0 && 2 * margin + stride + extra <= context) }

    /**
     * Receives a window: its [context] samples (the array is reused), the stream position its output begins at,
     * how many samples of output it gives, and how many it has with the cross-fade's [extra], at the end fewer.
     */
    fun interface Receiver {
        fun window(samples: FloatArray, position: Long, count: Int, valid: Int)
    }

    private val window = FloatArray(context)
    // The samples a window still to come may read; buffer[0] is sample [start].
    private var buffer = FloatArray(context + stride + 8192)
    private var start = 0L
    private var size = 0
    private var written = 0L
    private var next = 0L
    private var finished = false

    /** Takes [count] samples of [samples] from [offset], and makes every window they complete. */
    fun write(samples: FloatArray, offset: Int, count: Int, receiver: Receiver) {
        check(!finished) { "The windows are finished" }
        if (size + count > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + count))
        System.arraycopy(samples, offset, buffer, size, count)
        size += count
        written += count
        while (written >= next - margin + context) {
            fill(next, null)
            receiver.window(window, next, stride, stride + extra)
            next += stride
            trim()
        }
    }

    /** Makes the windows left, now that the length is known. */
    fun finish(receiver: Receiver) {
        if (finished) return
        finished = true
        while (next < written) {
            fill(next, written)
            receiver.window(window, next, min(stride.toLong(), written - next).toInt(), min((stride + extra).toLong(), written - next).toInt())
            next += stride
        }
    }

    private fun fill(position: Long, length: Long?) {
        for (i in 0 until context) {
            var at = position - margin + i
            at = if (length != null) reflect(at, length) else if (at < 0) -at else at
            window[i] = buffer[(at - start).toInt()]
        }
    }

    // Keeps the samples from a context before the next window: its reflections at either end stay within them.
    private fun trim() {
        val keep = maxOf(0L, next - context).coerceAtMost(start + size)
        val drop = (keep - start).toInt()
        if (drop <= 0) return
        System.arraycopy(buffer, drop, buffer, 0, size - drop)
        size -= drop
        start = keep
    }

    companion object {
        /** [index] reflected into a recording of [length] samples, as many times as it takes. */
        fun reflect(index: Long, length: Long): Long {
            require(length > 0)
            if (length == 1L) return 0
            val period = 2 * (length - 1)
            val folded = (index % period + period) % period
            return if (folded < length) folded else period - folded
        }

        /** The recording's samples the window at [position] holds (YouRec/StudioRestorationProcessor.swift:196-204). */
        fun sourceIndices(position: Long, length: Long, context: Int, margin: Int): LongArray =
            LongArray(context) { reflect(position - margin + it, length) }

        /** Whether a window is silence, which the networks are not asked about (YouRec/StudioVoiceProcessor.swift:108). */
        internal fun silent(samples: FloatArray): Boolean = samples.all { abs(it) < 1e-8f }
    }
}

/**
 * Speech detail restoration (LavaSR v2) of one channel at 48 kHz as it arrives, as the original's
 * StudioVoiceProcessor (YouRec/StudioVoiceProcessor.swift): four-second windows with 32 STFT hops of context
 * either side, each cut to its centre; the network's sound only above 4–6 kHz ([LavaDsp.merge]); [amount] of
 * it mixed into the recording. It gives back exactly as many samples as it is given, in order, about
 * 3.7 s late, the last ones from [finish]. Same calls as rnnoise's ChannelDenoiser.
 */
class SpeechDetailChannel(
    private val network: SpeechDetailNetwork,
    private val amount: Float,
    private val twin: RestorationTwin? = null,
    private val store: RestorationWindowStore? = null,
) {
    init { require(amount.isFinite() && amount in 0f..1f) }

    private val windows = ReflectedWindows(CONTEXT, MARGIN, STRIDE)
    private val mel = FloatArray(LavaDsp.MELS * LavaDsp.FRAMES)
    private val real = FloatArray(LavaDsp.BINS * LavaDsp.FRAMES)
    private val imaginary = FloatArray(LavaDsp.BINS * LavaDsp.FRAMES)
    private val reconstructed = FloatArray(CONTEXT)
    private val merged = FloatArray(CONTEXT)
    private val result = FloatArray(STRIDE)
    private var output: (FloatArray, Int) -> Unit = { _, _ -> }

    private val receiver = ReflectedWindows.Receiver { window, position, count, _ -> output(render(window, position, count), count) }

    /** Takes [count] samples of [samples] from [offset]; [output] receives restored blocks and how many samples of each are valid. */
    fun write(samples: FloatArray, offset: Int, count: Int, output: (FloatArray, Int) -> Unit) {
        this.output = output
        windows.write(samples, offset, count, receiver)
    }

    /** Gives the rest. Nothing may be written afterwards. */
    fun finish(output: (FloatArray, Int) -> Unit) {
        this.output = output
        windows.finish(receiver)
    }

    private fun render(window: FloatArray, position: Long, count: Int): FloatArray {
        store?.load(position, count)?.let { return it }
        val found = twin?.find(position, window, count)
        when {
            found != null -> System.arraycopy(found, 0, result, 0, count)
            ReflectedWindows.silent(window) -> result.fill(0f, 0, count)
            else -> {
                LavaDsp.features(window, mel)
                network.spectrum(mel, real, imaginary)
                LavaDsp.reconstruct(real, imaginary, reconstructed)
                LavaDsp.merge(window, reconstructed, merged)
                for (i in 0 until count) {
                    val dry = window[MARGIN + i]
                    val value = dry + amount * (merged[MARGIN + i] - dry)
                    if (!value.isFinite()) throw AudioChainException(AudioChainException.Reason.InvalidFormat)
                    result[i] = value
                }
            }
        }
        if (found == null) twin?.keep(position, window, result, count)
        store?.save(position, result, count)
        return result
    }

    companion object {
        /** YouRec/StudioVoiceProcessor.swift:7-9: four seconds, 32 STFT hops of context each side. */
        const val CONTEXT = LavaDsp.LENGTH
        const val MARGIN = 16_384
        const val STRIDE = CONTEXT - 2 * MARGIN
    }
}

/**
 * Studio restoration (VoiceFixer) of one channel at 48 kHz as it arrives, as the original's
 * StudioRestorationProcessor (YouRec/StudioRestorationProcessor.swift): to 44.1 kHz ([Resampler]); windows of
 * [CONTEXT] samples, 0.64 s of context before each 1.2 s hop, whose outputs overlap by [OVERLAP] samples
 * (70 ms) and are cross-faded; back to 48 kHz; [amount] of the restored voice mixed into the recording,
 * a missing last sample of it counting as silence (YouRec/StudioRestorationProcessor.swift:121-133). It gives
 * back exactly as many samples as it is given, in order, about two seconds late, the last ones from [finish].
 * [bank] is VoiceFixer's mel filterbank ([VoiceFixerDsp.bank]). The [store] keeps the network's windows
 * before the cross-fade, so they serve any amount.
 */
class StudioRestorationChannel(
    private val network: StudioRestorationNetwork,
    private val bank: FloatArray,
    private val amount: Float,
    private val twin: RestorationTwin? = null,
    private val store: RestorationWindowStore? = null,
) {
    init { require(amount.isFinite() && amount in 0f..1f) }

    private val down = Resampler(SOURCE_RATE, VoiceFixerDsp.RATE)
    private val up = Resampler(VoiceFixerDsp.RATE, SOURCE_RATE)
    private val windows = ReflectedWindows(CONTEXT, MARGIN, STRIDE, OVERLAP)
    private val mel = FloatArray(VoiceFixerDsp.FRAMES * VoiceFixerDsp.MELS)
    private val restored = FloatArray(VoiceFixerDsp.OUTPUT)
    private val raw = FloatArray(STRIDE + OVERLAP)
    private val tail = FloatArray(OVERLAP)
    private val result = FloatArray(STRIDE)
    private val mixed = FloatArray(4096)

    // The recording not yet mixed with its restoration; dry[0] is the next sample to give back.
    private var dry = FloatArray(1 shl 18)
    private var held = 0
    private var output: (FloatArray, Int) -> Unit = { _, _ -> }

    private val toWindows: (FloatArray, Int) -> Unit = { block, count -> windows.write(block, 0, count, receiver) }
    private val toMix: (FloatArray, Int) -> Unit = { block, count -> mix(block, count) }
    private val receiver = ReflectedWindows.Receiver { window, position, count, valid -> window(window, position, count, valid) }

    /** Takes [count] samples of [samples] from [offset]; [output] receives restored blocks and how many samples of each are valid. */
    fun write(samples: FloatArray, offset: Int, count: Int, output: (FloatArray, Int) -> Unit) {
        this.output = output
        if (held + count > dry.size) dry = dry.copyOf(maxOf(dry.size * 2, held + count))
        System.arraycopy(samples, offset, dry, held, count)
        held += count
        down.write(samples, offset, count, toWindows)
    }

    /** Gives the rest. Nothing may be written afterwards. */
    fun finish(output: (FloatArray, Int) -> Unit) {
        this.output = output
        down.finish(toWindows)
        windows.finish(receiver)
        up.finish(toMix)
        // The restoration may end a sample short of the recording: the rest is mixed with silence.
        while (held > 0) {
            val count = min(held, mixed.size)
            for (i in 0 until count) mixed[i] = dry[i] * (1 - amount)
            consume(count)
            output(mixed, count)
        }
    }

    private fun window(window: FloatArray, position: Long, count: Int, valid: Int) {
        val made = store?.load(position, valid) ?: run {
            val found = twin?.find(position, window, valid)
            when {
                found != null -> System.arraycopy(found, 0, raw, 0, valid)
                ReflectedWindows.silent(window) -> raw.fill(0f, 0, valid)
                else -> {
                    VoiceFixerDsp.features(window, bank, mel)
                    network.restore(mel, restored)
                    // Upstream centres the vocoder's padding around the input.
                    val trim = (VoiceFixerDsp.OUTPUT - CONTEXT) / 2
                    for (i in 0 until valid) {
                        val value = restored[MARGIN + trim + i]
                        if (!value.isFinite()) throw AudioChainException(AudioChainException.Reason.InvalidFormat)
                        raw[i] = value
                    }
                }
            }
            if (found == null) twin?.keep(position, window, raw, valid)
            store?.save(position, raw, valid)
            raw
        }
        // YouRec/StudioRestorationProcessor.swift:312-318: the first samples fade in over the last window's tail.
        for (i in 0 until count) {
            val weight = if (position > 0 && i < OVERLAP) (i + 1).toFloat() / (OVERLAP + 1) else 1f
            result[i] = weight * made[i] + (1 - weight) * (if (i < OVERLAP) tail[i] else 0f)
        }
        for (i in 0 until OVERLAP) tail[i] = if (count + i < valid) made[count + i] else 0f
        up.write(result, 0, count, toMix)
    }

    private fun mix(wet: FloatArray, count: Int) {
        // Restored samples past the end of the recording belong to the resampling's last fraction.
        val usable = min(count, held)
        var done = 0
        while (done < usable) {
            val n = min(usable - done, mixed.size)
            for (i in 0 until n) {
                val original = dry[i]
                mixed[i] = original + amount * (wet[done + i] - original)
            }
            consume(n)
            output(mixed, n)
            done += n
        }
    }

    private fun consume(count: Int) {
        System.arraycopy(dry, count, dry, 0, held - count)
        held -= count
    }

    companion object {
        const val SOURCE_RATE = 48_000

        /** YouRec/StudioRestorationProcessor.swift:190-193, in samples at 44.1 kHz. */
        const val CONTEXT = VoiceFixerDsp.CONTEXT
        const val MARGIN = 64 * VoiceFixerDsp.HOP
        const val STRIDE = 120 * VoiceFixerDsp.HOP
        const val OVERLAP = CONTEXT - 2 * MARGIN - STRIDE
    }
}
