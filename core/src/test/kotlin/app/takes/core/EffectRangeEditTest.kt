package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An effect's range dragged by its handles, and an effect edited or removed on
 * part of its application: the cases of Tests/VideoToolsTests.swift at
 * 57e5d91 (`timelineEffectRangeTests`, :766-981, without the decoded frames;
 * `scopedEffectTests`, :1098-1214), against YouRec/VideoTools.swift:599-1157
 * and YouRec/ScopedEffect.swift:215-480.
 */
class EffectRangeEditTest {
    private val source = SourceMedia(id = "00000000-0000-4000-8000-000000000001", filename = "source.mp4", duration = 4.0)
    private val original = EditState(listOf(TimelineClip(sourceID = source.id, start = 0.0, end = 4.0)))
    private val start = EditorRangeTiming.Edge.Start
    private val end = EditorRangeTiming.Edge.End

    private fun ranges(state: EditState, tool: EditorTool) = ToolRegions.of(state).filter { it.tool == tool }.map { it.start to it.end }

    private fun near(expected: List<Pair<Double, Double>>, actual: List<Pair<Double, Double>>) {
        assertEquals(expected.size, actual.size)
        for ((e, a) in expected.zip(actual)) {
            assertEquals(e.first, a.first, 1e-5)
            assertEquals(e.second, a.second, 1e-5)
        }
    }

    private fun trim(state: EditState, tool: EditorTool, from: Double, to: Double, at: Double? = null): EditState {
        val badge = ToolRegions.of(state).first { it.tool == tool && (at == null || at in it.start..it.end) }
        val draft = assertNotNull(EffectRangeEdit.of(badge, state, at ?: from)).let { EffectRangeEdit.of(badge, state, at ?: from)!! }
        return draft.setting(start, from).setting(end, to).applying(state)
    }

    private fun withTimeline(state: EditState, change: (TrackTimeline) -> TrackTimeline): EditState = EffectApplications.editing(state, change)

    private fun video(state: EditState, change: (TimelineClip) -> TimelineClip): EditState =
        withTimeline(state) { t -> t.copy(video = t.video.mapIndexed { i, item -> if (i == 0) item.copy(content = change(item.content)) else item }) }

    // MARK: timelineEffectRangeTests

    @Test fun framingEdgesReopenAtTheTrimmedRange() {
        var edit = video(original) { it.copy(mirror = MirrorSettings(horizontal = true), crop = CropSettings(zoom = 1.75), stabilization = StabilizationSettings(StabilizationLevel.Light)) }
        for (tool in listOf(EditorTool.Crop, EditorTool.Mirror, EditorTool.Stabilize)) edit = trim(edit, tool, 1.0, 3.0)
        for (tool in listOf(EditorTool.Crop, EditorTool.Mirror, EditorTool.Stabilize)) near(listOf(1.0 to 3.0), ranges(edit, tool))
        // Range edits preserve cuts and linked audio.
        assertEquals(original.toTimeline().audio, edit.toTimeline().audio)
        assertEquals(1, edit.clips.size)
        assertEquals(4.0, edit.duration, 1e-9)
        // Saved, reopened, undone and redone.
        var project = Project("Timeline effect ranges", id = "P", createdAt = 1).addRecording(source, now = 2)
        val base = project.edit
        val trimmed = EditState(edit.clips.map { it.copy(id = base.clips[0].id) })
        project = project.commit(trimmed, now = 3)
        project.validate()
        assertEquals(TimelineClip.SCOPED_EFFECTS_SCHEMA, project.schemaVersion)
        val files = mutableMapOf<String, ByteArray>()
        val reopened = Project.readManifest(project.manifest { name, data -> files[name] = data }) { files.getValue(it) }
        assertEquals(project.edit, reopened.edit)
        assertEquals(base, reopened.undo().edit)
        assertEquals(project.edit, reopened.undo().redo().edit)
    }

