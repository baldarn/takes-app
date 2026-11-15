package app.takes.core

import kotlin.math.abs
import kotlin.math.floor

/** A stretch of the timeline, in seconds. */
data class TimeRange(val start: Double, val end: Double) {
    val duration: Double get() = end - start

    /** At least one frame long, as the original app requires of a range before it edits anything. */
    val valid: Boolean get() = start.isFinite() && end.isFinite() && end - start >= RangeEditing.FRAME - RangeEditing.EPSILON
}

/** The clips and the items on the sound tracks that an edit is aimed at, by id. */
data class EditTargets(val clips: Set<String> = emptySet(), val audio: Set<String> = emptySet()) {
    val ids: Set<String> get() = clips + audio
    val isEmpty: Boolean get() = clips.isEmpty() && audio.isEmpty()
}

/** What a tool, Delete, Copy or Cut acts on. */
sealed interface EditScope {
    /**
     * A marked stretch of time. [targets] are the items that were selected
     * when the start was marked: only their parts inside the range count.
     * Null means everything.
     */
    data class Range(val range: TimeRange, val targets: EditTargets? = null) : EditScope

    /** Whole items, picked one by one. */
    data class Selected(val targets: EditTargets) : EditScope

    object Whole : EditScope
}

/**
 * What the editor remembers between taps: a marked start waiting for its end,
 * a marked range, or the items picked one by one. Runtime state, not saved
 * with the project. The rules are those of the original app's editor.
 */
data class RangeMarks(
    /** The marked start while the end has not been chosen yet. */
    val start: Double? = null,
    val range: TimeRange? = null,
    val selected: EditTargets = EditTargets(),
    /** The items the range is limited to: what was selected when the start was marked. Null means everything. */
    val rangeTargets: EditTargets? = null,
) {
    /** What the Mark button does next. */
    enum class Step { MarkStart, MarkEnd, MoveEnd }

    enum class Refusal {
        /** The end of an existing range must stay at least one frame after its start. */
        EndBeforeStart,
        /** The end must be at least one frame away from the start. */
        TooShort,
    }

    val step: Step get() = if (range != null) Step.MoveEnd else if (start == null) Step.MarkStart else Step.MarkEnd

    /** Why marking at [time] is refused, or null. Pass a time from [RangeEditing.markTime]. */
    fun refusal(time: Double): Refusal? = when {
        range != null -> if (time - range.start >= RangeEditing.FRAME - RangeEditing.EPSILON) null else Refusal.EndBeforeStart
        start != null -> if (abs(start - time) >= RangeEditing.FRAME - RangeEditing.EPSILON) null else Refusal.TooShort
        else -> null
    }

    /**
     * One press of Mark at [time]: the first sets the start, the second closes
     * the range in whichever direction, later ones move its end. Unchanged
     * when [refusal] gives a reason or [time] is not a number.
     */
    fun marked(time: Double): RangeMarks {
        if (!time.isFinite() || refusal(time) != null) return this
        return when {
            range != null -> copy(range = TimeRange(range.start, time))
            start != null -> copy(start = null, range = TimeRange(minOf(start, time), maxOf(start, time)))
            // The range belongs to what was selected when its start was marked.
            else -> copy(start = time, range = null, rangeTargets = selected.takeIf { !it.isEmpty })
        }
    }

    /** A tap on an item: it becomes the whole selection and any range is dropped. Null selects nothing. */
    fun selecting(clip: String? = null, audio: String? = null): RangeMarks =
        RangeMarks(selected = EditTargets(setOfNotNull(clip), setOfNotNull(audio)))

    /** A long press on a clip: adds it to the selection or takes it out. A range, even a pending one, is dropped and the selection starts over. */
    fun togglingClip(id: String): RangeMarks {
        val base = if (range == null && start == null) selected else EditTargets()
        return RangeMarks(selected = base.copy(clips = if (id in base.clips) base.clips - id else base.clips + id))
    }

    /** As [togglingClip], for an item on the sound tracks. */
    fun togglingAudio(id: String): RangeMarks {
        val base = if (range == null && start == null) selected else EditTargets()
        return RangeMarks(selected = base.copy(audio = if (id in base.audio) base.audio - id else base.audio + id))
    }

    fun cleared(): RangeMarks = RangeMarks()

    /** Copy and Cut wait for a pending range to be closed, and need a range or a selection. */
    val canCopy: Boolean get() = start == null && canDelete

    val canDelete: Boolean get() = !selected.isEmpty || range != null

    /** What a tool applies to: the marked range, else the selected items, else the whole project. */
    val scope: EditScope
        get() = when {
            range != null -> EditScope.Range(range, rangeTargets)
            !selected.isEmpty -> EditScope.Selected(selected)
            else -> EditScope.Whole
        }

    /**
     * The marks after the edit changed: ids that no longer exist leave the
     * selection, and a range reaching past the end of the movie is shortened
     * or, when less than a frame of it is left, dropped.
     */
    fun retained(state: EditState): RangeMarks {
        val clips = state.clips.mapTo(HashSet()) { it.id }
        val audio = state.audio.orEmpty().mapTo(HashSet()) { it.id }
        fun kept(targets: EditTargets) = EditTargets(targets.clips intersect clips, targets.audio intersect audio)
        val duration = state.duration
        val clipped = range?.let { TimeRange(it.start, minOf(it.end, duration)) }?.takeIf { it.valid }
        return copy(
            start = start?.takeIf { it <= duration },
            range = clipped,
            selected = kept(selected),
            rangeTargets = rangeTargets?.takeIf { clipped != null || start != null }?.let(::kept),
        )
    }
}

