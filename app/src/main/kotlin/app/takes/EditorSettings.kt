@file:OptIn(ExperimentalFoundationApi::class)

package app.takes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TextFormat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit

private val Muted = Color(0xFFA0A4AB)
private val CardFill = Color.White.copy(alpha = 0.07f)

/**
 * The editor's Settings, as the Settings section of the original's tools (YouRec/EditorTools.swift:79-106): Snap
 * to cuts, kept with the magnet beside Undo and Redo ([SnapSetting]), Match listening levels ([ListeningLevels]),
 * and the font library. A touch and hold on a setting gives its explanation, as in the original.
 */
@Composable
internal fun EditorSettingsButton(snap: SnapSetting, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, enabled = enabled) {
        Icon(Icons.Outlined.Settings, stringResource(R.string.settings_title), tint = if (enabled) Color.White else Muted)
    }
    if (open) EditorSettingsDialog(snap) { open = false }
}

@Composable
private fun EditorSettingsDialog(snap: SnapSetting, close: () -> Unit) {
    var fonts by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf<Pair<String, String>?>(null) }
    // The original pushes its Fonts screen over the tools; here it takes the dialog's place until it is closed.
    if (fonts) { FontLibraryDialog(use = null, draftFace = null) { fonts = false }; return }
    help?.let { (title, explanation) ->
        AlertDialog(
            onDismissRequest = { help = null },
            title = { Text(title) },
            text = { Text(explanation) },
            confirmButton = { TextButton(onClick = { help = null }) { Text(stringResource(R.string.settings_got_it)) } },
        )
        return
    }
    val snapTitle = stringResource(R.string.editor_snap)
    val snapExplanation = stringResource(R.string.settings_snap_explanation)
    val context = LocalContext.current
    var matching by remember { mutableStateOf(ListeningLevels.matching(context)) }
    val matchTitle = stringResource(R.string.enhance_match)
    val matchExplanation = stringResource(R.string.settings_match_explanation)
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.settings_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingCard(snapTitle, stringResource(R.string.settings_snap_hint), snap.on, snap::toggle) { help = snapTitle to snapExplanation }
                SettingCard(matchTitle, stringResource(R.string.settings_match_hint), matching, { matching = !matching; ListeningLevels.match(context, matching) }) {
                    help = matchTitle to matchExplanation
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).background(CardFill, RoundedCornerShape(14.dp))
                        .combinedClickable(role = Role.Button, onClick = { fonts = true }).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.TextFormat, null, Modifier.size(24.dp))
                    Column(Modifier.weight(1f).padding(start = 10.dp, top = 6.dp, bottom = 6.dp)) {
                        Text(stringResource(R.string.fonts_title), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.settings_fonts_hint), color = Muted, fontSize = 12.sp)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
                Text(stringResource(R.string.settings_hold_note), color = Muted, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.common_done)) } },
    )
}

/** A setting as the original's card: its name and a switch; a tap turns it, a touch and hold explains it. */
@Composable
private fun SettingCard(title: String, hint: String, on: Boolean, toggle: () -> Unit, explain: () -> Unit) {
    val state = stringResource(if (on) R.string.editor_snap_on else R.string.editor_snap_off)
    Column(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).background(CardFill, RoundedCornerShape(14.dp))
            .combinedClickable(role = Role.Switch, onLongClick = explain, onClick = toggle)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clearAndSetSemantics { role = Role.Switch; contentDescription = "$title. $hint"; stateDescription = state },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Switch(on, null)
        }
        Text(hint, color = Muted, fontSize = 12.sp)
    }
}


/**
 * Match listening levels: whether Enhance audio plays Bypass and Enhanced equally loud, kept across launches under
 * the original's key (YouRec/EditorController.swift:88-89), on until turned off. The editor's Settings and the
 * switch in Enhance audio (A/EnhanceInspector.kt) are the same setting, as in the original (TrackEditors.swift:184, 314).
 */
internal object ListeningLevels {
    private const val STORE = "enhance"
    private const val KEY = "matchListeningLevels"

    fun matching(context: Context): Boolean =
        runCatching { context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getBoolean(KEY, true) }.getOrDefault(true)

    fun match(context: Context, on: Boolean) {
        runCatching { context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit { putBoolean(KEY, on) } }
    }
}