    @Test fun trimmingASharedTitleFollowsSpeedAndRetractsAcrossTheCut() {
        val clip = TimelineClip(sourceID = source.id, start = 0.0, end = 4.0, speed = SpeedSettings(rate = 2.0), texts = listOf(TextOverlay(text = "One title", start = 0.0, end = 4.0)))
        val split = TimelineEditing.split(EditState(listOf(clip)), 1.0)!!
        val scoped = trim(split, EditorTool.Text, 0.2, 0.8, at = 0.5)
        assertEquals(0.4, scoped.clips[0].texts!!.first().start, 1e-5)
        assertEquals(1.6, scoped.clips[0].texts!!.first().end, 1e-5)
        assertNull(scoped.clips[1].texts)
        assertEquals(split.toTimeline().audio, scoped.toTimeline().audio)
    }

    @Test fun aGradeStopsAtItsNeighbourAndItsMotionFollowsItsNewClock() {
        val owner = original.clips[0].id
        val warm = ColorSettings(look = ColorLook.Warm).let { it.copy(motion = EffectAnimation(it.pose, 1.0)) }
        var looks = EffectApplications.settingGrade(original, warm, 1.0..2.0, setOf(owner))
        looks = EffectApplications.settingGrade(looks, ColorSettings(look = ColorLook.Cool), 3.0..4.0, setOf(owner))
        val extended = trim(looks, EditorTool.Color, 0.5, 3.9, at = 1.5)
        val grades = extended.clips[0].visibleGrades
        assertEquals(2, grades.size)
        assertEquals(0.5, grades[0].start, 1e-5)
        assertEquals(3.0, grades[0].end, 1e-5)
        assertEquals(ColorLook.Cool, grades[1].grade.look)
        assertEquals(2.5, grades[0].grade.motion!!.duration, 1e-5)
        assertEquals(0.0, grades[0].grade.motion!!.elapsed(0.5), 1e-5)
    }

    @Test fun disjointAudioEffectsSharingAnIdentityTrimIndependently() {
        val chain = listOf(AudioEffect(kind = AudioEffectKind.Gain, amount = 3.0))
        val sound = withTimeline(original) { t ->
            t.copy(audio = t.audio.mapIndexed { i, item -> if (i == 0) item.copy(enhancements = listOf(1.0 to 2.0, 3.0 to 4.0).map { AudioEnhancementRegion("E", EditInterval.of(it.first, it.second), chain) }) else item })
        }
        val trimmed = trim(sound, EditorTool.EnhanceAudio, 2.7, 3.8, at = 3.5)
        assertEquals(listOf(EditInterval.of(1.0, 2.0), EditInterval.of(2.7, 3.8)), trimmed.toTimeline().audio[0].enhancements.map { it.interval })
    }

    @Test fun aBeepSpanningTwoTracksKeepsBothVoicesAndANewerBeepInsideIt() {
        val event = "EVENT"
        var beep = withTimeline(original) { t ->
            val second = t.audio[0].copy(id = "SECOND", occurrenceID = "SECOND-OCC", followsVideo = null)
            val both = t.copy(audio = t.audio + second)
            TrackAudio.censoring(both, AudioCensorSettings(), TimelineSelection(audio = both.audio.mapTo(HashSet()) { it.id }, range = EditInterval.of(1.0, 2.0)), event)
        }
        val changed = trim(beep, EditorTool.CensorAudio, 0.5, 2.5)
        assertTrue(changed.toTimeline().audio.all { it.censors.size == 1 && it.censors[0].id == event && it.censors[0].interval == EditInterval.of(0.5, 2.5) })
        val voice = beep.toTimeline().audio[0].id
        beep = withTimeline(beep) { TrackAudio.censoring(it, AudioCensorSettings(frequency = 600.0), TimelineSelection(audio = setOf(voice), range = EditInterval.of(1.3, 1.7)), "NEWER") }
        val protected = trim(beep, EditorTool.CensorAudio, 0.5, 2.5, at = 1.1).toTimeline()
        assertEquals(listOf(event, "NEWER", event), protected.audio[0].censors.map { it.id })
        assertEquals(listOf(EditInterval.of(0.5, 1.3), EditInterval.of(1.3, 1.7), EditInterval.of(1.7, 2.5)), protected.audio[0].censors.map { it.interval })
        assertEquals(EditInterval.of(0.5, 2.5), protected.audio[1].censors[0].interval)
    }

