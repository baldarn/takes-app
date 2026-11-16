package app.takes.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The part of the whole lens the single camera records: a zoom and a centre
 * in the unrotated, unmirrored picture of the full lens, so the preview and
 * the writer can each rotate and mirror the same framing their own way
 * (YouRec/VideoEffects/CaptureZoomHistory.swift:5-32).
 */
data class CameraFraming(val zoom: Double, val center: Point = MIDDLE) {
    companion object {
        val MIDDLE = Point(0.5, 0.5)

        /** A point of the lens as shown at [angle] degrees, mirrored or not. */
        fun oriented(point: Point, angle: Double, mirrored: Boolean): Point {
            val quarter = (roundedAwayFromZero(angle / 90).toInt() % 4 + 4) % 4
            val result = when (quarter) {
                1 -> Point(1 - point.y, point.x)
                2 -> Point(1 - point.x, 1 - point.y)
                3 -> Point(point.y, 1 - point.x)
                else -> point
            }
            return if (mirrored) Point(1 - result.x, result.y) else result
        }

        /** The lens point of a point shown at [angle], mirrored or not: [oriented] undone. */
        fun sensorPoint(point: Point, angle: Double, mirrored: Boolean): Point =
            oriented(Point(if (mirrored) 1 - point.x else point.x, point.y), -angle, false)

        /** A crop centre in whole-lens units: the crop of [fraction] of the lens may touch an edge, never pass it. */
        fun clamped(point: Point, fraction: Double): Point {
            val half = min(1.0, max(0.0, fraction)) / 2
            return Point(min(1 - half, max(half, point.x)), min(1 - half, max(half, point.y)))
        }
    }
}

/**
 * The single camera's crop of the lens, as the original's face follow makes
 * it each frame (YouRec/DualFaceDetector.swift:129-195): the reserve of zoom
 * that eases in and out over a third of a second when following starts and
 * stops, the zoom of the crop past the lens's own, the crop rectangle from a
 * following framing or from a centre placed by hand, and the framing it shows.
 *
 * The face is followed by [FaceFraming.follow] over the whole picture; the
 * detection, the resampling and the levelled transform are the app's.
 */
object SingleCameraCrop {
    val FULL_FRAME = Rect(0.0, 0.0, 1.0, 1.0)

    /** The most the reserve zooms in to give a face room. */
    const val MAXIMUM_RESERVE = 1.20

    /** The reserve after [dt] seconds, ramping 0.6 a second; a centre placed by hand has none. */
    fun reserve(current: Double, following: Boolean, manual: Boolean, dt: Double): Double = when {
        manual -> 1.0
        following -> min(MAXIMUM_RESERVE, current + 0.6 * dt)
        else -> max(1.0, current - 0.6 * dt)
    }

    /**
     * The zoom of the crop over the picture the lens delivers at [inputZoom]:
     * the zoom asked for, or the lens's widest with the reserve, whichever is
     * closer, never below the whole picture.
     */
    fun zoom(inputZoom: Double, selectedZoom: Double?, minimumZoom: Double, reserve: Double): Double {
        val requested = selectedZoom ?: inputZoom
        return max(1.0, max(requested, minimumZoom * reserve) / inputZoom)
    }

    data class Crop(
        /** Fractions of the delivered picture, top-left. */
        val rectangle: Rect,
        /** The framing to carry on with: a centre placed by hand moves it, so following again eases out of it. */
        val framing: DualCameraLayout.Framing,
    )

    /**
     * The crop of [framing] (its zoom already from [zoom]); with [manual], the
     * centre placed by hand on the lens, mapped through the hardware zoom of
     * the lens past its widest ([inputZoom] over [minimumZoom]) and clamped.
     */
    fun crop(
        framing: DualCameraLayout.Framing,
        manual: Point?,
        inputZoom: Double,
        minimumZoom: Double,
        angle: Double,
        mirrored: Boolean,
    ): Crop {
        val fraction = 1 / framing.zoom
        var rectangle = Rect((1 - fraction) * framing.x, (1 - fraction) * framing.y, fraction, fraction)
        var next = framing
        if (manual != null && fraction < 1) {
            val hardware = inputZoom / minimumZoom
            val target = CameraFraming.oriented(manual, angle, mirrored)
            val center = CameraFraming.clamped(Point(0.5 + (target.x - 0.5) * hardware, 0.5 + (target.y - 0.5) * hardware), fraction)
            rectangle = rectangle.copy(x = center.x - fraction / 2, y = center.y - fraction / 2)
            next = framing.copy(x = rectangle.minX / (1 - fraction), y = rectangle.minY / (1 - fraction))
        }
        return Crop(rectangle, next)
    }

