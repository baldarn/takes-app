package app.takes.core

import app.takes.core.TrackFixtures.uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Move mode: the original's `moveCompanionTests`, `overlapInteractionTests`,
 * the Move half of `multiSelectionTests` and of `trackTimelineTests`
 * (`Tests/TrackTimelineTests.swift`) and all of `Tests/MoveBoundaryChecks.swift`,
 * with their numbers. Each check names its lines. Where the original sets a
 * transition on the timeline, the same edit is made in the sequence form and
 * converted. The original's checks that save a project with a timeline and
 * undo it (`:601-610`) wait for a project that holds one; the timeline is
 * written and read back instead.
 */
class TrackMoveTest {
    private fun t(seconds: Double) = EditTime.of(seconds)
    private fun timeline(clips: List<TimelineClip>) = EditState(clips).toTimeline()
    private fun <T> done(outcome: TimelineLinkOutcome<T>): T = outcome.valueOrNull ?: error("Refused: ${outcome.errorOrNull}")
    private fun TrackTimeline.picture(id: String) = video.first { it.id == id }
    private fun TrackTimeline.layers() = video.sortedWith(VideoTimelineItem.backToFront).map { it.id }

    private fun settled(timeline: TrackTimeline, sources: List<SourceMedia>) {
        assertNull(TrackInvariants.violation(timeline, sources))
        assertEquals(timeline, timeline.pruned())
    }

    // MARK: Tests/TrackTimelineTests.swift:13-21, the two linked clips

    private val short = SourceMedia(id = uuid(1), filename = "source.mp4", duration = 4.0)
    private val a = TimelineClip(uuid(2), short.id, 0.0, 2.0)
    private val b = TimelineClip(uuid(3), short.id, 2.0, 4.0)
    private val baseline = timeline(listOf(a, b))
    private val first = baseline.selection(a.id)

    /** Tests/TrackTimelineTests.swift:57-62: "Moving detached video leaves audio immutable", "Overlap compiler orders newly moved video in foreground", "Move clamps whole group at project start". */
    @Test
    fun anUnlinkedPictureMovesAloneComesToTheFrontAndStopsAtZero() {
        val separate = baseline.unlinked(first)
        var moving = separate.moved(separate.selection(a.id), t(2.5))
        assertEquals(separate.audio, moving.audio)
        assertEquals(t(2.5), moving.video[0].start)
        assertTrue(moving.compile().any { it.video.size == 2 && it.video.last() == a.id })
        settled(moving, listOf(short))
        moving = moving.moved(moving.selection(a.id), t(-30.0))
        assertEquals(EditTime.ZERO, moving.video[0].start)
        assertSame(moving, moving.moved(moving.selection(a.id), t(-1.0)))
        assertSame(moving, moving.moved(TimelineSelection(), t(1.0)))
    }

    /** Tests/TrackTimelineTests.swift:63-65: "Moving linked AV preserves sync". */
    @Test
    fun aLinkedPairMovesTogether() {
        val moved = baseline.moved(first, t(1.0))
        assertEquals(t(1.0), moved.video[0].start)
        assertEquals(moved.video[0].start, moved.audio[0].start)
        // Either member moves the pair.
        assertEquals(moved, baseline.moved(TimelineSelection(audio = first.audio), t(1.0)))
        settled(moved, listOf(short))
    }

    /** Tests/TrackTimelineTests.swift:71-74: "Stationary source marks are snap targets", "Moving footage never snaps to its own attached bookmark". */
    @Test
    fun bookmarksAreSnapPointsExceptThoseOfWhatMoves() {
        val marks = baseline.addingBookmark(t(1.0))
        assertEquals(1.0, marks.snapTarget(1.02, 60.0)!!, 0.0)
        assertNull(marks.snapTarget(1.02, 60.0, excluding = first.ids))
        assertEquals(listOf(t(0.0), t(1.0), t(2.0), t(4.0)), TrackMove.snapPoints(marks))
        assertEquals(listOf(t(0.0), t(2.0), t(3.0), t(4.0)), TrackMove.snapPoints(marks, first.ids, listOf(3.0)))
        assertEquals(1.02, TrackMove.snap(marks, 1.02, 60.0, first.ids), 0.0)
        assertEquals(1.0, TrackMove.snap(marks, 1.02, 60.0), 0.0)
    }

