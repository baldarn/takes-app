package app.takes.core

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * How loud sound is, as the original app works it out: decibels and factors,
 * the grid a level is chosen on, the drag that chooses it, how much room is
 * left before the mix clips, the fades at an item's ends and the few
 * milliseconds of fade at every cut.
 *
 * Nothing here is stored by [ClipAudio] or read by [TimelineEditing]; see
 * each type for where the saved project and the current editor differ.
 */
object AudioLevels {
    /**
     * Seconds of fade on each side of a cut, so it never clicks. The same
     * length as the shortest transition ramp, [TransitionSound.MINIMUM_RAMP].
     */
    const val CUT_FADE = TransitionSound.MINIMUM_RAMP

    /** Seconds over which a change of level alone takes hold: a short ramp, never a fade through silence. */
    const val GAIN_RAMP = 0.01

    /** The sample rate the original measures its mix at. */
    const val MEASURE_RATE = 48_000

    /** The factor a level in decibels multiplies samples by: 0 dB is 1, +6 dB about 2. The formula of [ClipAudio.gain]. */
    fun linear(decibels: Double): Double = 10.0.pow(decibels / 20)

    /** The level in decibels of a factor; negative infinity for silence. */
    fun decibels(gain: Double): Double = 20 * log10(gain)

    /** A level as it is shown: always a sign and one decimal, "+0.0 dB", "-12.5 dB". */
    fun format(decibels: Double): String = String.format(Locale.ROOT, "%+.1f dB", decibels)

    /**
     * The highest absolute sample among the first [count] of [samples] and
     * [peak] so far. Null when a sample is not a number: that mix cannot be
     * measured, and the original refuses it.
     */
    fun peak(samples: FloatArray, count: Int = samples.size, peak: Float = 0f): Float? {
        var result = peak
        for (index in 0 until count) {
            val sample = samples[index]
            if (!sample.isFinite()) return null
            result = maxOf(result, abs(sample))
        }
        return result
    }

    /**
     * Whether a level is shown as clipping: never while muted, and for a
     * selection of mixed levels only once one has been chosen for all of it.
     */
    fun warnsClipping(decibels: Double, headroom: AudioHeadroom?, muted: Boolean = false, mixed: Boolean = false, chosen: Boolean = false): Boolean =
        !muted && (!mixed || chosen) && headroom?.clips(decibels) == true
}

/**
 * The levels a gain control can choose: half decibels from −40 to +12. Zero
 * is one of them and never means silence; mute is a separate switch.
 *
 * [ClipAudio] accepts any level from −60 dB, and the current sound panel
 * moves in whole decibels and calls −60 "silent".
 */
object AudioGainGrid {
    const val MINIMUM_DB = -40.0
    const val MAXIMUM_DB = 12.0
    const val STEP_DB = 0.5

    /** The nearest level on the grid; halfway cases go away from zero. */
    fun snap(decibels: Double): Double = minOf(MAXIMUM_DB, maxOf(MINIMUM_DB, roundedAwayFromZero(decibels * 2) / 2))

    /** The level every one of [levels] has, or null when they differ or there are none. */
    fun common(levels: List<Double>): Double? {
        val first = levels.firstOrNull() ?: return null
        return first.takeIf { levels.all { abs(it - first) < 0.001 } }
    }

    /**
     * The level one press of a stepper gives: [delta] more than [value], on
     * the grid. With mixed levels and none chosen yet, the first press takes
     * the loudest of [levels] when going up and the quietest when going down.
     */
    fun stepped(value: Double?, delta: Double, levels: ClosedFloatingPointRange<Double>): Double =
        snap(if (value == null) (if (delta > 0) levels.endInclusive else levels.start) else value + delta)
}

/**
 * A level being chosen by dragging a waveform up or down. [initial] is the
 * level the dragged sound had in common, or null when its parts differ;
 * [levels] runs from the quietest to the loudest of them. [value] is what
 * the drag has chosen so far: null leaves mixed levels untouched.
 *
 * Distances are in the original's points, which are density-independent
 * pixels here, positive upwards.
 */
