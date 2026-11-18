package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What each edited thumbnail draws and is kept by, with the checks of Tests/TrackTimelineTests.swift:1025-1100 at 57e5d91. */
class FilmstripPlanTest {
    private val clip = TimelineClip("A", "S", 0.0, 30.0)
    private val edit = EditState(listOf(clip)).toTimeline()
    private val mono = ColorSettings(id = "mono", look = ColorLook.Mono)

    private fun key(timeline: TrackTimeline, t: Double, lane: VideoTimelineItem = timeline.video[0]): String =
        FilmstripPlan(timeline).sample(lane.id, EditTime.of(t), newID()).key

    private fun TrackTimeline.at(t: Double): VideoTimelineItem = video.first { EditTime.of(t) in it.interval }

    private fun TrackTimeline.changing(change: (TimelineClip) -> TimelineClip): TrackTimeline =
        copy(video = video.mapIndexed { i, item -> if (i == 0) item.copy(content = change(item.content)) else item })

    private val graded = edit.changing { it.copy(colorRegions = listOf(ColorRegion(10.0, 20.0, mono))) }

    @Test fun onlyTheGradedFramesGetANewIdentity() {
        assertNotEquals(key(edit, 14.0), key(graded, 14.0))
        assertEquals(key(edit, 4.0), key(graded, 4.0))
    }

    @Test fun aSplitKeepsTheGradedFrames() {
        val split = TrackEditing.split(graded, EditTime.of(12.0), TimelineSelection(video = setOf(graded.video[0].id))).timeline
        assertEquals(key(graded, 14.0), key(split, 14.0, split.at(14.0)))
    }

    @Test fun aRippleCarriesTheLookAndExactFrameToItsNewPlace() {
        val rippled = TrackEditing.removing(graded, 4.0, 8.0).timeline
        assertEquals(key(graded, 14.0), key(rippled, 10.0, rippled.at(10.0)))
    }

    private val stacked = graded.copy(video = graded.video + VideoTimelineItem.of(newID(), EditTime.of(12.0), TimelineClip("B", "T", 0.0, 5.0), layer = 2))

    @Test fun overlappingLanesShareOneComposite() {
        val composite = key(stacked, 14.0)
        assertNotEquals(key(graded, 14.0), composite)
        assertEquals(composite, key(stacked, 14.0, stacked.video[1]))
        // A cut outside the overlap keeps it.
        val split = TrackEditing.split(stacked, EditTime.of(4.0), TimelineSelection(video = setOf(stacked.video[0].id))).timeline
        assertEquals(composite, key(split, 14.0, split.video.first { it.content.sourceID == "T" }))
    }

    @Test fun aPlacementRedrawsItsOverlapAlone() {
        val top = stacked.video[1].id
        val placed = stacked.copy(placements = listOf(VideoPlacementRegion(participants = stacked.video.map { it.id }.toSet(), videos = mapOf(top to VideoPlacement(width = 0.5)))))
        assertNotEquals(key(stacked, 14.0), key(placed, 14.0))
        assertEquals(key(edit, 4.0), key(placed, 4.0))
    }

    private val captioned = TrackSubtitles.installing(edit, listOf(SubtitleWord(text = "Speaking", start = 13.0, end = 15.0)), 13.0, 15.0, "Auto")

    @Test fun aCaptionStaysWithItsPictureThroughARipple() {
        val caption = key(captioned, 14.0)
        assertNotEquals(key(edit, 14.0), caption)
        val shifted = TrackEditing.removing(captioned, 4.0, 8.0).timeline
        assertEquals(caption, key(shifted, 10.0, shifted.at(10.0)))
        val sample = FilmstripPlan(captioned).sample(captioned.video[0].id, EditTime.of(14.0), "G")
        assertTrue(sample.captionPlaceholder!!.let { sample.key.startsWith(it) && it != sample.key })
    }

    @Test fun aCorrectedCaptionChangesOnlyCaptionedThumbnails() {
        val cue = TrackSubtitles.cues(captioned)[0]
        val corrected = TrackSubtitles.correcting(captioned, cue, "Corrected")!!
        assertNotEquals(key(captioned, 14.0), key(corrected, 14.0))
        assertEquals(key(edit, 4.0), key(corrected, 4.0))
    }

