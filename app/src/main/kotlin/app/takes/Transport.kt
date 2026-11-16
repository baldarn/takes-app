package app.takes

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Row

private val BookmarkTint = Color(0xFFFF7A45)
private val SnapTint = Color(0xFFFFD60A)

/**
 * Previous change or next change, as the original's transport has them: a tap
 * goes to the nearest cut or change of treatment on that side, a hold to the
 * nearest change only, passing over plain cuts. [jump] is told which.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChangeButton(forward: Boolean, enabled: Boolean, jump: (effectsOnly: Boolean) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val label = stringResource(if (forward) R.string.editor_change_next else R.string.editor_change_previous)
    val hold = stringResource(R.string.editor_change_hold)
    Box(
        Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 40.dp).clip(RoundedCornerShape(20.dp))
            .combinedClickable(
                enabled = enabled, role = Role.Button, onClickLabel = label, onLongClickLabel = hold,
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); jump(true) }, onClick = { jump(false) },
            )
            .semantics { contentDescription = label },
        Alignment.Center,
    ) {
        Icon(if (forward) Icons.Filled.SkipNext else Icons.Filled.SkipPrevious, null, tint = Color.White.copy(alpha = if (enabled) 1f else 0.38f))
    }
}

/**
 * The bookmarks, in one menu as the original's Bookmarks button has them: set
 * or clear one at the playhead, step to the one before or after, or go to any
 * of them by its time.
 *
 * @param under whether a bookmark lies under the playhead, which the first entry then removes.
 * @param times every bookmark of the movie, in timeline seconds.
 */
@Composable
internal fun BookmarksMenu(
    under: Boolean, canToggle: Boolean, toggle: () -> Unit, previous: Double?, next: Double?, times: List<Double>,
    label: (Double) -> String, go: (Double) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val name = stringResource(R.string.editor_bookmarks)
    Box {
        TextButton(
            onClick = { open = true }, Modifier.defaultMinSize(minWidth = 40.dp).semantics { contentDescription = name },
            contentPadding = PaddingValues(horizontal = 6.dp),
        ) { Icon(if (under) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, null, tint = BookmarkTint) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (under) R.string.editor_bookmark_remove else R.string.editor_bookmark_add)) },
                onClick = { open = false; toggle() }, enabled = canToggle,
                leadingIcon = { Icon(if (under) Icons.Outlined.BookmarkRemove else Icons.Outlined.BookmarkAdd, null) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_bookmark_previous)) }, onClick = { open = false; previous?.let(go) }, enabled = previous != null,
                leadingIcon = { Icon(Icons.Filled.SkipPrevious, null) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_bookmark_next)) }, onClick = { open = false; next?.let(go) }, enabled = next != null,
                leadingIcon = { Icon(Icons.Filled.SkipNext, null) },
            )
            if (times.isNotEmpty()) HorizontalDivider()
            for (time in times) DropdownMenuItem(text = { Text(label(time)) }, onClick = { open = false; go(time) })
        }
    }
}

/**
 * The magnet beside Undo and Redo, as in the original: whether the playhead
 * settles on a nearby clip edge or bookmark when a scroll of the timeline
 * ends. A hold says what it does.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SnapToggle(on: Boolean, toggle: () -> Unit, explain: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val name = stringResource(R.string.editor_snap)
    val state = stringResource(if (on) R.string.editor_snap_on else R.string.editor_snap_off)
    val tint = if (on) SnapTint else Color(0xFFA0A4AB)
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(if (on) SnapTint.copy(alpha = 0.14f) else Color.Transparent)
            .combinedClickable(
                role = Role.Switch, onClickLabel = name,
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); explain() },
                onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); toggle() },
            )
            .semantics { contentDescription = name; stateDescription = state },
        Alignment.Center,
    ) {
        // The original's magnet: a horseshoe with two bright poles, struck through when off.
        Canvas(Modifier.size(24.dp).rotate(-25f)) {
            val unit = size.width / 24
            val shoe = Path().apply {
                moveTo(5 * unit, 8 * unit)
                lineTo(5 * unit, 14 * unit)
                cubicTo(5 * unit, 24 * unit, 19 * unit, 24 * unit, 19 * unit, 14 * unit)
                lineTo(19 * unit, 8 * unit)
            }
            drawPath(shoe, tint, style = Stroke(4 * unit, cap = StrokeCap.Butt))
            val pole = Color.White.copy(alpha = if (on) 0.95f else 0.5f)
            drawRect(pole, Offset(3 * unit, 3 * unit), Size(4 * unit, 3 * unit))
            drawRect(pole, Offset(17 * unit, 3 * unit), Size(4 * unit, 3 * unit))
            if (!on) drawLine(tint, Offset(1 * unit, 23 * unit), Offset(23 * unit, 1 * unit), 1.5f * unit, StrokeCap.Round)
        }
    }
}

/**
 * The review speed over the corner of the picture, as the original's: the
 * speed in a capsule, which opens the five it offers, 1× to 3×. It changes
 * the preview only; the sound keeps its pitch.
 */
@Composable
internal fun ReviewSpeed(speed: Double, open: Boolean, modifier: Modifier, toggle: () -> Unit, choose: (Double) -> Unit) {
    val name = stringResource(R.string.editor_review_speed)
    Row(
        modifier.clip(RoundedCornerShape(22.dp)).background(Color.Black.copy(alpha = 0.65f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = toggle, Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 44.dp).semantics { contentDescription = name; stateDescription = speedLabel(speed) },
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) { Text(speedLabel(speed), color = Color.White, fontWeight = FontWeight.SemiBold) }
        if (open) for (choice in REVIEW_SPEEDS) {
            val label = stringResource(R.string.editor_review_at, speedLabel(choice))
            TextButton(
                onClick = { choose(choice) }, Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 44.dp).semantics { contentDescription = label },
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) { Text(speedLabel(choice), color = if (choice == speed) SnapTint else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** The review speeds the original offers (EditorController.swift:304). */
private val REVIEW_SPEEDS = listOf(1.0, 1.5, 2.0, 2.5, 3.0)

/** Whether the playhead snaps, kept across launches as the original keeps it; on until turned off. */
internal class SnapSetting(context: Context) {
    private val prefs = context.getSharedPreferences("editor", Context.MODE_PRIVATE)
    var on by mutableStateOf(prefs.getBoolean(KEY, true)); private set

    fun toggle() {
        on = !on
        prefs.edit().putBoolean(KEY, on).apply()
    }

    private companion object {
        const val KEY = "snapToCuts"
    }
}
