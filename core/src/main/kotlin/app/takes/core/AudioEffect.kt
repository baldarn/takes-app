package app.takes.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * What one stage of the "Enhance audio" chain does. Saved as its name, as in
 * the original app (`EffectKind`, YouRec/Project.swift:63-97). A name this
 * version does not know is kept as [Unavailable], so a project made by a newer
 * version is read, shown and saved back without losing the stage.
 */
@Serializable(with = AudioEffectKindSerializer::class)
sealed class AudioEffectKind(val rawValue: String) {
    /** Noise reduction, by RNNoise or DeepFilterNet3. */
    data object VoiceIsolation : AudioEffectKind("voiceIsolation")

    /** Speech detail restoration, by LavaSR. */
    data object StudioVoice : AudioEffectKind("studioVoice")

    /** Studio restoration, by VoiceFixer. */
    data object StudioRestoration : AudioEffectKind("studioRestoration")

    /** Low-cut filter: removes rumble below [AudioEffect.frequency]. */
    data object HighPass : AudioEffectKind("highPass")

    /** Voice EQ: one band, [AudioEffect.amount] dB at [AudioEffect.frequency]. */
    data object Equalizer : AudioEffectKind("equalizer")

    /** Output gain, [AudioEffect.amount] dB. */
    data object Gain : AudioEffectKind("gain")

    /** A stage saved by a version of the app that has kinds this one lacks. */
    data class Unavailable(val name: String) : AudioEffectKind(name)

    val isSupported: Boolean get() = this !is Unavailable

    /** Needs a model: the three that are not plain filters. */
    val isNeural: Boolean get() = this == VoiceIsolation || this == StudioVoice || this == StudioRestoration

    val title: String
        get() = when (this) {
            VoiceIsolation -> "Noise reduction"
            StudioVoice -> "Speech detail restoration"
            StudioRestoration -> "Studio restoration"
            HighPass -> "Low-cut filter"
            Equalizer -> "Voice EQ"
            Gain -> "Output gain"
            is Unavailable -> "Unavailable filter"
        }

    companion object {
        /** The kinds a chain can be built from, in the order the "Add filter" menu lists them. */
        val all: List<AudioEffectKind> by lazy { listOf(VoiceIsolation, StudioVoice, StudioRestoration, HighPass, Equalizer, Gain) }

        fun of(rawValue: String): AudioEffectKind = all.firstOrNull { it.rawValue == rawValue } ?: Unavailable(rawValue)
    }
}

internal object AudioEffectKindSerializer : KSerializer<AudioEffectKind> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("app.takes.core.AudioEffectKind", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: AudioEffectKind) = encoder.encodeString(value.rawValue)
    override fun deserialize(decoder: Decoder): AudioEffectKind = AudioEffectKind.of(decoder.decodeString())
}

/**
 * Exactly which model, weights and runtime produced a stage's sound, so a
 * project never plays a different model's result under the same name.
 * The identities are the original's (YouRec/Project.swift:99-115); they name
 * its iOS runtimes, and are kept so its projects read back unchanged.
 */
@Serializable
data class AudioModel(val id: String, val weightsSHA256: String, val runtime: String) {
    companion object {
        val RNNoise = AudioModel(
            "rnnoise-v0.1-cdf196b1",
            "f0cdb52b30501aab489f90fedbc7a023c719d91b337db2da7f26fc3036556b95",
            "yourec-rnnoise-c-v1",
        )
        val Lava = AudioModel(
            "lavasr-v2-33ac0408",
            "1ef7557e72c9b2ee78ccb35cf3ab3400c0c73be1838ab16ff7dde52934f4d2cf",
            "yourec-coreml-fp32-lava-dsp-v1",
        )
        val VoiceFixer = AudioModel(
            "voicefixer-aae2253c",
            "67971970d18c06c1220dc63df11aa0186f787dc961c829dcd1624dad3d612360",
            "yourec-voicefixer-coreml-44100-context256-overlap-v2",
        )
        val DeepFilter = AudioModel(
            "deepfilternet3-dfc12319",
            "1114ff972e25679e7f9707da85eee62659e1bdf50199035ecacba12e4ec0425d",
            "yourec-dfn3-coreml-cpu-v1-dsp3dd66dea-milc2141d48",
        )
    }
}

