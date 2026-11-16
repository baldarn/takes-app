package app.takes.pixels

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import app.takes.SceneDrawing
import app.takes.TransitionPreviewShader
import app.takes.core.EditState
import app.takes.core.Rect
import app.takes.core.Size
import app.takes.core.TimelineClip
import app.takes.core.TimelineEditing
import app.takes.core.TransitionAxis
import app.takes.core.TransitionColor
import app.takes.core.TransitionFrame
import app.takes.core.TransitionFraming
import app.takes.core.TransitionGeometry
import app.takes.core.TransitionLayout
import app.takes.core.TransitionPicture
import app.takes.core.TransitionSettings
import app.takes.core.TransitionStyle
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The pixel tests of the transition shader: the real shader, run by the real
 * export effect on real OpenGL ES, against what the core's numbers
 * ([TransitionPicture.frame]) and the original's recipe say a frame holds.
 *
 * Tolerances, in steps of the eight-bit encoded value unless said otherwise:
 *  - a picture or a solid fill shown alone: [ALONE_LEVELS]. The shader hands the pixel through, yet reads it with a
 *    filtered texture: on the emulator's software renderer the rows beside a hard edge between two colors can come
 *    out one step off, so exactness is reported with each case and not required;
 *  - dissolve, fade through color, wipe: [MIX_LEVELS], and a wipe's edge may sit [Recipe]'s nudge either way;
 *  - Hitchcock, split screen: [FRAMED_LEVELS] (a steep key, a scaled picture);
 *  - whip pan against an even smear and defocus against an even disc: [WHIP_BOUND] and [DEFOCUS_BOUND]
 *    in linear light, since the shader's samples (and the spread of a defocus) only approximate them;
 *  - positions measured on a frame: stated with each case.
 */
class TransitionPixelCases(private val bench: Bench) {
    enum class Form(val label: String, val join: Int) {
        Between("between two clips", 1), Opening("opening", 0), Closing("closing", 1)
    }

    /** A transition laid out by the core on clips of [SECONDS] seconds: one clip for an opening or a close, two otherwise. */
    private class Laid(val clips: List<TimelineClip>, val join: TransitionLayout.Join) {
        val settings: TransitionSettings get() = join.transition!!

        /** The second of the timeline where the picture has changed by [progress]; a still's progress is that of its dissolve. */
        fun time(progress: Double): Double =
            join.origin + (if (join.timing.style == TransitionStyle.Still) join.timing.hold else 0.0) + progress * join.timing.motion
    }

    private fun clip(id: String, seconds: Double = SECONDS) = TimelineClip(id = id, sourceID = "S", start = 0.0, end = seconds)

    private fun laid(settings: TransitionSettings, form: Form, seconds: Double = SECONDS): Laid? {
        val state = EditState(if (form == Form.Between) listOf(clip("A", seconds), clip("B", seconds)) else listOf(clip("A", seconds)))
        val clips = TimelineEditing.setJoin(state, form.join, settings)?.clips ?: return null
        return TimelineEditing.join(clips, form.join)?.let { Laid(clips, it) }
    }

    private fun tolerance(frame: TransitionFrame): Tolerance = when (frame) {
        TransitionFrame.First, TransitionFrame.Second -> Tolerance.Levels(ALONE_LEVELS)
        is TransitionFrame.Dissolve, is TransitionFrame.FadeColor, is TransitionFrame.Wipe -> Tolerance.Levels(MIX_LEVELS)
        is TransitionFrame.Hitchcock, is TransitionFrame.Split -> Tolerance.Levels(FRAMED_LEVELS)
        is TransitionFrame.Whip -> Tolerance.Linear(WHIP_BOUND)
        is TransitionFrame.Defocus -> Tolerance.Linear(DEFOCUS_BOUND)
    }

    /**
     * One frame of [settings] at [progress] in [form], drawn by the effect
     * and compared with the recipe. [outgoing] and [incoming] are the
     * pictures of the two clips; [content] is where the outgoing clip's
     * picture lies in the frame when it does not fill it.
     */
    private fun Checks.drawn(
        settings: TransitionSettings, form: Form, progress: Double, outgoing: Picture, incoming: Picture,
        width: Int = WIDTH, height: Int = HEIGHT, content: Rect? = null,
    ): Picture? {
        val laid = laid(settings, form)
        check(laid != null) { "The core does not lay this transition out" }
        if (laid == null) return null
        val whole = Rect(0.0, 0.0, width.toDouble(), height.toDouble())
        // One export for all the moments of a transition: making the effect compiles the shader, which a software renderer is slow at.
        val stage = stages.getOrPut(listOf(settings, form, outgoing, incoming, width, height, content)) {
            Stage(bench, laid.clips, width, height, content = content?.let { lies -> { index: Int -> if (index == 0 && form != Form.Opening) lies else whole } }) { index, _ ->
                if (form == Form.Opening || index == 1) incoming else outgoing
            }
        }
        run {
            // A still shows the last frame that came before it froze: the export has to have seen one.
            if (laid.settings.style == TransitionStyle.Still) laid.join.outgoing?.let { index ->
                val placement = laid.layout.placements[index]
                TransitionPicture.heldFrame(laid.join.timing, laid.join.origin, placement.start, placement.duration)?.let { stage.frame(it.from - 1.0 / 30) }
            }
            val actual = stage.frame(laid.time(progress))
            val fill = Side.Solid(TransitionPicture.fill(laid.settings))
            val first: Side = if (form == Form.Opening) fill else Side.Image(outgoing)
            val second: Side = if (form == Form.Closing) fill else Side.Image(incoming)
            val frame = TransitionPicture.frame(laid.settings, progress, Size(width.toDouble(), height.toDouble()), laid.join.edge, laid.join.timing.videoDuration, 30.0)
            val recipe = Recipe(width, height, first, second, firstContent = content?.takeIf { form != Form.Opening } ?: whole)
            val blurred = frame is TransitionFrame.Whip || frame is TransitionFrame.Defocus
            val allowed = tolerance(frame)
            val difference = compare(actual, recipe, frame, allowed, if (blurred) 3 else 1)
            note("${frame.describe()}: $difference (allowed $allowed)")
            check(difference.over == 0) { "${difference.over} of ${difference.checked} pixels are further from the recipe than $allowed; worst ${difference.worst} at ${difference.where}" }
            return actual
        }
    }

