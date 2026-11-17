@file:OptIn(UnstableApi::class)

package app.takes

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import app.takes.core.EffectColorMath
import app.takes.core.EffectImage
import app.takes.core.EffectPalette
import app.takes.core.EffectPaletteAnalysis
import app.takes.core.EffectRGB
import app.takes.core.EffectValue
import app.takes.core.EffectVector
import app.takes.core.LiveVideoEffects
import app.takes.core.PersonMaskChunks
import app.takes.core.Size
import app.takes.core.TimelineClip
import app.takes.core.VideoEffectCadenceClock
import app.takes.core.VideoEffectID
import app.takes.core.VideoEffectLiveBudget
import app.takes.core.VideoEffectQuality
import app.takes.core.VideoEffectSettings
import app.takes.core.VideoEffectTime
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The live video effects drawn over the recorder's preview, as the original draws them on each camera frame
 * (YouRec/VideoEffects/LiveVideoEffects.swift:409-689): the export's own passes ([VideoEffectChain]) run on the
 * upright picture of the frame, with the person in it found by the segmenter, a Quantization's palette analysed from
 * the scene every sixth frame, a cadence's frame held, and the picture made smaller, then lighter, when the frames
 * take too long or the phone is warm ([VideoEffectLiveBudget]). The recording is never drawn on.
 *
 * Used on the thread of [CameraFrames], with its OpenGL context current.
 */
internal class LiveEffectRendering(private val context: Context) {
    /** Where the live effects keep their picked pictures between takes, as the original's LiveEffects folder. */
    val folder: File get() = folder(context)

    /** Called on the frames' thread when the note on the preview's quality changes: the note and whether the phone is warm. */
    var onQuality: ((LiveVideoEffects.QualityNote?, Boolean) -> Unit)? = null

    /** Called on the frames' thread with a Quantization's palette taken from one frame, for "Save palette from this frame". */
    var onPalette: ((String, EffectPalette?) -> Unit)? = null

    private val chain = VideoEffectChain(clip(emptyList()), begins = 0.0, frameRate = 30.0, assets = Assets(), sourceFPS = 30.0).apply { specialised = true }
    private var program: BaseGlShaderProgram? = null
    private var budget = VideoEffectLiveBudget(GPU_SECONDS)
    private var chainIDs = emptySet<String>()
    private var cadence = VideoEffectCadenceClock(emptyList())
    private var cadenceOf: List<VideoEffectSettings>? = null
    private var heldIndex = -1
    private var frames = 0
    private var note: Pair<LiveVideoEffects.QualityNote?, Boolean>? = null

    // The upright picture and the effects drawn on it, rows from the bottom as the chain's textures.
    private val upright = Target()
    private val result = Target()
    private val small = Target()

    /** The size of the picture drawn, upright: the frame's, made smaller by the budget. */
    var width = 0; private set
    var height = 0; private set

    /** The effects drawn into [result] at the last frame drawn, for the colour picked there. */
    val resultTexture: Int get() = result.texture

