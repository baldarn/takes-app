package app.takes.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Night fix in its Medium and Precise modes: each frame fused with its
 * neighbours, aligned by their motion, before the passes of Fast
 * ([VideoEffectNightFix]) clean and brighten it. From
 * YouRec/VideoEffects/NightFix.swift:228-297 (cited as `NightFix`) and the
 * kernels nf_guide, nf_seed, nf_fuse and nf_finish of
 * YouRec/VideoEffects/VideoEffects.metal:640-681 (`Metal`).
 *
 * The original measures the motion between two guides with Apple's Vision,
 * its classical optical flow (VNGenerateOpticalFlowRequest revision 1,
 * NightFix:263-276), whose algorithm is not published. Here it is measured on
 * the same guides by a pyramidal Lucas–Kanade of the port's own ([flow]),
 * which the export runs on the GPU pass for pass; the guides, the gates
 * before the flow, the weights and the averaging in linear light are the
 * original's.
 *
 * Pictures hold BT.709-encoded values, as in [VideoEffectNightFix].
 */
object VideoEffectNightFusion {
    /** A guide's channel, or one level of its pyramid: a value a pixel, rows from the top, read between centres and held at the edges. */
    class Plane(val width: Int, val height: Int, val data: FloatArray = FloatArray(width * height)) {
        init {
            require(width > 0 && height > 0 && data.size == width * height)
        }

        operator fun get(x: Int, y: Int): Double = data[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)].toDouble()

        /** The bilinear read at pixel coordinates, centres at whole numbers. */
        fun at(px: Double, py: Double): Double {
            val x0 = floor(px).toInt()
            val y0 = floor(py).toInt()
            val fx = px - floor(px)
            val fy = py - floor(py)
            val top = this[x0, y0] + (this[x0 + 1, y0] - this[x0, y0]) * fx
            val bottom = this[x0, y0 + 1] + (this[x0 + 1, y0 + 1] - this[x0, y0 + 1]) * fx
            return top + (bottom - top) * fy
        }

        /** The bilinear read at a position, 0…1 across the plane. */
        fun sample(uv: Point): Double = at(uv.x * width - .5, uv.y * height - .5)

