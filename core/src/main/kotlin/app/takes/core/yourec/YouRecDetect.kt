package app.takes.core.yourec

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Whether a project ZIP was made by YouRec, the iPhone original, rather than
 * by Takes (docs/IOS-INTEROP.md, 3.4). The schema number is never looked at:
 * the two apps' numbers overlap and mean different things.
 */
object YouRecDetect {
    /** The prefix of the original's recovery entries, `Recovery/<UUID or 64 hex>/<name>` (YouRec/ProjectArchive.swift:95-104). */
    const val RECOVERY = "Recovery/"

    /**
     * [manifest] is `project.json`, [entries] the names in the archive and
     * [integrity] `integrity.json`, when it reads as an object.
     *
     * The manifest decides when it can: YouRec always writes
     * `automaticCanvasVersion` (YouRec/Project.swift:566), and Takes writes its
     * dates as whole milliseconds since 1970, where YouRec writes seconds since
     * 2001 with their fraction. Only a manifest that says neither is told by the
     * archive around it: the original's integrity version 2 and its
     * `Recovery/` entries, which Takes never writes.
     */
    fun isYouRec(manifest: JsonObject, entries: Set<String>, integrity: JsonObject?): Boolean {
        if ("automaticCanvasVersion" in manifest) return true
        if ("recordingHistoryVersion" in manifest || "recordingSite" in manifest) return true
        if (((manifest["captureLedger"] as? JsonArray)?.firstOrNull() as? JsonObject)?.containsKey("clip") == true) return true
        if (("snapshots" in manifest || "history" in manifest) && "snapshotFiles" !in manifest) return true
        val created = manifest["createdAt"] as? JsonPrimitive
        // Takes' milliseconds are always a whole number, and YouRec's seconds of a `Date()` practically never are.
        if (created != null && !created.isString) return created.longOrNull == null
        return (integrity?.get("version") as? JsonPrimitive)?.intOrNull == 2 || entries.any { it.startsWith(RECOVERY) }
    }

    /**
     * Whether [path] is one of the original's recovery entries: the files of a
     * take that could not be read, kept apart from the timeline
     * (YouRec/ProjectArchive.swift:95-104).
     */
    fun isRecoveryEntry(path: String): Boolean {
        val parts = path.split("/")
        if (parts.size != 3 || parts[0] != "Recovery") return false
        val group = parts[1]
        if (!isUUID(group) && !(group.length == 64 && group.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })) return false
        val name = parts[2]
        if (name.isEmpty() || name.toByteArray().size > 255 || name.startsWith(".") || name.contains('\\') || name.contains('\u0000')) return false
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in RECOVERY_EXTENSIONS || name.endsWith(".events.json") || name.endsWith(".effects.json")
    }

    private val RECOVERY_EXTENSIONS = setOf("mov", "mp4", "m4v", "m4a", "caf", "wav", "aac", "flac", "mp3", "aif", "aiff")

    /** As Foundation's `UUID(uuidString:)` reads one: 8-4-4-4-12 hex digits, either case. */
    fun isUUID(text: String): Boolean {
        if (text.length != 36) return false
        return text.withIndex().all { (index, character) ->
            if (index == 8 || index == 13 || index == 18 || index == 23) character == '-'
            else character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'
        }
    }
}
