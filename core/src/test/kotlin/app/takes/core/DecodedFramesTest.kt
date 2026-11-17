package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * A decoded file read at the engine's rate, as the app's reader of sound files
 * reads it ([DecodedFrames]): a decoder that, as Android's does, says the
 * file's rate only with its first block, from a sync frame a little before
 * where the reading starts. The mix asks for its frames a block at a time
 * (TrackMixEngine); every frame must be the file's sound at its time.
 *
 * The port's own arithmetic: the original reads files through AVFoundation,
 * which hands its mix the sound already at the mix's rate.
 */
class DecodedFramesTest {
    /** A slow sound, so the linear interpolation between frames is close to it. */
    private fun sound(time: Double, channel: Int = 0) = 0.5 * sin(2 * PI * 50 * time + channel) + 0.3 * sin(2 * PI * 3 * time)

    /**
     * Reads [seconds] from second [from] at [rate] in [channels], blocks of
     * [block] frames, from a file at [sourceRate] in [sourceChannels] decoded in
     * AAC's 1024 frames from the frame before [lead] seconds earlier.
     */
    private fun read(from: Double, seconds: Double, rate: Int, channels: Int, sourceRate: Int, sourceChannels: Int, block: Int = 1024, lead: Double = 0.2): FloatArray {
        val frames = DecodedFrames(rate, channels)
        var next = Math.floorDiv(Math.round((from - lead) * sourceRate), 1024L) * 1024
        val samples = FloatArray(1024 * sourceChannels)
        val decode = {
            frames.format(sourceRate, sourceChannels)
            for (frame in 0 until 1024) for (c in 0 until sourceChannels) samples[frame * sourceChannels + c] = sound((next + frame).toDouble() / sourceRate, c).toFloat()
            frames.add(samples, 1024, next.toDouble() / sourceRate)
            next += 1024
            true
        }
        val first = Math.round(from * rate)
        val total = (seconds * rate).toInt()
        val into = FloatArray(total * channels)
        var done = 0
        while (done < total) {
            val count = minOf(block, total - done)
            frames.read(first + done, count, into, done, decode)
            done += count
        }
        return into
    }

    private fun assertSound(name: String, from: Double, rate: Int, channels: Int, into: FloatArray, expected: (Double, Int) -> Double) {
        val first = Math.round(from * rate)
        for (frame in 0 until into.size / channels) for (c in 0 until channels) {
            val time = (first + frame).toDouble() / rate
            val want = expected(time, c)
            val got = into[frame * channels + c]
            assertTrue("$name: frame at %.5f s, channel $c, is %.4f, not %.4f".format(time, got, want), abs(got - want) < 2e-3)
        }
    }

    @Test
    fun aFileAt441kHzReadFromPartwayIsItsSoundThroughout() {
        // The places of the report: a sound split at 7.033 s and one trimmed to begin at 5.833 s, both of which fell silent.
        for (from in listOf(0.0, 0.5, 3.21, 5.833, 7.033, 12.4)) {
            val read = read(from, 1.0, 48_000, 2, 44_100, 1)
            assertSound("from $from", from, 48_000, 2, read) { time, _ -> sound(time) }
        }
    }

    @Test
    fun aFileAtAnotherRateFromPartwayInBlocksOfAnySize() {
        for (block in listOf(1, 480, 1024, 4096)) {
            assertSound("22.05 kHz in blocks of $block", 9.9, 48_000, 2, read(9.9, 0.5, 48_000, 2, 22_050, 2, block)) { time, c -> sound(time, c) }
            assertSound("96 kHz in blocks of $block", 9.9, 48_000, 2, read(9.9, 0.5, 48_000, 2, 96_000, 2, block)) { time, c -> sound(time, c) }
        }
    }

    @Test
    fun aFileAtTheEnginesRateIsReadFrameForFrame() {
        val read = read(7.033, 0.5, 48_000, 2, 48_000, 2)
        val first = Math.round(7.033 * 48_000)
        for (frame in 0 until read.size / 2) for (c in 0 until 2) {
            assertEquals(sound((first + frame).toDouble() / 48_000, c).toFloat(), read[frame * 2 + c], 0f)
        }
    }

    @Test
    fun channelsAreGivenToBothOrAveraged() {
        assertSound("mono to stereo", 2.0, 48_000, 2, read(2.0, 0.2, 48_000, 2, 44_100, 1)) { time, _ -> sound(time) }
        assertSound("stereo to mono", 2.0, 16_000, 1, read(2.0, 0.2, 16_000, 1, 44_100, 2)) { time, _ -> (sound(time, 0) + sound(time, 1)) / 2 }
    }

    @Test
    fun whatIsNotDecodedIsSilence() {
        val frames = DecodedFrames(48_000, 2)
        val into = FloatArray(200) { 1f }
        frames.read(0, 100, into, 0) { false }
        assertTrue(into.all { it == 0f })
        // A sound that begins at second 1: what is asked of before it is silence.
        frames.format(44_100, 1)
        frames.add(FloatArray(1024) { 0.5f }, 1024, 1.0)
        val before = FloatArray(200) { 1f }
        frames.read(47_000, 100, before, 0) { false }
        assertTrue(before.all { it == 0f })
    }
}
