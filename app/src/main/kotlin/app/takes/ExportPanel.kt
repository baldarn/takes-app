package app.takes

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.takes.core.ExportProgress
import app.takes.core.ExportReceipt
import app.takes.core.ExportSettings
import app.takes.core.Project
import java.io.File

private val Muted = Color(0xFFA0A4AB)
private val Warning = Color(0xFFFF9F0A)
private val Done = Color(0xFF30D158)

/** What the status line of an export says, in the person's language. */
private val ExportProgress.Label.caption: Int
    get() = when (this) {
        ExportProgress.Label.Opening -> R.string.export_step_opening
        ExportProgress.Label.AudioEffects -> R.string.export_step_audio_effects
        ExportProgress.Label.Timeline -> R.string.export_step_timeline
        ExportProgress.Label.AudioPeak -> R.string.export_step_audio_peak
        ExportProgress.Label.Audio -> R.string.export_step_audio
        ExportProgress.Label.Picture -> R.string.export_step_picture
        ExportProgress.Label.AssembleVideo -> R.string.export_step_assemble_video
        ExportProgress.Label.AssembleMovie -> R.string.export_step_assemble_movie
        ExportProgress.Label.MovieIndex -> R.string.export_step_movie_index
        ExportProgress.Label.SourceFiles -> R.string.export_step_source_files
        ExportProgress.Label.Verifying -> R.string.export_step_verifying
        ExportProgress.Label.Saving -> R.string.export_step_saving
        ExportProgress.Label.Complete -> R.string.export_step_complete
        ExportProgress.Label.Reopening -> R.string.export_step_reopening
        ExportProgress.Label.Rebuilding -> R.string.export_step_rebuilding
    }

/** The three stretches of the bar, in the original's colors: preparing, encoding, finishing. */
private val ExportProgress.Phase.tint: Color
    get() = when (this) {
        ExportProgress.Phase.Preparing -> Color(0xFF0A84FF)
        ExportProgress.Phase.Encoding -> Color(0xFF40C8E0)
        ExportProgress.Phase.Finishing -> Color(0xFF5E5CE6)
    }

/**
 * Everything the editor shows about exporting [project]: the choice of the format, the export as it runs
 * with the step it is at, an export that paused and can be resumed, and the finished movie.
 *
 * [choosing] is true while the person has asked to export and [dismiss] ends that. [canvas] is the shape of
 * the first clip.
 */
@Composable
fun ExportPanel(project: Project, canvas: Pair<Int, Int>?, store: ProjectStore, exporter: Exporter, choosing: Boolean, dismiss: () -> Unit) {
    val context = LocalContext.current
    // An export that was interrupted is looked for when the editor opens and after every change: what was saved
    // for another edit or other settings is removed, as in the original.
    LaunchedEffect(project.id, project.updatedAt, project.historyCursor, project.exportSettings) { exporter.restore(project) }
    val status = exporter.status
    val paused = exporter.paused?.takeIf { it.projectID == project.id }
    // A paused export shows itself once; put aside, it comes back with the Export button.
    var aside by remember { mutableStateOf<PausedExport?>(null) }
    when {
        status != null -> ExportProgressDialog(status, exporter::cancel)
        paused != null && (choosing || aside !== paused) -> ExportPausedDialog(
            paused,
            resume = { dismiss(); exporter.start(project) },
            discard = { dismiss(); exporter.cancel() },
            close = { aside = paused; dismiss() },
        )
        // Not over the movie that has just been made.
        choosing && exporter.result == null -> ExportSettingsDialog(
            project, exporter, canvas, context, store,
            start = { settings ->
                dismiss()
                store.setExportSettings(project.id, settings)
                exporter.start(project.copy(exportSettings = settings))
            },
            dismiss = dismiss,
        )
    }
    if (status == null) exporter.result?.let { ExportedDialog(it, exporter, context, store) }
}

/**
 * The choices are remembered in the project. [canvas] is the shape of the first clip.
 *
 * While the edit and the choices are those of a movie that is already exported, that movie is offered, to
 * play, share and save, in place of a new export; a movie made from an earlier edit or with other choices is
 * offered as the previous export beside the new one.
 */
