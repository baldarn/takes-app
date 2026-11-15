package app.takes.core

/**
 * Bookmarks of the track timeline.
 *
 * A bookmark is saved by what it is attached to, not by a time
 * ([BookmarkAnchor]): a source second of an occurrence of footage or sound,
 * which follows that material through cuts, moves, trims and changes of
 * speed; a tick inside an occurrence that holds one frame; or a tick of the
 * timeline, for a mark put where nothing plays. Where each is now is
 * [TrackTimeline.resolvedBookmarks]; a mark whose material is not on the
 * timeline is kept and left out of that list, and comes back with the
 * material.
 *
 * YouRec/TrackTimeline.swift:1162-1174, :1470-1482; YouRec/EditorView.swift:1259-1292.
 */
object TrackBookmarks {
    /** The editor keeps this much of a label typed for a bookmark (YouRec/EditorView.swift:1292); a saved one may be [TrackInvariants.MAXIMUM_LABEL] long. */
    const val EDITOR_LABEL = 120

    /** The next or previous bookmark is one further than this from the playhead, in seconds (YouRec/EditorView.swift:1262). */
    const val NAVIGATION_SLACK = 0.02

    /**
     * What a bookmark put at [time] is attached to.
     *
     * The picture showing there: [preferred] when that is one of them,
     * otherwise the one in front. When [preferred] is a sound, no picture:
     * that sound if it plays there, otherwise the first sound that does.
     * With no picture, the first sound playing. With nothing, the timeline.
     *
     * A picture gives a source second, or, where it holds one frame, the
     * tick inside its occurrence. The original decides that by the clip's
     * held frame; clips here have none, so it is decided by the run of the
     * picture's timing, which for a held frame is the same thing.
     */
    fun anchor(timeline: TrackTimeline, time: EditTime, preferred: String? = null): BookmarkAnchor {
        val active = timeline.video.filter { time in it.interval }
        val audioSelected = preferred != null && timeline.audio.any { it.id == preferred }
        val item = if (audioSelected) null else active.firstOrNull { it.id == preferred } ?: active.maxByOrNull { it.layer }
        if (item != null) {
            val local = time - item.start
            item.mapping.source(local)?.let { source ->
                return if (held(item.mapping, local)) BookmarkAnchor.Local(item.occurrenceID, item.localOrigin + local) else BookmarkAnchor.Source(item.occurrenceID, item.content.sourceID, source)
            }
        }
        val sound = timeline.audio.firstOrNull { it.id == preferred && time in it.interval } ?: timeline.audio.firstOrNull { time in it.interval }
        if (sound != null) sound.mapping.source(time - sound.start)?.let { return BookmarkAnchor.Source(sound.occurrenceID, sound.sourceID, it) }
        return BookmarkAnchor.Project(time)
    }

    private fun held(map: SourcePlaybackMap, local: EditTime): Boolean {
        var cursor = EditTime.ZERO
        for (run in map.runs) {
            if (local < cursor + run.duration) return run.isHold
            cursor += run.duration
        }
        return false
    }

    /** The timeline with a bookmark at [time], attached as [anchor] says. */
    fun adding(timeline: TrackTimeline, time: EditTime, preferred: String? = null, label: String = "", id: String = newID()): TrackTimeline =
        timeline.copy(bookmarks = timeline.bookmarks + TimelineBookmark(id, label, anchor(timeline, time, preferred)))

    /** The timeline without the bookmark [id]. */
    fun removing(timeline: TrackTimeline, id: String): TrackTimeline =
        if (timeline.bookmarks.none { it.id == id }) timeline else timeline.copy(bookmarks = timeline.bookmarks.filter { it.id != id })

    /** The bookmark [id] named [label], cut to [EDITOR_LABEL] characters. The timeline as it is when there is no such bookmark. */
    fun labelled(timeline: TrackTimeline, id: String, label: String): TrackTimeline {
        if (timeline.bookmarks.none { it.id == id }) return timeline
        val kept = label.take(EDITOR_LABEL)
        return timeline.copy(bookmarks = timeline.bookmarks.map { if (it.id == id) it.copy(label = kept) else it })
    }

    /**
     * The item [bookmark] is attached to now: the first picture, or else the
     * first sound, that plays its source second; for a tick inside a held
     * occurrence, the piece that holds it. Null for a mark on the timeline
     * itself and for one whose material is gone. A move leaves out the marks
     * of what it moves by this.
     */
    fun owner(timeline: TrackTimeline, bookmark: TimelineBookmark): String? = when (val anchor = bookmark.anchor) {
        is BookmarkAnchor.Project -> null
        is BookmarkAnchor.Source ->
            timeline.video.firstOrNull { it.occurrenceID == anchor.occurrence && it.content.sourceID == anchor.source && it.mapping.local(anchor.time) != null }?.id
                ?: timeline.audio.firstOrNull { it.occurrenceID == anchor.occurrence && it.sourceID == anchor.source && it.mapping.local(anchor.time) != null }?.id
        is BookmarkAnchor.Local ->
            timeline.video.firstOrNull { it.occurrenceID == anchor.occurrence && anchor.offset >= it.localOrigin && anchor.offset < it.localOrigin + it.mapping.duration }?.id
    }

    /** The bookmarks that are nowhere on the timeline now: their footage or sound was cut away. They are kept, and return with it. */
    fun hidden(timeline: TrackTimeline): List<TimelineBookmark> {
        val shown = timeline.resolvedBookmarks.mapTo(HashSet()) { it.id }
        return timeline.bookmarks.filter { it.id !in shown }
    }

    /** The first of [marks] (in time order, as [TrackTimeline.resolvedBookmarks] gives them) after the playhead at [playhead] seconds. */
    fun next(marks: List<ResolvedBookmark>, playhead: Double): ResolvedBookmark? = marks.firstOrNull { it.time.seconds > playhead + NAVIGATION_SLACK }

    /** The last of [marks] before the playhead. */
    fun previous(marks: List<ResolvedBookmark>, playhead: Double): ResolvedBookmark? = marks.lastOrNull { it.time.seconds < playhead - NAVIGATION_SLACK }

    /**
     * [bookmarks] after [length] of time was opened at [from]: the marks on
     * the timeline itself at or after it move later; marks on footage or
     * sound follow their material and are left alone.
     */
    fun shiftingProjectMarks(bookmarks: List<TimelineBookmark>, from: EditTime, length: EditTime): List<TimelineBookmark> = bookmarks.map { mark ->
        val anchor = mark.anchor
        if (anchor is BookmarkAnchor.Project && anchor.time >= from) mark.copy(anchor = BookmarkAnchor.Project(anchor.time + length)) else mark
    }
}

/** See [TrackBookmarks.adding]. */
fun TrackTimeline.addingBookmark(time: EditTime, preferred: String? = null, label: String = ""): TrackTimeline = TrackBookmarks.adding(this, time, preferred, label)
