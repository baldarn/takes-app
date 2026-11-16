package app.takes

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import app.takes.core.AudioChainComparison
import app.takes.core.AudioChainEditing
import app.takes.core.AudioEffect
import app.takes.core.AudioEffectKind
import app.takes.core.AudioFader
import app.takes.core.AudioListeningLevels
import app.takes.core.AudioModel
import app.takes.core.EditInterval
import app.takes.core.EditTime
import app.takes.core.TrackAudioRegions
import app.takes.core.TrackMix
import app.takes.core.TrackMixPiece
import app.takes.core.TrackMixPlan
import app.takes.core.TrackMixer
import app.takes.core.toTimeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val Background = Color(0xFF0F0F0F)
private val Secondary = Color(0xFFA0A4AB)
private val Warning = Color(0xFFFF9F0A)

/** The stages Enhance audio offers in its Add filter menu: those this app plays. LavaSR and VoiceFixer are left out (docs/PARITY.md). */
private val Addable = listOf(AudioEffectKind.VoiceIsolation, AudioEffectKind.HighPass, AudioEffectKind.Equalizer, AudioEffectKind.Gain)

private fun stripColor(kind: AudioEffectKind) = Color(0xFF000000 or AudioFader.stripColor(kind).toLong())

/**
 * Enhance audio, as the original's inspector (YouRec/TrackEditors.swift:152-436 with
 * YouRec/AudioChainControls.swift): the chain in order as columns, each with its fader, its
 * on/off switch and a grip to drag it to another place; the selected stage's settings below,
 * with moving it earlier or later, resetting and removing it; Add filter. Play prepares up to
 * five seconds either side of the playhead within the selection ([AudioListeningLevels.listeningRange])
 * through the chain and plays it; Bypass plays the same stretch without the chain; with
 * Match listening levels the two are played equally loud ([AudioChainComparison]). Nothing
 * of the comparison is saved or exported. Apply puts the chain on the whole selection.
 *
 * Unlike the original it plays the sound only, without the picture.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EnhanceInspector(
    work: AudioToolWork,
    files: Map<String, File>,
    voiced: Map<String, Boolean>?,
    position: Double,
    apply: (List<AudioEffect>) -> Unit,
    remove: () -> Unit,
    dismiss: () -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("enhance", Context.MODE_PRIVATE) }
    val scope0 = remember(work) { work.chain }
    var comparison by remember {
        mutableStateOf(AudioChainComparison(effects = scope0.effects, matchListeningLevels = preferences.getBoolean("matchListeningLevels", true)))
    }
    val effects = comparison.effects
    var selectedID by remember { mutableStateOf(effects.firstOrNull()?.id) }
    val range = remember(work, position) {
        val timeline = work.state.toTimeline()
        val selection = work.targets.range ?: timeline.audio.filter { it.id in work.targets.audio }.let { items ->
            if (items.isEmpty()) EditInterval.of(0.0, 0.0) else EditInterval(items.minOf { it.start }, items.maxOf { it.end })
        }
        AudioListeningLevels.listeningRange(position, selection.start.seconds..selection.end.seconds)
    }
    val playback = remember { MixPlayback() }
    DisposableEffect(Unit) { onDispose { playback.release() } }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var loaded by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(false) }
    var heard by remember { mutableDoubleStateOf(range.start) }
    var originalEnergies by remember { mutableStateOf<FloatArray?>(null) }
    var failed by remember { mutableStateOf(false) }
    var matchingHelp by remember { mutableStateOf(false) }

    fun updateVolume() = playback.volume(comparison.listeningVolume)

    fun invalidate() {
        playback.pause(); playing = false
        job?.cancel(); job = null
        preparing = false; dirty = true
    }

    fun prepare(play: Boolean) {
        job?.cancel()
        preparing = true; progress = 0f; failed = false
        val wanted = comparison
        job = scope.launch {
            try {
                val plan = withContext(Dispatchers.Default) {
                    val state = work.enhanced(wanted.playing) ?: work.state
                    val timeline = state.toTimeline()
                    val listen = EditInterval.of(range.start, range.endInclusive)
                    if (!wanted.bypass) {
                        val requests = TrackAudioRegions.windowRequests(timeline, listen).values.filter { Denoiser.playable(it.effects) }
                        val total = requests.sumOf { it.spans.size }.coerceAtLeast(1)
                        var done = 0
                        for (request in requests) {
                            val file = files[request.sourceID] ?: continue
                            if (Denoiser.ready(file, request.effects) != null) { done += request.spans.size; continue }
                            for (span in request.spans) {
                                if (Denoiser.windows(file, request.effects).none { it.covers(span.start, span.end) }) {
                                    Denoiser.processWindow(file, request.effects, span) { progress = (done + it) / total }
                                }
                                done++
                                progress = done.toFloat() / total
                            }
                        }
                    }
                    val plan = TrackMix.plan(timeline, voiced = { voiced?.get(it) != false })
                    if (wanted.needsMeasuring) {
                        val original = originalEnergies ?: run {
                            val bare = (work.enhanced(emptyList()) ?: work.state).toTimeline()
                            energies(TrackMix.plan(bare, voiced = { voiced?.get(it) != false }), files, Denoiser.previewInput(files), range)
                        }
                        originalEnergies = original
                        val gains = AudioListeningLevels.gains(original, energies(plan, files, Denoiser.previewInput(files), range))
                        withContext(Dispatchers.Main) { comparison = comparison.copy(gains = gains) }
                    }
                    plan
                }
                playback.load(plan, files, Denoiser.previewInput(files))
                updateVolume()
                loaded = true; dirty = false; preparing = false
                if (play) { heard = range.start; playback.play(range.start); playing = true }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                preparing = false; failed = true
            }
        }
    }

    fun playPreview() {
        when {
            preparing -> invalidate()
            !loaded || dirty || failed -> prepare(play = true)
            playing -> { playback.pause(); playing = false }
            else -> {
                val from = if (heard >= range.endInclusive - 0.05) range.start else heard
                playback.play(from); playing = true
            }
        }
    }

    fun change(next: List<AudioEffect>) {
        val wasPlaying = playing
        invalidate()
        selectedID = AudioChainEditing.selection(effects, next, selectedID)
        comparison = comparison.changing(next)
        if (wasPlaying) prepare(play = true)
    }

    // Stops at the end of the stretch prepared, as the original's player stops at its preview range.
    LaunchedEffect(playing) {
        while (playing) {
            delay(50)
            heard = playback.position().coerceIn(range.start, range.endInclusive)
            if (playback.position() >= range.endInclusive) { playback.pause(); playing = false; heard = range.endInclusive }
        }
    }

    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) }
                    Text(stringResource(R.string.enhance_title), color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    TextButton(onClick = { apply(effects) }, enabled = work.targets.audio.isNotEmpty()) { Text(stringResource(R.string.enhance_apply)) }
                }
                // The listening row: where the preview is, Bypass, and Play.
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        clockLabel(maxOf(0.0, heard - range.start)) + " / " + clockLabel(range.endInclusive - range.start),
                        color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f),
                    )
                    val bypassLabel = stringResource(if (comparison.bypass) R.string.enhance_bypass_on else R.string.enhance_bypass)
                    IconButton(onClick = {
                        val wasPlaying = playing
                        invalidate()
                        comparison = comparison.togglingBypass()
                        if (wasPlaying) prepare(play = true)
                    }, modifier = Modifier.semantics { contentDescription = bypassLabel }) {
                        Icon(if (comparison.bypass) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = if (comparison.bypass) Warning else Color.White)
                    }
                    val playLabel = stringResource(if (preparing) R.string.enhance_cancel_preview else if (playing) R.string.enhance_pause else R.string.enhance_play_preview)
                    IconButton(onClick = ::playPreview, modifier = Modifier.semantics { contentDescription = playLabel }) {
                        Icon(if (preparing) Icons.Filled.Close else if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White)
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (scope0.mixed) Text(stringResource(R.string.enhance_mixed), color = Secondary, fontSize = 12.sp)
                    AddFilterButton { kind -> change(AudioChainEditing.add(effects, kind)) }
                    ChainControls(effects, selectedID, select = { selectedID = it }, change = ::change)
                    Text(stringResource(R.string.enhance_preview_note), color = Secondary, fontSize = 12.sp)
                    Row(
                        Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { matchingHelp = true }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.enhance_match), color = if (comparison.allowsMatching) Color.White else Secondary, modifier = Modifier.weight(1f))
                        Switch(checked = comparison.matching, enabled = comparison.allowsMatching, onCheckedChange = { on ->
                            runCatching { preferences.edit().putBoolean("matchListeningLevels", on).apply() }
                            val wasPlaying = playing
                            invalidate()
                            comparison = comparison.matching(on)
                            if (wasPlaying) prepare(play = true) else updateVolume()
                        })
                    }
                    if (!comparison.allowsMatching) Text(stringResource(R.string.enhance_match_suspended), color = Secondary, fontSize = 12.sp)
                    else if (dirty && loaded) Text(stringResource(R.string.enhance_play_prepares), color = Secondary, fontSize = 12.sp)
                    if (preparing) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.enhance_preparing, previewSeconds(range)), color = Secondary, fontSize = 12.sp)
                    }
                    if (failed) Text(stringResource(R.string.enhance_preview_failed), color = Warning, fontSize = 12.sp)
                    TextButton(onClick = remove) { Text(stringResource(R.string.common_remove), color = Color(0xFFFF453A)) }
                }
            }
        }
        if (matchingHelp) {
            AlertDialog(
                onDismissRequest = { matchingHelp = false },
                title = { Text(stringResource(R.string.enhance_match)) },
                text = { Text(stringResource(R.string.enhance_match_help)) },
                confirmButton = { TextButton(onClick = { matchingHelp = false }) { Text(stringResource(R.string.common_ok)) } },
            )
        }
    }
}

/** "0:03.2": minutes, seconds and tenths. */
private fun clockLabel(seconds: Double): String {
    val tenths = (seconds * 10).roundToInt()
    return String.format(Locale.ROOT, "%d:%02d.%d", tenths / 600, tenths / 10 % 60, tenths % 10)
}

