package app.takes.core.yourec

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The keys YouRec cannot do without, type by type (docs/IOS-INTEROP.md, 1.3-1.6
 * and 5.1, the "R" columns). Swift's synthesized `Decodable` requires every
 * property that is not Optional, even one with a default value, so one key
 * missing anywhere refuses the whole project on the iPhone. A type with a
 * decoder of its own lists only what that decoder requires: `CropSettings`
 * (YouRec/VideoTools.swift:64-73), `VideoEffectSettings`
 * (YouRec/VideoEffects/VideoEffectModel.swift:207-232), `EditState`
 * (YouRec/Project.swift:175-185), `Project` (:605-642).
 *
 * [problems] walks a project in the original's form and says where a key is
 * missing, or where an id is not a UUID, which Swift's `UUID` refuses.
 */
object SwiftRequiredKeys {
    /** A Swift type: the keys it requires, the keys holding a UUID, and what its other keys hold. */
    class Type(val name: String, val required: Set<String>, val uuids: Set<String> = emptySet(), val uuidLists: Set<String> = emptySet()) {
        val children = LinkedHashMap<String, Child>()
    }

    sealed interface Child {
        class One(val type: () -> Type) : Child
        class Many(val type: () -> Type) : Child
        /** A Swift `[UUID: X]`: a flat array of keys and values. */
        class FlatDictionary(val type: () -> Type) : Child
        /** An enum with values: an object with exactly one key, its case, each case's value of its type. */
        class Cases(val cases: Map<String, () -> Type?>) : Child
    }

    private fun type(name: String, vararg required: String, uuids: Set<String> = emptySet(), lists: Set<String> = emptySet(), build: Type.() -> Unit = {}) =
        Type(name, required.toSet(), uuids, lists).apply(build)

    private fun Type.one(key: String, type: () -> Type) { children[key] = Child.One(type) }
    private fun Type.many(key: String, type: () -> Type) { children[key] = Child.Many(type) }