/**
 * One stage of the chain. A chain is a list of these, applied in order; the
 * same kind may appear more than once and each keeps its own settings.
 *
 * [amount] is decibels for the voice EQ and the output gain (−24…24), and how
 * much of the model's sound replaces the recording for the neural kinds (0…1).
 * [frequency] is the low-cut's cutoff or the EQ's centre, in Hz. A stage that
 * is not [enabled] is bypassed: it stays in the chain and changes nothing.
 *
 * Field names and values are the original's `AudioEffect`
 * (YouRec/Project.swift:119-139).
 */
@Serializable
data class AudioEffect(
    val id: String = newID(),
    val kind: AudioEffectKind,
    val enabled: Boolean = true,
    val amount: Double = 0.0,
    val frequency: Double = 1800.0,
    val implementationVersion: Int = 1,
    /** Null for the filters, and in projects saved before stages had models. */
    val model: AudioModel? = null,
) {
    /**
     * Whether this version of the document format knows how to play the stage.
     * A neural stage at amount 0 changes nothing, so its model does not matter.
     * Whether this device has the model is a separate question: see [AudioModelStage.supports].
     */
    val supported: Boolean
        get() {
            if (!kind.isSupported || implementationVersion != 1) return false
            return when (kind) {
                AudioEffectKind.VoiceIsolation -> amount == 0.0 || model == AudioModel.RNNoise || model == AudioModel.DeepFilter
                AudioEffectKind.StudioVoice -> amount == 0.0 || model == AudioModel.Lava
                AudioEffectKind.StudioRestoration -> amount == 0.0 || model == AudioModel.VoiceFixer
                else -> true
            }
        }

    /**
     * Whether a project may hold this stage (YouRec/Project.swift:520-522,
     * YouRec/TrackTimeline.swift:1244). An unsupported stage keeps whatever
     * amount it was saved with.
     */
    val valid: Boolean
        get() = implementationVersion > 0 && amount.isFinite() && frequency.isFinite() &&
            frequency in MINIMUM_FREQUENCY..MAXIMUM_FREQUENCY && (!supported || amount in amountRange(kind))

    companion object {
        const val MINIMUM_FREQUENCY = 20.0
        const val MAXIMUM_FREQUENCY = 20000.0
        const val MAXIMUM_DB = 24.0

        /** The amounts a supported stage of [kind] may have. */
        fun amountRange(kind: AudioEffectKind): ClosedFloatingPointRange<Double> = if (kind.isNeural) 0.0..1.0 else -MAXIMUM_DB..MAXIMUM_DB

        /** A new stage with the settings it starts from, which are also what Reset returns to. */
        fun make(kind: AudioEffectKind, id: String = newID()): AudioEffect = AudioEffect(
            id = id,
            kind = kind,
            amount = if (kind == AudioEffectKind.StudioVoice) 0.35 else if (kind.isNeural) 1.0 else 0.0,
            frequency = if (kind == AudioEffectKind.HighPass) 80.0 else 1800.0,
            model = when (kind) {
                AudioEffectKind.VoiceIsolation -> AudioModel.RNNoise
                AudioEffectKind.StudioVoice -> AudioModel.Lava
                AudioEffectKind.StudioRestoration -> AudioModel.VoiceFixer
                else -> null
            },
        )
    }
}

/**
 * A chain applied to part of one piece of sound, as the original stores it on
 * its audio items (`AudioEnhancementRegion`, YouRec/TrackTimeline.swift:126-131).
 * The original writes `interval` as a pair of tick counts of its own time
 * type; here the bounds are seconds from the start of the piece, which is what
 * this app's timeline uses.
 */
@Serializable
data class AudioEnhancement(
    val id: String = newID(),
    val start: Double,
    val end: Double,
    val effects: List<AudioEffect>,
) {
    /** Stage ids are unique within a chain; the chain may be any length, including empty. */
    val valid: Boolean
        get() = start.isFinite() && end.isFinite() && start >= 0 && end > start && AudioChain.valid(effects)
}