    /**
     * Draws [effects] at [time] on the camera's frame of [sourceWidth] × [sourceHeight] standing upright, which
     * [drawCamera] draws into the framebuffer bound, of the size it is given. [thermal] is Android's thermal status;
     * a frame [tool] draws the picture even with no effect on it.
     * Returns false when nothing is to be shown, the frame to be drawn as it is.
     */
    fun draw(effects: List<VideoEffectSettings>, time: Double, sourceWidth: Int, sourceHeight: Int, thermal: Int, tool: Boolean, drawCamera: (Int, Int) -> Unit): Boolean {
        val began = SystemClock.elapsedRealtimeNanos()
        val enabled = effects.filter { it.enabled }
        if (enabled.isEmpty() && !tool) return false
        // Another chain of effects is assessed afresh. LiveVideoEffects.swift:181-183.
        val ids = enabled.map { it.id }.toSet()
        if (ids != chainIDs) {
            chainIDs = ids
            budget = VideoEffectLiveBudget(GPU_SECONDS)
            heldIndex = -1
        }
        val serious = LiveVideoEffects.serious(thermal)
        budget.thermal(serious, LiveVideoEffects.critical(thermal))
        val sourceLong = max(sourceWidth, sourceHeight).toDouble()
        val limit = budget.maximumLongSide ?: Double.POSITIVE_INFINITY
        val scale = if (sourceLong > limit) limit / sourceLong else 1.0
        val w = max(2, (sourceWidth * scale).roundToInt())
        val h = max(2, (sourceHeight * scale).roundToInt())
        if (w != width || h != height) {
            width = w
            height = h
            upright.size(w, h)
            result.size(w, h)
            heldIndex = -1
        }
        val lite = budget.quality == VideoEffectQuality.Lite
        chain.lite = lite
        // A cadence holds its frame: the picture drawn at its first is shown again. LiveVideoEffects.swift:622-631.
        if (cadenceOf != effects) {
            cadenceOf = effects
            cadence = VideoEffectCadenceClock(effects)
            heldIndex = -1
        }
        val hold = cadence.hold(time)
        frames++
        val drawn = hold == null || hold.index != heldIndex
        if (drawn) {
            heldIndex = hold?.index ?: -1
            upright.bind()
            drawCamera(w, h)
            capturing(time, sourceWidth, sourceHeight)
            val timing = timer.begin()
            analyse(effects, time)
            chain.clip = clip(effects)
            val shader = program ?: (chain.toGlShaderProgram(context, false) as BaseGlShaderProgram).also { program = it }
            shader.configure(w, h)
            result.bind()
            shader.drawFrame(upright.texture, (time * 1e6).toLong())
            // Where the phone cannot time its GPU, the drawing is waited for.
            if (!timing) GLES20.glFinish()
            timer.end(sourceLong, max(w, h).toDouble(), (SystemClock.elapsedRealtimeNanos() - began) / 1e9)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            timer.results { gpu, source, rendered -> measured(gpu, source, rendered) }
        } else {
            held++
        }
        val next = LiveVideoEffects.note(serious, budget, lite) to serious
        if (next != note) {
            note = next
            onQuality?.invoke(next.first, next.second)
        }
        return true
    }

    /** The whole frame took [wall] seconds, after the picture was shown. */
    fun shown(wall: Double, sourceLong: Double) {
        walls += wall
        if (walls.size > 300) walls.removeAt(0)
        lastWall = wall
        lastSourceLong = sourceLong
    }

    private val timer = GpuTimer()

    /**
     * The time the GPU takes to draw a frame's looks, as the original reads it from Metal (MetalEffectRenderer.gpuSeconds):
     * with the timer queries of `GL_EXT_disjoint_timer_query`, read a few frames later so that nothing waits for them.
     * Without them, the CPU's time with the drawing waited for.
     */
    private class GpuTimer {
        private class Query(val id: Int, var source: Double = 0.0, var rendered: Double = 0.0, var cpu: Double = 0.0, var pending: Boolean = false)
        private val queries = ArrayDeque<Query>()
        private var free = ArrayDeque<Query>()
        private var supported: Boolean? = null
        private var current: Query? = null

        /** Starts timing a frame; false where the phone cannot. */
        fun begin(): Boolean {
            if (supported == null) {
                supported = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_EXTENSIONS)?.contains("GL_EXT_disjoint_timer_query") == true &&
                    EffectMatteWindow.supported()
                if (supported == true) {
                    val ids = IntArray(6)
                    android.opengl.GLES30.glGenQueries(ids.size, ids, 0)
                    free = ArrayDeque(ids.map { Query(it) })
                }
            }
            if (supported != true) return false
            val query = free.removeFirstOrNull() ?: return false
            android.opengl.GLES30.glBeginQuery(TIME_ELAPSED, query.id)
            current = query
            return true
        }

        private var cpuOnly: Triple<Double, Double, Double>? = null

        fun end(source: Double, rendered: Double, cpu: Double) {
            val query = current
            if (query == null) {
                cpuOnly = Triple(cpu, source, rendered)
                return
            }
            android.opengl.GLES30.glEndQuery(TIME_ELAPSED)
            query.source = source
            query.rendered = rendered
            query.cpu = cpu
            queries.addLast(query)
            current = null
        }

