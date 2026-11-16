package app.takes.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Everything a renderer needs to draw one frame of a clip into the movie's
 * frame. The same numbers serve the preview and the export.
 *
 * All pixel coordinates have their origin at the top left with y growing
 * downward, as on screen. "Source" is the footage after its recorded
 * orientation is applied, so a portrait take is taller than wide.
 */
data class PictureFrame(
    /** The source second the motion was sampled at. */
    val sourceTime: Double,
    /** The framing shown, already sampled: it never carries a motion. Null fits the whole picture inside the frame. */
    val crop: CropSettings?,
    val source: Size,
    val canvas: Size,
    /**
     * The movie's frame measured in source pixels, before the turn: its size
     * is the canvas divided by [scale] and its center is the center of what
     * is shown. With a rotation the pixels really shown are this rectangle
     * turned back around its center; see [corners] and [footprint].
     */
    val window: Rect,
    /** The upright box of source pixels the frame touches. It always lies inside the source. */
    val footprint: Rect,
    /** Clockwise degrees, −180…180. */
    val rotation: Double,
    /** Canvas pixels per source pixel. */
    val scale: Double,
    /** Source pixels to canvas pixels, mirror included. */
    val transform: Affine,
    /** How far the motion is from A (0) to B (1); null when the framing does not move. */
    val fraction: Double?,
    /** The flips in [transform], after the framing. */
    val mirror: MirrorSettings? = null,
) {
    /**
     * The same picture as a view that fits the whole source inside a box the
     * size of [canvas] shows it once enlarged, turned, flipped and moved by
     * these numbers about its middle: what the preview gives a graphics
     * layer. Scaling comes before the turn there, so a single flip turns the
     * other way.
     */
    val layer: ViewLayer
        get() {
            val fitted = minOf(canvas.width / source.width, canvas.height / source.height)
            val enlarge = if (fitted > 0) scale / fitted else 1.0
            val middle = Point(source.width / 2, source.height / 2).applying(transform)
            val acrossFlip = mirror?.horizontal == true
            val downFlip = mirror?.vertical == true
            return ViewLayer(
                scaleX = if (acrossFlip) -enlarge else enlarge,
                scaleY = if (downFlip) -enlarge else enlarge,
                rotation = if (acrossFlip != downFlip) -rotation else rotation,
                translationX = middle.x - canvas.width / 2,
                translationY = middle.y - canvas.height / 2,
            )
        }

    /** The source points shown at the canvas's top left, top right, bottom left and bottom right. */
    val corners: List<Point>
        get() {
            val inverse = transform.inverted()
            return listOf(Point.ZERO, Point(canvas.width, 0.0), Point(0.0, canvas.height), Point(canvas.width, canvas.height))
                .map { it.applying(inverse) }
        }

    /** Whether the frame is filled edge to edge. Only an uncropped clip of another shape leaves bars. */
    val fills: Boolean get() = crop != null || abs(source.width * canvas.height - source.height * canvas.width) < 0.000001 * source.height * canvas.height
}

/** A graphics layer's scale, clockwise turn in degrees and move, in the pixels of the view; see [PictureFrame.layer]. */
data class ViewLayer(val scaleX: Double, val scaleY: Double, val rotation: Double, val translationX: Double, val translationY: Double)

object PictureFraming {
    /**
     * The smallest magnification, in canvas pixels per source pixel, at which
     * a frame turned by [rotation] degrees has no empty corner: the turned
     * frame's bounding box just fits the source. A crop's zoom multiplies it.
     */
    fun coverScale(rotation: Double, source: Size, canvas: Size): Double {
        if (!(source.width > 0 && source.height > 0 && canvas.width > 0 && canvas.height > 0)) return 0.0
        val radians = rotation * Math.PI / 180
        val c = abs(cos(radians))
        val s = abs(sin(radians))
        return maxOf((canvas.width * c + canvas.height * s) / source.width, (canvas.width * s + canvas.height * c) / source.height)
    }

