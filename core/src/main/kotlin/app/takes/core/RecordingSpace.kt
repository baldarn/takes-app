package app.takes.core

/**
 * How much free storage the recorder asks for before a segment and while it
 * is written, as the original app does it: a floor that is kept free on the
 * device whatever happens, room above it to start, and a check during the
 * take that pauses it while there is still room to finish the file
 * (YouRec/ProjectRecovery.swift:421-423 and 452-464,
 * YouRec/CaptureController.swift:1401-1403, 1430-1431 and 2682-2695).
 *
 * The rule is one; its numbers are the fields. Two sets are named,
 * [ORIGINAL] and [ANDROID_FLAT]; which one the recorder uses is decided
 * where it is used.
 *
 * Bytes throughout. "4K" is the original's higher quality, 3840 × 2160.
 */
data class RecordingSpace(
    /** The floor is the storage's size divided by this; zero or less leaves the size out of it. */
    val floorDivisor: Long,
    /** The floor is never less than this. */
    val floorMinimum: Long,
    /** The floor is never more than this. */
    val floorMaximum: Long,
    /** Room above the floor needed to start a segment at 1080p. */
    val startHD: Long,
    /** Room above the floor needed to start a segment in 4K. */
    val startUHD: Long,
    /** Room above the floor below which a running take is paused, before what [pauseSeconds] adds. */
    val pauseMargin: Long,
    /** Seconds of recording, at the fastest pace measured, added to [pauseMargin]. */
    val pauseSeconds: Double,
    /** The pace a segment at 1080p is assumed to write at before it is measured, in bytes a second; the measure never goes below it. */
    val assumedRateHD: Double,
    /** The same for 4K. */
    val assumedRateUHD: Double,
    /** Seconds between two checks during a take. */
    val checkInterval: Double,
) {
    /** The bytes to keep free on a storage of [total] bytes (ProjectRecovery.swift:421-423). */
    fun floor(total: Long): Long = maxOf(floorMinimum, minOf(floorMaximum, if (floorDivisor > 0) total / floorDivisor else 0L))

    /** What is free above the floor; negative when the floor is already used (ProjectRecovery.swift:454). */
    fun room(free: Long, total: Long): Long = free - floor(total)

    /** The room a segment needs to start (CaptureController.swift:1402). */
    fun startBytes(ultraHD: Boolean): Long = if (ultraHD) startUHD else startHD

    /** Whether a segment may start (CaptureController.swift:1401-1403, ProjectRecovery.swift:455). */
    fun canStart(free: Long, total: Long, ultraHD: Boolean): Boolean = startBytes(ultraHD) <= room(free, total)

    /**
     * The bytes to free before a segment can start, zero when it can. The
     * original names this amount in its message, "Free about … more space"
     * (ProjectRecovery.swift:456-462).
     */
    fun shortBy(free: Long, total: Long, ultraHD: Boolean): Long = maxOf(0L, startBytes(ultraHD) - room(free, total))

    /** The measure of a segment that is about to start at [now]: nothing written, the assumed pace (CaptureController.swift:1430-1431). */
    fun begin(now: Double, ultraHD: Boolean): Pace = Pace(now, 0L, if (ultraHD) assumedRateUHD else assumedRateHD)

    /** Whether it is time to look at the storage again, [lastCheck] being when it was last looked at (CaptureController.swift:2682). */
    fun isDue(now: Double, lastCheck: Double): Boolean = now - lastCheck > checkInterval

    /**
     * The room above the floor under which a take writing at [bytesPerSecond]
     * is paused. It covers the time to the next check, what the encoder still
     * holds, the end of the file and saving the project
     * (CaptureController.swift:2691-2692).
     */
    fun pauseBelow(bytesPerSecond: Double): Double = pauseMargin + pauseSeconds * bytesPerSecond

    /**
     * Whether a running take must be paused now so that its file can still
     * be finished. [free] is null when the storage could not be read, and the
     * original pauses then too (CaptureController.swift:2692).
     */
    fun shouldPause(free: Long?, total: Long, bytesPerSecond: Double): Boolean =
        free == null || room(free, total) < pauseBelow(bytesPerSecond)

    /**
     * How fast a segment is filling the storage: the size of its file at one
     * moment and the fastest pace seen between two looks
     * (CaptureController.swift:419-420 and 2685-2690).
     */
    data class Pace(val time: Double, val bytes: Long, val bytesPerSecond: Double) {
        /** The measure after the file was [bytes] long at [now]. A file that shrank or a clock that stood still changes nothing but the sample. */
        fun sampled(now: Double, bytes: Long): Pace {
            if (now <= time) return this
            val rate = maxOf(0L, bytes - this.bytes) / (now - time)
            return Pace(now, bytes, maxOf(bytesPerSecond, rate))
        }
    }

    companion object {
        /**
         * The original's numbers. The floor is 2 % of the storage, between
         * 512 MB and 2 GB (ProjectRecovery.swift:421-423); a segment starts
         * with 75 MB above it at 1080p and 170 MB in 4K
         * (CaptureController.swift:1402); a take is paused under 32 MB plus
         * 15 s of recording above it, looked at every 5 s
         * (CaptureController.swift:2682,2692), at a pace taken as 2 MB a
         * second at 1080p and 7 MB a second in 4K until a faster one is
         * measured (CaptureController.swift:1430).
         *
         * On a 128 GB phone that is 2.075 GB free to start at 1080p, and a
         * pause at 2.062 GB.
         */
        val ORIGINAL = RecordingSpace(
            floorDivisor = 50,
            floorMinimum = 512_000_000,
            floorMaximum = 2_000_000_000,
            startHD = 75_000_000,
            startUHD = 170_000_000,
            pauseMargin = 32_000_000,
            pauseSeconds = 15.0,
            assumedRateHD = 2_000_000.0,
            assumedRateUHD = 7_000_000.0,
            checkInterval = 5.0,
        )

        /**
         * The numbers recommended for Android: no floor, a flat 300 MiB to
         * start at 1080p, which is what the recorder asked for before this
         * rule, and 600 MiB in 4K; the pause during a take as in the
         * original, measured from an empty storage.
         */
        val ANDROID_FLAT = ORIGINAL.copy(
            floorDivisor = 0,
            floorMinimum = 0,
            floorMaximum = 0,
            startHD = 300L * 1024 * 1024,
            startUHD = 600L * 1024 * 1024,
        )

        /**
         * A 4 MB file the original writes beside each segment before it
         * starts and deletes when the movie is closed, so there is room to
         * finish it on a full storage (ProjectRecovery.swift:296-299,
         * YouRec/CaptureSegmentWriter.swift:291). Not part of the rule
         * above; named here for the step that writes segment receipts.
         */
        const val RESERVE_BYTES = 4L * 1024 * 1024
    }
}
