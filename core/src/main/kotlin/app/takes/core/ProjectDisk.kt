package app.takes.core

import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID

@Serializable
data class ProjectDeletion(val id: String, val date: Long)

/**
 * A draft journalled when a save failed: the hash of the manifest it was made on top of, and its own
 * manifest, in Base64 (YouRec/ProjectRecovery.swift:356-359).
 */
@Serializable
private data class PendingEdit(val previous: String? = null, val manifest: String)

/**
 * App-owned project directories. Each holds `project.json`, an immutable
 * content-addressed `History/`, the recordings, and recent manifest revisions.
 *
 * With [fonts], a save first copies into the project the imported fonts its
 * edit uses, so the manifest never names a font the folder lacks
 * (YouRec/Project.swift:840).
 */
class ProjectDisk(val root: File, private val fonts: FontLibrary? = null) {
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
        fonts?.preserve(FontLibrary.files(project.edit), directory)
        val head = File(directory, "project.json")
        val previous = if (head.isFile) head.readBytes() else null
        val data = project.manifest { name, bytes -> ensureSnapshot(bytes, File(history, name)) }
        if (previous != null && !data.contentEquals(previous)) {
            // The first save of a timeline leaves nothing an older version could open in its place (docs/TRACKS-PLAN.md, 3.4, guard 2).
            if (project.schemaVersion >= TrackTimeline.SCHEMA && (Project.schemaOf(previous) ?: 0) < TrackTimeline.SCHEMA) pinOlder(previous, directory)
            else keepRevision(previous, directory)
        }
        if (previous == null || !data.contentEquals(previous)) writeAtomically(data, head)
        clearMatchingDraft(data, directory)
    }

    /**
     * Removes the pending draft of the project in [directory] once [committed], the manifest just saved, is
     * the draft's own, as the original does after every save (YouRec/ProjectRecovery.swift:360-369).
     */
    private fun clearMatchingDraft(committed: ByteArray, directory: File) {
        val pending = File(directory, PENDING_EDIT)
        val draft = runCatching { documentJson.decodeFromString(PendingEdit.serializer(), File(pending, DRAFT).readText()) }.getOrNull() ?: return
        val manifest = runCatching { Base64.getDecoder().decode(draft.manifest) }.getOrNull() ?: return
        if (RecoveryRules.draftCommitted(committed, manifest)) pending.deleteRecursively()
    }

    /**
     * Writes [project] as the emergency journal of a save that failed (YouRec/ProjectRecovery.swift:370-382):
     * its manifest, with the hash of the saved one it was made on top of, and only the history snapshots the
     * project's `History` does not already hold, in the folder's `.pending-edit`.
     */
    fun journalDraft(project: Project) = synchronized(writeLock) {
        val directory = folder(project.id)
        val pending = File(directory, PENDING_EDIT)
        val head = File(directory, "project.json")
        val previous = if (head.isFile) sha256(head.readBytes()) else null
        val data = project.manifest { name, bytes ->
            val existing = File(File(directory, "History"), name)
            if (!(existing.isFile && existing.readBytes().contentEquals(bytes))) {
                val target = File(File(pending, "History"), name)
                if (!target.parentFile.isDirectory && !target.parentFile.mkdirs()) throw java.io.IOException("Cannot create ${target.parentFile.path}")
                if (!(target.isFile && target.readBytes().contentEquals(bytes))) writeAtomically(bytes, target)
            }
        }
        if (!pending.isDirectory && !pending.mkdirs()) throw java.io.IOException("Cannot create ${pending.path}")
        val envelope = PendingEdit(previous, Base64.getEncoder().encodeToString(data))
        writeAtomically(documentJson.encodeToString(PendingEdit.serializer(), envelope).toByteArray(), File(pending, DRAFT))
    }

    /**
     * The draft journalled for the project in folder [id], when it is still to be offered: the saved manifest is
     * not already the draft's and is still the one the draft was made on top of (YouRec/ProjectRecovery.swift:383-397).
     * Null when there is none to offer; throws when the journal cannot be read.
     */
    fun pendingDraft(id: String): Project? {
        val directory = folder(id)
        val pending = File(directory, PENDING_EDIT)
        val file = File(pending, DRAFT)
        if (!file.isFile) return null
        val draft = documentJson.decodeFromString(PendingEdit.serializer(), file.readText())
        val manifest = Base64.getDecoder().decode(draft.manifest)
        val head = File(directory, "project.json")
        val current = if (head.isFile) head.readBytes() else null
        if (!RecoveryRules.draftApplies(current, manifest, draft.previous)) return null
        val project = Project.readManifest(manifest) { name ->
            File(File(pending, "History"), name).takeIf { it.isFile }?.readBytes() ?: File(File(directory, "History"), name).readBytes()
        }
        if (project.id != id) throw ProjectException(ProjectException.Reason.InvalidDocument)
        return project
    }

    /**
     * Writes a history file unless it already holds [data]. A file found
     * whole is not read again while its size and time stay what they were,
     * as the original checks a snapshot once (YouRec/ProjectRecovery.swift:9-23).
     */
    private fun ensureSnapshot(data: ByteArray, file: File) {
        val name = file.name
        fun stamp() = "${file.length()}-${file.lastModified()}"
        if (file.isFile) {
            if (checked[file.path] == stamp()) return
            if (sha256(file.readBytes()) + ".json" == name) { remember(file.path, stamp()); return }
        }
        writeAtomically(data, file)
        remember(file.path, stamp())
    }

    /**
     * Moves [previous], the head being replaced, and every kept revision of a
     * schema below the timeline's out of `.revisions`, which older versions
     * scan for a manifest to open when the head is newer than they read, into
     * `.revisions/pinned`, which none of them looks in and nothing rotates:
     * `schema-<n>-<sha256>.json`, as the original pins a manifest before its
     * schema rises (YouRec/ProjectRecovery.swift:123-128). With the history
     * files, which no save deletes, each is the project as it was.
     */
    private fun pinOlder(previous: ByteArray, directory: File) {
        val pinned = File(File(directory, ".revisions"), PINNED)
        if (!pinned.isDirectory && !pinned.mkdirs()) throw java.io.IOException("Cannot create ${pinned.path}")
        fun pin(data: ByteArray) {
            val file = File(pinned, "schema-${Project.schemaOf(data) ?: 0}-${sha256(data)}.json")
            if (!file.isFile) writeAtomically(data, file)
        }
        pin(previous)
        for (revision in revisions(directory)) {
            val data = revision.readBytes()
            if ((Project.schemaOf(data) ?: 0) >= TrackTimeline.SCHEMA) continue
            pin(data)
            if (!revision.delete()) throw java.io.IOException("Cannot move ${revision.path}")
        }
    }

    /** The manifests [pinOlder] kept, newest schema first. */
    fun pinnedManifests(id: String): List<File> =
        File(File(folder(id), ".revisions"), PINNED).listFiles().orEmpty().filter { it.extension == "json" }.sortedByDescending { it.name }

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
    fun recoveryFiles(project: Project): List<File> = recoveryFiles(project.id)

    /** [recoveryFiles] of the project in folder [id], whether or not it can be read. */
    fun recoveryFiles(id: String): List<File> =
        File(folder(id), RECOVERY).listFiles().orEmpty().flatMap { it.listFiles().orEmpty().toList() }
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
        private const val PINNED = "pinned"

        // History files found whole, by path, with the size and time they had then.
        private val checked = java.util.concurrent.ConcurrentHashMap<String, String>()

        private fun remember(path: String, stamp: String) {
            if (checked.size > 200_000) checked.clear()
            checked[path] = stamp
        }
        /** The folder of a project that holds what could not be read, as in the original. */
        const val RECOVERY = "Recovery"
        /** The folder of a project that holds the journal of a save that failed, as in the original. */
        const val PENDING_EDIT = ".pending-edit"
        private const val DRAFT = "draft.json"
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
