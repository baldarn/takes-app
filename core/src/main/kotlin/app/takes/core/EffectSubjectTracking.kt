package app.takes.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Where the subject of a Crash Zoom following a face, or of a Dolly Zoom, is
 * at one moment: its box, 0…1 from the top left. The original keeps the box on
 * the recorded frame, before its rotation; here it is on the recording stood
 * upright, where the faces are found. YouRec/VideoEffects/VideoEffectSubjectTracking.swift:5-20.
 */
@Serializable
data class EffectSubjectSample(
    val time: Double,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val confidence: Double,
    val face: Boolean,
    /** A gap kept on purpose. */
    val lost: Boolean? = null,
) {
    val box: Rect get() = Rect(x, y, width, height)
    val center: Point get() = Point(x + width / 2, y + height / 2)

    /** The box touches the picture's edge. :14. */
    val clipped: Boolean get() = x < 0.015 || y < 0.015 || x + width > 0.985 || y + height > 0.985

    /** :15-19. */
    val valid: Boolean
        get() = time.isFinite() && time in 0.0..604800.0 && confidence.isFinite() && confidence in 0.0..1.0 &&
            listOf(x, y, width, height).all { it.isFinite() } && width > 0 && height > 0 && x >= 0 && y >= 0 &&
            x + width <= 1.0001 && y + height <= 1.0001

    companion object {
        fun of(time: Double, box: Rect, confidence: Double, face: Boolean) = EffectSubjectSample(time, box.x, box.y, box.width, box.height, confidence, face)
    }
}

/** The subject over a clip, one sample an analysed frame. VideoEffectSubjectTracking.swift:21-43. */
@Serializable
data class EffectSubjectTrack(val samples: List<EffectSubjectSample> = emptyList(), val stepwise: Boolean? = null) {
    val valid: Boolean get() = samples.size <= 2_000_000 && samples.all { it.valid } && samples.zipWithNext().all { (a, b) -> a.time < b.time }

    /**
     * The subject at [time]: the last sample at or before it, moved toward the next as far as the time has gone
     * between them; nothing after a gap, or half a second past the last sample. :25-42.
     */
    fun sample(time: Double): EffectSubjectSample? {
        var a = 0
        var b = samples.size
        while (a < b) {
            val m = (a + b) / 2
            if (samples[m].time <= time) a = m + 1 else b = m
        }
        if (a == 0) return null
        val previous = samples[a - 1]
        if (previous.lost == true || time - previous.time > 0.5) return null
        if (stepwise == true) return previous
        if (a >= samples.size || samples[a].lost == true || samples[a].time - previous.time > 0.5) return previous
        val next = samples[a]
        val fraction = min(1.0, max(0.0, (time - previous.time) / (next.time - previous.time)))
        return previous.copy(
            time = time,
            x = previous.x + (next.x - previous.x) * fraction,
            y = previous.y + (next.y - previous.y) * fraction,
            width = previous.width + (next.width - previous.width) * fraction,
            height = previous.height + (next.height - previous.height) * fraction,
        )
    }

    fun encoded(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** A track read back; null when it is not one, or not a valid one. */
        fun decoded(text: String): EffectSubjectTrack? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()?.takeIf { it.valid }

        /** How often the analysis looks at the footage: 15 frames a second. VideoEffectSubjectTracking.swift:144. */
        const val STEP = 1.0 / 15
    }
}

/** A face or a person found in a frame, its box 0…1 from the top left of the picture stood upright, and how sure the finder is. */
data class EffectSubjectCandidate(val box: Rect, val confidence: Double)

/**
 * The editor's subject tracker, from a point the person set: at each frame it
 * takes the faces found there, or the people when there are none, and keeps to
 * the one it followed, the one that overlaps it most; at the start, or after
 * losing it for half a second, the one under the point or nearest to it, or
 * the largest without a point. The box's centre follows with a time constant
 * of 0.12 s. VideoEffectSubjectTracking.swift:45-108.
 *
 * The original finds the subject every half second and follows it in between
 * with Vision's object tracker; Android has none, so here it is found at every
 * frame the analysis looks at, a fifteenth of a second apart, and where it is
 * not found it is held where it was for up to a second, as the tracker would
 * go on following it, before it is lost.
 */
class EffectSubjectTracker(private val point: Point? = null) {
    private var previous: EffectSubjectSample? = null
    private var found = Double.NEGATIVE_INFINITY

    /**
     * The subject at [time], from [faces] and, asked only when there are no faces, [people]; candidates under a
     * confidence of 0.5 are not considered. Null when there is none to follow.
     */
    fun update(time: Double, faces: () -> List<EffectSubjectCandidate>, people: () -> List<EffectSubjectCandidate>): EffectSubjectSample? {
        var candidates = faces().filter { it.confidence >= MINIMUM }.map { it to true }
        if (candidates.isEmpty()) candidates = people().filter { it.confidence >= MINIMUM }.map { it to false }
        val last = previous?.takeIf { time - it.time < 0.5 }
        val chosen = when {
            last != null -> candidates.filter { it.second == last.face && overlap(it.first.box, last.box) > 0.1 }.maxByOrNull { overlap(it.first.box, last.box) }
            point != null -> candidates.sortedWith { a, b ->
                val ai = contains(a.first.box, point)
                val bi = contains(b.first.box, point)
                if (ai != bi) (if (ai) -1 else 1)
                else hypot(a.first.box.midX - point.x, a.first.box.midY - point.y).compareTo(hypot(b.first.box.midX - point.x, b.first.box.midY - point.y))
            }.firstOrNull()
            else -> candidates.maxByOrNull { it.first.box.width * it.first.box.height }
        } ?: return held(time, last)
        val box = intersectUnit(chosen.first.box) ?: return null
        var result = EffectSubjectSample.of(time, box, chosen.first.confidence.coerceIn(0.0, 1.0), chosen.second)
        if (!result.valid) return null
        if (last != null && time > last.time && last.face == result.face) {
            val alpha = 1 - exp(-(time - last.time) / 0.12)
            val c = Point(last.center.x + (result.center.x - last.center.x) * alpha, last.center.y + (result.center.y - last.center.y) * alpha)
            result = result.copy(x = c.x - box.width / 2, y = c.y - box.height / 2)
        }
        result = intersectUnit(result.box)?.let { result.copy(x = it.x, y = it.y, width = it.width, height = it.height) } ?: return null
        previous = result
        found = time
        return result
    }

