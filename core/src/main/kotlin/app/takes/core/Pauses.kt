package app.takes.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * A stretch of immutable source media, in source seconds. Timeline time can
 * change after a speed edit, pause removal, split or reorder without losing it.
 */
@Serializable
data class SourceInterval(val start: Double, val end: Double) {
    val duration: Double get() = maxOf(0.0, end - start)
}

/** A silent stretch found by the detector. [enabled] false is the user's Keep choice. */
@Serializable
data class DetectedPause(val raw: SourceInterval, val enabled: Boolean = true)

@Serializable
enum class PauseLevelMode {
    @SerialName("automatic") Automatic,
    @SerialName("manual") Manual,
}

/**
 * Pause removal on one clip. [cuts] are the source intervals actually removed;
 * [candidates] keep every detected pause and its Cut/Keep choice for re-editing.
 * A null [levelMode] is a detection saved before RMS levels existed: it keeps
 * comparing its manual threshold against peaks.
 */
@Serializable(with = PauseSettingsSerializer::class)
data class PauseSettings(
    val id: String = newID(),
    val thresholdDB: Double = -38.0,
    val minimum: Double = 0.6,
    val before: Double = 0.12,
    val after: Double = 0.18,
    val cuts: List<SourceInterval> = emptyList(),
    val candidates: List<DetectedPause>? = null,
    val analyzed: SourceInterval? = null,
    val levelMode: PauseLevelMode? = PauseLevelMode.Automatic,
)

/**
 * The saved shape of [PauseSettings]. Same fields, but an absent levelMode
 * reads as null instead of the constructor's Automatic, as in the original app.
 */
@Serializable
private data class StoredPauseSettings(
    val id: String = newID(),
    val thresholdDB: Double = -38.0,
    val minimum: Double = 0.6,
    val before: Double = 0.12,
    val after: Double = 0.18,
    val cuts: List<SourceInterval> = emptyList(),
    val candidates: List<DetectedPause>? = null,
    val analyzed: SourceInterval? = null,
    val levelMode: PauseLevelMode? = null,
)

internal object PauseSettingsSerializer : KSerializer<PauseSettings> {
    override val descriptor: SerialDescriptor = StoredPauseSettings.serializer().descriptor

    override fun serialize(encoder: Encoder, value: PauseSettings) = encoder.encodeSerializableValue(
        StoredPauseSettings.serializer(),
        StoredPauseSettings(value.id, value.thresholdDB, value.minimum, value.before, value.after, value.cuts, value.candidates, value.analyzed, value.levelMode),
    )

    override fun deserialize(decoder: Decoder): PauseSettings = decoder.decodeSerializableValue(StoredPauseSettings.serializer()).run {
        PauseSettings(id, thresholdDB, minimum, before, after, cuts, candidates, analyzed, levelMode)
    }
}

/**
 * Audio levels of one source in equal slices of time, each 0–1 of full scale.
 * [rms] has one energy value per peak; older peak-only waveforms leave it null.
 * [speech] is one voice probability per slice, 0–255, measured for Pauses only.
 * The detector's speech and onset rules assume 10 ms slices.
 */
class SourceWaveform(
    val duration: Double,
    val peaks: FloatArray,
    val rms: FloatArray? = null,
    val speech: ByteArray? = null,
) {
    fun peak(time: Double): Float {
        if (peaks.isEmpty() || !(time >= 0) || !(time < duration)) return 0f
        return peaks[minOf(peaks.size - 1, (time / duration * peaks.size).toInt())]
    }

    companion object {
        /** A peak-only waveform from [WaveformBuilder] bytes. It has no RMS, so only a manual peak threshold can use it. */
        fun fromPeaks(peaks: ByteArray, peaksPerSecond: Int = WaveformBuilder.PEAKS_PER_SECOND): SourceWaveform =
            SourceWaveform(peaks.size.toDouble() / peaksPerSecond, FloatArray(peaks.size) { (peaks[it].toInt() and 0xFF) / 255f })
    }
}

/**
 * Finds the quiet stretches of a clip. Times are source seconds; [rate] is the
 * clip's constant playback speed, so minimum length and padding are measured
 * in the seconds the viewer hears.
 */
object PauseDetector {
    /** One addressable frame of the timeline. */
    const val FRAME = 1.0 / 30

