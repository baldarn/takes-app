package app.takes.core

import kotlin.math.abs
import kotlin.math.hypot

/**
 * The crop tool's picture area. The movie's frame is drawn as large as fits
 * inside the view, [inset] away from its edges, and the footage moves behind
 * it. Lengths are in the view's own units, origin at the top left.
 */
data class CropViewport(
    val view: Size,
    val source: Size,
    val canvas: Size,
    val mirror: MirrorSettings? = null,
    val inset: Double = 20.0,
) {
    /** Where the movie's frame sits in the view. */
    val window: Rect
        get() {
            val width = view.width - 2 * inset
            val height = view.height - 2 * inset
            if (!(width > 0 && height > 0 && canvas.width > 0 && canvas.height > 0)) return Rect.ZERO
            val scale = minOf(width / canvas.width, height / canvas.height)
            return Rect(inset + (width - canvas.width * scale) / 2, inset + (height - canvas.height * scale) / 2, canvas.width * scale, canvas.height * scale)
        }

    /** Source pixels to view positions for a framing: where to draw the footage. */
    fun transform(crop: CropSettings): Affine {
        val window = window
        val scale = window.width / maxOf(1.0, canvas.width)
        return crop.transform(source, canvas)
            .concatenating(mirror?.transform(canvas) ?: Affine.IDENTITY)
            .concatenating(Affine.scale(scale, scale))
            .concatenating(Affine.translation(window.minX, window.minY))
    }

    /** A distance on the view as a distance in source pixels, through the turn, the zoom and any mirror. */
    fun sourceDelta(delta: Point, crop: CropSettings): Point =
        delta.applying(transform(crop).copy(tx = 0.0, ty = 0.0).inverted())
}

/**
 * How touches and controls change a crop. Every function returns the crop to
 * show and store next; none keeps state, so a gesture carries its own start.
 */
object CropGestures {
    /** A drag does nothing until the finger has travelled this far. */
    const val PAN_SLOP = 8.0

    /** A rotation closer to upright than this is upright. */
    const val ROTATION_SNAP = 0.5

    /** The rotation slider moves in these steps, between −180 and 180. */
    const val ROTATION_STEP = 0.1

    /** One accessibility step multiplies or divides the zoom by this. */
    const val ZOOM_STEP = 1.1

    /** A crop inside the ranges a project may hold. The center may still point outside what can be shown. */
    fun limited(value: CropSettings): CropSettings = value.copy(
        x = minOf(1.0, maxOf(0.0, value.x)),
        y = minOf(1.0, maxOf(0.0, value.y)),
        zoom = minOf(CropSettings.MAXIMUM_ZOOM, maxOf(CropSettings.MINIMUM_ZOOM, value.zoom)),
        rotation = CropSettings.normalizedRotation(value.rotation),
    )

    /**
     * [limited], and on the chosen axes the center is replaced by the one
     * really shown, so a later move the other way responds at once. An axis
     * the gesture did not touch keeps its preferred center, which another
     * clip of a different shape may still be able to show.
     */
    fun settled(value: CropSettings, source: Size, canvas: Size, clampX: Boolean, clampY: Boolean): CropSettings {
        val crop = limited(value)
        val visible = crop.clamped(source, canvas)
        return crop.copy(x = if (clampX) visible.x else crop.x, y = if (clampY) visible.y else crop.y)
    }

    // MARK: Pinch

    /** What a pinch started from: the crop as shown, the source pixel under the fingers and the recognizer's scale then. */
    data class Pinch(val crop: CropSettings, val anchor: Point, val base: Double)

    /**
     * Begins a pinch at [focus], the point between the fingers. Begin again
     * whenever the number of fingers changes, so the picture does not jump.
     */
    fun pinchBegan(crop: CropSettings, focus: Point, scale: Double, viewport: CropViewport): Pinch {
        val shown = crop.clamped(viewport.source, viewport.canvas)
        return Pinch(shown, focus.applying(viewport.transform(shown).inverted()), scale)
    }

    /**
     * The crop while pinching: zoomed by how far the fingers spread since the
     * start, with the source pixel that was under them still under them. Two
     * fingers moving together therefore pan.
     *
     * [twist] turns the picture by that many clockwise degrees since the
     * start, around the same pixel; leave it at zero to pinch as the
     * original does, which has no twist gesture.
     */
    fun pinchChanged(pinch: Pinch, focus: Point, scale: Double, viewport: CropViewport, twist: Double = 0.0): CropSettings {
        val zoom = minOf(CropSettings.MAXIMUM_ZOOM, maxOf(CropSettings.MINIMUM_ZOOM, pinch.crop.zoom * scale / maxOf(0.001, pinch.base)))
        val turned = if (twist == 0.0) pinch.crop.rotation else snappedRotation(pinch.crop.rotation + twist)
        val value = pinch.crop.copy(zoom = zoom, rotation = turned)
        val window = viewport.window
        val delta = viewport.sourceDelta(Point(focus.x - window.midX, focus.y - window.midY), value)
        return settled(
            value.copy(x = (pinch.anchor.x - delta.x) / viewport.source.width, y = (pinch.anchor.y - delta.y) / viewport.source.height),
            viewport.source, viewport.canvas, clampX = true, clampY = true,
        )
    }

