package app.takes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takes.core.EditState
import app.takes.core.ImageDraft
import app.takes.core.ImageOverlay
import app.takes.core.ImageOverlays
import app.takes.core.TimelineEditing
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** The Image tool's color, on its bars on the timeline, as the original's tint (YouRec/EditorTools.swift:7). */
internal val ImageColor = Color(0xFFFF9F0A)

private val Soft = Color(0xFFA0A4AB)

/**
 * The pictures laid over clips, read from the project's [folder] by their file name, each upright as it was imported,
 * its longer side brought down to [longest] pixels, and kept for the next frame. A file that cannot be read gives none.
 * Not for two threads at once: each painter has its own.
 */
class OverlayPictures(private val folder: File, private val longest: Int = ImageOverlay.MAXIMUM_PIXELS) {
    // Access-ordered: a picture being replaced makes a new one, and old ones go.
    private val kept = object : LinkedHashMap<String, Bitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean = size > 4
    }

    fun bitmap(name: String): Bitmap? = kept[name] ?: decode(File(folder, name))?.also { kept[name] = it }

    private fun decode(file: File): Bitmap? = try {
        if (!file.isFile) null else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > longest) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    companion object {
        /** The longest side a picture is drawn from in the editor's preview, which is never that large on screen. */
        const val PREVIEW_PIXELS = 2048
    }
}

/**
 * The Image tool at work, as the original's tool model keeps it (YouRec/EditorTools.swift:259-333, :417-462): the
 * picture and its motion ([draft]), the seconds of the movie it shows over, the piece it was opened on and the files
 * imported while it is open.
 *
 * @param clipID the clip of the piece an existing picture was opened on; null for a new one, which goes on every clip.
 * @param opened the seconds the picture had when it was opened.
 * @param clips the clips selected when a new picture was added, which alone it goes on (YouRec/EditorView.swift:1012-1016,
 *   YouRec/EditorTools.swift:655); null for every clip its seconds cover.
 */
internal data class Imaging(
    val draft: ImageDraft,
    val range: ClosedFloatingPointRange<Double>,
    val clipID: String?,
    val existing: ImageOverlay?,
    val opened: ClosedFloatingPointRange<Double>?,
    val imported: List<String> = emptyList(),
    val loading: Boolean = false,
    val clips: Set<String>? = null,
) {
    val duration: Double get() = range.endInclusive - range.start
    val chosen: Boolean get() = draft.image.filename.isNotEmpty()
    private val scope: Set<String>? get() = clipID?.let(::setOf) ?: clips

    /** The edit as the preview shows it while the tool is open: an end being edited still, else the motion as it runs. */
    fun shown(edit: EditState): EditState? =
        if (!chosen) null else ImageOverlays.place(edit, draft.image, range, draft.shownMotion(duration), scope, opened)

    /** The edit with the picture as it is now (YouRec/EditorTools.swift:650-674); null when that changes nothing. */
    fun applied(edit: EditState): EditState? =
        if (!chosen) null else ImageOverlays.place(edit, draft.image, range, draft.captured(duration), scope, opened)

    /** The picture moved by a share of the frame and its size multiplied by [zoom], as a drag and a pinch on the preview move it. */
    fun moved(x: Double, y: Double, zoom: Double): Imaging {
        val image = draft.image
        return copy(draft = draft.adjusted(image.copy(
            x = ImageOverlay.snapped(x.coerceIn(0.0, 1.0)), y = ImageOverlay.snapped(y.coerceIn(0.0, 1.0)),
            width = (image.width * zoom).coerceIn(ImageOverlay.MINIMUM_WIDTH, ImageOverlay.MAXIMUM_WIDTH),
        )))
    }

    companion object {
        /** A new picture, over the marked range or for three seconds from the playhead (YouRec/EditorView.swift:1016). */
        fun adding(range: ClosedFloatingPointRange<Double>, clips: Set<String>? = null): Imaging =
            Imaging(ImageDraft(ImageOverlay(filename = "")), range, null, null, null, clips = clips)

        /** The picture [image] of the clip at [index], opened from its bar on that piece. Null when the piece shows none of it. */
        fun opening(clips: List<app.takes.core.TimelineClip>, index: Int, image: ImageOverlay): Imaging? {
            val range = ImageOverlays.range(clips, index, image) ?: return null
            return Imaging(ImageDraft.opening(image), range, clips[index].id, image, range)
        }
    }
}

/**
 * While a picture is worked on, a drag on the preview moves it and a pinch resizes it, a position near the middle
 * snapping to it (YouRec/EditorTools.swift:1207-1236); "Choose an image below" over the picture until one is chosen.
 */
