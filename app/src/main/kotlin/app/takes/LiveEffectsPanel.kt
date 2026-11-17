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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
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
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.pluralStringResource
import app.takes.core.ChromaScreen
import app.takes.core.EffectParameter
import app.takes.core.EffectRGB
import app.takes.core.EffectValue
import app.takes.core.LiveVideoEffects
import app.takes.core.Point
import app.takes.core.VideoEffectPlacementGesture
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions

/** The live effects this port offers in the recorder: the camera moves, and the looks the export's passes draw. */
internal object LiveMoves {
    val KINDS = listOf(VideoEffectID.DollyZoom, VideoEffectID.CrashZoom)

    /** The effects offered while recording, of the 21 of the original's (LiveVideoEffects.offered), that this phone draws. */
    fun offered(section: VideoEffectLibrarySection): List<VideoEffectID> =
        section.kinds.filter { it in LiveVideoEffects.offered && (it in KINDS || it in AndroidVideoEffects.offered) }
}

private val Soft = Color(0xFFA0A4AB)
private val Yellow = Color(0xFFFFD60A)
private val Orange = Color(0xFFFF9F0A)

private sealed interface LivePage {
    data object Applied : LivePage
    data object Library : LivePage
    data class Section(val section: VideoEffectLibrarySection) : LivePage
    data object Inspector : LivePage
}

/**
 * The recorder's Video effects panel, as the original's (YouRec/VideoEffects/LiveEffectsPanel.swift): the effects
 * applied, the library to add from and the controls of one; in place of all that, while a frame tool is open, its bar.
 * [inspect] is told the effect whose controls are open.
 */
