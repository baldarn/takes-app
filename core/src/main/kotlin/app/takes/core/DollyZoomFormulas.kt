package app.takes.core

import kotlin.math.pow

/*
 * Dolly Zoom in the editor: the original's simulated two-layer dolly, the
 * subject kept as it is and the background behind it magnified about the
 * subject over the effect's duration. Ported from
 * YouRec/VideoEffects/VideoEffects.metal (cited as `Metal`) case 8 and
 * YouRec/VideoEffects/MetalVideoEffects.swift (`Renderer`). Conventions are
 * those of [VideoEffectFormulas].
 *
 * The original separates the subject by an imported depth map where the
 * recording has one, and by the person mask otherwise (Renderer:492-498,
 * :634); Android has no imported depth, so here it is always the person.
 */
object DollyZoomFormulas {
    private val math = VideoEffectShaderMath

    /** The edge softness the person's mask is refined with. Renderer:497. */
    const val SOFTNESS = 0.3

    /**
     * How far the move is, 0…1, at [time] seconds into the effect of
     * [duration] seconds: held at the end, through the in-out cubic unless
     * [linear]. Metal:367.
     */
    fun progress(time: Double, duration: Double, linear: Boolean = false): Double {
        val t = math.clamp(time / maxOf(.001, duration), 0.0, 1.0)
        if (linear) return t
        return if (t < .5) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2
    }

    /**
     * How much the background is magnified at progress [t]: Stretch grows
     * from 1 to 1 + [strength], Compress comes back from 1 + [strength] to 1.
     * Metal:368.
     */
    fun scale(t: Double, strength: Double, compress: Boolean = false): Double =
        if (!compress) 1 + strength * t else 1 + strength * (1 - t)

    /** [u]'s magnification at its clock: "direction", "strength", "duration" and "easing", slots 0…3. Metal:367-368. */
    fun scale(u: EffectUniforms): Double = scale(progress(u.time, u.p[2].x, linear = u.p[3].x >= .5), u.p[1].x, compress = u.p[0].x >= .5)

    /** Where the background is read for the pixel at [uv]: magnified by [scale] about [target]. Metal:369. */
    fun backgroundPoint(uv: Point, target: Point, scale: Double): Point =
        Point((uv.x - target.x) / scale + target.x, (uv.y - target.y) / scale + target.y)

    /** The pixel: the [background] read there under the subject [c] by its alpha [a]. Metal:369. */
    fun dollyZoom(c: EffectVector, background: EffectVector, a: Double): EffectVector = math.mix(background, c, a)

    /** What a Dolly Zoom pass reads besides its input: the subject's refined [mask] and the background [estimate] behind it, filled. */
    data class Pictures(val mask: EffectMask, val estimate: EffectImage)

    /**
     * The pictures from the raw [person] mask laid on the frame: the mask
     * refined on the picture with softness 0.3 as a person's, and the
     * background estimated behind it with the fill, so the hole the subject
     * leaves takes the nearest background. [lite] is the recorder's lite
     * quality. Renderer:492-499.
     */
    fun pictures(input: EffectImage, person: EffectMask, lite: Boolean = false): Pictures {
        val mask = MaskRefinement.refinedMask(person, input, SOFTNESS, lite = lite, person = true)
        return Pictures(mask, AutoBackgroundFormulas.backgroundEstimate(input, mask, fill = true))
    }

    /** One pixel of the pass from its pictures. The target is "targetX" and "targetY", slots 4 and 5. Metal:366-369. */
    fun pixel(u: EffectUniforms, input: EffectImage, pictures: Pictures, x: Int, y: Int, scale: Double = scale(u)): EffectVector {
        val uv = input.uv(x, y)
        val c = input.sample(uv)
        val background = pictures.estimate.sample(backgroundPoint(uv, Point(u.p[4].x, u.p[5].x), scale))
        return VideoEffectFormulas.mixed(c, dollyZoom(c, background, pictures.mask.sample(uv)), u.intensity)
    }

    /**
     * A whole Dolly Zoom pass from the frame's raw [person] mask. The
     * original draws none without the mask (Renderer:634); here, as for an
     * Auto Background, the picture is left as it is.
     */
    fun pass(u: EffectUniforms, input: EffectImage, person: EffectMask?, lite: Boolean = false): EffectImage {
        require(u.effect == VideoEffectID.DollyZoom)
        if (person == null) return input
        val pictures = pictures(input, person, lite)
        val scale = scale(u)
        return EffectImage.of(input.width, input.height) { x, y -> pixel(u, input, pictures, x, y, scale) }
    }
}
