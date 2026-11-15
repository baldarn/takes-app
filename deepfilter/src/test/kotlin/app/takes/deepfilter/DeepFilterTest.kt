package app.takes.deepfilter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Everything around the network, which needs no model file: the transform, the framing,
 * the features, how the network's answer is applied, the delay, the end of a recording,
 * mixing and streaming. A stand-in answers in place of the network, so the expected
 * result is known exactly. [DeepFilterModelTest] runs the real network.
 */
class DeepFilterTest {
    private val hop = DeepFilter.HOP_SIZE

    /** Answers with one gain for every band and fixed filter taps, and notes what it was shown. */
    private class Stand(val gain: Float = 1f, val taps: Map<Int, Float> = mapOf(2 to 1f)) : Network {
        val erb = ArrayList<FloatArray>()
        val spec = ArrayList<FloatArray>()
        var resets = 0
        var closed = false

        override fun run(erb: FloatArray, spec: FloatArray, mask: FloatArray, coefs: FloatArray) {
            this.erb += erb.copyOf()
            this.spec += spec.copyOf()
            mask.fill(gain)
            coefs.fill(0f)
            for ((tap, value) in taps) for (bin in 0 until DeepFilter.DF_BINS) coefs[(tap * DeepFilter.DF_BINS + bin) * 2] = value
        }

        override fun reset() { resets++ }
        override fun close() { closed = true }
    }

    private fun signal(count: Int, seed: Long = 3): FloatArray {
        val random = Random(seed)
        return FloatArray(count) { (random.nextGaussian() * 0.05).toFloat() + 0.2f * sin(it * 0.03f) }
    }

    private fun maxDifference(a: FloatArray, b: FloatArray): Float {
        assertEquals(a.size, b.size)
        var worst = 0f
        for (i in a.indices) worst = maxOf(worst, abs(a[i] - b[i]))
        return worst
    }

    @Test fun transformMatchesTheDefinition() {
        val random = Random(5)
        for (size in listOf(1, 2, 3, 5, 30, 960)) {
            val real = FloatArray(size) { random.nextGaussian().toFloat() }
            val imag = FloatArray(size) { random.nextGaussian().toFloat() }
            for (inverse in listOf(false, true)) {
                val outReal = FloatArray(size); val outImag = FloatArray(size)
                Fft(size).transform(real, imag, outReal, outImag, inverse)
                var worst = 0.0
                for (k in 0 until size) {
                    var sumReal = 0.0; var sumImag = 0.0
                    for (n in 0 until size) {
                        val angle = (if (inverse) 2 else -2) * PI * k * n / size
                        sumReal += real[n] * cos(angle) - imag[n] * sin(angle)
                        sumImag += real[n] * sin(angle) + imag[n] * cos(angle)
                    }
                    worst = maxOf(worst, abs(sumReal - outReal[k]), abs(sumImag - outImag[k]))
                }
                assertTrue("size $size inverse $inverse: off by $worst", worst < 2e-4 * sqrt(size.toDouble()))
            }
        }
    }

    /** Two overlapping halves of the window, used going in and coming out, must add up to the signal. */
    @Test fun windowHalvesAddUpToOne() {
        for (i in 0 until hop) {
            val sum = DeepFilter.WINDOW[i] * DeepFilter.WINDOW[i] + DeepFilter.WINDOW[i + hop] * DeepFilter.WINDOW[i + hop]
            assertEquals(1f, sum, 1e-6f)
        }
    }

    /** The bands written down in the code are the ones DeepFilterNet's own library derives for 48 kHz. */
    @Test fun bandsAreTheOnesDeepFilterNetUses() {
        fun toErb(hz: Float) = 9.265f * ln(1f + hz / (24.7f * 9.265f))
        fun toHz(erb: Float) = 24.7f * 9.265f * (exp(erb / 9.265f) - 1f)
        val step = toErb(24_000f) / 32
        val widths = IntArray(32)
        var previous = 0; var carried = 0
        for (band in 1..32) {
            val boundary = (toHz(band * step) / 50f).roundToInt()
            var width = boundary - previous - carried
            if (width < 2) { carried = 2 - width; width = 2 } else carried = 0
            widths[band - 1] = width
            previous = boundary
        }
        widths[31] += 1
        widths[31] -= maxOf(0, widths.sum() - 481)
        assertArrayEquals(widths, DeepFilter.ERB_WIDTHS)
    }

