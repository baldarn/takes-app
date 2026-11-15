package app.takes.core

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Each stage of the chain is a column with one fader. This is what the fader
 * means: where its level sits for a stage's setting, the setting a level
 * stands for, and how the setting is written. Positions run from 0 at the
 * bottom to 1 at the top. From the original's YouRec/AudioChainControls.swift.
 */
object AudioFader {
    /** The low-cut's fader covers 20 Hz to 500 Hz, evenly by pitch. */
    const val MINIMUM_CUTOFF = 20.0
    const val MAXIMUM_CUTOFF = 500.0

    /** The voice EQ's frequency slider covers 80 Hz to 12 kHz, evenly by pitch. */
    const val MINIMUM_EQ_FREQUENCY = 80.0
    const val MAXIMUM_EQ_FREQUENCY = 12000.0

    private fun unit(value: Double) = min(1.0, max(0.0, value))

    /**
     * Where the fader sits for [effect]: the amount for a neural stage, the
     * cutoff for the low-cut, the decibels for the EQ and the gain
     * (AudioChainControls.swift:34-38).
     */
    fun position(effect: AudioEffect): Double = when {
        effect.kind.isNeural -> unit(effect.amount)
        effect.kind == AudioEffectKind.HighPass -> unit(ln(max(MINIMUM_CUTOFF, effect.frequency) / MINIMUM_CUTOFF) / ln(MAXIMUM_CUTOFF / MINIMUM_CUTOFF))
        else -> unit((effect.amount + AudioEffect.MAXIMUM_DB) / (2 * AudioEffect.MAXIMUM_DB))
    }

    /**
     * [effect] with the fader moved to [position]: amounts in whole percent,
     * cutoffs in whole Hz, decibels in tenths (AudioChainControls.swift:39-46).
     */
    fun setting(effect: AudioEffect, position: Double): AudioEffect {
        val n = unit(position)
        return when {
            effect.kind.isNeural -> effect.copy(amount = roundedAwayFromZero(n * 100) / 100)
            effect.kind == AudioEffectKind.HighPass -> effect.copy(frequency = roundedAwayFromZero(MINIMUM_CUTOFF * (MAXIMUM_CUTOFF / MINIMUM_CUTOFF).pow(n)))
            else -> effect.copy(amount = roundedAwayFromZero((n * 2 * AudioEffect.MAXIMUM_DB - AudioEffect.MAXIMUM_DB) * 10) / 10)
        }
    }

    /** Where the voice EQ's frequency slider sits for [frequency] Hz (AudioChainControls.swift:419). */
    fun eqFrequencyPosition(frequency: Double): Double =
        ln(max(MINIMUM_EQ_FREQUENCY, frequency) / MINIMUM_EQ_FREQUENCY) / ln(MAXIMUM_EQ_FREQUENCY / MINIMUM_EQ_FREQUENCY)

    /** The whole Hz the voice EQ's frequency slider stands for at [position] (AudioChainControls.swift:420). */
    fun eqFrequency(position: Double): Double =
        roundedAwayFromZero(MINIMUM_EQ_FREQUENCY * (MAXIMUM_EQ_FREQUENCY / MINIMUM_EQ_FREQUENCY).pow(position))

    /** The EQ and the gain go both ways from a centre line at 0 dB; the others fill from the bottom. */
    fun isBipolar(kind: AudioEffectKind): Boolean = kind == AudioEffectKind.Equalizer || kind == AudioEffectKind.Gain

    /** Where a new stage of [kind] starts, and where a dragged fader catches. */
    fun center(kind: AudioEffectKind): Double = position(AudioEffect.make(kind, id = ""))

