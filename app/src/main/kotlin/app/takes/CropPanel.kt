package app.takes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
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
import app.takes.core.CropDraft
import app.takes.core.CropGestures
import app.takes.core.CropSettings
import app.takes.core.CropViewport
import app.takes.core.EditState
import app.takes.core.Point
import app.takes.core.RangeEditing
import app.takes.core.Size as FrameSize
import app.takes.core.SpeedMapping
import app.takes.core.TimelineClip
import app.takes.core.TimelineEditing
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val Soft = Color(0xFFA0A4AB)
private val Plain = Color.White.copy(alpha = 0.09f)

/**
 * What the Crop tool was opened on. [base] is the edit as it was then and
 * [scoped] the marked range or the selection, null for the clip under the
 * playhead; [state] is the edit with the clips cut at the ends of a range, as
 * applying will leave it, and [pieces] the clips of it the framing goes on,
 * each with the timeline second it begins at.
 */
internal class CropScope(val base: EditState, val scoped: RangeEditing.Scoped?, chosen: Set<String>) {
    data class Piece(val clip: TimelineClip, val start: Double)

    val state: EditState = scoped?.state ?: base

    /** Every clip of [state] with where it begins: the pieces and the footage around them. */
    val all: List<Piece> = state.clips.indices.map { Piece(state.clips[it], TimelineEditing.clipStart(state.clips, it)) }
    val pieces: List<Piece> = all.filter { it.clip.id in chosen }
    val ids: Set<String> = pieces.map { it.clip.id }.toSet()

    /** The stretch of the movie the tool works on, from the first of its clips to the end of the last. */
    val start: Double = pieces.minOfOrNull { it.start } ?: 0.0
    val end: Double = pieces.maxOfOrNull { it.start + it.clip.duration } ?: start
    val duration: Double get() = end - start

    /** The clip of the tool at timeline second [time], or the nearest one: the picture the controls measure against. */
    fun active(time: Double): Piece? = pieces.minByOrNull { maxOf(0.0, it.start - time, time - it.start - it.clip.duration + 0.000001) }
}

/**
 * The Crop tool while it is open: the framing being worked on, [draft], and
 * the clips it will be put on, [on]. Nothing is saved until it is applied.
 */
internal data class Cropping(val draft: CropDraft, val on: CropScope) {
    /**
     * The crop to draw on [clip], a clip of the edit as it is now, where it
     * shows source second [sourceTime] at timeline second [time]: on the
     * tool's clips the framing being worked on, elsewhere the clip's own.
     * A range cuts clips, so the piece is found by the footage it shows.
     */
    fun shown(clip: TimelineClip, sourceTime: Double, time: Double): CropSettings? {
        val piece = on.all.filter { it.clip.sourceID == clip.sourceID && sourceTime >= it.clip.start - 0.000001 && sourceTime <= it.clip.end + 0.000001 }
            // Of two pieces that meet at this frame, the one that begins with it shows it.
            .minWithOrNull(compareBy<CropScope.Piece> { abs(it.start + it.clip.offset(sourceTime) - time) }.thenBy { sourceTime - it.clip.start })
            ?: return clip.crop
        if (piece.clip.id !in on.ids) return piece.clip.crop
        // Until something is changed every clip shows what it has.
        if (!draft.framingChanged && !draft.animationChanged) return piece.clip.crop
        return draft.displayed(sourceTime, SpeedMapping.spans(piece.clip, piece.clip.speed), piece.start - on.start, piece.clip.crop?.motion)
    }

    /**
     * The edit with the framing on every clip of the tool, or with their
     * crops taken off when [removing]. Null when that changes no clip: the
     * cuts at the ends of a range are then not made either. [sizes] has the
     * upright size of each recording and [canvas] the movie's frame.
     */
    fun applied(sizes: Map<String, FrameSize>, canvas: FrameSize, removing: Boolean = false): EditState? {
        var next = on.state
        var changed = false
        for (piece in on.pieces) {
            val index = next.clips.indexOfFirst { it.id == piece.clip.id }
            val clip = next.clips.getOrNull(index) ?: continue
            val crop = draft.applied(clip.crop, SpeedMapping.spans(clip, clip.speed), piece.start - on.start, sizes[clip.sourceID], canvas, removing)
            TimelineEditing.setCrop(next, index, crop)?.let { next = it; changed = true }
        }
        return next.takeIf { changed }
    }

    companion object {
        /**
         * The tool opened on a range or a selection, or with [scoped] null on
         * the clip [subject] of [edit]. The controls start from the crop of
         * [subject], the clip whose picture is shown.
         */
        fun opening(edit: EditState, scoped: RangeEditing.Scoped?, subject: TimelineClip): Cropping =
            Cropping(CropDraft.opening(subject.crop), CropScope(edit, scoped, scoped?.clips?.toSet() ?: setOf(subject.id)))
    }
}

