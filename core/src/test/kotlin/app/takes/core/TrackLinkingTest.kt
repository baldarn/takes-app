package app.takes.core

import app.takes.core.TrackFixtures.uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Selection, Unlink, Link, Restore sync and Close gap, with the checks of
 * the original's `Tests/TrackTimelineTests.swift` that concern them and its
 * numbers. Each names the lines it comes from. Where the original reaches a
 * state through an operation that is not in these files (a move, an outward
 * trim, setting a transition), the same state is written out or converted
 * from the sequence form, and the check says so.
 */
class TrackLinkingTest {
    private fun t(seconds: Double) = EditTime.of(seconds)

    // Tests/TrackTimelineTests.swift:386-389.
    private val source = SourceMedia(id = uuid(1), filename = "multi.mp4", duration = 12.0)
    private val sources = listOf(source)
    private val clips = (0 until 3).map { TimelineClip(uuid(10 + it), source.id, it * 4.0, (it + 1) * 4.0) }
    private val original = EditState(clips).toTimeline()
    private val all = original.entireSelection()
    private val detached = original.unlinked(all)
    private val videos = TimelineSelection(video = all.video)
    private val audios = TimelineSelection(audio = all.audio)

    private fun settled(timeline: TrackTimeline) {
        assertNull(TrackInvariants.violation(timeline, sources))
        assertEquals(timeline, timeline.pruned())
    }

    private fun shifted(timeline: TrackTimeline, ids: Set<String>, seconds: Double) = timeline.copy(
        video = timeline.video.map { if (it.id in ids) it.copy(start = it.start + t(seconds)) else it },
        audio = timeline.audio.map { if (it.id in ids) it.copy(start = it.start + t(seconds)) else it },
    )

    private fun <T> done(outcome: TimelineLinkOutcome<T>): T = outcome.valueOrNull ?: error("Refused: ${outcome.errorOrNull}")

    private fun rejected(timeline: TrackTimeline, scope: TimelineSelection, expected: TimelineLinkError) {
        assertEquals(expected, timeline.linkPlan(scope).errorOrNull)
        assertEquals(expected, timeline.joining(scope).errorOrNull)
    }

    // MARK: Selection

    /** Tests/TrackTimelineTests.swift:21, :39: "Linked selection includes exactly its picture and sound". */
    @Test
    fun aLinkedSelectionIsExactlyItsPictureAndItsSound() {
        val first = original.selection(clips[0].id)
        assertEquals(setOf(clips[0].id), first.video)
        assertEquals(setOf(TrackTimeline.counterpart(clips[0].id)), first.audio)
        assertEquals(first, original.selection(original.audio[0].id))
        assertEquals(TimelineSelection(video = setOf(clips[0].id)), original.selection(clips[0].id, linked = false))
        assertEquals(TimelineSelection(), original.selection(uuid(99)))
        assertTrue(TrackLinking.isSingleLink(original, first))
        assertFalse(TrackLinking.isSingleLink(original, all))
        assertEquals("Video + audio", first.title)
        assertEquals("1 video + 1 audio part", first.countTitle)
        assertEquals("2 audio parts", TimelineSelection(audio = setOf("a", "b")).countTitle)
        assertEquals("Video", videos.title)
        assertEquals("Audio", audios.title)
    }

    /** Tests/TrackTimelineTests.swift:390-394: "Long press adds another linked pair", "Long press removes the whole selected link, keeping other selections". */
    @Test
    fun aLongPressAddsOrRemovesAWholeLink() {
        var chosen = original.selection(clips[0].id).ids
        chosen = TrackLinking.toggling(original, clips[1].id, chosen)
        assertEquals(4, chosen.size)
        chosen = TrackLinking.toggling(original, original.audio[0].id, chosen)
        assertEquals(original.selection(clips[1].id).ids, chosen)
    }

