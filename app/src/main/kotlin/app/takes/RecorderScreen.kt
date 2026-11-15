package app.takes

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.takes.CaptureController.Phase
import app.takes.core.ZoomTransition
import kotlin.math.abs

private val Scrim = Color(0x99000000)

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** [openEditor] is the way back after a take recorded into the middle of an edit, and from its chip. */
@Composable
fun RecorderScreen(capture: CaptureController, store: ProjectStore, openEditor: () -> Unit) {
    val context = LocalContext.current
    var cameraGranted by remember { mutableStateOf(granted(context, Manifest.permission.CAMERA)) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        cameraGranted = granted(context, Manifest.permission.CAMERA)
    }
    LaunchedEffect(Unit) {
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filter { !granted(context, it) }
        if (missing.isNotEmpty()) request.launch(missing.toTypedArray())
    }
    if (!cameraGranted) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text(stringResource(R.string.recorder_camera_needed), color = Color.White)
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }) { Text(stringResource(R.string.recorder_open_settings)) }
        }
        return
    }

    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    val backToEditor by rememberUpdatedState(openEditor)
    val previewView = remember {
        // Show the whole recorded frame, never a crop of it.
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }
    LaunchedEffect(owner) { capture.attach(owner, previewView.surfaceProvider) }
    DisposableEffect(owner) {
        // In the background the take is paused and its segment saved, as in the original: the take, and a run
        // of takes recorded into the middle of an edit, are still there on return. Returning never resumes by itself.
        // The chosen microphone is a system-wide route: hold it only while the recorder is in front.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> capture.claimMicrophone()
                Lifecycle.Event.ON_STOP -> { capture.pause(); capture.releaseMicrophone() }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        view.keepScreenOn = true
        capture.onInsertionEnded = { backToEditor() }
        onDispose {
            owner.lifecycle.removeObserver(observer)
            view.keepScreenOn = false
            // Leaving by a tab ends a paused take too, and the tab chosen is where the person goes.
            capture.onInsertionEnded = null
            capture.detach()
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            detectTransformGestures { _, _, scale, _ -> capture.pinch(scale) }
        },
    ) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        Status(capture, store, Modifier.align(Alignment.TopCenter).statusBarsPadding())
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ProjectChip(capture, store, openEditor)
            ZoomRow(capture)
            CameraRow(capture)
            Controls(capture)
        }
        Notice(capture.message, Modifier.align(Alignment.Center)) { capture.message = null }
    }
}

