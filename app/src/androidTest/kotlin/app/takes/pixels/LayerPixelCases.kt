@file:OptIn(UnstableApi::class)

package app.takes.pixels

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.opengl.GLES20
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlShaderProgram
import app.takes.LayerDrawing
import app.takes.LayerPreviewShader
import app.takes.LayersEffect
import app.takes.SceneDrawing
import app.takes.core.EditState
import app.takes.core.EditTime
import app.takes.core.LayerPlan
import app.takes.core.PlacedPicture
import app.takes.core.PlacementEdgeCrop
import app.takes.core.PlacementLayout
import app.takes.core.Rect
import app.takes.core.Size as FrameSize
import app.takes.core.TimelineClip
import app.takes.core.TimelineEditing
import app.takes.core.TrackTimeline
import app.takes.core.TransitionFrame
import app.takes.core.TransitionSettings
import app.takes.core.TransitionStyle
import app.takes.core.VideoPlacement
import app.takes.core.VideoPlacementRegion
import app.takes.core.VideoTimelineItem
import app.takes.core.compile
import app.takes.core.toTimeline
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

/**
 * An export of picture layers in miniature: the real [LayersEffect] of a
 * [plan], given the frame [app.takes.DecksCompositor] would make of its lanes
 * at a moment (in a column, or in a grid of [columns]) and asked for the
 * frame of the movie. Each lane shows the picture its sequence plays then,
 * black where it plays none. [picture] gives the picture of each picture of
 * the plan, by index, or null for a black one.
 */
class LayerStage(
    private val bench: Bench, val plan: LayerPlan, val width: Int, val height: Int, private val columns: Int = 1,
    framesPerSecond: Double = 30.0, titled: Boolean = false,
    /** Whether the lanes are an HDR movie's, HLG in BT.2020: the effect compiled for HDR, as [app.takes.LayerMovie] makes it. */
    hdr: Boolean = false,
    /** The project's folder, which holds the pictures laid over clips. */
    folder: java.io.File? = null,
    private val picture: (index: Int) -> Picture?,
) {
    private val lanes = plan.lanes
    private val rows = (lanes + columns - 1) / columns
    private val program: GlShaderProgram = LayersEffect(plan, Size(width, height), lanes, columns, { framesPerSecond }, { Rect(0.0, 0.0, width.toDouble(), height.toDouble()) }, titled, hdr = hdr, folder = folder)
        .toGlShaderProgram(bench.context, /* useHdr = */ false)
    private var output: GlTextureInfo? = null
    private var failure: VideoFrameProcessingException? = null
    private val texture = IntArray(1)
    // One cell of the input at a time, and the frame read back: the same size.
    private val bytes: ByteBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())

    init {
        program.setOutputListener(object : GlShaderProgram.OutputListener {
            override fun onOutputFrameAvailable(outputTexture: GlTextureInfo, presentationTimeUs: Long) { output = outputTexture }
        })
        program.setErrorListener(Runnable::run) { failure = it }
        GLES20.glGenTextures(1, texture, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
        for (filter in listOf(GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_TEXTURE_MAG_FILTER)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, filter, GLES20.GL_LINEAR)
        for (wrap in listOf(GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_TEXTURE_WRAP_T)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, wrap, GLES20.GL_CLAMP_TO_EDGE)
    }

    /** The frame of the movie at [time]. */
    fun frame(time: Double): Picture = frameAt((time * 1e6).roundToLong() - 1)

    private fun frameAt(timeUs: Long): Picture {
        val time = (timeUs + 1) / 1e6
        val shown = List(lanes) { lane -> plan.onLane(lane, time)?.let { picture(it.index) } }
        val across = width * columns
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, across, height * rows, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        // A cell at a time, so a frame of 4K lanes fits the test's memory. OpenGL counts rows from the bottom: the
        // last row of cells is at the bottom, and a cell's last row first.
        for (cell in 0 until rows * columns) {
            val source = shown.getOrNull(cell)
            bytes.clear()
            for (row in 0 until height) {
                val y = height - 1 - row
                for (x in 0 until width) {
                    val color = source?.at(x, y) ?: 0
                    bytes.put((color shr 16).toByte()).put((color shr 8).toByte()).put(color.toByte()).put(0xff.toByte())
                }
            }
            bytes.flip()
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, cell % columns * width, (rows - 1 - cell / columns) * height, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
        }
        GlUtil.checkGlError()
        output = null
        program.queueInputFrame(bench.objects, GlTextureInfo(texture[0], C.INDEX_UNSET, C.INDEX_UNSET, across, height * rows), timeUs)
        failure?.let { throw it }
        val drawn = checkNotNull(output) { "The effect gave no frame" }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, drawn.fboId)
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        bytes.clear()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
        GlUtil.checkGlError()
        program.releaseOutputFrame(drawn)
        val pixels = IntArray(width * height)
        for (row in 0 until height) {
            val y = height - 1 - row
            for (x in 0 until width) {
                val at = (row * width + x) * 4
                pixels[y * width + x] = rgb(bytes.get(at).toInt() and 0xff, bytes.get(at + 1).toInt() and 0xff, bytes.get(at + 2).toInt() and 0xff)
            }
        }
        return Picture(width, height, pixels)
    }

    /**
     * How long the effect takes to make the frame at [time], in milliseconds: the median of [times] draws of the
     * same input, each waited for to the end. The pictures are uploaded once, before.
     */
    fun cost(time: Double, times: Int = 9): Double {
        frame(time)
        val timeUs = (time * 1e6).roundToLong() - 1
        val took = List(times) {
            output = null
            GLES20.glFinish()
            val began = System.nanoTime()
            program.queueInputFrame(bench.objects, GlTextureInfo(texture[0], C.INDEX_UNSET, C.INDEX_UNSET, width * columns, height * rows), timeUs)
            GLES20.glFinish()
            val milliseconds = (System.nanoTime() - began) / 1e6
            failure?.let { throw it }
            program.releaseOutputFrame(checkNotNull(output) { "The effect gave no frame" })
            milliseconds
        }.sorted()
        return took[times / 2]
    }

    fun release() {
        program.release()
        GLES20.glDeleteTextures(1, texture, 0)
    }
}