/**
 * A copied piece of the edit, as the clipboard holds it. [clips] play one
 * after another from second 0 of the fragment; each [audio] item's start is
 * relative to the fragment's start. The ids are those of the timeline the
 * fragment was copied from: [RangeEditing.paste] gives every paste new ones,
 * so never put these clips on a timeline directly.
 */
data class ClipFragment(
    val clips: List<TimelineClip> = emptyList(),
    val audio: List<AudioItem> = emptyList(),
    /** How the captions looked where the fragment came from; only when one of its clips has words. */
    val subtitles: SubtitleSettings? = null,
) {
    val isEmpty: Boolean get() = clips.isEmpty() && audio.isEmpty()

    /** Pasting picture opens room for it and moves the later clips; sound alone is laid over what is there. */
    val insertsTime: Boolean get() = clips.isNotEmpty()

    val duration: Double get() = maxOf(TimelineEditing.duration(clips), audio.maxOfOrNull { it.end } ?: 0.0)
}

/**
 * Editing a marked range, and the clipboard: delete, copy, cut and paste.
 * Every function returns a new state, or null when it does not apply, so the
 * caller commits the result with [Project.commit] as one Undo step.
 *
 * How the original app's rules land on this model:
 *
 *  - A clip is the original's linked picture and sound. Removing clips always
 *    closes the gap, since the picture here has no gaps.
 *  - An [AudioItem] is the original's independent sound. A range cuts it
 *    where it crosses the range, and what is left of it never moves; pasting
 *    picture moves the later clips and leaves every item where it is.
 *  - Titles, bookmarks and caption words are kept in source seconds on their
 *    clip, so they travel with each piece of it and need no shifting.
 *  - A transition belongs to its two clips. It survives while both stay
 *    neighbors, travels in a fragment that holds both, and is removed, giving
 *    both clips their full length back, when a cut or a paste separates them.
 *    Where it sits is read from [TimelineEditing.layout], as it was
 *    fitted: nothing here measures a transition by its style, and a
 *    transition that stays or travels keeps its fit.
 *  - The original keeps a clip where it is when its transition goes, so a
 *    freeze or a hold on a color can leave empty picture behind. Here the
 *    clips always follow one another, and that time closes.
 *  - The opening belongs to the first clip and the close to the last, as
 *    [TimelineEditing] keeps them: one that an edit moves away from its end of
 *    the timeline is removed, where the original lets it play on in the middle.
 */
object RangeEditing {
    /** The original's editing frame: marks and ranges are placed on a 30 per second grid whatever the footage is. */
    const val FRAME = 1.0 / 30

    const val EPSILON = 0.00001

    /** Times this close to a join are on it. */
    private const val JOIN_REACH = TimelineEditing.JOIN_REACH

    /** [time] on the nearest frame; a tie goes away from zero. */
    fun quantize(time: Double): Double {
        val frames = time * 30
        return (if (frames < 0) -floor(-frames + 0.5) else floor(frames + 0.5)) / 30
    }

    // MARK: Layout

    /** Timeline second where each clip begins, then the duration. */
    private fun starts(clips: List<TimelineClip>): DoubleArray = DoubleArray(clips.size + 1) { TimelineEditing.clipStart(clips, it) }

    /**
     * Where the timeline can be marked on a boundary: its start, every join,
     * and its end. The join of a transition is the moment its layout names:
     * the middle of the picture effect, or the end of a freeze. Empty for an
     * empty timeline.
     */
    fun joinTimes(clips: List<TimelineClip>): List<Double> = TimelineEditing.layout(clips).joins.map { it.time }

    /** Every join that has a transition, the opening and the close of any clip among them, as [TimelineEditing.join] counts them. */
    private fun transitions(clips: List<TimelineClip>): List<TransitionLayout.Join> = TimelineEditing.transitions(clips)