@Composable
internal fun LiveEffectsPanel(capture: CaptureController, modifier: Modifier, inspect: (VideoEffectID?) -> Unit, close: () -> Unit) {
    val effects = capture.liveEffects
    var page by remember { mutableStateOf<LivePage>(if (effects.isEmpty()) LivePage.Library else LivePage.Applied) }
    var selected by remember { mutableStateOf(effects.firstOrNull()?.id) }
    val current = effects.firstOrNull { it.id == selected }
    // A take closes it, as in the original.
    LaunchedEffect(capture.hasActiveTake) { if (capture.hasActiveTake) close() }
    LaunchedEffect(page, current?.kind, current?.enabled) { inspect(current?.takeIf { page == LivePage.Inspector && it.enabled }?.kind) }
    // Another effect's controls end a strobe tried and a frame tool. LiveEffectsPanel.swift:52-54.
    LaunchedEffect(selected, page) { capture.endStrobe(); capture.cancelFrameTools() }
    DisposableEffect(Unit) { onDispose { capture.endStrobe(); capture.cancelFrameTools(); capture.effectWarning = null } }
    fun update(change: (VideoEffectSettings) -> VideoEffectSettings) {
        // A background keeps only media of its kind. LiveEffectsPanel.swift:279-282.
        capture.setLiveEffects(capture.liveEffects.map { if (it.id == selected) change(it).sanitizedBackgroundMedia(0.0) else it })
    }
    fun remove(id: String) {
        val left = capture.liveEffects.filter { it.id != id }
        capture.setLiveEffects(left)
        selected = left.firstOrNull()?.id
        page = if (left.isEmpty()) LivePage.Library else LivePage.Applied
    }
    if (capture.frameToolActive) {
        FrameToolBar(capture, modifier)
        return
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.editor_effects), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.semantics { heading() })
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                TextButton(onClick = close, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.common_done), fontWeight = FontWeight.SemiBold) }
            }
        }
        // Back, where it is, and Add. VideoEffectNavigationBar.
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val back = when (page) {
                LivePage.Applied -> null
                LivePage.Library -> if (effects.isNotEmpty()) LivePage.Applied else null
                is LivePage.Section -> LivePage.Library
                LivePage.Inspector -> LivePage.Applied
            }
            if (back != null) {
                TextButton(onClick = { page = back }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
                    Text(
                        if (page is LivePage.Section) stringResource(R.string.effects_back) else stringResource(R.string.effects_back_count, effects.size),
                        Modifier.padding(start = 4.dp),
                    )
                }
            } else Text(stringResource(R.string.effects_applied_count, effects.size), color = Soft, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
            Spacer(Modifier.weight(1f))
            if (page == LivePage.Applied || page == LivePage.Inspector) {
                TextButton(onClick = { page = LivePage.Library }) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.effects_add), Modifier.padding(start = 4.dp))
                }
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
        // Each page and each effect's controls open at their top, as the original's ScrollView of an identity (LiveEffectsPanel.swift:41).
        val scroll = key(page, selected) { rememberScrollState() }
        Column(Modifier.weight(1f, fill = false).verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            capture.lookQuality?.let { Text(qualityNote(it), color = Soft, fontSize = 12.sp) }
            capture.effectWarning?.let { Text(it, color = Orange, fontSize = 12.sp) }
            when (val shown = page) {
                LivePage.Applied -> Applied(effects, edit = { selected = it; page = LivePage.Inspector }, toggle = { id ->
                    capture.setLiveEffects(capture.liveEffects.map { if (it.id == id) it.copy(enabled = !it.enabled) else it })
                }, add = { page = LivePage.Library }, clear = { capture.setLiveEffects(emptyList()); selected = null; page = LivePage.Library })
                LivePage.Library -> Library { page = LivePage.Section(it) }
                is LivePage.Section -> SectionEffects(shown.section) { kind ->
                    // One of each camera move, as in the original: the one there is opens instead. LiveEffectsPanel.swift:293-299.
                    val existing = capture.liveEffects.firstOrNull { it.kind == kind && kind in LiveMoves.KINDS }
                    if (existing != null) selected = existing.id
                    else {
                        var effect = VideoEffectSettings(kind)
                        if (kind == VideoEffectID.NegativeStrobe) effect = effect.with("timedBurst", EffectValue.Toggle(true))
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

/** The bar of a frame tool, in place of the panel: what to do on the preview, and Cancel, or Center, Revert and Done. LiveEffectsPanel.swift:85-105. */
@Composable
private fun FrameToolBar(capture: CaptureController, modifier: Modifier) {
    if (capture.positioning != null) {
        // The hint over the buttons, so that neither is cut where the words are long.
        Column(modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(
                stringResource(if (capture.placement?.movementLocked == true) R.string.live_effects_position_locked else R.string.live_effects_position_hint),
                color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
            )
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.Center) {
                val centerHint = stringResource(R.string.live_effects_center_hint)
                TextButton(onClick = capture::resetBackgroundPosition, modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = centerHint }) {
                    Text(stringResource(R.string.live_effects_center))
                }
                TextButton(onClick = { capture.finishBackgroundPosition(revert = true) }, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.live_effects_revert)) }
                Button(onClick = { capture.finishBackgroundPosition() }, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.common_done)) }
            }
        }
        return
    }
    Row(modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val countdown = capture.plateCountdown
        val progress = capture.plateProgress
        when {
            // The clean plate: the countdown, announced, then the capture's progress. LiveEffectsPanel.swift:50, 96-99.
            countdown != null -> {
                val said = stringResource(R.string.live_effects_plate_countdown, countdown)
                Text(said, color = Color.White, fontSize = 14.sp, maxLines = 2, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Assertive })
            }
            progress != null -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.live_effects_plate_capturing), color = Color.White, fontSize = 14.sp, maxLines = 2)
                LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
            else -> {
                Icon(Icons.Outlined.Colorize, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.live_effects_tap_color), color = Color.White, fontSize = 14.sp, maxLines = 2, modifier = Modifier.weight(1f))
            }
        }
        TextButton(onClick = capture::cancelFrameTools, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.common_cancel)) }
    }
}

@Composable
private fun Applied(effects: List<VideoEffectSettings>, edit: (String) -> Unit, toggle: (String) -> Unit, add: () -> Unit, clear: () -> Unit) {
    if (effects.isEmpty()) {
        Text(stringResource(R.string.live_effects_none), color = Soft)
        TextButton(onClick = add) { Icon(Icons.Filled.Add, null); Text(stringResource(R.string.effects_add_effect), Modifier.padding(start = 6.dp)) }
        return
    }
    for (effect in VideoEffectSettings.ordered(effects)) {
        val name = effectName(effect.kind)
        val look = effect.kind.librarySection.look
        val editLabel = stringResource(R.string.live_effects_edit, name)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.045f))
                .clickable(onClickLabel = editLabel, role = Role.Button) { edit(effect.id) }.padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(look.icon, null, tint = look.tint, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(vertical = 10.dp, horizontal = 10.dp)) {
                Text(name, color = Color.White, fontWeight = FontWeight.Medium)
                Text(stringResource(if (effect.enabled) R.string.live_effects_on else R.string.live_effects_off), color = Soft, fontSize = 12.sp)
            }
            val power = stringResource(if (effect.enabled) R.string.effects_disable_effect else R.string.effects_enable_effect)
            IconButton(onClick = { toggle(effect.id) }, modifier = Modifier.semantics { contentDescription = power }) {
                Icon(Icons.Outlined.PowerSettingsNew, null, Modifier.clip(CircleShape).background(if (effect.enabled) look.tint else Color.Transparent).padding(4.dp),
                    tint = if (effect.enabled) Color.Black else Soft)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.padding(end = 8.dp), tint = Soft)
        }
    }
    TextButton(onClick = clear, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.live_effects_clear), color = Color(0xFFFF6B6B)) }
}