    /**
     * With a network that changes nothing, what comes out is what went in: every length is
     * returned whole, and each sample at its own place, not 30 ms later.
     */
    @Test fun anyLengthComesBackWholeAndAligned() {
        for (length in listOf(1, 479, 480, 481, 960, 1000, 1440, 1441, 4800 + 37)) {
            val dry = signal(length)
            val stand = Stand()
            val wet = dry.copyOf()
            val steps = ArrayList<Float>()
            deepFilter(DeepFilter(stand), wet, 1f) { steps += it }
            val worst = maxDifference(dry, wet)
            assertTrue("length $length: off by $worst", worst < 1e-4f)
            assertEquals(steps, steps.sorted())
            assertEquals(1f, steps.last(), 0f)
            // The network is asked once per hop, and once more for the frame that ends the recording.
            assertEquals("length $length", (length + hop - 1) / hop + 1, stand.erb.size)
        }
    }

    @Test fun firstHopsReturnNothingAndFlushReturnsTheRest() {
        val dry = signal(hop * 6)
        val stand = Stand()
        val stream = DeepFilter(stand)
        val sizes = (0 until 6).map { stream.processHop(dry, it * hop).size }
        assertEquals(listOf(0, 0, 0, hop, hop, hop), sizes)
        assertEquals(3 * hop, stream.flush().size)
        assertEquals(0, stream.flush().size)
        assertThrows(IllegalStateException::class.java) { stream.processHop(dry) }

        // Nothing in, nothing out.
        stream.reset()
        assertEquals(0, stream.flush().size)

        assertThrows(IllegalArgumentException::class.java) { DeepFilter(Stand()).processHop(FloatArray(hop - 1)) }
        assertThrows(IllegalArgumentException::class.java) { DeepFilter(Stand()).processHop(FloatArray(hop), 1) }
        assertThrows(IllegalArgumentException::class.java) { DeepFilter(Stand()).processHop(FloatArray(hop) { Float.NaN }) }

        stream.close()
        stream.close()
        assertTrue(stand.closed)
        assertThrows(IllegalStateException::class.java) { stream.processHop(dry) }
        assertThrows(IllegalStateException::class.java) { stream.reset() }
    }

    @Test fun resetStartsOverExactly() {
        val dry = signal(hop * 10 + 77)
        val stand = Stand(gain = 0.5f, taps = mapOf(1 to 0.2f, 2 to 0.3f))
        val stream = DeepFilter(stand)
        val first = dry.copyOf().also { deepFilter(stream, it, 1f) }
        val shown = stand.erb.map { it.copyOf() }
        val resets = stand.resets
        stream.reset()
        assertEquals(resets + 1, stand.resets)
        stand.erb.clear()
        val second = dry.copyOf().also { deepFilter(stream, it, 1f) }
        assertArrayEquals(first, second, 0f)
        // The running levels the features are measured against start over too.
        for (i in shown.indices) assertArrayEquals(shown[i], stand.erb[i], 0f)
    }

    /**
     * What the network is shown, against the definition worked out separately in double
     * precision: band levels in dB and the lowest bins, each against a running level that
     * starts where the model expects. The first two frames are never shown.
     */
    @Test fun featuresFollowTheDefinition() {
        val frames = 12
        val dry = signal(hop * frames, seed = 9)
        val stand = Stand()
        DeepFilter(stand).use { stream -> for (frame in 0 until frames) stream.processHop(dry, frame * hop) }
        assertEquals(frames - 2, stand.erb.size)

        val mean = DoubleArray(32) { -60.0 - 30.0 * it / 31 }
        val unit = DoubleArray(96) { 0.001 - 0.0009 * it / 95 }
        val window = DoubleArray(960) { sin(0.5 * PI * sin(0.5 * PI * (it + 0.5) / 480).let { inner -> inner * inner }) }
        var worstErb = 0.0; var worstSpec = 0.0
        for (frame in 0 until frames) {
            val real = DoubleArray(481); val imag = DoubleArray(481)
            for (k in 0 until 481) {
                for (n in 0 until 960) {
                    val index = (frame - 1) * hop + n
                    val sample = if (index < 0) 0.0 else dry[index] * window[n]
                    real[k] += sample * cos(2 * PI * k * n / 960)
                    imag[k] -= sample * sin(2 * PI * k * n / 960)
                }
                real[k] /= 960.0; imag[k] /= 960.0
            }
            var bin = 0
            for (band in 0 until 32) {
                var power = 0.0
                repeat(DeepFilter.ERB_WIDTHS[band]) { power += (real[bin] * real[bin] + imag[bin] * imag[bin]) / DeepFilter.ERB_WIDTHS[band]; bin++ }
                val level = 10 * log10(power + 1e-10)
                mean[band] = level * 0.01 + mean[band] * 0.99
                if (frame >= 2) worstErb = maxOf(worstErb, abs((level - mean[band]) / 40 - stand.erb[frame - 2][band]))
            }
            for (f in 0 until 96) {
                unit[f] = sqrt(real[f] * real[f] + imag[f] * imag[f]) * 0.01 + unit[f] * 0.99
                if (frame >= 2) {
                    worstSpec = maxOf(worstSpec, abs(real[f] / sqrt(unit[f]) - stand.spec[frame - 2][f]))
                    worstSpec = maxOf(worstSpec, abs(imag[f] / sqrt(unit[f]) - stand.spec[frame - 2][96 + f]))
                }
            }
        }
        println("features against the definition: band levels off by $worstErb, bins off by $worstSpec")
        assertTrue("band levels off by $worstErb", worstErb < 1e-4)
        assertTrue("bins off by $worstSpec", worstSpec < 1e-3)
    }

