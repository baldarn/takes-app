package app.takes.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The five numbers of one filter stage, in the form
 * `y = b0·x + b1·x₁ + b2·x₂ − a1·y₁ − a2·y₂`. The formulas are the original's
 * (YouRec/AudioDSP.swift:13-29): a plain multiplier for the output gain, a
 * 12 dB/octave Butterworth high-pass for the low-cut, and a peaking band one
 * octave or so wide (Q = 1) for the voice EQ.
 */
data class BiquadCoefficients(
    val b0: Double = 1.0,
    val b1: Double = 0.0,
    val b2: Double = 0.0,
    val a1: Double = 0.0,
    val a2: Double = 0.0,
) {
    /** How much a steady tone of [frequency] Hz is raised or lowered, as a factor. */
    fun response(frequency: Double, sampleRate: Double = AudioChain.SAMPLE_RATE.toDouble()): Double {
        val w = 2 * PI * frequency / sampleRate
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val top = sqrt((b0 + b1 * c1 + b2 * c2).pow(2) + (b1 * s1 + b2 * s2).pow(2))
        val bottom = sqrt((1 + a1 * c1 + a2 * c2).pow(2) + (a1 * s1 + a2 * s2).pow(2))
        return top / bottom
    }

    companion object {
        /**
         * The filter for a low-cut, voice EQ or output gain stage. The frequency
         * is held between 20 Hz and 45% of the sample rate. A neural or unknown
         * kind is not a filter and is refused.
         */
        fun of(effect: AudioEffect, sampleRate: Double = AudioChain.SAMPLE_RATE.toDouble()): BiquadCoefficients {
            val frequency = min(sampleRate * 0.45, max(20.0, effect.frequency))
            val w = 2 * PI * frequency / sampleRate
            val c = cos(w); val s = sin(w)
            return when (effect.kind) {
                AudioEffectKind.Gain -> BiquadCoefficients(b0 = 10.0.pow(effect.amount / 20))
                AudioEffectKind.HighPass -> {
                    val alpha = s / (2 * sqrt(0.5)); val a0 = 1 + alpha
                    val b0 = (1 + c) / 2 / a0
                    BiquadCoefficients(b0, -(1 + c) / a0, b0, -2 * c / a0, (1 - alpha) / a0)
                }
                AudioEffectKind.Equalizer -> {
                    val amplitude = 10.0.pow(effect.amount / 40); val alpha = s / 2
                    val a0 = 1 + alpha / amplitude
                    BiquadCoefficients((1 + alpha * amplitude) / a0, -2 * c / a0, (1 - alpha * amplitude) / a0, -2 * c / a0, (1 - alpha / amplitude) / a0)
                }
                else -> throw IllegalArgumentException("Unsupported direct DSP stage: ${effect.kind.rawValue}")
            }
        }
    }
}

/**
 * Runs the filter stages of a chain over sound, one sample at a time. It
 * remembers each channel's state, so a recording can be fed in blocks of any
 * size and the result is the same as feeding it whole. Stages that are not
 * enabled are left out, which makes bypass exact: the samples come back
 * untouched. Sound is expected at 48 kHz.
 *
 * Only filters may be enabled in [effects]; a neural stage is refused, as in
 * the original (`AudioChainProcessor`, YouRec/AudioDSP.swift:5-50). To run a
 * whole chain, models included, use [AudioChain.render]. To start over on
 * other sound, make a new processor.
 */
class AudioChainProcessor(effects: List<AudioEffect>, sampleRate: Double = AudioChain.SAMPLE_RATE.toDouble(), val channels: Int = 2) {
    private val coefficients: List<BiquadCoefficients> = effects.filter { it.enabled }.map { BiquadCoefficients.of(it, sampleRate) }
    private val z1 = Array(coefficients.size) { DoubleArray(channels) }
    private val z2 = Array(coefficients.size) { DoubleArray(channels) }

    /** The loudest sample produced so far, on any channel. */
    var peak: Float = 0f
        private set

    /** The next sample of [channel]. A result that is not a number becomes silence. */
    fun sample(value: Float, channel: Int): Float {
        var output = value.toDouble()
        for (index in coefficients.indices) {
            val stage = coefficients[index]
            val input = output
            output = stage.b0 * input + z1[index][channel]
            z1[index][channel] = stage.b1 * input - stage.a1 * output + z2[index][channel]
            z2[index][channel] = stage.b2 * input - stage.a2 * output
        }
        val result = if (output.isFinite()) output.toFloat() else 0f
        peak = max(peak, abs(result))
        return result
    }

    /** The next [count] samples of [channel], replaced in [samples] starting at [offset]. */
    fun process(samples: FloatArray, channel: Int, offset: Int = 0, count: Int = samples.size - offset) {
        for (index in offset until offset + count) samples[index] = sample(samples[index], channel)
    }

    /** The next frames of all channels, replaced in [samples], which holds them interleaved. */
    fun processInterleaved(samples: FloatArray, offset: Int = 0, count: Int = samples.size - offset) {
        for (index in 0 until count) samples[offset + index] = sample(samples[offset + index], index % channels)
    }
}

/**
 * The part of the chain that needs a model. The core has none; the app
 * supplies this, backed by whatever models the device has.
 */
