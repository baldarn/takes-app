package app.takes.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * The reference formulas of the third batch of video effects: the pixel
 * branches of Freeze Frame, Step Printing and Undercranking, and Chroma Key's
 * spatial keyer (algorithm 1 and older recipes) with its passes and
 * backgrounds. Each is a branch or a kernel of YouRec/VideoEffects/VideoEffects.metal
 * (cited as `Metal`) with the same constants and order of operations,
 * prepared as YouRec/VideoEffects/MetalVideoEffects.swift (`Renderer`) and
 * YouRec/VideoEffects/VideoEffectAssets.swift (`Assets`) prepare them.
 * Conventions are those of [VideoEffectFormulas]. The temporal matte, the
 * person hint, match colour and the video, Studio and Window backgrounds
 * are not here.
 */

/** What a keyed pixel is drawn against, all read at the pixel: the new [background], the [screen] colour, and the blurred background the light wrap adds. */
data class ChromaKeyInputs(val background: EffectVector, val screen: EffectVector, val wrap: EffectVector)

object VideoEffectFormulasC {
    private val math = VideoEffectShaderMath

    // Freeze Frame. Metal:96, :386-387; parameters hold, push, style, audio.

    /** Where the pixel at [uv] reads the held frame after [time] seconds of hold: magnified about the centre by `1 + push · clamp(time / hold, 0, 1)`. Metal:96. */
    fun freezeFramePoint(uv: Point, time: Double, hold: Double = 3.0, push: Double = 0.05): Point {
        val zoom = 1 + push * minOf(1.0, maxOf(0.0, time / maxOf(.001, hold)))
        return Point((uv.x - .5) / zoom + .5, (uv.y - .5) / zoom + .5)
    }

    /** The Print style before its grain: 5 % towards grey and a 12 % vignette from 0.25 to 0.7 of the way out. Metal:387. */
    fun freezeFramePrint(c: EffectVector, uv: Point): EffectVector {
        val out = math.mix(EffectVector(math.luma(c)), c, .95)
        return out * (1 - .12 * math.smoothstep(.25, .7, sqrt((uv.x - .5).pow(2) + (uv.y - .5).pow(2))))
    }

    /** Print's grain: strength 0.3 at 1.5 pixels (at 1080), in colour. Metal:387, Renderer:440. */
    const val FREEZE_PRINT_GRAIN = 0.3
    const val FREEZE_PRINT_GRAIN_SIZE = 1.5

    // Step Printing. Metal:388.

    /** Step Printing's branch: the colour moved towards the block's blend by the smear. The compositor blends the frames itself and never draws it. */
    fun stepPrinting(c: EffectVector, blended: EffectVector, smear: Double = 0.7): EffectVector = math.mix(c, blended, smear)

    // Undercranking. Metal:97-100, :389; parameters fps, speed, flicker, weave, mute.

    /** Undercranking of the colour read at [VideoEffectTiming.undercrankingPoint]: the frame's flicker. */
    fun undercranking(c: EffectVector, frame: Int, flicker: Double = 0.5): EffectVector = c * VideoEffectTiming.undercrankingFlicker(frame, flicker)

    // Chroma Key. Metal:400-416, :468-490, :526-547; Renderer:506-530.

    /** Whether the key is a blue screen: by Screen type ([screenType] 0 Auto, 1 Green, 2 Blue), or on Auto when the screen is bluer than green. Metal:470. */
    fun blueScreen(screen: EffectVector, screenType: Int): Boolean = screenType > 1.5 || (screenType < .5 && screen.z > screen.y)

    /**
     * The screen colour the border says, `ve_screenReference`: 16 points
     * along each edge, 2 % in; those that are clearly green (or blue, by the
     * type, or by which are more on Auto) give the median of each channel; with
     * fewer than four, the manual [screenColor]. Metal:526-547.
     */
    fun screenReference(input: EffectImage, screenColor: EffectVector, screenType: Int = 0): EffectVector {
        val samples = mutableListOf<EffectVector>()
        var greens = 0
        var blues = 0
        for (i in 0 until 16) {
            val t = (i + .5) / 16
            for (location in listOf(Point(t, .02), Point(t, .98), Point(.02, t), Point(.98, t))) {
                val c = input.sample(location)
                samples += c
                if (c.y - maxOf(c.x, c.z) > .02) greens++
                if (c.z - maxOf(c.x, c.y) > .02) blues++
            }
        }
        val blue = screenType > 1.5 || (screenType < .5 && blues > greens)
        val values = samples.filter { (if (blue) it.z - maxOf(it.x, it.y) else it.y - maxOf(it.x, it.z)) > .02 }
        if (values.size < 4) return screenColor
        return EffectVector(values.map { it.x }.sorted()[values.size / 2], values.map { it.y }.sorted()[values.size / 2], values.map { it.z }.sorted()[values.size / 2])
    }

