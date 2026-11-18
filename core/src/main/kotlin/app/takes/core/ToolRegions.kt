package app.takes.core

import kotlin.math.abs

/** The tools that leave something on the edit, as the original's `VideoTool` names them (YouRec/VideoTools.swift). */
enum class EditorTool {
    Subtitles, Mirror, Image, VideoEffects, Crop, Speed, Color, Stabilize, Pauses, Text, CensorAudio, EnhanceAudio, Volume, Placement, Transition;

    /** The tools of a sound, whose regions are on sound items rather than on pictures (YouRec/VideoTools.swift:459). */
    val audio: Boolean get() = this == CensorAudio || this == EnhanceAudio || this == Volume
}

/** What a region's name is made of; the screen puts it in words (YouRec/VideoTools.swift:567-627). */
sealed interface ToolRegionLabel {
    /** The tool's own name: Image, Pauses, Subtitles, Censor audio, Enhance audio, Stabilize. */
    data object Tool : ToolRegionLabel

    /** A title's text. */
    data class Text(val text: String) : ToolRegionLabel

    /** "Flip H + V", "Flip horizontal" or "Flip vertical". */
    data class Flip(val horizontal: Boolean, val vertical: Boolean) : ToolRegionLabel

    /** "Crop · 12°" when turned, "Fill" at zoom 1, else the zoom with one decimal and ×. */
    data class Framing(val zoom: Double, val rotation: Double) : ToolRegionLabel {
        val turned: Boolean get() = abs(rotation) > 0.00001
    }

    /** The rate with up to two decimals and ×. */
    data class Rate(val rate: Double) : ToolRegionLabel

    /** The grade's title. */
    data class Grade(val color: ColorSettings) : ToolRegionLabel

    /** The effect's name. */
    data class Effect(val kind: VideoEffectID) : ToolRegionLabel

    /** "Muted" or the level in dB with a sign and one decimal, "Fade in", "Fade out", joined by " · ". */
    data class Levels(val muted: Boolean, val volumeDB: Double, val fadeIn: Boolean, val fadeOut: Boolean) : ToolRegionLabel

    /** "Placement · default" for pictures together without a region of their own, else "Placement". */
    data class Placement(val default: Boolean) : ToolRegionLabel

    /** The transition's style. */
    data class Transition(val style: TransitionStyle) : ToolRegionLabel
}

/**
 * One thing a tool left on the edit, where it plays, as the original's
 * `ToolRegion` (YouRec/VideoTools.swift:446-453): the effect [effectID] of
 * [tool] on the picture or sound [clipID], from [start] to [end] on the
 * timeline. The pieces of a split clip, and the clips a tool worked on at
 * once, keep one effect id, so the same effect is a region on each.
 *
 * [audio] is the region of a sound tool as the sound tools take it, on the
 * timeline's sound [clipID]; [join] the place of a transition among the
 * joins of a sequence.
 */
data class ToolRegion(
    val tool: EditorTool,
    val effectID: String,
    val clipID: String,
    val start: Double,
    val end: Double,
    val label: ToolRegionLabel,
    val audio: AudioToolRegion? = null,
    val join: Int? = null,
) {
    /** Placements are told apart by their stretch too (YouRec/VideoTools.swift:447). */
    val id: String
        get() = "$tool-$effectID-$clipID" + if (tool == EditorTool.Placement) "-${EditTime.of(start).ticks}-${EditTime.of(end).ticks}" else ""

    /** Whether it plays at timeline second [time]: a placement up to its end, the others to it and including it (YouRec/EditorView.swift:627-629). */
    fun contains(time: Double): Boolean =
        if (tool == EditorTool.Placement) EditTime.of(time) in EditInterval(EditTime.of(start), EditTime.of(end)) else time in start..end
}

/**
 * The regions of every tool on an edit, which the editor offers as chips at
 * the playhead and lists under "In this project" in its tools, and taking
 * one away from where its chip is.
 */
