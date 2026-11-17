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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ZoomInMap
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takes.core.DollyHint
import app.takes.core.EffectParameterKind
import app.takes.core.LiveCrashState
import app.takes.core.LiveDollyState
import app.takes.core.VideoEffectID
import app.takes.core.VideoEffectLibrarySection
import app.takes.core.VideoEffectParameterSection
import app.takes.core.VideoEffectParameterVisibility
import app.takes.core.VideoEffectSettings
import app.takes.core.librarySection
import java.text.NumberFormat

/** The live effects this port offers in the recorder: the two camera moves. */
internal object LiveMoves {
    val KINDS = listOf(VideoEffectID.DollyZoom, VideoEffectID.CrashZoom)
}

private val Soft = Color(0xFFA0A4AB)
private val Yellow = Color(0xFFFFD60A)

private enum class LivePage { Applied, Library, Inspector }

/**
 * The recorder's Video effects panel, as the original's (YouRec/VideoEffects/LiveEffectsPanel.swift): the effects
 * applied, the library to add from and the controls of one. This port offers the camera moves, Crash Zoom and Dolly
 * Zoom, which the camera makes while it records. [inspect] is told the effect whose controls are open.
 */
@Composable
internal fun LiveEffectsPanel(capture: CaptureController, modifier: Modifier, inspect: (VideoEffectID?) -> Unit, close: () -> Unit) {
    val effects = capture.liveEffects
    var page by remember { mutableStateOf(if (effects.isEmpty()) LivePage.Library else LivePage.Applied) }
    var selected by remember { mutableStateOf(effects.firstOrNull()?.id) }
    val current = effects.firstOrNull { it.id == selected }
    // A take closes it, as in the original.
    LaunchedEffect(capture.hasActiveTake) { if (capture.hasActiveTake) close() }
    LaunchedEffect(page, current?.kind, current?.enabled) { inspect(current?.takeIf { page == LivePage.Inspector && it.enabled }?.kind) }
    fun update(change: (VideoEffectSettings) -> VideoEffectSettings) {
        capture.setLiveEffects(capture.liveEffects.map { if (it.id == selected) change(it) else it })
    }
    fun remove(id: String) {
        val left = capture.liveEffects.filter { it.id != id }
        capture.setLiveEffects(left)
        selected = left.firstOrNull()?.id
        page = if (left.isEmpty()) LivePage.Library else LivePage.Applied
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.editor_effects), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.semantics { heading() })
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                TextButton(onClick = close, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.common_done), fontWeight = FontWeight.SemiBold) }
            }
        }
        // Back to the applied list, where it is, and Add. VideoEffectNavigationBar.
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (page != LivePage.Applied && effects.isNotEmpty()) {
                TextButton(onClick = { page = LivePage.Applied }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.effects_back_count, effects.size), Modifier.padding(start = 4.dp))
                }
            } else Text(stringResource(R.string.effects_applied_count, effects.size), color = Soft, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
            Spacer(Modifier.weight(1f))
            if (page != LivePage.Library) {
                TextButton(onClick = { page = LivePage.Library }) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.effects_add), Modifier.padding(start = 4.dp))
                }
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (page) {
                LivePage.Applied -> Applied(capture, effects, edit = { selected = it; page = LivePage.Inspector }, toggle = { id ->
                    capture.setLiveEffects(capture.liveEffects.map { if (it.id == id) it.copy(enabled = !it.enabled) else it })
                }, add = { page = LivePage.Library }, clear = { capture.setLiveEffects(emptyList()); selected = null; page = LivePage.Library })
                LivePage.Library -> Library { kind ->
                    // One of each camera move, as in the original: the one there is opens instead.
                    val existing = capture.liveEffects.firstOrNull { it.kind == kind }
                    if (existing != null) selected = existing.id
                    else {
                        val effect = VideoEffectSettings(kind)
                        capture.setLiveEffects(capture.liveEffects + effect)
                        selected = effect.id
                    }
                    page = LivePage.Inspector
                }
                LivePage.Inspector -> if (current == null) page = LivePage.Applied else Inspector(capture, current, ::update) { remove(current.id) }
            }
        }
    }
}

