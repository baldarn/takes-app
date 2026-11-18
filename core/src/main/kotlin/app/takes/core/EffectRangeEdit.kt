package app.takes.core

/**
 * The range of an effect's application dragged by its two handles on the
 * timeline, as the original's "Adjust effect range" does
 * (YouRec/VideoTools.swift:938-1157). The handles are on the timeline's
 * clock; the recipes are projected into source time only on the members of
 * the application and the pieces after them in the same row, which the
 * handles may sweep. Nothing is cut and no footage moves.
 */
class EffectRangeEdit private constructor(
    val region: ToolRegion,
    /** The application's range when the handles were picked up. */
    val original: ClosedFloatingPointRange<Double>,
    val timing: EditorRangeTiming,
    /** Where the playhead was, to go back to on Cancel. */
    val originalPlayhead: Double,
    /** The edge moved last, whose frame the preview shows. */
    val lastEdge: EditorRangeTiming.Edge?,
    private val members: Map<String, Set<EditInterval>>,
    private val scoped: ScopedEffectOperation?,
    /** The items of the rows the application is on: the pieces the handles can reach. */
    val eligible: Set<String>,
) {
    val hasPicture: Boolean get() = !region.tool.isAudioRange

    /** The edit with [edge] moved to [time], within its limits. */
    fun setting(edge: EditorRangeTiming.Edge, time: Double): EffectRangeEdit =
        EffectRangeEdit(region, original, timing.setting(edge, time), originalPlayhead, edge, members, scoped, eligible)

    private fun participates(id: String, interval: EditInterval, range: EditInterval): Boolean {
        if (members[id] != null) return true
        if (id !in eligible) return false
        val added = interval.intersection(range) ?: return false
        val old = EditInterval.of(original.start, original.endInclusive)
        return added.start < old.start || added.end > old.end
    }

    /** [state] with the application over the handles' range; [state] itself when they have not moved (YouRec/VideoTools.swift:1004-1012). */
    fun applying(state: EditState): EditState {
        val range = EditInterval.of(timing.range.start, timing.range.endInclusive)
        if (range == EditInterval.of(original.start, original.endInclusive)) return state
        scoped?.let { operation ->
            val candidate = applyingRecipe(operation.inspectorState(state), range)
            return operation.reshaped(state, candidate, range, eligible)
        }
        return applyingRecipe(state, range)
    }

    private fun applyingRecipe(state: EditState, range: EditInterval): EditState = EffectApplications.editing(state) { timeline ->
        if (region.tool.isAudioRange) applyingAudio(timeline, range)
        else applyingVideo(timeline, range)
    }

    private fun applyingVideo(timeline: TrackTimeline, range: EditInterval): TrackTimeline {
        val owner = timeline.video.firstOrNull { it.id == region.clipID } ?: return timeline
        val id = region.effectID
        val video = timeline.video.toMutableList()
        for (i in video.indices) {
            val item = timeline.video[i]
            if (!participates(item.id, item.interval, range)) continue
            val part = item.interval.intersection(range)
            val member = members[item.id] != null
            val recipe = if (member) item else owner
            var clip = item.content
            val source = part?.let { SourceInterval(clip.gradeTime((it.start - item.start).seconds), clip.gradeTime((it.end - item.start).seconds)) }
            val window = if (part == item.interval) null else source
            fun retimed(animation: EffectAnimation?): EffectAnimation? =
                animation?.copy(duration = range.duration.seconds)?.anchoredTo(clip, (item.start - range.start).seconds)
            when (region.tool) {
                EditorTool.Text -> {
                    var value = recipe.content.texts?.firstOrNull { it.id == id } ?: continue
                    value = value.copy(playbackClock = null)
                    if (source != null) value = value.copy(start = source.start, end = source.end)
                    clip = clip.copy(texts = replace(clip.texts, id, if (part == null) null else value) { it.id })
                }
                EditorTool.Image -> {
                    var value = recipe.content.images?.firstOrNull { it.id == id } ?: continue
                    value = value.copy(motion = retimed(value.motion))
                    if (source != null) value = value.copy(start = source.start, end = source.end)
                    clip = clip.copy(images = replace(clip.images, id, if (part == null) null else value) { it.id })
                }
                EditorTool.Crop -> {
                    val value = recipe.content.crop?.let { it.copy(motion = retimed(it.motion), activeRange = window) }
                    clip = clip.copy(crop = if (part == null) null else value)
                }
                EditorTool.Mirror -> clip = clip.copy(mirror = if (part == null) null else recipe.content.mirror?.copy(activeRange = window))
                EditorTool.Stabilize -> {
                    var value = recipe.content.stabilization
                    if (!member) value = value?.copy(referenceStart = clip.start, referenceEnd = clip.end)
                    value = value?.copy(id = id, activeRange = window)
                    clip = clip.copy(stabilization = if (part == null) null else value)
                }
                EditorTool.Color -> {
                    var value = recipe.content.visibleGrades.firstOrNull { it.grade.id == id }?.grade ?: continue
                    if (member) clip.visibleGrades.firstOrNull { it.grade.id == id }?.let { old -> clip = clip.replacingGrade(old.start..old.end, null, removingOnly = id) }
                    if (source != null) {
                        value = value.copy(motion = retimed(value.motion))
                        clip = clip.replacingGrade(source.start..source.end, value)
                    }
                }
                EditorTool.VideoEffects -> {
                    var value = recipe.content.videoEffects?.firstOrNull { it.id == id } ?: continue
                    if (part != null && source != null) {
                        if (!member) {
                            // A captured mask, palette sequence or face track belongs to its source.
                            value = value.copy(analysisFile = null, analysisDataFile = null, capturedEffectID = null)
                            val boundary = if (part.start < EditTime.of(original.start)) value.start else value.end
                            value = value.copy(
                                parameters = value.parameters.map { if (it.keyframes.isEmpty()) it else it.copy(value = it.value(boundary), keyframes = emptyList()) },
                                phaseOffset = null, releaseTime = null,
                            )
                        }
                        value = value.trimmingSource(source.start..source.end)
                        if (value.kind == VideoEffectID.NegativeStrobe) {
                            val runs = item.mapping.sliced(EditInterval(part.start - item.start, part.end - item.start)).runs
                            val points = ArrayList<StrobeTimePoint>()
                            var elapsed = 0.0
                            for (run in runs) {
                                if (run.isHold) continue
                                if (points.lastOrNull()?.source != run.sourceStart) points += StrobeTimePoint(run.sourceStart, elapsed)
                                elapsed += run.duration.seconds
                                points += StrobeTimePoint(run.sourceEnd, elapsed)
                            }
                            value = value.copy(phaseOffset = (part.start - range.start).seconds, strobeTimeMap = points.takeIf { it.size >= 2 })
                        }
                        value = value.copy(motion = retimed(value.motion))
                    }
                    clip = clip.copy(videoEffects = replace(clip.videoEffects, id, if (part == null) null else value) { it.id })
                }
                else -> continue
            }
            var next = item.copy(content = clip)
            if (part != null && (members.size > 1 || !member || range.start < owner.start || range.end > owner.end)) {
                next = next.copy(spanningEffects = item.spanningEffects.orEmpty() + id)
            }
            video[i] = next
        }
        return timeline.copy(video = video)
    }

    private fun <T> replace(list: List<T>?, id: String, value: T?, idOf: (T) -> String): List<T>? {
        val values = list.orEmpty().toMutableList()
        val index = values.indexOfFirst { idOf(it) == id }
        if (index >= 0) {
            if (value != null) values[index] = value else values.removeAt(index)
        } else if (value != null) values += value
        return values.ifEmpty { null }
    }

    private fun applyingAudio(timeline: TrackTimeline, range: EditInterval): TrackTimeline {
        val id = region.effectID
        val owner = timeline.audio.firstOrNull { it.id == region.clipID } ?: return timeline
        val first = EditTime.of(region.start) - owner.start
        val chain = owner.enhancements.firstOrNull { it.id == id && it.interval.start == first }?.effects
        val beep = owner.censors.firstOrNull { it.id == id && it.interval.start == first }?.settings
        val audio = timeline.audio.toMutableList()
        for (i in audio.indices) {
            val item = timeline.audio[i]
            if (!participates(item.id, item.interval, range)) continue
            fun belongs(recipeID: String, local: EditInterval): Boolean =
                recipeID == id && members[item.id]?.contains(EditInterval(item.start + local.start, item.start + local.end)) == true
            val local = item.interval.intersection(range)?.let { EditInterval(it.start - item.start, it.end - item.start) }
            fun available(occupied: List<EditInterval>): List<EditInterval> {
                local ?: return emptyList()
                val free = ArrayList<EditInterval>()
                var cursor = local.start
                for (other in occupied.sortedBy { it.start }) {
                    val overlap = local.intersection(other) ?: continue
                    if (cursor < overlap.start) free += EditInterval(cursor, overlap.start)
                    cursor = maxOf(cursor, overlap.end)
                }
                if (cursor < local.end) free += EditInterval(cursor, local.end)
                return free
            }
            var next = item
            if (chain != null && region.tool == EditorTool.EnhanceAudio) {
                val kept = item.enhancements.filter { !belongs(it.id, it.interval) }
                next = next.copy(enhancements = (kept + available(kept.map { it.interval }).map { AudioEnhancementRegion(id, it, chain) }).sortedBy { it.interval.start })
            } else if (beep != null && region.tool == EditorTool.CensorAudio) {
                val kept = item.censors.filter { !belongs(it.id, it.interval) }
                val envelope = EditInterval(range.start - item.start, range.end - item.start)
                val added = available(kept.map { it.interval }).map { AudioCensorRegion(id, it, envelope.start, beep, envelope) }
                next = next.withCensors((kept + added).sortedBy { it.interval.start })
            }
            if (local != null && (members.size > 1 || members[item.id] == null || range.start < owner.start || range.end > owner.end)) {
                next = next.copy(spanningEffects = item.spanningEffects.orEmpty() + id)
            }
            audio[i] = next
        }
        return timeline.copy(audio = audio)
    }

    companion object {
        /**
         * The handles for [region]'s application in [state], with the
         * playhead at [playhead]; null when its range cannot be edited: a
         * tool without one, an application shorter than a frame, a time effect,
         * or a title, picture or effect on a held frame (YouRec/VideoTools.swift:951-995).
         * The handles stop at the next piece of the same slot: the same effect
         * elsewhere, another crop, flip or stabilisation of an item, another
         * grade or sound effect in the way.
         */
        fun of(region: ToolRegion, state: EditState, playhead: Double, regions: List<ToolRegion> = ToolRegions.of(state)): EffectRangeEdit? {
            if (!region.tool.hasEditableRange) return null
            val timeline = state.toTimeline()
            val all = regions.filter { it.tool == region.tool }
            val event = EffectApplications.applicationRegions(timeline, region, all.filter { it.effectID == region.effectID })
            val members = event.groupBy { it.clipID }.mapValues { (_, list) -> list.mapTo(HashSet()) { it.interval } }
            val a = event.minOfOrNull { EditTime.of(it.start) } ?: return null
            val b = event.maxOfOrNull { EditTime.of(it.end) } ?: return null
            if (b - a < EditTime.of(PauseDetector.FRAME)) return null
            if (region.tool == EditorTool.VideoEffects) {
                val effect = timeline.video.firstOrNull { it.id == region.clipID }?.content?.videoEffects?.firstOrNull { it.id == region.effectID } ?: return null
                if (effect.kind in VideoEffectTiming.TIME_EFFECTS) return null
            }
            val lanes = timeline.lanes().filter { it.audio == region.tool.isAudioRange }
            val rows = lanes.filter { members[it.id] != null }.mapTo(HashSet()) { it.row }
            val chain = lanes.filter { it.row in rows }
            var left = chain.minOfOrNull { it.contentInterval.start } ?: return null
            var right = chain.maxOfOrNull { it.contentInterval.end } ?: return null
            val eligible = chain.mapTo(HashSet()) { it.id }
            fun protect(interval: EditInterval) {
                if (interval.end <= a) left = maxOf(left, interval.end)
                if (interval.start >= b) right = minOf(right, interval.start)
            }
            val exclusiveSlot = region.tool == EditorTool.Crop || region.tool == EditorTool.Mirror || region.tool == EditorTool.Stabilize
            val laneIntervals = chain.associate { it.id to it.contentInterval }
            for (other in all) {
                val item = laneIntervals[other.clipID] ?: continue
                val interval = other.interval
                if (other.effectID == region.effectID && members[other.clipID]?.contains(interval) == true) continue
                // Crop, flip and stabilisation each have one slot a clip; grades and sound effects have stretches. Titles and looks may overlap.
                if (exclusiveSlot || (other.effectID == region.effectID && !region.tool.isAudioRange)) protect(item)
                else if (region.tool.isAudioRange || region.tool == EditorTool.Color) protect(interval)
            }
            if (region.tool == EditorTool.Text || region.tool == EditorTool.Image || region.tool == EditorTool.VideoEffects) {
                for (item in timeline.video) {
                    if (item.id !in eligible || item.content.frozenFrame == null) continue
                    if (members[item.id] != null) return null
                    protect(item.interval)
                }
            }
            val operation = ScopedEffectOperation.of(region, state, null, regions)
            val scoped = if (operation?.needsProjection(timeline) == true) operation else null
            val original = EditInterval(a, b).seconds
            return EffectRangeEdit(
                region, original, EditorRangeTiming(original, EditInterval(left, right).seconds), playhead, null, members, scoped, eligible,
            )
        }
    }
}

/**
 * The effect over the source stretch [range]: a Crash Zoom's captured phase
 * moves with its start and a manual return outside the range is dropped
 * (YouRec/VideoEffects/VideoEffectModel.swift:323-329).
 */
fun VideoEffectSettings.trimmingSource(range: ClosedFloatingPointRange<Double>): VideoEffectSettings {
    var value = this
    if (kind == VideoEffectID.CrashZoom) phaseOffset?.let { value = value.copy(phaseOffset = maxOf(0.0, it + range.start - start)) }
    val release = value.releaseTime
    if (release != null && (release >= range.endInclusive || release < range.start - (value.phaseOffset ?: 0.0))) value = value.copy(releaseTime = null)
    return value.copy(start = range.start, end = range.endInclusive)
}