    /**
     * The transition running at [time], if any; its
     * [index][TransitionLayout.Join.index] is 0 for the opening of the first
     * clip, the number of clips for the close of the last, and between them
     * that of the clip it leads into.
     *
     * It runs for the whole of its [range][TransitionLayout.Join.range],
     * picture and sound, as fitted to the footage. Sound that leads or trails
     * the picture leaves the picture effect as long as it was and makes this
     * stretch longer: the two clips play together for all of it.
     */
    fun transitionAt(clips: List<TimelineClip>, time: Double): TransitionLayout.Join? =
        transitions(clips).firstOrNull { time >= it.range.start && time < it.range.endInclusive }

    /** The nearest join when it is within 8 points of [time] at this zoom, else [time]. */
    fun snap(time: Double, clips: List<TimelineClip>, pointsPerSecond: Double): Double {
        val nearest = joinTimes(clips).minByOrNull { abs(it - time) } ?: return time
        return if (abs(nearest - time) * pointsPerSecond <= 8) nearest else time
    }

    /**
     * Where a mark made with the playhead at [time] goes: on the frame grid,
     * or exactly on a join when snapping to cuts ([pointsPerSecond] given)
     * finds one near; and on the join of a transition when it would fall
     * inside one, since a range cannot begin or end in the middle of it.
     */
    fun markTime(state: EditState, time: Double, pointsPerSecond: Double? = null): Double {
        val clips = state.clips
        val boundary = if (pointsPerSecond != null) snap(time, clips, pointsPerSecond) else time
        val joins = joinTimes(clips)
        val onBoundary = pointsPerSecond != null && joins.any { abs(it - boundary) < JOIN_REACH }
        var mark = if (onBoundary) boundary else quantize(time)
        transitionAt(clips, mark)?.let { mark = it.time }
        return mark.coerceIn(0.0, state.duration)
    }

    // MARK: Cutting clips

    /** A clip or part of one, with the clip of the edit it comes from and the timeline second where it began there. */
    private data class Piece(val clip: TimelineClip, val origin: Int, val start: Double)

    /** A place between frames: [offset] seconds into the clip at [index], or with a null [offset] on the join before it. */
    private data class Cut(val index: Int, val offset: Double?) : Comparable<Cut> {
        override fun compareTo(other: Cut): Int =
            compareValuesBy(this, other, { it.index }, { it.offset ?: Double.NEGATIVE_INFINITY })
    }

    /** Anything shorter is not worth a piece of its own. */
    private const val SLIVER = FRAME - EPSILON

    /**
     * Where [time] cuts the timeline.
     *
     * Inside a transition two clips are in play, or none, and neither can be
     * cut: the cut is the join itself, between the two clips, before its clip
     * for an opening and after its clip for a close.
     *
     * Elsewhere one clip plays alone, from the end of the transition into it
     * to the start of the transition out of it, and is cut where [time] falls.
     * Within a frame of either end of that stretch the cut is the end itself,
     * so no sliver is left or copied; there the clip is still cut when a
     * transition holds the footage beyond, and the piece under the transition
     * stays whole with it.
     */
    private fun cut(clips: List<TimelineClip>, time: Double): Cut {
        val layout = TimelineEditing.layout(clips)
        if (clips.isEmpty() || time < SLIVER) return Cut(0, null)
        if (time > layout.duration - SLIVER) return Cut(clips.size, null)
        transitions(clips).firstOrNull { time > it.range.start + EPSILON && time < it.range.endInclusive - EPSILON }?.let { return Cut(it.boundary, null) }
        val index = TimelineEditing.locate(clips, time)?.index ?: return Cut(clips.size, null)
        val placement = layout.placements[index]
        val from = TimelineEditing.entering(clips, index)?.range?.endInclusive?.coerceIn(placement.start, placement.end) ?: placement.start
        val until = TimelineEditing.leaving(clips, index)?.range?.start?.coerceIn(from, placement.end) ?: placement.end
        val edge = if (time - from <= until - time) from else until
        val offset = (if (time - from < SLIVER || until - time < SLIVER) edge else time) - placement.start
        return when {
            offset < SLIVER -> Cut(index, null)
            placement.duration - offset < SLIVER -> Cut(index + 1, null)
            else -> Cut(index, offset)
        }
    }

