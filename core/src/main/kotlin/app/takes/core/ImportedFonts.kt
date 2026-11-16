package app.takes.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * One face of an imported font file, as the original names it
 * (YouRec/ImportedFonts.swift:7-11, 51-77): [id] is the "import:digest:index:name"
 * reference a title design saves, [weights] the stops of its weight axis (empty
 * for a face with one weight) and [defaultWeight] the weight it starts at.
 */
data class ImportedFontFace(val id: String, val name: String, val weights: List<Int>, val defaultWeight: Int) {
    val reference: ImportedFontReference get() = ImportedFontReference.parse(id)!!
}

/** A font file of the library: its [id] is the SHA-256 of its bytes, [filename] the name it was imported under (YouRec/ImportedFonts.swift:28-32). */
data class ImportedFontFile(val id: String, val filename: String, val faces: List<ImportedFontFace>)

/** Why a font could not be imported, in the original's words (YouRec/ImportedFonts.swift:80-88). */
class ImportedFontException(val reason: Reason) : Exception(reason.message) {
    enum class Reason(val message: String) {
        Invalid("This file could not be read as a font."),
        Unsupported("Choose a TTF, OTF, TTC, or OTC font file."),
    }
}

/**
 * The faces in the bytes of a TrueType or OpenType font, or of a collection of
 * them. The original asks Core Text (YouRec/ImportedFonts.swift:51-77); Android
 * has nothing that lists the faces of a file, so the tables are read here: the
 * PostScript and display names from `name`, the weight axis from `fvar`.
 */
object FontFaces {
    /** What one face says of itself. [axis] is the weight axis as minimum, default and maximum, or null. */
    data class Description(val postScriptName: String, val displayName: String, val axis: Triple<Double, Double, Double>?)

    private const val COLLECTION = 0x74746366 // "ttcf"
    private const val WEIGHT = 0x77676874 // "wght"
    private val SFNT = setOf(0x00010000, 0x4f54544f /* OTTO */, 0x74727565 /* true */)

    /** Throws [ImportedFontException] with [ImportedFontException.Reason.Invalid] when the bytes hold no usable face. */
    fun describe(bytes: ByteArray): List<Description> {
        val data = ByteBuffer.wrap(bytes)
        try {
            val offsets = if (bytes.size >= 12 && data.getInt(0) == COLLECTION) {
                val count = data.getInt(8)
                if (count <= 0 || count > 10_000) invalid()
                List(count) { data.getInt(12 + 4 * it).toLong() and 0xffffffffL }
            } else listOf(0L)
            return offsets.map { face(data, check(it, 12, bytes.size)) }.ifEmpty { invalid() }
        } catch (_: IndexOutOfBoundsException) {
            invalid()
        }
    }

    private fun face(data: ByteBuffer, start: Int): Description {
        if (data.getInt(start) !in SFNT) invalid()
        val count = data.getShort(start + 4).toInt() and 0xffff
        val tables = HashMap<Int, Pair<Int, Int>>()
        for (i in 0 until count) {
            val record = start + 12 + 16 * i
            val offset = data.getInt(record + 8).toLong() and 0xffffffffL
            val length = data.getInt(record + 12).toLong() and 0xffffffffL
            tables[data.getInt(record)] = check(offset, length, data.limit()) to length.toInt()
        }
        // A face Core Text accepts has a character map and outlines, TrueType or CFF.
        val outlines = (tag("glyf") in tables && tag("loca") in tables) || tag("CFF ") in tables || tag("CFF2") in tables
        if (tag("cmap") !in tables || !outlines) invalid()
        val names = tables[tag("name")]?.let { names(data, it.first, it.second) } ?: invalid()
        val postScript = names[6]?.takeIf { it.isNotEmpty() } ?: invalid()
        val display = names[4]?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(names[16] ?: names[1], names[17] ?: names[2]).joinToString(" ").takeIf { it.isNotBlank() }
            ?: postScript
        return Description(postScript, display, tables[tag("fvar")]?.let { weightAxis(data, it.first) })
    }

