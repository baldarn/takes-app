package app.takes.core

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sqrt

enum class PlacementCropEdge {
    Left, Right, Top, Bottom;

    /** Whether dragging it moves along the horizontal axis. */
    val horizontal: Boolean get() = this == Left || this == Right
}

/** Margins cut from a placed picture, each a fraction of the picture's own width or height. */
@Serializable
data class PlacementEdgeCrop(val left: Double = 0.0, val right: Double = 0.0, val top: Double = 0.0, val bottom: Double = 0.0) {
    val isEmpty: Boolean get() = left == 0.0 && right == 0.0 && top == 0.0 && bottom == 0.0

    val valid: Boolean
        get() = listOf(left, right, top, bottom).all { it.isFinite() && it >= 0 } &&
            left + right <= 1 - MINIMUM_SPAN + 0.0000001 && top + bottom <= 1 - MINIMUM_SPAN + 0.0000001

    operator fun get(edge: PlacementCropEdge): Double = when (edge) {
        PlacementCropEdge.Left -> left
        PlacementCropEdge.Right -> right
        PlacementCropEdge.Top -> top
        PlacementCropEdge.Bottom -> bottom
    }

    companion object {
        /** Opposite margins always leave at least this share of the picture. */
        const val MINIMUM_SPAN = 0.02
    }
}

/** The ready-made arrangements of pictures that play at the same time. */
enum class PlacementLayout(val title: String) {
    Cover("Cover"),
    SideBySide("Side by side"),
    TopAndBottom("Top and bottom"),
    PictureInPicture("Picture in picture"),
}

/**
 * Where one of several pictures playing at the same time sits in the movie's
 * frame. [x], [y], [width] and [height] are fractions of the canvas: the
 * center and size of its window, y from the top. The picture placed is the
 * clip as it would fill the whole canvas on its own, with its own crop.
 */
