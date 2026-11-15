package app.takes.core

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

/*
 * Quantization: its palettes in Oklab, the analysis that takes a palette
 * from the scene, the Pop palettes, the palette a frame is drawn with, the
 * two bilateral smoothing passes and the shader's branch. The analysis is
 * YouRec/VideoEffects/VideoEffectMath.swift:81-163 (cited as `Math`), the
 * palette a frame receives YouRec/VideoEffects/VideoEffectAssets.swift
 * (`Assets`), the passes YouRec/VideoEffects/MetalVideoEffects.swift
 * (`Renderer`) and the branch YouRec/VideoEffects/VideoEffects.metal (`Metal`).
 * Face detail is not here: it needs a person mask.
 */

/**
 * The original's random numbers for the palette analysis: SplitMix64, each
 * number 53 bits over 2^53, so that a seed gives the same palette on every
 * device. Math:100-108.
 */
class EffectRandom(seed: ULong) {
    var state: ULong = seed
        private set

    fun next(): Double {
        state += 0x9E3779B97F4A7C15uL
        var x = state
        x = (x xor (x shr 30)) * 0xBF58476D1CE4E5B9uL
        x = (x xor (x shr 27)) * 0x94D049BB133111EBuL
        return ((x xor (x shr 31)) shr 11).toDouble() / 9007199254740992.0
    }
}

/** A built-in palette: sixteen colours, sampled dark to light to the number asked for. Math:90-99. */
data class EffectPalettePreset(val id: String, val name: String, val colors: List<String>) {
    fun sampled(count: Int): List<EffectRGB> {
        val sorted = colors.map { EffectRGB.hex(it) }.sortedBy { EffectColorMath.lab(EffectColorMath.linear(it)).x }
        if (sorted.size < 2 || count < 2) return sorted
        return (0 until count).map { sorted[floor(it.toDouble() * (sorted.size - 1) / (count - 1) + .5).toInt()] }
    }
}

/** A palette over time: the palette of the last frame at or before a second. Assets:37-46. */
data class EffectPaletteFrame(val time: Double, val palette: EffectPalette)

data class EffectPaletteTrack(val frames: List<EffectPaletteFrame>) {
    fun palette(time: Double): EffectPalette? {
        if (frames.isEmpty()) return null
        var a = 0
        var b = frames.size
        while (a < b) {
            val m = (a + b) / 2
            if (frames[m].time <= time) a = m + 1 else b = m
        }
        return frames[maxOf(0, a - 1)].palette
    }
}

object EffectPaletteAnalysis {
    /** The Pop palettes, the original's YouRec/VideoEffects/PopPalettes.json. */
    val pop: List<EffectPalettePreset> = listOf(
        EffectPalettePreset(
            "pop1", "Pop 1",
            listOf(
                "#141326", "#29204A", "#442059", "#64285D", "#8F285B", "#BB305E", "#D74667", "#E45F73",
                "#DC7D60", "#CB9A52", "#C8AD4F", "#D4C958", "#DEDE78", "#BEE6CF", "#D4EDF4", "#F4F4D8",
            ),
        ),
        EffectPalettePreset(
            "pop2", "Pop 2",
            listOf(
                "#10252B", "#183943", "#204D57", "#29616A", "#34797A", "#45928C", "#73A291", "#9BAE8C",
                "#BAB582", "#D2BE77", "#E3CA7C", "#EBD58F", "#EFE0AA", "#F1E9C2", "#F5F1D9", "#FAF8ED",
            ),
        ),
        EffectPalettePreset(
            "pop3", "Pop 3",
            listOf(
                "#231A35", "#3B2449", "#572851", "#773255", "#934453", "#B15350", "#CC6653", "#DE7D5D",
                "#E99772", "#EEAF8C", "#F0C1A3", "#E9CFB7", "#DCDCCF", "#E3E7E0", "#EDF0E8", "#FAF6E8",
            ),
        ),
    )

    /** The size of the analysed picture: 64 × 36, or 36 × 64 for a portrait one. Assets:226-234. */
    fun sampleSize(width: Int, height: Int): Pair<Int, Int> = if (width >= height) Pair(64, 36) else Pair(36, 64)

