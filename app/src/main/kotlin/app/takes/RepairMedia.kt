package app.takes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takes.core.Project
import app.takes.core.SourceMedia
import app.takes.core.TrackConversion
import app.takes.core.TrackEditorEdits
import java.text.DateFormat
import java.util.Date

private val Muted = Color(0xFFA0A4AB)
private val Caution = Color(0xFFFF9F0A)

/**
 * The line on a project card when originals are missing, as the original's (ContentView.swift:667-674): "Repair
 * media" in orange when the timeline uses them, else a quiet note that they are only in the editing history.
 */
@Composable
fun RepairMediaLine(project: Project, store: ProjectStore, open: () -> Unit) {
    val missing = store.missingSources(project)
    if (missing.isEmpty()) return
    val onTimeline = !TrackEditorEdits.usingSources(project.edit, missing.mapTo(HashSet()) { it.id }).isEmpty
    TextButton(onClick = open, enabled = store.importing == null) {
        Icon(if (onTimeline) Icons.Outlined.Warning else Icons.Outlined.Info, null, Modifier.size(18.dp), tint = if (onTimeline) Caution else Muted)
        Text(
            if (onTimeline) stringResource(R.string.repair_card, missing.size) else pluralStringResource(R.plurals.repair_card_history, missing.size, missing.size),
            Modifier.padding(start = 6.dp), color = if (onTimeline) Caution else Muted, fontSize = 14.sp,
        )
    }
}

/**
 * "Repair media", as the original's sheet (ContentView.swift:437-492): each missing original with where the
 * timeline uses it and "Locate original…", which takes back only the unchanged file; and "Remove missing takes
 * from timeline", one undoable edit.
 */
@Composable
fun RepairMediaDialog(projectID: String, store: ProjectStore, close: () -> Unit) {
    val project = store.projects.firstOrNull { it.id == projectID } ?: return close()
    var locating by rememberSaveable { mutableStateOf("") }
    var unverified by remember { mutableStateOf<android.net.Uri?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val removed = stringResource(R.string.repair_removed)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val source = project.sources.firstOrNull { it.id == locating }
        if (uri != null && source != null) {
            if (source.sha256 == null) unverified = uri else store.relink(project, source, uri)
        }
    }
    val missing = store.missingSources(project)
    val missingIDs = missing.mapTo(HashSet()) { it.id }
    val timeline = remember(project.edit) { TrackConversion.timeline(project.edit) }
    val busy = store.importing != null
    // What a relink reports is said here, over the list, as the original's alert.
    LaunchedEffect(store.error) { store.error?.let { feedback = it; store.error = null } }
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text(stringResource(R.string.repair_title)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.repair_explanation), color = Muted, fontSize = 13.sp)
                for (source in missing) MissingSource(project, source, timeline, busy) {
                    locating = source.id
                    picker.launch(arrayOf(if (source.audioOnly == true) "audio/*" else "video/*"))
                }
                if (missing.isEmpty()) Text(stringResource(R.string.repair_all_found), Modifier.padding(top = 12.dp))
                if (!TrackEditorEdits.usingSources(project.edit, missingIDs).isEmpty) {
                    TextButton(onClick = { if (store.removeMissing(project)) feedback = removed }, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(R.string.repair_remove), color = RecordRed)
                    }
                    Text(stringResource(R.string.repair_remove_note), color = Muted, fontSize = 12.sp)
                }
                feedback?.let { Text(it, Modifier.padding(top = 8.dp), color = Muted) }
                store.importing?.let { Text(it, Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            if (busy && store.cancellable) TextButton(onClick = store::cancelOperation) { Text(stringResource(R.string.common_cancel)) }
            else TextButton(onClick = close, enabled = !busy) { Text(stringResource(R.string.common_done)) }
        },
    )
    unverified?.let { uri ->
        AlertDialog(
            onDismissRequest = { unverified = null },
            text = { Text(stringResource(R.string.repair_unverified_question)) },
            confirmButton = {
                TextButton(onClick = {
                    project.sources.firstOrNull { it.id == locating }?.let { store.relink(project, it, uri, allowUnverified = true) }
                    unverified = null
                }) { Text(stringResource(R.string.repair_use_original)) }
            },
            dismissButton = { TextButton(onClick = { unverified = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun MissingSource(project: Project, source: SourceMedia, timeline: app.takes.core.TrackTimeline, busy: Boolean, locate: () -> Unit) {
    var details by remember { mutableStateOf(false) }
    val number = project.sources.indexOfFirst { it.id == source.id } + 1
    val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(source.recordedAt))
    // Where the timeline uses it, as the original names the items: V for a picture, ♪ for a sound, by place.
    val uses = timeline.video.withIndex().filter { it.value.content.sourceID == source.id }.map { "V" + (it.index + 1) } +
        timeline.audio.withIndex().filter { it.value.sourceID == source.id }.map { "♪" + (it.index + 1) }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(stringResource(R.string.repair_take, number, date))
        Text(
            timeLabel(source.duration) + " · " + if (uses.isEmpty()) stringResource(R.string.repair_in_history) else stringResource(R.string.repair_on_timeline, uses.joinToString(", ")),
            color = Muted, fontSize = 12.sp,
        )
        TextButton(onClick = { details = !details }) { Text(stringResource(R.string.repair_file_details), fontSize = 13.sp) }
        if (details) Text(source.filename, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Muted)
        TextButton(onClick = locate, enabled = !busy) { Text(stringResource(R.string.repair_locate)) }
    }
}
