package app.takes.pixels

import app.takes.NightFixEffect
import app.takes.core.EffectImage
import app.takes.core.EffectValue
import app.takes.core.EffectVector
import app.takes.core.NightFixMode
import app.takes.core.TimelineClip
import app.takes.core.VideoEffectID
import app.takes.core.VideoEffectNightFix
import app.takes.core.VideoEffectNightFusion
import app.takes.core.VideoEffectSettings
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Night fix Medium and Precise drawn by the export's program ([NightFixEffect]) over a run of frames, against the
 * core given the same frames ([VideoEffectNightFusion.frame], then [VideoEffectNightFix.restore]).
 *
 * The frames are a dark scene of soft blobs panning a few pixels a frame, each with noise of its own, and one frame
 * held twice. The program gives each frame on once the frames after it in its window have come, in order, at its own
 * time. The flow is measured by the GPU in half floats with the texture's own filtering where the core reads exactly,
 * so a pixel may land a little differently: most must be within [ALLOWED] levels, a few within [OUTLIER_LEVELS]. The
 * fusion must change the picture against Fast, and take noise away.
 */
class NightFusionPixelCases(private val bench: Bench, private val width: Int = 192, private val height: Int = 108) {
    private val blobs = Random(11).let { r -> List(70) { doubleArrayOf(r.nextDouble(-20.0, 230.0), r.nextDouble(-20.0, 130.0), r.nextDouble(4.0, 14.0), r.nextDouble(-.08, .16)) } }

    private fun scene(x: Double, y: Double): Double = .14 + blobs.sumOf { (bx, by, r, a) ->
        val d2 = (x - bx) * (x - bx) + (y - by) * (y - by)
        if (d2 > 16 * r * r) 0.0 else a * exp(-d2 / (2 * r * r))
    }

    /** Frame [i]: the scene moved [step] pixels across and a third of that down a frame, with noise of the frame's own. */
    private fun frame(i: Int, step: Double, noise: Int): Picture {
        val random = Random(1000 + i)
        return Picture(width, height) { x, y ->
            val v = scene(x - step * i, y - step * i / 3)
            fun level(scale: Double) = ((v * scale) * 255 + random.nextInt(-noise, noise + 1)).roundToInt()
            rgb(level(1.0), level(.92), level(1.08))
        }
    }

    private fun clean(i: Int, step: Double): Picture = Picture(width, height) { x, y ->
        val v = scene(x - step * i, y - step * i / 3)
        rgb((v * 255).roundToInt(), (v * .92 * 255).roundToInt(), (v * 1.08 * 255).roundToInt())
    }

    private fun image(picture: Picture): EffectImage = EffectImage.of(width, height) { x, y ->
        EffectVector(picture.channel(x, y, 0) / 255.0, picture.channel(x, y, 1) / 255.0, picture.channel(x, y, 2) / 255.0)
    }

    private fun picture(image: EffectImage): Picture = Picture(width, height) { x, y ->
        val c = image[x, y]
        fun level(v: Double) = (v.coerceIn(0.0, 1.0) * 255).roundToInt()
        rgb(level(c.x), level(c.y), level(c.z))
    }

    private fun distance(a: Int, b: Int): Int = (0..2).maxOf { abs(((a shr (16 - 8 * it)) and 0xff) - ((b shr (16 - 8 * it)) and 0xff)) }

    private fun settings(mode: NightFixMode, values: Map<String, Double>): VideoEffectSettings {
        var effect = VideoEffectSettings(VideoEffectID.NightFix).with("mode", EffectValue.Choice(mode.rawValue))
        for ((id, value) in values) effect = effect.with(id, EffectValue.Number(value))
        return effect
    }

    /** Mean squared error of the red channel against [truth], inside a margin of 12 pixels. */
    private fun error(picture: Picture, truth: Picture): Double {
        var sum = 0.0
        var n = 0
        for (y in 12 until height - 12) for (x in 12 until width - 12) {
            val d = picture.channel(x, y, 0) - truth.channel(x, y, 0)
            sum += d * d
            n++
        }
        return sum / n
    }

