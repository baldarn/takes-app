package app.takes.core

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor

/*
 * The passes the original draws before an effect's own pass: highlight
 * extraction, reduction to a smaller picture and the separable Gaussian, as
 * YouRec/VideoEffects/MetalVideoEffects.swift (cited as `Renderer`) sizes and
 * weights them and YouRec/VideoEffects/VideoEffects.metal (`Metal`) computes
 * them. They work on small pictures in memory, so a GLSL multi-pass shader can
 * be compared with them pixel by pixel.
 *
 * The original keeps these pictures in half floats and computes the weights
 * in single precision; these keep single-precision pictures, compute in
 * double precision and round nothing else.
 */

/**
 * A picture of linear colours in rows from the top, three numbers a pixel,
 * read as the shader reads a texture: between pixel centres, held at the
 * edges (`linearSampler`, Metal:14).
 */
class EffectImage(val width: Int, val height: Int, val data: FloatArray = FloatArray(width * height * 3)) {
    init {
        require(width > 0 && height > 0 && data.size == width * height * 3)
    }

    val size: Size get() = Size(width.toDouble(), height.toDouble())

    /** The pixel at whole indices, held at the edges. */
    operator fun get(x: Int, y: Int): EffectVector {
        val i = (y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)) * 3
        return EffectVector(data[i].toDouble(), data[i + 1].toDouble(), data[i + 2].toDouble())
    }

    operator fun set(x: Int, y: Int, c: EffectVector) {
        val i = (y * width + x) * 3
        data[i] = c.x.toFloat()
        data[i + 1] = c.y.toFloat()
        data[i + 2] = c.z.toFloat()
    }

    /** The centre of a pixel as a position. */
    fun uv(x: Int, y: Int): Point = Point((x + .5) / width, (y + .5) / height)

    /** The bilinear read at a position, 0…1 across the picture. */
    fun sample(uv: Point): EffectVector {
        val tx = uv.x * width - .5
        val ty = uv.y * height - .5
        val x0 = floor(tx).toInt()
        val y0 = floor(ty).toInt()
        val fx = tx - floor(tx)
        val fy = ty - floor(ty)
        val math = VideoEffectShaderMath
        val top = math.mix(this[x0, y0], this[x0 + 1, y0], fx)
        val bottom = math.mix(this[x0, y0 + 1], this[x0 + 1, y0 + 1], fx)
        return math.mix(top, bottom, fy)
    }

    fun toPicture(): EffectPicture = EffectPicture(width, height) { x, y -> this[x, y] }

    override fun equals(other: Any?): Boolean = other is EffectImage && other.width == width && other.height == height && other.data.contentEquals(data)
    override fun hashCode(): Int = 31 * (31 * width + height) + data.contentHashCode()

    companion object {
        fun of(width: Int, height: Int, pixel: (x: Int, y: Int) -> EffectVector): EffectImage =
            EffectImage(width, height).also { image -> for (y in 0 until height) for (x in 0 until width) image[x, y] = pixel(x, y) }

        fun of(picture: EffectPicture): EffectImage = of(picture.width, picture.height) { x, y -> picture[x, y] }

        fun flat(width: Int, height: Int, c: EffectVector): EffectImage = of(width, height) { _, _ -> c }
    }
}

/**
 * The weights of one Gaussian pass as the original sends them: the centre
 * tap's weight, then pairs of neighbouring taps each read with one bilinear
 * sample at [offsets] (in pixels, on both sides) with the pair's summed
 * weight. [radius] is how many pixels the kernel reaches on each side, and
 * [taps] the exact weight of each of those pixels, from the centre out.
 */
data class GaussianKernel(val sigma: Double, val radius: Int, val center: Double, val offsets: List<Double>, val weights: List<Double>, val taps: List<Double>)

/** What an effect's pass reads besides its input, by the shader's texture names. */
data class EffectAuxiliary(
    val aux: EffectImage? = null,
    val background: EffectImage? = null,
    val extra: EffectImage? = null,
)

object VideoEffectPasses {
    private val math = VideoEffectShaderMath

    /**
     * The Gaussian of a sigma in pixels: held at 0.25 at least, reaching
     * `ceil(3σ)` pixels and at most 48, normalised over every tap; adjacent
     * positive taps share one bilinear sample, which "halves reads and removes
     * exp per pixel". Renderer:308-326, Metal:419-421.
     */
    fun gaussianKernel(sigma: Double): GaussianKernel {
        val s = maxOf(0.25, sigma)
        val radius = minOf(48, ceil(s * 3).toInt())
        val raw = (0..radius).map { exp(-(it * it).toDouble() / (2 * s * s)) }
        val total = raw[0] + 2 * raw.drop(1).sum()
        val offsets = mutableListOf<Double>()
        val weights = mutableListOf<Double>()
        for (i in 1..radius step 2) {
            val a = raw[i]
            val b = if (i + 1 <= radius) raw[i + 1] else 0.0
            offsets += i + b / (a + b)
            weights += (a + b) / total
        }
        check(offsets.size == (radius + 1) / 2)
        return GaussianKernel(s, radius, raw[0] / total, offsets, weights, raw.map { it / total })
    }

