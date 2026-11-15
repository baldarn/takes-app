package app.takes.core

import kotlin.math.ceil

/**
 * What one frame of a transition is made of, as plain numbers: nothing here
 * draws. "First" is the outgoing picture and "second" the incoming one, both
 * already drawn at canvas size with their own colour, crop and titles.
 *
 * Every share, weight and mix is meant to be applied in **linear light**, as
 * the original does: its renderer blends in extended linear sRGB, half float.
 * Blending gamma-encoded pixels with the same numbers gives darker midpoints
 * and softer edges. The one exception is the Hitchcock key, which is taken
 * from sRGB-encoded brightness (see [TransitionGeometry.LuminanceKey]).
 *
 * Canvas coordinates are the original's: origin at the bottom left, y upward.
 */
sealed interface TransitionFrame {
    /** The outgoing picture, untouched: progress is at or before 0, or a flash cut is between flashes. */
    object First : TransitionFrame

    /** The incoming picture, untouched: progress is at or past 1, or a flash cut is on a flash. */
    object Second : TransitionFrame

    /**
     * `first·(1 − second) + second·second` over the whole canvas. Used by
     * dissolve, by still (whose first picture is the held frame, see
     * [TransitionPicture.heldFrame]) and by a fade through color at the
     * opening or the close of the timeline.
     */
    data class Dissolve(val second: Double) : TransitionFrame {
        val first: Double get() = 1 - second
    }

    /**
     * A fade through [color] (sRGB components). The three weights add up to 1
     * and at most two are above 0: before the midpoint the first picture
     * gives way to the colour, from the midpoint the colour gives way to the
     * second. At the midpoint, and for the whole of a hold, the frame is the
     * colour alone.
     */
    data class FadeColor(val color: TransitionColor, val first: Double, val solid: Double, val second: Double) : TransitionFrame

    /** A gradient mask: the share of the second picture at a pixel is [TransitionGeometry.WipeEdge.secondShare]. */
    data class Wipe(val edge: TransitionGeometry.WipeEdge) : TransitionFrame

    /**
     * A split screen. The pair picture is black, then the first clip framed
     * in [first], the second framed in [second], then [line] filled with
     * [lineColor]. The frame is the first picture alone, the pair and the
     * second picture alone weighted by [mix].
     *
     * The clips are drawn without titles and cropped to their content (no
     * bars) before framing; the titles of both go once over the finished frame.
     */
    data class Split(
        val first: Rect,
        val second: Rect,
        val line: Rect,
        val lineColor: TransitionColor,
        val mix: TransitionGeometry.SplitMix,
        val firstFraming: TransitionFraming,
        val secondFraming: TransitionFraming,
    ) : TransitionFrame {
        /** The part of the first clip's content shown in [first] (top-down source coordinates) and its scale. */
        fun firstFramed(content: Size): TransitionGeometry.Framed = TransitionGeometry.framed(firstFraming, content, first)

        fun secondFramed(content: Size): TransitionGeometry.Framed = TransitionGeometry.framed(secondFraming, content, second)
    }

    /** A brightness key taken from the first picture: the share of the second at a pixel is [TransitionGeometry.LuminanceKey.secondShare]. */
    data class Hitchcock(val key: TransitionGeometry.LuminanceKey) : TransitionFrame

    /**
     * A whip pan. Each picture has its edge pixels extended outward without
     * limit, is moved by its offset (canvas pixels: to the right, or down the
     * screen when [vertical]), blurred along the travel by [blurRadius] at
     * [blurAngle] degrees, and cropped to the canvas; then the two are mixed
     * by [mix], the share of the second.
     *
     * [blurScale] is the factor the picture may be shrunk by before the blur;
     * offsets and radius are then multiplied by it and the result scaled back.
     */
    data class Whip(
        val vertical: Boolean,
        val firstOffset: Double,
        val secondOffset: Double,
        val blurRadius: Double,
        val blurAngle: Double,
        val blurScale: Double,
        val mix: Double,
    ) : TransitionFrame

    /**
     * Defocus. Each picture has its edge pixels extended outward, gets a disc
     * (aperture) blur of [blurRadius] canvas pixels with the given ring and
     * softness, and is cropped to the canvas; the two are mixed by [mix], the
     * share of the second. At a [mix] of 0 or 1 only one picture is needed.
     */
    data class Defocus(
        val blurRadius: Double,
        val blurScale: Double,
        val mix: Double,
        val ringAmount: Double = TransitionPicture.BOKEH_RING_AMOUNT,
        val ringSize: Double = TransitionPicture.BOKEH_RING_SIZE,
        val softness: Double = TransitionPicture.BOKEH_SOFTNESS,
    ) : TransitionFrame
}