    /** Tests/TrackTimelineTests.swift:75-82: "Bookmark at a split is not duplicated", "Source bookmark follows its occurrence after split and move". */
    @Test
    fun aBookmarkFollowsItsFootageThroughACutAndAMove() {
        var marks = baseline.addingBookmark(t(1.0))
        marks = TrackArrangeSupport.split(marks, t(1.0), marks.selection(a.id)).timeline
        assertEquals(listOf(t(1.0)), marks.resolvedBookmarks.map { it.time })
        val right = marks.video.first { it.start == t(1.0) }
        marks = marks.moved(marks.selection(right.id), t(4.0))
        assertEquals(t(5.0), marks.resolvedBookmarks[0].time)
        assertEquals(right.id, TrackBookmarks.owner(marks, marks.bookmarks[0]))
        settled(marks, listOf(short))
    }

    // MARK: Tests/TrackTimelineTests.swift:328-383, moveCompanionTests

    private val long = SourceMedia(id = uuid(4), filename = "companions.mov", duration = 18.0)
    private val five = (0 until 5).map { TimelineClip(uuid(20 + it), long.id, 3.0 * it, 3.0 * it + 3) }
    private val overlay = TimelineClip(uuid(30), long.id, 0.0, 10.0)
    private val score = SourceMedia(id = uuid(5), filename = "score.m4a", duration = 16.0, audioOnly = true)

    /** The five clips with the last two a tick late, an overlay from 2 s on layer 12 and a score under everything (:331-338). */
    private fun companions(): TrackTimeline {
        var timeline = timeline(five)
        timeline = timeline.moved(timeline.selection(five.takeLast(2).mapTo(HashSet()) { it.id }), EditTime(1))
        return timeline.copy(
            video = timeline.video + VideoTimelineItem.of(uuid(31), t(2.0), overlay, layer = 12),
            audio = timeline.audio + AudioTimelineItem(uuid(32), uuid(33), score.id, EditTime.ZERO, SourcePlaybackMap.single(t(16.0), 0.0, 16.0)),
        )
    }

    private fun TrackTimeline.pictures(vararg indices: Int) = selection(indices.mapTo(HashSet()) { five[it].id })

    /** Tests/TrackTimelineTests.swift:340-345. */
    @Test
    fun moveTakesTheChosenSidesWithLinkedSoundAndStopsAtAGapOfOneTick() {
        val timeline = companions()
        val seed = timeline.selection(five[1].id)
        val choices = timeline.moveChoices(seed)
        assertEquals(seed, choices.selected)
        assertEquals(timeline.pictures(0, 1), choices.left)
        assertEquals(timeline.pictures(1, 2), choices.right)
        assertEquals(timeline.pictures(0, 1, 2), choices.both)
        assertEquals(choices.left, choices[TimelineMoveScope.Left])
        assertEquals(choices.both, choices[TimelineMoveScope.Both])
        assertTrue(TimelineMoveScope.Both.includesLeft && TimelineMoveScope.Both.includesRight && !TimelineMoveScope.Left.includesRight && !TimelineMoveScope.Selected.includesLeft)
        // "Each selected island has its own left and right neighbors"
        val multiple = timeline.moveChoices(timeline.pictures(0, 4))
        assertEquals(timeline.pictures(0, 3, 4), multiple.left)
        assertEquals(timeline.pictures(0, 1, 2, 4), multiple.right)
    }

    /** Tests/TrackTimelineTests.swift:346-355: "Move preserves relative picture offsets", "Linked audio moves with its picture, independent music stays". */
    @Test
    fun aMoveKeepsEveryOffsetAndLeavesIndependentSound() {
        val timeline = companions()
        val right = timeline.moveChoices(timeline.selection(five[1].id)).right
        val moved = timeline.moved(right, t(0.75))
        for (picture in timeline.video) {
            val delta = if (picture.id in right.video) t(0.75) else EditTime.ZERO
            assertEquals(picture.start + delta, moved.picture(picture.id).start)
        }
        for (sound in timeline.audio) {
            val delta = if (sound.id in right.audio) t(0.75) else EditTime.ZERO
            assertEquals(sound.start + delta, moved.audio.first { it.id == sound.id }.start)
        }
        settled(moved, listOf(long, score))
    }

