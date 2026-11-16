package app.takes.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * How the recorder lays out two cameras filming at once, as the original's
 * `DualCameraLayout` (YouRec/DualCameraLayout.swift:4-126): a floating inset,
 * square, rectangular or round, over the first camera, the two side by side,
 * one above the other, or split along a diagonal; each feed with its own pan
 * and zoom; and Swap, which exchanges the feeds and keeps the geometry.
 *
 * Coordinates are top-left, in output pixels or view points: the preview and
 * the recording use the same regions. Fields keep the original's names, so a
 * saved layout reads the same; the optional ones stay optional so older
 * preferences keep their framing.
 */
@Serializable
data class DualCameraLayout(
    val mode: Mode = Mode.Floating,
    val swapped: Boolean = false,
    val diagonalFlipped: Boolean? = null,
    val roundInset: Boolean? = null,
    val verticalDivider: Double = 0.5,
    val horizontalDivider: Double = 0.5,
    val insetX: Double = 0.77,
    val insetY: Double = 0.28,
    /** The inset's side for its area, as a share of the canvas's shorter side. */
    val insetSize: Double = 0.32,
    /** Width over height of a rectangular inset; none is square. */
    val insetAspect: Double? = null,
    val first: Framing = Framing(),
    val second: Framing = Framing(),
) {
    @Serializable
    enum class Mode(val title: String) {
        @SerialName("floating") Floating("Floating"),
        @SerialName("sideBySide") SideBySide("Side by side"),
        @SerialName("topAndBottom") TopAndBottom("Top and bottom"),
        @SerialName("diagonal") Diagonal("Diagonal"),
    }

    /** One feed's pan, 0 to 1 across its spare picture, and zoom, 1 to 4. */
    @Serializable
    data class Framing(val x: Double = 0.5, val y: Double = 0.5, val zoom: Double = 1.0) {
        fun constrained() = Framing(clamp(x, 0.0, 1.0), clamp(y, 0.0, 1.0), clamp(zoom, 1.0, 4.0))
    }

    enum class InsetResizeAxis { Width, Height, Proportional }

    val isRound: Boolean get() = roundInset == true
    val diagonalFallsRight: Boolean get() = diagonalFlipped == true
    val diagonalTitle: String get() = if (diagonalFallsRight) "Top left to bottom right" else "Top right to bottom left"

    fun flippingDiagonal() = copy(diagonalFlipped = !diagonalFallsRight)

    private val insetDimensions: Size
        get() {
            if (isRound) return Size(insetSize, insetSize)
            val aspect = insetAspect?.takeIf { it.isFinite() && it > 0 } ?: 1.0
            return Size(insetSize * sqrt(aspect), insetSize / sqrt(aspect))
        }

    /**
     * The inset after a pinch of [scale] along [axis], starting from the size
     * shown (rotating a wide inset can make it fit smaller, and the next pinch
     * must answer without a hidden overshoot), constrained.
     */
    fun resizingInset(scale: Double, axis: InsetResizeAxis, canvas: Size): DualCameraLayout {
        if (!scale.isFinite() || scale <= 0 || !canvas.width.isFinite() || !canvas.height.isFinite() || canvas.width <= 0 || canvas.height <= 0) return this
        val center = regions(canvas)[1]
        val unit = min(canvas.width, canvas.height)
        var width = center.width / unit
        var height = center.height / unit
        if (isRound) {
            return copy(insetSize = min(1.0, max(MINIMUM_INSET, width * scale)), insetX = center.midX / canvas.width, insetY = center.midY / canvas.height).constrained()
        }
        val maxWidth = canvas.width / unit
        val maxHeight = canvas.height / unit
        when (axis) {
            InsetResizeAxis.Proportional -> {
                val factor = min(min(maxWidth / width, maxHeight / height), max(MINIMUM_INSET / min(width, height), scale))
                width *= factor; height *= factor
            }
            InsetResizeAxis.Width -> width = min(maxWidth, max(MINIMUM_INSET, width * scale))
            InsetResizeAxis.Height -> height = min(maxHeight, max(MINIMUM_INSET, height * scale))
        }
        return copy(insetSize = sqrt(width * height), insetAspect = width / height, insetX = center.midX / canvas.width, insetY = center.midY / canvas.height).constrained()
    }

    fun constrained(): DualCameraLayout {
        var size = if (insetSize.isFinite()) max(MINIMUM_INSET, insetSize) else DEFAULT_INSET
        var aspect = insetAspect
        if (isRound) size = min(1.0, size) else {
            val d = copy(insetSize = size).insetDimensions
            val w = if (d.width.isFinite()) max(MINIMUM_INSET, d.width) else DEFAULT_INSET
            val h = if (d.height.isFinite()) max(MINIMUM_INSET, d.height) else DEFAULT_INSET
            size = sqrt(w) * sqrt(h); aspect = w / h
        }
        return copy(
            verticalDivider = clamp(verticalDivider, 0.15, 0.85), horizontalDivider = clamp(horizontalDivider, 0.15, 0.85),
            insetSize = size, insetAspect = aspect, insetX = clamp(insetX, 0.0, 1.0), insetY = clamp(insetY, 0.0, 1.0),
            first = first.constrained(), second = second.constrained(),
        )
    }

    /** The regions of the first and the second slot. */
    fun regions(size: Size): List<Rect> {
        val w = size.width
        val h = size.height
        if (!w.isFinite() || !h.isFinite() || w <= 0 || h <= 0) return listOf(Rect.ZERO, Rect.ZERO)
        return when (mode) {
            Mode.Diagonal -> List(2) { Rect(0.0, 0.0, w, h) }
            Mode.SideBySide -> {
                val x = w * verticalDivider
                listOf(Rect(0.0, 0.0, x, h), Rect(x, 0.0, w - x, h))
            }
            Mode.TopAndBottom -> {
                val y = h * horizontalDivider
                listOf(Rect(0.0, 0.0, w, y), Rect(0.0, y, w, h - y))
            }
            Mode.Floating -> {
                // Keep the shape when the canvas turns; never off the canvas.
                val d = insetDimensions
                val unit = min(w, h)
                val fit = min(1.0, min((w / unit) / d.width, (h / unit) / d.height))
                val width = min(w, unit * (d.width * fit))
                val height = min(h, unit * (d.height * fit))
                val x = min(w - width, max(0.0, insetX * w - width / 2))
                val y = min(h - height, max(0.0, insetY * h - height / 2))
                listOf(Rect(0.0, 0.0, w, h), Rect(x, y, width, height))
            }
        }
    }

    /** Signed distance in pixels from the diagonal, positive in the lower triangle, which is the second slot's. */
    fun diagonalDistance(point: Point, size: Size): Double {
        if (size.width <= 0 || size.height <= 0) return 0.0
        val value = if (diagonalFallsRight) point.y / size.height - point.x / size.width else point.x / size.width + point.y / size.height - 1
        return value / hypot(1 / size.width, 1 / size.height)
    }

    /** Whether a touch at [point] is on [slot]'s picture. */
    fun contains(point: Point, slot: Int, size: Size): Boolean {
        val region = regions(size)[slot]
        if (!(point.x >= region.minX && point.x < region.maxX && point.y >= region.minY && point.y < region.maxY)) return false
        if (mode == Mode.Diagonal) return if (slot == 0) diagonalDistance(point, size) <= 0 else diagonalDistance(point, size) > 0
        if (mode == Mode.Floating && slot == 1 && isRound) return hypot(point.x - region.midX, point.y - region.midY) <= region.width / 2
        return true
    }

    companion object {
        const val MINIMUM_INSET = 0.18
        private const val DEFAULT_INSET = 0.32

        /** Which way a two-finger pinch on the inset resizes it, from the fingers' separation. */
        fun insetResizeAxis(separationX: Double, separationY: Double): InsetResizeAxis {
            val x = abs(separationX)
            val y = abs(separationY)
            if (max(x, y) <= 1) return InsetResizeAxis.Proportional
            if (x >= y * 2.4) return InsetResizeAxis.Width
            if (y >= x * 2.4) return InsetResizeAxis.Height
            return InsetResizeAxis.Proportional
        }

        /** Where a feed of [source] size is drawn to fill [region]: aspect fill with bounded pan, never stretched, never a hole. */
        fun placement(source: Size, region: Rect, framing: Framing): Rect {
            if (source.width <= 0 || source.height <= 0) return region
            val scale = max(region.width / source.width, region.height / source.height) * framing.zoom
            val w = source.width * scale
            val h = source.height * scale
            return Rect(region.minX - (w - region.width) * framing.x, region.minY - (h - region.height) * framing.y, w, h)
        }

        internal fun clamp(value: Double, low: Double, high: Double): Double = if (value.isFinite()) min(high, max(low, value)) else low
    }
}