    val editTime: Type = type("EditTime", "ticks")
    val editInterval: Type = type("EditInterval", "start", "end") { one("start") { editTime }; one("end") { editTime } }
    val playbackRun: Type = type("PlaybackRun", "duration", "sourceStart", "sourceEnd", "silent") { one("duration") { editTime } }
    val playbackMap: Type = type("SourcePlaybackMap", "runs") { many("runs") { playbackRun } }
    val sourceInterval: Type = type("SourceInterval", "start", "end")
    val strobePoint: Type = type("StrobeTimePoint", "source", "elapsed")
    val importedLUT: Type = type("ImportedLUT", "filename", "name", "input")
    val effectTiming: Type = type("EffectTiming", "curve", "rise", "release", "ramp")
    val effectPose: Type = type("EffectPose", "values") { one("lut") { importedLUT } }
    val effectAnimation: Type = type("EffectAnimation", "a", "b", "timing", "duration", "frame", "clock") {
        one("a") { effectPose }; one("b") { effectPose }; one("timing") { effectTiming }; many("clock") { strobePoint }
    }
    val titleInk: Type = type("TitleInk", "rgba")
    val titleGradient: Type = type("TitleGradient", "first", "last", "angle") { one("first") { titleInk }; one("last") { titleInk } }
    val titleDesign: Type = type(
        "TitleDesign", "face", "weight", "italic", "alignment", "tracking", "lineSpacing", "fill", "outlined", "border", "borderWidth",
        "shadow", "shade", "shadowBlur", "shadowOffset", "plate", "backing", "padding", "radius",
    ) {
        for (key in listOf("fill", "border", "shade", "backing")) one(key) { titleInk }
        one("gradient") { titleGradient }
    }
    val titleMotion: Type = type("TitleMotion", "entrance", "exit", "inSeconds", "outSeconds")
    val titleCardOffset: Type = type("TitleCard.Offset", "x", "y")
    val titleCard: Type = type("TitleCard", "fill", "border", "borderWidth", "padding", "radius", "spacing") {
        one("fill") { titleInk }; one("border") { titleInk }; one("labelOffset") { titleCardOffset }; one("headlineOffset") { titleCardOffset }
    }
    val labelHeadline: Type = type("LabelHeadline", "label", "labelStyle", "headlineStyle", "labelScale", "headlineScale", "card") {
        one("labelStyle") { titleDesign }; one("headlineStyle") { titleDesign }; one("card") { titleCard }
    }
    val textOverlay: Type = type("TextOverlay", "id", "text", "start", "end", "x", "y", "size", "style", "color", uuids = setOf("id")) {
        one("appearance") { titleDesign }; one("labelHeadline") { labelHeadline }; one("motion") { titleMotion }
    }
    val transitionFraming: Type = type("TransitionFraming", "x", "y", "zoom")
    val transitionColor: Type = type("TransitionColor", "red", "green", "blue")
    val clipTransition: Type = type(
        "ClipTransition", "id", "style", "duration", "axis", "divider", "reverse", "audio", "first", "second", uuids = setOf("id", "nextClipID"),
    ) { one("first") { transitionFraming }; one("second") { transitionFraming }; one("fadeColor") { transitionColor } }
    val effectRGB: Type = type("EffectRGB", "red", "green", "blue")
    val effectPalette: Type = type("EffectPalette", "colors", "thresholds") { many("colors") { effectRGB } }
    private val slider = type("slider", "min", "max", "defaultValue", "step")
    private val valueCases = Child.Cases(mapOf("number" to { type("number", "_0") }, "toggle" to { type("toggle", "_0") },
        "choice" to { type("choice", "_0") }, "color" to { type("color", "_0") { one("_0") { effectRGB } } }, "media" to { null }))
    val effectKeyframe: Type = type("EffectKeyframe", "id", "time", "value", uuids = setOf("id")) { children["value"] = valueCases }
    val effectParameter: Type = type("EffectParameter", "id", "title", "kind", "value", "keyframes", "animatable") {
        children["value"] = valueCases
        children["kind"] = Child.Cases(mapOf("slider" to { slider }, "toggle" to { type("toggle", "defaultValue") },
            "stepper" to { type("stepper", "values", "defaultValue") }, "color" to { type("color", "defaultValue") { one("defaultValue") { effectRGB } } },
            "picker" to { type("picker", "options", "defaultValue") }, "media" to { type("media", "image", "video") }))
        many("keyframes") { effectKeyframe }
    }
    val videoEffect: Type = type("VideoEffectSettings", "id", "kind", "enabled", "version", "start", "end", "parameters", uuids = setOf("id", "capturedEffectID")) {
        many("parameters") { effectParameter }; many("palette") { effectRGB }; one("lockedPalette") { effectPalette }
        one("motion") { effectAnimation }; many("strobeTimeMap") { strobePoint }
    }
    val frozenFrame: Type = type("FrozenFrameSegment", "sourceTime", "duration", "offset")
    val timelineClip: Type = type("TimelineClip", "id", "sourceID", "start", "end", uuids = setOf("id", "sourceID")) {
        one("stabilization") { type("StabilizationSettings", "level", "version", uuids = setOf("id")) }
        one("speed") { type("SpeedSettings", "id", "rate", uuids = setOf("id")) }
        one("color") { type("ColorSettings", "id", "look", "amount", uuids = setOf("id")) { one("imported") { importedLUT }; one("motion") { effectAnimation } } }
        many("texts") { textOverlay }
        one("pauses") {
            type("PauseSettings", "id", "thresholdDB", "minimum", "before", "after", "cuts", uuids = setOf("id")) {
                many("cuts") { sourceInterval }; many("candidates") { type("DetectedPause", "raw", "enabled") { one("raw") { sourceInterval } } }
                one("analyzed") { sourceInterval }
            }
        }
        one("crop") { type("CropSettings", "id", "x", "y", "zoom", uuids = setOf("id")) { one("motion") { effectAnimation } } }
        one("mirror") { type("MirrorSettings", "id", "horizontal", "vertical", uuids = setOf("id")) }
        many("images") { type("ImageOverlay", "id", "filename", "start", "end", "x", "y", "width", "opacity", uuids = setOf("id")) { one("motion") { effectAnimation } } }
        one("openingTransition") { clipTransition }; one("transition") { clipTransition }
        many("videoEffects") { videoEffect }
        one("frozenFrame") { frozenFrame }
    }
    val audioModel: Type = type("AudioModel", "id", "weightsSHA256", "runtime")
    val audioEffect: Type = type("AudioEffect", "id", "kind", "enabled", "amount", "frequency", "implementationVersion", uuids = setOf("id")) { one("model") { audioModel } }
    val subtitleWord: Type = type("SubtitleWord", "id", "phraseID", "text", "start", "end", uuids = setOf("id", "phraseID"))
    val audioItem: Type = type(
        "AudioTimelineItem", "id", "occurrenceID", "sourceID", "start", "mapping", "localOrigin", "volumeDB", "muted", "enhancements",
        uuids = setOf("id", "occurrenceID", "sourceID", "followsVideo"),
    ) {
        one("start") { editTime }; one("localOrigin") { editTime }; one("mapping") { playbackMap }
        one("fadeIn") { type("AudioEdgeFade", "seconds", "elapsed") }; one("fadeOut") { type("AudioEdgeFade", "seconds", "elapsed") }
        many("enhancements") { type("AudioEnhancementRegion", "id", "interval", "effects", uuids = setOf("id")) { one("interval") { editInterval }; many("effects") { audioEffect } } }
        many("censorRegions") {
            type("AudioCensorRegion", "id", "interval", "phaseOrigin", "settings", uuids = setOf("id")) {
                one("interval") { editInterval }; one("phaseOrigin") { editTime }; one("envelopeInterval") { editInterval }
                one("settings") { type("AudioCensorSettings", "frequency", "levelDB", "waveform", "envelope") }
            }
        }
        many("subtitleWords") { subtitleWord }
    }
    val videoItem: Type = type("VideoTimelineItem", "occurrenceID", "start", "content", "layer", "localOrigin", "mapping", uuids = setOf("occurrenceID")) {
        one("start") { editTime }; one("localOrigin") { editTime }; one("mapping") { playbackMap }; one("content") { timelineClip }
    }
    val videoPlacement: Type = type("VideoPlacement", "x", "y", "width", "height", "framing", "fill", "order") {
        one("framing") { transitionFraming }; one("motion") { effectAnimation }; one("edgeCrop") { type("PlacementEdgeCrop", "left", "right", "top", "bottom") }
    }
    val subtitleSettings: Type = type("SubtitleSettings", "id", "maxWords", "style", "color", "size", "y", "language", "timingFingerprint", uuids = setOf("id")) {
        one("appearance") { titleDesign }; one("motion") { titleMotion }
    }
    val trackTimeline: Type = type("TrackTimeline", "video", "audio", "links", "bookmarks", "placements", "transitions", "generated") {
        many("video") { videoItem }; many("audio") { audioItem }
        many("links") { type("TimelineLink", "id", "members", uuids = setOf("id"), lists = setOf("members")) }
        many("bookmarks") {
            type("TimelineBookmark", "id", "label", "anchor", uuids = setOf("id")) {
                children["anchor"] = Child.Cases(mapOf(
                    "source" to { type("source", "occurrence", "source", "time", uuids = setOf("occurrence", "source")) },
                    "local" to { type("local", "occurrence", "offset", uuids = setOf("occurrence")) { one("offset") { editTime } } },
                    "project" to { type("project", "_0") { one("_0") { editTime } } },
                ))
            }
        }
        many("placements") {
            type("VideoPlacementRegion", "id", "participants", "videos", uuids = setOf("id"), lists = setOf("participants")) {
                children["videos"] = Child.FlatDictionary { videoPlacement }
            }
        }
        many("transitions") {
            type("TimelineTransition", "settings", "origin", "factor", uuids = setOf("outgoing", "incoming")) { one("settings") { clipTransition }; one("origin") { editTime } }
        }
        many("generated") { type("TimelineGeneratedRegion", "id", "owner", "interval", uuids = setOf("id", "owner", "freezeVideo")) { one("interval") { editInterval } } }
        one("subtitles") { subtitleSettings }
    }
    val editSnapshot: Type = type("EditSnapshot", "state", "captureCursor") {
        // The original reads a state without a timeline through its oldest path, which drops all but the clips.
        one("state") { type("EditState", "timeline") { one("timeline") { trackTimeline } } }
    }
    val sourceMedia: Type = type("SourceMedia", "id", "filename", "duration", "recordedAt", uuids = setOf("id")) {
        many("events") { type("CaptureEvent", "id", "time", "kind", "detail", uuids = setOf("id")) }
        many("videoEffects") { videoEffect }
    }
    val project: Type = type("Project", "schemaVersion", "id", "title", "createdAt", "updatedAt", "sources", "historyCursor", uuids = setOf("id")) {
        one("exportSettings") { type("ExportSettings", "codec", "resolution", "frameRate") }
        many("sources") { sourceMedia }
        many("captureLedger") { type("CaptureLedgerEntry", "clip", "insertion") { one("clip") { timelineClip } } }
    }