/** How long the stretch prepared is, as the original writes it: whole seconds, tenths below one (YouRec/EffectApplication.swift:14-18). */
private fun previewSeconds(range: ClosedFloatingPointRange<Double>): String {
    val seconds = range.endInclusive - range.start
    return String.format(Locale.getDefault(), if (seconds < 1) "%.1f" else "%.0f", seconds)
}

/** The mean energy of each window of [range] of the mix of [plan] ([AudioListeningLevels.energies]). */
private fun energies(plan: TrackMixPlan, files: Map<String, File>, input: (TrackMixPiece) -> ProcessedSound?, range: ClosedFloatingPointRange<Double>): FloatArray =
    audioEngine(plan, files, input, MIX_RATE, 2).use { engine ->
        engine.seek(TrackMixer.firstFrame(EditTime.of(range.start), MIX_RATE))
        val frames = ((range.endInclusive - range.start) * MIX_RATE).toInt().coerceAtLeast(0)
        val all = FloatArray(frames * 2)
        val block = FloatArray(4096 * 2)
        var at = 0
        while (at < frames) {
            val count = minOf(4096, frames - at)
            engine.render(block, count)
            System.arraycopy(block, 0, all, at * 2, count * 2)
            at += count
        }
        AudioListeningLevels.energies(all, 2, MIX_RATE)
    }

