package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The range editor of the Crop, Video effects, Censor and Enhance tools, as
 * the original has it since 57e5d91: YouRec/CropEditor.swift:115-127,
 * 219-262; YouRec/VideoEffects/VideoEffectsEditor.swift:392-428;
 * YouRec/VideoEffects/VideoEffectModel.swift:323-329.
 */
class ToolRangeTest {
    private fun clip(id: String, start: Double = 0.0, end: Double = 4.0, source: String = "S") = TimelineClip(id = id, sourceID = source, start = start, end = end)

    // MARK: The clips a range may reach (YouRec/CropEditor.swift:121-127)

    @Test fun theChosenClipsAreTheEligibleOnesAndSpanTheBounds() {
        val state = EditState(listOf(clip("A"), clip("B", 10.0, 13.0), clip("C", 20.0, 22.0)))
        assertEquals(setOf("B"), ToolRange.eligible(state, setOf("B"), 4.0..7.0))
        assertEquals(4.0..7.0, ToolRange.bounds(state, setOf("B")))
        assertEquals(0.0..9.0, ToolRange.bounds(state, setOf("A", "C")))
        assertNull(ToolRange.bounds(state, setOf("Z")))
    }

    @Test fun piecesCutAtAMarkedRangeReachTheClipsThatPlayInIt() {
        val state = EditState(listOf(clip("A"), clip("B", 10.0, 13.0), clip("C", 20.0, 22.0)))
        // The pieces of a cut have ids the edit does not hold: the clips under the range stand for them.
        assertEquals(setOf("A", "B"), ToolRange.eligible(state, setOf("piece"), 3.0..5.0))
        assertEquals(setOf("B"), ToolRange.eligible(state, setOf("piece"), 3.0..5.0, targets = setOf("B", "C")))
    }

    @Test fun aMovedRangeCutsTheEligibleClipsAtItsEnds() {
        val state = EditState(listOf(clip("A"), clip("B", 10.0, 13.0)))
        val scoped = ToolRange.scoped(state, setOf("A"), 1.0..3.0)
        val pieces = scoped.state.clips.filter { it.id in scoped.clips }
        assertEquals(1, pieces.size)
        assertEquals(1.0, pieces[0].start, 1e-9)
        assertEquals(3.0, pieces[0].end, 1e-9)
        // The footage around the range stays, and B is not reached.
        assertEquals(4, scoped.state.clips.size)
        assertTrue(scoped.clips.none { id -> scoped.state.clips.first { it.id == id }.start >= 10.0 })
    }

    // MARK: An effect's range (YouRec/VideoEffects/VideoEffectsEditor.swift:392-428)

    private val pieces = listOf(VideoEffectRangeTrim.Piece(clip("A"), 0.0), VideoEffectRangeTrim.Piece(clip("B", 10.0, 14.0), 4.0))
    private val blur = VideoEffectSettings(VideoEffectID.ColorCorrection).copy(id = "E")
    private val other = VideoEffectSettings(VideoEffectID.ColorCorrection).copy(id = "O")

    @Test fun anEffectCoversWhereItShowsOnTheToolsClips() {
        val effects = mapOf("A" to listOf(blur.copy(start = 1.0, end = 4.0)), "B" to listOf(blur.copy(start = 10.0, end = 12.0)))
        assertEquals(1.0..6.0, VideoEffectRangeTrim.covered(pieces, effects, "E"))
        assertNull(VideoEffectRangeTrim.covered(pieces, effects, "Z"))
    }

    @Test fun theRangeIsBoundedByTheClipsThatHaveTheEffect() {
        val effects = mapOf("A" to listOf(blur), "B" to listOf(blur))
        val timing = VideoEffectRangeTrim.timing(pieces, effects, emptyMap(), "E", blur.kind, 1.0..6.0, null)!!
        assertEquals(0.0..8.0, timing.bounds)
        assertEquals(1.0..6.0, timing.range)
        // Moved before, it opens where it was moved to.
        assertEquals(2.0..3.0, VideoEffectRangeTrim.timing(pieces, effects, emptyMap(), "E", blur.kind, 1.0..6.0, 2.0..3.0)!!.range)
        // Only on A: A's footage.
        assertEquals(0.0..4.0, VideoEffectRangeTrim.timing(pieces, mapOf("A" to listOf(blur)), emptyMap(), "E", blur.kind, 0.0..4.0, null)!!.bounds)
    }

    @Test fun aTimeEffectOrAHeldFrameHasNoRange() {
        val effects = mapOf("A" to listOf(blur))
        assertNull(VideoEffectRangeTrim.timing(pieces, effects, emptyMap(), "E", VideoEffectID.FreezeFrame, 0.0..4.0, null))
        val held = listOf(VideoEffectRangeTrim.Piece(clip("A").copy(frozenFrame = FrozenFrameSegment(sourceTime = 1.0, duration = 2.0)), 0.0))
        assertNull(VideoEffectRangeTrim.timing(held, effects, emptyMap(), "E", blur.kind, 0.0..2.0, null))
    }

