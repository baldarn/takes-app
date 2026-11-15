package app.takes.core

import app.takes.core.TrackEditingSupport.assertAtRest
import app.takes.core.TrackEditingSupport.assertLanesAreDisjoint
import app.takes.core.TrackEditingSupport.move
import app.takes.core.TrackEditingSupport.ticks
import app.takes.core.TrackEditingSupport.unlink
import app.takes.core.TrackFixtures.uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The editing operations of the track timeline. The checks that come from
 * the original's `Tests/TrackTimelineTests.swift` name their lines and keep
 * its numbers; the rest are this port's own.
 */
class TrackEditingTest {
    // Tests/TrackTimelineTests.swift:13-17.
    private val source = SourceMedia(id = uuid(1), filename = "source.mp4", duration = 4.0)
    private val a = TimelineClip(uuid(2), source.id, 0.0, 2.0)
    private val b = TimelineClip(uuid(3), source.id, 2.0, 4.0)
    private val baseline = EditState(listOf(a, b)).toTimeline()
    private val first = TrackEditing.selection(baseline, a.id)

    // Tests/TrackTimelineTests.swift:539-541, :809-810: three clips of four seconds from one source.
    private val long = SourceMedia(id = uuid(20), filename = "source.mp4", duration = 20.0)
    private val clips = (0 until 3).map { TimelineClip(uuid(21 + it), long.id, it * 4.0, (it + 1) * 4.0) }
    private val original = EditState(clips).toTimeline()

    private fun TrackTimeline.picture(id: String) = video.first { it.id == id }
    private fun TrackTimeline.withSpeed(index: Int, rate: Double) =
        replacingVideoContents(projectedClips.toMutableList().also { it[index] = it[index].copy(speed = SpeedSettings(rate = rate)) })

    // MARK: Selection

    /** Tests/TrackTimelineTests.swift:39: "Linked selection includes exactly its picture and sound". */
    @Test
    fun aLinkedSelectionIsThePictureAndItsSound() {
        assertEquals(setOf(a.id), first.video)
        assertEquals(setOf(TrackTimeline.counterpart(a.id)), first.audio)
        assertEquals(first, TrackEditing.selection(baseline, setOf(baseline.audio[0].id)))
        assertEquals(TimelineSelection(audio = setOf(baseline.audio[0].id)), TrackEditing.selection(baseline, baseline.audio[0].id, linked = false))
        assertEquals(EditInterval(ticks(0.0), ticks(2.0)), TrackEditing.interval(baseline, first))
        assertEquals(EditInterval(ticks(0.0), ticks(4.0)), TrackEditing.interval(baseline, TrackEditing.entire(baseline)))
        assertNull(TrackEditing.interval(baseline, TimelineSelection()))
    }

    // MARK: Split

    /** Tests/TrackTimelineTests.swift:21-26: "A selection in the first clip still allows one synchronized cut in the second". */
    @Test
    fun aSelectionElsewhereStillCutsWhatIsUnderThePlayhead() {
        val cut = TrackEditing.splitAtPlayhead(baseline, 3.0, first.ids)
        assertEquals(listOf(ticks(0.0), ticks(2.0), ticks(3.0)), cut.video.map { it.start })
        assertEquals(listOf(ticks(0.0), ticks(2.0), ticks(3.0)), cut.audio.map { it.start })
        assertEquals(baseline.video.first(), cut.video.first())
        assertEquals(baseline.duration, cut.duration)
        assertAtRest("split", cut, listOf(source))
    }

    /** Tests/TrackTimelineTests.swift:27-28: "An old selection cannot create an empty clip at the project end". */
    @Test
    fun aCutAtTheEndCutsNothing() {
        assertEquals(baseline, TrackEditing.splitAtPlayhead(baseline, 4.0, first.ids))
        assertEquals(baseline, TrackEditing.splitAtPlayhead(baseline, 0.0))
        assertEquals(baseline, baseline.split(ticks(2.0), TrackEditing.entire(baseline)).timeline)
    }

    /** Tests/TrackTimelineTests.swift:43-48: "An explicitly selected detached audio part at the playhead still splits independently". */
    @Test
    fun aSelectedUnlinkedSoundIsCutOnItsOwn() {
        val separate = unlink(baseline, first)
        assertEquals(baseline.video, separate.video)
        assertEquals(baseline.audio.map { it.mapping }, separate.audio.map { it.mapping })
        val cut = TrackEditing.splitAtPlayhead(separate, 1.0, setOf(separate.audio[0].id))
        assertEquals(separate.video, cut.video)
        assertEquals(listOf(ticks(0.0), ticks(1.0), ticks(2.0)), cut.audio.map { it.start })
    }

    /** Tests/TrackTimelineTests.swift:75-82: a split makes independent linked pairs and a bookmark on the footage is neither doubled nor lost. */
    @Test
    fun aSplitMakesLinkedPairsAndKeepsBookmarksOnTheirFootage() {
        val marked = baseline.copy(bookmarks = listOf(TimelineBookmark(uuid(9), anchor = BookmarkAnchor.Source(a.id, source.id, 1.0))))
        val (cut, children) = marked.split(ticks(1.0), TrackEditing.selection(marked, a.id))
        assertEquals(baseline.duration, cut.duration)
        assertEquals(3, cut.video.size)
        assertEquals(3, cut.audio.size)
        assertEquals(3, cut.links.size)
        assertEquals(setOf(a.id, TrackTimeline.counterpart(a.id)), children.keys)
        val right = cut.video.first { it.start == ticks(1.0) }
        assertEquals(children[a.id], right.id)
        assertEquals(a.id, right.occurrenceID)
        assertEquals(ticks(1.0), right.localOrigin)
        assertEquals(1.0, right.content.start, 0.0)
        assertEquals(1.0, cut.picture(a.id).content.end, 0.0)
        // The earlier pair keeps the link's id, and the later sound follows the later picture.
        assertEquals(setOf(a.id, TrackTimeline.counterpart(a.id)), cut.links.first { it.id == a.id }.members)
        assertEquals(right.id, cut.audio.first { it.id == children[TrackTimeline.counterpart(a.id)] }.followsVideo)
        assertEquals(listOf(ticks(1.0)), cut.resolvedBookmarks.map { it.time })
        val moved = move(cut, TrackEditing.selection(cut, right.id), ticks(4.0))
        assertEquals(ticks(5.0), moved.resolvedBookmarks[0].time)
        moved.validate(listOf(source))
    }