@Composable
private fun ExportSettingsDialog(
    project: Project, exporter: Exporter, canvas: Pair<Int, Int>?, context: Context, store: ProjectStore, start: (ExportSettings) -> Unit, dismiss: () -> Unit,
) {
    val report = { message: String -> store.error = message }
    var settings by remember { mutableStateOf(project.exportSettings) }
    val exported by produceState<ExportedMovie?>(null, project.id, project.updatedAt, project.historyCursor, settings) {
        value = exporter.exported(project.copy(exportSettings = settings))
    }
    val made = exported?.takeIf { it.current }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.export_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Choices(stringResource(R.string.export_size), listOf(
                    stringResource(R.string.export_size_original) to ExportSettings.Resolution.Project, "720p" to ExportSettings.Resolution.HD,
                    "1080p" to ExportSettings.Resolution.FullHD, "4K" to ExportSettings.Resolution.UltraHD,
                ), settings.resolution) { settings = settings.copy(resolution = it) }
                canvas?.let { settings.resolution.size(it.first, it.second) }?.let { (width, height) ->
                    Text(stringResource(R.string.export_pixels, width, height), color = Muted, fontSize = 13.sp)
                }
                // Only the limits the exporter can really honor.
                Choices(stringResource(R.string.export_frame_rate), listOf(stringResource(R.string.export_up_to, 30) to 30, stringResource(R.string.export_up_to, 60) to 60), settings.frameRate) { settings = settings.copy(frameRate = it) }
                Text(stringResource(R.string.export_frame_rate_note), color = Muted, fontSize = 13.sp)
                Choices(stringResource(R.string.export_format), listOf("H.264" to ExportSettings.Codec.H264, stringResource(R.string.export_h265_smaller) to ExportSettings.Codec.HEVC), settings.codec) {
                    settings = settings.copy(codec = it)
                }
                val movie = exported
                if (made != null) {
                    Text(stringResource(R.string.export_step_complete), color = Done, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
                    made.warning?.let { ExportAudioWarning(it) }
                    ExportedActions(made.file, exporter, context, report, R.string.export_share_latest, R.string.export_save_to_gallery)
                } else if (movie != null) {
                    Text(stringResource(R.string.export_previous_stays), fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp))
                    Text(stringResource(R.string.export_previous_title), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                    Text(stringResource(R.string.export_previous_note), color = Muted, fontSize = 13.sp)
                    ExportedActions(movie.file, exporter, context, report, R.string.export_share_previous, R.string.export_save_previous)
                }
                DialogNotice(store)
            }
        },
        confirmButton = {
            if (made != null) TextButton(onClick = dismiss) { Text(stringResource(R.string.common_done)) }
            else Button(onClick = { start(settings) }) { Text(stringResource(R.string.editor_export)) }
        },
        dismissButton = { if (made == null) TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** What can be done with the movie in [file]: playing it, sharing it, saving it to the gallery. */
@Composable
private fun ExportedActions(file: File, exporter: Exporter, context: Context, report: (String) -> Unit, share: Int, save: Int) {
    val uri = remember(file) { runCatching { FileProvider.getUriForFile(context, "${context.packageName}.files", file) }.getOrNull() } ?: return
    fun open(intent: Intent) = try {
        context.startActivity(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (_: Exception) {
        report(context.getString(R.string.export_open_failed))
    }
    TextButton(onClick = { open(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")) }) { Text(stringResource(R.string.common_play)) }
    TextButton(onClick = {
        open(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri), null))
    }) { Text(stringResource(share)) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        TextButton(onClick = { exporter.saveToGallery(file) }) { Text(stringResource(save)) }
    }
}

/**
 * What the app has to say while one of these dialogs is open, said in the dialog: that the movie is in the
 * gallery, or that it could not be saved or opened. The app's own notice is drawn in the screen under the
 * dialog, where the message would wait unseen and turn up later over whatever screen came next. The original
 * says "Saved to Photos" in the panel, where the button was.
 */
@Composable
private fun DialogNotice(store: ProjectStore) {
    var said by remember { mutableStateOf<String?>(null) }
    val message = store.error
    LaunchedEffect(message) { if (message != null) { said = message; store.error = null } }
    val text = message ?: said ?: return
    Text(
        text, color = if (text == stringResource(R.string.export_gallery_saved)) Done else Warning, fontSize = 13.sp,
        modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** The export as it runs: the step it is at, in words, and one bar for all of it, which never moves back. */
@Composable
private fun ExportProgressDialog(status: ExportProgress, cancel: () -> Unit) {
    // The device stays awake while a movie is made: an export pauses when the screen goes dark.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val title = stringResource(status.label.caption)
    val percent = stringResource(R.string.export_percent, status.percent)
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.export_running_title)) },
        text = {
            Column {
                Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, Arrangement.SpaceBetween) {
                    Text(title, modifier = Modifier.weight(1f))
                    Text(percent, color = Muted, modifier = Modifier.padding(start = 8.dp))
                }
                LinearProgressIndicator(
                    progress = { status.fraction.toFloat() }, color = status.step.phase.tint,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).semantics { contentDescription = title; stateDescription = percent },
                )
            }
        },
        confirmButton = { TextButton(onClick = cancel) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** An export that stopped when the app left the front, or when the device was full: it goes on from what is saved, or is given up. */
@Composable
private fun ExportPausedDialog(paused: PausedExport, resume: () -> Unit, discard: () -> Unit, close: () -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.export_paused_title)) },
        text = {
            Column {
                Text(stringResource(if (paused.reason != null) R.string.export_paused_space else R.string.export_paused_background))
                if (paused.savedSeconds > 0) {
                    Text(stringResource(R.string.export_paused_saved, timeLabel(paused.savedSeconds)), modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = { Button(onClick = resume) { Text(stringResource(R.string.export_resume)) } },
        dismissButton = { TextButton(onClick = discard) { Text(stringResource(R.string.export_discard)) } },
    )
}

/** Said with a movie whose sound goes over full scale, as the original says it. */
@Composable
private fun ExportAudioWarning(warning: ExportReceipt.AudioWarning) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).background(Warning.copy(alpha = 0.1f), RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text(stringResource(R.string.export_audio_warning_title), color = Warning, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(stringResource(R.string.export_audio_warning, warning.decibels), color = Warning, fontSize = 13.sp)
    }
}

/** The finished movie, to play, share or save. */
@Composable
private fun ExportedDialog(file: File, exporter: Exporter, context: Context, store: ProjectStore) {
    AlertDialog(
        onDismissRequest = { exporter.result = null },
        title = { Text(stringResource(R.string.export_done_title)) },
        text = {
            Column {
                Text(file.name, color = Muted, fontSize = 13.sp)
                exporter.warning?.let { ExportAudioWarning(it) }
                ExportedActions(file, exporter, context, { message -> store.error = message }, R.string.export_share, R.string.export_save_to_gallery)
                DialogNotice(store)
            }
        },
        confirmButton = { TextButton(onClick = { exporter.result = null }) { Text(stringResource(R.string.common_done)) } },
    )
}
