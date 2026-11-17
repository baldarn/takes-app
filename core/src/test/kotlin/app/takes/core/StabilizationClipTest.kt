package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * A clip's stabilisation as the project keeps it (YouRec/Project.swift:47-48, 513-517,
 * YouRec/StabilizationPath.swift:24-40), the camera path measured frame by frame
 * (YouRec/VideoStabilizer.swift:119-206), its cache key (:33-35), and the correction in the
 * upright picture the port's renderers draw (YouRec/VideoToolCompositor.swift:158-161).
 * Tests marked TRANSLATED carry over Tests/StabilizationTests.swift.
 */
class StabilizationClipTest {
    private val take = SourceMedia(id = "S1", filename = "take.mp4", duration = 20.0, recordedAt = 5)

    private fun project() = Project("Trip", id = "P", createdAt = 1).addRecording(take, now = 2)

    private fun files(project: Project): Pair<ByteArray, Map<String, ByteArray>> {
        val files = mutableMapOf<String, ByteArray>()
        val manifest = project.manifest { name, data -> files[name] = data }
        return manifest to files
    }

    private fun reopened(project: Project): Project {
        val (manifest, files) = files(project)
        return Project.readManifest(manifest) { files.getValue(it) }
    }

    // MARK: The clip's setting

    @Test fun aClipWithoutStabilisationIsWrittenAsBefore() {
        val clip = TimelineClip(id = "C1", sourceID = "S1", start = 0.0, end = 10.0)
        assertEquals("""{"id":"C1","sourceID":"S1","start":0.0,"end":10.0}""", documentJson.encodeToString(TimelineClip.serializer(), clip))
        assertEquals(1, clip.schema)
        val base = project()
        val cut = base.commit(TimelineEditing.split(base.edit, 4.0)!!, now = 3)
        val (_, files) = files(cut)
        assertFalse(files.values.any { it.decodeToString().contains("stabilization") })
        assertEquals(17, cut.schemaVersion)
    }

    /** The original's keys and raw values (StabilizationPath.swift:4-5, 24-32). */
    @Test fun theSettingIsSavedAsTheOriginalSavesItAndRaisesTheSchema() {
        val settings = StabilizationSettings(StabilizationLevel.Strong, id = "E1", referenceStart = 0.0, referenceEnd = 10.0)
        val clip = TimelineClip(id = "C1", sourceID = "S1", start = 0.0, end = 10.0, stabilization = settings)
        val json = documentJson.encodeToString(TimelineClip.serializer(), clip)
        assertTrue(json, json.endsWith(""""stabilization":{"level":"strong","version":1,"id":"E1","referenceStart":0.0,"referenceEnd":10.0}}"""))
        assertEquals(clip, documentJson.decodeFromString(TimelineClip.serializer(), json))
        assertEquals(24, clip.schema)
        assertEquals(24, TimelineClip.STABILIZATION_SCHEMA)
        // As the original writes it: whole numbers without a point, no id and no reference range.
        val swift = documentJson.decodeFromString(TimelineClip.serializer(), """{"id":"C1","sourceID":"S1","start":0,"end":4,"stabilization":{"level":"light","version":1}}""")
        assertEquals(StabilizationSettings(StabilizationLevel.Light), swift.stabilization)
        for (level in StabilizationLevel.entries) {
            assertEquals("\"${level.id}\"", documentJson.encodeToString(StabilizationLevel.serializer(), level))
        }
    }

    /** TRANSLATED StabilizationTests.swift:63-74: through undo and redo saved and read again, a split, and an old clip. */
    @Test fun theSettingSurvivesTheHistoryASplitAndOldClips() {
        val base = project()
        val strong = TimelineEditing.setStabilization(base.edit, 0, StabilizationLevel.Strong)!!
        val saved = base.commit(strong, now = 3).undo(now = 4)
        assertEquals(24, saved.schemaVersion)
        saved.validate()
        val read = reopened(saved).redo(now = 5)
        assertEquals(StabilizationLevel.Strong, read.edit.clips[0].stabilization?.level)
        val split = TimelineEditing.split(read.edit, 2.0)!!
        assertTrue(split.clips.all { it.stabilization?.level == StabilizationLevel.Strong })
        // Both pieces keep the reference range of the whole take, so they share one path and one crop.
        assertTrue(split.clips.all { it.stabilizationStart == 0.0 && it.stabilizationEnd == 20.0 })
        val legacy = documentJson.decodeFromString(TimelineClip.serializer(), """{"id":"C1","sourceID":"S1","start":0,"end":4}""")
        assertNull(legacy.stabilization)
    }

