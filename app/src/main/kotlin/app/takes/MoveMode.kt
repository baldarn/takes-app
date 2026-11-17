package app.takes

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardTab
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import app.takes.core.EditTime
import app.takes.core.TimelineMoveScope
import app.takes.core.TrackMove
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

/** The boundary picker's colour, the original's systemCyan. */
internal val PickColor = Color(0xFF64D2FF)
private val GripYellow = Color(0xFFFFD60A)

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
 * The boundary picker of Move (EditorView.swift:288-302, :709-731): whether
 * it is on, and the stops a tap on one dashed line could mean. Scrolling
 * while it is on moves the playhead, paused, as the original's seek does
 * (EditorView.swift:586-590); [browse] is the second the timeline is scrolled
 * to past the end of the draft, where the playhead stops, and what the time
 * says there (EditorView.swift:550, :584).
 */
@Stable
internal class MovePicker {
    var picking by mutableStateOf(false)
    var nearby by mutableStateOf<List<EditTime>>(emptyList())
    var browse by mutableStateOf<Double?>(null)

    fun reset() {
        picking = false
        nearby = emptyList()
        browse = null
    }
}

/**
 * [MoveBar] for [session], with the choice among stops close together:
 * [update] takes the session a scope, a nudge or a stop made, [place] puts
 * the selection's start on a stop, [pause] stops playback for the picker,
 * [end] applies or cancels.
 */
@Composable
internal fun MoveControls(
    session: TrackMoveSession, picker: MovePicker, update: (TrackMoveSession?) -> Unit, place: (EditTime) -> Unit,
    pause: () -> Unit, end: (applying: Boolean) -> Unit,
) {
    MoveBar(
        session, picker.picking,
        previous = { session.previousStop?.let(place) }, next = { session.nextStop?.let(place) },
        pick = { on -> if (on) { pause(); picker.browse = null }; picker.picking = on },
        nudge = { frames -> update(session.nudged(frames)) },
        scope = { chosen -> update(session.changingScope(chosen)) },
        cancel = { end(false) }, done = { end(true) },
    )
    if (picker.nearby.isNotEmpty()) NearbyStopsDialog(picker.nearby, choose = { picker.nearby = emptyList(); place(it) }, dismiss = { picker.nearby = emptyList() })
}

/**
 * The panel under the timeline while Move is on, as the original's
 * `moveActions` and the scope buttons that take the place of the tools
 * (EditorView.swift:32-90, :690-735, :950-956): where the selection starts,
 * the steps to the previous and the next stop and the boundary picker, how
 * far it has moved with the frame nudges, Cancel and Done, and the four
 * scopes. While [picking], the row asks for a tap on a dashed line instead.
 */
