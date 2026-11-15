package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Snapping and the timeline's viewport.
 *
 * Tests marked TRANSLATED carry over an expectation of the iPhone app's
 * Tests/TrackTimelineTests.swift, whose fixture for the move is three 4 s
 * clips with their sound detached (lines 387-407): a dragged picture keeps
 * the edges of its own sound as targets. The others are this port's own,
 * from the formulas of YouRec/TrackTimeline.swift:1441-1527.
 */
class TimelineSnappingTest {
    private fun clip(start: Double, end: Double, marks: List<Double> = emptyList()) =
        TimelineClip(sourceID = "source", start = start, end = end, bookmarks = marks.map { Bookmark(time = it) }.ifEmpty { null })

    /** Every boundary of three 4 s pieces: what the original's detached sound leaves in place. */
    private val boundaries = listOf(0.0, 4.0, 8.0, 12.0)
    private val first = listOf(0.0, 4.0)
    private val third = listOf(8.0, 12.0)

    // MARK: Snap points

    @Test fun pointsAreClipEdgesSoundEdgesBookmarksAndTheStart() {
        val state = EditState(
            listOf(clip(0.0, 4.0, listOf(1.0)), clip(10.0, 14.0, listOf(12.5))),
            audio = listOf(AudioItem(sourceID = "music", start = 2.0, sourceStart = 0.0, sourceEnd = 20.0)),
        )
        assertEquals(listOf(0.0, 1.0, 2.0, 4.0, 6.5, 8.0, 22.0), TimelineSnapping.points(state))
        assertEquals(listOf(0.0, 4.0), TimelineSnapping.joins(listOf(clip(0.0, 4.0))))
        assertEquals(listOf(0.0, 4.0, 8.0), TimelineSnapping.joins(state.clips))
        assertEquals(listOf(0.0), TimelineSnapping.points(EditState()))
        assertEquals(listOf(0.0), TimelineSnapping.joins(emptyList()))
    }

    @Test fun whatIsDraggedAndItsBookmarksAreLeftOutAndThePlayheadIsAdded() {
        val state = EditState(
            listOf(clip(0.0, 4.0, listOf(1.0)), clip(10.0, 14.0)),
            audio = listOf(AudioItem(id = "music", sourceID = "music", start = 2.0, sourceStart = 0.0, sourceEnd = 3.0)),
        )
        assertEquals(listOf(0.0, 2.0, 4.0, 5.0, 8.0), TimelineSnapping.points(state, excludingClips = setOf(state.clips[0].id)))
        assertEquals(listOf(0.0, 1.0, 3.3, 4.0, 8.0), TimelineSnapping.points(state, excludingAudio = setOf("music"), extra = listOf(3.3)))
    }

    @Test fun aBookmarkOutsideTheVisiblePartOfItsClipIsNoPoint() {
        assertEquals(listOf(0.0, 2.0), TimelineSnapping.points(EditState(listOf(clip(3.0, 5.0, listOf(1.0, 5.0))))))
    }

    @Test fun aBookmarkFollowsTheSpeedOfItsClipAndADissolveGivesBothEdges() {
        val fast = EditState(listOf(clip(0.0, 8.0, listOf(4.0)).copy(speed = SpeedSettings(rate = 2.0))))
        assertEquals(listOf(0.0, 2.0, 4.0), TimelineSnapping.points(fast))
        val dissolved = TimelineEditing.setTransition(EditState(listOf(clip(0.0, 8.0), clip(8.0, 18.0))), 0, 2.0, ClipTransition.Style.Dissolve)!!
        assertEquals(listOf(0.0, 6.0, 8.0, 16.0), TimelineSnapping.points(dissolved))
    }

    // MARK: The playhead

    /** TRANSLATED TrackTimelineTests.swift:71-74. */
    @Test fun aBookmarkIsATargetButNotForItsOwnFootage() {
        val state = EditState(listOf(clip(0.0, 4.0, listOf(1.0)), clip(4.0, 8.0)))
        assertEquals(1.0, TimelineSnapping.target(1.02, 60.0, TimelineSnapping.points(state))!!, 0.0)
        assertNull(TimelineSnapping.target(1.02, 60.0, TimelineSnapping.points(state, excludingClips = setOf(state.clips[0].id))))
    }