    /**
     * Where the fader is while it is dragged: [origin] is where it was when
     * the drag began and [travel] how far the finger has gone up since, as a
     * fraction of the fader's height. The level catches on the stage's
     * starting value when it comes close (0.4 dB for the EQ and the gain, 1%
     * of the height otherwise) and on both ends (AudioChainControls.swift:356-364).
     */
    fun drag(kind: AudioEffectKind, origin: Double, travel: Double): Double = snap(kind, origin + travel) ?: unit(origin + travel)

    /** The catch point a level of [position] falls on, if any. The UI ticks when this changes to a new one. */
    fun snap(kind: AudioEffectKind, position: Double): Double? {
        val value = unit(position)
        val center = center(kind)
        val tolerance = if (isBipolar(kind)) 0.4 / 48 else 0.01
        return if (value == 0.0 || value == 1.0) value else if (abs(value - center) <= tolerance) center else null
    }

    /**
     * One step for an accessibility service's "increase" or "decrease": 1%, a
     * semitone of cutoff, or half a decibel (AudioChainControls.swift:366).
     */
    fun step(kind: AudioEffectKind): Double = when {
        kind.isNeural -> 0.01
        kind == AudioEffectKind.HighPass -> ln(2.0) / (12 * ln(MAXIMUM_CUTOFF / MINIMUM_CUTOFF))
        else -> 0.5 / 48
    }

    /** [effect] one step up (or down, for a negative [steps]) from fader position [position]. */
    fun stepped(effect: AudioEffect, position: Double, steps: Int): AudioEffect = setting(effect, unit(position + steps * step(effect.kind)))

    private fun fixed(value: BigDecimal, digits: Int): String = value.setScale(digits, RoundingMode.HALF_EVEN).toPlainString()

    /** "80 Hz" below 1 kHz, "1.8 kHz" from there up (AudioChainControls.swift:47-50). */
    fun frequencyText(frequency: Double): String =
        if (!frequency.isFinite()) ""
        else if (frequency >= 1000) fixed(BigDecimal(frequency.toString()).movePointLeft(3), 1) + " kHz"
        else fixed(BigDecimal(frequency.toString()), 0) + " Hz"

    /**
     * The fader's value as written under it: "35%", "80 Hz", "+3.0 dB",
     * "-6.0 dB"; no change is "+0.0 dB" (AudioChainControls.swift:51-55).
     * Digits and the decimal point are always these, whatever the language.
     */
    fun valueText(effect: AudioEffect): String = when {
        effect.kind.isNeural -> fixed(BigDecimal(effect.amount.toString()).movePointRight(2), 0) + "%"
        effect.kind == AudioEffectKind.HighPass -> frequencyText(effect.frequency)
        else -> fixed(BigDecimal(effect.amount.toString()), 1).let { (if (it.startsWith("-")) it else "+$it") + " dB" }
    }

    /** What an accessibility service reads for the fader: the value, and that the stage is bypassed when it is. */
    fun spokenValue(effect: AudioEffect): String = valueText(effect) + if (effect.enabled) "" else ", bypassed"

    /** The short name at the top of the column. */
    fun stripName(kind: AudioEffectKind): String = when (kind) {
        AudioEffectKind.VoiceIsolation -> "Denoise"
        AudioEffectKind.StudioVoice -> "Detail"
        AudioEffectKind.StudioRestoration -> "Studio"
        AudioEffectKind.HighPass -> "Low-cut"
        AudioEffectKind.Equalizer -> "EQ"
        AudioEffectKind.Gain -> "Gain"
        is AudioEffectKind.Unavailable -> "Unknown"
    }

    /** What the fader sets. */
    fun primaryLabel(kind: AudioEffectKind): String = if (kind.isNeural) "Amount" else if (kind == AudioEffectKind.HighPass) "Cutoff" else "Gain"

    /** How the fader is announced: "Output gain", "Noise reduction amount", "Low-cut filter cutoff", "Voice EQ gain". */
    fun faderLabel(kind: AudioEffectKind): String =
        if (kind == AudioEffectKind.Gain) kind.title else if (kind.isNeural) "${kind.title} amount" else "${kind.title} ${primaryLabel(kind).lowercase()}"

