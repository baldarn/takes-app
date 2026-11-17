package app.takes.core

import kotlinx.serialization.Serializable
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** Why a file could not be restored as a project. Nothing was added to the library. */
class ProjectRestoreException(val reason: Reason, cause: Throwable? = null) : Exception(reason.message, cause) {
    enum class Reason(val message: String) {
        NotABackup("This file is not a project backup."),
        Damaged("This backup is damaged or incomplete. Export the project again and restore that file."),
        DamagedMedia("A recording in this backup is damaged. Export the project again and restore that file."),
        NotEnoughSpace("There is not enough free space to restore this project."),
        TooLarge("This backup holds more editing history than the app can restore."),
    }
}

/**
 * Reads what [ProjectArchive] writes, and the same archive made by the iPhone
 * app, back into a project folder.
 *
 * A restored project is always a new one: it gets its own id, so restoring
 * never replaces a project of the library or of Recently Deleted, and the same
 * backup can be restored twice.
 *
 * The archive is read once, front to back, in any entry order. Everything is
 * unpacked into a hidden folder beside the projects, checked there, and only
 * then renamed into place; a failure leaves the library as it was.
 */
object ProjectRestore {
    /** What an archive may hold. The sizes are those of the iPhone app, which has no entry limit. */
    data class Limits(
        val entries: Int = 20_000,
        /** `project.json` and `integrity.json`, each. */
        val manifestBytes: Long = 20_000_000,
        /** One `History/` snapshot. */
        val snapshotBytes: Long = 32_000_000,
        /** All `History/` snapshots together. */
        val historyBytes: Long = 256_000_000,
    )

    private const val MANIFEST = "project.json"
    private const val HISTORY = "History"
    private const val STAGING = ".restore-"

    // Staging folders of another run of the app are leftovers; this run's may be in use.
    private val session = UUID.randomUUID().toString()

    @Serializable
    private data class Integrity(val version: Int, val sha256: Map<String, String>)

    /**
     * Restores the archive in [input], which is left open, as a new project
     * under [projectsRoot] and returns it as it was saved.
     *
     * [maximumBytes] caps what is written to disk, counted as it is unpacked
     * and not as the archive declares it: pass the free space the restore may
     * use. [progress] receives the bytes of the archive read so far, to compare
     * with the size of the file; throwing from it stops the restore.
     *
     * Throws [ProjectRestoreException], [ProjectException] for a manifest that
     * does not validate or for missing media, and [IOException] when the
     * archive cannot be read or the disk cannot be written.
     */
    fun restore(
        input: InputStream,
        projectsRoot: File,
        maximumBytes: Long,
        limits: Limits = Limits(),
        now: Long = System.currentTimeMillis(),
        progress: (read: Long) -> Unit = {},
    ): Project {
        if (!projectsRoot.isDirectory && !projectsRoot.mkdirs()) throw IOException("Cannot create ${projectsRoot.path}")
        discardInterrupted(projectsRoot)
        val disk = ProjectDisk(projectsRoot)
        var id = newID()
        while (disk.folder(id).exists() || File(disk.deletedRoot, id).exists()) id = newID()

        // A project store of its own, so the folder is saved by the same code
        // as any project and is already named for its id when it moves.
        val staging = File(projectsRoot, "$STAGING$session-${UUID.randomUUID()}")
        val folder = File(staging, id)
        try {
            if (!File(folder, HISTORY).mkdirs()) throw IOException("Cannot create ${folder.path}")
            val hashes = unpack(input, folder, maximumBytes, limits, progress)
            val project = verified(folder, hashes).copy(id = id, updatedAt = now)
            publish(project, folder, ProjectDisk(staging))
            Files.move(folder.toPath(), disk.folder(id).toPath(), StandardCopyOption.ATOMIC_MOVE)
            return project
        } finally {
            delete(staging)
        }
    }