    private val stages = mutableMapOf<List<Any?>, Stage>()

    /** Lets go of the exports the cases shared. */
    fun release() {
        stages.values.forEach { it.release() }
        stages.clear()
    }

    private val Laid.layout: TransitionLayout.Sequence get() = TimelineEditing.layout(clips)

    private fun TransitionFrame.describe(): String = when (this) {
        TransitionFrame.First -> "first picture alone"
        TransitionFrame.Second -> "second picture alone"
        is TransitionFrame.Dissolve -> "dissolve, second %.4f".format(second)
        is TransitionFrame.FadeColor -> "fade, first %.2f color %.2f second %.2f".format(first, solid, second)
        is TransitionFrame.Wipe -> "wipe, edge center (%.2f, %.2f) feather %.2f".format(edge.center.x, edge.center.y, edge.feather)
        is TransitionFrame.Split -> "split, first %.2f pair %.2f second %.2f".format(mix.first, mix.pair, mix.second)
        is TransitionFrame.Hitchcock -> "Hitchcock, key %.3f·luma %+.3f".format(key.scale, key.bias)
        is TransitionFrame.Whip -> "whip, offsets %.1f and %.1f, radius %.2f, mix %.4f".format(firstOffset, secondOffset, blurRadius, mix)
        is TransitionFrame.Defocus -> "defocus, radius %.2f, mix %.3f".format(blurRadius, mix)
    }

    // MARK: Pictures

    private val quadsA = Picture.quadrants(WIDTH, HEIGHT, rgb(200, 60, 30), rgb(40, 160, 90), rgb(90, 70, 200), rgb(220, 200, 60))
    private val quadsB = Picture.quadrants(WIDTH, HEIGHT, rgb(30, 120, 220), rgb(180, 40, 150), rgb(70, 70, 70), rgb(235, 235, 235))
    private val flatA = Picture.flat(WIDTH, HEIGHT, rgb(200, 60, 30))
    private val flatB = Picture.flat(WIDTH, HEIGHT, rgb(30, 120, 220))

    /** Four bands, each brightening to the right: gray, red, green, blue. A key on brightness cuts each at another place. */
    private val ramps = Picture(WIDTH, HEIGHT) { x, y ->
        val value = x * 255 / (WIDTH - 1)
        when (y * 4 / HEIGHT) { 0 -> rgb(value, value, value); 1 -> rgb(value, 0, 0); 2 -> rgb(0, value, 0); else -> rgb(0, 0, value) }
    }

    /** Brightening to the right with no blue in it, so the share of a picture that has blue is read off the blue alone. */
    private val yellowRamp = Picture(WIDTH, HEIGHT) { x, _ -> (x * 255 / (WIDTH - 1)).let { rgb(it, it, 0) } }

    /** Pictures whose color says where a pixel came from: a framed, scaled or moved picture is checked by position. */
    private val placesA = Picture(WIDTH, HEIGHT) { x, y -> rgb(30 + x * 200 / (WIDTH - 1), 30 + y * 200 / (HEIGHT - 1), 60) }
    private val placesB = Picture(WIDTH, HEIGHT) { x, y -> rgb(60, 30 + x * 200 / (WIDTH - 1), 230 - y * 200 / (HEIGHT - 1)) }

    private val blocksA = Picture.checker(WIDTH, HEIGHT, 40, 36, rgb(230, 200, 40), rgb(30, 40, 90))
    private val blocksB = Picture.checker(WIDTH, HEIGHT, 32, 45, rgb(40, 200, 220), rgb(120, 30, 60))

    /** Dark on the left, bright on the right: one edge for a blur to spread. */
    private val edge = Picture(WIDTH, HEIGHT) { x, _ -> if (x < WIDTH / 2) rgb(30, 30, 30) else rgb(230, 230, 230) }

    // MARK: The nine styles

    private class Styled(val settings: TransitionSettings, val outgoing: Picture, val incoming: Picture)

    private val styles: List<Styled> = listOf(
        Styled(TransitionSettings(id = "T", style = TransitionStyle.Dissolve, duration = 1.0), quadsA, quadsB),
        Styled(TransitionSettings(id = "T", style = TransitionStyle.FadeColor, duration = 1.0, fadeColor = TransitionColor(0.8, 0.3, 0.1)), quadsA, quadsB),
        Styled(TransitionSettings(id = "T", style = TransitionStyle.Wipe, duration = 1.0), quadsA, quadsB),
        // A divider that puts no pixel center on an edge of the halves or of the line.
        Styled(TransitionSettings(id = "T", style = TransitionStyle.Split, duration = 1.0, divider = 0.41, first = TransitionFraming(0.3, 0.5, 1.5)), placesA, placesB),
        Styled(TransitionSettings(id = "T", style = TransitionStyle.Hitchcock, duration = 1.0), ramps, quadsB),
        Styled(TransitionSettings(id = "T", style = TransitionStyle.Whip, duration = 1.0), blocksA, blocksB),
        Styled(TransitionSettings(id = "T", style = TransitionStyle.FlashCut, duration = 0.5), quadsA, quadsB),
        Styled(TransitionSettings(id = "T", style = TransitionStyle.Still, duration = 1.0, stillDissolve = 0.5), quadsA, quadsB),
        Styled(TransitionSettings(id = "T", style = TransitionStyle.Defocus, duration = 1.0, blurAmount = 1.0), blocksA, blocksB),
    )

