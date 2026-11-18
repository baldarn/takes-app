package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Effects on part of a clip, as the original keeps them since 57e5d91: grades
 * over parts of a clip (YouRec/VideoTools.swift:163-248), windows and framing
 * variants (YouRec/ScopedEffect.swift:1-133), active ranges, a title's clock
 * and the effects made across pieces (YouRec/Project.swift:51-63, 316-349,
 * 504-548; YouRec/TrackTimeline.swift:117-118, 175-176, 1557-1580). Line
 * numbers are of 57e5d91.
 */
class ScopedEffectsTest {
    private val take = SourceMedia(id = "S", filename = "take.mp4", duration = 20.0, recordedAt = 5)
    private fun clip(id: String = "A", start: Double = 0.0, end: Double = 4.0) = TimelineClip(id = id, sourceID = "S", start = start, end = end)

    // MARK: Grades over parts of a clip

    @Test fun aGradeOverTheWholeClipIsItsColorAndOverPartOfItARegion() {
        val warm = ColorSettings(id = "W", look = ColorLook.Warm)
        val whole = clip().replacingGrade(0.0..4.0, warm)
        assertEquals(warm, whole.color)
        assertNull(whole.colorRegions)
        val part = clip().replacingGrade(1.0..2.0, warm)
        assertNull(part.color)
        assertEquals(listOf(ColorRegion(1.0, 2.0, warm)), part.colorRegions)
        assertEquals(warm, part.grade(1.5))
        assertNull(part.grade(2.0)) // half-open
        assertTrue(part.hasGrade)
    }

    @Test fun reEditingTheMiddleOfALookLeavesIndependentRemnants() {
        val warm = ColorSettings(id = "W", look = ColorLook.Warm)
        val cool = ColorSettings(id = "C", look = ColorLook.Cool)
        val graded = clip().replacingGrade(0.0..4.0, warm).replacingGrade(1.0..3.0, cool)
        val grades = graded.visibleGrades
        assertEquals(listOf(0.0 to 1.0, 1.0 to 3.0, 3.0 to 4.0), grades.map { it.start to it.end })
        assertEquals("W", grades[0].grade.id)
        assertEquals("C", grades[1].grade.id)
        // Separate handles for the two remnants (YouRec/VideoTools.swift:211-214).
        assertNotEquals("W", grades[2].grade.id)
        assertEquals(ColorLook.Warm, grades[2].grade.look)
        // Removing only the middle grade leaves the two remnants as they were.
        val removed = graded.replacingGrade(0.0..4.0, null, removingOnly = "C")
        assertEquals(listOf(0.0 to 1.0, 3.0 to 4.0), removed.visibleGrades.map { it.start to it.end })
    }

    @Test fun gradesAreCutToTheFootageTheClipPlays() {
        val warm = ColorSettings(id = "W", look = ColorLook.Warm)
        val trimmed = clip(start = 2.0, end = 6.0).copy(colorRegions = listOf(ColorRegion(0.0, 3.0, warm), ColorRegion(5.0, 9.0, warm.copy(id = "X"))))
        assertEquals(listOf(2.0 to 3.0, 5.0 to 6.0), trimmed.visibleGrades.map { it.start to it.end })
        // A held frame grades along its motion clock (YouRec/VideoTools.swift:179-186).
        val held = clip().copy(frozenFrame = FrozenFrameSegment(sourceTime = 2.0, duration = 3.0, offset = 0.5))
        assertEquals(3.0, held.gradeTime(0.5), 1e-12)
        assertEquals(1.0, held.gradeOffset(3.5), 1e-12)
        assertEquals(3.0, held.gradeOffset(100.0), 1e-12)
    }

    // MARK: Windows and variants