        companion object {
            fun of(width: Int, height: Int, value: (x: Int, y: Int) -> Double): Plane =
                Plane(width, height, FloatArray(width * height) { value(it % width, it / width).toFloat() })
        }
    }

    /** The motion from one guide to another in guide pixels, a vector a pixel: a pixel at p of the first is at p + flow of the second. */
    class Flow(val width: Int, val height: Int, val dx: Plane, val dy: Plane) {
        /** The flow read between pixel centres at a position, 0…1 across the guide. */
        fun sample(uv: Point): Point = Point(dx.sample(uv), dy.sample(uv))

        operator fun get(x: Int, y: Int): Point = Point(dx[x, y], dy[x, y])
    }

    /**
     * A guide: the frame's luma, smoothed, as its square root in eight bits at
     * the guide's size, with its mean and the values of a grid of about 16 × 16
     * of its pixels. NightFix:222-227, :246-262.
     */
    class Guide(val plane: Plane) {
        val width: Int get() = plane.width
        val height: Int get() = plane.height
        val mean: Double = plane.data.sumOf { it.toDouble() } / plane.data.size
        val signature: DoubleArray = buildList {
            for (y in 0 until height step max(1, height / 16)) for (x in 0 until width step max(1, width / 16)) add(plane[x, y])
        }.toDoubleArray()

        /** The mean difference of two signatures. NightFix:226. */
        fun difference(other: Guide): Double = signature.indices.sumOf { abs(signature[it] - other.signature[it]) } / signature.size
    }

    /** A neighbour of the frame being fused: its picture, the flows from the frame to it and back, and how many frames away it is. */
    class Neighbour(val image: EffectImage, val forward: Flow, val backward: Flow, val offset: Int)

    /** The largest mean difference of two guides whose frames are fused. NightFix:286. */
    const val MEAN_GATE = 0.075

    /** The largest difference of two signatures whose frames are fused. NightFix:286. */
    const val SIGNATURE_GATE = 22.0 / 255.0

    /** The pyramid's coarsest level has at least this many pixels on its shorter side. */
    const val SMALLEST_LEVEL = 8

    /** The flow's window reaches this many pixels each side of the pixel measured: 5 × 5. */
    const val FLOW_REACH = 2

    /** Added to the window's structure tensor, so a flat window keeps the motion it was given. */
    const val FLOW_DAMPING = 1e-3

    /** The most a step of the flow moves, in pixels of its level. */
    const val FLOW_STEP = 1.0

    /**
     * The mode the frames of a clip are fused in: of its Night fix effects that
     * are enabled, fuse (Medium or Precise) and overlap its source seconds
     * [start]…[end], with some intensity and some reduction at their value or
     * a keyframe, the one of the widest window; null when there is none, and
     * the clip is drawn as Fast. An effect of another mode is drawn without
     * the fusion (NightFix.swift:346). NightFix:14, :65-70.
     */
    fun mode(effects: List<VideoEffectSettings>?, start: Double, end: Double): NightFixMode? {
        fun VideoEffectSettings.maximum(id: String): Double {
            val p = parameters.firstOrNull { it.id == id } ?: return 0.0
            return (listOf(p.value) + p.keyframes.map { it.value }).mapNotNull { (it as? EffectValue.Number)?.value }.maxOrNull() ?: 0.0
        }
        return effects.orEmpty()
            .filter { it.kind == VideoEffectID.NightFix && it.enabled && it.nightFixMode != NightFixMode.Fast && it.start < end && it.end > start }
            .takeIf { fusing -> fusing.any { it.maximum("intensity") > 0 && it.maximum("reduction") > 0 } }
            ?.maxByOrNull { it.nightFixMode.radius }?.nightFixMode
    }

    /**
     * The size of a frame's guide for a picture of [width] × [height]: at most a
     * quarter of it, and [NightFixMode.guideLimit] on its longer side, at least
     * 32 a side. NightFix:241-242.
     */
    fun guideSize(width: Int, height: Int, mode: NightFixMode): Pair<Int, Int> {
        val scale = min(0.25, mode.guideLimit / max(width, height).toDouble())
        return max(32, (width * scale).toInt()) to max(32, (height * scale).toInt())
    }

    /**
     * The guide of [picture] at [width] × [height]: the mean luma of nine reads
     * 0.65 guide pixels apart around each pixel's centre, of the values as they
     * are, its square root, in eight bits. Metal:647-655; the eight bits are the
     * guide's bgra8Unorm (NightFix:249).
     */
    fun guide(picture: EffectImage, width: Int, height: Int): Guide = Guide(
        Plane.of(width, height) { x, y ->
            var luma = 0.0
            for (j in -1..1) for (i in -1..1) {
                val at = Point((x + .5 + i * .65) / width, (y + .5 + j * .65) / height)
                luma += VideoEffectNightFix.y(picture.sample(at)) / 9
            }
            (sqrt(luma.coerceIn(0.0, 1.0)) * 255).roundToInt() / 255.0
        },
    )

    /** Whether the frame of [other] may be fused into the frame of [center]: no jump of exposure or scene between them. NightFix:284-286. */
    fun comparable(center: Guide, other: Guide): Boolean =
        abs(center.mean - other.mean) <= MEAN_GATE && center.difference(other) <= SIGNATURE_GATE

    /** The frames fused into frame [n] of [count]: up to [radius] each side, in order, within the footage. NightFix:281. */
    fun window(n: Int, count: Int, radius: Int): List<Int> = (max(0, n - radius)..min(count - 1, n + radius)).filter { it != n }

    /** The flow's iterations at each level of the pyramid: Medium as the original's medium accuracy, Precise its high (NightFix:271). */
    fun iterations(mode: NightFixMode): Int = if (mode == NightFixMode.Precise) 5 else 3

    /**
     * The levels of [plane]'s pyramid, the plane first: each half the one
     * before, integer halves, read between its pixels at the centres of the
     * smaller one, while the shorter side keeps [SMALLEST_LEVEL] pixels.
     */
    fun pyramid(plane: Plane): List<Plane> {
        val levels = arrayListOf(plane)
        while (true) {
            val last = levels.last()
            val w = last.width / 2
            val h = last.height / 2
            if (min(w, h) < SMALLEST_LEVEL) break
            levels += Plane.of(w, h) { x, y -> last.sample(Point((x + .5) / w, (y + .5) / h)) }
        }
        return levels
    }

    /**
     * The motion from guide [from] to guide [to], a vector a pixel of [from] in
     * its pixels: a pyramidal Lucas–Kanade. From the coarsest level, the flow
     * of the level before, scaled to this one, is refined [iterations] times:
     * over a window of 5 × 5 around each pixel, the gradients of [from]
     * (central differences) and the difference of [to] read where the flow
     * puts each pixel give the step that best cancels it, damped by
     * [FLOW_DAMPING] and at most [FLOW_STEP] a side. The port's own estimator,
     * where the original asks Vision (NightFix:263-276).
     */
    fun flow(from: Guide, to: Guide, mode: NightFixMode): Flow {
        val a = pyramid(from.plane)
        val b = pyramid(to.plane)
        var u: Plane? = null
        var v: Plane? = null
        for (k in a.indices.reversed()) {
            val first = a[k]
            val second = b[k]
            val w = first.width
            val h = first.height
            val coarse = u
            val coarseV = v
            var du = if (coarse == null) Plane(w, h) else Plane.of(w, h) { x, y -> coarse.sample(Point((x + .5) / w, (y + .5) / h)) * w / coarse.width }
            var dv = if (coarseV == null) Plane(w, h) else Plane.of(w, h) { x, y -> coarseV.sample(Point((x + .5) / w, (y + .5) / h)) * h / coarseV.height }
            repeat(iterations(mode)) {
                val (nu, nv) = step(first, second, du, dv)
                du = nu
                dv = nv
            }
            u = du
            v = dv
        }
        return Flow(from.width, from.height, u!!, v!!)
    }

    /** One step of [flow]'s refinement at a level: [a] and [b] the two guides' levels, [u] and [v] the flow so far. */
    fun step(a: Plane, b: Plane, u: Plane, v: Plane): Pair<Plane, Plane> {
        val w = a.width
        val h = a.height
        val nu = Plane(w, h)
        val nv = Plane(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val fx = u[x, y]
            val fy = v[x, y]
            var gxx = 0.0
            var gxy = 0.0
            var gyy = 0.0
            var bx = 0.0
            var by = 0.0
            for (j in -FLOW_REACH..FLOW_REACH) for (i in -FLOW_REACH..FLOW_REACH) {
                val qx = (x + i).coerceIn(0, w - 1)
                val qy = (y + j).coerceIn(0, h - 1)
                val ax = (a[qx + 1, qy] - a[qx - 1, qy]) / 2
                val ay = (a[qx, qy + 1] - a[qx, qy - 1]) / 2
                val e = b.at(qx + fx, qy + fy) - a[qx, qy]
                gxx += ax * ax
                gxy += ax * ay
                gyy += ay * ay
                bx += e * ax
                by += e * ay
            }
            gxx += FLOW_DAMPING
            gyy += FLOW_DAMPING
            val det = gxx * gyy - gxy * gxy
            val sx = (-(gyy * bx - gxy * by) / det).coerceIn(-FLOW_STEP, FLOW_STEP)
            val sy = (-(gxx * by - gxy * bx) / det).coerceIn(-FLOW_STEP, FLOW_STEP)
            nu.data[y * w + x] = (fx + sx).toFloat()
            nv.data[y * w + x] = (fy + sy).toFloat()
        }
        return nu to nv
    }

    /** The mean of nine reads 1.2 pixels apart around [uv]: what nf_fuse compares. Metal:659-663. */
    fun smooth(image: EffectImage, uv: Point): EffectVector {
        var sum = EffectVector.ZERO
        for (j in -1..1) for (i in -1..1) sum += image.sample(Point(uv.x + i * 1.2 / image.width, uv.y + j * 1.2 / image.height)) / 9.0
        return sum
    }

    /** The weight of an observation: by how far the flows there and back disagree, how far the pictures differ, and how many frames away it is. Metal:672. */
    fun weight(agreement: Double, difference: EffectVector, offset: Int): Double =
        exp(-agreement * agreement / (0.8 * 0.8)) * exp(-difference.dot(difference) / (3.0 * (6.0 / 255.0 * 1.5).pow(2))) * exp(-0.5 * (offset / 3.0).pow(2))

    /**
     * [current] fused with its [neighbours], the guides [guideWidth] ×
     * [guideHeight]: each pixel's own value in linear light, weight 1
     * (nf_seed), and each neighbour's where the flow puts the pixel, unless it
     * leaves the picture, the flow back disagrees by more than 3 guide pixels
     * or is not a number, weighed by [weight] (nf_fuse); the mean, encoded
     * (nf_finish). Metal:656-679.
     */
    fun fuse(current: EffectImage, neighbours: List<Neighbour>, guideWidth: Int, guideHeight: Int): EffectImage =
        EffectImage.of(current.width, current.height) { x, y ->
            val uv = current.uv(x, y)
            var sum = VideoEffectNightFix.decode(current[x, y])
            var total = 1.0
            for (n in neighbours) {
                val f = n.forward.sample(uv)
                val at = Point(uv.x + f.x / guideWidth, uv.y + f.y / guideHeight)
                if (!f.x.isFinite() || !f.y.isFinite()) continue
                if (at.x < 1.0 / guideWidth || at.y < 1.0 / guideHeight || at.x > 1 - 2.0 / guideWidth || at.y > 1 - 2.0 / guideHeight) continue
                val b = n.backward.sample(at)
                val agreement = sqrt((f.x + b.x).pow(2) + (f.y + b.y).pow(2))
                if (!agreement.isFinite() || agreement > 3.0) continue
                val w = weight(agreement, smooth(current, uv) - smooth(n.image, at), n.offset)
                sum += VideoEffectNightFix.decode(n.image.sample(at)) * w
                total += w
            }
            VideoEffectNightFix.encode(sum / max(1.0, total))
        }

    /**
     * Frame [n] of [frames] fused as [mode] fuses it: its guides, the
     * neighbours in its window whose guides pass the gates, the flows to each
     * and back, the fusion. Fast fuses nothing. NightFix:238-244, :277-297.
     */
    fun frame(frames: List<EffectImage>, n: Int, mode: NightFixMode): EffectImage {
        if (mode.radius == 0) return frames[n]
        val (gw, gh) = guideSize(frames[n].width, frames[n].height, mode)
        val guides = HashMap<Int, Guide>()
        fun guide(i: Int) = guides.getOrPut(i) { guide(frames[i], gw, gh) }
        val neighbours = window(n, frames.size, mode.radius).filter { comparable(guide(n), guide(it)) }.map { j ->
            Neighbour(frames[j], flow(guide(n), guide(j), mode), flow(guide(j), guide(n), mode), j - n)
        }
        return fuse(frames[n], neighbours, gw, gh)
    }
}
