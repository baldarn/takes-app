package app.takes.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.tanh

/**
 * The level horizon of the recorder, as the original's
 * `YouRec/HorizonLevel.swift:8-148`: each frame is turned by the correction
 * and magnified by a fixed amount, chosen from the largest angle allowed, so
 * no corner ever shows outside the picture. The correction comes from gravity,
 * refined by a long straight edge in the picture when the two agree.
 *
 * A positive angle straightens a line that descends to the right in the
 * delivered pixels.
 */
data class HorizonFraming(val radians: Double, val magnification: Double) {
    /** Output to input, in top-left fractions of the picture: the same map drives the drawing and focus taps. */
    fun sourceTransform(size: Size): Affine {
        val a = cos(radians) / magnification
        val s = sin(radians) / magnification
        val b = s * size.width / size.height
        val c = -s * size.height / size.width
        return Affine(a, b, c, a, (1 - a - c) / 2, (1 - b - a) / 2)
    }

    /** Maps pixel offsets from the crop centre onto the picture shown, y down. */
    fun screenTransform(flip: Boolean): Affine =
        Affine.scale(magnification, magnification).concatenating(Affine.rotation(if (flip) radians else -radians))

    /** The forward transform in bottom-left pixels of a picture of [extent], as an image filter draws it. */
    fun imageTransform(extent: Rect): Affine =
        Affine.translation(-extent.midX, -extent.midY)
            .concatenating(Affine.scale(magnification, magnification))
            .concatenating(Affine.rotation(radians))
            .concatenating(Affine.translation(extent.midX, extent.midY))

    companion object {
        const val DEFAULT_DEGREES = 9.0
        const val MINIMUM_DEGREES = 0.5
        const val MAXIMUM_DEGREES = 10.0

        /** The largest angle allowed, on a half-degree grid within its range. */
        fun degrees(value: Double): Double {
            if (!value.isFinite()) return DEFAULT_DEGREES
            return min(MAXIMUM_DEGREES, max(MINIMUM_DEGREES, roundedAwayFromZero(value * 2) / 2))
        }

        /** The magnification that keeps every corner inside a picture of [size] turned by up to [maxDegrees]. */
        fun scale(maxDegrees: Double, size: Size): Double {
            if (size.width <= 0 || size.height <= 0) return 1.0
            val angle = degrees(maxDegrees) * PI / 180
            return cos(angle) + max(size.width / size.height, size.height / size.width) * sin(angle)
        }
    }
}

/**
 * The correction over time, from gravity and the visible horizon
 * (`HorizonEstimate`, YouRec/HorizonLevel.swift:45-103). A visible line
 * refines gravity by up to 1.5° once it agrees within 3° three times in a
 * row, holds through short occlusions and fades when lost; the correction
 * eases into the limit, tapers to none when the phone is held diagonally, and
 * eases back to level when gravity is unknown.
 */
class HorizonEstimate {
    private var angle = 0.0
    private var lastTime: Double? = null
    private var lastGravityTime = Double.NEGATIVE_INFINITY
    private var lastGravity = 0.0
    private var visualBias = 0.0
    private var lastVisualTime = Double.NEGATIVE_INFINITY
    private var lastAgreement = Double.NEGATIVE_INFINITY
    private var agreedBias = 0.0
    private var candidate = 0.0
    private var agreements = 0

    fun observe(line: Double?, gravity: Double, time: Double) {
        if (line == null || abs(line - gravity) > 3 * PI / 180) {
            agreements = 0
            return
        }
        val difference = line - gravity
        if (time - lastVisualTime > 0.9 || abs(difference - candidate) > PI / 180) {
            agreements = 0
            candidate = difference
        }
        agreements++
        candidate += 0.4 * (difference - candidate)
        lastVisualTime = time
        if (agreements >= 3) {
            agreedBias = max(-1.5, min(1.5, candidate * 180 / PI)) * PI / 180
            lastAgreement = time
        }
    }

