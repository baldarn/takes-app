package app.takes.core

/** Pure timeline operations. Each returns a new state, or null when it does not apply. */
object TimelineEditing {
    /** Shortest piece an edit may leave behind, in seconds. */
    const val MINIMUM_CLIP = 0.1

    data class Location(val index: Int, val offset: Double)

    /** Timeline second where the clip at [index] begins. A dissolve pulls the next clip back under the previous one. */
    fun clipStart(clips: List<TimelineClip>, index: Int): Double {
        var start = 0.0
        for (position in 0 until minOf(index, clips.size)) start += clips[position].duration - overlap(clips, position)
        return start
    }

    fun duration(clips: List<TimelineClip>): Double = clipStart(clips, clips.size)

    /**
     * The clip showing at [time]. A cut belongs to the clip that starts there,
     * and so does a dissolve: the incoming clip owns the overlap. The end
     * belongs to the last clip.
     */
    fun locate(clips: List<TimelineClip>, time: Double): Location? {
        if (clips.isEmpty() || !time.isFinite()) return null
        var start = 0.0
        clips.forEachIndexed { index, clip ->
            val next = start + clip.duration - overlap(clips, index)
            if (time < next) return Location(index, (time - start).coerceAtLeast(0.0))
            start = next
        }
        return Location(clips.lastIndex, clips.last().duration)
    }

    /** A run of clips joined by cuts or fades. Dissolves separate chains, which overlap in time. */
    data class Chain(val first: Int, val last: Int, val start: Double, val end: Double)

    fun chains(clips: List<TimelineClip>): List<Chain> {
        val chains = mutableListOf<Chain>()
        var first = 0
        var start = 0.0
        var time = 0.0
        clips.forEachIndexed { index, clip ->
            val end = time + clip.duration
            val overlap = overlap(clips, index)
            if (overlap > 0 || index == clips.lastIndex) {
                chains += Chain(first, index, start, end)
                first = index + 1
                start = end - overlap
            }
            time = end - overlap
        }
        return chains
    }

