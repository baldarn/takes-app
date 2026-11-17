@file:OptIn(UnstableApi::class)

package app.takes.pixels

import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.SingleColorLut
import app.takes.ClipColor
import app.takes.ColorLuts
import app.takes.HlgColorLut
import app.takes.MovingColorLut
import app.takes.core.ColorGrade
import app.takes.core.ColorLook
import app.takes.core.ColorSettings
import app.takes.core.EffectAnimation
import app.takes.core.GradeMotion
import app.takes.core.TimelineClip
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The pixel tests of a grade that moves between two ends (YouRec/VideoToolCompositor.swift:145-152): the export's
 * effect, chosen by [ClipColor] as the export chooses it and timed by the frame, draws A's grade alone at the start,
 * B's alone at the end and the two dissolved as light between them, against the core's grade and dissolve
 * ([GradeMotion]); the preview's shader draws what the export draws; a grade that does not move is still Media3's
 * table, and on an HLG picture still [HlgColorLut], so its pixels are those of before; and on an HLG picture the
 * moving grade is that table at either end and mixes the light between.
 */
class GradeMotionPixelCases(private val bench: Bench) {
    private val folder: File = bench.context.cacheDir

    /** Grey, the primaries and a spread of colours. */
    private val ramps = Picture(256, 64) { x, y ->
        when (y * 4 / 64) {
            0 -> rgb(x, x, x)
            1 -> rgb(x, (x * 0.8).toInt(), (x * 0.6).toInt())
            2 -> rgb((x * 0.5).toInt(), x, (x * 0.9).toInt())
            else -> { val n = (x + 256 * y) * 2654435761L; rgb((n shr 8).toInt() and 0xff, (n shr 16).toInt() and 0xff, (n shr 24).toInt() and 0xff) }
        }
    }

    private val a = ColorSettings(look = ColorLook.Warm, exposure = 0.5, amount = 0.8)
    private val b = ColorSettings(look = ColorLook.Mono, highlights = -0.5)

    /** A 4-second clip whose grade goes from [a] to [b] along the whole clip, linearly. */
    private val clip = TimelineClip(id = "C", sourceID = "S", start = 0.0, end = 4.0).let { plain ->
        val motion = EffectAnimation(a.pose, plain.duration).copy(b = b.pose).anchored(plain.playbackSpans, 0.0)
        plain.copy(color = a.copy(motion = motion))
    }

    private fun level(value: Double) = (value.coerceIn(0.0, 1.0) * 255).roundToInt()

    private fun case(name: String, body: Checks.() -> Unit) = Case("moving grade: $name", body)

    private fun Checks.close(what: String, picture: Picture, bound: Int, mean: Double, expected: (x: Int, y: Int) -> IntArray) {
        var worst = 0
        var sum = 0.0
        var count = 0
        for (y in 0 until picture.height) for (x in 0 until picture.width) {
            val want = expected(x, y)
            for (c in 0..2) {
                val d = abs(picture.channel(x, y, c) - want[c])
                worst = maxOf(worst, d)
                sum += d
                count++
            }
        }
        val average = sum / count
        note("$what: largest difference $worst levels, mean ${"%.2f".format(average)}")
        check(worst <= bound && average <= mean) { "$what: largest difference $worst levels (at most $bound), mean $average (at most $mean)" }
    }

    private fun same(picture: Picture): (Int, Int) -> IntArray = { x, y -> IntArray(3) { c -> picture.channel(x, y, c) } }

    /** The one effect [ClipColor] adds for [settings] on [clip], in an SDR movie or ([hdr]) on HLG footage of an HDR one. */
    private fun chosen(settings: ColorSettings, hdr: Boolean): Effect {
        val effects = mutableListOf<Effect>()
        ClipColor(effects, hdr, if (hdr) C.COLOR_TRANSFER_HLG else null).look(settings, folder, null, clip, 0.0)
        return effects.single()
    }

    private fun drawn(effect: Effect, input: Picture, seconds: Double): Picture {
        val stage = GlEffectStage(bench, effect as GlEffect)
        return try { stage.frame(input, (seconds * 1e6).roundToInt().toLong()) } finally { stage.release() }
    }

    /** Where the motion is [fraction] of the way, in seconds of the clip, and the fraction there. */
    private fun at(fraction: Double): Pair<Double, Double> {
        val last = 4.0 - clip.color!!.motion!!.frame
        val seconds = fraction * last
        return seconds to GradeMotion.fraction(clip, seconds)!!
    }

    private fun graded(settings: ColorSettings, x: Int, y: Int): DoubleArray =
        ColorGrade.apply(settings, null, ramps.channel(x, y, 0) / 255.0, ramps.channel(x, y, 1) / 255.0, ramps.channel(x, y, 2) / 255.0)

