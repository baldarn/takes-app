package app.takes.core

import app.takes.core.TrackFixtures.uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The full Move: the four scopes, a change of scope while moving, the
 * boundary picker and the stops, frame nudges and the held snap, as the
 * editor of the original runs them (YouRec/EditorView.swift:1162-1239).
 *
 * All of `Tests/MoveBoundaryChecks.swift` is made through the session, every
 * scope reached by a change of scope and every stop by the picker; the
 * checks of `Tests/TrackTimelineTests.swift:590-599` are made for every
 * scope; and the checks the design notes ask of Move
 * (docs/EDITOR-TRACKS-DESIGN.md:421-425): linked and independent drags,
 * clamping at zero, Cancel, source bounds, and no silent overwrite.
 */
class TrackMoveScopesTest {
    private fun t(seconds: Double) = EditTime.of(seconds)
    private fun timeline(clips: List<TimelineClip>) = EditState(clips).toTimeline()
    private fun begin(timeline: TrackTimeline, selection: TimelineSelection, playhead: Double = 0.0, scale: Double = 60.0) =
        TrackMoveSession.begin(timeline, selection, playhead, scale) ?: error("Nothing to move")
    private fun moved(outcome: TrackMoveSession.Outcome) = (outcome as TrackMoveSession.Outcome.Moved).timeline
    private fun TrackMoveSession.on(scope: TimelineMoveScope) = changingScope(scope).also { assertEquals(scope, it.scope) }

    private val long = SourceMedia(id = uuid(4), filename = "moves.mov", duration = 18.0)
    private val scenes = (0 until 3).map { TimelineClip(uuid(70 + it), long.id, 6.0 * it, 6.0 * (it + 1)) }
    private val base = timeline(scenes)
    private val picked = base.selection(scenes[1].id)

    private val source = SourceMedia(id = uuid(6), filename = "multi.mp4", duration = 12.0)
    private val clips = (0 until 3).map { TimelineClip(uuid(40 + it), source.id, it * 4.0, (it + 1) * 4.0) }
    private val original = timeline(clips)
    private val detached = original.unlinked(original.entireSelection())

    // MARK: Tests/MoveBoundaryChecks.swift, through the session

    /** Tests/MoveBoundaryChecks.swift:15-22: each stop once; strict, reversible stops; companions keep the picked anchor. */
    @Test
    fun theStopsOfEveryScopeKeepThePickedAnchor() {
        val session = begin(base, picked)
        val plain = session.boundaries
        assertEquals(t(6.0), plain.anchor)
        assertEquals(listOf(0.0, 6.0, 12.0, 18.0).map(::t), plain.times)
        assertEquals(t(12.0), plain.next(t(6.0)))
        assertEquals(t(6.0), plain.previous(t(12.0)))
        assertNull(plain.next(t(18.0)))
        assertNull(plain.previous(EditTime.ZERO))
        val left = session.on(TimelineMoveScope.Left).boundaries
        val right = session.on(TimelineMoveScope.Right).boundaries
        assertEquals(plain.anchor, left.anchor)
        assertNull("The group with its left side cannot go before zero", left.previous(plain.anchor))
        assertEquals("Companions skip internal cuts", listOf(0.0, 6.0, 18.0).map(::t), right.times)
        assertEquals(TrackMove.boundaries(base, session.picked, session.choices.both), session.on(TimelineMoveScope.Both).boundaries)
    }

    /** Tests/MoveBoundaryChecks.swift:23-37, for each scope: a stop chosen with the picker keeps every offset and the sync. */
    @Test
    fun everyStopOfEveryScopeKeepsEveryOffset() {
        var count = 0
        for (scope in TimelineMoveScope.entries) {
            val session = begin(base, picked).changingScope(scope)
            val plan = session.boundaries
            for (target in plan.times) {
                val placed = session.placed(target) ?: error("$target is a stop")
                assertEquals(target, placed.anchor)
                val draft = placed.draft
                for (item in base.video) {
                    val delta = if (item.id in session.moving.video) target - plan.anchor else EditTime.ZERO
                    assertEquals("Move must preserve all picture offsets and untouched clips", item.start + delta, draft.video.first { it.id == item.id }.start)
                }
                for (item in base.audio) {
                    val delta = if (item.id in session.moving.audio) target - plan.anchor else EditTime.ZERO
                    assertEquals("Move must preserve linked sync", item.start + delta, draft.audio.first { it.id == item.id }.start)
                }
                assertNull(TrackInvariants.violation(draft, listOf(long)))
                count += 1
            }
        }
        assertEquals(4 + 3 + 3 + 2, count)
    }

