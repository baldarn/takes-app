package app.takes.core

import kotlin.math.exp
import kotlin.math.pow

/*
 * Night fix in its Fast mode: the single-frame passes the original runs on
 * the raw frame before crop, masks and grading, `nf_adjust`, `nf_chroma`
 * across and down, and `nf_mix`, from YouRec/VideoEffects/VideoEffects.metal:640-727
 * (cited as `Metal`) as YouRec/VideoEffects/NightFix.swift:206-217 and
 * :339-351 (`NightFix`) run them. In Fast mode the fused picture is the raw
 * frame itself, so these passes are the whole effect. The motion-aligned
 * fusion of the other modes is not here.
 *
 * The pictures hold BT.709-encoded values (the original's extended 709
 * working space), not linear light: the passes decode to linear where they
 * say so.
 */
object VideoEffectNightFix {
    /** The BT.709 encoding of linear light. Metal:644. */
    fun encode(x: Double): Double = maxOf(x, 0.0).let { if (it < 0.018) 4.5 * it else 1.099 * it.pow(0.45) - 0.099 }

    /** Its inverse. Metal:645. */
    fun decode(x: Double): Double = maxOf(x, 0.0).let { if (it < 0.081) it / 4.5 else ((it + 0.099) / 1.099).pow(1.0 / 0.45) }

    fun encode(c: EffectVector): EffectVector = c.map(::encode)
    fun decode(c: EffectVector): EffectVector = c.map(::decode)

    /** BT.709 luma of what it is given. Metal:646. */
    fun y(c: EffectVector): Double = c.dot(EffectColorMath.LUMA)

    /**
     * `nf_adjust` with the fused picture the raw one: a local Wiener
     * shrink of each pixel towards the mean of its 3 × 3 neighbourhood
     * (taps 0.85 pixels apart, wider above a reduction of 1), keeping what
     * stands out of the noise expected at [reduction], more so with
     * [detail]; then in linear light the shadows lifted by [brightness]
     * stops, white held, `gain / (1 + (gain − 1) · y)`. Metal:682-706.
     */
    fun adjust(raw: EffectImage, reduction: Double = 1.0, brightness: Double = 2.5, detail: Double = 0.5): EffectImage {
        val amount = reduction
        val reach = 0.85 + maxOf(0.0, amount - 1) * 1.3
        val mixing = minOf(1.0, amount)
        val noise2 = (6.0 / 255).pow(2) / 3 * amount * amount * VideoEffectShaderMath.mix(1.5, 0.2, detail)
        val gain = 2.0.pow(brightness)
        return EffectImage.of(raw.width, raw.height) { x, y ->
            val uv = raw.uv(x, y)
            var c = raw.sample(uv)
            var mean = EffectVector.ZERO
            var square = EffectVector.ZERO
            for (j in -1..1) for (i in -1..1) {
                val n = raw.sample(Point(uv.x + i * reach / raw.width, uv.y + j * reach / raw.height))
                mean += n / 9.0
                square += n * n / 9.0
            }
            val variance = (square - mean * mean).map { maxOf(it, 1e-8) }
            val keep = variance.map { (1 - noise2 / it).coerceIn(0.0, 1.0) }
            c = decode(VideoEffectShaderMath.mix(c, mean + keep * (c - mean), mixing))
            val luma = maxOf(0.0, y(c))
            val scale = if (luma <= 1) gain / (1 + (gain - 1) * luma) else 1.0
            encode((c * scale).map { maxOf(it, 0.0) })
        }
    }

    /**
     * `nf_chroma`, one pass across ([horizontal]) or down: the colour
     * differences R − Y and B − Y averaged over `clamp(10 · reduction, 1, 30)`
     * pixels each side, weighted by how close each tap's luma is and by its
     * distance, and mixed in by `min(0.98, 0.85 · reduction)`; luma is kept,
     * green rebuilt from it. Metal:707-719.
     */
    fun chroma(input: EffectImage, reduction: Double, horizontal: Boolean): EffectImage {
        val radius = (10.0 * reduction).coerceIn(1.0, 30.0).toInt()
        val dx = if (horizontal) 1.0 else 0.0
        val dy = if (horizontal) 0.0 else 1.0
        val blend = minOf(.98, .85 * reduction)
        return EffectImage.of(input.width, input.height) { x, y ->
            val uv = input.uv(x, y)
            val c = input.sample(uv)
            val luma = y(c)
            var r = 0.0
            var b = 0.0
            var total = 0.0
            for (k in -radius..radius) {
                val n = input.sample(Point(uv.x + k * dx / input.width, uv.y + k * dy / input.height))
                val ny = y(n)
                val weight = exp(-(ny - luma).pow(2) / (0.0012 * maxOf(1.0, reduction))) * exp(-0.5 * (k / maxOf(1.0, radius * 0.65)).pow(2))
                r += (n.x - ny) * weight
                b += (n.z - ny) * weight
                total += weight
            }
            val rr = VideoEffectShaderMath.mix(c.x - luma, r / maxOf(total, 1e-6), blend)
            val bb = VideoEffectShaderMath.mix(c.z - luma, b / maxOf(total, 1e-6), blend)
            val red = luma + rr
            val blue = luma + bb
            EffectVector(red, (luma - 0.2126 * red - 0.0722 * blue) / 0.7152, blue).map { maxOf(0.0, it) }
        }
    }

    /** `nf_mix`: the result back over the raw frame by [mix], in linear light. Metal:720-723. */
    fun mixed(raw: EffectImage, restored: EffectImage, mix: Double): EffectImage = EffectImage.of(raw.width, raw.height) { x, y ->
        encode(VideoEffectShaderMath.mix(decode(raw[x, y]), decode(restored[x, y]), mix))
    }

    /**
     * Night fix Fast of a raw frame at one second of [effect]: adjust, the
     * chroma cleanup across then down, the mix by intensity; the frame as it
     * is at no intensity, or with neither reduction nor brightness.
     * NightFix:206-217.
     */
    fun fast(raw: EffectImage, effect: VideoEffectSettings, time: Double): EffectImage {
        val mix = effect.number("intensity", time)
        val amount = effect.number("reduction", time)
        val stops = effect.number("brightness", time)
        if (mix <= 0 || amount <= 0 && stops == 0.0) return raw
        val first = adjust(raw, amount, stops, effect.number("detail", time))
        return mixed(raw, chroma(chroma(first, amount, true), amount, false), mix)
    }
}