@Serializable
data class VideoPlacement(
    val x: Double = 0.5,
    val y: Double = 0.5,
    val width: Double = 1.0,
    val height: Double = 1.0,
    /** The part of the picture shown in the window. */
    val framing: TransitionFraming = TransitionFraming(),
    /** Fill covers the window, cutting what does not fit; otherwise the picture is fitted inside it. */
    val fill: Boolean = true,
    /** Higher is in front. */
    val order: Int = 0,
    val motion: EffectAnimation? = null,
    /**
     * Independent clipping after framing and placement. It never takes part
     * in fit or fill, and never recenters or rescales the picture.
     */
    val edgeCrop: PlacementEdgeCrop? = null,
) {
    /** The window in canvas pixels, from the top left. */
    fun window(canvas: Size): Rect =
        Rect((x - width / 2) * canvas.width, (y - height / 2) * canvas.height, width * canvas.width, height * canvas.height)

    /**
     * Fit can leave empty space inside the window. Handles and margins follow
     * the picture, never that empty band.
     */
    fun pictureWindow(canvas: Size): Rect {
        val full = window(canvas)
        if (fill) return full
        val scale = minOf(width, height)
        return Rect(full.midX - canvas.width * scale / 2, full.midY - canvas.height * scale / 2, canvas.width * scale, canvas.height * scale)
    }

    /** What is left of the picture after the edge margins. */
    fun visibleWindow(canvas: Size): Rect {
        val full = pictureWindow(canvas)
        val crop = edgeCrop ?: PlacementEdgeCrop()
        return Rect(
            full.minX + crop.left * full.width, full.minY + crop.top * full.height,
            full.width * (1 - crop.left - crop.right), full.height * (1 - crop.top - crop.bottom),
        )
    }

    /**
     * The picture moved inside its window by a finger distance, in the units
     * of [canvas]. Framing stores a center in the picture; its really shown
     * center is resolved first, so reversing at an edge responds at once.
     */
    fun reframed(translation: Point, canvas: Size): VideoPlacement {
        val window = window(canvas)
        val crop = framing.crop(canvas, if (fill) window.size else canvas)
        val scale = if (fill) maxOf(window.width / crop.width, window.height / crop.height) else minOf(window.width / crop.width, window.height / crop.height)
        if (!(scale.isFinite() && scale > 0)) return this
        return copy(framing = framing.copy(
            x = minOf(canvas.width - crop.width / 2, maxOf(crop.width / 2, crop.midX - translation.x / scale)) / canvas.width,
            y = minOf(canvas.height - crop.height / 2, maxOf(crop.height / 2, crop.midY - translation.y / scale)) / canvas.height,
        ))
    }

    /**
     * One edge of the picture cut at [position], a fraction of the canvas
     * along that edge's axis. The opposite margin is never crossed and at
     * least [minimumPoints] of the picture stay, measured in [canvas] units.
     */
    fun cropped(edge: PlacementCropEdge, position: Double, canvas: Size = Size(1920.0, 1080.0), minimumPoints: Double = 0.0): VideoPlacement {
        if (!position.isFinite()) return this
        val picture = pictureWindow(canvas)
        val full = Rect(picture.minX / canvas.width, picture.minY / canvas.height, picture.width / canvas.width, picture.height / canvas.height)
        val crop = edgeCrop ?: PlacementEdgeCrop()
        val maximum = 1 - minOf(1.0, maxOf(PlacementEdgeCrop.MINIMUM_SPAN, minimumPoints / (if (edge.horizontal) picture.width else picture.height)))
        val next = when (edge) {
            PlacementCropEdge.Left -> crop.copy(left = minOf(maxOf(crop.left, maximum - crop.right), maxOf(0.0, (position - full.minX) / full.width)))
            PlacementCropEdge.Right -> crop.copy(right = minOf(maxOf(crop.right, maximum - crop.left), maxOf(0.0, (full.maxX - position) / full.width)))
            PlacementCropEdge.Top -> crop.copy(top = minOf(maxOf(crop.top, maximum - crop.bottom), maxOf(0.0, (position - full.minY) / full.height)))
            PlacementCropEdge.Bottom -> crop.copy(bottom = minOf(maxOf(crop.bottom, maximum - crop.top), maxOf(0.0, (full.maxY - position) / full.height)))
        }
        return copy(edgeCrop = next.takeUnless { it.isEmpty })
    }

    /** The placement shown at a source second: its motion sampled there, or itself when it does not move. */
    fun at(source: Double): VideoPlacement = motion?.let { applying(it.pose(source)) } ?: this

    /** The ranges a saved project may hold, for the placement and for both ends of its motion. */
    val valid: Boolean get() = validGeometry && (motion?.valid(pose) { applying(it).validGeometry } ?: true)

    private val validGeometry: Boolean
        get() = listOf(x, y, width, height, framing.x, framing.y, framing.zoom).all { it.isFinite() } &&
            x in -2.0..3.0 && y in -2.0..3.0 && width in 0.01..3.0 && height in 0.01..3.0 &&
            framing.x in 0.0..1.0 && framing.y in 0.0..1.0 && framing.zoom in 1.0..MAXIMUM_FRAMING_ZOOM &&
            (edgeCrop?.valid ?: true)

    companion object {
        /** A placed picture can be framed closer than a split screen's half. */
        const val MAXIMUM_FRAMING_ZOOM = 8.0

        /** Picture [index] of [count] in a ready-made arrangement, back to front. A layout resets margins, framing and fit. */
        fun layout(kind: PlacementLayout, index: Int, count: Int): VideoPlacement {
            var value = VideoPlacement(order = index)
            if (kind == PlacementLayout.SideBySide) {
                val width = 1.0 / maxOf(1, count)
                value = value.copy(width = width, x = (index + 0.5) * width)
            }
            if (kind == PlacementLayout.TopAndBottom) {
                val height = 1.0 / maxOf(1, count)
                value = value.copy(height = height, y = (index + 0.5) * height)
            }
            if (kind == PlacementLayout.PictureInPicture && index > 0) {
                // The first picture covers the canvas; the others stack from the bottom right corner.
                val total = maxOf(1, count - 1)
                val columns = if (total <= 3) 1 else ceil(sqrt(total.toDouble())).toInt()
                val rows = ceil(total.toDouble() / columns).toInt()
                val gap = 0.02
                val side = minOf(0.32, (0.9 - (maxOf(columns, rows) - 1) * gap) / maxOf(columns, rows))
                value = value.copy(
                    width = side, height = side,
                    x = 0.94 - side / 2 - ((index - 1) % columns) * (side + gap),
                    y = 0.94 - side / 2 - ((index - 1) / columns) * (side + gap),
                )
            }
            return value
        }

        /** One stable order, back to front, for the renderer, stills and hit testing. Ties keep the order of [ids]. */
        fun <ID> ordered(ids: List<ID>, settings: Map<ID, VideoPlacement>): List<ID> {
            val fallback = ids.withIndex().associate { it.value to it.index }
            return ids.sortedWith(compareBy({ settings[it]?.order ?: fallback.getValue(it) }, { fallback.getValue(it) }))
        }
    }
}

