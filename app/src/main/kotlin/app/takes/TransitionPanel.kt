package app.takes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.FilterNone
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.PausePresentation
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Tonality
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takes.core.EditorDurationPresets
import app.takes.core.PauseDetector
import app.takes.core.TransitionAudio
import app.takes.core.TransitionAxis
import app.takes.core.TransitionColor
import app.takes.core.TransitionDefaults
import app.takes.core.TransitionFraming
import app.takes.core.TransitionLayout
import app.takes.core.TransitionSettings
import app.takes.core.TransitionStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val Soft = Color(0xFFA0A4AB)
private val Accent = Color(0xFFFF9F0A)
private val Chosen = Accent.copy(alpha = 0.24f)
private val Plain = Color.White.copy(alpha = 0.07f)
private val Rounded = RoundedCornerShape(8.dp)
private const val FRAME = PauseDetector.FRAME

/** The style names of the core module, which only has them in English. */
val TransitionStyle.label: Int
    get() = when (this) {
        TransitionStyle.Dissolve -> R.string.transition_style_dissolve
        TransitionStyle.FadeColor -> R.string.transition_style_fade
        TransitionStyle.Wipe -> R.string.transition_style_wipe
        TransitionStyle.Split -> R.string.transition_style_split
        TransitionStyle.Hitchcock -> R.string.transition_style_hitchcock
        TransitionStyle.Whip -> R.string.transition_style_whip
        TransitionStyle.FlashCut -> R.string.transition_style_flash_cut
        TransitionStyle.Still -> R.string.transition_style_still
        TransitionStyle.Defocus -> R.string.transition_style_defocus
    }

/** The nearest picture to the symbol the original shows for each style. */
private val TransitionStyle.icon: ImageVector
    get() = when (this) {
        TransitionStyle.Dissolve -> Icons.Outlined.FilterNone
        TransitionStyle.FadeColor -> Icons.Outlined.Contrast
        TransitionStyle.Wipe -> Icons.Outlined.Flip
        TransitionStyle.Split -> Icons.Outlined.VerticalSplit
        TransitionStyle.Hitchcock -> Icons.Outlined.Tonality
        TransitionStyle.Whip -> Icons.Outlined.Air
        TransitionStyle.FlashCut -> Icons.Filled.Bolt
        TransitionStyle.Still -> Icons.Outlined.PausePresentation
        TransitionStyle.Defocus -> Icons.Outlined.Camera
    }

private val TransitionAxis.label: Int
    get() = if (this == TransitionAxis.Horizontal) R.string.transition_axis_horizontal else R.string.transition_axis_vertical

private val TransitionAudio.label: Int
    get() = when (this) {
        TransitionAudio.Crossfade -> R.string.transition_audio_crossfade
        TransitionAudio.First -> R.string.transition_audio_first
        TransitionAudio.Second -> R.string.transition_audio_second
    }

/** Seconds with up to two decimals, as the person reads them. */
private fun plain(value: Double): String {
    val text = String.format(Locale.getDefault(), "%.2f", value)
    return if (text.contains('.') || text.contains(',')) text.trimEnd('0').trimEnd('.', ',') else text
}

private fun fixed(value: Double): String = String.format(Locale.getDefault(), "%.2f", value)

private fun signed(value: Double): String = String.format(Locale.getDefault(), "%+.2f", value)

/** The colors offered for a fade, black first: the one the original starts from. */
private val fadeColors = listOf(0x000000, 0xFFFFFF, 0x808080, 0xE53935, 0xFF9F0A, 0xFFD60A, 0x34C759, 0x0A84FF, 0x5E5CE6, 0xBF5AF2).map {
    TransitionColor((it shr 16 and 0xFF) / 255.0, (it shr 8 and 0xFF) / 255.0, (it and 0xFF) / 255.0)
}

private fun TransitionColor.toCompose() = Color(red.toFloat(), green.toFloat(), blue.toFloat())

