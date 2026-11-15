package app.takes.core

import kotlin.math.floor

/**
 * The mix of a [TrackMixPlan] worked out on whole arrays of samples, slowly
 * and plainly: the reference the app's audio engine, in the preview and in
 * the export, is tested against sample by sample.
 *
 * Frame `f` of the output is at second `f / sampleRate` of the timeline. A
 * run of a piece covers the frames whose time lies in it, half-open as an
 * [EditInterval] is, so two pieces that meet share no frame and leave none
 * out. Each frame of a run reads the nearest frame of its source at the
 * place the run gives it; a run that is not at speed 1 ([TrackMixRun.atSpeed]) is read by linear
 * interpolation, which keeps neither the pitch nor the engine's stretch, so
 * only the place and the length of such a run are compared. The level of
 * the frame is the piece's [AudioEnvelopeSegment.gain] at that second, then
 * its censors ([AudioCensor.render]); lanes are added up and nothing is
 * limited, so a mix that clips shows it.
 */
object TrackMixer {
    /** The frames of a mix [duration] long at [sampleRate]: every frame that begins before the end. */
    fun frames(duration: EditTime, sampleRate: Int): Int = firstFrame(duration, sampleRate).toInt()

    /** The first frame at or after [time]. */
    fun firstFrame(time: EditTime, sampleRate: Int): Long {
        val scaled = time.ticks * sampleRate
        return Math.floorDiv(scaled + EditTime.SCALE - 1, EditTime.SCALE)
    }

    /**
     * The whole mix of [plan], [channels] interleaved at [sampleRate].
     * [source] gives the decoded sound a piece reads, at the same rate and
     * with the same channels, frame 0 at second 0 of the source, already
     * through the piece's chain; null plays nothing for that piece.
     */
    fun mix(plan: TrackMixPlan, sampleRate: Int, channels: Int, source: (TrackMixPiece) -> FloatArray?): FloatArray {
        val output = FloatArray(frames(plan.duration, sampleRate) * channels)
        for (piece in plan.pieces) {
            val samples = source(piece) ?: continue
            add(piece, samples, sampleRate, channels, output)
        }
        return output
    }

    /** Adds the sound of [piece], read from [samples], into [output]. */
    fun add(piece: TrackMixPiece, samples: FloatArray, sampleRate: Int, channels: Int, output: FloatArray) {
        val outputFrames = output.size / channels
        val sourceFrames = samples.size / channels
        for (run in piece.runs) {
            val first = firstFrame(run.start, sampleRate).coerceAtMost(outputFrames.toLong()).toInt()
            val last = firstFrame(run.end, sampleRate).coerceAtMost(outputFrames.toLong()).toInt()
            val count = last - first
            if (count <= 0) continue
            val chunk = FloatArray(count * channels)
            val gains = DoubleArray(count)
            val atSpeed = run.atSpeed
            for (frame in 0 until count) {
                val time = (first + frame).toDouble() / sampleRate
                gains[frame] = piece.envelope.gain(time)
                val position = (run.sourceStart + (time - run.start.seconds) * (if (atSpeed) 1.0 else run.rate)) * sampleRate
                for (channel in 0 until channels) chunk[frame * channels + channel] = read(samples, sourceFrames, channels, channel, position, atSpeed)
            }
            if (piece.censors.isEmpty()) {
                for (frame in 0 until count) for (channel in 0 until channels) chunk[frame * channels + channel] *= gains[frame].toFloat()
            } else {
                AudioCensor.render(chunk, channels, sampleRate.toDouble(), first.toDouble() / sampleRate, piece.censors, count, gains)
            }
            val offset = first * channels
            for (index in chunk.indices) output[offset + index] += chunk[index]
        }
    }

    private fun read(samples: FloatArray, frames: Int, channels: Int, channel: Int, position: Double, nearest: Boolean): Float {
        if (nearest) {
            val index = roundedAwayFromZero(position).toLong()
            return if (index in 0 until frames) samples[(index * channels + channel).toInt()] else 0f
        }
        val lower = floor(position).toLong()
        val share = (position - lower).toFloat()
        fun at(index: Long) = if (index in 0 until frames) samples[(index * channels + channel).toInt()] else 0f
        return at(lower) * (1 - share) + at(lower + 1) * share
    }
}
