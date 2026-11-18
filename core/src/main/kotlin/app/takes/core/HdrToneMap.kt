package app.takes.core

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

/**
 * HLG footage carried down to SDR before a creative effect, with an explicit source headroom, as the original does
 * since it fixed the brightness of HDR effects (YouRec/VideoEffects/MetalVideoEffects.swift:206-221, 605-613): the
 * picture's light is mapped from [SOURCE_HEADROOM] times reference white down to reference white, so that highlights
 * keep their detail instead of being clipped at SDR white.
 *
 * The original asks Core Image's `CIToneMapHeadroom`, whose curve Apple does not publish. The port uses ITU-R
 * BT.2390's EETF (BT.2390-10, section 5.4.1), the Hermite knee in PQ that maps a mastering peak onto a display's
 * peak: here the source's peak (1000 nits, [SOURCE_HEADROOM] times the 203-nit reference white) onto the 203 nits of
 * SDR white. Below the knee (about 88 nits, 0.43 of reference white) light is untouched. As Android's own tone mapper
 * does (frameworks/native/libs/tonemap), the curve is applied to the brightest of the three channels and the same gain
 * given to all three, so hues and saturation are kept and no channel goes past white.
 *
 * Light is display light counted from reference white: HLG's scene light through BT.2100's OOTF for a 1000-nit
 * display (system gamma 1.2), divided by 203 nits (ITU-R BT.2408), as the original's 1000/203.
 */
object HdrToneMap {
    /** The source's headroom over reference white: camera HLG's 1000 nits over 203 (MetalVideoEffects.swift:214). */
    const val SOURCE_HEADROOM = 1000.0 / 203.0

    /** HLG's reference white in nits (ITU-R BT.2408), the 1.0 of display light here. */
    const val REFERENCE_NITS = 203.0

    /** The nominal peak of an HLG display, for BT.2100's OOTF. */
    const val PEAK_NITS = 1000.0

    /** BT.2100's system gamma for a 1000-nit display. */
    const val SYSTEM_GAMMA = 1.2

    private const val M1 = 2610.0 / 16384
    private const val M2 = 2523.0 / 4096 * 128
    private const val C1 = 3424.0 / 4096
    private const val C2 = 2413.0 / 4096 * 32
    private const val C3 = 2392.0 / 4096 * 32

    /** SMPTE ST 2084's (PQ) signal for [nits]. */
    fun pq(nits: Double): Double {
        val y = (max(nits, 0.0) / 10000).pow(M1)
        return ((C1 + C2 * y) / (1 + C3 * y)).pow(M2)
    }

    /** The nits of PQ's signal [e]. */
    fun pqNits(e: Double): Double {
        val p = e.coerceIn(0.0, 1.0).pow(1 / M2)
        return 10000 * (max(p - C1, 0.0) / (C2 - C3 * p)).pow(1 / M1)
    }

    /**
     * BT.2390's EETF on display light [light] counted from reference white, from [headroom] times reference white
     * down to reference white (no black lift: both blacks are 0).
     */
    fun eetf(light: Double, headroom: Double = SOURCE_HEADROOM): Double {
        if (light <= 0) return 0.0
        if (headroom <= 1) return light.coerceAtMost(1.0)
        val sourcePeak = pq(headroom * REFERENCE_NITS)
        val e1 = pq(light * REFERENCE_NITS) / sourcePeak
        val maxLum = pq(REFERENCE_NITS) / sourcePeak
        val knee = 1.5 * maxLum - 0.5
        val e2 = when {
            e1 < knee -> e1
            e1 >= 1 -> maxLum
            else -> {
                val t = (e1 - knee) / (1 - knee)
                val t2 = t * t
                val t3 = t2 * t
                (2 * t3 - 3 * t2 + 1) * knee + (t3 - 2 * t2 + t) * (1 - knee) + (-2 * t3 + 3 * t2) * maxLum
            }
        }
        return pqNits(e2 * sourcePeak) / REFERENCE_NITS
    }

    /** The scene light of HLG's signal [e], 0…1 (BT.2100). */
    fun hlgScene(e: Double): Double {
        val v = e.coerceIn(0.0, 1.0)
        return if (v <= 0.5) v * v / 3 else (exp((v - 0.55991073) / 0.17883277) + 0.28466892) / 12
    }

    /** The display light of HLG's signal [rgb] (BT.2020), counted from reference white: BT.2100's OOTF at 1000 nits. */
    fun hlgDisplay(rgb: DoubleArray): DoubleArray {
        val scene = DoubleArray(3) { hlgScene(rgb[it]) }
        val ys = 0.2627 * scene[0] + 0.6780 * scene[1] + 0.0593 * scene[2]
        val gain = if (ys > 0) ys.pow(SYSTEM_GAMMA - 1) * PEAK_NITS / REFERENCE_NITS else 0.0
        return DoubleArray(3) { scene[it] * gain }
    }

    /** [light] (any primaries) tone-mapped: the gain of [eetf] on its brightest channel, given to all three. */
    fun toneMapped(light: DoubleArray, headroom: Double = SOURCE_HEADROOM): DoubleArray {
        val brightest = maxOf(light[0], light[1], light[2])
        if (brightest <= 0) return DoubleArray(3)
        val gain = eetf(brightest, headroom) / brightest
        return DoubleArray(3) { light[it] * gain }
    }

    private val BT2020_TO_BT709 = arrayOf(
        doubleArrayOf(1.6604910, -0.5876411, -0.0728499),
        doubleArrayOf(-0.1245505, 1.1328999, -0.0083494),
        doubleArrayOf(-0.0181508, -0.1005789, 1.1187297),
    )

    /** SDR video's signal (BT.709's curve) of linear [l], 0…1. */
    fun sdrSignal(l: Double): Double {
        val a = l.coerceIn(0.0, 1.0)
        return if (a < 0.018) a * 4.5 else 1.099 * a.pow(0.45) - 0.099
    }

    /**
     * An HLG picture's signal [rgb] (BT.2020) as SDR video's signal in BT.709, as the effects take it: display light,
     * tone-mapped from [headroom], into BT.709's primaries, held inside 0…1 and encoded by BT.709's curve. This is
     * `hlgToDisplaySdr` of the export's shader (app/HdrMovie.kt), which a pixel test checks against it.
     */
    fun hlgToSdr(rgb: DoubleArray, headroom: Double = SOURCE_HEADROOM): DoubleArray {
        val mapped = toneMapped(hlgDisplay(rgb), headroom)
        return DoubleArray(3) { row -> sdrSignal((0..2).sumOf { BT2020_TO_BT709[row][it] * mapped[it] }) }
    }
}