    private fun matrix(): List<Case> = styles.flatMap { styled ->
        Form.entries.flatMap { form ->
            MOMENTS.map { progress ->
                Case("${styled.settings.style.name} ${form.label} p=$progress") { drawn(styled.settings, form, progress, styled.outgoing, styled.incoming) }
            }
        }
    }

    /** Other settings of a style, between two clips at the three moments inside the transition. */
    private fun variant(name: String, settings: TransitionSettings, outgoing: Picture, incoming: Picture, content: Rect? = null): List<Case> =
        listOf(0.25, 0.5, 0.75).map { progress -> Case("$name p=$progress") { drawn(settings, Form.Between, progress, outgoing, incoming, content = content) } }

    private fun variants(): List<Case> =
        variant("Wipe with a hard edge", TransitionSettings(id = "T", style = TransitionStyle.Wipe, duration = 1.0, hardEdge = true), quadsA, quadsB) +
            variant("Wipe at 30 degrees", TransitionSettings(id = "T", style = TransitionStyle.Wipe, duration = 1.0, wipeAngle = 30.0), quadsA, quadsB) +
            variant("Wipe upward (top / bottom, reversed)", TransitionSettings(id = "T", style = TransitionStyle.Wipe, duration = 1.0, axis = TransitionAxis.Vertical, reverse = true), quadsA, quadsB) +
            variant("Split top / bottom", TransitionSettings(id = "T", style = TransitionStyle.Split, duration = 1.0, axis = TransitionAxis.Vertical, divider = 0.37, second = TransitionFraming(0.7, 0.2, 2.0)), placesA, placesB) +
            variant(
                "Split of a clip with bars beside it", TransitionSettings(id = "T", style = TransitionStyle.Split, duration = 1.0, divider = 0.41),
                Picture(WIDTH, HEIGHT) { x, y -> if (x < 40 || x >= 280) 0 else placesA.at(x, y) }, placesB, content = Rect(40.0, 0.0, 240.0, HEIGHT.toDouble()),
            ) +
            variant("Hitchcock, highlights first", TransitionSettings(id = "T", style = TransitionStyle.Hitchcock, duration = 1.0, highlightsFirst = true), ramps, quadsB) +
            variant("Whip downward reversed (top / bottom)", TransitionSettings(id = "T", style = TransitionStyle.Whip, duration = 1.0, axis = TransitionAxis.Vertical, reverse = true), blocksA, blocksB) +
            variant("Defocus at the default amount", TransitionSettings(id = "T", style = TransitionStyle.Defocus, duration = 1.0), blocksA, blocksB)

    // MARK: Measurements

    /** The share of the second picture along row [y], read from one [channel] in which the two flat pictures differ. */
    private fun shares(actual: Picture, y: Int, channel: Int, first: (Int) -> Int, second: Int): DoubleArray = DoubleArray(actual.width) { x ->
        val a = Light.linear(first(x) / 255.0)
        val b = Light.linear(second / 255.0)
        ((a - Light.linear(actual.channel(x, y, channel) / 255.0)) / (a - b)).coerceIn(0.0, 1.0)
    }

    /** Whether two colors are within two steps of each other in every channel: a picture drawn through the mix comes back that near. */
    private fun near(color: Int, other: Int): Boolean = (0..2).all { abs(((color shr (8 * it)) and 0xff) - ((other shr (8 * it)) and 0xff)) <= 2 }

    /** Where [values] cross [level] between two pixel centers, in pixels from the left; null when they never do. */
    private fun crossing(values: DoubleArray, level: Double): Double? {
        for (x in 0 until values.size - 1) {
            val (here, next) = values[x] to values[x + 1]
            if ((here - level) * (next - level) <= 0 && here != next) return x + 0.5 + (here - level) / (here - next)
        }
        return null
    }

