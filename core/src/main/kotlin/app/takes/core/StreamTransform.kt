package app.takes.core

import kotlin.math.abs
import kotlin.math.sign

/**
 * A camera stream's texture transform, as `SurfaceTexture.getTransformMatrix` gives it (column-major 4 × 4, texture
 * coordinates in, the texture's own out), split in two:
 * - the orientation: the quarter turns and flips that the stream applies to every frame;
 * - this frame's crop.
 *
 * A camera that steadies its preview by moving a crop over a larger picture moves the crop from one frame to the
 * next, as some MediaTek phones do. Each of the two cameras' frames is then drawn through its own crop when it
 * arrives. The canvas they make is turned by the host camera's orientation alone. Before, the host camera's latest
 * crop was laid over the whole canvas a frame late, and the other camera's picture shook with it (issue 160).
 */
object StreamTransform {
    /**
     * The orientation alone, mapping the unit square onto itself.
     *
     * It keeps the sign of each of the transform's four coefficients that is not zero. Each translation is then 0, or
     * 1 where its coefficient is negative. When the transform is not a quarter turn or a flip it is returned
     * unchanged, so [crop] is the identity.
     */
    fun orientation(m: FloatArray): FloatArray {
        require(m.size == 16)
        val a = m[0]; val b = m[1]; val c = m[4]; val d = m[5]
        val axisAligned = (near(b) && near(c) && !near(a) && !near(d)) || (near(a) && near(d) && !near(b) && !near(c))
        if (!axisAligned) return m.copyOf()
        val out = m.copyOf()
        out[0] = snap(a); out[1] = snap(b); out[4] = snap(c); out[5] = snap(d)
        // Each output coordinate depends on one input coordinate; a negative coefficient maps 0…1 onto 1…0.
        out[12] = if (out[0] < 0 || out[4] < 0) 1f else 0f
        out[13] = if (out[1] < 0 || out[5] < 0) 1f else 0f
        return out
    }

    /**
     * This frame's crop, in the texture's coordinates: the transform with its [orientation] taken out.
     * Sampling the texture through the crop gives the frame's crop as the texture stores it. Turning that by the
     * orientation gives what the whole transform gives.
     */
    fun crop(m: FloatArray): Affine {
        val whole = affine(m)
        return whole.inverseOrientation(affine(orientation(m)))
    }

    private fun Affine.inverseOrientation(orientation: Affine): Affine = orientation.inverted().concatenating(this)

    /** The 2D part of a column-major 4 × 4 transform. */
    fun affine(m: FloatArray): Affine =
        Affine(m[0].toDouble(), m[1].toDouble(), m[4].toDouble(), m[5].toDouble(), m[12].toDouble(), m[13].toDouble())

    private fun near(v: Float) = abs(v) < 1e-4f
    private fun snap(v: Float) = if (near(v)) 0f else sign(v)
}
