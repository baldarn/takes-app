package app.takes.core

import kotlinx.serialization.Serializable

/**
 * A picture over the footage, as the original's (YouRec/VideoTools.swift:84-98). Times are source seconds, so it
 * follows its clip through trims and moves. [filename] names a file in the project's folder.
 */
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
     * top-down renderer must flip it (`canvas.height - rect.maxY`), as [frame] does.
     */
    fun rect(image: Size, canvas: Size): Rect {
        val w = minOf(canvas.width, canvas.height) * width
        val h = w * image.height / maxOf(1.0, image.width)
        return Rect(canvas.width * x - w / 2, canvas.height * (1 - y) - h / 2, w, h)
    }

    /** [rect] in top-down coordinates, a picture's first row at the top, as Android draws. */
    fun frame(image: Size, canvas: Size): Rect = rect(image, canvas).let { it.copy(y = canvas.height - it.maxY) }

    val pose: EffectPose get() = EffectPose(mapOf("x" to x, "y" to y, "width" to width, "opacity" to opacity))

    /** This overlay fixed at [pose], no longer moving (YouRec/EffectAnimation.swift:193-204). */
    fun applying(pose: EffectPose): ImageOverlay =
        copy(x = pose["x"], y = pose["y"], width = pose["width"], opacity = pose["opacity"], motion = null)

    /** The overlay as it is drawn when its motion's clock is at source second [source]; itself when it does not move. */
    fun at(source: Double): ImageOverlay = motion?.let { applying(it.pose(source)) } ?: this

    /** The ranges a saved project may hold (YouRec/Project.swift:503-506). The owner also checks [end] against its source's length. */
    val valid: Boolean
        get() = isProjectFileName(filename) && start.isFinite() && end.isFinite() && start >= 0 && end > start && accepts(pose) &&
            (motion?.valid(pose) { accepts(it) } ?: true)

    companion object {
        fun accepts(pose: EffectPose): Boolean =
            pose["x"] in 0.0..1.0 && pose["y"] in 0.0..1.0 && pose["width"] in MINIMUM_WIDTH..MAXIMUM_WIDTH && pose["opacity"] in 0.0..1.0

        /** The size slider's and the pinch's limits, as shares of the shorter edge (YouRec/EditorTools.swift:1233, :1246). */
        const val MINIMUM_WIDTH = 0.03
        const val MAXIMUM_WIDTH = 3.0

        /** The longest side of an imported picture, in pixels: a larger one is brought down to it (YouRec/EditorTools.swift:530-531). */
        const val MAXIMUM_PIXELS = 4096

        /** A position this near the middle snaps to it while dragged ([OverlayPlacement.SNAP]). */
        const val SNAP = OverlayPlacement.SNAP

        /** [value], a position, snapped to the middle when it is near it ([OverlayPlacement.snapped]). */
        fun snapped(value: Double): Double = OverlayPlacement.snapped(value)

        /** The name of an imported picture in the project's folder (YouRec/EditorTools.swift:534). */
        fun importedName(id: String = newID()): String = "image-$id.png"
    }
}

/** The pictures laid over clips: which show, where they are placed, and how they move. */
object ImageOverlays {
    /**
     * The pictures the clip shows [offset] timeline seconds into it, in the order they are drawn, each posed by its
     * motion. A picture shows from its start up to its end in the footage the clip shows, a held frame's included;
     * its motion runs on the footage's clock, and over a held frame on the hold's
     * (YouRec/VideoToolCompositor.swift:169-173, :402-410).
     */
    fun shownAt(clip: TimelineClip, offset: Double): List<ImageOverlay> {
        val source = clip.sourceTime(offset)
        // A picture with windows shows only inside them (YouRec/ScopedEffect.swift:95-108).
        val images = (if (clip.effectWindows == null) clip.images else clip.resolvingEffects(clip.gradeTime(offset)).images) ?: return emptyList()
        val clock = clip.frozenFrame?.let { PictureFraming.motionTime(it, offset) } ?: source
        return images.filter { source >= it.start - EDGE && source < it.end - EDGE }.map { it.at(clock) }
    }

