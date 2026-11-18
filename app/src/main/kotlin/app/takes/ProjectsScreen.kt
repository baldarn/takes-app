package app.takes

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.outlined.PhoneIphone
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import app.takes.core.DeletionRules
import app.takes.core.Project
import app.takes.core.ProjectException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import java.text.DateFormat
import java.util.Date

private val Card = Color(0xFF1B1D21)
private val Muted = Color(0xFFA0A4AB)
private val SelectedGreen = Color(0xFF34C759)
private val Caution = Color(0xFFFF9F0A)

private fun dateLabel(time: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))

/** [open] receives the chosen project and the destination tab: 0 recorder, 2 editor. */
@Composable
fun ProjectsScreen(store: ProjectStore, exporter: Exporter, modifier: Modifier = Modifier, open: (Project, Int) -> Unit) {
    var renaming by remember { mutableStateOf<Project?>(null) }
    var erasing by remember { mutableStateOf<Project?>(null) }
    var showingCredits by remember { mutableStateOf(false) }
    // The project that cannot be read whose original recordings are open.
    var unreadable by rememberSaveable { mutableStateOf("") }
    // The project whose export panel is open from its card, as the original's export sheet.
    var exportingID by rememberSaveable { mutableStateOf("") }
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
    // The project being zipped for the iPhone, while the user chooses where to save it.
    var zippingForIPhone by rememberSaveable { mutableStateOf("") }
    val saveIPhoneZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { destination ->
        val project = store.projects.firstOrNull { it.id == zippingForIPhone }
        if (destination != null && project != null) store.archiveForIPhone(project, destination)
    }
    val zipName = stringResource(R.string.projects_zip_fallback_name)
    val openZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { archive ->
        if (archive != null) store.openArchive(archive)
    }
    val pickVideos = { projectID: String ->
        importInto = projectID
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }

    // As the original, on arriving at the list: projects over 30 days in Recently deleted are offered for removal.
    var expired by remember { mutableStateOf(emptyList<DeletedProject>()) }
    LaunchedEffect(Unit) { if (store.importing == null && store.error == null) expired = store.expiredDeletions() }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LazyColumn(modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            ProjectCard(project, store, open, rename = { renaming = project }, addVideos = { pickVideos(project.id) }, export = { exportingID = project.id },
                zip = { zipping = project.id; saveZip.launch(project.title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifEmpty { zipName } + ".zip") },
                zipForIPhone = { store.checkForIPhone(project) })
        }
        items(store.unavailable.toList(), key = { "unavailable-" + it.first }) { (folder, reason) ->
            // As the original's card of a project that cannot be read, which opens its recordings (ContentView.swift:680-687).
            Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.WarningAmber, null, Modifier.size(20.dp), tint = Color.White)
                    Text(stringResource(R.string.projects_unavailable_title), Modifier.padding(start = 8.dp), color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                Text(unavailableReason(reason), color = Muted, fontSize = 13.sp)
                Text(folder, color = Muted, fontSize = 11.sp)
                OutlinedButton(onClick = { unreadable = folder }, Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.projects_unavailable_originals)) }
            }
        }
        if (store.deleted.isNotEmpty()) {
            item { Text(stringResource(R.string.projects_recently_deleted), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp)) }
            items(store.deleted, key = { "deleted-" + it.project.id }) { entry ->
                Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(16.dp)) {
                    Text(entry.project.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(entry.date?.let { stringResource(R.string.projects_deleted_summary, summary(entry.project), dateLabel(it)) } ?: summary(entry.project), color = Muted, fontSize = 13.sp)
                    when {
                        entry.date == null -> Text(stringResource(R.string.projects_deleted_no_date), color = Muted, fontSize = 12.sp)
                        DeletionRules.isOlderThan30Days(entry.date, System.currentTimeMillis()) -> Text(stringResource(R.string.projects_over_30_days), color = Caution, fontSize = 12.sp)
                    }
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

    store.projects.firstOrNull { it.id == exportingID }?.let { CardExport(it, store, exporter) { exportingID = "" } }
    if (unreadable.isNotEmpty()) OriginalRecordings(unreadable, store) { unreadable = "" }
    if (expired.isNotEmpty()) {
        val count = expired.size
        AlertDialog(
            onDismissRequest = { expired = emptyList() },
            title = { Text(stringResource(R.string.projects_expired_title)) },
            text = { Text(pluralStringResource(R.plurals.projects_expired_message, count, count)) },
            confirmButton = {
                TextButton(onClick = { store.eraseExpired(expired); expired = emptyList() }) {
                    Text(pluralStringResource(R.plurals.projects_expired_delete, count, count), color = RecordRed)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { expired = emptyList() }) { Text(stringResource(R.string.projects_not_now)) }
                    TextButton(onClick = {
                        expired = emptyList()
                        // Recently deleted is on this list, under the projects: its header is shown at the top.
                        val header = 1 + (if (store.projects.isEmpty()) 1 else 0) + store.projects.size + store.unavailable.size
                        scope.launch { list.scrollToItem(header) }
                    }) { Text(stringResource(R.string.projects_review)) }
                }
            },
        )
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
    store.iPhoneCheck?.let { check ->
        val project = store.projects.firstOrNull { it.id == check.projectID }
        val name = (project?.title ?: "").replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifEmpty { zipName } + ".zip"
        val save = { zippingForIPhone = check.projectID; store.iPhoneCheck = null; saveIPhoneZip.launch(name) }
        when {
            project == null -> LaunchedEffect(check) { store.iPhoneCheck = null }
            // What the iPhone cannot take stops the export, with what to change (docs/IOS-INTEROP.md, 7.4).
            check.blockers.isNotEmpty() -> AlertDialog(
                onDismissRequest = { store.iPhoneCheck = null },
                title = { Text(stringResource(R.string.projects_iphone_blocked_title)) },
                text = { IssueList(stringResource(R.string.projects_iphone_blocked_intro), check.blockers) },
                confirmButton = { TextButton(onClick = { store.iPhoneCheck = null }) { Text(stringResource(R.string.common_ok)) } },
            )
            // What changes on the way is accepted before the file is chosen.
            check.losses.isNotEmpty() -> AlertDialog(
                onDismissRequest = { store.iPhoneCheck = null },
                title = { Text(stringResource(R.string.projects_iphone_losses_title)) },
                text = { IssueList(stringResource(R.string.projects_iphone_losses_intro), check.losses) },
                confirmButton = { TextButton(onClick = save) { Text(stringResource(R.string.projects_iphone_continue)) } },
                dismissButton = { TextButton(onClick = { store.iPhoneCheck = null }) { Text(stringResource(R.string.common_cancel)) } },
            )
            else -> LaunchedEffect(check) { save() }
        }
    }
    store.youRecNotes?.let { notes ->
        // A project from the iPhone that changed on the way: what changed, one line each (docs/IOS-INTEROP.md, 3.5).
        AlertDialog(
            onDismissRequest = { store.youRecNotes = null },
            title = { Text(stringResource(R.string.yourec_opened_title)) },
            text = {
                IssueList(stringResource(R.string.yourec_opened_intro), notes)
            },
            confirmButton = { TextButton(onClick = { store.youRecNotes = null }) { Text(stringResource(R.string.common_ok)) } },
        )
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

/** An introduction and one line for each thing, scrolling when they are many. */
@Composable
private fun IssueList(intro: String, lines: List<String>) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(intro)
        for (line in lines) Text("• $line")
    }
}

/** Why a project cannot be read: a text of the core module, in the person's language when it is one we know. */
@Composable
internal fun unavailableReason(reason: String): String =
    ProjectException.Reason.entries.firstOrNull { it.message == reason }?.let { stringResource(it.label) } ?: reason

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
private fun ProjectCard(
    project: Project, store: ProjectStore, open: (Project, Int) -> Unit, rename: () -> Unit, addVideos: () -> Unit, export: () -> Unit, zip: () -> Unit,
    zipForIPhone: () -> Unit,
) {
    var originals by remember { mutableStateOf(false) }
    var unfinished by remember { mutableStateOf(false) }
    var repairing by remember { mutableStateOf(false) }
    if (repairing) RepairMediaDialog(project.id, store) { repairing = false }
    if (unfinished) UnfinishedRecordings(project, store) { unfinished = false }
    if (originals) OriginalRecordings(project.id, store) { originals = false }
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
        RepairMediaLine(project, store) { repairing = true }
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
            // As in the original, the export of the movie is one tap from the card, and needs something to export.
            val canExport = project.edit.duration > 0
            IconButton(onClick = export, enabled = canExport) {
                Icon(Icons.Outlined.IosShare, stringResource(R.string.projects_export_named, project.title),
                    Modifier.size(22.dp), tint = if (canExport) Color.White else Muted.copy(alpha = 0.5f))
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
                    if (project.sources.isNotEmpty()) DropdownMenuItem({ Text(stringResource(R.string.projects_zip_iphone)) }, { choose(zipForIPhone) }, leadingIcon = { Icon(Icons.Outlined.PhoneIphone, null) })
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

/**
 * The editor's export panel for [project], opened from its card as the original's export sheet: the format,
 * the export as it runs, an export that paused, and the finished movie. [close] is called once nothing of it
 * is on screen any more.
 */
@Composable
private fun CardExport(project: Project, store: ProjectStore, exporter: Exporter, close: () -> Unit) {
    var choosing by remember(project.id) { mutableStateOf(true) }
    // The export canvas as in the editor: the size the Crop tool fixed, else the first clip's shape.
    val firstShape by produceState<Pair<Int, Int>?>(null, project.edit.clips.firstOrNull()?.sourceID) {
        value = project.edit.clips.firstOrNull()?.let { clip -> project.sources.firstOrNull { it.id == clip.sourceID } }
            ?.let { withContext(Dispatchers.IO) { displaySize(store.file(project, it)) } }
    }
    val canvas = project.edit.outputSize?.let { it.width.roundToInt() to it.height.roundToInt() } ?: firstShape
    ExportPanel(project, canvas, store, exporter, choosing) { choosing = false }
    val ended = !choosing && exporter.status == null && exporter.result == null
    LaunchedEffect(ended) { if (ended) close() }
}
