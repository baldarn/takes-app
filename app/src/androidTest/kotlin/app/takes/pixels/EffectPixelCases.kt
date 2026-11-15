@file:OptIn(UnstableApi::class)

package app.takes.pixels

import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlShaderProgram
import android.graphics.Bitmap
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import androidx.annotation.RequiresApi
import app.takes.EffectPreview
import app.takes.EffectShader
import app.takes.VideoEffectChain
import app.takes.core.EffectColorMath
import app.takes.core.EffectPicture
import app.takes.core.EffectValue
import app.takes.core.EffectVector
import app.takes.core.TimelineClip
import app.takes.core.VideoEffectFormulas
import app.takes.core.VideoEffectID
import app.takes.core.VideoEffectSettings
import app.takes.core.RGBChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The export's [VideoEffectChain] on one frame: [input] in, the frame it
 * draws out, on a real graphics context with no window.
 */
class EffectStage(private val bench: Bench, effects: List<VideoEffectSettings>, private val width: Int, private val height: Int) {
    private val clip = TimelineClip(id = "C", sourceID = "S", start = 0.0, end = 10.0, videoEffects = effects)
    private val program: GlShaderProgram = VideoEffectChain(clip, 0.0, RATE).toGlShaderProgram(bench.context, /* useHdr = */ false)
    private var output: GlTextureInfo? = null
    private var failure: VideoFrameProcessingException? = null
    private val texture = IntArray(1)
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

    fun frame(input: Picture, timeUs: Long = 1_000_000): Picture {
        bytes.clear()
        // OpenGL counts rows from the bottom.
        for (row in 0 until height) for (x in 0 until width) {
            val color = input.at(x, height - 1 - row)
            bytes.put((color shr 16).toByte()).put((color shr 8).toByte()).put(color.toByte()).put(0xff.toByte())
        }
        bytes.flip()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
        GlUtil.checkGlError()
        output = null
        program.queueInputFrame(bench.objects, GlTextureInfo(texture[0], C.INDEX_UNSET, C.INDEX_UNSET, width, height), timeUs)
        failure?.let { throw it }
        val drawn = checkNotNull(output) { "The effect gave no frame" }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, drawn.fboId)
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        bytes.clear()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
        GlUtil.checkGlError()
        program.releaseOutputFrame(drawn)
        val pixels = IntArray(width * height)
        for (row in 0 until height) for (x in 0 until width) {
            val at = (row * width + x) * 4
            pixels[(height - 1 - row) * width + x] = rgb(bytes.get(at).toInt() and 0xff, bytes.get(at + 1).toInt() and 0xff, bytes.get(at + 2).toInt() and 0xff)
        }
        return Picture(width, height, pixels)
    }

    fun release() {
        program.release()
        GLES20.glDeleteTextures(1, texture, 0)
    }

    companion object {
        const val RATE = 30.0
    }
}

/**
 * The preview's [EffectPreview] on one picture: a layer showing [input] with
 * the deck's render effect, drawn by the platform's renderer into an image
 * and read back. Android 13 and later.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
object PreviewStage {
    fun frame(input: Picture, passes: List<app.takes.core.EffectUniforms>): Picture {
        val width = input.width
        val height = input.height
        val bitmap = Bitmap.createBitmap(IntArray(input.pixels.size) { input.pixels[it] or (0xff shl 24) }, width, height, Bitmap.Config.ARGB_8888)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_CPU_READ_OFTEN)
        val node = RenderNode("deck").apply {
            setPosition(0, 0, width, height)
            beginRecording().drawBitmap(bitmap, 0f, 0f, null)
            endRecording()
            setRenderEffect(EffectPreview().effect(passes, width.toFloat(), height.toFloat()))
        }
        val root = RenderNode("root").apply {
            setPosition(0, 0, width, height)
            beginRecording().drawRenderNode(node)
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
 * The pixel tests of the video effects: each effect of the first batch drawn
 * by the export's shader on a synthetic picture (ramps of grey, of the
 * primaries and their complements, a spread of colors, and skin tones from
 * dark to bright) at its defaults and at two other sets of values, against
 * the core's reference formula ([VideoEffectFormulas.pass]) worked out in
 * double precision; at half intensity; and at intensity 0, which leaves the
 * picture as it was. The blocks of numbers are the core's ([EffectShader.passes]).
 */
