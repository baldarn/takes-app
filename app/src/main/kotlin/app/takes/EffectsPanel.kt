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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import app.takes.core.EffectPalette
import app.takes.core.EffectParameterKind
import app.takes.core.EffectValue
import app.takes.core.IsolationHue
import app.takes.core.VideoEffectID
import app.takes.core.VideoEffectLibrarySection
import app.takes.core.VideoEffectParameterSection
import app.takes.core.VideoEffectParameterVisibility
import app.takes.core.VideoEffectSettings
import app.takes.core.librarySection
import kotlinx.coroutines.launch
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
    modifier: Modifier = Modifier, appliesTo: String? = null, tools: EffectTools = EffectTools(),
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
                    if (effect == null) AppliedList(work, active, time, change) else Inspector(work, effect, time, change, tools)
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
private fun Inspector(work: EffectsWork, effect: VideoEffectSettings, time: Double, change: (EffectsWork) -> Unit, tools: EffectTools) {
    val name = effectName(effect.kind)
    val tint = effect.kind.librarySection.look.tint
    val at = work.sourceTime(time)
    var help by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val set = { id: String, value: EffectValue -> change(work.setting(id, value, time)) }
    val keys = KeyControls(
        animate = work.animate,
        toggle = { id -> change(work.keyframing(id, time)) },
        place = { source -> work.timelineTime(time, source) },
        seek = { source -> work.timelineTime(time, source)?.let(tools.seek) },
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        IconButton(onClick = { help = true }) { Icon(Icons.Outlined.HelpOutline, stringResource(R.string.effects_about, name), tint = Soft) }
        Spacer(Modifier.weight(1f))
        // Animate: changing a control adds a keyframe at the playhead. VideoEffectsEditor.swift:1209-1213.
        if (effect.kind != VideoEffectID.ColorCorrection) {
            val animateHint = stringResource(R.string.effects_animate_hint)
            FilterChip(
                work.animate, onClick = { change(work.copy(animate = !work.animate)) },
                label = { Text((if (work.animate) "◆ " else "◇ ") + stringResource(R.string.effects_animate)) },
                modifier = Modifier.padding(end = 8.dp).semantics { contentDescription = animateHint },
            )
        }
        val power = stringResource(if (effect.enabled) R.string.effects_disable_effect else R.string.effects_enable_effect)
        IconButton(
            onClick = { change(work.toggling(effect.id)) },
            modifier = Modifier.clip(CircleShape).background(if (effect.enabled) tint else Color.White.copy(alpha = 0.07f)).semantics { contentDescription = power },
        ) { Icon(Icons.Outlined.PowerSettingsNew, null, tint = if (effect.enabled) Color.Black else Soft) }
    }
    if (effect.kind !in AndroidVideoEffects.offered) Text(stringResource(R.string.effects_not_drawn), color = Color(0xFFFF9F0A), fontSize = 12.sp)
    else if (AndroidVideoEffects.previewSimplified(effect, at)) Text(stringResource(R.string.effects_preview_simplified), color = Soft, fontSize = 12.sp)
    when (effect.kind) {
        VideoEffectID.ColorCorrection -> CorrectionControls(effect, at, set) { change(work.resetting()) }
        VideoEffectID.Kaleidoscope -> {
            effect.parameters.firstOrNull { it.id == "divisions" }?.let { EffectControl(effect, it, at, set, keys) }
            var fine by rememberSaveable { mutableStateOf(true) }
            Disclosure(stringResource(R.string.kaleidoscope_fine), fine) { fine = !fine }
            if (fine) {
                for (id in listOf("sourceAngle", "sourcePanX", "sourcePanY", "splitAngle", "centerX", "centerY")) {
                    effect.parameters.firstOrNull { it.id == id }?.let { EffectControl(effect, it, at, set, keys) }
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
                    if (!VideoEffectParameterVisibility.visible(parameter, AndroidVideoEffects.asDrawn(effect), at) || AndroidVideoEffects.hides(effect.kind, parameter.id)) continue
                    if (parameter.kind is EffectParameterKind.Media) PictureControl(effect, parameter, at, set, tools) else EffectControl(effect, parameter, at, set, keys)
                }
            }
            if (effect.kind == VideoEffectID.Quantization) PaletteControls(work, effect, time, change, tools)
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
private fun EffectControl(effect: VideoEffectSettings, parameter: EffectParameter, at: Double, set: (String, EffectValue) -> Unit, keys: KeyControls? = null) {
    val title = controlName(effect.kind, parameter)
    val value = effect.value(parameter.id, at) ?: parameter.value
    Column(Modifier.fillMaxWidth()) {
        if (keys != null) Keyframes(parameter, title, at, keys)
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
                    for (choice in AndroidVideoEffects.choices(effect.kind, parameter.id, VideoEffectParameterVisibility.choices(parameter, effect))) {
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
            // A Chroma Key's screen and background: any colour, by its red, green and blue.
            is EffectParameterKind.Color -> if (effect.kind != VideoEffectID.ColorIsolation) {
                val color = (value as? EffectValue.Color)?.value ?: kind.defaultValue
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("#%02X%02X%02X".format((color.red * 255).roundToInt(), (color.green * 255).roundToInt(), (color.blue * 255).roundToInt()), color = Soft, fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
                    Box(Modifier.size(24.dp).clip(CircleShape).background(Color(color.red.toFloat(), color.green.toFloat(), color.blue.toFloat())))
                }
                val channels = listOf(R.string.effect_channel_red to color.red, R.string.effect_channel_green to color.green, R.string.effect_channel_blue to color.blue)
                channels.forEachIndexed { index, (name, level) ->
                    val spoken = "$title, ${stringResource(name)}"
                    val label = "${(level * 255).roundToInt()}"
                    Slider(
                        value = (level * 255).toFloat(), valueRange = 0f..255f,
                        onValueChange = { v ->
                            val c = v.roundToInt() / 255.0
                            set(parameter.id, EffectValue.Color(when (index) { 0 -> color.copy(red = c); 1 -> color.copy(green = c); else -> color.copy(blue = c) }))
                        },
                        modifier = Modifier.height(32.dp).semantics { contentDescription = spoken; stateDescription = label },
                    )
                }
            } else {
                // Color Isolation's: the hue it keeps, chosen round the colour wheel.
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
    var tab by rememberSaveable { mutableIntStateOf(0) }
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


/**
 * What the Video effects tool asks of the editor: a picture picked from the
 * phone and kept in the project (its file name, or null when it could not
 * be), and a palette taken from one frame of a recording.
 */
internal class EffectTools(
    val pickPicture: (picked: (String?) -> Unit) -> Unit = { it(null) },
    /** Moves the playhead to a timeline second. */
    val seek: (Double) -> Unit = {},
    val samplePalette: suspend (sourceID: String, time: Double, count: Int) -> EffectPalette? = { _, _, _ -> null },
)

/** A picture control: a Chroma Key's Image background, chosen from the phone. */
@Composable
private fun PictureControl(effect: VideoEffectSettings, parameter: EffectParameter, at: Double, set: (String, EffectValue) -> Unit, tools: EffectTools) {
    val file = (effect.value(parameter.id, at) as? EffectValue.Media)?.file
    var failed by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(controlName(effect.kind, parameter), color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = {
            tools.pickPicture { name -> if (name != null) { failed = false; set(parameter.id, EffectValue.Media(name)) } else failed = true }
        }) { Text(stringResource(if (file == null) R.string.effects_pick_picture else R.string.effects_change_picture)) }
    }
    if (failed) Text(stringResource(R.string.effects_picture_failed), color = Color(0xFFFF9F0A), fontSize = 12.sp)
}

/**
 * Quantization's palette from one frame: Re-sample while the palette is
 * locked, and Save palette, which keeps it in the project as the Saved
 * choice. VideoEffectsEditor.swift:883-907, :1174-1182.
 */
@Composable
private fun PaletteControls(work: EffectsWork, effect: VideoEffectSettings, time: Double, change: (EffectsWork) -> Unit, tools: EffectTools) {
    val latest by rememberUpdatedState(work)
    val scope = rememberCoroutineScope()
    var sampling by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val at = work.sourceTime(time)
    val count = effect.number("colors", at).toInt()
    val locked = stringResource(R.string.quantization_locked, count)
    val saved = stringResource(R.string.quantization_saved)
    val failed = stringResource(R.string.quantization_sample_failed)
    fun sample(lockOnly: Boolean) {
        val piece = work.piece(time) ?: return
        sampling = true
        scope.launch {
            val palette = tools.samplePalette(piece.clip.sourceID, at, count)
            sampling = false
            if (palette == null) {
                message = failed
                return@launch
            }
            val next = if (lockOnly) {
                latest.changing(effect.id) { it.copy(lockedPalette = palette) }.setting("lockPalette", EffectValue.Toggle(true), time)
            } else {
                latest.changing(effect.id) { it.copy(palette = palette.colors) }.setting("palette", EffectValue.Choice("Saved"), time)
            }
            change(next)
            message = if (lockOnly) locked else saved
        }
    }
    if (effect.toggle("lockPalette", at)) {
        TextButton(onClick = { sample(lockOnly = true) }, enabled = !sampling) { Text(stringResource(R.string.quantization_resample)) }
    }
    if (!effect.toggle("monochrome", at)) {
        TextButton(onClick = { sample(lockOnly = false) }, enabled = !sampling) {
            Text(stringResource(if (sampling) R.string.quantization_sampling else R.string.quantization_save_palette))
        }
        effect.palette?.let { colors ->
            Row(Modifier.fillMaxWidth().height(24.dp).clip(RoundedCornerShape(6.dp))) {
                for (c in colors) Box(Modifier.weight(1f).height(24.dp).background(Color(c.red.toFloat(), c.green.toFloat(), c.blue.toFloat())))
            }
        }
    }
    message?.let { Text(it, color = Soft, fontSize = 12.sp) }
}

/** What a control's keyframes do: whether Animate is on, a key added or taken away at the playhead, the playhead moved to a key's source second. */
private class KeyControls(val animate: Boolean, val toggle: (String) -> Unit, val place: (Double) -> Double?, val seek: (Double) -> Unit)

/**
 * A control's keyframes: a diamond that adds or takes away the key at the
 * playhead, while animating or once it has keys, and with keys the previous
 * one, how many there are (a list to go to one) and the next.
 * VideoEffectsEditor.swift:1275-1279, :1320-1336.
 */
@Composable
private fun Keyframes(parameter: EffectParameter, title: String, at: Double, keys: KeyControls) {
    if (!parameter.animatable || parameter.id == "view" || !(keys.animate || parameter.keyframes.isNotEmpty())) return
    val here = parameter.keyframes.any { kotlin.math.abs(it.time - at) < 1.0 / 60 }
    val previous = parameter.keyframes.lastOrNull { it.time < at - 1.0 / 60 }
    val next = parameter.keyframes.firstOrNull { it.time > at + 1.0 / 60 }
    var listing by remember { mutableStateOf(false) }
    val keyLabel = stringResource(R.string.effects_keyframe_for, title)
    val previousLabel = stringResource(R.string.effects_previous_keyframe, title)
    val nextLabel = stringResource(R.string.effects_next_keyframe, title)
    val count = pluralStringResource(R.plurals.effects_keyframes, parameter.keyframes.size, parameter.keyframes.size)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { keys.toggle(parameter.id) }, modifier = Modifier.semantics { contentDescription = keyLabel }) { Text(if (here) "◆" else "◇", color = Color.White) }
        if (parameter.keyframes.isNotEmpty()) {
            TextButton(onClick = { previous?.let { keys.seek(it.time) } }, enabled = previous != null, modifier = Modifier.semantics { contentDescription = previousLabel }) { Text("⏮") }
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { listing = true }, modifier = Modifier.fillMaxWidth()) { Text(count, fontSize = 12.sp) }
                androidx.compose.material3.DropdownMenu(expanded = listing, onDismissRequest = { listing = false }) {
                    for (key in parameter.keyframes) {
                        androidx.compose.material3.DropdownMenuItem(text = { Text(timeLabel(keys.place(key.time) ?: key.time)) }, onClick = { listing = false; keys.seek(key.time) })
                    }
                }
            }
            TextButton(onClick = { next?.let { keys.seek(it.time) } }, enabled = next != null, modifier = Modifier.semantics { contentDescription = nextLabel }) { Text("⏭") }
        }
    }
}
