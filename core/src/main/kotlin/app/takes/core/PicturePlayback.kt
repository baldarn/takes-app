package app.takes.core

/**
 * How a player goes through the footage of one picture of a plan: the
 * source stretches it plays, each at one rate ([EffectPlaybackSpan]), from
 * the first second of its footage. A player's position is seconds of footage
 * past that second; the movie's is seconds into the picture. A picture that
 * plays at one speed is one stretch, and these are the arithmetic the
 * players always did (`offset × rate`); Undercranking and Step Printing make
 * more stretches, and a held frame is one stretch of a single frame taking
 * the whole hold.
 */
object PicturePlayback {
    /**
     * The stretches of a picture that plays [content] with the timing
     * [runs] (a track timeline item's map): a run that holds is the held
     * frame of a Freeze Frame, played as its one frame over the run.
     */
    fun spans(content: TimelineClip, runs: List<PlaybackRun>): List<EffectPlaybackSpan> {
        val result = ArrayList<EffectPlaybackSpan>(runs.size)
        for (run in runs) {
            val seconds = run.duration.seconds
            if (seconds <= 0) continue
            result += if (run.isHold) {
                val end = maxOf(content.end, run.sourceStart + HELD_FRAME)
                EffectPlaybackSpan(run.sourceStart, end, (end - run.sourceStart) / seconds)
            } else EffectPlaybackSpan(run.sourceStart, run.sourceEnd, (run.sourceEnd - run.sourceStart) / seconds)
        }
        return result
    }

    /** The single rate of [spans], or null when they play at more than one. */
    fun steady(spans: List<EffectPlaybackSpan>): Double? {
        val first = spans.firstOrNull() ?: return null
        return first.rate.takeIf { spans.all { it.rate == first.rate } }
    }

    /** Seconds of footage past the first, [offset] seconds into the picture. */
    fun position(spans: List<EffectPlaybackSpan>, offset: Double): Double {
        val first = spans.firstOrNull() ?: return 0.0
        if (spans.size == 1) return offset * first.rate
        return SpeedMapping.sourceTime(offset, spans, spans.last().end) - first.start
    }

    /** Seconds into the picture at which the player is [position] seconds of footage past the first. */
    fun offset(spans: List<EffectPlaybackSpan>, position: Double): Double {
        val first = spans.firstOrNull() ?: return 0.0
        if (spans.size == 1) return position / first.rate
        return SpeedMapping.timelineTime(first.start + position, spans)
    }

    /** The rate the player goes at [position] seconds of footage past the first; 1 with nothing to play. */
    fun rate(spans: List<EffectPlaybackSpan>, position: Double): Double {
        val first = spans.firstOrNull() ?: return 1.0
        val time = first.start + position
        return (spans.firstOrNull { time < it.end - 0.000001 } ?: spans.last()).rate
    }

    /**
     * The first second after [position] (seconds of footage past the first)
     * at which the rate changes, as seconds past the first; null when it
     * does not change again.
     */
    fun nextChange(spans: List<EffectPlaybackSpan>, position: Double): Double? {
        val first = spans.firstOrNull() ?: return null
        val time = first.start + position
        val index = spans.indexOfFirst { time < it.end - 0.000001 }
        if (index < 0 || index == spans.lastIndex) return null
        for (next in index + 1 until spans.size) if (spans[next].rate != spans[index].rate) return spans[next].start - first.start
        return null
    }

    /** The footage a hold reads when its picture has no frame of its own: a sixtieth of a second, as the original's renderer cuts it (YouRec/MediaRenderer.swift:282). */
    const val HELD_FRAME = 1.0 / 60
}