    /** Within this of its start or end a source second counts as past it (YouRec/VideoToolCompositor.swift:402). */
    private const val EDGE = 0.00001

    /**
     * The seconds of the movie in which the clip at [index] shows [image]: the part of its footage the clip plays.
     * Null when it shows none of it.
     */
    fun range(clips: List<TimelineClip>, index: Int, image: ImageOverlay): ClosedFloatingPointRange<Double>? {
        val clip = clips.getOrNull(index) ?: return null
        val from = maxOf(clip.start, image.start)
        val to = minOf(clip.end, image.end)
        if (to <= from) return null
        val begins = TimelineEditing.clipStart(clips, index)
        return begins + clip.offset(from)..begins + clip.offset(to)
    }

    /**
     * Writes [image] over the seconds [range] of the movie, as the original's Image tool applies it
     * (YouRec/EditorTools.swift:650-674): each clip in [scope] (every clip when null) that plays during them gets
     * the picture with the same id over the footage it shows then, the one it had taken off and the new one put on
     * top; one on a clip the range no longer reaches is removed. While [range] is still [opened], the range the
     * picture had when it was opened, each clip keeps the footage seconds it has saved.
     *
     * With [motion], each clip's copy holds the A pose and the motion tied to the clip's playback, starting where the
     * clip starts counted from the start of [range]; a clip whose motion is unchanged and whose range is unchanged
     * keeps the clock it had (YouRec/EditorTools.swift:658-660). The image's own [ImageOverlay.start],
     * [ImageOverlay.end] and [ImageOverlay.motion] are not read.
     *
     * Null when nothing would change or the picture cannot be saved.
     */
    fun place(
        state: EditState, image: ImageOverlay, range: ClosedFloatingPointRange<Double>, motion: EffectAnimation? = null,
        scope: Set<String>? = null, opened: ClosedFloatingPointRange<Double>? = null,
    ): EditState? {
        val still = image.copy(start = 0.0, end = 1.0, motion = null)
        if (!still.valid || !range.start.isFinite() || !range.endInclusive.isFinite()) return null
        val starts = if (state.isSequence) TimelineEditing.layout(state.clips).placements.map { it.start } else TrackEditState.starts(state)
        val unchanged = opened != null && range == opened
        val clips = state.clips.mapIndexed { index, clip ->
            if (scope != null && clip.id !in scope) return@mapIndexed clip
            val images = clip.images.orEmpty()
            val previous = images.firstOrNull { it.id == image.id }
            val begins = starts[index]
            val drawn = if (motion == null) image.copy(motion = null) else {
                val kept = previous?.motion?.takeIf { unchanged && motion.hasSameSettings(it) }
                val offset = begins - range.start
                image.applying(motion.a).copy(motion = kept ?: clip.frozenFrame?.let { motion.anchored(it, offset) } ?: motion.anchored(clip.playbackSpans, offset))
            }
            val placed = if (unchanged) {
                previous?.let { drawn.copy(start = it.start, end = it.end) }
            } else {
                val from = maxOf(range.start, begins)
                val to = minOf(range.endInclusive, begins + clip.duration)
                if (to > from + 0.000001) drawn.copy(start = maxOf(0.0, clip.sourceTime(from - begins)), end = clip.sourceTime(to - begins)) else null
            }
            if (placed == previous) return@mapIndexed clip
            val next = images.filter { it.id != image.id } + listOfNotNull(placed)
            clip.copy(images = next.ifEmpty { null })
        }
        return if (clips == state.clips) null else TrackWrapping.edit(state, sound = false) { TrackSequenceView.merging(it, clips) }
    }