@Composable
private fun AddFilterButton(add: (AudioEffectKind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(stringResource(R.string.enhance_add_filter), modifier = Modifier.padding(start = 6.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (kind in Addable) DropdownMenuItem(text = { Text(stringResource(kindTitle(kind))) }, onClick = { open = false; add(kind) })
        }
    }
}

internal fun kindTitle(kind: AudioEffectKind): Int = when (kind) {
    AudioEffectKind.VoiceIsolation -> R.string.sound_noise_reduction
    AudioEffectKind.HighPass -> R.string.enhance_low_cut
    AudioEffectKind.Equalizer -> R.string.enhance_voice_eq
    AudioEffectKind.Gain -> R.string.enhance_gain
    AudioEffectKind.StudioVoice -> R.string.enhance_studio_voice
    AudioEffectKind.StudioRestoration -> R.string.enhance_studio_restoration
    else -> R.string.enhance_unknown
}

private fun stripName(kind: AudioEffectKind): Int = when (kind) {
    AudioEffectKind.VoiceIsolation -> R.string.enhance_strip_denoise
    AudioEffectKind.HighPass -> R.string.enhance_strip_low_cut
    AudioEffectKind.Equalizer -> R.string.enhance_strip_eq
    AudioEffectKind.Gain -> R.string.enhance_strip_gain
    AudioEffectKind.StudioVoice -> R.string.enhance_strip_detail
    AudioEffectKind.StudioRestoration -> R.string.enhance_strip_studio
    else -> R.string.enhance_unknown
}

/** The small line under a column's name ([AudioFader.detailText]): the model, the EQ's centre, or what the fader sets. */
@Composable
private fun detail(effect: AudioEffect): String? = when {
    effect.kind == AudioEffectKind.HighPass -> stringResource(R.string.enhance_cutoff)
    effect.kind is AudioEffectKind.Unavailable -> null
    else -> AudioFader.detailText(effect)
}

/**
 * The chain as columns, in order (YouRec/AudioChainControls.swift:62-151): a count and the
 * reordering hint above them, the selected stage's settings below; "Build your sound" when empty.
 */
@Composable
private fun ChainControls(effects: List<AudioEffect>, selectedID: String?, select: (String) -> Unit, change: (List<AudioEffect>) -> Unit) {
    if (effects.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Tune, contentDescription = null, tint = Secondary, modifier = Modifier.size(40.dp))
            Text(stringResource(R.string.enhance_empty_title), color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            Text(stringResource(R.string.enhance_empty_text), color = Secondary, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        return
    }
    val selected = AudioChainEditing.selected(effects, selectedID) ?: return
    val index = effects.indexOfFirst { it.id == selected.id }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(pluralStringResource(R.plurals.enhance_filters, effects.size, effects.size), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp).padding(start = 2.dp))
        // Wraps under large text rather than pushing the count off (YouRec/AudioChainControls.swift:90-96 sets them in one row).
        Text(
            stringResource(R.string.enhance_reorder_hint), color = Secondary, fontSize = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
    }
    val density = LocalDensity.current
    val stripWidth = 82.dp
    val spacing = 10.dp
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(spacing)) {
        effects.forEachIndexed { at, effect ->
            key(effect.id) {
                EffectStrip(
                    effect, at, selected.id == effect.id, Modifier.width(stripWidth),
                    select = { select(effect.id) },
                    change = { next -> AudioChainEditing.replace(effects, next)?.let(change) },
                    reorder = { dx ->
                        val step = with(density) { (stripWidth + spacing).toPx() }
                        val target = (at + (dx / step).roundToInt()).coerceIn(0, effects.lastIndex)
                        select(effect.id)
                        if (target != at) AudioChainEditing.move(effects, effect.id, effects[target].id)?.let(change)
                    },
                )
            }
        }
    }
    SelectedStageSettings(
        selected, index, effects.size,
        change = { next -> AudioChainEditing.replace(effects, next)?.let(change) },
        move = { offset -> AudioChainEditing.shift(effects, index, offset)?.let(change) },
        remove = { change(AudioChainEditing.remove(effects, index).first) },
    )
}

