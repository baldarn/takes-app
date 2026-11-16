package app.takes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takes.core.PinchZoom
import app.takes.core.RecorderCameras
import app.takes.core.ZoomTransition
import java.text.NumberFormat
import kotlinx.coroutines.delay
import kotlin.math.abs

/** What is selected or set in the recorder's own controls, as in the original. */
internal val ControlYellow = Color(0xFFFFD60A)
/** The plate of a group of controls that sits on the picture. */
internal val StripPlate = Color(0xB3262628)

/**
 * The gestures on the picture, as in the original: two fingers zoom, measured from where they went down,
 * and one short touch that does not travel is a tap. A third finger gives the gesture up.
 * [show] is given the pinch while it lasts, for the badge, and null when it ends.
 */
internal fun Modifier.pictureGestures(capture: CaptureController, show: (PinchZoom?) -> Unit, tap: (Offset) -> Unit): Modifier =
    pointerInput(capture) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val lens = capture.cameraID
            var startSpan = 0.0
            var startMid = Offset.Zero
            var two = false // a second finger landed: this touch is no longer a tap
            var pinch: PinchZoom? = null
            var travelled = false
            fun end(apply: Boolean) {
                pinch?.let { if (apply && capture.cameraID == lens) capture.endPinch(it) }
                pinch = null
                show(null)
            }
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) {
                    val up = event.changes.firstOrNull { it.id == down.id }
                    if (!two && !travelled && up != null && up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) tap(up.position)
                    end(apply = true)
                    break
                }
                if (pressed.size == 1 && !two) {
                    if ((pressed[0].position - down.position).getDistance() > viewConfiguration.touchSlop) travelled = true
                    continue
                }
                event.changes.forEach { it.consume() }
                if (pressed.size != 2) {
                    // A finger lifted, which ends the pinch exactly once, or a third one landed, which gives it up.
                    if (pinch != null) end(apply = pressed.size < 2 || pinch?.timed == false)
                    two = true
                    startSpan = -1.0
                    continue
                }
                val mid = (pressed[0].position + pressed[1].position) / 2f / density
                val span = maxOf(PinchZoom.MINIMUM_SPAN, ((pressed[0].position - pressed[1].position).getDistance() / density).toDouble())
                if (!two) { two = true; startSpan = span; startMid = mid }
                // After a pinch ended, the fingers still down do not begin another.
                if (startSpan < 0) continue
                if (pinch == null && PinchZoom.begins(startSpan, span, (mid - startMid).getDistance().toDouble())) {
                    pinch = capture.beginPinch(startSpan)
                    if (pinch == null) startSpan = -1.0
                }
                pinch?.let { current ->
                    if (capture.cameraID != lens || !capture.canPinch) { end(apply = false); startSpan = -1.0; return@let }
                    val step = current.moved(span)
                    pinch = step.pinch
                    step.apply?.let(capture::pinchZoom)
                    show(step.pinch)
                }
            }
        }
    }

/**
 * The read-out of a pinch, a quarter of the way down the picture: the zoom the fingers have reached and,
 * when a zoom time is set, that the move waits for the release.
 */
@Composable
internal fun PinchBadge(pinch: PinchZoom, modifier: Modifier = Modifier) {
    val zoom = PinchZoom.readout(pinch.zoom)
    Text(
        if (pinch.timed) stringResource(R.string.recorder_pinch_on_release, zoom, compactSeconds(pinch.seconds)) else zoom,
        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, maxLines = 1,
        modifier = modifier.height(28.dp).background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(14.dp)).padding(horizontal = 10.dp).wrapContentSize(),
    )
}

/** A zoom as the screen reader says it: the number alone, in the way of the language, "1" and "2,5" in Italian. */
private fun spokenZoom(zoom: Double): String = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(zoom)

