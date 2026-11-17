package app.takes.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** What a gesture on the Kaleidoscope's guide moves. YouRec/VideoEffects/KaleidoscopeGuide.swift:3-5. */
enum class KaleidoscopeTarget { Center, Rotation, Source, SourceRotation }

/**
 * Where a Kaleidoscope's split and source are: the split's centre (0…1 of the
 * picture) and turn in degrees, the source's pan (−1…1) and turn.
 * YouRec/VideoEffects/KaleidoscopeGuide.swift:7-37.
 */
data class KaleidoscopePose(
    val center: Point = Point(0.5, 0.5),
    val degrees: Double = 0.0,
    val sourcePan: Point = Point.ZERO,
    val sourceDegrees: Double = 0.0,
) {
    /** The effect's controls for what [target] moves. KaleidoscopeGuide.swift:23-30. */
    fun parameters(target: KaleidoscopeTarget): Map<String, Double> = when (target) {
        KaleidoscopeTarget.Center -> mapOf("centerX" to center.x, "centerY" to center.y)
        KaleidoscopeTarget.Rotation -> mapOf("splitAngle" to degrees)
        KaleidoscopeTarget.Source -> mapOf("sourcePanX" to sourcePan.x, "sourcePanY" to sourcePan.y)
        KaleidoscopeTarget.SourceRotation -> mapOf("sourceAngle" to sourceDegrees)
    }

    /** The source panned by [x], [y], each held to −1…1. KaleidoscopeGuide.swift:34-36. */
    fun offsetSource(x: Double, y: Double): KaleidoscopePose =
        copy(sourcePan = Point(min(1.0, max(-1.0, sourcePan.x + x)), min(1.0, max(-1.0, sourcePan.y + y))))

    /** The split's centre moved by [x], [y], held inside the picture. KaleidoscopeGuide.swift:61-69. */
    fun nudgeCenter(x: Double, y: Double): KaleidoscopePose =
        copy(center = Point(min(1.0, max(0.0, center.x + x)), min(1.0, max(0.0, center.y + y))))

    /** The source turned by [by] degrees, wrapped to −180…180. KaleidoscopeGuide.swift:71-75. */
    fun turningSource(by: Double): KaleidoscopePose = copy(sourceDegrees = EffectParameter.wrappedDegrees(sourceDegrees + by))

    /** The split turned by [by] degrees, wrapped. KaleidoscopeGuide.swift:146-150. */
    fun turningSplit(by: Double): KaleidoscopePose = copy(degrees = EffectParameter.wrappedDegrees(degrees + by))

    companion object {
        /** The pose of [effect] at source second [time]. KaleidoscopeGuide.swift:14-19. */
        fun of(effect: VideoEffectSettings, time: Double = 0.0) = KaleidoscopePose(
            Point(effect.number("centerX", time), effect.number("centerY", time)),
            effect.number("splitAngle", time),
            Point(effect.number("sourcePanX", time), effect.number("sourcePanY", time)),
            effect.number("sourceAngle", time),
        )

        /** A control's value as the guide reads it out: whole degrees, or whole percents. KaleidoscopeGuide.swift:20-22. */
        fun display(id: String, value: Double): String =
            if (id == "splitAngle" || id == "sourceAngle") "${roundedAwayFromZero(value).toInt()}°" else "${roundedAwayFromZero(value * 100).toInt()}%"
    }
}

/**
 * The Kaleidoscope's guide on the picture: the split's boundaries from its
 * centre, a cyan arc on the source wedge, a handle that turns the split, and
 * the gestures that move the centre, turn the split, pan the source with a
 * drag and turn it with a twist. [transform] takes the picture's unit square
 * to the view, as the picture is shown. YouRec/VideoEffects/KaleidoscopeGuide.swift:41-210.
 */
object KaleidoscopeGuide {
    /** How near a finger must land to the centre or the handle to take it rather than the source, in points. KaleidoscopeGuide.swift:166-167. */
    const val GRAB = 22.0

    /** The steps of a screen reader's actions: 2% of the picture, one degree. KaleidoscopeGuide.swift:117-145. */
    const val NUDGE = 0.02

    /**
     * How a drag on the shown picture moves the source under the wedge that was touched: the wedge's
     * rotation or reflection undone, and the source's own turn. YouRec/VideoEffects/VideoEffectMath.swift:7-22.
     */
    fun dragTransform(start: Point, center: Point, size: Size, angle: Double, divisions: Int, sourceAngle: Double = 0.0): Affine {
        val undoSourceTurn = -sourceAngle * Math.PI / 180
        val dx = (start.x - center.x) * size.width
        val dy = (start.y - center.y) * size.height
        if (hypot(dx, dy) <= 0.001) return Affine.rotation(undoSourceTurn)
        val axis = (angle - 90) * Math.PI / 180
        val turn = 2 * Math.PI
        val span = turn / divisions
        val relative = atan2(dy, dx) - axis
        val sector = min(divisions - 1, floor((relative - turn * floor(relative / turn)) / span).toInt())
        if (divisions % 2 == 0 && sector % 2 != 0) {
            val reflection = 2 * axis + (sector + 1) * span + undoSourceTurn
            return Affine(cos(reflection), sin(reflection), sin(reflection), -cos(reflection), 0.0, 0.0)
        }
        return Affine.rotation(-sector * span + undoSourceTurn)
    }

