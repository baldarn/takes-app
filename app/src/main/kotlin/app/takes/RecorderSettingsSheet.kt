package app.takes

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Lightbulb
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import app.takes.core.DualCameraControls
import app.takes.core.HorizonFraming
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
internal fun CameraSettings(capture: CaptureController, project: Project?, frontLight: Boolean, setFrontLight: (Boolean) -> Unit, close: () -> Unit) {
    var more by rememberSaveable { mutableStateOf(false) }
    BackHandler { if (more) more = false else close() }
    // A tap beside the panel closes it and a tap on the panel does nothing, neither with a ripple.
    val quiet = remember { MutableInteractionSource() }
    val title = stringResource(if (more) R.string.recorder_settings_more else R.string.recorder_settings_title)
    // In landscape the quick panel stands at the end of the screen, as tall as it, and at most 496 wide or 70 % of the
    // width, as in the original (RecorderView.swift:685-688); it scrolls when it is taller than the screen.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (more) 1f else 0.25f))
            .clickable(quiet, null, onClick = close).semantics { paneTitle = title },
        contentAlignment = if (landscape) Alignment.CenterEnd else Alignment.BottomCenter,
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
                (if (landscape) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End))
                    .padding(6.dp).width(minOf(496.dp, maxWidth * 0.7f)).fillMaxHeight() else Modifier.padding(6.dp).fillMaxWidth())
                    .clip(RoundedCornerShape(20.dp)).background(PanelGrey)
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(20.dp)).clickable(quiet, null) {}
                    .verticalScroll(rememberScrollState()),
            ) {
                TitleBar(title, close, null)
                QuickSettings(capture, frontLight, setFrontLight)
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

/**
 * What is changed often: the assists as tiles, then the recording colour. What depends on the camera is
 * there only where a camera of this phone has it, and can be used when the camera in use has it.
 */
@Composable
private fun QuickSettings(capture: CaptureController, frontLight: Boolean, setFrontLight: (Boolean) -> Unit) {
    var help by remember { mutableStateOf<String?>(null) }
    val tiles = mutableListOf<@Composable (Modifier) -> Unit>().apply {
        if (capture.offersStabilization) add { modifier ->
            val on = capture.format.stabilization != CaptureFormat.Stabilization.Off
            val status = stringResource(when {
                !capture.offers.stabilization -> R.string.recorder_stabilize_unavailable
                on -> R.string.recorder_stabilize_active
                else -> R.string.recorder_stabilize_inactive
            })
            AssistTile(
                stringResource(R.string.recorder_stabilize), Icons.Filled.CenterFocusWeak, on,
                enabled = capture.canChangeFormat && !capture.dualActive && (capture.offers.stabilization || capture.settings.stabilization),
                details = status + "\n\n" + stringResource(R.string.recorder_stabilize_help), explain = { help = it }, modifier = modifier,
            ) { capture.chooseStabilization(!capture.settings.stabilization) }
        }
    }
    if (capture.offersLevel) tiles.add { modifier ->
        val on = capture.settings.levelHorizon && capture.canLevel
        AssistTile(
            stringResource(R.string.recorder_level), Icons.Filled.Straighten, on,
            enabled = capture.canChangeFormat && capture.canLevel,
            details = (if (capture.canLevel) "" else stringResource(R.string.recorder_level_rear_only) + "\n\n") + stringResource(R.string.recorder_level_help),
            explain = { help = it }, modifier = modifier,
        ) { capture.chooseLevelHorizon(!capture.settings.levelHorizon) }
    }
    tiles.add { modifier ->
        val on = capture.settings.followFace
        val status = if (on) stringResource(when (capture.followStatus) {
            CameraFrames.FollowStatus.Searching -> R.string.recorder_follow_searching
            CameraFrames.FollowStatus.Following -> R.string.recorder_follow_following
            CameraFrames.FollowStatus.Holding -> R.string.recorder_follow_holding
        }) + "\n\n" else if (!capture.canChangeFollow && capture.ready) stringResource(R.string.recorder_assist_paused_only) + "\n\n" else ""
        AssistTile(
            stringResource(R.string.recorder_follow), Icons.Filled.Face, on, enabled = capture.canChangeFollow,
            details = status + stringResource(if (capture.dualActive) R.string.recorder_follow_help_dual else R.string.recorder_follow_help), explain = { help = it }, modifier = modifier,
        ) { capture.chooseFollowFace(!capture.settings.followFace) }
    }
    tiles.add { modifier ->
        // Needs nothing of the camera: the light is the display's.
        AssistTile(
            stringResource(R.string.recorder_light), if (frontLight) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb, frontLight, enabled = true,
            details = stringResource(R.string.recorder_light_help), explain = { help = it }, modifier = modifier,
        ) { setFrontLight(!frontLight) }
    }
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Tiles keep the width they have when there are four of them, as in the original; two columns where a
        // name does not fit a quarter of the width, as the original's grids at accessibility sizes.
        FitGrid(4, spacing = 8.dp, wrapping = true) {
            tiles.forEach { it(Modifier) }
        }
        // Only where the phone runs two cameras at once, as the original offers it (RecorderView.swift:819-833).
        if (capture.dualPairs.isNotEmpty()) TwoCameras(capture)
        if (capture.offersHDR) RecordingColor(capture)
    }
    help?.let { text ->
        AlertDialog(
            onDismissRequest = { help = null },
            confirmButton = { TextButton(onClick = { help = null }) { Text(stringResource(R.string.common_ok)) } },
            title = { Text(stringResource(R.string.recorder_assist_title)) }, text = { Text(text) },
        )
    }
}

