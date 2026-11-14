package app.takes.core

/** Pure timeline operations. Each returns a new state, or null when it does not apply. */
object TimelineEditing {
    /** Shortest piece an edit may leave behind, in seconds. */
    const val MINIMUM_CLIP = 0.1

    data class Location(val index: Int, val offset: Double)

    fun clipStart(clips: List<TimelineClip>, index: Int): Double = clips.take(index).sumOf { it.duration }

    /** The clip showing at [time]. A cut belongs to the clip that starts there; the end belongs to the last clip. */
    fun locate(clips: List<TimelineClip>, time: Double): Location? {
        if (clips.isEmpty() || !time.isFinite()) return null
        var start = 0.0
        clips.forEachIndexed { index, clip ->
            if (time < start + clip.duration) return Location(index, (time - start).coerceAtLeast(0.0))
            start += clip.duration
        }
        return Location(clips.lastIndex, clips.last().duration)
    }

    fun split(state: EditState, time: Double): EditState? {
        val (index, offset) = locate(state.clips, time) ?: return null
        val clip = state.clips[index]
        if (offset < MINIMUM_CLIP || clip.duration - offset < MINIMUM_CLIP) return null
        val cut = clip.start + offset
        // The join after the clip now follows the second piece; each piece keeps the titles it shows.
        fun titles(from: Double, to: Double) = clip.texts?.filter { it.start < to && it.end > from }?.ifEmpty { null }
        val pieces = listOf(
            clip.copy(end = cut, transition = null, texts = titles(clip.start, cut)),
            TimelineClip(sourceID = clip.sourceID, start = cut, end = clip.end, transition = clip.transition, texts = titles(cut, clip.end)),
        )
        return state.copy(clips = state.clips.take(index) + pieces + state.clips.drop(index + 1))
    }

    /** Removes the clip and closes the gap. */
    fun delete(state: EditState, index: Int): EditState? {
        if (index !in state.clips.indices) return null
        return state.copy(clips = withoutBrokenJoins(state.clips.filterIndexed { position, _ -> position != index }))
    }

    fun move(state: EditState, from: Int, to: Int): EditState? {
        if (from !in state.clips.indices || to !in state.clips.indices || from == to) return null
        val clips = state.clips.toMutableList()
        clips.add(to, clips.removeAt(from))
        return state.copy(clips = withoutBrokenJoins(clips))
    }

    /**
     * The clip with new source bounds, kept inside its source and at least
     * [MINIMUM_CLIP] long. Moving one edge never moves the other.
     */
    fun trimmed(clip: TimelineClip, start: Double, end: Double, sourceDuration: Double): TimelineClip {
        if (!start.isFinite() || !end.isFinite()) return clip
        val limit = maxOf(sourceDuration, clip.end)
        val newStart = if (start == clip.start) start else start.coerceIn(0.0, maxOf(0.0, clip.end - MINIMUM_CLIP))
        val newEnd = if (end == clip.end) end else end.coerceIn(minOf(limit, newStart + MINIMUM_CLIP), limit)
        return clip.copy(start = newStart, end = newEnd)
    }

    /** Changes the clip's source range; later clips ripple. */
    fun trim(state: EditState, index: Int, start: Double, end: Double, sourceDuration: Double): EditState? {
        val clip = state.clips.getOrNull(index) ?: return null
        val next = trimmed(clip, start, end, sourceDuration)
        if (next == clip) return null
        return state.copy(clips = state.clips.toMutableList().also { it[index] = next })
    }

    // MARK: Transitions

    private fun withoutBrokenJoins(clips: List<TimelineClip>): List<TimelineClip> = clips.mapIndexed { index, clip ->
        if (clip.transition != null && clip.transition.nextClipID != clips.getOrNull(index + 1)?.id) clip.copy(transition = null) else clip
    }

    /** The transition into the clip after [index], if those two clips are still neighbors. */
    fun transition(clips: List<TimelineClip>, index: Int): ClipTransition? =
        clips.getOrNull(index)?.transition?.takeIf { it.nextClipID == clips.getOrNull(index + 1)?.id }

    /** Sets the join after [index]; a null [duration] removes it. */
    fun setTransition(state: EditState, index: Int, duration: Double?): EditState? {
        val clip = state.clips.getOrNull(index) ?: return null
        val next = state.clips.getOrNull(index + 1) ?: return null
        val transition = duration?.let {
            if (!it.isFinite() || it <= 0) return null
            val current = transition(state.clips, index)
            ClipTransition(id = current?.id ?: newID(), nextClipID = next.id, duration = minOf(it, ClipTransition.MAXIMUM_DURATION))
        }
        if (transition == clip.transition) return null
        return state.copy(clips = state.clips.toMutableList().also { it[index] = clip.copy(transition = transition) })
    }

    /**
     * Seconds of fade on each side of the join after [index]. A fade never
     * takes more than half of either clip, so two fades cannot overlap.
     */
    fun fadeLength(clips: List<TimelineClip>, index: Int): Double {
        val transition = transition(clips, index) ?: return 0.0
        return minOf(transition.duration / 2, clips[index].duration / 2, clips[index + 1].duration / 2)
    }

    /** Picture and sound level, 0–1, at [offset] seconds into the clip at [index]. */
    fun level(clips: List<TimelineClip>, index: Int, offset: Double): Double {
        val clip = clips.getOrNull(index) ?: return 1.0
        return level(offset, clip.duration, fadeLength(clips, index - 1), fadeLength(clips, index))
    }

    fun level(offset: Double, duration: Double, fadeIn: Double, fadeOut: Double): Double {
        var level = 1.0
        if (fadeIn > 0 && offset < fadeIn) level = offset / fadeIn
        if (fadeOut > 0 && offset > duration - fadeOut) level = minOf(level, (duration - offset) / fadeOut)
        return level.coerceIn(0.0, 1.0)
    }

    // MARK: Titles

    fun textsAt(clip: TimelineClip, sourceTime: Double): List<TextOverlay> =
        clip.texts.orEmpty().filter { sourceTime >= it.start && sourceTime < it.end }

    /** Adds the title to the clip, or replaces the one with the same id. */
    fun setText(state: EditState, index: Int, text: TextOverlay): EditState? {
        val clip = state.clips.getOrNull(index) ?: return null
        if (!text.valid) return null
        val texts = clip.texts.orEmpty()
        val next = if (texts.any { it.id == text.id }) texts.map { if (it.id == text.id) text else it } else texts + text
        if (next == texts) return null
        return state.copy(clips = state.clips.toMutableList().also { it[index] = clip.copy(texts = next) })
    }

    fun removeText(state: EditState, index: Int, id: String): EditState? {
        val clip = state.clips.getOrNull(index) ?: return null
        val texts = clip.texts.orEmpty()
        if (texts.none { it.id == id }) return null
        return state.copy(clips = state.clips.toMutableList().also { it[index] = clip.copy(texts = texts.filter { it.id != id }.ifEmpty { null }) })
    }
}
