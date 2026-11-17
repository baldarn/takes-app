@file:OptIn(UnstableApi::class)

package app.takes.pixels

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.SingleColorLut
import app.takes.ClipColor
import app.takes.ColorLuts
import app.takes.RecoveredColorLut
import app.takes.core.ColorGrade
import app.takes.core.ColorLook
import app.takes.core.ColorSettings
import app.takes.core.EffectAnimation
import app.takes.core.GradeMotion
import app.takes.core.LUTInput
import app.takes.core.LocalRecovery
import app.takes.core.LogColor
import app.takes.core.TimelineClip
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The pixel tests of the local highlight recovery of log footage (YouRec/LogColor.swift:129-180, at
 * YouRec/VideoToolCompositor.swift:513), on frames of Apple Log made here from a linear scene with the core's curve
 * ([LogColor.encode]): a bright sky with clouds and windows over a darker ground. The export's passes
 * ([RecoveredColorLut]), chosen by [ClipColor] as the export chooses them, against the core's recovery
 * ([LocalRecovery.recodeFrame]) read through the same table, and against the core's grade of the recovered light;
 * the block averaging of larger frames against the exact box; a moving grade at its ends; and the preview's shader,
 * with coefficients worked out from the frame, against the export.
 */
class LocalRecoveryPixelCases(private val bench: Bench) {
    private val folder: File = bench.context.cacheDir

    /** The light of the scene at a point of a frame of [width] by [height]: Rec.709, linear, 1 a white card. */
    private fun scene(x: Int, y: Int, width: Int, height: Int): DoubleArray {
        val u = x.toDouble() / width
        val v = y.toDouble() / height
        return when {
            // A sky from 2 to 9 times a white card, with soft clouds and a lit building's windows.
            v < 0.55 -> {
                val clouds = 4.5 + 2.5 * sin(u * 17.0 + v * 9.0) * sin(v * 23.0 - u * 5.0)
                val window = if (u in 0.6..0.85 && v > 0.25 && (x / 4) % 2 == 0 && (y / 4) % 2 == 0) 0.6 else 1.0
                val y0 = clouds * window
                doubleArrayOf(y0 * 0.92, y0 * 0.98, y0 * 1.12)
            }
            // A ground in shadow, a mid-grey wall and a red sign.
            u < 0.3 -> doubleArrayOf(0.05 + 0.1 * u, 0.06 + 0.08 * v, 0.04)
            u < 0.7 -> { val g = 0.18 * (1 + 0.3 * sin(x * 0.9)); doubleArrayOf(g, g, g) }
            else -> doubleArrayOf(0.9, 0.12, 0.08)
        }
    }

    /** A frame of [profile]'s code values, eight bits, of the scene above. */
    private fun logFrame(width: Int, height: Int, profile: LUTInput) = Picture(width, height) { x, y ->
        val codes = LogColor.encode(scene(x, y, width, height), profile)
        rgb(level(codes[0]), level(codes[1]), level(codes[2]))
    }

    private fun level(value: Double) = (value.coerceIn(0.0, 1.0) * 255).roundToInt()

    private fun codes(picture: Picture) = DoubleArray(picture.width * picture.height * 3) { i -> picture.channel((i / 3) % picture.width, (i / 3) / picture.width, i % 3) / 255.0 }

    private fun case(name: String, body: Checks.() -> Unit) = Case("local recovery: $name", body)

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

    private fun chosen(settings: ColorSettings, log: LUTInput?, clip: TimelineClip? = null): Effect {
        val effects = mutableListOf<Effect>()
        ClipColor(effects, false, null).look(settings, folder, log, clip, 0.0)
        return effects.single()
    }

    private fun drawn(effect: Effect, input: Picture, seconds: Double = 0.0): Picture {
        val stage = GlEffectStage(bench, effect as GlEffect)
        return try { stage.frame(input, (seconds * 1e6).roundToInt().toLong()) } finally { stage.release() }
    }

