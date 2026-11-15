package app.takes.core

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Playing a chain and the recording equally loud while they are compared.
 * Both are measured in short windows; only windows where the voice survives
 * in both count, so the noise a denoiser removed never turns the recording
 * down. From the original's YouRec/OfflineAudioPipeline.swift:202-304.
 */
object AudioListeningLevels {
    /** Seconds in one measured window. */
    const val WINDOW = 0.4

    /** Seconds heard before and after the playhead when a chain is previewed. */
    const val PREVIEW_REACH = 5.0

    /** Volume factors for the two versions; the louder one is turned down, never below an eighth. */
    data class Gains(val original: Float = 1f, val processed: Float = 1f)

    /** One measured stretch: mean energy of each version and how long it is heard. */
    data class Window(val original: Float, val processed: Float, val weight: Double)

    /** An enabled output gain is a volume the user asked for; matching would cancel it. */
    fun allowsMatching(effects: List<AudioEffect>): Boolean = effects.none { it.enabled && it.kind == AudioEffectKind.Gain }

    /**
     * What is prepared for listening: up to five seconds either side of
     * [position], never leaving [selection] (YouRec/EffectApplication.swift:9-12).
     */
    fun listeningRange(position: Double, selection: ClosedFloatingPointRange<Double>): ClosedFloatingPointRange<Double> {
        val anchor = min(selection.endInclusive, max(selection.start, position))
        return max(selection.start, anchor - PREVIEW_REACH)..min(selection.endInclusive, anchor + PREVIEW_REACH)
    }

    /**
     * The mean energy of each [WINDOW] of [samples], which holds [channels]
     * channels interleaved at [sampleRate]. The last window may be shorter.
     * A sample that is not a number is refused.
     */
    fun energies(samples: FloatArray, channels: Int = 2, sampleRate: Int = AudioChain.SAMPLE_RATE): FloatArray {
        val window = (sampleRate * WINDOW).toInt() * channels
        val result = ArrayList<Float>(samples.size / window + 1)
        var energy = 0.0
        var count = 0
        for (value in samples) {
            if (!value.isFinite()) throw AudioChainException(AudioChainException.Reason.InvalidFormat)
            energy += value.toDouble() * value.toDouble(); count += 1
            if (count == window) { result += (energy / count).toFloat(); energy = 0.0; count = 0 }
        }
        if (count > 0) result += (energy / count).toFloat()
        return result.toFloatArray()
    }

    /** Gains for two versions of the same stretch, measured window by window from its start. */
    fun gains(original: FloatArray, processed: FloatArray): Gains =
        gains((0 until min(original.size, processed.size)).map { Window(original[it], processed[it], WINDOW) })

    /**
     * Gains for the parts of a recording that are actually heard: [spans] are
     * its source seconds in use, and [original] and [processed] its energies
     * from the beginning. A window counts for as long as it plays.
     */
    fun gains(spans: List<EffectPlaybackSpan>, original: FloatArray, processed: FloatArray): Gains {
        val windows = ArrayList<Window>()
        for (span in spans) {
            for (index in (span.start / WINDOW).toInt() until ceil(span.end / WINDOW).toInt()) {
                if (index !in original.indices || index !in processed.indices) continue
                val weight = max(0.0, min(span.end, (index + 1) * WINDOW) - max(span.start, index * WINDOW))
                windows += Window(original[index], processed[index], weight / span.rate)
            }
        }
        return gains(windows)
    }

    /**
     * Windows below −60 dBFS in either version are silence; windows the chain
     * lowered by more than 20 dB are removed noise. Of the rest, those within
     * 10 dB of the mean are taken as voice, and the louder version is turned
     * down to the quieter one. With nothing to compare, nothing is changed.
     */
    fun gains(windows: List<Window>): Gains {
        val audible = windows.filter { it.original > 0.000001f && it.processed > 0.000001f && it.processed >= it.original * 0.01f }
        val duration = audible.sumOf { it.weight }
        if (!(duration > 0)) return Gains()
        val mean = audible.sumOf { it.original.toDouble() * it.weight } / duration
        val processedMean = audible.sumOf { it.processed.toDouble() * it.weight } / duration
        val speech = audible.filter { it.original.toDouble() >= mean * 0.1 && it.processed.toDouble() >= processedMean * 0.1 }
        val original = speech.sumOf { it.original.toDouble() * it.weight }
        val processed = speech.sumOf { it.processed.toDouble() * it.weight }
        if (!(original > 0 && processed > 0)) return Gains()
        val target = min(original, processed)
        return Gains(max(0.125, sqrt(target / original)).toFloat(), max(0.125, sqrt(target / processed)).toFloat())
    }
}
