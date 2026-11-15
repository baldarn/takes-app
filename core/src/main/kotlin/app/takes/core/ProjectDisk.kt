package app.takes.core

import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable
data class ProjectDeletion(val id: String, val date: Long)

/**
 * App-owned project directories. Each holds `project.json`, an immutable
 * content-addressed `History/`, the recordings, and recent manifest revisions.
 */
class ProjectDisk(val root: File) {
    data class Library(val projects: List<Project>, val unavailable: Map<String, String>)

    fun folder(id: String): File = File(root, id)

    // Fixture stores must never share the user's Recently Deleted directory.
    val deletedRoot: File
        get() = File(root.parentFile, if (root.name == "Projects") "RecentlyDeleted" else root.name + "-RecentlyDeleted")

    fun save(project: Project) = synchronized(writeLock) {
        project.validate()
        val directory = folder(project.id)
        val history = File(directory, "History")
        if (!history.isDirectory && !history.mkdirs()) throw java.io.IOException("Cannot create ${history.path}")
        val head = File(directory, "project.json")
        val previous = if (head.isFile) head.readBytes() else null
        val data = project.manifest { name, bytes ->
            val file = File(history, name)
            if (!file.isFile || sha256(file.readBytes()) + ".json" != name) writeAtomically(bytes, file)
        }
        if (previous != null && !data.contentEquals(previous)) keepRevision(previous, directory)
        if (previous == null || !data.contentEquals(previous)) writeAtomically(data, head)
    }

    fun read(directory: File): Project {
        val history = File(directory, "History")
        val load = { name: String -> File(history, name).readBytes() }
        val head = File(directory, "project.json")
        val primary = runCatching { Project.readManifest(head.readBytes(), load) }
        primary.getOrNull()?.let { return it }
        // A head saved by a newer version is not damage. Opening an older revision in its
        // place would let the next saves push the newer work out of the kept revisions.
        (primary.exceptionOrNull() as? ProjectException)?.takeIf { it.reason == ProjectException.Reason.NewerVersion }?.let { throw it }
        // A damaged head must not hide the project: fall back to the newest
        // readable revision and leave every file in place.
        for (revision in revisions(directory)) {
            runCatching { Project.readManifest(revision.readBytes(), load) }.getOrNull()?.let { return it }
        }
        throw primary.exceptionOrNull()!!
    }

    fun loadAll(from: File = root): Library {
        val projects = mutableListOf<Project>()
        val unavailable = mutableMapOf<String, String>()
        for (directory in from.listFiles().orEmpty().sortedBy { it.name }) {
            if (!directory.isDirectory || directory.name.startsWith(".")) continue
            try {
                val project = read(directory)
                if (project.id == directory.name) projects += project
                else unavailable[directory.name] = ProjectException.Reason.InvalidDocument.message
            } catch (error: Exception) {
                unavailable[directory.name] = error.message ?: ProjectException.Reason.InvalidDocument.message
            }
        }
        return Library(projects.sortedByDescending { it.updatedAt }, unavailable)
    }

    /** Finished recordings in the project directory that no manifest refers to. */
    fun unindexedRecordings(project: Project): List<File> {
        val known = project.mediaFiles.map { it.lowercase() }.toSet()
        return folder(project.id).listFiles().orEmpty().filter { file ->
            val name = file.name.lowercase()
            file.extension.lowercase() in setOf("mp4", "mov") && !name.startsWith(".") && name !in known &&
                Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        }.sortedBy { it.name }
    }

