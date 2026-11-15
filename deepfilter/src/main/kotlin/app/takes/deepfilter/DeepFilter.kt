// Ported from the iPhone app's DeepFilterNetEngine.swift, itself adapted from Kyle Howells'
// DeepFilterNet-mlx (commit c6c4b9dc98c62bfa90ed66c5fd4f5d54e88c7b4f, Apache-2.0 / MIT).
// See NOTICES.txt.
package app.takes.deepfilter

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One DeepFilterNet3 noise-reduction stream: the model the iPhone app offers as
 * "DeepFilterNet3" beside RNNoise.
 *
 * Audio is one channel at 48 kHz, in the usual float range of −1…1, 480 samples (10 ms)
 * per call. Each instance remembers what it heard before, so use one per channel and per
 * recording, feed it the hops in order, call [flush] once at the end, and [close] it.
 *
 * The network looks 20 ms ahead, so the first calls return nothing and the end of the
 * recording comes out of [flush]. Put together, the returned samples line up with the
 * input sample for sample: there is no delay to remove. [deepFilter] and
 * [ChannelDeepFilter] hide all of this.
 *
 * This is `DeepFilterNetEngine` from the iPhone app (ThirdParty/DeepFilterNet3), with the
 * same framing, features, filtering and end of stream. Only the network runs elsewhere:
 * ONNX Runtime here, Core ML there.
 */
class DeepFilter internal constructor(private val network: Network) : AutoCloseable {
    /** A new stream on [model]. The model must stay open for as long as the stream is used. */
    constructor(model: DeepFilterModel) : this(model.newNetwork())

    private val fft = Fft(FFT_SIZE)
    private val analysisMemory = FloatArray(HOP_SIZE)
    private val synthesisMemory = FloatArray(HOP_SIZE)
    private val meanState = FloatArray(ERB_BANDS)
    private val unitState = FloatArray(DF_BINS)

    // The last five spectra, by frame number modulo five: the filter reads two frames
    // either side of the one it writes.
    private val real = Array(DF_ORDER) { FloatArray(BINS) }
    private val imag = Array(DF_ORDER) { FloatArray(BINS) }

    private val timeReal = FloatArray(FFT_SIZE)
    private val timeImag = FloatArray(FFT_SIZE)
    private val bandReal = FloatArray(FFT_SIZE)
    private val bandImag = FloatArray(FFT_SIZE)
    private val erb = FloatArray(ERB_BANDS)
    private val spec = FloatArray(2 * DF_BINS)
    private val mask = FloatArray(ERB_BANDS)
    private val coefs = FloatArray(DF_ORDER * DF_BINS * 2)
    private val zeroHop = FloatArray(HOP_SIZE)

    private var frames = 0L
    private var inputSamples = 0L
    private var outputSamples = 0L
    private var delayToDrop = HOP_SIZE
    private var finished = false
    private var closed = false

    init { clear() }

    /**
     * Takes 480 samples of [samples] starting at [offset] and returns the cleaned samples
     * that are ready: none for the first three hops, 480 afterwards.
     */
    @Synchronized
    fun processHop(samples: FloatArray, offset: Int = 0): FloatArray {
        check(!closed) { "DeepFilter is closed" }
        check(!finished) { "reset() the DeepFilter before starting another stream" }
        require(offset >= 0 && offset <= samples.size - HOP_SIZE) { "needs $HOP_SIZE samples from $offset" }
        for (index in offset until offset + HOP_SIZE) require(samples[index].isFinite()) { "DeepFilterNet3 received a sample that is not a number" }
        inputSamples += HOP_SIZE
        val output = hop(samples, offset)
        outputSamples += output.size
        return output
    }

