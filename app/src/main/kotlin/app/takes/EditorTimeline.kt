@file:OptIn(UnstableApi::class)

package app.takes

import androidx.compose.runtime.setValue
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import app.takes.core.WaveformBuilder
import kotlin.math.sqrt
import app.takes.core.TextOverlay
import androidx.compose.ui.draw.alpha
import app.takes.core.TransitionStyle
import androidx.compose.foundation.clickable
import app.takes.core.AudioItem
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp

import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takes.core.TimelineClip
import app.takes.core.TimelineEditing
import app.takes.core.TimelineZoom
import androidx.compose.ui.graphics.nativeCanvas
import app.takes.core.TransitionLayout
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

private val Panel = Color(0xFF1B1D21)
private val Muted = Color(0xFFA0A4AB)
private val TileSize = 56.dp
private val WaveHeight = 30.dp
private val WaveColor = Color(0xFF7DB7FF)
private val SoundColor = Color(0xFF7BD389)
private val TrackHeight = 26.dp
private val StripTop = 17.dp

/** Timeline thumbnails, decoded off the main thread and kept for reuse. */
internal object Thumbnails {
    private val cache = LruCache<String, Bitmap>(300)
    private val decoding = Dispatchers.IO.limitedParallelism(2)

    suspend fun frame(file: File, time: Double): Bitmap? {
        val key = file.path + "@" + (time * 2).roundToInt()
        cache.get(key)?.let { return it }
        return withContext(decoding) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                retriever.getFrameAtTime((time * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { frame ->
                    val scale = 160f / maxOf(frame.width, frame.height)
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

/** A trim in progress: the clip being changed and its uncommitted bounds. */
internal data class Trim(val index: Int, val leading: Boolean, val clip: TimelineClip)

/**
 * A Move under way: a horizontal drag on a picture being moved moves the
 * selection by [drag] seconds from where the finger went down; [end] when it
 * lifts, [cancel] when the system takes the gesture away. Empty space still
 * scrolls, and the trim handles and levels are off.
 */
internal class MoveDrag(val drag: (seconds: Double) -> Unit, val end: () -> Unit, val cancel: () -> Unit)

/** A row of pictures: thumbnails over the waveform, smaller when several rows share the timeline. */
private fun rowTile(rows: TimelineRows?) = if (rows == null || rows.count <= 1) TileSize else 40.dp
private fun rowWave(rows: TimelineRows?) = if (rows == null || rows.count <= 1) WaveHeight else 16.dp
private fun rowPitch(rows: TimelineRows?) = rowTile(rows) + rowWave(rows) + if (rows == null || rows.count <= 1) 0.dp else 2.dp
private fun stripHeight(rows: TimelineRows?) = if (rows == null || rows.count <= 1) TileSize + WaveHeight else rowPitch(rows) * rows.count
private val GhostColor = Color(0xFF64D2FF)
private val SyncColor = Color(0xFFFF453A)

@Composable
internal fun Timeline(
    clips: List<TimelineClip>, files: Map<String, File>, sounds: List<AudioItem>, names: Map<String, String>,
    select: (clip: String?, audio: String?) -> Unit, toggle: (clip: String?, audio: String?) -> Unit, seek: (time: Double, deselect: Boolean) -> Unit,
    openTitle: (index: Int, title: TextOverlay) -> Unit, current: Int?, trim: Trim?,
    pixelsPerSecond: Float, shift: Float, scroll: ScrollState,
    touched: () -> Unit, focusTransition: (TransitionLayout.Join) -> Unit, beginTrim: (leading: Boolean) -> Unit, dragTrim: (pixels: Float) -> Unit, endTrim: () -> Unit,
    marked: MarkedView? = null, level: LevelGesture? = null,
    pinch: (factor: Float?) -> Unit = {},
    rows: TimelineRows? = null, moveDrag: MoveDrag? = null,
    soundRows: Int = AudioItem.TRACKS, syncOffsets: Map<String, Double> = emptyMap(), soundless: Set<String> = emptySet(),
    movingSounds: Set<String> = emptySet(),
) {
    val density = LocalDensity.current
    // The gesture below outlives recompositions; always call the latest callbacks.
    val onLevel by rememberUpdatedState(level)
    val latestClips by rememberUpdatedState(clips)
    val latestSounds by rememberUpdatedState(sounds)
    var windowTop by remember { mutableFloatStateOf(0f) }
    val onTouched by rememberUpdatedState(touched)
    val onBeginTrim by rememberUpdatedState(beginTrim)
    val onDragTrim by rememberUpdatedState(dragTrim)
    val onEndTrim by rememberUpdatedState(endTrim)
    val onSelect by rememberUpdatedState(select)
    val onToggle by rememberUpdatedState(toggle)
    val onSeek by rememberUpdatedState(seek)
    val onPinch by rememberUpdatedState(pinch)
    val scale by rememberUpdatedState(pixelsPerSecond)
    val latestRows by rememberUpdatedState(rows)
    val onMove by rememberUpdatedState(moveDrag)
    val latestSoundRows by rememberUpdatedState(soundRows)
    val latestMovingSounds by rememberUpdatedState(movingSounds)
    val haptics = LocalHapticFeedback.current
    val pitch = rowPitch(rows)
    val strip = stripHeight(rows)
    // Where a touch grabs a trim handle of the current clip instead of scrolling. Off a single row, on the current clip's own row.
    val handle by rememberUpdatedState { x: Float, y: Float, half: Float ->
        current?.takeIf { it in clips.indices && moveDrag == null }?.let { index ->
            val row = rows?.rows?.getOrNull(index) ?: 0
            val top = with(density) { StripTop.toPx() + row * pitch.toPx() }
            if (rows != null && (y < top || y > top + with(density) { (rowTile(rows) + rowWave(rows)).toPx() })) return@let null
            val start = rows?.starts?.getOrNull(index) ?: TimelineEditing.clipStart(clips, index)
            val left = half + (start * pixelsPerSecond).toFloat() + shift - scroll.value
            val right = left + (clips[index].duration * pixelsPerSecond).toFloat()
            // Just the visible grip and a little around it; the rest of the clip scrolls.
            val (outside, inside) = with(density) { 6.dp.toPx() to 14.dp.toPx() }
            // An edge under a transition that plays two clips at once is no trim edge, as in the original.
            val leading = x in (left - outside)..(left + inside) && (rows != null || TimelineEditing.overlap(clips, index - 1) <= 0)
            val trailing = x in (right - inside)..(right + outside) && (rows != null || TimelineEditing.overlap(clips, index) <= 0)
            when {
                leading && trailing -> abs(x - left) < abs(x - right)
                leading -> true
                trailing -> false
                else -> null
            }
        }
    }
    BoxWithConstraints(
        // Clips at a fixed height from the top; audio tracks appear under them when there is sound.
        Modifier.fillMaxWidth().height(120.dp + (strip - TileSize - WaveHeight) + if (sounds.isEmpty()) 0.dp else TrackHeight * soundRows).background(Panel)
            .onGloballyPositioned { windowTop = it.positionInWindow().y }.pointerInput(Unit) {
            // Two fingers zoom, as the original's pinch (EditorTimeline.swift:577-582): null as they land, then how many
            // times as far apart they are. Meanwhile nothing scrolls, trims or levels.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var from = 0f
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.size >= 2) {
                        val apart = (pressed[0].position - pressed[1].position).getDistance()
                        if (from == 0f) { from = apart.coerceAtLeast(1f); onPinch(null) } else onPinch(apart / from)
                    }
                    if (from > 0f) event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }.pointerInput(Unit) {
            // The handles move under the finger, so the drag is tracked here,
            // in a coordinate space that stays still.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                onTouched() // scrubbing pauses playback; it stays paused after release
                val laid = latestRows
                val time = ((down.position.x - size.width / 2f + scroll.value) / scale).toDouble()
                val inStrip = down.position.y >= StripTop.toPx() && down.position.y <= (StripTop + strip).toPx()
                // The row of pictures under the finger, off a single row.
                val row = if (laid != null && inStrip) ((down.position.y - StripTop.toPx()) / pitch.toPx()).toInt() else 0
                // During a Move a drag on a picture or a sound being moved moves it; anywhere else the timeline scrolls.
                onMove?.let { move ->
                    val card = laid?.takeIf { inStrip }?.clipAt(latestClips, row, time)
                    val below = down.position.y - (StripTop + strip).toPx()
                    val soundRow = if (below >= 0) (below / TrackHeight.toPx()).toInt() else -1
                    val sound = if (soundRow in 0 until latestSoundRows) TimelineEditing.audioAt(latestSounds, soundRow, time)?.id else null
                    val grabbed = (card != null && laid?.moving?.contains(latestClips[card].id) == true) || (sound != null && sound in latestMovingSounds)
                    if (!grabbed) return@awaitEachGesture
                    down.consume()
                    var total = 0f
                    var lifted = false
                    try {
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                            total += change.positionChange().x
                            change.consume()
                            if (!change.pressed) { lifted = true; break }
                            move.drag((total / scale).toDouble())
                        }
                    } finally {
                        if (lifted) move.end() else move.cancel()
                    }
                    return@awaitEachGesture
                }
                // A drag up or down on a clip's waveform, or on a sound on an audio track, chooses its level, as in the original.
                val grip = if (inStrip) handle(down.position.x, down.position.y, size.width / 2f) else null
                onLevel?.takeIf { grip == null }?.let { gesture ->
                    val tile = rowTile(laid).toPx()
                    val wave = rowWave(laid).toPx()
                    val below = down.position.y - StripTop.toPx() - row * pitch.toPx() - tile
                    val onWave = below >= 0 && below < wave && time >= 0
                    val clip = when {
                        !onWave || !inStrip -> null
                        laid != null -> laid.clipAt(latestClips, row, time)?.let { latestClips[it].id }
                        time < TimelineEditing.duration(latestClips) -> TimelineEditing.locate(latestClips, time)?.let { latestClips[it.index].id }
                        else -> null
                    }
                    val underStrip = down.position.y - (StripTop + strip).toPx()
                    val track = if (underStrip >= 0) (underStrip / TrackHeight.toPx()).toInt() else -1
                    val sound = if (track in 0 until latestSoundRows) TimelineEditing.audioAt(latestSounds, track, time)?.id else null
                    if (clip != null || sound != null) { dragLevel(down, clip, sound, time, windowTop, gesture); return@awaitEachGesture }
                }
                // Above and below the clips the timeline only scrolls.
                if (!inStrip) return@awaitEachGesture
                val leading = grip ?: return@awaitEachGesture
                down.consume()
                onBeginTrim(leading)
                try {
                    var total = 0f
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        total += change.positionChange().x
                        change.consume()
                        if (!change.pressed) break
                        onDragTrim(total)
                    }
                } finally {
                    onEndTrim()
                }
            }
        }.pointerInput(Unit) {
            // A tap that no clip, title or sound took.
            detectTapGestures { at -> onSeek(((at.x - size.width / 2f + scroll.value) / scale).toDouble(), at.y >= StripTop.toPx()) }
        },
    ) {
        val viewport = constraints.maxWidth
        Column(Modifier.horizontalScroll(scroll).padding(horizontal = maxWidth / 2).padding(top = StripTop).fillMaxHeight()) {
            val width = with(density) { ((rows?.duration ?: TimelineEditing.duration(clips)) * pixelsPerSecond).toFloat().toDp() }
            if (rows != null) PictureRows(
                clips, files, rows, current, trim, pixelsPerSecond, viewport, scroll, marked, Modifier.width(width).height(strip),
                openTitle = openTitle, select = { onSelect(it, null) },
                toggle = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onToggle(it, null) },
                focusTransition = focusTransition, soundless = soundless,
            )
            // Clips sit at their timeline position; a dissolve draws the incoming clip over the end of the previous one.
            else Box(Modifier.width(width).height(TileSize + WaveHeight).offset { IntOffset(shift.roundToInt(), 0) }, Alignment.CenterStart) {
                clips.forEachIndexed { index, clip ->
                    val offset = (TimelineEditing.clipStart(clips, index) * pixelsPerSecond).toFloat()
                    key(clip.id) {
                        ClipStrip(
                            clip, files.getValue(clip.sourceID), index == current,
                            // The transition into the clip where it begins, when it is the clip's own opening or plays over the clip before;
                            // the one out of it where it ends, when it is the clip's own close or the next clip only follows.
                            entering = TimelineEditing.entering(clips, index)?.takeIf { it.edge == -1 || it.timing.overlap > 0 },
                            leaving = TimelineEditing.leaving(clips, index)?.takeIf { it.edge == 1 || it.timing.overlap <= 0 },
                            dissolve = (TimelineEditing.overlap(clips, index - 1) * pixelsPerSecond).toFloat(),
                            // The edge being dragged keeps its grip while the draft changes around it.
                            trimsEnd = trim?.leading == false || TimelineEditing.overlap(clips, index) <= 0,
                            offset, pixelsPerSecond, viewport, scroll, openTitle = { openTitle(index, it) },
                            select = { onSelect(clip.id, null) }, toggle = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onToggle(clip.id, null) },
                            focusTransition = focusTransition, soundless = clip.id in soundless,
                        )
                    }
                }
                // What is marked: the part of each clip inside the range, or the clips selected whole.
                if (marked != null) {
                    clips.forEachIndexed { index, clip ->
                        val start = TimelineEditing.clipStart(clips, index)
                        if (marked.clip(clip.id)) MarkedPart(marked, start, start + clip.duration, pixelsPerSecond, TileSize + WaveHeight)
                    }
                    marked.range?.let { RangeGuides(it, pixelsPerSecond, Modifier.matchParentSize()) }
                }
            }
            // Sound on its own tracks. It stays where it is when the clips above are edited.
            val soundName = stringResource(R.string.editor_sound_default_name)
            val mutedNote = stringResource(R.string.sound_muted)
            val selectLabel = stringResource(R.string.selection_select)
            val toggleLabel = stringResource(R.string.selection_toggle)
            if (sounds.isNotEmpty()) for (track in 0 until soundRows) {
                Box(Modifier.width(width).height(TrackHeight).padding(top = 3.dp)) {
                    for (item in sounds) {
                        if (item.track != track) continue
                        key(item.id) {
                            val note = item.audio?.let { if (it.muted) " · $mutedNote" else if (it.volumeDB != 0.0) " · ${levelLabel(it.volumeDB)}" else "" } ?: ""
                            Text(
                                (names[item.sourceID] ?: soundName) + note, color = Color.Black, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.offset { IntOffset((item.start * pixelsPerSecond).roundToInt(), 0) }
                                    .width(with(density) { (item.duration * pixelsPerSecond).toFloat().toDp() }).fillMaxHeight()
                                    .background(SoundColor, RoundedCornerShape(4.dp))
                                    .then(if (item.id in movingSounds) Modifier.border(2.dp, GhostColor, RoundedCornerShape(4.dp)) else Modifier)
                                    .pointerInput(item.id) {
                                        detectTapGestures(
                                            onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onToggle(null, item.id) },
                                            onTap = { onSelect(null, item.id) },
                                        )
                                    }
                                    .semantics {
                                        onClick(selectLabel) { onSelect(null, item.id); true }
                                        onLongClick(toggleLabel) { onToggle(null, item.id); true }
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                            // A sound that plays apart from its picture says by how much, as the original's Restore sync does.
                            syncOffsets[item.id]?.let { offset ->
                                val shown = signedSeconds(offset)
                                val described = stringResource(R.string.sync_badge_description, shown)
                                Text(
                                    stringResource(R.string.sync_badge, shown), color = Color.White, fontSize = 10.sp, maxLines = 1, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.offset { IntOffset((item.start * pixelsPerSecond).roundToInt() + 2.dp.roundToPx(), 2.dp.roundToPx()) }
                                        .background(SyncColor, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp)
                                        .semantics { contentDescription = described },
                                )
                            }
                        }
                    }
                    if (marked != null) {
                        for (item in sounds) if (item.track == track && marked.audio(item.id)) MarkedPart(marked, item.start, item.end, pixelsPerSecond, TrackHeight - 3.dp)
                        marked.range?.let { RangeGuides(it, pixelsPerSecond, Modifier.matchParentSize()) }
                    }
                }
            }
        }
        // The ruler along the top, its marks at least 44 dp apart, as in the original (EditorTimeline.swift:998-1001).
        val rulerPaint = remember(density) {
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(0xA0, 0xA4, 0xAB)
                textSize = with(density) { 10.sp.toPx() }
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL)
                fontFeatureSettings = "tnum"
            }
        }
        Canvas(Modifier.fillMaxWidth().height(StripTop)) {
            val half = size.width / 2
            val step = TimelineZoom.rulerStep((pixelsPerSecond / density.density).toDouble())
            val total = rows?.duration ?: TimelineEditing.duration(clips)
            val start = ((scroll.value - half) / pixelsPerSecond).toDouble().coerceAtLeast(0.0)
            val end = ((scroll.value + half) / pixelsPerSecond).toDouble().coerceAtMost(total)
            for (time in TimelineZoom.rulerMarks(start, end, step)) {
                val x = half + (time * pixelsPerSecond).toFloat() - scroll.value
                drawLine(Muted, Offset(x, size.height - 4.dp.toPx()), Offset(x, size.height), 1.dp.toPx())
                drawContext.canvas.nativeCanvas.drawText(TimelineZoom.rulerLabel(time), x + 3.dp.toPx(), 11.dp.toPx(), rulerPaint)
            }
        }
        Box(Modifier.align(Alignment.Center).width(2.dp).fillMaxHeight().background(Color.White))
        level?.readout?.let { LevelReadoutLabel(it, viewport.toFloat()) }
        if (clips.isNotEmpty()) {
            val label = trim?.let { stringResource(R.string.editor_clip_duration, it.index + 1, preciseLabel(it.clip.duration)) }
                ?: stringResource(R.string.editor_clip_position, (current ?: 0) + 1, clips.size)
            Text(label, color = if (trim != null) Color.White else Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopStart).background(Panel).padding(start = 8.dp, end = 4.dp))
        }
    }
}

