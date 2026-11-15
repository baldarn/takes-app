package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * The checks the tests of the editing operations share.
 */
internal object TrackEditingSupport {
    fun ticks(seconds: Double) = EditTime.of(seconds)

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
