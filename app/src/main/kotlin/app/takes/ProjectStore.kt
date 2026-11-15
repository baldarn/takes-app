package app.takes

import android.app.Application
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.takes.core.CaptureEventsFile
import app.takes.core.ClipFragment
import app.takes.core.CubeLUT
import app.takes.core.EditState
import app.takes.core.ImportedLUT
import app.takes.core.LUTException
import app.takes.core.ExportSettings
import app.takes.core.Project
import app.takes.core.ProjectArchive
import app.takes.core.ProjectException
import app.takes.core.ProjectDisk
import app.takes.core.ProjectDuplicate
import app.takes.core.ProjectDuplicateException
import app.takes.core.ProjectRestore
import app.takes.core.ProjectRestoreException
import app.takes.core.SourceMedia
import app.takes.core.TimelineEditing
import app.takes.core.UnusedTakes
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

/** The text shown for a project problem; the core module only has it in English. */
val ProjectException.Reason.label: Int
    get() = when (this) {
        ProjectException.Reason.InvalidDocument -> R.string.project_error_invalid_document
        ProjectException.Reason.MissingMedia -> R.string.project_error_missing_media
        ProjectException.Reason.InvalidMedia -> R.string.project_error_invalid_media
        ProjectException.Reason.NewerVersion -> R.string.project_error_newer_version
    }

