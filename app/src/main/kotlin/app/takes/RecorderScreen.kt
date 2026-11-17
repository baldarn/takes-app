package app.takes

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.RoundedCorner
import android.view.Surface
import android.view.View
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.takes.CaptureController.Phase
import app.takes.core.CaptureFormat
import app.takes.core.LiveCrashState
import app.takes.core.RecorderFramePoints
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToDown
import app.takes.core.Point
import app.takes.core.VideoEffectID
import app.takes.core.FrontLight
import app.takes.core.PinchZoom
import app.takes.core.Project
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** The plate under a control that sits on the picture, as in the original. */
internal val ControlPlate = Color(0x85000000)
/** The plate in use: opaque while the front light is on, so the controls stay readable on it, as in the original. */
internal val LocalControlPlate = compositionLocalOf { ControlPlate }
private val LitPlate = Color(FrontLight.PLATE_WHITE.toFloat(), FrontLight.PLATE_WHITE.toFloat(), FrontLight.PLATE_WHITE.toFloat())
private val PausedYellow = Color(0xFFFFD60A)

/**
 * The haptics of the recorder, as in the original: a tick for each step of a control and for a zoom preset,
 * a firmer one for Mark and for an assist. They follow the system's touch feedback setting and need no permission.
 */
internal fun View.tick() {
    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}

