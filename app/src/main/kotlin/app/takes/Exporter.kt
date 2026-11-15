@file:OptIn(UnstableApi::class)

package app.takes

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.metrics.LogSessionId
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.createBitmap
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.util.Size
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.effect.MatrixTransformation
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.PassthroughShaderProgram
import app.takes.core.FrameCeiling
import app.takes.core.PictureFraming
import app.takes.core.matrixValues
import app.takes.core.normalized
import androidx.media3.effect.OverlayEffect
import app.takes.core.AudioItem
import app.takes.core.SoundEnvelope
import app.takes.core.ExportSections
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
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
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

/**
 * Gives the frames that pass through other times: [retimed] of their own, in microseconds.
 *
 * It is used twice on a movie that a clock gives its frames. The clock's frames are given times a few
 * microseconds early, and the frames of the movie made from them the times of the movie's frame grid.
 *
 * Early, because Media3 draws, for each frame of the clock, the frame of a deck nearest to it in time. Footage at
 * half the movie's frame rate has two frames equally near every other frame of the clock, and a microsecond
 * decided between them (a file gives its frames times cut down to the microsecond, the clock's are rounded): on
 * two such frames in three the movie showed the frame that had not begun yet, so that a frame of the footage
 * lasted one frame of the movie and the next one three. A few microseconds earlier, the frame that is showing is
 * always the nearer one, and footage at the movie's own rate is not concerned.
 *
 * On the grid, because Media3 counts the clock's frame times in a frame length it keeps with seven digits: they
 * fall behind the grid by a microsecond every thousand frames. A flash cut counts the frames of the movie from
 * their times, and forty seconds into a movie its flashes came a frame late.
 */
class FrameTimeEffect(private val retimed: (Long) -> Long) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = object : PassthroughShaderProgram() {
        override fun queueInputFrame(glObjectsProvider: GlObjectsProvider, inputTexture: GlTextureInfo, presentationTimeUs: Long) =
            super.queueInputFrame(glObjectsProvider, inputTexture, retimed(presentationTimeUs))
    }
}

/**
 * A cropped clip's picture in the export: the footage zoomed, turned and
 * moved into the movie's frame, which it fills. The framing is worked out
 * for each frame by [PictureFraming.frame], the function the preview draws
 * from, so a crop that moves between two poses moves here as it does there.
 *
 * [begins] is where the clip starts in the movie: Media3 times a clip's
 * frames from the start of its sequence, after its speed, and the sequences
 * are filled with stills so that their time is the movie's. [source] is the
 * upright size of the recording and [canvas] the size of the movie's frame.
 */
class FramingEffect(private val clip: TimelineClip, private val begins: Double, private val source: FrameSize, private val canvas: FrameSize) : MatrixTransformation {
    private val matrix = android.graphics.Matrix()

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(canvas.width.toInt(), canvas.height.toInt())

    override fun getMatrix(presentationTimeUs: Long): android.graphics.Matrix {
        val frame = PictureFraming.frame(clip, presentationTimeUs / 1e6 - begins, source, canvas)
        // Media3 moves the corners of the picture in −1…1 coordinates, y upward, and draws what stays inside the frame.
        matrix.setValues(frame.transform.normalized(source, canvas).matrixValues())
        return matrix
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

/** A black picture for the stretches of an export that have no clip, made once and kept with the app's temporary files. */
fun blackStill(context: Context): File = File(context.cacheDir, "export-black.png").also { file ->
    if (file.length() == 0L) {
        val bitmap = createBitmap(16, 16).apply { eraseColor(Color.BLACK) }
        val writing = File(file.parentFile, ".${file.name}")
        writing.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        writing.renameTo(file)
    }
}

/** The picture in [file] shown for [micros] microseconds as an export item, [framesPerSecond] frames of it a second. */
fun still(file: File, micros: Long, framesPerSecond: Int, effects: List<Effect>): EditedMediaItem =
    EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.fromFile(file)).setImageDurationMs(maxOf(1, micros / 1000)).build())
        .setDurationUs(micros)
        .setFrameRate(framesPerSecond)
        .setEffects(Effects(emptyList(), effects))
        .build()

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

