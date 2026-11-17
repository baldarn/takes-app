package app.takes

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.HighlightAlt
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takes.core.EditState
import app.takes.core.EditTargets
import app.takes.core.RangeMarks
import app.takes.core.TimeRange
import app.takes.core.TimelinePasteTarget
import app.takes.core.TrackComponents
import kotlin.math.roundToInt

/** The marked range and the selection on the timeline. */
internal val RangeColor = Color(0xFFFFD60A)

/** What Copy, Cut and Paste are drawn in. */
private val ClipboardColor = Color(0xFF64D2FF)
private val Quiet = Color(0xFFA0A4AB)

/** A sound out of sync with its picture. */
private val SyncColor = Color(0xFFFF6961)

/** Restore sync for the one sound selected, [shown] its offset as the original writes it (+0.50). */
internal class SyncAction(val shown: String, val restore: () -> Unit)

/** What a transition is offered in: the original's orange. */
private val TransitionColor = Color(0xFFFF9F0A)

/**
 * What the marks show on the timeline: the stretch of time, marked or still
 * open at the playhead, and the items it is limited to. Without a stretch,
 * [targets] are the items selected whole.
 */
internal data class MarkedView(val range: TimeRange?, val targets: EditTargets?) {
    fun clip(id: String) = targets == null || id in targets.clips
    fun audio(id: String) = targets == null || id in targets.audio

    companion object {
        /** Null when nothing is marked or selected. */
        fun of(marks: RangeMarks, playhead: Double): MarkedView? {
            val range = marks.range ?: marks.start?.let { TimeRange(minOf(it, playhead), maxOf(it, playhead)) }
            return when {
                range != null -> MarkedView(range, marks.rangeTargets)
                !marks.selected.isEmpty -> MarkedView(null, marks.selected)
                else -> null
            }
        }
    }
}

/**
 * The mark on one item of a timeline row that begins at [start] and ends at
 * [end]: the part of it inside the range, shaded and outlined, or an outline
 * around all of it when it is selected whole.
 */
@Composable
internal fun MarkedPart(view: MarkedView, start: Double, end: Double, pixelsPerSecond: Float, height: Dp) {
    val from = maxOf(start, view.range?.start ?: start)
    val to = minOf(end, view.range?.end ?: end)
    if (to <= from) return
    val width = with(LocalDensity.current) { ((to - from) * pixelsPerSecond).toFloat().toDp() }
    val shape = RoundedCornerShape(if (view.range == null) 4.dp else 0.dp)
    Box(
        Modifier.offset { IntOffset((from * pixelsPerSecond).roundToInt(), 0) }.width(width).height(height)
            .then(if (view.range == null) Modifier else Modifier.background(RangeColor.copy(alpha = 0.16f)))
            .border(2.dp, RangeColor, shape),
    )
}

/** Dashed lines at the two ends of the range, down a timeline row. */
@Composable
internal fun RangeGuides(range: TimeRange, pixelsPerSecond: Float, modifier: Modifier) {
    Canvas(modifier) {
        val dashes = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))
        for (time in setOf(range.start, range.end)) {
            val x = (time * pixelsPerSecond).toFloat()
            drawLine(RangeColor.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx(), pathEffect = dashes)
        }
    }
}

/** The items the marks are about, named as the original names them: V1 + V2, ♪1. */
@Composable
internal fun selectionTitle(edit: EditState, targets: EditTargets?, names: Map<String, String>): String {
    val clips = edit.clips.withIndex().filter { targets == null || it.value.id in targets.clips }
    val sounds = edit.audio.orEmpty().withIndex().filter { targets == null || it.value.id in targets.audio }
    val labels = clips.map { "V${it.index + 1}" } + sounds.map { "♪${it.index + 1}" }
    if (labels.size == 1 && sounds.size == 1) names[sounds[0].value.sourceID]?.let { return labels[0] + " · " + it }
    if (labels.size <= 4) return labels.joinToString(" + ")
    val kind = stringResource(if (clips.isEmpty()) R.string.selection_audio else if (sounds.isEmpty()) R.string.selection_video else R.string.selection_video_audio)
    return pluralStringResource(R.plurals.selection_items, labels.size, kind, labels.size)
}

