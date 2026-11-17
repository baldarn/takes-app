package app.takes

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.takes.core.CaptureEffectsFile
import app.takes.core.CaptureEventsFile
import app.takes.core.CubeLUT
import app.takes.core.DeletionRules
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
import app.takes.core.ProjectSelection
import app.takes.core.RecoveredProject
import app.takes.core.RecoveryRules
import app.takes.core.SourceMedia
import app.takes.core.VideoEffectSettings
import app.takes.core.SubtitleScratch
import app.takes.core.TimelineClip
import app.takes.core.TimelineClipboard
import app.takes.core.TrackEditorEdits
import app.takes.core.VideoInsertionSite
import app.takes.core.TimelineEditing
import app.takes.core.VoiceRecording
import app.takes.core.WaveFile
import app.takes.core.PhotoClip
import app.takes.core.newID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class TakesApp : Application() {
    val store by lazy { ProjectStore(this) }
    val capture by lazy { CaptureController(this, store) }
    val exporter by lazy { Exporter(this, store) }

    /**
     * The tab on show, kept for as long as the process lives: the activity started again over a running process
     * (from the launcher, after Home during an export) comes back where the person was, as the original's single
     * window does. A new process opens on the recorder.
     */
    var tab = 0

    override fun onCreate() {
        super.onCreate()
        // The decoded sound of a transcription that was running when the app was last closed.
        thread(name = "subtitle-scratch") { runCatching { SubtitleScratch.clear(cacheDir) } }
    }
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

/** Thrown at the next step of a piece of work after the person tapped Cancel. */
private class Cancelled : RuntimeException()

/** No original recording could be read into a recovered project. */
private class NoPlayableMedia : RuntimeException()

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
    val disk = ProjectDisk(File(context.filesDir, "Projects"), ImportedFonts.library(context))

    var projects by mutableStateOf(emptyList<Project>()); private set
    var deleted by mutableStateOf(emptyList<DeletedProject>()); private set
    var unavailable by mutableStateOf(emptyMap<String, String>()); private set
    var selectedID by mutableStateOf(prefs.getString("selected", null)); private set

    /**
     * Since when a project's movie is as long as its longest item, sound past the last picture included
     * ([app.takes.core.MovieLength]): the first start of a version that plays that sound. A project made before keeps the
     * picture's length until the person has been shown the longer one and chosen ([adoptSoundLength]).
     */
    private val soundLengthSince = prefs.getLong("soundLengthSince", 0L).takeIf { it > 0 }
        ?: System.currentTimeMillis().also { prefs.edit().putLong("soundLengthSince", it).apply() }
    private var soundLengthAdopted by mutableStateOf(prefs.getStringSet("soundLengthAdopted", null).orEmpty().toSet())

    /** Whether [project]'s movie lasts until its sound ends ([app.takes.core.MovieLength.of]). */
    fun soundLength(project: Project): Boolean = project.createdAt >= soundLengthSince || project.id in soundLengthAdopted

    /** Marks [projectID] as shown the length its sound gives it, so its movie takes that length from now on. */
    fun adoptSoundLength(projectID: String) {
        if (projectID in soundLengthAdopted) return
        soundLengthAdopted = soundLengthAdopted + projectID
        prefs.edit().putStringSet("soundLengthAdopted", soundLengthAdopted).apply()
    }
    var error by mutableStateOf<String?>(null)
    /** What an import in progress is doing, for display. */
    var importing by mutableStateOf<String?>(null); private set
    /** Whether the work in progress offers Cancel: an import of videos, a duplicate, a ZIP made or opened. */
    var cancellable by mutableStateOf(false); private set
    /** Whether Cancel was tapped; the work stops at its next step and keeps nothing it made. */
    var cancelling by mutableStateOf(false); private set
    // Read by the work on its own thread, at each step.
    private val cancelRequested = AtomicBoolean(false)
    /**
     * What Copy or Cut last took in each project, by project id. As in the original, a project has its own
     * clipboard, kept while the app runs and never saved: a fragment names the recordings of its project.
     */
    val clipboards = mutableStateMapOf<String, TimelineClipboard>()

    // Counts the unfinished recordings moved aside or deleted, so that the screen lists them again. Before the first launch work, which uses it.
    private var recoveryChanges by mutableStateOf(0)

    val selected: Project? get() = projects.firstOrNull { it.id == selectedID }

    init {
        disk.root.mkdirs()
        disk.finishPendingDeletions()
        reload()
        projects.forEach(::indexFinishedTakes)
        // The project selected when the app was last used, else the most recent one, and a project to record into
        // when there is none, as the original starts (ProjectStore.swift:150-154, ContentView.swift:341).
        select(ProjectSelection.atLaunch(selectedID, projects.map { it.id }))
        if (ProjectSelection.createsAtLaunch(projects.map { it.id })) create()
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
        val previous = selectedID
        val project = projectID?.let { id -> projects.firstOrNull { it.id == id } } ?: create() ?: return
        begin(cancellable = true)
        scope.launch {
            var failed = 0
            // The videos join the project only once all are copied, so Cancel leaves it as it was.
            val copied = mutableListOf<SourceMedia>()
            try {
                uris.forEachIndexed { index, uri ->
                    importing = context.getString(R.string.projects_importing_progress, index + 1, uris.size)
                    val source = withContext(Dispatchers.IO) { copyIn(project.id, uri) }
                    if (source == null) failed++ else copied += source
                }
            } catch (_: Cancelled) {
                withContext(Dispatchers.IO) { copied.forEach { file(project, it).delete() } }
                // A project made for this import goes with it.
                if (projectID == null) discardNew(project, previous)
                end()
                return@launch
            }
            for (source in copied) if (!addRecording(project.id, source)) failed++
            end()
            if (failed > 0) {
                error = if (uris.size == 1) context.getString(R.string.projects_import_failed_one)
                else context.getString(R.string.projects_import_failed_some, failed, uris.size)
            }
        }
    }

    /**
     * Copies videos and photos picked from the device into the project and puts them into its timeline at second
     * [at], in the order picked, as one Undo step, as the original's editor adds them (VideoBatchImport.swift:21-72).
     * A photo becomes a movie of [PhotoClip.SECONDS] seconds. Nothing is added when one of them cannot be read.
     * With a [site], an add button of the timeline, they go there instead ([placeFootage]).
     * [done] is told what was added, to select, and where the first one begins.
     */
    fun insertMedia(projectID: String, uris: List<Uri>, at: Double, site: VideoInsertionSite? = null, done: (TrackEditorEdits.Made) -> Unit = {}) {
        if (uris.isEmpty() || importing != null) return
        scope.launch {
            val added = mutableListOf<SourceMedia>()
            var failed = false
            for ((index, uri) in uris.withIndex()) {
                importing = context.getString(R.string.projects_importing_progress, index + 1, uris.size)
                val source = if (resolver.getType(uri)?.startsWith("image/") == true) photoIn(projectID, uri) else withContext(Dispatchers.IO) { copyIn(projectID, uri) }
                if (source == null) { failed = true; break }
                added += source
            }
            importing = null
            val project = projects.firstOrNull { it.id == projectID }
            val placed = if (failed || project == null) null else placeFootage(added.fold(project) { it, source -> it.addSource(source) }, added, at, site)
            if (placed == null || !update(placed.first)) {
                project?.let { for (source in added) file(it, source).delete() }
                error = if (uris.size == 1) context.getString(R.string.projects_import_failed_one) else context.getString(R.string.add_media_failed)
            } else done(placed.second)
        }
    }

    /** A photo picked from the device, as a movie in the project's folder. */
    private suspend fun photoIn(projectID: String, uri: Uri): SourceMedia? {
        val folder = disk.folder(projectID).apply { mkdirs() }
        val id = newID()
        val file = File(folder, "photo-${System.currentTimeMillis()}-$id.mp4")
        val partial = File(folder, ".${file.name}.tmp.mp4")
        return try {
            if (!PhotoMovie.write(context, uri, partial)) return null
            val duration = withContext(Dispatchers.IO) { mediaDuration(partial) } ?: return null
            if (!partial.renameTo(file)) return null
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            SourceMedia(id = id, filename = file.name, duration = duration, originalName = name?.take(255))
        } finally {
            partial.delete()
        }
    }

    /**
     * Copies a sound file picked from the device into the project and places
     * it on an audio track at timeline second [at], as one undoable edit.
     */
    fun importAudio(projectID: String, uri: Uri, at: Double) {
        if (importing != null) return
        begin(cancellable = false)
        scope.launch {
            importing = context.getString(R.string.sound_importing)
            val source = withContext(Dispatchers.IO) { copyInSound(projectID, uri) }
            end()
            val project = projects.firstOrNull { it.id == projectID }
            if (source == null || project == null) {
                error = context.getString(R.string.sound_import_failed)
                return@launch
            }
            placeSound(project, source, at)?.let { error = it }
        }
    }

    /**
     * Adds the voice recording [recording], a WAV file of the editor's
     * recorder, to the project and places it on an audio track at timeline
     * second [at], as one undoable edit, as an imported sound is added
     * (YouRec/VoiceRecorder.swift:404-406, YouRec/ProjectStore.swift:458-483).
     * The file is copied, so the recorder can throw its takes away. [name] is
     * the name the sound is shown with. Returns what went wrong, or null.
     */
    suspend fun insertRecording(projectID: String, recording: File, name: String, at: Double): String? {
        if (importing != null) return context.getString(R.string.voice_add_failed)
        val source = withContext(Dispatchers.IO) {
            val folder = disk.folder(projectID).apply { mkdirs() }
            val id = newID()
            val file = File(folder, "audio-${System.currentTimeMillis()}-$id.wav")
            val partial = File(folder, ".${file.name}.tmp")
            try {
                recording.copyTo(partial, overwrite = true)
                val bytes = WaveFile.dataBytes(partial) ?: return@withContext null
                val duration = VoiceRecording.seconds(bytes / VoiceRecording.BYTES_PER_FRAME)
                if (!partial.renameTo(file)) return@withContext null
                SourceMedia(id = id, filename = file.name, duration = duration, audioOnly = true, originalName = name.take(255))
            } catch (_: Exception) {
                null
            } finally {
                partial.delete()
            }
        }
        val project = projects.firstOrNull { it.id == projectID }
        if (source == null || project == null) return context.getString(R.string.voice_add_failed)
        return placeSound(project, source, VoiceRecording.position(at, project.edit.duration))
    }

    /** Puts the sound file [source], already in the project's folder, on an audio track at [at] in one step; what went wrong, or null. */
    private fun placeSound(project: Project, source: SourceMedia, at: Double): String? {
        val added = project.addSource(source)
        val edit = TimelineEditing.addAudio(added.edit, source.id, source.duration, at)
        val problem = when {
            edit == null -> context.getString(R.string.sound_tracks_busy)
            !update(added.commit(edit)) -> error.also { error = null }
            else -> return null
        }
        file(project, source).delete()
        return problem
    }

    /**
     * Writes the project as a ZIP to a document the user chose: its recordings
     * and sounds, its editing history, and the current edit as an
     * OpenTimelineIO file that other editors can open.
     */
    fun archive(project: Project, destination: Uri) {
        if (importing != null) return
        begin(cancellable = true)
        scope.launch {
            importing = context.getString(R.string.projects_zipping)
            var stopped = false
            val problem = withContext(Dispatchers.IO) {
                try {
                    var shown = -1
                    // As the original, the margin and the media must fit above the floor before anything is written, and the
                    // floor is checked again every 16 MB (ProjectArchive.swift:80-91, 232-240). The person's document is
                    // measured on the phone's storage, where the providers on it keep what they are given.
                    shortOfSpace(ProjectArchive.requiredSpace(disk.folder(project.id), project))?.let { throw IOException(it) }
                    var nextCheck = RecoveryRules.CHECK_INTERVAL
                    resolver.openOutputStream(destination)!!.use { stream ->
                        // write() flushes; use() closes the document.
                        ProjectArchive.write(disk.folder(project.id), project, stream.buffered(1 shl 16)) { written, total ->
                            stopIfCancelled()
                            if (written >= nextCheck) {
                                shortOfSpace(0)?.let { throw IOException(it) }
                                nextCheck = written + RecoveryRules.CHECK_INTERVAL
                            }
                            val percent = if (total > 0) (written * 100 / total).toInt() else 100
                            if (percent != shown) { shown = percent; importing = context.getString(R.string.projects_zipping_percent, percent) }
                        }
                    }
                    null
                } catch (failure: Exception) {
                    // A partial archive would look like a backup and not be one.
                    runCatching { DocumentsContract.deleteDocument(resolver, destination) }
                    stopped = failure is Cancelled
                    describe(failure)
                }
            }
            end()
            if (stopped) return@launch
            error = if (problem == null) context.getString(R.string.projects_zip_saved) else context.getString(R.string.projects_zip_failed, problem)
        }
    }

    /**
     * Copies a .cube file picked from the device into the project, named by
     * its content so the same LUT is stored once. Null, with a message for
     * the person, when the file is not a usable LUT.
     */
    /**
     * A picture picked for a video effect's background, kept in the project
     * folder under a name made from its contents: its file name, or null when
     * it is not a picture that can be read.
     */
    suspend fun importEffectPicture(projectID: String, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            val extension = when (bounds.outMimeType) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/heif", "image/heic" -> "heic"
                else -> "jpg"
            }
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val folder = disk.folder(projectID).apply { mkdirs() }
            val file = File(folder, "effect-picture-${hash.take(32)}.$extension")
            if (!file.isFile) ProjectDisk.writeAtomically(bytes, file)
            file.name
        } catch (_: Exception) {
            null
        }
    }

    /**
     * A picture picked for the Image tool, upright, its longer side brought down to 4096 pixels, written as a PNG in
     * the project's folder under a new name, as the original imports it (YouRec/EditorTools.swift:524-537): its file
     * name, or null when it is not a picture that can be read.
     */
    suspend fun importImage(projectID: String, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val picture = PhotoMovie.decode(resolver, uri, app.takes.core.ImageOverlay.MAXIMUM_PIXELS) ?: return@withContext null
            val folder = disk.folder(projectID).apply { mkdirs() }
            val file = File(folder, app.takes.core.ImageOverlay.importedName())
            val bytes = java.io.ByteArrayOutputStream().also { picture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            picture.recycle()
            ProjectDisk.writeAtomically(bytes, file)
            file.name
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Deletes the pictures imported while the Image tool was open that no step of the project names, when it closes
     * (YouRec/EditorTools.swift:539-542).
     */
    fun discardImages(projectID: String, names: Collection<String>) {
        val kept = projects.firstOrNull { it.id == projectID }?.imageFiles?.toSet() ?: return
        val folder = disk.folder(projectID)
        for (name in names) if (name !in kept && name.startsWith("image-") && name == File(name).name) File(folder, name).delete()
    }

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
            val cube = CubeLUT.parse(bytes) // rejects anything that is not a valid, bounded cube
            // Named by the cube's TITLE when it has one, else by its file, as the original (YouRec/ColorGrading.swift:126).
            val name = cube.title?.ifBlank { null } ?: resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
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
            resolver.openInputStream(uri)?.use { input -> partial.outputStream().use { copyStopping(input, it) } } ?: return null
            val duration = mediaDuration(partial) ?: return null
            if (displaySize(partial) == null || !partial.renameTo(file)) return null
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            SourceMedia(id = id, filename = file.name, duration = duration, originalName = name?.take(255))
        } catch (cancelled: Cancelled) {
            file.delete()
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            partial.delete()
        }
    }

    /** Copies [input] to [output], stopping at the next read once Cancel is tapped. */
    private fun copyStopping(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(1 shl 16)
        while (true) {
            stopIfCancelled()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
    }

    private fun begin(cancellable: Boolean) {
        cancelRequested.set(false)
        cancelling = false
        this.cancellable = cancellable
    }

    private fun end() {
        importing = null
        cancellable = false
        cancelling = false
    }

    /** Stops the import, duplicate or ZIP in progress, as the original's Cancel. Nothing it made is kept. */
    fun cancelOperation() {
        if (importing == null || !cancellable) return
        cancelRequested.set(true)
        cancelling = true
    }

    private fun stopIfCancelled() {
        if (cancelRequested.get()) throw Cancelled()
    }

    /** Removes a project made a moment ago for work that was cancelled, and selects again what was selected. */
    private suspend fun discardNew(project: Project, previous: String?) {
        withContext(Dispatchers.IO) { disk.folder(project.id).deleteRecursively() }
        projects = projects.filter { it.id != project.id }
        select(previous?.takeIf { id -> projects.any { it.id == id } })
    }

    /** [gainDB]: the recorder's gain for the take, given to its clip as its level; [videoEffects]: its Crash Zooms, given to its clip. */
    fun addRecording(projectID: String, source: SourceMedia, gainDB: Double = 0.0, videoEffects: List<VideoEffectSettings>? = null): Boolean {
        val project = projects.firstOrNull { it.id == projectID } ?: return false
        return update(project.addRecording(source, gainDB = gainDB, videoEffects = videoEffects))
    }

    fun commit(projectID: String, state: EditState) = change(projectID) { it.commit(state) }

    /** [state] in a movie frame of [outputSize], fixed by the Crop tool; null gives it the first picture's shape again. */
    fun commit(projectID: String, state: EditState, outputSize: app.takes.core.Size?) = change(projectID) { it.commit(state, outputSize = outputSize) }

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
    fun addUnused(projectID: String, takes: List<SourceMedia>, at: Double, site: VideoInsertionSite? = null): TrackEditorEdits.Made? {
        val project = projects.firstOrNull { it.id == projectID } ?: return null
        val added = placeFootage(project, takes, at, site) ?: return null
        return if (update(added.first)) added.second else null
    }

    /**
     * [takes] of [project], whole, put into its edit as new footage, as the original's editor adds them
     * (TimelineEditing.swift:86-127): at second [at] with everything after moved later, or at [site],
     * using the free time there first. One Undo step; the clips are new ones, selected afterwards.
     */
    private fun placeFootage(project: Project, takes: List<SourceMedia>, at: Double, site: VideoInsertionSite?): Pair<Project, TrackEditorEdits.Made>? {
        val footage = project.recordings.associateBy { it.id }
        if (takes.isEmpty() || takes.any { footage[it.id] == null }) return null
        val clips = takes.map { TimelineClip(sourceID = it.id, start = 0.0, end = footage.getValue(it.id).duration) }
        val made = TrackEditorEdits.insertingFootage(project.edit, clips, at, site) ?: return null
        return project.commit(made.edit) to made
    }

    /**
     * Puts seconds [start] to [end] of the take [source] back at the end of the timeline, as one
     * Undo step, as the original's "Add range to end of timeline". False when the range is not
     * one of the take.
     */
    fun addOriginal(projectID: String, source: SourceMedia, start: Double, end: Double): Boolean {
        val project = projects.firstOrNull { it.id == projectID } ?: return false
        val take = project.recordings.firstOrNull { it.id == source.id }
        if (take == null || !file(project, take).isFile || start < 0 || end - start < TimelineEditing.MINIMUM_CLIP / 2 || end > take.duration + 0.001) return false
        val clip = TimelineClip(sourceID = take.id, start = start, end = end.coerceAtMost(take.duration))
        val edit = TimelineEditing.insert(project.edit, listOf(clip), project.edit.duration) ?: return false
        return update(project.commit(edit))
    }

    /**
     * The original recordings of [project] as the original lists them (YouRec/RecordingSalvage.swift:13-46):
     * its sources, the media of its folder it does not list, and what was set aside in its Recovery folder.
     */
    fun originals(project: Project): List<RecoveredProject.Original> = originals(project.id, project)

    /**
     * The original recordings of the project in folder [id], [project] when it can be read; of one that cannot,
     * the media of its folder and of its Recovery folder (YouRec/ContentView.swift:680-687).
     */
    fun originals(id: String, project: Project?): List<RecoveredProject.Original> {
        recoveryChanges
        return RecoveredProject.originals(project, disk.folder(id), disk.recoveryFiles(id))
    }

    /**
     * Tries again to open the projects that could not be read, as the original's "Retry opening project"
     * (YouRec/ProjectStore.swift:169-179). Whether the project in folder [id] opens now.
     */
    fun retryUnavailable(id: String): Boolean {
        reload()
        return projects.any { it.id == id }
    }

    /**
     * Why [bytes] cannot be written into the library keeping the original's floor
     * (YouRec/ProjectRecovery.swift:452-464), in the person's language; null when they can.
     */
    private fun shortOfSpace(bytes: Long): String? {
        disk.root.mkdirs()
        val more = RecoveryRules.spaceToFree(bytes, disk.root.usableSpace, disk.root.totalSpace) ?: return null
        return context.getString(R.string.originals_free_space, Formatter.formatShortFileSize(context, more))
    }

    /** Room a copy of [files] may take ([RecoveredProject.estimate]). */
    fun recoveryEstimate(files: List<File>): Long = RecoveredProject.estimate(files.map { it.length() })

    /**
     * Makes a separate project from the original recordings of [project], or from [only] of them, as the
     * original's "Create recovered project" and "Recover this recording into a new project"
     * (YouRec/ProjectStore.swift:747-805). What reads whole is copied as it is; what does not is salvaged
     * as far as it can be read ([RecordingSalvage.repair]); what cannot be read at all stays where it is.
     * [project] is not changed. [done] gets what happened, in the person's language.
     */
    fun recover(project: Project, only: File? = null, done: (String) -> Unit) = recover(project.id, project, only, done)

    /** [recover] for the project in folder [id], whose [project] is null when it cannot be read. */
    fun recover(id: String, project: Project?, only: File? = null, done: (String) -> Unit) {
        if (importing != null) return
        begin(cancellable = true)
        importing = context.getString(R.string.originals_copying)
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { recovered(id, project, only) } }
            end()
            reload()
            done(result.fold(::report) { failure ->
                when (failure) {
                    is Cancelled -> context.getString(R.string.originals_copy_cancelled)
                    is NoPlayableMedia -> context.getString(R.string.originals_no_media)
                    is Exception -> describe(failure)
                    else -> throw failure
                }
            })
        }
    }

    private fun report(outcome: RecoveredProject.Outcome): String {
        val words = context.resources
        return listOfNotNull(
            words.getQuantityString(R.plurals.originals_recovered, outcome.recordings, outcome.title, outcome.recordings),
            if (outcome.partial > 0) words.getQuantityString(R.plurals.originals_recovered_partial, outcome.partial, outcome.partial) else null,
            if (outcome.skipped > 0) words.getQuantityString(R.plurals.originals_recovered_skipped, outcome.skipped, outcome.skipped) else null,
        ).joinToString(" ")
    }

    private fun recovered(id: String, project: Project?, only: File?): RecoveredProject.Outcome {
        val folder = disk.folder(id)
        // The original lists what is on the disk, so a source whose file is missing is not one to copy.
        val items = RecoveredProject.chosen(originals(id, project), folder, only).filter { it.file.isFile }
        if (items.isEmpty()) throw NoPlayableMedia()
        // Put together beside the projects, hidden from the library, and moved in only once it is saved.
        val staging = File(disk.root, ".recovery-" + id)
        staging.deleteRecursively()
        shortOfSpace(RecoveredProject.estimate(items.map { it.file.length() }))?.let { throw IOException(it) }
        val title = RecoveredProject.title(project?.title, context.getString(R.string.originals_recovered_suffix), context.getString(R.string.originals_recovered_fallback))
        var recovered = Project(title)
        val staged = ProjectDisk(staging)
        val directory = staged.folder(recovered.id)
        if (!directory.mkdirs()) throw IOException("Cannot create ${directory.path}")
        try {
            var skipped = 0
            var partial = 0
            for ((index, item) in items.withIndex()) {
                stopIfCancelled()
                val id = newID()
                val complete = try { RecordingSalvage.inspect(item.file) } catch (_: RecordingSalvage.Unreadable) { null }
                val copy: File
                val media = if (complete != null) {
                    copy = File(directory, "$id.${item.file.extension.lowercase()}")
                    item.file.inputStream().use { input -> copy.outputStream().use { output -> copyStopping(input, output); output.fd.sync() } }
                    complete
                } else {
                    // The muxer writes MPEG-4, where the original's writer makes a QuickTime movie.
                    copy = File(directory, "$id.mp4")
                    try {
                        RecordingSalvage.repair(item.file, copy, ::stopIfCancelled, ::shortOfSpace).also { partial++ }
                    } catch (_: RecordingSalvage.Unreadable) {
                        skipped++
                        continue
                    }
                }
                recovered = RecoveredProject.adding(recovered, RecoveredProject.source(item, id, copy.name, media.duration, media.audioOnly))
                importing = context.getString(R.string.originals_copying_percent, (index + 1) * 100 / items.size)
            }
            if (recovered.sources.isEmpty()) throw NoPlayableMedia()
            staged.save(recovered)
            staged.read(directory)
            Files.move(directory.toPath(), disk.folder(recovered.id).toPath(), StandardCopyOption.ATOMIC_MOVE)
            return RecoveredProject.Outcome(recovered.title, recovered.sources.size, partial, skipped)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** The originals of [project] whose file is no longer in its folder, as the original's `missingSources`. */
    fun missingSources(project: Project): List<SourceMedia> = project.sources.filter { !file(project, it).exists() }

    /**
     * Takes [project]'s pictures and sounds of the missing originals off the timeline, as one undoable edit, as the
     * original's "Remove missing takes from timeline" (ContentView.swift:457-466). False when nothing was removed.
     */
    fun removeMissing(project: Project): Boolean {
        val current = projects.firstOrNull { it.id == project.id } ?: return false
        val missing = missingSources(current).mapTo(HashSet()) { it.id }
        val edit = TrackEditorEdits.removingSources(current.edit, missing) ?: return false
        return update(current.commit(edit))
    }

    /**
     * Puts the file at [uri] back in place of the missing original [source] of [project], as the original's
     * `beginRelink` (ProjectStore.swift:666-703): the copy must last as long as the take, within 0.05 s, hold
     * its kind of track and, when the take has a fingerprint, have the same SHA-256. A take without one is
     * relinked only when [allowUnverified]; it keeps the new file's fingerprint from then on.
     */
    fun relink(project: Project, source: SourceMedia, uri: Uri, allowUnverified: Boolean = false) {
        if (importing != null) return
        begin(cancellable = true)
        importing = context.getString(R.string.repair_checking)
        scope.launch {
            val problem = withContext(Dispatchers.IO) {
                val destination = file(project, source)
                val temporary = File(disk.folder(project.id).apply { mkdirs() }, ".relink-${newID()}.tmp")
                try {
                    if (source.sha256 == null && !allowUnverified) return@withContext context.getString(R.string.repair_unverified)
                    if (destination.exists()) return@withContext context.getString(R.string.repair_exists)
                    resolver.openInputStream(uri)?.use { input -> temporary.outputStream().use { copyStopping(input, it) } }
                        ?: return@withContext context.getString(R.string.repair_unreadable)
                    val duration = mediaDuration(temporary)
                    val kind = if (source.audioOnly == true) hasAudio(temporary) else displaySize(temporary) != null
                    if (duration == null || kind.not() || kotlin.math.abs(duration - source.duration) >= 0.05) return@withContext context.getString(R.string.repair_unreadable)
                    val hash = fingerprint(temporary)
                    if (source.sha256 != null && hash != source.sha256) return@withContext context.getString(R.string.repair_checksum)
                    stopIfCancelled()
                    if (!temporary.renameTo(destination)) return@withContext context.getString(R.string.repair_unreadable)
                    withContext(Dispatchers.Main) {
                        projects.firstOrNull { it.id == project.id }?.let { value ->
                            update(value.copy(sources = value.sources.map { if (it.id == source.id) it.copy(sha256 = hash) else it }, updatedAt = System.currentTimeMillis()))
                        }
                    }
                    null
                } catch (_: Cancelled) {
                    null
                } catch (problem: Exception) {
                    describe(problem)
                } finally {
                    temporary.delete()
                }
            }
            end()
            problem?.let { error = it }
        }
    }

    private fun fingerprint(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                stopIfCancelled()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Copies originals of [project] to the device's Movies and Music collections. The files themselves stay. */
    fun saveOriginals(project: Project, sources: List<SourceMedia>) = saveOriginals(sources.map { file(project, it) to (it.audioOnly == true) })

    /** Copies [files], each with whether it is sound only, to the device's Movies and Music collections. */
    fun saveOriginals(files: List<Pair<File, Boolean>>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        scope.launch {
            val failed = withContext(Dispatchers.IO) { files.count { (file, audioOnly) -> !saveToCollection(file, audioOnly) } }
            error = when {
                failed == 0 -> context.resources.getQuantityString(R.plurals.originals_saved, files.size, files.size)
                else -> context.getString(R.string.originals_save_failed, failed, files.size)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToCollection(file: File, audioOnly: Boolean): Boolean {
        if (!file.isFile) return false
        val collection = if (audioOnly) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mediaType(file, audioOnly))
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (audioOnly) "Music/Takes" else "Movies/Takes")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return false
        return try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 16) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            true
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            false
        }
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
        // The most recent project left is selected in its place, as in the original (ProjectStore.swift:269).
        select(ProjectSelection.afterRemoving(selectedID, project.id, projects.map { it.id }))
    }

    /**
     * Brings back a project from a ZIP made by "Zip project". It always
     * arrives as a new project, so nothing already in the library is replaced.
     */
    fun openArchive(source: Uri) {
        if (importing != null) return
        begin(cancellable = true)
        scope.launch {
            importing = context.getString(R.string.projects_unzipping)
            var stopped = false
            val problem = withContext(Dispatchers.IO) {
                try {
                    val size = resolver.query(source, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { row ->
                        if (row.moveToFirst() && !row.isNull(0)) row.getLong(0) else 0L
                    } ?: 0L
                    disk.root.mkdirs()
                    // What is written keeps the original's floor free, so the phone is not filled to the brim (ProjectArchive.swift:80-91, 135).
                    val room = RecoveryRules.room(disk.root.usableSpace, disk.root.totalSpace)
                    var shown = -1
                    resolver.openInputStream(source)!!.use { stream ->
                        ProjectRestore.restore(stream, disk.root, room) { read ->
                            stopIfCancelled()
                            val percent = if (size > 0) (read * 100 / size).toInt().coerceIn(0, 100) else -1
                            if (percent != shown && percent >= 0) { shown = percent; importing = context.getString(R.string.projects_unzipping_percent, percent) }
                        }
                    }
                    null
                } catch (failure: Exception) {
                    stopped = failure is Cancelled
                    (failure as? ProjectRestoreException)?.let { context.getString(it.reason.label) } ?: describe(failure)
                }
            }
            end()
            reload()
            if (stopped) return@launch
            error = if (problem == null) context.getString(R.string.projects_unzip_done) else context.getString(R.string.projects_unzip_failed, problem)
        }
    }

    /** Makes an independent copy of the project, with its recordings and editing history. */
    fun duplicate(project: Project) {
        if (importing != null) return
        begin(cancellable = true)
        scope.launch {
            importing = context.getString(R.string.projects_duplicating)
            val title = ProjectDuplicate.title(project.title, projects.map { it.title }, context.getString(R.string.projects_copy_word))
            val result = withContext(Dispatchers.IO) {
                try {
                    // The copies keep the original's floor free (ProjectArchive.swift:191, 135).
                    disk.root.mkdirs()
                    val room = RecoveryRules.room(disk.root.usableSpace, disk.root.totalSpace)
                    var shown = -1
                    Result.success(ProjectDuplicate.duplicate(project, disk.root, title, room) { copied, total ->
                        stopIfCancelled()
                        val percent = if (total > 0) (copied * 100 / total).toInt() else 100
                        if (percent != shown) { shown = percent; importing = context.getString(R.string.projects_duplicating_percent, percent) }
                    })
                } catch (failure: Exception) {
                    Result.failure(failure)
                }
            }
            end()
            reload()
            result.onSuccess { select(it.id) }.onFailure { failure ->
                if (failure is Cancelled) return@onFailure
                val problem = if (failure is ProjectDuplicateException) context.getString(R.string.unzip_no_space) else describe(failure as Exception)
                error = context.getString(R.string.projects_duplicate_failed, problem)
            }
        }
    }

    /** Brings [project] back from Recently deleted and selects it, as the original (ProjectStore.swift:273-279). */
    fun restore(project: Project) = attempt(R.string.projects_restore_failed) {
        disk.restoreDeleted(project.id)
        select(project.id)
    }

    fun erase(project: Project) = attempt(R.string.projects_erase_failed) { disk.eraseDeleted(project.id) }

    /**
     * The projects of Recently Deleted that have been there more than 30 days, as the original's
     * `expiredDeletions` (ProjectStore.swift:301-304). One without a deletion date never expires.
     */
    fun expiredDeletions(now: Long = System.currentTimeMillis()): List<DeletedProject> =
        deleted.filter { entry -> entry.date?.let { DeletionRules.isOlderThan30Days(it, now) } == true }

    /** Permanently deletes [confirmed] that are still in Recently Deleted and still expired, as `eraseExpiredDeletions`. */
    fun eraseExpired(confirmed: List<DeletedProject>, now: Long = System.currentTimeMillis()) {
        if (importing != null) return
        var failure: String? = null
        for (entry in confirmed) {
            val date = disk.deletionRecord(entry.project.id)?.date ?: continue
            if (!DeletionRules.isOlderThan30Days(date, now)) continue
            try { disk.eraseDeleted(entry.project.id) } catch (problem: Exception) { if (failure == null) failure = describe(problem) }
        }
        failure?.let { error = context.getString(R.string.projects_expired_failed, it) }
        reload()
    }

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
        // In the original's order: a known deletion date first, the latest first (DeletionRules.ordered).
        deleted = DeletionRules.ordered(disk.loadAll(disk.deletedRoot).projects.map { DeletedProject(it, disk.deletionRecord(it.id)?.date) }) {
            DeletionRules.Entry(it.project.id, it.date, it.date != null)
        }
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
            val duration = mediaDuration(file)
            if (duration == null) { setAside(file, project); continue }
            // The marks and camera changes saved beside the take while it was recorded come back with it.
            val events = CaptureEventsFile.read(file, duration)
            // And its Crash Zooms, saved beside it as they were made (YouRec/ProjectStore.swift:360-376).
            val effects = CaptureEffectsFile.read(file, duration)
            addRecording(project.id, SourceMedia(filename = file.name, duration = duration, recordedAt = file.lastModified(), events = events), videoEffects = effects)
        }
    }

    // MARK: Unfinished recordings. What could not be read is kept in the project's Recovery folder, as in the original.

    /** The recordings of [project] that could not be brought back, for the person to save or delete. */
    fun unfinishedRecordings(project: Project): List<File> {
        recoveryChanges
        return disk.recoveryFiles(project)
    }

    /**
     * A recording left by a take that was interrupted and that cannot be read. An empty file holds nothing
     * and goes, as when a take ends with one; any other is moved aside with its events, never deleted, and
     * the person is told once, in the original's words.
     */
    private fun setAside(file: File, project: Project) {
        if (file.length() == 0L) {
            if (file.delete()) { CaptureEventsFile.delete(file); CaptureEffectsFile.delete(file) }
            return
        }
        // A file that cannot be moved stays where it is and is tried again at the next launch.
        if (runCatching { disk.quarantineRecording(file, project) }.isSuccess) {
            recoveryChanges++
            error = context.getString(R.string.recorder_unfinished_notice)
        }
    }

    /** Deletes an unfinished recording for good, with what was saved beside it. */
    fun removeUnfinished(file: File, project: Project) {
        try {
            disk.removeRecoveryFile(file, project)
        } catch (problem: Exception) {
            error = describe(problem)
        }
        recoveryChanges++
    }

    /** Saves a copy of an unfinished recording where the person chose. The file itself stays. */
    fun saveUnfinished(file: File, destination: Uri) {
        scope.launch {
            val problem = withContext(Dispatchers.IO) {
                try {
                    resolver.openOutputStream(destination)!!.use { stream -> file.inputStream().use { it.copyTo(stream, 1 shl 16) } }
                    null
                } catch (failure: Exception) {
                    failure
                }
            }
            if (problem != null) error = context.getString(R.string.recorder_unfinished_save_failed, describe(problem))
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
