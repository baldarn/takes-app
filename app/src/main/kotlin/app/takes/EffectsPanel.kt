package app.takes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takes.core.ColorCorrectionControls
import app.takes.core.EffectParameter
import app.takes.core.EffectParameterKind
import app.takes.core.EffectValue
import app.takes.core.IsolationHue
import app.takes.core.VideoEffectID
import app.takes.core.VideoEffectLibrarySection
import app.takes.core.VideoEffectParameterSection
import app.takes.core.VideoEffectParameterVisibility
import app.takes.core.VideoEffectSettings
import app.takes.core.librarySection
import kotlin.math.roundToInt

private val Soft = Color(0xFFA0A4AB)
private val Card = Color.White.copy(alpha = 0.045f)

/**
 * The Video effects tool, in place of the tools under the timeline, as the
 * original's editor (YouRec/VideoEffects/VideoEffectsEditor.swift): the
 * effects at the playhead, a library in sections to add from, and the
 * controls of one effect, made from the kinds of its parameters.
 *
 * @param work the tool's state; [change] takes it after every control.
 * @param time the playhead: which clip's effects are listed, and the source second controls are read at.
 * @param apply saves the effects on the tool's clips as one Undo step.
 */
@Composable
internal fun EffectsPanel(
    work: EffectsWork, time: Double, change: (EffectsWork) -> Unit, apply: () -> Unit, cancel: () -> Unit,
    modifier: Modifier = Modifier, appliesTo: String? = null,
) {
    val active = work.active(time)
    Column(modifier.fillMaxWidth().background(Color(0xFF1B1D21))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = cancel) { Text(stringResource(R.string.common_cancel)) }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.editor_effects), color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                appliesTo?.let { Text(it, color = Soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            TextButton(onClick = apply) { Text(stringResource(R.string.crop_apply), fontWeight = FontWeight.Bold) }
        }
        NavigationRow(work, active.size, change)
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (val page = work.page) {
                EffectsPage.Applied -> AppliedList(work, active, time, change)
                EffectsPage.Library -> Library(null) { change(work.copy(page = EffectsPage.Section(it))) }
                is EffectsPage.Section -> {
                    Library(page.section) {}
                    SectionEffects(page.section) { kind -> change(work.adding(kind)) }
                }
                EffectsPage.Inspector -> {
                    val effect = work.current(time)
                    if (effect == null) AppliedList(work, active, time, change) else Inspector(work, effect, time, change)
                }
            }
        }
    }
}

/** Back, where the tool is, comparing with the original, and Add. VideoEffectsEditor.swift:48-78. */
@Composable
private fun NavigationRow(work: EffectsWork, count: Int, change: (EffectsWork) -> Unit) {
    val page = work.page
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val canGoBack = page != EffectsPage.Applied && (page != EffectsPage.Library || count > 0)
        if (canGoBack) {
            IconButton(onClick = {
                change(work.copy(page = if (page is EffectsPage.Section) EffectsPage.Library else EffectsPage.Applied))
            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.effects_back), tint = Color.White) }
        } else Spacer(Modifier.width(12.dp))
        val title = when (page) {
            EffectsPage.Applied -> stringResource(R.string.effects_applied_count, count)
            EffectsPage.Library -> stringResource(R.string.effects_add_title)
            is EffectsPage.Section -> stringResource(page.section.look.title)
            EffectsPage.Inspector -> stringResource(R.string.effects_back_count, count)
        }
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        // The picture with or without the effects, as the original's eye over its preview.
        if (work.hasEffects) {
            val hint = stringResource(R.string.effects_compare_hint)
            val state = stringResource(if (work.bypass) R.string.effects_compare_original else R.string.effects_compare_effects)
            TextButton(onClick = { change(work.copy(bypass = !work.bypass)) }, modifier = Modifier.semantics { contentDescription = hint; stateDescription = state }) {
                Icon(if (work.bypass) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null, Modifier.size(18.dp))
                Text(state, Modifier.padding(start = 6.dp), fontSize = 13.sp)
            }
        }
        if (page == EffectsPage.Applied || page == EffectsPage.Inspector) {
            TextButton(onClick = { change(work.copy(page = EffectsPage.Library)) }) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Text(stringResource(R.string.effects_add), Modifier.padding(start = 4.dp))
            }
        }
    }
}