    /**
     * How much of a pixel is kept, `ve_keyAlpha`: one minus its colour
     * difference (the key channel over the others, the larger of them at an
     * even [balance], else red and the other channel weighted by it) over the
     * screen's, times [strength]; then stretched between [blackClip] and
     * [whiteClip]. Metal:468-480.
     */
    fun keyAlpha(
        c: EffectVector,
        screen: EffectVector,
        screenType: Int = 0,
        strength: Double = 1.0,
        balance: Double = 0.5,
        blackClip: Double = 0.05,
        whiteClip: Double = 0.9,
    ): Double {
        val blue = blueScreen(screen, screenType)
        val key = if (blue) c.z else c.y
        val other = if (blue) c.y else c.z
        val sk = if (blue) screen.z else screen.y
        val so = if (blue) screen.y else screen.z
        val even = abs(balance - .5) < .0001
        val d = key - if (even) maxOf(c.x, other) else balance * c.x + (1 - balance) * other
        val ds = sk - if (even) maxOf(screen.x, so) else balance * screen.x + (1 - balance) * so
        val a = 1 - math.clamp(strength * d / maxOf(ds, .0001), 0.0, 1.0)
        return math.clamp((a - blackClip) / maxOf(.0001, whiteClip - blackClip), 0.0, 1.0)
    }

    /**
     * One pass of the matte's grow or shrink, `ve_morphology`: the largest
     * (growing, [radius] ≥ 0) or smallest value along a line of whole pixels
     * up to the radius's floor and at the radius itself, read between pixels;
     * at most 64. Metal:481-490.
     */
    fun morphology(input: EffectImage, radius: Double, horizontal: Boolean): EffectImage {
        val reach = minOf(64.0, abs(radius))
        val r = floor(reach).toInt()
        val grow = radius >= 0
        val dx = if (horizontal) 1.0 / input.width else 0.0
        val dy = if (horizontal) 0.0 else 1.0 / input.height
        return EffectImage.of(input.width, input.height) { x, y ->
            val uv = input.uv(x, y)
            var a = input.sample(uv).x
            for (i in -r..r) {
                val b = input.sample(Point(uv.x + dx * i, uv.y + dy * i)).x
                a = if (grow) maxOf(a, b) else minOf(a, b)
            }
            for (sign in listOf(-1, 1)) {
                val b = input.sample(Point(uv.x + dx * reach * sign, uv.y + dy * reach * sign)).x
                a = if (grow) maxOf(a, b) else minOf(a, b)
            }
            EffectVector(a)
        }
    }

    /** The grow or shrink of a matte by [radius] pixels: a pass across, then one down. Renderer:328-333. */
    fun morph(input: EffectImage, radius: Double): EffectImage = morphology(morphology(input, radius, true), radius, false)

    /**
     * The spatial matte, a picture with the alpha in every channel: the key
     * of each pixel against the screen ([screen] under it), grown by
     * "grow" pixels at 1080, then softened by a Gaussian of `1.5 · softness`
     * pixels at 1080. Renderer:506-530.
     */
    fun matte(input: EffectImage, screen: (Point) -> EffectVector, u: EffectUniforms): EffectImage {
        val scale = VideoEffectPasses.pixelScale(input.width, input.height)
        var matte = EffectImage.of(input.width, input.height) { x, y ->
            val uv = input.uv(x, y)
            EffectVector(
                keyAlpha(
                    input.sample(uv), screen(uv), u.number("screenType").toInt(), u.number("strength"), u.number("balance"),
                    u.number("blackClip"), u.number("whiteClip"),
                ),
            )
        }
        val grow = u.number("grow") * scale
        if (grow != 0.0) matte = morph(matte, grow)
        val softness = u.number("softness")
        return if (softness > 0) VideoEffectPasses.blur(matte, softness * 1.5 * scale) else matte
    }