    /**
     * How much closer than the upright framing a turn by [rotation] degrees
     * has to be to keep the corners filled. Below 1 when the turn lets the
     * frame see more, as a portrait frame turned a quarter on a landscape take.
     */
    fun rotationZoom(rotation: Double, source: Size, canvas: Size): Double {
        val upright = coverScale(0.0, source, canvas)
        return if (upright > 0) coverScale(rotation, source, canvas) / upright else 1.0
    }

    /** An uncropped clip: the whole picture, centered, as large as fits. */
    fun fit(source: Size, canvas: Size): Affine {
        if (!(source.width > 0 && source.height > 0)) return Affine.IDENTITY
        val scale = minOf(canvas.width / source.width, canvas.height / source.height)
        return Affine.scale(scale, scale)
            .concatenating(Affine.translation((canvas.width - source.width * scale) / 2, (canvas.height - source.height * scale) / 2))
    }

    /**
     * The source second a clip's motions are sampled at, [offset] timeline
     * seconds into the clip. [cuts] are source ranges removed from it.
     */
    fun motionTime(clip: TimelineClip, offset: Double, cuts: List<SourceInterval> = emptyList()): Double =
        SpeedMapping.sourceTime(offset, SpeedMapping.spans(clip, clip.speed, cuts), clip.end)

    /** A held frame shows one source second, but its motions keep running over the hold. */
    fun motionTime(hold: FrozenFrameSegment, elapsed: Double): Double = hold.sourceTime + hold.offset + maxOf(0.0, elapsed)

    /** The frame of a clip [offset] timeline seconds into it, flipped as the clip is unless [mirror] says otherwise. */
    fun frame(
        clip: TimelineClip,
        offset: Double,
        source: Size,
        canvas: Size,
        mirror: MirrorSettings? = clip.mirror,
        cuts: List<SourceInterval> = emptyList(),
    ): PictureFrame = frame(clip.crop, motionTime(clip, offset, cuts), source, canvas, mirror)

    /** The frame of a framing at a source second. A moving [crop] is sampled there. */
    fun frame(crop: CropSettings?, sourceTime: Double, source: Size, canvas: Size, mirror: MirrorSettings? = null): PictureFrame {
        val flip = mirror?.transform(canvas) ?: Affine.IDENTITY
        if (crop == null) {
            val whole = Rect(0.0, 0.0, source.width, source.height)
            val scale = if (source.width > 0 && source.height > 0) minOf(canvas.width / source.width, canvas.height / source.height) else 1.0
            return PictureFrame(sourceTime, null, source, canvas, whole, whole, 0.0, scale, fit(source, canvas).concatenating(flip), null, mirror)
        }
        val shown = crop.at(sourceTime)
        val window = shown.rect(source, canvas)
        return PictureFrame(
            sourceTime = sourceTime,
            crop = shown,
            source = source,
            canvas = canvas,
            window = window,
            footprint = shown.footprint(source, canvas),
            rotation = CropSettings.normalizedRotation(shown.rotation),
            scale = if (window.width > 0) canvas.width / window.width else 1.0,
            transform = shown.transform(source, canvas).concatenating(flip),
            fraction = crop.motion?.fraction(sourceTime),
            mirror = mirror,
        )
    }
}

/**
 * The same transform between normalized device coordinates: −1…1 across each
 * picture, x to the right and y upward, as a vertex shader sees them.
 */
fun Affine.normalized(source: Size, canvas: Size): Affine =
    Affine(source.width / 2, 0.0, 0.0, -source.height / 2, source.width / 2, source.height / 2)
        .concatenating(this)
        .concatenating(Affine(2 / canvas.width, 0.0, 0.0, -2 / canvas.height, -1.0, 1.0))

/** The nine values of a 3×3 matrix, row by row: the order `android.graphics.Matrix.setValues` takes. */
fun Affine.matrixValues(): FloatArray =
    floatArrayOf(a.toFloat(), c.toFloat(), tx.toFloat(), b.toFloat(), d.toFloat(), ty.toFloat(), 0f, 0f, 1f)