    /**
     * One Gaussian pass, `ve_blur`: across when [horizontal], else down; the
     * output has the input's size. Metal:422-431.
     */
    fun gaussian(input: EffectImage, kernel: GaussianKernel, horizontal: Boolean): EffectImage {
        val dx = if (horizontal) 1.0 / input.width else 0.0
        val dy = if (horizontal) 0.0 else 1.0 / input.height
        return EffectImage.of(input.width, input.height) { x, y ->
            val uv = input.uv(x, y)
            var result = input.sample(uv) * kernel.center
            for (i in kernel.offsets.indices) {
                val o = kernel.offsets[i]
                result += (input.sample(Point(uv.x + dx * o, uv.y + dy * o)) + input.sample(Point(uv.x - dx * o, uv.y - dy * o))) * kernel.weights[i]
            }
            result
        }
    }

    /**
     * The same pass as a direct convolution, every tap read at its own pixel
     * and held at the edges: what [gaussian] must equal.
     */
    fun convolve(input: EffectImage, kernel: GaussianKernel, horizontal: Boolean): EffectImage = EffectImage.of(input.width, input.height) { x, y ->
        var result = input[x, y] * kernel.taps[0]
        for (i in 1..kernel.radius) {
            result += if (horizontal) (input[x + i, y] + input[x - i, y]) * kernel.taps[i] else (input[x, y + i] + input[x, y - i]) * kernel.taps[i]
        }
        result
    }

    /**
     * An area average to a smaller size, `ve_reduce`: each output pixel the
     * mean of the input under it, read at the centres of a grid of
     * `ceil(ratio)` points per axis. When the ratio is whole and even the
     * shader reads the corners between four pixels instead, the same mean.
     * Metal:507-520.
     */
    fun reduce(input: EffectImage, width: Int, height: Int): EffectImage {
        val rx = input.width.toDouble() / width
        val ry = input.height.toDouble() / height
        val cx = maxOf(1.0, ceil(rx)).toInt()
        val cy = maxOf(1.0, ceil(ry)).toInt()
        val paired = rx == floor(rx) && ry == floor(ry) && cx % 2 == 0 && cy % 2 == 0
        return EffectImage.of(width, height) { qx, qy ->
            var total = EffectVector.ZERO
            if (paired) {
                for (y in 0 until cy step 2) for (x in 0 until cx step 2) {
                    total += input.sample(Point((qx + (x + 1.0) / cx) / width, (qy + (y + 1.0) / cy) / height))
                }
                total *= 4.0
            } else {
                for (y in 0 until cy) for (x in 0 until cx) {
                    total += input.sample(Point((qx + (x + .5) / cx) / width, (qy + (y + .5) / cy) / height))
                }
            }
            total / (cx * cy).toDouble()
        }
    }

    /**
     * The bright parts of a picture at a smaller size, `ve_highlights`: every
     * input pixel under an output pixel weighted by how far its luma is over
     * [threshold], `smoothstep(threshold − softness, threshold + softness, luma)`,
     * before the area average, so "tiny moving practicals must not disappear
     * between quarter-resolution sample centers". A whole ratio reads the
     * pixels themselves, another one the bilinear sample. Metal:438-453.
     */
    fun highlights(input: EffectImage, width: Int, height: Int, threshold: Double, softness: Double): EffectImage {
        val rx = input.width.toDouble() / width
        val ry = input.height.toDouble() / height
        val sx = maxOf(1, ceil(rx).toInt())
        val sy = maxOf(1, ceil(ry).toInt())
        val integral = rx == floor(rx) && ry == floor(ry)
        return EffectImage.of(width, height) { qx, qy ->
            var sum = EffectVector.ZERO
            var n = 0
            for (y in 0 until sy) for (x in 0 until sx) {
                val c = if (integral) {
                    input[qx * sx + x, qy * sy + y]
                } else {
                    input.sample(Point((qx + (x + .5) / sx) / width, (qy + (y + .5) / sy) / height))
                }
                sum += c * math.smoothstep(threshold - softness, threshold + softness, math.luma(c))
                n++
            }
            sum / maxOf(n, 1).toDouble()
        }
    }