@Composable
private fun Applied(capture: CaptureController, effects: List<VideoEffectSettings>, edit: (String) -> Unit, toggle: (String) -> Unit, add: () -> Unit, clear: () -> Unit) {
    if (effects.isEmpty()) {
        Text(stringResource(R.string.live_effects_none), color = Soft)
        TextButton(onClick = add) { Icon(Icons.Filled.Add, null); Text(stringResource(R.string.effects_add_effect), Modifier.padding(start = 6.dp)) }
        return
    }
    for (effect in VideoEffectSettings.ordered(effects)) {
        val name = effectName(effect.kind)
        val tint = effect.kind.librarySection.look.tint
        val editLabel = stringResource(R.string.live_effects_edit, name)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.045f))
                .clickable(onClickLabel = editLabel, role = Role.Button) { edit(effect.id) }.padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(name, color = Color.White, fontWeight = FontWeight.Medium)
                Text(stringResource(if (effect.enabled) R.string.live_effects_on else R.string.live_effects_off), color = Soft, fontSize = 12.sp)
            }
            val power = stringResource(if (effect.enabled) R.string.effects_disable_effect else R.string.effects_enable_effect)
            IconButton(onClick = { toggle(effect.id) }, modifier = Modifier.semantics { contentDescription = power }) {
                Icon(Icons.Outlined.PowerSettingsNew, null, Modifier.clip(CircleShape).background(if (effect.enabled) tint else Color.Transparent).padding(4.dp),
                    tint = if (effect.enabled) Color.Black else Soft)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.padding(end = 8.dp), tint = Soft)
        }
    }
    TextButton(onClick = clear, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.live_effects_clear), color = Color(0xFFFF6B6B)) }
}

@Composable
private fun Library(add: (VideoEffectID) -> Unit) {
    val section = VideoEffectLibrarySection.CameraFrame
    Text(stringResource(section.look.title), color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
    for (kind in section.kinds.filter { it in LiveMoves.KINDS }) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.045f))
                .clickable(role = Role.Button) { add(kind) }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(effectName(kind), color = Color.White, fontWeight = FontWeight.Medium)
                Text(effectSummary(kind), color = Soft, fontSize = 12.sp)
            }
            Icon(Icons.Filled.Add, null, tint = section.look.tint)
        }
    }
}