/**
 * The pixel tests of the layer shader: the real [LayersEffect] on real OpenGL
 * ES, three lanes at most, against what [LayerPlan.moment] says a frame holds:
 * black, then back to front a pair in transition mixed in linear light and
 * each other picture moved into its window ([PlacedPicture]) and clipped to
 * it. Pixels are compared within [LEVELS] steps of the eight-bit encoded
 * value; those within a pixel of a window's edge are left out.
 */
class LayerPixelCases(private val bench: Bench) {
    private val quadsA = Picture.quadrants(WIDTH, HEIGHT, rgb(200, 60, 30), rgb(40, 160, 90), rgb(90, 70, 200), rgb(220, 200, 60))
    private val quadsB = Picture.quadrants(WIDTH, HEIGHT, rgb(30, 120, 220), rgb(180, 40, 150), rgb(70, 70, 70), rgb(235, 235, 235))
    private val quadsO = Picture.quadrants(WIDTH, HEIGHT, rgb(250, 250, 250), rgb(10, 10, 10), rgb(240, 20, 20), rgb(20, 220, 240))
    private val canvas = FrameSize(WIDTH.toDouble(), HEIGHT.toDouble())

    private fun clip(id: String, seconds: Double) = TimelineClip(id = id, sourceID = "S", start = 0.0, end = seconds)

    /** [timeline] with the picture [id] from [start] for [seconds], above the others; in [window] wherever it plays with others. */
    private fun overlaid(timeline: TrackTimeline, id: String, start: Double, seconds: Double, window: VideoPlacement?): TrackTimeline {
        val over = timeline.copy(video = timeline.video + VideoTimelineItem.of("occurrence-$id", EditTime.of(start), clip(id, seconds), 10))
        if (window == null) return over
        val together = over.compile().map { it.video.toSet() }.filter { id in it && it.size > 1 }.distinct()
        return over.copy(placements = together.mapIndexed { number, ids -> VideoPlacementRegion(id = "R$number", participants = ids, videos = ids.associateWith { if (it == id) window else VideoPlacement() }) })
    }

