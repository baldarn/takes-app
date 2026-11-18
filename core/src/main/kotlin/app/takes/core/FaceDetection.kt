package app.takes.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * The faces in a picture as MediaPipe's full-range face detector finds them, where the original asks Vision for its
 * face rectangles (YouRec/DualFaceDetector.swift:30-34, YouRec/VideoEffects/VideoEffectSubjectTracking.swift:65-67).
 * The model (BlazeFace, `face_detection_full_range.tflite`, run on LiteRT by the app) finds faces turned to three
 * quarters and in profile, and faces a few metres away, as Vision does; this is what goes in and comes out of it, as
 * MediaPipe's own graph does it (mediapipe/modules/face_detection/face_detection.pbtxt, full-range options):
 *
 * - [fill] lays the picture, stood upright, on the model's [SIDE] × [SIDE] square, its long side across, centred
 *   between black bars ([Letterbox]), each value from −1 to 1;
 * - [decode] reads a box and a score for each of the [ANCHORS] places of the model's 48 × 48 grid
 *   (SsdAnchorsCalculator: one layer, stride 4, one anchor of fixed size at each cell's centre), keeps those of a
 *   score of at least [MIN_SCORE], Vision's confidence the original keeps, and merges those that overlap by more
 *   than [OVERLAP] into one, their boxes weighted by their scores (NonMaxSuppressionCalculator, WEIGHTED);
 * - the boxes come back in fractions of the picture stood upright, top-down, and [toCamera] turns them back into
 *   the picture as it was given.
 *
 * Which face is followed is chosen afterwards, as the original chooses it ([FaceTarget], [SubjectTracker],
 * [EffectSubjectTracker]).
 */
object FaceDetection {
    /** The side of the model's square input. */
    const val SIDE = 192

    /** The places the model scores: one at each cell of its 48 × 48 grid. */
    const val ANCHORS = (SIDE / 4) * (SIDE / 4)

    /** The values the model gives for each place: the box's centre and size, then six landmarks, in its pixels. */
    const val VALUES = 16

    /** The lowest score kept, Vision's confidence the original keeps (DualFaceDetector.swift:32). */
    const val MIN_SCORE = 0.5

    /** How much two boxes overlap, as intersection over union, to be one face (MediaPipe's full-range graph). */
    const val OVERLAP = 0.3

    /** The scores are clipped to this before the sigmoid, as MediaPipe's TensorsToDetectionsCalculator does. */
    private const val SCORE_CLIP = 100.0

    /** The most source pixels across that are averaged for one of the model's, each way. */
    private const val MOST_TAPS = 3

    /** A picture's pixel at a column and a row from the top, as 0xRRGGBB: an interface, so that nothing is boxed. */
    fun interface Pixels {
        fun rgb(x: Int, y: Int): Int
    }

    /** A face found: its box in fractions of the picture stood upright, top-down, and the model's score, 0…1. */
    data class Face(val box: Rect, val score: Double)

    /** Where the upright picture lies on the model's square, in fractions of the square. */
    data class Letterbox(val x: Double, val y: Double, val width: Double, val height: Double) {
        /** A box in fractions of the square, in fractions of the picture. */
        fun picture(box: Rect): Rect = Rect((box.x - x) / width, (box.y - y) / height, box.width / width, box.height / height)
    }

    /** Where a picture of [width] × [height], stood upright by turning it [upright] degrees clockwise, lies on the square. */
    fun letterbox(width: Int, height: Int, upright: Int): Letterbox {
        val turned = (upright % 180 + 180) % 180 != 0
        val w = (if (turned) height else width).toDouble()
        val h = (if (turned) width else height).toDouble()
        val long = max(w, h)
        val fw = w / long
        val fh = h / long
        return Letterbox((1 - fw) / 2, (1 - fh) / 2, fw, fh)
    }

    /**
     * Fills [into] ([SIDE] × [SIDE] × 3 floats, rows from the top, red, green and blue from −1 to 1) with the picture
     * of [width] × [height] pixels, turned [upright] degrees clockwise to stand, read by [rgb] at a pixel of the
     * picture as given (column, row from the top) as 0xRRGGBB. Each of the model's pixels is the average of up to
     * 3 × 3 of the picture's over its footprint. Gives where the picture lies on the square.
     */
    fun fill(into: FloatArray, width: Int, height: Int, upright: Int, rgb: Pixels): Letterbox {
        require(into.size >= SIDE * SIDE * 3)
        val box = letterbox(width, height, upright)
        val taps = ceil(max(width, height).toDouble() / SIDE).toInt().coerceIn(1, MOST_TAPS)
        val turn = (upright % 360 + 360) % 360
        val left = box.x * SIDE
        val top = box.y * SIDE
        val across = box.width * SIDE
        val down = box.height * SIDE
        val scale = 1f / (127.5f * taps * taps)
        // A quarter turn swaps the axes: the upright picture's columns are then the given picture's rows.
        val swapped = turn == 90 || turn == 270
        // Where each tap of a column, and of a row, of the square falls on the given picture: worked out once, as
        // a turn moves whole columns and rows. The upright u runs along the given x, or back along it, or along y.
        fun places(start: Double, length: Double, flipped: Boolean, size: Int) = IntArray(SIDE * taps) {
            val f = ((it / taps + (it % taps + 0.5) / taps - start) / length).coerceIn(0.0, 1.0)
            min(size - 1, ((if (flipped) 1 - f else f) * size).toInt())
        }
        val columns = places(left, across, turn == 180 || turn == 90, if (swapped) height else width)
        val rows = places(top, down, turn == 180 || turn == 270, if (swapped) width else height)
        for (my in 0 until SIDE) for (mx in 0 until SIDE) {
            val at = (my * SIDE + mx) * 3
            if (mx + 0.5 < left || mx + 0.5 > left + across || my + 0.5 < top || my + 0.5 > top + down) {
                into[at] = -1f; into[at + 1] = -1f; into[at + 2] = -1f
                continue
            }
            var r = 0
            var g = 0
            var b = 0
            for (ty in 0 until taps) for (tx in 0 until taps) {
                val c = rows[my * taps + ty]
                val k = columns[mx * taps + tx]
                val colour = if (swapped) rgb.rgb(c, k) else rgb.rgb(k, c)
                r += colour shr 16 and 0xFF
                g += colour shr 8 and 0xFF
                b += colour and 0xFF
            }
            into[at] = r * scale - 1f
            into[at + 1] = g * scale - 1f
            into[at + 2] = b * scale - 1f
        }
        return box
    }

    /**
     * The faces in what the model gave: [boxes], [VALUES] floats for each of the [ANCHORS] places, and [scores], one
     * logit for each, for a picture laid on the square as [letterbox] says. Best first.
     */
    fun decode(boxes: FloatArray, scores: FloatArray, letterbox: Letterbox, minScore: Double = MIN_SCORE): List<Face> {
        require(boxes.size >= ANCHORS * VALUES && scores.size >= ANCHORS)
        val grid = SIDE / 4
        val found = ArrayList<Face>()
        for (i in 0 until ANCHORS) {
            val score = 1 / (1 + exp(-scores[i].toDouble().coerceIn(-SCORE_CLIP, SCORE_CLIP)))
            if (score < minScore) continue
            val o = i * VALUES
            val cx = boxes[o] / SIDE + (i % grid + 0.5) / grid
            val cy = boxes[o + 1] / SIDE + (i / grid + 0.5) / grid
            val w = boxes[o + 2].toDouble() / SIDE
            val h = boxes[o + 3].toDouble() / SIDE
            if (!(w > 0 && h > 0 && cx.isFinite() && cy.isFinite())) continue
            found += Face(Rect(cx - w / 2, cy - h / 2, w, h), score)
        }
        return merge(found).map { it.copy(box = letterbox.picture(it.box)) }
    }

    /**
     * Weighted non-maximum suppression: the best face takes every one that overlaps it by more than [OVERLAP], its box
     * the average of theirs weighted by score, its score its own; then the best of those left, and so on.
     */
    fun merge(faces: List<Face>): List<Face> {
        var left = faces.sortedByDescending { it.score }
        val merged = ArrayList<Face>()
        while (left.isNotEmpty()) {
            val best = left[0]
            val (group, rest) = left.partition { overlap(best.box, it.box) > OVERLAP || it === best }
            val total = group.sumOf { it.score }
            fun mean(value: (Rect) -> Double) = group.sumOf { value(it.box) * it.score } / total
            val minX = mean { it.minX }
            val minY = mean { it.minY }
            merged += Face(Rect(minX, minY, mean { it.maxX } - minX, mean { it.maxY } - minY), best.score)
            left = rest
        }
        return merged
    }

    /** Intersection over union. */
    fun overlap(a: Rect, b: Rect): Double {
        val w = min(a.maxX, b.maxX) - max(a.minX, b.minX)
        val h = min(a.maxY, b.maxY) - max(a.minY, b.minY)
        if (w <= 0 || h <= 0) return 0.0
        val union = a.width * a.height + b.width * b.height - w * h
        return if (union > 0) w * h / union else 0.0
    }

    /** A box on the picture stood upright by [upright] degrees clockwise, in the picture as given. */
    fun toCamera(box: Rect, upright: Int): Rect {
        val turn = (upright % 360 + 360) % 360
        val a = given(box.minX, box.minY, turn)
        val b = given(box.maxX, box.maxY, turn)
        return Rect(min(a.first, b.first), min(a.second, b.second), abs(a.first - b.first), abs(a.second - b.second))
    }

    /** The point (u, v) of the picture stood upright by [turn] degrees clockwise, on the picture as given. */
    private fun given(u: Double, v: Double, turn: Int): Pair<Double, Double> = when (turn) {
        90 -> v to 1 - u
        180 -> 1 - u to 1 - v
        270 -> 1 - v to u
        else -> u to v
    }
}
