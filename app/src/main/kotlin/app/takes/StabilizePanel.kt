package app.takes

import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takes.core.EditState
import app.takes.core.EffectPreviewWindow
import app.takes.core.RangeEditing
import app.takes.core.SourceMedia
import app.takes.core.StabilizationLevel
import app.takes.core.StabilizationPlan
import app.takes.core.TimelineClip
import app.takes.core.TimelineEditing
import app.takes.core.newID
import app.takes.core.stabilizing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private val Soft = Color(0xFFA0A4AB)
private val Chosen = Color(0xFFFF4D57)

/**
 * The Stabilize tool open on [from], the edit as it was saved: on the clips of [scope] (a range or a selection,
 * cut as the other tools cut it), else on [shown], the clip under the playhead, as the original's tool workspace
 * applies it (YouRec/EditorTools.swift:437-442, 710-712). [shown] begins at second [shownStart] of the movie; the
 * tool previews it, ten seconds of it around [anchor] (:472-482, 731-742).
 */
internal class Stabilizing(
    val from: EditState, val scope: RangeEditing.Scoped?, val label: String?, val ranged: Boolean,
    val shown: TimelineClip, val shownStart: Double, val anchor: Double,
) {
    private val targets: List<TimelineClip> = scope?.let { s -> s.state.clips.filter { it.id in s.clips } } ?: listOf(shown)

    /** The level the clips have when they all have the same one, Off aside (:437-440). */
    val saved: StabilizationLevel? = targets.map { it.stabilization?.level ?: StabilizationLevel.Off }.toSet().singleOrNull()?.takeIf { it != StabilizationLevel.Off }

    /** The level chosen; the subject's own, or Standard (:442). */
    var level by mutableStateOf(shown.stabilization?.level ?: StabilizationLevel.Standard)

    /** The clips shown as they were (Before) or stabilised (After) (:1266-1272). */
    var original by mutableStateOf(false)

    /** The preview stopped while it was being measured (:396-401). */
    var stopped by mutableStateOf(false)

    /** How far the preview's window is measured, while it is. */
    var progress by mutableStateOf<Double?>(null)

    /** The plan of the window at [level], once measured. */
    var plan by mutableStateOf<StabilizationPlan?>(null)

    var failed by mutableStateOf(false)
    var attempt by mutableIntStateOf(0)

    /** The window previewed, in seconds of the movie. */
    val window = EffectPreviewWindow.range(anchor, shownStart..shownStart + shown.duration)

    /** The same window in source seconds of [shown]. */
    val sourceWindow = shown.sourceTime(window.start - shownStart)..shown.sourceTime(window.endInclusive - shownStart)

    /** The edit with the level chosen on every clip it applies to, null when nothing changes. */
    fun applied(): EditState? {
        val id = targets.firstNotNullOfOrNull { it.stabilization?.id } ?: newID()
        val base = scope?.state ?: from
        var state = base
        var changed = false
        for (target in targets) {
            val index = state.clips.indexOfFirst { it.id == target.id }
            TimelineEditing.setStabilization(state, index, level, id)?.let { state = it; changed = true }
        }
        // A range cut with nothing changed is no change either.
        return state.takeIf { changed }
    }
}