    @Test
    fun theStretchOfASelectionIsItsRangeOrFromItsFirstStartToItsLastEnd() {
        assertEquals(EditInterval.of(0.0, 12.0), original.interval(all))
        assertEquals(EditInterval.of(4.0, 8.0), original.interval(original.selection(clips[1].id)))
        assertEquals(EditInterval.of(0.0, 12.0), original.interval(original.selection(setOf(clips[0].id, clips[2].id))))
        assertEquals(EditInterval.of(5.0, 7.0), original.interval(original.selection(clips[1].id, range = EditInterval.of(5.0, 7.0))))
        assertNull(original.interval(TimelineSelection()))
        assertEquals(all, TrackLinking.items(original, setOf(source.id)))
        assertEquals(TimelineSelection(), TrackLinking.items(original, setOf(uuid(99))))
    }

    // MARK: Unlink

    /** Tests/TrackTimelineTests.swift:43-48: "Unlink changes no timing, samples or settings". */
    @Test
    fun unlinkChangesNoTimingSampleOrSetting() {
        val first = original.selection(clips[0].id)
        val separate = original.unlinked(first)
        assertEquals(original.video, separate.video)
        assertEquals(original.audio.map { it.mapping }, separate.audio.map { it.mapping })
        assertEquals(original.audio.map { it.start }, separate.audio.map { it.start })
        assertEquals(original.audio.map { it.copy(followsVideo = null) }.take(1) + original.audio.drop(1), separate.audio)
        assertEquals(original.links.drop(1), separate.links)
        assertEquals(TimelineSelection(video = setOf(clips[0].id)), separate.selection(clips[0].id))
        settled(separate)
        assertSame(separate, separate.unlinked(first))
    }

    // MARK: Link

    /** Tests/TrackTimelineTests.swift:395-401: "Join horizontal chains before vertical linking", "Join preserves every sample map, source identity, setting and follower", "Multi-part link persists exactly". */
    @Test
    fun linkingEverythingMakesOneLinkAndChangesNoItem() {
        val together = done(original.joining(all))
        assertEquals(1, together.timeline.links.size)
        assertEquals(all.ids, together.timeline.links[0].members)
        assertEquals(original.video, together.timeline.video)
        assertEquals(original.audio, together.timeline.audio)
        assertEquals(all, together.selection)
        settled(together.timeline)
        assertEquals(together.timeline, TrackTimeline.decoded(together.timeline.encoded()))
    }

    /** Tests/TrackTimelineTests.swift:407-415: pictures alone and sounds alone link side by side, and deleting such a group moves nothing else. */
    @Test
    fun picturesOrSoundsAloneLinkSideBySide() {
        val v = done(detached.joining(videos))
        val a = done(detached.joining(audios))
        assertEquals(all.video, v.timeline.selection(clips[1].id).ids)
        assertEquals(all.audio, a.timeline.selection(detached.audio[1].id).ids)
        settled(v.timeline)
        settled(a.timeline)
        val removeV = TrackArrangeSupport.remove(v.timeline, v.selection).timeline
        assertTrue(removeV.video.isEmpty())
        assertEquals(detached.audio, removeV.audio)
        assertEquals(t(12.0), removeV.duration)
        val removeA = TrackArrangeSupport.remove(a.timeline, a.selection).timeline
        assertTrue(removeA.audio.isEmpty())
        assertEquals(detached.video, removeA.video)
    }

    /** Tests/TrackTimelineTests.swift:416-419: "Bulk linked deletion closes both vacated ranges exactly once". */
    @Test
    fun deletingTwoLinkedPairsClosesBothStretchesOnce() {
        val bulk = TrackArrangeSupport.remove(original, original.selection(setOf(clips[0].id, clips[2].id))).timeline
        assertEquals(1, bulk.video.size)
        assertEquals(1, bulk.audio.size)
        assertEquals(t(4.0), bulk.duration)
        assertEquals(EditTime.ZERO, bulk.video[0].start)
        settled(bulk)
    }

