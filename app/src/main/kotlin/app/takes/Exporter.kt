@file:OptIn(UnstableApi::class)

package app.takes

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.GainProcessor
import androidx.media3.effect.OverlayEffect
import app.takes.core.TimelineEditing
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import app.takes.core.Project
import app.takes.core.TimelineClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/** One timeline clip as a player or export item, cut to its source range. */
fun clippedItem(clip: TimelineClip, file: File): MediaItem = MediaItem.Builder()
    .setUri(Uri.fromFile(file))
    .setMediaId(clip.id)
    .setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder()
            .setStartPositionUs((clip.start * 1e6).roundToLong())
            .setEndPositionUs((clip.end * 1e6).roundToLong())
            .build(),
    ).build()

/** Upright picture size with even dimensions, or null when the file has no readable video. */
fun displaySize(file: File): Pair<Int, Int>? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.path)
        fun number(key: Int) = retriever.extractMetadata(key)?.toIntOrNull()
        val width = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: return null
        val height = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: return null
        val turned = (number(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0) % 180 != 0
        val (w, h) = if (turned) height to width else width to height
        (w / 2 * 2) to (h / 2 * 2)
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

/**
 * Renders the current edit to a new movie in the project's `Exports` folder.
 * Sources are never modified. The picture takes the size of the first clip;
 * other clips are scaled to fit inside it.
 */
class Exporter(private val context: Context, private val store: ProjectStore) {
    private val scope = MainScope()
    private var transformer: Transformer? = null
    private var partial: File? = null

    var progress by mutableStateOf<Float?>(null); private set
    var result by mutableStateOf<File?>(null)

    fun start(project: Project) {
        if (progress != null) return
        val clips = project.edit.clips
        if (clips.isEmpty()) { store.error = "Add a recording before exporting."; return }
        progress = 0f
        scope.launch {
            val files = project.sources.associate { it.id to store.file(project, it) }
            val canvas = withContext(Dispatchers.IO) { files[clips.first().sourceID]?.let(::displaySize) }
            if (progress == null) return@launch // cancelled while reading
            if (canvas == null || clips.any { files[it.sourceID]?.isFile != true }) {
                store.error = "A source file is missing or unreadable. The project has been preserved."
                progress = null
                return@launch
            }
            val folder = File(store.disk.folder(project.id), "Exports").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val name = project.title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(60).ifEmpty { "Movie" } + " $stamp.mp4"
            val output = File(folder, name)
            val writing = File(folder, ".$name")
            val fit = Presentation.createForWidthAndHeight(canvas.first, canvas.second, Presentation.LAYOUT_SCALE_TO_FIT)
            val items = clips.mapIndexed { index, clip ->
                val fadeIn = TimelineEditing.fadeLength(clips, index - 1)
                val fadeOut = TimelineEditing.fadeLength(clips, index)
                val fades = fadeIn > 0 || fadeOut > 0
                val begins = TimelineEditing.clipStart(clips, index)
                val picture = buildList<Effect> {
                    add(fit)
                    if (!clip.texts.isNullOrEmpty()) add(OverlayEffect(listOf(TitleOverlay(clip, begins, canvas.first, canvas.second))))
                    if (fades) add(FadeEffect(begins, clip.duration, fadeIn, fadeOut)) // after the titles, so they fade too
                }
                val sound = if (fades) listOf<AudioProcessor>(GainProcessor(FadeGain(clip.duration, fadeIn, fadeOut))) else emptyList()
                EditedMediaItem.Builder(clippedItem(clip, files.getValue(clip.sourceID)))
                    .setEffects(Effects(sound, picture))
                    .build()
            }
            // Silence is generated for takes recorded without a microphone.
            val composition = Composition.Builder(EditedMediaItemSequence.withAudioAndVideoFrom(items)).build()
            val running = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (writing.renameTo(output)) result = output
                        else store.error = "The exported movie could not be saved."
                        finish()
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        writing.delete()
                        store.error = "Export failed: ${exportException.message ?: exportException.errorCodeName}"
                        finish()
                    }
                }).build()
            transformer = running
            partial = writing
            running.start(composition, writing.path)
            val holder = ProgressHolder()
            while (transformer === running) {
                if (running.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress = holder.progress / 100f
                delay(200)
            }
        }
    }

    fun cancel() {
        transformer?.cancel()
        partial?.delete()
        finish()
    }

    private fun finish() {
        transformer = null
        partial = null
        progress = null
    }

    /** Copies a finished export to the device's Movies collection. */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveToGallery(file: File) {
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Takes")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                    ?: return@withContext false
                try {
                    resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                    true
                } catch (_: Exception) {
                    resolver.delete(uri, null, null)
                    false
                }
            }
            store.error = if (saved) "Saved to Movies/Takes in your gallery." else "The movie could not be saved to the gallery."
        }
    }
}