    /** Tests/TrackTimelineTests.swift:143-157, with the speed alone: a clip here has no pause cuts of its own. */
    @Test
    fun aSplitKeepsEveryTickThroughAnySpeed() {
        val random = TrackFixtures.Random(21)
        repeat(2000) {
            val clip = TimelineClip(uuid(40), source.id, 0.0, 4.0, speed = SpeedSettings(rate = 0.25 + random.next() * 3.75))
            // The two numbers the original draws for a pause.
            random.next(); random.next()
            val graph = EditState(listOf(clip)).toTimeline()
            val end = graph.duration
            val at = EditTime(maxOf(1L, (end.ticks.toDouble() * (0.1 + random.next() * 0.8)).toLong()))
            val cut = graph.split(at, TrackEditing.entire(graph)).timeline
            assertEquals(at, cut.video[0].end)
            assertEquals(at, cut.video[1].start)
            assertEquals(end, cut.video[1].end)
            assertEquals(at, cut.audio[0].end)
            assertEquals(end, cut.audio[1].end)
            val selected = cut.scoped(TrackEditing.entire(cut, EditInterval(at, end)))
            assertEquals(1, selected.selection.video.size)
            assertEquals(1, selected.selection.audio.size)
        }
    }

    @Test
    fun aSplitOfOnePictureOfAPlacementKeepsThePlacementOnTheSideThatStillOverlaps() {
        // Clip 1 moved over the second half of clip 0.
        val stacked = move(original, TrackEditing.selection(original, clips[1].id), ticks(-2.0))
        val region = VideoPlacementRegion(uuid(60), setOf(clips[0].id, clips[1].id), mapOf(clips[0].id to VideoPlacement(), clips[1].id to VideoPlacement(width = 0.5, order = 1)))
        val placed = stacked.copy(placements = listOf(region))
        assertEquals(placed, placed.pruned())
        // Cut clip 0 at 1 s: only its later piece still plays with clip 1.
        val (cut, children) = placed.split(ticks(1.0), TrackEditing.selection(placed, clips[0].id))
        val later = children.getValue(clips[0].id)
        assertEquals(listOf(setOf(later, clips[1].id)), cut.placements.map { it.participants })
        assertEquals(VideoPlacement(width = 0.5, order = 1), cut.placements[0].videos[clips[1].id])
        assertNotEquals(region.id, cut.placements[0].id)
        assertAtRest("placement", cut, listOf(long))
    }

    // MARK: Delete

    /** Tests/TrackTimelineTests.swift:29-32: "Ripple deletion follows surviving content, or lands at the cut when current content is removed". */
    @Test
    fun aLinkedDeletionTakesThePlayheadWithWhatStays() {
        for ((before, after) in listOf(0.5 to 0.0, 2.0 to 0.0, 3.25 to 1.25, 4.0 to 2.0)) {
            assertEquals("$before", ticks(after), baseline.removing(first, ticks(before)).position)
        }
        // :33-34: "A later deletion leaves the playhead untouched".
        assertEquals(ticks(0.75), baseline.removing(TrackEditing.selection(baseline, b.id), ticks(0.75)).position)
        assertNull(baseline.removing(first).position)
        assertEquals(TrackEditing.Removal(baseline, ticks(1.0)), baseline.removing(TimelineSelection(), ticks(1.0)))
    }

    /** Tests/TrackTimelineTests.swift:35-38: "Deleted linked footage lands at its cut even under a stationary detached overlay". */
    @Test
    fun deletedLinkedFootageLandsAtItsCutUnderAPictureThatStays() {
        val cover = baseline.video[0].let { it.copy(content = it.content.copy(id = uuid(50)), start = ticks(0.25), layer = 99) }
        val covered = baseline.copy(video = baseline.video + cover)
        val removal = covered.removing(first, ticks(1.0))
        assertEquals(EditTime.ZERO, removal.position)
        // The picture that is in no link stays where it was.
        assertEquals(ticks(0.25), removal.timeline.picture(cover.id).start)
    }

    /** Tests/TrackTimelineTests.swift:40-42: "Linked deletion closes gap in both components". */
    @Test
    fun aLinkedDeletionClosesTheGapOnBothLanes() {
        val ripple = baseline.removing(first).timeline
        assertEquals(ticks(2.0), ripple.duration)
        assertEquals(EditTime.ZERO, ripple.video[0].start)
        assertEquals(EditTime.ZERO, ripple.audio[0].start)
        assertAtRest("ripple", ripple, listOf(source))
    }