@Composable
internal fun ClipStrip(
    clip: TimelineClip, file: File, current: Boolean, entering: TransitionLayout.Join?, leaving: TransitionLayout.Join?, dissolve: Float, trimsEnd: Boolean, offset: Float,
    pixelsPerSecond: Float, viewport: Int, scroll: ScrollState, openTitle: (TextOverlay) -> Unit,
    select: () -> Unit, toggle: () -> Unit, focusTransition: (TransitionLayout.Join) -> Unit,
    top: Int = 0, tileHeight: Dp = TileSize, waveHeight: Dp = WaveHeight, outline: Color? = null,
    soundless: Boolean = false,
) {
    val density = LocalDensity.current
    val onSelect by rememberUpdatedState(select)
    val onToggle by rememberUpdatedState(toggle)
    val selectLabel = stringResource(R.string.selection_select)
    val toggleLabel = stringResource(R.string.selection_toggle)
    val width = (clip.duration * pixelsPerSecond).toFloat()
    val tile = with(density) { tileHeight.toPx() }
    val count = ceil(width / tile).toInt()
    // Only the tiles near the screen are composed and decoded.
    val visible by remember(offset, count, tile, viewport) {
        derivedStateOf {
            val first = ((scroll.value - viewport - offset) / tile).toInt().coerceIn(0, count)
            val last = ceil((scroll.value + viewport - offset) / tile).toInt().coerceIn(0, count)
            first until last
        }
    }
    val peaks by produceState<ByteArray?>(null, file) { value = Waveforms.peaks(file) }
    val loudness = (clip.audio?.gain ?: 1.0).toFloat()
    Box(
        Modifier.offset { IntOffset(offset.roundToInt(), top) }.width(with(density) { width.toDp() })
            .border(if (current || outline != null) 2.dp else 1.dp, outline ?: if (current) Color.White else Color.Black)
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onToggle() }, onTap = { onSelect() }) }
            .semantics {
                onClick(selectLabel) { onSelect(); true }
                onLongClick(toggleLabel) { onToggle(); true }
            },
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(tileHeight).clipToBounds().background(Color(0xFF2A2D33))) {
                for (index in visible) {
                    key(index) {
                        val time = clip.sourceTime((index + 0.5) * tile / pixelsPerSecond).coerceAtMost(clip.end)
                        val image by produceState<Bitmap?>(null, file, (time * 2).roundToInt()) { value = Thumbnails.frame(file, time) }
                        image?.let {
                            Image(it.asImageBitmap(), null, Modifier.offset { IntOffset((index * tile).roundToInt(), 0) }.size(tileHeight), contentScale = ContentScale.Crop)
                        }
                    }
                }
            }
            Canvas(Modifier.fillMaxWidth().height(waveHeight).background(Color(0xFF14161A))) {
                // A picture whose sound was unlinked has none here: the sound is drawn on a row of its own.
                if (soundless) return@Canvas
                val levels = peaks ?: return@Canvas
                val step = 3.dp.toPx()
                // Draw only the columns near the screen.
                var x = ((scroll.value - viewport - offset).coerceAtLeast(0f) / step).toInt() * step
                val last = minOf(size.width, scroll.value + viewport - offset)
                while (x < last) {
                    val from = clip.sourceTime((x / pixelsPerSecond).toDouble())
                    val level = WaveformBuilder.level(levels, from, minOf(clip.end, from + step / pixelsPerSecond * clip.rate))
                    // Drawn at the clip's own level: flat when muted, taller when boosted.
                    val bar = (sqrt(level * loudness) * size.height).coerceIn(1.dp.toPx(), size.height)
                    drawRect(if (loudness == 0f) Muted else WaveColor, Offset(x, (size.height - bar) / 2), Size(step * 0.6f, bar))
                    x += step
                }
            }
        }
        // Titles as labelled bars over the footage they cover; a tap opens the title on this piece.
        // Only the part the clip shows: a title may reach past a clip that was split or trimmed after it was placed.
        // Titles showing at the same time go on rows of their own, so each can be read and tapped.
        val editTitle = stringResource(R.string.title_edit)
        val rows = mutableListOf<Double>() // where the last bar of each row ends
        for (title in clip.texts.orEmpty().sortedBy { it.start }) {
            val from = maxOf(title.start, clip.start)
            val to = minOf(title.end, clip.end)
            if (to <= from) continue
            val row = rows.indexOfFirst { it <= from }.let { if (it >= 0) it else if (rows.size < 4) rows.size else rows.indices.minBy { index -> rows[index] } }
            if (row == rows.size) rows += to else rows[row] = to
            Text(
                title.text, color = Color.Black, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Clip,
                modifier = Modifier.offset { IntOffset((clip.offset(from) * pixelsPerSecond).roundToInt(), (row * 21.dp.toPx()).roundToInt()) }
                    .width(with(density) { ((to - from) / clip.rate * pixelsPerSecond).toFloat().toDp() })
                    .background(TitleColor, RoundedCornerShape(3.dp))
                    .clickable(onClickLabel = editTitle) { openTitle(title) }
                    // Clear of the trim grip when the title starts with the clip.
                    .padding(start = if (current && from == clip.start) 13.dp else 3.dp, end = 3.dp),
            )
        }
        // What has been done to the picture, named over it.
        listOfNotNull(clip.color?.label(), stringResource(R.string.editor_tag_crop).takeIf { clip.crop != null }, clip.speed?.let { speedLabel(it.rate) }).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { tags ->
            Text(tags, color = Color.Black, fontSize = 10.sp, maxLines = 1,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 12.dp).background(Color(0xFFB6C2FF), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp))
        }
        // What has been done to the sound, where the waveform is.
        clip.audio?.takeIf { !soundless }?.let { audio ->
            val note = listOfNotNull(
                if (audio.muted) stringResource(R.string.sound_muted) else audio.volumeDB.takeIf { it != 0.0 }?.let(::levelLabel),
                stringResource(R.string.editor_tag_fade).takeIf { audio.fadeIn > 0 || audio.fadeOut > 0 },
                stringResource(R.string.editor_tag_clean).takeIf { audio.denoise > 0 },
            ).joinToString(" · ")
            if (note.isNotEmpty()) {
                Text(note, color = Color.White, fontSize = 10.sp, maxLines = 1,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp).background(Color(0xCC000000), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp))
            }
        }
        // Bookmarks as flags on the footage they mark.
        for (mark in clip.bookmarks.orEmpty()) {
            if (mark.time < clip.start || mark.time >= clip.end) continue
            Box(Modifier.offset { IntOffset((clip.offset(mark.time) * pixelsPerSecond).roundToInt() - 1.dp.roundToPx(), 0) }
                .width(3.dp).height(tileHeight + waveHeight).background(BookmarkColor))
        }
        // The part of this clip that plays over the previous one, and the transitions at its two ends by name.
        if (dissolve > 0) Box(Modifier.width(with(density) { dissolve.toDp() }).height(tileHeight + waveHeight).background(Color.White.copy(alpha = 0.35f)))
        // A tap on the name goes to the join, where the transition is offered for editing; the touch reaches a little around the name.
        val onFocus by rememberUpdatedState(focusTransition)
        val focusLabel = stringResource(R.string.transition_tag_focus)
        @Composable
        fun tag(join: TransitionLayout.Join, modifier: Modifier, reach: PaddingValues) {
            val style = join.transition?.style ?: return
            Box(modifier.clickable(onClickLabel = focusLabel) { onFocus(join) }.padding(reach)) {
                Text(
                    stringResource(if (style == TransitionStyle.FadeColor) R.string.transition_tag_fade else style.label), color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    modifier = Modifier.background(Color.White, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp),
                )
            }
        }
        entering?.let { tag(it, Modifier.align(Alignment.BottomStart).padding(start = 12.dp), PaddingValues(top = 14.dp, end = 12.dp)) }
        leaving?.let { tag(it, Modifier.align(Alignment.BottomEnd).padding(end = 12.dp), PaddingValues(top = 14.dp, start = 12.dp)) }
        if (current) {
            val grip = Modifier.width(minOf(10.dp, with(density) { (width / 2).toDp() })).height(tileHeight + waveHeight).background(Color.White, RoundedCornerShape(3.dp))
            // No grip on an edge that lies under a transition playing two clips at once: it is not the edge of the footage shown.
            if (dissolve <= 0) Box(grip.align(Alignment.CenterStart))
            if (trimsEnd) Box(grip.align(Alignment.CenterEnd))
        }
    }
}

