package app.takes.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * How the two speech restoration stages cut a recording into windows, put the results back together and mix
 * them (YouRec/StudioVoiceProcessor.swift, YouRec/StudioRestorationProcessor.swift), with stand-in networks.
 */
class SpeechRestorationTest {
    /** [channel] fed [input] in blocks of [block], everything it gives back. */
    private fun run(input: FloatArray, block: Int, write: (FloatArray, Int, Int, (FloatArray, Int) -> Unit) -> Unit, finish: ((FloatArray, Int) -> Unit) -> Unit): FloatArray {
        val out = ArrayList<Float>(input.size)
        val take = { b: FloatArray, c: Int -> for (i in 0 until c) out += b[i] }
        var at = 0
        while (at < input.size) {
            val n = minOf(block, input.size - at)
            write(input, at, n, take)
            at += n
        }
        finish(take)
        return out.toFloatArray()
    }

    private fun lava(input: FloatArray, amount: Float, network: SpeechDetailNetwork, block: Int = 4096, twin: RestorationTwin? = null, store: RestorationWindowStore? = null): FloatArray {
        val channel = SpeechDetailChannel(network, amount, twin, store)
        return run(input, block, channel::write, channel::finish)
    }

    private fun studio(input: FloatArray, amount: Float, network: StudioRestorationNetwork, block: Int = 4096, twin: RestorationTwin? = null, store: RestorationWindowStore? = null): FloatArray {
        val channel = StudioRestorationChannel(network, FloatArray(VoiceFixerDsp.BINS * VoiceFixerDsp.MELS), amount, twin, store)
        return run(input, block, channel::write, channel::finish)
    }

    private fun speechLike(count: Int, seed: Int = 1): FloatArray {
        val random = Random(seed)
        return FloatArray(count) { (0.1 * sin(2 * PI * 1000 * it / 48000) + 0.03 * sin(2 * PI * 9000 * it / 48000) + 0.01 * (random.nextDouble() - 0.5)).toFloat() }
    }

    private fun amplitude(x: FloatArray, hz: Double, from: Int, to: Int): Double {
        var re = 0.0; var im = 0.0
        for (i in from until to) { val a = 2 * PI * hz * i / 48000; re += x[i] * cos(a); im += x[i] * sin(a) }
        return hypot(re, im) * 2 / (to - from)
    }

    private class MemoryStore : RestorationWindowStore {
        val windows = HashMap<Long, FloatArray>()
        override fun load(position: Long, count: Int) = windows[position]?.takeIf { it.size == count }
        override fun save(position: Long, samples: FloatArray, count: Int) { windows[position] = samples.copyOf(count) }
    }

    // Windows.

    @Test fun finalWindowReflectsTheWholeRecording() {
        // Tests/DeepFilterTests.swift:270-271: a partial final hop reads samples before its ordinary left margin.
        val indices = ReflectedWindows.sourceIndices(52920, 57330, StudioRestorationChannel.CONTEXT, StudioRestorationChannel.MARGIN)
        assertEquals(22492L, indices.last()); assertEquals(0L, indices.min()); assertEquals(57329L, indices.max())
        assertEquals(0L, ReflectedWindows.reflect(-5, 1))
        assertEquals(5L, ReflectedWindows.reflect(-5, 100)); assertEquals(97L, ReflectedWindows.reflect(101, 100))
        // A very short recording bounces: 0 1 2 1 0 1 2 …
        assertEquals(listOf(0L, 1L, 2L, 1L, 0L, 1L, 2L), (0L..6L).map { ReflectedWindows.reflect(it, 3) })
    }