private fun TransitionColor.hex() = String.format(Locale.US, "%02X%02X%02X", (red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt())

/**
 * The Transition tool, in place of the editing tools so the picture stays in
 * view: the controls of the original's transition editor, in its order.
 * Everything happens on [settings], which the preview shows on the timeline
 * as it would be; [apply] makes it one undo step and [cancel] drops it.
 *
 * @param existing whether the join already has a transition, which can then be removed.
 * @param index the join: 0 is the opening of the timeline, [count] (the number of clips) its close.
 * @param join the join as the timeline lays it out with [settings], shortened to fit; null while what fits is under one frame.
 * @param maximum the longest duration the footage around the join allows.
 * @param lengthChange seconds the project gets longer (or, negative, shorter) with this transition.
 * @param audioMaximum how far the sound may be offset or blended.
 * @param step moves the tool to the join before (−1) or after (+1) this one.
 */
@Composable
fun TransitionPanel(
    settings: TransitionSettings, existing: Boolean, change: (TransitionSettings) -> Unit,
    index: Int, count: Int, join: TransitionLayout.Join?, maximum: Double, projectDuration: Double, lengthChange: Double, audioMaximum: Double,
    playing: Boolean, play: () -> Unit, step: (Int) -> Unit,
    apply: () -> Unit, remove: () -> Unit, cancel: () -> Unit, modifier: Modifier = Modifier,
) {
    val edge = TransitionLayout.edge(index, count)
    var choosingStyle by remember { mutableStateOf(false) }
    // The styles picked while the tool is open: a whip pan and a still get their own length the first time.
    var visited by remember(index) { mutableStateOf(setOf(settings.style)) }
    val style = settings.style
    val fitted = join?.timing?.videoDuration ?: 0.0
    val length = join?.let { it.range.endInclusive - it.range.start } ?: 0.0
    val lengthNote = when {
        abs(lengthChange) < 0.00001 -> stringResource(R.string.transition_length_unchanged, fixed(length))
        lengthChange < 0 -> stringResource(R.string.transition_length_shorter, fixed(length), fixed(-lengthChange))
        else -> stringResource(R.string.transition_length_longer, fixed(length), fixed(lengthChange))
    }

    Column(modifier.fillMaxWidth().background(Color(0xFF1B1D21))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = cancel) { Text(stringResource(R.string.common_cancel)) }
            Text(stringResource(R.string.transition_title), color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
            TextButton(onClick = apply, enabled = join != null) { Text(stringResource(R.string.transition_apply), fontWeight = FontWeight.SemiBold) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Which join, with a way to the ones beside it, and the transition played by itself.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val playLabel = stringResource(if (playing) R.string.transition_pause else R.string.transition_play)
                TextButton(onClick = play, enabled = join != null, modifier = Modifier.semantics { contentDescription = playLabel }) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null)
                }
                Text(
                    when (edge) {
                        -1 -> stringResource(R.string.transition_join_start)
                        1 -> stringResource(R.string.transition_join_end)
                        else -> stringResource(R.string.transition_join_between, index, index + 1)
                    },
                    color = Soft, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
                val before = stringResource(R.string.transition_previous_join)
                val after = stringResource(R.string.transition_next_join)
                TextButton(onClick = { step(-1) }, enabled = index > 0, modifier = Modifier.semantics { contentDescription = before }) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, null) }
                TextButton(onClick = { step(1) }, enabled = index < count, modifier = Modifier.semantics { contentDescription = after }) { Icon(Icons.AutoMirrored.Filled.NavigateNext, null) }
            }
            val styleTitle = stringResource(R.string.transition_style_title)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(Rounded).clickable(onClickLabel = styleTitle) { choosingStyle = true }.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(style.icon, null, tint = Accent)
                Text(stringResource(style.label), color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f).padding(start = 10.dp))
                Icon(Icons.Outlined.UnfoldMore, null, Modifier.size(16.dp), tint = Accent)
            }
            // Before Android 13 the preview has no shader for these styles; the export draws them in full.
            if (!shadedPreview && style in simplifiedWithoutShader) Note(stringResource(R.string.transition_preview_simplified))
            if (style == TransitionStyle.FadeColor) FadeColor(settings.resolvedFadeColor) { change(settings.copy(fadeColor = it.takeIf { color -> color != TransitionColor.BLACK })) }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val top = TransitionDefaults.durationMaximum(settings, maximum, projectDuration)
                DurationPicker(stringResource(if (style == TransitionStyle.Still) R.string.transition_freeze_duration else R.string.transition_blend_duration), settings.duration, top) {
                    change(settings.copy(duration = it))
                }
                // A neighbor one frame long leaves a single duration: nothing to adjust.
                val name = stringResource(R.string.transition_duration_slider)
                Slider(
                    value = settings.duration.coerceIn(FRAME, maxOf(FRAME, top)).toFloat(),
                    onValueChange = { change(settings.copy(duration = ((it / FRAME).roundToInt() * FRAME).coerceIn(FRAME, maxOf(FRAME, top)))) },
                    valueRange = FRAME.toFloat()..maxOf(2 * FRAME, top).toFloat(), enabled = top >= 2 * FRAME,
                    modifier = Modifier.height(36.dp).semantics { contentDescription = name },
                )
                if (join == null) Note(stringResource(R.string.transition_too_short), warning = true) else {
                    Note(lengthNote)
                    if (join.factor < 0.99999) Note(stringResource(R.string.transition_fitted, (join.factor * 100).roundToInt()), warning = true)
                }
            }
            if (style == TransitionStyle.Split || style == TransitionStyle.Whip) {
                Segments(stringResource(R.string.transition_layout), TransitionAxis.entries.map { stringResource(it.label) to it }, settings.axis) { change(settings.copy(axis = it)) }
            }
            if (style == TransitionStyle.Whip) Toggle(stringResource(R.string.transition_reverse), settings.reverse) { change(settings.copy(reverse = it)) }
            if (style == TransitionStyle.Wipe) {
                Segments(
                    stringResource(R.string.transition_edge),
                    listOf(stringResource(R.string.transition_edge_soft) to false, stringResource(R.string.transition_edge_hard) to true), settings.hardEdge ?: false,
                ) { change(settings.copy(hardEdge = it)) }
                // The slider gives the whole direction, so a saved reversal is folded into the angle.
                val angle = settings.angle.let { if (it > 180) it - 360 else it }
                Amount(stringResource(R.string.transition_angle), angle, -180.0..180.0, 1.0, "${settings.angle.roundToInt()}°") { change(settings.copy(wipeAngle = it, reverse = false)) }
            }
            if (style == TransitionStyle.Hitchcock) {
                Segments(
                    stringResource(R.string.transition_reveal),
                    listOf(stringResource(R.string.transition_shadows_first) to false, stringResource(R.string.transition_highlights_first) to true), settings.highlightsFirst ?: false,
                ) { change(settings.copy(highlightsFirst = it)) }
            }
            if (style == TransitionStyle.FlashCut) {
                val flashes = settings.flashCount ?: 3
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.transition_flashes, flashes), color = Color.White, modifier = Modifier.weight(1f))
                    val fewer = stringResource(R.string.transition_flashes_fewer)
                    val more = stringResource(R.string.transition_flashes_more)
                    OutlinedButton(onClick = { change(settings.copy(flashCount = flashes - 1)) }, enabled = flashes > 1, modifier = Modifier.semantics { contentDescription = fewer }) { Icon(Icons.Filled.Remove, null) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { change(settings.copy(flashCount = flashes + 1)) }, enabled = flashes < 20, modifier = Modifier.semantics { contentDescription = more }) { Icon(Icons.Filled.Add, null) }
                }
                Note(stringResource(R.string.transition_flashes_note))
                val acceleration = settings.flashAcceleration ?: 2.0
                Amount(stringResource(R.string.transition_acceleration), acceleration, 0.25..4.0, 0.05, String.format(Locale.getDefault(), "%.1f×", acceleration)) { change(settings.copy(flashAcceleration = it)) }
                val fit = settings.flashFrames(fitted).size
                if (join != null && fit < flashes) Note(pluralStringResource(R.plurals.transition_flashes_fit, fit, fit), warning = true)
            }
            if (style == TransitionStyle.Still) {
                val dissolves = (settings.stillDissolve ?: 0.0) > 0
                Segments(
                    stringResource(R.string.transition_after_freeze),
                    listOf(stringResource(R.string.transition_cut) to false, stringResource(R.string.transition_style_dissolve) to true), dissolves,
                ) { change(settings.copy(stillDissolve = if (it) TransitionDefaults.STILL_DISSOLVE else null)) }
                if (dissolves) Seconds(stringResource(R.string.transition_exit_dissolve), settings.stillDissolve ?: TransitionDefaults.STILL_DISSOLVE, FRAME..5.0) { change(settings.copy(stillDissolve = it)) }
                Note(stringResource(R.string.transition_still_note))
            }
            if (style == TransitionStyle.Defocus) {
                val blur = settings.blurAmount ?: 0.75
                Amount(stringResource(R.string.transition_optical_blur), blur, 0.1..1.0, 0.01, "${(blur * 100).toInt()}%") { change(settings.copy(blurAmount = it)) }
            }
            if (style.supportsHold) {
                val top = TransitionDefaults.durationMaximum(settings, maximum, projectDuration)
                Seconds(
                    stringResource(if (style == TransitionStyle.FadeColor && edge == 0) R.string.transition_hold_color else R.string.transition_hold_halfway),
                    settings.hold ?: 0.0, 0.0..TransitionDefaults.holdMaximum(top),
                ) { change(settings.copy(hold = it.takeIf { hold -> hold != 0.0 })) }
            }
            if (style == TransitionStyle.Split) {
                Note(stringResource(R.string.transition_split_note))
                Amount(stringResource(R.string.transition_divider), settings.divider, 0.15..0.85, 0.01, "${(settings.divider * 100).roundToInt()}%") { change(settings.copy(divider = it)) }
                Segments(stringResource(R.string.transition_audio), TransitionAudio.entries.map { stringResource(it.label) to it }, settings.audio) {
                    change(settings.copy(audio = it, audioOffset = null, audioBlend = null))
                }
                TextButton(onClick = { change(settings.copy(first = TransitionFraming(), second = TransitionFraming())) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(stringResource(R.string.transition_reset_framing))
                }
            }
            // A freeze cannot run its clip's sound on under the frozen picture in the preview, so its sound stays the cut it is.
            if (style != TransitionStyle.Still) AudioTiming(settings, edge, join, audioMaximum, lengthNote.takeIf { join != null }, change)
            if (existing) {
                OutlinedButton(onClick = remove, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) { Text(stringResource(R.string.transition_remove), color = RecordRed) }
                if (edge == 0) Note(stringResource(R.string.transition_remove_note))
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (choosingStyle) {
        AlertDialog(
            onDismissRequest = { choosingStyle = false },
            title = { Text(stringResource(R.string.transition_style_title)) },
            text = {
                // The original's order, two to a row; only the styles this version draws.
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (row in TransitionStyle.entries.filter { it in SceneDrawing.drawn }.chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (each in row) {
                            val name = stringResource(each.label)
                            Row(
                                Modifier.weight(1f).heightIn(min = 54.dp).clip(RoundedCornerShape(10.dp)).background(if (each == style) Chosen else Plain)
                                    .then(if (each == style) Modifier.border(1.dp, Accent, RoundedCornerShape(10.dp)) else Modifier)
                                    .clickable {
                                        change(TransitionDefaults.choosing(settings, each, visited))
                                        visited = visited + each
                                        choosingStyle = false
                                    }
                                    .semantics { selected = each == style; role = Role.Button; contentDescription = name }.padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(each.icon, null, Modifier.size(20.dp), tint = Color.White)
                                Text(name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                        repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingStyle = false }) { Text(stringResource(R.string.common_done)) } },
        )
    }
}

@Composable
private fun Note(text: String, warning: Boolean = false) {
    Text(text, color = if (warning) Accent else Soft, fontSize = 12.sp, lineHeight = 16.sp)
}

/** The choices of one setting side by side, under its name. */
@Composable
private fun <T> Segments(name: String, options: List<Pair<String, T>>, selected: T, choose: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, color = Soft, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(4.dp)) {
            for ((label, value) in options) {
                Box(
                    Modifier.weight(1f).heightIn(min = 44.dp).clip(Rounded).background(if (value == selected) Chosen else Plain).clickable { choose(value) }
                        .semantics { this.selected = value == selected; role = Role.Tab; contentDescription = "$name: $label" }.padding(4.dp),
                    Alignment.Center,
                ) { Text(label, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        Switch(on, set, Modifier.semantics { contentDescription = label })
    }
}

/** A slider with its name and value above it. */
@Composable
private fun Amount(name: String, value: Double, range: ClosedFloatingPointRange<Double>, step: Double, label: String, zero: Boolean = false, change: (Double) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(name, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(label, color = Soft, fontSize = 14.sp)
        }
        Box(contentAlignment = Alignment.Center) {
            Slider(
                value = value.toFloat().coerceIn(range.start.toFloat(), range.endInclusive.toFloat()),
                onValueChange = { change(((it / step).roundToInt() * step).coerceIn(range.start, range.endInclusive)) },
                valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
                modifier = Modifier.height(36.dp).semantics { contentDescription = name },
            )
            // The middle of a range that goes both ways.
            if (zero) Box(Modifier.width(1.dp).height(14.dp).background(Color.White.copy(alpha = 0.65f)))
        }
    }
}

/** A length in seconds, in steps of one frame. */
@Composable
private fun Seconds(name: String, value: Double, range: ClosedFloatingPointRange<Double>, change: (Double) -> Unit) =
    Amount(name, value, range, FRAME, stringResource(R.string.transition_duration_seconds, fixed(value)), zero = range.start < 0 && range.endInclusive == -range.start, change = change)

/** How long the transition is: a row that opens the original's presets; those that do not fit are dimmed. */
@Composable
private fun DurationPicker(title: String, seconds: Double, maximum: Double, select: (Double) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(Rounded).background(Plain).clickable { expanded = !expanded }
                .semantics { contentDescription = title }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp), tint = Color.White)
            Text(title, color = Color.White, modifier = Modifier.weight(1f).padding(start = 8.dp))
            Text(stringResource(R.string.transition_duration_seconds, plain(seconds)), color = Color.White)
            Text(if (expanded) "  ▴" else "  ▾", color = Soft, fontSize = 12.sp)
        }
        if (expanded) {
            for (row in EditorDurationPresets.transitionSeconds.chunked(5)) Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(6.dp)) {
                for (value in row) {
                    val fits = EditorDurationPresets.fits(value, maximum)
                    val chosen = abs(seconds - value) < 1e-4
                    Box(
                        Modifier.weight(1f).heightIn(min = 44.dp).clip(Rounded).background(if (chosen) Chosen else Plain)
                            .then(if (chosen) Modifier.border(1.dp, Accent, Rounded) else Modifier)
                            .clickable(enabled = fits) { select(value) }.semantics { selected = chosen; role = Role.Button },
                        Alignment.Center,
                    ) {
                        Text(stringResource(R.string.transition_duration_seconds, plain(value)), color = if (fits) Color.White else Soft.copy(alpha = 0.5f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Note(stringResource(R.string.transition_up_to, plain(maximum)))
        }
    }
}

/** The color a fade goes through: a few to tap, or any other as six hex digits. Black is the original's default. */
@Composable
private fun FadeColor(color: TransitionColor, choose: (TransitionColor) -> Unit) {
    var hex by remember(color) { mutableStateOf(color.hex()) }
    val title = stringResource(R.string.transition_fade_color)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, modifier = Modifier.weight(1f))
            Box(Modifier.size(28.dp).clip(CircleShape).background(color.toCompose()).border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape))
        }
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(4.dp), Alignment.CenterVertically) {
            for (each in fadeColors) {
                val name = stringResource(R.string.transition_fade_color_named, "#" + each.hex())
                Box(Modifier.weight(1f).height(44.dp).clickable { choose(each) }.semantics { contentDescription = name; role = Role.Button; selected = each == color }, Alignment.Center) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(each.toCompose()).border(if (each == color) 2.dp else 1.dp, if (each == color) Accent else Color.White.copy(alpha = 0.4f), CircleShape))
                }
            }
        }
        val hexName = stringResource(R.string.transition_color_hex)
        OutlinedTextField(
            hex, { typed ->
                hex = typed.filter { it.isLetterOrDigit() }.take(6).uppercase()
                hex.takeIf { it.length == 6 }?.toIntOrNull(16)?.let { choose(TransitionColor((it shr 16 and 0xFF) / 255.0, (it shr 8 and 0xFF) / 255.0, (it and 0xFF) / 255.0)) }
            },
            Modifier.fillMaxWidth().semantics { contentDescription = "$title $hexName" }, label = { Text(hexName) }, placeholder = { Text("RRGGBB") }, singleLine = true,
        )
    }
}

