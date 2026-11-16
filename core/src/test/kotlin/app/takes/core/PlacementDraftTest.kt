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
 * with the persistence half of `placementCropTests` (Tests/TrackTimelineTests.swift:255-323),
 * the edge crop, reframing and the A/B motion of the tool.
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

    private fun reopened(project: Project): Project {
        val files = HashMap<String, ByteArray>()
        val manifest = project.manifest { name, data -> files[name] = data }
        return Project.readManifest(manifest) { files.getValue(it) }.also { it.validate() }
    }

    /**
     * Tests/TrackTimelineTests.swift:314-323: a crop committed on one of two
     * placed pictures; Undo takes away the crop and nothing else, and Redo
     * after reopening the saved project brings back exactly the edit. The
     * original raises its schema to 7 for a crop, which earlier readers would
     * drop; here the crop needs nothing above [TrackTimeline.LAYERED_SCHEMA],
     * the number of any timeline where pictures play together, because every
     * release that reads such a timeline reads and draws the crop.
     */
    @Test
    fun aCropIsOneUndoStepThatSurvivesTheSavedProject() {
        val original = VideoPlacement(x = 0.6, y = 0.4, width = 0.8, height = 0.6, framing = TransitionFraming(x = 0.2, y = 0.7, zoom = 2.0), order = 3)
        val ids = setOf(clips[0].id, clips[1].id)
        val placed = layered.copy(placements = listOf(VideoPlacementRegion(participants = ids, videos = mapOf(clips[0].id to original, clips[1].id to original))))
        var project = Project(title = "Placement crop Undo", sources = listOf(source), snapshots = listOf(EditSnapshot.of(sequence, 0))).commit(placed)
        val crop = PlacementEdgeCrop(left = 0.2, right = 0.1, top = 0.15, bottom = 0.25)
        val cropped = placed.copy(placements = listOf(placed.placements[0].copy(videos = placed.placements[0].videos + (clips[1].id to original.copy(edgeCrop = crop)))))
        project = project.commit(cropped)
        project.validate()
        assertEquals(TrackTimeline.LAYERED_SCHEMA, project.schemaVersion)
        val state = project.edit
        project = project.undo()
        assertNull("Undo restores crop without changing the overlapping pictures", project.edit.toTimeline().placements[0].videos.getValue(clips[1].id).edgeCrop)
        assertEquals(placed.video, project.edit.toTimeline().video)
        project = reopened(project).redo()
        assertEquals("Placement crop survives saved Redo and reopening", state, project.edit)
        assertEquals(crop, project.edit.toTimeline().placements[0].videos.getValue(clips[1].id).edgeCrop)
    }

    /** The edge handles, Reset crop and the layouts in the tool (YouRec/TrackEditors.swift:631-652, :689-690, :831-838). */
    @Test
    fun theToolCropsAnEdgeResetsItAndALayoutResetsCrop() {
        val canvas = Size(400.0, 225.0)
        val draft = PlacementDraft.open(layered, setOf(clips[0].id, clips[1].id), 3.0)!!.laidOut(PlacementLayout.SideBySide)
        val front = draft.current!!
        // The right picture's left edge dragged a quarter of its width inwards.
        val window = front.pictureWindow(canvas)
        val cut = draft.changing { PlacementEditing.edgeDragged(front, PlacementCropEdge.Left, (window.minX + window.width / 4) / canvas.width, canvas) }
        assertEquals(0.25, cut.current!!.edgeCrop!!.left, 1e-9)
        assertEquals("Cropping moves and scales nothing", front, cut.current!!.copy(edgeCrop = null))
        val saved = cut.applied(layered).placements.single().videos.getValue(clips[1].id)
        assertEquals(0.25, saved.edgeCrop!!.left, 1e-9)
        // Reset crop keeps the window, framing and order; a layout resets the crop too.
        val framed = cut.changing { it.copy(framing = TransitionFraming(x = 0.3, y = 0.4, zoom = 2.0)) }
        assertEquals(framed.current!!.copy(edgeCrop = null), framed.cropReset().current)
        assertNull(cut.laidOut(PlacementLayout.SideBySide).current!!.edgeCrop)
        // One accessibility step on an edge cuts two hundredths more.
        assertEquals(0.27, cut.changing { PlacementEditing.edgeStepped(it, PlacementCropEdge.Left, PlacementEditing.EDGE_STEP, canvas) }.current!!.edgeCrop!!.left, 1e-9)
    }

    /** "Reframe inside window": the picture moves and zooms inside a window that stays (YouRec/TrackEditors.swift:592-596, :613). */
    @Test
    fun reframingMovesThePictureAndNotTheWindow() {
        val canvas = Size(400.0, 225.0)
        val draft = PlacementDraft.open(layered, setOf(clips[0].id, clips[1].id), 3.0)!!.laidOut(PlacementLayout.PictureInPicture).reframing(true)
        assertTrue(draft.reframe)
        val start = draft.current!!
        val zoomed = draft.changing { PlacementEditing.zoomed(start, it, 2.0) }
        assertEquals(2.0, zoomed.current!!.framing.zoom, 1e-12)
        val moved = zoomed.changing { PlacementEditing.reframed(start, it, Point(-10.0, 0.0), canvas) }.current!!
        assertTrue(moved.framing.x > 0.5)
        assertEquals(start.window(canvas), moved.window(canvas))
        assertFalse(draft.reframing(false).reframe)
    }

    /**
     * The A/B motion of the windows (YouRec/TrackEditors.swift:500-565): the
     * ends chosen and changed, Apply writes the A end into the window with
     * the motion beside it, its clock starting with the overlap, and the
     * movie shows the window of the motion at each moment
     * (Tests/EffectAnimationChecks.swift:163-203, "Animated placement").
     */
    @Test
    fun theWindowsMoveFromAToBOverTheOverlap() {
        val opened = PlacementDraft.open(layered, setOf(clips[0].id, clips[1].id), 3.0)!!
        assertEquals(2.0, opened.lower, 1e-9)
        assertEquals(4.0 - PlacementDraft.FRAME, opened.upper, 1e-9)
        assertEquals(2.0, opened.motionDuration, 1e-9)
        assertNull(opened.menuMotion)
        assertNull(opened.parkedAt())
        val a = opened.laidOut(PlacementLayout.PictureInPicture).selectingEnd(EffectEndpoint.A)
        assertEquals(EffectEndpoint.A, a.endpoint)
        assertEquals("Parked on A at the start of the overlap", 2.0, a.parkedAt()!!, 1e-9)
        assertEquals(setOf(clips[0].id, clips[1].id), a.motions.keys)
        // B starts as A; the front window moved to the top left there.
        val b = a.selectingEnd(EffectEndpoint.B)
        assertEquals("Parked on B at the last frame", 4.0 - PlacementDraft.FRAME, b.parkedAt()!!, 1e-9)
        val pip = b.current!!
        val moved = b.changing { it.copy(x = 0.2, y = 0.2) }
        val timeline = moved.finishing().applied(layered)
        val front = timeline.placements.single().videos.getValue(clips[1].id)
        assertEquals("The saved window is the A end", pip.x, front.x, 1e-12)
        val motion = front.motion!!
        assertEquals(2.0, motion.duration, 1e-9)
        assertEquals(0.2, motion.b["x"], 1e-12)
        // The clock starts with the overlap: the front picture's first frame, source second 4, and the back one's at its source second 2.
        assertEquals(0.0, motion.elapsed(4.0), 1e-9)
        assertEquals(0.0, timeline.placements.single().videos.getValue(clips[0].id).motion!!.elapsed(2.0), 1e-9)
        // The movie shows the motion at every moment.
        val plan = LayerPlan.planned(timeline, 3)!!
        val canvas = Size(1920.0, 1080.0)
        for (time in listOf(2.0, 2.5, 3.0, 3.9)) {
            val layer = plan.moment(time, canvas).layers.last() as LayerPlan.Layer.Whole
            val expected = PlacementRendering.placed(front.applying(motion.pose(4.0 + time - 2.0)), canvas)
            assertEquals("Animated placement at $time", expected, layer.window)
        }
        // Opening again finds the motion; applying it unchanged changes nothing, and keeps the clock.
        val again = PlacementDraft.open(timeline, setOf(clips[0].id, clips[1].id), 3.0)!!
        assertEquals(motion, again.motions[clips[1].id])
        assertSame(timeline, again.applied(timeline))
        assertSame(timeline, again.selectingEnd(EffectEndpoint.B).finishing().applied(timeline))
        // A change with the preview off the ends edits the end last chosen and parks there.
        val edited = again.selectingEnd(EffectEndpoint.B).scrubbed().changing { it.copy(width = 0.5, height = 0.5) }
        assertEquals(EffectEndpoint.B, edited.previewEndpoint)
        // Retiming moves the parked B onto the peak.
        val risen = edited.retimed(EffectTiming(curve = EffectCurve.RiseAndHold, rise = 1.0))
        assertEquals(3.0, risen.parkedAt()!!, 1e-9)
        assertEquals(EffectCurve.RiseAndHold, risen.applied(timeline).placements.single().videos.getValue(clips[0].id).motion!!.timing.curve)
        // Remove animation leaves the windows as they are shown, fixed.
        val fixed = edited.removingAnimation()
        assertTrue(fixed.motions.isEmpty())
        val still = fixed.applied(timeline).placements.single().videos
        assertTrue(still.values.all { it.motion == null })
        assertEquals(0.5, still.getValue(clips[1].id).width, 1e-12)
        // Play starts from the overlap's start while an end is edited; stopping goes back to it.
        assertEquals(2.0, edited.playFrom(3.0), 1e-9)
        assertEquals(3.0, edited.finishing().playFrom(3.0), 1e-9)
        assertEquals(EffectEndpoint.B, edited.scrubbed().stopped().previewEndpoint)
    }

    @Test
    fun fewerThanTwoPicturesOpenNothing() {
        assertNull(PlacementDraft.open(layered, setOf(clips[0].id), 3.0))
        assertNull(PlacementDraft.open(layered, setOf("missing", clips[0].id), 3.0))
    }
}
