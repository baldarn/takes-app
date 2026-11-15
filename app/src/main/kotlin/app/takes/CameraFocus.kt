package app.takes

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPoint
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.takes.core.FocusControl
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The focus of the camera in use, as the original has it: tap to focus with a box where the picture was
 * tapped, a lock, manual focus where the lens can be set, and hints for lenses that cannot do one of these.
 * Each camera remembers whether it was locked and where. Main thread only, except [results].
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraFocus(private val context: Context, private val settings: RecorderSettings) {
    /** The lens focuses by itself. */
    var autofocus by mutableStateOf(false); private set
    /** The lens can be set to a position: manual focus. */
    var adjustable by mutableStateOf(false); private set
    /** The focus can be held where it is. */
    var lockSupported by mutableStateOf(false); private set
    var locked by mutableStateOf(false); private set
    /** Where the lens is, from 0 at its nearest focus to 1 at its farthest. */
    var lens by mutableStateOf(FocusControl.DEFAULT); private set
    /** A line shown above the focus button for a moment. */
    var hint by mutableStateOf<String?>(null); private set
    /** Where the picture was tapped, while the lens focuses there. */
    var box by mutableStateOf<Offset?>(null); private set
    var boxShown by mutableStateOf(false); private set

    val mode: FocusControl.Mode get() = FocusControl.mode(autofocus, adjustable, locked)
    val fixed: Boolean get() = mode == FocusControl.Mode.Fixed

    private val scope = MainScope()
    private val main = Handler(Looper.getMainLooper())
    private var camera: Camera? = null
    private var cameraID: String? = null
    private var nearest = 0.0 // dioptres; zero on a lens that cannot be set
    private var pointSupported = false
    private var hintJob: Job? = null
    private var boxJob: Job? = null
    @Volatile private var reported = Float.NaN // dioptres, as the camera last reported them
    @Volatile private var following = false

    /**
     * Listens to what the camera reports with every frame, for the position of the lens while it focuses by
     * itself. Called on the camera's thread.
     */
    val results = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val distance = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: return
            if (abs(distance - reported) < 0.01f) return
            reported = distance
            if (following) main.post { follow() }
        }
    }

    private fun follow() {
        if (!locked && nearest > 0 && !reported.isNaN()) lens = FocusControl.position(reported.toDouble(), nearest)
    }

    /** A camera was bound: reads what its lens can do and returns it to how this camera was left. */
    fun bound(camera: Camera, id: String) {
        unbound()
        this.camera = camera
        cameraID = id
        val info = Camera2CameraInfo.from(camera.cameraInfo)
        val modes = runCatching { info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) }.getOrNull() ?: IntArray(0)
        nearest = (runCatching { info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) }.getOrNull() ?: 0f).toDouble()
        // A lens without a nearest focus is a fixed one, whatever modes it lists.
        autofocus = nearest > 0 && modes.any { it != CameraCharacteristics.CONTROL_AF_MODE_OFF }
        adjustable = nearest > 0 && CameraCharacteristics.CONTROL_AF_MODE_OFF in modes
        pointSupported = autofocus && runCatching { camera.cameraInfo.isFocusMeteringSupported(action(centre(), hold = false)) }.getOrDefault(false)
        lockSupported = adjustable || pointSupported
        lens = settings.focusLens(id)
        following = adjustable
        if (settings.focusLocked(id) && lockSupported) lock(lens, persist = false) else follow()
    }

    /** No camera is bound. */
    fun unbound() {
        following = false
        reported = Float.NaN
        camera = null
        cameraID = null
        locked = false
        autofocus = false
        adjustable = false
        lockSupported = false
        pointSupported = false
        dismissBox(fade = false)
    }

    private fun centre() = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)

    private fun action(point: MeteringPoint, hold: Boolean): FocusMeteringAction =
        FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).apply { if (hold) disableAutoCancel() }.build()

    /**
     * The picture was tapped at [marker]: focuses on [point] and draws the box there until the lens settles.
     * A locked lens keeps its focus, as in the original.
     */
    fun tap(point: MeteringPoint, marker: Offset) {
        val camera = camera ?: return
        if (locked) return
        if (fixed) { show(context.getString(R.string.recorder_focus_fixed_hint)); return }
        if (!autofocus) return
        val action = action(point, hold = false)
        if (!pointSupported) { show(context.getString(R.string.recorder_focus_own_point)); return }
        // A tap beside the picture is not a place to focus on.
        if (!runCatching { camera.cameraInfo.isFocusMeteringSupported(action) }.getOrDefault(false)) return
        val started = SystemClock.elapsedRealtime()
        var settled = false
        runCatching { camera.cameraControl.startFocusAndMetering(action) }.getOrNull()
            ?.addListener({ settled = true }, ContextCompat.getMainExecutor(context)) ?: return
        box = marker
        boxShown = true
        boxJob?.cancel()
        boxJob = scope.launch {
            while (!FocusControl.boxEnds((SystemClock.elapsedRealtime() - started) / 1000.0, settled)) delay(50)
            dismissBox(fade = true)
        }
    }

    private fun dismissBox(fade: Boolean) {
        boxJob?.cancel()
        boxJob = null
        boxShown = false
        if (!fade) box = null
    }

    /** Changes between automatic and locked focus, as a tap on the focus button does; says so on a fixed lens. */
    fun toggle() {
        if (FocusControl.canToggle(lockSupported, autofocus)) setLocked(!locked)
        else if (fixed) show(context.getString(R.string.recorder_focus_fixed_hint))
    }

    /**
     * Locks the focus, at [position] where the lens can be set and where it is otherwise, or returns it to
     * the camera. A position being dragged is not remembered until the finger lifts ([persist]).
     */
    fun setLocked(locked: Boolean, position: Double? = null, persist: Boolean = true) {
        val camera = camera ?: return
        if (locked) {
            if (!lockSupported) return
            lock(FocusControl.bounded(position ?: current()), persist)
        } else {
            this.locked = false
            runCatching { Camera2CameraControl.from(camera.cameraControl).clearCaptureRequestOptions() }
            runCatching { camera.cameraControl.cancelFocusAndMetering() }
            follow()
            if (persist) remember()
        }
    }

    /** Where the lens is now: as the camera reports it while it focuses by itself. */
    private fun current(): Double =
        if (!locked && nearest > 0 && !reported.isNaN()) FocusControl.position(reported.toDouble(), nearest) else lens

    private fun lock(position: Double, persist: Boolean) {
        val camera = camera ?: return
        dismissBox(fade = false)
        locked = true
        if (adjustable) {
            lens = position
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, FocusControl.dioptres(position, nearest).toFloat())
                .build()
            runCatching { Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(options) }
        } else {
            // A lens that cannot be set is focused once and held there.
            runCatching { camera.cameraControl.startFocusAndMetering(action(centre(), hold = true)) }
        }
        if (persist) remember()
    }

    private fun remember() {
        val id = cameraID ?: return
        settings.rememberFocus(id, locked, lens)
    }

    /** Shows [text] above the focus button for a moment. */
    fun show(text: String) {
        hintJob?.cancel()
        hint = text
        hintJob = scope.launch {
            delay((FocusControl.HINT_SECONDS * 1000).toLong())
            hint = null
        }
    }
}

