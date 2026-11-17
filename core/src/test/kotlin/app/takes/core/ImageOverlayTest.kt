package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The Image tool's pictures over the footage, as the original keeps, places and draws them
 * (YouRec/VideoTools.swift:84-98, YouRec/EditorTools.swift:259-329, :650-674, :1207-1254,
 * YouRec/VideoToolCompositor.swift:396-420). Swift lines are those of the original at faf1815.
 */
class ImageOverlayTest {
    private val tiny = 1e-9
    private val take = SourceMedia(id = "S", filename = "take.mp4", duration = 20.0, recordedAt = 5)

    private fun clip(id: String, start: Double = 0.0, end: Double = 4.0) = TimelineClip(id = id, sourceID = "S", start = start, end = end)
    private fun picture(id: String = "I", start: Double = 0.0, end: Double = 3.0) = ImageOverlay(id = id, filename = "image-$id.png", start = start, end = end)
    private val pair = EditState(listOf(clip("A"), clip("B", 4.0, 8.0)))

    // MARK: Saving

    /** A clip without pictures is written as before, so projects without them save byte for byte as they did. */
    @Test fun aClipWithoutPicturesIsWrittenAsBefore() {
        assertEquals("""{"id":"A","sourceID":"S","start":0.0,"end":4.0}""", documentJson.encodeToString(TimelineClip.serializer(), clip("A")))
        assertEquals(1, clip("A").schema)
        assertEquals(TimelineClip.IMAGES_SCHEMA, clip("A").copy(images = listOf(picture())).schema)
        assertEquals(1, clip("A").copy(images = emptyList()).schema)
    }

    /**
     * Saved with the clip, as Tests/EffectAnimationChecks.swift:45-75 saves a moving one; readers before schema 23
     * would leave the picture out and must refuse the project.
     */
    @Test fun aPictureIsSavedWithItsMotionAndOlderReadersRefuse() {
        val base = Project("Trip", id = "P", createdAt = 1).addRecording(take, now = 2)
        val start = picture(end = 4.0).copy(x = 0.2)
        val moving = start.copy(motion = EffectAnimation(start.pose, start.copy(x = 0.82, y = 0.75, width = 0.8, opacity = 0.5).pose, duration = 4.0))
        val edited = base.commit(ImageOverlays.place(base.edit, moving, 0.0..4.0, moving.motion)!!, now = 3)
        assertEquals(TimelineClip.IMAGES_SCHEMA, edited.schemaVersion)
        assertEquals(23, TimelineClip.IMAGES_SCHEMA)
        assertEquals(23, Project.LATEST_READABLE_SCHEMA)
        edited.validate()
        val files = mutableMapOf<String, ByteArray>()
        val manifest = edited.manifest { name, data -> files[name] = data }
        val reopened = Project.readManifest(manifest) { files.getValue(it) }
        assertEquals(edited.edit.clips, reopened.edit.clips)
        assertEquals(listOf("image-I.png"), reopened.imageFiles)
        assertEquals(listOf("take.mp4", "image-I.png"), reopened.mediaFiles)
        // The file stays the project's while an undone step still names it (YouRec/Project.swift:286).
        assertEquals(listOf("image-I.png"), reopened.undo().imageFiles)
        val newer = manifest.decodeToString().replaceFirst("\"schemaVersion\":23", "\"schemaVersion\":${Project.LATEST_READABLE_SCHEMA + 1}")
        try { Project.readManifest(newer.toByteArray()) { files.getValue(it) }; fail("opened") } catch (refused: ProjectException) {
            assertEquals(ProjectException.Reason.NewerVersion, refused.reason)
        }
    }

