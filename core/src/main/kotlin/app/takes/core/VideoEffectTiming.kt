package app.takes.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln

/*
 * The time effects as pure functions from a clip's output time to what it
 * shows and what it plays: Freeze Frame's hold, Undercranking's cadence,
 * speed-up, flicker, weave and mute, and Step Printing's blocks of blended
 * source frames and its retime. They follow the original's
 * YouRec/VideoEffects/VideoEffectTiming.swift (cited as `Timing`),
 * YouRec/VideoEffects/VideoEffectSampling.swift (`Sampling`), the compositor
 * YouRec/VideoToolCompositor.swift (`Compositor`) and the audio of
 * YouRec/MediaRenderer.swift (`Renderer`). They will become part of the
 * playback map; for now they hold the maths.
 */

/**
 * The real presentation times of a movie's frames, sorted and distinct: what
 * the original reads from the file so that optical printing groups actual
 * source frames, variable-rate movies included. Sampling:5-28.
 */
class VideoEffectFrameIndex(val times: List<Double>) {
    val valid: Boolean
        get() = times.isNotEmpty() && times.size <= 3_000_000 && times.all { it.isFinite() && it in 0.0..604800.0 } &&
            times.zipWithNext().all { (a, b) -> a < b }

    /** The first frame at or after [time], within 0.1 µs; past the last frame, the count. */
    fun first(time: Double): Int {
        var a = 0
        var b = times.size
        while (a < b) {
            val m = (a + b) / 2
            if (times[m] < time - 0.0000001) a = m + 1 else b = m
        }
        return a
    }

    /** The last frame at or before [time], within 0.1 µs; the first frame before it. */
    fun last(time: Double): Int {
        var a = 0
        var b = times.size
        while (a < b) {
            val m = (a + b) / 2
            if (times[m] <= time + 0.0000001) a = m + 1 else b = m
        }
        return maxOf(0, a - 1)
    }

    /**
     * The [count] frames of the Step Printing block that holds [time]:
     * blocks of [count] frames from the frame at [origin], the effect's
     * start; the frame at or after [time] when [firstAtOrAfter], else the one
     * at or before. Held inside the clip's frames, [lower] up to before
     * [upper]; a clip shorter than a frame repeats the frame under [lower].
     */
    fun block(time: Double, origin: Double, count: Int, lower: Double, upper: Double, firstAtOrAfter: Boolean): List<Double> {
        val start = last(origin)
        val index = if (firstAtOrAfter) first(time) else last(time)
        val block = start + (maxOf(start, minOf(times.size - 1, index)) - start) / count * count
        val minimum = minOf(times.size - 1, first(lower))
        val lastBefore = first(upper) - 1
        if (lastBefore < minimum) return List(count) { times[last(lower)] }
        val maximum = minOf(times.size - 1, lastBefore)
        return (0 until count).map { times[minOf(maximum, maxOf(minimum, block + it))] }
    }
}

/**
 * A clip's cadence clock with its trim bounds. The cadence integrates from
 * each effect's own origin, over the effects' whole ranges and without the
 * clip's pauses, so a pause or a split cannot reset its phase. Sampling:84-97.
 */
class VideoEffectSequenceClock(val lower: Double, val upper: Double, val cadence: VideoEffectCadenceClock) {
    companion object {
        /** The clock of a clip from [start] to [end] (source seconds) at [speed], with its [effects]. */
        fun of(effects: List<VideoEffectSettings>, start: Double, end: Double, speed: SpeedSettings? = null): VideoEffectSequenceClock {
            val cadenced = effects.filter { it.enabled && (it.kind == VideoEffectID.SuperEight || it.kind == VideoEffectID.Undercranking) }
            val from = minOf(start, cadenced.minOfOrNull { it.start } ?: start)
            val to = maxOf(end, cadenced.maxOfOrNull { it.end } ?: end)
            val spans = VideoEffectTiming.playbackSpans(effects, from, to, speed, listOf(SourceInterval(from, to)))
            return VideoEffectSequenceClock(start, end, VideoEffectCadenceClock(cadenced, spans.map { VideoEffectCadenceClock.Rate(it.start, it.end, it.rate) }))
        }
    }
}

/**
 * The source frames one output frame is made of. [times] are the source
 * seconds to read; [smear] how much of the block's mix is spread over its
 * frames. With [firstAtOrAfter] each time reads the first frame at or after
 * it (else the nearest one before), never one at or past [before].
 * Sampling:99-131.
 */