/**
 * The Stabilize tool, as the original's (YouRec/EditorTools.swift:1286-1310, 1131-1172): Off, Light, Standard and
 * Strong, what each does, the crop zoom the preview needs, Before and After; the editor's preview shows [work.shown]
 * as the level chosen would leave it, ten seconds of it measured first. [apply] is given the edit to commit, null
 * when nothing changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StabilizePanel(
    work: Stabilizing, preview: PreviewStabilization, source: SourceMedia?, folder: File,
    apply: (EditState?) -> Unit, cancel: () -> Unit, modifier: Modifier = Modifier,
) {
    // The window measured for the level chosen, and shown in the editor's preview in place of the clip's own plan.
    LaunchedEffect(work, work.level, work.stopped, work.attempt) {
        work.failed = false
        val candidate = work.shown.copy(stabilization = work.shown.stabilizing(work.level))
        val (from, to) = work.sourceWindow.start to work.sourceWindow.endInclusive
        if (work.level == StabilizationLevel.Off || source == null || work.stopped || to - from < 0.1) {
            work.plan = null
            preview.trial = mapOf(work.shown.id to PreviewStabilization.Trial(candidate, null, from, to))
            return@LaunchedEffect
        }
        work.progress = 0.0
        val context = currentCoroutineContext()
        try {
            val made = withContext(Dispatchers.IO) {
                preview.rotation(source)
                Stabilizer.samplePlan(source, candidate, work.level, from, to, folder, progress = { work.progress = it }, cancel = { !context.isActive })
            }
            work.plan = made
            preview.trial = mapOf(work.shown.id to PreviewStabilization.Trial(candidate, made, from, to))
        } catch (_: java.util.concurrent.CancellationException) {
        } catch (problem: Exception) {
            android.util.Log.w("Stabilization", "preview failed", problem)
            work.failed = true
        } finally {
            work.progress = null
        }
    }
    LaunchedEffect(work.original) { preview.before = work.original }
    DisposableEffect(work) { onDispose { preview.trial = emptyMap(); preview.before = false } }

    Column(modifier.fillMaxWidth().background(Color(0xFF1B1D21))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = cancel) { Text(stringResource(R.string.common_cancel)) }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.stabilize_title), color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                // The scope, and the stretch the preview measures (:1125-1129).
                val windowLabel = stringResource(R.string.stabilize_window, EffectPreviewWindow.seconds(work.window))
                Text(listOfNotNull(work.label, windowLabel).joinToString(" · "), color = Soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Apply, Apply changes over a level already there, or Done on the level the clips have (:1141-1149).
            TextButton(onClick = { apply(work.applied()) }) {
                val label = when (work.saved) {
                    null -> R.string.crop_apply
                    work.level -> R.string.stabilize_done
                    else -> R.string.stabilize_apply_changes
                }
                Text(stringResource(label), fontWeight = FontWeight.Bold)
            }
        }
        // Before and After (:1266-1272).
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f))
            SingleChoiceSegmentedButtonRow {
                for ((index, before) in listOf(true, false).withIndex()) {
                    SegmentedButton(
                        selected = work.original == before, onClick = { work.original = before },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                    ) { Text(stringResource(if (before) R.string.stabilize_before else R.string.stabilize_after)) }
                }
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            work.saved?.let { saved ->
                val status = preview.progress?.let { stringResource(R.string.stabilize_applying, Math.round(it * 100).toInt()) } ?: stringResource(R.string.stabilize_applied)
                Text("$status · ${stringResource(saved.title)}", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            // Two by two, as the original's grid (:1297-1301).
            for (row in StabilizationLevel.entries.chunked(2)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (level in row) {
                        val chosen = work.level == level
                        val colors = if (chosen) ButtonDefaults.buttonColors(containerColor = Chosen, contentColor = Color.Black)
                        else ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f), contentColor = Color.White)
                        Button(
                            onClick = { if (work.level != level) { work.level = level; work.stopped = false } },
                            colors = colors, shape = RoundedCornerShape(11.dp),
                            modifier = Modifier.weight(1f).heightIn(min = 46.dp).semantics { selected = chosen },
                        ) { Text(stringResource(level.title), maxLines = 1, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
            val plan = work.plan
            if (plan != null && plan.trackingFraction < 0.65) {
                Text(stringResource(R.string.stabilize_tracking_warning), color = Color(0xFFFF9F0A), fontSize = 12.sp)
            }
            Text(stringResource(work.level.explanation), color = Soft, fontSize = 14.sp)
            val measuring = work.progress
            when {
                measuring != null -> {
                    Text(stringResource(R.string.stabilize_preparing, EffectPreviewWindow.seconds(work.window)), color = Color.White, fontSize = 12.sp)
                    val name = stringResource(R.string.stabilize_progress)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(progress = { measuring.toFloat() }, modifier = Modifier.weight(1f).semantics { contentDescription = name })
                        TextButton(onClick = { work.stopped = true }) { Text(stringResource(R.string.stabilize_stop)) }
                    }
                }
                work.stopped -> OutlinedButton(onClick = { work.stopped = false }) { Text(stringResource(R.string.stabilize_resume)) }
                work.failed -> {
                    Text(stringResource(R.string.stabilize_failed), color = Color(0xFFFF9F0A), fontSize = 12.sp)
                    OutlinedButton(onClick = { work.attempt++ }) { Text(stringResource(R.string.stabilize_retry)) }
                }
                // The crop the level needs, as the zoom that hides the edges (:1304-1306).
                work.level == StabilizationLevel.Off -> Text(stringResource(R.string.stabilize_original_framing), color = Soft, fontSize = 12.sp)
                plan != null -> Text(stringResource(R.string.stabilize_crop_zoom, String.format(Locale.getDefault(), "%.2f", plan.zoom)), color = Soft, fontSize = 12.sp)
            }
            if (work.ranged) Text(stringResource(R.string.stabilize_range_edges), color = Soft, fontSize = 12.sp)
        }
    }
}

/** The level's name (YouRec/StabilizationPath.swift:7). */
private val StabilizationLevel.title: Int
    get() = when (this) {
        StabilizationLevel.Off -> R.string.stabilize_off
        StabilizationLevel.Light -> R.string.stabilize_light
        StabilizationLevel.Standard -> R.string.stabilize_standard
        StabilizationLevel.Strong -> R.string.stabilize_strong
    }

/** What the level does (YouRec/StabilizationPath.swift:8-15). */
private val StabilizationLevel.explanation: Int
    get() = when (this) {
        StabilizationLevel.Off -> R.string.stabilize_off_detail
        StabilizationLevel.Light -> R.string.stabilize_light_detail
        StabilizationLevel.Standard -> R.string.stabilize_standard_detail
        StabilizationLevel.Strong -> R.string.stabilize_strong_detail
    }

/**
 * The measuring of the stabilised clips while the picture already plays, over the preview, as the original says it
 * while it applies stabilisation (YouRec/EditorController.swift:107-111, 525-526).
 */
@Composable
internal fun StabilizationStatus(preview: PreviewStabilization, modifier: Modifier = Modifier) {
    val progress = preview.progress ?: return
    Box(modifier.padding(8.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(stringResource(R.string.stabilize_applying, Math.round(progress * 100).toInt()), color = Color.White, fontSize = 12.sp)
    }
}
