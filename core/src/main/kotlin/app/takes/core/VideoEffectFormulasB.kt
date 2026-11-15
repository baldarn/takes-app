package app.takes.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The reference formulas of the second batch of video effects: Halation,
 * 16mm, Super 8, Widescreen 1953 without its oval bokeh, and Crash Zoom with
 * a target set by hand. Each is a branch of `ve_effect` and of `effectPoint`
 * in YouRec/VideoEffects/VideoEffects.metal (cited as `Metal`) with the same
 * constants and order of operations, reading the pictures that
 * [VideoEffectPasses] draws before it as YouRec/VideoEffects/MetalVideoEffects.swift
 * (`Renderer`) does. Conventions are those of [VideoEffectFormulas].
 */

/** Where Crash Zoom is in its move at one moment: how much it magnifies, and how fast that changes, per second. */
data class CrashZoomState(val zoom: Double, val velocity: Double)

/** A Super 8 light leak at one moment: how strong (0…1, a half sine), and whether it comes in from the left edge. */
data class LightLeak(val pulse: Double, val fromLeft: Boolean)

object VideoEffectFormulasB {
    private val math = VideoEffectShaderMath

    /**
     * The shader's value noise: whole-number hashes at the corners of the
     * cell under [px], [py], smoothly blended, −1…1. Gate weave reads it
     * along a line, with the frame number scaled into [px]. Metal:35-40.
     */
    fun noise(px: Double, py: Double, frame: Int, seed: Int): Double {
        val cx = floor(px).toInt()
        val cy = floor(py).toInt()
        fun smooth(f: Double) = f * f * (3 - 2 * f)
        val fx = smooth(math.fract(px))
        val fy = smooth(math.fract(py))
        val z = frame.toUInt() + seed.toUInt()
        fun at(x: Int, y: Int) = math.random01(x.toUInt(), y.toUInt(), z)
        return math.mix(math.mix(at(cx, cy), at(cx + 1, cy), fx), math.mix(at(cx, cy + 1), at(cx + 1, cy + 1), fx), fy) * 2 - 1
    }

    /**
     * The film gate: 1 inside a rectangle with corners rounded by [radius]
     * (in picture fractions, so the corners are ovals on a picture that is
     * not square), falling to 0 over 0.004 at its edge. Metal:158-161.
     */
    fun gate(uv: Point, radius: Double): Double {
        val dx = abs(uv.x - .5) - (.5 - radius)
        val dy = abs(uv.y - .5) - (.5 - radius)
        val outside = sqrt(maxOf(dx, 0.0).pow(2) + maxOf(dy, 0.0).pow(2))
        return 1 - math.smoothstep(radius - .004, radius + .004, outside + minOf(maxOf(dx, dy), 0.0))
    }

    /** The frame number scaled as the shader does it, in single precision: the weave's position along its noise. */
    private fun along(frame: Int, rate: Float): Double = (frame.toUInt().toFloat() * rate).toDouble()

    // Halation. Metal:375-376; parameters glow, red, flash, flashColor.

    /** The flash colours in the catalogue's order: Neutral, Warm, Cool. Linear, as the shader uses them. */
    val flashColors: List<EffectVector> = listOf(EffectVector(1.0), EffectVector(1.0, .8, .6), EffectVector(.65, .8, 1.0))

    /**
     * Halation of a colour, given what its three blurred highlights hold
     * under the pixel ([VideoEffectPasses.halation]): [glow] the 1 % blur,
     * [halo] the 0.6 % one whose red alone fringes, [wide] the 4 % one. The
     * flash lifts the shadows by the colour, nothing at white.
     */
    fun halation(
        c: EffectVector,
        glow: EffectVector,
        halo: EffectVector,
        wide: EffectVector,
        amount: Double = 0.4,
        red: Double = 0.5,
        flash: Double = 0.2,
        flashColor: Int = 1,
    ): EffectVector {
        val y = math.luma(c)
        return c + (glow * .65 + wide * .35) * amount + EffectVector(1.0, .25, .1) * halo.x * red + flashColors[flashColor] * flash * .08 * (1 - y)
    }

    // 16mm. Metal:115-118, :377-378; parameters grain, grainSize, softness, weave, frameEdge, color.

    /** The grain sizes of Fine, Medium and Coarse, in pixels at 1080. Metal:378, Renderer:438. */
    val sixteenMMGrainSizes: List<Double> = listOf(1.0, 1.5, 2.2)

