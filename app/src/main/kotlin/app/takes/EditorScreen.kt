@file:OptIn(UnstableApi::class)

package app.takes

import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.Icons
import androidx.compose.ui.semantics.Role
import java.util.Date
import java.text.DateFormat
import app.takes.core.UnusedTakes
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import app.takes.core.TextOverlay
import app.takes.core.TransitionSettings
import app.takes.core.TransitionStyle
import androidx.compose.material3.Slider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import app.takes.core.AudioGainDrag
import app.takes.core.AudioItem
import android.net.Uri
import androidx.compose.runtime.mutableIntStateOf
import app.takes.core.PauseDetector
import app.takes.core.PauseSettings
import app.takes.core.SourceInterval
import app.takes.core.SourceWaveform
import android.os.Build
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.asComposeRenderEffect
import app.takes.core.ColorLook
import app.takes.core.ColorSettings
import app.takes.core.ImportedLUT
import app.takes.core.CropSettings
import app.takes.core.CropViewport
import app.takes.core.PictureFrame
import app.takes.core.PictureFraming
import app.takes.core.Size as FrameSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import android.app.Activity
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import app.takes.core.EditScope
import app.takes.core.EditState
import app.takes.core.EditTargets
import app.takes.core.Project
import app.takes.core.RangeEditing
import app.takes.core.RangeMarks
import app.takes.core.RenderPlan
import app.takes.core.ReviewWindows
import app.takes.core.TimelineClip
import app.takes.core.TimelineEditing
import app.takes.core.TimelineNavigation
import app.takes.core.TimelineSnapping
import app.takes.core.TitleMotionRanges
import app.takes.core.TrackRenderPlan
import app.takes.core.TransitionAxis
import app.takes.core.TransitionDefaults
import app.takes.core.TransitionFraming
import app.takes.core.TransitionGeometry
import app.takes.core.TransitionLayout
import app.takes.core.TransitionScene
import app.takes.core.TransitionTiming
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

private val Muted = Color(0xFFA0A4AB)
internal val TitleColor = Color(0xFFFFD54A)
internal val BookmarkColor = Color(0xFFFF7A45)

internal fun preciseLabel(seconds: Double): String {
    val tenths = (seconds.coerceAtLeast(0.0) * 10).toLong()
    return "%d:%02d.%d".format(tenths / 600, tenths / 10 % 60, tenths % 10)
}

@Composable
fun EditorScreen(store: ProjectStore, exporter: Exporter, modifier: Modifier = Modifier, record: () -> Unit = {}) {
    val project = store.selected
    if (project == null || project.sources.isEmpty()) {
        Box(modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
            Text(stringResource(if (project == null) R.string.editor_no_project else R.string.editor_no_recordings), color = Muted)
        }
        return
    }
    key(project.id) { Editor(project, store, exporter, modifier, record) }
}