    /**
     * The two pieces of a clip cut [offset] seconds into it, made by
     * [TimelineEditing.split] itself, so a range leaves on each piece what an
     * ordinary split leaves there: its titles, its marks, its fades, and the
     * join at its own end of the clip.
     *
     * The clip is handed over between two empty clips, away from its
     * neighbors, so that no transition is in play around it. A split refuses
     * to leave less than [TimelineEditing.MINIMUM_CLIP], and a range may leave
     * one frame: such a piece is lent footage for the split and gives it back.
     */
    private fun divided(piece: Piece, offset: Double): List<Piece> {
        val clip = piece.clip
        val room = 2 * TimelineEditing.MINIMUM_CLIP
        val lead = if (offset < room) room else 0.0
        val tail = if (clip.duration - offset < room) room else 0.0
        // Without its opening and its close, which would be in play here and are given back to the piece a split leaves each on.
        val lent = clip.copy(
            start = clip.start - lead * clip.rate, end = clip.end + tail * clip.rate,
            openingTransition = null, openingTransitionFit = null, transition = null, transitionFit = null,
        )
        val empty = TimelineClip(sourceID = clip.sourceID, start = 0.0, end = 0.0)
        val cut = checkNotNull(TimelineEditing.split(EditState(listOf(empty, lent, empty)), lead + offset)) { "A clip could not be cut $offset s into ${clip.duration} s" }
        val left = cut.clips[1].copy(start = clip.start, openingTransition = clip.openingTransition, openingTransitionFit = clip.openingTransitionFit)
        val right = cut.clips[2].copy(end = clip.end, transition = clip.transition, transitionFit = clip.transitionFit)
        return listOf(Piece(left, piece.origin, piece.start), Piece(right, piece.origin, piece.start + offset))
    }

    private fun pieces(clips: List<TimelineClip>): List<Piece> {
        val starts = starts(clips)
        return clips.mapIndexed { index, clip -> Piece(clip, index, starts[index]) }
    }

    private fun List<Piece>.dividedAt(index: Int, offset: Double): List<Piece> = take(index) + divided(this[index], offset) + drop(index + 1)

    /**
     * The clips after some were removed or added between them: a join whose
     * next clip is another one is a plain cut again, while an opening and a
     * close stay with their clip. The rule is
     * [TimelineEditing]'s own, reached through its delete: an empty clip is
     * added at the end and removed again.
     */
    private fun joined(clips: List<TimelineClip>): List<TimelineClip> {
        val last = clips.lastOrNull() ?: return clips
        val empty = TimelineClip(sourceID = last.sourceID, start = 0.0, end = 0.0)
        return TimelineEditing.delete(EditState(clips + empty), clips.size)?.clips ?: clips
    }

    /** The clips without the opening and the close, which shape the ends of a timeline and not the clips' places among themselves. */
    private fun inner(clips: List<TimelineClip>): List<TimelineClip> =
        clips.map { clip ->
            val leaving = clip.transition?.takeIf { it.nextClipID != null }
            clip.copy(openingTransition = null, openingTransitionFit = null, transition = leaving, transitionFit = clip.transitionFit.takeIf { leaving != null })
        }

    /** The timeline cut at both ends of a range: [pieces] `from` until `until` lie inside it. */
    private class Resolved(val pieces: List<Piece>, val from: Int, val until: Int)

    /** Cuts the clips among [targets] (null: all) at the two ends of the range. Null when nothing lies between them. */
    private fun resolve(clips: List<TimelineClip>, start: Double, end: Double, targets: Set<String>?): Resolved? {
        val lower = cut(clips, start)
        val upper = cut(clips, end)
        if (lower >= upper) return null
        fun targeted(index: Int) = targets == null || clips[index].id in targets
        var pieces = pieces(clips)
        // The upper end first, so the lower one still finds its clip at its index.
        var until = upper.index
        if (upper.offset != null && targeted(upper.index)) { pieces = pieces.dividedAt(upper.index, upper.offset); until += 1 }
        var from = lower.index
        if (lower.offset != null) {
            from += 1
            if (targeted(lower.index)) { pieces = pieces.dividedAt(lower.index, lower.offset); until += 1 }
        }
        return Resolved(pieces, from, until)
    }

    // MARK: Cutting sound

    private fun ClipAudio?.normalized(): ClipAudio? = this?.takeIf { it != ClipAudio() }

    /** An item cut by a range: what stays before it, what lies inside it, what stays after it. */
    private class Carved(val before: AudioItem?, val inside: AudioItem?, val after: AudioItem?)

    /**
     * Cuts an item at the ends of a range. As with clips, an end within a
     * frame of the item's own edge is that edge. Each side of a cut loses the
     * fade that was on the other side.
     */
    private fun carve(item: AudioItem, start: Double, end: Double): Carved {
        val from = if (start - item.start < FRAME - EPSILON) item.start else start
        val to = if (item.end - end < FRAME - EPSILON) item.end else end
        if (from == item.start && to == item.end) return Carved(null, item, null)
        if (to - from < FRAME - EPSILON) return Carved(item, null, null)
        fun part(a: Double, b: Double, id: String): AudioItem {
            val sound = item.audio?.copy(
                fadeIn = if (a > item.start) 0.0 else item.audio.fadeIn,
                fadeOut = if (b < item.end) 0.0 else item.audio.fadeOut,
            ).normalized()
            return item.copy(id = id, start = a, sourceStart = item.sourceStart + (a - item.start), sourceEnd = item.sourceStart + (b - item.start), audio = sound)
        }
        val before = if (from > item.start) part(item.start, from, item.id) else null
        val after = if (to < item.end) part(to, item.end, if (before == null) item.id else newID()) else null
        return Carved(before, part(from, to, item.id), after)
    }