/** The effects at the playhead, each with its switch and a delete button. VideoEffectsEditor.swift:174-208, :1112-1125. */
@Composable
private fun AppliedList(work: EffectsWork, active: List<VideoEffectSettings>, time: Double, change: (EffectsWork) -> Unit) {
    if (active.isEmpty()) {
        Text(stringResource(R.string.effects_none_here), color = Soft)
        TextButton(onClick = { change(work.copy(page = EffectsPage.Library)) }) {
            Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
            Text(stringResource(R.string.effects_add_effect), Modifier.padding(start = 4.dp))
        }
        return
    }
    Text(stringResource(R.string.effects_at_playhead), color = Soft, fontSize = 12.sp)
    for (effect in active) {
        val look = effect.kind.librarySection.look
        val name = effectName(effect.kind)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card)
                .clickable { change(work.copy(selected = effect.id, page = EffectsPage.Inspector)) }.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(look.icon, null, tint = look.tint, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(name, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(stringResource(if (effect.enabled) R.string.effects_on_edit else R.string.effects_off_edit), color = Soft, fontSize = 12.sp)
            }
            val enable = stringResource(R.string.effects_enable, name)
            Switch(effect.enabled, onCheckedChange = { change(work.toggling(effect.id)) }, modifier = Modifier.semantics { contentDescription = enable })
            val delete = stringResource(R.string.effects_delete, name)
            IconButton(onClick = { change(work.removing(effect.id, time)) }) { Icon(Icons.Outlined.Delete, delete, tint = Soft) }
        }
    }
}

/** The four sections, or with [section] its line. Sections with nothing offered yet are left out. VideoEffectsEditor.swift:81-152. */
@Composable
private fun Library(section: VideoEffectLibrarySection?, choose: (VideoEffectLibrarySection) -> Unit) {
    if (section != null) {
        Text(stringResource(section.look.summary), color = Soft, fontSize = 14.sp)
        return
    }
    for (group in VideoEffectLibrarySection.entries) {
        val count = AndroidVideoEffects.effects(group).size
        if (count == 0) continue
        val look = group.look
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(look.tint.copy(alpha = 0.08f)).clickable { choose(group) }
                .heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp).semantics { role = Role.Button },
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

/** The offered effects of a section, each with its line; a tap adds it and opens its controls. */
@Composable
private fun SectionEffects(section: VideoEffectLibrarySection, add: (VideoEffectID) -> Unit) {
    val look = section.look
    for (kind in AndroidVideoEffects.effects(section)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).clickable { add(kind) }
                .heightIn(min = 68.dp).padding(12.dp).semantics { role = Role.Button },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(look.icon, null, tint = look.tint, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(effectName(kind), color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(effectSummary(kind), color = Soft, fontSize = 12.sp, lineHeight = 16.sp)
            }
            Icon(Icons.Outlined.AddCircleOutline, null, tint = look.tint)
        }
    }
}

