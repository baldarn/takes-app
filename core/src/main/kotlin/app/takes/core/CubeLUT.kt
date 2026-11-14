package app.takes.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.math.abs

/** A .cube file that cannot be used. The message is written for the person who chose it. */
class LUTException(message: String) : Exception(message)

/**
 * Strict, bounded CUBE reader. Red is the fastest-changing axis. Both standalone
 * 1D curves and 3D cubes are supported; combined shaper+cube files are rejected
 * explicitly rather than silently discarding their shaper. Values may exceed 1.
 *
 * A 3D cube keeps its lattice in [rgb], three floats per point. A 1D curve
 * keeps its samples in [curves], three floats per sample, and leaves [rgb] empty.
 */
class CubeLUT private constructor(
    val dimension: Int,
    val rgb: FloatArray,
    val curves: FloatArray?,
    val domainMin: DoubleArray,
    val domainMax: DoubleArray,
    val title: String?,
) {
    /**
     * One color through the table: trilinear between the eight surrounding
     * lattice points of a cube, linear along each channel of a curve. Input
     * outside the domain uses the nearest edge; nothing is extrapolated.
     */
    fun sample(r: Float, g: Float, b: Float): FloatArray {
        val last = dimension - 1
        val index = IntArray(3)
        val fraction = FloatArray(3)
        val input = floatArrayOf(r, g, b)
        for (c in 0..2) {
            val normalized = (input[c] - domainMin[c]) / (domainMax[c] - domainMin[c])
            // A NaN channel has no place in the table; treat it as the low edge.
            val position = (if (normalized.isNaN()) 0.0 else normalized.coerceIn(0.0, 1.0)) * last
            index[c] = minOf(position.toInt(), last - 1)
            fraction[c] = (position - index[c]).toFloat()
        }
        val output = FloatArray(3)
        if (curves != null) {
            for (c in 0..2) {
                val low = curves[index[c] * 3 + c]
                output[c] = low + (curves[(index[c] + 1) * 3 + c] - low) * fraction[c]
            }
            return output
        }
        for (corner in 0..7) {
            val dr = corner and 1
            val dg = corner shr 1 and 1
            val db = corner shr 2 and 1
            val weight = (if (dr == 1) fraction[0] else 1 - fraction[0]) *
                (if (dg == 1) fraction[1] else 1 - fraction[1]) *
                (if (db == 1) fraction[2] else 1 - fraction[2])
            if (weight == 0f) continue
            val point = (((index[2] + db) * dimension + index[1] + dg) * dimension + index[0] + dr) * 3
            for (c in 0..2) output[c] += rgb[point + c] * weight
        }
        return output
    }

    companion object {
        const val MAXIMUM_BYTES = 32 * 1024 * 1024

        private const val LIMIT = 65536.0
        private val newlines = Regex("[\\n\\r\\u000B\\u000C\\u0085\\u2028\\u2029]+")

        /** Reads the bytes of a .cube file. */
        fun parse(data: ByteArray): CubeLUT {
            if (data.size > MAXIMUM_BYTES) throw LUTException("Choose a UTF-8 .cube file smaller than 32 MB.")
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = try { decoder.decode(ByteBuffer.wrap(data)).toString() } catch (_: CharacterCodingException) {
                throw LUTException("Choose a UTF-8 .cube file smaller than 32 MB.")
            }
            return parse(text)
        }

        fun parse(text: String): CubeLUT {
            var size: Int? = null
            var oneD = false
            var title: String? = null
            var low = doubleArrayOf(0.0, 0.0, 0.0)
            var high = doubleArrayOf(1.0, 1.0, 1.0)
            var rows = FloatArray(0)
            var count = 0
            val seen = mutableSetOf<String>()
            fun expected(size: Int) = if (oneD) size else size * size * size

            for (raw in text.split(newlines)) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
                val first = parts.firstOrNull() ?: continue
                if (first == "TITLE") {
                    if (!seen.add("TITLE")) throw LUTException("The LUT contains duplicate TITLE entries.")
                    title = line.drop(5).trim('"', ' ').take(120)
                    continue
                }
                if (first == "LUT_3D_SIZE" || first == "LUT_1D_SIZE") {
                    val value = if (size == null && count == 0 && parts.size == 2) parts[1].toIntOrNull() else null
                    if (value == null || value !in (if (first == "LUT_3D_SIZE") 2..65 else 2..16384)) {
                        throw LUTException("Use a standalone 3D LUT (2–65 points) or 1D LUT (2–16,384 points). Combined shaper LUTs are not supported.")
                    }
                    size = value
                    oneD = first == "LUT_1D_SIZE"
                    continue
                }
                if (first == "DOMAIN_MIN" || first == "DOMAIN_MAX") {
                    if (count != 0 || first in seen || parts.size != 4) throw LUTException("Invalid LUT input domain.")
                    val values = parts.drop(1).mapNotNull { if (plain(it)) it.toDoubleOrNull() else null }
                    if (values.size != 3 || !values.all { it.isFinite() && abs(it) <= LIMIT }) throw LUTException("Invalid LUT input domain.")
                    if (first == "DOMAIN_MIN") low = values.toDoubleArray() else high = values.toDoubleArray()
                    seen += first
                    continue
                }
                // Anything else must be a color entry; an unknown directive fails here.
                val total = size?.let(::expected)
                if (total == null || parts.size != 3 || count >= total) throw LUTException("Unsupported LUT header or incorrect number of color entries.")
                val values = parts.mapNotNull { if (plain(it)) it.toFloatOrNull() else null }
                if (values.size != 3 || !values.all { it.isFinite() && abs(it) <= LIMIT }) throw LUTException("The LUT contains an invalid color value.")
                if (rows.isEmpty()) rows = FloatArray(total * 3)
                for (c in 0..2) rows[count * 3 + c] = values[c]
                count++
            }
            val dimension = size
            if (dimension == null || count != expected(dimension) || !(0..2).all { high[it] > low[it] }) {
                throw LUTException("The LUT is incomplete or has an invalid input domain.")
            }
            return if (oneD) CubeLUT(dimension, FloatArray(0), rows, low, high, title) else CubeLUT(dimension, rows, null, low, high, title)
        }

        /** Java would also read a type suffix such as "1f" as a number, which no CUBE file means. */
        private fun plain(text: String): Boolean = text.last() !in "fFdD"
    }
}