    private fun overlaps(item: AudioItem, start: Double, end: Double) = item.start < end && start < item.end

    // MARK: Delete

    /** The edit after [delete], and where the playhead goes when one was given. */
    data class Removed(val state: EditState, val position: Double?)

    /**
     * Removes what [scope] covers and closes the picture.
     *
     * A range cuts the clips it crosses and removes everything between its
     * ends; later clips move earlier. Sound items lose the part inside the
     * range and are otherwise left exactly where they are: the sound tracks do
     * not ripple. A selection removes its clips and items whole; the whole
     * project scope removes everything.
     *
     * A transition stays while its two clips, or the pieces of them that
     * carried it, are still neighbors: a range that ends where a transition
     * begins leaves it whole. It is removed with either of them, and two
     * clips that become neighbors meet at a plain cut. An opening goes with
     * the start of its clip and a close with the end of its clip.
     *
     * [playhead] is carried to the same frame of the same footage, or to the
     * cut that replaced it when it was inside what went away.
     *
     * Null when the range is shorter than a frame or inverted, or when the
     * scope covers nothing.
     */
    fun delete(given: EditState, scope: EditScope, playhead: Double? = null): Removed? {
        // From here on the transitions that stay keep the fit they have.
        val state = given.copy(clips = TimelineEditing.fitted(given.clips))
        val all = pieces(state.clips)
        val items = state.audio.orEmpty()
        val kept: List<Piece>
        val sound: List<AudioItem>
        when (scope) {
            is EditScope.Range -> {
                val range = bounded(scope.range) ?: return null
                val resolved = resolve(state.clips, range.start, range.end, scope.targets?.clips)
                kept = resolved?.pieces?.filterIndexed { index, piece ->
                    !(index in resolved.from until resolved.until && scope.targets.aims(state.clips[piece.origin].id))
                } ?: all
                sound = items.flatMap { item ->
                    if (scope.targets?.audio?.contains(item.id) == false || !overlaps(item, range.start, range.end)) return@flatMap listOf(item)
                    val carved = carve(item, range.start, range.end)
                    if (carved.inside == null) listOf(item) else listOfNotNull(carved.before, carved.after)
                }
            }
            is EditScope.Selected -> {
                kept = all.filter { it.clip.id !in scope.targets.clips }
                sound = items.filter { it.id !in scope.targets.audio }
            }
            EditScope.Whole -> { kept = emptyList(); sound = emptyList() }
        }
        val clips = joined(kept.map { it.clip })
        if (kept.map { it.clip } == state.clips && sound == items) return null
        val next = state.copy(clips = clips, audio = sound.sortedBy { it.start }.ifEmpty { null })
        return Removed(next, playhead?.takeIf { it.isFinite() }?.let { carried(state.clips, kept, clips, it) })
    }

    private fun EditTargets?.aims(clip: String) = this == null || clip in clips

    /** The range with its start inside the timeline, or null when it is not a range the original would edit. */
    private fun bounded(range: TimeRange): TimeRange? = TimeRange(maxOf(0.0, range.start), range.end).takeIf { range.valid && it.valid }

    /** Where the frame under [playhead] is after an edit that kept [kept] as [clips]. */
    private fun carried(before: List<TimelineClip>, kept: List<Piece>, clips: List<TimelineClip>, playhead: Double): Double {
        val duration = TimelineEditing.duration(clips)
        val (index, offset) = TimelineEditing.locate(before, playhead.coerceIn(0.0, TimelineEditing.duration(before))) ?: return 0.0
        val source = before[index].sourceTime(offset)
        val starts = starts(clips)
        val same = kept.indices.filter { kept[it].origin == index && source >= kept[it].clip.start && source <= kept[it].clip.end }
        // A cut belongs to the piece that starts there.
        val found = same.firstOrNull { source < kept[it].clip.end } ?: same.lastOrNull()
        if (found != null) return (starts[found] + kept[found].clip.offset(source)).coerceIn(0.0, duration)
        // The frame was removed: land on the cut that replaced it.
        val following = kept.indices.firstOrNull { kept[it].origin > index || (kept[it].origin == index && kept[it].clip.start >= source) }
        return if (following != null) starts[following] else duration
    }