object ToolRegions {
    /**
     * Every region of [state], as the original lists them
     * (YouRec/VideoTools.swift:567-627): the subtitles, then picture by
     * picture its flip, images, video effects, crop, speed, grade, pauses
     * and titles, then sound by sound its censors, its chains and its
     * level and fades, then the stretches where pictures play together, then
     * the transitions. An image, an effect or a title is the part of it the
     * clip shows; the others are the whole clip.
     */
    fun of(state: EditState): List<ToolRegion> {
        val result = ArrayList<ToolRegion>()
        val timeline = state.toTimeline()
        val subtitles = state.subtitles
        if (subtitles != null) {
            val cues = SubtitleEditing.cues(state)
            if (cues.isNotEmpty()) {
                result += ToolRegion(EditorTool.Subtitles, subtitles.id, cues.first().audioID, cues.first().start, cues.maxOf { it.end }, ToolRegionLabel.Tool)
            }
        }
        val starts = if (state.isSequence) TimelineEditing.layout(state.clips).placements.map { it.start } else TrackEditState.starts(state)
        state.clips.forEachIndexed { index, clip ->
            val cursor = starts.getOrElse(index) { 0.0 }
            val whole = cursor to cursor + clip.duration
            val first = result.size
            fun part(from: Double, to: Double): Pair<Double, Double>? {
                if (!(from < clip.end && to > clip.start)) return null
                val a = cursor + clip.offset(maxOf(clip.start, from))
                val b = cursor + clip.offset(minOf(clip.end, to))
                return if (b > a) a to b else null
            }
            // The part of the clip a source window shows; the whole clip without one (YouRec/VideoTools.swift:854-858).
            fun visible(active: SourceInterval?): Pair<Double, Double>? {
                active ?: return whole
                val a = cursor + clip.gradeOffset(active.start)
                val b = cursor + clip.gradeOffset(active.end)
                return if (b > a) a to b else null
            }
            fun add(tool: EditorTool, id: String, span: Pair<Double, Double>, label: ToolRegionLabel) {
                result += ToolRegion(tool, id, clip.id, span.first, span.second, label)
            }
            clip.mirror?.let { mirror -> visible(mirror.activeRange)?.let { add(EditorTool.Mirror, mirror.id, it, ToolRegionLabel.Flip(mirror.horizontal, mirror.vertical)) } }
            for (image in clip.images.orEmpty()) part(image.start, image.end)?.let { add(EditorTool.Image, image.id, it, ToolRegionLabel.Tool) }
            for (effect in clip.videoEffects.orEmpty()) part(effect.start, effect.end)?.let { add(EditorTool.VideoEffects, effect.id, it, ToolRegionLabel.Effect(effect.kind)) }
            clip.crop?.let { crop -> visible(crop.activeRange)?.let { add(EditorTool.Crop, crop.id, it, ToolRegionLabel.Framing(crop.zoom, crop.rotation)) } }
            clip.speed?.let { add(EditorTool.Speed, it.id, whole, ToolRegionLabel.Rate(it.rate)) }
            // Each grade where it is, over part of the clip or all of it (YouRec/VideoTools.swift:872-875).
            for (region in clip.visibleGrades) {
                val a = cursor + clip.gradeOffset(region.start)
                val b = cursor + clip.gradeOffset(region.end)
                if (b > a) add(EditorTool.Color, region.grade.id, a to b, ToolRegionLabel.Grade(region.grade))
            }
            // Under its effect id, else the clip's, as the original names an unnamed one (VideoTools.swift:590).
            clip.stabilization?.takeIf { it.level != StabilizationLevel.Off }?.let { stabilization ->
                visible(stabilization.activeRange)?.let { add(EditorTool.Stabilize, stabilization.id ?: clip.id, it, ToolRegionLabel.Tool) }
            }
            clip.pauses?.let { add(EditorTool.Pauses, it.id, whole, ToolRegionLabel.Tool) }
            for (text in clip.texts.orEmpty()) part(text.start, text.end)?.let { add(EditorTool.Text, text.id, it, ToolRegionLabel.Text(text.displayText)) }
            // The clip's other framings, each where it applies (YouRec/VideoTools.swift:883-889).
            for (variant in clip.framingVariants.orEmpty()) {
                val span = variant.activeRange?.let(::visible) ?: (if (variant.activeRange == null) whole else null) ?: continue
                val label = when (variant) {
                    is FramingVariant.Crop -> ToolRegionLabel.Framing(variant.value.zoom, variant.value.rotation)
                    is FramingVariant.Mirror -> ToolRegionLabel.Flip(variant.value.horizontal, variant.value.vertical)
                    is FramingVariant.Stabilization -> ToolRegionLabel.Tool
                }
                add(variant.tool, variant.id, span, label)
            }
            // A recipe with windows is a region in each of them (YouRec/VideoTools.swift:890-900).
            clip.effectWindows?.let { windows ->
                val recipes = result.subList(first, result.size).toList()
                while (result.size > first) result.removeAt(result.lastIndex)
                for (recipe in recipes) {
                    val spans = windows[recipe.effectID]
                    if (spans == null) { result += recipe; continue }
                    for (window in spans) {
                        val span = visible(window) ?: continue
                        val part = EditInterval.of(span.first, span.second).intersection(EditInterval.of(recipe.start, recipe.end)) ?: continue
                        result += recipe.copy(start = part.start.seconds, end = part.end.seconds)
                    }
                }
            }
        }
        val badges = TrackAudioRegions.badges(timeline).groupBy { it.itemID }
        for (item in timeline.audio) {
            for (badge in badges[item.id].orEmpty()) {
                val tool = if (badge.kind == AudioToolKind.Censor) EditorTool.CensorAudio else EditorTool.EnhanceAudio
                result += ToolRegion(tool, badge.effectID, item.id, badge.interval.start.seconds, badge.interval.end.seconds, ToolRegionLabel.Tool, audio = badge)
            }
            if (item.muted || item.volumeDB != 0.0 || item.fadeIn != null || item.fadeOut != null) {
                result += ToolRegion(
                    EditorTool.Volume, item.id, item.id, item.start.seconds, item.end.seconds,
                    ToolRegionLabel.Levels(item.muted, item.volumeDB, item.fadeIn != null, item.fadeOut != null),
                )
            }
        }
        for (spot in PlacementSpots.of(timeline)) {
            result += ToolRegion(
                EditorTool.Placement, spot.placementID ?: spot.video[0], spot.video[0], spot.interval.start.seconds, spot.interval.end.seconds,
                ToolRegionLabel.Placement(spot.placementID == null),
            )
        }
        if (state.isSequence) for (join in TimelineEditing.transitions(state.clips)) {
            val transition = join.transition ?: continue
            val clip = (join.outgoing ?: join.incoming)?.let { state.clips.getOrNull(it)?.id } ?: state.clips.firstOrNull()?.id ?: continue
            result += ToolRegion(
                EditorTool.Transition, transition.id, clip, join.range.start, join.range.endInclusive,
                ToolRegionLabel.Transition(transition.style), join = join.index,
            )
        }
        return result
    }