    /** Tests/TrackTimelineTests.swift:424-431: "AV ripple never shifts a detached horizontal audio group". */
    @Test
    fun deletingALinkedPairNeverMovesSoundsLinkedOnlyToEachOther() {
        var bed = original.unlinked(original.selection(clips[1].id))
        bed = bed.unlinked(bed.selection(clips[2].id))
        bed = done(bed.joining(TimelineSelection(audio = bed.audio.drop(1).mapTo(LinkedHashSet()) { it.id }))).timeline
        val sounds = bed.audio.drop(1)
        val pair = TimelineSelection(video = setOf(clips[0].id), audio = setOf(original.audio[0].id))
        val removed = TrackArrangeSupport.remove(bed, pair).timeline
        assertEquals(sounds, removed.audio)
        settled(removed)
    }

    /** Tests/TrackTimelineTests.swift:432-439: "Splitting unrelated audio preserves a horizontal video group", "Splitting a joined sequence separates the two sides, with AV sync preserved". */
    @Test
    fun aCutKeepsLinksOfOtherLanesAndDividesTheLinkItCuts() {
        val horizontal = done(detached.joining(videos)).timeline
        val unrelated = TrackArrangeSupport.split(horizontal, t(6.0), TimelineSelection(audio = setOf(horizontal.audio[1].id))).timeline
        assertEquals(horizontal.links, unrelated.links)
        assertEquals(4, unrelated.audio.size)

        val together = done(original.joining(all))
        val divided = TrackArrangeSupport.split(together.timeline, t(6.0), together.selection).timeline
        assertEquals(2, divided.links.size)
        assertTrue(divided.links.all { it.members.size == 4 })
        settled(divided)
    }

    /** Tests/TrackTimelineTests.swift:440-450: each refusal names the problem with the selection. */
    @Test
    fun aSelectionThatCannotBeLinkedIsRefusedWithItsReason() {
        rejected(detached, TimelineSelection(), TimelineLinkError.TooFew)
        rejected(detached, TimelineSelection(video = setOf(clips[0].id)), TimelineLinkError.TooFew)
        rejected(detached, TimelineSelection(video = setOf(clips[0].id, clips[2].id)), TimelineLinkError.NonContiguous)
        // The original moves the middle picture a second earlier (:447-449).
        val overlap = shifted(detached, setOf(clips[1].id), -1.0)
        rejected(overlap, videos, TimelineLinkError.NonContiguous)
        rejected(detached, TimelineSelection(video = setOf(clips[0].id), audio = setOf(detached.audio[2].id)), TimelineLinkError.NoIntersection)
        // A range is not a selection of whole items.
        rejected(original, original.entireSelection(EditInterval.of(1.0, 2.0)), TimelineLinkError.TooFew)
        // A gap of one tick is a gap.
        val tick = detached.copy(video = detached.video.map { if (it.id == clips[2].id) it.copy(start = it.start + EditTime(1)) else it })
        rejected(tick, videos, TimelineLinkError.NonContiguous)
        assertEquals(
            "Video and audio cover different times. Link only their intersection?",
            TimelineLinkError.ConfirmationRequired.message,
        )
    }

    /** Tests/TrackTimelineTests.swift:451-459: "Horizontal aggregation makes 3 video sections match one equal-length audio section", "One link can glue differently partitioned AV". */
    @Test
    fun picturesCutDifferentlyFromTheirSoundLinkWithoutAQuestion() {
        var partitioned = EditState(listOf(TimelineClip(uuid(20), source.id, 0.0, 12.0))).toTimeline()
        partitioned = partitioned.unlinked(partitioned.entireSelection())
        partitioned = TrackArrangeSupport.split(partitioned, t(4.0), TimelineSelection(video = setOf(partitioned.video[0].id))).timeline
        partitioned = TrackArrangeSupport.split(partitioned, t(8.0), TimelineSelection(video = partitioned.video.mapTo(LinkedHashSet()) { it.id })).timeline
        assertEquals(3, partitioned.video.size)
        val plan = done(partitioned.linkPlan(partitioned.entireSelection()))
        assertFalse(plan.needsIntersection)
        assertEquals(EditInterval.of(0.0, 12.0), plan.video)
        assertEquals(plan.video, plan.audio)
        val joined = done(partitioned.joining(plan.selection))
        assertEquals(4, joined.timeline.links[0].members.size)
        settled(joined.timeline)
    }

