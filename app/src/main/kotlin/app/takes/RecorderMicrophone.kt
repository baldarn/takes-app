package app.takes

import app.takes.core.RecordingGain
import androidx.compose.ui.platform.LocalView
import androidx.compose.material3.TextButton
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Slider
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takes.CaptureController.Phase
import app.takes.core.LevelMeter

private val NoMicrophoneOrange = Color(0xFFFF9F0A)
private val NoSignalYellow = Color(0xFFFFD60A)
private val LevelGreen = Color(0xFF30D158)

/** What the meter shows, as far as the screen draws it: it changes less often than the level itself. */
private data class Shown(val bars: Int, val clipping: Boolean, val arriving: Boolean, val decibels: Int, val fraction: Float)

/** Reads the meter once per frame, as the original's small views take their readings. */
@Composable
private fun meterReading(capture: CaptureController): Shown {
    fun read() = capture.meter.reading().let { Shown(it.bars, it.clipping, it.arriving, Math.round(it.level).toInt(), (Math.round(it.fraction * 100) / 100.0).toFloat()) }
    var shown by remember { mutableStateOf(read()) }
    LaunchedEffect(capture) {
        while (true) {
            withFrameNanos { }
            val next = read()
            if (next != shown) shown = next
        }
    }
    return shown
}

/**
 * The microphone control at the start of the top bar, as in the original's compact form: the icon, the
 * seven-bar meter and a mark for clipping or no signal, with the name of the microphone under them. It is
 * always there, orange when there is no microphone to record from, and opens the level and the microphones.
 */
@Composable
internal fun MicrophonePill(capture: CaptureController, modifier: Modifier = Modifier) {
    val available = capture.meterAvailable
    var open by remember { mutableStateOf(false) }
    val name = if (available) capture.microphone ?: stringResource(R.string.recorder_mic_phone) else stringResource(R.string.recorder_mic_none)
    val spoken = stringResource(R.string.recorder_mic_label, name)
    // Under the meter there is room for a dozen letters: the phone's own microphone has a short name for it,
    // and another name loses its middle, so the end that tells two devices apart stays.
    val short = if (name == stringResource(R.string.recorder_mic_phone)) stringResource(R.string.recorder_mic_phone_short)
        else name.replace("Microphone", "Mic", ignoreCase = true).replace("Microfono", "Mic", ignoreCase = true)
    val tint = if (available) Color.White else NoMicrophoneOrange
    val lit = LocalControlPlate.current != ControlPlate
    Box(modifier) {
        Column(
            Modifier.height(44.dp).alpha(if (capture.phase == Phase.Saving) 0.4f else 1f).clip(RoundedCornerShape(50))
                .background(if (available || lit) LocalControlPlate.current else NoMicrophoneOrange.copy(alpha = 0.22f))
                .clickable(enabled = capture.phase != Phase.Saving, onClickLabel = stringResource(R.string.recorder_mic_change), role = Role.Button) { open = true }
                .padding(horizontal = 8.dp)
                .semantics(mergeDescendants = true) { contentDescription = spoken; stateDescription = name },
            Arrangement.spacedBy(2.dp, Alignment.CenterVertically), Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (available) Icons.Filled.Mic else Icons.Filled.MicOff, null, Modifier.size(14.dp), tint = tint)
                if (available) MeterBars(capture)
            }
            Text(
                short, color = tint, fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.MiddleEllipsis, textAlign = TextAlign.Center, modifier = Modifier.widthIn(64.dp, 88.dp).clearAndSetSemantics {},
            )
        }
        DropdownMenu(open, { open = false }) { MicrophonePanel(capture) { open = false } }
    }
}