    /** YouRec/Project.swift:502-506, with a file name that stays in the project's folder. */
    @Test fun picturesOutsideTheOriginalsRangesAreRefused() {
        val base = Project("Trip", id = "P", createdAt = 1).addRecording(take, now = 2)
        fun refused(images: List<ImageOverlay>) {
            val state = base.edit.copy(clips = listOf(base.edit.clips[0].copy(images = images)))
            try { base.copy(snapshots = listOf(EditSnapshot(state, 1))).validate(); fail("accepted $images") } catch (refused: ProjectException) {
                assertEquals(ProjectException.Reason.InvalidDocument, refused.reason)
            }
        }
        assertTrue(picture().valid)
        assertTrue(picture().copy(width = 0.03, opacity = 0.0, x = 1.0, y = 0.0).valid && picture().copy(width = 3.0).valid)
        refused(listOf(picture(), picture()))
        refused(listOf(picture(end = 20.5)))
        refused(listOf(picture().copy(width = 0.02)))
        refused(listOf(picture().copy(width = 3.1)))
        refused(listOf(picture().copy(opacity = 1.5)))
        refused(listOf(picture().copy(x = Double.NaN)))
        refused(listOf(picture(start = 2.0, end = 2.0)))
        refused(listOf(picture().copy(filename = "../take.mp4")))
        refused(listOf(picture().copy(filename = ".hidden.png")))
        refused(listOf(picture().copy(filename = "")))
        val moving = picture().let { it.copy(motion = EffectAnimation(it.pose, it.copy(width = 3.5).pose, duration = 3.0)) }
        refused(listOf(moving))
    }

    // MARK: Drawing

    /** YouRec/VideoTools.swift:94-97: the width a share of the shorter edge, the height by the picture's aspect, about its centre. */
    @Test fun aPictureIsPlacedByItsCentreOnTheShorterEdge() {
        val image = ImageOverlay(filename = "a.png", x = 0.75, y = 0.25)
        assertEquals(Rect(480.0 - 72.0, 270.0 - 36.0, 144.0, 72.0), image.rect(Size(200.0, 100.0), Size(640.0, 360.0)))
        // Top down: a quarter from the top.
        assertEquals(Rect(480.0 - 72.0, 90.0 - 36.0, 144.0, 72.0), image.frame(Size(200.0, 100.0), Size(640.0, 360.0)))
        // Upright: the width of the frame is its shorter edge.
        assertEquals(Rect(90.0 - 144.0, 160.0 - 72.0, 288.0, 144.0), image.copy(x = 0.25, y = 0.25, width = 0.8).frame(Size(200.0, 100.0), Size(360.0, 640.0)))
    }

    /**
     * A picture shows from its start up to its end in the footage (YouRec/VideoToolCompositor.swift:402), as
     * Tests/VideoToolsTests.swift:723-735 exports one from 1 s to 2 s: there at 1.5 s, gone at 0.5 s and 2.5 s.
     */
    @Test fun aPictureShowsOverItsFootageOnly() {
        val shown = clip("A").copy(images = listOf(picture(start = 1.0, end = 2.0)))
        assertEquals(listOf("I"), ImageOverlays.shownAt(shown, 1.5).map { it.id })
        assertEquals(listOf("I"), ImageOverlays.shownAt(shown, 1.0).map { it.id })
        for (offset in listOf(0.5, 2.0, 2.5)) assertTrue(ImageOverlays.shownAt(shown, offset).isEmpty())
        // On a clip played twice as fast, over half the time.
        val fast = shown.copy(speed = SpeedSettings(rate = 2.0))
        assertEquals(listOf("I"), ImageOverlays.shownAt(fast, 0.75).map { it.id })
        assertTrue(ImageOverlays.shownAt(fast, 1.5).isEmpty())
    }

    /** Its motion runs on the footage's clock (VideoToolCompositor.swift:403-404), and over a held frame on the hold's (:169-172). */
    @Test fun aMovingPictureIsPosedOnTheClockOfItsFootage() {
        val still = picture(end = 4.0).copy(x = 0.2)
        val motion = EffectAnimation(still.pose, still.copy(x = 0.8).pose, duration = 4.0)
        val placed = ImageOverlays.place(EditState(listOf(clip("A"))), still, 0.0..4.0, motion)!!.clips[0]
        val image = placed.images!!.single()
        assertEquals(0.2, image.x, tiny)
        assertEquals(0.2, ImageOverlays.shownAt(placed, 0.0).single().x, tiny)
        assertEquals(0.5, ImageOverlays.shownAt(placed, (4.0 - PauseDetector.FRAME) / 2).single().x, 1e-6)
        assertNull(ImageOverlays.shownAt(placed, 1.0).single().motion)
        // A held frame shows one second of its footage, but the motion keeps running over the hold.
        val hold = FrozenFrameSegment(sourceTime = 1.0, duration = 2.0)
        val heldPlaced = clip("H", 1.0, 1.5).copy(frozenFrame = hold, images = listOf(still.copy(motion = motion.copy(duration = 2.0).anchored(hold, 0.0))))
        val a = ImageOverlays.shownAt(heldPlaced, 0.0).single().x
        val b = ImageOverlays.shownAt(heldPlaced, 1.0).single().x
        assertEquals(0.2, a, tiny)
        assertTrue("$b", b > 0.4 && b < 0.6)
    }

