package app.takes.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The reference formulas of the first batch of video effects: each branch of
 * the original's shader, YouRec/VideoEffects/VideoEffects.metal (cited below
 * as `Metal`), written as pure functions with the same constants and the same
 * order of operations. They are what a GLSL shader of this app is compared
 * with, pixel by pixel.
 *
 * All colours here are what the shader works on: display-referred linear
 * BT.709, each channel 0…1 (Metal:3-4). Positions are `uv`, 0…1 across the
 * picture from its top left corner, a pixel's centre being
 * `(index + 0.5) / size` (Metal:306). The shader computes in single precision
 * on half-float pictures; these compute in double precision and round nothing.
 */

/** The shader's helper functions. Metal:13-39, :154-157. */
object VideoEffectShaderMath {
    val LUMA = EffectVector(0.2126, 0.7152, 0.0722)

    fun luma(c: EffectVector): Double = c.dot(LUMA)

    /** Linear to sRGB. Above the knee the base is held at zero, as the shader's `max(c,0.0)`. */
    fun srgb(x: Double): Double = if (x > 0.0031308) 1.055 * maxOf(x, 0.0).pow(1.0 / 2.4) - 0.055 else x * 12.92
    fun srgb(c: EffectVector): EffectVector = c.map(::srgb)

    /** sRGB to linear. */
    fun lin(x: Double): Double = if (x > 0.04045) ((x + 0.055) / 1.055).pow(2.4) else x / 12.92
    fun lin(c: EffectVector): EffectVector = c.map(::lin)

    /** Linear RGB to Oklab; the cube root is `pow(max(·, 0), 1/3)` as in the shader. */
    fun lab(c: EffectVector): EffectVector {
        val v = EffectVector(
            c.dot(EffectVector(.4122214708, .5363325363, .0514459929)),
            c.dot(EffectVector(.2119034982, .6806995451, .1073969566)),
            c.dot(EffectVector(.0883024619, .2817188376, .6299787005)),
        ).map { maxOf(it, 0.0).pow(1.0 / 3.0) }
        return EffectVector(
            v.dot(EffectVector(.2104542553, .793617785, -.0040720468)),
            v.dot(EffectVector(1.9779984951, -2.428592205, .4505937099)),
            v.dot(EffectVector(.0259040371, .7827717662, -.808675766)),
        )
    }

    /** Oklab to linear RGB. */
    fun unlab(c: EffectVector): EffectVector {
        val v = EffectVector(
            c.x + .3963377774 * c.y + .2158037573 * c.z,
            c.x - .1055613458 * c.y - .0638541728 * c.z,
            c.x - .0894841775 * c.y - 1.291485548 * c.z,
        ).map { it * it * it }
        return EffectVector(
            v.dot(EffectVector(4.0767416621, -3.3077115913, .2309699292)),
            v.dot(EffectVector(-1.2684380046, 2.6097574011, -.3413193965)),
            v.dot(EffectVector(-.0041960863, -.7034186147, 1.707614701)),
        )
    }

    /** Hue as a fraction of the turn, saturation and value. */
    fun hsv(c: EffectVector): EffectVector {
        val hi = maxOf(c.x, maxOf(c.y, c.z))
        val lo = minOf(c.x, minOf(c.y, c.z))
        val d = hi - lo
        var h = 0.0
        if (d > 1e-6) {
            h = if (hi == c.x) ((c.y - c.z) / d + 6.0).rem(6.0) else if (hi == c.y) (c.z - c.x) / d + 2 else (c.x - c.y) / d + 4
        }
        return EffectVector(h / 6, if (hi > 1e-6) d / hi else 0.0, hi)
    }

    /** The shorter way between two hues, each a fraction of the turn. */
    fun hueDistance(a: Double, b: Double): Double = abs(a - b).let { minOf(it, 1 - it) }

    fun clamp(x: Double, low: Double, high: Double): Double = minOf(maxOf(x, low), high)
    fun mix(a: Double, b: Double, t: Double): Double = a + (b - a) * t
    fun mix(a: EffectVector, b: EffectVector, t: Double): EffectVector = a + (b - a) * t
    fun fract(x: Double): Double = x - floor(x)

    fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
        val t = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    /** Metal:154. */
    fun sCurve(y: Double, contrast: Double): Double {
        val v = clamp(y, 0.0, 1.0)
        val a = v.pow(contrast)
        val b = (1 - v).pow(contrast)
        return a / maxOf(a + b, 1e-6)
    }

