package app.takes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

val RecordRed = Color(0xFFE5484D)

fun timeLabel(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
    else "%d:%02d".format(total / 60, total % 60)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as TakesApp
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color.White, onPrimary = Color.Black, error = RecordRed)) {
                Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    var tab by rememberSaveable { mutableIntStateOf(app.tab) }
                    SideEffect { app.tab = tab }
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (tab) {
                                0 -> RecorderScreen(app.capture, app.store) { tab = 2 }
                                1 -> ProjectsScreen(app.store, Modifier.statusBarsPadding()) { project, destination ->
                                    app.store.select(project.id)
                                    // "Continue recording" always means at the end.
                                    if (destination == 0) app.store.recordAtEnd(project.id)
                                    tab = destination
                                }
                                else -> EditorScreen(app.store, app.exporter, Modifier.statusBarsPadding()) { tab = 0 }
                            }
                            Notice(app.store.error, Modifier.align(Alignment.Center)) { app.store.error = null }
                        }
                        PrimaryTabRow(selectedTabIndex = tab, modifier = Modifier.navigationBarsPadding()) {
                            // Nobody leaves the recorder while a take is being written, as in the original.
                            val locked = app.capture.hasActiveTake
                            listOf(R.string.tab_recorder, R.string.tab_projects, R.string.tab_editor).forEachIndexed { index, title ->
                                Tab(selected = tab == index, onClick = { tab = index }, enabled = !locked,
                                    modifier = Modifier.alpha(if (locked && tab != index) 0.38f else 1f), text = { Text(stringResource(title)) })
                            }
                        }
                    }
                }
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
