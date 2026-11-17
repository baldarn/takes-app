package app.takes.core

import kotlin.math.pow

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
     * Highlights follows them in linear light, as the original's grade applies
     * it before any look (YouRec/VideoToolCompositor.swift:503-511, [LogColor.highlights]).
     * Local recovery applies to log recordings only ([LocalRecovery]).
     */
    fun balance(settings: ColorSettings, red: Double, green: Double, blue: Double): DoubleArray {
        val exposure = settings.exposure ?: 0.0
        val warmth = settings.warmth ?: 0.0
        val tint = settings.tint ?: 0.0
        val highlights = settings.highlights ?: 0.0
        if (exposure == 0.0 && warmth == 0.0 && tint == 0.0 && highlights == 0.0) return doubleArrayOf(red, green, blue)
        val gain = 2.0.pow(exposure)
        val gains = doubleArrayOf(1 + 0.12 * warmth, 1 - 0.10 * tint, 1 - 0.12 * warmth)
        val input = doubleArrayOf(red, green, blue)
        val linear = LogColor.highlights(DoubleArray(3) { toLinear(input[it].coerceIn(0.0, 1.0)) * gain * gains[it] }, highlights)
        return DoubleArray(3) { toDisplay(linear[it].coerceIn(0.0, 1.0)) }
    }

    /**
     * One color of a log recording through the whole grade, as the original's
     * compositor grades it (YouRec/VideoToolCompositor.swift:505-529): the code
     * values decoded to linear Rec.709 light ([LogColor.decode]), exposure,
     * warmth, tint and Highlights on that light with its headroom kept, the
     * viewing transform ([LogColor.display]), then the look on the picture as
     * viewed, or an imported LUT on what it expects ([LogColor.lutInput]),
     * at the grade's strength. The result is display-encoded 0–1.
     */
    fun applyLog(settings: ColorSettings, lut: CubeLUT?, input: LUTInput?, profile: LUTInput, red: Double, green: Double, blue: Double): DoubleArray =
        fromLogBalanced(settings, lut, input, logBalanced(settings, profile, red, green, blue))

    /**
     * The light of a log recording's code values after exposure, warmth, tint and Highlights: what the original's
     * local recovery works on (YouRec/VideoToolCompositor.swift:505-513), linear Rec.709 with its headroom.
     */
    fun logBalanced(settings: ColorSettings, profile: LUTInput, red: Double, green: Double, blue: Double): DoubleArray {
        val scene = LogColor.decode(doubleArrayOf(red, green, blue), profile)
        val gains = logGains(settings)
        return LogColor.highlights(DoubleArray(3) { maxOf(0.0, scene[it]) * gains[it] }, settings.highlights ?: 0.0)
    }

    /** Exposure and the channel gains of warmth and tint, together, on each channel of a log recording's light. */
    fun logGains(settings: ColorSettings): DoubleArray {
        val gain = 2.0.pow(settings.exposure ?: 0.0)
        val warmth = settings.warmth ?: 0.0
        val tint = settings.tint ?: 0.0
        return doubleArrayOf(gain * (1 + 0.12 * warmth), gain * (1 - 0.10 * tint), gain * (1 - 0.12 * warmth))
    }

    /** The rest of [applyLog] from the balanced light ([logBalanced]): the viewing transform, then the look or LUT. */
    fun fromLogBalanced(settings: ColorSettings, lut: CubeLUT?, input: LUTInput?, balanced: DoubleArray): DoubleArray {
        val viewed = LogColor.display(balanced)
        val shown = DoubleArray(3) { toDisplay(viewed[it]) }
        if (settings.amount <= 0 || (lut == null && settings.look == ColorLook.Natural)) return shown
        val graded = if (lut != null) {
            val entry = LogColor.lutInput(balanced, viewed, input ?: LUTInput.Rec709)
            lut.sample(entry[0].toFloat(), entry[1].toFloat(), entry[2].toFloat()).let { DoubleArray(3) { i -> it[i].toDouble() } }
        } else look(settings.look, shown[0], shown[1], shown[2])
        return DoubleArray(3) { (shown[it] + (graded[it] - shown[it]) * minOf(1.0, settings.amount)).coerceIn(0.0, 1.0) }
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
     * file when the settings use an imported LUT. With [sourceLog] the table
     * takes the code values of a recording in that log profile ([applyLog]).
     */
    fun table(settings: ColorSettings, lut: CubeLUT? = null, sourceLog: LUTInput? = null): FloatArray {
        val table = FloatArray(DIMENSION * DIMENSION * DIMENSION * 3)
        val last = (DIMENSION - 1).toDouble()
        var index = 0
        for (blue in 0 until DIMENSION) for (green in 0 until DIMENSION) for (red in 0 until DIMENSION) {
            val color = if (sourceLog == null) apply(settings, lut, red / last, green / last, blue / last)
            else applyLog(settings, lut, settings.imported?.input, sourceLog, red / last, green / last, blue / last)
            table[index++] = color[0].toFloat(); table[index++] = color[1].toFloat(); table[index++] = color[2].toFloat()
        }
        return table
    }
}
