package app.takes.rnnoise

/**
 * Cleans one channel of 48 kHz sound as it arrives, so a long recording never
 * has to be held in memory. It gives back exactly as many samples as it was
 * given, aligned with the input, and produces the same result as [denoise] on
 * the whole channel.
 *
 * Samples are normalized floats. One instance per channel per recording; feed
 * it in order, call [finish] once, then [close].
 */
class ChannelDenoiser(private val amount: Float) : AutoCloseable {
    private val rnnoise = RNNoise()
    private val dry = FloatArray(RNNoise.FRAME_SIZE)
    private val wet = FloatArray(RNNoise.FRAME_SIZE)
    private val previous = FloatArray(RNNoise.FRAME_SIZE)
    private val mixed = FloatArray(RNNoise.FRAME_SIZE)
    private var filled = 0
    private var held = 0 // samples of [previous] still waiting for their cleaned frame

    init { require(amount in 0f..1f) { "amount must be between 0 and 1" } }

    /** Takes [count] samples from [samples]. [output] receives cleaned blocks: a buffer to copy from, and how many samples of it are valid. */
    fun write(samples: FloatArray, offset: Int, count: Int, output: (FloatArray, Int) -> Unit) {
        var read = offset
        val end = offset + count
        while (read < end) {
            val taken = minOf(end - read, RNNoise.FRAME_SIZE - filled)
            System.arraycopy(samples, read, dry, filled, taken)
            filled += taken
            read += taken
            if (filled == RNNoise.FRAME_SIZE) frame(RNNoise.FRAME_SIZE, output)
        }
    }

    /** Flushes what is left. Nothing may be written afterwards. */
    fun finish(output: (FloatArray, Int) -> Unit) {
        if (filled > 0) {
            val valid = filled
            dry.fill(0f, filled) // the last frame is padded with silence
            frame(valid, output)
        }
        if (held > 0) {
            dry.fill(0f) // one silent frame pushes the last real one out
            frame(0, output)
        }
    }

    // RNNoise answers one frame late: what it returns now is the previous frame, cleaned.
    private fun frame(valid: Int, output: (FloatArray, Int) -> Unit) {
        rnnoise.process(dry, 0, wet, 0)
        if (held > 0) {
            for (index in 0 until held) mixed[index] = previous[index] + amount * (wet[index] - previous[index])
            output(mixed, held)
        }
        System.arraycopy(dry, 0, previous, 0, RNNoise.FRAME_SIZE)
        held = valid
        filled = 0
    }

    override fun close() = rnnoise.close()
}
