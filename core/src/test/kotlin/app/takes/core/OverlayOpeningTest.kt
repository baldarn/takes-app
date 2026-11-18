package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** A new title's defaults, the centre snap of a drag and the paused frame a tool opens on. */
class OverlayOpeningTest {
    private val frame = PauseDetector.FRAME

    // Tests/SubtitleTests.swift:111-118
    @Test fun eachAxisSnapsToTheCentreOnItsOwn() {
        for ((finger, expected) in listOf(
            (0.486 to 0.7) to (0.5 to 0.7),
            (0.3 to 0.514) to (0.3 to 0.5),
            (0.51 to 0.49) to (0.5 to 0.5),
            (0.48 to 0.52) to (0.48 to 0.52),
        )) {
            assertEquals(expected, OverlayPlacement.snappedCenter(finger.first, finger.second))
        }
    }

    // YouRec/VideoTools.swift:250-258: kept inside the frame; snapping at less than 1.5 % only.
    @Test fun aDragStaysInsideTheFrameAndSnapsWithinOneAndAHalfPercent() {
        assertEquals(0.0 to 1.0, OverlayPlacement.snappedCenter(-0.2, 1.4))
        assertEquals(0.5, OverlayPlacement.snapped(0.5149), 0.0)
        assertEquals(0.516, OverlayPlacement.snapped(0.516), 0.0)
        assertEquals(ImageOverlay.SNAP, OverlayPlacement.SNAP, 0.0)
    }

    // YouRec/SubtitleEditor.swift:214-216: the caption series snaps where the drag puts it, then is kept within its limits.
    @Test fun aDraggedCaptionSeriesSnapsToTheCentre() {
        val placement = SubtitlePlacement(Size(1920.0, 1080.0), Size(600.0, 80.0))
        val settings = SubtitleSettings(x = 0.3, y = 0.8)
        val snapped = placement.dragged(settings, 0.507, 0.494)
        assertEquals(0.5, snapped.centerX, 0.0)
        assertEquals(0.5, snapped.y, 0.0)
        val free = placement.dragged(settings, 0.42, 0.7)
        assertEquals(0.42, free.centerX, 1e-12)
        assertEquals(0.7, free.y, 1e-12)
        // Past the edge the series stays whole on the frame.
        assertEquals(placement.constrained(settings.copy(x = 1.0, y = 1.0)), placement.dragged(settings, 1.3, 1.2))
    }

    // YouRec/VideoTools.swift:363-372: "Text" at a tenth of the short side, where it was "Your text" at 0.065.
    @Test fun aNewTitleStartsAsTextAtATenth() {
        val fresh = TextOverlay.fresh()
        assertEquals("Text", fresh.text)
        assertEquals(0.10, fresh.size, 0.0)
        assertEquals(TitleDesign(), fresh.appearance)
        assertEquals("Testo", TextOverlay.fresh("Testo").text)
    }

    // Tests/TitleDesignTests.swift:404-415
    @Test fun anOverlayOpensOnItsReadableFootage() {
        val timing = EditorRangeTiming(3.125..6.125, 1.25..70.38)
        val fade = TitleMotion(entrance = TitleMove.Fade, inSeconds = 5.0)
        val readable = fade.readableRange(timing.range)
        val opening = timing.openingPosition(listOf(1.25..70.38), readable)
        assertTrue("Long entrance must open fully readable", timing.range.start + opening in readable)
        assertEquals(1.0, fade.pose(opening, timing.duration).opacity, 0.0)
        assertEquals("Static overlay opens halfway through its range", 1.5, timing.openingPosition(listOf(1.25..70.38), null), 0.0)
        val split = EditorRangeTiming(3.125..4.125, timing.range)
        assertTrue("Previewing only the entrance stays on its most visible in-range frame",
            abs(split.openingPosition(listOf(split.range), readable) - (1 - frame)) < 0.000001)
        val sparse = EditorRangeTiming(0.0..10.0, 0.0..10.0)
        assertEquals("The poster should not land in a gap", 7.0, sparse.openingPosition(listOf(0.0..1.0, 7.0..10.0), null), 0.0)
    }

    // YouRec/TitleDesign.swift:410-414
    @Test fun theReadableRangeLeavesOutBothEnds() {
        val motion = TitleMotion(entrance = TitleMove.Fade, exit = TitleMove.Wipe, inSeconds = 0.5, outSeconds = 0.75)
        val readable = motion.readableRange(2.0..6.0)
        assertEquals(2.5, readable.start, 1e-12)
        assertEquals(5.25, readable.endInclusive, 1e-12)
        assertEquals(2.0..6.0, TitleMotion().readableRange(2.0..6.0))
    }

    // YouRec/EditorTools.swift:308-320: the Title tool opens on the readable middle of the footage its title covers.
    @Test fun theTitleToolOpensOnTheReadablePartOfItsTitle() {
        val clips = listOf(TimelineClip("A", "S", 0.0, 4.0), TimelineClip("B", "S", 10.0, 20.0))
        val still = TextOverlay.fresh().copy(id = "T")
        assertEquals(5.0, OverlayOpening.title(clips, still, 3.0..7.0, listOf(0.0..4.0, 4.0..14.0)), 1e-9)
        // Over the first clip only, the last frame of it nearest the middle.
        assertEquals(4.0 - frame, OverlayOpening.title(clips, still, 3.0..7.0, listOf(0.0..4.0)), 1e-9)
        // A three-second fade in: the middle, 5, is still arriving; the readable part is 6 to 7.
        val fading = still.copy(motion = TitleMotion(entrance = TitleMove.Fade, inSeconds = 3.0))
        val placed = TimelineEditing.placeTitle(EditState(clips = clips), fading, 3.0..7.0)!!.clips
        assertEquals(6.5, OverlayOpening.title(placed, fading, 3.0..7.0, listOf(0.0..4.0, 4.0..14.0)), 1e-9)
    }

    // YouRec/SubtitleEditor.swift:68-79
    @Test fun theSubtitlesToolOpensOnTheCaptionNearestTheMiddle() {
        val cues = listOf(SubtitleCue("1", "A", emptyList(), "One", 0.0, 2.0), SubtitleCue("2", "A", emptyList(), "Two", 9.0, 11.0))
        assertEquals(10.0, OverlayOpening.captions(cues, 0.0..20.0, null), 1e-9)
        assertEquals(1.0, OverlayOpening.captions(cues, 0.0..8.0, null), 1e-9)
        assertEquals(3.0, OverlayOpening.captions(emptyList(), 3.0..8.0, null), 0.0)
        // A slow entrance moves the frame onto the readable part of the caption.
        assertEquals(10.75, OverlayOpening.captions(cues, 0.0..20.0, TitleMotion(entrance = TitleMove.Drift, inSeconds = 1.5)), 1e-9)
    }
}
