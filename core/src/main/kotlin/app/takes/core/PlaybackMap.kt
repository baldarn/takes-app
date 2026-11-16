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
         * The timing a clip has by itself: one run per stretch it plays
         * ([TimelineClip.playbackSpans]), each silent where the clip mutes its
         * source there. The boundaries between runs are rounded to ticks,
         * not each run's length, so the residue of rounding does not add up.
         * A held frame is one hold, silent when its Freeze Frame cuts the
         * sound (YouRec/TrackTimeline.swift:49-64; a hold that plays on is
         * this port's, as the original's sequence renderer plays it,
         * YouRec/MediaRenderer.swift:497-504).
         */
        fun of(clip: TimelineClip): SourcePlaybackMap {
            clip.frozenFrame?.let { hold ->
                return SourcePlaybackMap(listOf(PlaybackRun(EditTime.of(hold.duration), hold.sourceTime, hold.sourceTime, silent = holdSilent(clip))))
            }
            var total = 0.0
            var prior = EditTime.ZERO
            val effects = clip.videoEffects.orEmpty()
            return SourcePlaybackMap(
                clip.playbackSpans.map { span ->
                    total += span.duration
                    val boundary = EditTime.of(total)
                    PlaybackRun(boundary - prior, span.start, span.end, VideoEffectSettings.mutesSourceAudio(effects, (span.start + span.end) / 2))
                        .also { prior = boundary }
                }.filter { it.duration > EditTime.ZERO },
            )
        }

        /**
         * [of] the clip, [length] ticks long: the length a sequence's layout
         * gives it ([TrackConversion]), the difference of rounding taken by
         * the last run. A clip that plays one stretch at one rate is one run.
         */
        fun of(clip: TimelineClip, length: EditTime): SourcePlaybackMap {
            val map = of(clip)
            if (map.runs.size <= 1 && map.runs.none { it.silent || it.isHold }) return single(length, clip.start, clip.end)
            val difference = length - map.duration
            if (difference == EditTime.ZERO) return map
            val last = map.runs.last()
            val runs = map.runs.dropLast(1) + last.copy(duration = maxOf(EditTime(1), last.duration + difference))
            return SourcePlaybackMap(runs)
        }

        /** Whether the hold of a held frame is silent: unless its Freeze Frame plays the sound on. */
        fun holdSilent(clip: TimelineClip): Boolean =
            clip.frozenFrame == null || VideoEffectSettings.mutesSourceAudio(clip.videoEffects.orEmpty(), clip.start, frozen = true) ||
                clip.videoEffects.orEmpty().none { it.kind == VideoEffectID.FreezeFrame }

        /** One run from [sourceStart] to [sourceEnd] taking [duration]. */
        fun single(duration: EditTime, sourceStart: Double, sourceEnd: Double): SourcePlaybackMap =
            SourcePlaybackMap(listOf(PlaybackRun(duration, sourceStart, sourceEnd)))
    }
}