    /** 16mm's gate weave at a frame: how far the picture moves, in pixels of a picture of [size]. Metal:116. */
    fun sixteenMMWeave(frame: Int, weave: Double, size: Size): Point {
        val scale = minOf(size.width, size.height) / 1080
        val t = along(frame, .13f)
        return Point(noise(t, 2.0, 0, 7) * weave * scale * 2, noise(t, 9.0, 0, 3) * weave * scale * 2)
    }

    /** Where the pixel at [uv] reads the 16mm picture: magnified by 1.006 about the centre, then moved by the weave. Metal:115-118. */
    fun sixteenMMPoint(uv: Point, size: Size, frame: Int, weave: Double): Point {
        val move = sixteenMMWeave(frame, weave, size)
        return Point((uv.x - .5) / 1.006 + .5 + move.x / size.width, (uv.y - .5) / 1.006 + .5 + move.y / size.height)
    }

    /**
     * 16mm before its grain and gate, given the picture and its blur read at
     * the woven point: softness mixes in the blur, a gentle shoulder above
     * 0.85, and B&W takes the luma.
     */
    fun sixteenMMTone(sharp: EffectVector, soft: EffectVector, softness: Double = 0.3, mono: Boolean = false): EffectVector {
        var out = math.mix(sharp, soft, softness)
        out /= out.map { maxOf(it - .85, 0.0) } * .15 + 1.0
        if (mono) out = EffectVector(math.luma(out))
        return out
    }

    /** The corner radius of 16mm's frame edge. Metal:378. */
    const val SIXTEEN_MM_GATE = 0.035

    // Super 8. Metal:119-122, :379-383; parameters fade, grain, weave, fps, leaks, border.

    /** Super 8's grain size, in pixels at 1080. Renderer:439. */
    const val SUPER_EIGHT_GRAIN_SIZE = 2.2

    /** The corner radius of Super 8's frame border. Metal:383. */
    const val SUPER_EIGHT_GATE = 0.055

    /** Super 8's weave at a frame, in pixels: twice as far across as 16mm's noise, three times down. Metal:120. */
    fun superEightWeave(frame: Int, weave: Double, size: Size): Point {
        val scale = minOf(size.width, size.height) / 1080
        val t = along(frame, .16f)
        return Point(noise(t, 2.0, 0, 5) * 2 * weave * scale, noise(t, 9.0, 0, 17) * 3 * weave * scale)
    }

    /** Where the pixel at [uv] reads the Super 8 picture: magnified by 1.012 about the centre, then woven. Metal:119-122. */
    fun superEightPoint(uv: Point, size: Size, frame: Int, weave: Double): Point {
        val move = superEightWeave(frame, weave, size)
        return Point((uv.x - .5) / 1.012 + .5 + move.x / size.width, (uv.y - .5) / 1.012 + .5 + move.y / size.height)
    }

    /**
     * Where Super 8 reads its red and its blue: the green at the woven point,
     * red one pixel (at 1080) further out from the centre in proportion to the
     * distance, blue as far in. Returns red's, green's, blue's. Metal:379-380.
     */
    fun superEightChannels(uv: Point, size: Size, point: Point): RGBOffsetSamples {
        val scale = minOf(size.width, size.height) / 1080
        val ox = (uv.x - .5) * scale / size.width
        val oy = (uv.y - .5) * scale / size.height
        return RGBOffsetSamples(Point(point.x + ox, point.y + oy), point, Point(point.x - ox, point.y - oy))
    }

    /**
     * Super 8's colour before its grain, leaks and border, given the blurred
     * picture's channels as [superEightChannels] reads them: lifted blacks and
     * lowered whites by the fade, warm, a little red from the green, 80 %
     * saturation. Metal:380-381.
     */
    fun superEightTone(c: EffectVector, fade: Double = 0.5): EffectVector {
        var out = math.mix(c, c * .88 + .06, fade)
        out *= EffectVector(1.06, 1.0, .92)
        out = EffectVector(out.x + out.y * .025 * fade, out.y, out.z)
        return math.mix(EffectVector(math.luma(out)), out, .8)
    }

    /**
     * Super 8's light leak at a second of the effect's clock: in each six
     * seconds one leak, beginning at a random moment in the first 4.5 and
     * lasting 0.5 to 1.5, from a random side. Metal:382.
     */
    fun superEightLeak(time: Double): LightLeak {
        val cycle = floor(time / 6)
        val phase = time - cycle * 6
        val n = cycle.toLong().toUInt()
        val begin = math.random01(n, 12u, 3u) * 4.5
        val length = .5 + math.random01(n, 4u, 6u)
        val pulse = if (phase >= begin && phase < begin + length) sin((phase - begin) / length * PI) else 0.0
        return LightLeak(pulse, math.random01(n, 7u, 11u) > .5)
    }

