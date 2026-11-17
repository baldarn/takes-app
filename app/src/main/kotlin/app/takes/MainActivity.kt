package app.takes

import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

val RecordRed = Color(0xFFE5484D)

fun timeLabel(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
    else "%d:%02d".format(total / 60, total % 60)
}

class MainActivity : ComponentActivity() {
    /**
     * While it returns true, the volume keys and a camera key press the recorder's Crash Zoom when they are let go,
     * as the original's capture event interaction (CaptureController.swift:3214-3220); the volume is not changed then.
     */
    var cameraKeys: (() -> Boolean)? = null
    var cameraKeyPressed: (() -> Unit)? = null

    private fun takesCameraKey(keyCode: Int): Boolean =
        (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_CAMERA) && cameraKeys?.invoke() == true

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = takesCameraKey(keyCode) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!takesCameraKey(keyCode)) return super.onKeyUp(keyCode, event)
        if (!event.isCanceled) cameraKeyPressed?.invoke()
        return true
    }

    // Changes that could not be saved are tried again when the app comes to the front and when it leaves it,
    // as the original does when its scene becomes active or goes to the background (ContentView.swift:362-364).
    override fun onStart() {
        super.onStart()
        retryUnsaved()
    }

    override fun onStop() {
        retryUnsaved()
        super.onStop()
    }

    private fun retryUnsaved() {
        val store = (application as TakesApp).store
        if (store.unsavedDrafts.isNotEmpty()) store.retrySaves()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as TakesApp
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color.White, onPrimary = Color.Black, error = RecordRed)) {
                Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    var tab by rememberSaveable { mutableIntStateOf(app.tab) }
                    SideEffect { app.tab = tab }
                    // The activity is not made again when the phone turns (the manifest's configChanges): the screens
                    // are laid out again around the same state, so a take, a player and a tool's draft go on.
                    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                    // No status bar in the recorder, as in the original, nor in landscape, where the iPhone shows none.
                    // A swipe brings it back for a moment.
                    val view = LocalView.current
                    val fullScreen = tab == 0 || landscape
                    LaunchedEffect(fullScreen) {
                        val bars = WindowCompat.getInsetsController(window, view)
                        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        if (fullScreen) bars.hide(WindowInsetsCompat.Type.statusBars()) else bars.show(WindowInsetsCompat.Type.statusBars())
                    }
                    // The project whose original recordings the banner's Review opened.
                    var reviewing by rememberSaveable { mutableStateOf("") }
                    val banner = RecoveryBanner.of(app.store, app.capture.hasActiveTake)
                    // Projects and the editor keep clear of the camera cutout and, in landscape, of the navigation bar under
                    // them; under a banner, which keeps clear of the cutout itself, of the navigation bar only.
                    val screen = when {
                        banner != null && landscape -> Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                        banner != null -> Modifier
                        else -> Modifier.windowInsetsPadding(
                            WindowInsets.safeDrawing.only(if (landscape) WindowInsetsSides.Top + WindowInsetsSides.Bottom else WindowInsetsSides.Top),
                        )
                    }
                    // Clear of a cutout or a navigation bar at the sides, which landscape puts there.
                    Tabbed(landscape, Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                        Column {
                            // Over every screen, as the original's (ContentView.swift:75-94).
                            if (banner != null) RecoveryBanner(banner, app.store) { reviewing = it }
                            Box(Modifier.weight(1f)) {
                                when (tab) {
                                    0 -> RecorderScreen(app.capture, app.store) { tab = 2 }
                                    1 -> ProjectsScreen(app.store, app.exporter, screen) { project, destination ->
                                        app.store.select(project.id)
                                        // "Continue recording" always means at the end.
                                        if (destination == 0) app.store.recordAtEnd(project.id)
                                        tab = destination
                                    }
                                    else -> EditorScreen(app.store, app.exporter, screen) { tab = 0 }
                                }
                                Notice(app.store.error, Modifier.align(Alignment.Center)) { app.store.error = null }
                            }
                            if (reviewing.isNotEmpty()) OriginalRecordings(reviewing, app.store) { reviewing = "" }
                        }
                        // Nobody leaves the recorder while a take is being written, nor the screen of an import, a
                        // duplicate, a ZIP or a recovery while it runs, as in the original (ContentView.swift:541-550).
                        val locked = app.capture.hasActiveTake || app.store.importing != null
                        val titles = listOf(R.string.tab_recorder, R.string.tab_projects, R.string.tab_editor)
                        if (landscape) {
                            // In landscape the tabs stand in a column at the start, so the screens keep their height.
                            Column(
                                Modifier.width(IntrinsicSize.Max).fillMaxHeight().background(MaterialTheme.colorScheme.surface)
                                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)).selectableGroup(),
                                Arrangement.Center,
                            ) {
                                titles.forEachIndexed { index, title ->
                                    Tab(selected = tab == index, onClick = { tab = index }, enabled = !locked,
                                        selectedContentColor = MaterialTheme.colorScheme.onSurface, unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).alpha(if (locked && tab != index) 0.38f else 1f)
                                            .drawBehind { if (tab == index) drawRect(Color.White, size = Size(3.dp.toPx(), size.height)) },
                                        text = { Text(stringResource(title)) })
                                }
                            }
                        } else {
                            PrimaryTabRow(selectedTabIndex = tab, modifier = Modifier.navigationBarsPadding()) {
                                titles.forEachIndexed { index, title ->
                                    Tab(selected = tab == index, onClick = { tab = index }, enabled = !locked,
                                        modifier = Modifier.alpha(if (locked && tab != index) 0.38f else 1f), text = { TabLabel(stringResource(title)) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The screen and the tabs: the tabs under the screen in portrait, at its start in landscape. The screen is always
 * the first child, so turning the phone moves it without making it again.
 */
@Composable
private fun Tabbed(landscape: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val (screen, tabs) = measurables
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        if (landscape) {
            val bar = tabs.measure(loose.copy(minHeight = constraints.maxHeight))
            val page = screen.measure(Constraints.fixed(maxOf(0, constraints.maxWidth - bar.width), constraints.maxHeight))
            layout(constraints.maxWidth, constraints.maxHeight) {
                bar.place(0, 0)
                page.place(bar.width, 0)
            }
        } else {
            val bar = tabs.measure(loose.copy(minWidth = constraints.maxWidth))
            val page = screen.measure(Constraints.fixed(constraints.maxWidth, maxOf(0, constraints.maxHeight - bar.height)))
            layout(constraints.maxWidth, constraints.maxHeight) {
                page.place(0, 0)
                bar.place(0, page.height)
            }
        }
    }
}

/** A problem the user has to see, with one action to dismiss it. */
@Composable
fun Notice(text: String?, modifier: Modifier = Modifier, dismiss: () -> Unit) {
    if (text == null) return
    Column(
        modifier.padding(24.dp).background(Color(0xF0202328), RoundedCornerShape(16.dp)).padding(16.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(text, color = Color.White, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = dismiss) { Text(stringResource(R.string.common_ok)) }
    }
}
