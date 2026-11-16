package app.takes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import app.takes.core.EditState
import app.takes.core.PauseChoice
import app.takes.core.PauseDetector
import app.takes.core.PauseLevelMode
import app.takes.core.PauseSettings
import app.takes.core.PauseTool
import app.takes.core.SourceInterval
import app.takes.core.SourceWaveform
import app.takes.core.newID
import kotlinx.coroutines.delay
import java.io.File
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import kotlin.math.roundToInt

private val Soft = Color(0xFFA0A4AB)

/**
 * The Pauses tool at work, as the original's tool workspace keeps it
 * (YouRec/EditorTools.swift:337-357, :934-1041): the clips [ids] of [base]
 * it acts on, the detection settings, every pause found with its Cut or
 * Keep, and the edit they make ([draft]), which the editor shows and plays
 * until it is applied. [from] is the saved edit it was opened on.
 */
internal class Pausing(val from: EditState, val base: EditState, val ids: List<String>, val label: String?) {
    /** Whether the clips had pauses taken out when the tool opened: it then opens on them, and can remove them. */
    val existing = PauseTool.saved(base, ids)
    private val opened = PauseTool.opened(base, ids)
    /** The id the settings are saved under: the one they had, or a new one. */
    val id: String = PauseTool.settings(base, ids).id.takeIf { existing } ?: newID()

    var settings by mutableStateOf(PauseTool.settings(base, ids))
    var choices by mutableStateOf(opened.first)
    /** The silence level each clip was scanned at. */
    var levels by mutableStateOf(opened.second)
    var selected by mutableIntStateOf(0)
    /** A scan has given its pauses, or the tool opened on saved ones. */
    var detected by mutableStateOf(existing)
    var scanning by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    var audible by mutableStateOf(true)
    /** Before: the clips with every pause, as they were before any was taken out. */
    var original by mutableStateOf(false)
    /** Asks for a scan: a number that changes, with whether it waits a moment first, for a slider still moving. */
    var scan by mutableStateOf(if (existing) 0 to false else 1 to false)
    /** Where the playhead was when Listen began; null when not listening. */
    var listeningFrom by mutableStateOf<Double?>(null)

    /** The pauses chosen to keep, by clip, across scans (YouRec/EditorTools.swift:385-387). */
    var kept: Map<String, List<SourceInterval>> = choices.filter { !it.enabled }.groupBy({ it.clipID }, { it.raw })

    /** The clips without a pause taken out. */
    val uncut: EditState = PauseTool.state(base, ids, settings, emptyList(), emptyMap(), id, removing = true)

    private var made: Pair<List<Any?>, EditState>? = null

    /** The edit with the choices as they are now. */
    val draft: EditState
        get() {
            val key = listOf(settings, choices, levels)
            made?.let { if (it.first == key) return it.second }
            return PauseTool.state(base, ids, settings, choices, levels, id).also { made = key to it }
        }

    /** What the editor shows: the draft, or Before. */
    val shown: EditState get() = if (original) uncut else draft

    fun select(index: Int) {
        if (choices.isEmpty()) return
        selected = index.coerceIn(0, choices.lastIndex)
    }

    fun setCut(enabled: Boolean) {
        val choice = choices.getOrNull(selected) ?: return
        kept = kept + (choice.clipID to PauseTool.keeping(kept[choice.clipID].orEmpty(), choice.raw, enabled))
        choices = choices.toMutableList().also { it[selected] = choice.copy(enabled = enabled) }
    }

    fun change(next: PauseSettings) {
        val old = settings
        settings = next
        if (PauseTool.rescans(old, next) || scanning) scan = scan.first + 1 to true
    }

    /** Automatic level on or off; turned off it starts from the level shown (YouRec/EditorTools.swift:351-355). */
    fun automatic(on: Boolean) {
        var next = settings
        if (!on) next = next.copy(thresholdDB = shownLevel)
        change(next.copy(levelMode = if (on) PauseLevelMode.Automatic else PauseLevelMode.Manual))
    }

    /** The level of the selected pause's clip, or of the first clip (YouRec/EditorTools.swift:341-344). */
    val shownLevel: Double
        get() = (choices.getOrNull(selected)?.clipID ?: ids.firstOrNull())?.let { levels[it] } ?: settings.thresholdDB
}