    /** Where the manifest [manifest] and the steps [steps] miss a key the original requires, or hold an id that is not a UUID. */
    fun problems(manifest: JsonElement, steps: Collection<JsonElement>): List<String> {
        val found = ArrayList<String>()
        walk(manifest, project, "project.json", found)
        steps.forEachIndexed { index, step -> walk(step, editSnapshot, "step $index", found) }
        return found
    }

    private fun walk(element: JsonElement, type: Type, path: String, found: MutableList<String>) {
        val fields = element as? JsonObject ?: run { found += "$path: ${type.name} is not an object"; return }
        for (key in type.required) if (fields[key] == null || fields[key] is JsonNull) found += "$path: ${type.name} has no $key"
        fun uuid(value: JsonElement?, where: String) {
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (value != null && value !is JsonNull && (text == null || !YouRecDetect.isUUID(text))) found += "$where is not a UUID"
        }
        for (key in type.uuids) uuid(fields[key], "$path.$key")
        for (key in type.uuidLists) (fields[key] as? JsonArray)?.forEach { uuid(it, "$path.$key[]") }
        for ((key, child) in type.children) {
            val value = fields[key] ?: continue
            if (value is JsonNull) continue
            when (child) {
                is Child.One -> walk(value, child.type(), "$path.$key", found)
                is Child.Many -> (value as? JsonArray)?.forEachIndexed { index, item -> walk(item, child.type(), "$path.$key[$index]", found) }
                    ?: run { found += "$path.$key is not an array" }
                is Child.FlatDictionary -> {
                    val flat = value as? JsonArray
                    if (flat == null || flat.size % 2 != 0) { found += "$path.$key is not a flat dictionary"; continue }
                    for (index in flat.indices step 2) {
                        uuid(flat[index], "$path.$key[$index]")
                        walk(flat[index + 1], child.type(), "$path.$key[${index + 1}]", found)
                    }
                }
                is Child.Cases -> {
                    val cases = value as? JsonObject
                    if (cases == null || cases.size != 1 || cases.keys.single() !in child.cases) { found += "$path.$key is not one case"; continue }
                    val (case, payload) = cases.entries.single()
                    child.cases.getValue(case)()?.let { walk(payload, it, "$path.$key.$case", found) }
                }
            }
        }
    }
}
