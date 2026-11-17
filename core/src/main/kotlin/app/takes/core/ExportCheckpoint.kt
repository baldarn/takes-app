package app.takes.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor

/**
 * The arithmetic of an export made in sections: the picture is encoded twelve
 * seconds at a time, and every finished section is kept, so an interrupted
 * export goes on from the last complete one.
 *
 * Times are counted in ticks of 1/240 000 s, which every supported frame rate
 * divides exactly, so sections and pictures never disagree by a rounding.
 */
object ExportSections {
    const val SECONDS = 12
    const val TICKS_PER_SECOND = 240_000L
    private const val SECTION_TICKS = SECONDS * TICKS_PER_SECOND
    private const val LONGEST = 604_800.0

    data class Section(val index: Int, val startTicks: Long, val lengthTicks: Long) {
        val start: Double get() = startTicks.toDouble() / TICKS_PER_SECOND
        val length: Double get() = lengthTicks.toDouble() / TICKS_PER_SECOND
        val end: Double get() = (startTicks + lengthTicks).toDouble() / TICKS_PER_SECOND
    }

    /** [seconds] on the tick clock, rounded to the nearest tick. */
    fun ticks(seconds: Double): Long {
        if (!seconds.isFinite()) return 0
        val scaled = minOf(LONGEST, maxOf(-LONGEST, seconds)) * TICKS_PER_SECOND
        return if (scaled < 0) -floor(-scaled + 0.5).toLong() else floor(scaled + 0.5).toLong()
    }

    /** The length of one picture at [framesPerSecond], which must divide the tick clock. */
    fun frameTicks(framesPerSecond: Int): Long {
        require(framesPerSecond > 0 && TICKS_PER_SECOND % framesPerSecond == 0L) { "Unsupported frame rate" }
        return TICKS_PER_SECOND / framesPerSecond
    }

    /** The sections of a movie of [duration] seconds: twelve seconds each, the last one whatever is left. */
    fun sections(duration: Double): List<Section> {
        val total = ticks(duration)
        val result = mutableListOf<Section>()
        var start = 0L
        while (start < total) {
            val length = minOf(SECTION_TICKS, total - start)
            result += Section(result.size, start, length)
            start += length
        }
        return result
    }

    /** The pictures a stretch of [lengthTicks] holds: one for every frame tick that begins inside it. */
    fun pictures(lengthTicks: Long, framesPerSecond: Int): Int {
        val frame = frameTicks(framesPerSecond)
        return ((lengthTicks + frame - 1) / frame).toInt()
    }

    /** The pictures of the whole movie. */
    fun pictures(duration: Double, framesPerSecond: Int): Int = pictures(ticks(duration), framesPerSecond)

    /**
     * Whether a saved section holding [pictures] pictures covers its whole
     * length and no more. A section that stops one picture short (an encoder
     * may end at its last source frame) is encoded again; the others are kept.
     */
    fun isComplete(pictures: Int, lengthTicks: Long, framesPerSecond: Int): Boolean {
        val frame = frameTicks(framesPerSecond)
        val covered = pictures * frame
        return covered >= lengthTicks && covered - frame < lengthTicks
    }

    /**
     * Whether a section may be joined into the movie: it has pictures, is at
     * most one picture short, and every section but the last is exactly full.
     */
    fun canJoin(pictures: Int, lengthTicks: Long, framesPerSecond: Int, last: Boolean): Boolean {
        val frame = frameTicks(framesPerSecond)
        val covered = pictures * frame
        return pictures > 0 && covered >= lengthTicks - frame && (last || covered == lengthTicks)
    }

    /**
     * The sections one run of the encoder makes, from the first of [missing] to the last of them, of the movie's
     * [all] sections: the encoder is started once for them and its output cut into section files at their
     * boundaries ([Run]). A saved section between two missing ones is drawn again and not kept.
     */
    fun run(all: List<Section>, missing: List<Section>, framesPerSecond: Int): Run? {
        if (missing.isEmpty()) return null
        return Run(all.subList(missing.first().index, missing.last().index + 1), framesPerSecond)
    }

