package app.takes.core

import app.takes.core.TrackFixtures.uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reduced Move: a session on the "selected" scope, as the editor of the
 * original runs it (YouRec/EditorView.swift:1162-1239). The checks of
 * `Tests/TrackTimelineTests.swift:590-599` and of the selected scope in
 * `Tests/MoveBoundaryChecks.swift` are made through the session, with what
 * the editor needs on top: the moved picture in front, Cancel giving back
 * the same bytes, Done after several drags as one edit, and the gaps a move
 * leaves.
 */
class TrackMoveSessionTest {
    private fun t(seconds: Double) = EditTime.of(seconds)
    private fun timeline(clips: List<TimelineClip>) = EditState(clips).toTimeline()
    private fun begin(timeline: TrackTimeline, selection: TimelineSelection, playhead: Double = 0.0, scale: Double = 60.0) =
        TrackMoveSession.begin(timeline, selection, playhead, scale) ?: error("Nothing to move")
    private fun moved(outcome: TrackMoveSession.Outcome) = (outcome as TrackMoveSession.Outcome.Moved).timeline

    private val source = SourceMedia(id = uuid(6), filename = "multi.mp4", duration = 12.0)
    private val clips = (0 until 3).map { TimelineClip(uuid(40 + it), source.id, it * 4.0, (it + 1) * 4.0) }
    private val original = timeline(clips)
    private val detached = original.unlinked(original.entireSelection())

    // MARK: Tests/TrackTimelineTests.swift:574-599, through the session

    /**
     * Tests/TrackTimelineTests.swift:574-599: seven selections, linked and
     * unlinked, dragged by twelve offsets. The draft is the move of the
     * scoped selection; it uses up, trims and rewrites nothing; every card
     * keeps its row and no row hides another item.
     */
    @Test
    fun theDraftNeverOverwritesAndKeepsEveryCardOnItsRow() {
        var count = 0
        for (base in listOf(original, detached)) {
            val selections = listOf(
                base.selection(clips[0].id), base.selection(clips[1].id), base.selection(clips[2].id),
                TimelineSelection(audio = setOf(base.audio[1].id)),
                base.selection(setOf(clips[0].id, clips[2].id)), base.entireSelection(),
                base.selection(clips[1].id, range = EditInterval.of(5.0, 7.0)),
            )
            for (selection in selections) {
                val session = begin(base, selection)
                // A range is cut first, with new ids for the pieces.
                val scoped = TrackEditing.scoped(base, selection)
                assertEquals(scoped.timeline.video.map { it.interval }, session.base.video.map { it.interval })
                assertEquals(scoped.selection.ids.size, session.moving.ids.size)
                val rows = session.base.lanes(session.moving.ids).associate { it.id to it.row }
                val priorVideo = session.base.video.associateBy { it.id }
                val priorAudio = session.base.audio.associateBy { it.id }
                for (offset in listOf(-30.0, -8.0, -4.0, -2.0, -0.1, 0.0, 0.1, 2.0, 4.0, 6.0, 8.0, 12.0)) {
                    val draft = session.dragged(offset, snapping = false).draft
                    assertEquals(session.base.moved(session.moving, t(offset)), draft)
                    assertEquals(priorVideo.keys, draft.video.mapTo(HashSet()) { it.id })
                    assertEquals(priorAudio.keys, draft.audio.mapTo(HashSet()) { it.id })
                    for (item in draft.video) {
                        assertEquals(priorVideo.getValue(item.id).mapping, item.mapping)
                        assertEquals(priorVideo.getValue(item.id).content, item.content)
                    }
                    for (item in draft.audio) assertEquals(priorAudio.getValue(item.id).mapping, item.mapping)
                    val lanes = draft.lanes(session.moving.ids)
                    for (lane in lanes) {
                        assertEquals("Move keeps every card's row", rows[lane.id], lane.row)
                        assertTrue(lanes.all { it.id == lane.id || it.row != lane.row || it.interval.intersection(lane.interval) == null })
                    }
                    assertNull(TrackInvariants.violation(draft, listOf(source)))
                    count += 1
                }
            }
        }
        assertEquals(168, count)
    }

    // MARK: Tests/MoveBoundaryChecks.swift, the selected scope

    private val long = SourceMedia(id = uuid(4), filename = "moves.mov", duration = 18.0)
    private val scenes = (0 until 3).map { TimelineClip(uuid(70 + it), long.id, 6.0 * it, 6.0 * (it + 1)) }
    private val scenery = timeline(scenes)
    private val picked = scenery.selection(scenes[1].id)