/** The library's four sections, each with how many effects it offers here. VideoEffectLibrary, VideoEffectsEditor.swift:81-152. */
@Composable
private fun Library(choose: (VideoEffectLibrarySection) -> Unit) {
    for (section in VideoEffectLibrarySection.entries) {
        val count = LiveMoves.offered(section).size
        if (count == 0) continue
        val look = section.look
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(look.tint.copy(alpha = 0.08f)).clickable(role = Role.Button) { choose(section) }
                .heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(look.icon, null, tint = look.tint, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(stringResource(look.title), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(pluralStringResource(R.plurals.effects_section_count, count, count), color = Soft, fontSize = 12.sp)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Soft)
        }
    }
}

/** A section's effects offered while recording, each with its line; a tap adds it and opens its controls. */
@Composable
private fun SectionEffects(section: VideoEffectLibrarySection, add: (VideoEffectID) -> Unit) {
    val look = section.look
    Text(stringResource(look.title), color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
    Text(stringResource(look.summary), color = Soft, fontSize = 13.sp)
    for (kind in LiveMoves.offered(section)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.045f))
                .clickable(role = Role.Button) { add(kind) }.heightIn(min = 60.dp).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(effectName(kind), color = Color.White, fontWeight = FontWeight.Medium)
                Text(effectSummary(kind), color = Soft, fontSize = 12.sp)
            }
            Icon(Icons.Filled.Add, null, tint = look.tint)
        }
    }
}

/** What the panel says of the preview's quality. LiveVideoEffects.swift:566-572. */
@Composable
internal fun qualityNote(note: LiveVideoEffects.QualityNote): String = stringResource(
    when (note) {
        LiveVideoEffects.QualityNote.Warm -> R.string.live_effects_quality_warm
        LiveVideoEffects.QualityNote.WarmLite -> R.string.live_effects_quality_warm_lite
        LiveVideoEffects.QualityNote.Lite -> R.string.live_effects_quality_lite
        LiveVideoEffects.QualityNote.Reduced -> R.string.live_effects_quality_reduced
    },
)

