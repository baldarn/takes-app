package app.takes.core

import kotlin.math.pow
import kotlin.math.sqrt

/*
 * Auto Background: the estimate of the background behind the person, the
 * keyed pixel, the light wrap, match colour, and the backgrounds the original
 * draws, Studio and Window among them. Ported from
 * YouRec/VideoEffects/MetalVideoEffects.swift (cited as `Renderer`), the
 * kernels and branch of YouRec/VideoEffects/VideoEffects.metal (`Metal`) and
 * YouRec/VideoEffects/VideoEffectAssets.swift (`Assets`). Conventions are
 * those of [VideoEffectFormulas]; the original's half-float pictures are
 * single precision here.
 *
 * The original's four-channel pictures (colour weighted by a coverage, and
 * the coverage) are kept here as two pictures, colour and coverage, read and
 * blurred alike: every pass on them is linear and channel by channel.
 */

/**
 * What match colour compares, each a mean over the frame: the subject's
 * colour weighted by its coverage [subject] and that coverage [coverage]
 * (`matchSubject`, rgb and alpha), and the new background's colour
 * [background] (`matchBackground`). Renderer:536-541.
 */
data class MatchColorReference(val subject: EffectVector, val coverage: Double, val background: EffectVector)

/** A picture of colour weighted by a coverage, and the coverage: a four-channel texture of the original. */
data class WeightedImage(val color: EffectImage, val weight: EffectImage) {
    init {
        require(color.width == weight.width && color.height == weight.height)
    }

    val width: Int get() = color.width
    val height: Int get() = color.height
}

object AutoBackgroundFormulas {
    private val math = VideoEffectShaderMath

    // The background estimate. Renderer:334-353, Metal:491-506.

    /**
     * The known background at a quarter of the size (at least 2 × 2),
     * `ve_bgWeight`: the picture read between its pixels at each pixel's
     * centre, weighted by `1 − mask`, and that weight. Metal:491-497.
     */
    fun backgroundWeight(input: EffectImage, mask: EffectMask): WeightedImage {
        val w = VideoEffectPasses.quarter(input.width)
        val h = VideoEffectPasses.quarter(input.height)
        val weight = EffectImage.of(w, h) { x, y -> EffectVector(1 - mask.sample(Point((x + .5) / w, (y + .5) / h))) }
        val color = EffectImage.of(w, h) { x, y -> input.sample(Point((x + .5) / w, (y + .5) / h)) * weight[x, y].x }
        return WeightedImage(color, weight)
    }

    /** [VideoEffectPasses.blur] of both pictures alike. */
    fun blur(image: WeightedImage, sigma: Double, width: Int = image.width, height: Int = image.height): WeightedImage =
        WeightedImage(VideoEffectPasses.blur(image.color, sigma, width, height), VideoEffectPasses.blur(image.weight, sigma, width, height))

    /**
     * The local background colour, `ve_bgNormalize`, at [weighted]'s size:
     * the weighted colour over its weight where the weight passes 0.001,
     * else [fallback] under the pixel. Metal:498-503.
     */
    fun normalize(weighted: WeightedImage, fallback: EffectImage): EffectImage = EffectImage.of(weighted.width, weighted.height) { x, y ->
        val uv = Point((x + .5) / weighted.width, (y + .5) / weighted.height)
        val a = weighted.weight.sample(uv).x
        if (a > .001) weighted.color.sample(uv) / a else fallback.sample(uv)
    }

    /**
     * The original's `backgroundEstimate`: the known background blurred by
     * 4 pixels at 1080 (of the full picture) at a quarter of the size; without
     * [fill], normalised against the picture itself. With [fill] (Dolly Zoom)
     * a pyramid of blurs of σ 2, each half the size of the one before, down
     * to 2 × 2, normalised from the smallest up, so a hole takes the colour of
     * the nearest known background. Renderer:334-353.
     */
    fun backgroundEstimate(input: EffectImage, mask: EffectMask, fill: Boolean = false): EffectImage {
        val weighted = backgroundWeight(input, mask)
        val local = blur(weighted, 4.0 * minOf(input.width, input.height) / 1080)
        if (!fill) return normalize(local, input)
        val pyramid = mutableListOf(local)
        var last = local
        while (last.width > 2 || last.height > 2) {
            last = blur(last, 2.0, maxOf(2, last.width / 2), maxOf(2, last.height / 2))
            pyramid += last
        }
        var fallback = input
        for (level in pyramid.reversed()) fallback = normalize(level, fallback)
        return fallback
    }