    /**
     * Tests/TrackTimelineTests.swift:460-476: a sound moved two seconds late
     * against its picture links only over the time they share, and only when
     * that is asked for. The original moves the sound (:463); here it is
     * written two seconds later.
     */
    @Test
    fun pictureAndSoundOverDifferentTimesLinkOnlyWhatTheyShareAndOnlyWhenAsked() {
        var mismatch = EditState(listOf(TimelineClip(uuid(20), source.id, 0.0, 8.0))).toTimeline()
        mismatch = mismatch.unlinked(mismatch.entireSelection())
        mismatch = shifted(mismatch, setOf(mismatch.audio[0].id), 2.0)
        val before = mismatch
        val plan = done(mismatch.linkPlan(mismatch.entireSelection()))
        assertTrue(plan.needsIntersection)
        assertEquals(EditInterval.of(2.0, 8.0), plan.intersection)
        rejected(mismatch, TimelineSelection(video = emptySet()), TimelineLinkError.TooFew)
        assertEquals(TimelineLinkError.ConfirmationRequired, mismatch.joining(mismatch.entireSelection()).errorOrNull)
        assertEquals(before, mismatch)

        val clipped = done(mismatch.joining(mismatch.entireSelection(), intersectionOnly = true))
        settled(clipped.timeline)
        assertEquals(before.duration, clipped.timeline.duration)
        assertEquals(2, clipped.timeline.video.size)
        assertEquals(2, clipped.timeline.audio.size)
        assertEquals(EditInterval.of(2.0, 8.0), clipped.timeline.interval(clipped.selection))
        assertEquals(2, clipped.selection.ids.size)
        assertEquals(before.video[0].mapping.duration.ticks, clipped.timeline.video.sumOf { it.mapping.duration.ticks })
        assertEquals(before.audio[0].mapping.duration.ticks, clipped.timeline.audio.sumOf { it.mapping.duration.ticks })
        assertEquals(listOf(clipped.selection.ids), clipped.timeline.links.map { it.members })
        // The sound is two seconds off its picture: it is linked, and does not follow it.
        assertTrue(clipped.timeline.audio.all { it.followsVideo == null })
    }

    /** Tests/TrackTimelineTests.swift:480-485: "N-part video/audio chains link the complete shared span". */
    @Test
    fun runsOfSeveralPartsLinkOverAllTheTimeTheyShare() {
        val many = shifted(detached, all.audio, 2.0)
        val joined = done(many.joining(many.entireSelection(), intersectionOnly = true))
        settled(joined.timeline)
        assertEquals(EditInterval.of(2.0, 12.0), joined.timeline.interval(joined.selection))
    }

    @Test
    fun linkingAnOriginalPairAgainMakesTheSoundFollowItsPicture() {
        val pair = detached.selection(setOf(clips[1].id, detached.audio[1].id))
        val joined = done(detached.joining(pair))
        assertEquals(clips[1].id, joined.timeline.audio[1].followsVideo)
        assertEquals(original.audio[1], joined.timeline.audio[1])
        assertEquals(listOf(pair.ids), joined.timeline.links.map { it.members })
        settled(joined.timeline)
        // A link takes its members out of the links they were in; one left with a single member goes.
        val regrouped = done(original.joining(TimelineSelection(video = setOf(clips[0].id, clips[1].id))))
        assertEquals(listOf(clips[2].id), regrouped.timeline.links.dropLast(1).map { it.id })
        assertEquals(original.selection(setOf(clips[0].id, clips[1].id)).ids, regrouped.timeline.links.last().members)
        settled(regrouped.timeline)
    }