    /**
     * A keyed pixel, `ve_effect` case 18: the straight colour unmixed from
     * the screen under alpha [a] and held inside 0…1, despilled (the key
     * channel brought towards 0.525 × the other two by [despill], what it
     * loses spread back as (0.3, 0.6, 0.1)/2), laid over the background, and
     * the light wrap added on the matte's edge, `a(1 − a) · 4`. [view] 1
     * shows the matte, 2 the original. Metal:400-416.
     */
    fun chromaKey(
        c: EffectVector,
        a: Double,
        inputs: ChromaKeyInputs,
        screenType: Int = 0,
        despill: Double = 0.8,
        wrap: Double = 0.25,
        view: Int = 0,
    ): EffectVector {
        val screen = inputs.screen
        var straight = ((c - screen * (1 - a)) / maxOf(a, .0001)).clamped()
        val blue = blueScreen(screen, screenType)
        val channel = if (blue) 2 else 1
        val limit = if (blue) (straight.x + straight.y) * .525 else (straight.x + straight.z) * .525
        val before = straight[channel]
        val after = minOf(before, math.mix(before, limit, despill))
        straight = if (blue) straight.copy(z = after) else straight.copy(y = after)
        straight += EffectVector(.3, .6, .1) * ((before - after) * .5)
        var out = straight * a + inputs.background * (1 - a)
        out += inputs.wrap * (a * (1 - a) * 4 * wrap * .25)
        return when (view) {
            1 -> EffectVector(a)
            2 -> c
            else -> out
        }
    }

    // Backgrounds. Assets:586-627.

    /** A solid background: the colour (sRGB) in linear light, times 2^brightness. */
    fun solidBackground(color: EffectRGB, brightness: Double = 0.0): EffectVector = EffectColorMath.linear(color) * 2.0.pow(brightness)

    /**
     * An image background on a picture of [width] × [height]: scaled to fill
     * (or fit) and by the zoom, placed by X and Y (0 the image's left or top
     * edge on the picture's, 1 its right or bottom), black outside it,
     * blurred by "backgroundBlur" (at 1080) and brought up or down by
     * "backgroundBrightness" stops. Core Image's Gaussian is taken with the
     * radius as σ.
     */
    fun imageBackground(image: EffectImage, width: Int, height: Int, fill: Boolean = true, x: Double = .5, y: Double = .5, zoom: Double = 1.0, blur: Double = 0.0, brightness: Double = 0.0): EffectImage {
        val scale = (if (fill) maxOf(width.toDouble() / image.width, height.toDouble() / image.height) else minOf(width.toDouble() / image.width, height.toDouble() / image.height)) * zoom
        val left = (width - image.width * scale) * x
        val top = (height - image.height * scale) * y
        var placed = EffectImage.of(width, height) { px, py ->
            val u = (px + .5 - left) / scale / image.width
            val v = (py + .5 - top) / scale / image.height
            if (u < 0 || u > 1 || v < 0 || v > 1) EffectVector.ZERO else image.sample(Point(u, v))
        }
        if (blur > 0) placed = VideoEffectPasses.blur(placed, blur * VideoEffectPasses.pixelScale(width, height))
        val gain = 2.0.pow(brightness)
        return EffectImage.of(width, height) { px, py -> placed[px, py] * gain }
    }

    /** "Blur original": the picture blurred by 20 pixels at 1080 (Core Image's radius taken as σ), then the background blur and brightness. */
    fun blurOriginalBackground(input: EffectImage, blur: Double = 0.0, brightness: Double = 0.0): EffectImage {
        val scale = VideoEffectPasses.pixelScale(input.width, input.height)
        var image = VideoEffectPasses.blur(input, 20 * scale)
        if (blur > 0) image = VideoEffectPasses.blur(image, blur * scale)
        val gain = 2.0.pow(brightness)
        return EffectImage.of(input.width, input.height) { x, y -> image[x, y] * gain }
    }

    /** Whether a clean plate of [plate]'s shape may be used on a frame of [frame]'s: their aspect ratios within 1 %. VideoEffectCleanPlate.swift:27-30. */
    fun cleanPlateCompatible(plate: Size, frame: Size): Boolean =
        plate.width > 0 && plate.height > 0 && frame.width > 0 && frame.height > 0 && abs((plate.width / plate.height) / (frame.width / frame.height) - 1) < 0.01

    /**
     * A clean plate from a still picture: scaled to the frame and blurred by
     * 8 pixels at 1080 (Core Image's radius taken as σ). Its colour grade
     * and the frame's crop are the caller's. Assets:504-519.
     */
    fun cleanPlate(still: EffectImage, width: Int, height: Int): EffectImage? {
        if (!cleanPlateCompatible(still.size, Size(width.toDouble(), height.toDouble()))) return null
        val scaled = EffectImage.of(width, height) { x, y -> still.sample(Point((x + .5) / width, (y + .5) / height)) }
        return VideoEffectPasses.blur(scaled, 8 * VideoEffectPasses.pixelScale(width, height))
    }

    /** What a Chroma Key pass reads besides its input, as the renderer draws it. */
    data class ChromaKeyPictures(val matte: EffectImage, val screen: (Point) -> EffectVector, val background: EffectImage, val wrap: EffectImage)