    /** An older version refuses the project rather than show it unstabilised. */
    @Test fun anOlderReaderRefusesAndANewerProjectIsRefused() {
        val base = project()
        val edited = base.commit(TimelineEditing.setStabilization(base.edit, 0, StabilizationLevel.Standard)!!, now = 3)
        val (manifest, files) = files(edited)
        assertTrue(manifest.decodeToString().contains("\"schemaVersion\":24"))
        assertFalse(Project.isNewer(manifest))
        val newer = manifest.decodeToString().replaceFirst("\"schemaVersion\":24", "\"schemaVersion\":${Project.LATEST_READABLE_SCHEMA + 1}")
        try { Project.readManifest(newer.toByteArray()) { files.getValue(it) }; fail("opened") } catch (refused: ProjectException) {
            assertEquals(ProjectException.Reason.NewerVersion, refused.reason)
        }
        // A version that knew the pictures over a clip but not stabilisation refuses it.
        assertTrue(24 > TimelineClip.IMAGES_SCHEMA)
    }

    /** YouRec/EditorTools.swift:710-712: Off takes the setting away; a level keeps the reference range and the effect's id. */
    @Test fun theToolSetsAndTakesAwayALevel() {
        val base = project().edit
        val light = TimelineEditing.setStabilization(base, 0, StabilizationLevel.Light, id = "E1")!!
        assertEquals(StabilizationSettings(StabilizationLevel.Light, id = "E1", referenceStart = 0.0, referenceEnd = 20.0), light.clips[0].stabilization)
        assertNull(TimelineEditing.setStabilization(light, 0, StabilizationLevel.Light))
        val strong = TimelineEditing.setStabilization(light, 0, StabilizationLevel.Strong)!!
        assertEquals("E1", strong.clips[0].stabilization?.id)
        assertNull(TimelineEditing.setStabilization(strong, 0, StabilizationLevel.Off)!!.clips[0].stabilization)
        assertNull(TimelineEditing.setStabilization(base, 0, StabilizationLevel.Off))
        // A trimmed piece keeps the range it was measured over.
        val trimmed = strong.clips[0].copy(start = 5.0, end = 8.0)
        assertEquals(0.0, trimmed.stabilizationStart, 0.0)
        assertEquals(20.0, trimmed.stabilizationEnd, 0.0)
    }

    /** YouRec/Project.swift:513-517. */
    @Test fun settingsTheOriginalRefusesAreRefused() {
        assertTrue(StabilizationSettings(StabilizationLevel.Light).valid(10.0))
        assertTrue(StabilizationSettings(StabilizationLevel.Light, referenceStart = 0.0, referenceEnd = 10.0005).valid(10.0))
        assertFalse(StabilizationSettings(StabilizationLevel.Light, version = 2).valid(10.0))
        assertFalse(StabilizationSettings(StabilizationLevel.Light, referenceStart = 1.0).valid(10.0))
        assertFalse(StabilizationSettings(StabilizationLevel.Light, referenceEnd = 1.0).valid(10.0))
        assertFalse(StabilizationSettings(StabilizationLevel.Light, referenceStart = 2.0, referenceEnd = 1.0).valid(10.0))
        assertFalse(StabilizationSettings(StabilizationLevel.Light, referenceStart = 0.0, referenceEnd = 11.0).valid(10.0))
        val base = project()
        val bad = base.edit.copy(clips = base.edit.clips.map { it.copy(stabilization = StabilizationSettings(StabilizationLevel.Strong, version = 2)) })
        try { base.commit(bad, now = 3).validate(); fail("valid") } catch (refused: ProjectException) {
            assertEquals(ProjectException.Reason.InvalidDocument, refused.reason)
        }
    }

    @Test fun theChangeButtonsStopAtAStabilisedClip() {
        val base = project().edit
        val split = TimelineEditing.split(base, 10.0)!!
        val one = TimelineEditing.setStabilization(split, 1, StabilizationLevel.Light)!!
        assertFalse(10.0 in TimelineNavigation.of(split).changes)
        assertTrue(10.0 in TimelineNavigation.of(one).changes)
    }

    // MARK: Measuring

    /** YouRec/VideoStabilizer.swift:129-130. */
    @Test fun framesAreComparedAtMostSixHundredFortyPixelsLong() {
        fun size(w: Int, h: Int) = StabilizationMeasure(w, h, 1.0).let { it.analysisWidth to it.analysisHeight }
        assertEquals(640 to 360, size(1920, 1080))
        assertEquals(640 to 360, size(3840, 2160))
        assertEquals(360 to 640, size(1080, 1920))
        assertEquals(640 to 480, size(640, 480))
        assertEquals(100 to 64, size(100, 50))
        // 1440 × 1080: 640 × 480; 1000 × 562: 562 × 0.64 = 359.68, rounded.
        assertEquals(640 to 480, size(1440, 1080))
        assertEquals(640 to 360, size(1000, 562))
    }

