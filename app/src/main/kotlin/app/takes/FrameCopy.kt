package app.takes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.takes.core.FrameCopy
import app.takes.core.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Copy frame on the editor's preview (YouRec/EditorView.swift:1795-1835, 1946-1952, 2044-2048): a long press copies
 * the edited frame showing at the playhead to the clipboard as a PNG, with "Copying frame…", then "Frame copied" and
 * a light tap of the phone, or "Couldn’t copy this frame", over the picture for two seconds, read by the screen
 * reader. The frame is the movie's own drawing, as the cover's ([CoverFrames]), where the original asks its image
 * generator for the editor's composition; the PNG is kept with the project and handed to the clipboard as a content
 * URI of the app's FileProvider, as Android's clipboard holds no image data.
 */
@Stable
class PreviewFrameCopy(private val context: Context, private val scope: CoroutineScope, private val haptics: HapticFeedback) {
    /** What the picture says: copying, copied or failed; null when nothing is shown. */
    var message: String? by mutableStateOf(null)
        private set

    private var job: Job? = null

    /** Copies the frame of [project] showing at second [time] of a movie [duration] long. */
    fun copy(project: Project, store: ProjectStore, time: Double, duration: Double) {
        job?.cancel()
        message = context.getString(R.string.frame_copy_copying)
        job = scope.launch {
            val copied = runCatching { copied(project, store, FrameCopy.pictureTime(time, duration, FRAME)) }.getOrDefault(false)
            if (copied) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            message = context.getString(if (copied) R.string.frame_copy_done else R.string.frame_copy_failed)
            delay((FrameCopy.FEEDBACK_SECONDS * 1000).toLong())
            message = null
        }
    }

    /** Leaves the copy under way, as the original does when the preview goes away. */
    fun cancel() {
        job?.cancel()
        message = null
    }

    private suspend fun copied(project: Project, store: ProjectStore, time: Double): Boolean {
        val frames = CoverFrames.of(context.applicationContext, project, store) ?: return false
        val frame = frames.frame(time, thumbnail = false) ?: return false
        val file = withContext(Dispatchers.IO) { png(frame, File(File(store.disk.folder(project.id), "Cache"), "frames")) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, context.getString(R.string.frame_copy_label), uri))
        return true
    }

    private companion object {
        /** The editor's picture clock, 30 frames a second (YouRec/EditorController.swift:672), as the cover's. */
        const val FRAME = 1.0 / 30

        /** [frame] as `frame.png` in [folder], in place of the one copied before. */
        fun png(frame: Bitmap, folder: File): File {
            folder.mkdirs()
            val file = File(folder, "frame.png")
            val partial = File(folder, "frame.png.partial")
            partial.outputStream().use { if (!frame.compress(Bitmap.CompressFormat.PNG, 100, it)) error("PNG not written") }
            if (!partial.renameTo(file)) error("PNG not kept")
            return file
        }
    }
}

/** A [PreviewFrameCopy] for the editor's preview. */
@Composable
fun rememberPreviewFrameCopy(): PreviewFrameCopy {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    return remember(context, scope) { PreviewFrameCopy(context, scope, haptics) }
}

/** The copy's feedback over the picture, as the original's capsule (EditorView.swift:1810-1815). */
@Composable
fun FrameCopyFeedback(copy: PreviewFrameCopy, modifier: Modifier = Modifier) {
    val message = copy.message ?: return
    Text(
        message, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}