/** Main-thread owner of the project library. Every change is saved before it is shown. */
class ProjectStore(context: Context) {
    // For the texts shown to the person.
    private val context = context.applicationContext
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
    /**
     * What Copy or Cut last took in each project, by project id. As in the original, a project has its own
     * clipboard, kept while the app runs and never saved: a fragment names the recordings of its project.
     */
    val clipboards = mutableStateMapOf<String, ClipFragment>()

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
        val title = context.getString(R.string.projects_default_name, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date()))
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
                importing = context.getString(R.string.projects_importing_progress, index + 1, uris.size)
                val source = withContext(Dispatchers.IO) { copyIn(project.id, uri) }
                if (source == null || !addRecording(project.id, source)) failed++
            }
            importing = null
            if (failed > 0) {
                error = if (uris.size == 1) context.getString(R.string.projects_import_failed_one)
                else context.getString(R.string.projects_import_failed_some, failed, uris.size)
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
            importing = context.getString(R.string.sound_importing)
            val source = withContext(Dispatchers.IO) { copyInSound(projectID, uri) }
            importing = null
            val project = projects.firstOrNull { it.id == projectID }
            if (source == null || project == null) {
                error = context.getString(R.string.sound_import_failed)
                return@launch
            }
            val added = project.addSource(source)
            val edit = TimelineEditing.addAudio(added.edit, source.id, source.duration, at)
            if (edit == null) {
                file(project, source).delete()
                error = context.getString(R.string.sound_tracks_busy)
            } else update(added.commit(edit))
        }
    }

    /**
     * Writes the project as a ZIP to a document the user chose: its recordings
     * and sounds, its editing history, and the current edit as an
     * OpenTimelineIO file that other editors can open.
     */
    fun archive(project: Project, destination: Uri) {
        if (importing != null) return
        scope.launch {
            importing = context.getString(R.string.projects_zipping)
            val problem = withContext(Dispatchers.IO) {
                try {
                    var shown = -1
                    resolver.openOutputStream(destination)!!.use { stream ->
                        // write() flushes; use() closes the document.
                        ProjectArchive.write(disk.folder(project.id), project, stream.buffered(1 shl 16)) { written, total ->
                            val percent = if (total > 0) (written * 100 / total).toInt() else 100
                            if (percent != shown) { shown = percent; importing = context.getString(R.string.projects_zipping_percent, percent) }
                        }
                    }
                    null
                } catch (failure: Exception) {
                    // A partial archive would look like a backup and not be one.
                    runCatching { DocumentsContract.deleteDocument(resolver, destination) }
                    describe(failure)
                }
            }
            importing = null
            error = if (problem == null) context.getString(R.string.projects_zip_saved) else context.getString(R.string.projects_zip_failed, problem)
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
            }?.substringBeforeLast('.')?.take(120)?.ifBlank { null } ?: context.getString(R.string.color_lut_default_name)
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val folder = disk.folder(projectID).apply { mkdirs() }
            val file = File(folder, "effect-lut-$hash.cube")
            if (!file.isFile) ProjectDisk.writeAtomically(bytes, file)
            ImportedLUT(file.name, name)
        } catch (problem: LUTException) {
            withContext(Dispatchers.Main) { error = problem.message?.let { context.getString(R.string.color_lut_rejected, it) } }
            null
        } catch (_: Exception) {
            withContext(Dispatchers.Main) { error = context.getString(R.string.color_lut_unreadable) }
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

    /** The next takes go into the edit at timeline second [at], as one undo step, instead of at the end. */
    fun recordHere(projectID: String, at: Double) = change(projectID) { it.beginRecordingInsertion(at) }

    /** Takes go to the end of the project again. */
    fun recordAtEnd(projectID: String) = change(projectID) { it.endRecordingInsertion() }

    /**
     * Puts takes that are in the project but not in the edit back at timeline
     * second [at], in the order given, as one Undo step. Returns where the
     * first one begins, or null when nothing was added.
     */
    fun addUnused(projectID: String, takes: List<SourceMedia>, at: Double): Double? {
        val project = projects.firstOrNull { it.id == projectID } ?: return null
        val added = UnusedTakes.add(project, takes, at) ?: return null
        return if (update(added.project)) added.start else null
    }

    fun undo(projectID: String) = change(projectID) { it.undo() }

    fun redo(projectID: String) = change(projectID) { it.redo() }

    private fun change(projectID: String, edit: (Project) -> Project) {
        val project = projects.firstOrNull { it.id == projectID } ?: return
        val next = edit(project)
        if (next != project) update(next)
    }

    fun delete(project: Project) = attempt(R.string.projects_delete_failed) {
        disk.moveToDeleted(project)
        if (selectedID == project.id) select(null)
    }

    /**
     * Brings back a project from a ZIP made by "Zip project". It always
     * arrives as a new project, so nothing already in the library is replaced.
     */
    fun openArchive(source: Uri) {
        if (importing != null) return
        scope.launch {
            importing = context.getString(R.string.projects_unzipping)
            val problem = withContext(Dispatchers.IO) {
                try {
                    val size = resolver.query(source, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { row ->
                        if (row.moveToFirst() && !row.isNull(0)) row.getLong(0) else 0L
                    } ?: 0L
                    disk.root.mkdirs()
                    // Keep some room free, as the original does, so the phone is not filled to the brim.
                    val room = (disk.root.usableSpace - 256_000_000L).coerceAtLeast(0)
                    var shown = -1
                    resolver.openInputStream(source)!!.use { stream ->
                        ProjectRestore.restore(stream, disk.root, room) { read ->
                            val percent = if (size > 0) (read * 100 / size).toInt().coerceIn(0, 100) else -1
                            if (percent != shown && percent >= 0) { shown = percent; importing = context.getString(R.string.projects_unzipping_percent, percent) }
                        }
                    }
                    null
                } catch (failure: Exception) {
                    (failure as? ProjectRestoreException)?.let { context.getString(it.reason.label) } ?: describe(failure)
                }
            }
            importing = null
            reload()
            error = if (problem == null) context.getString(R.string.projects_unzip_done) else context.getString(R.string.projects_unzip_failed, problem)
        }
    }

    /** Makes an independent copy of the project, with its recordings and editing history. */
    fun duplicate(project: Project) {
        if (importing != null) return
        scope.launch {
            importing = context.getString(R.string.projects_duplicating)
            val title = ProjectDuplicate.title(project.title, projects.map { it.title }, context.getString(R.string.projects_copy_word))
            val result = withContext(Dispatchers.IO) {
                try {
                    val room = (disk.root.usableSpace - 256_000_000L).coerceAtLeast(0)
                    Result.success(ProjectDuplicate.duplicate(project, disk.root, title, room))
                } catch (failure: Exception) {
                    Result.failure(failure)
                }
            }
            importing = null
            reload()
            result.onSuccess { select(it.id) }.onFailure { failure ->
                val problem = if (failure is ProjectDuplicateException) context.getString(R.string.unzip_no_space) else describe(failure as Exception)
                error = context.getString(R.string.projects_duplicate_failed, problem)
            }
        }
    }

    fun restore(project: Project) = attempt(R.string.projects_restore_failed) { disk.restoreDeleted(project.id) }

    fun erase(project: Project) = attempt(R.string.projects_erase_failed) { disk.eraseDeleted(project.id) }

    private fun attempt(failure: Int, action: () -> Unit) {
        try {
            action()
        } catch (problem: Exception) {
            error = context.getString(failure, describe(problem))
        }
        reload()
    }

    /** A known project problem in the person's language, otherwise what the system reported. */
    private fun describe(problem: Exception): String =
        (problem as? ProjectException)?.let { context.getString(it.reason.label) } ?: problem.message ?: context.getString(R.string.common_unknown_error)

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
        error = context.getString(R.string.projects_save_failed, describe(problem))
        false
    }

    // A take is never removed from a manifest, so a playable movie the manifest
    // does not list was finished just before its project could be saved.
    private fun indexFinishedTakes(project: Project) {
        for (file in disk.unindexedRecordings(project)) {
            val duration = mediaDuration(file) ?: continue
            // The marks and camera changes saved beside the take while it was recorded come back with it.
            val events = CaptureEventsFile.read(file, duration)
            addRecording(project.id, SourceMedia(filename = file.name, duration = duration, recordedAt = file.lastModified(), events = events))
        }
    }
}

/** Why a ZIP could not be opened as a project, in the person's language. */
val ProjectRestoreException.Reason.label: Int
    get() = when (this) {
        ProjectRestoreException.Reason.NotABackup -> R.string.unzip_not_a_backup
        ProjectRestoreException.Reason.Damaged -> R.string.unzip_damaged
        ProjectRestoreException.Reason.DamagedMedia -> R.string.unzip_damaged_media
        ProjectRestoreException.Reason.NotEnoughSpace -> R.string.unzip_no_space
        ProjectRestoreException.Reason.TooLarge -> R.string.unzip_too_large
    }