    /**
     * The names of a face by name ID, each in the best record the table has:
     * Windows English, then any Windows or Unicode record, then Macintosh Roman.
     */
    private fun names(data: ByteBuffer, start: Int, length: Int): Map<Int, String> {
        val count = data.getShort(start + 2).toInt() and 0xffff
        val strings = start + (data.getShort(start + 4).toInt() and 0xffff)
        val best = HashMap<Int, Pair<Int, String>>()
        for (i in 0 until count) {
            val record = start + 6 + 12 * i
            if (record + 12 > start + length) break
            val platform = data.getShort(record).toInt() and 0xffff
            val encoding = data.getShort(record + 2).toInt() and 0xffff
            val language = data.getShort(record + 4).toInt() and 0xffff
            val id = data.getShort(record + 6).toInt() and 0xffff
            val size = data.getShort(record + 8).toInt() and 0xffff
            val at = strings + (data.getShort(record + 10).toInt() and 0xffff)
            val rank = when {
                platform == 3 && (encoding == 1 || encoding == 10) && language == 0x409 -> 0
                platform == 3 && (encoding == 1 || encoding == 10) -> 1
                platform == 0 -> 2
                platform == 1 && encoding == 0 -> 3
                else -> continue
            }
            if ((best[id]?.first ?: Int.MAX_VALUE) <= rank) continue
            if (at + size > data.limit()) invalid()
            val raw = data.array().copyOfRange(at, at + size)
            val text = if (platform == 1) String(raw, Charsets.ISO_8859_1) else String(raw, Charsets.UTF_16BE)
            best[id] = rank to text.trim()
        }
        return best.mapValues { it.value.second }
    }

    private fun weightAxis(data: ByteBuffer, start: Int): Triple<Double, Double, Double>? {
        val axes = start + (data.getShort(start + 4).toInt() and 0xffff)
        val count = data.getShort(start + 8).toInt() and 0xffff
        val size = data.getShort(start + 10).toInt() and 0xffff
        for (i in 0 until count) {
            val axis = axes + i * size
            if (data.getInt(axis) != WEIGHT) continue
            fun fixed(at: Int) = data.getInt(at) / 65536.0
            return Triple(fixed(axis + 4), fixed(axis + 8), fixed(axis + 12))
        }
        return null
    }

    /**
     * The weights a face offers and the one it starts at, from its weight axis
     * (YouRec/ImportedFonts.swift:62-71): the ends of the axis within 100…1000,
     * its default, and every hundred between. A face without the axis offers
     * none and starts at 400. Core Text reports the axis values as numbers the
     * original reads as integers, dropping the fraction.
     */
    fun weights(axis: Triple<Double, Double, Double>?): Pair<List<Int>, Int> {
        if (axis == null) return emptyList<Int>() to 400
        val low = maxOf(100, axis.first.toInt())
        val high = minOf(1000, axis.third.toInt())
        if (low > high) return emptyList<Int>() to 400
        val weight = minOf(high, maxOf(low, axis.second.toInt()))
        return (listOf(low, high, weight) + (100..1000 step 100).filter { it in low..high }).toSortedSet().toList() to weight
    }

    /**
     * The faces of a file whose SHA-256 is [digest], with the ids titles save
     * (YouRec/ImportedFonts.swift:59, 72): the PostScript name in the id is cut
     * to its first 140 bytes.
     */
    fun faces(bytes: ByteArray, digest: String): List<ImportedFontFace> = describe(bytes).mapIndexed { index, face ->
        val name = face.postScriptName.toByteArray()
        val hint = String(name.copyOf(minOf(140, name.size)), Charsets.UTF_8)
        val (weights, weight) = weights(face.axis)
        ImportedFontFace("import:$digest:$index:$hint", face.displayName, weights, weight)
    }

    private fun tag(name: String): Int = ByteBuffer.wrap(name.toByteArray(Charsets.ISO_8859_1)).int

    private fun check(offset: Long, length: Long, size: Int): Int {
        if (offset < 0 || length < 0 || offset + length > size) invalid()
        return offset.toInt()
    }

    private fun invalid(): Nothing = throw ImportedFontException(ImportedFontException.Reason.Invalid)
}

/**
 * The fonts imported into the app, one folder per file named by its SHA-256,
 * holding the file under the name it was imported with
 * (YouRec/ImportedFonts.swift:34-188). Projects keep a copy of the files their
 * titles and captions use, as `font-<sha256>.sfnt` beside their media
 * ([preserve]), so a project opens with its fonts after they leave the
 * library, on another phone, and from a ZIP.
 *
 * Every read checks the bytes against their name: a file changed on disk is
 * no font of the library.
 */
class FontLibrary(val root: File) {
    private class Contents(val length: Long, val modified: Long, val faces: List<ImportedFontFace>)

    private val cache = HashMap<String, Contents>()

    /** The file of the library with this digest, if any. */
    @Synchronized
    fun file(digest: String): File? = File(root, digest).listFiles().orEmpty()
        .firstOrNull { it.extension.lowercase() in EXTENSIONS && Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }

    /** The faces of [file] if its bytes have the SHA-256 [digest]; null otherwise. Read once while its size and time stay the same. */
    @Synchronized
    fun contents(file: File, digest: String): List<ImportedFontFace>? {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        val length = file.length()
        val modified = file.lastModified()
        cache[file.path]?.let { if (it.length == length && it.modified == modified) return it.faces }
        val faces = try {
            val bytes = file.readBytes()
            if (sha256(bytes) != digest) return null
            FontFaces.faces(bytes, digest)
        } catch (_: Exception) {
            return null
        }
        if (cache.size > 256) cache.clear()
        cache[file.path] = Contents(length, modified, faces)
        return faces
    }

