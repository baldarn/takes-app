package app.takes.core

import kotlinx.serialization.Serializable

/** A picture over the footage. Times are source seconds, so it follows its clip through trims and moves. */
@Serializable
data class ImageOverlay(
    val id: String = newID(),
    val filename: String,
    val start: Double = 0.0,
    val end: Double = 3.0,
    val x: Double = 0.5,
    val y: Double = 0.5,
    /** Fraction of the shorter canvas edge; the original aspect is retained. */
    val width: Double = 0.4,
    val opacity: Double = 1.0,
    val motion: EffectAnimation? = null,
) {
    /**
     * Where the image is drawn. [y] is measured from the top, as touch input is;
     * the rectangle is in the original's bottom-up canvas coordinates, so a
     * top-down renderer must flip it (`canvas.height - rect.maxY`).
     */
    fun rect(image: Size, canvas: Size): Rect {
        val w = minOf(canvas.width, canvas.height) * width
        val h = w * image.height / maxOf(1.0, image.width)
        return Rect(canvas.width * x - w / 2, canvas.height * (1 - y) - h / 2, w, h)
    }

    val pose: EffectPose get() = EffectPose(mapOf("x" to x, "y" to y, "width" to width, "opacity" to opacity))

    /** This overlay fixed at [pose], no longer moving. */
    fun applying(pose: EffectPose): ImageOverlay =
        copy(x = pose["x"], y = pose["y"], width = pose["width"], opacity = pose["opacity"], motion = null)

    /** The ranges a saved project may hold. The owner also checks [end] against its source's length. */
    val valid: Boolean
        get() = start.isFinite() && end.isFinite() && start >= 0 && end > start && accepts(pose) &&
            (motion?.valid(pose) { accepts(it) } ?: true)

    companion object {
        fun accepts(pose: EffectPose): Boolean =
            pose["x"] in 0.0..1.0 && pose["y"] in 0.0..1.0 && pose["width"] in 0.03..3.0 && pose["opacity"] in 0.0..1.0
    }
}