/**
 * One column (YouRec/AudioChainControls.swift:154-215): its number and grip, which a long
 * press drags to another place, the on/off switch, and the fader.
 */
@Composable
private fun EffectStrip(
    effect: AudioEffect, index: Int, selected: Boolean, modifier: Modifier,
    select: () -> Unit, change: (AudioEffect) -> Unit, reorder: (Float) -> Unit,
) {
    var drag by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val currentReorder by rememberUpdatedState(reorder)
    val currentSelect by rememberUpdatedState(select)
    val color = stripColor(effect.kind)
    val title = stringResource(kindTitle(effect.kind))
    val gripLabel = stringResource(R.string.enhance_reorder, title)
    Column(
        modifier.offset { IntOffset(drag.roundToInt(), 0) }.zIndex(if (dragging) 1f else 0f),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(44.dp)
                .pointerInput(effect.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { dragging = true; drag = 0f; currentSelect() },
                        onDragEnd = { val dx = drag; dragging = false; drag = 0f; currentReorder(dx) },
                        onDragCancel = { dragging = false; drag = 0f },
                        onDrag = { change, amount -> change.consume(); drag += amount.x },
                    )
                }
                .pointerInput(effect.id) { detectTapGestures { currentSelect() } }
                .semantics { contentDescription = gripLabel }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${index + 1}", color = Secondary, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.DragHandle, contentDescription = null, tint = Secondary, modifier = Modifier.size(16.dp))
        }
        val powerLabel = stringResource(R.string.enhance_enabled, title)
        val on = stringResource(R.string.enhance_on)
        val off = stringResource(R.string.enhance_off)
        Row(
            Modifier.fillMaxWidth().height(44.dp).clip(CircleShape)
                .background(color.copy(alpha = if (effect.enabled) 0.15f else 0.04f))
                .clickable { select(); change(AudioChainEditing.toggled(effect)) }
                .semantics { contentDescription = powerLabel; stateDescription = if (effect.enabled) on else off },
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = if (effect.enabled) color else Secondary
            Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Text(if (effect.enabled) on else off, color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 5.dp))
        }
        Fader(effect, selected, select, change)
    }
}

