@file:OptIn(UnstableApi::class)

package app.takes

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import app.takes.core.WaveformBuilder
import kotlin.math.sqrt
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import app.takes.core.TextOverlay
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.takes.core.EditState
import app.takes.core.Project
import app.takes.core.TimelineClip
import app.takes.core.TimelineEditing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
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
private val TitleColor = Color(0xFFFFD54A)

private fun preciseLabel(seconds: Double): String {
    val tenths = (seconds.coerceAtLeast(0.0) * 10).toLong()
    return "%d:%02d.%d".format(tenths / 600, tenths / 10 % 60, tenths % 10)
}

/** Plays the edit as a playlist of clipped sources, addressed by timeline seconds. */
private class EditorPlayer(context: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build()
    var playing by mutableStateOf(false); private set

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        })
    }

    fun load(clips: List<TimelineClip>, files: Map<String, File>, time: Double) {
        val location = TimelineEditing.locate(clips, time)
        if (location == null) { player.clearMediaItems(); return }
        player.setMediaItems(clips.map { clippedItem(it, files.getValue(it.sourceID)) }, location.index, (location.offset * 1000).toLong())
        player.prepare()
    }

    fun time(clips: List<TimelineClip>): Double {
        val index = player.currentMediaItemIndex.coerceIn(0, maxOf(0, clips.lastIndex))
        return TimelineEditing.clipStart(clips, index) + player.currentPosition.coerceAtLeast(0) / 1000.0
    }

    fun seek(clips: List<TimelineClip>, time: Double) {
        val location = TimelineEditing.locate(clips, time) ?: return
        player.seekTo(location.index, (location.offset * 1000).toLong())
    }

    fun pause() = player.pause()
}

