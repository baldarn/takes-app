package app.takes.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A project as one ZIP: the originals, the editing history and an
 * OpenTimelineIO edit. Unzipped, the .otio file sits beside the media it
 * names, ready to import into another editor.
 *
 * Entries, in this order:
 *  - `project.json` and the `History/<sha256>.json` snapshots it lists
 *  - `Timeline.otio` (see [OpenTimeline.filename])
 *  - every file of [Project.sources], under its own name, then the files its
 *    edits refer to: imported LUTs, and the copies of imported fonts
 *    ([Project.fontFiles]) the project holds
 *  - `integrity.json`, the SHA-256 of each media file as it was archived
 *
 * Caches, exports and manifest revisions are not part of a project and stay behind.
 */
object ProjectArchive {
    const val INTEGRITY = "integrity.json"

    private val pretty = Json { prettyPrint = true }

    /**
     * Writes the archive to [output], which is left open. [progress] receives
     * the media bytes archived so far and their total; throwing from it stops
     * the export.
     *
     * The manifest and history are written from [project] itself, the same
     * bytes [ProjectDisk.save] stores, so they always agree with the timeline
     * beside them. Only media is read from [projectFolder].
     *
     * Nothing is written when a source file is missing or is not a plain
     * file: a symbolic link is never followed, wherever it points.
     */
    fun write(projectFolder: File, project: Project, output: OutputStream, progress: (written: Long, total: Long) -> Unit = { _, _ -> }) {
        project.validate()
        val media = media(projectFolder, project)
        val total = media.sumOf { (_, path) -> size(path) }
        val timeline = OpenTimeline.text(project).toByteArray()

        val zip = ZipOutputStream(output)
        val stamp = project.updatedAt
        fun add(name: String, data: ByteArray) {
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            zip.putNextEntry(ZipEntry(name).apply { time = stamp })
            zip.write(data)
            zip.closeEntry()
        }

        val snapshots = LinkedHashMap<String, ByteArray>()
        val manifest = project.manifest { name, data -> snapshots.putIfAbsent(name, data) }
        add("project.json", manifest)
        for ((name, data) in snapshots) add("History/$name", data)
        add(OpenTimeline.filename(project), timeline)

        // Movies and sound are already compressed. Level 0 copies them as they
        // are, in one pass: a stored entry would need the checksum before its
        // first byte, which means reading every recording twice.
        zip.setLevel(Deflater.NO_COMPRESSION)
        val hashes = sortedMapOf<String, String>()
        val buffer = ByteArray(1 shl 20)
        var written = 0L
        progress(0, total)
        for ((name, path) in media) {
            val expected = size(path)
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            zip.putNextEntry(ZipEntry(name).apply { time = stamp })
            // The check and the open are separate steps; refusing links again here closes the gap between them.
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    zip.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    count += read
                    progress(minOf(written + count, total), total)
                }
            }
            zip.closeEntry()
            if (count != expected) throw IOException("A recording changed during the export. Wait for recording to finish and try again.")
            written += count
            hashes[name] = digest.digest().joinToString("") { "%02x".format(it) }
        }

        val integrity = buildJsonObject {
            put("version", 1)
            put("sha256", buildJsonObject { for ((name, hash) in hashes) put(name, hash) })
        }
        add(INTEGRITY, pretty.encodeToString(JsonObject.serializer(), integrity).toByteArray())
        // Ends the archive without closing the caller's stream.
        zip.finish()
        output.flush()
    }

    /** What is kept free beyond the media for a ZIP to be made (YouRec/ProjectArchive.swift:232). */
    const val SPACE_MARGIN = 256_000_000L

    /**
     * The room a ZIP of [project] needs before it is begun, as the original requires it above the
     * storage's floor (YouRec/ProjectArchive.swift:232-240): [SPACE_MARGIN] and every media file
     * it will hold. Throws as [write] does when a source is missing or is not a plain file.
     */
    fun requiredSpace(projectFolder: File, project: Project): Long {
        project.validate()
        return SPACE_MARGIN + media(projectFolder, project).sumOf { (_, path) -> size(path) }
    }

    /** The media files a ZIP of [project] holds, by name, with their paths. */
    private fun media(projectFolder: File, project: Project): List<Pair<String, Path>> {
        // validate() has made every name a plain file name, so each path stays inside the folder.
        // A picture, an effect's file or a font copy already missing stays behind, as in the original
        // (YouRec/ProjectArchive.swift:226-231): only the recordings are required.
        val recordings = project.sources.map { it.filename }.toSet()
        return project.mediaFiles.map { it to File(projectFolder, it).toPath() }
            .filter { (name, path) -> name in recordings || Files.exists(path, LinkOption.NOFOLLOW_LINKS) }
    }

    private fun size(path: Path): Long {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw ProjectException(ProjectException.Reason.MissingMedia)
        return Files.size(path)
    }
}
