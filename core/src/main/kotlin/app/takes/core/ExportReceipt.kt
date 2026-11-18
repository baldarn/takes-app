package app.takes.core

import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.UUID
import kotlin.math.log10
import kotlin.math.pow

/**
 * What is written beside a finished movie, as `<movie name>.json`: the edit
 * it was made from, the recordings it used and the format it has. A movie
 * without a readable receipt is not offered as an export.
 */
@Serializable
data class ExportReceipt(
    /** 2 since the peak is measured only where the edit can raise a level (YouRec/Project.swift:711, 726-727). */
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: String = newID(),
    val createdAt: Long = System.currentTimeMillis(),
    /** The movie's file name inside `Exports`. */
    val filename: String,
    val projectID: String,
    val projectTitle: String,
    /** The step of the project's history that was exported. */
    val historyRevision: Int,
    val edit: EditState,
    /** The recordings and sounds the edit uses, as they were. */
    val sources: List<SourceMedia>,
    /** SHA-256 of each of those files, by source id. */
    val sourceChecksums: Map<String, String>,
    val width: Int,
    val height: Int,
    val framesPerSecond: Int = 30,
    val settings: ExportSettings? = null,
    /** The codec the movie really has, which HDR can force away from the chosen one. */
    val codec: ExportSettings.Codec? = null,
    /**
     * The loudest sample of this movie's finished mix, 1 being full scale,
     * where the edit can raise a level ([ExportAudioLevel.checkRanges]); a
     * receipt of version 1 holds the loudest of the whole movie. Null when it
     * was not measured, as for a movie whose edit raises no level: such a
     * movie makes no claim about its sound.
     */
    val audioPeak: Float? = null,
) {
    /** The movie's length in seconds: that of the edit it was made from. */
    val duration: Double get() = edit.duration

    /**
     * Set when the edited mix went 0.5 dB or more over full scale and the movie may distort (Project.swift:728-740).
     * A receipt of version 1 measured the whole movie: its peak is laid to the edit only when the edit could have
     * raised the level of all of it.
     */
    val audioWarning: AudioWarning?
        get() {
            val warning = AudioWarning.of(audioPeak) ?: return null
            if (schemaVersion < 2) {
                val timeline = edit.toTimeline()
                val whole = EditInterval(EditTime.ZERO, timeline.duration)
                if (ExportAudioLevel.checkRanges(timeline, sources) != listOf(whole)) return null
            }
            return warning
        }

    /** By how much the loudest peak of a movie exceeds full scale. */
    data class AudioWarning(val excessDB: Double) {
        /** One decimal, with a point in every language, as the original prints it. */
        val decibels: String get() = String.format(Locale.ROOT, "%.1f", excessDB)
        val text: String get() = "The edited audio peaks $decibels dB above full scale. Lower the volume of the affected clips and export again."

        companion object {
            /** A small overshoot of decoding or resampling is no warning: 0.5 dB over full scale (Project.swift:730). */
            val THRESHOLD: Float = 10.0.pow(0.5 / 20).toFloat()

            /** No warning without a measurement, nor below [THRESHOLD]; the excess as it is (Project.swift:730-731). */
            fun of(peak: Float?): AudioWarning? {
                if (peak == null || !peak.isFinite() || peak < THRESHOLD) return null
                return AudioWarning(20 * log10(peak.toDouble()))
            }
        }
    }

    companion object {
        /** The version receipts are written at. */
        const val SCHEMA_VERSION = 2
    }
}

/** The finished movies of a project and their receipts, in its `Exports` folder. */
object ExportReceipts {
    private val pretty = kotlinx.serialization.json.Json(documentJson) { prettyPrint = true }

    fun exports(folder: File): File = File(folder, ExportCheckpoint.EXPORTS)

    /** The movie a receipt belongs to. */
    fun movie(folder: File, receipt: ExportReceipt): File = File(exports(folder), receipt.filename)

    /** The size of the movie in bytes, or null when it is gone. */
    fun bytes(folder: File, receipt: ExportReceipt): Long? = movie(folder, receipt).takeIf { it.isFile }?.length()

    /** The receipt file of a movie: the same name, ending in `.json`. */
    fun receiptFile(movie: File): File = File(movie.parentFile, movie.nameWithoutExtension + ".json")

    /** The sources an edit plays: those of its clips and of the sound on its own tracks, in the project's order. */
    fun usedSources(project: Project): List<SourceMedia> {
        val edit = project.edit
        val ids = edit.clips.map { it.sourceID }.toSet() + edit.audio.orEmpty().map { it.sourceID }
        return project.sources.filter { it.id in ids }
    }

    /**
     * The exports of a project, newest first. A receipt counts only if it
     * can be read, names a file directly inside `Exports`, and that movie is
     * still there.
     */
    fun list(folder: File): List<ExportReceipt> {
        val directory = exports(folder)
        return directory.listFiles().orEmpty().filter { it.isFile && it.extension == "json" }.mapNotNull { file ->
            val receipt = try {
                documentJson.decodeFromString(ExportReceipt.serializer(), file.readText())
            } catch (_: Exception) {
                return@mapNotNull null
            }
            receipt.takeIf { plainName(it.filename) && File(directory, it.filename).isFile }
        }.sortedByDescending { it.createdAt }
    }

    private fun plainName(name: String) = name.isNotEmpty() && name == File(name).name && !name.contains('\\') && name != "." && name != ".."

