package app.takes

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.RoundedCorner
import androidx.activity.compose.LocalActivity
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.takes.CaptureController.Phase
import app.takes.core.Project
import app.takes.core.ZoomTransition
import kotlinx.coroutines.delay
import kotlin.math.abs

private val Scrim = Color(0x99000000)
/** The plate under a control that sits on the picture, as in the original. */
private val ControlPlate = Color(0x85000000)
private val PausedYellow = Color(0xFFFFD60A)

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
    val activity = LocalActivity.current
    val backToEditor by rememberUpdatedState(openEditor)
    val previewView = remember {
        // Show the whole recorded frame, never a crop of it.
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }
    var settingsOpen by remember { mutableStateOf(false) }
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
        // Full screen, as in the original: no status bar while the recorder shows. A swipe brings it back for a moment.
        val bars = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.statusBars())
        capture.onInsertionEnded = { backToEditor() }
        onDispose {
            owner.lifecycle.removeObserver(observer)
            view.keepScreenOn = false
            bars?.show(WindowInsetsCompat.Type.statusBars())
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
        // Clear of the camera cutout, which stays when the status bar is hidden.
        Status(capture, store, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)))
        SettingsButton(Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))) { settingsOpen = true }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ProjectChip(capture, store, openEditor)
            ZoomRow(capture)
            CameraRow(capture)
            Controls(capture)
        }
        TakeFrame(capture.phase, Modifier.matchParentSize())
        if (settingsOpen) CameraSettings(capture, store.selected) { settingsOpen = false }
        Notice(capture.message, Modifier.align(Alignment.Center)) { capture.message = null }
    }
}

/** The way into "Camera settings", at the end of the top bar as in the original. */
@Composable
private fun SettingsButton(modifier: Modifier, open: () -> Unit) {
    Box(
        modifier.padding(top = 9.dp, end = 12.dp).size(44.dp).clip(CircleShape).background(ControlPlate).clickable(role = Role.Button, onClick = open),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Tune, stringResource(R.string.recorder_settings_title), Modifier.size(22.dp), tint = Color.White)
    }
}

/**
 * The frame around the recorder, as in the original: red while a take is being recorded,
 * yellow while it is paused. A 2 dp line, 3 dp from the edge; it takes no touches.
 */
@Composable
private fun TakeFrame(phase: Phase, modifier: Modifier) {
    val color = when (phase) {
        Phase.Recording -> RecordRed.copy(alpha = 0.85f)
        Phase.Paused -> PausedYellow.copy(alpha = 0.75f)
        else -> return
    }
    // The line follows the corners of the display where the system tells them; over the tabs it is square.
    val view = LocalView.current
    val top = with(LocalDensity.current) {
        val radius = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
        } else 0
        maxOf(4.dp, radius.toDp() - 3.dp)
    }
    Box(modifier.padding(3.dp).border(2.dp, color, RoundedCornerShape(topStart = top, topEnd = top, bottomStart = 4.dp, bottomEnd = 4.dp)))
}

