package app.takes.core

import app.takes.core.TrackFixtures.uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bookmarks of the track timeline: what a new one is attached to, and the
 * list a navigator steps through. How a mark follows its footage through a
 * cut and a move is checked with those operations, in [TrackMoveTest].
 */
class TrackBookmarksTest {
    private fun t(seconds: Double) = EditTime.of(seconds)

    // Tests/TrackTimelineTests.swift:13-17.
    private val source = SourceMedia(id = uuid(1), filename = "source.mp4", duration = 4.0)
    private val a = TimelineClip(uuid(2), source.id, 0.0, 2.0)
    private val b = TimelineClip(uuid(3), source.id, 2.0, 4.0)
    private val baseline = EditState(listOf(a, b)).toTimeline()
    private val sound = baseline.audio[0]

    private fun settled(timeline: TrackTimeline) {
        assertNull(TrackInvariants.violation(timeline, listOf(source)))
        assertEquals(timeline, timeline.pruned())
    }

    /** Tests/TrackTimelineTests.swift:71-72: a mark at 1 s of two linked clips is on the footage of the first. */
    @Test
    fun aMarkOnAPictureIsAttachedToItsSourceSecond() {
        val marks = baseline.addingBookmark(t(1.0))
        assertEquals(BookmarkAnchor.Source(a.id, source.id, 1.0), marks.bookmarks.single().anchor)
        assertEquals(listOf(t(1.0)), marks.resolvedBookmarks.map { it.time })
        assertEquals(a.id, TrackBookmarks.owner(marks, marks.bookmarks.single()))
        assertEquals(baseline.video, marks.video)
        settled(marks)
        // At the cut the mark is on the picture that begins there.
        assertEquals(BookmarkAnchor.Source(b.id, source.id, 2.0), TrackBookmarks.anchor(baseline, t(2.0)))
    }

    @Test
    fun aMarkFollowsThePlayingSpeedOfItsPicture() {
        val fast = EditState(listOf(a.copy(speed = SpeedSettings(rate = 2.0)), b)).toTimeline()
        val marks = fast.addingBookmark(t(0.5), label = "Here")
        assertEquals(BookmarkAnchor.Source(a.id, source.id, 1.0), marks.bookmarks.single().anchor)
        assertEquals("Here", marks.bookmarks.single().label)
        assertEquals(t(0.5), marks.resolvedBookmarks.single().time)
    }

    @Test
    fun ofPicturesThatOverlapTheOneInFrontIsMarkedUnlessAnotherIsPreferred() {
        val cover = VideoTimelineItem.of(uuid(20), t(0.5), TimelineClip(uuid(21), source.id, 3.0, 4.0), layer = 9)
        val stacked = baseline.copy(video = baseline.video + cover)
        assertEquals(BookmarkAnchor.Source(uuid(20), source.id, 3.5), TrackBookmarks.anchor(stacked, t(1.0)))
        assertEquals(BookmarkAnchor.Source(a.id, source.id, 1.0), TrackBookmarks.anchor(stacked, t(1.0), preferred = a.id))
        // A preferred picture that does not play there is not used.
        assertEquals(BookmarkAnchor.Source(uuid(20), source.id, 3.5), TrackBookmarks.anchor(stacked, t(1.0), preferred = b.id))
    }

    @Test
    fun withASoundSelectedTheMarkIsOnSound() {
        val music = AudioTimelineItem(uuid(30), uuid(31), source.id, t(0.5), SourcePlaybackMap.single(t(3.0), 1.0, 4.0))
        val mixed = baseline.copy(audio = baseline.audio + music)
        assertEquals(BookmarkAnchor.Source(uuid(31), source.id, 1.5), TrackBookmarks.anchor(mixed, t(1.0), preferred = music.id))
        // Selected, but not playing there: the first sound that plays.
        assertEquals(BookmarkAnchor.Source(a.id, source.id, 0.25), TrackBookmarks.anchor(mixed, t(0.25), preferred = music.id))
        assertEquals(music.id, TrackBookmarks.owner(mixed, TimelineBookmark(anchor = BookmarkAnchor.Source(uuid(31), source.id, 1.5))))
    }

    @Test
    fun withNoPictureTheMarkIsOnTheFirstSoundAndWithNothingOnTheTimeline() {
        val soundOnly = baseline.copy(video = baseline.video.drop(1), links = baseline.links.drop(1), audio = baseline.audio.map { it.copy(followsVideo = if (it.id == sound.id) null else it.followsVideo) })
        assertEquals(BookmarkAnchor.Source(a.id, source.id, 1.0), TrackBookmarks.anchor(soundOnly, t(1.0)))
        val empty = baseline.copy(audio = baseline.audio.drop(1).map { it.copy(followsVideo = null) }, video = baseline.video.drop(1), links = emptyList())
        val marks = empty.addingBookmark(t(1.0))
        assertEquals(BookmarkAnchor.Project(t(1.0)), marks.bookmarks.single().anchor)
        assertEquals(t(1.0), marks.resolvedBookmarks.single().time)
        assertNull(TrackBookmarks.owner(marks, marks.bookmarks.single()))
        settled(marks)
        // Past the end there is nothing either.
        assertEquals(BookmarkAnchor.Project(t(9.0)), TrackBookmarks.anchor(baseline, t(9.0)))
    }