    /** The threshold in dBFS: estimated from the recording in automatic mode, else the stored one. */
    fun levelDB(wave: SourceWaveform, settings: PauseSettings): Double {
        val levels = wave.rms
        if (settings.levelMode != PauseLevelMode.Automatic || levels == null || levels.isEmpty()) return settings.thresholdDB
        val sorted = levels.sortedArray()
        fun db(percentile: Int) = decibels(sorted[(sorted.size - 1) * percentile / 100])
        val floorDB = db(10)
        var speechDB = db(90)
        // A take can be mostly steady room tone with only a few spoken words.
        // A sustained louder passage qualifies as a reference; a lone click cannot.
        if (speechDB - floorDB < 10 && db(50) - floorDB < 5 && wave.duration > 0) {
            val block = maxOf(1, ceil(0.2 * levels.size / wave.duration).toInt())
            var loudest = 0f
            var i = 0
            while (i <= levels.size - block) {
                var quietest = levels[i]
                for (j in i + 1 until i + block) quietest = minOf(quietest, levels[j])
                loudest = maxOf(loudest, quietest)
                i += block
            }
            speechDB = maxOf(speechDB, decibels(loudest))
        }
        // Leave headroom above room tone and stay well below the stronger voice.
        return rounded(maxOf(-56.0, minOf(-27.0, floorDB + 8, speechDB - 18)))
    }

    /** The pauses as detected, before any padding. These are what the user reviews. */
    fun raw(wave: SourceWaveform, clip: TimelineClip, settings: PauseSettings, levelDB: Double? = null, rate: Double = 1.0): List<SourceInterval> =
        detect(wave, clip, settings.copy(before = 0.0, after = 0.0), levelDB, rate)

    /** [raw] shortened by the padding, without regard to any clip. */
    fun padded(raw: SourceInterval, rate: Double, settings: PauseSettings): SourceInterval {
        val a = minOf(raw.end, raw.start + settings.before * rate)
        return SourceInterval(a, maxOf(a, raw.end - settings.after * rate))
    }

    /** [raw] shortened by the padding, measured on the clip's own timeline and kept inside the clip. */
    fun padded(raw: SourceInterval, clip: TimelineClip, settings: PauseSettings, rate: Double = 1.0): SourceInterval {
        val a = minOf(raw.end, sourceTime(clip, rate, timelineTime(clip, rate, raw.start) + settings.before))
        val b = maxOf(a, sourceTime(clip, rate, maxOf(0.0, timelineTime(clip, rate, raw.end) - settings.after)))
        return SourceInterval(a, b)
    }

    /** What is really removed: enabled candidates, padded, clipped to the clip and merged. */
    fun effectiveCuts(candidates: List<DetectedPause>, clip: TimelineClip, settings: PauseSettings, rate: Double = 1.0): List<SourceInterval> {
        val frame = FRAME * rate
        val proposed = candidates.filter { it.enabled }.map {
            val padded = padded(it.raw, clip, settings, rate)
            SourceInterval(maxOf(clip.start, padded.start), minOf(clip.end, padded.end))
        }.filter { it.duration >= frame - 0.000001 }.sortedBy { it.start }
        val cuts = mutableListOf<SourceInterval>()
        for (cut in proposed) {
            val last = cuts.lastOrNull()
            if (last != null && cut.start <= last.end) cuts[cuts.lastIndex] = last.copy(end = maxOf(last.end, cut.end))
            else cuts += cut
        }
        // Preserve one addressable frame even with zero padding on a silent
        // clip. The UI summary and the saved edit use these same intervals.
        val excess = cuts.sumOf { it.duration } - maxOf(0.0, maxOf(0.0, clip.end - clip.start) - frame)
        if (excess > 0 && cuts.isNotEmpty()) cuts[cuts.lastIndex] = cuts.last().let { it.copy(end = it.end - excess) }
        return cuts.filter { it.duration >= frame - 0.000001 }
    }

    /** The parts of the clip that remain after [cuts], in source order. */
    fun keptIntervals(clip: TimelineClip, cuts: List<SourceInterval>): List<SourceInterval> {
        val result = mutableListOf<SourceInterval>()
        var cursor = clip.start
        for (cut in cuts.sortedBy { it.start }) {
            val a = maxOf(clip.start, cut.start)
            val b = minOf(clip.end, cut.end)
            if (b <= a) continue
            if (a > cursor) result += SourceInterval(cursor, a)
            cursor = maxOf(cursor, b)
        }
        if (cursor < clip.end) result += SourceInterval(cursor, clip.end)
        return result
    }

