package app.takes.core

/** Pure timeline operations. Each returns a new state, or null when it does not apply. */
object TimelineEditing {
    /** Shortest piece an edit may leave behind, in seconds. */
    const val MINIMUM_CLIP = 0.1

    data class Location(val index: Int, val offset: Double)

    // The layout of the list asked about last: an edit asks many questions about the same clips.
    @Volatile private var laidOut: Pair<List<TimelineClip>, TransitionLayout.Sequence>? = null

    /**
     * Where every clip plays and what happens at every join. Join 0 is the
     * opening of the first clip, join n the close of the last, and join i the
     * one into the clip at i. The opening of another clip and the close of
     * another are among the layout's [TransitionLayout.Sequence.fades], under
     * numbers of their own ([TransitionLayout.openingIndex],
     * [TransitionLayout.closeIndex]): they stay with their clip when it is no
     * longer the first or the last, as in the original, where an opening and
     * a close belong to their picture (Transitions.swift:299-322,
     * TrackTimeline.swift:881-885). A transition plays as it was fitted when its fit is saved
     * ([TimelineClip.transitionFit]); one saved without a fit is fitted to
     * the footage around it here, by [TransitionLayout.sequence]'s rule, and
     * [fitted] is what saves it.
     */
    fun layout(clips: List<TimelineClip>): TransitionLayout.Sequence {
        laidOut?.let { (list, sequence) -> if (list === clips) return sequence }
        val sequence = TransitionLayout.sequence(
            clips.map { it.duration }, listOf(clips.firstOrNull()?.openingTransition) + clips.map { it.transition }, clips.map { it.id },
            listOf(clips.firstOrNull()?.openingTransitionFit?.factor) + clips.map { it.transitionFit?.factor },
            clips.map { it.openingTransition }, clips.map { it.openingTransitionFit?.factor },
        )
        laidOut = clips to sequence
        return sequence
    }

    /**
     * The clips with the fit of every transition in play saved on them, as
     * [layout] finds it: the conversion the original makes once of a timeline
     * saved without fits. The same list, the same instance, when nothing was
     * missing. Every edit that moves footage starts from this, so from then
     * on its rule decides what becomes of each fit and none is derived again.
     */
    fun fitted(clips: List<TimelineClip>): List<TimelineClip> {
        if (clips.isEmpty()) return clips
        val layout = layout(clips)
        var changed = false
        val next = clips.mapIndexed { index, clip ->
            val opening = layout.join(TransitionLayout.openingIndex(index, clips.size))?.takeIf { it.transition != null }?.let { TransitionFit(it.factor) } ?: clip.openingTransitionFit
            val leaving = layout.leaving(index)?.takeIf { it.transition != null }?.let { TransitionFit(it.factor) } ?: clip.transitionFit
            if (opening == clip.openingTransitionFit && leaving == clip.transitionFit) clip else {
                changed = true
                clip.copy(openingTransitionFit = opening, transitionFit = leaving)
            }
        }
        return if (changed) next else clips
    }

    /** Where the settings of a join are saved: on the clip at [clip], as its opening or as what it has for its end. */
    private data class Saved(val clip: Int, val opening: Boolean)

    /** Where join [join] of [count] clips keeps its settings; null when there is no such join. */
    private fun saved(join: Int, count: Int): Saved? = when {
        count == 0 || join < 0 -> null
        join == 0 -> Saved(0, true)
        join <= count -> Saved(join - 1, false)
        join < 2 * count -> Saved(join - count, true)
        join < 3 * count - 1 -> Saved(join - 2 * count, false)
        else -> null
    }

    /** The clips with the transition of join [join] and its fit replaced; null removes both. */
    private fun withJoin(clips: List<TimelineClip>, join: Int, settings: TransitionSettings?, fit: TransitionFit?): List<TimelineClip> {
        val (clip, opening) = saved(join, clips.size) ?: return clips
        return clips.toMutableList().also {
            if (opening) it[clip] = clips[clip].copy(openingTransition = settings, openingTransitionFit = fit.takeIf { settings != null })
            else it[clip] = clips[clip].copy(transition = settings, transitionFit = fit.takeIf { settings != null })
        }
    }

    /**
     * The original's guard after every edit: a transition between two clips
     * that costs more footage than one of them has is removed. The rules of
     * the edits here never leave one, so this is a net and not a rule.
     */
    private fun pruned(clips: List<TimelineClip>): List<TimelineClip> {
        var result = clips
        for (join in layout(clips).joins) {
            if (join.transition == null || join.edge != 0) continue
            if (join.timing.leftCost > clips[join.index - 1].duration + PRUNE_SLACK || join.timing.rightCost > clips[join.index].duration + PRUNE_SLACK) {
                result = withJoin(result, join.index, null, null)
            }
        }
        return result
    }

    private const val PRUNE_SLACK = 0.00001

    /** Factors closer than this are the same fit. */
    private const val SAME_FIT = 0.000000001

