package app.takes

import android.app.Application
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.takes.core.CubeLUT
import app.takes.core.EditState
import app.takes.core.ImportedLUT
import app.takes.core.LUTException
import app.takes.core.ExportSettings
import app.takes.core.Project
import app.takes.core.ProjectDisk
import app.takes.core.SourceMedia
import app.takes.core.TimelineEditing
import app.takes.core.newID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

class TakesApp : Application() {
    val store by lazy { ProjectStore(this) }
    val capture by lazy { CaptureController(this, store) }
    val exporter by lazy { Exporter(this, store) }
}

/** Seconds of playable media, or null when the file is not a valid movie. */
fun mediaDuration(file: File): Double? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.path)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ?.let { it / 1000.0 }?.takeIf { it > 0 }
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

data class DeletedProject(val project: Project, val date: Long?)

/** Main-thread owner of the project library. Every change is saved before it is shown. */
class ProjectStore(context: Context) {
    private val prefs = context.getSharedPreferences("projects", Context.MODE_PRIVATE)
    private val resolver = context.contentResolver
    private val scope = MainScope()
    val disk = ProjectDisk(File(context.filesDir, "Projects"))

    var projects by mutableStateOf(emptyList<Project>()); private set
    var deleted by mutableStateOf(emptyList<DeletedProject>()); private set
    var unavailable by mutableStateOf(emptyMap<String, String>()); private set
    var selectedID by mutableStateOf(prefs.getString("selected", null)); private set
    var error by mutableStateOf<String?>(null)
    /** What an import in progress is doing, for display. */
    var importing by mutableStateOf<String?>(null); private set

    val selected: Project? get() = projects.firstOrNull { it.id == selectedID }

    init {
        disk.root.mkdirs()
        disk.finishPendingDeletions()
        reload()
        projects.forEach(::indexFinishedTakes)
    }

    fun file(project: Project, source: SourceMedia) = File(disk.folder(project.id), source.filename)

    fun select(id: String?) {
        selectedID = id
        prefs.edit().putString("selected", id).apply()
    }

