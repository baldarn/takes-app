package app.takes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takes.CaptureController.Phase
import app.takes.core.CaptureFormat
import app.takes.core.Project
import app.takes.core.ZoomTransition

/** The tint of what is on in the recorder's settings, as in the original. */
internal val SettingsCyan = Color(0xFF64D2FF)
private val PanelGrey = Color(0xFF1C1C1E)
private val RowGrey = Color(0xFF2C2C2E)
private val Secondary = Color(0xFF98989F)

/**
 * "Camera settings", as in the original: a panel over the bottom of the picture with what is changed
 * often, and "More settings" behind it, which fills the screen. A tap beside the panel closes it.
 */
@Composable
internal fun CameraSettings(capture: CaptureController, project: Project?, close: () -> Unit) {
    var more by rememberSaveable { mutableStateOf(false) }
    BackHandler { if (more) more = false else close() }
    // A tap beside the panel closes it and a tap on the panel does nothing, neither with a ripple.
    val quiet = remember { MutableInteractionSource() }
    val title = stringResource(if (more) R.string.recorder_settings_more else R.string.recorder_settings_title)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (more) 1f else 0.25f))
            .clickable(quiet, null, onClick = close).semantics { paneTitle = title },
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (more) {
            Column(
                Modifier.fillMaxSize().background(PanelGrey).clickable(quiet, null) {}
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            ) {
                TitleBar(title, close) { more = false }
                MoreSettings(capture, project)
            }
        } else {
            Column(
                Modifier.padding(6.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(PanelGrey)
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(20.dp)).clickable(quiet, null) {},
            ) {
                TitleBar(title, close, null)
                HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { more = true }.heightIn(min = 48.dp).padding(horizontal = 20.dp),
                    Arrangement.spacedBy(10.dp), Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Settings, null, Modifier.size(20.dp), tint = Color.White)
                    Text(stringResource(R.string.recorder_settings_more), color = Color.White, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = Secondary)
                }
            }
        }
    }
}

@Composable
private fun TitleBar(title: String, close: () -> Unit, back: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = if (back == null) 20.dp else 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = back), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.recorder_settings_title), tint = SettingsCyan)
            }
        }
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
        TextButton(onClick = close) { Text(stringResource(R.string.common_done), color = SettingsCyan, fontWeight = FontWeight.SemiBold) }
    }
}

