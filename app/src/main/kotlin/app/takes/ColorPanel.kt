package app.takes

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takes.core.ColorControl
import app.takes.core.ColorLook
import app.takes.core.ColorSettings
import app.takes.core.EditState
import app.takes.core.ImportedLUT
import app.takes.core.RangeEditing
import app.takes.core.ToolCompare
import kotlinx.coroutines.launch

private val Soft = Color(0xFFA0A4AB)
private val RemoveRed = Color(0xFFFF6961)

/**
 * The Color tool at work, as the original's tool workspace keeps it
 * (YouRec/EditorTools.swift:170-190, 700-722): the grade being chosen for the
 * clips [ids] of [base], and whether the preview shows Before. [base] is the
 * edit the tool opened on, cut at the ends of a range ([scoped]); [from] is
 * the saved edit then, which a change from elsewhere ends the tool for.
 */
internal class Coloring(
    val from: EditState, val base: EditState, val ids: List<String>, val scoped: RangeEditing.Scoped?, val label: String?, initial: ColorSettings,
) {
    var color by mutableStateOf(initial)
    /** Before: the clips without their grade (EditorTools.swift:369, 721). */
    var original by mutableStateOf(false)
    /** Whether the clips had a grade when the tool opened: it can then be removed (EditorTools.swift:1096-1100). */
    val existing = base.clips.any { it.id in ids && it.color != null }

    private var made: Pair<ColorSettings?, EditState>? = null

    /** What the editor shows: the clips with the grade, or Before without it. */
    val shown: EditState
        get() {
            val grade = if (original) null else color
            made?.let { if (it.first == grade) return it.second }
            return ToolCompare.colored(base, ids, grade).also { made = grade to it }
        }

    /** The edit with the grade applied, or with it removed. */
    fun applied(removing: Boolean = false): EditState = ToolCompare.colored(base, ids, if (removing) null else color)
}

/**
 * The Color tool, in place of the timeline, with the editor's preview showing
 * its grade as it changes, as the original's tool shows its own preview
 * (YouRec/EditorTools.swift:1072-1151): Cancel and Apply, Before and After,
 * and the original's colour controls (YouRec/ColorGradeControls.swift). Nothing
 * is saved until [apply]; [remove] takes the grade off the clips.
 */
