package app.takes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takes.core.EditTime
import app.takes.core.EditorDurationPresets
import app.takes.core.EditorRangeTiming
import app.takes.core.PauseDetector
import java.text.NumberFormat
import java.util.Locale

private val ChipShape = RoundedCornerShape(8.dp)
private val Accent = Color(0xFF0A84FF)

/** [seconds] with up to two decimals, as Swift's `.fractionLength(0...2)`. */
private fun short(seconds: Double): String = NumberFormat.getNumberInstance(Locale.getDefault()).apply { maximumFractionDigits = 2 }.format(seconds)

/**
 * The duration of a range and its two edges, as the original's `EditorRangeDuration` since 57e5d91
 * (YouRec/EditorDurationPicker.swift:6-180): a row that opens the presets and a Custom chip, then Start and End,
 * each a frame earlier or later with − and + and on one ruler over the bounds with a slider, and Extend to end. A
 * preset sets the length once; the last manual change stays Custom even when the length equals a preset.
 * [change] is told the edge, its new time and whether the change was manual. [showsDisclosure] false shows
 * everything at once.
 */
@Composable
internal fun EditorRangeDuration(
    timing: EditorRangeTiming, name: String, showsDisclosure: Boolean = true,
    change: (edge: EditorRangeTiming.Edge, time: Double, manual: Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    val choices = EditorDurationPresets.wholeSeconds
    val isCustom = custom || choices.none { EditTime.of(it) == EditTime.of(timing.duration) }
    val title = stringResource(R.string.range_duration)
    val length = stringResource(R.string.range_seconds, short(timing.duration))
    val customLabel = stringResource(R.string.range_custom)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showsDisclosure) {
            val hint = stringResource(if (expanded) R.string.range_hide_choices else R.string.range_show_choices)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(ChipShape).background(Color.White.copy(alpha = 0.07f))
                    .clickable(onClickLabel = hint) { expanded = !expanded }
                    .semantics { contentDescription = title; stateDescription = if (isCustom) "$length, $customLabel" else length }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp), tint = Color.White)
                Text(title, Modifier.weight(1f).padding(start = 8.dp), color = Color.White)
                Text(if (isCustom) stringResource(R.string.range_custom_length, length) else length, color = Color.White)
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, Modifier.size(18.dp), tint = Color.White)
            }
        }
        if (expanded || !showsDisclosure) {
            // Five a row, the presets then Custom (YouRec/EditorDurationPicker.swift:49-69).
            val cells = choices.map<Double, Double?> { it } + listOf(null)
            for (row in cells.chunked(5)) Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(6.dp)) {
                for (value in row) {
                    if (value == null) {
                        val customHint = stringResource(R.string.range_custom_hint)
                        Chip(customLabel, isCustom, enabled = true, Modifier.weight(1f), customHint) { custom = true }
                    } else {
                        val label = stringResource(R.string.range_seconds, short(value))
                        Chip(label, !isCustom && EditTime.of(timing.duration) == EditTime.of(value), EditorDurationPresets.fits(value, timing.available), Modifier.weight(1f)) {
                            custom = false
                            change(EditorRangeTiming.Edge.End, timing.range.start + value, false)
                        }
                    }
                }
                repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            for (edge in EditorRangeTiming.Edge.entries) EdgeControl(timing, edge, name) { time -> custom = true; change(edge, time, true) }
            TextButton(onClick = { custom = true; change(EditorRangeTiming.Edge.End, timing.bounds.endInclusive, true) }, Modifier.heightIn(min = 44.dp)) {
                Text(stringResource(R.string.range_extend_to_end))
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, hint: String? = null, action: () -> Unit) {
    Box(
        modifier.heightIn(min = 44.dp).clip(ChipShape).background(if (selected) Accent.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.07f))
            .then(if (selected) Modifier.border(2.dp, Accent, ChipShape) else Modifier)
            .clickable(enabled = enabled, onClickLabel = hint, onClick = action)
            .semantics { role = Role.Button; this.selected = selected },
        Alignment.Center,
    ) {
        Text(label, color = if (enabled) Color.White else Color.White.copy(alpha = 0.35f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** One edge: its name, − a frame, its time, + a frame, and the slider on the ruler shared by both (YouRec/EditorDurationPicker.swift:144-179). */
@Composable
private fun EdgeControl(timing: EditorRangeTiming, edge: EditorRangeTiming.Edge, name: String, trim: (Double) -> Unit) {
    val value = timing.boundary(edge)
    val limits = timing.limits(edge)
    val title = stringResource(if (edge == EditorRangeTiming.Edge.Start) R.string.range_start else R.string.range_end)
    val earlier = stringResource(R.string.range_earlier, title)
    val later = stringResource(R.string.range_later, title)
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { trim(value - PauseDetector.FRAME) }, Modifier.size(44.dp).semantics { contentDescription = earlier }, enabled = EditTime.of(value) > EditTime.of(limits.start)) {
                Icon(Icons.Filled.Remove, null, tint = Color.White)
            }
            Text(preciseLabel(value), color = Color.White, fontSize = 14.sp)
            IconButton(onClick = { trim(value + PauseDetector.FRAME) }, Modifier.size(44.dp).semantics { contentDescription = later }, enabled = EditTime.of(value) < EditTime.of(limits.endInclusive)) {
                Icon(Icons.Filled.Add, null, tint = Color.White)
            }
        }
        val label = "$name ${title.lowercase()}"
        Slider(
            value = timing.fraction(edge).toFloat().coerceIn(0f, 1f),
            onValueChange = { trim(timing.time(it.toDouble(), edge)) },
            enabled = EditTime.of(limits.start) != EditTime.of(limits.endInclusive),
            modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = label; stateDescription = preciseLabel(value) },
        )
    }
}

