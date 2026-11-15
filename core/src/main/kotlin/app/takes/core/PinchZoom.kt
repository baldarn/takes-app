package app.takes.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.truncate

/**
 * One two-finger pinch on the recorder's picture, as the original app maps it
 * to a zoom (YouRec/RecorderZoomViewport.swift:116-214), and the numbers of
 * its zoom controls.
 *
 * The pinch is measured from where the fingers went down, never from the
 * previous event, so a small change in finger pressure at a lens limit cannot
 * reverse the zoom. A first stretch of travel changes nothing, which keeps
 * the jitter of two fingers moving in parallel out of the zoom, and the zoom
 * then eases in without a step.
 *
 * With a zoom time of zero every change is applied at once. With a time set
 * the pinch only composes the move: nothing reaches the camera until the
 * fingers lift, and the move then runs over [seconds] on the curve of
 * [ZoomTransition].
 *
 * Zoom values are the camera's own. The original multiplies them by a
 * display factor for the read-out and the presets (the camera's 1 can be
 * shown as 0.5×); on Android the zoom ratio already is the shown value, so
 * the factor defaults to 1.
 *
 * Only the zoom of the gesture is here. Its second half, the pan of the
 * picture inside the lens, needs the original's `CameraFraming`
 * (YouRec/VideoEffects/CaptureZoomHistory.swift:5-32) and is not ported.
 *
 * Distances are the original's points, which are density-independent pixels
 * here.
 */
