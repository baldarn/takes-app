package app.takes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takes.speech.SpeechModel
import app.takes.speech.SpeechModelCorrupt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Offers the model a speech restoration stage needs, when it is added or selected without it: what it is and
 * how large, Download, its progress while it arrives, and Cancel, which stops it and keeps what has arrived,
 * so the next Download goes on from there, as the subtitles' speech model is offered (SubtitlesPanel.kt). On a
 * connection that counts the data it carries, as mobile data does, it says so before the download.
 * [installed] is called once every file is in place and checked.
 */
@Composable
internal fun RestorationDownload(model: SpeechModel, stage: String, installed: () -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var present by remember { mutableLongStateOf(RestorationModelStore.present(model)) }
    var failure by remember { mutableStateOf<String?>(null) }
    val metered = remember { metered(context) }
    val total = SubtitleModel.megabytes(model.bytes)
    val left = SubtitleModel.megabytes(model.bytes - present)
    val failed = stringResource(R.string.subtitles_download_failed)
    val corrupt = stringResource(R.string.subtitles_download_corrupt)
    val noRoom = stringResource(R.string.enhance_model_no_room, left, model.name)
    val unknown = stringResource(R.string.common_unknown_error)

    fun stop() {
        job?.cancel()
        job = null
    }

    fun download() {
        failure = null
        if (!RestorationModelStore.hasRoom(model)) { failure = noRoom; return }
        job = scope.launch {
            try {
                RestorationModelStore.download(model) { present = it }
                job = null
                installed()
            } catch (stopped: CancellationException) {
                throw stopped
            } catch (_: SpeechModelCorrupt) {
                failure = corrupt
            } catch (problem: IOException) {
                failure = failed.format(problem.message ?: unknown)
            } finally {
                job = null
                present = RestorationModelStore.present(model)
            }
        }
    }

    AlertDialog(
        onDismissRequest = { stop(); dismiss() },
        title = { Text(stringResource(R.string.enhance_model_download_title, model.name)) },
        text = {
            Column {
                Text(stringResource(R.string.enhance_model_download_message, stage, model.name, total))
                if (metered && job == null) Text(stringResource(R.string.subtitles_metered_message, left), modifier = Modifier.padding(top = 8.dp))
                if (job != null) {
                    LinearProgressIndicator(progress = { present.toFloat() / model.bytes }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                    Text(
                        stringResource(R.string.subtitles_downloading, model.name, SubtitleModel.megabytes(present), total),
                        fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                }
                failure?.let { Text(it, color = Color(0xFFFF9F0A), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            if (job == null) {
                TextButton(onClick = ::download) {
                    Text(if (present > 0) stringResource(R.string.subtitles_download_continue, left) else stringResource(R.string.enhance_model_download, total))
                }
            }
        },
        dismissButton = { TextButton(onClick = { stop(); dismiss() }) { Text(stringResource(R.string.common_cancel)) } },
    )
}
