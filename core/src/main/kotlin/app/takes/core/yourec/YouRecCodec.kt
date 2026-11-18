package app.takes.core.yourec

import app.takes.core.AudioModel
import app.takes.core.EditSnapshot
import app.takes.core.EditState
import app.takes.core.ExportFormat
import app.takes.core.Project
import app.takes.core.ProjectException
import app.takes.core.Size
import app.takes.core.SupportedVideoEffects
import app.takes.core.TimelineClip
import app.takes.core.TrackTimeline
import app.takes.core.VideoEffectSettings
import app.takes.core.documentJson
import app.takes.core.sha256
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * A project of YouRec, the iPhone original, read as a Takes project
 * (docs/IOS-INTEROP.md, 3.5 and 7.2).
 *
 * The original's documents are transformed as JSON trees ([YouRecJson]) into
 * what Takes writes, and then read by Takes' own serialisers and checks, so
 * nothing is read twice in two ways. Every step of the history is encoded
 * again in Takes' form, under the name of its new bytes.
 */
object YouRecCodec {
    /** The newest schema the original reads (YouRec/Project.swift:229); a project above it uses what this reading does not know. */
    const val LATEST_READABLE_SCHEMA = 12

    /**
     * [project] as Takes saves it, and [notes] on what changed on the way.
     * [sourceEffects] are the effects each take was recorded with, by its file
     * name, which Takes keeps beside the movie ([app.takes.core.CaptureEffectsFile]).
     * [mediaFiles] are the files the original says the project owns
     * ([YouRecMediaFiles]), and [timelineName] the OpenTimelineIO file its ZIP
     * may hold beside them.
     */
    class Imported(
        val project: Project,
        val notes: List<YouRecNote>,
        val sourceEffects: Map<String, List<VideoEffectSettings>>,
        val mediaFiles: List<String>,
        val timelineName: String,
    )

    /**
     * Reads the YouRec manifest [manifest], loading each `History/` step by
     * name with [load], whose bytes must hash to that name as the original
     * checks them (YouRec/Project.swift:625-629).
     *
     * [probe] gives the upright size of a source by its file name, or null:
     * it is asked only for an edit whose frame came from a take that is not
     * its first picture ([YouRecNote.Kind.CanvasFixed]).
     *
     * Throws [ProjectException] with [ProjectException.Reason.InvalidDocument]
     * for anything the original would not read, or Takes cannot.
     */
    fun import(manifest: ByteArray, load: (name: String) -> ByteArray, probe: (filename: String) -> Size? = { null }): Imported = try {
        Reader(probe).read(manifest, load)
    } catch (error: ProjectException) {
        throw error
    } catch (error: Exception) {
        throw ProjectException(ProjectException.Reason.InvalidDocument, error)
    }

    private class Reader(val probe: (String) -> Size?) {
        val notes = LinkedHashMap<YouRecNote.Kind, MutableList<String>>()
        var shortened = false
        val sizes = HashMap<String, Size?>()

        fun note(kind: YouRecNote.Kind, file: String? = null) {
            val files = notes.getOrPut(kind) { mutableListOf() }
            if (file != null && file !in files) files += file
        }

