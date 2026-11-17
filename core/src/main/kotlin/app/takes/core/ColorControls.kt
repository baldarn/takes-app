package app.takes.core

import kotlin.math.roundToLong

/**
 * The sliders of the Color tool, with the original's ranges and steps
 * (YouRec/ColorGradeControls.swift:25, 41, 63, 70-72, 152): strength, local
 * highlight recovery and Highlights in hundredths, exposure, warmth and tint
 * in twentieths.
 */
enum class ColorControl(val low: Double, val high: Double, val step: Double) {
    Strength(0.0, 1.0, 0.01),
    LocalRecovery(0.0, 1.0, 0.01),
    Highlights(-1.0, 1.0, 0.01),
    Exposure(-4.0, 4.0, 0.05),
    Warmth(-1.0, 1.0, 0.05),
    Tint(-1.0, 1.0, 0.05),
    ;

    /** [value] on this slider's grid and inside its range, free of rounding noise. */
    fun stepped(value: Double): Double {
        val steps = Math.round(value.coerceIn(low, high) / step)
        // The step in thousandths is a whole number, so the product is exact before the last division.
        return (steps * (step * 1000).roundToLong()) / 1000.0
    }

    /**
     * The value as the original writes it beside its slider: strength as a
     * percentage, as local highlight recovery (ColorGradeControls.swift:37),
     * Highlights as a signed percentage, the balance with two
     * decimals and exposure in EV (ColorGradeControls.swift:27, 57, 151).
     */
    fun label(value: Double): String = when (this) {
        Strength, LocalRecovery -> "${Math.round(value * 100)}%"
        Highlights -> "%+.0f".format(java.util.Locale.ROOT, value * 100)
        Exposure -> "%+.2f EV".format(java.util.Locale.ROOT, value)
        Warmth, Tint -> "%+.2f".format(java.util.Locale.ROOT, value)
    }
}