    /** Tests/MoveBoundaryChecks.swift:38-51, with the step buttons: a one-tick gap is two stops; a loose sound is placed alone. */
    @Test
    fun theStepButtonsReachBothEdgesOfAOneTickGap() {
        var gaps = moved(begin(base, base.selection(scenes[2].id)).dragged(EditTime(1).seconds, snapping = false).done(base))
        val tick = t(12.0) + EditTime(1)
        // The first scene, put at 6, steps once to the cut at 12 and once more to the edge a tick later.
        val first = begin(gaps, gaps.selection(scenes[0].id)).placed(t(6.0))!!
        val atCut = first.toNextStop()!!
        assertEquals(t(12.0), atCut.anchor)
        assertEquals(tick, atCut.toNextStop()!!.anchor)
        assertEquals(t(12.0), atCut.toNextStop()!!.toPreviousStop()!!.anchor)
        gaps = gaps.unlinked(gaps.selection(scenes[2].id))
        val loose = gaps.selection(gaps.audio[2].id)
        gaps = moved(begin(gaps, loose).dragged(1.0, snapping = false).done(gaps))
        assertTrue("An independent sound edit is a valid destination for picture", tick + t(1.0) in begin(gaps, picked).boundaries.times)
        val sound = begin(gaps, loose).placed(EditTime.ZERO)!!
        assertEquals("Detached audio can be precisely placed without moving picture", gaps.video, sound.draft.video)
        assertEquals(EditTime.ZERO, sound.draft.audio[2].start)
    }

    /** Tests/MoveBoundaryChecks.swift:53-58: a dissolve is a stop at its join for every scope that leaves one of its pictures. */
    @Test
    fun aDissolveIsAStopAtItsDrawnJoin() {
        val blend = TimelineEditing.setJoin(EditState(scenes), 1, TransitionSettings(duration = 1.0))!!.toTimeline()
        val join = EditTime.of(TrackLayout.of(blend).joins.first { it.transition != null }.time)
        val session = begin(blend, blend.selection(scenes[2].id))
        val folded = session.boundaries
        assertTrue(join in folded.times)
        assertFalse(blend.video[1].start in folded.times)
        assertFalse(blend.video[0].end in folded.times)
        // With its left side the last scene takes the dissolve along: its join is no stop of that group.
        val left = session.changingScope(TimelineMoveScope.Left)
        assertTrue(scenes[1].id in left.moving.video && scenes[0].id in left.moving.video)
        assertFalse(join in left.boundaries.times)
        val placed = session.placed(join)!!
        assertEquals(join, placed.anchor)
        assertNull(TrackInvariants.violation(placed.draft, listOf(long)))
    }

    /** Tests/MoveBoundaryChecks.swift:60-67: a marked range aligns from its own start; disjoint parts move together, keeping their gap. */
    @Test
    fun aMarkedRangeAlignsFromItsStartAndDisjointPartsMoveTogether() {
        val partial = begin(base, base.entireSelection(EditInterval.of(1.25, 3.25)))
        assertEquals(t(1.25), partial.boundaries.anchor)
        assertEquals(t(3.25), partial.nextStop)
        val grouped = begin(base, base.selection(setOf(scenes[0].id, scenes[2].id))).dragged(6.0, snapping = false)
        assertEquals(listOf(6.0, 6.0, 18.0).map(::t), grouped.draft.video.map { it.start })
    }

    // MARK: Tests/TrackTimelineTests.swift:590-599, every scope

