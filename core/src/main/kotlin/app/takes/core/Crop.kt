package app.takes.core

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Framing is stored in the oriented source, independent of preview size.
 * The output aspect belongs to the project; a crop fills that output frame.
 *
 * [x] and [y] are the center of the window as a fraction of the source,
 * [zoom] magnifies beyond the smallest window that fills the canvas, and
 * [rotation] is clockwise degrees, applied before output-space mirrors and overlays.
 */
@Serializable
data class CropSettings(
    val id: String = newID(),
    val x: Double = 0.5,
    val y: Double = 0.5,
    val zoom: Double = 1.0,
    val rotation: Double = 0.0,
) {
    private val radians: Double get() = rotation * Math.PI / 180

    /**
     * The crop window is axis-aligned in output space. Its inverse-rotated
     * footprint must fit inside the source, including when panned to an edge.
     */
    fun rect(source: Size, canvas: Size): Rect {
        if (!(source.width > 0 && source.height > 0 && canvas.width > 0 && canvas.height > 0)) return Rect.ZERO
        val c = abs(cos(radians))
        val s = abs(sin(radians))
        val scale = maxOf(
            (canvas.width * c + canvas.height * s) / source.width,
            (canvas.width * s + canvas.height * c) / source.height,
        ) * minOf(MAXIMUM_ZOOM, maxOf(MINIMUM_ZOOM, zoom))
        val width = canvas.width / scale
        val height = canvas.height / scale
        val halfX = (width * c + height * s) / 2
        val halfY = (width * s + height * c) / 2
        val cx = minOf(source.width - halfX, maxOf(halfX, x * source.width))
        val cy = minOf(source.height - halfY, maxOf(halfY, y * source.height))
        return Rect(cx - width / 2, cy - height / 2, width, height)
    }

    /** The axis-aligned box of source pixels the rotated window touches. */
    fun footprint(source: Size, canvas: Size): Rect {
        val area = rect(source, canvas)
        val c = abs(cos(radians))
        val s = abs(sin(radians))
        val width = area.width * c + area.height * s
        val height = area.width * s + area.height * c
        return Rect(area.midX - width / 2, area.midY - height / 2, width, height)
    }

    /** Source pixels to canvas pixels: center the window, rotate, scale to the canvas, center on it. */
    fun transform(source: Size, canvas: Size): Affine {
        val area = rect(source, canvas)
        if (!(area.width > 0)) return Affine.IDENTITY
        val scale = canvas.width / area.width
        return Affine.translation(-area.midX, -area.midY)
            .concatenating(Affine.rotation(radians))
            .concatenating(Affine.scale(scale, scale))
            .concatenating(Affine.translation(canvas.width / 2, canvas.height / 2))
    }

    /** The same picture with stored values inside their limits: what [rect] already shows. */
    fun clamped(source: Size, canvas: Size): CropSettings {
        val limited = copy(zoom = minOf(MAXIMUM_ZOOM, maxOf(MINIMUM_ZOOM, zoom)), rotation = normalizedRotation(rotation))
        val rect = limited.rect(source, canvas)
        return limited.copy(x = rect.midX / maxOf(1.0, source.width), y = rect.midY / maxOf(1.0, source.height))
    }

    fun isFullFrame(source: Size, canvas: Size): Boolean {
        if (!(abs(normalizedRotation(rotation)) < 0.00001 && zoom == 1.0 && source.width > 0 && source.height > 0)) return false
        val area = rect(source, canvas)
        // Encoder-safe even dimensions can slightly change an imported image's
        // aspect. That rounding alone must not create a crop or split a range.
        return abs(area.width - source.width) < maxOf(2.0, source.width * 0.0025) &&
            abs(area.height - source.height) < maxOf(2.0, source.height * 0.0025)
    }

    /** The ranges a saved project may hold. */
    val valid: Boolean
        get() = x.isFinite() && y.isFinite() && zoom.isFinite() && rotation.isFinite() &&
            rotation in -180.0..180.0 && x in 0.0..1.0 && y in 0.0..1.0 && zoom in MINIMUM_ZOOM..MAXIMUM_ZOOM

    companion object {
        const val MINIMUM_ZOOM = 1.0
        const val MAXIMUM_ZOOM = 8.0

        /** Any angle as −180…180. A positive half turn stays +180, so a slider at its end does not jump. */
        fun normalizedRotation(angle: Double): Double {
            if (!angle.isFinite()) return 0.0
            val value = (angle + 180) % 360
            val normalized = (if (value < 0) value + 360 else value) - 180
            return if (normalized == -180.0 && angle > 0) 180.0 else normalized
        }
    }
}

/** Flips in output space. */
@Serializable
data class MirrorSettings(val id: String = newID(), val horizontal: Boolean = false, val vertical: Boolean = false) {
    fun transform(canvas: Size): Affine = Affine(
        if (horizontal) -1.0 else 1.0, 0.0, 0.0, if (vertical) -1.0 else 1.0,
        if (horizontal) canvas.width else 0.0, if (vertical) canvas.height else 0.0,
    )
}

/** The shapes a project can be reframed to. Not saved: the project stores the resulting canvas size. */
enum class OutputShape(val title: String, val aspect: Double?) {
    Original("Original", null),
    Landscape("16:9", 16.0 / 9),
    Portrait("9:16", 9.0 / 16),
    Square("1:1", 1.0),
    Feed("4:5", 4.0 / 5);

    companion object {
        fun canvas(source: Size, aspect: Double?): Size {
            if (aspect == null) return source
            // Keep the source's short-edge resolution, rounded to encoder-safe pixels.
            val edge = minOf(source.width, source.height)
            return Size(
                maxOf(2.0, roundedAwayFromZero((if (aspect >= 1) edge * aspect else edge) / 2) * 2),
                maxOf(2.0, roundedAwayFromZero((if (aspect >= 1) edge else edge / aspect) / 2) * 2),
            )
        }
    }
}
