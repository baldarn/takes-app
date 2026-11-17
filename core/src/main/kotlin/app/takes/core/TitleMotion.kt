package app.takes.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.floor

@Serializable
enum class TitleDirection {
    @SerialName("up") Up,
    @SerialName("down") Down,
    @SerialName("left") Left,
    @SerialName("right") Right,
}

@Serializable
enum class TitleMove {
    @SerialName("none") None,
    @SerialName("fade") Fade,
    @SerialName("drift") Drift,
    @SerialName("scale") Scale,
    @SerialName("wipe") Wipe,
}

/** How a title arrives and leaves. Between the two it is still. */
@Serializable
data class TitleMotion(
    val entrance: TitleMove = TitleMove.None,
    val exit: TitleMove = TitleMove.None,
    val inSeconds: Double = 0.35,
    val outSeconds: Double = 0.35,
    val inDirection: TitleDirection? = null,
    val outDirection: TitleDirection? = null,
) {
    val active: Boolean get() = entrance != TitleMove.None || exit != TitleMove.None
    val valid: Boolean get() = inSeconds in 0.05..5.0 && outSeconds in 0.05..5.0

    /**
     * A title at one moment. [left]…[bottom] are the visible part of the
     * drawn title as fractions of it, measured from its top left; [rise] and
     * [shift] move it by fractions of its own height.
     */
    data class Pose(
        val opacity: Double = 1.0,
        val scale: Double = 1.0,
        val rise: Double = 0.0,
        val shift: Double = 0.0,
        val left: Double = 0.0,
        val right: Double = 1.0,
        val top: Double = 0.0,
        val bottom: Double = 1.0,
    ) {
        /** Where the visible part is drawn when the still title would fill [rect]. */
        fun rect(rect: Rect): Rect {
            val width = rect.width * scale
            val height = rect.height * scale
            return Rect(
                rect.midX - width / 2 + width * left + shift * rect.height,
                rect.midY - height / 2 + rise * rect.height + height * top,
                width * (right - left), height * (bottom - top),
            )
        }

        /**
         * The visible part of a title bitmap of this many pixels, on whole
         * pixels; the whole bitmap when nothing is hidden, null when nothing is left.
         */
        fun crop(width: Int, height: Int): Rect? {
            if (!(left > 0 || right < 1 || top > 0 || bottom < 1)) return Rect(0.0, 0.0, width.toDouble(), height.toDouble())
            val a = floor(left * width)
            val b = ceil(right * width)
            val y = floor(top * height)
            val endY = ceil(bottom * height)
            if (!(b > a && endY > y)) return null
            return Rect(a, y, b - a, endY - y)
        }
    }

    private data class Ends(val entrance: Double, val exit: Double)

    private fun fittedEnds(duration: Double): Ends {
        val a = if (entrance == TitleMove.None) 0.0 else inSeconds
        val b = if (exit == TitleMove.None) 0.0 else outSeconds
        if (!(a + b > 0)) return Ends(0.0, 0.0)
        // Keep a fully readable interval even if both requested transitions exceed the cue.
        val hold = minOf(0.1, maxOf(0.0, duration * 0.2))
        val fit = minOf(1.0, maxOf(0.0, duration - hold) / (a + b))
        return Ends(a * fit, b * fit)
    }

    /** The stretch of the title worth playing to preview one of its ends. */
    fun previewWindow(exit: Boolean, duration: Double): ClosedFloatingPointRange<Double> {
        val ends = fittedEnds(duration)
        if (exit) return maxOf(0.0, duration - ends.exit - 0.35)..maxOf(0.0, duration)
        return 0.0..maxOf(0.0, minOf(duration, ends.entrance + 0.35))
    }

    /** The title [time] seconds into its [duration] on screen. */
    fun pose(time: Double, duration: Double): Pose {
        val ends = fittedEnds(duration)
        val entering = ends.entrance > 0 && time < ends.entrance
        val leaving = ends.exit > 0 && time > duration - ends.exit
        if (!entering && !leaving) return Pose()
        val fraction = if (entering) time / ends.entrance else (duration - time) / ends.exit
        val t = minOf(1.0, maxOf(0.0, fraction))
        val eased = t * t * (3 - 2 * t)
        val direction = if (entering) inDirection else outDirection
        return when (if (entering) entrance else exit) {
            TitleMove.None -> Pose()
            TitleMove.Fade -> Pose(opacity = eased)
            TitleMove.Drift -> {
                val offset = (if (entering) -0.32 else 0.32) * (1 - eased)
                when (direction ?: TitleDirection.Up) {
                    TitleDirection.Up -> Pose(opacity = eased, rise = -offset)
                    TitleDirection.Down -> Pose(opacity = eased, rise = offset)
                    TitleDirection.Left -> Pose(opacity = eased, shift = -offset)
                    TitleDirection.Right -> Pose(opacity = eased, shift = offset)
                }
            }
            TitleMove.Scale -> Pose(opacity = eased, scale = 1 + (if (entering) -0.12 else 0.08) * (1 - eased))
            TitleMove.Wipe -> when (direction ?: TitleDirection.Right) {
                TitleDirection.Right -> if (entering) Pose(right = eased) else Pose(left = 1 - eased)
                TitleDirection.Left -> if (entering) Pose(left = 1 - eased) else Pose(right = eased)
                TitleDirection.Down -> if (entering) Pose(bottom = eased) else Pose(top = 1 - eased)
                TitleDirection.Up -> if (entering) Pose(top = 1 - eased) else Pose(bottom = eased)
            }
        }
    }
}

