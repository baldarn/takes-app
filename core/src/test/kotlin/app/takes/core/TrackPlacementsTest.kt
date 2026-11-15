package app.takes.core

import app.takes.core.TrackFixtures.uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The windows of overlapping pictures as relations of the timeline. The
 * checks that come from the original name their lines in
 * `Tests/TrackTimelineTests.swift`; those of its placement checks that need a
 * move are in [TrackMoveTest].
 */
class TrackPlacementsTest {
    private fun t(seconds: Double) = EditTime.of(seconds)
    private val source = SourceMedia(id = uuid(1), filename = "source.mp4", duration = 20.0)
    private val sources = listOf(source)

    private fun picture(number: Int, start: Double, length: Double, layer: Int) =
        VideoTimelineItem.of(uuid(100 + number), t(start), TimelineClip(uuid(number), source.id, 0.0, length), layer)

    private val window = VideoPlacement(x = 0.7, y = 0.3, width = 0.4, height = 0.4)

    // A under B from 1 s to 2 s, B under C from 3 s to 5 s; A and C never play together.
    private val a = picture(10, 0.0, 2.0, 0)
    private val b = picture(11, 1.0, 4.0, 1)
    private val c = picture(12, 3.0, 2.0, 2)
    private val ab = VideoPlacementRegion(uuid(50), setOf(a.id, b.id), mapOf(a.id to VideoPlacement(), b.id to window.copy(order = 1)))
    private val stacked = TrackTimeline(video = listOf(a, b, c), placements = listOf(ab))

    private fun settled(timeline: TrackTimeline) {
        assertNull(TrackInvariants.violation(timeline, sources))
        assertEquals(timeline, timeline.pruned())
    }

    // MARK: From the original

    /**
     * Tests/TrackTimelineTests.swift:254-262: "Equal placement depths retain
     * the timeline's stable picture order". Two pictures of the same take on
     * one layer, both with the same window.
     */
    @Test
    fun equalDepthsKeepTheTimelinesOrder() {
        val first = picture(1, 0.0, 8.0, 0)
        val other = picture(2, 0.0, 8.0, 0)
        val region = VideoPlacementRegion(participants = setOf(first.id, other.id), videos = mapOf(first.id to window, other.id to window))
        val timeline = TrackTimeline(video = listOf(first, other), placements = listOf(region))
        val canonical = timeline.video.sortedWith(VideoTimelineItem.backToFront).map { it.id }
        assertEquals(canonical, VideoPlacement.ordered(canonical, region.videos))
        assertEquals(canonical, TrackPlacements.order(timeline, region))
        settled(timeline)
    }

    /**
     * Tests/TrackTimelineTests.swift:263-267: "Arrange preview merges audio
     * subdivisions and finds the overlap even from an outside playhead". The
     * original unlinks and cuts the sounds at 3 s; the same timeline is
     * written out here.
     */
    @Test
    fun thePreviewStretchIgnoresCutsThatOnlySoundsMake() {
        val clip = TimelineClip(uuid(1), source.id, 0.0, 8.0)
        val converted = EditState(listOf(clip)).toTimeline()
        val other = converted.video[0].copy(occurrenceID = uuid(3), content = clip.copy(id = uuid(2)))
        val ids = setOf(clip.id, other.id)
        val sound = converted.audio[0].copy(followsVideo = null)
        val divided = converted.copy(
            video = converted.video + other,
            audio = listOf(sound.sliced(EditInterval(t(0.0), t(3.0)), sound.id), sound.sliced(EditInterval(t(3.0), t(8.0)), uuid(4))),
            links = emptyList(),
            placements = listOf(VideoPlacementRegion(participants = ids, videos = mapOf(clip.id to window, other.id to window))),
        )
        settled(divided)
        assertEquals(EditInterval.of(0.0, 8.0), TrackPlacements.previewInterval(divided, ids, 9.0, 1.0..2.0))
    }

    // MARK: The preview stretch

