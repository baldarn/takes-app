package app.takes.core

import kotlin.math.abs

/**
 * What the movie shows at one moment: which pictures a renderer needs and
 * how [TransitionPicture] says to combine them. The preview and the export
 * both draw from this, so they cannot disagree about what a moment holds.
 *
 * Clips are shown by two players ("decks"). The chains of
 * [TimelineEditing.chains] alternate between them, so two clips that play at
 * once, or a frozen frame and the clip that follows it, are never on the
 * same deck.
 */
object TransitionScene {
    /** Where one side of a moment comes from. */
    sealed interface Source {
        /** The running picture of the clip at [index], [offset] timeline seconds into it, on [deck]. */
        data class Clip(val index: Int, val deck: Int, val offset: Double) : Source

        /**
         * The clip at [index] frozen on the frame it showed [offset] timeline
         * seconds into it: the last one before a still. The freeze began at
         * timeline second [from]; a renderer that cannot go back keeps the
         * last frame it drew before that.
         */
        data class Held(val index: Int, val deck: Int, val offset: Double, val from: Double) : Source

        /** No clip: a solid color, sRGB. */
        data class Fill(val color: TransitionColor) : Source
    }

    /**
     * One moment. [frame] combines [first] (the outgoing side) and [second]
     * (the incoming one); outside a transition it is [TransitionFrame.First]
     * and both sides are the clip that is showing. [firstShare] and
     * [secondShare] say how much of each side's titles to show.
     */
    data class Moment(
        val first: Source,
        val second: Source,
        val frame: TransitionFrame,
        val firstShare: Double,
        val secondShare: Double,
        val join: TransitionLayout.Join? = null,
    ) {
        /** The sides a renderer has to draw for this frame: one when the frame is a single picture. */
        val sources: List<Source>
            get() = when (frame) {
                TransitionFrame.First -> listOf(first)
                TransitionFrame.Second -> listOf(second)
                is TransitionFrame.FadeColor -> listOfNotNull(first.takeIf { frame.first > 0 }, second.takeIf { frame.second > 0 })
                else -> listOf(first, second)
            }
    }

    /**
     * The frame rate taken for footage whose own is not known, the one a
     * flash cut counts in where no movie is in sight, and the one a still's
     * held frame is counted in.
     */
    const val FRAMES_PER_SECOND = 30.0

    /** An export of plain cuts thins footage only under a ceiling up to this; above it every frame of the footage stays. */
    const val HIGHEST_CEILING = 30

    /**
     * The frames per second of a movie that has a transition or a freeze in
     * it: what a flash cut counts its frames in, as the original counts them
     * in the frame rate of its export, so that a flash is one frame of the
     * movie.
     *
     * Such a movie has one frame rate from its first frame to its last, as
     * the original's has (TransitionRenderer.swift:167): the export gives it
     * a frame on every tick of that rate, whatever the footage, and takes
     * for each the frame of the footage nearest in time. The original is
     * told its rate; here the setting is a [ceiling] and the footage decides
     * below it. The rate is that of the fastest footage among the clips
     * ([footage]: frames per second, one entry per clip, null when not
     * known and then counted as [FRAMES_PER_SECOND]), each first brought
     * under the ceiling by leaving out whole frames ([FrameCeiling.rate]: 60
     * under 30 is 30, 50 under 30 is 25), and then the nearest of the rates
     * the original exports at ([ExportSettings.FRAME_RATES]) that is not
     * above the ceiling. So footage all of one rate at or under the ceiling
     * keeps every frame and gets none added; where rates are mixed the
     * slower footage has frames shown twice, and a clip's speed changes which
     * of its frames are shown, not the rate of the movie.
     *
     * A movie of plain cuts is not concerned: it has no clock, and its frames
     * keep the times of the footage, thinned under the ceiling.
     */
    fun frameRate(footage: List<Double?>, ceiling: Int): Int {
        val fastest = footage.maxOfOrNull { FrameCeiling.rate(it?.takeIf { rate -> rate.isFinite() && rate > 0 } ?: FRAMES_PER_SECOND, ceiling.toDouble()) } ?: FRAMES_PER_SECOND
        val allowed = ExportSettings.FRAME_RATES.filter { it <= ceiling }.ifEmpty { listOf(ExportSettings.FRAME_RATES.min()) }
        // Halfway between two rates, the higher: frames shown twice rather than left out.
        return allowed.sortedDescending().minBy { abs(it - fastest) }
    }

