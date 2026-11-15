package app.takes.deepfilter

/**
 * Cleans one channel of 48 kHz sound as it arrives, so a long recording never has to be
 * held in memory. It gives back exactly as many samples as it was given, aligned with the
 * input, and produces the same result as [deepFilter] on the whole channel.
 *
 * Samples are normalized floats. One instance per channel per recording; feed it in
 * order, call [finish] once, then [close]. The cleaned blocks come about 30 ms after the
 * samples they belong to, and the last of them from [finish].
 */
class ChannelDeepFilter internal constructor(private val stream: DeepFilter?, private val amount: Float) : AutoCloseable {
    /** At an [amount] of 0 the model is not run and the samples pass through unchanged. */
    constructor(model: DeepFilterModel, amount: Float) : this(open(model, amount), amount)

    private val hop = FloatArray(DeepFilter.HOP_SIZE)
    private var filled = 0

    // The originals still waiting for their cleaned sample: at most the three hops the
    // network holds back and the one being filled.
    private val dry = FloatArray(4 * DeepFilter.HOP_SIZE)
    private var dryStart = 0
    private var held = 0
    private val mixed = FloatArray(DeepFilter.HOP_SIZE)

    init { checkAmount(amount) }

    /** Takes [count] samples from [samples]. [output] receives cleaned blocks: a buffer to copy from, and how many samples of it are valid. */
    fun write(samples: FloatArray, offset: Int, count: Int, output: (FloatArray, Int) -> Unit) {
        var read = offset
        val end = offset + count
        while (read < end) {
            val taken = minOf(end - read, hop.size - filled)
            if (stream == null) {
                System.arraycopy(samples, read, mixed, 0, taken)
                output(mixed, taken)
                read += taken
                continue
            }
            for (index in 0 until taken) {
                val sample = samples[read + index]
                hop[filled + index] = sample
                dry[(dryStart + held + index) % dry.size] = sample
            }
            held += taken
            filled += taken
            read += taken
            if (filled == hop.size) {
                filled = 0
                emit(stream.processHop(hop), output)
            }
        }
    }

    /** Flushes what is left. Nothing may be written afterwards. */
    fun finish(output: (FloatArray, Int) -> Unit) {
        if (stream == null) return
        if (filled > 0) {
            hop.fill(0f, filled, hop.size) // the last hop is padded with silence
            filled = 0
            emit(stream.processHop(hop), output)
        }
        emit(stream.flush(), output)
        check(held == 0) { "DeepFilterNet3 left $held samples unreturned" }
    }

    // Cleaned samples beyond the originals belong to the padding and are dropped.
    private fun emit(wet: FloatArray, output: (FloatArray, Int) -> Unit) {
        var done = 0
        val total = minOf(wet.size, held)
        while (done < total) {
            val count = minOf(total - done, mixed.size)
            for (index in 0 until count) {
                val value = mix(dry[(dryStart + index) % dry.size], wet[done + index], amount)
                check(value.isFinite()) { "DeepFilterNet3 produced a sample that is not a number" }
                mixed[index] = value
            }
            dryStart = (dryStart + count) % dry.size
            held -= count
            done += count
            output(mixed, count)
        }
    }

    override fun close() { stream?.close() }

    private companion object {
        fun open(model: DeepFilterModel, amount: Float): DeepFilter? {
            checkAmount(amount)
            return if (amount == 0f) null else DeepFilter(model)
        }
    }
}
