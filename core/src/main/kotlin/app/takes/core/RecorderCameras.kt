package app.takes.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A camera as the recorder's controls see it: which side of the phone it is
 * on and how its lens compares with the main one (below 1 an ultrawide,
 * well above 1 a tele).
 */
data class RecorderCamera(val id: String, val front: Boolean, val lensZoom: Double = 1.0) {
    val ultrawide: Boolean get() = lensZoom < ULTRAWIDE_BELOW
    val tele: Boolean get() = lensZoom > TELE_ABOVE
    val main: Boolean get() = !ultrawide && !tele

    companion object {
        const val ULTRAWIDE_BELOW = 0.9
        const val TELE_ABOVE = 1.5
    }
}

/**
 * Which camera two of the recorder's buttons lead to, and the zoom presets of
 * a camera, as in the original app (YouRec/CaptureController.swift:1255-1271
 * and 1508-1511, YouRec/RecorderView.swift:1049-1053 and 1077-1090).
 */
object RecorderCameras {
    /**
     * The camera the flip button changes to from [current]: the one used
     * last on the other side of the phone, else the first one there; null
     * when the other side has no camera (CaptureController.swift:1262-1267).
     */
    fun opposite(cameras: List<RecorderCamera>, current: String?, lastFront: String?, lastRear: String?): RecorderCamera? {
        val now = cameras.firstOrNull { it.id == current } ?: return null
        val choices = cameras.filter { it.front != now.front }
        val remembered = if (now.front) lastRear else lastFront
        return choices.firstOrNull { it.id == remembered } ?: choices.firstOrNull()
    }

    /**
     * From a rear ultrawide that is a camera of its own, the 1× preset
     * changes to the rear main camera instead of magnifying the ultrawide;
     * null everywhere else (CaptureController.swift:1508-1511).
     */
    fun mainAtOne(cameras: List<RecorderCamera>, current: String?): RecorderCamera? {
        if (cameras.none { it.id == current && !it.front && it.ultrawide }) return null
        return cameras.firstOrNull { !it.front && it.main }
    }

    /** The other values after the lens minimum, as the original shows them (RecorderView.swift:1050). */
    val PRESETS = listOf(1.0, 2.0, 3.0, 5.0, 6.0)

    /**
     * The zoom presets of a lens that reaches from [minimum] to [maximum]:
     * its minimum when that is wider than 1×, which is the original's 0.5 on
     * most phones and the true value on the others, then 1, 2, 3, 5 and 6 as
     * far as the lens goes. [maximum] is already capped (see
     * [PinchZoom.maximum]).
     */
    fun presets(minimum: Double, maximum: Double): List<Double> {
        val wide = if (minimum < 1 - PinchZoom.PRESET_TOLERANCE) listOf(minimum) else emptyList()
        return wide + PRESETS.filter { it >= minimum - EDGE && it <= maximum + EDGE }
    }

    /** A lens that reports 0.99999 or 5.00001 still reaches the preset. */
    private const val EDGE = 0.001

    /** A preset as its button shows it: "1×", "0.5×", "0.6×" (RecorderView.swift:1103). */
    fun presetLabel(zoom: Double): String {
        val tenths = (zoom * 10).roundToInt() / 10.0
        return (if (abs(tenths - tenths.roundToInt()) < 1e-9) tenths.roundToInt().toString() else String.format(Locale.ROOT, "%.1f", tenths)) + "×"
    }
}