/** The sections of "More settings", in the original's order; the ones that need work on the camera come with it. */
@Composable
private fun MoreSettings(capture: CaptureController, project: Project?) {
    val settings = capture.settings
    var credits by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
    ) {
        SettingsSection(stringResource(R.string.recorder_settings_project)) {
            SettingsRow { Text(project?.title ?: stringResource(R.string.recorder_new_project), color = Color.White) }
            SettingsRow { SettingsNote(stringResource(R.string.recorder_settings_captured, clock(project?.capturedDuration ?: 0.0))) }
        }
        if (capture.hasFrontCamera) {
            SettingsSection(stringResource(R.string.recorder_settings_picture)) {
                // Changing either rebuilds the camera, which is done between segments, never under one.
                SettingsSwitch(stringResource(R.string.recorder_mirror_preview), settings.mirrorPreview, capture.canChangeFormat) { capture.chooseMirrorPreview(it) }
                SettingsSwitch(stringResource(R.string.recorder_mirror_recording), settings.mirrorRecording, capture.canChangeTake) { capture.chooseMirrorRecording(it) }
                SettingsRow { SettingsNote(stringResource(R.string.recorder_mirror_note)) }
            }
        }
        FocusSettings(capture.focus, capture.ready && capture.phase != Phase.Saving)
        SettingsSection(stringResource(R.string.recorder_settings_zoom)) {
            var choosing by remember { mutableStateOf(false) }
            val transition = stringResource(R.string.recorder_settings_transition)
            val value = zoomTimeName(settings.zoomSeconds)
            SettingsRow(Modifier.clickable(role = Role.DropdownList) { choosing = true }.semantics { stateDescription = value }) {
                Text(transition, color = Color.White, modifier = Modifier.weight(1f))
                Box {
                    Text(value, color = Secondary)
                    DropdownMenu(choosing, { choosing = false }) {
                        (ZoomTransition.durationPresets + settings.zoomSeconds).distinct().sorted().forEach { seconds ->
                            DropdownMenuItem(
                                { Text(zoomTimeName(seconds)) }, { capture.chooseZoomDuration(seconds); choosing = false },
                                trailingIcon = { if (seconds == settings.zoomSeconds) Icon(Icons.Filled.Check, null) },
                            )
                        }
                    }
                }
            }
            SettingsRow { SettingsNote(stringResource(R.string.recorder_settings_zoom_note)) }
        }
        if (capture.microphones.isNotEmpty()) {
            // A running segment keeps its microphone: the choice is for the next one.
            val free = capture.phase == Phase.Idle || capture.phase == Phase.Paused
            SettingsSection(stringResource(R.string.recorder_settings_microphone)) {
                ChoiceRow(stringResource(R.string.recorder_mic_automatic), capture.chosenMicrophone == null, free) { capture.chooseMicrophone(null) }
                capture.microphones.forEach { choice ->
                    ChoiceRow(choice.label, capture.chosenMicrophone?.id == choice.id, free) { capture.chooseMicrophone(choice.id) }
                }
                SettingsRow { SettingsNote(stringResource(if (free) R.string.recorder_settings_microphone_note else R.string.recorder_settings_microphone_busy)) }
            }
        }
        if (capture.offersUltraHD) {
            SettingsSection(stringResource(R.string.recorder_settings_quality)) {
                ChoiceRow(stringResource(R.string.recorder_quality_1080), !settings.ultraHD, capture.canChangeTake) { capture.chooseUltraHD(false) }
                ChoiceRow(stringResource(R.string.recorder_quality_4k), settings.ultraHD, capture.canChangeTake) { capture.chooseUltraHD(true) }
                SettingsRow {
                    SettingsNote(stringResource(if (settings.ultraHD && !capture.offers.ultraHD) R.string.recorder_quality_note_camera else R.string.recorder_quality_note))
                }
            }
        }
        SettingsSection(stringResource(R.string.recorder_settings_framing)) {
            SettingsSwitch(stringResource(R.string.recorder_settings_fit), settings.fitFrame) { settings.fitFrame = it }
            SettingsRow { SettingsNote(stringResource(R.string.recorder_settings_fit_note)) }
        }
        SettingsSection(stringResource(R.string.recorder_settings_about)) {
            SettingsRow(Modifier.clickable(role = Role.Button) { credits = true }) {
                Text(stringResource(R.string.recorder_settings_credits), color = Color.White, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = Secondary)
            }
        }
        Spacer(Modifier.size(24.dp))
    }
    if (credits) CreditsScreen(report = { capture.message = it }) { credits = false }
}

@Composable
internal fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title.uppercase(), color = Secondary, fontSize = 12.sp,
        modifier = Modifier.padding(start = 12.dp, top = 18.dp, bottom = 6.dp).semantics { heading() },
    )
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(RowGrey), content = content)
}

@Composable
internal fun SettingsRow(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        Arrangement.spacedBy(10.dp), Alignment.CenterVertically, content,
    )
}

@Composable
internal fun SettingsNote(text: String) {
    Text(text, color = Secondary, fontSize = 12.sp, lineHeight = 16.sp)
}

@Composable
internal fun SettingsSwitch(label: String, on: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    SettingsRow(Modifier.alpha(if (enabled) 1f else 0.4f)) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        Switch(
            on, change, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = SettingsCyan, checkedThumbColor = Color.White, disabledCheckedTrackColor = SettingsCyan),
        )
    }
}

/** One of several, with a check on the one in use. */
@Composable
private fun ChoiceRow(label: String, chosen: Boolean, enabled: Boolean, choose: () -> Unit) {
    SettingsRow(
        Modifier.alpha(if (enabled) 1f else 0.4f).clickable(enabled = enabled, role = Role.RadioButton, onClick = choose).semantics { selected = chosen },
    ) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        if (chosen) Icon(Icons.Filled.Check, null, Modifier.width(20.dp), tint = SettingsCyan)
    }
}

/** The zoom transition time in words; the core's own label is English only. */
@Composable
internal fun zoomTimeName(seconds: Double): String =
    if (seconds == 0.0) stringResource(R.string.recorder_zoom_instant) else ZoomTransition.durationLabel(seconds)

private fun clock(seconds: Double): String {
    val value = (if (seconds.isFinite()) seconds else 0.0).toLong().coerceAtLeast(0)
    return "%02d:%02d".format(value / 60, value % 60)
}
