package app.takes.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ColorGradeTest {
    private fun at(table: FloatArray, red: Int, green: Int, blue: Int): List<Float> {
        val index = ((blue * ColorGrade.DIMENSION + green) * ColorGrade.DIMENSION + red) * 3
        return listOf(table[index], table[index + 1], table[index + 2])
    }

    @Test fun naturalIsTheIdentityWithRedFastest() {
        val table = ColorGrade.table(ColorSettings())
        assertEquals(33 * 33 * 33 * 3, table.size)
        assertEquals(listOf(0f, 0f, 0f), at(table, 0, 0, 0))
        assertEquals(listOf(1f, 0f, 0f), at(table, 32, 0, 0))
        assertEquals(listOf(0f, 1f, 0f), at(table, 0, 32, 0))
        assertEquals(listOf(0f, 0f, 1f), at(table, 0, 0, 32))
        assertEquals(listOf(0.5f, 0.25f, 0.75f), at(table, 16, 8, 24))
    }

    @Test fun looksFollowTheOriginalMath() {
        val mono = ColorGrade.look(ColorLook.Mono, 1.0, 0.0, 0.0)
        assertArrayEquals(doubleArrayOf(0.2126, 0.2126, 0.2126), mono, 1e-12)
        val warm = ColorGrade.look(ColorLook.Warm, 0.5, 0.5, 0.5)
        assertTrue(warm[0] > 0.5 && warm[1] == 0.5 && warm[2] < 0.5)
        val ink = ColorGrade.look(ColorLook.Ink, 0.5, 0.5, 0.5)
        assertEquals(0.5, ink[0], 1e-12) // the curve is symmetric around middle gray
        assertEquals(0.0, ColorGrade.look(ColorLook.Ink, 0.05, 0.05, 0.05)[1], 0.0)
        val contrast = ColorGrade.look(ColorLook.RichContrast, 0.25, 0.5, 0.75)
        assertTrue(contrast[0] < 0.25 && abs(contrast[1] - 0.5) < 1e-12 && contrast[2] > 0.75)
        assertEquals(14, ColorLook.entries.size)
        for (look in ColorLook.entries) {
            val table = ColorGrade.table(ColorSettings(look = look))
            assertTrue(look.name, table.all { it in 0f..1f })
        }
    }

    @Test fun strengthBlendsFromThePictureToTheLook() {
        val full = ColorGrade.apply(ColorSettings(look = ColorLook.Mono), null, 1.0, 0.0, 0.0)
        val half = ColorGrade.apply(ColorSettings(look = ColorLook.Mono, amount = 0.5), null, 1.0, 0.0, 0.0)
        val none = ColorGrade.apply(ColorSettings(look = ColorLook.Mono, amount = 0.0), null, 1.0, 0.0, 0.0)
        assertArrayEquals(doubleArrayOf(0.2126, 0.2126, 0.2126), full, 1e-12)
        assertArrayEquals(doubleArrayOf((1 + 0.2126) / 2, 0.1063, 0.1063), half, 1e-12)
        assertArrayEquals(doubleArrayOf(1.0, 0.0, 0.0), none, 1e-12)
    }

    @Test fun exposureDoublesTheLightAndStaysAtZeroStrength() {
        val lifted = ColorGrade.apply(ColorSettings(exposure = 1.0, amount = 0.0), null, 0.5, 0.5, 0.5)
        // 0.5 display is 0.214 linear; doubled is 0.428, which displays as 0.686.
        assertEquals(0.6858, lifted[0], 1e-3)
        assertEquals(lifted[0], lifted[2], 0.0)
        val warm = ColorGrade.apply(ColorSettings(warmth = 1.0), null, 0.5, 0.5, 0.5)
        assertTrue(warm[0] > 0.5 && warm[2] < 0.5 && abs(warm[1] - 0.5) < 1e-12)
        val magenta = ColorGrade.apply(ColorSettings(tint = 1.0), null, 0.5, 0.5, 0.5)
        assertTrue(magenta[1] < 0.5 && abs(magenta[0] - 0.5) < 1e-12)
        assertEquals(1.0, ColorGrade.apply(ColorSettings(exposure = 4.0), null, 0.9, 0.9, 0.9)[0], 1e-9) // clipped, not wrapped
    }

    @Test fun anImportedLutReplacesTheLook() {
        // A 2-point cube that swaps red and blue.
        val swap = CubeLUT.parse("LUT_3D_SIZE 2\n" + listOf("0 0 0", "0 0 1", "0 1 0", "0 1 1", "1 0 0", "1 0 1", "1 1 0", "1 1 1").joinToString("\n"))
        val settings = ColorSettings(look = ColorLook.Mono, imported = ImportedLUT("effect-abc.cube", "Swap"))
        assertArrayEquals(doubleArrayOf(0.0, 0.25, 1.0), ColorGrade.apply(settings, swap, 1.0, 0.25, 0.0), 1e-6)
        assertEquals("Swap", settings.title)
        assertEquals("Color balance", ColorSettings(exposure = 0.5).title)
    }

    @Test fun colorIsAnEditThatFollowsTheClip() {
        val state = EditState(listOf(TimelineClip(sourceID = "A", start = 0.0, end = 10.0)))
        assertNull(TimelineEditing.setColor(state, 0, ColorSettings()))           // nothing to change
        assertNull(TimelineEditing.setColor(state, 0, ColorSettings(amount = 2.0)))
        assertNull(TimelineEditing.setColor(state, 0, ColorSettings(imported = ImportedLUT("../x.cube", "x"))))
        val graded = TimelineEditing.setColor(state, 0, ColorSettings(look = ColorLook.Cinema, amount = 0.6))!!
        assertEquals(7, graded.clips[0].schema)
        assertNull(TimelineEditing.setColor(graded, 0, ColorSettings())!!.clips[0].color) // back to natural stores nothing
        // Both pieces of a split, and every piece left after removing pauses, keep the color.
        assertEquals(listOf(ColorLook.Cinema, ColorLook.Cinema), TimelineEditing.split(graded, 4.0)!!.clips.map { it.color!!.look })
        assertEquals(3, TimelineEditing.removeIntervals(graded, 0, listOf(SourceInterval(2.0, 3.0), SourceInterval(6.0, 7.0)))!!.clips.count { it.color?.look == ColorLook.Cinema })
        var project = Project("x").addRecording(SourceMedia(filename = "a.mp4", duration = 10.0))
        project = project.commit(TimelineEditing.setColor(project.edit, 0, ColorSettings(look = ColorLook.Ink, exposure = -0.5))!!)
        assertEquals(7, project.schemaVersion)
        project.validate()
        val files = mutableMapOf<String, ByteArray>()
        val manifest = project.manifest { name, data -> files[name] = data }
        assertEquals(project, Project.readManifest(manifest) { files.getValue(it) })
        assertTrue(files.values.any { it.decodeToString().contains("\"look\":\"ink\"") }) // the original app's names
    }
}