    /** Every file of the library, by the name of its first face, then file name (YouRec/ImportedFonts.swift:101-112). */
    @Synchronized
    fun files(): List<ImportedFontFile> = root.listFiles().orEmpty().filter { !it.name.startsWith(".") }.mapNotNull { directory ->
        val digest = directory.name
        val file = file(digest) ?: return@mapNotNull null
        val faces = contents(file, digest)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        ImportedFontFile(digest, file.name, faces)
    }.sortedWith(compareBy<ImportedFontFile> { it.faces[0].name }.thenBy { it.filename }.thenBy { it.id })

    /**
     * Adds the font in [bytes], picked as [filename]. Returns the file of the
     * library and whether it was added: the same bytes under another name are
     * the file already there (YouRec/ImportedFonts.swift:114-146).
     */
    @Synchronized
    fun add(bytes: ByteArray, filename: String): Pair<ImportedFontFile, Boolean> {
        val name = File(filename).name
        if (File(name).extension.lowercase() !in EXTENSIONS || name.startsWith(".") || name.toByteArray().size > 255) {
            throw ImportedFontException(ImportedFontException.Reason.Unsupported)
        }
        val digest = sha256(bytes)
        file(digest)?.let { installed ->
            val faces = contents(installed, digest) ?: throw ImportedFontException(ImportedFontException.Reason.Invalid)
            return ImportedFontFile(digest, installed.name, faces) to false
        }
        val faces = FontFaces.faces(bytes, digest)
        // The file is the whole entry, published at once; an empty folder left by an interrupted import is invisible.
        val directory = File(root, digest)
        if (!directory.isDirectory && !directory.mkdirs()) throw java.io.IOException("Cannot create ${directory.path}")
        ProjectDisk.writeAtomically(bytes, File(directory, name))
        return ImportedFontFile(digest, name, faces) to true
    }

    /** Takes a file out of the library. Projects keep their copies (YouRec/ImportedFonts.swift:147-152). */
    @Synchronized
    fun remove(digest: String) {
        if (digest.length != 64 || digest.any { it !in "0123456789abcdef" }) return
        val directory = File(root, digest)
        directory.listFiles().orEmpty().forEach { cache.remove(it.path); it.delete() }
        directory.delete()
    }

    /**
     * The face [id] names, and the file it is in: the copy in [folder], a
     * project's, before the library's (YouRec/ImportedFonts.swift:154-164).
     * Null when neither holds it, for a title to fall back and say so.
     */
    fun resolve(id: String, folder: File?): Pair<File, ImportedFontFace>? {
        val reference = ImportedFontReference.parse(id) ?: return null
        fun read(file: File): Pair<File, ImportedFontFace>? {
            val face = contents(file, reference.digest)?.getOrNull(reference.index)?.takeIf { it.id == id } ?: return null
            return file to face
        }
        folder?.let { read(File(it, reference.filename)) }?.let { return it }
        return file(reference.digest)?.let(::read)
    }

    /**
     * Copies into a project's [folder] the files of the library it names that
     * it does not hold yet, before its manifest names them
     * (YouRec/ImportedFonts.swift:166-177). A file the library no longer has
     * is skipped: the project may still hold it, or falls back.
     */
    @Synchronized
    fun preserve(filenames: Collection<String>, folder: File) {
        for (filename in filenames) {
            val target = File(folder, filename)
            if (target.exists()) continue
            val digest = filename.removePrefix("font-").removeSuffix(".sfnt")
            val source = file(digest) ?: continue
            ProjectDisk.writeAtomically(source.readBytes(), target)
        }
    }

    companion object {
        /** The kinds of file the library takes, by extension (YouRec/ImportedFonts.swift:35). */
        val EXTENSIONS = listOf("ttf", "otf", "ttc", "otc")

        /**
         * The project files of the imported faces an edit uses: its titles'
         * and its captions' (YouRec/ImportedFonts.swift:181-187).
         */
        fun files(state: EditState): Set<String> {
            val clips = state.layered?.video?.map { it.content } ?: state.clips
            val subtitles = state.layered?.subtitles ?: state.subtitles
            return files(clips, subtitles)
        }

        internal fun files(clips: List<TimelineClip>, subtitles: SubtitleSettings?): Set<String> {
            val faces = clips.flatMap { clip -> clip.texts.orEmpty().mapNotNull { it.appearance?.face } } + listOfNotNull(subtitleFace(subtitles))
            return faces.mapNotNullTo(LinkedHashSet()) { ImportedFontReference.parse(it)?.filename }
        }

        private fun subtitleFace(settings: SubtitleSettings?): String? =
            ((settings?.appearance as? JsonObject)?.get("face") as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