    /** Tests/TrackTimelineTests.swift:356-370. */
    @Test
    fun picturesAndIndependentSoundsHaveJoinsOfTheirOwn() {
        val detached = timeline(five).let { it.unlinked(it.entireSelection()) }
        val audio = detached.moveChoices(detached.selection(detached.audio[2].id))
        assertTrue(audio.both.video.isEmpty())
        assertEquals(detached.audio.mapTo(HashSet()) { it.id }, audio.both.audio)
        val picture = detached.moveChoices(detached.selection(five[2].id))
        assertTrue(picture.both.audio.isEmpty())
        assertEquals(5, picture.both.video.size)

        // "An overlay cut exactly at a main edit follows its own continuation only"
        var stacked = detached.copy(video = detached.video + VideoTimelineItem.of(uuid(31), t(1.0), overlay, layer = 12))
        stacked = TrackArrangeSupport.split(stacked, t(6.0), stacked.entireSelection()).timeline
        val overlayIDs = stacked.video.filter { it.layer == 12 }.mapTo(HashSet()) { it.id }
        assertEquals(2, overlayIDs.size)
        assertEquals(overlayIDs, stacked.moveChoices(stacked.selection(overlay.id)).right.video)
        // "The main edit stays on its own layer at a stacked cut"
        assertEquals(picture.both.video, stacked.moveChoices(stacked.selection(five[2].id)).both.video)

        // "A direction with no additional neighbors is unavailable"
        val end = detached.moveChoices(detached.selection(five.last().id))
        assertTrue(end.available(TimelineMoveScope.Selected))
        assertTrue(end.available(TimelineMoveScope.Left))
        assertFalse(end.available(TimelineMoveScope.Right))
        assertFalse(end.available(TimelineMoveScope.Both))
    }

    /** Tests/TrackTimelineTests.swift:372-379: "Walk through cuts and an overlapping transition transitively", "A transition follows its moving pair". */
    @Test
    fun aTransitionJoinsItsPicturesAndMovesWithThem() {
        val joined = TimelineEditing.setJoin(EditState(five.take(3)), 1, TransitionSettings(style = TransitionStyle.Dissolve, duration = 1.0))!!.toTimeline()
        val relation = joined.transitions[0]
        val fromLast = joined.moveChoices(joined.selection(five[2].id)).left
        assertEquals(joined.entireSelection(), fromLast)
        val moved = joined.moved(fromLast, t(2.0))
        assertEquals(relation.origin + t(2.0), moved.transitions.first().origin)
        assertEquals(relation.copy(origin = relation.origin + t(2.0)), moved.transitions.single())
        assertEquals(joined.generated.map { it.copy(interval = EditInterval(it.interval.start + t(2.0), it.interval.end + t(2.0))) }, moved.generated)
        settled(moved, listOf(long))
        // A picture taken away from its transition loses it.
        val apart = joined.moved(joined.selection(five[1].id), t(20.0))
        assertTrue(apart.transitions.isEmpty() && apart.generated.isEmpty())
        settled(apart, listOf(long))
    }

    /** Tests/TrackTimelineTests.swift:380-382: "Marked ranges recruit outward from their scoped boundaries". */
    @Test
    fun aMarkedRangeRecruitsOutwardsFromItsOwnEnds() {
        val detached = timeline(five).let { it.unlinked(it.entireSelection()) }
        val scoped = TrackArrangeSupport.scoped(detached, TimelineSelection(video = setOf(five[1].id), range = EditInterval.of(4.0, 5.0)))
        val range = scoped.timeline.moveChoices(scoped.selection)
        assertEquals(1, range.selected.video.size)
        assertEquals(3, range.left.video.size)
        assertEquals(5, range.right.video.size)
    }

    @Test
    fun atOneCutTheHalvesOfAnOccurrenceAreMatchedBeforeTheRest() {
        fun part(id: String, occurrence: String) = TrackMove.CutNeighbor(id, occurrence)
        assertEquals(
            listOf("o1" to "o2", "main1" to "main2"),
            TrackMove.neighbors(listOf(part("main1", "A"), part("o1", "O")), listOf(part("o2", "O"), part("main2", "B"))),
        )
        assertEquals(listOf("x" to "y"), TrackMove.neighbors(listOf(part("x", "A"), part("z", "C")), listOf(part("y", "B"))))
        assertTrue(TrackMove.neighbors(listOf(part("x", "A")), emptyList()).isEmpty())
    }