    /** Tests/TrackTimelineTests.swift:49-56: an unlinked sound or picture deleted on its own leaves a gap and moves nothing. */
    @Test
    fun anUnlinkedDeletionLeavesAGap() {
        val separate = unlink(baseline, first)
        val sound = separate.removing(TrackEditing.selection(separate, baseline.audio[0].id), ticks(3.0))
        assertEquals(ticks(3.0), sound.position)
        assertEquals(baseline.video, sound.timeline.video)
        assertEquals(listOf(baseline.audio[1]), sound.timeline.audio)
        assertEquals(ticks(4.0), sound.timeline.duration)
        val picture = separate.removing(TrackEditing.selection(separate, a.id), ticks(1.0))
        assertEquals(ticks(1.0), picture.position)
        assertEquals(separate.audio, picture.timeline.audio)
        assertEquals(listOf(baseline.video[1]), picture.timeline.video)
        assertEquals(ticks(4.0), picture.timeline.duration)
    }

    /** Tests/TrackTimelineTests.swift:66-70: "Deleting a linked clip never cuts or shifts detached audio". */
    @Test
    fun aLinkedDeletionNeverMovesSoundThatWasUnlinked() {
        val bed = unlink(baseline, TrackEditing.selection(baseline, b.id))
        val detached = bed.audio[1]
        val removed = bed.removing(first).timeline
        assertTrue(detached in removed.audio)
        // Neither does the picture that lost its link.
        assertEquals(ticks(2.0), removed.picture(b.id).start)
    }

    /** Tests/TrackTimelineTests.swift:83-88: a range is cut out of linked picture and sound and the exact interval closes. */
    @Test
    fun aRangeDeletionClosesExactlyTheRange() {
        val scope = baseline.scoped(TrackEditing.entire(baseline, EditInterval.of(0.5, 3.5)))
        assertEquals(2, scope.selection.video.size)
        assertEquals(2, scope.selection.audio.size)
        assertNull(scope.selection.range)
        val removal = scope.timeline.removing(scope.selection, ticks(3.75))
        assertEquals(ticks(0.75), removal.position)
        assertTrue(abs(removal.timeline.duration.seconds - 1) < 0.00001)
        assertEquals(listOf(0.0, 3.5), removal.timeline.video.map { it.content.start })
        assertEquals(listOf(0.5, 4.0), removal.timeline.video.map { it.content.end })
        assertAtRest("range", removal.timeline, listOf(source))
    }

    /** Tests/TrackTimelineTests.swift:126-131: "Deletion rebases by playback duration rather than source seconds". */
    @Test
    fun aDeletionCountsPlayedTimeNotSourceTime() {
        val retimed = EditState(listOf(a.copy(speed = SpeedSettings(rate = 1.5)), b)).toTimeline()
        val prior = retimed.video[1].start + ticks(0.5)
        assertEquals(ticks(0.5), retimed.removing(TrackEditing.selection(retimed, a.id), prior).position)
    }

    @Test
    fun aRangeInSecondsIsPutOnTheEdgesItIsWithinAFrameOf() {
        // YouRec/TimelineEditing.swift:66-80: 1.99 is within a frame of the cut at 2.
        val cut = TrackEditing.removing(baseline, 1.99, 3.0)
        assertEquals(ticks(3.0), cut.timeline.duration)
        assertEquals(listOf(a.id), cut.timeline.video.take(1).map { it.id })
        assertEquals(listOf(2.0 to 3.0), cut.clips.map { it.start to it.end })
        assertEquals(baseline.video[0], cut.timeline.video[0])
        // Under a frame nothing happens.
        assertSame(baseline, TrackEditing.removing(baseline, 1.0, 1.02).timeline)
    }

    // MARK: Gaps and inserted time

    /** Tests/TrackTimelineTests.swift:99-102: "Insert time preserves crossing pieces and shifts right side". */
    @Test
    fun insertedTimeCutsWhatCrossesItAndMovesTheRest() {
        val opened = baseline.insertingTime(ticks(1.0), ticks(1.0))
        assertEquals(ticks(5.0), opened.duration)
        assertEquals(3, opened.video.size)
        assertEquals(listOf(ticks(0.0), ticks(2.0), ticks(3.0)), opened.video.map { it.start })
        assertEquals(opened.video.map { it.start }, opened.audio.map { it.start })
        assertEquals(EditInterval(ticks(1.0), ticks(2.0)), TrackEditing.gap(opened, ticks(1.5)))
        opened.validate(listOf(source))
        assertSame(baseline, baseline.insertingTime(EditTime.ZERO, ticks(1.0)))
    }

    @Test
    fun timeInsertedAtAPictureGapLeavesASoundtrackWhole() {
        val music = SourceMedia(id = uuid(70), filename = "music.m4a", duration = 30.0, audioOnly = true)
        val bed = AudioTimelineItem(uuid(71), uuid(71), music.id, EditTime.ZERO, SourcePlaybackMap.single(ticks(10.0), 0.0, 10.0))
        val scored = baseline.copy(audio = baseline.audio + bed)
        val opened = scored.insertingTime(ticks(1.0), ticks(2.0), atPictureGap = true)
        assertEquals(bed, opened.audio.last())
        assertEquals(ticks(3.0), opened.picture(b.id).start)
        assertEquals(ticks(3.0), opened.audio[1].start)
        // Without it the soundtrack is cut at the place, and its later piece moves.
        val cut = scored.insertingTime(ticks(1.0), ticks(2.0))
        assertEquals(listOf(ticks(0.0), ticks(3.0)), cut.audio.filter { it.sourceID == music.id }.map { it.start })
    }

