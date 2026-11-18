package app.takes.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The radix-2 complex Fourier transform the speech restoration stages use
 * ([LavaDsp], [VoiceFixerDsp]), as the original's native DSP has its own
 * (ThirdParty/LavaSR/LavaDSP.cpp:12-34, ThirdParty/VoiceFixer/VoiceFixerDSP.cpp:9-30):
 * in place, forward with e^(−iωt), inverse divided by the size. The rotations
 * are worked out once per size in double precision, where the original
 * multiplies its way along each stage in single precision; the two agree to
 * the last digits only.
 */
internal class Fft(val size: Int) {
    init { require(size >= 2 && size and (size - 1) == 0) { "The size must be a power of two" } }

    private val cosines = FloatArray(size / 2) { cos(2 * PI * it / size).toFloat() }
    private val sines = FloatArray(size / 2) { sin(2 * PI * it / size).toFloat() }
    private val reversed = IntArray(size).also { table ->
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            table[i] = j
        }
    }

    /** Transforms [real] and [imaginary], each [size] long, in place; [inverse] for the way back. */
    fun transform(real: FloatArray, imaginary: FloatArray, inverse: Boolean = false) {
        for (i in 1 until size) {
            val j = reversed[i]
            if (i < j) {
                var t = real[i]; real[i] = real[j]; real[j] = t
                t = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = t
            }
        }
        val sign = if (inverse) 1f else -1f
        var width = 2
        while (width <= size) {
            val half = width / 2
            val step = size / width
            var begin = 0
            while (begin < size) {
                for (j in 0 until half) {
                    val c = cosines[j * step]
                    val s = sign * sines[j * step]
                    val a = begin + j
                    val b = a + half
                    val oddReal = real[b] * c - imaginary[b] * s
                    val oddImaginary = real[b] * s + imaginary[b] * c
                    real[b] = real[a] - oddReal
                    imaginary[b] = imaginary[a] - oddImaginary
                    real[a] += oddReal
                    imaginary[a] += oddImaginary
                }
                begin += width
            }
            width = width shl 1
        }
        if (inverse) {
            val scale = 1f / size
            for (i in 0 until size) { real[i] *= scale; imaginary[i] *= scale }
        }
    }

    companion object {
        private val made = HashMap<Int, Fft>()

        /** One per size, shared: the tables are read only. */
        @Synchronized fun of(size: Int): Fft = made.getOrPut(size) { Fft(size) }
    }
}
