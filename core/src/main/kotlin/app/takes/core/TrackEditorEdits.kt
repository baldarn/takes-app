package app.takes.core

/**
 * Copy, Cut and Paste with their targets, footage added at a place on the
 * timeline, and bookmarks anywhere, on the edit of a project
 * (docs/TRACKS-PLAN.md, S12). Each works on the edit's timeline, through the
 * track model ([TrackClipboard], [TrackEditing.inserting], [TrackBookmarks]),
 * for a sequence of clips and for a timeline alike, and gives an edit to
 * commit: a sequence when the result is one, else the timeline
 * ([TrackTimeline.toEdit]). So a bookmark in empty time, a paste over a
 * picture or footage in a gap make an edit that is no sequence, as in the
 * original.
 *
 * The editor logic of the original, YouRec/EditorView.swift:775-898 and
 * :1255-1292.
 */
object TrackEditorEdits {
    /** An edit made, with what it added selected and the place to put the playhead. */
    data class Made(val edit: EditState, val selection: TimelineSelection, val position: Double)

    /** What Cut leaves: the edit without the pieces, the pieces, and where the playhead goes. */
    data class Cut(val edit: EditState, val clipboard: TimelineClipboard, val position: Double)

    /** What the Paste button offers at the playhead: its targets (none, one, or a menu of several) and whether it says "Replaces transition". */
    data class PasteChoices(val targets: List<TimelinePasteTarget>, val replacesTransition: Boolean)

    // MARK: Clipboard

    /** Every place of [state] a clipboard can go ([TrackClipboard.pasteTargets]). */
    fun pasteTargets(state: EditState): List<TimelinePasteTarget> = TrackClipboard.pasteTargets(state.toTimeline())

    /**
     * What Paste offers with the playhead at [playhead] seconds: the targets
     * it is on, which the editor names by [TimelinePasteTarget.labelKey],
     * and whether pasting [clipboard] there undoes a transition.
     * YouRec/EditorView.swift:631-632, :775-798.
     */
    fun pasteChoices(state: EditState, clipboard: TimelineClipboard, playhead: Double): PasteChoices {
        val timeline = state.toTimeline()
        val at = EditTime.of(playhead)
        val targets = TrackClipboard.targetsAt(timeline, at)
        return PasteChoices(targets, TrackClipboard.replacesTransition(timeline, clipboard, at, targets))
    }

    /** The target of "Paste here" on the add button [site] ([TrackClipboard.target]). */
    fun pasteTarget(state: EditState, site: VideoInsertionSite): TimelinePasteTarget? = TrackClipboard.target(state.toTimeline(), site)

    /** A copy of [targets] of [state] ([TrackClipboard.copied]); null when it holds nothing. */
    fun copying(state: EditState, targets: TimelineSelection): TimelineClipboard? =
        TrackClipboard.copied(state.toTimeline(), targets).takeUnless { it.isEmpty }

    /** [targets] lifted out of [state] with the playhead at [playhead] seconds ([TrackClipboard.cut]); null when there is nothing to lift. */
    fun cutting(state: EditState, targets: TimelineSelection, playhead: Double): Cut? {
        val cut = TrackClipboard.cut(state.toTimeline(), targets, EditTime.of(playhead)) ?: return null
        return Cut(cut.timeline.toEdit(state.captureBase), cut.clipboard, cut.position.seconds)
    }

    /**
     * [clipboard] pasted into [state] on [target], or, with none, at the
     * playhead at [playhead] seconds: a clipboard that inserts time opens
     * the picture sequence there, any other is laid over what is there, its
     * pictures in front ([TrackClipboard.pasting]). The pasted items are
     * selected and the playhead goes to where they begin. Null when nothing
     * was pasted. YouRec/EditorView.swift:861-869.
     */
    fun pasting(state: EditState, clipboard: TimelineClipboard, playhead: Double, target: TimelinePasteTarget? = null): Made? {
        if (clipboard.isEmpty || !playhead.isFinite()) return null
        val pasted = TrackClipboard.pasting(state.toTimeline(), clipboard, EditTime.of(playhead), target)
        if (pasted.selection.isEmpty) return null
        val start = TrackLinking.interval(pasted.timeline, pasted.selection)?.start ?: return null
        return Made(pasted.timeline.toEdit(state.captureBase), pasted.selection, start.seconds)
    }