@Composable
private fun Editor(project: Project, store: ProjectStore, exporter: Exporter, modifier: Modifier, record: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    // The Transition tool: the join it is working on and the settings not saved yet. The whole
    // editor shows the timeline as it would be with them, since a transition moves what follows it.
    var transitioning by remember { mutableStateOf<Transitioning?>(null) }
    val saved = project.edit
    // A cut being previewed: until it has played, the editor shows and plays the edit as it would be without the range.
    var auditioning by remember { mutableStateOf<Audition?>(null) }
    val edit = remember(saved, transitioning?.join, transitioning?.shown, auditioning) { auditioning?.state ?: transitioning?.let { drafted(saved, it.join, it.shown) } ?: saved }
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
        onDispose { owner.lifecycle.removeObserver(observer); editor.release() }
    }
    // Watching the movie or waiting for an export must not let the screen turn off.
    val view = LocalView.current
    // The Subtitles tool: the edit it is working on, shown in the preview and not saved until applied.
    var subtitling by remember { mutableStateOf<EditState?>(null) }
    var subtitleWork by remember { mutableStateOf(false) } // downloading the model or transcribing
    // The Title tool: the title it is working on, shown in the preview and not saved until confirmed.
    var titling by remember { mutableStateOf<Titling?>(null) }
    // Where a preview of a title's motion stops by itself.
    var auditionEnd by remember { mutableStateOf<Double?>(null) }
    // Where the picture goes back to when it has stopped there: the start of a transition played by itself, as in the original.
    var auditionReturn by remember { mutableStateOf<Double?>(null) }
    val busy = editor.playing || exporter.progress != null || subtitleWork
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }
    val sounds = edit.audio.orEmpty()
    // Noise reduction runs in the background, one recording at a time. Until a clip's
    // cleaned sound is ready the preview plays the recorded sound; then the players reload.
    // The render plan of the edit as it is shown, before any cleaned sound is at hand: it marks every clip that asks for noise reduction.
    val asked = remember(edit) { TrackRenderPlan.of(edit) }
    val cleaning = asked.pictures.filter { it.soundRemoved }.map { files.getValue(it.sourceID) to (asked.clips[it.index].audio?.denoise ?: 0.0) }.distinct()
    var cleaned by remember { mutableIntStateOf(0) }
    var cleaningProgress by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(cleaning) {
        try {
            for ((file, amount) in cleaning) {
                if (Denoiser.ready(file, amount) != null) continue
                cleaningProgress = 0f
                if (Denoiser.process(file, amount) { cleaningProgress = it } != null) cleaned++
            }
        } finally {
            cleaningProgress = null
        }
    }
    // The cleaned sound that is ready, by clip, and the plan the preview plays: a clip whose cleaned sound is not ready yet plays its recorded sound.
    val clean = remember(asked, cleaned, files) {
        asked.pictures.filter { it.soundRemoved }.mapNotNull { clip ->
            Denoiser.ready(files.getValue(clip.sourceID), asked.clips[clip.index].audio?.denoise ?: 0.0)?.let { clip.index to it }
        }.toMap()
    }
    val plan = remember(asked, clean) { if (asked.pictures.none { it.soundRemoved }) asked else TrackRenderPlan.of(edit, clean.keys) }
    LaunchedEffect(clips, sounds, cleaned) {
        playhead = playhead.coerceIn(0.0, duration)
        editor.load(plan, files, clean, playhead)
    }
    // The Crop tool: the framing it is working on and the clips it goes on, shown in the preview and not saved until applied.
    var cropping by remember { mutableStateOf<Cropping?>(null) }
    // The timeline scrolls under a fixed playhead. Playback and edits move the
    // scroll position; a drag moves the playhead and seeks.
    LaunchedEffect(clips, pixelsPerSecond, editor.playing) {
        withFrameNanos { } // lay out the new clip widths first
        scroll.scrollTo((playhead.coerceIn(0.0, duration) * pixelsPerSecond).roundToInt())
        while (editor.playing) {
            withFrameNanos { }
            playhead = editor.tick().coerceIn(0.0, duration)
            scroll.scrollTo((playhead * pixelsPerSecond).roundToInt())
            auditionEnd?.let {
                if (playhead >= it) {
                    auditionEnd = null
                    editor.pause()
                    auditionReturn?.let { back ->
                        auditionReturn = null
                        playhead = back
                        editor.seek(back)
                        scroll.scrollTo((back * pixelsPerSecond).roundToInt())
                    }
                }
            }
        }
    }
    LaunchedEffect(clips, pixelsPerSecond) {
        snapshotFlow { scroll.value }.drop(1).collect { pixels ->
            val time = (pixels / pixelsPerSecond).toDouble().coerceIn(0.0, duration)
            // Our own scrollTo lands within half a pixel of the playhead.
            if (!editor.playing && abs(time - playhead) > 0.6 / pixelsPerSecond) {
                playhead = time
                editor.seek(time)
                // Moving the picture by hand leaves the end of a crop's motion it was parked on: the motion is shown as it runs.
                cropping?.let { if (it.draft.previewEndpoint != null) cropping = it.copy(draft = it.draft.scrubbed()) }
            }
        }
    }

    // Previewing a cut: plays across it, then puts back the edit as it is and the playhead where it was.
    LaunchedEffect(auditioning) {
        val audition = auditioning ?: return@LaunchedEffect
        try {
            auditionEnd = audition.review.end
            auditionReturn = null
            playhead = audition.review.start
            editor.play(audition.review.start)
            snapshotFlow { editor.playing }.first { !it }
        } finally {
            auditionEnd = null
            auditioning = null
            playhead = audition.review.returnTo
        }
    }
    // Meanwhile the first touch anywhere ends the preview and does nothing else.
    val previewing = if (auditioning == null) Modifier else Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).consume()
            editor.pause()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
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
    // The marked range and the selection: the editor's own, never saved. They follow the edit as it changes.
    var marks by remember { mutableStateOf(RangeMarks()) }
    LaunchedEffect(saved) { marks = marks.retained(saved) }
    // The last thing that happened, said beside the Mark button for a moment.
    var feedback by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(feedback) { if (feedback != null) { kotlinx.coroutines.delay(3000); feedback = null } }
    // What Sound, Color, Crop and Speed apply to while something is marked or selected: the clips inside,
    // cut at the two ends of a range. Null: the clip under the playhead, as always.
    val toolScope = remember(edit, marks) { if (marks.scope == EditScope.Whole) null else RangeEditing.scoped(edit, marks.scope) }
    // The sounds on the audio tracks a tool for sound applies to as well: the ones selected whole.
    val toolSounds = (marks.scope as? EditScope.Selected)?.targets?.audio.orEmpty()
    val toolScopeLabel = when (val scope = marks.scope) {
        is EditScope.Range -> stringResource(R.string.selection_tool_range, preciseLabel(scope.range.start), preciseLabel(scope.range.end))
        is EditScope.Selected -> stringResource(R.string.selection_tool_selected, selectionCount(scope.targets))
        EditScope.Whole -> null
    }
    // A tool cuts the clips at the ends of the range, and the pieces inside are new clips: a range limited
    // to some clips takes them in, as the original carries a range from the clips to their pieces.
    val applyScoped = { scoped: RangeEditing.Scoped, next: EditState?, time: Double ->
        if (next != null) marks.rangeTargets?.let { marks = marks.copy(rangeTargets = it.copy(clips = it.clips + scoped.clips)) }
        commit(next, time)
    }
    // The clip a tool shows the settings of: the one of its scope under the playhead, else the first.
    val toolSubject = toolScope?.let { scoped ->
        val under = TimelineEditing.locate(scoped.state.clips, playhead)?.let { scoped.state.clips[it.index] }?.takeIf { it.id in scoped.clips }
        under ?: scoped.state.clips.firstOrNull { it.id == scoped.clips.firstOrNull() }
    }

    // Trim handles change the clip under the playhead. The draft is shown in
    // the timeline while dragging and becomes one undo step on release.
    val durations = remember(project.sources) { project.sources.associate { it.id to it.duration } }
    var trim by remember { mutableStateOf<Trim?>(null) }
    // The draft is what the trim would leave: a transition the clip no longer has the footage for is gone from it, as it will be.
    val shown = trim?.takeIf { it.index in clips.indices }?.let { draft ->
        TimelineEditing.trim(edit, draft.index, draft.clip.start, draft.clip.end, durations[clips[draft.index].sourceID] ?: clips[draft.index].end)?.clips
    } ?: clips
    val dragTrim = drag@{ pixels: Float ->
        val draft = trim ?: return@drag
        val original = clips.getOrNull(draft.index) ?: return@drag
        val delta = pixels / pixelsPerSecond * original.rate // the strip is drawn in timeline seconds
        val limit = durations[original.sourceID] ?: original.end
        val next = if (draft.leading) TimelineEditing.trimmed(original, original.start + delta, original.end, limit)
        else TimelineEditing.trimmed(original, original.start, original.end + delta, limit)
        trim = draft.copy(clip = next)
        // Show the frame at the moving edge while it is inside the current clip.
        val edge = if (draft.leading) next.start else next.end - 0.03
        if (edge in original.start..original.end) editor.seek(TimelineEditing.clipStart(clips, draft.index) + original.offset(edge))
    }
    val endTrim = end@{
        val draft = trim ?: return@end
        trim = null
        val original = clips.getOrNull(draft.index) ?: return@end
        val base = TimelineEditing.clipStart(clips, draft.index)
        val next = TimelineEditing.trim(edit, draft.index, draft.clip.start, draft.clip.end, durations[original.sourceID] ?: original.end)
        if (next == null) { editor.seek(playhead); return@end }
        // Keep the same frame under the playhead when it survives the trim.
        val offset = (playhead - base - original.offset(draft.clip.start)).coerceIn(0.0, draft.clip.duration - 0.01)
        val moved = base + offset - playhead
        commit(next, base + offset)
        scroll.dispatchRawDelta((moved * pixelsPerSecond).toFloat()) // no one-frame jump before the scroll catches up
    }

    // What the playhead shows on top of the picture: titles and the fade of a transition.
    val canvas by produceState<Pair<Int, Int>?>(null, clips.firstOrNull()?.sourceID) {
        value = clips.firstOrNull()?.let { files[it.sourceID] }?.let { withContext(Dispatchers.IO) { displaySize(it) } }
    }
    // The upright size of each recording, for framing.
    val sizes by produceState(emptyMap<String, FrameSize>(), project.sources) {
        value = withContext(Dispatchers.IO) {
            project.recordings.mapNotNull { source ->
                displaySize(store.file(project, source))?.let { source.id to FrameSize(it.first.toDouble(), it.second.toDouble()) }
            }.toMap()
        }
    }
    // The frames per second of each recording, and from them the frame rate the movie will have with the export
    // settings as they are: a flash cut counts its frames in it, here as in the export.
    val rates by produceState(emptyMap<String, Double>(), project.sources) {
        value = withContext(Dispatchers.IO) { project.recordings.mapNotNull { source -> frameRate(store.file(project, source))?.let { source.id to it } }.toMap() }
    }
    val ceiling = project.exportSettings.frameRate
    val frames = remember(clips, rates, ceiling) {
        val movie = plan.frameRate(plan.pictures.map { rates[it.sourceID] }, ceiling).toDouble()
        val rate: (TransitionLayout.Join) -> Double = { movie }
        rate
    }
    var pacing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val unusedFailed = stringResource(R.string.unused_failed)
    // The unused takes chosen so far, in the order they will play; null while the list is closed.
    var choosing by remember { mutableStateOf<List<String>?>(null) }
    val current = location?.let { clips[it.index] }
    val sourceTime = if (location != null && current != null) current.sourceTime(location.offset) else 0.0
    // Which deck shows each clip, and the clip each deck is playing now or holding the last frame of.
    val deckClips = List(2) { deck ->
        plan.onDeck(deck, playhead)?.index ?: plan.held(deck, playhead)?.index ?: location?.index?.takeIf { plan.pictures[it].deck == deck }
    }
    // What each deck sounds like: its clip's own level and what the transitions at its ends leave of it.
    val deckSounds = List(2) { deck -> plan.deckLevel(deck, playhead) }
    // The color each deck shows.
    val folder = remember(project.id) { store.disk.folder(project.id) }
    val deckColors = deckClips.map { index -> index?.let { plan.pictures[it].color } }
    // The crop a clip shows at the playhead and the source second it is sampled at: its own, or while the Crop tool
    // is open what the tool would leave on it.
    val cropOf = { index: Int ->
        val clip = plan.clips[index]
        val start = plan.pictures[index].start
        val offset = (playhead - start).coerceIn(0.0, clip.duration)
        val shows = PictureFraming.motionTime(clip, offset)
        val framed = cropping
        (if (framed != null) framed.shown(clip, shows, start + offset) else clip.crop) to shows
    }
    // The framing each deck shows at this moment, for a frame of the size it is drawn in: a crop is sampled at the
    // source second its clip shows, so one that moves between two poses moves as the footage plays or is scrubbed.
    val deckFrames = deckClips.map { index ->
        val size = index?.let { sizes[plan.pictures[it].sourceID] }
        val (crop, shows) = index?.let(cropOf) ?: (null to 0.0)
        if (crop == null || size == null) null else {
            val framing: (FrameSize) -> PictureFrame = { frame -> PictureFraming.frame(crop, shows, size, frame) }
            framing
        }
    }
    SideEffect {
        deckSounds.forEachIndexed(editor::setSound)
        for (track in 0 until AudioItem.TRACKS) {
            editor.setTrackSound(track, plan.trackLevel(track, playhead))
        }
    }
    // The titles under the playhead, with the one being worked on as it is now.
    val titledEdit = titling?.applied(edit)
    val titled = titledEdit?.clips ?: clips
    val titlePlan = remember(titledEdit, plan) { titledEdit?.let { TrackRenderPlan.of(it) } ?: plan }
    val shownTitles = titlePlan.titlesShown(playhead, frames)
    val titlePainter = remember { TitlePainter(context.assets) }
    // Taking a color from the picture: the preview as it is on screen, without the titles over it.
    var previewBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var sampling by remember { mutableStateOf(false) }
    val samplePicture: suspend () -> Bitmap? = sample@{
        val window = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()?.window
        val area = previewBounds
        if (window == null || area == null || area.width < 2 || area.height < 2) return@sample null
        sampling = true
        try {
            repeat(2) { withFrameNanos { } } // let the titles leave the screen
            suspendCancellableCoroutine { waiting ->
                val picture = Bitmap.createBitmap(area.width.toInt(), area.height.toInt(), Bitmap.Config.ARGB_8888)
                val part = android.graphics.Rect(area.left.toInt(), area.top.toInt(), area.left.toInt() + picture.width, area.top.toInt() + picture.height)
                try {
                    PixelCopy.request(window, part, picture, { result -> waiting.resume(picture.takeIf { result == PixelCopy.SUCCESS }) }, Handler(Looper.getMainLooper()))
                } catch (_: IllegalArgumentException) {
                    waiting.resume(null)
                }
            }
        } finally {
            sampling = false
        }
    }
    // Subtitles, laid out once for the movie's shape and drawn by the code the export uses.
    val captionPlan = remember(subtitling, plan) { subtitling?.let { TrackRenderPlan.of(it) } ?: plan }
    val captions = captionPlan.captions
    val captionLayout = remember(captions, captionPlan.subtitles?.style, captionPlan.subtitles?.resolvedFont, canvas) {
        val look = captionPlan.subtitles
        val shape = canvas
        if (look == null || shape == null) null else SubtitleLayout(captions, look, shape.first, shape.second)
    }
    val scope = rememberCoroutineScope()
    val jump = { time: Double ->
        editor.pause()
        playhead = time
        editor.seek(time)
        scope.launch { scroll.scrollTo((time * pixelsPerSecond).roundToInt()) }
        Unit
    }
    // The stops of the previous and next change buttons, from the edit as it is shown.
    val navigation = remember(edit) { TimelineNavigation.of(edit) }
    // The magnet: when a scroll of the timeline by hand ends, the playhead settles on a clip edge or a bookmark
    // within reach on screen, as in the original (EditorTimeline.swift:515-525).
    val snap = remember { SnapSetting(context) }
    val snapNote = stringResource(R.string.editor_snap_note)
    val snapping by rememberUpdatedState(Triple(snap.on, edit, scale))
    val settle by rememberUpdatedState(jump)
    LaunchedEffect(scroll) {
        var scrubbed = false
        launch { scroll.interactionSource.interactions.collect { if (it is DragInteraction.Start) scrubbed = true } }
        snapshotFlow { scroll.isScrollInProgress }.collect { moving ->
            if (!moving && scrubbed) {
                scrubbed = false
                val (on, shown, zoom) = snapping
                if (on && !editor.playing) {
                    val target = TimelineSnapping.snap(playhead, zoom.toDouble(), TimelineSnapping.points(shown))
                    if (target != playhead) settle(target.coerceIn(0.0, shown.duration))
                }
            }
        }
    }
    var choosingExport by remember { mutableStateOf(false) }
    var findingPauses by remember { mutableStateOf(false) }
    var editingColor by remember { mutableStateOf(false) }
    var editingAudio by remember { mutableStateOf(false) }
    var leveling by remember { mutableStateOf<Leveling?>(null) }
    val levelMeasuring = remember { java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Job?>(null) }
    var chosenSound by remember { mutableStateOf<String?>(null) }   // the audio item being edited
    var shapingSound by remember { mutableStateOf(false) }
    val pickSound = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) store.importAudio(project.id, uri, playhead)
    }
    val soundName = stringResource(R.string.editor_sound_default_name)
    val names = remember(project.sources, soundName) { project.sources.associate { it.id to (it.originalName ?: soundName) } }

    Column(modifier.fillMaxSize().then(previewing)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(project.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = { editor.pause(); marks = marks.cleared(); store.undo(project.id) }, enabled = project.canUndo && subtitling == null && titling == null && transitioning == null) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.editor_undo)) }
            TextButton(onClick = { editor.pause(); marks = marks.cleared(); store.redo(project.id) }, enabled = project.canRedo && subtitling == null && titling == null && transitioning == null) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.editor_redo)) }
            SnapToggle(snap.on, snap::toggle) { store.error = snapNote }
            Button(onClick = { editor.pause(); choosingExport = true }, modifier = Modifier.padding(start = 4.dp), enabled = clips.isNotEmpty() && subtitling == null && titling == null && transitioning == null) { Text(stringResource(R.string.editor_export)) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), Alignment.Center) {
            // The export canvas: the first clip's shape. Other clips fit inside it.
            Box((canvas?.let { Modifier.aspectRatio(it.first.toFloat() / it.second) } ?: Modifier.fillMaxSize()).clipToBounds().onGloballyPositioned { previewBounds = it.boundsInWindow() }) {
                val effects = deckColors.map { color ->
                    remember(color, folder) {
                        if (color != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ColorLuts.previewEffect(color, folder).asComposeRenderEffect() else null
                    }
                }
                // Where a clip's own picture lies in the frame: all of it when cropped, else fitted inside it.
                val content = { index: Int, frame: FrameSize -> RenderPlan.content(frame, sizes[plan.pictures[index].sourceID], cropped = cropOf(index).first != null) }
                DeckPictures(editor.decks, effects, deckFrames, moment = { frame -> plan.moment(playhead, frame, frames) }, content = content, held = editor.heldPictures)
                // While a title is being worked on, a drag on the picture moves it and a pinch resizes it.
                val work by rememberUpdatedState(titling)
                var dragged by remember { mutableStateOf<Offset?>(null) } // where the drag is, before snapping to the middle
                val moving = if (titling == null) Modifier else Modifier.pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val now = work ?: return@detectTransformGestures
                        fun snapped(value: Float) = if (abs(value - 0.5f) < 0.015f) 0.5 else value.toDouble()
                        val from = dragged?.takeIf { snapped(it.x) == now.title.x && snapped(it.y) == now.title.y } ?: Offset(now.title.x.toFloat(), now.title.y.toFloat())
                        val to = Offset((from.x + pan.x / size.width).coerceIn(0f, 1f), (from.y + pan.y / size.height).coerceIn(0f, 1f))
                        dragged = to
                        titling = now.copy(title = now.title.copy(x = snapped(to.x), y = snapped(to.y), size = (now.title.size * zoom).coerceIn(0.02, 0.3)))
                    }
                }
                // While a split screen is being worked on, a drag on a half pans its picture, a pinch zooms it and a double tap resets it.
                val shaping by rememberUpdatedState(transitioning)
                val framing = if (transitioning?.settings?.style != TransitionStyle.Split) Modifier else {
                    fun framed(at: Offset, width: Int, height: Int, change: (TransitionFraming, FrameSize, FrameSize) -> TransitionFraming) {
                        val now = shaping ?: return
                        val settings = now.settings
                        val second = if (settings.axis == TransitionAxis.Horizontal) at.x >= width * settings.divider else at.y >= height * settings.divider
                        // At an opening and at a close one half is a color, with nothing to frame.
                        val sides = TimelineEditing.layout(clips).join(now.join)
                        val clip = (if (second) sides?.incoming else sides?.outgoing) ?: return
                        if (clip !in clips.indices) return
                        val frame = FrameSize(width.toDouble(), height.toDouble())
                        val halves = TransitionGeometry.splitRects(settings, frame)
                        val next = change(if (second) settings.second else settings.first, content(clip, frame).size, (if (second) halves.second else halves.first).size)
                        transitioning = now.changed(if (second) settings.copy(second = next) else settings.copy(first = next))
                    }
                    Modifier.pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            framed(centroid, size.width, size.height) { framing, source, viewport ->
                                // As the original moves it: by the finger's travel in the clip's own pixels, kept inside the clip.
                                val scale = viewport.width / maxOf(1.0, framing.crop(source, viewport).width)
                                val moved = framing.copy(
                                    x = (framing.x - pan.x / maxOf(1.0, scale * source.width)).coerceIn(0.0, 1.0),
                                    y = (framing.y - pan.y / maxOf(1.0, scale * source.height)).coerceIn(0.0, 1.0),
                                    zoom = (framing.zoom * zoom).coerceIn(1.0, 4.0),
                                )
                                val kept = moved.crop(source, viewport)
                                moved.copy(x = (kept.midX / source.width).coerceIn(0.0, 1.0), y = (kept.midY / source.height).coerceIn(0.0, 1.0))
                            }
                        }
                    }.pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { at -> framed(at, size.width, size.height) { _, _, _ -> TransitionFraming() } })
                    }
                }
                // While a clip is being cropped, a drag on the picture moves the footage under the frame and a pinch zooms it.
                val framed by rememberUpdatedState(cropping)
                val sized by rememberUpdatedState(sizes to canvas)
                val reframing = if (cropping == null) Modifier else Modifier.pointerInput(Unit) {
                    cropGestures(
                        viewport = {
                            val (known, shape) = sized
                            val source = framed?.on?.active(playhead)?.let { known[it.clip.sourceID] }
                            if (source == null || shape == null) null
                            else CropViewport(FrameSize(size.width.toDouble(), size.height.toDouble()), source, FrameSize(shape.first.toDouble(), shape.second.toDouble()), inset = 0.0)
                        },
                        crop = { framed?.draft?.crop ?: CropSettings() },
                        change = { next -> framed?.let { cropping = it.copy(draft = it.draft.adjusted(next)) } },
                    )
                }
                Canvas(Modifier.fillMaxSize().then(moving).then(framing).then(reframing)) {
                    if (sampling) return@Canvas
                    if (cropping != null) cropGuides()
                    drawIntoCanvas { target ->
                        titlePainter.draw(target.nativeCanvas, size.width, size.height, shownTitles)
                        captionPlan.subtitles?.let { captionLayout?.draw(target.nativeCanvas, size.width, size.height, playhead, it) }
                    }
                }
            }
            if (clips.isEmpty()) Text(stringResource(R.string.editor_timeline_empty), color = Muted)
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("${preciseLabel(playhead)} / ${preciseLabel(duration)}", color = Color.White, fontSize = 14.sp)
            val tight = PaddingValues(horizontal = 6.dp)
            val small = Modifier.defaultMinSize(minWidth = 40.dp)
            // As the original's transport: the bookmarks in one menu, and on either side of Play the previous and the
            // next change, which a tap takes to the nearest cut or change of treatment and a hold to the nearest change only.
            val toChange = { forward: Boolean, effectsOnly: Boolean -> navigation.destination(playhead, forward, effectsOnly)?.let(jump); Unit }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val under = location?.let { TimelineEditing.bookmarkAt(clips, it.index, sourceTime) }
                BookmarksMenu(
                    under = under != null, canToggle = location != null,
                    toggle = { location?.let { commit(TimelineEditing.toggleBookmark(edit, it.index, sourceTime), playhead) } },
                    previous = TimelineEditing.previousBookmark(clips, playhead), next = TimelineEditing.nextBookmark(clips, playhead),
                    times = TimelineEditing.bookmarkTimes(clips), label = ::preciseLabel, go = jump,
                )
                ChangeButton(forward = false, enabled = navigation.destination(playhead, false) != null) { toChange(false, it) }
            }
            TextButton(
                onClick = {
                    auditionEnd = null
                    val framed = cropping
                    if (editor.playing) editor.pause()
                    // The Crop tool plays what it works on and stops at its end, as the original's preview does.
                    else if (framed != null) {
                        auditionEnd = (framed.on.end - PauseDetector.FRAME / 2).takeIf { framed.on.end < duration - 0.0005 }
                        val from = framed.playFrom(playhead)
                        playhead = from
                        editor.play(from)
                    } else editor.play(if (playhead >= duration - 0.05) 0.0 else playhead)
                },
                enabled = clips.isNotEmpty(), contentPadding = tight,
            ) { Icon(if (editor.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(if (editor.playing) R.string.editor_pause else R.string.common_play), Modifier.size(32.dp)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChangeButton(forward = true, enabled = navigation.destination(playhead, true) != null) { toChange(true, it) }
                val zoomOutLabel = stringResource(R.string.editor_zoom_timeline_out)
                val zoomInLabel = stringResource(R.string.editor_zoom_timeline_in)
                TextButton(onClick = { scale = (scale / 1.5f).coerceAtLeast(6f) }, small, contentPadding = tight) { Text("−", fontSize = 20.sp, modifier = Modifier.semantics { contentDescription = zoomOutLabel }) }
                TextButton(onClick = { scale = (scale * 1.5f).coerceAtMost(480f) }, small, contentPadding = tight) { Text("+", fontSize = 20.sp, modifier = Modifier.semantics { contentDescription = zoomInLabel }) }
            }
        }
        cleaningProgress?.let {
            Text(stringResource(R.string.editor_reducing_noise, (it * 100).roundToInt()), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, bottom = 2.dp))
        }
        // A level being chosen by dragging a waveform up or down: the original's quick volume (EditorTimeline.swift:609-687).
        val leveled = leveling
        val levelChanged = stringResource(R.string.sound_level_changed)
        val levelGesture = LevelGesture(
            begin = begin@{ clip, sound, time ->
                if (subtitling != null || titling != null || transitioning != null || cropping != null || trim != null) return@begin false
                // The marked range or the whole selection when the finger goes down inside it; else the one waveform under it,
                // which becomes what is selected.
                val ranged = toolScope?.takeIf { marks.range?.let { clip != null && time >= it.start && time < it.end } == true && marks.rangeTargets?.clips?.contains(clip) != false && it.clips.isNotEmpty() }
                val selected = marks.range == null && marks.start == null && (clip != null && clip in marks.selected.clips || sound != null && sound in marks.selected.audio)
                val base = ranged?.state ?: edit
                val targets = when {
                    ranged != null -> soundTargets(base, ranged.clips, emptyList(), { "" }) { "" }
                    selected -> soundTargets(base, marks.selected.clips, marks.selected.audio, { "" }) { "" }
                    else -> soundTargets(base, listOfNotNull(clip), listOfNotNull(sound), { "" }) { "" }
                }
                if (targets.isEmpty()) return@begin false
                if (ranged == null && !selected) marks = marks.selecting(clip, sound)
                view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                leveling = Leveling(base, targets, AudioGainDrag.begin(targets.map { it.audio.volumeDB }))
                // How far the level can go, for the warning beside the finger: measured while the drag goes on.
                levelMeasuring.set(scope.launch {
                    val measured = try { Headroom.measure(base, files, targets.map { it.id }.toSet(), targets.minOf { it.start }..targets.maxOf { it.end }) } catch (stopped: kotlinx.coroutines.CancellationException) { throw stopped } catch (problem: Exception) { null }
                    leveling?.let { if (it.targets === targets) leveling = it.copy(headroom = measured) }
                })
                true
            },
            drag = { up, sideways, at, height ->
                leveling?.let { current ->
                    val next = current.adjustment.updated(up)
                    if (next.value != current.adjustment.value) view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    leveling = current.copy(adjustment = next, cancelling = AudioGainDrag.cancels(sideways), at = at, height = height)
                }
            },
            rest = {
                // Resting against the top or the bottom of the screen, the level keeps stepping.
                leveling?.takeIf { !it.cancelling }?.let { current ->
                    val density = view.resources.displayMetrics.density
                    @Suppress("DEPRECATION") val insets = view.rootWindowInsets
                    @Suppress("DEPRECATION") val top = (insets?.systemWindowInsetTop ?: 0) / density
                    @Suppress("DEPRECATION") val bottom = (view.rootView.height - (insets?.systemWindowInsetBottom ?: 0)) / density
                    val delta = AudioGainDrag.edgeStep(current.height, top.toDouble(), bottom.toDouble())
                    if (delta != 0.0) leveling = current.copy(adjustment = current.adjustment.steppedAtEdge(delta))
                }
            },
            end = { lifted ->
                levelMeasuring.get()?.cancel()
                leveling?.let { current ->
                    leveling = null
                    current.adjustment.committed(cancelled = !lifted || current.cancelling)?.let { level ->
                        withSound(current.base, current.targets) { it.audio.copy(volumeDB = level) }?.let { next ->
                            commit(next, playhead)
                            // As in the original, what was changed is what is selected afterwards.
                            marks = RangeMarks(selected = EditTargets(current.targets.filter { it.clip }.map { it.id }.toSet(), current.targets.filter { !it.clip }.map { it.id }.toSet()))
                            feedback = levelChanged.format(levelLabel(level))
                        }
                    }
                }
            },
            readout = leveled?.readout(stringResource(R.string.sound_level_mixed), stringResource(R.string.common_cancel)),
        )
        Timeline(
            leveled?.shown(shown) ?: shown, files, leveled?.shownSounds(sounds) ?: sounds, names,
            // A tap picks one item, or lets go of it when it is all that is picked; a long press adds it or takes it out.
            select = { clip, audio ->
                if (subtitling == null && titling == null && transitioning == null && cropping == null) {
                    val one = EditTargets(setOfNotNull(clip), setOfNotNull(audio))
                    marks = if (marks.range == null && marks.start == null && marks.selected == one) marks.cleared() else marks.selecting(clip, audio)
                }
            },
            toggle = { clip, audio ->
                if (subtitling == null && titling == null && transitioning == null && cropping == null) {
                    editor.pause()
                    marks = if (clip != null) marks.togglingClip(clip) else if (audio != null) marks.togglingAudio(audio) else marks
                }
            },
            // A tap beside the clips and sounds goes there and lets go of everything; above them it only goes there.
            seek = { time, deselect ->
                if (subtitling == null && titling == null && transitioning == null && cropping == null) {
                    if (deselect) marks = marks.selecting()
                    jump(time.coerceIn(0.0, duration))
                }
            },
            // A title is opened from its bar, on the piece the bar is on. The picture goes to where that piece shows it.
            openTitle = { index, title ->
                val clip = clips.getOrNull(index)
                if (clip != null && subtitling == null && titling == null && transitioning == null && cropping == null && trim == null) {
                    editor.pause()
                    TimelineEditing.titleRange(clips, index, title)?.let { range ->
                        if (playhead < range.start || playhead >= range.endInclusive) jump(range.start.coerceIn(0.0, duration))
                        titling = Titling(title, range, clip.id, title, range)
                    }
                }
            },
            trim?.index ?: location?.index, trim, pixelsPerSecond,
            shift = trim?.takeIf { it.leading }?.let { ((it.clip.start - clips[it.index].start) * pixelsPerSecond).toFloat() } ?: 0f,
            scroll = scroll, touched = { editor.pause() },
            // A tap on the name of a transition puts the playhead on its join, where the row below offers it, as in the original.
            focusTransition = { join ->
                if (subtitling == null && titling == null && transitioning == null && cropping == null) {
                    marks = marks.selecting()
                    jump(join.time.coerceIn(0.0, duration))
                }
            },
            beginTrim = { leading -> if (subtitling == null && titling == null && transitioning == null && cropping == null) location?.let { trim = Trim(it.index, leading, clips[it.index]) } },
            dragTrim = dragTrim, endTrim = endTrim,
            // A range still open runs to the playhead. Nothing is marked on a timeline a tool is redrawing.
            marked = if (transitioning == null && trim == null && auditioning == null) MarkedView.of(marks, playhead) else null,
            level = levelGesture,
        )
        val framing = cropping
        if (framing != null) {
            val shape = canvas?.let { FrameSize(it.first.toDouble(), it.second.toDouble()) }
            // The tool closes when the edit changes under it, as with Undo.
            LaunchedEffect(edit) { if (cropping?.on?.base != edit) cropping = null }
            // Parked on an end of the motion, the picture stands where that end shows: the start for A, the peak
            // or the last frame for B. It follows the peak as the timing changes.
            val parked = framing.draft.previewEndpoint
            LaunchedEffect(parked, framing.draft.animation?.timing) {
                framing.parkedAt(playhead)?.coerceIn(0.0, duration)?.let { if (editor.playing || abs(it - playhead) > 0.0005) { auditionEnd = null; jump(it) } }
            }
            // Playing shows the motion run; when it stops, the picture goes back to the end being edited.
            LaunchedEffect(editor.playing) {
                val now = cropping ?: return@LaunchedEffect
                if (editor.playing) { if (now.draft.previewEndpoint != null) cropping = now.copy(draft = now.draft.scrubbed()) }
                else if (now.draft.endpoint != null && now.draft.previewEndpoint == null) cropping = now.copy(draft = now.draft.copy(previewEndpoint = now.draft.endpoint))
            }
            CropPanel(
                framing, source = framing.on.active(playhead)?.let { sizes[it.clip.sourceID] }, canvas = shape,
                whole = framing.on.start <= 0.0005 && framing.on.end >= duration - 0.0005,
                change = { cropping = framing.copy(draft = it) },
                apply = { removing ->
                    val next = shape?.let { framing.applied(sizes, it, removing) }
                    editor.pause()
                    auditionEnd = null
                    cropping = null
                    framing.on.scoped?.let { applyScoped(it, next, playhead) } ?: commit(next, playhead)
                },
                cancel = { editor.pause(); auditionEnd = null; cropping = null },
                modifier = Modifier.weight(1f), appliesTo = toolScopeLabel,
            )
        } else if (titling != null) {
            val work = titling!!
            // A new title may go anywhere in the movie; an existing one is worked on inside the clip of the piece that was opened.
            val index = work.clipID?.let { id -> clips.indexOfFirst { it.id == id } }
            val bounds = if (index == null) 0.0..duration else TimelineEditing.clipRange(clips, index)
            if (bounds == null) titling = null else TitlePanel(
                draft = work.title, existing = work.existing, change = { titling = work.copy(title = it) },
                range = work.range, bounds = bounds, setRange = { titling = work.copy(range = it) },
                replay = { exit, title ->
                    // On the clips as they would be with the title as it is at this moment.
                    val moved = work.copy(title = title).applied(edit)?.clips ?: titled
                    val run = TitleMotionRanges(moved).range(title, work.range.start)
                    val motion = title.motion
                    if (run != null && motion != null && title.text.isNotBlank()) {
                        val window = motion.previewWindow(exit, run.endInclusive - run.start)
                        val from = (run.start + window.start).coerceIn(0.0, duration)
                        val to = (run.start + window.endInclusive).coerceIn(0.0, duration)
                        if (to > from) {
                            auditionEnd = to
                            auditionReturn = null
                            playhead = from
                            editor.play(from)
                        }
                    }
                },
                sample = samplePicture,
                // One undo step for everything done in the tool. A motion that does nothing is not saved.
                done = {
                    val title = work.title.copy(text = work.title.text.trim(), motion = work.title.motion?.takeIf { it.active })
                    titling = null
                    auditionEnd = null
                    commit(work.copy(title = title).applied(edit), playhead)
                },
                // From the piece that was opened only; another piece carrying the title keeps it.
                remove = {
                    titling = null
                    auditionEnd = null
                    if (index != null) commit(TimelineEditing.removeText(edit, index, work.title.id), playhead)
                },
                cancel = { editor.pause(); auditionEnd = null; titling = null },
                modifier = Modifier.weight(1.5f),
            )
        } else if (transitioning != null) {
            val work = transitioning!!
            val join = TimelineEditing.join(clips, work.join)?.takeIf { it.transition == work.shown.copy(nextClipID = it.transition?.nextClipID) }
            val middle = { TimelineEditing.layout(clips).join(work.join)?.let { if (join != null) it.origin + it.timing.videoDuration / 2 else it.time } ?: playhead }
            // Which end of which clip: an opening and a close keep their number while their clip is not the first or the last.
            val place = TimelineEditing.layout(saved.clips).join(work.join)
            // The picture goes to the middle of the transition whenever its place or its length changes.
            LaunchedEffect(work.join, join?.origin, join?.timing?.videoDuration) { jump(middle().coerceIn(0.0, duration)) }
            // A change of length moves clips and reloads the players, so it waits for the slider to rest.
            LaunchedEffect(work.settings) {
                kotlinx.coroutines.delay(150)
                transitioning = transitioning?.let { if (it.settings == work.settings) it.copy(shown = work.settings) else it }
            }
            TransitionPanel(
                settings = work.settings, existing = work.initial != null, change = { transitioning = work.changed(it) },
                index = work.join, count = saved.clips.size, edge = place?.edge ?: 0, join = join,
                maximum = TimelineEditing.joinMaximum(saved.clips, work.join), projectDuration = duration,
                lengthChange = duration - (TimelineEditing.setJoin(saved, work.join, null) ?: saved).duration,
                audioMaximum = TransitionDefaults.audioMaximum(clips.maxOfOrNull { it.duration }),
                framesPerSecond = TimelineEditing.layout(clips).join(work.join)?.let(frames) ?: TransitionScene.FRAMES_PER_SECOND,
                playing = editor.playing,
                play = {
                    if (editor.playing) { auditionEnd = null; editor.pause() } else join?.let {
                        // A little of each clip around it, as the original plays it: to one frame before the end, and then back to the start.
                        val from = (it.range.start - 0.65).coerceAtLeast(0.0)
                        auditionEnd = ((it.range.endInclusive + 0.65).coerceAtMost(duration) - PauseDetector.FRAME).coerceAtLeast(from)
                        auditionReturn = from
                        playhead = from
                        editor.play(from)
                    }
                },
                // To another join only while nothing here would be lost.
                step = { by -> if (work.settings == (work.initial ?: work.fresh)) transitioning = Transitioning.at(saved, work.join + by, canvas) },
                apply = {
                    val next = drafted(saved, work.join, work.settings)
                    val time = middle()
                    transitioning = null
                    auditionEnd = null
                    if (next != saved) commit(next, time.coerceIn(0.0, next.duration)) else editor.pause()
                },
                remove = {
                    val next = TimelineEditing.setJoin(saved, work.join, null)
                    transitioning = null
                    auditionEnd = null
                    commit(next, next?.let { TimelineEditing.layout(it.clips).join(work.join)?.time ?: place?.time?.coerceAtMost(it.duration) } ?: playhead)
                },
                cancel = { editor.pause(); auditionEnd = null; transitioning = null },
                modifier = Modifier.weight(1.5f),
            )
        } else if (subtitling != null) {
            SubtitlesPanel(
                saved = edit, draft = subtitling ?: edit, change = { subtitling = it },
                files = files, layout = captionLayout, playhead = playhead, playing = editor.playing,
                seek = jump, working = { subtitleWork = it },
                // One undo step for everything done in the tool.
                apply = { val next = subtitling; subtitling = null; if (next != null && next != edit) commit(next, playhead) },
                close = { subtitling = null },
                modifier = Modifier.weight(1.5f),
            )
        } else {
            // Tools act on the clip under the playhead. Short rows, so nothing is cut off at any font size.
            val tight = PaddingValues(horizontal = 8.dp)
            val compact = Modifier.defaultMinSize(minWidth = 48.dp)
            val endAfterStart = stringResource(R.string.range_end_after_start)
            val tooShort = stringResource(R.string.range_too_short)
            val aligned = stringResource(R.string.range_aligned_to_join)
            val cleared = stringResource(R.string.selection_cleared)
            val deleted = stringResource(R.string.selection_deleted)
            val nothingToDelete = stringResource(R.string.selection_nothing_to_delete)
            val copied = stringResource(R.string.clipboard_copied)
            val cutDone = stringResource(R.string.clipboard_cut_done)
            val pasteDone = stringResource(R.string.clipboard_pasted)
            val noRoom = stringResource(R.string.clipboard_paste_no_room)
            val clipboard = store.clipboards[project.id]
            val resources = LocalResources.current
            val open = marks.range != null || marks.start != null
            // The transitions offered beside Mark, as the original offers them (EditorView.swift:633-674, 803-826): with the
            // playhead on a join (Transitions.swift:254-257) that has room for a frame, a new one or the one that is there;
            // and every other one the playhead is inside. A join between two clips comes before an opening or a close there.
            val offered = remember(clips, playhead) {
                // The change buttons stop on the first frame that shows a change. The original's cuts lie on
                // its frame grid, so that frame is the join; here a cut can fall between frames, so a join
                // less than a frame behind the playhead still counts as under it.
                val on = TimelineEditing.layout(clips).joins.filter { abs(it.time - playhead) < TimelineEditing.JOIN_REACH || (playhead - it.time) in 0.0..(1.0 / 30) }
                    .let { found -> found.firstOrNull { it.edge == 0 } ?: found.firstOrNull() }
                    ?.takeIf { TimelineEditing.joinMaximum(clips, it.index) >= PauseDetector.FRAME }
                listOfNotNull(on) + TimelineEditing.transitions(clips).filter { it.index != on?.index && playhead >= it.range.start && playhead <= it.range.endInclusive }
            }
            SelectionBar(
                step = marks.step,
                // On the frame grid, on a cut when one is near, and never inside a transition.
                mark = if (duration <= 0) null else ({
                    val time = RangeEditing.markTime(edit, playhead, scale.toDouble())
                    feedback = if (RangeEditing.transitionAt(clips, RangeEditing.quantize(playhead)) != null && abs(time - playhead) > RangeEditing.EPSILON) aligned else null
                    jump(time)
                    when (marks.refusal(time)) {
                        RangeMarks.Refusal.EndBeforeStart -> feedback = endAfterStart
                        RangeMarks.Refusal.TooShort -> feedback = tooShort
                        null -> marks = marks.marked(time)
                    }
                }),
                title = if (open || !marks.selected.isEmpty) selectionTitle(saved, if (open) marks.rangeTargets else marks.selected, names) else null,
                detail = when {
                    marks.start != null -> stringResource(R.string.range_choose_end)
                    else -> marks.range?.let { preciseLabel(it.duration) } ?: selectionCount(marks.selected)
                },
                message = feedback ?: stringResource(R.string.range_hint),
                clear = { marks = marks.cleared(); feedback = cleared },
                // The movie as it would be without the range, played across the place where the range began.
                preview = marks.range?.let { range ->
                    {
                        RangeEditing.delete(edit, marks.scope)?.let { removed ->
                            ReviewWindows.cut(range.start, removed.state.duration, playhead)?.let { auditioning = Audition(removed.state, it) }
                        }
                        Unit
                    }
                },
                // One Undo step; the playhead stays on its frame, or goes to the cut when its frame went away.
                delete = if (!marks.canDelete) null else ({
                    val removed = RangeEditing.delete(edit, marks.scope, playhead)
                    if (removed != null) commit(removed.state, removed.position ?: playhead)
                    marks = marks.cleared()
                    feedback = if (removed != null) deleted else nothingToDelete
                }),
                canCopy = marks.canCopy,
                copy = {
                    RangeEditing.copy(edit, marks.scope)?.let {
                        editor.pause()
                        store.clipboards[project.id] = it
                        marks = marks.cleared()
                        feedback = copied
                    }
                },
                // Copy and delete as one Undo step. The clipboard takes the piece once the edit is saved.
                cut = {
                    RangeEditing.cut(edit, marks.scope, playhead)?.let { lifted ->
                        commit(lifted.state, lifted.position ?: playhead)
                        if (store.selected?.edit?.clips == lifted.state.clips) {
                            store.clipboards[project.id] = lifted.fragment
                            marks = marks.cleared()
                            feedback = cutDone
                        }
                    }
                },
                // At the playhead: picture opens room for itself, sound is laid on a free track. What was pasted is selected.
                paste = clipboard?.let { fragment ->
                    {
                        val pasted = RangeEditing.paste(edit, fragment, playhead)
                        if (pasted == null) store.error = noRoom else {
                            commit(pasted.state, pasted.start)
                            marks = RangeMarks(selected = EditTargets(pasted.clips.toSet(), pasted.audio.toSet()))
                            feedback = pasteDone
                            if (pasted.skippedAudio > 0) store.error = resources.getQuantityString(R.plurals.clipboard_pasted_skipped, pasted.skippedAudio, pasted.skippedAudio)
                        }
                    }
                },
                canPaste = !editor.playing,
                replacesTransition = clipboard?.insertsTime == true &&
                    (RangeEditing.transitionAt(clips, playhead) != null || TimelineEditing.layout(clips).all.any { it.transition != null && abs(it.time - playhead) < RangeEditing.EPSILON }),
                clearClipboard = { store.clipboards.remove(project.id) },
                // A sound on an audio track is opened from here once it is the one thing selected.
                editSound = marks.selected.audio.singleOrNull()?.takeIf { !open && marks.selected.clips.isEmpty() }?.let { id -> { editor.pause(); chosenSound = id } },
                transitions = offered.map { join ->
                    val style = join.transition?.style
                    val name = stringResource(style?.label ?: R.string.editor_transition)
                    TransitionChip(
                        name, if (style == null) stringResource(R.string.transition_chip_add) else stringResource(R.string.transition_chip_edit, name), adds = style == null,
                    ) { editor.pause(); jump(join.time.coerceIn(0.0, duration)); transitioning = Transitioning.at(edit, join.index, canvas) }
                },
            )
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                TextButton(onClick = { commit(TimelineEditing.split(edit, playhead), playhead); marks = marks.cleared() }, compact,
                    enabled = TimelineEditing.split(edit, playhead) != null, contentPadding = tight) { Text(stringResource(R.string.editor_split)) }
                // With a range or a selection these four apply to the clips in it; a scope that holds no clip has nothing for them.
                val scopeHasClips = toolScope == null || toolSubject != null
                TextButton(onClick = { editor.pause(); editingAudio = true }, compact, enabled = location != null && (scopeHasClips || toolSounds.isNotEmpty()), contentPadding = tight) { Text(stringResource(R.string.editor_sound)) }
                TextButton(onClick = { editor.pause(); editingColor = true }, compact, enabled = location != null && scopeHasClips, contentPadding = tight) { Text(stringResource(R.string.editor_color)) }
                TextButton(onClick = {
                    editor.pause()
                    // The picture shown while framing is the clip under the playhead: of a scope, one of its clips.
                    val shownAt = toolScope?.let { scoped ->
                        val start = scoped.state.clips.indexOfFirst { it.id == toolSubject?.id }.takeIf { it >= 0 }?.let { TimelineEditing.clipStart(scoped.state.clips, it) }
                        if (start != null && (playhead < start || playhead >= start + toolSubject!!.duration)) start else null
                    }
                    shownAt?.let(jump)
                    val framed = toolSubject ?: current
                    if (framed != null) cropping = Cropping.opening(edit, toolScope, framed)
                }, compact, enabled = current != null && sizes[current.sourceID] != null && scopeHasClips, contentPadding = tight) { Text(stringResource(R.string.editor_crop)) }
                TextButton(onClick = { editor.pause(); pacing = true }, compact, enabled = location != null && scopeHasClips, contentPadding = tight) { Text(stringResource(R.string.editor_speed)) }
            }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                TextButton(
                    onClick = {
                        editor.pause()
                        // Always a new title, also over another one: a title that is there is opened from its bar on the timeline.
                        // It starts at the playhead and stays three seconds of the movie, on every clip those seconds cover.
                        // Over the marked range when there is one, as in the original.
                        if (clips.isNotEmpty()) titling = Titling(TextOverlay.fresh(), marks.range?.let { it.start..it.end } ?: TimelineEditing.newTitleRange(clips, playhead), null, null, null)
                    },
                    compact, enabled = location != null, contentPadding = tight,
                ) { Text(stringResource(R.string.editor_title)) }
                // On the join nearest to the playhead: between two clips, or the opening or the close of the movie.
                TextButton(onClick = {
                    editor.pause()
                    val joins = TimelineEditing.layout(clips).joins
                    joins.minByOrNull { abs(it.time - playhead) + if (it.edge == 0) 0.0 else 0.0001 }?.let { transitioning = Transitioning.at(edit, it.index, canvas) }
                }, compact, enabled = location != null, contentPadding = tight) { Text(stringResource(R.string.editor_transition)) }
                TextButton(onClick = { editor.pause(); findingPauses = true }, compact, enabled = location != null, contentPadding = tight) { Text(stringResource(R.string.editor_pauses)) }
                TextButton(onClick = { editor.pause(); pickSound.launch("audio/*") }, compact, enabled = clips.isNotEmpty(), contentPadding = tight) { Text(stringResource(R.string.editor_add_audio)) }
                TextButton(onClick = { editor.pause(); subtitling = edit }, compact, enabled = clips.isNotEmpty(), contentPadding = tight) {
                    Icon(Icons.Outlined.Subtitles, stringResource(R.string.editor_subtitles), tint = if (edit.subtitles != null) TitleColor else LocalContentColor.current)
                }
            }
            if (adding) {
                val unused = UnusedTakes.of(project)
                AlertDialog(
                    onDismissRequest = { adding = false },
                    title = { Text(stringResource(R.string.add_video_title, timeLabel(playhead))) },
                    text = {
                        Column {
                            TextButton(onClick = { adding = false; store.recordHere(project.id, playhead); record() }) {
                                Icon(Icons.Filled.FiberManualRecord, null, Modifier.size(18.dp), tint = RecordRed)
                                Text(stringResource(R.string.editor_record_here), Modifier.padding(start = 8.dp))
                            }
                            if (unused.isNotEmpty()) TextButton(onClick = { adding = false; choosing = emptyList() }) {
                                Icon(Icons.Outlined.VideoLibrary, null, Modifier.size(18.dp))
                                Text(stringResource(R.string.add_video_unused, unused.size), Modifier.padding(start = 8.dp))
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.common_cancel)) } },
                )
            }
            choosing?.let { chosen ->
                val unused = UnusedTakes.of(project)
                AlertDialog(
                    onDismissRequest = { choosing = null },
                    title = { Text(stringResource(R.string.unused_title)) },
                    text = {
                        // Tap to add a take to the playback order, tap again to take it out.
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            Text(stringResource(R.string.unused_note), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                            for (take in unused) {
                                val place = chosen.indexOf(take.id)
                                val file = store.file(project, take)
                                val image by produceState<Bitmap?>(null, file) { value = Thumbnails.frame(file, 0.0) }
                                Row(
                                    Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { choosing = if (place >= 0) chosen - take.id else chosen + take.id }.padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.size(56.dp).background(Color(0xFF2A2D33), RoundedCornerShape(6.dp)).clipToBounds()) {
                                        image?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                    }
                                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                        Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(take.recordedAt)), maxLines = 2)
                                        Text(preciseLabel(take.duration), color = Muted, fontSize = 13.sp)
                                    }
                                    if (place >= 0) Text("${place + 1}", fontWeight = FontWeight.Bold, color = BookmarkColor, modifier = Modifier.padding(horizontal = 8.dp))
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(enabled = chosen.isNotEmpty(), onClick = {
                            val takes = chosen.mapNotNull { id -> unused.firstOrNull { it.id == id } }
                            choosing = null
                            store.addUnused(project.id, takes, playhead)?.let { start -> playhead = start; editor.seek(start) }
                                ?: run { store.error = unusedFailed }
                        }) { Text(if (chosen.isEmpty()) stringResource(R.string.unused_insert) else stringResource(R.string.unused_insert_count, chosen.size)) }
                    },
                    dismissButton = { TextButton(onClick = { choosing = null }) { Text(stringResource(R.string.common_cancel)) } },
                )
            }
            if (pacing && location != null && current != null) {
                AlertDialog(
                    onDismissRequest = { pacing = false },
                    title = { Text(stringResource(R.string.speed_title)) },
                    text = {
                        Column {
                            toolScopeLabel?.let { Text(it, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)) }
                            // Two rows, so every speed is in view on a narrow phone.
                            for (rates in listOf(listOf(0.25, 0.5, 0.75), listOf(1.0, 1.5, 2.0, 4.0))) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (rate in rates) {
                                    FilterChip((toolSubject ?: current).rate == rate, onClick = {
                                        // The same frame stays under the playhead.
                                        val frame = current.sourceTime(location.offset)
                                        if (toolScope != null) {
                                            val under = TimelineEditing.locate(toolScope.state.clips, playhead)?.let { toolScope.state.clips[it.index].id }
                                            val next = applied(toolScope) { state, at -> TimelineEditing.setSpeed(state, at, rate) }
                                            val at = next?.clips?.indexOfFirst { it.id == under }?.takeIf { it >= 0 }
                                            applyScoped(toolScope, next, if (next != null && at != null) TimelineEditing.clipStart(next.clips, at) + next.clips[at].offset(frame).coerceIn(0.0, next.clips[at].duration) else playhead)
                                        } else {
                                            val next = TimelineEditing.setSpeed(edit, location.index, rate)
                                            commit(next, next?.let { TimelineEditing.clipStart(it.clips, location.index) + it.clips[location.index].offset(frame) } ?: playhead)
                                        }
                                        pacing = false
                                    }, label = { Text(speedLabel(rate)) })
                                }
                            }
                            Text(stringResource(R.string.speed_note), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                        }
                    },
                    confirmButton = { TextButton(onClick = { pacing = false }) { Text(stringResource(R.string.common_cancel)) } },
                )
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), Arrangement.SpaceEvenly) {
                for ((label, step) in listOf(stringResource(R.string.editor_move_earlier) to -1, stringResource(R.string.editor_move_later) to 1)) {
                    val description = stringResource(if (step < 0) R.string.editor_move_clip_earlier else R.string.editor_move_clip_later)
                    val target = location?.let { it.index + step }
                    TextButton(
                        onClick = {
                            val next = TimelineEditing.move(edit, location!!.index, target!!)
                            commit(next, next?.let { TimelineEditing.clipStart(it.clips, target) + location.offset } ?: playhead)
                        },
                        compact.semantics { contentDescription = description },
                        enabled = target != null && target in clips.indices, contentPadding = tight,
                    ) { Text(label) }
                }
                // New takes go in at the playhead; Stop in the recorder ends the run.
                TextButton(onClick = { editor.pause(); adding = true }, compact, contentPadding = tight) { Text(stringResource(R.string.editor_add_video)) }
                TextButton(onClick = { location?.let { commit(TimelineEditing.delete(edit, it.index), TimelineEditing.clipStart(clips, it.index)) } }, compact,
                    enabled = location != null, contentPadding = tight) { Icon(Icons.Outlined.Delete, stringResource(R.string.common_delete), tint = if (location != null) RecordRed else LocalContentColor.current) }
            }
        }
    }

    store.importing?.let { step ->
        AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text(stringResource(R.string.common_importing)) }, text = { Text(step) })
    }
    if (editingColor && location != null && current != null) {
        ColorDialog(
            (toolSubject ?: current).color ?: ColorSettings(),
            importLut = { uri -> store.importLut(project.id, uri) },
            save = { color ->
                if (toolScope != null) applyScoped(toolScope, applied(toolScope) { state, at -> TimelineEditing.setColor(state, at, color) }, playhead)
                else commit(TimelineEditing.setColor(edit, location.index, color), playhead)
                editingColor = false
            },
            dismiss = { editingColor = false },
            appliesTo = toolScopeLabel,
        )
    }
    sounds.firstOrNull { it.id == chosenSound }?.let { item ->
        if (shapingSound) {
            val targets = soundTargets(edit, emptyList(), listOf(item.id), { "" }) { names[it.sourceID] ?: soundName }
            SoundDialog(
                targets, canReduceNoise = false,
                measure = { change -> Headroom.measure(withSound(edit, targets, change) ?: edit, files, setOf(item.id), item.start..item.end) },
                save = { change -> commit(withSound(edit, targets, change), playhead); shapingSound = false; chosenSound = null },
                dismiss = { shapingSound = false }, title = stringResource(R.string.sound_volume_and_fades),
            )
        } else {
            AudioItemDialog(
                name = names[item.sourceID] ?: soundName, item = item,
                moved = TimelineEditing.moveAudio(edit, item.id, playhead),
                startHere = TimelineEditing.trimAudio(edit, item.id, playhead, leading = true),
                endHere = TimelineEditing.trimAudio(edit, item.id, playhead, leading = false),
                removed = TimelineEditing.removeAudio(edit, item.id),
                apply = { commit(it, playhead); chosenSound = null },
                shape = { shapingSound = true },
                dismiss = { chosenSound = null },
            )
        }
    }

    if (findingPauses && location != null && current != null) {
        PausesDialog(
            files.getValue(current.sourceID), current,
            remove = { cuts ->
                commit(TimelineEditing.removeIntervals(edit, location.index, cuts), TimelineEditing.clipStart(clips, location.index))
                findingPauses = false
            },
            dismiss = { findingPauses = false },
        )
    }
    if (editingAudio && location != null && current != null) {
        // The clip under the playhead; in a scope its clips, cut at the ends of a range, and the sounds on the audio tracks
        // selected with them, which have no noise reduction.
        val base = toolScope?.state ?: edit
        val clipName = stringResource(R.string.sound_target_clip)
        val targets = soundTargets(base, toolScope?.clips ?: listOf(current.id), toolSounds, { clipName.format(it + 1) }) { names[it.sourceID] ?: soundName }
        SoundDialog(
            targets,
            canReduceNoise = toolScope == null || toolSubject != null,
            measure = { change ->
                if (targets.isEmpty()) null
                else Headroom.measure(withSound(base, targets, change) ?: base, files, targets.map { it.id }.toSet(), targets.minOf { it.start }..targets.maxOf { it.end })
            },
            save = { change ->
                // Nothing changed changes nothing: the cuts at the ends of a range are then not made either.
                val next = withSound(base, targets, change)
                commit(next, playhead)
                // As the original after Volume on a range: the range is let go.
                if (next != null && marks.range != null) marks = marks.cleared()
                editingAudio = false
            },
            dismiss = { editingAudio = false },
            appliesTo = toolScopeLabel,
        )
    }
    ExportPanel(project, canvas, store, exporter, choosingExport) { choosingExport = false }
}

