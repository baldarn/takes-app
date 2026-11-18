package app.takes.core

import app.takes.core.TrackFixtures.uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stitching of linked groups on the timeline, with the checks of the
 * original's Tests/TrackTimelineTests.swift:461-485 at 57e5d91 on three
 * clips of four seconds (:389-416).
 */
class TimelineLinkLayoutTest {
    private fun t(seconds: Double) = EditTime.of(seconds)
    private val source = SourceMedia(id = uuid(1), filename = "multi.mp4", duration = 12.0)
    private val clips = (0 until 3).map { TimelineClip(uuid(10 + it), source.id, it * 4.0, (it + 1) * 4.0) }
    private val original = EditState(clips).toTimeline()
    private val all = original.entireSelection()
    private val together = original.joining(all).valueOrNull!!
    private val detached = original.unlinked(all)
    private val pictureOnly = detached.joining(TimelineSelection(video = all.video)).valueOrNull!!

    private fun layout(timeline: TrackTimeline, moving: Set<String>? = null) = TimelineLinkLayout(timeline.lanes(moving), timeline.links)

    @Test fun gluedPictureAndSoundShareTheirInternalCuts() {
        val glued = layout(together.timeline)
        assertEquals(clips[0].id, glued.edges[clips[1].id]?.previous)
        assertEquals(clips[2].id, glued.edges[clips[1].id]?.next)
        assertEquals(t(4.0), glued.edges[clips[1].id]?.previousDuration)
        assertEquals(original.audio[2].id, glued.edges[original.audio[1].id]?.next)
        assertEquals(6, glued.edges[clips[0].id]?.memberCount)
    }

    @Test fun theQuietOutlineMarksHorizontalGlueOnly() {
        assertTrue(layout(together.timeline).edges.values.all { it.outlinedGroup })
        assertTrue(layout(original).edges.values.none { it.outlinedGroup })
    }

    @Test fun onlyExplicitlyGluedGroupsShareOutsideBorders() {
        val separate = layout(original)
        assertNull(separate.edges[clips[1].id]?.previous)
        assertEquals(3, separate.bridges.size)
        assertEquals(listOf(EditInterval(t(0.0), t(12.0))), layout(together.timeline).bridges.map { it.interval })
        assertTrue(separate.edges.getValue(clips[1].id).joinsBelow)
        assertTrue(separate.edges.getValue(original.audio[1].id).joinsAbove)
        assertEquals(2, separate.edges[clips[1].id]?.memberCount)
    }

    @Test fun unlinkedSoundStaysIndependent() {
        val glued = layout(pictureOnly.timeline)
        assertTrue(glued.bridges.isEmpty())
        assertEquals(clips[2].id, glued.edges[clips[1].id]?.next)
        assertNull(glued.edges[original.audio[1].id])
        assertTrue(layout(detached).edges.isEmpty())
    }

    @Test fun aOneTickGapNeverAcquiresASharedOutline() {
        val gapped = together.timeline.let { it.copy(video = it.video.mapIndexed { i, item -> if (i == 2) item.copy(start = item.start + EditTime(1)) else item }) }
        val edges = layout(gapped).edges
        assertNull(edges[clips[1].id]?.next)
        assertFalse(edges.getValue(clips[2].id).joinsBelow)
    }

    @Test fun liftingAnItemIntoALaneOfItsOwnCannotJoinThroughAnotherRow() {
        val lifted = layout(together.timeline, moving = setOf(clips[1].id)).edges.getValue(clips[1].id)
        assertNull(lifted.previous)
        assertFalse(lifted.joinsBelow)
    }

    // MARK: As the editor draws it

    @Test fun onTheEditorsTimelineAPicturesOwnSoundIsOnItsCard() {
        val state = TrackEditState.edit(together.timeline)
        val drawn = TimelineLinkLayout.drawn(state, emptyMap(), emptyList())
        // The cuts the glued pictures share, on their one row; no border with sound drawn on the cards.
        assertEquals(clips[0].id, drawn.edges[clips[1].id]?.previous)
        assertTrue(drawn.edges.getValue(clips[1].id).outlinedGroup)
        assertEquals(6, drawn.edges[clips[1].id]?.memberCount)
        assertTrue(drawn.bridges.isEmpty())
        assertTrue(TimelineLinkLayout.drawn(EditState(clips), emptyMap(), emptyList()).edges.values.none { it.outlinedGroup })
    }
}