/**
 * The original's touches on the picture while framing: one finger drags the
 * footage under the frame once it has travelled [CropGestures.PAN_SLOP], two
 * fingers zoom around the point between them and move it. Lifting or adding
 * a finger begins again from the framing as it is, so the picture never jumps.
 *
 * [viewport] is the picture area in the pixels of this view, null while the
 * sizes are not known; [crop] the framing now and [change] takes the next one.
 */
internal suspend fun PointerInputScope.cropGestures(viewport: () -> CropViewport?, crop: () -> CropSettings, change: (CropSettings) -> Unit) {
    val slop = CropGestures.PAN_SLOP * density
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var fingers = 0
        var pinch: CropGestures.Pinch? = null
        var dragged: CropSettings? = null
        var origin = Offset.Zero
        do {
            val event = awaitPointerEvent()
            val down = event.changes.filter { it.pressed }
            val port = viewport()
            if (port != null && down.size >= 2) {
                val focus = Offset(down.sumOf { it.position.x.toDouble() }.toFloat() / down.size, down.sumOf { it.position.y.toDouble() }.toFloat() / down.size)
                // How far apart the fingers are, as the mean distance from the point between them.
                val spread = down.sumOf { hypot((it.position.x - focus.x).toDouble(), (it.position.y - focus.y).toDouble()) } / down.size
                val began = pinch?.takeIf { fingers == down.size } ?: CropGestures.pinchBegan(crop(), Point(focus.x.toDouble(), focus.y.toDouble()), spread, port)
                pinch = began
                dragged = null
                // The original has no twist: two fingers never turn the picture.
                change(CropGestures.pinchChanged(began, Point(focus.x.toDouble(), focus.y.toDouble()), spread, port, twist = 0.0))
            } else if (port != null && down.size == 1) {
                val at = down[0].position
                val from = dragged?.takeIf { fingers == 1 } ?: crop().also { origin = at }
                dragged = from
                pinch = null
                CropGestures.panChanged(from, Point((at.x - origin.x).toDouble(), (at.y - origin.y).toDouble()), port, slop)?.let(change)
            }
            fingers = down.size
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/** The frame's edge and its thirds, drawn over the picture while framing as the original draws them. */
internal fun DrawScope.cropGuides() {
    val thin = 0.5.dp.toPx()
    val line = Color.White.copy(alpha = 0.3f)
    for (third in 1..2) {
        drawLine(line, Offset(size.width * third / 3, 0f), Offset(size.width * third / 3, size.height), thin)
        drawLine(line, Offset(0f, size.height * third / 3), Offset(size.width, size.height * third / 3), thin)
    }
    drawRect(Color.White, style = Stroke(1.5.dp.toPx()))
}

/** An angle as the original writes it: whole when it is, else with one decimal. */
private fun degrees(value: Double): String {
    val tenths = (value * 10).roundToLong()
    return if (tenths % 10 == 0L) "${tenths / 10}°" else String.format(Locale.getDefault(), "%.1f°", tenths / 10.0)
}

/**
 * The Crop tool, in place of the tools under the timeline, so the picture
 * stays in view and follows the controls. A crop fills the movie's frame
 * with part of the clip: zoomed, turned and moved.
 *
 * @param work the tool's state; [change] takes the framing after every control.
 * @param source the upright size of the clip whose picture is shown, and [canvas] the movie's frame; null while not known.
 * @param whole whether the tool works on the whole movie, so its framing has no edge to change at.
 * @param apply saves the framing, or with true takes the crop off the clips.
 */
@Composable
internal fun CropPanel(
    work: Cropping, source: FrameSize?, canvas: FrameSize?, whole: Boolean, change: (CropDraft) -> Unit,
    apply: (removing: Boolean) -> Unit, cancel: () -> Unit, modifier: Modifier = Modifier, appliesTo: String? = null,
) {
    val draft = work.draft
    val crop = draft.crop
    val ready = source != null && canvas != null
    val adjust = { next: CropSettings -> if (ready) change(draft.adjusted(next)) }
    var showPosition by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth().background(Color(0xFF1B1D21))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = cancel) { Text(stringResource(R.string.common_cancel)) }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.editor_crop), color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                appliesTo?.let { Text(it, color = Soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (work.on.pieces.any { it.clip.crop != null }) {
                val remove = stringResource(R.string.crop_remove)
                TextButton(onClick = { apply(true) }, modifier = Modifier.semantics { contentDescription = remove }) { Icon(Icons.Outlined.Delete, null, tint = Color.White.copy(alpha = 0.7f)) }
            }
            TextButton(onClick = { apply(false) }, enabled = ready) { Text(stringResource(R.string.crop_apply), fontWeight = FontWeight.Bold) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.crop_hint), color = Color.White, fontSize = 14.sp)
            // Rotation: the slider, the angle, which a tap sets upright again, and a quarter turn.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.crop_rotation), color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                val reset = stringResource(R.string.crop_rotation_reset)
                val angle = degrees(crop.rotation)
                TextButton(onClick = { adjust(CropGestures.rotated(crop, 0.0)) }, enabled = ready,
                    modifier = Modifier.defaultMinSize(minWidth = 52.dp).semantics { contentDescription = reset; stateDescription = angle }) { Text(angle) }
                val turn = stringResource(R.string.crop_rotate_90)
                TextButton(onClick = { adjust(CropGestures.quarterTurn(crop)) }, enabled = ready, modifier = Modifier.semantics { contentDescription = turn }) {
                    Icon(Icons.Filled.Rotate90DegreesCw, null)
                }
            }
            val rotation = stringResource(R.string.crop_rotation_slider)
            Slider(
                value = crop.rotation.toFloat(), onValueChange = { adjust(CropGestures.rotated(crop, (it / CropGestures.ROTATION_STEP).roundToInt() * CropGestures.ROTATION_STEP)) },
                valueRange = -180f..180f, enabled = ready, modifier = Modifier.height(36.dp).semantics { contentDescription = rotation },
            )
            Text(stringResource(R.string.crop_rotation_note), color = Soft, fontSize = 12.sp, lineHeight = 16.sp)
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.crop_zoom), color = Color.White, fontSize = 14.sp)
                val zoom = stringResource(R.string.crop_zoom_slider)
                Slider(
                    value = crop.zoom.toFloat(), onValueChange = { adjust(CropGestures.zoomed(crop, it.toDouble())) },
                    valueRange = CropSettings.MINIMUM_ZOOM.toFloat()..CropSettings.MAXIMUM_ZOOM.toFloat(), enabled = ready,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp).height(36.dp).semantics { contentDescription = zoom },
                )
                Text(String.format(Locale.getDefault(), "%.1f×", crop.zoom), color = Color.White, fontSize = 14.sp, maxLines = 1)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { adjust(CropGestures.reset(crop)) }, enabled = ready) { Text(stringResource(R.string.crop_reset_framing)) }
                Box(Modifier.weight(1f))
                if (source != null && canvas != null) {
                    val area = crop.rect(source, canvas)
                    val width = Math.round(area.width).toInt()
                    val height = Math.round(area.height).toInt()
                    val spoken = stringResource(R.string.crop_source_area, width, height)
                    Text("$width × $height px", color = Soft, fontSize = 12.sp, maxLines = 1, modifier = Modifier.semantics { contentDescription = spoken })
                }
            }
            // Position, closed until asked for: the picture itself is dragged.
            val position = stringResource(R.string.crop_position)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).clickable { showPosition = !showPosition }.semantics { role = Role.Button },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(position, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Icon(if (showPosition) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Soft)
            }
            if (showPosition && source != null && canvas != null) {
                val visible = crop.clamped(source, canvas)
                for (across in listOf(true, false)) {
                    val name = stringResource(if (across) R.string.crop_across else R.string.crop_down)
                    val spoken = stringResource(if (across) R.string.crop_across_slider else R.string.crop_down_slider)
                    val reach = CropGestures.positionRange(crop, across, source, canvas)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, color = Color.White, fontSize = 14.sp, modifier = Modifier.width(88.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Slider(
                            value = (if (across) visible.x else visible.y).toFloat().coerceIn(reach.start.toFloat(), reach.endInclusive.toFloat()),
                            onValueChange = {
                                adjust(if (across) CropGestures.moved(crop, x = it.toDouble(), source = source, canvas = canvas) else CropGestures.moved(crop, y = it.toDouble(), source = source, canvas = canvas))
                            },
                            valueRange = reach.start.toFloat()..reach.endInclusive.toFloat(),
                            enabled = !CropGestures.positionLocked(crop, across, source, canvas),
                            modifier = Modifier.weight(1f).height(36.dp).semantics { contentDescription = spoken },
                        )
                    }
                }
            }
            if (!whole) Text(stringResource(R.string.crop_edges_note), color = Soft, fontSize = 12.sp, lineHeight = 16.sp)
            if (work.on.pieces.size > 1) Text(stringResource(R.string.crop_clips_note, work.on.pieces.size), color = Soft, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}