    @Test fun refinedPicturesAreNeverReused() {
        val refined = edit.changing { it.copy(videoEffects = listOf(VideoEffectSettings(VideoEffectID.AutoBackground, 10.0, 20.0))) }
        assertNotEquals(key(refined, 14.0), key(refined, 14.0))
        assertEquals(key(edit, 4.0), key(refined, 4.0))
        val steadied = refined.changing { it.copy(stabilization = StabilizationSettings(StabilizationLevel.Strong, activeRange = SourceInterval(2.0, 6.0))) }
        assertNotEquals(key(steadied, 4.0), key(steadied, 4.0))
        assertEquals(key(edit, 24.0), key(steadied, 24.0))
    }

    @Test fun theGradeFollowsTheSourceClockAtBothEdges() {
        val fractional = EditState(listOf(TimelineClip("A", "S", 0.0, 3.000003))).toTimeline()
        val before = key(fractional, 1.0)
        val ranged = fractional.changing { it.copy(colorRegions = listOf(ColorRegion(1.0, 2.0, mono))) }
        assertNotEquals(before, key(ranged, 1.0))
        assertEquals(key(edit, 2.0), key(ranged, 2.0))
    }

    @Test fun aPlainFrameIsItsSourceAndTime() {
        val sample = FilmstripPlan(edit).sample(edit.video[0].id, EditTime.of(4.0), "G")
        assertTrue(sample.plain)
        assertEquals(4.0, sample.sourceTime!!, 1e-9)
        assertEquals("S:" + EditTime.of(4.0).ticks, sample.key)
        assertNull(sample.captionPlaceholder)
        assertFalse(FilmstripPlan(graded).sample(graded.video[0].id, EditTime.of(14.0), "G").plain)
    }

    @Test fun aTransitionIsDrawnAgainWithEachGeneration() {
        val pair = EditState(listOf(TimelineClip("A", "S", 0.0, 10.0), TimelineClip("B", "S", 20.0, 30.0))).toTimeline()
        val left = pair.video.minBy { it.start }
        val right = pair.video.maxBy { it.start }
        val faded = pair.copy(transitions = listOf(TimelineTransition(left.id, right.id, TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0), origin = right.start - EditTime.of(0.5))))
        val at = right.start.seconds
        assertEquals("G:" + EditTime.of(at).ticks, FilmstripPlan(faded).sample(right.id, EditTime.of(at), "G").key)
        assertEquals(key(pair, 4.0), key(faded, 4.0))
    }

    @Test fun whatGivesTheTimelineANewGeneration() {
        assertFalse(FilmstripPlan.regenerates(null, edit))
        assertFalse(FilmstripPlan.regenerates(edit, edit.copy(bookmarks = listOf(TimelineBookmark(anchor = BookmarkAnchor.Project(EditTime.ZERO))))))
        assertTrue(FilmstripPlan.regenerates(edit, graded))
        assertTrue(FilmstripPlan.regenerates(edit, captioned))
        // Sound matters only under subtitles.
        val louder = edit.copy(audio = edit.audio.map { it.copy(volumeDB = -6.0) })
        assertFalse(FilmstripPlan.regenerates(edit, louder))
        assertTrue(FilmstripPlan.regenerates(captioned, captioned.copy(audio = captioned.audio.map { it.copy(volumeDB = -6.0) })))
    }

    @Test fun aRippleInATwentyMinuteTimelineKeepsEverySurvivingThumbnail() {
        val clips = (0 until 120).map { index -> TimelineClip("C$index", "S$index", 10.0, 20.0).let { if (index % 4 == 0) it.copy(color = mono) else it } }
        val long = EditState(clips).toTimeline()
        val original = FilmstripPlan(long)
        val short = TrackEditing.removing(long, 365.0, 370.0).timeline
        val after = FilmstripPlan(short, reusing = original)
        var matches = 0
        for (item in long.video) {
            for (offset in listOf(1.0, 5.0, 9.0)) {
                val t = item.start + EditTime.of(offset)
                if (!(t < EditTime.of(365.0) || t >= EditTime.of(370.0))) continue
                val shifted = if (t < EditTime.of(365.0)) t else t - EditTime.of(5.0)
                val lane = short.video.firstOrNull { shifted in it.interval && it.content.sourceID == item.content.sourceID } ?: continue
                if (original.sample(item.id, t, newID()).key == after.sample(lane.id, shifted, newID()).key) matches++
            }
        }
        assertEquals(358, matches)
    }
}
