@file:OptIn(UnstableApi::class)

package app.takes.pixels

import android.graphics.Bitmap
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderEffect
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.SingleColorLut
import app.takes.ColorLuts
import app.takes.FramingEffect
import app.takes.core.ColorGrade
import app.takes.core.ColorLook
import app.takes.core.ColorSettings
import app.takes.core.CropSettings
import app.takes.core.LUTInput
import app.takes.core.LogColor
import app.takes.core.MirrorSettings
import app.takes.core.OutputShape
import app.takes.core.PictureFraming
import app.takes.core.Size as FrameSize
import app.takes.core.TimelineClip
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/** One Media3 effect of the export on one frame: [input] in, what it draws out, at the size it draws. */
class GlEffectStage(private val bench: Bench, effect: GlEffect) {
    private val program: GlShaderProgram = effect.toGlShaderProgram(bench.context, /* useHdr = */ false)
    private var output: GlTextureInfo? = null
    private var failure: VideoFrameProcessingException? = null
    private val texture = IntArray(1)

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

    fun frame(input: Picture, timeUs: Long = 1_000_000): Picture {
        val bytes = ByteBuffer.allocateDirect(input.width * input.height * 4).order(ByteOrder.nativeOrder())
        // OpenGL counts rows from the bottom.
        for (row in 0 until input.height) for (x in 0 until input.width) {
            val color = input.at(x, input.height - 1 - row)
            bytes.put((color shr 16).toByte()).put((color shr 8).toByte()).put(color.toByte()).put(0xff.toByte())
        }
        bytes.flip()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, input.width, input.height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
        GlUtil.checkGlError()
        output = null
        program.queueInputFrame(bench.objects, GlTextureInfo(texture[0], C.INDEX_UNSET, C.INDEX_UNSET, input.width, input.height), timeUs)
        failure?.let { throw it }
        val drawn = checkNotNull(output) { "The effect gave no frame" }
        val width = drawn.width
        val height = drawn.height
        val read = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, drawn.fboId)
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, read)
        GlUtil.checkGlError()
        program.releaseOutputFrame(drawn)
        val pixels = IntArray(width * height)
        for (row in 0 until height) for (x in 0 until width) {
            val at = (row * width + x) * 4
            pixels[(height - 1 - row) * width + x] = rgb(read.get(at).toInt() and 0xff, read.get(at + 1).toInt() and 0xff, read.get(at + 2).toInt() and 0xff)
        }
        return Picture(width, height, pixels)
    }

    fun release() {
        program.release()
        GLES20.glDeleteTextures(1, texture, 0)
    }
}

/**
 * The platform's renderer on one picture, as the editor's preview draws a deck:
 * [input] fitted whole into a view of [width] by [height], with [effect] on it
 * and the view scaled, turned and moved by [setUp]. Android 13 and later.
 */
object ViewStage {
    fun frame(input: Picture, width: Int, height: Int, effect: RenderEffect? = null, setUp: RenderNode.() -> Unit = {}): Picture {
        val bitmap = Bitmap.createBitmap(IntArray(input.pixels.size) { input.pixels[it] or (0xff shl 24) }, input.width, input.height, Bitmap.Config.ARGB_8888)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_CPU_READ_OFTEN)
        val fitted = minOf(width.toFloat() / input.width, height.toFloat() / input.height)
        val node = RenderNode("deck").apply {
            setPosition(0, 0, width, height)
            beginRecording().apply {
                translate((width - input.width * fitted) / 2, (height - input.height * fitted) / 2)
                scale(fitted, fitted)
                drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            }
            endRecording()
            effect?.let { setRenderEffect(it) }
            setUp()
        }
        val root = RenderNode("root").apply {
            setPosition(0, 0, width, height)
            beginRecording().apply { drawColor(android.graphics.Color.BLACK); drawRenderNode(node) }
            endRecording()
        }
        val renderer = HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            renderer.setContentRoot(root)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = reader.acquireNextImage()
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixels = IntArray(width * height)
                for (y in 0 until height) for (x in 0 until width) {
                    val at = y * plane.rowStride + x * plane.pixelStride
                    pixels[y * width + x] = rgb(buffer.get(at).toInt() and 0xff, buffer.get(at + 1).toInt() and 0xff, buffer.get(at + 2).toInt() and 0xff)
                }
                return Picture(width, height, pixels)
            } finally {
                image.close()
            }
        } finally {
            renderer.destroy()
            reader.close()
            bitmap.recycle()
        }
    }
}

/**
 * The pixel tests of the picture tools: the colour table with Highlights and
 * with Apple Log footage as the export's LUT effect and the preview's shader
 * apply it, against the core's grade ([ColorGrade]); a flipped clip and a
 * movie of another shape as the export's [FramingEffect] draws them; and the
 * preview's layer of a flipped, turned and cropped clip against the export.
 */
class PictureToolPixelCases(private val bench: Bench) {
    private val folder: File = bench.context.cacheDir

