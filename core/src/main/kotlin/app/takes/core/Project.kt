package app.takes.core

import kotlinx.serialization.SerialName
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

/**
 * A join belongs to these exact neighbors: it only applies while the clip
 * after it is [nextClipID]. Moving or deleting a clip never passes a
 * transition on to unrelated footage.
 */
@Serializable
data class ClipTransition(
    val id: String = newID(),
    val nextClipID: String,
    val style: Style = Style.FadeBlack,
    val duration: Double = 0.5,
) {
    @Serializable
    enum class Style {
        /** Both clips fade through black; nothing overlaps. */
        @SerialName("fadeBlack") FadeBlack,

        /** The next clip starts under the end of this one and they blend. The timeline gets shorter. */
        @SerialName("dissolve") Dissolve,
    }

    val valid: Boolean get() = duration.isFinite() && duration > 0 && duration <= MAXIMUM_DURATION

    companion object {
        const val MAXIMUM_DURATION = 10.0
    }
}

/** A title over the picture. Times are source seconds, so it follows its footage through trims and moves. */
@Serializable
data class TextOverlay(
    val id: String = newID(),
    val text: String = "Your text",
    val start: Double = 0.0,
    val end: Double = 3.0,
    val x: Double = 0.5,
    val y: Double = 0.5,
    // Font size relative to the shorter canvas dimension; consistent on rotation.
    val size: Double = 0.065,
) {
    val valid: Boolean
        get() = start.isFinite() && end.isFinite() && start >= 0 && end > start && text.length <= 500 &&
            x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0 && size.isFinite() && size in 0.02..0.3
}

/** A mark on the footage, at a source second. It follows its clip through trims, splits and moves. */
@Serializable
data class Bookmark(val id: String = newID(), val time: Double, val label: String? = null) {
    val valid: Boolean get() = time.isFinite() && time >= 0 && (label?.length ?: 0) <= 100
}

/** How a clip's own sound is played: level, mute, and a fade at each end. Named as in the original app. */
@Serializable
data class ClipAudio(
    val volumeDB: Double = 0.0,
    val muted: Boolean = false,
    val fadeIn: Double = 0.0,
    val fadeOut: Double = 0.0,
    /** Noise reduction: how much of the cleaned sound replaces the recorded one, 0–1. Clips only. */
    val denoise: Double = 0.0,
) {
    val gain: Double get() = if (muted) 0.0 else Math.pow(10.0, volumeDB / 20)
    val valid: Boolean
        get() = volumeDB.isFinite() && volumeDB in MINIMUM_DB..MAXIMUM_DB && denoise.isFinite() && denoise in 0.0..1.0 &&
            fadeIn.isFinite() && fadeOut.isFinite() && fadeIn >= 0 && fadeOut >= 0 && fadeIn <= 604800 && fadeOut <= 604800

    companion object {
        const val MINIMUM_DB = -60.0
        const val MAXIMUM_DB = 12.0
    }
}

@Serializable
data class TimelineClip(
    val id: String = newID(),
    val sourceID: String,
    val start: Double,
    val end: Double,
    /** The join to the clip after this one. */
    val transition: ClipTransition? = null,
    val texts: List<TextOverlay>? = null,
    val bookmarks: List<Bookmark>? = null,
    /** Null plays the sound as recorded. */
    val audio: ClipAudio? = null,
) {
    val duration: Double get() = end - start

    /** The oldest schema whose readers understand everything this clip uses. */
    val schema: Int get() = when {
        (audio?.denoise ?: 0.0) > 0 -> 6
        audio != null -> 4
        !bookmarks.isNullOrEmpty() -> 3
        transition != null || !texts.isNullOrEmpty() -> 2
        else -> 1
    }
}

/**
 * One edit of the timeline. [captureBase] is runtime provenance: how many
 * captured takes the state had seen when it was read from the project.
 */
@Serializable
data class EditState(
    val clips: List<TimelineClip> = emptyList(),
    val captureBase: Int? = null,
    /** Sound placed on its own tracks, such as music or a voice-over. Null when there is none. */
    val audio: List<AudioItem>? = null,
) {
    /** The movie is as long as its picture; sound past the last clip is not played. */
    val duration: Double get() = TimelineEditing.duration(clips)
}

