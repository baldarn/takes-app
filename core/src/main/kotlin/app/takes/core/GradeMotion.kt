package app.takes.core

import kotlin.math.pow

/**
 * A grade that moves between two ends, A and B, as the original draws it
 * (YouRec/VideoToolCompositor.swift:140-152): the picture is graded by A and
 * by B, each a fixed grade with its own look or LUT, and the two pictures are
 * dissolved by the motion's fraction at the source second shown; before the
 * motion has started only A is drawn, once it is at B only B. The numbers of
 * the two grades are not interpolated, so a look or a LUT changes by fading
 * from one to the other, as in the original.
 */
object GradeMotion {
    /** The grade a clip shows at one moment: [from], or with [to] the two dissolved [fraction] of the way to [to]. */
    data class Moment(val from: ColorSettings, val to: ColorSettings? = null, val fraction: Double = 0.0)

    /** The two fixed grades a moving grade dissolves between, A then B; null when [grade] does not move. */
    fun ends(grade: ColorSettings): Pair<ColorSettings, ColorSettings>? =
        grade.motion?.let { grade.applying(it.a) to grade.applying(it.b) }

    /** What [grade] shows at source second [source] (VideoToolCompositor.swift:145-152). */
    fun moment(grade: ColorSettings, source: Double): Moment {
        val motion = grade.motion ?: return Moment(grade)
        val f = motion.fraction(source)
        return when {
            f <= 0 -> Moment(grade.applying(motion.a))
            f >= 1 -> Moment(grade.applying(motion.b))
            else -> Moment(grade.applying(motion.a), grade.applying(motion.b), f)
        }
    }

    /**
     * How far from A to B the grade of [clip] is, [offset] timeline seconds into the clip: its motion sampled at the
     * source second shown there, as a moving crop is ([PictureFraming.motionTime]); null when [grade] does not move.
     */
    fun fraction(clip: TimelineClip, offset: Double, grade: ColorSettings? = clip.grade(clip.gradeTime(offset.coerceIn(0.0, clip.duration)))): Double? =
        grade?.motion?.fraction(PictureFraming.motionTime(clip, offset.coerceIn(0.0, clip.duration)))

    /** SDR video's light of a display-encoded value (BT.709), the light the original's dissolve mixes in. */
    fun light(signal: Double): Double = signal.coerceIn(0.0, 1.0).let { if (it < 0.081) it / 4.5 else ((it + 0.099) / 1.099).pow(1 / 0.45) }

    /** The display-encoded value of [light] (BT.709). */
    fun signal(light: Double): Double = light.coerceIn(0.0, 1.0).let { if (it < 0.018) 4.5 * it else 1.099 * it.pow(0.45) - 0.099 }

    /**
     * One color graded by A ([a]) and by B ([b]), display-encoded, dissolved [fraction] of the way to B. Core Image's
     * dissolve (`CIDissolveTransition`) mixes in its working space, which is linear light: the two are mixed as light
     * and encoded again. The ends are each grade's own color exactly.
     */
    fun dissolve(a: DoubleArray, b: DoubleArray, fraction: Double): DoubleArray = when {
        fraction <= 0 -> a.copyOf()
        fraction >= 1 -> b.copyOf()
        else -> DoubleArray(3) { signal(light(a[it]) + (light(b[it]) - light(a[it])) * fraction) }
    }
}

/**
 * The Color tool's grade while it can move, as the original's tool workspace
 * keeps it (YouRec/EditorTools.swift:259-310): the controls always edit one
 * fixed grade, [color]; with a motion, that grade is the end being edited.
 *
 * @property endpoint the end being edited, shown in the menu.
 * @property previewEndpoint the end the preview is parked on; scrubbing or playing clears it.
 */
data class GradeDraft(
    val color: ColorSettings,
    val animation: EffectAnimation? = null,
    val endpoint: EffectEndpoint? = null,
    val previewEndpoint: EffectEndpoint? = null,
    val lastEndpoint: EffectEndpoint = EffectEndpoint.A,
) {
    /** The motion with the grade on screen written into the end it belongs to, over [duration] (`capturedAnimation`). */
    fun captured(duration: Double): EffectAnimation? =
        animation?.with(endpoint ?: lastEndpoint, color.pose)?.copy(duration = duration)

    /** Starts editing an end; the first time, a motion whose two ends are the grade on screen (`selectEndpoint`). */
    fun selecting(end: EffectEndpoint, duration: Double): GradeDraft {
        val motion = captured(duration) ?: EffectAnimation(color.pose, duration)
        return copy(color = color.applying(motion[end]), animation = motion, endpoint = end, previewEndpoint = end, lastEndpoint = end)
    }

    /** A change of a control. Away from the end being edited the preview goes back to it (`adjustedEndpoint`). */
    fun adjusted(next: ColorSettings): GradeDraft {
        val changed = copy(color = next.copy(motion = null))
        if (animation == null || previewEndpoint != null) return changed
        return changed.copy(endpoint = lastEndpoint, previewEndpoint = lastEndpoint)
    }

    /** "Done": keeps the motion and leaves the ends (`finishEndpoint`). */
    fun finishing(duration: Double): GradeDraft = copy(animation = captured(duration), endpoint = null, previewEndpoint = null)

    /** "Remove animation": the grade on screen stays, fixed (`removeAnimation`). */
    fun removingAnimation(): GradeDraft = copy(animation = null, endpoint = null, previewEndpoint = null)

    /** A change of curve, rise, release or ramp (`updateTiming`). */
    fun retimed(timing: EffectTiming, duration: Double): GradeDraft {
        val motion = captured(duration) ?: return this
        if (motion.timing == timing) return this
        return copy(animation = motion.copy(timing = timing))
    }

    /** The preview was moved by hand, or plays. */
    fun scrubbed(): GradeDraft = copy(previewEndpoint = null)

    /** Back on the end being edited, once playing stops. */
    fun stopped(): GradeDraft = if (endpoint != null && previewEndpoint == null) copy(previewEndpoint = endpoint) else this

    /** Where the preview stands, in seconds from the start of the tool's stretch: on the parked end, or where it is. */
    fun previewPosition(position: Double, duration: Double): Double =
        animation?.let { it.timing.previewPosition(position, previewEndpoint, duration, it.frame) } ?: position

    /**
     * The grade to put on a clip that plays [spans] and begins [offset] seconds into the tool's stretch of
     * [duration] seconds (EditorTools.swift:704-712): fixed at A, with the motion tied to the clip's footage. A clip
     * whose motion is unchanged keeps its clock ([EffectAnimation.placed]); [previous] is the grade it has.
     */
    fun placed(previous: ColorSettings?, spans: List<EffectPlaybackSpan>, offset: Double, duration: Double): ColorSettings {
        val motion = captured(duration) ?: return color
        return color.applying(motion.a).copy(motion = motion.placed(spans, offset, previous?.motion))
    }

    companion object {
        /** The tool opened on a grade: a moving one shows its A, its motion set aside for editing (EditorTools.swift:461-462). */
        fun opening(stored: ColorSettings): GradeDraft {
            val motion = stored.motion ?: return GradeDraft(stored)
            return GradeDraft(stored.applying(motion.a), motion)
        }
    }
}
