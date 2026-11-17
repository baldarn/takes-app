package app.takes.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * The sound of a Chroma Key's or an Auto Background's movie, its "Background
 * sound", in the mix: lanes of their own after the edit's, laid out as the
 * original lays out its background tracks
 * (YouRec/VideoEffects/VideoEffectBackgroundAudio.swift:27-145):
 *
 *  - for every piece a picture plays (a stretch of its source at one rate, or
 *    its held frame) and every Chroma Key or Auto Background on in it, the
 *    stretch of the effect inside the piece, cut at the keys of its media,
 *    background and Background sound; a stretch sounds where, at its start,
 *    Background sound is on, the background is Video and its movie has sound;
 *  - the movie plays from the effect's start and loops as its picture does
 *    ([EffectBackgroundLoop]): each turn of the loop plays the movie whole,
 *    the next starting one overlap before it ends, the two crossfaded in
 *    straight lines over the overlap; at the picture's rate, its pitch kept;
 *  - every stretch at −18 dB, with the 8 ms fades of a cut at its ends, those
 *    between stretches that carry one another on taken away, and the ramps of
 *    the picture's transitions; on a held frame the movie plays on, silent
 *    when the Freeze Frame cuts the sound and fading to nothing over the hold
 *    when it fades it out;
 *  - the stretches that carry one another on go on one lane, one after the
 *    other; each such run of them on the first lane free when it starts.
 *
 * A movie is read under [sourceID] of its file in the project's folder.
 */
object EffectBackgroundSound {
    /** The level of the movie's sound: −18 dB. VideoEffectBackgroundAudio.swift:71. */
    val VOLUME: Double = 10.0.pow(-18.0 / 20)

    private const val PREFIX = "effect-media:"

    /** The source a movie [file] of the project's folder is read under in the mix. */
    fun sourceID(file: String): String = PREFIX + file

    /** The project file a source of [sourceID] reads; null for any other source. */
    fun file(sourceID: String): String? = if (sourceID.startsWith(PREFIX)) sourceID.removePrefix(PREFIX) else null

    /**
     * A movie as the mix plays it: [duration] seconds long, the length its
     * picture loops over (the asset's), its sound from [soundStart] to
     * [soundEnd] seconds (the sound track's range). VideoEffectBackgroundAudio.swift:50-57.
     */
    data class Movie(val duration: Double, val soundStart: Double, val soundEnd: Double)

    /** The effects that can put a movie's sound in. VideoEffectBackgroundAudio.swift:38. */
    private val KINDS = setOf(VideoEffectID.ChromaKey, VideoEffectID.AutoBackground)

    /** The keyed controls that cut a stretch. VideoEffectBackgroundAudio.swift:44. */
    private val KEYED = setOf("media", "background", "backgroundAudio")

    /**
     * The movies of the project's folder whose sound [timeline] may play:
     * those named by a Chroma Key or an Auto Background that is on and turns
     * Background sound on somewhere. What the mix needs read ([Movie]).
     */
    fun files(timeline: TrackTimeline): Set<String> {
        val names = sortedSetOf<String>()
        for (item in timeline.video) {
            for (effect in item.content.videoEffects.orEmpty()) {
                if (!effect.enabled || effect.kind !in KINDS) continue
                val sound = effect.parameters.firstOrNull { it.id == "backgroundAudio" } ?: continue
                if ((listOf(sound.value) + sound.keyframes.map { it.value }).none { (it as? EffectValue.Toggle)?.value == true }) continue
                val media = effect.parameters.firstOrNull { it.id == "media" } ?: continue
                for (value in listOf(media.value) + media.keyframes.map { it.value }) (value as? EffectValue.Media)?.file?.let { names += it }
            }
        }
        return names
    }

    private class Item(
        val effect: String,
        val identity: String,
        val sourceID: String,
        val start: Double,
        val duration: Double,
        val sourceStart: Double,
        val sourceEnd: Double,
        var envelope: AudioEnvelopeSegment,
    ) {
        val end: Double get() = start + duration

        fun carriesOn(next: Item): Boolean = abs(end - next.start) < 0.0001 && abs(sourceEnd - next.sourceStart) < 0.0001
    }

