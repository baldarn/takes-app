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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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

@Composable
fun RecorderScreen(capture: CaptureController, store: ProjectStore) {
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
            Text("Camera access is needed to record.", color = Color.White)
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }) { Text("Open settings") }
        }
        return
    }

    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    val previewView = remember {
        // Show the whole recorded frame, never a crop of it.
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }
    LaunchedEffect(owner) { capture.attach(owner, previewView.surfaceProvider) }
    DisposableEffect(owner) {
        // Returning from the background must never resume recording by itself.
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) capture.finishTake() }
        owner.lifecycle.addObserver(observer)
        view.keepScreenOn = true
        onDispose {
            owner.lifecycle.removeObserver(observer)
            view.keepScreenOn = false
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
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 12.dp)) {
            ZoomRow(capture)
            CameraRow(capture)
            Controls(capture, store)
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
                    Phase.Idle -> "Ready"
                    Phase.Recording -> "REC  " + timeLabel(capture.elapsed)
                    Phase.Paused -> "PAUSED  " + timeLabel(capture.elapsed)
                    Phase.Saving -> "SAVING…"
                },
                color = Color.White, fontWeight = FontWeight.Bold,
            )
        }
        val project = store.selected
        Text(
            (project?.let { "${it.title} · ${timeLabel(it.capturedDuration)}" } ?: "New project") +
                " · " + if (capture.landscape) "Landscape" else "Portrait",
            color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp).background(Scrim, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
        )
        if (recording && capture.audioWarning == null) {
            LinearProgressIndicator(
                progress = { capture.level },
                modifier = Modifier.padding(top = 8.dp).width(160.dp).semantics { contentDescription = "Microphone level" },
                color = if (capture.level > 0.95f) RecordRed else Color.White, trackColor = Scrim,
            )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoomRow(capture: CaptureController) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        var choosing by remember { mutableStateOf(false) }
        Box {
            Chip(ZoomTransition.durationLabel(capture.zoomDuration), false,
                Modifier.clickable(onClickLabel = "Change zoom transition time", role = Role.Button) { choosing = true })
            DropdownMenu(choosing, { choosing = false }) {
                ZoomTransition.durationPresets.forEach { seconds ->
                    DropdownMenuItem({ Text(ZoomTransition.durationLabel(seconds)) }, { capture.chooseZoomDuration(seconds); choosing = false })
                }
            }
        }
        val presets = ZoomTransition.presets(capture.zoomRange.start, capture.zoomRange.endInclusive)
        presets.forEach { preset ->
            // Tap uses the saved time; double tap always takes one second.
            Chip(ZoomTransition.zoomLabel(preset), abs(capture.zoom - preset) < 0.005,
                Modifier.combinedClickable(
                    role = Role.Button, onClickLabel = "Zoom", onDoubleClick = { capture.zoomTo(preset, 1.0) },
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
                Modifier.clickable(enabled = capture.phase != Phase.Saving, onClickLabel = "Switch camera", role = Role.Button) {
                    capture.switchCamera(choice.id)
                })
        }
    }
}

@Composable
private fun Controls(capture: CaptureController, store: ProjectStore) {
    val phase = capture.phase
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
        Box(Modifier.width(96.dp), contentAlignment = Alignment.Center) {
            if (phase == Phase.Recording || phase == Phase.Paused) {
                TextButton(onClick = capture::finishTake) { Text("Stop", fontSize = 17.sp) }
            }
        }
        val pausing = phase == Phase.Recording
        Box(
            Modifier.size(76.dp).border(4.dp, Color.White, CircleShape)
                .clickable(enabled = phase != Phase.Saving, role = Role.Button) { if (pausing) capture.pause() else capture.record() }
                .semantics {
                    role = Role.Button
                    contentDescription = when (phase) {
                        Phase.Recording -> "Pause"
                        Phase.Paused -> "Resume recording"
                        else -> "Record"
                    }
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
            if (phase == Phase.Idle && store.selected?.sources?.isNotEmpty() == true) {
                TextButton(onClick = { store.create() }) { Text("New project", fontSize = 15.sp) }
            }
            // One tap marks the moment without interrupting the take.
            if (phase == Phase.Recording) {
                TextButton(onClick = capture::bookmark) { Text(if (capture.marks == 0) "Mark" else "Mark · ${capture.marks}", fontSize = 17.sp) }
            }
        }
    }
}
