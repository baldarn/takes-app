package app.takes.core

import kotlinx.serialization.Serializable
import java.io.File

/**
 * What the recorder's live effects worked out while a take was recorded, saved beside it so that the editor
 * draws the take with what the preview showed: the original's `CapturedEffectAnalysis`
 * (YouRec/VideoEffects/VideoEffectCaptureAnalysis.swift:25-36), in its JSON. Here it carries the palettes a
 * Quantization showed, by the recipe's effect, in seconds of the segment; the person masks, the qualities, the
 * subjects and the temporal mattes the original also keeps are not written, and the editor works those out itself.
 */
@Serializable
data class CapturedEffectAnalysis(
    val version: Int = 1,
    val palettes: Map<String, Track> = emptyMap(),
    val masks: List<MaskFrame> = emptyList(),
) {
    @Serializable
    data class Frame(val time: Double, val palette: EffectPalette)

    @Serializable
    data class Track(val frames: List<Frame>) {
        val track: EffectPaletteTrack get() = EffectPaletteTrack(frames.map { EffectPaletteFrame(it.time, it.palette) })
    }

    /** A stored person mask (VideoEffectCaptureAnalysis.swift:6-12); none is written yet. */
    @Serializable
    data class MaskFrame(val time: Double, val offset: Long, val bytes: Int, val width: Int, val height: Int)

    /** Whether a reader may trust it, as the original's checks (VideoEffectCaptureAnalysis.swift:164-190). */
    val valid: Boolean
        get() = version == 1 && palettes.size <= 100 && palettes.values.all { track ->
            track.frames.size <= 2_000_000 && track.frames.all { it.time.isFinite() && it.time >= 0 && it.palette.valid } &&
                track.frames.zipWithNext().all { (a, b) -> a.time < b.time }
        }

    /**
     * Collects the analysis of one segment as its frames are drawn
     * (VideoEffectCaptureAnalysis.swift:78-106): a palette is kept only when it differs from the effect's last one,
     * and only at a time after it. Safe to call from the frames' thread while another thread saves it.
     */
    class Recorder(val metadataName: String = "effect-${newID()}.json", val dataName: String = "effect-${newID()}.bin") {
        private val palettes = LinkedHashMap<String, MutableList<Frame>>()

        @Synchronized
        fun append(time: Double, shown: Map<String, EffectPalette>) {
            if (!time.isFinite() || time < 0) return
            for ((id, palette) in shown) {
                val frames = palettes.getOrPut(id) { mutableListOf() }
                val last = frames.lastOrNull()
                if (last != null && (last.palette == palette || time <= last.time)) continue
                frames += Frame(time, palette)
            }
        }

        @Synchronized
        fun snapshot(): CapturedEffectAnalysis = CapturedEffectAnalysis(palettes = palettes.mapValues { Track(it.value.toList()) })

        /** Writes what was collected beside a take in [folder], the data file empty, as the original's checkpoint (VideoEffectCaptureAnalysis.swift:144-149). */
        fun save(folder: File) {
            val data = File(folder, dataName)
            if (!data.exists()) data.createNewFile()
            ProjectDisk.writeAtomically(documentJson.encodeToString(serializer(), snapshot()).toByteArray(), File(folder, metadataName))
        }

        /** The effects of a recipe pointing at this analysis, each by its own id (LiveVideoEffects.swift:329-333, 376-380). */
        fun label(recipe: List<VideoEffectSettings>): List<VideoEffectSettings> =
            recipe.map { it.copy(analysisFile = metadataName, analysisDataFile = dataName, capturedEffectID = it.id) }
    }

    companion object {
        /** The analysis in [file], or null when there is none or it cannot be trusted. */
        fun read(file: File): CapturedEffectAnalysis? = try {
            if (!file.isFile || file.length() > 64_000_000) null
            else documentJson.decodeFromString(serializer(), file.readText()).takeIf { it.valid }
        } catch (_: Exception) {
            null
        }

        /** Whether a take's live effects call for an analysis: any effect on but a Dolly Zoom (LiveVideoEffects.swift:307). */
        fun wanted(effects: List<VideoEffectSettings>): Boolean = effects.any { it.enabled && it.kind != VideoEffectID.DollyZoom }

        /**
         * Whether the palettes captured for [effect] still draw it as the preview did
         * (YouRec/MediaRenderer.swift:413-419): it has an analysis, its colours and its palette lock are as recorded,
         * the effects before it give the same picture as the recorded ones before theirs, and the clip's own colour,
         * crop, mirror and stabilisation are untouched ([plain]). [recipe] is what was saved beside the take.
         */
        fun palettesHold(effect: VideoEffectSettings, clipEffects: List<VideoEffectSettings>, recipe: List<VideoEffectSettings>?, plain: Boolean): Boolean {
            if (effect.analysisFile == null || !plain) return false
            val original = recipe?.firstOrNull { it.analysisID == effect.analysisID } ?: return false
            val inputs = setOf("colors", "lockPalette")
            val samePalette = original.parameters.filter { it.id in inputs } == effect.parameters.filter { it.id in inputs }
            val sameInput = LiveVideoEffects.prefix(recipe, original.id).map { it.captureIdentity } ==
                LiveVideoEffects.prefix(clipEffects, effect.id).map { it.captureIdentity }
            return samePalette && sameInput
        }
    }
}
