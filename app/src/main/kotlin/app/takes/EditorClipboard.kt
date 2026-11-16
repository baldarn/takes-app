package app.takes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import app.takes.core.EditState
import app.takes.core.RangeMarks
import app.takes.core.TimelineClipboard
import app.takes.core.TimelinePasteTarget
import app.takes.core.TrackComponents
import app.takes.core.TrackEditorEdits
import app.takes.core.TrackSequenceView
import app.takes.core.VideoInsertionSite
import app.takes.core.toTimeline

/**
 * Copy, Cut and Paste of the editor through the track model
 * ([TrackEditorEdits]), on a row of clips and on rows alike, as the
 * original's (EditorView.swift:775-898): the targets Paste offers at the
 * playhead with their names, and what each of the three does.
 */
internal class EditorClipboard(
    val held: TimelineClipboard?,
    /** Where Paste can go with the playhead where it is, by name; empty while playing or with nothing to paste. */
    val choices: List<PasteChoice>,
    val replacesTransition: Boolean,
    val copy: () -> Unit,
    val cut: () -> Unit,
    /** Paste on the one place the playhead is on, or, on none, at the playhead: a linked picture opens time there, anything else is laid over it. */
    val paste: () -> Unit,
    /** "Paste here" at an add button of the timeline. */
    val pasteAt: (VideoInsertionSite) -> Unit,
)

/**
 * The clipboard of [projectID] in [store] at [playhead] on [edit], with
 * [marks] what Copy and Cut take. [commit] saves an edit and puts the
 * playhead; [select] gives the marks after; [said] tells what happened.
 */
@Composable
internal fun rememberClipboard(
    store: ProjectStore, projectID: String, edit: EditState, saved: EditState, marks: RangeMarks, playhead: Double, playing: Boolean,
    pause: () -> Unit, commit: (EditState?, Double) -> Unit, select: (RangeMarks) -> Unit, said: (String) -> Unit,
): EditorClipboard {
    val copied = stringResource(R.string.clipboard_copied)
    val cutDone = stringResource(R.string.clipboard_cut_done)
    val pasteDone = stringResource(R.string.clipboard_pasted)
    val nothingPasted = stringResource(R.string.clipboard_paste_nothing)
    val held = store.clipboards[projectID]
    // None while playing, as the original (EditorView.swift:627-632).
    val found = remember(edit, held, playhead, playing) { held?.takeIf { !playing }?.let { TrackEditorEdits.pasteChoices(edit, it, playhead) } }
    val labels = found?.targets.orEmpty().map { pasteLabel(it.labelKey(edit.toTimeline())) }
    // The pasted items selected, the playhead where they begin (EditorView.swift:861-869).
    val pasting = { target: TimelinePasteTarget? ->
        val made = held?.let { TrackEditorEdits.pasting(edit, it, playhead, target) }
        if (made == null) store.error = nothingPasted else {
            commit(made.edit, made.position)
            select(RangeMarks(selected = TrackComponents.targets(made.edit, made.selection)))
            said(pasteDone)
        }
    }
    return EditorClipboard(
        held,
        found?.targets.orEmpty().zip(labels) { target, label -> PasteChoice(label) { pasting(target) } },
        found?.replacesTransition == true,
        copy = {
            TrackSequenceView.selection(edit, edit.toTimeline(), marks.scope)?.let { TrackEditorEdits.copying(edit, it) }?.let {
                pause()
                store.clipboards[projectID] = it
                select(marks.cleared())
                said(copied)
            }
        },
        // Copy and delete as one Undo step. The clipboard takes the pieces once the edit is saved.
        cut = {
            TrackSequenceView.selection(edit, edit.toTimeline(), marks.scope)?.let { TrackEditorEdits.cutting(edit, it, playhead) }?.let { lifted ->
                commit(lifted.edit, lifted.position)
                if (store.selected?.edit != saved) {
                    store.clipboards[projectID] = lifted.clipboard
                    select(marks.cleared())
                    said(cutDone)
                }
            }
        },
        paste = { pasting(found?.targets?.singleOrNull()) },
        pasteAt = { site -> pasting(TrackEditorEdits.pasteTarget(edit, site)) },
    )
}
