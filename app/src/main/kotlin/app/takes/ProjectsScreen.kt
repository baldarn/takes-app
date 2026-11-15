package app.takes

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.takes.core.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private val Card = Color(0xFF1B1D21)
private val Muted = Color(0xFFA0A4AB)

private fun dateLabel(time: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))

private fun play(context: Context, file: File, report: (String) -> Unit) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        report("No app on this device can play the recording.")
    }
}

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
    val pickVideos = { projectID: String ->
        importInto = projectID
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("Projects", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { pickVideos("") }) { Text("Import") }
                    Button(onClick = { store.create()?.let { open(it, 0) } }) { Text("New recording") }
                }
            }
        }
        if (store.projects.isEmpty()) {
            item { Text("Nothing here yet. Record something or import videos; each becomes a project you can continue later.", color = Muted) }
        }
        items(store.projects, key = { it.id }) { project ->
            ProjectCard(project, store, open, rename = { renaming = project }, addVideos = { pickVideos(project.id) },
                zip = { zipping = project.id; saveZip.launch(project.title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifEmpty { "Project" } + ".zip") })
        }
        items(store.unavailable.toList(), key = { "unavailable-" + it.first }) { (folder, reason) ->
            Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(16.dp)) {
                Text("Project that cannot be opened", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(reason, color = Muted, fontSize = 13.sp)
                Text(folder, color = Muted, fontSize = 11.sp)
            }
        }
        if (store.deleted.isNotEmpty()) {
            item { Text("Recently deleted", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp)) }
            items(store.deleted, key = { "deleted-" + it.project.id }) { entry ->
                Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(16.dp)) {
                    Text(entry.project.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(summary(entry.project) + (entry.date?.let { " · deleted " + dateLabel(it) } ?: ""), color = Muted, fontSize = 13.sp)
                    Row {
                        TextButton(onClick = { store.restore(entry.project) }) { Text("Restore") }
                        TextButton(onClick = { erasing = entry.project }) { Text("Delete permanently", color = RecordRed) }
                    }
                }
            }
        }
        item { TextButton(onClick = { showingCredits = true }) { Text("Credits and licenses", color = Muted) } }
    }

    if (showingCredits) CreditsScreen(report = { store.error = it }) { showingCredits = false }
    store.importing?.let { step ->
        AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text("Importing") }, text = { Text(step) })
    }
    renaming?.let { project ->
        var title by remember(project.id) { mutableStateOf(project.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename project") },
            text = { OutlinedTextField(title, { title = it.take(120) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { store.rename(project, title); renaming = null }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    erasing?.let { project ->
        AlertDialog(
            onDismissRequest = { erasing = null },
            title = { Text("Delete “${project.title}” permanently?") },
            text = { Text("Its ${project.recordings.size} recordings will be erased. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { store.erase(project); erasing = null }) { Text("Delete permanently", color = RecordRed) } },
            dismissButton = { TextButton(onClick = { erasing = null }) { Text("Cancel") } },
        )
    }
}

private fun summary(project: Project): String {
    val takes = project.recordings.size
    return if (takes == 0) "No recordings" else "$takes ${if (takes == 1) "recording" else "recordings"} · ${timeLabel(project.capturedDuration)}"
}

@Composable
private fun ProjectCard(project: Project, store: ProjectStore, open: (Project, Int) -> Unit, rename: () -> Unit, addVideos: () -> Unit, zip: () -> Unit) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(project.recordings.firstOrNull()?.let { store.file(project, it) })
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(project.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text(summary(project), color = Muted, fontSize = 13.sp)
                Text(dateLabel(project.updatedAt), color = Muted, fontSize = 12.sp)
            }
        }
        // Four actions on one line: tight padding, labels that never wrap, and a scroll as the last resort.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), Arrangement.SpaceBetween) {
            val tight = PaddingValues(horizontal = 8.dp)
            TextButton(onClick = { open(project, 0) }, contentPadding = tight) { Text("Continue recording", maxLines = 1, softWrap = false) }
            if (project.sources.isNotEmpty()) TextButton(onClick = { open(project, 2) }, contentPadding = tight) { Text("Edit", maxLines = 1, softWrap = false) }
            TextButton(onClick = rename, contentPadding = tight) { Text("Rename", maxLines = 1, softWrap = false) }
            TextButton(onClick = { store.delete(project) }, contentPadding = tight) { Text("Delete", color = RecordRed, maxLines = 1, softWrap = false) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), Arrangement.SpaceBetween) {
            val tight = PaddingValues(horizontal = 8.dp)
            if (project.sources.isNotEmpty()) {
                TextButton(onClick = { expanded = !expanded }, contentPadding = tight) { Text(if (expanded) "Hide originals" else "Originals", maxLines = 1, softWrap = false) }
            }
            TextButton(onClick = addVideos, contentPadding = tight) { Text("Add videos", maxLines = 1, softWrap = false) }
            if (project.sources.isNotEmpty()) TextButton(onClick = zip, contentPadding = tight) { Text("Zip project", maxLines = 1, softWrap = false) }
        }
        if (expanded) {
            project.sources.forEachIndexed { index, source ->
                Row(
                    Modifier.fillMaxWidth().clickable(onClickLabel = "Play") {
                        play(context, store.file(project, source)) { store.error = it }
                    }.padding(horizontal = 12.dp, vertical = 10.dp),
                    Arrangement.SpaceBetween,
                ) {
                    val switches = source.events.orEmpty().count { it.kind == "camera" }
                    Text("${index + 1}.  ${source.originalName ?: dateLabel(source.recordedAt)}" + if (switches > 0) "  ·  $switches camera ${if (switches == 1) "switch" else "switches"}" else "",
                        color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(timeLabel(source.duration), color = Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
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