    private fun cuts(): EditState {
        var cuts = TimelineEditing.split(original, 1.0)!!
        cuts = TimelineEditing.split(cuts, 3.0)!!
        return EffectApplications.editing(cuts) { t ->
            t.copy(video = t.video.mapIndexed { i, item ->
                if (i != 1) item else item.copy(content = item.content.copy(
                    mirror = MirrorSettings(horizontal = true),
                    texts = listOf(TextOverlay(text = "Across both cuts", start = 1.5, end = 2.5)),
                    videoEffects = listOf(VideoEffectSettings(VideoEffectID.Noir, 1.5, 2.5)),
                ))
            })
        }
    }

    @Test fun bothHandlesCrossTwoCutsWithOneIdentity() {
        val cuts = cuts()
        val clipIDs = cuts.toTimeline().video.map { it.id }
        for (tool in listOf(EditorTool.Text, EditorTool.Mirror, EditorTool.VideoEffects)) {
            val expanded = trim(cuts, tool, 0.5, 3.5, at = 2.0)
            val parts = ToolRegions.of(expanded).filter { it.tool == tool }
            near(listOf(0.5 to 1.0, 1.0 to 3.0, 3.0 to 3.5), parts.map { it.start to it.end })
            assertEquals(1, parts.map { it.effectID }.toSet().size)
            // Extending a picture effect leaves footage and linked audio untouched.
            assertEquals(clipIDs, expanded.toTimeline().video.map { it.id })
            assertEquals(cuts.toTimeline().audio, expanded.toTimeline().audio)
            // Both ways of opening a badge recover its saved application.
            for (region in parts) {
                val scope = EffectApplications.scope(expanded, region, TimelineSelection(video = setOf(region.clipID)), openingBadge = true)
                assertEquals(0.5, scope.range.start, 1e-5)
                assertEquals(3.5, scope.range.endInclusive, 1e-5)
                assertEquals(clipIDs.toSet(), scope.clipIDs)
                val handles = EffectRangeEdit.of(region, expanded, 2.0)!!
                assertEquals(scope.range.start, handles.original.start, 1e-5)
                assertEquals(scope.range.endInclusive, handles.original.endInclusive, 1e-5)
            }
            // A handle can retract completely out of the clip it was opened from.
            val contracted = trim(expanded, tool, 1.25, 2.75, at = 3.25)
            near(listOf(1.25 to 2.75), ranges(contracted, tool))
        }
    }

    @Test fun anOverlayOnAnotherRowAndAGapAreRespected() {
        val cuts = cuts()
        val overlay = cuts.toTimeline().video[1].let { item ->
            item.copy(occurrenceID = "OVERLAY", start = EditTime.of(0.25), layer = item.layer + 10, content = item.content.copy(id = "00000000-0000-4000-8000-0000000000AA", texts = null))
        }
        val withOverlap = withTimeline(cuts) { it.copy(video = it.video + overlay) }
        val overlapped = trim(withOverlap, EditorTool.Text, 0.5, 3.5, at = 2.0)
        // Dragging on a row leaves a parallel overlay alone.
        assertEquals(overlay, overlapped.toTimeline().video.first { it.id == overlay.id })
        val gapped = withTimeline(cuts) { t -> t.copy(video = t.video.mapIndexed { i, item -> if (i == 2) item.copy(start = EditTime.of(3.5)) else item }) }
        val acrossGap = trim(gapped, EditorTool.Text, 1.5, 4.0, at = 2.0)
        val far = ToolRegions.of(acrossGap).first { it.tool == EditorTool.Text && it.clipID == gapped.toTimeline().video[2].id }
        // An application intentionally extended across empty time remains one event.
        val scope = EffectApplications.scope(acrossGap, far, openingBadge = true)
        assertEquals(1.5, scope.range.start, 1e-5)
        assertEquals(4.0, scope.range.endInclusive, 1e-5)
    }