    /**
     * Controls operate on perceptual tone values; image math and compositing
     * stay linear. A 0.5 linear pivot crushed normal skin at the default
     * contrast. Metal:155-157.
     */
    fun curve(y: Double, contrast: Double): Double = lin(clamp((srgb(y) - .5) * contrast + .5, 0.0, 1.0))

    /** Metal:32. */
    fun hash32(value: UInt): UInt {
        var n = value
        n = n xor (n shr 16)
        n *= 0x7feb352du
        n = n xor (n shr 15)
        n *= 0x846ca68bu
        n = n xor (n shr 16)
        return n
    }

    /** A number in 0…1 from three whole ones: 24 bits, exact in single precision. Metal:33. */
    fun random01(x: UInt, y: UInt, z: UInt): Double =
        (hash32(x xor hash32(y + 0x9e3779b9u) xor hash32(z + 0x85ebca6bu)) shr 8).toDouble() / 16777216.0
}

/**
 * The film grain of the original: a lattice of noise made once per frame, read
 * between its points, and multiplied into the picture where it is neither
 * black nor white. Metal:137-142 (`grain`), :554-559 (`ve_grainNoise`);
 * the lattice's size is YouRec/VideoEffects/MetalVideoEffects.swift:445-448.
 *
 * The lattice is whole-number hashing, so its values are the same on any
 * machine; the shader stores them in a half-float texture, which this does not
 * round to.
 */
object VideoEffectGrain {
    /** One point of the lattice of a frame: `x`, `y`, `z` for the three channels of colour grain, `w` for monochrome grain. Each −1…1. */
    fun lattice(x: Int, y: Int, frame: Int): EffectUniformVector {
        val f = frame.toUInt()
        val base = VideoEffectShaderMath.random01(x.toUInt(), y.toUInt(), f + 13u) * 2 - 1
        fun other(seed: UInt) = VideoEffectShaderMath.random01(x.toUInt(), y.toUInt(), f + seed) * 2 - 1
        return EffectUniformVector(base * .7 + other(29u) * .3, base * .7 + other(59u) * .3, base * .7 + other(101u) * .3, base)
    }

    /**
     * The monochrome lattice of a frame, [columns] by [rows], row by row: each point's `w` of [lattice]
     * as a level, −1…1 as 0…255, as the export's texture holds it. The hashes of the row and of the frame
     * are worked out once, so that a picture's worth (two million points at 1080 × 1920) is made in a
     * few milliseconds, for the preview. Metal:32-33, :554-559.
     */
    fun monochromeLevels(columns: Int, rows: Int, frame: Int): ByteArray {
        val out = ByteArray(columns * rows)
        val z = VideoEffectShaderMath.hash32(frame.toUInt() + 13u + 0x85ebca6bu)
        for (y in 0 until rows) {
            val row = VideoEffectShaderMath.hash32(y.toUInt() + 0x9e3779b9u) xor z
            val at = y * columns
            for (x in 0 until columns) {
                val r = (VideoEffectShaderMath.hash32(x.toUInt() xor row) shr 8).toInt()
                // ((r / 2²⁴ · 2 − 1) · 0.5 + 0.5) · 255, rounded: r · 255 / 2²⁴ to the nearest whole.
                out[at + x] = ((r.toLong() * 255 + (1L shl 23)) shr 24).toInt().toByte()
            }
        }
        return out
    }

    /** How many points the lattice of a picture has, across and down, for grain of a size (1 for Noir). */
    fun latticeSize(width: Int, height: Int, grainSize: Double): Pair<Int, Int> {
        val scale = minOf(width, height) / 1080.0
        return Pair(ceil(width / (scale * grainSize)).toInt() + 1, ceil(height / (scale * grainSize)).toInt() + 1)
    }