/** The controls of one move, as LiveEffectsPanel.swift:140-200 lays them out. */
@Composable
private fun Inspector(capture: CaptureController, effect: VideoEffectSettings, update: ((VideoEffectSettings) -> VideoEffectSettings) -> Unit, remove: () -> Unit) {
    val name = effectName(effect.kind)
    val tint = effect.kind.librarySection.look.tint
    var help by remember { mutableStateOf(false) }
    val view = LocalView.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        IconButton(onClick = { help = true }) { Icon(Icons.Outlined.HelpOutline, stringResource(R.string.effects_about, name), tint = Soft) }
        Spacer(Modifier.weight(1f))
        val power = stringResource(if (effect.enabled) R.string.effects_disable_effect else R.string.effects_enable_effect)
        IconButton(
            onClick = { update { it.copy(enabled = !it.enabled) } },
            modifier = Modifier.clip(CircleShape).background(if (effect.enabled) tint else Color.White.copy(alpha = 0.07f)).semantics { contentDescription = power },
        ) { Icon(Icons.Outlined.PowerSettingsNew, null, tint = if (effect.enabled) Color.Black else Soft) }
    }
    if (effect.kind == VideoEffectID.CrashZoom && effect.enabled) {
        Button(onClick = { if (capture.triggerCrashZoom()) view.tick() }, enabled = capture.canTriggerCrash, colors = ButtonDefaults.buttonColors(containerColor = tint)) {
            Text(crashLabel(capture.crashState, ready = R.string.crash_try))
        }
    }
    if (effect.kind == VideoEffectID.DollyZoom && effect.enabled) {
        val range = capture.dollyCleanRange
        Text(stringResource(R.string.dolly_range, oneDecimal(range.start), oneDecimal(range.endInclusive)), color = Soft, fontSize = 12.sp)
        Text(stringResource(R.string.dolly_stops_after, NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(effect.number("duration"))), color = Soft, fontSize = 12.sp)
        Button(onClick = capture::triggerDollyZoom, enabled = capture.canTriggerDolly, colors = ButtonDefaults.buttonColors(containerColor = tint)) {
            Text(dollyLabel(capture.dollyState))
        }
        Text(dollyHint(capture.dollyHint), color = Soft, fontSize = 12.sp)
    }
    for (section in VideoEffectParameterSection.sections(effect, 0.0, live = true)) {
        if (section != VideoEffectParameterSection.General) Text(section.title, color = Color.White, fontWeight = FontWeight.SemiBold)
        for (parameter in effect.parameters) {
            if (VideoEffectParameterSection.section(parameter, effect) != section) continue
            if (!VideoEffectParameterVisibility.visible(parameter, effect, 0.0, live = true) || parameter.kind is EffectParameterKind.Media) continue
            EffectControl(effect, parameter, 0.0, set = { id, value -> update { it.with(id, value) } })
        }
    }
    if (effect.kind == VideoEffectID.CrashZoom) Text(stringResource(R.string.crash_note), color = Soft, fontSize = 12.sp)
    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
    TextButton(onClick = remove, modifier = Modifier.heightIn(min = 44.dp)) {
        Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp), tint = Color(0xFFFF6B6B))
        Text(stringResource(R.string.effects_remove), Modifier.padding(start = 6.dp), color = Color(0xFFFF6B6B))
    }
    if (help) {
        AlertDialog(
            onDismissRequest = { help = false },
            title = { Text(name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // The original's help, which tells how the move is made while recording.
                    Text(if (effect.kind == VideoEffectID.CrashZoom) stringResource(R.string.effect_crash_zoom_live_help) else effectHelp(effect.kind))
                    effectHistory(effect.kind)?.let {
                        HorizontalDivider()
                        Text(stringResource(R.string.effects_film_technique), fontWeight = FontWeight.SemiBold)
                        Text(it, color = Soft)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { help = false }) { Text(stringResource(R.string.common_done)) } },
        )
    }
}

private fun oneDecimal(value: Double): String = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1; minimumFractionDigits = 1 }.format(value)

/** Crash Zoom's button: Return while it holds, its state while it moves. RecorderView.swift:107. */
@Composable
internal fun crashLabel(state: LiveCrashState, ready: Int = R.string.crash_button): String = stringResource(
    when (state) {
        LiveCrashState.Ready -> ready
        LiveCrashState.Holding -> R.string.crash_return
        LiveCrashState.Pushing -> R.string.crash_pushing
        LiveCrashState.Returning -> R.string.crash_returning
    },
)

@Composable
internal fun dollyLabel(state: LiveDollyState): String = stringResource(
    when (state) {
        LiveDollyState.Ready -> R.string.dolly_start
        LiveDollyState.Preparing -> R.string.dolly_preparing
        LiveDollyState.Following -> R.string.dolly_stop
        LiveDollyState.Stopping -> R.string.dolly_settling
        LiveDollyState.Holding -> R.string.dolly_start_again
    },
)