    @Test fun audioTrimsCrossBothClipEdgesWithoutChangingVideo() {
        val cuts = cuts()
        val chain = listOf(AudioEffect(kind = AudioEffectKind.Gain, amount = 3.0))
        val audioCuts = withTimeline(cuts) { t ->
            t.copy(audio = t.audio.mapIndexed { i, item -> if (i == 1) item.copy(enhancements = listOf(AudioEnhancementRegion("E", EditInterval.of(0.5, 1.5), chain))) else item })
        }
        val extended = trim(audioCuts, EditorTool.EnhanceAudio, 0.5, 3.5, at = 2.0).toTimeline()
        assertEquals(listOf(EditInterval.of(0.5, 1.0), EditInterval.of(0.0, 2.0), EditInterval.of(0.0, 0.5)), extended.audio.map { it.enhancements.firstOrNull()?.interval })
        assertEquals(audioCuts.toTimeline().video, extended.video)
    }

    @Test fun aNewMultiClipApplicationIsRememberedAndOlderOnesStillReopen() {
        val cuts = cuts()
        val ids = cuts.toTimeline().video.map { it.id }
        val middle = ids[1]
        val fresh = TextOverlay(text = "One application", start = 0.0, end = 4.0)
        val applied = EffectApplications.remembering(EditorTool.Text, EditState(cuts.clips.map { it.copy(texts = listOf(fresh)) }), cuts)
        val badge = ToolRegions.of(applied).first { it.tool == EditorTool.Text && it.clipID == middle }
        // A freshly authored multi-clip application reopens from a selected clip.
        val reopened = EffectApplications.scope(applied, badge, TimelineSelection(video = setOf(middle)), openingBadge = true)
        assertEquals(0.0..4.0, reopened.range)
        // An older shared title reopens across cuts and still supports selected removal.
        val legacy = EditState(applied.clips.map { it.copy(spanningEffects = null) })
        assertEquals(0.0..4.0, EffectApplications.scope(legacy, badge, TimelineSelection(video = setOf(middle)), openingBadge = true).range)
        assertEquals(setOf(middle), EffectApplications.scope(legacy, badge, TimelineSelection(video = setOf(middle))).clipIDs)
        // Opening an independently restyled legacy title ignores incidental clip selection.
        val restyled = EditState(legacy.clips.mapIndexed { i, clip -> if (i == 1) clip.copy(texts = clip.texts!!.map { it.copy(text = "Edited separately") }) else clip })
        val range = EffectApplications.scope(restyled, badge, TimelineSelection(video = setOf(ids[0])), openingBadge = true).range
        assertEquals(1.0, range.start, 1e-5)
        assertEquals(3.0, range.endInclusive, 1e-5)
    }