class EffectPixelCases(private val bench: Bench) {
    private val width = 256
    private val height = 144
    private val skins = listOf(rgb(241, 194, 167), rgb(224, 172, 140), rgb(198, 134, 95), rgb(161, 102, 70), rgb(141, 85, 54), rgb(92, 58, 40))

    /** Twelve bands: grey, red, green, blue, cyan, magenta and yellow ramps; two of scattered colors; a mixed ramp; skin tones lit from dark to bright. */
    private val input = Picture(width, height) { x, y ->
        val band = y / 12
        when (band) {
            0 -> rgb(x, x, x)
            1 -> rgb(x, 0, 0)
            2 -> rgb(0, x, 0)
            3 -> rgb(0, 0, x)
            4 -> rgb(0, x, x)
            5 -> rgb(x, 0, x)
            6 -> rgb(x, x, 0)
            7, 8 -> { val n = (x + 256 * (y - 84)) * 2654435761L; rgb((n shr 8).toInt() and 0xff, (n shr 16).toInt() and 0xff, (n shr 24).toInt() and 0xff) }
            9 -> rgb(x, 255 - x, (x * 3) and 0xff)
            else -> {
                val skin = skins[x % skins.size]
                val light = 0.4 + 0.8 * (x / skins.size) / (width / skins.size).toDouble() + (y - 120) * 0.01
                rgb(((skin shr 16 and 0xff) * light).roundToInt(), ((skin shr 8 and 0xff) * light).roundToInt(), ((skin and 0xff) * light).roundToInt())
            }
        }
    }

    private val linear = EffectPicture(width, height) { x, y ->
        EffectVector(EffectColorMath.linear(input.channel(x, y, 0) / 255.0), EffectColorMath.linear(input.channel(x, y, 1) / 255.0), EffectColorMath.linear(input.channel(x, y, 2) / 255.0))
    }

    /** What the core's formulas make of the input under [effects] at second [time], to eight bits. */
    private fun expected(effects: List<VideoEffectSettings>, time: Double): Picture {
        var picture = linear
        for (pass in EffectShader.passes(effects, time, EffectStage.RATE)) picture = VideoEffectFormulas.pass(pass, picture)
        return Picture(width, height) { x, y ->
            val c = picture[x, y]
            fun level(v: Double) = (EffectColorMath.encoded(v).coerceIn(0.0, 1.0) * 255).roundToInt()
            rgb(level(c.x), level(c.y), level(c.z))
        }
    }

    private fun distance(a: Int, b: Int): Int = (0..2).maxOf { kotlin.math.abs(((a shr (16 - 8 * it)) and 0xff) - ((b shr (16 - 8 * it)) and 0xff)) }

    private fun case(name: String, effects: List<VideoEffectSettings>, allowed: Int, time: Double = 1.0, identity: Boolean = false, preview: Boolean = false) = Case(name) {
        val stage = EffectStage(bench, effects, width, height)
        try {
            val passes = EffectShader.passes(effects, time, EffectStage.RATE)
            if (!identity) check(passes.isNotEmpty()) { "Nothing is drawn: the case tests nothing" }
            val drawn = if (preview && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) PreviewStage.frame(input, passes) else stage.frame(input, (time * 1e6).roundToLong())
            val want = if (identity) input else expected(effects, time)
            var worst = 0
            var worstAt = 0
            var over = 0
            for (i in input.pixels.indices) {
                val d = distance(drawn.pixels[i], want.pixels[i])
                if (d > worst) { worst = d; worstAt = i }
                if (d > allowed) over++
            }
            note("${passes.size} pass(es), ${input.pixels.size} pixels, worst $worst levels at (${worstAt % width}, ${worstAt / width}): drawn #%06x, formula #%06x, from #%06x; %d over %d"
                .format(drawn.pixels[worstAt], want.pixels[worstAt], input.pixels[worstAt], over, allowed))
            // Single precision against double: a few saturated colours near the edge of the gamut, where a formula takes
            // the difference of two near numbers or a shoulder turns sharply, may come out a little further.
            val outliers = if (allowed == 0) 0 else OUTLIERS
            check(over <= outliers && worst <= maxOf(allowed, if (outliers > 0) OUTLIER_LEVELS else 0)) {
                "$over pixels more than $allowed levels from the core's formula (at most $outliers may be, by up to $OUTLIER_LEVELS), the worst $worst"
            }
        } finally {
            stage.release()
        }
    }

