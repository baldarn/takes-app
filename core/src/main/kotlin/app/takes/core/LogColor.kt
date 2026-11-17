package app.takes.core

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Log colour as the original manages it (YouRec/LogColor.swift:61-191): the
 * Apple Log curve, the primaries of Apple Log (Rec.2020) and Apple Log 2
 * (Apple's wide gamut) into linear Rec.709, the viewing transform that shows
 * a log recording on an SDR screen, and the Highlights correction of the
 * colour grade, all on one colour at a time in linear light. Profile
 * constants are the Apple Log Profile (2023) and Apple Log 2 Profile (2025)
 * as the original took them, cross-checked there against OpenColorIO's Apple
 * camera transforms.
 *
 * The local recovery of the original, a filter over the whole frame
 * (`LogColor.localRecovery`), is [LocalRecovery].
 */
object LogColor {
    private const val A = 47.28711236
    private const val B = 0.05641088
    private const val C = 0.69336945
    private const val D = 0.08550479
    private const val E = 0.00964052

    /** A code value of the Apple Log curve to scene-linear light. */
    fun decodeValue(x: Double): Double =
        if (x >= A * (0.01 + B).pow(2)) 2.0.pow((x - C) / D) - E else sqrt(max(0.0, x) / A) - B

    /** Scene-linear light to a code value of the Apple Log curve. */
    fun encodeValue(x: Double): Double =
        if (x >= 0.01) log2(max(x + E, 0.00000001)) * D + C else A * max(0.0, x + B).pow(2)

    private fun log2(x: Double) = ln(x) / ln(2.0)

    /** Row-major 3 × 3 matrices. */
    private fun multiply(m: DoubleArray, n: DoubleArray) = DoubleArray(9) { i ->
        val row = i / 3
        val column = i % 3
        (0 until 3).sumOf { m[row * 3 + it] * n[it * 3 + column] }
    }

    private fun apply(m: DoubleArray, rgb: DoubleArray) = DoubleArray(3) { r -> m[r * 3] * rgb[0] + m[r * 3 + 1] * rgb[1] + m[r * 3 + 2] * rgb[2] }

    private fun inverse(m: DoubleArray): DoubleArray {
        val (a, b, c) = Triple(m[0], m[1], m[2])
        val (d, e, f) = Triple(m[3], m[4], m[5])
        val (g, h, i) = Triple(m[6], m[7], m[8])
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        return doubleArrayOf(
            (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
        )
    }

    /** RGB to XYZ for primaries given as chromaticities, white D65. */
    private fun xyz(r: Pair<Double, Double>, g: Pair<Double, Double>, b: Pair<Double, Double>): DoubleArray {
        fun column(p: Pair<Double, Double>) = doubleArrayOf(p.first / p.second, 1.0, (1 - p.first - p.second) / p.second)
        val cr = column(r)
        val cg = column(g)
        val cb = column(b)
        val matrix = DoubleArray(9) { i -> listOf(cr, cg, cb)[i % 3][i / 3] }
        val white = doubleArrayOf(0.3127 / 0.3290, 1.0, (1 - 0.3127 - 0.3290) / 0.3290)
        val s = apply(inverse(matrix), white)
        return DoubleArray(9) { i -> matrix[i] * s[i % 3] }
    }

    private val REC709 = xyz(0.64 to 0.33, 0.30 to 0.60, 0.15 to 0.06)
    private val BT2020 = xyz(0.708 to 0.292, 0.170 to 0.797, 0.131 to 0.046)
    private val APPLE = xyz(0.725 to 0.301, 0.221 to 0.814, 0.068 to -0.076)
    private val FROM_LOG = multiply(inverse(REC709), BT2020)
    private val FROM_LOG2 = multiply(inverse(REC709), APPLE)
    private val TO_LOG = inverse(FROM_LOG)
    private val TO_LOG2 = inverse(FROM_LOG2)

    /** The profile's primaries to linear Rec.709, row-major; Rec.709 itself is the identity. */
    fun matrix(profile: LUTInput): DoubleArray = when (profile) {
        LUTInput.AppleLog -> FROM_LOG.copyOf()
        LUTInput.AppleLog2 -> FROM_LOG2.copyOf()
        LUTInput.Rec709 -> doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    }

    /** Code values of a log recording to linear Rec.709 light. */
    fun decode(codes: DoubleArray, profile: LUTInput): DoubleArray =
        apply(if (profile == LUTInput.AppleLog2) FROM_LOG2 else FROM_LOG, DoubleArray(3) { decodeValue(codes[it]) })

    /** Linear Rec.709 light to code values of [profile]: what a LUT made for that profile expects. */
    fun encode(scene: DoubleArray, profile: LUTInput): DoubleArray {
        val rgb = apply(if (profile == LUTInput.AppleLog2) TO_LOG2 else TO_LOG, scene)
        return DoubleArray(3) { encodeValue(rgb[it]) }
    }

    private fun luma(rgb: DoubleArray) = max(0.0, rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722)

    /**
     * The viewing transform of a log recording on an SDR screen, linear in and
     * out: middle grey stays at 0.18, the toe stays linear, a rational shoulder
     * above 0.6 keeps bright highlights apart, and colours out of gamut are
     * brought toward neutral at the same luminance instead of clipping each
     * channel. Viewing only; a recording never has it baked in.
     */
    fun display(scene: DoubleArray): DoubleArray {
        val y = luma(scene)
        val excess = max(0.0, y - 0.6)
        val mapped = if (y <= 0.6) y else 0.6 + 0.4 * excess / (excess + 0.4)
        val scale = if (y > 0.000001) mapped / y else 1.0
        val chroma = DoubleArray(3) { scene[it] * scale - mapped }
        val hi = chroma.max()
        val lo = chroma.min()
        val fit = min(1.0, min((1 - mapped) / max(hi, 0.000001), mapped / max(-lo, 0.000001)))
        return DoubleArray(3) { (mapped + chroma[it] * fit).coerceIn(0.0, 1.0) }
    }

    /**
     * The Highlights value of the colour grade on scene-linear light, before
     * the viewing transform and any LUT: luminance up to 0.35 is untouched,
     * brighter tones are compressed by a log shoulder of unit slope at its
     * knee (negative amounts, recovering highlights) or lifted (positive).
     * Monotonic, and zero is an exact bypass.
     */
    fun highlights(scene: DoubleArray, amount: Double): DoubleArray {
        if (!amount.isFinite() || amount == 0.0) return scene
        val y = luma(scene)
        val mapped = highlightsLuminance(y, amount)
        val scale = if (y > 0.000001) mapped / y else 1.0
        return DoubleArray(3) { scene[it] * scale }
    }

    /** The luminance [y] becomes under Highlights at [amount] ([highlights]). */
    fun highlightsLuminance(y: Double, amount: Double): Double {
        if (!amount.isFinite() || amount == 0.0 || y <= KNEE) return y
        val a = max(-1.0, min(1.0, amount))
        val delta = y - KNEE
        val strength = max(0.000001, -a * 6.0)
        return KNEE + if (a < 0) ln(1 + strength * delta) / strength else delta * (1 + 2 * a * delta / (delta + KNEE))
    }

    /** The luminance that Highlights at [amount] turns into [mapped]: the inverse of [highlightsLuminance]. */
    fun highlightsInverse(mapped: Double, amount: Double): Double {
        if (!amount.isFinite() || amount == 0.0 || mapped <= KNEE) return mapped
        val a = max(-1.0, min(1.0, amount))
        val m = mapped - KNEE
        val delta = if (a < 0) {
            val strength = -a * 6.0
            (kotlin.math.exp(strength * m) - 1) / strength
        } else {
            // m (δ + K) = δ (δ + K) + 2aδ², a quadratic in δ with one positive root.
            val q = 1 + 2 * a
            (m - KNEE + sqrt((KNEE - m) * (KNEE - m) + 4 * q * m * KNEE)) / (2 * q)
        }
        return KNEE + delta
    }

    /** Luminance at or below which Highlights changes nothing. */
    const val KNEE = 0.35

    /**
     * What a LUT gets from a picture, as the original's grade feeds it
     * (YouRec/VideoToolCompositor.swift:513-523): a Rec.709 LUT the picture
     * as viewed, display-encoded; a log LUT the balanced scene encoded in
     * its profile, never the SDR picture.
     */
    fun lutInput(balancedScene: DoubleArray, viewedScene: DoubleArray, input: LUTInput): DoubleArray =
        if (input == LUTInput.Rec709) DoubleArray(3) { toDisplay(viewedScene[it]) } else encode(balancedScene, input)

    /** sRGB transfer, linear to display-encoded, as the grade's LUT domain is entered. */
    fun toDisplay(value: Double): Double = when {
        value <= 0.0031308 -> value * 12.92
        else -> 1.055 * value.pow(1 / 2.4) - 0.055
    }

    fun toLinear(value: Double): Double = if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
}
