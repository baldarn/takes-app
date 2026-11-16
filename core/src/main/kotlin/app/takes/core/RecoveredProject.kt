package app.takes.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * A separate project made from the original recordings of another, as the
 * original's "Create recovered project" and "Recover this recording into a new
 * project" make it (YouRec/ProjectStore.swift:747-805,
 * YouRec/OriginalRecordingsView.swift:60-199). The project the recordings come
 * from is never changed. Reading and repairing the movies is the app's; what
 * is copied, what the new project is called and how its timeline is laid out
 * are here.
 */
object RecoveredProject {
    /** The new project's title keeps this much of the old one before its suffix (YouRec/ProjectStore.swift:765). */
    const val TITLE_PREFIX = 95

    /** The longest name a recovered recording keeps of the original's (YouRec/ProjectStore.swift:790). */
    const val NAME_LENGTH = 255

    /** What the original counts as media among the files of a project's folder (YouRec/RecordingSalvage.swift:25). */
    val MEDIA_EXTENSIONS = setOf("mov", "mp4", "m4v", "m4a", "aac", "mp3", "wav", "aif", "aiff", "flac", "caf")

    /** A recording of the project's: its file, and the source it is when the project lists it. */
    data class Original(val file: File, val source: SourceMedia? = null) {
        /** What it is called: the name it was imported with, else its file's (YouRec/RecordingSalvage.swift:8). */
        val title: String get() = source?.originalName ?: file.name
    }

    /**
     * The original recordings of [project], whose folder is [folder], as the
     * original lists them (YouRec/RecordingSalvage.swift:13-46): the sources,
     * in the project's order, then the media in the folder the project does
     * not list (not hidden, not an effect's picture), then what was set aside
     * in [recovery] ([ProjectDisk.recoveryFiles]), each in order of name.
     * Unlike the original, a source whose file is missing is listed too, so
     * the person can see it is missing.
     */
    fun originals(project: Project, folder: File, recovery: List<File>): List<Original> {
        val known = project.mediaFiles.map { it.lowercase() }.toSet()
        val unlisted = folder.listFiles().orEmpty().filter { file ->
            val name = file.name.lowercase()
            name !in known && !name.startsWith(".") && !name.startsWith("effect-") && file.extension.lowercase() in MEDIA_EXTENSIONS &&
                Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        }.sortedBy { it.name }
        return project.sources.map { Original(File(folder, it.filename), it) } + unlisted.map { Original(it) } + recovery.map { Original(it) }
    }

    /**
     * The name the original gives the automatic repaired copy of a set-aside
     * recording called [name], in the project's folder (YouRec/ProjectStore.swift:529).
     */
    fun automaticCopy(name: String): String = "recovered-" + sha256(name.toByteArray()) + ".mov"

    /**
     * The recordings to copy into the new project (YouRec/ProjectStore.swift:753-759):
     * only [selected] when one is chosen; otherwise every one in the project's
     * [folder] itself, and each one set aside whose automatic repaired copy is
     * not already one of the project's sources.
     */
    fun chosen(originals: List<Original>, folder: File, selected: File? = null): List<Original> {
        val playableCopies = originals.mapNotNull { it.source?.filename }.toSet()
        return originals.filter { item ->
            if (selected != null) return@filter item.file == selected
            if (item.file.parentFile == folder) return@filter true
            automaticCopy(item.file.name) !in playableCopies
        }
    }

    /** Room the copy of recordings of these [sizes] may take: each one's salvage budget (YouRec/ProjectStore.swift:763). */
    fun estimate(sizes: List<Long>): Long = sizes.sumOf(RecoveryRules::copyBudget)

    /**
     * The title of the project recovered from one called [title], or from no
     * project ([fallback], the original's "Recordings"): the first 95
     * characters and [suffix], " · Recovered" in the person's language
     * (YouRec/ProjectStore.swift:765).
     */
    fun title(title: String?, suffix: String, fallback: String = "Recordings"): String = prefix(title ?: fallback, TITLE_PREFIX) + suffix

    /**
     * The source a recovered recording is in the new project
     * (YouRec/ProjectStore.swift:788-790): its new file, its readable length,
     * when the take was recorded (now for a file the project did not list),
     * whether it is sound only, and the name of the original kept as its name.
     */
    fun source(item: Original, id: String, filename: String, duration: Double, audioOnly: Boolean, now: Long = System.currentTimeMillis()): SourceMedia =
        SourceMedia(
            id = id, filename = filename, duration = duration,
            recordedAt = item.source?.recordedAt ?: now,
            audioOnly = if (audioOnly) true else null,
            originalName = prefix(item.title, NAME_LENGTH),
        )

    /**
     * [project] with [source] added as the original adds it
     * (YouRec/ProjectStore.swift:791-794): footage at the end of the
     * timeline, as a take is; a sound at the end of the edit on a track of
     * its own, as one Undo step.
     */
    fun adding(project: Project, source: SourceMedia, now: Long = System.currentTimeMillis()): Project {
        if (source.audioOnly != true) return project.addRecording(source, now)
        val added = project.addSource(source, now)
        val edit = TimelineEditing.addAudio(added.edit, source.id, source.duration, added.edit.duration) ?: return added
        return added.commit(edit, now)
    }

    /** What a recovery made: the new project's title, its recordings, how many were partly read, how many were left unread. */
    data class Outcome(val title: String, val recordings: Int, val partial: Int, val skipped: Int)

    /**
     * The words the original reports a recovery with (YouRec/ProjectStore.swift:801-803),
     * in English; the app says them in the person's language.
     */
    fun message(outcome: Outcome): String =
        "Created “" + outcome.title + "” with ${outcome.recordings} recordings." +
            (if (outcome.partial > 0) " Recovered readable portions of ${outcome.partial} files." else "") +
            (if (outcome.skipped > 0) " ${outcome.skipped} unreadable files remain with the original project." else "")

    /** The first [length] characters, without cutting a character made of two in half. */
    private fun prefix(text: String, length: Int): String {
        val cut = text.take(length)
        return if (cut.length < text.length && cut.lastOrNull()?.isHighSurrogate() == true) cut.dropLast(1) else cut
    }
}