interface AudioModelStage {
    /** Whether this device can run [effect], a neural stage: its kind and its exact [AudioEffect.model]. */
    fun supports(effect: AudioEffect): Boolean

    /**
     * [channels] with [effect] applied: as many channels, each as long as it
     * was, at 48 kHz. [AudioEffect.amount] is how much of the model's sound
     * replaces the input; [AudioChain.mix] does that blend. The input arrays
     * must not be changed. [progress] goes from 0 to 1.
     */
    fun process(effect: AudioEffect, channels: List<FloatArray>, progress: (Double) -> Unit): List<FloatArray>
}

class AudioChainException(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        /** An enabled stage cannot be played here: unknown kind or version, or a model this device lacks. */
        UnavailableModel,

        /** The sound is not something the chain can process, or a model returned something else than it was given. */
        InvalidFormat,
    }
}

object AudioChain {
    /** Every stage works at this rate; sound at another rate is converted first. */
    const val SAMPLE_RATE = 48000

    /** The result of [render]: new arrays, and the loudest sample among them. */
    class Rendered(val channels: List<FloatArray>, val peak: Float)

    /**
     * The stages that change the sound, in order: enabled, and not a neural
     * stage turned down to nothing (YouRec/OfflineAudioPipeline.swift:44).
     */
    fun active(effects: List<AudioEffect>): List<AudioEffect> = effects.filter { it.enabled && !(it.kind.isNeural && it.amount == 0.0) }

    /** Whether a chain may be stored: every stage valid and no id used twice. */
    fun valid(effects: List<AudioEffect>): Boolean = effects.all { it.valid } && effects.map { it.id }.toSet().size == effects.size

    /**
     * Whether [effect] can be played here. A bypassed stage never stops a
     * chain; its settings are simply kept.
     */
    fun playable(effect: AudioEffect, models: AudioModelStage? = null): Boolean = when {
        !effect.enabled -> true
        !effect.supported -> false
        effect.kind.isNeural && effect.amount != 0.0 -> models?.supports(effect) == true
        else -> true
    }

    /**
     * Runs the whole chain over [channels], the channels of one piece of sound
     * at 48 kHz, all the same length. Each stage hears the result of the one
     * before it and starts with no memory, as in the original
     * (YouRec/OfflineAudioPipeline.swift:41-104). Samples are single precision
     * between stages, so the last digit can differ from one [AudioChainProcessor]
     * holding all the filters. The input is left untouched; with nothing active
     * the result is an exact copy.
     *
     * Throws [AudioChainException] before doing any work when an enabled stage
     * cannot be played here.
     */
    fun render(
        effects: List<AudioEffect>,
        channels: List<FloatArray>,
        models: AudioModelStage? = null,
        progress: (Double) -> Unit = {},
    ): Rendered {
        if (!effects.all { playable(it, models) }) throw AudioChainException(AudioChainException.Reason.UnavailableModel)
        if (channels.isEmpty() || channels.any { it.size != channels[0].size }) throw AudioChainException(AudioChainException.Reason.InvalidFormat)
        val stages = active(effects)
        var current = channels.map { it.copyOf() }
        stages.forEachIndexed { index, effect ->
            val report = { fraction: Double -> progress((index + fraction) / stages.size) }
            if (effect.kind.isNeural) {
                val result = models!!.process(effect, current, report)
                if (result.size != current.size || result.indices.any { result[it].size != current[it].size }) {
                    throw AudioChainException(AudioChainException.Reason.InvalidFormat)
                }
                current = result
            } else {
                val processor = AudioChainProcessor(listOf(effect), channels = current.size)
                current.forEachIndexed { channel, samples -> processor.process(samples, channel) }
                report(1.0)
            }
        }
        progress(1.0)
        return Rendered(current, peak(current))
    }

    /** [render] for sound with a single channel. */
    fun render(effects: List<AudioEffect>, channel: FloatArray, models: AudioModelStage? = null): FloatArray =
        render(effects, listOf(channel), models).channels[0]

    /**
     * How a neural stage's amount blends the model's sound into the recording:
     * `dry + amount × (wet − dry)` on each sample
     * (YouRec/OfflineAudioPipeline.swift:164). At 0 the result is the recording.
     */
    fun mix(dry: FloatArray, wet: FloatArray, amount: Float): FloatArray {
        require(dry.size == wet.size)
        return FloatArray(dry.size) { dry[it] + amount * (wet[it] - dry[it]) }
    }

    /** The loudest sample, as a positive number. 1 is full scale. */
    fun peak(channels: List<FloatArray>): Float {
        var peak = 0f
        for (samples in channels) for (value in samples) peak = max(peak, abs(value))
        return peak
    }

    /**
     * A name for the sound a chain produces: equal for two chains exactly when
     * they have the same enabled stages (ids included) with the same settings,
     * in the same order. Bypassed stages do not count, so turning one off and
     * on again finds the sound already made (YouRec/TrackRenderer.swift:27-30).
     */
    fun signature(effects: List<AudioEffect>): String =
        sha256(documentJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(AudioEffect.serializer()), effects.filter { it.enabled }).toByteArray())
}