    /**
     * Consecutive [sections] made by one run of the encoder: rendered as one stretch ([span]), whose frames are
     * numbered from zero, and cut at the section boundaries into one file each. Every section but the last of the
     * movie is a whole number of frames long, so a boundary falls on a frame.
     */
    class Run(val sections: List<Section>, private val framesPerSecond: Int) {
        init {
            require(sections.isNotEmpty() && sections.zipWithNext().all { (a, b) -> b.index == a.index + 1 && b.startTicks == a.startTicks + a.lengthTicks })
        }

        /** The stretch of the movie the run renders. */
        val span: Section = Section(sections.first().index, sections.first().startTicks, sections.sumOf { it.lengthTicks })

        /** The frame of the run each section begins at. */
        private val firsts: List<Int> = sections.map { ((it.startTicks - span.startTicks) / frameTicks(framesPerSecond)).toInt() }

        /** The frames of the run. */
        val frames: Int = pictures(span.lengthTicks, framesPerSecond)

        /** The position in [sections] of the one that frame [frame] of the run belongs to, or null outside the run. */
        fun position(frame: Int): Int? = if (frame < 0 || frame >= frames) null else firsts.indexOfLast { it <= frame }

        /** The time in the run, in microseconds, at which the section at [position] begins: its frames are timed from it. */
        fun beginsMicros(position: Int): Long = Math.round(firsts[position] * 1e6 / framesPerSecond)

        /** The frame of the run, the nearest one, at time [micros] of the run. */
        fun frame(micros: Long): Int = Math.round(micros * framesPerSecond / 1e6).toInt()
    }

    /** Seconds of picture that are safe when the first [complete] sections are, in a movie of [total] seconds. */
    fun savedSeconds(complete: Int, total: Double): Double {
        var index = 0
        while (index < complete && (index * SECONDS).toDouble() < total) index += 1
        return minOf(total, (index * SECONDS).toDouble())
    }

    /**
     * Whether a finished movie has the length that was asked for: within
     * 0.12 s, or two pictures when those are longer.
     *
     * A file is as long as its longest track, and encoded sound ends on a
     * whole packet, with the encoder's own lead before it. At the usual
     * rates a packet is about a fiftieth of a second and disappears in the
     * tolerance; a recording made at 8 or 16 kHz (the emulator's microphone,
     * a Bluetooth headset) keeps its rate in the movie, where a packet of
     * [soundPacket] seconds is a sixteenth or an eighth of a second. Two of
     * them and a picture are then allowed.
     */
    fun durationMatches(actual: Double, expected: Double, framesPerSecond: Int, soundPacket: Double = 0.0): Boolean =
        actual.isFinite() && abs(actual - expected) <= maxOf(0.12, 2.0 / framesPerSecond, 2 * soundPacket + 1.0 / framesPerSecond)

    /**
     * The second picture read back from a finished movie to prove it decodes
     * to its end: the last one. A movie of a tenth of a second or less is
     * checked at its first picture only.
     */
    fun lastPictureTime(actual: Double, expected: Double, framesPerSecond: Int): Double? =
        if (actual > 0.1) maxOf(0.0, minOf(actual, expected) - 1.0 / framesPerSecond) else null
}

/**
 * The saved work of one interrupted export, in `Exports/.paused-export` of a
 * project's folder. Only finished stages are put here; an encoder always
 * writes to `Processing` and its file is moved in when it is complete.
 *
 * The folder holds a ticket naming the project state it belongs to, the
 * measured sound peak, the encoded sound and the picture sections.
 */
