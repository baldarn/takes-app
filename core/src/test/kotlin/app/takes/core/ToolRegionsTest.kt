package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegionsTest {
    private val grade = ColorSettings(id = "grade", look = ColorLook.Warm)
    private val title = TextOverlay(id = "title", text = "Hello", start = 3.0, end = 5.0)
    private val a = TimelineClip(id = "a", sourceID = "A", start = 0.0, end = 4.0, crop = CropSettings(id = "crop", zoom = 1.5))
    private val b = TimelineClip(id = "b", sourceID = "B", start = 2.0, end = 8.0, color = grade, texts = listOf(title))

    // YouRec/VideoTools.swift:578-595: picture by picture its crop, grade and titles; a title is the part of it the clip
    // shows, at its place on the timeline, the others the whole clip.
    @Test fun regionsArePictureByPictureWhereTheyPlay() {
        val regions = ToolRegions.of(EditState(listOf(a, b)))
        assertEquals(listOf(EditorTool.Crop, EditorTool.Color, EditorTool.Text), regions.map { it.tool })
        val (crop, color, text) = regions
        assertEquals(0.0 to 4.0, crop.start to crop.end)
        assertEquals(ToolRegionLabel.Framing(1.5, 0.0), crop.label)
        assertEquals(4.0 to 10.0, color.start to color.end)
        assertEquals("grade", color.effectID)
        // Source 3 to 5 of a clip that begins at source 2 and at timeline 4.
        assertEquals(5.0, text.start, 1e-9)
        assertEquals(7.0, text.end, 1e-9)
        assertEquals(ToolRegionLabel.Text("Hello"), text.label)
    }

    // YouRec/VideoTools.swift:455-456: effect identity carries across a cut; both pieces of a split clip keep the grade's id,
    // and "In this project" lists it once (YouRec/EditorView.swift:977-989).
    @Test fun aSplitClipKeepsOneEffectListedOnce() {
        val split = TimelineEditing.split(EditState(listOf(a, b)), 7.0)!!
        val colors = ToolRegions.of(split).filter { it.tool == EditorTool.Color }
        assertEquals(2, colors.size)
        assertEquals(setOf("grade"), colors.map { it.effectID }.toSet())
        val listed = ToolRegions.listed(ToolRegions.of(split), time = 8.0)
        assertEquals(1, listed.count { it.tool == EditorTool.Color })
        // The one at the playhead comes first.
        assertEquals(EditorTool.Color, listed.first().tool)
        assertEquals(7.0, listed.first().start, 1e-9)
    }

    // YouRec/EditorView.swift:980-982: with a selection, only the regions of the items selected, inside its range.
    @Test fun theListFollowsTheSelection() {
        val regions = ToolRegions.of(EditState(listOf(a, b)))
        assertEquals(listOf(EditorTool.Crop), ToolRegions.listed(regions, 0.0, selected = setOf("a")).map { it.tool })
        assertEquals(listOf(EditorTool.Color), ToolRegions.listed(regions, 0.0, selected = setOf("b"), range = 8.0..9.0).map { it.tool })
    }

    // YouRec/EditorView.swift:627-629: a placement plays up to its end, the other regions to it and including it.
    @Test fun theChipsAtThePlayhead() {
        val regions = ToolRegions.of(EditState(listOf(a, b)))
        assertEquals(listOf(EditorTool.Crop, EditorTool.Color), ToolRegions.at(regions, 4.0).map { it.tool })
        assertEquals(listOf(EditorTool.Color, EditorTool.Text), ToolRegions.at(regions, 6.0).map { it.tool })
        val placement = ToolRegion(EditorTool.Placement, "p", "a", 1.0, 2.0, ToolRegionLabel.Placement(true))
        assertTrue(placement.contains(1.0))
        assertFalse(placement.contains(2.0))
        assertTrue(regions[0].contains(4.0))
    }

    // YouRec/VideoTools.swift:455-456, 526-548: a chip removes its effect from its own piece; the other piece keeps it.
    @Test fun removingStaysOnItsPiece() {
        val split = TimelineEditing.split(EditState(listOf(a, b)), 7.0)!!
        val first = ToolRegions.of(split).first { it.tool == EditorTool.Color }
        val removed = ToolRegions.removing(split, first)!!
        assertEquals(listOf(null, grade), removed.clips.drop(1).map { it.color })
        val title = ToolRegions.of(split).first { it.tool == EditorTool.Text }
        assertNull(ToolRegions.removing(split, title)!!.clips.first { it.id == title.clipID }.texts)
        val crop = ToolRegions.of(split).first { it.tool == EditorTool.Crop }
        assertNull(ToolRegions.removing(split, crop)!!.clips[0].crop)
        // Nothing to take away: nothing changes.
        assertNull(ToolRegions.removing(split, first.copy(effectID = "other")))
    }

    // YouRec/VideoTools.swift:590, 545: a stabilised clip is a region over the whole clip under its effect id, else the
    // clip's; Off is none; removing it takes the setting away from its own piece.
    @Test fun aStabilisedClipIsARegion() {
        val strong = TimelineEditing.setStabilization(EditState(listOf(a, b)), 1, StabilizationLevel.Strong, id = "steady")!!
        val region = ToolRegions.of(strong).single { it.tool == EditorTool.Stabilize }
        assertEquals("steady", region.effectID)
        assertEquals(4.0 to 10.0, region.start to region.end)
        assertEquals(ToolRegionLabel.Tool, region.label)
        val unnamed = EditState(listOf(a, b.copy(stabilization = StabilizationSettings(StabilizationLevel.Light))))
        assertEquals("b", ToolRegions.of(unnamed).single { it.tool == EditorTool.Stabilize }.effectID)
        assertTrue(ToolRegions.of(EditState(listOf(b.copy(stabilization = StabilizationSettings(StabilizationLevel.Off))))).none { it.tool == EditorTool.Stabilize })
        val split = TimelineEditing.split(strong, 7.0)!!
        val first = ToolRegions.of(split).first { it.tool == EditorTool.Stabilize }
        assertEquals(listOf(null, StabilizationLevel.Strong), ToolRegions.removing(split, first)!!.clips.drop(1).map { it.stabilization?.level })
        assertNull(ToolRegions.removing(unnamed, ToolRegions.of(unnamed).single { it.tool == EditorTool.Stabilize })!!.clips[1].stabilization)
    }

    // YouRec/VideoTools.swift:597-602, 512-518: a sound's level and fades are one region; removing it puts the sound back
    // at 0 dB, unmuted and without fades.
    @Test fun aSoundsLevelIsARegionAndRemovingItResetsIt() {
        val loud = EditState(listOf(a.copy(crop = null, audio = ClipAudio(volumeDB = 3.0, fadeIn = 1.0))))
        val level = ToolRegions.of(loud).single()
        assertEquals(EditorTool.Volume, level.tool)
        assertEquals(ToolRegionLabel.Levels(muted = false, volumeDB = 3.0, fadeIn = true, fadeOut = false), level.label)
        assertEquals(0.0 to 4.0, level.start to level.end)
        val reset = ToolRegions.removing(loud, level)!!
        assertTrue(ToolRegions.of(reset).isEmpty())
        val sound = reset.clips[0].audio
        assertTrue(sound == null || (sound.volumeDB == 0.0 && sound.fadeIn == 0.0 && !sound.muted))
    }

    // YouRec/VideoTools.swift:619-623: a transition is a region over its stretch; removing it puts back a straight cut.
    @Test fun aTransitionIsARegion() {
        val joined = TimelineEditing.setTransition(EditState(listOf(a.copy(crop = null), b.copy(color = null, texts = null))), 0, 1.0, TransitionStyle.Dissolve)!!
        val transition = ToolRegions.of(joined).single()
        assertEquals(EditorTool.Transition, transition.tool)
        assertEquals(ToolRegionLabel.Transition(TransitionStyle.Dissolve), transition.label)
        assertNotNull(transition.join)
        val cut = ToolRegions.removing(joined, transition)!!
        assertTrue(ToolRegions.of(cut).isEmpty())
        // Not listed under "In this project" (YouRec/EditorView.swift:981).
        assertTrue(ToolRegions.listed(ToolRegions.of(joined), 0.0).isEmpty())
    }
}