data class PinchZoom(
    /** The zoom when the fingers went down. */
    val startZoom: Double,
    /** The distance between the fingers when they went down. */
    val startSpan: Double,
    /** The zoom time chosen in the recorder; zero applies each change at once. */
    val seconds: Double,
    val minimum: Double,
    val maximum: Double,
    /** The zoom the pinch has reached. */
    val zoom: Double = startZoom,
    /** Whether an instant pinch already changed the camera, so the release saves the zoom even where it started. */
    val applied: Boolean = false,
) {
    /** A pinch and what to do with the camera now, if anything. */
    data class Step(val pinch: PinchZoom, /** The zoom to apply at once without saving it; null leaves the camera alone. */ val apply: Double?)

    /** Whether the move waits for the release. */
    val timed: Boolean get() = seconds > 0

    /**
     * How much larger the picture is than when the pinch began. While a timed
     * move is composed the original draws the preview at this scale
     * (RecorderZoomViewport.swift:169,188).
     */
    val scale: Double get() = zoom / startZoom

    /** Whether the pinch moved the zoom enough to count (RecorderZoomViewport.swift:174, the zoom half of it). */
    val changed: Boolean get() = abs(scale - 1) > CHANGE

    /**
     * The pinch with the fingers [span] apart. An instant pinch gives the
     * zoom to apply whenever it differs from the last one
     * (RecorderZoomViewport.swift:182-186); a timed one never does.
     */
    fun moved(span: Double): Step {
        val next = zoom(startZoom, startSpan, span, minimum, maximum)
        val live = !timed && next != zoom
        return Step(copy(zoom = next, applied = applied || live), next.takeIf { live })
    }

    /**
     * The zoom to move to over [seconds] and to remember, once the fingers
     * lift; null when the pinch changed nothing
     * (RecorderZoomViewport.swift:175-180).
     */
    fun released(): Double? = zoom.takeIf { changed || applied }

    companion object {
        /**
         * How far the span must change before the zoom moves at all, as the
         * natural logarithm of span over starting span: 2.2 %
         * (RecorderZoomViewport.swift:152).
         */
        const val DEAD_ZONE = 0.022

        /** The further change, in the same unit, over which the zoom eases in to follow the fingers (RecorderZoomViewport.swift:152). */
        const val EASE_IN = 0.033

        /** Points of change in span, or of travel of the midpoint, that begin the gesture (RecorderZoomViewport.swift:302). */
        const val BEGIN_TRAVEL = 3.0

        /** The shortest span that is measured, so two fingers on one spot never divide by zero (RecorderZoomViewport.swift:291). */
        const val MINIMUM_SPAN = 1.0

        /** A change of scale beyond this counts as a zoom (RecorderZoomViewport.swift:174). */
        const val CHANGE = 0.005

        /** The original never zooms past this, whatever the lens offers (YouRec/CaptureController.swift:1786). */
        const val MAXIMUM_ZOOM = 10.0

        /** The original's zoom presets, as shown (YouRec/RecorderView.swift:1050). */
        val PRESETS = listOf(0.5, 1.0, 2.0, 3.0, 5.0, 6.0)

        /** A preset is marked as selected while the shown zoom is this near it (RecorderView.swift:1078). */
        const val PRESET_TOLERANCE = 0.04

        /** A double tap on a preset takes this long, whatever zoom time is set (RecorderView.swift:1079). */
        const val QUICK_SECONDS = 1.0

        /** Where the badge sits: this share of the picture's height from the top, centred (RecorderZoomViewport.swift:81). */
        const val BADGE_HEIGHT = 0.27

        /** Points of vertical drag on the zoom time for each step of it (RecorderView.swift:1164). */
        const val DURATION_DRAG_STEP = 14.0

        /** Seconds each step of that drag adds or removes (RecorderView.swift:1165). */
        const val DURATION_STEP = 0.5

        /** Points a drag on the zoom time travels before it starts (RecorderView.swift:1155). */
        const val DURATION_DRAG_START = 8.0

        /** Seconds the hint "Swipe up/down to change zoom time" stays after a tap (RecorderView.swift:1063). */
        const val DURATION_HINT_SECONDS = 2.5

        /**
         * A pinch beginning at [zoom] with the fingers [span] apart, on a
         * lens that reaches from [minimum] to [maximum].
         */
        fun begin(zoom: Double, span: Double, seconds: Double, minimum: Double, maximum: Double): PinchZoom =
            PinchZoom(zoom, maxOf(MINIMUM_SPAN, span), ZoomTransition.boundedDuration(seconds), minimum, maximum)

        /** Whether two fingers have moved enough to begin a pinch: in span, or together by [travel] (RecorderZoomViewport.swift:302). */
        fun begins(startSpan: Double, span: Double, travel: Double): Boolean =
            abs(maxOf(MINIMUM_SPAN, span) - maxOf(MINIMUM_SPAN, startSpan)) > BEGIN_TRAVEL || travel > BEGIN_TRAVEL

        /** The pinch so far in stops: the natural logarithm of span over starting span (RecorderZoomViewport.swift:151). */
        fun stops(startSpan: Double, span: Double): Double = ln(maxOf(MINIMUM_SPAN, span) / maxOf(MINIMUM_SPAN, startSpan))

        /**
         * The part of [stops] that reaches the zoom: none inside the dead
         * zone, all of it past the ease, a smooth step between
         * (RecorderZoomViewport.swift:152-153).
         */
        fun eased(stops: Double): Double {
            val t = maxOf(0.0, minOf(1.0, (abs(stops) - DEAD_ZONE) / EASE_IN))
            return stops * t * t * (3 - 2 * t)
        }

        /** The zoom a pinch from [startSpan] to [span] gives, starting at [startZoom], kept inside the lens (RecorderZoomViewport.swift:151-153). */
        fun zoom(startZoom: Double, startSpan: Double, span: Double, minimum: Double, maximum: Double): Double =
            minOf(maximum, maxOf(minimum, startZoom * exp(eased(stops(startSpan, span)))))

        /** The most the recorder zooms on a lens that reaches [lensMaximum] (CaptureController.swift:1786). */
        fun maximum(lensMaximum: Double): Double = minOf(MAXIMUM_ZOOM, lensMaximum)

        /** The presets a lens from [minimum] to [maximum] can reach, as shown (RecorderView.swift:1049-1053). */
        fun presets(minimum: Double, maximum: Double, display: Double = 1.0): List<Double> =
            PRESETS.filter { it >= minimum * display && it <= maximum * display }

        /** Whether [preset] is the one to mark at [zoom] (RecorderView.swift:1078). */
        fun isSelected(preset: Double, zoom: Double, display: Double = 1.0): Boolean = abs(zoom * display - preset) < PRESET_TOLERANCE

        /** The zoom as the read-out and the badge show it: always one decimal, "1.0×" (RecorderView.swift:942, RecorderZoomViewport.swift:200). */
        fun readout(zoom: Double, display: Double = 1.0): String = String.format(Locale.ROOT, "%.1f×", zoom * display)

        /**
         * The zoom time after dragging it [translation] points down from
         * where it was [start] seconds: up lengthens, half a second for every
         * whole [DURATION_DRAG_STEP] points (RecorderView.swift:1164-1165,1183-1186).
         */
        fun draggedDuration(start: Double, translation: Double): Double =
            ZoomTransition.boundedDuration(start + truncate(-translation / DURATION_DRAG_STEP) * DURATION_STEP)

        /**
         * Where the picture sits in a view of [bounds]: the whole of a frame
         * shaped like [canvas] when fitting, the view covered when filling
         * (RecorderZoomViewport.swift:216-222). The view itself without a
         * frame.
         */
        fun pictureRect(bounds: Size, canvas: Size?, fill: Boolean): Rect {
            if (canvas == null || canvas.width <= 0 || canvas.height <= 0) return Rect(0.0, 0.0, bounds.width, bounds.height)
            val x = bounds.width / canvas.width
            val y = bounds.height / canvas.height
            val scale = if (fill) maxOf(x, y) else minOf(x, y)
            val width = canvas.width * scale
            val height = canvas.height * scale
            return Rect(bounds.width / 2 - width / 2, bounds.height / 2 - height / 2, width, height)
        }
    }
}
