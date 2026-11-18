package app.takes.core.yourec

import app.takes.core.FontLibrary
import app.takes.core.ImportedFontReference
import app.takes.core.Project
import app.takes.core.VideoEffectSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The files the original says a project owns, `Project.mediaFiles`
 * (YouRec/Project.swift:286-302): the sources in their order, then, sorted,
 * the pictures, the effects' files and LUTs and the imported fonts of every
 * step of the history and of the takes. A ZIP of YouRec holds these and
 * nothing else (YouRec/ProjectArchive.swift:334-341).
 *
 * It is worked out on the project as Takes reads it, with the effects the
 * takes were recorded with ([sourceEffects]), which Takes keeps beside each
 * movie rather than in the project. The pictures on every step are its
 * timeline's when it has one; the takes not yet on a step are [Project.captureLedger].
 */
object YouRecMediaFiles {
    fun of(project: Project, sourceEffects: Collection<List<VideoEffectSettings>>): List<String> {
        val clips = project.snapshots.flatMap { step -> step.timeline?.video?.map { it.content } ?: step.state.clips } + project.captureLedger
        val images = clips.flatMap { clip -> clip.images.orEmpty().map { it.filename } }
        val effects = (sourceEffects.flatten() + clips.flatMap { it.videoEffects.orEmpty() }).flatMap { it.mediaFiles }
        val luts = clips.flatMap { clip -> clip.color?.lutAssets.orEmpty().map { it.filename } }
        val fonts = project.snapshots.flatMap { step ->
            step.timeline?.let { FontLibrary.files(it.video.map { item -> item.content }, it.subtitles) } ?: FontLibrary.files(step.state)
        } + FontLibrary.files(project.captureLedger, null)
        return project.sources.map { it.filename } + (images + effects + luts + fonts).toSortedSet()
    }

    /**
     * The same rule on a project in the original's form: its manifest [manifest]
     * and its steps [steps], each `{"captureCursor":…,"state":{"timeline":…}}`.
     * The steps Takes writes for the iPhone hold every take, so the history is
     * the steps themselves; the ledger's clips are counted as the original
     * counts their titles' fonts (Project.swift:296-300), and, holding nothing
     * else, change nothing more.
     */
    fun of(manifest: JsonObject, steps: Collection<JsonObject>): List<String> {
        val sources = (manifest["sources"] as JsonArray).map { it as JsonObject }
        val ledger = (manifest["captureLedger"] as? JsonArray).orEmpty().map { (it as JsonObject)["clip"] as JsonObject }
        val timelines = steps.map { ((it["state"] as JsonObject)["timeline"] as JsonObject) }
        val clips = timelines.flatMap { timeline -> (timeline["video"] as JsonArray).map { (it as JsonObject)["content"] as JsonObject } } + ledger
        val files = sortedSetOf<String>()
        for (clip in clips) {
            for (image in clip.array("images")) image.string("filename")?.let(files::add)
            for (effect in clip.array("videoEffects")) files += effectFiles(effect)
            (clip["color"] as? JsonObject)?.let { color ->
                (color["imported"] as? JsonObject)?.string("filename")?.let(files::add)
                (color["motion"] as? JsonObject)?.let { motion ->
                    for (end in listOf("a", "b")) ((motion[end] as? JsonObject)?.get("lut") as? JsonObject)?.string("filename")?.let(files::add)
                }
            }
            for (text in clip.array("texts")) font((text["appearance"] as? JsonObject)?.string("face"))?.let(files::add)
        }
        for (timeline in timelines) font(((timeline["subtitles"] as? JsonObject)?.get("appearance") as? JsonObject)?.string("face"))?.let(files::add)
        for (source in sources) for (effect in source.array("videoEffects")) files += effectFiles(effect)
        return sources.map { it.string("filename")!! } + files
    }

    /** An effect's own files (YouRec/VideoEffects/VideoEffectModel.swift:263-268): its analysis and the media of its controls. */
    fun effectFiles(effect: JsonObject): Set<String> {
        val files = sortedSetOf<String>()
        effect.string("analysisFile")?.let(files::add)
        effect.string("analysisDataFile")?.let(files::add)
        for (parameter in effect.array("parameters")) {
            val values = listOfNotNull(parameter["value"]) + parameter.array("keyframes").mapNotNull { it["value"] }
            for (value in values) media(value)?.let(files::add)
        }
        return files
    }

    /** The file an `EffectValue.media` names, `{"media":{"_0":name}}`. */
    fun media(value: JsonElement): String? = (((value as? JsonObject)?.get("media") as? JsonObject)?.get("_0") as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun font(face: String?): String? = face?.let { ImportedFontReference.parse(it)?.filename }
    private fun JsonObject.array(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().map { it as JsonObject }
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** The name of the OpenTimelineIO file the original writes beside [mediaFiles] (YouRec/EditorialTimeline.swift:16-21). */
    fun timelineName(mediaFiles: List<String>): String {
        val taken = mediaFiles.map { it.lowercase() }.toSet()
        var name = "Timeline.otio"
        var index = 1
        while (name.lowercase() in taken) { index += 1; name = "Timeline-$index.otio" }
        return name
    }
}