    /** Tests/TrackTimelineTests.swift:186-187: "Close gap removes only time empty on every lane". */
    @Test
    fun closingAGapRemovesOnlyTimeThatIsEmptyOnEveryLane() {
        val moved = move(baseline, TrackEditing.entire(baseline), ticks(1.0))
        assertNull(TrackEditing.gap(moved, ticks(1.0)))
        assertNull(TrackEditing.gap(moved, ticks(5.0)))
        assertEquals(EditInterval(EditTime.ZERO, ticks(1.0)), TrackEditing.gap(moved, ticks(0.5)))
        val marked = moved.copy(bookmarks = listOf(TimelineBookmark(uuid(9), anchor = BookmarkAnchor.Project(ticks(0.75))), TimelineBookmark(uuid(10), anchor = BookmarkAnchor.Project(ticks(3.0)))))
        val closed = marked.closingGap(ticks(0.5))
        assertEquals(baseline.video, closed.video)
        assertEquals(baseline.audio, closed.audio)
        assertEquals(listOf(EditTime.ZERO, ticks(2.0)), closed.resolvedBookmarks.map { it.time })
        // Sound alone under a picture gap is not empty time.
        val separate = unlink(baseline, first)
        val hole = separate.removing(TimelineSelection(video = setOf(a.id))).timeline
        assertNull(TrackEditing.gap(hole, ticks(1.0)))
        assertSame(hole, hole.closingGap(ticks(1.0)))
    }

    // MARK: Insert

    @Test
    fun insertedClipsAreNewFootageWithSoundAndRippleFromThePlayhead() {
        val take = TimelineClip(uuid(80), source.id, 1.0, 2.5, speed = SpeedSettings(uuid(81), 1.5), texts = listOf(TextOverlay.fresh()))
        val result = TrackEditing.inserting(baseline, listOf(take), 1.0)
        // Tests/TrackTimelineTests.swift has no check of its own; YouRec/TimelineEditing.swift:86-127.
        assertEquals(ticks(5.0), result.duration)
        // The first clip is cut at the playhead and the new second of footage goes between its pieces.
        assertEquals(listOf(ticks(0.0), ticks(1.0), ticks(2.0), ticks(3.0)), result.video.map { it.start })
        val added = result.video[1]
        assertNotEquals(take.id, added.id)
        assertNotEquals(uuid(81), added.content.speed!!.id)
        assertNotEquals(take.texts!![0].id, added.content.texts!![0].id)
        assertEquals(ticks(1.0), added.mapping.duration)
        assertEquals((baseline.video.maxOf { it.layer }) + 1, added.layer)
        val sound = result.audio.first { it.followsVideo == added.id }
        assertEquals(added.start, sound.start)
        assertTrue(result.links.any { it.members == setOf(added.id, sound.id) })
        assertAtRest("insert", result, listOf(source))
        assertSame(baseline, TrackEditing.inserting(baseline, emptyList(), 1.0))
    }

    @Test
    fun clipsInsertedAtASiteUseFreePictureTimeFirst() {
        // A picture gap of one second after the first clip, with nothing else moved.
        val gapped = move(baseline, TrackEditing.selection(baseline, b.id), ticks(1.0))
        val short = TimelineClip(uuid(80), source.id, 0.0, 0.5)
        val fitted = TrackEditing.inserting(gapped, listOf(short), 0.0, VideoInsertionSite.Gap(2.0))
        assertEquals(gapped.duration, fitted.duration)
        assertEquals(listOf(ticks(0.0), ticks(2.0), ticks(3.0)), fitted.video.map { it.start })
        val longer = TimelineClip(uuid(82), source.id, 0.0, 1.5)
        val pushed = TrackEditing.inserting(gapped, listOf(longer), 0.0, VideoInsertionSite.Gap(2.0))
        assertEquals(listOf(ticks(0.0), ticks(2.0), ticks(3.5)), pushed.video.map { it.start })
        assertEquals(ticks(5.5), pushed.duration)
        val end = TrackEditing.inserting(gapped, listOf(short), 0.0, VideoInsertionSite.End)
        assertEquals(ticks(5.0), end.video.last().start)
        val silent = TrackEditing.inserting(gapped, listOf(short), 0.0, VideoInsertionSite.End, pictureOnlySources = setOf(source.id))
        assertEquals(gapped.audio, silent.audio)
        assertEquals(gapped.links, silent.links)
        assertAtRest("site", pushed, listOf(source))
    }

    // MARK: Extend and trim

    /** Tests/TrackTimelineTests.swift:177-181: "Outward trim restores source samples and linked sound". */
    @Test
    fun extendingRevealsTheSourceAndTheLinkedSoundFollows() {
        var reveal = EditState(listOf(TimelineClip(uuid(90), source.id, 1.0, 3.0))).toTimeline()
        val id = reveal.video[0].id
        reveal = move(reveal, TrackEditing.entire(reveal), ticks(2.0))
        reveal = reveal.extending(TrackEditing.selection(reveal, id), left = true, by = ticks(1.0), sources = listOf(source))
        assertEquals(ticks(1.0), reveal.video[0].start)
        assertEquals(0.0, reveal.video[0].content.start, 0.0)
        assertEquals(reveal.video[0].mapping, reveal.audio[0].mapping)
        assertEquals(ticks(3.0), reveal.video[0].mapping.duration)
        assertAtRest("reveal", reveal, listOf(source))
        // No more than the source has, and no further left than zero.
        val all = reveal.extending(TrackEditing.selection(reveal, id), left = false, by = ticks(30.0), sources = listOf(source))
        assertEquals(4.0, all.video[0].content.end, 0.0)
        assertEquals(ticks(5.0), all.video[0].end)
        assertSame(all, all.extending(TrackEditing.selection(all, id), left = false, by = ticks(1.0), sources = listOf(source)))
        assertSame(all, all.extending(TrackEditing.selection(all, id), left = true, by = ticks(1.0), sources = listOf(source)))
    }