/** [change] made to each clip of a tool's scope, by its place in the edit, as one edit. Null when it changes none of them: the cuts at the ends of a range are then not made either. */
private fun applied(scoped: RangeEditing.Scoped, change: (EditState, Int) -> EditState?): EditState? {
    var state = scoped.state
    var changed = false
    for (id in scoped.clips) change(state, state.clips.indexOfFirst { it.id == id })?.let { state = it; changed = true }
    return state.takeIf { changed }
}

/** A cut being previewed: the edit without what would be removed, and the stretch of it to play. */
private data class Audition(val state: EditState, val review: ReviewWindows.Review)

/** The look names of the core module, which only has them in English. */
private val ColorLook.label: Int
    get() = when (this) {
        ColorLook.Natural -> R.string.color_look_natural
        ColorLook.Warm -> R.string.color_look_warm
        ColorLook.Cool -> R.string.color_look_cool
        ColorLook.Cinema -> R.string.color_look_cinema
        ColorLook.Mono -> R.string.color_look_mono
        ColorLook.Portrait -> R.string.color_look_portrait
        ColorLook.Daylight -> R.string.color_look_daylight
        ColorLook.GoldenHour -> R.string.color_look_golden_hour
        ColorLook.MutedFilm -> R.string.color_look_muted_film
        ColorLook.RichContrast -> R.string.color_look_rich_contrast
        ColorLook.Pastel -> R.string.color_look_pastel
        ColorLook.Night -> R.string.color_look_night
        ColorLook.Silver -> R.string.color_look_silver
        ColorLook.Ink -> R.string.color_look_ink
    }

