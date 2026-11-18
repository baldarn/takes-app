package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A piece of a title edited on part of its range keeps the clock of the whole
 * title, as the original draws it since 57e5d91: its run is the whole clock,
 * placed by where its footage stands on it (YouRec/VideoTools.swift:1222-1224),
 * and it is posed at the clock's time of the footage shown
 * (YouRec/VideoToolCompositor.swift:507-511).
 */
class TitlePlaybackClockTest {
    private val motion = TitleMotion(entrance = TitleMove.Fade, exit = TitleMove.Fade, inSeconds = 1.0, outSeconds = 1.0)
    // A title over six seconds of footage at speed 1: its clock runs 0…6 with the footage.
    private val clock = TitlePlaybackClock(6.0, listOf(StrobeTimePoint(0.0, 0.0), StrobeTimePoint(6.0, 6.0)))
    private val title = TextOverlay(id = "T", text = "Hello", start = 0.0, end = 6.0, motion = motion)

    @Test fun aPieceOnItsOwnClockRunsTheWholeClock() {
        // The piece of footage 2…4 s, at 10 s of the movie, after a clip of 10 s.
        val before = TimelineClip(id = "A", sourceID = "S", start = 10.0, end = 20.0)
        val piece = TimelineClip(id = "B", sourceID = "S", start = 2.0, end = 4.0, texts = listOf(title.copy(playbackClock = clock)))
        val ranges = TitleMotionRanges(listOf(before, piece))
        assertEquals(8.0..14.0, ranges.range(title.copy(playbackClock = clock), 10.5))
        // At 10.5 s the footage is at 2.5 s, its clock at 2.5 s of 6: fully in, not yet leaving.
        val clocked = title.copy(playbackClock = clock)
        assertEquals(motion.pose(2.5, 6.0), ranges.pose(clocked, 10.5))
        assertEquals(motion.pose(3.9, 6.0), ranges.pose(clocked, 11.9))
    }

    @Test fun onAFasterClipTheClockFollowsTheFootage() {
        // The same piece played twice as fast: one second of the movie shows two of the footage and of the clock.
        val piece = TimelineClip(id = "B", sourceID = "S", start = 2.0, end = 4.0, speed = SpeedSettings(rate = 2.0), texts = listOf(title.copy(playbackClock = clock)))
        val clocked = title.copy(playbackClock = clock)
        val ranges = TitleMotionRanges(listOf(piece))
        assertEquals(motion.pose(3.0, 6.0), ranges.pose(clocked, 0.5))
    }

    @Test fun withoutAClockATitleRunsOverItsPieces() {
        val piece = TimelineClip(id = "B", sourceID = "S", start = 2.0, end = 4.0, texts = listOf(title))
        val ranges = TitleMotionRanges(listOf(piece))
        assertEquals(0.0..2.0, ranges.range(title, 1.0))
        assertEquals(motion.pose(1.0, 2.0), ranges.pose(title, 1.0))
    }
}
