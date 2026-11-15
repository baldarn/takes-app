package app.takes.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/** The shape of one cycle of the censor tone. */
@Serializable
enum class CensorWaveform {
    @SerialName("sine") Sine,
    @SerialName("triangle") Triangle,
    @SerialName("square") Square,
}

/** How the tone comes in and goes out: quickly, gently, or as a row of short beeps. */
@Serializable
enum class CensorEnvelope {
    @SerialName("short") Short,
    @SerialName("soft") Soft,
    @SerialName("pulse") Pulse,
}

/** The tone that replaces censored sound. Saved with the original's field names. */
@Serializable
data class AudioCensorSettings(
    /** Hertz. */
    val frequency: Double = 1000.0,
    /** The tone's peak, in decibels below full scale. */
    val levelDB: Double = -18.0,
    val waveform: CensorWaveform = CensorWaveform.Sine,
    val envelope: CensorEnvelope = CensorEnvelope.Short,
) {
    val valid: Boolean
        get() = frequency.isFinite() && frequency in MINIMUM_FREQUENCY..MAXIMUM_FREQUENCY && levelDB.isFinite() && levelDB in MINIMUM_LEVEL_DB..MAXIMUM_LEVEL_DB

    /** The tone's peak as a factor of full scale. */
    val amplitude: Double get() = AudioLevels.linear(levelDB)

    /**
     * One sample of the tone at full scale, [time] seconds from where its
     * phase starts. The phase comes from the time and not from how many
     * samples were made before, so seeking, playing and exporting give the
     * same samples. The triangle's harmonics stop below half of [sampleRate]
     * and the square's steps are rounded, so neither aliases.
     */
    fun oscillator(time: Double, sampleRate: Double): Double {
        val cycle = (time * frequency) % 1.0
        val phase = 2 * PI * cycle
        if (waveform == CensorWaveform.Sine) return sin(phase)
        if (waveform == CensorWaveform.Square) {
            val t = if (cycle < 0) cycle + 1 else cycle
            val dt = minOf(0.49, frequency / sampleRate)
            fun blep(t: Double): Double {
                if (t < dt) { val x = t / dt; return x + x - x * x - 1 }
                if (t > 1 - dt) { val x = (t - 1) / dt; return x * x + x + x + 1 }
                return 0.0
            }
            return (if (t < 0.5) 1.0 else -1.0) + blep(t) - blep((t + 0.5) % 1.0)
        }
        val maximum = minOf(17, floor(sampleRate * 0.45 / frequency).toInt())
        var value = 0.0
        var scale = 0.0
        for (harmonic in 1..maxOf(1, maximum) step 2) {
            val n = harmonic.toDouble()
            val coefficient = (if ((harmonic / 2) % 2 == 0) 1.0 else -1.0) / (n * n)
            value += coefficient * sin(n * phase)
            scale += abs(coefficient)
        }
        return value / maxOf(1.0, scale)
    }

    /**
     * The tone's level, 0 to 1, [time] seconds into a censor [duration]
     * seconds long: a smooth rise and fall of 8 ms, or 60 ms when soft, never
     * more than half the censor each. Pulsed, it is also cut into beeps of
     * 180 ms every quarter of a second.
     */
    fun envelopeValue(time: Double, duration: Double): Double {
        if (time < 0 || time >= duration) return 0.0
        val fade = minOf(duration / 2, if (envelope == CensorEnvelope.Soft) SOFT_FADE else FADE)
        fun smooth(value: Double): Double { val x = minOf(1.0, maxOf(0.0, value)); return x * x * (3 - 2 * x) }
        var result = smooth(time / maxOf(0.00001, fade)) * smooth((duration - time) / maxOf(0.00001, fade))
        if (envelope == CensorEnvelope.Pulse) {
            val t = time % PULSE_PERIOD
            result *= smooth(t / FADE) * smooth((PULSE_LENGTH - t) / FADE)
        }
        return result
    }

    companion object {
        const val MINIMUM_FREQUENCY = 100.0
        const val MAXIMUM_FREQUENCY = 4000.0
        const val MINIMUM_LEVEL_DB = -36.0
        const val MAXIMUM_LEVEL_DB = -6.0
        const val FADE = 0.008
        const val SOFT_FADE = 0.06
        const val PULSE_PERIOD = 0.25
        const val PULSE_LENGTH = 0.18
    }
}