data class AudioGainDrag(
    val initial: Double?,
    val levels: ClosedFloatingPointRange<Double>,
    val value: Double? = initial,
    /** Decibels added by [steppedAtEdge] since the drag began. */
    val continued: Double = 0.0,
) {
    /**
     * The drag with the finger [distance] above where it went down. With
     * mixed levels the first [DEAD_ZONE] changes nothing; past it the level
     * starts from the loudest part going up and the quietest going down. A
     * level holds until the finger is clearly nearer another, and 0 dB holds
     * a little longer.
     */
    fun updated(distance: Double): AudioGainDrag {
        val base = initial ?: if (distance >= 0) levels.endInclusive else levels.start
        val delta = if (initial == null) maxOf(0.0, abs(distance) - DEAD_ZONE) * (if (distance >= 0) DB_PER_POINT else -DB_PER_POINT) else distance * DB_PER_POINT
        val raw = base + delta + continued
        var next = if (initial == null && abs(distance) < DEAD_ZONE && continued == 0.0) null else AudioGainGrid.snap(raw)
        if (value != null && next != null && abs(raw - value) < (if (value == 0.0) ZERO_DETENT else DETENT)) next = value
        return copy(value = next)
    }

    /**
     * One more step while the finger rests against the top ([delta] positive)
     * or the bottom of the screen; see [edgeStep]. Unchanged at either end of the grid.
     */
    fun steppedAtEdge(delta: Double): AudioGainDrag {
        val next = AudioGainGrid.snap((value ?: if (delta > 0) levels.endInclusive else levels.start) + delta)
        return if (next == value) this else copy(value = next, continued = continued + delta)
    }

    /**
     * The level to save when the finger lifts, or null when nothing changes:
     * the drag was cancelled, chose no level, or ended where it began.
     */
    fun committed(cancelled: Boolean = false): Double? = value?.takeIf { !cancelled && it != initial }

    companion object {
        /** Decibels per point of travel: 4 points to a step of the grid. */
        const val DB_PER_POINT = 0.125

        /** Points a drag over mixed levels moves before it chooses one. */
        const val DEAD_ZONE = 12.0

        /** A chosen level holds while the finger is within this many decibels of it. */
        const val DETENT = 0.4

        /** The same for 0 dB, which is easier to come back to. */
        const val ZERO_DETENT = 0.6

        /** Sideways points past which lifting the finger cancels the drag. */
        const val CANCEL_DISTANCE = 80.0

        /** Points from the top or bottom of the usable screen within which the level keeps stepping. */
        const val EDGE_MARGIN = 22.0

        /** Seconds between those steps. */
        const val EDGE_INTERVAL = 0.12

        /** A drag over sound whose parts are at [levels], one per part. */
        fun begin(levels: List<Double>): AudioGainDrag =
            AudioGainDrag(AudioGainGrid.common(levels), (levels.minOrNull() ?: 0.0)..(levels.maxOrNull() ?: 0.0))

        /** Decibels for [distance] points of travel, before the grid. */
        fun decibels(distance: Double): Double = distance * DB_PER_POINT

        /** Points of travel for a change of [decibels]. */
        fun distance(decibels: Double): Double = decibels / DB_PER_POINT

        /** Whether the finger has gone far enough sideways to cancel. */
        fun cancels(sideways: Double): Boolean = abs(sideways) > CANCEL_DISTANCE

        /**
         * The step to take every [EDGE_INTERVAL] while the finger is at
         * height [y] on a screen usable from [top] to [bottom], y growing
         * downwards: up half a decibel near the top, down near the bottom, 0 elsewhere.
         */
        fun edgeStep(y: Double, top: Double, bottom: Double): Double =
            if (y < top + EDGE_MARGIN) AudioGainGrid.STEP_DB else if (y > bottom - EDGE_MARGIN) -AudioGainGrid.STEP_DB else 0.0
    }
}