    // MARK: Drag

    /**
     * The crop after one finger has moved by [translation] since the drag
     * began on [start]; null while the finger is within [slop]. The picture
     * follows the finger. A second finger ends the drag; take a new [start]
     * when one finger is left again.
     */
    fun panChanged(start: CropSettings, translation: Point, viewport: CropViewport, slop: Double = PAN_SLOP): CropSettings? {
        if (hypot(translation.x, translation.y) < slop) return null
        val delta = viewport.sourceDelta(translation, start)
        val visible = start.clamped(viewport.source, viewport.canvas)
        val movedX = abs(delta.x) > 0.000001
        val movedY = abs(delta.y) > 0.000001
        return settled(
            start.copy(
                x = if (movedX) visible.x - delta.x / viewport.source.width else start.x,
                y = if (movedY) visible.y - delta.y / viewport.source.height else start.y,
            ),
            viewport.source, viewport.canvas, movedX, movedY,
        )
    }

    // MARK: Rotation

    /**
     * An angle pulled onto the nearest of [detents] within [threshold]
     * degrees, then as −180…180. The original snaps to upright only.
     */
    fun snappedRotation(degrees: Double, detents: List<Double> = listOf(0.0), threshold: Double = ROTATION_SNAP): Double =
        detents.firstOrNull { abs(CropSettings.normalizedRotation(degrees - it)) < threshold }?.let(CropSettings::normalizedRotation)
            ?: CropSettings.normalizedRotation(degrees)

    /** The crop turned to [degrees], from the slider. The zoom is kept: the window shrinks as needed to keep its corners filled. */
    fun rotated(crop: CropSettings, degrees: Double): CropSettings = limited(crop.copy(rotation = snappedRotation(degrees)))

    /** A quarter turn clockwise from where it is. */
    fun quarterTurn(crop: CropSettings): CropSettings = rotated(crop, crop.rotation + 90)

    // MARK: Controls

    fun zoomed(crop: CropSettings, zoom: Double): CropSettings = limited(crop.copy(zoom = zoom))

    /** One accessibility step closer or further. */
    fun zoomStepped(crop: CropSettings, closer: Boolean): CropSettings =
        zoomed(crop, if (closer) crop.zoom * ZOOM_STEP else crop.zoom / ZOOM_STEP)

    /** The position sliders: only the axis that was moved is stored as shown. */
    fun moved(crop: CropSettings, x: Double? = null, y: Double? = null, source: Size, canvas: Size): CropSettings {
        val value = crop.copy(x = x ?: crop.x, y = y ?: crop.y)
        val visible = value.clamped(source, canvas)
        return limited(value.copy(x = if (x != null) visible.x else value.x, y = if (y != null) visible.y else value.y))
    }

    /** The centers a position slider can reach, as fractions of the source. */
    fun positionRange(crop: CropSettings, horizontal: Boolean, source: Size, canvas: Size): ClosedFloatingPointRange<Double> {
        val area = crop.footprint(source, canvas)
        val half = if (horizontal) area.width / maxOf(1.0, source.width) / 2 else area.height / maxOf(1.0, source.height) / 2
        return if (half < 0.5) half..(1 - half) else 0.0..1.0
    }

    /** Whether a position slider has nowhere to go: the window already spans the source that way. */
    fun positionLocked(crop: CropSettings, horizontal: Boolean, source: Size, canvas: Size): Boolean {
        val area = crop.footprint(source, canvas)
        return if (horizontal) area.width >= source.width - 0.001 else area.height >= source.height - 0.001
    }

    /** "Reset framing": centered, widest, upright. It keeps the crop's identity. */
    fun reset(crop: CropSettings): CropSettings = CropSettings(id = crop.id)
}

/** The movie's shape while the crop tool is open. */
object CropShape {
    /**
     * The canvas the tool frames into. [aspect] is the chosen shape's, null
     * for the footage's own; [original] is the canvas the project would have
     * with no fixed size and [pinned] the fixed size it has now, if any.
     */
    fun canvas(aspect: Double?, original: Size, pinned: Size?): Size =
        OutputShape.canvas(if (aspect == null) original else pinned ?: original, aspect)

    /**
     * The fixed output size to store when the tool is applied. Choosing
     * "Original" with no crop left frees the size again; any crop or chosen
     * shape pins [canvas]; otherwise it stays as it was.
     */
    fun outputSize(chosen: OutputShape?, anyCrop: Boolean, changed: Boolean, current: Size?, canvas: Size): Size? = when {
        chosen == OutputShape.Original && !anyCrop -> null
        changed || chosen != null -> canvas
        else -> current
    }
}

