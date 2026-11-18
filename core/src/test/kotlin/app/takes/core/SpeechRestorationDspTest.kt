package app.takes.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The signal processing of the two speech restoration stages, without their networks: the transform, LavaSR's
 * frontend, reconstruction and crossover (ThirdParty/LavaSR/LavaDSP.cpp), VoiceFixer's frontend
 * (ThirdParty/VoiceFixer/VoiceFixerDSP.cpp) and the rate conversion Studio restoration needs.
 */
class SpeechRestorationDspTest {
    private fun sine(hz: Double, count: Int, rate: Int = 48000, level: Double = 0.1) =
        FloatArray(count) { (level * sin(2 * PI * hz * it / rate)).toFloat() }

    /** How much of [hz] is in [x] from [from] to [to], as an amplitude. */
    private fun amplitude(x: FloatArray, hz: Double, from: Int, to: Int, rate: Int = 48000): Double {
        var re = 0.0; var im = 0.0
        for (i in from until to) { val a = 2 * PI * hz * i / rate; re += x[i] * cos(a); im += x[i] * sin(a) }
        return hypot(re, im) * 2 / (to - from)
    }

    // The transform.

    @Test fun transformMatchesTheDefinitionAndGoesBack() {
        val random = Random(7)
        val size = 64
        val real = FloatArray(size) { random.nextFloat() - 0.5f }
        val imaginary = FloatArray(size) { random.nextFloat() - 0.5f }
        val (re0, im0) = real.copyOf() to imaginary.copyOf()
        Fft.of(size).transform(real, imaginary)
        for (k in 0 until size) {
            var re = 0.0; var im = 0.0
            for (t in 0 until size) {
                val a = -2 * PI * k * t / size
                re += re0[t] * cos(a) - im0[t] * sin(a); im += re0[t] * sin(a) + im0[t] * cos(a)
            }
            assertEquals(re, real[k].toDouble(), 1e-5); assertEquals(im, imaginary[k].toDouble(), 1e-5)
        }
        Fft.of(size).transform(real, imaginary, inverse = true)
        for (t in 0 until size) { assertEquals(re0[t], real[t], 1e-6f); assertEquals(im0[t], imaginary[t], 1e-6f) }
    }

    // LavaSR.

    @Test fun lavaFeaturesOfSilenceAreTheFloor() {
        val mel = FloatArray(LavaDsp.MELS * LavaDsp.FRAMES)
        LavaDsp.features(FloatArray(LavaDsp.LENGTH), mel)
        assertTrue(mel.all { it == ln(1e-5f) })
    }

    @Test fun lavaFeaturesFindATone() {
        // A 1 kHz tone is loudest in one band near the middle of the 0–8 kHz bank, the same in every frame.
        val mel = FloatArray(LavaDsp.MELS * LavaDsp.FRAMES)
        LavaDsp.features(sine(1000.0, LavaDsp.LENGTH), mel)
        val loudest = (0 until LavaDsp.FRAMES step 37).map { frame -> (0 until LavaDsp.MELS).maxBy { mel[it * LavaDsp.FRAMES + frame] } }.toSet()
        assertEquals(1, loudest.size)
        // The bank is for 44.1 kHz, as the checkpoint's: at 48 kHz the tone sits where 1000 × 44100/48000 Hz would.
        val band = loudest.single()
        assertTrue("band $band", band in 20..35)
        // The bottom band hears none of it: the floor of the log.
        assertTrue(mel[0 * LavaDsp.FRAMES + 100] < mel[band * LavaDsp.FRAMES + 100] - 5)
    }

