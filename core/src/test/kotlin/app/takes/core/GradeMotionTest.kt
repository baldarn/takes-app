package app.takes.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GradeMotionTest {
    private val frame = PauseDetector.FRAME
    private val clip = TimelineClip(id = "c", sourceID = "S", start = 0.0, end = 4.0)
    private val mono = ColorSettings(look = ColorLook.Mono).pose

    /** Tests/EffectAnimationChecks.swift:41-56: rise, hold and return of a second each, from no grade to Mono, over a 4 s clip. */
    private fun moving(a: EffectPose = ColorSettings().pose, b: EffectPose = mono, on: TimelineClip = clip): ColorSettings {
        val motion = EffectAnimation(a, on.duration).copy(b = b, timing = EffectTiming(EffectCurve.RiseHoldReturn, 1.0, 1.0))
        return ColorSettings().copy(motion = motion.anchored(on.playbackSpans, 0.0))
    }

    // YouRec/VideoToolCompositor.swift:145-152 and Tests/EffectAnimationChecks.swift:137-143: A alone at the start and the
    // last frame, B alone over the hold, the two dissolved on the rise and the return.
    @Test fun aGradeShowsAAtTheEndsBOverTheHoldAndBothBetween() {
        val grade = moving()
        val (a, b) = GradeMotion.ends(grade)!!
        assertEquals(ColorLook.Natural, a.look)
        assertEquals(ColorLook.Mono, b.look)
        for (t in listOf(0.0, 4 - frame)) assertEquals(GradeMotion.Moment(a), GradeMotion.moment(grade, t))
        for (t in listOf(1.0, 2.0, 3.0)) assertEquals(GradeMotion.Moment(b), GradeMotion.moment(grade, t))
        val rising = GradeMotion.moment(grade, 0.5)
        assertEquals(a, rising.from)
        assertEquals(b, rising.to)
        assertEquals(0.5, rising.fraction, 1e-9)
        // The return runs over the last second, to the last frame.
        val returning = GradeMotion.moment(grade, 3.5)
        assertEquals(b, returning.to)
        assertEquals(1 - (3.5 - 3) / (4 - frame - 3), returning.fraction, 1e-9)
        // A grade that does not move is itself at every second.
        val still = ColorSettings(look = ColorLook.Warm)
        assertNull(GradeMotion.ends(still))
        assertEquals(GradeMotion.Moment(still), GradeMotion.moment(still, 2.0))
    }

    // The numbers of the ends are not interpolated: halfway the look is still each end's own, in the dissolve.
    @Test fun aLookChangesByDissolvingTheTwoGradedPictures() {
        val grade = moving(ColorSettings(look = ColorLook.Warm, amount = 0.4, exposure = 1.0).pose, ColorSettings(look = ColorLook.Ink, amount = 1.0).pose)
        val halfway = GradeMotion.moment(grade, 0.5)
        assertEquals(ColorSettings(id = grade.id, look = ColorLook.Warm, amount = 0.4, exposure = 1.0, highlights = 0.0, localRecovery = 0.0, warmth = 0.0, tint = 0.0), halfway.from)
        assertEquals(ColorLook.Ink, halfway.to!!.look)
        assertEquals(0.0, halfway.to.exposure!!, 0.0)
    }

    // CIDissolveTransition mixes in Core Image's linear working space: the ends exactly, between them as light.
    @Test fun theDissolveMixesLightAndKeepsTheEndsExactly() {
        val a = doubleArrayOf(0.1, 0.5, 1.0)
        val b = doubleArrayOf(0.9, 0.5, 0.0)
        assertArrayEquals(a, GradeMotion.dissolve(a, b, 0.0), 0.0)
        assertArrayEquals(b, GradeMotion.dissolve(a, b, 1.0), 0.0)
        val half = GradeMotion.dissolve(doubleArrayOf(0.0, 1.0, 0.5), doubleArrayOf(1.0, 1.0, 0.5), 0.5)
        // Half of white's light is not half its signal: BT.709 gives 0.7055.
        assertEquals(1.099 * Math.pow(0.5, 0.45) - 0.099, half[0], 1e-9)
        assertEquals(1.0, half[1], 1e-9)
        assertEquals(0.5, half[2], 1e-9)
        for (v in listOf(0.0, 0.05, 0.3, 0.7, 1.0)) assertEquals(v, GradeMotion.signal(GradeMotion.light(v)), 1e-9)
    }

    // The motion is sampled at the source second shown, as a moving crop: a clip trimmed to begin at 2 s, at double speed.
    @Test fun theFractionFollowsTheFootageOfTheClip() {
        val fast = TimelineEditing.setSpeed(EditState(listOf(clip.copy(start = 2.0, end = 10.0))), 0, 2.0)!!.clips[0]
        assertEquals(4.0, fast.duration, 1e-9)
        val graded = fast.copy(color = moving(on = fast))
        assertNull(GradeMotion.fraction(fast, 1.0))
        for (offset in listOf(0.0, 0.25, 0.5, 1.0, 2.0, 3.5)) {
            val expected = graded.color!!.motion!!.timing.fraction(offset, 4.0, frame)
            assertEquals("at $offset", expected, GradeMotion.fraction(graded, offset)!!, 1e-9)
        }
        // A split keeps the motion on both halves, tied to the footage (Tests/EffectAnimationChecks.swift:76-79).
        val split = TimelineEditing.split(EditState(listOf(graded)), 1.5)!!
        assertEquals(graded.color, split.clips[1].color)
        assertEquals(GradeMotion.fraction(graded, 2.5)!!, GradeMotion.fraction(split.clips[1], 1.0)!!, 1e-9)
    }

    // YouRec/EditorTools.swift:266-299: the first choice of an end makes a motion whose two ends are the grade on screen;
    // the controls then change that end; choosing the other loads it; applying stores A with the motion.
    @Test fun theDraftEditsOneEndAtATime() {
        val warm = ColorSettings(look = ColorLook.Warm, exposure = 0.5)
        var draft = GradeDraft.opening(warm)
        assertNull(draft.animation)
        draft = draft.selecting(EffectEndpoint.B, 4.0)
        assertEquals(warm.pose, draft.animation!!.a)
        assertEquals(warm.pose, draft.animation!!.b)
        assertEquals(EffectEndpoint.B, draft.previewEndpoint)
        draft = draft.adjusted(draft.color.copy(look = ColorLook.Mono, exposure = -1.0))
        assertEquals(ColorLook.Mono, draft.captured(4.0)!!.b.look)
        assertEquals(-1.0, draft.captured(4.0)!!.b["exposure"], 0.0)
        assertEquals(warm.pose, draft.captured(4.0)!!.a)
        // Choosing A writes B and shows A.
        draft = draft.selecting(EffectEndpoint.A, 4.0)
        assertEquals(ColorLook.Warm, draft.color.look)
        assertEquals(ColorLook.Mono, draft.animation!!.b.look)
        // A change after scrubbing goes back to the end being edited.
        draft = draft.scrubbed()
        assertNull(draft.previewEndpoint)
        draft = draft.adjusted(draft.color.copy(amount = 0.5))
        assertEquals(EffectEndpoint.A, draft.previewEndpoint)
        assertEquals(0.5, draft.captured(4.0)!!.a["amount"], 0.0)
        // Done leaves the ends; a retiming keeps both ends.
        draft = draft.finishing(4.0).retimed(EffectTiming(EffectCurve.RiseAndHold, rise = 2.0), 4.0)
        assertNull(draft.endpoint)
        assertEquals(EffectCurve.RiseAndHold, draft.animation!!.timing.curve)
        assertEquals(ColorLook.Mono, draft.animation!!.b.look)
        // Applied: the grade at A, with the motion (EditorTools.swift:706-707).
        val placed = draft.placed(null, clip.playbackSpans, 0.0, 4.0)
        assertEquals(ColorLook.Warm, placed.look)
        assertEquals(0.5, placed.amount, 0.0)
        assertEquals(ColorLook.Mono, placed.motion!!.b.look)
        assertTrue(placed.valid && placed.motion!!.valid(placed.pose) { placed.applying(it).valid })
        // Opened again, the tool shows A with the motion set aside.
        val reopened = GradeDraft.opening(placed)
        assertNull(reopened.color.motion)
        assertEquals(ColorLook.Warm, reopened.color.look)
        assertEquals(placed.motion, reopened.animation)
        // Remove animation keeps the grade on screen, fixed.
        val fixed = reopened.selecting(EffectEndpoint.B, 4.0).removingAnimation()
        assertNull(fixed.animation)
        assertEquals(ColorLook.Mono, fixed.placed(placed, clip.playbackSpans, 0.0, 4.0).look)
        assertNull(fixed.placed(placed, clip.playbackSpans, 0.0, 4.0).motion)
    }

    // B is parked on its peak and follows it as the timing changes (EffectAnimation.swift:63-73).
    @Test fun theParkedEndFollowsItsPeak() {
        var draft = GradeDraft.opening(ColorSettings()).selecting(EffectEndpoint.B, 4.0)
        assertEquals(4 - frame, draft.previewPosition(1.0, 4.0), 1e-9)
        draft = draft.retimed(EffectTiming(EffectCurve.RiseAndHold, rise = 1.5), 4.0)
        assertEquals(1.5, draft.previewPosition(1.0, 4.0), frame)
        assertEquals(1.0, draft.scrubbed().previewPosition(1.0, 4.0), 0.0)
        assertEquals(EffectEndpoint.B, draft.scrubbed().stopped().previewEndpoint)
    }

    // EditorTools.swift:704-707: each clip of the scope gets the motion tied to its footage from where it begins in the
    // stretch the tool works on (VideoTools.swift:642-650), so the grade runs on across the cut.
    @Test fun theToolPutsTheMotionOnEveryClipOfItsStretch() {
        val first = TimelineClip(id = "a", sourceID = "A", start = 1.0, end = 3.0)
        val second = TimelineClip(id = "b", sourceID = "B", start = 5.0, end = 7.0)
        val outside = TimelineClip(id = "x", sourceID = "X", start = 0.0, end = 1.0)
        val base = EditState(listOf(outside, first, second))
        assertEquals(1.0..5.0, ToolCompare.stretch(base, listOf("a", "b")))
        val draft = GradeDraft.opening(ColorSettings()).selecting(EffectEndpoint.B, 4.0).adjusted(ColorSettings(look = ColorLook.Mono)).finishing(4.0)
        val after = ToolCompare.graded(base, listOf("a", "b"), draft)
        assertNull(after.clips[0].color)
        val a = after.clips[1].color!!
        val b = after.clips[2].color!!
        assertEquals(a.motion!!.copy(clock = emptyList()), b.motion!!.copy(clock = emptyList()))
        assertEquals(4.0, a.motion!!.duration, 0.0)
        // Two seconds into the stretch is the first second of the second clip, source second 5.
        assertEquals(2.0, b.motion!!.elapsed(5.0), 1e-9)
        assertEquals(a.motion!!.timing.fraction(2.0, 4.0, frame), GradeMotion.fraction(after.clips[2], 0.0)!!, 1e-9)
        assertEquals(a.motion!!.timing.fraction(0.5, 4.0, frame), GradeMotion.fraction(after.clips[1], 0.5)!!, 1e-9)
        // Before takes the grade off; a motion applied again unchanged keeps its clock.
        assertNull(ToolCompare.graded(after, listOf("a", "b"), null).clips[1].color)
        val reopened = GradeDraft.opening(b)
        assertSame(b.motion, reopened.placed(b, after.clips[2].playbackSpans, 2.0, 4.0).motion)
        val moved = ToolCompare.graded(after, listOf("b"), reopened)
        assertEquals(b.motion, moved.clips[2].color!!.motion)
    }

    // YouRec/Project.swift:289: the LUT files of both ends belong to the project.
    @Test fun theLutsOfBothEndsBelongToTheProject() {
        var project = Project("x").addRecording(SourceMedia(filename = "a.mp4", duration = 10.0))
        val film = ImportedLUT("effect-lut-0a1b.cube", "Film")
        val night = ImportedLUT("effect-lut-0c2d.cube", "Night")
        val grade = moving(ColorSettings(imported = film).pose, ColorSettings(imported = night).pose, project.edit.clips[0])
        project = project.commit(TimelineEditing.setColor(project.edit, 0, grade.applying(grade.motion!!.a).copy(motion = grade.motion))!!)
        assertEquals(listOf(film.filename, night.filename), project.effectFiles)
        project.validate()
    }
}