    // MARK: Tests/TrackTimelineTests.swift:385-533, the Move half of multiSelectionTests

    private val source = SourceMedia(id = uuid(6), filename = "multi.mp4", duration = 12.0)
    private val clips = (0 until 3).map { TimelineClip(uuid(40 + it), source.id, it * 4.0, (it + 1) * 4.0) }
    private val original = timeline(clips)
    private val all = original.entireSelection()
    private val detached = original.unlinked(all)

    /** Tests/TrackTimelineTests.swift:460-479: "Moving the new link leaves outside tails stationary". */
    @Test
    fun movingALinkOfSharedTimeLeavesTheTailsWhereTheyAre() {
        var mismatch = timeline(listOf(TimelineClip(uuid(50), source.id, 0.0, 8.0)))
        mismatch = mismatch.unlinked(mismatch.entireSelection())
        mismatch = mismatch.moved(TimelineSelection(audio = setOf(mismatch.audio[0].id)), t(2.0))
        val clipped = done(mismatch.joining(mismatch.entireSelection(), intersectionOnly = true))
        val moved = clipped.timeline.moved(clipped.selection, t(1.0))
        assertEquals(clipped.timeline.video[0], moved.video[0])
        assertEquals(clipped.timeline.audio[1], moved.audio[1])
        assertEquals(t(3.0), moved.video[1].start)
        assertEquals(t(3.0), moved.audio[0].start)
        settled(moved, listOf(source))
    }

    /** Tests/TrackTimelineTests.swift:492-507: snapping on exact ticks, at two scales, with a held snap. */
    @Test
    fun aMoveSnapsOnExactTicksAndHoldsUntilTheFingerIsFarEnough() {
        val moving = TimelineSelection(video = setOf(clips[0].id))
        val snap = detached.snappedMove(moving, t(3.9), 60.0)
        assertEquals(t(4.0), snap.delta)
        assertEquals(TimelineMoveSnap(t(4.0), EditTime.ZERO), snap.snap)
        val held = detached.snappedMove(moving, t(4.25), 60.0, latch = snap.snap)
        assertEquals(t(4.0), held.delta)
        val released = detached.snappedMove(moving, t(4.4), 60.0, latch = held.snap)
        assertNull(released.snap)
        assertEquals(t(4.4), released.delta)
        val trailing = detached.snappedMove(TimelineSelection(video = setOf(clips[2].id)), t(-3.9), 60.0)
        assertEquals(t(-4.0), trailing.delta)
        val zoomed = detached.snappedMove(moving, t(3.9), 720.0)
        assertNull(zoomed.snap)
        val clamp = detached.snappedMove(moving, t(-100.0), 60.0)
        assertEquals(EditTime.ZERO, clamp.delta)
        val selfOnly = original.snappedMove(all, t(0.5), 60.0)
        assertNull(selfOnly.snap)
        assertEquals(t(0.5), selfOnly.delta)
        assertEquals(TimelineMoveResult(EditTime.ZERO), detached.snappedMove(TimelineSelection(), t(1.0), 60.0))
    }

    /** Tests/TrackTimelineTests.swift:522-526: "Moving a group preserves its internal foreground/background order". */
    @Test
    fun movingAGroupKeepsItsOwnOrderInFrontAndBehind() {
        var layers = detached.moved(TimelineSelection(video = setOf(clips[0].id)), t(5.0))
        val before = layers.video.sortedBy { it.layer }.map { it.id }
        layers = layers.moved(layers.entireSelection(), t(1.0))
        assertEquals(before, layers.video.sortedBy { it.layer }.map { it.id })
    }

    /** Tests/TrackTimelineTests.swift:527-531: "A sticky snap yields to a nearer cut or bookmark under the finger". */
    @Test
    fun aHeldSnapGivesWayToANearerPointUnderTheFinger() {
        val moving = TimelineSelection(video = setOf(clips[0].id))
        val dense = detached.addingBookmark(t(4.2))
        val targetA = dense.snappedMove(moving, t(3.99), 60.0)
        assertEquals(t(4.0), targetA.delta)
        val targetB = dense.snappedMove(moving, t(4.2), 60.0, latch = targetA.snap)
        assertEquals(t(4.2), targetB.delta)
    }