    /** The STFT [LavaDsp.reconstruct] inverts: periodic Hann, 2048 points, hop 512, reflected "same" padding. */
    private fun spectrum(audio: FloatArray): Pair<FloatArray, FloatArray> {
        val real = FloatArray(LavaDsp.BINS * LavaDsp.FRAMES)
        val imaginary = FloatArray(LavaDsp.BINS * LavaDsp.FRAMES)
        val re = FloatArray(LavaDsp.FFT_SIZE); val im = FloatArray(LavaDsp.FFT_SIZE)
        val padding = (LavaDsp.FFT_SIZE - LavaDsp.HOP) / 2
        for (frame in 0 until LavaDsp.FRAMES) {
            for (i in 0 until LavaDsp.FFT_SIZE) {
                var at = frame * LavaDsp.HOP + i - padding
                if (at < 0) at = -at
                if (at >= LavaDsp.LENGTH) at = 2 * LavaDsp.LENGTH - 2 - at
                re[i] = (audio[at] * 0.5 * (1 - cos(2 * PI * i / LavaDsp.FFT_SIZE))).toFloat(); im[i] = 0f
            }
            Fft.of(LavaDsp.FFT_SIZE).transform(re, im)
            for (f in 0 until LavaDsp.BINS) { real[f * LavaDsp.FRAMES + frame] = re[f]; imaginary[f * LavaDsp.FRAMES + frame] = im[f] }
        }
        return real to imaginary
    }

    @Test fun lavaReconstructionInvertsTheTransform() {
        val random = Random(3)
        val audio = FloatArray(LavaDsp.LENGTH) { (random.nextFloat() - 0.5f) * 0.2f }
        val (real, imaginary) = spectrum(audio)
        val back = FloatArray(LavaDsp.LENGTH)
        LavaDsp.reconstruct(real, imaginary, back)
        var error = 0f
        for (i in audio.indices) error = max(error, abs(back[i] - audio[i]))
        assertTrue("error $error", error < 1e-5f)
    }

    @Test fun lavaMergeKeepsTheOriginalBelowFourKilohertz() {
        val original = sine(1000.0, LavaDsp.LENGTH)
        val output = FloatArray(LavaDsp.LENGTH)
        // A restoration that lost the low band and added 10 kHz: only the 10 kHz is taken.
        val restored = sine(10_000.0, LavaDsp.LENGTH, level = 0.05)
        LavaDsp.merge(original, restored, output)
        assertEquals(0.1, amplitude(output, 1000.0, 48000, 144000), 1e-3)
        assertEquals(0.05, amplitude(output, 10_000.0, 48000, 144000), 1e-3)
        // The same restoration as the original changes nothing.
        LavaDsp.merge(original, original, output)
        assertArrayEquals(original, output, 1e-7f)
        assertEquals(0f, LavaDsp.crossover(4000f)); assertEquals(0.5f, LavaDsp.crossover(5000f)); assertEquals(1f, LavaDsp.crossover(6000f))
    }

    // VoiceFixer.

    @Test fun voiceFixerFeaturesAreTheMagnitudesThroughTheBank() {
        val random = Random(11)
        val audio = FloatArray(VoiceFixerDsp.CONTEXT) { (random.nextFloat() - 0.5f) * 0.3f }
        // Band m takes bin 7m, and band 127 the sum of bins 0 and 1.
        val bank = FloatArray(VoiceFixerDsp.BINS * VoiceFixerDsp.MELS)
        for (m in 0 until 127) bank[7 * m * VoiceFixerDsp.MELS + m] = 1f
        bank[0 * VoiceFixerDsp.MELS + 127] = 1f; bank[1 * VoiceFixerDsp.MELS + 127] = 1f
        val mel = FloatArray(VoiceFixerDsp.FRAMES * VoiceFixerDsp.MELS)
        VoiceFixerDsp.features(audio, bank, mel)
        // Frame 0 and 100 by the definition: centred, reflected, periodic Hann, |X| floored at 1e-4.
        for (frame in listOf(0, 100, 255)) {
            fun magnitude(bin: Int): Double {
                var re = 0.0; var im = 0.0
                for (i in 0 until 2048) {
                    var j = frame * 441 + i - 1024
                    if (j < 0) j = -j
                    if (j >= VoiceFixerDsp.CONTEXT) j = 2 * VoiceFixerDsp.CONTEXT - 2 - j
                    val x = audio[j] * 0.5 * (1 - cos(2 * PI * i / 2048))
                    re += x * cos(2 * PI * bin * i / 2048); im -= x * sin(2 * PI * bin * i / 2048)
                }
                return max(1e-4, sqrt(re * re + im * im))
            }
            for (m in listOf(0, 3, 50, 126)) assertEquals(magnitude(7 * m), mel[frame * 128 + m].toDouble(), 2e-4)
            assertEquals(magnitude(0) + magnitude(1), mel[frame * 128 + 127].toDouble(), 2e-4)
        }
    }

