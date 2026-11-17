package app.takes

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import app.takes.core.at
import app.takes.core.matrixValues
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.outlined.Diamond
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import app.takes.core.EffectAnimation
import app.takes.core.EffectCurve
import app.takes.core.EffectEndpoint
import app.takes.core.EffectTiming
import app.takes.core.PauseDetector
import app.takes.core.RampShape
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
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
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
import app.takes.core.CropDraft
import app.takes.core.CropShape
import app.takes.core.MirrorSettings
import app.takes.core.OutputShape
import app.takes.core.CropGestures
import app.takes.core.CropSettings
import app.takes.core.CropViewport
import app.takes.core.EditState
import app.takes.core.Point
import app.takes.core.RangeEditing
import app.takes.core.Size as FrameSize
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

    /** The number of [active] among [pieces], from 0. */
    fun index(time: Double): Int = active(time)?.let { pieces.indexOf(it) }?.coerceAtLeast(0) ?: 0

    /** Where the clip [delta] places after the one at [time] begins: the original's arrows (YouRec/CropEditor.swift:244-248). */
    fun step(time: Double, delta: Int): Double? =
        pieces.getOrNull((index(time) + delta).coerceIn(0, maxOf(0, pieces.size - 1)))?.start

    /** The second of the movie the tool's picture stands at for the playhead at [time]: inside what it works on, not on its last frame. */
    fun position(time: Double): Double = time.coerceIn(start, maxOf(start, end - PauseDetector.FRAME))
}

/**
 * The Crop tool while it is open: the framing being worked on, [draft], and
 * the clips it will be put on, [on]. Nothing is saved until it is applied.
 *
 * [mirror] is the flip of the tool's clips, put on all of them once
 * [mirrorChanged]. [aspect] is the shape of the movie's frame the tool frames
 * into, null for the first picture's own, and [shape] the one chosen in the
 * menu since the tool was opened (YouRec/CropEditor.swift:92-93, :189-194).
 */
