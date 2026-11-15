package app.takes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takes.core.AudioEdgeFade
import app.takes.core.AudioFadeEdge
import app.takes.core.AudioGainGrid
import app.takes.core.AudioHeadroom
import app.takes.core.AudioItem
import app.takes.core.AudioLevels
import app.takes.core.ClipAudio
import app.takes.core.EditInterval
import app.takes.core.EditState
import app.takes.core.EditTime
import app.takes.core.TimelineEditing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import kotlin.math.expm1
import kotlin.math.ln1p
import kotlin.math.roundToLong

private val Quiet = Color(0xFFA0A4AB)
private val Clipping = Color(0xFFFF453A)
private val Level = Color(0xFF64D2FF)

/**
 * One clip or sound the Sound tool works on: how it sounds now, where it
 * plays in the movie, and the name it goes by beside a fade.
 */
internal class SoundTarget(val id: String, val audio: ClipAudio, val start: Double, val end: Double, val name: String, val clip: Boolean) {
    val length: Double get() = end - start

    /** Seconds of the fade at [edge] that are heard (TrackTimeline.swift:188-190). */
    fun fadeSeconds(edge: AudioFadeEdge, audio: ClipAudio = this.audio): Double =
        (if (edge == AudioFadeEdge.FadeIn) audio.fades.fadeIn else audio.fades.fadeOut)?.remaining(length) ?: 0.0
}

/** The clips [clips] and the sounds [sounds] of [state], by id, as the Sound tool takes them: clips first, in order. */
internal fun soundTargets(state: EditState, clips: Collection<String>, sounds: Collection<String>, clipName: (Int) -> String, soundName: (AudioItem) -> String): List<SoundTarget> =
    state.clips.mapIndexedNotNull { index, clip ->
        if (clip.id !in clips) return@mapIndexedNotNull null
        TimelineEditing.clipRange(state.clips, index)?.let { SoundTarget(clip.id, clip.audio ?: ClipAudio(), it.start, it.endInclusive, clipName(index), clip = true) }
    } + state.audio.orEmpty().filter { it.id in sounds }.map { SoundTarget(it.id, it.audio ?: ClipAudio(), it.start, it.end, soundName(it), clip = false) }

/**
 * [state] with the sound [change] gives each of [targets], as one edit. Null
 * when it changes none of them. Sounds on the audio tracks have no noise reduction.
 */
internal fun withSound(state: EditState, targets: List<SoundTarget>, change: (SoundTarget) -> ClipAudio): EditState? {
    var next = state
    var changed = false
    for (target in targets) {
        val audio = change(target)
        val result = if (target.clip) TimelineEditing.setAudio(next, next.clips.indexOfFirst { it.id == target.id }, audio)
        else TimelineEditing.setAudioItemSound(next, target.id, audio.copy(denoise = 0.0))
        if (result != null) { next = result; changed = true }
    }
    return next.takeIf { changed }
}

/** Seconds as they are shown and typed: up to three decimals, in the person's own notation. */
private fun seconds(value: Double): String = DecimalFormat("0.###", DecimalFormatSymbols.getInstance()).format(value)

/**
 * Volume, mute and fades of [targets], with the original's controls
 * (TrackEditors.swift:272-309): the level on its grid of half decibels from
 * −40 to +12 with a stepper, shown with a sign and one decimal; Mute and
 * Reset to 0 dB; a fade at each end of any length up to its item's, typed or
 * dragged on a scale that gives short fades room; and the warning when the
 * movie's mix would clip at the level shown, from [measure].
 *
 * With several targets of different levels nothing is changed until a level
 * is chosen, and Mute is left as each has it until the switch is touched.
 * The fade in belongs to the target that begins first and the fade out to
 * the one that ends last, each named beside its control.
 *
 * [measure] is given the sound the dialog would save, without the level, and
 * returns how far the level can go; null when it cannot be measured.
 * Noise reduction, which the original keeps in another tool, stays here for clips.
 */