    /**
     * Tests/TrackTimelineTests.swift:956-968, without the rate effect that gives the original's clip two runs: "Extend
     * keeps the enhancement span exact", 0.5…5.1 after a reveal on the right and 1.5…6.1 after one on the left.
     */
    @Test
    fun extendingKeepsAChainOnTheSameSound() {
        var enhanced = EditState(listOf(TimelineClip(uuid(90), long.id, 2.0, 8.0))).toTimeline()
        enhanced = move(enhanced, TrackEditing.entire(enhanced), ticks(2.0))
        val region = AudioEnhancementRegion(uuid(91), EditInterval.of(0.5, 5.1), listOf(AudioEffect.make(AudioEffectKind.HighPass, uuid(92))))
        enhanced = enhanced.copy(audio = listOf(enhanced.audio[0].copy(enhancements = listOf(region))))
        for (left in listOf(false, true)) {
            val extended = enhanced.extending(TrackEditing.entire(enhanced), left, ticks(1.0), listOf(long))
            extended.validate(listOf(long))
            assertEquals(listOf(region.id), extended.audio[0].enhancements.map { it.id })
            assertEquals(if (left) EditInterval.of(1.5, 6.1) else EditInterval.of(0.5, 5.1), extended.audio[0].enhancements[0].interval)
        }
        // The same for a sound that follows no picture: its own run grows, and on the left its regions move with the sound.
        val separate = unlink(enhanced, TrackEditing.entire(enhanced))
        val sound = TimelineSelection(audio = setOf(separate.audio[0].id))
        val left = separate.extending(sound, true, ticks(1.0), listOf(long))
        assertEquals(EditInterval.of(1.5, 6.1), left.audio[0].enhancements[0].interval)
        assertEquals(ticks(1.0), left.audio[0].start)
        assertEquals(1.0, left.audio[0].mapping.runs.first().sourceStart, 0.0)
        assertEquals(separate.video, left.video)
        left.validate(listOf(long))
    }

    /** Tests/TrackTimelineTests.swift:657-666: "Extending into a new overlap reveals the extended footage in front". */
    @Test
    fun extendingIntoANewOverlapComesInFront() {
        val lower = clips[0].id
        val cut = original.split(ticks(2.0), TrackEditing.selection(original, lower)).timeline
        val child = cut.video.first { it.start == ticks(2.0) }
        val reveal = cut.extending(TrackEditing.selection(cut, lower), left = false, by = ticks(1.0), sources = listOf(long))
        val overlap = reveal.compile().first { lower in it.video && child.id in it.video }
        assertEquals(lower, overlap.video.last())
        val lanes = reveal.lanes()
        assertTrue(lanes.first { it.id == lower }.row < lanes.first { it.id == child.id }.row)
        assertLanesAreDisjoint(reveal, listOf(long))
    }

    @Test
    fun aHandleTrimInwardsDeletesThatPartAndOutwardsReveals() {
        // YouRec/EditorView.swift:1241-1258.
        val inward = TrackEditing.trimming(baseline, a.id, left = false, delta = -0.5, sources = listOf(source), preserving = ticks(3.0))!!
        assertEquals(ticks(3.5), inward.timeline.duration)
        assertEquals(1.5, inward.timeline.picture(a.id).content.end, 0.0)
        assertEquals(ticks(1.5), inward.timeline.picture(b.id).start)
        assertEquals(ticks(2.5), inward.position)
        // At least one frame is left.
        val most = TrackEditing.trimming(baseline, a.id, left = true, delta = 30.0, sources = listOf(source))!!
        assertEquals(EditTime.of(TrackEditing.FRAME), most.timeline.picture(most.timeline.video[0].id).mapping.duration)
        // Outwards: the trimmed half second comes back over the next clip, which does not move.
        val outward = TrackEditing.trimming(inward.timeline, a.id, left = false, delta = 0.5, sources = listOf(source))!!
        assertEquals(2.0, outward.timeline.picture(a.id).content.end, 0.0)
        assertEquals(ticks(1.5), outward.timeline.picture(b.id).start)
        assertNull(TrackEditing.trimming(baseline, a.id, left = false, delta = 0.0, sources = listOf(source)))
        assertNull(TrackEditing.trimming(baseline, uuid(999), left = false, delta = -0.5, sources = listOf(source)))
    }

    @Test
    fun sourceTimeTakenOutOfAPictureLeavesPiecesAndClosesTheGaps() {
        val cut = TrackEditing.removingSource(original, clips[1].id, listOf(SourceInterval(4.5, 5.0), SourceInterval(6.0, 7.0)))!!
        val pieces = cut.video.filter { it.occurrenceID == clips[1].id }
        assertEquals(listOf(4.0 to 4.5, 5.0 to 6.0, 7.0 to 8.0), pieces.map { it.content.start to it.content.end })
        assertEquals(listOf(ticks(4.0), ticks(4.5), ticks(5.5)), pieces.map { it.start })
        assertEquals(ticks(6.5), cut.picture(clips[2].id).start)
        assertEquals(ticks(10.5), cut.duration)
        assertEquals(cut.video.map { it.start }, cut.audio.map { it.start })
        assertAtRest("pauses", cut, listOf(long))
        assertNull(TrackEditing.removingSource(original, clips[1].id, listOf(SourceInterval(0.0, 1.0))))
        assertNull(TrackEditing.removingSource(original, uuid(999), listOf(SourceInterval(4.5, 5.0))))
    }