    /**
     * The colours the analysis looks at: the picture read bilinearly at the
     * centres of a 64 × 36 grid, as Core Image scales it down. The original
     * first draws the frame at 256 pixels on its long side through the
     * effects before Quantization; that is the caller's [image].
     */
    fun samples(image: EffectImage): List<EffectVector> {
        val (w, h) = sampleSize(image.width, image.height)
        return (0 until h).flatMap { y -> (0 until w).map { x -> image.sample(Point((x + .5) / w, (y + .5) / h)) } }
    }

    /**
     * A palette of [count] colours (2…16) from linear samples: each sample
     * held inside 0…1 and given a little more contrast ([EffectColorMath.prepared]);
     * the luma steps are the quantiles of the samples' luma, so B&W follows
     * the scene, never equal bands; six k-means rounds in Oklab from
     * [previous]'s colours when it has as many, else from k-means++ seeds
     * drawn with [seed]; an empty cluster is revived at the sample farthest
     * from the others; colours sorted dark to light. Unless the scene cut
     * (a colour moved more than 0.25), the colours and steps move towards the
     * new ones by `1 − exp(−elapsed / 0.5)`. Math:109-163.
     */
    fun analyze(samples: List<EffectVector>, count: Int, previous: EffectPalette? = null, elapsed: Double = 0.2, seed: ULong = 0uL): EffectPalette {
        val n = maxOf(2, minOf(16, count))
        val rgb = samples.filter { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }.map { EffectColorMath.prepared(it.clamped()) }
        if (rgb.isEmpty()) return EffectPalette(listOf(EffectRGB(0.0, 0.0, 0.0), EffectRGB(1.0, 1.0, 1.0)), listOf(0.5))
        val labs = rgb.map { EffectColorMath.lab(it) }
        val ys = rgb.map { EffectColorMath.luminance(it) }.sorted()
        val thresholds = (1 until n).map { i -> ys[minOf(ys.size - 1, (i.toDouble() * ys.size / n).toInt())] }
        val random = EffectRandom(seed)
        val centers: MutableList<EffectVector>
        if (previous != null && previous.colors.size == n) {
            centers = previous.colors.map { EffectColorMath.lab(EffectColorMath.linear(it)) }.toMutableList()
        } else {
            centers = mutableListOf(labs[(random.next() * labs.size).toInt() % labs.size])
            while (centers.size < n) {
                val distances = labs.map { p -> centers.minOf { p.distanceSquared(it) } }
                val target = random.next() * distances.sum()
                var cumulative = 0.0
                var index = labs.size - 1
                for (i in labs.indices) {
                    cumulative += distances[i]
                    if (cumulative >= target) {
                        index = i
                        break
                    }
                }
                centers += labs[index]
            }
        }
        repeat(6) {
            val sums = MutableList(n) { EffectVector.ZERO }
            val weights = IntArray(n)
            for (p in labs) {
                var best = 0
                var distance = Double.POSITIVE_INFINITY
                for (i in centers.indices) {
                    val d = p.distanceSquared(centers[i])
                    if (d < distance) {
                        distance = d
                        best = i
                    }
                }
                sums[best] += p
                weights[best] += 1
            }
            val populated = mutableListOf<EffectVector>()
            for (i in centers.indices) if (weights[i] > 0) {
                centers[i] = sums[i] / weights[i].toDouble()
                populated += centers[i]
            }
            if (populated.size < n) {
                // A blank first frame can seed identical grey centres: revive empty clusters from the least represented real pixel.
                val residual = labs.map { p -> populated.minOf { p.distanceSquared(it) } }.toMutableList()
                for (i in centers.indices) if (weights[i] == 0) {
                    val farthest = residual.indices.maxByOrNull { residual[it] } ?: break
                    centers[i] = labs[farthest]
                    if (residual[farthest] < 1e-10) continue
                    for (j in labs.indices) residual[j] = minOf(residual[j], labs[j].distanceSquared(centers[i]))
                }
            }
        }
        var sorted = centers.sortedBy { it.x }
        var bands = thresholds
        if (previous != null && previous.colors.size == n) {
            val old = previous.colors.map { EffectColorMath.lab(EffectColorMath.linear(it)) }.sortedBy { it.x }
            val cut = sorted.zip(old).any { (a, b) -> a.distance(b) > 0.25 }
            if (!cut) {
                val alpha = 1 - exp(-maxOf(0.0, elapsed) / 0.5)
                sorted = old.zip(sorted).map { (a, b) -> a + (b - a) * alpha }
                if (previous.thresholds.size == bands.size) bands = previous.thresholds.zip(bands).map { (a, b) -> a + (b - a) * alpha }
            }
        }
        return EffectPalette(sorted.map { EffectColorMath.rgb(EffectColorMath.linearFromLab(it)) }, bands)
    }

