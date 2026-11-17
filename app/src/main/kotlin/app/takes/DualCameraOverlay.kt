package app.takes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Crop169
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.filled.SwapCalls
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takes.core.DualCameraControls
import app.takes.core.DualCameraLayout
import app.takes.core.Point
import app.takes.core.Size
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The icon of each layout, as the original's (DualCameraLayout.swift:9). */
internal val DualCameraLayout.Mode.icon: ImageVector
    get() = when (this) {
        DualCameraLayout.Mode.Floating -> Icons.Filled.PictureInPicture
        DualCameraLayout.Mode.SideBySide -> Icons.Filled.VerticalSplit
        DualCameraLayout.Mode.TopAndBottom -> Icons.Filled.ViewAgenda
        DualCameraLayout.Mode.Diagonal -> Icons.Filled.ChangeHistory
    }

private val DualCameraLayout.Mode.titleRes: Int
    get() = when (this) {
        DualCameraLayout.Mode.Floating -> R.string.recorder_dual_floating
        DualCameraLayout.Mode.SideBySide -> R.string.recorder_dual_side_by_side
        DualCameraLayout.Mode.TopAndBottom -> R.string.recorder_dual_top_and_bottom
        DualCameraLayout.Mode.Diagonal -> R.string.recorder_dual_diagonal
    }

/** In the camera strip with two cameras, in place of the camera and zoom controls: Swap and Layout. RecorderView.swift:515-523. */
@Composable
internal fun DualStripButtons(capture: CaptureController, editing: Boolean, toggleEditing: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .clickable(role = Role.Button) { val layout = capture.dualLayout; capture.updateDualLayout(layout.copy(swapped = !layout.swapped)) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.SwapCalls, stringResource(R.string.recorder_dual_swap), Modifier.size(22.dp), tint = Color.White)
    }
    Row(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(50)).background(if (editing) SettingsCyan.copy(alpha = 0.3f) else Color.Transparent)
            .clickable(role = Role.Button, onClick = toggleEditing).padding(horizontal = 10.dp)
            .semantics(mergeDescendants = true) { selected = editing },
        Arrangement.spacedBy(6.dp), Alignment.CenterVertically,
    ) {
        Icon(capture.dualLayout.mode.icon, null, Modifier.size(18.dp), tint = Color.White)
        Text(stringResource(R.string.recorder_dual_layout), color = Color.White, fontSize = 14.sp)
    }
}

/**
 * The bar under the top of the recorder while the layout is edited: the four layouts and Done, the inset's shape when
 * it floats, and what the fingers do. RecorderView.swift:292-320, 392-398.
 */