    /**
     * The noise under one pixel: the lattice read with the shader's smoothed
     * bilinear sample. [x] and [y] are the pixel's whole indices; grain is
     * laid out as on a picture whose shorter side is 1080.
     */
    fun sampled(x: Int, y: Int, width: Int, height: Int, frame: Int, grainSize: Double): EffectUniformVector {
        val pixelScale = minOf(width, height) / 1080.0
        val (columns, rows) = latticeSize(width, height, grainSize)
        val px = x / pixelScale / grainSize
        val py = y / pixelScale / grainSize
        fun smooth(f: Double) = f * f * (3 - 2 * f)
        // The sample point is (cell + 0.5 + f) texels: between the centres of `cell` and the next, f of the way.
        val tx = floor(px) + smooth(VideoEffectShaderMath.fract(px))
        val ty = floor(py) + smooth(VideoEffectShaderMath.fract(py))
        val x0 = floor(tx).toInt()
        val y0 = floor(ty).toInt()
        val fx = tx - x0
        val fy = ty - y0
        fun at(i: Int, j: Int) = lattice(i.coerceIn(0, columns - 1), j.coerceIn(0, rows - 1), frame)
        val a = at(x0, y0)
        val b = at(x0 + 1, y0)
        val c = at(x0, y0 + 1)
        val d = at(x0 + 1, y0 + 1)
        fun blend(pick: (EffectUniformVector) -> Double): Double {
            val top = VideoEffectShaderMath.mix(pick(a), pick(b), fx)
            val bottom = VideoEffectShaderMath.mix(pick(c), pick(d), fx)
            return VideoEffectShaderMath.mix(top, bottom, fy)
        }
        return EffectUniformVector(blend { it.x }, blend { it.y }, blend { it.z }, blend { it.w })
    }

    /**
     * A colour with grain on it, given the noise under its pixel: one number
     * for monochrome grain, or one per channel. Strongest in the middle
     * tones, nothing at black and at white. Metal:137-141.
     */
    fun apply(c: EffectVector, noise: EffectVector, strength: Double): EffectVector {
        val y = VideoEffectShaderMath.clamp(VideoEffectShaderMath.luma(c), 0.0, 1.0)
        val a = strength * .12 * maxOf(0.0, 4 * y * (1 - y)).pow(.7)
        return (c * (noise * a * 2.0).map(::exp)).clamped()
    }

    fun apply(c: EffectVector, noise: Double, strength: Double): EffectVector = apply(c, EffectVector(noise), strength)
}

/**
 * A picture the formulas can read as the shader reads its input: linear
 * colours in rows from the top, sampled between pixel centres and held at the
 * edges (`linearSampler`, Metal:14).
 */
class EffectPicture(val width: Int, val height: Int, private val pixels: List<EffectVector>) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height)
    }

    constructor(width: Int, height: Int, pixel: (x: Int, y: Int) -> EffectVector) :
        this(width, height, List(width * height) { pixel(it % width, it / width) })

    val size: Size get() = Size(width.toDouble(), height.toDouble())

    operator fun get(x: Int, y: Int): EffectVector = pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]

    /** The centre of a pixel as a position. */
    fun uv(x: Int, y: Int): Point = Point((x + .5) / width, (y + .5) / height)

    fun sample(uv: Point): EffectVector {
        val tx = uv.x * width - .5
        val ty = uv.y * height - .5
        val x0 = floor(tx).toInt()
        val y0 = floor(ty).toInt()
        val fx = tx - floor(tx)
        val fy = ty - floor(ty)
        val top = VideoEffectShaderMath.mix(this[x0, y0], this[x0 + 1, y0], fx)
        val bottom = VideoEffectShaderMath.mix(this[x0, y0 + 1], this[x0 + 1, y0 + 1], fx)
        return VideoEffectShaderMath.mix(top, bottom, fy)
    }
}

/** Where an RGB Offset reads each channel from. */
data class RGBOffsetSamples(val red: Point, val green: Point, val blue: Point)

object VideoEffectFormulas {
    private val math = VideoEffectShaderMath

    /**
     * How an effect's result joins its input: at full intensity the result,
     * held inside 0…1; below, the input moved towards that by the intensity.
     * At zero the input comes back exactly. Metal:417.
     */
    fun mixed(original: EffectVector, out: EffectVector, intensity: Double): EffectVector =
        if (intensity >= 1) out.clamped() else math.mix(original, out.clamped(), intensity)

    // Color Correction. Metal:238-303; parameters p[0]…p[11].

    /** Brings a colour inside 0…1 by pulling it towards the grey of its own luma, so the hue holds. Metal:243-250. */
    fun gradeGamut(c: EffectVector): EffectVector {
        val y = math.clamp(math.luma(c), 0.0, 1.0)
        var amount = 1.0
        val delta = c - y
        for (i in 0..2) {
            if (delta[i] > 0) amount = minOf(amount, (1 - y) / delta[i]) else if (delta[i] < 0) amount = minOf(amount, -y / delta[i])
        }
        return delta * maxOf(0.0, amount) + y
    }