    // Match colour. Renderer:354-361, :536-541; Metal:409-413, :548-552.

    /** The size the subject's weighted colour is drawn at before it is averaged. Renderer:537. */
    const val MATCH_WIDTH = 64
    const val MATCH_HEIGHT = 36

    /**
     * The subject's colour weighted by how surely it is the subject,
     * `ve_subjectWeight`, at 64 × 36: the picture read at each pixel's centre
     * times `smoothstep(0.5, 0.95, mask)`, and that weight. Metal:548-552.
     */
    fun subjectWeight(input: EffectImage, mask: EffectMask): WeightedImage {
        val weight = EffectImage.of(MATCH_WIDTH, MATCH_HEIGHT) { x, y ->
            EffectVector(math.smoothstep(.5, .95, mask.sample(Point((x + .5) / MATCH_WIDTH, (y + .5) / MATCH_HEIGHT))))
        }
        val color = EffectImage.of(MATCH_WIDTH, MATCH_HEIGHT) { x, y ->
            input.sample(Point((x + .5) / MATCH_WIDTH, (y + .5) / MATCH_HEIGHT)) * weight[x, y].x
        }
        return WeightedImage(color, weight)
    }

    /** The mean of a picture, `average`: [VideoEffectPasses.reduce] to a quarter of the size, rounded up, until one pixel is left. Renderer:354-361. */
    fun average(input: EffectImage): EffectVector {
        var image = input
        while (image.width > 1 || image.height > 1) image = VideoEffectPasses.reduce(image, maxOf(1, (image.width + 3) / 4), maxOf(1, (image.height + 3) / 4))
        return image[0, 0]
    }

    /** What match colour compares on a frame: the subject under [mask] in [input], and the [background]. Renderer:536-541. */
    fun matchReference(input: EffectImage, mask: EffectMask, background: EffectImage): MatchColorReference {
        val weighted = subjectWeight(input, mask)
        return MatchColorReference(average(weighted.color), average(weighted.weight).x, average(background))
    }

    /**
     * The straight colour of the subject brought towards the background's
     * light: times `mix(1, clamp(background / max(mean, 0.01), 0.5, 2),
     * strength)`, the mean being the subject's colour over its coverage; no
     * change when the coverage is under 0.001. Metal:409-413.
     */
    fun matchColor(straight: EffectVector, reference: MatchColorReference, strength: Double): EffectVector {
        if (!(reference.coverage > .001)) return straight
        val mean = reference.subject / maxOf(.001, reference.coverage)
        val ratio = EffectVector(
            math.clamp(reference.background.x / maxOf(mean.x, .01), .5, 2.0),
            math.clamp(reference.background.y / maxOf(mean.y, .01), .5, 2.0),
            math.clamp(reference.background.z / maxOf(mean.z, .01), .5, 2.0),
        )
        return straight * math.mix(EffectVector(1.0), ratio, strength)
    }

    // The keyed pixel. Metal:400-416, case 19.