    /** A dissolve of a second from A into B, each [SECONDS] long, with O over them in the corner from second 1.5 for 6 seconds. */
    private val dissolved: TrackTimeline by lazy {
        val clips = TimelineEditing.setJoin(EditState(listOf(clip("A", SECONDS), clip("B", SECONDS))), 1, TransitionSettings(id = "T", style = TransitionStyle.Dissolve, duration = 1.0))!!.clips
        EditState(clips).toTimeline()
    }
    private val corner = VideoPlacement.layout(PlacementLayout.PictureInPicture, 1, 2).copy(order = 10)

    private fun picture(plan: LayerPlan): (Int) -> Picture = { index ->
        when (plan.pictures[index].id) { "A" -> quadsA; "B" -> quadsB; else -> quadsO }
    }

    private val stages = mutableMapOf<Pair<Any, Int>, LayerStage>()

    private fun stage(plan: LayerPlan, columns: Int = 1) = stages.getOrPut(plan to columns) { LayerStage(bench, plan, WIDTH, HEIGHT, columns, picture = picture(plan)) }

    fun release() {
        stages.values.forEach { it.release() }
        stages.clear()
    }

    /**
     * The color [moment] gives pixel ([x], [y]), encoded, 0 to 1, from the
     * pictures themselves; null for a pixel within a pixel of a window's edge.
     */
    private fun expected(moment: LayerPlan.Moment, plan: LayerPlan, x: Int, y: Int, out: DoubleArray): Boolean {
        val qx = x + 0.5
        val qy = y + 0.5
        val a = DoubleArray(3)
        val b = DoubleArray(3)
        out.fill(0.0)
        for (layer in moment.layers) when (layer) {
            is LayerPlan.Layer.Mixed -> {
                val share = when (val frame = layer.frame) {
                    is TransitionFrame.Dissolve -> frame.second
                    TransitionFrame.First -> 0.0
                    TransitionFrame.Second -> 1.0
                    else -> error("Only a dissolve here")
                }
                fun side(source: app.takes.core.TransitionScene.Source, into: DoubleArray) = when (source) {
                    is app.takes.core.TransitionScene.Source.Clip -> Side.Image(picture(plan)(source.index)).linear(qx, qy, into)
                    is app.takes.core.TransitionScene.Source.Held -> Side.Image(picture(plan)(source.index)).linear(qx, qy, into)
                    is app.takes.core.TransitionScene.Source.Fill -> into.fill(0.0)
                }
                side(layer.first, a)
                side(layer.second, b)
                for (c in 0..2) out[c] = Light.encoded(a[c] * (1 - share) + b[c] * share)
            }
            is LayerPlan.Layer.Whole -> {
                val index = (layer.source as? app.takes.core.TransitionScene.Source.Clip)?.index ?: (layer.source as app.takes.core.TransitionScene.Source.Held).index
                val placed = layer.window
                if (placed == null) { picture(plan)(index).encoded(qx, qy, out); continue }
                // The picture's own pixel under this canvas pixel, back through the window.
                val px = (qx - placed.window.midX) / placed.scale + placed.source.midX
                val py = (qy - placed.window.midY) / placed.scale + placed.source.midY
                val clip = placed.clip
                val inside = qx >= clip.minX && qx < clip.maxX && qy >= clip.minY && qy < clip.maxY &&
                    px >= placed.source.minX && px < placed.source.maxX && py >= placed.source.minY && py < placed.source.maxY
                val near = qx >= clip.minX - 1 && qx < clip.maxX + 1 && qy >= clip.minY - 1 && qy < clip.maxY + 1 &&
                    !(qx >= clip.minX + 1 && qx < clip.maxX - 1 && qy >= clip.minY + 1 && qy < clip.maxY - 1)
                if (near) return false
                if (inside) picture(plan)(index).encoded(px, py, out)
            }
        }
        return true
    }