    // MARK: Copy

    /**
     * What [scope] covers, as a fragment for the clipboard; the edit is not
     * changed.
     *
     * A range gives the pieces of the clips inside it, cut where [delete]
     * would cut them, each with its trim, speed, crop, color, sound settings,
     * the titles it shows, the bookmarks on it and its caption words; and the
     * part of every sound item inside the range, at its distance from the
     * picture it lay under. A selection gives its clips whole, packed one
     * after another in playback order.
     *
     * A transition comes along only when both its clips do, whole at that
     * join. One at an edge of the fragment is left behind: the clipboard holds
     * clips, not the identity of the neighboring join. An opening comes with
     * the start of its clip and a close with the end of its clip, as the
     * original's clipboard keeps them (TimelinePaste.swift:70-75).
     *
     * Null when the range is shorter than a frame or inverted, or nothing is covered.
     */
    fun copy(given: EditState, scope: EditScope): ClipFragment? {
        // A transition travels with the fit it has.
        val state = given.copy(clips = TimelineEditing.fitted(given.clips))
        val items = state.audio.orEmpty()
        val chosen: List<Piece>
        val sound: List<AudioItem>
        when (scope) {
            is EditScope.Range -> {
                val range = bounded(scope.range) ?: return null
                val resolved = resolve(state.clips, range.start, range.end, scope.targets?.clips)
                chosen = resolved?.pieces?.subList(resolved.from, resolved.until)?.filter { scope.targets.aims(state.clips[it.origin].id) }.orEmpty()
                sound = items.filter { scope.targets?.audio?.contains(it.id) != false && overlaps(it, range.start, range.end) }
                    .mapNotNull { carve(it, range.start, range.end).inside }
            }
            is EditScope.Selected -> {
                chosen = pieces(state.clips).filter { it.clip.id in scope.targets.clips }
                sound = items.filter { it.id in scope.targets.audio }
            }
            EditScope.Whole -> { chosen = pieces(state.clips); sound = items }
        }
        if (chosen.isEmpty() && sound.isEmpty()) return null
        val clips = chosen.mapIndexed { position, piece ->
            val whole = state.clips[piece.origin]
            val next = chosen.getOrNull(position + 1)
            val join = piece.clip.transition
            val travels = when {
                join == null -> false
                // The close of its clip: a cut leaves it on the piece that ends the clip.
                join.nextClipID == null -> true
                // Both partners, still neighbors, and neither cut on the side of the join.
                else -> TimelineEditing.transition(state.clips, piece.origin) != null &&
                    next != null && next.origin == piece.origin + 1 && next.clip.start == state.clips[next.origin].start
            }
            piece.clip.copy(
                // A piece is known by the clip it was cut from, which keeps a detached sound item paired with its picture.
                id = whole.id,
                transition = join.takeIf { travels },
                transitionFit = piece.clip.transitionFit.takeIf { travels },
                // A cut leaves the opening on the piece that begins its clip.
                openingTransition = piece.clip.openingTransition,
                openingTransitionFit = piece.clip.openingTransitionFit,
                // Only the marks that show: a mark on trimmed-away footage stays behind.
                bookmarks = piece.clip.bookmarks?.filter { it.time >= piece.clip.start && it.time < piece.clip.end }?.ifEmpty { null },
            )
        }
        val placed = placed(chosen, clips, sound, keepGaps = scope is EditScope.Range)
        val spoken = clips.any { clip -> clip.subtitleWords.orEmpty().any { it.end > clip.start && it.start < clip.end } }
        return ClipFragment(clips, placed, state.subtitles.takeIf { spoken })
    }

    /**
     * The sound items of a fragment, moved from timeline seconds to seconds
     * from the fragment's start. An item that began under a copied clip keeps
     * its distance from that clip. Otherwise empty time before it is dropped;
     * a range keeps the silence between its items, a selection closes it.
     */
    private fun placed(chosen: List<Piece>, clips: List<TimelineClip>, sound: List<AudioItem>, keepGaps: Boolean): List<AudioItem> {
        if (sound.isEmpty()) return emptyList()
        val sorted = sound.sortedBy { it.start }
        if (chosen.isEmpty()) {
            if (keepGaps) return sorted.map { it.copy(start = it.start - sorted.first().start) }
            // Islands of sound, packed: the original's rule for unrelated selections.
            var islandEnd = sorted.first().end
            var removed = sorted.first().start
            return sorted.map { item ->
                if (item.start > islandEnd) removed += item.start - islandEnd
                islandEnd = maxOf(islandEnd, item.end)
                item.copy(start = item.start - removed)
            }
        }
        // From where the first clip begins, which is where a paste puts it.
        val begins = starts(inner(clips))
        fun end(index: Int) = chosen[index].start + chosen[index].clip.duration
        return sorted.map { item ->
            val under = chosen.indices.lastOrNull { item.start >= chosen[it].start && item.start < end(it) }
            val after = chosen.indices.lastOrNull { end(it) <= item.start }
            val start = when {
                under != null -> begins[under] + (item.start - chosen[under].start)
                after == null -> 0.0
                keepGaps -> begins[after] + clips[after].duration + (item.start - end(after))
                else -> begins[after] + clips[after].duration
            }
            item.copy(start = maxOf(0.0, start))
        }
    }