/** The controls of one effect, as LiveEffectsPanel.swift:133-201 lays them out. */
@Composable
private fun Inspector(capture: CaptureController, effect: VideoEffectSettings, update: ((VideoEffectSettings) -> VideoEffectSettings) -> Unit, remove: () -> Unit) {
    val name = effectName(effect.kind)
    val tint = effect.kind.librarySection.look.tint
    var help by remember { mutableStateOf(false) }
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    // A picture or a movie for the background, or a clean plate, from the phone. LiveEffectsPanel.swift:56, 300-320.
    var refused by remember { mutableStateOf<Pair<EffectMediaKind, EffectMediaPicked>?>(null) }
    var picking by remember { mutableStateOf<Pair<String, EffectMediaKind>?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val (parameter, kind) = picking ?: return@rememberLauncherForActivityResult
        picking = null
        if (uri == null) return@rememberLauncherForActivityResult
        val target = effect.id
        scope.launch {
            val picked = capture.importLiveMedia(uri, kind)
            refused = if (picked is EffectMediaPicked.Kept) null else kind to picked
            if (picked is EffectMediaPicked.Kept) capture.setLiveEffects(capture.liveEffects.map {
                if (it.id != target) it
                else if (parameter == "media") it.with("background", EffectValue.Choice(if (kind == EffectMediaKind.Movie) "Video" else "Image")).with("media", EffectValue.Media(picked.name))
                else it.with(parameter, EffectValue.Media(picked.name))
            })
        }
    }
    fun choose(parameter: String, kind: EffectMediaKind) {
        picking = parameter to kind
        val type = if (kind == EffectMediaKind.Movie) ActivityResultContracts.PickVisualMedia.VideoOnly else ActivityResultContracts.PickVisualMedia.ImageOnly
        pick.launch(PickVisualMediaRequest.Builder().setMediaType(type).build())
    }
    // A control set: Lock palette takes the palette of this frame, and a new number of colours with it locked takes it
    // again; a screen colour chosen turns detection off. LiveEffectsPanel.swift:283-292.
    val set = { id: String, value: EffectValue ->
        if (id == "lockPalette" && value == EffectValue.Toggle(true)) capture.sampleEffectPalette(effect.id)
        else {
            val resample = id == "colors" && effect.toggle("lockPalette")
            update { e ->
                val next = e.with(id, value)
                if (id == "screenColor") next.with("autoScreen", EffectValue.Toggle(false)).with("screenType", EffectValue.Choice("Auto")) else next
            }
            if (resample) capture.sampleEffectPalette(effect.id)
        }
    }
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
    if (effect.kind == VideoEffectID.AutoBackground) Text(stringResource(R.string.live_effects_auto_background_note), color = Soft, fontSize = 12.sp)
    if (effect.kind == VideoEffectID.ChromaKey) Text(stringResource(R.string.live_effects_chroma_note), color = Soft, fontSize = 12.sp)
    if (effect.kind == VideoEffectID.ChromaKey && effect.toggle("personHint")) Text(stringResource(R.string.live_effects_person_hint_note), color = Soft, fontSize = 12.sp)
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
    // A picked picture is placed on the preview with the fingers. LiveEffectsPanel.swift:158-160.
    if ((effect.kind == VideoEffectID.ChromaKey || effect.kind == VideoEffectID.AutoBackground) && effect.choice("background") in listOf("Image", "Video") &&
        (effect.value("media", 0.0) as? EffectValue.Media)?.file != null) {
        TextButton(onClick = { capture.beginBackgroundPosition(effect.id) }, modifier = Modifier.heightIn(min = 44.dp)) {
            Icon(Icons.Outlined.OpenWith, null, Modifier.size(18.dp))
            Text(stringResource(R.string.live_effects_position), Modifier.padding(start = 6.dp))
        }
    }
    when (effect.kind) {
        VideoEffectID.ColorCorrection -> CorrectionControls(effect, 0.0, set) { update { it.copy(parameters = VideoEffectSettings(VideoEffectID.ColorCorrection).parameters) } }
        // The pads in place of the six sliders, drawn as the preview shows the picture, then the strength. LiveEffectsPanel.swift:163-165.
        VideoEffectID.RgbOffset -> {
            val front = capture.cameras.firstOrNull { it.id == capture.cameraID }?.front == true
            val mirrored = front && capture.settings.mirrorPreview != capture.settings.mirrorRecording
            RgbOffsetPads(effect, 0.0, mirrored) { values -> update { e -> values.entries.fold(e) { a, (id, v) -> a.with(id, EffectValue.Number(v)) } } }
            effect.parameters.firstOrNull { it.id == "intensity" }?.let { EffectControl(effect, it, 0.0, set) }
        }
        else -> {
            val drawn = if (effect.kind in LiveMoves.KINDS) effect else AndroidVideoEffects.asDrawn(effect)
            for (section in VideoEffectParameterSection.sections(drawn, 0.0, live = true)) {
                val parameters = effect.parameters.filter { parameter ->
                    VideoEffectParameterSection.section(parameter, effect) == section &&
                        VideoEffectParameterVisibility.visible(parameter, drawn, 0.0, live = true) &&
                        (effect.kind in LiveMoves.KINDS || !AndroidVideoEffects.hides(effect.kind, parameter.id))
                }
                if (parameters.isEmpty()) continue
                if (section != VideoEffectParameterSection.General) Text(sectionTitle(section), color = Color.White, fontWeight = FontWeight.SemiBold)
                for (parameter in parameters) {
                    when {
                        parameter.kind is EffectParameterKind.Media -> {
                            val file = (effect.value(parameter.id, 0.0) as? EffectValue.Media)?.file
                            val kind = when {
                                parameter.id == "cleanPlate" -> EffectMediaKind.Plate
                                effect.choice("background") == "Video" -> EffectMediaKind.Movie
                                else -> EffectMediaKind.Picture
                            }
                            // The clean plate: captured from the empty screen, or a picture of the frame's shape. LiveEffectsPanel.swift:258-271.
                            if (kind == EffectMediaKind.Plate) {
                                Text(controlName(effect.kind, parameter), color = Color.White, fontSize = 14.sp)
                                Text(stringResource(R.string.live_effects_plate_hint), color = Soft, fontSize = 12.sp)
                                TextButton(onClick = { refused = null; capture.captureCleanPlate(effect.id) }, enabled = capture.plateProgress == null, modifier = Modifier.heightIn(min = 44.dp)) {
                                    Icon(Icons.Outlined.CenterFocusWeak, null, Modifier.size(18.dp))
                                    Text(stringResource(R.string.live_effects_capture_plate), Modifier.padding(start = 6.dp))
                                }
                                if (file != null) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(stringResource(R.string.effects_plate_set), color = Color(0xFF63E6BE), fontSize = 12.sp, modifier = Modifier.weight(1f))
                                        TextButton(onClick = { set(parameter.id, EffectValue.Media(null)) }, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.effects_remove_media)) }
                                    }
                                    val fits = capture.plateFits(file)
                                    Text(stringResource(if (fits) R.string.live_effects_plate_again else R.string.live_effects_plate_mismatch), color = if (fits) Soft else Orange, fontSize = 12.sp)
                                }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                if (kind != EffectMediaKind.Plate) Text(controlName(effect.kind, parameter), color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { choose(parameter.id, kind) }, modifier = Modifier.heightIn(min = 44.dp)) {
                                    Text(stringResource(when {
                                        kind == EffectMediaKind.Movie -> if (file == null) R.string.effects_pick_movie else R.string.effects_change_movie
                                        else -> if (file == null || kind == EffectMediaKind.Plate) R.string.effects_pick_picture else R.string.effects_change_picture
                                    }))
                                }
                            }
                            when (refused?.takeIf { it.first == kind }?.second) {
                                EffectMediaPicked.Mismatch -> R.string.effects_plate_mismatch
                                EffectMediaPicked.Unreadable -> if (kind == EffectMediaKind.Movie) R.string.effects_movie_failed else R.string.effects_picture_failed
                                else -> null
                            }?.let { Text(stringResource(it), color = Orange, fontSize = 12.sp) }
                        }
                        parameter.id == "screenColor" -> ScreenColor(effect, parameter, set) { capture.beginColorPick(effect.id, parameter.id) }
                        else -> {
                            EffectControl(effect, parameter, 0.0, set)
                            if (effect.kind == VideoEffectID.ColorIsolation && parameter.id == "color") {
                                TextButton(onClick = { capture.beginColorPick(effect.id, parameter.id) }, modifier = Modifier.heightIn(min = 44.dp)) {
                                    Icon(Icons.Outlined.Colorize, null, Modifier.size(18.dp))
                                    Text(stringResource(R.string.live_effects_pick_from_preview), Modifier.padding(start = 6.dp))
                                }
                            }
                        }
                    }
                }
            }
            if (effect.kind == VideoEffectID.Kaleidoscope) {
                // The guide on the picture moves the split and the source (LiveKaleidoscopeGuide). KaleidoscopeGuide.swift:283.
                Text(stringResource(R.string.kaleidoscope_controls_hint), color = Soft, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { update { it.with("centerX", EffectValue.Number(0.5)).with("centerY", EffectValue.Number(0.5)).with("splitAngle", EffectValue.Number(0.0)) } }) {
                        Text(stringResource(R.string.kaleidoscope_reset_split))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { update { it.with("sourcePanX", EffectValue.Number(0.0)).with("sourcePanY", EffectValue.Number(0.0)) } }) {
                        Text(stringResource(R.string.kaleidoscope_center_source))
                    }
                }
            }
        }
    }
    // Try strobe: the burst in the preview, which shows a strobe only so or while recording. LiveEffectsPanel.swift:180-183.
    if (effect.kind == VideoEffectID.NegativeStrobe) {
        TextButton(onClick = { capture.tryStrobe(effect.id) }, enabled = effect.enabled, modifier = Modifier.heightIn(min = 44.dp)) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
            Text(stringResource(R.string.live_effects_try_strobe), Modifier.padding(start = 6.dp))
        }
    }
    // A Quantization's palette from this frame. LiveEffectsPanel.swift:184-194.
    if (effect.kind == VideoEffectID.Quantization) {
        if (effect.toggle("lockPalette")) {
            TextButton(onClick = { capture.sampleEffectPalette(effect.id) }, enabled = capture.paletteSampling == null) { Text(stringResource(R.string.quantization_resample)) }
        }
        if (!effect.toggle("monochrome")) {
            TextButton(onClick = { capture.sampleEffectPalette(effect.id, save = true) }, enabled = capture.paletteSampling == null) {
                Text(stringResource(if (capture.paletteSampling == effect.id) R.string.quantization_sampling else R.string.quantization_save_palette))
            }
            effect.palette?.let { colors ->
                Row(Modifier.fillMaxWidth().height(24.dp).clip(RoundedCornerShape(6.dp))) {
                    for (c in colors) Box(Modifier.weight(1f).height(24.dp).background(Color(c.red.toFloat(), c.green.toFloat(), c.blue.toFloat())))
                }
            }
        }
    }
    if (effect.kind == VideoEffectID.CrashZoom) Text(stringResource(R.string.crash_note), color = Soft, fontSize = 12.sp)
    if (effect.kind == VideoEffectID.Undercranking) Text(stringResource(R.string.live_effects_undercranking_note), color = Soft, fontSize = 12.sp)
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

