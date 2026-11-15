package app.takes.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.pow

/** Every way one clip can give way to the next. */
@Serializable
enum class TransitionStyle(val title: String) {
    @SerialName("dissolve") Dissolve("Dissolve"),

    /** Saved as "fadeBlack": the name it had before the color could be chosen. */
    @SerialName("fadeBlack") FadeColor("Fade through color"),

    /** An edge travels across the picture, uncovering the next clip. */
    @SerialName("wipe") Wipe("Wipe"),

    /** Both clips side by side for a while, each in its own framing. */
    @SerialName("split") Split("Split screen"),

    /** The next clip appears through the dark or the bright parts of this one first. */
    @SerialName("hitchcock") Hitchcock("Hitchcock"),

    /** Both clips sweep past with motion blur, as in a fast pan. */
    @SerialName("whip") Whip("Whip pan"),

    /** Single frames of the next clip flash in before the cut. */
    @SerialName("flashCut") FlashCut("Flash Cut"),

    /** The last frame is held, then cuts or dissolves to the next clip. */
    @SerialName("still") Still("still"),

    /** Both clips go out of focus and the change happens inside the blur. */
    @SerialName("defocus") Defocus("Defocus");

    /** A hold pauses the move at its midpoint. The two styles made of whole frames have no midpoint to hold. */
    val supportsHold: Boolean get() = this != FlashCut && this != Still
}

@Serializable
enum class TransitionAxis(val title: String) {
    @SerialName("horizontal") Horizontal("Left / right"),
    @SerialName("vertical") Vertical("Top / bottom"),
}

/** Whose sound plays during a split screen. */
@Serializable
enum class TransitionAudio(val title: String) {
    @SerialName("crossfade") Crossfade("Crossfade"),
    @SerialName("first") First("First clip"),
    @SerialName("second") Second("Second clip"),
}

/** How a clip is framed inside its half of a split screen. */
@Serializable
data class TransitionFraming(
    val x: Double = 0.5,
    /** Top-to-bottom coordinates, matching touch input. */
    val y: Double = 0.5,
    val zoom: Double = 1.0,
) {
    /** The part of [source] that fills [viewport], in top-down source coordinates. */
    fun crop(source: Size, viewport: Size): Rect = CropSettings(x = x, y = y, zoom = zoom).rect(source, viewport)

    val valid: Boolean
        get() = x.isFinite() && y.isFinite() && zoom.isFinite() && x in 0.0..1.0 && y in 0.0..1.0 && zoom in 1.0..4.0
}

/** sRGB components 0–1. */
@Serializable
data class TransitionColor(val red: Double, val green: Double, val blue: Double) {
    val valid: Boolean get() = listOf(red, green, blue).all { it.isFinite() && it in 0.0..1.0 }

    companion object {
        val BLACK = TransitionColor(0.0, 0.0, 0.0)
    }
}

/**
 * The original app's `ClipTransition` in full. The port's existing
 * `ClipTransition` is the same saved object with only the fields of its two
 * styles; this type reads and writes every field, so the two are to be merged.
 *
 * Fields added after the first release are null when unset, so documents
 * that do not use them stay readable by older versions.
 */