    // MARK: Tests/TrackTimelineTests.swift:537-650, overlapInteractionTests

    /** Tests/TrackTimelineTests.swift:541-558. */
    private fun lanesAreDisjoint(timeline: TrackTimeline) {
        val lanes = timeline.lanes()
        assertEquals(timeline.allIDs, lanes.mapTo(HashSet()) { it.id })
        lanes.forEachIndexed { index, lane ->
            for (other in lanes.drop(index + 1)) if (other.row == lane.row) assertNull("Two overlapping items on one row", lane.interval.intersection(other.interval))
        }
        val lastVideo = lanes.filter { !it.audio }.maxOfOrNull { it.row } ?: -1
        assertTrue(lanes.filter { it.audio }.all { it.row > lastVideo })
        val row = lanes.filter { !it.audio }.associate { it.id to it.row }
        for (part in timeline.compile()) {
            val actual = timeline.video.filter { part.interval.start in it.interval }.mapTo(HashSet()) { it.id }
            assertEquals(actual, part.video.toSet())
            assertEquals("The upper row is the picture in front", part.video.sortedByDescending { row.getValue(it) }, part.video)
        }
        settled(timeline, listOf(source))
    }

    /** Tests/TrackTimelineTests.swift:559-573. */
    private fun retainedFootage(timeline: TrackTimeline) {
        clips.forEachIndexed { index, clip ->
            val pictures = timeline.video.filter { it.occurrenceID == clip.id }.flatMap { it.mapping.runs }
            val sounds = timeline.audio.filter { it.occurrenceID == clip.id }.flatMap { it.mapping.runs }
            for (runs in listOf(pictures, sounds)) {
                val ordered = runs.sortedBy { it.sourceStart }
                assertEquals(index * 4.0, ordered.first().sourceStart, 0.0)
                assertEquals((index + 1) * 4.0, ordered.last().sourceEnd, 0.0)
                assertTrue(abs(ordered.sumOf { it.sourceEnd - it.sourceStart } - 4) < 0.00001)
                for ((left, right) in ordered.zipWithNext()) assertTrue(abs(left.sourceEnd - right.sourceStart) < 0.00001)
            }
        }
    }

    /**
     * Tests/TrackTimelineTests.swift:574-622: seven selections, linked and
     * unlinked, each moved by twelve offsets: 168 arrangements. A move never
     * uses up, trims or rewrites an item; every item stays on a row of its
     * own; deleting what was moved shows what it covered, whole.
     */
    @Test
    fun aMoveNeverOverwritesAndEveryItemStaysVisible() {
        var count = 0
        for (unlink in listOf(false, true)) {
            val base = if (unlink) detached else original
            val selections = listOf(
                base.selection(clips[0].id), base.selection(clips[1].id), base.selection(clips[2].id),
                TimelineSelection(audio = setOf(base.audio[1].id)),
                base.selection(setOf(clips[0].id, clips[2].id)), base.entireSelection(),
                base.selection(clips[1].id, range = EditInterval.of(5.0, 7.0)),
            )
            for (selection in selections) {
                val scoped = TrackArrangeSupport.scoped(base, selection)
                val moveRows = scoped.timeline.lanes(scoped.selection.ids).associate { it.id to it.row }
                val priorVideo = scoped.timeline.video.associateBy { it.id }
                val priorAudio = scoped.timeline.audio.associateBy { it.id }
                for (offset in listOf(-30.0, -8.0, -4.0, -2.0, -0.1, 0.0, 0.1, 2.0, 4.0, 6.0, 8.0, 12.0)) {
                    val moved = scoped.timeline.moved(scoped.selection, t(offset))
                    assertEquals(priorVideo.keys, moved.video.mapTo(HashSet()) { it.id })
                    assertEquals(priorAudio.keys, moved.audio.mapTo(HashSet()) { it.id })
                    for (item in moved.video) {
                        assertEquals(priorVideo.getValue(item.id).mapping, item.mapping)
                        assertEquals(priorVideo.getValue(item.id).content, item.content)
                    }
                    for (item in moved.audio) assertEquals(priorAudio.getValue(item.id).mapping, item.mapping)
                    lanesAreDisjoint(moved)
                    retainedFootage(moved)
                    val draftLanes = moved.lanes(scoped.selection.ids)
                    for (lane in draftLanes) {
                        assertEquals("Move keeps every card's row", moveRows[lane.id], lane.row)
                        assertTrue(draftLanes.all { it.id == lane.id || it.row != lane.row || it.interval.intersection(lane.interval) == null })
                    }
                    assertEquals(moved, TrackTimeline.decoded(moved.encoded()))
                    // Deleting what was moved shows what it covered, whole.
                    var deleted = moved.unlinked(moved.entireSelection())
                    deleted = TrackArrangeSupport.remove(deleted, scoped.selection).timeline
                    for (item in deleted.video) {
                        assertEquals(priorVideo.getValue(item.id).content, item.content)
                        assertEquals(priorVideo.getValue(item.id).mapping, item.mapping)
                    }
                    lanesAreDisjoint(deleted)
                    count += 1
                }
            }
        }
        assertEquals(168, count)
    }