data class VideoEffectSamplePlan(
    val times: List<Double>,
    val smear: Double,
    val firstAtOrAfter: Boolean = false,
    val before: Double = Double.POSITIVE_INFINITY,
) {
    /**
     * How much of each of [times] the output frame holds. The compositor mixes
     * the frames with dissolves, each frame after the first at
     * `weight / accumulated`, starting from `1 − smear + weight` with
     * `weight = smear / count`: the first frame keeps `1 − smear + smear / count`
     * and every other `smear / count`. Without smear, or with one frame, only
     * the first is shown. Compositor:371-394.
     */
    val weights: List<Double>
        get() {
            if (times.size <= 1 || !(smear > 0)) return List(times.size) { if (it == 0) 1.0 else 0.0 }
            val weight = smear / times.size
            return List(times.size) { if (it == 0) 1 - smear + weight else weight }
        }

    /** The same mix as the compositor computes it, dissolve by dissolve: what [weights] must equal. */
    val dissolved: List<Double>
        get() {
            if (times.size <= 1 || !(smear > 0)) return weights
            val weight = smear / times.size
            var accumulated = 1 - smear + weight
            val result = MutableList(times.size) { if (it == 0) 1.0 else 0.0 }
            for (i in 1 until times.size) {
                accumulated += weight
                val t = weight / accumulated
                for (j in 0 until i) result[j] *= 1 - t
                result[i] = t
            }
            return result
        }

    companion object {
        /**
         * What the output frame at [sourceTime] reads, or null when it reads
         * the frame playing there as it is. A held frame ([frozen]) reads its
         * one second. A cadence hold reads the frame at its boundary, held
         * inside the clip. Step Printing reads its block from [frames], or
         * without an index the block on a grid of [sourceFPS] from the
         * effect's start, each time half a frame early so rounding cannot
         * pick a frame twice.
         */
        fun make(
            effects: List<VideoEffectSettings>,
            sourceTime: Double,
            sourceFPS: Double,
            clock: VideoEffectSequenceClock,
            frames: VideoEffectFrameIndex? = null,
            frozen: FrozenFrameSegment? = null,
        ): VideoEffectSamplePlan? {
            if (frozen != null) return VideoEffectSamplePlan(listOf(frozen.sourceTime), 0.0)
            val active = effects.filter { it.enabled && sourceTime >= it.start && sourceTime < it.end }
            var sample = sourceTime
            var changed = false
            clock.cadence.hold(sourceTime)?.let {
                sample = it.boundary
                changed = true
            }
            val step = active.firstOrNull { it.kind == VideoEffectID.StepPrinting }
            if (step != null) {
                val count = maxOf(2, step.number("step", sourceTime).toInt())
                val smear = step.number("smear", sourceTime)
                if (frames != null) {
                    return VideoEffectSamplePlan(frames.block(sample, step.start, count, clock.lower, clock.upper, changed), smear, true, clock.upper)
                }
                val selected = if (changed) VideoEffectTime.firstFrame(sample, sourceFPS) else sample
                val frame = floor(maxOf(0.0, selected - step.start) * sourceFPS + 0.00001).toInt()
                val first = frame / count * count
                val last = maxOf(clock.lower, clock.upper - 1.0 / sourceFPS)
                val times = (0 until count).map { minOf(last, maxOf(clock.lower, step.start + (first + it - 0.5) / sourceFPS)) }
                return VideoEffectSamplePlan(times, smear, true, clock.upper)
            }
            // Integration pieces can be shorter than a frame: clamp to the clip, not to the current piece, which would break a hold.
            return if (changed) VideoEffectSamplePlan(listOf(minOf(clock.upper - 0.00001, maxOf(clock.lower, sample))), 0.0, true, clock.upper) else null
        }
    }
}

/**
 * What a frozen hold shows at one moment. [sourceTime] is the held source
 * second. [holdTime] is how long the hold has been held, counting a split
 * hold's earlier pieces: Freeze Frame's own clock. [zoom] is the push-in's
 * magnification about the centre. With [print] the frame is drawn as a print,
 * its grain seeded by [grainFrame], the frame number of the timeline second.
 */
data class FreezeFrameMoment(val sourceTime: Double, val holdTime: Double, val zoom: Double, val print: Boolean, val grainFrame: Int)

/**
 * What a hold plays at one moment: the source second [sourceTime] at [gain],
 * or nothing when [sourceTime] is null.
 */
data class EffectSound(val sourceTime: Double?, val gain: Double = 1.0) {
    companion object {
        val SILENT = EffectSound(null, 0.0)
    }
}

/**
 * What an Undercranking frame shows: the source second read ([sourceTime],
 * the first frame at or after the cadence's boundary), the brightness the
 * flicker multiplies it by, and how far the gate weave moves it, in pixels
 * of the picture.
 */
