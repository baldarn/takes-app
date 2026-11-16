package app.takes.core

import java.io.File
import java.io.RandomAccessFile

/**
 * The rules the original keeps a take, a draft and the device safe by, apart
 * from the files that apply them (YouRec/ProjectRecovery.swift:299-464,
 * YouRec/RecordingSalvage.swift:54-152). The free-space floor itself is
 * [RecordingSpace.ORIGINAL]'s; Recently Deleted is [DeletionRules].
 */
object RecoveryRules {
    // MARK: Space

    /** The most a project may hold in automatic recovery copies: one file, and all of them. */
    const val AUTO_FILE_LIMIT = 64L * 1024 * 1024
    const val AUTO_TOTAL_LIMIT = 256L * 1024 * 1024

    /** The floor kept free on a storage of [total] bytes: max(512 MB, min(2 GB, total / 50)). */
    fun floor(total: Long): Long = RecordingSpace.ORIGINAL.floor(total)

    /**
     * Why [bytes] cannot be written with [free] bytes free on a storage of
     * [total], keeping the floor; none when they can. The original's words,
     * with the amount to free formatted by [format] (the system's file size text).
     */
    fun shortOfSpace(bytes: Long, free: Long, total: Long, format: (Long) -> String): String? =
        spaceToFree(bytes, free, total)?.let { "Free about " + format(it) + " more space to finish this operation and keep space available on the phone." }

    /** How much more must be freed for [bytes] to be written keeping the floor, at least one; none when they fit. */
    fun spaceToFree(bytes: Long, free: Long, total: Long): Long? {
        val room = free - floor(total)
        return if (bytes in 0..room) null else maxOf(1L, bytes - room)
    }

    // MARK: Salvage

    /** Room a repaired copy of a [bytes] long recording may take: the file, a tenth more and a megabyte. */
    fun copyBudget(bytes: Long): Long = bytes + bytes / 10 + 1_048_576

    /** Whether a file of the repair folder is an unpublished copy, safe to clear: 64 hex digits and `.mov`. */
    fun isUnpublishedCopy(name: String): Boolean {
        val stem = name.substringBeforeLast('.', "")
        return name.endsWith(".mov") && stem.length == 64 && stem.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    /** The longest a readable recording may last, a week; anything else is not media. */
    const val MAXIMUM_DURATION = 604_800.0

    fun plausibleDuration(seconds: Double): Boolean = seconds.isFinite() && seconds > 0 && seconds <= MAXIMUM_DURATION

    /** Where a track of [duration] seconds is probed for a first sample: its start and 0.4 s before its end. */
    fun probeOffsets(duration: Double): List<Double> = listOf(0.0, maxOf(0.0, duration - 0.40)).distinct()

    /**
     * The length of the complete start of an interrupted fragmented movie:
     * up to the end of the last movie fragment closed by its media data, after
     * the movie's initialisation box. Unknown or broken boxes end the scan.
     * None when there is no such start, or when it is the whole file (nothing
     * to repair). [read] gives up to the asked bytes at an offset.
     */
    fun completePrefix(length: Long, read: (offset: Long, count: Int) -> ByteArray, checkCancelled: () -> Unit = {}): Long? {
        var offset = 0L
        var boundary = 0L
        var initialized = false
        var fragment = false
        while (length - offset >= 8) {
            checkCancelled()
            val header = read(offset, 16)
            if (header.size < 8) break
            fun integer(from: Int, to: Int): Long {
                var value = 0L
                for (i in from until to) value = value shl 8 or (header[i].toLong() and 0xFF)
                return value
            }
            var size = integer(0, 4)
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            val extended = size == 1L
            if (extended) {
                if (header.size != 16) break
                size = integer(8, 16)
            }
            // A 64-bit size past Long's range reads negative: broken.
            if (size < (if (extended) 16 else 8) || size > length - offset) break
            offset += size
            if (type == "moov") initialized = true
            if (type == "moof") fragment = initialized
            if (type == "mdat" && fragment) {
                boundary = offset
                fragment = false
            }
        }
        return if (boundary in 1 until length) boundary else null
    }

    fun completePrefix(bytes: ByteArray): Long? = completePrefix(bytes.size.toLong(), { offset, count ->
        bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + count))
    })

    fun completePrefix(file: File, checkCancelled: () -> Unit = {}): Long? = RandomAccessFile(file, "r").use { input ->
        completePrefix(input.length(), { offset, count ->
            input.seek(offset)
            val buffer = ByteArray(count)
            var filled = 0
            while (filled < count) {
                val n = input.read(buffer, filled, count - filled)
                if (n < 0) break
                filled += n
            }
            buffer.copyOf(filled)
        }, checkCancelled)
    }

    // MARK: Take receipts

    /**
     * Whether a take's receipt names the recording it sits beside: version 1,
     * the project's id, the movie's file name, a sequence from 0, and a file
     * name ending in the segment's id (YouRec/ProjectRecovery.swift:312-319).
     * Takes' recordings end in [extension].
     */
    fun receiptMatches(
        version: Int,
        receiptProject: String,
        project: String,
        receiptFilename: String,
        movieFilename: String,
        sequence: Int,
        segmentId: String,
        extension: String = "mov",
    ): Boolean = version == 1 && receiptProject.equals(project, ignoreCase = true) && receiptFilename == movieFilename &&
        sequence >= 0 && receiptFilename.lowercase().endsWith("-$segmentId.$extension".lowercase())

    /**
     * Whether a reserve file left by a take no longer running may be
     * reclaimed: its name, without `.reserve`, ends in a UUID
     * (YouRec/ProjectRecovery.swift:327-347).
     */
    fun isReclaimableReserve(name: String, live: Boolean): Boolean =
        !live && name.endsWith(".reserve") && UUID_SUFFIX.containsMatchIn(name.removeSuffix(".reserve"))

    private val UUID_SUFFIX = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    // MARK: Drafts

    /**
     * Whether a pending draft, journalled when a normal save failed, is to be
     * offered: the saved project is not already the draft's, and is still the
     * one the draft was made on top of (YouRec/ProjectRecovery.swift:383-397).
     * [hash] is the hash the draft recorded its base by.
     */
    fun draftApplies(current: ByteArray?, draftManifest: ByteArray, draftPrevious: String?, hash: (ByteArray) -> String = ::sha256): Boolean =
        !(current contentEquals draftManifest) && current?.let(hash) == draftPrevious

    /** Whether a saved project makes the pending draft redundant, so it can go (YouRec/ProjectRecovery.swift:362-369). */
    fun draftCommitted(committed: ByteArray, draftManifest: ByteArray): Boolean = committed contentEquals draftManifest
}