@Composable
internal fun SoundDialog(
    targets: List<SoundTarget>, canReduceNoise: Boolean,
    measure: suspend (change: (SoundTarget) -> ClipAudio) -> AudioHeadroom?,
    save: (change: (SoundTarget) -> ClipAudio) -> Unit, dismiss: () -> Unit, appliesTo: String? = null,
    title: String = stringResource(R.string.sound_dialog_title),
) {
    val levels = remember(targets) { targets.map { it.audio.volumeDB } }
    val common = remember(levels) { AudioGainGrid.common(levels) }
    val bounds = remember(levels) { (levels.minOrNull() ?: 0.0)..(levels.maxOrNull() ?: 0.0) }
    val mixed = common == null
    val mixedMute = remember(targets) { targets.any { it.audio.muted } && targets.any { !it.audio.muted } }
    var volume by remember { mutableDoubleStateOf(common ?: bounds.endInclusive) }
    var volumeChosen by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(targets.isNotEmpty() && targets.all { it.audio.muted }) }
    var muteChosen by remember { mutableStateOf(false) }
    var fadeEdits by remember { mutableStateOf(mapOf<AudioFadeEdge, Double>()) }
    // One target for each fade: the first to begin and the last to end, the earliest in the list on a tie.
    val fadeTargets = remember(targets) {
        val places = targets.map { EditInterval.of(it.start, it.end) }
        AudioFadeEdge.entries.associateWith { edge -> edge.index(places) { true }?.let { targets[it] } }
    }
    val denoised = remember(targets) { targets.filter { it.clip } }
    var denoise by remember { mutableDoubleStateOf(denoised.firstOrNull()?.audio?.denoise ?: 0.0) }
    var denoiseChosen by remember { mutableStateOf(false) }

    // What the dialog would save for a target, as things stand when it is asked.
    fun changed(target: SoundTarget, level: Boolean, fades: Map<AudioFadeEdge, Double>, silent: Boolean?, clean: Double?): ClipAudio {
        var audio = target.audio
        if (level) audio = audio.copy(volumeDB = volume)
        if (silent != null) audio = audio.copy(muted = silent)
        for ((edge, length) in fades) if (fadeTargets[edge]?.id == target.id) {
            // A fade that is set starts whole again, no longer than its item (TrackTimeline.swift:191-195).
            val fade = AudioEdgeFade.of(length, EditTime.of(target.length))
            audio = audio.withFades(if (edge == AudioFadeEdge.FadeIn) audio.fades.copy(fadeIn = fade) else audio.fades.copy(fadeOut = fade))
        }
        if (clean != null && target.clip) audio = audio.copy(denoise = clean)
        return audio
    }

    // How far the level can go, measured again when what shapes the sound besides its level changes.
    var headroom by remember { mutableStateOf<AudioHeadroom?>(null) }
    var headroomNote by remember { mutableStateOf<Int?>(R.string.sound_headroom_checking) }
    LaunchedEffect(muted, muteChosen, fadeEdits) {
        headroom = null
        headroomNote = R.string.sound_headroom_checking
        delay(250) // a fade being dragged is measured once it rests
        val fades = fadeEdits
        val silent = muted.takeIf { muteChosen }
        val result = try { measure { changed(it, level = false, fades, silent, null) } } catch (stopped: CancellationException) { throw stopped } catch (problem: Exception) { null }
        headroom = result
        headroomNote = if (result == null) R.string.sound_headroom_unavailable else if (result.silent) R.string.sound_headroom_silent else null
    }
    val unset = mixed && !volumeChosen
    val clipping = AudioLevels.warnsClipping(volume, headroom, muted, mixed, volumeChosen)

    fun step(delta: Double) {
        volume = AudioGainGrid.stepped(volume.takeIf { !unset }, delta, bounds)
        volumeChosen = true
    }

    val shown = if (unset) stringResource(R.string.sound_mixed_levels) else if (muted) stringResource(R.string.sound_muted) else AudioLevels.format(volume)
    val sliderLabel = stringResource(R.string.sound_volume_decibels)
    val sliderValue = (if (unset) stringResource(R.string.sound_mixed_choose) else AudioLevels.format(volume)) + if (clipping) ", " + stringResource(R.string.sound_clipping_short) else ""
    val lowerLabel = stringResource(R.string.sound_step_down)
    val raiseLabel = stringResource(R.string.sound_step_up)
    val muteLabel = stringResource(R.string.sound_mute)
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                appliesTo?.let { Text(it, color = Quiet, fontSize = 13.sp) }
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text(shown, color = Color.White, fontSize = 22.sp, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { step(-AudioGainGrid.STEP_DB) }, modifier = Modifier.semantics { contentDescription = lowerLabel }) { Text("−") }
                    Spacer(Modifier.padding(start = 8.dp))
                    OutlinedButton(onClick = { step(AudioGainGrid.STEP_DB) }, modifier = Modifier.semantics { contentDescription = raiseLabel }) { Text("+") }
                }
                val tint = if (unset) Quiet else if (clipping) Clipping else Level
                Slider(
                    value = volume.coerceIn(AudioGainGrid.MINIMUM_DB, AudioGainGrid.MAXIMUM_DB).toFloat(),
                    onValueChange = { volume = AudioGainGrid.snap(it.toDouble()); volumeChosen = true },
                    valueRange = AudioGainGrid.MINIMUM_DB.toFloat()..AudioGainGrid.MAXIMUM_DB.toFloat(),
                    colors = SliderDefaults.colors(thumbColor = if (unset) Quiet else if (clipping) Clipping else Color.White, activeTrackColor = tint),
                    modifier = Modifier.semantics { contentDescription = sliderLabel; stateDescription = sliderValue },
                )
                if (clipping) Text("⚠ " + stringResource(R.string.sound_clipping), color = Clipping, fontSize = 13.sp)
                else headroomNote?.let { Text(stringResource(it), color = Quiet, fontSize = 13.sp) }
                if (unset) Text(stringResource(R.string.sound_choose_level), color = Quiet, fontSize = 13.sp)
                if (mixedMute && !muteChosen) Text(stringResource(R.string.sound_mixed_mute), color = Quiet, fontSize = 13.sp)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = muted, onCheckedChange = { muted = it; muteChosen = true }, modifier = Modifier.semantics { contentDescription = muteLabel })
                    Text(stringResource(R.string.sound_mute_switch), color = Color.White, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 10.dp))
                    TextButton(onClick = { volume = 0.0; muted = false; volumeChosen = true; muteChosen = true }) { Text(stringResource(R.string.sound_reset_zero)) }
                }
                for (edge in AudioFadeEdge.entries) {
                    val target = fadeTargets[edge] ?: continue
                    HorizontalDivider(Modifier.padding(top = 4.dp))
                    FadeRow(
                        title = stringResource(if (edge == AudioFadeEdge.FadeIn) R.string.sound_fade_in else R.string.sound_fade_out),
                        target = stringResource(R.string.sound_fade_target, target.name, preciseLabel(if (edge == AudioFadeEdge.FadeIn) target.start else target.end)),
                        seconds = fadeEdits[edge] ?: target.fadeSeconds(edge), limit = target.length,
                        change = { length ->
                            val value = EditTime.of(length).seconds
                            fadeEdits = if (value == target.fadeSeconds(edge)) fadeEdits - edge else fadeEdits + (edge to value)
                        },
                    )
                }
                if (canReduceNoise && denoised.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(top = 4.dp))
                    Choices(stringResource(R.string.sound_noise_reduction), listOf(stringResource(R.string.sound_noise_off) to 0.0, stringResource(R.string.sound_noise_light) to 0.5, stringResource(R.string.sound_noise_full) to 1.0), denoise) { denoise = it; denoiseChosen = true }
                    Text(stringResource(R.string.sound_noise_note), color = Quiet, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val (level, fades, silent, clean) = Chosen(volumeChosen, fadeEdits, muted.takeIf { muteChosen }, denoise.takeIf { denoiseChosen })
                save { changed(it, level, fades, silent, clean) }
            }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