/** One effect's controls: its name, help and switch, then what its parameters' kinds make. VideoEffectsEditor.swift:1126-1188. */
@Composable
private fun Inspector(work: EffectsWork, effect: VideoEffectSettings, time: Double, change: (EffectsWork) -> Unit) {
    val name = effectName(effect.kind)
    val tint = effect.kind.librarySection.look.tint
    val at = work.sourceTime(time)
    var help by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val set = { id: String, value: EffectValue -> change(work.setting(id, value, time)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        IconButton(onClick = { help = true }) { Icon(Icons.Outlined.HelpOutline, stringResource(R.string.effects_about, name), tint = Soft) }
        Spacer(Modifier.weight(1f))
        val power = stringResource(if (effect.enabled) R.string.effects_disable_effect else R.string.effects_enable_effect)
        IconButton(
            onClick = { change(work.toggling(effect.id)) },
            modifier = Modifier.clip(CircleShape).background(if (effect.enabled) tint else Color.White.copy(alpha = 0.07f)).semantics { contentDescription = power },
        ) { Icon(Icons.Outlined.PowerSettingsNew, null, tint = if (effect.enabled) Color.Black else Soft) }
    }
    if (effect.kind !in AndroidVideoEffects.offered) Text(stringResource(R.string.effects_not_drawn), color = Color(0xFFFF9F0A), fontSize = 12.sp)
    when (effect.kind) {
        VideoEffectID.ColorCorrection -> CorrectionControls(effect, at, set) { change(work.resetting()) }
        VideoEffectID.Kaleidoscope -> {
            effect.parameters.firstOrNull { it.id == "divisions" }?.let { EffectControl(effect, it, at, set) }
            var fine by rememberSaveable { mutableStateOf(true) }
            Disclosure(stringResource(R.string.kaleidoscope_fine), fine) { fine = !fine }
            if (fine) {
                for (id in listOf("sourceAngle", "sourcePanX", "sourcePanY", "splitAngle", "centerX", "centerY")) {
                    effect.parameters.firstOrNull { it.id == id }?.let { EffectControl(effect, it, at, set) }
                }
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { change(work.setting(mapOf("centerX" to 0.5, "centerY" to 0.5, "splitAngle" to 0.0), time)) }) { Text(stringResource(R.string.kaleidoscope_reset_split)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { change(work.setting(mapOf("sourcePanX" to 0.0, "sourcePanY" to 0.0), time)) }) { Text(stringResource(R.string.kaleidoscope_center_source)) }
                }
            }
        }
        else -> {
            for (section in VideoEffectParameterSection.sections(effect, at)) {
                if (section != VideoEffectParameterSection.General) Text(section.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                for (parameter in effect.parameters) {
                    if (VideoEffectParameterSection.section(parameter, effect) != section) continue
                    if (!VideoEffectParameterVisibility.visible(parameter, effect, at) || AndroidVideoEffects.hides(effect.kind, parameter.id)) continue
                    EffectControl(effect, parameter, at, set)
                }
            }
            if (effect.kind == VideoEffectID.RgbOffset) {
                TextButton(onClick = { change(work.setting(listOf("redX", "redY", "greenX", "greenY", "blueX", "blueY").associateWith { 0.0 }, time)) }) {
                    Text(stringResource(R.string.rgb_center_all))
                }
            }
        }
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { confirmRemove = true }) {
            Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp), tint = Color(0xFFFF6B6B))
            Text(stringResource(R.string.effects_remove), Modifier.padding(start = 6.dp), color = Color(0xFFFF6B6B))
        }
        Spacer(Modifier.weight(1f))
        if (effect.kind != VideoEffectID.ColorCorrection) TextButton(onClick = { confirmReset = true }) { Text(stringResource(R.string.effects_reset)) }
    }
    if (help) {
        val history = effectHistory(effect.kind)
        AlertDialog(
            onDismissRequest = { help = false },
            title = { Text(name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(effectHelp(effect.kind))
                    if (history != null) {
                        HorizontalDivider()
                        Text(stringResource(R.string.effects_film_technique), fontWeight = FontWeight.SemiBold)
                        Text(history, color = Soft)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { help = false }) { Text(stringResource(R.string.common_done)) } },
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.effects_remove_confirm, name)) },
            confirmButton = { TextButton(onClick = { confirmRemove = false; change(work.removing(effect.id, time)) }) { Text(stringResource(R.string.common_remove)) } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.effects_reset_confirm)) },
            text = { Text(stringResource(R.string.effects_reset_message)) },
            confirmButton = { TextButton(onClick = { confirmReset = false; change(work.resetting()) }) { Text(stringResource(R.string.common_reset)) } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/** A heading that opens and closes what follows it. */
@Composable
private fun Disclosure(title: String, open: Boolean, toggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = toggle).semantics { role = Role.Button },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Soft)
    }
}

