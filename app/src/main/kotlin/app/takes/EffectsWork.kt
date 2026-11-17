package app.takes

import app.takes.core.ChromaScreen
import app.takes.core.EditState
import app.takes.core.EffectRGB
import app.takes.core.EffectValue
import app.takes.core.IsolationHue
import app.takes.core.RangeEditing
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
     * Zoom following a face from its target, or moving to the target itself.
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

    /**
     * The time effects, which change what plays when (the playback map):
     * Freeze Frame, a held frame put in at the playhead; Step Printing, its
     * blocks of frames blended in the export; Undercranking, its cadence held
     * in the export, its speed and mute in the playback and the mix. Super 8's
     * cadence comes with them.
     */
    val BATCH_TIME: List<VideoEffectID> = listOf(VideoEffectID.FreezeFrame, VideoEffectID.StepPrinting, VideoEffectID.Undercranking)

    /**
     * The effects that read the person in the frame, with the parts of the others that do (Color Isolation's
     * subject, Chroma Key's person hint, Quantization's face detail): offered where LiteRT runs (PersonMasks.kt).
     * Auto Background without its video background and match colour; Dolly Zoom, the subject separated by the person
     * (there is no imported depth here), its target followed from the point set (SubjectTracks.kt).
     */
    val BATCH_PERSON: List<VideoEffectID> = listOf(VideoEffectID.AutoBackground, VideoEffectID.DollyZoom)

    /** Whether this phone makes person masks. */
    private val people: Boolean get() = PersonMasks.available()

    val offered: Set<VideoEffectID> get() = (BATCH_A + BATCH_B + BATCH_C + BATCH_TIME + if (people) BATCH_PERSON else emptyList()).toSet()

    /** The library's effects of [section] that are offered, in the order the original lists them. */
    fun effects(section: VideoEffectLibrarySection): List<VideoEffectID> = section.kinds.filter { it in offered }

    /** Controls of an offered effect that this release does not draw, by effect. */
    private val HIDDEN: Map<VideoEffectID, Set<String>> = mapOf(
        VideoEffectID.ChromaKey to setOf("cleanPlate", "backgroundAudio"),
        VideoEffectID.AutoBackground to setOf("matchColor", "matchStrength", "backgroundAudio"),
        // Medium and Precise need optical flow.
        VideoEffectID.NightFix to setOf("mode"),
    )

    /** Controls that need a person mask, hidden where this phone cannot make one: Color Isolation keeps "A color" only. */
    private val PERSON: Map<VideoEffectID, Set<String>> = mapOf(
        VideoEffectID.ColorIsolation to setOf("mode"),
        VideoEffectID.Quantization to setOf("faceDetail"),
        VideoEffectID.ChromaKey to setOf("personHint"),
    )

    /** Controls of an offered effect that this release does not offer yet. */
    fun hides(kind: VideoEffectID, parameter: String): Boolean =
        HIDDEN[kind]?.contains(parameter) == true || (!people && PERSON[kind]?.contains(parameter) == true)

    /** Whether the preview draws a simplified version of [effect] at source second [at]: its blurs, smoothing or matte are stand-ins. */
    fun previewSimplified(effect: VideoEffectSettings, at: Double): Boolean = when (effect.kind) {
        VideoEffectID.Halation, VideoEffectID.SuperEight, VideoEffectID.ChromaKey, VideoEffectID.NightFix -> true
        // The preview plays the retime and draws the flicker and weave; the export holds the cadence's frames.
        VideoEffectID.Undercranking -> true
        // The preview plays the retime; the export blends each block's frames.
        VideoEffectID.StepPrinting -> effect.number("smear", at) > 0
        // The preview draws the push-in and Print's look; the export adds its grain.
        VideoEffectID.FreezeFrame -> effect.choice("style", at) == "Print"
        VideoEffectID.SixteenMM -> effect.number("softness", at) > 0
        VideoEffectID.Widescreen -> effect.number("flares", at) > 0 || effect.choice("bars", at) == "Blur fill"
        VideoEffectID.Quantization -> effect.number("smoothing", at) > 0 || (people && effect.toggle("faceDetail", at) && effect.number("colors", at) <= 4)
        // The preview reads the person's mask as it is, without the guided filter, and the picture as the background behind it.
        VideoEffectID.AutoBackground -> true
        // The preview reads the person's mask as it is, and the picture outward from the person as the background behind them.
        VideoEffectID.DollyZoom -> true
        // The preview draws each frame's own matte; the export blends it with the frames before (algorithm 2).
        VideoEffectID.ColorIsolation -> effect.choice("mode", at) == "The subject" || effect.usesTemporalMatte
        else -> false
    }

    /** [effect] as this release draws it, for what its controls show: every effect is drawn as it is set. */
    fun asDrawn(effect: VideoEffectSettings): VideoEffectSettings = effect

    /** The choices of a picker this release offers: a Chroma Key's and an Auto Background's backgrounds without video. */
    fun choices(kind: VideoEffectID, parameter: String, choices: List<String>): List<String> =
        if ((kind == VideoEffectID.ChromaKey || kind == VideoEffectID.AutoBackground) && parameter == "background") choices.filter { it != "Video" } else choices
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
    /** The colour control of the selected effect a tap on the preview picks, while it does. VideoEffectsEditor.swift:344, 865-869. */
    val picking: String? = null,
    /** Why the last tap of a pick was not taken; null before one. */
    val pickRefusal: PickRefusal? = null,
) {
    /** Why a tap of a colour pick was not taken. VideoEffectsEditor.swift:871-880. */
    enum class PickRefusal { Outside, NotScreen, NoHue }

    /** Begins picking the colour [parameter] of the selected effect from the preview. VideoEffectsEditor.swift:865-868. */
    fun beginningPick(parameter: String): EffectsWork = copy(picking = parameter, pickRefusal = null)

    /** Ends a pick without a colour. VideoEffectsEditor.swift:869. */
    fun cancellingPick(): EffectsWork = copy(picking = null, pickRefusal = null)

    /**
     * The colour picked, [color], set at timeline second [time]: a screen's only when it is a green or blue one, and
     * as calibration, with detection off, the type Auto and no keys; Color Isolation's only when it has a hue. A
     * colour refused, or a tap outside the picture (null), keeps the pick going with its reason.
     * VideoEffectsEditor.swift:524-532, 870-880.
     */
    fun picked(color: EffectRGB?, time: Double): EffectsWork {
        val parameter = picking ?: return this
        if (color == null) return copy(pickRefusal = PickRefusal.Outside)
        if (parameter == "screenColor") {
            if (!ChromaScreen.accepts(color)) return copy(pickRefusal = PickRefusal.NotScreen)
            return screenColor(color).cancellingPick()
        }
        if (current(time)?.kind == VideoEffectID.ColorIsolation && parameter == "color" && !IsolationHue.accepts(color)) return copy(pickRefusal = PickRefusal.NoHue)
        return setting(parameter, EffectValue.Color(color), time).cancellingPick()
    }

    /** A Chroma Key's screen set as calibration: detection off, the type Auto, the colour without keys. VideoEffectsEditor.swift:524-532. */
    fun screenColor(color: EffectRGB): EffectsWork = changing { effect ->
        effect.copy(
            parameters = effect.parameters.map { p ->
                when (p.id) {
                    "autoScreen" -> p.copy(value = EffectValue.Toggle(false))
                    "screenType" -> p.copy(value = EffectValue.Choice("Auto"))
                    "screenColor" -> p.copy(value = EffectValue.Color(color), keyframes = emptyList())
                    else -> p
                }
            },
        )
    }

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
            // Color Isolation and Chroma Key are made with the temporal matte, algorithm 2 (VideoEffectModel.swift:200-202).
            var effect = VideoEffectSettings(kind, clip.start, clip.end).copy(id = id)
            if (kind == VideoEffectID.NegativeStrobe) effect = effect.copy(phaseOffset = maxOf(0.0, piece.start - on.start), strobeTimeMap = strobeClock(clip))
            next[clip.id] = next[clip.id].orEmpty() + effect
        }
        return copy(effects = next, selected = id, page = EffectsPage.Inspector)
    }

    /** Playback seconds against source seconds, where the clip does not play its footage at its own pace. */
    private fun strobeClock(clip: TimelineClip): List<StrobeTimePoint>? {
        val spans = clip.playbackSpans
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
