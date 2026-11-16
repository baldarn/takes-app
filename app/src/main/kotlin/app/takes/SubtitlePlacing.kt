package app.takes

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.takes.core.Rect
import app.takes.core.SubtitlePlacement
import app.takes.core.SubtitleSettings
import kotlin.math.hypot
import kotlin.math.max

/** Where the captions are being placed by hand, for the picture to draw the outline of the largest caption. */
internal class SubtitlePlacing(val placement: SubtitlePlacement, val settings: SubtitleSettings)

/** The words of the screen reader's actions on the captions, in the order move left, right, up, down, enlarge, shrink. */
internal class SubtitlePlacingActions(val left: String, val right: String, val up: String, val down: String, val enlarge: String, val shrink: String)

/**
 * The original's touches on the captions while the Subtitles tool is open: a
 * drag that begins on the outline of the largest caption moves the series,
 * a pinch resizes it around the fingers, both within the limits of the
 * sliders (YouRec/SubtitleEditor.swift:183-200, YouRec/EditorView.swift:1540-1550).
 * A touch elsewhere is left to the picture. A screen reader moves and resizes
 * by steps (YouRec/EditorView.swift:1413-1422).
 *
 * [placing] gives the placement and the settings now, null while no caption
 * shows; [change] takes the next settings; [guide] is told while a gesture holds the captions.
 */
internal fun Modifier.subtitlePlacing(
    placing: () -> SubtitlePlacing?, change: (SubtitleSettings) -> Unit, guide: (Boolean) -> Unit, actions: SubtitlePlacingActions?,
): Modifier {
    val touches = pointerInput(Unit) {
        val reach = 10.dp.toPx()
        val least = 44.dp.toPx()
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false)
            val start = placing() ?: return@awaitEachGesture
            val box = start.placement.envelope(start.settings)
            if (!holds(box, first.position, size.width.toFloat(), size.height.toFloat(), least, reach)) return@awaitEachGesture
            first.consume()
            guide(true)
            try {
                var fingers = 0
                var focus = Offset.Zero
                var spread = 0.0
                var placed: SubtitleSettings? = null // what this gesture last gave, before the picture has caught up with it
                do {
                    val event = awaitPointerEvent()
                    val down = event.changes.filter { it.pressed }
                    if (down.isNotEmpty()) {
                        val now = Offset(down.sumOf { it.position.x.toDouble() }.toFloat() / down.size, down.sumOf { it.position.y.toDouble() }.toFloat() / down.size)
                        val apart = down.sumOf { hypot((it.position.x - now.x).toDouble(), (it.position.y - now.y).toDouble()) } / down.size
                        // Adding or lifting a finger begins again from where the captions are, so they never jump.
                        if (down.size == fingers) placing()?.let { current ->
                            val width = size.width.toDouble().coerceAtLeast(1.0)
                            val height = size.height.toDouble().coerceAtLeast(1.0)
                            val from = placed ?: current.settings
                            val next = if (down.size >= 2 && spread > 0 && apart > 0) {
                                current.placement.pinched(from, apart / spread, focus.x / width, focus.y / height, now.x / width, now.y / height)
                            } else {
                                current.placement.moved(from, (now.x - focus.x) / width, (now.y - focus.y) / height)
                            }
                            if (next != from) { placed = next; change(next) }
                        }
                        fingers = down.size
                        focus = now
                        spread = apart
                    }
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            } finally {
                guide(false)
            }
        }
    }
    if (actions == null) return touches
    fun step(body: (SubtitlePlacing) -> SubtitleSettings) = placing()?.let { change(body(it)); true } ?: false
    fun resize(it: SubtitlePlacing, factor: Double): SubtitleSettings {
        val at = it.placement.constrained(it.settings)
        return it.placement.pinched(at, factor, at.centerX, at.y, at.centerX, at.y)
    }
    return touches.semantics {
        customActions = listOf(
            CustomAccessibilityAction(actions.left) { step { it.placement.moved(it.settings, -0.05, 0.0) } },
            CustomAccessibilityAction(actions.right) { step { it.placement.moved(it.settings, 0.05, 0.0) } },
            CustomAccessibilityAction(actions.up) { step { it.placement.moved(it.settings, 0.0, -0.05) } },
            CustomAccessibilityAction(actions.down) { step { it.placement.moved(it.settings, 0.0, 0.05) } },
            CustomAccessibilityAction(actions.enlarge) { step { resize(it, 1.1) } },
            CustomAccessibilityAction(actions.shrink) { step { resize(it, 1 / 1.1) } },
        )
    }
}

/** The screen reader's actions on the captions, in the language of the phone. */
@Composable
internal fun subtitlePlacingActions() = SubtitlePlacingActions(
    stringResource(R.string.subtitles_move_left), stringResource(R.string.subtitles_move_right), stringResource(R.string.subtitles_move_up),
    stringResource(R.string.subtitles_move_down), stringResource(R.string.subtitles_enlarge), stringResource(R.string.subtitles_shrink),
)

/** Whether a touch at [at] takes hold of the captions: on their outline, made at least [least] across, or within [reach] of it. */
private fun holds(box: Rect, at: Offset, width: Float, height: Float, least: Float, reach: Float): Boolean {
    val w = box.width * width
    val h = box.height * height
    val padX = max(0.0, (least - w) / 2) + reach
    val padY = max(0.0, (least - h) / 2) + reach
    val left = box.x * width - padX
    val top = box.y * height - padY
    return at.x >= left && at.x <= left + w + 2 * padX && at.y >= top && at.y <= top + h + 2 * padY
}

/** The outline of the largest caption of the project, drawn while the captions are being placed, as the original's dashed guide. */
internal fun DrawScope.subtitleGuide(placing: SubtitlePlacing) {
    val box = placing.placement.envelope(placing.settings)
    drawRect(
        Color(0xFF32ADE6), Offset((box.x * size.width).toFloat(), (box.y * size.height).toFloat()), Size((box.width * size.width).toFloat(), (box.height * size.height).toFloat()),
        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
}