        fun read(data: ByteArray, load: (String) -> ByteArray): Imported {
            val root = documentJson.parseToJsonElement(data.decodeToString()) as? JsonObject ?: invalid("The manifest is not an object")
            val schema = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull ?: invalid("No schema")
            if (schema !in 1..LATEST_READABLE_SCHEMA) invalid("Schema $schema")
            (root["recordingHistoryVersion"] as? JsonPrimitive)?.let { if (it.intOrNull != 1) invalid("Recording history version") }

            // Sources: the dates, and the effects of each take, which go beside its movie.
            val sourceEffects = LinkedHashMap<String, List<VideoEffectSettings>>()
            val sourceTrees = (root["sources"] as? JsonArray ?: invalid("No sources")).map { element ->
                val source = element as? JsonObject ?: invalid("A source is not an object")
                val fields = LinkedHashMap(source)
                fields["recordedAt"] = JsonPrimitive(YouRecJson.dateToTakes(source["recordedAt"]))
                fields.remove("videoEffects")?.takeIf { it !is JsonNull }?.let { effects ->
                    val filename = (source["filename"] as? JsonPrimitive)?.content ?: invalid("A source has no file")
                    val list = documentJson.decodeFromJsonElement(SupportedVideoEffects, effects)
                    if (list.isNotEmpty()) sourceEffects[filename] = list
                }
                JsonObject(fields)
            }
            val sourceFiles = sourceTrees.associate { (it["id"] as? JsonPrimitive)?.content to (it["filename"] as? JsonPrimitive)?.content }

            // The history: from its files, or, in the oldest documents, inline.
            val steps: List<JsonObject> = when {
                root["snapshotFiles"] is JsonArray -> (root["snapshotFiles"] as JsonArray).map { element ->
                    val name = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid("A step name is not a string")
                    if (!Project.isSnapshotName(name)) invalid("A step name is not a hash")
                    val bytes = load(name)
                    if (sha256(bytes) + ".json" != name) invalid("A step does not match its name")
                    documentJson.parseToJsonElement(bytes.decodeToString()) as? JsonObject ?: invalid("A step is not an object")
                }
                root["snapshots"] is JsonArray -> (root["snapshots"] as JsonArray).map { it as? JsonObject ?: invalid("A step is not an object") }
                // `history` is a list of states from before the capture ledger, which needs the original's migration.
                else -> invalid("No history files")
            }
            val snapshots = steps.map { step -> snapshot(step, sourceFiles) }

            // The capture ledger: the clips, and which of them were inserted at a chosen place.
            val events = sourceTrees.associate { source ->
                (source["id"] as? JsonPrimitive)?.content to Pair((source["duration"] as? JsonPrimitive)?.doubleOrNull ?: 0.0, source["events"] as? JsonArray)
            }
            val bookmarked = HashSet<String>()
            val insertions = ArrayList<Int>()
            val ledger = (root["captureLedger"] as? JsonArray).orEmpty().mapIndexed { index, element ->
                val entry = element as? JsonObject ?: invalid("A ledger entry is not an object")
                val clip = entry["clip"] as? JsonObject ?: invalid("A ledger entry has no clip")
                val insertion = (entry["insertion"] as? JsonPrimitive)?.booleanOrNull ?: invalid("A ledger entry has no insertion flag")
                if (insertion) insertions += index
                var fields = YouRecJson.timelineClipToTakes(clip) { shortened = true }
                // The marks made while recording, which the original adds when it replays the take (YouRec/TrackTimeline.swift:1463-1469).
                val sourceID = (clip["sourceID"] as? JsonPrimitive)?.content
                if (!insertion && sourceID != null && bookmarked.add(sourceID)) {
                    val (duration, list) = events[sourceID] ?: (0.0 to null)
                    val marks = list.orEmpty().mapNotNull { event ->
                        val fields = event as? JsonObject ?: return@mapNotNull null
                        val time = (fields["time"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                        if ((fields["kind"] as? JsonPrimitive)?.content != Project.BOOKMARK_EVENT || !(time >= 0 && time < duration)) return@mapNotNull null
                        buildJsonObject { put("id", fields["id"] ?: invalid("A mark has no id")); put("time", time) }
                    }
                    if (marks.isNotEmpty()) fields = JsonObject(LinkedHashMap(fields).apply { put("bookmarks", JsonArray(marks)) })
                }
                documentJson.decodeFromJsonElement(TimelineClip.serializer(), fields)
            }

            if ((root["recordingInsertion"] as? JsonPrimitive)?.doubleOrNull != null) note(YouRecNote.Kind.RecordingRunEnded)
            if (shortened) note(YouRecNote.Kind.TitlesShortened)
            // The restoration models the stages need, named by the original's model ids: the app tells of those not downloaded.
            val neural = setOf(AudioModel.Lava.id, AudioModel.VoiceFixer.id)
            snapshots.flatMap { step -> step.timeline?.audio.orEmpty() }.flatMap { item -> item.enhancements.flatMap { it.effects } }
                .filter { it.enabled && it.amount > 0 && it.model?.id in neural }.mapNotNull { it.model?.id }.distinct().sorted()
                .forEach { note(YouRecNote.Kind.NeuralStagesUnprocessed, it) }

            // Every step in Takes' own form, under the hash of its new bytes.
            val written = LinkedHashMap<String, ByteArray>()
            val names = snapshots.map { snapshot -> snapshot.filename().also { written[it] = snapshot.data() } }
            val takesSchema = maxOf(TrackTimeline.SCHEMA, snapshots.maxOf { it.schema }, ledger.maxOfOrNull { it.schema } ?: 0)
            val takes = buildJsonObject {
                put("schemaVersion", takesSchema)
                put("id", root["id"] ?: invalid("No id"))
                put("title", root["title"] ?: invalid("No title"))
                put("createdAt", YouRecJson.dateToTakes(root["createdAt"]))
                put("updatedAt", YouRecJson.dateToTakes(root["updatedAt"]))
                put("sources", JsonArray(sourceTrees))
                put("historyCursor", root["historyCursor"] ?: invalid("No cursor"))
                put("captureLedger", documentJson.encodeToJsonElement(ListSerializer(TimelineClip.serializer()), ledger))
                put("snapshotFiles", JsonArray(names.map { JsonPrimitive(it) }))
                root["exportSettings"]?.takeIf { it !is JsonNull }?.let { put("exportSettings", it) }
                if (insertions.isNotEmpty()) put("captureInsertions", JsonArray(insertions.map { JsonPrimitive(it) }))
            }
            val project = Project.readManifest(takes.toString().toByteArray()) { name -> written.getValue(name) }
            val mediaFiles = YouRecMediaFiles.of(project, sourceEffects.values)
            return Imported(project, notes.map { (kind, files) -> YouRecNote(kind, files) }, sourceEffects, mediaFiles, YouRecMediaFiles.timelineName(mediaFiles))
        }

        /** One step of the history (YouRec/Project.swift:196-214), as Takes saves it. */
        fun snapshot(step: JsonObject, sourceFiles: Map<String?, String?>): EditSnapshot {
            val state = step["state"] as? JsonObject ?: invalid("A step has no state")
            val cursor = step["captureCursor"] ?: invalid("A step has no capture cursor")
            val saved = state["timeline"] as? JsonObject
            val takesState = if (saved != null) {
                val timeline = YouRecJson.trackTimelineToTakes(saved) { shortened = true }
                var outputSize = state["outputSize"]?.takeIf { it !is JsonNull }
                val reference = (state["canvasReference"] as? JsonPrimitive)?.content
                if (outputSize == null && reference != null) outputSize = canvas(saved, reference, sourceFiles)
                buildJsonObject {
                    put("timeline", timeline)
                    outputSize?.let { put("outputSize", it) }
                }
            } else {
                // Saved before the track timeline: a sequence of clips, which Takes reads as one.
                val clips = state["clips"] as? JsonArray ?: invalid("A step has neither a timeline nor clips")
                if ((state["effects"] as? JsonArray).orEmpty().isNotEmpty()) note(YouRecNote.Kind.LegacyEffectsDropped)
                buildJsonObject {
                    put("clips", JsonArray(clips.map { YouRecJson.timelineClipToTakes(it as? JsonObject ?: invalid("A clip is not an object")) { shortened = true } }))
                    state["outputSize"]?.takeIf { it !is JsonNull }?.let { put("outputSize", it) }
                }
            }
            val tree = buildJsonObject { put("state", takesState); put("captureCursor", cursor) }
            return documentJson.decodeFromJsonElement(EditSnapshot.serializer(), tree)
        }

        /**
         * The frame of an edit whose frame came from the take [reference]
         * (YouRec/Project.swift:146-151, YouRec/MediaRenderer.swift:509-517).
         * Takes always takes it from the first picture, so the take's upright
         * size becomes the edit's fixed size, even as the original rounds it,
         * unless that take is the first picture anyway or is no longer on the
         * timeline. Null when the frame follows the first picture.
         */
        fun canvas(timeline: JsonObject, reference: String, sourceFiles: Map<String?, String?>): JsonElement? {
            if (reference == YouRecJson.firstVideoSourceID(timeline) || reference !in YouRecJson.videoSourceIDs(timeline)) return null
            val filename = sourceFiles[reference] ?: return null
            val size = sizes.getOrPut(filename) {
                probe(filename)?.let { upright ->
                    val (width, height) = ExportFormat.canvas(upright.width, upright.height)
                    Size(width.toDouble(), height.toDouble()).takeIf { EditState.validOutputSize(it) }
                }
            }
            if (size == null) {
                note(YouRecNote.Kind.CanvasDropped, filename)
                return null
            }
            note(YouRecNote.Kind.CanvasFixed, filename)
            return JsonArray(listOf(JsonPrimitive(size.width), JsonPrimitive(size.height)))
        }
    }

    private fun invalid(reason: String): Nothing = throw YouRecFormatException(reason)
}
