package app.takes.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/*
 * The temporal smoothing of the person mask, as the original's
 * VideoEffectMaskSmoother (YouRec/VideoEffects/MetalVideoEffects.swift:693-746,
 * cited as `Renderer`) drives it and its kernels ve_maskSceneCut and
 * ve_maskTemporal (YouRec/VideoEffects/VideoEffects.metal:563-597, `Metal`)
 * compute it. These are the CPU reference the GPU code is checked against.
 *
 * The original keeps the mask in 8-bit textures (r8Unorm), its history in half
 * floats (rg16Float) and reads every texture through `half`; it computes in
 * single precision. These do the same: [MaskStorage.r8] and [MaskStorage.half]
 * round where the original's textures do, and the arithmetic is in Float.
 */

/**
 * One channel of a small picture, in rows from the top, read as the shader
 * reads a texture: [get] at whole indices held at the edges, [sample] between
 * pixel centres (`linearSampler`, Metal:14).
 */
class MaskPlane(val width: Int, val height: Int, val data: FloatArray = FloatArray(width * height)) {
    init {
        require(width > 0 && height > 0 && data.size == width * height)
    }

    operator fun get(x: Int, y: Int): Float = data[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]

    operator fun set(x: Int, y: Int, value: Float) {
        data[y * width + x] = value
    }

    /** Bilinear at normalized coordinates, clamped to the edge pixels. */
    fun sample(u: Float, v: Float): Float {
        val x = u * width - .5f
        val y = v * height - .5f
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        val top = this[x0, y0] + (this[x0 + 1, y0] - this[x0, y0]) * fx
        val bottom = this[x0, y0 + 1] + (this[x0 + 1, y0 + 1] - this[x0, y0 + 1]) * fx
        return top + (bottom - top) * fy
    }

    fun sameSize(other: MaskPlane): Boolean = width == other.width && height == other.height

    fun map(transform: (Float) -> Float): MaskPlane = MaskPlane(width, height, FloatArray(data.size) { transform(data[it]) })

    override fun equals(other: Any?): Boolean = other is MaskPlane && sameSize(other) && other.data.contentEquals(data)

    override fun hashCode(): Int = 31 * (31 * width + height) + data.contentHashCode()

    override fun toString(): String = "MaskPlane(${width}x$height)"

    companion object {
        fun of(width: Int, height: Int, value: (x: Int, y: Int) -> Float): MaskPlane =
            MaskPlane(width, height, FloatArray(width * height) { value(it % width, it / width) })

        fun flat(width: Int, height: Int, value: Float): MaskPlane = MaskPlane(width, height, FloatArray(width * height) { value })
    }
}

/**
 * The same values as the effects' [EffectMask], which reads them as doubles: the smoother and the
 * temporal window work in single precision as the GPU does, the effects' formulas in double.
 */
fun MaskPlane.toEffectMask(): EffectMask = EffectMask(width, height, data.copyOf())

fun EffectMask.toMaskPlane(): MaskPlane = MaskPlane(width, height, data.copyOf())

/** The roundings of the original's texture formats. */
object MaskStorage {
    /**
     * The nearest half float (IEEE binary16, ties to even): what a write to or
     * a read from an r16Float / rg16Float texture, or a `half` variable, keeps.
     * Above the largest half the value becomes infinite.
     */
    fun half(value: Float): Float {
        if (!value.isFinite() || value == 0f) return value
        val a = abs(value.toDouble())
        if (a >= 65520.0) return if (value > 0) Float.POSITIVE_INFINITY else Float.NEGATIVE_INFINITY
        // Subnormal halves are multiples of 2^-24; normal ones have 10 bits after the point.
        val quantum = if (a < 2.0.pow(-14)) 2.0.pow(-24) else 2.0.pow(Math.getExponent(a) - 10)
        val rounded = Math.rint(a / quantum) * quantum
        return (if (value < 0) -rounded else rounded).toFloat()
    }

    /** An r8Unorm texel: held inside 0…1, the nearest of 256 steps (ties to even). */
    fun r8(value: Float): Float = (Math.rint(value.coerceIn(0f, 1f) * 255.0) / 255.0).toFloat()

    fun half(plane: MaskPlane): MaskPlane = plane.map(::half)

    fun r8(plane: MaskPlane): MaskPlane = plane.map(::r8)
}

/**
 * The smoothed mask's history (the original's rg16Float state, Renderer:695):
 * per pixel the alpha written and the source luma it was seen on, both half floats.
 */
class PersonMaskState(val alpha: MaskPlane, val luma: MaskPlane) {
    init {
        require(alpha.sameSize(luma))
    }
}

/**
 * Smooths the person mask frame by frame, as the original's
 * VideoEffectMaskSmoother (Renderer:693-746). Confined to one thread, like the
 * original's segmentation queue.
 */
class PersonMaskSmoother {
    private var state: PersonMaskState? = null
    private var previousTime: Double? = null
    private var previousGeometry = ""

    /** The next push starts a fresh history. Renderer:707. */
    fun reset() {
        previousTime = null
    }

