package app.takes.core

/**
 * The editor preview's inspection zoom, as the original's player view keeps
 * it (YouRec/EditorView.swift:1528-1543, 1559-1601, 1621-1628): a pinch
 * magnifies the picture from 1 to 8 times about the point between the
 * fingers, a drag moves it while magnified, and the picture is never moved
 * past the edges of the view, nor, at 1×, at all. It changes only what the
 * preview shows, never the export.
 *
 * [x] and [y] are how far the magnified picture's centre lies from the
 * view's centre, in the view's pixels.
 */
data class PreviewInspection(val zoom: Double = 1.0, val x: Double = 0.0, val y: Double = 0.0) {
    /** Whether the picture is magnified, and Reset preview zoom shows (EditorView.swift:1543). */
    val zoomed: Boolean get() = zoom > 1.001

    /**
     * After a step of a pinch whose fingers' centre went from ([centreX] − [panX], [centreY] − [panY]) to
     * ([centreX], [centreY]) while their spread changed by [factor]: the point that was under the fingers stays
     * under them (EditorView.swift:1593-1597), within [bounds] for a picture fitted in it at [fit].
     */
    fun pinched(factor: Double, centreX: Double, centreY: Double, panX: Double, panY: Double, bounds: Size, fit: Size): PreviewInspection {
        val next = (zoom * factor).coerceIn(1.0, MAXIMUM)
        val ratio = next / zoom
        val midX = bounds.width / 2
        val midY = bounds.height / 2
        return PreviewInspection(
            next,
            centreX - midX - (centreX - panX - midX - x) * ratio,
            centreY - midY - (centreY - panY - midY - y) * ratio,
        ).clamped(bounds, fit)
    }

    /** Moved by a drag of one finger (EditorView.swift:1621-1628). */
    fun panned(dx: Double, dy: Double, bounds: Size, fit: Size): PreviewInspection =
        copy(x = x + dx, y = y + dy).clamped(bounds, fit)

    /** Magnified [factor] times where it is, as the screen reader's Zoom in and Zoom out do (EditorView.swift:1471-1474, 1601). */
    fun stepped(factor: Double, bounds: Size, fit: Size): PreviewInspection =
        copy(zoom = (zoom * factor).coerceIn(1.0, MAXIMUM)).clamped(bounds, fit)

    /** Kept so the magnified picture still covers what it can of the view (EditorView.swift:1531-1533). */
    fun clamped(bounds: Size, fit: Size): PreviewInspection {
        val limitX = maxOf(0.0, (fit.width * zoom - bounds.width) / 2)
        val limitY = maxOf(0.0, (fit.height * zoom - bounds.height) / 2)
        // Adding zero turns a negative zero into zero.
        return copy(x = x.coerceIn(-limitX, limitX) + 0.0, y = y.coerceIn(-limitY, limitY) + 0.0)
    }

    companion object {
        /** The most the picture is magnified (EditorView.swift:1592, 1601). */
        const val MAXIMUM = 8.0

        /** How much a screen reader's Zoom in or Zoom out changes it (EditorView.swift:1472-1473). */
        const val STEP = 1.5

        /** The size of a picture of [canvas]'s shape fitted inside [bounds], or [bounds] when the shape is unknown (EditorView.swift:1530). */
        fun fitted(canvas: Size?, bounds: Size): Size {
            if (canvas == null || canvas.width <= 0 || canvas.height <= 0) return bounds
            val scale = minOf(bounds.width / canvas.width, bounds.height / canvas.height)
            return Size(canvas.width * scale, canvas.height * scale)
        }
    }
}
