package app.takes.core

/**
 * The range a tool's range editor moves, as the original's inspectors have it
 * since 57e5d91: the clips it may reach and the stretch they span, and the
 * clips its edit then goes on. Line numbers are of 57e5d91.
 */
object ToolRange {
    /**
     * The clips of [state] a tool may reach with its range: [chosen] when
     * [state] holds them all, else, for pieces cut at the ends of a marked
     * range, the clips that play in [range] among [targets] when there are
     * some (YouRec/CropEditor.swift:121, `clipIDs ?? targets.video ?? items
     * intersecting the range`).
     */
    fun eligible(state: EditState, chosen: Set<String>, range: ClosedFloatingPointRange<Double>, targets: Set<String>? = null): Set<String> {
        val video = state.toTimeline().video
        if (chosen.isNotEmpty() && video.map { it.id }.containsAll(chosen)) return chosen
        val marked = EditInterval.of(range.start, range.endInclusive)
        return video.filter { (targets == null || it.id in targets) && it.interval.intersection(marked) != null }.mapTo(LinkedHashSet()) { it.id }
    }

    /** From the start of the first of [eligible] to the end of the last; null when [state] holds none (YouRec/CropEditor.swift:123-126). */
    fun bounds(state: EditState, eligible: Set<String>): ClosedFloatingPointRange<Double>? {
        val intervals = state.toTimeline().video.filter { it.id in eligible }.map { it.interval }
        if (intervals.isEmpty()) return null
        return intervals.minOf { it.start }.seconds..intervals.maxOf { it.end }.seconds
    }

    /** From the start of the first of the sounds [audio] to the end of the last; null when [state] holds none (YouRec/TrackEditors.swift:196-198). */
    fun audioBounds(state: EditState, audio: Set<String>): ClosedFloatingPointRange<Double>? {
        val intervals = state.toTimeline().audio.filter { it.id in audio }.map { it.interval }
        if (intervals.isEmpty()) return null
        return intervals.minOf { it.start }.seconds..intervals.maxOf { it.end }.seconds
    }

    /**
     * The clips a tool whose range was moved acts on: [state] cut at the
     * ends of [range], and the pieces of [eligible] inside it
     * (YouRec/CropEditor.swift:238-249, `timeline.scoped`).
     */
    fun scoped(state: EditState, eligible: Set<String>, range: ClosedFloatingPointRange<Double>): RangeEditing.Scoped =
        RangeEditing.scoped(state, EditScope.Range(TimeRange(range.start, range.endInclusive), EditTargets(clips = eligible)))
}

/**
 * The range of one video effect moved by the Video effects tool's range
 * editor (YouRec/VideoEffects/VideoEffectsEditor.swift:392-428 at 57e5d91):
 * on each of the tool's clips the effect is trimmed to the footage the range
 * covers, taken away where it covers none, and given back, in its place among
 * the clip's effects, where the range comes back over it.
 */
object VideoEffectRangeTrim {
    /** A clip of the tool and where it begins in the movie. */
    data class Piece(val clip: TimelineClip, val start: Double) {
        val end: Double get() = start + clip.duration
    }

    /** An effect as it was on a clip before the range left it, and its place in the clip's list. */
    data class Stored(val effect: VideoEffectSettings, val index: Int)

    /**
     * Where [effect] shows on [pieces], in seconds of the movie, from the
     * first piece it covers to the end of the last; null when it covers none
     * (YouRec/VideoTools.swift:1170-1191, `videoPreviewRange` for an effect).
     */
    fun covered(pieces: List<Piece>, effects: Map<String, List<VideoEffectSettings>>, id: String): ClosedFloatingPointRange<Double>? {
        val spans = pieces.mapNotNull { piece ->
            val clip = piece.clip
            val effect = effects[clip.id]?.firstOrNull { it.id == id } ?: return@mapNotNull null
            if (!(effect.start < clip.end && effect.end > clip.start)) return@mapNotNull null
            val first = piece.start + clip.offset(maxOf(clip.start, effect.start))
            val end = piece.start + clip.offset(minOf(clip.end, effect.end))
            if (first < end) first..end else null
        }
        if (spans.isEmpty()) return null
        return spans.minOf { it.start }..spans.maxOf { it.endInclusive }
    }

    /**
     * The range editor of effect [id], or null when it has none: a time
     * effect, which retimes the footage; a held frame, which has one source
     * second; or no clip of the tool with the effect (:392-402). [trimmed] is
     * the range it was last moved to, else [selection].
     */
    fun timing(
        pieces: List<Piece>, effects: Map<String, List<VideoEffectSettings>>, stored: Map<String, Stored>,
        id: String, kind: VideoEffectID, selection: ClosedFloatingPointRange<Double>, trimmed: ClosedFloatingPointRange<Double>?,
    ): EditorRangeTiming? {
        if (kind.descriptor.family == EffectFamily.Time) return null
        val items = pieces.filter { stored[it.clip.id] != null || effects[it.clip.id]?.any { effect -> effect.id == id } == true }
        if (items.any { it.clip.frozenFrame != null }) return null
        val first = items.minOfOrNull { it.start } ?: return null
        val last = items.maxOf { it.end }
        if (!(last > first)) return null
        return EditorRangeTiming(trimmed ?: selection, first..last)
    }

    /** The lists of effects and the effects set aside after effect [id] is given [range] (:403-428). */
    fun trimming(
        pieces: List<Piece>, effects: Map<String, List<VideoEffectSettings>>, stored: Map<String, Stored>,
        id: String, range: ClosedFloatingPointRange<Double>,
    ): Pair<Map<String, List<VideoEffectSettings>>, Map<String, Stored>> {
        val lists = effects.toMutableMap()
        val kept = stored.toMutableMap()
        for (piece in pieces) {
            val clip = piece.clip
            val list = lists[clip.id].orEmpty().toMutableList()
            val index = list.indexOfFirst { it.id == id }
            var value = if (index >= 0) list[index] else kept[clip.id]?.effect ?: continue
            if (index >= 0) kept[clip.id] = Stored(value, index)
            val first = maxOf(piece.start, range.start)
            val end = minOf(piece.end, range.endInclusive)
            if (first < end) {
                val sourceStart = clip.sourceTime(first - piece.start)
                val sourceEnd = clip.sourceTime(end - piece.start)
                if (!(sourceEnd > sourceStart)) continue
                value = value.trimmingSourceRange(sourceStart..sourceEnd)
                value.motion?.let { motion ->
                    value = value.copy(motion = motion.copy(duration = range.endInclusive - range.start).anchoredTo(clip, piece.start - range.start))
                }
                if (index >= 0) list[index] = value
                else list.add(minOf(kept[clip.id]?.index ?: list.size, list.size), value)
            } else if (index >= 0) list.removeAt(index)
            lists[clip.id] = list
        }
        return lists to kept
    }
}

/**
 * The effect over source seconds [range]: a Crash Zoom keeps its push where it
 * was in the footage, and a release outside the range is let go
 * (YouRec/VideoEffects/VideoEffectModel.swift:323-329 at 57e5d91).
 */
fun VideoEffectSettings.trimmingSourceRange(range: ClosedFloatingPointRange<Double>): VideoEffectSettings {
    var next = this
    val phase = phaseOffset
    if (kind == VideoEffectID.CrashZoom && phase != null) next = next.copy(phaseOffset = maxOf(0.0, phase + range.start - start))
    val release = releaseTime
    if (release != null && (release >= range.endInclusive || release < range.start - (next.phaseOffset ?: 0.0))) next = next.copy(releaseTime = null)
    return next.copy(start = range.start, end = range.endInclusive)
}
