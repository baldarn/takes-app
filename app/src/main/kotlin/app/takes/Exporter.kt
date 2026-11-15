@file:OptIn(UnstableApi::class, ExperimentalApi::class)

package app.takes

import app.takes.core.ExportProblem
import android.app.Activity
import android.app.Application
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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.media3.common.SpeedParameters
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
import app.takes.core.ExportCheckpoint
import app.takes.core.ExportProgress
import app.takes.core.ExportReceipt
import app.takes.core.ExportReceipts
import app.takes.core.ExportSections
import app.takes.core.ExportSpace
import app.takes.core.ExportSpaceException
import app.takes.core.ExportSettings
import app.takes.core.Rect as FrameRect
import app.takes.core.Size as FrameSize
import app.takes.core.TransitionLayout
import app.takes.core.TransitionScene
import app.takes.core.SubtitleEditing
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ExperimentalApi
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
import app.takes.core.RenderPlan
import app.takes.core.TimelineClip
import app.takes.core.TrackRenderPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/** A clip of a render plan as a player or export item, cut to the footage it plays: never later than the frame that begins there ([RenderPlan.cutMicros]). */
fun clippedItem(picture: RenderPlan.Picture, file: File): MediaItem = MediaItem.Builder()
    .setUri(Uri.fromFile(file))
    .setMediaId(picture.id)
    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionUs(picture.sourceStartUs).setEndPositionUs(picture.sourceEndUs).build())
    .build()

/** A sound of a render plan's sound tracks as an export item, cut to the part of its source the movie plays. */
fun clippedItem(sound: RenderPlan.Entry.Sound, file: File): MediaItem = MediaItem.Builder()
    .setUri(Uri.fromFile(file))
    .setMediaId(sound.item.id)
    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionUs(sound.sourceStartUs).setEndPositionUs(sound.sourceEndUs).build())
    .build()

/**
 * A clip's speed for the export: one rate for the whole clip, picture and sound, with the pitch kept.
 *
 * The pitch has to be asked for. Given the speed alone (`setSpeed(SpeedProvider)`), Media3 changes the
 * speed of the sound by playing its samples faster or slower, as a tape would, and a clip at double speed
 * sounds an octave higher; asked to keep the pitch it stretches the sound in time instead, to the same length.
 */