    /** The regions at timeline second [time], which the editor offers as chips (YouRec/EditorView.swift:626-629). */
    fun at(regions: List<ToolRegion>, time: Double): List<ToolRegion> = regions.filter { it.contains(time) }

    /**
     * The regions "In this project" lists (YouRec/EditorView.swift:977-989):
     * neither transitions nor placements; of the items [selected] when there
     * are any, inside its [range] when it has one; those at [time] first,
     * then by where they begin; each effect once.
     */
    fun listed(regions: List<ToolRegion>, time: Double, selected: Set<String> = emptySet(), range: ClosedFloatingPointRange<Double>? = null): List<ToolRegion> {
        val seen = HashSet<String>()
        return regions.filter { region ->
            region.tool != EditorTool.Transition && region.tool != EditorTool.Placement &&
                (selected.isEmpty() || region.clipID in selected && (range == null || region.start <= range.endInclusive && region.end >= range.start))
        }.sortedWith(compareBy<ToolRegion> { !(time >= it.start && time < it.end) }.thenBy { it.start })
            .filter { seen.add(it.tool.name + it.effectID) }
    }

    /**
     * [state] without [region], from where its chip is: a badge's removal
     * stays on its own piece, the other pieces of the same effect keep it
     * (YouRec/VideoTools.swift:455-548). A censor goes from the overlapping
     * sounds it was made on at once; a level goes back to 0 dB, unmuted and
     * without fades; Freeze Frame takes its held frame with it. Null when
     * nothing changes.
     */
    fun removing(state: EditState, region: ToolRegion): EditState? {
        val index = state.clips.indexOfFirst { it.id == region.clipID }
        val clip = state.clips.getOrNull(index)
        fun recipe(change: (TimelineClip) -> TimelineClip): EditState? {
            clip ?: return null
            val changed = change(clip)
            if (changed == clip) return null
            return TimelineEditing.recipes(state, state.clips.toMutableList().also { it[index] = changed })
        }
        return when (region.tool) {
            EditorTool.Subtitles -> SubtitleEditing.remove(state).takeIf { it != state }
            EditorTool.CensorAudio, EditorTool.EnhanceAudio -> {
                val badge = region.audio ?: return null
                timelineEdit(state) { TrackAudioRegions.removing(it, badge.kind, badge.effectID, TrackAudioRegions.targets(it, badge)) }
            }
            EditorTool.Volume -> timelineEdit(state) { timeline ->
                timeline.copy(audio = timeline.audio.map { item ->
                    if (item.id != region.effectID) item else item.copy(volumeDB = 0.0, muted = false, fadeIn = null, fadeOut = null)
                })
            }
            EditorTool.Placement -> timelineEdit(state) { TrackPlacements.removing(it, region.effectID) }
            EditorTool.Transition -> region.join?.let { TimelineEditing.setJoin(state, it, null) }
            EditorTool.VideoEffects -> {
                val effect = clip?.videoEffects?.firstOrNull { it.id == region.effectID } ?: return null
                if (clip.frozenFrame != null && effect.kind == VideoEffectID.FreezeFrame) TimelineEditing.delete(state, index)
                else recipe { it.copy(videoEffects = it.videoEffects?.filter { e -> e.id != region.effectID }?.ifEmpty { null }) }
            }
            EditorTool.Text -> recipe { it.copy(texts = it.texts?.filter { t -> t.id != region.effectID }?.ifEmpty { null }) }
            EditorTool.Image -> recipe { it.copy(images = it.images?.filter { i -> i.id != region.effectID }?.ifEmpty { null }) }
            EditorTool.Speed -> if (clip?.speed?.id == region.effectID) TimelineEditing.setSpeed(state, index, 1.0) else null
            EditorTool.Pauses -> if (clip?.pauses?.id == region.effectID) TimelineEditing.setPauses(state, index, null) else null
            EditorTool.Color -> recipe { if (it.color?.id == region.effectID) it.copy(color = null) else it }
            EditorTool.Crop -> recipe { if (it.crop?.id == region.effectID) it.copy(crop = null) else it }
            EditorTool.Mirror -> recipe { if (it.mirror?.id == region.effectID) it.copy(mirror = null) else it }
            EditorTool.Stabilize -> recipe { if ((it.stabilization?.id ?: it.id) == region.effectID) it.copy(stabilization = null) else it }
        }
    }

    /** [state] with [change] made to its timeline; null when nothing changes. */
    private fun timelineEdit(state: EditState, change: (TrackTimeline) -> TrackTimeline): EditState? {
        val timeline = state.toTimeline()
        val next = change(timeline)
        return if (next == timeline) null else TrackEditState.edit(next, state.captureBase)
    }
}
