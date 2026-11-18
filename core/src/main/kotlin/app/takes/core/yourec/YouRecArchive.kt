package app.takes.core.yourec

import app.takes.core.ProjectArchive
import app.takes.core.ProjectException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a [YouRecExport.Plan] as the ZIP the original's `ProjectArchive.export`
 * writes and its restore reads (YouRec/ProjectArchive.swift:219-296;
 * docs/IOS-INTEROP.md, 3.6): every entry stored, with its checksum and sizes
 * in its local header and no data descriptor, as ZIPFoundation writes them
 * (ZIPFoundation/Archive+Writing.swift:138-152); the steps, the manifest,
 * the current edit as an OpenTimelineIO file, the media, then
 * `integrity.json` with the hash of each media file. No recovery files.
 *
 * A stored entry needs its checksum before its first byte, so each file is
 * read twice: once for its checksum and hash, once to copy it.
 */
object YouRecArchive {
    const val INTEGRITY = ProjectArchive.INTEGRITY

    /** The room the ZIP needs before it is begun: [ProjectArchive.SPACE_MARGIN] and every file it holds, a re-encoded picture as encoded. */
    fun requiredSpace(plan: YouRecExport.Plan, png: (File) -> ByteArray): Long =
        ProjectArchive.SPACE_MARGIN + plan.media.sumOf { if (it.png) png(it.file).size.toLong() else it.file.length() } +
            plan.steps.values.sumOf { it.size.toLong() } + plan.timeline.size

    /**
     * Writes the ZIP of [plan] to [output], which is left open. [png] re-encodes a picture as PNG ([YouRecExport.Media.png]).
     * [progress] receives the media bytes copied and their total; throwing from it stops the export.
     * Throws [YouRecBlockedException] when the plan has blockers.
     */
    fun write(plan: YouRecExport.Plan, output: OutputStream, stamp: Long, png: (File) -> ByteArray, progress: (written: Long, total: Long) -> Unit = { _, _ -> }) {
        if (plan.blockers.isNotEmpty()) throw YouRecBlockedException(plan.blockers)
        class Summary(val size: Long, val crc: Long, val sha: String, val data: ByteArray?)
        // The checksums first, which a stored entry needs in its header.
        val pictures = plan.media.filter { it.png }.associate { it.name to png(it.file) }
        val summaries = plan.media.associate { media ->
            val data = pictures[media.name]
            val crc = CRC32()
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            open(media, data).use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    crc.update(buffer, 0, read); digest.update(buffer, 0, read); size += read
                }
            }
            media.name to Summary(size, crc.value, digest.digest().joinToString("") { "%02x".format(it) }, data)
        }
        val hashes = summaries.mapValues { it.value.sha }
        val total = summaries.values.sumOf { it.size }

        val zip = ZipOutputStream(output)
        fun begin(name: String, size: Long, crc: Long) {
            zip.putNextEntry(ZipEntry(name).apply {
                method = ZipEntry.STORED
                this.size = size
                compressedSize = size
                this.crc = crc
                time = stamp
            })
        }
        fun add(name: String, data: ByteArray) {
            begin(name, data.size.toLong(), CRC32().also { it.update(data) }.value)
            zip.write(data)
            zip.closeEntry()
        }
        for ((name, data) in plan.steps) add("History/$name", data)
        add("project.json", plan.manifest(hashes.filterKeys { name -> plan.media.any { it.name == name && it.recording } }))
        add(plan.timelineName, plan.timeline)
        var written = 0L
        progress(0, total)
        val buffer = ByteArray(1 shl 20)
        for (media in plan.media) {
            val summary = summaries.getValue(media.name)
            begin(media.name, summary.size, summary.crc)
            var count = 0L
            open(media, summary.data).use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    // A file that grew since its checksum would make a damaged entry.
                    if (count > summary.size) throw IOException("A file changed during the export.")
                    zip.write(buffer, 0, read)
                    progress(written + count, total)
                }
            }
            if (count != summary.size) throw IOException("A file changed during the export.")
            zip.closeEntry()
            written += count
        }
        add(INTEGRITY, integrity(hashes).toByteArray())
        zip.finish()
        output.flush()
    }

    /** `integrity.json` as the original writes it: pretty-printed with sorted keys, version 1 without recovery files (YouRec/ProjectArchive.swift:242, 291). */
    fun integrity(hashes: Map<String, String>): String {
        val escaped = { text: String -> kotlinx.serialization.json.JsonPrimitive(text).toString() }
        val lines = hashes.toSortedMap().entries.joinToString(",\n") { (name, hash) -> "    ${escaped(name)} : ${escaped(hash)}" }
        return if (hashes.isEmpty()) "{\n  \"sha256\" : {\n\n  },\n  \"version\" : 1\n}" else "{\n  \"sha256\" : {\n$lines\n  },\n  \"version\" : 1\n}"
    }

    private fun open(media: YouRecExport.Media, data: ByteArray?): InputStream {
        if (data != null) return data.inputStream()
        // Never through a link.
        if (!Files.isRegularFile(media.file.toPath(), LinkOption.NOFOLLOW_LINKS)) throw ProjectException(ProjectException.Reason.MissingMedia)
        return Files.newInputStream(media.file.toPath(), LinkOption.NOFOLLOW_LINKS)
    }
}

/** The project has something the iPhone cannot take: [blockers] says what. */
class YouRecBlockedException(val blockers: List<YouRecExport.Issue>) : IOException("This project cannot be zipped for iPhone: " + blockers.joinToString { it.kind.name })