    // MARK: Cut

    /** The edit after [cut], what went to the clipboard, and where the playhead goes when one was given. */
    data class CutOut(val state: EditState, val fragment: ClipFragment, val position: Double?)

    /**
     * [copy] and [delete] as one edit: the fragment holds exactly what was
     * lifted. Publish the fragment to the clipboard only once the edit has
     * been committed and saved. Null when either does not apply.
     */
    fun cut(state: EditState, scope: EditScope, playhead: Double? = null): CutOut? {
        val fragment = copy(state, scope) ?: return null
        val removed = delete(state, scope, playhead) ?: return null
        return CutOut(removed.state, fragment, removed.position)
    }

    // MARK: Paste

    /**
     * The edit after [paste]; the ids of what was added, to select it; the
     * timeline second where it begins, to move the playhead there; and how
     * many sound items of the fragment found no free track and were left out.
     */
    data class Pasted(val state: EditState, val clips: List<String>, val audio: List<String>, val start: Double, val skippedAudio: Int)

    /**
     * Puts a copy of [fragment] at timeline second [at], clamped to the movie.
     *
     * Picture opens room for itself: the clip showing there is cut and the
     * fragment goes between the pieces, later clips move, and the sound items
     * already on the tracks stay where they are, uncut. Within a frame of a
     * clip's edge it goes between the clips. A transition running at [at] is
     * removed first and both its clips get their full length back, as in the
     * original, whatever its style, the opening and the close too; then the
     * clip showing there is cut. On the join of a transition the fragment
     * goes between its two clips, which ends it. A fragment placed before the
     * first clip leaves that clip its opening, and one placed after the last
     * leaves it its close: they play on with their clip, as in the original.
     * So do the openings and the closes the fragment's own clips bring.
     *
     * The fragment's sound items are laid at their distance from its start,
     * each on its own track or the other one when that is busy; one that fits
     * on neither is left out and counted. A fragment of sound alone moves
     * nothing.
     *
     * Every paste has its own identities: clips, titles, bookmarks, caption
     * words and phrases, speed, crop and color settings, transitions and sound
     * items all get new ids, so editing a copy never edits the original or
     * another paste. A project without captions takes the fragment's caption
     * look when the fragment brings words.
     *
     * Null when the fragment is empty, [at] is not a number, or nothing could
     * be placed.
     */
    fun paste(state: EditState, fragment: ClipFragment, at: Double): Pasted? {
        if (fragment.isEmpty || !at.isFinite()) return null
        val copy = fresh(fragment)
        var clips = state.clips
        val base: Double
        if (copy.clips.isEmpty()) {
            base = at.coerceIn(0.0, maxOf(state.duration, state.audio.orEmpty().maxOfOrNull { it.end } ?: 0.0))
        } else {
            val point = at.coerceIn(0.0, state.duration)
            // The rule of TimelineEditing.insert: on the join of a transition between its two clips;
            // otherwise what is in play at the point is separated before the picture is opened.
            val (ready, onJoin) = TimelineEditing.opened(clips, point)
            clips = ready
            val index: Int
            if (onJoin != null) {
                index = onJoin
            } else {
                val cut = cut(clips, point)
                if (cut.offset != null) clips = pieces(clips).dividedAt(cut.index, cut.offset).map { it.clip }
                index = if (cut.offset != null) cut.index + 1 else cut.index
            }
            clips = joined(clips.take(index) + copy.clips + clips.drop(index))
            base = TimelineEditing.clipStart(clips, index)
        }
        val items = state.audio.orEmpty().toMutableList()
        val added = mutableListOf<AudioItem>()
        for (item in copy.audio) {
            val moved = item.copy(start = base + item.start)
            val free = (listOf(moved.track) + (0 until AudioItem.TRACKS)).distinct().filter { it in 0 until AudioItem.TRACKS }
                .map { moved.copy(track = it) }.firstOrNull { candidate -> items.none { it.track == candidate.track && overlaps(it, candidate.start, candidate.end) } }
            if (free != null) { items += free; added += free }
        }
        if (copy.clips.isEmpty() && added.isEmpty()) return null
        val next = state.copy(
            clips = clips,
            audio = items.sortedBy { it.start }.ifEmpty { null },
            // The words came from another structure: regrouping stays off until they are transcribed here.
            subtitles = state.subtitles ?: copy.subtitles?.copy(timingFingerprint = ""),
        )
        val start = if (copy.clips.isEmpty()) added.minOf { it.start } else base
        return Pasted(next, copy.clips.map { it.id }, added.map { it.id }, start, copy.audio.size - added.size)
    }