/** One assist: a tap turns it on or off, holding it explains it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AssistTile(
    title: String, icon: ImageVector, on: Boolean, enabled: Boolean, details: String, explain: (String) -> Unit, modifier: Modifier = Modifier, toggle: () -> Unit,
) {
    val color = if (on) SettingsCyan else Color.White
    val explainLabel = stringResource(R.string.recorder_assist_explain)
    val view = LocalView.current
    Box(
        modifier.heightIn(min = 62.dp).alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(10.dp))
            .background(if (on) SettingsCyan.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.07f))
            // Holding explains the assist also where it cannot be used.
            .combinedClickable(role = Role.Switch, onLongClick = { explain(details) }) { if (enabled) { toggle(); view.confirm() } }
            .semantics(mergeDescendants = true) {
                toggleableState = ToggleableState(on)
                if (!enabled) disabled()
                customActions = listOf(CustomAccessibilityAction(explainLabel) { explain(details); true })
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.padding(vertical = 8.dp, horizontal = 4.dp), Arrangement.spacedBy(5.dp), Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(22.dp), tint = color)
            Text(title, color = color, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, textAlign = TextAlign.Center)
        }
        if (on) Icon(Icons.Filled.CheckCircle, null, Modifier.align(Alignment.TopEnd).padding(6.dp).size(12.dp), tint = SettingsCyan)
    }
}

/**
 * "Use two cameras", and with them on the First and the Second camera, each menu offering only what the phone can
 * pair: as the original (RecorderView.swift:648-681, 819-883). Changed before a take or while it is paused.
 */
@Composable
private fun TwoCameras(capture: CaptureController) {
    val enabled = capture.canChangeCameras
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsSwitchRow(stringResource(R.string.recorder_dual_use), capture.dualActive, enabled) { capture.chooseTwoCameras(it) }
        if (capture.dualActive) {
            val (first, second) = DualCameraControls.slots(capture.dualPair, capture.dualLayout.swapped)
            val all = DualCameraControls.cameras(capture.dualPairs)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DualCameraMenu(capture, true, first, all, enabled, Modifier.weight(1f))
                DualCameraMenu(capture, false, second, DualCameraControls.partners(capture.dualPairs, first), enabled, Modifier.weight(1f))
            }
            SettingsNote(stringResource(R.string.recorder_dual_format))
        }
    }
}

@Composable
private fun SettingsSwitchRow(label: String, on: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).alpha(if (enabled) 1f else 0.4f), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Switch(
            on, change, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = SettingsCyan, checkedThumbColor = Color.White, disabledCheckedTrackColor = SettingsCyan),
        )
    }
}

