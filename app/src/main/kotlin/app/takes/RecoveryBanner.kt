package app.takes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Bar = Color(0xFF1C1D21)
private val Orange = Color(0xFFFF9F0A)

/**
 * The bar over every screen that tells of work at risk, as the original's (YouRec/ContentView.swift:75-94):
 * changes that could not be saved, with "Retry save", or else, while no take is being recorded, the first
 * project with a recovery notice, with "Review", which opens its original recordings, and a close button.
 */
sealed interface RecoveryBanner {
    /** Changes not saved; [memoryOnly] when some of them have no journal and would be lost with the app. */
    data class Unsaved(val memoryOnly: Boolean) : RecoveryBanner

    /** A recovery notice of the project in folder [projectID]. */
    data class Notice(val projectID: String, val text: String) : RecoveryBanner

    companion object {
        /** What the bar shows now, or null for no bar. */
        fun of(store: ProjectStore, recording: Boolean): RecoveryBanner? {
            if (store.unsavedDrafts.isNotEmpty()) return Unsaved(!store.journaledDrafts.containsAll(store.unsavedDrafts.keys))
            if (recording) return null
            val id = store.recoveryMessages.keys.minOrNull() ?: return null
            return Notice(id, store.recoveryMessages.getValue(id))
        }
    }
}

@Composable
fun RecoveryBanner(banner: RecoveryBanner, store: ProjectStore, review: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Bar).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (banner) {
            is RecoveryBanner.Unsaved -> {
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, null, Modifier.size(18.dp), tint = Orange)
                        Text(stringResource(R.string.recovery_unsaved_title), Modifier.padding(start = 6.dp), color = Orange, fontWeight = FontWeight.SemiBold)
                    }
                    if (banner.memoryOnly) Text(stringResource(R.string.recovery_unsaved_memory), color = Orange, fontSize = 13.sp)
                }
                TextButton(onClick = store::retrySaves) { Text(stringResource(R.string.recovery_retry_save), color = Orange) }
            }
            is RecoveryBanner.Notice -> {
                Text(banner.text, Modifier.weight(1f), color = Color.White, fontSize = 13.sp)
                TextButton(onClick = { review(banner.projectID) }) { Text(stringResource(R.string.projects_review)) }
                IconButton(onClick = { store.recoveryMessages.remove(banner.projectID) }, Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp)) {
                    Icon(Icons.Filled.Close, stringResource(R.string.recovery_dismiss), tint = Color.White)
                }
            }
        }
    }
}
