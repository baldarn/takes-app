package app.takes.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Captions designed with the title designer: the series' design and motion
 * (YouRec/Subtitles.swift:41-46, YouRec/SubtitleEditor.swift:279-294) and the
 * room the layout keeps for the motion (YouRec/SubtitleRenderer.swift:42-49).
 */
class SubtitleDesignTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun resource(path: String) = javaClass.getResourceAsStream("/formats/subtitles/$path")!!.readBytes()

    /**
     * The golden subtitles project as the version before captions had a design
     * wrote it (`core/src/test/resources/formats/subtitles`, made from
     * [GoldenProjects] at 87f88fb): read and written again, every byte is the same.
     */
    @Test fun aProjectWithoutADesignedSeriesIsWrittenAsBefore() {
        val manifest = resource("project.json")
        val history = HashMap<String, ByteArray>()
        val project = Project.readManifest(manifest) { name -> resource("History/$name").also { history[name] = it } }
        assertTrue(project.history.any { it.subtitles != null })
        assertTrue(project.history.all { it.subtitles?.appearance == null && it.subtitles?.motion == null })
        assertArrayEquals(manifest, project.manifest { _, _ -> })
        // Each step encoded afresh from what was read, not the bytes kept from reading it.
        for ((name, bytes) in history) {
            val step = documentJson.decodeFromString(EditSnapshot.serializer(), bytes.decodeToString())
            assertArrayEquals(name, bytes, documentJson.encodeToString(EditSnapshot.serializer(), step).toByteArray())
        }
        // And saved by the app, with its font library, the folder holds the same files.
        val disk = ProjectDisk(File(temporary.root, "Projects"), FontLibrary(File(temporary.root, "Fonts")))
        disk.save(project)
        val folder = disk.folder(project.id)
        assertArrayEquals(manifest, File(folder, "project.json").readBytes())
        assertEquals(history.keys.sorted(), File(folder, "History").list()!!.sorted())
        for ((name, bytes) in history) assertArrayEquals(bytes, File(folder, "History/$name").readBytes())
        assertEquals(listOf("History", "project.json"), folder.list()!!.sorted())
    }

    @Test fun aDesignedSeriesKeepsItsDesignAndMotionThroughTheHistory() {
        val project = Project.readManifest(resource("project.json")) { resource("History/$it") }
        val edit = project.edit
        val design = TitleDesign(face = "caveat", weight = 600, plate = TitlePlate.Lines, gradient = TitleGradient.gallery[3].second)
        val motion = TitleMotion(TitleMove.Drift, TitleMove.Wipe, 0.5, 0.25, TitleDirection.Left, TitleDirection.Down)
        val styled = SubtitleEditing.restyle(edit, edit.subtitles!!.copy(appearance = design, motion = motion))!!
        val next = project.commit(styled)
        // No schema of its own, as in the original (YouRec/Project.swift:318-330): the same as any other change of the captions.
        assertEquals(project.commit(SubtitleEditing.restyle(edit, edit.subtitles!!.copy(size = 0.05))!!).schemaVersion, next.schemaVersion)
        val disk = ProjectDisk(File(temporary.root, "Projects"))
        disk.save(next)
        val read = disk.read(disk.folder(next.id))
        assertEquals(design, read.edit.subtitles?.appearance)
        assertEquals(motion, read.edit.subtitles?.motion)
        assertNull(read.undo().edit.subtitles?.appearance)
    }

    // YouRec/Subtitles.swift:43
    @Test fun theDesignerStartsFromTheSeriesStyleColorAndFont() {
        val plain = SubtitleSettings(style = SubtitleSettings.Style.Highlight, color = SubtitleSettings.Color.Yellow, font = SubtitleFont.Mono)
        assertEquals(TitleDesign.legacy(TextStyle.Highlight, TextColor.Yellow, SubtitleFont.Mono), plain.design)
        assertEquals("Menlo-Bold", plain.design.face)
        assertEquals(TitlePlate.Block, plain.design.plate)
        assertEquals(TitleInk.of(TextColor.Yellow), plain.design.backing)
        assertEquals(TitleDesign.legacy(TextStyle.Box, TextColor.White, SubtitleFont.System), SubtitleSettings().design)
        val designed = plain.copy(appearance = TitleDesign(face = "fraunces"))
        assertEquals(TitleDesign(face = "fraunces"), designed.design)
    }

    // YouRec/Subtitles.swift:46
    @Test fun aDesignOrMotionOutOfItsRangesIsInvalid() {
        assertTrue(SubtitleSettings(appearance = TitleDesign(), motion = TitleMotion(TitleMove.Fade)).valid)
        assertFalse(SubtitleSettings(appearance = TitleDesign(weight = 50)).valid)
        assertFalse(SubtitleSettings(appearance = TitleDesign(borderWidth = 0.5)).valid)
        assertFalse(SubtitleSettings(motion = TitleMotion(inSeconds = 6.0)).valid)
        assertFalse(SubtitleSettings(motion = TitleMotion(outSeconds = 0.01)).valid)
    }

    // YouRec/SubtitleRenderer.swift:42-49
    @Test fun theLayoutKeepsRoomForTheMotion() {
        val box = Size(500.0, 100.0)
        assertEquals(box, SubtitlePlacement.moving(box, null))
        assertEquals(box, SubtitlePlacement.moving(box, TitleMotion(TitleMove.Fade, TitleMove.Wipe)))
        assertEquals(Size(540.0, 108.0), SubtitlePlacement.moving(box, TitleMotion(exit = TitleMove.Scale)))
        // A drift without a direction goes up.
        assertEquals(Size(500.0, 164.0), SubtitlePlacement.moving(box, TitleMotion(entrance = TitleMove.Drift)))
        assertEquals(Size(564.0, 164.0), SubtitlePlacement.moving(box, TitleMotion(TitleMove.Drift, TitleMove.Drift, inDirection = TitleDirection.Left, outDirection = TitleDirection.Down)))
        // The scale comes first, and the drift's margin is of the grown height.
        val both = SubtitlePlacement.moving(box, TitleMotion(TitleMove.Drift, TitleMove.Scale, inDirection = TitleDirection.Right))
        assertEquals(540.0 + 108.0 * 0.64, both.width, 1e-9)
        assertEquals(108.0, both.height, 1e-9)
        // A smaller largest size for a moving series: room for the move inside the picture.
        val canvas = Size(1920.0, 1080.0)
        val still = SubtitlePlacement(canvas, box)
        val moving = SubtitlePlacement(canvas, SubtitlePlacement.moving(box, TitleMotion(entrance = TitleMove.Drift, inDirection = TitleDirection.Left)))
        assertTrue(moving.maximumSize < still.maximumSize)
    }

    // YouRec/SubtitleRenderer.swift:75-83, 114: each caption is posed by the time since it began, over its own length.
    @Test fun eachCaptionMovesOverItsOwnLength() {
        val motion = TitleMotion(TitleMove.Fade, TitleMove.Scale, inSeconds = 0.4, outSeconds = 0.4)
        assertNull(SubtitlePlacement.pose(null, 0.1, 2.0))
        assertNull(SubtitlePlacement.pose(TitleMotion(), 0.1, 2.0))
        assertEquals(motion.pose(0.1, 2.0), SubtitlePlacement.pose(motion, 0.1, 2.0))
        assertEquals(TitleMotion.Pose(), SubtitlePlacement.pose(motion, 1.0, 2.0))
        assertTrue(SubtitlePlacement.pose(motion, 0.1, 2.0)!!.opacity < 0.5)
        assertTrue(SubtitlePlacement.pose(motion, 1.95, 2.0)!!.scale > 1.0)
    }
}