    /**
     * Tests/TrackTimelineTests.swift:574-599 for each scope: the draft uses
     * up, trims and rewrites nothing, every card keeps its row and no row
     * hides another item, and the timeline stays valid with its footage
     * inside its sources.
     */
    @Test
    fun noScopeOverwritesTrimsOrMovesACardOffItsRow() {
        var count = 0
        for (start in listOf(original, detached)) {
            val selections = listOf(
                start.selection(clips[0].id), start.selection(clips[1].id), start.selection(clips[2].id),
                TimelineSelection(audio = setOf(start.audio[1].id)),
                start.selection(setOf(clips[0].id, clips[2].id)),
                start.selection(clips[1].id, range = EditInterval.of(5.0, 7.0)),
            )
            for (selection in selections) {
                val first = begin(start, selection)
                for (scope in first.scopes) {
                    val session = first.changingScope(scope)
                    val rows = session.base.lanes(session.moving.ids).associate { it.id to it.row }
                    val priorVideo = session.base.video.associateBy { it.id }
                    val priorAudio = session.base.audio.associateBy { it.id }
                    for (offset in listOf(-30.0, -4.0, -0.1, 0.0, 0.1, 4.0, 12.0)) {
                        val draft = session.dragged(offset, snapping = false).draft
                        assertEquals("Move cannot consume or replace another item", priorVideo.keys, draft.video.mapTo(HashSet()) { it.id })
                        assertEquals(priorAudio.keys, draft.audio.mapTo(HashSet()) { it.id })
                        for (item in draft.video) {
                            assertEquals("Move cannot trim or rewrite video", priorVideo.getValue(item.id).mapping, item.mapping)
                            assertEquals(priorVideo.getValue(item.id).content, item.content)
                        }
                        for (item in draft.audio) assertEquals("Move cannot trim audio", priorAudio.getValue(item.id).mapping, item.mapping)
                        val lanes = draft.lanes(session.moving.ids)
                        for (lane in lanes) {
                            assertEquals("Move keeps every card's row fixed as edges cross", rows[lane.id], lane.row)
                            assertTrue("Dedicated Move rows never hide another item", lanes.all { it.id == lane.id || it.row != lane.row || it.interval.intersection(lane.interval) == null })
                        }
                        assertNull(TrackInvariants.violation(draft, listOf(source)))
                        count += 1
                    }
                }
            }
        }
        assertTrue(count > 6 * 2 * 7)
    }

    // MARK: Scopes

    @Test
    fun theScopesFollowCutsAndStopAtAGap() {
        // A gap after the first picture: its right side stops there.
        val spaced = original.moved(original.selection(setOf(clips[1].id, clips[2].id)), t(1.0))
        val session = begin(spaced, spaced.selection(clips[0].id))
        assertEquals(listOf(TimelineMoveScope.Selected), session.scopes)
        assertSame(session, session.changingScope(TimelineMoveScope.Right))
        val middle = begin(spaced, spaced.selection(clips[1].id))
        assertEquals(listOf(TimelineMoveScope.Selected, TimelineMoveScope.Right), middle.scopes)
        val right = middle.changingScope(TimelineMoveScope.Right)
        assertEquals(setOf(clips[1].id, clips[2].id), right.moving.video)
        assertEquals(spaced.selection(setOf(clips[1].id, clips[2].id)).audio, right.moving.audio)
        // On the middle of three, both sides take all.
        val all = begin(original, original.selection(clips[1].id))
        assertEquals(TimelineMoveScope.entries, all.scopes)
        assertEquals(original.entireSelection().ids, all.changingScope(TimelineMoveScope.Both).moving.ids)
        assertSame(all, all.changingScope(TimelineMoveScope.Selected))
    }

