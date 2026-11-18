package app.takes.core

/**
 * The one recipe a scoped inspector copies in and out. A change to a clip-wide
 * canvas, or to another member of a stack of effects, cannot escape it
 * (YouRec/ScopedEffect.swift:135-169).
 */
internal sealed interface ScopedRecipe {
    data class Text(val value: TextOverlay) : ScopedRecipe
    data class Image(val value: ImageOverlay) : ScopedRecipe
    data class Effect(val value: VideoEffectSettings) : ScopedRecipe
    data class Framing(val value: FramingVariant) : ScopedRecipe
    data class Grade(val value: ColorSettings) : ScopedRecipe

    fun identified(id: String): ScopedRecipe = when (this) {
        is Text -> Text(value.copy(id = id))
        is Image -> Image(value.copy(id = id))
        is Effect -> {
            var v = value
            if (v.id != id && (v.analysisFile != null || v.kind == VideoEffectID.ChromaKey || v.kind == VideoEffectID.AutoBackground)) v = v.copy(capturedEffectID = v.analysisID)
            Effect(v.copy(id = id))
        }
        is Framing -> Framing(value.identified(id))
        is Grade -> Grade(value.copy(id = id))
    }

    /** Keeps the authored clock and geometry window: scope edges say where it shows, not a new fade, strobe or zoom. */
    fun keepingTiming(original: ScopedRecipe): ScopedRecipe = when {
        this is Text && original is Text -> Text(value.copy(start = original.value.start, end = original.value.end))
        this is Image && original is Image -> Image(value.copy(
            start = original.value.start, end = original.value.end,
            motion = original.value.motion?.clock?.let { clock -> value.motion?.copy(clock = clock) } ?: value.motion,
        ))
        this is Effect && original is Effect -> Effect(value.copy(
            start = original.value.start, end = original.value.end, phaseOffset = original.value.phaseOffset, strobeTimeMap = original.value.strobeTimeMap,
            motion = original.value.motion?.clock?.let { clock -> value.motion?.copy(clock = clock) } ?: value.motion,
        ))
        this is Framing && original is Framing -> {
            val v = value
            val old = original.value
            when {
                v is FramingVariant.Crop && old is FramingVariant.Crop -> Framing(FramingVariant.Crop(v.value.copy(
                    activeRange = old.value.activeRange,
                    motion = old.value.motion?.clock?.let { clock -> v.value.motion?.copy(clock = clock) } ?: v.value.motion,
                )))
                v is FramingVariant.Mirror && old is FramingVariant.Mirror -> Framing(FramingVariant.Mirror(v.value.copy(activeRange = old.value.activeRange)))
                v is FramingVariant.Stabilization && old is FramingVariant.Stabilization -> Framing(FramingVariant.Stabilization(v.id, v.value.copy(activeRange = old.value.activeRange)))
                else -> this
            }
        }
        this is Grade && original is Grade -> Grade(value.copy(motion = original.value.motion?.clock?.let { clock -> value.motion?.copy(clock = clock) } ?: value.motion))
        else -> this
    }
}

/** The recipe [id] of [tool] on the clip (YouRec/ScopedEffect.swift:172-180). */
internal fun TimelineClip.scopedRecipe(tool: EditorTool, id: String): ScopedRecipe? = when (tool) {
    EditorTool.Text -> texts?.firstOrNull { it.id == id }?.let { ScopedRecipe.Text(it) }
    EditorTool.Image -> images?.firstOrNull { it.id == id }?.let { ScopedRecipe.Image(it) }
    EditorTool.VideoEffects -> videoEffects?.firstOrNull { it.id == id }?.let { ScopedRecipe.Effect(it) }
    EditorTool.Color -> visibleGrades.firstOrNull { it.grade.id == id }?.let { ScopedRecipe.Grade(it.grade) }
    else -> (framing(tool)?.takeIf { it.id == id } ?: framingVariants?.firstOrNull { it.id == id })?.let { ScopedRecipe.Framing(it) }
}

