package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The live effects' analysis saved with a take (C/CapturedEffectAnalysis.kt). */
class CapturedEffectAnalysisTest {
    private val two = EffectPalette(listOf(EffectRGB(0.1, 0.1, 0.1), EffectRGB(0.9, 0.9, 0.9)), listOf(0.5))
    private val other = EffectPalette(listOf(EffectRGB(0.2, 0.1, 0.1), EffectRGB(0.8, 0.9, 0.9)), listOf(0.4))

    // YouRec/VideoEffects/VideoEffectCaptureAnalysis.swift:101-106: a palette is kept only when it changes.
    @Test fun keepsAPaletteOnlyWhenItChangesAndTimeMovesOn() {
        val recorder = CapturedEffectAnalysis.Recorder()
        recorder.append(0.0, mapOf("Q" to two))
        recorder.append(0.033, mapOf("Q" to two))
        recorder.append(0.2, mapOf("Q" to other))
        recorder.append(0.1, mapOf("Q" to two))
        val track = recorder.snapshot().palettes.getValue("Q").track
        assertEquals(listOf(0.0, 0.2), track.frames.map { it.time })
        assertEquals(two, track.palette(0.19))
        assertEquals(other, track.palette(5.0))
    }

    // VideoEffectCaptureAnalysis.swift:25-36, 144-149: the original's JSON, with its empty data file beside it.
    @Test fun savesTheOriginalsJsonAndReadsItBack() {
        val folder = Files.createTempDirectory("analysis").toFile()
        val recorder = CapturedEffectAnalysis.Recorder()
        recorder.append(1.0, mapOf("Q" to two))
        recorder.save(folder)
        val text = folder.resolve(recorder.metadataName).readText()
        assertTrue(text.contains("\"version\":1"))
        assertTrue(text.contains("\"palettes\":{\"Q\":{\"frames\":[{\"time\":1.0,\"palette\":{\"colors\":[{\"red\":0.1"))
        assertTrue(text.contains("\"masks\":[]"))
        assertEquals(0L, folder.resolve(recorder.dataName).length())
        assertEquals(two, CapturedEffectAnalysis.read(folder.resolve(recorder.metadataName))!!.palettes.getValue("Q").track.palette(2.0))
        folder.resolve("bad.json").writeText("{\"version\":2}")
        assertNull(CapturedEffectAnalysis.read(folder.resolve("bad.json")))
        folder.deleteRecursively()
    }

    // LiveVideoEffects.swift:307, 329-333, 376-380.
    @Test fun labelsTheRecipeAndIsWantedForAnyEffectButADollyZoom() {
        val recorder = CapturedEffectAnalysis.Recorder()
        val q = VideoEffectSettings(VideoEffectID.Quantization)
        val labelled = recorder.label(listOf(q)).single()
        assertEquals(recorder.metadataName, labelled.analysisFile)
        assertEquals(recorder.dataName, labelled.analysisDataFile)
        assertEquals(q.id, labelled.capturedEffectID)
        assertTrue(labelled.valid)
        assertFalse(CapturedEffectAnalysis.wanted(listOf(VideoEffectSettings(VideoEffectID.DollyZoom))))
        assertFalse(CapturedEffectAnalysis.wanted(listOf(q.copy(enabled = false))))
        assertTrue(CapturedEffectAnalysis.wanted(listOf(q)))
    }

    // YouRec/MediaRenderer.swift:413-419: captured palettes hold until the colours, the lock, the effects before or the clip change.
    @Test fun palettesHoldUntilTheirInputsChange() {
        val recorder = CapturedEffectAnalysis.Recorder()
        val noir = VideoEffectSettings(VideoEffectID.Noir)
        val q = VideoEffectSettings(VideoEffectID.Quantization)
        val recipe = recorder.label(listOf(noir, q))
        val clip = recipe.map { it.copy(id = newID()) }
        assertTrue(CapturedEffectAnalysis.palettesHold(clip[1], clip, recipe, plain = true))
        assertFalse(CapturedEffectAnalysis.palettesHold(clip[1], clip, recipe, plain = false))
        assertFalse(CapturedEffectAnalysis.palettesHold(clip[1], clip, null, plain = true))
        val fewer = clip[1].with("colors", EffectValue.Number(3.0))
        assertFalse(CapturedEffectAnalysis.palettesHold(fewer, listOf(clip[0], fewer), recipe, plain = true))
        val darker = clip[0].with("contrast", EffectValue.Number(1.2))
        assertFalse(CapturedEffectAnalysis.palettesHold(clip[1], listOf(darker, clip[1]), recipe, plain = true))
        assertFalse(CapturedEffectAnalysis.palettesHold(q, listOf(q), recipe, plain = true))
    }

    private fun mask(time: Double, value: Int) = PersonMaskChunks.Mask(time, 4, 2, ByteArray(8) { (value + it).toByte() })

