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
import kotlin.math.ceil
import kotlin.math.log10

/**
 * What is written beside a finished movie, as `<movie name>.json`: the edit
 * it was made from, the recordings it used and the format it has. A movie
 * without a readable receipt is not offered as an export.
 */
@Serializable
data class ExportReceipt(
    val schemaVersion: Int = 1,
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
     * The loudest sample of this movie's finished mix, 1 being full scale.
     * Null when it was not measured: such a movie makes no claim about its sound.
     */
    val audioPeak: Float? = null,
) {
    /** The movie's length in seconds: that of the edit it was made from. */
    val duration: Double get() = edit.duration

    /** Set when the mix went over full scale and the movie may distort. */
    val audioWarning: AudioWarning? get() = AudioWarning.of(audioPeak)

    /** By how much the loudest peak of a movie exceeds full scale. */
    data class AudioWarning(val excessDB: Double) {
        /** One decimal, with a point in every language, as the original prints it. */
        val decibels: String get() = String.format(Locale.ROOT, "%.1f", excessDB)
        val text: String get() = "Peaks are $decibels dB above full scale. Lower Volume in the editor and export again."

        companion object {
            /** A peak barely over full scale is rounding, not clipping. */
            const val THRESHOLD = 1.0001f

            /**
             * No warning without a measurement, nor at or below [THRESHOLD].
             * Above it the excess is rounded up to a tenth of a decibel and is
             * never shown as less than 0.1 dB.
             */
            fun of(peak: Float?): AudioWarning? {
                if (peak == null || !peak.isFinite() || peak <= THRESHOLD) return null
                return AudioWarning(maxOf(0.1, ceil(20 * log10(peak.toDouble()) * 10) / 10))
            }
        }
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
            (receipt.settings ?: ExportSettings()) == project.exportSettings && comparable(receipt.edit) == edit &&
                receipt.sources.filter { it.id in ids } == sources && movie(folder, receipt).isFile
        }
    }

    // How many takes an edit had seen is bookkeeping of the running app, not part of the movie.
    private fun comparable(edit: EditState) = edit.copy(captureBase = null)

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