    @Test fun silenceGivesTheLevelsTheModelStartsFrom() {
        val stand = Stand()
        DeepFilter(stand).use { stream -> repeat(3) { stream.processHop(FloatArray(hop)) } }
        // Third frame of silence at −100 dB against a level that started at −60 dB.
        val level = -100f
        var state = -60f
        repeat(3) { state = level * 0.01f + state * 0.99f }
        assertEquals((level - state) / 40f, stand.erb[0][0], 1e-5f)
        assertTrue(stand.spec[0].all { it == 0f })
    }

    /** The gain reaches every bin, and the filter replaces the lowest bins. */
    @Test fun gainsAndFilterAreApplied() {
        val dry = signal(hop * 8 + 5)
        val wet = dry.copyOf()
        deepFilter(DeepFilter(Stand(gain = 0.5f, taps = mapOf(2 to 0.5f))), wet, 1f)
        val worst = maxDifference(FloatArray(dry.size) { dry[it] * 0.5f }, wet)
        assertTrue("off by $worst", worst < 1e-4f)

        // A filter of zero silences the bins below 4.8 kHz and leaves the rest.
        fun level(samples: FloatArray, hz: Double): Double {
            var re = 0.0; var im = 0.0
            for (i in hop until samples.size - hop) { re += samples[i] * cos(2 * PI * hz * i / 48_000); im += samples[i] * sin(2 * PI * hz * i / 48_000) }
            return sqrt(re * re + im * im) / (samples.size - 2 * hop) * 2
        }
        val tones = FloatArray(hop * 40) { (0.1 * sin(2 * PI * 1000 * it / 48_000) + 0.1 * sin(2 * PI * 9000 * it / 48_000)).toFloat() }
        deepFilter(DeepFilter(Stand(gain = 1f, taps = emptyMap())), tones, 1f)
        assertTrue("1 kHz left at ${level(tones, 1000.0)}", level(tones, 1000.0) < 1e-4)
        assertEquals(0.1, level(tones, 9000.0), 1e-3)
    }

    /** The five filter taps are the two frames before, the frame itself and the two after, in that order. */
    @Test fun filterTapsRunFromThePastToTheFuture() {
        // Only below 4 kHz, where the filter works, and with no repeating pattern.
        val random = Random(11)
        val parts = List(40) { Triple(200 + 3500 * random.nextDouble(), 2 * PI * random.nextDouble(), random.nextDouble()) }
        val dry = FloatArray(hop * 30) { i ->
            val fade = 0.5 - 0.5 * cos(2 * PI * i / (hop * 30))
            (0.01 * fade * parts.sumOf { (hz, phase, level) -> level * sin(2 * PI * hz * i / 48_000 + phase) }).toFloat()
        }
        for ((tap, expected) in listOf(0 to 2 * hop, 1 to hop, 2 to 0, 3 to -hop, 4 to -2 * hop)) {
            val wet = dry.copyOf()
            deepFilter(DeepFilter(Stand(taps = mapOf(tap to 1f))), wet, 1f)
            fun match(lag: Int): Double {
                var sum = 0.0
                for (i in 3 * hop until dry.size - 3 * hop) sum += wet[i + lag].toDouble() * dry[i]
                return sum
            }
            assertEquals("tap $tap", expected, (-3 * hop..3 * hop).maxByOrNull { match(it) })
        }
    }

    @Test fun amountMixesWithTheOriginalOfTheSameInstant() {
        val dry = signal(hop * 9 + 123)
        val stand = { DeepFilter(Stand(gain = 0.3f, taps = mapOf(1 to 0.4f, 3 to -0.2f))) }
        val full = dry.copyOf().also { deepFilter(stand(), it, 1f) }
        val half = dry.copyOf().also { deepFilter(stand(), it, 0.5f) }
        for (i in dry.indices) assertEquals(dry[i] + 0.5f * (full[i] - dry[i]), half[i], 0f)
        assertTrue(maxDifference(dry, full) > 0.01f)
    }