    /** Tests/TrackTimelineTests.swift:623-639: overlaps, a cut, a link, an unlink and a hundred moves either way, one after another. */
    @Test
    fun aHundredMixedMovesKeepEveryItemAndAllItsFootage() {
        var sequence = original.moved(original.selection(clips[1].id), t(-2.0))
        lanesAreDisjoint(sequence)
        sequence = TrackArrangeSupport.split(sequence, t(3.0), sequence.selection(clips[1].id)).timeline
        val secondPieces = sequence.video.filter { it.occurrenceID == clips[1].id }.mapTo(HashSet()) { it.id }
        val joined = done(sequence.joining(sequence.selection(secondPieces)))
        sequence = joined.timeline.moved(joined.selection, t(1.0))
        lanesAreDisjoint(sequence)
        retainedFootage(sequence)
        sequence = sequence.unlinked(sequence.entireSelection())
        for (index in 0 until 100) {
            val item = sequence.video[index % sequence.video.size]
            sequence = sequence.moved(TimelineSelection(video = setOf(item.id)), t(if (index % 2 == 0) 0.37 else -0.23))
            lanesAreDisjoint(sequence)
            retainedFootage(sequence)
        }
    }

    /** Tests/TrackTimelineTests.swift:640-650: "Nudging a covered picture preserves foreground order", "A sync nudge cannot replace the visible picture". */
    @Test
    fun adjustingAnOverlapThatIsAlreadyThereSwapsNothing() {
        var stacked = original.moved(original.selection(clips[1].id), t(-2.0))
        val lower = clips[0].id
        val top = clips[1].id
        val prior = stacked.layers()
        stacked = stacked.moved(stacked.selection(lower), t(1.0 / 30))
        assertEquals(prior, stacked.layers())
        assertEquals(top, stacked.compile().first { lower in it.video && top in it.video }.video.last())
    }

    @Test
    fun picturesMovedOntoOthersComeToTheFrontInTheirOwnOrder() {
        val before = detached.video
        val moved = before.map { if (it.id != clips[2].id) it.copy(start = it.start + t(6.0)) else it }
        val promoted = TrackMove.promotingNewOverlaps(moved, setOf(clips[0].id, clips[1].id), before)
        assertEquals(listOf(3, 4, 2), promoted.map { it.layer })
        // Nothing new is overlapped: nothing changes.
        assertSame(before, TrackMove.promotingNewOverlaps(before, setOf(clips[0].id), before))
        val apart = before.map { if (it.id == clips[2].id) it.copy(start = it.start + t(6.0)) else it }
        assertSame(apart, TrackMove.promotingNewOverlaps(apart, setOf(clips[2].id), before))
    }

    // MARK: Tests/TrackTimelineTests.swift:268-281, placements across a move

