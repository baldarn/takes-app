package app.takes.core

import kotlin.math.abs

/**
 * One stretch of a sound item's source played without a break: [duration]
 * ticks of the timeline from [start], taking the source from [sourceStart]
 * to [sourceEnd] seconds. [rate] is how fast the source goes by, with the
 * pitch kept.
 */
data class TrackMixRun(val start: EditTime, val duration: EditTime, val sourceStart: Double, val sourceEnd: Double) {
    val end: EditTime get() = start + duration
    val rate: Double get() = if (duration <= EditTime.ZERO) 1.0 else (sourceEnd - sourceStart) / duration.seconds

    /**
     * Whether the run plays its source at speed 1: its source is as long as
     * the run to within the rounding of its two ends to ticks. A sound laid
     * at a second between two ticks has a rate a hair off 1, and that is not
     * a change of speed.
     */
    val atSpeed: Boolean get() = abs((sourceEnd - sourceStart) - duration.seconds) <= 2.0 / EditTime.SCALE

    /**
     * Whether [other] goes through its source as fast as this run: both at
     * speed, or at rates that agree to within what rounding each run's
     * length to a tick moves a rate by. The two pieces of a split clip at
     * four times the speed are at rates a few millionths apart.
     */
    fun sameRate(other: TrackMixRun): Boolean {
        if (atSpeed && other.atSpeed) return true
        val rounding = 1.5 / EditTime.SCALE * (abs(rate) / maxOf(duration.seconds, TICK) + abs(other.rate) / maxOf(other.duration.seconds, TICK))
        return abs(rate - other.rate) < TrackMix.SAME_SOURCE + rounding
    }

    private companion object {
        const val TICK = 1.0 / EditTime.SCALE
    }
}

/**
 * A piece of one sound item that plays on one lane: from [start] to [end] of
 * the timeline, its source cut into [runs] (contiguous in the source, at one
 * rate or at rates that follow each other), processed by [chain], at the
 * level [envelope] gives every sample, with [censors] silenced and toned.
 *
 * [input] names what the lane decodes: the source with the chain that plays
 * on it, so two pieces with equal inputs read the same processed file.
 * [continues] says the piece picks up the sound of the piece before it on
 * its lane exactly where that one left off: no fade at the cut between them,
 * only the level sliding from one's to the other's.
 */
data class TrackMixPiece(
    val owner: String,
    val sourceID: String,
    val lane: Int,
    val start: EditTime,
    val end: EditTime,
    val runs: List<TrackMixRun>,
    val chain: List<AudioEffect>,
    val input: TrackMixInput,
    val envelope: AudioEnvelopeSegment,
    val censors: List<AudioCensorSpan>,
    val continues: Boolean,
) {
    val interval: EditInterval get() = EditInterval(start, end)
}

/** What a lane decodes for a piece: a source and the active stages of the chain on it, without the stages' ids, so the pieces of one split read one file. */
data class TrackMixInput(val sourceID: String, val chain: List<AudioEffect>)

/** One lane of the mix: pieces in time order that never overlap, each starting where or after the one before ends. */
data class TrackMixLane(val index: Int, val pieces: List<TrackMixPiece>) {
    /** The level of the lane sample by sample, for an audio processor ([AudioEnvelope.fill]). */
    fun envelope(live: LiveAudioGain? = null): AudioEnvelope = AudioEnvelope(pieces.map { it.envelope }, live)

    /** The piece playing at [time]; null where the lane is silent. */
    fun at(time: EditTime): TrackMixPiece? {
        var lower = 0
        var upper = pieces.size
        while (lower < upper) {
            val middle = (lower + upper) / 2
            if (pieces[middle].end <= time) lower = middle + 1 else upper = middle
        }
        return pieces.getOrNull(lower)?.takeIf { time >= it.start }
    }
}

/** A stretch of the timeline and the pieces playing in it, by lane. */
data class TrackMixStretch(val interval: EditInterval, val pieces: List<TrackMixPiece>)

/**
 * How the sound of a track timeline is mixed: every sound that plays, on
 * lanes, with its source cuts and its level per sample. The preview's audio
 * engine and the export's both play this, and [TrackMixer] is the reference
 * they are tested against.
 *
 * [duration] is the movie's: as long as its longest item, picture or sound,
 * or a transition's stretch (YouRec/TrackTimeline.swift:503).
 */
data class TrackMixPlan(val duration: EditTime, val lanes: List<TrackMixLane>) {
    val pieces: List<TrackMixPiece> get() = lanes.flatMap { it.pieces }