    /**
     * The mask smoothed with the history. [mask] is the segmentation at its own
     * size (stored as 8-bit, so it is read rounded to [MaskStorage.r8]); [guideLuma]
     * is the linear luma of the frame scaled to the mask's size; [geometry]
     * names the camera or the layout, and a change of it starts afresh;
     * [motionGate] lets a large change of the mask through at once. The result
     * is a new 8-bit mask; nothing given or returned earlier is changed.
     * Renderer:708-745.
     */
    fun push(mask: MaskPlane, guideLuma: MaskPlane, time: Double, geometry: String, motionGate: Boolean = false): MaskPlane {
        // Renderer:710: the original refuses (unavailableMask) such a mask.
        require(mask.width in 1..1024 && mask.height in 1..1024 && time.isFinite()) { "unavailable mask" }
        require(guideLuma.sameSize(mask)) { "the guide is drawn at the mask's size" }
        // Renderer:711-718: a new size makes a new history.
        if (state?.alpha?.sameSize(mask) != true) {
            state = null
            previousTime = null
        }
        val delta = previousTime?.let { time - it } ?: 0.0
        val reset = resets(previousTime, previousGeometry, time, geometry)
        val result = step(MaskStorage.r8(mask), guideLuma, state, weight(delta), reset, motionGate)
        state = result.state
        previousTime = time
        previousGeometry = geometry
        return result.output
    }

    /** What one push writes: the 8-bit output and the next history. */
    class Step(val output: MaskPlane, val state: PersonMaskState, val sceneCut: Boolean)

    companion object {
        /** The scene-cut grid, its threshold of mean luma change, and the gates. Metal:566-570, Renderer:216. */
        const val GRID_COLUMNS = 32
        const val GRID_ROWS = 18
        const val SCENE_CUT_THRESHOLD = 0.12f
        const val MOTION_GATE = 0.02f
        const val ALPHA_GATE = 0.15f
        const val LONGEST_STEP = 0.25

        /** The EMA weight of the new mask after [delta] seconds: alpha 0.3 at 30 Hz. Renderer:741. */
        fun weight(delta: Double): Float = (1 - 0.7.pow(maxOf(0.0, delta) * 30)).toFloat()

        /**
         * Whether the history is dropped: the first frame, another geometry, a
         * step back in time, no time passed, or more than a quarter second. Renderer:740.
         */
        fun resets(previousTime: Double?, previousGeometry: String, time: Double, geometry: String): Boolean {
            if (previousTime == null) return true
            val delta = time - previousTime
            return geometry != previousGeometry || delta <= 0 || delta > LONGEST_STEP
        }

        /** The value the shader passes as the motion gate: 0.02, or infinity when off. Renderer:216. */
        fun motionGateValue(motionGate: Boolean): Float = if (motionGate) MOTION_GATE else Float.POSITIVE_INFINITY

        /**
         * ve_maskSceneCut (Metal:565-574): the mean absolute change of luma
         * over a 32×18 grid of the guide's pixels against the history's luma,
         * above [threshold]; always a cut when [reset].
         */
        fun sceneCut(guideLuma: MaskPlane, previous: PersonMaskState?, reset: Boolean, threshold: Float = SCENE_CUT_THRESHOLD): Boolean {
            if (reset || previous == null) return true
            require(previous.luma.sameSize(guideLuma))
            var difference = 0f
            val w = guideLuma.width
            val h = guideLuma.height
            for (y in 0 until GRID_ROWS) for (x in 0 until GRID_COLUMNS) {
                // uint((float(x)+0.5)*width/32): truncated, held inside the picture.
                val ax = minOf(((x + .5f) * w / 32f).toInt(), w - 1)
                val ay = minOf(((y + .5f) * h / 18f).toInt(), h - 1)
                difference += abs(guideLuma[ax, ay] - previous.luma[ax, ay])
            }
            return difference / 576 > threshold
        }

        /**
         * ve_maskTemporal for one pixel (Metal:578-591): with no cut, the new
         * alpha [alpha] is mixed into the old one by [weight] where the luma
         * moved less than [motionGate]; with a finite gate, also only where the
         * alpha moved less than 0.15. Otherwise the new alpha is kept.
         */
        fun temporal(alpha: Float, luma: Float, oldAlpha: Float, oldLuma: Float, weight: Float, motionGate: Float, cut: Boolean): Float {
            if (cut) return alpha
            if (abs(luma - oldLuma) < motionGate && (!motionGate.isFinite() || abs(alpha - oldAlpha) < ALPHA_GATE))
                return oldAlpha + (alpha - oldAlpha) * weight
            return alpha
        }

        /**
         * One frame of both kernels (Renderer:215-219): the cut decision, the
         * smoothed alpha written to the 8-bit output, and the history of
         * (alpha, luma) in half floats. [mask] holds 8-bit values, read as halves.
         */
        fun step(mask: MaskPlane, guideLuma: MaskPlane, previous: PersonMaskState?, weight: Float, reset: Boolean, motionGate: Boolean): Step {
            require(mask.sameSize(guideLuma))
            val cut = sceneCut(guideLuma, previous, reset)
            val gate = motionGateValue(motionGate)
            val alpha = MaskPlane(mask.width, mask.height)
            val luma = MaskPlane(mask.width, mask.height)
            val output = MaskPlane(mask.width, mask.height)
            for (i in mask.data.indices) {
                val l = guideLuma.data[i]
                val a = if (cut) MaskStorage.half(mask.data[i]) else temporal(
                    MaskStorage.half(mask.data[i]), l, previous!!.alpha.data[i], previous.luma.data[i], weight, gate, false,
                )
                alpha.data[i] = MaskStorage.half(a)
                luma.data[i] = MaskStorage.half(l)
                output.data[i] = MaskStorage.r8(MaskStorage.half(a))
            }
            return Step(output, PersonMaskState(alpha, luma), cut)
        }
    }
}