/** How many items are selected: 2 videos + 1 audio part. */
@Composable
internal fun selectionCount(targets: EditTargets): String = selectionCount(targets.clips.size, targets.audio.size)

/**
 * How many pictures and sounds [targets] hold on [edit], with what they are
 * linked to: a clip with its own sound is 1 video + 1 audio part, as the
 * original counts it (TrackTimeline.swift:236-239). That says what the
 * selection holds: video and audio, video, or audio.
 */
@Composable
internal fun selectionCount(edit: EditState, targets: EditTargets): String {
    val held = remember(edit, targets) { TrackComponents.selection(edit, targets) }
    return selectionCount(held.video.size, held.audio.size)
}

@Composable
private fun selectionCount(videos: Int, sounds: Int): String = listOfNotNull(
    videos.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.selection_videos, it, it) },
    sounds.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.selection_audio_parts, it, it) },
).joinToString(" + ")

/**
 * A transition offered in the row under the timeline, as the original offers
 * it there: one to add on the join the playhead is on, or the one the
 * playhead is on or inside, to open.
 */
internal class TransitionChip(
    val label: String, val description: String, val adds: Boolean, val color: Color = TransitionColor,
    /** What a touch and hold offers to take away (YouRec/EditorView.swift:1676-1710), by the region's [id]; null when it cannot be. */
    val id: String? = null, val remove: (() -> Unit)? = null,
    val open: () -> Unit,
)

/**
 * The row between the timeline and the tools. Mark is always there. Beside it,
 * while something is marked or selected, what can be done with it; otherwise
 * Paste when the clipboard holds something, then the [transitions] at the
 * playhead and [placement], or without any the last thing that happened.
 * Beside a selection, the Placement of the pictures playing at the playhead
 * stays, as a sign alone after Paste (EditorView.swift:665, 800-802).
 * An action that is null is not offered. With [unavailable], Copy, Cut and
 * Paste are drawn as off and a tap on them gives the reason to [explain].
 */