    private fun measurements(): List<Case> = listOf(
        Case("Mixes are made in linear light") {
            val actual = drawn(TransitionSettings(id = "T", duration = 1.0), Form.Between, 0.5, flatA, flatB) ?: return@Case
            val red = actual.channel(WIDTH / 2, HEIGHT / 2, 0)
            val linear = Light.encoded((Light.linear(200 / 255.0) + Light.linear(30 / 255.0)) / 2) * 255
            note("an even mix of red 200 and 30 is drawn as $red: in linear light it is %.1f, on encoded values 115".format(linear))
            check(abs(red - linear) <= MIX_LEVELS + 0.5) { "Red is $red, not $linear" }
            check(abs(red - 115) > 10) { "Red is $red, which is the mix of the encoded values" }
        },
    ) + listOf(false, true).flatMap { hard ->
        listOf(0.25, 0.5, 0.75).map { progress ->
            Case("Wipe edge: position and softness, ${if (hard) "hard" else "soft"} p=$progress") {
                val settings = TransitionSettings(id = "T", style = TransitionStyle.Wipe, duration = 1.0, hardEdge = hard.takeIf { it })
                val actual = drawn(settings, Form.Between, progress, flatA, flatB) ?: return@Case
                val core = TransitionGeometry.wipe(settings, progress, Size(WIDTH.toDouble(), HEIGHT.toDouble()))
                val share = shares(actual, HEIGHT / 2, 2, { 30 }, 220)
                if (hard) {
                    // A band one pixel wide: at most one pixel is a blend, and the pictures change side at the edge.
                    val blended = share.count { it > 0.02 && it < 0.98 }
                    val change = share.indexOfFirst { it < 0.5 }
                    note("edge between pixels ${change - 1} and $change, $blended blended pixel(s); core: center %.2f, band %.2f to %.2f".format(core.center.x, core.p0.x, core.p1.x))
                    check(blended <= 1) { "$blended pixels are blends across a band one pixel wide" }
                    check(abs(change - core.center.x) <= 1.0) { "The pictures change side at $change, the core's edge is at ${core.center.x}" }
                } else {
                    val center = crossing(share, 0.5)
                    val (from, to) = crossing(share, 0.9) to crossing(share, 0.1)
                    check(center != null && from != null && to != null) { "No ramp found on the row" }
                    if (center == null || from == null || to == null) return@Case
                    val band = (to - from) / 0.8
                    note("edge center %.2f px, band %.2f px wide; core: center %.2f, band %.2f".format(center, band, core.center.x, 2 * core.feather))
                    check(abs(center - core.center.x) <= 0.5) { "The edge is at $center, the core's at ${core.center.x}" }
                    check(abs(band - 2 * core.feather) <= 1.0) { "The band is $band wide, the core's ${2 * core.feather}" }
                }
            }
        }
    } + listOf(false, true).flatMap { highlights ->
        listOf(0.25, 0.5, 0.75).map { progress ->
            Case("Hitchcock key: threshold and softness, ${if (highlights) "highlights" else "shadows"} first p=$progress") {
                val settings = TransitionSettings(id = "T", style = TransitionStyle.Hitchcock, duration = 1.0, highlightsFirst = highlights.takeIf { it })
                val actual = drawn(settings, Form.Between, progress, yellowRamp, flatB) ?: return@Case
                val key = TransitionGeometry.hitchcock(settings, progress)
                val share = shares(actual, HEIGHT / 2, 2, { 0 }, 220)
                // The brightness of the first picture, as it is encoded, at each pixel of the row.
                fun luma(position: Double) = (position - 0.5) * 255 / (WIDTH - 1) / 255 * (TransitionGeometry.LUMA_RED + TransitionGeometry.LUMA_GREEN)
                val center = crossing(share, 0.5)
                // The ramp is read between a quarter and three quarters: at its ends it runs off the brightest yellow.
                val (from, to) = crossing(share, 0.75) to crossing(share, 0.25)
                check(center != null && from != null && to != null) { "No key edge found on the row" }
                if (center == null || from == null || to == null) return@Case
                val threshold = (0.5 - key.bias) / key.scale
                val width = abs(luma(to) - luma(from)) / 0.5
                note("second picture at half share where brightness is %.4f, over a band of %.4f; core: %.4f and %.4f".format(luma(center), width, threshold, 1 / abs(key.scale)))
                check(abs(luma(center) - threshold) <= 0.008) { "The key is at ${luma(center)}, the core's threshold at $threshold" }
                check(abs(width - 1 / abs(key.scale)) <= 0.01) { "The key is $width wide, the core's ${1 / abs(key.scale)}" }
                // Shadows first: the dark end has gone over; highlights first: the bright end.
                check((share.first() > share.last()) == !highlights) { "The wrong end of the brightness goes over first" }
            }
        }
    } + listOf(
        Case("Split screen: halves and divider on the pixel grid") {
            val settings = TransitionSettings(id = "T", style = TransitionStyle.Split, duration = 1.0)
            val laid = laid(settings, Form.Between) ?: return@Case
            val stage = Stage(bench, laid.clips, WIDTH, HEIGHT) { index, _ -> if (index == 0) flatA else flatB }
            try {
                val actual = stage.frame(laid.time(0.5))
                val row = (0 until WIDTH).map { actual.at(it, HEIGHT / 2) }
                val line = row.indices.filter { near(row[it], LINE) }
                val lastFirst = row.indexOfLast { near(it, flatA.at(0, 0)) }
                val firstSecond = row.indexOfFirst { near(it, flatB.at(0, 0)) }
                val core = TransitionGeometry.splitLine(settings, Size(WIDTH.toDouble(), HEIGHT.toDouble()))
                note("first picture up to pixel $lastFirst, divider on pixel(s) $line, second picture from pixel $firstSecond; core: halves meet at ${WIDTH * settings.divider}, line from ${core.x} to ${core.maxX}")
                note("the core's line covers two half pixels here; which whole pixel the original's renderer fills is not in its source")
                check(line.isNotEmpty() && line.all { it + 1.0 > core.x - 0.001 && it < core.maxX + 0.001 }) { "The divider is on $line, the core's line from ${core.x} to ${core.maxX}" }
                check(line.size <= 2) { "The divider is ${line.size} pixels wide" }
                check(lastFirst < firstSecond && firstSecond - lastFirst <= 3) { "The halves do not meet at the divider" }
                check(line.isNotEmpty() && (0 until HEIGHT).all { near(actual.at(line.first(), it), LINE) }) { "The divider does not run the whole height" }
            } finally {
                stage.release()
            }
        },
        Case("Whip pan: the slide and the reach of the smear") {
            val settings = TransitionSettings(id = "T", style = TransitionStyle.Whip, duration = 1.0)
            val gray = Picture.flat(WIDTH, HEIGHT, rgb(60, 60, 60))
            val actual = drawn(settings, Form.Between, 0.5, edge, gray) ?: return@Case
            val core = TransitionGeometry.whip(settings, 0.5, Size(WIDTH.toDouble(), HEIGHT.toDouble()))
            val row = IntArray(WIDTH) { actual.channel(it, HEIGHT / 2, 0) }
            val (dark, bright) = row.first() to row.last()
            val changing = row.indices.filter { abs(row[it] - dark) > 1 && abs(row[it] - bright) > 1 }
            val at = WIDTH / 2 + core.firstOffset
            // Half way between the two plateaus, in linear light.
            val linear = DoubleArray(WIDTH) { Light.linear(row[it] / 255.0) }
            val middle = crossing(linear, (linear.first() + linear.last()) / 2)
            note("edge moved to %.2f px (core: %.2f), smeared from pixel %d to %d; core: radius %.2f, so %.2f to %.2f".format(middle ?: Double.NaN, at, changing.first(), changing.last(), core.blurRadius, at - core.blurRadius, at + core.blurRadius))
            check(middle != null && abs(middle - at) <= 1.5) { "The edge is at $middle, the core's offset puts it at $at" }
            check(changing.first() + 0.5 >= at - core.blurRadius - 1.5 && changing.last() + 0.5 <= at + core.blurRadius + 1.5) { "The smear reaches past the radius: ${changing.first()} to ${changing.last()}" }
            check(changing.size >= 2 * core.blurRadius * 0.85) { "The smear is ${changing.size} px long, the radius ${core.blurRadius} to each side" }
        },
        Case("Defocus: the reach of the blur") {
            val settings = TransitionSettings(id = "T", style = TransitionStyle.Defocus, duration = 1.0, blurAmount = 1.0)
            // At a quarter of the way only the first picture shows.
            val actual = drawn(settings, Form.Between, 0.25, edge, flatB) ?: return@Case
            val core = TransitionGeometry.defocus(settings, 0.25, Size(WIDTH.toDouble(), HEIGHT.toDouble()))
            val row = IntArray(WIDTH) { actual.channel(it, HEIGHT / 2, 0) }
            val changing = row.indices.filter { abs(row[it] - row.first()) > 1 && abs(row[it] - row.last()) > 1 }
            note("blurred from pixel %d to %d around the edge at %d; core: radius %.2f".format(changing.first(), changing.last(), WIDTH / 2, core.blurRadius))
            check(changing.first() + 0.5 >= WIDTH / 2 - core.blurRadius - 1.5 && changing.last() + 0.5 <= WIDTH / 2 + core.blurRadius + 1.5) { "The blur reaches past the radius: ${changing.first()} to ${changing.last()}" }
            check(changing.size >= 2 * core.blurRadius * 0.7) { "The blur is ${changing.size} px across, the radius ${core.blurRadius}" }
            check(abs(row.first() - 30) <= 1 && abs(row.last() - 230) <= 1) { "Away from the edge the picture is not as it was" }
        },
    ) + SIZES.flatMap { (width, height) ->
        listOf(
            Case("Whip pan: no copies of a line at ${width}x$height") { whipSamples(width, height) },
            Case("Defocus: no copies of a square at ${width}x$height, amount 1") { defocusSamples(width, height, 1.0) },
            Case("Defocus: no copies of a square at ${width}x$height, default amount") { defocusSamples(width, height, null) },
        )
    } + Case("Whip pan and defocus: the time a frame takes at 3840x2160") { blurCost(3840, 2160) } +
        Case("Defocus in the preview: the decks spread, then the disc") { previewDefocus() }

