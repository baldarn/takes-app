package app.takes.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * The signal processing around LavaSR v2's network, Speech detail restoration
 * ([AudioEffectKind.StudioVoice]), a port of the original's native DSP
 * (ThirdParty/LavaSR/LavaDSP.cpp) for one fixed window of [LENGTH] samples
 * at 48 kHz, four seconds:
 *
 * - [features]: the log mel spectrogram the network reads, 80 bands × [FRAMES]
 *   frames, from a periodic Hann STFT of [FFT_SIZE] points every [HOP] samples
 *   with reflected "same" padding, through the checkpoint's Slaney filterbank
 *   for 44.1 kHz from 0 to 8 kHz, which the published v2 checkpoint uses even
 *   for 48 kHz sound (ThirdParty/LavaSR/PROVENANCE.md).
 * - [reconstruct]: the waveform back from the network's spectrum by inverse
 *   STFT and overlap-add, divided by the summed squared window.
 * - [merge]: the original below 4 kHz and the restoration above 6 kHz, with a
 *   smooth complementary crossover between them and no delay.
 *
 * Arrays are laid out as the original's: mel `[80][375]`, spectra `[1025][375]`,
 * row by row.
 */
object LavaDsp {
    const val FFT_SIZE = 2048
    const val HOP = 512
    const val FRAMES = 375
    const val LENGTH = 192_000
    const val BINS = FFT_SIZE / 2 + 1
    const val MELS = 80
    private const val PADDING = (FFT_SIZE - HOP) / 2

    /** The size of the transform [merge] filters in: the window, padded with silence to a power of two. */
    const val MERGE_SIZE = 262_144

    private val window = FloatArray(FFT_SIZE) { (0.5 * (1 - cos(2 * PI * it / FFT_SIZE))).toFloat() }

    // As LavaDSP.cpp:36-41, in single precision.
    private fun hzToMel(hz: Float): Float = if (hz < 1000) hz * 3 / 200 else 15 + ln(hz / 1000) * 27 / ln(6.4f)
    private fun melToHz(mel: Float): Float = if (mel < 15) mel * 200 / 3 else 1000 * exp((mel - 15) * ln(6.4f) / 27)

    /** The filterbank, `[80][1025]`, and the first and last bin each band reaches, so the zeros are skipped. */
    private val bank = FloatArray(MELS * BINS)
    private val first = IntArray(MELS)
    private val last = IntArray(MELS)

    init {
        val edges = FloatArray(MELS + 2) { melToHz(hzToMel(8000f) * it / (MELS + 1)) }
        for (m in 0 until MELS) {
            first[m] = BINS; last[m] = -1
            for (f in 0 until BINS) {
                val hz = f.toFloat() * 44100 / FFT_SIZE
                val up = (hz - edges[m]) / (edges[m + 1] - edges[m])
                val down = (edges[m + 2] - hz) / (edges[m + 2] - edges[m + 1])
                val weight = max(0f, min(up, down)) * 2 / (edges[m + 2] - edges[m])
                bank[m * BINS + f] = weight
                if (weight != 0f) { first[m] = min(first[m], f); last[m] = f }
            }
        }
    }

    /** Reflects [index] into a window of [length] samples, once, as the STFT's padding does. */
    private fun reflect(index: Int, length: Int): Int = when {
        index < 0 -> -index
        index >= length -> 2 * length - 2 - index
        else -> index
    }

    /** The network's input for [audio], [LENGTH] samples: `mel`, `[80][375]`, natural log, floored at 1e-5. */
    fun features(audio: FloatArray, mel: FloatArray) {
        require(audio.size >= LENGTH && mel.size >= MELS * FRAMES)
        val fft = Fft.of(FFT_SIZE)
        val real = FloatArray(FFT_SIZE)
        val imaginary = FloatArray(FFT_SIZE)
        val magnitudes = FloatArray(BINS)
        for (frame in 0 until FRAMES) {
            for (i in 0 until FFT_SIZE) {
                real[i] = audio[reflect(frame * HOP + i - PADDING, LENGTH)] * window[i]
                imaginary[i] = 0f
            }
            fft.transform(real, imaginary)
            for (f in 0 until BINS) magnitudes[f] = hypot(real[f], imaginary[f])
            for (m in 0 until MELS) {
                var sum = 0f
                for (f in first[m]..last[m]) sum += bank[m * BINS + f] * magnitudes[f]
                mel[m * FRAMES + frame] = ln(max(1e-5f, sum))
            }
        }
    }

    /**
     * The waveform of the network's spectrum, [real] and [imaginary] `[1025][375]`, into [audio], [LENGTH]
     * samples: the imaginary parts of the DC and Nyquist bins are dropped, as an inverse real transform does.
     */
    fun reconstruct(real: FloatArray, imaginary: FloatArray, audio: FloatArray) {
        require(real.size >= BINS * FRAMES && imaginary.size >= BINS * FRAMES && audio.size >= LENGTH)
        val padded = (FRAMES - 1) * HOP + FFT_SIZE
        val output = FloatArray(padded)
        val envelope = FloatArray(padded)
        val fft = Fft.of(FFT_SIZE)
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        for (frame in 0 until FRAMES) {
            for (f in 0 until BINS) {
                re[f] = real[f * FRAMES + frame]
                im[f] = if (f == 0 || f == FFT_SIZE / 2) 0f else imaginary[f * FRAMES + frame]
                if (f > 0 && f < FFT_SIZE / 2) { re[FFT_SIZE - f] = re[f]; im[FFT_SIZE - f] = -im[f] }
            }
            fft.transform(re, im, inverse = true)
            for (i in 0 until FFT_SIZE) {
                val at = frame * HOP + i
                output[at] += re[i] * window[i]
                envelope[at] += window[i] * window[i]
            }
        }
        for (i in 0 until LENGTH) audio[i] = output[i + PADDING] / max(1e-8f, envelope[i + PADDING])
    }

    /**
     * [original] with only the upper band of [restored] (LavaDSP.cpp:106-125): their difference, filtered in one
     * transform of [MERGE_SIZE] points by a smoothstep from 0 at 4 kHz to 1 at 6 kHz, added to the original.
     */
    fun merge(original: FloatArray, restored: FloatArray, output: FloatArray) {
        require(original.size >= LENGTH && restored.size >= LENGTH && output.size >= LENGTH)
        val real = FloatArray(MERGE_SIZE)
        val imaginary = FloatArray(MERGE_SIZE)
        for (i in 0 until LENGTH) real[i] = restored[i] - original[i]
        val fft = Fft.of(MERGE_SIZE)
        fft.transform(real, imaginary)
        for (i in 0 until MERGE_SIZE) {
            val gain = crossover(min(i, MERGE_SIZE - i).toFloat() * 48000 / MERGE_SIZE)
            real[i] *= gain
            imaginary[i] *= gain
        }
        fft.transform(real, imaginary, inverse = true)
        for (i in 0 until LENGTH) output[i] = original[i] + real[i]
    }

    /** How much of the restoration [merge] keeps at [hz]: 0 to 4 kHz, 1 from 6 kHz, a smoothstep between. */
    fun crossover(hz: Float): Float {
        val t = ((hz - 4000) / 2000).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }
}
