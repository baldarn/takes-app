package app.takes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takes.core.SpeedSettings
import kotlin.math.abs
import kotlin.math.roundToInt

private val Muted = Color(0xFFA0A4AB)

/**
 * The Speed tool, as the original's (EditorTools.swift:1311-1315): the rate in
 * large type, a slider from 0.25× to 4× in steps of 0.05, and four quick
 * choices. Nothing changes until [apply] is given the rate.
 *
 * @param rate the rate the clip has now.
 * @param scope what the tool applies to, when it is a range or a selection.
 */
@Composable
internal fun SpeedDialog(rate: Double, scope: String?, apply: (Double) -> Unit, dismiss: () -> Unit) {
    var chosen by remember { mutableDoubleStateOf(SpeedSettings.stepped(rate)) }
    val name = stringResource(R.string.speed_slider)
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.speed_title)) },
        text = {
            Column {
                scope?.let { Text(it, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)) }
                Text(speedLabel(chosen), fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
                val steps = ((SpeedSettings.MAXIMUM_RATE - SpeedSettings.MINIMUM_RATE) / SpeedSettings.STEP).roundToInt() - 1
                Slider(
                    value = chosen.toFloat(), onValueChange = { chosen = SpeedSettings.stepped(it.toDouble()) },
                    valueRange = SpeedSettings.MINIMUM_RATE.toFloat()..SpeedSettings.MAXIMUM_RATE.toFloat(), steps = steps,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = name; stateDescription = speedLabel(chosen) },
                )
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                    for (quick in listOf(0.5, 1.0, 1.5, 2.0)) FilterChip(abs(chosen - quick) < 0.001, onClick = { chosen = quick }, label = { Text(speedLabel(quick)) })
                }
                Text(stringResource(R.string.speed_note), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = { TextButton(onClick = { apply(chosen) }) { Text(stringResource(R.string.speed_apply)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