/** The lengths offered for a title, an image or a transition, and whether one fits. */
object EditorDurationPresets {
    val wholeSeconds: List<Double> = (1..10).map { it.toDouble() } + listOf(15.0, 30.0, 60.0)
    val transitionSeconds: List<Double> = listOf(0.5) + wholeSeconds

    /** Compared on the editor's clock, so a length that fits to within a tick is offered. */
    fun fits(seconds: Double, available: Double): Boolean = ticks(seconds) <= ticks(available)

    private const val TICKS_PER_SECOND = 240_000.0

    private fun ticks(seconds: Double): Long =
        if (seconds.isFinite()) roundedAwayFromZero(minOf(604_800.0, maxOf(-604_800.0, seconds)) * TICKS_PER_SECOND).toLong() else 0
}

/** Overlay trimming uses the editor clock. A source boundary can be between frames. */
class OverlayTiming private constructor(
    val bounds: ClosedFloatingPointRange<Double>,
    val range: ClosedFloatingPointRange<Double>,
) {
    enum class Edge { Start, End }

    val duration: Double get() = range.endInclusive - range.start
    val available: Double get() = bounds.endInclusive - range.start

    fun limits(edge: Edge): ClosedFloatingPointRange<Double> = when (edge) {
        Edge.Start -> bounds.start..maxOf(bounds.start, range.endInclusive - PauseDetector.FRAME)
        Edge.End -> minOf(bounds.endInclusive, range.start + PauseDetector.FRAME)..bounds.endInclusive
    }

    /** The time for a slider position: whole frames from the lower limit, with both limits exactly reachable. */
    fun time(fraction: Double, edge: Edge): Double {
        val limits = limits(edge)
        if (fraction <= 0) return limits.start
        if (fraction >= 1) return limits.endInclusive
        val offset = (limits.endInclusive - limits.start) * fraction
        val frames = roundedAwayFromZero(offset / PauseDetector.FRAME)
        return minOf(limits.endInclusive, limits.start + frames * PauseDetector.FRAME)
    }

    /** The timing with one edge moved, kept a frame from the other where there is room. */
    fun setting(edge: Edge, value: Double): OverlayTiming {
        if (!value.isFinite()) return this
        val limits = limits(edge)
        val time = minOf(limits.endInclusive, maxOf(limits.start, value))
        return OverlayTiming(bounds, if (edge == Edge.Start) time..range.endInclusive else range.start..time)
    }

    companion object {
        /** [range] is brought inside [bounds]. */
        operator fun invoke(range: ClosedFloatingPointRange<Double>, bounds: ClosedFloatingPointRange<Double>): OverlayTiming {
            val start = minOf(bounds.endInclusive, maxOf(bounds.start, range.start))
            return OverlayTiming(bounds, start..minOf(bounds.endInclusive, maxOf(start, range.endInclusive)))
        }
    }
}