    /** The sizes and the sigma a [blur] works at. */
    data class BlurPlan(val width: Int, val height: Int, val reduced: Boolean, val kernel: GaussianKernel)

    /**
     * How the original's blur of [sigma] input pixels, to a picture of
     * [width] × [height] (by default the input's), is drawn: it works at a
     * smaller size still when the sigma there would pass 14, and scales the
     * sigma to the size it works at. Renderer:292-307.
     */
    fun blurPlan(inputWidth: Int, inputHeight: Int, sigma: Double, width: Int = inputWidth, height: Int = inputHeight): BlurPlan {
        val downscale = minOf(1.0, 14 / maxOf(0.25, sigma * width / inputWidth))
        val w = maxOf(2, (width * downscale).toInt())
        val h = maxOf(2, (height * downscale).toInt())
        return BlurPlan(w, h, w < inputWidth || h < inputHeight, gaussianKernel(sigma * w / inputWidth))
    }

    /**
     * The original's blur: [reduce] when the plan is smaller than the input,
     * then a pass across and one down; with [horizontalOnly] the second pass
     * goes across again, so the flares spread only sideways (at √2 σ).
     * Renderer:292-307.
     */
    fun blur(input: EffectImage, sigma: Double, width: Int = input.width, height: Int = input.height, horizontalOnly: Boolean = false): EffectImage {
        val plan = blurPlan(input.width, input.height, sigma, width, height)
        val source = if (plan.reduced) reduce(input, plan.width, plan.height) else input
        return gaussian(gaussian(source, plan.kernel, true), plan.kernel, horizontalOnly)
    }

    /** A picture's shorter side over 1080: what spatial parameters scale by. Renderer:434. */
    fun pixelScale(width: Int, height: Int): Double = minOf(width, height) / 1080.0

    /** A quarter of a size, at least two pixels. Renderer:470. */
    fun quarter(n: Int): Int = maxOf(2, n / 4)

    /** Half a size, at least two pixels: the lite quality's blurs. Renderer:460-465. */
    fun half(n: Int): Int = maxOf(2, n / 2)

    /**
     * Halation's three blurs of its highlights at a quarter of the size:
     * [EffectAuxiliary.aux] the glow (1 % of the width), `background` the
     * halo whose red fringes (0.6 %), `extra` the wide glow (4 %). [lite]
     * halves the sigmas, as the original's lite quality. Renderer:468-482.
     */
    fun halation(input: EffectImage, lite: Boolean = false): EffectAuxiliary {
        val small = highlights(input, quarter(input.width), quarter(input.height), .75, .1)
        val detail = if (lite) .5 else 1.0
        val w = input.width.toDouble()
        return EffectAuxiliary(
            aux = blur(small, w * 0.01 / 4 * detail),
            background = blur(small, w * 0.006 / 4 * detail),
            extra = blur(small, w * 0.04 / 4 * detail),
        )
    }

    /**
     * Widescreen's flares, [EffectAuxiliary.aux]: its highlights over 0.9 at a
     * quarter of the size, blurred only across by a 24th of the width; with
     * [blurFill] also `background`, the picture blurred by 12 (at 1080) at a
     * quarter of the size, for the bars. Renderer:468-474.
     */
    fun widescreen(input: EffectImage, blurFill: Boolean, lite: Boolean = false): EffectAuxiliary {
        val small = highlights(input, quarter(input.width), quarter(input.height), .9, .05)
        val detail = if (lite) .5 else 1.0
        val flares = blur(small, input.width.toDouble() / 24 / 4 * detail, horizontalOnly = true)
        val background = if (blurFill) {
            blur(input, 12 * pixelScale(input.width, input.height), quarter(input.width), quarter(input.height))
        } else {
            null
        }
        return EffectAuxiliary(aux = flares, background = background)
    }

    /** 16mm's softness, [EffectAuxiliary.aux]: the picture blurred by 1.5 (at 1080), drawn only while the softness is above zero. Renderer:460-463. */
    fun sixteenMM(input: EffectImage, softness: Double, lite: Boolean = false): EffectAuxiliary {
        if (softness <= 0) return EffectAuxiliary()
        val sigma = 1.5 * pixelScale(input.width, input.height)
        return EffectAuxiliary(aux = if (lite) blur(input, sigma, half(input.width), half(input.height)) else blur(input, sigma))
    }

    /** Super 8's softness, [EffectAuxiliary.aux]: the picture blurred by 2 (at 1080), always. Renderer:464-466. */
    fun superEight(input: EffectImage, lite: Boolean = false): EffectAuxiliary {
        val sigma = 2 * pixelScale(input.width, input.height)
        return EffectAuxiliary(aux = if (lite) blur(input, sigma, half(input.width), half(input.height)) else blur(input, sigma))
    }
}