/**
 * A censored stretch of one item's sound. Times are in the item's own clock,
 * from its start, so the region follows the item when it moves.
 *
 * [phaseOrigin] and [envelopeInterval] say where the whole censor began and
 * ended when it was made. Pieces of one censor, on either side of a split or
 * on overlapping items, keep those and so stay one continuous tone. They may
 * lie outside the item. A null [envelopeInterval] means [interval].
 *
 * Saved with the original's field names; an item holds its regions in
 * `censorRegions`, absent when there are none.
 */
@Serializable
data class AudioCensorRegion(
    /** The censor this is a piece of: shared by the pieces of one action. */
    val id: String = newID(),
    val interval: EditInterval,
    val phaseOrigin: EditTime,
    val settings: AudioCensorSettings,
    val envelopeInterval: EditInterval? = null,
) {
    /** What is left of the region on the piece [part] of its item, in the piece's own clock; null when none of it is there. */
    fun sliced(part: EditInterval): AudioCensorRegion? {
        val intersection = interval.intersection(part) ?: return null
        val envelope = envelopeInterval ?: interval
        return copy(
            interval = EditInterval(intersection.start - part.start, intersection.end - part.start),
            envelopeInterval = EditInterval(envelope.start - part.start, envelope.end - part.start),
            phaseOrigin = phaseOrigin - part.start,
        )
    }
}

/**
 * A stretch of censored sound on the timeline's clock, ready to render. The
 * recorded sound is silent throughout; the tone is heard only where
 * [emitsTone], because one censor over several sounds at once belongs to one of them.
 */
data class AudioCensorSpan(
    val start: Double,
    val end: Double,
    val phaseOrigin: Double,
    val envelopeStart: Double,
    val envelopeEnd: Double,
    val settings: AudioCensorSettings,
    val emitsTone: Boolean,
) {
    /** The share of the recorded sound left at [time]: none inside, back to all of it [EDGE] seconds either side. */
    fun originalGain(time: Double): Double {
        if (time >= start && time < end) return 0.0
        return minOf(1.0, maxOf(0.0, if (time < start) (start - time) / EDGE else (time - end) / EDGE))
    }

    /** The tone's sample at [time], as a share of full scale; 0 outside the span. */
    fun tone(time: Double, sampleRate: Double): Double {
        if (!emitsTone || time < start || time >= end) return 0.0
        return settings.amplitude * settings.envelopeValue(time - envelopeStart, envelopeEnd - envelopeStart) *
            settings.oscillator(time - phaseOrigin, sampleRate)
    }

    companion object {
        /** Seconds over which the recorded sound goes down before a censor and comes back after it. */
        const val EDGE = 0.005
    }
}

/** What censoring needs to know of a sound on the timeline: where it begins, how loud it is, and its regions. */
data class AudioCensorItem(
    val id: String,
    val start: EditTime,
    val censors: List<AudioCensorRegion> = emptyList(),
    val volumeDB: Double = 0.0,
    val muted: Boolean = false,
)

/**
 * Censoring sound: replacing a stretch of it with a tone. The functions keep
 * an item's regions right through every edit and turn them into samples.
 */
object AudioCensor {
    /**
     * The regions of an item at [item] on the timeline after one censor
     * action. The action covers [range] of the timeline, or the whole item
     * when null; where it misses the item nothing changes.
     *
     * Regions under the action are cut back to what lies outside it, or only
     * the region [removingOnly] when given. Then, unless [settings] is null
     * (which only removes), a region with [effectID] is added. Every item
     * censored by one action is given the same id, and their regions share
     * the action's clock even on an item that begins partway through it.
     */
    fun censored(
        regions: List<AudioCensorRegion>, item: EditInterval, settings: AudioCensorSettings?, range: EditInterval? = null,
        effectID: String = newID(), removingOnly: String? = null,
    ): List<AudioCensorRegion> {
        val target = (if (range == null) item else item.intersection(range)) ?: return regions
        val local = EditInterval(target.start - item.start, target.end - item.start)
        val result = ArrayList<AudioCensorRegion>()
        for (region in regions) {
            if ((removingOnly != null && region.id != removingOnly) || region.interval.intersection(local) == null) { result += region; continue }
            val envelope = region.envelopeInterval ?: region.interval
            if (region.interval.start < local.start) {
                result += region.copy(interval = EditInterval(region.interval.start, local.start), envelopeInterval = EditInterval(envelope.start, local.start))
            }
            if (region.interval.end > local.end) {
                result += region.copy(interval = EditInterval(local.end, region.interval.end), envelopeInterval = EditInterval(local.end, envelope.end))
            }
        }
        if (settings != null) {
            val envelope = range?.let { EditInterval(it.start - item.start, it.end - item.start) }
            result += AudioCensorRegion(effectID, local, envelope?.start ?: local.start, settings, envelope)
        }
        return result.sortedBy { it.interval.start }
    }