    /**
     * The subject followed held where it was last found, for at most [HOLD] seconds: the original's tracker goes on
     * following it between its detections, and a detection that finds nothing new leaves it to the tracker (:82-83).
     */
    private fun held(time: Double, last: EffectSubjectSample?): EffectSubjectSample? {
        if (last == null || time - found > HOLD) return null
        return last.copy(time = time).also { previous = it }
    }

    companion object {
        /** VideoEffectSubjectTracking.swift:69, 72, 93. */
        const val MINIMUM = 0.5

        /** How long a subject not found again is held where it was, standing for Vision's tracker. */
        const val HOLD = 1.0

        /** Intersection over union. :57-61. */
        fun overlap(a: Rect, b: Rect): Double {
            val x0 = max(a.minX, b.minX)
            val y0 = max(a.minY, b.minY)
            val x1 = min(a.maxX, b.maxX)
            val y1 = min(a.maxY, b.maxY)
            if (x1 <= x0 || y1 <= y0) return 0.0
            val i = (x1 - x0) * (y1 - y0)
            return i / max(0.000001, a.width * a.height + b.width * b.height - i)
        }

        private fun contains(r: Rect, p: Point) = p.x >= r.minX && p.x < r.maxX && p.y >= r.minY && p.y < r.maxY

        private fun intersectUnit(r: Rect): Rect? {
            val x0 = max(0.0, r.minX)
            val y0 = max(0.0, r.minY)
            val x1 = min(1.0, r.maxX)
            val y1 = min(1.0, r.maxY)
            return if (x1 > x0 && y1 > y0) Rect(x0, y0, x1 - x0, y1 - y0) else null
        }

        /**
         * The box around a mask's person: the pixels of [rows] ([width] × [height], one byte each) at half or more,
         * with how much of the box they fill as the confidence; null without any.
         */
        fun maskBox(rows: ByteArray, width: Int, height: Int): EffectSubjectCandidate? {
            var x0 = width
            var y0 = height
            var x1 = -1
            var y1 = -1
            var count = 0
            for (y in 0 until height) for (x in 0 until width) {
                if ((rows[y * width + x].toInt() and 0xff) >= 128) {
                    count++
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                    if (y < y0) y0 = y
                    if (y > y1) y1 = y
                }
            }
            if (count == 0) return null
            // Too small to be a person: noise of the model.
            if (count < width * height / 400) return null
            val box = Rect(x0.toDouble() / width, y0.toDouble() / height, (x1 - x0 + 1).toDouble() / width, (y1 - y0 + 1).toDouble() / height)
            return EffectSubjectCandidate(box, 1.0)
        }
    }
}

/**
 * Where an effect's subject is followed from, and over what: the hand-set point at the effect's start and the
 * span of the clip it covers, which together with the recording name its analysis (VideoEffectSubjectTracking.swift:112-118).
 */
object EffectSubjectAnalysis {
    /** Whether [effect] reads a subject: a Dolly Zoom, or a Crash Zoom following a face. VideoEffectAssets.swift:476. */
    fun follows(effect: VideoEffectSettings, time: Double): Boolean =
        effect.kind == VideoEffectID.DollyZoom || effect.kind == VideoEffectID.CrashZoom && effect.toggle("trackFace", time)

    /** Whether [effect] ever reads one. */
    fun tracked(effect: VideoEffectSettings): Boolean =
        effect.kind == VideoEffectID.DollyZoom || effect.kind == VideoEffectID.CrashZoom &&
            (effect.toggle("trackFace", effect.start) || effect.parameters.any { it.id == "trackFace" && it.keyframes.isNotEmpty() })

    /** The span of [clip]'s source the analysis of [effect] covers. :114. */
    fun span(effect: VideoEffectSettings, clip: TimelineClip): ClosedFloatingPointRange<Double> = max(clip.start, effect.start)..min(clip.end, effect.end)

    /** The point the subject is chosen from: the target at the span's start. :116. */
    fun seed(effect: VideoEffectSettings, clip: TimelineClip): Point {
        val start = span(effect, clip).start
        return Point(effect.number("targetX", start), effect.number("targetY", start))
    }

    /** What the analysis is kept under: the recording, the span and the point. :113-117. */
    fun key(source: String, effect: VideoEffectSettings, clip: TimelineClip): String {
        val span = span(effect, clip)
        val seed = seed(effect, clip)
        return listOf("subject", 2, source, span.start, span.endInclusive, seed.x, seed.y).joinToString("|")
    }
}
