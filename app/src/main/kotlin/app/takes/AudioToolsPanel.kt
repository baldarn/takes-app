package app.takes

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takes.core.AudioCensorSettings
import app.takes.core.AudioChainScope
import app.takes.core.AudioEffect
import app.takes.core.AudioToolKind
import app.takes.core.AudioToolRegion
import app.takes.core.CensorEnvelope
import app.takes.core.CensorWaveform
import app.takes.core.EditInterval
import app.takes.core.EditState
import app.takes.core.TimelineSelection
import app.takes.core.TrackAudio
import app.takes.core.TrackAudioRegions
import app.takes.core.TrackEditState
import app.takes.core.TrackSequenceView
import app.takes.core.TrackTimeline
import app.takes.core.toTimeline
import kotlin.math.roundToInt

private val Soft = Color(0xFFA0A4AB)
private val EnhanceColor = Color(0xFF64D2FF)
private val CensorColor = Color(0xFFFF9F0A)

/**
 * The sounds of the timeline that the clips [clips] and the sound items
 * [sounds] of [state] stand for, in [range] when there is one: what Enhance
 * audio and Censor act on. A clip stands for its own sound.
 */
internal fun soundSelection(state: EditState, clips: Collection<String>, sounds: Collection<String>, range: EditInterval?): TimelineSelection {
    val timeline = state.toTimeline()
    val ids = LinkedHashSet<String>()
    for (clip in clips) {
        if (state.isSequence) ids += TrackTimeline.counterpart(clip)
        else timeline.audio.filter { it.followsVideo == clip }.forEach { ids += it.id }
    }
    for (sound in sounds) ids += TrackSequenceView.soundID(state, sound)
    return TimelineSelection(audio = ids.filterTo(LinkedHashSet()) { id -> timeline.audio.any { it.id == id } }, range = range)
}

/** [state] with [change] made to its timeline; null when nothing changes. A sequence that can no longer be one is saved as its timeline. */
internal fun withTimeline(state: EditState, change: (TrackTimeline) -> TrackTimeline): EditState? {
    val timeline = state.toTimeline()
    val next = change(timeline)
    return if (next == timeline) null else TrackEditState.edit(next, state.captureBase)
}

/** The censor settings of the region [effectID], if the timeline has one. */
internal fun censorSettings(timeline: TrackTimeline, effectID: String): AudioCensorSettings? =
    timeline.audio.firstNotNullOfOrNull { item -> item.censors.firstOrNull { it.id == effectID }?.settings }

/**
 * Censor on a stretch of sound (YouRec/TrackEditors.swift:330-380): the tone
 * that replaces it, its pitch, level, wave and how it starts and ends. One
 * tone is shared by the sounds that overlap ([app.takes.core.AudioCensor]).
 */