    /**
     * After the clip at [index] was made shorter without losing footage (a
     * faster speed, pauses taken out): when the transition into it and the
     * one out of it together cost more than it now lasts, both are scaled by
     * the largest factor that fits, as the original does. Otherwise nothing
     * changes, and a transition never grows here.
     */
    private fun refitted(clips: List<TimelineClip>, index: Int): List<TimelineClip> {
        val layout = layout(clips)
        val sides = listOf(layout.entering(index)!!, layout.leaving(index)!!)
        fun cost(scale: Double) = TransitionTiming.of(sides[0].transition, sides[0].factor * scale, sides[0].edge).rightCost +
            TransitionTiming.of(sides[1].transition, sides[1].factor * scale, sides[1].edge).leftCost
        val length = clips[index].duration
        if (cost(1.0) <= length) return clips
        var low = 0.0
        var high = 1.0
        repeat(TransitionLayout.FIT_STEPS) {
            val middle = (low + high) / 2
            if (cost(middle) <= length) low = middle else high = middle
        }
        var result = clips
        for (side in sides) if (side.transition != null) result = withJoin(result, side.index, side.transition, TransitionFit(side.factor * low))
        return result
    }

    /** The join numbered [index], which for the first numbers is the one into the clip at [index]; null when it is a plain cut, or when its transition is left with less than a frame. */
    fun join(clips: List<TimelineClip>, index: Int): TransitionLayout.Join? = layout(clips).join(index)?.takeIf(::inPlay)

    private fun inPlay(join: TransitionLayout.Join) = join.transition != null && TransitionLayout.longEnough(join.timing)

    /** Every transition in play, in the order of their numbers: the opening and the close of any clip among them. */
    fun transitions(clips: List<TimelineClip>): List<TransitionLayout.Join> = layout(clips).all.filter(::inPlay)

    /** The transition at the start of the clip at [index]: the one from the clip before, or the clip's own opening. Null when it starts on a plain cut. */
    fun entering(clips: List<TimelineClip>, index: Int): TransitionLayout.Join? = layout(clips).entering(index)?.takeIf(::inPlay)

    /** The transition at the end of the clip at [index]: the one into the clip after, or the clip's own close. Null when it ends on a plain cut. */
    fun leaving(clips: List<TimelineClip>, index: Int): TransitionLayout.Join? = layout(clips).leaving(index)?.takeIf(::inPlay)

    /** Timeline second where the clip at [index] begins: under the end of the previous clip when they overlap, after a pause when time is inserted. Past the last clip, the end. */
    fun clipStart(clips: List<TimelineClip>, index: Int): Double {
        val layout = layout(clips)
        return layout.placements.getOrNull(index)?.start ?: if (index < 0) 0.0 else layout.duration
    }

    /** As long as the last clip plays, or longer when the timeline closes on a freeze or a hold. */
    fun duration(clips: List<TimelineClip>): Double = layout(clips).duration

    /**
     * The clip the playhead is on at [time]. A cut belongs to the clip that
     * starts there; a transition changes hands at its boundary, the middle of
     * a dissolve or the end of a freeze. The end belongs to the last clip.
     * While no clip plays (a hold on a color, a freeze) the offset stays at
     * the edge of the clip the moment belongs to.
     */
    fun locate(clips: List<TimelineClip>, time: Double): Location? {
        if (clips.isEmpty() || !time.isFinite()) return null
        val placements = layout(clips).placements
        val found = placements.firstOrNull { time < it.upper } ?: placements.last()
        return Location(found.index, (time - found.start).coerceIn(0.0, found.duration))
    }

    /**
     * A run of clips that follow one another without a break, so one player
     * can run it as a gapless playlist. Wherever two clips play at once or
     * time is inserted between them a new chain begins; neighboring chains
     * are shown by different players.
     */
    data class Chain(val first: Int, val last: Int, val start: Double, val end: Double)

    fun chains(clips: List<TimelineClip>): List<Chain> {
        val placements = layout(clips).placements
        val chains = mutableListOf<Chain>()
        var first = 0
        placements.forEachIndexed { index, placement ->
            val next = placements.getOrNull(index + 1)
            if (next == null || kotlin.math.abs(next.start - placement.end) > 2 * TransitionLayout.TICK) {
                chains += Chain(first, index, placements[first].start, placement.end)
                first = index + 1
            }
        }
        return chains
    }

    /**
     * Cuts the clip showing at [time] in two. Refused inside a transition.
     * No fit changes: the transition into the clip stays on the first piece
     * and the one out of it goes with the second, as they were.
     */
    fun split(state: EditState, time: Double): EditState? {
        val clips = fitted(state.clips)
        val (index, offset) = locate(clips, time) ?: return null
        val clip = clips[index]
        if (offset < MINIMUM_CLIP || clip.duration - offset < MINIMUM_CLIP) return null
        // Not inside a transition: the picture or the sound of two clips is in play there.
        if (layout(clips).all.any { it.transition != null && time > it.range.start && time < it.range.endInclusive }) return null
        val cut = clip.sourceTime(offset)
        // The join after the clip now follows the second piece. Both pieces keep every title, as in the original: one that
        // lies in the other piece's footage does not show, and shows again when the piece is extended over that footage.
        val (before, after) = clip.bookmarks.orEmpty().partition { it.time < cut }
        // Each piece keeps the level, and of each fade what falls in it: a cut inside a fade leaves both pieces its level and slope there.
        val pieces = listOf(
            clip.copy(end = cut, transition = null, transitionFit = null, bookmarks = before.ifEmpty { null },
                audio = clip.audio?.sliced(0.0, offset, clip.duration).normalized()),
            // A copy, so whatever else a clip carries, such as its color, stays on both pieces.
            clip.copy(id = newID(), start = cut, openingTransition = null, openingTransitionFit = null, bookmarks = after.ifEmpty { null },
                audio = clip.audio?.sliced(offset, clip.duration, clip.duration).normalized()),
        )
        return state.copy(clips = pruned(clips.take(index) + pieces + clips.drop(index + 1)))
    }

