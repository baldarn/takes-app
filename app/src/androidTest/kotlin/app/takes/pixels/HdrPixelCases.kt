package app.takes.pixels

import app.takes.core.EditState
import app.takes.core.LayerPlan
import app.takes.core.Size
import app.takes.core.TextOverlay
import app.takes.core.TimelineClip
import app.takes.core.TimelineEditing
import app.takes.core.TransitionFrame
import app.takes.core.TransitionGeometry
import app.takes.core.TransitionPicture
import app.takes.core.TransitionSettings
import app.takes.core.TransitionStyle
import app.takes.core.toTimeline
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The transition and layer shaders compiled for an HDR movie (`TransitionShader.HDR`), in both exports: of a
 * sequence ([Stage]) and of picture layers ([LayerStage]). The pictures are the HLG signal itself, as an HDR export
 * hands them to the effect ([app.takes.HdrMovie]); the shader mixes in linear light counted from SDR white, which HLG
 * puts at 75 % of its signal (ITU-R BT.2408), so a highlight is above 1 as in the original's extended linear working
 * space (`YouRec/VideoToolCompositor.swift:194`), and a color given as SDR, a title's, is carried into HLG at its
 * reference white.
 */
class HdrPixelCases(private val bench: Bench) {
    private fun clip(id: String, titles: List<TextOverlay>? = null) = TimelineClip(id = id, sourceID = "S", start = 0.0, end = SECONDS, texts = titles)

    private fun joined(settings: TransitionSettings, titles: List<TextOverlay>? = null): List<TimelineClip> =
        TimelineEditing.setJoin(EditState(listOf(clip("A", titles), clip("B"))), 1, settings)!!.clips

    private fun layers(clips: List<TimelineClip>): LayerPlan = LayerPlan.planned(EditState(clips).toTimeline(), 2)!!

    /** The time [progress] of the way through the transition of [clips]. */
    private fun time(clips: List<TimelineClip>, progress: Double): Double = TimelineEditing.join(clips, 1)!!.let { it.origin + it.timing.motion * progress }

    /** The frame of [clips] at [time], from flat pictures of each clip, in the export of a sequence or of picture layers. */
    private fun drawn(clips: List<TimelineClip>, time: Double, width: Int, height: Int, layered: Boolean, hdr: Boolean, titled: Boolean = false, picture: (String) -> Picture): Picture =
        if (layered) {
            val plan = layers(clips)
            val stage = LayerStage(bench, plan, width, height, titled = titled, hdr = hdr) { picture(plan.pictures[it].id) }
            try { stage.frame(time) } finally { stage.release() }
        } else {
            val stage = Stage(bench, clips, width, height, titled = titled, hdr = hdr) { index, _ -> picture(clips[index].id) }
            try { stage.frame(time) } finally { stage.release() }
        }

    private fun export(layered: Boolean) = if (layered) "picture layers" else "a sequence"

    fun all(): List<Case> = listOf(false, true).flatMap { layered ->
        listOf(
            Case("HDR, ${export(layered)}: a dissolve mixes a highlight in light counted from SDR white") { dissolve(layered) },
            Case("HDR, ${export(layered)}: a defocus spreads a highlight as a disc brighter than SDR white allows") { defocus(layered) },
            Case("HDR, ${export(layered)}: a white title lands at SDR white, not at the highlight") { title(layered) },
        )
    }

    /**
     * The HLG peak, 3.77 times SDR white, dissolving into SDR white: half way the light is half of each, well above
     * SDR white, where a mix held to SDR white would give SDR white.
     */
    private fun Checks.dissolve(layered: Boolean) {
        val settings = TransitionSettings(id = "T", style = TransitionStyle.Dissolve, duration = 1.0)
        val clips = joined(settings)
        val peak = Picture.flat(WIDTH, HEIGHT, rgb(255, 255, 255))
        val white = Picture.flat(WIDTH, HEIGHT, rgb(SDR_WHITE, SDR_WHITE, SDR_WHITE))
        val pictures = mapOf("A" to peak, "B" to white)
        for (progress in listOf(0.0, 0.25, 0.5, 0.75)) {
            val share = (TransitionPicture.frame(settings, progress, Size(WIDTH.toDouble(), HEIGHT.toDouble())) as? TransitionFrame.Dissolve)?.second ?: 0.0
            val actual = drawn(clips, time(clips, progress), WIDTH, HEIGHT, layered, hdr = true) { pictures.getValue(it) }.channel(WIDTH / 2, HEIGHT / 2, 1)
            val expected = signal(light(255) * (1 - share) + light(SDR_WHITE) * share)
            note("p=%.2f, share %.3f: drawn %d, HLG mix %d (%.2f of SDR white)".format(progress, share, actual, expected, light(actual)))
            check(abs(actual - expected) <= 2) { "At $progress the dissolve is $actual, mixed in HLG's light $expected" }
            if (progress < 0.75) check(actual > SDR_WHITE + 10) { "At $progress the highlight is $actual, no brighter than SDR white" }
        }
        val sdr = drawn(clips, time(clips, 0.5), WIDTH, HEIGHT, layered, hdr = false) { pictures.getValue(it) }.channel(WIDTH / 2, HEIGHT / 2, 1)
        note("the SDR program on the same signal half way: $sdr")
    }

