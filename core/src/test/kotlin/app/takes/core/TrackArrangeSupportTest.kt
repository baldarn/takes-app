package app.takes.core

import app.takes.core.TrackFixtures.uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The cut, the deletion, the opening of time and the joins that the
 * arranging operations use ([TrackArrangeSupport]), against the original's
 * checks of the same functions (`Tests/TrackTimelineTests.swift`). These
 * functions stand in for the basic editing operations of the track timeline
 * and go when those are in the core; the checks say that, until then, what
 * the arranging operations build on behaves as the original does.
 */
class TrackArrangeSupportTest {
    private fun t(seconds: Double) = EditTime.of(seconds)

    // Tests/TrackTimelineTests.swift:13-21.
    private val source = SourceMedia(id = uuid(1), filename = "source.mp4", duration = 4.0)
    private val sources = listOf(source)
    private val a = TimelineClip(uuid(2), source.id, 0.0, 2.0)
    private val b = TimelineClip(uuid(3), source.id, 2.0, 4.0)
    private val baseline = EditState(listOf(a, b)).toTimeline()
    private val first = baseline.selection(a.id)

    private fun settled(timeline: TrackTimeline) {
        assertNull(TrackInvariants.violation(timeline, sources))
        assertEquals(timeline, timeline.pruned())
    }

    /** Tests/TrackTimelineTests.swift:29-34: where the playhead goes when linked footage is deleted. */
    @Test
    fun aLinkedDeletionTakesThePlayheadWithWhatIsLeft() {
        for ((before, after) in listOf(0.5 to 0.0, 2.0 to 0.0, 3.25 to 1.25, 4.0 to 2.0)) {
            assertEquals("$before", t(after), TrackArrangeSupport.remove(baseline, first, t(before)).position)
        }
        assertEquals(t(0.75), TrackArrangeSupport.remove(baseline, baseline.selection(b.id), t(0.75)).position)
        assertNull(TrackArrangeSupport.remove(baseline, first).position)
    }

    /** Tests/TrackTimelineTests.swift:40-42: "Linked deletion closes gap in both components". */
    @Test
    fun aLinkedDeletionClosesItsTimeInPictureAndSound() {
        val ripple = TrackArrangeSupport.remove(baseline, first).timeline
        assertEquals(t(2.0), ripple.duration)
        assertEquals(EditTime.ZERO, ripple.video[0].start)
        assertEquals(EditTime.ZERO, ripple.audio[0].start)
        settled(ripple)
    }

    /** Tests/TrackTimelineTests.swift:49-56: an unlinked sound or picture deleted by itself leaves a gap and moves nothing. */
    @Test
    fun anUnlinkedDeletionLeavesAGap() {
        val separate = baseline.unlinked(first)
        val sound = TrackArrangeSupport.remove(separate, separate.selection(baseline.audio[0].id), t(3.0))
        assertEquals(t(3.0), sound.position)
        assertEquals(baseline.video, sound.timeline.video)
        assertEquals(listOf(baseline.audio[1]), sound.timeline.audio)
        assertEquals(t(4.0), sound.timeline.duration)
        val picture = TrackArrangeSupport.remove(separate, separate.selection(a.id), t(1.0))
        assertEquals(t(1.0), picture.position)
        assertEquals(separate.audio, picture.timeline.audio)
        assertEquals(listOf(baseline.video[1]), picture.timeline.video)
        assertEquals(t(4.0), picture.timeline.duration)
        settled(picture.timeline)
    }

    /** Tests/TrackTimelineTests.swift:66-70: "Deleting a linked clip never cuts or shifts detached audio". */
    @Test
    fun aLinkedDeletionNeverMovesUnlinkedSound() {
        val bed = baseline.unlinked(baseline.selection(b.id))
        val detached = bed.audio[1]
        assertTrue(detached in TrackArrangeSupport.remove(bed, first).timeline.audio)
    }

    /** Tests/TrackTimelineTests.swift:75-77: "Split preserves all source and creates independent linked pairs". */
    @Test
    fun aCutKeepsAllFootageAndMakesLinkedPairs() {
        val cut = TrackArrangeSupport.split(baseline, t(1.0), first)
        assertEquals(baseline.duration, cut.timeline.duration)
        assertEquals(3, cut.timeline.video.size)
        assertEquals(3, cut.timeline.audio.size)
        assertEquals(3, cut.timeline.links.size)
        assertEquals(setOf(a.id, TrackTimeline.counterpart(a.id)), cut.children.keys)
        val right = cut.timeline.video[1]
        assertEquals(a.id, right.occurrenceID)
        assertEquals(t(1.0), right.localOrigin)
        assertEquals(1.0, right.content.start, 0.0)
        assertEquals(right.id, cut.timeline.audio[1].followsVideo)
        settled(cut.timeline)
        // On an edge, and outside the selection, nothing is cut.
        assertEquals(baseline, TrackArrangeSupport.split(baseline, t(2.0), baseline.entireSelection()).timeline)
        assertEquals(baseline, TrackArrangeSupport.split(baseline, t(3.0), first).timeline)
    }

    /** Tests/TrackTimelineTests.swift:83-88: a range deleted across two linked clips. */
    @Test
    fun aRangeIsCutAtBothEndsAndDeletedExactly() {
        val scoped = TrackArrangeSupport.scoped(baseline, baseline.entireSelection(EditInterval.of(0.5, 3.5)))
        assertEquals(2, scoped.selection.video.size)
        assertEquals(2, scoped.selection.audio.size)
        assertNull(scoped.selection.range)
        val removal = TrackArrangeSupport.remove(scoped.timeline, scoped.selection, t(3.75))
        assertEquals(t(0.75), removal.position)
        assertTrue(abs(removal.timeline.duration.seconds - 1) < 0.00001)
        assertEquals(listOf(0.0, 3.5), removal.timeline.video.map { it.content.start })
        assertEquals(listOf(0.5, 4.0), removal.timeline.video.map { it.content.end })
        settled(removal.timeline)
    }

