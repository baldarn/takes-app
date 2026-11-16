@file:OptIn(UnstableApi::class)

package app.takes.pixels

import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlShaderProgram
import app.takes.LayersEffect
import app.takes.core.EditState
import app.takes.core.EditTime
import app.takes.core.LayerPlan
import app.takes.core.PlacedPicture
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
 * the plan, by index.
 */
class LayerStage(
    private val bench: Bench, val plan: LayerPlan, val width: Int, val height: Int, private val columns: Int = 1,
    framesPerSecond: Double = 30.0, private val picture: (index: Int) -> Picture,
) {
    private val lanes = plan.lanes
    private val rows = (lanes + columns - 1) / columns
    private val program: GlShaderProgram = LayersEffect(plan, Size(width, height), lanes, columns, { framesPerSecond }) { Rect(0.0, 0.0, width.toDouble(), height.toDouble()) }
        .toGlShaderProgram(bench.context, /* useHdr = */ false)
    private var output: GlTextureInfo? = null
    private var failure: VideoFrameProcessingException? = null
    private val texture = IntArray(1)
    private val bytes: ByteBuffer = ByteBuffer.allocateDirect(width * columns * height * rows * 4).order(ByteOrder.nativeOrder())
    private val read: ByteBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())

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
        bytes.clear()
        // OpenGL counts rows from the bottom: the last row of cells comes first.
        for (row in 0 until height * rows) {
            val fromTop = height * rows - 1 - row
            val y = fromTop % height
            for (x in 0 until across) {
                val source = shown.getOrNull(fromTop / height * columns + x / width)
                val color = source?.at(x % width, y) ?: 0
                bytes.put((color shr 16).toByte()).put((color shr 8).toByte()).put(color.toByte()).put(0xff.toByte())
            }
        }
        bytes.flip()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, across, height * rows, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
        GlUtil.checkGlError()
        output = null
        program.queueInputFrame(bench.objects, GlTextureInfo(texture[0], C.INDEX_UNSET, C.INDEX_UNSET, across, height * rows), timeUs)
        failure?.let { throw it }
        val drawn = checkNotNull(output) { "The effect gave no frame" }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, drawn.fboId)
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        read.clear()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, read)
        GlUtil.checkGlError()
        program.releaseOutputFrame(drawn)
        val pixels = IntArray(width * height)
        for (row in 0 until height) {
            val y = height - 1 - row
            for (x in 0 until width) {
                val at = (row * width + x) * 4
                pixels[y * width + x] = rgb(read.get(at).toInt() and 0xff, read.get(at + 1).toInt() and 0xff, read.get(at + 2).toInt() and 0xff)
            }
        }
        return Picture(width, height, pixels)
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
    )

    companion object {
        const val WIDTH = 320
        const val HEIGHT = 180
        const val SECONDS = 4.5

        /** A picture moved into a window is sampled between its pixels, as a dissolve is mixed: within two steps. */
        const val LEVELS = 2.0
    }
}