    @Test fun aRecipeAppliesOnlyInItsWindows() {
        val noir = VideoEffectSettings(VideoEffectID.Noir).copy(id = "N")
        val title = TextOverlay(id = "T", text = "Hi", start = 0.0, end = 4.0)
        val windowed = clip().copy(
            videoEffects = listOf(noir), texts = listOf(title), mirror = MirrorSettings(id = "M", horizontal = true),
            effectWindows = mapOf("N" to listOf(SourceInterval(1.0, 2.0)), "M" to listOf(SourceInterval(0.0, 1.0), SourceInterval(3.0, 4.0))),
        )
        assertTrue(windowed.validEffectWindows)
        assertEquals(listOf(noir), windowed.resolvingEffects(1.5).videoEffects)
        assertEquals(emptyList<VideoEffectSettings>(), windowed.resolvingEffects(2.5).videoEffects)
        assertNull(windowed.resolvingEffects(2.5).mirror)
        assertEquals("M", windowed.resolvingEffects(3.5).mirror?.id)
        // A recipe without a window applies everywhere.
        assertEquals(listOf(title), windowed.resolvingEffects(2.5).texts)
        // The pieces every renderer receives (YouRec/ScopedEffect.swift:109-132).
        val pieces = windowed.resolvedEffectPieces
        assertEquals(listOf(0.0 to 1.0, 1.0 to 2.0, 2.0 to 3.0, 3.0 to 4.0), pieces.map { it.start to it.end })
        assertEquals(listOf(true, false, false, true), pieces.map { it.mirror != null })
        assertEquals(listOf(false, true, false, false), pieces.map { !it.videoEffects.isNullOrEmpty() })
        assertTrue(pieces.all { it.effectWindows == null && it.framingVariants == null })
    }

    @Test fun aFramingVariantSharesTheSlotAtAnotherTime() {
        val crop = CropSettings(id = "P", zoom = 1.5)
        val local = CropSettings(id = "L", zoom = 2.5)
        val varied = clip().copy(
            crop = crop, framingVariants = listOf(FramingVariant.Crop(local)),
            effectWindows = mapOf("P" to listOf(SourceInterval(0.0, 1.0), SourceInterval(3.0, 4.0)), "L" to listOf(SourceInterval(1.0, 3.0))),
        )
        assertTrue(varied.validEffectWindows)
        // Render instructions gate both crop recipes (Tests/VideoToolsTests.swift, scopedEffectTests).
        assertEquals(listOf(1.5, 2.5, 1.5), listOf(0.5, 1.5, 3.5).map { t -> varied.resolvedEffectPieces.first { it.start <= t && t < it.end }.crop?.zoom })
        // Promoting the variant puts the main crop among the variants.
        val promoted = varied.promoting(FramingVariant.Crop(local))
        assertEquals("L", promoted.crop?.id)
        assertEquals(listOf("P"), promoted.framingVariants?.map { it.id })
        // A variant without a window is not valid, nor one named twice.
        assertFalse(varied.copy(effectWindows = mapOf("P" to listOf(SourceInterval(0.0, 1.0)))).validEffectWindows)
        assertFalse(varied.copy(framingVariants = listOf(FramingVariant.Crop(local), FramingVariant.Crop(local))).validEffectWindows)
    }

    @Test fun normalizingTidiesVariantsAndWindows() {
        val local = CropSettings(id = "L", zoom = 2.5)
        // A fresh crop over the whole clip replaces its variants of that tool.
        val replaced = clip().copy(crop = CropSettings(id = "F"), framingVariants = listOf(FramingVariant.Crop(local)), effectWindows = mapOf("L" to listOf(SourceInterval(1.0, 2.0))))
            .normalizingEffectWindows()
        assertNull(replaced.framingVariants)
        assertNull(replaced.effectWindows)
        // A variant moves into an empty slot.
        val moved = clip().copy(framingVariants = listOf(FramingVariant.Crop(local)), effectWindows = mapOf("L" to listOf(SourceInterval(1.0, 2.0))))
            .normalizingEffectWindows()
        assertEquals("L", moved.crop?.id)
        assertEquals(mapOf("L" to listOf(SourceInterval(1.0, 2.0))), moved.effectWindows)
        // Windows of a recipe that is gone go with it.
        assertNull(clip().copy(effectWindows = mapOf("gone" to listOf(SourceInterval(1.0, 2.0)))).normalizingEffectWindows().effectWindows)
    }