    /**
     * The seconds the editor's analysis looks at between [start] and [end]:
     * every 0.2 s and every key of "colors" or "lockPalette" ([keys]), each
     * taken at the first frame of [frameTimes] that reaches it (a frame
     * starting before [start] counts as at [start]). Assets:183-194.
     */
    fun analysisTimes(start: Double, end: Double, keys: List<Double>, frameTimes: List<Double>): List<Double> {
        val stride = generateSequence(start) { it + 0.2 }.takeWhile { it < end }.toList()
        val targets = (stride + keys.filter { it >= start && it < end }).toSet().sorted()
        var index = 0
        val result = mutableListOf<Double>()
        for (pts in frameTimes) {
            val time = maxOf(start, pts)
            if (!(index < targets.size && time + 0.00001 >= targets[index])) continue
            while (index < targets.size && time + 0.00001 >= targets[index]) index++
            result += time
        }
        return result
    }

    /**
     * A clip's palette track, deterministic whatever order the frames are
     * asked for in: at each of [times] the samples of that frame
     * ([samples]), analysed from the palette before it over the time
     * between, seeded by the frame's number at 30 fps. While the palette is
     * locked, and was at the frame before with as many colours, the palette
     * is kept. Assets:186-209.
     */
    fun track(effect: VideoEffectSettings, times: List<Double>, samples: (Double) -> List<EffectVector>): EffectPaletteTrack {
        val frames = mutableListOf<EffectPaletteFrame>()
        var previous: EffectPalette? = null
        var wasLocked = false
        for (time in times) {
            val locked = effect.toggle("lockPalette", time)
            val count = effect.number("colors", time).toInt()
            val keep = locked && wasLocked && previous?.colors?.size == count
            wasLocked = locked
            if (keep) continue
            val palette = analyze(samples(time), count, previous, frames.lastOrNull()?.let { time - it.time } ?: 0.2, VideoEffectTime.frameIndex(time, 30.0).toULong())
            frames += EffectPaletteFrame(time, palette)
            previous = palette
        }
        return EffectPaletteTrack(frames)
    }

    /** While recording, the palette is analysed on every sixth frame, from the one before. LiveVideoEffects.swift:604-612. */
    fun liveAnalyses(frame: Int): Boolean = frame % 6 == 0

    /**
     * The palette a frame is drawn with ([EffectFrameInputs.palette]), from
     * the [analyzed] one: none unless it has as many colours as the effect
     * asks for; the locked palette instead while Lock palette is on and it
     * has as many. B&W uses that palette's luma steps alone. Saved samples
     * the effect's own colours, with the analysed steps or, before any, the
     * midpoints of their lumas; a Pop palette takes its colours from the
     * preset and keeps the analysed steps. Assets:534-552.
     */
    fun displayPalette(effect: VideoEffectSettings, time: Double, analyzed: EffectPalette?): EffectPalette? {
        val count = effect.number("colors", time).toInt()
        var palette = if (analyzed?.colors?.size == count) analyzed else null
        val locked = effect.lockedPalette
        if (effect.toggle("lockPalette", time) && locked != null && locked.colors.size == count) palette = locked
        if (effect.toggle("monochrome", time)) return palette
        val choice = effect.choice("palette", time)
        val saved = effect.palette
        if (choice == "Saved" && saved != null && saved.size >= 2) {
            val colors = sample(saved, count)
            val luminances = colors.map { EffectColorMath.luminance(EffectColorMath.linear(it)) }.sorted()
            return EffectPalette(colors, palette?.thresholds ?: luminances.zipWithNext { a, b -> (a + b) / 2 })
        }
        val preset = pop.firstOrNull { it.name == choice }
        if (preset != null && palette != null) palette = palette.copy(colors = preset.sampled(count))
        return palette
    }