data class UndercrankingMoment(val sourceTime: Double, val held: Boolean, val flicker: Double, val weave: Point)

object VideoEffectTiming {
    private val timed = setOf(VideoEffectID.Undercranking, VideoEffectID.StepPrinting)

    /**
     * What a clip plays, as source stretches each at one rate: [kept], the
     * clip from [start] to [end] without its pauses, at [speed] times the
     * speed of its Undercranking and Step Printing. A stretch whose rate
     * changes by more than 1 % between its ends is halved until it does not,
     * or is a 30th of a second or less, twelve times at most; the stretches
     * are laid on the effects' own source ranges so that splitting a clip
     * cannot move them. Timing:36-67.
     */
    fun playbackSpans(effects: List<VideoEffectSettings>, start: Double, end: Double, speed: SpeedSettings?, kept: List<SourceInterval>): List<EffectPlaybackSpan> {
        val active = effects.filter { it.enabled && it.kind in timed && it.start < end && it.end > start }
        if (active.isEmpty()) {
            val rate = SpeedMapping.rate(speed, VideoEffectSettings.sequenceRate(effects, start))
            return kept.map { EffectPlaybackSpan(it.start, it.end, rate) }
        }
        val points = mutableListOf(minOf(start, active.minOf { it.start }), maxOf(end, active.maxOf { it.end }))
        for (effect in active) {
            points += listOf(effect.start, effect.end)
            points += effect.parameters.filter { it.id == "speed" || it.id == "mute" }.flatMap { p -> p.keyframes.map { it.time } }
        }
        val boundaries = points.toSet().sorted()
        val result = mutableListOf<EffectPlaybackSpan>()
        fun rate(time: Double) = SpeedMapping.rate(speed, VideoEffectSettings.sequenceRate(effects, time))
        fun add(a: Double, b: Double, depth: Int = 0) {
            if (kept.none { it.start < b && it.end > a }) return
            val r0 = rate(a + 0.0000001)
            val r1 = rate(b - 0.0000001)
            if (depth < 12 && b - a > 1.0 / 30 && abs(ln(r1 / r0)) > 0.01) {
                val middle = (a + b) / 2
                add(a, middle, depth + 1)
                add(middle, b, depth + 1)
            } else {
                val value = rate((a + b) / 2)
                for (interval in kept) if (interval.start < b && interval.end > a) {
                    result += EffectPlaybackSpan(maxOf(a, interval.start), minOf(b, interval.end), value)
                }
            }
        }
        for ((a, b) in boundaries.zipWithNext()) if (b > a) add(a, b)
        return result
    }

    // Freeze Frame.

    /**
     * The source second a freeze at [position] seconds into a clip holds:
     * the second showing there, held inside the clip and at least one
     * [frame] before its end. TimelineEditing.freezing, Timing:70-74.
     */
    fun freezeSourceTime(clipStart: Double, clipEnd: Double, shown: Double, frame: Double): Double =
        minOf(clipEnd - frame, maxOf(clipStart, shown))

    /**
     * The hold a Freeze Frame inserts: one frame of source from
     * [sourceTime], held for the effect's "hold" seconds. The original makes
     * it three seconds long and its editor then sets the hold duration;
     * a new effect's hold is three. Timing:76-79, VideoEffectsEditor.swift:498.
     */
    fun freezeHold(sourceTime: Double, effect: VideoEffectSettings): FrozenFrameSegment =
        FrozenFrameSegment(sourceTime, effect.number("hold", sourceTime))

    /**
     * What a hold piece shows [offset] seconds into it, the piece starting at
     * timeline second [timelineStart] on a timeline of [frameRate] frames a
     * second. Push-in magnifies by `1 + push · clamp(holdTime / hold, 0, 1)`
     * (Metal:96); Print is the style that animates. Its controls are read at
     * the held second, as the compositor does. Compositor:169-173, :355-362.
     */
    fun freezeShown(hold: FrozenFrameSegment, effect: VideoEffectSettings, offset: Double, timelineStart: Double = 0.0, frameRate: Double = 30.0): FreezeFrameMoment {
        val holdTime = hold.offset + maxOf(0.0, offset)
        val zoom = 1 + effect.number("push", hold.sourceTime) * minOf(1.0, maxOf(0.0, holdTime / maxOf(.001, effect.number("hold", hold.sourceTime))))
        return FreezeFrameMoment(hold.sourceTime, holdTime, zoom, effect.choice("style", hold.sourceTime) == "Print", VideoEffectTime.frameIndex(timelineStart + offset, frameRate))
    }