@Composable
internal fun dollyHint(hint: DollyHint): String = stringResource(
    when (hint) {
        DollyHint.TapFace -> R.string.dolly_hint_tap_face
        DollyHint.MoreRange -> R.string.dolly_hint_more_range
        DollyHint.TapVisible -> R.string.dolly_hint_tap_visible
        DollyHint.HoldStill -> R.string.dolly_hint_hold_still
        DollyHint.KeepInFrame -> R.string.dolly_hint_keep_in_frame
        DollyHint.ZoomOut -> R.string.dolly_hint_zoom_out
        DollyHint.LeaveRoom -> R.string.dolly_hint_leave_room
        DollyHint.WalkToward -> R.string.dolly_hint_walk_toward
        DollyHint.WalkAway -> R.string.dolly_hint_walk_away
        DollyHint.Holding -> R.string.dolly_hint_holding
        DollyHint.FollowingBody -> R.string.dolly_hint_following_body
        DollyHint.NearLimit -> R.string.dolly_hint_near_limit
        DollyHint.Digital -> R.string.dolly_hint_digital
        DollyHint.MoveHeld -> R.string.dolly_hint_move_held
        DollyHint.MoveComplete -> R.string.dolly_hint_move_complete
        DollyHint.SubjectLost -> R.string.dolly_hint_subject_lost
        DollyHint.MoveStopped -> R.string.dolly_hint_move_stopped
        DollyHint.ZoomStopped -> R.string.dolly_hint_zoom_stopped
        DollyHint.MovePaused -> R.string.dolly_hint_move_paused
        DollyHint.RecordingStopped -> R.string.dolly_hint_recording_stopped
    },
)

/**
 * The buttons of the camera moves above the cameras, as the original's (RecorderView.swift:105-133): Dolly Zoom with
 * its room left and what to do, and Crash Zoom.
 */
@Composable
internal fun CameraMoveControls(capture: CaptureController, modifier: Modifier = Modifier) {
    if (!capture.crashAvailable && !capture.dollyAvailable) return
    val view = LocalView.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (capture.dollyAvailable) {
                MoveButton(dollyLabel(capture.dollyState), if (capture.dollyState.moving) Icons.Outlined.StopCircle else Icons.Outlined.CenterFocusWeak, capture.canTriggerDolly) {
                    capture.triggerDollyZoom()
                }
            }
            if (capture.crashAvailable) {
                val holding = capture.crashState == LiveCrashState.Holding
                MoveButton(crashLabel(capture.crashState), if (holding) Icons.Filled.ZoomInMap else Icons.Outlined.CenterFocusWeak, capture.canTriggerCrash) {
                    if (capture.triggerCrashZoom()) view.tick()
                }
            }
        }
        if (capture.dollyAvailable) {
            if (capture.dollyState.moving) {
                val room = stringResource(R.string.dolly_room)
                LinearProgressIndicator(
                    progress = { capture.dollyHeadroom.toFloat() }, color = Yellow, trackColor = Color.White.copy(alpha = 0.25f),
                    modifier = Modifier.width(120.dp).semantics { contentDescription = room },
                )
            }
            Text(
                dollyHint(capture.dollyHint), color = Color.White, fontSize = 11.sp, maxLines = 2, textAlign = TextAlign.Center,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun MoveButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, action: () -> Unit) {
    Row(
        Modifier.heightIn(min = 48.dp).alpha(if (enabled) 1f else 0.45f).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.65f))
            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(50))
            .clickable(enabled = enabled, role = Role.Button, onClick = action).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = Color.White)
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** The way into the live video effects, in the top bar as in the original (RecorderView.swift:495-498): off during a take. */
@Composable
internal fun LiveEffectsButton(capture: CaptureController, modifier: Modifier, open: () -> Unit) {
    val enabled = !capture.hasActiveTake
    val active = capture.liveEffects.any { it.enabled }
    val label = stringResource(R.string.live_effects_open)
    val quality = stringResource(R.string.live_effects_full_quality)
    Box(
        modifier.size(44.dp).alpha(if (enabled) 1f else 0.4f).clip(CircleShape)
            .background(if (active) Color.White.copy(alpha = 0.3f) else LocalControlPlate.current)
            .clickable(enabled = enabled, role = Role.Button, onClick = open)
            .semantics { contentDescription = label; stateDescription = quality },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(20.dp), tint = Color.White)
    }
}
