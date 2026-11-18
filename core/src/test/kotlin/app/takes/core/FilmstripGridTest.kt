package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The timeline's thumbnail grid, with the checks of Tests/TrackTimelineTests.swift:1065-1077 at 57e5d91. */
class FilmstripGridTest {
    private val grid = FilmstripGrid(tileWidth = 120.0, pointsPerSecond = 30.0, fps = 30.0)

    @Test fun aTileCoversAPowerOfTwoOfFrames() {
        // 120 points at 30 a second are 4 seconds, 120 frames: the nearest power of two is 128.
        assertEquals(128.0 / 30, grid.step, 1e-12)
        assertEquals(1.0 / 30, FilmstripGrid(64.0, 2000.0, 30.0).step, 1e-12)
    }

    @Test fun aSubFrameTileNeverSamplesTheClipAfterIt() {
        assertEquals(EditTime.of(1.0), grid.sample(1.01, 1.02, center = true, duration = 30.0))
        // Never past the last frame.
        assertEquals(EditTime.of(899.0 / 30), grid.sample(40.0, 44.0, center = false, duration = 30.0))
    }

    @Test fun tinyEffectsCannotQueueHundredsOfDecodesInOneTile() {
        val edges = (1 until 1000).map { it / 100.0 }
        val bounded = grid.boundaries(0.0, 10.0, edges, pointsPerSecond = 12.0)
        assertEquals(0.0, bounded.first(), 0.0)
        assertEquals(10.0, bounded.last(), 0.0)
        assertTrue(bounded.size <= 11)
    }

    @Test fun splittingKeepsTheGridInsideTheRightPiece() {
        val timeline = EditState(listOf(TimelineClip("A", "S", 0.0, 30.0))).toTimeline()
        val split = TrackEditing.split(timeline, EditTime.of(10.0), TimelineSelection(video = setOf(timeline.video[0].id))).timeline
        val right = split.video.maxBy { it.start }
        assertEquals(grid.slot(15.0, 0.0), grid.slot(15.0, (right.start - right.localOrigin).seconds), 1e-9)
    }

    @Test fun aClipsTilesFollowTheGridOfItsFootage() {
        val grid = FilmstripGrid(64.0, 16.0, FilmstripTiles.FPS)
        // 64 points at 16 a second: 128 frames, 128/30 s a tile.
        val step = 128.0 / 30
        // The right piece of a split at 10 s: its footage would begin at 0, so its first tile is cut where the grid has it.
        val right = FilmstripTiles.of(grid, start = 10.0, duration = 20.0, origin = 0.0, from = 0.0, to = 20.0)
        assertEquals(0.0, right.first().from, 1e-9)
        assertEquals(3 * step - 10.0, right.first().to, 1e-9)
        assertEquals(3 * step - 10.0, right[1].from, 1e-9)
        assertEquals(20.0, right.last().to, 1e-9)
        // Each shows the first frame of its tile.
        assertEquals(EditTime.of(3 * step).seconds - 10.0, right[1].at, 1e-9)
        // The same footage unsplit has the same tiles from 10 s on.
        val whole = FilmstripTiles.of(grid, start = 0.0, duration = 30.0, origin = 0.0, from = 10.0, to = 30.0)
        assertEquals(right.drop(1).map { it.from + 10.0 }, whole.drop(1).map { it.from })
    }
}
