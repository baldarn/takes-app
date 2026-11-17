package app.takes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.takes.core.Project
import app.takes.core.RecoveredProject
import app.takes.core.SourceMedia
import app.takes.core.TimelineEditing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private val SheetGrey = Color(0xFF1C1C1E)
private val RowGrey = Color(0xFF2C2C2E)
private val Secondary = Color(0xFF98989F)

/** The type the share sheet and the gallery are told, from the file's extension. */
internal fun mediaType(file: File, audioOnly: Boolean): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: if (audioOnly) "audio/*" else "video/*"

/** Offers [files] to the apps that can take them, through the system share sheet. */
private fun share(context: Context, files: List<Pair<File, Boolean>>) {
    val uris = ArrayList<Uri>(files.map { (file, _) -> FileProvider.getUriForFile(context, "${context.packageName}.files", file) })
    val types = files.map { (file, audio) -> mediaType(file, audio) }.distinct()
    val type = types.singleOrNull() ?: if (files.all { it.second }) "audio/*" else if (files.none { it.second }) "video/*" else "*/*"
    val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    intent.setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, null))
}

/** Seconds with tenths, for the ends of a range. */
private fun rangeLabel(seconds: Double): String {
    val tenths = Math.round(seconds.coerceAtLeast(0.0) * 10)
    return "%d:%02d.%d".format(tenths / 600, tenths / 10 % 60, tenths % 10)
}

private fun takeTitle(source: SourceMedia) =
    source.originalName ?: DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(source.recordedAt))

/** What an original is called in the list: a take's name or date, else its file's name. */
private fun itemTitle(item: RecoveredProject.Original) = item.source?.let(::takeTitle) ?: item.title

/**
 * The project's original recordings and sound files, as the original's Original recordings
 * (`YouRec/OriginalRecordingsView.swift:15-108`): each one can be reviewed in the app, shared or
 * saved to the gallery, and all at once, and a separate project can be made from them. Besides the
 * sources, the media of the folder the project does not list and what was set aside are listed.
 * [projectID] names the project's folder: for a project that cannot be read, the media of its folder
 * are listed, with why it cannot be read and a retry (`YouRec/OriginalRecordingsView.swift:19-22`).
 */