    /**
     * The preview's shader, compiled and run by the platform as the editor runs it, on a layer of two decks: a
     * bright square in the first through a defocus becomes one even disc, as in the export.
     */
    private fun Checks.previewDefocus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return note("no preview shader below Android 13")
        val (width, height) = 480 to 270
        val radius = 30f
        val half = 4
        val decks = Bitmap.createBitmap(width, 2 * height, Bitmap.Config.ARGB_8888)
        Canvas(decks).apply {
            drawColor(Color.BLACK)
            drawRect((width / 2 - half).toFloat(), (height / 2 - half).toFloat(), (width / 2 + half + 1).toFloat(), (height / 2 + half + 1).toFloat(), Paint().apply { color = Color.WHITE })
        }
        val values = FloatArray(24).also { it[0] = radius; it[1] = 0f; it[2] = 0.12f; it[3] = 0.1f }
        val drawing = SceneDrawing(SceneDrawing.DEFOCUS, 0f, 1f, FloatArray(3), values)
        val reader = ImageReader.newInstance(width, 2 * height, PixelFormat.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val node = RenderNode("decks").apply {
            setPosition(0, 0, width, 2 * height)
            beginRecording().drawBitmap(decks, 0f, 0f, null)
            endRecording()
            setRenderEffect(TransitionPreviewShader().effect(drawing, width.toFloat(), height.toFloat()))
        }
        val renderer = HardwareRenderer().apply { setSurface(reader.surface); setContentRoot(node) }
        try {
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = reader.acquireNextImage()
            val drawn = Bitmap.wrapHardwareBuffer(checkNotNull(image.hardwareBuffer), null)!!.copy(Bitmap.Config.ARGB_8888, false)
            image.close()
            fun encoded(x: Int, y: Int) = (drawn.getPixel(x, y) shr 16) and 0xff
            val row = (0 until width).map { encoded(it, height / 2) }
            val lit = row.indices.filter { Light.linear(row[it] / 255.0) > LIT }
            val gaps = if (lit.isEmpty()) 0 else (lit.first()..lit.last()).count { it !in lit }
            val inner = mutableListOf<Int>()
            for (y in 0 until height) for (x in 0 until width) if (hypot(x + 0.5 - width / 2.0, y + 0.5 - height / 2.0) <= radius - half * 1.5 - 3) inner += encoded(x, y)
            val mean = inner.map { Light.linear(it / 255.0) }.average()
            val even = (2 * half + 1.0) * (2 * half + 1.0) / (Math.PI * radius * radius)
            note("lit from pixel %s to %s on the middle row, %d dark between; inside the disc encoded %d to %d, %.5f in linear light (an even disc: %.5f)"
                .format(lit.firstOrNull(), lit.lastOrNull(), gaps, inner.min(), inner.max(), mean, even))
            check(lit.isNotEmpty() && gaps == 0) { "The disc has $gaps dark pixels across its middle" }
            check(inner.max() - inner.min() <= 3) { "The disc ranges from ${inner.min()} to ${inner.max()} inside: the samples show" }
            check(abs(mean - even) <= even * 0.2) { "The disc is $mean on average, an even disc $even" }
            // Where the light falls to half its inside, on each side of the middle.
            val rim = listOf((width / 2 downTo 0).firstOrNull { Light.linear(row[it] / 255.0) < mean / 2 }, (width / 2 until width).firstOrNull { Light.linear(row[it] / 255.0) < mean / 2 })
            note("half light at pixels %s from the middle; radius %.1f".format(rim.map { it?.let { at -> abs(at - width / 2) } }, radius))
            check(rim.all { it != null && abs(abs(it - width / 2) - radius) <= 3 }) { "The disc's rim is at $rim, the radius $radius" }
        } finally {
            renderer.destroy()
            reader.close()
            node.discardDisplayList()
        }
    }

