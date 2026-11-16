package app.takes.pixels

import app.takes.core.EditState
import app.takes.core.EditTime
import app.takes.core.LayerPlan
import app.takes.core.PlacementLayout
import app.takes.core.Size
import app.takes.core.TextOverlay
import app.takes.core.TimelineClip
import app.takes.core.TimelineEditing
import app.takes.core.TrackTimeline
import app.takes.core.TransitionFrame
import app.takes.core.TransitionPicture
import app.takes.core.TransitionSettings
import app.takes.core.TransitionStyle
import app.takes.core.VideoPlacement
import app.takes.core.VideoPlacementRegion
import app.takes.core.VideoTimelineItem
import app.takes.core.compile
import app.takes.core.toTimeline
import kotlin.math.abs

/**
 * Titles through a transition, in both exports: of a sequence ([Stage], the transition effect) and of picture layers
 * ([LayerStage], the pair under a picture in the corner). The original draws each clip with its titles before the two
 * are combined, for every style but a split screen (`YouRec/VideoToolCompositor.swift:282`, `:409-411`): a title is
 * wiped, keyed, slid and blurred with its picture. For a split screen the titles of both go once over the pair
 * (`:298-301`).
 *
 * Each case draws an edit twice, with its titles and without them, on flat pictures: where the two frames differ is
 * where a title shows, and by how much is how strongly.
 */
class TitlePixelCases(private val bench: Bench) {
    private val colorA = rgb(40, 90, 160)
    private val colorB = rgb(160, 90, 40)
    private val colorO = rgb(30, 170, 60)

    /** An edit drawn with titles and without, its transition, and what the core says it draws at a progress. */
    private class Drawn(
        val titled: (Double) -> Picture, val plain: (Double) -> Picture, val release: () -> Unit,
        val settings: TransitionSettings, val origin: Double, val motion: Double, val videoDuration: Double,
    ) {
        fun time(progress: Double) = origin + motion * progress
        fun frame(progress: Double): TransitionFrame = TransitionPicture.frame(settings, progress, Size(WIDTH.toDouble(), HEIGHT.toDouble()), 0, videoDuration, 30.0)

        /** A time A shows alone, and one B does. */
        val first get() = origin - 0.5
        val second get() = origin + motion + 0.5
    }

    private fun clip(id: String, seconds: Double = SECONDS) = TimelineClip(id = id, sourceID = "S", start = 0.0, end = seconds)

    private fun joined(settings: TransitionSettings, titles: List<List<TextOverlay>>?): List<TimelineClip> =
        TimelineEditing.setJoin(EditState(listOf(clip("A"), clip("B"))), 1, settings)!!.clips.mapIndexed { index, clip -> clip.copy(texts = titles?.getOrNull(index)?.takeIf { it.isNotEmpty() }) }

    /** Two clips of [SECONDS] joined by [settings], each with its [titles], exported as a sequence. */
    private fun sequence(settings: TransitionSettings, titles: List<List<TextOverlay>>): Drawn {
        val a = Picture.flat(WIDTH, HEIGHT, colorA)
        val b = Picture.flat(WIDTH, HEIGHT, colorB)
        val titled = Stage(bench, joined(settings, titles), WIDTH, HEIGHT, titled = true) { index, _ -> if (index == 0) a else b }
        val plain = Stage(bench, joined(settings, null), WIDTH, HEIGHT) { index, _ -> if (index == 0) a else b }
        val join = TimelineEditing.join(titled.clips, 1)!!
        return Drawn(titled::frame, plain::frame, { titled.release(); plain.release() }, settings, join.origin, join.timing.motion, join.timing.videoDuration)
    }