@Composable
internal fun OriginalRecordings(projectID: String, store: ProjectStore, close: () -> Unit) {
    val context = LocalContext.current
    var reviewing by remember { mutableStateOf<RecoveredProject.Original?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var copying by remember { mutableStateOf(false) }
    val project = store.projects.firstOrNull { it.id == projectID }
    val items = store.originals(projectID, project)
    val copySize = remember(items) { runCatching { store.recoveryEstimate(items.map { it.file }.filter { it.isFile }) }.getOrNull() }
    val title = stringResource(R.string.originals_title)
    // While the copy runs the sheet stays, as the original's does.
    val leave = { if (!copying) close() }
    Dialog(onDismissRequest = leave, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(SheetGrey).safeDrawingPadding().semantics { paneTitle = title }) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = leave, enabled = !copying) { Text(stringResource(R.string.common_done), color = SettingsCyan, fontWeight = FontWeight.SemiBold) }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                // The project's recovery notice, as the original's (OriginalRecordingsView.swift:18).
                store.recoveryMessages[projectID]?.let { Text(it, color = Color.White, modifier = Modifier.padding(12.dp)) }
                if (project == null) store.unavailable[projectID]?.let { reason ->
                    Text(unavailableReason(reason), color = Secondary, modifier = Modifier.padding(12.dp))
                    val again = stringResource(R.string.originals_available_again)
                    TextButton(onClick = { if (store.retryUnavailable(projectID)) message = again }) {
                        Text(stringResource(R.string.originals_retry_open), color = SettingsCyan)
                    }
                }
                message?.let { Text(it, color = Color.White, modifier = Modifier.padding(12.dp)) }
                if (items.isEmpty()) Text(stringResource(R.string.originals_none), color = Secondary, modifier = Modifier.padding(12.dp))
                Column(Modifier.fillMaxWidth().padding(top = 12.dp).background(RowGrey, RoundedCornerShape(12.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val reviewLabel = stringResource(R.string.originals_review)
                    items.forEachIndexed { index, item ->
                        val source = item.source
                        val name = itemTitle(item)
                        Row(Modifier.fillMaxWidth().clickable(onClickLabel = reviewLabel) { reviewing = item }.padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${index + 1}.  " + name, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (source != null) {
                                    val switches = source.events.orEmpty().count { it.kind == "camera" }
                                    Text(stringResource(if (source.audioOnly == true) R.string.originals_sound_length else R.string.originals_recorded, timeLabel(source.duration)) +
                                        if (switches > 0) " · " + pluralStringResource(R.plurals.projects_camera_switches, switches, switches) else "", color = Secondary, fontSize = 12.sp)
                                }
                            }
                            val shareLabel = stringResource(R.string.originals_share_one, name)
                            IconButton(onClick = { share(context, listOf(item.file to (source?.audioOnly == true))) }, enabled = item.file.isFile) {
                                Icon(Icons.Outlined.Share, shareLabel, tint = SettingsCyan)
                            }
                        }
                    }
                }
                if (items.size > 1) {
                    val present = items.map { it.file to (it.source?.audioOnly == true) }.filter { it.first.isFile }
                    TextButton(onClick = { share(context, present) }, enabled = present.isNotEmpty()) { Text(stringResource(R.string.originals_share_all), color = SettingsCyan) }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) TextButton(onClick = { store.saveOriginals(present) }, enabled = present.isNotEmpty()) {
                        Text(stringResource(R.string.originals_save_all), color = SettingsCyan)
                    }
                }
                Text(stringResource(R.string.originals_note), color = Secondary, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                if (items.isNotEmpty()) {
                    // YouRec/OriginalRecordingsView.swift:60-88: a separate project from the playable originals.
                    val tint = if (copying) Secondary else SettingsCyan
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp).background(RowGrey, RoundedCornerShape(12.dp)).padding(horizontal = 4.dp)) {
                        TextButton(onClick = {
                            copying = true
                            message = null
                            store.recover(projectID, project) { report -> message = report; copying = false }
                            // Another piece of work was running, so nothing started.
                            if (store.importing == null) copying = false
                        }, enabled = !copying) {
                            Icon(Icons.Outlined.CreateNewFolder, null, tint = tint)
                            Text(stringResource(if (copying) R.string.originals_creating_recovered else R.string.originals_create_recovered), color = tint, modifier = Modifier.padding(start = 8.dp))
                        }
                        if (copying) TextButton(onClick = store::cancelOperation, enabled = !store.cancelling) {
                            Text(stringResource(R.string.originals_cancel_copy), color = SettingsCyan)
                        }
                    }
                    Text(stringResource(R.string.originals_recover_footer), color = Secondary, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    copySize?.let {
                        Text(stringResource(R.string.originals_copy_space, Formatter.formatShortFileSize(context, it)), color = Secondary, fontSize = 12.sp,
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 16.dp))
                    }
                }
            }
        }
    }
    reviewing?.let { item -> OriginalReview(projectID, project, item, store) { reviewing = null } }
}

/**
 * One original, played in the app, with what happened while it was recorded, as the original's
 * review (`YouRec/OriginalRecordingsView.swift:110-199`): a range of a take, the whole take at first,
 * can be put back at the end of the timeline as one Undo step; one that does not read whole, or that
 * the project does not list, can be recovered into a new project.
 */