    /**
     * [base], the mix of [timeline], with the sound of its background movies
     * on lanes after its own: [movies] gives each movie file that has sound
     * (one missing, or without sound, plays nothing).
     */
    fun plan(base: TrackMixPlan, timeline: TrackTimeline, movies: Map<String, Movie>): TrackMixPlan {
        val items = items(timeline, movies)
        if (items.isEmpty()) return base
        // Stretches of one turn of one movie under one effect that follow each other in time and in the movie carry
        // each other on: no fade between them, one lane. VideoEffectBackgroundAudio.swift:84-122.
        val groups = LinkedHashMap<String, MutableList<Item>>()
        for (item in items) groups.getOrPut(item.identity) { ArrayList() } += item
        val chains = ArrayList<List<Item>>()
        for (group in groups.values) {
            val ordered = group.sortedBy { it.start }
            for ((a, b) in ordered.zipWithNext()) {
                if (a.carriesOn(b)) {
                    a.envelope = a.envelope.copy(fadeOut = 0.0)
                    b.envelope = b.envelope.copy(fadeIn = 0.0)
                }
            }
            var chain = ArrayList<Item>()
            for (item in ordered) {
                if (chain.isNotEmpty() && !chain.last().carriesOn(item)) { chains += chain; chain = ArrayList() }
                chain += item
            }
            if (chain.isNotEmpty()) chains += chain
        }
        // Each run on the first lane free when it starts, else a new one. VideoEffectBackgroundAudio.swift:123-137.
        val lanes = ArrayList<MutableList<TrackMixPiece>>()
        val ends = ArrayList<Double>()
        for (chain in chains.sortedBy { it.first().start }) {
            val first = chain.first()
            var index = ends.indexOfFirst { it <= first.start + 0.000001 }
            if (index < 0) { index = lanes.size; lanes += ArrayList<TrackMixPiece>(); ends += 0.0 }
            val lane = base.lanes.size + index
            chain.forEachIndexed { position, item ->
                val start = EditTime.of(item.start)
                val end = EditTime.of(item.end)
                val run = TrackMixRun(start, end - start, item.sourceStart, item.sourceEnd)
                lanes[index] += TrackMixPiece(
                    item.effect, item.sourceID, lane, start, end, listOf(run), emptyList(), TrackMixInput(item.sourceID, emptyList()),
                    item.envelope, emptyList(), continues = position > 0,
                )
            }
            ends[index] = chain.last().end
        }
        return base.copy(lanes = base.lanes + lanes.mapIndexed { index, pieces -> TrackMixLane(base.lanes.size + index, pieces) })
    }