    /** Grey, the primaries and a spread of colours, with many bright tones for Highlights to act on. */
    private val ramps = Picture(256, 96) { px, y ->
        val x = px
        when (y * 6 / 96) {
            0 -> rgb(x, x, x)
            1 -> rgb(x, (x * 0.8).toInt(), (x * 0.6).toInt())
            2 -> rgb((x * 0.5).toInt(), x, (x * 0.9).toInt())
            3 -> rgb(255 - x / 3, 200, x)
            4 -> { val n = (px + 256 * y) * 2654435761L; rgb((n shr 8).toInt() and 0xff, (n shr 16).toInt() and 0xff, (n shr 24).toInt() and 0xff) }
            else -> rgb(180 + x / 4, 160 + x / 4, 140 + x / 4)
        }
    }

    /** A smooth picture of a landscape take, different in every direction, so a flip or a turn is told at once. */
    private val take = Picture(192, 108) { x, y -> rgb(x * 255 / 191, y * 255 / 107, ((x + 2 * y) * 255 / 407)) }

    private fun level(value: Double) = (value.coerceIn(0.0, 1.0) * 255).roundToInt()

    /** The largest and the mean difference, in levels, between [picture] and [expected] of each pixel. */
    private fun Checks.close(what: String, picture: Picture, bound: Int, mean: Double, expected: (x: Int, y: Int) -> IntArray) {
        var worst = 0
        var sum = 0.0
        var count = 0
        for (y in 0 until picture.height) for (x in 0 until picture.width) {
            val want = expected(x, y)
            for (c in 0..2) {
                val d = abs(picture.channel(x, y, c) - want[c])
                worst = maxOf(worst, d)
                sum += d
                count++
            }
        }
        val average = sum / count
        note("$what: largest difference $worst levels, mean ${"%.2f".format(average)}")
        check(worst <= bound && average <= mean) { "$what: largest difference $worst levels (at most $bound), mean $average (at most $mean)" }
    }

    private fun graded(settings: ColorSettings, x: Int, y: Int, log: LUTInput? = null): IntArray {
        val r = ramps.channel(x, y, 0) / 255.0
        val g = ramps.channel(x, y, 1) / 255.0
        val b = ramps.channel(x, y, 2) / 255.0
        val color = if (log == null) ColorGrade.apply(settings, null, r, g, b) else ColorGrade.applyLog(settings, null, null, log, r, g, b)
        return IntArray(3) { level(color[it]) }
    }

    private val highlightSets = listOf(-1.0, -0.5, 0.5, 1.0).map { ColorSettings(highlights = it) } +
        ColorSettings(highlights = -0.6, exposure = 0.7, warmth = 0.3, look = ColorLook.Cinema, amount = 0.8)

    private fun exportGrade(settings: ColorSettings, log: LUTInput? = null): Picture {
        val stage = GlEffectStage(bench, SingleColorLut.createFromCube(ColorLuts.cube(settings, folder, log)))
        return try { stage.frame(ramps) } finally { stage.release() }
    }

    private fun case(name: String, body: Checks.() -> Unit) = Case("picture tools: $name", body)