    /**
     * C1 rational shoulder fits a known maximum to white, preserving every
     * level and a unit slope at the knee. Mirroring it gives a toe for
     * negative Blacks. Metal:251-257.
     */
    fun gradeShoulder(value: Double, maximum: Double): Double {
        if (maximum <= 1 || value <= .8) return value
        val a = (maximum - .8) / .2
        val t = (value - .8) / (maximum - .8)
        return .8 + .2 * a * t / (1 + (a - 1) * t)
    }

    /**
     * Primary grading. Exposure is linear stops; tone controls operate on
     * perceptual luma; all tone changes share one RGB ratio, avoiding
     * per-channel contrast hue shifts. With every control but the pivot at its
     * neutral value the colour is returned untouched. Metal:258-303.
     */
    fun colorCorrection(
        input: EffectVector,
        exposure: Double = 0.0,
        contrast: Double = 1.0,
        shadows: Double = 0.0,
        highlights: Double = 0.0,
        blacks: Double = 0.0,
        whites: Double = 0.0,
        pivot: Double = 0.5,
        temperature: Double = 0.0,
        tint: Double = 0.0,
        saturation: Double = 1.0,
        vibrance: Double = 0.0,
        hue: Double = 0.0,
    ): EffectVector {
        val neutral = exposure == 0.0 && contrast == 1.0 && shadows == 0.0 && highlights == 0.0 && blacks == 0.0 && whites == 0.0 &&
            temperature == 0.0 && tint == 0.0 && saturation == 1.0 && vibrance == 0.0 && hue == 0.0
        if (neutral) return input // Pivot alone is inert; preserve the exact input.
        var c = input
        val gains = EffectVector(.45 * temperature + .2 * tint, -.2 * tint, -.45 * temperature + .2 * tint).map { 2.0.pow(it) }
        c *= gains / gains.dot(math.LUMA) * 2.0.pow(exposure)
        val before = math.luma(c)
        var v = math.srgb(maxOf(before, 0.0))
        v = gradeShoulder(v, math.srgb(2.0.pow(exposure)))
        if (contrast != 1.0 && v > 0 && v < 1) {
            val odds = pivot / (1 - pivot)
            v = 1 / (1 + odds.pow(contrast - 1) * ((1 - v) / v).pow(contrast))
        }
        v += shadows * v * (1 - v) * (1 - v)
        v += highlights * v * v * (1 - v)
        v += .12 * blacks * (1 - v).pow(4.0) + .12 * whites * v.pow(4.0)
        v = gradeShoulder(v, 1 + .12 * whites)
        v = 1 - gradeShoulder(1 - v, 1 - .12 * blacks)
        val target = math.lin(v)
        c = if (before > 1e-7) c * (target / before) else EffectVector(target)
        var perceptual = math.lab(c.map { maxOf(it, 0.0) })
        val chroma = sqrt(perceptual.y * perceptual.y + perceptual.z * perceptual.z)
        // Vibrance mostly affects muted colors; protect common skin hues from boosts.
        val h = math.hsv(math.srgb(c.map { maxOf(it, 0.0) }))
        val skin = math.smoothstep(.025, .055, h.x) * (1 - math.smoothstep(.12, .16, h.x)) * math.smoothstep(.08, .2, h.y)
        var vibrant = vibrance * (1 - math.clamp(chroma / .25, 0.0, 1.0))
        if (vibrant > 0) vibrant *= 1 - .6 * skin
        if (hue != 0.0 && chroma > 1e-5) {
            val angle = hue * PI / 180
            val cs = cos(angle)
            val sn = sin(angle)
            perceptual = EffectVector(perceptual.x, cs * perceptual.y - sn * perceptual.z, sn * perceptual.y + cs * perceptual.z)
            c = math.unlab(perceptual)
            c *= target / maxOf(math.luma(c), 1e-7)
        }
        c = gradeGamut(c)
        var gain = saturation * (1 + vibrant)
        if (gain > 1) {
            val delta = c - target
            var ratio = 0.0
            for (i in 0..2) ratio = maxOf(ratio, if (delta[i] > 0) delta[i] / maxOf(1 - target, 1e-7) else -delta[i] / maxOf(target, 1e-7))
            // A monotone saturation shoulder: full gain on muted colors, smoothly
            // tending to the gamut boundary. Distinct vivid shades stay distinct.
            gain /= sqrt(1 + (gain * gain - 1) * ratio * ratio)
        }
        c = (c - target) * gain + target
        // Compress chroma toward neutral at the same luma instead of independently
        // clipping RGB channels (which changes hue, especially on saturated reds).
        return gradeGamut(c)
    }