    /** The correction in radians at [time]; [stabilized] when the camera's stabilisation already removes fast shake. */
    fun correction(gravity: Double?, time: Double, maxDegrees: Double, stabilized: Boolean): Double {
        val dt = max(0.0, min(0.1, time - (lastTime ?: (time - 1.0 / 30))))
        lastTime = time
        if (gravity != null) {
            lastGravity = gravity
            lastGravityTime = time
        }
        val recent = time - lastAgreement < 0.9
        visualBias += ((if (recent) agreedBias else 0.0) - visualBias) * (1 - exp(-dt / (if (recent) 0.35 else 0.9)))
        val raw = if (time - lastGravityTime < 0.25) lastGravity + visualBias else 0.0
        val limit = HorizonFraming.degrees(maxDegrees) * PI / 180
        val knee = limit * 0.85
        val magnitude = abs(raw)
        val bounded = if (magnitude <= knee) magnitude else knee + (limit - knee) * tanh((magnitude - knee) / (limit - knee))
        // Held diagonally neither quarter-turn is a natural reference: taper to none.
        val ambiguity = min(1.0, max(0.0, (abs(lastGravity) * 180 / PI - 35) / 10))
        val target = (if (raw < 0) -bounded else bounded) * (1 - ambiguity * ambiguity * (3 - 2 * ambiguity))
        val tau = if (gravity == null) 0.35 else if (stabilized) 0.22 else 0.075
        // A smaller limit also fits this frame inside its smaller crop at once.
        angle = min(limit, max(-limit, angle + (target - angle) * (1 - exp(-dt / tau))))
        return angle
    }

    companion object {
        /**
         * The camera's roll from gravity's [x] and [y] on the device, with the
         * picture delivered at [rotation] degrees: the residual within a
         * quarter-turn, since the interface or a take can be locked a
         * quarter-turn away from the phone. None when looking straight up or down.
         */
        fun roll(x: Double, y: Double, rotation: Double, mirrored: Boolean): Double? {
            if (hypot(x, y) <= 0.24) return null
            val raw = atan2(-x, -y) + (rotation - 90) * PI / 180
            val residual = atan2(sin(4 * raw), cos(4 * raw)) / 4
            return if (mirrored) -residual else residual
        }
    }
}

/**
 * A long edge of consistent polarity across most of the picture
 * (`HorizonLine`, YouRec/HorizonLevel.swift:105-148): short, moving or
 * steeply sloped edges cannot vote the frame away from gravity. The luma is a
 * small picture, rows top-down; the app samples it down to 256 on the long side.
 */
object HorizonLine {
    private class Edge(val x: Int, val y: Int, val strength: Int)

    /** The slope in radians within ±12°, or none. */
    fun estimate(luma: ByteArray, width: Int, height: Int): Double? {
        if (width < 32 || height < 24 || luma.size != width * height) return null
        fun at(i: Int) = luma[i].toInt() and 0xFF
        val edges = ArrayList<Edge>()
        for (x in 2 until width - 2 step 2) {
            for (y in 2 until height - 2) {
                val i = y * width + x
                val v = at(i + width) - at(i - width)
                if (abs(v) < 14 || abs(at(i + 1) - at(i - 1)) >= abs(v)) continue
                edges.add(Edge(x, y, min(64, max(-64, v))))
            }
        }
        if (edges.size < width / 3) return null
        val pad = width / 4 + 2
        val rows = height + 2 * pad
        val scores = ArrayList<Double>()
        for (halfDegrees in -24..24) {
            val slope = tan(halfDegrees * PI / 360)
            val votes = IntArray(rows * 8)
            for (edge in edges) {
                val row = pad + edge.y - roundedAwayFromZero((edge.x - width / 2) * slope).toInt()
                votes[row * 8 + edge.x * 8 / width] += edge.strength
            }
            var best = 0.0
            for (row in pad / 2 until rows - pad / 2) {
                var sum = 0
                for (band in 0 until 8) sum += max(-width * 2, min(width * 2, votes[row * 8 + band]))
                best = max(best, abs(sum).toDouble())
            }
            scores.add(best)
        }
        val peak = scores.max()
        if (peak <= width * 11) return null
        val top = scores.indices.filter { scores[it] >= peak * 0.97 }
        val first = top.first()
        val last = top.last()
        if (last - first > 6) return null
        return ((first + last) / 2.0 - 24) * PI / 360
    }

    /**
     * The same on a picture delivered sideways (a landscape sensor for a
     * portrait two-camera picture), [width] by [height] as delivered: its
     * vertical edge is the horizon of the final picture.
     */
    fun estimateSideways(luma: ByteArray, width: Int, height: Int): Double? {
        if (luma.size != width * height) return null
        // Turned a quarter clockwise as the original samples it: output row r, column c reads input (x = r, y = height - 1 - c).
        val turned = ByteArray(width * height)
        for (r in 0 until width) for (c in 0 until height) turned[r * height + c] = luma[(height - 1 - c) * width + r]
        return estimate(turned, height, width)
    }
}