/**
 * When the sound changes over, apart from the picture: a start offset
 * (before the picture for a J-cut, after it for an L-cut) and the length of
 * the blend, with the original's small steps and its diagram of the two.
 */
@Composable
private fun AudioTiming(settings: TransitionSettings, edge: Int, join: TransitionLayout.Join?, maximum: Double, lengthNote: String?, change: (TransitionSettings) -> Unit) {
    val offset = settings.audioOffset ?: 0.0
    val blend = TransitionDefaults.displayedAudioBlend(settings, edge)
    @Composable
    fun Steps(earlier: String, middle: String, middleName: String, later: String, back: () -> Unit, reset: () -> Unit, forward: () -> Unit) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            OutlinedButton(onClick = back, modifier = Modifier.semantics { contentDescription = earlier }) { Icon(Icons.Filled.Remove, null, Modifier.size(16.dp)); Text(" 0.1 s") }
            OutlinedButton(onClick = reset, modifier = Modifier.semantics { contentDescription = middleName }) { Text(middle) }
            OutlinedButton(onClick = forward, modifier = Modifier.semantics { contentDescription = later }) { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)); Text(" 0.1 s") }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.GraphicEq, null, Modifier.size(18.dp), tint = Color.White)
            Text(stringResource(R.string.transition_audio_timing), color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 8.dp))
            TextButton(onClick = { change(settings.copy(audioOffset = null, audioBlend = null, audio = TransitionAudio.Crossfade)) }) { Text(stringResource(R.string.common_reset)) }
        }
        Seconds(stringResource(R.string.transition_audio_offset), offset, -maximum..maximum) { change(settings.copy(audioOffset = it, audio = TransitionAudio.Crossfade)) }
        Steps(
            stringResource(R.string.transition_audio_earlier), "0 s", stringResource(R.string.transition_audio_align), stringResource(R.string.transition_audio_later),
            back = { change(settings.copy(audioOffset = maxOf(-maximum, offset - 0.1), audio = TransitionAudio.Crossfade)) },
            reset = { change(settings.copy(audioOffset = 0.0, audio = TransitionAudio.Crossfade)) },
            forward = { change(settings.copy(audioOffset = minOf(maximum, offset + 0.1), audio = TransitionAudio.Crossfade)) },
        )
        Note(stringResource(if (offset < 0) R.string.transition_audio_before else if (offset > 0) R.string.transition_audio_after else R.string.transition_audio_follows))
        Seconds(stringResource(R.string.transition_audio_blend), blend, 0.0..maxOf(maximum, blend)) { change(settings.copy(audioBlend = it, audio = TransitionAudio.Crossfade)) }
        Steps(
            stringResource(R.string.transition_audio_shorter), stringResource(R.string.transition_cut), stringResource(R.string.transition_audio_cut), stringResource(R.string.transition_audio_longer),
            back = { change(settings.copy(audioBlend = maxOf(0.0, blend - 0.1), audio = TransitionAudio.Crossfade)) },
            reset = { change(settings.copy(audioBlend = 0.0, audio = TransitionAudio.Crossfade)) },
            forward = { change(settings.copy(audioBlend = minOf(TransitionSettings.MAXIMUM_DURATION, blend + 0.1), audio = TransitionAudio.Crossfade)) },
        )
        if (!settings.customAudio && settings.style == TransitionStyle.FadeColor) Note(stringResource(R.string.transition_audio_default_note))
        if (settings.style == TransitionStyle.Split && !settings.customAudio && settings.audio != TransitionAudio.Crossfade) {
            Note(stringResource(R.string.transition_audio_split_note, stringResource(settings.audio.label)))
        }
        if (join != null) {
            // Picture above, sound below, on the stretch the transition takes; the line is where the picture starts.
            val lower = join.range.start
            val span = maxOf(FRAME, join.range.endInclusive - lower)
            val sound = when (edge) {
                -1 -> join.audioIn
                1 -> join.audioOut
                else -> minOf(join.audioIn.start, join.audioOut.start)..maxOf(join.audioIn.endInclusive, join.audioOut.endInclusive)
            }
            val diagram = stringResource(R.string.transition_audio_diagram_label)
            Column(Modifier.clearAndSetSemantics { contentDescription = diagram }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((icon, range, color) in listOf(Triple(Icons.Filled.Movie, join.videoRange, Accent), Triple(Icons.Filled.GraphicEq, sound, Color(0xFF32ADE6)))) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, Modifier.size(18.dp), tint = Soft)
                        Canvas(Modifier.padding(start = 8.dp).weight(1f).height(7.dp)) {
                            val round = CornerRadius(size.height / 2)
                            drawRoundRect(Color.White.copy(alpha = 0.07f), cornerRadius = round)
                            val from = (size.width * (range.start - lower) / span).toFloat()
                            val wide = maxOf(2f, (size.width * (range.endInclusive - range.start) / span).toFloat())
                            drawRoundRect(color, Offset(from, 0f), Size(wide, size.height), round)
                            val start = (size.width * (join.origin - lower) / span).toFloat()
                            drawRect(Color.White.copy(alpha = 0.4f), Offset(start, -3.dp.toPx()), Size(1.dp.toPx(), size.height + 6.dp.toPx()))
                        }
                    }
                }
                Text(stringResource(R.string.transition_audio_diagram, signed(sound.start - join.origin), signed(sound.endInclusive - join.origin)), color = Soft, fontSize = 11.sp)
            }
        }
        if (lengthNote != null) Note(lengthNote)
    }
}