    /** Removes the clip and closes the gap. Its transitions go with it; every other keeps its fit. */
    fun delete(state: EditState, index: Int): EditState? {
        if (index !in state.clips.indices) return null
        return state.copy(clips = pruned(withoutBrokenJoins(fitted(state.clips).filterIndexed { position, _ -> position != index })))
    }

    /** Moves the clip. A transition whose two clips are no longer neighbors is removed; every other keeps its fit. */
    fun move(state: EditState, from: Int, to: Int): EditState? {
        if (from !in state.clips.indices || to !in state.clips.indices || from == to) return null
        val clips = fitted(state.clips).toMutableList()
        clips.add(to, clips.removeAt(from))
        return state.copy(clips = pruned(withoutBrokenJoins(clips)))
    }

    /** Times this close to a join are on it, as in the original's paste. */
    const val JOIN_REACH = 0.0001

    /**
     * The timeline made ready for footage to go in at [point], as the
     * original's paste does: the clips, and the join the footage goes on when
     * [point] is on the join of a transition (the footage then goes between
     * its two clips, which ends it). Otherwise every transition running at
     * [point] is removed, so both its clips have their whole length again,
     * and the clip then showing at that second is the one to cut.
     */
    internal fun opened(clips: List<TimelineClip>, point: Double): Pair<List<TimelineClip>, Int?> {
        var result = fitted(clips)
        (1 until result.size).firstOrNull { index -> join(result, index)?.let { kotlin.math.abs(it.time - point) < JOIN_REACH } == true }?.let { return result to it }
        // A transition gone moves the clips after it, which can bring another one over the point.
        while (true) {
            val crossed = transitions(result).filter { point > it.range.start && point < it.range.endInclusive }
            if (crossed.isEmpty()) break
            for (join in crossed) result = withJoin(result, join.index, null, null)
        }
        return result to null
    }

    /**
     * Puts [clips] into the timeline at [time], in order; later clips ripple.
     * Inside a clip, the clip is split there and they go between the pieces.
     * Inside a transition, the transition is removed and the clip then
     * showing at that second is split, as the original's paste does and as
     * [RangeEditing.paste] does ([opened]); on the join of a transition they
     * go between its two clips. Where a split is refused (closer than
     * [MINIMUM_CLIP] to an edge) they go to the nearer edge of the clip
     * instead. A transition on the join they land on is removed, as when a
     * clip is moved there: it belonged to those neighbors. Every other
     * transition keeps its fit.
     * Null when there is nothing to insert or a clip is already on the timeline.
     */
    fun insert(state: EditState, clips: List<TimelineClip>, time: Double): EditState? {
        if (clips.isEmpty() || !time.isFinite()) return null
        val ids = clips.map { it.id }.toSet()
        if (ids.size != clips.size || state.clips.any { it.id in ids }) return null
        val at = time.coerceIn(0.0, state.duration)
        var (base, index) = opened(state.clips, at).let { it.first to (it.second ?: -1) }
        if (index < 0) {
            index = 0
            locate(base, at)?.let { (found, offset) ->
                val cut = split(state.copy(clips = base), at)
                if (cut != null) { base = cut.clips; index = found + 1 }
                else index = if (offset <= base[found].duration / 2) found else found + 1
            }
        }
        return state.copy(clips = pruned(withoutBrokenJoins(base.take(index) + clips + base.drop(index))))
    }

    /**
     * The clip with new source bounds, kept inside its source and at least
     * [MINIMUM_CLIP] long. Moving one edge never moves the other.
     *
     * A fade follows its footage, as in the original, where a trim is a cut
     * and what is cut off removed: an edge moved into a fade leaves the rest
     * of it, from the level it had there, and moved back out gives it back.
     */
    fun trimmed(clip: TimelineClip, start: Double, end: Double, sourceDuration: Double): TimelineClip {
        if (!start.isFinite() || !end.isFinite()) return clip
        val limit = maxOf(sourceDuration, clip.end)
        // The shortest clip is measured on the timeline, so in source seconds it scales with the speed.
        val least = MINIMUM_CLIP * clip.rate
        val newStart = if (start == clip.start) start else start.coerceIn(0.0, maxOf(0.0, clip.end - least))
        val newEnd = if (end == clip.end) end else end.coerceIn(minOf(limit, newStart + least), limit)
        var audio = clip.audio
        if (audio != null && (newStart != clip.start || newEnd != clip.end)) {
            val head = (newStart - clip.start) / clip.rate
            val tail = (clip.end - newEnd) / clip.rate
            if (head > 0 || tail > 0) audio = audio.sliced(maxOf(0.0, head), clip.duration - maxOf(0.0, tail), clip.duration)
            if (head < 0) audio = audio.extended(-head, leading = true)
            if (tail < 0) audio = audio.extended(-tail, leading = false)
            audio = audio.normalized()
        }
        return clip.copy(start = newStart, end = newEnd, audio = audio)
    }