    // A split clip's badge, with only its own picture selected, scopes that picture; a selection of both still edits
    // the shared effect together; opened from its badge, the touching halves with one recipe are one application, as
    // the editor opens every tool from a badge (Tests/VideoToolsTests.swift:1047-1071, 967-976; YouRec/EditorView.swift:1369-1380).
    // The original's request without a selection and not from a badge, which its editor never makes, is left out: here
    // the halves of a split in a row of clips are told apart by their ids rather than by one occurrence.
    @Test fun aSplitBadgeScopesItsOwnPictureAndOpensOnTheWholeApplication() {
        val long = EditState(listOf(TimelineClip(sourceID = source.id, start = 0.0, end = 8.0)))
        var state = video(long) {
            it.copy(
                videoEffects = listOf(VideoEffectSettings(VideoEffectID.Noir, 0.0, 8.0)),
                texts = listOf(TextOverlay(text = "Across the cut", start = 0.0, end = 8.0)),
                images = listOf(ImageOverlay(filename = "image-overlay.png", end = 8.0)),
                crop = CropSettings(zoom = 1.2), color = ColorSettings(look = ColorLook.Warm), mirror = MirrorSettings(horizontal = true),
            )
        }
        state = TimelineEditing.split(state, 4.0)!!
        val left = state.clips[0].id
        val right = state.clips[1].id
        val tools = listOf(EditorTool.VideoEffects, EditorTool.Text, EditorTool.Image, EditorTool.Crop, EditorTool.Color, EditorTool.Mirror)
        val regions = ToolRegions.of(state).filter { it.clipID == left && it.tool in tools }
        assertEquals(tools.toSet(), regions.map { it.tool }.toSet())
        for (region in regions) {
            assertEquals(region.tool.toString(), setOf(left), EffectApplications.scope(state, region, TimelineSelection(video = setOf(left))).clipIDs)
            assertEquals(region.tool.toString(), setOf(left, right), EffectApplications.scope(state, region, TimelineSelection(video = setOf(left, right))).clipIDs)
            val opened = EffectApplications.scope(state, region, openingBadge = true)
            assertEquals(region.tool.toString(), setOf(left, right), opened.clipIDs)
            assertEquals(EffectScope.Title.Clips(2), opened.title)
            assertEquals(0.0, opened.range.start, 1e-5)
            assertEquals(8.0, opened.range.endInclusive, 1e-5)
        }
        // A piece restyled on its own is not part of the other's application.
        val restyled = EffectApplications.editing(state) { t -> t.copy(video = t.video.map { if (it.id == right) it.copy(content = it.content.copy(crop = it.content.crop!!.copy(zoom = 2.0))) else it }) }
        val crop = ToolRegions.of(restyled).first { it.clipID == left && it.tool == EditorTool.Crop }
        assertEquals(setOf(left), EffectApplications.scope(restyled, crop, openingBadge = true).clipIDs)
        assertEquals(EffectScope.Title.ThisClip, EffectApplications.scope(restyled, crop, openingBadge = true).title)
    }

    // The Crop tool frames and flips at once: a new crop and a new flip on two clips each remember both, and editing an
    // existing one adds nothing (YouRec/VideoTools.swift:705-737; Tests/VideoToolsTests.swift:1150-1156).
    @Test fun aNewCropAndFlipOverTwoClipsAreRememberedAndAnEditedOneIsNot() {
        val before = TimelineEditing.split(original, 2.0)!!
        val crop = CropSettings(zoom = 1.5)
        val mirror = MirrorSettings(horizontal = true)
        val made = EditState(before.clips.map { it.copy(crop = crop, mirror = mirror) })
        val remembered = EffectApplications.remembering(EditorTool.Mirror, EffectApplications.remembering(EditorTool.Crop, made, before), before)
        for (item in remembered.toTimeline().video) assertEquals(setOf(crop.id, mirror.id), item.spanningEffects)
        assertEquals(made.clips.map { it.crop }, remembered.clips.map { it.crop })
        // A legacy pair edited again keeps the membership it had: none.
        val legacy = EditState(made.clips)
        val zoomed = EditState(legacy.clips.map { it.copy(crop = it.crop!!.copy(zoom = 2.0)) })
        assertTrue(EffectApplications.remembering(EditorTool.Crop, zoomed, legacy).toTimeline().video.all { it.spanningEffects == null })
        // A tool without a range of its own leaves nothing.
        assertEquals(made, EffectApplications.remembering(EditorTool.Speed, made, before))
    }

    // MARK: scopedEffectTests

    private val image = ImageOverlay(id = "IMG", filename = "image-test.png", start = 0.0, end = 4.0)