    @Test
    fun aMarkInsideAHeldFrameIsATickOfItsOccurrence() {
        val held = VideoTimelineItem(
            occurrenceID = uuid(40), start = t(4.0), content = TimelineClip(uuid(41), source.id, 1.0, 1.0),
            localOrigin = t(0.5), mapping = SourcePlaybackMap(listOf(PlaybackRun(t(3.0), 1.0, 1.0, silent = true))),
        )
        val timeline = baseline.copy(video = baseline.video + held)
        val marks = timeline.addingBookmark(t(5.0))
        assertEquals(BookmarkAnchor.Local(uuid(40), t(1.5)), marks.bookmarks.single().anchor)
        assertEquals(t(5.0), marks.resolvedBookmarks.single().time)
        assertEquals(held.id, TrackBookmarks.owner(marks, marks.bookmarks.single()))
        assertNull(TrackInvariants.violation(marks, listOf(source)))
    }

    @Test
    fun aMarkIsRemovedAndNamedByItsId() {
        val marks = TrackBookmarks.adding(TrackBookmarks.adding(baseline, t(1.0), id = uuid(50)), t(3.0), id = uuid(51))
        assertEquals(listOf(uuid(51)), TrackBookmarks.removing(marks, uuid(50)).bookmarks.map { it.id })
        assertSame(marks, TrackBookmarks.removing(marks, uuid(52)))
        val named = TrackBookmarks.labelled(marks, uuid(51), "Retake")
        assertEquals(listOf("", "Retake"), named.bookmarks.map { it.label })
        assertEquals(marks.bookmarks.map { it.anchor }, named.bookmarks.map { it.anchor })
        assertSame(marks, TrackBookmarks.labelled(marks, uuid(52), "Retake"))
        // YouRec/EditorView.swift:1292: the editor keeps 120 characters.
        val long = TrackBookmarks.labelled(marks, uuid(50), "x".repeat(121))
        assertEquals(120, long.bookmarks[0].label.length)
        assertEquals(120, TrackBookmarks.EDITOR_LABEL)
        settled(long)
    }

    @Test
    fun aMarkWhoseFootageIsGoneIsKeptAndLeftOutOfTheList() {
        val marks = TrackBookmarks.adding(TrackBookmarks.adding(baseline, t(1.0), id = uuid(50)), t(3.0), id = uuid(51))
        val trimmed = marks.copy(
            video = marks.video.drop(1), audio = marks.audio.drop(1), links = marks.links.drop(1),
        )
        assertEquals(listOf(uuid(51)), trimmed.resolvedBookmarks.map { it.id })
        assertEquals(listOf(uuid(50)), TrackBookmarks.hidden(trimmed).map { it.id })
        assertNull(TrackBookmarks.owner(trimmed, trimmed.bookmarks[0]))
        assertTrue(TrackBookmarks.hidden(marks).isEmpty())
        settled(trimmed)
    }

    /** YouRec/EditorView.swift:1262 and YouRec/TrackEditors.swift:83: a mark closer than 0.02 s is the one the playhead is on. */
    @Test
    fun steppingSkipsTheMarkThePlayheadIsOn() {
        val marks = baseline.addingBookmark(t(1.0)).addingBookmark(t(3.0)).resolvedBookmarks
        assertEquals(t(1.0), TrackBookmarks.next(marks, 0.0)?.time)
        assertEquals(t(3.0), TrackBookmarks.next(marks, 1.0)?.time)
        assertEquals(t(3.0), TrackBookmarks.next(marks, 0.985)?.time)
        assertEquals(t(1.0), TrackBookmarks.next(marks, 0.97)?.time)
        assertNull(TrackBookmarks.next(marks, 3.0))
        assertNull(TrackBookmarks.previous(marks, 1.0))
        assertNull(TrackBookmarks.previous(marks, 1.015))
        assertEquals(t(1.0), TrackBookmarks.previous(marks, 1.03)?.time)
        assertEquals(t(3.0), TrackBookmarks.previous(marks, 4.0)?.time)
        assertNull(TrackBookmarks.next(emptyList(), 0.0))
        assertEquals(0.02, TrackBookmarks.NAVIGATION_SLACK, 0.0)
    }

    @Test
    fun openingTimeMovesOnlyTheMarksOnTheTimelineItself() {
        val marks = listOf(
            TimelineBookmark(uuid(60), anchor = BookmarkAnchor.Project(t(1.0))),
            TimelineBookmark(uuid(61), anchor = BookmarkAnchor.Project(t(2.0))),
            TimelineBookmark(uuid(62), anchor = BookmarkAnchor.Source(a.id, source.id, 1.5)),
            TimelineBookmark(uuid(63), anchor = BookmarkAnchor.Local(a.id, t(3.0))),
        )
        val shifted = TrackBookmarks.shiftingProjectMarks(marks, t(2.0), t(5.0))
        assertEquals(
            listOf(BookmarkAnchor.Project(t(1.0)), BookmarkAnchor.Project(t(7.0)), marks[2].anchor, marks[3].anchor),
            shifted.map { it.anchor },
        )
    }
}
