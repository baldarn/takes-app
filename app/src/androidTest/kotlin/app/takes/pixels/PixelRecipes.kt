package app.takes.pixels

import app.takes.core.Rect
import app.takes.core.TransitionColor
import app.takes.core.TransitionFrame
import app.takes.core.Point
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** The sRGB transfer function, as the original's renderer decodes and encodes around its linear working space. */
object Light {
    fun linear(encoded: Double): Double {
        val c = encoded.coerceIn(0.0, 1.0)
        return if (c < 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    fun encoded(linear: Double): Double {
        val c = linear.coerceIn(0.0, 1.0)
        return if (c < 0.0031308) c * 12.92 else 1.055 * c.pow(1 / 2.4) - 0.055
    }
}

fun rgb(red: Int, green: Int, blue: Int): Int = (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)

/** A picture of eight bits a channel, rows from the top: what a deck holds and what the shader gives back. */
class Picture(val width: Int, val height: Int, val pixels: IntArray) {
    constructor(width: Int, height: Int, at: (x: Int, y: Int) -> Int) : this(width, height, IntArray(width * height) { at(it % width, it / width) })

    fun at(x: Int, y: Int): Int = pixels[y * width + x]

    /** Channel 0 is red, 1 green, 2 blue: 0 to 255. */
    fun channel(x: Int, y: Int, channel: Int): Int = (pixels[y * width + x] shr (16 - 8 * channel)) and 0xff

    /**
     * The encoded color at a point given in pixels from the top left, 0 to 1:
     * between pixel centers it is their even blend, and past the picture its
     * edge pixels go on, as a texture read with linear filtering and clamping does.
     */
    fun encoded(px: Double, py: Double, out: DoubleArray) {
        val cx = px.coerceIn(0.5, width - 0.5) - 0.5
        val cy = py.coerceIn(0.5, height - 0.5) - 0.5
        val x0 = floor(cx).toInt().coerceIn(0, width - 1)
        val y0 = floor(cy).toInt().coerceIn(0, height - 1)
        val x1 = minOf(x0 + 1, width - 1)
        val y1 = minOf(y0 + 1, height - 1)
        val fx = cx - x0
        val fy = cy - y0
        for (c in 0..2) {
            val top = channel(x0, y0, c) * (1 - fx) + channel(x1, y0, c) * fx
            val bottom = channel(x0, y1, c) * (1 - fx) + channel(x1, y1, c) * fx
            out[c] = (top * (1 - fy) + bottom * fy) / 255.0
        }
    }

    companion object {
        fun flat(width: Int, height: Int, color: Int) = Picture(width, height) { _, _ -> color }

        /** Four fields of different colors, so a picture turned or mirrored is told at once. */
        fun quadrants(width: Int, height: Int, topLeft: Int, topRight: Int, bottomLeft: Int, bottomRight: Int) = Picture(width, height) { x, y ->
            if (y < height / 2) (if (x < width / 2) topLeft else topRight) else (if (x < width / 2) bottomLeft else bottomRight)
        }

        /** Blocks of two colors, with edges both ways for a blur to spread. */
        fun checker(width: Int, height: Int, blockWidth: Int, blockHeight: Int, one: Int, other: Int) = Picture(width, height) { x, y ->
            if ((x / blockWidth + y / blockHeight) % 2 == 0) one else other
        }
    }
}

/** One side of a transition as the recipe reads it: a clip's picture, or the solid color standing in for a missing side. */
sealed interface Side {
    fun encoded(px: Double, py: Double, out: DoubleArray)

    fun linear(px: Double, py: Double, out: DoubleArray) {
        encoded(px, py, out)
        for (c in 0..2) out[c] = Light.linear(out[c])
    }

    class Image(val picture: Picture) : Side {
        override fun encoded(px: Double, py: Double, out: DoubleArray) = picture.encoded(px, py, out)
    }

    class Solid(val color: TransitionColor) : Side {
        override fun encoded(px: Double, py: Double, out: DoubleArray) {
            out[0] = color.red; out[1] = color.green; out[2] = color.blue
        }
    }
}

/**
 * What a frame of a transition should hold at a pixel, in linear light:
 * the original's recipe (`TransitionRenderer.composite`) worked out from the
 * numbers of the core's [TransitionFrame], without the shader.
 *
 * The two blurs are the ideal ones, not the shader's: a whip pan is the even
 * average over the whole length of the smear, a defocus the even average over
 * the whole disc. The original's own kernels (`CIMotionBlur`, `CIBokehBlur`)
 * are not in its source, so these are what its recipe names, and the shader is
 * held to them within a stated bound only.
 *
 * Pixels count from the top left; the core's geometry counts y upward.
 */
class Recipe(
    val width: Int, val height: Int, val first: Side, val second: Side,
    /** Where each clip's own picture lies in the frame, without bars: top-down. */
    val firstContent: Rect = Rect(0.0, 0.0, width.toDouble(), height.toDouble()),
    val secondContent: Rect = Rect(0.0, 0.0, width.toDouble(), height.toDouble()),
) {
    private val a = DoubleArray(3)
    private val b = DoubleArray(3)
    private val scratch = DoubleArray(3)

    private fun mixed(share: Double, out: DoubleArray) {
        for (c in 0..2) out[c] = a[c] * (1 - share) + b[c] * share
    }

    /** The top of a rectangle of the core, counted downward. */
    private fun top(rect: Rect) = height - rect.maxY

    private fun inside(px: Double, py: Double, rect: Rect) = px >= rect.x && px < rect.maxX && py >= top(rect) && py < top(rect) + rect.height

    /**
     * The color at the center of pixel ([x], [y]). [nudge] moves a wipe's edge
     * that many pixels along its travel, for the limits of what rounding may give.
     */
    fun expected(frame: TransitionFrame, x: Int, y: Int, out: DoubleArray, nudge: Double = 0.0) {
        val px = x + 0.5
        val py = y + 0.5
        when (frame) {
            TransitionFrame.First -> first.linear(px, py, out)
            TransitionFrame.Second -> second.linear(px, py, out)
            is TransitionFrame.Dissolve -> {
                first.linear(px, py, a); second.linear(px, py, b)
                mixed(frame.second, out)
            }
            is TransitionFrame.FadeColor -> {
                first.linear(px, py, a); second.linear(px, py, b)
                val solid = doubleArrayOf(Light.linear(frame.color.red), Light.linear(frame.color.green), Light.linear(frame.color.blue))
                for (c in 0..2) out[c] = frame.first * a[c] + frame.solid * solid[c] + frame.second * b[c]
            }
            is TransitionFrame.Wipe -> {
                first.linear(px, py, a); second.linear(px, py, b)
                mixed(frame.edge.secondShare(Point(px + nudge * frame.edge.dx, height - py + nudge * frame.edge.dy)), out)
            }
            is TransitionFrame.Hitchcock -> {
                first.encoded(px, py, scratch)
                val share = frame.key.secondShare(scratch[0], scratch[1], scratch[2])
                first.linear(px, py, a); second.linear(px, py, b)
                mixed(share, out)
            }
            is TransitionFrame.Split -> {
                val pair = DoubleArray(3)
                if (inside(px, py, frame.first)) {
                    val framed = frame.firstFramed(firstContent.size)
                    first.linear(firstContent.x + framed.crop.x + (px - frame.first.x) / framed.scale, firstContent.y + framed.crop.y + (py - top(frame.first)) / framed.scale, pair)
                }
                if (inside(px, py, frame.second)) {
                    val framed = frame.secondFramed(secondContent.size)
                    second.linear(secondContent.x + framed.crop.x + (px - frame.second.x) / framed.scale, secondContent.y + framed.crop.y + (py - top(frame.second)) / framed.scale, pair)
                }
                if (inside(px, py, frame.line)) {
                    pair[0] = Light.linear(frame.lineColor.red); pair[1] = Light.linear(frame.lineColor.green); pair[2] = Light.linear(frame.lineColor.blue)
                }
                first.linear(px, py, a); second.linear(px, py, b)
                for (c in 0..2) out[c] = frame.mix.first * a[c] + frame.mix.pair * pair[c] + frame.mix.second * b[c]
            }
            is TransitionFrame.Whip -> {
                val alongX = if (frame.vertical) 0.0 else 1.0
                val alongY = 1 - alongX
                fun pan(side: Side, offset: Double, out: DoubleArray) {
                    val steps = if (frame.blurRadius > 0) SMEAR_STEPS else 1
                    out.fill(0.0)
                    for (step in 0 until steps) {
                        val along = if (steps == 1) 0.0 else frame.blurRadius * ((step + 0.5) / steps * 2 - 1)
                        side.linear(px - offset * alongX + along * alongX, py - offset * alongY + along * alongY, scratch)
                        for (c in 0..2) out[c] += scratch[c] / steps
                    }
                }
                pan(first, frame.firstOffset, a); pan(second, frame.secondOffset, b)
                mixed(frame.mix, out)
            }
            is TransitionFrame.Defocus -> {
                fun disc(side: Side, out: DoubleArray) {
                    if (!(frame.blurRadius > 0)) return side.linear(px, py, out)
                    out.fill(0.0)
                    for (point in DISC) {
                        side.linear(px + point.first * frame.blurRadius, py + point.second * frame.blurRadius, scratch)
                        for (c in 0..2) out[c] += scratch[c] / DISC.size
                    }
                }
                if (frame.mix <= 0) disc(first, out) else if (frame.mix >= 1) disc(second, out) else {
                    disc(first, a); disc(second, b)
                    mixed(frame.mix, out)
                }
            }
        }
    }

    /** True where a pixel center lies on an edge of a split screen's rectangles, so that rounding alone decides its side. */
    fun onAnEdge(frame: TransitionFrame, x: Int, y: Int): Boolean {
        if (frame !is TransitionFrame.Split) return false
        val px = x + 0.5
        val py = y + 0.5
        return listOf(frame.first, frame.second, frame.line).any { rect ->
            abs(px - rect.x) < EDGE || abs(px - rect.maxX) < EDGE || abs(py - top(rect)) < EDGE || abs(py - top(rect) - rect.height) < EDGE
        }
    }

    companion object {
        /** A pixel center nearer than this to an edge is on it. */
        const val EDGE = 0.01

        /** Points along the smear of the ideal whip pan. */
        const val SMEAR_STEPS = 256

        /** Points evenly over the unit disc, for the ideal defocus: many more than the shader's, and not on its spiral's angles. */
        val DISC: List<Pair<Double, Double>> = List(600) { index ->
            val radius = sqrt((index + 0.5) / 600)
            val angle = index * 2.399963229728653 + 0.7
            radius * cos(angle) to radius * sin(angle)
        }
    }
}

/** How far a drawn frame is from its recipe. */
class Difference {
    var checked = 0
    var over = 0
    var onEdges = 0
    /** Pixels not exactly what the recipe gives once rounded to eight bits; counted for encoded tolerances only. */
    var inexact = 0
    var worst = 0.0
    var where = ""

    override fun toString(): String =
        "$checked pixels, worst %.4f%s, %d over, %d not to the bit, %d on an edge left out".format(worst, if (where.isEmpty()) "" else " at $where", over, inexact, onEdges)
}

/** How near a pixel has to be. */
sealed interface Tolerance {
    /** In steps of the eight-bit encoded value. */
    data class Levels(val levels: Double) : Tolerance

    /** In linear light, 0 to 1: for the blurs, which are held to their ideal within a bound. */
    data class Linear(val bound: Double) : Tolerance
}

/** Compares every [stride]-th pixel of [actual] with [recipe] for [frame]. */
fun compare(actual: Picture, recipe: Recipe, frame: TransitionFrame, tolerance: Tolerance, stride: Int = 1): Difference {
    val difference = Difference()
    val low = DoubleArray(3)
    val high = DoubleArray(3)
    // A wipe's edge may land a hair either way: the shader works in single precision.
    val nudge = if (frame is TransitionFrame.Wipe) 0.004 else 0.0
    for (y in stride / 2 until actual.height step stride) for (x in stride / 2 until actual.width step stride) {
        if (recipe.onAnEdge(frame, x, y)) { difference.onEdges++; continue }
        recipe.expected(frame, x, y, low, -nudge)
        if (nudge > 0) recipe.expected(frame, x, y, high, nudge) else low.copyInto(high)
        difference.checked++
        var worst = 0.0
        for (c in 0..2) {
            val (least, most) = minOf(low[c], high[c]) to maxOf(low[c], high[c])
            val off = when (tolerance) {
                is Tolerance.Levels -> {
                    val value = actual.channel(x, y, c).toDouble()
                    maxOf(0.0, Light.encoded(least) * 255 - value, value - Light.encoded(most) * 255)
                }
                is Tolerance.Linear -> {
                    val value = Light.linear(actual.channel(x, y, c) / 255.0)
                    maxOf(0.0, least - value, value - most)
                }
            }
            worst = maxOf(worst, off)
        }
        val allowed = when (tolerance) {
            is Tolerance.Levels -> tolerance.levels + 0.5
            is Tolerance.Linear -> tolerance.bound
        }
        if (worst > allowed) difference.over++
        if (tolerance is Tolerance.Levels && worst > 0.5) difference.inexact++
        if (worst > difference.worst) {
            difference.worst = worst
            difference.where = "($x, $y)"
        }
    }
    return difference
}