private data class Chosen(val level: Boolean, val fades: Map<AudioFadeEdge, Double>, val silent: Boolean?, val clean: Double?)

/**
 * One fade (TrackEditors.swift:88-150): whose it is, its length as a button
 * that opens a field for an exact number of seconds, and a slider whose
 * travel is logarithmic, so short fades are easy to set on a long item. The
 * slider moves in tenths of a second and reaches the item's whole length.
 */
@Composable
private fun FadeRow(title: String, target: String, seconds: Double, limit: Double, change: (Double) -> Unit) {
    var entering by remember { mutableStateOf(false) }
    var entry by remember { mutableStateOf("") }
    val value = if (seconds == 0.0) stringResource(R.string.sound_fade_off) else stringResource(R.string.sound_fade_seconds, seconds(seconds))
    val durationLabel = stringResource(R.string.sound_fade_duration, title)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White)
            Text(target, color = Quiet, fontSize = 12.sp)
        }
        TextButton(onClick = { entry = seconds(seconds); entering = true }, modifier = Modifier.semantics { contentDescription = durationLabel; stateDescription = value }) {
            Text(value, fontFamily = FontFamily.Monospace)
        }
    }
    val scale = ln1p(maxOf(0.001, limit))
    Slider(
        value = (ln1p(seconds) / scale).toFloat().coerceIn(0f, 1f),
        onValueChange = { position ->
            val raw = expm1(position * scale)
            change(if (position >= 1f) limit else minOf(limit, (raw * 10).roundToLong() / 10.0))
        },
        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFF66D4CF)),
        modifier = Modifier.heightIn(min = 40.dp).semantics { contentDescription = title; stateDescription = value },
    )
    if (entering) {
        // A typed number may use a comma; the item's whole length may be typed as it is shown, rounded.
        val typed = entry.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
        val entered = typed?.let { if (it > 0 && it == Math.round(limit * 1000) / 1000.0) limit else it.takeIf { it <= limit } }
        AlertDialog(
            onDismissRequest = { entering = false },
            title = { Text(durationLabel) },
            text = {
                Column {
                    Text(stringResource(R.string.sound_fade_enter, seconds(limit)), color = Quiet, fontSize = 13.sp)
                    OutlinedTextField(
                        value = entry, onValueChange = { entry = it }, singleLine = true,
                        label = { Text(stringResource(R.string.sound_fade_field)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { entered?.let(change); entering = false }, enabled = entered != null) { Text(stringResource(R.string.sound_fade_set)) } },
            dismissButton = { TextButton(onClick = { entering = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
