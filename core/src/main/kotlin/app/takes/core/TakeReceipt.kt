package app.takes.core

import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * What the recorder notes beside a segment of a take as it begins, as the original's take receipt
 * (YouRec/ProjectRecovery.swift:229-353): the project, the take and the segment, where in the edit the
 * take was going and on top of which state of it, so that a take whose app stopped before it joined the
 * project is put back where it belongs, or kept aside when the edit has changed since. A reserve of 4 MB
 * is written with it, and given back when the segment ends, so that there is room to finish the file.
 *
 * Takes' receipt also carries the recording gain of the take's microphone, which its clip is given.
 */
@Serializable
data class TakeReceipt(
    val version: Int = 1,
    val projectID: String,
    val takeID: String,
    val segmentID: String,
    val sequence: Int,
    val filename: String,
    val started: Long,
    /** Where in the edit the take was going; null at the end. */
    val insertion: Double? = null,
    /** The history step the edit was at, by the name of its snapshot, when the take was going into it. */
    val revision: String? = null,
    /** How many takes the project's ledger held then. */
    val captureCount: Int = 0,
    val gainDB: Double = 0.0,
    val indexed: Boolean = false,
) {
    companion object {
        /** The folder of a project that holds the receipts, as in the original. */
        const val FOLDER = ".capture-receipts"

        /** The room kept for a segment to finish its file (YouRec/ProjectRecovery.swift:297). */
        const val RESERVE_BYTES = 4 * 1024 * 1024

        /** The receipt of the recording [movie] (YouRec/ProjectRecovery.swift:255-258). */
        fun location(movie: File): File = File(File(movie.parentFile, FOLDER), movie.nameWithoutExtension + ".json")

        fun reserve(movie: File): File = File(File(movie.parentFile, FOLDER), movie.nameWithoutExtension + ".reserve")

        /**
         * The receipt of a segment of [project] about to be written to [movie]
         * (YouRec/ProjectRecovery.swift:259-291): with the place the takes go and the state of the edit
         * when one is chosen, else none.
         */
        fun of(project: Project, movie: File, takeID: String, segmentID: String, sequence: Int, started: Long, gainDB: Double): TakeReceipt {
            val insertion = project.recordingInsertion
            return TakeReceipt(
                projectID = project.id, takeID = takeID, segmentID = segmentID, sequence = sequence, filename = movie.name, started = started,
                insertion = insertion,
                revision = if (insertion == null) null else project.snapshots.getOrNull(project.historyCursor)?.filename(),
                captureCount = if (insertion == null) 0 else project.captureLedger.size,
                gainDB = gainDB,
            )
        }

        /** Writes [receipt] beside [movie] with its reserve; nothing is left when either cannot be written (YouRec/ProjectRecovery.swift:292-303). */
        fun write(receipt: TakeReceipt, movie: File) {
            val file = location(movie)
            if (!file.parentFile.isDirectory && !file.parentFile.mkdirs()) throw java.io.IOException("Cannot create ${file.parentFile.path}")
            ProjectDisk.writeAtomically(documentJson.encodeToString(serializer(), receipt).toByteArray(), file)
            try {
                ProjectDisk.writeAtomically(ByteArray(RESERVE_BYTES) { 0xad.toByte() }, reserve(movie))
            } catch (problem: Exception) {
                file.delete()
                throw problem
            }
        }

        /**
         * The receipt of [movie] in project [projectID], or null when it has none; throws when it is not the
         * movie's own (YouRec/ProjectRecovery.swift:310-317, [RecoveryRules.receiptMatches]).
         */
        fun read(movie: File, projectID: String): TakeReceipt? {
            val file = location(movie)
            if (!file.isFile) return null
            val receipt = documentJson.decodeFromString(serializer(), file.readText())
            if (!RecoveryRules.receiptMatches(receipt.version, receipt.projectID, projectID, receipt.filename, movie.name, receipt.sequence, receipt.segmentID, movie.extension)) {
                throw ProjectException(ProjectException.Reason.InvalidDocument)
            }
            return receipt
        }

        /** Gives back the room kept for [movie]'s segment, which has ended (YouRec/ProjectRecovery.swift:346-352). */
        fun releaseReserve(movie: File) {
            reserve(movie).delete()
        }

        /** Removes [movie]'s receipt and reserve, once the take is in its project. */
        fun remove(movie: File) {
            releaseReserve(movie)
            location(movie).delete()
        }

        /**
         * Removes the reserves of the project folder [folder] that no segment being written holds ([live]), as
         * the original does at launch (YouRec/ProjectRecovery.swift:327-345). How many of them had no movie:
         * takes stopped before a movie file was there.
         */
        fun reclaimAbandonedReserves(folder: File, live: Set<File> = emptySet()): Int {
            var missingMovies = 0
            val liveNames = live.map { it.canonicalPath }.toSet()
            for (file in File(folder, FOLDER).listFiles().orEmpty()) {
                if (!RecoveryRules.isReclaimableReserve(file.name, file.canonicalPath in liveNames)) continue
                if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) continue
                if (!file.delete()) continue
                val stem = file.name.removeSuffix(".reserve")
                if (MOVIE_EXTENSIONS.none { File(folder, "$stem.$it").exists() }) missingMovies++
            }
            return missingMovies
        }

        private val MOVIE_EXTENSIONS = listOf("mp4", "mov")
    }
}

/**
 * [Project] with the take [source] found after the app stopped, placed as the original indexes one
 * (YouRec/Project.swift:375-393): [placed] says whether it went into the edit, or only joined the project's
 * recordings, for the person to place from Original recordings.
 */
data class IndexedTake(val project: Project, val placed: Boolean)

/**
 * Indexes [source], a take found in [project]'s folder that its manifest does not list, by its [receipt]
 * (YouRec/Project.swift:375-393): a sound or a take whose placement is [uncertain] or already done only joins the
 * recordings; a take that was going to the end goes there; one that was going into the edit goes there only while
 * the edit is still where it was when the take began. A take without a receipt goes where the next take would.
 */
fun Project.indexTake(source: SourceMedia, receipt: TakeReceipt?, uncertain: Boolean, videoEffects: List<VideoEffectSettings>? = null): IndexedTake {
    if (sources.any { it.id == source.id || it.filename == source.filename }) return IndexedTake(this, false)
    if (source.audioOnly == true || uncertain || receipt?.indexed == true) return IndexedTake(addSource(source), false)
    if (receipt != null) {
        if (!receipt.projectID.equals(id, ignoreCase = true) || !receipt.segmentID.equals(source.id, ignoreCase = true)) {
            throw ProjectException(ProjectException.Reason.InvalidDocument)
        }
        if (receipt.insertion == null) {
            // At the end, whatever place takes go to now.
            val appended = copy(recordingInsertion = null, recordingInsertionStep = null).addRecording(source, gainDB = receipt.gainDB, videoEffects = videoEffects)
            return IndexedTake(appended.copy(recordingInsertion = recordingInsertion, recordingInsertionStep = recordingInsertionStep), true)
        }
        val revision = snapshots.getOrNull(historyCursor)?.filename()
        if (recordingInsertion != receipt.insertion || revision != receipt.revision || captureLedger.size != receipt.captureCount) {
            return IndexedTake(addSource(source), false)
        }
        return IndexedTake(addRecording(source, gainDB = receipt.gainDB, videoEffects = videoEffects), true)
    }
    return IndexedTake(addRecording(source, videoEffects = videoEffects), true)
}