    @Test fun streamedWindowsAreTheWholeRecordingsWindows() {
        for (length in listOf(1, 17, 1000, 9000, 25_000, 61_000)) {
            val input = FloatArray(length) { it.toFloat() }
            val windows = ReflectedWindows(context = 3000, margin = 700, stride = 1500, extra = 100)
            var expected = 0L
            val receiver = ReflectedWindows.Receiver { samples, position, count, valid ->
                assertEquals(expected, position)
                assertEquals(minOf(1500L, length - position).toInt(), count)
                assertEquals(minOf(1600L, length - position).toInt(), valid)
                val indices = ReflectedWindows.sourceIndices(position, length.toLong(), 3000, 700)
                for (i in 0 until 3000) assertEquals(indices[i].toFloat(), samples[i])
                expected += 1500
            }
            var at = 0
            val block = 777
            while (at < length) { val n = minOf(block, length - at); windows.write(input, at, n, receiver); at += n }
            windows.finish(receiver)
            assertEquals(((length + 1499) / 1500) * 1500L, expected)
        }
    }

    // Speech detail restoration.

    /** A network whose spectrum is silence: the restoration takes away what lies above 4–6 kHz. */
    private val silentSpectrum = SpeechDetailNetwork { _, real, imaginary -> real.fill(0f); imaginary.fill(0f) }

    @Test fun detailRestorationGivesBackEverySampleInPlace() {
        for (length in listOf(1, 17, 481, 159_232, 200_000, 500_001)) {
            val input = speechLike(length)
            // At amount 0 the windows are cut to their centres and put back: the recording exactly.
            assertArrayEquals("length $length", input, lava(input, 0f, silentSpectrum, block = 1 + length / 3), 0f)
        }
    }

    @Test fun detailRestorationReplacesOnlyTheUpperBand() {
        val input = speechLike(400_000)
        val output = lava(input, 1f, silentSpectrum)
        assertEquals(input.size, output.size)
        // The 1 kHz voice stays, 9 kHz goes, across window boundaries (159,232 and 318,464).
        for (from in listOf(10_000, 150_000, 300_000)) {
            assertEquals(0.1, amplitude(output, 1000.0, from, from + 48_000), 2e-3)
            assertTrue(amplitude(output, 9000.0, from, from + 48_000) < 1e-3)
        }
        // Amount is a wet/dry mix.
        val half = lava(input, 0.5f, silentSpectrum)
        for (i in input.indices step 97) assertEquals(input[i] + 0.5f * (output[i] - input[i]), half[i], 1e-6f)
    }

    @Test fun detailRestorationSharesWindowsBetweenEqualChannelsAndResumes() {
        var calls = 0
        val network = SpeechDetailNetwork { mel, real, imaginary -> calls++; silentSpectrum.spectrum(mel, real, imaginary) }
        val input = speechLike(400_000)
        val twin = RestorationTwin()
        val left = SpeechDetailChannel(network, 0.35f, twin)
        val right = SpeechDetailChannel(network, 0.35f, twin)
        val outLeft = ArrayList<Float>(); val outRight = ArrayList<Float>()
        var at = 0
        while (at < input.size) {
            val n = minOf(4096, input.size - at)
            left.write(input, at, n) { b, c -> for (i in 0 until c) outLeft += b[i] }
            right.write(input, at, n) { b, c -> for (i in 0 until c) outRight += b[i] }
            at += n
        }
        left.finish { b, c -> for (i in 0 until c) outLeft += b[i] }
        right.finish { b, c -> for (i in 0 until c) outRight += b[i] }
        assertEquals(3, calls) // one per window, not one per window per channel
        assertEquals(outLeft, outRight)

        // Windows kept by a run stopped half way are used, and give the same sound.
        val store = MemoryStore()
        calls = 0
        val first = lava(input, 0.35f, network, store = store)
        assertEquals(3, calls)
        store.windows.remove(store.windows.keys.max())
        calls = 0
        assertArrayEquals(first, lava(input, 0.35f, network, store = store), 0f)
        assertEquals(1, calls)
        assertArrayEquals(outLeft.toFloatArray(), first, 0f)
    }

    @Test fun detailRestorationLeavesSilenceAlone() {
        var calls = 0
        val network = SpeechDetailNetwork { _, _, _ -> calls++ }
        assertArrayEquals(FloatArray(300_000), lava(FloatArray(300_000), 1f, network), 0f)
        assertEquals(0, calls)
    }

    // Studio restoration.