    /**
     * An Auto Background pixel, `ve_effect` case 19: the straight colour
     * unmixed from the estimated background ([ChromaKeyInputs.screen]) under
     * alpha [a] and held inside 0…1 ("dividing by a tiny alpha must not create
     * a bright opaque fringe"), matched in colour when [match] is given, laid
     * over the new background, and the light wrap added on the matte's edge,
     * `a(1 − a) · 4 · wrap · 0.25`. [view] 1 shows the matte, 2 the original.
     */
    fun autoBackground(
        c: EffectVector,
        a: Double,
        inputs: ChromaKeyInputs,
        wrap: Double = 0.25,
        view: Int = 0,
        match: MatchColorReference? = null,
        matchStrength: Double = 0.3,
    ): EffectVector {
        var straight = ((c - inputs.screen * (1 - a)) / maxOf(a, .0001)).clamped()
        if (match != null) straight = matchColor(straight, match, matchStrength)
        var out = straight * a + inputs.background * (1 - a)
        out += inputs.wrap * (a * (1 - a) * 4 * wrap * .25)
        return when (view) {
            1 -> EffectVector(a)
            2 -> c
            else -> out
        }
    }

    // The pictures of a pass. Renderer:500-505, :536-541.

    /**
     * The light wrap's picture, `wrapImage`: with some wrap on a background
     * that is not Solid, the background blurred by 2 % of the width at a
     * quarter of the size; otherwise the background itself. Renderer:503-505, :517-519.
     */
    fun wrapImage(u: EffectUniforms, input: EffectImage, background: EffectImage): EffectImage =
        if (u.number("wrap") > 0 && u.number("background").toInt() != BACKGROUND_SOLID) {
            VideoEffectPasses.blur(background, input.width * 0.02, VideoEffectPasses.quarter(input.width), VideoEffectPasses.quarter(input.height))
        } else {
            background
        }

    /** What an Auto Background pass reads besides its input. */
    data class Pictures(
        val mask: EffectMask,
        val estimate: EffectImage,
        val background: EffectImage,
        val wrap: EffectImage,
        val match: MatchColorReference?,
    )

    /**
     * Auto Background's pictures for one frame from the raw [person] mask and
     * the new [background] (a picture of the frame's size, or 1 × 1 for a
     * solid colour, as [background] makes it): the person refined with the
     * effect's softness and grow, the background estimate, the light wrap
     * and, with "matchColor" on, match colour's reference. [lite] is the
     * recorder's lite quality. Renderer:500-505, :536-541.
     */
    fun pictures(u: EffectUniforms, input: EffectImage, person: EffectMask, background: EffectImage, lite: Boolean = false): Pictures {
        require(u.effect == VideoEffectID.AutoBackground)
        val mask = MaskRefinement.refinedMask(person, input, u.number("softness"), u.number("grow"), lite, person = true)
        val match = if (u.number("matchColor") > .5) matchReference(input, mask, background) else null
        return Pictures(mask, backgroundEstimate(input, mask), background, wrapImage(u, input, background), match)
    }

    /** One pixel of the pass from its pictures, mixed with the input by the intensity. */
    fun pixel(u: EffectUniforms, input: EffectImage, pictures: Pictures, x: Int, y: Int): EffectVector {
        val uv = input.uv(x, y)
        val c = input.sample(uv)
        val out = autoBackground(
            c, pictures.mask.sample(uv), ChromaKeyInputs(pictures.background.sample(uv), pictures.estimate.sample(uv), pictures.wrap.sample(uv)),
            u.number("wrap"), u.number("view").toInt(), pictures.match, u.number("matchStrength"),
        )
        return VideoEffectFormulas.mixed(c, out, u.intensity)
    }

    /** A whole Auto Background pass. The original draws none without a person mask (Renderer:634). */
    fun pass(u: EffectUniforms, input: EffectImage, person: EffectMask, background: EffectImage, lite: Boolean = false): EffectImage {
        val pictures = pictures(u, input, person, background, lite)
        return EffectImage.of(input.width, input.height) { x, y -> pixel(u, input, pictures, x, y) }
    }

    // Backgrounds. Assets:587-634.

    /** The indices of the "background" choice. VideoEffectCatalog.kt:43. */
    const val BACKGROUND_SOLID = 0
    const val BACKGROUND_IMAGE = 1
    const val BACKGROUND_VIDEO = 2
    const val BACKGROUND_BLUR_ORIGINAL = 3
    const val BACKGROUND_STUDIO = 4
    const val BACKGROUND_WINDOW = 5