    /**
     * A square at the HLG peak on black through a defocus at its peak: its light spreads evenly over the disc, as
     * much light as the square holds, 3.77 times what a square at SDR white would give.
     */
    private fun Checks.defocus(layered: Boolean) {
        val (width, height) = 960 to 540
        val settings = TransitionSettings(id = "T", style = TransitionStyle.Defocus, duration = 1.0, blurAmount = 1.0)
        val clips = joined(settings)
        val canvas = Size(width.toDouble(), height.toDouble())
        val radius = TransitionGeometry.defocus(settings, 0.5, canvas).blurRadius
        val half = BlurMarks.half(width, height, settings)
        val square = Picture(width, height) { x, y -> if (abs(x - width / 2) <= half && abs(y - height / 2) <= half) rgb(255, 255, 255) else 0 }
        val black = Picture.flat(width, height, 0)
        // A quarter of the way only the first picture shows, blurred by sin(π/4) of the peak.
        val at = 0.25
        val blur = TransitionGeometry.defocus(settings, at, canvas).blurRadius
        val actual = drawn(clips, time(clips, at), width, height, layered, hdr = true) { if (it == "A") square else black }
        val inner = mutableListOf<Int>()
        for (y in 0 until height) for (x in 0 until width) if (hypot(x + 0.5 - width / 2.0, y + 0.5 - height / 2.0) <= blur - half * 1.5 - 3) inner += actual.channel(x, y, 1)
        val mean = inner.map { light(it) }.average()
        val even = light(255) * (2 * half + 1.0) * (2 * half + 1.0) / (Math.PI * blur * blur)
        note("radius %.1f at the peak, %.1f here; inside the disc signal %d to %d, %.4f of SDR white on average (the square's light spread evenly: %.4f, at SDR white it would be %.4f)"
            .format(radius, blur, inner.min(), inner.max(), mean, even, even / light(255)))
        check(inner.max() - inner.min() <= 4) { "The disc ranges from ${inner.min()} to ${inner.max()} inside: the samples show" }
        check(abs(mean - even) <= even * 0.2) { "The disc holds $mean of SDR white on average, the square's light spread evenly $even" }
    }

    /**
     * A title of white letters over black, before the transition: the letters are SDR white carried into HLG, 75 % of
     * the signal, and never the HLG peak a white of 255 would be if it went in as it is.
     */
    private fun Checks.title(layered: Boolean) {
        val settings = TransitionSettings(id = "T", style = TransitionStyle.Dissolve, duration = 1.0)
        val clips = joined(settings, listOf(TextOverlay(id = "W", text = "WWW", x = 0.5, y = 0.5, size = 0.4, start = 0.0, end = SECONDS)))
        val black = Picture.flat(WIDTH, HEIGHT, 0)
        val actual = drawn(clips, 1.0, WIDTH, HEIGHT, layered, hdr = true, titled = true) { black }
        val levels = (0 until WIDTH * HEIGHT).map { actual.channel(it % WIDTH, it / WIDTH, 1) }
        val letters = levels.count { it >= SDR_WHITE - 2 }
        val brightest = levels.max()
        val sdr = drawn(clips, 1.0, WIDTH, HEIGHT, layered, hdr = false, titled = true) { black }.let { picture -> (0 until WIDTH * HEIGHT).maxOf { picture.channel(it % WIDTH, it / WIDTH, 1) } }
        note("brightest pixel of the title $brightest (SDR white in HLG: $SDR_WHITE), $letters pixels within 2 of it; the SDR program draws it at $sdr")
        check(abs(brightest - SDR_WHITE) <= 2) { "The title's white is $brightest, SDR white in HLG $SDR_WHITE" }
        check(letters > 200) { "Only $letters pixels of the title are white" }
    }

    companion object {
        const val WIDTH = 320
        const val HEIGHT = 180
        const val SECONDS = 4.5

        /** The light of SDR white as a share of HLG's brightest (ITU-R BT.2408), as `HdrMovie.GLSL` has it. */
        private const val HLG_WHITE = 0.26496256

        /** SDR white in an 8-bit HLG signal: 75 %. */
        val SDR_WHITE = signal(1.0)

        /** The light of an 8-bit HLG signal, counted from SDR white (`hlgLin`). */
        fun light(level: Int): Double {
            val e = level / 255.0
            val l = if (e <= 0.5) e * e / 3 else (exp((e - 0.55991073) / 0.17883277) + 0.28466892) / 12
            return l / HLG_WHITE
        }

        /** The 8-bit HLG signal of [light] counted from SDR white, held to what HLG holds (`hlgEnc`). */
        fun signal(light: Double): Int {
            val l = (light * HLG_WHITE).coerceIn(0.0, 1.0)
            val e = if (l <= 1.0 / 12) sqrt(3 * l) else 0.17883277 * ln(12 * l - 0.28466892) + 0.55991073
            return (e * 255).roundToInt()
        }
    }
}