    /** "Moving a placed overlay across a split preserves its window instead of turning it into full-frame Cover". */
    @Test
    fun aPlacedOverlayMovedAcrossACutKeepsItsWindow() {
        val take = SourceMedia(id = uuid(7), filename = "source.mp4", duration = 8.0)
        val base = TimelineClip(uuid(60), take.id, 0.0, 8.0)
        val window = VideoPlacement(x = 0.7, y = 0.3, width = 0.4, height = 0.4, edgeCrop = PlacementEdgeCrop(left = 0.1))
        var timeline = timeline(listOf(base))
        timeline = timeline.unlinked(timeline.entireSelection())
        val over = VideoTimelineItem.of(uuid(62), t(1.0), TimelineClip(uuid(61), take.id, 0.0, 2.0))
        timeline = timeline.copy(
            video = timeline.video + over,
            placements = listOf(VideoPlacementRegion(participants = setOf(base.id, over.id), videos = mapOf(base.id to window, over.id to window))),
        )
        timeline = TrackArrangeSupport.split(timeline, t(4.0), TimelineSelection(video = setOf(base.id))).timeline
        assertEquals(1, timeline.placements.size)
        val moved = timeline.moved(TimelineSelection(video = setOf(over.id)), t(4.0))
        val plan = moved.compile().first { t(5.5) in it.interval }
        val region = moved.placements.first { it.id == plan.placementID }
        assertEquals(window.copy(order = region.videos.getValue(over.id).order), region.videos[over.id])
        assertEquals(1, moved.placements.size)
        settled(moved, listOf(take))
    }

    // MARK: Tests/MoveBoundaryChecks.swift

    private val scenes = (0 until 3).map { TimelineClip(uuid(70 + it), long.id, 6.0 * it, 6.0 * (it + 1)) }
    private val base = timeline(scenes)
    private val picked = base.selection(scenes[1].id)

    /** Tests/MoveBoundaryChecks.swift:15-22. */
    @Test
    fun theStopsAreTheEdgesOfWhatStaysEachOnce() {
        val plain = TrackMove.boundaries(base, picked, picked)
        assertEquals(t(6.0), plain.anchor)
        assertEquals(listOf(0.0, 6.0, 12.0, 18.0).map(::t), plain.times)
        assertEquals(t(12.0), plain.next(t(6.0)))
        assertEquals(t(6.0), plain.previous(t(12.0)))
        assertNull(plain.next(t(18.0)))
        assertNull(plain.previous(EditTime.ZERO))
        val choices = base.moveChoices(picked)
        val left = TrackMove.boundaries(base, picked, choices.left)
        val right = TrackMove.boundaries(base, picked, choices.right)
        assertEquals(plain.anchor, left.anchor)
        assertNull(left.previous(plain.anchor))
        assertEquals(listOf(0.0, 6.0, 18.0).map(::t), right.times)
        assertEquals(TimelineMoveBoundaries(), TrackMove.boundaries(base, TimelineSelection(), picked))
        assertEquals(TimelineMoveBoundaries(), TrackMove.boundaries(base, picked, TimelineSelection()))
    }

    /** Tests/MoveBoundaryChecks.swift:23-37: "Move must preserve all picture offsets and untouched clips", "Move must preserve linked sync". */
    @Test
    fun aMoveToAnyStopKeepsEveryOffset() {
        val choices = base.moveChoices(picked)
        for (scope in listOf(choices.selected, choices.left, choices.right, choices.both)) {
            val plan = TrackMove.boundaries(base, picked, scope)
            assertTrue(plan.times.isNotEmpty())
            for (target in plan.times) {
                val draft = base.moved(scope, target - plan.anchor)
                for (item in base.video) {
                    val delta = if (item.id in scope.video) target - plan.anchor else EditTime.ZERO
                    assertEquals(item.start + delta, draft.picture(item.id).start)
                }
                for (item in base.audio) {
                    val delta = if (item.id in scope.audio) target - plan.anchor else EditTime.ZERO
                    assertEquals(item.start + delta, draft.audio.first { it.id == item.id }.start)
                }
                settled(draft, listOf(long))
            }
        }
    }

    /** Tests/MoveBoundaryChecks.swift:38-51. */
    @Test
    fun aGapOfOneTickHasTwoStopsAndASoundCutIsAStopForPicture() {
        var gaps = base.moved(base.selection(scenes[2].id), EditTime(1))
        val first = gaps.selection(scenes[0].id)
        val tickStops = TrackMove.boundaries(gaps, first, first)
        val tick = t(12.0) + EditTime(1)
        assertEquals(tick, tickStops.next(t(12.0)))
        assertEquals(t(12.0), tickStops.previous(tick))
        gaps = gaps.unlinked(gaps.selection(scenes[2].id))
        val loose = gaps.selection(gaps.audio[2].id)
        gaps = gaps.moved(loose, t(1.0))
        val audioCuts = TrackMove.boundaries(gaps, picked, picked)
        assertTrue(tick + t(1.0) in audioCuts.times)
        val anchoredAudio = TrackMove.boundaries(gaps, loose, loose)
        val audioDraft = gaps.moved(loose, EditTime.ZERO - anchoredAudio.anchor)
        assertEquals(gaps.video, audioDraft.video)
        assertEquals(EditTime.ZERO, audioDraft.audio[2].start)
    }