    /**
     * Chroma Key's pictures for one frame with the new [background] (a
     * picture of the frame's size, or 1 × 1 for a solid colour) and an
     * optional [cleanPlate]: the screen is the plate, else the border's
     * reference when "autoScreen" is on, else the manual colour; the light
     * wrap reads the background blurred by 2 % of the width at a quarter of
     * the size, or for a solid one the background itself. Renderer:506-530.
     */
    fun chromaKeyPictures(u: EffectUniforms, input: EffectImage, background: EffectImage, cleanPlate: EffectImage? = null): ChromaKeyPictures {
        require(u.effect == VideoEffectID.ChromaKey)
        val manual = u.vector("screenColor").xyz
        val screen: (Point) -> EffectVector = when {
            cleanPlate != null -> { uv -> cleanPlate.sample(uv) }
            u.number("autoScreen") > .5 -> screenReference(input, manual, u.number("screenType").toInt()).let { reference -> { _ -> reference } }
            else -> { _ -> manual }
        }
        val solid = u.number("background").toInt() == 0
        val wrap = if (u.number("wrap") > 0 && !solid) {
            VideoEffectPasses.blur(background, input.width * 0.02, VideoEffectPasses.quarter(input.width), VideoEffectPasses.quarter(input.height))
        } else {
            background
        }
        return ChromaKeyPictures(matte(input, screen, u), screen, background, wrap)
    }

    /** One keyed pixel of the pass from its pictures, mixed with the input by the intensity. */
    fun chromaKeyPixel(u: EffectUniforms, input: EffectImage, pictures: ChromaKeyPictures, x: Int, y: Int): EffectVector {
        val uv = input.uv(x, y)
        val c = input.sample(uv)
        val out = chromaKey(
            c, pictures.matte.sample(uv).x, ChromaKeyInputs(pictures.background.sample(uv), pictures.screen(uv), pictures.wrap.sample(uv)),
            u.number("screenType").toInt(), u.number("despill"), u.number("wrap"), u.number("view").toInt(),
        )
        return VideoEffectFormulas.mixed(c, out, u.intensity)
    }

    /**
     * A whole spatial Chroma Key pass. A recipe with the temporal matte
     * ([EffectUniforms.FLAG_TEMPORAL_MATTE]) or the person hint needs what is
     * not here.
     */
    fun chromaKeyPass(u: EffectUniforms, input: EffectImage, background: EffectImage, cleanPlate: EffectImage? = null): EffectImage {
        require(u.flags and EffectUniforms.FLAG_TEMPORAL_MATTE == 0) { "The temporal matte is not referenced" }
        require(u.number("personHint") < .5) { "The person hint needs a person mask" }
        val pictures = chromaKeyPictures(u, input, background, cleanPlate)
        return EffectImage.of(input.width, input.height) { x, y -> chromaKeyPixel(u, input, pictures, x, y) }
    }

    // The time effects' pixels.

    /**
     * One pixel of Freeze Frame's or Undercranking's pass from its block [u]
     * ([EffectFrame.of] with the hold's clock for a freeze): the picture read
     * at the moved point, Print's look and grain, or the flicker. Metal:386-389.
     */
    fun timePixel(u: EffectUniforms, input: EffectImage, x: Int, y: Int): EffectVector {
        val uv = input.uv(x, y)
        val original = input.sample(uv)
        val out = when (u.effect) {
            VideoEffectID.FreezeFrame -> {
                var out = input.sample(freezeFramePoint(uv, u.time, u.number("hold"), u.number("push")))
                if (u.number("style") > .5) {
                    out = freezeFramePrint(out, uv)
                    out = VideoEffectGrain.apply(out, VideoEffectGrain.sampled(x, y, input.width, input.height, u.frame, FREEZE_PRINT_GRAIN_SIZE).xyz, FREEZE_PRINT_GRAIN)
                }
                out
            }
            VideoEffectID.Undercranking -> {
                val frame = VideoEffectTiming.undercrankingFrame(u.time, u.number("fps"))
                undercranking(input.sample(VideoEffectTiming.undercrankingPoint(uv, input.size, frame, u.number("weave"))), frame, u.number("flicker"))
            }
            else -> throw IllegalArgumentException("No time formula for ${u.effect}")
        }
        return VideoEffectFormulas.mixed(original, out, u.intensity)
    }

    fun timePass(u: EffectUniforms, input: EffectImage): EffectImage = EffectImage.of(input.width, input.height) { x, y -> timePixel(u, input, x, y) }
}
