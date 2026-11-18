package app.takes.core

/*
 * One application of an effect: the pieces of the same effect that were made
 * together, across cuts, takes and gaps, as the original reopens and trims
 * them since 57e5d91 (YouRec/VideoTools.swift:599-704, 705-737, 805-840).
 */

/** The sound tools whose regions are ranges of a sound (YouRec/VideoTools.swift:600). */
val EditorTool.isAudioRange: Boolean get() = this == EditorTool.CensorAudio || this == EditorTool.EnhanceAudio

/** The tools whose regions have a range of their own that can be edited (YouRec/VideoTools.swift:601). */
val EditorTool.hasEditableRange: Boolean
    get() = isAudioRange || this == EditorTool.Text || this == EditorTool.Image || this == EditorTool.Color ||
        this == EditorTool.VideoEffects || this == EditorTool.Crop || this == EditorTool.Mirror || this == EditorTool.Stabilize

/** The region's stretch on the editor's clock. */
val ToolRegion.interval: EditInterval get() = EditInterval.of(start, end)

/** [EditInterval] less [part]: what is before it and what is after it, without empty pieces. */
internal fun EditInterval.subtracting(part: EditInterval): List<EditInterval> {
    val hit = intersection(part) ?: return listOf(this)
    return listOf(EditInterval(start, hit.start), EditInterval(hit.end, end)).filter { !it.isEmpty }
}

/** What a tool opened from a region acts on: [range] in timeline seconds, the pictures [clipIDs] (null for a sound tool), and the [targets]. */
data class EffectScope(
    val tool: EditorTool,
    val effectID: String,
    val range: ClosedFloatingPointRange<Double>,
    val clipIDs: Set<String>?,
    val targets: TimelineSelection,
    /** "This clip", "This audio section", or how many clips or sections, as the original titles it. */
    val title: Title,
) {
    sealed interface Title {
        data object ThisClip : Title
        data object ThisAudioSection : Title
        data class Clips(val count: Int) : Title
        data class AudioSections(val count: Int) : Title
    }
}

object EffectApplications {
    /** [state] with [change] made to its timeline: a sequence again when it is one. Itself when nothing changes. */
    internal fun editing(state: EditState, change: (TrackTimeline) -> TrackTimeline): EditState {
        val timeline = state.toTimeline()
        val next = change(timeline)
        if (next == timeline) return state
        return TrackEditState.edit(next, state.captureBase).copy(outputSize = state.outputSize)
    }

    /**
     * The pieces of the effect of [region] that are one application with it,
     * among [matching], the regions of the same tool and effect. Pieces of an
     * item that remembers the effect ([VideoTimelineItem.spanningEffects]) join
     * one another; older edits have no such memory, so touching pieces of
     * different takes with the same recipe join, while a plain split, which
     * copied its recipe, stays apart unless [openingApplication]
     * (YouRec/VideoTools.swift:613-686).
     */
    fun applicationRegions(state: EditState, region: ToolRegion, matching: List<ToolRegion>, openingApplication: Boolean = true): List<ToolRegion> =
        applicationRegions(state.toTimeline(), region, matching, openingApplication)