    /** What the crop shows, as a framing of the whole lens. */
    fun visible(rectangle: Rect, inputZoom: Double, minimumZoom: Double, angle: Double, mirrored: Boolean): CameraFraming {
        val hardware = inputZoom / minimumZoom
        val sensor = Point(0.5 + (rectangle.midX - 0.5) / hardware, 0.5 + (rectangle.midY - 0.5) / hardware)
        return CameraFraming(inputZoom / rectangle.width, CameraFraming.sensorPoint(sensor, angle, mirrored))
    }

    /** Whether a frame starts afresh: another camera, size, turn or mirror, or time going back or jumping more than half a second. */
    fun restarts(sameSource: Boolean, previousTime: Double?, time: Double): Boolean =
        !sameSource || (previousTime != null && (time < previousTime || time - previousTime > 0.5))

    /** Seconds since the previous frame, at most a tenth; a first frame counts as one at 30 fps. */
    fun elapsed(previousTime: Double?, time: Double): Double = max(0.0, min(0.1, time - (previousTime ?: (time - 1.0 / 30))))
}

/**
 * The pan half of a two-finger pinch on the recorder's picture
 * (YouRec/RecorderZoomViewport.swift:151-174): the point of the lens under the
 * fingers' previous midpoint stays under the new one, also when both fingers
 * move together or turn back at a lens limit. The zoom half is [PinchZoom].
 *
 * Points and sizes are view points; [image] is where the whole picture is
 * drawn. [leveled] maps offsets from the crop centre onto the picture shown
 * (the identity, or the level horizon's `screenTransform`).
 */
object PinchPan {
    data class Step(
        /** The new centre on the lens. */
        val center: Point,
        /** Whether the fingers have travelled far enough to take the framing over from the face follow. */
        val dragged: Boolean,
    )

    fun step(
        image: Rect,
        minimumZoom: Double,
        previousZoom: Double,
        previousCenter: Point,
        zoom: Double,
        previousMid: Point,
        mid: Point,
        initialMid: Point,
        initialSpan: Double,
        span: Double,
        angle: Double,
        mirrored: Boolean,
        leveled: Affine,
        wasFollowing: Boolean,
        dragged: Boolean,
    ): Step {
        val from = minimumZoom / previousZoom
        val to = minimumZoom / zoom
        val oldCenter = CameraFraming.oriented(previousCenter, angle, mirrored)
        val offset = Point(
            (previousMid.x - image.midX) * from - (mid.x - image.midX) * to,
            (previousMid.y - image.midY) * from - (mid.y - image.midY) * to,
        ).applying(leveled.inverted())
        val proposed = Point(oldCenter.x + offset.x / image.width, oldCenter.y + offset.y / image.height)
        val distance = hypot(mid.x - initialMid.x, mid.y - initialMid.y)
        val takeover = max(14.0, min(30.0, abs(span - initialSpan) * 0.3))
        val nowDragged = dragged || distance >= takeover
        val following = wasFollowing && !nowDragged
        val center = if (following) previousCenter else CameraFraming.sensorPoint(CameraFraming.clamped(proposed, to), angle, mirrored)
        return Step(center, nowDragged)
    }

    /**
     * How far the shown picture must move, in view points, for a framing that
     * went from [startCenter] to [center] at a zoom ratio; and whether the
     * change is worth saving (more than half a percent of zoom or three
     * quarters of a point).
     */
    fun changed(image: Rect, minimumZoom: Double, startZoom: Double, startCenter: Point, zoom: Double, center: Point, angle: Double, mirrored: Boolean, leveled: Affine): Boolean {
        val to = minimumZoom / zoom
        val source = CameraFraming.oriented(startCenter, angle, mirrored)
        val dest = CameraFraming.oriented(center, angle, mirrored)
        val raw = Point((source.x - dest.x) * image.width / to, (source.y - dest.y) * image.height / to)
        val moved = raw.applying(leveled)
        return abs(zoom / startZoom - 1) > 0.005 || hypot(moved.x, moved.y) > 0.75
    }
}
