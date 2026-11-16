package app.takes.core

import app.takes.core.TrackFixtures.uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Placement tool's draft and where the bubble is offered, as the
 * original's editor (YouRec/TrackEditors.swift:440-841, YouRec/VideoTools.swift:612-622),
 * with the persistence half of `placementCropTests` (Tests/TrackTimelineTests.swift:255-323)
 * minus the crop.
 */
class PlacementDraftTest {
    private val source = SourceMedia(id = uuid(6), filename = "multi.mp4", duration = 12.0)
    private val clips = (0 until 3).map { TimelineClip(uuid(40 + it), source.id, it * 4.0, (it + 1) * 4.0) }
    private val sequence = EditState(clips).toTimeline()

    /** The second clip moved two seconds earlier, over the end of the first: an overlap from 2 to 4 s. */
    private val layered: TrackTimeline = run {
        val session = TrackMoveSession.begin(sequence, sequence.selection(clips[1].id), 0.0, 60.0)!!.dragged(-2.0, snapping = false)
        (session.done(sequence) as TrackMoveSession.Outcome.Moved).timeline
    }

    @Test
    fun theBubbleIsOfferedOnAnOverlapAndNotOnASequenceOrADissolve() {
        assertTrue(PlacementSpots.of(sequence).isEmpty())
        val spots = PlacementSpots.of(layered)
        assertEquals(1, spots.size)
        assertEquals(EditInterval.of(2.0, 4.0), spots[0].interval)
        // The moved picture is in front.
        assertEquals(listOf(clips[0].id, clips[1].id), spots[0].video)
        assertNull(spots[0].placementID)
        assertNotNull(PlacementSpots.at(layered, 3.0))
        assertNull(PlacementSpots.at(layered, 1.0))
        assertNull(PlacementSpots.at(layered, 4.0))
        // A dissolve plays two pictures at once and is edited as a transition.
        val dissolved = TimelineEditing.setJoin(EditState(clips), 1, TransitionSettings(style = TransitionStyle.Dissolve, duration = 1.0))!!.toTimeline()
        assertTrue(dissolved.compile().any { it.video.size == 2 })
        assertTrue(PlacementSpots.of(dissolved).isEmpty())
    }

    @Test
    fun aPresetSavedIsOneRegionThatSurvivesUndoAndTheSavedBytes() {
        val spot = PlacementSpots.at(layered, 3.0)!!
        val draft = PlacementDraft.open(layered, spot.participants, 3.0)!!
        assertEquals(clips[1].id, draft.selected)
        assertFalse(draft.inheritedSeed)
        // Unchanged, Apply changes nothing.
        assertSame(layered, draft.applied(layered))
        val side = draft.laidOut(PlacementLayout.SideBySide)
        val saved = side.applied(layered)
        assertEquals(1, saved.placements.size)
        val region = saved.placements.single()
        assertEquals(spot.participants, region.participants)
        assertEquals(0.25, region.videos.getValue(clips[0].id).x, 1e-9)
        assertEquals(0.75, region.videos.getValue(clips[1].id).x, 1e-9)
        assertEquals(0.5, region.videos.getValue(clips[1].id).width, 1e-9)
        // The region is in force where exactly those pictures play.
        assertEquals(region.id, PlacementSpots.at(saved, 3.0)!!.placementID)
        // It reads back from its bytes as it was written.
        assertEquals(saved, TrackTimeline.decoded(saved.encoded()))
        // Opening again starts from what was saved, and Remove draws both whole.
        val again = PlacementDraft.open(saved, spot.participants, 3.0)!!
        assertEquals(region, again.region)
        assertTrue(again.removed(saved).placements.isEmpty())
    }

    @Test
    fun pictureInPictureOrderFitAndCenter() {
        val draft = PlacementDraft.open(layered, setOf(clips[0].id, clips[1].id), 3.0)!!.laidOut(PlacementLayout.PictureInPicture)
        val front = draft.region.videos.getValue(clips[1].id)
        assertTrue(front.width < 0.5 && front.x > 0.5 && front.y > 0.5)
        assertEquals(1.0, draft.region.videos.getValue(clips[0].id).width, 1e-9)
        // Sending the small picture to the back puts the whole one in front of it.
        val back = draft.sentToBack()
        assertEquals(listOf(clips[1].id, clips[0].id), back.order)
        assertEquals(listOf(clips[0].id, clips[1].id), back.selecting(clips[1].id).broughtToFront().order)
        assertFalse(draft.filling(false).current!!.fill)
        val framed = draft.changing { it.copy(framing = TransitionFraming(x = 0.2, y = 0.3, zoom = 2.0)) }
        assertEquals(TransitionFraming(), framed.centered().current!!.framing)
        // A drag of the window keeps a touchable part on the canvas.
        val canvas = Size(400.0, 225.0)
        val dragged = draft.changing { PlacementEditing.moved(it, it, Point(-1000.0, 0.0), canvas) }.current!!
        assertTrue(dragged.visibleWindow(canvas).maxX >= PlacementEditing.TOUCH - 1e-6)
    }

    @Test
    fun applyReplacesATransitionBetweenThePictures() {
        val draft = PlacementDraft.open(layered, setOf(clips[0].id, clips[1].id), 3.0)!!
        assertFalse(draft.replacesTransition(layered))
        val relation = TimelineTransition(clips[0].id, clips[1].id, TransitionSettings(style = TransitionStyle.Dissolve, duration = 2.0), EditTime.of(2.0))
        val withTransition = layered.copy(transitions = listOf(relation))
        assertTrue(draft.replacesTransition(withTransition))
        assertTrue(draft.laidOut(PlacementLayout.TopAndBottom).applied(withTransition).transitions.isEmpty())
    }

    @Test
    fun fewerThanTwoPicturesOpenNothing() {
        assertNull(PlacementDraft.open(layered, setOf(clips[0].id), 3.0))
        assertNull(PlacementDraft.open(layered, setOf("missing", clips[0].id), 3.0))
    }
}