internal fun View.confirm() {
    performHapticFeedback(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** [openEditor] is the way back after a take recorded into the middle of an edit, and from its chip. */
@Composable
fun RecorderScreen(capture: CaptureController, store: ProjectStore, openEditor: () -> Unit) {
    val context = LocalContext.current
    var cameraGranted by remember { mutableStateOf(granted(context, Manifest.permission.CAMERA)) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        cameraGranted = granted(context, Manifest.permission.CAMERA)
        // The meter starts with the permission to record.
        capture.foreground()
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
    val previewView = remember { PreviewView(context) }
    val settings = capture.settings
    var settingsOpen by remember { mutableStateOf(false) }
    var zoomOpen by remember { mutableStateOf(false) }
    // Being on is not remembered, as in the original: the light is off whenever the recorder opens.
    var frontLight by remember { mutableStateOf(false) }
    var pinch by remember { mutableStateOf<PinchZoom?>(null) }
    // The live video effects, over the picture in place of the controls, as in the original (RecorderView.swift:252-267).
    var effectsOpen by remember { mutableStateOf(false) }
    var inspected by remember { mutableStateOf<VideoEffectID?>(null) }
    // Where the subject of a camera move was tapped, shown for a moment.
    var bracket by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(bracket) { if (bracket != null) { delay(800); bracket = null } }
    // While the camera changes, the last picture of the old one covers the preview, as in the original.
    var cover by remember { mutableStateOf<ImageBitmap?>(null) }
    var streaming by remember { mutableStateOf(false) }
    DisposableEffect(previewView) {
        val observer = Observer<PreviewView.StreamState> { streaming = it == PreviewView.StreamState.STREAMING }
        previewView.previewStreamState.observeForever(observer)
        onDispose { previewView.previewStreamState.removeObserver(observer) }
    }
    LaunchedEffect(cover) {
        if (cover == null) return@LaunchedEffect
        // Until the new camera delivers pictures; a camera that never does must not leave the cover up.
        withTimeoutOrNull(1000) { snapshotFlow { streaming }.first { !it } }
        withTimeoutOrNull(3000) { snapshotFlow { streaming }.first { it } }
        cover = null
    }
    val switchCamera = { id: String, nativeZoom: Boolean ->
        if (id != capture.cameraID && capture.phase != Phase.Saving && cover == null) {
            cover = runCatching { previewView.bitmap?.asImageBitmap() }.getOrNull()
            capture.switchCamera(id, nativeZoom)
        }
    }
    LaunchedEffect(owner) { capture.attach(owner, previewView.surfaceProvider) }
    DisposableEffect(owner) {
        // In the background the take is paused and its segment saved, as in the original: the take, and a run
        // of takes recorded into the middle of an edit, are still there on return. Returning never resumes by itself.
        // The chosen microphone is a system-wide route: hold it only while the recorder is in front.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { capture.claimMicrophone(); capture.foreground() }
                Lifecycle.Event.ON_STOP -> { capture.background(); capture.releaseMicrophone() }
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

    // The interface turns with the phone between takes and is held as it stands from the first Record to Stop,
    // pauses included, as the original holds it (RecorderView.swift:341-369): the take keeps one frame.
    val held = capture.phase != Phase.Idle
    DisposableEffect(activity, held) {
        activity?.requestedOrientation = if (held) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    // The volume keys press Crash Zoom while it is on, as the original's capture event interaction (CaptureController.swift:3214-3220).
    DisposableEffect(activity) {
        val main = activity as? MainActivity
        main?.cameraKeys = { capture.crashAvailable && capture.ready }
        main?.cameraKeyPressed = { if (capture.triggerCrashZoom()) view.tick() }
        onDispose { main?.cameraKeys = null; main?.cameraKeyPressed = null }
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // The interface turned with the top of the phone, and its cameras, to the right puts the take controls on the
    // left, at the other end, as in the original (RecorderView.swift:146-148, 211-234).
    val controlsOnLeft = capture.rotation == Surface.ROTATION_270

    // A point of the preview in the recorded frame, as fractions from its top left; null outside it.
    fun framePoint(at: Offset): Point? {
        val front = capture.cameras.firstOrNull { it.id == capture.cameraID }?.front == true
        val aspect = if (previewView.width < previewView.height) 9.0 / 16 else 16.0 / 9
        return RecorderFramePoints.fromView(
            at.x.toDouble(), at.y.toDouble(), previewView.width.toDouble(), previewView.height.toDouble(), aspect,
            fill = !settings.fitFrame, unmirror = front && settings.mirrorPreview != settings.mirrorRecording,
        )
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black).pictureGestures(capture, show = { pinch = it }) { at ->
            // While placing a picture the fingers move it; while picking a colour a tap picks it. RecorderView.swift:86, CaptureController.swift:3222-3236.
            if (capture.positioning != null) return@pictureGestures
            if (capture.colorPick != null) {
                framePoint(at)?.let(capture::pickColor)
                return@pictureGestures
            }
            // A tap outside the effects closes them, except while it chooses the subject of a move. RecorderView.swift:87-95.
            val closes = when (inspected) {
                VideoEffectID.CrashZoom -> !capture.crashAvailable || capture.crashState != LiveCrashState.Ready
                VideoEffectID.DollyZoom -> !capture.dollyAvailable || capture.dollyState.moving
                else -> true
            }
            if (effectsOpen && closes) { effectsOpen = false; return@pictureGestures }
            if (!capture.ready || capture.phase == Phase.Saving || cover != null) return@pictureGestures
            if (capture.choosesSubject) {
                // A tap chooses the subject of a camera move, in the recorded frame. CaptureController.swift:3236, 3340-3346.
                framePoint(at)?.let { capture.selectSubject(it); bracket = at }
            } else {
                // A tap on the picture focuses there, as in the original.
                capture.focus.tap(previewView.meteringPointFactory.createPoint(at.x, at.y), at)
            }
        },
    ) {
        // The whole recorded frame, or the screen filled with it: a setting, as in the original.
        AndroidView({ previewView }, Modifier.fillMaxSize()) {
            it.scaleType = if (settings.fitFrame) PreviewView.ScaleType.FIT_CENTER else PreviewView.ScaleType.FILL_CENTER
        }
        cover?.let {
            Image(it, null, Modifier.fillMaxSize(), contentScale = if (settings.fitFrame) ContentScale.Fit else ContentScale.Crop)
        }
        FocusBox(capture.focus)
        bracket?.let { SubjectBracket(it) }
        if (capture.positioning != null) BackgroundPlacing(capture, previewView, Modifier.matchParentSize())
        // Over the picture and under the controls. It is part of the interface: the camera never sees it.
        if (frontLight) FrontLightRing(settings, screenCornerRadius()) { frontLight = false }
        CompositionLocalProvider(LocalControlPlate provides if (frontLight) LitPlate else ControlPlate) {
            pinch?.let {
                // 27 % of the way down, centred, as in the original.
                PinchBadge(it, Modifier.align(BiasAlignment(0f, (PinchZoom.BADGE_HEIGHT * 2 - 1).toFloat())))
            }
            // While the settings are open the screen reader is in them alone, as in the original.
            if (!effectsOpen) Box(Modifier.matchParentSize().then(if (settingsOpen) Modifier.clearAndSetSemantics {} else Modifier)) {
                // Clear of the camera cutout, which stays when the status bar is hidden.
                Status(capture, store, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)))
                // At the start of the top bar, as in the original.
                MicrophonePill(
                    capture,
                    Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Start))
                        .padding(top = 9.dp, start = 12.dp),
                )
                if (capture.lookWarm) {
                    WarmBanner(
                        capture,
                        Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).padding(top = 62.dp),
                    )
                }
                LiveEffectsButton(
                    capture,
                    Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))
                        .padding(top = 9.dp, end = 12.dp + 44.dp + 8.dp),
                ) { effectsOpen = true }
                SettingsButton(
                    // A dot says that an assist is on.
                    assist = frontLight || capture.format.stabilization != CaptureFormat.Stabilization.Off || capture.levelling || capture.settings.followFace,
                colour = stringResource(if (capture.format.hdr) R.string.recorder_color_hdr else R.string.recorder_color_sdr),
                    Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End)),
                ) { settingsOpen = true }
                val usable = capture.ready && capture.phase != Phase.Saving && cover == null
                val sides = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                // In landscape the three take buttons form a rail at one end, and the rest stands at the bottom between
                // the rail and the other end, as in the original (RecorderView.swift:211-234, 423-425).
                if (landscape) {
                    Controls(
                        capture, store.selected?.title, rail = true,
                        Modifier.align(if (controlsOnLeft) Alignment.CenterStart else Alignment.CenterEnd).windowInsetsPadding(sides).padding(horizontal = 18.dp),
                    )
                }
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(sides)
                        .then(if (landscape) Modifier.padding(start = if (controlsOnLeft) RailWidth else 0.dp, end = if (controlsOnLeft) 0.dp else RailWidth) else Modifier)
                        .padding(bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FocusHint(capture.focus)
                    ProjectChip(capture, store, openEditor)
                    CameraMoveControls(capture, Modifier.padding(bottom = 6.dp))
                    if (zoomOpen && capture.hasZoom) {
                        Box(Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp)) { ZoomPresets(capture, usable, switchCamera) }
                    }
                    CameraStrip(capture, usable, zoomOpen, { zoomOpen = !zoomOpen }, switchCamera)
                    if (!landscape) Controls(capture, store.selected?.title, rail = false)
                }
            }
        }
        if (effectsOpen) {
            val panel = Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF0F0F0F)).border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
                // A touch on the panel is the panel's: it neither closes it nor reaches the picture under it.
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { if (it.changedToDown()) it.consume() } } }
            val sides = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom + WindowInsetsSides.Top)
            LiveEffectsPanel(
                capture,
                // A frame tool's bar stands at the bottom, over as little of the picture as it can. RecorderView.swift:258-266.
                if (capture.frameToolActive) Modifier.align(Alignment.BottomCenter).windowInsetsPadding(sides).padding(8.dp).widthIn(max = 520.dp).fillMaxWidth().then(panel)
                else if (landscape) Modifier.align(if (controlsOnLeft) Alignment.CenterStart else Alignment.CenterEnd).windowInsetsPadding(sides).padding(8.dp)
                    .width(360.dp).fillMaxHeight().then(panel)
                else Modifier.align(Alignment.BottomCenter).windowInsetsPadding(sides).padding(8.dp).fillMaxWidth().height(340.dp).then(panel),
                inspect = { inspected = it },
            ) { effectsOpen = false; inspected = null }
        }
        TakeFrame(capture.phase, landscape, Modifier.matchParentSize())
        if (settingsOpen) CameraSettings(capture, store.selected, frontLight, { frontLight = it }) { settingsOpen = false }
        Notice(capture.message, Modifier.align(Alignment.Center)) { capture.message = null }
    }
}