    /** Every run of sound, lane after lane: what [AudioGainMix] and [AudioHeadroom] work on. */
    fun envelopes(): List<AudioEnvelopeSegment> = lanes.flatMap { lane -> lane.pieces.map { it.envelope } }

    /** The pieces playing at [time], one per lane at most, lowest lane first. */
    fun at(time: EditTime): List<TrackMixPiece> = lanes.mapNotNull { it.at(time) }

    /** The plan with every envelope replaced by [change] of it: for listening to a level or measuring headroom ([AudioGainMix.replacing]). */
    fun withEnvelopes(change: (List<AudioEnvelopeSegment>) -> List<AudioEnvelopeSegment>): TrackMixPlan {
        val all = pieces
        val changed = change(all.map { it.envelope })
        require(changed.size == all.size) { "An envelope for every piece" }
        var next = 0
        return copy(lanes = lanes.map { lane -> lane.copy(pieces = lane.pieces.map { it.copy(envelope = changed[next++]) }) })
    }

    /** The mix for a level being tried or measured: [mode] of [AudioGainMix] for [selection]. */
    fun gainMix(selection: AudioGainSelection, mode: AudioGainMix.Mode): TrackMixPlan = withEnvelopes { AudioGainMix.replacing(it, selection, mode) }

    /** From zero to [duration], cut wherever a piece starts or ends, with the pieces playing in each stretch; stretches of silence are there, empty. */
    val stretches: List<TrackMixStretch>
        get() {
            val edges = sortedSetOf(0L, duration.ticks)
            for (piece in pieces) { edges += piece.start.ticks; edges += piece.end.ticks }
            val points = edges.filter { it in 0..duration.ticks }
            return points.zipWithNext { a, b ->
                val stretch = EditInterval(EditTime(a), EditTime(b))
                TrackMixStretch(stretch, at(EditTime(a)))
            }
        }
}

/**
 * The mix plan of a track timeline (docs/TRACKS-PLAN.md, S5), as the
 * original lays its sound out (YouRec/TrackRenderer.swift:193-298):
 *
 *  - sounds are taken in the order they start, those that start together in
 *    the timeline's order; a muted one, or one whose source has no sound
 *    ([voiced]), takes no lane;
 *  - each is cut where its chain of effects starts or ends and where its
 *    source jumps, holds or falls silent; a silent or held piece plays
 *    nothing;
 *  - a piece goes on the first lane that is free when it starts, with the
 *    same [format] of input, as the original's renderer does;
 *  - a piece that carries on the sound of the last piece of a lane (it starts
 *    where that ends, reads the same input from where that left off, at the
 *    same rate, with the same transition ramps) continues it: no 8 ms fade at
 *    that cut, the level slides over [AudioLevels.GAIN_RAMP];
 *  - every piece has an [AudioEnvelopeSegment] with the 8 ms fades at its
 *    cuts, the ramps of the transitions of the picture it follows, the fades
 *    of its item (up to the whole item, and what a split left of them behind
 *    the edge) and its volume.
 *
 * Where this differs from the original, by choice:
 *
 *  - a piece that continues the sound on some lane goes on that lane, before
 *    the first free one: the original takes the first free lane and, when an
 *    earlier lane is free too, cuts the continuous sound with two fades;
 *  - one envelope per piece rather than per run of the source, so a change
 *    of speed inside one stretch of the source is not cut (the original
 *    renders such a stretch as one file, and its runs then continue);
 *  - the input compares the chain's stages without their ids, so the pieces
 *    of one denoised clip continue, as they do in today's export;
 *  - a transition shorter than one frame of picture shapes no sound, as in
 *    today's export ([TransitionLayout.longEnough]);
 *  - the cuts of the original's preview range are not here: a preview plays
 *    the whole plan from where it seeks.
 */
object TrackMix {
    /** Source seconds closer than this are the same (YouRec/TrackRenderer.swift:207, :285). */
    const val SAME_SOURCE = 0.00001