@Composable
internal fun ImageGestures(work: Imaging, change: (Imaging) -> Unit, modifier: Modifier = Modifier) {
    val now by rememberUpdatedState(work)
    val changed by rememberUpdatedState(change)
    // Where the drag is, before snapping to the middle.
    var dragged by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val label = stringResource(R.string.image_position_label)
    Box(
        modifier.fillMaxSize().semantics { contentDescription = label }.pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ ->
                val image = now.draft.image
                if (!now.chosen) return@detectTransformGestures
                val from = dragged?.takeIf { ImageOverlay.snapped(it.first) == image.x && ImageOverlay.snapped(it.second) == image.y } ?: (image.x to image.y)
                val to = (from.first + pan.x / size.width).coerceIn(0.0, 1.0) to (from.second + pan.y / size.height).coerceIn(0.0, 1.0)
                dragged = to
                changed(now.moved(to.first, to.second, zoom.toDouble()))
            }
        },
        Alignment.Center,
    ) {
        if (!work.chosen) {
            Text(
                stringResource(R.string.image_choose_below), color = Color.White,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * The Image tool, in place of the editing tools so the picture stays in view (YouRec/EditorTools.swift:1238-1254): the
 * picture from the system's photo picker, how long it stays, its size, opacity and position, and its motion between two
 * ends with the controls the Crop tool has. Everything happens on [work], which the preview shows; Done makes it one
 * undo step and Cancel drops it, with the pictures imported meanwhile.
 *
 * @param bounds the seconds of the movie it may cover: all of it for a new picture, the clip of the piece that was opened for an existing one.
 * @param playhead where the editor stands now; [jump] moves it there and [playing] says whether it plays.
 */
@Composable
internal fun ImageTool(
    work: Imaging, change: (Imaging) -> Unit, edit: EditState, bounds: ClosedFloatingPointRange<Double>,
    playhead: () -> Double, playing: Boolean, jump: (Double) -> Unit,
    store: ProjectStore, projectID: String, commit: (EditState?) -> Unit, close: () -> Unit, modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(work)
    val changed by rememberUpdatedState(change)
    val scope = rememberCoroutineScope()
    val unreadable = stringResource(R.string.image_unreadable)
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        changed(current.copy(loading = true))
        scope.launch {
            val name = store.importImage(projectID, uri)
            val now = current
            if (name == null) {
                changed(now.copy(loading = false))
                store.error = unreadable
            } else {
                changed(now.copy(draft = now.draft.copy(image = now.draft.image.copy(filename = name)), imported = now.imported + name, loading = false))
            }
        }
    }
    // Parked on an end of the motion, the picture stands where that end is, following it as the timing changes (EditorTools.swift:299-302).
    val parked = work.draft.parked(work.duration)?.let { work.range.start + it }
    LaunchedEffect(parked) { parked?.let { if (abs(it - playhead()) > 0.0005) jump(it) } }
    // Playing, or moving the picture by hand, shows the motion as it runs; when it stops, the picture goes back to the end being edited.
    LaunchedEffect(playing) {
        val now = current
        if (playing) changed(now.copy(draft = now.draft.scrubbed()))
        else if (now.draft.endpoint != null && now.draft.previewEndpoint == null) changed(now.copy(draft = now.draft.copy(previewEndpoint = now.draft.endpoint)))
    }
    val running by rememberUpdatedState(playing)
    LaunchedEffect(Unit) {
        snapshotFlow { playhead() }.collect { at ->
            val now = current
            val stand = now.draft.parked(now.duration)?.let { now.range.start + it } ?: return@collect
            if (!running && abs(at - stand) > 0.0005) changed(now.copy(draft = now.draft.scrubbed()))
        }
    }
    val finish = { kept: String? -> store.discardImages(projectID, current.imported.filter { it != kept }) }
    ImagePanel(
        work, set = { changed(current.copy(draft = current.draft.adjusted(it))) },
        setRange = { changed(current.copy(range = it)) }, bounds = bounds,
        choose = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        motion = { changed(current.copy(draft = it(current.draft))) },
        done = {
            val now = current
            val next = now.applied(edit)
            close()
            commit(next)
            finish(now.draft.image.filename)
        },
        remove = {
            val now = current
            val index = now.clipID?.let { id -> edit.clips.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
            close()
            if (index != null && now.existing != null) commit(ImageOverlays.remove(edit, index, now.existing.id))
            finish(null)
        },
        cancel = { close(); finish(null) },
        modifier = modifier,
    )
}

/** The controls of the Image tool, in the original's order (YouRec/EditorTools.swift:1238-1254, :1094). */
@Composable
private fun ImagePanel(
    work: Imaging, set: (ImageOverlay) -> Unit, setRange: (ClosedFloatingPointRange<Double>) -> Unit, bounds: ClosedFloatingPointRange<Double>,
    choose: () -> Unit, motion: ((ImageDraft) -> ImageDraft) -> Unit,
    done: () -> Unit, remove: () -> Unit, cancel: () -> Unit, modifier: Modifier = Modifier,
) {
    val image = work.draft.image
    var showPosition by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().background(Color(0xFF1B1D21))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = cancel) { Text(stringResource(R.string.common_cancel)) }
            Text(
                stringResource(if (work.existing == null) R.string.image_add else R.string.image_edit), color = Color.White, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            if (work.existing != null) TextButton(onClick = remove) { Text(stringResource(R.string.common_remove), color = RecordRed) }
            TextButton(onClick = done, enabled = work.chosen && !work.loading) { Text(stringResource(R.string.common_done)) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // The motion's ends first, as the original puts its animation controls above the settings.
            AnimationControls(
                work.draft.animation, work.draft.endpoint, work.duration,
                select = { end -> motion { it.selecting(end, work.duration) } },
                remove = { motion { it.removingAnimation() } },
                done = { motion { it.finishing(work.duration) } },
                update = { timing -> motion { it.retimed(timing, work.duration) } },
            )
            val label = stringResource(when {
                work.loading -> R.string.image_loading
                work.chosen -> R.string.image_replace
                else -> R.string.image_choose
            })
            TextButton(onClick = choose, enabled = !work.loading, modifier = Modifier.heightIn(min = 44.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(20.dp))
                Text(label, Modifier.padding(start = 8.dp))
            }
            DurationPicker(work.range, bounds, setRange)
            Text(stringResource(R.string.title_drag_hint), color = Soft, fontSize = 12.sp)
            // On a scale of ratios, from 3% to 300% of the shorter edge (EditorTools.swift:1246).
            val percent = stringResource(R.string.image_percent, (image.width * 100).roundToInt())
            Setting(stringResource(R.string.title_size), stringResource(R.string.image_size_label), percent,
                ln(image.width), ln(ImageOverlay.MINIMUM_WIDTH)..ln(ImageOverlay.MAXIMUM_WIDTH)) { set(image.copy(width = exp(it).coerceIn(ImageOverlay.MINIMUM_WIDTH, ImageOverlay.MAXIMUM_WIDTH))) }
            Setting(stringResource(R.string.image_opacity), stringResource(R.string.image_opacity_label), stringResource(R.string.image_percent, (image.opacity * 100).roundToInt()),
                image.opacity, 0.0..1.0) { set(image.copy(opacity = it.coerceIn(0.0, 1.0))) }
            TextButton(onClick = { showPosition = !showPosition }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text((if (showPosition) "▾ " else "▸ ") + stringResource(R.string.title_position))
            }
            if (showPosition) {
                Setting(stringResource(R.string.title_across), stringResource(R.string.image_across_label), stringResource(R.string.image_percent, (image.x * 100).roundToInt()),
                    image.x, 0.0..1.0) { set(image.copy(x = it.coerceIn(0.0, 1.0))) }
                Setting(stringResource(R.string.title_down), stringResource(R.string.image_down_label), stringResource(R.string.image_percent, (image.y * 100).roundToInt()),
                    image.y, 0.0..1.0) { set(image.copy(y = it.coerceIn(0.0, 1.0))) }
                TextButton(onClick = { set(image.copy(x = 0.5, y = 0.5)) }, Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.image_centre)) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** A slider with its name and value above it, and the name a screen reader says. */
@Composable
private fun Setting(name: String, spoken: String, value: String, at: Double, range: ClosedFloatingPointRange<Double>, change: (Double) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(name, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(value, color = Soft, fontSize = 14.sp)
        }
        Slider(
            value = at.toFloat().coerceIn(range.start.toFloat(), range.endInclusive.toFloat()),
            onValueChange = { change(it.toDouble()) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            modifier = Modifier.height(36.dp).semantics { contentDescription = spoken; stateDescription = value },
        )
    }
}

/** Where a new picture goes: the marked range, else three seconds of the movie from the playhead, as a new title. */
internal fun newImageRange(clips: List<app.takes.core.TimelineClip>, playhead: Double, marked: ClosedFloatingPointRange<Double>?): ClosedFloatingPointRange<Double> =
    marked ?: TimelineEditing.newTitleRange(clips, playhead)
