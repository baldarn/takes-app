package app.takes.core

/**
 * Before and After in a tool that changes clips, as the original's tool
 * preview (YouRec/EditorTools.swift:700-722, 1262-1275): After is the edit
 * with the tool's setting on the clips it works on, Before the same edit with
 * that setting taken off them, as `state(removing: true)` makes it, not the
 * edit as it was saved.
 */
object ToolCompare {
    /** [base] with the clips [ids] given [color]; null takes their color away (Before). */
    fun colored(base: EditState, ids: Collection<String>, color: ColorSettings?): EditState =
        each(base, ids) { state, at -> TimelineEditing.setColor(state, at, color ?: ColorSettings()) }

    /**
     * The stretch of [state] the clips [ids] cover, from the start of the first to the end of the last, which a
     * grade's motion runs over (YouRec/VideoTools.swift:642-650, `videoPreviewRange`).
     */
    fun stretch(state: EditState, ids: Collection<String>): ClosedFloatingPointRange<Double> {
        val placed = state.clips.indices.filter { state.clips[it].id in ids }.mapNotNull { index ->
            TrackEditState.clipStart(state, index)?.let { it to it + state.clips[index].duration }
        }
        val lower = (placed.minOfOrNull { it.first } ?: 0.0).coerceIn(0.0, maxOf(0.0, state.duration))
        return lower..maxOf(lower, minOf(state.duration, placed.maxOfOrNull { it.second } ?: lower))
    }

    /**
     * [base] with the clips [ids] given the Color tool's grade, a moving one tied to each clip's footage from where
     * the clip begins in the tool's stretch (YouRec/EditorTools.swift:704-712); null takes their color away (Before).
     */
    fun graded(base: EditState, ids: Collection<String>, draft: GradeDraft?): EditState {
        if (draft == null) return colored(base, ids, null)
        val stretch = stretch(base, ids)
        val duration = stretch.endInclusive - stretch.start
        return each(base, ids) { state, at ->
            val clip = state.clips[at]
            val offset = (TrackEditState.clipStart(state, at) ?: stretch.start) - stretch.start
            TimelineEditing.setColor(state, at, draft.placed(clip.color, clip.playbackSpans, offset, duration))
        }
    }

    /**
     * [base] with the clips [ids] played at [rate]; 1 plays them as recorded (Before). With [id], one speed under
     * that id on all of them, as the original's tool saves it (YouRec/EditorTools.swift:703).
     */
    fun paced(base: EditState, ids: Collection<String>, rate: Double, id: String? = null): EditState =
        each(base, ids) { state, at -> TimelineEditing.setSpeed(state, at, rate, id) }

    /**
     * The second of [to] that shows the frame [time] shows in [from], so that
     * switching between Before and After, or changing a speed, keeps the same
     * footage under the playhead: the clip found there by its id, at the same
     * source second. Where that clip is not in [to], the same second, within it.
     */
    fun mapped(from: EditState, to: EditState, time: Double): Double {
        val end = maxOf(0.0, to.duration)
        val location = TrackEditState.locate(from, time) ?: return time.coerceIn(0.0, end)
        val clip = from.clips[location.index]
        val index = to.clips.indexOfFirst { it.id == clip.id }
        val start = TrackEditState.clipStart(to, index) ?: return time.coerceIn(0.0, end)
        val target = to.clips[index]
        return (start + target.offset(clip.sourceTime(location.offset)).coerceIn(0.0, target.duration)).coerceIn(0.0, end)
    }

    private fun each(base: EditState, ids: Collection<String>, change: (EditState, Int) -> EditState?): EditState {
        var state = base
        for (id in ids) {
            val at = state.clips.indexOfFirst { it.id == id }
            if (at >= 0) change(state, at)?.let { state = it }
        }
        return state
    }
}