@Composable
private fun DualCameraMenu(
    capture: CaptureController, firstSlot: Boolean, selection: DualCameraControls.Camera?, choices: List<DualCameraControls.Camera>, enabled: Boolean, modifier: Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val name = capture.dualChoice(selection)?.label ?: stringResource(R.string.recorder_camera)
    val label = stringResource(if (firstSlot) R.string.recorder_dual_first_camera else R.string.recorder_dual_second_camera)
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.07f)).clickable(enabled = enabled, role = Role.DropdownList) { open = true }
                .padding(horizontal = 12.dp).semantics(mergeDescendants = true) { contentDescription = label; stateDescription = name },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (firstSlot) R.string.recorder_dual_first else R.string.recorder_dual_second), color = Secondary, fontSize = 11.sp)
                Text(name, color = Color.White, fontSize = 14.sp, maxLines = 1)
            }
            Icon(Icons.Filled.UnfoldMore, null, Modifier.size(16.dp), tint = Color.White)
        }
        DropdownMenu(open, { open = false }) {
            choices.forEach { camera ->
                DropdownMenuItem(
                    { Text(capture.dualChoice(camera)?.label ?: camera.id) },
                    { open = false; capture.chooseDualCamera(camera, firstSlot) },
                    leadingIcon = { Icon(if (camera.id == selection?.id) Icons.Filled.Check else Icons.Filled.PhotoCamera, null) },
                    modifier = Modifier.semantics { selected = camera.id == selection?.id },
                )
            }
        }
    }
}

