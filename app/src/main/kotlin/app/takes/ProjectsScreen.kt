package app.takes

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takes.core.Project
import app.takes.core.ProjectException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private val Card = Color(0xFF1B1D21)
private val Muted = Color(0xFFA0A4AB)
private val SelectedGreen = Color(0xFF34C759)

private fun dateLabel(time: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))

/** [open] receives the chosen project and the destination tab: 0 recorder, 2 editor. */
@Composable
fun ProjectsScreen(store: ProjectStore, modifier: Modifier = Modifier, open: (Project, Int) -> Unit) {
    var renaming by remember { mutableStateOf<Project?>(null) }
    var erasing by remember { mutableStateOf<Project?>(null) }
    var showingCredits by remember { mutableStateOf(false) }
    // The project that picked videos go to; empty for a new project.
    var importInto by rememberSaveable { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { videos ->
        store.importVideos(importInto.ifEmpty { null }, videos)
    }
    // The project being zipped, while the user chooses where to save it.
    var zipping by rememberSaveable { mutableStateOf("") }
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { destination ->
        val project = store.projects.firstOrNull { it.id == zipping }
        if (destination != null && project != null) store.archive(project, destination)
    }
    val zipName = stringResource(R.string.projects_zip_fallback_name)
    val openZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { archive ->
        if (archive != null) store.openArchive(archive)
    }
    val pickVideos = { projectID: String ->
        importInto = projectID
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            // The buttons go under the title where they do not fit beside it, as at the largest text sizes.
            FlowRow(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text(stringResource(R.string.projects_title), Modifier.align(Alignment.CenterVertically), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                FlowRow(Modifier.align(Alignment.CenterVertically)) {
                    TextButton(onClick = { pickVideos("") }, contentPadding = PaddingValues(horizontal = 8.dp)) { Icon(Icons.Outlined.FileDownload, null, Modifier.size(18.dp)); Text(stringResource(R.string.projects_import), Modifier.padding(start = 4.dp), maxLines = 1, softWrap = false) }
                    Button(onClick = { store.create()?.let { open(it, 0) } }, contentPadding = PaddingValues(horizontal = 14.dp)) { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Text(stringResource(R.string.projects_new_recording), Modifier.padding(start = 4.dp), maxLines = 1, softWrap = false) }
                }
            }
        }
        if (store.projects.isEmpty()) {
            item { Text(stringResource(R.string.projects_empty), color = Muted) }
        }
        items(store.projects, key = { it.id }) { project ->
            ProjectCard(project, store, open, rename = { renaming = project }, addVideos = { pickVideos(project.id) },
                zip = { zipping = project.id; saveZip.launch(project.title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifEmpty { zipName } + ".zip") })
        }
        items(store.unavailable.toList(), key = { "unavailable-" + it.first }) { (folder, reason) ->
            Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(16.dp)) {
                Text(stringResource(R.string.projects_unavailable_title), color = Color.White, fontWeight = FontWeight.SemiBold)
                // The reason is a text of the core module; show ours when it is one we know.
                val known = ProjectException.Reason.entries.firstOrNull { it.message == reason }
                Text(known?.let { stringResource(it.label) } ?: reason, color = Muted, fontSize = 13.sp)
                Text(folder, color = Muted, fontSize = 11.sp)
            }
        }
        if (store.deleted.isNotEmpty()) {
            item { Text(stringResource(R.string.projects_recently_deleted), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp)) }
            items(store.deleted, key = { "deleted-" + it.project.id }) { entry ->
                Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(16.dp)) {
                    Text(entry.project.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(entry.date?.let { stringResource(R.string.projects_deleted_summary, summary(entry.project), dateLabel(it)) } ?: summary(entry.project), color = Muted, fontSize = 13.sp)
                    Row {
                        TextButton(onClick = { store.restore(entry.project) }) { Text(stringResource(R.string.projects_restore)) }
                        TextButton(onClick = { erasing = entry.project }) { Text(stringResource(R.string.projects_delete_permanently), color = RecordRed) }
                    }
                }
            }
        }
        item {
            // Some file managers label a ZIP as generic data, so both kinds are offered.
            TextButton(onClick = { openZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }) {
                Text(stringResource(R.string.projects_open_zip), color = Muted)
            }
        }
        item { TextButton(onClick = { showingCredits = true }) { Text(stringResource(R.string.credits_title), color = Muted) } }
    }

    if (showingCredits) CreditsScreen(report = { store.error = it }) { showingCredits = false }
    store.importing?.let { step ->
        // As in the original, an import, a duplicate or a ZIP can be cancelled, and then keeps nothing it made.
        AlertDialog(onDismissRequest = {}, title = { Text(stringResource(R.string.common_importing)) },
            text = { Text(if (store.cancelling) stringResource(R.string.projects_cancelling) else step) },
            confirmButton = {
                if (store.cancellable) TextButton(onClick = store::cancelOperation, enabled = !store.cancelling) { Text(stringResource(R.string.common_cancel)) }
            })
    }
    renaming?.let { project ->
        var title by remember(project.id) { mutableStateOf(project.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.projects_rename_title)) },
            text = { OutlinedTextField(title, { title = it.take(120) }, singleLine = true) },
            // As in the original, an empty or blank name cannot be saved.
            confirmButton = { TextButton(onClick = { store.rename(project, title); renaming = null }, enabled = title.isNotBlank()) { Text(stringResource(R.string.projects_rename)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    erasing?.let { project ->
        AlertDialog(
            onDismissRequest = { erasing = null },
            title = { Text(stringResource(R.string.projects_erase_title, project.title)) },
            text = { Text(pluralStringResource(R.plurals.projects_erase_message, project.recordings.size, project.recordings.size)) },
            confirmButton = { TextButton(onClick = { store.erase(project); erasing = null }) { Text(stringResource(R.string.projects_delete_permanently), color = RecordRed) } },
            dismissButton = { TextButton(onClick = { erasing = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun summary(project: Project): String {
    // As the original's card: the takes, the sound files when there are any, and the length of the edit.
    val takes = project.recordings.size
    val sounds = project.sources.size - takes
    val parts = mutableListOf<String>()
    if (takes > 0) parts += pluralStringResource(R.plurals.projects_summary_takes, takes, takes)
    else if (sounds == 0) parts += stringResource(R.string.projects_summary_empty)
    if (sounds > 0) parts += pluralStringResource(R.plurals.projects_summary_sounds, sounds, sounds)
    parts += stringResource(R.string.projects_summary_edited, timeLabel(project.edit.duration))
    return parts.joinToString(" · ")
}

@Composable
private fun ProjectCard(project: Project, store: ProjectStore, open: (Project, Int) -> Unit, rename: () -> Unit, addVideos: () -> Unit, zip: () -> Unit) {
    var originals by remember { mutableStateOf(false) }
    var unfinished by remember { mutableStateOf(false) }
    if (unfinished) UnfinishedRecordings(project, store) { unfinished = false }
    if (originals) OriginalRecordings(project, store) { originals = false }
    Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(project.recordings.firstOrNull()?.let { store.file(project, it) })
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                // As in the original, tapping the title renames the project.
                Text(project.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2,
                    modifier = Modifier.clickable(onClickLabel = stringResource(R.string.projects_rename_named, project.title), onClick = rename))
                Text(summary(project), color = Muted, fontSize = 13.sp)
                Text(dateLabel(project.updatedAt), color = Muted, fontSize = 12.sp)
            }
            // The project Record and Edit act on, as the original's check mark.
            if (store.selectedID == project.id) Icon(Icons.Filled.CheckCircle, stringResource(R.string.projects_selected), Modifier.padding(start = 8.dp).size(22.dp), tint = SelectedGreen)
        }
        // As in the original: the two main actions stay visible, the rest is in a menu.
        // The two go one over the other where they do not fit beside the menu, which stays at the end.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val tight = PaddingValues(horizontal = 8.dp)
            FlowRow(Modifier.weight(1f)) {
                TextButton(onClick = { open(project, 0) }, contentPadding = tight) { Text(stringResource(R.string.projects_continue_recording), maxLines = 1, softWrap = false) }
                if (project.sources.isNotEmpty()) TextButton(onClick = { open(project, 2) }, contentPadding = tight) {
                    Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.projects_edit), Modifier.padding(start = 4.dp), maxLines = 1, softWrap = false)
                }
            }
            Box {
                var menu by remember { mutableStateOf(false) }
                val choose = { action: () -> Unit -> menu = false; action() }
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.projects_actions, project.title), tint = Color.White) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.projects_rename)) }, { choose(rename) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                    DropdownMenuItem({ Text(stringResource(R.string.projects_duplicate)) }, { choose { store.duplicate(project) } }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) })
                    // As in the original, what was set aside is among the originals too.
                    if (project.sources.isNotEmpty() || store.unfinishedRecordings(project).isNotEmpty()) DropdownMenuItem(
                        { Text(stringResource(R.string.projects_originals)) }, { choose { originals = true } },
                        leadingIcon = { Icon(Icons.Outlined.VideoLibrary, null) })
                    DropdownMenuItem({ Text(stringResource(R.string.projects_add_videos)) }, { choose(addVideos) }, leadingIcon = { Icon(Icons.Filled.Add, null) })
                    if (project.sources.isNotEmpty()) DropdownMenuItem({ Text(stringResource(R.string.projects_zip)) }, { choose(zip) }, leadingIcon = { Icon(Icons.Outlined.Inventory2, null) })
                    if (store.unfinishedRecordings(project).isNotEmpty()) DropdownMenuItem(
                        { Text(stringResource(R.string.recorder_unfinished_title)) }, { choose { unfinished = true } }, leadingIcon = { Icon(Icons.Outlined.VideocamOff, null) })
                    DropdownMenuItem({ Text(stringResource(R.string.common_delete), color = RecordRed) }, { choose { store.delete(project) } }, leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = RecordRed) })
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(file: File?) {
    val image by produceState<Bitmap?>(null, file) {
        value = file?.let {
            withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(it.path)
                    retriever.getFrameAtTime(0)?.let { frame ->
                        val scale = 320f / maxOf(frame.width, frame.height)
                        if (scale < 1) Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true) else frame
                    }
                } catch (_: Exception) {
                    null
                } finally {
                    runCatching { retriever.release() }
                }
            }
        }
    }
    val shape = Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black)
    image?.let { Image(it.asImageBitmap(), null, shape, contentScale = ContentScale.Crop) } ?: Box(shape)
}