    /**
     * A thin bright line through a whip pan at its peak. The original smears it on a picture reduced to 960 pixels
     * on its long side (`YouRec/TransitionRenderer.swift:223-234`, `:268-275`): one even streak, with no copies of it.
     */
    private fun Checks.whipSamples(width: Int, height: Int) {
        val settings = TransitionSettings(id = "T", style = TransitionStyle.Whip, duration = 1.0)
        val laid = laid(settings, Form.Between) ?: return
        val line = Picture(width, height) { x, _ -> if (abs(x - width / 2) <= 1) rgb(255, 255, 255) else 0 }
        val black = Picture.flat(width, height, 0)
        val stage = Stage(bench, laid.clips, width, height) { index, _ -> if (index == 0) line else black }
        try {
            val actual = stage.frame(laid.time(0.5))
            val canvas = Size(width.toDouble(), height.toDouble())
            val core = TransitionGeometry.whip(settings, 0.5, canvas)
            val scale = TransitionGeometry.blurScale(core.blurRadius, canvas)
            val encoded = IntArray(width) { actual.channel(it, height / 2, 0) }
            val row = DoubleArray(width) { Light.linear(encoded[it] / 255.0) }
            // Runs of lit pixels: one, unless the samples of the smear show as copies of the line.
            val runs = mutableListOf<IntRange>()
            var x = 0
            while (x < width) {
                if (row[x] <= LIT) { x++; continue }
                val start = x
                while (x < width && row[x] > LIT) x++
                runs += start until x
            }
            check(runs.size == 1) { "${runs.size} separate streaks: ${runs.take(6)}" }
            if (runs.isEmpty()) return
            val center = width / 2 + core.firstOffset
            // Inside the streak, away from its ends, which a pixel of the reduced picture and its enlargement soften.
            val margin = 2 / scale + 2
            val inner = ((center - core.blurRadius + margin).toInt()..(center + core.blurRadius - margin).toInt()).map { encoded[it] }
            val lit = runs.first().first..runs.last().last
            // The line is three pixels wide at full light: its light spread evenly over the length of the smear.
            val even = 3 / (2 * core.blurRadius) * (1 - core.mix)
            val mean = inner.map { Light.linear(it / 255.0) }.average()
            note("lit from pixel %d to %d, encoded %d to %d inside, %.5f in linear light on average (an even smear: %.5f); core: radius %.2f about %.1f, reduced by %.3f"
                .format(lit.first, lit.last, inner.min(), inner.max(), mean, even, core.blurRadius, center, scale))
            check(inner.max() - inner.min() <= 2) { "The streak ranges from ${inner.min()} to ${inner.max()} inside: the samples show" }
            check(abs(mean - even) <= even * 0.15) { "The streak is $mean on average, an even smear $even" }
            check(abs(lit.first + 0.5 - (center - core.blurRadius)) <= margin + 1 && abs(lit.last + 0.5 - (center + core.blurRadius)) <= margin + 1) {
                "The streak runs from ${lit.first} to ${lit.last}, the core's from ${center - core.blurRadius} to ${center + core.blurRadius}"
            }
        } finally {
            stage.release()
        }
    }

