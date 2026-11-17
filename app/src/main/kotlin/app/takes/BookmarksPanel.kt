package app.takes

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import java.io.File
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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
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
 * (EditorView.swift:1269-1295): "Add at" the playhead, then every bookmark
 * in time order with a picture of the footage it marks, its label and its
 * time; a tap goes there, a touch and hold opens a menu with Rename, as the
 * original's context menu, and a swipe to the left deletes it, as its swipe
 * action. Both are actions for a screen reader too. Bookmarks on a picture,
 * on a sound or in empty time are all here. [frame] is the recording and its
 * second shown at a timeline second, none where no picture plays.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BookmarksPanel(
    marks: List<ResolvedBookmark>, playhead: Double,
    add: () -> Unit, go: (Double) -> Unit, rename: (id: String, label: String) -> Unit, remove: (id: String) -> Unit, dismiss: () -> Unit,
    frame: (Double) -> Pair<File, Double>? = { null },
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
                val renameLabel = stringResource(R.string.bookmark_rename)
                val deleteLabel = stringResource(R.string.bookmark_delete)
                for (mark in marks) key(mark.id) {
                    val seconds = mark.time.seconds
                    var menu by remember { mutableStateOf(false) }
                    val swipe = rememberSwipeToDismissBoxState()
                    SwipeToDismissBox(
                        swipe,
                        backgroundContent = {
                            Box(Modifier.fillMaxSize().background(RecordRed, RoundedCornerShape(6.dp)).padding(end = 16.dp), Alignment.CenterEnd) {
                                Text(deleteLabel, color = Color.White)
                            }
                        },
                        enableDismissFromStartToEnd = false,
                        onDismiss = { remove(mark.id) },
                    ) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .combinedClickable(onLongClick = { menu = true }, onLongClickLabel = renameLabel) { go(seconds) }
                                .semantics {
                                    customActions = listOf(
                                        CustomAccessibilityAction(renameLabel) { renaming = mark; true },
                                        CustomAccessibilityAction(deleteLabel) { remove(mark.id); true },
                                    )
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val shown = remember(mark.time) { frame(seconds) }
                            val image by produceState<Bitmap?>(null, shown) { value = shown?.let { Thumbnails.frame(it.first, it.second) } }
                            Box(Modifier.padding(end = 8.dp).size(56.dp, 40.dp).clip(RoundedCornerShape(5.dp)), Alignment.Center) {
                                image?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                    ?: Icon(Icons.Filled.Bookmark, null, Modifier.size(20.dp), tint = BookmarkColor)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(mark.bookmark.label.ifEmpty { stringResource(R.string.bookmark_default) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(preciseLabel(seconds), color = Color(0xFFA0A4AB), fontSize = 12.sp)
                            }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(renameLabel) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                    onClick = { menu = false; renaming = mark },
                                )
                            }
                        }
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
 * while not [editable] (a Move) nothing changes. [playhead] is read when it
 * is needed, so that a scrub composes the editor again only when it passes
 * a bookmark.
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
    edit: EditState, saved: EditState, playhead: () -> Double, selected: EditTargets, editable: Boolean,
    commit: (EditState?, Double) -> Unit, said: (String) -> Unit,
): EditorBookmarkActions {
    val list = remember(edit) { TrackEditorEdits.bookmarks(edit) }
    val around by remember(list, playhead) {
        derivedStateOf {
            val at = playhead()
            Triple(
                list.firstOrNull { abs(it.time.seconds - at) < 0.5 / 30 },
                list.lastOrNull { it.time.seconds < at - 0.02 }?.time?.seconds,
                list.firstOrNull { it.time.seconds > at + 0.02 }?.time?.seconds,
            )
        }
    }
    val added = stringResource(R.string.bookmark_added)
    val removed = stringResource(R.string.bookmark_removed)
    return EditorBookmarkActions(
        list,
        under = around.first,
        previous = around.second,
        next = around.third,
        add = {
            val preferred = selected.clips.singleOrNull() ?: selected.audio.singleOrNull()?.let { TrackSequenceView.soundID(saved, it) }
            val at = playhead()
            if (editable) TrackEditorEdits.addingBookmark(saved, at, preferred)?.let { next -> commit(next, at); said(added) }
        },
        remove = { id -> if (editable) TrackEditorEdits.removingBookmark(saved, id)?.let { next -> commit(next, playhead()); said(removed) } },
        rename = { id, label -> if (editable) TrackEditorEdits.renamingBookmark(saved, id, label)?.let { commit(it, playhead()) } },
        editable = editable,
    )
}

/** The Bookmarks button of the transport with its menu, and the list it opens ([open]). */
@Composable
internal fun EditorBookmarks(
    bookmarks: EditorBookmarkActions, playhead: () -> Double, opened: Boolean, open: (Boolean) -> Unit, go: (Double) -> Unit,
    frame: (Double) -> Pair<File, Double>? = { null },
) {
    BookmarksMenu(
        under = bookmarks.under != null, canToggle = bookmarks.editable,
        toggle = bookmarks.toggle, previous = bookmarks.previous, next = bookmarks.next,
        marks = bookmarks.list.map { it.time.seconds to it.bookmark.label }, label = ::preciseLabel, go = go,
        manage = { open(true) },
    )
    if (opened) BookmarksPanel(
        bookmarks.list, playhead(), add = bookmarks.add, go = { open(false); go(it) },
        rename = bookmarks.rename, remove = bookmarks.remove, dismiss = { open(false) }, frame = frame,
    )
}