/**
 * How much of each feed covers a point of the two-camera picture, as the
 * original's masks draw it (YouRec/DualCameraMasks.swift:11-40): the diagonal
 * with a two-pixel dark separator whose edges ramp over one pixel, and the
 * round inset with a one-pixel edge. Coverage of the second feed elsewhere is
 * its region's, drawn over the first.
 */
object DualCameraMasks {
    /** The grey of the diagonal's separator, in 0 to 1. */
    const val SEPARATOR_GREY = 0.12

    data class Coverage(val first: Double, val separator: Double, val second: Double)

    /** At the centre of a pixel, [point], top-left coordinates. */
    fun coverage(point: Point, layout: DualCameraLayout, canvas: Size): Coverage {
        when {
            layout.mode == DualCameraLayout.Mode.Diagonal -> {
                val d = layout.diagonalDistance(point, canvas)
                val first = (-0.5 - d).coerceIn(0.0, 1.0)
                val second = (d - 0.5).coerceIn(0.0, 1.0)
                return Coverage(first, 1 - first - second, second)
            }
            layout.mode == DualCameraLayout.Mode.Floating && layout.isRound -> {
                val region = layout.regions(canvas)[1]
                val radius = region.width / 2
                val inner = max(0.0, radius - 0.5)
                val outer = radius + 0.5
                val distance = hypot(point.x - region.midX, point.y - region.midY)
                val second = ((outer - distance) / (outer - inner)).coerceIn(0.0, 1.0)
                return Coverage(1 - second, 0.0, second)
            }
            else -> {
                val region = layout.regions(canvas)[1]
                val inside = point.x >= region.minX && point.x < region.maxX && point.y >= region.minY && point.y < region.maxY
                return if (inside) Coverage(0.0, 0.0, 1.0) else Coverage(1.0, 0.0, 0.0)
            }
        }
    }
}

