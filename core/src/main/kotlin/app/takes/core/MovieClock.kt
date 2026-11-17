package app.takes.core

import kotlin.math.abs

/**
 * Where the frames of a segment's movie stand on the recorder's clocks, so that what the live effects showed is saved
 * on the movie's, as the original's are by its writer (LiveVideoEffects.swift:354-358, 641-646). Times are
 * nanoseconds of the clock the camera stamps its frames on, Android's `elapsedRealtimeNanos`.
 *
 * The effect clock starts at [origin], when the recorder says the recording has begun, before the movie's first frame
 * ([firstFrame]); the preview draws a frame some time after the camera took it ([drawnLead] less [takenLead]). The
 * recorder never says when its first frame was taken, only the length recorded ([reported]): the time of the frame
 * last written less the first's, both frames the camera took ([captured]). The first frame is the one of those times
 * that every report's length leads to another, found by the irregular spacing of the camera's frames; until it is, or
 * where the camera's times are not the movie's, it is taken as the earliest the reports allow, less the least time a
 * report takes to come ([REPORT_LATENCY]).
 *
 * Safe to call from the camera's, the preview's and the main thread.
 */
class MovieClock(private val reportLatency: Long = REPORT_LATENCY) {
    private val captures = LongArray(CAPTURES)
    private var captureCount = 0
    private val delays = LongArray(DELAYS)
    private var delayCount = 0
    private var origin = 0L
    private var bound = Long.MAX_VALUE
    private var candidates: LongArray? = null
    private var scores = IntArray(0)
    private var checked = 0
    private var reports = 0
    private var found: Long? = null

    /** The recorder has begun at [at]: where the effect clock starts. */
    @Synchronized
    fun start(at: Long) {
        origin = at
    }

    /** The camera took a frame at [timestamp], one of those the movie can be made of. */
    @Synchronized
    fun captured(timestamp: Long) {
        captures[captureCount % CAPTURES] = timestamp
        captureCount++
    }

    /** The preview drew at [at] a frame the camera took at [timestamp]. */
    @Synchronized
    fun drawn(timestamp: Long, at: Long) {
        val delay = at - timestamp
        if (delay < 0 || delay >= MAXIMUM_DELAY) return
        delays[delayCount % DELAYS] = delay
        delayCount++
    }

    /** The recorder said at [at] that [recorded] nanoseconds of the movie are written. */
    @Synchronized
    fun reported(at: Long, recorded: Long) {
        if (recorded <= 0 || origin == 0L) return
        bound = minOf(bound, at - recorded)
        if (found != null || reports >= GIVE_UP) return
        reports++
        val list = candidates
        if (list == null) {
            // The first frame was taken before the frame now reported, by no more than a second.
            val starts = (0 until minOf(captureCount, CAPTURES)).map { captures[it] }
                .filter { it in bound - SEARCH..bound && near(it + recorded) }.distinct()
            if (starts.isEmpty()) return
            candidates = starts.toLongArray()
            scores = IntArray(starts.size) { 1 }
            checked = 1
            return
        }
        checked++
        for (i in list.indices) if (list[i] <= bound && near(list[i] + recorded)) scores[i]++
        if (checked < CHECKS) return
        val best = scores.indices.maxByOrNull { scores[it] } ?: return
        val others = scores.indices.filter { it != best }.maxOfOrNull { scores[it] } ?: 0
        if (list[best] <= bound && scores[best] >= checked * 9 / 10 && others <= checked / 2) found = list[best]
    }

    private fun near(time: Long): Boolean {
        for (i in 0 until minOf(captureCount, CAPTURES)) if (abs(captures[i] - time) <= TOLERANCE) return true
        return false
    }

    /** When the movie's first frame was taken: found among the camera's frames, else the earliest the reports allow. */
    val firstFrame: Long?
        @Synchronized get() = found ?: bound.takeIf { it != Long.MAX_VALUE }?.minus(reportLatency)

    /** Whether [firstFrame] is one of the camera's frames, measured rather than estimated. */
    val measured: Boolean @Synchronized get() = found != null

    /**
     * Seconds a time of a frame the camera took, from [origin], runs ahead of the movie's: the movie's first frame on
     * that clock, never below zero. What the frames showed is kept at the time each was taken, then brought back by this.
     */
    val takenLead: Double
        @Synchronized get() = firstFrame?.let { maxOf(0.0, (it - origin) / 1e9) } ?: 0.0

    /**
     * Seconds the effect clock, the time from [origin] a frame is drawn, runs ahead of the movie's: [takenLead] and the
     * time the preview takes to draw a frame, the median of the latest.
     */
    val drawnLead: Double
        @Synchronized get() {
            val count = minOf(delayCount, DELAYS)
            if (count == 0) return takenLead
            val sorted = delays.copyOf(count).sorted()
            return takenLead + sorted[count / 2] / 1e9
        }

    companion object {
        /**
         * The least time a report of the recorded length takes to reach the app after the frame it counts is taken,
         * when the first frame is not found: 0.091 s on a Galaxy A54, measured against a zoom step seen in both the
         * preview's frames and the movie.
         */
        const val REPORT_LATENCY = 90_000_000L
        /** The movie's and the camera's times of a frame agree to the microsecond the recorder counts in. */
        const val TOLERANCE = 2_000L
        private const val CAPTURES = 128
        private const val DELAYS = 15
        private const val MAXIMUM_DELAY = 1_000_000_000L
        private const val SEARCH = 1_000_000_000L
        /** The reports a first frame must agree with, nine in ten, where no other does with half of them. */
        private const val CHECKS = 15
        private const val GIVE_UP = 120
    }
}