    // VideoEffectCaptureAnalysis.swift:107-125, 246-282: a mask is kept when it is another one, at the time of the
    // first frame drawn with it, and the one shown at a time is the last at or before it.
    @Test fun keepsEachMaskOnceAndReadsItBackByTime() {
        val folder = Files.createTempDirectory("analysis").toFile()
        val recorder = CapturedEffectAnalysis.Recorder()
        recorder.begin(folder)
        val a = mask(0.0, 10)
        val b = mask(0.1, 50)
        val smooth = mask(0.0, 90)
        recorder.append(0.0, emptyMap(), a, smooth)
        recorder.append(0.033, emptyMap(), a, smooth)
        recorder.append(0.067, emptyMap(), b, null)
        recorder.save(folder, last = true)
        val data = folder.resolve(recorder.dataName)
        val analysis = CapturedEffectAnalysis.read(folder.resolve(recorder.metadataName), data)!!
        assertEquals(listOf(0.0, 0.067), analysis.masks.map { it.time })
        assertEquals(listOf(0.0), analysis.autoBackgroundMasks!!.map { it.time })
        assertTrue(analysis.hasMotionAwareAutoBackground)
        assertEquals(CapturedEffectAnalysis.FILTER, analysis.maskFilter)
        CapturedEffectAnalysis.Masks(analysis, data).use { masks ->
            assertEquals(a.rows.toList(), masks.mask(0.05)!!.rows.toList())
            assertEquals(b.rows.toList(), masks.mask(9.0)!!.rows.toList())
            // Before them all, the first (:264-265).
            assertEquals(10.toByte(), masks.mask(-1.0)!!.rows[0])
            assertEquals(smooth.rows.toList(), masks.mask(3.0, auto = true)!!.rows.toList())
        }
        // A data file shorter than the masks it should hold is not trusted (:186-187).
        data.writeBytes(ByteArray(3))
        assertNull(CapturedEffectAnalysis.read(folder.resolve(recorder.metadataName), data))
        folder.deleteRecursively()
    }

    // The masks found on a picture the preview's Crash Zoom pushed in are not the recording's: a gap, where the editor finds its own.
    @Test fun leavesAGapWhereTheMasksWereOfAPushedInPicture() {
        val folder = Files.createTempDirectory("analysis").toFile()
        val recorder = CapturedEffectAnalysis.Recorder()
        recorder.begin(folder)
        recorder.append(0.0, emptyMap(), mask(0.0, 1), mask(0.0, 2))
        recorder.append(1.0, emptyMap(), mask(1.0, 3), mask(1.0, 4), pushed = true)
        recorder.append(2.0, emptyMap(), mask(2.0, 5), mask(2.0, 6))
        recorder.save(folder, last = true)
        val data = folder.resolve(recorder.dataName)
        val analysis = CapturedEffectAnalysis.read(folder.resolve(recorder.metadataName), data)!!
        assertEquals(listOf(0.0, 2.0), analysis.masks.map { it.time })
        assertEquals(listOf(CapturedEffectAnalysis.Gap(1.0, 2.0)), analysis.maskGaps)
        CapturedEffectAnalysis.Masks(analysis, data).use { masks ->
            assertEquals(1.toByte(), masks.mask(0.5)!!.rows[0])
            assertNull(masks.mask(1.5))
            assertEquals(5.toByte(), masks.mask(2.5)!!.rows[0])
        }
        assertTrue(CapturedEffectAnalysis.masksServe(analysis, auto = true, from = 2.0, to = 4.0))
        assertFalse(CapturedEffectAnalysis.masksServe(analysis, auto = true, from = 0.0, to = 4.0))
        folder.deleteRecursively()
    }

    // MediaRenderer.swift:376-378: saved masks serve unless Auto Background wants smoothed ones that were not kept.
    @Test fun savedMasksServeAutoBackgroundOnlyWithItsSmoothedOnes() {
        val raw = CapturedEffectAnalysis(masks = listOf(CapturedEffectAnalysis.MaskFrame(0.0, 0, 1, 1, 1)))
        assertTrue(CapturedEffectAnalysis.masksServe(raw, auto = false, from = 0.0, to = 5.0))
        assertFalse(CapturedEffectAnalysis.masksServe(raw, auto = true, from = 0.0, to = 5.0))
        val both = raw.copy(autoBackgroundMasks = raw.masks, autoBackgroundMotionAware = true)
        assertTrue(CapturedEffectAnalysis.masksServe(both, auto = true, from = 0.0, to = 5.0))
        assertFalse(CapturedEffectAnalysis.masksServe(both.copy(autoBackgroundMotionAware = null), auto = true, from = 0.0, to = 5.0))
        // The original's later filter name says the same (:164).
        assertTrue(CapturedEffectAnalysis.masksServe(both.copy(autoBackgroundMotionAware = null, maskFilter = "auto-background-motion-v2"), auto = true, from = 0.0, to = 5.0))
        assertFalse(CapturedEffectAnalysis.masksServe(CapturedEffectAnalysis(), auto = false, from = 0.0, to = 5.0))
        assertFalse(CapturedEffectAnalysis.masksServe(null, auto = false, from = 0.0, to = 5.0))
        // A filter the original does not know is not trusted (:179).
        assertFalse(raw.copy(maskFilter = "other").valid)
    }