/**
 * The factors the selected sound can be multiplied by without the whole mix
 * clipping. For every sample, with S the selected sound at 0 dB and U
 * everything else, −1 ≤ S·g + U ≤ 1; the factors that satisfy every sample
 * are [minimum]…[maximum]. Measuring both parts, rather than warning above
 * 0 dB, accounts for other tracks and for sounds that cancel each other.
 */
data class AudioHeadroom(val minimum: Double = 0.0, val maximum: Double = Double.POSITIVE_INFINITY, val samples: Int = 0) {
    /** True when nothing audible was measured: the selection is silent or muted. */
    val silent: Boolean get() = samples == 0

    /** The headroom after one more sample of the selected sound and of the rest. */
    fun including(selected: Double, remainder: Double): AudioHeadroom {
        if (!selected.isFinite() || !remainder.isFinite()) return copy(maximum = -1.0)
        // A silent selected sample changes nothing, but the rest may clip on its own.
        if (abs(selected) <= SILENCE) return if (abs(remainder) > 1) copy(maximum = -1.0) else this
        val a = (-1 - remainder) / selected
        val b = (1 - remainder) / selected
        return AudioHeadroom(maxOf(minimum, minOf(a, b)), minOf(maximum, maxOf(a, b)), samples + 1)
    }

    /** The headroom after the first [count] samples of two buffers of the same layout, without allocating per sample. */
    fun including(selected: FloatArray, remainder: FloatArray, count: Int = minOf(selected.size, remainder.size)): AudioHeadroom {
        var low = minimum
        var high = maximum
        var measured = samples
        for (index in 0 until count) {
            val s = selected[index].toDouble()
            val u = remainder[index].toDouble()
            if (!s.isFinite() || !u.isFinite()) { high = -1.0; continue }
            if (abs(s) <= SILENCE) { if (abs(u) > 1) high = -1.0; continue }
            val a = (-1 - u) / s
            val b = (1 - u) / s
            low = maxOf(low, minOf(a, b)); high = minOf(high, maxOf(a, b)); measured++
        }
        return AudioHeadroom(low, high, measured)
    }

    /** Whether the mix clips with the selected sound at [decibels]. */
    fun clips(decibels: Double): Boolean {
        val gain = AudioLevels.linear(decibels)
        return gain < minimum - TOLERANCE || gain > maximum + TOLERANCE
    }

    companion object {
        private const val SILENCE = 1e-12
        private const val TOLERANCE = 1e-7

        /**
         * The frames to measure for a selection over [range] seconds of a
         * mix [duration] seconds long: the range within the mix, with 10 ms
         * more at the end for the ramp out of it. Null when nothing is left,
         * as when the selection lies past the end.
         */
        fun frames(range: ClosedFloatingPointRange<Double>, duration: Double, sampleRate: Int = AudioLevels.MEASURE_RATE): LongRange? {
            val lower = maxOf(0.0, range.start)
            val upper = minOf(duration, range.endInclusive + AudioLevels.GAIN_RAMP)
            if (!duration.isFinite() || !lower.isFinite() || !upper.isFinite() || upper <= lower) return null
            return roundedAwayFromZero(lower * sampleRate).toLong() until roundedAwayFromZero(upper * sampleRate).toLong()
        }
    }
}

/**
 * A straight fade at one end of an item, [seconds] long from silence to full
 * level. [elapsed] is how much of it is already behind the item's edge: a
 * split inside a fade leaves the later piece a fade that starts part of the
 * way up, with the same slope.
 *
 * Saved with the original's field names. [ClipAudio] instead holds each fade
 * as a number of seconds, takes at most half the item for each, and a split
 * drops the fade from the piece that loses its edge.
 */
@Serializable
data class AudioEdgeFade(val seconds: Double, val elapsed: Double = 0.0) {
    val valid: Boolean get() = seconds > 0 && seconds <= EditTime.LIMIT && elapsed >= 0 && elapsed < seconds