    /**
     * Changes the clip's source range; later clips ripple. As in the
     * original, when the transition into the clip and the one out of it
     * together cost more footage than the clip is left with, both are
     * removed, and they do not come back when the clip is extended again.
     * Otherwise no fit changes, however much room the clip gains.
     */
    fun trim(state: EditState, index: Int, start: Double, end: Double, sourceDuration: Double): EditState? {
        val clip = state.clips.getOrNull(index) ?: return null
        if (trimmed(clip, start, end, sourceDuration) == clip) return null
        var clips = fitted(state.clips)
        val next = trimmed(clips[index], start, end, sourceDuration)
        val sides = layout(clips).let { listOf(it.entering(index)!!, it.leaving(index)!!) }
        clips = clips.toMutableList().also { it[index] = next }
        if (sides[0].timing.rightCost + sides[1].timing.leftCost > next.duration) {
            for (join in sides) if (join.transition != null) clips = withJoin(clips, join.index, null, null)
        }
        return state.copy(clips = pruned(clips))
    }

    /**
     * Removes stretches of source time from the clip at [index], such as
     * detected pauses, leaving the pieces in between as separate clips with
     * the gaps closed. Pieces too short to stand alone are dropped with the
     * cut. Titles stay on every piece. Null when nothing would change or
     * nothing would be left.
     *
     * The transition into the clip stays on the first piece and the one out
     * of it on the last. One that now costs more than its piece lasts is
     * scaled to fit it, as the original does when pauses make a clip shorter
     * (there a clip stays one clip; refitting each to its piece is this
     * port's reading for separate pieces).
     */
    fun removeIntervals(state: EditState, index: Int, cuts: List<SourceInterval>): EditState? {
        val clip = fitted(state.clips).getOrNull(index) ?: return null
        val kept = PauseDetector.keptIntervals(clip, cuts).filter { it.duration / clip.rate >= MINIMUM_CLIP }
        if (kept.isEmpty() || (kept.size == 1 && kept[0].start == clip.start && kept[0].end == clip.end)) return null
        // The fades stay those of the whole clip, now shorter, as in the original, where a clip without its pauses is
        // still one item: squeezed when it has become shorter than they are, and each piece plays its part of them.
        val lengths = kept.map { it.duration / clip.rate }
        val sound = clip.audio?.fitted(clip.duration, lengths.sum())
        val pieces = kept.mapIndexed { position, part ->
            val first = position == 0
            val last = position == kept.lastIndex
            clip.copy(
                // The first piece stays the clip the previous one joins; the last carries the join to the next.
                id = if (first) clip.id else newID(),
                start = part.start, end = part.end,
                transition = if (last) clip.transition else null,
                transitionFit = if (last) clip.transitionFit else null,
                openingTransition = if (first) clip.openingTransition else null,
                openingTransitionFit = if (first) clip.openingTransitionFit else null,
                // Every piece keeps every title, as the pieces of a split do: a title shows wherever its footage is played.
                bookmarks = clip.bookmarks?.filter { it.time >= part.start && it.time < part.end }?.ifEmpty { null },
                audio = sound?.sliced(lengths.take(position).sum(), lengths.take(position + 1).sum(), lengths.sum()).normalized(),
            )
        }
        val before = fitted(state.clips)
        var clips = refitted(before.take(index) + pieces + before.drop(index + 1), index)
        if (pieces.size > 1) clips = refitted(clips, index + pieces.lastIndex)
        return state.copy(clips = pruned(clips))
    }

    // MARK: Transitions

    /**
     * A transition between two clips belongs to those exact neighbors. After
     * clips are removed, moved or inserted, a join whose next clip is another
     * one is a plain cut again. An opening and a close belong to their clip
     * alone and go where it goes, as in the original: a clip put before the
     * first or after the last leaves them playing with their picture.
     */
    private fun withoutBrokenJoins(clips: List<TimelineClip>): List<TimelineClip> = clips.mapIndexed { index, clip ->
        val broken = clip.transition != null && clip.transition.nextClipID != null && clip.transition.nextClipID != clips.getOrNull(index + 1)?.id
        if (broken) clip.copy(transition = null, transitionFit = null) else clip
    }

    /** The transition into the clip after [index], if those two clips are still neighbors. */
    fun transition(clips: List<TimelineClip>, index: Int): TransitionSettings? =
        clips.getOrNull(index)?.transition?.takeIf { index + 1 < clips.size && it.nextClipID == clips[index + 1].id }

