package app.takes.core

/**
 * The item with a fade of [seconds] at [edge]: no longer than the item, none
 * for zero. A fade set anew starts at silence; what a split left behind the
 * edge is forgotten. YouRec/TrackTimeline.swift:191-195.
 */
fun AudioTimelineItem.settingFade(edge: AudioFadeEdge, seconds: Double): AudioTimelineItem {
    val fade = AudioEdgeFade.of(seconds, mapping.duration)
    return if (edge == AudioFadeEdge.FadeIn) copy(fadeIn = fade) else copy(fadeOut = fade)
}

/** The fades at the item's two ends. */
val AudioTimelineItem.fades: AudioEdgeFades get() = AudioEdgeFades(fadeIn, fadeOut)

/**
 * The operations on the sound items of a track timeline: a new sound, level,
 * fades, chains of effects on a stretch, and censors. Ports of the original's
 * (YouRec/TrackTimeline.swift:191-222, :743-766; YouRec/AudioCensor.swift:92-130),
 * over what the core already has: [AudioEdgeFade], [AudioCensor], [AudioChain].
 *
 * What a sound carries is kept right by the edits that cut or retime it, not
 * here: a split or a range gives each piece what is left of the fades (a cut
 * inside a fade keeps its slope and its level at the cut), the censors (the
 * tone goes on exactly as before) and the chains ([AudioTimelineItem.sliced]);
 * a change of speed takes chains and censors to where their sound now plays
 * and makes pieces that touch one again ([TrackEditing.synchronizingFollowers]);
 * revealing footage on the left moves them with the sound
 * ([TrackEditing.extending]).
 *
 * None of these changes when anything plays, so none ends with the prune.
 */
object TrackAudio {
    /** A timeline with a new sound on it, and the sound's id. */
    data class Inserted(val timeline: TrackTimeline, val id: String)

    /**
     * Lays the whole of [source] over what is there, from [position] (never
     * before zero), as a sound that follows no picture and is in no link.
     * Nothing moves and nothing is cut. YouRec/TrackTimeline.swift:217-222.
     */
    fun inserting(timeline: TrackTimeline, source: SourceMedia, position: EditTime): Inserted {
        val item = AudioTimelineItem(
            occurrenceID = newID(), sourceID = source.id, start = maxOf(EditTime.ZERO, position),
            mapping = SourcePlaybackMap.single(EditTime.of(source.duration), 0.0, source.duration),
        )
        return Inserted(timeline.copy(audio = timeline.audio + item), item.id)
    }

    /** The selected sounds at [db], kept within −60…12, and [muted] or not. A range in the selection is not looked at: cut first ([TrackEditing.scoped]). YouRec/TrackTimeline.swift:743-745. */
    fun settingVolume(timeline: TrackTimeline, db: Double, muted: Boolean, selection: TimelineSelection): TrackTimeline {
        val level = minOf(ClipAudio.MAXIMUM_DB, maxOf(ClipAudio.MINIMUM_DB, db))
        return timeline.copy(audio = timeline.audio.map { if (it.id in selection.audio) it.copy(volumeDB = level, muted = muted) else it })
    }

    /**
     * The one sound a fade at [edge] belongs to among those [selected]: the
     * first to begin for a fade in, the last to end for a fade out; of
     * several at the same place, the first in the timeline's order.
     * YouRec/TrackTimeline.swift:153-164.
     */
    fun fadeTarget(timeline: TrackTimeline, edge: AudioFadeEdge, selected: Set<String>): AudioTimelineItem? =
        edge.index(timeline.audio.map { it.interval }) { timeline.audio[it].id in selected }?.let { timeline.audio[it] }

    /** The sound [id] with a fade of [seconds] at [edge] ([AudioTimelineItem.settingFade]). The same timeline when there is no such sound. */
    fun settingFade(timeline: TrackTimeline, id: String, edge: AudioFadeEdge, seconds: Double): TrackTimeline {
        if (timeline.audio.none { it.id == id }) return timeline
        return timeline.copy(audio = timeline.audio.map { if (it.id == id) it.settingFade(edge, seconds) else it })
    }

    /** The level the item's volume and fades leave [local] ticks into it, as a factor: 0 when muted. */
    fun level(item: AudioTimelineItem, local: EditTime): Double = item.gain * item.fades.gain(local.seconds, item.mapping.duration.seconds)

    /**
     * Puts the chain [effects] on the selected sounds, on the selection's
     * range or on the whole of each. A chain already under it is cut back to
     * what lies outside, so no stretch is processed twice; an empty chain
     * only removes. A sound the range misses is left alone. Every region made
     * by one call has the id [effectID].
     * YouRec/TrackTimeline.swift:746-766.
     */
    fun enhancing(timeline: TrackTimeline, effects: List<AudioEffect>, selection: TimelineSelection, effectID: String = newID()): TrackTimeline =
        timeline.copy(audio = timeline.audio.map { item ->
            if (item.id !in selection.audio) return@map item
            val target = (if (selection.range == null) item.interval else item.interval.intersection(selection.range)) ?: return@map item
            val local = EditInterval(target.start - item.start, target.end - item.start)
            val regions = ArrayList<AudioEnhancementRegion>()
            for (region in item.enhancements) {
                if (region.interval.intersection(local) == null) { regions += region; continue }
                if (region.interval.start < local.start) regions += region.copy(interval = EditInterval(region.interval.start, local.start))
                if (region.interval.end > local.end) regions += region.copy(interval = EditInterval(local.end, region.interval.end))
            }
            if (effects.isNotEmpty()) regions += AudioEnhancementRegion(effectID, local, effects)
            item.copy(enhancements = regions.sortedBy { it.interval.start })
        })

    /** The chain that plays [local] ticks into the item, without the stages that are off ([AudioChain.active]); empty where there is none. */
    fun chain(item: AudioTimelineItem, local: EditTime): List<AudioEffect> =
        item.enhancements.firstOrNull { local in it.interval }?.let { AudioChain.active(it.effects) }.orEmpty()

    /**
     * Censors the selected sounds with [settings], on the selection's range
     * or on the whole of each; null only removes. Censors under it are cut
     * back to what lies outside, or only the censor [removingOnly] when
     * given. Every piece one call makes has the id [effectID] and shares the
     * call's clock, also on a sound that begins partway through the range, so
     * the pieces are one continuous tone. A sound the range misses is left
     * alone. YouRec/AudioCensor.swift:92-113, through [AudioCensor.censored].
     */
    fun censoring(
        timeline: TrackTimeline, settings: AudioCensorSettings?, selection: TimelineSelection,
        effectID: String = newID(), removingOnly: String? = null,
    ): TrackTimeline = timeline.copy(audio = timeline.audio.map { item ->
        if (item.id !in selection.audio) item
        else item.withCensors(AudioCensor.censored(item.censors, item.interval, settings, selection.range, effectID, removingOnly))
    })

    /**
     * The censored stretches of [item] on the timeline's clock, ready to
     * render. Where pieces of one censor lie over each other on several
     * sounds, all are silenced and one carries the tone: the loudest that is
     * not muted, the earliest in the timeline among equals.
     * YouRec/AudioCensor.swift:114-130, through [AudioCensor.spans].
     */
    fun censorSpans(timeline: TrackTimeline, item: AudioTimelineItem): List<AudioCensorSpan> {
        fun of(sound: AudioTimelineItem) = AudioCensorItem(sound.id, sound.start, sound.censors, sound.volumeDB, sound.muted)
        return AudioCensor.spans(timeline.audio.map(::of), of(item))
    }
}