    /** Seconds of the fade heard in an item [length] seconds long. */
    fun remaining(length: Double): Double = minOf(length, seconds - elapsed)

    /** The fade after [amount] is cut off its edge, or null when that was all of it. */
    fun trimming(amount: EditTime): AudioEdgeFade? =
        copy(elapsed = (EditTime.of(elapsed) + amount).seconds).takeIf { it.elapsed < seconds }

    /** The fade when its item changes length, as after a change of speed: squeezed to fit a shorter item, never stretched. */
    fun fitted(oldLength: EditTime, newLength: EditTime): AudioEdgeFade {
        val visible = remaining(oldLength.seconds)
        if (newLength <= EditTime.ZERO || newLength.seconds >= visible) return this
        val scale = newLength.seconds / visible
        return AudioEdgeFade(seconds * scale, elapsed * scale)
    }

    /** The level [distanceFromEdge] seconds inside the item from the fade's edge, 0 to 1. */
    fun gain(distanceFromEdge: Double): Double = minOf(1.0, maxOf(0.0, (elapsed + distanceFromEdge) / seconds))

    companion object {
        /** A fade of [seconds] on an item [length] long: no longer than the item, null for none. */
        fun of(seconds: Double, length: EditTime): AudioEdgeFade? =
            minOf(length, maxOf(EditTime.ZERO, EditTime.of(seconds))).seconds.takeIf { it > 0 }?.let { AudioEdgeFade(it) }
    }
}

/** The fades at the two ends of one item. */
@Serializable
data class AudioEdgeFades(val fadeIn: AudioEdgeFade? = null, val fadeOut: AudioEdgeFade? = null) {
    /** The level they leave [offset] seconds into an item [length] seconds long: the lower of the two. */
    fun gain(offset: Double, length: Double): Double = minOf(fadeIn?.gain(offset) ?: 1.0, fadeOut?.gain(length - offset) ?: 1.0)

    /** The fades of the piece [part] of an item [length] long, in the item's own time. */
    fun sliced(part: EditInterval, length: EditTime): AudioEdgeFades =
        AudioEdgeFades(fadeIn?.trimming(part.start), fadeOut?.trimming(length - part.end))

    /** The fades when the item changes length without being cut. */
    fun fitted(oldLength: EditTime, newLength: EditTime): AudioEdgeFades =
        AudioEdgeFades(fadeIn?.fitted(oldLength, newLength), fadeOut?.fitted(oldLength, newLength))
}

/** Which end of the selected sound a fade is set on. */
enum class AudioFadeEdge {
    FadeIn, FadeOut;

    /**
     * The one item the fade belongs to among [items], given in track order
     * with their places on the timeline: of those [selected], the first to
     * begin for a fade in, the last to end for a fade out. The earliest in
     * the list wins a tie.
     */
    fun index(items: List<EditInterval>, selected: (Int) -> Boolean): Int? {
        val chosen = items.indices.filter(selected)
        return if (this == FadeIn) chosen.minByOrNull { items[it].start } else chosen.maxByOrNull { items[it].end }
    }
}

/** The sound a level applies to: items by id, and optionally only a stretch of the timeline. */
data class AudioGainSelection(val audio: Set<String> = emptySet(), val range: EditInterval? = null)

/**
 * The level of one uninterrupted run of sound on a track, on the timeline's
 * clock. Every shape multiplies or limits the others: the fades at the cut
 * on each side, the transitions of the picture it follows, the fades the
 * user put on the item, and its volume.
 */