    /**
     * Tests/TrackTimelineTests.swift:508-512: "Sequential parts joined by an
     * explicit transition can be glued without losing its handles". The
     * original sets the transition on the timeline; here the same edit is
     * made in the sequence form and converted.
     */
    @Test
    fun partsJoinedByATransitionLinkAsOneRun() {
        val blend = TimelineEditing.setJoin(EditState(clips.take(2)), 1, TransitionSettings(duration = 1.0))!!.toTimeline()
        assertEquals(1, blend.transitions.size)
        val joined = done(blend.joining(blend.entireSelection()))
        assertEquals(blend.transitions, joined.timeline.transitions)
        assertEquals(blend.video, joined.timeline.video)
        settled(joined.timeline)
    }

    /** Tests/TrackTimelineTests.swift:513-521: "Refused intersection preserves the complete transition and sources". */
    @Test
    fun sharedTimeThatEndsInsideATransitionIsRefused() {
        var blend = TimelineEditing.setJoin(EditState(clips.take(2)), 1, TransitionSettings(duration = 1.0))!!.toTimeline()
        blend = blend.unlinked(blend.entireSelection())
        // A detached sound that ends inside the crossfade.
        blend = blend.copy(audio = listOf(detached.audio[0].copy(mapping = detached.audio[0].mapping.sliced(EditInterval.of(0.0, 3.5)))))
        val unchanged = blend
        assertEquals(TimelineLinkError.TransitionBoundary, blend.linkPlan(blend.entireSelection()).errorOrNull)
        assertEquals(TimelineLinkError.TransitionBoundary, blend.joining(blend.entireSelection(), intersectionOnly = true).errorOrNull)
        assertEquals(unchanged, blend)
    }

    // MARK: Restore sync and Close gap

    /**
     * Tests/TrackTimelineTests.swift:177-187: "Restore sync is explicit and
     * does not silently relink", "Close gap removes only time empty on every
     * lane". The original reaches the state through a move and an outward
     * trim (:179-181): a picture of source 0…3 s at 1 s, with its sound.
     */
    @Test
    fun restoreSyncMovesTheSoundBackAndCloseGapRemovesEmptyTime() {
        val clip = TimelineClip(uuid(20), source.id, 0.0, 3.0)
        var reveal = EditState(listOf(clip)).toTimeline()
        reveal = shifted(reveal, reveal.allIDs, 1.0)
        settled(reveal)
        assertEquals(EditTime.ZERO, reveal.syncOffset(reveal.audio[0].id))
        reveal = reveal.unlinked(reveal.selection(clip.id))
        val sound = reveal.audio[0].id
        reveal = shifted(reveal, setOf(sound), 0.5)
        assertEquals(t(0.5), reveal.syncOffset(sound))
        reveal = reveal.restoringSync(sound)
        assertEquals(reveal.video[0].start, reveal.audio[0].start)
        assertTrue(reveal.links.isEmpty())
        assertEquals(EditInterval.of(0.0, 1.0), reveal.gap(t(0.5)))
        reveal = reveal.closingGap(t(0.5))
        assertEquals(EditTime.ZERO, reveal.video[0].start)
        assertEquals(EditTime.ZERO, reveal.audio[0].start)
        settled(reveal)
    }