    @Test
    fun aChangeOfScopeAsksForTheSameMoveAndGoingBackRestoresIt() {
        // The middle clip dragged 3 s earlier; its left side cannot go that far and stops at zero.
        val session = begin(original, original.selection(clips[1].id)).dragged(-3.0, snapping = false).dragEnded()
        assertEquals(t(-3.0), session.offset)
        val left = session.changingScope(TimelineMoveScope.Left)
        assertEquals(EditTime.ZERO, left.offset)
        assertEquals(t(-3.0), left.requested)
        assertEquals(EditTime.ZERO, left.interval?.start)
        assertEquals(original, left.draft)
        assertEquals(TrackMoveSession.Outcome.Unchanged, left.done(original))
        // Back on the selection alone, the drag asked for is there again, and the next drag starts from it.
        val back = left.changingScope(TimelineMoveScope.Selected)
        assertEquals(t(-3.0), back.offset)
        assertEquals(t(1.0), back.interval?.start)
        assertEquals(t(-3.0), back.dragOrigin)
        assertNull(back.snap)
        // To the right, the whole right side moves by the same amount.
        val right = session.changingScope(TimelineMoveScope.Right)
        assertEquals(t(-3.0), right.offset)
        assertEquals(listOf(0.0, 1.0, 5.0).map(::t), right.draft.video.map { it.start }.sorted())
        assertEquals(original.moved(right.moving, t(-3.0)), moved(right.done(original)))
    }

    // MARK: The boundary picker

    @Test
    fun aStopIsHeldAsASnapAndWidensTheViewport() {
        val session = begin(base, picked, scale = 60.0)
        assertNull(session.viewportEnd)
        assertEquals(base.duration, session.viewportDuration)
        assertNull(session.placed(t(7.0)))
        val end = session.placed(t(18.0))!!
        assertEquals(t(12.0), end.offset)
        assertEquals(TimelineMoveSnap(t(18.0), t(6.0)), end.snap)
        assertEquals(t(24.0), end.viewportEnd)
        assertEquals(t(12.0), end.dragOrigin)
        // A small drag stays on the held stop; a longer one lets go.
        assertEquals(t(12.0), end.dragged(0.2).offset)
        assertEquals(t(13.0), end.dragged(1.0).offset)
        // A drag keeps the viewport; another stop sets it again; a change of scope follows the draft.
        assertEquals(t(24.0), end.dragged(-12.0, snapping = false).viewportEnd)
        assertEquals(t(18.0), end.placed(t(0.0))!!.viewportEnd)
        val wider = end.changingScope(TimelineMoveScope.Right)
        assertEquals(t(30.0), wider.draft.duration)
        assertEquals(t(30.0), wider.viewportEnd)
        assertNull(session.changingScope(TimelineMoveScope.Right).viewportEnd)
        assertTrue(session.canPickBoundary)
        // Everything moving: zero is behind it, and the end of the timeline is the one stop ahead.
        val all = begin(base, base.entireSelection())
        assertNull(all.previousStop)
        assertEquals(t(18.0), all.nextStop)
        assertNull(all.toPreviousStop())
    }

    @Test
    fun markersGroupNearbyStopsAndATapPicksTheNearest() {
        val times = listOf(0.0, 0.1, 0.2, 2.0, 10.0).map(::t)
        // 60 points a second, scrolled 30 points: 0 is off the screen.
        val markers = TrackMove.markers(times, 400.0) { it.seconds * 60 - 3 }
        assertEquals(listOf(listOf(0.1, 0.2).map(::t), listOf(t(2.0))), markers.map { it.times })
        assertEquals(listOf(0.1, 0.2).map(::t), TrackMove.picked(markers, 20.0))
        assertEquals(listOf(t(2.0)), TrackMove.picked(markers, 130.0))
        assertNull(TrackMove.picked(markers, 300.0))
        assertNull(TrackMove.picked(emptyList(), 0.0))
        // Two stops in one frame need a precise label; others the ordinary one.
        val near = listOf(t(4.0), t(4.0) + EditTime(2))
        assertEquals("0:04.000008", TrackMove.preciseLabel(near[1], near))
        assertNull(TrackMove.preciseLabel(t(4.0), listOf(t(4.0), t(5.0))))
        assertEquals("1:01.500000", TrackMove.preciseLabel(t(61.5), listOf(t(61.5), t(61.5) + EditTime(1))))
    }

    // MARK: Nudges and the held snap