    // VideoEffectCaptureAnalysis.swift:13-24, 96-100; VideoEffectAssets.swift:474: a quality kept when it changes, full without one.
    @Test fun keepsEachEffectsQualityWhenItChanges() {
        val recorder = CapturedEffectAnalysis.Recorder()
        val full = VideoEffectQuality.Full
        val lite = VideoEffectQuality.Lite
        recorder.append(0.0, emptyMap(), null, null, mapOf("A" to full))
        recorder.append(0.5, emptyMap(), null, null, mapOf("A" to full))
        recorder.append(1.0, emptyMap(), null, null, mapOf("A" to lite))
        recorder.append(1.5, emptyMap(), null, null, mapOf("A" to lite))
        val analysis = recorder.snapshot()
        assertEquals(listOf(0.0, 1.0), analysis.qualities!!.getValue("A").frames.map { it.time })
        assertEquals(full, analysis.quality("A", 0.9))
        assertEquals(lite, analysis.quality("A", 1.0))
        assertEquals(lite, analysis.quality("A", 30.0))
        assertEquals(full, analysis.quality("B", 1.0))
        assertEquals(full, CapturedEffectAnalysis().quality("A", 1.0))
        val json = documentJson.encodeToString(CapturedEffectAnalysis.serializer(), analysis)
        assertTrue(json, json.contains("\"qualities\":{\"A\":{\"frames\":[{\"time\":0.0,\"quality\":\"full\"},{\"time\":1.0,\"quality\":\"lite\"}]}}"))
    }

    // VideoEffectCaptureAnalysis.swift:88-95, VideoEffectSubjectTracking.swift:21-41: the subject at every frame, stepwise,
    // and once where it is lost; the editor holds a sample until the next, and none after a loss.
    @Test fun keepsTheSubjectStepwiseAndWhereItIsLost() {
        val recorder = CapturedEffectAnalysis.Recorder()
        val seen = EffectSubjectSample.of(99.0, Rect(0.4, 0.3, 0.2, 0.2), 1.0, true)
        recorder.append(0.0, emptyMap(), null, null, tracking = true)
        recorder.append(0.1, emptyMap(), null, null, subject = seen, tracking = true)
        recorder.append(0.2, emptyMap(), null, null, subject = seen.copy(x = 0.5), tracking = true)
        recorder.append(0.3, emptyMap(), null, null, tracking = true)
        recorder.append(0.4, emptyMap(), null, null, tracking = true)
        val track = recorder.snapshot().subjects!!
        assertEquals(true, track.stepwise)
        assertEquals(listOf(0.1, 0.2, 0.3), track.samples.map { it.time })
        assertEquals(listOf(null, null, true), track.samples.map { it.lost })
        assertNull(track.sample(0.05))
        assertEquals(0.4, track.sample(0.15)!!.x, 1e-9)
        assertEquals(0.5, track.sample(0.29)!!.x, 1e-9)
        assertNull(track.sample(0.35))
        // Looked for in vain from the start: an empty track, which still says the take's subject was saved.
        val none = CapturedEffectAnalysis.Recorder()
        none.append(0.0, emptyMap(), null, null, tracking = true)
        assertEquals(emptyList<EffectSubjectSample>(), none.snapshot().subjects!!.samples)
        // Not tracked: no subjects at all.
        val off = CapturedEffectAnalysis.Recorder()
        off.append(0.0, emptyMap(), null, null)
        assertNull(off.snapshot().subjects)
    }

    // VideoEffectAssets.swift:375-379: the analysis of the first effect on at the time that has one.
    @Test fun theFrameReadsTheAnalysisOfTheFirstEffectOnThere() {
        val recorder = CapturedEffectAnalysis.Recorder()
        val (noir, q) = recorder.label(listOf(VideoEffectSettings(VideoEffectID.Noir), VideoEffectSettings(VideoEffectID.Quantization)))
        val added = VideoEffectSettings(VideoEffectID.Halation)
        assertEquals(q.id, CapturedEffectAnalysis.source(listOf(added, noir.copy(start = 2.0), q), 1.0)!!.id)
        assertEquals(noir.id, CapturedEffectAnalysis.source(listOf(added, noir, q), 1.0)!!.id)
        assertEquals(q.id, CapturedEffectAnalysis.source(listOf(noir.copy(enabled = false), q), 1.0)!!.id)
        assertNull(CapturedEffectAnalysis.source(listOf(added), 1.0))
    }
}