    /** The same, exported as picture layers: a picture O over the pair in the corner all along. */
    private fun layered(settings: TransitionSettings, titles: List<List<TextOverlay>>): Drawn {
        fun plan(with: List<List<TextOverlay>>?): LayerPlan {
            val timeline = EditState(joined(settings, with)).toTimeline()
            val over = timeline.copy(video = timeline.video + VideoTimelineItem.of("occurrence-O", EditTime.of(0.0), clip("O", 2 * SECONDS), 10))
            val corner = VideoPlacement.layout(PlacementLayout.PictureInPicture, 1, 2).copy(order = 10)
            val together = over.compile().map { it.video.toSet() }.filter { "O" in it && it.size > 1 }.distinct()
            val placed: TrackTimeline = over.copy(placements = together.mapIndexed { number, ids -> VideoPlacementRegion(id = "R$number", participants = ids, videos = ids.associateWith { if (it == "O") corner else VideoPlacement() }) })
            return LayerPlan.planned(placed, 3)!!
        }
        val pictures = mapOf("A" to Picture.flat(WIDTH, HEIGHT, colorA), "B" to Picture.flat(WIDTH, HEIGHT, colorB), "O" to Picture.flat(WIDTH, HEIGHT, colorO))
        val withTitles = plan(titles)
        val without = plan(null)
        val titled = LayerStage(bench, withTitles, WIDTH, HEIGHT, titled = true) { index -> pictures[withTitles.pictures[index].id] }
        val plain = LayerStage(bench, without, WIDTH, HEIGHT) { index -> pictures[without.pictures[index].id] }
        val transition = withTitles.transitions.first()
        return Drawn(titled::frame, plain::frame, { titled.release(); plain.release() }, settings, transition.origin.seconds, transition.timing.motion, transition.timing.videoDuration)
    }

    /** How far apart two colors are: the largest difference of a channel, in steps. */
    private fun apart(one: Int, other: Int): Int = (0..2).maxOf { abs(((one shr (8 * it)) and 0xff) - ((other shr (8 * it)) and 0xff)) }

    /** Where a title shows in a frame: by how much the frame with titles differs from the one without, pixel by pixel. */
    private fun Drawn.title(time: Double): IntArray {
        val with = titled(time)
        val without = plain(time)
        return IntArray(WIDTH * HEIGHT) { apart(with.pixels[it], without.pixels[it]) }
    }

    private fun title(text: String, y: Double = 0.5, size: Double = 0.3) = TextOverlay(id = text, text = text, x = 0.5, y = y, size = size, start = 0.0, end = SECONDS)

    /**
     * A wipe: the title of B is uncovered with B's picture, behind the edge, whole; ahead of the edge, where A still
     * shows, there is none of it. Drawn once over the frame it would fade everywhere by the progress.
     */
    private fun Checks.wiped(drawn: Drawn) {
        val alone = drawn.title(drawn.second)
        val mask = BooleanArray(alone.size) { alone[it] > 40 }
        val whole = alone.max()
        val plain = drawn.plain(drawn.time(0.5))
        val difference = drawn.title(drawn.time(0.5))
        val ahead = mask.indices.filter { mask[it] && apart(plain.pixels[it], colorA) <= 2 }
        val behind = mask.indices.filter { mask[it] && apart(plain.pixels[it], colorB) <= 2 }
        note("title pixels ahead of the edge ${ahead.size}, behind it ${behind.size}; the title alone differs by up to $whole")
        check(ahead.size > 50 && behind.size > 50) { "The edge does not cross the title: ${ahead.size} pixels ahead, ${behind.size} behind" }
        val leaked = ahead.maxOfOrNull { difference[it] } ?: 0
        check(leaked <= 3) { "The title of B shows ahead of the edge, over A, by up to $leaked steps" }
        val shown = behind.maxOfOrNull { difference[it] } ?: 0
        check(shown >= whole - 6) { "The title of B behind the edge differs by up to $shown steps, not whole ($whole)" }
    }