    /**
     * The small line under the name: the model for a neural stage, the centre
     * frequency for the EQ, nothing for the gain (AudioChainControls.swift:290-296).
     */
    fun detailText(effect: AudioEffect): String? = when (effect.kind) {
        AudioEffectKind.VoiceIsolation -> if (effect.model == AudioModel.DeepFilter) "DFN3" else "RNNoise"
        AudioEffectKind.StudioVoice -> "LavaSR"
        AudioEffectKind.StudioRestoration -> "VoiceFixer"
        AudioEffectKind.Equalizer -> frequencyText(effect.frequency)
        AudioEffectKind.Gain -> null
        else -> primaryLabel(effect.kind)
    }

    /** The column's color as 0xRRGGBB (AudioChainControls.swift:18-28). */
    fun stripColor(kind: AudioEffectKind): Int = when (kind) {
        AudioEffectKind.VoiceIsolation -> rgb(0.18, 0.83, 0.69)
        AudioEffectKind.StudioVoice -> rgb(0.98, 0.70, 0.29)
        AudioEffectKind.StudioRestoration -> rgb(0.45, 0.71, 0.98)
        AudioEffectKind.HighPass -> rgb(0.73, 0.53, 0.97)
        AudioEffectKind.Equalizer -> rgb(0.34, 0.66, 1.0)
        AudioEffectKind.Gain -> rgb(1.0, 0.44, 0.50)
        is AudioEffectKind.Unavailable -> 0x8E8E93
    }

    private fun rgb(red: Double, green: Double, blue: Double): Int {
        fun byte(value: Double) = roundedAwayFromZero(value * 255).toInt()
        return (byte(red) shl 16) or (byte(green) shl 8) or byte(blue)
    }
}

/**
 * Changes to a chain made from the editor: each returns the new list and
 * leaves the old one alone. Stages keep their ids through every change, so
 * the editor can follow the selected one. From the original's
 * YouRec/AudioChainControls.swift:122-151, 435-446 and YouRec/TrackEditors.swift:311.
 */
object AudioChainEditing {
    /** A new stage of [kind] at the end of the chain. */
    fun add(effects: List<AudioEffect>, kind: AudioEffectKind): List<AudioEffect> = effects + AudioEffect.make(kind)

    /** [effect] in place of the stage with its id. Null when the chain has no such stage. */
    fun replace(effects: List<AudioEffect>, effect: AudioEffect): List<AudioEffect>? {
        val index = effects.indexOfFirst { it.id == effect.id }
        return if (index < 0) null else effects.toMutableList().also { it[index] = effect }
    }

    /**
     * The stage [source] dropped on the stage [target]: it takes the target's
     * place and the stages between move over by one. Null when nothing moves.
     */
    fun move(effects: List<AudioEffect>, source: String, target: String): List<AudioEffect>? {
        val from = effects.indexOfFirst { it.id == source }
        val to = effects.indexOfFirst { it.id == target }
        if (from < 0 || to < 0 || from == to) return null
        return effects.toMutableList().also { it.add(to, it.removeAt(from)) }
    }

    /** Whether the stage at [index] can go one place earlier ([offset] −1) or later (+1). */
    fun canShift(effects: List<AudioEffect>, index: Int, offset: Int): Boolean = index in effects.indices && index + offset in effects.indices

    /** The stage at [index] swapped with its neighbor. Null at the end of the chain. */
    fun shift(effects: List<AudioEffect>, index: Int, offset: Int): List<AudioEffect>? {
        if (!canShift(effects, index, offset)) return null
        return effects.toMutableList().also { val moved = it[index]; it[index] = it[index + offset]; it[index + offset] = moved }
    }