@Composable
internal fun MoveBar(
    session: TrackMoveSession, picking: Boolean,
    previous: () -> Unit, next: () -> Unit, pick: (Boolean) -> Unit, nudge: (frames: Int) -> Unit, scope: (TimelineMoveScope) -> Unit,
    cancel: () -> Unit, done: () -> Unit,
) {
    val offset = session.offset.seconds
    Column(Modifier.fillMaxWidth().background(Color(0xFF1B1D21)).padding(horizontal = 8.dp, vertical = 4.dp)) {
        if (picking) Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CenterFocusStrong, null, Modifier.padding(end = 8.dp).size(22.dp), tint = PickColor)
                val title = stringResource(R.string.move_pick_title)
                val note = stringResource(R.string.move_pick_note)
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "$title. $note" }) {
                    Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(note, color = Quiet, fontSize = 13.sp)
                }
                val back = stringResource(R.string.move_pick_back_description)
                TextButton(onClick = { pick(false) }, Modifier.semantics { contentDescription = back }) { Text(stringResource(R.string.move_pick_back)) }
        // Cancel and Done go to a line of their own where the row has no room for them.
        } else FlowRow(Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            val start = TrackMove.preciseLabel(session.anchor, session.boundaries.times) ?: preciseLabel(session.anchor.seconds)
            val startLabel = stringResource(R.string.move_selection_start)
            Column(Modifier.align(Alignment.CenterVertically).semantics(mergeDescendants = true) { contentDescription = startLabel; stateDescription = start }) {
                Text(start, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
                Text(startLabel, color = Quiet, fontSize = 11.sp, maxLines = 1)
            }
            Row(Modifier.align(Alignment.CenterVertically).padding(start = 6.dp), Arrangement.spacedBy(4.dp), Alignment.CenterVertically) {
                StopButton(Icons.AutoMirrored.Filled.KeyboardTab, mirrored = true, R.string.move_previous, GripYellow, session.previousStop != null, previous)
                StopButton(Icons.AutoMirrored.Filled.KeyboardTab, mirrored = false, R.string.move_next, GripYellow, session.nextStop != null, next)
                StopButton(Icons.Filled.CenterFocusStrong, mirrored = false, R.string.move_choose, PickColor, session.canPickBoundary) { pick(true) }
            }
            Row(Modifier.weight(1f).align(Alignment.CenterVertically), Arrangement.End, Alignment.CenterVertically) {
                TextButton(onClick = cancel, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(stringResource(R.string.common_cancel), maxLines = 1, softWrap = false) }
                TextButton(onClick = done, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(stringResource(R.string.common_done), fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
            }
        }
        // How far it moved, with a frame earlier and later beside it (docs/EDITOR-TRACKS-DESIGN.md:106-110).
        // At the largest sizes the offset stands over the two nudges, which have the width between them.
        val earlier = stringResource(R.string.move_nudge_earlier)
        val later = stringResource(R.string.move_nudge_later)
        val nudgeEarlier = @Composable {
            TextButton(onClick = { nudge(-1) }, Modifier.semantics { contentDescription = earlier }, enabled = !picking, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(stringResource(R.string.move_nudge_earlier_short), color = if (picking) Quiet else MoveColor, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
        }
        val nudgeLater = @Composable {
            TextButton(onClick = { nudge(1) }, Modifier.semantics { contentDescription = later }, enabled = !picking, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(stringResource(R.string.move_nudge_later_short), color = if (picking) Quiet else MoveColor, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
        }
        val shift = signedSeconds(offset)
        val accessible = accessibilityText()
        val shiftLabel = stringResource(R.string.move_offset_description, shift)
        val moved: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier.padding(horizontal = 8.dp).semantics(mergeDescendants = true) { contentDescription = shiftLabel }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.move_offset, shift), color = MoveColor, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
                // Over the nudges, as large text stands it, the hint has the width to go onto more lines.
                Text(
                    if (session.snap != null) stringResource(R.string.move_snapped) else stringResource(R.string.move_hint), color = Quiet, fontSize = 11.sp,
                    maxLines = if (accessible) 3 else 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
            }
        }
        if (accessible) {
            moved(Modifier.fillMaxWidth())
            FlowRow(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { nudgeEarlier(); nudgeLater() }
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            nudgeEarlier()
            moved(Modifier.weight(1f))
            nudgeLater()
        }
        // The four scopes, a scope that would move no more than a narrower one off (EditorView.swift:950-956).
        // Two columns where a name does not fit a quarter of the width, as the original's grid (EditorView.swift:902-905).
        FitGrid(4, Modifier.fillMaxWidth().padding(vertical = 4.dp), wrapping = true) {
            for (each in TimelineMoveScope.entries) {
                MoveScopeButton(each, chosen = each == session.scope, enabled = !picking && each in session.scopes, Modifier) { scope(each) }
            }
        }
    }
}

/** A step to a stop or the picker: a round button tinted as the original's (EditorView.swift:736-744). */
@Composable
private fun StopButton(icon: ImageVector, mirrored: Boolean, label: Int, tint: Color, enabled: Boolean, action: () -> Unit) {
    val description = stringResource(label)
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(tint.copy(alpha = if (enabled) 0.14f else 0.05f))
            .clickable(enabled = enabled, role = Role.Button, onClick = action).semantics { contentDescription = description },
        Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(20.dp).graphicsLayer { if (mirrored) scaleX = -1f }, tint = if (enabled) tint else Quiet.copy(alpha = 0.5f))
    }
}

/**
 * A scope of Move as the original draws it (EditorView.swift:32-90): three
 * tiles, the middle one the selection in yellow, the sides filled when the
 * scope takes them, a rail under what moves, and the name.
 */
@Composable
private fun MoveScopeButton(scope: TimelineMoveScope, chosen: Boolean, enabled: Boolean, modifier: Modifier, action: () -> Unit) {
    val title = stringResource(scope.title)
    val hint = stringResource(scope.hint)
    val ink = when { !enabled -> Quiet.copy(alpha = 0.5f); chosen -> GripYellow; else -> Color.White }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.heightIn(min = 52.dp).clip(shape).background(if (chosen) GripYellow.copy(alpha = 0.13f) else Color.White.copy(alpha = 0.07f))
            .then(if (chosen) Modifier.border(1.5.dp, GripYellow, shape) else Modifier)
            .clickable(enabled = enabled, onClickLabel = hint, role = Role.RadioButton, onClick = action)
            .semantics { contentDescription = title; selected = chosen }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.size(38.dp, 19.dp)) {
            val unit = size.width / 38
            for (index in 0..2) {
                val center = index == 1
                val topLeft = Offset((index * 13 + 1) * unit, (if (center) 1 else 3) * unit)
                val tile = Size(10 * unit, (if (center) 14 else 10) * unit)
                val corner = CornerRadius(2 * unit)
                val filled = center || (if (index == 0) scope.includesLeft else scope.includesRight)
                if (filled) drawRoundRect(if (center) GripYellow.copy(alpha = if (enabled) 1f else 0.5f) else ink, topLeft, tile, corner)
                else drawRoundRect(ink.copy(alpha = 0.55f), topLeft, tile, corner, style = Stroke(unit))
                if (center) drawRoundRect(Color.Black.copy(alpha = 0.75f), Offset(topLeft.x + 4 * unit, 5 * unit), Size(2 * unit, 6 * unit), CornerRadius(unit))
            }
            val first = if (scope.includesLeft) 1 else 14
            val last = if (scope.includesRight) 37 else 24
            drawRoundRect(ink, Offset(first * unit, 17 * unit), Size((last - first) * unit, 2 * unit), CornerRadius(unit))
        }
        Text(title, color = ink, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2)
    }
}