    @Test fun aFrameDrawsTheRecipeInForceWithTheActiveRangesHonoured() {
        val warm = ColorSettings(id = "W", look = ColorLook.Warm)
        val ranged = clip().copy(
            crop = CropSettings(id = "P", zoom = 2.0, activeRange = SourceInterval(1.0, 3.0)),
            mirror = MirrorSettings(id = "M", horizontal = true, activeRange = SourceInterval(0.0, 1.0)),
            colorRegions = listOf(ColorRegion(2.0, 4.0, warm)),
        )
        val early = ranged.recipe(0.5)
        assertNull(early.crop)
        assertEquals("M", early.mirror?.id)
        assertNull(early.color)
        val late = ranged.recipe(2.5)
        assertEquals(2.0, late.crop!!.zoom, 0.0)
        assertNull(late.mirror)
        assertEquals(warm, late.color)
        assertNull(late.colorRegions)
    }

    // MARK: Saving

    @Test fun aClipWithoutScopedEffectsIsWrittenAsBefore() {
        assertEquals("""{"id":"A","sourceID":"S","start":0.0,"end":4.0}""", documentJson.encodeToString(TimelineClip.serializer(), clip()))
        // The pieces' membership travels with the sequence form's clip and is never written with it.
        assertEquals("""{"id":"A","sourceID":"S","start":0.0,"end":4.0}""", documentJson.encodeToString(TimelineClip.serializer(), clip().copy(spanningEffects = setOf("X"))))
        assertFalse(clip().scoped)
    }

    @Test fun framingVariantsAndWindowsAreWrittenAsTheOriginalWritesThem() {
        val variant = FramingVariant.Stabilization("V", StabilizationSettings(StabilizationLevel.Light, id = "V", activeRange = SourceInterval(1.0, 2.0)))
        val json = documentJson.encodeToString(FramingVariant.serializer(), variant)
        assertEquals("""{"stabilization":{"_0":"V","_1":{"level":"light","version":1,"id":"V","activeRange":{"start":1.0,"end":2.0}}}}""", json)
        assertEquals(variant, documentJson.decodeFromString(FramingVariant.serializer(), json))
        val mirror = FramingVariant.Mirror(MirrorSettings(id = "M", horizontal = true))
        assertEquals("""{"mirror":{"_0":{"id":"M","horizontal":true,"vertical":false}}}""", documentJson.encodeToString(FramingVariant.serializer(), mirror))
        val windowed = clip().copy(mirror = MirrorSettings(id = "M"), effectWindows = mapOf("M" to listOf(SourceInterval(1.0, 2.0))))
        val text = documentJson.encodeToString(TimelineClip.serializer(), windowed)
        assertTrue(text, text.contains(""""effectWindows":{"M":[{"start":1.0,"end":2.0}]}"""))
        assertEquals(windowed, documentJson.decodeFromString(TimelineClip.serializer(), text))
    }

    @Test fun scopedEffectsRaiseTheSchemaAndSurviveReopening() {
        val base = Project("Trip", id = "P", createdAt = 1).addRecording(take, now = 2)
        val clip = base.edit.clips[0]
        val warm = ColorSettings(id = "W", look = ColorLook.Warm)
        val edited = base.commit(base.edit.copy(clips = listOf(clip.replacingGrade(1.0..2.0, warm).copy(mirror = MirrorSettings(id = "M", horizontal = true, activeRange = SourceInterval(1.0, 3.0))))), now = 3)
        assertEquals(TimelineClip.SCOPED_EFFECTS_SCHEMA, edited.schemaVersion)
        edited.validate()
        val files = mutableMapOf<String, ByteArray>()
        val manifest = edited.manifest { name, data -> files[name] = data }
        val reopened = Project.readManifest(manifest) { files.getValue(it) }
        assertEquals(edited.edit, reopened.edit)
        assertEquals(base.edit, reopened.undo().edit)
    }