/** A slider position on the control's steps. */
private fun stepped(value: Float, kind: EffectParameterKind.Slider): Double {
    val steps = ((value - kind.min) / kind.step).roundToInt()
    // Rounded off at a billionth, so that 39 steps of 0.05 save as 1.95.
    return (Math.round((kind.min + steps * kind.step) * 1e9) / 1e9).coerceIn(kind.min, kind.max)
}

/** One control, by the kind of its parameter. VideoEffectsEditor.swift:1236-1317. */
@Composable
private fun EffectControl(effect: VideoEffectSettings, parameter: EffectParameter, at: Double, set: (String, EffectValue) -> Unit) {
    val title = controlName(effect.kind, parameter)
    val value = effect.value(parameter.id, at) ?: parameter.value
    Column(Modifier.fillMaxWidth()) {
        when (val kind = parameter.kind) {
            is EffectParameterKind.Slider -> {
                val number = (value as? EffectValue.Number)?.value ?: kind.defaultValue
                val label = effectValueLabel(effect.kind, parameter.id, number)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(label, color = Soft, fontSize = 14.sp)
                }
                Slider(
                    value = number.toFloat(), onValueChange = { set(parameter.id, EffectValue.Number(stepped(it, kind))) },
                    valueRange = kind.min.toFloat()..kind.max.toFloat(),
                    modifier = Modifier.height(36.dp).semantics { contentDescription = title; stateDescription = label },
                )
            }
            is EffectParameterKind.Toggle -> {
                // The whole row switches, its name as well.
                val on = (value as? EffectValue.Toggle)?.value ?: kind.defaultValue
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).toggleable(on, role = Role.Switch) { set(parameter.id, EffectValue.Toggle(it)) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(on, onCheckedChange = null)
                }
            }
            is EffectParameterKind.Picker -> {
                Text(title, color = Color.White, fontSize = 14.sp)
                val chosen = (value as? EffectValue.Choice)?.value
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (choice in VideoEffectParameterVisibility.choices(parameter, effect)) {
                        FilterChip(chosen == choice, onClick = { set(parameter.id, EffectValue.Choice(choice)) }, label = { Text(choiceName(effect.kind, choice)) })
                    }
                }
            }
            is EffectParameterKind.Stepper -> {
                Text(title, color = Color.White, fontSize = 14.sp)
                val chosen = (value as? EffectValue.Number)?.value
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (number in kind.values) {
                        val spoken = "$title ${effectNumber(number)}"
                        FilterChip(chosen == number, onClick = { set(parameter.id, EffectValue.Number(number)) }, label = { Text(effectNumber(number)) },
                            modifier = Modifier.defaultMinSize(minWidth = 46.dp).semantics { contentDescription = spoken })
                    }
                }
            }
            // Of the offered effects only Color Isolation has a colour: the hue it keeps, chosen round the colour wheel.
            is EffectParameterKind.Color -> {
                val color = (value as? EffectValue.Color)?.value ?: kind.defaultValue
                val degrees = IsolationHue.degrees(color)
                val shown = IsolationHue.color(degrees)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("${degrees.roundToInt()}°", color = Soft, fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
                    Box(Modifier.size(24.dp).clip(CircleShape).background(Color(shown.red.toFloat(), shown.green.toFloat(), shown.blue.toFloat())))
                }
                val label = "${degrees.roundToInt()}°"
                Slider(
                    value = degrees.toFloat(), onValueChange = { set(parameter.id, EffectValue.Color(IsolationHue.color(it.roundToInt().toDouble()))) },
                    valueRange = 0f..359f, modifier = Modifier.height(36.dp).semantics { contentDescription = title; stateDescription = label },
                )
                if (!IsolationHue.accepts(color)) Text(stringResource(R.string.isolation_invalid_hue), color = Color(0xFFFF9F0A), fontSize = 12.sp)
            }
            is EffectParameterKind.Media -> Unit
        }
    }
}