@Serializable
data class TransitionSettings(
    val id: String = newID(),
    /** Null on a fade at the very end of the timeline, which has no next clip. */
    val nextClipID: String? = null,
    val style: TransitionStyle = TransitionStyle.Dissolve,
    val duration: Double = 0.5,
    val axis: TransitionAxis = TransitionAxis.Horizontal,
    /** Where a split screen divides, as a fraction from the left or the top. */
    val divider: Double = 0.5,
    val reverse: Boolean = false,
    val audio: TransitionAudio = TransitionAudio.Crossfade,
    val first: TransitionFraming = TransitionFraming(),
    val second: TransitionFraming = TransitionFraming(),
    val fadeColor: TransitionColor? = null,
    /** Seconds the move pauses at its midpoint. */
    val hold: Double? = null,
    /** Seconds from the start of the picture effect to the start of the sound change; negative leads. */
    val audioOffset: Double? = null,
    /** Seconds the sound change takes. */
    val audioBlend: Double? = null,
    /** Degrees clockwise in screen coordinates. */
    val wipeAngle: Double? = null,
    val hardEdge: Boolean? = null,
    val highlightsFirst: Boolean? = null,
    val flashCount: Int? = null,
    val flashAcceleration: Double? = null,
    /** Null or 0 is a cut; [duration] is the freeze length. */
    val stillDissolve: Double? = null,
    val blurAmount: Double? = null,
) {
    val resolvedFadeColor: TransitionColor get() = fadeColor ?: TransitionColor.BLACK
    val holdDuration: Double get() = if (style.supportsHold) hold ?: 0.0 else 0.0
    val customAudio: Boolean get() = audioOffset != null || audioBlend != null

    /** Unless set, the sound changes over as long as the picture does. A fade's hold is silent and a freeze cuts. */
    val defaultAudioBlend: Double
        get() = if (style == TransitionStyle.Still) 0.0 else duration + if (style == TransitionStyle.FadeColor) 0.0 else holdDuration

    /** The direction a wipe travels, in degrees clockwise on screen: 0 to the right, 90 downward. */
    val angle: Double
        get() = (wipeAngle ?: if (axis == TransitionAxis.Horizontal) 0.0 else 90.0) + if (reverse) 180.0 else 0.0

    /** Whether anything is used that the first four styles' readers do not know. */
    val newFeatures: Boolean
        get() = style !in listOf(TransitionStyle.Dissolve, TransitionStyle.FadeColor, TransitionStyle.Wipe, TransitionStyle.Split) ||
            resolvedFadeColor != TransitionColor.BLACK || hold != null || audioOffset != null || audioBlend != null ||
            wipeAngle != null || hardEdge == true

    /**
     * The frames of a flash cut that show the next clip, counted from the
     * start of the transition. Flashes are single frames, never adjacent, and
     * neither the first frame nor the last: the cut itself follows the last frame.
     * A higher [flashAcceleration] bunches them towards the start.
     */
    fun flashFrames(duration: Double, framesPerSecond: Double = 30.0): List<Int> {
        val frames = maxOf(1, roundedAwayFromZero(duration * framesPerSecond).toInt())
        val count = minOf(flashCount ?: 3, maxOf(0, (frames - 1) / 2))
        if (count <= 0) return emptyList()
        val result = mutableListOf<Int>()
        for (index in 1..count) {
            val u = index.toDouble() / (count + 1)
            val desired = roundedAwayFromZero((1 - (1 - u).pow(flashAcceleration ?: 2.0)) * (frames - 1)).toInt()
            // Late enough to leave a gap after the previous flash, early enough to leave room for the rest.
            result += minOf(frames - 2 - 2 * (count - index), maxOf((result.lastOrNull() ?: -1) + 2, desired))
        }
        return result
    }

    /** The ranges a saved project may hold. */
    val valid: Boolean
        get() = duration.isFinite() && duration > 0 && duration <= MAXIMUM_DURATION && divider.isFinite() && divider in 0.15..0.85 &&
            resolvedFadeColor.valid && first.valid && second.valid &&
            listOfNotNull(hold, audioBlend, stillDissolve).all { it.isFinite() && it in 0.0..MAXIMUM_DURATION } &&
            (audioOffset?.let { it.isFinite() && abs(it) <= MAXIMUM_DURATION } ?: true) &&
            (wipeAngle?.let { it.isFinite() && it in -360.0..360.0 } ?: true) &&
            (flashCount?.let { it in 1..20 } ?: true) &&
            (flashAcceleration?.let { it.isFinite() && it in 0.25..4.0 } ?: true) &&
            (blurAmount?.let { it.isFinite() && it in 0.0..1.0 } ?: true)

    companion object {
        /**
         * Match the document's maximum running time; usable duration is limited
         * by the actual neighboring footage, not an arbitrary short preset cap.
         */
        const val MAXIMUM_DURATION = 604800.0
    }
}

/**
 * When the picture and the sound of a transition happen. Relative times use
 * the beginning of the picture effect as zero. Both tracks keep the same media
 * clock; the union of picture and sound requirements decides how far the next
 * clip overlaps (or follows) the previous one.
 */