/**
 * The filled fader (YouRec/AudioChainControls.swift:230-375): the level fills the column from
 * the bottom, or from the centre line for the EQ and the gain. A tap selects the stage; a
 * vertical drag moves the level from where it was, catching on the stage's starting value
 * and both ends with a tick ([AudioFader.drag]); the change is made when the finger lifts.
 */
@Composable
private fun Fader(effect: AudioEffect, selected: Boolean, select: () -> Unit, change: (AudioEffect) -> Unit) {
    val supported = effect.supported
    var dragging by remember { mutableStateOf(false) }
    var level by remember { mutableDoubleStateOf(AudioFader.position(effect)) }
    if (!dragging) level = AudioFader.position(effect)
    val current by rememberUpdatedState(effect)
    val currentChange by rememberUpdatedState(change)
    val currentSelect by rememberUpdatedState(select)
    val haptics = LocalHapticFeedback.current
    val color = stripColor(effect.kind)
    val shown = AudioFader.setting(effect, level)
    val label = stringResource(kindTitle(effect.kind))
    val bypassed = stringResource(R.string.enhance_bypassed)
    val bipolar = AudioFader.isBipolar(effect.kind)
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.fillMaxWidth().height(180.dp).clip(shape)
            .background(color.copy(alpha = if (effect.enabled) 0.1f else 0.035f))
            .border(if (selected) 2.5.dp else 1.5.dp, if (selected) Color.White else color.copy(alpha = 0.48f), shape)
            .drawBehind {
                val y = (1 - level.toFloat()) * size.height
                val middle = size.height / 2
                val top = if (bipolar) minOf(y, middle) else y
                val height = if (bipolar) abs(middle - y) else size.height - y
                drawRect(color.copy(alpha = if (effect.enabled) 0.48f else 0.10f), Offset(0f, top), Size(size.width, height))
                if (bipolar) drawRect(Color.White.copy(alpha = 0.24f), Offset(8.dp.toPx(), middle), Size(size.width - 16.dp.toPx(), 1.dp.toPx()))
                drawRect(color.copy(alpha = if (effect.enabled) 1f else 0.4f), Offset(0f, y.coerceIn(0f, size.height - 2.dp.toPx())), Size(size.width, 2.dp.toPx()))
            }
            .pointerInput(effect.id, supported) {
                if (!supported) return@pointerInput
                var origin = 0.0
                var travel = 0.0
                var caught: Double? = null
                detectVerticalDragGestures(
                    onDragStart = { dragging = true; origin = AudioFader.position(current); travel = 0.0; caught = null; currentSelect() },
                    onDragEnd = { dragging = false; currentChange(AudioFader.setting(current, level)) },
                    onDragCancel = { dragging = false; level = AudioFader.position(current) },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        travel -= amount / size.height
                        level = AudioFader.drag(current.kind, origin, travel)
                        val snap = AudioFader.snap(current.kind, level)
                        if (snap != null && snap != caught) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        caught = snap
                    },
                )
            }
            .pointerInput(effect.id) { detectTapGestures { currentSelect() } }
            .semantics {
                contentDescription = label
                stateDescription = AudioFader.valueText(effect) + if (effect.enabled) "" else ", $bypassed"
                progressBarRangeInfo = ProgressBarRangeInfo(AudioFader.position(effect).toFloat(), 0f..1f)
                if (supported) setProgress { target -> currentSelect(); currentChange(AudioFader.setting(current, target.toDouble())); true }
            },
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 16.dp, start = 4.dp, end = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(stripName(effect.kind)), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            detail(effect)?.let { Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, maxLines = 1) }
        }
        Text(
            AudioFader.valueText(shown), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
        )
    }
}