    private class Set(val label: String, val values: Map<String, Any>, val time: Double = 1.0)

    private fun settings(kind: VideoEffectID, values: Map<String, Any>, intensity: Double? = null): VideoEffectSettings {
        var effect = VideoEffectSettings(kind)
        // Color Isolation and Chroma Key are made with the spatial matte on Android for now.
        if (kind == VideoEffectID.ColorIsolation) effect = effect.copy(algorithm = 1)
        for ((id, value) in values) effect = effect.with(id, when (value) {
            is Boolean -> EffectValue.Toggle(value)
            is String -> EffectValue.Choice(value)
            is app.takes.core.EffectRGB -> EffectValue.Color(value)
            else -> EffectValue.Number((value as Number).toDouble())
        })
        if (intensity != null) effect = effect.with("intensity", EffectValue.Number(intensity))
        return effect
    }

    private fun effect(label: String, kind: VideoEffectID, sets: List<Set>): List<Case> {
        val cases = sets.map { set -> case("$label: ${set.label}", listOf(settings(kind, set.values)), ALLOWED, set.time) } +
            // The preview draws no grain.
            sets.map { set -> case("$label, preview: ${set.label}", listOf(settings(kind, if (kind == VideoEffectID.Noir) set.values + ("grain" to false) else set.values)), ALLOWED, set.time, preview = true) }
        if (!kind.descriptor.supportsIntensity) return cases
        val last = sets.last()
        return cases + listOf(
            case("$label: intensity 0 leaves the picture as it was", listOf(settings(kind, last.values, 0.0)), 0, last.time, identity = true),
            case("$label: intensity 0.5", listOf(settings(kind, last.values, 0.5)), ALLOWED, last.time),
        )
    }