    /** Every stretch of a movie's sound, picture by picture. VideoEffectBackgroundAudio.swift:27-83. */
    private fun items(timeline: TrackTimeline, movies: Map<String, Movie>): List<Item> {
        val incoming = HashMap<String, TimelineTransition>()
        val outgoing = HashMap<String, TimelineTransition>()
        for (relation in timeline.transitions) {
            if (!TransitionLayout.longEnough(relation.timing)) continue
            relation.incoming?.let { incoming.putIfAbsent(it, relation) }
            relation.outgoing?.let { outgoing.putIfAbsent(it, relation) }
        }
        val items = ArrayList<Item>()
        for (picture in timeline.chronologicalVideo) {
            val clip = picture.content
            if (clip.videoEffects.orEmpty().none { it.enabled && it.kind in KINDS }) continue
            val inRamp = incoming[picture.id]?.let { TransitionSound.rampIn(it.timing, it.origin.seconds) }
            val outRamp = outgoing[picture.id]?.let { TransitionSound.rampOut(it.timing, it.origin.seconds) }
            var pieceStart = picture.start.seconds
            for (piece in pieces(clip)) {
                val pieceAt = pieceStart
                pieceStart += piece.duration
                val freeze = clip.frozenFrame
                val freezeEffect = clip.videoEffects.orEmpty().firstOrNull { it.kind == VideoEffectID.FreezeFrame }
                val freezeMode = freezeEffect?.choice("audio", clip.start) ?: "Continue"
                if (freeze != null && freezeMode == "Cut") continue
                val sourceStart = piece.start + (freeze?.offset ?: 0.0)
                for (effect in clip.videoEffects.orEmpty()) {
                    if (!effect.enabled || effect.kind !in KINDS) continue
                    if (freeze != null && !(effect.start <= piece.start && effect.end > piece.start)) continue
                    val lower = if (freeze == null) maxOf(piece.start, effect.start) else sourceStart
                    val upper = if (freeze == null) minOf(piece.end, effect.end) else sourceStart + piece.duration
                    if (upper <= lower) continue
                    // The keys of the media, the background and Background sound cut the stretch. VideoEffectBackgroundAudio.swift:43-45.
                    val keys = if (freeze != null) emptyList() else effect.parameters.filter { it.id in KEYED }.flatMap { p -> p.keyframes.map { it.time } }
                    val bounds = (listOf(lower, upper) + keys.filter { it > lower && it < upper }).toSortedSet().toList()
                    for ((a, b) in bounds.zipWithNext()) {
                        val setting = if (freeze == null) a else piece.start
                        if (!effect.toggle("backgroundAudio", setting) || effect.choice("background", setting) != "Video") continue
                        val file = (effect.value("media", setting) as? EffectValue.Media)?.file ?: continue
                        val movie = movies[file] ?: continue
                        if (!(movie.duration > 0.001) || !movie.duration.isFinite()) continue
                        val loop = EffectBackgroundLoop(movie.duration)
                        val localA = maxOf(0.0, a - effect.start)
                        val localB = b - effect.start
                        val first = maxOf(0, floor(localA / loop.period).toInt() - 1)
                        val last = floor(localB / loop.period).toInt()
                        for (cycle in first..last) {
                            val origin = cycle * loop.period
                            val begin = maxOf(localA, origin + movie.soundStart)
                            val end = minOf(localB, origin + minOf(movie.duration, movie.soundEnd))
                            if (end - begin <= 1.0 / 60000) continue
                            val start = pieceAt + (effect.start + begin - sourceStart) / piece.rate
                            val output = (end - begin) / piece.rate
                            val loopStart = pieceAt + (effect.start + origin - sourceStart) / piece.rate
                            var envelope = AudioEnvelopeSegment(
                                start = start, end = start + output, fadeIn = AudioLevels.CUT_FADE, fadeOut = AudioLevels.CUT_FADE,
                                clipStart = loopStart, clipEnd = loopStart + movie.duration / piece.rate,
                                transitionIn = if (cycle > 0) loop.overlap / piece.rate else 0.0, transitionOut = loop.overlap / piece.rate,
                                inRamp = inRamp, outRamp = outRamp, volume = VOLUME,
                            )
                            if (freeze != null && freezeMode == "Fade out over hold") {
                                // VideoEffectBackgroundAudio.swift:72-77.
                                val hold = maxOf(0.001, freezeEffect?.number("hold", clip.start) ?: freeze.duration)
                                val from = freeze.offset + start - pieceAt
                                envelope = envelope.copy(volume = VOLUME * maxOf(0.0, 1 - from / hold), endVolume = VOLUME * maxOf(0.0, 1 - (from + output) / hold))
                            }
                            items += Item(effect.id, "$file|${effect.id}|$cycle", sourceID(file), start, output, begin - origin, end - origin, envelope)
                        }
                    }
                }
            }
        }
        return items
    }

    /** A stretch of a clip's source at one rate, as the original's playback pieces: its held frame whole, or each span. YouRec/VideoTools.swift:278-293. */
    private class Piece(val start: Double, val end: Double, val rate: Double, val duration: Double)

    private fun pieces(clip: TimelineClip): List<Piece> {
        clip.frozenFrame?.let { hold ->
            val rate = SpeedMapping.rate(clip.speed, VideoEffectSettings.sequenceRate(clip.videoEffects.orEmpty(), clip.start))
            return listOf(Piece(clip.start, clip.end, rate, hold.duration))
        }
        return clip.playbackSpans.map { span ->
            val rate = minOf(SpeedMapping.MAXIMUM_PLAYBACK_RATE, maxOf(SpeedMapping.MINIMUM_PLAYBACK_RATE, span.rate))
            Piece(span.start, span.end, rate, (span.end - span.start) / rate)
        }
    }
}
