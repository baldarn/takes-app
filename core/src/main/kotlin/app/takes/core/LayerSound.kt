package app.takes.core

import kotlin.math.roundToLong

/**
 * The sound of an edit drawn by picture layers ([LayerPlan]), until the
 * stages of sound lanes put it in a plan of its own: what the preview and the
 * export play beside the pictures.
 *
 *  - Each picture sounds as a clip of a sequence does by itself
 *    ([RenderPlan.voices]): its own footage, cut as its picture is, at its
 *    own time and speed, with the level, mute, fades and noise reduction of
 *    the sound that follows it ([clips], the edit's view of its pictures,
 *    found by id). A picture whose sound follows it no longer is silent.
 *    Pictures over one another sound at once, each on the lane its picture
 *    decodes on. A transition shapes the pictures and not their sound.
 *  - The sounds that follow no picture ([sounds], the edit's sound items)
 *    play on their two tracks as they do for a sequence ([RenderPlan.soundtracks]).
 */
class LayerSound(val plan: LayerPlan, clips: List<TimelineClip>, val sounds: List<AudioItem>) {
    /** Each picture, by index, with the sound settings of its clip in the edit's view. */
    val heard: List<TimelineClip> = run {
        val byID = HashMap<String, TimelineClip>()
        for (clip in clips) byID.putIfAbsent(clip.id, clip)
        plan.pictures.map { picture -> picture.content.copy(audio = byID[picture.id].let { if (it == null) ClipAudio(muted = true) else it.audio }) }
    }

    /** The noise reduction the sound of the picture at [index] asks for; 0 for none. */
    fun denoise(index: Int): Double = heard.getOrNull(index)?.audio?.takeIf { !it.muted }?.denoise ?: 0.0

    /** How loud the picture at [index] is [offset] seconds into it: its own level and fades. */
    fun clipLevel(index: Int, offset: Double): Double = heard.getOrNull(index)?.let { TimelineEditing.soundLevel(listOf(it), 0, offset) } ?: 0.0

    /** How loud [lane] is at [time] in the preview: the level of the picture it plays, 0 where it plays none. */
    fun laneLevel(lane: Int, time: Double): Double {
        val picture = plan.onLane(lane, time) ?: return 0.0
        return clipLevel(picture.index, time - picture.start)
    }

    /** The level of the picture at [index] sample by sample, on the movie's clock; null when it is silent. */
    fun clipEnvelope(index: Int): AudioEnvelopeSegment? {
        val clip = heard.getOrNull(index) ?: return null
        val start = plan.pictures[index].start
        val alone = SoundEnvelope.clip(listOf(clip), 0) ?: return null
        return alone.copy(start = alone.start + start, end = alone.end + start, clipStart = alone.clipStart + start, clipEnd = alone.clipEnd + start)
    }

    /**
     * The sound of the pictures in an export, one sequence a lane: the
     * pictures of the lane that sound ([voiced] says whether a recording has
     * sound, and a picture whose cleaned sound plays always does), at their
     * times, silent between them.
     */
    fun voices(voiced: (sourceID: String) -> Boolean): List<RenderPlan.Sequence> = (0 until plan.lanes).mapNotNull { lane ->
        val own = plan.pictures.filter { it.lane == lane && heard[it.index].audio?.muted != true && (denoise(it.index) > 0 || voiced(it.sourceID)) }
            .sortedWith(compareBy<LayerPlan.Picture> { it.start }.thenBy { it.index })
        if (own.isEmpty()) return@mapNotNull null
        RenderPlan.Sequence(RenderPlan.Role.Voice, lane, spaced(own, { it.start }, { it.end }) { RenderPlan.Entry.Clip(it.index) })
    }

    /** The sounds of the two tracks, one sequence a track, silent between them; the movie ends with its picture. */
    val soundtracks: List<RenderPlan.Sequence>
        get() = (0 until AudioItem.TRACKS).mapNotNull { track ->
            val total = plan.duration
            val items = sounds.filter { it.track == track && it.start < total - RenderPlan.SOUND_MARGIN }.sortedBy { it.start }
            if (items.isEmpty()) return@mapNotNull null
            RenderPlan.Sequence(RenderPlan.Role.Soundtrack, track, spaced(items, { it.start }, { minOf(it.end, total) }) { item ->
                val seconds = minOf(item.end, total) - item.start
                RenderPlan.Entry.Sound(item, item.start, seconds, (item.sourceStart * 1e6).roundToLong(), ((item.sourceStart + seconds) * 1e6).roundToLong())
            })
        }

    /** The level of a sound of the tracks sample by sample, as [RenderPlan.soundEnvelope]. */
    fun soundEnvelope(item: AudioItem): AudioEnvelopeSegment? = SoundEnvelope.item(sounds, item, plan.duration)

    /** How loud sound track [track] is at [time] in the preview. */
    fun trackLevel(track: Int, time: Double): Double =
        TimelineEditing.audioAt(sounds, track, time)?.let { TimelineEditing.soundLevel(it, time - it.start) } ?: 0.0

    /** Every run of sound of the movie, the pictures first, as [RenderPlan.envelopes]. */
    fun envelopes(): List<AudioEnvelopeSegment> = plan.pictures.indices.mapNotNull(::clipEnvelope) + sounds.mapNotNull(::soundEnvelope)

    private fun <T> spaced(pieces: List<T>, start: (T) -> Double, end: (T) -> Double, entry: (T) -> RenderPlan.Entry): List<RenderPlan.Entry> {
        val entries = mutableListOf<RenderPlan.Entry>()
        var filled = 0.0
        fun gap(until: Double) {
            val micros = ((until - filled) * 1e6).roundToLong()
            if (micros > 0) entries += RenderPlan.Entry.Gap(filled, micros)
        }
        for (piece in pieces) {
            gap(start(piece))
            entries += entry(piece)
            filled = end(piece)
        }
        gap(plan.duration)
        return entries
    }
}