    /** The chain without the stage at [index], and the id to select next: the stage that took its place, or the last. */
    fun remove(effects: List<AudioEffect>, index: Int): Pair<List<AudioEffect>, String?> {
        val next = effects.toMutableList().also { it.removeAt(index) }
        return next to next.getOrNull(min(index, next.size - 1))?.id
    }

    /** The stage switched between on and bypassed. */
    fun toggled(effect: AudioEffect): AudioEffect = effect.copy(enabled = !effect.enabled)

    /** The stage back to the settings it started from. It stays on or bypassed, and keeps its model. */
    fun reset(effect: AudioEffect): AudioEffect = AudioEffect.make(effect.kind, effect.id).copy(enabled = effect.enabled, model = effect.model)

    /** The stage whose settings are shown: the selected one, or the first while nothing valid is selected. */
    fun selected(effects: List<AudioEffect>, selectedID: String?): AudioEffect? = effects.firstOrNull { it.id == selectedID } ?: effects.firstOrNull()

    /**
     * The selection after the chain changed from [before] to [after]: a stage
     * that was just added is selected; a selection that is gone falls back to
     * the first stage; otherwise it stays (AudioChainControls.swift:122-125).
     */
    fun selection(before: List<AudioEffect>, after: List<AudioEffect>, selectedID: String?): String? {
        val old = before.map { it.id }.toSet()
        after.firstOrNull { it.id !in old }?.let { return it.id }
        return if (after.none { it.id == selectedID }) after.firstOrNull()?.id else selectedID
    }

    /** What the editor opens with for a selection that already has chains: the first one. */
    class Opening(val effects: List<AudioEffect>, val mixed: Boolean)

    /**
     * [chains] are the chains found in the selection, in order, and
     * [unprocessed] says some selected sound has none. The selection is mixed
     * when applying would replace chains that differ (YouRec/TrackEditors.swift:213-219).
     */
    fun opening(chains: List<List<AudioEffect>>, unprocessed: Boolean): Opening =
        Opening(chains.firstOrNull() ?: emptyList(), chains.drop(1).any { it != chains.first() } || (unprocessed && chains.isNotEmpty()))
}

/**
 * Listening to a chain against the recording, in the editor. [bypass] plays
 * the selection without the chain; nothing here is saved or exported. With
 * [matchListeningLevels] the two are played equally loud, so that louder does
 * not pass for better; [gains] are the levels measured for that by
 * [AudioListeningLevels]. From YouRec/TrackEditors.swift:162-186, 362-394.
 */
data class AudioChainComparison(
    val effects: List<AudioEffect> = emptyList(),
    val bypass: Boolean = false,
    val matchListeningLevels: Boolean = true,
    val gains: AudioListeningLevels.Gains = AudioListeningLevels.Gains(),
) {
    /** The chain to play right now: none while bypassed. */
    val playing: List<AudioEffect> get() = if (bypass) emptyList() else effects

    /** An enabled output gain is a volume the user asked for, so levels are not matched over it. */
    val allowsMatching: Boolean get() = AudioListeningLevels.allowsMatching(effects)

    /** What the "Match listening levels" switch shows. It cannot be changed while [allowsMatching] is false. */
    val matching: Boolean get() = matchListeningLevels && allowsMatching

    /** Whether the levels of the two versions need measuring before this plays. */
    val needsMeasuring: Boolean get() = !bypass && effects.any { it.enabled } && allowsMatching

    /** The player's volume for what is playing now, as a factor. */
    val listeningVolume: Float get() = if (matching) (if (bypass) gains.original else gains.processed) else 1f

    /** Any change to the chain is heard at once: bypass is switched off. */
    fun changing(effects: List<AudioEffect>): AudioChainComparison = copy(effects = effects, bypass = false)

    fun togglingBypass(): AudioChainComparison = copy(bypass = !bypass)

    /** Switching matching on or off forgets the measured levels. */
    fun matching(on: Boolean): AudioChainComparison = copy(matchListeningLevels = on, gains = AudioListeningLevels.Gains())
}
