package app.takes

import android.app.Application
import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.takes.core.EditState
import app.takes.core.Project
import app.takes.core.ProjectDisk
import app.takes.core.SourceMedia
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
    val disk = ProjectDisk(File(context.filesDir, "Projects"))

    var projects by mutableStateOf(emptyList<Project>()); private set
    var deleted by mutableStateOf(emptyList<DeletedProject>()); private set
    var unavailable by mutableStateOf(emptyMap<String, String>()); private set
    var selectedID by mutableStateOf(prefs.getString("selected", null)); private set
    var error by mutableStateOf<String?>(null)

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

    fun addRecording(projectID: String, source: SourceMedia): Boolean {
        val project = projects.firstOrNull { it.id == projectID } ?: return false
        return update(project.addRecording(source))
    }

    fun commit(projectID: String, state: EditState) = change(projectID) { it.commit(state) }

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
