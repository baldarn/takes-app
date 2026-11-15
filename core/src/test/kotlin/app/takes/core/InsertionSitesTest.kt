package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where new video goes, and the numbers of a photo's clip.
 *
 * Tests marked TRANSLATED carry over an expectation of the iPhone app's
 * timelineVideoInsertionTests (Tests/RecordingHistoryTests.swift:466-527):
 * a 4 s take, a 6 s gap and a 2 s take, with 2 s and 8 s takes to add. The
 * rule is YouRec/TimelineEditing.swift:3-33. The others are this port's own.
 */
class InsertionSitesTest {
    private fun span(start: Double, end: Double) = InsertionSites.Span(start, end)
    private fun placement(start: Double, rippleAt: Double, ripple: Double) = InsertionSites.Placement(start, rippleAt, ripple)

    /** Picture from 0 to 4 and from 10 to 12. RecordingHistoryTests.swift:466-479. */
    private val gapped = listOf(span(0.0, 4.0), span(10.0, 12.0))

    /** The same with 5 s of empty time first. RecordingHistoryTests.swift:500-501. */
    private val leading = listOf(span(5.0, 9.0), span(15.0, 17.0))

    private val gap = VideoInsertionSite.Gap(after = 4.0)

    // MARK: The original's rule

    /** TRANSLATED RecordingHistoryTests.swift:482-485. */
    @Test fun overlappingAndTouchingPictureIsOneStretch() {
        assertEquals(listOf(span(0.0, 12.0)), InsertionSites.union(listOf(span(10.0, 12.0), span(0.0, 4.0), span(3.0, 8.0), span(8.0, 10.0))))
        assertEquals(gapped, InsertionSites.union(listOf(span(10.0, 12.0), span(0.0, 4.0), span(6.0, 6.0))))
    }

    /** TRANSLATED RecordingHistoryTests.swift:489-491: two 2 s takes fill the gap from its left edge and nothing moves. */
    @Test fun aBatchThatFitsFillsTheGapFromItsLeftEdge() {
        assertEquals(placement(4.0, 10.0, 0.0), InsertionSites.placement(gap, gapped, 4.0))
    }

    /** TRANSLATED RecordingHistoryTests.swift:495-497: the 8 s take starts at 4 and the clip after the gap moves from 10 to 12. */
    @Test fun onlyTheOverflowMovesThePictureAfterTheGap() {
        assertEquals(placement(4.0, 10.0, 2.0), InsertionSites.placement(gap, gapped, 8.0))
    }

    /** TRANSLATED RecordingHistoryTests.swift:498-500: sound goes on to 16, the new take starts at 12. */
    @Test fun theEndIsAfterTheLastPictureNotAfterTheSound() {
        assertEquals(placement(12.0, 12.0, 0.0), InsertionSites.placement(VideoInsertionSite.End, gapped, 2.0))
    }

    /** TRANSLATED RecordingHistoryTests.swift:503-505. */
    @Test fun theStartIsFlushAgainstTheFirstPicture() {
        assertEquals(placement(3.0, 5.0, 0.0), InsertionSites.placement(VideoInsertionSite.Start, leading, 2.0))
    }

    /** TRANSLATED RecordingHistoryTests.swift:506-507: the 8 s take starts at 0 and the first clip moves from 5 to 8. */
    @Test fun aLongAdditionAtTheStartMovesOnlyItsExcess() {
        assertEquals(placement(0.0, 5.0, 3.0), InsertionSites.placement(VideoInsertionSite.Start, leading, 8.0))
    }

    /** TRANSLATED RecordingHistoryTests.swift:508-512: a project with only a soundtrack. */
    @Test fun withoutAnyPictureEverythingGoesAtZero() {
        for (site in listOf(VideoInsertionSite.Start, gap, VideoInsertionSite.End)) {
            assertEquals(placement(0.0, 0.0, 0.0), InsertionSites.placement(site, emptyList(), 2.0))
        }
    }