    /** Tests/MoveBoundaryChecks.swift:53-58: "Dissolves offer their displayed join instead of their underlying overlap handles". */
    @Test
    fun aDissolveIsAStopAtItsJoinNotAtItsHandles() {
        val blend = TimelineEditing.setJoin(EditState(scenes), 1, TransitionSettings(duration = 1.0))!!.toTimeline()
        val last = blend.selection(scenes[2].id)
        val folded = TrackMove.boundaries(blend, last, last)
        val join = TrackArrangeSupport.joins(blend).first { it.transition != null }
        assertTrue(EditTime.of(join.time) in folded.times)
        assertFalse(blend.video[1].start in folded.times)
        assertFalse(blend.video[0].end in folded.times)
        assertTrue(blend.video[1].start < EditTime.of(join.time) && EditTime.of(join.time) < blend.video[0].end)
        // With both pictures of the dissolve moving, its join is no stop.
        val pair = blend.selection(setOf(scenes[0].id, scenes[1].id))
        assertFalse(EditTime.of(join.time) in TrackMove.boundaries(blend, pair, pair).times)
    }

    /** Tests/MoveBoundaryChecks.swift:60-67. */
    @Test
    fun aMarkedRangeIsAlignedFromItsOwnStartAndSeparatePartsMoveTogether() {
        val scoped = TrackArrangeSupport.scoped(base, base.entireSelection(EditInterval.of(1.25, 3.25)))
        val partial = TrackMove.boundaries(scoped.timeline, scoped.selection, scoped.selection)
        assertEquals(t(1.25), partial.anchor)
        assertEquals(t(3.25), partial.next(t(1.25)))
        val separate = base.selection(setOf(scenes[0].id, scenes[2].id))
        val grouped = base.moved(separate, t(6.0))
        assertEquals(listOf(6.0, 6.0, 18.0).map(::t), grouped.video.map { it.start })
    }

    // MARK: Tests/TrackTimelineTests.swift:838-845, :933-938, the joins after a move

    /** "Move derives joins from actual time order", "Moved transition boundaries name the actual neighboring pictures". */
    @Test
    fun afterMovesTheJoinsFollowTheOrderInTime() {
        var reordered = original.moved(original.selection(clips[0].id), t(12.0))
        reordered = reordered.moved(reordered.selection(clips[2].id), t(-8.0))
        reordered = reordered.moved(reordered.selection(clips[0].id), t(-4.0))
        val joins = TrackArrangeSupport.joins(reordered)
        assertEquals(listOf(0.0, 4.0, 8.0, 12.0), joins.filter { it.selectable }.map { it.time })
        val boundary = TrackArrangeSupport.boundary(joins, 4.0)
        assertEquals(clips[2].id, boundary?.outgoingID)
        assertEquals(clips[1].id, boundary?.incomingID)
    }

    /** "Moved fades are anchored to their own picture", "A moved opening fade leaves the adjacent cut available". */
    @Test
    fun anOpeningMovesWithItsPictureAndLeavesTheCutBesideItFree() {
        val opened = TimelineEditing.setJoin(EditState(clips), 0, TransitionSettings(duration = 1.0))!!.toTimeline()
        assertEquals(1, opened.transitions.size)
        val moved = opened.moved(opened.selection(clips[0].id), t(12.0))
        assertEquals(1, moved.transitions.size)
        val joins = TrackArrangeSupport.joins(moved)
        val fade = joins.first { it.transition != null }
        assertEquals(12.0, fade.time, 0.0)
        assertNull(TrackArrangeSupport.boundary(joins, 0.0))
        val boundary = TrackArrangeSupport.boundary(joins, 12.0)
        assertNotNull(boundary)
        assertEquals(clips[2].id, boundary?.outgoingID)
        assertEquals(clips[0].id, boundary?.incomingID)
        settled(moved, listOf(source))
    }
}