/** Whether the file has sound, as its container states it. */
fun hasSound(file: File): Boolean {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(file.path)
        (0 until extractor.trackCount).any { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
    } catch (_: Exception) {
        false
    } finally {
        runCatching { extractor.release() }
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
 * The encoders of a movie that a clock gives its frames. Media3 tells the video encoder the frame rate of the
 * first thing it is given, which is then the clock's still, and a still states none: every movie would be
 * encoded as one of 30 frames a second, with the bitrate Media3 picks for 30. This states the movie's own.
 */
class ClockedEncoders(private val encoders: Codec.EncoderFactory, private val framesPerSecond: Int) : Codec.EncoderFactory {
    override fun createForAudioEncoding(format: Format, logSessionId: LogSessionId?): Codec = encoders.createForAudioEncoding(format, logSessionId)

    override fun createForVideoEncoding(format: Format, logSessionId: LogSessionId?): Codec =
        encoders.createForVideoEncoding(format.buildUpon().setFrameRate(framesPerSecond.toFloat()).build(), logSessionId)

    override fun audioNeedsEncoding(): Boolean = encoders.audioNeedsEncoding()

    override fun videoNeedsEncoding(): Boolean = encoders.videoNeedsEncoding()
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

        /** How much earlier than its own time a frame of the clock asks the decks for theirs, in microseconds ([FrameTimeEffect]). */
        const val CLOCK_LEAD = 3L
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
            val rates = withContext(Dispatchers.IO) { clips.map { it.sourceID }.distinct().associateWith { files[it]?.let(::frameRate) } }
            val total = TimelineEditing.duration(clips)
            val chains = TimelineEditing.chains(clips)
            val joined = TimelineEditing.transitions(clips).isNotEmpty()
            // A movie with a transition or a freeze in it is given its frames by a clock, at one rate that follows the
            // footage under the ceiling of the settings; a flash cut counts its frames in it. A movie of plain cuts
            // is the clips one after the other and keeps the frame times of the footage.
            val clocked = joined || chains.size > 1 || chains[0].start >= 0.0005 || total - chains[0].end >= 0.0005
            val rate = TransitionScene.frameRate(clips.map { rates[it.sourceID] }, settings.frameRate)
            val frames = { _: TransitionLayout.Join -> rate.toDouble() }
            // One for every item: a Presentation keeps the matrix it worked out for the size of its last input, and
            // two items of different shapes that play at once, one in each sequence, would be drawn with each other's.
            fun fit() = Presentation.createForWidthAndHeight(canvas.first, canvas.second, Presentation.LAYOUT_SCALE_TO_FIT)
            val items = clips.mapIndexed { index, clip ->
                val picture = buildList<Effect> {
                    // A ceiling for high frame rate footage; frames are never invented, and footage
                    // at or below the ceiling keeps every frame. Under a clock nothing is thinned
                    // beforehand: the clock takes, for each of its frames, the nearest of the footage.
                    if (!clocked && settings.frameRate <= TransitionScene.HIGHEST_CEILING) add(FrameCeilingEffect(settings.frameRate))
                    // Color on the footage itself, before it is fitted: the bars around a clip of another shape stay black.
                    clip.color?.let { add(SingleColorLut.createFromCube(ColorLuts.cube(it, folder.parentFile!!))) }
                    // A crop fills the frame with part of the footage, frame by frame; fitting then has nothing left to do.
                    val source = sizes[clip.sourceID]
                    if (clip.crop != null && source != null) {
                        add(FramingEffect(
                            clip, TimelineEditing.clipStart(clips, index),
                            FrameSize(source.first.toDouble(), source.second.toDouble()), FrameSize(canvas.first.toDouble(), canvas.second.toDouble()),
                        ))
                    }
                    add(fit())
                }
                // Sound follows the clip's own level and fades and any transition at its ends, sample by sample, and fades for a
                // few milliseconds at every cut.
                val sound = listOf<AudioProcessor>(SoundProcessor(SoundEnvelope.clip(clips, index), TimelineEditing.clipStart(clips, index)))
                // Speed comes first in Media3, so the effects after it are timed in movie seconds.
                EditedMediaItem.Builder(clippedItem(clip, files.getValue(clip.sourceID)))
                    .setRemoveAudio(clocked || index in clean) // its sound plays from a sequence of its own
                    .setEffects(Effects(sound, picture))
                    .apply { steadySpeed(clip)?.let(::setSpeed) }
                    .build()
            }
            // Sound on its own tracks: one audio-only sequence per track, silent between
            // items. The movie ends with its picture, so later sound is left out.
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
                            .setEffects(Effects(listOf<AudioProcessor>(SoundProcessor(SoundEnvelope.item(sounds, item, total), item.start)), emptyList()))
                            .build(),
                    )
                    filled = item.start + seconds
                }
                gap(total)
                sequence.build()
            }
            // Clip sound on sequences of its own, audio only, at the clips' own times: the cleaned sound of a clip
            // that has it, in place of the recorded one, and under a clock the sound of every clip. Clips that
            // dissolve into each other overlap, so chains alternate.
            //
            // Under a clock the decks carry the picture alone. A deck that carries sound and opens on a still asks
            // Media3 for its silence the moment its picture is taken, and Media3 has none to give until the first
            // sequence with sound has given its own: the export then stopped with "Asset loader error", whenever
            // that deck was the quicker of the two. A sequence of sound alone waits instead.
            val voiced = if (!clocked) emptyMap() else withContext(Dispatchers.IO) { clips.map { it.sourceID }.distinct().associateWith { files[it]?.let(::hasSound) == true } }
            val apart = clips.indices.filter { it in clean || voiced[clips[it].sourceID] == true }
            val cleaned = (0..1).mapNotNull { parity ->
                val indices = apart.filter { index -> chains.indexOfFirst { index in it.first..it.last } % 2 == parity }
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
                        EditedMediaItem.Builder(clippedItem(clip, clean[index] ?: files.getValue(clip.sourceID))).setRemoveVideo(true)
                            .setEffects(Effects(listOf<AudioProcessor>(SoundProcessor(SoundEnvelope.clip(clips, index), begins)), emptyList()))
                            .apply { steadySpeed(clip)?.let(::setSpeed) }
                            .build(),
                    )
                    filled = begins + clip.duration
                }
                gap(total)
                sequence.build()
            }
            // A movie under a clock with no sound anywhere still has a sound track, silent, as it has when its
            // clips carry their sound themselves.
            val extra = (soundtracks + cleaned).ifEmpty {
                if (clocked) listOf(EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)).addGap((total * 1e6).roundToLong()).build()) else emptyList()
            }
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
            // Titles and subtitles go over the frame the transition effect has finished, in the same
            // pass. (While Media3 itself blended the two sequences, an overlay in the same composition
            // corrupted the blend, and such a movie took a second pass and a second encoding.)
            val movie = if (!clocked) {
                // Silence is generated for takes recorded without a microphone.
                Composition.Builder(listOf(EditedMediaItemSequence.withAudioAndVideoFrom(items)) + extra).setEffects(titles).setHdrMode(sdr).build()
            } else {
                // The clock: a sequence of its own, first, that holds one still for the whole movie at the movie's
                // frame rate. Media3 composes one frame for every frame of its first sequence, at that frame's time,
                // and takes from each of the others the frame nearest to it. Without the clock the first deck gave
                // the times: its footage's while it played, and 30 a second where it was empty, whatever the footage
                // on the other deck. The clock is not drawn (DecksCompositor).
                val black = withContext(Dispatchers.IO) { blackStill(context) }
                val ticks = ExportSections.pictures(total, rate)
                val clock = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
                    .addItem(still(black, Math.round(ticks * 1e6 / rate), rate, listOf(FrameTimeEffect { maxOf(0, it - CLOCK_LEAD) }))).build()
                // The clips are on one deck, or, where neighbors play at once or with a pause between them, their
                // chains alternate between two. The decks are laid one above the other and the transition effect makes
                // each frame from them.
                val decks = minOf(chains.size, 2)
                val sequences = List(decks) { EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO)) }
                val filled = DoubleArray(decks)
                // Where a deck has no clip it shows a black still, fitted like a clip. Media3's own gap
                // gives frames of 16 by 16 pixels: at every change between those and a clip's frames the deck's
                // textures were made again while the compositor still held frames in the old ones, which came out
                // black (a clip's last frame) or were drawn over too soon (a clip two frames ahead of its time).
                fun gap(deck: Int, until: Double) {
                    val micros = ((until - filled[deck]) * 1e6).roundToLong()
                    if (micros > 0) sequences[deck].addItem(still(black, micros, rate, listOf(fit())))
                }
                chains.forEachIndexed { index, chain ->
                    gap(index % decks, chain.start)
                    for (clip in chain.first..chain.last) sequences[index % decks].addItem(items[clip])
                    filled[index % decks] = chain.end
                }
                for (deck in 0 until decks) gap(deck, total)
                Composition.Builder(listOf(clock) + sequences.map { it.build() } + extra)
                    .setVideoCompositorSettings(DecksCompositor(Size(canvas.first, canvas.second), decks))
                    .setEffects(Effects(emptyList(), listOf<Effect>(FrameTimeEffect { Math.round(Math.round(it * rate / 1e6) * 1e6 / rate) }, TransitionEffect(clips, Size(canvas.first, canvas.second), decks, frames, content)) + titles.videoEffects))
                    .setHdrMode(sdr)
                    .build()
            }
            val codec = if (settings.codec == ExportSettings.Codec.HEVC) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
            try {
                render(movie, writing, codec, began, 1f, rate.takeIf { clocked })
                if (writing.renameTo(output)) result = output else store.error = context.getString(R.string.export_save_failed)
            } catch (problem: ExportException) {
                store.error = context.getString(R.string.export_failed, problem.message ?: problem.errorCodeName)
            } finally {
                writing.delete()
                progress = null
            }
        }
    }

    /**
     * Runs one export to [file], reporting progress between [from] and [to]. Cancelling the coroutine cancels the export.
     * [framesPerSecond] is the movie's frame rate when a clock gives it one; null when its frames are those of the footage.
     */
    private suspend fun render(composition: Composition, file: File, codec: String, from: Float, to: Float, framesPerSecond: Int?) = coroutineScope {
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
                    .apply { framesPerSecond?.let { setEncoderFactory(ClockedEncoders(DefaultEncoderFactory.Builder(context).build(), it)) } }
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