    /** Tests/MoveBoundaryChecks.swift:15-19, :23-37: each stop once, and a move to any of them keeps every offset and the sync. */
    @Test
    fun theStopsAreTheEdgesOfWhatStaysAndAMoveToOneKeepsEveryOffset() {
        val session = begin(scenery, picked)
        val plan = session.boundaries
        assertEquals(t(6.0), plan.anchor)
        assertEquals(listOf(0.0, 6.0, 12.0, 18.0).map(::t), plan.times)
        for (target in plan.times) {
            val draft = session.dragged((target - plan.anchor).seconds, snapping = false).draft
            for (item in scenery.video) {
                val delta = if (item.id in session.moving.video) target - plan.anchor else EditTime.ZERO
                assertEquals(item.start + delta, draft.video.first { it.id == item.id }.start)
            }
            for (item in scenery.audio) {
                val delta = if (item.id in session.moving.audio) target - plan.anchor else EditTime.ZERO
                assertEquals(item.start + delta, draft.audio.first { it.id == item.id }.start)
            }
            assertNull(TrackInvariants.violation(draft, listOf(long)))
        }
    }

    /** Tests/MoveBoundaryChecks.swift:38-51: a gap of a tick is two stops; a loose sound is placed alone. */
    @Test
    fun aGapOfOneTickHasTwoStopsAndALooseSoundMovesAlone() {
        var gaps = moved(begin(scenery, scenery.selection(scenes[2].id)).dragged(EditTime(1).seconds, snapping = false).done(scenery))
        assertEquals(scenery.video[2].start + EditTime(1), gaps.video[2].start)
        val tick = t(12.0) + EditTime(1)
        val stops = begin(gaps, gaps.selection(scenes[0].id)).boundaries
        assertEquals(tick, stops.next(t(12.0)))
        assertEquals(t(12.0), stops.previous(tick))
        gaps = gaps.unlinked(gaps.selection(scenes[2].id))
        val loose = gaps.selection(gaps.audio[2].id)
        gaps = moved(begin(gaps, loose).dragged(1.0, snapping = false).done(gaps))
        assertTrue(tick + t(1.0) in begin(gaps, picked).boundaries.times)
        val sound = begin(gaps, loose)
        val draft = sound.dragged((EditTime.ZERO - sound.boundaries.anchor).seconds, snapping = false).draft
        assertEquals(gaps.video, draft.video)
        assertEquals(EditTime.ZERO, draft.audio[2].start)
    }

    /** Tests/MoveBoundaryChecks.swift:60-67: a marked range moves from its own start; separate parts move together. */
    @Test
    fun aMarkedRangeMovesFromItsOwnStartAndSeparatePartsMoveTogether() {
        val partial = begin(scenery, scenery.entireSelection(EditInterval.of(1.25, 3.25)))
        assertEquals(t(1.25), partial.boundaries.anchor)
        assertEquals(t(3.25), partial.boundaries.next(t(1.25)))
        assertEquals(EditInterval.of(1.25, 3.25), partial.interval)
        val grouped = begin(scenery, scenery.selection(setOf(scenes[0].id, scenes[2].id))).dragged(6.0, snapping = false)
        assertEquals(listOf(6.0, 6.0, 18.0).map(::t), grouped.draft.video.map { it.start })
    }

    // MARK: The session

    @Test
    fun aLinkedPairMovesTogetherAndStopsAtZero() {
        val session = begin(original, TimelineSelection(audio = setOf(original.audio[1].id)))
        assertEquals(original.selection(clips[1].id).ids, session.moving.ids)
        val right = session.dragged(1.5, snapping = false)
        assertEquals(t(5.5), right.draft.video.first { it.id == clips[1].id }.start)
        assertEquals(t(5.5), right.draft.audio.first { it.id == original.audio[1].id }.start)
        val left = right.dragEnded().dragged(-30.0)
        assertEquals(t(-4.0), left.offset)
        assertEquals(EditTime.ZERO, left.interval?.start)
        assertNull(TrackMoveSession.begin(original, TimelineSelection(), 0.0, 60.0))
    }

    @Test
    fun anOverlapMadeByADragPutsTheMovedPictureInFront() {
        val session = begin(original, original.selection(clips[0].id)).dragged(2.0, snapping = false)
        val parts = session.draft.compile().filter { it.video.size == 2 }
        assertTrue(parts.isNotEmpty())
        assertTrue(parts.all { it.video.last() == clips[0].id })
        assertEquals(clips[0].id, moved(session.done(original)).video.maxBy { it.layer }.id)
        // The later picture dragged back over the earlier one comes to the front too.
        val back = begin(original, original.selection(clips[1].id)).dragged(-2.0, snapping = false)
        assertTrue(back.draft.compile().filter { it.video.size == 2 }.all { it.video.last() == clips[1].id })
    }