internal data class Cropping(
    val draft: CropDraft, val on: CropScope,
    val mirror: MirrorSettings = MirrorSettings(), val mirrorChanged: Boolean = false,
    val aspect: Double? = on.base.outputSize?.let { it.width / it.height }, val shape: OutputShape? = null,
    /** Preview, the movie as it will play with every tool, rather than Frame, the footage under the frame (YouRec/CropEditor.swift:78, :385-391). */
    val reviewing: Boolean = false,
) {
    /** The piece of the tool's edit that shows source second [sourceTime] of [clip]'s footage at timeline second [time]. */
    private fun piece(clip: TimelineClip, sourceTime: Double, time: Double): CropScope.Piece? =
        on.all.filter { it.clip.sourceID == clip.sourceID && sourceTime >= it.clip.start - 0.000001 && sourceTime <= it.clip.end + 0.000001 }
            // Of two pieces that meet at this frame, the one that begins with it shows it.
            .minWithOrNull(compareBy<CropScope.Piece> { abs(it.start + it.clip.offset(sourceTime) - time) }.thenBy { sourceTime - it.clip.start })

    /** The flip to draw on [clip] where it shows [sourceTime] at [time]: on the tool's clips the one being worked on once changed. */
    fun mirrorShown(clip: TimelineClip, sourceTime: Double, time: Double): MirrorSettings? {
        val piece = piece(clip, sourceTime, time) ?: return clip.mirror
        return if (mirrorChanged && piece.clip.id in on.ids) mirror.takeIf { it.horizontal || it.vertical } else piece.clip.mirror
    }

    /** The flip of the clip the controls measure against at [time], for the touches on the picture. */
    fun activeMirror(time: Double): MirrorSettings? =
        if (mirrorChanged) mirror.takeIf { it.horizontal || it.vertical } else on.active(time)?.clip?.mirror

    /** The movie's frame while the tool is open, from [original], the first picture's own shape. */
    fun canvas(original: FrameSize): FrameSize = CropShape.canvas(aspect, original, on.base.outputSize)

    /** The name of the shape in the menu: the chosen one, or the one the frame has, or none of them. */
    val shapeChoice: OutputShape?
        get() = shape ?: if (aspect == null) OutputShape.Original else OutputShape.entries.firstOrNull { shape -> shape.aspect?.let { abs(it - aspect) < 0.001 } == true }

    fun reshaped(chosen: OutputShape): Cropping = copy(shape = chosen, aspect = chosen.aspect, reviewing = false)

    fun flipped(next: MirrorSettings): Cropping = copy(mirror = next, mirrorChanged = true)

    /**
     * The crop to draw on [clip], a clip of the edit as it is now, where it
     * shows source second [sourceTime] at timeline second [time]: on the
     * tool's clips the framing being worked on, elsewhere the clip's own.
     * A range cuts clips, so the piece is found by the footage it shows.
     */
    fun shown(clip: TimelineClip, sourceTime: Double, time: Double): CropSettings? {
        val piece = piece(clip, sourceTime, time) ?: return clip.crop
        if (piece.clip.id !in on.ids) return piece.clip.crop
        // Until something is changed every clip shows what it has; one without a crop fills the frame, as applying leaves it.
        if (!draft.framingChanged && !draft.animationChanged) return piece.clip.crop ?: CropSettings(id = draft.crop.id)
        return draft.displayed(sourceTime, piece.clip.playbackSpans, piece.start - on.start, piece.clip.crop?.motion)
    }

    /** What applying leaves: the edit, and the fixed size of the movie's frame, null for the first picture's shape. */
    data class Applied(val state: EditState, val outputSize: FrameSize?)

    /**
     * The edit with the framing and the flip on every clip of the tool, or
     * with their crops taken off when [removing], and the movie's frame after
     * it as the original decides it (YouRec/CropEditor.swift:212-237): a crop
     * or a chosen shape pins the frame, "Original" with no crop left frees it.
     * When no clip changes, the cuts at the ends of a range are not made
     * either. [sizes] has the upright size of each recording and [original]
     * the first picture's.
     */
    fun applied(sizes: Map<String, FrameSize>, original: FrameSize, removing: Boolean = false): Applied {
        val canvas = canvas(original)
        var next = on.state
        var cropped = false
        var flipped = false
        for (piece in on.pieces) {
            val index = next.clips.indexOfFirst { it.id == piece.clip.id }
            val clip = next.clips.getOrNull(index) ?: continue
            val crop = draft.applied(clip.crop, clip.playbackSpans, piece.start - on.start, sizes[clip.sourceID], canvas, removing)
            TimelineEditing.setCrop(next, index, crop)?.let { next = it; cropped = true }
            if (!removing && mirrorChanged) TimelineEditing.setMirror(next, index, mirror)?.let { next = it; flipped = true }
        }
        if (!cropped && !flipped) next = on.base
        val pinned = on.base.outputSize
        if (removing) return Applied(next, pinned)
        return Applied(next, CropShape.outputSize(shape, next.clips.any { it.crop != null }, cropped, pinned, canvas))
    }

    /**
     * Where Play starts from with the playhead at [time]: the start of what
     * the tool works on while an end of the motion is being edited, or when
     * the playhead is outside it or at its end; else where it is.
     */
    fun playFrom(time: Double): Double =
        if (draft.endpoint != null || time < on.start || time >= on.end - 0.05) on.start else time

    /** The timeline second the picture stands at while parked on an end of the motion; null when it is not parked. */
    fun parkedAt(time: Double): Double? =
        if (draft.previewEndpoint == null || draft.animation == null) null else on.start + draft.previewPosition(time - on.start, on.duration)

    companion object {
        /**
         * The tool opened on a range or a selection, or with [scoped] null on
         * the clip [subject] of [edit]. The controls start from the crop of
         * [subject], the clip whose picture is shown.
         */
        fun opening(edit: EditState, scoped: RangeEditing.Scoped?, subject: TimelineClip): Cropping =
            Cropping(CropDraft.opening(subject.crop), CropScope(edit, scoped, scoped?.clips?.toSet() ?: setOf(subject.id)), mirror = subject.mirror ?: MirrorSettings())
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

/** An angle as the original writes it: whole when it is, else with one decimal. */
private fun degrees(value: Double): String {
    val tenths = (value * 10).roundToLong()
    return if (tenths % 10 == 0L) "${tenths / 10}°" else String.format(Locale.getDefault(), "%.1f°", tenths / 10.0)
}

/**
 * The Crop tool, a workspace of its own as the original's (YouRec/CropEditor.swift:331-480): its header
 * ([headerModifier]), the picture, the switch between Frame and Preview with Play, the arrows between the
 * clips and the position ([playbackModifier]), and the settings ([modifier]). A crop fills the movie's frame
 * with part of the clip: zoomed, turned and moved.
 *
 * @param work the tool's state; [change] takes the framing after every control.
 * @param source the upright size of the clip whose picture is shown, and [canvas] the movie's frame; null while not known.
 * @param whole whether the tool works on the whole movie, so its framing has no edge to change at.
 * @param apply saves the framing, or with true takes the crop off the clips.
 * @param reshape takes the shape chosen for the movie's frame, and [flip] the clips' flip.
 * @param playhead the second of the movie the picture stands at, read only where it is shown; [playing] whether it plays.
 * @param review switches between Frame and Preview, [togglePlay] plays or pauses, [seek] moves the picture to a second of the movie.
 */
@Composable
internal fun CropPanel(
    work: Cropping, source: FrameSize?, canvas: FrameSize?, whole: Boolean, change: (CropDraft) -> Unit,
    apply: (removing: Boolean) -> Unit, cancel: () -> Unit, modifier: Modifier = Modifier, appliesTo: String? = null,
    reshape: (OutputShape) -> Unit = {}, flip: (MirrorSettings) -> Unit = {},
    headerModifier: Modifier = Modifier, playbackModifier: Modifier = Modifier,
    playhead: () -> Double = { 0.0 }, playing: Boolean = false,
    review: (Boolean) -> Unit = {}, togglePlay: () -> Unit = {}, seek: (Double) -> Unit = {},
) {
    val draft = work.draft
    val crop = draft.crop
    val ready = source != null && canvas != null
    val adjust = { next: CropSettings -> if (ready) change(draft.adjusted(next)) }
    var showPosition by remember { mutableStateOf(false) }

    Row(headerModifier.fillMaxWidth().background(Color.Black).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = cancel, modifier = Modifier.defaultMinSize(minWidth = 60.dp, minHeight = 44.dp)) { Text(stringResource(R.string.common_cancel)) }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.editor_crop), color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
            appliesTo?.let { Text(it, color = Soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (work.on.pieces.any { it.clip.crop != null }) {
            val remove = stringResource(R.string.crop_remove)
            TextButton(onClick = { apply(true) }, modifier = Modifier.semantics { contentDescription = remove }) { Icon(Icons.Outlined.Delete, null, tint = Color.White.copy(alpha = 0.7f)) }
        }
        TextButton(onClick = { apply(false) }, enabled = ready, modifier = Modifier.defaultMinSize(minWidth = 60.dp, minHeight = 44.dp)) { Text(stringResource(R.string.crop_apply), fontWeight = FontWeight.Bold) }
    }
    CropPlayback(work, playhead, playing, playbackModifier, review, togglePlay, seek)
    Column(modifier.fillMaxWidth().background(Color(0xFF141414))) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // The move between two framings. An end that is chosen is what the controls below change, on the frame.
            AnimationControls(
                draft.animation, draft.endpoint, work.on.duration,
                select = { review(false); change(draft.selecting(it, work.on.duration)) }, remove = { review(false); change(draft.removingAnimation()) },
                done = { change(draft.finishing()) }, update = { change(draft.retimed(it, work.on.duration)) },
            )
            // The shape of the whole movie, as the original's menu in its Crop tool (YouRec/CropEditor.swift:418-436).
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AspectRatio, null, tint = Color.White, modifier = Modifier.padding(end = 8.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.crop_shape), color = Color.White, fontSize = 14.sp)
                    Text(stringResource(R.string.crop_shape_whole), color = Soft, fontSize = 12.sp)
                }
                val shown = work.shapeChoice?.let { stringResource(it.label) } ?: stringResource(R.string.crop_shape_custom)
                if (ready) {
                    Capsule(shown, stringResource(R.string.crop_shape) + ": " + shown, trailing = Icons.Filled.ExpandMore) { close ->
                        for (choice in OutputShape.entries) {
                            DropdownMenuItem({ Text(stringResource(choice.label)) }, { close(); reshape(choice) }, modifier = Modifier.semantics { selected = choice == work.shapeChoice })
                        }
                    }
                } else Text(shown, color = Soft, fontSize = 14.sp)
            }
            Text(stringResource(if (work.reviewing) R.string.crop_preview_note else R.string.crop_hint), color = Color.White, fontSize = 14.sp)
            // On the frame only, as the original's (YouRec/CropEditor.swift:437-471): the preview shows the result.
            if (!work.reviewing) {
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
                // The size goes under the button where the two do not fit side by side.
                FlowRow(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    TextButton(onClick = { adjust(CropGestures.reset(crop)) }, enabled = ready) { Text(stringResource(R.string.crop_reset_framing)) }
                    if (source != null && canvas != null) {
                        val area = crop.rect(source, canvas)
                        val width = Math.round(area.width).toInt()
                        val height = Math.round(area.height).toInt()
                        val spoken = stringResource(R.string.crop_source_area, width, height)
                        Text("$width × $height px", color = Soft, fontSize = 12.sp, maxLines = 1, modifier = Modifier.align(Alignment.CenterVertically).padding(start = 12.dp).semantics { contentDescription = spoken })
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
                            Text(name, color = Color.White, fontSize = 14.sp, modifier = Modifier.width(104.dp).padding(end = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                // The flips of the original's Mirror tool (YouRec/EditorTools.swift:1319-1322), here beside the framing.
                for (across in listOf(true, false)) {
                    val name = stringResource(if (across) R.string.crop_flip_horizontal else R.string.crop_flip_vertical)
                    val on = if (across) work.mirror.horizontal else work.mirror.vertical
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Switch(on, { flip(if (across) work.mirror.copy(horizontal = it) else work.mirror.copy(vertical = it)) }, modifier = Modifier.semantics { contentDescription = name })
                    }
                }
                Text(stringResource(R.string.crop_flip_note), color = Soft, fontSize = 12.sp, lineHeight = 16.sp)
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Color.White.copy(alpha = 0.15f))
            if (!whole) Text(stringResource(R.string.crop_edges_note), color = Soft, fontSize = 12.sp, lineHeight = 16.sp)
            if (work.on.pieces.size > 1) Text(pluralStringResource(R.plurals.crop_clips_note, work.on.pieces.size, work.on.pieces.size), color = Soft, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

/**
 * Under the picture, as the original's (YouRec/CropEditor.swift:382-409): Frame and Preview, Play while
 * previewing or while an end of the motion is edited, the arrows between the tool's clips with which one is
 * shown, and the position in what the tool works on. Reads [playhead] itself, so a scrub composes only this.
 */
@Composable
private fun CropPlayback(
    work: Cropping, playhead: () -> Double, playing: Boolean, modifier: Modifier,
    review: (Boolean) -> Unit, togglePlay: () -> Unit, seek: (Double) -> Unit,
) {
    val scope = work.on
    val index by remember(scope) { derivedStateOf { scope.index(playhead()) } }
    // By the tenth of a second, as the time is written.
    val at by remember(scope) { derivedStateOf { ((scope.position(playhead()) - scope.start) * 10).roundToLong() / 10.0 } }
    Column(modifier.fillMaxWidth().background(Color.Black).padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            for (result in listOf(false, true)) {
                val chosen = work.reviewing == result
                TextButton(
                    onClick = { review(result) },
                    modifier = Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(50)).background(if (chosen) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                        .semantics { selected = chosen },
                ) { Text(stringResource(if (result) R.string.crop_preview else R.string.crop_frame), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
            }
            Spacer(Modifier.weight(1f))
            if (work.reviewing || work.draft.endpoint != null) {
                val label = stringResource(if (playing) R.string.crop_pause else R.string.crop_play)
                IconButton(onClick = togglePlay, modifier = Modifier.semantics { contentDescription = label }) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White)
                }
            }
            if (scope.pieces.size > 1) {
                val previous = stringResource(R.string.crop_previous_clip)
                IconButton(onClick = { scope.step(playhead(), -1)?.let(seek) }, enabled = index > 0, modifier = Modifier.semantics { contentDescription = previous }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = if (index > 0) Color.White else Soft)
                }
                Text("${index + 1}/${scope.pieces.size}", color = Color.White, fontSize = 12.sp, maxLines = 1)
                val next = stringResource(R.string.crop_next_clip)
                IconButton(onClick = { scope.step(playhead(), 1)?.let(seek) }, enabled = index < scope.pieces.size - 1, modifier = Modifier.semantics { contentDescription = next }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = if (index < scope.pieces.size - 1) Color.White else Soft)
                }
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(preciseLabel(at), color = Color.White, fontSize = 12.sp, maxLines = 1)
            val spoken = stringResource(R.string.crop_position_slider)
            val length = maxOf(PauseDetector.FRAME, scope.duration - PauseDetector.FRAME)
            // The slider follows the picture without composing the panel again at every frame.
            val value by remember(scope) { derivedStateOf { (scope.position(playhead()) - scope.start).toFloat() } }
            Slider(
                value = value.coerceIn(0f, length.toFloat()), onValueChange = { seek(scope.start + it.toDouble()) },
                valueRange = 0f..length.toFloat(), modifier = Modifier.weight(1f).padding(horizontal = 8.dp).height(36.dp).semantics { contentDescription = spoken },
            )
            Text(preciseLabel(scope.duration), color = Soft, fontSize = 12.sp, maxLines = 1)
        }
    }
}

/**
 * The frame being cropped, as the original's crop surface draws it (YouRec/CropEditor.swift:503-640): the
 * footage of the clip the picture stands on at [playhead], a still read from the recording at the second
 * it shows, as large as the movie's frame fits [CropViewport.inset] inside the view, and around the frame
 * the rest of the footage under a dark veil; the frame's edge and its thirds over it. Its color is the
 * clip's ([grade]). One finger drags the footage and two zoom it ([cropGestures]); a screen reader hears
 * the framing and zooms by a step with its actions, as the original's adjustable view.
 *
 * @param files each recording's file by its id, [sizes] its upright size, [canvas] the movie's frame.
 * @param change takes the framing after a gesture.
 */
@Composable
internal fun CropFrameView(
    work: Cropping, playhead: () -> Double, files: Map<String, File>, sizes: Map<String, FrameSize>, canvas: FrameSize?,
    grade: (TimelineClip, Double) -> android.graphics.RenderEffect?, change: (CropSettings) -> Unit, modifier: Modifier = Modifier,
) {
    val scope = work.on
    // The clip shown, the source second of it by the millisecond and the second of the movie: what a scrub changes.
    val shown by remember(scope) {
        derivedStateOf {
            val time = scope.position(playhead())
            scope.active(time)?.let { piece ->
                val offset = (time - piece.start).coerceIn(0.0, maxOf(0.0, piece.clip.duration - PauseDetector.FRAME))
                Triple(piece, (piece.clip.sourceTime(offset) * 1000).roundToLong(), piece.start + offset)
            }
        }
    }
    val piece = shown?.first
    val sourceTime = (shown?.second ?: 0L) / 1000.0
    val time = shown?.third ?: 0.0
    val source = piece?.let { sizes[it.clip.sourceID] }
    val stills = remember { CropStills() }
    DisposableEffect(stills) { onDispose { stills.close() } }
    // The still and the clip it is of, so that a picture is never drawn with another clip's geometry.
    var still by remember { mutableStateOf<Pair<String, Bitmap>?>(null) }
    LaunchedEffect(piece?.clip?.id, shown?.second) {
        val clip = piece?.clip ?: return@LaunchedEffect
        val file = files[clip.sourceID] ?: return@LaunchedEffect
        delay(80) // as the original waits before asking for a frame (YouRec/CropEditor.swift:256)
        stills.frame(file, sourceTime)?.let { still = clip.id to it }
    }
    val picture = still?.takeIf { it.first == piece?.clip?.id }?.second
    val effect = piece?.let { grade(it.clip, time - it.start) }
    val inset = with(LocalDensity.current) { 20.dp.toPx().toDouble() }
    val framing = piece?.let { work.shown(it.clip, sourceTime, time) }?.at(sourceTime)
    val mirror = piece?.let { work.mirrorShown(it.clip, sourceTime, time) }
    val current by rememberUpdatedState(Triple(source, canvas, mirror))
    val crop by rememberUpdatedState(work.draft.crop)
    val label = stringResource(R.string.crop_canvas)
    val hint = stringResource(R.string.crop_canvas_hint)
    val closer = stringResource(R.string.crop_zoom_in)
    val further = stringResource(R.string.crop_zoom_out)
    val value = if (source != null && canvas != null) {
        val visible = work.draft.crop.clamped(source, canvas)
        stringResource(R.string.crop_canvas_value, work.draft.crop.rotation, work.draft.crop.zoom, (visible.x * 100).roundToInt(), (visible.y * 100).roundToInt())
    } else ""
    Box(
        modifier.background(Color.Black).clipToBounds()
            .pointerInput(Unit) {
                cropGestures(
                    viewport = {
                        val (known, shape, flip) = current
                        if (known == null || shape == null) null else CropViewport(FrameSize(size.width.toDouble(), size.height.toDouble()), known, shape, flip, inset)
                    },
                    crop = { crop }, change = change,
                )
            }
            .semantics {
                contentDescription = "$label. $hint"
                stateDescription = value
                customActions = listOf(
                    CustomAccessibilityAction(closer) { change(CropGestures.zoomStepped(crop, closer = true)); true },
                    CustomAccessibilityAction(further) { change(CropGestures.zoomStepped(crop, closer = false)); true },
                )
            },
    ) {
        if (picture != null && source != null && canvas != null && framing != null) {
            // The footage, in a layer of its own so that its color does not tint the veil.
            Canvas(Modifier.fillMaxSize().graphicsLayer { renderEffect = effect?.asComposeRenderEffect() }) {
                val port = CropViewport(FrameSize(size.width.toDouble(), size.height.toDouble()), source, canvas, mirror, inset)
                val matrix = Matrix().apply {
                    setScale((source.width / picture.width).toFloat(), (source.height / picture.height).toFloat())
                    postConcat(Matrix().apply { setValues(port.transform(framing).matrixValues()) })
                }
                drawIntoCanvas { it.nativeCanvas.drawBitmap(picture, matrix, Paint(Paint.FILTER_BITMAP_FLAG)) }
            }
        }
        if (canvas != null) Canvas(Modifier.fillMaxSize()) {
            val window = CropViewport(FrameSize(size.width.toDouble(), size.height.toDouble()), canvas, canvas, null, inset).window
            val left = window.minX.toFloat()
            val top = window.minY.toFloat()
            val right = window.maxX.toFloat()
            val bottom = window.maxY.toFloat()
            // The footage outside the frame, dimmed as the original's veil (black at 65 %).
            val veil = Color.Black.copy(alpha = 0.65f)
            drawRect(veil, Offset.Zero, Size(size.width, top))
            drawRect(veil, Offset(0f, bottom), Size(size.width, size.height - bottom))
            drawRect(veil, Offset(0f, top), Size(left, bottom - top))
            drawRect(veil, Offset(right, top), Size(size.width - right, bottom - top))
            val thin = 0.5.dp.toPx()
            val line = Color.White.copy(alpha = 0.3f)
            for (third in 1..2) {
                val x = left + (right - left) * third / 3
                val y = top + (bottom - top) * third / 3
                drawLine(line, Offset(x, top), Offset(x, bottom), thin)
                drawLine(line, Offset(left, y), Offset(right, y), thin)
            }
            drawRect(Color.White, Offset(left, top), Size(right - left, bottom - top), style = Stroke(1.5.dp.toPx()))
        }
        if (picture == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
    }
}

/** Stills of the recordings, read at the exact second, at most 1600 pixels on a side as the original asks for (YouRec/CropEditor.swift:265). */
private class CropStills {
    private val decoding = Dispatchers.IO.limitedParallelism(1)
    private var reader: MediaMetadataRetriever? = null
    private var reading: File? = null

    suspend fun frame(file: File, time: Double): Bitmap? = withContext(decoding) {
        try {
            val retriever = reader?.takeIf { reading == file } ?: MediaMetadataRetriever().also {
                reader?.release()
                reader = null
                it.setDataSource(file.path)
                reader = it
                reading = file
            }
            val micros = (time * 1e6).roundToLong()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) retriever.getScaledFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST, 1600, 1600)
            else retriever.getFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST)?.let { whole ->
                val scale = 1600f / maxOf(whole.width, whole.height)
                if (scale < 1) Bitmap.createScaledBitmap(whole, (whole.width * scale).roundToInt(), (whole.height * scale).roundToInt(), true) else whole
            }
        } catch (_: Exception) {
            null
        }
    }

    fun close() {
        val held = reader
        reader = null
        reading = null
        CoroutineScope(decoding).launch { runCatching { held?.release() } }
    }
}

