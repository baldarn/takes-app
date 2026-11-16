package app.takes.core

/**
 * The kinds of difference ([TrackParityKinds]) that have been read and
 * accepted: with `TAKES_PARITY_GATE=1` in the environment the parity suite
 * fails on any difference whose kind is not in this list. They are the kinds
 * docs/TRACKS-PARITY-DECISIONS.md decides as Unreachable or Invisible, and the ones adopted since. Every
 * other kind is "Kept until" a later stage or "Not adopted": the function
 * that would show it keeps today's result, so the kind must not appear. A
 * difference no kind explains can never be accepted.
 *
 * A kind in this list may appear; it need not. Of the Invisible ones, the
 * functions take up none where today's result could be kept at no cost, and
 * the comment on each says where it stands.
 */
internal object TrackParityAccepted {
    /** Unreachable: needs a clip whose sound is unlinked, which the editor cannot make yet. To be read again in S6. */
    private val unreachable = setOf(
        "unlinked-picture-does-not-ripple",
        "move-finds-no-cut-among-unlinked-pictures",
        "words-of-unlinked-sound",
        "bookmark-also-marks-the-unlinked-sound",
        "unlinked-clip-remembers-no-sound",
        "unlink-does-not-bake-the-transition",
        "unlink-is-never-refused",
        "sync-is-measured-on-footage-that-shows",
        "sound-settings-of-an-unlinked-clip",
    )

    /** Invisible: the result differs in a way nobody sees or hears. */
    private val invisible = setOf(
        // RangeEditing keeps its own copy, which carries the look only with words that show.
        "caption-look-travels-with-any-words",
        // Not taken up: regrouping at the same count makes new phrase ids and nothing else, which would be an empty step of Undo.
        "regroup-at-the-count-it-has",
        // RangeEditing carries the playhead as before.
        "playhead-in-time-a-transition-holds",
        // Adopted at S12 by TrackEditorEdits.addingBookmark; the clip's bookmark button (TimelineEditing.toggleBookmark) still marks its footage.
        "bookmark-at-the-last-second",
        // RangeEditing and removeIntervals drop a mark with its footage as before.
        "bookmark-outlives-its-footage",
        // Taken up at S4: a fade is set no longer than its item, from silence.
        "fade-no-longer-than-its-item",
        // Taken up for a trim at S4: a handle cuts a fade where the edge goes in and gives it back as the original's does.
        "fades-through-retimes-and-cuts",
        // Adopted at S12 for footage (TrackEditorEdits.insertingFootage); the takes of the capture ledger are inserted again at every read and keep their ids.
        "insert-new-identity",
        // Not taken up: the piece left of a sound item keeps the item's id, so the saved bytes of a project do not move.
        "later-piece-new-identity",
    )

    /**
     * Adopted at S4 (docs/TRACKS-PARITY-DECISIONS.md, "At S4"): the editing
     * functions now give the original's result, and an edit whose pictures
     * the result leaves over one another or apart is that timeline.
     */
    private val adopted = setOf(
        "handle-trim-outwards-overlaps",
        "handle-trim-inwards-is-a-deletion",
        "range-inside-a-transition",
        "split-refused-near-an-edge",
        "range-end-within-a-frame-of-a-cut",
        "removed-transition-leaves-its-time",
        "close-that-holds-pushes-nothing",
    )

    val kinds: Set<String> = unreachable + invisible + adopted
}