    @Test fun voiceFixerBankIsReadFromItsNumpyFile() {
        val header = "{'descr': '<f4', 'fortran_order': False, 'shape': (1025, 128), }".padEnd(117) + "\n"
        val values = java.nio.ByteBuffer.allocate(1025 * 128 * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 1025 * 128) values.putFloat(i * 0.5f)
        val file = byteArrayOf(0x93.toByte()) + "NUMPY".toByteArray() + byteArrayOf(1, 0, 118, 0) + header.toByteArray() + values.array()
        assertEquals(524_928, file.size) // the size of the published mel_filters.npy
        val bank = VoiceFixerDsp.bank(file)!!
        assertEquals(0f, bank[0]); assertEquals(64f, bank[128]); assertEquals((1025 * 128 - 1) * 0.5f, bank.last())
        assertNull(VoiceFixerDsp.bank(file.copyOf(file.size - 4)))
        assertNull(VoiceFixerDsp.bank(file.copyOf().also { it[30] = 'x'.code.toByte() }))
    }

    // Rate conversion.

    private fun resample(input: FloatArray, from: Int, to: Int, block: Int = 1000): FloatArray {
        val resampler = Resampler(from, to)
        val out = ArrayList<Float>()
        var at = 0
        while (at < input.size) {
            val n = minOf(block, input.size - at)
            resampler.write(input, at, n) { b, c -> for (i in 0 until c) out += b[i] }
            at += n
        }
        resampler.finish { b, c -> for (i in 0 until c) out += b[i] }
        return out.toFloatArray()
    }

    @Test fun resamplingGivesTheExpectedLength() {
        for (count in listOf(0, 1, 17, 160, 161, 48_000, 100_003)) {
            val down = resample(FloatArray(count) { 0.01f }, 48000, 44100)
            assertEquals((count * 147L + 159) / 160, down.size.toLong())
            val up = resample(down, 44100, 48000)
            assertTrue("$count → ${up.size}", up.size - count in 0..1)
        }
    }

    @Test fun resamplingKeepsSpeechBandsAndTiming() {
        val tone = FloatArray(96_000) { (0.1 * sin(2 * PI * 1000 * it / 48000) + 0.05 * sin(2 * PI * 7000 * it / 48000)).toFloat() }
        val down = resample(tone, 48000, 44100, block = 4096)
        // At 44.1 kHz the same tones, in phase with the input's clock: no delay.
        for (i in 2000 until 86000) {
            val t = i / 44100.0
            assertEquals(0.1 * sin(2 * PI * 1000 * t) + 0.05 * sin(2 * PI * 7000 * t), down[i].toDouble(), 2e-4)
        }
        val back = resample(down, 44100, 48000, block = 333)
        for (i in 2000 until 94000) assertEquals(tone[i], back[i], 3e-4f)
        // A constant passes unchanged away from the ends.
        val level = resample(FloatArray(10_000) { 0.25f }, 48000, 44100)
        for (i in 200 until level.size - 200) assertEquals(0.25f, level[i], 1e-5f)
    }

    @Test fun downsamplingRemovesWhatTheLowerRateCannotHold() {
        // 23 kHz at 48 kHz would fold to 21.1 kHz at 44.1 kHz.
        val down = resample(sine(23_000.0, 48_000), 48000, 44100)
        assertTrue(amplitude(down, 21_100.0, 2000, 40000, 44100) < 0.1 * 1e-3)
    }

    @Test fun resamplingDoesNotDependOnTheBlocks() {
        val random = Random(5)
        val input = FloatArray(30_000) { random.nextFloat() - 0.5f }
        val whole = resample(input, 48000, 44100, block = input.size)
        for (block in listOf(1, 7, 4096)) assertArrayEquals(whole, resample(input, 48000, 44100, block), 0f)
    }
}
