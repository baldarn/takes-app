package app.takes

import android.app.Activity
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

/**
 * The movie full screen, as the original's full preview (TrackEditors.swift:22-86): the picture alone on black,
 * turning with the phone, with a transport of its own over it that hides itself while the movie plays. A tap on
 * the picture plays or pauses and brings the controls back. It plays the editor's own players, so closing it
 * returns to the editor at the same moment.
 *
 * @param aspect the width of the movie's frame over its height, null while it is not known.
 * @param picture the decks and what is drawn over them, filling the frame it is given.
 */
@Composable
internal fun FullPreview(
    aspect: Float?, playing: Boolean, playhead: Double, duration: Double, speed: Double,
    togglePlay: () -> Unit, seek: (Double) -> Unit, setSpeed: (Double) -> Unit,
    change: (forward: Boolean) -> Boolean, toChange: (forward: Boolean, effectsOnly: Boolean) -> Unit,
    previousBookmark: Double?, nextBookmark: Double?, bookmarkUnder: Boolean, toggleBookmark: () -> Unit, bookmarks: List<Double>,
    close: () -> Unit, picture: @Composable () -> Unit,
) {
    // The editor is portrait; while the movie is full screen the whole activity turns with the phone.
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
        val before = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        onDispose { if (before != null) activity.requestedOrientation = before }
    }
    Dialog(onDismissRequest = close, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // No status or navigation bar over the movie; a swipe from an edge brings them back for a moment.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val view = LocalView.current
        DisposableEffect(window) {
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                WindowCompat.getInsetsController(window, view).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            onDispose { }
        }
        var controls by remember { mutableStateOf(true) }
        // Each touch of a control asks again for the controls, which go two and a half seconds after the last while playing.
        var touched by remember { mutableIntStateOf(0) }
        val shown = { controls = true; touched++ }
        LaunchedEffect(playing, touched) {
            if (!playing) { controls = true; return@LaunchedEffect }
            delay(2500)
            controls = false
        }
        val tap by rememberUpdatedState({ togglePlay(); shown() })
        val playLabel = stringResource(if (playing) R.string.editor_pause else R.string.common_play)
        // Pinch to inspect, as the original's full screen preview (TrackEditors.swift:35, EditorView.swift:1586-1601).
        val inspection = remember { InspectionZoom() }
        LaunchedEffect(aspect) { inspection.canvas = aspect?.let { app.takes.core.Size(it.toDouble(), 1.0) } }
        Box(
            Modifier.fillMaxSize().background(Color.Black).clipToBounds()
                .inspecting(inspection, enabled = true, inspectionLabels())
                .pointerInput(Unit) { detectTapGestures(onTap = { tap() }) }
                .semantics { onClick(playLabel) { tap(); true } },
            Alignment.Center,
        ) {
            Box((aspect?.let { Modifier.aspectRatio(it) } ?: Modifier.fillMaxSize()).inspected(inspection).clipToBounds()) { picture() }
            InspectionReset(inspection, named = false, Modifier.align(Alignment.CenterEnd))
            if (controls) Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    var speedsOpen by remember { mutableStateOf(false) }
                    ReviewSpeed(speed, speedsOpen, Modifier, toggle = { speedsOpen = !speedsOpen; shown() }) { setSpeed(it); speedsOpen = false; shown() }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = close) { Text(stringResource(R.string.common_done), color = Color.White) }
                }
                Spacer(Modifier.weight(1f))
                Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 16.dp, vertical = 4.dp)) {
                    val position = stringResource(R.string.full_preview_position)
                    Slider(
                        value = playhead.coerceIn(0.0, duration).toFloat(), onValueChange = { seek(it.toDouble()); shown() },
                        valueRange = 0f..maxOf(0.001f, duration.toFloat()),
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White),
                        modifier = Modifier.semantics { contentDescription = position },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("${preciseLabel(playhead)} / ${preciseLabel(duration)}", color = Color.White, fontSize = 13.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ChangeButton(forward = false, enabled = change(false)) { toChange(false, it); shown() }
                            TextButton(onClick = { togglePlay(); shown() }) {
                                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, playLabel, Modifier.size(32.dp), tint = Color.White)
                            }
                            ChangeButton(forward = true, enabled = change(true)) { toChange(true, it); shown() }
                        }
                        BookmarksMenu(
                            under = bookmarkUnder, canToggle = true, toggle = { toggleBookmark(); shown() },
                            previous = previousBookmark, next = nextBookmark, marks = bookmarks.map { it to "" }, label = ::preciseLabel, go = { seek(it); shown() },
                        )
                    }
                }
            }
        }
    }
}