class ExportCheckpoint(folder: File) {
    /** What the saved work was made from. Any difference makes it worthless. */
    @Serializable
    private data class Ticket(
        val projectID: String,
        val revision: Long,
        val historyCursor: Int,
        // Absent for the default format, as in the first checkpoints of the original.
        val settings: ExportSettings? = null,
    ) {
        constructor(project: Project) : this(
            project.id, project.updatedAt, project.historyCursor,
            project.exportSettings.takeIf { it != ExportSettings() },
        )
    }

    val directory = File(File(folder, EXPORTS), PAUSED)
    private val projectFolder = folder
    private val ticketFile get() = File(directory, "ticket.json")
    private val pauseReasonFile get() = File(directory, "pause-reason.txt")
    private val peakFile get() = File(directory, "peak.json")

    /** The sound of the whole movie, encoded once so it stays continuous across picture sections. */
    val audioFile: File get() = File(directory, "sound.mp4")

    /** Where picture section [index] is kept once it is complete. */
    fun section(index: Int): File = File(directory, "picture-$index.mp4")

    /**
     * Whether the saved work was made from exactly this state of [project]:
     * the same project, the same save, the same step of its history and the
     * same export settings. An edit, an undo, a rename or another format all
     * make it false.
     */
    fun matches(project: Project): Boolean {
        val ticket = try {
            documentJson.decodeFromString(Ticket.serializer(), ticketFile.readText())
        } catch (_: Exception) {
            return false
        }
        return ticket == Ticket(project)
    }

    /** Gets the folder ready for an export of [project]: kept when it [matches], emptied and given a new ticket otherwise. */
    fun prepare(project: Project) {
        if (matches(project)) return
        discard()
        if (!directory.mkdirs() && !directory.isDirectory) throw ExportProblem(ExportProblem.Reason.NotPrepared)
        writeAtomically(ticketFile, documentJson.encodeToString(Ticket.serializer(), Ticket(project)))
    }

    /** Why the export stopped, when it was not the person's doing; shown until it is resumed. */
    var pauseReason: String?
        get() = try { pauseReasonFile.readText() } catch (_: Exception) { null }
        set(reason) {
            try {
                if (reason != null) writeAtomically(pauseReasonFile, reason) else pauseReasonFile.delete()
            } catch (_: Exception) {
                // The reason is a courtesy; the export itself is unaffected.
            }
        }

    /** Removes the saved work. */
    fun discard() {
        if (directory.exists() && !directory.deleteRecursively()) throw ExportProblem(ExportProblem.Reason.PausedNotRemoved)
    }

    /**
     * Moves the saved work out of the way, into a folder of its own under
     * `Processing`, and returns that folder for the caller to delete at
     * leisure. A new export can use the slot at once.
     */
    fun detachCompletedFiles(): File {
        val scratch = File(projectFolder, PROCESSING)
        scratch.mkdirs()
        val retired = File(scratch, UUID.randomUUID().toString().uppercase())
        Files.move(directory.toPath(), retired.toPath(), StandardCopyOption.ATOMIC_MOVE)
        return retired
    }

    /** The loudest sample of the finished mix, measured before encoding; null until it has been measured. */
    val measuredPeak: Float?
        get() = try {
            documentJson.decodeFromString(Float.serializer(), peakFile.readText())
        } catch (_: Exception) {
            null
        }

    fun savePeak(peak: Float) = writeAtomically(peakFile, documentJson.encodeToString(Float.serializer(), peak))

    /** How many sections are saved without a gap, counting from the first. */
    fun completeSections(total: Double): Int {
        var index = 0
        while ((index * ExportSections.SECONDS).toDouble() < total && section(index).isFile) index += 1
        return index
    }

    /** Seconds of picture that will not be rendered again. */
    fun savedSeconds(total: Double): Double = ExportSections.savedSeconds(completeSections(total), total)

    /** The sections still to render, in order. */
    fun remaining(total: Double): List<ExportSections.Section> = ExportSections.sections(total).filter { !section(it.index).isFile }