    /** TrackTimeline.swift:1488-1491: within 8 units on screen, whatever the zoom. */
    @Test fun thePlayheadSnapsWithinEightUnitsAtAnyZoom() {
        assertEquals(8.0, TimelineSnapping.PLAYHEAD_REACH, 0.0)
        // 60 per second: 8 units are 0.1333 s.
        assertEquals(4.0, TimelineSnapping.target(4.13, 60.0, boundaries)!!, 0.0)
        assertEquals(4.0, TimelineSnapping.target(3.87, 60.0, boundaries)!!, 0.0)
        assertNull(TimelineSnapping.target(4.14, 60.0, boundaries))
        // 720 per second, the closest zoom: 0.0111 s.
        assertEquals(4.0, TimelineSnapping.target(4.011, 720.0, boundaries)!!, 0.0)
        assertNull(TimelineSnapping.target(4.012, 720.0, boundaries))
        // 10 per second: 0.8 s.
        assertEquals(4.0, TimelineSnapping.target(4.79, 10.0, boundaries)!!, 0.0)
        assertNull(TimelineSnapping.target(4.81, 10.0, boundaries))
        // 2 per second: every point is within reach, the nearest wins.
        assertEquals(8.0, TimelineSnapping.target(6.5, 2.0, boundaries)!!, 0.0)
        assertEquals(4.0, TimelineSnapping.target(5.5, 2.0, boundaries)!!, 0.0)
    }

    @Test fun snapReturnsTheTimeItselfWhenNothingIsNear() {
        assertEquals(4.0, TimelineSnapping.snap(4.1, 60.0, boundaries), 0.0)
        assertEquals(5.0, TimelineSnapping.snap(5.0, 60.0, boundaries), 0.0)
        assertEquals(5.0, TimelineSnapping.snap(5.0, 60.0, emptyList()), 0.0)
        assertNull(TimelineSnapping.target(5.0, 60.0, emptyList()))
    }

    @Test fun ofTwoPointsEquallyNearTheEarlierWins() {
        assertEquals(4.0, TimelineSnapping.target(4.5, 10.0, listOf(4.0, 5.0))!!, 0.0)
    }

    // MARK: A dragged piece

    /** TRANSLATED TrackTimelineTests.swift:494-495. */
    @Test fun aLeadingEdgeSnapsExactlyToANeighbouringBoundary() {
        val snap = TimelineSnapping.move(first, 3.9, 60.0, boundaries)
        assertEquals(4.0, snap.delta, 0.0)
        assertEquals(TimelineSnapping.MoveSnap(target = 4.0, edge = 0.0), snap.snap)
    }

    /** TRANSLATED TrackTimelineTests.swift:496-497. */
    @Test fun aSnapHoldsThroughSmallMovement() {
        val snap = TimelineSnapping.move(first, 3.9, 60.0, boundaries)
        val held = TimelineSnapping.move(first, 4.25, 60.0, boundaries, snap.snap)
        assertEquals(4.0, held.delta, 0.0)
        assertEquals(snap.snap, held.snap)
    }

    /** TRANSLATED TrackTimelineTests.swift:498-499. */
    @Test fun aSnapReleasesBeyondItsHold() {
        val held = TimelineSnapping.move(first, 4.25, 60.0, boundaries, TimelineSnapping.MoveSnap(4.0, 0.0))
        val released = TimelineSnapping.move(first, 4.4, 60.0, boundaries, held.snap)
        assertNull(released.snap)
        assertEquals(4.4, released.delta, 0.000005)
    }

    /** TRANSLATED TrackTimelineTests.swift:500-501. */
    @Test fun aTrailingEdgeSnapsFromTheOtherSide() {
        assertEquals(-4.0, TimelineSnapping.move(third, -3.9, 60.0, boundaries).delta, 0.0)
    }

    /** TRANSLATED TrackTimelineTests.swift:502-503. */
    @Test fun reachIsMeasuredOnScreenNotInTime() {
        val zoomed = TimelineSnapping.move(first, 3.9, 720.0, boundaries)
        assertNull(zoomed.snap)
        assertEquals(3.9, zoomed.delta, 0.000005)
    }

    /** TRANSLATED TrackTimelineTests.swift:504-505. */
    @Test fun aPieceNeverGoesBeforeTheStart() {
        assertEquals(0.0, TimelineSnapping.move(first, -100.0, 60.0, boundaries).delta, 0.0)
        assertEquals(-8.0, TimelineSnapping.move(third, -100.0, 60.0, emptyList()).delta, 0.0)
    }

    /** TRANSLATED TrackTimelineTests.swift:506-507: with everything moving, only the start of the movie is left to snap to. */
    @Test fun movingEverythingSnapsToNothingOfItsOwn() {
        val all = TimelineSnapping.move(boundaries, 0.5, 60.0, listOf(0.0))
        assertNull(all.snap)
        assertEquals(0.5, all.delta, 0.0)
    }

    /** TRANSLATED TrackTimelineTests.swift:526-531: a bookmark at 4.2 beside the cut at 4. */
    @Test fun aHeldSnapYieldsToANearerPointUnderTheFinger() {
        val dense = boundaries + 4.2
        val a = TimelineSnapping.move(first, 3.99, 60.0, dense)
        assertEquals(4.0, a.delta, 0.0)
        val b = TimelineSnapping.move(first, 4.2, 60.0, dense, a.snap)
        assertEquals(4.2, b.delta, 0.0)
        assertEquals(TimelineSnapping.MoveSnap(target = 4.2, edge = 0.0), b.snap)
    }

