@file:OptIn(UnstableApi::class)

package app.takes

import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
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
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.effect.Crop
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.PassthroughShaderProgram
import app.takes.core.FrameCeiling
import androidx.media3.effect.OverlayEffect
import app.takes.core.AudioItem
import app.takes.core.ExportSettings
import app.takes.core.Rect as FrameRect
import app.takes.core.Size as FrameSize
import app.takes.core.TransitionLayout
import app.takes.core.TransitionScene
import app.takes.core.SubtitleEditing
import app.takes.core.TimelineEditing
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.SingleColorLut
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
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * The microsecond a clip is cut at, for a source second: never later than it.
 * A file gives each frame its time rounded down to the microsecond. A cut on
 * the frame grid rounded to the nearest one would fall just after its frame
 * one time in three, and the piece before the cut would keep that frame while
 * the piece after it lost it: a piece one frame long would lose its only frame.
 */
fun cutMicros(seconds: Double): Long = floor(seconds * 1e6).toLong()

/** One timeline clip as a player or export item, cut to its source range. */
fun clippedItem(clip: TimelineClip, file: File): MediaItem = MediaItem.Builder()
    .setUri(Uri.fromFile(file))
    .setMediaId(clip.id)
    .setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder()
            .setStartPositionUs(cutMicros(clip.start))
            .setEndPositionUs(cutMicros(clip.end))
            .build(),
    ).build()

/** A clip's speed for the export: one rate for the whole clip, picture and sound, with the pitch kept. */
fun steadySpeed(clip: TimelineClip): SpeedProvider? = clip.rate.takeIf { it != 1.0 }?.let { rate ->
    object : SpeedProvider {
        override fun getSpeed(timeUs: Long) = rate.toFloat()
        override fun getNextSpeedChangeTimeUs(timeUs: Long) = C.TIME_UNSET
    }
}

/**
 * The export's ceiling on a clip's frame rate ([FrameCeiling]). Media3's own
 * frame-rate limit holds each frame back until the next one arrives and
 * drops the one it is holding when the clip ends, so every clip lost its
 * last frame; this one decides each frame as it comes.
 */
class FrameCeilingEffect(private val framesPerSecond: Int) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = object : PassthroughShaderProgram() {
        private val ceiling = FrameCeiling(framesPerSecond.toDouble())

        override fun queueInputFrame(glObjectsProvider: GlObjectsProvider, inputTexture: GlTextureInfo, presentationTimeUs: Long) {
            if (ceiling.keeps(presentationTimeUs)) super.queueInputFrame(glObjectsProvider, inputTexture, presentationTimeUs) else {
                inputListener.onInputFrameProcessed(inputTexture)
                inputListener.onReadyToAcceptInputFrame()
            }
        }

        override fun signalEndOfCurrentInputStream() {
            super.signalEndOfCurrentInputStream()
            ceiling.reset()
        }

        override fun flush() {
            super.flush()
            ceiling.reset()
        }
    }
}

/** One audio item as a player or export item, cut to the part of its source it plays. */
fun clippedItem(item: AudioItem, file: File, seconds: Double = item.duration): MediaItem = MediaItem.Builder()
    .setUri(Uri.fromFile(file))
    .setMediaId(item.id)
    .setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder()
            .setStartPositionUs((item.sourceStart * 1e6).roundToLong())
            .setEndPositionUs(((item.sourceStart + seconds) * 1e6).roundToLong())
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

/** The frames per second of the file's picture as its container states them, or null when it does not say. */
fun frameRate(file: File): Double? {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(file.path)
        (0 until extractor.trackCount).map(extractor::getTrackFormat).firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }?.let { format ->
            if (!format.containsKey(MediaFormat.KEY_FRAME_RATE)) null
            else runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrElse { format.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }
        }?.takeIf { it.isFinite() && it > 0 }
    } catch (_: Exception) {
        null
    } finally {
        runCatching { extractor.release() }
    }
}