    fun plan(
        timeline: TrackTimeline,
        voiced: (sourceID: String) -> Boolean = { true },
        format: (TrackMixInput) -> String = { "" },
    ): TrackMixPlan {
        val pictures = timeline.video.associateBy { it.id }
        val incoming = HashMap<String, TimelineTransition>()
        val outgoing = HashMap<String, TimelineTransition>()
        for (relation in timeline.transitions) {
            if (!TransitionLayout.longEnough(relation.timing)) continue
            relation.incoming?.let { incoming.putIfAbsent(it, relation) }
            relation.outgoing?.let { outgoing.putIfAbsent(it, relation) }
        }
        val censored = timeline.audio.any { it.censors.isNotEmpty() }

        class Lane(val index: Int, val format: String) {
            val pieces = ArrayList<TrackMixPiece>()
            val end: EditTime get() = pieces.lastOrNull()?.end ?: EditTime.ZERO
        }
        val lanes = ArrayList<Lane>()

        fun continues(prior: TrackMixPiece, start: EditTime, input: TrackMixInput, first: TrackMixRun, inRamp: Any?, outRamp: Any?): Boolean {
            val last = prior.runs.last()
            return prior.end == start && prior.input == input && abs(last.sourceEnd - first.sourceStart) < SAME_SOURCE &&
                last.sameRate(first) && prior.envelope.inRamp == inRamp && prior.envelope.outRamp == outRamp
        }

        for (item in timeline.audio.withIndex().sortedWith(compareBy({ it.value.start }, { it.index })).map { it.value }) {
            if (item.muted || !voiced(item.sourceID)) continue
            val length = item.mapping.duration
            val points = sortedSetOf(0L, length.ticks)
            for (region in item.enhancements) { points += region.interval.start.ticks; points += region.interval.end.ticks }
            var cursor = EditTime.ZERO
            for ((run, next) in item.mapping.runs.zipWithNext()) {
                cursor += run.duration
                if (abs(run.sourceEnd - next.sourceStart) > SAME_SOURCE || run.silent != next.silent || run.isHold || next.isHold) points += cursor.ticks
            }
            val follows = item.followsVideo?.takeIf { it in pictures }
            val inRamp = follows?.let { incoming[it] }?.let { TransitionSound.rampIn(it.timing, it.origin.seconds) }
            val outRamp = follows?.let { outgoing[it] }?.let { TransitionSound.rampOut(it.timing, it.origin.seconds) }
            val censors = if (censored && item.censors.isNotEmpty()) TrackAudio.censorSpans(timeline, item) else emptyList()

            for ((a, b) in points.filter { it in 0..length.ticks }.zipWithNext()) {
                if (b <= a) continue
                val part = EditInterval(EditTime(a), EditTime(b))
                val map = item.mapping.sliced(part)
                if (map.runs.any { it.silent || it.isHold }) continue
                val chain = item.enhancements.firstOrNull { part.start in it.interval }?.let { AudioChain.active(it.effects) }.orEmpty()
                val input = TrackMixInput(item.sourceID, chain.map { it.copy(id = "") })
                val start = item.start + part.start
                var at = start
                val runs = map.runs.map { run -> TrackMixRun(at, run.duration, run.sourceStart, run.sourceEnd).also { at += run.duration } }
                val first = runs.first()
                val kind = format(input)

                val carried = lanes.firstOrNull { lane -> lane.format == kind && lane.pieces.lastOrNull()?.let { continues(it, start, input, first, inRamp, outRamp) } == true }
                val lane = carried ?: lanes.firstOrNull { lane ->
                    if (lane.end > start || lane.format != kind) return@firstOrNull false
                    // A retimed piece right after another gets a processor of its own unless it carries that one's sound on.
                    val prior = lane.pieces.lastOrNull() ?: return@firstOrNull true
                    lane.end != start || (prior.runs.last().atSpeed && first.atSpeed)
                } ?: Lane(lanes.size, kind).also { lanes += it }
                val prior = if (carried != null) lane.pieces.last() else null
                if (prior != null) lane.pieces[lane.pieces.size - 1] = prior.copy(envelope = prior.envelope.copy(fadeOut = 0.0))

                val envelope = AudioEnvelopeSegment(
                    start = start.seconds, end = (item.start + part.end).seconds,
                    fadeIn = if (prior != null) 0.0 else AudioLevels.CUT_FADE, fadeOut = AudioLevels.CUT_FADE,
                    clipStart = item.start.seconds, clipEnd = item.end.seconds,
                    inRamp = inRamp, outRamp = outRamp, userFadeIn = item.fadeIn, userFadeOut = item.fadeOut,
                    owner = item.id, initialOwner = prior?.owner, initialVolume = prior?.envelope?.volume, volume = item.gain,
                )
                lane.pieces += TrackMixPiece(
                    item.id, item.sourceID, lane.index, start, item.start + part.end, runs, chain, input, envelope,
                    censors,
                    continues = prior != null,
                )
            }
        }
        return TrackMixPlan(timeline.duration, lanes.map { TrackMixLane(it.index, it.pieces.toList()) })
    }
}

/** The mix plan of the timeline ([TrackMix.plan]). */
fun TrackTimeline.mixPlan(voiced: (sourceID: String) -> Boolean = { true }): TrackMixPlan = TrackMix.plan(this, voiced)
