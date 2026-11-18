package app.takes.core.yourec

import app.takes.core.sha256
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The YouRec projects of `src/test/resources/yourec/<case>/`, written by hand
 * as the original's `JSONEncoder` writes them (docs/IOS-INTEROP.md, 7.5): keys
 * sorted, dates as seconds since 2001, dictionaries keyed by UUID as flat
 * arrays, enums with values as `{"case":{…}}`.
 *
 * The steps are kept as `History/step-<n>.json` so they can be edited; the
 * manifest names them so, and [load] names each by the SHA-256 of its bytes,
 * as the original does, in the manifest too.
 */
object YouRecFixtures {
    val cases = listOf("takes", "layers", "titles", "effects", "canvas", "audio", "scoped")

    class Fixture(val manifest: ByteArray, val steps: Map<String, ByteArray>)

    fun load(case: String): Fixture {
        val folder = File(YouRecFixtures::class.java.getResource("/yourec/$case/project.json")!!.toURI()).parentFile
        var manifest = File(folder, "project.json").readText()
        val steps = LinkedHashMap<String, ByteArray>()
        for (file in File(folder, "History").listFiles()!!.sortedBy { it.name }) {
            val bytes = file.readBytes()
            val name = sha256(bytes) + ".json"
            manifest = manifest.replace("\"${file.name}\"", "\"$name\"")
            steps[name] = bytes
        }
        return Fixture(manifest.toByteArray(), steps)
    }

    /**
     * A ZIP as the original's `ProjectArchive.export` writes it
     * (YouRec/ProjectArchive.swift:219-296): every entry stored, the steps,
     * the manifest, then [media] and [extra] entries, then `integrity.json`
     * with [integrity] as its text.
     */
    fun zip(fixture: Fixture, media: Map<String, ByteArray>, integrity: String, extra: Map<String, ByteArray> = emptyMap()): ByteArray {
        val entries = LinkedHashMap<String, ByteArray>()
        for ((name, bytes) in fixture.steps) entries["History/$name"] = bytes
        entries["project.json"] = fixture.manifest
        entries += media
        entries += extra
        entries["integrity.json"] = integrity.toByteArray()
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                for ((name, bytes) in entries) {
                    val entry = ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = bytes.size.toLong()
                        crc = CRC32().also { it.update(bytes) }.value
                    }
                    zip.putNextEntry(entry)
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
    }

    /** `integrity.json` as the original writes it: pretty-printed, keys sorted (YouRec/ProjectArchive.swift:242). */
    fun integrity(hashes: Map<String, String>, version: Int): String {
        val lines = hashes.toSortedMap().entries.joinToString(",\n") { (name, hash) -> "    \"$name\" : \"$hash\"" }
        return "{\n  \"sha256\" : {\n$lines\n  },\n  \"version\" : $version\n}"
    }
}