/**
 * Which face a camera follows, as the original's `DualFaceTarget`
 * (YouRec/DualCameraLayout.swift:128-162). Boxes are fractions of the
 * picture, top-down, before the preview's mirror. Once chosen, a face is kept
 * across detections and short losses: a larger face that comes in does not
 * take over, and only after a sustained loss is the best face chosen again.
 */
data class FaceTarget(val box: Rect? = null, val seenAt: Double = Double.NEGATIVE_INFINITY) {
    fun observing(faces: List<Rect>, time: Double): FaceTarget {
        // A partly visible face can extend past the picture: keep what shows.
        val valid = faces.filter { it.width > 0 && it.height > 0 && listOf(it.minX, it.minY, it.maxX, it.maxY).all(Double::isFinite) }
            .mapNotNull { intersectUnit(it) }
        val old = box
        if (old != null && time - seenAt < 0.8) {
            val nearby = valid.filter { face ->
                val distance = hypot(face.midX - old.midX, face.midY - old.midY)
                distance < max(0.12, min(0.3, max(old.width, old.height))) && face.width / old.width > 0.45 && face.width / old.width < 2.2
            }
            val next = nearby.minByOrNull { hypot(it.midX - old.midX, it.midY - old.midY) } ?: return this
            return FaceTarget(next, time)
        }
        val best = valid.maxByOrNull { it.width * it.height / (0.25 + hypot(it.midX - 0.5, it.midY - 0.5)) }
        return if (best != null) FaceTarget(best, time) else copy(box = null)
    }

    /** The face to frame now; none after 0.4 s without seeing it, when the framing holds. */
    fun current(time: Double): Rect? = if (time - seenAt < 0.4) box else null

    private fun intersectUnit(r: Rect): Rect? {
        val minX = max(0.0, r.minX)
        val minY = max(0.0, r.minY)
        val maxX = min(1.0, r.maxX)
        val maxY = min(1.0, r.maxY)
        return if (maxX > minX && maxY > minY) Rect(minX, minY, maxX - minX, maxY - minY) else null
    }
}

object FaceFraming {
    /**
     * The next framing that brings [face] toward [target] in [region], as the
     * original's `DualFaceFraming.follow` (YouRec/DualCameraLayout.swift:164-180):
     * only within the spare picture there already is, never adding zoom to make
     * room, with a dead zone, smoothing over 0.22 s and a speed limit. No face
     * holds the framing.
     */
    fun follow(
        face: Rect?,
        source: Size,
        region: Rect,
        current: DualCameraLayout.Framing,
        elapsed: Double,
        target: Point = Point(0.5, 0.5),
    ): DualCameraLayout.Framing {
        if (face == null || !elapsed.isFinite() || elapsed <= 0) return current
        val placed = DualCameraLayout.placement(source, region, current)
        val dt = min(0.1, elapsed)
        val alpha = 1 - exp(-dt / 0.22)
        fun move(value: Double, center: Double, full: Double, visible: Double, target: Double): Double {
            val spare = full - visible
            if (spare <= 0.5) return value
            val error = center * full - spare * value - visible * target
            val deadZone = visible * 0.045
            if (abs(error) <= deadZone) return value
            val distance = error - deadZone * sign(error)
            val step = min(visible * dt * 1.5, max(-visible * dt * 1.5, distance * alpha))
            return min(1.0, max(0.0, value + step / spare))
        }
        return current.copy(
            x = move(current.x, face.midX, placed.width, region.width, target.x),
            y = move(current.y, face.midY, placed.height, region.height, target.y),
        )
    }
}

/** Whether two cameras' frames are one moment (YouRec/DualCameraLayout.swift:182-189). */
object DualFramePairing {
    enum class Action { Pair, DropFirst, DropSecond }

    fun action(first: Double, second: Double): Action {
        val difference = first - second
        if (abs(difference) <= 1.0 / 60 + 0.001) return Action.Pair
        return if (difference < 0) Action.DropFirst else Action.DropSecond
    }
}