data class AudioEnvelopeSegment(
    val start: Double,
    val end: Double,
    /** Seconds of fade after [start]; 0 where the sound continues from the run before. */
    val fadeIn: Double = AudioLevels.CUT_FADE,
    val fadeOut: Double = AudioLevels.CUT_FADE,
    /** Where the item this run belongs to begins and ends. */
    val clipStart: Double = start,
    val clipEnd: Double = end,
    val transitionIn: Double = 0.0,
    val transitionOut: Double = 0.0,
    val audibleStart: Double = Double.NEGATIVE_INFINITY,
    val audibleEnd: Double = Double.POSITIVE_INFINITY,
    /** The ramps of [TransitionSound.rampIn] and [TransitionSound.rampOut], on the timeline. */
    val inRamp: ClosedFloatingPointRange<Double>? = null,
    val outRamp: ClosedFloatingPointRange<Double>? = null,
    val userFadeIn: AudioEdgeFade? = null,
    val userFadeOut: AudioEdgeFade? = null,
    /** The item this run plays. */
    val owner: String? = null,
    /** The item whose sound this run continues, and its volume: the level slides from one to the other over [AudioLevels.GAIN_RAMP]. */
    val initialOwner: String? = null,
    val initialVolume: Double? = null,
    /** The item's level as a factor, 0 when muted. */
    val volume: Double = 1.0,
    /** When set, the volume slides from [volume] to this over the run. */
    val endVolume: Double? = null,
    /** Stretch of the timeline inside which [gainInside] replaces the volume and outside which [gainOutside] does. */
    val gainWindow: ClosedFloatingPointRange<Double>? = null,
    val gainInside: Double? = null,
    val gainOutside: Double? = null,
    val initialGainInside: Double? = null,
    val initialGainOutside: Double? = null,
) {
    /** The item's volume at [time], before any window. */
    fun level(time: Double): Double =
        endVolume?.let { volume + (it - volume) * clamped((time - start) / maxOf(0.000001, end - start)) } ?: volume

    /** The factor samples are multiplied by at timeline second [time]; 0 outside the run. */
    fun gain(time: Double): Double {
        if (time < start || time >= end) return 0.0
        var gain = clamped(minOf(if (fadeIn > 0) (time - start) / fadeIn else 1.0, if (fadeOut > 0) (end - time) / fadeOut else 1.0))
        if (transitionIn > 0) gain = minOf(gain, maxOf(0.0, (time - clipStart) / transitionIn))
        if (transitionOut > 0) gain = minOf(gain, maxOf(0.0, (clipEnd - time) / transitionOut))
        gain = minOf(gain, maxOf(0.0, minOf((time - audibleStart) / AudioLevels.CUT_FADE, (audibleEnd - time) / AudioLevels.CUT_FADE)))
        inRamp?.let { gain *= TransitionSound.rising(time, it) }
        outRamp?.let { gain *= TransitionSound.falling(time, it) }
        gain *= minOf(userFadeIn?.gain(time - clipStart) ?: 1.0, userFadeOut?.gain(clipEnd - time) ?: 1.0)
        val target = adjusted(time, level(time), gainInside, gainOutside)
        if (initialVolume != null) {
            val initial = adjusted(time, initialVolume, initialGainInside, initialGainOutside)
            return gain * (initial + (target - initial) * clamped((time - start) / AudioLevels.GAIN_RAMP))
        }
        return gain * target
    }

    private fun adjusted(time: Double, value: Double, inside: Double?, outside: Double?): Double {
        val range = gainWindow ?: return value
        val enter = clamped((time - range.start) / AudioLevels.GAIN_RAMP)
        val leave = clamped((time - range.endInclusive) / AudioLevels.GAIN_RAMP)
        val weight = enter * (1 - leave)
        return (outside ?: value) * (1 - weight) + (inside ?: value) * weight
    }

    private fun clamped(value: Double) = minOf(1.0, maxOf(0.0, value))
}

/** Other mixes made from the same runs, for listening to a level before saving it and for measuring [AudioHeadroom]. */
object AudioGainMix {
    sealed interface Mode {
        /** The selected sound at this level in decibels, everything else as saved. */
        data class Audition(val decibels: Double) : Mode

        /** Only the selected sound, at 0 dB: the S of [AudioHeadroom]. Tracks with no envelope of their own must be silenced by the caller. */
        data object Selected : Mode

        /** Everything but the selected sound: the U of [AudioHeadroom]. */
        data object Remainder : Mode
    }

