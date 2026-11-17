@file:OptIn(UnstableApi::class)

package app.takes

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import androidx.media3.common.C
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.PassthroughShaderProgram
import androidx.media3.transformer.Composition
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import app.takes.core.CoverPick
import app.takes.core.EditRender
import app.takes.core.EditState
import app.takes.core.SourceMedia
import app.takes.core.ExportSections
import app.takes.core.Project
import app.takes.core.TransitionScene
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The frames of a project's movie as the export draws them, one at a time, for Select cover.
 *
 * The original takes them from the editor's composition, the one its preview plays, with its color, crop,
 * effects, transitions and titles, at the size of its canvas, in SDR (YouRec/CoverPicker.swift:17-52). Here the
 * preview is drawn by players and views, and the one drawing of a whole frame is the export's: a frame is
 * rendered as a section of one frame of the movie ([MovieRender.picture]) and read from the GPU before the
 * encoder, so it carries no compression of its own. The movie is made as the export makes it (Exporter.kt `run`
 * and `layered`), at the movie's own shape rather than the size of the export settings, as the original's canvas,
 * at 30 frames a second, the original preview's clock, and in SDR.
 */
class CoverFrames private constructor(
    private val context: Context, private val edit: EditState, private val render: EditRender, private val files: Map<String, File>, private val folder: File,
    private val sources: Map<String, SourceMedia>,
) {
    /** The movie's frame, width and height, before any export setting. */
    var canvas: Pair<Int, Int>? = null; private set

    private val movies = mutableMapOf<Pair<Int, Int>, MovieRender>()
    // One frame is drawn at a time: the grid's and the chosen one's take turns at the decoders.
    private val turn = Mutex()

    // The shape of the movie as the export finds it: the Crop tool's fixed frame, else the first picture's.
    private suspend fun prepare() = withContext(Dispatchers.IO) {
        val first = when (render) {
            is EditRender.Sequence -> render.plan.pictures.firstOrNull()?.sourceID
            is EditRender.Layers -> edit.clips.firstOrNull()?.sourceID ?: render.plan.pictures.firstOrNull()?.sourceID
            else -> null
        }
        canvas = movieShape(edit, first?.let { files[it] })
    }

    private suspend fun movie(size: Pair<Int, Int>): MovieRender = movies[size] ?: withContext(Dispatchers.IO) {
        when (render) {
            is EditRender.Sequence -> {
                val used = render.plan.pictures.map { it.sourceID }.distinct()
                ExportMovie(
                    context, render.plan, RATE, files, size,
                    sizes = used.associateWith { files[it]?.let(::displaySize) },
                    rates = used.associateWith { files[it]?.let(::frameRate) },
                    black = blackStill(context), luts = folder, media = sources,
                )
            }
            is EditRender.Layers -> {
                val plan = render.plan
                val used = plan.pictures.map { it.sourceID }.distinct()
                val side = if (plan.lanes > 1) largestTextureSide() else null
                LayerMovie(
                    context, plan, RATE, files, size, DecksCompositor.columns(plan.lanes, size.second, side),
                    sizes = used.associateWith { files[it]?.let(::displaySize) },
                    rates = used.associateWith { files[it]?.let(::frameRate) },
                    black = blackStill(context), luts = folder,
                )
            }
            else -> throw IllegalStateException("Nothing to draw")
        }
    }.also { movies[size] = it }

    /**
     * The frame showing at second [time] of the movie: as large as the canvas, or for the grid no larger than
     * [CoverPick.THUMBNAIL_SIDE] a side ([thumbnail]). Null when it could not be drawn.
     */
    suspend fun frame(time: Double, thumbnail: Boolean): Bitmap? = turn.withLock {
        val shape = canvas ?: return@withLock null
        val size = if (thumbnail) CoverPick.thumbnail(shape.first, shape.second) else shape
        try {
            val movie = movie(size)
            val frame = ExportSections.frameTicks(movie.rate)
            val index = (ExportSections.ticks(time) / frame).coerceAtLeast(0)
            val picture = withContext(Dispatchers.Default) { movie.picture(ExportSections.Section(0, index * frame, frame)) }
            val grab = FrameGrab()
            // The movie's picture as the export makes it, with the frame read at the end of its chain.
            val composition = Composition.Builder(picture.sequences).setVideoCompositorSettings(picture.videoCompositorSettings)
                .setEffects(Effects(picture.effects.audioProcessors, picture.effects.videoEffects + grab)).setHdrMode(picture.hdrMode).build()
            val scratch = File(context.cacheDir, "cover-frame.mp4")
            try {
                withContext(passes) { run(composition, scratch, movie.rate) }
            } finally {
                scratch.delete()
            }
            grab.frame
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            android.util.Log.w("CoverFrames", "A frame at $time could not be drawn", problem)
            null
        }
    }

    private suspend fun run(composition: Composition, file: File, framesPerSecond: Int) = suspendCancellableCoroutine { continuation ->
        val transformer = movieTransformer(context, MimeTypes.VIDEO_H264, framesPerSecond).setLooper(passing.looper)
            // The pass writes its one frame only once it has decoded up to it from the key frame before: on a slow device that
            // may be longer than the 25 s Media3 waits by default between two samples.
            .setMaxDelayBetweenMuxerSamplesMs(PATIENCE_MS)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) = continuation.resume(Unit)

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) =
                    continuation.resumeWithException(exportException)
            }).build()
        continuation.invokeOnCancellation { passing.post { transformer.cancel() } }
        transformer.start(composition, file.path)
    }

    companion object {
        /** How long a pass may go without writing its frame before it is given up. */
        private const val PATIENCE_MS = 120_000L

        // The passes run on a thread of their own, not the main one: there the editor's drawing, waiting for the GPU
        // the pass is using, held the pass's own messages back until its watchdog gave up.
        private val passing by lazy { Handler(HandlerThread("CoverFrames").apply { start() }.looper) }
        private val passes by lazy { passing.asCoroutineDispatcher() }

        /** The original's preview draws 30 frames a second (YouRec/EditorController.swift:520), the clock of the frames offered. */
        private val RATE = TransitionScene.frameRate(30)

        /** The frames of [project]'s movie, or null when it has none to draw. */
        suspend fun of(context: Context, project: Project, store: ProjectStore): CoverFrames? {
            val render = EditRender.of(project.edit, EditRendering.LANES)
            if (render !is EditRender.Sequence && render !is EditRender.Layers) return null
            val files = project.sources.associate { it.id to store.file(project, it) }
            return CoverFrames(context, project.edit, render, files, store.disk.folder(project.id), project.sources.associateBy { it.id })
                .also { it.prepare() }.takeIf { it.canvas != null }
        }

        /** Where the JPEG of a cover is kept to be shared: with the project, among what it can make again. */
        fun folder(store: ProjectStore, project: Project): File = File(File(store.disk.folder(project.id), "Cache"), "covers")

        /**
         * [frame] as a JPEG named [name] at the original's quality ([CoverPick.QUALITY]), in [folder]; the one made
         * before it there is removed.
         */
        suspend fun jpeg(frame: Bitmap, folder: File, name: String): File = withContext(Dispatchers.IO) {
            folder.mkdirs()
            folder.listFiles()?.forEach { it.delete() }
            File(folder, name).also { file -> file.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, CoverPick.QUALITY, it) } }
        }

        /** Copies [jpeg] to the device's Pictures collection; whether it was saved. */
        @RequiresApi(Build.VERSION_CODES.Q)
        suspend fun saveToGallery(context: Context, jpeg: File): Boolean = withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, jpeg.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Takes")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return@withContext false
            try {
                resolver.openOutputStream(uri)!!.use { out -> jpeg.inputStream().use { it.copyTo(out) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                true
            } catch (_: Exception) {
                resolver.delete(uri, null, null)
                false
            }
        }
    }
}