/** The shape names of the core module, which only has them in English. */
private val OutputShape.label: Int
    get() = when (this) {
        OutputShape.Original -> R.string.crop_shape_original
        OutputShape.Landscape -> R.string.crop_shape_landscape
        OutputShape.Portrait -> R.string.crop_shape_portrait
        OutputShape.Square -> R.string.crop_shape_square
        OutputShape.Feed -> R.string.crop_shape_feed
    }

/** The curve names of the core module, which only has them in English. */
private val EffectCurve.label: Int
    get() = when (this) {
        EffectCurve.Linear -> R.string.motion_curve_linear
        EffectCurve.EaseIn -> R.string.motion_curve_ease_in
        EffectCurve.EaseOut -> R.string.motion_curve_ease_out
        EffectCurve.Smooth -> R.string.motion_curve_smooth
        EffectCurve.RiseAndHold -> R.string.motion_curve_rise_hold
        EffectCurve.RiseHoldReturn -> R.string.motion_curve_rise_hold_return
    }

private val RampShape.label: Int
    get() = when (this) {
        RampShape.Linear -> R.string.motion_curve_linear
        RampShape.EaseIn -> R.string.motion_curve_ease_in
        RampShape.EaseOut -> R.string.motion_curve_ease_out
        RampShape.Smooth -> R.string.motion_curve_smooth
    }

