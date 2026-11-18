package app.takes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takes.core.VideoInsertionSite

/**
 * What can be added, as the original's add panel (AddVideoPanel.swift:20-72):
 * at the playhead from the Add video tool, or at an add button of the
 * timeline ([site]), where the clipboard can be pasted too ([pasteHere]).
 * [at] names the playhead for the first.
 */
@Composable
internal fun AddVideoDialog(
    site: VideoInsertionSite?, at: String, unusedCount: Int,
    pasteHere: (() -> Unit)?, recordHere: () -> Unit, phone: () -> Unit, unused: () -> Unit, dismiss: () -> Unit,
    files: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = {
            Text(
                when (site) {
                    null -> stringResource(R.string.add_video_title, at)
                    VideoInsertionSite.Start -> stringResource(R.string.add_site_start)
                    VideoInsertionSite.End -> stringResource(R.string.add_site_end)
                    is VideoInsertionSite.Gap -> stringResource(R.string.add_site_gap)
                },
            )
        },
        text = {
            // Scrolls where the screen is short, as in landscape.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                pasteHere?.let {
                    TextButton(onClick = it) {
                        Icon(Icons.Outlined.ContentPaste, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.paste_here), Modifier.padding(start = 8.dp))
                    }
                }
                TextButton(onClick = recordHere) {
                    Icon(Icons.Filled.FiberManualRecord, null, Modifier.size(18.dp), tint = RecordRed)
                    Text(stringResource(R.string.editor_record_here), Modifier.padding(start = 8.dp))
                }
                TextButton(onClick = phone) {
                    Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.add_media_phone), Modifier.padding(start = 8.dp))
                }
                // From Files, several added in the order of their names (AddVideoPanel.swift:50-55, 107-116).
                files?.let {
                    TextButton(onClick = it) {
                        Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp))
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(stringResource(R.string.add_media_files))
                            Text(stringResource(R.string.add_media_files_detail), fontSize = 12.sp)
                        }
                    }
                }
                if (unusedCount > 0) TextButton(onClick = unused) {
                    Icon(Icons.Outlined.VideoLibrary, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.add_video_unused, unusedCount), Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