    /**
     * The five dyes of the Silent Tint in the catalogue's order (Night Blue,
     * Amber, Fire Red, Nature Green, Sepia), as sRGB. Metal:333.
     */
    val silentTints: List<EffectVector> = listOf(
        EffectVector(.243137, .435294, .658824),
        EffectVector(.85098, .643137, .254902),
        EffectVector(.721569, .262745, .184314),
        EffectVector(.431373, .560784, .305882),
        EffectVector(.541176, .415686, .270588),
    )

    /**
     * Silent Tint: contrast and dye in linear light. [tint] is the dye's
     * index; [tone] is the "Tone" process, which colours the shadows and
     * leaves the whites, where "Tint" colours the whole picture. Metal:332-336.
     */
    fun silentTint(c: EffectVector, tint: Int = 1, tone: Boolean = false, contrast: Double = 1.15): EffectVector {
        val dye = math.lin(silentTints[tint])
        val l = math.curve(math.luma(c), contrast)
        return if (!tone) dye * l else math.mix(dye * .15, EffectVector.ONE, math.smoothstep(0.0, 1.0, l))
    }

    /** Three-Strip; protect skin hue from the red-separation push. Metal:337-345. */
    fun threeStrip(c: EffectVector, saturation: Double = 1.4, redDepth: Double = 0.6, warmth: Double = 0.2): EffectVector {
        val enc = math.srgb(c)
        val h = math.hsv(enc)
        val skin = math.smoothstep(.03, .055, h.x) * (1 - math.smoothstep(.115, .15, h.x)) * math.smoothstep(.12, .3, h.y)
        val separation = c - (EffectVector(c.y, c.z, c.x) + EffectVector(c.z, c.x, c.y)) * .5
        var out = c + separation * (saturation - 1) * (1 - .65 * skin)
        val red = 1 - math.smoothstep(15.0 / 360, 25.0 / 360, math.hueDistance(h.x, 0.0))
        val less = red * redDepth * .15 * (1 - skin)
        out = EffectVector(out.x, out.y - less, out.z - less)
        val l = math.luma(out)
        val s = l * l * (3 - 2 * l)
        out += (s - l) * .14
        out *= EffectVector(1 + warmth * .06, 1.0, 1 - warmth * .06)
        val high = maxOf(out.x, maxOf(out.y, out.z))
        if (high > .9) out = (out - .9) / (1 + maxOf(0.0, high - .9)) + .9
        return math.mix(out, c, skin * .35)
    }

    /** Bleach Bypass. Metal:346. */
    fun bleachBypass(c: EffectVector, strength: Double = 0.7): EffectVector {
        val y = math.luma(c)
        val overlay = if (y < .5) c * 2.0 * y else EffectVector.ONE - (EffectVector.ONE - c) * 2.0 * (1 - y)
        var bleached = math.mix(EffectVector(y), overlay, .5)
        bleached = math.mix(EffectVector(math.luma(bleached)), bleached, 1 - .3 * strength)
        return math.mix(c, bleached, strength)
    }

    /**
     * Day for Night. The top of the picture is darkened where it is bright and
     * sky blue, and the corners a little everywhere, so the result depends on
     * the position. Metal:347-350.
     */
    fun dayForNight(c: EffectVector, uv: Point, darkness: Double = 0.6, blue: Double = 0.7, moonlight: Boolean = true): EffectVector {
        val y = math.luma(c)
        val h = math.hsv(math.srgb(c))
        var out = math.mix(EffectVector(y), c, .4) * 2.0.pow(-3 * darkness)
        out *= math.mix(EffectVector.ONE, EffectVector(.55, .7, 1.0), blue)
        val sky = (1 - math.smoothstep(.25, .38, uv.y)) * math.smoothstep(.5, .85, y) * (1 - math.smoothstep(.08, .18, math.hueDistance(h.x, .61)))
        out *= 1 - .5 * sky
        if (moonlight) out = math.mix(out, EffectVector(y * 2.0.pow(-.7 * darkness)), math.smoothstep(.94, .99, y) * .7)
        out *= 1 - .15 * math.smoothstep(.2, .75, length(uv.x - .5, uv.y - .5))
        return out
    }