@Composable
private fun Status(capture: CaptureController, store: ProjectStore, modifier: Modifier) {
    Column(modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val recording = capture.phase == Phase.Recording
        Row(
            Modifier.background(if (recording) RecordRed else Scrim, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when (capture.phase) {
                    Phase.Idle -> stringResource(R.string.recorder_ready)
                    Phase.Recording -> stringResource(R.string.recorder_rec, timeLabel(capture.elapsed))
                    Phase.Paused -> stringResource(R.string.recorder_paused, timeLabel(capture.elapsed))
                    Phase.Saving -> stringResource(R.string.recorder_saving)
                },
                color = Color.White, fontWeight = FontWeight.Bold,
            )
        }
        val micLevel = stringResource(R.string.recorder_mic_level)
        val project = store.selected
        Text(
            (project?.let { "${it.title} · ${timeLabel(it.capturedDuration)}" } ?: stringResource(R.string.recorder_new_project)) +
                " · " + if (capture.landscape) stringResource(R.string.recorder_landscape) else stringResource(R.string.recorder_portrait),
            color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp).background(Scrim, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
        )
        if (recording && capture.audioWarning == null) {
            LinearProgressIndicator(
                progress = { capture.level },
                modifier = Modifier.padding(top = 8.dp).width(160.dp).semantics { contentDescription = micLevel },
                color = if (capture.level > 0.95f) RecordRed else Color.White, trackColor = Scrim,
            )
        }
        capture.microphone?.takeIf { recording && capture.audioWarning == null }?.let {
            Text(if (capture.chosenMicrophone == null) stringResource(R.string.recorder_mic_automatic_using, it) else it,
                color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp).background(Scrim, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
        }
        if (capture.phase == Phase.Idle || capture.phase == Phase.Paused) MicrophoneChooser(capture)
        // What this phone's cameras offer, to copy and send; the development build only.
        if (capture.phase == Phase.Idle) CameraReportEntry(capture)
        capture.microphoneWarning?.let {
            Text(it, color = Color.White, fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp).background(RecordRed, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
        }
        capture.audioWarning?.let {
            Text(it, color = Color.White, fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp).background(RecordRed, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier) {
    Text(
        label, color = if (selected) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
        modifier = modifier.background(if (selected) Color.White else Scrim, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/**
 * Where the takes go, above the cameras as in the original. During a run started with "Record here" it names
 * the place and is the way back to the editor; otherwise it starts a new project once this one has footage.
 */
@Composable
private fun ProjectChip(capture: CaptureController, store: ProjectStore, openEditor: () -> Unit) {
    val project = store.selected ?: return
    val insertion = project.recordingInsertion
    if (insertion != null) {
        ProjectAction(stringResource(R.string.recorder_insert_back, timeLabel(insertion)), Icons.AutoMirrored.Filled.Undo, !capture.hasActiveTake) {
            // A paused take ends here, which closes the run and opens the editor by itself.
            if (capture.phase == Phase.Paused) capture.finishTake() else { store.recordAtEnd(project.id); openEditor() }
        }
    } else if (project.sources.isNotEmpty() && !capture.hasActiveTake) {
        ProjectAction(stringResource(R.string.recorder_new_project), Icons.Filled.Add, true) {
            // A paused take belongs to the project being left.
            capture.finishTake()
            store.create()
        }
    }
}

@Composable
private fun ProjectAction(label: String, icon: ImageVector, enabled: Boolean, action: () -> Unit) {
    Row(
        Modifier.padding(bottom = 2.dp).alpha(if (enabled) 1f else 0.4f).heightIn(min = 44.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = action).padding(vertical = 6.dp)
            .background(Color(0x47000000), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = Color.White)
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The input for the next segment. A running segment keeps its microphone, so this hides while recording. */
@Composable
private fun MicrophoneChooser(capture: CaptureController) {
    if (capture.microphones.isEmpty()) return
    var choosing by remember { mutableStateOf(false) }
    val chosen = capture.chosenMicrophone
    Box(Modifier.padding(top = 8.dp)) {
        Chip(stringResource(R.string.recorder_mic_chip, chosen?.label ?: stringResource(R.string.recorder_mic_automatic)), false,
            Modifier.clickable(onClickLabel = stringResource(R.string.recorder_mic_change), role = Role.Button) { choosing = true })
        DropdownMenu(choosing, { choosing = false }) {
            DropdownMenuItem({ Text(stringResource(R.string.recorder_mic_automatic)) }, { capture.chooseMicrophone(null); choosing = false })
            capture.microphones.forEach { choice ->
                DropdownMenuItem({ Text(choice.label) }, { capture.chooseMicrophone(choice.id); choosing = false })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoomRow(capture: CaptureController) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        var choosing by remember { mutableStateOf(false) }
        Box {
            Chip(zoomTimeLabel(capture.zoomDuration), false,
                Modifier.clickable(onClickLabel = stringResource(R.string.recorder_zoom_time_change), role = Role.Button) { choosing = true })
            DropdownMenu(choosing, { choosing = false }) {
                ZoomTransition.durationPresets.forEach { seconds ->
                    DropdownMenuItem({ Text(zoomTimeLabel(seconds)) }, { capture.chooseZoomDuration(seconds); choosing = false })
                }
            }
        }
        val zoomLabel = stringResource(R.string.recorder_zoom)
        val presets = ZoomTransition.presets(capture.zoomRange.start, capture.zoomRange.endInclusive)
        presets.forEach { preset ->
            // Tap uses the saved time; double tap always takes one second.
            Chip(ZoomTransition.zoomLabel(preset), abs(capture.zoom - preset) < 0.005,
                Modifier.combinedClickable(
                    role = Role.Button, onClickLabel = zoomLabel, onDoubleClick = { capture.zoomTo(preset, 1.0) },
                ) { capture.zoomTo(preset) })
        }
        if (presets.none { abs(capture.zoom - it) < 0.005 }) {
            Text(ZoomTransition.zoomLabel(capture.zoom), color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CameraRow(capture: CaptureController) {
    if (capture.cameras.size < 2) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        capture.cameras.forEach { choice ->
            Chip(choice.label, choice.id == capture.cameraID,
                Modifier.clickable(enabled = capture.phase != Phase.Saving, onClickLabel = stringResource(R.string.recorder_switch_camera), role = Role.Button) {
                    capture.switchCamera(choice.id)
                })
        }
    }
}

@Composable
private fun Controls(capture: CaptureController) {
    val phase = capture.phase
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
        Box(Modifier.width(96.dp), contentAlignment = Alignment.Center) {
            if (phase == Phase.Recording || phase == Phase.Paused) {
                TextButton(onClick = capture::finishTake) { Text(stringResource(R.string.recorder_stop), fontSize = 17.sp) }
            }
        }
        val pausing = phase == Phase.Recording
        val recordLabel = stringResource(when (phase) {
            Phase.Recording -> R.string.recorder_pause
            Phase.Paused -> R.string.recorder_resume
            else -> R.string.recorder_record
        })
        Box(
            Modifier.size(76.dp).border(4.dp, Color.White, CircleShape)
                .clickable(enabled = phase != Phase.Saving, role = Role.Button) { if (pausing) capture.pause() else capture.record() }
                .semantics {
                    role = Role.Button
                    contentDescription = recordLabel
                },
            contentAlignment = Alignment.Center,
        ) {
            if (pausing) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(2) { Box(Modifier.size(10.dp, 28.dp).background(Color.White, RoundedCornerShape(2.dp))) }
                }
            } else {
                Box(Modifier.size(58.dp).background(if (phase == Phase.Saving) Color.Gray else RecordRed, CircleShape))
            }
        }
        Box(Modifier.width(96.dp), contentAlignment = Alignment.Center) {
            // One tap marks the moment without interrupting the take.
            if (phase == Phase.Recording) {
                TextButton(onClick = capture::bookmark) { Text(if (capture.marks == 0) stringResource(R.string.recorder_mark) else stringResource(R.string.recorder_mark_count, capture.marks), fontSize = 17.sp) }
            }
        }
    }
}

/** The zoom transition time as the chip shows it; the core's own label is English only. */
@Composable
private fun zoomTimeLabel(seconds: Double): String =
    if (seconds == 0.0) stringResource(R.string.recorder_zoom_instant) else ZoomTransition.durationLabel(seconds)