/** The clip with the recipe [id] of [tool] set, added beside [parent], or taken away when [recipe] is null (YouRec/ScopedEffect.swift:181-206). */
internal fun TimelineClip.settingScopedRecipe(recipe: ScopedRecipe?, tool: EditorTool, id: String, parent: String): TimelineClip {
    fun <T> update(list: List<T>?, value: T?, idOf: (T) -> String): List<T>? {
        val values = list.orEmpty().toMutableList()
        val index = values.indexOfFirst { idOf(it) == id }
        if (index >= 0) {
            if (value != null) values[index] = value else values.removeAt(index)
        } else if (value != null) {
            val after = values.indexOfFirst { idOf(it) == parent }
            values.add(if (after >= 0) after + 1 else values.size, value)
        }
        return values.ifEmpty { null }
    }
    return when (tool) {
        EditorTool.Text -> copy(texts = update(texts, (recipe as? ScopedRecipe.Text)?.value) { it.id })
        EditorTool.Image -> copy(images = update(images, (recipe as? ScopedRecipe.Image)?.value) { it.id })
        EditorTool.VideoEffects -> copy(videoEffects = update(videoEffects, (recipe as? ScopedRecipe.Effect)?.value) { it.id })
        EditorTool.Crop, EditorTool.Mirror, EditorTool.Stabilize -> {
            var clip = this
            val alternatives = framingVariants.orEmpty().filter { it.id != id }.toMutableList()
            val current = framing(tool)
            if (current?.id == id || current == null) {
                val value = (recipe as? ScopedRecipe.Framing)?.value
                if (value != null) clip = clip.settingFraming(value)
                else {
                    val index = alternatives.indexOfFirst { it.tool == tool }
                    clip = if (index >= 0) clip.settingFraming(alternatives.removeAt(index))
                    else when (tool) {
                        EditorTool.Crop -> clip.copy(crop = null)
                        EditorTool.Mirror -> clip.copy(mirror = null)
                        else -> clip.copy(stabilization = null)
                    }
                }
            } else (recipe as? ScopedRecipe.Framing)?.let { alternatives += it.value }
            clip.copy(framingVariants = alternatives.ifEmpty { null })
        }
        else -> this
    }
}

/** The clip with the source windows of [id] made [ranges], joined; none when empty (YouRec/ScopedEffect.swift:207-212). */
internal fun TimelineClip.settingWindows(ranges: List<EditInterval>, id: String): TimelineClip {
    val windows = effectWindows.orEmpty().toMutableMap()
    val spans = TrackEditing.union(ranges).map { SourceInterval(it.start.seconds, it.end.seconds) }
    if (spans.isEmpty()) windows.remove(id) else windows[id] = spans
    return copy(effectWindows = windows.ifEmpty { null })
}

/**
 * An effect edited or removed on part of its application: on the clip of its
 * badge, on the selected clips, or in the marked range, without cutting,
 * unlinking or retiming any footage (YouRec/ScopedEffect.swift:215-480). The
 * part of a local edit becomes a variant of its own; what is left keeps the
 * effect's id. [scopeName] is how the menu names the part.
 */