/**
 * When each moving title is on screen, in timeline seconds. A title's motion
 * runs on the movie's clock, not the footage's: on a clip played twice as fast
 * the title lasts half as long and still takes the same time to arrive.
 *
 * Like the picture, a clock can span a straight cut or a dissolve: the pieces
 * of a split clip carry the same title, and it arrives once and leaves once.
 * A real gap, or a motion changed on one piece only, starts a new run.
 */
class TitleMotionRanges(clips: List<TimelineClip>) {
    private data class Key(val id: String, val motion: TitleMotion)

    private val runs: Map<Key, List<ClosedFloatingPointRange<Double>>>

    init {
        val parts = mutableMapOf<Key, MutableList<ClosedFloatingPointRange<Double>>>()
        clips.forEachIndexed { index, clip ->
            val start = TimelineEditing.clipStart(clips, index)
            for (title in clip.texts.orEmpty()) {
                val motion = title.motion?.takeIf { it.active } ?: continue
                if (!(title.end > clip.start && title.start < clip.end)) continue
                val a = start + clip.offset(maxOf(clip.start, title.start))
                val b = start + clip.offset(minOf(clip.end, title.end))
                if (b > a) parts.getOrPut(Key(title.id, motion)) { mutableListOf() }.add(a..b)
            }
        }
        runs = parts.mapValues { (_, pieces) ->
            val joined = mutableListOf<ClosedFloatingPointRange<Double>>()
            for (piece in pieces.sortedBy { it.start }) {
                val previous = joined.lastOrNull()
                if (previous != null && piece.start <= previous.endInclusive + TICK) {
                    joined[joined.lastIndex] = previous.start..maxOf(previous.endInclusive, piece.endInclusive)
                } else {
                    joined.add(piece)
                }
            }
            joined
        }
    }

    val isEmpty: Boolean get() = runs.isEmpty()

    /** The run of [title] that timeline second [time] falls in; null for a still title. */
    fun range(title: TextOverlay, time: Double): ClosedFloatingPointRange<Double>? {
        val motion = title.motion?.takeIf { it.active } ?: return null
        return runs[Key(title.id, motion)]?.firstOrNull { time >= it.start - 0.00001 && time <= it.endInclusive + 0.00001 }
    }

    /** How [title] is posed at timeline second [time]: still unless it is arriving or leaving. */
    fun pose(title: TextOverlay, time: Double): TitleMotion.Pose {
        val range = range(title, time) ?: return TitleMotion.Pose()
        return title.motion!!.pose(time - range.start, range.endInclusive - range.start)
    }

    /**
     * One title to draw, or one picture laid over a clip ([image], with no [title]): how opaque its clip makes it,
     * and how its motion poses a title. A clip's pictures are drawn under its titles, in the layer of its picture, as
     * the original draws them (YouRec/VideoToolCompositor.swift:396-420).
     */
    data class Shown(val title: TextOverlay?, val share: Double, val pose: TitleMotion.Pose = TitleMotion.Pose(), val image: ImageOverlay? = null) {
        /** What is drawn, wherever in the footage it lies: the same title or picture on two clips is drawn once. */
        val drawing: Any get() = title?.copy(start = 0.0, end = 0.0) ?: image!!.copy(start = 0.0, end = 0.0)

        companion object {
            /** [image], posed as its motion has it, to draw as much as [share]. */
            fun picture(image: ImageOverlay, share: Double): Shown = Shown(null, share, image = image)
        }
    }

    /**
     * Every picture and title to draw at timeline second [time]: the pictures ([pictures]) under the titles, the
     * titles as [TimelineEditing.titlesAt] lists them. A title on both clips of a dissolve is drawn once, whole:
     * the two clips carry it over different footage seconds, so it is the same title when everything but those is
     * the same.
     */
    fun shown(clips: List<TimelineClip>, time: Double, frames: (TransitionLayout.Join) -> Double = { TransitionScene.FRAMES_PER_SECOND }): List<Shown> {
        val moment = TransitionScene.moment(clips, time, frames = frames)
        return pictures(clips, moment) + shown(TransitionScene.titles(clips, moment), time)
    }