    fun all(): List<Case> = buildList {
        add(case("a fixed grade is still Media3's table in SDR and the HLG table in HDR") {
            check(chosen(a, hdr = false) is SingleColorLut) { "SDR: ${chosen(a, false)}" }
            check(chosen(a, hdr = true) is HlgColorLut) { "HDR: ${chosen(a, true)}" }
            check(chosen(clip.color!!, hdr = false) is MovingColorLut) { "a moving grade is not drawn as one" }
        })
        add(case("export in SDR: A at the start, B at the end, the two dissolved as light between") {
            val effect = chosen(clip.color!!, hdr = false)
            val ends = GradeMotion.ends(clip.color!!)!!
            fun fixed(settings: ColorSettings) = drawn(SingleColorLut.createFromCube(ColorLuts.cube(settings, folder)), ramps, 0.0)
            val atA = fixed(ends.first)
            val atB = fixed(ends.second)
            close("start against A's table", drawn(effect, ramps, 0.0), bound = 3, mean = 0.3, expected = same(atA))
            close("last frame against B's table", drawn(effect, ramps, at(1.0).first), bound = 3, mean = 0.3, expected = same(atB))
            for (share in listOf(0.25, 0.5, 0.75)) {
                val (seconds, f) = at(share)
                val picture = drawn(effect, ramps, seconds)
                close("at $share against the core's dissolve", picture, bound = 6, mean = 1.2) { x, y ->
                    val mixed = GradeMotion.dissolve(graded(ends.first, x, y), graded(ends.second, x, y), f)
                    IntArray(3) { level(mixed[it]) }
                }
                // Mixed as light, not as signal: a mid grey between Warm's and Mono's is brighter than their mean signal.
                if (share == 0.5) {
                    val x = 128
                    val signalMean = (atA.channel(x, 0, 1) + atB.channel(x, 0, 1)) / 2.0
                    check(picture.channel(x, 0, 1) >= signalMean - 0.5) { "green ${picture.channel(x, 0, 1)} under the signals' mean $signalMean" }
                }
            }
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(case("preview shader draws what the export draws") {
            val effect = chosen(clip.color!!, hdr = false)
            val preview = ColorLuts.Preview(clip.color!!, folder, null)
            check(preview.moves) { "the preview does not see the motion" }
            for (share in listOf(0.0, 0.3, 0.5, 1.0)) {
                val (seconds, f) = at(share)
                val shown = ViewStage.frame(ramps, ramps.width, ramps.height, preview.effect(f))
                close("preview at $share against the export", shown, bound = 4, mean = 1.0, expected = same(drawn(effect, ramps, seconds)))
            }
            // A grade that does not move draws its own table, whatever the fraction.
            val still = ColorLuts.Preview(a, folder, null)
            check(!still.moves) { "a fixed grade moves" }
            close("fixed grade", ViewStage.frame(ramps, ramps.width, ramps.height, still.effect(0.7)), bound = 0, mean = 0.0,
                expected = same(ViewStage.frame(ramps, ramps.width, ramps.height, ColorLuts.previewEffect(a, folder))))
        })
        add(case("export of HLG footage: the HLG table of A and of B at the ends, their light mixed between") {
            val effect = chosen(clip.color!!, hdr = true)
            val ends = GradeMotion.ends(clip.color!!)!!
            val atA = drawn(HlgColorLut(ColorLuts.table(ends.first, folder)), ramps, 0.0)
            val atB = drawn(HlgColorLut(ColorLuts.table(ends.second, folder)), ramps, 0.0)
            close("start against A's HLG table", drawn(effect, ramps, 0.0), bound = 0, mean = 0.0, expected = same(atA))
            close("last frame against B's HLG table", drawn(effect, ramps, at(1.0).first), bound = 1, mean = 0.1, expected = same(atB))
            val (seconds, f) = at(0.5)
            // The light of each end, mixed, in BT.2020 as in BT.709: the primaries are a linear change of the light.
            // Where an end is held at HLG's peak or at black, as colours outside BT.709 go below it, its light is not
            // known from its picture: those channels are left out.
            val halfway = drawn(effect, ramps, seconds)
            fun held(level: Int) = level == 0 || level == 255
            close("halfway against the mixed light of the two", halfway, bound = 3, mean = 0.6) { x, y ->
                IntArray(3) { c ->
                    if (held(atA.channel(x, y, c)) || held(atB.channel(x, y, c))) halfway.channel(x, y, c)
                    else signal(light(atA.channel(x, y, c)) * (1 - f) + light(atB.channel(x, y, c)) * f)
                }
            }
        })
    }

    private companion object {
        const val HLG_WHITE = 0.26496256

        /** The light of an 8-bit HLG signal, counted from SDR white (`hlgLin`). */
        fun light(level: Int): Double {
            val e = level / 255.0
            val l = if (e <= 0.5) e * e / 3 else (exp((e - 0.55991073) / 0.17883277) + 0.28466892) / 12
            return l / HLG_WHITE
        }

        /** The 8-bit HLG signal of [light] counted from SDR white (`hlgEnc`). */
        fun signal(light: Double): Int {
            val l = (light * HLG_WHITE).coerceIn(0.0, 1.0)
            val e = if (l <= 1.0 / 12) sqrt(3 * l) else 0.17883277 * ln(12 * l - 0.28466892) + 0.55991073
            return (e * 255).roundToInt()
        }
    }
}