class ScopedEffectOperation private constructor(
    val region: ToolRegion,
    /** The pieces of the effect's application. */
    val event: List<ToolRegion>,
    /** The part edited, by item, on the timeline's clock. */
    val parts: Map<String, List<EditInterval>>,
    val local: Boolean,
    val scopeName: ScopeName,
    private val variant: String = newID(),
) {
    enum class ScopeName { MarkedRange, ThisClip, ThisAudioClip, SelectedClips }

    /** The stretch the part spans. */
    val range: ClosedFloatingPointRange<Double>
        get() {
            val all = parts.values.flatten()
            return all.minOf { it.start }.seconds..all.maxOf { it.end }.seconds
        }

    /** The id the edited part renders under: a new one for a local edit. */
    val renderedID: String get() = if (local) variant else region.effectID

    fun contains(projectTime: Double, owner: String? = null): Boolean {
        val t = EditTime.of(projectTime)
        if (owner != null) return parts[owner]?.any { t in it } == true
        return parts.values.any { spans -> spans.any { t in it } }
    }

    /**
     * Whether the effect can be edited only through this operation: a part of
     * it, or an application of several pieces of one item, a hole in it, or
     * one already made of windows or variants. An inspector can still edit a
     * simple whole application directly (YouRec/ScopedEffect.swift:260-275).
     */
    fun needsProjection(state: EditState): Boolean = needsProjection(state.toTimeline())

    internal fun needsProjection(timeline: TrackTimeline): Boolean {
        if (local) return true
        if (event.size > event.map { it.clipID }.toSet().size) return true
        if (TrackEditing.union(event.map { it.interval }).size > 1) return true
        if (region.tool.isAudioRange || region.tool == EditorTool.Color) {
            val hull = EditInterval.of(range.start, range.endInclusive)
            val owners = if (region.tool.isAudioRange) timeline.audio.map { it.id to it.interval } else timeline.video.map { it.id to it.interval }
            for ((id, interval) in owners) {
                val spans = parts[id] ?: continue
                val intersection = interval.intersection(hull) ?: continue
                if (spans != listOf(intersection)) return true
            }
        }
        return timeline.video.any { item ->
            parts[item.id] != null && (item.content.effectWindows?.get(region.effectID) != null || item.content.framingVariants?.any { it.id == region.effectID } == true)
        }
    }

    /** What the inspector opens on: each variant of the effect in its slot (YouRec/ScopedEffect.swift:283-292). */
    fun inspectorState(state: EditState): EditState = EffectApplications.editing(state) { timeline ->
        timeline.copy(video = timeline.video.map { item ->
            val variant = if (parts[item.id] != null) item.content.framingVariants?.firstOrNull { it.id == region.effectID } else null
            if (variant != null) item.copy(content = item.content.promoting(variant)) else item
        })
    }

    /** [state] without the effect in the part (YouRec/ScopedEffect.swift:294). */
    fun removing(state: EditState): EditState = updating(state, null)

    /** [state] with the effect in the part as the inspector left it in [edited] (YouRec/ScopedEffect.swift:295). */
    fun applying(edited: EditState, state: EditState): EditState = updating(state, edited)

    private fun updating(state: EditState, edited: EditState?): EditState {
        val base = state.toTimeline()
        val draft = edited?.toTimeline()
        var outputSize = state.outputSize
        val id = region.effectID
        val destination = if (local) variant else id
        var changed = false
        if (!local && region.tool == EditorTool.Crop && edited != null && edited.outputSize != state.outputSize) {
            outputSize = edited.outputSize
            changed = true
        }
        val titles = if (region.tool == EditorTool.Text) TitleMotionRanges(state.clips) else null
        fun titleClock(item: VideoTimelineItem, title: TextOverlay): TitlePlaybackClock? {
            title.playbackClock?.let { return it }
            val occurrence = event.firstOrNull { it.clipID == item.id } ?: return null
            val range = titles?.range(title, (occurrence.start + occurrence.end) / 2) ?: return null
            val anchor = EffectAnimation(EffectPose(emptyMap()), range.endInclusive - range.start).anchoredTo(item.content, item.start.seconds - range.start)
            return TitlePlaybackClock(anchor.duration, anchor.clock)
        }
        val video = base.video.toMutableList()
        for (i in video.indices) {
            val item = video[i]
            val selected = parts[item.id] ?: continue
            val original = item.content.scopedRecipe(region.tool, id) ?: continue
            var next = draft?.video?.firstOrNull { it.id == item.id }?.content?.scopedRecipe(region.tool, id)?.keepingTiming(original)?.identified(id)
            if (next == original) continue
            if (next is ScopedRecipe.Text && original is ScopedRecipe.Text && next.value.motion == original.value.motion) {
                next = ScopedRecipe.Text(next.value.copy(playbackClock = titleClock(item, original.value)))
            }
            val affected = sourceRanges(selected, item)
            val coverage = sourceRanges(event.filter { it.clipID == item.id }.map { it.interval }, item)
            if (affected.isEmpty()) continue
            var clip = item.content
            if (region.tool == EditorTool.Stabilize && clip.stabilization != null && clip.stabilization!!.id == null) {
                clip = clip.copy(stabilization = clip.stabilization!!.copy(id = item.content.id))
            }
            if (region.tool == EditorTool.Color) {
                var grades = clip.visibleGrades
                for (part in affected) {
                    grades = grades.flatMap { entry ->
                        val span = EditInterval.of(entry.start, entry.end)
                        val inside = if (entry.grade.id == id) span.intersection(part) else null
                        if (inside == null) return@flatMap listOf(entry)
                        val pieces = span.subtracting(inside).map { ColorRegion(it.start.seconds, it.end.seconds, entry.grade) }.toMutableList()
                        val grade = (next?.identified(destination) as? ScopedRecipe.Grade)?.value
                        if (grade != null) pieces += ColorRegion(inside.start.seconds, inside.end.seconds, grade)
                        pieces
                    }
                }
                clip = clip.copy(color = null, colorRegions = grades.sortedBy { it.start }.ifEmpty { null })
            } else {
                val outside = affected.fold(coverage) { rest, part -> rest.flatMap { it.subtracting(part) } }
                if (!local && next != null) clip = clip.settingScopedRecipe(next, region.tool, id, id)
                else {
                    if (next != null) {
                        clip = clip.settingScopedRecipe(next.identified(destination), region.tool, destination, id)
                        clip = clip.settingWindows(affected, destination)
                    }
                    clip = clip.settingWindows(outside, id)
                    if (outside.isEmpty()) clip = clip.settingScopedRecipe(null, region.tool, id, id)
                }
            }
            video[i] = item.copy(content = clip)
            changed = true
        }
        val audio = base.audio.toMutableList()
        if (region.tool.isAudioRange) for (i in audio.indices) {
            var item = audio[i]
            val selected = parts[item.id] ?: continue
            val draftItem = draft?.audio?.firstOrNull { it.id == item.id }
            for (projectPart in selected) {
                val localPart = EditInterval(projectPart.start - item.start, projectPart.end - item.start)
                if (region.tool == EditorTool.EnhanceAudio) {
                    val next = draftItem?.enhancements?.firstOrNull { it.id == id && it.interval.intersection(localPart) != null }?.effects
                    item = item.copy(enhancements = item.enhancements.flatMap { entry ->
                        val hit = if (entry.id == id) entry.interval.intersection(localPart) else null
                        if (hit == null || next == entry.effects) return@flatMap listOf(entry)
                        changed = true
                        val pieces = entry.interval.subtracting(hit).map { entry.copy(interval = it) }.toMutableList()
                        if (next != null) pieces += AudioEnhancementRegion(destination, hit, next)
                        pieces.sortedBy { it.interval.start }
                    })
                } else {
                    val next = draftItem?.censors?.firstOrNull { it.id == id && it.interval.intersection(localPart) != null }?.settings
                    item = item.withCensors(item.censors.flatMap { entry ->
                        val hit = if (entry.id == id) entry.interval.intersection(localPart) else null
                        if (hit == null || next == entry.settings) return@flatMap listOf(entry)
                        changed = true
                        val envelope = entry.envelopeInterval ?: entry.interval
                        val pieces = entry.interval.subtracting(hit).map { entry.copy(interval = it, envelopeInterval = envelope) }.toMutableList()
                        if (next != null) pieces += entry.copy(id = destination, interval = hit, envelopeInterval = envelope, settings = next)
                        pieces.sortedBy { it.interval.start }
                    })
                }
            }
            audio[i] = item
        }
        if (!changed) return state
        val owners = event.mapTo(HashSet()) { it.clipID }
        if (region.tool.isAudioRange) {
            for (i in audio.indices) if (audio[i].id in owners) audio[i] = audio[i].copy(spanningEffects = audio[i].spanningEffects.orEmpty() + setOf(id, destination))
        } else {
            for (i in video.indices) if (video[i].id in owners) {
                var item = video[i]
                if (region.tool == EditorTool.Text) {
                    val j = item.content.texts?.indexOfFirst { it.id == id } ?: -1
                    val old = base.video[i].content.texts?.firstOrNull { it.id == id }
                    if (j >= 0 && old != null && item.content.texts!![j].motion == old.motion) {
                        val texts = item.content.texts!!.toMutableList()
                        texts[j] = texts[j].copy(playbackClock = titleClock(base.video[i], old))
                        item = item.copy(content = item.content.copy(texts = texts))
                    }
                }
                video[i] = item.copy(spanningEffects = item.spanningEffects.orEmpty() + setOf(id, destination))
            }
        }
        val timeline = EffectApplications.normalized(base.copy(video = video, audio = audio))
        return TrackEditState.edit(timeline, state.captureBase).copy(outputSize = outputSize)
    }

    /**
     * [state] with the effect's range made [range] by the handles: the
     * recipes and clocks of [proposed], installed only on the old visible
     * pieces and the edges the handles swept, on the items in [eligible]
     * (YouRec/ScopedEffect.swift:411-472).
     */
    internal fun reshaped(state: EditState, proposed: EditState, range: EditInterval, eligible: Set<String>): EditState {
        val base = state.toTimeline()
        val draft = proposed.toTimeline()
        val hull = EditInterval.of(this.range.start, this.range.endInclusive)
        val id = region.effectID
        val tool = region.tool
        val growth = range.subtracting(hull)
        val others = ToolRegions.of(state).filter { it.tool == tool && it.effectID != id }
        fun coverage(owner: String, track: EditInterval): List<EditInterval> {
            var newlyCovered = growth.mapNotNull { it.intersection(track) }
            if (tool.isAudioRange || tool == EditorTool.Color || tool == EditorTool.Mirror || tool == EditorTool.Crop || tool == EditorTool.Stabilize) {
                for (other in others) if (other.clipID == owner) newlyCovered = newlyCovered.flatMap { it.subtracting(other.interval) }
            }
            return TrackEditing.union(parts[owner].orEmpty().mapNotNull { it.intersection(range) } + newlyCovered)
        }
        val video = base.video.map { item ->
            if (tool.isAudioRange || item.id !in eligible || (parts[item.id] == null && growth.none { it.intersection(item.interval) != null })) return@map item
            val spans = sourceRanges(coverage(item.id, item.interval), item)
            var clip = item.content
            val recipe = draft.video.firstOrNull { it.id == item.id }?.content?.scopedRecipe(tool, id)
            if (tool == EditorTool.Color) {
                var grades = clip.visibleGrades.filter { it.grade.id != id }
                if (recipe is ScopedRecipe.Grade) grades = grades + spans.map { ColorRegion(it.start.seconds, it.end.seconds, recipe.value) }
                clip = clip.copy(color = null, colorRegions = grades.sortedBy { it.start }.ifEmpty { null })
            } else {
                clip = clip.settingScopedRecipe(if (spans.isEmpty()) null else recipe, tool, id, id)
                clip = clip.settingWindows(if (recipe == null) emptyList() else spans, id)
            }
            item.copy(content = clip, spanningEffects = if (spans.isNotEmpty()) item.spanningEffects.orEmpty() + id else item.spanningEffects)
        }
        val audio = base.audio.map { item ->
            if (!tool.isAudioRange || item.id !in eligible) return@map item
            val draftItem = draft.audio.firstOrNull { it.id == item.id } ?: return@map item
            if (parts[item.id] == null && growth.none { it.intersection(item.interval) != null }) return@map item
            val spans = coverage(item.id, item.interval).map { EditInterval(it.start - item.start, it.end - item.start) }
            var next = if (tool == EditorTool.EnhanceAudio) {
                val copies = draftItem.enhancements.filter { it.id == id }.flatMap { value -> spans.mapNotNull { span -> span.intersection(value.interval)?.let { value.copy(interval = it) } } }
                item.copy(enhancements = (item.enhancements.filter { it.id != id } + copies).sortedBy { it.interval.start })
            } else {
                val copies = draftItem.censors.filter { it.id == id }.flatMap { value -> spans.mapNotNull { span -> span.intersection(value.interval)?.let { value.copy(interval = it) } } }
                item.withCensors((item.censors.filter { it.id != id } + copies).sortedBy { it.interval.start })
            }
            if (spans.isNotEmpty()) next = next.copy(spanningEffects = item.spanningEffects.orEmpty() + id)
            next
        }
        val timeline = EffectApplications.normalized(base.copy(video = video, audio = audio))
        return TrackEditState.edit(timeline, state.captureBase).copy(outputSize = state.outputSize)
    }

    /** [parts] of [item] on the timeline's clock as source stretches, joined (YouRec/ScopedEffect.swift:474-479). */
    private fun sourceRanges(parts: List<EditInterval>, item: VideoTimelineItem): List<EditInterval> =
        TrackEditing.union(parts.map { span ->
            EditInterval(
                EditTime.of(item.content.gradeTime((span.start - item.start).seconds)),
                EditTime.of(item.content.gradeTime((span.end - item.start).seconds)),
            )
        }.filter { !it.isEmpty })

    companion object {
        /**
         * The operation on [region]'s effect for [selection]: its part on the
         * selected items, inside the marked range when there is one; the whole
         * application without a selection. Null for a tool without an editable
         * range, a time effect, which retimes the footage, a selection that
         * misses the application, or one that covers all of it
         * (YouRec/ScopedEffect.swift:223-247).
         */
        fun of(region: ToolRegion, state: EditState, selection: TimelineSelection?, all: List<ToolRegion> = ToolRegions.of(state)): ScopedEffectOperation? {
            if (!region.tool.hasEditableRange) return null
            if (region.tool == EditorTool.VideoEffects) {
                val effect = state.clips.firstOrNull { it.id == region.clipID }?.videoEffects?.firstOrNull { it.id == region.effectID }
                if (effect != null && effect.kind.descriptor.family == EffectFamily.Time) return null
            }
            val event = EffectApplications.applicationRegions(state, region, all.filter { it.tool == region.tool && it.effectID == region.effectID })
            val parts = LinkedHashMap<String, MutableList<EditInterval>>()
            val isAudio = region.tool.isAudioRange
            val selected = selection?.let { if (isAudio) it.audio else it.video }
            for (member in event) {
                if (selected != null && member.clipID !in selected) continue
                val interval = member.interval
                val marked = selection?.range
                if (marked != null) interval.intersection(marked)?.let { parts.getOrPut(member.clipID) { mutableListOf() } += it }
                else parts.getOrPut(member.clipID) { mutableListOf() } += interval
            }
            if (parts.isEmpty()) return null
            val local = selection != null
            if (local) {
                val complete = event.all { member -> parts[member.clipID]?.contains(member.interval) == true }
                if (complete) return null
            }
            val scopeName = when {
                selection?.range != null -> ScopeName.MarkedRange
                parts.size == 1 -> if (isAudio) ScopeName.ThisAudioClip else ScopeName.ThisClip
                else -> ScopeName.SelectedClips
            }
            return ScopedEffectOperation(region, event, parts.mapValues { TrackEditing.union(it.value) }, local, scopeName)
        }
    }
}

/** The motion with its clock tied to [clip]'s playback, [offset] seconds in: along its hold for a held frame (YouRec/EffectAnimation.swift:109-127). */
fun EffectAnimation.anchoredTo(clip: TimelineClip, offset: Double): EffectAnimation =
    clip.frozenFrame?.let { anchored(it, offset) } ?: anchored(clip.playbackSpans, offset)