fun steadySpeed(speed: Double): SpeedParameters? = speed.takeIf { it != 1.0 }?.let { rate ->
    SpeedParameters(
        object : SpeedProvider {
            override fun getSpeed(timeUs: Long) = rate.toFloat()
            override fun getNextSpeedChangeTimeUs(timeUs: Long) = C.TIME_UNSET
        },
        /* shouldMaintainPitch = */ true,
    )
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

/** An export that stopped and waits to be resumed: the project it belongs to, the seconds of picture that are saved, and why it stopped when it was not for leaving the app. */
data class PausedExport(val projectID: String, val savedSeconds: Double, val reason: String?)

/**
 * A movie of a project that can be played, shared and saved without exporting: the one made from the edit and
 * the settings as they are now ([current]), or else the newest one, made from an earlier edit or with other
 * settings. [warning] is set when the sound of a current movie goes over full scale.
 */
data class ExportedMovie(val file: File, val current: Boolean, val warning: ExportReceipt.AudioWarning?)

/**
 * Renders the current edit to a new movie in the project's `Exports` folder.
 * Sources are never modified. The picture takes the size of the first clip;
 * other clips are scaled to fit inside it.
 *
 * The movie is made in parts that are kept as they are finished, in `Exports/.paused-export`
 * ([ExportCheckpoint]): the sound of all of it, then the picture twelve seconds at a time, then both joined
 * without encoding anything again. An export does not go on while the app is not in front: it pauses, and
 * resumes from the parts that are saved, also after the app was closed in the middle of it.
 *
 * Beside each movie goes a receipt ([ExportReceipt]): the edit it was made from, the recordings it used and
 * its format. While the edit and the settings are those of a receipt, its movie is offered in place of a new
 * export. A new movie replaces the ones before it that have a receipt; movies made before there were receipts
 * are left where they are.
 */
class Exporter(private val context: Context, private val store: ProjectStore) {
    /** Something about the project stops the export before it begins; [message] is for the person. */
    private class Refusal(message: String) : Exception(message)

    private companion object {
        /** How often a section that comes out incomplete is rendered before the export gives up. */
        const val ATTEMPTS = 3
    }

    private val scope = MainScope()
    private val main = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private var pausing = false

    // The movies being copied to the gallery: a new export does not remove one of them.
    private val copying = mutableSetOf<File>()

    // An exporter is made by the screen that shows it, so the app is in front then.
    private var foreground = true

    /** Where the export that is running stands; null when none is. */
    var status by mutableStateOf<ExportProgress?>(null); private set

    /** How full the bar of the running export is, 0 to 1; null when none is running. */
    val progress: Float? get() = status?.fraction?.toFloat()

    /** The export that waits to be resumed, if there is one. */
    var paused by mutableStateOf<PausedExport?>(null); private set

    var result by mutableStateOf<File?>(null)

    /** Set with [result] when the sound of that movie goes over full scale and may distort. */
    var warning by mutableStateOf<ExportReceipt.AudioWarning?>(null); private set

    init {
        // An export does not run behind another app, as in the original: it pauses when the app is no longer
        // on screen. Turning the device recreates the screen without leaving it.
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { foreground = true }
            override fun onActivityStopped(activity: Activity) {
                if (activity.isChangingConfigurations) return
                foreground = false
                pause()
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun folder(project: Project) = store.disk.folder(project.id)

    /**
     * Looks for an export of [project] that was interrupted, when its editor opens and after every change:
     * [paused] is set when the saved parts belong to the project as it is now. Parts made from another edit
     * or with other settings are removed, and so is what an export that was closed left half written.
     */
    fun restore(project: Project) {
        if (status != null) return
        val folder = folder(project)
        val exported = ExportReceipts.matching(project, ExportReceipts.list(folder), folder) != null
        val found = ExportCheckpoint(folder).restore(project, exported)
        ExportCheckpoint.reclaim(folder)
        paused = found?.let { PausedExport(project.id, it.savedSeconds, it.reason) }
    }

    /**
     * The movie of [project] that can be offered without exporting, if there is one: the movie of the edit and
     * the settings as they are, else the newest export, else the newest movie made before exports had receipts.
     */
    suspend fun exported(project: Project): ExportedMovie? = withContext(Dispatchers.IO) {
        val folder = folder(project)
        ExportReceipts.select(project, ExportReceipts.list(folder), folder)
            ?.let { ExportedMovie(ExportReceipts.movie(folder, it.receipt), it.current, it.receipt.audioWarning.takeIf { _ -> it.current }) }
            ?: ExportReceipts.exports(folder).listFiles().orEmpty()
                .filter { it.isFile && it.extension == "mp4" && !it.name.startsWith(".") && !ExportReceipts.receiptFile(it).exists() }
                .maxByOrNull { it.lastModified() }?.let { ExportedMovie(it, false, null) }
    }

    /** Starts an export of [project], or goes on with the one that is saved for it. */
    fun start(project: Project) {
        if (status != null) return
        if (project.edit.clips.isEmpty()) { store.error = context.getString(R.string.export_no_clips); return }
        val folder = folder(project)
        val checkpoint = ExportCheckpoint(folder)
        try {
            checkpoint.prepare(project)
        } catch (problem: Exception) {
            store.error = context.getString(R.string.export_failed, said(problem))
            return
        }
        checkpoint.pauseReason = null
        paused = null
        pausing = false
        result = null
        warning = null
        val duration = project.edit.duration
        status = checkpoint.startingProgress(duration)
        job = scope.launch {
            // The movie, from the moment it is in the Exports folder: it counts even if a pause comes in just then.
            val saved = arrayOfNulls<File>(1)
            var peak: Float? = null
            var problem: Exception? = null
            try {
                run(project, folder, checkpoint) { movie, loudest -> saved[0] = movie; peak = loudest }
            } catch (_: CancellationException) {
                // Paused or cancelled: which of the two is told below.
            } catch (failed: Exception) {
                problem = failed
            }
            // What follows runs to its end also when the export was cancelled: a coroutine that has been cancelled
            // is not given the result of work it hands to another thread, unless that is asked for here.
            withContext(NonCancellable) { finish(project, folder, checkpoint, saved[0], peak, problem) }
            job = null
        }
    }

    /** Ends a run of the export: [published] is its movie, if it made one, with the [peak] of its sound, and [problem] what stopped it, if something did. */
    private suspend fun finish(project: Project, folder: File, checkpoint: ExportCheckpoint, published: File?, peak: Float?, problem: Exception?) {
        // Nothing half written is ever kept: only whole parts are in the checkpoint.
        withContext(Dispatchers.IO) { ExportCheckpoint.reclaim(folder) }
        val ending = when {
            published != null -> null
            problem == null -> if (pausing) ExportCheckpoint.Failure.PausedInBackground else ExportCheckpoint.Failure.Cancelled
            problem is Refusal -> ExportCheckpoint.Failure.Failed(problem.message.orEmpty())
            else -> ExportCheckpoint.failure(
                context.getString(R.string.export_failed, said(problem)),
                cancelled = false, outOfSpace = outOfSpace(problem), foreground = foreground, paused = pausing,
            )
        }
        val stays = ending is ExportCheckpoint.Failure.PausedInBackground || ending is ExportCheckpoint.Failure.PausedForSpace
        when (checkpoint.finish(paused = stays, published = published != null)) {
            // The saved parts have served.
            ExportCheckpoint.Ending.Published -> withContext(Dispatchers.IO) { runCatching { checkpoint.discard() } }
            ExportCheckpoint.Ending.Paused -> {
                val reason = (ending as? ExportCheckpoint.Failure.PausedForSpace)?.reason
                checkpoint.pauseReason = reason
                paused = PausedExport(project.id, checkpoint.savedSeconds(project.edit.duration), reason)
            }
            ExportCheckpoint.Ending.Abandoned -> if (ending is ExportCheckpoint.Failure.Failed) store.error = ending.message
        }
        status = null
        if (published != null) {
            warning = ExportReceipt.AudioWarning.of(peak)
            result = published
        }
    }

    /** Whether [problem] is the device being full. */
    /** The reason of a failed export in the person's language; a technical code or the system's text when the export gave none. */
    private fun said(problem: Throwable): String =
        (problem as? ExportProblem)?.let { context.getString(it.reason.label) }
            ?: problem.message ?: (problem as? ExportException)?.errorCodeName ?: context.getString(R.string.common_unknown_error)

    private fun outOfSpace(problem: Throwable): Boolean =
        generateSequence(problem) { it.cause }.take(8).any { it is ExportSpaceException || it.message?.contains("ENOSPC") == true || it.message?.contains("No space left") == true }

    /** Moves the bar. A step never gives way to an earlier one and the bar never moves back ([ExportProgress.advanced]). */
    private fun report(next: ExportProgress) {
        if (Looper.myLooper() != main.looper) { main.post { report(next) }; return }
        status = status?.advanced(next) ?: return
    }

    /**
     * The whole export of [project]; [published] is told the movie once it is saved, and the loudest sample of its
     * sound when that was measured. Cancelling the coroutine stops it, and what is in [checkpoint] by then stays.
     */
    private suspend fun run(project: Project, folder: File, checkpoint: ExportCheckpoint, published: (File, Float?) -> Unit): Unit = coroutineScope {
        // What the movie is made of, before any cleaned sound is at hand: every clip that asks for noise reduction is marked.
        val asked = TrackRenderPlan.of(project.edit)
        val settings = project.exportSettings
        val files = project.sources.associate { it.id to store.file(project, it) }
        // The first clip gives the picture its shape; the settings give it its size.
        val canvas = withContext(Dispatchers.IO) { files[asked.pictures.first().sourceID]?.let(::displaySize) }
            ?.let { settings.resolution.size(it.first, it.second) }
        if (canvas == null || asked.pictures.any { files[it.sourceID]?.isFile != true } || asked.sounds.any { files[it.sourceID]?.isFile != true }) {
            throw Refusal(context.getString(R.string.export_source_missing))
        }
        // Two clips playing at once are drawn from a frame that holds both, twice as tall as the movie
        // (DecksCompositor). A GPU that cannot hold it would fail halfway: say so before any work is done.
        if (asked.stacked) {
            val side = withContext(Dispatchers.Default) { largestTextureSide() }
            if (side != null && !asked.fits(canvas.first, canvas.second, side)) {
                throw Refusal(context.getString(R.string.export_transitions_too_large, canvas.first, canvas.second))
            }
        }
        val exports = File(folder, ExportCheckpoint.EXPORTS)
        val scratch = File(folder, ExportCheckpoint.PROCESSING)
        withContext(Dispatchers.IO) {
            ExportCheckpoint.reclaim(folder)
            if (!scratch.mkdirs() && !scratch.isDirectory) throw ExportProblem(ExportProblem.Reason.NotPrepared)
        }
        fun partial() = File(scratch, UUID.randomUUID().toString().uppercase() + ".mp4")

        // Noise reduction: the cleaned sound of every clip that asks for it, made before rendering and kept.
        report(ExportProgress.of(ExportProgress.Step.AudioEffects))
        val wanted = asked.pictures.filter { it.soundRemoved }
        val clean = mutableMapOf<Int, File>()
        wanted.forEachIndexed { step, picture ->
            Denoiser.process(files.getValue(picture.sourceID), asked.clips[picture.index].audio?.denoise ?: 0.0) {
                report(ExportProgress.of(ExportProgress.Step.AudioEffects, (step + it.toDouble()) / wanted.size))
            }?.let { clean[picture.index] = it }
        }

        report(ExportProgress.of(ExportProgress.Step.Timeline, 1.0))
        // The movie as it is rendered: a clip whose sound could not be cleaned plays its recorded sound.
        val plan = TrackRenderPlan.of(project.edit, clean.keys)
        val duration = plan.duration
        val movie = withContext(Dispatchers.IO) {
            val used = plan.pictures.map { it.sourceID }.distinct()
            ExportMovie(
                context, plan, settings.frameRate, files, canvas,
                sizes = used.associateWith { files[it]?.let(::displaySize) }, // the upright size of each recording; a crop is measured in its pixels
                rates = used.associateWith { files[it]?.let(::frameRate) },
                voiced = used.associateWith { files[it]?.let(::hasSound) == true },
                clean = clean, black = blackStill(context), luts = folder,
            )
        }
        val codec = if (settings.codec == ExportSettings.Codec.HEVC) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
        val sections = ExportSections.sections(duration)

        // Beside the picture, not before it: the sound of the whole movie in one pass, and how loud the mix gets,
        // measured before anything is encoded and kept with the saved work. On a phone the sound takes about as
        // long as the picture, and one after the other an export took twice the time of a single pass.
        val measuring = async {
            if (checkpoint.measuredPeak == null) {
                report(ExportProgress.of(ExportProgress.Step.AudioPeak))
                ExportCheck.peak(plan, files, clean) { report(ExportProgress.of(ExportProgress.Step.AudioPeak, it)) }
                    ?.let { peak -> withContext(Dispatchers.IO) { checkpoint.savePeak(peak) } }
            }
        }
        val sounding = async {
            if (!checkpoint.audioFile.isFile) {
                report(ExportProgress.of(ExportProgress.Step.Audio))
                val file = partial()
                withContext(Dispatchers.IO) { ExportSpace.require(ExportSpace.audio(duration), scratch) }
                render(movie.sound(), file, codec, movie.rate) { report(ExportProgress.of(ExportProgress.Step.Audio, it)) }
                withContext(Dispatchers.IO) {
                    if (ExportTrack.read(file, "audio/") == null) throw ExportProblem(ExportProblem.Reason.SoundNotRendered)
                    checkpoint.publish(file, checkpoint.audioFile)
                }
            }
        }

        // The picture, a section at a time. One that is saved is not rendered again.
        var tracks: List<ExportTrack> = emptyList()
        var again = false
        while (true) {
            for (section in checkpoint.remaining(duration)) {
                var saved = false
                for (attempt in 1..ATTEMPTS) {
                    val file = partial()
                    withContext(Dispatchers.IO) { ExportSpace.require(ExportSpace.section(canvas.first, canvas.second, section.length), scratch) }
                    render(movie.picture(section), file, codec, movie.rate) { report(ExportProgress.picture(section.start, it, section.length, duration)) }
                    // A file that opens proves nothing, an interrupted one opens too: a section is whole when it
                    // holds every one of its frames. An encoder may end one frame short; it is then asked again.
                    saved = withContext(Dispatchers.IO) {
                        val track = ExportTrack.read(file, "video/")
                        val whole = track != null && track.startsOnKey && ExportSections.isComplete(track.samples, section.lengthTicks, movie.rate)
                        if (whole) checkpoint.publish(file, checkpoint.section(section.index)) else file.delete()
                        whole
                    }
                    if (saved) break
                }
                if (!saved) throw ExportProblem(ExportProblem.Reason.SectionIncomplete)
            }
            // First pass of the join: every section is looked at. They must hold the frames the movie needs and
            // agree on the codec's setup data, of which the movie holds one copy.
            report(ExportProgress.of(ExportProgress.Step.AssembleMovie))
            val read = withContext(Dispatchers.IO) {
                sections.map { section ->
                    ExportTrack.read(checkpoint.section(section.index), "video/").also { report(ExportProgress.of(ExportProgress.Step.AssembleMovie, ExportProgress.assemblyScan(section.index, sections.size))) }
                }
            }
            val fits = read.withIndex().all { (index, track) ->
                track != null && track.shape == read.first()?.shape && ExportSections.canJoin(track.samples, sections[index].lengthTicks, movie.rate, index == sections.lastIndex)
            }
            if (fits) { tracks = read.filterNotNull(); break }
            // Sections that do not fit together, made by another version of the system or damaged: all of them
            // are rendered again, once.
            if (again) throw ExportProblem(ExportProblem.Reason.SectionsMismatch)
            again = true
            withContext(Dispatchers.IO) { sections.forEach { checkpoint.section(it.index).delete() } }
        }

        sounding.await()
        measuring.await()

        // The join: the sections end to end and the sound beside them, nothing encoded again.
        val joined = partial()
        withContext(Dispatchers.IO) {
            val parts = sections.map { checkpoint.section(it.index) }
            val sound = ExportTrack.read(checkpoint.audioFile, "audio/") ?: throw ExportProblem(ExportProblem.Reason.SavedSoundUnreadable)
            ExportSpace.require(ExportSpace.join(parts.sumOf { it.length() } + checkpoint.audioFile.length()), scratch)
            val active = coroutineContext
            ExportJoin.join(
                parts, tracks.map { it.samples }, movie.rate, checkpoint.audioFile, maxOf(tracks.maxOf { it.largest }, sound.largest, 1), joined,
                progress = { report(ExportProgress.of(ExportProgress.Step.AssembleMovie, it)) },
                writing = { report(ExportProgress.of(ExportProgress.Step.MovieIndex)) },
                active = { active.ensureActive() },
            )
        }

        // The recordings the movie was made from, as they are now: their checksums go on the receipt.
        val used = ExportReceipts.usedSources(project)
        val checksums = withContext(Dispatchers.IO) {
            used.mapIndexed { index, source ->
                val active = coroutineContext
                source.id to sha256(files.getValue(source.id)) { active.ensureActive(); report(ExportProgress.sourceFiles(index, it, used.size)) }
            }.toMap()
        }

        // The movie is proved before it is offered: every picture is in it, it is as long as the edit, and its
        // first and last picture decode. One that fails is reported and never published.
        report(ExportProgress.of(ExportProgress.Step.Verifying))
        withContext(Dispatchers.IO) {
            if (ExportTrack.read(joined, "video/")?.samples != tracks.sumOf { it.samples }) throw ExportProblem(ExportProblem.Reason.Incomplete)
            ExportCheck.verify(joined, duration, movie.rate) { report(ExportProgress.of(ExportProgress.Step.Verifying, 0.5)) }
        }

        report(ExportProgress.of(ExportProgress.Step.Saving))
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = project.title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(60).ifEmpty { context.getString(R.string.export_fallback_name) } + " $stamp.mp4"
        val output = File(exports, name)
        // The movie goes into the Exports folder with its receipt, and the exports before it go: only the newest
        // is kept. Their files and the saved parts of this one are moved under Processing, which is emptied when
        // the run ends. A movie that is being copied to the gallery stays.
        withContext(NonCancellable + Dispatchers.IO) {
            val previous = ExportReceipts.list(folder)
            val peak = checkpoint.measuredPeak
            try {
                ExportReceipts.publish(ExportReceipts.receipt(project, name, checksums, canvas.first, canvas.second, settings.codec, peak), joined, output)
            } catch (_: Exception) {
                throw Refusal(context.getString(R.string.export_save_failed))
            }
            published(output, peak)
            ExportReceipts.retire(previous, folder, checkpoint) { movie -> synchronized(copying) { movie in copying } }
        }
    }

    /** The SHA-256 of [file] in hexadecimal; [progress] is told how much of it has been read, 0 to 1. */
    private fun sha256(file: File, progress: (Double) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 20)
        val length = maxOf(1L, file.length())
        var done = 0L
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                done += read
                progress(done.toDouble() / length)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Runs one pass of the transformer to [file] and tells [progress] how much of it is done, 0 to 1.
     * Cancelling the coroutine cancels the pass. [framesPerSecond] is the movie's frame rate, which the
     * encoder has to be told ([ClockedEncoders]).
     */
    private suspend fun render(composition: Composition, file: File, codec: String, framesPerSecond: Int, progress: (Double) -> Unit) = coroutineScope {
        var running: Transformer? = null
        val watching = launch {
            val holder = ProgressHolder()
            while (true) {
                if (running?.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress(holder.progress / 100.0)
                delay(200)
            }
        }
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(codec)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(ClockedEncoders(DefaultEncoderFactory.Builder(context).build(), framesPerSecond))
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) = continuation.resume(Unit)

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) =
                            continuation.resumeWithException(exportException)
                    }).build()
                running = transformer
                continuation.invokeOnCancellation { transformer.cancel() }
                transformer.start(composition, file.path)
            }
        } finally {
            watching.cancel()
        }
    }

    /**
     * Stops the running export to be continued later: when the app leaves the front. What is saved stays,
     * and the part being made is given up.
     */
    fun pause() {
        if (status == null) return
        pausing = true
        job?.cancel()
    }

    /** Stops the running export for good, or gives up the one that is paused: what was saved is removed. */
    fun cancel() {
        if (status != null) {
            pausing = false
            job?.cancel()
            return
        }
        val waiting = paused ?: return
        paused = null
        scope.launch(Dispatchers.IO) { runCatching { ExportCheckpoint(store.disk.folder(waiting.projectID)).discard() } }
    }

    /** Copies a finished export to the device's Movies collection. */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveToGallery(file: File) {
        scope.launch {
            synchronized(copying) { copying += file }
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
            synchronized(copying) { copying -= file }
            store.error = context.getString(if (saved) R.string.export_gallery_saved else R.string.export_gallery_failed)
        }
    }
}

