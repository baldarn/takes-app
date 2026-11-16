package app.takes.core

import kotlin.math.floor

/**
 * The decoded sound a piece of the mix reads: its input ([TrackMixPiece.input])
 * at the engine's rate and channels, frame 0 at second 0 of the source,
 * already through the piece's chain.
 */
interface TrackMixSource : AutoCloseable {
    /**
     * Writes [count] frames from source frame [first] into [into] at frame
     * [offset], interleaved. Frames before the source's start or past its end
     * are silence. From one call to the next [first] never goes back.
     */
    fun read(first: Long, count: Int, into: FloatArray, offset: Int)

    override fun close() {}
}

/**
 * The sound of a run that is not at speed ([TrackMixRun.atSpeed]): its frames
 * one after the other, from [TrackMixRetime.open]'s first frame on.
 */
interface TrackMixRetimed : AutoCloseable {
    /** The next [count] frames of the run, interleaved, into [into] from frame [offset]. */
    fun read(count: Int, into: FloatArray, offset: Int)

    override fun close() {}
}

/** How the engine plays a run faster or slower: the app keeps the pitch, the core reads by linear interpolation as [TrackMixer] does. */
fun interface TrackMixRetime {
    /**
     * The run [run] read from [source], starting [skip] frames into the
     * frames of the run (a seek lands inside a run), at [sampleRate] with
     * [channels].
     */
    fun open(source: TrackMixSource, run: TrackMixRun, skip: Long, sampleRate: Int, channels: Int): TrackMixRetimed

    companion object {
        /** Linear interpolation between the two frames around each place: the reference's reading, with no pitch kept. */
        val linear = TrackMixRetime { source, run, skip, sampleRate, channels -> LinearStretch(source, run, skip, sampleRate, channels) }
    }
}

private class LinearStretch(private val source: TrackMixSource, private val run: TrackMixRun, skip: Long, private val sampleRate: Int, private val channels: Int) : TrackMixRetimed {
    private var next = TrackMixer.firstFrame(run.start, sampleRate) + skip
    private var window = FloatArray(0)

    override fun read(count: Int, into: FloatArray, offset: Int) {
        if (count <= 0) return
        fun position(frame: Long) = (run.sourceStart + (frame.toDouble() / sampleRate - run.start.seconds) * run.rate) * sampleRate
        val low = floor(minOf(position(next), position(next + count - 1))).toLong()
        val high = floor(maxOf(position(next), position(next + count - 1))).toLong() + 1
        val span = (high - low + 1).toInt()
        if (window.size < span * channels) window = FloatArray(span * channels)
        source.read(low, span, window, 0)
        for (frame in 0 until count) {
            val place = position(next + frame)
            val lower = floor(place).toLong()
            val share = (place - lower).toFloat()
            val a = ((lower - low) * channels).toInt()
            for (channel in 0 until channels) {
                into[(offset + frame) * channels + channel] = window[a + channel] * (1 - share) + window[a + channels + channel] * share
            }
        }
        next += count
    }
}

/**
 * The audio engine: the mix of a [TrackMixPlan] made a block at a time, for
 * the export's sound file, the preview's output, the recognizer's 16 kHz and
 * the peak and headroom measurements. It plays what [TrackMixer] plays, frame
 * for frame (the reference is that class, on whole arrays): every run of
 * every piece on the frames whose time lies in it, read from the piece's
 * input, at the level of the piece's envelope, censored, lanes added up and
 * nothing limited.
 *
 * [open] gives a piece's input when the piece is first heard, null for
 * silence; it is closed once the piece has played or on a seek. A run at
 * speed reads its nearest frames, as the reference does; one that is not is
 * read through [stretch]. With [live], the level being tried in the Volume
 * tool follows it ([AudioEnvelope.fill]).
 */
