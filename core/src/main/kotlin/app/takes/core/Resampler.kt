package app.takes.core

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Changes the sample rate of one channel as it arrives, for Studio restoration, which works at 44.1 kHz on
 * sound kept at 48 kHz: the original converts the whole file there and back with AVAudioConverter at its
 * best quality (YouRec/StudioRestorationProcessor.swift:97-120), which Android has no equivalent of.
 *
 * Band-limited interpolation: each output sample is the input weighted by a windowed sinc centred on where
 * it falls, [HALF_WIDTH] input samples either side, with a Kaiser window (β [BETA], about 86 dB) and a cutoff
 * at 95 % of the lower rate's Nyquist frequency, one table of weights for each of the fractional positions
 * the ratio of the rates has. There is no delay: output sample n is the input at n × in/out seconds, the
 * samples before the first and after the last counting as silence. [inputRate] samples give
 * ⌈count × out/in⌉ samples, the last ones drained by [finish].
 */
class Resampler(val inputRate: Int, val outputRate: Int) {
    // Output n falls at input position n × step / phases.
    private val phases: Int
    private val step: Int

    init {
        require(inputRate > 0 && outputRate > 0)
        var a = inputRate; var b = outputRate
        while (b != 0) { val t = a % b; a = b; b = t }
        phases = outputRate / a
        step = inputRate / a
    }

    private val taps = 2 * HALF_WIDTH
    private val weights: FloatArray = FloatArray(phases * taps).also { table ->
        // In cycles per input sample.
        val cutoff = 0.5 * 0.95 * minOf(1.0, outputRate.toDouble() / inputRate)
        val norm = bessel(BETA)
        for (p in 0 until phases) {
            val fraction = p.toDouble() / phases
            var sum = 0.0
            val row = DoubleArray(taps) { j ->
                val x = (j - HALF_WIDTH + 1) - fraction
                val r = x / HALF_WIDTH
                val kaiser = if (r * r >= 1) 0.0 else bessel(BETA * sqrt(1 - r * r)) / norm
                val sinc = if (x == 0.0) 1.0 else sin(2 * PI * cutoff * x) / (2 * PI * cutoff * x)
                2 * cutoff * sinc * kaiser
            }
            row.forEach { sum += it }
            // Each position passes a constant level unchanged.
            for (j in 0 until taps) table[p * taps + j] = (row[j] / sum).toFloat()
        }
    }

    // The input not yet behind every output still to come; history[0] is input sample [held].
    private var history = FloatArray(1 shl 14)
    private var size = 0
    private var held = 0L
    private var written = 0L
    private var produced = 0L
    private var finished = false
    private val block = FloatArray(4096)

    /** The output samples [count] input samples give: the smallest n with n × in/out ≥ count. */
    fun outputCount(count: Long): Long = (count * phases + step - 1) / step

    /** Takes [count] samples of [samples] from [offset]; [output] receives the samples it can give now. */
    fun write(samples: FloatArray, offset: Int, count: Int, output: (FloatArray, Int) -> Unit) {
        check(!finished) { "The resampler is finished" }
        if (size + count > history.size) history = history.copyOf(maxOf(history.size * 2, size + count))
        System.arraycopy(samples, offset, history, size, count)
        size += count
        written += count
        emit(output, false)
    }

    /** Gives the rest: the outputs that fall before the end of the input. */
    fun finish(output: (FloatArray, Int) -> Unit) {
        if (finished) return
        finished = true
        emit(output, true)
    }

    private fun emit(output: (FloatArray, Int) -> Unit, end: Boolean) {
        val total = if (end) outputCount(written) else Long.MAX_VALUE
        var filled = 0
        while (produced < total) {
            val position = produced * step
            val base = position / phases
            val phase = (position - base * phases).toInt()
            // The last input this output reads; past the end it is silence.
            if (!end && base + HALF_WIDTH >= written) break
            var sum = 0f
            val first = base - HALF_WIDTH + 1
            val row = phase * taps
            for (j in 0 until taps) {
                val at = first + j - held
                if (at >= 0 && at < size) sum += history[at.toInt()] * weights[row + j]
            }
            block[filled++] = sum
            produced++
            if (filled == block.size) { output(block, filled); filled = 0 }
        }
        if (filled > 0) output(block, filled)
        // Keeps what the next output reads from its first tap on.
        val keep = (produced * step / phases - HALF_WIDTH + 1).coerceIn(held, held + size)
        val drop = (keep - held).toInt()
        if (drop > 0) {
            System.arraycopy(history, drop, history, 0, size - drop)
            size -= drop
            held = keep
        }
    }

    companion object {
        const val HALF_WIDTH = 64
        const val BETA = 8.6

        /** The zeroth-order modified Bessel function of the first kind, by its series. */
        private fun bessel(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var k = 1
            while (term > 1e-12 * sum) {
                term *= (x / (2 * k)) * (x / (2 * k))
                sum += term
                k++
            }
            return sum
        }
    }
}