/** "Recording color", as in the original without Log: SDR or HDR, changed before a take or while it is paused. */
@Composable
private fun RecordingColor(capture: CaptureController) {
    val hdr = capture.format.hdr
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.recorder_color), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(11.dp)).padding(3.dp), Arrangement.spacedBy(3.dp)) {
            listOf(false to R.string.recorder_color_sdr, true to R.string.recorder_color_hdr).forEach { (mode, name) ->
                val chosen = mode == hdr
                val enabled = capture.canChangeFormat && (!mode || capture.offers.hdr && !capture.dualActive)
                Box(
                    Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).background(if (chosen) SettingsCyan else Color.Transparent)
                        .clickable(enabled = enabled, role = Role.RadioButton) { if (!chosen) capture.chooseHDR(mode) }.semantics { selected = chosen },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(name), color = if (chosen) Color.Black else if (enabled) Color.White else Secondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        SettingsNote(stringResource(when {
            capture.hasActiveTake -> R.string.recorder_color_note_pause
            !capture.ready -> R.string.recorder_color_note_not_ready
            !capture.offers.hdr -> R.string.recorder_color_note_sdr_only
            // The level horizon and follow face draw the frames in 8 bits.
            capture.settings.hdr && !hdr && capture.framesInRecording -> R.string.recorder_color_note_assist
            // Asked for, and this camera has it, but not in the size being recorded.
            capture.settings.hdr && !hdr -> R.string.recorder_color_note_size
            hdr -> R.string.recorder_color_note_hdr
            else -> R.string.recorder_color_note_sdr
        }))
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
        if (capture.offersLevel) LevelSettings(capture)
        // RecorderView.swift:1242-1247
        if (capture.dualActive) {
            SettingsSection(stringResource(R.string.recorder_dual_section)) {
                SettingsRow { Text(stringResource(R.string.recorder_dual_section_format), color = Color.White) }
                SettingsRow { SettingsNote(stringResource(R.string.recorder_dual_note)) }
            }
        }
        if (capture.hasFrontCamera) {
            SettingsSection(stringResource(R.string.recorder_settings_picture)) {
                // Changing either rebuilds the camera, which is done between segments, never under one.
                SettingsSwitch(stringResource(R.string.recorder_mirror_preview), settings.mirrorPreview, capture.canChangeFormat) { capture.chooseMirrorPreview(it) }
                SettingsSwitch(stringResource(R.string.recorder_mirror_recording), settings.mirrorRecording, capture.canChangeTake) { capture.chooseMirrorRecording(it) }
                SettingsRow { SettingsNote(stringResource(R.string.recorder_mirror_note)) }
            }
        }
        if (!capture.dualActive) FocusSettings(capture.focus, capture.ready && capture.phase != Phase.Saving)
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
                capture.microphones.forEach { choice ->
                    ChoiceRow(choice.label, capture.microphoneKey == choice.key, free) { capture.chooseMicrophone(choice.key) }
                }
                SettingsRow { SettingsNote(stringResource(if (free) R.string.recorder_settings_microphone_quick else R.string.recorder_settings_microphone_busy)) }
            }
            SettingsSection(stringResource(R.string.recorder_settings_priority)) {
                val order = capture.microphonePriority
                order.forEachIndexed { index, entry ->
                    SettingsRow {
                        Column(Modifier.weight(1f)) {
                            Text(entry.label, color = Color.White)
                            if (!entry.connected) SettingsNote(stringResource(R.string.recorder_mic_disconnected_state))
                        }
                        MoveButton(Icons.Filled.ArrowUpward, stringResource(R.string.recorder_mic_earlier, entry.label), index > 0) { capture.moveMicrophonePreference(entry.key, -1) }
                        MoveButton(Icons.Filled.ArrowDownward, stringResource(R.string.recorder_mic_later, entry.label), index + 1 < order.size) { capture.moveMicrophonePreference(entry.key, 1) }
                    }
                }
            }
        }
        if (capture.offersUltraHD) {
            SettingsSection(stringResource(R.string.recorder_settings_quality)) {
                ChoiceRow(stringResource(R.string.recorder_quality_1080), !settings.ultraHD, capture.canChangeTake && !capture.dualActive) { capture.chooseUltraHD(false) }
                ChoiceRow(stringResource(R.string.recorder_quality_4k), settings.ultraHD, capture.canChangeTake && !capture.dualActive) { capture.chooseUltraHD(true) }
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

/** An arrow that moves an entry of a list one place. */
@Composable
private fun MoveButton(icon: ImageVector, label: String, enabled: Boolean, move: () -> Unit) {
    Box(
        Modifier.size(44.dp).alpha(if (enabled) 1f else 0.3f).clip(RoundedCornerShape(50)).clickable(enabled = enabled, role = Role.Button, onClick = move),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(20.dp), tint = SettingsCyan) }
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

/** "Level horizon" in More settings: its largest angle, and how much that magnifies the picture. */
@Composable
private fun LevelSettings(capture: CaptureController) {
    val settings = capture.settings
    val enabled = capture.ready && !capture.hasActiveTake && capture.canLevel
    SettingsSection(stringResource(R.string.recorder_level)) {
        val angle = stringResource(R.string.recorder_level_degrees, java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(settings.horizonDegrees))
        val label = stringResource(R.string.recorder_level_max_angle)
        SettingsRow(Modifier.alpha(if (enabled) 1f else 0.4f)) {
            Text(label, color = Color.White, modifier = Modifier.weight(1f))
            Text(angle, color = SettingsCyan, fontWeight = FontWeight.SemiBold)
        }
        SettingsRow(Modifier.alpha(if (enabled) 1f else 0.4f)) {
            val view = LocalView.current
            androidx.compose.material3.Slider(
                settings.horizonDegrees.toFloat(),
                { value -> val before = settings.horizonDegrees; capture.chooseHorizonDegrees(value.toDouble()); if (settings.horizonDegrees != before) view.tick() },
                Modifier.weight(1f).semantics { contentDescription = label; stateDescription = angle },
                enabled = enabled,
                valueRange = HorizonFraming.MINIMUM_DEGREES.toFloat()..HorizonFraming.MAXIMUM_DEGREES.toFloat(),
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = SettingsCyan),
            )
        }
        // The magnification at the size recorded, 16:9.
        val scale = "%.2f".format(HorizonFraming.scale(settings.horizonDegrees, app.takes.core.Size(16.0, 9.0)))
        SettingsRow {
            SettingsNote(
                stringResource(if (settings.levelHorizon) R.string.recorder_level_framing else R.string.recorder_level_framing_off, scale) + "\n" +
                    stringResource(R.string.recorder_level_note),
            )
        }
    }
}