    /**
     * The regions of the piece [part] of an item, in the item's own clock:
     * for a split, once for each side, and for a trim. A tone cut this way
     * goes on exactly as before.
     */
    fun sliced(regions: List<AudioCensorRegion>, part: EditInterval): List<AudioCensorRegion> = regions.mapNotNull { it.sliced(part) }

    /** The regions of a pasted copy: each censor [fresh] names anew, so changing the copy never changes what it was copied from. */
    fun copied(regions: List<AudioCensorRegion>, fresh: (String) -> String): List<AudioCensorRegion> = regions.map { it.copy(id = fresh(it.id)) }

    /**
     * The regions after the item's playback changes from [old] to [new], as
     * with a change of speed or pauses cut out: each stays on the same
     * recorded sound. A region whose sound is no longer played is dropped,
     * one cut in two by removed footage becomes two. The tone starts afresh
     * in every piece, since its length changed.
     */
    fun retimed(regions: List<AudioCensorRegion>, old: List<EffectPlaybackSpan>, new: List<EffectPlaybackSpan>): List<AudioCensorRegion> {
        val moved = ArrayList<AudioCensorRegion>()
        for (region in regions) {
            // The source seconds the region covered.
            val covered = ArrayList<Pair<Double, Double>>()
            var cursor = EditTime.ZERO
            for (span in old) {
                val length = EditTime.of(span.duration)
                val end = cursor + length
                val part = EditInterval(cursor, end).intersection(region.interval)
                if (part != null && span.end != span.start) covered += source(span, length, part.start - cursor) to source(span, length, part.end - cursor)
                cursor = end
            }
            cursor = EditTime.ZERO
            for (span in new) {
                val length = EditTime.of(span.duration)
                val source = span.end - span.start
                if (source != 0.0) for ((from, to) in covered) {
                    val a = maxOf(from, span.start)
                    val b = minOf(to, span.end)
                    if (b <= a) continue
                    val interval = EditInterval(
                        cursor + EditTime.of(length.seconds * (a - span.start) / source),
                        cursor + EditTime.of(length.seconds * (b - span.start) / source),
                    )
                    if (!interval.isEmpty) moved += region.copy(interval = interval, phaseOrigin = interval.start, envelopeInterval = null)
                }
                cursor += length
            }
        }
        val merged = ArrayList<AudioCensorRegion>()
        for (region in moved.sortedBy { it.interval.start }) {
            val last = merged.lastOrNull()
            if (last != null && last.id == region.id && last.settings == region.settings && last.interval.end == region.interval.start) {
                merged[merged.lastIndex] = last.copy(interval = EditInterval(last.interval.start, region.interval.end))
            } else merged += region
        }
        return merged
    }

    private fun source(span: EffectPlaybackSpan, length: EditTime, offset: EditTime): Double =
        if (length <= EditTime.ZERO) span.start else span.start + (span.end - span.start) * minOf(1.0, maxOf(0.0, offset.seconds / length.seconds))

    /**
     * What a saved item's regions must satisfy on an item [duration] long:
     * inside the item, not empty, not overlapping, an allowed tone, and a
     * censor's own start and end around each piece of it.
     */
    fun valid(regions: List<AudioCensorRegion>, duration: EditTime): Boolean {
        var end = EditTime.ZERO
        for (region in regions.sortedBy { it.interval.start }) {
            val envelope = region.envelopeInterval
            if (region.interval.start < end || region.interval.end > duration || region.interval.isEmpty || !region.settings.valid ||
                abs(region.phaseOrigin.seconds) > EditTime.LIMIT) return false
            if (envelope != null && (envelope.isEmpty || envelope.start > region.interval.start || envelope.end < region.interval.end ||
                    abs(envelope.start.seconds) > EditTime.LIMIT || abs(envelope.end.seconds) > EditTime.LIMIT)) return false
            end = region.interval.end
        }
        return true
    }