/**
 * A piece of sound at a fixed place on the timeline, independent of the
 * clips: editing the picture does not move it. Two tracks; items on the same
 * track never overlap.
 */
@Serializable
data class AudioItem(
    val id: String = newID(),
    val sourceID: String,
    /** Timeline second where it begins. */
    val start: Double,
    val sourceStart: Double,
    val sourceEnd: Double,
    val track: Int = 0,
    val audio: ClipAudio? = null,
) {
    val duration: Double get() = sourceEnd - sourceStart
    val end: Double get() = start + duration

    companion object {
        const val TRACKS = 2
    }
}

/**
 * Immutable edit payload. Capture is journaled independently, so a new take
 * never rewrites a historical snapshot or becomes an accidental Undo step.
 */
@Serializable
data class EditSnapshot(val state: EditState, val captureCursor: Int) {
    fun data(): ByteArray = documentJson.encodeToString(serializer(), this).toByteArray()
}

/** How a project is rendered to a movie. Saved with the project, outside undo. */
@Serializable
data class ExportSettings(
    val codec: Codec = Codec.H264,
    val resolution: Resolution = Resolution.Project,
    val frameRate: Int = 30,
) {
    @Serializable
    enum class Codec { @SerialName("H.264") H264, @SerialName("H.265") HEVC }

    @Serializable
    enum class Resolution(val long: Int, val short: Int) {
        @SerialName("Project size") Project(0, 0),
        @SerialName("720p") HD(1280, 720),
        @SerialName("1080p") FullHD(1920, 1080),
        @SerialName("4K") UltraHD(3840, 2160);

        /** The canvas scaled to fit this format in either orientation, with even dimensions. */
        fun size(width: Int, height: Int): Pair<Int, Int> {
            if (this == Project || width <= 0 || height <= 0) return width to height
            val scale = minOf(long.toDouble() / maxOf(width, height), short.toDouble() / minOf(width, height))
            fun even(dimension: Int) = maxOf(2, Math.round(dimension * scale / 2).toInt() * 2)
            return even(width) to even(height)
        }
    }

    val valid: Boolean get() = frameRate in FRAME_RATES

    companion object {
        val FRAME_RATES = listOf(24, 25, 30, 50, 60)
    }
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
    val exportSettings: ExportSettings = ExportSettings(),
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
    val exportSettings: ExportSettings = ExportSettings(),
) {
    private fun materialized(snapshot: EditSnapshot): EditState =
        EditState(snapshot.state.clips + captureLedger.drop(snapshot.captureCursor), captureLedger.size, snapshot.state.audio)

    val edit: EditState get() = materialized(snapshots[historyCursor])
    val history: List<EditState> get() = snapshots.map(::materialized)
    val mediaFiles: List<String> get() = sources.map { it.filename }
    /** The footage, without imported sound files. */
    val recordings: List<SourceMedia> get() = sources.filter { it.audioOnly != true }
    val capturedDuration: Double get() = recordings.sumOf { it.duration }
    val canUndo: Boolean get() = historyCursor > 0
    val canRedo: Boolean get() = historyCursor + 1 < snapshots.size

    fun renamed(title: String, now: Long = System.currentTimeMillis()): Project = copy(title = title, updatedAt = now)

    /**
     * A state built before a take arrived keeps its capture cursor, so the
     * newer takes are appended again rather than silently discarded.
     */
    fun commit(state: EditState, now: Long = System.currentTimeMillis()): Project {
        val sound = state.audio?.ifEmpty { null }
        if (state.clips == edit.clips && sound == edit.audio) return this
        val cursor = (state.captureBase ?: captureLedger.size).coerceIn(0, captureLedger.size)
        return copy(
            // Older readers must not silently drop what they do not know.
            schemaVersion = maxOf(schemaVersion, state.clips.maxOfOrNull { it.schema } ?: 1, if (sound != null) 5 else 1),
            snapshots = snapshots.take(historyCursor + 1) + EditSnapshot(EditState(state.clips, audio = sound), cursor),
            historyCursor = historyCursor + 1,
            updatedAt = now,
        )
    }

    /**
     * Adds media that is not a take, such as an imported sound file. It joins
     * the project's files without appearing on the timeline by itself.
     */
    fun addSource(source: SourceMedia, now: Long = System.currentTimeMillis()): Project =
        copy(sources = sources + source, updatedAt = now)

    /** Recording additions are outside editor Undo: every history step gains the take. */
    fun addRecording(source: SourceMedia, now: Long = System.currentTimeMillis()): Project {
        // Marks made while recording arrive on the take's clip.
        val marks = source.events.orEmpty().filter { it.kind == BOOKMARK_EVENT }.map { Bookmark(id = it.id, time = it.time) }.ifEmpty { null }
        val clip = TimelineClip(sourceID = source.id, start = 0.0, end = source.duration, bookmarks = marks)
        return copy(
            schemaVersion = maxOf(schemaVersion, clip.schema),
            sources = sources + source,
            captureLedger = captureLedger + clip,
            updatedAt = now,
        )
    }

    fun undo(now: Long = System.currentTimeMillis()): Project =
        if (canUndo) copy(historyCursor = historyCursor - 1, updatedAt = now) else this

    fun redo(now: Long = System.currentTimeMillis()): Project =
        if (canRedo) copy(historyCursor = historyCursor + 1, updatedAt = now) else this

    fun validate() {
        if (schemaVersion !in 1..LATEST_READABLE_SCHEMA) invalid()
        if (snapshots.isEmpty() || historyCursor !in snapshots.indices) invalid()
        if (!exportSettings.valid) invalid()
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
            val sound = state.audio.orEmpty()
            if (sound.map { it.id }.toSet().size != sound.size) invalid()
            for (item in sound) {
                val source = byID[item.sourceID] ?: invalid()
                if (!item.start.isFinite() || item.start < 0 || item.start > MAX_DURATION || item.track !in 0 until AudioItem.TRACKS) invalid()
                if (!item.sourceStart.isFinite() || !item.sourceEnd.isFinite() || item.sourceStart < 0 ||
                    item.sourceEnd <= item.sourceStart || item.sourceEnd > source.duration + 0.001) invalid()
                if (item.audio?.valid == false) invalid()
                if (sound.any { it !== item && it.track == item.track && it.start < item.end && item.start < it.end }) invalid()
            }
            for (clip in state.clips) {
                val source = byID[clip.sourceID] ?: invalid()
                if (!clip.start.isFinite() || !clip.end.isFinite() || clip.start < 0 ||
                    clip.end <= clip.start || clip.end > source.duration + 0.001) invalid()
                if (clip.transition?.valid == false) invalid()
                val texts = clip.texts.orEmpty()
                if (texts.map { it.id }.toSet().size != texts.size) invalid()
                if (texts.any { !it.valid || it.end > source.duration + 0.001 }) invalid()
                if (clip.audio?.valid == false) invalid()
                val marks = clip.bookmarks.orEmpty()
                if (marks.map { it.id }.toSet().size != marks.size) invalid()
                if (marks.any { !it.valid || it.time > source.duration + 0.001 }) invalid()
            }
        }
    }

    /** Content-addressed history: unchanged snapshots are never rewritten. */
    fun manifest(write: (name: String, data: ByteArray) -> Unit): ByteArray {
        val names = snapshots.map { snapshot ->
            val data = snapshot.data()
            (sha256(data) + ".json").also { write(it, data) }
        }
        val manifest = Manifest(schemaVersion, id, title, createdAt, updatedAt, sources, historyCursor, captureLedger, names, exportSettings)
        return documentJson.encodeToString(Manifest.serializer(), manifest).toByteArray()
    }

    companion object {
        const val LATEST_READABLE_SCHEMA = 6
        const val BOOKMARK_EVENT = "bookmark"
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
                exportSettings = manifest.exportSettings,
            ).also { it.validate() }
        }
    }
}