    /** Tests/TrackTimelineTests.swift:99-102: "Insert time preserves crossing pieces and shifts right side". */
    @Test
    fun openingTimeCutsWhatCrossesAndMovesTheRest() {
        val opened = TrackArrangeSupport.insertTime(baseline, t(1.0), t(1.0))
        assertEquals(t(5.0), opened.duration)
        assertEquals(3, opened.video.size)
        assertEquals(listOf(t(0.0), t(2.0), t(3.0)), opened.video.map { it.start })
        settled(opened)
    }

    /**
     * Tests/TrackTimelineTests.swift:143-157: "Video split conserves exact
     * ticks through speed", "Audio and picture split stay sample-aligned",
     * "Scope never selects only one linked component because of rounding".
     * The original's clips also have pause cuts, which clips here do not.
     */
    @Test
    fun aCutConservesTicksThroughAnySpeed() {
        val random = TrackFixtures.Random()
        repeat(2000) {
            val clip = TimelineClip(uuid(5), source.id, 0.0, 4.0, speed = SpeedSettings(rate = 0.25 + random.next() * 3.75))
            val graph = EditState(listOf(clip)).toTimeline()
            val end = graph.duration
            val at = EditTime(maxOf(1L, (end.ticks * (0.1 + random.next() * 0.8)).toLong()))
            val cut = TrackArrangeSupport.split(graph, at, graph.entireSelection()).timeline
            assertEquals(at, cut.video[0].end)
            assertEquals(at, cut.video[1].start)
            assertEquals(end, cut.video[1].end)
            assertEquals(at, cut.audio[0].end)
            assertEquals(end, cut.audio[1].end)
            val selected = TrackArrangeSupport.scoped(cut, cut.entireSelection(EditInterval(at, end)))
            assertEquals(1, selected.selection.video.size)
            assertEquals(1, selected.selection.audio.size)
        }
    }

    /**
     * Tests/TrackTimelineTests.swift:133-142: "Transition handles do not
     * cause playhead drift after deletion", "Deleting a linked clip between
     * two transitions closes only the newly vacated gap". The transitions
     * are set in the sequence form and converted.
     */
    @Test
    fun deletingAClipBetweenTwoTransitionsClosesOnlyItsOwnTime() {
        val c = TimelineClip(uuid(4), source.id, 0.0, 2.0)
        var state = EditState(listOf(a, b, c))
        state = TimelineEditing.setJoin(state, 1, TransitionSettings(duration = 0.5))!!
        state = TimelineEditing.setJoin(state, 2, TransitionSettings(duration = 0.5))!!
        val three = state.toTimeline()
        assertEquals(2, three.transitions.size)
        val priorLast = three.video[2].start
        val removal = TrackArrangeSupport.remove(three, three.selection(b.id), priorLast + t(1.0))
        val result = removal.timeline
        assertEquals(result.video.first { it.id == c.id }.start + t(1.0), removal.position)
        assertEquals(result.video[0].end, result.video[1].start)
        assertEquals(t(4.0), result.duration)
        assertTrue(result.transitions.isEmpty())
        settled(result)
    }

    /**
     * Tests/TrackTimelineTests.swift:821-830: "Removing a linked transition
     * restores the straight cut". The dissolve is set in the sequence form.
     */
    @Test
    fun removingATransitionRestoresTheStraightCut() {
        val clips = (0 until 3).map { TimelineClip(uuid(10 + it), source.id, 0.0, 4.0) }
        val original = EditState(clips).toTimeline()
        val dissolve = TimelineEditing.setJoin(EditState(clips), 1, TransitionSettings(duration = 1.0))!!.toTimeline()
        assertEquals(listOf(t(0.0), t(3.0), t(7.0)), dissolve.video.map { it.start })
        val straight = TrackArrangeSupport.restoringStraightCut(dissolve, dissolve.transitions[0].id)
        assertEquals(original, straight)
        assertEquals(dissolve, TrackArrangeSupport.restoringStraightCut(dissolve, uuid(99)))
    }

    @Test
    fun theJoinsOfASequenceAreItsCutsItsStartAndItsEnd() {
        val joins = TrackArrangeSupport.joins(baseline)
        assertEquals(listOf(0.0, 2.0, 4.0), joins.map { it.time })
        assertTrue(joins.all { it.selectable && it.transition == null })
        assertEquals(listOf(null, a.id, b.id), joins.map { it.outgoingID })
        assertEquals(listOf(a.id, b.id, null), joins.map { it.incomingID })
        assertEquals(listOf(-1, 0, 1), joins.map { it.edge })
        assertTrue(TrackArrangeSupport.joins(TrackTimeline()).isEmpty())
        // Two pictures a tick apart follow one another and do not meet.
        val apart = baseline.copy(video = baseline.video.map { if (it.id == b.id) it.copy(start = it.start + EditTime(1)) else it })
        assertEquals(listOf(true, false, true), TrackArrangeSupport.joins(apart).map { it.selectable })
        assertEquals(listOf(EditInterval.of(0.0, 2.0), EditInterval(t(2.0) + EditTime(1), t(4.0) + EditTime(1))), TrackArrangeSupport.videoCoverage(apart))
    }
}