    private fun case(name: String, mode: NightFixMode, values: Map<String, Double>, count: Int, step: Double = 2.0, noise: Int = 10) = Case(name) {
        val effect = settings(mode, values)
        val clip = TimelineClip(id = "C", sourceID = "S", start = 0.0, end = 10.0, videoEffects = listOf(effect))
        val stage = FramesStage(bench, NightFixEffect(clip, 0.0), width, height)
        try {
            val sources = (0 until count).map { frame(it, step, noise) }
            val images = sources.map(::image)
            // Frame 2 is held for a second frame: the same source second again.
            val feed = (0 until count).flatMap { if (it == 2) listOf(it, it) else listOf(it) }
            val stamps = feed.indices.map { k -> ((1.0 + feed[k] / RATE) * 1e6).roundToLong() }
            val given = ArrayList<Pair<Long, Picture>>()
            feed.forEachIndexed { k, i -> given += stage.feed(sources[i], stamps[k]) }
            given += stage.end()
            given += stage.rest()
            check(given.map { it.first } == stamps) { "The frames are given on at ${given.map { it.first }}, not at $stamps" }
            var worstAll = 0
            var fusedNoise = 0.0
            var rawNoise = 0.0
            var changed = 0
            for ((k, i) in feed.withIndex()) {
                val time = 1.0 + i / RATE
                val fused = VideoEffectNightFusion.frame(images, i, mode)
                val want = picture(VideoEffectNightFix.restore(images[i], fused, effect, time))
                val fast = picture(VideoEffectNightFix.fast(images[i], effect, time))
                val got = given[k].second
                var worst = 0
                var worstAt = 0
                var over = 0
                for (p in want.pixels.indices) {
                    val d = distance(got.pixels[p], want.pixels[p])
                    if (d > worst) { worst = d; worstAt = p }
                    if (d > ALLOWED) over++
                }
                changed += want.pixels.indices.count { distance(want.pixels[it], fast.pixels[it]) > ALLOWED }
                worstAll = maxOf(worstAll, worst)
                val truth = picture(VideoEffectNightFix.fast(image(clean(i, step)), effect, time))
                fusedNoise += error(got, truth)
                rawNoise += error(fast, truth)
                note("frame $i: worst $worst levels at (${worstAt % width}, ${worstAt / width}): drawn #%06x, core #%06x; %d over %d".format(got.pixels[worstAt], want.pixels[worstAt], over, ALLOWED))
                check(over <= OUTLIERS && worst <= OUTLIER_LEVELS) { "Frame $i: $over pixels more than $ALLOWED levels from the core, the worst $worst" }
            }
            note("against the clean scene, mean squared error %.1f fused, %.1f Fast; %d pixels changed by the fusion".format(fusedNoise / feed.size, rawNoise / feed.size, changed))
            check(changed >= .2 * width * height * feed.size) { "The fusion changes too little: the case tests nothing" }
            check(fusedNoise < rawNoise * .8) { "The fusion takes too little noise away: $fusedNoise against $rawNoise" }
        } finally {
            stage.release()
        }
    }

    fun all(): List<Case> = listOf(
        case("Night fix Medium: a dark pan, no brightening", NightFixMode.Medium, mapOf("brightness" to 0.0), 5),
        case("Night fix Medium: a dark pan, defaults", NightFixMode.Medium, emptyMap(), 5),
        case("Night fix Precise: a dark pan, no brightening, light reduction", NightFixMode.Precise, mapOf("brightness" to 0.0, "reduction" to .6), 10),
        case("Night fix Precise: a dark pan, defaults", NightFixMode.Precise, emptyMap(), 10, step = 3.0),
    )

    companion object {
        const val RATE = 30.0

        /** Levels most pixels must be within. */
        const val ALLOWED = 2

        /** Pixels of a frame that may be further, and how far. */
        const val OUTLIERS = 64
        const val OUTLIER_LEVELS = 8
    }
}
