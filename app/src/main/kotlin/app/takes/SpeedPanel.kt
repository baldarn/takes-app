package app.takes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takes.core.EditState
import app.takes.core.RangeEditing
import app.takes.core.SpeedSettings
import app.takes.core.ToolCompare
import app.takes.core.newID
import kotlin.math.abs
import kotlin.math.roundToInt

private val Muted = Color(0xFFA0A4AB)
private val RemoveRed = Color(0xFFFF6961)

/**
 * The Speed tool at work, as the original's tool workspace keeps it
 * (YouRec/EditorTools.swift:170-190, 700-704): the rate chosen for the clips
 * [ids] of [base], the rate the preview plays (the slider's once it rests: a
 * new rate moves every later clip, and the players load the edit again), and
 * whether the preview shows Before. [base] is the edit the tool opened on, cut
 * at the ends of a range ([scoped]); [from] is the saved edit then.
 */
internal class Pacing(
    val from: EditState, val base: EditState, val ids: List<String>, val scoped: RangeEditing.Scoped?, val label: String?, initial: Double,
    /** The id the speed is saved under on every clip: the one opened from its chip, else a new one (EditorTools.swift:428, 703). */
    val id: String = newID(),
) {
    var rate by mutableDoubleStateOf(SpeedSettings.stepped(initial))
    var settled by mutableDoubleStateOf(rate)
    /** Before: the clips as recorded (EditorTools.swift:369, 704). */
    var original by mutableStateOf(false)
    /** Whether the clips had a speed when the tool opened: it can then be removed (EditorTools.swift:1096-1100). */
    val existing = base.clips.any { it.id in ids && it.speed != null }

    private var made: Pair<Double, EditState>? = null

    /** What the editor shows: the clips at the rate, or Before as recorded. */
    val shown: EditState
        get() {
            val playing = if (original) 1.0 else settled
            made?.let { if (it.first == playing) return it.second }
            return ToolCompare.paced(base, ids, playing).also { made = playing to it }
        }

    /** The edit with the rate applied, or with the speed removed. */
    fun applied(removing: Boolean = false): EditState = ToolCompare.paced(base, ids, if (removing) 1.0 else rate, id)
}

/**
 * The Speed tool, as the original's (EditorTools.swift:1311-1315), in place of
 * the timeline with the editor's preview playing the clips at the rate: Cancel
 * and Apply, Before and After, the rate in large type, a slider from 0.25× to
 * 4× in steps of 0.05, and four quick choices. Nothing is saved until [apply];
 * [remove] plays the clips as recorded again.
 */
@Composable
internal fun SpeedPanel(work: Pacing, apply: () -> Unit, remove: () -> Unit, cancel: () -> Unit, modifier: Modifier = Modifier) {
    val name = stringResource(R.string.speed_slider)
    Column(modifier.fillMaxWidth().background(Color(0xFF1B1D21))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = cancel) { Text(stringResource(R.string.common_cancel)) }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.editor_speed), color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                work.label?.let { Text(it, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            TextButton(onClick = apply) { Text(stringResource(R.string.speed_apply), fontWeight = FontWeight.Bold) }
        }
        BeforeAfter(work.original, { work.original = it })
        // Scrolls where the screen is short, as in landscape.
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(speedLabel(work.rate), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
            val steps = ((SpeedSettings.MAXIMUM_RATE - SpeedSettings.MINIMUM_RATE) / SpeedSettings.STEP).roundToInt() - 1
            Slider(
                value = work.rate.toFloat(), onValueChange = { work.rate = SpeedSettings.stepped(it.toDouble()) },
                onValueChangeFinished = { work.settled = work.rate },
                valueRange = SpeedSettings.MINIMUM_RATE.toFloat()..SpeedSettings.MAXIMUM_RATE.toFloat(), steps = steps,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = name; stateDescription = speedLabel(work.rate) },
            )
            FlowRow(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                for (quick in listOf(0.5, 1.0, 1.5, 2.0)) FilterChip(abs(work.rate - quick) < 0.001, onClick = { work.rate = quick; work.settled = quick }, label = { Text(speedLabel(quick)) })
            }
            Text(stringResource(R.string.speed_note), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            if (work.existing) {
                TextButton(onClick = remove, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                    Icon(Icons.Outlined.Delete, null, tint = RemoveRed)
                    Text(stringResource(R.string.speed_remove), color = RemoveRed, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}