    /** YouRec/TimelineEditing.swift:26-29. */
    @Test fun aGapThatIsNoLongerThereFallsBackToTheStartOrTheEnd() {
        // No picture begins before the place: the start.
        assertEquals(InsertionSites.placement(VideoInsertionSite.Start, leading, 2.0), InsertionSites.placement(VideoInsertionSite.Gap(5.0), leading, 2.0))
        // No picture after it: right after what is there.
        assertEquals(placement(12.0, 12.0, 0.0), InsertionSites.placement(VideoInsertionSite.Gap(12.0), gapped, 2.0))
        // The gap is found again from its left edge, wherever inside it the place is.
        assertEquals(placement(4.0, 10.0, 0.0), InsertionSites.placement(VideoInsertionSite.Gap(7.0), gapped, 2.0))
    }

    @Test fun pictureFromTimeZeroLeavesNoRoomBeforeIt() {
        assertEquals(placement(0.0, 0.0, 2.0), InsertionSites.placement(VideoInsertionSite.Start, gapped, 2.0))
    }

    /** YouRec/TimelineEditing.swift:6-12. */
    @Test fun eachSiteSaysWhatItsButtonDoes() {
        assertEquals("Add video or photo at start", VideoInsertionSite.Start.label)
        assertEquals("Add video or photo in gap", gap.label)
        assertEquals("Add video or photo at end", VideoInsertionSite.End.label)
    }

    // MARK: On this port's edit

    private val a = TimelineClip(sourceID = "a", start = 0.0, end = 4.0)
    private val b = TimelineClip(sourceID = "b", start = 0.0, end = 6.0)
    private val added = TimelineClip(sourceID = "new", start = 0.0, end = 2.0)
    private val state = EditState(listOf(a, b))

    /** The order of the footage and where the added clip begins, after inserting at the time the rule gives. */
    private fun inserted(playhead: Double, site: VideoInsertionSite? = null): Pair<List<String>, Double> {
        val time = InsertionSites.time(state, playhead, site)!!
        val next = TimelineEditing.insert(state, listOf(added), time)!!
        return next.clips.map { it.sourceID } to TimelineEditing.clipStart(next.clips, next.clips.indexOfFirst { it.id == added.id })
    }

    @Test fun anEditHasPictureFromZeroToItsEndOrNone() {
        assertEquals(listOf(span(0.0, 10.0)), InsertionSites.coverage(state))
        assertEquals(emptyList<InsertionSites.Span>(), InsertionSites.coverage(EditState()))
        assertEquals(listOf(VideoInsertionSite.Start, VideoInsertionSite.End), InsertionSites.sites(state))
        assertEquals(listOf(VideoInsertionSite.Start), InsertionSites.sites(EditState()))
    }

    /** YouRec/TimelineEditing.swift:89 and 117-118: without a site, at the playhead. */
    @Test fun thePlayheadAtTheStartInsertsBeforeEverything() {
        assertEquals(0.0, InsertionSites.time(state, 0.0)!!, 0.0)
        assertEquals(listOf("new", "a", "b") to 0.0, inserted(0.0))
    }

    @Test fun thePlayheadInsideAClipInsertsThereBetweenItsTwoPieces() {
        assertEquals(1.5, InsertionSites.time(state, 1.5)!!, 0.0)
        assertEquals(listOf("a", "new", "a", "b") to 1.5, inserted(1.5))
        assertEquals(listOf("a", "b", "new", "b") to 7.0, inserted(7.0))
    }

    @Test fun thePlayheadAtACutInsertsBetweenTheTwoClips() {
        assertEquals(4.0, InsertionSites.time(state, 4.0)!!, 0.0)
        assertEquals(listOf("a", "new", "b") to 4.0, inserted(4.0))
    }

    @Test fun thePlayheadAtTheEndInsertsAfterEverything() {
        assertEquals(10.0, InsertionSites.time(state, 10.0)!!, 0.0)
        assertEquals(listOf("a", "b", "new") to 10.0, inserted(10.0))
    }