/** Seconds as the original writes them: up to two decimals, none that say nothing. */
private fun seconds(value: Double): String {
    val hundredths = (value * 100).roundToLong()
    val text = when {
        hundredths % 100 == 0L -> "${hundredths / 100}"
        hundredths % 10 == 0L -> String.format(Locale.getDefault(), "%.1f", hundredths / 100.0)
        else -> String.format(Locale.getDefault(), "%.2f", hundredths / 100.0)
    }
    return "$text s"
}

/** A rounded button that opens a list of choices under it, as the original's menus do. */
@Composable
private fun Capsule(label: String, spoken: String, icon: ImageVector? = null, trailing: ImageVector? = null, menu: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(50)).background(Plain).clickable { open = true }
                .semantics { role = Role.DropdownList; contentDescription = spoken }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            icon?.let { Icon(it, null, Modifier.size(18.dp), tint = Color.White) }
            Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            trailing?.let { Icon(it, null, Modifier.size(18.dp), tint = Color.White) }
        }
        DropdownMenu(open, { open = false }) { menu { open = false } }
    }
}

/**
 * The move between two framings, as the original offers it for every tool
 * that can move: "Animate" opens the two ends, A and B; choosing one shows
 * it and lets the controls change it. With a motion there is its curve, and
 * for the curves that rise and hold their ramp and the times of the rise and
 * the return. This is "Animate" and its menu (YouRec/EffectAnimationControls.swift:14-35).
 */