@Composable
internal fun SelectionBar(
    step: RangeMarks.Step, mark: (() -> Unit)?,
    title: String?, detail: String, message: String,
    clear: () -> Unit,
    preview: (() -> Unit)? = null, delete: (() -> Unit)? = null,
    copy: (() -> Unit)? = null, cut: (() -> Unit)? = null, canCopy: Boolean = true,
    paste: (() -> Unit)? = null, canPaste: Boolean = true, replacesTransition: Boolean = false, clearClipboard: () -> Unit = {},
    pasteTargets: List<PasteChoice> = emptyList(),
    editSound: (() -> Unit)? = null,
    transitions: List<TransitionChip> = emptyList(),
    placement: TransitionChip? = null,
    /** What the tools left at the playhead, after the transitions and Placement (EditorView.swift:803-826). */
    effects: List<TransitionChip> = emptyList(),
    unavailable: String? = null, explain: (String) -> Unit = {},
    deleteLabel: Int = R.string.selection_delete, restoreSync: SyncAction? = null,
) {
    val because = unavailable?.let { reason -> { explain(reason) } }
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val markLabel = stringResource(when (step) {
            RangeMarks.Step.MarkStart -> R.string.range_mark_start
            RangeMarks.Step.MarkEnd -> R.string.range_mark_end
            RangeMarks.Step.MoveEnd -> R.string.range_move_end
        })
        TextButton(onClick = { mark?.invoke() }, Modifier.semantics { contentDescription = markLabel }, enabled = mark != null, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Icon(if (step == RangeMarks.Step.MarkStart) Icons.Outlined.HighlightAlt else Icons.Filled.Check, null, Modifier.size(18.dp))
            // The short names while the actions need the room, as the original's compact tools.
            val short = if (step == RangeMarks.Step.MarkStart) R.string.range_start_short else R.string.range_end_short
            Text(if (title == null) markLabel else stringResource(short), Modifier.padding(start = 4.dp), maxLines = 1)
        }
        if (title != null) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                var menu by remember { mutableStateOf(false) }
                val actionsLabel = stringResource(R.string.selection_actions, title)
                Box {
                    TextButton(onClick = { menu = true }, Modifier.semantics { contentDescription = actionsLabel }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Column(Modifier.widthIn(min = 44.dp, max = 84.dp)) {
                            Text(title, color = Color.White, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(detail, color = Quiet, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        @Composable
                        fun item(label: Int, icon: ImageVector, enabled: Boolean = true, action: () -> Unit) = DropdownMenuItem(
                            text = { Text(stringResource(label)) }, onClick = { menu = false; action() }, enabled = enabled, leadingIcon = { Icon(icon, null) },
                        )
                        copy?.let { item(R.string.clipboard_copy, Icons.Outlined.ContentCopy, canCopy, because ?: it) }
                        cut?.let { item(R.string.clipboard_cut, Icons.Outlined.ContentCut, canCopy, because ?: it) }
                        if (paste != null) item(R.string.clipboard_clear, Icons.Filled.Close, action = clearClipboard)
                        restoreSync?.let { sync ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sync_restore, sync.shown)) }, onClick = { menu = false; sync.restore() },
                                leadingIcon = { Icon(Icons.Filled.Sync, null) },
                            )
                        }
                        preview?.let { item(R.string.range_preview_cut, Icons.Outlined.Slideshow, action = it) }
                    }
                }
                // The one sound selected plays apart from its picture: by how much, and Restore sync (EditorView.swift:649-655).
                restoreSync?.let { sync ->
                    val description = stringResource(R.string.sync_restore_description, sync.shown)
                    TextButton(onClick = sync.restore, Modifier.semantics { contentDescription = description }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Icon(Icons.Filled.Sync, null, Modifier.size(18.dp), tint = SyncColor)
                        Text(stringResource(R.string.sync_badge, sync.shown), Modifier.padding(start = 2.dp), color = SyncColor, fontSize = 12.sp, maxLines = 1)
                    }
                }
                preview?.let { BarIcon(Icons.Outlined.Slideshow, R.string.range_preview_cut, Color.White, it) }
                copy?.let { ClipboardButton(Icons.Outlined.ContentCopy, R.string.clipboard_copy, R.string.clipboard_copy_selection, canCopy, because ?: it, off = because != null) }
                cut?.let { ClipboardButton(Icons.Outlined.ContentCut, R.string.clipboard_cut, R.string.clipboard_cut_selection, canCopy, because ?: it, off = because != null) }
                delete?.let { BarIcon(Icons.Outlined.Delete, deleteLabel, RecordRed, it) }
                editSound?.let { BarIcon(Icons.Outlined.Tune, R.string.editor_edit_sound, Color.White, it) }
                BarIcon(Icons.Filled.Close, R.string.selection_clear, Color.White.copy(alpha = 0.8f), clear)
                paste?.let { PasteButton(canPaste, replacesTransition, because ?: it, off = because != null, pasteTargets.takeIf { because == null }.orEmpty()) }
                placement?.let { chip ->
                    TextButton(
                        onClick = chip.open, Modifier.defaultMinSize(minWidth = 44.dp).semantics { contentDescription = chip.description }, shape = CircleShape,
                        colors = ButtonDefaults.textButtonColors(containerColor = chip.color.copy(alpha = 0.12f), contentColor = chip.color),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) { Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp)) }
                }
            }
        } else {
            paste?.let {
                PasteButton(canPaste, replacesTransition, because ?: it, off = because != null, pasteTargets.takeIf { because == null }.orEmpty())
                BarIcon(Icons.Filled.Close, R.string.clipboard_clear, Color.White.copy(alpha = 0.8f), clearClipboard)
            }
            val chips = transitions + listOfNotNull(placement) + effects
            // A chip held for removal stands alone until it is removed or let go, as in the original (EditorView.swift:639-640).
            var armed by remember { mutableStateOf<String?>(null) }
            val shown = chips.firstOrNull { it.id != null && it.id == armed }?.let(::listOf) ?: chips
            if (shown.isEmpty()) Text(message, Modifier.weight(1f).padding(horizontal = 8.dp), color = Quiet, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 4.dp), Arrangement.spacedBy(6.dp), Alignment.CenterVertically) {
                for (chip in shown) {
                    val held = chip.id != null && chip.id == armed
                    EffectChipButton(
                        chip.label, chip.color, held, edit = { armed = null; chip.open() },
                        arm = chip.remove?.let { { armed = chip.id } }, cancel = { armed = null },
                        remove = { armed = null; chip.remove?.invoke() }, description = chip.description, adds = chip.adds,
                    )
                }
            }
        }
    }
}

