package app.takes

import app.takes.core.EditState
import app.takes.core.EffectValue
import app.takes.core.RangeEditing
import app.takes.core.SpeedMapping
import app.takes.core.StrobeTimePoint
import app.takes.core.TimelineClip
import app.takes.core.VideoEffectEditing
import app.takes.core.VideoEffectID
import app.takes.core.VideoEffectLibrarySection
import app.takes.core.VideoEffectSettings
import app.takes.core.newID
import kotlin.math.abs

/**
 * The effects this release offers in the library: those the preview and the
 * export draw on Android. A later batch adds its effects to [offered] by
 * adding a list beside [BATCH_A].
 */
internal object AndroidVideoEffects {
    /** The first release (docs/PIP-AND-EFFECTS-PLAN.md 2.5, batch A). */
    val BATCH_A: List<VideoEffectID> = listOf(
        VideoEffectID.ColorCorrection, VideoEffectID.SilentTint, VideoEffectID.ThreeStrip, VideoEffectID.BleachBypass,
        VideoEffectID.DayForNight, VideoEffectID.Noir, VideoEffectID.Expressionism, VideoEffectID.ColorIsolation,
        VideoEffectID.RgbOffset, VideoEffectID.NegativeStrobe, VideoEffectID.Kaleidoscope,
    )

    /**
     * The second batch, drawn in several passes: Super 8 without its
     * cadence, Widescreen without its oval bokeh (which needs depth), Crash
     * Zoom with its target set by hand.
     */
    val BATCH_B: List<VideoEffectID> = listOf(
        VideoEffectID.Halation, VideoEffectID.SixteenMM, VideoEffectID.SuperEight, VideoEffectID.Widescreen, VideoEffectID.CrashZoom,
    )

    /**
     * The third batch, the effects of it that change no timing: Quantization
     * with its palette from the scene (without face detail, which needs a
     * person mask), Chroma Key's spatial keyer on a solid colour, a picture
     * from the phone or the picture blurred, and Night fix Fast. Freeze
     * Frame, Undercranking, Step Printing and Super 8's cadence change what
     * plays when, which is the playback map's.
     */
    val BATCH_C: List<VideoEffectID> = listOf(VideoEffectID.Quantization, VideoEffectID.ChromaKey, VideoEffectID.NightFix)

    val offered: Set<VideoEffectID> = (BATCH_A + BATCH_B + BATCH_C).toSet()

    /** The library's effects of [section] that are offered, in the order the original lists them. */
    fun effects(section: VideoEffectLibrarySection): List<VideoEffectID> = section.kinds.filter { it in offered }

    /** Controls of an offered effect that this release does not draw, by effect. */
    private val HIDDEN: Map<VideoEffectID, Set<String>> = mapOf(
        // "A color" only: "The subject" needs a person mask.
        VideoEffectID.ColorIsolation to setOf("mode"),
        // Following a face needs face detection; the target is set by hand.
        VideoEffectID.CrashZoom to setOf("trackFace"),
        // The cadence holds frames, which is the playback map's.
        VideoEffectID.SuperEight to setOf("fps"),
        VideoEffectID.Quantization to setOf("faceDetail"),
        VideoEffectID.ChromaKey to setOf("personHint", "cleanPlate", "matchColor", "matchStrength", "backgroundAudio"),
        // Medium and Precise need optical flow.
        VideoEffectID.NightFix to setOf("mode"),
    )

    /** Controls of an offered effect that this release does not offer yet. */
    fun hides(kind: VideoEffectID, parameter: String): Boolean = HIDDEN[kind]?.contains(parameter) == true

    /** Whether the preview draws a simplified version of [effect] at source second [at]: its blurs, smoothing or matte are stand-ins. */
    fun previewSimplified(effect: VideoEffectSettings, at: Double): Boolean = when (effect.kind) {
        VideoEffectID.Halation, VideoEffectID.SuperEight, VideoEffectID.ChromaKey, VideoEffectID.NightFix -> true
        VideoEffectID.SixteenMM -> effect.number("softness", at) > 0
        VideoEffectID.Widescreen -> effect.number("flares", at) > 0 || effect.choice("bars", at) == "Blur fill"
        VideoEffectID.Quantization -> effect.number("smoothing", at) > 0
        else -> false
    }

    /** [effect] as this release draws it, for what its controls show: a Crash Zoom moves to its target set by hand, even one made to follow a face. */
    fun asDrawn(effect: VideoEffectSettings): VideoEffectSettings =
        if (effect.kind == VideoEffectID.CrashZoom) effect.with("trackFace", EffectValue.Toggle(false)) else effect

    /** The choices of a picker this release offers: a Chroma Key's backgrounds without video, Studio and Window. */
    fun choices(kind: VideoEffectID, parameter: String, choices: List<String>): List<String> =
        if (kind == VideoEffectID.ChromaKey && parameter == "background") choices.filter { it in listOf("Solid", "Image", "Blur original") } else choices
}