    @Test fun trimmingLeavesTheEffectOnTheFootageTheRangeCoversAndGivesItBack() {
        val effects = mapOf("A" to listOf(blur, other), "B" to listOf(other, blur))
        // Over A's last second and B's first: 3…5 s of the movie.
        val (trimmed, stored) = VideoEffectRangeTrim.trimming(pieces, effects, emptyMap(), "E", 3.0..5.0)
        assertEquals(3.0, trimmed.getValue("A")[0].start, 1e-9)
        assertEquals(4.0, trimmed.getValue("A")[0].end, 1e-9)
        assertEquals(10.0, trimmed.getValue("B")[1].start, 1e-9)
        assertEquals(11.0, trimmed.getValue("B")[1].end, 1e-9)
        assertEquals(VideoEffectRangeTrim.Stored(blur, 1), stored["B"])
        // Off B: taken away there; back over it, in its place again.
        val (off, kept) = VideoEffectRangeTrim.trimming(pieces, trimmed, stored, "E", 1.0..2.0)
        assertEquals(listOf("O"), off.getValue("B").map { it.id })
        val (back, _) = VideoEffectRangeTrim.trimming(pieces, off, kept, "E", 1.0..6.0)
        assertEquals(listOf("O", "E"), back.getValue("B").map { it.id })
        assertEquals(10.0, back.getValue("B")[1].start, 1e-9)
        assertEquals(12.0, back.getValue("B")[1].end, 1e-9)
    }

    @Test fun aCrashZoomKeepsItsPushAndLetsGoOfAReleaseOutsideTheRange() {
        val zoom = VideoEffectSettings(VideoEffectID.CrashZoom, start = 2.0, end = 8.0).copy(phaseOffset = 0.5, releaseTime = 7.0)
        val trimmed = zoom.trimmingSourceRange(3.0..6.0)
        assertEquals(1.5, trimmed.phaseOffset!!, 1e-9)
        assertNull(trimmed.releaseTime)
        assertEquals(3.0, trimmed.start, 1e-9)
        assertEquals(6.0, trimmed.end, 1e-9)
        assertEquals(7.0, zoom.trimmingSourceRange(3.0..7.5).releaseTime!!, 1e-9)
    }

    // MARK: The Crop tool's range (YouRec/CropEditor.swift:219-263)

    @Test fun movingTheRangeRunsTheMotionOverTheNewRange() {
        val crop = CropSettings(id = "C", zoom = 2.0)
        val moving = CropDraft.opening(crop.copy(motion = EffectAnimation(crop.pose, crop.pose, duration = 4.0)))
        val trimmed = moving.trimmed(2.5)
        assertEquals(2.5, trimmed.animation!!.duration, 1e-9)
        assertTrue(trimmed.animationChanged)
        assertNull(CropDraft.opening(crop).trimmed(2.5).animation)
    }

    @Test fun aCropOnAMovedRangeCoversTheWholeOfItsClips() {
        val narrowed = CropSettings(id = "C", zoom = 2.0, activeRange = SourceInterval(1.0, 2.0))
        val draft = CropDraft.opening(narrowed)
        val canvas = Size(1920.0, 1080.0)
        val spans = listOf(EffectPlaybackSpan(0.0, 4.0, 1.0))
        // Unmoved, a crop opened on a clip keeps its active range.
        assertEquals(narrowed, draft.applied(narrowed, spans, 0.0, null, canvas))
        // Moved, from its badge: the crop as it is, on the new range's clips, over the whole of them.
        assertEquals(narrowed.copy(activeRange = null), draft.applied(null, spans, 0.0, null, canvas, retimed = true, reopened = true))
        // Moved from the tools: each clip keeps its own crop, over the whole of it.
        val own = CropSettings(id = "D", zoom = 1.5, activeRange = SourceInterval(0.5, 1.0))
        assertEquals(own.copy(activeRange = null), draft.applied(own, spans, 0.0, null, canvas, retimed = true))
    }

    // MARK: Enhance audio's range (YouRec/TrackEditors.swift:196-199)

    @Test fun theSoundsSpanTheBoundsOfEnhanceAudio() {
        val state = EditState(listOf(clip("A")), audio = listOf(AudioItem(id = "M", sourceID = "music", start = 2.0, sourceStart = 0.0, sourceEnd = 6.0)))
        val music = state.toTimeline().audio.first { it.sourceID == "music" }.id
        assertEquals(2.0..8.0, ToolRange.audioBounds(state, setOf(music)))
        assertNull(ToolRange.audioBounds(state, emptySet()))
    }
}
