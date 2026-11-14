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
        val pieces = listOf(clip.copy(end = cut), TimelineClip(sourceID = clip.sourceID, start = cut, end = clip.end))
        return state.copy(clips = state.clips.take(index) + pieces + state.clips.drop(index + 1))
    }

    /** Removes the clip and closes the gap. */
    fun delete(state: EditState, index: Int): EditState? {
        if (index !in state.clips.indices) return null
        return state.copy(clips = state.clips.filterIndexed { position, _ -> position != index })
    }

    fun move(state: EditState, from: Int, to: Int): EditState? {
        if (from !in state.clips.indices || to !in state.clips.indices || from == to) return null
        val clips = state.clips.toMutableList()
        clips.add(to, clips.removeAt(from))
        return state.copy(clips = clips)
    }
}