private val TimelineMoveScope.title: Int
    get() = when (this) {
        TimelineMoveScope.Selected -> R.string.move_scope_selected
        TimelineMoveScope.Left -> R.string.move_scope_left
        TimelineMoveScope.Right -> R.string.move_scope_right
        TimelineMoveScope.Both -> R.string.move_scope_both
    }

private val TimelineMoveScope.hint: Int
    get() = when (this) {
        TimelineMoveScope.Selected -> R.string.move_scope_selected_hint
        TimelineMoveScope.Left -> R.string.move_scope_left_hint
        TimelineMoveScope.Right -> R.string.move_scope_right_hint
        TimelineMoveScope.Both -> R.string.move_scope_both_hint
    }

/**
 * The stops a tap on one dashed line could mean, as the original's sheet
 * "Choose a start time" (EditorView.swift:288-302): each by a time that
 * tells it from the others.
 */
@Composable
internal fun NearbyStopsDialog(times: List<EditTime>, choose: (EditTime) -> Unit, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.move_choose_start)) },
        text = {
            Column {
                for (time in times) TextButton(onClick = { choose(time) }, Modifier.fillMaxWidth()) {
                    Text(TrackMove.preciseLabel(time, times) ?: preciseLabel(time.seconds), Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.move_pick_back)) } },
    )
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

/**
 * The last row of tools: the clip under the playhead a place earlier or
 * later on a row of clips (off one, [reorder] says why not), Move, Unlink or
 * Link in the stable slot beside it (EditorView.swift:949-950), Add video at
 * the playhead and Delete of the clip under the playhead.
 */
@Composable
internal fun ArrangeTools(
    unavailable: String?, explain: (String) -> Unit, reorderable: (step: Int) -> Boolean, reorder: (step: Int) -> Unit,
    canMove: Boolean, move: () -> Unit, linking: Boolean, canLink: Boolean, link: () -> Unit,
    addVideo: () -> Unit, canDelete: Boolean, delete: () -> Unit,
) {
    val tight = PaddingValues(horizontal = 8.dp)
    val compact = Modifier.defaultMinSize(minWidth = 48.dp)
    ToolRow(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        for ((label, step) in listOf(stringResource(R.string.editor_move_earlier) to -1, stringResource(R.string.editor_move_later) to 1)) {
            val description = stringResource(if (step < 0) R.string.editor_move_clip_earlier else R.string.editor_move_clip_later)
            ReasonedButton(
                unavailable, explain = explain, onClick = { reorder(step) },
                modifier = compact.semantics { contentDescription = description },
                enabled = reorderable(step), content = { Text(label) },
            )
        }
        val moveDescription = stringResource(R.string.move_button_description)
        TextButton(onClick = move, compact.semantics { contentDescription = moveDescription }, enabled = canMove, contentPadding = tight) {
            Icon(Icons.Filled.SwapHoriz, null, Modifier.size(18.dp))
            Text(stringResource(R.string.move_button), Modifier.padding(start = 2.dp))
        }
        val linkDescription = stringResource(if (linking) R.string.link_unlink_description else R.string.link_link_description)
        TextButton(onClick = link, compact.semantics { contentDescription = linkDescription }, enabled = canLink, contentPadding = tight) {
            Icon(if (linking) Icons.Filled.LinkOff else Icons.Filled.Link, null, Modifier.size(18.dp))
            Text(stringResource(if (linking) R.string.link_unlink else R.string.link_link), Modifier.padding(start = 2.dp))
        }
        // New takes go in at the playhead; Stop in the recorder ends the run.
        TextButton(onClick = addVideo, compact, contentPadding = tight) { Text(stringResource(R.string.editor_add_video)) }
        TextButton(onClick = delete, compact, enabled = canDelete, contentPadding = tight) {
            Icon(Icons.Outlined.Delete, stringResource(R.string.common_delete), tint = if (canDelete) RecordRed else LocalContentColor.current)
        }
    }
}