/** The bracket where the subject of a camera move was tapped: 48 dp, for 0.8 s. CaptureController.swift:3343-3346. */
@Composable
private fun SubjectBracket(at: Offset) {
    val half = with(LocalDensity.current) { 24.dp.toPx() }
    Box(
        Modifier.offset { IntOffset((at.x - half).toInt(), (at.y - half).toInt()) }.size(48.dp)
            .border(1.5.dp, Color(0xFFFFD60A), RoundedCornerShape(8.dp)),
    )
}

/** The way into "Camera settings", at the end of the top bar as in the original. */
@Composable
private fun SettingsButton(assist: Boolean, colour: String, modifier: Modifier, open: () -> Unit) {
    Box(
        modifier.padding(top = 9.dp, end = 12.dp).size(44.dp).clip(CircleShape).background(LocalControlPlate.current).clickable(role = Role.Button, onClick = open)
            // The screen reader hears the colour the takes are recorded in, as in the original.
            .semantics(mergeDescendants = true) { stateDescription = colour },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Tune, stringResource(R.string.recorder_settings_title), Modifier.size(22.dp), tint = Color.White)
        if (assist) Box(Modifier.align(Alignment.BottomEnd).padding(7.dp).size(6.dp).background(SettingsCyan, CircleShape))
    }
}

