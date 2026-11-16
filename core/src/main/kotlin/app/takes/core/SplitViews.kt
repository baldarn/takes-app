package app.takes.core

/**
 * A split screen drawn with plain views instead of a shader, as the preview
 * draws it before Android 13. Each side is a view the size of the frame that
 * shows its clip as the deck shows it, with the clip's own picture at its
 * content rectangle; the view is scaled about its top left corner, moved and
 * clipped so that its clip lies in its half framed as the export frames it
 * (YouRec/TransitionRenderer.swift:252-264, `framed` at :284-292), with the
 * numbers of [TransitionFrame.Split]. Coordinates here are top-down frame
 * pixels, as views count them.
 *
 * The pair is exact. Its brief entry and exit, which the original dissolves
 * from the first picture and to the second (:261-263), cannot be: one player
 * shows one view, and the clip that is alone is also one of the pair. So
 * while the picture alone outweighs the pair it is shown whole, with the
 * other half and the line fading in over it by the pair's share; from the
 * moment the pair outweighs it, the pair alone is shown.
 */
object SplitViews {
    /**
     * A view the size of the frame scaled by [scale] about its top left
     * corner, then moved by [translationX] and [translationY], and clipped to
     * [shown] (frame pixels).
     */
    data class Placed(val shown: Rect, val scale: Double, val translationX: Double, val translationY: Double)

    /** One side: [placed] in its half, or whole and unmoved when null; how opaque it is; its place back to front. */
    data class Side(val placed: Placed?, val alpha: Double, val rank: Int)

    /** Both sides, and the divider ([line], frame pixels) with how opaque it is; the line is over both. */
    data class Drawing(val first: Side, val second: Side, val line: Rect, val lineAlpha: Double)

    /**
     * Where the view of a clip goes so that the part [framed] picks of its
     * content, which lies at [content] in the view, fills [half] (the core's
     * bottom-up coordinates on [canvas]).
     */
    fun placed(half: Rect, framed: TransitionGeometry.Framed, content: Rect, canvas: Size): Placed {
        val shown = topDown(half, canvas)
        // The view's point at the corner of the crop lands on the corner of the half.
        return Placed(
            shown, framed.scale,
            shown.x - framed.scale * (content.x + framed.crop.x),
            shown.y - framed.scale * (content.y + framed.crop.y),
        )
    }

    /**
     * The drawing of [frame] on [canvas]. [firstContent] and [secondContent]
     * are where each side's clip lies in its view (frame pixels, without the
     * bars around it); a side with no clip is the fill color, everywhere.
     */
    fun of(frame: TransitionFrame.Split, canvas: Size, firstContent: Rect, secondContent: Rect): Drawing {
        val first = placed(frame.first, frame.firstFramed(firstContent.size), firstContent, canvas)
        val second = placed(frame.second, frame.secondFramed(secondContent.size), secondContent, canvas)
        val line = topDown(frame.line, canvas)
        val mix = frame.mix
        return when {
            mix.first > mix.pair -> Drawing(Side(null, 1.0, 0), Side(second, mix.pair, 1), line, mix.pair)
            mix.second > mix.pair -> Drawing(Side(first, mix.pair, 1), Side(null, 1.0, 0), line, mix.pair)
            else -> Drawing(Side(first, 1.0, 1), Side(second, 1.0, 1), line, 1.0)
        }
    }

    private fun topDown(rect: Rect, canvas: Size) = Rect(rect.x, canvas.height - rect.maxY, rect.width, rect.height)
}