    fun split(state: EditState, time: Double): EditState? {
        val (index, offset) = locate(state.clips, time) ?: return null
        val clip = state.clips[index]
        if (offset < MINIMUM_CLIP || clip.duration - offset < MINIMUM_CLIP) return null
        // Not inside a dissolve: two clips are showing there.
        if (offset < overlap(state.clips, index - 1) || offset > clip.duration - overlap(state.clips, index)) return null
        val cut = clip.start + offset
        // The join after the clip now follows the second piece; each piece keeps the titles it shows.
        fun titles(from: Double, to: Double) = clip.texts?.filter { it.start < to && it.end > from }?.ifEmpty { null }
        val (before, after) = clip.bookmarks.orEmpty().partition { it.time < cut }
        val pieces = listOf(
            clip.copy(end = cut, transition = null, texts = titles(clip.start, cut), bookmarks = before.ifEmpty { null }),
            TimelineClip(sourceID = clip.sourceID, start = cut, end = clip.end, transition = clip.transition,
                texts = titles(cut, clip.end), bookmarks = after.ifEmpty { null }),
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
    fun setTransition(state: EditState, index: Int, duration: Double?, style: ClipTransition.Style = ClipTransition.Style.FadeBlack): EditState? {
        val clip = state.clips.getOrNull(index) ?: return null
        val next = state.clips.getOrNull(index + 1) ?: return null
        val transition = duration?.let {
            if (!it.isFinite() || it <= 0) return null
            val current = transition(state.clips, index)
            ClipTransition(id = current?.id ?: newID(), nextClipID = next.id, style = style, duration = minOf(it, ClipTransition.MAXIMUM_DURATION))
        }
        if (transition == clip.transition) return null
        return state.copy(clips = state.clips.toMutableList().also { it[index] = clip.copy(transition = transition) })
    }

    /**
     * Seconds of fade on each side of the join after [index]. A fade never
     * takes more than half of either clip, so two fades cannot overlap.
     */
    fun fadeLength(clips: List<TimelineClip>, index: Int): Double {
        val transition = transition(clips, index)?.takeIf { it.style == ClipTransition.Style.FadeBlack } ?: return 0.0
        return minOf(transition.duration / 2, clips[index].duration / 2, clips[index + 1].duration / 2)
    }

    /**
     * Seconds the clip after [index] overlaps it in a dissolve. Never more
     * than half of either clip, so a clip's two dissolves cannot meet.
     */
    fun overlap(clips: List<TimelineClip>, index: Int): Double {
        val transition = transition(clips, index)?.takeIf { it.style == ClipTransition.Style.Dissolve } ?: return 0.0
        return minOf(transition.duration, clips[index].duration / 2, clips[index + 1].duration / 2)
    }

    /** Share of the picture and sound, 0–1, that the clip at [index] contributes at [offset] seconds into it. */
    fun weight(clips: List<TimelineClip>, index: Int, offset: Double): Double {
        val clip = clips.getOrNull(index) ?: return 0.0
        return level(offset, clip.duration, overlap(clips, index - 1), overlap(clips, index))
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

    /**
     * Every title showing at timeline second [time], with its opacity. During
     * a dissolve the titles of both clips show, each with its clip's share.
     */
    fun titlesAt(clips: List<TimelineClip>, time: Double): List<Pair<TextOverlay, Double>> {
        val (index, offset) = locate(clips, time) ?: return emptyList()
        val clip = clips[index]
        val share = weight(clips, index, offset)
        val entering = textsAt(clip, clip.start + offset).map { it to share }
        if (share >= 1 || index == 0) return entering
        val leaving = clips[index - 1]
        return textsAt(leaving, leaving.end - overlap(clips, index - 1) + offset).map { it to 1 - share } + entering
    }

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

    // MARK: Bookmarks

    /** Marks closer than this to the playhead count as being under it. */
    const val BOOKMARK_REACH = 0.15

    /** Timeline seconds of every bookmark that is inside the visible part of its clip, in order. */
    fun bookmarkTimes(clips: List<TimelineClip>): List<Double> = clips.flatMapIndexed { index, clip ->
        val begins = clipStart(clips, index)
        clip.bookmarks.orEmpty().filter { it.time >= clip.start && it.time < clip.end }.map { begins + it.time - clip.start }
    }.sorted()

    fun nextBookmark(clips: List<TimelineClip>, time: Double): Double? = bookmarkTimes(clips).firstOrNull { it > time + 0.05 }

    fun previousBookmark(clips: List<TimelineClip>, time: Double): Double? = bookmarkTimes(clips).lastOrNull { it < time - 0.05 }

    /** The bookmark of the clip at [index] within reach of [sourceTime], if any. */
    fun bookmarkAt(clips: List<TimelineClip>, index: Int, sourceTime: Double): Bookmark? =
        clips.getOrNull(index)?.bookmarks.orEmpty().filter { kotlin.math.abs(it.time - sourceTime) <= BOOKMARK_REACH }
            .minByOrNull { kotlin.math.abs(it.time - sourceTime) }

    /** Removes the bookmark under [sourceTime] on the clip at [index], or adds one there. */
    fun toggleBookmark(state: EditState, index: Int, sourceTime: Double): EditState? {
        val clip = state.clips.getOrNull(index) ?: return null
        if (!sourceTime.isFinite() || sourceTime < clip.start || sourceTime > clip.end) return null
        val existing = bookmarkAt(state.clips, index, sourceTime)
        val marks = clip.bookmarks.orEmpty()
        val next = if (existing != null) marks.filter { it.id != existing.id } else (marks + Bookmark(time = sourceTime)).sortedBy { it.time }
        return state.copy(clips = state.clips.toMutableList().also { it[index] = clip.copy(bookmarks = next.ifEmpty { null }) })
    }
}