/** Seconds with at most two decimals, as the original writes the sliders' values. */
private fun decimals(value: Double, most: Int = 2): String =
    DecimalFormat(if (most == 0) "0" else "0." + "#".repeat(most), DecimalFormatSymbols.getInstance()).format(value)

private fun fixed(value: Double): String = DecimalFormat("0.00", DecimalFormatSymbols.getInstance()).format(value)

/**
 * Scans the clips of [work] for pauses with its settings, as the original's
 * analysis does (YouRec/EditorTools.swift:934-979): each recording measured
 * once ([PauseLevels]), its silence level estimated once, the pauses of each
 * clip found raw and those chosen to keep kept.
 */
private suspend fun scanned(work: Pausing, files: Map<String, File>) {
    val settings = work.settings
    val anchor = work.choices.getOrNull(work.selected)
    val clips = work.base.clips.filter { it.id in work.ids }
    val sources = clips.map { it.sourceID }.distinct()
    val waves = HashMap<String, SourceWaveform?>()
    val levels = HashMap<String, Double>()
    for ((number, source) in sources.withIndex()) {
        val file = files[source]
        waves[source] = file?.let { PauseLevels.measure(it) { part -> work.progress = (number + part) / sources.size } }
        work.progress = (number + 1f) / sources.size
    }
    val proposed = ArrayList<PauseChoice>()
    val sourceLevels = HashMap<String, Double>()
    var audible = false
    for (clip in clips) {
        val wave = waves[clip.sourceID] ?: continue
        audible = audible || wave.peaks.isNotEmpty()
        val level = sourceLevels.getOrPut(clip.sourceID) { PauseDetector.levelDB(wave, settings) }
        if (wave.peaks.isNotEmpty()) levels[clip.id] = level
        val raws = PauseDetector.raw(wave, clip, settings, level, clip.rate)
        proposed += PauseTool.found(clip.id, raws, work.kept[clip.id].orEmpty())
    }
    work.choices = proposed
    work.audible = audible
    work.levels = levels
    work.selected = PauseTool.nearest(proposed, anchor) ?: work.selected.coerceIn(0, maxOf(0, proposed.lastIndex))
    work.detected = true
}

