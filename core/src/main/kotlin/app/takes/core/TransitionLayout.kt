package app.takes.core

import kotlin.math.abs

/**
 * Where transitions sit on the timeline and how one that asks for more
 * footage than there is gets shortened. Everything is in timeline seconds:
 * a clip's length here is its played length, after speed and removed pauses.
 *
 * A clip has two slots, one for the transition into it and one for the
 * transition out of it. The footage one transition occupies cannot be given
 * to the other, so three clips never show at once and two transitions never
 * cross. A transition that does not fit is scaled by a factor between 0 and
 * 1, picture and sound together; the settings stay as they were entered.
 *
 * A join is counted from 0: join 0 is the opening of the timeline (edge −1),
 * join n of n clips is its close (edge +1), and join i between them is the
 * one from clip i − 1 to clip i (edge 0).
 */
object TransitionLayout {
    /** Halvings used to find the largest factor that fits. */
    const val FIT_STEPS = 48

    /** The footage assumed on a side that has no clip, and the longest a transition may be. */
    const val LIMIT = TransitionSettings.MAXIMUM_DURATION

    /** One tick of the original's timeline clock; positions closer than two ticks count as equal. */
    const val TICK = 1.0 / 240_000

    private const val FIT_SLACK = 0.00000001
    private const val HANDLE_SLACK = 0.00001

    /** −1 for the opening, +1 for the close, 0 between two clips. */
    fun edge(index: Int, count: Int): Int = if (index == 0) -1 else if (index == count) 1 else 0

    /** A transition shorter than one frame of picture is no transition. */
    fun longEnough(timing: TransitionTiming): Boolean = timing.videoDuration >= PauseDetector.FRAME - 0.000001

    // MARK: Fitting one transition

    /** The largest [factor] at which a transition fits the free footage, and its timing at that factor. */
    data class Fit(val factor: Double, val timing: TransitionTiming)

    /**
     * Seconds of a clip still free for a new transition on one side when the
     * transition on its other side occupies [neighbourCost] of it: the
     * neighbour's [TransitionTiming.rightCost] for the outgoing clip, its
     * [TransitionTiming.leftCost] for the incoming one. A null [length] is a
     * side without a clip.
     */
    fun free(length: Double?, neighbourCost: Double = 0.0): Double = maxOf(0.0, (length ?: LIMIT) - neighbourCost)

    fun fits(settings: TransitionSettings, factor: Double, edge: Int, leftFree: Double, rightFree: Double): Boolean {
        val timing = TransitionTiming.of(settings, factor, edge)
        return timing.leftCost <= leftFree + FIT_SLACK && timing.rightCost <= rightFree + FIT_SLACK
    }

    /**
     * Fits [settings] into [leftFree] seconds of the outgoing clip and
     * [rightFree] seconds of the incoming one (see [free]). At factor 1 when
     * it fits as entered; otherwise the largest factor found by [FIT_STEPS]
     * halvings of 0…1. Null when what is left is under one frame: then there
     * is no transition.
     */
    fun fit(settings: TransitionSettings, edge: Int, leftFree: Double, rightFree: Double): Fit? {
        var factor = 1.0
        if (!fits(settings, 1.0, edge, leftFree, rightFree)) {
            var low = 0.0
            var high = 1.0
            repeat(FIT_STEPS) {
                val middle = (low + high) / 2
                if (fits(settings, middle, edge, leftFree, rightFree)) low = middle else high = middle
            }
            factor = low
        }
        val timing = TransitionTiming.of(settings, factor, edge)
        return if (longEnough(timing)) Fit(factor, timing) else null
    }

    // MARK: Joins

    /** One boundary of the timeline, with or without a transition. */
    data class Join(
        val index: Int,
        /** The moment that counts as the boundary: where the playhead snaps and the marker sits. */
        val time: Double,
        /** The whole affected stretch, picture and sound. */
        val range: ClosedFloatingPointRange<Double>,
        /** The longest duration worth offering for this join. */
        val maximum: Double,
        val transition: TransitionSettings?,
        /** Timeline second where the picture effect starts. */
        val origin: Double = 0.0,
        val timing: TransitionTiming = TransitionTiming(),
        val factor: Double = 1.0,
        val edge: Int = 0,
    ) {
        val videoRange: ClosedFloatingPointRange<Double> get() = origin..(origin + timing.videoDuration)
        val audioIn: ClosedFloatingPointRange<Double> get() = TransitionSound.rampIn(timing, origin)
        val audioOut: ClosedFloatingPointRange<Double> get() = TransitionSound.rampOut(timing, origin)

        /** How far the picture has changed at timeline second [at]. */
        fun progress(at: Double): Double = timing.progress(at - origin)
    }

