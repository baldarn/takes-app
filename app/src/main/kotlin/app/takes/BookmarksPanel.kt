package app.takes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takes.core.EditState
import app.takes.core.EditTargets
import app.takes.core.ResolvedBookmark
import app.takes.core.TrackEditorEdits
import app.takes.core.TrackSequenceView
import kotlin.math.abs
import app.takes.core.TrackBookmarks

/**
 * The list of bookmarks, as the original's Bookmarks panel
 * (EditorView.swift:1274-1295): "Add at" the playhead, then every bookmark
 * in time order by its label and time; a tap goes there, and each can be
 * renamed or deleted. Bookmarks on a picture, on a sound or in empty time are
 * all here.
 */
@Composable
internal fun BookmarksPanel(
    marks: List<ResolvedBookmark>, playhead: Double,
    add: () -> Unit, go: (Double) -> Unit, rename: (id: String, label: String) -> Unit, remove: (id: String) -> Unit, dismiss: () -> Unit,
) {
    var renaming by remember { mutableStateOf<ResolvedBookmark?>(null) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.editor_bookmarks)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TextButton(onClick = add, Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                    Icon(Icons.Outlined.BookmarkAdd, null, Modifier.size(20.dp))
                    Text(stringResource(R.string.bookmarks_add_at, preciseLabel(playhead)), Modifier.weight(1f).padding(start = 8.dp))
                }
                if (marks.isEmpty()) Text(stringResource(R.string.bookmarks_none), color = Color(0xFFA0A4AB), fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                for (mark in marks) {
                    val seconds = mark.time.seconds
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { go(seconds) }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Bookmark, null, Modifier.padding(horizontal = 8.dp).size(20.dp), tint = BookmarkColor)
                        Column(Modifier.weight(1f)) {
                            Text(mark.bookmark.label.ifEmpty { stringResource(R.string.bookmark_default) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(preciseLabel(seconds), color = Color(0xFFA0A4AB), fontSize = 12.sp)
                        }
                        IconButton(onClick = { renaming = mark }) { Icon(Icons.Outlined.Edit, stringResource(R.string.bookmark_rename)) }
                        IconButton(onClick = { remove(mark.id) }) { Icon(Icons.Outlined.Delete, stringResource(R.string.bookmark_delete), tint = RecordRed) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_done)) } },
    )
    renaming?.let { mark ->
        var name by remember(mark.id) { mutableStateOf(mark.bookmark.label) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.bookmark_label_title)) },
            text = {
                OutlinedTextField(
                    name, { name = it.take(TrackBookmarks.EDITOR_LABEL) }, singleLine = true,
                    label = { Text(stringResource(R.string.bookmark_label)) },
                )
            },
            confirmButton = { TextButton(onClick = { renaming = null; rename(mark.id, name) }) { Text(stringResource(R.string.common_save)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/**
 * The bookmarks of the edit and what the editor does with them: anywhere,
 * on the picture in front or the one selected, on a selected sound, or in
 * empty time and at the end (EditorView.swift:1259-1268). They are made on
 * the [saved] edit, so a draft on the screen is never committed with one;
 * while not [editable] (a Move) nothing changes.
 */
internal class EditorBookmarkActions(
    val list: List<ResolvedBookmark>, val under: ResolvedBookmark?, val previous: Double?, val next: Double?,
    val add: () -> Unit, val remove: (String) -> Unit, val rename: (String, String) -> Unit, val editable: Boolean,
) {
    val times: List<Double> get() = list.map { it.time.seconds }
    val toggle: () -> Unit = { under?.let { remove(it.id) } ?: add() }
}

@Composable
internal fun rememberBookmarks(
    edit: EditState, saved: EditState, playhead: Double, selected: EditTargets, editable: Boolean,
    commit: (EditState?, Double) -> Unit, said: (String) -> Unit,
): EditorBookmarkActions {
    val list = remember(edit) { TrackEditorEdits.bookmarks(edit) }
    val added = stringResource(R.string.bookmark_added)
    val removed = stringResource(R.string.bookmark_removed)
    return EditorBookmarkActions(
        list,
        under = list.firstOrNull { abs(it.time.seconds - playhead) < 0.5 / 30 },
        previous = list.lastOrNull { it.time.seconds < playhead - 0.02 }?.time?.seconds,
        next = list.firstOrNull { it.time.seconds > playhead + 0.02 }?.time?.seconds,
        add = {
            val preferred = selected.clips.singleOrNull() ?: selected.audio.singleOrNull()?.let { TrackSequenceView.soundID(saved, it) }
            if (editable) TrackEditorEdits.addingBookmark(saved, playhead, preferred)?.let { next -> commit(next, playhead); said(added) }
        },
        remove = { id -> if (editable) TrackEditorEdits.removingBookmark(saved, id)?.let { next -> commit(next, playhead); said(removed) } },
        rename = { id, label -> if (editable) TrackEditorEdits.renamingBookmark(saved, id, label)?.let { commit(it, playhead) } },
        editable = editable,
    )
}

/** The Bookmarks button of the transport with its menu, and the list it opens ([open]). */
@Composable
internal fun EditorBookmarks(bookmarks: EditorBookmarkActions, playhead: Double, opened: Boolean, open: (Boolean) -> Unit, go: (Double) -> Unit) {
    BookmarksMenu(
        under = bookmarks.under != null, canToggle = bookmarks.editable,
        toggle = bookmarks.toggle, previous = bookmarks.previous, next = bookmarks.next,
        marks = bookmarks.list.map { it.time.seconds to it.bookmark.label }, label = ::preciseLabel, go = go,
        manage = { open(true) },
    )
    if (opened) BookmarksPanel(
        bookmarks.list, playhead, add = bookmarks.add, go = { open(false); go(it) },
        rename = bookmarks.rename, remove = bookmarks.remove, dismiss = { open(false) },
    )
}