    fun replacing(segments: List<AudioEnvelopeSegment>, selection: AudioGainSelection, mode: Mode): List<AudioEnvelopeSegment> = segments.map { source ->
        val selected = source.owner != null && source.owner in selection.audio
        val initialSelected = source.initialOwner != null && source.initialOwner in selection.audio
        val windowed = source.copy(gainWindow = window(selection))
        when (mode) {
            is Mode.Audition -> {
                val gain = AudioLevels.linear(mode.decibels)
                windowed.copy(gainInside = if (selected) gain else source.gainInside, initialGainInside = if (initialSelected) gain else source.initialGainInside)
            }
            Mode.Selected -> windowed.copy(gainOutside = 0.0, gainInside = if (selected) 1.0 else 0.0,
                initialGainOutside = 0.0, initialGainInside = if (initialSelected) 1.0 else 0.0)
            Mode.Remainder -> windowed.copy(gainInside = if (selected) 0.0 else source.gainInside, initialGainInside = if (initialSelected) 0.0 else source.initialGainInside)
        }
    }

    internal fun window(selection: AudioGainSelection): ClosedFloatingPointRange<Double> =
        selection.range?.seconds ?: (Double.NEGATIVE_INFINITY..Double.POSITIVE_INFINITY)
}

/**
 * The level being tried while it is dragged, handed from the editor to the
 * audio thread. [update] swaps one small immutable value; the audio side
 * takes a [snapshot] once per buffer.
 */
class LiveAudioGain {
    data class Snapshot(val revision: Int = 0, val selection: AudioGainSelection = AudioGainSelection(), val gain: Double? = null) {
        /** [source] with the tried level mixed in: [scale] of its own volume plus [offset]. */
        fun applying(source: AudioEnvelopeSegment, time: Double, scale: Double, offset: Double): AudioEnvelopeSegment {
            val selected = source.owner != null && source.owner in selection.audio
            val initialSelected = source.initialOwner != null && source.initialOwner in selection.audio
            if (!selected && !initialSelected) return source
            return source.copy(
                gainWindow = AudioGainMix.window(selection),
                gainInside = if (selected) source.level(time) * scale + offset else source.gainInside,
                initialGainInside = if (initialSelected) (source.initialVolume ?: source.volume) * scale + offset else source.initialGainInside,
            )
        }
    }

    @Volatile private var value = Snapshot()

    /** Tries [decibels] on [selection]; null goes back to the saved levels. */
    @Synchronized fun update(decibels: Double?, selection: AudioGainSelection) {
        value = Snapshot(value.revision + 1, selection, decibels?.let(AudioLevels::linear))
    }

    fun snapshot(): Snapshot = value
}

/**
 * The level of one track sample by sample: its runs in order, found by
 * binary search, with the level being tried through [live] brought in over
 * [AudioLevels.GAIN_RAMP] so a drag never clicks. Only the selected sound is
 * smoothed; cuts and fades of everything else stay exactly as they are.
 *
 * One per audio processor: [fill] is called from the audio thread only.
 */
class AudioEnvelope(private val segments: List<AudioEnvelopeSegment>, private val live: LiveAudioGain? = null) {
    private var snapshot = LiveAudioGain.Snapshot()
    private var scale = 1.0
    private var offset = 0.0
    private var rampRemaining = 0

    val duration: Double get() = segments.lastOrNull()?.end ?: 0.0

    init { prepare() }

    /** Starts from the level being tried right now, without a ramp: for a new or reconfigured processor. */
    fun prepare() {
        snapshot = live?.snapshot() ?: LiveAudioGain.Snapshot()
        scale = if (snapshot.gain == null) 1.0 else 0.0
        offset = snapshot.gain ?: 0.0
        rampRemaining = 0
    }

    /** The run playing at [time]: the first that ends after it. Null past the last. */
    fun segment(time: Double): AudioEnvelopeSegment? {
        var lower = 0
        var upper = segments.size
        while (lower < upper) {
            val middle = (lower + upper) / 2
            if (segments[middle].end <= time) lower = middle + 1 else upper = middle
        }
        return segments.getOrNull(lower)
    }