    private fun base(): EditState {
        var base = video(original) {
            it.copy(
                texts = listOf(TextOverlay(text = "Whole", start = 0.0, end = 4.0, motion = TitleMotion(entrance = TitleMove.Fade, inSeconds = 2.0))),
                crop = CropSettings(zoom = 1.5), mirror = MirrorSettings(horizontal = true), stabilization = StabilizationSettings(StabilizationLevel.Light),
                color = ColorSettings(look = ColorLook.Warm), videoEffects = listOf(VideoEffectSettings(VideoEffectID.Noir, 0.0, 4.0)), images = listOf(image),
            )
        }
        val audio = base.toTimeline().audio[0].id
        val whole = TimelineSelection(audio = setOf(audio), range = EditInterval.of(0.0, 4.0))
        base = withTimeline(base) { TrackAudio.enhancing(it, listOf(AudioEffect.make(AudioEffectKind.Gain)), whole) }
        base = withTimeline(base) { TrackAudio.censoring(it, AudioCensorSettings(), whole) }
        return base
    }

    private fun command(state: EditState, tool: EditorTool, selection: TimelineSelection?, id: String? = null): ScopedEffectOperation {
        val regions = ToolRegions.of(state)
        val region = regions.first { it.tool == tool && (id == null || it.effectID == id) }
        return ScopedEffectOperation.of(region, state, selection, regions)!!
    }

    @Test fun eachToolRemovesItsMiddleWithoutCuttingMedia() {
        val base = base()
        val timeline = base.toTimeline()
        val track = timeline.video[0].id
        val audio = timeline.audio[0].id
        val targets = TimelineSelection(video = setOf(track), audio = setOf(audio), range = EditInterval.of(1.0, 3.0))
        for (tool in listOf(EditorTool.Text, EditorTool.Image, EditorTool.Color, EditorTool.Crop, EditorTool.Mirror, EditorTool.Stabilize, EditorTool.VideoEffects, EditorTool.CensorAudio, EditorTool.EnhanceAudio)) {
            val op = command(base, tool, targets)
            // An unchanged Apply leaves no detached ids.
            assertEquals("$tool", base, op.applying(op.inspectorState(base), base))
            val edit = op.removing(base)
            val pieces = ToolRegions.of(edit).filter { it.tool == tool }
            near(listOf(0.0 to 1.0, 3.0 to 4.0), pieces.map { it.start to it.end })
            assertEquals("$tool keeps both remnants under one id", 1, pieces.map { it.effectID }.toSet().size)
            val after = edit.toTimeline()
            assertEquals(listOf(track), after.video.map { it.id })
            assertEquals(listOf(audio), after.audio.map { it.id })
            assertEquals(base.duration, edit.duration, 1e-9)
            assertEquals(timeline.links, after.links)
            // Whole removal addresses both ends.
            val whole = command(edit, tool, null)
            assertEquals(2, whole.event.size)
            assertTrue(ToolRegions.of(whole.removing(edit)).none { it.tool == tool })
            // An outer trim preserves the middle hole.
            val trimmed = EffectRangeEdit.of(pieces[1], edit, 3.5)!!.setting(start, 0.5).applying(edit)
            near(listOf(0.5 to 1.0, 3.0 to 4.0), ranges(trimmed, tool))
            // A disjoint selection cannot remove the application.
            assertNull(ScopedEffectOperation.of(op.region, base, TimelineSelection(video = setOf("X"), audio = setOf("Y"), range = EditInterval.of(1.0, 3.0))))
        }
    }

    @Test fun localAudioSettingsAffectOnlyTheSelectedSound() {
        val base = base()
        val timeline = base.toTimeline()
        val targets = TimelineSelection(video = setOf(timeline.video[0].id), audio = setOf(timeline.audio[0].id), range = EditInterval.of(1.0, 3.0))
        val enhancement = command(base, EditorTool.EnhanceAudio, targets)
        val gain = AudioEffect.make(AudioEffectKind.Gain).copy(amount = -9.0)
        val draft = withTimeline(enhancement.inspectorState(base)) { TrackAudio.enhancing(it, listOf(gain), targets, enhancement.region.effectID) }
        val local = enhancement.applying(draft, base).toTimeline().audio[0].enhancements
        assertEquals(listOf(EditInterval.of(0.0, 1.0), EditInterval.of(1.0, 3.0), EditInterval.of(3.0, 4.0)), local.map { it.interval })
        assertEquals(listOf(gain), local[1].effects)
        assertEquals(local[0].id, local[2].id)
        assertNotEquals(local[0].id, local[1].id)
    }