    /** Returns the end of the recording that was still inside. Nothing may be processed afterwards without [reset]. */
    @Synchronized
    fun flush(): FloatArray {
        check(!closed) { "DeepFilter is closed" }
        if (finished) return EMPTY
        finished = true
        if (inputSamples == 0L) return EMPTY
        // Silence pushes out what the look-ahead was holding back.
        var tail = FloatArray(0)
        repeat(LOOKAHEAD + 1) { tail += hop(zeroHop, 0) }
        val remaining = max(0L, inputSamples - outputSamples).toInt()
        if (tail.size > remaining) tail = tail.copyOf(remaining)
        outputSamples += tail.size
        return tail
    }

    /** Forgets the stream, so the same instance can clean another recording from its start. */
    @Synchronized
    fun reset() {
        check(!closed) { "DeepFilter is closed" }
        clear()
    }

    @Synchronized
    override fun close() {
        if (!closed) network.close()
        closed = true
    }

    private fun clear() {
        analysisMemory.fill(0f)
        synthesisMemory.fill(0f)
        // Where the levels start before any audio: −60 to −90 dB across the bands, and
        // 0.001 to 0.0001 across the filtered bins. These are the values the model was
        // trained with (`mean_norm_state` and `unit_norm_state` in the iPhone app's
        // DeepFilterNet3-DSP.json).
        for (band in 0 until ERB_BANDS) meanState[band] = -60f - 30f * band / (ERB_BANDS - 1)
        for (bin in 0 until DF_BINS) unitState[bin] = 0.001f - 0.0009f * bin / (DF_BINS - 1)
        for (frame in real) frame.fill(0f)
        for (frame in imag) frame.fill(0f)
        network.reset()
        frames = 0
        inputSamples = 0
        outputSamples = 0
        delayToDrop = HOP_SIZE
        finished = false
    }

    private fun hop(samples: FloatArray, offset: Int): FloatArray {
        val current = frames++
        val slot = (current % DF_ORDER).toInt()
        analyze(samples, offset, real[slot], imag[slot])
        features(real[slot], imag[slot])
        // The network answers for the frame two before the one it is given, and its own
        // look-ahead means the first two frames are never shown to it.
        if (current < LOOKAHEAD) return EMPTY
        network.run(erb, spec, mask, coefs)

        val target = ((current - LOOKAHEAD) % DF_ORDER).toInt()
        val outReal = bandReal
        val outImag = bandImag
        // Above the filtered bins: one gain per band.
        var bin = 0
        for (band in 0 until ERB_BANDS) {
            val gain = mask[band]
            repeat(ERB_WIDTHS[band]) {
                outReal[bin] = real[target][bin] * gain
                outImag[bin] = imag[target][bin] * gain
                bin++
            }
        }
        // The lowest 96 bins: a five-tap complex filter over the frames around the target.
        for (f in 0 until DF_BINS) {
            var sumReal = 0f
            var sumImag = 0f
            for (tap in 0 until DF_ORDER) {
                val frame = current - (DF_ORDER - 1) + tap
                if (frame < 0) continue // before the recording there is silence
                val source = (frame % DF_ORDER).toInt()
                val index = (tap * DF_BINS + f) * 2
                val cr = coefs[index]
                val ci = coefs[index + 1]
                val sr = real[source][f]
                val si = imag[source][f]
                sumReal += sr * cr - si * ci
                sumImag += sr * ci + si * cr
            }
            outReal[f] = sumReal
            outImag[f] = sumImag
        }
        val output = synthesize(outReal, outImag)
        // The first frame out is the half window before the recording starts.
        if (delayToDrop > 0) {
            delayToDrop = 0
            return EMPTY
        }
        return output
    }

    /** One 960-sample frame, the previous hop and this one, to 481 bins. */
    private fun analyze(samples: FloatArray, offset: Int, outReal: FloatArray, outImag: FloatArray) {
        for (index in 0 until HOP_SIZE) {
            timeReal[index] = analysisMemory[index] * WINDOW[index]
            timeReal[HOP_SIZE + index] = samples[offset + index] * WINDOW[HOP_SIZE + index]
        }
        timeImag.fill(0f)
        System.arraycopy(samples, offset, analysisMemory, 0, HOP_SIZE)
        fft.transform(timeReal, timeImag, bandReal, bandImag, inverse = false)
        for (bin in 0 until BINS) {
            outReal[bin] = bandReal[bin] * NORM
            outImag[bin] = bandImag[bin] * NORM
        }
    }