    /**
     * Each clip's pictures go into its side under its titles before the transition combines the two sides, as each
     * clip is rendered with its overlays (YouRec/VideoToolCompositor.swift:282, :396-420); for a split screen those
     * of both once over the pair (:298-301).
     */
    @Test fun picturesAreDrawnUnderTheTitlesOfTheirSide() {
        val title = TextOverlay(id = "T", text = "One", start = 0.0, end = 4.0)
        val image = picture(start = 0.0, end = 4.0)
        var state = TimelineEditing.setText(pair, 0, title)!!
        state = state.copy(clips = state.clips.mapIndexed { index, clip -> clip.copy(images = listOf(image.copy(id = "I$index", filename = "image-$index.png", start = clip.start, end = clip.end))) })
        val dissolved = TimelineEditing.setJoin(state, 1, TransitionSettings(duration = 2.0))!!
        val plan = TrackRenderPlan.of(dissolved)
        assertTrue(plan.titled)
        val sides = plan.titleSides(3.0)
        assertEquals(listOf("image-0.png", null), sides.first.map { it.image?.filename })
        assertEquals(listOf(null, "One"), sides.first.map { it.title?.text })
        assertEquals(listOf("image-1.png"), sides.second.map { it.image?.filename })
        // Without a shader, over the picture: each by its clip's share, the pictures under the titles.
        val over = plan.titlesShown(3.0)
        assertEquals(listOf("image-0.png", "image-1.png", null), over.map { it.image?.filename })
        assertEquals(0.5, over[0].share, 1e-6)
        assertEquals(0.5, over[1].share, 1e-6)
        // The same picture on both sides of a dissolve is drawn once, whole.
        val same = dissolved.copy(clips = dissolved.clips.map { it.copy(images = listOf(image.copy(end = 8.0))) })
        assertEquals(listOf(1.0), TrackRenderPlan.of(same).titlesShown(3.0).filter { it.image != null }.map { it.share })
        // A split screen: none in either side, both once over the pair.
        val split = TrackRenderPlan.of(TimelineEditing.setJoin(same, 1, TransitionSettings(style = TransitionStyle.Split, duration = 2.0))!!)
        val pairSides = split.titleSides(3.0)
        assertTrue(pairSides.first.isEmpty() && pairSides.second.isEmpty())
        assertEquals(listOf("image-I.png", null), pairSides.over.map { it.image?.filename })
        // A clip by itself, as picture layers draw each lane.
        val alone = listOf(state.clips[0])
        assertEquals(listOf("image-0.png", null), TitleMotionRanges(alone).shown(alone, 1.0).map { it.image?.filename })
        // A clip without pictures or titles draws nothing.
        assertFalse(TrackRenderPlan.of(pair).titled)
    }

    // MARK: Placing

