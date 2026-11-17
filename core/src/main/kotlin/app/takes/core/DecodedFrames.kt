package app.takes.core

import kotlin.math.floor

/**
 * A file's sound as its decoder gives it, a block at a time at the file's own
 * rate and channels, read at the engine's [rate] and [channels]: what the
 * app's reader of sound files ([TrackMixSource]) holds between the decoder and
 * the mix. A frame of the engine is read at its time in the file, by linear
 * interpolation between the two decoded frames around it when the rates
 * differ; one channel is given to both, of more the first two are taken, and
 * for one channel all are averaged.
 *
 * The file's rate is known only once the decoder has given its format, which
 * happens in the middle of the first read, so every place is reckoned at the
 * rate known when it is used.
 */
class DecodedFrames(val rate: Int, val channels: Int) {
    /** The file's rate, 0 until the decoder says. */
    var sourceRate = 0
        private set
    private var sourceChannels = 0

    /** Decoded frames at the source's rate, already in the engine's channels, the first being source frame [base]. */
    private var held = FloatArray(0)
    private var size = 0
    private var base = Long.MIN_VALUE

    /** The decoder's format; the first one stands. */
    fun format(sourceRate: Int, sourceChannels: Int) {
        if (this.sourceRate != 0 || sourceRate <= 0 || sourceChannels <= 0) return
        this.sourceRate = sourceRate
        this.sourceChannels = sourceChannels
    }

    /**
     * A decoded block: [frames] frames of [samples], interleaved in the
     * source's channels. The first block says where the sound begins, at
     * second [time] of the file; the rest follow it frame by frame.
     */
    fun add(samples: FloatArray, frames: Int, time: Double) {
        if (sourceRate == 0 || frames <= 0) return
        if (base == Long.MIN_VALUE) base = Math.round(time * sourceRate)
        if (held.size < (size + frames) * channels) held = held.copyOf(maxOf(held.size * 2, (size + frames) * channels))
        var at = size * channels
        for (frame in 0 until frames) {
            val from = frame * sourceChannels
            when {
                channels == 1 -> { var sum = 0f; for (c in 0 until sourceChannels) sum += samples[from + c]; held[at++] = sum / sourceChannels }
                sourceChannels == 1 -> { val value = samples[from]; for (c in 0 until channels) held[at++] = value }
                else -> for (c in 0 until channels) held[at++] = samples[from + minOf(c, sourceChannels - 1)]
            }
        }
        size += frames
    }

    /** The place of engine frame [frame] among the source's frames. */
    private fun place(frame: Long): Double = if (sourceRate == rate) frame.toDouble() else frame.toDouble() * sourceRate / rate

    /** True when the frames a read up to engine frame [last] needs are held. */
    private fun covers(last: Long): Boolean =
        sourceRate != 0 && base != Long.MIN_VALUE && base + size > floor(place(last)).toLong() + 1

    /**
     * Writes [count] engine frames from [first] into [into] at frame [offset],
     * interleaved, calling [decode] for the next block (it gives the format
     * and adds the block, or returns false at the end of the sound) until
     * they are held; what is not held is silence. What lies before [first] is
     * let go, as it is never asked for again.
     */
    fun read(first: Long, count: Int, into: FloatArray, offset: Int, decode: () -> Boolean) {
        if (count <= 0) return
        while (!covers(first + count - 1)) if (!decode()) break
        val exact = sourceRate == rate
        for (frame in 0 until count) {
            val at = first + frame
            val target = (offset + frame) * channels
            if (base == Long.MIN_VALUE || sourceRate == 0) { for (c in 0 until channels) into[target + c] = 0f; continue }
            if (exact) {
                val index = at - base
                if (index in 0 until size) System.arraycopy(held, (index * channels).toInt(), into, target, channels)
                else for (c in 0 until channels) into[target + c] = 0f
            } else {
                val position = place(at)
                val lower = floor(position).toLong()
                val share = (position - lower).toFloat()
                val a = lower - base
                for (c in 0 until channels) {
                    val x = if (a in 0 until size) held[(a * channels).toInt() + c] else 0f
                    val y = if (a + 1 in 0 until size) held[((a + 1) * channels).toInt() + c] else 0f
                    into[target + c] = x + (y - x) * share
                }
            }
        }
        if (base != Long.MIN_VALUE && sourceRate > 0) {
            val keep = floor(place(first)).toLong() - 1
            val drop = (keep - base).coerceIn(0, size.toLong()).toInt()
            if (drop > 0) {
                System.arraycopy(held, drop * channels, held, 0, (size - drop) * channels)
                size -= drop
                base += drop
            }
        }
    }
}