    /**
     * Removes what a restore left behind when the app was stopped in the
     * middle of it. Safe at any time: a restore running now is not touched.
     */
    fun discardInterrupted(projectsRoot: File) {
        for (directory in projectsRoot.listFiles().orEmpty()) {
            if (directory.name.startsWith(STAGING) && !directory.name.startsWith("$STAGING$session-")) delete(directory)
        }
    }

    /** Unpacks every entry into [folder] and returns the SHA-256 of each top-level file by its name in the archive. */
    private fun unpack(input: InputStream, folder: File, maximumBytes: Long, limits: Limits, progress: (Long) -> Unit): Map<String, String> {
        val counted = Counted(input)
        val zip = ZipInputStream(counted)
        val hashes = LinkedHashMap<String, String>()
        val seen = HashSet<String>()
        val buffer = ByteArray(1 shl 20)
        var total = 0L
        var history = 0L
        try {
            while (true) {
                // An entry name that is not text is reported as an illegal argument.
                val entry = try { zip.nextEntry } catch (error: IllegalArgumentException) { throw refused(error) } ?: break
                val name = entry.name
                val snapshot = name.startsWith("$HISTORY/") && Project.isSnapshotName(name.substring(HISTORY.length + 1))
                if (entry.isDirectory || !(snapshot || plain(name))) throw refused()
                // Two names that differ only in case are one file on many disks.
                if (!seen.add(name.lowercase())) throw refused()
                if (seen.size > limits.entries) throw ProjectRestoreException(ProjectRestoreException.Reason.TooLarge)

                val limit = when {
                    snapshot -> minOf(limits.snapshotBytes, limits.historyBytes - history)
                    name == MANIFEST || name == ProjectArchive.INTEGRITY -> limits.manifestBytes
                    else -> Long.MAX_VALUE
                }
                val file = File(folder, name).toPath()
                // The names are already safe; this holds even if that check is ever loosened.
                val parent = if (snapshot) File(folder, HISTORY).toPath() else folder.toPath()
                if (file.parent != parent || file.normalize() != file) throw refused()

                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                // Never over a file that is already there, and never through a link.
                FileOutputStream(Files.createFile(file).toFile()).use { output ->
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        size += read
                        total += read
                        // What was really unpacked: the sizes an archive declares cost nothing to forge.
                        if (size > limit) throw ProjectRestoreException(ProjectRestoreException.Reason.TooLarge)
                        if (total > maximumBytes) throw ProjectRestoreException(ProjectRestoreException.Reason.NotEnoughSpace)
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        progress(counted.count)
                    }
                    output.fd.sync()
                }
                if (snapshot) history += size else hashes[name] = digest.digest().joinToString("") { "%02x".format(it) }
            }
        } catch (error: ZipException) {
            // Includes an entry whose bytes do not match its checksum.
            throw ProjectRestoreException(ProjectRestoreException.Reason.Damaged, error)
        } catch (error: EOFException) {
            throw ProjectRestoreException(ProjectRestoreException.Reason.Damaged, error)
        } finally {
            // Frees the decompressor; the caller's stream stays open.
            zip.close()
        }
        progress(counted.count)
        return hashes
    }

    /** The project the unpacked [folder] holds, once every file in it is accounted for and intact. */
    private fun verified(folder: File, hashes: Map<String, String>): Project {
        // A file that is not a ZIP at all unpacks to nothing.
        if (hashes.isEmpty()) throw refused()
        // The archive is written with the hashes last, so a file cut short has none.
        if (MANIFEST !in hashes || ProjectArchive.INTEGRITY !in hashes) throw ProjectRestoreException(ProjectRestoreException.Reason.Damaged)
        val history = File(folder, HISTORY)
        val listed = HashSet<String>()
        val project = Project.readManifest(File(folder, MANIFEST).readBytes()) { listed += it; File(history, it).readBytes() }

        val integrity = try {
            documentJson.decodeFromString(Integrity.serializer(), File(folder, ProjectArchive.INTEGRITY).readText())
        } catch (error: Exception) {
            throw ProjectRestoreException(ProjectRestoreException.Reason.Damaged, error)
        }
        // Version 2 carries the iPhone app's recovery files, which have no place here.
        if (integrity.version != 1) throw refused()

        val media = project.mediaFiles.toSet()
        val timeline = OpenTimeline.filename(project)
        // Nothing comes along that the project does not name.
        if (!hashes.keys.all { it in media || it == MANIFEST || it == ProjectArchive.INTEGRITY || it == timeline }) throw refused()
        if (!history.list().orEmpty().all { it in listed }) throw refused()
        // A picture, an effect's file or a font copy may have been missing when the archive was made, or made by a
        // version that did not carry it: it stays missing, its titles fall back (YouRec/ProjectArchive.swift:227-231, 356-358).
        val fonts = project.fontFiles.toSet()
        if (!project.sources.all { it.filename in hashes }) throw ProjectException(ProjectException.Reason.MissingMedia)
        // A font copy is named by the SHA-256 of its bytes: one that is not is damaged, whatever the archive says.
        for (font in fonts) {
            val hash = hashes[font] ?: continue
            if (font != "font-$hash.sfnt") throw ProjectRestoreException(ProjectRestoreException.Reason.DamagedMedia)
        }
        // Every recording is vouched for, and nothing outside the project is.
        if (!project.sources.all { it.filename in integrity.sha256 } || !integrity.sha256.keys.all { it in media }) throw refused()
        // What the archive vouches for must be there.
        if (!integrity.sha256.keys.all { it in hashes }) throw ProjectException(ProjectException.Reason.MissingMedia)
        for ((name, hash) in integrity.sha256) {
            if (hashes[name] != hash) throw ProjectRestoreException(ProjectRestoreException.Reason.DamagedMedia)
        }
        // The verified hash stays with each source, to recognise its file if it ever has to be found again.
        return project.copy(sources = project.sources.map { it.copy(sha256 = integrity.sha256[it.filename]) }).also { it.validate() }
    }

    /** Leaves in [folder] exactly what [ProjectDisk.save] writes for [project], beside its media. */
    private fun publish(project: Project, folder: File, staged: ProjectDisk) {
        // The timeline is written again by every export and the hashes are only for the journey.
        File(folder, OpenTimeline.filename(project)).delete()
        File(folder, ProjectArchive.INTEGRITY).delete()
        // Saved as new: the archived manifest names another project and must not become a revision of this one.
        if (!File(folder, MANIFEST).delete()) throw IOException("Cannot replace ${folder.path}/$MANIFEST")
        staged.save(project)
        // A snapshot written by another version of the app may be stored again under a new name.
        val kept = HashSet<String>()
        project.manifest { name, _ -> kept += name }
        for (file in File(folder, HISTORY).listFiles().orEmpty()) if (file.name !in kept) file.delete()
    }

    /** A file name with no folder in it, visible, and usable on any disk. */
    private fun plain(name: String): Boolean =
        name.isNotEmpty() && !name.startsWith(".") && name.none { it == '/' || it == '\\' || it == '\u0000' } &&
            name.toByteArray().size <= 255 && name == File(name).name && !name.equals(HISTORY, ignoreCase = true)

    private fun refused(cause: Throwable? = null) = ProjectRestoreException(ProjectRestoreException.Reason.NotABackup, cause)

    /** Removes a folder and what is in it, without following a link out of it. */
    private fun delete(directory: File) {
        val root = directory.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        runCatching {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                    Files.delete(directory)
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }

    /** Counts the bytes of the archive itself as they are read. */
    private class Counted(input: InputStream) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count += 1 }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, length).also { if (it > 0) count += it }
        override fun skip(amount: Long): Long = super.skip(amount).also { count += it }
        override fun close() {} // The caller keeps its stream.
        override fun markSupported(): Boolean = false
    }
}