    /**
     * A bright square a quarter of the radius wide through a defocus at its peak. The original blurs it on a reduced
     * picture by an aperture (`YouRec/TransitionRenderer.swift:239-251`): one even disc, with no copies of the square.
     */
    private fun Checks.defocusSamples(width: Int, height: Int, amount: Double?) {
        val settings = TransitionSettings(id = "T", style = TransitionStyle.Defocus, duration = 1.0, blurAmount = amount)
        val laid = laid(settings, Form.Between) ?: return
        val canvas = Size(width.toDouble(), height.toDouble())
        val core = TransitionGeometry.defocus(settings, 0.5, canvas)
        val scale = TransitionGeometry.blurScale(core.blurRadius, canvas)
        val (cx, cy) = width / 2 to height / 2
        val half = (core.blurRadius / 8).roundToInt()
        val square = Picture(width, height) { x, y -> if (abs(x - cx) <= half && abs(y - cy) <= half) rgb(255, 255, 255) else 0 }
        val black = Picture.flat(width, height, 0)
        val stage = Stage(bench, laid.clips, width, height) { index, _ -> if (index == 0) square else black }
        try {
            val actual = stage.frame(laid.time(0.5))
            val reach = core.blurRadius.toInt() + half + 8
            val side = 2 * reach + 1
            fun encoded(x: Int, y: Int) = actual.channel(cx - reach + x, cy - reach + y, 0)
            fun lit(x: Int, y: Int) = Light.linear(encoded(x, y) / 255.0)
            // Patches of lit pixels that touch: one, unless the samples of the disc show as copies of the square.
            val seen = BooleanArray(side * side)
            var patches = 0
            for (start in 0 until side * side) {
                if (seen[start] || lit(start % side, start / side) <= LIT) continue
                patches++
                val open = ArrayDeque(listOf(start))
                seen[start] = true
                while (open.isNotEmpty()) {
                    val at = open.removeFirst()
                    val (x, y) = at % side to at / side
                    for ((nx, ny) in listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
                        if (nx !in 0 until side || ny !in 0 until side || seen[ny * side + nx] || lit(nx, ny) <= LIT) continue
                        seen[ny * side + nx] = true
                        open += ny * side + nx
                    }
                }
            }
            // Inside the disc, away from its rim (softened by the square, a pixel of the reduced picture and its enlargement).
            val margin = 2 / scale + 2 + half * 1.5
            val inner = mutableListOf<Int>()
            for (y in 0 until side) for (x in 0 until side) if (hypot((x - reach).toDouble(), (y - reach).toDouble()) <= core.blurRadius - margin) inner += encoded(x, y)
            val mean = inner.map { Light.linear(it / 255.0) }.average()
            // The square's light spread evenly over the disc, half of it mixed with the black second picture.
            val area = (2 * half + 1.0) * (2 * half + 1.0)
            val frame = TransitionGeometry.defocus(settings, 0.5, canvas)
            val even = area / (Math.PI * core.blurRadius * core.blurRadius) * (1 - frame.mix)
            // Where the light falls to half its inside along the row through the middle, on each side.
            val row = DoubleArray(side) { lit(it, reach) }
            val rim = listOf((reach downTo 0).firstOrNull { row[it] < mean / 2 }, (reach until side).firstOrNull { row[it] < mean / 2 })
            note("%d patch(es) of light; inside the disc encoded %d to %d, %.5f in linear light on average (an even disc: %.5f); half light at pixels %s from the middle; core: radius %.2f, reduced by %.3f, square %d px"
                .format(patches, inner.min(), inner.max(), mean, even, rim.map { it?.let { at -> abs(at - reach) } }, core.blurRadius, scale, 2 * half + 1))
            check(patches == 1) { "$patches separate patches of light" }
            check(inner.max() - inner.min() <= 3) { "The disc ranges from ${inner.min()} to ${inner.max()} inside: the samples show" }
            check(abs(mean - even) <= even * 0.15) { "The disc is $mean on average, an even disc $even" }
            check(rim.all { it != null && abs(abs(it - reach) - core.blurRadius) <= 2 / scale + 3 }) { "The disc's rim is at $rim, the core's radius ${core.blurRadius}" }
        } finally {
            stage.release()
        }
    }

    /** The time the export's effect takes to draw a frame of [width] × [height] of a dissolve, of a whip pan and of a defocus, each at its peak. */
    private fun Checks.blurCost(width: Int, height: Int) {
        val picture = Picture.flat(width, height, rgb(120, 90, 60))
        for (settings in listOf(
            TransitionSettings(id = "T", style = TransitionStyle.Dissolve, duration = 1.0),
            TransitionSettings(id = "T", style = TransitionStyle.Whip, duration = 1.0),
            TransitionSettings(id = "T", style = TransitionStyle.Defocus, duration = 1.0, blurAmount = 1.0),
            TransitionSettings(id = "T", style = TransitionStyle.Defocus, duration = 1.0),
        )) {
            val laid = laid(settings, Form.Between) ?: return
            val stage = Stage(bench, laid.clips, width, height) { _, _ -> picture }
            try {
                for (progress in listOf(0.25, 0.5)) {
                    note("%s%s at p=%.2f: %.1f ms".format(settings.style.name, settings.blurAmount?.let { " (amount $it)" } ?: "", progress, stage.cost(laid.time(progress))))
                }
            } finally {
                stage.release()
            }
        }
    }

    // MARK: Whole frames: flash cut and still

    /** The frames of a half-second flash cut with three flashes that show the next clip, as the original's rule gives them. */
    private val flashes = mapOf(24 to listOf(5, 8, 10), 25 to listOf(5, 9, 11), 30 to listOf(6, 11, 13), 60 to listOf(13, 22, 27))

    private fun flashCuts(): List<Case> = flashes.map { (rate, expected) ->
        Case("Flash cut: frames showing the next clip at $rate fps") {
            val settings = TransitionSettings(id = "T", style = TransitionStyle.FlashCut, duration = 0.5)
            val laid = laid(settings, Form.Between) ?: return@Case
            val stage = Stage(bench, laid.clips, WIDTH, HEIGHT, framesPerSecond = rate.toDouble()) { index, _ -> if (index == 0) quadsA else quadsB }
            try {
                val frames = (0.5 * rate).roundToInt()
                val shown = (0 until frames).map { frame ->
                    val actual = stage.frameAt(Stage.stamp(laid.join.origin + frame.toDouble() / rate))
                    when {
                        actual.pixels.contentEquals(quadsA.pixels) -> 'A'
                        actual.pixels.contentEquals(quadsB.pixels) -> 'B'
                        else -> '?'
                    }
                }
                val flashed = shown.indices.filter { shown[it] == 'B' }
                note("of $frames frames from ${laid.join.origin} s, the next clip shows on $flashed; the original: $expected; the core: ${laid.settings.flashFrames(laid.join.timing.videoDuration, rate.toDouble())}")
                check('?' !in shown) { "A frame of a flash cut is neither clip whole: ${shown.joinToString("")}" }
                check(flashed == expected) { "The next clip shows on $flashed, the original has $expected" }
                val after = stage.frameAt(Stage.stamp(laid.join.origin + 0.5))
                check(after.pixels.contentEquals(quadsB.pixels)) { "The frame after the last is not the next clip" }
            } finally {
                stage.release()
            }
        }
    }