/** The box where the picture was tapped: yellow, 48 dp, there until the lens settles, then fading. */
@Composable
internal fun FocusBox(focus: CameraFocus) {
    val at = focus.box ?: return
    val alpha by animateFloatAsState(
        if (focus.boxShown) 1f else 0f, tween(if (focus.boxShown) 0 else (FocusControl.BOX_FADE_SECONDS * 1000).toInt()), label = "focusBox",
    )
    val half = with(LocalDensity.current) { (FocusControl.BOX_SIDE / 2).dp.toPx() }
    Box(
        Modifier.offset { IntOffset((at.x - half).roundToInt(), (at.y - half).roundToInt()) }.size(FocusControl.BOX_SIDE.dp).alpha(alpha)
            .border(2.5.dp, Color.Black.copy(alpha = 0.35f), RoundedCornerShape(3.dp)).padding(0.5.dp)
            .border(1.5.dp, ControlYellow, RoundedCornerShape(3.dp)),
    )
}

/**
 * The focus button of the camera strip, as in the original: AF, MF, Locked or Fixed. A tap changes between
 * automatic and locked; in manual focus a swipe up focuses farther and down nearer, finely at first.
 */
@Composable
internal fun FocusButton(focus: CameraFocus, enabled: Boolean) {
    val mode = focus.mode
    val manual = mode == FocusControl.Mode.Manual
    var adjusted by remember { mutableStateOf<Double?>(null) }
    val label = stringResource(when (mode) {
        FocusControl.Mode.Fixed -> R.string.recorder_focus_fixed_label
        FocusControl.Mode.Manual -> R.string.recorder_focus_manual_label
        FocusControl.Mode.Locked -> R.string.recorder_focus_locked_label
        FocusControl.Mode.Auto -> R.string.recorder_focus_auto_label
    })
    val value = when (mode) {
        FocusControl.Mode.Fixed -> label
        FocusControl.Mode.Auto -> stringResource(R.string.recorder_focus_auto_value)
        else -> stringResource(R.string.recorder_focus_locked_value, FocusControl.percent(focus.lens))
    }
    val farther = stringResource(R.string.recorder_focus_farther)
    val nearer = stringResource(R.string.recorder_focus_nearer)
    val color = if (focus.locked) ControlYellow else Color.White
    Box(
        Modifier.size(52.dp, 44.dp).alpha(if (enabled) 1f else 0.4f).pointerInput(focus, enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown()
                val start = focus.lens
                var moved = false
                var vertical = false
                var lens: Double? = null
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    val travel = (change.position - down.position) / density
                    if (!change.pressed) {
                        // The position is remembered when the finger lifts; a touch that did not travel is a tap.
                        lens?.let { focus.setLocked(true, it) } ?: run { if (!moved) focus.toggle() }
                        break
                    }
                    if (!moved && maxOf(abs(travel.x), abs(travel.y)) >= FocusControl.SWIPE_START) { moved = true; vertical = abs(travel.y) > abs(travel.x) }
                    if (!moved || !vertical || focus.mode != FocusControl.Mode.Manual) continue
                    change.consume()
                    val next = FocusControl.swiped(start, travel.y.toDouble())
                    if (next != lens) {
                        lens = next
                        adjusted = next
                        focus.setLocked(true, next, persist = false)
                    }
                }
                adjusted = null
            }
        }.clearAndSetSemantics {
            contentDescription = label
            stateDescription = value
            role = Role.Button
            if (enabled) {
                onClick { focus.toggle(); true }
                if (manual) {
                    customActions = listOf(
                        CustomAccessibilityAction(farther) { focus.setLocked(true, focus.lens + FocusControl.STEP); true },
                        CustomAccessibilityAction(nearer) { focus.setLocked(true, focus.lens - FocusControl.STEP); true },
                    )
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (manual) Icon(Icons.Filled.KeyboardArrowUp, null, Modifier.size(11.dp), tint = color)
            Text(
                stringResource(when (mode) {
                    FocusControl.Mode.Fixed -> R.string.recorder_focus_fixed
                    FocusControl.Mode.Manual -> R.string.recorder_focus_manual
                    FocusControl.Mode.Locked -> R.string.recorder_focus_locked
                    FocusControl.Mode.Auto -> R.string.recorder_focus_auto
                }),
                color = color, fontSize = 12.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
            if (manual) Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(11.dp), tint = color)
        }
        val bubble = adjusted?.let { stringResource(R.string.recorder_focus_percent, FocusControl.percent(it)) } ?: focus.hint
        if (bubble != null) {
            Text(
                bubble, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                fontFamily = if (adjusted != null) FontFamily.Monospace else null,
                modifier = Modifier.align(Alignment.TopEnd).wrapContentSize(Alignment.TopEnd, unbounded = true).offset(y = (-34).dp)
                    .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 6.dp)
                    .semantics { if (adjusted == null) liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** The "Focus" section of More settings: the lock, and the position of the lens where it can be set. */
@Composable
internal fun FocusSettings(focus: CameraFocus, enabled: Boolean) {
    Section(stringResource(R.string.recorder_settings_focus)) {
        SwitchRow(stringResource(R.string.recorder_focus_lock), focus.locked, enabled && focus.lockSupported && !focus.fixed) { focus.setLocked(it) }
        if (focus.adjustable) {
            val position = stringResource(R.string.recorder_focus_position)
            SettingsRow(Modifier.alpha(if (enabled) 1f else 0.4f)) {
                Note(stringResource(R.string.recorder_focus_near))
                Slider(
                    focus.lens.toFloat(), { focus.setLocked(true, it.toDouble()) }, Modifier.weight(1f).semantics { contentDescription = position },
                    enabled = enabled, colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = SettingsCyan),
                )
                Note(stringResource(R.string.recorder_focus_far))
            }
        }
        val note = when {
            focus.fixed -> R.string.recorder_focus_note_fixed
            focus.adjustable -> R.string.recorder_focus_note_manual
            else -> null
        }
        if (note != null) SettingsRow { Note(stringResource(note)) }
    }
}
