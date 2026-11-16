package app.takes.core

/**
 * A place for new video chosen on the timeline itself, with one of its add
 * buttons, rather than at the playhead.
 */
sealed interface VideoInsertionSite {
    /** Before the first picture. */
    data object Start : VideoInsertionSite

    /** In the stretch without picture that follows the picture ending at [after]. */
    data class Gap(val after: Double) : VideoInsertionSite

    /** After the last picture. */
    data object End : VideoInsertionSite

    val label: String
        get() = when (this) {
            Start -> "Add video or photo at start"
            is Gap -> "Add video or photo in gap"
            End -> "Add video or photo at end"
        }
}

/**
 * Where new video goes.
 *
 * Without a site it goes in at the playhead, kept inside the movie, and
 * everything from there on moves later by its length. With a site it goes
 * beside existing picture: free time without picture is used first, and later
 * footage moves only by what does not fit.
 */
object InsertionSites {
    /** A stretch of the timeline that has picture. */
    data class Span(val start: Double, val end: Double)

    /** New video begins at [start]; everything from [rippleAt] on moves later by [ripple] seconds. */
    data class Placement(val start: Double, val rippleAt: Double, val ripple: Double)

    /** [spans] in order, with those that touch or overlap made one. Empty ones are dropped. */
    fun union(spans: List<Span>): List<Span> {
        val result = mutableListOf<Span>()
        for (span in spans.filter { it.end > it.start }.sortedBy { it.start }) {
            val last = result.lastOrNull()
            if (last != null && span.start <= last.end) result[result.lastIndex] = last.copy(end = maxOf(last.end, span.end)) else result += span
        }
        return result
    }

    /**
     * Where the edit of [state] has picture: the stretches of its timeline
     * with a picture or a transition's time ([videoCoverage]). A sequence of
     * clips has one, from zero to its end, or none.
     */
    fun coverage(state: EditState): List<Span> = coverage(state.toTimeline())

    /** Where [timeline] has picture ([videoCoverage]). */
    fun coverage(timeline: TrackTimeline): List<Span> = timeline.videoCoverage.map { Span(it.start.seconds, it.end.seconds) }

    /**
     * The original's rule, for any [coverage] (see [union]) and [length]
     * seconds of new video.
     *  - Start: flush against the first picture, using the empty time before
     *    it; only the excess moves the first picture later.
     *  - End: right after the last picture, even when sound goes on after it.
     *  - Gap: at the left edge of the gap; the picture after the gap moves
     *    only when the new video is longer than the gap. With no picture
     *    before the place it is Start; with none after it, End.
     * With no picture at all, everything goes at zero.
     */
    fun placement(site: VideoInsertionSite, coverage: List<Span>, length: Double): Placement {
        val first = coverage.firstOrNull() ?: return Placement(0.0, 0.0, 0.0)
        val last = coverage.last()
        return when (site) {
            VideoInsertionSite.Start -> Placement(maxOf(0.0, first.start - length), first.start, maxOf(0.0, length - first.start))
            VideoInsertionSite.End -> Placement(last.end, last.end, 0.0)
            is VideoInsertionSite.Gap -> {
                val left = coverage.lastOrNull { it.start < site.after } ?: return placement(VideoInsertionSite.Start, coverage, length)
                val right = coverage.firstOrNull { it.start > left.end } ?: return Placement(left.end, left.end, 0.0)
                Placement(left.end, right.start, maxOf(0.0, length - (right.start - left.end)))
            }
        }
    }

    /** The add buttons the timeline of [state] offers ([sites] of its timeline). */
    fun sites(state: EditState): List<VideoInsertionSite> = sites(state.toTimeline())

    /**
     * The add buttons of [timeline], each once, in order: the start, one in
     * every stretch without picture between two pictures, named by where the
     * picture before it ends, and the end. With no picture, the start alone.
     * YouRec/EditorTimeline.swift:318-366, without what decides whether a
     * button fits on the screen.
     */
    fun sites(timeline: TrackTimeline): List<VideoInsertionSite> {
        val coverage = timeline.videoCoverage
        if (coverage.isEmpty()) return listOf(VideoInsertionSite.Start)
        return listOf(VideoInsertionSite.Start) + coverage.zipWithNext { left, _ -> VideoInsertionSite.Gap(left.end.seconds) } + VideoInsertionSite.End
    }

    /**
     * The timeline second to pass to [TimelineEditing.insert],
     * [UnusedTakes.add] or [Project.beginRecordingInsertion].
     *
     * Without a [site] that is the [playhead], kept inside the movie; null
     * when it is not a number. With one it is where the original's rule
     * starts the new video, as the editor of the original shows it before
     * the length is known (YouRec/EditorView.swift:599): zero for the start
     * of a timeline whose picture begins at zero, the end of the picture for
     * the end, the left edge of a gap for the gap.
     */
    fun time(state: EditState, playhead: Double, site: VideoInsertionSite? = null): Double? {
        if (site != null) return placement(site, coverage(state), 0.0).start
        if (!playhead.isFinite()) return null
        return minOf(state.duration, maxOf(0.0, playhead))
    }
}

/**
 * A photo on the timeline is a clip like any other: the still is written
 * once as a silent movie of [SECONDS] seconds at [FRAME_RATE] frames per
 * second, so cuts, trims and exports work to the frame.
 *
 * These are the numbers of that movie. Writing it, and what the project has
 * to remember about it, are not in this port yet.
 */
object PhotoClip {
    /** How long a photo lasts when added. */
    const val SECONDS = 3

    const val FRAME_RATE = 30

    /** The same picture, this many times. */
    const val FRAMES = SECONDS * FRAME_RATE

    /** The longer side of the decoded photo is brought down to this; a smaller photo is not enlarged. */
    const val MAXIMUM_PIXELS = 3840

    /** The movie's size for a decoded photo: each side rounded up to an even number, at least 2. The extra row or column is black. */
    fun encodedSize(width: Int, height: Int): Pair<Int, Int> =
        maxOf(2, (width + 1) / 2 * 2) to maxOf(2, (height + 1) / 2 * 2)

    /** When frame [index] shows, in seconds. */
    fun frameTime(index: Int): Double = index.toDouble() / FRAME_RATE

    /** The whole photo as a clip, for the source its movie became. */
    fun clip(source: SourceMedia): TimelineClip = TimelineClip(sourceID = source.id, start = 0.0, end = source.duration)
}
