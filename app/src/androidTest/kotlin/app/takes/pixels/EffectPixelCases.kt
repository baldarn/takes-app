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
import app.takes.EffectAssets
import app.takes.EffectPreview
import app.takes.NightFixEffect
import app.takes.EffectShader
import app.takes.VideoEffectChain
import app.takes.core.EffectColorMath
import app.takes.core.EffectPalette
import app.takes.core.EffectPaletteAnalysis
import app.takes.core.VideoEffectFormulasC
import app.takes.core.VideoEffectNightFix
import app.takes.core.VideoEffectPasses
import app.takes.core.VideoEffectQuantization
import app.takes.core.EffectImage
import app.takes.core.EffectUniforms
import app.takes.core.VideoEffectFormulasB
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
class EffectStage(
    private val bench: Bench, effects: List<VideoEffectSettings>, private val width: Int, private val height: Int,
    assets: EffectAssets = EffectAssets.NONE, nightFix: Boolean = false, mirror: app.takes.core.MirrorSettings? = null,
) {
    private val clip = TimelineClip(id = "C", sourceID = "S", start = 0.0, end = 10.0, videoEffects = effects, mirror = mirror)
    private val program: GlShaderProgram = (if (nightFix) NightFixEffect(clip, 0.0) else VideoEffectChain(clip, 0.0, RATE, assets)).toGlShaderProgram(bench.context, /* useHdr = */ false)
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
    fun frame(input: Picture, passes: List<app.takes.core.EffectUniforms>, picture: Bitmap? = null, person: app.takes.PersonFrame? = null): Picture {
        val width = input.width
        val height = input.height
        val bitmap = Bitmap.createBitmap(IntArray(input.pixels.size) { input.pixels[it] or (0xff shl 24) }, width, height, Bitmap.Config.ARGB_8888)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_CPU_READ_OFTEN)
        val node = RenderNode("deck").apply {
            setPosition(0, 0, width, height)
            beginRecording().drawBitmap(bitmap, 0f, 0f, null)
            endRecording()
            setRenderEffect(EffectPreview().effect(passes, width.toFloat(), height.toFloat(), picture = { picture }, person = { person }))
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
class EffectPixelCases(private val bench: Bench, private val width: Int = 256, private val height: Int = 144) {
    private val skins = listOf(rgb(241, 194, 167), rgb(224, 172, 140), rgb(198, 134, 95), rgb(161, 102, 70), rgb(141, 85, 54), rgb(92, 58, 40))

    /** Twelve bands: grey, red, green, blue, cyan, magenta and yellow ramps; two of scattered colors; a mixed ramp; skin tones lit from dark to bright. */
    private val input = Picture(width, height) { px, y ->
        val band = y * 12 / height
        // Across the picture from 0 to 255.
        val x = px * 255 / (width - 1)
        when (band) {
            0 -> rgb(x, x, x)
            1 -> rgb(x, 0, 0)
            2 -> rgb(0, x, 0)
            3 -> rgb(0, 0, x)
            4 -> rgb(0, x, x)
            5 -> rgb(x, 0, x)
            6 -> rgb(x, x, 0)
            7, 8 -> { val n = (px + width * (y - 7 * height / 12)) * 2654435761L; rgb((n shr 8).toInt() and 0xff, (n shr 16).toInt() and 0xff, (n shr 24).toInt() and 0xff) }
            9 -> rgb(x, 255 - x, (x * 3) and 0xff)
            else -> {
                val skin = skins[px % skins.size]
                val light = 0.4 + 0.8 * (px / skins.size) / (width / skins.size).toDouble() + (y - 10 * height / 12) * 0.01 * 144 / height
                rgb(((skin shr 16 and 0xff) * light).roundToInt(), ((skin shr 8 and 0xff) * light).roundToInt(), ((skin and 0xff) * light).roundToInt())
            }
        }
    }

    /** A picture for an Image background: a warm and a cool ramp crossing, of another shape than the frame. */
    private val backdrop: Bitmap = Bitmap.createBitmap(IntArray(200 * 150) { i ->
        val x = i % 200
        val y = i / 200
        (0xff shl 24) or rgb(x * 255 / 199, 80 + y, 255 - y * 255 / 149)
    }, 200, 150, Bitmap.Config.ARGB_8888)

    /**
     * What the cases' effects read: the palette of a Quantization analysed
     * from the input itself, as the original analyses a frame, and [backdrop].
     */
    private val assets = object : EffectAssets {
        override fun analysed(effect: VideoEffectSettings, sourceTime: Double): EffectPalette =
            EffectPaletteAnalysis.analyze(EffectPaletteAnalysis.samples(EffectImage.of(linear)), effect.number("colors", sourceTime).toInt(), seed = 7uL)

        override fun picture(effect: VideoEffectSettings): Bitmap = backdrop
    }

    private val linear = EffectPicture(width, height) { x, y ->
        EffectVector(EffectColorMath.linear(input.channel(x, y, 0) / 255.0), EffectColorMath.linear(input.channel(x, y, 1) / 255.0), EffectColorMath.linear(input.channel(x, y, 2) / 255.0))
    }

    private fun linear(picture: Picture): EffectImage = EffectImage.of(width, height) { x, y ->
        EffectVector(EffectColorMath.linear(picture.channel(x, y, 0) / 255.0), EffectColorMath.linear(picture.channel(x, y, 1) / 255.0), EffectColorMath.linear(picture.channel(x, y, 2) / 255.0))
    }

    /** One pass by the core's reference formula. */
    private fun reference(u: EffectUniforms, image: EffectImage, assets: EffectAssets, effects: List<VideoEffectSettings>): EffectImage = when (u.effect) {
        in VideoEffectFormulasB.referenced -> VideoEffectFormulasB.pass(u, image)
        VideoEffectID.Quantization -> VideoEffectQuantization.pass(u, image)
        VideoEffectID.ChromaKey -> {
            val scale = VideoEffectPasses.pixelScale(width, height)
            val brightness = u.number("backgroundBrightness")
            val background = when (EffectShader.background(u)) {
                "Image" -> assets.picture(effects.first { it.id == u.id })?.let { bitmap ->
                    VideoEffectFormulasC.imageBackground(
                        bitmapImage(bitmap), width, height, u.number("fit") < .5, u.number("backgroundX"), u.number("backgroundY"),
                        u.number("backgroundZoom"), u.number("backgroundBlur"), brightness,
                    )
                } ?: EffectImage.flat(1, 1, EffectVector.ZERO)
                "Blur original" -> VideoEffectFormulasC.blurOriginalBackground(image, u.number("backgroundBlur"), brightness)
                else -> EffectImage.flat(1, 1, u.vector("backgroundColor").xyz * Math.pow(2.0, brightness))
            }
            check(scale > 0)
            VideoEffectFormulasC.chromaKeyPass(u, image, background)
        }
        else -> EffectImage.of(VideoEffectFormulas.pass(u, image.toPicture()))
    }

    private fun bitmapImage(bitmap: Bitmap): EffectImage = EffectImage.of(bitmap.width, bitmap.height) { x, y ->
        val c = bitmap.getPixel(x, y)
        EffectVector(EffectColorMath.linear((c shr 16 and 0xff) / 255.0), EffectColorMath.linear((c shr 8 and 0xff) / 255.0), EffectColorMath.linear((c and 0xff) / 255.0))
    }

    /** Night fix Fast by the core ([VideoEffectNightFix.fast]) on the raw picture's encoded values, to eight bits. */
    private fun nightFixed(input: Picture, effect: VideoEffectSettings, time: Double): Picture {
        val raw = EffectImage.of(width, height) { x, y -> EffectVector(input.channel(x, y, 0) / 255.0, input.channel(x, y, 1) / 255.0, input.channel(x, y, 2) / 255.0) }
        val fixed = VideoEffectNightFix.fast(raw, effect, time)
        return Picture(width, height) { x, y ->
            val c = fixed[x, y]
            fun level(v: Double) = (v.coerceIn(0.0, 1.0) * 255).roundToInt()
            rgb(level(c.x), level(c.y), level(c.z))
        }
    }

    /** What the core's formulas make of [source] under [effects] at second [time], to eight bits. */
    private fun expected(effects: List<VideoEffectSettings>, time: Double, source: Picture = input, assets: EffectAssets = this.assets): Picture {
        var image = if (source === input) EffectImage.of(linear) else linear(source)
        for (pass in EffectShader.passes(effects, time, EffectStage.RATE, EffectAssets.inputs(assets, effects, time))) image = reference(pass, image, assets, effects)
        return Picture(width, height) { x, y ->
            val c = image[x, y]
            fun level(v: Double) = (EffectColorMath.encoded(v).coerceIn(0.0, 1.0) * 255).roundToInt()
            rgb(level(c.x), level(c.y), level(c.z))
        }
    }

    private fun distance(a: Int, b: Int): Int = (0..2).maxOf { kotlin.math.abs(((a shr (16 - 8 * it)) and 0xff) - ((b shr (16 - 8 * it)) and 0xff)) }

    /**
     * [effects] drawn at second [time] against the core. With [preview] the
     * preview's shader draws, on Android 13 and later; with [simplified] too
     * its stand-ins for blurs, which are only measured, not held to the core.
     */
    private fun case(
        name: String, effects: List<VideoEffectSettings>, allowed: Int, time: Double = 1.0, identity: Boolean = false, preview: Boolean = false, simplified: Boolean = false,
        source: Picture = input, assets: EffectAssets = this.assets, nightFix: Boolean = false,
    ) = Case(name) {
        val input = source
        val stage = EffectStage(bench, effects, width, height, assets, nightFix)
        try {
            val passes = EffectShader.passes(effects, time, EffectStage.RATE, EffectAssets.inputs(assets, effects, time))
            if (!identity && !nightFix) check(passes.isNotEmpty()) { "Nothing is drawn: the case tests nothing" }
            val drawn = if (preview && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PreviewStage.frame(input, passes, effects.firstNotNullOfOrNull { assets.picture(it) })
            } else {
                stage.frame(input, (time * 1e6).roundToLong())
            }
            // The export draws a frame a cadence holds (Super 8's) as it looks at the second held, decided at the movie's frame; the preview holds none.
            val clip = app.takes.core.TimelineClip(id = "C", sourceID = "S", start = 0.0, end = 10.0, videoEffects = effects)
            val drawnAt = if (preview || !app.takes.holdsFrames(clip)) time
            else app.takes.core.VideoEffectTiming.shown(clip, app.takes.movieFrameOffset((time * 1e6).roundToLong(), 0.0, clip, EffectStage.RATE), EffectStage.RATE, app.takes.core.VideoEffectSequenceClock.of(clip)).sourceTime
            val want = when {
                identity -> input
                nightFix -> nightFixed(input, effects.first(), time)
                else -> expected(effects, drawnAt, input, assets)
            }
            var worst = 0
            var worstAt = 0
            var over = 0
            // Night fix on the band of scattered colours: its chroma cleanup weighs each neighbour by exp(−Δy² / 0.0012),
            // where a thousandth of luma in single precision moves the average, and the brightening multiplies that.
            // There it is held to SCATTERED levels; everywhere else as any effect.
            val scattered = { i: Int -> nightFix && (i / width) * 12 / height in 7..8 }
            var scatteredWorst = 0
            for (i in input.pixels.indices) {
                val d = distance(drawn.pixels[i], want.pixels[i])
                if (scattered(i)) {
                    scatteredWorst = maxOf(scatteredWorst, d)
                    continue
                }
                if (d > worst) { worst = d; worstAt = i }
                if (d > allowed) over++
            }
            if (simplified) {
                note("simplified preview, not compared: worst $worst levels, $over pixels over $allowed")
                return@Case
            }
            note("${passes.size} pass(es), ${input.pixels.size} pixels, worst $worst levels at (${worstAt % width}, ${worstAt / width}): drawn #%06x, formula #%06x, from #%06x; %d over %d"
                .format(drawn.pixels[worstAt], want.pixels[worstAt], input.pixels[worstAt], over, allowed))
            // Single precision against double: a few saturated colours near the edge of the gamut, where a formula takes
            // the difference of two near numbers or a shoulder turns sharply, may come out a little further.
            if (effects.any { it.kind == VideoEffectID.Quantization }) {
                // A quantizer puts each pixel in one of a few bins: a pixel on the boundary between two goes to the
                // other side in single precision, a whole palette step away. Those may be up to 1 % of the picture,
                // and each must be a colour the formula gives elsewhere, or between two of them with dithering.
                val palette = want.pixels.toSet()
                val stray = input.pixels.indices.count { i -> distance(drawn.pixels[i], want.pixels[i]) > allowed && palette.none { distance(it, drawn.pixels[i]) <= allowed } }
                note("quantizer: $over pixels on another side of a boundary, $stray of them no colour of the formula's")
                check(over <= input.pixels.size / 100 && (stray == 0 || effects.last().kind != VideoEffectID.Quantization || effects.any { it.number("intensity", time) < 1 })) {
                    "$over pixels more than $allowed levels from the core's formula, $stray of them no colour it gives"
                }
                return@Case
            }
            if (nightFix) {
                note("on the scattered colours, worst $scatteredWorst levels")
                check(scatteredWorst <= SCATTERED) { "$scatteredWorst levels from the core's formula on the scattered colours, more than $SCATTERED" }
            }
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
        // Color Isolation and Chroma Key with the single-frame matte, algorithm 1, as older recipes keep it; TemporalMattePixelCases draws algorithm 2.
        if (kind == VideoEffectID.ColorIsolation || kind == VideoEffectID.ChromaKey) effect = effect.copy(algorithm = 1)
        for ((id, value) in values) effect = effect.with(id, when (value) {
            is Boolean -> EffectValue.Toggle(value)
            is String -> EffectValue.Choice(value)
            is app.takes.core.EffectRGB -> EffectValue.Color(value)
            else -> EffectValue.Number((value as Number).toDouble())
        })
        if (intensity != null) effect = effect.with("intensity", EffectValue.Number(intensity))
        return effect
    }

    private fun effect(label: String, kind: VideoEffectID, sets: List<Set>, simplified: (Map<String, Any>) -> Boolean = { false }): List<Case> {
        val cases = sets.map { set -> case("$label: ${set.label}", listOf(settings(kind, set.values)), ALLOWED, set.time) } +
            // Noir's grain too: the preview reads the export's lattice.
            sets.map { set ->
                case("$label, preview: ${set.label}", listOf(settings(kind, set.values)), ALLOWED, set.time, preview = true, simplified = simplified(set.values))
            }
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

    /** The effects of the second batch: their passes on a picture large enough for the blurs to reach. */
    fun batchB(): List<Case> {
        // A moment of Super 8's light leak, from its own clock.
        val leak = generateSequence(0.0) { it + 0.05 }.takeWhile { it < 12 }.first { VideoEffectFormulasB.superEightLeak(it).pulse > 0.6 }
        // Of the multi-pass effects the preview draws a simplified version; Crash Zoom and black bars without flares are exact.
        fun simplified(kind: VideoEffectID, values: Map<String, Any>): Boolean = when (kind) {
            VideoEffectID.CrashZoom -> false
            VideoEffectID.Widescreen -> (values["flares"] as? Double ?: 0.4) > 0 || values["bars"] == "Blur fill"
            else -> true
        }
        fun effectB(label: String, kind: VideoEffectID, sets: List<Set>): List<Case> {
            val cases = sets.map { set -> case("$label: ${set.label}", listOf(settings(kind, set.values)), ALLOWED, set.time) } +
                sets.map { set ->
                    val values = set.values + (if (kind == VideoEffectID.SixteenMM || kind == VideoEffectID.SuperEight) mapOf("grain" to 0.0) else emptyMap())
                    case("$label, preview: ${set.label}", listOf(settings(kind, values)), ALLOWED, set.time, preview = true, simplified = simplified(kind, values))
                }
            if (!kind.descriptor.supportsIntensity) return cases
            val last = sets.last()
            return cases + listOf(
                case("$label: intensity 0 leaves the picture as it was", listOf(settings(kind, last.values, 0.0)), 0, last.time, identity = true),
                case("$label: intensity 0.5", listOf(settings(kind, last.values, 0.5)), ALLOWED, last.time),
            )
        }
        return effectB("Halation", VideoEffectID.Halation, listOf(
            Set("defaults", emptyMap()),
            Set("all glow, all red, cool flash", mapOf("glow" to 1.0, "red" to 1.0, "flash" to 0.5, "flashColor" to "Cool")),
            Set("a little glow, no red, no flash", mapOf("glow" to 0.2, "red" to 0.0, "flash" to 0.0, "flashColor" to "Neutral")),
        )) + effectB("16mm", VideoEffectID.SixteenMM, listOf(
            Set("defaults", emptyMap()),
            Set("fine grain, sharp, frame edge, B&W, full weave", mapOf("grainSize" to "Fine", "softness" to 0.0, "frameEdge" to true, "color" to "B&W", "weave" to 1.0), time = 2.3),
            Set("coarse, all grain, all softness, steady", mapOf("grainSize" to "Coarse", "grain" to 1.0, "softness" to 1.0, "weave" to 0.0)),
        )) + effectB("Super 8", VideoEffectID.SuperEight, listOf(
            Set("defaults", emptyMap()),
            Set("all fade, no grain, full weave, a light leak, no border", mapOf("fade" to 1.0, "grain" to 0.0, "weave" to 1.0, "leaks" to 1.0, "border" to false), time = leak),
            Set("no fade, all grain, at 24", mapOf("fade" to 0.0, "grain" to 1.0, "fps" to 24.0), time = leak),
        )) + effectB("Widescreen 1953", VideoEffectID.Widescreen, listOf(
            Set("defaults", emptyMap()),
            Set("1.33, blur fill, all amber flares", mapOf("ratio" to "1.33:1", "bars" to "Blur fill", "flares" to 1.0, "flareColor" to "Amber")),
            Set("1.85, black, no flares", mapOf("ratio" to "1.85:1", "flares" to 0.0)),
            Set("2.20, blur fill, neutral flares", mapOf("ratio" to "2.20:1", "bars" to "Blur fill", "flareColor" to "Neutral")),
        )) + effectB("Crash Zoom", VideoEffectID.CrashZoom, listOf(
            Set("pushing in", emptyMap(), time = 0.15),
            Set("overshooting, off centre", mapOf("targetX" to 0.3, "targetY" to 0.7), time = 0.22),
            Set("holding, 4×, no blur", mapOf("zoom" to 4.0, "motionBlur" to 0.0), time = 0.9),
            Set("returning", mapOf("return" to true, "hold" to 0.5, "targetX" to 0.8), time = 0.85),
        )) + listOf(
            case("Chain: Crash Zoom, Halation, 16mm, Widescreen", listOf(
                settings(VideoEffectID.CrashZoom, emptyMap()),
                settings(VideoEffectID.Halation, emptyMap()),
                settings(VideoEffectID.SixteenMM, mapOf("grain" to 0.0)),
                settings(VideoEffectID.Widescreen, mapOf("bars" to "Blur fill")),
            ), CHAIN, time = 0.15),
        )
    }

    /**
     * A subject before a green screen: the input's bands in an oval in the middle, its edge mixed with the screen
     * over a few pixels, and the screen a little uneven, darker at the bottom; and the same before a blue screen.
     */
    private fun screened(blue: Boolean) = Picture(width, height) { x, y ->
        val dx = (x - width / 2.0) / (width * 0.3)
        val dy = (y - height / 2.0) / (height * 0.36)
        val edge = ((1 - kotlin.math.sqrt(dx * dx + dy * dy)) * 12).coerceIn(0.0, 1.0)
        val shade = 1 - 0.25 * y / height
        val screen = if (blue) rgb((30 * shade).toInt(), (60 * shade).toInt(), (210 * shade).toInt()) else rgb((40 * shade).toInt(), (200 * shade).toInt(), (60 * shade).toInt())
        val subject = input.at(x, y)
        fun mixed(shift: Int) = (((subject shr shift) and 0xff) * edge + ((screen shr shift) and 0xff) * (1 - edge)).roundToInt()
        rgb(mixed(16), mixed(8), mixed(0))
    }

    /** The effects of the third batch that change no timing: Quantization, Chroma Key's spatial keyer, Night fix Fast. */
    fun batchC(): List<Case> {
        val green = screened(blue = false)
        val blue = screened(blue = true)
        val quantization = effect("Quantization", VideoEffectID.Quantization, listOf(
            Set("defaults: four from the scene, smoothed", emptyMap()),
            Set("eight from the scene, no smoothing, ordered 8×8", mapOf("colors" to 8.0, "smoothing" to 0.0, "dithering" to "Ordered 8x8")),
            Set("B&W in five, smoothing 1", mapOf("colors" to 5.0, "monochrome" to true, "smoothing" to 1.0)),
            Set("Pop 2 in six, ordered 4×4", mapOf("colors" to 6.0, "palette" to "Pop 2", "dithering" to "Ordered 4x4", "smoothing" to 0.2)),
        ), simplified = { it["smoothing"] != 0.0 })
        fun key(label: String, values: Map<String, Any>, source: Picture, simplified: Boolean = true, intensity: Double? = null) = listOf(
            case("Chroma Key: $label", listOf(settings(VideoEffectID.ChromaKey, values, intensity)), ALLOWED, source = source),
            case("Chroma Key, preview: $label", listOf(settings(VideoEffectID.ChromaKey, values, intensity)), ALLOWED, source = source, preview = true, simplified = simplified),
        )
        return quantization +
            key("defaults, found green screen, solid background", emptyMap(), green) +
            key("blue screen set by hand, hard edge, grown, image filling", mapOf(
                "autoScreen" to false, "screenColor" to app.takes.core.EffectRGB.hex("#1E3CD2"), "screenType" to "Blue", "softness" to 0.0, "grow" to 2.0,
                "background" to "Image", "backgroundZoom" to 1.4, "backgroundX" to 0.2, "backgroundBrightness" to 0.4,
            ), blue) +
            key("strong, shrunk, uneven balance, image fitted and blurred", mapOf(
                "strength" to 1.6, "balance" to 0.3, "grow" to -1.5, "softness" to 1.0, "despill" to 0.3, "wrap" to 1.0,
                "background" to "Image", "fit" to "Fit", "backgroundBlur" to 6.0,
            ), green) +
            key("blur original, darker", mapOf("background" to "Blur original", "backgroundBrightness" to -0.5, "backgroundBlur" to 4.0), green) +
            // Match colour: the subject brought toward the new background's light. Metal:409-413, Renderer:536-541.
            key("match colour to a warm solid, brighter", mapOf("matchColor" to true, "matchStrength" to 0.8, "backgroundColor" to app.takes.core.EffectRGB.hex("#D2783C"), "backgroundBrightness" to 0.5), green) +
            key("match colour to an image, at its default strength", mapOf("matchColor" to true, "background" to "Image", "backgroundBrightness" to -0.3), blue) +
            // The matte as it is shown, its alpha sRGB-encoded: near black a thousandth of alpha is a level or more.
            key("the matte, screen set by hand", mapOf("view" to "Matte", "softness" to 0.0, "autoScreen" to false, "screenColor" to app.takes.core.EffectRGB.hex("#28C83C")), green, simplified = false) +
            key("half", emptyMap(), green, intensity = 0.5) +
            listOf(
                case("Chroma Key: intensity 0 leaves the picture as it was", listOf(settings(VideoEffectID.ChromaKey, emptyMap(), 0.0)), 0, source = green, identity = true),
                case("Night fix: defaults", listOf(settings(VideoEffectID.NightFix, emptyMap())), ALLOWED, nightFix = true),
                case("Night fix: bright, strong reduction, little detail", listOf(settings(VideoEffectID.NightFix, mapOf("brightness" to 5.0, "reduction" to 2.5, "detail" to 0.1))), ALLOWED, nightFix = true),
                case("Night fix: no brightening, light reduction, at half", listOf(settings(VideoEffectID.NightFix, mapOf("brightness" to 0.0, "reduction" to 0.4, "detail" to 1.0), 0.5)), ALLOWED, nightFix = true),
                case("Night fix: no reduction nor brightening leaves the picture as it was", listOf(settings(VideoEffectID.NightFix, mapOf("brightness" to 0.0, "reduction" to 0.0))), 0, nightFix = true, identity = true),
                case("Chain: Chroma Key, Quantization, Halation", listOf(
                    settings(VideoEffectID.ChromaKey, mapOf("background" to "Blur original")),
                    settings(VideoEffectID.Quantization, mapOf("smoothing" to 0.0)),
                    settings(VideoEffectID.Halation, emptyMap()),
                ), CHAIN, source = green),
            )
    }

    private companion object {
        /** Eight bits in, a single-precision shader, eight bits out: a level of rounding each way. */
        const val ALLOWED = 2

        /** A chain is kept at eight bits between its passes, where the original keeps half floats: a level more each pass. */
        const val CHAIN = 4

        /**
         * How many pixels of a case may lie further than its allowance, and by how much at most: those on a hard
         * edge of the picture (a hue range with no softness, a kaleidoscope wedge), where one GPU's trigonometry
         * puts a pixel on the other side. A Mali GPU had 45 of the 36,864, by up to 7 levels.
         */
        const val OUTLIERS = 64

        /** Night fix's allowance on the scattered colours, against a 255 levels' range in a random neighbourhood. */
        const val SCATTERED = 32
        const val OUTLIER_LEVELS = 8
    }
}
