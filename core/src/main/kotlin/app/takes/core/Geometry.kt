package app.takes.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Plain geometry in Doubles, so picture math stays free of platform types.
 * Field names follow CoreGraphics, which the original app's math was written against.
 */
data class Size(val width: Double, val height: Double)

data class Point(val x: Double, val y: Double) {
    fun applying(transform: Affine): Point =
        Point(transform.a * x + transform.c * y + transform.tx, transform.b * x + transform.d * y + transform.ty)

    companion object {
        val ZERO = Point(0.0, 0.0)
    }
}

data class Rect(val x: Double, val y: Double, val width: Double, val height: Double) {
    val minX: Double get() = x
    val minY: Double get() = y
    val maxX: Double get() = x + width
    val maxY: Double get() = y + height
    val midX: Double get() = x + width / 2
    val midY: Double get() = y + height / 2
    val size: Size get() = Size(width, height)

    /** Whole containment, edges included. */
    fun contains(other: Rect): Boolean =
        other.minX >= minX && other.minY >= minY && other.maxX <= maxX && other.maxY <= maxY

    companion object {
        val ZERO = Rect(0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * A 2D affine transform with CoreGraphics' layout: x' = a·x + c·y + tx and
 * y' = b·x + d·y + ty. Kept identical so transforms ported from the original
 * compose in the same order and give the same numbers.
 */
data class Affine(val a: Double, val b: Double, val c: Double, val d: Double, val tx: Double, val ty: Double) {
    /** This transform first, then [next]. */
    fun concatenating(next: Affine): Affine = Affine(
        a * next.a + b * next.c, a * next.b + b * next.d,
        c * next.a + d * next.c, c * next.b + d * next.d,
        tx * next.a + ty * next.c + next.tx, tx * next.b + ty * next.d + next.ty,
    )

    /** Unchanged when it cannot be inverted, as CoreGraphics does. */
    fun inverted(): Affine {
        val determinant = a * d - b * c
        if (determinant == 0.0 || !determinant.isFinite()) return this
        return Affine(
            d / determinant, -b / determinant, -c / determinant, a / determinant,
            (c * ty - d * tx) / determinant, (b * tx - a * ty) / determinant,
        )
    }

    companion object {
        val IDENTITY = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        fun translation(x: Double, y: Double) = Affine(1.0, 0.0, 0.0, 1.0, x, y)
        fun scale(x: Double, y: Double) = Affine(x, 0.0, 0.0, y, 0.0, 0.0)
        fun rotation(radians: Double) = Affine(cos(radians), sin(radians), -sin(radians), cos(radians), 0.0, 0.0)
    }
}

/** Swift's `rounded()`: halves go away from zero. `Math.round` sends negative halves up instead. */
internal fun roundedAwayFromZero(value: Double): Double = if (value < 0) -floor(abs(value) + 0.5) else floor(value + 0.5)
