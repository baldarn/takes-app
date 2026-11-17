@file:OptIn(ExperimentalMaterial3Api::class)

package app.takes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PictureInPicture
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Vrpano
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takes.core.EditorTool
import app.takes.core.ToolRegion

private val Muted = Color(0xFFA0A4AB)
private val SheetFill = Color(0xFF0F0F0F)

/** The tools the sheet offers, in the original's order (YouRec/EditorTools.swift:37-62). */
internal enum class SheetTool { AddVideo, AddAudio, Title, Image, Effects, Stabilize, Crop, Color, Speed, Pauses, Enhance, Censor, Subtitles }

/** One tool of the sheet: whether it can be used now, and what opens it once the sheet has closed. */
internal class SheetEntry(val enabled: Boolean, val open: () -> Unit)

/** The sign of each tool, standing in for the original's symbols. */
internal fun toolIcon(tool: EditorTool): ImageVector = when (tool) {
    EditorTool.Subtitles -> Icons.Outlined.Subtitles
    EditorTool.Mirror -> Icons.Outlined.Flip
    EditorTool.Image -> Icons.Outlined.Image
    EditorTool.VideoEffects -> Icons.Outlined.AutoAwesome
    EditorTool.Crop -> Icons.Outlined.Crop
    EditorTool.Speed -> Icons.Outlined.Speed
    EditorTool.Color -> Icons.Outlined.Palette
    EditorTool.Stabilize -> Icons.Outlined.Vrpano
    EditorTool.Pauses -> Icons.Outlined.ContentCut
    EditorTool.Text -> Icons.Outlined.Title
    EditorTool.CensorAudio -> Icons.Outlined.VolumeOff
    EditorTool.EnhanceAudio -> Icons.Outlined.GraphicEq
    EditorTool.Volume -> Icons.Outlined.VolumeUp
    EditorTool.Placement -> Icons.Outlined.PictureInPicture
    EditorTool.Transition -> Icons.Outlined.SwapHoriz
}

private fun SheetTool.tool(): EditorTool? = when (this) {
    SheetTool.AddVideo, SheetTool.AddAudio -> null
    SheetTool.Title -> EditorTool.Text
    SheetTool.Image -> EditorTool.Image
    SheetTool.Effects -> EditorTool.VideoEffects
    SheetTool.Stabilize -> EditorTool.Stabilize
    SheetTool.Crop -> EditorTool.Crop
    SheetTool.Color -> EditorTool.Color
    SheetTool.Speed -> EditorTool.Speed
    SheetTool.Pauses -> EditorTool.Pauses
    SheetTool.Enhance -> EditorTool.EnhanceAudio
    SheetTool.Censor -> EditorTool.CensorAudio
    SheetTool.Subtitles -> EditorTool.Subtitles
}

/**
 * The Tools sheet, as the original's (YouRec/EditorTools.swift:10-106, opened from Tools at EditorView.swift:913-915
 * and 310-339): "Add media at" the playhead with Add video, Add audio and Select cover; Overlays, Title and Image, with
 * where a new one goes; Adjust, the tools that change what is there, with what they apply to ([scope]); "In this
 * project", what the tools have left on the edit ([regions]), each opened by a tap in its tool, where it can be
 * removed; and the editor's Settings. A tool chosen closes the sheet and opens; Censor audio without a marked range
 * says how to mark the sound instead (EditorTools.swift:115-118). [entries] holds every [SheetTool].
 */
