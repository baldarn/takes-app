package app.takes

import app.takes.core.EditState
import app.takes.core.TimeRange
import app.takes.core.TimelineClip
import app.takes.core.TrackEditState
import app.takes.core.TrackTimeline
import app.takes.core.lanes

/**
 * Where the timeline draws the pictures of an edit that is no single row:
 * pictures that play at the same time on rows of their own, the one in
 * front on top, and the time without picture, as the original draws them
 * (YouRec/EditorTimeline.swift:52-73, `timelineLanes`). While a Move is under
 * way the pictures being moved have rows of their own at the top, and
 * [ghost] is where they were.
 *
 * @property starts the timeline second each clip of the edit begins, by its number.
 * @property rows the row of each clip, by its number; 0 is the top.
 * @property count how many rows of pictures there are, at least one.
 * @property duration the end of the edit, in seconds.
 * @property gaps the stretches without picture: the movie is black there.
 * @property moving the clips being moved.
 */
internal class TimelineRows(
    val starts: List<Double>,
    val rows: List<Int>,
    val count: Int,
    val duration: Double,
    val gaps: List<TimeRange>,
    val moving: Set<String> = emptySet(),
    val ghost: TimeRange? = null,
) {
    /** The clip number on [row] playing at timeline second [time], if any. */
    fun clipAt(clips: List<TimelineClip>, row: Int, time: Double): Int? = clips.indices.firstOrNull { index ->
        rows.getOrNull(index) == row && time >= starts[index] && time < starts[index] + clips[index].duration
    }

    companion object {
        /**
         * The rows of [state], drawn from [timeline], its own when it is
         * layered. Null for a sequence of clips that is not being moved: the
         * timeline draws it as the one row it is.
         */
        fun of(state: EditState, timeline: TrackTimeline? = state.layered, moving: Set<String>? = null, ghost: TimeRange? = null): TimelineRows? {
            val drawn = timeline ?: return null
            val lanes = drawn.lanes(moving).filter { !it.audio }.associateBy { it.id }
            val starts = state.clips.indices.map { index ->
                lanes[state.clips[index].id]?.contentInterval?.start?.seconds ?: TrackEditState.clipStart(state, index) ?: 0.0
            }
            val rows = state.clips.map { lanes[it.id]?.row ?: 0 }
            return TimelineRows(
                starts, rows, (rows.maxOrNull() ?: 0) + 1, state.duration,
                TrackEditState.pictureGaps(state).filter { it.end - it.start > 1e-6 },
                moving.orEmpty(), ghost,
            )
        }
    }
}
