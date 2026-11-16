package app.takes.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow

/*
 * The finite-window matte history of the temporal Color Isolation and Chroma
 * Key: which source frames a frame blends and with what weights
 * (YouRec/VideoEffects/VideoEffectMath.swift:23-54, cited as `Math`), and the
 * passes that blend them on the GPU, ve_matteGuide, ve_matteCuts and
 * ve_matteWindow (YouRec/VideoEffects/VideoEffects.metal:598-639, `Metal`),
 * driven by temporalMatte (YouRec/VideoEffects/MetalVideoEffects.swift:221-251,
 * `Renderer`). These are the CPU reference the GPU code is checked against.
 *
 * As the original, the passes compute in single precision and read and write
 * their textures through half floats ([MaskStorage.half]); the history mattes
 * are 8-bit and the caller gives them rounded ([MaskStorage.r8]).
 */

/**
 * The two matte histories (Math:26-30): a source-frame EMA whose previous
 * frame keeps a [prior] share, a third for Color Isolation ("two-frame EMA")
 * and a quarter for Chroma Key ("25% previous alpha"), over as many frames as
 * leave less than a thousandth to the frames dropped.
 */
enum class VideoEffectMatteKind(val prior: Double) {
    ISOLATION(1.0 / 3),
    KEY(0.25);

    val capacity: Int get() = 1 + ceil(ln(0.001) / ln(prior)).toInt()
}

/**
 * The source frames a frame's matte blends (Math:31-54): [indices] into the
 * source times, oldest first; [weights], the EMA initialized with the oldest
 * frame's raw matte; and [tailBound], the weight lost to the frames the window
 * dropped (0 when nothing before it belongs to the stream).
 */
data class VideoEffectTemporalWindow(val indices: List<Int>, val weights: List<Double>, val tailBound: Double) {
    companion object {
        /** A larger step between two source frames breaks the history. Math:44, 47. */
        const val LONGEST_STEP = 0.25

        /**
         * The window at [time] of an effect starting at [start], over the sorted
         * source frame [times]; [cuts] holds the indices of frames that begin a
         * new scene. Null without a frame on screen. Math:35-53.
         */
        fun make(times: List<Double>, time: Double, start: Double, kind: VideoEffectMatteKind, cuts: Set<Int> = emptySet()): VideoEffectTemporalWindow? {
            if (times.isEmpty() || !time.isFinite() || !start.isFinite() || time < start) return null
            fun lowerBound(value: Double): Int {
                var a = 0
                var b = times.size
                while (a < b) {
                    val m = (a + b) / 2
                    if (times[m] < value) a = m + 1 else b = m
                }
                return a
            }
            // A mid-frame start acts on the frame already on screen, just like
            // Step Printing. Its floor sample belongs to the new effect's window.
            val first = maxOf(0, lowerBound(start + 0.0000001) - 1)
            val current = lowerBound(time + 0.0000001) - 1
            if (current < first || current < 0) return null
            var oldest = current
            while (oldest > first && current - oldest + 1 < kind.capacity && times[oldest] - times[oldest - 1] <= LONGEST_STEP && oldest !in cuts) oldest -= 1
            val indices = (oldest..current).toList()
            val count = indices.size
            val weights = (0 until count).map { i -> kind.prior.pow((count - 1 - i).toDouble()) * (if (i == 0) 1.0 else 1 - kind.prior) }
            val truncated = oldest > first && times[oldest] - times[oldest - 1] <= LONGEST_STEP && oldest !in cuts
            return VideoEffectTemporalWindow(indices, weights, if (truncated) weights[0] else 0.0)
        }
    }
}

/** The GPU passes of the matte window, on the CPU. */
object VideoEffectTemporalMatte {
    /** Textures the passes bind for each list (Renderer:230-233, Metal:609, 628). */
    const val SLOTS = 8

    /** Which cuts ve_matteCuts looks for (Renderer:226, Metal:612-613). */
    enum class CutDetection(val parameter: Float) {
        /** Every boundary of the window: a fresh analysis. */
        FRESH(0f),

        /** None: the window already stops at the recorded cuts. */
        RECORDED(1f),

        /** Only the current frame's boundary, for a frame live preview skipped. */
        CURRENT(2f);

        companion object {
            /** temporalMatte's two switches. Renderer:226. */
            fun of(detectCuts: Boolean, detectCurrentCut: Boolean): CutDetection = when {
                detectCuts -> FRESH
                detectCurrentCut -> CURRENT
                else -> RECORDED
            }
        }
    }

    /** The result of temporalMatte: the blended matte, and the eight cut decisions (newest boundary first). */
    class Result(val matte: MaskPlane, val cuts: BooleanArray)

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    private fun srgb(c: Float): Float = if (c > 0.0031308f) (1.055 * c.toDouble().pow(1.0 / 2.4) - 0.055).toFloat() else c * 12.92f

    /** A texture2d<half> sampled with linearSampler: the filtered value as a half. */
    private fun sampled(plane: MaskPlane, u: Float, v: Float): Float = MaskStorage.half(plane.sample(u, v))

    /** The guide's size: a quarter, rounded up, at least one. Renderer:224. */
    fun guideSize(width: Int, height: Int): Pair<Int, Int> = maxOf(1, (width + 3) / 4) to maxOf(1, (height + 3) / 4)

