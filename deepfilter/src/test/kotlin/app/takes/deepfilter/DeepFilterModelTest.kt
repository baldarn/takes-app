package app.takes.deepfilter

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.FloatBuffer
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Runs the real network through ONNX Runtime on this computer, with the same Kotlin code
 * the app uses. The weights are not in git: these tests are skipped unless the published
 * file is at `deepfilter/models/deepfilter.onnx` (`./gradlew :deepfilter:fetchModel`) or
 * where TAKES_DFN3_MODEL points. The signals are synthetic; the printed levels are what
 * was measured.
 */
class DeepFilterModelTest {
    private val rate = DeepFilter.SAMPLE_RATE
    private val hop = DeepFilter.HOP_SIZE

    @Before fun needsTheModel() {
        assumeTrue("DeepFilterNet3 weights not found at $path", path.isFile)
    }

    private fun pinkNoise(count: Int, level: Float, seed: Long = 1): FloatArray {
        val random = Random(seed)
        var slow = 0.0; var middle = 0.0; var fast = 0.0
        val out = FloatArray(count) {
            val white = random.nextGaussian()
            slow = 0.99765 * slow + white * 0.0990460
            middle = 0.96300 * middle + white * 0.2965164
            fast = 0.57000 * fast + white * 1.0526913
            (slow + middle + fast + white * 0.1848).toFloat()
        }
        val scale = level / rms(out)
        for (i in out.indices) out[i] *= scale
        return out
    }

    /**
     * Something a voice detector can take for a vowel: harmonics of a gliding pitch around
     * 130 Hz, loudest near the formants of "ah", in four syllables a second. With
     * [pauses], every second half second is silent.
     */
    private fun voice(count: Int, level: Float, pauses: Boolean = false): FloatArray {
        val formants = listOf(700.0 to 110.0, 1200.0 to 130.0, 2600.0 to 200.0)
        val out = FloatArray(count)
        var phase = 0.0
        for (i in 0 until count) {
            val t = i.toDouble() / rate
            val pitch = 130 + 25 * sin(2 * PI * 0.7 * t)
            phase += 2 * PI * pitch / rate
            var sample = 0.0
            for (harmonic in 1..30) {
                val hz = harmonic * pitch
                var gain = 0.03
                for ((center, width) in formants) gain += exp(-0.5 * ((hz - center) / width).let { it * it })
                sample += gain / harmonic * sin(harmonic * phase)
            }
            val syllable = 0.5 - 0.5 * kotlin.math.cos(2 * PI * 4 * t)
            out[i] = if (pauses && t % 1.0 >= 0.5) 0f else (sample * syllable).toFloat()
        }
        val scale = level / rms(out)
        for (i in out.indices) out[i] *= scale
        return out
    }