    fun detect(wave: SourceWaveform, clip: TimelineClip, settings: PauseSettings, levelDB: Double? = null, rate: Double = 1.0): List<SourceInterval> {
        val levels = (if (settings.levelMode == null) wave.peaks else wave.rms) ?: return emptyList()
        if (levels.isEmpty() || !(wave.duration > 0)) return emptyList()
        val step = wave.duration / levels.size
        val threshold = 10.0.pow((levelDB ?: levelDB(wave, settings)) / 20).toFloat()
        val result = mutableListOf<SourceInterval>()
        var beginning: Int? = null

        fun append(from: Int, to: Int) {
            val start = from * step
            val time = minOf(clip.end, to * step)
            if (timelineTime(clip, rate, time) - timelineTime(clip, rate, start) < settings.minimum) return
            val interval = padded(SourceInterval(start, time), clip, settings, rate)
            if (timelineTime(clip, rate, interval.end) - timelineTime(clip, rate, interval.start) >= FRAME) result += interval
        }

        val first = maxOf(0, ceil(clip.start / step).toInt())
        val last = minOf(levels.size, floor(clip.end / step).toInt())
        if (first >= last) return result

        // Establish that this is dialogue before relaxing the energy gate. For music,
        // steady room tone or an unvoiced selection, keep the ordinary silence test.
        val speech = wave.speech?.takeIf { settings.levelMode == PauseLevelMode.Automatic && it.size == levels.size }
        var hasDialogue = false
        if (speech != null) {
            var voiced = 0
            val reference = maxOf(1, ceil(0.16 / step).toInt())
            for (i in first until last) {
                voiced = if (probability(speech, i) >= 179) voiced + 1 else 0
                if (voiced >= reference) { hasDialogue = true; break }
            }
        }
        // Let steady noise exceed the normal threshold by up to 12 dB, bounded
        // at -15 dBFS. A likely voice protects even a quiet syllable.
        val noiseLimit = minOf(0.177828f, threshold * 3.981072f)

        fun close(end: Int) {
            val start = beginning ?: return
            beginning = null
            if (!hasDialogue || end - start < 5 || (start until end).none { levels[it] > threshold }) { append(start, end); return }
            // A faint utterance can slip past the voice classifier. Protect sustained
            // onsets that rise 7 dB above this gap's own background. Three of five
            // 10 ms bins distinguish a short syllable from an isolated noise spike.
            val sorted = levels.copyOfRange(start, end).apply { sort() }
            val onset = maxOf(threshold, sorted[sorted.size / 2] * 2.23872f)
            var elevated = 0
            var cursor = start
            for (i in start until end + 2) {
                if (i < end && levels[i] > onset) elevated++
                if (i - 5 >= start && levels[i - 5] > onset) elevated--
                if (i >= start + 2 && elevated >= 3) {
                    val protected = i - 2
                    if (cursor < protected) append(cursor, protected)
                    cursor = protected + 1
                }
            }
            if (cursor < end) append(cursor, end)
        }

        for (index in first until last) {
            var quiet = levels[index] <= threshold
            if (hasDialogue && speech != null) {
                val probability = probability(speech, index)
                quiet = (quiet && probability < 102) || (levels[index] <= noiseLimit && probability < 31)
            }
            if (quiet) { if (beginning == null) beginning = index } else close(index)
        }
        close(last)
        return result
    }

    private fun probability(speech: ByteArray, index: Int): Int = speech[index].toInt() and 0xFF

    private fun decibels(level: Float): Double = 20 * log10(maxOf(1e-8, level.toDouble()))

    /** Half away from zero, as the original rounds; Math.round would send -32.5 to -32. */
    private fun rounded(value: Double): Double = if (value < 0) -floor(abs(value) + 0.5) else floor(value + 0.5)

    // A clip plays [start, end) of its source at a constant rate.
    private fun timelineTime(clip: TimelineClip, rate: Double, source: Double): Double =
        maxOf(0.0, minOf(clip.end, source) - clip.start) / rate

    private fun sourceTime(clip: TimelineClip, rate: Double, offset: Double): Double =
        minOf(clip.end, clip.start + maxOf(0.0, offset) * rate)
}