    /** A frame of a clip that says which clip and which of its frames it is. */
    private fun numbered(clip: Int, frame: Int) = Picture.flat(WIDTH, HEIGHT, rgb(70 + 60 * clip, 20 + 2 * (frame % 100), 128))

    private fun stills(): List<Case> = listOf(
        Case("Still: the frame held, through a freeze that cuts and one that dissolves") {
            // Three clips of three seconds; a one-second freeze that cuts, then half a second that dissolves over 0.3 s.
            val state = EditState(listOf(clip("A", 3.0), clip("B", 3.0), clip("C", 3.0)))
            val cut = TimelineEditing.setJoin(state, 1, TransitionSettings(id = "T1", style = TransitionStyle.Still, duration = 1.0))
            val clips = cut?.let { TimelineEditing.setJoin(it, 2, TransitionSettings(id = "T2", style = TransitionStyle.Still, duration = 0.5, stillDissolve = 0.3)) }?.clips
            check(clips != null) { "The core does not lay the stills out" }
            if (clips == null) return@Case
            val layout = TimelineEditing.layout(clips)
            fun frameOf(index: Int, time: Double) = ((time - layout.placements[index].start) * 30).roundToInt()
            val stage = Stage(bench, clips, WIDTH, HEIGHT) { index, time -> numbered(index, frameOf(index, time)) }
            try {
                check(stage.decks == 2) { "A freeze between clips takes two sequences" }
                var worst = 0.0
                var held = 0
                val wrong = mutableListOf<String>()
                // The frames of the export one after another, from half a second before the first freeze to past the second.
                val frames = ((layout.joins[1].origin - 0.5) * 30).roundToInt()..((layout.joins[2].origin + layout.joins[2].timing.videoDuration + 0.2) * 30).roundToInt()
                for (number in frames) {
                    val time = number / 30.0
                    val actual = stage.frameAt(Stage.stamp(time))
                    val join = listOf(1, 2).map { layout.joins[it] }.firstOrNull { time >= it.origin - 1e-9 && time < it.origin + it.timing.videoDuration - 1e-9 }
                    val (recipe, frame) = if (join == null) {
                        val index = layout.placements.first { time >= it.start - 1e-9 && time < it.end - 1e-9 }.index
                        Recipe(WIDTH, HEIGHT, Side.Image(numbered(index, frameOf(index, time))), Side.Solid(TransitionColor.BLACK)) to TransitionFrame.First
                    } else {
                        // The frame the core names: the last one of the outgoing clip on the grid of thirtieths before the freeze.
                        val placement = layout.placements[join.outgoing!!]
                        val kept = TransitionPicture.heldFrame(join.timing, join.origin, placement.start, placement.duration)!!
                        held++
                        Recipe(WIDTH, HEIGHT, Side.Image(numbered(placement.index, frameOf(placement.index, kept.shown))), Side.Image(numbered(join.incoming!!, frameOf(join.incoming!!, time)))) to
                            TransitionPicture.frame(join.transition!!, join.timing, time - join.origin, Size(WIDTH.toDouble(), HEIGHT.toDouble()))
                    }
                    val difference = compare(actual, recipe, frame, Tolerance.Levels(MIX_LEVELS), 16)
                    worst = maxOf(worst, difference.worst)
                    if (difference.over > 0) wrong += "frame $number (%.3f s): drawn %06x".format(time, actual.at(0, 0))
                }
                val first = TransitionPicture.heldFrame(layout.joins[1].timing, layout.joins[1].origin, layout.placements[0].start, layout.placements[0].duration)!!
                note("$held frames of freeze and dissolve among ${frames.count()} frames, worst $worst levels; the first freeze holds the frame of ${first.shown} s from ${first.from} s to ${first.until} s")
                check(wrong.isEmpty()) { "${wrong.size} frames are not what the core names: ${wrong.take(6)}" }
            } finally {
                stage.release()
            }
        },
    )

    fun all(): List<Case> = matrix() + variants() + measurements() + flashCuts() + stills()

    companion object {
        const val WIDTH = 320
        const val HEIGHT = 180
        const val SECONDS = 4.5
        val MOMENTS = listOf(0.0, 0.25, 0.5, 0.75, 1.0)

        /** The sizes the sample spacing of the two blurs is measured at. */
        val SIZES = listOf(1920 to 1080, 3840 to 2160)

        const val ALONE_LEVELS = 1.0
        const val MIX_LEVELS = 2.0
        const val FRAMED_LEVELS = 3.0

        /**
         * How far, in linear light, the samples along the smear may be from the even smear, on a frame of [WIDTH] by
         * [HEIGHT]: there they lie a pixel apart. Measured on a Galaxy A54: 0.007 at most.
         */
        const val WHIP_BOUND = 0.02

        /**
         * How far, in linear light, 96 samples of the disc with a brighter rim, on a picture spread first, may be from
         * the even disc, on a frame of [WIDTH] by [HEIGHT]; the spread softens the edges of the blocks. Measured on a
         * Galaxy A54: 0.040 at most. That no sample shows at full size the cases with a line and a square check.
         */
        const val DEFOCUS_BOUND = 0.05

        /** The divider of a split screen as it is encoded: a quarter gray. */
        val LINE = rgb(64, 64, 64)

        /** Linear light above this counts as lit, in the pictures that are black but for one mark. */
        const val LIT = 0.0008
    }
}
