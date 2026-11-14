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
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.util.Size
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
    private var job: Job? = null

    var progress by mutableStateOf<Float?>(null); private set
    var result by mutableStateOf<File?>(null)

    fun start(project: Project) {
        if (progress != null) return
        val clips = project.edit.clips
        if (clips.isEmpty()) { store.error = "Add a recording before exporting."; return }
        progress = 0f
        job = scope.launch {
            val files = project.sources.associate { it.id to store.file(project, it) }
            val canvas = withContext(Dispatchers.IO) { files[clips.first().sourceID]?.let(::displaySize) }
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
                // A join is a fade or a dissolve, never both, and sound follows either one.
                val soundIn = maxOf(fadeIn, TimelineEditing.overlap(clips, index - 1))
                val soundOut = maxOf(fadeOut, TimelineEditing.overlap(clips, index))
                val begins = TimelineEditing.clipStart(clips, index)
                val picture = buildList<Effect> {
                    add(fit)
                    if (fadeIn > 0 || fadeOut > 0) add(FadeEffect(begins, clip.duration, fadeIn, fadeOut))
                }
                val sound = if (soundIn > 0 || soundOut > 0) listOf<AudioProcessor>(GainProcessor(FadeGain(clip.duration, soundIn, soundOut))) else emptyList()
                EditedMediaItem.Builder(clippedItem(clip, files.getValue(clip.sourceID)))
                    .setEffects(Effects(sound, picture))
                    .build()
            }
            val chains = TimelineEditing.chains(clips)
            // Titles are drawn once, over the finished picture.
            val titles = if (clips.any { !it.texts.isNullOrEmpty() }) {
                Effects(emptyList(), listOf<Effect>(OverlayEffect(listOf(TitleOverlay(clips, canvas.first, canvas.second)))))
            } else Effects.EMPTY
            // An overlay in the same composition corrupts the blend of two
            // sequences, so a movie with both dissolves and titles is rendered
            // in two passes: the blend first, then the titles over it.
            val blended = File(folder, ".$name.blend").takeIf { chains.size > 1 && titles != Effects.EMPTY }
            val first = if (chains.size == 1) {
                // Silence is generated for takes recorded without a microphone.
                Composition.Builder(EditedMediaItemSequence.withAudioAndVideoFrom(items)).setEffects(titles).build()
            } else {
                // Dissolves: chains alternate between two sequences that overlap in
                // time. The first is drawn on top with the opacity of its share.
                val total = TimelineEditing.duration(clips)
                val sequences = List(2) { EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO)) }
                val filled = DoubleArray(2)
                fun gap(deck: Int, until: Double) {
                    val micros = ((until - filled[deck]) * 1e6).roundToLong()
                    if (micros > 0) sequences[deck].addGap(micros)
                }
                chains.forEachIndexed { index, chain ->
                    gap(index % 2, chain.start)
                    for (clip in chain.first..chain.last) sequences[index % 2].addItem(items[clip])
                    filled[index % 2] = chain.end
                }
                for (deck in 0..1) gap(deck, total)
                Composition.Builder(sequences.map { it.build() })
                    .setVideoCompositorSettings(DissolveCompositor(clips, Size(canvas.first, canvas.second)))
                    .build()
            }
            try {
                if (blended == null) render(first, writing, 0f, 1f) else {
                    render(first, blended, 0f, 0.5f)
                    val whole = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(blended))).build()
                    render(Composition.Builder(EditedMediaItemSequence.withAudioAndVideoFrom(listOf(whole))).setEffects(titles).build(), writing, 0.5f, 1f)
                }
                if (writing.renameTo(output)) result = output else store.error = "The exported movie could not be saved."
            } catch (problem: ExportException) {
                store.error = "Export failed: ${problem.message ?: problem.errorCodeName}"
            } finally {
                blended?.delete()
                writing.delete()
                progress = null
            }
        }
    }

    /** Runs one export to [file], reporting progress between [from] and [to]. Cancelling the coroutine cancels the export. */
    private suspend fun render(composition: Composition, file: File, from: Float, to: Float) = coroutineScope {
        var running: Transformer? = null
        val watching = launch {
            val holder = ProgressHolder()
            while (true) {
                if (running?.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress = from + (to - from) * holder.progress / 100f
                delay(200)
            }
        }
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) = continuation.resume(Unit)

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) =
                            continuation.resumeWithException(exportException)
                    }).build()
                running = transformer
                continuation.invokeOnCancellation { transformer.cancel() }
                transformer.start(composition, file.path)
            }
            progress = to
        } finally {
            watching.cancel()
        }
    }

    fun cancel() {
        job?.cancel()
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