    /** The light a leak adds at [uv]: a warm glow falling off from its edge. Metal:382-383. */
    fun superEightLeakLight(uv: Point, leak: LightLeak, leaks: Double = 0.2): EffectVector {
        val edge = if (leak.fromLeft) uv.x else 1 - uv.x
        return EffectVector(1.0, .22, .035) * (exp(-edge * 7) * leak.pulse * leaks)
    }

    // Widescreen 1953. Metal:364-370; parameters ratio, bars, flares, flareColor, ovalBokeh.

    /** The ratios of the catalogue: 2.39, 2.20, 1.85, 1.33. */
    val widescreenRatios: List<Double> = listOf(2.39, 2.20, 1.85, 1.33)

    /** The flare colours: Blue, Amber, Neutral. Linear, as the shader uses them. */
    val flareColors: List<EffectVector> = listOf(EffectVector(.35, .6, 1.0), EffectVector(1.0, .6, .25), EffectVector(1.0))

    /** Whether the pixel at [uv] is under a bar: above and below a picture wider than the ratio's shape, beside one narrower. Metal:364-365. */
    fun widescreenBar(uv: Point, size: Size, ratio: Int = 0): Boolean {
        val r = widescreenRatios[ratio]
        val aspect = size.width / size.height
        return if (aspect < r) abs(uv.y - .5) > .5 * aspect / r else abs(uv.x - .5) > .5 * r / aspect
    }

    /**
     * Widescreen of a colour, given its flares under the pixel
     * ([VideoEffectPasses.widescreen]); under a bar black, or with
     * [blurFill] the blurred picture at 60 %, which [background] holds.
     */
    fun widescreen(
        c: EffectVector,
        flare: EffectVector,
        bar: Boolean,
        flares: Double = 0.4,
        flareColor: Int = 0,
        blurFill: Boolean = false,
        background: EffectVector = EffectVector.ZERO,
    ): EffectVector {
        if (bar) return if (blurFill) background * .6 else EffectVector.ZERO
        return c + flare * flareColors[flareColor] * flares
    }

    // Crash Zoom. Metal:87-94, :361-366; parameters zoom, speed, hold, return, motionBlur, targetX, targetY, trackFace.

    /** How long the overshoot after the push lasts, in seconds. Metal:90. */
    const val CRASH_ZOOM_SETTLE = 0.08

    /**
     * Crash Zoom's move at [time] seconds of its clock: a push to [zoom] over
     * [speed] seconds on t⁵, a 3 % overshoot on a half sine over 0.08, the
     * hold, and with [returns] (or a [release] second set by hand, which then
     * replaces push + 0.08 + hold) a smoothstep back to 1 over [speed]. The
     * velocity is the shader's, which leaves out the zoom amount on the
     * overshoot and the return. Metal:361-364.
     */
    fun crashZoom(time: Double, zoom: Double = 2.5, speed: Double = 0.18, hold: Double = 1.0, returns: Boolean = false, release: Double? = null): CrashZoomState {
        val t = time
        val push = maxOf(.001, speed)
        val back = release ?: (push + CRASH_ZOOM_SETTLE + hold)
        return when {
            t < push -> {
                val f = math.clamp(t / push, 0.0, 1.0)
                CrashZoomState(math.mix(1.0, zoom, f.pow(5)), 5 * f.pow(4) / push)
            }
            t < push + CRASH_ZOOM_SETTLE -> {
                val f = (t - push) / CRASH_ZOOM_SETTLE
                CrashZoomState(zoom * (1 + .03 * sin(f * PI)), .03 * PI / CRASH_ZOOM_SETTLE * kotlin.math.cos(f * PI))
            }
            (returns || release != null) && t > back -> {
                val f = math.clamp((t - back) / push, 0.0, 1.0)
                CrashZoomState(math.mix(zoom, 1.0, f * f * (3 - 2 * f)), -6 * f * (1 - f) / push)
            }
            else -> CrashZoomState(zoom, 0.0)
        }
    }

    /** Where the pixel at [uv] reads the picture under a magnification of [zoom] about [target]. Metal:93. */
    fun crashZoomPoint(uv: Point, target: Point, zoom: Double): Point =
        Point((uv.x - target.x) / zoom + target.x, (uv.y - target.y) / zoom + target.y)