    fun applicationRegions(timeline: TrackTimeline, region: ToolRegion, matching: List<ToolRegion>, openingApplication: Boolean = true): List<ToolRegion> {
        if (!region.tool.hasEditableRange || matching.size <= 1) return listOf(region)
        val audio = region.tool.isAudioRange
        val id = region.effectID
        val owners: Map<String, Pair<String, Boolean>> =
            if (audio) timeline.audio.associate { it.id to (it.occurrenceID to (it.spanningEffects?.contains(id) == true)) }
            else timeline.video.associate { it.id to (it.occurrenceID to (it.spanningEffects?.contains(id) == true)) }
        val pictures = timeline.video.associate { it.id to it.content }
        val sounds = timeline.audio.associateBy { it.id }

        // Earlier builds sometimes copied the id while letting the two halves be restyled apart. Keep that apart when opening an old edit.
        fun recipe(clip: TimelineClip): Any? = when (region.tool) {
            EditorTool.Text -> clip.texts?.firstOrNull { it.id == id }?.copy(start = 0.0, end = 1.0)
            EditorTool.Image -> clip.images?.firstOrNull { it.id == id }?.let { it.copy(start = 0.0, end = 1.0, motion = it.motion?.copy(clock = emptyList())) }
            EditorTool.Color -> clip.visibleGrades.firstOrNull { it.grade.id == id }?.grade?.let { it.copy(motion = it.motion?.copy(clock = emptyList())) }
            EditorTool.Mirror -> clip.mirror?.copy(activeRange = null)
            EditorTool.Crop -> clip.crop?.let { it.copy(activeRange = null, motion = it.motion?.copy(clock = emptyList())) }
            EditorTool.Stabilize -> clip.stabilization?.level ?: StabilizationLevel.Off
            EditorTool.VideoEffects -> clip.videoEffects?.firstOrNull { it.id == id }?.let {
                it.copy(start = 0.0, end = 1.0, motion = it.motion?.copy(clock = emptyList()), strobeTimeMap = null, phaseOffset = null)
            }
            else -> null
        }
        fun sameLegacyRecipe(a: ToolRegion, b: ToolRegion): Boolean {
            if (audio) {
                val l = sounds[a.clipID] ?: return false
                val r = sounds[b.clipID] ?: return false
                val la = a.interval.start - l.start
                val rb = b.interval.start - r.start
                if (region.tool == EditorTool.CensorAudio) {
                    return l.censors.firstOrNull { it.id == id && it.interval.start == la }?.settings ==
                        r.censors.firstOrNull { it.id == id && it.interval.start == rb }?.settings
                }
                return l.enhancements.firstOrNull { it.id == id && it.interval.start == la }?.effects ==
                    r.enhancements.firstOrNull { it.id == id && it.interval.start == rb }?.effects
            }
            val l = pictures[a.clipID] ?: return false
            val r = pictures[b.clipID] ?: return false
            return recipe(l) == recipe(r)
        }
        val event = mutableListOf(region)
        val remaining = matching.filter { it.clipID != region.clipID || it.interval != region.interval }.toMutableList()
        while (remaining.isNotEmpty()) {
            val next = remaining.filter { candidate ->
                val owner = owners[candidate.clipID] ?: return@filter false
                event.any { member ->
                    val other = owners[member.clipID] ?: return@any false
                    if (candidate.clipID == member.clipID) return@any owner.second
                    val a = candidate.interval
                    val b = member.interval
                    if (owner.second && other.second) return@any true
                    if (region.tool == EditorTool.CensorAudio && a.intersection(b) != null) return@any true
                    (openingApplication || owner.first != other.first) && maxOf(a.start, b.start) <= minOf(a.end, b.end) && sameLegacyRecipe(candidate, member)
                }
            }
            if (next.isEmpty()) break
            event += next
            remaining.removeAll { candidate -> next.any { it.clipID == candidate.clipID && it.interval == candidate.interval } }
        }
        return event
    }

    /**
     * What a tool opened from [region] acts on (YouRec/VideoTools.swift:688-704):
     * the whole application when the badge is opened ([openingBadge]), else the
     * pieces of it on the items of [selection], inside its range when it has one.
     */
    fun scope(state: EditState, region: ToolRegion, selection: TimelineSelection = TimelineSelection(), openingBadge: Boolean = false, regions: List<ToolRegion> = ToolRegions.of(state)): EffectScope {
        val audio = region.tool == EditorTool.EnhanceAudio || region.tool == EditorTool.Volume || region.tool == EditorTool.CensorAudio
        val matching = regions.filter { it.tool == region.tool && it.effectID == region.effectID }
        val event = applicationRegions(state, region, matching, openingBadge)
        val selected = if (audio) selection.audio else selection.video
        val whole = openingBadge && region.tool.hasEditableRange
        val scoped = if (whole) emptyList() else matching.filter { it.clipID in selected && (selection.range == null || selection.range.intersection(it.interval) != null) }
        val regionsInScope = scoped.ifEmpty { event }
        val ids = regionsInScope.mapTo(LinkedHashSet()) { it.clipID }
        val bounds = EditInterval.of(regionsInScope.minOfOrNull { it.start } ?: region.start, regionsInScope.maxOfOrNull { it.end } ?: region.end)
        val range = if (scoped.isEmpty()) bounds else selection.range?.intersection(bounds) ?: bounds
        val title = when {
            ids.size == 1 -> if (audio) EffectScope.Title.ThisAudioSection else EffectScope.Title.ThisClip
            audio -> EffectScope.Title.AudioSections(ids.size)
            else -> EffectScope.Title.Clips(ids.size)
        }
        val targetRange = if (audio && region.tool.isAudioRange) range else if (scoped.isEmpty()) null else selection.range
        val targets = TimelineSelection(video = if (audio) emptySet() else ids, audio = if (audio) ids else emptySet(), range = targetRange)
        return EffectScope(region.tool, region.effectID, range.seconds, if (audio) null else ids, targets, title)
    }