    fun all(): List<Case> =
        effect("Color Correction", VideoEffectID.ColorCorrection, listOf(
            Set("exposure only", mapOf("exposure" to 0.3)),
            Set("brighter, more contrast, warm, saturated", mapOf("exposure" to 0.5, "contrast" to 1.4, "shadows" to 0.3, "highlights" to -0.2, "temperature" to 0.4, "tint" to -0.2, "saturation" to 1.3, "vibrance" to 0.4)),
            Set("darker, flat, low pivot, hue turned", mapOf("exposure" to -0.7, "contrast" to 0.7, "pivot" to 0.35, "blacks" to 0.4, "whites" to -0.5, "tint" to 0.5, "saturation" to 0.4, "vibrance" to -0.3, "hue" to 45.0)),
        )) + listOf(case("Color Correction: defaults leave the picture as it was", listOf(settings(VideoEffectID.ColorCorrection, emptyMap())), 1, identity = true)) +
        effect("Silent Tint", VideoEffectID.SilentTint, listOf(
            Set("defaults", emptyMap()),
            Set("Night Blue, toned, contrast 1.4", mapOf("tint" to "Night Blue", "mode" to "Tone", "contrast" to 1.4)),
            Set("Sepia, contrast 0.8", mapOf("tint" to "Sepia", "contrast" to 0.8)),
        )) + effect("Three-Strip", VideoEffectID.ThreeStrip, listOf(
            Set("defaults", emptyMap()),
            Set("saturation 1.8, deep red, cool", mapOf("saturation" to 1.8, "redDepth" to 1.0, "warmth" to -1.0)),
            Set("saturation 1, no red depth, warm", mapOf("saturation" to 1.0, "redDepth" to 0.0, "warmth" to 1.0)),
        )) + effect("Bleach Bypass", VideoEffectID.BleachBypass, listOf(
            Set("defaults", emptyMap()),
            Set("strength 1", mapOf("strength" to 1.0)),
            Set("strength 0.3", mapOf("strength" to 0.3)),
        )) + effect("Day for Night", VideoEffectID.DayForNight, listOf(
            Set("defaults", emptyMap()),
            Set("darkest, little blue, no moonlight", mapOf("darkness" to 1.0, "blue" to 0.2, "moonlight" to false)),
            Set("light, all blue", mapOf("darkness" to 0.3, "blue" to 1.0)),
        )) + effect("Noir", VideoEffectID.Noir, listOf(
            Set("defaults, with grain", emptyMap()),
            Set("contrast 2, crush 0.3, blue shadows, no grain", mapOf("contrast" to 2.0, "crush" to 0.3, "shadowColor" to true, "grain" to false)),
            Set("contrast 1, no crush, grain", mapOf("contrast" to 1.0, "crush" to 0.0)),
        )) + effect("Expressionism", VideoEffectID.Expressionism, listOf(
            Set("defaults", emptyMap()),
            Set("contrast 3, full vignette, circle, tint", mapOf("contrast" to 3.0, "vignette" to 1.0, "iris" to "Circle", "tint" to true)),
            Set("contrast 1.5, no vignette", mapOf("contrast" to 1.5, "vignette" to 0.0)),
        )) + effect("Color Isolation", VideoEffectID.ColorIsolation, listOf(
            Set("defaults", emptyMap()),
            Set("green, wide and soft", mapOf("color" to app.takes.core.EffectRGB.hex("#2ECC71"), "range" to 60.0, "softness" to 1.0)),
            Set("skin hue, narrow and hard", mapOf("color" to app.takes.core.EffectRGB.hex("#D9A066"), "range" to 5.0, "softness" to 0.0)),
        )) + effect("RGB Offset", VideoEffectID.RgbOffset, listOf(
            Set("red right, blue left", mapOf(RGBChannel.Red.x to 0.2, RGBChannel.Blue.x to -0.2)),
            Set("all channels apart, both ways", mapOf(RGBChannel.Red.x to 0.5, RGBChannel.Red.y to -0.3, RGBChannel.Green.y to 0.4, RGBChannel.Blue.x to -1.0, RGBChannel.Blue.y to 1.0)),
            Set("a small shift", mapOf(RGBChannel.Green.x to 0.013, RGBChannel.Green.y to -0.007)),
        )) + effect("Negative Strobe", VideoEffectID.NegativeStrobe, listOf(
            Set("defaults, on a negative frame", emptyMap(), time = 0.07),
            Set("1 a second, on a negative frame", mapOf("frequency" to 1.0), time = 0.75),
            Set("2 a second, on a negative frame", mapOf("frequency" to 2.0), time = 0.3),
        )) + listOf(case("Negative Strobe: a positive frame is left as it was", listOf(settings(VideoEffectID.NegativeStrobe, mapOf("frequency" to 1.0))), 0, time = 0.25, identity = true)) +
        effect("Kaleidoscope", VideoEffectID.Kaleidoscope, listOf(
            Set("defaults", emptyMap()),
            Set("five divisions, off centre, turned", mapOf("divisions" to 5.0, "centerX" to 0.4, "centerY" to 0.6, "splitAngle" to 30.0, "sourceAngle" to -20.0)),
            Set("eight divisions, panned", mapOf("divisions" to 8.0, "sourcePanX" to 0.3, "sourcePanY" to -0.2)),
        )) + listOf(
            case("Chain: Color Correction, Silent Tint at half, Noir", listOf(
                settings(VideoEffectID.ColorCorrection, mapOf("exposure" to 0.4, "saturation" to 1.6, "hue" to -30.0)),
                settings(VideoEffectID.SilentTint, mapOf("tint" to "Fire Red"), 0.5),
                settings(VideoEffectID.Noir, mapOf("grain" to false)),
            ), CHAIN),
            case("Chain: RGB Offset after Day for Night", listOf(
                settings(VideoEffectID.DayForNight, emptyMap()),
                settings(VideoEffectID.RgbOffset, mapOf(RGBChannel.Red.x to 0.3)),
            ), CHAIN),
        )

    private companion object {
        /** Eight bits in, a single-precision shader, eight bits out: a level of rounding each way. */
        const val ALLOWED = 2

        /** A chain is kept at eight bits between its passes, where the original keeps half floats: a level more each pass. */
        const val CHAIN = 4

        /** How many pixels of a case may lie further than its allowance, and by how much at most. */
        const val OUTLIERS = 16
        const val OUTLIER_LEVELS = 8
    }
}