    /** How far the radial blur reaches, as a fraction of the distance from the target: at most 0.12. Metal:365. */
    fun crashZoomBlur(velocity: Double, motionBlur: Double = 0.6): Double = minOf(.12, abs(velocity) * .002 * motionBlur)

    /** The eight points the radial blur averages, along the line from the target through [point], evenly from −½ to +½ of [blur]. Metal:365-366. */
    fun crashZoomTaps(point: Point, target: Point, blur: Double): List<Point> = (0 until 8).map { i ->
        val k = blur * (i / 7.0 - .5)
        Point(point.x + (point.x - target.x) * k, point.y + (point.y - target.y) * k)
    }

    /** The kinds whose shader branch [pixel] holds. */
    val referenced: Set<VideoEffectID> = setOf(
        VideoEffectID.Halation, VideoEffectID.SixteenMM, VideoEffectID.SuperEight, VideoEffectID.Widescreen, VideoEffectID.CrashZoom,
    )

    /** The auxiliary pictures an effect's pass reads, drawn from its input as the original draws them. Renderer:459-482. */
    fun auxiliary(u: EffectUniforms, input: EffectImage, lite: Boolean = false): EffectAuxiliary = when (u.effect) {
        VideoEffectID.Halation -> VideoEffectPasses.halation(input, lite)
        VideoEffectID.Widescreen -> VideoEffectPasses.widescreen(input, u.number("bars") > .5, lite)
        VideoEffectID.SixteenMM -> VideoEffectPasses.sixteenMM(input, u.number("softness"), lite)
        VideoEffectID.SuperEight -> VideoEffectPasses.superEight(input, lite)
        else -> EffectAuxiliary()
    }

    /**
     * One pixel of one effect's pass, as `ve_effect` writes it (Metal:304-418),
     * from the block [u] of [EffectFrame.of] and the pictures [auxiliary]
     * drew. The grain is the frame's lattice under the pixel. Widescreen's oval
     * bokeh needs depth, which no frame has here: it is left out, as the
     * original does without depth (Metal:366, `u.flags&16`).
     */
    fun pixel(u: EffectUniforms, input: EffectImage, auxiliary: EffectAuxiliary, x: Int, y: Int): EffectVector {
        require(u.effect in referenced) { "No reference formula for ${u.effect}" }
        val size = input.size
        val uv = input.uv(x, y)
        fun p(index: Int) = u.p[index].x
        val original = input.sample(uv)
        val c = original
        fun grain(out: EffectVector, strength: Double, grainSize: Double, mono: Boolean): EffectVector {
            val n = VideoEffectGrain.sampled(x, y, input.width, input.height, u.frame, grainSize)
            return if (mono) VideoEffectGrain.apply(out, n.w, strength) else VideoEffectGrain.apply(out, n.xyz, strength)
        }
        val out = when (u.effect) {
            VideoEffectID.CrashZoom -> {
                val release = if (u.flags and EffectUniforms.FLAG_RELEASE != 0) p(39) else null
                val state = crashZoom(u.time, p(0), p(1), p(2), p(3) > .5, release)
                val target = Point(p(5), p(6))
                val point = crashZoomPoint(uv, target, state.zoom)
                var sum = EffectVector.ZERO
                for (tap in crashZoomTaps(point, target, crashZoomBlur(state.velocity, p(4)))) sum += input.sample(tap) / 8.0
                sum
            }
            VideoEffectID.Widescreen -> widescreen(
                c, auxiliary.aux!!.sample(uv), widescreenBar(uv, size, p(0).toInt()), p(2), p(3).toInt(), p(1) > .5,
                auxiliary.background?.sample(uv) ?: EffectVector.ZERO,
            )
            VideoEffectID.Halation -> halation(
                c, auxiliary.aux!!.sample(uv), auxiliary.background!!.sample(uv), auxiliary.extra!!.sample(uv), p(0), p(1), p(2), p(3).toInt(),
            )
            VideoEffectID.SixteenMM -> {
                val point = sixteenMMPoint(uv, size, u.frame, p(3))
                val soft = auxiliary.aux ?: input
                val mono = p(5) > .5
                var out = grain(sixteenMMTone(input.sample(point), soft.sample(point), p(2), mono), p(0), sixteenMMGrainSizes[p(1).toInt()], mono)
                if (p(4) > .5) out *= gate(uv, SIXTEEN_MM_GATE)
                out
            }
            else -> {
                val point = superEightPoint(uv, size, u.frame, p(2))
                val at = superEightChannels(uv, size, point)
                val soft = auxiliary.aux!!
                val read = EffectVector(soft.sample(at.red).x, soft.sample(at.green).y, soft.sample(at.blue).z)
                var out = grain(superEightTone(read, p(0)), p(1), SUPER_EIGHT_GRAIN_SIZE, false)
                out += superEightLeakLight(uv, superEightLeak(u.time), p(4))
                if (p(5) > .5) out *= gate(uv, SUPER_EIGHT_GATE)
                out
            }
        }
        return VideoEffectFormulas.mixed(original, out, u.intensity)
    }

