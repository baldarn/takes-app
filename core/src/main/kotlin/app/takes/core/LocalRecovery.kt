package app.takes.core

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The local highlight recovery of a log recording's grade, as the original draws it
 * (YouRec/LogColor.swift:129-180, applied at YouRec/VideoToolCompositor.swift:513 on the balanced light of log
 * footage only): the log luminance of the frame split by a guided filter into a smooth illumination and its detail,
 * the bright illumination compressed, the detail put back, and the result taken back to scene light through the
 * inverse of the viewing shoulder, as one gain on each pixel's light that keeps its chroma. Zero is an exact bypass.
 *
 * The filter works on luminance alone, so a pixel's change is one number ([gain]). The port draws the grade of log
 * footage as a table on code values ([ColorGrade.table]), so that gain is turned back into the code values that give
 * the same balanced light ([recode]), and the table does the rest.
 */
object LocalRecovery {
    /** The regularisation of the guided filter (YouRec/LogColor.swift:143). */
    const val EPSILON = 0.12

    /** The radius in pixels of the neighbourhood on a frame of [width] by [height] (YouRec/LogColor.swift:170). */
    fun radius(width: Int, height: Int): Int = max(1, (60.0 * min(width, height) / 1080).roundToInt())

    /**
     * The side of the blocks the port's export averages a frame in before its boxes, for a [radius]: one pixel up to
     * 15, so the box is exact there, and an eighth of the radius beyond, so that a box spans at most about 34 blocks
     * whatever the frame's size. Not in the original, whose Core Image box runs over every pixel.
     */
    fun block(radius: Int): Int = max(1, radius / 8)

    /** The log luminance a pixel adds to the moments (YouRec/LogColor.swift:135-138). */
    fun logLuminance(y: Double): Double = log2(max(0.000001, y))

    /** The guided filter's slope and offset from the neighbourhood's mean log luminance and mean square (YouRec/LogColor.swift:141-146). */
    fun coefficients(mean: Double, meanSquare: Double): DoubleArray {
        val variance = max(0.0, meanSquare - mean * mean)
        val a = variance / (variance + EPSILON)
        return doubleArrayOf(a, mean * (1 - a))
    }

    /**
     * The scene luminance a pixel of balanced luminance [y] is given, with the neighbourhood's mean coefficients [a]
     * and [b] and the strength (YouRec/LogColor.swift:149-165).
     */
    fun target(y: Double, a: Double, b: Double, strength: Double): Double {
        val yy = max(0.000001, y)
        val l = log2(yy)
        val baseLog = a * l + b
        val base = 2.0.pow(baseLog)
        val adjusted = if (base <= 0.35) base else 0.35 + ln(1.0 + 12.0 * (base - 0.35)) / 12.0
        val excess = max(0.0, adjusted - 0.6)
        val mappedBase = if (adjusted <= 0.6) adjusted else 0.6 + 0.4 * excess / (excess + 0.4)
        var local = mappedBase * 2.0.pow(l - baseLog)
        val spike = max(0.0, local - 0.9)
        local = if (local <= 0.9) local else 0.9 + 0.1 * spike / (spike + 0.1)
        val originalExcess = max(0.0, yy - 0.6)
        val original = if (yy <= 0.6) yy else 0.6 + 0.4 * originalExcess / (originalExcess + 0.4)
        val shown = original + (local - original) * strength
        return if (shown <= 0.6) shown else 0.6 + 0.4 * (shown - 0.6) / max(0.000001, 1.0 - shown)
    }

    /** The gain on a pixel's light (YouRec/LogColor.swift:166): [target] over its luminance. */
    fun gain(y: Double, a: Double, b: Double, strength: Double): Double = target(y, a, b, strength) / max(0.000001, y)

    /** The strength of a grade's recovery, or 0 where it does nothing (YouRec/LogColor.swift:168, :179). */
    fun strength(settings: ColorSettings): Double = settings.localRecovery?.takeIf { it.isFinite() && it > 0 }?.let { min(1.0, it) } ?: 0.0

    /**
     * The mean of each pixel's square neighbourhood of [radius], with the frame's edges extended
     * (YouRec/LogColor.swift:171-175: a box of 2r + 1 over the frame clamped to its extent).
     */
    fun boxMean(plane: DoubleArray, width: Int, height: Int, radius: Int): DoubleArray {
        val across = DoubleArray(plane.size)
        val side = 2 * radius + 1
        for (y in 0 until height) {
            val row = y * width
            var sum = 0.0
            for (k in -radius..radius) sum += plane[row + k.coerceIn(0, width - 1)]
            for (x in 0 until width) {
                across[row + x] = sum / side
                sum += plane[row + (x + radius + 1).coerceAtMost(width - 1)] - plane[row + (x - radius).coerceAtLeast(0)]
            }
        }
        val out = DoubleArray(plane.size)
        for (x in 0 until width) {
            var sum = 0.0
            for (k in -radius..radius) sum += across[k.coerceIn(0, height - 1) * width + x]
            for (y in 0 until height) {
                out[y * width + x] = sum / side
                sum += across[(y + radius + 1).coerceAtMost(height - 1) * width + x] - across[(y - radius).coerceAtLeast(0) * width + x]
            }
        }
        return out
    }

    /** The mean coefficients ([coefficients]) at each pixel of a frame of balanced [luminance], row by row. */
    class Coefficients(val width: Int, val height: Int, val a: DoubleArray, val b: DoubleArray)