/**
 * Renders the current edit to a new movie in the project's `Exports` folder.
 * Sources are never modified. The picture takes the size of the first clip;
 * other clips are scaled to fit inside it.
 */
class Exporter(private val context: Context, private val store: ProjectStore) {
    // The movie is always SDR, so HDR footage from a phone camera is tone-mapped.
    private val sdr = Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL

    private companion object {
        /** Share of the progress bar given to noise reduction when a movie needs it. */
        const val CLEANING = 0.25f
    }

    private val scope = MainScope()
    private var job: Job? = null

    var progress by mutableStateOf<Float?>(null); private set
    var result by mutableStateOf<File?>(null)

    fun start(project: Project) {
        if (progress != null) return
        val clips = project.edit.clips
        if (clips.isEmpty()) { store.error = context.getString(R.string.export_no_clips); return }
        progress = 0f
        job = scope.launch {
            val files = project.sources.associate { it.id to store.file(project, it) }
            val settings = project.exportSettings
            // The first clip gives the picture its shape; the settings give it its size.
            val canvas = withContext(Dispatchers.IO) { files[clips.first().sourceID]?.let(::displaySize) }
                ?.let { settings.resolution.size(it.first, it.second) }
            val sounds = project.edit.audio.orEmpty()
            if (canvas == null || clips.any { files[it.sourceID]?.isFile != true } || sounds.any { files[it.sourceID]?.isFile != true }) {
                store.error = context.getString(R.string.export_source_missing)
                progress = null
                return@launch
            }
            // Two clips playing at once are drawn from a frame that holds both, twice as tall as the movie
            // (DecksCompositor). A GPU that cannot hold it would fail halfway: say so before any work is done.
            if (TimelineEditing.chains(clips).size > 1) {
                val side = withContext(Dispatchers.Default) { largestTextureSide() }
                if (side != null && (canvas.first > side || canvas.second * 2 > side)) {
                    store.error = context.getString(R.string.export_transitions_too_large, canvas.first, canvas.second)
                    progress = null
                    return@launch
                }
            }
            val folder = File(store.disk.folder(project.id), "Exports").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val name = project.title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(60).ifEmpty { context.getString(R.string.export_fallback_name) } + " $stamp.mp4"
            val output = File(folder, name)
            val writing = File(folder, ".$name")
            // Noise reduction: make the cleaned sound of every clip that asks for it, before rendering.
            val wanted = clips.mapIndexedNotNull { index, clip -> clip.audio?.denoise?.takeIf { it > 0 }?.let { index to it } }
            val clean = mutableMapOf<Int, File>()
            wanted.forEachIndexed { step, (index, amount) ->
                Denoiser.process(files.getValue(clips[index].sourceID), amount) { progress = CLEANING * (step + it) / wanted.size }?.let { clean[index] = it }
            }
            val began = if (wanted.isEmpty()) 0f else CLEANING
            // The upright size of each recording that is cropped; the window is measured in its pixels.
            val sizes = withContext(Dispatchers.IO) {
                clips.map { it.sourceID }.distinct().associateWith { files[it]?.let(::displaySize) }
            }
            // A flash cut counts its frames in the frame rate the movie has while it plays, which follows the footage.
            val rates = withContext(Dispatchers.IO) { clips.map { it.sourceID }.distinct().associateWith { files[it]?.let(::frameRate) } }
            val footage = clips.map { rates[it.sourceID] }
            val frames = { join: TransitionLayout.Join -> TransitionScene.frameRate(clips, join, footage, settings.frameRate) }
            val fit = Presentation.createForWidthAndHeight(canvas.first, canvas.second, Presentation.LAYOUT_SCALE_TO_FIT)
            val items = clips.mapIndexed { index, clip ->
                val picture = buildList<Effect> {
                    // A ceiling for high frame rate footage; frames are never invented, and footage
                    // at or below the ceiling keeps every frame.
                    if (settings.frameRate <= 30) add(FrameCeilingEffect(settings.frameRate))
                    // Color on the footage itself, before it is fitted: the bars around a clip of another shape stay black.
                    clip.color?.let { add(SingleColorLut.createFromCube(ColorLuts.cube(it, folder.parentFile!!))) }
                    // A crop cuts the window out of the footage; fitting then fills the frame with it.
                    val source = sizes[clip.sourceID]
                    if (clip.crop != null && source != null) {
                        val window = clip.crop!!.rect(FrameSize(source.first.toDouble(), source.second.toDouble()), FrameSize(canvas.first.toDouble(), canvas.second.toDouble()))
                        // Media3 wants the window in its −1…1 coordinates, y upward.
                        add(Crop(
                            (2 * window.x / source.first - 1).toFloat(), (2 * (window.x + window.width) / source.first - 1).toFloat(),
                            (1 - 2 * (window.y + window.height) / source.second).toFloat(), (1 - 2 * window.y / source.second).toFloat(),
                        ))
                    }
                    add(fit)
                }
                // Sound follows the clip's own level and fades, and any transition at its ends.
                val shaped = clip.audio != null || TimelineEditing.entering(clips, index) != null || TimelineEditing.leaving(clips, index) != null
                val sound = if (shaped) listOf<AudioProcessor>(SoundProcessor { TimelineEditing.soundLevel(clips, index, it) }) else emptyList()
                // Speed comes first in Media3, so the effects after it are timed in movie seconds.
                EditedMediaItem.Builder(clippedItem(clip, files.getValue(clip.sourceID)))
                    .setRemoveAudio(index in clean) // its cleaned sound plays from a sequence of its own
                    .setEffects(Effects(sound, picture))
                    .apply { steadySpeed(clip)?.let(::setSpeed) }
                    .build()
            }
            // Sound on its own tracks: one audio-only sequence per track, silent between
            // items. The movie ends with its picture, so later sound is left out.
            val total = TimelineEditing.duration(clips)
            val soundtracks = (0 until AudioItem.TRACKS).mapNotNull { track ->
                val items = sounds.filter { it.track == track && it.start < total - 0.01 }.sortedBy { it.start }
                if (items.isEmpty()) return@mapNotNull null
                val sequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
                var filled = 0.0
                fun gap(until: Double) {
                    val micros = ((until - filled) * 1e6).roundToLong()
                    if (micros > 0) sequence.addGap(micros)
                }
                for (item in items) {
                    gap(item.start)
                    val seconds = minOf(item.end, total) - item.start
                    sequence.addItem(
                        EditedMediaItem.Builder(clippedItem(item, files.getValue(item.sourceID), seconds)).setRemoveVideo(true)
                            .setEffects(Effects(listOf<AudioProcessor>(SoundProcessor { TimelineEditing.soundLevel(item, it) }), emptyList()))
                            .build(),
                    )
                    filled = item.start + seconds
                }
                gap(total)
                sequence.build()
            }
            val chains = TimelineEditing.chains(clips)
            // Cleaned clip sound, in place of the recorded one: audio-only sequences at the
            // clips' own times. Clips that dissolve into each other overlap, so chains alternate.
            val cleaned = (0..1).mapNotNull { parity ->
                val indices = clean.keys.sorted().filter { index -> chains.indexOfFirst { index in it.first..it.last } % 2 == parity }
                if (indices.isEmpty()) return@mapNotNull null
                val sequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
                var filled = 0.0
                fun gap(until: Double) {
                    val micros = ((until - filled) * 1e6).roundToLong()
                    if (micros > 0) sequence.addGap(micros)
                }
                for (index in indices) {
                    val clip = clips[index]
                    val begins = TimelineEditing.clipStart(clips, index)
                    gap(begins)
                    sequence.addItem(
                        EditedMediaItem.Builder(clippedItem(clip, clean.getValue(index)))
                            .setEffects(Effects(listOf<AudioProcessor>(SoundProcessor { TimelineEditing.soundLevel(clips, index, it) }), emptyList()))
                            .apply { steadySpeed(clip)?.let(::setSpeed) }
                            .build(),
                    )
                    filled = begins + clip.duration
                }
                gap(total)
                sequence.build()
            }
            val extra = soundtracks + cleaned
            // Titles and subtitles are drawn once, over the finished picture; subtitles on top.
            val captions = SubtitleEditing.cues(project.edit)
            val written = listOfNotNull(
                if (clips.any { !it.texts.isNullOrEmpty() }) TitleOverlay(clips, canvas.first, canvas.second, context.assets, frames) else null,
                project.edit.subtitles?.takeIf { captions.isNotEmpty() }?.let { SubtitleOverlay(captions, it, canvas.first, canvas.second) },
            )
            val titles = if (written.isNotEmpty()) Effects(emptyList(), listOf<Effect>(OverlayEffect(written))) else Effects.EMPTY
            // Where each clip's own picture lies in the frame: all of it when cropped, else fitted inside it.
            val frame = FrameSize(canvas.first.toDouble(), canvas.second.toDouble())
            val content = { index: Int ->
                val source = sizes[clips[index].sourceID]
                if (clips[index].crop != null || source == null) FrameRect(0.0, 0.0, frame.width, frame.height) else {
                    val scale = minOf(frame.width / source.first, frame.height / source.second)
                    FrameRect((frame.width - source.first * scale) / 2, (frame.height - source.second * scale) / 2, source.first * scale, source.second * scale)
                }
            }
            // Transitions are drawn on the finished frame, from one sequence or from two.
            val joined = TimelineEditing.transitions(clips).isNotEmpty()
            fun drawn(decks: Int) = TransitionEffect(clips, Size(canvas.first, canvas.second), decks, frames, content)
            // Titles and subtitles go over the frame the transition effect has finished, in the same
            // pass. (While Media3 itself blended the two sequences, an overlay in the same composition
            // corrupted the blend, and such a movie took a second pass and a second encoding.)
            val movie = if (chains.size == 1) {
                val chain = chains[0]
                val effects = if (joined) Effects(emptyList(), listOf<Effect>(drawn(1)) + titles.videoEffects) else titles
                // Silence is generated for takes recorded without a microphone.
                val sequence = if (chain.start < 0.0005 && total - chain.end < 0.0005) EditedMediaItemSequence.withAudioAndVideoFrom(items) else {
                    // A freeze before the first clip or after the last one: time with no clip in it.
                    val padded = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
                    ((chain.start * 1e6).roundToLong()).takeIf { it > 0 }?.let(padded::addGap)
                    items.forEach(padded::addItem)
                    (((total - chain.end) * 1e6).roundToLong()).takeIf { it > 0 }?.let(padded::addGap)
                    padded.build()
                }
                Composition.Builder(listOf(sequence) + extra).setEffects(effects).setHdrMode(sdr).build()
            } else {
                // Chains alternate between two sequences, since neighbors play at once or
                // with a pause between them. The two are laid side by side and the
                // transition effect makes each frame from them.
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
                Composition.Builder(sequences.map { it.build() } + extra)
                    .setVideoCompositorSettings(DecksCompositor(Size(canvas.first, canvas.second)))
                    .setEffects(Effects(emptyList(), listOf<Effect>(drawn(2)) + titles.videoEffects))
                    .setHdrMode(sdr)
                    .build()
            }
            val codec = if (settings.codec == ExportSettings.Codec.HEVC) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
            try {
                render(movie, writing, codec, began, 1f)
                if (writing.renameTo(output)) result = output else store.error = context.getString(R.string.export_save_failed)
            } catch (problem: ExportException) {
                store.error = context.getString(R.string.export_failed, problem.message ?: problem.errorCodeName)
            } finally {
                writing.delete()
                progress = null
            }
        }
    }

    /** Runs one export to [file], reporting progress between [from] and [to]. Cancelling the coroutine cancels the export. */
    private suspend fun render(composition: Composition, file: File, codec: String, from: Float, to: Float) = coroutineScope {
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
                    .setVideoMimeType(codec)
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
            store.error = context.getString(if (saved) R.string.export_gallery_saved else R.string.export_gallery_failed)
        }
    }
}