    /**
     * The settings saved for join [join]: 0 is the opening of the first clip,
     * the number of clips the close of the last, and the numbers of
     * [TransitionLayout.openingIndex] and [TransitionLayout.closeIndex] the
     * opening and the close of any other clip.
     */
    fun joinSettings(clips: List<TimelineClip>, join: Int): TransitionSettings? {
        val (clip, opening) = saved(join, clips.size) ?: return null
        return when {
            opening -> clips[clip].openingTransition
            join in 1 until clips.size -> transition(clips, clip)
            else -> clips[clip].transition?.takeIf { it.nextClipID == null }
        }
    }

    /**
     * Sets the transition at join [join] (0 the opening, the number of clips
     * the close), or restores the plain cut when [settings] is null.
     *
     * As in the original, the settings are kept as entered and the
     * transition is fitted, from its full length, into the footage the
     * transitions on the far sides of its two clips leave free; those keep
     * their fits and are never shortened to make room, so the order in which
     * transitions are set matters. The factor found is saved with it. Setting
     * the same settings again fits it again, which is the one moment a
     * shortened transition gets back the length its clips have gained since.
     *
     * Null when nothing changes, when the settings are out of range, or when
     * what fits is shorter than one frame: that is no transition.
     */
    fun setJoin(state: EditState, join: Int, settings: TransitionSettings?): EditState? {
        val place = saved(join, state.clips.size) ?: return null
        var clips = fitted(state.clips)
        val between = join in 1 until clips.size
        // The clips on its two sides: an opening has none before it, a close none after it.
        val left = if (place.opening) null else place.clip
        val right = if (place.opening) place.clip else if (between) join else null
        val value = settings?.copy(nextClipID = right?.let { clips[it].id })
        if (value != null && !value.valid) return null
        val current = joinSettings(clips, join)
        // The opening of a clip that is not the first and the close of one that is not the last are there because their
        // clip was once at that end: they can be changed and removed, not made.
        if (current == null && join > clips.size) return null
        if (value == null) return if (current == null) null else state.copy(clips = withJoin(clips, join, null, null))
        val before = layout(clips)
        // The transitions on the far sides keep their footage.
        val leftFree = TransitionLayout.free(left?.let { clips[it].duration }, left?.let { before.entering(it)?.timing?.rightCost } ?: 0.0)
        val rightFree = TransitionLayout.free(right?.let { clips[it].duration }, right?.let { before.leaving(it)?.timing?.leftCost } ?: 0.0)
        val fit = TransitionLayout.fit(value, if (left == null) -1 else if (right == null) 1 else 0, leftFree, rightFree) ?: return null
        val fitted = (if (place.opening) clips[place.clip].openingTransitionFit else clips[place.clip].transitionFit)?.factor
        // Found again by halving, the same fit comes out a hair different: that is no change.
        if (value == current && fitted != null && kotlin.math.abs(fit.factor - fitted) < SAME_FIT) return null
        // Each clip has one transition at its start and one at its end: the new one takes those two places, as in the
        // original (Transitions.swift:343-347). Between two clips it replaces the opening of the second (the close of the
        // first is what it is saved over); an opening replaces the transition from the clip before.
        if (between) clips = clips.toMutableList().also { it[join] = it[join].copy(openingTransition = null, openingTransitionFit = null) }
        if (place.opening && place.clip > 0 && transition(clips, place.clip - 1) != null) clips = withJoin(clips, place.clip, null, null)
        return state.copy(clips = withJoin(clips, join, value, TransitionFit(fit.factor)))
    }

    /**
     * The longest duration the Transition tool offers for join [join]: the
     * whole played length of the shorter of its two clips, or of its one clip
     * for an opening or a close, as the original's editor offers it
     * (Transitions.swift:294, read by TransitionEditor.swift:11). The footage
     * the transitions at the clips' other ends already hold is not taken off:
     * a duration that does not fit beside them is kept as entered and plays
     * shortened ([setJoin]), which the tool says. 0 when there is no such join.
     */
    fun joinMaximum(clips: List<TimelineClip>, join: Int): Double {
        val found = layout(clips).join(join) ?: return 0.0
        return minOf(found.outgoing?.let { clips[it].duration } ?: TransitionLayout.LIMIT, found.incoming?.let { clips[it].duration } ?: TransitionLayout.LIMIT)
    }

    /** Sets the join after the clip at [index] to [style] over [duration] seconds; a null [duration] removes it. */
    fun setTransition(state: EditState, index: Int, duration: Double?, style: TransitionStyle): EditState? {
        if (index !in 0 until state.clips.size - 1) return null
        if (duration != null && (!duration.isFinite() || duration <= 0)) return null
        val current = transition(state.clips, index)
        return setJoin(state, index + 1, duration?.let { (current ?: TransitionSettings()).copy(style = style, duration = it) })
    }

    /** Seconds the clip at [index] and the one after it play at once; 0 at a cut and where time is inserted. */
    fun overlap(clips: List<TimelineClip>, index: Int): Double = layout(clips).placements.getOrNull(index)?.takeIf { index < clips.lastIndex }?.outgoing ?: 0.0