    /**
     * A saved palette brought to [count] colours, sorted dark to light: by
     * brightness rank when it has fewer, else by farthest-point picking from
     * the darkest, so that a lone saturated colour among neutrals survives.
     * Assets:553-571.
     */
    fun sample(colors: List<EffectRGB>, count: Int): List<EffectRGB> {
        if (colors.size <= 1 || count <= 1 || count == colors.size) return colors
        val entries = colors.map { it to EffectColorMath.lab(EffectColorMath.linear(it)) }.sortedBy { it.second.x }
        if (entries.size < count) return (0 until count).map { entries[floor(it.toDouble() * (entries.size - 1) / (count - 1) + .5).toInt()].first }
        val chosen = mutableListOf(0)
        val distance = entries.map { it.second.distanceSquared(entries[0].second) }.toMutableList()
        distance[0] = -1.0
        while (chosen.size < count) {
            val next = distance.indices.maxByOrNull { distance[it] } ?: break
            chosen += next
            distance[next] = -1.0
            for (i in entries.indices) distance[i] = minOf(distance[i], entries[i].second.distanceSquared(entries[next].second))
        }
        return chosen.sorted().map { entries[it].first }
    }
}

object VideoEffectQuantization {
    private val math = VideoEffectShaderMath

    /** The ordered-dither threshold of a pixel, 0…1, from a Bayer matrix of [size] (4 or 8). Metal:162-166. */
    fun bayer(x: Int, y: Int, size: Int): Double {
        var value = 0u
        var bit = 0
        while ((1 shl bit) < size) {
            val bx = (x.toUInt() shr bit) and 1u
            val by = (y.toUInt() shr bit) and 1u
            value = (value shl 2) or ((bx xor by) * 2u + by)
            bit++
        }
        return (value.toDouble() + .5) / (size * size)
    }

    /**
     * One edge-preserving smoothing pass, `ve_bilateral`, to a picture of
     * [width] × [height]: along a line across when [horizontal], else down,
     * `ceil(2σ)` taps each side (at most 10, σ at least 0.5) at whole pixels
     * of the output, each weighted by its distance and by how far its sRGB
     * colour is from the centre's (`/0.02`). Metal:432-437.
     */
    fun bilateral(input: EffectImage, width: Int, height: Int, sigma: Double, horizontal: Boolean): EffectImage {
        val s = maxOf(.5, sigma)
        val radius = minOf(10, ceil(s * 2).toInt())
        val dx = if (horizontal) 1.0 else 0.0
        val dy = if (horizontal) 0.0 else 1.0
        return EffectImage.of(width, height) { x, y ->
            val uv = Point((x + .5) / width, (y + .5) / height)
            val center = input.sample(uv)
            val reference = math.srgb(center)
            var result = EffectVector.ZERO
            var total = 0.0
            for (i in -radius..radius) {
                val c = input.sample(Point(uv.x + dx * i / width, uv.y + dy * i / height))
                val w = exp(-(i * i).toDouble() / (2 * s * s) - math.srgb(c).distanceSquared(reference) / .02)
                result += c * w
                total += w
            }
            result / maxOf(total, 1e-6)
        }
    }

    /** The size and σ the smoothing works at: the picture brought to 1080 on its short side at most (half that in lite quality), σ `(1 + 4 · smoothing)` pixels there. Renderer:451-457. */
    data class SmoothingPlan(val width: Int, val height: Int, val sigma: Double)

    fun smoothingPlan(width: Int, height: Int, smoothing: Double, lite: Boolean = false): SmoothingPlan {
        val scale = VideoEffectPasses.pixelScale(width, height)
        val factor = (if (lite) .5 else 1.0) / maxOf(1.0, scale)
        return SmoothingPlan(maxOf(2, (width * factor).toInt()), maxOf(2, (height * factor).toInt()), (1 + 4 * smoothing) * scale * factor)
    }