    /** What the network is shown: band levels in dB and the lowest bins, each against its running level. */
    private fun features(frameReal: FloatArray, frameImag: FloatArray) {
        var bin = 0
        for (band in 0 until ERB_BANDS) {
            val share = 1f / ERB_WIDTHS[band]
            var power = 0f
            repeat(ERB_WIDTHS[band]) {
                power += (frameReal[bin] * frameReal[bin] + frameImag[bin] * frameImag[bin]) * share
                bin++
            }
            val level = 10f * log10(power + 1e-10f)
            meanState[band] = level * (1f - ALPHA) + meanState[band] * ALPHA
            erb[band] = (level - meanState[band]) / 40f
        }
        for (f in 0 until DF_BINS) {
            val re = frameReal[f]
            val im = frameImag[f]
            unitState[f] = sqrt(re * re + im * im) * (1f - ALPHA) + unitState[f] * ALPHA
            val norm = sqrt(max(unitState[f], 1e-10f))
            spec[f] = re / norm
            spec[DF_BINS + f] = im / norm
        }
    }

    /** 481 bins back to 480 samples, overlapped with the frame before. Overwrites the arrays it is given. */
    private fun synthesize(frameReal: FloatArray, frameImag: FloatArray): FloatArray {
        for (bin in 0 until BINS) {
            frameReal[bin] = frameReal[bin] / NORM
            frameImag[bin] = frameImag[bin] / NORM
        }
        // A real signal's upper half mirrors the lower.
        for (bin in 1 until FFT_SIZE / 2) {
            frameReal[FFT_SIZE - bin] = frameReal[bin]
            frameImag[FFT_SIZE - bin] = -frameImag[bin]
        }
        fft.transform(frameReal, frameImag, timeReal, timeImag, inverse = true)
        val output = FloatArray(HOP_SIZE)
        for (index in 0 until HOP_SIZE) {
            output[index] = timeReal[index] * (1f / FFT_SIZE) * WINDOW[index] + synthesisMemory[index]
            synthesisMemory[index] = timeReal[HOP_SIZE + index] * (1f / FFT_SIZE) * WINDOW[HOP_SIZE + index]
        }
        return output
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        /** Samples per call, 10 ms. */
        const val HOP_SIZE = 480

        internal const val FFT_SIZE = 960
        internal const val BINS = FFT_SIZE / 2 + 1
        internal const val ERB_BANDS = 32
        internal const val DF_BINS = 96
        internal const val DF_ORDER = 5
        internal const val LOOKAHEAD = 2
        private const val ALPHA = 0.99f
        private const val NORM = 1f / (FFT_SIZE * FFT_SIZE) * (2 * HOP_SIZE)
        private val EMPTY = FloatArray(0)

        /**
         * How many of the 481 bins each band covers. A band's level is the mean of its
         * bins and its gain goes to all of them. The same bands as `erb_fb` and
         * `erb_inv_fb` in the iPhone app's DeepFilterNet3-DSP.json.
         */
        internal val ERB_WIDTHS = intArrayOf(
            2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 5, 5, 7,
            7, 8, 10, 12, 13, 15, 18, 20, 24, 28, 31, 37, 42, 50, 56, 67,
        )

        /** The Vorbis window, the same going in and coming out; two halves overlapped add up to the signal. */
        internal val WINDOW = FloatArray(FFT_SIZE) {
            val inner = sin(0.5f * PI.toFloat() * (it + 0.5f) / (FFT_SIZE / 2))
            sin(0.5f * PI.toFloat() * inner * inner)
        }

        init { check(ERB_WIDTHS.size == ERB_BANDS && ERB_WIDTHS.sum() == BINS) }
    }
}