/** Timeline thumbnails, decoded off the main thread and kept for reuse. */
private object Thumbnails {
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

@Composable
fun EditorScreen(store: ProjectStore, exporter: Exporter, modifier: Modifier = Modifier) {
    val project = store.selected
    if (project == null || project.sources.isEmpty()) {
        Box(modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
            Text(if (project == null) "Choose a project in Projects to edit it." else "Record something in this project before editing.", color = Muted)
        }
        return
    }
    key(project.id) { Editor(project, store, exporter, modifier) }
}

@Composable
private fun Editor(project: Project, store: ProjectStore, exporter: Exporter, modifier: Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val edit = project.edit
    val clips = edit.clips
    val duration = edit.duration
    val files = remember(project.sources) { project.sources.associate { it.id to store.file(project, it) } }
    val editor = remember { EditorPlayer(context) }
    var playhead by remember { mutableDoubleStateOf(0.0) }
    var scale by remember { mutableFloatStateOf(48f) } // dp of timeline per second
    val pixelsPerSecond = with(LocalDensity.current) { scale.dp.toPx() }
    val scroll = rememberScrollState()

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) editor.pause() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); editor.player.release() }
    }
    LaunchedEffect(clips) {
        playhead = playhead.coerceIn(0.0, duration)
        editor.load(clips, files, playhead)
    }
    // The timeline scrolls under a fixed playhead. Playback and edits move the
    // scroll position; a drag moves the playhead and seeks.
    LaunchedEffect(clips, pixelsPerSecond, editor.playing) {
        withFrameNanos { } // lay out the new clip widths first
        scroll.scrollTo((playhead.coerceIn(0.0, duration) * pixelsPerSecond).roundToInt())
        while (editor.playing) {
            withFrameNanos { }
            playhead = editor.time(clips).coerceIn(0.0, duration)
            scroll.scrollTo((playhead * pixelsPerSecond).roundToInt())
        }
    }
    LaunchedEffect(clips, pixelsPerSecond) {
        snapshotFlow { scroll.value }.drop(1).collect { pixels ->
            val time = (pixels / pixelsPerSecond).toDouble().coerceIn(0.0, duration)
            // Our own scrollTo lands within half a pixel of the playhead.
            if (!editor.playing && abs(time - playhead) > 0.6 / pixelsPerSecond) {
                playhead = time
                editor.seek(clips, time)
            }
        }
    }

    // Lambdas, not local functions: references to local functions compare
    // equal across recompositions, so a long-lived gesture would keep the first.
    val commit = { next: EditState?, time: Double ->
        if (next != null) {
            editor.pause()
            playhead = time
            store.commit(project.id, next)
        }
    }
    val location = TimelineEditing.locate(clips, playhead)

    // Trim handles change the clip under the playhead. The draft is shown in
    // the timeline while dragging and becomes one undo step on release.
    val durations = remember(project.sources) { project.sources.associate { it.id to it.duration } }
    var trim by remember { mutableStateOf<Trim?>(null) }
    val shown = trim?.takeIf { it.index in clips.indices }?.let { draft -> clips.toMutableList().also { it[draft.index] = draft.clip } } ?: clips
    val dragTrim = drag@{ pixels: Float ->
        val draft = trim ?: return@drag
        val original = clips.getOrNull(draft.index) ?: return@drag
        val delta = pixels / pixelsPerSecond
        val limit = durations[original.sourceID] ?: original.end
        val next = if (draft.leading) TimelineEditing.trimmed(original, original.start + delta, original.end, limit)
        else TimelineEditing.trimmed(original, original.start, original.end + delta, limit)
        trim = draft.copy(clip = next)
        // Show the frame at the moving edge while it is inside the current clip.
        val edge = if (draft.leading) next.start else next.end - 0.03
        if (edge in original.start..original.end) editor.seek(clips, TimelineEditing.clipStart(clips, draft.index) + edge - original.start)
    }
    val endTrim = end@{
        val draft = trim ?: return@end
        trim = null
        val original = clips.getOrNull(draft.index) ?: return@end
        val base = TimelineEditing.clipStart(clips, draft.index)
        val next = TimelineEditing.trim(edit, draft.index, draft.clip.start, draft.clip.end, durations[original.sourceID] ?: original.end)
        if (next == null) { editor.seek(clips, playhead); return@end }
        // Keep the same frame under the playhead when it survives the trim.
        val offset = (playhead - base - (draft.clip.start - original.start)).coerceIn(0.0, draft.clip.duration - 0.01)
        val moved = base + offset - playhead
        commit(next, base + offset)
        scroll.dispatchRawDelta((moved * pixelsPerSecond).toFloat()) // no one-frame jump before the scroll catches up
    }

    // What the playhead shows on top of the picture: titles and the fade of a transition.
    val canvas by produceState<Pair<Int, Int>?>(null, clips.firstOrNull()?.sourceID) {
        value = clips.firstOrNull()?.let { files[it.sourceID] }?.let { withContext(Dispatchers.IO) { displaySize(it) } }
    }
    val current = location?.let { clips[it.index] }
    val sourceTime = if (location != null && current != null) current.start + location.offset else 0.0
    val titles = current?.let { TimelineEditing.textsAt(it, sourceTime) }.orEmpty()
    val level = location?.let { TimelineEditing.level(clips, it.index, it.offset) } ?: 1.0
    SideEffect { editor.player.volume = level.toFloat() }
    var editingTitle by remember { mutableStateOf(false) }
    var editingTransition by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(project.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = { editor.pause(); store.undo(project.id) }, enabled = project.canUndo) { Text("Undo") }
            TextButton(onClick = { editor.pause(); store.redo(project.id) }, enabled = project.canRedo) { Text("Redo") }
            Button(onClick = { editor.pause(); exporter.start(project) }, enabled = clips.isNotEmpty()) { Text("Export") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), Alignment.Center) {
            // The export canvas: the first clip's shape. Other clips fit inside it.
            Box(canvas?.let { Modifier.aspectRatio(it.first.toFloat() / it.second) } ?: Modifier.fillMaxSize()) {
                AndroidView(
                    { PlayerView(it).apply { useController = false; player = editor.player } },
                    Modifier.fillMaxSize(),
                )
                Canvas(Modifier.fillMaxSize()) {
                    drawIntoCanvas { target -> titles.forEach { TitleRenderer.draw(target.nativeCanvas, size.width, size.height, it) } }
                }
                if (level < 1) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (1 - level).toFloat())))
            }
            if (clips.isEmpty()) Text("The timeline is empty. Undo to bring clips back.", color = Muted)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("${preciseLabel(playhead)} / ${preciseLabel(duration)}", color = Color.White, fontSize = 14.sp)
            TextButton(
                onClick = {
                    if (editor.playing) editor.pause() else {
                        if (playhead >= duration - 0.05) editor.seek(clips, 0.0)
                        editor.player.play()
                    }
                },
                enabled = clips.isNotEmpty(),
            ) { Text(if (editor.playing) "Pause" else "Play", fontSize = 17.sp) }
            Row {
                TextButton(onClick = { scale = (scale / 1.5f).coerceAtLeast(6f) }) { Text("−", fontSize = 20.sp, modifier = Modifier.semantics { contentDescription = "Zoom timeline out" }) }
                TextButton(onClick = { scale = (scale * 1.5f).coerceAtMost(480f) }) { Text("+", fontSize = 20.sp, modifier = Modifier.semantics { contentDescription = "Zoom timeline in" }) }
            }
        }
        Timeline(
            shown, files, trim?.index ?: location?.index, trim, pixelsPerSecond,
            shift = trim?.takeIf { it.leading }?.let { ((it.clip.start - clips[it.index].start) * pixelsPerSecond).toFloat() } ?: 0f,
            scroll = scroll, touched = { editor.pause() },
            beginTrim = { leading -> location?.let { trim = Trim(it.index, leading, clips[it.index]) } },
            dragTrim = dragTrim, endTrim = endTrim,
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), Arrangement.SpaceEvenly) {
            TextButton(onClick = { commit(TimelineEditing.split(edit, playhead), playhead) },
                enabled = TimelineEditing.split(edit, playhead) != null) { Text("Split") }
            TextButton(onClick = { editor.pause(); editingTitle = true }, enabled = location != null) { Text(if (titles.isEmpty()) "Title" else "Edit title") }
            TextButton(onClick = { editor.pause(); editingTransition = true },
                enabled = location != null && location.index < clips.lastIndex) { Text("Transition") }
            for ((label, step) in listOf("◀ Move" to -1, "Move ▶" to 1)) {
                val target = location?.let { it.index + step }
                TextButton(
                    onClick = {
                        val next = TimelineEditing.move(edit, location!!.index, target!!)
                        commit(next, next?.let { TimelineEditing.clipStart(it.clips, target) + location.offset } ?: playhead)
                    },
                    enabled = target != null && target in clips.indices,
                ) { Text(label) }
            }
            TextButton(onClick = { location?.let { commit(TimelineEditing.delete(edit, it.index), TimelineEditing.clipStart(clips, it.index)) } },
                enabled = location != null) { Text("Delete", color = if (location != null) RecordRed else Color.Unspecified) }
        }
    }

    if (editingTitle && location != null && current != null) {
        TitleDialog(
            existing = titles.firstOrNull(), clip = current, sourceTime = sourceTime, sourceDuration = durations[current.sourceID] ?: current.end,
            save = { commit(TimelineEditing.setText(edit, location.index, it), playhead); editingTitle = false },
            remove = { commit(TimelineEditing.removeText(edit, location.index, it), playhead); editingTitle = false },
            dismiss = { editingTitle = false },
        )
    }
    if (editingTransition && location != null) {
        TransitionDialog(
            current = TimelineEditing.transition(clips, location.index)?.duration,
            choose = { commit(TimelineEditing.setTransition(edit, location.index, it), playhead); editingTransition = false },
            dismiss = { editingTransition = false },
        )
    }

    ExportDialogs(exporter, context) { store.error = it }
}