/** A Chroma Key's screen colour: green, blue, or sampled from the preview. VideoEffectsEditor.swift:153-172. */
@Composable
internal fun ScreenColor(effect: VideoEffectSettings, parameter: EffectParameter, set: (String, EffectValue) -> Unit, sample: () -> Unit) {
    val color = (effect.value(parameter.id, 0.0) as? EffectValue.Color)?.value ?: EffectRGB(0.0, 1.0, 0.0)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(controlName(effect.kind, parameter), color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Box(Modifier.size(24.dp).clip(CircleShape).background(Color(color.red.toFloat(), color.green.toFloat(), color.blue.toFloat())))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { set(parameter.id, EffectValue.Color(EffectRGB(0.0, 1.0, 0.0))) }, modifier = Modifier.heightIn(min = 44.dp)) { Text(choiceName(effect.kind, "Green")) }
        TextButton(onClick = { set(parameter.id, EffectValue.Color(EffectRGB(0.0, 0.0, 1.0))) }, modifier = Modifier.heightIn(min = 44.dp)) { Text(choiceName(effect.kind, "Blue")) }
        val label = stringResource(R.string.live_effects_sample_screen)
        TextButton(onClick = sample, modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = label }) {
            Icon(Icons.Outlined.Colorize, null, Modifier.size(18.dp))
            Text(stringResource(R.string.live_effects_sample), Modifier.padding(start = 6.dp))
        }
    }
    if (!ChromaScreen.accepts(color)) Text(stringResource(R.string.live_effects_screen_saved_invalid), color = Orange, fontSize = 12.sp)
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
    val quality = stringResource(if (capture.lookQuality == null) R.string.live_effects_full_quality else R.string.live_effects_adaptive_quality)
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