    /** The guided filter of a frame's balanced [luminance], up to the mean coefficients (YouRec/LogColor.swift:177-178). */
    fun coefficients(luminance: DoubleArray, width: Int, height: Int, radius: Int = radius(width, height)): Coefficients {
        val l = DoubleArray(luminance.size) { logLuminance(luminance[it]) }
        val mean = boxMean(l, width, height, radius)
        val meanSquare = boxMean(DoubleArray(l.size) { l[it] * l[it] }, width, height, radius)
        val a = DoubleArray(l.size)
        val b = DoubleArray(l.size)
        for (i in l.indices) coefficients(mean[i], meanSquare[i]).let { a[i] = it[0]; b[i] = it[1] }
        return Coefficients(width, height, boxMean(a, width, height, radius), boxMean(b, width, height, radius))
    }

    /** The gain on each pixel of a frame of balanced [luminance] at [amount] (YouRec/LogColor.swift:167-180); all ones at 0. */
    fun gains(luminance: DoubleArray, width: Int, height: Int, amount: Double): DoubleArray {
        val strength = if (amount.isFinite() && amount > 0) min(1.0, amount) else return DoubleArray(luminance.size) { 1.0 }
        val c = coefficients(luminance, width, height)
        return DoubleArray(luminance.size) { gain(luminance[it], c.a[it], c.b[it], strength) }
    }

    /** Balanced light, three values a pixel row by row, with the recovery at [amount] on it (YouRec/LogColor.swift:167-180). */
    fun apply(scene: DoubleArray, width: Int, height: Int, amount: Double): DoubleArray {
        val luminance = DoubleArray(width * height) { max(0.0, scene[it * 3] * 0.2126 + scene[it * 3 + 1] * 0.7152 + scene[it * 3 + 2] * 0.0722) }
        val g = gains(luminance, width, height, amount)
        return DoubleArray(scene.size) { scene[it] * g[it / 3] }
    }

    /**
     * How much to scale a log recording's light, as decoded from its curve and before anything else, so that its
     * balanced light comes out scaled by [gain]: exposure, warmth and tint scale with it, Highlights does not, so the
     * scale is found through Highlights' inverse ([LogColor.highlightsInverse]). [before] is the luminance before
     * Highlights, [highlights] the grade's value.
     */
    fun lightScale(before: Double, gain: Double, highlights: Double): Double {
        if (before <= 0.000001) return 1.0
        val after = LogColor.highlightsLuminance(before, highlights)
        return LogColor.highlightsInverse(gain * after, highlights) / before
    }

    /**
     * The code values of a log recording ([codes], of [profile]) that the grade [settings] turns into balanced light
     * scaled by [gain]: the light of each channel ([LogColor.decodeValue]) scaled by [lightScale] and encoded again.
     * The grade's table on these is the original's grade with the recovery ([ColorGrade.logBalanced]).
     */
    fun recode(codes: DoubleArray, settings: ColorSettings, profile: LUTInput, gain: Double): DoubleArray {
        if (gain == 1.0) return codes
        val light = DoubleArray(3) { LogColor.decodeValue(codes[it]) }
        val scene = LogColor.decode(codes, profile)
        val gains = ColorGrade.logGains(settings)
        val before = max(0.0, (0..2).sumOf { max(0.0, scene[it]) * gains[it] * LUMA[it] })
        val scale = lightScale(before, gain, settings.highlights ?: 0.0)
        return DoubleArray(3) { LogColor.encodeValue(light[it] * scale) }
    }

    /**
     * A frame of a log recording's code values ([codes], three a pixel row by row) recoded for the grade [settings]
     * with its recovery: what the export's passes draw before the grade's table.
     */
    fun recodeFrame(codes: DoubleArray, width: Int, height: Int, settings: ColorSettings, profile: LUTInput): DoubleArray {
        val strength = strength(settings)
        if (strength == 0.0) return codes
        val g = gains(balancedLuminance(codes, settings, profile), width, height, strength)
        val out = DoubleArray(codes.size)
        for (i in 0 until width * height) {
            recode(doubleArrayOf(codes[i * 3], codes[i * 3 + 1], codes[i * 3 + 2]), settings, profile, g[i]).copyInto(out, i * 3)
        }
        return out
    }

    /** The luminance of the balanced light ([ColorGrade.logBalanced]) of each pixel of a frame of log [codes], three values a pixel. */
    fun balancedLuminance(codes: DoubleArray, settings: ColorSettings, profile: LUTInput): DoubleArray = DoubleArray(codes.size / 3) { i ->
        val balanced = ColorGrade.logBalanced(settings, profile, codes[i * 3], codes[i * 3 + 1], codes[i * 3 + 2])
        max(0.0, (0..2).sumOf { balanced[it] * LUMA[it] })
    }

    /**
     * The mean coefficients of a frame quantised for a picture of the preview: [a] in 0…1 and [b] over [B_RANGE]
     * from [B_LOW], each as sixteen bits in two 8-bit channels, high byte first. Linear filtering of the two bytes
     * filters the value, so the picture can be drawn larger with the GPU's filtering.
     */
    fun packed(coefficients: Coefficients): Pair<IntArray, IntArray> {
        fun pack(value: Double): Int {
            val v = (value.coerceIn(0.0, 1.0) * 65535).roundToInt()
            return (0xFF shl 24) or ((v shr 8) shl 16) or ((v and 0xff) shl 8)
        }
        return IntArray(coefficients.a.size) { pack(coefficients.a[it]) } to IntArray(coefficients.b.size) { pack((coefficients.b[it] - B_LOW) / B_RANGE) }
    }

    /** The span of the offset [Coefficients.b] when [packed]: a mean log luminance from 2⁻²⁴ to 2⁸. */
    const val B_LOW = -24.0
    const val B_RANGE = 32.0

    private val LUMA = doubleArrayOf(0.2126, 0.7152, 0.0722)

    private fun log2(x: Double) = ln(x) / ln(2.0)
}
