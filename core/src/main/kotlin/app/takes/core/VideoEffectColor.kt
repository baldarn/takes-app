package app.takes.core

import kotlinx.serialization.Serializable
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A colour of a video effect as the person picked it: sRGB, each channel 0…1.
 * Saved with the original's field names (YouRec/VideoEffects/VideoEffectModel.swift:55-63).
 */
@Serializable
data class EffectRGB(val red: Double, val green: Double, val blue: Double) {
    val valid: Boolean get() = listOf(red, green, blue).all { it.isFinite() && it in 0.0..1.0 }

    companion object {
        /** `#RRGGBB`, with or without the hash. Anything that is not a hexadecimal number reads as black. */
        fun hex(hex: String): EffectRGB {
            val value = hex.replace("#", "").toUIntOrNull(16) ?: 0u
            return EffectRGB(
                ((value shr 16) and 255u).toDouble() / 255,
                ((value shr 8) and 255u).toDouble() / 255,
                (value and 255u).toDouble() / 255,
            )
        }
    }
}

/**
 * Three numbers the effects' maths works on: linear RGB (BT.709 primaries), an
 * Oklab colour (L, a, b), or any other triple a formula needs. The original
 * uses `SIMD3<Double>` on the processor and `float3` in the shader.
 */
data class EffectVector(val x: Double, val y: Double, val z: Double) {
    constructor(all: Double) : this(all, all, all)

    operator fun plus(other: EffectVector) = EffectVector(x + other.x, y + other.y, z + other.z)
    operator fun plus(other: Double) = EffectVector(x + other, y + other, z + other)
    operator fun minus(other: EffectVector) = EffectVector(x - other.x, y - other.y, z - other.z)
    operator fun minus(other: Double) = EffectVector(x - other, y - other, z - other)
    operator fun times(other: EffectVector) = EffectVector(x * other.x, y * other.y, z * other.z)
    operator fun times(other: Double) = EffectVector(x * other, y * other, z * other)
    operator fun div(other: EffectVector) = EffectVector(x / other.x, y / other.y, z / other.z)
    operator fun div(other: Double) = EffectVector(x / other, y / other, z / other)
    operator fun get(index: Int): Double = when (index) { 0 -> x; 1 -> y; else -> z }

    fun dot(other: EffectVector): Double = x * other.x + y * other.y + z * other.z
    fun map(transform: (Double) -> Double) = EffectVector(transform(x), transform(y), transform(z))
    fun clamped(low: Double = 0.0, high: Double = 1.0) = map { minOf(high, maxOf(low, it)) }
    fun distanceSquared(other: EffectVector): Double = (this - other).let { it.dot(it) }
    fun distance(other: EffectVector): Double = sqrt(distanceSquared(other))

    companion object {
        val ZERO = EffectVector(0.0)
        val ONE = EffectVector(1.0)
    }
}

/**
 * The colour conversions of the video effects: sRGB ↔ linear light ↔ Oklab.
 * The Oklab equations are the published ones (Björn Ottosson,
 * https://bottosson.github.io/posts/oklab/); values are linear sRGB / BT.709.
 *
 * YouRec/VideoEffects/VideoEffectMath.swift:56-80, constants as written there.
 */
object EffectColorMath {
    /** An sRGB channel to linear light. */
    fun linear(x: Double): Double = if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)

    /** A linear channel to sRGB. */
    fun encoded(x: Double): Double = if (x <= 0.0031308) x * 12.92 else 1.055 * maxOf(0.0, x).pow(1 / 2.4) - 0.055

    fun linear(c: EffectRGB): EffectVector = EffectVector(linear(c.red), linear(c.green), linear(c.blue))

    /** Linear light back to a colour, each channel held inside 0…1. */
    fun rgb(c: EffectVector): EffectRGB =
        EffectRGB(minOf(1.0, maxOf(0.0, encoded(c.x))), minOf(1.0, maxOf(0.0, encoded(c.y))), minOf(1.0, maxOf(0.0, encoded(c.z))))

    /** Linear RGB to Oklab. */
    fun lab(c: EffectVector): EffectVector {
        val l = cbrt(0.4122214708 * c.x + 0.5363325363 * c.y + 0.0514459929 * c.z)
        val m = cbrt(0.2119034982 * c.x + 0.6806995451 * c.y + 0.1073969566 * c.z)
        val s = cbrt(0.0883024619 * c.x + 0.2817188376 * c.y + 0.6299787005 * c.z)
        return EffectVector(
            0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s,
        )
    }

    /** Oklab to linear RGB. */
    fun linearFromLab(c: EffectVector): EffectVector {
        val l = (c.x + 0.3963377774 * c.y + 0.2158037573 * c.z).pow(3)
        val m = (c.x - 0.1055613458 * c.y - 0.0638541728 * c.z).pow(3)
        val s = (c.x - 0.0894841775 * c.y - 1.291485548 * c.z).pow(3)
        return EffectVector(
            4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s,
        )
    }

    /** sRGB to Oklab, through linear light. */
    fun lab(c: EffectRGB): EffectVector = lab(linear(c))

    /** Oklab to sRGB, through linear light; not held inside 0…1, so that a round trip is exact. */
    fun encodedFromLab(c: EffectVector): EffectVector = linearFromLab(c).map(::encoded)

    /** BT.709 luma of linear light. */
    fun luminance(c: EffectVector): Double = c.dot(LUMA)

    /** What the palette analysis looks at: a little more contrast around the middle, held inside 0…1. */
    fun prepared(c: EffectVector): EffectVector {
        val y = luminance(c)
        val s = y * y * (3 - 2 * y)
        val target = y + (s - y) * 0.2
        return (c + (target - y)).clamped()
    }

    val LUMA = EffectVector(0.2126, 0.7152, 0.0722)
}