    private fun motion(x: Double = 0.0, y: Double = 0.0, angle: Double = 0.0, valid: Boolean = true) = StabilizationMeasure.Motion(x, y, angle, 0.0, valid)

    /** YouRec/VideoStabilizer.swift:151-205. */
    @Test fun motionsChainIntoAPathAndAnUntrackedFrameStartsASegment() {
        val measure = StabilizationMeasure(1920, 1080, 2.0)
        // The first frame of the stretch is at its start, whatever its own time.
        measure.add(0.01, null)
        measure.add(1 / 30.0 + 0.01, motion(x = 1.0, y = -2.0))
        measure.add(2 / 30.0 + 0.01, motion(x = 1.0, angle = 0.01))
        // Already measured, and past the stretch: not measured.
        assertFalse(measure.accepts(2 / 30.0 + 0.01))
        assertFalse(measure.accepts(2.0))
        assertFalse(measure.accepts(Double.NaN))
        measure.add(0.1 + 0.01, motion(valid = false))
        measure.add(0.15, motion(x = 1.0))
        // More than 0.15 s after the frame before.
        measure.add(0.31, motion(x = 1.0))
        measure.add(0.34, motion(x = 1.0))
        val analysis = measure.analysis()!!
        assertTrue(analysis.valid)
        val samples = analysis.samples
        assertEquals(listOf(0.0, 1 / 30.0 + 0.01, 2 / 30.0 + 0.01, 0.11, 0.15, 0.31, 0.34), samples.map { it.time })
        assertEquals(listOf(0, 0, 0, 1, 1, 2, 2), samples.map { it.segment })
        // A pixel at 640 × 360 is three of the recording's.
        assertEquals(StabilizationPose(3.0, -6.0), samples[1].pose)
        val second = StabilizationPose(3.0, 0.0, 0.01).applying(StabilizationPose(3.0, -6.0))
        assertEquals(second, samples[2].pose)
        assertEquals(StabilizationPose.IDENTITY, samples[3].pose)
        assertEquals(StabilizationPose(3.0), samples[4].pose)
        assertEquals(StabilizationPose.IDENTITY, samples[5].pose)
        assertEquals(StabilizationPose(3.0), samples[6].pose)
        assertEquals(4, analysis.trackedFrames)
        assertEquals(4 / 6.0, analysis.trackingFraction, 1e-12)
        assertNull(StabilizationMeasure(1920, 1080, 2.0).analysis())
        assertEquals(0.0, StabilizationMeasure.time(0.9, 1.0), 0.0)
        assertEquals(0.5, StabilizationMeasure.time(1.5, 1.0), 0.0)
    }

    // MARK: The cache

    /** YouRec/VideoStabilizer.swift:33-35, 46-48, 69. */
    @Test fun anAnalysisIsKeptUnderItsRecordingAndStretchAndReadBack() {
        val key = StabilizationAnalysis.key("abc", 0.0, 4.0)
        assertEquals(64, key.length)
        assertEquals(key, StabilizationAnalysis.key("abc", 0.0, 4.0))
        assertNotEquals(key, StabilizationAnalysis.key("abc", 0.0, 5.0))
        assertNotEquals(key, StabilizationAnalysis.key("abd", 0.0, 4.0))
        val analysis = StabilizationAnalysis(1920, 1080, 4.0, listOf(StabilizationSample(0.0, StabilizationPose.IDENTITY, 0), StabilizationSample(0.5, StabilizationPose(1.5, -2.0, 0.01, 0.001), 0)), 1)
        assertEquals(analysis, StabilizationAnalysis.decode(analysis.encoded()))
        // As the original's encoder writes it.
        val swift = """{"width":1920,"height":1080,"duration":4,"samples":[{"time":0,"pose":{"x":0,"y":0,"angle":0,"logScale":0},"segment":0}],"trackedFrames":0}"""
        assertEquals(1, StabilizationAnalysis.decode(swift.toByteArray())!!.samples.size)
        assertNull(StabilizationAnalysis.decode("{".toByteArray()))
    }

    /** YouRec/VideoStabilizer.swift:104-109: a sample's corrections keep the clip's clock. */
    @Test fun aSamplesPlanKeepsTheClipsClock() {
        val plan = StabilizationPlan(Size(640.0, 360.0), listOf(StabilizationSample(0.0, StabilizationPose(1.0), 0), StabilizationSample(1.0, StabilizationPose(2.0), 0)), 1.05, false, 1.0)
        val shifted = plan.shifted(3.0)
        assertEquals(listOf(3.0, 4.0), shifted.samples.map { it.time })
        assertEquals(plan.zoom, shifted.zoom, 0.0)
    }