/**
 * The picture recipe of each transition style: which easing it applies to
 * progress and what the renderer then needs. Progress is linear in time, 0 at
 * the first clip and 1 at the second, as [TransitionTiming.progress] gives it.
 *
 * Easing by style, as in the original's renderer:
 * - dissolve, still, wipe, Hitchcock: smoothstep, t²(3 − 2t);
 * - whip pan: smoothstep for the slide and the mix, linear for the blur;
 * - fade through color, split screen, flash cut, defocus: linear.
 *
 * All mixes are meant to be applied in linear light (see [TransitionFrame]).
 */
object TransitionPicture {
    /** The solid picture standing in for a missing side, and the colour of a fade's midpoint: sRGB, opaque. */
    fun fill(settings: TransitionSettings): TransitionColor =
        if (settings.style == TransitionStyle.FadeColor) settings.resolvedFadeColor else TransitionColor.BLACK

    /**
     * The style actually drawn. At the opening ([edge] −1) and the close (+1)
     * of the timeline a fade through color is a dissolve between [fill] and
     * the clip, eased like any dissolve.
     */
    fun drawnStyle(settings: TransitionSettings, edge: Int = 0): TransitionStyle =
        if (settings.style == TransitionStyle.FadeColor && edge != 0) TransitionStyle.Dissolve else settings.style

    /**
     * Everything needed to draw the frame at [progress]. A side that has no
     * clip (the first at an opening, the second at a close, or both in the
     * middle of a color hold) is [fill].
     *
     * [duration] is the transition's fitted picture length
     * ([TransitionTiming.videoDuration]) and [framesPerSecond] the frame rate
     * of the output; only a flash cut uses them, and preview and export must
     * pass the same values or the flashes land on different frames.
     */
    fun frame(
        settings: TransitionSettings,
        progress: Double,
        canvas: Size,
        edge: Int = 0,
        duration: Double? = null,
        framesPerSecond: Double = 30.0,
    ): TransitionFrame {
        val t = clamped(progress)
        if (t <= 0) return TransitionFrame.First
        if (t >= 1) return TransitionFrame.Second
        return when (drawnStyle(settings, edge)) {
            TransitionStyle.Dissolve, TransitionStyle.Still -> dissolve(t)
            TransitionStyle.FadeColor -> fadeColor(settings, t)
            TransitionStyle.Wipe -> wipe(settings, t, canvas)
            TransitionStyle.Split -> split(settings, t, canvas)
            TransitionStyle.Hitchcock -> hitchcock(settings, t)
            TransitionStyle.Whip -> whip(settings, t, canvas)
            TransitionStyle.FlashCut -> flashCut(settings, t, duration, framesPerSecond)
            TransitionStyle.Defocus -> defocus(settings, t, canvas)
        }
    }

    /** The frame [elapsed] seconds after the transition's origin, with the fitted [timing] of the join. */
    fun frame(
        settings: TransitionSettings,
        timing: TransitionTiming,
        elapsed: Double,
        canvas: Size,
        edge: Int = 0,
        framesPerSecond: Double = 30.0,
    ): TransitionFrame = frame(settings, timing.progress(elapsed), canvas, edge, timing.videoDuration, framesPerSecond)

    /** Dissolve and still: the second picture's share is the eased progress. A hold is an even mix of two running pictures. */
    fun dissolve(progress: Double): TransitionFrame.Dissolve = TransitionFrame.Dissolve(TransitionGeometry.eased(progress))

    /**
     * Fade through color between two clips: linear, not eased. The first half
     * of progress takes the first picture to the colour, the second half
     * brings the second picture up from it.
     */
    fun fadeColor(settings: TransitionSettings, progress: Double): TransitionFrame.FadeColor {
        val t = clamped(progress)
        val color = fill(settings)
        if (t < 0.5) {
            val solid = minOf(1.0, t * 2)
            return TransitionFrame.FadeColor(color, 1 - solid, solid, 0.0)
        }
        val second = maxOf(0.0, t * 2 - 1)
        return TransitionFrame.FadeColor(color, 0.0, 1 - second, second)
    }

