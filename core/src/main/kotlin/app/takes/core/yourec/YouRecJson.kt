package app.takes.core.yourec

import app.takes.core.TextColor
import app.takes.core.TextStyle
import app.takes.core.TitleDesign
import app.takes.core.documentJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.roundToLong

/**
 * The differences between what YouRec's `JSONEncoder` writes and what Takes'
 * serialisers read, as transforms of the JSON tree, one per Swift type and
 * named after it (docs/IOS-INTEROP.md, 1 and 7.2). Each type that differs has
 * the way into Takes (`…ToTakes`); the way back to the iPhone is the same
 * table read from the other side and is written beside it when Takes writes
 * ZIPs for the iPhone.
 *
 * Everything not named here is the same JSON on both sides and passes as it is.
 */
internal object YouRecJson {
    /** Seconds between 1970 and 2001, where Foundation's `Date` counts from. */
    private const val REFERENCE_DATE = 978_307_200.0

    /** T1: a Swift `Date`, seconds since 2001 as a Double, as Takes' milliseconds since 1970. */
    fun dateToTakes(element: JsonElement?): Long {
        val seconds = (element as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: throw YouRecFormatException("A date is not a number")
        if (!seconds.isFinite()) throw YouRecFormatException("A date is not finite")
        return ((seconds + REFERENCE_DATE) * 1000).roundToLong()
    }

    /** T1 the other way. */
    fun dateToYouRec(milliseconds: Long): Double = milliseconds / 1000.0 - REFERENCE_DATE

    /**
     * T4: a Swift `[UUID: X]`, which `JSONEncoder` writes as one flat array of
     * keys and values, as the object Takes keys by id. An odd length, a key
     * that is not a string or a key given twice is not one.
     */
    fun flatDictionaryToObject(element: JsonElement?): JsonObject {
        val flat = element as? JsonArray ?: throw YouRecFormatException("A dictionary keyed by UUID is not an array")
        if (flat.size % 2 != 0) throw YouRecFormatException("A dictionary keyed by UUID has an odd length")
        val result = LinkedHashMap<String, JsonElement>()
        for (index in flat.indices step 2) {
            val key = (flat[index] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw YouRecFormatException("A dictionary key is not a string")
            if (result.put(key, flat[index + 1]) != null) throw YouRecFormatException("A dictionary key is repeated")
        }
        return JsonObject(result)
    }

    /** T4 the other way. */
    fun objectToFlatDictionary(map: JsonObject): JsonArray =
        JsonArray(map.toSortedMap().flatMap { (key, value) -> listOf(JsonPrimitive(key), value) })

    /** The longest title Takes keeps, in UTF-16 units (Project.kt TextOverlay.valid); the original counts characters. */
    const val TITLE_LENGTH = 500

    /**
     * T7: a `TextOverlay`. The original draws a title saved without a design in
     * the look of its style and color (YouRec/VideoTools.swift:231), where
     * Takes would draw it outlined: such a title gets that look as its design.
     * `style` and `color` then say nothing more and are left out. A title
     * longer than Takes allows is shortened, and [shortened] is called.
     */
    fun textOverlayToTakes(text: JsonObject, shortened: () -> Unit): JsonObject {
        val result = LinkedHashMap(text)
        if (text["appearance"] == null || text["appearance"] is kotlinx.serialization.json.JsonNull) {
            val style = enumValue(text["style"], TextStyle.entries, TextStyle.Bold) { it.serialName() }
            val color = enumValue(text["color"], TextColor.entries, TextColor.White) { it.serialName() }
            result["appearance"] = documentJson.encodeToJsonElement(TitleDesign.serializer(), TitleDesign.legacy(style, color))
        }
        result.remove("style")
        result.remove("color")
        shorten(text["text"])?.let { result["text"] = it; shortened() }
        // A card's label is held to the same length (YouRec/TitleDesign.swift:200).
        (text["labelHeadline"] as? JsonObject)?.let { group ->
            shorten(group["label"])?.let { result["labelHeadline"] = JsonObject(LinkedHashMap(group).apply { put("label", it) }); shortened() }
        }
        return JsonObject(result)
    }

    /** [element], a string longer than Takes allows a title, cut to [TITLE_LENGTH]; null when it is not one. */
    private fun shorten(element: kotlinx.serialization.json.JsonElement?): JsonPrimitive? {
        val words = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (words.length <= TITLE_LENGTH) return null
        // Never half of a character made of two UTF-16 units.
        val end = if (Character.isHighSurrogate(words[TITLE_LENGTH - 1])) TITLE_LENGTH - 1 else TITLE_LENGTH
        return JsonPrimitive(words.substring(0, end))
    }

    /** A `TimelineClip`: its titles, and its effect windows, a dictionary keyed by UUID (T4; YouRec/Project.swift:61-63). */
    fun timelineClipToTakes(clip: JsonObject, shortened: () -> Unit): JsonObject {
        val texts = clip["texts"] as? JsonArray
        val windows = clip["effectWindows"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }
        if (texts == null && windows == null) return clip
        return JsonObject(LinkedHashMap(clip).apply {
            if (texts != null) put("texts", JsonArray(texts.map { textOverlayToTakes(it as? JsonObject ?: throw YouRecFormatException("A title is not an object"), shortened) }))
            if (windows != null) put("effectWindows", flatDictionaryToObject(windows))
        })
    }

    /**
     * A `TrackTimeline`: each picture's recipe ([timelineClipToTakes]), each
     * placement's windows ([flatDictionaryToObject]) and the captions' timing
     * fingerprint. That fingerprint is a hash of Swift's own encoding of the
     * timing (YouRec/Subtitles.swift:183-189), which Takes does not reproduce:
     * it is left empty, so regrouping the words waits for the next
     * transcription, as after any change of the timing (T9).
     */
    fun trackTimelineToTakes(timeline: JsonObject, shortened: () -> Unit): JsonObject {
        val result = LinkedHashMap(timeline)
        (timeline["video"] as? JsonArray)?.let { video ->
            result["video"] = JsonArray(video.map { item ->
                val fields = item as? JsonObject ?: throw YouRecFormatException("A picture is not an object")
                val content = fields["content"] as? JsonObject ?: throw YouRecFormatException("A picture has no recipe")
                JsonObject(LinkedHashMap(fields).apply { put("content", timelineClipToTakes(content, shortened)) })
            })
        }
        (timeline["placements"] as? JsonArray)?.let { placements ->
            result["placements"] = JsonArray(placements.map { region ->
                val fields = region as? JsonObject ?: throw YouRecFormatException("A placement is not an object")
                JsonObject(LinkedHashMap(fields).apply { put("videos", flatDictionaryToObject(fields["videos"])) })
            })
        }
        (timeline["subtitles"] as? JsonObject)?.let { subtitles ->
            result["subtitles"] = JsonObject(LinkedHashMap(subtitles).apply { put("timingFingerprint", JsonPrimitive("")) })
        }
        return JsonObject(result)
    }

    /**
     * T7 the other way: the original requires `style` and `color`
     * (YouRec/VideoTools.swift:226-227) and draws a title without a design in
     * their look, bold white with a shadow; Takes draws one without a design
     * as [TitleDesign.plain], which is written in full.
     */
    fun textOverlayToYouRec(text: JsonObject): JsonObject = JsonObject(LinkedHashMap(text).apply {
        put("style", JsonPrimitive("bold"))
        put("color", JsonPrimitive("white"))
        if (text["appearance"] == null || text["appearance"] is kotlinx.serialization.json.JsonNull) {
            put("appearance", documentJson.encodeToJsonElement(TitleDesign.serializer(), TitleDesign.plain))
        }
    })

    /**
     * T5: a `ClipTransition`. Takes leaves out six keys at their defaults
     * (TransitionSettings.kt:101-107); Swift's synthesized decoding requires
     * them (YouRec/Transitions.swift:39-64).
     */
    fun clipTransitionToYouRec(settings: JsonObject): JsonObject = JsonObject(LinkedHashMap(settings).apply {
        val center = JsonObject(mapOf("x" to JsonPrimitive(0.5), "y" to JsonPrimitive(0.5), "zoom" to JsonPrimitive(1.0)))
        putIfAbsent("axis", JsonPrimitive("horizontal"))
        putIfAbsent("divider", JsonPrimitive(0.5))
        putIfAbsent("reverse", JsonPrimitive(false))
        putIfAbsent("audio", JsonPrimitive("crossfade"))
        putIfAbsent("first", center)
        putIfAbsent("second", center)
    })

    /** The keys of a Takes clip that only its sequence form has (TrackConversion.kt:78-81); the original has none of them. */
    private val SEQUENCE_ONLY = listOf("audio", "bookmarks", "subtitleWords", "transitionFit", "openingTransitionFit")

    /** A `TimelineClip` as the original reads it: without the sequence form's keys, its titles and transitions completed, its windows a flat dictionary. */
    fun timelineClipToYouRec(clip: JsonObject): JsonObject = JsonObject(LinkedHashMap(clip).apply {
        SEQUENCE_ONLY.forEach { remove(it) }
        (clip["effectWindows"] as? JsonObject)?.let { put("effectWindows", objectToFlatDictionary(it)) }
        (clip["texts"] as? JsonArray)?.let { texts -> put("texts", JsonArray(texts.map { textOverlayToYouRec(it as JsonObject) })) }
        for (key in listOf("transition", "openingTransition")) (clip[key] as? JsonObject)?.let { put(key, clipTransitionToYouRec(it)) }
    })

    /** A `TrackTimeline` as the original reads it ([trackTimelineToTakes] the other way); the captions' fingerprint is left empty (T9). */
    fun trackTimelineToYouRec(timeline: JsonObject): JsonObject {
        val result = LinkedHashMap(timeline)
        (timeline["video"] as? JsonArray)?.let { video ->
            result["video"] = JsonArray(video.map { item ->
                val fields = item as JsonObject
                JsonObject(LinkedHashMap(fields).apply { put("content", timelineClipToYouRec(fields["content"] as JsonObject)) })
            })
        }
        (timeline["placements"] as? JsonArray)?.let { placements ->
            result["placements"] = JsonArray(placements.map { region ->
                val fields = region as JsonObject
                JsonObject(LinkedHashMap(fields).apply { put("videos", objectToFlatDictionary(fields["videos"] as JsonObject)) })
            })
        }
        (timeline["transitions"] as? JsonArray)?.let { transitions ->
            result["transitions"] = JsonArray(transitions.map { relation ->
                val fields = relation as JsonObject
                JsonObject(LinkedHashMap(fields).apply { put("settings", clipTransitionToYouRec(fields["settings"] as JsonObject)) })
            })
        }
        (timeline["subtitles"] as? JsonObject)?.let { subtitles ->
            result["subtitles"] = JsonObject(LinkedHashMap(subtitles).apply { put("timingFingerprint", JsonPrimitive("")) })
        }
        return JsonObject(result)
    }

    /**
     * The bytes Swift's `JSONEncoder` with `.sortedKeys` gives a tree
     * (YouRec/Project.swift:211, 554-557): compact, every object's keys in
     * order. Numbers are written as Takes writes them, which Swift reads.
     */
    fun sortedBytes(element: JsonElement): ByteArray = sorted(element).toString().toByteArray()

    fun sorted(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.toSortedMap().mapValues { sorted(it.value) })
        is JsonArray -> JsonArray(element.map(::sorted))
        else -> element
    }

    /** The source of the picture the original gives the movie's frame by default: the earliest, the first listed of those (YouRec/Project.swift:147). */
    fun firstVideoSourceID(timeline: JsonObject): String? {
        var first: Pair<Long, String>? = null
        for (item in timeline["video"] as? JsonArray ?: return null) {
            val fields = item as? JsonObject ?: continue
            val ticks = ((fields["start"] as? JsonObject)?.get("ticks") as? JsonPrimitive)?.longOrNull ?: continue
            val source = ((fields["content"] as? JsonObject)?.get("sourceID") as? JsonPrimitive)?.content ?: continue
            if (first == null || ticks < first.first) first = ticks to source
        }
        return first?.second
    }

    /** The sources of the pictures on [timeline]. */
    fun videoSourceIDs(timeline: JsonObject): Set<String> =
        (timeline["video"] as? JsonArray).orEmpty().mapNotNullTo(HashSet()) { item ->
            (((item as? JsonObject)?.get("content") as? JsonObject)?.get("sourceID") as? JsonPrimitive)?.content
        }

    private fun <T : Enum<T>> enumValue(element: JsonElement?, entries: List<T>, fallback: T, name: (T) -> String): T {
        val raw = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return fallback
        return entries.firstOrNull { name(it) == raw } ?: throw YouRecFormatException("Unknown value $raw")
    }

    private fun Enum<*>.serialName(): String = name.replaceFirstChar { it.lowercase() }
}

/** The YouRec JSON is not what the original writes. */
class YouRecFormatException(message: String) : Exception(message)
