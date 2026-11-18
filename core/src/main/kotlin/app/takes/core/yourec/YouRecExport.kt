package app.takes.core.yourec

import app.takes.core.CaptureEffectsFile
import app.takes.core.ExportSettings
import app.takes.core.OpenTimeline
import app.takes.core.Project
import app.takes.core.SupportedVideoEffects
import app.takes.core.TimelineClip
import app.takes.core.TrackInvariants
import app.takes.core.TrackTimeline
import app.takes.core.VideoEffectID
import app.takes.core.documentJson
import app.takes.core.sha256
import app.takes.core.toTimeline
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/**
 * A Takes project as YouRec, the iPhone original, restores it from a ZIP
 * (docs/IOS-INTEROP.md, 1.4, 2.3, 3.6, 5.3 and 7.3). [plan] works out the
 * documents and the files, and what stops the export or is lost on the way;
 * [YouRecArchive] writes them.
 *
 * Every step of the history is written as the timeline it shows, with every
 * take already on it, so the original replays no take from the ledger and the
 * recording gain and marks Takes keeps on a take's clip are not lost (1.4).
 */
object YouRecExport {
    /** What the app reads of a media file: the MIME types of its tracks, or null when it cannot tell. */
    class Probe(val mimes: (File) -> List<String>? = { null })

    /** Something that stops the export ([Kind.blocker]) or that the person accepts by going on. */
    data class Issue(val kind: Kind, val files: List<String> = emptyList()) {
        enum class Kind(val blocker: Boolean) {
            /** A container or codec the iPhone cannot play: Ogg, WebM, Matroska, 3GP, VP8, VP9, AV1, Opus, Vorbis, AMR. */
            UnsupportedMedia(true),
            /** A recording whose file is not in the project's folder. */
            MissingRecording(true),
            /** A moving grade the original would refuse (YouRec/Project.swift:490). */
            ColorMotion(true),
            /** A held frame without its Freeze Frame, or outside its clip (YouRec/Project.swift:483-486). */
            FrozenFrame(true),
            /** A step that breaks the original's timeline rules (YouRec/TrackTimeline.swift:1175-1248). */
            InvalidTimeline(true),
            /** A file name the original refuses (YouRec/Project.swift:446-461), or two that differ only in case. */
            FileNames(true),
            /** More history than the original restores (YouRec/ProjectArchive.swift:53-63). */
            TooLarge(true),
            /** The effects' analysis made by Takes, which the iPhone cannot read and works out again. */
            AnalysisRecomputed(false),
            /** Takes being recorded at a chosen place: the next take on the iPhone goes at the end. */
            RecordingRunDropped(false),
            /** Captions whose words the iPhone will not regroup until they are transcribed again. */
            CaptionRegrouping(false),
        }
    }

    /** A file of the ZIP: [name] in the archive, from [file]; [png] when it is re-encoded as PNG (5.3). */
    class Media(val name: String, val file: File, val png: Boolean, val recording: Boolean)

    class Plan(
        val issues: List<Issue>,
        /** The steps by name, each once, in the order the history first uses them. */
        val steps: Map<String, ByteArray>,
        /** The trees of the steps, one per step of the history, for checks. */
        val stepTrees: List<JsonObject>,
        val media: List<Media>,
        val schema: Int,
        private val build: (Map<String, String>) -> JsonObject,
        /** The OpenTimelineIO file of the current edit and its name, as the original's ZIP holds them (YouRec/ProjectArchive.swift:259). */
        val timelineName: String,
        val timeline: ByteArray,
    ) {
        val blockers: List<Issue> get() = issues.filter { it.kind.blocker }
        val losses: List<Issue> get() = issues.filter { !it.kind.blocker }

        /** The manifest, its recordings vouched for by [hashes], by file name. */
        fun manifestTree(hashes: Map<String, String>): JsonObject = build(hashes)

        fun manifest(hashes: Map<String, String>): ByteArray = YouRecJson.sortedBytes(manifestTree(hashes))
    }