    /**
     * A new picture goes over the seconds of the movie it is given, on every clip they cover, in the footage each
     * shows then (YouRec/EditorTools.swift:665-670).
     */
    @Test fun aNewPictureIsPlacedOnEveryClipItsSecondsCover() {
        val placed = ImageOverlays.place(pair, picture(), 3.0..5.0)!!
        assertEquals(listOf(3.0 to 4.0), placed.clips[0].images!!.map { it.start to it.end })
        assertEquals(listOf(4.0 to 5.0), placed.clips[1].images!!.map { it.start to it.end })
        assertEquals(setOf("I"), placed.clips.flatMap { it.images.orEmpty() }.map { it.id }.toSet())
        // On the piece it was opened on, with the range as it was, the footage seconds are kept; a new range moves it.
        val kept = ImageOverlays.place(placed, picture().copy(opacity = 0.5), 3.0..4.0, scope = setOf("A"), opened = 3.0..4.0)!!
        assertEquals(listOf(Triple(3.0, 4.0, 0.5)), kept.clips[0].images!!.map { Triple(it.start, it.end, it.opacity) })
        assertEquals(1.0, kept.clips[1].images!!.single().opacity, tiny)
        val moved = ImageOverlays.place(placed, picture(), 2.0..4.0, scope = setOf("A"), opened = 3.0..4.0)!!
        assertEquals(listOf(2.0 to 4.0), moved.clips[0].images!!.map { it.start to it.end })
        // Nothing changes: no edit.
        assertNull(ImageOverlays.place(placed, picture(), 3.0..4.0, scope = setOf("A"), opened = 3.0..4.0))
        // Changed, it is taken off and put back on top of the clip's others (EditorTools.swift:662-669).
        val two = ImageOverlays.place(placed, picture("J"), 3.0..4.0)!!
        val restacked = ImageOverlays.place(two, picture().copy(opacity = 0.4), 3.0..4.0, scope = setOf("A"), opened = 3.0..4.0)!!
        assertEquals(listOf("J", "I"), restacked.clips[0].images!!.map { it.id })
        // Removed from the piece it was opened on only (VideoTools.swift:539).
        val removed = ImageOverlays.remove(placed, 0, "I")!!
        assertNull(removed.clips[0].images)
        assertNotNull(removed.clips[1].images)
        assertNull(ImageOverlays.remove(removed, 0, "I"))
        // Where each piece shows it.
        assertEquals(3.0..4.0, ImageOverlays.range(placed.clips, 0, placed.clips[0].images!!.single()))
        assertEquals(4.0..5.0, ImageOverlays.range(placed.clips, 1, placed.clips[1].images!!.single()))
        // A picture that cannot be saved is not placed.
        assertNull(ImageOverlays.place(pair, picture().copy(filename = ""), 0.0..1.0))
    }

    /**
     * A moving picture keeps its A pose in its fields, and each clip's copy has the motion tied to where the clip starts
     * from the start of the picture's seconds (EditorTools.swift:658-660); re-applied unchanged it keeps its clock.
     */
    @Test fun aMotionIsTiedToEachClipItCovers() {
        val still = picture(end = 4.0)
        val motion = EffectAnimation(still.pose, still.copy(x = 0.9).pose, duration = 4.0)
        val placed = ImageOverlays.place(pair, still, 2.0..6.0, motion)!!
        val first = placed.clips[0].images!!.single()
        val second = placed.clips[1].images!!.single()
        assertEquals(0.5, first.x, tiny)
        assertEquals(listOf(StrobeTimePoint(0.0, -2.0), StrobeTimePoint(4.0, 2.0)), first.motion!!.clock)
        assertEquals(listOf(StrobeTimePoint(4.0, 2.0), StrobeTimePoint(8.0, 6.0)), second.motion!!.clock)
        // The pose runs on across the cut.
        val before = ImageOverlays.shownAt(placed.clips[0], 4.0 - 1e-4).single().x
        val after = ImageOverlays.shownAt(placed.clips[1], 0.0).single().x
        assertEquals(before, after, 1e-4)
        // Opened again on the first piece and applied as it was: the clock it had is kept.
        val again = ImageOverlays.place(placed, ImageDraft.opening(first).image, 2.0..4.0, ImageDraft.opening(first).captured(2.0)!!.copy(duration = 4.0), setOf("A"), 2.0..4.0)
        assertNull(again)
    }

    /** Both pieces of a split keep the picture, as every other setting of the clip (Tests/VideoToolsTests.swift:828-833). */
    @Test fun bothPiecesOfASplitKeepThePicture() {
        val state = EditState(listOf(clip("A", 0.0, 8.0).copy(images = listOf(picture(end = 8.0)))))
        val split = TimelineEditing.split(state, 4.0)!!
        assertEquals(2, split.clips.size)
        assertTrue(split.clips.all { it.images == listOf(picture(end = 8.0)) })
        assertEquals(listOf("I"), ImageOverlays.shownAt(split.clips[1], 1.0).map { it.id })
    }