    /**
     * What a hold piece plays [offset] seconds into it. Cut is silent.
     * Continue plays the source on from the held second, so the words
     * repeat when the footage resumes; Fade out over hold does the same
     * falling linearly to silence over the piece. A split hold's later piece
     * starts again from the held second, as the original's audio does: it
     * reads `sourceTime` for `duration`, not the piece's offset, and fades
     * each piece on its own. Renderer:497-504.
     */
    fun freezeHeard(hold: FrozenFrameSegment, effect: VideoEffectSettings, offset: Double): EffectSound {
        if (offset < 0 || offset >= hold.duration) return EffectSound.SILENT
        return when (effect.choice("audio", hold.sourceTime)) {
            "Cut" -> EffectSound.SILENT
            "Fade out over hold" -> EffectSound(hold.sourceTime + offset, maxOf(0.0, 1 - offset / hold.duration))
            else -> EffectSound(hold.sourceTime + offset)
        }
    }

    // Undercranking.

    /** The frame number of Undercranking's flicker and weave: its clock times its cadence. Metal:98, :389. */
    fun undercrankingFrame(time: Double, fps: Double): Int = floor(maxOf(0.0, time) * fps).toInt()

    /** The flicker of a frame: `1 + (random(4, 7, frame) · 2 − 1) · 0.08 · flicker`. Metal:389. */
    fun undercrankingFlicker(frame: Int, flicker: Double): Double =
        1 + (VideoEffectShaderMath.random01(4u, 7u, frame.toUInt()) * 2 - 1) * .08 * flicker

    /** The weave of a frame, in pixels of a picture of [size]: twice the noise across, three times down. Metal:97-100. */
    fun undercrankingWeave(frame: Int, weave: Double, size: Size): Point {
        val scale = minOf(size.width, size.height) / 1080
        val t = (frame.toUInt().toFloat() * .18f).toDouble()
        return Point(VideoEffectFormulasB.noise(t, 3.0, 0, 7) * 2 * weave * scale, VideoEffectFormulasB.noise(t, 8.0, 0, 11) * 3 * weave * scale)
    }

    /** Where the pixel at [uv] reads the Undercranking picture: magnified by 1.01 about the centre, then woven. Metal:97-100. */
    fun undercrankingPoint(uv: Point, size: Size, frame: Int, weave: Double): Point {
        val move = undercrankingWeave(frame, weave, size)
        return Point((uv.x - .5) / 1.01 + .5 + move.x / size.width, (uv.y - .5) / 1.01 + .5 + move.y / size.height)
    }

    /**
     * What an Undercranking frame shows at the source second [sourceTime]
     * (the one the clip's playback reaches; [SpeedMapping.sourceTime]), on
     * footage of [sourceFPS] or, with [frames], its real frame times. The
     * whole look is drawn at the frame read, so the flicker and the weave
     * hold with the cadence. Sampling:99-131, Compositor:333-337.
     */
    fun undercranking(
        effect: VideoEffectSettings,
        effects: List<VideoEffectSettings>,
        clock: VideoEffectSequenceClock,
        sourceTime: Double,
        sourceFPS: Double,
        size: Size,
        frames: VideoEffectFrameIndex? = null,
    ): UndercrankingMoment {
        val plan = VideoEffectSamplePlan.make(effects, sourceTime, sourceFPS, clock, frames)
        val read = plan?.times?.first()?.let { time ->
            if (frames != null) frames.times[minOf(frames.times.size - 1, frames.first(time))] else VideoEffectTime.firstFrame(time, sourceFPS)
        } ?: sourceTime
        val time = maxOf(0.0, read - effect.start + (effect.phaseOffset ?: 0.0))
        val frame = undercrankingFrame(time, effect.number("fps", read))
        return UndercrankingMoment(read, plan != null, undercrankingFlicker(frame, effect.number("flicker", read)), undercrankingWeave(frame, effect.number("weave", read), size))
    }

    /**
     * What a clip's piece plays [offset] seconds in: the source at the
     * playback's second, retimed (its pitch kept) but never held by the
     * cadence; nothing when the piece is muted at its [pieceStart], as the
     * original decides once per piece. Timing:24-31, Renderer:477-485.
     */
    fun heard(effects: List<VideoEffectSettings>, spans: List<EffectPlaybackSpan>, pieceStart: Double, offset: Double, end: Double): EffectSound =
        if (VideoEffectSettings.mutesSourceAudio(effects, pieceStart)) EffectSound.SILENT else EffectSound(SpeedMapping.sourceTime(offset, spans, end))
}