    /**
     * How much of its colour a pixel keeps under Color Isolation "A color":
     * 1 inside the hue range, falling to 0 over the softness, and nothing for
     * a colour too pale to have a hue. [color] is the kept colour in linear
     * light, as the shader receives it. Metal:352.
     */
    fun colorIsolationWeight(c: EffectVector, color: EffectVector, range: Double = 22.0, softness: Double = 0.4): Double {
        val h = math.hsv(math.srgb(c))
        val target = math.hsv(math.srgb(color))
        val r = range / 360
        val soft = maxOf(.001, softness * .1)
        return (1 - math.smoothstep(r, r + soft, math.hueDistance(h.x, target.x))) * math.smoothstep(.08, .2, h.y)
    }

    /** Color Isolation, given how much of its colour the pixel keeps: the rest is its luma. Metal:353. */
    fun colorIsolation(c: EffectVector, weight: Double): EffectVector = math.mix(EffectVector(math.luma(c)), c, weight)

    /**
     * Color Isolation "A color" by the single-frame rule, which recipes with
     * [VideoEffectSettings.algorithm] absent or 1 are drawn with. A recipe
     * with algorithm 2 takes its weight from a matte smoothed over the last
     * source frames instead (Metal:352, `u.flags&8`).
     */
    fun colorIsolation(c: EffectVector, color: EffectVector, range: Double = 22.0, softness: Double = 0.4): EffectVector =
        colorIsolation(c, colorIsolationWeight(c, color, range, softness))

    /**
     * Noir without its grain: [VideoEffectGrain.apply] with strength
     * [NOIR_GRAIN] and the monochrome noise of the pixel comes after it when
     * the grain is on. The luma is weighted towards green and red, as a lens
     * filter would. Metal:354.
     */
    fun noir(c: EffectVector, contrast: Double = 1.5, crush: Double = 0.12, shadowColor: Boolean = false): EffectVector {
        var l = math.sCurve(math.srgb(c.dot(EffectVector(.4, .5, .1))), contrast)
        l = math.lin(l * (1 - 2 * crush * (1 - l)))
        var out = EffectVector(l)
        if (shadowColor) out *= math.mix(EffectVector(.94, .97, 1.0), EffectVector.ONE, l)
        return out
    }

    /** The strength and the size of Noir's grain. Metal:354. */
    const val NOIR_GRAIN = 0.25
    const val NOIR_GRAIN_SIZE = 1.0

    /**
     * Expressionism. The vignette is an oval that follows the picture's shape,
     * or with [circle] a circle; [size] is the picture's, in pixels.
     * Metal:355-356.
     */
    fun expressionism(
        c: EffectVector,
        uv: Point,
        size: Size,
        contrast: Double = 2.2,
        vignette: Double = 0.7,
        circle: Boolean = false,
        tint: Boolean = false,
    ): EffectVector {
        val perceptual = math.srgb(math.luma(c))
        var l = math.sCurve(perceptual, contrast)
        l = math.lin(math.mix(l, math.smoothstep(.35, .65, perceptual), .4))
        var dx = (uv.x - .5) * 2
        val dy = (uv.y - .5) * 2
        if (circle) dx *= size.width / size.height
        val v = math.clamp(1 - math.clamp(length(dx, dy) / 1.25, 0.0, 1.0).pow(2.5), 0.0, 1.0)
        l *= math.mix(1.0, v, vignette)
        var out = EffectVector(l)
        if (tint) out *= math.lin(EffectVector(.82, .84, .52))
        return out
    }

    /**
     * RGB Offset: where the red, the green and the blue of the pixel at [uv]
     * are read from. An offset of 1 is 12 % of the picture's shorter side;
     * a positive one moves the channel's image right and down. Metal:320-325.
     */
    fun rgbOffset(uv: Point, size: Size, red: Point, green: Point, blue: Point): RGBOffsetSamples {
        val shorter = minOf(size.width, size.height)
        val reachX = 0.12 * shorter / size.width
        val reachY = 0.12 * shorter / size.height
        fun from(offset: Point) = Point(uv.x - offset.x * reachX, uv.y - offset.y * reachY)
        return RGBOffsetSamples(from(red), from(green), from(blue))
    }

