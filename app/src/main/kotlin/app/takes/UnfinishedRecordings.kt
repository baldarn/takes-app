package app.takes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.takes.core.Project
import java.io.File

private val SheetGrey = Color(0xFF1C1C1E)
private val RowGrey = Color(0xFF2C2C2E)
private val Secondary = Color(0xFF98989F)

/**
 * "Unfinished recordings", as in the original: the recordings of a project that were interrupted and could
 * not be brought back when the app started. Each can be saved as a copy or deleted; the project and its edit
 * are not touched. A take that could be read is not here: it went back into the project at launch.
 */
@Composable
internal fun UnfinishedRecordings(project: Project, store: ProjectStore, close: () -> Unit) {
    val files = store.unfinishedRecordings(project)
    // The path of the file being saved or deleted: it outlives the system's file chooser.
    var saving by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<File?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { destination ->
        val file = store.unfinishedRecordings(project).firstOrNull { it.path == saving }
        if (destination != null && file != null) store.saveUnfinished(file, destination)
    }
    // Nothing left to show once the last one is deleted.
    LaunchedEffect(files.isEmpty()) { if (files.isEmpty()) close() }
    val title = stringResource(R.string.recorder_unfinished_title)
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(SheetGrey).safeDrawingPadding().semantics { paneTitle = title }) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = close) { Text(stringResource(R.string.common_done), color = SettingsCyan, fontWeight = FontWeight.SemiBold) }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Text(
                    stringResource(R.string.recorder_unfinished_preserved).uppercase(), color = Secondary, fontSize = 12.sp,
                    modifier = Modifier.padding(start = 12.dp, top = 18.dp, bottom = 6.dp).semantics { heading() },
                )
                Column(Modifier.fillMaxWidth().background(RowGrey, RoundedCornerShape(12.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    files.forEach { file ->
                        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 10.dp)) {
                            Text(file.name, color = Color.White, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                TextButton(onClick = { saving = file.path; save.launch(file.name) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                                    Text(stringResource(R.string.recorder_unfinished_save), color = SettingsCyan)
                                }
                                TextButton(onClick = { deleting = file }) { Text(stringResource(R.string.common_delete), color = RecordRed) }
                            }
                        }
                    }
                }
                Text(stringResource(R.string.recorder_unfinished_note), color = Secondary, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }
    deleting?.let { file ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.recorder_unfinished_delete_question)) },
            confirmButton = { TextButton(onClick = { store.removeUnfinished(file, project); deleting = null }) { Text(stringResource(R.string.recorder_unfinished_delete), color = RecordRed) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