    @Test fun aLocalCropIsAVariantThatEditsAndSurvivesIndependently() {
        val base = base()
        val track = base.toTimeline().video[0].id
        val targets = TimelineSelection(video = setOf(track), range = EditInterval.of(1.0, 3.0))
        val crop = command(base, EditorTool.Crop, targets)
        val parent = crop.region.effectID
        fun zooms(edit: EditState) = listOf(0.5, 1.5, 3.5).map { t -> edit.clips[0].resolvedEffectPieces.first { it.start <= t && t < it.end }.crop?.zoom ?: 1.0 }
        fun zoomed(state: EditState, zoom: Double) = video(state) { it.copy(crop = it.crop!!.copy(zoom = zoom)) }
        var variant = crop.applying(zoomed(crop.inspectorState(base), 2.5), base)
        val looks = ToolRegions.of(variant).filter { it.tool == EditorTool.Crop }
        val localID = looks.first { kotlin.math.abs(it.start - 1.0) < 1e-5 && kotlin.math.abs(it.end - 3.0) < 1e-5 }.effectID
        // Crop creates one independent variant without another clip.
        assertEquals(3, looks.size)
        assertNotEquals(parent, localID)
        assertEquals(listOf(1.5, 2.5, 1.5), zooms(variant))
        // A whole edit from either end leaves the variant untouched.
        val whole = command(variant, EditorTool.Crop, null, parent)
        variant = whole.applying(zoomed(whole.inspectorState(variant), 3.0), variant)
        assertEquals(listOf(3.0, 2.5, 3.0), zooms(variant))
        // A reopened local framing variant edits independently.
        val local = command(variant, EditorTool.Crop, null, localID)
        variant = local.applying(zoomed(local.inspectorState(variant), 1.75), variant)
        assertEquals(listOf(3.0, 1.75, 3.0), zooms(variant))
        // Deleting the original promotes the surviving variant.
        val removed = whole.removing(variant)
        assertEquals(listOf(1.0, 1.75, 1.0), zooms(removed))
        assertTrue(removed.clips[0].validEffectWindows)
        // A later cut clips the source-anchored variant, which follows its footage.
        val split = TimelineEditing.split(variant, 2.0)!!
        near(listOf(1.0 to 2.0, 2.0 to 3.0), ToolRegions.of(split).filter { it.effectID == localID }.map { it.start to it.end })
    }

    @Test fun aLocalTitleInheritsTheFadeUnderWay() {
        val base = base()
        val timeline = base.toTimeline()
        val targets = TimelineSelection(video = setOf(timeline.video[0].id), audio = setOf(timeline.audio[0].id), range = EditInterval.of(1.0, 3.0))
        val title = command(base, EditorTool.Text, targets)
        val draft = video(title.inspectorState(base)) { it.copy(texts = it.texts!!.map { t -> t.copy(text = "Local") }) }
        val titles = title.applying(draft, base)
        val local = titles.clips[0].resolvingEffects(1.5).texts!!.first()
        assertEquals("Local", local.text)
        assertEquals(1.5, local.playbackClock!!.elapsed(1.5), 1e-9)
        // Local variants survive saving, reopening, Undo and Redo.
        var project = Project("Scoped effects", id = "P", createdAt = 1).addRecording(source, now = 2)
        project = project.commit(EditState(base.clips.map { it.copy(id = project.edit.clips[0].id) }), now = 3)
        val before = project.edit
        project = project.commit(EditState(titles.clips.map { it.copy(id = before.clips[0].id) }), now = 4)
        project.validate()
        val files = mutableMapOf<String, ByteArray>()
        val reopened = Project.readManifest(project.manifest { name, data -> files[name] = data }) { files.getValue(it) }
        assertEquals(project.edit, reopened.edit)
        assertEquals(before, reopened.undo().edit)
    }
}