    /**
     * A whip pan or a defocus: the title of A is blurred with A's picture, and in a whip pan moved with it. Its
     * strongest difference falls below that of the title sharp, and more pixels show it. Drawn sharp over the frame it
     * would keep its whole difference where A alone shows (the defocus, before its change-over), and about three
     * quarters of it where A has half the frame (the whip pan, mixed in linear light). In a whip pan its middle moves
     * by the slide.
     */
    private fun Checks.blurred(drawn: Drawn) {
        val whip = drawn.settings.style == TransitionStyle.Whip
        // The moment of the strongest blur while A shows at least as much as B.
        val progress = (5..60).map { it / 100.0 }.filter { p ->
            when (val frame = drawn.frame(p)) {
                is TransitionFrame.Whip -> frame.mix <= 0.5
                is TransitionFrame.Defocus -> frame.mix <= 0.0
                else -> false
            }
        }.maxByOrNull { p ->
            when (val frame = drawn.frame(p)) {
                is TransitionFrame.Whip -> frame.blurRadius
                is TransitionFrame.Defocus -> frame.blurRadius
                else -> 0.0
            }
        } ?: return check(false) { "No moment of the blur shows A" }
        val frame = drawn.frame(progress)
        val sharp = drawn.title(drawn.first)
        val soft = drawn.title(drawn.time(progress))
        fun middle(difference: IntArray): Double = difference.indices.sumOf { (it % WIDTH) * difference[it].toDouble() } / difference.sum()
        note("at p=$progress (${frame}): sharp title differs by up to ${sharp.max()}, through the transition by up to ${soft.max()}; middle %.1f, then %.1f".format(middle(sharp), middle(soft)))
        val bound = if (whip) 0.6 else 0.85
        check(soft.max() < bound * sharp.max()) { "The title stays sharp: it differs by up to ${soft.max()} steps, sharp by ${sharp.max()}" }
        check(soft.count { it > 8 } > 1.2 * sharp.count { it > 8 }) { "The title is not spread: ${soft.count { it > 8 }} pixels show it, sharp ${sharp.count { it > 8 }}" }
        if (whip && frame is TransitionFrame.Whip && !frame.vertical) {
            val moved = middle(soft) - middle(sharp)
            check(abs(moved - frame.firstOffset) <= maxOf(3.0, 0.15 * abs(frame.firstOffset))) { "The title moved by %.1f pixels, its picture by %.1f".format(moved, frame.firstOffset) }
        }
    }

    /** A split screen: the titles of both clips once over the pair, whole and where they are when each clip shows alone. */
    private fun Checks.split(drawn: Drawn) {
        fun white(picture: Picture) = picture.pixels.indices.filter { index -> (0..2).all { ((picture.pixels[index] shr (8 * it)) and 0xff) >= 235 } }.toSet()
        val expected = white(drawn.titled(drawn.first)) + white(drawn.titled(drawn.second))
        val actual = white(drawn.titled(drawn.time(0.5)))
        val off = (expected - actual).size + (actual - expected).size
        note("white title pixels: ${expected.size} alone, ${actual.size} over the split screen, $off apart")
        check(expected.size > 100) { "The titles are not drawn" }
        check(off <= expected.size / 50) { "$off of ${expected.size} white pixels of the titles are not where they are alone" }
    }

    /** A dissolve: the title of A shows as much as A does, half way through about half. */
    private fun Checks.dissolved(drawn: Drawn) {
        val sharp = drawn.title(drawn.first).max()
        val half = drawn.title(drawn.time(0.5)).max()
        note("the title differs by up to $sharp alone, $half half way")
        check(half in (sharp * 0.25).toInt()..(sharp * 0.8).toInt()) { "Half way through the title differs by $half steps, alone by $sharp" }
    }

    private fun case(name: String, settings: TransitionSettings, titles: List<List<TextOverlay>>, body: Checks.(Drawn) -> Unit): List<Case> =
        listOf("sequence" to ::sequence, "layers" to ::layered).map { (form, make) ->
            Case("Titles ($form): $name") {
                val drawn = make(settings, titles)
                try {
                    body(drawn)
                } finally {
                    drawn.release()
                }
            }
        }

    fun all(): List<Case> =
        case("a wipe uncovers the title of the clip coming with its picture", TransitionSettings(id = "T", style = TransitionStyle.Wipe, duration = 1.0), listOf(emptyList(), listOf(title("WIPED")))) { wiped(it) } +
            case("a whip pan slides and smears a title with its picture", TransitionSettings(id = "T", style = TransitionStyle.Whip, duration = 1.0), listOf(listOf(title("WHIP", size = 0.12)), emptyList())) { blurred(it) } +
            case("a defocus blurs a title with its picture", TransitionSettings(id = "T", style = TransitionStyle.Defocus, duration = 1.0, blurAmount = 1.0), listOf(listOf(title("BLUR", size = 0.12)), emptyList())) { blurred(it) } +
            case("a split screen draws both titles once over the pair", TransitionSettings(id = "T", style = TransitionStyle.Split, duration = 1.0), listOf(listOf(title("ONE", 0.25, 0.15)), listOf(title("TWO", 0.75, 0.15)))) { split(it) } +
            case("a dissolve mixes a title with its picture", TransitionSettings(id = "T", style = TransitionStyle.Dissolve, duration = 1.0), listOf(listOf(title("MIX")), emptyList())) { dissolved(it) }

    companion object {
        const val WIDTH = 480
        const val HEIGHT = 270
        const val SECONDS = 4.5
    }
}