/**
 * The pictures of an edit that is no single row, each on its row at its own
 * start (TimelineRows), with the time without picture marked as a gap and,
 * during a Move, the outline of where the moved pictures were.
 */
@Composable
private fun PictureRows(
    clips: List<TimelineClip>, files: Map<String, File>, rows: TimelineRows, current: Int?, trim: Trim?,
    pixelsPerSecond: Float, viewport: Int, scroll: ScrollState, marked: MarkedView?, modifier: Modifier,
    openTitle: (index: Int, title: TextOverlay) -> Unit, select: (String) -> Unit, toggle: (String) -> Unit,
    focusTransition: (TransitionLayout.Join) -> Unit, soundless: Set<String> = emptySet(),
) {
    val density = LocalDensity.current
    val tile = rowTile(rows)
    val wave = rowWave(rows)
    val pitch = with(density) { rowPitch(rows).toPx() }
    val gapLabel = stringResource(R.string.gap_label)
    Box(modifier) {
        // Time without picture: black in the movie, marked here across every row.
        for (gap in rows.gaps) {
            Box(
                Modifier.offset { IntOffset((gap.start * pixelsPerSecond).roundToInt(), 0) }
                    .width(with(density) { ((gap.end - gap.start) * pixelsPerSecond).toFloat().toDp() }).fillMaxHeight()
                    .background(Color.Black).border(1.dp, Muted.copy(alpha = 0.5f)),
                Alignment.Center,
            ) { Text(gapLabel, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Clip) }
        }
        clips.forEachIndexed { index, clip ->
            val file = files[clip.sourceID] ?: return@forEachIndexed
            key(clip.id) {
                ClipStrip(
                    clip, file, index == current && trim?.index.let { it == null || it == index },
                    entering = null, leaving = null, dissolve = 0f, trimsEnd = true,
                    (rows.starts[index] * pixelsPerSecond).toFloat(), pixelsPerSecond, viewport, scroll,
                    openTitle = { openTitle(index, it) }, select = { select(clip.id) }, toggle = { toggle(clip.id) },
                    focusTransition = focusTransition,
                    top = (rows.rows[index] * pitch).roundToInt(), tileHeight = tile, waveHeight = wave,
                    outline = GhostColor.takeIf { clip.id in rows.moving }, soundless = clip.id in soundless,
                )
            }
        }
        if (marked != null) {
            clips.forEachIndexed { index, clip ->
                if (!marked.clip(clip.id)) return@forEachIndexed
                Box(Modifier.offset { IntOffset(0, (rows.rows[index] * pitch).roundToInt()) }) {
                    MarkedPart(marked, rows.starts[index], rows.starts[index] + clip.duration, pixelsPerSecond, tile + wave)
                }
            }
            marked.range?.let { RangeGuides(it, pixelsPerSecond, Modifier.matchParentSize()) }
        }
        // Where the moved pictures were when Move began: a dashed outline over their rows.
        rows.ghost?.let { ghost ->
            val movingRows = clips.indices.filter { clips[it].id in rows.moving }.map { rows.rows[it] }
            // A sound moved alone has its own outline on its row; no picture moves.
            if (movingRows.isEmpty()) return@let
            val first = movingRows.minOrNull() ?: 0
            val last = movingRows.maxOrNull() ?: 0
            Canvas(Modifier.matchParentSize()) {
                val dashes = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                val left = (ghost.start * pixelsPerSecond).toFloat()
                val right = (ghost.end * pixelsPerSecond).toFloat()
                val top = first * pitch
                val bottom = last * pitch + (tile + wave).toPx()
                drawRect(GhostColor, Offset(left, top), Size(right - left, bottom - top), style = Stroke(1.5.dp.toPx(), pathEffect = dashes))
            }
        }
    }
}