    /**
     * Where the bar starts: at zero for a fresh export, otherwise at the
     * first step again with the bar where the saved work leaves it.
     */
    fun startingProgress(total: Double): ExportProgress {
        if (!(total > 0)) return ExportProgress.of(ExportProgress.Step.Opening)
        val saved = savedSeconds(total)
        return when {
            saved > 0 -> ExportProgress.resumingAfter(ExportProgress.of(ExportProgress.Step.Picture, saved / total))
            audioFile.isFile -> ExportProgress.resumingAfter(ExportProgress.of(ExportProgress.Step.Audio, 1.0))
            measuredPeak != null -> ExportProgress.resumingAfter(ExportProgress.of(ExportProgress.Step.AudioPeak, 1.0))
            else -> ExportProgress.of(ExportProgress.Step.Opening)
        }
    }

    /**
     * Publishes a finished stage: [partial], written under `Processing`,
     * becomes [destination] in one move, so the folder never holds half a file.
     */
    fun publish(partial: File, destination: File) {
        Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    /** A paused export found when a project is opened. */
    data class Paused(val savedSeconds: Double, val reason: String?)

    /**
     * Looks for a paused export of [project] when its export screen opens.
     * The saved work is kept only if it [matches] and the same edit has not
     * been exported meanwhile ([exported]); otherwise it is removed.
     */
    fun restore(project: Project, exported: Boolean): Paused? {
        if (!matches(project) || exported) {
            try { discard() } catch (_: IOException) {}
            return null
        }
        return Paused(savedSeconds(project.edit.duration), pauseReason)
    }

    /** How a run of the exporter ended. */
    enum class Ending {
        /** The movie was published. The saved work has served and is retired with the previous movies. */
        Published,
        /** Stopped to be continued: the saved work stays. */
        Paused,
        /** Cancelled or failed for good: the saved work is removed. */
        Abandoned,
    }

    /** What becomes of the saved work when a run ends. A published movie counts even if a pause came in at the same moment. */
    fun finish(paused: Boolean, published: Boolean): Ending = when {
        published -> Ending.Published
        paused -> Ending.Paused
        else -> {
            try { discard() } catch (_: IOException) {}
            Ending.Abandoned
        }
    }

    /** What to do about an export that stopped with an error. */
    sealed interface Failure {
        /** The person cancelled or discarded it. Nothing is shown. */
        data object Cancelled : Failure
        /** The device is full. The export pauses and says why; freeing space lets it resume. */
        data class PausedForSpace(val reason: String) : Failure
        /** The app was not in front. The export pauses and resumes from the saved sections. */
        data object PausedInBackground : Failure
        /** Anything else is reported. */
        data class Failed(val message: String) : Failure
    }

    companion object {
        const val EXPORTS = "Exports"
        const val PROCESSING = "Processing"
        const val PAUSED = ".paused-export"

        /** Added to the device's own message when an export pauses for lack of space. */
        const val FREE_SPACE_HINT = " Free space, then resume the export."

        /** Sorts a failure of a resumable export. [paused] is true when a pause was already asked for. */
        fun failure(message: String, cancelled: Boolean, outOfSpace: Boolean, foreground: Boolean, paused: Boolean = false): Failure = when {
            cancelled -> Failure.Cancelled
            outOfSpace -> Failure.PausedForSpace(message + FREE_SPACE_HINT)
            !foreground -> Failure.PausedInBackground
            paused -> Failure.Cancelled
            else -> Failure.Failed(message)
        }

        /**
         * Removes the working files an interrupted run left in a project's
         * folder. Recordings, the project itself, finished exports and a
         * paused export are never touched.
         */
        fun reclaim(folder: File) {
            File(folder, PROCESSING).deleteRecursively()
        }

        internal fun writeAtomically(file: File, text: String) {
            val writing = File(file.parentFile, ".saving-" + UUID.randomUUID())
            try {
                writing.writeText(text)
                Files.move(writing.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                writing.delete()
            }
        }
    }
}