class TrackMixEngine(
    val plan: TrackMixPlan,
    val sampleRate: Int,
    val channels: Int,
    private val open: (TrackMixPiece) -> TrackMixSource?,
    private val stretch: TrackMixRetime = TrackMixRetime.linear,
    live: LiveAudioGain? = null,
) : AutoCloseable {
    /** The frames of the whole mix ([TrackMixer.frames]). */
    val frames: Long = TrackMixer.frames(plan.duration, sampleRate).toLong()

    /** The frame the next [render] begins with. */
    var position: Long = 0
        private set

    private class Run(val run: TrackMixRun, val first: Long, val last: Long)

    private inner class Piece(val piece: TrackMixPiece) {
        val runs = piece.runs.map { run ->
            Run(run, TrackMixer.firstFrame(run.start, sampleRate).coerceAtMost(frames), TrackMixer.firstFrame(run.end, sampleRate).coerceAtMost(frames))
        }
        val first = runs.minOf { it.first }
        val last = runs.maxOf { it.last }
        var source: TrackMixSource? = null
        var opened = false
        var stretched: TrackMixRetimed? = null
        var stretchedRun: Run? = null

        fun source(): TrackMixSource? {
            if (!opened) { opened = true; source = open(piece) }
            return source
        }

        fun close() {
            runCatching { stretched?.close() }
            runCatching { source?.close() }
            stretched = null; stretchedRun = null; source = null; opened = false
        }
    }

    private inner class Lane(lane: TrackMixLane, live: LiveAudioGain?) {
        val pieces = lane.pieces.map { Piece(it) }
        val envelope = live?.let { lane.envelope(it) }
        var next = 0

        fun seek(frame: Long) {
            pieces.forEach { it.close() }
            next = pieces.indexOfFirst { it.last > frame }.let { if (it < 0) pieces.size else it }
            envelope?.prepare()
        }
    }

    private val lanes = plan.lanes.map { Lane(it, live) }
    private var chunk = FloatArray(0)
    private var gains = DoubleArray(0)
    private var window = FloatArray(0)

    /** Plays on from [frame]: whatever was open is let go, a piece being heard there is opened again. */
    fun seek(frame: Long) {
        position = frame.coerceIn(0, frames)
        lanes.forEach { it.seek(position) }
    }

    /**
     * Mixes the next [count] frames into [into], interleaved, from its start,
     * replacing what it held. Past the end of the mix it writes silence.
     * Returns how many frames of the mix there were.
     */
    fun render(into: FloatArray, count: Int): Int {
        java.util.Arrays.fill(into, 0, count * channels, 0f)
        val from = position
        val until = minOf(frames, from + count)
        if (until <= from) return 0
        if (chunk.size < count * channels) { chunk = FloatArray(count * channels); gains = DoubleArray(count) }
        for (lane in lanes) {
            var index = lane.next
            while (index < lane.pieces.size) {
                val piece = lane.pieces[index]
                if (piece.first >= until) break
                if (piece.last > from) mixPiece(lane, piece, from, until, into)
                if (piece.last <= until) { piece.close(); if (index == lane.next) lane.next = index + 1 }
                index++
            }
        }
        position = until
        return (until - from).toInt()
    }

    private fun mixPiece(lane: Lane, piece: Piece, from: Long, until: Long, into: FloatArray) {
        val source = piece.source() ?: return
        val censors = piece.piece.censors
        for (run in piece.runs) {
            val a = maxOf(run.first, from)
            val b = minOf(run.last, until)
            val count = (b - a).toInt()
            if (count <= 0) continue
            if (run.run.atSpeed) readAtSpeed(source, run.run, a, count) else readStretched(piece, source, run, a, count)
            val envelope = lane.envelope
            if (envelope != null) envelope.fill(a.toDouble() / sampleRate, count, sampleRate.toDouble(), gains)
            else for (frame in 0 until count) gains[frame] = piece.piece.envelope.gain((a + frame).toDouble() / sampleRate)
            if (censors.isEmpty()) {
                for (frame in 0 until count) {
                    val gain = gains[frame].toFloat()
                    for (channel in 0 until channels) chunk[frame * channels + channel] *= gain
                }
            } else {
                AudioCensor.render(chunk, channels, sampleRate.toDouble(), a.toDouble() / sampleRate, censors, count, gains)
            }
            val offset = ((a - from) * channels).toInt()
            for (index in 0 until count * channels) into[offset + index] += chunk[index]
        }
    }

    /** The nearest frame of the source for each frame, as [TrackMixer] reads a run at speed. */
    private fun readAtSpeed(source: TrackMixSource, run: TrackMixRun, a: Long, count: Int) {
        fun index(frame: Long) = roundedAwayFromZero((run.sourceStart + (frame.toDouble() / sampleRate - run.start.seconds)) * sampleRate).toLong()
        val low = index(a)
        val high = index(a + count - 1)
        if (high - low == (count - 1).toLong()) {
            source.read(low, count, chunk, 0)
            return
        }
        // A frame the rounding of seconds puts twice, or skips: read the span and take each frame's own.
        val span = (high - low + 1).toInt().coerceAtLeast(1)
        if (window.size < span * channels) window = FloatArray(span * channels)
        source.read(low, span, window, 0)
        for (frame in 0 until count) {
            val at = ((index(a + frame) - low).coerceIn(0, span.toLong() - 1) * channels).toInt()
            System.arraycopy(window, at, chunk, frame * channels, channels)
        }
    }

    private fun readStretched(piece: Piece, source: TrackMixSource, run: Run, a: Long, count: Int) {
        if (piece.stretchedRun !== run) {
            runCatching { piece.stretched?.close() }
            piece.stretched = stretch.open(source, run.run, a - run.first, sampleRate, channels)
            piece.stretchedRun = run
        }
        piece.stretched!!.read(count, chunk, 0)
    }

    override fun close() {
        lanes.forEach { lane -> lane.pieces.forEach { it.close() } }
    }

    /** The whole mix in one array: for tests and short stretches. */
    fun renderAll(block: Int = 4096): FloatArray {
        seek(0)
        val output = FloatArray((frames * channels).toInt())
        val buffer = FloatArray(block * channels)
        var at = 0
        while (true) {
            val done = render(buffer, block)
            if (done == 0) break
            System.arraycopy(buffer, 0, output, at * channels, done * channels)
            at += done
        }
        return output
    }
}

/** A source held whole in memory, frame 0 at second 0: for tests and short sounds. */
class TrackMixArraySource(private val samples: FloatArray, private val channels: Int) : TrackMixSource {
    private val frames = samples.size / channels

    override fun read(first: Long, count: Int, into: FloatArray, offset: Int) {
        for (frame in 0 until count) {
            val index = first + frame
            for (channel in 0 until channels) {
                into[(offset + frame) * channels + channel] = if (index in 0 until frames) samples[(index * channels + channel).toInt()] else 0f
            }
        }
    }
}