/** A trim in progress: the clip being changed and its uncommitted bounds. */
private data class Trim(val index: Int, val leading: Boolean, val clip: TimelineClip)

@Composable
private fun Timeline(
    clips: List<TimelineClip>, files: Map<String, File>, current: Int?, trim: Trim?,
    pixelsPerSecond: Float, shift: Float, scroll: ScrollState,
    touched: () -> Unit, beginTrim: (leading: Boolean) -> Unit, dragTrim: (pixels: Float) -> Unit, endTrim: () -> Unit,
) {
    val density = LocalDensity.current
    // The gesture below outlives recompositions; always call the latest callbacks.
    val onTouched by rememberUpdatedState(touched)
    val onBeginTrim by rememberUpdatedState(beginTrim)
    val onDragTrim by rememberUpdatedState(dragTrim)
    val onEndTrim by rememberUpdatedState(endTrim)
    // Where a touch grabs a trim handle of the current clip instead of scrolling.
    val handle by rememberUpdatedState { x: Float, half: Float ->
        current?.takeIf { it in clips.indices }?.let { index ->
            val left = half + (TimelineEditing.clipStart(clips, index) * pixelsPerSecond).toFloat() + shift - scroll.value
            val right = left + (clips[index].duration * pixelsPerSecond).toFloat()
            val (outside, inside) = with(density) { 10.dp.toPx() to 22.dp.toPx() }
            val leading = x in (left - outside)..(left + inside)
            val trailing = x in (right - inside)..(right + outside)
            when {
                leading && trailing -> abs(x - left) < abs(x - right)
                leading -> true
                trailing -> false
                else -> null
            }
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(120.dp).background(Panel).pointerInput(Unit) {
            // The handles move under the finger, so the drag is tracked here,
            // in a coordinate space that stays still.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                onTouched() // scrubbing pauses playback; it stays paused after release
                val leading = handle(down.position.x, size.width / 2f) ?: return@awaitEachGesture
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
        },
    ) {
        val viewport = constraints.maxWidth
        Row(
            Modifier.horizontalScroll(scroll).padding(horizontal = maxWidth / 2).fillMaxHeight().offset { IntOffset(shift.roundToInt(), 0) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            var offset = 0f
            clips.forEachIndexed { index, clip ->
                key(clip.id) { ClipStrip(clip, files.getValue(clip.sourceID), index == current, TimelineEditing.transition(clips, index) != null, offset, pixelsPerSecond, viewport, scroll) }
                offset += (clip.duration * pixelsPerSecond).toFloat()
            }
        }
        Box(Modifier.align(Alignment.Center).width(2.dp).fillMaxHeight().background(Color.White))
        if (clips.isNotEmpty()) {
            val label = trim?.let { "Clip ${it.index + 1}: ${preciseLabel(it.clip.duration)}" } ?: "Clip ${(current ?: 0) + 1} of ${clips.size}"
            Text(label, color = if (trim != null) Color.White else Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp))
        }
    }
}

@Composable
private fun ClipStrip(
    clip: TimelineClip, file: File, current: Boolean, joined: Boolean, offset: Float,
    pixelsPerSecond: Float, viewport: Int, scroll: ScrollState,
) {
    val density = LocalDensity.current
    val width = (clip.duration * pixelsPerSecond).toFloat()
    val tile = with(density) { TileSize.toPx() }
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
    Box(Modifier.width(with(density) { width.toDp() }).border(if (current) 2.dp else 1.dp, if (current) Color.White else Color.Black)) {
        Column {
            Box(Modifier.fillMaxWidth().height(TileSize).clipToBounds().background(Color(0xFF2A2D33))) {
                for (index in visible) {
                    key(index) {
                        val time = (clip.start + (index + 0.5) * tile / pixelsPerSecond).coerceAtMost(clip.end)
                        val image by produceState<Bitmap?>(null, file, (time * 2).roundToInt()) { value = Thumbnails.frame(file, time) }
                        image?.let {
                            Image(it.asImageBitmap(), null, Modifier.offset { IntOffset((index * tile).roundToInt(), 0) }.size(TileSize), contentScale = ContentScale.Crop)
                        }
                    }
                }
            }
            Canvas(Modifier.fillMaxWidth().height(WaveHeight).background(Color(0xFF14161A))) {
                val levels = peaks ?: return@Canvas
                val step = 3.dp.toPx()
                // Draw only the columns near the screen.
                var x = ((scroll.value - viewport - offset).coerceAtLeast(0f) / step).toInt() * step
                val last = minOf(size.width, scroll.value + viewport - offset)
                while (x < last) {
                    val from = clip.start + x / pixelsPerSecond
                    val level = WaveformBuilder.level(levels, from, minOf(clip.end, from + step / pixelsPerSecond))
                    val bar = maxOf(1.dp.toPx(), sqrt(level) * size.height)
                    drawRect(WaveColor, Offset(x, (size.height - bar) / 2), Size(step * 0.6f, bar))
                    x += step
                }
            }
        }
        // Titles as labelled bars over the footage they cover.
        for (title in clip.texts.orEmpty()) {
            val from = maxOf(title.start, clip.start)
            val to = minOf(title.end, clip.end)
            if (to <= from) continue
            Text(
                title.text, color = Color.Black, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Clip,
                modifier = Modifier.offset { IntOffset(((from - clip.start) * pixelsPerSecond).roundToInt(), 0) }
                    .width(with(density) { ((to - from) * pixelsPerSecond).toFloat().toDp() })
                    .background(TitleColor, RoundedCornerShape(3.dp)).padding(horizontal = 3.dp),
            )
        }
        // A transition into the next clip.
        if (joined) {
            Text("Fade", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp).background(Color.White, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp))
        }
        if (current) {
            val grip = Modifier.width(minOf(10.dp, with(density) { (width / 2).toDp() })).height(TileSize + WaveHeight).background(Color.White, RoundedCornerShape(3.dp))
            Box(grip.align(Alignment.CenterStart))
            Box(grip.align(Alignment.CenterEnd))
        }
    }
}