    /**
     * The recordings of [project] that could not be read and were moved aside, as the original keeps
     * them (YouRec/Project.swift:726-731): the movies in the folders of `Recovery/`, in order of path.
     */
    fun recoveryFiles(project: Project): List<File> =
        File(folder(project.id), RECOVERY).listFiles().orEmpty().flatMap { it.listFiles().orEmpty().toList() }
            .filter { it.extension.lowercase() in setOf("mp4", "mov") && Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
            .sortedBy { it.path }

    /**
     * Moves a recording of [project] that no manifest refers to into a folder of its own under
     * `Recovery/`, with the files saved beside it, such as its events (YouRec/Project.swift:732-752).
     * Nothing is deleted: the companions are copied first and removed only once the movie has moved.
     * A file that is not one of [unindexedRecordings] is left alone.
     */
    fun quarantineRecording(requested: File, project: Project) {
        val file = unindexedRecordings(project).firstOrNull { it.canonicalFile == requested.canonicalFile } ?: return
        val destination = File(File(folder(project.id), RECOVERY), sha256(file.name.toByteArray()))
        if (!destination.isDirectory && !destination.mkdirs()) throw java.io.IOException("Cannot create ${destination.path}")
        val stem = file.nameWithoutExtension + "."
        val known = project.mediaFiles.toSet()
        val companions = folder(project.id).listFiles().orEmpty().filter {
            it.name != file.name && it.name.startsWith(stem) && it.name !in known && Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS)
        }
        for (companion in companions) {
            val target = File(destination, companion.name)
            if (!target.exists()) Files.copy(companion.toPath(), target.toPath())
        }
        Files.move(file.toPath(), File(destination, file.name).toPath())
        companions.forEach { it.delete() }
    }

    /** Deletes one of [recoveryFiles] for good, with its folder and what was kept beside it (YouRec/ProjectStore.swift:568-572). */
    fun removeRecoveryFile(file: File, project: Project) {
        if (recoveryFiles(project).none { it == file }) return
        val directory = file.parentFile ?: return
        directory.listFiles().orEmpty().forEach { if (!it.delete()) throw java.io.IOException("Cannot delete ${it.path}") }
        if (!directory.delete()) throw java.io.IOException("Cannot delete ${directory.path}")
    }

    fun moveToDeleted(project: Project, now: Long = System.currentTimeMillis()) {
        deletedRoot.mkdirs()
        // A failed receipt write leaves the active project in place. The manifest,
        // originals and immutable edit history are not rewritten for deletion.
        val receipt = documentJson.encodeToString(ProjectDeletion.serializer(), ProjectDeletion(project.id, now))
        writeAtomically(receipt.toByteArray(), deletionFile(folder(project.id)))
        Files.move(folder(project.id).toPath(), File(deletedRoot, project.id).toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    fun restoreDeleted(id: String) {
        root.mkdirs()
        Files.move(File(deletedRoot, id).toPath(), folder(id).toPath(), StandardCopyOption.ATOMIC_MOVE)
        deletionFile(folder(id)).delete()
    }

    fun deletionRecord(id: String): ProjectDeletion? = runCatching {
        documentJson.decodeFromString(ProjectDeletion.serializer(), deletionFile(File(deletedRoot, id)).readText())
    }.getOrNull()?.takeIf { it.id == id }

    fun eraseDeleted(id: String) {
        val pending = File(deletedRoot, ".purging-" + UUID.randomUUID())
        // This rename is the permanent-deletion commit. Only confirmed removals
        // enter this queue; a crash after any child unlink can safely resume.
        Files.move(File(deletedRoot, id).toPath(), pending.toPath(), StandardCopyOption.ATOMIC_MOVE)
        pending.deleteRecursively()
    }

    fun finishPendingDeletions() {
        for (directory in deletedRoot.listFiles().orEmpty()) {
            if (directory.name.startsWith(".purging-") && Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                directory.deleteRecursively()
            }
        }
    }

    private fun deletionFile(directory: File) = File(directory, ".deletion.json")

    private fun revisions(directory: File): List<File> =
        File(directory, ".revisions").listFiles().orEmpty().filter { it.extension == "json" }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })

    private fun keepRevision(manifest: ByteArray, directory: File) {
        val folder = File(directory, ".revisions")
        folder.mkdirs()
        writeAtomically(manifest, File(folder, "%020d-%s.json".format(System.currentTimeMillis(), sha256(manifest).take(16))))
        revisions(directory).drop(KEPT_REVISIONS).forEach { it.delete() }
    }

    companion object {
        private const val KEPT_REVISIONS = 5
        /** The folder of a project that holds what could not be read, as in the original. */
        const val RECOVERY = "Recovery"
        private val writeLock = Any()

        /** The destination either keeps its old bytes or holds all the new ones. */
        fun writeAtomically(data: ByteArray, destination: File) {
            val temporary = File(destination.parentFile, "." + destination.name + "." + UUID.randomUUID() + ".tmp")
            try {
                FileOutputStream(temporary).use { it.write(data); it.fd.sync() }
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temporary.delete()
            }
        }
    }
}
