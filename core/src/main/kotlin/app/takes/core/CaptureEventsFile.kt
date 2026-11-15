package app.takes.core

import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * The events of a take, kept beside its movie while it is recorded, as the
 * original app does (YouRec/CaptureSegmentWriter.swift:116-122): a JSON list
 * in `<movie name>.events.json`, written again in full at each event, so the
 * marks and camera changes of a take that is interrupted are found with it
 * when the take is indexed (YouRec/ProjectStore.swift:351-359).
 *
 * The list has the original's shape: `id`, `time`, `kind`, `detail`.
 */
object CaptureEventsFile {
    private val format = ListSerializer(CaptureEvent.serializer())

    /** The file that holds the events of [movie], in the same folder. */
    fun beside(movie: File): File = File(movie.parentFile, movie.nameWithoutExtension + ".events.json")

    /** Replaces the events saved for [movie]. The file either keeps the old list or holds all of the new one. */
    fun write(events: List<CaptureEvent>, movie: File) =
        ProjectDisk.writeAtomically(documentJson.encodeToString(format, events).toByteArray(), beside(movie))

    /**
     * The events saved for [movie] that fall inside its [duration], or null
     * when there are none: no file, a file that cannot be read, or nothing
     * left inside the movie. The file is left in place in every case.
     */
    fun read(movie: File, duration: Double): List<CaptureEvent>? = try {
        val file = beside(movie)
        if (!file.isFile) null
        else CaptureEvent.recovered(documentJson.decodeFromString(format, file.readText()), duration).ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    /** Removes the events saved for [movie], for a movie that holds nothing and is removed too. */
    fun delete(movie: File) {
        beside(movie).delete()
    }
}