    /** TrackTimeline.swift:1514: an edge takes hold within 10 units; the distances in seconds at three zooms. */
    @Test fun acquireIsTenUnitsAtAnyZoom() {
        assertEquals(10.0, TimelineSnapping.MOVE_ACQUIRE, 0.0)
        // 60 per second: 0.1667 s.
        assertNotNull(TimelineSnapping.move(first, 3.84, 60.0, boundaries).snap)
        assertNull(TimelineSnapping.move(first, 3.83, 60.0, boundaries).snap)
        // 30 per second: 0.3333 s.
        assertNotNull(TimelineSnapping.move(first, 3.67, 30.0, boundaries).snap)
        assertNull(TimelineSnapping.move(first, 3.66, 30.0, boundaries).snap)
        // 240 per second: 0.0417 s.
        assertNotNull(TimelineSnapping.move(first, 4.041, 240.0, boundaries).snap)
        assertNull(TimelineSnapping.move(first, 4.043, 240.0, boundaries).snap)
    }

    /** TrackTimeline.swift:1517-1522: a held snap lasts to 18 units; the distances in seconds at three zooms. */
    @Test fun releaseIsEighteenUnitsAtAnyZoom() {
        assertEquals(18.0, TimelineSnapping.MOVE_HOLD, 0.0)
        val latch = TimelineSnapping.MoveSnap(target = 4.0, edge = 0.0)
        // 60 per second: 0.3 s.
        assertEquals(latch, TimelineSnapping.move(first, 4.29, 60.0, boundaries, latch).snap)
        assertNull(TimelineSnapping.move(first, 4.31, 60.0, boundaries, latch).snap)
        assertEquals(latch, TimelineSnapping.move(first, 3.71, 60.0, boundaries, latch).snap)
        assertNull(TimelineSnapping.move(first, 3.69, 60.0, boundaries, latch).snap)
        // 30 per second: 0.6 s.
        assertEquals(latch, TimelineSnapping.move(first, 4.59, 30.0, boundaries, latch).snap)
        assertNull(TimelineSnapping.move(first, 4.61, 30.0, boundaries, latch).snap)
        // 240 per second: 0.075 s.
        assertEquals(latch, TimelineSnapping.move(first, 4.074, 240.0, boundaries, latch).snap)
        assertNull(TimelineSnapping.move(first, 4.076, 240.0, boundaries, latch).snap)
    }

    /** TrackTimeline.swift:1520: holding gives way only to a point more than one unit nearer. */
    @Test fun aHeldSnapKeepsHoldAgainstAPointBarelyNearer() {
        val latch = TimelineSnapping.MoveSnap(target = 4.0, edge = 0.0)
        // At 4.05 the latch is 3 units away and the point at 4.09 is 2.4: not nearer by more than one.
        assertEquals(latch, TimelineSnapping.move(first, 4.05, 60.0, boundaries + 4.09, latch).snap)
        // At 4.08 the latch is 4.8 away and the point 0.6: it gives way.
        assertEquals(4.09, TimelineSnapping.move(first, 4.08, 60.0, boundaries + 4.09, latch).delta, 0.0)
    }

    @Test fun aLatchThatNoLongerAppliesIsIgnored() {
        val gone = TimelineSnapping.MoveSnap(target = 5.0, edge = 0.0)
        assertNull(TimelineSnapping.move(first, 5.1, 60.0, boundaries, gone).snap)
        val otherEdge = TimelineSnapping.MoveSnap(target = 4.0, edge = 1.0)
        assertNull(TimelineSnapping.move(first, 3.25, 60.0, boundaries, otherEdge).snap)
        // Holding it would put the piece before the start.
        val before = TimelineSnapping.MoveSnap(target = 0.0, edge = 4.0)
        assertEquals(-1.0, TimelineSnapping.move(listOf(1.0, 4.0), -3.9, 60.0, listOf(0.0, 8.0), before).delta, 0.0)
    }

    @Test fun thePlayheadIsATargetForADraggedPiece() {
        val state = EditState(listOf(clip(0.0, 4.0)), audio = listOf(AudioItem(id = "music", sourceID = "music", start = 6.0, sourceStart = 0.0, sourceEnd = 3.0)))
        val targets = TimelineSnapping.points(state, excludingAudio = setOf("music"), extra = listOf(5.5))
        assertEquals(listOf(0.0, 4.0, 5.5), targets)
        // Its start comes to the playhead; then its end comes to the clip's end.
        assertEquals(-0.5, TimelineSnapping.move(listOf(6.0, 9.0), -0.6, 60.0, targets).delta, 0.0)
        assertEquals(TimelineSnapping.MoveSnap(target = 4.0, edge = 9.0), TimelineSnapping.move(listOf(6.0, 9.0), -4.9, 60.0, targets).snap)
    }

