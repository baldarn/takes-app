package app.takes.core

import kotlin.math.abs

/**
 * Where an export measures how loud its mix gets, and the measurement itself, as the original does since it stopped
 * warning about peaks the edit did not make (YouRec/AudioGain.swift:158-270): only where the edit can raise the
 * original level, so a straight recording, a cut, a fade, a muted part or an edit of the picture alone is never
 * measured, and a loud passage of a take cannot warn about an edit elsewhere.
 */
object ExportAudioLevel {
    /**
     * The stretches of [timeline] where the edit can raise a level (AudioGain.swift:158-186): every audible run of a
     * sound turned up or played at another speed, every part of an enhancement whose stages can raise a peak, and
     * every place two audible sounds overlap, [backgroundAudio] (the sound of the effects' background movies) among
     * them. Sounds of still photos and muted or silent runs and held frames play nothing.
     */
    fun checkRanges(timeline: TrackTimeline, usedSources: List<SourceMedia>, backgroundAudio: List<EditInterval> = emptyList()): List<EditInterval> {
        val stills = usedSources.filter { it.isStillPhoto == true }.map { it.id }.toSet()
        val sounding = backgroundAudio.toMutableList()
        val check = ArrayList<EditInterval>()
        for (item in timeline.audio) {
            if (item.muted || item.sourceID in stills) continue
            var time = item.start
            for (run in item.mapping.runs) {
                val end = time + run.duration
                val start = time
                time = end
                if (run.silent || run.isHold || end <= start) continue
                val span = EditInterval(start, end)
                sounding += span
                if (item.volumeDB > 0 || abs(EditTime.of(run.sourceEnd - run.sourceStart).ticks - run.duration.ticks) > 1) check += span
                for (region in item.enhancements) {
                    if (region.effects.none(::canRaisePeak)) continue
                    EditInterval(item.start + region.interval.start, item.start + region.interval.end).intersection(span)?.let { check += it }
                }
            }
        }
        var lastEnd = EditTime.ZERO
        for (span in sounding.sortedBy { it.start }) {
            if (span.start < lastEnd) check += EditInterval(span.start, minOf(span.end, lastEnd))
            lastEnd = maxOf(lastEnd, span.end)
        }
        return TrackEditing.union(check)
    }

    /** Whether a stage of Enhance audio can make a sound louder (AudioGain.swift:190-199). */
    fun canRaisePeak(effect: AudioEffect): Boolean {
        if (!effect.enabled) return false
        return when (effect.kind) {
            AudioEffectKind.Gain -> effect.amount > 0
            AudioEffectKind.Equalizer, AudioEffectKind.VoiceIsolation, AudioEffectKind.StudioVoice, AudioEffectKind.StudioRestoration -> effect.amount != 0.0
            AudioEffectKind.HighPass, is AudioEffectKind.Unavailable -> true
        }
    }

    /**
     * [ranges] as frames of a mix at [rate] frames a second, inside the movie's [duration]: each from the first frame
     * at or after its start to the first at or after its end, overlaps made one (AudioGain.swift:213-219).
     */
    fun frameSpans(ranges: List<EditInterval>, duration: Double, rate: Int): List<LongRange> {
        fun firstFrame(time: EditTime): Long = (time.ticks * rate + EditTime.SCALE - 1) / EditTime.SCALE
        val whole = EditInterval(EditTime.ZERO, EditTime.of(duration))
        return TrackEditing.union(ranges.mapNotNull { it.intersection(whole) })
            .map { firstFrame(it.start) until firstFrame(it.end) }.filter { !it.isEmpty() }
    }

    /**
     * The loudest sample, as a magnitude, of the [frames] interleaved frames of [block] that begin at frame [first]
     * of the mix, counting only frames inside [spans] (AudioGain.swift:236-265), and [peak], the loudest before.
     */
    fun peak(block: FloatArray, first: Long, frames: Int, channels: Int, spans: List<LongRange>, peak: Float): Float {
        var loudest = peak
        val end = first + frames
        for (span in spans) {
            if (span.last < first) continue
            if (span.first >= end) break
            val from = (maxOf(span.first, first) - first).toInt()
            val to = (minOf(span.last + 1, end) - first).toInt()
            for (index in from * channels until to * channels) loudest = maxOf(loudest, abs(block[index]))
        }
        return loudest
    }
}