/** The controls of a placement that can move. */
val VideoPlacement.pose: EffectPose
    get() {
        val c = edgeCrop ?: PlacementEdgeCrop()
        return EffectPose(mapOf(
            "x" to x, "y" to y, "width" to width, "height" to height, "fx" to framing.x, "fy" to framing.y, "zoom" to framing.zoom,
            "left" to c.left, "right" to c.right, "top" to c.top, "bottom" to c.bottom,
        ))
    }

/** This placement fixed at [pose], no longer moving. */
fun VideoPlacement.applying(pose: EffectPose): VideoPlacement = copy(
    motion = null, x = pose["x"], y = pose["y"], width = pose["width"], height = pose["height"],
    framing = TransitionFraming(pose["fx"], pose["fy"], pose["zoom"]),
    edgeCrop = PlacementEdgeCrop(pose["left"], pose["right"], pose["top"], pose["bottom"]).takeUnless { it.isEmpty },
)

/** What a renderer draws for one placed picture. Pixels from the top left of the canvas. */
data class PlacedPicture(
    /** The part of the canvas-sized picture that is drawn. */
    val source: Rect,
    /** [source]'s center lands on the center of this window. */
    val window: Rect,
    /** Canvas pixels per picture pixel. */
    val scale: Double,
    /** Nothing is drawn outside this rectangle. Empty when the picture is off the canvas. */
    val clip: Rect,
) {
    /** Picture pixels to canvas pixels. */
    val transform: Affine
        get() = Affine.translation(-source.midX, -source.midY)
            .concatenating(Affine.scale(scale, scale))
            .concatenating(Affine.translation(window.midX, window.midY))
}

object PlacementRendering {
    /** The numbers for a placement that does not move; sample a moving one with [VideoPlacement.at] first. */
    fun placed(settings: VideoPlacement, canvas: Size): PlacedPicture {
        val window = settings.window(canvas)
        val source = settings.framing.crop(canvas, if (settings.fill) window.size else canvas)
        val scale = if (settings.fill) maxOf(window.width / source.width, window.height / source.height)
        else minOf(window.width / source.width, window.height / source.height)
        return PlacedPicture(source, window, scale, intersection(settings.visibleWindow(canvas), Rect(0.0, 0.0, canvas.width, canvas.height)))
    }

    private fun intersection(a: Rect, b: Rect): Rect {
        val x = maxOf(a.minX, b.minX)
        val y = maxOf(a.minY, b.minY)
        val width = minOf(a.maxX, b.maxX) - x
        val height = minOf(a.maxY, b.maxY) - y
        return if (width > 0 && height > 0) Rect(x, y, width, height) else Rect.ZERO
    }
}

/**
 * How touches change a placement. [canvas] is the size of the movie's frame as
 * drawn on screen, and positions are measured from its top left corner.
 * [touch] is the smallest comfortable target, 44 points in the original.
 */
object PlacementEditing {
    const val TOUCH = 44.0

    /** A window's center or edge this close to the canvas's center or edge, as a fraction of the canvas, lands on it. */
    const val WINDOW_SNAP = 0.012

    /** An edge handle this close, in points, to the picture's own edge or to the canvas's edges or center lands there. */
    const val EDGE_SNAP = 5.0

    /** One accessibility step moves a margin by this share of the picture. */
    const val EDGE_STEP = 0.02

    /** A window can grow to this many times the canvas. */
    const val MAXIMUM_SIZE = 3.0

    /**
     * Keeps a touchable strip of the visible window on the canvas, then snaps
     * it to the canvas: centered, or flush with the leading or trailing edge.
     */
    fun constrained(value: VideoPlacement, canvas: Size, touch: Double = TOUCH): VideoPlacement {
        val visible = value.visibleWindow(canvas)
        fun coordinate(raw: Double, leading: Double, trailing: Double, points: Double): Double {
            val keep = minOf(touch / points, trailing - leading)
            val bounded = minOf(1 - keep - leading, maxOf(keep - trailing, raw))
            return listOf(0.5 - (leading + trailing) / 2, -leading, 1 - trailing).firstOrNull { abs(it - bounded) < WINDOW_SNAP } ?: bounded
        }
        return value.copy(
            x = coordinate(value.x, visible.minX / canvas.width - value.x, visible.maxX / canvas.width - value.x, canvas.width),
            y = coordinate(value.y, visible.minY / canvas.height - value.y, visible.maxY / canvas.height - value.y, canvas.height),
        )
    }