    private const val HEADER_LIMIT = 20_000_000
    private const val STEP_LIMIT = 32_000_000
    private const val HISTORY_LIMIT = 256_000_000L

    private val BLOCKED_EXTENSIONS = setOf("ogg", "oga", "opus", "webm", "mkv", "mka", "3gp", "3gpp", "3g2", "amr", "awb")
    private val BLOCKED_MIMES = setOf(
        "video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9", "video/av01", "audio/vorbis", "audio/opus", "audio/3gpp", "audio/amr-wb",
        "video/x-matroska", "audio/webm", "video/webm",
    )
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "heic")
    private val EFFECT_EXTENSIONS = setOf("json", "png", "jpg", "jpeg", "heic", "mov", "mp4", "bin", "cube")
    private val RESERVED = setOf("project.json", "integrity.json", "cache", "processing", "previews", "exports")

    /** Keys whose string values are ids, which the original reads as UUIDs (T6). */
    private val ID_KEYS = setOf(
        "id", "occurrenceID", "sourceID", "followsVideo", "outgoing", "incoming", "owner", "freezeVideo", "nextClipID",
        "capturedEffectID", "phraseID", "occurrence", "source",
    )

    fun plan(project: Project, folder: File, probe: Probe = Probe()): Plan {
        project.validate()
        val issues = LinkedHashMap<Issue.Kind, MutableList<String>>()
        fun issue(kind: Issue.Kind, file: String? = null) {
            val files = issues.getOrPut(kind) { mutableListOf() }
            if (file != null && file !in files) files += file
        }

        // Every step as the timeline it shows, every take on it (1.4).
        val history = project.history
        val timelines = history.map { it.toTimeline() }
        for (timeline in timelines) {
            if (TrackInvariants.violation(timeline, project.sources) != null) issue(Issue.Kind.InvalidTimeline)
            for (item in timeline.video) checkClip(item.content, ::issue)
        }
        for (clip in project.captureLedger) checkClip(clip, ::issue)
        if (timelines.any { it.subtitles != null }) issue(Issue.Kind.CaptionRegrouping)
        if (project.recordingInsertion != null) issue(Issue.Kind.RecordingRunDropped)

        var steps: List<JsonObject> = timelines.mapIndexed { index, timeline ->
            val tree = YouRecJson.trackTimelineToYouRec(documentJson.encodeToJsonElement(TrackTimeline.serializer(), timeline) as JsonObject)
            buildJsonObject {
                put("captureCursor", project.captureLedger.size)
                put("state", buildJsonObject {
                    put("timeline", tree)
                    history[index].outputSize?.let { size -> put("outputSize", JsonArray(listOf(JsonPrimitive(size.width), JsonPrimitive(size.height)))) }
                })
            }
        }
        var ledger: List<JsonObject> = project.captureLedger.map { clip ->
            YouRecJson.timelineClipToYouRec(documentJson.encodeToJsonElement(TimelineClip.serializer(), clip) as JsonObject)
        }
        // The effects each take was recorded with, kept beside its movie (CaptureEffectsFile).
        var recipes: Map<String, JsonArray> = project.sources.mapNotNull { source ->
            val file = CaptureEffectsFile.beside(File(folder, source.filename))
            val effects = runCatching { documentJson.decodeFromString(SupportedVideoEffects, file.readText()) }.getOrNull()?.ifEmpty { null }
            effects?.let { source.id to (documentJson.encodeToJsonElement(SupportedVideoEffects, it) as JsonArray) }
        }.toMap()

        // T8: the analysis Takes made is not the original's format (5.2): its references go, and its files stay behind.
        val analyses = (steps + ledger).flatMap(::effects) + recipes.values.flatMap { list -> list.map { it as JsonObject } }
        val stripped = analyses.mapNotNull { (it["analysisFile"] as? JsonPrimitive)?.content }.toSet().filterNot { madeByYouRec(File(folder, it)) }.toSet()
        val bare = analyses.any { it["analysisFile"] == null && it["analysisDataFile"] != null }
        if (stripped.isNotEmpty() || bare) issue(Issue.Kind.AnalysisRecomputed)
        fun strip(effect: JsonObject): JsonObject {
            val analysis = (effect["analysisFile"] as? JsonPrimitive)?.content
            if (analysis !in stripped && !(analysis == null && effect["analysisDataFile"] != null)) return effect
            return JsonObject(effect - "analysisFile" - "analysisDataFile" - "capturedEffectID")
        }

        // 5.3: effect pictures in WebP become PNG, movies named .m4v take the name of their container.
        val effectMedia = analyses.flatMap { effect -> YouRecMediaFiles.effectFiles(strip(effect)) }.toSet() - stripped
        val renames = LinkedHashMap<String, String>()
        val pngs = HashSet<String>()
        val taken = (project.mediaFiles).map { it.lowercase() }.toMutableSet()
        fun unique(stem: String, extension: String): String {
            var name = "$stem.$extension"
            var index = 1
            while (name.lowercase() in taken) { index += 1; name = "$stem-$index.$extension" }
            taken += name.lowercase()
            return name
        }
        for (name in effectMedia.sorted()) {
            val extension = name.substringAfterLast('.', "").lowercase()
            val stem = name.substringBeforeLast('.')
            when (extension) {
                "webp" -> unique(stem, "png").also { renames[name] = it; pngs += it }
                "m4v" -> when (container(File(folder, name))) {
                    "qt" -> renames[name] = unique(stem, "mov")
                    "mp4" -> renames[name] = unique(stem, "mp4")
                    // Missing: left out as any missing file; not a movie: refused.
                    null -> if (File(folder, name).exists()) issue(Issue.Kind.UnsupportedMedia, name)
                }
            }
        }
        fun effect(effect: JsonObject): JsonObject = rename(strip(effect), renames) as JsonObject
        steps = steps.map { mapEffects(it, ::effect) as JsonObject }
        ledger = ledger.map { mapEffects(it, ::effect) as JsonObject }
        recipes = recipes.mapValues { (_, list) -> JsonArray(list.map { effect(it as JsonObject) }) }

        // T6: every id a UUID, the same one wherever it is named.
        val ids = HashMap<String, String>()
        val remap: (String) -> String = { id ->
            if (YouRecDetect.isUUID(id)) id else ids.getOrPut(id) { UUID.nameUUIDFromBytes(id.toByteArray()).toString().uppercase() }
        }
        steps = steps.map { remapIDs(it, remap) as JsonObject }
        ledger = ledger.map { remapIDs(it, remap) as JsonObject }
        recipes = recipes.mapValues { (_, list) -> remapIDs(list, remap) as JsonArray }

        val sources = project.sources.map { source ->
            buildJsonObject {
                put("id", remap(source.id))
                put("filename", source.filename)
                put("duration", source.duration)
                put("recordedAt", YouRecJson.dateToYouRec(source.recordedAt))
                source.events?.let { events -> put("events", remapIDs(documentJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(app.takes.core.CaptureEvent.serializer()), events), remap)) }
                recipes[source.id]?.let { put("videoEffects", it) }
                source.audioOnly?.let { put("audioOnly", it) }
                // T11: a photo made into a movie here is the original's still photo.
                (source.isStillPhoto ?: true.takeIf { source.filename.startsWith("photo-") })?.let { put("isStillPhoto", it) }
                source.originalName?.let { put("originalName", it) }
            }
        }
        val ledgerEntries = ledger.mapIndexed { index, clip ->
            buildJsonObject { put("clip", clip); put("insertion", index in project.captureInsertions) }
        }

        // The steps' bytes, as the original names them; a step the history uses twice is written once.
        val bytes = steps.map(YouRecJson::sortedBytes)
        val names = bytes.map { sha256(it) + ".json" }
        val distinct = LinkedHashMap<String, ByteArray>()
        names.forEachIndexed { index, name -> distinct.putIfAbsent(name, bytes[index]) }
        // The original charges every reference to a step (YouRec/ProjectArchive.swift:53-63, 315-321).
        if (bytes.any { it.size > STEP_LIMIT } || bytes.sumOf { it.size.toLong() } > HISTORY_LIMIT) issue(Issue.Kind.TooLarge)

        val schema = YouRecSchema.of(sources, ledger, steps.map { (it["state"] as JsonObject)["timeline"] as JsonObject })
        check(schema in 6..YouRecCodec.LATEST_READABLE_SCHEMA)
        val base = buildJsonObject {
            put("automaticCanvasVersion", 1)
            put("captureLedger", JsonArray(ledgerEntries))
            put("createdAt", YouRecJson.dateToYouRec(project.createdAt))
            put("exportSettings", documentJson.encodeToJsonElement(ExportSettings.serializer(), project.exportSettings))
            put("historyCursor", project.historyCursor)
            put("id", remap(project.id))
            put("recordingHistoryVersion", 1)
            put("schemaVersion", schema)
            put("snapshotFiles", JsonArray(names.map { JsonPrimitive(it) }))
            put("sources", JsonArray(sources))
            put("title", project.title)
            put("updatedAt", YouRecJson.dateToYouRec(project.updatedAt))
        }
        val manifest = { hashes: Map<String, String> ->
            JsonObject(base + ("sources" to JsonArray(sources.map { source ->
                val hash = hashes[(source["filename"] as JsonPrimitive).content]
                if (hash == null) source else JsonObject(source + ("sha256" to JsonPrimitive(hash)))
            })))
        }
        if (YouRecJson.sortedBytes(manifest(project.sources.associate { it.filename to "0".repeat(64) })).size > HEADER_LIMIT) issue(Issue.Kind.TooLarge)

        // The files the original will look for, named as it requires (YouRec/Project.swift:446-461).
        val mediaFiles = YouRecMediaFiles.of(base, steps)
        val recordings = project.sources.map { it.filename }.toSet()
        if (mediaFiles.map { it.lowercase() }.toSet().size != mediaFiles.size) issue(Issue.Kind.FileNames)
        val disk = renames.entries.associate { (from, to) -> to to from }
        val media = ArrayList<Media>()
        for (name in mediaFiles) {
            val extension = name.substringAfterLast('.', "").lowercase()
            val recording = name in recordings
            when {
                recording -> if (name.lowercase() in RESERVED) issue(Issue.Kind.FileNames, name)
                name.startsWith("image-") -> if (extension !in IMAGE_EXTENSIONS) issue(Issue.Kind.FileNames, name)
                name.startsWith("effect-") -> if (extension !in EFFECT_EXTENSIONS) issue(Issue.Kind.FileNames, name)
                name.startsWith("font-") -> {}
                else -> issue(Issue.Kind.FileNames, name)
            }
            val file = File(folder, disk[name] ?: name)
            if (!file.isFile) {
                // As the original, a picture or an effect's file already missing stays behind; a recording may not.
                if (recording) issue(Issue.Kind.MissingRecording, name)
                continue
            }
            if (recording || extension in setOf("mov", "mp4", "m4v")) {
                if (extension in BLOCKED_EXTENSIONS || probe.mimes(file).orEmpty().any { it.lowercase() in BLOCKED_MIMES }) issue(Issue.Kind.UnsupportedMedia, name)
            }
            media += Media(name, file, name in pngs, recording)
        }
        val problems = SwiftRequiredKeys.problems(manifest(emptyMap()), steps)
        check(problems.isEmpty()) { problems.take(5).joinToString("; ") }
        return Plan(
            issues.map { (kind, files) -> Issue(kind, files) }, distinct, steps, media, schema, manifest,
            YouRecMediaFiles.timelineName(mediaFiles), OpenTimeline.text(project).toByteArray(),
        )
    }

    /** What the original checks of a clip and Takes does not (1.7). */
    private fun checkClip(clip: TimelineClip, issue: (Issue.Kind, String?) -> Unit) {
        clip.color?.let { color ->
            if (!color.valid || color.motion?.valid(color.pose) { color.applying(it).valid } == false) issue(Issue.Kind.ColorMotion, null)
        }
        clip.frozenFrame?.let { frozen ->
            if (!frozen.valid || frozen.sourceTime < clip.start || frozen.sourceTime >= clip.end ||
                clip.videoEffects.orEmpty().none { it.kind == VideoEffectID.FreezeFrame }) issue(Issue.Kind.FrozenFrame, null)
        }
    }

    /**
     * Whether the analysis [file] is the original's: its maps keyed by UUID are flat arrays, where Takes writes
     * objects and adds `maskGaps` (C/CapturedEffectAnalysis.kt). One that is missing or unreadable is not.
     */
    internal fun madeByYouRec(file: File): Boolean {
        val tree = runCatching { documentJson.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull() ?: return false
        return tree["palettes"] is JsonArray && "maskGaps" !in tree && (tree["qualities"] == null || tree["qualities"] is JsonArray)
    }

    /** The container of an MPEG-4 family movie by its `ftyp` box: "qt" for QuickTime, "mp4" for the ISO formats, null for neither. */
    internal fun container(file: File): String? = runCatching {
        val head = ByteArray(12)
        file.inputStream().use { input -> if (input.readNBytes(head, 0, 12) < 12) return null }
        if (String(head, 4, 4, Charsets.ISO_8859_1) != "ftyp") return null
        if (String(head, 8, 4, Charsets.ISO_8859_1) == "qt  ") "qt" else "mp4"
    }.getOrNull()

    /** The video effects of every clip in [element]. */
    private fun effects(element: JsonElement): List<JsonObject> = when (element) {
        is JsonObject -> element.entries.flatMap { (key, value) ->
            if (key == "videoEffects" && value is JsonArray) value.map { it as JsonObject } else effects(value)
        }
        is JsonArray -> element.flatMap(::effects)
        else -> emptyList()
    }

    private fun mapEffects(element: JsonElement, transform: (JsonObject) -> JsonObject): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (key, value) ->
            if (key == "videoEffects" && value is JsonArray) JsonArray(value.map { transform(it as JsonObject) }) else mapEffects(value, transform)
        })
        is JsonArray -> JsonArray(element.map { mapEffects(it, transform) })
        else -> element
    }

    /** [element] with each `{"media":{"_0":name}}` of [renames] naming the new file. */
    private fun rename(element: JsonElement, renames: Map<String, String>): JsonElement {
        if (renames.isEmpty()) return element
        YouRecMediaFiles.media(element)?.let { name ->
            return renames[name]?.let { JsonObject(mapOf("media" to JsonObject(mapOf("_0" to JsonPrimitive(it))))) } ?: element
        }
        return when (element) {
            is JsonObject -> JsonObject(element.mapValues { rename(it.value, renames) })
            is JsonArray -> JsonArray(element.map { rename(it, renames) })
            else -> element
        }
    }

    /**
     * [element] with every id passed through [remap]: the values of [ID_KEYS], the members of a link and the
     * participants of a placement, and the keys of its windows. A control's id and a sound model's are names, not ids.
     */
    private fun remapIDs(element: JsonElement, remap: (String) -> String): JsonElement = when (element) {
        is JsonObject -> {
            val named = "animatable" in element || "weightsSHA256" in element
            JsonObject(element.mapValues { (key, value) ->
                when {
                    key in ID_KEYS && value is JsonPrimitive && value.isString && !(key == "id" && named) -> JsonPrimitive(remap(value.content))
                    (key == "members" || key == "participants") && value is JsonArray ->
                        JsonArray(value.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.let { p -> JsonPrimitive(remap(p.content)) } ?: it })
                    key == "videos" && value is JsonArray -> JsonArray(value.mapIndexed { index, item ->
                        if (index % 2 == 0 && item is JsonPrimitive && item.isString) JsonPrimitive(remap(item.content)) else remapIDs(item, remap)
                    })
                    else -> remapIDs(value, remap)
                }
            })
        }
        is JsonArray -> JsonArray(element.map { remapIDs(it, remap) })
        else -> element
    }
}