    fun all(): List<Case> = buildList {
        addAll(GradeMotionPixelCases(bench).all())
        addAll(LocalRecoveryPixelCases(bench).all())
        for (settings in highlightSets) {
            add(case("export colour LUT with highlights ${settings.highlights}, exposure ${settings.exposure}") {
                val drawn = exportGrade(settings)
                // A 33-point table read between its points: a few levels from the grade worked out at each colour.
                close("export", drawn, bound = 6, mean = 1.0) { x, y -> graded(settings, x, y) }
                // Highlights really acts: the brightest grey moves the way the value says.
                val before = ramps.channel(255, 0, 0)
                val after = drawn.channel(255, 0, 0)
                if (settings.exposure == null) check(if (settings.highlights!! < 0) after < before - 10 else after >= before) { "white went from $before to $after" }
            })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(case("preview colour shader with highlights ${settings.highlights}, exposure ${settings.exposure}") {
                val shown = ViewStage.frame(ramps, ramps.width, ramps.height, ColorLuts.previewEffect(settings, folder))
                close("preview", shown, bound = 6, mean = 1.2) { x, y -> graded(settings, x, y) }
                val exported = exportGrade(settings)
                close("preview against export", shown, bound = 4, mean = 1.0) { x, y -> IntArray(3) { c -> exported.channel(x, y, c) } }
            })
        }
        for (profile in listOf(LUTInput.AppleLog, LUTInput.AppleLog2)) {
            for (settings in listOf(ColorSettings(), ColorSettings(highlights = -0.8, look = ColorLook.Warm))) {
                add(case("${profile.title} footage decoded to display, highlights ${settings.highlights}") {
                    val drawn = exportGrade(settings, profile)
                    // Log code values change fast near black and the gamut fit of the viewing transform has kinks: a 33-point
                    // table read between its points is far off on a few colours (up to about 100 levels on the emulator) and
                    // within a level on average. The preview reads the same table, and is held to it closely below.
                    close("export", drawn, bound = 110, mean = 1.5) { x, y -> graded(settings, x, y, profile) }
                    // Middle grey recorded in log shows as middle grey.
                    val code = LogColor.encodeValue(0.18)
                    val grey = Picture.flat(8, 8, rgb(level(code), level(code), level(code)))
                    if (settings.highlights == null) {
                        val stage = GlEffectStage(bench, SingleColorLut.createFromCube(ColorLuts.cube(settings, folder, profile)))
                        val out = try { stage.frame(grey) } finally { stage.release() }
                        val want = level(LogColor.toDisplay(0.18))
                        check(abs(out.channel(4, 4, 1) - want) <= 3) { "grey shows as ${out.channel(4, 4, 1)}, not $want" }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val shown = ViewStage.frame(ramps, ramps.width, ramps.height, ColorLuts.previewEffect(settings, folder, profile))
                        close("preview against export", shown, bound = 4, mean = 1.0) { x, y -> IntArray(3) { c -> drawn.channel(x, y, c) } }
                    }
                })
            }
        }
        val source = FrameSize(take.width.toDouble(), take.height.toDouble())
        for (mirror in listOf(MirrorSettings(horizontal = true), MirrorSettings(vertical = true), MirrorSettings(horizontal = true, vertical = true))) {
            add(case("export flips an uncropped clip, horizontal ${mirror.horizontal}, vertical ${mirror.vertical}") {
                val clip = TimelineClip(id = "C", sourceID = "S", start = 0.0, end = 10.0, mirror = mirror)
                val stage = GlEffectStage(bench, FramingEffect(clip, 0.0, source, source))
                val drawn = try { stage.frame(take) } finally { stage.release() }
                check(drawn.width == take.width && drawn.height == take.height) { "the frame is ${drawn.width} × ${drawn.height}" }
                close("flipped", drawn, bound = 2, mean = 0.5) { x, y ->
                    val from = take.at(if (mirror.horizontal) take.width - 1 - x else x, if (mirror.vertical) take.height - 1 - y else y)
                    IntArray(3) { (from shr (16 - 8 * it)) and 0xff }
                }
            })
        }
        for (shape in listOf(OutputShape.Portrait, OutputShape.Square, OutputShape.Feed)) {
            add(case("export frames a landscape take as ${shape.title}") {
                val canvas = OutputShape.canvas(source, shape.aspect)
                val crop = CropSettings()
                val clip = TimelineClip(id = "C", sourceID = "S", start = 0.0, end = 10.0, crop = crop)
                val stage = GlEffectStage(bench, FramingEffect(clip, 0.0, source, canvas))
                val drawn = try { stage.frame(take) } finally { stage.release() }
                check(drawn.width == canvas.width.toInt() && drawn.height == canvas.height.toInt()) { "the frame is ${drawn.width} × ${drawn.height}, not $canvas" }
                // The middle of the take, the frame's height and as much of its width as the shape takes.
                val window = crop.rect(source, canvas)
                val out = DoubleArray(3)
                close("framed", drawn, bound = 3, mean = 0.8) { x, y ->
                    take.encoded(window.x + (x + 0.5) * window.width / canvas.width, window.y + (y + 0.5) * window.height / canvas.height, out)
                    IntArray(3) { level(out[it]) }
                }
            })
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            for (mirror in listOf(null, MirrorSettings(horizontal = true), MirrorSettings(horizontal = true, vertical = true))) {
                add(case("preview layer matches the export, turned and cropped to 9:16, horizontal ${mirror?.horizontal}, vertical ${mirror?.vertical}") {
                    val canvas = OutputShape.canvas(source, OutputShape.Portrait.aspect)
                    val crop = CropSettings(x = 0.4, y = 0.55, zoom = 1.4, rotation = 12.0)
                    val clip = TimelineClip(id = "C", sourceID = "S", start = 0.0, end = 10.0, crop = crop, mirror = mirror)
                    val stage = GlEffectStage(bench, FramingEffect(clip, 0.0, source, canvas))
                    val exported = try { stage.frame(take) } finally { stage.release() }
                    val layer = PictureFraming.frame(clip, 0.0, source, canvas).layer
                    val shown = ViewStage.frame(take, canvas.width.toInt(), canvas.height.toInt()) {
                        scaleX = layer.scaleX.toFloat(); scaleY = layer.scaleY.toFloat(); rotationZ = layer.rotation.toFloat()
                        translationX = layer.translationX.toFloat(); translationY = layer.translationY.toFloat()
                    }
                    // Two renderers sample the same smooth picture: a level or two apart, more only at the edges.
                    close("preview against export", shown, bound = 24, mean = 1.5) { x, y -> IntArray(3) { c -> exported.channel(x, y, c) } }
                })
            }
        }
    }
}