    /** Dragging a window: [start] is the placement when the drag began, [current] the one on screen now. */
    fun moved(start: VideoPlacement, current: VideoPlacement, translation: Point, canvas: Size, touch: Double = TOUCH): VideoPlacement {
        val original = start.visibleWindow(canvas)
        val now = current.visibleWindow(canvas)
        return constrained(
            current.copy(
                x = current.x + (original.midX + translation.x - now.midX) / canvas.width,
                y = current.y + (original.midY + translation.y - now.midY) / canvas.height,
            ),
            canvas, touch,
        )
    }

    /** Pinching a window: it grows and shrinks around the center of what is visible, never below a touchable size. */
    fun resized(start: VideoPlacement, current: VideoPlacement, magnification: Double, canvas: Size, touch: Double = TOUCH): VideoPlacement {
        val original = start.visibleWindow(canvas)
        val before = current.visibleWindow(canvas)
        val minimum = minOf(1.0, maxOf(touch / original.width, touch / original.height))
        val scale = minOf(MAXIMUM_SIZE / maxOf(start.width, start.height), maxOf(minimum, magnification))
        val sized = current.copy(width = start.width * scale, height = start.height * scale)
        val after = sized.visibleWindow(canvas)
        return constrained(
            sized.copy(x = sized.x + (before.midX - after.midX) / canvas.width, y = sized.y + (before.midY - after.midY) / canvas.height),
            canvas, touch,
        )
    }

    /** Dragging with "Reframe inside window" on: the picture moves, the window stays. */
    fun reframed(start: VideoPlacement, current: VideoPlacement, translation: Point, canvas: Size): VideoPlacement {
        val framed = start.copy(framing = start.framing.copy(zoom = current.framing.zoom)).reframed(translation, canvas)
        return current.copy(framing = current.framing.copy(x = framed.framing.x, y = framed.framing.y))
    }

    /** Pinching with "Reframe inside window" on. */
    fun zoomed(start: VideoPlacement, current: VideoPlacement, magnification: Double): VideoPlacement =
        current.copy(framing = current.framing.copy(zoom = minOf(VideoPlacement.MAXIMUM_FRAMING_ZOOM, maxOf(1.0, start.framing.zoom * magnification))))

    /**
     * Dragging an edge handle to [position], a fraction of the canvas along
     * the edge's axis. [start] is the placement when the drag began.
     */
    fun edgeDragged(start: VideoPlacement, edge: PlacementCropEdge, position: Double, canvas: Size, touch: Double = TOUCH, snap: Double = EDGE_SNAP): VideoPlacement {
        val base = start.pictureWindow(canvas)
        val original = when (edge) {
            PlacementCropEdge.Left -> base.minX / canvas.width
            PlacementCropEdge.Right -> base.maxX / canvas.width
            PlacementCropEdge.Top -> base.minY / canvas.height
            PlacementCropEdge.Bottom -> base.maxY / canvas.height
        }
        val threshold = snap / (if (edge.horizontal) canvas.width else canvas.height)
        val snapped = listOf(original, 0.0, 0.5, 1.0).firstOrNull { abs(it - position) < threshold } ?: position
        return start.cropped(edge, snapped, canvas, touch)
    }

    /** One accessibility step on an edge: a positive [amount] cuts more, a negative one restores. */
    fun edgeStepped(value: VideoPlacement, edge: PlacementCropEdge, amount: Double, canvas: Size, touch: Double = TOUCH): VideoPlacement {
        val bounds = value.visibleWindow(canvas)
        val base = value.pictureWindow(canvas)
        val position = when (edge) {
            PlacementCropEdge.Left -> (bounds.minX + amount * base.width) / canvas.width
            PlacementCropEdge.Right -> (bounds.maxX - amount * base.width) / canvas.width
            PlacementCropEdge.Top -> (bounds.minY + amount * base.height) / canvas.height
            PlacementCropEdge.Bottom -> (bounds.maxY - amount * base.height) / canvas.height
        }
        return value.cropped(edge, position, canvas, touch)
    }