    // MARK: A plain sequence

    /** Where a clip of a [Sequence] plays. */
    data class Placement(
        val index: Int,
        val start: Double,
        val duration: Double,
        /** Seconds it overlaps the clip before it, and the clip after it. */
        val incoming: Double,
        val outgoing: Double,
        /** The boundaries on either side: the stretch this clip owns. */
        val lower: Double,
        val upper: Double,
    ) {
        val end: Double get() = start + duration
    }

    /** Clips one after another with their transitions resolved. */
    data class Sequence(val placements: List<Placement>, val joins: List<Join>, val duration: Double) {
        /** The transition whose stretch contains [time]. */
        fun transition(time: Double): Join? = joins.firstOrNull { it.transition != null && time >= it.range.start && time < it.range.endInclusive }
    }

    /**
     * Lays out clips that simply follow one another, deriving every factor
     * from the clips themselves: nothing is stored. [durations] are the clips'
     * played lengths; [transitions] has one entry per join, so one more than
     * there are clips (the opening first, the close last; missing entries are
     * plain cuts).
     *
     * When the transitions on a clip's two sides together ask for more than
     * the clip, both are shortened by the same factor, clip by clip from the
     * first. Invalid settings are ignored. With [ids] (the clips' ids), a
     * transition whose [TransitionSettings.nextClipID] is not the clip after
     * it is ignored too.
     */
    fun sequence(durations: List<Double>, transitions: List<TransitionSettings?>, ids: List<String>? = null): Sequence {
        val count = durations.size
        if (count == 0) return Sequence(emptyList(), emptyList(), 0.0)
        val settings = List(count + 1) { index ->
            transitions.getOrNull(index)?.takeIf { it.valid && (ids == null || index == 0 || it.nextClipID == ids.getOrNull(index)) }
        }
        val factors = MutableList(count + 1) { 1.0 }
        fun timing(index: Int, scale: Double = 1.0) = TransitionTiming.of(settings[index], factors[index] * scale, edge(index, count))
        // Adjacent footprints cannot share source time.
        for (index in 0 until count) {
            if (timing(index).rightCost + timing(index + 1).leftCost > durations[index] + FIT_SLACK) {
                var low = 0.0
                var high = 1.0
                repeat(FIT_STEPS) {
                    val middle = (low + high) / 2
                    if (timing(index, middle).rightCost + timing(index + 1, middle).leftCost <= durations[index]) low = middle else high = middle
                }
                factors[index] *= low
                factors[index + 1] *= low
            }
        }
        val times = List(count + 1) { timing(it) }
        val starts = MutableList(count) { 0.0 }
        val origins = MutableList(count + 1) { 0.0 }
        // Sound leading an opening is reserved at time zero instead of moving the first clip negative.
        origins[0] = -minOf(0.0, times[0].rightStart)
        starts[0] = origins[0] + times[0].rightStart
        for (index in 1 until count) {
            origins[index] = starts[index - 1] + durations[index - 1] - times[index].leftEnd
            starts[index] = origins[index] + times[index].rightStart
        }
        val lastEnd = starts[count - 1] + durations[count - 1]
        origins[count] = lastEnd - times[count].leftEnd
        val duration = maxOf(lastEnd, origins[count] + times[count].upper)
        val joins = List(count + 1) { index ->
            val timing = times[index]
            val origin = origins[index]
            val edge = edge(index, count)
            val leftSpace = if (index > 0) maxOf(0.0, durations[index - 1] - times[index - 1].rightCost) else Double.POSITIVE_INFINITY
            val rightSpace = if (index < count) maxOf(0.0, durations[index] - times[index + 1].leftCost) else Double.POSITIVE_INFINITY
            // A fade between two clips takes half its duration from each of them.
            val cost = if (settings[index]?.style == TransitionStyle.FadeColor && edge == 0) 0.5 else 1.0
            val maximum = if (settings[index]?.style == TransitionStyle.Still) LIMIT else maxOf(0.0, minOf(LIMIT, leftSpace / cost, rightSpace / cost))
            Join(
                index, if (index == 0) 0.0 else if (index == count) duration else origin + timing.cut,
                maxOf(0.0, origin + timing.lower)..maxOf(0.0, origin + timing.upper), maximum, settings[index], origin, timing, factors[index], edge,
            )
        }
        val placements = List(count) { index ->
            Placement(index, starts[index], durations[index], maxOf(0.0, times[index].overlap), maxOf(0.0, times[index + 1].overlap), joins[index].time, joins[index + 1].time)
        }
        return Sequence(placements, joins, duration)
    }

