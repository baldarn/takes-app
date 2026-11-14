package app.takes.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID

fun newID(): String = UUID.randomUUID().toString().uppercase()

internal val documentJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

class ProjectException(val reason: Reason, cause: Throwable? = null) : Exception(reason.message, cause) {
    enum class Reason(val message: String) {
        InvalidDocument("This project has unsupported or invalid editing data. Its files have been preserved."),
        MissingMedia("A source file is missing. The project has been preserved."),
        InvalidMedia("The recording could not be opened as a valid video. Its file has been preserved."),
    }
}

private fun invalid(): Nothing = throw ProjectException(ProjectException.Reason.InvalidDocument)

@Serializable
data class CaptureEvent(
    val id: String = newID(),
    val time: Double,
    val kind: String,
    val detail: String,
) {
    companion object {
        fun recovered(events: List<CaptureEvent>, duration: Double): List<CaptureEvent> =
            events.filter { it.time.isFinite() && it.time >= 0 && it.time < duration }
    }
}

/** Immutable recorded or imported media. An edit never rewrites it. */
@Serializable
data class SourceMedia(
    val id: String = newID(),
    val filename: String,
    val duration: Double,
    val recordedAt: Long = System.currentTimeMillis(),
    val events: List<CaptureEvent>? = null,
    val sha256: String? = null,
    val audioOnly: Boolean? = null,
    val originalName: String? = null,
)

@Serializable
data class TimelineClip(
    val id: String = newID(),
    val sourceID: String,
    val start: Double,
    val end: Double,
) {
    val duration: Double get() = end - start
}

/**
 * One edit of the timeline. [captureBase] is runtime provenance: how many
 * captured takes the state had seen when it was read from the project.
 */
@Serializable
data class EditState(
    val clips: List<TimelineClip> = emptyList(),
    val captureBase: Int? = null,
) {
    val duration: Double get() = clips.sumOf { it.duration }
}

/**
 * Immutable edit payload. Capture is journaled independently, so a new take
 * never rewrites a historical snapshot or becomes an accidental Undo step.
 */
@Serializable
data class EditSnapshot(val state: EditState, val captureCursor: Int) {
    fun data(): ByteArray = documentJson.encodeToString(serializer(), this).toByteArray()
}

@Serializable
private data class Manifest(
    val schemaVersion: Int,
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sources: List<SourceMedia>,
    val historyCursor: Int,
    val captureLedger: List<TimelineClip> = emptyList(),
    val snapshotFiles: List<String>,
)