    @Test
    fun syncIsMeasuredAgainstThePieceThatShowsTheSoundsFirstSecond() {
        // The sound of the second half of a cut clip, early by a quarter of a second.
        var cut = TrackArrangeSupport.split(EditState(listOf(clips[0])).toTimeline(), t(1.5), TimelineSelection(video = setOf(clips[0].id))).timeline
        cut = cut.unlinked(cut.entireSelection())
        val late = cut.audio[1].id
        assertEquals(EditTime.ZERO, cut.syncOffset(late))
        val early = shifted(cut, setOf(late), -0.25)
        assertEquals(t(-0.25), early.syncOffset(late))
        assertEquals(cut, early.restoringSync(late))
        // A late sound goes back as far as it is late.
        val first = cut.audio[0].id
        val pushed = shifted(cut, setOf(first), 1.0)
        assertEquals(t(1.0), pushed.syncOffset(first))
        assertEquals(EditTime.ZERO, pushed.restoringSync(first).audio[0].start)
        // Sound of another occurrence has nothing to be in sync with.
        val music = AudioTimelineItem(uuid(30), uuid(31), source.id, t(0.0), SourcePlaybackMap.single(t(2.0), 0.0, 2.0))
        val mixed = cut.copy(audio = cut.audio + music)
        assertNull(mixed.syncOffset(music.id))
        assertSame(mixed, mixed.restoringSync(music.id))
        assertNull(mixed.syncOffset(uuid(99)))
    }

    @Test
    fun aGapIsTimeThatIsEmptyOnEveryLane() {
        // Pictures at 0…4 and 6…10 with their sounds; music under the first second of the hole.
        var spaced = EditState(clips.take(2)).toTimeline()
        spaced = shifted(spaced, spaced.selection(clips[1].id).ids, 2.0)
        assertEquals(EditInterval.of(4.0, 6.0), spaced.gap(t(4.0)))
        assertEquals(EditInterval.of(4.0, 6.0), spaced.gap(t(5.9)))
        assertNull(spaced.gap(t(3.9)))
        assertNull(spaced.gap(t(6.0)))
        assertNull(spaced.gap(t(10.0)))
        assertNull(spaced.gap(t(-1.0)))
        assertSame(spaced, spaced.closingGap(t(2.0)))
        val music = AudioTimelineItem(uuid(30), uuid(31), source.id, t(3.0), SourcePlaybackMap.single(t(2.0), 0.0, 2.0))
        val under = spaced.copy(audio = spaced.audio + music)
        assertNull(under.gap(t(4.5)))
        assertEquals(EditInterval.of(5.0, 6.0), under.gap(t(5.5)))

        val marked = under.copy(
            bookmarks = listOf(
                TimelineBookmark(uuid(40), anchor = BookmarkAnchor.Project(t(4.0))),
                TimelineBookmark(uuid(41), anchor = BookmarkAnchor.Project(t(5.5))),
                TimelineBookmark(uuid(42), anchor = BookmarkAnchor.Project(t(9.0))),
                TimelineBookmark(uuid(43), anchor = BookmarkAnchor.Source(clips[1].id, source.id, 5.0)),
            ),
        )
        val closed = marked.closingGap(t(5.5))
        assertEquals(listOf(t(0.0), t(5.0)), closed.video.map { it.start })
        assertEquals(listOf(t(0.0), t(5.0), t(3.0)), closed.audio.map { it.start })
        assertEquals(t(9.0), closed.duration)
        // Marks on the timeline move with the time taken out; one inside the gap lands where it was.
        assertEquals(listOf(t(4.0), t(5.0), t(6.0), t(8.0)), closed.resolvedBookmarks.map { it.time })
        assertEquals(marked.video.map { it.mapping }, closed.video.map { it.mapping })
        settled(closed)
    }

    @Test
    fun closingAGapCarriesATransitionWithItsPictures() {
        val blend = TimelineEditing.setJoin(EditState(clips), 2, TransitionSettings(duration = 1.0))!!.toTimeline()
        val tail = blend.selection(setOf(clips[1].id, clips[2].id)).ids
        // The two pictures of the dissolve, three seconds later, as a move leaves them.
        val spaced = shifted(blend, tail, 3.0).let { it.copy(transitions = it.transitions.map { relation -> relation.copy(origin = relation.origin + t(3.0)) }) }.pruned()
        assertEquals(1, spaced.transitions.size)
        settled(spaced)
        val closed = spaced.closingGap(t(5.0))
        assertEquals(blend, closed)
    }
}
