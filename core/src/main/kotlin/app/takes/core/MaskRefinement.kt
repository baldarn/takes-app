package app.takes.core

import kotlin.math.floor

/*
 * The original's refinement of a mask before an effect reads it, `refinedMask`:
 * a guided filter that snaps the mask to the edges of the picture, the
 * clipping of a person's low-confidence skirts, and an optional grow or
 * shrink. Ported from YouRec/VideoEffects/MetalVideoEffects.swift:382-402
 * (cited as `Renderer`) and the kernels `ve_guidedStats`, `ve_guidedApply`,
 * `ve_personCoverage` and `ve_morphology` of YouRec/VideoEffects/VideoEffects.metal
 * (`Metal`, :454-490).
 *
 * The original keeps the intermediate pictures in half floats (`rg16Float`
 * coefficients, `r16Float` masks); these keep single-precision values and
 * compute in double precision.
 */

/**
 * A picture of one number a pixel, 0…1, in rows from the top: a person mask,
 * a matte or a guide's luma. It is read as the shader reads an `r16Float`
 * texture: between pixel centres, held at the edges (`linearSampler`, Metal:14).
 */
class EffectMask(val width: Int, val height: Int, val data: FloatArray = FloatArray(width * height)) {
    init {
        require(width > 0 && height > 0 && data.size == width * height)
    }

    val size: Size get() = Size(width.toDouble(), height.toDouble())

    /** The value at whole indices, held at the edges. */
    operator fun get(x: Int, y: Int): Double = data[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)].toDouble()

    operator fun set(x: Int, y: Int, value: Double) {
        data[y * width + x] = value.toFloat()
    }

    /** The centre of a pixel as a position. */
    fun uv(x: Int, y: Int): Point = Point((x + .5) / width, (y + .5) / height)

    /** The bilinear read at a position, 0…1 across the picture. */
    fun sample(uv: Point): Double {
        val tx = uv.x * width - .5
        val ty = uv.y * height - .5
        val x0 = floor(tx).toInt()
        val y0 = floor(ty).toInt()
        val fx = tx - floor(tx)
        val fy = ty - floor(ty)
        val m = VideoEffectShaderMath
        return m.mix(m.mix(this[x0, y0], this[x0 + 1, y0], fx), m.mix(this[x0, y0 + 1], this[x0 + 1, y0 + 1], fx), fy)
    }

    /** The mask as a picture with its value in every channel, as the original's single-channel textures are read as colours. */
    fun toImage(): EffectImage = EffectImage.of(width, height) { x, y -> EffectVector(this[x, y]) }

    override fun equals(other: Any?): Boolean = other is EffectMask && other.width == width && other.height == height && other.data.contentEquals(data)
    override fun hashCode(): Int = 31 * (31 * width + height) + data.contentHashCode()

    companion object {
        fun of(width: Int, height: Int, value: (x: Int, y: Int) -> Double): EffectMask =
            EffectMask(width, height).also { mask -> for (y in 0 until height) for (x in 0 until width) mask[x, y] = value(x, y) }

        fun flat(width: Int, height: Int, value: Double): EffectMask = of(width, height) { _, _ -> value }

        /** The first channel of a picture, `.r` as the shader reads a mask. */
        fun of(image: EffectImage): EffectMask = of(image.width, image.height) { x, y -> image[x, y].x }

        /** The linear luma of a picture: a guide given as luma reads the same as the picture, since a bilinear read and the luma are both linear. */
        fun luma(image: EffectImage): EffectMask = of(image.width, image.height) { x, y -> VideoEffectShaderMath.luma(image[x, y]) }

        fun luma(picture: EffectPicture): EffectMask = of(picture.width, picture.height) { x, y -> VideoEffectShaderMath.luma(picture[x, y]) }
    }
}

object MaskRefinement {
    private val math = VideoEffectShaderMath

    /**
     * How a refinement of a mask on a guide of [guideWidth] × [guideHeight]
     * is drawn: the guided filter's coefficients at [width] × [height] (the
     * guide brought within 1024 pixels on its longer side for a person, 384
     * at the lite quality; 512 and 256 for other masks), over a window of
     * [radius] pixels on each side there (at most 6 in the shader,
     * [GUIDED_RADIUS_LIMIT]), blurred by [sigma] of those pixels; and for a
     * person the coverage clip between [black] and [white]. Renderer:382-397.
     */
    data class Plan(val width: Int, val height: Int, val scale: Double, val radius: Int, val sigma: Double, val black: Double, val white: Double)

    /** The shader's bound on the guided filter's window. Metal:456. */
    const val GUIDED_RADIUS_LIMIT = 6

    fun plan(guideWidth: Int, guideHeight: Int, softness: Double, lite: Boolean = false, person: Boolean = false): Plan {
        // Person boundaries must not acquire the wide feather used for glow or
        // coarse depth. Keep accurate masks detailed and bound coefficient blur. Renderer:383-384.
        val maximum = if (person) (if (lite) 384.0 else 1024.0) else (if (lite) 256.0 else 512.0)
        val scale = minOf(1.0, maximum / maxOf(guideWidth, guideHeight))
        val width = maxOf(2, (guideWidth * scale).toInt())
        val height = maxOf(2, (guideHeight * scale).toInt())
        val radius = maxOf(1, ((2 + 6 * softness) * minOf(guideWidth, guideHeight) / 1080 * scale).toInt())
        val sigma = if (person) .25 + .75 * softness else 1 + softness
        // Remove low-confidence skirts without moving the 50% contour. Renderer:395.
        val black = .02 + .05 * (1 - softness)
        return Plan(width, height, scale, radius, sigma, black, 1 - black)
    }