/** The Color Correction in two tabs with its advanced light, as the original lays it out. VideoEffectsEditor.swift:1343-1383. */
@Composable
private fun CorrectionControls(effect: VideoEffectSettings, at: Double, set: (String, EffectValue) -> Unit, reset: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(tab == 0, onClick = { tab = 0 }, label = { Text(stringResource(R.string.correction_light)) })
        FilterChip(tab == 1, onClick = { tab = 1 }, label = { Text(stringResource(R.string.correction_color)) })
    }
    Text(stringResource(R.string.correction_note), color = Soft, fontSize = 12.sp)
    val group = @Composable { ids: List<String> ->
        for (id in ids) effect.parameters.firstOrNull { it.id == id }?.let { CorrectionSlider(effect, it, at, set, enabled = !(id == "pivot" && effect.number("contrast", at) == 1.0)) }
    }
    group(if (tab == 0) ColorCorrectionControls.light else ColorCorrectionControls.color)
    if (tab == 0) {
        Disclosure(stringResource(R.string.correction_advanced), advanced) { advanced = !advanced }
        if (advanced) {
            group(ColorCorrectionControls.advanced)
            if (effect.number("contrast", at) == 1.0) Text(stringResource(R.string.correction_pivot_note), color = Soft, fontSize = 12.sp)
        }
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
    effect.parameters.firstOrNull { it.id == "intensity" }?.let { CorrectionSlider(effect, it, at, set) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.correction_hold_help), color = Soft, fontSize = 12.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = { confirmReset = true }) { Text(stringResource(R.string.correction_reset)) }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.correction_reset_confirm)) },
            text = { Text(stringResource(R.string.correction_reset_message)) },
            confirmButton = { TextButton(onClick = { confirmReset = false; reset() }) { Text(stringResource(R.string.correction_reset_all)) } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/**
 * A Color Correction slider: holding its name explains it, a tap on its
 * value sets it back to neutral. VideoEffectsEditor.swift:1385-1418.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CorrectionSlider(effect: VideoEffectSettings, parameter: EffectParameter, at: Double, set: (String, EffectValue) -> Unit, enabled: Boolean = true) {
    val kind = parameter.kind as? EffectParameterKind.Slider ?: return
    val number = (effect.value(parameter.id, at) as? EffectValue.Number)?.value ?: kind.defaultValue
    val title = controlName(effect.kind, parameter)
    val shown = ColorCorrectionControls.display(parameter.id, number)
    var help by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title, color = if (enabled) Color.White else Soft, fontSize = 14.sp,
                modifier = Modifier.weight(1f).heightIn(min = 40.dp).combinedClickable(onLongClick = { help = true }, onClick = {}).padding(top = 10.dp),
            )
            val resetLabel = stringResource(R.string.correction_reset_value, title)
            TextButton(onClick = { set(parameter.id, EffectValue.Number(kind.defaultValue)) }, enabled = enabled,
                modifier = Modifier.defaultMinSize(minWidth = 68.dp).semantics { contentDescription = resetLabel; stateDescription = shown }) { Text(shown) }
        }
        Slider(
            value = number.toFloat(), onValueChange = { set(parameter.id, EffectValue.Number(stepped(it, kind))) }, enabled = enabled,
            valueRange = kind.min.toFloat()..kind.max.toFloat(),
            modifier = Modifier.height(32.dp).semantics { contentDescription = title; stateDescription = shown },
        )
    }
    if (help) {
        AlertDialog(
            onDismissRequest = { help = false },
            title = { Text(title) },
            text = { Text(stringResource(correctionHelp(parameter.id))) },
            confirmButton = { TextButton(onClick = { help = false }) { Text(stringResource(R.string.correction_got_it)) } },
        )
    }
}