/** The note that the phone is warm, under the top bar, as the original's (RecorderView.swift:430-434). */
@Composable
internal fun WarmBanner(capture: CaptureController, modifier: Modifier) {
    val detail = capture.lookQuality?.let { qualityNote(it) }.orEmpty()
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 12.dp, vertical = 7.dp)
            .semantics(mergeDescendants = true) { stateDescription = detail },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Outlined.Thermostat, null, Modifier.size(16.dp), tint = Color.White)
        Text(stringResource(R.string.live_effects_warm_banner), color = Color.White, fontSize = 12.sp)
    }
}

/**
 * Placing a picked picture on the preview: a drag moves it and a pinch scales it about the fingers, in fractions of
 * the recorded frame as the preview shows it, with the movements as actions for a screen reader.
 * CaptureController.swift:302-314, 3222-3235.
 */
@Composable
internal fun BackgroundPlacing(capture: CaptureController, view: android.view.View, modifier: Modifier) {
    val settings = capture.settings
    val front = capture.cameras.firstOrNull { it.id == capture.cameraID }?.front == true
    val unmirror = front && settings.mirrorPreview != settings.mirrorRecording
    val names = listOf(
        stringResource(R.string.live_effects_move_left), stringResource(R.string.live_effects_move_right), stringResource(R.string.live_effects_move_up),
        stringResource(R.string.live_effects_move_down), stringResource(R.string.live_effects_enlarge), stringResource(R.string.live_effects_shrink),
    )
    val hint = stringResource(R.string.live_effects_position_accessibility)
    fun step(gesture: VideoEffectPlacementGesture): Boolean {
        capture.moveBackground(gesture)
        capture.moveBackground(VideoEffectPlacementGesture.Ended)
        return true
    }
    val middle = Point(0.5, 0.5)
    Box(
        modifier.pointerInput(capture) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                // The frame as the preview shows it: filling the view or fitted in it.
                val width = view.width.toDouble()
                val height = view.height.toDouble()
                val aspect = if (width < height) 9.0 / 16 else 16.0 / 9
                val scale = if (!settings.fitFrame) maxOf(width / aspect, height) else minOf(width / aspect, height)
                val shownWidth = scale * aspect
                fun frame(x: Float, y: Float): Point {
                    val px = (x - (width - shownWidth) / 2) / shownWidth
                    return Point(if (unmirror) 1 - px else px, (y - (height - scale) / 2) / scale)
                }
                if (zoom != 1f) {
                    capture.moveBackground(VideoEffectPlacementGesture.Pinch(zoom.toDouble(), frame(centroid.x - pan.x, centroid.y - pan.y), frame(centroid.x, centroid.y)))
                } else {
                    capture.moveBackground(VideoEffectPlacementGesture.Pan(Point((if (unmirror) -pan.x else pan.x) / shownWidth, pan.y / scale)))
                }
            }
        }.semantics {
            contentDescription = hint
            customActions = listOf(
                CustomAccessibilityAction(names[0]) { step(VideoEffectPlacementGesture.Pan(Point(-0.05, 0.0))) },
                CustomAccessibilityAction(names[1]) { step(VideoEffectPlacementGesture.Pan(Point(0.05, 0.0))) },
                CustomAccessibilityAction(names[2]) { step(VideoEffectPlacementGesture.Pan(Point(0.0, -0.05))) },
                CustomAccessibilityAction(names[3]) { step(VideoEffectPlacementGesture.Pan(Point(0.0, 0.05))) },
                CustomAccessibilityAction(names[4]) { step(VideoEffectPlacementGesture.Pinch(1.1, middle, middle)) },
                CustomAccessibilityAction(names[5]) { step(VideoEffectPlacementGesture.Pinch(1 / 1.1, middle, middle)) },
            )
        },
    )
}