/**
 * The Pauses tool, in place of the timeline: the controls of the original's
 * (YouRec/EditorTools.swift:1395-1471). The summary of what the cuts remove,
 * each pause in turn with Cut or Keep and Listen, and the detection settings:
 * automatic or manual silence level, the shortest pause, and how much of the
 * pause to keep after and before speech. Nothing is saved until [apply];
 * [remove] puts back every pause the clips had taken out.
 *
 * @param seek puts the playhead on a second of the movie as [Pausing.shown] has it.
 * @param listen plays the stretch given and goes back to where it began; [stop] ends it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PausesPanel(
    work: Pausing, files: Map<String, File>, playing: Boolean,
    seek: (Double) -> Unit, listen: (ClosedFloatingPointRange<Double>) -> Unit, stop: () -> Unit,
    apply: (EditState) -> Unit, remove: (EditState) -> Unit, cancel: () -> Unit, modifier: Modifier = Modifier,
) {
    var showSettings by remember { mutableStateOf(false) }
    val settings = work.settings
    val shown = work.shown

    // A scan when the tool opens on clips without pauses, on Scan again, and when the level or the shortest pause changes.
    LaunchedEffect(work, work.scan) {
        val (asked, waiting) = work.scan
        if (asked == 0) return@LaunchedEffect
        if (waiting) delay(250)
        work.scanning = true
        work.progress = 0f
        try {
            scanned(work, files)
        } finally {
            work.scanning = false
        }
    }
    // The playhead goes to the pause chosen, as it is shown (YouRec/EditorTools.swift:1009-1012, :837).
    val choice = work.choices.getOrNull(work.selected)
    LaunchedEffect(work, work.selected, work.detected, work.original, choice?.enabled, settings.before, settings.after) {
        if (work.listeningFrom != null) return@LaunchedEffect
        choice?.let { seek(PauseTool.range(shown, it, settings, work.original).start) }
    }
    LaunchedEffect(playing) { if (!playing) work.listeningFrom = null }

    Column(modifier.fillMaxWidth().background(Color(0xFF1B1D21))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = cancel) { Text(stringResource(R.string.common_cancel)) }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.editor_pauses), color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                work.label?.let { Text(it, color = Soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (work.existing) {
                // Removing this effect restores the pauses (YouRec/EditorTools.swift:1096-1100).
                val removing = stringResource(R.string.pauses_title)
                TextButton(onClick = { remove(work.uncut) }, modifier = Modifier.semantics { contentDescription = removing }) {
                    Icon(Icons.Outlined.Delete, null, tint = Color.White.copy(alpha = 0.7f))
                }
            }
            // Apply once a scan has found something, or on pauses already saved (YouRec/EditorTools.swift:409).
            TextButton(onClick = { apply(work.draft) }, enabled = !work.scanning && work.detected && (work.choices.isNotEmpty() || work.existing)) {
                Text(stringResource(R.string.crop_apply), fontWeight = FontWeight.Bold)
            }
        }
        // Before and After, as the original's tool preview (YouRec/EditorTools.swift:1266-1272).
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f))
            SingleChoiceSegmentedButtonRow {
                for ((index, before) in listOf(true, false).withIndex()) {
                    SegmentedButton(
                        selected = work.original == before, onClick = { work.original = before },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                    ) { Text(stringResource(if (before) R.string.pauses_before else R.string.pauses_after)) }
                }
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (work.scanning) {
                Text(
                    stringResource(if (settings.levelMode == PauseLevelMode.Automatic) R.string.pauses_listening_speech else R.string.pauses_measuring),
                    color = Color.White, fontWeight = FontWeight.SemiBold,
                )
                val scanName = stringResource(R.string.pauses_scan_progress)
                LinearProgressIndicator(progress = { work.progress }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = scanName })
            } else if (work.detected) {
                val (count, shorter) = PauseTool.summary(work.draft, work.ids)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pluralStringResource(R.plurals.pauses_summary, count, count, fixed(shorter)), color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { work.scan = work.scan.first + 1 to false }) {
                        Icon(Icons.Filled.Refresh, null, Modifier.padding(end = 4.dp))
                        Text(stringResource(R.string.pauses_scan_again))
                    }
                }
                Text(stringResource(R.string.pauses_lengths, preciseLabel(work.uncut.duration), preciseLabel(work.draft.duration)), color = Soft, fontSize = 12.sp)
            }
            if (choice != null) {
                val clip = work.base.clips.firstOrNull { it.id == choice.clipID }
                val removable = clip?.let { PauseTool.cutSeconds(it, choice.raw, settings) } ?: 0.0
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.04f)).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { work.select(work.selected - 1) }, enabled = !work.scanning && work.selected > 0) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.pauses_previous))
                        }
                        Text(stringResource(R.string.pauses_selection, work.selected + 1, work.choices.size), color = Color.White, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        IconButton(onClick = { work.select(work.selected + 1) }, enabled = !work.scanning && work.selected < work.choices.lastIndex) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.pauses_next))
                        }
                    }
                    val at = preciseLabel(PauseTool.range(shown, choice, settings, work.original).start)
                    Text(stringResource(if (work.original) R.string.pauses_at_before else R.string.pauses_at_after, at), color = Soft, fontSize = 12.sp)
                    if (removable < PauseDetector.FRAME) {
                        Text(stringResource(R.string.pauses_no_room), color = Soft, fontSize = 12.sp)
                    } else {
                        val thisPause = stringResource(R.string.pauses_this)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().semantics { contentDescription = thisPause }) {
                            for ((index, cut) in listOf(true, false).withIndex()) {
                                SegmentedButton(
                                    selected = choice.enabled == cut, onClick = { work.setCut(cut) }, enabled = !work.scanning,
                                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                                ) { Text(stringResource(if (cut) R.string.pauses_cut else R.string.pauses_keep)) }
                            }
                        }
                        Text(
                            if (choice.enabled) stringResource(R.string.pauses_remove_seconds, fixed(removable)) else stringResource(R.string.pauses_keep_note),
                            color = Soft, fontSize = 12.sp,
                        )
                    }
                    val listening = work.listeningFrom != null && playing
                    Button(
                        onClick = {
                            if (listening) stop() else {
                                val range = PauseTool.range(shown, choice, settings, work.original)
                                work.listeningFrom = range.start
                                listen(PauseTool.listening(range, shown.duration))
                            }
                        },
                        enabled = !work.scanning, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    ) {
                        Icon(if (listening) Icons.Filled.Stop else Icons.Filled.PlayArrow, null, Modifier.padding(end = 6.dp))
                        Text(
                            stringResource(
                                when {
                                    listening -> R.string.pauses_stop
                                    work.original || !choice.enabled || removable < PauseDetector.FRAME -> R.string.pauses_listen_pause
                                    else -> R.string.pauses_listen_cut
                                },
                            ),
                        )
                    }
                }
            } else if (work.detected && !work.scanning) {
                Text(
                    stringResource(
                        when {
                            !work.audible -> R.string.pauses_no_audio
                            settings.levelMode == PauseLevelMode.Automatic -> R.string.pauses_none_automatic
                            else -> R.string.pauses_none_manual
                        },
                    ),
                    color = Soft, fontSize = 14.sp,
                )
            }
            // Detection settings, closed until asked for (YouRec/EditorTools.swift:1448-1468).
            val detection = stringResource(R.string.pauses_detection)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).clickable { showSettings = !showSettings }.semantics { role = Role.Button },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(detection, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Icon(if (showSettings) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Soft)
            }
            if (showSettings) {
                val automatic = settings.levelMode == PauseLevelMode.Automatic
                val automaticName = stringResource(R.string.pauses_automatic)
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(automaticName, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(automatic, { work.automatic(it) }, enabled = !(work.scanning && automatic), modifier = Modifier.semantics { contentDescription = automaticName })
                }
                if (automatic) {
                    Text(stringResource(R.string.pauses_automatic_note), color = Soft, fontSize = 12.sp)
                    val low = work.levels.values.minOrNull()
                    val high = work.levels.values.maxOrNull()
                    if (work.detected && work.audible && low != null && high != null) {
                        Text(
                            if (low == high) stringResource(R.string.pauses_reference_level, decimals(low))
                            else stringResource(R.string.pauses_reference_levels, decimals(low), decimals(high)),
                            color = Soft, fontSize = 12.sp,
                        )
                    }
                } else {
                    Parameter(
                        stringResource(R.string.pauses_silence_below), settings.thresholdDB.coerceIn(PauseTool.THRESHOLD), PauseTool.THRESHOLD, PauseTool.THRESHOLD_STEP,
                        stringResource(if (settings.levelMode == null) R.string.pauses_dbfs_peak else R.string.pauses_dbfs_rms),
                    ) { work.change(settings.copy(thresholdDB = it)) }
                    Text(stringResource(R.string.pauses_manual_note), color = Soft, fontSize = 12.sp)
                }
                val second = stringResource(R.string.pauses_seconds)
                Parameter(stringResource(R.string.pauses_minimum), settings.minimum.coerceIn(PauseTool.MINIMUM), PauseTool.MINIMUM, PauseTool.MINIMUM_STEP, second) {
                    work.change(settings.copy(minimum = it))
                }
                // "Keep after speech" is the padding at the start of a pause, "Keep before speech" the one at its end.
                Parameter(stringResource(R.string.pauses_keep_after_speech), settings.before.coerceIn(PauseTool.PADDING), PauseTool.PADDING, PauseTool.PADDING_STEP, second) {
                    work.change(settings.copy(before = it))
                }
                Parameter(stringResource(R.string.pauses_keep_before_speech), settings.after.coerceIn(PauseTool.PADDING), PauseTool.PADDING, PauseTool.PADDING_STEP, second) {
                    work.change(settings.copy(after = it))
                }
                Text(stringResource(R.string.pauses_settings_note), color = Soft, fontSize = 12.sp)
            }
            Text(stringResource(R.string.pauses_saved_note), color = Soft, fontSize = 12.sp)
        }
    }
}

/** A slider with its name and value, on a grid of [step] (YouRec/EditorTools.swift:1472-1477). */
@Composable
private fun Parameter(title: String, value: Double, range: ClosedFloatingPointRange<Double>, step: Double, suffix: String, change: (Double) -> Unit) {
    val shown = "${decimals(value)} $suffix"
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(title, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(shown, color = Color.White, fontSize = 12.sp)
        }
        val steps = ((range.endInclusive - range.start) / step).roundToInt() - 1
        Slider(
            value = value.toFloat(),
            onValueChange = { raw ->
                val snapped = range.start + ((raw - range.start) / step).roundToInt() * step
                val next = (Math.round(snapped * 1000) / 1000.0).coerceIn(range)
                if (next != value) change(next)
            },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(), steps = steps,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = title; stateDescription = shown },
        )
    }
}