    /**
     * ve_matteGuide (Metal:598-604): the mean linear luma of each 4×4 block of
     * the picture, held inside 0…1 and encoded with the sRGB curve, as a half.
     * The picture is linear RGB, read through half floats.
     */
    fun guide(input: EffectImage): MaskPlane {
        val (w, h) = guideSize(input.width, input.height)
        return MaskPlane.of(w, h) { qx, qy ->
            var sum = 0f
            var count = 0
            for (y in qy * 4 until minOf((qy + 1) * 4, input.height)) for (x in qx * 4 until minOf((qx + 1) * 4, input.width)) {
                val i = (y * input.width + x) * 3
                val r = MaskStorage.half(input.data[i])
                val g = MaskStorage.half(input.data[i + 1])
                val b = MaskStorage.half(input.data[i + 2])
                sum += r * 0.2126f + g * 0.7152f + b * 0.0722f
                count++
            }
            MaskStorage.half(srgb((sum / maxOf(1, count)).coerceIn(0f, 1f)))
        }
    }

    /**
     * Whether the guides [current] and [previous] differ as a scene cut does
     * (Metal:614-624): over a 32×18 grid of samples, the mean change above
     * 0.20, or above 0.08 with a 16-bin histogram changed by more than 0.30.
     */
    fun cut(current: MaskPlane, previous: MaskPlane): Boolean {
        var difference = 0f
        val a = IntArray(16)
        val b = IntArray(16)
        for (y in 0 until 18) for (x in 0 until 32) {
            val u = (x + .5f) / 32
            val v = (y + .5f) / 18
            val c = sampled(current, u, v)
            val p = sampled(previous, u, v)
            difference += abs(c - p)
            a[minOf(15, (c * 16).toInt())]++
            b[minOf(15, (p * 16).toInt())]++
        }
        var histogram = 0f
        for (i in 0 until 16) histogram += abs((a[i] - b[i]).toFloat())
        difference /= 576
        histogram /= 1152
        return difference > .20f || (difference > .08f && histogram > .30f)
    }

    /**
     * ve_matteCuts (Metal:605-625): for each of the eight boundaries, between
     * guide i and the older guide i+1 (newest first), whether it is a cut.
     * [count] is the number of frames in the window.
     */
    fun cuts(guides: List<MaskPlane>, count: Int = guides.size, detection: CutDetection = CutDetection.FRESH): BooleanArray {
        require(guides.isNotEmpty() && count in 1..SLOTS)
        fun bound(index: Int) = guides[minOf(index, guides.size - 1, SLOTS - 1)]
        return BooleanArray(SLOTS) { i ->
            val skipped = (detection != CutDetection.FRESH && (detection == CutDetection.RECORDED || i > 0)) || i + 1 >= count
            !skipped && cut(bound(i), bound(i + 1))
        }
    }

    /**
     * ve_matteWindow (Metal:626-639): the EMA of the [mattes] (newest first)
     * at the size of the newest, each previous frame keeping [prior], none
     * across a cut; for Chroma Key ([key]) the previous frames' share fades
     * out as the guide's luma moves from 0.01 to 0.02 between two frames.
     * The oldest frame takes all the weight left. Written as a half.
     */
    fun window(mattes: List<MaskPlane>, guides: List<MaskPlane>, cuts: BooleanArray, prior: Float, key: Boolean, count: Int = mattes.size): MaskPlane {
        require(mattes.isNotEmpty() && guides.isNotEmpty() && count in 1..SLOTS && cuts.size >= SLOTS)
        fun matte(index: Int) = mattes[minOf(index, mattes.size - 1, SLOTS - 1)]
        fun guide(index: Int) = guides[minOf(index, guides.size - 1, SLOTS - 1)]
        val out = mattes[0]
        return MaskPlane.of(out.width, out.height) { qx, qy ->
            val u = (qx + .5f) / out.width
            val v = (qy + .5f) / out.height
            fun value(plane: MaskPlane) = if (plane.sameSize(out)) MaskStorage.half(plane[qx, qy]) else sampled(plane, u, v)
            var sum = 0f
            var remaining = 1f
            for (i in 0 until count - 1) {
                var p = if (cuts[i]) 0f else prior
                if (key) {
                    val motion = abs(sampled(guide(i), u, v) - sampled(guide(i + 1), u, v))
                    p *= 1 - smoothstep(.01f, .02f, motion)
                }
                sum += remaining * (1 - p) * value(matte(i))
                remaining *= p
                if (remaining == 0f) break
            }
            sum += remaining * value(matte(count - 1))
            MaskStorage.half(sum.coerceIn(0f, 1f))
        }
    }

    /**
     * temporalMatte (Renderer:221-251): the [current] matte blended with the
     * [history] of (matte, guide), newest first, of which the kind's window
     * keeps capacity − 1 frames. [guide] is the current frame's guide.
     */
    fun temporalMatte(
        current: MaskPlane,
        guide: MaskPlane,
        history: List<Pair<MaskPlane, MaskPlane>>,
        kind: VideoEffectMatteKind,
        detectCuts: Boolean = true,
        detectCurrentCut: Boolean = false,
    ): Result {
        val previous = history.take(kind.capacity - 1)
        val mattes = listOf(current) + previous.map { it.first }
        val guides = listOf(guide) + previous.map { it.second }
        val cuts = cuts(guides, mattes.size, CutDetection.of(detectCuts, detectCurrentCut))
        val matte = window(mattes, guides, cuts, kind.prior.toFloat(), kind == VideoEffectMatteKind.KEY, mattes.size)
        return Result(matte, cuts)
    }
}
