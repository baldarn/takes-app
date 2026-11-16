package app.takes.core

/**
 * Where a stored person mask lies on the movie's frame. A mask is kept in the decoded frame's
 * own coordinates, before the track's rotation, as the original's Vision provider returns it
 * (YouRec/VideoEffects/VideoEffectAssets.swift:264-300), and the picture reaches the effects
 * turned upright, framed (crop, mirror) and fitted to the movie's frame. The mask is laid there by
 * the same steps taken back: as the original's `placed()` applies `maskTransform`
 * (VideoEffectAssets.swift:389-401).
 */
object PersonMaskPlacement {
    /**
     * A position 0…1 on the upright frame, from the top left, to the same point on the stored
     * frame, for a track turned clockwise by [rotation] degrees to stand upright.
     */
    fun uprightToStored(rotation: Int): Affine = when ((rotation % 360 + 360) % 360) {
        90 -> Affine(0.0, -1.0, 1.0, 0.0, 0.0, 1.0)
        180 -> Affine(-1.0, 0.0, 0.0, -1.0, 1.0, 1.0)
        270 -> Affine(0.0, 1.0, -1.0, 0.0, 1.0, 0.0)
        else -> Affine.IDENTITY
    }

    /**
     * A pixel of the movie's frame to a position 0…1 on the stored mask: [framing] takes the
     * upright recording of [source] pixels onto the frame ([PictureFraming.frame]'s transform).
     */
    fun toMask(framing: Affine, source: Size, rotation: Int): Affine =
        framing.inverted().concatenating(Affine.scale(1 / source.width, 1 / source.height)).concatenating(uprightToStored(rotation))

    /** [toMask] for [clip] at [offset] seconds into it, its recording [source] upright, on a movie [frame], as the export frames it. */
    fun toMask(clip: TimelineClip, offset: Double, source: Size, frame: Size, rotation: Int): Affine =
        toMask(PictureFraming.frame(clip, offset, source, frame).transform, source, rotation)
}
