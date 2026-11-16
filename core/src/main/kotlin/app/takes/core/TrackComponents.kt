package app.takes.core

import kotlin.math.abs

/**
 * What a selection holds, as the original names it: picture and sound
 * together, picture only, sound only (YouRec/TrackTimeline.swift:236;
 * docs/EDITOR-TRACKS-DESIGN.md, "The UI must say which material an
 * operation affects").
 */
enum class SelectionMaterial { VideoAndAudio, Video, Audio }

/**
 * Picture and sound as two components of a clip, in the editor's terms
 * (docs/TRACKS-PLAN.md, S6): what a selection of the editor holds on the
 * timeline, Unlink and Link, Delete that closes the time of a linked pair and
 * leaves a gap where a component goes alone, the sync offset of a sound and
 * Restore sync, and the rows the sounds of their own are drawn on.
 *
 * The editor names what it selects by the ids of its view ([EditTargets]): a
 * clip by its id, which selects the picture with what it is linked to, and a
 * sound of its own by the id of its sound item. Everything here reads the
 * timeline of the edit and answers in those ids again.
 *
 * YouRec/EditorView.swift:1125-1161 (`removeSelection`, `toggleLink`,
 * `applyLink`), :649-655 (Restore audio sync), YouRec/TrackTimeline.swift:575-639.
 */
object TrackComponents {
    /** Sync offsets this close to zero are in sync: the ticks a converted second may be off by ([TrackSequenceView.TICKS]). */
    private const val IN_SYNC = TrackSequenceView.TICKS

    /**
     * True when a picture of [timeline] has its own sound beside it without
     * that sound following it: the pair was unlinked. A row of clips holds
     * such a pair (a muted clip and a sound item, `TrackConversion` rule 6),
     * and stays one until an edit leaves what no row can hold: Delete of a
     * component alone leaves a gap, and then the edit is layered.
     */
    fun detached(timeline: TrackTimeline): Boolean = TrackSequenceView.sounds(timeline).values.any { it.followsVideo == null }

    /** The pictures of [timeline] whose own sound was unlinked from them and is drawn on a row of its own. */
    fun soundless(timeline: TrackTimeline): Set<String> =
        TrackSequenceView.sounds(timeline).filterValues { it.followsVideo == null }.keys

    // MARK: Selection

    /** What [targets] select on the timeline of [state], with everything linked to them. */
    fun selection(state: EditState, targets: EditTargets): TimelineSelection =
        TrackSequenceView.selection(state, state.toTimeline(), EditScope.Selected(targets)) ?: TimelineSelection()

    /** What [selection] holds; null when it holds nothing. */
    fun material(selection: TimelineSelection): SelectionMaterial? = when {
        selection.isEmpty -> null
        selection.video.isEmpty() -> SelectionMaterial.Audio
        selection.audio.isEmpty() -> SelectionMaterial.Video
        else -> SelectionMaterial.VideoAndAudio
    }

    /** What [targets] hold on [state]. */
    fun material(state: EditState, targets: EditTargets): SelectionMaterial? = material(selection(state, targets))

    /**
     * The editor's names for [selection] on [state]: each picture by its
     * clip, each sound that follows no picture by the id the editor draws it
     * with. A sound that follows a picture is selected with that picture.
     */
    fun targets(state: EditState, selection: TimelineSelection): EditTargets {
        val clips = state.clips.mapNotNullTo(LinkedHashSet()) { clip -> clip.id.takeIf { it in selection.video } }
        val audio = LinkedHashSet<String>()
        for (item in state.audio.orEmpty()) if (TrackSequenceView.soundID(state, item.id) in selection.audio) audio += item.id
        state.layered?.let { timeline -> for (sound in timeline.audio) if (sound.followsVideo == null && sound.id in selection.audio) audio += sound.id }
        return EditTargets(clips, audio)
    }

    /**
     * True when Delete of [targets] leaves the time it frees empty: a sound
     * by itself, or a picture without its sound where Delete runs on the
     * track model ([delete]). A linked pair closes its time, and so does a
     * clip of a row of clips that holds no unlinked pair.
     */
    fun leavesGap(state: EditState, targets: EditTargets): Boolean = when (material(state, targets)) {
        SelectionMaterial.Audio -> true
        SelectionMaterial.Video -> !state.isSequence || detached(state.toTimeline())
        else -> false
    }

    // MARK: Unlink and Link

    /** An edit after Unlink or Link, and what is selected afterwards. */
    data class Linked(val state: EditState, val targets: EditTargets)

    /** True when [targets] are exactly the members of one link: the button says Unlink; otherwise Link. */
    fun isLinked(state: EditState, targets: EditTargets): Boolean {
        val timeline = state.toTimeline()
        return TrackLinking.isSingleLink(timeline, selection(state, targets))
    }

    /**
     * Unlink: the link of [targets], when they are exactly one link, is
     * removed ([TrackLinking.unlinked]); nothing moves and no sample changes.
     * Both components stay selected, each now on its own. Null when [targets]
     * are not one link.
     */
    fun unlinking(state: EditState, targets: EditTargets): Linked? {
        val timeline = state.toTimeline()
        val chosen = selection(state, targets)
        if (!TrackLinking.isSingleLink(timeline, chosen)) return null
        val next = timeline.unlinked(chosen)
        if (next == timeline) return null
        val edit = next.toEdit(state.captureBase)
        return Linked(edit, targets(edit, chosen))
    }

    /** What Link of [targets] would join, or why it cannot ([TrackLinking.linkPlan]). */
    fun linkPlan(state: EditState, targets: EditTargets): TimelineLinkOutcome<TimelineLinkPlan> =
        state.toTimeline().linkPlan(selection(state, targets))