    // MARK: Stored relations

    /** A clip's picture on the timeline. */
    data class Picture(val id: String, val start: Double, val duration: Double) {
        val end: Double get() = start + duration
    }

    /**
     * A transition as the original stores it: between [outgoing] and
     * [incoming] (null at the opening and at the close), with the resolved
     * [origin] — the timeline second where the picture effect starts — and the
     * [factor] it was fitted with. Rendering reads these and never recomputes them.
     */
    data class Relation(val outgoing: String?, val incoming: String?, val settings: TransitionSettings, val origin: Double, val factor: Double = 1.0) {
        val edge: Int get() = if (outgoing == null) -1 else if (incoming == null) 1 else 0
        val timing: TransitionTiming get() = TransitionTiming.of(settings, factor, edge)
        val range: ClosedFloatingPointRange<Double> get() = timing.let { maxOf(0.0, origin + it.lower)..maxOf(0.0, origin + it.upper) }
    }

    /** Pictures at explicit places with the transitions relating them. */
    data class Arrangement(val pictures: List<Picture>, val relations: List<Relation> = emptyList()) {
        /** The pictures in the order they play. */
        val chronological: List<Picture> get() = pictures.sortedBy { it.start }

        /** As long as the last picture, or the last transition if it reaches further (a closing freeze, a closing fade's hold). */
        val duration: Double get() = maxOf(pictures.maxOfOrNull { it.end } ?: 0.0, relations.maxOfOrNull { it.range.endInclusive } ?: 0.0)

        /** The transition into the picture [id], and the one out of it. */
        fun entering(id: String): Relation? = relations.firstOrNull { it.incoming == id }
        fun leaving(id: String): Relation? = relations.firstOrNull { it.outgoing == id }

        /** Every boundary between neighbours in playing order, the opening first and the close last. */
        val joins: List<Join>
            get() {
                val ordered = chronological
                if (ordered.isEmpty()) return emptyList()
                return List(ordered.size + 1) { index ->
                    val left = ordered.getOrNull(index - 1)
                    val right = ordered.getOrNull(index)
                    val relation = relations.firstOrNull { it.outgoing == left?.id && it.incoming == right?.id }
                    val maximum = minOf(left?.duration ?: LIMIT, right?.duration ?: LIMIT)
                    val edge = if (left == null) -1 else if (right == null) 1 else 0
                    if (relation == null) {
                        val point = left?.end ?: right?.start ?: 0.0
                        Join(index, point, maxOf(0.0, point)..maxOf(0.0, point), maximum, null, point, edge = edge)
                    } else {
                        val timing = relation.timing
                        val point = if (edge == -1) right!!.start else if (edge == 1) left!!.end else relation.origin + timing.cut
                        Join(index, point, relation.range, maximum, relation.settings, relation.origin, timing, relation.factor, edge)
                    }
                }
            }
    }

    /** Clips placed one after another from zero, with no transitions yet. */
    fun arrangement(ids: List<String>, durations: List<Double>): Arrangement {
        var start = 0.0
        return Arrangement(ids.zip(durations).map { (id, duration) -> Picture(id, start, duration).also { start += duration } })
    }