/**
 * The selected stage's card (YouRec/AudioChainControls.swift:383-446): its name, Bypassed
 * when off; the model of noise reduction, the EQ's frequency, or a word on what the stage
 * does; moving it earlier or later, Reset and Remove.
 */
@Composable
private fun SelectedStageSettings(effect: AudioEffect, index: Int, count: Int, change: (AudioEffect) -> Unit, move: (Int) -> Unit, remove: () -> Unit) {
    val color = stripColor(effect.kind)
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(color.copy(alpha = 0.07f)).border(1.dp, color.copy(alpha = 0.22f), shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Text(stringResource(kindTitle(effect.kind)), color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp).weight(1f))
            if (!effect.enabled) Text(stringResource(R.string.enhance_bypassed), color = Secondary, fontSize = 12.sp)
        }
        when {
            !effect.supported -> Text(stringResource(R.string.enhance_unavailable), color = Warning, fontSize = 12.sp)
            effect.kind == AudioEffectKind.VoiceIsolation -> {
                var open by remember { mutableStateOf(false) }
                val name = if (effect.model == AudioModel.DeepFilter) "DeepFilterNet3" else "RNNoise"
                val modelLabel = stringResource(R.string.enhance_model)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(modelLabel, color = Secondary, modifier = Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = "$modelLabel: $name" }) {
                            Text(name)
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            DropdownMenuItem(text = { Text("RNNoise") }, onClick = { open = false; change(effect.copy(model = AudioModel.RNNoise)) })
                            DropdownMenuItem(text = { Text("DeepFilterNet3") }, onClick = { open = false; change(effect.copy(model = AudioModel.DeepFilter)) })
                        }
                    }
                }
                Text(stringResource(R.string.enhance_noise_note), color = Secondary, fontSize = 12.sp)
            }
            effect.kind == AudioEffectKind.Equalizer -> {
                var frequency by remember(effect.id, effect.frequency) { mutableDoubleStateOf(effect.frequency) }
                Row {
                    Text(stringResource(R.string.enhance_frequency), color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(AudioFader.frequencyText(frequency), color = Color.White, fontSize = 14.sp)
                }
                Slider(
                    value = AudioFader.eqFrequencyPosition(frequency).toFloat(),
                    onValueChange = { frequency = AudioFader.eqFrequency(it.toDouble()) },
                    onValueChangeFinished = { change(effect.copy(frequency = frequency)) },
                    colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color),
                )
                Text(stringResource(R.string.enhance_eq_note), color = Secondary, fontSize = 12.sp)
            }
            effect.kind == AudioEffectKind.HighPass -> Text(stringResource(R.string.enhance_low_cut_note), color = Secondary, fontSize = 12.sp)
            effect.kind == AudioEffectKind.Gain -> Text(stringResource(R.string.enhance_gain_note), color = Secondary, fontSize = 12.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val earlier = stringResource(R.string.enhance_move_earlier)
            val later = stringResource(R.string.enhance_move_later)
            val removeLabel = stringResource(R.string.enhance_remove_stage)
            IconButton(onClick = { move(-1) }, enabled = index > 0, modifier = Modifier.semantics { contentDescription = earlier }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
            IconButton(onClick = { move(1) }, enabled = index + 1 < count, modifier = Modifier.semantics { contentDescription = later }) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { change(AudioChainEditing.reset(effect)) }, enabled = effect.supported) {
                Text(stringResource(R.string.common_reset), color = Color.White.copy(alpha = 0.85f))
            }
            IconButton(onClick = remove, modifier = Modifier.semantics { contentDescription = removeLabel }) {
                Icon(Icons.Outlined.Delete, contentDescription = null, tint = Color(0xFFFF453A))
            }
        }
    }
}
