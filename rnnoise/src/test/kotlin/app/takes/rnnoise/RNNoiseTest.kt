package app.takes.rnnoise

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Runs the real C library, built for this computer, through the same JNI bridge and
 * Kotlin code the app uses. The signals are synthetic; the printed levels are what was
 * measured.
 */
class RNNoiseTest {
    private val rate = RNNoise.SAMPLE_RATE
    private val frame = RNNoise.FRAME_SIZE

    private fun whiteNoise(count: Int, level: Float, seed: Long = 1): FloatArray {
        val random = Random(seed)
        return FloatArray(count) { (random.nextGaussian() * level).toFloat() }
    }

    /**
     * Noise that falls 3 dB per octave, as the hiss of a room, a fan or a microphone
     * does. Paul Kellet's filter of white noise.
     */
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
     * 130 Hz, loudest near the formants of "ah" (700, 1200 and 2600 Hz), in four syllables
     * a second. With [pauses], every second half second is silent.
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

    /** How much of [reference] is in [signal], as a gain, and the level of everything else. */
    private fun split(signal: FloatArray, reference: FloatArray, from: Int): Pair<Double, Double> {
        var cross = 0.0; var power = 0.0
        for (i in from until signal.size) { cross += signal[i].toDouble() * reference[i]; power += reference[i].toDouble() * reference[i] }
        val gain = cross / power
        var rest = 0.0
        for (i in from until signal.size) rest += (signal[i] - gain * reference[i]).let { it * it }
        return gain to sqrt(rest / (signal.size - from))
    }

    @Test fun silenceStaysSilent() {
        val samples = FloatArray(rate + 123)
        denoise(samples, 1f)
        val peak = samples.maxOf { abs(it) }
        println("silence: output peak $peak")
        assertEquals(0f, peak, 1e-7f)
    }

    @Test fun steadyNoiseIsReduced() {
        val dry = pinkNoise(rate * 4, 0.02f)
        val wet = dry.copyOf()
        denoise(wet, 1f)
        // The first second is left out: the network needs a moment to learn the noise.
        val before = rms(dry, rate); val after = rms(wet, rate)
        val change = db(after.toDouble() / before)
        println("pink noise: rms $before -> $after, %.1f dB".format(change))
        assertTrue("noise only fell by $change dB", change < -10)
    }

    /**
     * Noise that is equally loud at every pitch up to 24 kHz is not something this 2017
     * network was taught: it removes little of it, and how much varies from run to run of
     * the noise. Measured here so the number is known; only "not louder" is promised.
     */
    @Test fun whiteNoiseIsNotMadeLouder() {
        val dry = whiteNoise(rate * 4, 0.05f)
        val wet = dry.copyOf()
        denoise(wet, 1f)
        val before = rms(dry, rate); val after = rms(wet, rate)
        println("white noise: rms $before -> $after, %.1f dB".format(db(after.toDouble() / before)))
        assertTrue(after < before)
    }

    @Test fun voiceSurvivesBetterThanNoise() {
        val clean = voice(rate * 6, 0.1f, pauses = true)
        val hiss = pinkNoise(clean.size, 0.01f, seed = 2)
        val wet = FloatArray(clean.size) { clean[it] + hiss[it] }
        denoise(wet, 1f)
        // The noise is unrelated to the voice, so the part of the output that follows the
        // clean voice is the voice that was kept.
        val (voiceGain, rest) = split(wet, clean, rate)
        val voiceDb = db(voiceGain)
        // In the pauses there is only noise, so what is left there is the noise that was kept.
        var before = 0.0; var after = 0.0
        for (i in rate until clean.size) if (i % rate >= rate * 6 / 10) { before += hiss[i].toDouble() * hiss[i]; after += wet[i].toDouble() * wet[i] }
        val pauseDb = db(sqrt(after / before))
        println("voice + pink noise: voice %.1f dB, noise in the pauses %.1f dB".format(voiceDb, pauseDb))
        println("voice + pink noise: all that is not the clean voice, %.1f dB relative to the noise put in".format(db(rest / rms(hiss, rate))))
        assertTrue("voice fell by $voiceDb dB", voiceDb > -3)
        assertTrue("noise only fell by $pauseDb dB", pauseDb < -10)
    }

    @Test fun speechProbabilityTellsVoiceFromNoise() {
        fun probability(samples: FloatArray): Float = RNNoise().use { rnnoise ->
            val out = FloatArray(frame)
            var sum = 0f; var frames = 0
            for (start in rate until samples.size - frame + 1 step frame) { sum += rnnoise.process(samples, start, out, 0); frames++ }
            sum / frames
        }
        val voiced = probability(voice(rate * 4, 0.1f)); val hiss = probability(pinkNoise(rate * 4, 0.02f))
        println("speech probability: voice $voiced, pink noise $hiss")
        assertTrue(voiced in 0f..1f && hiss in 0f..1f)
        assertTrue(voiced > hiss + 0.3f)
    }