    // MARK: The upright picture

    /** The correction of the encoded frame carried through its turn: the same picture either way. */
    @Test fun theCorrectionFollowsTheRecordingsTurn() {
        val size = Size(1920.0, 1080.0)
        val plan = StabilizationPlan(size, listOf(StabilizationSample(0.0, StabilizationPose(10.0, -4.0, 0.02), 0)), 1.07, false, 1.0)
        assertEquals(plan.transform(0.0), plan.uprightTransform(0.0, 0))
        for (rotation in listOf(90, 180, 270, -90)) {
            val turn = plan.upright(rotation)
            val corrected = plan.uprightTransform(0.0, rotation)
            for (p in listOf(Point(0.0, 0.0), Point(1920.0, 1080.0), Point(300.0, 700.0))) {
                val expected = p.applying(plan.transform(0.0)).applying(turn)
                val shown = p.applying(turn).applying(corrected)
                assertEquals("$rotation $p", expected.x, shown.x, 1e-6)
                assertEquals("$rotation $p", expected.y, shown.y, 1e-6)
            }
            // The encoded frame's corners land on the upright frame's.
            val upright = if (rotation % 180 == 0) size else Size(size.height, size.width)
            val corners = listOf(Point(0.0, 0.0), Point(1920.0, 0.0), Point(0.0, 1080.0), Point(1920.0, 1080.0)).map { it.applying(turn) }
            assertTrue(corners.all { it.x in listOf(0.0, upright.width) && it.y in listOf(0.0, upright.height) })
        }
        // Turned a quarter clockwise, a move to the right of the encoded frame is a move down the upright one.
        val right = StabilizationPlan(size, listOf(StabilizationSample(0.0, StabilizationPose(10.0), 0)), 1.0, false, 1.0).uprightTransform(0.0, 90)
        assertEquals(0.0, right.tx, 1e-9)
        assertEquals(10.0, right.ty, 1e-9)
    }

    /** The preview's layer puts every source point where the correction and then the framing do. */
    @Test fun theLayerOfAStabilisedFrameMatchesItsTransform() {
        val source = Size(1920.0, 1080.0)
        val correction = StabilizationPose(12.0, -7.0, 0.03).transform(source, 1.08)
        for (canvas in listOf(Size(1920.0, 1080.0), Size(1080.0, 1920.0), Size(1080.0, 1080.0))) {
            for (mirror in listOf(null, MirrorSettings(horizontal = true), MirrorSettings(horizontal = true, vertical = true))) {
                for (crop in listOf(null, CropSettings(x = 0.3, y = 0.6, zoom = 1.7, rotation = 23.0))) {
                    val frame = PictureFraming.frame(crop, 0.0, source, canvas, mirror).stabilized(correction)
                    val layer = frame.layer
                    val fitted = minOf(canvas.width / source.width, canvas.height / source.height)
                    val radians = layer.rotation * Math.PI / 180
                    for (p in listOf(Point(0.0, 0.0), Point(1920.0, 0.0), Point(700.0, 900.0))) {
                        val x = (p.x - source.width / 2) * fitted * layer.scaleX
                        val y = (p.y - source.height / 2) * fitted * layer.scaleY
                        val shown = Point(
                            canvas.width / 2 + layer.translationX + x * cos(radians) - y * sin(radians),
                            canvas.height / 2 + layer.translationY + x * sin(radians) + y * cos(radians),
                        )
                        val expected = p.applying(correction).applying(frame.transform)
                        assertEquals("$canvas $mirror $crop $p", expected.x, shown.x, 1e-6)
                        assertEquals("$canvas $mirror $crop $p", expected.y, shown.y, 1e-6)
                    }
                }
            }
        }
    }

    // MARK: The tool's preview

    /** YouRec/EffectApplication.swift:9-18. */
    @Test fun theToolPreviewsTenSecondsAroundThePlayhead() {
        assertEquals(15.0..25.0, EffectPreviewWindow.range(20.0, 0.0..60.0))
        assertEquals(0.0..7.0, EffectPreviewWindow.range(2.0, 0.0..60.0))
        assertEquals(10.0..14.0, EffectPreviewWindow.range(30.0, 10.0..14.0))
        assertEquals("10", EffectPreviewWindow.seconds(15.0..25.0))
        assertEquals("4", EffectPreviewWindow.seconds(10.0..14.0))
        assertEquals("2", EffectPreviewWindow.seconds(0.0..2.5))
        assertEquals("0.4", EffectPreviewWindow.seconds(1.0..1.4))
    }
}