    // MARK: Recipes

    @Test
    fun projectedClipsCarryTheirTransitionsAndMergingThemBackChangesNothing() {
        val dissolve = TransitionSettings(id = uuid(100), nextClipID = clips[1].id, duration = 1.0)
        val opening = TransitionSettings(id = uuid(101), style = TransitionStyle.FadeColor, duration = 0.5)
        val timeline = EditState(TimelineEditing.fitted(listOf(clips[0].copy(openingTransition = opening, transition = dissolve), clips[1], clips[2]))).toTimeline()
        val projected = timeline.projectedClips
        assertEquals(dissolve, projected[0].transition)
        assertEquals(opening, projected[0].openingTransition)
        assertEquals(TransitionFit(1.0), projected[0].transitionFit)
        assertNull(projected[1].transition)
        assertSame(timeline, timeline.replacingVideoContents(projected))
        // A recipe edit that leaves timing alone moves nothing; the order of the clips never moves a transition.
        val colored = timeline.replacingVideoContents(projected.map { it.copy(color = ColorSettings(id = uuid(102), amount = 0.5)) }.reversed())
        assertEquals(timeline.transitions, colored.transitions)
        assertEquals(timeline.audio, colored.audio)
        assertEquals(timeline.video.map { it.start }.sorted(), colored.video.map { it.start }.sorted())
        assertTrue(colored.video.all { it.content.color?.amount == 0.5 })
        assertEquals(clips.reversed().map { it.id }, colored.video.map { it.id })
        // Only a removal made on the clip removes its transition.
        val removed = timeline.replacingVideoContents(projected.map { if (it.id == clips[0].id) it.copy(transition = null, transitionFit = null) else it })
        assertEquals(listOf(opening.id), removed.transitions.map { it.id })
        assertAtRest("removed", removed, listOf(long))
    }

    /** Tests/TrackTimelineTests.swift:811-817: speed leaves a picture playing over it where it is and ripples the linked sequence. */
    @Test
    fun speedLeavesAConcurrentLayerInPlaceAndRipplesTheSequence() {
        val overlapped = move(original, TrackEditing.selection(original, clips[1].id), ticks(-3.0))
        val fast = overlapped.withSpeed(0, 4.0)
        fast.validate(listOf(long))
        assertEquals(ticks(1.0), fast.picture(clips[1].id).start)
        assertEquals(ticks(5.0), fast.picture(clips[2].id).start)
        assertEquals(ticks(1.0), fast.picture(clips[0].id).mapping.duration)
        assertEquals(fast.picture(clips[0].id).mapping, fast.audio.first { it.followsVideo == clips[0].id }.mapping)
        assertEquals(fast, TrackEditing.settingSpeed(overlapped, clips[0].id, fast.picture(clips[0].id).content.speed))
        assertAtRest("fast", fast, listOf(long))
    }

    /** Tests/TrackTimelineTests.swift:908-919: "Retiming an overlay never ripples the main sequence". */
    @Test
    fun retimingAnOverlayNeverRipplesTheSequenceUnderIt() {
        val overlay = TimelineClip(uuid(110), long.id, 12.0, 14.0)
        var tracks = EditState(listOf(overlay)).toTimeline()
        tracks = move(tracks, TrackEditing.entire(tracks), ticks(1.0))
        tracks = tracks.copy(video = tracks.video.map { it.copy(layer = 9) })
        val composite = original.copy(video = original.video + tracks.video, audio = original.audio + tracks.audio, links = original.links + tracks.links)
        for (rate in listOf(0.5, 2.0)) {
            val index = composite.projectedClips.indexOfFirst { it.id == overlay.id }
            val edit = composite.withSpeed(index, rate)
            edit.validate(listOf(long))
            for (clip in clips) assertEquals("$rate", original.picture(clip.id).start, edit.picture(clip.id).start)
            assertEquals(ticks(2.0 / rate), edit.picture(overlay.id).mapping.duration)
        }
        // :920-922: "An overlay ending on a cut cannot steal its outgoing participant".
        val ending = composite.copy(video = composite.video.map { if (it.id == overlay.id) it.copy(start = ticks(2.0)) else it }).synchronizingFollowers()
        assertEquals(ticks(2.0), ending.audio.first { it.followsVideo == overlay.id }.start)
        assertEquals(clips[0].id, TrackLayout.of(ending).boundary(4.0)?.outgoingID)
    }

    /** Tests/TrackTimelineTests.swift:650-656: "Slowing detached picture reveals its extended footage instead of hiding it". */
    @Test
    fun slowingAnUnlinkedPictureMovesNothingAndComesInFront() {
        val slowed = unlink(original, TrackEditing.entire(original)).withSpeed(0, 0.5)
        assertEquals(original.video.map { it.start }, slowed.video.map { it.start })
        assertEquals(original.audio, slowed.audio.map { it.copy(followsVideo = it.id.let { id -> original.audio.first { o -> o.id == id }.followsVideo }) })
        assertEquals(clips[0].id, slowed.compile().first { clips[0].id in it.video && clips[1].id in it.video }.video.last())
        assertLanesAreDisjoint(slowed, listOf(long))
    }