    /** Where the four edge handles are drawn: on the visible window's edges, kept on the canvas. */
    fun handles(value: VideoPlacement, canvas: Size): Map<PlacementCropEdge, Point> {
        if (!(canvas.width > 0 && canvas.height > 0)) return emptyMap()
        val visible = value.visibleWindow(canvas)
        fun x(x: Double) = minOf(canvas.width, maxOf(0.0, x))
        fun y(y: Double) = minOf(canvas.height, maxOf(0.0, y))
        val onCanvas = visible.maxX > 0 && visible.minX < canvas.width && visible.maxY > 0 && visible.minY < canvas.height
        val middle = if (onCanvas) Point((x(visible.minX) + x(visible.maxX)) / 2, (y(visible.minY) + y(visible.maxY)) / 2)
        else Point(x(visible.midX), y(visible.midY))
        return mapOf(
            PlacementCropEdge.Left to Point(x(visible.minX), middle.y), PlacementCropEdge.Right to Point(x(visible.maxX), middle.y),
            PlacementCropEdge.Top to Point(middle.x, y(visible.minY)), PlacementCropEdge.Bottom to Point(middle.x, y(visible.maxY)),
        )
    }

    /**
     * The handle a touch at [point] grabs, if any. A handle reaches further
     * outward than inward, so the inside of a small window stays free for
     * moving it.
     */
    fun handle(value: VideoPlacement, point: Point, canvas: Size, touch: Double = TOUCH): PlacementCropEdge? {
        val visible = value.visibleWindow(canvas)
        return handles(value, canvas).filter { (edge, position) ->
            val span = if (edge.horizontal) visible.width else visible.height
            val inward = (if (span < 2 * touch) 7.0 else 14.0) * touch / TOUCH
            val outward = touch - inward
            val dx = point.x - position.x
            val dy = point.y - position.y
            val normal = when (edge) {
                PlacementCropEdge.Left -> dx
                PlacementCropEdge.Right -> -dx
                PlacementCropEdge.Top -> dy
                PlacementCropEdge.Bottom -> -dy
            }
            val tangent = if (edge.horizontal) abs(dy) else abs(dx)
            normal >= -outward && normal <= inward && tangent <= touch / 2
        }.minByOrNull { (_, position) -> hypot(position.x - point.x, position.y - point.y) }?.key
    }

    /** The frontmost picture whose visible window contains [point]. [ids] are in timeline order. */
    fun <ID> hit(ids: List<ID>, settings: Map<ID, VideoPlacement>, point: Point, canvas: Size): ID? =
        VideoPlacement.ordered(ids, settings).lastOrNull { id ->
            settings[id]?.visibleWindow(canvas)?.let { point.x >= it.minX && point.x < it.maxX && point.y >= it.minY && point.y < it.maxY } == true
        }

    /**
     * The picture a gesture starting at [point] works on. A picture chosen
     * by hand ([explicitPick]) is kept while the touch is inside it, even
     * when another covers it. A pinch also keeps a small selected window when
     * it starts within [touch] of it and nothing else small is under the
     * fingers. Otherwise it is the picture touched.
     */
    fun <ID> target(
        ids: List<ID>, settings: Map<ID, VideoPlacement>, selected: ID?, explicitPick: Boolean,
        point: Point, canvas: Size, pinching: Boolean = false, touch: Double = TOUCH,
    ): ID? {
        fun Rect.holds(p: Point) = p.x >= minX && p.x < maxX && p.y >= minY && p.y < maxY
        val half = canvas.width * canvas.height * 0.5
        val window = selected?.let { settings[it]?.visibleWindow(canvas) }
        val touched = hit(ids, settings, point, canvas)
        val touchedWindow = touched?.let { settings[it]?.visibleWindow(canvas) }
        val marginIsClear = touched == null || touched == selected || (touchedWindow?.let { it.width * it.height >= half } ?: false)
        if (explicitPick && window?.holds(point) == true) return selected
        if (pinching && marginIsClear && window != null && window.width * window.height < half &&
            Rect(window.minX - touch, window.minY - touch, window.width + 2 * touch, window.height + 2 * touch).holds(point)
        ) return selected
        return touched
    }

    /** "Bring to front" and "Send to back" for one picture among [all]. */
    fun broughtToFront(value: VideoPlacement, all: Collection<VideoPlacement>): VideoPlacement = value.copy(order = (all.maxOfOrNull { it.order } ?: 0) + 1)

    fun sentToBack(value: VideoPlacement, all: Collection<VideoPlacement>): VideoPlacement = value.copy(order = (all.minOfOrNull { it.order } ?: 0) - 1)
}
