package app.takes.core

import kotlinx.serialization.Serializable

/**
 * One stretch of an item of the track timeline: [duration] ticks of the
 * item's own clock showing the source from [sourceStart] to [sourceEnd], in
 * source seconds. Equal ends are a hold: one frame kept, no sound. The speed
 * is `(sourceEnd − sourceStart) / duration`. [silent] is true where the
 * source's sound is muted for this stretch.
 *
 * The original's `PlaybackRun` (YouRec/TrackTimeline.swift:34-44), saved with
 * its names. Source positions stay seconds and are never put on the tick clock.
 */
@Serializable
data class PlaybackRun(
    val duration: EditTime,
    val sourceStart: Double,
    val sourceEnd: Double,
    val silent: Boolean = false,
) {
    val isHold: Boolean get() = sourceStart == sourceEnd

    /** The source second showing [offset] into the run; the ends outside it. */
    fun source(offset: EditTime): Double {
        if (duration <= EditTime.ZERO) return sourceStart
        return sourceStart + (sourceEnd - sourceStart) * minOf(1.0, maxOf(0.0, offset.seconds / duration.seconds))
    }
}

/**
 * The timing of one item: its runs, one after another. The item is as long as
 * the sum of their ticks. The original's `SourcePlaybackMap`
 * (YouRec/TrackTimeline.swift:45-109).
 */
@Serializable
data class SourcePlaybackMap(val runs: List<PlaybackRun>) {
    val duration: EditTime get() = EditTime(runs.sumOf { it.duration.ticks })

    /** The source second showing [local] ticks into the item; its last second at the very end, null outside. */
    fun source(local: EditTime): Double? {
        var cursor = EditTime.ZERO
        for (run in runs) {
            if (local < cursor + run.duration) return run.source(maxOf(EditTime.ZERO, local - cursor))
            cursor += run.duration
        }
        return if (local == duration) runs.lastOrNull()?.sourceEnd else null
    }

    /** How far into the item the source second [time] shows; null when no run plays it. A hold plays no second. */
    fun local(time: Double): EditTime? {
        var cursor = EditTime.ZERO
        for (run in runs) {
            if (!run.isHold && time >= run.sourceStart && time < run.sourceEnd) {
                return cursor + EditTime.of(run.duration.seconds * (time - run.sourceStart) / (run.sourceEnd - run.sourceStart))
            }
            cursor += run.duration
        }
        return null
    }

    /** The timing of the piece [interval] of the item, in the item's own ticks. */
    fun sliced(interval: EditInterval): SourcePlaybackMap {
        var cursor = EditTime.ZERO
        val result = mutableListOf<PlaybackRun>()
        for (run in runs) {
            val end = cursor + run.duration
            EditInterval(cursor, end).intersection(interval)?.let { part ->
                result += PlaybackRun(part.duration, run.source(part.start - cursor), run.source(part.end - cursor), run.silent)
            }
            cursor = end
        }
        return SourcePlaybackMap(result)
    }

    companion object {
        /**
         * The timing a clip has by itself: one run per stretch it plays, at
         * the clip's speed. The boundaries between runs are rounded to ticks,
         * not each run's length, so the residue of rounding does not add up
         * (YouRec/TrackTimeline.swift:49-64). A clip here plays one stretch
         * at one rate and mutes none of it; the original's held frame, pause
         * cuts and effect spans are not on the clip yet.
         */
        fun of(clip: TimelineClip): SourcePlaybackMap {
            var total = 0.0
            var prior = EditTime.ZERO
            return SourcePlaybackMap(
                SpeedMapping.spans(clip, clip.speed).map { span ->
                    total += span.duration
                    val boundary = EditTime.of(total)
                    PlaybackRun(boundary - prior, span.start, span.end).also { prior = boundary }
                }.filter { it.duration > EditTime.ZERO },
            )
        }

        /** One run from [sourceStart] to [sourceEnd] taking [duration]. */
        fun single(duration: EditTime, sourceStart: Double, sourceEnd: Double): SourcePlaybackMap =
            SourcePlaybackMap(listOf(PlaybackRun(duration, sourceStart, sourceEnd)))
    }
}