    /** Wipe: the edge travels by the eased progress. */
    fun wipe(settings: TransitionSettings, progress: Double, canvas: Size): TransitionFrame.Wipe =
        TransitionFrame.Wipe(TransitionGeometry.wipe(settings, progress, canvas))

    /** Split screen: linear, with a brief blend in and out of the pair. */
    fun split(settings: TransitionSettings, progress: Double, canvas: Size): TransitionFrame.Split {
        val (first, second) = TransitionGeometry.splitRects(settings, canvas)
        return TransitionFrame.Split(
            first, second, TransitionGeometry.splitLine(settings, canvas), SPLIT_LINE_COLOR,
            TransitionGeometry.splitMix(progress), settings.first, settings.second,
        )
    }

    /** Hitchcock: the brightness threshold sweeps by the eased progress. */
    fun hitchcock(settings: TransitionSettings, progress: Double): TransitionFrame.Hitchcock =
        TransitionFrame.Hitchcock(TransitionGeometry.hitchcock(settings, progress))

    /** Whip pan: eased slide and mix, blur following the linear progress. */
    fun whip(settings: TransitionSettings, progress: Double, canvas: Size): TransitionFrame.Whip {
        val whip = TransitionGeometry.whip(settings, progress, canvas)
        return TransitionFrame.Whip(
            whip.vertical, whip.firstOffset, whip.secondOffset, whip.blurRadius, if (whip.vertical) 90.0 else 0.0,
            TransitionGeometry.blurScale(whip.blurRadius, canvas), whip.mix,
        )
    }

    /** Flash cut: whole frames only, never a blend. */
    fun flashCut(settings: TransitionSettings, progress: Double, duration: Double? = null, framesPerSecond: Double = 30.0): TransitionFrame =
        if (TransitionGeometry.flashShowsSecond(settings, progress, duration, framesPerSecond)) TransitionFrame.Second else TransitionFrame.First

    /** Defocus: linear for the blur and for the window in which the pictures change over. */
    fun defocus(settings: TransitionSettings, progress: Double, canvas: Size): TransitionFrame.Defocus {
        val defocus = TransitionGeometry.defocus(settings, progress, canvas)
        return TransitionFrame.Defocus(defocus.blurRadius, TransitionGeometry.blurScale(defocus.blurRadius, canvas), defocus.mix)
    }

    /**
     * The frame a still holds. From [from] to [until] (timeline seconds) the
     * outgoing clip shows the frame it showed at [shown], which is [offset]
     * timeline seconds into the clip (`clip.sourceTime(offset)` gives the
     * source second), with the clip's colour, crop and titles as at that instant.
     */
    data class HeldFrame(val from: Double, val until: Double, val shown: Double, val offset: Double)

    /**
     * The held frame of a still whose picture effect starts at [origin]: the
     * last frame of the outgoing clip actually shown before the freeze, on
     * the output's frame grid. [clipStart] and [clipDuration] are the
     * outgoing clip's place and played length on the timeline.
     *
     * Null when [timing] is not a still or holds nothing. A still at the
     * opening of the timeline has no outgoing clip: it holds [fill] instead.
     */
    fun heldFrame(timing: TransitionTiming, origin: Double, clipStart: Double, clipDuration: Double, framesPerSecond: Double = 30.0): HeldFrame? {
        if (timing.style != TransitionStyle.Still) return null
        val clipEnd = clipStart + clipDuration
        val from = maxOf(clipStart, minOf(clipEnd, origin))
        val until = origin + timing.videoDuration
        if (!(until > from)) return null
        val shown = maxOf(clipStart, (ceil(from * framesPerSecond - 0.000001) - 1) / framesPerSecond)
        return HeldFrame(from, until, shown, maxOf(0.0, minOf(clipDuration - HELD_FRAME_MARGIN, shown - clipStart)))
    }

    private fun clamped(value: Double) = minOf(1.0, maxOf(0.0, value))

    /** The divider of a split screen. */
    val SPLIT_LINE_COLOR = TransitionColor(0.25, 0.25, 0.25)

    /** The aperture blur of defocus: a faint bright ring at the rim of each disc. */
    const val BOKEH_RING_AMOUNT = 0.12
    const val BOKEH_RING_SIZE = 0.1
    const val BOKEH_SOFTNESS = 0.25

    /** The held frame is sampled at least this far before the end of the clip's footage. */
    const val HELD_FRAME_MARGIN = 1.0 / 60000
}