/** How long a title or a picture stays, in the original's range editor (YouRec/EditorTools.swift:1670 at 57e5d91). */
@Composable
internal fun DurationPicker(range: ClosedFloatingPointRange<Double>, bounds: ClosedFloatingPointRange<Double>, change: (ClosedFloatingPointRange<Double>) -> Unit) {
    // All in seconds of the movie, as the original: a title may run across cuts and clips of any speed.
    val timing = EditorRangeTiming(range, bounds)
    EditorRangeDuration(timing, stringResource(R.string.range_duration)) { edge, time, _ -> change(timing.setting(edge, time).range) }
}

/**
 * The marked range's duration and edges, as the original's `SelectionDurationEditor` (YouRec/EditorDurationPicker.swift:304-346),
 * from Edit duration in the selection's menu: everything shown at once, Cancel, and Done, which marks the range edited.
 * [show] puts the frame an edge moved to under the playhead.
 */
@Composable
internal fun SelectionDurationDialog(
    range: ClosedFloatingPointRange<Double>, bounds: ClosedFloatingPointRange<Double>,
    show: (Double) -> Unit, cancel: () -> Unit, done: (ClosedFloatingPointRange<Double>) -> Unit,
) {
    var timing by remember { mutableStateOf(EditorRangeTiming(range, bounds)) }
    val name = stringResource(R.string.range_selected)
    AlertDialog(
        onDismissRequest = cancel,
        title = { Text(stringResource(R.string.range_duration_of, preciseLabel(timing.duration))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                EditorRangeDuration(timing, name, showsDisclosure = false) { edge, time, _ ->
                    timing = timing.setting(edge, time)
                    show(timing.previewTime(edge))
                }
            }
        },
        confirmButton = { TextButton(onClick = { done(timing.range) }) { Text(stringResource(R.string.effect_range_done)) } },
        dismissButton = { TextButton(onClick = cancel) { Text(stringResource(R.string.effect_range_cancel)) } },
    )
}