    /** The perceptual value the guided filter works on: the sRGB encoding of the guide's linear luma. Metal:457, :461. */
    fun perceptual(luma: Double): Double = math.srgb(luma)

    /**
     * The guided filter's coefficients, `ve_guidedStats`: for each pixel of a
     * picture of [width] × [height], over the square of `2r + 1` pixels about
     * it (r the [radius] held at 6), the means of the guide's perceptual luma
     * `i`, of the mask `p`, of `i²` and of `i·p`, all read between pixels of
     * the guide and the mask; then `a = cov(i, p) / (var(i) + 0.001)` and
     * `b = mean(p) − a · mean(i)`, in the picture's first two channels.
     * Metal:454-459.
     */
    fun guidedStats(guide: EffectMask, mask: EffectMask, width: Int, height: Int, radius: Int): EffectImage {
        val r = minOf(radius, GUIDED_RADIUS_LIMIT)
        return EffectImage.of(width, height) { qx, qy ->
            var si = 0.0
            var sp = 0.0
            var sii = 0.0
            var sip = 0.0
            var n = 0.0
            for (y in -r..r) for (x in -r..r) {
                val uv = Point((qx + x + .5) / width, (qy + y + .5) / height)
                val i = perceptual(guide.sample(uv))
                val p = mask.sample(uv)
                si += i
                sp += p
                sii += i * i
                sip += i * p
                n++
            }
            si /= n
            sp /= n
            sii /= n
            sip /= n
            val a = (sip - si * sp) / (maxOf(0.0, sii - si * si) + .001)
            EffectVector(a, sp - a * si, 0.0)
        }
    }

    /** The refined mask at the guide's size, `ve_guidedApply`: `clamp(a · i + b, 0, 1)` with the coefficients read between their pixels. Metal:460-462. */
    fun guidedApply(guide: EffectMask, coefficients: EffectImage): EffectMask = EffectMask.of(guide.width, guide.height) { x, y ->
        val uv = guide.uv(x, y)
        val ab = coefficients.sample(uv)
        math.clamp(ab.x * perceptual(guide.sample(uv)) + ab.y, 0.0, 1.0)
    }

    /** A person's coverage, `ve_personCoverage`: `smoothstep(black, white, m)`, so the skirts below [black] go and the 50 % contour stays. Metal:463-467. */
    fun personCoverage(mask: EffectMask, black: Double, white: Double): EffectMask =
        EffectMask.of(mask.width, mask.height) { x, y -> math.smoothstep(black, white, mask[x, y]) }

    /**
     * One pass of a grow or shrink, `ve_morphology`, to a picture of [width] ×
     * [height]: the largest (growing, [radius] ≥ 0) or smallest value of
     * [input] read between its pixels along a line of whole output pixels up
     * to the radius's floor and at the radius itself; at most 64. Metal:481-490.
     */
    fun morphology(input: EffectMask, radius: Double, horizontal: Boolean, width: Int = input.width, height: Int = input.height): EffectMask =
        EffectMask.of(VideoEffectFormulasC.morphology(input.toImage(), radius, horizontal, width, height))

    /** A grow or shrink by [radius] output pixels to [width] × [height]: a pass across, then one down. Renderer:328-333. */
    fun morph(input: EffectMask, radius: Double, width: Int = input.width, height: Int = input.height): EffectMask =
        morphology(morphology(input, radius, true, width, height), radius, false, width, height)

    /**
     * The original's `refinedMask` of [mask] on the picture [guide] (given as
     * its linear luma, [EffectMask.luma]): the guided filter's coefficients at
     * the [plan]'s size, blurred, applied at the guide's size; for a [person]
     * the coverage clip; then grown or shrunk by [grow] pixels at 1080.
     * [softness] is the effect's edge softness, 0…1. Renderer:382-402.
     */
    fun refinedMask(mask: EffectMask, guide: EffectMask, softness: Double, grow: Double = 0.0, lite: Boolean = false, person: Boolean = false): EffectMask {
        val plan = plan(guide.width, guide.height, softness, lite, person)
        val stats = guidedStats(guide, mask, plan.width, plan.height, plan.radius)
        val smooth = VideoEffectPasses.blur(stats, plan.sigma)
        var refined = guidedApply(guide, smooth)
        if (person) refined = personCoverage(refined, plan.black, plan.white)
        if (grow != 0.0) return morph(refined, grow * minOf(guide.width, guide.height) / 1080)
        return refined
    }

    /** [refinedMask] on a picture as the guide. */
    fun refinedMask(mask: EffectMask, guide: EffectImage, softness: Double, grow: Double = 0.0, lite: Boolean = false, person: Boolean = false): EffectMask =
        refinedMask(mask, EffectMask.luma(guide), softness, grow, lite, person)
}