    fun level(offset: Double, duration: Double, fadeIn: Double, fadeOut: Double): Double {
        var level = 1.0
        if (fadeIn > 0 && offset < fadeIn) level = offset / fadeIn
        if (fadeOut > 0 && offset > duration - fadeOut) level = minOf(level, (duration - offset) / fadeOut)
        return level.coerceIn(0.0, 1.0)
    }

    // MARK: Titles

    fun textsAt(clip: TimelineClip, sourceTime: Double): List<TextOverlay> =
        clip.texts.orEmpty().filter { sourceTime >= it.start && sourceTime < it.end }

    /**
     * Every title showing at timeline second [time], with its opacity: a
     * title shows as much as its clip's picture does. During a transition the
     * titles of both clips show, the outgoing clip's first. [frames] is the
     * movie's frame rate during a join's transition, as [TransitionScene.moment] takes it.
     */
    fun titlesAt(clips: List<TimelineClip>, time: Double, frames: (TransitionLayout.Join) -> Double = { TransitionScene.FRAMES_PER_SECOND }): List<Pair<TextOverlay, Double>> =
        TransitionScene.titles(clips, TransitionScene.moment(clips, time, frames = frames))

    /**
     * Adds the title to the clip, or replaces the one with the same id, on
     * this clip only. The pieces of a split clip may carry the same title, and
     * its id keeps its motion running across the cut, but it is not the scope
     * of an edit: a change to one piece leaves the others as they were.
     */
    fun setText(state: EditState, index: Int, text: TextOverlay): EditState? {
        val clip = state.clips.getOrNull(index) ?: return null
        if (!text.valid) return null
        val texts = clip.texts.orEmpty()
        val next = if (texts.any { it.id == text.id }) texts.map { if (it.id == text.id) text else it } else texts + text
        if (next == texts) return null
        return state.copy(clips = state.clips.toMutableList().also { it[index] = clip.copy(texts = next) })
    }

    /** Removes the title from the clip at [index] only; another piece carrying it keeps it. */
    fun removeText(state: EditState, index: Int, id: String): EditState? {
        val clip = state.clips.getOrNull(index) ?: return null
        val texts = clip.texts.orEmpty()
        if (texts.none { it.id == id }) return null
        return state.copy(clips = state.clips.toMutableList().also { it[index] = clip.copy(texts = texts.filter { it.id != id }.ifEmpty { null }) })
    }

    /** How long a new title stays, in seconds of the movie. */
    const val NEW_TITLE = 3.0

    /**
     * Where a new title goes: from the playhead at [time] for [NEW_TITLE]
     * seconds of the movie, across whatever cuts that covers, and no further
     * than the end. At the very end it still gets the last frame.
     */
    fun newTitleRange(clips: List<TimelineClip>, time: Double): ClosedFloatingPointRange<Double> {
        val duration = duration(clips)
        return minOf(time, maxOf(0.0, duration - PauseDetector.FRAME))..minOf(duration, time + NEW_TITLE)
    }

    /** The seconds of the movie in which the clip at [index] plays. */
    fun clipRange(clips: List<TimelineClip>, index: Int): ClosedFloatingPointRange<Double>? =
        layout(clips).placements.getOrNull(index)?.let { it.start..it.end }

    /**
     * The seconds of the movie in which the clip at [index] shows [title]:
     * the part of the title's footage the clip plays. Null when it shows none
     * of it, as a piece does whose title lies in footage cut away from it.
     */
    fun titleRange(clips: List<TimelineClip>, index: Int, title: TextOverlay): ClosedFloatingPointRange<Double>? {
        val clip = clips.getOrNull(index) ?: return null
        val from = maxOf(clip.start, title.start)
        val to = minOf(clip.end, title.end)
        if (to <= from) return null
        val begins = clipStart(clips, index)
        return begins + clip.offset(from)..begins + clip.offset(to)
    }

    /**
     * Writes [title] over the seconds [range] of the movie. Each clip in
     * [scope] that plays during them gets the title with the same id over the
     * footage it shows then, so a title runs across cuts and still arrives
     * once and leaves once; a copy the clip had is replaced, and one on a
     * clip the range no longer reaches is removed. [scope] is the clips an
     * edit may touch: all of them (null) for a new title, the one piece that
     * was opened for an existing one. The title's own [TextOverlay.start] and
     * [TextOverlay.end] are not read.
     *
     * [opened] is the range the title had when it was opened. While [range]
     * is still that, each clip keeps the footage seconds it has saved, which
     * may reach past the clip, and only the rest of the title changes.
     *
     * Null when nothing would change or the title cannot be saved.
     */
    fun placeTitle(
        state: EditState, title: TextOverlay, range: ClosedFloatingPointRange<Double>,
        scope: Set<String>? = null, opened: ClosedFloatingPointRange<Double>? = null,
    ): EditState? {
        if (!title.copy(start = 0.0, end = 1.0).valid || !range.start.isFinite() || !range.endInclusive.isFinite()) return null
        val placements = layout(state.clips).placements
        val clips = state.clips.mapIndexed { index, clip ->
            if (scope != null && clip.id !in scope) return@mapIndexed clip
            val texts = clip.texts.orEmpty()
            val previous = texts.firstOrNull { it.id == title.id }
            val others = texts.filter { it.id != title.id }
            val placed = if (opened != null && range == opened) {
                previous?.let { title.copy(start = it.start, end = it.end) }
            } else {
                val begins = placements[index].start
                val from = maxOf(range.start, begins)
                val to = minOf(range.endInclusive, begins + clip.duration)
                if (to > from + 0.000001) title.copy(start = maxOf(0.0, clip.sourceTime(from - begins)), end = clip.sourceTime(to - begins)) else null
            }
            if (placed == previous) return@mapIndexed clip
            // A changed title stays where it was among the clip's titles; a new one goes on top.
            val next = if (placed == null) others else if (previous == null) texts + placed else texts.map { if (it.id == title.id) placed else it }
            clip.copy(texts = next.ifEmpty { null })
        }
        return if (clips == state.clips) null else state.copy(clips = clips)
    }