/**
 * The crop tool's working state. The controls always edit one fixed framing,
 * [crop]; while a motion exists that framing is one of its two ends.
 */
data class CropDraft(
    /** What the controls show. It never carries a motion. */
    val crop: CropSettings,
    val animation: EffectAnimation? = null,
    /** The end being edited, shown in the menu. */
    val endpoint: EffectEndpoint? = null,
    /** The end the preview is parked on. Scrubbing clears it. */
    val previewEndpoint: EffectEndpoint? = null,
    val lastEndpoint: EffectEndpoint = EffectEndpoint.A,
    val framingChanged: Boolean = false,
    val animationChanged: Boolean = false,
) {
    /** The motion with the framing on screen written into the end it belongs to. */
    val captured: EffectAnimation?
        get() = animation?.with(endpoint ?: lastEndpoint, crop.pose)

    /** Starts editing an end; the first time, this creates a motion whose two ends are the current framing. */
    fun selecting(end: EffectEndpoint, duration: Double): CropDraft {
        val motion = (captured ?: EffectAnimation(crop.pose, duration)).copy(duration = duration)
        return copy(
            crop = crop.applying(motion[end]), animation = motion, endpoint = end, previewEndpoint = end, lastEndpoint = end,
            framingChanged = true, animationChanged = true,
        )
    }

    /** "Done": keeps the motion and leaves the ends. */
    fun finishing(): CropDraft = copy(animation = captured, endpoint = null, previewEndpoint = null)

    /** "Remove animation": the framing on screen stays as a fixed crop. */
    fun removingAnimation(): CropDraft =
        copy(animation = null, endpoint = null, previewEndpoint = null, framingChanged = true, animationChanged = true)

    /** A change of curve, rise, release or ramp. [duration] is the length of the selection the tool was opened on. */
    fun retimed(timing: EffectTiming, duration: Double): CropDraft {
        val motion = captured ?: return this
        if (motion.timing == timing) return this
        return copy(animation = motion.copy(timing = timing, duration = duration), animationChanged = true)
    }

    /** A new framing from a gesture or a control. With a motion it edits the end last chosen and parks the preview there. */
    fun adjusted(value: CropSettings): CropDraft {
        val next = copy(crop = CropGestures.limited(value).copy(id = crop.id, motion = null), framingChanged = true)
        if (animation == null) return next
        return if (previewEndpoint == null) next.copy(animationChanged = true, endpoint = lastEndpoint, previewEndpoint = lastEndpoint)
        else next.copy(animationChanged = true)
    }

    /** The user moved the preview position by hand. */
    fun scrubbed(): CropDraft = copy(previewEndpoint = null)

    /**
     * Where the preview should stand, in seconds from the start of the
     * selection: on the parked end, which follows the peak as the timing
     * changes, or else where it is.
     */
    fun previewPosition(position: Double, duration: Double): Double =
        animation?.let { it.timing.previewPosition(position, previewEndpoint, duration, it.frame) } ?: position

    /**
     * The framing to draw behind the window. Parked on an end it is [crop];
     * after scrubbing it is the motion sampled at [sourceTime]. [spans] and
     * [offset] tie a changed motion to the clip as [applied] will; [stored]
     * is the motion the clip has now.
     */
    fun displayed(sourceTime: Double, spans: List<EffectPlaybackSpan>, offset: Double, stored: EffectAnimation?): CropSettings {
        val motion = captured ?: return crop
        if (previewEndpoint != null) return crop
        val placed = if (animationChanged) motion.placed(spans, offset, stored) else motion
        return crop.applying(placed.pose(sourceTime))
    }

    /**
     * The crop to store on a clip when the tool is applied, null for none.
     * [previous] is what the clip has now; [spans] what it plays and [offset]
     * where it starts, in seconds from the start of the selection. A fixed
     * crop that shows the whole picture is no crop. [source] is the clip's
     * oriented size, null when unknown.
     */
    fun applied(
        previous: CropSettings?,
        spans: List<EffectPlaybackSpan>,
        offset: Double,
        source: Size?,
        canvas: Size,
        removing: Boolean = false,
    ): CropSettings? {
        if (removing) return null
        var proposed = if (framingChanged) crop else previous ?: CropSettings(id = crop.id)
        val motion = captured
        if (motion != null && animationChanged) {
            proposed = crop.applying(motion.a).copy(motion = motion.placed(spans, offset, previous?.motion))
        }
        if (source != null && proposed.motion == null && proposed.isFullFrame(source, canvas)) return null
        return proposed
    }

    companion object {
        /** The tool opened on a clip: the stored crop, its motion set aside for editing. [id] names a crop that does not exist yet. */
        fun opening(stored: CropSettings?, id: String = stored?.id ?: newID()): CropDraft =
            CropDraft(crop = (stored ?: CropSettings()).copy(id = id, motion = null), animation = stored?.motion)
    }
}