    @Test fun zeroAmountLeavesTheAudioUntouched() {
        val dry = signal(1000)
        val samples = dry.copyOf()
        val stand = Stand()
        var reported = -1f
        deepFilter(DeepFilter(stand), samples, 0f) { reported = it }
        assertArrayEquals(dry, samples, 0f)
        assertEquals(1f, reported, 0f)
        assertEquals(0, stand.erb.size)
        assertThrows(IllegalArgumentException::class.java) { deepFilter(DeepFilter(stand), samples, 1.5f) }
        assertThrows(IllegalArgumentException::class.java) { deepFilter(DeepFilter(stand), samples, -0.1f) }
        assertThrows(IllegalArgumentException::class.java) { deepFilter(DeepFilter(stand), samples, Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { ChannelDeepFilter(DeepFilter(stand), Float.NaN) }

        // Nothing is changed when the audio itself is refused.
        val broken = dry.copyOf().also { it[900] = Float.POSITIVE_INFINITY }
        val before = broken.copyOf()
        assertThrows(IllegalArgumentException::class.java) { deepFilter(DeepFilter(stand), broken, 1f) }
        assertArrayEquals(before, broken, 0f)

        // An empty recording is not an error.
        deepFilter(DeepFilter(stand), FloatArray(0), 1f) { reported = it }
        assertEquals(1f, reported, 0f)
    }

    @Test fun streamingMatchesTheWholeChannelBitForBit() {
        val random = Random(7)
        for (length in listOf(0, 1, 479, 480, 481, 2000, 48_000 + 123)) {
            val input = signal(length, seed = length.toLong())
            for (amount in listOf(1f, 0.5f, 0f)) {
                val stand = { DeepFilter(Stand(gain = 0.6f, taps = mapOf(0 to 0.1f, 2 to 0.5f, 4 to -0.1f))) }
                val whole = input.copyOf().also { deepFilter(stand(), it, amount) }
                // At zero the public constructor runs no model at all.
                val streamed = stream(ChannelDeepFilter(if (amount == 0f) null else stand(), amount), input, random)
                assertArrayEquals("length $length amount $amount", whole, streamed, 0f)
            }
        }
    }

    /** The files the module ships with are what the code expects, with or without the weights. */
    @Test fun shippedGraphPointsIntoThePublishedModel() {
        val graph = DeepFilterModel::class.java.getResourceAsStream("streaming-graph.onnx")!!.use { it.readBytes() }
        val tensors = externalTensors(graph)
        assertTrue(tensors.isNotEmpty())
        assertEquals(tensors.size, tensors.map { it.name }.toSet().size)
        for (tensor in tensors) {
            assertTrue(tensor.name, tensor.type == 1 || tensor.type == 7)
            assertTrue(tensor.name, tensor.offset > 0 && tensor.offset + tensor.length <= DeepFilterModel.SIZE)
            val values = tensor.dims.fold(1L) { size, dim -> size * dim }
            assertEquals(tensor.name, values * (if (tensor.type == 1) 4 else 8), tensor.length)
        }
        // Nearly all of the published file is weights, and the graph uses nearly all of them.
        val bytes = tensors.sumOf { it.length }
        println("shipped graph: ${graph.size} bytes, ${tensors.size} weight tensors, $bytes of ${DeepFilterModel.SIZE} bytes of the model")
        assertTrue("$bytes bytes", bytes > DeepFilterModel.SIZE * 0.9)
    }

    @Test fun anyOtherModelFileIsRefused() {
        assertThrows(DeepFilterModelException::class.java) { DeepFilterModel(ByteArray(10)) }
        assertThrows(DeepFilterModelException::class.java) { DeepFilterModel(ByteArray(DeepFilterModel.SIZE)) }
        assertThrows(DeepFilterModelException::class.java) { DeepFilterModel(ByteArray(DeepFilterModel.SIZE + 1).inputStream()) }
        assertThrows(DeepFilterModelException::class.java) { DeepFilterModel(ByteArray(DeepFilterModel.SIZE).inputStream()) }
        assertThrows(DeepFilterModelException::class.java) { DeepFilterModel(File("build/no-such-model.onnx")) }
        val wrong = File.createTempFile("deepfilter", ".onnx").apply { writeBytes(ByteArray(1000)); deleteOnExit() }
        assertThrows(DeepFilterModelException::class.java) { DeepFilterModel(wrong) }
    }
}

/** Feeds [input] in uneven chunks, as a decoder would deliver them, and collects what comes back. */
internal fun stream(channel: ChannelDeepFilter, input: FloatArray, random: Random): FloatArray {
    val streamed = FloatArray(input.size)
    var written = 0
    val collect = { block: FloatArray, count: Int -> System.arraycopy(block, 0, streamed, written, count); written += count }
    channel.use {
        var offset = 0
        while (offset < input.size) {
            val chunk = minOf(input.size - offset, 1 + random.nextInt(1500))
            channel.write(input, offset, chunk, collect)
            offset += chunk
        }
        channel.finish(collect)
    }
    assertEquals(input.size, written)
    return streamed
}
