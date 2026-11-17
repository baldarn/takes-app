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
}