@Composable
internal fun ColorPanel(
    work: Coloring, importLut: suspend (Uri) -> ImportedLUT?, apply: () -> Unit, remove: () -> Unit, cancel: () -> Unit, modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().background(Color(0xFF1B1D21))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = cancel) { Text(stringResource(R.string.common_cancel)) }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.color_tool_title), color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                work.label?.let { Text(it, color = Soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            TextButton(onClick = apply) { Text(stringResource(R.string.crop_apply), fontWeight = FontWeight.Bold) }
        }
        BeforeAfter(work.original, { work.original = it })
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ColorGradeControls(work, importLut)
            if (work.existing) {
                // Remove look, when the tool was opened on clips with a grade (EditorTools.swift:1096-1100).
                TextButton(onClick = remove, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                    Icon(Icons.Outlined.Delete, null, tint = RemoveRed)
                    Text(stringResource(R.string.color_remove), color = RemoveRed, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

/**
 * The controls of the original's Color tool, in its order (YouRec/ColorGradeControls.swift:12-121): the name of the
 * look or LUT, its strength, Highlights, exposure and white balance behind a disclosure, and the looks.
 */
@Composable
private fun ColorGradeControls(work: Coloring, importLut: suspend (Uri) -> ImportedLUT?) {
    val color = work.color
    fun change(next: ColorSettings) { work.color = next }
    val scope = rememberCoroutineScope()
    val pickLut = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        // An imported LUT is chosen at full strength, as the original does (EditorTools.swift:561).
        if (uri != null) scope.launch { importLut(uri)?.let { change(work.color.copy(imported = it, look = ColorLook.Natural, amount = 1.0)) } }
    }
    var balanceOpen by remember { mutableStateOf(false) }

    Text(color.label(), color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    // Strength does nothing to Natural without a LUT, and is off there as in the original (ColorGradeControls.swift:26).
    Level(stringResource(R.string.color_strength), color.amount, ColorControl.Strength, enabled = color.look != ColorLook.Natural || color.imported != null) {
        change(work.color.copy(amount = it))
    }
    // A correction left at zero is stored as nothing, as in the original.
    val highlights = color.highlights ?: 0.0
    Level(stringResource(R.string.color_highlights), highlights, ColorControl.Highlights, reset = stringResource(R.string.color_reset_highlights) to { change(work.color.copy(highlights = null)) }) {
        change(work.color.copy(highlights = it.takeIf { it != 0.0 }))
    }
    Text(stringResource(R.string.color_highlights_help), color = Soft, fontSize = 12.sp)
    // Exposure and white balance, folded away until opened (ColorGradeControls.swift:68-75).
    val group = stringResource(R.string.color_balance_group)
    val state = stringResource(if (balanceOpen) R.string.common_expanded else R.string.common_collapsed)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button) { balanceOpen = !balanceOpen }
            .semantics { contentDescription = group; stateDescription = state },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(group, color = Color.White, modifier = Modifier.weight(1f))
        Icon(if (balanceOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Soft)
    }
    if (balanceOpen) {
        Level(stringResource(R.string.color_exposure), color.exposure ?: 0.0, ColorControl.Exposure) { change(work.color.copy(exposure = it.takeIf { it != 0.0 })) }
        Level(stringResource(R.string.color_warmth), color.warmth ?: 0.0, ColorControl.Warmth) { change(work.color.copy(warmth = it.takeIf { it != 0.0 })) }
        Level(stringResource(R.string.color_tint), color.tint ?: 0.0, ColorControl.Tint) { change(work.color.copy(tint = it.takeIf { it != 0.0 })) }
        TextButton(onClick = { change(work.color.copy(exposure = null, warmth = null, tint = null)) }, modifier = Modifier.heightIn(min = 44.dp)) {
            Text(stringResource(R.string.color_reset_balance))
        }
    }
    val lut = color.imported
    if (lut == null) {
        Choices(stringResource(R.string.color_look), ColorLook.entries.map { stringResource(it.label) to it }, color.look) { change(work.color.copy(look = it)) }
    } else {
        Text(stringResource(R.string.color_lut_name, lut.name), color = Color.White, modifier = Modifier.padding(top = 12.dp))
    }
    Row {
        TextButton(onClick = { pickLut.launch("*/*") }) { Text(stringResource(if (lut == null) R.string.color_import_lut else R.string.color_choose_another_lut)) }
        if (lut != null) TextButton(onClick = { change(work.color.copy(imported = null)) }) { Text(stringResource(R.string.color_use_look)) }
    }
}

/**
 * A slider of the Color tool with its name and value, on the original's range and steps ([ColorControl]); with
 * [reset], the value is a button that puts it back to zero, as Highlights' (ColorGradeControls.swift:53-62).
 */
@Composable
private fun Level(label: String, value: Double, control: ColorControl, enabled: Boolean = true, reset: Pair<String, () -> Unit>? = null, change: (Double) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Text(label, color = if (enabled) Color.White else Soft, fontSize = 14.sp)
        if (reset == null) Text(control.label(value), color = Color.White, fontSize = 13.sp)
        else {
            TextButton(onClick = reset.second, modifier = Modifier.semantics { contentDescription = reset.first; stateDescription = control.label(value) }) {
                Text(control.label(value), color = Color.White, fontSize = 13.sp)
            }
        }
    }
    Slider(
        value = value.toFloat(), onValueChange = { change(control.stepped(it.toDouble())) }, enabled = enabled,
        valueRange = control.low.toFloat()..control.high.toFloat(), modifier = Modifier.semantics { contentDescription = label },
    )
}

/** The look names of the core module, which only has them in English. */
internal val ColorLook.label: Int
    get() = when (this) {
        ColorLook.Natural -> R.string.color_look_natural
        ColorLook.Warm -> R.string.color_look_warm
        ColorLook.Cool -> R.string.color_look_cool
        ColorLook.Cinema -> R.string.color_look_cinema
        ColorLook.Mono -> R.string.color_look_mono
        ColorLook.Portrait -> R.string.color_look_portrait
        ColorLook.Daylight -> R.string.color_look_daylight
        ColorLook.GoldenHour -> R.string.color_look_golden_hour
        ColorLook.MutedFilm -> R.string.color_look_muted_film
        ColorLook.RichContrast -> R.string.color_look_rich_contrast
        ColorLook.Pastel -> R.string.color_look_pastel
        ColorLook.Night -> R.string.color_look_night
        ColorLook.Silver -> R.string.color_look_silver
        ColorLook.Ink -> R.string.color_look_ink
    }