    /**
     * Sets the transition at join [index] of the pictures in playing order,
     * or removes it when [settings] is null.
     *
     * The new transition takes the two slots it needs: whatever left the
     * outgoing picture or entered the incoming one before is removed. It is
     * fitted ([fit]) into the footage the transitions on the far sides of its
     * two pictures leave free. The outgoing picture stays where it is; the
     * incoming one and everything after it move so that it starts where the
     * transition needs it. Source trims never change.
     *
     * Removing a transition leaves the pictures where they are; see
     * [restoringStraightCut]. A transition that would be shorter than one
     * frame is not created (what it replaced is still removed).
     */
    fun setting(arrangement: Arrangement, settings: TransitionSettings?, index: Int): Arrangement {
        val ordered = arrangement.chronological
        if (index !in 0..ordered.size || ordered.isEmpty()) return arrangement
        val left = ordered.getOrNull(index - 1)
        val right = ordered.getOrNull(index)
        var edit = refitted(arrangement.copy(relations = arrangement.relations.filterNot { it.outgoing == left?.id && it.incoming == right?.id }))
        if (settings == null) return edit
        // Each picture has one incoming and one outgoing slot.
        edit = edit.copy(relations = edit.relations.filterNot { (left != null && it.outgoing == left.id) || (right != null && it.incoming == right.id) })
        val value = settings.copy(nextClipID = right?.id)
        // The transitions on the far sides keep their footage.
        val leftFree = free(left?.duration, left?.let { edit.entering(it.id) }?.timing?.rightCost ?: 0.0)
        val rightFree = free(right?.duration, right?.let { edit.leaving(it.id) }?.timing?.leftCost ?: 0.0)
        val edge = if (left == null) -1 else if (right == null) 1 else 0
        val fit = fit(value, edge, leftFree, rightFree) ?: return edit
        // Sound leading an opening is reserved at time zero instead of moving the first picture negative.
        val origin = left?.let { it.end - fit.timing.leftEnd } ?: maxOf(0.0, (right?.start ?: 0.0) - fit.timing.rightStart)
        if (right != null) edit = shifted(edit, right, left, origin + fit.timing.rightStart - right.start)
        return refitted(edit.copy(relations = edit.relations + Relation(left?.id, right?.id, value, origin, fit.factor)))
    }

    /** Removes the transition at join [index] and closes or opens the timeline again so the two pictures meet at a plain cut. */
    fun restoringStraightCut(arrangement: Arrangement, index: Int): Arrangement {
        val ordered = arrangement.chronological
        val left = ordered.getOrNull(index - 1)
        val right = ordered.getOrNull(index)
        val result = setting(arrangement, null, index)
        if (left == null || right == null) return result
        return refitted(shifted(result, right, left, left.end - right.start))
    }

    /** Moves [right] and everything that starts at or after it, except [left], by [delta], with the transitions wholly among them. */
    private fun shifted(arrangement: Arrangement, right: Picture, left: Picture?, delta: Double): Arrangement {
        val moving = arrangement.pictures.filter { it.id != left?.id && (it.id == right.id || it.start >= right.start) }.map { it.id }.toSet()
        return Arrangement(
            arrangement.pictures.map { if (it.id in moving) it.copy(start = it.start + delta) else it },
            arrangement.relations.map { relation ->
                if (listOfNotNull(relation.outgoing, relation.incoming).all { it in moving }) relation.copy(origin = relation.origin + delta) else relation
            },
        )
    }