data class TransitionTiming(
    val style: TransitionStyle? = null,
    /** Seconds the picture is changing. */
    val motion: Double = 0.0,
    /** Seconds it stands still: at the midpoint, or on the frozen frame. */
    val hold: Double = 0.0,
    val videoDuration: Double = 0.0,
    /** When the incoming clip's sound comes up. */
    val audioIn: ClosedFloatingPointRange<Double> = 0.0..0.0,
    /** When the outgoing clip's sound goes down. */
    val audioOut: ClosedFloatingPointRange<Double> = 0.0..0.0,
    /** Where the outgoing clip's footage ends. */
    val leftEnd: Double = 0.0,
    /** Where the incoming clip's footage begins. */
    val rightStart: Double = 0.0,
    /** The whole affected stretch, picture and sound. */
    val lower: Double = 0.0,
    val upper: Double = 0.0,
    /** The moment that counts as the boundary between the two clips. */
    val cut: Double = 0.0,
    /** Seconds of the outgoing and of the incoming clip that this transition occupies. */
    val leftCost: Double = 0.0,
    val rightCost: Double = 0.0,
) {
    /** Seconds both clips play at once; negative when time is inserted between them. */
    val overlap: Double get() = leftEnd - rightStart

    /** How far the picture has changed, 0 to 1, [elapsed] seconds in. A hold stays at one half. */
    fun progress(elapsed: Double): Double {
        if (style == TransitionStyle.Still) {
            return if (motion > 0) minOf(1.0, maxOf(0.0, (elapsed - hold) / motion)) else if (elapsed < hold) 0.0 else 1.0
        }
        if (!(motion > 0)) return if (elapsed < videoDuration / 2) 0.0 else 1.0
        if (elapsed < motion / 2) return minOf(1.0, maxOf(0.0, elapsed / motion))
        if (elapsed <= motion / 2 + hold) return 0.5
        return minOf(1.0, maxOf(0.0, (elapsed - hold) / motion))
    }

    companion object {
        /**
         * [factor] shrinks a transition that does not fit its footage. [edge]
         * is −1 at the opening of the timeline, +1 at its close and 0 between two clips.
         */
        fun of(settings: TransitionSettings?, factor: Double = 1.0, edge: Int = 0): TransitionTiming {
            if (settings == null) return TransitionTiming()
            val fade = settings.style == TransitionStyle.FadeColor
            val still = settings.style == TransitionStyle.Still
            var motion = (if (still) settings.stillDissolve ?: 0.0 else settings.duration) * factor
            // Solid gaps and silent freezes need no source handles. Custom audio
            // can make the neighboring source run underneath them, so those holds
            // must then participate in fitting, just like moving-picture holds.
            var hold = if (still) settings.duration * (if (settings.customAudio && edge != 1) factor else 1.0)
            else settings.holdDuration * (if (fade && edge == 0 && !settings.customAudio) 1.0 else factor)
            if (!still && motion + hold < PauseDetector.FRAME - 0.000001) { motion = 0.0; hold = 0.0 }
            val videoDuration = motion + hold
            var aNeed = videoDuration
            var bNeed = 0.0
            if (fade && edge == 0) { aNeed = motion / 2; bNeed = motion / 2 + hold }
            if (still) { aNeed = 0.0; bNeed = hold }
            var offset = (settings.audioOffset ?: 0.0) * factor
            var blend = (settings.audioBlend ?: if (edge != 0 && fade) settings.duration + settings.holdDuration else settings.defaultAudioBlend) * factor
            if (settings.style == TransitionStyle.Split && settings.audioOffset == null && settings.audioBlend == null) {
                // One side's sound only: switch in a few milliseconds, at the end or at the start.
                if (settings.audio == TransitionAudio.First) { offset = maxOf(0.0, videoDuration - 0.008); blend = minOf(0.008, videoDuration) }
                if (settings.audio == TransitionAudio.Second) blend = minOf(0.008, videoDuration)
            }
            val cut = if (still) hold else videoDuration / 2
            val base = TransitionTiming(style = settings.style, motion = motion, hold = hold, videoDuration = videoDuration, cut = cut)
            if (edge == -1) {
                val begin = offset + if (still && !settings.customAudio) hold else 0.0
                val audioIn = begin..(begin + blend)
                val rightStart = minOf(bNeed, audioIn.start)
                val upper = maxOf(videoDuration, audioIn.endInclusive)
                return base.copy(audioIn = audioIn, rightStart = rightStart, lower = minOf(0.0, rightStart), upper = upper, rightCost = upper - rightStart)
            }
            if (edge == 1) {
                val audioOut = offset..(offset + blend)
                val leftEnd = maxOf(aNeed, audioOut.endInclusive)
                val lower = minOf(0.0, audioOut.start)
                return base.copy(audioOut = audioOut, leftEnd = leftEnd, lower = lower, upper = maxOf(videoDuration, leftEnd), leftCost = leftEnd - lower)
            }
            val audioOut: ClosedFloatingPointRange<Double>
            val audioIn: ClosedFloatingPointRange<Double>
            if ((fade || still) && !settings.customAudio) {
                // Sound goes down before the hold and comes up after it.
                audioOut = offset..(offset + blend / 2)
                audioIn = (offset + blend / 2 + hold)..(offset + blend + hold)
            } else {
                audioIn = offset..(offset + blend)
                audioOut = audioIn
            }
            val leftEnd = maxOf(aNeed, audioOut.endInclusive)
            val rightStart = minOf(bNeed, audioIn.start)
            val lower = minOf(0.0, rightStart, audioOut.start)
            val upper = maxOf(videoDuration, leftEnd, audioIn.endInclusive)
            return base.copy(
                audioIn = audioIn, audioOut = audioOut, leftEnd = leftEnd, rightStart = rightStart, lower = lower, upper = upper,
                leftCost = leftEnd - lower, rightCost = upper - rightStart,
            )
        }
    }
}