    /** The deck showing each clip: chains alternate. */
    fun decks(clips: List<TimelineClip>): List<Int> {
        val decks = MutableList(clips.size) { 0 }
        TimelineEditing.chains(clips).forEachIndexed { chain, run -> for (index in run.first..run.last) decks[index] = chain % 2 }
        return decks
    }

    /** How much of the titles of the outgoing and of the incoming clip show in [frame]; a split screen shows both, once, over the pair. */
    fun shares(frame: TransitionFrame, progress: Double): Pair<Double, Double> = when (frame) {
        TransitionFrame.First -> 1.0 to 0.0
        TransitionFrame.Second -> 0.0 to 1.0
        is TransitionFrame.Dissolve -> frame.first to frame.second
        is TransitionFrame.FadeColor -> frame.first to frame.second
        is TransitionFrame.Wipe, is TransitionFrame.Hitchcock -> TransitionGeometry.eased(progress).let { 1 - it to it }
        is TransitionFrame.Whip -> 1 - frame.mix to frame.mix
        is TransitionFrame.Defocus -> 1 - frame.mix to frame.mix
        is TransitionFrame.Split -> 1.0 to 1.0
    }

    /**
     * The moment at timeline second [time]. [canvas] is the size of the
     * picture in pixels; the geometry of a wipe, a split screen, a whip pan
     * and a defocus is given in it. [frames] is the frame rate of the movie
     * ([frameRate]), asked for the join it is needed at; a flash cut counts in it.
     */
    fun moment(
        clips: List<TimelineClip>, time: Double, canvas: Size = Size(1920.0, 1080.0), decks: List<Int> = decks(clips),
        frames: (TransitionLayout.Join) -> Double = { FRAMES_PER_SECOND },
    ): Moment {
        val black = Source.Fill(TransitionColor.BLACK)
        if (clips.isEmpty() || !time.isFinite()) return Moment(black, black, TransitionFrame.First, 0.0, 0.0)
        val layout = TimelineEditing.layout(clips)
        val at = time.coerceIn(0.0, layout.duration)
        val join = layout.all.firstOrNull {
            it.transition != null && TransitionLayout.longEnough(it.timing) && at >= it.range.start && (at < it.range.endInclusive || (it.edge == 1 && at >= layout.duration))
        }
        if (join == null) {
            val (index, offset) = TimelineEditing.locate(clips, at) ?: return Moment(black, black, TransitionFrame.First, 0.0, 0.0)
            val showing = Source.Clip(index, decks[index], offset)
            return Moment(showing, showing, TransitionFrame.First, 1.0, 0.0)
        }
        val settings = join.transition!!
        val progress = join.progress(at)
        val frame = TransitionPicture.frame(settings, progress, canvas, join.edge, join.timing.videoDuration, if (settings.style == TransitionStyle.FlashCut) frames(join) else FRAMES_PER_SECOND)
        val fill = Source.Fill(TransitionPicture.fill(settings))
        fun running(index: Int): Source.Clip {
            val placement = layout.placements[index]
            return Source.Clip(index, decks[index], (at - placement.start).coerceIn(0.0, placement.duration))
        }
        val first: Source = if (join.outgoing == null) fill else {
            val placement = layout.placements[join.outgoing]
            val held = TransitionPicture.heldFrame(join.timing, join.origin, placement.start, placement.duration, FRAMES_PER_SECOND)
            if (held != null && at >= held.from) Source.Held(placement.index, decks[placement.index], held.offset, held.from) else running(placement.index)
        }
        val second: Source = if (join.incoming == null) fill else running(join.incoming)
        val (firstShare, secondShare) = shares(frame, progress)
        return Moment(first, second, frame, firstShare, secondShare, join)
    }

    /** The titles of [moment] with their opacity, the outgoing side's first. A side that does not show gives none. */
    fun titles(clips: List<TimelineClip>, moment: Moment): List<Pair<TextOverlay, Double>> {
        fun of(source: Source, share: Double): List<Pair<TextOverlay, Double>> {
            if (share <= 0) return emptyList()
            val (index, offset) = when (source) {
                is Source.Clip -> source.index to source.offset
                is Source.Held -> source.index to source.offset
                is Source.Fill -> return emptyList()
            }
            val clip = clips.getOrNull(index) ?: return emptyList()
            return TimelineEditing.textsAt(clip, clip.sourceTime(offset)).map { it to share }
        }
        return of(moment.first, moment.firstShare) + if (moment.second == moment.first) emptyList() else of(moment.second, moment.secondShare)
    }
}