    /** A pasted clip is given its own picture ids, as YouRec/TimelineEditing.swift:112 and TrackTimeline.swift:1554 give them. */
    @Test fun aPastedClipHasPicturesOfItsOwn() {
        val state = EditState(listOf(clip("A").copy(images = listOf(picture()))))
        val fragment = ClipFragment(state.clips)
        val pasted = RangeEditing.paste(state, fragment, 4.0)!!.state
        val ids = pasted.clips.flatMap { it.images.orEmpty() }.map { it.id }
        assertEquals(2, ids.size)
        assertEquals(2, ids.toSet().size)
        assertEquals(setOf("image-I.png"), pasted.clips.flatMap { it.images.orEmpty() }.map { it.filename }.toSet())
    }

    /** A picture that shows is a change the editor can go to (YouRec/TimelineNavigation.swift:103); a transparent one is not. */
    @Test fun aPictureIsAChange() {
        val shown = EditState(listOf(clip("A", 0.0, 8.0).copy(images = listOf(picture(start = 2.0, end = 5.0)))))
        val navigation = TimelineNavigation.of(shown)
        assertEquals(2.0, navigation.destination(0.0, true)!!, 1e-6)
        val hidden = EditState(listOf(clip("A", 0.0, 8.0).copy(images = listOf(picture(start = 2.0, end = 5.0).copy(opacity = 0.0)))))
        assertNotEquals(2.0, TimelineNavigation.of(hidden).destination(0.0, true) ?: -1.0, 1e-6)
    }

    // MARK: The tool

    /** The A and B poses of the tool (EditorTools.swift:263-308). */
    @Test fun theToolEditsEachEndOfTheMotion() {
        var draft = ImageDraft(picture())
        assertNull(draft.captured(3.0))
        // The first end chosen makes a motion whose two ends are the pose on screen, and parks the preview on it.
        draft = draft.selecting(EffectEndpoint.B, 3.0)
        assertEquals(EffectEndpoint.B, draft.previewEndpoint)
        assertEquals(draft.image.pose, draft.captured(3.0)!!.a)
        draft = draft.adjusted(draft.image.copy(x = 0.9))
        assertEquals(0.9, draft.captured(3.0)!!.b["x"], tiny)
        assertEquals(0.5, draft.captured(3.0)!!.a["x"], tiny)
        // Parked on an end the preview shows its pose, still; B is at the last frame (EffectAnimation timing).
        assertNull(draft.shownMotion(3.0))
        assertEquals(3.0 - PauseDetector.FRAME, draft.parked(3.0)!!, 1e-9)
        // Back to A loads its pose.
        draft = draft.selecting(EffectEndpoint.A, 3.0)
        assertEquals(0.5, draft.image.x, tiny)
        assertEquals(0.0, draft.parked(3.0)!!, tiny)
        // Scrubbed, the motion runs; a change then edits the end last chosen and parks there again.
        draft = draft.scrubbed()
        assertNotNull(draft.shownMotion(3.0))
        draft = draft.adjusted(draft.image.copy(y = 0.1))
        assertEquals(EffectEndpoint.A, draft.previewEndpoint)
        assertEquals(0.1, draft.captured(3.0)!!.a["y"], tiny)
        // Done keeps the motion; Remove animation keeps the pose on screen.
        val done = draft.finishing(3.0)
        assertNull(done.endpoint)
        assertEquals(0.9, done.animation!!.b["x"], tiny)
        val curved = done.retimed(EffectTiming(curve = EffectCurve.EaseIn), 3.0)
        assertEquals(EffectCurve.EaseIn, curved.animation!!.timing.curve)
        val plain = done.removingAnimation()
        assertNull(plain.animation)
        assertEquals(0.1, plain.image.y, tiny)
        // Opened on a saved moving picture: its A pose, its motion set aside.
        val saved = picture().copy(motion = done.animation)
        val opened = ImageDraft.opening(saved)
        assertNull(opened.image.motion)
        assertEquals(0.1, opened.image.y, tiny)
        assertEquals(done.animation, opened.animation)
    }

    @Test fun positionsNearTheMiddleSnapToIt() {
        // EditorTools.swift:1225-1226.
        assertEquals(0.5, ImageOverlay.snapped(0.51), tiny)
        assertEquals(0.52, ImageOverlay.snapped(0.52), tiny)
        assertTrue(ImageOverlay.importedName("X") == "image-X.png" && isProjectFileName(ImageOverlay.importedName()))
    }
}
