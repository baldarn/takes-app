package app.takes.core

import app.takes.core.CoverFramePool.Interval
import app.takes.core.TrackFixtures.uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverPickTest {
    private fun ticks(seconds: Double) = EditTime.of(seconds)

    // Two pictures of two seconds with a second of nothing between them, and a sound from 1 s to 7 s.
    private val a = TimelineClip(uuid(1), uuid(10), 0.0, 2.0)
    private val b = TimelineClip(uuid(2), uuid(10), 4.0, 6.0)
    private val sound = AudioTimelineItem(id = uuid(3), occurrenceID = uuid(4), sourceID = uuid(11), start = ticks(1.0), mapping = SourcePlaybackMap.of(TimelineClip(uuid(5), uuid(11), 0.0, 6.0)))
    private val timeline = TrackTimeline(
        video = listOf(VideoTimelineItem.of(uuid(6), ticks(0.0), a), VideoTimelineItem.of(uuid(7), ticks(3.0), b)),
        audio = listOf(sound),
    )

    // CoverPicker.swift:31: nothing selected, the whole project, sound included, over the time with picture.
    @Test fun nothingSelectedOffersTheWholeProject() {
        val source = CoverPick.source(timeline, null)
        assertEquals(CoverPick.Scope.Whole, source.scope)
        assertEquals(0.0, source.start, 0.0)
        assertEquals(7.0, source.end, 1e-9)
        // Sixty pictures in 0…2 s and sixty in 3…5 s; the gap and the sound past the picture offer none.
        assertEquals(120, source.pool.count)
        assertEquals(3.0, source.pool.time(60), 1e-9)
        assertTrue(CoverPick.canUpdate(source.pool))
    }

    // CoverPicker.swift:23-24: a marked range is the scope, whatever is selected with it.
    @Test fun aMarkedRangeIsTheScope() {
        val source = CoverPick.source(timeline, TimeRange(1.5, 3.5), video = setOf(a.id))
        assertEquals(CoverPick.Scope.Range, source.scope)
        assertEquals(1.5, source.start, 0.0)
        assertEquals(3.5, source.end, 0.0)
        // 1.5…2 s and 3…3.5 s.
        assertEquals(30, source.pool.count)
        assertEquals(1.5, source.pool.time(0), 1e-9)
        assertEquals(3.0, source.pool.time(15), 1e-9)
    }

    // CoverPicker.swift:25-28: the pictures and sounds selected, the header from the first start to the last end.
    @Test fun selectedItemsAreTheScope() {
        val picture = CoverPick.source(timeline, null, video = setOf(b.id))
        assertEquals(CoverPick.Scope.Clips, picture.scope)
        assertEquals(3.0, picture.start, 1e-9)
        assertEquals(5.0, picture.end, 1e-9)
        assertEquals(60, picture.pool.count)
        // A sound selected alone offers the picture shown while it plays.
        val heard = CoverPick.source(timeline, null, audio = setOf(sound.id))
        assertEquals(CoverPick.Scope.Clips, heard.scope)
        assertEquals(1.0, heard.start, 1e-9)
        assertEquals(7.0, heard.end, 1e-9)
        assertEquals(90, heard.pool.count)
    }

    // CoverPicker.swift:34-39: a Still after a picture holds it past its clip, as far as the project goes.
    @Test fun aStillHoldsThePictureItLeadsOutOf() {
        val still = TimelineTransition(outgoing = b.id, incoming = null, settings = TransitionSettings(style = TransitionStyle.Still, duration = 5.0), origin = ticks(5.0))
        val held = timeline.copy(transitions = listOf(still))
        assertTrue(still.timing.videoDuration > 2.0)
        assertEquals(listOf(Interval(0.0, 2.0), Interval(3.0, 5.0), Interval(5.0, 7.0)), CoverPick.picture(held))
        assertEquals(180, CoverPick.source(held, null).pool.count)
        // A still that opens the movie holds nothing before it (CoverPicker.swift:36, `outgoing != nil`).
        val opening = still.copy(outgoing = null, incoming = a.id, origin = ticks(0.0))
        assertEquals(2, CoverPick.picture(timeline.copy(transitions = listOf(opening))).size)
    }

    // CoverPicker.swift:96: Update is offered once there are more than nine frames.
    @Test fun updateNeedsMoreThanNineFrames() {
        assertFalse(CoverPick.canUpdate(CoverPick.source(timeline, TimeRange(0.0, 0.3)).pool))
        assertTrue(CoverPick.canUpdate(CoverPick.source(timeline, TimeRange(0.0, 10.0 / 30)).pool))
        assertEquals(0, CoverPick.source(timeline, TimeRange(2.2, 2.8)).pool.count)
    }

    // CoverPicker.swift:6-9: minutes, seconds and hundredths, rounded to the hundredth
    // (Tests/EditorUITests.swift:11041-11044 writes a frame's time the same way).
    @Test fun timesAreWrittenInHundredths() {
        assertEquals("0:00.00", CoverPick.label(0.0))
        assertEquals("0:01.50", CoverPick.label(1.5))
        assertEquals("0:01.03", CoverPick.label(1.0 + 1.0 / 30))
        assertEquals("1:05.97", CoverPick.label(65.0 + 29.0 / 30))
        // At 60 frames a second, 1/60 s is 0.02 s, where the 30-frame grid wrote 0.03.
        assertEquals("0:00.02", CoverPick.label(1.0 / 60))
        assertEquals("0:00.04", CoverPick.label(0.044))
        assertEquals("1:00.00", CoverPick.label(59.996))
        assertEquals("75:00.00", CoverPick.label(4500.0))
        assertEquals("0:00.00", CoverPick.label(-3.0))
        assertEquals("0:00.00", CoverPick.label(Double.NaN))
    }

    // CoverPicker.swift:73-78: the title without separators, at most 40 characters, then the time.
    @Test fun theJpegIsNamedAfterTheProjectAndTheFrame() {
        assertEquals("Holiday - 0-01.50.jpg", CoverPick.fileName("Holiday", 1.5))
        assertEquals("a-b-c-d - 0-00.00.jpg", CoverPick.fileName("a/b:c\\d", 0.0))
        assertEquals("a-b - 0-00.00.jpg", CoverPick.fileName("//a\n\tb::", 0.0))
        assertEquals("Takes - 0-02.00.jpg", CoverPick.fileName("/:\\", 2.0))
        assertEquals("Takes - 0-02.00.jpg", CoverPick.fileName("  ", 2.0))
        // The spaces around a separator stay and the joined name is trimmed afterwards, as in the original.
        assertEquals("- - 0-02.00.jpg", CoverPick.fileName(" / ", 2.0))
        assertEquals("x".repeat(40) + " - 0-00.00.jpg", CoverPick.fileName("x".repeat(60), 0.0))
        // Counted as a reader counts: a letter with its accent is one character, not cut in two.
        val accented = "e\u0301".repeat(41)
        assertEquals("e\u0301".repeat(40) + " - 0-00.00.jpg", CoverPick.fileName(accented, 0.0))
    }

    // CoverPicker.swift:47: the grid's frames fit 512 by 512 and are never made larger.
    @Test fun thumbnailsFitFiveHundredTwelve() {
        assertEquals(512 to 288, CoverPick.thumbnail(1920, 1080))
        assertEquals(288 to 512, CoverPick.thumbnail(720, 1280))
        assertEquals(512 to 512, CoverPick.thumbnail(2160, 2160))
        assertEquals(320 to 240, CoverPick.thumbnail(320, 240))
    }

    // CoverPicker.swift:20 and 103.
    @Test fun theGridFollowsTheShapeAndTheTextSize() {
        assertEquals(16.0 / 9, CoverPick.aspect(0, 0), 0.0)
        assertEquals(0.5625, CoverPick.aspect(720, 1280), 0.0)
        assertEquals(148, CoverPick.columnWidth(16.0 / 9, accessible = false))
        assertEquals(100, CoverPick.columnWidth(0.5625, accessible = false))
        assertEquals(170, CoverPick.columnWidth(0.5625, accessible = true))
    }

    // CoverPicker.swift:198-213: a frame for every 24 points of travel, later to the left, held inside the pool.
    @Test fun aSwipeStepsOneFrameEvery24Points() {
        assertEquals(10, CoverPick.scrubbed(10, 0.0, 100))
        assertEquals(10, CoverPick.scrubbed(10, -23.0, 100))
        assertEquals(11, CoverPick.scrubbed(10, -36.0, 100))
        assertEquals(9, CoverPick.scrubbed(10, 36.0, 100))
        assertEquals(12, CoverPick.scrubbed(10, -48.0, 100))
        assertEquals(0, CoverPick.scrubbed(1, 480.0, 100))
        assertEquals(99, CoverPick.scrubbed(98, -480.0, 100))
        assertEquals(0, CoverPick.scrubbed(0, -48.0, 1))
    }
}
