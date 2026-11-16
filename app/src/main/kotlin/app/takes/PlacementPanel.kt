package app.takes

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takes.core.EditState
import app.takes.core.PlacementDraft
import app.takes.core.PlacementEditing
import app.takes.core.PlacementLayout
import app.takes.core.PlacementRendering
import app.takes.core.PlacementSpot
import app.takes.core.Point
import app.takes.core.RenderPlan
import app.takes.core.Size
import app.takes.core.TrackEditState
import app.takes.core.TrackTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private val Soft = Color(0xFFA0A4AB)
internal val PlacementColor = Color(0xFFBF5AF2)

/** The Placement tool while open: the draft, and the stretch and timeline it was opened on. */
internal data class Placing(val draft: PlacementDraft, val spot: PlacementSpot, val timeline: TrackTimeline)

/** The picture named as the original names it: V2 · front. [number] counts the clips from 1. */
@Composable
private fun videoLabel(draft: PlacementDraft, id: String, number: Int): String {
    val rank = draft.depth(id)
    val count = draft.members.size
    val depth = when (rank) {
        count - 1 -> stringResource(R.string.placement_front)
        0 -> stringResource(R.string.placement_back)
        else -> stringResource(R.string.placement_middle, rank + 1, count)
    }
    return "V$number · $depth"
}

/**
 * The controls of the Placement tool, in place of the tools, as the
 * original's `PlacementEditor` (YouRec/TrackEditors.swift:440-841) without
 * the position slider, edge crop, reframing and motion: which picture, the
 * ready-made layouts, front and back, Fit or Fill, centering, and Apply,
 * Cancel and Reset. The windows are dragged and pinched on the preview
 * ([PlacementOverlay]).
 */
@Composable
internal fun PlacementPanel(
    work: Placing, edit: EditState, change: (PlacementDraft) -> Unit,
    apply: () -> Unit, remove: () -> Unit, cancel: () -> Unit, modifier: Modifier = Modifier,
) {
    val draft = work.draft
    val numbers = edit.clips.withIndex().associate { it.value.id to it.index + 1 }
    Column(modifier.fillMaxWidth().background(Color(0xFF1B1D21))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = cancel) { Text(stringResource(R.string.common_cancel)) }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.placement_title), color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                val title = draft.members.joinToString(" + ") { "V${numbers[it] ?: 0}" } + " · " +
                    preciseLabel(work.spot.interval.start.seconds) + "–" + preciseLabel(work.spot.interval.end.seconds)
                Text(title, color = Soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = apply, enabled = draft.members.size > 1) { Text(stringResource(R.string.placement_apply), fontWeight = FontWeight.Bold) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.placement_hint), color = Soft, fontSize = 12.sp)
            // The picture the buttons and the gestures work on; one under another is picked here.
            val videoLabelText = stringResource(R.string.placement_video)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).semantics { contentDescription = videoLabelText }, Arrangement.spacedBy(6.dp), Alignment.CenterVertically) {
                for (id in draft.order.asReversed()) {
                    FilterChip(selected = id == draft.selected, onClick = { change(draft.selecting(id, explicit = true)) }, label = { Text(videoLabel(draft, id, numbers[id] ?: 0), maxLines = 1) })
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                var layouts by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { layouts = true }) { Text(stringResource(R.string.placement_layout)) }
                    DropdownMenu(layouts, onDismissRequest = { layouts = false }) {
                        Text(stringResource(R.string.placement_layout_note), color = Soft, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        for ((kind, label) in listOf(
                            PlacementLayout.Cover to R.string.placement_cover, PlacementLayout.SideBySide to R.string.placement_side_by_side,
                            PlacementLayout.TopAndBottom to R.string.placement_top_bottom, PlacementLayout.PictureInPicture to R.string.placement_pip,
                        )) DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { layouts = false; change(draft.laidOut(kind)) })
                    }
                }
                TextButton(onClick = { change(draft.broughtToFront()) }) { Text(stringResource(R.string.placement_bring_front), maxLines = 1) }
                TextButton(onClick = { change(draft.sentToBack()) }) { Text(stringResource(R.string.placement_send_back), maxLines = 1) }
            }
            draft.current?.let { value ->
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.placement_fill), color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(value.fill, onCheckedChange = { change(draft.filling(it)) })
                }
                TextButton(onClick = { change(draft.centered()) }) { Text(stringResource(R.string.placement_center)) }
            }
            if (draft.replacesTransition(work.timeline)) Text(stringResource(R.string.placement_replaces_transition), color = Color(0xFFFF9F0A), fontSize = 12.sp)
            Text(stringResource(R.string.placement_audio_note), color = Soft, fontSize = 12.sp, lineHeight = 16.sp)
            TextButton(onClick = remove, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(stringResource(R.string.placement_reset), color = RecordRed) }
        }
    }
}

/** Frames of the pictures being placed, larger than the timeline's thumbnails and at the exact time. */
private object PlacementStills {
    private val cache = LruCache<String, Bitmap>(8)
    private val decoding = Dispatchers.IO.limitedParallelism(2)