/** The zoom in the camera strip: shows the magnification and opens the presets. */
@Composable
internal fun ZoomButton(capture: CaptureController, open: Boolean, enabled: Boolean, toggle: () -> Unit) {
    val label = stringResource(R.string.recorder_zoom_times, spokenZoom(capture.zoom))
    Box(
        Modifier.size(58.dp, 44.dp).alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, role = Role.Button, onClick = toggle)
            .clearAndSetSemantics { contentDescription = label; role = Role.Button; if (enabled) onClick { toggle(); true } },
        contentAlignment = Alignment.Center,
    ) {
        Text(PinchZoom.readout(capture.zoom), color = if (open) ControlYellow else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * The zoom presets with the transition time beside them, as in the original. A tap on a preset takes the
 * saved time, a double tap one second; from an ultrawide that is a camera of its own, 1× changes to the main camera.
 */
@Composable
internal fun ZoomPresets(capture: CaptureController, enabled: Boolean, switchCamera: (String, Boolean) -> Unit) {
    var hint by remember { mutableIntStateOf(0) }
    var hinting by remember { mutableStateOf(false) }
    LaunchedEffect(hint) {
        if (hint == 0) return@LaunchedEffect
        hinting = true
        delay((PinchZoom.DURATION_HINT_SECONDS * 1000).toLong())
        hinting = false
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (hinting) {
            Text(
                stringResource(R.string.recorder_zoom_time_hint), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        Row(
            Modifier.height(48.dp).background(StripPlate, RoundedCornerShape(50)).padding(horizontal = 6.dp),
            Arrangement.spacedBy(4.dp), Alignment.CenterVertically,
        ) {
            ZoomDuration(capture, tapped = { hint++ }, dragged = { hinting = false })
            Box(Modifier.size(1.dp, 22.dp).background(Color.White.copy(alpha = 0.2f)))
            val main = capture.mainLensAtOneX
            Row(Modifier.alpha(if (enabled) 1f else 0.4f).horizontalScroll(rememberScrollState()), Arrangement.spacedBy(2.dp)) {
                RecorderCameras.presets(capture.zoomRange.start, capture.zoomRange.endInclusive).forEach { preset ->
                    val toMain = preset == 1.0 && main != null
                    val select = { seconds: Double ->
                        hinting = false
                        if (toMain) switchCamera(main!!.id, true) else capture.zoomTo(preset, seconds)
                    }
                    ZoomPreset(preset, toMain, !toMain && PinchZoom.isSelected(preset, capture.zoom), enabled,
                        normal = { select(capture.zoomDuration) }, quick = { select(PinchZoom.QUICK_SECONDS) })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoomPreset(zoom: Double, selectsMainLens: Boolean, selected: Boolean, enabled: Boolean, normal: () -> Unit, quick: () -> Unit) {
    val label = if (selectsMainLens) stringResource(R.string.recorder_zoom_preset_main) else stringResource(R.string.recorder_zoom_preset, spokenZoom(zoom))
    val quickLabel = stringResource(R.string.recorder_zoom_one_second)
    val color = if (selected) ControlYellow else Color.White
    // The tap waits to see whether it is a double one; the touch is answered at once, as in the original.
    val view = LocalView.current
    Column(
        Modifier.size(44.dp, 48.dp).clip(RoundedCornerShape(50))
            .pointerInput(enabled) { if (enabled) awaitEachGesture { awaitFirstDown(requireUnconsumed = false); view.tick() } }
            // The single tap waits for a possible second one, so Instant never fires before a double tap.
            .combinedClickable(enabled = enabled, role = Role.Button, onDoubleClick = quick, onClick = normal)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                this.selected = selected
                if (enabled) {
                    onClick { normal(); true }
                    customActions = listOf(CustomAccessibilityAction(quickLabel) { quick(); true })
                }
            },
        Arrangement.Center, Alignment.CenterHorizontally,
    ) {
        Text(RecorderCameras.presetLabel(zoom), color = color, fontSize = 13.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (selectsMainLens) Text(stringResource(R.string.recorder_zoom_main), color = color, fontSize = 9.sp, lineHeight = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/**
 * The zoom transition time beside the presets. Dragging it up lengthens it and down shortens it, half a
 * second for every 14 dp; a tap says so. It is yellow while a time is set.
 */
@Composable
private fun ZoomDuration(capture: CaptureController, tapped: () -> Unit, dragged: () -> Unit) {
    val seconds = capture.zoomDuration
    var dragging by remember { mutableStateOf(false) }
    val onTap by rememberUpdatedState(tapped)
    val onDrag by rememberUpdatedState(dragged)
    val color = if (seconds == 0.0) Color.White else ControlYellow
    val name = stringResource(R.string.recorder_zoom_time)
    val value = zoomTimeName(seconds)
    val longer = stringResource(R.string.recorder_zoom_time_longer)
    val shorter = stringResource(R.string.recorder_zoom_time_shorter)
    // A tick for each half second, as in the original.
    val view = LocalView.current
    val change = { next: Double -> if (capture.chooseZoomDuration(next)) view.tick() }
    Box(
        Modifier.size(56.dp, 48.dp).pointerInput(capture) {
            awaitEachGesture {
                val down = awaitFirstDown()
                var start: Double? = null
                var vertical = false
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) { if (start == null) onTap(); break }
                    val travel = change.position - down.position
                    if (start == null && travel.getDistance() >= PinchZoom.DURATION_DRAG_START * density) {
                        onDrag()
                        start = capture.zoomDuration
                        vertical = abs(travel.y) > abs(travel.x)
                        dragging = true
                    }
                    val from = start ?: continue
                    change.consume()
                    if (vertical) change(PinchZoom.draggedDuration(from, (travel.y / density).toDouble()))
                }
                dragging = false
            }
        }.clearAndSetSemantics {
            contentDescription = name
            stateDescription = value
            customActions = listOf(
                CustomAccessibilityAction(longer) { change(capture.zoomDuration + PinchZoom.DURATION_STEP); true },
                CustomAccessibilityAction(shorter) { change(capture.zoomDuration - PinchZoom.DURATION_STEP); true },
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Timer, null, Modifier.size(11.dp), tint = color)
                Text(
                    if (seconds == 0.0) stringResource(R.string.recorder_zoom_instant_short) else compactSeconds(seconds), color = color, fontSize = if (seconds == 0.0) 10.sp else 12.sp,
                    lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, maxLines = 1,
                )
            }
            Icon(Icons.Filled.UnfoldMore, null, Modifier.size(11.dp), tint = color)
        }
        if (dragging) {
            Text(
                value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1,
                modifier = Modifier.align(Alignment.TopStart).wrapContentSize(Alignment.TopStart, unbounded = true).offset(36.dp, (-38).dp)
                    .background(Color(0xF0303034), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }
    }
}

/** A zoom time as the chip and the badge write it: "3s", "0.5s". */
internal fun compactSeconds(seconds: Double): String = ZoomTransition.durationLabel(seconds).replace(" ", "")