    /** Takes the picture off the clip at [index] only (YouRec/VideoTools.swift:539); another piece carrying it keeps it. */
    fun remove(state: EditState, index: Int, id: String): EditState? {
        val clip = state.clips.getOrNull(index) ?: return null
        val images = clip.images.orEmpty()
        if (images.none { it.id == id }) return null
        val clips = state.clips.toMutableList().also { it[index] = clip.copy(images = images.filter { image -> image.id != id }.ifEmpty { null }) }
        return TrackWrapping.edit(state, sound = false) { TrackSequenceView.merging(it, clips) }
    }
}

/**
 * The Image tool's picture while it is worked on, as the original's tool model keeps it
 * (YouRec/EditorTools.swift:259-329): the controls' pose, and the motion with the end being edited.
 *
 * @param image what the controls show. It never carries a motion.
 * @param endpoint the end being edited, shown in the menu.
 * @param previewEndpoint the end the preview is parked on. Scrubbing or playing clears it.
 */
data class ImageDraft(
    val image: ImageOverlay,
    val animation: EffectAnimation? = null,
    val endpoint: EffectEndpoint? = null,
    val previewEndpoint: EffectEndpoint? = null,
    val lastEndpoint: EffectEndpoint = EffectEndpoint.A,
) {
    /** The motion with the pose on screen written into the end it belongs to, over [duration] seconds (`capturedAnimation`). */
    fun captured(duration: Double): EffectAnimation? =
        animation?.with(endpoint ?: lastEndpoint, image.pose)?.copy(duration = duration)

    /** Starts editing an end; the first time, this creates a motion whose two ends are the current pose (`selectEndpoint`). */
    fun selecting(end: EffectEndpoint, duration: Double): ImageDraft {
        val motion = captured(duration) ?: EffectAnimation(image.pose, duration)
        return copy(image = image.applying(motion[end]), animation = motion, endpoint = end, previewEndpoint = end, lastEndpoint = end)
    }

    /** "Done": keeps the motion and leaves the ends (`finishEndpoint`). */
    fun finishing(duration: Double): ImageDraft = copy(animation = captured(duration), endpoint = null, previewEndpoint = null)

    /** "Remove animation": the pose on screen stays as a fixed one (`removeAnimation`). */
    fun removingAnimation(): ImageDraft = copy(animation = null, endpoint = null, previewEndpoint = null)

    /** A change of curve, rise, release or ramp (`updateTiming`). */
    fun retimed(timing: EffectTiming, duration: Double): ImageDraft {
        val motion = captured(duration) ?: return this
        if (motion.timing == timing) return this
        return copy(animation = motion.copy(timing = timing))
    }

    /**
     * A new pose from a gesture or a control. With a motion and the preview no longer on an end, it edits the end last
     * chosen and parks the preview there again (`adjustedEndpoint`).
     */
    fun adjusted(value: ImageOverlay): ImageDraft {
        val next = copy(image = value.copy(id = image.id, motion = null))
        return if (animation != null && previewEndpoint == null) next.copy(endpoint = lastEndpoint, previewEndpoint = lastEndpoint) else next
    }

    /** The preview was moved by hand or played: it shows the motion as it runs. */
    fun scrubbed(): ImageDraft = if (previewEndpoint == null) this else copy(previewEndpoint = null)

    /** Where the preview stands while parked on an end, in seconds from the start of the picture's range; null when it is not. */
    fun parked(duration: Double): Double? {
        val end = previewEndpoint ?: return null
        val motion = animation ?: return null
        return motion.timing.endpoint(end, duration, motion.frame)
    }

    /**
     * The motion to place with the picture: none while the preview is parked on an end, which shows that end's pose
     * still, as the original's `displayedImage` does; else the motion as it is.
     */
    fun shownMotion(duration: Double): EffectAnimation? = if (previewEndpoint != null) null else captured(duration)

    companion object {
        /** The tool opened on a saved picture: its pose, its motion set aside for editing (YouRec/EditorTools.swift:457-462). */
        fun opening(stored: ImageOverlay): ImageDraft =
            ImageDraft(image = stored.motion?.let { stored.applying(it.a) } ?: stored.copy(motion = null), animation = stored.motion)
    }
}