@Composable
internal fun AnimationEndpointMenu(
    motion: EffectAnimation?, endpoint: EffectEndpoint?, select: (EffectEndpoint) -> Unit, remove: () -> Unit, done: () -> Unit,
) {
    val label = endpoint?.let { stringResource(R.string.motion_edit_end, it.name) } ?: stringResource(R.string.motion_animate)
    Capsule(label, label, icon = if (motion == null) Icons.Outlined.Diamond else Icons.Filled.Diamond) { close ->
        for (end in EffectEndpoint.entries) {
            DropdownMenuItem(
                { Text(end.name) }, { close(); select(end) },
                leadingIcon = { Icon(if (end == endpoint) Icons.Filled.Diamond else Icons.Outlined.Diamond, null) },
                modifier = Modifier.semantics { selected = end == endpoint },
            )
        }
        if (motion != null) {
            DropdownMenuItem({ Text(stringResource(R.string.motion_remove), color = RecordRed) }, { close(); remove() })
            DropdownMenuItem({ Text(stringResource(R.string.common_done)) }, { close(); done() })
        }
    }
}

/**
 * The motion's controls: "Animate" ([AnimationEndpointMenu]) and with a motion its curve, and for a curve that rises
 * its ramp and times (YouRec/EffectAnimationControls.swift:37-130).
 *
 * @param duration the length of what the tool works on, which the motion runs over.
 */