    /**
     * The negative a strobe shows. A photographic negative complements display
     * values, so mid-gray stays mid-gray. Which frames show it is
     * [VideoEffectSettings.strobeShowsNegative]. Metal:326-330.
     */
    fun negative(c: EffectVector): EffectVector = math.lin(EffectVector.ONE - math.srgb(c.clamped()))

    /**
     * Kaleidoscope: the position the pixel at [uv] shows.
     *
     * Polar distances are in pixels, so a 120-degree wedge stays 120 degrees
     * in either orientation. The first clockwise sector from the split's ray
     * is kept; odd counts repeat the entire wedge by rotation, even counts
     * alternate reflections. The source rotation is inverse-mapped in picture
     * pixels about the centre, then the pan is taken off; a position outside
     * the picture reflects at its edges. Metal:54-86.
     */
    fun kaleidoscope(
        uv: Point,
        size: Size,
        divisions: Double = 2.0,
        centerX: Double = 0.5,
        centerY: Double = 0.5,
        splitAngle: Double = 0.0,
        sourcePanX: Double = 0.0,
        sourcePanY: Double = 0.0,
        sourceAngle: Double = 0.0,
    ): Point {
        val count = math.clamp(roundHalfAway(divisions), 2.0, 9.0).toInt()
        val dx = (uv.x - centerX) * size.width
        val dy = (uv.y - centerY) * size.height
        var px = uv.x
        var py = uv.y
        if (dx * dx + dy * dy >= 1e-8) {
            val axis = splitAngle * (PI / 180.0) - PI / 2
            val turn = 2 * PI
            val span = turn / count
            val angle = math.fract((atan2(dy, dx) - axis) / turn) * turn
            val sector = minOf((angle / span).toInt(), count - 1)
            if (sector != 0) {
                var local = angle - sector * span
                if (count % 2 == 0 && sector % 2 != 0) local = span - local
                val mapped = axis + local
                val radius = length(dx, dy)
                px = centerX + radius * cos(mapped) / size.width
                py = centerY + radius * sin(mapped) / size.height
            }
        }
        if (sourceAngle != 0.0) {
            val a = -sourceAngle * (PI / 180.0)
            val ox = (px - centerX) * size.width
            val oy = (py - centerY) * size.height
            val c = cos(a)
            val s = sin(a)
            px = centerX + (c * ox - s * oy) / size.width
            py = centerY + (s * ox + c * oy) / size.height
        }
        px -= sourcePanX
        py -= sourcePanY
        if (px >= 0.0 && py >= 0.0 && px <= 1.0 && py <= 1.0) return Point(px, py)
        return Point(1.0 - abs(2.0 * math.fract(px * 0.5) - 1.0), 1.0 - abs(2.0 * math.fract(py * 0.5) - 1.0))
    }

    /** The shader's `round`: halves away from zero. */
    private fun roundHalfAway(x: Double): Double = if (x < 0) -floor(-x + .5) else floor(x + .5)

    private fun length(x: Double, y: Double): Double = sqrt(x * x + y * y)

    /** The kinds whose shader branch [pixel] holds. */
    val referenced: Set<VideoEffectID> = setOf(
        VideoEffectID.ColorCorrection, VideoEffectID.SilentTint, VideoEffectID.ThreeStrip, VideoEffectID.BleachBypass,
        VideoEffectID.DayForNight, VideoEffectID.Noir, VideoEffectID.Expressionism, VideoEffectID.ColorIsolation,
        VideoEffectID.RgbOffset, VideoEffectID.NegativeStrobe, VideoEffectID.Kaleidoscope,
    )