@Composable
private fun OriginalReview(projectID: String, project: Project?, item: RecoveredProject.Original, store: ProjectStore, close: () -> Unit) {
    val context = LocalContext.current
    val file = item.file
    val source = item.source
    // Read off the main thread, as the original inspects it; null until then, and when it does not read whole.
    var media by remember(file) { mutableStateOf<RecordingSalvage.Media?>(null) }
    var issue by remember(file) { mutableStateOf<String?>(null) }
    var saving by remember(file) { mutableStateOf(false) }
    val unreadable = stringResource(R.string.originals_unreadable)
    val player = remember(file) { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(file) {
        val inspected = withContext(Dispatchers.IO) { runCatching { RecordingSalvage.inspect(file) }.getOrNull() }
        media = inspected
        if (inspected == null) {
            issue = unreadable
        } else {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
        }
    }
    val repairSize = remember(file) { if (file.isFile) store.recoveryEstimate(listOf(file)) else null }
    val duration = source?.duration ?: 0.0
    var start by remember(file) { mutableDoubleStateOf(0.0) }
    var end by remember(file) { mutableDoubleStateOf(duration) }
    val title = stringResource(R.string.originals_review)
    val leave = { if (!saving) close() }
    Dialog(onDismissRequest = leave, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(SheetGrey).safeDrawingPadding().semantics { paneTitle = title }) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = leave, enabled = !saving) { Text(stringResource(R.string.common_done), color = SettingsCyan, fontWeight = FontWeight.SemiBold) }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!file.isFile) Text(stringResource(R.string.originals_missing), color = Secondary)
                else if (media != null) AndroidView(
                    { PlayerView(it).apply { this.player = player } },
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black),
                )
                Text(itemTitle(item), color = Color.White, fontWeight = FontWeight.SemiBold)
                if (file.isFile) issue?.let { Text(it, color = Secondary) }
                if (source != null) {
                    Text(stringResource(if (source.audioOnly == true) R.string.originals_sound_length else R.string.originals_recorded, timeLabel(duration)), color = Secondary)
                    // What the recorder noted that matters when looking for a problem: camera and microphone changes.
                    source.events.orEmpty().filter { it.kind != Project.BOOKMARK_EVENT && it.detail.isNotBlank() }.forEach { event ->
                        Text(timeLabel(event.time) + " · " + event.detail, color = Secondary, fontSize = 13.sp)
                    }
                }
                if (project != null && source != null && media != null && source.audioOnly != true && duration > TimelineEditing.MINIMUM_CLIP) {
                    val startLabel = stringResource(R.string.originals_range_start)
                    val endLabel = stringResource(R.string.originals_range_end)
                    Text(startLabel + "  " + rangeLabel(start), color = Color.White)
                    Slider((start / duration).toFloat(), { value ->
                        start = (value * duration).coerceIn(0.0, end - TimelineEditing.MINIMUM_CLIP)
                        player.seekTo((start * 1000).toLong())
                    }, Modifier.semantics { contentDescription = startLabel })
                    Text(endLabel + "  " + rangeLabel(end), color = Color.White)
                    Slider((end / duration).toFloat(), { value ->
                        end = (value * duration).coerceIn(start + TimelineEditing.MINIMUM_CLIP, duration)
                        player.seekTo(((end - TimelineEditing.MINIMUM_CLIP).coerceAtLeast(start) * 1000).toLong())
                    }, Modifier.semantics { contentDescription = endLabel })
                    Button(onClick = { if (store.addOriginal(project.id, source, start, end)) close() }, enabled = !saving) {
                        Text(stringResource(R.string.originals_add_range))
                    }
                }
                // YouRec/OriginalRecordingsView.swift:167-180: one that does not read whole, or is not one of the project's.
                if (file.isFile && (media == null || source == null)) {
                    repairSize?.let { Text(stringResource(R.string.originals_repair_space, Formatter.formatShortFileSize(context, it)), color = Secondary, fontSize = 12.sp) }
                    TextButton(onClick = {
                        saving = true
                        store.recover(projectID, project, file) { report -> issue = report; saving = false }
                        if (store.importing == null) saving = false
                    }, enabled = !saving) {
                        Text(stringResource(if (saving) R.string.originals_recovering else R.string.originals_recover_one), color = if (saving) Secondary else SettingsCyan)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { share(context, listOf(file to (source?.audioOnly == true))) }, enabled = file.isFile) {
                        Text(stringResource(R.string.originals_share), color = SettingsCyan)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) TextButton(onClick = { store.saveOriginals(listOf(file to (source?.audioOnly == true))) }, enabled = file.isFile) {
                        Text(stringResource(R.string.originals_save), color = SettingsCyan)
                    }
                }
            }
        }
    }
}