/** The radius of the display's corners, where the system tells it; zero elsewhere. */
@Composable
private fun screenCornerRadius(): Dp {
    val view = LocalView.current
    return with(LocalDensity.current) {
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0 else 0).toDp()
    }
}

/**
 * The frame around the recorder, as in the original: red while a take is being recorded,
 * yellow while it is paused. A 2 dp line, 3 dp from the edge; it takes no touches.
 */
@Composable
private fun TakeFrame(phase: Phase, landscape: Boolean, modifier: Modifier) {
    val color = when (phase) {
        Phase.Recording -> RecordRed.copy(alpha = 0.85f)
        Phase.Paused -> PausedYellow.copy(alpha = 0.75f)
        else -> return
    }
    // The line follows the corners of the display where the system tells them; beside the tabs, under them in
    // portrait and at the start in landscape, it is square.
    val round = maxOf(4.dp, screenCornerRadius() - 3.dp)
    val shape = if (landscape) RoundedCornerShape(topStart = 4.dp, topEnd = round, bottomStart = 4.dp, bottomEnd = round)
    else RoundedCornerShape(topStart = round, topEnd = round, bottomStart = 4.dp, bottomEnd = 4.dp)
    Box(modifier.padding(3.dp).border(2.dp, color, shape))
}

@Composable
private fun Status(capture: CaptureController, store: ProjectStore, modifier: Modifier) {
    Column(modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // The interface stands as the take will be framed, and nothing has to name the frame.
        Clock(capture, store.selected)
        val warning = Modifier.padding(top = 8.dp)
        // What this phone's cameras offer, to copy and send; the development build only.
        if (capture.phase == Phase.Idle) CameraReportEntry(capture)
        capture.microphoneWarning?.let {
            Text(it, color = Color.White, fontSize = 13.sp,
                modifier = warning.background(RecordRed, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
        }
        capture.audioWarning?.let {
            Text(it, color = Color.White, fontSize = 13.sp,
                modifier = warning.background(RecordRed, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
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
    // The recorder reports the length of the segment with each frame it writes; the clock runs on between its reports.
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
        else -> LocalControlPlate.current
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

/**
 * The camera strip above the bottom row, as in the original: flip to the other side of the phone,
 * the menu of cameras, the zoom read-out that opens the presets, the focus.
 */
@Composable
private fun CameraStrip(capture: CaptureController, usable: Boolean, zoomOpen: Boolean, toggleZoom: () -> Unit, switchCamera: (String, Boolean) -> Unit) {
    if (capture.cameras.isEmpty()) return
    Row(
        Modifier.padding(bottom = 14.dp).background(StripPlate, RoundedCornerShape(50)).padding(horizontal = 5.dp),
        Arrangement.spacedBy(6.dp), Alignment.CenterVertically,
    ) {
        val opposite = capture.oppositeCamera
        val flipLabel = stringResource(
            if (opposite?.front == true) R.string.recorder_flip_front else R.string.recorder_flip_back,
        )
        val flips = usable && opposite != null
        Box(
            Modifier.size(44.dp).alpha(if (flips) 1f else 0.4f).clip(CircleShape)
                .clickable(enabled = flips, role = Role.Button) { opposite?.let { switchCamera(it.id, false) } }
                .semantics { opposite?.let { stateDescription = it.label } },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Cameraswitch, flipLabel, Modifier.size(22.dp), tint = Color.White)
        }
        var choosing by remember { mutableStateOf(false) }
        val current = capture.cameras.firstOrNull { it.id == capture.cameraID }
        val cameraLabel = stringResource(R.string.recorder_camera)
        Box {
            Row(
                Modifier.heightIn(min = 44.dp).widthIn(min = 64.dp, max = 118.dp).alpha(if (usable) 1f else 0.4f)
                    .clickable(enabled = usable, onClickLabel = stringResource(R.string.recorder_switch_camera), role = Role.DropdownList) { choosing = true }
                    .padding(end = 8.dp).semantics(mergeDescendants = true) { contentDescription = cameraLabel; current?.let { stateDescription = it.label } },
                Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally), Alignment.CenterVertically,
            ) {
                Text(current?.label ?: cameraLabel, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics {})
                Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(14.dp), tint = Color.White)
            }
            DropdownMenu(choosing, { choosing = false }) {
                capture.cameras.forEach { choice ->
                    DropdownMenuItem(
                        // A camera without 4K records 1080p while 4K is chosen, and says so.
                        { Text(if (capture.settings.ultraHD && !choice.offers.ultraHD) stringResource(R.string.recorder_camera_1080_only, choice.label) else choice.label) },
                        { choosing = false; switchCamera(choice.id, false) },
                        leadingIcon = { Icon(if (choice.id == capture.cameraID) Icons.Filled.Check else Icons.Filled.PhotoCamera, null) },
                        modifier = Modifier.semantics { selected = choice.id == capture.cameraID },
                    )
                }
            }
        }
        if (capture.hasZoom) {
            StripDivider()
            ZoomButton(capture, zoomOpen, usable, toggleZoom)
        }
        StripDivider()
        FocusButton(capture.focus, usable, capture.settings.focusPeaking, capture::toggleFocusPeaking)
    }
}

@Composable
private fun StripDivider() {
    Box(Modifier.size(1.dp, 16.dp).background(Color.White.copy(alpha = 0.2f)))
}

/** The width the rail of take buttons takes in landscape, with its margins. */
private val RailWidth = 124.dp

/**
 * The bottom row, as in the original, or in landscape the rail at one end (RecorderView.swift:423-425): the same
 * three buttons from top to bottom, 24 apart. The big button starts a take, stops the one being recorded and resumes
 * a paused one; the round button after it pauses, and finishes a paused take; Mark is before it.
 */
@Composable
private fun Controls(capture: CaptureController, project: String?, rail: Boolean, modifier: Modifier = Modifier) {
    val phase = capture.phase
    val recording = phase == Phase.Recording
    val mark = @Composable {
        // In place from the first Record to Stop; it marks only while a segment is being written.
        if (phase != Phase.Idle) MarkButton(capture)
    }
    val side = @Composable {
        when (phase) {
            Phase.Recording -> SideButton(Icons.Filled.Pause, R.string.recorder_pause_recording, capture::pause)
            Phase.Paused -> SideButton(Icons.Filled.Stop, R.string.recorder_finish_paused, capture::finishTake)
            else -> Unit
        }
    }
    val record = @Composable {
        val recordLabel = stringResource(when (phase) {
            Phase.Recording -> R.string.recorder_stop_recording
            Phase.Paused -> R.string.recorder_resume
            else -> R.string.recorder_record
        })
        // The screen reader hears where the take goes, as in the original.
        val into = project ?: stringResource(R.string.recorder_new_project)
        Box(
            Modifier.size(88.dp).clip(CircleShape)
                .clickable(enabled = phase != Phase.Saving, role = Role.Button) { if (recording) capture.finishTake() else capture.record() }
                .semantics {
                    role = Role.Button
                    contentDescription = recordLabel
                    stateDescription = into
                },
            contentAlignment = Alignment.Center,
        ) {
            val lit = LocalControlPlate.current == LitPlate
            Box(Modifier.size(80.dp).background(if (lit) LitPlate else Color.Transparent, CircleShape).border(3.dp, Color.White, CircleShape))
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
    }
    if (rail) {
        Column(modifier, Arrangement.spacedBy(24.dp), Alignment.CenterHorizontally) {
            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { mark() }
            record()
            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { side() }
        }
    } else {
        Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { mark() }
            record()
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { side() }
        }
    }
}

/** One tap marks the moment without interrupting the take. The icon fills for a moment after each mark. */
@Composable
private fun MarkButton(capture: CaptureController) {
    val enabled = capture.phase == Phase.Recording
    var acknowledged by remember { mutableStateOf(false) }
    val view = LocalView.current
    LaunchedEffect(capture.marks) {
        if (capture.marks > 0) { acknowledged = true; delay(700); acknowledged = false }
    }
    val label = stringResource(R.string.recorder_mark_label)
    val value = stringResource(if (acknowledged) R.string.recorder_mark_added else R.string.recorder_mark_moment)
    Column(
        Modifier.size(52.dp).alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(15.dp)).background(LocalControlPlate.current)
            .clickable(enabled = enabled, role = Role.Button) { capture.bookmark(); view.confirm() }
            // One element for the screen reader: what the button does, and that the mark was placed.
            .semantics(mergeDescendants = true) { contentDescription = label; stateDescription = value },
        Arrangement.Center, Alignment.CenterHorizontally,
    ) {
        Icon(if (acknowledged) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, null, Modifier.size(22.dp), tint = Color.White)
        Text(stringResource(R.string.recorder_mark), color = Color.White, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
private fun SideButton(icon: ImageVector, label: Int, action: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(LocalControlPlate.current).clickable(role = Role.Button, onClick = action),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, stringResource(label), Modifier.size(24.dp), tint = Color.White)
    }
}