    @Test fun nothingToMoveMovesNothing() {
        assertEquals(TimelineSnapping.MoveResult(0.0, null), TimelineSnapping.move(emptyList(), 3.0, 60.0, boundaries))
    }

    // MARK: The viewport

    /** TRANSLATED TrackTimelineTests.swift:113-114. */
    @Test fun aSixteenMinuteProjectFitsWithBothEndsOnScreen() {
        val viewport = TimelineViewport(width = 402.0, duration = 960.0, requestedScale = 0.01)
        assertTrue(viewport.fits)
        assertTrue(viewport.x(0.0, 0.0) >= 0)
        assertTrue(viewport.x(960.0, 0.0) <= 402)
        assertTrue(viewport.minimumScale < 0.4)
        assertEquals(306.0 / 960, viewport.scale, 1e-12)
        assertEquals(48.0, viewport.x(0.0, 0.0), 0.0)
        assertEquals(354.0, viewport.x(960.0, 0.0), 1e-9)
        assertEquals(402.0, viewport.contentWidth, 1e-9)
    }

    /** TrackTimeline.swift:1441-1456. */
    @Test fun zoomedInThePlayheadIsInTheMiddleAndTheMovieScrolls() {
        val viewport = TimelineViewport(width = 400.0, duration = 60.0, requestedScale = 60.0)
        assertFalse(viewport.fits)
        assertEquals(60.0, viewport.scale, 0.0)
        assertEquals(200.0, viewport.inset, 0.0)
        assertEquals(4000.0, viewport.contentWidth, 0.0)
        // Scrolled by 600, second 10 is under the playhead in the middle.
        assertEquals(200.0, viewport.x(10.0, 600.0), 0.0)
        assertEquals(10.0, viewport.time(200.0, 600.0), 0.0)
        assertEquals(10.0, viewport.scrubTime(600.0), 0.0)
        // A finger past either end is at that end.
        assertEquals(0.0, viewport.time(0.0, 0.0), 0.0)
        assertEquals(60.0, viewport.time(400.0, 3600.0), 0.0)
    }

    @Test fun pointsAndSecondsConvertBackAndForthAtEveryZoom() {
        for (scale in listOf(0.01, 6.0, 60.0, 240.0, 720.0)) {
            val viewport = TimelineViewport(width = 400.0, duration = 120.0, requestedScale = scale)
            for (time in listOf(0.0, 0.5, 33.3, 120.0)) assertEquals(time, viewport.time(viewport.x(time, 150.0), 150.0), 1e-9)
        }
    }

    @Test fun theScaleStaysBetweenWholeMovieAndTheClosestZoom() {
        assertEquals(720.0, TimelineViewport(400.0, 60.0, 5000.0).scale, 0.0)
        assertEquals(304.0 / 60, TimelineViewport(400.0, 60.0, 0.0).scale, 1e-12)
        assertEquals(60.0, TimelineViewport.DEFAULT_SCALE, 0.0)
        // A movie under a second is laid out as one second, and an empty view still has a scale.
        assertEquals(304.0, TimelineViewport(400.0, 0.2, 1.0).minimumScale, 0.0)
        assertEquals(0.000001, TimelineViewport(50.0, 60.0, 1.0).minimumScale, 0.0)
    }

    /** YouRec/EditorTimeline.swift:186-187. */
    @Test fun theZoomMenuOffersFivePresets() {
        val presets = TimelineViewport(width = 400.0, duration = 600.0, requestedScale = 60.0).presets
        assertEquals(listOf("Whole project", "5 minutes", "1 minute", "10 seconds", "1 second"), presets.map { it.first })
        assertEquals(listOf(304.0 / 600, 400.0 / 300, 400.0 / 60, 40.0, 400.0), presets.map { it.second })
    }

    /** Snap reach in seconds at the presets of a 400-unit-wide view: 8 units divided by the scale. */
    @Test fun snapReachInSecondsFollowsTheZoom() {
        val viewport = TimelineViewport(width = 400.0, duration = 600.0, requestedScale = 40.0)
        val reach = TimelineSnapping.PLAYHEAD_REACH / viewport.scale
        assertEquals(0.2, reach, 1e-12)
        assertEquals(100.0, TimelineSnapping.target(100.0 + reach - 0.001, viewport.scale, listOf(100.0))!!, 0.0)
        assertNull(TimelineSnapping.target(100.0 + reach + 0.001, viewport.scale, listOf(100.0)))
    }
}