    /**
     * One pixel of one effect's pass, as the shader's `ve_effect` writes it
     * (Metal:304-418): the pixel ([x], [y]) of a picture the size of [input],
     * from the block [u] that [EffectFrame.of] made for the frame. Running the
     * blocks of a frame in their order, each on the picture the one before
     * made, gives the frame.
     *
     * Noir's grain is the frame's lattice under the pixel. A Color Isolation
     * that keeps the subject, or one made with the temporal matte, reads how
     * much to keep from [mask]; without one it cannot be drawn. For "The
     * subject" that is the person mask refined by [subjectMask], which
     * [pass] makes from the raw mask it is given.
     */
    fun pixel(u: EffectUniforms, input: EffectPicture, x: Int, y: Int, mask: ((Point) -> Double)? = null): EffectVector {
        require(u.effect in referenced) { "No reference formula for ${u.effect}" }
        val size = input.size
        val uv = input.uv(x, y)
        fun p(index: Int) = u.p[index].x
        if (u.effect == VideoEffectID.Kaleidoscope) {
            // A mirrored sample keeps its source radiometry: this pass is not held inside 0…1. Metal:307-311.
            val reflected = input.sample(kaleidoscope(uv, size, p(0), p(1), p(2), p(3), p(4), p(5), p(6)))
            return if (u.intensity >= 1) reflected else math.mix(input.sample(uv), reflected, u.intensity)
        }
        val original = input.sample(uv)
        val c = original
        val out = when (u.effect) {
            VideoEffectID.RgbOffset -> rgbOffset(uv, size, Point(p(0), p(1)), Point(p(2), p(3)), Point(p(4), p(5))).let {
                EffectVector(input.sample(it.red).x, input.sample(it.green).y, input.sample(it.blue).z)
            }
            VideoEffectID.NegativeStrobe -> negative(c)
            VideoEffectID.ColorCorrection -> colorCorrection(c, p(0), p(1), p(2), p(3), p(4), p(5), p(6), p(7), p(8), p(9), p(10), p(11))
            VideoEffectID.SilentTint -> silentTint(c, p(0).toInt(), p(1) >= .5, p(2))
            VideoEffectID.ThreeStrip -> threeStrip(c, p(0), p(1), p(2))
            VideoEffectID.BleachBypass -> bleachBypass(c, p(0))
            VideoEffectID.DayForNight -> dayForNight(c, uv, p(0), p(1), p(2) > .5)
            VideoEffectID.ColorIsolation -> {
                val weight = if (p(0) > .5 || u.flags and EffectUniforms.FLAG_TEMPORAL_MATTE != 0) {
                    val matte = requireNotNull(mask) { "This Color Isolation reads a matte" }
                    matte(uv)
                } else {
                    colorIsolationWeight(c, u.p[1].xyz, p(2), p(3))
                }
                colorIsolation(c, weight)
            }
            VideoEffectID.Noir -> noir(c, p(0), p(1), p(3) > .5).let { plain ->
                if (p(2) > .5) {
                    VideoEffectGrain.apply(plain, VideoEffectGrain.sampled(x, y, input.width, input.height, u.frame, NOIR_GRAIN_SIZE).w, NOIR_GRAIN)
                } else {
                    plain
                }
            }
            else -> expressionism(c, uv, size, p(0), p(1), p(2) > .5, p(3) > .5)
        }
        return mixed(original, out, u.intensity)
    }

    /**
     * What Color Isolation "The subject" keeps: the raw [person] mask refined
     * on the picture with the effect's softness as a person's,
     * [MaskRefinement.refinedMask]. [lite] is the recorder's lite quality.
     * Renderer:483-484, Metal:351.
     */
    fun subjectMask(u: EffectUniforms, input: EffectPicture, person: EffectMask, lite: Boolean = false): EffectMask {
        require(u.effect == VideoEffectID.ColorIsolation)
        return MaskRefinement.refinedMask(person, EffectMask.luma(input), u.number("softness"), lite = lite, person = true)
    }

    /**
     * A whole pass: [pixel] for every pixel of [input]. A Color Isolation
     * keeping the subject, drawn without the temporal matte and given no
     * [mask], reads the [person] mask through [subjectMask]. With the temporal
     * matte the [mask] is the matte, which is not referenced here.
     */
    fun pass(u: EffectUniforms, input: EffectPicture, mask: ((Point) -> Double)? = null, person: EffectMask? = null, lite: Boolean = false): EffectPicture {
        val subject = u.effect == VideoEffectID.ColorIsolation && u.p[0].x > .5 && u.flags and EffectUniforms.FLAG_TEMPORAL_MATTE == 0
        val matte = mask ?: if (subject && person != null) subjectMask(u, input, person, lite).let { refined -> { uv: Point -> refined.sample(uv) } } else null
        return EffectPicture(input.width, input.height) { x, y -> pixel(u, input, x, y, matte) }
    }
}