        /** Gives the frames timed whose times are in: GPU seconds, and the long sides of the source and of the picture drawn. */
        fun results(take: (Double, Double, Double) -> Unit) {
            cpuOnly?.let { (cpu, source, rendered) -> cpuOnly = null; take(cpu, source, rendered) }
            val value = IntArray(1)
            while (queries.isNotEmpty()) {
                val query = queries.first()
                android.opengl.GLES30.glGetQueryObjectuiv(query.id, android.opengl.GLES30.GL_QUERY_RESULT_AVAILABLE, value, 0)
                if (value[0] == 0) break
                queries.removeFirst()
                android.opengl.GLES30.glGetQueryObjectuiv(query.id, android.opengl.GLES30.GL_QUERY_RESULT, value, 0)
                val disjoint = IntArray(1)
                android.opengl.GLES20.glGetIntegerv(GPU_DISJOINT, disjoint, 0)
                // A frame whose clock was disturbed is not counted.
                if (disjoint[0] == 0) take((value[0].toLong() and 0xffffffffL) / 1e9, query.source, query.rendered)
                free.addLast(query)
            }
        }

        private companion object {
            const val TIME_ELAPSED = 0x88BF
            const val GPU_DISJOINT = 0x8FBB
        }
    }

    private val gpus = mutableListOf<Double>()
    private val walls = mutableListOf<Double>()
    private var lastWall = 0.0
    private var lastSourceLong = 0.0
    private var reportedAt = 0L
    private var rendered = 0
    private var held = 0

    private fun measured(gpu: Double, sourceLong: Double, renderLong: Double) {
        rendered++
        gpus += gpu
        if (gpus.size > 300) gpus.removeAt(0)
        // The wall time of a frame is known after it is shown: the one before stands for it, as the budget sees the frames go by.
        budget.observe(gpu, max(gpu, lastWall), sourceLong, renderLong)
        val now = SystemClock.elapsedRealtime()
        if (now - reportedAt > 5000 && gpus.size >= 30) {
            reportedAt = now
            fun p(values: List<Double>, q: Double) = values.sorted().let { if (it.isEmpty()) 0.0 else it[minOf(it.size - 1, (it.size * q).toInt())] * 1000 }
            Log.i(
                TAG, "render ${width}x$height from ${sourceLong.toInt()} quality ${budget.quality} limit ${budget.maximumLongSide} " +
                    "draw p50 %.1f p95 %.1f ms, frame p50 %.1f p95 %.1f ms, drawn %d held %d".format(p(gpus, .5), p(gpus, .95), p(walls, .5), p(walls, .95), rendered, held),
            )
        }
    }

    // MARK: What the passes read: the person, the palette, a picked picture.

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "live-effects-analysis").apply { priority = Thread.NORM_PRIORITY } }
    private var segmenter: PersonMasks.Live? = null
    private val smoothing = PersonMaskChunks.Smoothing()
    @Volatile private var person: PersonFrame? = null
    @Volatile private var smoothed: PersonFrame? = null
    @Volatile private var segmenting = false
    @Volatile private var analysing = false
    private val palettes = HashMap<String, EffectPalette>()
    private var lastPaletteTime = 0.0
    @Volatile private var paletteRequest: String? = null

    /** Takes a palette from the next frame for the Quantization [id]: Save palette from this frame. LiveVideoEffects.swift:244-249, 578-589. */
    fun samplePalette(id: String) { paletteRequest = id }

    /** What a clean plate's capture tells: its progress, the plate kept under a name in [folder], or why it ended. */
    sealed interface PlateEvent {
        data class Progress(val fraction: Double) : PlateEvent
        data class Ready(val name: String) : PlateEvent
        data class Failed(val failure: app.takes.core.CleanPlateAverage.Failure?) : PlateEvent
    }

    // The clean plate being captured, on the frames' thread, and when its progress was told.
    private var plate: app.takes.core.CleanPlateAverage? = null
    private var plateListener: ((PlateEvent) -> Unit)? = null
    private var plateToldAt = Double.NEGATIVE_INFINITY

    /**
     * Captures a clean plate from the frames drawn from now: a second of the empty screen averaged, at most
     * [PLATE_LONG_SIDE] pixels on its longer side, kept as a PNG in [folder]; [listener] is told on this thread.
     * LiveVideoEffects.swift:264-272, 472-494.
     */
    fun capturePlate(listener: (PlateEvent) -> Unit) {
        plate = app.takes.core.CleanPlateAverage()
        plateListener = listener
        plateToldAt = Double.NEGATIVE_INFINITY
    }

    /** Ends a capture under way without its plate. */
    fun cancelPlate() {
        plate = null
        plateListener = null
    }

    /**
     * Adds the upright picture drawn at [time] to the plate being captured, at a size of its own from the camera's
     * [sourceWidth] × [sourceHeight], which the budget's smaller pictures do not change.
     */
    private fun capturing(time: Double, sourceWidth: Int, sourceHeight: Int) {
        val average = plate ?: return
        val listener = plateListener ?: return
        val scale = minOf(1.0, PLATE_LONG_SIDE.toDouble() / max(sourceWidth, sourceHeight))
        val w = max(2, (sourceWidth * scale).roundToInt())
        val h = max(2, (sourceHeight * scale).roundToInt())
        val pixels = readSmall(w, h)
        val rgba = ByteArray(w * h * 4)
        // Rows from the bottom, read back; kept from the top.
        for (row in 0 until h) {
            pixels.position((h - 1 - row) * w * 4)
            pixels.get(rgba, row * w * 4, w * 4)
        }
        val finished = try {
            average.append(rgba, w, h, time)
        } catch (failed: app.takes.core.CleanPlateAverage.Failed) {
            cancelPlate()
            listener(PlateEvent.Failed(failed.failure))
            return
        }
        if (time - plateToldAt >= 0.1 || finished) {
            plateToldAt = time
            listener(PlateEvent.Progress(average.progress))
        }
        if (!finished) return
        cancelPlate()
        val bytes = average.encoded() ?: return listener(PlateEvent.Failed(null))
        val (pw, ph) = average.width to average.height
        val target = File(folder, "clean-plate-${java.util.UUID.randomUUID()}.png")
        worker.execute {
            val event = try {
                val colours = IntArray(pw * ph) { i ->
                    (0xff shl 24) or ((bytes[i * 4].toInt() and 0xff) shl 16) or ((bytes[i * 4 + 1].toInt() and 0xff) shl 8) or (bytes[i * 4 + 2].toInt() and 0xff)
                }
                val picture = Bitmap.createBitmap(colours, pw, ph, Bitmap.Config.ARGB_8888)
                val png = java.io.ByteArrayOutputStream().also { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                app.takes.core.ProjectDisk.writeAtomically(png, target)
                PlateEvent.Ready(target.name)
            } catch (problem: Exception) {
                Log.w("LiveEffects", "clean plate", problem)
                PlateEvent.Failed(null)
            }
            listener(event)
        }
    }

    /** Starts the looks afresh: the person, the palettes and the held frame. LiveVideoEffects.swift:168-200. */
    fun reset() {
        person = null
        smoothed = null
        synchronized(palettes) { palettes.clear() }
        heldIndex = -1
    }

    private fun needsPerson(effects: List<VideoEffectSettings>): Boolean = effects.any { effect ->
        effect.enabled && when (effect.kind) {
            VideoEffectID.AutoBackground -> true
            VideoEffectID.ColorIsolation -> effect.choice("mode") == "The subject"
            VideoEffectID.ChromaKey -> effect.toggle("personHint")
            VideoEffectID.Quantization -> effect.toggle("faceDetail") && effect.number("colors") <= 4
            else -> false
        }
    }

    /** Copies a small picture from [upright] for the segmenter and the palette, and looks at it off this thread. */
    private fun analyse(effects: List<VideoEffectSettings>, time: Double) {
        if (needsPerson(effects) && !segmenting && PersonMasks.available()) {
            segmenting = true
            val side = PersonMasks.SIDE
            val pixels = readSmall(side, side)
            val size = Size(width.toDouble(), height.toDouble())
            val geometry = "${width}x$height"
            worker.execute {
                try {
                    val rgb = FloatArray(side * side * 3)
                    val luma = FloatArray(side * side)
                    // Read rows from the bottom; the model and the stored masks go from the top.
                    for (y in 0 until side) for (x in 0 until side) {
                        val p = ((side - 1 - y) * side + x) * 4
                        val r = (pixels.get(p).toInt() and 255) / 255f
                        val g = (pixels.get(p + 1).toInt() and 255) / 255f
                        val b = (pixels.get(p + 2).toInt() and 255) / 255f
                        val i = y * side + x
                        rgb[i * 3] = r
                        rgb[i * 3 + 1] = g
                        rgb[i * 3 + 2] = b
                        luma[i] = (.2126 * EffectColorMath.linear(r.toDouble()) + .7152 * EffectColorMath.linear(g.toDouble()) + .0722 * EffectColorMath.linear(b.toDouble())).toFloat()
                    }
                    val model = segmenter ?: PersonMasks.Live(context).also { segmenter = it }
                    val rows = model.mask(rgb)
                    val soft = smoothing.push(rows, side, side, luma, time, geometry)
                    person = PersonFrame(PersonMaskChunks.Mask(time, side, side, rows), 0, size)
                    smoothed = PersonFrame(PersonMaskChunks.Mask(time, side, side, soft), 0, size)
                } catch (problem: Exception) {
                    Log.w(TAG, "No person mask", problem)
                } finally {
                    segmenting = false
                }
            }
        }
        val quantizations = effects.filter { it.enabled && it.kind == VideoEffectID.Quantization }
        val request = paletteRequest
        val sample = request != null && effects.any { it.id == request && it.kind == VideoEffectID.Quantization }
        if (request != null && !sample) {
            paletteRequest = null
            onPalette?.invoke(request, null)
        }
        // Every sixth frame, from the palette before; at once when there is none yet. LiveVideoEffects.swift:590-620.
        val due = quantizations.isNotEmpty() && (synchronized(palettes) { quantizations.any { it.id !in palettes } } || EffectPaletteAnalysis.liveAnalyses(frames))
        if ((due || sample) && !analysing) {
            analysing = true
            val (w, h) = if (width >= height) 256 to max(1, 256 * height / width) else max(1, 256 * width / height) to 256
            val pixels = readSmall(w, h)
            val elapsed = max(0.0, time - lastPaletteTime)
            lastPaletteTime = time
            val targets = quantizations
            val picked = if (sample) effects.first { it.id == request } else null
            if (sample) paletteRequest = null
            worker.execute {
                try {
                    val image = EffectImage.of(w, h) { x, y ->
                        val p = ((h - 1 - y) * w + x) * 4
                        EffectVector(
                            EffectColorMath.linear((pixels.get(p).toInt() and 255) / 255.0),
                            EffectColorMath.linear((pixels.get(p + 1).toInt() and 255) / 255.0),
                            EffectColorMath.linear((pixels.get(p + 2).toInt() and 255) / 255.0),
                        )
                    }
                    val samples = EffectPaletteAnalysis.samples(image)
                    val seed = VideoEffectTime.frameIndex(time, 30.0).toULong()
                    if (picked != null) {
                        onPalette?.invoke(picked.id, EffectPaletteAnalysis.analyze(samples, picked.number("colors").toInt(), seed = seed))
                    }
                    for (effect in targets) {
                        val count = effect.number("colors").toInt()
                        val previous = synchronized(palettes) { palettes[effect.id] }?.takeIf { it.colors.size == count }
                        val palette = EffectPaletteAnalysis.analyze(samples, count, previous, if (previous == null) 0.2 else elapsed, seed)
                        synchronized(palettes) { palettes[effect.id] = palette }
                    }
                } catch (problem: Exception) {
                    Log.w(TAG, "No palette", problem)
                    if (picked != null) onPalette?.invoke(picked.id, null)
                } finally {
                    analysing = false
                }
            }
        }
    }

    /** The upright picture at [w] × [h], read back: rows from the bottom, four bytes a pixel. */
    private fun readSmall(w: Int, h: Int): ByteBuffer {
        small.size(w, h)
        small.bind()
        blit.draw(upright.texture)
        val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        upright.bind()
        return pixels
    }

    /** The colour of the drawn picture about ([x], [y]), fractions from the top left: the median of its 9 × 9 patch. */
    fun colorAt(x: Double, y: Double): EffectRGB? {
        if (width == 0 || result.texture == 0) return null
        val (left, top, right, bottom) = LiveVideoEffects.patch(x, y, width, height).let { listOf(it[0], it[1], it[2], it[3]) }
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return null
        result.bind()
        val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        // Rows from the bottom: the patch's top row is [height - top - 1].
        GLES20.glReadPixels(left, height - bottom, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        val bytes = ByteArray(w * h * 4)
        pixels.get(bytes)
        return LiveVideoEffects.medianColor(bytes, w, h)
    }

    private inner class Assets : EffectAssets {
        override fun person(sourceTime: Double, smoothed: Boolean): PersonFrame? = if (smoothed) this@LiveEffectRendering.smoothed else person
        override fun analysed(effect: VideoEffectSettings, sourceTime: Double): EffectPalette? = synchronized(palettes) { palettes[effect.id] }
        override fun picture(effect: VideoEffectSettings): Bitmap? {
            val name = (effect.value("media", 0.0) as? EffectValue.Media)?.file ?: return null
            return EffectPictures.get(File(folder, name))
        }

        override fun movie(effect: VideoEffectSettings): File? = EffectPictures.movie(folder, effect)

        // The camera's frames do not wait for a movie's: they show the last frame read.
        override val waitsForMovies: Boolean get() = false

        // The plate of the camera's picture as it is drawn, standing upright. LiveVideoEffects.swift:472-498.
        override fun plate(effect: VideoEffectSettings): CleanPlate? {
            val name = (effect.value("cleanPlate", 0.0) as? EffectValue.Media)?.file ?: return null
            if (width == 0 || height == 0) return null
            // By the picture's shape, which the budget's smaller pictures keep, so that the plate is not read again for them.
            val shape = app.takes.core.Size((width * 100.0 / height).roundToInt() * 10.0, 1000.0)
            return CleanPlates.plate(File(folder, name), shape, null, null, wait = false)
        }
    }

    private val blit = Blit()

    fun release() {
        program?.release()
        program = null
        upright.release()
        result.release()
        small.release()
        blit.release()
        worker.execute { segmenter?.close(); segmenter = null }
    }

    /** A picture drawn into: its texture and framebuffer. */
    private class Target {
        var texture = 0; private set
        private var framebuffer = 0
        private var w = 0
        private var h = 0

        fun size(width: Int, height: Int) {
            if (width == w && height == h && texture != 0) return
            release()
            w = width
            h = height
            texture = GlUtil.createTexture(width, height, /* useHighPrecisionColorComponents = */ false)
            framebuffer = GlUtil.createFboForTexture(texture)
        }

        fun bind() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
            GLES20.glViewport(0, 0, w, h)
        }

        fun release() {
            if (framebuffer != 0) GlUtil.deleteFbo(framebuffer)
            if (texture != 0) GlUtil.deleteTexture(texture)
            framebuffer = 0
            texture = 0
        }
    }

    /** A texture drawn whole into the framebuffer bound. */
    private class Blit {
        private var program = 0

        fun draw(texture: Int) {
            if (program == 0) program = CameraFrames.link(VERTEX, FRAGMENT)
            GLES20.glUseProgram(program)
            val position = GLES20.glGetAttribLocation(program, "aPosition")
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, CameraFrames.QUAD)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun release() {
            if (program != 0) GLES20.glDeleteProgram(program)
            program = 0
        }

        private companion object {
            const val VERTEX = """
                attribute vec4 aPosition;
                varying vec2 vCoordinate;
                void main() {
                    gl_Position = aPosition;
                    vCoordinate = aPosition.xy * 0.5 + 0.5;
                }
            """
            const val FRAGMENT = """
                precision mediump float;
                uniform sampler2D uTexture;
                varying vec2 vCoordinate;
                void main() { gl_FragColor = texture2D(uTexture, vCoordinate); }
            """
        }
    }

    companion object {
        private const val TAG = "LiveEffects"

        /**
         * The GPU time a frame of looks may take, at the clock the governor runs the GPU at: most of a frame at 30 a
         * second, the rest left to the camera and the display, as the budget allows the whole frame (VideoEffectLiveBudget).
         */
        private const val GPU_SECONDS = 0.026

        /** The longer side of a clean plate captured: it is blurred by 8 pixels at 1080 where it is used. */
        private const val PLATE_LONG_SIDE = 1280

        fun folder(context: Context): File = File(context.filesDir, "LiveEffects").apply { mkdirs() }

        /** The effects as the clip the chain draws: from the start, as long as can be. */
        fun clip(effects: List<VideoEffectSettings>): TimelineClip =
            TimelineClip(sourceID = "live", start = 0.0, end = VideoEffectSettings.MAXIMUM_TIME, videoEffects = VideoEffectSettings.ordered(effects).ifEmpty { null })
    }
}
