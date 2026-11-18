@file:OptIn(ExperimentalFoundationApi::class)

package app.takes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.takes.core.EditorTool
import app.takes.core.ToolRegion
import app.takes.core.ToolRegionLabel
import java.util.Locale

/**
 * The tint of each tool, as the original's (YouRec/EditorTools.swift:6-8), in iOS's dark-mode system colours:
 * cyan for subtitles, effects and adding video, purple for titles, blue for speed, pink for colour, mint for the
 * sound tools and stabilisation, white for pauses, indigo for crop, teal for mirror, orange for images,
 * transitions and placement.
 */
internal fun toolTint(tool: EditorTool): Color = when (tool) {
    EditorTool.Subtitles, EditorTool.VideoEffects -> Color(0xFF64D2FF)
    EditorTool.Text -> Color(0xFFBF5AF2)
    EditorTool.Speed -> Color(0xFF0A84FF)
    EditorTool.Color -> Color(0xFFFF375F)
    EditorTool.Stabilize, EditorTool.EnhanceAudio, EditorTool.Volume, EditorTool.CensorAudio -> Color(0xFF66D4CF)
    EditorTool.Pauses -> Color.White
    EditorTool.Crop -> Color(0xFF5E5CE6)
    EditorTool.Mirror -> Color(0xFF40C8E0)
    EditorTool.Image, EditorTool.Transition, EditorTool.Placement -> Color(0xFFFF9F0A)
}

/** The tool's name, as the original's titles (YouRec/VideoTools.swift). */
@Composable
internal fun toolName(tool: EditorTool): String = stringResource(
    when (tool) {
        EditorTool.Subtitles -> R.string.editor_subtitles
        EditorTool.Mirror -> R.string.region_mirror
        EditorTool.Image -> R.string.image_tool
        EditorTool.VideoEffects -> R.string.editor_effects
        EditorTool.Crop -> R.string.editor_crop
        EditorTool.Speed -> R.string.editor_speed
        EditorTool.Color -> R.string.editor_color
        EditorTool.Stabilize -> R.string.region_stabilize
        EditorTool.Pauses -> R.string.editor_pauses
        EditorTool.Text -> R.string.editor_title
        EditorTool.CensorAudio -> R.string.region_censor
        EditorTool.EnhanceAudio -> R.string.enhance_title
        EditorTool.Volume -> R.string.region_volume
        EditorTool.Placement -> R.string.placement_title
        EditorTool.Transition -> R.string.editor_transition
    },
)

/** A region's name, as the original's labels (YouRec/VideoTools.swift:567-627). */
@Composable
internal fun regionLabel(region: ToolRegion): String = when (val label = region.label) {
    ToolRegionLabel.Tool -> toolName(region.tool)
    is ToolRegionLabel.Text -> label.text
    is ToolRegionLabel.Flip -> stringResource(
        if (label.horizontal && label.vertical) R.string.region_flip_both else if (label.horizontal) R.string.region_flip_horizontal else R.string.region_flip_vertical,
    )
    is ToolRegionLabel.Framing -> when {
        label.turned -> stringResource(R.string.region_crop_turned, decimals(label.rotation, 0, 1))
        label.zoom == 1.0 -> stringResource(R.string.region_fill)
        else -> stringResource(R.string.region_rate, decimals(label.zoom, 1, 1))
    }
    is ToolRegionLabel.Rate -> stringResource(R.string.region_rate, decimals(label.rate, 0, 2))
    is ToolRegionLabel.Grade -> label.color.label()
    is ToolRegionLabel.Effect -> effectName(label.kind)
    is ToolRegionLabel.Levels -> listOfNotNull(
        if (label.muted) stringResource(R.string.sound_muted) else label.volumeDB.takeIf { it != 0.0 }?.let { String.format(Locale.getDefault(), "%+.1f dB", it) },
        stringResource(R.string.sound_fade_in).takeIf { label.fadeIn },
        stringResource(R.string.sound_fade_out).takeIf { label.fadeOut },
    ).joinToString(" · ")
    is ToolRegionLabel.Placement -> stringResource(if (label.default) R.string.placement_default else R.string.placement_title)
    is ToolRegionLabel.Transition -> stringResource(label.style.label)
}

/** [value] written with between [least] and [most] decimals, as Swift's `.fractionLength(least...most)`. */
private fun decimals(value: Double, least: Int, most: Int): String {
    val format = java.text.NumberFormat.getNumberInstance(Locale.getDefault())
    format.minimumFractionDigits = least
    format.maximumFractionDigits = most
    return format.format(value)
}