    /**
     * [after] with the membership of each new application of [tool] that the
     * edit from [before] made remembered on the items it is on, so a deliberate
     * application across a gap is told from pieces a cut left
     * (YouRec/VideoTools.swift:705-737). An existing application keeps its membership.
     */
    fun remembering(tool: EditorTool, after: EditState, before: EditState): EditState {
        if (!tool.hasEditableRange) return after
        val timeline = after.toTimeline()
        val old = before.toTimeline()
        val changed: Set<String> = if (tool.isAudioRange) {
            val was = old.audio.associateBy { it.id }
            timeline.audio.filter { was[it.id] != it }.mapTo(HashSet()) { it.id }
        } else {
            val was = old.video.associate { it.id to it.content }
            timeline.video.filter { was[it.id] != it.content }.mapTo(HashSet()) { it.id }
        }
        if (changed.isEmpty()) return after
        val groups = ToolRegions.of(after).filter { it.tool == tool && it.clipID in changed }.groupBy { it.effectID }
        val oldRegions = ToolRegions.of(before).filter { it.tool == tool }
        var result = timeline
        for ((id, parts) in groups) {
            if (oldRegions.any { it.effectID == id }) continue
            val ids = parts.mapTo(HashSet()) { it.clipID }
            result = if (tool.isAudioRange) result.copy(audio = result.audio.map { if (it.id in ids) it.copy(spanningEffects = it.spanningEffects.orEmpty() + id) else it })
            else result.copy(video = result.video.map { if (it.id in ids) it.copy(spanningEffects = it.spanningEffects.orEmpty() + id) else it })
        }
        if (result == timeline) return after
        return TrackEditState.edit(result, after.captureBase).copy(outputSize = after.outputSize)
    }

    /**
     * [state] with [grade] over the timeline's [range] on the pictures
     * [videos], and the grades there cut around it; with [removingOnly], only
     * that grade is taken away there. A motion without a clock is tied to
     * each picture's playback, keeping the clock of the same motion found in
     * [original] (YouRec/VideoTools.swift:231-248).
     */
    fun settingGrade(
        state: EditState, grade: ColorSettings?, range: ClosedFloatingPointRange<Double>, videos: Set<String>,
        removingOnly: String? = null, original: TrackTimeline? = null,
    ): EditState = editing(state) { timeline ->
        timeline.copy(video = timeline.video.map { item ->
            if (item.id !in videos) return@map item
            val part = item.interval.intersection(EditInterval.of(range.start, range.endInclusive)) ?: return@map item
            val a = item.content.gradeTime((part.start - item.start).seconds)
            val b = item.content.gradeTime((part.end - item.start).seconds)
            if (!(b > a)) return@map item
            var value = grade
            val motion = grade?.motion
            if (motion != null && motion.clock.isEmpty()) {
                val previous = original?.video?.firstOrNull { it.id == item.id }?.content?.grade(a)?.motion
                value = value?.copy(motion = if (previous != null && motion.hasSameSettings(previous)) previous else motion.anchoredTo(item.content, item.start.seconds - range.start))
            }
            item.copy(content = item.content.replacingGrade(a..b, value, removingOnly))
        })
    }

    /**
     * [timeline] with its windows and variants tidied and each item's
     * memberships kept only for effects it still holds (YouRec/VideoTools.swift:805-840).
     */
    fun normalized(timeline: TrackTimeline): TrackTimeline {
        val video = timeline.video.map { item ->
            val content = if (item.content.effectWindows != null || item.content.framingVariants != null) item.content.normalizingEffectWindows() else item.content
            val members = item.spanningEffects?.let { set ->
                val ids = HashSet<String>()
                content.videoEffects.orEmpty().mapTo(ids) { it.id }
                content.texts.orEmpty().mapTo(ids) { it.id }
                content.images.orEmpty().mapTo(ids) { it.id }
                content.visibleGrades.mapTo(ids) { it.grade.id }
                listOfNotNull(content.crop?.id, content.mirror?.id, content.stabilization?.let { it.id ?: content.id }).forEach { ids += it }
                content.framingVariants.orEmpty().mapTo(ids) { it.id }
                set.intersect(ids).ifEmpty { null }
            }
            if (content == item.content && members == item.spanningEffects) item else item.copy(content = content, spanningEffects = members)
        }
        val audio = timeline.audio.map { item ->
            val set = item.spanningEffects ?: return@map item
            val ids = (item.enhancements.map { it.id } + item.censors.map { it.id }).toSet()
            val live = set.intersect(ids).ifEmpty { null }
            if (live == set) item else item.copy(spanningEffects = live)
        }
        return timeline.copy(video = video, audio = audio)
    }
}
