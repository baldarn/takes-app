package app.takes.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.pow

/** The built-in looks, with the original app's names and math. */
@Serializable
enum class ColorLook(val title: String) {
    @SerialName("natural") Natural("Natural"),
    @SerialName("warm") Warm("Warm"),
    @SerialName("cool") Cool("Cool shadows"),
    @SerialName("cinema") Cinema("Cinema"),
    @SerialName("mono") Mono("Mono"),
    @SerialName("portrait") Portrait("Portrait"),
    @SerialName("daylight") Daylight("Daylight"),
    @SerialName("goldenHour") GoldenHour("Golden hour"),
    @SerialName("mutedFilm") MutedFilm("Muted film"),
    @SerialName("richContrast") RichContrast("Rich contrast"),
    @SerialName("pastel") Pastel("Pastel"),
    @SerialName("night") Night("Night"),
    @SerialName("silver") Silver("Silver"),
    @SerialName("ink") Ink("Ink"),
}

/** A .cube file copied into the project. Its output must be Rec.709. */
@Serializable
data class ImportedLUT(val filename: String, val name: String) {
    val valid: Boolean
        get() = name.length <= 120 && filename.startsWith("effect-") && filename.endsWith(".cube") &&
            filename.none { it == '/' || it == '\\' }
}

/**
 * A clip's color: a look or an imported LUT at some strength, over an
 * exposure, warmth and tint correction that stays in place at zero strength.
 */
@Serializable
data class ColorSettings(
    val look: ColorLook = ColorLook.Natural,
    val amount: Double = 1.0,
    val imported: ImportedLUT? = null,
    /** Stops, as in a camera: +1 doubles the light. */
    val exposure: Double = 0.0,
    val warmth: Double = 0.0,
    val tint: Double = 0.0,
) {
    val hasBalance: Boolean get() = exposure != 0.0 || warmth != 0.0 || tint != 0.0
    val isIdentity: Boolean get() = !hasBalance && look == ColorLook.Natural && imported == null
    val title: String get() = imported?.name ?: if (look == ColorLook.Natural && hasBalance) "Color balance" else look.title
    val valid: Boolean
        get() = amount.isFinite() && amount in 0.0..1.0 && exposure.isFinite() && exposure in -4.0..4.0 &&
            warmth.isFinite() && warmth in -1.0..1.0 && tint.isFinite() && tint in -1.0..1.0 && (imported?.valid ?: true)
}

/**
 * Turns color settings into one lookup table that both the preview and the
 * export apply, so they cannot disagree. The table maps display-encoded
 * Rec.709 colors (what an SDR video holds) to display-encoded colors.
 */
object ColorGrade {
    const val DIMENSION = 33