    /** The smoothed picture Quantization reads, [EffectAuxiliary.aux]: two bilateral passes, across then down; none without smoothing. Renderer:451-457. */
    fun smoothed(input: EffectImage, smoothing: Double, lite: Boolean = false): EffectImage? {
        if (!(smoothing > 0)) return null
        val plan = smoothingPlan(input.width, input.height, smoothing, lite)
        val across = bilateral(input, plan.width, plan.height, plan.sigma, true)
        return bilateral(across, plan.width, plan.height, plan.sigma, false)
    }

    /**
     * Quantization of one colour, `ve_effect` case 7: [c] is the pixel, or
     * the smoothed picture under it; it gets the analysis's contrast, goes to
     * Oklab, is dithered in lightness by the Bayer threshold of pixel ([x],
     * [y]) times the mean step between neighbouring palette colours, and
     * then becomes the palette colour of its luma band (B&W, as that band's
     * grey; a Pop palette) or its nearest palette colour in Oklab (From
     * scene, Saved). [palette] is in Oklab and [thresholds] the luma steps,
     * as the block carries them; [dithering] is the choice's index, 0 Off.
     * Fewer than two colours leave the picture alone. Metal:313-316, :357-365.
     */
    fun quantize(
        c: EffectVector,
        x: Int,
        y: Int,
        palette: List<EffectVector>,
        thresholds: List<Double>,
        monochrome: Boolean = false,
        paletteChoice: Int = 0,
        dithering: Int = 0,
    ): EffectVector {
        val count = palette.size
        if (count < 2) return c
        val luma = math.luma(c)
        var v = math.lab((c + .2 * (luma * luma * (3 - 2 * luma) - luma)).clamped())
        if (dithering > 0) {
            var spread = 0.0
            for (i in 1 until count) spread += palette[i - 1].distance(palette[i])
            spread /= maxOf(1, count - 1)
            v = v.copy(x = v.x + (bayer(x, y, if (dithering < 2) 4 else 8) - .5) * spread)
        }
        return if (monochrome || paletteChoice in 1..3) {
            val l = math.luma(math.unlab(v).clamped())
            var best = 0
            for (i in 0 until count - 1) if (l >= thresholds.getOrElse(i) { 0.0 }) best++
            if (monochrome) math.lin(EffectVector(best.toDouble() / maxOf(1, count - 1))) else math.unlab(palette[best])
        } else {
            var best = 0
            var closest = 1e10
            for (i in 0 until count) {
                val d = v.distanceSquared(palette[i])
                if (d < closest) {
                    closest = d
                    best = i
                }
            }
            math.unlab(palette[best])
        }
    }

    /**
     * One pixel of Quantization's pass from its block [u], which carries the
     * frame's palette ([EffectFrameInputs.palette]), and the [smoothed] picture,
     * mixed with the input by the intensity. Face detail needs a person mask
     * and is left out, as the original does without one (`u.flags&1`).
     */
    fun pixel(u: EffectUniforms, input: EffectImage, smoothed: EffectImage?, x: Int, y: Int): EffectVector {
        require(u.effect == VideoEffectID.Quantization)
        val uv = input.uv(x, y)
        val original = input.sample(uv)
        if (u.count < 2) return original
        val c = if (u.number("smoothing") > 0 && smoothed != null) smoothed.sample(uv) else original
        val out = quantize(
            c, x, y, u.palette.take(u.count), u.thresholds,
            u.number("monochrome") > .5, u.number("palette").toInt(), u.number("dithering").toInt(),
        )
        return VideoEffectFormulas.mixed(original, out, u.intensity)
    }

    /** A whole pass: the smoothing, then [pixel] everywhere. */
    fun pass(u: EffectUniforms, input: EffectImage, lite: Boolean = false): EffectImage {
        val smoothed = smoothed(input, u.number("smoothing"), lite)
        return EffectImage.of(input.width, input.height) { x, y -> pixel(u, input, smoothed, x, y) }
    }
}