    @Test
    fun aChangeOfSpeedCarriesFadesChainsAndCensorsOnTheSameSound() {
        val settings = AudioCensorSettings()
        val sound = original.audio[0].copy(
            fadeIn = AudioEdgeFade(3.0),
            enhancements = listOf(AudioEnhancementRegion(uuid(120), EditInterval.of(1.0, 3.0), listOf(AudioEffect.make(AudioEffectKind.Gain, uuid(121))))),
            censorRegions = listOf(AudioCensorRegion(uuid(122), EditInterval.of(2.0, 3.0), ticks(1.5), settings, EditInterval.of(1.5, 3.5))),
        )
        val timeline = original.copy(audio = listOf(sound) + original.audio.drop(1))
        val fast = timeline.withSpeed(0, 2.0).audio[0]
        // YouRec/TrackTimeline.swift:1282-1333: the fade is squeezed to the shorter item, the regions follow their source.
        assertEquals(AudioEdgeFade(2.0), fast.fadeIn)
        assertEquals(listOf(EditInterval.of(0.5, 1.5)), fast.enhancements.map { it.interval })
        assertEquals(listOf(AudioCensorRegion(uuid(122), EditInterval.of(1.0, 1.5), ticks(1.0), settings, null)), fast.censors)
        // Slower: nothing is stretched but the regions.
        val slow = timeline.withSpeed(0, 0.5).audio[0]
        assertEquals(AudioEdgeFade(3.0), slow.fadeIn)
        assertEquals(listOf(EditInterval.of(2.0, 6.0)), slow.enhancements.map { it.interval })
        timeline.withSpeed(0, 0.5).validate(listOf(long))
    }

    @Test
    fun aClipThatIsGoneIsDeletedAndANewOneIsPutAfterTheOneBeforeIt() {
        val projected = original.projectedClips
        val without = original.replacingVideoContents(listOf(projected[0], projected[2]))
        assertEquals(listOf(clips[0].id, clips[2].id), without.video.map { it.id })
        assertEquals(ticks(8.0), without.duration)
        assertEquals(2, without.audio.size)
        val extra = TimelineClip(uuid(130), long.id, 12.0, 13.0)
        val with = original.replacingVideoContents(listOf(projected[0], extra, projected[1], projected[2]))
        assertEquals(listOf(ticks(0.0), ticks(4.0), ticks(5.0), ticks(9.0)), with.video.map { it.start })
        assertEquals(extra.id, with.video[1].id)
        assertEquals(4, with.audio.size)
        assertAtRest("with", with, listOf(long))
    }

    // MARK: A marked scope found again

    /** Tests/TrackTimelineTests.swift:853-871. */
    @Test
    fun aMarkedRangeFollowsItsFootageThroughSpeedToolsAndSplits() {
        val whole = EditState(listOf(TimelineClip(uuid(140), long.id, 0.0, 20.0))).toTimeline()
        val selected = TrackEditing.entire(whole, EditInterval.of(2.0, 8.0))
        val scope = whole.scoped(selected)
        val index = scope.timeline.projectedClips.indexOfFirst { it.id in scope.selection.video }
        val retimed = scope.timeline.withSpeed(index, 2.0)
        // "Marked ranges follow their source content through a speed change".
        val sped = TrackEditing.remapping(retimed, selected, whole)
        assertEquals(EditInterval.of(2.0, 5.0), sped.range)
        // "Delete after Speed removes the intended source range only".
        assertEquals(ticks(14.0), retimed.scoped(sped).let { it.timeline.removing(it.selection).timeline }.duration)
        // "Delete after a range tool removes the entire retained range".
        val retained = TrackEditing.remapping(scope.timeline, selected, whole)
        assertEquals(EditInterval.of(2.0, 8.0), retained.range)
        assertEquals(ticks(14.0), scope.timeline.scoped(retained).let { it.timeline.removing(it.selection).timeline }.duration)
        // "Delete after Split follows both children".
        val split = whole.split(ticks(5.0), selected).timeline
        val both = TrackEditing.remapping(split, selected, whole)
        assertEquals(2, both.video.size)
        assertEquals(ticks(14.0), split.scoped(both).let { it.timeline.removing(it.selection).timeline }.duration)
        // A second use of the same source is not a descendant.
        val again = TrackEditing.inserting(whole, listOf(TimelineClip(uuid(141), long.id, 3.0, 4.0)), 20.0)
        assertEquals(selected.video, TrackEditing.remapping(again, selected, whole).video)
    }

    // MARK: Layout

    /** Tests/TrackTimelineTests.swift:836-842: "Move derives joins from actual time order". */
    @Test
    fun theLayoutReadsJoinsFromTheOrderPicturesPlayIn() {
        var reordered = move(original, TrackEditing.selection(original, clips[0].id), ticks(12.0))
        reordered = move(reordered, TrackEditing.selection(reordered, clips[2].id), ticks(-8.0))
        reordered = move(reordered, TrackEditing.selection(reordered, clips[0].id), ticks(-4.0))
        val layout = TrackLayout.of(reordered)
        assertEquals(listOf(0.0, 4.0, 8.0, 12.0), layout.joins.filter { it.selectable }.map { it.time })
        assertEquals(clips[2].id, layout.boundary(4.0)?.outgoingID)
        assertEquals(clips[1].id, layout.boundary(4.0)?.incomingID)
        assertEquals(listOf(clips[2].id, clips[1].id, clips[0].id), layout.placements.map { it.clip.id })
        assertEquals(listOf(-1, 0, 0, 1), layout.joins.map { it.edge })
        assertEquals(12.0, layout.duration, 0.0)
        assertEquals(1, layout.placement(5.0)?.index)
        assertEquals(2, layout.placement(12.0)?.index)
    }