@Composable
private fun <T> Choices(label: String, options: List<Pair<String, T>>, selected: T, choose: (T) -> Unit) {
    Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), Arrangement.spacedBy(8.dp)) {
        options.forEach { (name, value) -> FilterChip(selected = value == selected, onClick = { choose(value) }, label = { Text(name) }) }
    }
}

/** Adds a title at the playhead, or edits the one showing there. */
@Composable
private fun TitleDialog(
    existing: TextOverlay?, clip: TimelineClip, sourceTime: Double, sourceDuration: Double,
    save: (TextOverlay) -> Unit, remove: (String) -> Unit, dismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(existing?.text ?: "") }
    var y by remember { mutableDoubleStateOf(existing?.y ?: 0.5) }
    var size by remember { mutableDoubleStateOf(existing?.size ?: 0.065) }
    // Seconds on screen; 0 keeps the current range, a negative value means the whole clip.
    var length by remember { mutableDoubleStateOf(if (existing == null) 3.0 else 0.0) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (existing == null) "Add title" else "Edit title") },
        text = {
            Column {
                OutlinedTextField(text, { text = it.take(500) }, label = { Text("Text") }, maxLines = 4)
                Choices("Position", listOf("Top" to 0.15, "Center" to 0.5, "Bottom" to 0.85), y) { y = it }
                Choices("Size", listOf("Small" to 0.045, "Medium" to 0.065, "Large" to 0.1), size) { size = it }
                Choices("On screen", (if (existing == null) emptyList() else listOf("As is" to 0.0)) +
                    listOf("2 s" to 2.0, "3 s" to 3.0, "5 s" to 5.0, "Whole clip" to -1.0), length) { length = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    val start = existing?.start ?: sourceTime
                    val (from, to) = when {
                        length < 0 -> clip.start to clip.end
                        length == 0.0 && existing != null -> existing.start to existing.end
                        else -> start to minOf(start + length, maxOf(sourceDuration, clip.end))
                    }
                    // Too close to the end of the recording: show it over the last half second.
                    val begin = if (to - from < 0.1) maxOf(0.0, to - 0.5) else from
                    save((existing ?: TextOverlay()).copy(text = text.trim(), start = begin, end = to, y = y, size = size))
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (existing != null) TextButton(onClick = { remove(existing.id) }) { Text("Remove", color = RecordRed) }
                TextButton(onClick = dismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun TransitionDialog(current: Double?, choose: (Double?) -> Unit, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Transition to the next clip") },
        text = {
            Column {
                Text("Fade through black. Picture and sound fade out, then in.", color = Muted, fontSize = 13.sp)
                for ((label, seconds) in listOf<Pair<String, Double?>>("None" to null, "0.3 s" to 0.3, "0.5 s" to 0.5, "1 s" to 1.0, "2 s" to 2.0)) {
                    TextButton(onClick = { choose(seconds) }) {
                        Text((if (seconds == current) "● " else "○ ") + label, fontSize = 16.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ExportDialogs(exporter: Exporter, context: Context, report: (String) -> Unit) {
    exporter.progress?.let { progress ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Exporting… ${(progress * 100).roundToInt()}%") },
            text = { LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = exporter::cancel) { Text("Cancel") } },
        )
    }
    exporter.result?.let { file ->
        val uri = remember(file) { FileProvider.getUriForFile(context, "${context.packageName}.files", file) }
        fun open(intent: Intent) = try {
            context.startActivity(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            report("No app on this device can open the movie.")
        }
        AlertDialog(
            onDismissRequest = { exporter.result = null },
            title = { Text("Movie exported") },
            text = {
                Column {
                    Text(file.name, color = Muted, fontSize = 13.sp)
                    TextButton(onClick = { open(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")) }) { Text("Play") }
                    TextButton(onClick = {
                        open(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri), null))
                    }) { Text("Share") }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        TextButton(onClick = { exporter.saveToGallery(file) }) { Text("Save to gallery") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { exporter.result = null }) { Text("Done") } },
        )
    }
}