    /** One color through a built-in look. Values are display-encoded 0–1; the result may leave that range. */
    fun look(look: ColorLook, red: Double, green: Double, blue: Double): DoubleArray {
        var rgb = doubleArrayOf(red, green, blue)
        val luma = red * 0.2126 + green * 0.7152 + blue * 0.0722
        fun each(change: (Double) -> Double) { rgb = DoubleArray(3) { change(rgb[it]) } }
        when (look) {
            ColorLook.Natural -> Unit
            ColorLook.Warm -> { rgb[0] = rgb[0].pow(0.92); rgb[2] = rgb[2].pow(1.10) }
            ColorLook.Cool -> { rgb[0] = rgb[0].pow(1.08); rgb[2] = rgb[2].pow(0.92) }
            ColorLook.Mono -> rgb = doubleArrayOf(luma, luma, luma)
            ColorLook.Cinema -> {
                each { (0.5 + (it - 0.5) * 1.12).coerceIn(0.0, 1.0) }
                rgb[0] += 0.035 * luma; rgb[1] += 0.012 * (1 - luma); rgb[2] += 0.035 * (1 - luma)
                each { luma + (it - luma) * 0.85 }
            }
            ColorLook.Portrait -> {
                each { luma + (it - luma) * 0.92 }
                rgb[0] += 0.018 * luma * (1 - luma); rgb[2] -= 0.025 * luma * (1 - luma)
                each { it * 0.95 + 0.025 }
            }
            ColorLook.Daylight -> {
                each { 0.5 + (it - 0.5) * 1.04 }
                each { luma + (it - luma) * 1.08 }
            }
            ColorLook.GoldenHour -> {
                rgb[0] += 0.12 * luma * (1 - luma); rgb[1] += 0.025 * luma
                rgb[2] *= 0.86
                each { maxOf(0.0, it).pow(0.95) }
            }
            ColorLook.MutedFilm -> {
                each { 0.055 + 0.89 * (luma + (it - luma) * 0.72) }
                rgb[2] += 0.018 * (1 - luma)
            }
            ColorLook.RichContrast -> each { val x = it.coerceIn(0.0, 1.0); x * x * (3 - 2 * x) * 0.55 + x * 0.45 }
            ColorLook.Pastel -> each { 0.075 + 0.88 * maxOf(0.0, luma + (it - luma) * 0.65).pow(0.91) }
            ColorLook.Night -> {
                each { maxOf(0.0, it * 0.94 - 0.012) }
                rgb[2] += 0.045 * (1 - luma); rgb[1] += 0.012 * (1 - luma)
            }
            ColorLook.Silver -> {
                val y = maxOf(0.0, rgb[0] * 0.3 + rgb[1] * 0.6 + rgb[2] * 0.1).pow(0.92)
                rgb = doubleArrayOf(y * 0.98, y, minOf(1.0, y * 1.015))
            }
            ColorLook.Ink -> {
                val y = ((luma - 0.08) / 0.84).coerceIn(0.0, 1.0)
                val dense = y * y * (3 - 2 * y)
                rgb = doubleArrayOf(dense, dense, dense)
            }
        }
        return rgb
    }

    private fun toLinear(value: Double): Double = if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)

    private fun toDisplay(value: Double): Double = if (value <= 0.0031308) value * 12.92 else 1.055 * value.pow(1 / 2.4) - 0.055

    /**
     * Exposure, warmth and tint. Exposure multiplies the light itself, so it
     * is applied in linear terms. Warmth and tint are simple channel gains: an
     * approximation of the original's temperature filter, not a match for it.
     */
    fun balance(settings: ColorSettings, red: Double, green: Double, blue: Double): DoubleArray {
        if (!settings.hasBalance) return doubleArrayOf(red, green, blue)
        val gain = 2.0.pow(settings.exposure)
        val gains = doubleArrayOf(1 + 0.12 * settings.warmth, 1 - 0.10 * settings.tint, 1 - 0.12 * settings.warmth)
        val input = doubleArrayOf(red, green, blue)
        return DoubleArray(3) { toDisplay((toLinear(input[it].coerceIn(0.0, 1.0)) * gain * gains[it]).coerceIn(0.0, 1.0)) }
    }

    /** One color through the whole grade. */
    fun apply(settings: ColorSettings, lut: CubeLUT?, red: Double, green: Double, blue: Double): DoubleArray {
        val balanced = balance(settings, red, green, blue)
        val graded = if (lut != null) lut.sample(balanced[0].toFloat(), balanced[1].toFloat(), balanced[2].toFloat()).let { DoubleArray(3) { i -> it[i].toDouble() } }
        else look(settings.look, balanced[0], balanced[1], balanced[2])
        // Strength blends from the balanced picture to the look, never back to the uncorrected one.
        return DoubleArray(3) { (balanced[it] + (graded[it] - balanced[it]) * settings.amount).coerceIn(0.0, 1.0) }
    }

    /**
     * The grade as a cube of [DIMENSION] points per side: three floats per
     * point, red changing fastest, then green, then blue. [lut] is the parsed
     * file when the settings use an imported LUT.
     */
    fun table(settings: ColorSettings, lut: CubeLUT? = null): FloatArray {
        val table = FloatArray(DIMENSION * DIMENSION * DIMENSION * 3)
        val last = (DIMENSION - 1).toDouble()
        var index = 0
        for (blue in 0 until DIMENSION) for (green in 0 until DIMENSION) for (red in 0 until DIMENSION) {
            val color = apply(settings, lut, red / last, green / last, blue / last)
            table[index++] = color[0].toFloat(); table[index++] = color[1].toFloat(); table[index++] = color[2].toFloat()
        }
        return table
    }
}