    @Test
    fun cancelGivesBackTheSameBytes() {
        val ranged = original.selection(clips[1].id, range = EditInterval.of(5.0, 7.0))
        for (selection in listOf(original.selection(clips[0].id), ranged)) {
            val bytes = original.encoded()
            val session = begin(original, selection).dragged(2.0).dragEnded().dragged(3.0).dragEnded()
            assertTrue(session.draft != original)
            assertSame(original, session.cancel())
            assertEquals(bytes, session.cancel().encoded())
        }
        // The cuts for a range are no edit when nothing moved.
        assertEquals(TrackMoveSession.Outcome.Unchanged, begin(original, ranged).done(original))
        assertEquals(TrackMoveSession.Outcome.Unchanged, begin(original, ranged).dragged(1.0).dragged(0.0, snapping = false).done(original))
    }

    @Test
    fun doneAfterThreeDragsIsOneEdit() {
        val selection = original.selection(clips[0].id)
        var session = begin(original, selection)
        session = session.dragged(1.0, snapping = false).dragEnded()
        session = session.dragged(1.5, snapping = false).dragEnded()
        session = session.dragged(-0.5, snapping = false).dragEnded()
        assertEquals(t(2.0), session.offset)
        assertEquals(TrackMoveSession.Outcome.Moved(original.moved(selection, t(2.0))), session.done(original))
        // Changed under the session: nothing to commit.
        assertEquals(TrackMoveSession.Outcome.Stale, session.done(original.moved(original.selection(clips[2].id), t(1.0))))
    }

    @Test
    fun aDragSnapsToEdgesThePlayheadAndBookmarksAndHoldsTheSnap() {
        val moving = TimelineSelection(video = setOf(clips[0].id))
        var session = begin(detached, moving, playhead = 6.3)
        session = session.dragged(3.9)
        assertEquals(t(4.0), session.offset)
        assertEquals(TimelineMoveSnap(t(4.0), EditTime.ZERO), session.snap)
        session = session.dragEnded()
        assertEquals(t(4.0), session.dragged(0.25).offset)
        assertEquals(t(4.4), session.dragged(0.25).dragged(0.4).offset)
        assertNull(session.dragged(0.25).dragged(0.4).snap)
        assertEquals(t(3.9), session.dragged(-0.1, snapping = false).offset)
        // The playhead when Move began is a point; zoomed in, the same drag does not reach.
        assertEquals(t(6.3), begin(detached, moving, playhead = 6.3).dragged(6.25).offset)
        assertEquals(t(6.25), begin(detached, moving, playhead = 6.3, scale = 720.0).dragged(6.25).offset)
        // A stationary bookmark is a point; the moved footage's own is not.
        assertEquals(t(5.0), begin(detached.addingBookmark(t(5.0)), moving).dragged(4.95).offset)
        val own = original.addingBookmark(t(1.0))
        assertEquals(t(0.98), begin(own, own.selection(clips[0].id)).dragged(0.98).offset)
        // A drag the system took away goes back to where it began, unsnapped.
        val cancelled = session.dragged(2.0).dragCancelled()
        assertEquals(t(4.0), cancelled.offset)
        assertNull(cancelled.snap)
    }

    // MARK: Gaps

    @Test
    fun aClipMovedOutOfTheMiddleLeavesAGapThatClosesAtAPoint() {
        val out = moved(begin(original, original.selection(clips[1].id)).dragged(8.0, snapping = false).done(original))
        assertEquals(listOf(EditInterval.of(4.0, 8.0)), out.gaps)
        assertEquals(out.gaps, out.pictureGaps)
        assertEquals(EditInterval.of(4.0, 8.0), out.gap(t(5.0)))
        val closed = out.closingGap(t(5.0))
        assertEquals(listOf(0.0, 4.0, 8.0).map(::t), closed.chronologicalVideo.map { it.start })
        assertEquals(emptyList<EditInterval>(), closed.gaps)
        assertEquals(out, out.closingGap(t(2.0)))
        // A picture moved off its sound leaves black time, not a gap.
        val black = moved(begin(detached, TimelineSelection(video = setOf(clips[1].id))).dragged(8.0, snapping = false).done(detached))
        assertEquals(emptyList<EditInterval>(), black.gaps)
        assertEquals(listOf(EditInterval.of(4.0, 8.0)), black.pictureGaps)
        // Moving the first clip later opens a gap at the start.
        val late = original.moved(original.entireSelection(), t(2.0))
        assertEquals(listOf(EditInterval.of(0.0, 2.0)), late.gaps)
        assertEquals(emptyList<EditInterval>(), original.gaps)
    }
}