    @Test
    fun aNudgeIsAFrameUnsnappedAndHeldAtZero() {
        val session = begin(original, original.selection(clips[1].id))
        val later = session.nudged(1)
        assertEquals(t(1.0 / 30), later.offset)
        assertEquals(later.offset, later.dragOrigin)
        assertNull(later.snap)
        assertEquals(t(-0.1), session.nudged(-3).offset)
        assertSame(session, session.nudged(0))
        assertEquals(t(-4.0), session.nudged(-1000).offset)
        // A drag after a nudge starts from it.
        assertEquals(t(1.0 / 30 + 0.5), later.dragged(0.5, snapping = false).offset)
    }

    @Test
    fun theHeldSnapHasTheOriginalsHysteresis() {
        // Edges 10 points away are taken, kept up to 18 points away, then let go.
        val moving = TimelineSelection(video = setOf(clips[0].id))
        val scale = 100.0
        val session = begin(detached, moving, playhead = 30.0, scale = scale)
        val taken = session.dragged(3.91)
        assertEquals(t(4.0), taken.offset)
        assertEquals(t(4.0), taken.dragged(4.17).offset)
        assertEquals(t(4.19), taken.dragged(4.19).offset)
        assertNull(taken.dragged(4.19).snap)
        assertEquals(t(3.89), session.dragged(3.89).offset)
        // Without the magnet nothing is held.
        assertNull(taken.dragged(4.01, snapping = false).snap)
    }

    // MARK: docs/EDITOR-TRACKS-DESIGN.md:421-425

    @Test
    fun linkedAndIndependentDragsClampAtZeroAndCancelGivesBackTheSameBytes() {
        // Linked: the sound comes with its picture.
        val linked = begin(original, original.selection(clips[1].id)).dragged(-30.0, snapping = false)
        assertEquals(EditTime.ZERO, linked.interval?.start)
        assertEquals(EditTime.ZERO, linked.draft.audio.first { it.followsVideo == clips[1].id }.start)
        // Independent: the picture moves alone, its sound stays; held at zero, never before it.
        val alone = begin(detached, TimelineSelection(video = setOf(clips[2].id))).dragged(-30.0, snapping = false)
        assertEquals(EditTime.ZERO, alone.draft.video.first { it.id == clips[2].id }.start)
        assertEquals(detached.audio, alone.draft.audio)
        assertTrue(alone.draft.video.all { it.start >= EditTime.ZERO } && alone.draft.audio.all { it.start >= EditTime.ZERO })
        // Every scope at every stop and back: Cancel gives the same timeline and the same bytes.
        val bytes = original.encoded()
        var session = begin(original, original.selection(clips[1].id))
        for (scope in session.scopes) {
            session = session.changingScope(scope)
            session = session.toNextStop() ?: session.toPreviousStop() ?: session
            session = session.dragged(0.7).dragEnded().nudged(2)
        }
        assertTrue(session.draft != original)
        assertSame(original, session.cancel())
        assertEquals(bytes, session.cancel().encoded())
        assertNotNull(session.done(original) as? TrackMoveSession.Outcome.Moved)
        assertEquals(TrackMoveSession.Outcome.Stale, session.done(session.draft))
    }

    @Test
    fun aMoveKeepsFootageInsideItsSourcesAndOverwritesNothing() {
        for (scope in TimelineMoveScope.entries) {
            val session = begin(original, original.selection(clips[1].id)).changingScope(scope)
            for (offset in listOf(-6.0, -2.0, 2.0, 6.0)) {
                val draft = session.dragged(offset, snapping = false).draft
                assertNull(TrackInvariants.violation(draft, listOf(source)))
                // Pictures moved over others are in front of them; the covered ones are all still there, whole.
                assertEquals(original.video.map { it.mapping }, original.video.map { item -> draft.video.first { it.id == item.id }.mapping })
                assertEquals(original.audio.size, draft.audio.size)
                assertEquals(original.video.size, draft.video.size)
                for (item in draft.video) if (item.id in session.moving.video) {
                    val covered = draft.video.filter { it.id !in session.moving.video && it.interval.intersection(item.interval) != null }
                    assertTrue(covered.all { it.layer < item.layer })
                }
            }
        }
    }
}