    // MARK: Bookmarks

    /** Marks closer than this to the playhead count as being under it. */
    const val BOOKMARK_REACH = 0.15

    /** Timeline seconds of every bookmark that is inside the visible part of its clip, in order. */
    fun bookmarkTimes(clips: List<TimelineClip>): List<Double> = clips.flatMapIndexed { index, clip ->
        val begins = clipStart(clips, index)
        clip.bookmarks.orEmpty().filter { it.time >= clip.start && it.time < clip.end }.map { begins + clip.offset(it.time) }
    }.sorted()

    fun nextBookmark(clips: List<TimelineClip>, time: Double): Double? = bookmarkTimes(clips).firstOrNull { it > time + 0.05 }

    fun previousBookmark(clips: List<TimelineClip>, time: Double): Double? = bookmarkTimes(clips).lastOrNull { it < time - 0.05 }

    /** The bookmark of the clip at [index] within reach of [sourceTime], if any. */
    fun bookmarkAt(clips: List<TimelineClip>, index: Int, sourceTime: Double): Bookmark? =
        clips.getOrNull(index)?.bookmarks.orEmpty().filter { kotlin.math.abs(it.time - sourceTime) <= BOOKMARK_REACH }
            .minByOrNull { kotlin.math.abs(it.time - sourceTime) }

    /** Removes the bookmark under [sourceTime] on the clip at [index], or adds one there. */
    fun toggleBookmark(state: EditState, index: Int, sourceTime: Double): EditState? {
        val clip = state.clips.getOrNull(index) ?: return null
        if (!sourceTime.isFinite() || sourceTime < clip.start || sourceTime > clip.end) return null
        val existing = bookmarkAt(state.clips, index, sourceTime)
        val marks = clip.bookmarks.orEmpty()
        val next = if (existing != null) marks.filter { it.id != existing.id } else (marks + Bookmark(time = sourceTime)).sortedBy { it.time }
        return state.copy(clips = state.clips.toMutableList().also { it[index] = clip.copy(bookmarks = next.ifEmpty { null }) })
    }

    // MARK: Clip sound

    private fun ClipAudio?.normalized(): ClipAudio? = this?.takeIf { it != ClipAudio() }

    /** Sets how the clip at [index] sounds. The default settings are stored as nothing at all. */
    fun setAudio(state: EditState, index: Int, audio: ClipAudio): EditState? {
        val clip = state.clips.getOrNull(index) ?: return null
        if (!audio.valid) return null
        val next = audio.normalized()
        if (next == clip.audio) return null
        return state.copy(clips = state.clips.toMutableList().also { it[index] = clip.copy(audio = next) })
    }

    /**
     * How loud the clip at [index] is at [offset] seconds into it, as a factor
     * of the recorded sound: its level and mute, its own fades, and what the
     * transitions at its two ends leave of it ([TransitionSound]). Above 1
     * for a boosted clip. This is [SoundEnvelope.clip] without the few
     * milliseconds of fade at a cut, which the export adds sample by sample.
     */
    fun soundLevel(clips: List<TimelineClip>, index: Int, offset: Double): Double {
        val segment = SoundEnvelope.clip(clips, index, cutFades = false) ?: return 0.0
        // Asked about its very end, the clip is still the one playing.
        return segment.copy(end = Double.POSITIVE_INFINITY).gain(segment.start + offset)
    }

    // MARK: Sound on its own tracks

    private fun EditState.withAudio(items: List<AudioItem>) = copy(audio = items.sortedBy { it.start }.ifEmpty { null })

    private fun fits(items: List<AudioItem>, item: AudioItem) =
        items.none { it.id != item.id && it.track == item.track && it.start < item.end && item.start < it.end }

    /** The first track where [item] overlaps nothing, or null when every track is busy there. */
    private fun placed(items: List<AudioItem>, item: AudioItem): AudioItem? =
        (listOf(item.track) + (0 until AudioItem.TRACKS)).distinct().map { item.copy(track = it) }.firstOrNull { fits(items, it) }