@Composable
internal fun DualLayoutBar(capture: CaptureController, modifier: Modifier, done: () -> Unit) {
    val layout = capture.dualLayout
    val plate = Modifier.background(Color(0xD91C1C1E), RoundedCornerShape(14.dp)).padding(4.dp)
    Column(modifier, Arrangement.spacedBy(6.dp), Alignment.CenterHorizontally) {
        Row(plate, Arrangement.spacedBy(4.dp), Alignment.CenterVertically) {
            DualCameraLayout.Mode.entries.forEach { mode ->
                val chosen = layout.mode == mode
                Box(
                    Modifier.size(48.dp, 44.dp).clip(RoundedCornerShape(10.dp)).background(if (chosen) SettingsCyan.copy(alpha = 0.4f) else Color.Transparent)
                        .clickable(role = Role.RadioButton) { capture.updateDualLayout(capture.dualLayout.copy(mode = mode)) }
                        .semantics { selected = chosen },
                    contentAlignment = Alignment.Center,
                ) { Icon(mode.icon, stringResource(mode.titleRes), Modifier.size(22.dp), tint = Color.White) }
            }
            Box(Modifier.widthIn(min = 48.dp).heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = done).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.common_done), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (layout.mode == DualCameraLayout.Mode.Floating) {
            Row(plate, Arrangement.spacedBy(4.dp), Alignment.CenterVertically) {
                listOf(false, true).forEach { round ->
                    val chosen = layout.isRound == round
                    Row(
                        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).background(if (chosen) SettingsCyan.copy(alpha = 0.4f) else Color.Transparent)
                            .clickable(role = Role.RadioButton) { capture.updateDualLayout(capture.dualLayout.copy(roundInset = round)) }
                            .padding(horizontal = 12.dp).semantics(mergeDescendants = true) { selected = chosen },
                        Arrangement.spacedBy(6.dp), Alignment.CenterVertically,
                    ) {
                        Icon(if (round) Icons.Filled.FiberManualRecord else Icons.Filled.Crop169, null, Modifier.size(18.dp), tint = Color.White)
                        Text(stringResource(if (round) R.string.recorder_dual_circle else R.string.recorder_dual_rectangle), color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }
        val hint = when {
            layout.mode == DualCameraLayout.Mode.Diagonal -> R.string.recorder_dual_hint_diagonal
            layout.mode == DualCameraLayout.Mode.Floating -> if (layout.isRound) R.string.recorder_dual_hint_circle else R.string.recorder_dual_hint_rectangle
            else -> R.string.recorder_dual_hint_split
        }
        Text(
            stringResource(hint), color = Color.White, fontSize = 12.sp,
            modifier = Modifier.background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
}

/**
 * The pictures of the two cameras under the fingers, over the preview where the composed picture is shown, as the
 * original's overlay (RecorderView.swift:1372-1531): the floating picture is dragged with one finger and pinched to
 * resize, along the axis of the fingers when it is a rectangle; any other picture is panned with two fingers and pinched
 * to zoom its feed; while the layout is edited, the divider is dragged and the diagonal flipped.
 */
@Composable
internal fun DualLayoutOverlay(capture: CaptureController, fill: Boolean, handles: Boolean, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val viewWidth = constraints.maxWidth.toDouble()
        val viewHeight = constraints.maxHeight.toDouble()
        val canvas = capture.dualCanvas
        // The composed picture as the preview shows it: filling the screen or whole in it, centred.
        val scale = if (fill) max(viewWidth / canvas.width, viewHeight / canvas.height) else min(viewWidth / canvas.width, viewHeight / canvas.height)
        val shown = Size(canvas.width * scale, canvas.height * scale)
        val left = (viewWidth - shown.width) / 2
        val top = (viewHeight - shown.height) / 2
        val layout = capture.dualDisplayLayout
        val regions = layout.regions(shown)
        val panStart = with(density) { DualCameraControls.PAN_START.dp.toPx() }
        Box(
            Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .size(with(density) { shown.width.toFloat().toDp() }, with(density) { shown.height.toFloat().toDp() })
                .pointerInput(shown) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val at = Point(down.position.x.toDouble(), down.position.y.toDouble())
                        val start = capture.dualDisplayLayout
                        val slot = when {
                            start.contains(at, 1, shown) -> 1
                            start.contains(at, 0, shown) -> 0
                            else -> return@awaitEachGesture
                        }
                        val inset = DualCameraControls.isInset(start, slot)
                        val mirrored = capture.dualSlotMirrored(slot)
                        var count = 0
                        var centroid = Offset.Zero
                        var span = 0f
                        var axis = DualCameraLayout.InsetResizeAxis.Proportional
                        var pending = Offset.Zero
                        var panning = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            val middle = pressed.fold(Offset.Zero) { sum, change -> sum + change.position } / pressed.size.toFloat()
                            val separation = if (pressed.size >= 2) pressed[1].position - pressed[0].position else Offset.Zero
                            if (pressed.size != count) {
                                // A finger landed or lifted: measure afresh, so the picture never jumps.
                                count = pressed.size
                                centroid = middle
                                span = separation.getDistance()
                                if (count == 2) axis = DualCameraLayout.insetResizeAxis(separation.x.toDouble(), separation.y.toDouble())
                                if (inset || count >= 2) event.changes.forEach { it.consume() }
                                continue
                            }
                            var next = capture.dualDisplayLayout
                            var manual = false
                            var delta = middle - centroid
                            centroid = middle
                            if (inset || count >= 2) {
                                if (!inset && !panning) {
                                    // A pinch standing still is not a pan: the middle must travel first. RecorderView.swift:1494-1501.
                                    pending += delta
                                    if (pending.getDistance() >= panStart) { delta = pending; panning = true } else delta = Offset.Zero
                                }
                                if (delta != Offset.Zero) {
                                    next = DualCameraControls.dragged(next, slot, delta.x.toDouble(), delta.y.toDouble(), shown, mirrored)
                                    manual = !inset
                                }
                            }
                            if (count == 2) {
                                val now = separation.getDistance()
                                if (span > 1f && now > 1f) next = DualCameraControls.pinched(next, slot, (now / span).toDouble(), axis, shown)
                                span = now
                            }
                            if (next != capture.dualDisplayLayout) capture.updateDualLayout(next, manualPan = manual)
                            if (inset || count >= 2) event.changes.forEach { it.consume() }
                        }
                    }
                },
        ) {
            if (layout.mode == DualCameraLayout.Mode.Floating) {
                // The floating picture's outline, as the original's 1-point white border.
                val inset = regions[1]
                val name = stringResource(R.string.recorder_dual_inset)
                val hint = stringResource(if (layout.isRound) R.string.recorder_dual_inset_round_hint else R.string.recorder_dual_inset_hint)
                Box(
                    Modifier.offset { IntOffset(inset.minX.roundToInt(), inset.minY.roundToInt()) }
                        .size(with(density) { inset.width.toFloat().toDp() }, with(density) { inset.height.toFloat().toDp() })
                        .border(1.dp, Color.White.copy(alpha = 0.8f), if (layout.isRound) CircleShape else RoundedCornerShape(0.dp))
                        .semantics { contentDescription = name; stateDescription = hint },
                )
            }
            if (handles && layout.mode == DualCameraLayout.Mode.Diagonal) {
                val flip = stringResource(R.string.recorder_dual_flip_diagonal)
                val value = stringResource(if (layout.diagonalFallsRight) R.string.recorder_dual_diagonal_falls_right else R.string.recorder_dual_diagonal_falls_left)
                Handle(Offset((shown.width / 2).toFloat(), (shown.height / 2).toFloat()), Icons.Filled.Flip,
                    Modifier.clickable(role = Role.Button) { capture.updateDualLayout(capture.dualLayout.flippingDiagonal()) }
                        .semantics { contentDescription = flip; stateDescription = value })
            } else if (handles && layout.mode != DualCameraLayout.Mode.Floating) {
                val vertical = layout.mode == DualCameraLayout.Mode.SideBySide
                val at = if (vertical) Offset(regions[0].maxX.toFloat(), (shown.height / 2).toFloat()) else Offset((shown.width / 2).toFloat(), regions[0].maxY.toFloat())
                val divider = if (vertical) layout.verticalDivider else layout.horizontalDivider
                val label = stringResource(R.string.recorder_dual_divider)
                val value = stringResource(R.string.recorder_dual_divider_value, (divider * 100).roundToInt())
                val length = if (vertical) shown.width else shown.height
                Handle(at, if (vertical) Icons.Filled.SwapHoriz else Icons.Filled.SwapVert,
                    Modifier.pointerInput(vertical, length) {
                        var startValue = 0.0
                        var travel = 0.0
                        detectDragGestures(
                            onDragStart = {
                                startValue = if (vertical) capture.dualLayout.verticalDivider else capture.dualLayout.horizontalDivider
                                travel = 0.0
                            },
                        ) { change, amount ->
                            change.consume()
                            travel += if (vertical) amount.x else amount.y
                            capture.updateDualLayout(DualCameraControls.divided(capture.dualLayout, startValue, travel, length))
                        }
                    }.semantics {
                        contentDescription = label
                        stateDescription = value
                        progressBarRangeInfo = ProgressBarRangeInfo(divider.toFloat(), 0.15f..0.85f)
                        // RecorderView.swift:1421-1426: five percent a step.
                        setProgress { target ->
                            capture.updateDualLayout(DualCameraControls.divided(capture.dualLayout, target.toDouble(), 0.0, 1.0))
                            true
                        }
                    })
            }
        }
    }
}

/** A round handle of 44 dp centred on [at], in pixels of its parent. */
@Composable
private fun Handle(at: Offset, icon: ImageVector, modifier: Modifier) {
    val half = with(LocalDensity.current) { 22.dp.toPx() }
    Box(
        Modifier.offset { IntOffset((at.x - half).roundToInt(), (at.y - half).roundToInt()) }.size(44.dp).clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.7f)).border(1.dp, Color.White.copy(alpha = 0.8f), CircleShape).then(modifier),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(18.dp), tint = Color.White) }
}