    fun create(): Project? {
        val title = "Recording " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date())
        val project = Project(title)
        if (!update(project)) return null
        select(project.id)
        return project
    }

    fun rename(project: Project, title: String) {
        val trimmed = title.trim()
        if (trimmed.isNotEmpty() && trimmed != project.title) update(project.renamed(trimmed))
    }

    /**
     * Copies videos picked from the device into the project, or into a new
     * one when [projectID] is null, and adds them to the end of its timeline.
     * The originals stay where they are; the project owns its copies.
     */
    fun importVideos(projectID: String?, uris: List<Uri>) {
        if (uris.isEmpty() || importing != null) return
        val project = projectID?.let { id -> projects.firstOrNull { it.id == id } } ?: create() ?: return
        scope.launch {
            var failed = 0
            uris.forEachIndexed { index, uri ->
                importing = "Importing ${index + 1} of ${uris.size}…"
                val source = withContext(Dispatchers.IO) { copyIn(project.id, uri) }
                if (source == null || !addRecording(project.id, source)) failed++
            }
            importing = null
            if (failed > 0) {
                error = if (uris.size == 1) "The video could not be imported. It may not be a playable movie."
                else "$failed of ${uris.size} videos could not be imported."
            }
        }
    }

    /**
     * Copies a sound file picked from the device into the project and places
     * it on an audio track at timeline second [at], as one undoable edit.
     */
    fun importAudio(projectID: String, uri: Uri, at: Double) {
        if (importing != null) return
        scope.launch {
            importing = "Importing sound…"
            val source = withContext(Dispatchers.IO) { copyInSound(projectID, uri) }
            importing = null
            val project = projects.firstOrNull { it.id == projectID }
            if (source == null || project == null) {
                error = "The sound could not be imported. It may not be a playable audio file."
                return@launch
            }
            val added = project.addSource(source)
            val edit = TimelineEditing.addAudio(added.edit, source.id, source.duration, at)
            if (edit == null) {
                file(project, source).delete()
                error = "Both audio tracks are busy here. Move the playhead or remove a sound first."
            } else update(added.commit(edit))
        }
    }

    /**
     * Copies a .cube file picked from the device into the project, named by
     * its content so the same LUT is stored once. Null, with a message for
     * the person, when the file is not a usable LUT.
     */
    suspend fun importLut(projectID: String, uri: Uri): ImportedLUT? = withContext(Dispatchers.IO) {
        try {
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val limited = ByteArray(CubeLUT.MAXIMUM_BYTES + 1)
                var size = 0
                while (size < limited.size) {
                    val read = input.read(limited, size, limited.size - size)
                    if (read < 0) break
                    size += read
                }
                limited.copyOf(size)
            } ?: return@withContext null
            CubeLUT.parse(bytes) // rejects anything that is not a valid, bounded cube
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }?.substringBeforeLast('.')?.take(120)?.ifBlank { null } ?: "Imported LUT"
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val folder = disk.folder(projectID).apply { mkdirs() }
            val file = File(folder, "effect-$hash.cube")
            if (!file.isFile) ProjectDisk.writeAtomically(bytes, file)
            ImportedLUT(file.name, name)
        } catch (problem: LUTException) {
            withContext(Dispatchers.Main) { error = problem.message }
            null
        } catch (_: Exception) {
            withContext(Dispatchers.Main) { error = "The LUT file could not be read." }
            null
        }
    }

    private fun copyInSound(projectID: String, uri: Uri): SourceMedia? {
        val folder = disk.folder(projectID).apply { mkdirs() }
        val id = newID()
        val extension = when (resolver.getType(uri)) {
            "audio/mpeg" -> "mp3"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/ogg" -> "ogg"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/aac" -> "aac"
            else -> "m4a"
        }
        val file = File(folder, "audio-${System.currentTimeMillis()}-$id.$extension")
        val partial = File(folder, ".${file.name}.tmp")
        return try {
            resolver.openInputStream(uri)?.use { input -> partial.outputStream().use { input.copyTo(it) } } ?: return null
            val duration = mediaDuration(partial) ?: return null
            if (!hasAudio(partial) || !partial.renameTo(file)) return null
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            SourceMedia(id = id, filename = file.name, duration = duration, audioOnly = true, originalName = name?.take(255))
        } catch (_: Exception) {
            null
        } finally {
            partial.delete()
        }
    }

    private fun hasAudio(file: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        } catch (_: Exception) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun copyIn(projectID: String, uri: Uri): SourceMedia? {
        val folder = disk.folder(projectID).apply { mkdirs() }
        val id = newID()
        val extension = when (resolver.getType(uri)) {
            "video/quicktime" -> "mov"
            "video/webm" -> "webm"
            "video/x-matroska" -> "mkv"
            "video/3gpp" -> "3gp"
            else -> "mp4"
        }
        val file = File(folder, "import-${System.currentTimeMillis()}-$id.$extension")
        // Hidden until complete, so an interrupted copy is never mistaken for a recording.
        val partial = File(folder, ".${file.name}.tmp")
        return try {
            resolver.openInputStream(uri)?.use { input -> partial.outputStream().use { input.copyTo(it) } } ?: return null
            val duration = mediaDuration(partial) ?: return null
            if (displaySize(partial) == null || !partial.renameTo(file)) return null
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            SourceMedia(id = id, filename = file.name, duration = duration, originalName = name?.take(255))
        } catch (_: Exception) {
            null
        } finally {
            partial.delete()
        }
    }

    fun addRecording(projectID: String, source: SourceMedia): Boolean {
        val project = projects.firstOrNull { it.id == projectID } ?: return false
        return update(project.addRecording(source))
    }

    fun commit(projectID: String, state: EditState) = change(projectID) { it.commit(state) }

    fun setExportSettings(projectID: String, settings: ExportSettings) = change(projectID) { it.copy(exportSettings = settings) }

    fun undo(projectID: String) = change(projectID) { it.undo() }

    fun redo(projectID: String) = change(projectID) { it.redo() }

    private fun change(projectID: String, edit: (Project) -> Project) {
        val project = projects.firstOrNull { it.id == projectID } ?: return
        val next = edit(project)
        if (next != project) update(next)
    }

    fun delete(project: Project) = attempt("The project could not be deleted") {
        disk.moveToDeleted(project)
        if (selectedID == project.id) select(null)
    }

    fun restore(project: Project) = attempt("The project could not be restored") { disk.restoreDeleted(project.id) }

    fun erase(project: Project) = attempt("The project could not be erased") { disk.eraseDeleted(project.id) }

    private fun attempt(failure: String, action: () -> Unit) {
        try {
            action()
        } catch (problem: Exception) {
            error = "$failure: ${problem.message}"
        }
        reload()
    }

    private fun reload() {
        val library = disk.loadAll()
        projects = library.projects
        unavailable = library.unavailable
        deleted = disk.loadAll(disk.deletedRoot).projects.map { DeletedProject(it, disk.deletionRecord(it.id)?.date) }
    }

    private fun update(project: Project): Boolean = try {
        disk.save(project)
        projects = (projects.filter { it.id != project.id } + project).sortedByDescending { it.updatedAt }
        true
    } catch (problem: Exception) {
        error = "Changes not saved: ${problem.message}"
        false
    }

    // A take is never removed from a manifest, so a playable movie the manifest
    // does not list was finished just before its project could be saved.
    private fun indexFinishedTakes(project: Project) {
        for (file in disk.unindexedRecordings(project)) {
            val duration = mediaDuration(file) ?: continue
            addRecording(project.id, SourceMedia(filename = file.name, duration = duration, recordedAt = file.lastModified()))
        }
    }
}