    /** The factor at [time] with the level being tried as it stands. */
    fun gain(time: Double): Double {
        val segment = segment(time) ?: return 0.0
        return if (scale == 1.0 && offset == 0.0) segment.gain(time) else snapshot.applying(segment, time, scale, offset).gain(time)
    }

    /** Writes the factors of [count] frames from second [start] at [sampleRate] into [gains]. */
    fun fill(start: Double, count: Int, sampleRate: Double, gains: DoubleArray) {
        live?.snapshot()?.takeIf { it.revision != snapshot.revision }?.let { next ->
            // Letting go keeps the selection, so the tried level ramps out of the same sound.
            if (next.gain == null) snapshot = next.copy(selection = snapshot.selection)
            else {
                if (next.selection != snapshot.selection) { scale = 1.0; offset = 0.0 }
                snapshot = next
            }
            rampRemaining = maxOf(1, (sampleRate * AudioLevels.GAIN_RAMP).toInt())
        }
        for (frame in 0 until count) {
            if (rampRemaining > 0) {
                scale += ((if (snapshot.gain == null) 1.0 else 0.0) - scale) / rampRemaining
                offset += ((snapshot.gain ?: 0.0) - offset) / rampRemaining
                rampRemaining--
            }
            gains[frame] = gain(start + frame / sampleRate)
        }
    }
}

/**
 * The fades at plain cuts for a timeline of clips played end to end:
 * [AudioLevels.CUT_FADE] down to silence and back up at every cut, and at the
 * start and the end of the movie. Each sample looks only at the cut on
 * either side of it, found by binary search, so thousands of cuts cost no
 * more than a few.
 */
class CutEnvelope(boundaries: List<Double>, val duration: Double) {
    private val boundaries = boundaries.toDoubleArray()

    fun gain(time: Double): Double {
        var gain = minOf(1.0, maxOf(0.0, minOf(time, duration - time) / AudioLevels.CUT_FADE))
        var lower = 0
        var upper = boundaries.size
        while (lower < upper) {
            val middle = (lower + upper) / 2
            if (boundaries[middle] < time) lower = middle + 1 else upper = middle
        }
        if (lower < boundaries.size) gain = minOf(gain, abs(boundaries[lower] - time) / AudioLevels.CUT_FADE)
        if (lower > 0) gain = minOf(gain, abs(time - boundaries[lower - 1]) / AudioLevels.CUT_FADE)
        return gain
    }

    companion object {
        /**
         * For pieces [durations] long played one after the other. There is
         * no fade where [continues] says the sound of the piece at an index
         * carries on into the next, as across a split nobody trimmed.
         */
        fun of(durations: List<Double>, continues: (Int) -> Boolean): CutEnvelope {
            val points = ArrayList<Double>()
            var cursor = 0.0
            durations.forEachIndexed { index, duration ->
                if (index > 0 && !continues(index - 1)) points += cursor
                cursor += duration
            }
            return CutEnvelope(points, cursor)
        }

        /** Whether the sound of [clip] carries on into [next]: the same source, picked up where it left off, both muted or neither. */
        fun continues(clip: TimelineClip, next: TimelineClip): Boolean =
            clip.sourceID == next.sourceID && abs(clip.end - next.start) < 0.00001 && (clip.audio?.muted == true) == (next.audio?.muted == true)
    }
}

/**
 * The time of each buffer an audio processor is handed. A buffer whose
 * reported time is not a number, as can happen at an empty or retimed edit,
 * is placed right after the one before instead of being dropped.
 */
class AudioEnvelopeClock {
    var next: Double? = null
        private set

    /** The time of a buffer of [frames] at [rate], reported to start at [reported]; [fallback] when nothing came before. */
    fun start(reported: Double, frames: Int, rate: Double, fallback: Double): Double {
        val time = if (reported.isFinite()) reported else next ?: fallback
        next = time + frames / rate
        return time
    }
}