    @Test
    fun thePreviewStretchIsTheOneAtThePlayheadThenThePreferredThenTheFirst() {
        val twice = TrackTimeline(video = listOf(a, b))
        assertEquals(EditInterval.of(1.0, 2.0), TrackPlacements.previewInterval(twice, setOf(a.id, b.id), 9.0, 0.0..9.0))
        assertNull(TrackPlacements.previewInterval(twice, setOf(a.id, c.id), 1.5, 0.0..9.0))
        assertNull(TrackPlacements.previewInterval(twice, setOf(a.id), 1.5, 0.0..9.0))
        // Where a third picture shows, the set is another one.
        assertEquals(EditInterval.of(3.0, 5.0), TrackPlacements.previewInterval(stacked, setOf(b.id, c.id), 0.0, 0.0..1.0))
        assertEquals(EditInterval.of(1.0, 2.0), TrackPlacements.previewInterval(stacked, setOf(a.id, b.id), 4.0, 4.0..5.0))
    }

    // MARK: The seed

    @Test
    fun theSavedRegionOfExactlyThatSetIsTheSeed() {
        assertSame(ab, TrackPlacements.seed(stacked, setOf(a.id, b.id), t(1.5)))
    }

    @Test
    fun aNewSetIsWholeWhereNothingIsShared() {
        val seed = TrackPlacements.seed(stacked.copy(placements = emptyList()), setOf(b.id, c.id), t(3.5))
        assertEquals(setOf(b.id, c.id), seed.participants)
        assertEquals(TrackPlacements.defaults(listOf(b.id, c.id)), seed.videos)
    }

    @Test
    fun aThirdPictureDoesNotResetTheWindowsOfTheTwoItJoins() {
        // All three at once from 3 s to 4 s.
        val long = picture(10, 0.0, 4.0, 0)
        val timeline = stacked.copy(video = listOf(long, b, c))
        val seed = TrackPlacements.seed(timeline, setOf(a.id, b.id, c.id), t(3.5))
        assertEquals(VideoPlacement(order = 0), seed.videos[a.id])
        assertEquals(window.copy(order = 1), seed.videos[b.id])
        assertEquals(VideoPlacement(order = 2), seed.videos[c.id])
    }

    @Test
    fun theOrderOfAnOldRegionIsKeptAmongThePicturesItShares() {
        val swapped = ab.copy(videos = mapOf(a.id to VideoPlacement(order = 5), b.id to window.copy(order = 1)))
        val long = picture(10, 0.0, 4.0, 0)
        val seed = TrackPlacements.seed(stacked.copy(video = listOf(long, b, c), placements = listOf(swapped)), setOf(a.id, b.id, c.id), t(3.5))
        // B was behind A there, so it stays behind it; C keeps its place in front.
        assertEquals(mapOf(b.id to 0, a.id to 1, c.id to 2), seed.videos.mapValues { it.value.order })
        assertEquals(window.copy(order = 0), seed.videos[b.id])
    }

    @Test
    fun oneSharedPictureKeepsItsWindowOnlyAtHandOrWhenItIsTheOneMoving() {
        val pair = setOf(b.id, c.id)
        // The old region plays from 1 s to 2 s: one second away from 3 s.
        assertEquals(VideoPlacement(order = 0), TrackPlacements.seed(stacked, pair, t(3.0)).videos[b.id])
        assertEquals(window.copy(order = 0), TrackPlacements.seed(stacked, pair, t(3.0), moving = setOf(b.id)).videos[b.id])
        assertEquals(window.copy(order = 0), TrackPlacements.seed(stacked, pair, t(1.5)).videos[b.id])
        assertEquals(VideoPlacement(order = 1), TrackPlacements.seed(stacked, pair, t(3.0), moving = setOf(b.id)).videos[c.id])
    }

    // MARK: New overlaps