@Composable
internal fun ToolsSheet(
    insertAt: String, overlayScope: String, scope: String,
    entries: Map<SheetTool, SheetEntry>, canCensor: Boolean, cover: (() -> Unit)?,
    regions: List<ToolRegion>, editRegion: (ToolRegion) -> Unit,
    snap: SnapSetting, close: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var censorHelp by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = close, sheetState = state, containerColor = SheetFill) {
        // What was chosen opens as the sheet goes, as the original's pending tool (EditorView.swift:310-312).
        fun choose(action: () -> Unit) { close(); action() }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tools_title), Modifier.weight(1f).semantics { heading() }, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            TextButton(onClick = close, Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.common_done), fontWeight = FontWeight.SemiBold) }
        }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.tools_add_media_at, insertAt), color = Muted, fontSize = 12.sp)
            FitGrid(2, spacing = 10.dp) {
                for (tool in listOf(SheetTool.AddVideo, SheetTool.AddAudio)) ToolCard(tool, entries.getValue(tool)) { choose(it) }
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).background(Color(0xFF40C8E0).copy(alpha = 0.09f), RoundedCornerShape(14.dp))
                    .then(if (cover != null) Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.tools_cover_hint)) { choose(cover) } else Modifier.alpha(0.35f))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.PhotoLibrary, null, Modifier.width(28.dp), tint = Color(0xFF40C8E0))
                Text(stringResource(R.string.editor_cover), Modifier.weight(1f).padding(start = 10.dp), color = Color.White, fontWeight = FontWeight.SemiBold)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Muted)
            }
            Section(stringResource(R.string.tools_overlays), overlayScope)
            FitGrid(2, spacing = 10.dp) {
                for (tool in listOf(SheetTool.Title, SheetTool.Image)) ToolCard(tool, entries.getValue(tool)) { choose(it) }
            }
            Section(stringResource(R.string.tools_adjust), scope)
            FitGrid(2, spacing = 10.dp) {
                for (tool in listOf(SheetTool.Effects, SheetTool.Stabilize, SheetTool.Crop, SheetTool.Color, SheetTool.Speed, SheetTool.Pauses, SheetTool.Enhance, SheetTool.Censor, SheetTool.Subtitles)) {
                    ToolCard(tool, entries.getValue(tool)) { action -> if (tool == SheetTool.Censor && !canCensor) censorHelp = true else choose(action) }
                }
            }
            if (regions.isNotEmpty()) {
                Text(stringResource(R.string.tools_in_project), Modifier.padding(top = 6.dp).semantics { heading() }, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                for (region in regions) {
                    val label = regionLabel(region)
                    val title = if (region.tool == EditorTool.Text) label else toolName(region.tool) + " · " + label
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { choose { editRegion(region) } },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(toolIcon(region.tool), null, Modifier.width(26.dp), tint = toolTint(region.tool))
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.tools_tap_to_edit), color = Muted, fontSize = 12.sp)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Muted)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(top = 6.dp), color = Color.White.copy(alpha = 0.08f))
            Text(stringResource(R.string.settings_title), Modifier.semantics { heading() }, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            EditorSettingsSection(snap)
            Spacer(Modifier.heightIn(min = 8.dp))
        }
    }
    if (censorHelp) AlertDialog(
        onDismissRequest = { censorHelp = false },
        title = { Text(stringResource(R.string.tools_censor_help_title)) },
        text = { Text(stringResource(R.string.tools_censor_help)) },
        confirmButton = { TextButton(onClick = { censorHelp = false }) { Text(stringResource(R.string.settings_got_it)) } },
    )
}

/** A section's name as a heading, with what it applies to under it. */
@Composable
private fun Section(title: String, detail: String) {
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, Modifier.semantics { heading() }, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Text(detail, color = Muted, fontSize = 12.sp)
    }
}

/** A tool as the original's card (EditorTools.swift:119-128): its sign in its colour and its name, on a tint of it. */
@Composable
private fun ToolCard(tool: SheetTool, entry: SheetEntry, choose: (() -> Unit) -> Unit) {
    val region = tool.tool()
    val tint = region?.let(::toolTint) ?: if (tool == SheetTool.AddVideo) Color(0xFF64D2FF) else Color(0xFF66D4CF)
    val icon = region?.let(::toolIcon) ?: if (tool == SheetTool.AddVideo) Icons.Outlined.VideoLibrary else Icons.Outlined.LibraryMusic
    val name = when (tool) {
        SheetTool.AddVideo -> stringResource(R.string.editor_add_video)
        SheetTool.AddAudio -> stringResource(R.string.editor_add_audio)
        else -> toolName(region!!)
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).background(tint.copy(alpha = 0.09f), RoundedCornerShape(14.dp))
            .clickable(enabled = entry.enabled, role = Role.Button) { choose(entry.open) }
            .alpha(if (entry.enabled) 1f else 0.35f).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = tint)
        Text(name, Modifier.padding(start = 10.dp), color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2)
    }
}