data class Project(
    val title: String,
    val schemaVersion: Int = 1,
    val id: String = newID(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val sources: List<SourceMedia> = emptyList(),
    val snapshots: List<EditSnapshot> = listOf(EditSnapshot(EditState(), 0)),
    val captureLedger: List<TimelineClip> = emptyList(),
    val historyCursor: Int = 0,
) {
    private fun materialized(snapshot: EditSnapshot): EditState =
        EditState(snapshot.state.clips + captureLedger.drop(snapshot.captureCursor), captureLedger.size)

    val edit: EditState get() = materialized(snapshots[historyCursor])
    val history: List<EditState> get() = snapshots.map(::materialized)
    val mediaFiles: List<String> get() = sources.map { it.filename }
    val capturedDuration: Double get() = sources.sumOf { it.duration }
    val canUndo: Boolean get() = historyCursor > 0
    val canRedo: Boolean get() = historyCursor + 1 < snapshots.size

    fun renamed(title: String, now: Long = System.currentTimeMillis()): Project = copy(title = title, updatedAt = now)

    /**
     * A state built before a take arrived keeps its capture cursor, so the
     * newer takes are appended again rather than silently discarded.
     */
    fun commit(state: EditState, now: Long = System.currentTimeMillis()): Project {
        if (state.clips == edit.clips) return this
        val cursor = (state.captureBase ?: captureLedger.size).coerceIn(0, captureLedger.size)
        return copy(
            snapshots = snapshots.take(historyCursor + 1) + EditSnapshot(EditState(state.clips), cursor),
            historyCursor = historyCursor + 1,
            updatedAt = now,
        )
    }

    /** Recording additions are outside editor Undo: every history step gains the take. */
    fun addRecording(source: SourceMedia, now: Long = System.currentTimeMillis()): Project = copy(
        sources = sources + source,
        captureLedger = captureLedger + TimelineClip(sourceID = source.id, start = 0.0, end = source.duration),
        updatedAt = now,
    )

    fun undo(now: Long = System.currentTimeMillis()): Project =
        if (canUndo) copy(historyCursor = historyCursor - 1, updatedAt = now) else this

    fun redo(now: Long = System.currentTimeMillis()): Project =
        if (canRedo) copy(historyCursor = historyCursor + 1, updatedAt = now) else this

    fun validate() {
        if (schemaVersion !in 1..LATEST_READABLE_SCHEMA) invalid()
        if (snapshots.isEmpty() || historyCursor !in snapshots.indices) invalid()
        if (snapshots.any { it.captureCursor !in 0..captureLedger.size }) invalid()
        val byID = sources.associateBy { it.id }
        if (byID.size != sources.size) invalid()
        if (sources.map { it.filename.lowercase() }.toSet().size != sources.size) invalid()
        if (captureLedger.any { it.sourceID !in byID }) invalid()
        for (source in sources) {
            val name = source.filename
            if (!source.duration.isFinite() || source.duration <= 0 || source.duration > MAX_DURATION) invalid()
            if ((source.originalName?.length ?: 0) > 255) invalid()
            if (name.isEmpty() || name != File(name).name || name.startsWith(".") || name.contains("\\")) invalid()
            if (name.lowercase() in RESERVED_NAMES) invalid()
            source.sha256?.let { hash -> if (hash.length != 64 || hash.any { it !in HEX }) invalid() }
            if (source.events.orEmpty().any { !it.time.isFinite() || it.time < 0 || it.time > source.duration + 1 }) invalid()
        }
        for (state in history) {
            if (!state.duration.isFinite() || state.duration > MAX_DURATION) invalid()
            if (state.clips.map { it.id }.toSet().size != state.clips.size) invalid()
            for (clip in state.clips) {
                val source = byID[clip.sourceID] ?: invalid()
                if (!clip.start.isFinite() || !clip.end.isFinite() || clip.start < 0 ||
                    clip.end <= clip.start || clip.end > source.duration + 0.001) invalid()
            }
        }
    }

    /** Content-addressed history: unchanged snapshots are never rewritten. */
    fun manifest(write: (name: String, data: ByteArray) -> Unit): ByteArray {
        val names = snapshots.map { snapshot ->
            val data = snapshot.data()
            (sha256(data) + ".json").also { write(it, data) }
        }
        val manifest = Manifest(schemaVersion, id, title, createdAt, updatedAt, sources, historyCursor, captureLedger, names)
        return documentJson.encodeToString(Manifest.serializer(), manifest).toByteArray()
    }

    companion object {
        const val LATEST_READABLE_SCHEMA = 1
        private const val MAX_DURATION = 604800.0
        private const val HEX = "0123456789abcdef"
        private val RESERVED_NAMES = setOf("project.json", "history", "recovery", "cache", "exports")

        fun isSnapshotName(name: String): Boolean =
            name.length == 69 && name.endsWith(".json") && name.take(64).all { it in HEX }

        fun readManifest(data: ByteArray, load: (name: String) -> ByteArray): Project {
            val manifest = try {
                documentJson.decodeFromString(Manifest.serializer(), data.decodeToString())
            } catch (error: Exception) {
                throw ProjectException(ProjectException.Reason.InvalidDocument, error)
            }
            if (manifest.snapshotFiles.any { !isSnapshotName(it) }) invalid()
            val snapshots = manifest.snapshotFiles.map { name ->
                val payload = try { load(name) } catch (error: Exception) {
                    throw ProjectException(ProjectException.Reason.InvalidDocument, error)
                }
                if (sha256(payload) + ".json" != name) invalid()
                try {
                    documentJson.decodeFromString(EditSnapshot.serializer(), payload.decodeToString())
                } catch (error: Exception) {
                    throw ProjectException(ProjectException.Reason.InvalidDocument, error)
                }
            }
            return Project(
                title = manifest.title, schemaVersion = manifest.schemaVersion, id = manifest.id,
                createdAt = manifest.createdAt, updatedAt = manifest.updatedAt, sources = manifest.sources,
                snapshots = snapshots, captureLedger = manifest.captureLedger, historyCursor = manifest.historyCursor,
            ).also { it.validate() }
        }
    }
}