    @Test fun zeroAmountLeavesTheAudioUntouched() {
        val dry = whiteNoise(1000, 0.05f)
        val samples = dry.copyOf()
        var reported = -1f
        denoise(samples, 0f) { reported = it }
        assertArrayEquals(dry, samples, 0f)
        assertEquals(1f, reported, 0f)
        assertThrows(IllegalArgumentException::class.java) { denoise(samples, 1.5f) }
        assertThrows(IllegalArgumentException::class.java) { denoise(samples, Float.NaN) }
    }

    /** The helper against the plain library, frame by frame, for lengths around a frame. */
    @Test fun anyLengthIsPaddedAndAlignedWithTheOriginal() {
        for (length in listOf(0, 1, 479, 480, 481, 960, 1000, 4800 + 37)) {
            val dry = voice(maxOf(length, 1), 0.1f).copyOf(length)
            // Reference: pad to whole frames, add the silent frame that pushes out the
            // end, and drop the first frame that comes out, which is the delay.
            val frames = (length + frame - 1) / frame
            val padded = dry.copyOf((frames + 1) * frame)
            val expected = FloatArray(padded.size)
            RNNoise().use { for (start in padded.indices step frame) it.process(padded, start, expected, start) }

            val full = dry.copyOf()
            val steps = ArrayList<Float>()
            denoise(full, 1f) { steps += it }
            // Mixed the way the iPhone app mixes, which even at 1 is not exactly the cleaned sample.
            val mixed = FloatArray(length) { dry[it] + 1f * (expected[frame + it] - dry[it]) }
            assertArrayEquals("length $length", mixed, full, 0f)
            assertEquals(steps, steps.sorted())
            assertEquals(1f, steps.last(), 0f)

            val half = dry.copyOf()
            denoise(half, 0.5f)
            for (i in 0 until length) assertEquals(dry[i] + 0.5f * (full[i] - dry[i]), half[i], 1e-6f)
        }
    }

    /** With the delay removed, the cleaned voice lines up with the original, not 10 ms later. */
    @Test fun outputIsNotDelayed() {
        val clean = voice(rate * 2, 0.1f)
        val wet = clean.copyOf()
        denoise(wet, 1f)
        fun match(lag: Int): Double {
            var sum = 0.0
            for (i in rate until clean.size - frame) sum += wet[i + lag].toDouble() * clean[i]
            return sum
        }
        val best = (-frame..frame).maxByOrNull { match(it) }
        println("delay of the cleaned voice against the original: $best samples")
        assertEquals(0, best)
    }

    @Test fun statesAreIndependentAndRepeatable() {
        val samples = pinkNoise(frame * 20, 0.05f).also { voice(it.size, 0.1f).forEachIndexed { i, v -> it[i] += v } }
        val a = samples.copyOf(); val b = samples.copyOf()
        // Two channels being cleaned at once must not disturb each other.
        val threads = listOf(a, b).map { Thread { denoise(it, 1f) }.apply { start() } }
        threads.forEach { it.join() }
        assertArrayEquals(a, b, 0f)
    }

    @Test fun frameBoundsAndClosing() {
        val rnnoise = RNNoise()
        assertThrows(IllegalArgumentException::class.java) { rnnoise.process(FloatArray(frame - 1)) }
        assertThrows(IllegalArgumentException::class.java) { rnnoise.process(FloatArray(frame), 1) }
        assertThrows(IllegalArgumentException::class.java) { rnnoise.process(FloatArray(frame), 0, FloatArray(frame), -1) }
        val probability = rnnoise.process(FloatArray(frame))
        assertTrue(probability in 0f..1f)
        rnnoise.close()
        rnnoise.close()
        assertThrows(IllegalStateException::class.java) { rnnoise.process(FloatArray(frame)) }
    }

    /** The network is the one the iPhone app names in `AudioModel.rnnoise` (YouRec/Project.swift). */
    @Test fun weightsAreTheOnesTheIPhoneAppShips() {
        val bytes = File("src/main/cpp/rnnoise/src/rnn_data.c").readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("f0cdb52b30501aab489f90fedbc7a023c719d91b337db2da7f26fc3036556b95", hash)
    }

    @Test fun streamingMatchesTheWholeChannelBitForBit() {
        val random = java.util.Random(7)
        for (length in listOf(0, 1, 479, 480, 481, 2000, 48_000 + 123)) {
            val input = FloatArray(length) { (random.nextGaussian() * 0.05).toFloat() + 0.2f * kotlin.math.sin(it * 0.03).toFloat() }
            for (amount in listOf(1f, 0.5f)) {
                val whole = input.copyOf().also { denoise(it, amount) }
                val streamed = FloatArray(length)
                var written = 0
                val collect = { block: FloatArray, count: Int -> System.arraycopy(block, 0, streamed, written, count); written += count }
                ChannelDenoiser(amount).use { channel ->
                    // Uneven chunks, as a decoder would deliver them.
                    var offset = 0
                    while (offset < length) {
                        val chunk = minOf(length - offset, 1 + random.nextInt(1500))
                        channel.write(input, offset, chunk, collect)
                        offset += chunk
                    }
                    channel.finish(collect)
                }
                org.junit.Assert.assertEquals("length $length", length, written)
                org.junit.Assert.assertArrayEquals("length $length amount $amount", whole, streamed, 0f)
            }
        }
    }
}