@Composable
private fun Status(capture: CaptureController, store: ProjectStore, modifier: Modifier) {
    Column(modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val recording = capture.phase == Phase.Recording
        Clock(capture, store.selected)
        val micLevel = stringResource(R.string.recorder_mic_level)
        // The interface stays upright, so the frame the take will have is named here.
        Text(
            if (capture.landscape) stringResource(R.string.recorder_landscape) else stringResource(R.string.recorder_portrait),
            color = Color.White, fontSize = 12.sp, maxLines = 1,
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

/**
 * The one clock of the recorder, as in the original: the length of the edit plus the segment being written.
 * Red while recording, dark red when the project already has footage, a yellow word while the take is paused.
 */
@Composable
private fun Clock(capture: CaptureController, project: Project?) {
    val phase = capture.phase
    val edited = remember(project) { project?.edit?.duration ?: 0.0 }
    // The recorder reports the length of the segment once a second; the clock runs on between its reports.
    var running by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(phase) {
        running = 0.0
        while (phase == Phase.Recording) { running = capture.segmentSeconds(); delay(200) }
    }
    val seconds = (edited + if (phase == Phase.Recording) running else 0.0).toLong().coerceAtLeast(0)
    val time = "%02d:%02d".format(seconds / 60, seconds % 60)
    val plate = when {
        phase == Phase.Recording -> RecordRed.copy(alpha = 0.8f)
        project?.sources?.isNotEmpty() == true && phase != Phase.Saving -> Color(0xFF961C29)
        phase == Phase.Paused -> Color(0xA6000000)
        else -> ControlPlate
    }
    val spoken = when (phase) {
        Phase.Idle -> stringResource(R.string.recorder_ready) + " " + time
        Phase.Recording -> stringResource(R.string.recorder_rec, time)
        Phase.Paused -> stringResource(R.string.recorder_paused, time)
        Phase.Saving -> stringResource(R.string.recorder_saving)
    }
    Row(
        Modifier.widthIn(min = 80.dp).heightIn(min = 38.dp).background(plate, RoundedCornerShape(50)).padding(horizontal = 12.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally), Alignment.CenterVertically,
    ) {
        when (phase) {
            Phase.Saving -> CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
            Phase.Recording -> Text(stringResource(R.string.recorder_clock_rec), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Phase.Paused -> Text(stringResource(R.string.recorder_clock_paused), color = PausedYellow, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Phase.Idle -> Unit
        }
        Text(
            if (phase == Phase.Saving) stringResource(R.string.recorder_saving) else time,
            color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, maxLines = 1,
        )
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

/**
 * The bottom row, as in the original. The big button starts a take, stops the one being recorded and resumes
 * a paused one; the round button on its right pauses, and finishes a paused take; Mark is on the left.
 */
@Composable
private fun Controls(capture: CaptureController) {
    val phase = capture.phase
    val recording = phase == Phase.Recording
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            // In place from the first Record to Stop; it marks only while a segment is being written.
            if (phase != Phase.Idle) MarkButton(capture)
        }
        val recordLabel = stringResource(when (phase) {
            Phase.Recording -> R.string.recorder_stop_recording
            Phase.Paused -> R.string.recorder_resume
            else -> R.string.recorder_record
        })
        Box(
            Modifier.size(88.dp).clip(CircleShape)
                .clickable(enabled = phase != Phase.Saving, role = Role.Button) { if (recording) capture.finishTake() else capture.record() }
                .semantics {
                    role = Role.Button
                    contentDescription = recordLabel
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(80.dp).border(3.dp, Color.White, CircleShape))
            when (phase) {
                Phase.Saving -> CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 3.dp)
                Phase.Recording -> Box(Modifier.size(30.dp).background(RecordRed, RoundedCornerShape(7.dp)))
                Phase.Paused -> {
                    Box(Modifier.size(66.dp).background(RecordRed, CircleShape))
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(34.dp), tint = Color.White)
                }
                Phase.Idle -> Box(Modifier.size(66.dp).background(RecordRed, CircleShape))
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            when (phase) {
                Phase.Recording -> SideButton(Icons.Filled.Pause, R.string.recorder_pause_recording, capture::pause)
                Phase.Paused -> SideButton(Icons.Filled.Stop, R.string.recorder_finish_paused, capture::finishTake)
                else -> Unit
            }
        }
    }
}

/** One tap marks the moment without interrupting the take. The icon fills for a moment after each mark. */
@Composable
private fun MarkButton(capture: CaptureController) {
    val enabled = capture.phase == Phase.Recording
    var acknowledged by remember { mutableStateOf(false) }
    LaunchedEffect(capture.marks) {
        if (capture.marks > 0) { acknowledged = true; delay(700); acknowledged = false }
    }
    Column(
        Modifier.size(52.dp).alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(15.dp)).background(ControlPlate)
            .clickable(enabled = enabled, role = Role.Button, onClick = capture::bookmark),
        Arrangement.Center, Alignment.CenterHorizontally,
    ) {
        Icon(if (acknowledged) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, null, Modifier.size(22.dp), tint = Color.White)
        Text(stringResource(R.string.recorder_mark), color = Color.White, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun SideButton(icon: ImageVector, label: Int, action: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(ControlPlate).clickable(role = Role.Button, onClick = action),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, stringResource(label), Modifier.size(24.dp), tint = Color.White)
    }
}

/** The zoom transition time as the chip shows it; the core's own label is English only. */
@Composable
private fun zoomTimeLabel(seconds: Double): String =
    if (seconds == 0.0) stringResource(R.string.recorder_zoom_instant) else ZoomTransition.durationLabel(seconds)