    /** The grade's table alone on [input]: Media3's colour LUT effect. */
    private fun table(settings: ColorSettings, profile: LUTInput, input: Picture) = drawn(SingleColorLut.createFromCube(ColorLuts.cube(settings, folder, profile)), input)

    /** The core's recovery of [input], as code values of eight bits. */
    private fun recoded(input: Picture, settings: ColorSettings, profile: LUTInput): Picture {
        val out = LocalRecovery.recodeFrame(codes(input), input.width, input.height, settings, profile)
        return Picture(input.width, input.height) { x, y -> val i = (y * input.width + x) * 3; rgb(level(out[i]), level(out[i + 1]), level(out[i + 2])) }
    }

    /** The original's grade of [input] with its recovery, worked out at each pixel without a table (YouRec/VideoToolCompositor.swift:505-529). */
    private fun graded(input: Picture, settings: ColorSettings, profile: LUTInput): Array<IntArray> {
        val codes = codes(input)
        val balanced = DoubleArray(codes.size) { i -> ColorGrade.logBalanced(settings, profile, codes[i / 3 * 3], codes[i / 3 * 3 + 1], codes[i / 3 * 3 + 2])[i % 3] }
        val recovered = LocalRecovery.apply(balanced, input.width, input.height, settings.localRecovery ?: 0.0)
        return Array(input.width * input.height) { p ->
            val color = ColorGrade.fromLogBalanced(settings, null, null, doubleArrayOf(recovered[p * 3], recovered[p * 3 + 1], recovered[p * 3 + 2]))
            IntArray(3) { level(color[it]) }
        }
    }

