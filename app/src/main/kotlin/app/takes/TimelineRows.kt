package app.takes

import app.takes.core.EditState
import app.takes.core.InsertionSites
import app.takes.core.VideoInsertionSite
import app.takes.core.TimeRange
import app.takes.core.TimelineClip
import app.takes.core.TrackEditState
import app.takes.core.TimelineEditing
import app.takes.core.TrackLayout
import app.takes.core.TrackTimeline
import app.takes.core.TransitionStyle
import app.takes.core.lanes

/**
 * A transition as the timeline marks it, as the original does
 * (YouRec/EditorTimeline.swift:110-140, :953-968): a badge on its join at
 * [time], on the top [row] of the pictures it plays on, and a bar under each
 * of those rows from [start] to [end], where its picture effect runs.
 * [number] is the join's number in the row of clips, null off one.
 */
internal class TransitionMarker(
    val time: Double, val start: Double, val end: Double, val row: Int, val rows: List<Int>, val style: TransitionStyle, val edge: Int, val number: Int?,
) {
    companion object {
        /** The transitions of a row of clips, each with its join's number ([TimelineEditing.transitions]). */
        fun of(clips: List<TimelineClip>): List<TransitionMarker> = TimelineEditing.transitions(clips).mapNotNull { join ->
            val style = join.transition?.style ?: return@mapNotNull null
            val range = join.origin..(join.origin + join.timing.videoDuration)
            TransitionMarker(join.time, range.start, range.endInclusive, 0, listOf(0), style, join.edge, join.index)
        }
    }
}

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
    /** Whether each clip's left and right edge is the edge of its footage, by its number: an edge folded under a transition has no grip (YouRec/TrackTimeline.swift:1689-1690). */
    val trimsLeft: List<Boolean> = rows.map { true },
    val trimsRight: List<Boolean> = rows.map { true },
    val markers: List<TransitionMarker> = emptyList(),
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
            val rowOf = lanes.mapValues { it.value.row }
            // No badge during a Move, as in the original.
            val markers = if (moving != null) emptyList() else TrackLayout.of(drawn).joins.mapNotNull { join ->
                val style = join.transition?.style?.takeIf { join.selectable } ?: return@mapNotNull null
                val on = listOfNotNull(join.outgoingID, join.incomingID).mapNotNull { rowOf[it] }.distinct().ifEmpty { return@mapNotNull null }
                TransitionMarker(join.time, join.videoRange.start, join.videoRange.endInclusive, on.min(), on, style, join.edge, null)
            }
            return TimelineRows(
                starts, rows, (rows.maxOrNull() ?: 0) + 1, state.duration,
                TrackEditState.pictureGaps(state).filter { it.end - it.start > 1e-6 },
                moving.orEmpty(), ghost,
                state.clips.map { lanes[it.id]?.trimsLeft ?: true }, state.clips.map { lanes[it.id]?.trimsRight ?: true }, markers,
            )
        }
    }
}

/**
 * An add button of the timeline (YouRec/EditorTimeline.swift:318-366): the
 * start, left of where the timeline begins, or inside the time without
 * picture before the first one, from [from] to [to], when that is off the
 * screen; the end, right of where the last picture ends; or a gap from
 * [from] to [to], the button inside it.
 */
internal class AddSite(val site: VideoInsertionSite, val from: Double, val to: Double) {
    companion object {
        /** The add buttons of [state], one for each of its [InsertionSites.sites]. */
        fun of(state: EditState): List<AddSite> {
            val coverage = InsertionSites.coverage(state)
            return InsertionSites.sites(state).map { site ->
                when (site) {
                    VideoInsertionSite.Start -> AddSite(site, 0.0, coverage.firstOrNull()?.start ?: 0.0)
                    VideoInsertionSite.End -> (coverage.lastOrNull()?.end ?: 0.0).let { AddSite(site, it, it) }
                    is VideoInsertionSite.Gap -> AddSite(site, site.after, coverage.firstOrNull { it.start > site.after }?.start ?: site.after)
                }
            }
        }
    }
}
