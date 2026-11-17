package app.takes.core

import kotlin.math.ceil

/**
 * How the sound processed by Enhance audio is kept, and trimmed, in the projects' `Cache` folders, as the
 * original keeps its processed audio (YouRec/SourceMediaCache.swift:68-122): every project's processed
 * files count together against a quota of a tenth of what is free and of what they hold, between 2 and
 * 8 GB; the cheapest to make again go first, then the oldest; and what is in use is never removed.
 * Working files left by an interrupted run go at launch, as the original wipes its `Processing` and
 * `Previews` folders (YouRec/ProjectStore.swift:136-142).
 */
object ProcessingCache {
    /** What processing keeps free beyond what it is about to write (YouRec/SourceMediaCache.swift:110, 119). */
    const val MARGIN = 256_000_000L

    const val MINIMUM_QUOTA = 2_000_000_000L
    const val MAXIMUM_QUOTA = 8_000_000_000L

    /** The most the processed files may hold with [available] bytes free and [held] bytes in them (YouRec/SourceMediaCache.swift:106-108). */
    fun quota(available: Long, held: Long): Long = maxOf(MINIMUM_QUOTA, minOf(MAXIMUM_QUOTA, available / 10 + held / 10))

    /**
     * Room made before [seconds] of a recording are processed through a chain of [stages] stages: eight bytes a
     * frame at 48 kHz for each stage and three more (YouRec/SourceMediaCache.swift:344, 452).
     */
    fun required(seconds: Double, stages: Int): Long = ceil(maxOf(0.0, seconds) * 48_000 * 8 * (stages + 3)).toLong()

    /**
     * How much a processed file is worth keeping: a hundred for each neural stage of its chain, and ten more when
     * its last stage is neural, so that changing the tone does not run a model again (YouRec/SourceMediaCache.swift:446-450).
     * [neural] says, stage by stage, whether it is a model. A stretch processed for listening is worth nothing.
     */
    fun priority(neural: List<Boolean>): Int = neural.count { it } * 100 + if (neural.lastOrNull() == true) 10 else 0

    // A recording's name, then the chain: RNNoise alone at a strength, or a digest of 12 bytes; then a stretch's span.
    private val PROCESSED = Regex("""^[^.].*\.(clean-\d+|chain-[0-9a-f]{24})(\.window-\d+-\d+)?\.wav$""")

    /** Whether [name] is a file of processed sound, which may be trimmed. */
    fun isProcessed(name: String): Boolean = PROCESSED.matches(name)

    /** Whether [name] is a hidden file being written, which nothing reads until it is renamed, left over when the app stops. */
    fun isWorking(name: String): Boolean = name.startsWith(".") && name.endsWith(".tmp")

    /** The files kept beside a processed file, which go with it (YouRec/SourceMediaCache.swift:114-117). */
    val COMPANIONS = listOf(".priority", ".peaks")

    /** A processed file: its [path], its size, when it was last made or used, its [priority], and whether it is in use. */
    data class Entry(val path: String, val bytes: Long, val modified: Long, val priority: Int, val pinned: Boolean = false)

    /** What [trim] removes, in order, and whether there is room afterwards for what is to be written. */
    data class Trim(val remove: List<Entry>, val enough: Boolean)

    /**
     * The processed files to remove, with [available] bytes free, before [required] bytes are written
     * (YouRec/SourceMediaCache.swift:106-122): the lowest [Entry.priority] first, the least recently used of
     * equal ones first, never one in use, until the files are within the [quota] and [required] and the
     * [MARGIN] are free.
     */
    fun trim(entries: List<Entry>, available: Long, required: Long): Trim {
        var held = entries.sumOf { it.bytes }
        var free = available
        val quota = quota(available, held)
        val removed = mutableListOf<Entry>()
        for (entry in entries.sortedWith(compareBy<Entry> { it.priority }.thenBy { it.modified })) {
            if (entry.pinned) continue
            if (held <= quota && free >= required + MARGIN) break
            removed += entry
            held -= entry.bytes
            free += entry.bytes
        }
        return Trim(removed, free >= required + MARGIN)
    }
}
