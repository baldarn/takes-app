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
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
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
import android.os.Build
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.asComposeRenderEffect
import app.takes.core.ColorLook
import app.takes.core.ColorSettings
import app.takes.core.LUTInput
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import app.takes.core.PlacementDraft
import app.takes.core.PlacementSpots
import app.takes.core.SequenceOnly
import app.takes.core.TrackEditState
import app.takes.core.TrackComponents
import app.takes.core.TrackEditorEdits
import app.takes.core.VideoInsertionSite
import app.takes.core.InsertionSites
import androidx.compose.ui.platform.LocalResources
import app.takes.core.TimelineLinkError
import app.takes.core.TimelineLinkOutcome
import app.takes.core.TrackMoveSession
import app.takes.core.toEdit
import app.takes.core.toTimeline
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshots.Snapshot
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import app.takes.core.EditScope
import app.takes.core.EditTime
import app.takes.core.ClipAudio
import app.takes.core.EditState
import app.takes.core.EditTargets
import app.takes.core.Project
import app.takes.core.RangeEditing
import app.takes.core.RangeMarks
import app.takes.core.RenderPlan
import app.takes.core.ReviewWindows
import app.takes.core.SubtitleEditing
import app.takes.core.TimelineClip
import app.takes.core.TimelineEditing
import app.takes.core.TimelineNavigation
import app.takes.core.TimelineZoom
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.heightIn
import android.content.res.Configuration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Layout
import app.takes.core.TimelineSnapping
import app.takes.core.TitleMotionRanges
import app.takes.core.TrackRenderPlan
import app.takes.core.TrackAudioRegions
import app.takes.core.TrackMix
import app.takes.core.MovieLength
import app.takes.core.AudioToolKind
import app.takes.core.TrackSequenceView
import app.takes.core.EditInterval
import app.takes.core.TimelineSelection
import app.takes.core.TrackCaptions
import app.takes.core.EditRender
import app.takes.core.TimelineTransition
import app.takes.core.EffectValue
import app.takes.core.VideoEffectID
import app.takes.core.VideoEffectSettings
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
import kotlinx.coroutines.flow.conflate
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