    /**
     * The censored stretches of [item] on the timeline, among all the sounds
     * [items] in track order.
     *
     * Where pieces of the same censor, with the same tone, lie over each
     * other on several items, every one of them is silenced but only one
     * plays the tone: the loudest that is not muted, and of equally loud ones
     * the earliest in [items]. Otherwise the same beep would add up and come
     * out louder than it was set. A stretch is divided where that owner changes.
     */
    fun spans(items: List<AudioCensorItem>, item: AudioCensorItem): List<AudioCensorSpan> {
        val order = items.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
        return item.censors.flatMap { region ->
            val whole = EditInterval(item.start + region.interval.start, item.start + region.interval.end)
            val envelope = region.envelopeInterval ?: region.interval
            val others = items.withIndex()
                .filter { (index, other) -> !other.muted && (other.volumeDB > item.volumeDB || (other.volumeDB == item.volumeDB && index < order)) }
                .flatMap { (_, other) ->
                    other.censors.filter { it.id == region.id && it.settings == region.settings }
                        .map { EditInterval(other.start + it.interval.start, other.start + it.interval.end) }
                }.mapNotNull { it.intersection(whole) }
            val points = (listOf(whole.start, whole.end) + others.flatMap { listOf(it.start, it.end) }).distinct().sorted()
            points.zipWithNext { a, b ->
                AudioCensorSpan(
                    start = a.seconds, end = b.seconds, phaseOrigin = (item.start + region.phaseOrigin).seconds,
                    envelopeStart = (item.start + envelope.start).seconds, envelopeEnd = (item.start + envelope.end).seconds,
                    settings = region.settings, emitsTone = others.none { a in it },
                )
            }
        }
    }

    /** The share of an item's recorded sound left at timeline second [time] by its [spans]. */
    fun originalGain(time: Double, spans: List<AudioCensorSpan>): Double {
        var gain = 1.0
        for (span in spans) gain = minOf(gain, span.originalGain(time))
        return gain
    }

    /** The tone an item's [spans] add at timeline second [time], as a share of full scale. */
    fun tone(time: Double, spans: List<AudioCensorSpan>, sampleRate: Double): Double {
        var tone = 0.0
        for (span in spans) tone += span.tone(time, sampleRate)
        return tone
    }

    /**
     * Censors an item's sound in place. [samples] holds [frames] frames of
     * [channels] interleaved samples, the first at timeline second [start].
     *
     * [gains] are the item's own level for each frame, as [AudioEnvelope.fill]
     * writes them: they shape the tone exactly as they shape the recorded
     * sound, so a tone fades, is turned down and is muted with its item.
     * Null leaves the level alone for another stage to apply.
     */
    fun render(
        samples: FloatArray, channels: Int, sampleRate: Double, start: Double, spans: List<AudioCensorSpan>,
        frames: Int = samples.size / channels, gains: DoubleArray? = null,
    ) {
        for (frame in 0 until frames) {
            val time = start + frame / sampleRate
            val level = gains?.get(frame) ?: 1.0
            val gain = (level * originalGain(time, spans)).toFloat()
            val tone = (level * tone(time, spans, sampleRate)).toFloat()
            for (channel in 0 until channels) {
                val index = frame * channels + channel
                samples[index] = samples[index] * gain + tone
            }
        }
    }

    /** The same for 16-bit samples, which cannot go past full scale. */
    fun render(
        samples: ShortArray, channels: Int, sampleRate: Double, start: Double, spans: List<AudioCensorSpan>,
        frames: Int = samples.size / channels, gains: DoubleArray? = null,
    ) {
        for (frame in 0 until frames) {
            val time = start + frame / sampleRate
            val level = gains?.get(frame) ?: 1.0
            val gain = level * originalGain(time, spans)
            val tone = level * tone(time, spans, sampleRate) * Short.MAX_VALUE
            for (channel in 0 until channels) {
                val index = frame * channels + channel
                samples[index] = minOf(Short.MAX_VALUE.toDouble(), maxOf(Short.MIN_VALUE.toDouble(), samples[index] * gain + tone)).toInt().toShort()
            }
        }
    }
}
