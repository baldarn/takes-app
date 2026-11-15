package app.takes.core

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The front light of the recorder: a ring of light at the edge of the
 * display that lights the face in front of it and is never recorded, as in
 * the original app (YouRec/RecorderFrontLight.swift:4-162).
 *
 * Lengths are the original's points, which are density-independent pixels
 * here.
 */
object FrontLight {
    /** How wide the ring is, as a share of the short side of the screen (RecorderFrontLight.swift:16-26). */
    enum class Width(val fraction: Double) { Thin(0.045), Medium(0.085), Wide(0.14) }

    /** The colour of the light (RecorderFrontLight.swift:28-39). The custom one is chosen by the person. */
    enum class Tone(val red: Double, val green: Double, val blue: Double) {
        White(1.0, 1.0, 1.0), Warm(1.0, 0.82, 0.62), Cool(0.72, 0.87, 1.0), Custom(1.0, 0.70, 0.79)
    }

    /** A colour with each part from 0 to 1. */
    data class Rgb(val red: Double, val green: Double, val blue: Double)

    val DEFAULT_WIDTH = Width.Medium
    val DEFAULT_TONE = Tone.White
    const val DEFAULT_OPACITY = 1.0

    /** The opacity moves in steps of 5 % (RecorderFrontLight.swift:121). */
    const val OPACITY_STEP = 0.05

    /**
     * The hue of the custom colour until the person chooses one, in degrees:
     * the original's rose (RecorderFrontLight.swift:50). Android has no
     * system colour picker, so the custom colour is a hue at the strength of
     * that rose, pale enough to light a face.
     */
    const val DEFAULT_HUE = 342.0
    const val CUSTOM_SATURATION = 0.30

    fun width(name: String?): Width = Width.entries.firstOrNull { it.name == name } ?: DEFAULT_WIDTH

    /** The fourth swatch of the original was once a fixed rose, saved as "Rose" (RecorderFrontLight.swift:41-44). */
    fun tone(name: String?): Tone = if (name == "Rose") Tone.Custom else Tone.entries.firstOrNull { it.name == name } ?: DEFAULT_TONE

    fun opacity(value: Double): Double =
        if (value.isFinite()) minOf(1.0, maxOf(0.0, (value / OPACITY_STEP).roundToInt() * OPACITY_STEP)) else DEFAULT_OPACITY

    fun hue(value: Double): Double = if (value.isFinite()) ((value % 360) + 360) % 360 else DEFAULT_HUE

    /** The colour of [tone]; the custom one is [hue] at a fixed, pale strength. */
    fun color(tone: Tone, hue: Double = DEFAULT_HUE): Rgb =
        if (tone == Tone.Custom) pale(hue(hue)) else Rgb(tone.red, tone.green, tone.blue)

    /** A hue at full brightness and [CUSTOM_SATURATION]. */
    private fun pale(hue: Double): Rgb {
        val sector = hue / 60
        val rising = sector - Math.floor(sector)
        val low = 1 - CUSTOM_SATURATION
        val up = 1 - CUSTOM_SATURATION * (1 - rising)
        val down = 1 - CUSTOM_SATURATION * rising
        return when (sector.toInt() % 6) {
            0 -> Rgb(1.0, up, low)
            1 -> Rgb(down, 1.0, low)
            2 -> Rgb(low, 1.0, up)
            3 -> Rgb(low, down, 1.0)
            4 -> Rgb(up, low, 1.0)
            else -> Rgb(1.0, low, down)
        }
    }

    /** How wide the ring is on a screen of [width] by [height] (RecorderFrontLight.swift:57). */
    fun thickness(width: Double, height: Double, ring: Width): Double = minOf(width, height) * ring.fraction

    /**
     * The corner radius of the opening inside the ring, on a screen whose own
     * corners have [cornerRadius]: the ring follows the corner, and the
     * opening never gets sharper than a small radius
     * (RecorderFrontLight.swift:65).
     */
    fun openingRadius(cornerRadius: Double, thickness: Double): Double = maxOf(minOf(cornerRadius, 10.0), cornerRadius - thickness)

    /** Half the side of the bulb's touch target (RecorderFrontLight.swift:59). */
    const val BULB_RADIUS = 22.0

    /**
     * How far from the two edges the centre of the bulb sits, so that its
     * round target nests against a rounded corner with a small margin
     * (RecorderFrontLight.swift:60).
     */
    fun bulbInset(cornerRadius: Double): Double = BULB_RADIUS + maxOf(0.0, cornerRadius - BULB_RADIUS) * (1 - 1 / sqrt(2.0)) + 3

    /** The plate under the recorder's controls turns opaque while the light is on, so they stay readable on it (YouRec/RecorderView.swift:67). */
    const val PLATE_WHITE = 0.14
}
