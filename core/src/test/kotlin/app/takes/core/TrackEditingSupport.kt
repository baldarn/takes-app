package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * What the tests of the editing operations need from the operations that are
 * ported elsewhere (unlinking and Move), and the checks they share. The two
 * operations here are the original's, written out for the tests only.
 */
internal object TrackEditingSupport {
    fun ticks(seconds: Double) = EditTime.of(seconds)

    /** YouRec/TrackTimeline.swift:575-579: the links of the selection go, and their sounds follow nothing. */
    fun unlink(timeline: TrackTimeline, selection: TimelineSelection): TrackTimeline {
        val ids = selection.ids
        val members = timeline.links.filter { link -> link.members.any { it in ids } }.flatMap { it.members }.toSet()
        return timeline.copy(
            audio = timeline.audio.map { if (it.id in members) it.copy(followsVideo = null) else it },
            links = timeline.links.filter { link -> link.members.none { it in ids } },
        )
    }

    /** YouRec/TrackTimeline.swift:703-725: one offset for the whole group, held at zero. */
    fun move(timeline: TrackTimeline, selection: TimelineSelection, delta: EditTime): TrackTimeline {
        val chosen = TrackEditing.expanded(timeline, selection)
        val range = TrackEditing.interval(timeline, TimelineSelection(chosen.video, chosen.audio)) ?: return timeline
        val amount = maxOf(EditTime.ZERO - range.start, delta)
        if (amount == EditTime.ZERO) return timeline
        fun inside(relation: TimelineTransition) = listOfNotNull(relation.outgoing, relation.incoming).all { it in chosen.video }
        var moved = timeline.copy(video = timeline.video.map { if (it.id in chosen.video) it.copy(start = it.start + amount) else it })
        moved = TrackEditing.promotingNewOverlaps(moved, chosen.video, timeline.video)
        moved = moved.copy(
            audio = moved.audio.map { if (it.id in chosen.audio) it.copy(start = it.start + amount) else it },
            transitions = moved.transitions.map { if (inside(it)) it.copy(origin = it.origin + amount) else it },
        )
        return TrackEditing.retainingPlacementAcrossNewOverlaps(moved, chosen.video).pruned()
    }

    /** A timeline a project may hold, with its relations in line with its items. */
    fun assertAtRest(message: String, timeline: TrackTimeline, sources: List<SourceMedia>) {
        assertNull(message, TrackInvariants.violation(timeline, sources))
        val pruned = timeline.pruned()
        assertEquals("$message: links", pruned.links, timeline.links)
        assertEquals("$message: placements", pruned.placements, timeline.placements)
        assertEquals("$message: transitions", pruned.transitions, timeline.transitions)
        assertEquals("$message: stretches", pruned.generated, timeline.generated)
        assertEquals("$message: followers", pruned.audio.map { it.followsVideo }, timeline.audio.map { it.followsVideo })
        assertEquals(message, pruned, timeline)
    }

    /** Tests/TrackTimelineTests.swift:541-559: every item has a row, rows never hide an item, sound is under picture, and the rows agree with what is drawn in front. */
    fun assertLanesAreDisjoint(timeline: TrackTimeline, sources: List<SourceMedia>) {
        val lanes = timeline.lanes()
        assertEquals(timeline.allIDs, lanes.map { it.id }.toSet())
        lanes.forEachIndexed { index, lane ->
            for (other in lanes.drop(index + 1)) if (other.row == lane.row) assertNull(lane.interval.intersection(other.interval))
        }
        val lastVideo = lanes.filter { !it.audio }.maxOfOrNull { it.row } ?: -1
        assertTrue(lanes.filter { it.audio }.all { it.row > lastVideo })
        val row = lanes.filter { !it.audio }.associate { it.id to it.row }
        for (part in timeline.compile()) {
            assertEquals(timeline.video.filter { part.interval.start in it.interval }.map { it.id }.toSet(), part.video.toSet())
            assertEquals(part.video.sortedByDescending { row.getValue(it) }, part.video)
        }
        timeline.validate(sources)
    }
}