/** As [ColorSettings.title], from the resources. */
@Composable
internal fun ColorSettings.label(): String =
    imported?.name ?: stringResource(if (look == ColorLook.Natural && hasBalance) R.string.color_balance else look.label)

@Composable
internal fun <T> Choices(label: String, options: List<Pair<String, T>>, selected: T, choose: (T) -> Unit) {
    Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), Arrangement.spacedBy(8.dp)) {
        options.forEach { (name, value) -> FilterChip(selected = value == selected, onClick = { choose(value) }, label = { Text(name) }) }
    }
}

/** The clip's color: a look or an imported LUT at some strength, over exposure, warmth and tint. */
@Composable
private fun ColorDialog(saved: ColorSettings, importLut: suspend (Uri) -> ImportedLUT?, save: (ColorSettings) -> Unit, dismiss: () -> Unit, appliesTo: String? = null) {
    var color by remember { mutableStateOf(saved) }
    val scope = rememberCoroutineScope()
    val pickLut = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch { importLut(uri)?.let { color = color.copy(imported = it) } }
    }
    @Composable
    fun Level(label: String, value: Double, range: ClosedFloatingPointRange<Float>, shown: String, change: (Double) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), Arrangement.SpaceBetween) {
            Text(label, color = Muted, fontSize = 13.sp)
            Text(shown, color = Color.White, fontSize = 13.sp)
        }
        // Steps of a twentieth of the range: fine enough to see, coarse enough to hit zero again.
        Slider(value = value.toFloat(), onValueChange = { change((it * 20 / (range.endInclusive - range.start)).roundToInt() * (range.endInclusive - range.start) / 20.0) },
            valueRange = range, modifier = Modifier.semantics { contentDescription = label })
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.color_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                appliesTo?.let { Text(it, color = Muted, fontSize = 13.sp) }
                val lut = color.imported
                if (lut == null) {
                    Choices(stringResource(R.string.color_look), ColorLook.entries.map { stringResource(it.label) to it }, color.look) { color = color.copy(look = it) }
                } else {
                    Text(stringResource(R.string.color_lut_name, lut.name), color = Color.White, modifier = Modifier.padding(top = 12.dp))
                }
                Row {
                    TextButton(onClick = { pickLut.launch("*/*") }) { Text(stringResource(if (lut == null) R.string.color_import_lut else R.string.color_choose_another_lut)) }
                    if (lut != null) TextButton(onClick = { color = color.copy(imported = null) }) { Text(stringResource(R.string.color_use_look)) }
                }
                Level(stringResource(R.string.color_strength), color.amount, 0f..1f, "${(color.amount * 100).roundToInt()}%") { color = color.copy(amount = it) }
                // A correction left at zero is stored as nothing, as in the original.
                val exposure = color.exposure ?: 0.0
                val warmth = color.warmth ?: 0.0
                val tint = color.tint ?: 0.0
                Level(stringResource(R.string.color_exposure), exposure, -2f..2f, "%+.1f".format(exposure)) { color = color.copy(exposure = it.takeIf { it != 0.0 }) }
                Level(stringResource(R.string.color_warmth), warmth, -1f..1f, "%+.0f".format(warmth * 100)) { color = color.copy(warmth = it.takeIf { it != 0.0 }) }
                Level(stringResource(R.string.color_tint), tint, -1f..1f, "%+.0f".format(tint * 100)) { color = color.copy(tint = it.takeIf { it != 0.0 }) }
            }
        },
        confirmButton = { TextButton(onClick = { save(color) }) { Text(stringResource(R.string.common_save)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { color = ColorSettings(id = color.id) }, enabled = !color.isIdentity) { Text(stringResource(R.string.common_reset)) }
                TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}

/**
 * Finds the pauses in the clip under the playhead and offers to cut them out.
 * The threshold between speech and silence is estimated from the recording.
 */
@Composable
private fun PausesDialog(file: File, clip: TimelineClip, remove: (List<SourceInterval>) -> Unit, dismiss: () -> Unit) {
    var minimum by remember { mutableDoubleStateOf(0.6) }
    var progress by remember { mutableFloatStateOf(0f) }
    // null while measuring; then the measurements, or nothing when the clip has no usable sound.
    val measured by produceState<Pair<SourceWaveform?, Boolean>>(null to false, file) {
        value = PauseLevels.measure(file) { progress = it } to true
    }
    val wave = measured.first
    val settings = PauseSettings(minimum = minimum)
    val cuts = remember(wave, minimum, clip) { wave?.let { PauseDetector.detect(it, clip, settings) }.orEmpty() }
    val shorter = clip.duration - PauseDetector.keptIntervals(clip, cuts).filter { it.duration >= TimelineEditing.MINIMUM_CLIP }.sumOf { it.duration }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.pauses_title)) },
        text = {
            Column {
                when {
                    !measured.second -> {
                        Text(stringResource(R.string.pauses_listening), color = Color.White)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                    }
                    wave == null -> Text(stringResource(R.string.pauses_no_sound), color = Color.White)
                    else -> {
                        Text(
                            if (cuts.isEmpty()) stringResource(R.string.pauses_none_found)
                            else pluralStringResource(R.plurals.pauses_found, cuts.size, cuts.size, preciseLabel(shorter)),
                            color = Color.White,
                        )
                        Text(stringResource(R.string.pauses_silence_level, PauseDetector.levelDB(wave, settings).roundToInt()), color = Muted, fontSize = 13.sp)
                        Choices(stringResource(R.string.pauses_shortest), listOf("0.4 s" to 0.4, "0.6 s" to 0.6, "1 s" to 1.0, "2 s" to 2.0), minimum) { minimum = it }
                        Text(stringResource(R.string.pauses_note), color = Muted, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { remove(cuts) }, enabled = cuts.isNotEmpty() && shorter > 0) { Text(stringResource(R.string.common_remove)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** What can be done to a sound on an audio track. Each edit is given ready, or null when it does not apply at the playhead. */
@Composable
private fun AudioItemDialog(
    name: String, item: AudioItem, moved: EditState?, startHere: EditState?, endHere: EditState?, removed: EditState?,
    apply: (EditState?) -> Unit, shape: () -> Unit, dismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text(stringResource(R.string.sound_item_range, preciseLabel(item.start), preciseLabel(item.end), item.track + 1), color = Muted, fontSize = 13.sp)
                TextButton(onClick = shape) { Text(stringResource(R.string.sound_volume_and_fades)) }
                TextButton(onClick = { apply(moved) }, enabled = moved != null) { Text(stringResource(R.string.sound_move_to_playhead)) }
                TextButton(onClick = { apply(startHere) }, enabled = startHere != null) { Text(stringResource(R.string.sound_cut_before)) }
                TextButton(onClick = { apply(endHere) }, enabled = endHere != null) { Text(stringResource(R.string.sound_cut_after)) }
                TextButton(onClick = { apply(removed) }) { Text(stringResource(R.string.common_remove), color = RecordRed) }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_done)) } },
    )
}

/**
 * A transition being worked on in the Transition tool: at which join, as it
 * is now, as the timeline shows it (a change of length waits for the slider
 * to rest), as it was saved ([initial], null when new) and as a new one starts ([fresh]).
 */
private data class Transitioning(val join: Int, val settings: TransitionSettings, val shown: TransitionSettings, val initial: TransitionSettings?, val fresh: TransitionSettings) {
    /** With new settings; shown at once when they leave every clip where it is. */
    fun changed(next: TransitionSettings): Transitioning =
        copy(settings = next, shown = if (TransitionTiming.of(next) == TransitionTiming.of(shown)) next else shown)

    companion object {
        /** The tool opened on [join] of [state]: its transition, or a new one as the original starts it. */
        fun at(state: EditState, join: Int, canvas: Pair<Int, Int>?): Transitioning? {
            val found = TimelineEditing.layout(state.clips).join(join) ?: return null
            val existing = TimelineEditing.joinSettings(state.clips, join)
            val fresh = TransitionDefaults.new(TimelineEditing.joinMaximum(state.clips, join), canvas?.let { FrameSize(it.first.toDouble(), it.second.toDouble()) })
            return Transitioning(join, existing ?: fresh, existing ?: fresh, existing, fresh)
        }
    }
}

/** [state] with [settings] on [join]; when they leave less than a frame of transition, with a plain cut there. */
private fun drafted(state: EditState, join: Int, settings: TransitionSettings): EditState =
    TimelineEditing.setJoin(state, join, settings)
        ?: if (TimelineEditing.joinSettings(state.clips, join)?.copy(nextClipID = null) == settings.copy(nextClipID = null)) state else TimelineEditing.setJoin(state, join, null) ?: state

/**
 * A title being worked on in the Title tool: as it is now and the seconds of
 * the movie it shows over. A new one ([clipID] and [existing] null) goes on
 * every clip those seconds cover; one opened from its bar is worked on on
 * that clip only, and [opened] is what it showed over then.
 */
private data class Titling(
    val title: TextOverlay, val range: ClosedFloatingPointRange<Double>, val clipID: String?, val existing: TextOverlay?,
    val opened: ClosedFloatingPointRange<Double>?,
) {
    /** The edit with the title as it is now; null when that changes nothing. */
    fun applied(edit: EditState): EditState? = TimelineEditing.placeTitle(edit, title, range, clipID?.let { setOf(it) }, opened)
}

/** A playback rate as the person reads it: 0.5×, 2×. */
internal fun speedLabel(rate: Double): String = (if (rate % 1.0 == 0.0) rate.toInt().toString() else rate.toString()) + "×"