    @Test
    fun aPlaceBetweenPicturesThatDoNotMeetIsNoJoin() {
        val gapped = move(original, TrackEditing.selection(original, clips[2].id), ticks(1.0))
        val layout = TrackLayout.of(gapped)
        assertEquals(listOf(true, true, false, true), layout.joins.map { it.selectable })
        assertNull(layout.boundary(8.0))
        val late = move(original, TrackEditing.entire(original), ticks(1.0))
        assertFalse(TrackLayout.of(late).joins[0].selectable)
        val empty = TrackLayout.of(TrackTimeline())
        assertTrue(empty.joins.isEmpty() && empty.placements.isEmpty())
        // The place before a picture that meets nothing is where the picture before it ends.
        val (entering, leaving) = layout.joins(layout.placements[2])
        assertEquals(8.0, entering.time, 0.0)
        assertNull(entering.transition)
        assertEquals(13.0, leaving.time, 0.0)
        // A picture over another leads into nothing: its end is a cut of no length.
        val cover = original.video[0].let { it.copy(content = it.content.copy(id = uuid(150), end = 2.0), start = ticks(1.0), layer = 9, mapping = it.mapping.sliced(EditInterval.of(0.0, 2.0))) }
        val covered = TrackLayout.of(original.copy(video = original.video + cover))
        val end = covered.joins(covered.placements.first { it.clip.id == cover.id }).second
        assertEquals(3.0, end.time, 0.0)
        assertEquals(3.0..3.0, end.range)
        assertEquals(clips[0].id, covered.boundary(4.0)?.outgoingID)
    }

    @Test
    fun theLayoutOfAConvertedSequenceIsTheSequenceLayout() {
        for ((name, state) in TrackFixtures.all) {
            val timeline = state.toTimeline()
            val layout = TrackLayout.of(timeline)
            val sequence = TimelineEditing.layout(state.clips)
            assertEquals(name, state.clips.size, layout.placements.size)
            for ((placement, expected) in layout.placements.zip(sequence.placements)) {
                assertEquals(name, expected.start, placement.start, 2 * TransitionLayout.TICK)
            }
            // Every transition in play is a join, with the fit that was saved.
            val inPlay = sequence.all.filter { it.transition != null }
            assertEquals(name, inPlay.map { it.transition!!.id }.toSet(), layout.joins.mapNotNull { it.transition?.id }.toSet())
            for (join in inPlay) {
                val found = layout.joins.first { it.transition?.id == join.transition!!.id }
                assertEquals(name, join.factor, found.factor, 0.0)
                assertEquals(name, join.origin, found.origin, 2 * TransitionLayout.TICK)
                assertEquals(name, join.edge, found.edge)
            }
        }
    }

    // MARK: Generated edits

    /** "Persistent Undo with generated edit sequences" (the original's design notes): no edit leaves a timeline a project must refuse, or one not at rest. */
    @Test
    fun generatedEditSequencesStayValidAndAtRest() {
        val random = TrackFixtures.Random(5)
        val sources = TrackFixtures.sources
        repeat(120) { round ->
            var timeline = TrackFixtures.plain().toTimeline()
            repeat(30) { step ->
                val before = timeline
                val time = ticks(random.next(0.0, maxOf(1.0, timeline.duration.seconds)))
                fun item(): String? = timeline.allIDs.toList().let { if (it.isEmpty()) null else it[random.below(it.size)] }
                fun picture(): String? = timeline.video.let { if (it.isEmpty()) null else it[random.below(it.size)].id }
                val operation = random.below(13)
                timeline = when (operation) {
                    0 -> timeline.split(time, TrackEditing.entire(timeline)).timeline
                    1 -> item()?.let { timeline.split(time, TrackEditing.selection(timeline, it)).timeline } ?: timeline
                    2 -> item()?.let { timeline.removing(TrackEditing.selection(timeline, it), time).timeline } ?: timeline
                    3 -> item()?.let { timeline.removing(TrackEditing.selection(timeline, it, linked = false)).timeline } ?: timeline
                    4 -> timeline.insertingTime(ticks(random.next(0.0, 2.0)), time, atPictureGap = random.next() < 0.3)
                    5 -> timeline.closingGap(time)
                    6 -> item()?.let { timeline.extending(TrackEditing.selection(timeline, it), random.next() < 0.5, ticks(random.next(0.0, 3.0)), sources) } ?: timeline
                    7 -> picture()?.let { TrackEditing.settingSpeed(timeline, it, SpeedSettings(rate = random.next(0.25, 4.0))) } ?: timeline
                    8 -> time.seconds.let { TrackEditing.removing(timeline, it, it + random.next(0.0, 3.0)).timeline }
                    9 -> item()?.let { TrackEditing.trimming(timeline, it, random.next() < 0.5, random.next(-2.0, 2.0), sources)?.timeline } ?: timeline
                    10 -> TrackEditing.inserting(
                        timeline, listOf(TimelineClip(sourceID = TrackFixtures.second.id, start = 1.0, end = 1.0 + random.next(0.2, 3.0))), time.seconds,
                        listOf(null, VideoInsertionSite.Start, VideoInsertionSite.End, VideoInsertionSite.Gap(time.seconds))[random.below(4)],
                    )
                    11 -> item()?.let { unlink(timeline, TrackEditing.selection(timeline, it)) } ?: timeline
                    else -> item()?.let { move(timeline, TrackEditing.selection(timeline, it), ticks(random.next(-5.0, 5.0))) } ?: timeline
                }
                assertAtRest("round $round, step $step, operation $operation", timeline, sources)
                // Nothing that was there is ever replaced by something else under its id.
                if (operation in listOf(4, 5, 11)) assertEquals(before.allIDs, timeline.allIDs.intersect(before.allIDs))
            }
        }
    }
}