    private fun Checks.matches(stage: LayerStage, time: Double, what: String) {
        val plan = stage.plan
        val moment = plan.moment(time, canvas)
        val actual = stage.frame(time)
        val want = DoubleArray(3)
        var checked = 0
        var over = 0
        var worst = 0.0
        var where = ""
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
            if (!expected(moment, plan, x, y, want)) continue
            checked++
            var off = 0.0
            for (c in 0..2) off = maxOf(off, kotlin.math.abs(actual.channel(x, y, c) - want[c] * 255))
            if (off > LEVELS + 0.5) over++
            if (off > worst) { worst = off; where = "($x, $y)" }
        }
        note("$what at %.3f s: $checked pixels, worst %.2f at $where".format(time, worst))
        check(checked > WIDTH * HEIGHT / 2) { "Too few pixels compared: $checked" }
        check(over == 0) { "$over of $checked pixels are further than $LEVELS from what the moment holds; worst %.2f at $where".format(worst) }
    }

    private fun layers(name: String, body: Checks.() -> Unit) = Case("layers: $name", body)

    fun all(): List<Case> = listOf(
        layers("an overlay in a window over a dissolve, three lanes in a column") {
            val plan = LayerPlan.planned(overlaid(dissolved, "O", 1.5, 6.0, corner), 3)!!
            check(plan.lanes == 3) { "Three lanes, not ${plan.lanes}" }
            val relation = plan.transitions.first()
            val during = relation.origin.seconds + relation.timing.videoDuration * 0.4
            val moment = plan.moment(during, canvas)
            check(moment.layers.map { it::class.simpleName } == listOf("Mixed", "Whole")) { "The pair and the overlay, not ${moment.layers}" }
            check((moment.layers[1] as LayerPlan.Layer.Whole).window != null) { "The overlay has its window" }
            matches(stage(plan), during, "dissolve under the corner")
        },
        layers("the same frames from lanes in a grid two wide") {
            val plan = LayerPlan.planned(overlaid(dissolved, "O", 1.5, 6.0, corner), 3)!!
            val relation = plan.transitions.first()
            for (share in listOf(0.2, 0.7)) {
                val during = relation.origin.seconds + relation.timing.videoDuration * share
                val column = stage(plan).frame(during)
                val grid = stage(plan, columns = 2).frame(during)
                // The same pixels of the lanes, read at other texture coordinates: as near as a mix is to its recipe.
                val apart = column.pixels.indices.maxOf { at -> (0..2).maxOf { c -> kotlin.math.abs(((column.pixels[at] shr (16 - 8 * c)) and 0xff) - ((grid.pixels[at] shr (16 - 8 * c)) and 0xff)) } }
                note("grid against column at %.3f s: %d steps at most".format(during, apart))
                check(apart <= LEVELS) { "The grid draws another frame than the column at $during: $apart steps apart" }
                matches(stage(plan, columns = 2), during, "grid")
            }
        },
        layers("before the dissolve the overlay sits in its window over the base") {
            val plan = LayerPlan.planned(overlaid(dissolved, "O", 1.5, 6.0, corner), 3)!!
            matches(stage(plan), 2.0, "base and corner")
            matches(stage(plan), plan.duration - 2.5, "second base and corner")
        },
        layers("an overlay with no window covers its base, and where nothing plays the frame is black") {
            val apart = dissolved.copy(transitions = emptyList(), generated = emptyList(), video = dissolved.video.mapIndexed { index, item -> if (index == 1) item.copy(start = item.start + EditTime.of(2.0)) else item })
            val plan = LayerPlan.planned(overlaid(apart, "O", 1.0, 2.0, null), 3)!!
            matches(stage(plan), 2.0, "covered")
            val gap = (plan.pictures.first { it.id == "A" }.end + plan.pictures.first { it.id == "B" }.start) / 2
            check(plan.moment(gap, canvas).layers.isEmpty()) { "Nothing plays in the gap" }
            val black = stage(plan).frame(gap)
            check(black.pixels.all { it == 0 }) { "The gap is not black" }
            matches(stage(plan), plan.duration - 1.0, "after the gap")
        },
        // YouRec Tests/TrackMediaTests.swift:401-435: "Cropping leaves exact interior pixels and reveals unaltered lower
        // video on all four sides". The preview draws with the same LayerDrawing and shader body as this export effect.
        layers("an edge crop clips the picture in front and reveals the one under it on all four sides") {
            val cropped = VideoPlacement(order = 10, edgeCrop = PlacementEdgeCrop(left = 0.2, right = 0.15, top = 0.2, bottom = 0.1))
            val plan = LayerPlan.planned(overlaid(EditState(listOf(clip("A", SECONDS))).toTimeline(), "O", 1.0, 3.0, cropped), 3)!!
            val window = (plan.moment(2.0, canvas).layers.last() as LayerPlan.Layer.Whole).window
            check(window != null && window.scale == 1.0) { "The picture in front keeps its size: $window" }
            matches(stage(plan), 2.0, "cropped")
            val frame = stage(plan).frame(2.0)
            val want = DoubleArray(3)
            // Inside the margins the picture in front, untouched; outside them on every side the one under it.
            for ((x, y, front) in listOf(Triple(100, 60, true), Triple(20, 60, false), Triple(300, 60, false), Triple(100, 10, false), Triple(100, 175, false))) {
                (if (front) quadsO else quadsA).encoded(x + 0.5, y + 0.5, want)
                val off = (0..2).maxOf { c -> kotlin.math.abs(frame.channel(x, y, c) - want[c] * 255) }
                check(off <= LEVELS + 0.5) { "($x, $y) should show ${if (front) "the cropped picture" else "the picture under it"}: $off steps off" }
            }
        },
    ) + TransitionPixelCases.SIZES.flatMap { (width, height) ->
        listOf(
            layers("a whip pan under an overlay: no copies of a line at ${width}x$height") { blurUnderOverlay(TransitionSettings(id = "T", style = TransitionStyle.Whip, duration = 1.0), width, height) },
            layers("a defocus under an overlay: no copies of a square at ${width}x$height, amount 1") {
                blurUnderOverlay(TransitionSettings(id = "T", style = TransitionStyle.Defocus, duration = 1.0, blurAmount = 1.0), width, height)
            },
            layers("a defocus under an overlay: no copies of a square at ${width}x$height, default amount") {
                blurUnderOverlay(TransitionSettings(id = "T", style = TransitionStyle.Defocus, duration = 1.0), width, height)
            },
        )
    } + layers("whip pan and defocus under an overlay: the time a frame takes at 3840x2160") { blurCost(3840, 2160) } +
        layers("a defocus in the preview: the lanes of the pair spread, then the disc, the picture in front sharp") { previewDefocus() } +
        layers("a whip pan in the preview on a view of 1920x1080: the lanes of the pair spread along it, one even streak") { previewWhip(1920, 1080) }

    /**
     * The preview's layer shader on a view of [width] × [height], one full screen: it takes fewer samples of the
     * smear, more than a pixel apart, and spreads the lanes of the pair along its axis first, so a line through a whip
     * pan at its peak is one even streak, as in the export ([evenStreak]).
     */
    private fun Checks.previewWhip(width: Int, height: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return note("no preview shader below Android 13")
        val settings = TransitionSettings(id = "T", style = TransitionStyle.Whip, duration = 1.0)
        val canvas = FrameSize(width.toDouble(), height.toDouble())
        val moment = app.takes.core.TransitionScene.Moment(
            app.takes.core.TransitionScene.Source.Clip(0, 0, 0.0), app.takes.core.TransitionScene.Source.Clip(1, 1, 0.0),
            app.takes.core.TransitionPicture.frame(settings, 0.5, canvas), 0.5, 0.5,
        )
        val scene = SceneDrawing.of(moment, width.toFloat(), height.toFloat(), { (it as app.takes.core.TransitionScene.Source.Clip).deck.toFloat() }) { Rect(0.0, 0.0, canvas.width, canvas.height) }
        val drawing = LayerDrawing(scene, true, 0, FloatArray(LayerDrawing.MOST * LayerDrawing.STRIDE))
        val lanes = Bitmap.createBitmap(width, 2 * height, Bitmap.Config.ARGB_8888)
        Canvas(lanes).apply {
            drawColor(Color.BLACK)
            drawRect((width / 2 - 1).toFloat(), 0f, (width / 2 + 2).toFloat(), height.toFloat(), Paint().apply { color = Color.WHITE })
        }
        val reader = ImageReader.newInstance(width, 2 * height, PixelFormat.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val node = RenderNode("lanes").apply {
            setPosition(0, 0, width, 2 * height)
            beginRecording().drawBitmap(lanes, 0f, 0f, null)
            endRecording()
            setRenderEffect(LayerPreviewShader().effect(drawing, width.toFloat(), height.toFloat()))
        }
        val renderer = HardwareRenderer().apply { setSurface(reader.surface); setContentRoot(node) }
        try {
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = reader.acquireNextImage()
            val drawn = Bitmap.wrapHardwareBuffer(checkNotNull(image.hardwareBuffer), null)!!.copy(Bitmap.Config.ARGB_8888, false)
            image.close()
            val row = IntArray(width) { drawn.getPixel(it, height / 2) }
            evenStreak(Picture(width, height) { x, y -> if (y == height / 2) row[x] else 0 }, settings)
        } finally {
            renderer.destroy()
            reader.close()
            node.discardDisplayList()
        }
    }

    /**
     * The preview's layer shader, compiled and run by the platform as the editor runs it, on a layer of three lanes:
     * a bright square on the first lane through a defocus into the second becomes one even disc, as in the export,
     * and the third lane, in front in the top left corner, keeps its hard edge: only the pair's lanes are spread.
     */
    private fun Checks.previewDefocus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return note("no preview shader below Android 13")
        val (width, height) = 480 to 270
        val radius = 30f
        val half = 4
        val edge = width / 8
        val lanes = Bitmap.createBitmap(width, 3 * height, Bitmap.Config.ARGB_8888)
        Canvas(lanes).apply {
            drawColor(Color.BLACK)
            val white = Paint().apply { color = Color.WHITE }
            drawRect((width / 2 - half).toFloat(), (height / 2 - half).toFloat(), (width / 2 + half + 1).toFloat(), (height / 2 + half + 1).toFloat(), white)
            // The third lane: white left of the edge, black right of it.
            drawRect(0f, 2f * height, edge.toFloat(), 3f * height, white)
        }
        val values = FloatArray(24).also { it[0] = radius; it[1] = 0f; it[2] = 0.12f; it[3] = 0.1f }
        val scene = SceneDrawing(SceneDrawing.DEFOCUS, 0f, 1f, FloatArray(3), values)
        // The third lane as it is, shown in the top left quarter.
        val front = FloatArray(LayerDrawing.MOST * LayerDrawing.STRIDE).also {
            floatArrayOf(0f, 0f, width / 4f, height / 4f, width / 2f, height / 2f, width / 2f, height / 2f, 2f, 1f).copyInto(it)
        }
        val drawing = LayerDrawing(scene, true, 1, front)
        val reader = ImageReader.newInstance(width, 3 * height, PixelFormat.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val node = RenderNode("lanes").apply {
            setPosition(0, 0, width, 3 * height)
            beginRecording().drawBitmap(lanes, 0f, 0f, null)
            endRecording()
            setRenderEffect(LayerPreviewShader().effect(drawing, width.toFloat(), height.toFloat()))
        }
        val renderer = HardwareRenderer().apply { setSurface(reader.surface); setContentRoot(node) }
        try {
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = reader.acquireNextImage()
            val drawn = Bitmap.wrapHardwareBuffer(checkNotNull(image.hardwareBuffer), null)!!.copy(Bitmap.Config.ARGB_8888, false)
            image.close()
            fun encoded(x: Int, y: Int) = (drawn.getPixel(x, y) shr 16) and 0xff
            val inner = mutableListOf<Int>()
            for (y in 0 until height) for (x in 0 until width) if (kotlin.math.hypot(x + 0.5 - width / 2.0, y + 0.5 - height / 2.0) <= radius - half * 1.5 - 3) inner += encoded(x, y)
            val mean = inner.map { Light.linear(it / 255.0) }.average()
            val even = (2 * half + 1.0) * (2 * half + 1.0) / (Math.PI * radius * radius)
            val beside = listOf(encoded(edge - 2, height / 8), encoded(edge - 1, height / 8), encoded(edge, height / 8), encoded(edge + 1, height / 8))
            note("inside the disc encoded %d to %d, %.5f in linear light (an even disc: %.5f); the picture in front across its edge: %s".format(inner.min(), inner.max(), mean, even, beside))
            check(inner.max() - inner.min() <= 3) { "The disc ranges from ${inner.min()} to ${inner.max()} inside: the samples show" }
            check(kotlin.math.abs(mean - even) <= even * 0.2) { "The disc is $mean on average, an even disc $even" }
            check(beside == listOf(255, 255, 0, 0)) { "The picture in front is not sharp across its edge: $beside" }
        } finally {
            renderer.destroy()
            reader.close()
            node.discardDisplayList()
        }
    }

    /** A transition of [settings] from A into B, each [SECONDS] long, with O over them in the corner from second 1.5 for 6 seconds. */
    private fun underCorner(settings: TransitionSettings): LayerPlan {
        val clips = TimelineEditing.setJoin(EditState(listOf(clip("A", SECONDS), clip("B", SECONDS))), 1, settings)!!.clips
        return LayerPlan.planned(overlaid(EditState(clips).toTimeline(), "O", 1.5, 6.0, corner), 3)!!
    }

    /** The second of [plan]'s transition at which its picture has changed by [progress]. */
    private fun at(plan: LayerPlan, progress: Double): Double = plan.transitions.first().let { it.origin.seconds + it.timing.motion * progress }

    /**
     * The mark of a whip pan or a defocus ([BlurMarks]) in A through the transition into a black B, at its peak,
     * under O in the corner. The original draws the pair of an edit of tracks with the same transition as a
     * sequence's (`YouRec/VideoToolCompositor.swift:295`, `YouRec/TransitionRenderer.swift:223-251`): blurred on a
     * reduced picture, one even streak or disc, as the export of a sequence draws it. O stays sharp over it.
     */
    private fun Checks.blurUnderOverlay(settings: TransitionSettings, width: Int, height: Int) {
        val plan = underCorner(settings)
        val peak = at(plan, 0.5)
        val moment = plan.moment(peak, FrameSize(width.toDouble(), height.toDouble()))
        check(moment.layers.map { it::class.simpleName } == listOf("Mixed", "Whole")) { "The pair and the overlay, not ${moment.layers}" }
        val placed = (moment.layers.last() as LayerPlan.Layer.Whole).window ?: return check(false) { "The overlay has no window" }
        val whip = settings.style == TransitionStyle.Whip
        val mark = if (whip) BlurMarks.line(width, height) else BlurMarks.square(width, height, settings)
        val over = Picture.quadrants(width, height, rgb(250, 250, 250), rgb(10, 10, 10), rgb(240, 20, 20), rgb(20, 220, 240))
        val stage = LayerStage(bench, plan, width, height) { index -> when (plan.pictures[index].id) { "A" -> mark; "B" -> null; else -> over } }
        try {
            val actual = stage.frame(peak)
            if (whip) evenStreak(actual, settings) else evenDisc(actual, settings)
            // The overlay in its window as it is: the middle of each of its four fields.
            val window = placed.window
            val want = DoubleArray(3)
            for ((fx, fy) in listOf(0.25 to 0.25, 0.75 to 0.25, 0.25 to 0.75, 0.75 to 0.75)) {
                val x = (window.minX + window.width * fx).toInt()
                val y = (window.minY + window.height * fy).toInt()
                over.encoded((x + 0.5 - window.midX) / placed.scale + placed.source.midX, (y + 0.5 - window.midY) / placed.scale + placed.source.midY, want)
                val off = (0..2).maxOf { c -> kotlin.math.abs(actual.channel(x, y, c) - want[c] * 255) }
                check(off <= LEVELS + 0.5) { "($x, $y) in the overlay's window is $off steps from the overlay" }
            }
        } finally {
            stage.release()
        }
    }

    /** The time the export's effect takes to draw a frame of [width] × [height] of each blur under an overlay, and of a dissolve. */
    private fun Checks.blurCost(width: Int, height: Int) {
        val picture = Picture.flat(width, height, rgb(120, 90, 60))
        for (settings in listOf(
            TransitionSettings(id = "T", style = TransitionStyle.Dissolve, duration = 1.0),
            TransitionSettings(id = "T", style = TransitionStyle.Whip, duration = 1.0),
            TransitionSettings(id = "T", style = TransitionStyle.Defocus, duration = 1.0, blurAmount = 1.0),
            TransitionSettings(id = "T", style = TransitionStyle.Defocus, duration = 1.0),
        )) {
            val plan = underCorner(settings)
            val stage = LayerStage(bench, plan, width, height) { picture }
            try {
                for (progress in listOf(0.25, 0.5)) {
                    note("%s%s at p=%.2f: %.1f ms".format(settings.style.name, settings.blurAmount?.let { " (amount $it)" } ?: "", progress, stage.cost(at(plan, progress))))
                }
            } finally {
                stage.release()
            }
        }
    }

    companion object {
        const val WIDTH = 320
        const val HEIGHT = 180
        const val SECONDS = 4.5

        /** A picture moved into a window is sampled between its pixels, as a dissolve is mixed: within two steps. */
        const val LEVELS = 2.0
    }
}