    /** A vocoder that always says the same level. */
    private fun steady(level: Float, calls: IntArray = IntArray(1)) = StudioRestorationNetwork { _, audio -> calls[0]++; audio.fill(level) }

    @Test fun studioRestorationKeepsTheLengthOfEveryRecording() {
        // Tests/DeepFilterTests.swift:272-284: exact frame counts down to one.
        for (length in listOf(1, 17, 481, 19_201, 62_401, 250_000)) {
            val input = FloatArray(length) { if (length > 100) (0.02 * sin(it * 0.03)).toFloat() else 0f }
            val output = studio(input, 1f, steady(0.1f), block = 1 + length / 4)
            assertEquals(length, output.size)
            assertTrue(output.all { it.isFinite() })
        }
        // A silent channel stays silent: its windows are not restored (Tests/DeepFilterTests.swift:283).
        assertArrayEquals(FloatArray(70_000), studio(FloatArray(70_000), 1f, steady(0.1f)), 0f)
    }

    @Test fun studioRestorationMixesTheRestoredVoiceByAmount() {
        val input = speechLike(300_000)
        val full = studio(input, 1f, steady(0.1f))
        // The vocoder's level comes through the windows, their cross-fades and both rate changes.
        for (i in 1000 until input.size - 1000 step 13) assertEquals(0.1f, full[i], 2e-4f)
        // Tests/DeepFilterTests.swift:240-244: amount is an exact wet/dry mix.
        val half = studio(input, 0.5f, steady(0.1f))
        for (i in input.indices) assertEquals(input[i] + 0.5f * (full[i] - input[i]), half[i], 1e-5f)
    }

    @Test fun studioRestorationResumesWithTheSameSound() {
        val input = speechLike(300_000, seed = 9)
        var counter = 0f
        // A different level every window, so the cross-fades show.
        val calls = IntArray(1)
        val network = StudioRestorationNetwork { _, audio -> calls[0]++; counter += 0.01f; audio.fill(counter) }
        val store = MemoryStore()
        val first = studio(input, 1f, network, store = store)
        val windows = store.windows.size
        assertEquals(calls[0], windows)
        store.windows.remove(store.windows.keys.max())
        store.windows.remove(store.windows.keys.min())
        calls[0] = 0
        counter = 0f
        val again = studio(input, 1f, network, store = store)
        assertEquals(2, calls[0])
        // The two windows made again have new levels; everything between them is as before.
        val from = (StudioRestorationChannel.STRIDE + StudioRestorationChannel.OVERLAP) * 48_000L / 44_100 + 200
        val to = (windows - 2L) * StudioRestorationChannel.STRIDE * 48_000 / 44_100
        for (i in from until to) assertEquals(first[i.toInt()], again[i.toInt()], 0f)
        // And a cross-fade never jumps: consecutive samples stay close.
        for (i in 1000 until first.size - 1000) assertTrue(abs(first[i] - first[i - 1]) < 0.005f)
    }

    @Test fun studioRestorationSharesWindowsBetweenEqualChannels() {
        val calls = IntArray(1)
        val network = steady(0.2f, calls)
        val input = speechLike(200_000)
        val twin = RestorationTwin()
        val bank = FloatArray(VoiceFixerDsp.BINS * VoiceFixerDsp.MELS)
        val left = StudioRestorationChannel(network, bank, 1f, twin)
        val right = StudioRestorationChannel(network, bank, 1f, twin)
        var count = 0
        var at = 0
        while (at < input.size) {
            val n = minOf(4096, input.size - at)
            left.write(input, at, n) { _, c -> count += c }
            right.write(input, at, n) { _, c -> count += c }
            at += n
        }
        left.finish { _, c -> count += c }
        right.finish { _, c -> count += c }
        assertEquals(2 * input.size, count)
        val windows = (Resampler(48000, 44100).outputCount(input.size.toLong()) + StudioRestorationChannel.STRIDE - 1) / StudioRestorationChannel.STRIDE
        assertEquals(windows.toInt(), calls[0])
    }
}
