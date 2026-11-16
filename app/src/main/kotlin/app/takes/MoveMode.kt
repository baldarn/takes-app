package app.takes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takes.core.EditScope
import app.takes.core.EditState
import app.takes.core.RangeMarks
import app.takes.core.SequenceOnly
import app.takes.core.TimeRange
import app.takes.core.TrackLinking
import app.takes.core.TrackMoveSession
import app.takes.core.TrackSequenceView
import app.takes.core.toTimeline
import java.util.Locale

private val Quiet = Color(0xFFA0A4AB)
private val MoveColor = Color(0xFF64D2FF)

/**
 * Move begun on what is marked or selected in [edit], as the original's
 * `beginMove` (EditorView.swift:1162-1172): the selection with what it is
 * linked to, a range cut at its ends. Null when nothing is selected.
 */
internal fun beginMove(edit: EditState, marks: RangeMarks, playhead: Double, scale: Double): TrackMoveSession? {
    if (marks.scope == EditScope.Whole || marks.start != null) return null
    val timeline = edit.toTimeline()
    val selection = TrackSequenceView.selection(edit, timeline, marks.scope) ?: return null
    return TrackMoveSession.begin(timeline, selection, playhead, scale)
}

/** Where the moved pictures were when Move began, drawn as a ghost outline. */
internal val TrackMoveSession.ghost: TimeRange?
    get() = TrackLinking.interval(base, moving)?.let { TimeRange(it.start.seconds, it.end.seconds) }

/** Where the moved group starts now, in seconds. */
internal val TrackMoveSession.start: Double?
    get() = interval?.start?.seconds

/** The signed offset as the original shows it, with two decimals: +1.50, −0.25, 0.00. */
internal fun signedSeconds(seconds: Double): String {
    val text = String.format(Locale.getDefault(), "%.2f", kotlin.math.abs(seconds))
    return when {
        seconds > 0.0005 -> "+$text"
        seconds < -0.0005 -> "−$text"
        else -> text
    }
}

/**
 * The row under the timeline while Move is on, as the original's
 * `moveActions` (EditorView.swift:690-735) without the boundary picker: where
 * the selection starts and how far it has moved, then Cancel and Done.
 */
@Composable
internal fun MoveBar(session: TrackMoveSession, cancel: () -> Unit, done: () -> Unit) {
    val offset = session.offset.seconds
    Column(Modifier.fillMaxWidth().background(Color(0xFF1B1D21)).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            val start = preciseLabel(session.start ?: 0.0)
            val startLabel = stringResource(R.string.move_selection_start)
            Column(Modifier.semantics(mergeDescendants = true) { contentDescription = startLabel; stateDescription = start }) {
                Text(start, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
                Text(startLabel, color = Quiet, fontSize = 11.sp, maxLines = 1)
            }
            val shift = signedSeconds(offset)
            val shiftLabel = stringResource(R.string.move_offset_description, shift)
            Column(Modifier.padding(start = 16.dp).semantics(mergeDescendants = true) { contentDescription = shiftLabel }) {
                Text(stringResource(R.string.move_offset, shift), color = MoveColor, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
                Text(if (session.snap != null) stringResource(R.string.move_snapped) else " ", color = Quiet, fontSize = 11.sp, maxLines = 1)
            }
            Row(Modifier.weight(1f), Arrangement.End, Alignment.CenterVertically) {
                TextButton(onClick = cancel, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(stringResource(R.string.common_cancel)) }
                TextButton(onClick = done, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(stringResource(R.string.common_done), fontWeight = FontWeight.Bold) }
            }
        }
        Text(stringResource(R.string.move_hint), color = Quiet, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
    }
}

/** The reason a function of a row of clips is off, in the user's language. */
@Composable
internal fun SequenceOnly.text(): String = stringResource(
    when (this) {
        SequenceOnly.Reorder -> R.string.unavailable_reorder
        SequenceOnly.Transition -> R.string.unavailable_transition
        SequenceOnly.Clipboard -> R.string.unavailable_clipboard
    },
)

/**
 * A tool button that is off for [reason] on this edit: drawn as off, yet a
 * tap says why instead of doing nothing. Null [reason]: an ordinary button.
 */
@Composable
internal fun ReasonedButton(
    reason: String?, explain: (String) -> Unit, enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unavailable = stringResource(R.string.unavailable_state)
    TextButton(
        onClick = { if (reason != null) explain(reason) else onClick() },
        modifier = modifier.then(if (reason != null) Modifier.semantics { stateDescription = unavailable } else Modifier),
        enabled = enabled || reason != null, contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        if (reason == null) content() else androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Quiet.copy(alpha = 0.6f)) { content() }
    }
}