    /**
     * Link: [targets] become one link ([TrackLinking.joining]), and what was
     * linked is selected. Where picture and sound cover different times the
     * answer is [TimelineLinkError.ConfirmationRequired] until
     * [intersectionOnly]: then they are cut at the ends of the time they
     * share and only that is linked. Nothing moves.
     */
    fun linking(state: EditState, targets: EditTargets, intersectionOnly: Boolean = false): TimelineLinkOutcome<Linked> =
        when (val joined = state.toTimeline().joining(selection(state, targets), intersectionOnly)) {
            is TimelineLinkOutcome.Refused -> joined
            is TimelineLinkOutcome.Done -> {
                val edit = named(joined.value.timeline).toEdit(state.captureBase)
                TimelineLinkOutcome.Done(Linked(edit, targets(edit, joined.value.selection)))
            }
        }

    /**
     * [timeline] with a link of a picture and its own sound under the
     * picture's id, as the conversion names it (`TrackConversion`): a link's
     * id says nothing, and so a clip linked again to its sound is a row of
     * clips again, as it was before Unlink.
     */
    private fun named(timeline: TrackTimeline): TrackTimeline {
        val taken = timeline.links.mapTo(HashSet()) { it.id }
        val pictures = timeline.video.mapTo(HashSet()) { it.id }
        var changed = false
        val links = timeline.links.map { link ->
            val picture = link.members.singleOrNull { it in pictures }
            if (picture == null || link.id == picture || picture in taken || link.members != setOf(picture, TrackTimeline.counterpart(picture))) return@map link
            changed = true
            taken += picture
            link.copy(id = picture)
        }
        return if (changed) timeline.copy(links = links) else timeline
    }

    // MARK: Delete

    /**
     * Delete of [scope], as the original's editor deletes: a linked pair
     * closes the time it leaves, a picture or a sound by itself leaves a gap
     * and moves nothing ([TrackSequenceView.delete]). A row of clips without
     * an unlinked pair deletes as it always has ([RangeEditing.delete]).
     */
    fun delete(state: EditState, scope: EditScope, playhead: Double? = null): RangeEditing.Removed? {
        if (state.isSequence && !detached(state.toTimeline())) return RangeEditing.delete(state, scope, playhead)
        val removed = TrackSequenceView.delete(state, scope, playhead) ?: return null
        return RangeEditing.Removed(removed.result.timeline.toEdit(state.captureBase), removed.position)
    }

    // MARK: Restore sync

    /** A sound out of sync with its picture: its id on the timeline and the seconds it plays late (early when negative). */
    data class Offset(val audio: String, val seconds: Double)

    /** The offset of the sound with timeline id [audio] ([TrackLinking.syncOffset]); null when in sync or when no picture shows its footage. */
    fun offset(timeline: TrackTimeline, audio: String): Offset? {
        val offset = timeline.syncOffset(audio) ?: return null
        return if (abs(offset.ticks) <= IN_SYNC) null else Offset(audio, offset.seconds)
    }

    /**
     * The one sound [targets] hold when it is out of sync with its picture,
     * for Restore sync, as the original offers it (EditorView.swift:650-655).
     */
    fun offset(state: EditState, targets: EditTargets): Offset? {
        val audio = selection(state, targets).audio.singleOrNull() ?: return null
        return offset(state.toTimeline(), audio)
    }

    /** Restore sync: the sound [audio] (a timeline id) back under its picture ([TrackLinking.restoringSync]). It is not linked again. */
    fun restoringSync(state: EditState, audio: String): EditState? {
        val timeline = state.toTimeline()
        val next = timeline.restoringSync(audio)
        return if (next == timeline) null else next.toEdit(state.captureBase)
    }

    // MARK: Sound rows

    /**
     * The sounds the timeline draws on rows of their own: those that follow
     * no picture, by the editor's ids. A row of clips has its sound items on
     * their tracks. Off a row of clips every such sound of the timeline is
     * drawn, on the first row where it overlaps no other, in the order they
     * begin; a sound in several runs is drawn as long as it plays, from the
     * first second it plays. [offsets] has each one that is out of sync with
     * its picture, by the same ids.
     */
    class SoundRows(val items: List<AudioItem>, val count: Int, val offsets: Map<String, Double>)

    fun soundRows(state: EditState): SoundRows {
        val timeline = state.layered
        if (timeline == null) {
            val items = state.audio.orEmpty()
            val converted = if (items.isEmpty()) null else state.toTimeline()
            val offsets = HashMap<String, Double>()
            if (converted != null) for (item in items) offset(converted, TrackSequenceView.soundID(state, item.id))?.let { offsets[item.id] = it.seconds }
            return SoundRows(items, if (items.isEmpty()) 0 else AudioItem.TRACKS, offsets)
        }
        val ends = ArrayList<EditTime>()
        val items = ArrayList<AudioItem>()
        val offsets = HashMap<String, Double>()
        for (sound in timeline.audio.filter { it.followsVideo == null }.sortedBy { it.start }) {
            val first = sound.mapping.runs.firstOrNull() ?: continue
            val row = ends.indexOfFirst { it <= sound.start }.let { if (it >= 0) it else ends.size }
            if (row == ends.size) ends += sound.end else ends[row] = sound.end
            val audio = TrackConversion.withFades(ClipAudio(sound.volumeDB, sound.muted), sound.fadeIn, sound.fadeOut).takeIf { it != ClipAudio() }
            items += AudioItem(sound.id, sound.sourceID, sound.start.seconds, first.sourceStart, first.sourceStart + sound.mapping.duration.seconds, row, audio)
            offset(timeline, sound.id)?.let { offsets[sound.id] = it.seconds }
        }
        return SoundRows(items, ends.size, offsets)
    }
}