    // MARK: Footage

    /**
     * [clips] put into [state] as new footage ([TrackEditing.inserting]):
     * at [site], beside the picture there, using the free time first, or,
     * without one, at [time] seconds with everything from there on moved
     * later. Every clip gets an id of its own, as in the original, and the
     * new pictures are selected; the playhead goes to where the first of
     * them begins. The sources in [pictureOnlySources] (photos) get no sound.
     * Null when there is nothing to insert. YouRec/TimelineEditing.swift:86-127,
     * YouRec/ProjectStore.swift:431-434.
     */
    fun insertingFootage(
        state: EditState, clips: List<TimelineClip>, time: Double, site: VideoInsertionSite? = null,
        pictureOnlySources: Set<String> = emptySet(),
    ): Made? {
        if (clips.isEmpty() || (site == null && !time.isFinite())) return null
        val timeline = state.toTimeline()
        val before = timeline.video.mapTo(HashSet()) { it.id } + timeline.audio.map { it.id }
        val result = TrackEditing.inserting(timeline, clips, if (time.isFinite()) time else 0.0, site, pictureOnlySources)
        val added = TimelineSelection(
            result.video.filter { it.id !in before }.mapTo(LinkedHashSet()) { it.id },
            result.audio.filter { it.id !in before }.mapTo(LinkedHashSet()) { it.id },
        )
        val start = TrackLinking.interval(result, added)?.start ?: return null
        return Made(result.toEdit(state.captureBase), added, start.seconds)
    }

    // MARK: Bookmarks

    /** Every bookmark of [state] that is on the timeline now, in time order ([TrackTimeline.resolvedBookmarks]): the list of the Bookmarks panel. */
    fun bookmarks(state: EditState): List<ResolvedBookmark> = state.toTimeline().resolvedBookmarks.sortedBy { it.time }

    /**
     * A bookmark at [time] seconds of [state], wherever that is
     * ([TrackBookmarks.anchor]): on the picture showing there ([preferred]
     * when that is one of them, else the one in front), on a sound when
     * [preferred] is one or no picture plays, or on the timeline itself in
     * empty time and at the very end. Null when [time] is not a number.
     * YouRec/EditorView.swift:1265-1268.
     */
    fun addingBookmark(state: EditState, time: Double, preferred: String? = null, label: String = ""): EditState? {
        if (!time.isFinite()) return null
        val timeline = state.toTimeline()
        val at = maxOf(EditTime.ZERO, minOf(timeline.duration, EditTime.of(time)))
        return TrackBookmarks.adding(timeline, at, preferred, label.take(TrackBookmarks.EDITOR_LABEL)).toEdit(state.captureBase)
    }

    /** [state] without the bookmark [id]; null when it has none such. */
    fun removingBookmark(state: EditState, id: String): EditState? = TrackEditState.editing(state) { TrackBookmarks.removing(it, id) }

    /** The bookmark [id] named [label] ([TrackBookmarks.labelled]); null when nothing changes. */
    fun renamingBookmark(state: EditState, id: String, label: String): EditState? = TrackEditState.editing(state) { TrackBookmarks.labelled(it, id, label) }

    /** The pictures and sounds of [state] that play one of [sources] ([TrackLinking.items]). */
    fun usingSources(state: EditState, sources: Set<String>): TimelineSelection = TrackLinking.items(state.toTimeline(), sources)

    /**
     * [state] without the pictures and sounds that play one of [sources], as the original's "Remove missing
     * takes from timeline" (YouRec/ContentView.swift:457-466): one edit, what was linked to them closes up as a
     * removal does ([TrackEditing.removing]). Null when nothing plays them.
     */
    fun removingSources(state: EditState, sources: Set<String>): EditState? = TrackEditState.editing(state) { timeline ->
        TrackLinking.items(timeline, sources).takeUnless { it.isEmpty }?.let { TrackEditing.removing(timeline, it).timeline }
    }
}