/** Seven bars from −54 dBFS in steps of 8, the last one red, and the mark beside them. */
@Composable
private fun MeterBars(capture: CaptureController) {
    val shown = meterReading(capture)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clearAndSetSemantics {}, Arrangement.spacedBy(2.dp), Alignment.CenterVertically) {
            repeat(LevelMeter.BARS) { index ->
                val color = if (index >= shown.bars) Color.White.copy(alpha = 0.22f) else if (LevelMeter.isRed(index)) Color.Red else Color.White
                Box(Modifier.size(3.dp, (5 + index * 2).dp).background(color, RoundedCornerShape(50)))
            }
        }
        if (shown.clipping) {
            Icon(Icons.Filled.Error, stringResource(R.string.recorder_mic_clipping), Modifier.size(14.dp), tint = Color.Red)
        } else if (!shown.arriving && capture.ready && capture.phase != Phase.Saving) {
            Icon(Icons.Filled.MicOff, stringResource(R.string.recorder_mic_no_signal), Modifier.size(14.dp), tint = NoSignalYellow)
        }
    }
}

/**
 * What the control opens: the level in dBFS with "Clipping" and "No signal", and the microphones.
 * and the recording gain of the microphone in use, as the original's panel.
 */
@Composable
private fun MicrophonePanel(capture: CaptureController, close: () -> Unit) {
    if (capture.meterAvailable) {
        val shown = meterReading(capture)
        val level = stringResource(R.string.recorder_mic_level)
        val label = when {
            shown.clipping -> stringResource(R.string.recorder_mic_clipping)
            shown.arriving -> LevelMeter.format(shown.decibels.toDouble())
            else -> stringResource(R.string.recorder_mic_no_signal)
        }
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp).width(250.dp).semantics(mergeDescendants = true) { contentDescription = level; stateDescription = label },
            Arrangement.spacedBy(10.dp), Alignment.CenterVertically,
        ) {
            LinearProgressIndicator(
                progress = { shown.fraction }, modifier = Modifier.weight(1f), color = if (shown.clipping) Color.Red else LevelGreen,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f), drawStopIndicator = {},
            )
            Text(
                label, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.width(92.dp).clearAndSetSemantics {},
                color = if (shown.clipping) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RecordingGainControls(capture, shown.clipping)
    }
    // A running segment keeps its microphone: the choice is for the next one.
    val free = capture.phase == Phase.Idle || capture.phase == Phase.Paused
    capture.microphones.forEach { choice ->
        DropdownMenuItem(
            { Text(choice.label) }, { capture.chooseMicrophone(choice.key); close() }, enabled = free,
            leadingIcon = { Icon(if (choice.key == capture.microphoneKey) Icons.Filled.Check else Icons.Filled.Mic, null) },
        )
    }
    if (!free && capture.microphones.isNotEmpty()) {
        Text(
            stringResource(R.string.recorder_settings_microphone_busy), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(250.dp),
        )
    }
}

/**
 * The recording gain, −40 to +12 dB in half decibels, remembered for each microphone (the original's
 * `RecordingGainControls`). The camera library's recorder takes no gain, so the file is recorded as it is and
 * the gain becomes the level of the take's clip; the meter shows the sound with the gain, as the take will be.
 */
@Composable
private fun RecordingGainControls(capture: CaptureController, clipping: Boolean) {
    val view = LocalView.current
    val gain = capture.recordingGain
    val label = stringResource(R.string.recorder_gain)
    val value = stringResource(R.string.recorder_gain_value, String.format(java.util.Locale.getDefault(), "%+.1f", gain))
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).width(250.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f),
                color = if (clipping) Color.Red else MaterialTheme.colorScheme.onSurface,
            )
            TextButton(onClick = { capture.chooseRecordingGain(0.0) }, enabled = gain != 0.0) { Text(stringResource(R.string.recorder_gain_reset)) }
        }
        Slider(
            gain.toFloat(),
            { next -> val before = capture.recordingGain; capture.chooseRecordingGain(next.toDouble()); if (capture.recordingGain != before) view.tick() },
            Modifier.semantics { contentDescription = label; stateDescription = value },
            valueRange = RecordingGain.MINIMUM_DB.toFloat()..RecordingGain.MAXIMUM_DB.toFloat(),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = if (clipping) Color.Red else LevelGreen),
        )
        Text(stringResource(R.string.recorder_gain_note), fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
