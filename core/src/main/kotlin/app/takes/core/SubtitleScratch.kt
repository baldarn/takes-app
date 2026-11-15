package app.takes.core

import java.io.File

/**
 * The temporary folders where a transcription keeps the decoded sound of a project, in the app's cache.
 *
 * A job removes its folder when it ends. One that dies with the app cannot, and an hour of sound is about
 * 230 MB: so the folders that no running job owns are removed when the app starts and before each job.
 * The original keeps its speech samples in memory and has nothing to clear.
 */
object SubtitleScratch {
    private const val PREFIX = "subtitles-"
    private val running = mutableSetOf<File>()

    /** A new empty folder in [cache] for one job, which [close] removes. */
    fun open(cache: File): File = synchronized(running) {
        File(cache, PREFIX + newID()).also { it.mkdirs(); running += it.absoluteFile }
    }

    fun close(folder: File) {
        folder.deleteRecursively()
        synchronized(running) { running -= folder.absoluteFile }
    }

    /** Removes from [cache] the folders of jobs that are not running, and tells how many there were. */
    fun clear(cache: File): Int {
        // Listed and chosen under the lock, so a folder opened meanwhile is either known or not listed yet.
        val left = synchronized(running) {
            cache.listFiles { file -> file.isDirectory && file.name.startsWith(PREFIX) && file.absoluteFile !in running }.orEmpty().toList()
        }
        left.forEach { it.deleteRecursively() }
        return left.size
    }
}
