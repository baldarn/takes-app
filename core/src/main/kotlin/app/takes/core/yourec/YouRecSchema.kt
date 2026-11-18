package app.takes.core.yourec

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The schema number the original gives a project, by what it holds
 * (YouRec/Project.swift:316-330, 338-345, 378, and 333-335 at dadc258; docs/IOS-INTEROP.md, 2.1 and
 * 2.3). Takes' own numbers mean other things and are never compared with it.
 */
object YouRecSchema {
    /** The floor of every commit and of a new project (YouRec/Project.swift:235, 319). */
    const val FLOOR = 6

    /** [sources], [ledger] clips and step [timelines] in the original's form. */
    fun of(sources: List<JsonObject>, ledger: List<JsonObject>, timelines: List<JsonObject>): Int {
        var schema = FLOOR
        fun raise(to: Int) { schema = maxOf(schema, to) }
        fun present(element: Any?) = element != null && element !is JsonNull
        val placements = timelines.flatMap { it.objects("placements") }.flatMap { region ->
            (region["videos"] as? JsonArray).orEmpty().filterIndexed { index, _ -> index % 2 == 1 }.map { it as JsonObject }
        }
        val audio = timelines.flatMap { it.objects("audio") }
        val clips = timelines.flatMap { it.objects("video") }.map { it["content"] as JsonObject } + ledger
        // 5: a take recorded with effects; below the floor, so it never shows.
        if (sources.any { it.objects("videoEffects").isNotEmpty() }) raise(5)
        if (placements.any { present(it["edgeCrop"]) }) raise(7)
        if (timelines.any { present(it["subtitles"]) }) raise(8)
        if (audio.any { it.objects("censorRegions").isNotEmpty() } || sources.any { (it["audioOnly"] as? JsonPrimitive)?.content == "true" }) raise(9)
        if (audio.any { present(it["fadeIn"]) || present(it["fadeOut"]) }) raise(11)
        val moving = clips.any { clip ->
            present((clip["crop"] as? JsonObject)?.get("motion")) || present((clip["color"] as? JsonObject)?.get("motion")) ||
                clip.objects("images").any { present(it["motion"]) } || clip.objects("videoEffects").any { present(it["motion"]) }
        }
        if (moving || placements.any { present(it["motion"]) }) raise(12)
        // 14: a title of a label and a headline on the timeline (YouRec/Project.swift:333-335, at dadc258).
        val pictures = timelines.flatMap { it.objects("video") }.map { it["content"] as JsonObject }
        if (pictures.any { clip -> clip.objects("texts").any { present(it["labelHeadline"]) } }) raise(14)
        // 10 is a kept canvasReference, which Takes never writes.
        return schema
    }

    private fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().map { it as JsonObject }
}