    /** The mean green and its spread over the sky, the upper half of the frame. */
    private fun sky(picture: Picture): Pair<Double, Double> {
        val values = buildList { for (y in 0 until picture.height / 2) for (x in 0 until picture.width) add(picture.channel(x, y, 1).toDouble()) }
        val mean = values.average()
        return mean to sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    private val grades = listOf(
        ColorSettings(localRecovery = 1.0),
        ColorSettings(localRecovery = 0.5, highlights = -0.4, exposure = 0.3, look = ColorLook.Warm, amount = 0.8),
    )

    fun all(): List<Case> = buildList {
        add(case("chosen for Log footage only, and only with a recovery") {
            check(chosen(grades[0], LUTInput.AppleLog) is RecoveredColorLut) { "Log with a recovery: ${chosen(grades[0], LUTInput.AppleLog)}" }
            check(chosen(grades[0], null) is SingleColorLut) { "not Log: ${chosen(grades[0], null)}" }
            check(chosen(ColorSettings(), LUTInput.AppleLog) is SingleColorLut) { "no recovery: ${chosen(ColorSettings(), LUTInput.AppleLog)}" }
        })
        for (profile in listOf(LUTInput.AppleLog, LUTInput.AppleLog2)) for (settings in grades) {
            add(case("export of ${profile.title} at 320 × 180, recovery ${settings.localRecovery}, highlights ${settings.highlights}: the core's, exactly boxed") {
                val input = logFrame(320, 180, profile)
                check(LocalRecovery.block(LocalRecovery.radius(320, 180)) == 1) { "not the exact box" }
                val picture = drawn(chosen(settings, profile), input)
                close("against the table on the core's recoded frame", picture, bound = 6, mean = 0.6, expected = same(table(settings, profile, recoded(input, settings, profile))))
                // The table read between its points, a few levels from the grade worked out at each pixel.
                val exact = graded(input, settings, profile)
                close("against the core's grade of the recovered light", picture, bound = 10, mean = 1.5) { x, y -> exact[y * input.width + x] }
                // The sky comes down and keeps its clouds: darker on average than without the recovery, its spread wider.
                val plain = sky(table(settings, profile, input))
                val recovered = sky(picture)
                note("sky without the recovery %.1f ± %.1f, with it %.1f ± %.1f".format(plain.first, plain.second, recovered.first, recovered.second))
                check(recovered.first < plain.first - 5) { "the sky is not darker: ${recovered.first} against ${plain.first}" }
                check(recovered.second > plain.second) { "the clouds are flatter: ${recovered.second} against ${plain.second}" }
                // The ground in shadow is untouched.
                val ground = table(settings, profile, input)
                for (x in listOf(10, 40, 80)) for (c in 0..2) {
                    check(abs(picture.channel(x, 170, c) - ground.channel(x, 170, c)) <= 2) { "the shadow at $x moved: ${picture.channel(x, 170, c)} against ${ground.channel(x, 170, c)}" }
                }
            })
        }
        add(case("export at 960 × 540 averages blocks: close to the exact box") {
            val profile = LUTInput.AppleLog
            val settings = grades[0]
            val input = logFrame(960, 540, profile)
            check(LocalRecovery.block(LocalRecovery.radius(960, 540)) > 1) { "not in blocks" }
            val picture = drawn(chosen(settings, profile), input)
            close("against the table on the core's recoded frame", picture, bound = 8, mean = 0.8, expected = same(table(settings, profile, recoded(input, settings, profile))))
        })
        add(case("a moving grade: A's table alone at the start, B's recovery at the end") {
            val profile = LUTInput.AppleLog
            val a = ColorSettings()
            val b = ColorSettings(localRecovery = 1.0)
            val clip = TimelineClip(id = "C", sourceID = "S", start = 0.0, end = 4.0).let { plain ->
                val motion = EffectAnimation(a.pose, plain.duration).copy(b = b.pose).anchored(plain.playbackSpans, 0.0)
                plain.copy(color = a.copy(motion = motion))
            }
            val ends = GradeMotion.ends(clip.color!!)!!
            val input = logFrame(320, 180, profile)
            val effect = chosen(clip.color!!, profile, clip)
            check(effect is RecoveredColorLut) { "a moving grade with a recovery is $effect" }
            close("start against A's table", drawn(effect, input, 0.0), bound = 3, mean = 0.3, expected = same(table(ends.first, profile, input)))
            val last = 4.0 - clip.color!!.motion!!.frame
            close("end against B's recovery", drawn(effect, input, last), bound = 1, mean = 0.1, expected = same(drawn(chosen(ends.second, profile), input)))
            val f = GradeMotion.fraction(clip, last / 2)!!
            val atA = table(ends.first, profile, input)
            val atB = drawn(chosen(ends.second, profile), input)
            close("halfway against the two mixed as light", drawn(effect, input, last / 2), bound = 3, mean = 0.5) { x, y ->
                val mixed = GradeMotion.dissolve(DoubleArray(3) { atA.channel(x, y, it) / 255.0 }, DoubleArray(3) { atB.channel(x, y, it) / 255.0 }, f)
                IntArray(3) { level(mixed[it]) }
            }
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) for (profile in listOf(LUTInput.AppleLog, LUTInput.AppleLog2)) {
            add(case("preview of ${profile.title} draws what the export draws") {
                val settings = grades[1]
                val input = logFrame(320, 180, profile)
                val preview = ColorLuts.Preview(settings, folder, profile)
                val recovery = checkNotNull(preview.recovery) { "the preview has no recovery" }
                val frame = Bitmap.createBitmap(IntArray(input.pixels.size) { input.pixels[it] or (0xff shl 24) }, input.width, input.height, Bitmap.Config.ARGB_8888)
                recovery.read(frame, RectF(0f, 0f, input.width.toFloat(), input.height.toFloat()), wait = true)
                val shown = ViewStage.frame(input, input.width, input.height, preview.effect())
                close("preview against the export", shown, bound = 6, mean = 1.0, expected = same(drawn(chosen(settings, profile), input)))
                check(ColorLuts.Preview(ColorSettings(), folder, profile).recovery == null) { "a grade without a recovery reads frames" }
            })
        }
    }
}