    /** What the guide draws, in the view's points. */
    data class Layout(
        /** The split's centre. */
        val pivot: Point,
        /** Each boundary's direction from the centre, of length one in the picture's own axes. */
        val directions: List<Point>,
        /** How far the boundaries are drawn: the picture's diagonal. */
        val reach: Double,
        /** The handle that turns the split. */
        val handle: Point,
        /** The cyan arc over the source wedge, 25 points 24 from the centre. */
        val arc: List<Point>,
        /** The part of the picture inside the view, where the guide draws and takes gestures. */
        val visible: Rect,
        /** The picture's width and height in the view. */
        val width: Double,
        val height: Double,
    )

    /**
     * The guide for [pose] with [divisions] on a view of [size], or null when the picture is not on it.
     * KaleidoscopeGuide.swift:50-53, 77-100, 104-112.
     */
    fun layout(pose: KaleidoscopePose, divisions: Int, transform: Affine, size: Size): Layout? {
        if (abs(transform.a * transform.d - transform.b * transform.c) <= 0.001) return null
        val picture = bounds(transform)
        val view = Rect(0.0, 0.0, size.width, size.height)
        val visible = intersection(picture, view) ?: return null
        val pivot = pose.center.applying(transform)
        val width = hypot(transform.a, transform.b)
        val height = hypot(transform.c, transform.d)
        val angle = (pose.degrees - 90) * Math.PI / 180
        fun direction(ray: Double) = Point(
            transform.a * cos(ray) / width + transform.c * sin(ray) / height,
            transform.b * cos(ray) / width + transform.d * sin(ray) / height,
        )
        val count = max(1, divisions)
        val directions = (0 until count).map { direction(angle + it * 2 * Math.PI / count) }
        val reach = hypot(picture.width, picture.height)
        // Keep the handle reachable when a boundary points off the screen. KaleidoscopeGuide.swift:92-97.
        val length = min(82.0, max(40.0, min(picture.width, picture.height) * 0.3))
        val inset = Rect(
            visible.x + min(22.0, visible.width / 4), visible.y + min(22.0, visible.height / 4),
            visible.width - 2 * min(22.0, visible.width / 4), visible.height - 2 * min(22.0, visible.height / 4),
        )
        fun inside(p: Point) = p.x >= inset.minX && p.x < inset.maxX && p.y >= inset.minY && p.y < inset.maxY
        val candidates = directions.map { Point(pivot.x + it.x * length, pivot.y + it.y * length) }
        val handle = candidates.firstOrNull(::inside) ?: candidates.map {
            Point(min(inset.maxX, max(inset.minX, it.x)), min(inset.maxY, max(inset.minY, it.y)))
        }.maxBy { hypot(it.x - pivot.x, it.y - pivot.y) }
        val arc = (0..24).map { i ->
            val d = direction(angle + i / 24.0 * 2 * Math.PI / count)
            Point(pivot.x + 24 * d.x, pivot.y + 24 * d.y)
        }
        return Layout(pivot, directions, reach, handle, arc, visible, width, height)
    }

    /** What a finger landing at [location] takes: the nearer of the centre and the handle within [GRAB], else the source. KaleidoscopeGuide.swift:164-167. */
    fun target(location: Point, layout: Layout): KaleidoscopeTarget {
        val centerDistance = hypot(location.x - layout.pivot.x, location.y - layout.pivot.y)
        val handleDistance = hypot(location.x - layout.handle.x, location.y - layout.handle.y)
        return when {
            min(centerDistance, handleDistance) > GRAB -> KaleidoscopeTarget.Source
            centerDistance <= handleDistance -> KaleidoscopeTarget.Center
            else -> KaleidoscopeTarget.Rotation
        }
    }

