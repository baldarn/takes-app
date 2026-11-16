package app.takes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import app.takes.core.SourceMedia
import app.takes.core.TimelineEditing
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

/**
 * The project's original recordings and sound files, as the original's Original recordings
 * (`YouRec/OriginalRecordingsView.swift:32-58`): each one can be reviewed in the app, shared or
 * saved to the gallery, and all at once.
 */
@Composable
internal fun OriginalRecordings(project: Project, store: ProjectStore, close: () -> Unit) {
    val context = LocalContext.current
    var reviewing by remember { mutableStateOf<SourceMedia?>(null) }
    val title = stringResource(R.string.originals_title)
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(SheetGrey).safeDrawingPadding().semantics { paneTitle = title }) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = close) { Text(stringResource(R.string.common_done), color = SettingsCyan, fontWeight = FontWeight.SemiBold) }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                val sources = project.sources
                if (sources.isEmpty()) Text(stringResource(R.string.originals_none), color = Secondary, modifier = Modifier.padding(12.dp))
                Column(Modifier.fillMaxWidth().padding(top = 12.dp).background(RowGrey, RoundedCornerShape(12.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val reviewLabel = stringResource(R.string.originals_review)
                    sources.forEachIndexed { index, source ->
                        val file = store.file(project, source)
                        Row(Modifier.fillMaxWidth().clickable(onClickLabel = reviewLabel) { reviewing = source }.padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${index + 1}.  " + takeTitle(source), color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val switches = source.events.orEmpty().count { it.kind == "camera" }
                                Text(stringResource(if (source.audioOnly == true) R.string.originals_sound_length else R.string.originals_recorded, timeLabel(source.duration)) +
                                    if (switches > 0) " · " + pluralStringResource(R.plurals.projects_camera_switches, switches, switches) else "", color = Secondary, fontSize = 12.sp)
                            }
                            val shareLabel = stringResource(R.string.originals_share_one, takeTitle(source))
                            IconButton(onClick = { share(context, listOf(file to (source.audioOnly == true))) }, enabled = file.isFile) {
                                Icon(Icons.Outlined.Share, shareLabel, tint = SettingsCyan)
                            }
                        }
                    }
                }
                if (sources.size > 1) {
                    val present = sources.map { store.file(project, it) to (it.audioOnly == true) }.filter { it.first.isFile }
                    TextButton(onClick = { share(context, present) }, enabled = present.isNotEmpty()) { Text(stringResource(R.string.originals_share_all), color = SettingsCyan) }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) TextButton(onClick = { store.saveOriginals(project, sources) }) {
                        Text(stringResource(R.string.originals_save_all), color = SettingsCyan)
                    }
                }
                Text(stringResource(R.string.originals_note), color = Secondary, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }
    reviewing?.let { source -> OriginalReview(project, source, store) { reviewing = null } }
}

/**
 * One original, played in the app, with what happened while it was recorded, as the original's
 * review (`YouRec/OriginalRecordingsView.swift:110-199`): a range of it, the whole take at first,
 * can be put back at the end of the timeline as one Undo step.
 */
@Composable
private fun OriginalReview(project: Project, source: SourceMedia, store: ProjectStore, close: () -> Unit) {
    val context = LocalContext.current
    val file = store.file(project, source)
    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply { if (file.isFile) { setMediaItem(MediaItem.fromUri(Uri.fromFile(file))); prepare() } }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    val duration = source.duration
    var start by remember(source.id) { mutableDoubleStateOf(0.0) }
    var end by remember(source.id) { mutableDoubleStateOf(duration) }
    val title = stringResource(R.string.originals_review)
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(SheetGrey).safeDrawingPadding().semantics { paneTitle = title }) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = close) { Text(stringResource(R.string.common_done), color = SettingsCyan, fontWeight = FontWeight.SemiBold) }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (file.isFile) AndroidView(
                    { PlayerView(it).apply { this.player = player } },
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black),
                ) else Text(stringResource(R.string.originals_missing), color = Secondary)
                Text(takeTitle(source), color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(stringResource(if (source.audioOnly == true) R.string.originals_sound_length else R.string.originals_recorded, timeLabel(duration)), color = Secondary)
                // What the recorder noted that matters when looking for a problem: camera and microphone changes.
                source.events.orEmpty().filter { it.kind != Project.BOOKMARK_EVENT && it.detail.isNotBlank() }.forEach { event ->
                    Text(timeLabel(event.time) + " · " + event.detail, color = Secondary, fontSize = 13.sp)
                }
                if (source.audioOnly != true && file.isFile && duration > TimelineEditing.MINIMUM_CLIP) {
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
                    Button(onClick = { if (store.addOriginal(project.id, source, start, end)) close() }) {
                        Text(stringResource(R.string.originals_add_range))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { share(context, listOf(file to (source.audioOnly == true))) }, enabled = file.isFile) {
                        Text(stringResource(R.string.originals_share), color = SettingsCyan)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) TextButton(onClick = { store.saveOriginals(project, listOf(source)) }, enabled = file.isFile) {
                        Text(stringResource(R.string.originals_save), color = SettingsCyan)
                    }
                }
            }
        }
    }
}