@Composable
private fun BarIcon(icon: ImageVector, label: Int, tint: Color, action: () -> Unit) {
    TextButton(onClick = action, Modifier.defaultMinSize(minWidth = 40.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
        Icon(icon, stringResource(label), Modifier.size(22.dp), tint = tint)
    }
}

/** Copy or Cut: the sign over its name, as in the original. */
@Composable
private fun ClipboardButton(icon: ImageVector, name: Int, label: Int, enabled: Boolean, action: () -> Unit, off: Boolean = false) {
    val description = stringResource(label)
    val unavailable = stringResource(R.string.unavailable_state)
    val tint = if (enabled && !off) ClipboardColor else Quiet
    TextButton(onClick = action, Modifier.defaultMinSize(minWidth = 44.dp).semantics { contentDescription = description; if (off) stateDescription = unavailable }, enabled = enabled, contentPadding = PaddingValues(horizontal = 4.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, null, Modifier.size(18.dp), tint = tint)
            Text(stringResource(name), color = if (enabled && !off) Color.White else Quiet, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** A place Paste offers at the playhead, by its name ([app.takes.core.TimelinePasteTarget.labelKey]). */
internal class PasteChoice(val label: String, val paste: () -> Unit)

/**
 * Paste, as the original's (EditorView.swift:775-798): with the playhead on
 * several places at once, a cut of the picture and one of the sound, a menu
 * of them; "Replaces transition" under it when pasting there undoes one.
 */
@Composable
private fun PasteButton(enabled: Boolean, replacesTransition: Boolean, action: () -> Unit, off: Boolean = false, choices: List<PasteChoice> = emptyList()) {
    val tint = if (enabled && !off) ClipboardColor else Quiet
    val unavailable = stringResource(R.string.unavailable_state)
    val replaces = stringResource(R.string.clipboard_replaces_transition)
    var menu by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { if (choices.size > 1) menu = true else action() },
            Modifier.semantics { if (off) stateDescription = unavailable else if (replacesTransition) stateDescription = replaces },
            enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp),
        ) {
            Icon(Icons.Outlined.ContentPaste, null, Modifier.size(18.dp), tint = tint)
            Column(Modifier.padding(start = 4.dp)) {
                Text(stringResource(R.string.clipboard_paste), color = tint, maxLines = 1, lineHeight = 16.sp)
                if (replacesTransition) Text(replaces, color = tint, fontSize = 10.sp, lineHeight = 11.sp, maxLines = 1)
            }
        }
        DropdownMenu(menu && choices.size > 1, onDismissRequest = { menu = false }) {
            for (choice in choices) DropdownMenuItem(text = { Text(choice.label) }, onClick = { menu = false; choice.paste() })
        }
    }
}

/** A place to paste as the original names it (TimelinePaste.swift:38-52): Start, End, Gap, or the two items either side, "Video · V2 | V3". */
@Composable
internal fun pasteLabel(label: TimelinePasteTarget.Label): String {
    fun number(prefix: String, n: Int) = if (n > 0) "$prefix$n" else prefix
    return when (label.key) {
        TimelinePasteTarget.Label.Key.Start -> stringResource(R.string.paste_target_start)
        TimelinePasteTarget.Label.Key.End -> stringResource(R.string.paste_target_end)
        TimelinePasteTarget.Label.Key.Gap -> stringResource(R.string.paste_target_gap)
        TimelinePasteTarget.Label.Key.Video -> stringResource(R.string.paste_target_video, number("V", label.left), number("V", label.right))
        TimelinePasteTarget.Label.Key.Audio -> stringResource(R.string.paste_target_audio, number("A", label.left), number("A", label.right))
    }
}