/**
 * Reads the frames that reach it from the GPU, as they are, and lets them through: the last one is [frame]. It
 * stands last in the chain, after everything the export draws.
 */
private class FrameGrab : GlEffect {
    @Volatile var frame: Bitmap? = null; private set

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = object : PassthroughShaderProgram() {
        override fun queueInputFrame(glObjectsProvider: GlObjectsProvider, inputTexture: GlTextureInfo, presentationTimeUs: Long) {
            frame = read(inputTexture)
            super.queueInputFrame(glObjectsProvider, inputTexture, presentationTimeUs)
        }
    }

    private fun read(texture: GlTextureInfo): Bitmap? {
        val bound = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, it, 0) }[0]
        val made = if (texture.fboId == C.INDEX_UNSET) IntArray(1).also { GLES20.glGenFramebuffers(1, it, 0) }[0] else null
        try {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, made ?: texture.fboId)
            if (made != null) GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture.texId, 0)
            val pixels = ByteBuffer.allocateDirect(texture.width * texture.height * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, texture.width, texture.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            if (GLES20.glGetError() != GLES20.GL_NO_ERROR) return null
            pixels.rewind()
            val upsideDown = createBitmap(texture.width, texture.height).apply { copyPixelsFromBuffer(pixels) }
            // GL's rows go up from the bottom.
            return Bitmap.createBitmap(upsideDown, 0, 0, texture.width, texture.height, Matrix().apply { preScale(1f, -1f) }, false)
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bound)
            made?.let { GLES20.glDeleteFramebuffers(1, intArrayOf(it), 0) }
        }
    }
}