@Composable
internal fun AnimationControls(
    motion: EffectAnimation?, endpoint: EffectEndpoint?, duration: Double,
    select: (EffectEndpoint) -> Unit, remove: () -> Unit, done: () -> Unit, update: (EffectTiming) -> Unit,
    /** False where the tool shows [AnimationEndpointMenu] elsewhere, as the Color tool does beside Before and After. */
    showsEndpointMenu: Boolean = true,
) {
    val ends: @Composable () -> Unit = {
        if (showsEndpointMenu) AnimationEndpointMenu(motion, endpoint, select, remove, done)
        else Text(stringResource(R.string.motion_curve), color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (motion == null) { if (showsEndpointMenu) ends() } else {
            val timing = motion.timing
            // The curve goes under the end where the two do not fit side by side.
            FlowRow(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Arrangement.spacedBy(8.dp)) {
                ends()
                val curve = stringResource(timing.curve.label)
                Capsule(curve, stringResource(R.string.motion_curve) + ": " + curve, trailing = Icons.Filled.ExpandMore) { close ->
                    for (choice in EffectCurve.entries) {
                        DropdownMenuItem({ Text(stringResource(choice.label)) }, {
                            close()
                            // A rise and a return that would not both fit share the time.
                            val fits = choice != EffectCurve.RiseHoldReturn || timing.rise + timing.release <= duration
                            update(if (fits) timing.copy(curve = choice) else timing.copy(curve = choice, rise = duration / 2, release = duration / 2))
                        }, modifier = Modifier.semantics { selected = choice == timing.curve })
                    }
                }
            }
            if (endpoint != null || timing.curve.hasRise) {
                // How far from A, at the bottom, to B, at the top, over the length of the motion.
                Canvas(Modifier.fillMaxWidth().height(23.dp)) {
                    val line = Path()
                    for (step in 0..100) {
                        val x = step / 100f
                        val y = 2.dp.toPx() + (1 - timing.fraction(x * duration, duration, motion.frame).toFloat()) * (size.height - 4.dp.toPx())
                        if (step == 0) line.moveTo(0f, y) else line.lineTo(x * size.width, y)
                    }
                    drawPath(line, Color.Cyan, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
                }
            }
            if (timing.curve.hasRise) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val name = stringResource(R.string.motion_ramp)
                    Text(name, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    val ramp = stringResource(timing.ramp.label)
                    Capsule(ramp, "$name: $ramp", trailing = Icons.Filled.ExpandMore) { close ->
                        for (choice in RampShape.entries) {
                            DropdownMenuItem({ Text(stringResource(choice.label)) }, { close(); update(timing.copy(ramp = choice)) }, modifier = Modifier.semantics { selected = choice == timing.ramp })
                        }
                    }
                }
                val phases = timing.phases(duration)
                Phase(stringResource(R.string.motion_rise), stringResource(R.string.motion_rise_time), phases.rise, maxOf(0.0, duration - phases.release)) { update(timing.copy(rise = it)) }
                if (timing.curve == EffectCurve.RiseHoldReturn) {
                    Phase(stringResource(R.string.motion_return), stringResource(R.string.motion_return_time), phases.release, maxOf(0.0, duration - phases.rise)) { update(timing.copy(release = it)) }
                }
                Text(stringResource(R.string.motion_hold, seconds(phases.hold)), color = Soft, fontSize = 12.sp)
            }
        }
    }
}

/** The time of the rise or of the return: a slider, and a step down and up beside its value. */
@Composable
private fun Phase(title: String, spoken: String, value: Double, maximum: Double, set: (Double) -> Unit) {
    val step = if (maximum < 0.25) PauseDetector.FRAME else 0.1
    val bounded = { number: Double -> set(if (number >= maximum) maximum else minOf(maximum, maxOf(0.0, Math.round(number / step) * step))) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 12.sp, modifier = Modifier.defaultMinSize(minWidth = 45.dp), maxLines = 1)
        Slider(
            value = value.toFloat().coerceIn(0f, maxOf(0.001, maximum).toFloat()), onValueChange = { bounded(it.toDouble()) },
            valueRange = 0f..maxOf(0.001, maximum).toFloat(), enabled = maximum > 0,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).height(36.dp).semantics { contentDescription = spoken },
        )
        val less = stringResource(R.string.motion_step_less, title)
        val more = stringResource(R.string.motion_step_more, title)
        TextButton(onClick = { bounded(value - step) }, enabled = value > 0, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(36.dp).semantics { contentDescription = less }) { Text("−", fontSize = 18.sp) }
        Text(seconds(value), color = Color.White, fontSize = 12.sp, maxLines = 1)
        TextButton(onClick = { bounded(value + step) }, enabled = value < maximum, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(36.dp).semantics { contentDescription = more }) { Text("+", fontSize = 18.sp) }
    }
}
