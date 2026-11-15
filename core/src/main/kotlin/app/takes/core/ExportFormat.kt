package app.takes.core

import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.floor

/**
 * What a movie comes out as, on top of the choices in [ExportSettings]: the
 * codec HDR footage forces, the picture size, and the line that sums it up.
 */
object ExportFormat {
    /** The canvas of a project that has no footage yet. */
    val EMPTY_CANVAS = 1920 to 1080

    /** HDR is only written as 10-bit H.265, whatever codec was chosen. */
    fun codec(settings: ExportSettings, hdr: Boolean): ExportSettings.Codec = if (hdr) ExportSettings.Codec.HEVC else settings.codec

    /**
     * The canvas a recording gives a project: its upright size, each side
     * rounded to the nearest even number of pixels.
     */
    fun canvas(uprightWidth: Double, uprightHeight: Double): Pair<Int, Int> = even(uprightWidth) to even(uprightHeight)

    private fun even(dimension: Double) = (floor(abs(dimension) / 2 + 0.5) * 2).toInt()

    /**
     * The picture size of the movie. A crop, a mirror or a clip of another
     * shape never changes it: they are drawn inside the canvas.
     */
    fun size(settings: ExportSettings, canvas: Pair<Int, Int>): Pair<Int, Int> = settings.resolution.size(canvas.first, canvas.second)

    /** True when the chosen format is larger than the footage, which the screen says in so many words. */
    fun isUpscaled(output: Pair<Int, Int>, canvas: Pair<Int, Int>): Boolean = output.first > canvas.first + 1

    /** "1920 × 1080 · 30 fps · H.264", with " · HDR" for an HDR movie. Without a canvas yet, the format's name stands in for the size. */
    fun summary(settings: ExportSettings, canvas: Pair<Int, Int>?, hdr: Boolean): String {
        val dimensions = canvas?.let { size(settings, it) }?.let { "${it.first} × ${it.second}" } ?: name(settings.resolution)
        return "$dimensions · ${settings.frameRate} fps · ${name(codec(settings, hdr))}" + if (hdr) " · HDR" else ""
    }

    /** The names the settings are saved under, which are also what the original shows. */
    fun name(resolution: ExportSettings.Resolution): String = when (resolution) {
        ExportSettings.Resolution.Project -> "Project size"
        ExportSettings.Resolution.HD -> "720p"
        ExportSettings.Resolution.FullHD -> "1080p"
        ExportSettings.Resolution.UltraHD -> "4K"
    }

    fun name(codec: ExportSettings.Codec): String = when (codec) {
        ExportSettings.Codec.H264 -> "H.264"
        ExportSettings.Codec.HEVC -> "H.265"
    }
}

/** The export could not go on without filling the device. [missing] is how many bytes to free. */
class ExportSpaceException(val missing: Long) : IOException("There is not enough free space to finish this export.")

/**
 * Why an export failed, as a reason the app turns into a text in the
 * person's language. Still an [IOException], so every catch of one holds it.
 */
class ExportProblem(val reason: Reason, cause: Throwable? = null) : IOException(reason.name, cause) {
    enum class Reason {
        WrongLength, Incomplete, FirstPicture, LastPicture, Unreadable, NotPrepared, SoundNotRendered,
        SectionIncomplete, SectionsMismatch, SavedSoundUnreadable, SavedSectionUnreadable, SectionsNotWhole, PausedNotRemoved,
    }
}

/**
 * How much room each stage of an export asks for before it starts, and how
 * much of the device is never used. These are deliberate over-estimates, not
 * predictions of the movie's size.
 */
object ExportSpace {
    private const val MARGIN = 2_000_000L

    /** The part of a volume of [total] bytes that stays free: a fiftieth of it, at least 512 MB and at most 2 GB. */
    fun reserve(total: Long): Long = maxOf(512_000_000L, minOf(2_000_000_000L, total / 50))

    /** Bytes an export may still use on a volume with [free] of [total] bytes left. May be negative. */
    fun room(free: Long, total: Long): Long = free - reserve(total)

    /** Bytes to free before [bytes] can be written, or 0 when they fit. */
    fun missing(bytes: Long, free: Long, total: Long): Long {
        val room = room(free, total)
        return if (bytes in 0..room) 0 else maxOf(1, bytes - room)
    }

    /** Throws [ExportSpaceException] unless [bytes] fit on the volume holding [directory]. */
    fun require(bytes: Long, directory: File) {
        val volume = generateSequence(directory) { it.parentFile }.first { it.exists() }
        val missing = missing(bytes, volume.usableSpace, volume.totalSpace)
        if (missing > 0) throw ExportSpaceException(missing)
    }

    /** For the sound of a movie of [duration] seconds: 32 000 bytes a second. */
    fun audio(duration: Double): Long = (duration * 32_000).toLong() + MARGIN

    /** For one picture section of [seconds]: one byte for every pixel of every second. */
    fun section(width: Int, height: Int, seconds: Double): Long = (width.toDouble() * height * seconds).toLong() + MARGIN

    /** For joining saved parts of [parts] bytes in all into the movie: their size and a fiftieth more. */
    fun join(parts: Long): Long = parts + parts / 50 + MARGIN
}