    /** The fragment with new identities throughout. One old id always becomes the same new one, so pairs stay pairs. */
    private fun fresh(fragment: ClipFragment): ClipFragment {
        val ids = HashMap<String, String>()
        fun fresh(old: String) = ids.getOrPut(old) { newID() }
        val clips = fragment.clips.mapIndexed { index, clip ->
            clip.copy(
                id = fresh(clip.id),
                // A close names no clip; a transition names the clip it leads into, which must be the next one here.
                transition = clip.transition?.takeIf { it.nextClipID == null || it.nextClipID == fragment.clips.getOrNull(index + 1)?.id }
                    ?.let { it.copy(id = fresh(it.id), nextClipID = it.nextClipID?.let(::fresh)) },
                transitionFit = clip.transitionFit.takeIf { clip.transition?.nextClipID.let { next -> next == null || next == fragment.clips.getOrNull(index + 1)?.id } },
                openingTransition = clip.openingTransition?.let { it.copy(id = fresh(it.id), nextClipID = it.nextClipID?.let(::fresh)) },
                texts = clip.texts?.map { it.copy(id = fresh(it.id)) },
                bookmarks = clip.bookmarks?.map { it.copy(id = newID()) },
                color = clip.color?.let { it.copy(id = fresh(it.id)) },
                crop = clip.crop?.let { it.copy(id = fresh(it.id)) },
                speed = clip.speed?.let { it.copy(id = fresh(it.id)) },
                subtitleWords = clip.subtitleWords?.map { it.copy(id = fresh(it.id), phraseID = fresh(it.phraseID)) },
            )
        }
        return ClipFragment(clips, fragment.audio.map { it.copy(id = fresh(it.id)) }, fragment.subtitles)
    }

    // MARK: Tool scope

    /** The edit with its clips cut at the ends of a range, and the clips a tool applies to, in playback order. */
    data class Scoped(val state: EditState, val clips: List<String>)

    /**
     * Which clips a tool applies to. The whole project: all of them. A
     * selection: the selected ones. A range: the clips are cut at its two
     * ends, keeping their effects and identities, and the tool applies to the
     * pieces inside, so it changes exactly the marked footage; [Scoped.state]
     * is the edit with those cuts and nothing else changed.
     *
     * A range that begins or ends inside a transition ([transitionAt]), other
     * than on its join, applies to nothing and cuts nothing: the tool would
     * silently change the footage hidden under it. So does a range shorter
     * than a frame.
     */
    fun scoped(state: EditState, scope: EditScope): Scoped = when (scope) {
        EditScope.Whole -> Scoped(state, state.clips.map { it.id })
        is EditScope.Selected -> Scoped(state, state.clips.map { it.id }.filter { it in scope.targets.clips })
        is EditScope.Range -> {
            // The cuts are made on the fitted clips, so no transition is fitted again around the pieces.
            val clips = TimelineEditing.fitted(state.clips)
            val joins = joinTimes(clips)
            fun inside(time: Double) = joins.none { abs(it - time) < JOIN_REACH } && transitionAt(clips, time) != null
            val resolved = bounded(scope.range)?.takeUnless { inside(it.start) || inside(it.end) }
                ?.let { resolve(clips, it.start, it.end, scope.targets?.clips) }
            if (resolved == null) Scoped(state, emptyList()) else {
                val chosen = resolved.pieces.subList(resolved.from, resolved.until).filter { scope.targets.aims(clips[it.origin].id) }
                // An edit that cuts nothing is the edit it was given.
                val pieces = resolved.pieces.map { it.clip }
                Scoped(if (pieces == clips) state else state.copy(clips = pieces), chosen.map { it.clip.id })
            }
        }
    }

    /**
     * The stretch of time a tool opened without a scope of its own works on:
     * the marked range inside the movie, else the clip [clipID] from its join
     * to the next, else the whole movie.
     */
    fun interval(state: EditState, range: TimeRange?, clipID: String?): TimeRange {
        if (range != null) return TimeRange(maxOf(0.0, range.start), minOf(state.duration, range.end))
        val index = state.clips.indexOfFirst { it.id == clipID }
        if (index < 0) return TimeRange(0.0, state.duration)
        val joins = joinTimes(state.clips)
        return TimeRange(joins[index], joins[index + 1])
    }
}
