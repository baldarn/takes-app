package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolCompareTest {
    private val a = TimelineClip(id = "a", sourceID = "A", start = 2.0, end = 10.0)
    private val b = TimelineClip(id = "b", sourceID = "B", start = 0.0, end = 4.0)
    private val graded = ColorSettings(look = ColorLook.Warm, exposure = 1.0)

    // YouRec/EditorTools.swift:706-712: After sets the grade on each clip of the scope; Before (removing) takes it off.
    @Test fun afterGradesTheClipsAndBeforeTakesTheirGradeOff() {
        val base = EditState(listOf(a.copy(color = ColorSettings(look = ColorLook.Mono)), b.copy(color = ColorSettings(look = ColorLook.Ink))))
        val after = ToolCompare.colored(base, listOf("a"), graded)
        assertEquals(graded, after.clips[0].color)
        assertEquals(ColorLook.Ink, after.clips[1].color?.look)
        val before = ToolCompare.colored(base, listOf("a"), null)
        assertNull(before.clips[0].color)
        assertEquals(ColorLook.Ink, before.clips[1].color?.look)
        // An empty grade is stored as none (EditorTools.swift:712).
        assertNull(ToolCompare.colored(base, listOf("a", "b"), ColorSettings()).clips.firstNotNullOfOrNull { it.color })
    }

    // EditorTools.swift:704: After plays the scope at the rate; Before (removing) as recorded.
    @Test fun afterPacesTheClipsAndBeforePlaysThemAsRecorded() {
        val base = TimelineEditing.setSpeed(EditState(listOf(a, b)), 1, 0.5)!!
        val after = ToolCompare.paced(base, listOf("a", "b"), 2.0)
        assertEquals(listOf(4.0, 2.0), after.clips.map { it.duration })
        val before = ToolCompare.paced(base, listOf("a", "b"), 1.0)
        assertEquals(listOf(8.0, 4.0), before.clips.map { it.duration })
        assertEquals(base, ToolCompare.paced(base, listOf("missing"), 2.0))
    }

    @Test fun thePlayheadStaysOnTheSameFootage() {
        val before = EditState(listOf(a, b))
        val after = ToolCompare.paced(before, listOf("a"), 2.0)
        // Source 6 of the first clip: 4 s into it as recorded, 2 s at double speed.
        assertEquals(2.0, ToolCompare.mapped(before, after, 4.0), 1e-9)
        assertEquals(4.0, ToolCompare.mapped(after, before, 2.0), 1e-9)
        // The second clip begins at 8 as recorded and at 4 after; one second into it stays one second into it.
        assertEquals(5.0, ToolCompare.mapped(before, after, 9.0), 1e-9)
        // Past the end, the end.
        assertEquals(after.duration, ToolCompare.mapped(before, after, 50.0), 1e-9)
    }
}
