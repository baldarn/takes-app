package app.takes.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** Why a project could not be duplicated. Nothing was added to the library. */
class ProjectDuplicateException(val reason: Reason, cause: Throwable? = null) : Exception(reason.message, cause) {
    enum class Reason(val message: String) {
        NotEnoughSpace("There is not enough free space to duplicate this project."),
    }
}

/**
 * Makes a second project out of one of the library: the same recordings,
 * sounds, edits and undo history, under its own id and a title of its own.
 *
 * Each project owns its files. The media is copied, never shared, so either
 * project can be edited, repaired or deleted without the other noticing.
 *
 * Everything is put together in a hidden folder beside the projects and only
 * then renamed into place; a failure leaves the library as it was.
 */
object ProjectDuplicate {
    /** Titles are kept this short, suffix included. */
    const val MAXIMUM_TITLE = 120

    private const val STAGING = ".duplicate-"

    // Staging folders of another run of the app are leftovers; this run's may be in use.
    private val session = UUID.randomUUID().toString()

    /**
     * The title for a copy of the project called [title]: "Trip copy", then
     * "Trip copy 2", "Trip copy 3"… the first that none of [taken], the titles
     * of the projects in the library, already has, whatever its case.
     *
     * A copy of a copy is numbered beside it rather than named "Trip copy copy".
     * [word] is "copy" in the person's language.
     */
    fun title(title: String, taken: Collection<String>, word: String = "copy"): String {
        val base = title.replace(Regex(" " + Regex.escape(word) + "(?: [0-9]+)?$", RegexOption.IGNORE_CASE), "")
        val existing = taken.map { it.lowercase() }.toSet()
        var number = 1
        while (true) {
            val suffix = if (number == 1) " $word" else " $word $number"
            val candidate = shortened(base, MAXIMUM_TITLE - suffix.length) + suffix
            if (candidate.lowercase() !in existing) return candidate
            number += 1
        }
    }

    /**
     * Duplicates [original], whose folder is under [projectsRoot], as a new
     * project called [title] and returns it as it was saved.
     *
     * The copy is written from [original] itself, so edits not saved yet come
     * along. It is a project made at [now]: it gets its own id and dates, and
     * its next take is appended at the end even while the original is in the
     * middle of inserting takes at the playhead.
     *
     * Only what the project names is copied: its recordings and sounds and the
     * files its edits refer to, from any step of the history. Caches, exports
     * and manifest revisions stay with the original. A file that is already
     * missing is missing in the copy too, and can be found again in both.
     *
     * [maximumBytes] caps what is written to disk: pass the free space the
     * copy may use. [progress] receives the media bytes copied so far and
     * their total; throwing from it stops the copy.
     *
     * Throws [ProjectDuplicateException], [ProjectException] for a project
     * that does not validate or whose media is not a plain file (a symbolic
     * link is never followed), and [IOException] when the disk cannot be read
     * or written.
     */
    fun duplicate(
        original: Project,
        projectsRoot: File,
        title: String,
        maximumBytes: Long = Long.MAX_VALUE,
        now: Long = System.currentTimeMillis(),
        progress: (copied: Long, total: Long) -> Unit = { _, _ -> },
    ): Project {
        original.validate()
        if (!projectsRoot.isDirectory && !projectsRoot.mkdirs()) throw IOException("Cannot create ${projectsRoot.path}")
        discardInterrupted(projectsRoot)
        val disk = ProjectDisk(projectsRoot)
        var id = newID()
        while (disk.folder(id).exists() || File(disk.deletedRoot, id).exists()) id = newID()
        val copy = original.copy(
            id = id, title = title, createdAt = now, updatedAt = now,
            recordingInsertion = null, recordingInsertionStep = null,
        )

        // validate() has made every name a plain file name, so each path stays inside the folder.
        val source = disk.folder(original.id)
        val media = original.mediaFiles.distinct().mapNotNull { name ->
            val path = File(source, name).toPath()
            // An already missing file stays missing, and can be found again in both projects.
            if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return@mapNotNull null
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw ProjectException(ProjectException.Reason.InvalidDocument)
            Triple(name, path, Files.size(path))
        }
        val total = media.sumOf { it.third }
        if (total > maximumBytes) throw ProjectDuplicateException(ProjectDuplicateException.Reason.NotEnoughSpace)

        // A project store of its own, so the folder is saved by the same code
        // as any project and is already named for its id when it moves.
        val staging = File(projectsRoot, "$STAGING$session-${UUID.randomUUID()}")
        val folder = File(staging, id)
        try {
            if (!folder.mkdirs()) throw IOException("Cannot create ${folder.path}")
            val buffer = ByteArray(1 shl 20)
            var copied = 0L
            progress(0, total)
            for ((name, path, _) in media) {
                // The check and the open are separate steps; refusing links again here closes the gap between them.
                Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                    // Never over a file that is already there, and never through a link.
                    FileOutputStream(Files.createFile(File(folder, name).toPath()).toFile()).use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            copied += read
                            // What was really copied: a recording may have grown since it was measured.
                            if (copied > maximumBytes) throw ProjectDuplicateException(ProjectDuplicateException.Reason.NotEnoughSpace)
                            output.write(buffer, 0, read)
                            progress(minOf(copied, total), total)
                        }
                        output.fd.sync()
                    }
                }
            }
            ProjectDisk(staging).save(copy)
            Files.move(folder.toPath(), disk.folder(id).toPath(), StandardCopyOption.ATOMIC_MOVE)
            return copy
        } finally {
            delete(staging)
        }
    }

    /**
     * Removes what a duplication left behind when the app was stopped in the
     * middle of it. Safe at any time: a duplication running now is not touched.
     */
    fun discardInterrupted(projectsRoot: File) {
        for (directory in projectsRoot.listFiles().orEmpty()) {
            if (directory.name.startsWith(STAGING) && !directory.name.startsWith("$STAGING$session-")) delete(directory)
        }
    }

    /** The first [length] characters, without cutting a character made of two in half. */
    private fun shortened(text: String, length: Int): String {
        val cut = text.take(maxOf(0, length))
        return if (cut.length < text.length && cut.lastOrNull()?.isHighSurrogate() == true) cut.dropLast(1) else cut
    }

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
}
