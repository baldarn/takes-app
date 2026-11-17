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

    /** [base] with the clips [ids] played at [rate]; 1 plays them as recorded (Before). */
    fun paced(base: EditState, ids: Collection<String>, rate: Double): EditState =
        each(base, ids) { state, at -> TimelineEditing.setSpeed(state, at, rate) }

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