    @Test fun invalidScopedEffectsAreRefused() {
        val warm = ColorSettings(id = "W", look = ColorLook.Warm)
        assertTrue(ScopedEffects.valid(clip().copy(colorRegions = listOf(ColorRegion(0.0, 1.0, warm), ColorRegion(1.0, 2.0, warm.copy(id = "X")))), 20.0))
        // Overlapping regions, regions beside a whole-clip grade, an empty range.
        assertFalse(ScopedEffects.valid(clip().copy(colorRegions = listOf(ColorRegion(0.0, 2.0, warm), ColorRegion(1.0, 3.0, warm))), 20.0))
        assertFalse(ScopedEffects.valid(clip().copy(color = warm, colorRegions = listOf(ColorRegion(0.0, 1.0, warm))), 20.0))
        assertFalse(ScopedEffects.valid(clip().copy(crop = CropSettings(activeRange = SourceInterval(2.0, 2.0))), 20.0))
        assertFalse(TitlePlaybackClock(0.0, listOf(StrobeTimePoint(0.0, 0.0))).valid)
        assertFalse(TextOverlay(playbackClock = TitlePlaybackClock(1.0, emptyList())).valid)
    }

    // MARK: Pieces and regions

    @Test fun membershipAcrossPiecesSurvivesTheSequenceForm() {
        val state = EditState(listOf(clip("A").copy(spanningEffects = setOf("T")), clip("B", 4.0, 8.0).copy(spanningEffects = setOf("T"))))
        val timeline = state.toTimeline()
        assertEquals(listOf(setOf("T"), setOf("T")), timeline.video.map { it.spanningEffects })
        assertTrue(timeline.video.all { it.content.spanningEffects == null })
        assertEquals(state.clips, TrackSequenceForm.sequence(timeline)!!.clips)
        assertTrue(ScopedEffects.spans(timeline))
    }

    @Test fun aRegionIsWhereItsWindowShowsTheRecipe() {
        val ranged = EditState(listOf(clip().copy(
            mirror = MirrorSettings(id = "M", horizontal = true),
            effectWindows = mapOf("M" to listOf(SourceInterval(0.0, 1.0), SourceInterval(3.0, 4.0))),
            crop = CropSettings(id = "P", zoom = 2.0, activeRange = SourceInterval(1.0, 3.0)),
            colorRegions = listOf(ColorRegion(2.0, 4.0, ColorSettings(id = "W", look = ColorLook.Warm))),
        )))
        val regions = ToolRegions.of(ranged)
        assertEquals(listOf(0.0 to 1.0, 3.0 to 4.0), regions.filter { it.tool == EditorTool.Mirror }.map { it.start to it.end })
        assertEquals(listOf(1.0 to 3.0), regions.filter { it.tool == EditorTool.Crop }.map { it.start to it.end })
        assertEquals(listOf(2.0 to 4.0), regions.filter { it.tool == EditorTool.Color }.map { it.start to it.end })
    }

    @Test fun aPastedClipHasScopedEffectsOfItsOwn() {
        val local = CropSettings(id = "L", zoom = 2.5)
        val original = clip().copy(
            crop = CropSettings(id = "P"), framingVariants = listOf(FramingVariant.Crop(local)),
            effectWindows = mapOf("P" to listOf(SourceInterval(0.0, 1.0)), "L" to listOf(SourceInterval(1.0, 3.0))),
            colorRegions = listOf(ColorRegion(1.0, 2.0, ColorSettings(id = "W"))),
        )
        val state = EditState(listOf(original))
        val copied = RangeEditing.copy(state, EditScope.Whole)!!
        val pasted = RangeEditing.paste(state, copied, 4.0)!!.state.clips[1]
        assertTrue(pasted.validEffectWindows)
        val ids = setOf(pasted.crop!!.id, pasted.framingVariants!!.single().id, pasted.colorRegions!!.single().grade.id)
        assertTrue(ids.none { it in setOf("P", "L", "W") })
        assertEquals(setOf(pasted.crop!!.id, pasted.framingVariants!!.single().id), pasted.effectWindows!!.keys)
    }
}