/** The time of the playhead and the length of the movie, composed again only when the tenth of a second shown changes. */
@Composable
private fun PlayheadTime(playhead: () -> Double, duration: Double, modifier: Modifier = Modifier) {
    val shown by remember(playhead) { derivedStateOf { preciseLabel(playhead()) } }
    Text("$shown / ${preciseLabel(duration)}", modifier, color = Color.White, fontSize = 14.sp, maxLines = 2)
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
    val debuggable = remember(context) { (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 }
    val owner = LocalLifecycleOwner.current
    // The Transition tool: the join it is working on and the settings not saved yet. The whole
    // editor shows the timeline as it would be with them, since a transition moves what follows it.
    var transitioning by remember { mutableStateOf<Transitioning?>(null) }
    val saved = project.edit
    // A cut being previewed: until it has played, the editor shows and plays the edit as it would be without the range.
    var auditioning by remember { mutableStateOf<Audition?>(null) }
    // Move: the session while it is on, and the draft the preview shows, made again when a drag ends (EditorView.swift:1216-1223).
    var moving by remember { mutableStateOf<TrackMoveSession?>(null) }
    var moveShown by remember { mutableStateOf<EditState?>(null) }
    // The boundary picker of Move, and the stops a tap on one dashed line could mean (EditorView.swift:288-302, :709-731).
    val picker = remember { MovePicker() }
    // The Placement tool, and the draft the preview shows, made again when the fingers lift.
    var placementWork by remember { mutableStateOf<Placing?>(null) }
    var placementShown by remember { mutableStateOf<EditState?>(null) }
    // The Pauses tool, and the edit it shows: its choices, or Before them (A/PausesPanel.kt).
    var pausing by remember { mutableStateOf<Pausing?>(null) }
    val pausingShown = pausing?.shown
    val edit = remember(saved, transitioning?.join, transitioning?.shown, auditioning, moveShown, placementShown, pausingShown) {
        moveShown ?: placementShown ?: auditioning?.state ?: pausingShown ?: transitioning?.let { drafted(saved, it.join, it.shown) } ?: saved
    }
    val clips = edit.clips
    // The movie lasts until its sound ends, past the last picture in black, once the project takes that length ([MovieLength]).
    val withSound = store.soundLength(project)
    val duration = MovieLength.of(edit, withSound)
    val files = remember(project.sources) { project.sources.associate { it.id to store.file(project, it) } }
    val editor = remember { EditorPlayer(context) }
    // Read where it is drawn or used, or through a derived state that changes when what it shows does: a read in the
    // body of the editor would compose all of it again at every frame of a scrub or of playback.
    var playhead by remember { mutableDoubleStateOf(0.0) }
    var scale by remember { mutableFloatStateOf(48f) } // dp of timeline per second
    // From the whole project to 720 dp a second, as the original's viewport (C/TimelineZoom.kt).
    val timelineWidth = LocalConfiguration.current.screenWidthDp.toDouble()
    val zoomTo = { next: Double -> scale = TimelineZoom.clamped(next, timelineWidth, edit.duration).toFloat() }
    var pinchStart by remember { mutableDoubleStateOf(48.0) }
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
    // How the edit is drawn: a sequence on two decks; pictures over one another or apart by picture layers, a player a
    // lane; or not at all, past the pictures the device decodes at once. For layers the render plan holds the sound tracks alone.
    val render = remember(edit) { EditRender.of(edit, EditRendering.LANES) }
    val layers = (render as? EditRender.Layers)?.plan
    val refusal = (render as? EditRender.TooManyLayers)?.let { EditRendering.tooMany(context, it) }
    val asked = remember(render) { (render as? EditRender.Sequence)?.plan ?: RenderPlan.of(EditState(audio = edit.audio)) }
    // A sequence whose sound runs past its last picture plays on over black to the end of the movie, as the export does.
    val plan = remember(asked, duration) { if (render is EditRender.Sequence && duration > asked.duration) asked.copy(duration = duration) else asked }
    // The sound is the audio engine's: the mix plan of the edit's timeline, as the export mixes it. The chains of
    // Enhance audio are processed for listening a stretch at a time, from the playhead to half a minute after it
    // ([TrackAudioRegions.windowRequests]), so a short stretch of a long take never processes the take; until one is
    // ready its pieces play the recording, then the mix takes it. The export processes every chain whole.
    val timeline = remember(edit) { edit.toTimeline() }
    var cleaned by remember { mutableIntStateOf(0) }
    var cleaningProgress by remember { mutableStateOf<Float?>(null) }
    // Enhance audio or Censor, open on what it acts on.
    var audioTool by remember { mutableStateOf<Pair<AudioToolKind, AudioToolWork>?>(null) }
    val listening by remember { derivedStateOf { (playhead / LISTEN_STEP).toInt() } }
    LaunchedEffect(timeline, listening, files) {
        val from = listening * LISTEN_STEP
        val requests = TrackAudioRegions.windowRequests(timeline, EditInterval.of(maxOf(0.0, from - LISTEN_STEP), from + LISTEN_AHEAD)).values
        try {
            for (request in requests) {
                val file = files[request.sourceID] ?: continue
                if (!Denoiser.playable(request.effects) || Denoiser.ready(file, request.effects) != null) continue
                for (span in request.spans) {
                    if (Denoiser.windows(file, request.effects).any { it.covers(span.start, span.end) }) continue
                    cleaningProgress = 0f
                    if (Denoiser.processWindow(file, request.effects, span) != null) cleaned++
                }
            }
        } finally {
            cleaningProgress = null
        }
    }
    // Which recordings have sound at all: one without takes no lane.
    var voiced by remember { mutableStateOf<Map<String, Boolean>?>(null) }
    LaunchedEffect(files) { voiced = withContext(Dispatchers.IO) { files.mapValues { hasSound(it.value) } } }
    // The sound a fade being listened to or a level being dragged would give, heard before it is saved: the mix plays it in place of the edit's.
    var soundTrial by remember { mutableStateOf<EditState?>(null) }
    val mixOf = { trial: EditState?, heard: Map<String, Boolean> -> TrackMix.plan(trial?.toTimeline() ?: timeline, voiced = { heard[it] == true }) }
    val mixPlan = remember(timeline, soundTrial, voiced) { voiced?.let { mixOf(soundTrial, it) } }
    LaunchedEffect(mixPlan, cleaned) { mixPlan?.let { editor.loadSound(it, files, Denoiser.input(files)) } }
    // While an edit drawn by layers exports, its players let go of their decoders: the export needs one a lane as well.
    val exporting = exporter.progress != null
    LaunchedEffect(clips, sounds, layers, plan.duration, refusal != null, exporting && layers != null) {
        playhead = playhead.coerceIn(0.0, duration)
        when {
            layers != null && exporting -> editor.unload()
            layers != null -> editor.load(layers, files, playhead)
            refusal != null -> editor.unload()
            else -> editor.load(plan, files, playhead)
        }
    }
    // The Crop tool: the framing it is working on and the clips it goes on, shown in the preview and not saved until applied.
    var cropping by remember { mutableStateOf<Cropping?>(null) }
    // The Video effects tool: the effects it is working on and the clips they go on, not saved until applied.
    var effecting by remember { mutableStateOf<EffectsWork?>(null) }
    // A Freeze Frame just put in from the Video effects tool, by its id: the tool opens again on its controls once the edit has it.
    var frozenEffect by remember { mutableStateOf<String?>(null) }
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
    // The clip under the playhead, by its place in the clips; where in it the playhead is, [TrackEditState.locate] at the time it is needed.
    val located by remember(edit) { derivedStateOf { TrackEditState.locate(edit, playhead)?.index } }
    // The marked range and the selection: the editor's own, never saved. They follow the edit as it changes.
    var marks by remember { mutableStateOf(RangeMarks()) }
    // Link of picture and sound that cover different times, waiting for the question to be answered.
    var pendingLink by remember { mutableStateOf<PendingLink?>(null) }
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
        is EditScope.Selected -> stringResource(R.string.selection_tool_selected, selectionCount(edit, scope.targets))
        EditScope.Whole -> null
    }
    // A tool cuts the clips at the ends of the range, and the pieces inside are new clips: a range limited
    // to some clips takes them in, as the original carries a range from the clips to their pieces.
    val applyScoped = { scoped: RangeEditing.Scoped, next: EditState?, time: Double ->
        if (next != null) marks.rangeTargets?.let { marks = marks.copy(rangeTargets = it.copy(clips = it.clips + scoped.clips)) }
        commit(next, time)
    }
    // The clip a tool shows the settings of: the one of its scope under the playhead, else the first.
    val toolSubject by remember(toolScope) {
        derivedStateOf {
            toolScope?.let { scoped ->
                val under = TimelineEditing.locate(scoped.state.clips, playhead)?.let { scoped.state.clips[it.index] }?.takeIf { it.id in scoped.clips }
                under ?: scoped.state.clips.firstOrNull { it.id == scoped.clips.firstOrNull() }
            }
        }
    }

    // Trim handles change the clip under the playhead. The draft is shown in
    // the timeline while dragging and becomes one undo step on release.
    val durations = remember(project.sources) { project.sources.associate { it.id to it.duration } }
    var trim by remember { mutableStateOf<Trim?>(null) }
    // The draft is what the trim would leave: a transition the clip no longer has the footage for is gone from it, as it will be.
    val trimmed = trim?.takeIf { it.index in clips.indices }?.let { draft ->
        TimelineEditing.trim(edit, draft.index, draft.clip.start, draft.clip.end, durations[clips[draft.index].sourceID] ?: clips[draft.index].end)
    }
    val shown = trimmed?.clips ?: clips
    // Where each clip begins: in a row of clips the layout's, off a row its picture's own start.
    val clipStart = { state: EditState, index: Int -> TrackEditState.clipStart(state, index) ?: 0.0 }
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
        if (edge in original.start..original.end) editor.seek(clipStart(edit, draft.index) + original.offset(edge))
    }
    val endTrim = end@{
        val draft = trim ?: return@end
        trim = null
        val original = clips.getOrNull(draft.index) ?: return@end
        val base = clipStart(edit, draft.index)
        val next = TimelineEditing.trim(edit, draft.index, draft.clip.start, draft.clip.end, durations[original.sourceID] ?: original.end)
        if (next == null) { editor.seek(playhead); return@end }
        // Keep the same frame under the playhead when it survives the trim.
        val offset = (playhead - base - original.offset(draft.clip.start)).coerceIn(0.0, draft.clip.duration - 0.01)
        val moved = base + offset - playhead
        commit(next, base + offset)
        scroll.dispatchRawDelta((moved * pixelsPerSecond).toFloat()) // no one-frame jump before the scroll catches up
    }

    // What the playhead shows on top of the picture: titles and the fade of a transition.
    val firstShape by produceState<Pair<Int, Int>?>(null, clips.firstOrNull()?.sourceID) {
        value = clips.firstOrNull()?.let { files[it.sourceID] }?.let { withContext(Dispatchers.IO) { displaySize(it) } }
    }
    // The movie's frame: the size the Crop tool fixed, or the one it frames into while open, else the first picture's shape.
    val originalShape = firstShape?.let { FrameSize(it.first.toDouble(), it.second.toDouble()) }
    val canvas: Pair<Int, Int>? = (originalShape?.let { cropping?.canvas(it) } ?: saved.outputSize ?: originalShape)
        ?.let { it.width.roundToInt() to it.height.roundToInt() }
    // The upright size of each recording, for framing.
    val sizes by produceState(emptyMap<String, FrameSize>(), project.sources) {
        value = withContext(Dispatchers.IO) {
            project.recordings.mapNotNull { source ->
                displaySize(store.file(project, source))?.let { source.id to FrameSize(it.first.toDouble(), it.second.toDouble()) }
            }.toMap()
        }
    }
    // The frame rate the movie will have with the export settings as they are: a flash cut counts its frames in
    // it, here as in the export.
    val movieRate = TransitionScene.frameRate(project.exportSettings.frameRate).toDouble()
    val frames = remember(movieRate) {
        val movie = movieRate
        val rate: (TransitionLayout.Join) -> Double = { movie }
        rate
    }
    var pacing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    // The add button of the timeline the panel was opened from; null for the Add video tool, which adds at the playhead.
    var addSite by remember { mutableStateOf<VideoInsertionSite?>(null) }
    val unusedFailed = stringResource(R.string.unused_failed)
    // The unused takes chosen so far, in the order they will play; null while the list is closed.
    var choosing by remember { mutableStateOf<List<String>?>(null) }
    val current = located?.let { clips[it] }
    // Which deck shows each clip, and the clip each deck is playing now or holding the last frame of.
    val deckClips by remember(plan, edit) {
        derivedStateOf {
            List(2) { deck ->
                plan.onDeck(deck, playhead)?.index ?: plan.held(deck, playhead)?.index ?: located?.takeIf { plan.pictures.getOrNull(it)?.deck == deck }
            }
        }
    }
    // For an edit drawn by layers, the picture each lane shows: playing, holding a frame, or waiting to play next.
    val lanePictures by remember(layers) {
        derivedStateOf {
            layers?.let { drawn ->
                List(drawn.lanes) { lane ->
                    drawn.onLane(lane, playhead)?.index ?: drawn.freezes.firstOrNull { it.lane == lane && playhead >= it.from && playhead < it.until }?.picture
                        ?: drawn.pictures.filter { it.lane == lane && it.start > playhead }.minByOrNull { it.start }?.index
                }
            }
        }
    }
    // The clip each deck or lane shows, and the second of the movie it begins at.
    val shownClips: List<Pair<TimelineClip, Double>?> = List(EditRendering.LANES) { deck ->
        if (layers != null) lanePictures?.getOrNull(deck)?.let { layers.pictures[it].content to layers.pictures[it].start }
        else deckClips.getOrNull(deck)?.let { plan.clips[it] to plan.pictures[it].start }
    }
    // The color each deck shows.
    val folder = remember(project.id) { store.disk.folder(project.id) }
    // Imported fonts come from this project's copies first.
    DisposableEffect(folder) { ImportedFonts.openFolder = folder; onDispose { if (ImportedFonts.openFolder == folder) ImportedFonts.openFolder = null } }
    // The recordings in Apple Log, whose picture is decoded to display before any grade, as in the export.
    val logProfiles by produceState(emptyMap<String, LUTInput>(), project.sources) {
        value = withContext(Dispatchers.IO) { project.recordings.mapNotNull { source -> LogFootage.profile(store.file(project, source))?.let { source.id to it } }.toMap() }
    }
    val deckColors = shownClips.map { shown -> shown?.let { (clip, _) -> logProfiles[clip.sourceID].let { log -> if (clip.color == null && log == null) null else (clip.color ?: ColorSettings()) to log } } }
    // The crop a clip shows at the playhead and the source second it is sampled at: its own, or while the Crop tool
    // is open what the tool would leave on it.
    val cropFor = { clip: TimelineClip, start: Double ->
        val offset = (playhead - start).coerceIn(0.0, clip.duration)
        val shows = PictureFraming.motionTime(clip, offset)
        val framed = cropping
        (if (framed != null) framed.shown(clip, shows, start + offset) else clip.crop) to shows
    }
    val cropOf = { index: Int -> cropFor(plan.clips[index], plan.pictures[index].start) }
    // The framing each deck shows at this moment, for a frame of the size it is drawn in: a crop is sampled at the
    // source second its clip shows, so one that moves between two poses moves as the footage plays or is scrubbed.
    // With the Crop tool open, whether each deck shows a framing or a flip at all: derived, so that a scrub composes the
    // editor again only when one appears or goes, the framing itself being sampled when the picture is drawn.
    val toolFramed by remember(shownClips) {
        derivedStateOf {
            val tool = cropping ?: return@derivedStateOf null
            shownClips.map { shown ->
                shown?.let { (clip, start) ->
                    val (crop, shows) = cropFor(clip, start)
                    crop != null || tool.mirrorShown(clip, shows, start + (playhead - start).coerceIn(0.0, clip.duration)) != null
                } == true
            }
        }
    }
    val deckFrames = shownClips.mapIndexed { deck, shown ->
        val size = shown?.let { sizes[it.first.sourceID] }
        val tool = cropping
        // A clip's own crop and flip: sampled at the source second when the picture is drawn, which a scrub does not compose again.
        if (tool == null) {
            val (clip, start) = shown ?: return@mapIndexed null
            if (size == null || (clip.crop == null && clip.mirror == null)) return@mapIndexed null
            val framing: (FrameSize) -> PictureFrame = { frame ->
                val shows = PictureFraming.motionTime(clip, (playhead - start).coerceIn(0.0, clip.duration))
                PictureFraming.frame(clip.crop, shows, size, frame, clip.mirror)
            }
            return@mapIndexed framing
        }
        if (shown == null || size == null || toolFramed?.getOrNull(deck) != true) null else {
            val (clip, start) = shown
            val framing: (FrameSize) -> PictureFrame = { frame ->
                val (crop, shows) = cropFor(clip, start)
                // The flip too, the one being worked on while the Crop tool is open.
                val mirror = tool.mirrorShown(clip, shows, start + (playhead - start).coerceIn(0.0, clip.duration))
                PictureFraming.frame(crop, shows, size, frame, mirror)
            }
            framing
        }
    }
    // The titles under the playhead, with the one being worked on as it is now.
    val titledEdit = titling?.applied(edit)
    val titled = titledEdit?.clips ?: clips
    val titlePlan = remember(titledEdit, plan) { titledEdit?.takeIf { it.isSequence }?.let { TrackRenderPlan.of(it) } ?: plan }
    // Worked out where they are drawn, at each frame, without composing the editor again. With the shader each clip's
    // titles are on its deck and go through the transition with it; over the picture only a split screen's.
    fun titleMoment() = titlePlan.moment(playhead, frames = frames)
    fun shownTitles() = if (shadedPreview && layers == null) titlePlan.titleSides(titleMoment(), playhead).over else titlePlan.titlesShown(playhead, frames)
    fun deckTitles() = titleMoment().let { titlePlan.titleSides(it, playhead).onDecks(it) }
    // An edit drawn by layers has its titles inside the layer of their picture: each lane's, as a clip by itself.
    val titleLayers = remember(titledEdit, layers) { titledEdit?.takeIf { !it.isSequence }?.let { (EditRender.of(it, EditRendering.LANES) as? EditRender.Layers)?.plan } ?: layers }
    // Worked out where the pictures are composed.
    fun laneTitles() = lanePictures.orEmpty().map { index ->
        val picture = index?.let { layers?.pictures?.getOrNull(it) } ?: return@map emptyList()
        val clip = titleLayers?.pictures?.firstOrNull { it.id == picture.id }?.content ?: picture.content
        if (clip.texts.isNullOrEmpty()) emptyList() else listOf(clip).let { alone -> TitleMotionRanges(alone).shown(alone, (playhead - picture.start).coerceIn(0.0, picture.duration), frames) }
    }
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
    val captionPlan = remember(subtitling, plan) { subtitling?.takeIf { it.isSequence }?.let { TrackRenderPlan.of(it) } ?: plan }
    val captionLayers = remember(subtitling, layers) { subtitling?.takeIf { !it.isSequence }?.let { (EditRender.of(it, EditRendering.LANES) as? EditRender.Layers)?.plan } ?: layers }
    val captions = captionLayers?.captions ?: captionPlan.captions
    val captionLook = if (captionLayers != null) captionLayers.subtitles else captionPlan.subtitles
    val captionLayout = remember(captions, captionLook?.style, captionLook?.resolvedFont, captionLook?.appearance?.layoutKey, captionLook?.motion, canvas) {
        val look = captionLook
        val shape = canvas
        if (look == null || shape == null) null else SubtitleLayout(captions, look, shape.first, shape.second, context.assets)
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
    val changeBack by remember(navigation) { derivedStateOf { navigation.destination(playhead, false) != null } }
    val changeOn by remember(navigation) { derivedStateOf { navigation.destination(playhead, true) != null } }
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
    var editingColor by remember { mutableStateOf(false) }
    var editingAudio by remember { mutableStateOf(false) }
    var leveling by remember { mutableStateOf<Leveling?>(null) }
    val levelMeasuring = remember { java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Job?>(null) }
    var chosenSound by remember { mutableStateOf<String?>(null) }   // the audio item being edited
    var shapingSound by remember { mutableStateOf(false) }
    val pickSound = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) store.importAudio(project.id, uri, playhead)
    }
    // Add audio at the playhead: record a voice or choose a file (AudioToolsEditor.swift:5-65, EditorView.swift:326, 339).
    var addingAudio by remember { mutableStateOf<Double?>(null) }
    // A picture for a video effect's background, kept in the project; [pictureFor] takes its file name.
    var pictureFor by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val picked = pictureFor
        pictureFor = null
        if (uri == null || picked == null) return@rememberLauncherForActivityResult
        scope.launch { picked(store.importEffectPicture(project.id, uri)) }
    }
    val seekTo by rememberUpdatedState(jump)
    val effectTools = remember(project.id) {
        EffectTools(
            seek = { seekTo(it) },
            pickPicture = { picked ->
                pictureFor = picked
                pickPicture.launch(PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly).build())
            },
            samplePalette = { sourceID, time, count ->
                files[sourceID]?.let { file -> withContext(Dispatchers.IO) { runCatching { ScenePalettes.sample(file, time, count) }.getOrNull() } }
            },
        )
    }
    // New footage selected, the playhead where it begins (EditorView.swift:885-889).
    val resources = LocalResources.current
    val footageAdded = { made: TrackEditorEdits.Made, count: Int ->
        jump(made.position)
        marks = RangeMarks(selected = TrackComponents.targets(made.edit, made.selection))
        feedback = resources.getQuantityString(R.plurals.footage_inserted, count, count)
    }
    // Videos and photos from the phone go in at the playhead, or at the add button they were chosen from, in the
    // order picked, as the original's add panel (AddVideoPanel.swift:45-49).
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        val site = addSite
        val at = InsertionSites.time(edit, playhead, site) ?: playhead
        if (uris.isNotEmpty()) store.insertMedia(project.id, uris, at, site) { made -> footageAdded(made, uris.size) }
    }
    // Bookmarks anywhere: on the picture in front or the one selected, on a selected sound, or in empty time and at the
    // end (EditorView.swift:1259-1295). Made on the saved edit, so a draft on the screen is never committed with one.
    val bookmarks = rememberBookmarks(edit, saved, { playhead }, marks.selected, editable = moving == null, commit = commit, said = { feedback = it })
    var bookmarksOpen by remember { mutableStateOf(false) }
    val soundName = stringResource(R.string.editor_sound_default_name)
    val names = remember(project.sources, soundName) { project.sources.associate { it.id to (it.originalName ?: soundName) } }

    // The review speed, preview only as in the original (EditorView.swift:454-465): reviews of a cut, a transition or the playhead play at 1×.
    var reviewSpeed by remember { mutableDoubleStateOf(1.0) }
    var reviewSpeedsOpen by remember { mutableStateOf(false) }
    val chooseReviewSpeed = { speed: Double ->
        reviewSpeed = speed
        reviewSpeedsOpen = false
        if (editor.playing && auditionReturn == null && auditionEnd == null && auditioning == null) editor.play(playhead, speed)
    }
    // The movie full screen, with a transport of its own (A/FullPreview.kt).
    var fullPreview by remember { mutableStateOf(false) }
    // The decks are drawn on new surfaces each way; paused, they show nothing there until they are put on their frame again.
    LaunchedEffect(fullPreview) {
        repeat(2) { withFrameNanos { } }
        if (!editor.playing) editor.seek(playhead)
    }
    // Play or pause, from the Play button or a tap on the picture. A pause during a quick review goes back to where it began.
    val togglePlay: () -> Unit = {
        auditionEnd = null
        val framed = cropping
        if (editor.playing) { editor.pause(); auditionReturn?.let { auditionReturn = null; jump(it) } }
        // The Crop tool plays what it works on and stops at its end, as the original's preview does.
        else if (framed != null) {
            auditionEnd = (framed.on.end - PauseDetector.FRAME / 2).takeIf { framed.on.end < duration - 0.0005 }
            val from = framed.playFrom(playhead)
            playhead = from
            editor.play(from, reviewSpeed)
        } else editor.play(if (playhead >= duration - 0.05) 0.0 else playhead, reviewSpeed)
    }

    // Quick review, as the original's (EditorView.swift:526-547): with a range marked, the movie as it would be without it,
    // across the cut; otherwise a second on each side of the playhead, or of where the review under way began, and back.
    val quickReview: () -> Unit = {
        val range = marks.range
        val removed = range?.let { RangeEditing.delete(edit, marks.scope) }
        val cut = if (range != null && removed != null) ReviewWindows.cut(range.start, removed.state.duration, playhead) else null
        if (cut != null && removed != null) { editor.pause(); auditionEnd = null; auditionReturn = null; auditioning = Audition(removed.state, cut) }
        else if (clips.isNotEmpty()) {
            val review = ReviewWindows.quick(auditionReturn ?: playhead, duration)
            editor.pause()
            auditionEnd = review.end
            auditionReturn = review.returnTo
            playhead = review.start
            editor.play(review.start)
        }
    }
    // Play waits a moment for a second tap, which asks for a quick review instead; with a screen reader it plays at once,
    // and the review is one of the button's actions.
    var pendingPlay by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var playTapTime by remember { mutableLongStateOf(0L) }
    var playTapAnchor by remember { mutableDoubleStateOf(0.0) }
    val cancelPendingPlay: () -> Unit = { pendingPlay?.cancel(); pendingPlay = null }
    val playTapped: () -> Unit = {
        val reader = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
        val now = android.os.SystemClock.uptimeMillis()
        if (reader) togglePlay()
        else if (now - playTapTime < (ReviewWindows.DOUBLE_TAP * 1000).toLong()) {
            cancelPendingPlay()
            playTapTime = 0L
            editor.pause()
            playhead = playTapAnchor
            quickReview()
        } else {
            playTapTime = now
            playTapAnchor = playhead
            if (editor.playing) togglePlay()
            else {
                cancelPendingPlay()
                pendingPlay = scope.launch {
                    kotlinx.coroutines.delay((ReviewWindows.DOUBLE_TAP * 1000).toLong())
                    pendingPlay = null
                    playTapTime = 0L
                    togglePlay()
                }
            }
        }
    }

    // In landscape the picture stands beside the tools and the timeline runs under both, as in the original's
    // landscape branch (EditorView.swift:354-383). The parts are the same ones in both orientations, so turning the
    // phone keeps the players, the scroll and a tool's draft.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // What a tool panel takes beside the picture in portrait; a panel of buttons takes the room it needs. Larger text
    // gives the panel more, as the original's panels grow at accessibility sizes (EditorTools.swift:1103).
    val toolWeight = when {
        moving != null -> 0f
        placementWork != null || effecting != null || cropping != null -> 1f
        titling != null || transitioning != null || pausing != null || subtitling != null -> 1.5f
        else -> 0f
    } * LocalDensity.current.fontScale.coerceIn(1f, 2f)
    val timelineRows = rememberScrollState()
    val toolRows = rememberScrollState()
    val accessible = accessibilityText()
    EditorFrame(landscape, accessible, toolWeight, modifier.fillMaxSize().then(previewing)) {
        Row(Modifier.layoutId(EditorSlot.Header).fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // Beside the tools with large text there is no room for the name: the original's header in landscape has none (EditorView.swift:428).
            if (landscape && accessible) Spacer(Modifier.weight(1f))
            else Text(project.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = { editor.pause(); marks = marks.cleared(); store.undo(project.id) }, enabled = project.canUndo && subtitling == null && titling == null && transitioning == null && moving == null && placementWork == null) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.editor_undo)) }
            TextButton(onClick = { editor.pause(); marks = marks.cleared(); store.redo(project.id) }, enabled = project.canRedo && subtitling == null && titling == null && transitioning == null && moving == null && placementWork == null) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.editor_redo)) }
            SnapToggle(snap.on, snap::toggle) { store.error = snapNote }
            EditorSettingsButton(snap, enabled = subtitling == null && titling == null && transitioning == null && moving == null && placementWork == null)
            Button(onClick = { editor.pause(); choosingExport = true }, modifier = Modifier.padding(start = 4.dp), enabled = clips.isNotEmpty() && subtitling == null && titling == null && transitioning == null && moving == null && placementWork == null) { Text(stringResource(R.string.editor_export)) }
        }
        Box(Modifier.layoutId(EditorSlot.Preview).background(Color.Black), Alignment.Center) {
            // The export canvas: the first clip's shape. Other clips fit inside it.
            Box((canvas?.let { Modifier.aspectRatio(it.first.toFloat() / it.second) } ?: Modifier.fillMaxSize()).clipToBounds().onGloballyPositioned { previewBounds = it.boundsInWindow() }) {
                val grades = deckColors.map { color ->
                    remember(color, folder) {
                        if (color != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ColorLuts.previewEffect(color.first, folder, color.second) else null
                    }
                }
                // The video effects after the color, as the export draws them: at the source second the deck shows.
                val effectPreviews = remember { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) List(EditRendering.LANES) { EffectPreview() } else null }
                // A palette from the scene is analysed in the background the first time it is shown, and drawn once it is.
                var palettesReady by remember { mutableIntStateOf(0) }
                // Made where the pictures are composed: at each frame only while a clip on view has effects, whose passes change with the time.
                fun effects() = List(EditRendering.LANES) { deck ->
                    val grade = grades[deck]
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@List grade?.asComposeRenderEffect()
                    var restored: android.graphics.RenderEffect? = null
                    val shaded = shownClips[deck]?.takeIf { (clip, _) -> effecting != null || !clip.videoEffects.isNullOrEmpty() }?.let { (clip, start) ->
                        val offset = (playhead - start).coerceIn(0.0, clip.duration)
                        // While the Video effects tool is open, its draft (or nothing, comparing with the original).
                        val work = effecting
                        val sourceTime = clip.sourceTime(offset)
                        val shown = if (work != null) work.shown(clip, sourceTime, playhead) else clip.videoEffects
                        val assets = SourceAssets(files[clip.sourceID], folder, clip, wait = false, context = context, media = project.sources.firstOrNull { it.id == clip.sourceID }) { palettesReady++ }
                        palettesReady.let { } // read, so that the picture is drawn again once a palette or a person mask is ready
                        restored = effectPreviews?.get(deck)?.nightFix(shown, sourceTime)
                        // A held frame pushes in on the hold's clock; a cadence's held frames and Step Printing's blends the preview does not draw.
                        val passes = EffectShader.passes(shown, sourceTime, movieRate, EffectAssets.inputs(assets, shown, sourceTime), clip.frozenFrame?.let { it.offset + offset })
                        previewBounds?.takeIf { passes.isNotEmpty() }?.let { bounds ->
                            effectPreviews?.get(deck)?.effect(
                                passes, bounds.width, bounds.height,
                                picture = { id -> shown?.firstOrNull { it.id == id }?.let(assets::picture) },
                                person = { smoothed -> assets.person(sourceTime, smoothed) },
                            )
                        }
                    }
                    // Night fix on the raw picture, then its color, then the other effects.
                    val graded = if (restored != null && grade != null) android.graphics.RenderEffect.createChainEffect(grade, restored) else grade ?: restored
                    (if (shaded != null && graded != null) android.graphics.RenderEffect.createChainEffect(shaded, graded) else shaded ?: graded)?.asComposeRenderEffect()
                }
                val pastPictures by remember(asked) { derivedStateOf { playhead >= asked.duration + MovieLength.MARGIN } }
                // Where a clip's own picture lies in the frame: all of it when cropped, else fitted inside it.
                val content = { index: Int, frame: FrameSize -> RenderPlan.content(frame, sizes[plan.pictures[index].sourceID], cropped = cropOf(index).first != null) }
                val pictures: @Composable () -> Unit = {
                    when {
                        layers != null -> {
                            val perTransition = { _: TimelineTransition -> movieRate }
                            LayerPictures(
                                editor.lanes, layers.lanes, effects(), deckFrames, laneTitles(), titlePainter,
                                moment = { frame -> layers.moment(playhead, frame, perTransition) },
                                content = { index, frame ->
                                    val picture = layers.pictures[index]
                                    RenderPlan.content(frame, sizes[picture.sourceID], cropped = cropFor(picture.content, picture.start).first != null)
                                },
                            )
                        }
                        refusal != null -> LayersRefused(refusal)
                        else -> Box {
                            DeckPictures(editor.decks, effects(), deckFrames, moment = { frame -> plan.moment(playhead, frame, frames) }, content = content, held = editor.heldPictures, titles = deckTitles(), painter = titlePainter)
                            // Past the last picture the movie is black under its sound, as it is exported.
                            if (pastPictures) Box(Modifier.matchParentSize().background(Color.Black))
                        }
                    }
                }
                // Full screen the decks are drawn there alone, with the titles and subtitles over them.
                if (fullPreview) FullPreview(
                    aspect = canvas?.let { it.first.toFloat() / it.second }, playing = editor.playing, playhead = playhead, duration = duration, speed = reviewSpeed,
                    togglePlay = togglePlay, seek = jump, setSpeed = chooseReviewSpeed,
                    change = { forward -> navigation.destination(playhead, forward) != null },
                    toChange = { forward, effectsOnly -> navigation.destination(playhead, forward, effectsOnly)?.let(jump) },
                    previousBookmark = bookmarks.previous, nextBookmark = bookmarks.next,
                    bookmarkUnder = bookmarks.under != null, toggleBookmark = bookmarks.toggle,
                    bookmarks = bookmarks.times, close = { fullPreview = false },
                ) {
                    pictures()
                    Canvas(Modifier.fillMaxSize()) {
                        drawIntoCanvas { target ->
                            titlePainter.draw(target.nativeCanvas, size.width, size.height, shownTitles())
                            captionLook?.let { captionLayout?.draw(target.nativeCanvas, size.width, size.height, playhead, it) }
                        }
                    }
                } else pictures()
                // While a title is being worked on, a drag on the picture moves it and a pinch resizes it.
                val work by rememberUpdatedState(titling)
                var dragged by remember { mutableStateOf<Offset?>(null) } // where the drag is, before snapping to the middle
                val titleMoving = if (titling == null) Modifier else Modifier.pointerInput(Unit) {
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
                val reframing = if (cropping == null && effecting == null) Modifier else Modifier.pointerInput(Unit) {
                    cropGestures(
                        viewport = {
                            val (known, shape) = sized
                            val source = framed?.on?.active(playhead)?.let { known[it.clip.sourceID] }
                            if (source == null || shape == null) null
                            else CropViewport(FrameSize(size.width.toDouble(), size.height.toDouble()), source, FrameSize(shape.first.toDouble(), shape.second.toDouble()), framed?.activeMirror(playhead), inset = 0.0)
                        },
                        crop = { framed?.draft?.crop ?: CropSettings() },
                        change = { next -> framed?.let { cropping = it.copy(draft = it.draft.adjusted(next)) } },
                    )
                }
                // With the Subtitles tool open, a drag or a pinch on the captions places them (SubtitlePlacing.kt).
                // Whether a caption shows at the playhead, derived so that a scrub composes the editor again only when that changes.
                val captionShown by remember(captionLayout) { derivedStateOf { captionLayout?.let { SubtitleEditing.active(it.cues, playhead).isNotEmpty() } == true } }
                val placing by rememberUpdatedState(subtitling?.subtitles?.let { look -> captionLayout?.takeIf { captionShown }?.let { SubtitlePlacing(it.placement, look) } })
                var guiding by remember { mutableStateOf(false) }
                val placeCaptions = if (subtitling == null) Modifier else Modifier.subtitlePlacing({ placing }, { next -> editor.pause(); subtitling?.let { SubtitleEditing.restyle(it, next) }?.let { subtitling = it } }, { guiding = it }, subtitlePlacingActions())
                // Otherwise a tap on the picture plays or pauses, as in the original (EditorView.swift:449).
                val toggle by rememberUpdatedState(togglePlay)
                val cancelPending by rememberUpdatedState(cancelPendingPlay)
                val tapLabel = stringResource(if (editor.playing) R.string.editor_pause else R.string.common_play)
                val tapping = if (titling != null || transitioning?.settings?.style == TransitionStyle.Split || cropping != null || effecting != null || placementWork != null || clips.isEmpty()) Modifier
                else Modifier.pointerInput(Unit) { detectTapGestures(onTap = { cancelPending(); toggle() }) }.semantics { onClick(tapLabel) { cancelPending(); toggle(); true } }
                // In a layer of its own: the titles are drawn again at every frame of a scrub, and nothing around them is.
                Canvas(Modifier.fillMaxSize().then(titleMoving).then(framing).then(reframing).then(placeCaptions).then(tapping).graphicsLayer()) {
                    if (sampling) return@Canvas
                    if (cropping != null) cropGuides()
                    if (guiding) placing?.let { subtitleGuide(it) }
                    drawIntoCanvas { target ->
                        titlePainter.draw(target.nativeCanvas, size.width, size.height, shownTitles())
                        captionLook?.let { captionLayout?.draw(target.nativeCanvas, size.width, size.height, playhead, it) }
                    }
                }
                // With Placement open, the pictures as they will be placed, and the gestures that place them.
                placementWork?.let { work ->
                    PlacementOverlay(
                        work, edit, files, { playhead }, editor.playing,
                        change = { next -> placementWork = placementWork?.copy(draft = next) },
                        settle = { placementWork?.let { now -> placementShown = now.draft.applied(now.timeline).toEdit(saved.captureBase) } },
                    )
                }
            }
            if (clips.isEmpty()) Text(stringResource(R.string.editor_timeline_empty), color = Muted)
            else {
                ReviewSpeed(reviewSpeed, reviewSpeedsOpen, Modifier.align(Alignment.BottomStart).padding(8.dp), toggle = { reviewSpeedsOpen = !reviewSpeedsOpen }, choose = chooseReviewSpeed)
                val fullLabel = stringResource(R.string.full_preview_open)
                IconButton(
                    onClick = { cancelPendingPlay(); fullPreview = true }, Modifier.align(Alignment.TopEnd).semantics { contentDescription = fullLabel },
                    enabled = titling == null && transitioning == null && cropping == null && effecting == null && subtitling == null && auditioning == null && moving == null && placementWork == null,
                ) { Icon(Icons.Filled.OpenInFull, null, tint = Color.White) }
            }
        }
        Column(Modifier.layoutId(EditorSlot.Transport)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            // Measured after the buttons, so at a large font size the time goes on two lines rather than they shrink.
            PlayheadTime({ playhead }, duration, Modifier.weight(1f, fill = false))
            val tight = PaddingValues(horizontal = 6.dp)
            // As the original's transport: the bookmarks in one menu, and on either side of Play the previous and the
            // next change, which a tap takes to the nearest cut or change of treatment and a hold to the nearest change only.
            val toChange = { forward: Boolean, effectsOnly: Boolean -> navigation.destination(playhead, forward, effectsOnly)?.let(jump); Unit }
            Row(verticalAlignment = Alignment.CenterVertically) {
                EditorBookmarks(bookmarks, { playhead }, bookmarksOpen, open = { editor.pause(); bookmarksOpen = it }, go = jump)
                ChangeButton(forward = false, enabled = changeBack) { toChange(false, it) }
            }
            val reviewLabel = stringResource(R.string.editor_quick_review)
            TextButton(
                onClick = playTapped,
                modifier = Modifier.semantics { customActions = listOf(CustomAccessibilityAction(reviewLabel) { quickReview(); true }) },
                enabled = clips.isNotEmpty(), contentPadding = tight,
            ) {
                val pausing = editor.playing || pendingPlay != null
                Icon(if (pausing) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(if (editor.playing) R.string.editor_pause else R.string.common_play), Modifier.size(32.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChangeButton(forward = true, enabled = changeOn) { toChange(true, it) }
                // The original's span menu, with a step in and out for those who cannot pinch.
                ZoomMenu(
                    TimelineZoom.span(scale.toDouble(), timelineWidth, duration),
                    choose = { zoomTo(TimelineZoom.scale(it, timelineWidth, duration)) },
                    step = { by -> zoomTo(scale * if (by > 0) 1.5 else 1 / 1.5) },
                )
            }
        }
        cleaningProgress?.let {
            Text(stringResource(R.string.editor_reducing_noise, (it * 100).roundToInt()), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, bottom = 2.dp))
        }
        }
        // A level being chosen by dragging a waveform up or down: the original's quick volume (EditorTimeline.swift:609-687).
        val leveled = leveling
        val levelChanged = stringResource(R.string.sound_level_changed)
        val levelGesture = LevelGesture(
            begin = begin@{ clip, sound, time ->
                if (subtitling != null || titling != null || transitioning != null || cropping != null || effecting != null || trim != null || moving != null || placementWork != null) return@begin false
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
        // While Move is on the timeline draws the draft as it is under the finger; off a row of clips, on rows of pictures.
        val session = moving
        val live = remember(session?.draft) { session?.draft?.toEdit(saved.captureBase) }
        val rows = remember(live, trimmed ?: edit) {
            if (session != null && live != null) TimelineRows.of(live, session.draft, session.moving.video, session.ghost) else TimelineRows.of(trimmed ?: edit)
        }
        // The sounds of their own on rows of their own, from the timeline, and the pictures whose sound was unlinked into one of them.
        val drawnEdit = live ?: trimmed ?: edit
        val soundRows = remember(drawnEdit) { TrackComponents.soundRows(drawnEdit) }
        val soundless = remember(drawnEdit) { TrackComponents.soundless(drawnEdit.toTimeline()) }
        val movingSounds = remember(drawnEdit, session?.moving) { session?.let { TrackComponents.targets(drawnEdit, it.moving).audio }.orEmpty() }
        // The picked material's start put on a stop, the playhead with it (EditorView.swift:1184-1198).
        // A change of scope, a nudge, a stop or the end of a drag: the draft is the preview's at once.
        val updateMove = { next: TrackMoveSession? -> if (next != null) { moving = next; moveShown = next.draft.toEdit(saved.captureBase) } }
        val placeStop = { time: EditTime ->
            moving?.placed(time)?.let { next ->
                updateMove(next)
                picker.picking = false
                jump(time.seconds.coerceIn(0.0, maxOf(duration, next.viewportDuration.seconds)))
            }
            Unit
        }
        val snapFeedback = { before: TrackMoveSession, after: TrackMoveSession ->
            if (after.snap != null && after.snap != before.snap) view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        }
        val addSites = remember(edit) { AddSite.of(edit) }
        val markedView by remember(marks) { derivedStateOf { MarkedView.of(marks, playhead) } }
        // In landscape the timeline keeps the original's 164 points and scrolls up and down past them (EditorView.swift:376).
        Column(Modifier.layoutId(EditorSlot.Timeline).then(if (landscape) Modifier.heightIn(max = 164.dp).verticalScroll(timelineRows) else Modifier)) {
        Timeline(
            live?.clips ?: leveled?.shown(shown) ?: shown, files, leveled?.takeIf { live == null }?.shownSounds(soundRows.items) ?: soundRows.items, names,
            // A tap picks one item, or lets go of it when it is all that is picked; a long press adds it or takes it out.
            select = { clip, audio ->
                if (subtitling == null && titling == null && transitioning == null && cropping == null && effecting == null && moving == null && placementWork == null) {
                    val one = EditTargets(setOfNotNull(clip), setOfNotNull(audio))
                    marks = if (marks.range == null && marks.start == null && marks.selected == one) marks.cleared() else marks.selecting(clip, audio)
                }
            },
            toggle = { clip, audio ->
                if (subtitling == null && titling == null && transitioning == null && cropping == null && effecting == null && moving == null && placementWork == null) {
                    editor.pause()
                    marks = if (clip != null) marks.togglingClip(clip) else if (audio != null) marks.togglingAudio(audio) else marks
                }
            },
            // A tap beside the clips and sounds goes there and lets go of everything; above them it only goes there.
            seek = { time, deselect ->
                if (subtitling == null && titling == null && transitioning == null && cropping == null && effecting == null && moving == null && placementWork == null) {
                    if (deselect) marks = marks.selecting()
                    jump(time.coerceIn(0.0, duration))
                }
            },
            // A title is opened from its bar, on the piece the bar is on. The picture goes to where that piece shows it.
            openTitle = { index, title ->
                val clip = clips.getOrNull(index)
                if (clip != null && subtitling == null && titling == null && transitioning == null && cropping == null && effecting == null && moving == null && placementWork == null && trim == null) {
                    editor.pause()
                    TimelineEditing.titleRange(clips, index, title)?.let { range ->
                        if (playhead < range.start || playhead >= range.endInclusive) jump(range.start.coerceIn(0.0, duration))
                        titling = Titling(title, range, clip.id, title, range)
                    }
                }
            },
            // The clip being trimmed where the draft has it: an outward trim over a neighbour makes the draft one of rows, in another order.
            trim?.let { draft -> shown.indexOfFirst { it.id == clips.getOrNull(draft.index)?.id }.takeIf { it >= 0 } ?: draft.index } ?: located,
            trim?.let { draft -> shown.indexOfFirst { it.id == clips.getOrNull(draft.index)?.id }.takeIf { it >= 0 }?.let { draft.copy(index = it) } ?: draft }, pixelsPerSecond,
            // A leading trim of a row of clips keeps what follows still while the draft closes up; a draft of rows has every picture at its own place.
            shift = trim?.takeIf { it.leading && trimmed?.isSequence != false }?.let { ((it.clip.start - clips[it.index].start) * pixelsPerSecond).toFloat() } ?: 0f,
            scroll = scroll, touched = { editor.pause() },
            // A tap on the name of a transition puts the playhead on its join, where the row below offers it, as in the original.
            focusTransition = { time ->
                if (subtitling == null && titling == null && transitioning == null && cropping == null && effecting == null && moving == null && placementWork == null) {
                    marks = marks.selecting()
                    jump(time.coerceIn(0.0, duration))
                }
            },
            beginTrim = { leading -> if (subtitling == null && titling == null && transitioning == null && cropping == null && effecting == null && moving == null && placementWork == null) located?.let { trim = Trim(it, leading, clips[it]) } },
            dragTrim = dragTrim, endTrim = endTrim,
            // A range still open runs to the playhead. Nothing is marked on a timeline a tool is redrawing.
            marked = if (transitioning == null && trim == null && auditioning == null && moving == null) markedView else null,
            level = levelGesture,
            pinch = { factor -> if (factor == null) pinchStart = scale.toDouble() else zoomTo(TimelineZoom.pinched(pinchStart, factor.toDouble(), timelineWidth, duration)) },
            rows = rows,
            soundRows = soundRows.count, syncOffsets = soundRows.offsets, soundless = soundless, movingSounds = movingSounds,
            // While moving, to the end of the draft or of a stop chosen beyond it (EditorTimeline.swift:24).
            length = maxOf(duration, session?.viewportDuration?.seconds ?: 0.0),
            boundaries = session?.takeIf { picker.picking }?.boundaries?.times,
            // The add buttons, while nothing else is under way (EditorTimeline.swift:318-320).
            addSites = addSites.takeIf { subtitling == null && titling == null && transitioning == null && cropping == null && effecting == null && moving == null && placementWork == null && trim == null && auditioning == null },
            addVideo = { site -> cancelPendingPlay(); editor.pause(); addSite = site; adding = true },
            bookmarks = bookmarks.times, openBookmarks = { editor.pause(); bookmarksOpen = true },
            pickBoundary = { stops -> if (stops.size == 1) placeStop(stops[0]) else if (stops.isNotEmpty()) picker.nearby = stops },
            moveDrag = session?.takeIf { !picker.picking }?.let {
                MoveDrag(
                    drag = { seconds -> moving?.let { now -> val next = now.dragged(seconds, snap.on, scale.toDouble()); snapFeedback(now, next); moving = next } },
                    end = { updateMove(moving?.dragEnded()) },
                    cancel = { updateMove(moving?.dragCancelled()?.dragEnded()) },
                )
            },
        )
        // The badges of Enhance audio and Censor, under the timeline and scrolling with it.
        AudioToolBadges(timeline, scroll, pixelsPerSecond) { badge -> audioTool = badge.kind to AudioToolWork.of(edit, badge, soundSelection(edit, marks.selected.clips, marks.selected.audio, marks.range?.let { EditInterval.of(it.start, it.end) })) }
        }
        val framing = cropping
        // Once the edit holds the Freeze Frame just made, the tool opens on its controls.
        LaunchedEffect(edit, frozenEffect) {
            val id = frozenEffect ?: return@LaunchedEffect
            val hold = edit.clips.firstOrNull { clip -> clip.videoEffects.orEmpty().any { it.id == id } } ?: return@LaunchedEffect
            frozenEffect = null
            effecting = EffectsWork.opening(edit, null, hold).copy(selected = id, page = EffectsPage.Inspector)
        }
        val effectsWork = effecting
        val placed = placementWork
        val movedDone = stringResource(R.string.move_done)
        val moveCancelled = stringResource(R.string.move_cancelled)
        val moveStale = stringResource(R.string.move_stale)
        // Cancel puts back the playhead and the scale Move began with. EditorView.swift:1224-1239.
        val endMove = { applying: Boolean ->
            moving?.let { now ->
                moving = null
                moveShown = null
                picker.reset()
                val outcome = if (applying) now.done(saved.toTimeline()) else null
                when (outcome) {
                    // The playhead stays where the stops and the edge scrolling took it, which the timeline shows.
                    is TrackMoveSession.Outcome.Moved -> { commit(outcome.timeline.toEdit(saved.captureBase), playhead); marks = marks.cleared(); feedback = movedDone }
                    TrackMoveSession.Outcome.Unchanged -> feedback = null
                    TrackMoveSession.Outcome.Stale -> feedback = moveStale
                    null -> { scale = now.scale.toFloat(); jump(now.playhead); feedback = moveCancelled }
                }
            }
            Unit
        }
        // The project changed under the session (it cannot be undone meanwhile, but a take may arrive): it ends, as in the original.
        LaunchedEffect(saved) { moving?.let { if (!it.isCurrent(saved.toTimeline())) { moving = null; moveShown = null; feedback = moveStale } } }
        if (session != null) {
            Box(Modifier.layoutId(EditorSlot.Tools).then(if (landscape) Modifier.verticalScroll(toolRows) else Modifier)) {
                MoveControls(session, picker, update = updateMove, place = placeStop, pause = { cancelPendingPlay(); editor.pause() }, end = endMove)
            }
        } else if (placed != null) {
            val applied = stringResource(R.string.placement_applied)
            val removedNote = stringResource(R.string.placement_removed)
            LaunchedEffect(saved) { if (placementWork?.timeline != saved.layered) { placementWork = null; placementShown = null } }
            PlacementPanel(
                placed, edit, { playhead }, editor.playing, change = { placementWork = placed.copy(draft = it); placementShown = it.applied(placed.timeline).toEdit(saved.captureBase) },
                seek = jump, play = { from, until -> auditionEnd = until.takeIf { it < duration - 0.0005 }; playhead = from; editor.play(from, reviewSpeed) }, pause = { auditionEnd = null; editor.pause() },
                apply = {
                    val next = placed.draft.applied(placed.timeline)
                    placementWork = null
                    placementShown = null
                    if (next != placed.timeline) { commit(next.toEdit(saved.captureBase), playhead); feedback = applied }
                },
                remove = {
                    val next = placed.draft.removed(placed.timeline)
                    placementWork = null
                    placementShown = null
                    if (next != placed.timeline) { commit(next.toEdit(saved.captureBase), playhead); feedback = removedNote }
                },
                cancel = { placementWork = null; placementShown = null },
                modifier = Modifier.layoutId(EditorSlot.Tools),
            )
        } else if (effectsWork != null) {
            // The tool closes when the edit changes under it, as with Undo.
            LaunchedEffect(edit) { if (effecting?.on?.base != edit) effecting = null }
            val freezeRefused = stringResource(R.string.effects_freeze_refused)
            EffectsPanel(
                effectsWork, { playhead }, change = { effecting = it },
                // One Undo step for everything done in the tool. Effects that change nothing are not saved.
                apply = {
                    val next = effectsWork.applied()
                    effecting = null
                    effectsWork.on.scoped?.let { applyScoped(it, next, playhead) } ?: commit(next, playhead)
                },
                cancel = { effecting = null },
                modifier = Modifier.layoutId(EditorSlot.Tools), appliesTo = toolScopeLabel, tools = effectTools,
                // The held frame goes in at the playhead as one Undo step, with what the tool had done so far in the step before it.
                freeze = {
                    val base = effectsWork.applied()?.takeIf { effectsWork.on.scoped == null } ?: edit
                    val made = TimelineEditing.freezing(base, playhead)
                    if (made == null) feedback = freezeRefused else {
                        effecting = null
                        frozenEffect = made.second
                        commit(made.first, playhead)
                    }
                },
            )
        } else if (framing != null) {
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
            // The size of the footage under the playhead, derived so that a scrub composes the editor again only at another clip.
            val cropSource by remember(framing, sizes) { derivedStateOf { framing.on.active(playhead)?.let { sizes[it.clip.sourceID] } } }
            CropPanel(
                framing, source = cropSource, canvas = shape,
                whole = framing.on.start <= 0.0005 && framing.on.end >= duration - 0.0005,
                change = { cropping = framing.copy(draft = it) },
                reshape = { cropping = framing.reshaped(it) },
                flip = { cropping = framing.flipped(it) },
                apply = { removing ->
                    val next = originalShape?.let { framing.applied(sizes, it, removing) }
                    editor.pause()
                    auditionEnd = null
                    cropping = null
                    if (next != null) {
                        // One Undo step for the framing, the flip and the movie's shape (CropEditor.swift:212-237).
                        if (next.state != framing.on.base) framing.on.scoped?.let { scoped -> marks.rangeTargets?.let { marks = marks.copy(rangeTargets = it.copy(clips = it.clips + scoped.clips)) } }
                        store.commit(project.id, next.state, next.outputSize)
                    }
                },
                cancel = { editor.pause(); auditionEnd = null; cropping = null },
                modifier = Modifier.layoutId(EditorSlot.Tools), appliesTo = toolScopeLabel,
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
                modifier = Modifier.layoutId(EditorSlot.Tools),
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
                // Against the timeline as saved, as the original's note reads: an existing transition opens unchanged, a new one against the straight cut.
                lengthChange = duration - (if (work.initial != null) saved else TimelineEditing.setJoin(saved, work.join, null) ?: saved).duration,
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
                modifier = Modifier.layoutId(EditorSlot.Tools),
            )
        } else if (pausing != null) {
            val work = pausing!!
            // An edit made meanwhile, such as a take that arrives, ends the tool.
            LaunchedEffect(saved) { if (work.from != saved) pausing = null }
            val done = { next: EditState ->
                editor.pause()
                auditionEnd = null
                auditionReturn = null
                pausing = null
                val scoped = toolScope
                // One undo step for the whole tool; the playhead stays where it is in the edit.
                if (next != saved) {
                    val time = playhead.coerceIn(0.0, next.duration)
                    if (scoped != null && work.label != null) applyScoped(scoped, next, time) else commit(next, time)
                }
            }
            PausesPanel(
                work, files, playing = editor.playing,
                seek = { jump(it.coerceIn(0.0, duration)) },
                listen = { range ->
                    editor.pause()
                    auditionReturn = playhead
                    auditionEnd = range.endInclusive
                    playhead = range.start
                    editor.play(range.start)
                },
                stop = {
                    editor.pause()
                    auditionEnd = null
                    auditionReturn?.let { auditionReturn = null; jump(it) }
                },
                apply = done, remove = done,
                cancel = { editor.pause(); auditionEnd = null; auditionReturn = null; pausing = null },
                modifier = Modifier.layoutId(EditorSlot.Tools),
            )
        } else if (subtitling != null) {
            SubtitlesPanel(
                saved = edit, draft = subtitling ?: edit, change = { subtitling = it },
                // What a transcription listens to: the marked range, the span of what is selected, or the whole timeline.
                listened = TrackCaptions.scope(timeline, marks.range?.let { TimelineSelection(range = EditInterval.of(it.start, it.end)) }
                    ?: marks.selected.takeIf { !it.isEmpty }?.let { TimelineSelection(it.clips, it.audio.mapTo(LinkedHashSet()) { id -> TrackSequenceView.soundID(edit, id) }) }),
                files = files, layout = captionLayout, playhead = { playhead }, playing = editor.playing,
                seek = jump, working = { subtitleWork = it },
                // One undo step for everything done in the tool.
                apply = { val next = subtitling; subtitling = null; if (next != null && next != edit) commit(next, playhead) },
                close = { subtitling = null }, sample = samplePicture,
                audition = { from, to -> auditionEnd = to; auditionReturn = null; playhead = from; editor.play(from) },
                modifier = Modifier.layoutId(EditorSlot.Tools),
            )
        } else {
            // Tools act on the clip under the playhead. Rows that go onto more lines when their buttons do not fit (ToolRows.kt).
            val tight = PaddingValues(horizontal = 8.dp)
            val compact = Modifier.defaultMinSize(minWidth = 48.dp)
            val endAfterStart = stringResource(R.string.range_end_after_start)
            val tooShort = stringResource(R.string.range_too_short)
            val aligned = stringResource(R.string.range_aligned_to_join)
            val cleared = stringResource(R.string.selection_cleared)
            val deleted = stringResource(R.string.selection_deleted)
            val nothingToDelete = stringResource(R.string.selection_nothing_to_delete)
            val clipboard = rememberClipboard(
                store, project.id, edit, saved, marks, { playhead }, editor.playing, pause = { editor.pause() }, commit = commit,
                select = { marks = it }, said = { feedback = it },
            )
            val open = marks.range != null || marks.start != null
            // The transitions offered beside Mark, as the original offers them (EditorView.swift:633-674, 803-826): with the
            // playhead on a join (Transitions.swift:254-257) that has room for a frame, a new one or the one that is there;
            // and every other one the playhead is inside. A join between two clips comes before an opening or a close there.
            val offered = if (!edit.isSequence) emptyList() else remember(clips) { derivedStateOf {
                // The change buttons stop on the first frame that shows a change. The original's cuts lie on
                // its frame grid, so that frame is the join; here a cut can fall between frames, so a join
                // less than a frame behind the playhead still counts as under it.
                val on = TimelineEditing.layout(clips).joins.filter { abs(it.time - playhead) < TimelineEditing.JOIN_REACH || (playhead - it.time) in 0.0..(1.0 / 30) }
                    .let { found -> found.firstOrNull { it.edge == 0 } ?: found.firstOrNull() }
                    ?.takeIf { TimelineEditing.joinMaximum(clips, it.index) >= PauseDetector.FRAME }
                listOfNotNull(on) + TimelineEditing.transitions(clips).filter { it.index != on?.index && playhead >= it.range.start && playhead <= it.range.endInclusive }
            } }.value
            // Off a row of clips: Placement where pictures play together, Close gap where nothing plays.
            val spot by remember(edit) { derivedStateOf { edit.layered?.let { PlacementSpots.at(it, playhead) } } }
            val gaps = remember(edit) { edit.layered?.let { TrackEditState.gaps(edit) } }
            val gap by remember(gaps) { derivedStateOf { gaps?.firstOrNull { playhead >= it.start - 0.0005 && playhead < it.end } } }
            val placementLabel = stringResource(R.string.placement_open)
            val closeGapLabel = stringResource(R.string.gap_close_description)
            val gapClosed = stringResource(R.string.gap_closed)
            val syncRestored = stringResource(R.string.sync_restored)
            // The one sound selected, when it plays apart from its picture: offered back in sync.
            val syncOffset = remember(edit, marks) { if (open || marks.selected.isEmpty) null else TrackComponents.offset(edit, marks.selected) }
            // Unlink and Link (EditorView.swift:1137-1161): Unlink when the selection is one link, else Link of what is selected.
            val heldCount = selectionCount(edit, marks.selected)
            val unlinkedNote = stringResource(R.string.link_unlinked, heldCount)
            val linkedNote = stringResource(R.string.link_linked)
            val clearRange = stringResource(R.string.link_clear_range)
            val linkErrors = linkErrorTexts()
            val linking = remember(edit, marks) { !open && !marks.selected.isEmpty && TrackComponents.isLinked(edit, marks.selected) }
            val applyLink = { base: EditState, targets: EditTargets, intersection: Boolean ->
                when (val joined = TrackComponents.linking(base, targets, intersection)) {
                    is TimelineLinkOutcome.Refused -> store.error = linkErrors.getValue(joined.error)
                    is TimelineLinkOutcome.Done -> { commit(joined.value.state, playhead); marks = RangeMarks(selected = joined.value.targets); feedback = linkedNote }
                }
            }
            val toggleLink = {
                cancelPendingPlay()
                editor.pause()
                when {
                    open -> store.error = clearRange
                    linking -> TrackComponents.unlinking(edit, marks.selected)?.let { done ->
                        commit(done.state, playhead)
                        marks = RangeMarks(selected = done.targets)
                        feedback = unlinkedNote
                    }
                    else -> when (val plan = TrackComponents.linkPlan(edit, marks.selected)) {
                        is TimelineLinkOutcome.Refused -> store.error = linkErrors.getValue(plan.error)
                        is TimelineLinkOutcome.Done -> {
                            val shared = plan.value.intersection
                            if (plan.value.needsIntersection && shared != null) pendingLink = PendingLink(edit, marks.selected, shared.start.seconds, shared.end.seconds)
                            else applyLink(edit, marks.selected, false)
                        }
                    }
                }
                Unit
            }
            pendingLink?.let { pending ->
                val stale = stringResource(R.string.link_stale)
                AlertDialog(
                    onDismissRequest = { pendingLink = null },
                    title = { Text(stringResource(R.string.link_intersection_title)) },
                    text = { Text(stringResource(R.string.link_intersection_message, preciseLabel(pending.start), preciseLabel(pending.end))) },
                    confirmButton = {
                        TextButton(onClick = {
                            pendingLink = null
                            if (pending.base == edit) applyLink(pending.base, pending.targets, true) else store.error = stale
                        }) { Text(stringResource(R.string.link_intersection_confirm)) }
                    },
                    dismissButton = { TextButton(onClick = { pendingLink = null }) { Text(stringResource(R.string.common_cancel)) } },
                )
            }
            // In landscape the tools scroll beside the picture.
            Column(Modifier.layoutId(EditorSlot.Tools).then(if (landscape) Modifier.verticalScroll(toolRows) else Modifier)) {
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
                    else -> marks.range?.let { preciseLabel(it.duration) } ?: selectionCount(edit, marks.selected)
                },
                message = feedback ?: stringResource(R.string.range_hint),
                clear = { marks = marks.cleared(); feedback = cleared },
                // The movie as it would be without the range, played across the place where the range began.
                preview = marks.range?.let { range ->
                    {
                        TrackComponents.delete(edit, marks.scope)?.let { removed ->
                            ReviewWindows.cut(range.start, removed.state.duration, playhead)?.let { auditioning = Audition(removed.state, it) }
                        }
                        Unit
                    }
                },
                // One Undo step; the playhead stays on its frame, or goes to the cut when its frame went away.
                // A linked pair closes the time it leaves; a picture or a sound by itself leaves a gap (EditorView.swift:1125-1132).
                delete = if (!marks.canDelete) null else ({
                    val removed = TrackComponents.delete(edit, marks.scope, playhead)
                    if (removed != null) commit(removed.state, removed.position ?: playhead)
                    marks = marks.cleared()
                    feedback = if (removed != null) deleted else nothingToDelete
                }),
                deleteLabel = if (!open && TrackComponents.leavesGap(edit, marks.selected)) R.string.selection_delete_gap else R.string.selection_delete,
                restoreSync = syncOffset?.let { off ->
                    SyncAction(signedSeconds(off.seconds)) {
                        TrackComponents.restoringSync(edit, off.audio)?.let { next -> commit(next, playhead); feedback = syncRestored }
                    }
                },
                // Copy, Cut and Paste of the track model, on a row of clips and on rows alike (EditorView.swift:839-869).
                canCopy = marks.canCopy,
                copy = clipboard.copy, cut = clipboard.cut,
                paste = clipboard.held?.let { clipboard.paste }, pasteTargets = clipboard.choices,
                canPaste = !editor.playing, replacesTransition = clipboard.replacesTransition,
                clearClipboard = { store.clipboards.remove(project.id) },
                // A sound on an audio track is opened from here once it is the one thing selected.
                editSound = marks.selected.audio.singleOrNull()?.takeIf { !open && marks.selected.clips.isEmpty() }?.let { id -> { editor.pause(); chosenSound = id } },
                transitions = offered.map { join ->
                    val style = join.transition?.style
                    val name = stringResource(style?.label ?: R.string.editor_transition)
                    TransitionChip(
                        name, if (style == null) stringResource(R.string.transition_chip_add) else stringResource(R.string.transition_chip_edit, name), adds = style == null,
                    ) { editor.pause(); jump(join.time.coerceIn(0.0, duration)); transitioning = Transitioning.at(edit, join.index, canvas) }
                } + listOfNotNull(
                    // In time where nothing plays, Close gap (EditorView.swift:675-682).
                    gap?.let { found ->
                        TransitionChip(stringResource(R.string.gap_close), closeGapLabel, adds = false, color = Color(0xFF64D2FF)) {
                            TrackEditState.closingGap(edit, playhead)?.let { next -> commit(next, found.start); feedback = gapClosed }
                        }
                    },
                ),
                // At an overlap, the Placement bubble (VideoTools.swift:612-622), offered beside a selection too (EditorView.swift:665).
                placement = spot?.let { found ->
                    TransitionChip(stringResource(if (found.placementID == null) R.string.placement_default else R.string.placement_title), placementLabel, adds = false, color = PlacementColor) {
                        editor.pause()
                        val timeline = edit.layered
                        val draft = timeline?.let { PlacementDraft.open(it, found.participants, playhead) }
                        if (timeline != null && draft != null) placementWork = Placing(draft, found, timeline)
                    }
                },
            )
            // Whether Split cuts at the playhead. Knowing it means making the cut, too much for every frame of a scrub on the
            // main thread: it is worked out on another thread for the latest time, and the button shows the latest answer.
            val splits by produceState(remember { TimelineEditing.split(edit, Snapshot.withoutReadObservation { playhead }) != null }, edit) {
                snapshotFlow { playhead }.conflate().collect { at -> value = withContext(Dispatchers.Default) { TimelineEditing.split(edit, at) != null } }
            }
            ToolRow(Modifier.fillMaxWidth()) {
                TextButton(onClick = { TimelineEditing.split(edit, playhead)?.let { commit(it, playhead); marks = marks.cleared() } }, compact,
                    enabled = splits, contentPadding = tight) { Text(stringResource(R.string.editor_split)) }
                // With a range or a selection these four apply to the clips in it; a scope that holds no clip has nothing for them.
                val scopeHasClips = toolScope == null || toolSubject != null
                TextButton(onClick = { editor.pause(); editingAudio = true }, compact, enabled = located != null && (scopeHasClips || toolSounds.isNotEmpty()), contentPadding = tight) { Text(stringResource(R.string.editor_sound)) }
                TextButton(onClick = { editor.pause(); editingColor = true }, compact, enabled = located != null && scopeHasClips, contentPadding = tight) { Text(stringResource(R.string.editor_color)) }
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
                TextButton(onClick = { editor.pause(); pacing = true }, compact, enabled = located != null && scopeHasClips, contentPadding = tight) { Text(stringResource(R.string.editor_speed)) }
                TextButton(onClick = {
                    editor.pause()
                    val subject = toolSubject ?: current
                    if (subject != null) effecting = EffectsWork.opening(edit, toolScope, subject)
                }, compact, enabled = current != null && scopeHasClips, contentPadding = tight) { Icon(Icons.Outlined.AutoAwesome, stringResource(R.string.editor_effects)) }
            }
            ToolRow(Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = {
                        editor.pause()
                        // Always a new title, also over another one: a title that is there is opened from its bar on the timeline.
                        // It starts at the playhead and stays three seconds of the movie, on every clip those seconds cover.
                        // Over the marked range when there is one, as in the original.
                        if (clips.isNotEmpty()) titling = Titling(TextOverlay.fresh(), marks.range?.let { it.start..it.end } ?: TimelineEditing.newTitleRange(clips, playhead), null, null, null)
                    },
                    compact, enabled = located != null, contentPadding = tight,
                ) { Text(stringResource(R.string.editor_title)) }
                // On the join nearest to the playhead: between two clips, or the opening or the close of the movie.
                // Off a row of clips a join has no number: drawn as off, a tap says why.
                ReasonedButton(TrackEditState.unavailable(edit, SequenceOnly.Transition)?.text(), explain = { store.error = it }, enabled = located != null, onClick = {
                    editor.pause()
                    val joins = TimelineEditing.layout(clips).joins
                    joins.minByOrNull { abs(it.time - playhead) + if (it.edge == 0) 0.0 else 0.0001 }?.let { transitioning = Transitioning.at(edit, it.index, canvas) }
                }, content = { Text(stringResource(R.string.editor_transition)) }, modifier = compact)
                TextButton(onClick = {
                    editor.pause()
                    // On the clips of the range or the selection, cut at the ends of a range, else on the clip under the playhead.
                    if (current != null) pausing = toolScope?.let { Pausing(saved, it.state, it.clips, toolScopeLabel) } ?: Pausing(saved, edit, listOf(current.id), null)
                }, compact, enabled = located != null, contentPadding = tight) { Text(stringResource(R.string.editor_pauses)) }
                TextButton(onClick = { editor.pause(); addingAudio = playhead }, compact, enabled = clips.isNotEmpty(), contentPadding = tight) { Text(stringResource(R.string.editor_add_audio)) }
                TextButton(onClick = { editor.pause(); subtitling = edit }, compact, enabled = clips.isNotEmpty(), contentPadding = tight) {
                    Icon(Icons.Outlined.Subtitles, stringResource(R.string.editor_subtitles), tint = if (edit.subtitles != null) TitleColor else LocalContentColor.current)
                }
            }
            // A project made before the movie took its sound's length is shown the longer one once, and chooses.
            val pastPicture = if (withSound) 0.0 else MovieLength.pastPicture(saved)
            if (pastPicture > 0) {
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text(stringResource(R.string.sound_past_picture_title)) },
                    text = { Text(stringResource(R.string.sound_past_picture_message, timeLabel(pastPicture))) },
                    confirmButton = { TextButton(onClick = { store.adoptSoundLength(project.id) }) { Text(stringResource(R.string.sound_past_picture_keep)) } },
                    dismissButton = {
                        TextButton(onClick = {
                            MovieLength.trimmed(saved)?.let { commit(it, playhead.coerceAtMost(saved.duration)) }
                            store.adoptSoundLength(project.id)
                        }) { Text(stringResource(R.string.sound_past_picture_trim)) }
                    },
                )
            }
            addingAudio?.let { at ->
                val added = stringResource(R.string.add_audio_added)
                AddAudio(
                    store, project.id, at, chooseFile = { addingAudio = null; pickSound.launch("audio/*") },
                    inserted = { addingAudio = null; jump(at); feedback = added }, close = { addingAudio = null },
                )
            }
            if (adding) {
                val site = addSite
                AddVideoDialog(
                    site, timeLabel(playhead), UnusedTakes.of(project).size,
                    // From an add button, the clipboard goes there too (AddVideoPanel.swift:51-58).
                    pasteHere = if (site != null && clipboard.held != null) ({ adding = false; clipboard.pasteAt(site) }) else null,
                    recordHere = { adding = false; store.recordHere(project.id, InsertionSites.time(edit, playhead, site) ?: playhead); record() },
                    phone = {
                        adding = false
                        pickMedia.launch(PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo).setOrderedSelection(true).build())
                    },
                    unused = { adding = false; choosing = emptyList() },
                    dismiss = { adding = false },
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
                            store.addUnused(project.id, takes, InsertionSites.time(edit, playhead, addSite) ?: playhead, addSite)?.let { made -> footageAdded(made, takes.size) }
                                ?: run { store.error = unusedFailed }
                        }) { Text(if (chosen.isEmpty()) stringResource(R.string.unused_insert) else stringResource(R.string.unused_insert_count, chosen.size)) }
                    },
                    dismissButton = { TextButton(onClick = { choosing = null }) { Text(stringResource(R.string.common_cancel)) } },
                )
            }
            if (pacing && current != null) TrackEditState.locate(edit, playhead)?.let { location ->
                SpeedDialog((toolSubject ?: current).rate, toolScopeLabel, dismiss = { pacing = false }, apply = { rate ->
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
                })
            }
            val nothingToMove = stringResource(R.string.move_nothing)
            ArrangeTools(
                TrackEditState.unavailable(edit, SequenceOnly.Reorder)?.text(), explain = { store.error = it },
                reorderable = { step -> located?.let { it + step in clips.indices } == true },
                reorder = { step ->
                    TrackEditState.locate(edit, playhead)?.let { at ->
                        val target = at.index + step
                        val next = TimelineEditing.move(edit, at.index, target)
                        commit(next, next?.let { TimelineEditing.clipStart(it.clips, target) + at.offset } ?: playhead)
                    }
                },
                // Move: the selection dragged along the timeline, one Undo step on Done (EditorView.swift:950).
                canMove = marks.canDelete && marks.start == null && clips.isNotEmpty(),
                move = {
                    cancelPendingPlay()
                    editor.pause()
                    val begun = beginMove(edit, marks, playhead, scale.toDouble())
                    if (begun == null) feedback = nothingToMove else { moving = begun; moveShown = null }
                },
                linking = linking, canLink = marks.canDelete && clips.isNotEmpty(), link = toggleLink,
                addVideo = { editor.pause(); addSite = null; adding = true },
                canDelete = located != null, delete = { located?.let { commit(TimelineEditing.delete(edit, it), clipStart(edit, it)) } },
            )
            }
        }
    }

    store.importing?.let { step ->
        AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text(stringResource(R.string.common_importing)) }, text = { Text(step) })
    }
    val colored = located
    if (editingColor && colored != null && current != null) {
        ColorDialog(
            (toolSubject ?: current).color ?: ColorSettings(),
            importLut = { uri -> store.importLut(project.id, uri) },
            save = { color ->
                if (toolScope != null) applyScoped(toolScope, applied(toolScope) { state, at -> TimelineEditing.setColor(state, at, color) }, playhead)
                else commit(TimelineEditing.setColor(edit, colored, color), playhead)
                editingColor = false
            },
            dismiss = { editingColor = false },
            appliesTo = toolScopeLabel,
        )
    }
    // Listening to a fade of the Sound dialog (TrackEditors.swift:381-390): the sound it would save, at 1×, from [from] to [until].
    val listenTo = { base: EditState, targets: List<SoundTarget> ->
        { from: Double, until: Double, change: (SoundTarget) -> ClipAudio ->
            val trial = withSound(base, targets, change) ?: base
            soundTrial = trial
            // Loaded before playing, so the first moment heard is already the fade being tried.
            voiced?.let { editor.loadSound(mixOf(trial, it), files, Denoiser.input(files)) }
            auditionReturn = null
            auditionEnd = until.takeIf { it < duration - 0.0005 }
            playhead = from
            editor.play(from)
        }
    }
    val stopListening = { if (soundTrial != null) { soundTrial = null; auditionEnd = null; editor.pause() } }
    sounds.firstOrNull { it.id == chosenSound }?.let { item ->
        if (shapingSound) {
            val targets = soundTargets(edit, emptyList(), listOf(item.id), { "" }) { names[it.sourceID] ?: soundName }
            SoundDialog(
                targets,
                measure = { change -> Headroom.measure(withSound(edit, targets, change) ?: edit, files, setOf(item.id), item.start..item.end) },
                save = { change -> stopListening(); commit(withSound(edit, targets, change), playhead); shapingSound = false; chosenSound = null },
                dismiss = { stopListening(); shapingSound = false }, title = stringResource(R.string.sound_volume_and_fades),
                listen = listenTo(edit, targets), playing = editor.playing, pause = { auditionEnd = null; editor.pause() },
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

    if (editingAudio && located != null && current != null) {
        // The clip under the playhead; in a scope its clips, cut at the ends of a range, and the sounds on the audio tracks
        // selected with them, which have no noise reduction.
        val base = toolScope?.state ?: edit
        val clipName = stringResource(R.string.sound_target_clip)
        val targets = soundTargets(base, toolScope?.clips ?: listOf(current.id), toolSounds, { clipName.format(it + 1) }) { names[it.sourceID] ?: soundName }
        SoundDialog(
            targets,
            // Enhance audio and Censor act on the same sounds, in the marked range when there is one.
            tools = { kind ->
                val selection = soundSelection(edit, toolScope?.clips ?: listOf(current.id), toolSounds, marks.range?.let { EditInterval.of(it.start, it.end) })
                stopListening()
                editingAudio = false
                audioTool = kind to AudioToolWork(edit, null, selection)
            },
            measure = { change ->
                if (targets.isEmpty()) null
                else Headroom.measure(withSound(base, targets, change) ?: base, files, targets.map { it.id }.toSet(), targets.minOf { it.start }..targets.maxOf { it.end })
            },
            save = { change ->
                stopListening()
                // Nothing changed changes nothing: the cuts at the ends of a range are then not made either.
                val next = withSound(base, targets, change)
                commit(next, playhead)
                // As the original after Volume on a range: the range is let go.
                if (next != null && marks.range != null) marks = marks.cleared()
                editingAudio = false
            },
            dismiss = { stopListening(); editingAudio = false },
            appliesTo = toolScopeLabel,
            listen = listenTo(base, targets), playing = editor.playing, pause = { auditionEnd = null; editor.pause() },
        )
    }
    audioTool?.let { (kind, work) ->
        val done = { next: EditState? -> commit(next, playhead); audioTool = null }
        if (kind == AudioToolKind.Enhance) {
            LaunchedEffect(work) { editor.pause() }
            EnhanceInspector(work, files, voiced, playhead, apply = { done(work.enhanced(it)) }, remove = { done(if (work.region != null) work.removed() else work.enhanced(emptyList())) }, dismiss = { audioTool = null })
        }
        else CensorDialog(work.censor, apply = { done(work.censored(it)) }, remove = work.region?.let { { done(work.removed()) } }, dismiss = { audioTool = null })
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
/** The parts of the editor's screen, which [EditorFrame] lays out for the orientation. */
private enum class EditorSlot { Header, Preview, Transport, Timeline, Tools }

/**
 * The editor's screen. In portrait, from the top: the header, the picture, the transport, the timeline and the tools;
 * the picture takes what is left, shared with a tool panel by [toolWeight] when one is open. In landscape, as the
 * original's (EditorView.swift:354-383): the timeline runs along the bottom, and above it the picture, under the
 * header, stands beside the transport and the tools, which take 52 % of the width. With [accessible] text the
 * original gives the controls 56 % of the width and the whole height (EditorView.swift:366-370): here the tools
 * have the height beside the picture, and the timeline runs under the picture alone. The keyboard leaves no room
 * for the timeline in landscape: it goes while the keyboard is up.
 */
@Composable
private fun EditorFrame(landscape: Boolean, accessible: Boolean, toolWeight: Float, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        fun slot(id: EditorSlot) = measurables.firstOrNull { it.layoutId == id }
        fun Measurable.natural(width: Int, room: Int) = measure(Constraints(width, width, 0, maxOf(0, room)))
        val header: Placeable?
        val transport: Placeable?
        val timeline: Placeable?
        val preview: Placeable?
        val tools: Placeable?
        if (!landscape) {
            var room = height
            header = slot(EditorSlot.Header)?.natural(width, room)?.also { room -= it.height }
            transport = slot(EditorSlot.Transport)?.natural(width, room)?.also { room -= it.height }
            timeline = slot(EditorSlot.Timeline)?.natural(width, room)?.also { room -= it.height }
            room = maxOf(0, room)
            val panel = slot(EditorSlot.Tools)
            tools = if (panel != null && toolWeight > 0) panel.measure(Constraints.fixed(width, (room * toolWeight / (1 + toolWeight)).toInt()))
            else panel?.natural(width, room)
            preview = slot(EditorSlot.Preview)?.measure(Constraints.fixed(width, maxOf(0, room - (tools?.height ?: 0))))
            layout(width, height) {
                var y = 0
                listOf(header, preview, transport, timeline, tools).forEach { it?.place(0, y); y += it?.height ?: 0 }
            }
        } else {
            val side = (width * (if (accessible) 0.56f else 0.52f)).toInt()
            val picture = width - side
            // With the keyboard up there is no room for it: the picture and the tool being typed into keep the screen.
            timeline = if (height < 240.dp.roundToPx()) null else slot(EditorSlot.Timeline)?.natural(if (accessible) picture else width, height / 2)
            val top = height - (timeline?.height ?: 0)
            header = slot(EditorSlot.Header)?.natural(picture, top)
            preview = slot(EditorSlot.Preview)?.measure(Constraints.fixed(picture, maxOf(0, top - (header?.height ?: 0))))
            val column = if (accessible) height else top
            transport = slot(EditorSlot.Transport)?.natural(side, column)
            val room = maxOf(0, column - (transport?.height ?: 0))
            val panel = slot(EditorSlot.Tools)
            tools = if (panel != null && toolWeight > 0) panel.measure(Constraints.fixed(side, room)) else panel?.natural(side, room)
            layout(width, height) {
                header?.place(0, 0)
                preview?.place(0, header?.height ?: 0)
                transport?.place(picture, 0)
                tools?.place(picture, transport?.height ?: 0)
                timeline?.place(0, top)
            }
        }
    }
}

@Composable
internal fun ColorSettings.label(): String =
    imported?.name ?: stringResource(if (look == ColorLook.Natural && hasBalance) R.string.color_balance else look.label)

@Composable
internal fun <T> Choices(label: String, options: List<Pair<String, T>>, selected: T, choose: (T) -> Unit) {
    Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (name, value) -> FilterChip(selected = value == selected, onClick = { choose(value) }, label = { Text(name) }) }
    }
}

/** The clip's color: a look or an imported LUT at some strength, over highlights, exposure, warmth and tint. */
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
                // Highlights sits above the balance, as in the original's colour controls (YouRec/ColorGradeControls.swift:52-66).
                val highlights = color.highlights ?: 0.0
                Level(stringResource(R.string.color_highlights), highlights, -1f..1f, "%+.0f".format(highlights * 100)) { color = color.copy(highlights = it.takeIf { it != 0.0 }) }
                Text(stringResource(R.string.color_highlights_help), color = Muted, fontSize = 12.sp)
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
            Column(Modifier.verticalScroll(rememberScrollState())) {
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

/** Link of [targets] on [base] waiting for the question whether to link only the time from [start] to [end] that they share. */
private data class PendingLink(val base: EditState, val targets: EditTargets, val start: Double, val end: Double)

/** How far the playhead moves before the chains are processed for the next stretch, and how far past it they are, in seconds. */
private const val LISTEN_STEP = 10.0
private const val LISTEN_AHEAD = 30.0

/** What each refusal of Link says, in the user's language. */
@Composable
private fun linkErrorTexts(): Map<TimelineLinkError, String> = TimelineLinkError.entries.associateWith { error ->
    stringResource(
        when (error) {
            TimelineLinkError.TooFew -> R.string.link_too_few
            TimelineLinkError.NonContiguous -> R.string.link_non_contiguous
            TimelineLinkError.NoIntersection -> R.string.link_no_intersection
            TimelineLinkError.ConfirmationRequired -> R.string.link_intersection_title
            TimelineLinkError.TransitionBoundary -> R.string.link_transition_boundary
        },
    )
}
