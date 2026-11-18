package app.takes.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The frontend of VoiceFixer, Studio restoration ([AudioEffectKind.StudioRestoration]), a port of the
 * original's (ThirdParty/VoiceFixer/VoiceFixerDSP.cpp): [CONTEXT] samples at 44.1 kHz to [FRAMES] frames of
 * 128 mel magnitudes, from a periodic Hann STFT of 2048 points every 441 samples with reflected centre
 * padding, through the author's HTK filterbank (`VoiceFixer-Mel.bin`, `[1025][128]` row by row, which is
 * also the published ONNX conversion's `mel_filters.npy`). The network takes them as they are: its own
 * graph takes the logarithm.
 */
object VoiceFixerDsp {
    const val RATE = 44_100
    const val FFT_SIZE = 2048
    const val HOP = 441
    const val BINS = FFT_SIZE / 2 + 1
    const val MELS = 128
    const val FRAMES = 256

    /** The samples one window holds: 255 hops, so that 256 frames cover it. */
    const val CONTEXT = 255 * HOP

    /** What the vocoder gives back for a window: its padding of two hops each side included. */
    const val OUTPUT = 260 * HOP

    private val window = FloatArray(FFT_SIZE) { (0.5 * (1 - cos(2 * PI * it / FFT_SIZE))).toFloat() }

    /** [audio], [CONTEXT] samples, as the network's `mel`, `[256][128]`, through [bank], `[1025][128]`. */
    fun features(audio: FloatArray, bank: FloatArray, mel: FloatArray) {
        require(audio.size >= CONTEXT && bank.size >= BINS * MELS && mel.size >= FRAMES * MELS)
        val fft = Fft.of(FFT_SIZE)
        val real = FloatArray(FFT_SIZE)
        val imaginary = FloatArray(FFT_SIZE)
        val magnitude = FloatArray(BINS)
        for (t in 0 until FRAMES) {
            for (i in 0 until FFT_SIZE) {
                var j = t * HOP + i - FFT_SIZE / 2
                if (j < 0) j = -j
                if (j >= CONTEXT) j = 2 * CONTEXT - 2 - j
                real[i] = audio[j] * window[i]
                imaginary[i] = 0f
            }
            fft.transform(real, imaginary)
            for (f in 0 until BINS) magnitude[f] = sqrt(max(1e-8f, real[f] * real[f] + imaginary[f] * imaginary[f]))
            val row = t * MELS
            for (m in 0 until MELS) mel[row + m] = 0f
            for (f in 0 until BINS) {
                val value = magnitude[f]
                val weights = f * MELS
                for (m in 0 until MELS) mel[row + m] += value * bank[weights + m]
            }
        }
    }

    /**
     * The filterbank from the bytes of `mel_filters.npy`: a version 1 NumPy header for little-endian float32,
     * `[1025][128]`, then the values. Null when the bytes are not that.
     */
    fun bank(npy: ByteArray): FloatArray? {
        if (npy.size < 10 || npy[0] != 0x93.toByte() || String(npy, 1, 5, Charsets.US_ASCII) != "NUMPY" || npy[6].toInt() != 1) return null
        val headerLength = (npy[8].toInt() and 0xff) or ((npy[9].toInt() and 0xff) shl 8)
        val start = 10 + headerLength
        if (npy.size != start + BINS * MELS * 4) return null
        val header = String(npy, 10, headerLength, Charsets.US_ASCII)
        if ("'<f4'" !in header || "'fortran_order': False" !in header || "($BINS, $MELS)" !in header) return null
        val values = java.nio.ByteBuffer.wrap(npy, start, BINS * MELS * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(BINS * MELS).also { values.get(it) }
    }
}
