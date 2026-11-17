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
     * Cuts the clip showing at [time] in two, and every sound item playing
     * there, as the original cuts everything under the playhead when nothing
     * is selected (YouRec/TimelineEditing.swift:45-62). Refused inside a
     * transition. No fit changes: the transition into the clip stays on the
     * first piece and the one out of it goes with the second, as they were.
     * Both pieces keep every title and every word; each keeps the level, and
     * of each fade what falls in it; a bookmark stays with the piece that
     * plays it. A sound item's pieces stay on its track and play on without
     * a break; the first keeps its id.
     *
     * The cut is the track model's ([TrackEditing.split]) of everything under
     * the playhead, where today's rules say:
     */
    fun split(state: EditState, time: Double): EditState? = split(state, time, sounds = true)

    /** [split]; with [sounds] false the sound items are left whole, as an insertion needs them (`insert-moves-sound-items`). */
    private fun split(state: EditState, time: Double, sounds: Boolean): EditState? {
        // Off a sequence: the original's cut at the playhead, on the 1/30 s grid, of everything under it ([TrackEditing.splitAtPlayhead]).
        if (!state.isSequence) return if (time.isFinite()) TrackEditState.editing(state) { TrackEditing.splitAtPlayhead(it, time) } else null
        val base = TrackWrapping.fitted(state)
        val clips = base.clips
        val (index, offset) = locate(clips, time) ?: return null
        val clip = clips[index]
        // As in the original, a piece of any length is left; the track model refuses one shorter than a tick (YouRec/TrackTimeline.swift:1011-1014).
        if (offset <= 0 || clip.duration - offset <= 0) return null
        // Not inside a transition: the picture or the sound of two clips is in play there.
        if (layout(clips).all.any { it.transition != null && time > it.range.start && time < it.range.endInclusive }) return null
        // Kept until S3b (split-on-the-grid): the cut is where the playhead is, not on the frame nearest to it.
        val cut = clip.sourceTime(offset)
        val slack = TransitionLayout.TICK * maxOf(1.0, clip.rate)
        // Since S5 (split-cuts-sound-items) the sound items under the playhead are cut too, each exactly at [time] as the clip is.
        val under = if (sounds) base.audio.orEmpty().filter { time > it.start && time < it.end } else emptyList()
        val settled = { piece: AudioItem ->
            val whole = under.firstOrNull { it.id == piece.id }
            val later = under.firstOrNull { it.id != piece.id && it.sourceID == piece.sourceID && kotlin.math.abs(it.end - piece.end) <= TransitionLayout.TICK && kotlin.math.abs(piece.start - time) <= TransitionLayout.TICK }
            when {
                whole != null -> piece.copy(sourceEnd = TrackWrapping.exactly(piece.sourceEnd, whole.sourceStart + (time - whole.start)))
                later != null -> piece.copy(
                    start = TrackWrapping.exactly(piece.start, time), sourceStart = TrackWrapping.exactly(piece.sourceStart, later.sourceStart + (time - later.start)), track = later.track,
                )
                else -> piece
            }
        }
        val result = TrackWrapping.edit(base, sound = sounds, settled = settled) { timeline ->
            val picture = timeline.video.firstOrNull { it.id == clip.id } ?: return@edit null
            timeline.split(picture.start + EditTime.of(offset), if (sounds) TrackLinking.entireSelection(timeline) else timeline.selection(clip.id)).timeline
        } ?: return null
        if (result.clips.size != clips.size + 1) return null
        return result.copy(clips = result.clips.mapIndexed { place, piece ->
            when (place) {
                index -> piece.copy(end = TrackWrapping.exactly(piece.end, cut, slack))
                index + 1 -> piece.copy(start = TrackWrapping.exactly(piece.start, cut, slack))
                else -> piece
            }
        })
    }

    /**
     * Removes the clip and closes the gap. Its transitions go with it; every
     * other keeps its fit. The deletion is the track model's, of the clip's
     * picture with what it is linked to ([TrackEditing.removing]).
     */
    fun delete(state: EditState, index: Int): EditState? = delete(state, index, layers = true)

    /** [delete]; with [layers] false the clips still follow one another afterwards, as [RangeEditing] needs of its joins. */
    internal fun delete(state: EditState, index: Int, layers: Boolean): EditState? {
        val id = state.clips.getOrNull(index)?.id ?: return null
        // Off a sequence: the picture with what it is linked to, as the track model deletes; their time closes, nothing else moves.
        if (!state.isSequence) return TrackEditState.editing(state) { it.removing(it.selection(id)).timeline }
        // Since S4 the time a transition of the clip held stays (`removed-transition-leaves-its-time`).
        return TrackWrapping.edit(TrackWrapping.fitted(state), sound = false, layers = layers) { it.removing(it.selection(id)).timeline }?.let(::settledIfSequence)
    }

    /**
     * Moves the clip. A transition whose two clips are no longer neighbors is
     * removed; every other keeps its fit. The clip is lifted out and put back
     * on the cut it goes to, as the track model's clipboard does
     * ([TrackSequenceView.moving]).
     */
    fun move(state: EditState, from: Int, to: Int): EditState? {
        // Off a sequence there is no row to put a clip in: [SequenceOnly.Reorder], use [TrackMoveSession].
        if (!state.isSequence) return null
        if (from !in state.clips.indices || to !in state.clips.indices || from == to) return null
        val id = state.clips[from].id
        val order = state.clips.map { it.id }.toMutableList().also { it.add(to, it.removeAt(from)) }.withIndex().associate { it.value to it.index }
        return TrackWrapping.edit(TrackWrapping.fitted(state), sound = false, layers = true) { timeline ->
            TrackSequenceView.moving(timeline, id, to)?.let { moved -> moved.copy(video = moved.video.sortedBy { order[it.id] ?: Int.MAX_VALUE }) }
        }?.let(::settledIfSequence)
    }

    /** Times this close to a join are on it, as in the original's paste. */
    const val JOIN_REACH = 0.0001

    /**
     * The timeline made ready for footage to go in at [point], as the
     * original's paste does: the clips, and the join the footage goes on when
     * [point] is on the join of a transition (the footage then goes between
     * its two clips, which ends it). Otherwise every transition running at
     * [point] is removed ([setJoin]), so both its clips have their whole
     * length again, and the clip then showing at that second is the one to cut.
     */
    internal fun opened(clips: List<TimelineClip>, point: Double): Pair<List<TimelineClip>, Int?> {
        var result = fitted(clips)
        (1 until result.size).firstOrNull { index -> join(result, index)?.let { kotlin.math.abs(it.time - point) < JOIN_REACH } == true }?.let { return result to it }
        // A transition gone moves the clips after it, which can bring another one over the point.
        while (true) {
            val crossed = transitions(result).filter { point > it.range.start && point < it.range.endInclusive }
            var removed = false
            for (join in crossed) setJoin(EditState(result), join.index, null, layers = false)?.let { result = it.clips; removed = true }
            if (!removed) break
        }
        return result to null
    }

    /**
     * Puts [clips] into the timeline at [time], in order; later clips ripple.
     * Inside a clip, the clip is split there and they go between the pieces.
     * Inside a transition, the transition is removed and the clip then
     * showing at that second is split, as the original's paste does and as
     * [RangeEditing.paste] does ([opened]); on the join of a transition they
     * go between its two clips. Where a split is refused (less than a
     * tick from an edge) they go to the nearer edge of the clip instead.
     * A transition on the join they land on is removed, as when a
     * clip is moved there: it belonged to those neighbors. Every other
     * transition keeps its fit.
     *
     * Time is opened for the clips as the track model opens it
     * ([TrackEditing.insertingTime]) and each goes in as a picture, a sound
     * and a link. They keep the ids they are given, where the track model's
     * own insertion makes copies with new ones (`insert-new-identity`): the
     * takes of the capture ledger are put in again every time a project is
     * read ([Project.materialized]) and have to be the same clips each time.
     * Null when there is nothing to insert or a clip is already on the timeline.
     */
    fun insert(state: EditState, clips: List<TimelineClip>, time: Double): EditState? {
        if (clips.isEmpty() || !time.isFinite()) return null
        val ids = clips.map { it.id }.toSet()
        if (ids.size != clips.size || state.clips.any { it.id in ids }) return null
        // Off a sequence: time is opened at [time] on every lane and the clips go in there, in front ([TrackEditState.insert]).
        if (!state.isSequence) return TrackEditState.insert(state, clips, time)
        // Kept until S5 (footage-put-after-the-picture): the movie ends with its picture, and so does the place footage can go.
        val at = time.coerceIn(0.0, state.duration)
        // Not adopted (insert-inside-a-transition): a transition running there is removed and the clip then showing is cut, the original's Paste rule for every insertion.
        val (ready, onJoin) = opened(state.clips, at)
        var base = if (ready === state.clips) state else state.copy(clips = ready)
        var index = onJoin ?: -1
        if (index < 0) {
            index = 0
            locate(base.clips, at)?.let { (found, offset) ->
                val cut = split(base, at, sounds = false)
                if (cut != null) { base = cut; index = found + 1 }
                // Where no cut can be made (on an edge, to the tick), the footage goes to the nearer edge.
                else index = if (offset <= base.clips[found].duration / 2) found else found + 1
            }
        }
        if (index in 1 until base.clips.size && transition(base.clips, index - 1) != null) base = setJoin(base, index, null, layers = false) ?: base
        val place = index
        // Kept until S5 (insert-moves-sound-items): the sound items stay where they are on the clock.
        return TrackWrapping.edit(base, sound = false) { timeline ->
            val point = timeline.video.getOrNull(place)?.start ?: timeline.video.lastOrNull()?.end ?: EditTime.ZERO
            // What [TrackEditing.inserting] does, without the new identities: time is opened and the clips go in as a
            // picture, a sound and a link each, with the transitions they have among themselves.
            val addition = EditState(clips).toTimeline()
            val opened = timeline.insertingTime(addition.duration, point)
            val front = (opened.video.maxOfOrNull { it.layer } ?: 0) + 1
            opened.copy(
                video = opened.video.take(place) + addition.video.map { it.copy(start = it.start + point, layer = it.layer + front) } + opened.video.drop(place),
                audio = opened.audio + addition.audio.map { it.copy(start = it.start + point) },
                links = opened.links + addition.links,
                bookmarks = opened.bookmarks + addition.bookmarks,
                transitions = opened.transitions + addition.transitions.map { it.copy(origin = it.origin + point) },
            ).pruned()
        // The clips put in are the clips given, as they were given.
        }?.let { result -> settled(result.copy(clips = result.clips.map { clip -> clips.firstOrNull { it.id == clip.id } ?: clip })) }
    }

    /**
     * A Freeze Frame at the playhead, as the original's `freezing` makes it
     * (YouRec/VideoEffects/VideoEffectTiming.swift:70-86): the clip showing
     * at [time], on the 1/30 s grid, is cut there and a held frame of the
     * second it shows goes between the pieces, three seconds long, with a
     * Freeze Frame of its own and none of the clip's other time effects,
     * speed, transitions, bookmarks, words or fades. Nothing after it is
     * removed: the footage resumes after the hold. Refused inside a
     * transition, where no single frame is showing. The edit and the id of
     * the new Freeze Frame; null when refused.
     */
    fun freezing(state: EditState, time: Double): Pair<EditState, String>? {
        if (!time.isFinite() || state.clips.isEmpty()) return null
        val frame = TrackEditing.FRAME
        val duration = state.duration
        val position = if (time >= duration - frame / 2) duration else minOf(maxOf(0.0, Math.round(time / frame) * frame), maxOf(0.0, duration - frame))
        if (state.isSequence && layout(state.clips).all.any { it.transition != null && position > it.range.start + JOIN_REACH && position < it.range.endInclusive - JOIN_REACH }) return null
        val (index, offset) = locate(state.clips, position) ?: return null
        val clip = state.clips[index]
        if (clip.frozenFrame != null) return null
        val sourceTime = VideoEffectTiming.freezeSourceTime(clip.start, clip.end, clip.sourceTime(offset), frame)
        val end = minOf(clip.end, sourceTime + frame)
        val effect = VideoEffectSettings(VideoEffectID.FreezeFrame, start = sourceTime, end = end)
        val hold = clip.copy(
            id = newID(), start = sourceTime, end = end, speed = null,
            transition = null, openingTransition = null, transitionFit = null, openingTransitionFit = null,
            bookmarks = null, subtitleWords = null,
            audio = clip.audio?.let { ClipAudio(it.volumeDB, it.muted, denoise = it.denoise) }?.takeIf { it != ClipAudio() },
            frozenFrame = VideoEffectTiming.freezeHold(sourceTime, effect),
            videoEffects = clip.videoEffects.orEmpty().filter { it.kind.descriptor.family != EffectFamily.Time } + effect,
        )
        return insert(state, listOf(hold), position)?.let { it to effect.id }
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
     * The clip's edges moved to [start] and [end] (source seconds, as
     * [trimmed] keeps them), each as the original's editor moves an edge
     * with its handle ([TrackEditing.trimming], YouRec/EditorView.swift:1241-1258):
     *
     *  - Inwards, the trimmed part of the clip and its sound is cut off and
     *    deleted, so the time closes as a deletion of linked picture and
     *    sound closes it. The transition on the trimmed side has lost the
     *    edge it was on and goes with it; the one on the other side is not
     *    touched. A fade follows its footage: cut where the edge went in.
     *  - Outwards, footage is revealed and nothing else moves: the clip grows
     *    over its neighbour and comes in front of it, which makes the edit
     *    one of pictures over one another ([EditState.layered]). A fade at
     *    that edge gets back what the revealed time gives it, and a
     *    transition beside it may find a larger fit.
     *
     * The start is moved first, then the end. Null when nothing changes.
     */
    fun trim(state: EditState, index: Int, start: Double, end: Double, sourceDuration: Double): EditState? {
        val clip = state.clips.getOrNull(index) ?: return null
        val next = trimmed(clip, start, end, sourceDuration)
        if (next == clip) return null
        val sources = listOf(SourceMedia(clip.sourceID, filename = "", duration = maxOf(sourceDuration, clip.end, next.end)))
        val operation = { timeline: TrackTimeline ->
            var result = timeline
            if (next.start != clip.start) {
                result = TrackEditing.trimming(result, clip.id, true, (next.start - clip.start) / clip.rate, sources)?.timeline ?: result
                // A head cut away leaves the later piece, which the cut gave an id of its own (YouRec/TrackTimeline.swift:1015-1016).
                // Not taken up (`later-piece-new-identity`, Invisible): it keeps the clip's id, which the editor, the selection
                // and the capture ledger know it by; the piece that had it is gone.
                if (result.video.none { it.id == clip.id }) {
                    val occurrence = timeline.video.firstOrNull { it.id == clip.id }?.occurrenceID
                    val before = timeline.allIDs
                    result.video.firstOrNull { it.occurrenceID == occurrence && it.id !in before }?.let { result = renamed(result, it.id, clip.id) }
                }
            }
            if (next.end != clip.end) result = TrackEditing.trimming(result, clip.id, false, (next.end - clip.end) / clip.rate, sources)?.timeline ?: result
            result.takeIf { it != timeline }
        }
        if (!state.isSequence) return TrackEditState.editing(state, operation)
        return TrackWrapping.edit(TrackWrapping.fitted(state), layers = true, operation = operation)
    }

    /** [timeline] with the picture [from] called [to], wherever it is named, and the sound that follows it named after it ([TrackTimeline.counterpart]). */
    private fun renamed(timeline: TrackTimeline, from: String, to: String): TrackTimeline {
        val sound = timeline.audio.firstOrNull { it.followsVideo == from }?.id
        val heard = TrackTimeline.counterpart(to)
        fun id(value: String?) = when (value) { from -> to; null -> null; sound -> heard; else -> value }
        return timeline.copy(
            video = timeline.video.map { if (it.id == from) it.copy(content = it.content.copy(id = to)) else it },
            audio = timeline.audio.map { if (it.followsVideo == from) it.copy(id = heard, followsVideo = to) else it },
            links = timeline.links.map { link -> if (from in link.members || sound in link.members) link.copy(members = link.members.mapTo(LinkedHashSet()) { id(it)!! }) else link },
            transitions = timeline.transitions.map { relation ->
                if (relation.outgoing != from && relation.incoming != from && relation.settings.nextClipID != from) relation
                else relation.copy(outgoing = id(relation.outgoing), incoming = id(relation.incoming), settings = relation.settings.copy(nextClipID = id(relation.settings.nextClipID)))
            },
            placements = timeline.placements.map { region ->
                if (from !in region.participants) region
                else region.copy(participants = region.participants.mapTo(LinkedHashSet()) { id(it)!! }, videos = region.videos.mapKeys { id(it.key)!! })
            },
        )
    }

    /**
     * Removes stretches of source time from the clip at [index], leaving the
     * pieces in between as separate clips with the gaps closed: what the
     * Pauses tool of earlier versions did. The tool now keeps the pauses as a
     * setting of the clip ([setPauses]), as the original does; this stays for
     * the parity suite and the golden projects, which hold edits made so. Pieces too short to stand alone are dropped with the
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
        // Off a sequence: each stretch cut out of the picture and deleted with what it is linked to ([TrackEditing.removingSource]).
        if (!state.isSequence) {
            val id = state.clips.getOrNull(index)?.id ?: return null
            return TrackEditState.editing(state) { TrackEditing.removingSource(it, id, cuts) }
        }
        val clip = fitted(state.clips).getOrNull(index) ?: return null
        // Not adopted here (`split-refused-near-an-edge`): the original keeps one clip with its pauses as a setting and makes
        // no pieces (N3), so a sliver between two pauses that cannot stand alone goes with them, as before.
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

    /**
     * [state] after clips were removed, moved or put in, with the settings of
     * every join that is broken taken off its clip ([withoutBrokenJoins]).
     * The timeline has no such transition, and the conversion reads none;
     * this is what keeps the saved clips saying the same.
     */
    private fun settledIfSequence(state: EditState): EditState = if (state.isSequence) settled(state) else state

    private fun settled(state: EditState): EditState = withoutBrokenJoins(state.clips).let { if (it == state.clips) state else state.copy(clips = it) }

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
    fun setJoin(state: EditState, join: Int, settings: TransitionSettings?): EditState? = setJoin(state, join, settings, layers = true)

    /**
     * [setJoin]; with [layers] false the clips still follow one another
     * afterwards whatever the transition held, as an edit that goes on to
     * work on the clips needs ([opened], [insert]).
     */
    private fun setJoin(state: EditState, join: Int, settings: TransitionSettings?, layers: Boolean): EditState? {
        // Off a sequence a join has no number: [SequenceOnly.Transition].
        if (!state.isSequence) return null
        val base = TrackWrapping.fitted(state)
        val clips = base.clips
        val place = saved(join, clips.size) ?: return null
        val (outgoing, incoming) = TrackSequenceView.joinClips(clips, join) ?: return null
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
        // Not adopted (the fit's last digits, docs/TRACKS-PARITY-DECISIONS.md "At S4"): the fit that is saved is the one
        // [TransitionLayout.fit] finds, to the last digit. The track model fits by halving with the original's slack and
        // comes out a hundred-millionth longer, which a timeline's ticks absorb and a sequence's seconds do not.
        var factor: Double? = null
        if (value != null) {
            val before = layout(clips)
            val leftFree = TransitionLayout.free(left?.let { clips[it].duration }, left?.let { before.entering(it)?.timing?.rightCost } ?: 0.0)
            val rightFree = TransitionLayout.free(right?.let { clips[it].duration }, right?.let { before.leaving(it)?.timing?.leftCost } ?: 0.0)
            // What fits is shorter than one frame: that is no transition, and nothing changes.
            factor = (TransitionLayout.fit(value, if (left == null) -1 else if (right == null) 1 else 0, leftFree, rightFree) ?: return null).factor
            val fitted = (if (place.opening) clips[place.clip].openingTransitionFit else clips[place.clip].transitionFit)?.factor
            // Found again by halving, the same fit comes out a hair different: that is no change.
            if (value == current && fitted != null && kotlin.math.abs(factor - fitted) < SAME_FIT) return null
        }
        // The join is found by its two clips among the joins of the timeline, and the transition set or removed there
        // ([TrackTransitions.setting], [TrackTransitions.restoringStraightCut]): each clip has one transition at its start
        // and one at its end, and the new one takes those two places.
        // Since S4 a transition removed leaves the time it held, and a close that holds pushes nothing: the pictures
        // stay where the track model puts them (`removed-transition-leaves-its-time`, `close-that-holds-pushes-nothing`).
        val result = TrackWrapping.edit(base, sound = false, layers = layers) { timeline ->
            val found = TrackLayout.of(timeline).joins.firstOrNull { it.outgoingID == outgoing && it.incomingID == incoming } ?: return@edit null
            if (settings == null) TrackTransitions.restoringStraightCut(timeline, found.index) else TrackTransitions.setting(timeline, settings, found.index)
        } ?: return null
        // An edit of pictures over one another or apart keeps the track model's fit, which its ticks absorb.
        if (!result.isSequence) return result
        val made = (if (place.opening) result.clips[place.clip].openingTransitionFit else result.clips[place.clip].transitionFit)?.factor
        if (factor == null || made == null || kotlin.math.abs(made - factor) > FIT_ROUNDING) return result
        return result.copy(clips = withJoin(result.clips, join, joinSettings(result.clips, join), TransitionFit(factor)))
    }

    /**
     * A fit of the track model and the fit today's rule finds, closer than
     * this, are the same fit: the track model measures a clip in ticks, and
     * half a tick of footage is this much of a short transition.
     */
    private const val FIT_ROUNDING = 0.0001

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
        // Off a sequence: [SequenceOnly.Transition].
        if (!state.isSequence) return null
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

    // MARK: Recipes

    /**
     * [state] with [clips], the same clips with the recipe of some changed
     * (titles, color, framing): the tools that edit a clip's recipe read the
     * clips and answer clips, and what they changed is merged into the
     * timeline ([TrackSequenceView.merging]), as the original's tools do
     * through `replaceVideoContents`. Null when the merge changes nothing.
     */
    private fun recipes(state: EditState, clips: List<TimelineClip>): EditState? =
        TrackWrapping.edit(state, sound = false) { TrackSequenceView.merging(it, clips) }

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
        return recipes(state, state.clips.toMutableList().also { it[index] = clip.copy(texts = next) })
    }

    /** Removes the title from the clip at [index] only; another piece carrying it keeps it. */
    fun removeText(state: EditState, index: Int, id: String): EditState? {
        val clip = state.clips.getOrNull(index) ?: return null
        val texts = clip.texts.orEmpty()
        if (texts.none { it.id == id }) return null
        return recipes(state, state.clips.toMutableList().also { it[index] = clip.copy(texts = texts.filter { it.id != id }.ifEmpty { null }) })
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
        // Where each clip begins: in the layout of a sequence, or where its picture is on a timeline.
        val starts = if (state.isSequence) layout(state.clips).placements.map { it.start } else TrackEditState.starts(state)
        val clips = state.clips.mapIndexed { index, clip ->
            if (scope != null && clip.id !in scope) return@mapIndexed clip
            val texts = clip.texts.orEmpty()
            val previous = texts.firstOrNull { it.id == title.id }
            val others = texts.filter { it.id != title.id }
            val placed = if (opened != null && range == opened) {
                previous?.let { title.copy(start = it.start, end = it.end) }
            } else {
                val begins = starts[index]
                val from = maxOf(range.start, begins)
                val to = minOf(range.endInclusive, begins + clip.duration)
                if (to > from + 0.000001) title.copy(start = maxOf(0.0, clip.sourceTime(from - begins)), end = clip.sourceTime(to - begins)) else null
            }
            if (placed == previous) return@mapIndexed clip
            // A changed title stays where it was among the clip's titles; a new one goes on top.
            val next = if (placed == null) others else if (previous == null) texts + placed else texts.map { if (it.id == title.id) placed else it }
            clip.copy(texts = next.ifEmpty { null })
        }
        return if (clips == state.clips) null else recipes(state, clips)
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
        // The mark is one of the timeline's, on the footage of the clip's occurrence at the second given
        // ([BookmarkAnchor.Source]). The track model's own [TrackBookmarks.adding] takes a moment of the timeline and marks
        // what plays then, which at a clip's very last second is the clip after it, or the timeline itself where the movie
        // ends (`bookmark-at-the-last-second`): a mark the sequence form has no place for. So the mark is put on the
        // footage it was made on, as today, until bookmarks are the timeline's own (S12).
        return TrackWrapping.edit(state, sound = false) { timeline ->
            if (existing != null) return@edit TrackBookmarks.removing(timeline, existing.id)
            val picture = timeline.video.firstOrNull { it.id == clip.id } ?: return@edit null
            timeline.copy(bookmarks = timeline.bookmarks + TimelineBookmark(anchor = BookmarkAnchor.Source(picture.occurrenceID, clip.sourceID, sourceTime)))
        }
    }

    // MARK: Clip sound

    private fun ClipAudio?.normalized(): ClipAudio? = this?.takeIf { it != ClipAudio() }

    /** Sets how the clip at [index] sounds. The default settings are stored as nothing at all. */
    /**
     * [audio] with each fade that changes from [was] set as the original sets
     * one (YouRec/TrackTimeline.swift:191-195): no longer than the item,
     * [length] seconds, and from silence (`fade-no-longer-than-its-item`, since S4).
     */
    internal fun capped(audio: ClipAudio, was: ClipAudio, length: Double): ClipAudio {
        var result = audio
        if (audio.fadeIn != was.fadeIn) result = result.copy(fadeIn = audio.fadeIn.coerceIn(0.0, maxOf(0.0, length)), fadeInElapsed = null)
        if (audio.fadeOut != was.fadeOut) result = result.copy(fadeOut = audio.fadeOut.coerceIn(0.0, maxOf(0.0, length)), fadeOutElapsed = null)
        return result
    }

    fun setAudio(state: EditState, index: Int, audio: ClipAudio): EditState? {
        val clip = state.clips.getOrNull(index) ?: return null
        if (!audio.valid) return null
        val next = capped(audio, clip.audio ?: ClipAudio(), clip.duration).normalized()
        if (next == clip.audio) return null
        val clips = state.clips.toMutableList().also { it[index] = clip.copy(audio = next) }
        // Kept until S6 (sound-settings-of-an-unlinked-clip): a clip whose sound was unlinked has no sound on the timeline
        // to set; what it is given is what it remembers for when the sound is linked again ([AudioUnlinking]).
        if (AudioUnlinking.isDetached(state, index) || AudioUnlinking.isDetached(state.copy(clips = clips), index)) return state.copy(clips = clips)
        // The level, the mute, the fades and the noise reduction are written to the sound that follows the clip's picture.
        return recipes(state, clips)
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

    /** The sound of the timeline that the sound item [id] of [state] is; null when there is no such item. */
    private fun sound(state: EditState, timeline: TrackTimeline, id: String): AudioTimelineItem? {
        if (state.audio.orEmpty().none { it.id == id }) return null
        val name = TrackWrapping.soundID(state, id)
        return timeline.audio.firstOrNull { it.id == name }
    }

    /** Puts a whole sound source on the timeline at [at], laid over what is there ([TrackAudio.inserting]). Null when both tracks are busy there. */
    fun addAudio(state: EditState, sourceID: String, sourceDuration: Double, at: Double): EditState? {
        if (!at.isFinite() || at < 0 || !sourceDuration.isFinite() || sourceDuration < MINIMUM_CLIP) return null
        return TrackWrapping.edit(state, settled = { it.copy(start = TrackWrapping.exactly(it.start, at)) }) {
            TrackAudio.inserting(it, SourceMedia(sourceID, "", sourceDuration, 0L, audioOnly = true), EditTime.of(at)).timeline
        }
    }

    /** Moves an item to begin at [start] ([TrackMove.moved]), changing track if its own is busy there. */
    fun moveAudio(state: EditState, id: String, start: Double): EditState? {
        val item = state.audio.orEmpty().firstOrNull { it.id == id } ?: return null
        if (!start.isFinite() || start < 0 || start == item.start) return null
        return TrackWrapping.edit(state, settled = { it.copy(start = TrackWrapping.exactly(it.start, start)) }) { timeline ->
            val sound = sound(state, timeline, id) ?: return@edit null
            timeline.moved(TimelineSelection(audio = setOf(sound.id)), EditTime.of(start) - sound.start)
        }
    }

    /**
     * Cuts the item so it begins or ends at timeline second [time], which
     * must fall inside it. What stays keeps its place on the timeline. The
     * part before or after [time] is cut off and deleted as the track model
     * deletes ([TrackEditing.scoped], [TrackEditing.removing]). What is left
     * after the beginning is cut away is a new item to the track model
     * (`later-piece-new-identity`); here it keeps the id the item has, so a
     * saved project and a selection name it as before.
     */
    fun trimAudio(state: EditState, id: String, time: Double, leading: Boolean): EditState? {
        val item = state.audio.orEmpty().firstOrNull { it.id == id } ?: return null
        // As in the original, a piece of any length is left; the track model refuses one shorter than a tick.
        if (!time.isFinite() || time <= item.start || time >= item.end) return null
        val cut = item.sourceStart + (time - item.start)
        // Kept until S3b (split-on-the-grid): the cut is at the second given. What is left stays the item, on the item's track.
        val settled = { piece: AudioItem ->
            if (leading) piece.copy(id = id, start = TrackWrapping.exactly(piece.start, time), sourceStart = TrackWrapping.exactly(piece.sourceStart, cut), track = item.track)
            else piece.copy(sourceEnd = TrackWrapping.exactly(piece.sourceEnd, cut), track = item.track)
        }
        return TrackWrapping.edit(state, settled = settled) { timeline ->
            val sound = sound(state, timeline, id) ?: return@edit null
            val at = EditTime.of(time)
            if (at <= sound.start || at >= sound.end) return@edit null
            val scope = timeline.scoped(TimelineSelection(audio = setOf(sound.id), range = if (leading) EditInterval(sound.start, at) else EditInterval(at, sound.end)))
            scope.timeline.removing(scope.selection).timeline
        }
    }

    /**
     * A trim of the sound item [id] by one of its handles, as the original's
     * editor makes it ([TrackEditing.trimming], YouRec/EditorView.swift:1241-1258):
     * [delta] timeline seconds at its [leading] or trailing edge. Inwards the
     * part trimmed off is deleted, leaving at least a frame; outwards the sound
     * is revealed, as far as its source of [sourceDuration] seconds goes and,
     * at the start, no earlier than the timeline's. Nothing else moves. What is
     * left keeps the item's id and its track where that is free, and each edge
     * the second it is given (until `split-on-the-grid`). Null when nothing
     * changes.
     */
    fun trimAudioEdge(state: EditState, id: String, leading: Boolean, delta: Double, sourceDuration: Double): EditState? {
        val item = state.audio.orEmpty().firstOrNull { it.id == id } ?: return null
        if (!delta.isFinite() || delta == 0.0) return null
        val outwards = if (leading) delta < 0 else delta > 0
        val frame = TrackEditing.FRAME
        // Where the edge goes, in the item's own seconds, as the track model will put it to within a tick.
        val (start, sourceStart, sourceEnd) = when {
            leading && outwards -> minOf(-delta, item.start, item.sourceStart).let { Triple(item.start - it, item.sourceStart - it, item.sourceEnd) }
            outwards -> Triple(item.start, item.sourceStart, item.sourceEnd + minOf(delta, maxOf(0.0, sourceDuration - item.sourceEnd)))
            leading -> minOf(item.end - frame, maxOf(item.start, item.start + delta)).let { Triple(it, item.sourceStart + (it - item.start), item.sourceEnd) }
            else -> maxOf(item.start + frame, minOf(item.end, item.end + delta)).let { Triple(item.start, item.sourceStart, item.sourceStart + (it - item.start)) }
        }
        val sources = listOf(SourceMedia(item.sourceID, filename = "", duration = maxOf(sourceDuration, item.sourceEnd), audioOnly = true))
        val settled = { piece: AudioItem ->
            if (piece.sourceID != item.sourceID) piece
            else piece.copy(
                id = id, start = TrackWrapping.exactly(piece.start, start), sourceStart = TrackWrapping.exactly(piece.sourceStart, sourceStart),
                sourceEnd = TrackWrapping.exactly(piece.sourceEnd, sourceEnd), track = item.track,
            )
        }
        return TrackWrapping.edit(state, settled = settled) { timeline ->
            val sound = sound(state, timeline, id) ?: return@edit null
            TrackEditing.trimming(timeline, sound.id, leading, delta, sources)?.timeline?.let { trimmed ->
                // What a reveal adds is a run of its own, at the same pace from where the sound began or ended: one run with it, as a sound item is.
                trimmed.copy(audio = trimmed.audio.map { if (it.sourceID == item.sourceID && it.mapping.runs.size > 1) it.copy(mapping = joined(it.mapping)) else it })
            }
        }
    }

    /** [map] with the runs that carry one another on, at one pace through the source, made one. */
    private fun joined(map: SourcePlaybackMap): SourcePlaybackMap {
        val runs = ArrayList<PlaybackRun>()
        for (run in map.runs) {
            val last = runs.lastOrNull()
            val pace = { it: PlaybackRun -> (it.sourceEnd - it.sourceStart) / it.duration.seconds }
            if (last != null && !last.isHold && !run.isHold && last.silent == run.silent && kotlin.math.abs(last.sourceEnd - run.sourceStart) < 1e-9 &&
                kotlin.math.abs(pace(last) - pace(run)) < 1e-6) runs[runs.lastIndex] = PlaybackRun(last.duration + run.duration, last.sourceStart, run.sourceEnd, run.silent)
            else runs += run
        }
        return SourcePlaybackMap(runs)
    }

    /** Sets the level, mute and fades of a sound item ([TrackAudio.settingVolume], [TrackAudio.settingFade]); a fade is no longer than the item. */
    fun setAudioItemSound(state: EditState, id: String, audio: ClipAudio): EditState? {
        val item = state.audio.orEmpty().firstOrNull { it.id == id } ?: return null
        if (!audio.valid) return null
        val next = capped(audio, item.audio ?: ClipAudio(), item.duration).normalized()
        if (next == item.audio) return null
        val result = TrackWrapping.edit(state) { timeline -> sound(state, timeline, id)?.let { TrackWrapping.sounding(timeline, it.id, audio, item.audio ?: ClipAudio()) } } ?: state
        // Off a sequence the sound items are a view of the timeline's sounds, which have no amount of noise reduction.
        if (!result.isSequence) return result.takeIf { it != state }
        // A sound of the timeline has no amount of noise reduction for all of it; a sound item keeps the one it is given, which does nothing until items are cleaned (S7).
        return result.copy(audio = result.audio?.map { if (it.id == id && it.audio != next) it.copy(audio = next) else it })
    }

    /** Removes a sound item by itself, which moves nothing ([TrackEditing.removing]). */
    fun removeAudio(state: EditState, id: String): EditState? =
        TrackWrapping.edit(state) { timeline -> sound(state, timeline, id)?.let { timeline.removing(TimelineSelection(audio = setOf(it.id))).timeline } }

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
        return recipes(state, state.clips.toMutableList().also { it[index] = clip.copy(color = next) })
    }

    /** Gives the clip [effects] in place of its video effects; null or empty takes them all away. */
    fun setVideoEffects(state: EditState, index: Int, effects: List<VideoEffectSettings>?): EditState? {
        val clip = state.clips.getOrNull(index) ?: return null
        val next = effects?.takeIf { it.isNotEmpty() }
        if (next == clip.videoEffects) return null
        return recipes(state, state.clips.toMutableList().also { it[index] = clip.copy(videoEffects = next) })
    }

    // MARK: Crop

    /** Sets the framing of the clip at [index]; null removes it, showing the whole picture again. */
    fun setCrop(state: EditState, index: Int, crop: CropSettings?): EditState? {
        val clip = state.clips.getOrNull(index) ?: return null
        if (crop?.valid == false || crop == clip.crop) return null
        return recipes(state, state.clips.toMutableList().also { it[index] = clip.copy(crop = crop) })
    }

    /**
     * Flips the clip at [index] in the movie's frame. One that flips neither
     * way is no mirror and is stored as none, as in the original
     * (YouRec/EditorTools.swift:713).
     */
    fun setMirror(state: EditState, index: Int, mirror: MirrorSettings?): EditState? {
        val clip = state.clips.getOrNull(index) ?: return null
        val next = mirror?.takeIf { it.horizontal || it.vertical }
        if (next == clip.mirror) return null
        return recipes(state, state.clips.toMutableList().also { it[index] = clip.copy(mirror = next) })
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
        // The speed is merged as an edited recipe, with its ripple ([TrackEditing.settingSpeed]); the clips keep their order.
        return retimed(state, index, { it.copy(speed = speed) }) { timeline -> TrackEditing.settingSpeed(timeline, clip.id, speed) }
    }

    // MARK: Pauses

    /**
     * Takes [pauses] out of the clip at [index], or puts back every pause it
     * had taken out when null, as the original's Pauses tool writes them on
     * the clip (YouRec/EditorTools.swift:618-640): the clip stays one clip
     * and plays its footage without [PauseSettings.cuts], so later clips
     * move up. Its transitions stay as they are unless together they cost
     * more than the clip now lasts; then they are scaled together to fit, as
     * for a change of speed (YouRec/TrackTimeline.swift:1376-1385). Null
     * when nothing changes.
     */
    fun setPauses(state: EditState, index: Int, pauses: PauseSettings?): EditState? {
        val clip = state.clips.getOrNull(index) ?: return null
        if (pauses == clip.pauses) return null
        return retimed(state, index, { it.copy(pauses = pauses) }) { timeline -> TrackEditing.settingPauses(timeline, clip.id, pauses) }
    }

    /**
     * The clip at [index] [change]d by [operation] on the timeline: an edit
     * that keeps its footage and changes how long it plays. On a sequence
     * the fits saved where its transitions are scaled are the ones
     * [refitted] finds.
     */
    private fun retimed(state: EditState, index: Int, change: (TimelineClip) -> TimelineClip, operation: (TrackTimeline) -> TrackTimeline?): EditState? {
        val base = TrackWrapping.fitted(state)
        val result = TrackWrapping.edit(base, sound = false, operation = operation) ?: return null
        // Off a sequence the fits are the track model's.
        if (!state.isSequence) return result
        // Not adopted (the fit's last digits): where the clip's transitions are scaled to fit it, the fits saved are the ones today's rule finds
        // ([refitted]), to the last digit; the track model measures the clip in ticks and comes out a millionth apart.
        val wanted = refitted(base.clips.toMutableList().also { it[index] = change(it[index]) }, index).associateBy { it.id }
        return result.copy(clips = result.clips.map { made ->
            val fits = wanted[made.id] ?: return@map made
            fun same(a: TransitionFit?, b: TransitionFit?) = a != null && b != null && a != b && kotlin.math.abs(a.factor - b.factor) <= FIT_ROUNDING
            made.copy(
                transitionFit = if (same(made.transitionFit, fits.transitionFit)) fits.transitionFit else made.transitionFit,
                openingTransitionFit = if (same(made.openingTransitionFit, fits.openingTransitionFit)) fits.openingTransitionFit else made.openingTransitionFit,
            )
        })
    }
}