    /** Studio's light, near its centre, and the dark it falls to; Window's panes. sRGB, as Core Image's colours. Assets:607-612. */
    val STUDIO_LIGHT = EffectRGB(0.65, 0.58, 0.48)
    val STUDIO_DARK = EffectRGB(0.08, 0.10, 0.14)
    val WINDOW_PANE = EffectRGB(0.62, 0.73, 0.83)

    /**
     * The authored scenes, "no licensed stock photographs", before their blur
     * and brightness: Studio a radial light from [STUDIO_LIGHT] at 30 % across
     * and 30 % down to [STUDIO_DARK] at 80 % of the width from there,
     * interpolated in linear light; Window the same with two panes, 10 % of
     * the width wide at 64 % and 80 % across, from 17 % to 82 % down. A pixel
     * is a pane's when its centre is inside it. Assets:604-613.
     */
    fun studioBackground(width: Int, height: Int, window: Boolean = false): EffectImage {
        val light = EffectColorMath.linear(STUDIO_LIGHT)
        val dark = EffectColorMath.linear(STUDIO_DARK)
        val pane = EffectColorMath.linear(WINDOW_PANE)
        val w = width.toDouble()
        val h = height.toDouble()
        return EffectImage.of(width, height) { px, py ->
            // Core Image's coordinates rise from the bottom.
            val x = px + .5
            val y = h - (py + .5)
            val inPane = window && y >= h * .18 && y < h * .18 + h * .65 &&
                listOf(.64, .80).any { left -> x >= w * left && x < w * left + w * .1 }
            if (inPane) {
                pane
            } else {
                val t = math.clamp(sqrt((x - w * .3).pow(2) + (y - h * .7).pow(2)) / (w * .8), 0.0, 1.0)
                math.mix(light, dark, t)
            }
        }
    }

    /**
     * The new background of a Chroma Key or an Auto Background on a frame,
     * as the original draws it: Solid a 1 × 1 picture of the colour; Image
     * and Video the [media] picture (the still, or the movie's frame) placed
     * by [VideoEffectFormulasC.imageBackground], or without one the solid
     * colour over the whole frame; Blur original the frame blurred by 20
     * pixels at 1080, or by "portraitBlur" for an Auto Background; Studio and
     * Window [studioBackground]. Each is then blurred by "backgroundBlur" and
     * brought up or down by "backgroundBrightness" stops. Assets:587-627.
     */
    fun background(u: EffectUniforms, input: EffectImage, media: EffectImage? = null): EffectImage {
        require(u.effect == VideoEffectID.AutoBackground || u.effect == VideoEffectID.ChromaKey)
        val blur = u.number("backgroundBlur")
        val brightness = u.number("backgroundBrightness")
        val color = u.vector("backgroundColor").xyz
        val gain = 2.0.pow(brightness)
        return when (val mode = u.number("background").toInt()) {
            BACKGROUND_IMAGE, BACKGROUND_VIDEO ->
                if (media != null) {
                    VideoEffectFormulasC.imageBackground(
                        media, input.width, input.height, u.number("fit").toInt() == 0, u.number("backgroundX"), u.number("backgroundY"),
                        u.number("backgroundZoom"), blur, brightness,
                    )
                } else {
                    EffectImage.flat(input.width, input.height, color * gain)
                }
            BACKGROUND_BLUR_ORIGINAL -> VideoEffectFormulasC.blurOriginalBackground(
                input, blur, brightness, if (u.effect == VideoEffectID.AutoBackground) u.number("portraitBlur") else 20.0,
            )
            BACKGROUND_STUDIO, BACKGROUND_WINDOW -> {
                var scene = studioBackground(input.width, input.height, mode == BACKGROUND_WINDOW)
                if (blur > 0) scene = VideoEffectPasses.blur(scene, blur * VideoEffectPasses.pixelScale(input.width, input.height))
                EffectImage.of(input.width, input.height) { x, y -> scene[x, y] * gain }
            }
            // "A constant color remains constant after blur/exposure." Assets:621-623.
            else -> EffectImage.flat(1, 1, color * gain)
        }
    }
}