    suspend fun frame(file: File, time: Double): Bitmap? {
        val key = file.path + "@" + (time * 30).roundToInt()
        cache.get(key)?.let { return it }
        return withContext(decoding) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                retriever.getFrameAtTime((time * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)?.let { frame ->
                    val scale = 720f / maxOf(frame.width, frame.height)
                    if (scale < 1) Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true) else frame
                }?.also { cache.put(key, it) }
            } catch (_: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }
}

/**
 * Over the preview while Placement is open: the pictures as they will be
 * placed, drawn with the export's numbers from frames at the playhead
 * (`PlacementRendering.placed`, as the original's still surface), the
 * selected window outlined. A drag moves the window under the finger, a
 * pinch resizes it, a tap selects the picture in front there. [settle] is
 * called when the fingers lift, for the preview to show the draft.
 */
@Composable
internal fun PlacementOverlay(
    work: Placing, edit: EditState, files: Map<String, File>, playhead: Double,
    change: (PlacementDraft) -> Unit, settle: () -> Unit, modifier: Modifier = Modifier,
) {
    val draft = work.draft
    val stills by produceState(emptyMap<String, Bitmap>(), draft.members, edit, (playhead * 30).roundToInt()) {
        val result = HashMap<String, Bitmap>()
        for (id in draft.members) {
            val index = edit.clips.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: continue
            val clip = edit.clips[index]
            val start = TrackEditState.clipStart(edit, index) ?: continue
            val offset = (playhead - start).coerceIn(0.0, maxOf(0.0, clip.duration - 1.0 / 30))
            val file = files[clip.sourceID] ?: continue
            PlacementStills.frame(file, clip.sourceTime(offset))?.let { result[id] = it }
        }
        value = result
    }
    val latest by rememberUpdatedState(draft)
    val onChange by rememberUpdatedState(change)
    val onSettle by rememberUpdatedState(settle)
    Canvas(
        modifier.fillMaxSize().pointerInput(Unit) {
            val touch = 44.dp.toPx().toDouble()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val canvas = Size(size.width.toDouble(), size.height.toDouble())
                val first = latest
                val target = PlacementEditing.target(first.members, first.region.videos, first.selected, first.explicitPick, Point(down.position.x.toDouble(), down.position.y.toDouble()), canvas, touch = touch)
                var working = if (target != null && target != first.selected) first.selecting(target) else first
                if (working !== first) onChange(working)
                val start = target?.let { working.region.videos[it] }
                var pan = Offset.Zero
                var zoom = 1f
                var pinching = false
                var moved = false
                do {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.count { it.pressed }
                    if (pressed >= 2 && !pinching) { pinching = true; zoom = 1f }
                    pan += event.calculatePan()
                    if (pressed >= 2) zoom *= event.calculateZoom()
                    if (pan.getDistance() > viewConfiguration.touchSlop || zoom != 1f) moved = true
                    if (moved && start != null && target != null) {
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                        val translation = Point(pan.x.toDouble(), pan.y.toDouble())
                        working = working.changing(target) { now ->
                            val dragged = PlacementEditing.moved(start, now, translation, canvas, touch)
                            if (pinching) PlacementEditing.resized(start, dragged, zoom.toDouble(), canvas, touch) else dragged
                        }
                        onChange(working)
                    }
                    if (event.calculateCentroid() == Offset.Unspecified) break
                } while (event.changes.any { it.pressed })
                // A tap on a picture under the selected one, away from it, picks the one in front there.
                if (!moved) PlacementEditing.hit(first.members, first.region.videos, Point(down.position.x.toDouble(), down.position.y.toDouble()), canvas)?.let { hit ->
                    if (hit != working.selected) onChange(working.selecting(hit))
                } else onSettle()
            }
        },
    ) {
        val canvas = Size(size.width.toDouble(), size.height.toDouble())
        drawRect(Color.Black)
        drawIntoCanvas { target ->
            val native = target.nativeCanvas
            for (id in draft.order) {
                val value = draft.region.videos[id] ?: continue
                val image = stills[id] ?: continue
                val placed = PlacementRendering.placed(value, canvas)
                if (placed.clip.width <= 0 || placed.clip.height <= 0) continue
                // The picture as it would fill the canvas on its own, then placed.
                val content = RenderPlan.content(canvas, Size(image.width.toDouble(), image.height.toDouble()), cropped = false)
                val transform = placed.transform
                val matrix = Matrix().apply {
                    setValues(floatArrayOf(transform.a.toFloat(), transform.c.toFloat(), transform.tx.toFloat(), transform.b.toFloat(), transform.d.toFloat(), transform.ty.toFloat(), 0f, 0f, 1f))
                }
                native.save()
                native.clipRect(placed.clip.minX.toFloat(), placed.clip.minY.toFloat(), placed.clip.maxX.toFloat(), placed.clip.maxY.toFloat())
                native.concat(matrix)
                native.drawBitmap(image, null, android.graphics.RectF(content.minX.toFloat(), content.minY.toFloat(), content.maxX.toFloat(), content.maxY.toFloat()), null)
                native.restore()
            }
        }
        draft.current?.let { value ->
            val window = value.visibleWindow(canvas)
            drawRect(
                PlacementColor, Offset(window.minX.toFloat(), window.minY.toFloat()),
                androidx.compose.ui.geometry.Size(window.width.toFloat(), window.height.toFloat()), style = Stroke(2.dp.toPx()),
            )
        }
    }
}