    @Test fun aPlayheadOutsideTheMovieIsBroughtToItsNearerEnd() {
        assertEquals(10.0, InsertionSites.time(state, 99.0)!!, 0.0)
        assertEquals(0.0, InsertionSites.time(state, -2.0)!!, 0.0)
        assertNull(InsertionSites.time(state, Double.NaN))
    }

    @Test fun theAddButtonsIgnoreThePlayhead() {
        assertEquals(listOf("new", "a", "b") to 0.0, inserted(7.0, VideoInsertionSite.Start))
        assertEquals(listOf("a", "b", "new") to 10.0, inserted(1.5, VideoInsertionSite.End))
    }

    /** There are no gaps here: one remembered from elsewhere falls back as the original's does. */
    @Test fun aGapOnAnEditWithoutGapsIsItsEndOrItsStart() {
        assertEquals(10.0, InsertionSites.time(state, 1.5, VideoInsertionSite.Gap(4.0))!!, 0.0)
        assertEquals(0.0, InsertionSites.time(state, 1.5, VideoInsertionSite.Gap(0.0))!!, 0.0)
    }

    @Test fun onAnEmptyEditEverythingIsAtZero() {
        val empty = EditState()
        assertEquals(0.0, InsertionSites.time(empty, 5.0)!!, 0.0)
        for (site in listOf(VideoInsertionSite.Start, gap, VideoInsertionSite.End)) assertEquals(0.0, InsertionSites.time(empty, 5.0, site)!!, 0.0)
        assertEquals(listOf(added), TimelineEditing.insert(empty, listOf(added), InsertionSites.time(empty, 5.0)!!)!!.clips)
    }

    /** With a dissolve the movie is shorter than its clips together; the end is the end of the movie. */
    @Test fun theEndFollowsADissolve() {
        val dissolved = TimelineEditing.setTransition(state, 0, 2.0, TransitionStyle.Dissolve)!!
        assertEquals(8.0, InsertionSites.time(dissolved, 0.0, VideoInsertionSite.End)!!, 0.0)
        assertEquals(8.0, InsertionSites.time(dissolved, 9.0)!!, 0.0)
    }

    // MARK: A photo

    /** YouRec/PhotoClip.swift:7 and 48-59; the original's test reads frames 0, 45 and 89 (RecordingHistoryTests.swift:435-436). */
    @Test fun aPhotoIsThreeSecondsOfTheSameFrame() {
        assertEquals(3, PhotoClip.SECONDS)
        assertEquals(30, PhotoClip.FRAME_RATE)
        assertEquals(90, PhotoClip.FRAMES)
        assertEquals(0.0, PhotoClip.frameTime(0), 0.0)
        assertEquals(1.5, PhotoClip.frameTime(45), 0.0)
        assertEquals(89.0 / 30, PhotoClip.frameTime(89), 0.0)
        assertEquals(3840, PhotoClip.MAXIMUM_PIXELS)
    }

    /** YouRec/PhotoClip.swift:20-21. */
    @Test fun itsMovieHasEvenSides() {
        assertEquals(216 to 122, PhotoClip.encodedSize(216, 122))
        assertEquals(216 to 122, PhotoClip.encodedSize(215, 121))
        assertEquals(2 to 2, PhotoClip.encodedSize(1, 1))
        assertEquals(2 to 2, PhotoClip.encodedSize(0, 0))
        assertEquals(3840 to 2160, PhotoClip.encodedSize(3840, 2160))
    }

    /** YouRec/VideoBatchImport.swift:100-102: the whole of its movie, like any take. */
    @Test fun aPhotoIsAClipOfItsWholeMovie() {
        val source = SourceMedia(filename = "photo.mp4", duration = 3.0)
        val clip = PhotoClip.clip(source)
        assertEquals(source.id, clip.sourceID)
        assertEquals(0.0, clip.start, 0.0)
        assertEquals(3.0, clip.end, 0.0)
        assertEquals(PhotoClip.SECONDS.toDouble(), clip.duration, 0.0)
    }
}