    /** Puts a whole sound source on the timeline at [at]. Null when both tracks are busy there. */
    fun addAudio(state: EditState, sourceID: String, sourceDuration: Double, at: Double): EditState? {
        if (!at.isFinite() || at < 0 || !sourceDuration.isFinite() || sourceDuration < MINIMUM_CLIP) return null
        val items = state.audio.orEmpty()
        val item = placed(items, AudioItem(sourceID = sourceID, start = at, sourceStart = 0.0, sourceEnd = sourceDuration)) ?: return null
        return state.withAudio(items + item)
    }

    /** Moves an item to begin at [start], changing track if its own is busy there. */
    fun moveAudio(state: EditState, id: String, start: Double): EditState? {
        val items = state.audio.orEmpty()
        val item = items.firstOrNull { it.id == id } ?: return null
        if (!start.isFinite() || start < 0 || start == item.start) return null
        val moved = placed(items, item.copy(start = start)) ?: return null
        return state.withAudio(items.map { if (it.id == id) moved else it })
    }

    /**
     * Cuts the item so it begins or ends at timeline second [time], which
     * must fall inside it. What stays keeps its place on the timeline.
     */
    fun trimAudio(state: EditState, id: String, time: Double, leading: Boolean): EditState? {
        val items = state.audio.orEmpty()
        val item = items.firstOrNull { it.id == id } ?: return null
        if (!time.isFinite() || time - item.start < MINIMUM_CLIP || item.end - time < MINIMUM_CLIP) return null
        val cut = item.sourceStart + (time - item.start)
        // A cut inside a fade leaves what is left of it, as on a clip.
        val sound = (if (leading) item.audio?.sliced(time - item.start, item.duration, item.duration) else item.audio?.sliced(0.0, time - item.start, item.duration)).normalized()
        val trimmed = if (leading) item.copy(start = time, sourceStart = cut, audio = sound) else item.copy(sourceEnd = cut, audio = sound)
        return state.withAudio(items.map { if (it.id == id) trimmed else it })
    }

    fun setAudioItemSound(state: EditState, id: String, audio: ClipAudio): EditState? {
        val items = state.audio.orEmpty()
        val item = items.firstOrNull { it.id == id } ?: return null
        if (!audio.valid || audio.normalized() == item.audio) return null
        return state.withAudio(items.map { if (it.id == id) it.copy(audio = audio.normalized()) else it })
    }

    fun removeAudio(state: EditState, id: String): EditState? {
        val items = state.audio.orEmpty()
        if (items.none { it.id == id }) return null
        return state.withAudio(items.filter { it.id != id })
    }

    /** The item playing on [track] at timeline second [time]. */
    fun audioAt(items: List<AudioItem>, track: Int, time: Double): AudioItem? =
        items.firstOrNull { it.track == track && time >= it.start && time < it.end }

    /** How loud an item is at [offset] seconds into it: its level, mute and fades; [SoundEnvelope.item] without the fades of a cut. */
    fun soundLevel(item: AudioItem, offset: Double): Double {
        val segment = SoundEnvelope.item(listOf(item), item, cutFades = false) ?: return 0.0
        return segment.copy(end = Double.POSITIVE_INFINITY).gain(segment.start + offset)
    }

    // MARK: Color

    /** Sets the color of the clip at [index]. Settings that change nothing are stored as nothing at all. */
    fun setColor(state: EditState, index: Int, color: ColorSettings): EditState? {
        val clip = state.clips.getOrNull(index) ?: return null
        if (!color.valid) return null
        val next = color.takeIf { !it.isIdentity }
        if (next == clip.color) return null
        return state.copy(clips = state.clips.toMutableList().also { it[index] = clip.copy(color = next) })
    }

    // MARK: Crop

    /** Sets the framing of the clip at [index]; null removes it, showing the whole picture again. */
    fun setCrop(state: EditState, index: Int, crop: CropSettings?): EditState? {
        val clip = state.clips.getOrNull(index) ?: return null
        if (crop?.valid == false || crop == clip.crop) return null
        return state.copy(clips = state.clips.toMutableList().also { it[index] = clip.copy(crop = crop) })
    }

    // MARK: Speed

    /**
     * Plays the clip at [rate] times its recorded speed; 1 removes the
     * setting. The clip keeps its footage and takes more or less time, so
     * later clips move. When its transitions then cost more than it lasts,
     * they are scaled together to fit, as in the original; a clip made
     * slower does not give a shortened transition its length back.
     */
    fun setSpeed(state: EditState, index: Int, rate: Double): EditState? {
        val clip = state.clips.getOrNull(index) ?: return null
        val speed = if (rate == 1.0) null else (clip.speed ?: SpeedSettings()).copy(rate = rate)
        if (speed?.valid == false || speed == clip.speed) return null
        // A fade longer than the clip now lasts is squeezed into it, as in the original; one that fits keeps its length.
        val sound = clip.audio?.fitted(clip.duration, clip.copy(speed = speed).duration).normalized()
        val clips = fitted(state.clips).toMutableList().also { it[index] = it[index].copy(speed = speed, audio = sound) }
        return state.copy(clips = pruned(refitted(clips, index)))
    }
}