    @Test
    fun aMovedPictureTakesItsWindowIntoTheOverlapsItNowHas() {
        val kept = TrackPlacements.retainingAcrossNewOverlaps(stacked, setOf(b.id))
        assertEquals(2, kept.placements.size)
        val added = kept.placements[1]
        assertEquals(setOf(b.id, c.id), added.participants)
        assertEquals(window.copy(order = 0), added.videos[b.id])
        assertEquals(VideoPlacement(order = 1), added.videos[c.id])
        settled(kept)
        // Nothing to carry: the picture that moved has no window of its own.
        assertSame(stacked, TrackPlacements.retainingAcrossNewOverlaps(stacked, setOf(c.id)))
        assertSame(stacked, TrackPlacements.retainingAcrossNewOverlaps(stacked, emptySet()))
        val plain = stacked.copy(placements = emptyList())
        assertSame(plain, TrackPlacements.retainingAcrossNewOverlaps(plain, setOf(b.id)))
    }

    // MARK: Copies and cuts

    @Test
    fun aCopyKeepsTheWindowsOfThePicturesCopied() {
        val long = picture(10, 0.0, 4.0, 0)
        val all = VideoPlacementRegion(uuid(51), setOf(a.id, b.id, c.id), mapOf(a.id to VideoPlacement(), b.id to window, c.id to VideoPlacement(order = 2)))
        val timeline = TrackTimeline(video = listOf(long, b, c), placements = listOf(all))
        assertEquals(listOf(all), TrackPlacements.keeping(timeline, setOf(a.id, b.id, c.id)))
        val two = TrackPlacements.keeping(timeline, setOf(a.id, b.id)).single()
        assertEquals(setOf(a.id, b.id), two.participants)
        assertEquals(mapOf(a.id to VideoPlacement(), b.id to window), two.videos)
        assertTrue(two.id != all.id)
        assertEquals(emptyList<VideoPlacementRegion>(), TrackPlacements.keeping(timeline, setOf(b.id)))
        // A region saved for the two is not made a second time.
        assertEquals(listOf(ab), TrackPlacements.keeping(timeline.copy(placements = listOf(ab, all)), setOf(a.id, b.id)))
    }

    @Test
    fun aCutKeepsTheWindowsOnBothSides() {
        val children = mapOf(b.id to uuid(21))
        val carried = TrackPlacements.carriedAcrossSplit(listOf(ab), children).single()
        assertEquals(setOf(a.id, uuid(21)), carried.participants)
        assertEquals(mapOf(a.id to VideoPlacement(), uuid(21) to window.copy(order = 1)), carried.videos)
        assertTrue(carried.id != ab.id)
        assertEquals(emptyList<VideoPlacementRegion>(), TrackPlacements.carriedAcrossSplit(listOf(ab), mapOf(c.id to uuid(22))))
    }

    // MARK: Saving and removing

    @Test
    fun savingARegionReplacesTheOneOfItsSetAndTheTransitionBetweenItsPictures() {
        val changed = ab.copy(id = uuid(52), videos = ab.videos + (a.id to window))
        val saved = TrackPlacements.setting(stacked, changed)
        assertEquals(listOf(changed), saved.placements)
        settled(saved)

        // Two clips with a dissolve: a placement on the two takes its place.
        val first = TimelineClip(uuid(1), source.id, 0.0, 4.0)
        val second = TimelineClip(uuid(2), source.id, 4.0, 8.0)
        val blend = TimelineEditing.setJoin(EditState(listOf(first, second)), 1, TransitionSettings(duration = 1.0))!!.toTimeline()
        assertEquals(1, blend.transitions.size)
        val region = VideoPlacementRegion(participants = setOf(first.id, second.id), videos = TrackPlacements.defaults(listOf(first.id, second.id)))
        val placed = TrackPlacements.setting(blend, region)
        assertTrue(placed.transitions.isEmpty() && placed.generated.isEmpty())
        assertEquals(listOf(region), placed.placements)
        assertEquals(blend.video, placed.video)
        settled(placed)
    }

    @Test
    fun removingARegionLeavesThePicturesAsTheyAre() {
        val removed = TrackPlacements.removing(stacked, ab.id)
        assertTrue(removed.placements.isEmpty())
        assertEquals(stacked.video, removed.video)
        assertEquals(removed, TrackPlacements.removing(stacked, setOf(a.id, b.id)))
        assertSame(stacked, TrackPlacements.removing(stacked, uuid(99)))
        assertSame(stacked, TrackPlacements.removing(stacked, setOf(a.id, c.id)))
    }
}