    private fun rms(samples: FloatArray, from: Int = 0, to: Int = samples.size): Float {
        var sum = 0.0
        for (i in from until to) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / (to - from)).toFloat()
    }

    private fun db(ratio: Double) = 20 * log10(ratio)

    /** How much of [reference] is in [signal], as a gain. */
    private fun share(signal: FloatArray, reference: FloatArray, from: Int): Double {
        var cross = 0.0; var power = 0.0
        for (i in from until signal.size) { cross += signal[i].toDouble() * reference[i]; power += reference[i].toDouble() * reference[i] }
        return cross / power
    }

    /**
     * The frame-by-frame graph this module ships against the published file, which does a
     * whole recording at once: same features in, same gains and filters out.
     */
    @Test fun shippedGraphGivesWhatThePublishedModelGives() {
        val frames = 60
        val random = Random(147)
        val erb = FloatArray(frames * 32) { random.nextGaussian().toFloat() }
        val spec = FloatArray(2 * frames * 96) { random.nextGaussian().toFloat() }
        val environment = OrtEnvironment.getEnvironment()
        val expectedMask = FloatArray(frames * 32)
        val expectedCoefs = FloatArray(5 * frames * 96 * 2)
        environment.createSession(path.absolutePath, OrtSession.SessionOptions()).use { session ->
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(erb), longArrayOf(1, 1, frames.toLong(), 32)).use { erbTensor ->
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(spec), longArrayOf(1, 2, frames.toLong(), 96)).use { specTensor ->
                    session.run(mapOf("feat_erb" to erbTensor, "feat_spec" to specTensor)).use { result ->
                        (result.get("erb_mask").get() as OnnxTensor).floatBuffer.get(expectedMask)
                        (result.get("df_coefs").get() as OnnxTensor).floatBuffer.get(expectedCoefs)
                    }
                }
            }
        }
        var worst = 0f
        model.newNetwork().use { network ->
            network.reset()
            val mask = FloatArray(32); val coefs = FloatArray(5 * 96 * 2)
            val frameErb = FloatArray(32); val frameSpec = FloatArray(2 * 96)
            for (frame in 0 until frames) {
                // The published file looks two frames ahead by itself, and sees nothing after the end.
                val shown = frame + 2
                frameErb.fill(0f); frameSpec.fill(0f)
                if (shown < frames) {
                    erb.copyInto(frameErb, 0, shown * 32, shown * 32 + 32)
                    for (part in 0 until 2) spec.copyInto(frameSpec, part * 96, (part * frames + shown) * 96, (part * frames + shown) * 96 + 96)
                }
                network.run(frameErb, frameSpec, mask, coefs)
                for (band in 0 until 32) worst = maxOf(worst, abs(mask[band] - expectedMask[frame * 32 + band]))
                for (tap in 0 until 5) for (i in 0 until 96 * 2) {
                    worst = maxOf(worst, abs(coefs[tap * 96 * 2 + i] - expectedCoefs[(tap * frames + frame) * 96 * 2 + i]))
                }
            }
        }
        println("frame by frame against the published model: off by at most $worst")
        assertTrue("off by $worst", worst < 5e-5f)
    }

    @Test fun silenceStaysSilent() {
        val samples = FloatArray(rate + 123)
        deepFilter(model, samples, 1f)
        assertTrue(samples.all { it == 0f })
    }

    @Test fun steadyNoiseIsReduced() {
        val dry = pinkNoise(rate * 4, 0.02f)
        val wet = dry.copyOf()
        val started = System.nanoTime()
        deepFilter(model, wet, 1f)
        val seconds = (System.nanoTime() - started) / 1e9
        // The first second is left out: the network needs a moment to learn the noise.
        val before = rms(dry, rate); val after = rms(wet, rate)
        val change = db(after.toDouble() / before)
        println("pink noise: rms $before -> $after, %.1f dB".format(change))
        println("speed on this computer: 4 s of audio in %.2f s".format(seconds))
        assertTrue("noise only fell by $change dB", change < -15)
    }

    @Test fun voiceSurvivesBetterThanNoise() {
        val clean = voice(rate * 6, 0.1f, pauses = true)
        val hiss = pinkNoise(clean.size, 0.01f, seed = 2)
        val wet = FloatArray(clean.size) { clean[it] + hiss[it] }
        deepFilter(model, wet, 1f)
        // The noise is unrelated to the voice, so the part of the output that follows the
        // clean voice is the voice that was kept.
        val voiceDb = db(share(wet, clean, rate))
        // In the pauses there is only noise, so what is left there is the noise that was kept.
        var before = 0.0; var after = 0.0
        for (i in rate until clean.size) if (i % rate >= rate * 6 / 10) { before += hiss[i].toDouble() * hiss[i]; after += wet[i].toDouble() * wet[i] }
        val pauseDb = db(sqrt(after / before))
        println("voice + pink noise: voice %.1f dB, noise in the pauses %.1f dB".format(voiceDb, pauseDb))
        assertTrue("voice fell by $voiceDb dB", voiceDb > -3)
        assertTrue("noise only fell by $pauseDb dB", pauseDb < -20)
    }

    /**
     * The iPhone app's own timing check (Tests/DeepFilterTests.swift): a voice-like signal
     * with a little noise comes out where it went in, not 30 ms later.
     */
    @Test fun outputIsNotDelayed() {
        val harmonic = FloatArray(rate * 4)
        var phase = 0.0
        var seed = 42UL
        for (i in harmonic.indices) {
            val time = i.toDouble() / rate
            phase += 2 * PI * (140 + 15 * sin(2 * PI * 5 * time)) / rate
            var voice = 0.0
            for (overtone in 1..20) voice += sin(overtone * phase) / overtone
            val envelope = maxOf(0.0, sin(2 * PI * 2.5 * time))
            seed = seed * 6364136223846793005UL + 1442695040888963407UL
            val noise = ((seed shr 33).toDouble() / (1L shl 31) - 0.5) * 0.02
            harmonic[i] = (0.15 * envelope * voice + noise).toFloat()
        }
        val enhanced = harmonic.copyOf().also { deepFilter(model, it, 1f) }
        fun correlation(lag: Int): Double {
            var product = 0.0; var inputPower = 0.0; var outputPower = 0.0
            for (i in rate until 3 * rate step 3) {
                val x = harmonic[i].toDouble(); val y = enhanced[i + lag].toDouble()
                product += x * y; inputPower += x * x; outputPower += y * y
            }
            return product / sqrt(inputPower * outputPower)
        }
        val coarse = (-1440..1440 step 32).maxByOrNull { correlation(it) }!!
        val lag = (coarse - 32..coarse + 32).maxByOrNull { correlation(it) }!!
        println("delay of the cleaned voice against the original: $lag samples, correlation ${correlation(lag)}")
        assertTrue("delayed by $lag samples", abs(lag) <= 1)
        assertTrue("correlation ${correlation(lag)}", correlation(lag) > 0.98)
        // The peak is not just a neighbouring pitch period.
        assertTrue(correlation(0) - maxOf(correlation(-340), correlation(340)) > 0.02)
    }

    /** As in the iPhone app's test: 12 347 samples, two channels with different sound, one model. */
    @Test fun channelsAreIndependentAndRepeatable() {
        val frames = 12_347
        val left = FloatArray(frames) { (sin(it * 0.033) * 0.3 + kotlin.math.cos(it * 0.117) * 0.13).toFloat() }
        val right = FloatArray(frames) { (sin(it * 0.027) * 0.25).toFloat() }
        val alone = listOf(left, right).map { channel -> channel.copyOf().also { deepFilter(model, it, 1f) } }
        assertTrue(alone[0].all { it.isFinite() })
        assertTrue(alone[0].zip(left).any { (wet, dry) -> wet != dry })

        // Both at once, and in the other order, on the same model.
        val together = listOf(right.copyOf(), left.copyOf())
        val threads = together.map { Thread { deepFilter(model, it, 1f) }.apply { start() } }
        threads.forEach { it.join() }
        assertArrayEquals(alone[0], together[1], 0f)
        assertArrayEquals(alone[1], together[0], 0f)

        val half = left.copyOf().also { deepFilter(model, it, 0.5f) }
        for (i in 0 until frames) assertEquals(left[i] + 0.5f * (alone[0][i] - left[i]), half[i], 0f)

        val untouched = left.copyOf().also { deepFilter(model, it, 0f) }
        assertArrayEquals(left, untouched, 0f)
    }

    /** One stream used again after [DeepFilter.reset] behaves like a new one. */
    @Test fun resetClearsEverythingTheNetworkRemembers() {
        val dry = pinkNoise(hop * 30 + 200, 0.05f).also { voice(it.size, 0.1f).forEachIndexed { i, v -> it[i] += v } }
        DeepFilter(model).use { stream ->
            val first = dry.copyOf().also { deepFilter(stream, it, 1f) }
            assertEquals(0, stream.flush().size)
            assertThrows(IllegalStateException::class.java) { stream.processHop(FloatArray(hop)) }
            stream.reset()
            val second = dry.copyOf().also { deepFilter(stream, it, 1f) }
            assertArrayEquals(first, second, 0f)
            val fresh = dry.copyOf().also { deepFilter(model, it, 1f) }
            assertArrayEquals(first, fresh, 0f)
        }
    }

    @Test fun streamingMatchesTheWholeChannelBitForBit() {
        val random = Random(7)
        for (length in listOf(1, 479, 480, 481, 2000, 48_000 + 123)) {
            val input = FloatArray(length) { (random.nextGaussian() * 0.05).toFloat() + 0.2f * sin(it * 0.03f) }
            for (amount in listOf(1f, 0.5f, 0f)) {
                val whole = input.copyOf().also { deepFilter(model, it, amount) }
                val streamed = stream(ChannelDeepFilter(model, amount), input, random)
                assertArrayEquals("length $length amount $amount", whole, streamed, 0f)
            }
        }
    }

    @Test fun modelLoadsFromAStreamAndRefusesUseAfterClosing() {
        val other = path.inputStream().use { DeepFilterModel(it) }
        val dry = pinkNoise(hop * 10, 0.05f)
        val expected = dry.copyOf().also { deepFilter(model, it, 1f) }
        val actual = dry.copyOf().also { deepFilter(other, it, 1f) }
        assertArrayEquals(expected, actual, 0f)
        other.close()
        other.close()
        assertThrows(IllegalStateException::class.java) { DeepFilter(other) }
    }

    /** The weights the build packs beside the library's classes, as the app loads them ([DeepFilterModel.bundled]). */
    @Test fun buildCarriesTheModel() {
        val found = DeepFilterModel.bundled()
        assertNotNull("the build packs app/takes/deepfilter/deepfilter.onnx", found)
        val carried = found!!
        val dry = pinkNoise(hop * 10, 0.05f)
        val expected = dry.copyOf().also { deepFilter(model, it, 1f) }
        val actual = dry.copyOf().also { deepFilter(carried, it, 1f) }
        assertArrayEquals(expected, actual, 0f)
        carried.close()
    }

    companion object {
        private val path = File(System.getProperty("takes.deepfilter.model") ?: "models/deepfilter.onnx")
        private val model by lazy { DeepFilterModel(path) }
    }
}