    /** A whole pass: its auxiliary pictures, then [pixel] for every pixel of [input]. */
    fun pass(u: EffectUniforms, input: EffectImage, lite: Boolean = false): EffectImage {
        val auxiliary = auxiliary(u, input, lite)
        return EffectImage.of(input.width, input.height) { x, y -> pixel(u, input, auxiliary, x, y) }
    }
}

/**
 * Which source frame a cadence effect shows: Super 8 at 18 or 24 frames a
 * second, Undercranking at 12, 16 or 18. The phase is integrated over source
 * time, at the frame rate over the playback rate of each stretch, so animated
 * cadence and speed never rewind the shown frame; the slower active cadence
 * wins; a stretch with no active cadence starts the next on a whole frame.
 * YouRec/VideoEffects/VideoEffectMath.swift:181-219.
 *
 * [rates] are the clip's playback rates over source stretches; 1 elsewhere.
 */
class VideoEffectCadenceClock(effects: List<VideoEffectSettings>, rates: List<Rate> = emptyList()) {
    data class Rate(val start: Double, val end: Double, val rate: Double)

    /** A held frame: its number on the cadence, and the source second it begins at, the time shown. */
    data class Hold(val index: Int, val boundary: Double)

    private class Segment(val start: Double, val end: Double, val phase: Double, val frequency: Double, val groupStart: Double) {
        val endPhase: Double get() = phase + (end - start) * frequency
    }

    private val segments: List<Segment>

    init {
        val cadenced = effects.filter { it.enabled && (it.kind == VideoEffectID.SuperEight || it.kind == VideoEffectID.Undercranking) }
        val points = (
            cadenced.flatMap { e -> listOf(e.start, e.end) + e.parameters.filter { it.id == "fps" || it.id == "intensity" }.flatMap { p -> p.keyframes.map { it.time } } } +
                rates.flatMap { listOf(it.start, it.end) }
            ).toSet().sorted()
        val values = mutableListOf<Segment>()
        var phase = 0.0
        var group = 0.0
        var active = false
        for ((a, b) in points.zipWithNext()) {
            if (b <= a) continue
            val middle = (a + b) / 2
            val fps = cadenced.filter { it.start <= middle && it.end > middle && (!it.kind.descriptor.supportsIntensity || it.number("intensity", middle) > 0) }
                .minOfOrNull { it.number("fps", middle) } ?: 0.0
            if (!(fps > 0)) {
                active = false
                continue
            }
            if (!active) {
                phase = ceil(phase - 0.000001)
                group = a
            }
            active = true
            val rate = rates.firstOrNull { it.start <= middle && it.end > middle }?.rate ?: 1.0
            val segment = Segment(a, b, phase, fps / maxOf(0.001, rate), group)
            values += segment
            phase = segment.endPhase
        }
        segments = values
    }

    /** The frame shown at a source second, or null where no cadence is active. */
    fun hold(time: Double): Hold? {
        var a = 0
        var b = segments.size
        while (a < b) {
            val m = (a + b) / 2
            if (segments[m].start <= time) a = m + 1 else b = m
        }
        if (a == 0) return null
        val current = segments[a - 1]
        if (!(time < current.end)) return null
        val index = floor(current.phase + (time - current.start) * current.frequency + 0.000001)
        a = 0
        b = segments.size
        while (a < b) {
            val m = (a + b) / 2
            if (segments[m].endPhase < index - 0.000001) a = m + 1 else b = m
        }
        val source = segments[minOf(a, segments.size - 1)]
        return Hold(index.toInt(), maxOf(current.groupStart, source.start + (index - source.phase) / source.frequency))
    }

    /**
     * The source second shown at [time], on footage of [fps]: the first
     * frame at or after the held boundary, as capture takes it; [time]
     * itself where no cadence is active.
     */
    fun shownTime(time: Double, fps: Double): Double = hold(time)?.let { VideoEffectTime.firstFrame(it.boundary, fps) } ?: time
}