    /**
     * The export that already shows [project] as it is now: the same
     * settings, the same edit and the same recordings, with its movie still
     * on disk. While there is one, the screen says "Exported" instead of
     * offering to export again.
     */
    fun matching(project: Project, receipts: List<ExportReceipt>, folder: File): ExportReceipt? {
        val sources = usedSources(project)
        val ids = sources.map { it.id }.toSet()
        val edit = comparable(project.edit)
        return receipts.firstOrNull { receipt ->
            (receipt.settings ?: ExportSettings()) == project.exportSettings && same(comparable(receipt.edit), edit) &&
                receipt.sources.filter { it.id in ids } == sources && movie(folder, receipt).isFile
        }
    }

    // How many takes an edit had seen is bookkeeping of the running app, not part of the movie.
    private fun comparable(edit: EditState) = edit.copy(captureBase = null)

    /**
     * Whether a receipt's edit is the project's: equal, or the same timeline
     * ([Project.commit] compares edits so). A receipt written before the
     * project saved timelines holds the edit as it was saved then, which an
     * edit committed since shows with its numbers settled
     * ([TrackSequenceForm]): both sides are compared settled, as a timeline
     * read back.
     */
    private fun same(receipt: EditState, edit: EditState): Boolean = receipt == edit || settled(receipt) == settled(edit)

    private fun settled(edit: EditState): EditState = EditSnapshot.saved(edit).let { it.toTimeline().toSequence() ?: it }

    /** The movie the screen offers: the one of the current edit, else the newest, marked as a previous export. */
    data class Selection(val receipt: ExportReceipt, val current: Boolean)

    fun select(project: Project, receipts: List<ExportReceipt>, folder: File): Selection? =
        matching(project, receipts, folder)?.let { Selection(it, true) } ?: receipts.firstOrNull()?.let { Selection(it, false) }

    /** The receipt of a movie just rendered from [project]. */
    fun receipt(
        project: Project, filename: String, checksums: Map<String, String>, width: Int, height: Int,
        codec: ExportSettings.Codec, audioPeak: Float?, now: Long = System.currentTimeMillis(), id: String = newID(),
    ): ExportReceipt = ExportReceipt(
        id = id, createdAt = now, filename = filename, projectID = project.id, projectTitle = project.title,
        historyRevision = project.historyCursor, edit = comparable(project.edit), sources = usedSources(project),
        sourceChecksums = checksums, width = width, height = height, framesPerSecond = project.exportSettings.frameRate,
        settings = project.exportSettings, codec = codec, audioPeak = audioPeak,
    )

    /** A name for a new movie that cannot collide with another. */
    fun newMovie(folder: File): File = File(exports(folder), UUID.randomUUID().toString().uppercase() + ".mp4")

    /** True when [destination] is free for a new movie and its receipt. */
    fun isFree(destination: File): Boolean = !destination.exists() && !receiptFile(destination).exists()

    /**
     * Publishes a finished movie: [rendered], a complete file under
     * `Processing`, becomes [destination] with [receipt] beside it.
     *
     * The receipt goes first. A crash in between leaves a receipt without a
     * movie, which [list] ignores; it never leaves a movie whose edit is not
     * recorded. If the movie cannot be moved, the receipt is taken back.
     */
    fun publish(receipt: ExportReceipt, rendered: File, destination: File) {
        if (!isFree(destination)) throw FileAlreadyExistsException(destination)
        val directory = destination.parentFile
        if (!directory.mkdirs() && !directory.isDirectory) throw IOException("The export could not be saved.")
        val receiptFile = receiptFile(destination)
        val writing = File(rendered.parentFile, UUID.randomUUID().toString().uppercase() + ".json")
        try {
            writing.writeText(pretty.encodeToString(ExportReceipt.serializer(), receipt))
            Files.move(writing.toPath(), receiptFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            writing.delete()
        }
        try {
            sync(receiptFile)
            sync(rendered)
            Files.move(rendered.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (error: Exception) {
            receiptFile.delete()
            throw error
        }
    }

    // Opened without creating: a movie that is not there must fail, not appear empty.
    private fun sync(file: File) = FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { it.force(true) }

    /** The exports removed by [retire] and the folder their files now wait in to be deleted. */
    data class Retired(val ids: Set<String>, val scratch: File?)

    /**
     * Once a new movie is published, the earlier ones go: only the newest
     * export of a project is kept. Their files and the used checkpoint are
     * moved into one folder under `Processing`, which the caller deletes in
     * the background; [ExportCheckpoint.reclaim] finishes the job after a crash.
     *
     * A movie [inUse], such as one still being copied to the gallery, stays.
     * If nothing can be moved, nothing is retired and every movie stays usable.
     */
    fun retire(previous: List<ExportReceipt>, folder: File, checkpoint: ExportCheckpoint, inUse: (File) -> Boolean = { false }): Retired {
        val scratch = try { checkpoint.detachCompletedFiles() } catch (_: Exception) { return Retired(emptySet(), null) }
        val retired = mutableSetOf<String>()
        for (receipt in previous) {
            val movie = movie(folder, receipt)
            if (inUse(movie)) continue
            val retiredMovie = File(scratch, receipt.id + ".mp4")
            try { Files.move(movie.toPath(), retiredMovie.toPath(), StandardCopyOption.ATOMIC_MOVE) } catch (_: Exception) { continue }
            try { Files.move(receiptFile(movie).toPath(), receiptFile(retiredMovie).toPath(), StandardCopyOption.ATOMIC_MOVE) } catch (_: Exception) {}
            retired += receipt.id
        }
        return Retired(retired, scratch)
    }

    /** The list after [published] replaced the [retired] exports: the new movie first. */
    fun afterPublishing(published: ExportReceipt, previous: List<ExportReceipt>, retired: Set<String>): List<ExportReceipt> =
        listOf(published) + previous.filter { it.id != published.id && it.id !in retired }
}
