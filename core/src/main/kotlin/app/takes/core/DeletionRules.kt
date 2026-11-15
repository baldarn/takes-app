package app.takes.core

import java.time.Instant
import java.time.ZoneId

/**
 * The rules of Recently Deleted, as in the original (`ProjectDeletion.isOlderThan30Days` in
 * `YouRec/Project.swift:716-720` and the sort in `YouRec/ProjectStore.swift:287-300`).
 *
 * The age is counted from the day the project was moved to Recently Deleted, never from its last
 * edit: an old project deleted today does not expire.
 */
object DeletionRules {
    /**
     * Whether a project deleted at [deletedAt] has been in Recently Deleted for more than 30 days at
     * [now], both in milliseconds since 1970. The days are calendar days in [zone], so a change of
     * clock for summer time neither shortens nor lengthens them. Exactly 30 days is not more than 30
     * days, and a date in the future never qualifies.
     */
    fun isOlderThan30Days(deletedAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val cutoff = Instant.ofEpochMilli(now).atZone(zone).minusDays(30).toInstant().toEpochMilli()
        return deletedAt < cutoff
    }

    /**
     * What the order of Recently Deleted needs to know of a project: its [id], when it was deleted
     * if that can be read, and whether that date is the real one rather than when the app first saw
     * the project there.
     */
    data class Entry(val id: String, val date: Long?, val dateIsKnown: Boolean)

    /**
     * The order of Recently Deleted: projects with a known deletion date first, then the most
     * recently deleted first; projects without a readable date count as deleted at the beginning of
     * time. Equal dates go by id, so the order never changes between two looks.
     */
    fun <T> ordered(items: List<T>, entry: (T) -> Entry): List<T> =
        items.sortedWith(
            compareByDescending<T> { entry(it).dateIsKnown }
                .thenByDescending { entry(it).date ?: Long.MIN_VALUE }
                .thenBy { entry(it).id },
        )
}