/** Where the Video effects tool is: the applied list, the library, one of its sections, or an effect's controls. */
internal sealed interface EffectsPage {
    data object Applied : EffectsPage
    data object Library : EffectsPage
    data class Section(val section: VideoEffectLibrarySection) : EffectsPage
    data object Inspector : EffectsPage
}

/**
 * The Video effects tool while it is open (YouRec/VideoEffects/VideoEffectsEditor.swift,
 * `VideoEffectsWorkspace`). [on] is what it was opened on, as for the Crop tool:
 * the clip under the playhead, or the pieces of a range or a selection.
 * [effects] is the list each of those clips will have; one effect added to
 * several clips has the same id on each, and every change goes to all of them.
 * Nothing is saved until it is applied, as one Undo step.
 */
internal data class EffectsWork(
    val on: CropScope,
    val effects: Map<String, List<VideoEffectSettings>>,
    val selected: String? = null,
    val page: EffectsPage = EffectsPage.Applied,
    /** Showing the picture without the effects, to compare. */
    val bypass: Boolean = false,
    /** Animate: a control changed adds a keyframe at the playhead. VideoEffectsEditor.swift:1209-1213. */
    val animate: Boolean = false,
) {
    /** The clip of the tool at timeline second [time], or the nearest one. */
    fun piece(time: Double): CropScope.Piece? = on.active(time)

    /** The source second the piece at [time] shows: what effects and their keyframes are measured in. */
    fun sourceTime(time: Double): Double {
        val piece = piece(time) ?: return 0.0
        return piece.clip.sourceTime((time - piece.start).coerceIn(0.0, maxOf(0.0, piece.clip.duration - 0.001)))
    }

    /** The effects on the picture at [time], in the order they are drawn. VideoEffectsEditor.swift:396-398. */
    fun active(time: Double): List<VideoEffectSettings> {
        val piece = piece(time) ?: return emptyList()
        val at = sourceTime(time)
        return VideoEffectSettings.ordered(effects[piece.clip.id].orEmpty()).filter { at >= it.start && at < it.end }
    }

    /** The effect being edited, as the piece at [time] has it. */
    fun current(time: Double): VideoEffectSettings? {
        val id = selected ?: return null
        return active(time).firstOrNull { it.id == id } ?: effects.values.flatten().firstOrNull { it.id == id }
    }

    /** Whether any clip of the tool has an effect. */
    val hasEffects: Boolean get() = effects.values.any { it.isNotEmpty() }

    /**
     * A new effect of [kind] on every clip of the tool, over the whole clip,
     * and its controls open. A Negative Strobe keeps one clock across the
     * clips, and through a clip played at another speed. VideoEffectsEditor.swift:454-488.
     */
    fun adding(kind: VideoEffectID): EffectsWork {
        val id = newID()
        val next = effects.toMutableMap()
        for (piece in on.pieces) {
            val clip = piece.clip
            var effect = VideoEffectSettings(kind, clip.start, clip.end).copy(id = id)
            // Color Isolation of one hue and Chroma Key's keyer are the single-frame renderings.
            if (kind == VideoEffectID.ColorIsolation || kind == VideoEffectID.ChromaKey) effect = effect.copy(algorithm = 1)
            // A Crash Zoom moves towards the target set by hand.
            if (kind == VideoEffectID.CrashZoom) effect = effect.with("trackFace", EffectValue.Toggle(false))
            if (kind == VideoEffectID.NegativeStrobe) effect = effect.copy(phaseOffset = maxOf(0.0, piece.start - on.start), strobeTimeMap = strobeClock(clip))
            next[clip.id] = next[clip.id].orEmpty() + effect
        }
        return copy(effects = next, selected = id, page = EffectsPage.Inspector)
    }

    /** Playback seconds against source seconds, where the clip does not play its footage at its own pace. */
    private fun strobeClock(clip: TimelineClip): List<StrobeTimePoint>? {
        val spans = SpeedMapping.spans(clip, clip.speed)
        val first = spans.firstOrNull() ?: return null
        if (spans.size <= 1 && first.rate == 1.0) return null
        var elapsed = 0.0
        val points = mutableListOf(StrobeTimePoint(first.start, 0.0))
        for (span in spans) {
            if (span.start > points.last().source) points += StrobeTimePoint(span.start, elapsed)
            elapsed += span.duration
            points += StrobeTimePoint(span.end, elapsed)
        }
        return points
    }

    /** [change] on effect [id] (the selected one when null) on every clip that has it. */
    fun changing(id: String? = selected, change: (VideoEffectSettings) -> VideoEffectSettings): EffectsWork {
        val target = id ?: return this
        return copy(effects = effects.mapValues { (_, list) -> list.map { if (it.id == target) change(it) else it } })
    }

    /**
     * Control [parameter] of the selected effect set to [value]. A control
     * that has keyframes gets one at the playhead instead of losing them.
     * VideoEffectsEditor.swift:548-556.
     */
    fun setting(parameter: String, value: EffectValue, time: Double): EffectsWork {
        val at = sourceTime(time)
        val clipStart = piece(time)?.clip?.start
        return changing { effect ->
            val control = effect.parameters.firstOrNull { it.id == parameter }
            when {
                control == null || !control.kind.accepts(value) -> effect
                // Animating, the first key also holds the value it had at the clip's start. VideoEffectsEditor.swift:535-547.
                animate && control.animatable -> {
                    val start = clipStart ?: at
                    val first = if (control.keyframes.isEmpty() && at > start + 0.001) effect.withKeyframe(parameter, start, effect.value(parameter, start) ?: control.value) else effect
                    first.withKeyframe(parameter, at, value)
                }
                else -> effect.with(parameter, value).let { if (control.keyframes.isEmpty()) it else it.withKeyframe(parameter, at, value) }
            }
        }
    }

    /**
     * A key of control [parameter] of the selected effect at the playhead
     * added, holding the value there, or the one within a sixtieth of a
     * second taken away. VideoEffectsEditor.swift:559-572.
     */
    fun keyframing(parameter: String, time: Double): EffectsWork {
        val at = sourceTime(time)
        return changing { effect ->
            val control = effect.parameters.firstOrNull { it.id == parameter } ?: return@changing effect
            val near = control.keyframes.firstOrNull { abs(it.time - at) < 1.0 / 60 }
            if (near != null) effect.copy(parameters = effect.parameters.map { if (it.id == parameter) it.copy(keyframes = it.keyframes - near) else it })
            else effect.withKeyframe(parameter, at, effect.value(parameter, at) ?: control.value)
        }
    }

    /** The timeline second at which the piece at [time] shows source second [sourceTime]: where a keyframe is. */
    fun timelineTime(time: Double, sourceTime: Double): Double? {
        val piece = piece(time) ?: return null
        return piece.start + piece.clip.offset(sourceTime.coerceIn(piece.clip.start, piece.clip.end))
    }

    /** Several controls set at once, as the Kaleidoscope's resets do. */
    fun setting(values: Map<String, Double>, time: Double): EffectsWork =
        values.entries.fold(this) { work, (id, value) -> work.setting(id, EffectValue.Number(value), time) }

    /** Effect [id] on or off. */
    fun toggling(id: String): EffectsWork = changing(id) { it.copy(enabled = !it.enabled) }

    /** The selected effect with every control at its default and no keyframes or motion. */
    fun resetting(): EffectsWork = changing { it.copy(parameters = VideoEffectSettings.defaultParameters(it.kind), motion = null) }

    /** Effect [id] taken off every clip; the tool goes back to the applied list, or to the library when nothing is left. */
    fun removing(id: String, time: Double): EffectsWork {
        val next = copy(effects = effects.mapValues { (_, list) -> list.filterNot { it.id == id } }, selected = selected?.takeIf { it != id })
        return next.copy(page = if (next.active(time).isEmpty()) EffectsPage.Library else EffectsPage.Applied)
    }

    /**
     * The edit with the effects on the tool's clips, null when that changes
     * none of them: the cuts at the ends of a range are then not made either.
     * A look showing its matte or its original is saved showing its result.
     */
    fun applied(): EditState? =
        VideoEffectEditing.setEffects(on.state, on.pieces.associate { piece -> piece.clip.id to effects[piece.clip.id].orEmpty().map { it.recordingResult } })

    /**
     * The effects to draw on [clip], a clip of the edit as it is now, where it
     * shows source second [sourceTime] at timeline second [time]: on the
     * tool's clips the list being worked on, none while comparing with the
     * original, elsewhere the clip's own. A range cuts clips, so the piece is
     * found by the footage it shows, as [Cropping.shown] finds it.
     */
    fun shown(clip: TimelineClip, sourceTime: Double, time: Double): List<VideoEffectSettings>? {
        val piece = on.all.filter { it.clip.sourceID == clip.sourceID && sourceTime >= it.clip.start - 0.000001 && sourceTime <= it.clip.end + 0.000001 }
            .minWithOrNull(compareBy<CropScope.Piece> { abs(it.start + it.clip.offset(sourceTime) - time) }.thenBy { sourceTime - it.clip.start })
            ?: return clip.videoEffects
        if (piece.clip.id !in on.ids) return piece.clip.videoEffects
        return if (bypass) null else effects[piece.clip.id]?.takeIf { it.isNotEmpty() }
    }

    companion object {
        /**
         * The tool opened on a range or a selection, or with [scoped] null on
         * the clip [subject] of [edit]: on the applied list when a clip of it
         * has effects, else on the library.
         */
        fun opening(edit: EditState, scoped: RangeEditing.Scoped?, subject: TimelineClip): EffectsWork {
            val on = CropScope(edit, scoped, scoped?.clips?.toSet() ?: setOf(subject.id))
            val effects = on.pieces.associate { it.clip.id to it.clip.videoEffects.orEmpty() }
            return EffectsWork(on, effects, page = if (effects.values.any { it.isNotEmpty() }) EffectsPage.Applied else EffectsPage.Library)
        }
    }
}