    /**
     * Brings the relations back in line with the pictures after pictures were
     * trimmed, moved or removed. A relation whose picture is gone is dropped.
     * Between two pictures, the factor is found again from how far they now
     * overlap ([FIT_STEPS] halvings); the relation is dropped when no factor
     * gives that overlap or when a picture no longer has the footage. A
     * dropped transition does not come back when the footage does.
     */
    fun refitted(arrangement: Arrangement): Arrangement {
        val byID = arrangement.pictures.associateBy { it.id }
        val kept = arrangement.relations.mapNotNull { saved ->
            val a = saved.outgoing?.let { byID[it] }
            val b = saved.incoming?.let { byID[it] }
            if ((saved.outgoing != null && a == null) || (saved.incoming != null && b == null)) return@mapNotNull null
            if (a != null && b != null) {
                val required = a.end - b.start
                var factor = saved.factor
                if (abs(TransitionTiming.of(saved.settings, factor).overlap - required) > 2 * TICK) {
                    val least = TransitionTiming.of(saved.settings, 0.0).overlap
                    val most = TransitionTiming.of(saved.settings, 1.0).overlap
                    if (required < minOf(least, most) - HANDLE_SLACK || required > maxOf(least, most) + HANDLE_SLACK) return@mapNotNull null
                    var low = 0.0
                    var high = 1.0
                    repeat(FIT_STEPS) {
                        val middle = (low + high) / 2
                        if ((TransitionTiming.of(saved.settings, middle).overlap < required) == (most >= least)) low = middle else high = middle
                    }
                    factor = (low + high) / 2
                }
                val timing = TransitionTiming.of(saved.settings, factor)
                if (timing.leftCost > a.duration + HANDLE_SLACK || timing.rightCost > b.duration + HANDLE_SLACK) return@mapNotNull null
                val origin = a.end - timing.leftEnd
                if (abs(origin + timing.rightStart - b.start) > 2 * TICK) return@mapNotNull null
                saved.copy(origin = origin, factor = factor)
            } else {
                val timing = saved.timing
                saved.copy(origin = a?.let { it.end - timing.leftEnd } ?: b?.let { it.start - timing.rightStart } ?: saved.origin)
            }
        }
        // One transition per slot; the one added last wins.
        val entered = mutableSetOf<String>()
        val left = mutableSetOf<String>()
        val unique = kept.asReversed().filter { relation ->
            if ((relation.incoming != null && relation.incoming in entered) || (relation.outgoing != null && relation.outgoing in left)) return@filter false
            relation.incoming?.let { entered += it }
            relation.outgoing?.let { left += it }
            true
        }.asReversed()
        return arrangement.copy(relations = unique)
    }
}

/** What the transition editor starts from and offers, as in the original. */
object TransitionDefaults {
    /** The duration a whip pan gets the first time it is chosen: eight frames. */
    const val WHIP_DURATION = 8.0 / 30

    /** The freeze a still gets the first time it is chosen. */
    const val STILL_DURATION = 1.0

    /** The exit dissolve a still gets when "Dissolve" is chosen after the freeze. */
    const val STILL_DISSOLVE = 0.3

    /**
     * A new transition: a dissolve of half a second, or of the join's
     * [maximum] ([TransitionLayout.Join.maximum]) when that is shorter. On a
     * [canvas] taller than wide, split screen and whip pan start top / bottom.
     */
    fun new(maximum: Double, canvas: Size? = null): TransitionSettings = TransitionSettings(
        duration = minOf(0.5, maximum),
        axis = if (canvas != null && canvas.height > canvas.width) TransitionAxis.Vertical else TransitionAxis.Horizontal,
    )

    /**
     * [settings] after [style] is picked. The first time a style is picked
     * while the editor is open ([visited] holds those picked so far, and the
     * style the editor opened with), a whip pan becomes [WHIP_DURATION] long
     * and a still [STILL_DURATION]; after that the duration is left alone.
     */
    fun choosing(settings: TransitionSettings, style: TransitionStyle, visited: Set<TransitionStyle>): TransitionSettings {
        val duration = when {
            style in visited -> settings.duration
            style == TransitionStyle.Whip -> WHIP_DURATION
            style == TransitionStyle.Still -> STILL_DURATION
            else -> settings.duration
        }
        return settings.copy(style = style, duration = duration)
    }

    /** The top of the duration slider: a freeze needs no footage, so it may be as long as the project, and never under a minute. */
    fun durationMaximum(settings: TransitionSettings, joinMaximum: Double, projectDuration: Double): Double =
        if (settings.style == TransitionStyle.Still) maxOf(60.0, projectDuration, settings.duration) else joinMaximum

    /** How far the sound may be offset or blended: the longest clip, between 2 s and a minute. */
    fun audioMaximum(longestClip: Double?): Double = minOf(60.0, maxOf(2.0, longestClip ?: 2.0))

    /** The audio blend shown while none is set. */
    fun displayedAudioBlend(settings: TransitionSettings, edge: Int): Double =
        settings.audioBlend ?: if (settings.style == TransitionStyle.FadeColor && edge != 0) settings.duration + settings.holdDuration else settings.defaultAudioBlend

    /** The top of the hold slider. */
    fun holdMaximum(durationMaximum: Double): Double = maxOf(10.0, minOf(60.0, durationMaximum))
}