/**
 * A chip under the timeline for something a tool left at the playhead, as the original's `EffectRemovalBubble`
 * (YouRec/EditorView.swift:1676-1710): a tap opens it in its tool; a touch and hold, never an edit by itself, shows
 * a bin and a cross beside the name, and the bin takes it away. For a screen reader the chip is "Edit …", with
 * Remove as an action. [adds] draws a plus in place of the tool's sign, for a transition not made yet.
 */
/** One entry of an effect's options (YouRec/EditorView.swift:2347-2373): its words, its sign, whether it takes something away. */
internal class ChipOption(val label: String, val icon: ImageVector, val destructive: Boolean = false, val action: () -> Unit)

/**
 * An effect's chip with its options, as the original's `EffectRemovalBubble` since 57e5d91
 * (YouRec/EditorView.swift:2299-2374): a tap edits it, a touch and hold or the "…" beside its name opens the options,
 * worked out by [options] when they open.
 */
@Composable
internal fun EffectOptionsChip(label: String, tint: Color, description: String, edit: () -> Unit, options: () -> List<ChipOption>) {
    val haptics = LocalHapticFeedback.current
    var open by remember { mutableStateOf(false) }
    val optionsLabel = stringResource(R.string.effect_options, label)
    val optionsAction = stringResource(R.string.effect_options_action)
    Box {
        Row(Modifier.clip(CircleShape).background(tint.copy(alpha = if (open) 0.22f else 0.12f)).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.heightIn(min = 44.dp)
                    .combinedClickable(
                        onClick = edit,
                        onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); open = true },
                        onLongClickLabel = optionsAction,
                    )
                    .clearAndSetSemantics {
                        contentDescription = description
                        role = Role.Button
                        onClick { edit(); true }
                        customActions = listOf(CustomAccessibilityAction(optionsAction) { open = true; true })
                    }
                    .padding(start = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp), tint = tint)
                Text(label, Modifier.padding(start = 4.dp).widthIn(max = 180.dp), color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.width(1.dp).height(16.dp).background(tint.copy(alpha = 0.32f)))
            IconButton(onClick = { open = true }, Modifier.size(44.dp).semantics { contentDescription = optionsLabel }) {
                Icon(Icons.Filled.MoreHoriz, null, tint = tint)
            }
        }
        if (open) {
            val shown = remember { options() }
            DropdownMenu(expanded = true, onDismissRequest = { open = false }) {
                Row(Modifier.widthIn(min = 260.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val closeLabel = stringResource(R.string.effect_options_close)
                    IconButton(onClick = { open = false }, Modifier.size(44.dp).semantics { contentDescription = closeLabel }) { Icon(Icons.Filled.Close, null) }
                }
                for (option in shown) {
                    val color = if (option.destructive) RecordRed else MaterialTheme.colorScheme.onSurface
                    DropdownMenuItem(
                        text = { Text(option.label, color = color) },
                        leadingIcon = { Icon(option.icon, null, tint = color) },
                        onClick = { open = false; option.action() },
                        modifier = Modifier.heightIn(min = 44.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun EffectChipButton(
    label: String, tint: Color, armed: Boolean, edit: () -> Unit, arm: (() -> Unit)?, cancel: () -> Unit, remove: () -> Unit,
    description: String = stringResource(R.string.effect_chip_edit, label), adds: Boolean = false, iconOnly: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val removeLabel = stringResource(R.string.effect_chip_remove, label)
    val holdLabel = stringResource(R.string.effect_chip_hold)
    val shape = CircleShape
    Row(
        Modifier.clip(shape).background(tint.copy(alpha = if (armed) 0.22f else 0.12f)).heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.heightIn(min = 44.dp)
                .combinedClickable(
                    onClick = { if (!armed) edit() },
                    onLongClick = arm?.takeIf { !armed }?.let { { haptics.performHapticFeedback(HapticFeedbackType.LongPress); it() } },
                    onLongClickLabel = holdLabel,
                )
                .clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Button
                    onClick { edit(); true }
                    if (arm != null) customActions = listOf(CustomAccessibilityAction(removeLabel) { arm(); true })
                }
                .padding(horizontal = if (iconOnly) 10.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (adds) Icons.Filled.Add else Icons.Outlined.Tune, null, Modifier.size(18.dp), tint = tint)
            if (!iconOnly) Text(label, Modifier.padding(start = 4.dp).widthIn(max = 180.dp), color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (armed) {
            IconButton(onClick = remove, Modifier.size(44.dp).semantics { contentDescription = removeLabel }) {
                Icon(Icons.Filled.Delete, null, tint = RecordRed)
            }
            val cancelLabel = stringResource(R.string.effect_chip_cancel)
            IconButton(onClick = cancel, Modifier.size(44.dp).semantics { contentDescription = cancelLabel }) {
                Icon(Icons.Filled.Close, null, tint = Color.White)
            }
        }
    }
}