    /**
     * A one-finger drag begun at [start] (in the view) on [pose]: given where the finger is, the pose it
     * makes, null where it changes nothing. The drag keeps the pose it began with, so that neither a handle
     * nor a turn past ±180° jumps. KaleidoscopeGuide.swift:162-190.
     */
    fun drag(pose: KaleidoscopePose, divisions: Int, transform: Affine, layout: Layout, start: Point): Pair<KaleidoscopeTarget, (Point) -> KaleidoscopePose?> {
        val inverse = transform.inverted()
        val from = start.applying(inverse)
        val target = target(start, layout)
        val size = Size(layout.width, layout.height)
        val mapping = dragTransform(from, pose.center, size, pose.degrees, divisions, pose.sourceDegrees)
        return Pair(target) { current ->
            val point = current.applying(inverse)
            when (target) {
                KaleidoscopeTarget.Source -> {
                    val delta = Point((point.x - from.x) * size.width, (point.y - from.y) * size.height).applying(mapping)
                    pose.offsetSource(delta.x / size.width, delta.y / size.height)
                }
                KaleidoscopeTarget.Rotation -> {
                    val c = pose.center
                    val dx = (point.x - c.x) * size.width
                    val dy = (point.y - c.y) * size.height
                    if (hypot(dx, dy) <= 8) null
                    else {
                        val initial = atan2((from.y - c.y) * size.height, (from.x - c.x) * size.width)
                        pose.copy(degrees = EffectParameter.wrappedDegrees(pose.degrees + (atan2(dy, dx) - initial) * 180 / Math.PI))
                    }
                }
                KaleidoscopeTarget.Center -> pose.copy(center = Point(
                    min(1.0, max(0.0, pose.center.x + point.x - from.x)), min(1.0, max(0.0, pose.center.y + point.y - from.y)),
                ))
                KaleidoscopeTarget.SourceRotation -> null
            }
        }
    }

    /**
     * A two-finger twist begun with its middle at [midpoint] (in the view): given the fingers' turn in
     * radians, the pose with the source turned, settling at 0° within 2°. A reflected wedge or a mirrored
     * view reverses the turn; the wedge is the one the twist began on. KaleidoscopeGuide.swift:191-206.
     */
    fun twist(pose: KaleidoscopePose, divisions: Int, transform: Affine, layout: Layout, midpoint: Point): (Double) -> KaleidoscopePose {
        val start = midpoint.applying(transform.inverted())
        val wedge = dragTransform(start, pose.center, Size(layout.width, layout.height), pose.degrees, divisions)
        val determinant = (wedge.a * wedge.d - wedge.b * wedge.c) * (transform.a * transform.d - transform.b * transform.c)
        val sign = if (determinant < 0) -1.0 else 1.0
        return { radians ->
            val degrees = EffectParameter.wrappedDegrees(pose.sourceDegrees + sign * radians * 180 / Math.PI)
            pose.copy(sourceDegrees = if (abs(degrees) <= 2) 0.0 else degrees)
        }
    }

    /**
     * Whether a twist of the fingers counts yet: past 2° once, then every change over 0.002 radians with the
     * fingers more than 8 points apart. [turning] is whether it already counted. KaleidoscopeGuide.swift:247-256.
     */
    fun twistCounts(turn: Double, lastTurn: Double, turning: Boolean, spread: Double): Boolean =
        (turning || abs(turn) >= 2 * Math.PI / 180) && spread > 8 && abs(turn - lastTurn) > 0.002

    /**
     * The picture's unit square on a view of [width] by [height] that shows it whole, then enlarged, turned and
     * shifted about the view's middle by [layer] as a framing places it (scale, then rotation in degrees clockwise,
     * then translation), as the editor's preview lays a cropped clip. The original's guide fits the canvas
     * (VideoEffectsEditor.swift:983-986).
     */
    fun viewTransform(width: Double, height: Double, layer: ViewLayer? = null): Affine {
        val unit = Affine.scale(width, height)
        if (layer == null) return unit
        return unit.concatenating(Affine.translation(-width / 2, -height / 2))
            .concatenating(Affine.scale(layer.scaleX, layer.scaleY))
            .concatenating(Affine.rotation(layer.rotation * Math.PI / 180))
            .concatenating(Affine.translation(width / 2 + layer.translationX, height / 2 + layer.translationY))
    }

    private fun bounds(transform: Affine): Rect {
        val corners = listOf(Point(0.0, 0.0), Point(1.0, 0.0), Point(0.0, 1.0), Point(1.0, 1.0)).map { it.applying(transform) }
        val x0 = corners.minOf { it.x }
        val y0 = corners.minOf { it.y }
        return Rect(x0, y0, corners.maxOf { it.x } - x0, corners.maxOf { it.y } - y0)
    }

    private fun intersection(a: Rect, b: Rect): Rect? {
        val x0 = max(a.minX, b.minX)
        val y0 = max(a.minY, b.minY)
        val x1 = min(a.maxX, b.maxX)
        val y1 = min(a.maxY, b.maxY)
        // CGRect.intersects is false for rectangles that only touch.
        return if (x1 > x0 && y1 > y0) Rect(x0, y0, x1 - x0, y1 - y0) else null
    }
}
