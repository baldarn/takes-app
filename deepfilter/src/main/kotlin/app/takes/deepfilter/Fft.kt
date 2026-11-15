package app.takes.deepfilter

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A discrete Fourier transform of complex numbers for any [size] made of small prime
 * factors. DeepFilterNet works on 960 samples, which is not a power of two, so the usual
 * radix-2 transform does not fit. Neither direction scales its result.
 */
internal class Fft(private val size: Int) {
    private val factors: IntArray
    private val cosine = FloatArray(size) { cos(2 * PI * it / size).toFloat() }
    private val sine = FloatArray(size) { sin(2 * PI * it / size).toFloat() }
    private val scratchReal: Array<FloatArray>
    private val scratchImag: Array<FloatArray>

    init {
        require(size > 0) { "size must be positive" }
        val found = ArrayList<Int>()
        var rest = size
        var factor = 2
        while (rest > 1) {
            if (rest % factor == 0) { found += factor; rest /= factor } else factor++
        }
        factors = found.toIntArray()
        scratchReal = Array(factors.size) { FloatArray(factors[it]) }
        scratchImag = Array(factors.size) { FloatArray(factors[it]) }
    }

    /** Transforms [size] values. With [inverse] the exponent is positive. Input and output must be different arrays. */
    fun transform(inReal: FloatArray, inImag: FloatArray, outReal: FloatArray, outImag: FloatArray, inverse: Boolean) {
        step(inReal, inImag, 0, 1, outReal, outImag, 0, size, 0, if (inverse) 1f else -1f)
    }

    private fun step(
        inReal: FloatArray, inImag: FloatArray, inOffset: Int, stride: Int,
        outReal: FloatArray, outImag: FloatArray, outOffset: Int, count: Int, depth: Int, sign: Float,
    ) {
        if (count == 1) {
            outReal[outOffset] = inReal[inOffset]
            outImag[outOffset] = inImag[inOffset]
            return
        }
        val radix = factors[depth]
        val part = count / radix
        for (q in 0 until radix) {
            step(inReal, inImag, inOffset + q * stride, stride * radix, outReal, outImag, outOffset + q * part, part, depth + 1, sign)
        }
        val real = scratchReal[depth]
        val imag = scratchImag[depth]
        val turn = size / count // table steps for one step of this level's root of unity
        val spoke = size / radix
        for (k in 0 until part) {
            for (q in 0 until radix) {
                val index = outOffset + q * part + k
                val c = cosine[q * k * turn]
                val s = sign * sine[q * k * turn]
                real[q] = outReal[index] * c - outImag[index] * s
                imag[q] = outReal[index] * s + outImag[index] * c
            }
            for (r in 0 until radix) {
                var sumReal = 0f
                var sumImag = 0f
                for (q in 0 until radix) {
                    val angle = (q * r % radix) * spoke
                    val c = cosine[angle]
                    val s = sign * sine[angle]
                    sumReal += real[q] * c - imag[q] * s
                    sumImag += real[q] * s + imag[q] * c
                }
                outReal[outOffset + r * part + k] = sumReal
                outImag[outOffset + r * part + k] = sumImag
            }
        }
    }
}