@Composable
internal fun CensorDialog(initial: AudioCensorSettings, apply: (AudioCensorSettings) -> Unit, remove: (() -> Unit)?, dismiss: () -> Unit) {
    var settings by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.censor_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.censor_frequency, settings.frequency.roundToInt()), color = Color.White)
                Slider(
                    value = settings.frequency.toFloat(),
                    onValueChange = { settings = settings.copy(frequency = (it / 10).roundToInt() * 10.0) },
                    valueRange = AudioCensorSettings.MINIMUM_FREQUENCY.toFloat()..AudioCensorSettings.MAXIMUM_FREQUENCY.toFloat(),
                )
                Text(stringResource(R.string.censor_level, levelLabel(settings.levelDB)), color = Color.White)
                Slider(
                    value = settings.levelDB.toFloat(),
                    onValueChange = { settings = settings.copy(levelDB = it.roundToInt().toDouble()) },
                    valueRange = AudioCensorSettings.MINIMUM_LEVEL_DB.toFloat()..AudioCensorSettings.MAXIMUM_LEVEL_DB.toFloat(),
                )
                Text(stringResource(R.string.censor_wave), color = Color.White)
                FlowRow {
                    for ((wave, name) in listOf(CensorWaveform.Sine to R.string.censor_sine, CensorWaveform.Triangle to R.string.censor_triangle, CensorWaveform.Square to R.string.censor_square)) {
                        FilterChip(selected = settings.waveform == wave, onClick = { settings = settings.copy(waveform = wave) }, label = { Text(stringResource(name)) }, modifier = Modifier.padding(end = 6.dp))
                    }
                }
                Text(stringResource(R.string.censor_envelope), color = Color.White)
                FlowRow {
                    for ((shape, name) in listOf(CensorEnvelope.Short to R.string.censor_short, CensorEnvelope.Soft to R.string.censor_soft, CensorEnvelope.Pulse to R.string.censor_pulse)) {
                        FilterChip(selected = settings.envelope == shape, onClick = { settings = settings.copy(envelope = shape) }, label = { Text(stringResource(name)) }, modifier = Modifier.padding(end = 6.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { apply(settings) }) { Text(stringResource(R.string.common_save)) } },
        dismissButton = {
            Row {
                remove?.let { TextButton(onClick = it) { Text(stringResource(R.string.common_remove)) } }
                TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}

/**
 * The badges of Enhance audio and Censor on the timeline ([TrackAudioRegions.badges]), in a strip under it that
 * scrolls with it: one for each region, as long as it, at its place. A tap opens its tool on what it acts on.
 */
@Composable
internal fun AudioToolBadges(timeline: TrackTimeline, scroll: ScrollState, pixelsPerSecond: Float, open: (AudioToolRegion) -> Unit) {
    val badges = remember(timeline) { TrackAudioRegions.badges(timeline) }
    if (badges.isEmpty()) return
    val density = LocalDensity.current
    // The regions that overlap go on rows of their own, so each can be tapped.
    val rows = remember(badges) {
        val ends = ArrayList<Double>()
        badges.sortedBy { it.interval.start }.map { badge ->
            val row = ends.indexOfFirst { it <= badge.interval.start.seconds }.let { if (it < 0) { ends += 0.0; ends.lastIndex } else it }
            ends[row] = badge.interval.end.seconds
            badge to row
        }
    }
    val count = (rows.maxOfOrNull { it.second } ?: 0) + 1
    // As the timeline lays its items out: second 0 under the playhead in the middle when it has not scrolled.
    BoxWithConstraints(Modifier.fillMaxWidth().height((count * 18).dp).clipToBounds()) {
        val half = with(density) { maxWidth.toPx() } / 2
        for ((badge, row) in rows) {
            val left = half + (badge.interval.start.seconds * pixelsPerSecond).toFloat() - scroll.value
            val w = with(density) { ((badge.interval.end - badge.interval.start).seconds * pixelsPerSecond).toFloat().toDp() }
            val enhance = badge.kind == AudioToolKind.Enhance
            Box(
                // As long as its region even past the width of the screen, which would otherwise cut it short.
                Modifier.offset { IntOffset(left.roundToInt(), (row * 18).dp.roundToPx()) }.wrapContentWidth(Alignment.Start, unbounded = true).width(w).height(16.dp)
                    .background((if (enhance) EnhanceColor else CensorColor).copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                    .clickable { open(badge) },
            ) {
                Text(stringResource(if (enhance) R.string.enhance_badge else R.string.censor_badge), color = Color.Black, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp))
            }
        }
    }
}

/** What a badge's tool works on and does, for [state] and the selection [selection]. */
internal class AudioToolWork(val state: EditState, val region: AudioToolRegion?, val targets: TimelineSelection) {
    private val timeline = state.toTimeline()

    val chain: AudioChainScope get() = TrackAudioRegions.chainScope(timeline, targets)
    val censor: AudioCensorSettings get() = region?.let { censorSettings(timeline, it.effectID) } ?: AudioCensorSettings()

    fun enhanced(effects: List<AudioEffect>): EditState? = withTimeline(state) {
        if (region != null) TrackAudio.enhancing(it, effects, targets, region.effectID) else TrackAudio.enhancing(it, effects, targets)
    }

    fun censored(settings: AudioCensorSettings): EditState? = withTimeline(state) {
        if (region != null) TrackAudio.censoring(it, settings, targets, region.effectID) else TrackAudio.censoring(it, settings, targets)
    }

    fun removed(): EditState? = region?.let { badge -> withTimeline(state) { TrackAudioRegions.removing(it, badge.kind, badge.effectID, targets) } }

    companion object {
        /** The tool opened from the badge [region], acting on what [TrackAudioRegions.targets] gives with [selection]. */
        fun of(state: EditState, region: AudioToolRegion, selection: TimelineSelection): AudioToolWork =
            AudioToolWork(state, region, TrackAudioRegions.targets(state.toTimeline(), region, selection))
    }
}

/**
 * The progress of Enhance audio processing the stretch about to be heard, over the middle of the preview, as the
 * original shows its preparation (YouRec/EditorView.swift:468-478): a bar and the words with how much is done, on
 * a dark rounded panel. Over the picture rather than beside it, so nothing moves when it comes and goes.
 */
@Composable
fun CleaningProgress(progress: Float, modifier: Modifier = Modifier) {
    Column(
        modifier.background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(16.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.material3.LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(160.dp))
        Text(stringResource(R.string.editor_reducing_noise, (progress * 100).roundToInt()), color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    }
}