    /**
     * The pictures laid over the clips of [moment] ([TransitionScene.images]), each with its clip's share of the frame,
     * the outgoing side's first; one on both clips is drawn once, as much as the two together. What a renderer draws
     * over the finished picture, without a transition shader; with one, each side's go into its picture ([sides]).
     */
    fun pictures(clips: List<TimelineClip>, moment: TransitionScene.Moment): List<Shown> {
        fun of(source: TransitionScene.Source, share: Double) = if (share <= 0) emptyList() else TransitionScene.images(clips, source).map { Shown.picture(it, share) }
        val listed = of(moment.first, moment.firstShare) + if (moment.second == moment.first) emptyList() else of(moment.second, moment.secondShare)
        return listed.distinctBy { it.drawing }.map { shown -> shown.copy(share = minOf(1.0, listed.filter { it.drawing == shown.drawing }.sumOf { it.share })) }
    }

    /** The titles [listed] at timeline second [time], each with its opacity, to draw as [shown] draws them. */
    fun shown(listed: List<Pair<TextOverlay, Double>>, time: Double): List<Shown> {
        fun drawing(title: TextOverlay) = title.copy(start = 0.0, end = 0.0)
        return listed.distinctBy { drawing(it.first) }.map { (title, _) ->
            Shown(title, minOf(1.0, listed.filter { drawing(it.first) == drawing(title) }.sumOf { it.second }), pose(title, time))
        }
    }

    /**
     * The titles of a moment drawn with its pictures: [first] into the outgoing side's picture and [second] into
     * the incoming one's, each whole, before the two are combined, so that a title is wiped, keyed, slid and blurred
     * with its clip (YouRec/VideoToolCompositor.swift:282, :409-411); [over] once over the pair, for a split screen
     * (:298-301). Outside a transition the clip showing is the first side.
     */
    data class Sides(val first: List<Shown>, val second: List<Shown>, val over: List<Shown>) {
        /**
         * The titles each of [count] decks or lanes carries in [moment]: a side's titles on the deck that shows it,
         * none on a deck that shows no clip, and none on either for a split screen. Only the sides the frame shows
         * count ([TransitionScene.Moment.sources]): in a fade through a color the clip leaving and the one coming can
         * be on the same deck, one after the other.
         */
        fun onDecks(moment: TransitionScene.Moment, count: Int = 2): List<List<Shown>> {
            val decks = MutableList(count) { emptyList<Shown>() }
            for (source in moment.sources) {
                val deck = TransitionScene.deckOf(source)?.takeIf { it in 0 until count } ?: continue
                if (decks[deck].isEmpty()) decks[deck] = if (source == moment.first) first else second
            }
            return decks
        }
    }

    /**
     * Where the titles of [moment], at timeline second [time], are drawn ([Sides]): every title whole, its clip's
     * share of the frame given by the transition itself. A title on both clips of a split screen is drawn over the
     * pair once.
     */
    fun sides(clips: List<TimelineClip>, moment: TransitionScene.Moment, time: Double): Sides {
        // A clip's pictures under its titles (YouRec/VideoToolCompositor.swift:396-420).
        fun of(source: TransitionScene.Source) =
            TransitionScene.images(clips, source).map { Shown.picture(it, 1.0) } + TransitionScene.texts(clips, source).map { Shown(it, 1.0, pose(it, time)) }
        val first = of(moment.first)
        val second = if (moment.second == moment.first) emptyList() else of(moment.second)
        if (TransitionScene.titlesInSides(moment.frame)) return Sides(first, second, emptyList())
        return Sides(emptyList(), emptyList(), (first + second).distinctBy { it.drawing })
    }

    private companion object {
        /** One tick of the editor's clock. */
        const val TICK = 1 / 240_000.0
    }
}