val ExportProblem.Reason.label: Int
    get() = when (this) {
        ExportProblem.Reason.WrongLength -> R.string.export_problem_wrong_length
        ExportProblem.Reason.Incomplete -> R.string.export_problem_incomplete
        ExportProblem.Reason.FirstPicture -> R.string.export_problem_first_picture
        ExportProblem.Reason.LastPicture -> R.string.export_problem_last_picture
        ExportProblem.Reason.Unreadable -> R.string.export_problem_unreadable
        ExportProblem.Reason.NotPrepared -> R.string.export_problem_not_prepared
        ExportProblem.Reason.SoundNotRendered -> R.string.export_problem_sound_not_rendered
        ExportProblem.Reason.SectionIncomplete -> R.string.export_problem_section_incomplete
        ExportProblem.Reason.SectionsMismatch -> R.string.export_problem_sections_mismatch
        ExportProblem.Reason.SavedSoundUnreadable -> R.string.export_problem_saved_sound_unreadable
        ExportProblem.Reason.SavedSectionUnreadable -> R.string.export_problem_saved_section_unreadable
        ExportProblem.Reason.SectionsNotWhole -> R.string.export_problem_sections_not_whole
        ExportProblem.Reason.PausedNotRemoved -> R.string.export_problem_paused_not_removed
    }