/**
 * The Kaleidoscope's guide on the recorder's picture while its controls are open: the recorded frame as the preview
 * shows it, filling the view or fitted in it, mirrored where the selfie preview is shown mirrored against the
 * recording. RecorderView.swift:179-185, CaptureController.swift:501-504.
 */
@Composable
internal fun LiveKaleidoscopeGuide(capture: CaptureController, modifier: Modifier) {
    val split = capture.liveEffects.firstOrNull { it.kind == VideoEffectID.Kaleidoscope && it.enabled } ?: return
    val settings = capture.settings
    val front = capture.cameras.firstOrNull { it.id == capture.cameraID }?.front == true
    val unmirror = front && settings.mirrorPreview != settings.mirrorRecording
    Box(modifier) {
        KaleidoscopeGuideView(
            app.takes.core.KaleidoscopePose.of(split), split.number("divisions").toInt(),
            projection = { width, height, _ ->
                val aspect = if (width < height) 9.0 / 16 else 16.0 / 9
                val scale = if (!settings.fitFrame) maxOf(width / aspect, height.toDouble()) else minOf(width / aspect, height.toDouble())
                val shownWidth = scale * aspect
                val x0 = (width - shownWidth) / 2
                val y0 = (height - scale) / 2
                if (unmirror) app.takes.core.Affine(-shownWidth, 0.0, 0.0, scale, x0 + shownWidth, y0) else app.takes.core.Affine(shownWidth, 0.0, 0.0, scale, x0, y0)
            },
        ) { pose, target ->
            val values = pose.parameters(target)
            capture.setLiveEffects(capture.liveEffects.map { e -> if (e.id == split.id) values.entries.fold(e) { a, (id, v) -> a.with(id, EffectValue.Number(v)) } else e })
        }
    }
}
