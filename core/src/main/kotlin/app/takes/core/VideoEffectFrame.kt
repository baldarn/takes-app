package app.takes.core

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/*
 * What the effects of a clip come to on one frame: which are on, in which
 * order they are drawn, and the numbers each one's shader receives. Ported
 * from YouRec/VideoEffects/MetalVideoEffects.swift (cited as `Renderer`): the
 * choice and order of the effects from `render` (:559-572, :592-593), the
 * per-effect clock from :627-638, the packing from `uniforms` (:268-291).
 */

/** The frame arithmetic of the effects. YouRec/VideoEffects/VideoEffectMath.swift:163-179. */
object VideoEffectTime {
    /** Fractional cadence: 18fps on a 30fps output must remain 18fps, not 15. */
    fun heldTime(presentationTime: Double, fps: Double): Double = floor(maxOf(0.0, presentationTime) * fps + 0.000001) / maxOf(1.0, fps)

    /** The whole frame a second falls in, at [fps]: the seed of the grain and the other per-frame terms. */
    fun frameIndex(time: Double, fps: Double): Int = maxOf(0, floor(maxOf(0.0, time) * fps + 0.000001).toInt())

    /**
     * Capture selects the first available frame on/after a cadence boundary.
     * Choosing the preceding frame on export produces a different motion phase.
     */
    fun firstFrame(atOrAfter: Double, fps: Double): Double = ceil(maxOf(0.0, atOrAfter) * maxOf(1.0, fps) - 0.0001) / maxOf(1.0, fps)

    /** A second on the 600-tick clock the original's recorder writes. */
    fun captureTime(time: Double): Double = floor(maxOf(0.0, time) * 600 + 0.5) / 600

    fun cubic(time: Double): Double {
        val t = minOf(1.0, maxOf(0.0, time))
        return if (t < 0.5) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2
    }

    fun dollyTarget(zoom: Double, referenceHeight: Double, observedHeight: Double, range: ClosedFloatingPointRange<Double>): Double {
        if (!(observedHeight > 0.00001 && observedHeight.isFinite())) return zoom
        return minOf(range.endInclusive, maxOf(range.start, zoom * referenceHeight / observedHeight))
    }
}

/** Four numbers of the block a shader receives: `float4` in the original's `EffectUniforms`. */
data class EffectUniformVector(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0, val w: Double = 0.0) {
    val xyz: EffectVector get() = EffectVector(x, y, z)
}

/**
 * What a frame brings to an effect besides its settings: the things the
 * original computes from the footage. None exists on Android yet; each sets
 * one bit of [EffectUniforms.flags], and the palette fills its own slots.
 * Renderer:13-30, :270.
 */
data class EffectFrameInputs(
    val personMask: Boolean = false,
    val cleanPlate: Boolean = false,
    val depthMap: Boolean = false,
    val palette: EffectPalette? = null,
    /**
     * Where the subject a Dolly Zoom or a Crash Zoom following a face moves to is on the frame, 0…1 from the
     * top left, where the analysis followed it; null to use the target set by hand. VideoEffectAssets.swift:476-486.
     */
    val target: Point? = null,
)

/**
 * The block one effect's shader receives for one frame, field for field the
 * original's `EffectUniforms` (YouRec/VideoEffects/VideoEffects.metal:5-11),
 * with every parameter resolved at the frame's source second.
 *
 * [p] has 40 slots. Slot `i` holds the `i`-th parameter of the effect's
 * catalogue entry, "intensity" not counted, in the catalogue's order: a number
 * as it is, a toggle as 1 or 0, a choice as its index among the catalogue's
 * options, all in `x`; a colour as linear RGB in `x`, `y`, `z` with `w` 1; a
 * file leaves its slot at zero. Slot 39 `x` is the release second of a
 * captured Crash Zoom on the effect's own clock, when [flags] has
 * [FLAG_RELEASE]. Slot 38, the picture size for the original's geometry maps
 * of masks, is not filled.
 *
 * [kind] is the number the shader switches on, [VideoEffectID.shaderIndex].
 * [frame] seeds the per-frame terms (grain, weave). [time] is seconds since
 * the effect began, on its own clock; [duration] its length; [fps] the
 * movie's frame rate. [intensity] mixes the effect's result with its input,
 * and is 1 for the effects that have none. [count] is the number of palette
 * colours; [palette] holds them in Oklab and [thresholds] the luma steps
 * between them.
 */
data class EffectUniforms(
    val id: String,
    val effect: VideoEffectID,
    val frame: Int,
    val flags: Int,
    val count: Int,
    val time: Double,
    val intensity: Double,
    val duration: Double,
    val fps: Double,
    val p: List<EffectUniformVector>,
    val palette: List<EffectVector> = emptyList(),
    val thresholds: List<Double> = emptyList(),
) {
    val kind: Int get() = effect.shaderIndex

    /** The `x` of the slot of the catalogue parameter [id]; zero for a parameter the effect does not have. */
    fun number(id: String): Double = effect.descriptor.parameters.indexOfFirst { it.id == id }.let { if (it < 0) 0.0 else p[it].x }

    /** The slot of the catalogue parameter [id]: for a colour, its linear RGB. */
    fun vector(id: String): EffectUniformVector = effect.descriptor.parameters.indexOfFirst { it.id == id }.let { if (it < 0) EffectUniformVector() else p[it] }

    /**
     * The 60 vectors after the eight header words, as the original lays them
     * out and as single-precision numbers: `p[40]`, `palette[16]` with `w` 1
     * on the colours present, `thresholds[4]` holding up to fifteen steps.
     * 240 numbers. Renderer:272-291.
     */
    fun vectors(): FloatArray {
        val result = FloatArray((SLOTS + 16 + 4) * 4)
        p.forEachIndexed { i, v ->
            result[i * 4] = v.x.toFloat()
            result[i * 4 + 1] = v.y.toFloat()
            result[i * 4 + 2] = v.z.toFloat()
            result[i * 4 + 3] = v.w.toFloat()
        }
        palette.take(16).forEachIndexed { i, c ->
            result[(SLOTS + i) * 4] = c.x.toFloat()
            result[(SLOTS + i) * 4 + 1] = c.y.toFloat()
            result[(SLOTS + i) * 4 + 2] = c.z.toFloat()
            result[(SLOTS + i) * 4 + 3] = 1f
        }
        thresholds.take(15).forEachIndexed { i, t -> result[(SLOTS + 16) * 4 + i] = t.toFloat() }
        return result
    }

    companion object {
        /** The number of parameter slots. */
        const val SLOTS = 40

        /** Bits of [flags]. Renderer:270. */
        const val FLAG_PERSON_MASK = 1
        const val FLAG_CLEAN_PLATE = 2

        /** A captured Crash Zoom was released by hand: slot 39 holds when. */
        const val FLAG_RELEASE = 4

        /** The recipe asks for the temporal matte, [VideoEffectSettings.algorithm] 2. */
        const val FLAG_TEMPORAL_MATTE = 8
        const val FLAG_DEPTH = 16
    }
}

/** A uniform scale about a point followed by nothing else: all the original's Crash Zoom does to a later effect's target. */
private data class TargetTransform(val scale: Double = 1.0, val x: Double = 0.0, val y: Double = 0.0) {
    fun applied(px: Double, py: Double): Pair<Double, Double> = Pair(scale * px + x, scale * py + y)

    /** This, then [next]. */
    fun then(next: TargetTransform) = TargetTransform(next.scale * scale, next.scale * x + next.x, next.scale * y + next.y)
}

object EffectFrame {
    /**
     * The order in which the families are drawn. It is the fixed order of
     * [VideoEffectSettings.ordered] except for Time, which is drawn after the
     * camera effects and not last: "Sequence sampling is handled by the
     * timeline. Its pixel decorations precede Frame so a held push-in or weave
     * never shifts the clean bars." Renderer:592.
     */
    val stages: List<EffectFamily> = listOf(
        EffectFamily.Key, EffectFamily.Camera, EffectFamily.Time, EffectFamily.Color,
        EffectFamily.Quantization, EffectFamily.Texture, EffectFamily.Frame,
    )

    /**
     * The effects this chain never draws: Night fix restores the raw frame
     * before colour and framing, and Step Printing blends source frames in the
     * compositor. YouRec/VideoToolCompositor.swift:324-325.
     */
    val outsideTheChain: Set<VideoEffectID> = setOf(VideoEffectID.NightFix, VideoEffectID.StepPrinting)

    /**
     * The effects of a clip that are drawn on one frame, in the order they are
     * drawn, each with the block its shader receives.
     *
     * [effects] is the clip's list as saved. [sourceTime] is the source second
     * of the frame; on a held frame, the second that is held. [movieFrame] is
     * the frame's number in the movie, which seeds the grain: the original
     * takes [VideoEffectTime.frameIndex] of the source second, or of the
     * movie's second on a held frame. [frameRate] is the movie's. [holdTime]
     * is how long a held frame has been held, and replaces the clock of a
     * Negative Strobe and of a Freeze Frame there. [inputs] is what the frame
     * brings for an effect, by the effect's id.
     *
     * An effect is drawn when it is enabled, the second is inside its range,
     * and its intensity, if it has one, is above zero. A Negative Strobe is
     * drawn only on its negative frames, where it inverts the picture; an RGB
     * Offset only while some channel is off its place.
     */
    fun of(
        effects: List<VideoEffectSettings>,
        sourceTime: Double,
        movieFrame: Int,
        frameRate: Double = 30.0,
        holdTime: Double? = null,
        inputs: Map<String, EffectFrameInputs> = emptyMap(),
    ): List<EffectUniforms> {
        val enabled = VideoEffectSettings.ordered(effects.filterNot { it.kind in outsideTheChain }).filter { effect ->
            if (!(effect.enabled && sourceTime >= effect.start && sourceTime < effect.end)) return@filter false
            when (effect.kind) {
                VideoEffectID.NegativeStrobe -> effect.strobeShowsNegative(sourceTime, frameRate, holdTime)
                VideoEffectID.RgbOffset -> RGBChannel.entries.any { effect.number(it.x, sourceTime) != 0.0 || effect.number(it.y, sourceTime) != 0.0 }
                else -> true
            }
        }
        val active = enabled.filter { !it.kind.descriptor.supportsIntensity || it.number("intensity", sourceTime) > 0 }
        val pixelOrder = stages.flatMap { stage -> active.filter { it.kind.descriptor.family == stage } }
        var targetTransform = TargetTransform()
        return pixelOrder.map { settings ->
            val given = inputs[settings.id] ?: EffectFrameInputs()
            // A camera move's target is where the picture's point is after the moves drawn before it.
            val target = if (settings.kind == VideoEffectID.DollyZoom || settings.kind == VideoEffectID.CrashZoom) {
                val followed = given.target
                targetTransform.applied(followed?.x ?: settings.number("targetX", sourceTime), followed?.y ?: settings.number("targetY", sourceTime))
            } else {
                null
            }
            val offset = settings.phaseOffset ?: 0.0
            val time = (if (settings.kind == VideoEffectID.FreezeFrame) holdTime else null) ?: maxOf(0.0, sourceTime - settings.start + offset)
            val p = MutableList(EffectUniforms.SLOTS) { EffectUniformVector() }
            settings.kind.descriptor.parameters.forEachIndexed { index, definition ->
                if (target != null && (definition.id == "targetX" || definition.id == "targetY")) {
                    p[index] = EffectUniformVector(x = if (definition.id == "targetX") target.first else target.second)
                    return@forEachIndexed
                }
                when (val value = settings.value(definition.id, sourceTime) ?: definition.value) {
                    is EffectValue.Number -> p[index] = EffectUniformVector(x = value.value)
                    is EffectValue.Toggle -> p[index] = EffectUniformVector(x = if (value.value) 1.0 else 0.0)
                    is EffectValue.Choice -> (definition.kind as? EffectParameterKind.Picker)?.let { picker ->
                        p[index] = EffectUniformVector(x = maxOf(0, picker.options.indexOf(value.value)).toDouble())
                    }
                    is EffectValue.Color -> EffectColorMath.linear(value.value).let { p[index] = EffectUniformVector(it.x, it.y, it.z, 1.0) }
                    is EffectValue.Media -> Unit
                }
            }
            settings.releaseTime?.let { p[39] = EffectUniformVector(x = maxOf(0.0, it - settings.start + offset)) }
            if (settings.kind == VideoEffectID.CrashZoom && target != null) {
                targetTransform = targetTransform.then(crashZoom(settings, sourceTime, target))
            }
            EffectUniforms(
                id = settings.id,
                effect = settings.kind,
                frame = movieFrame,
                flags = (if (given.personMask) EffectUniforms.FLAG_PERSON_MASK else 0) or
                    (if (given.cleanPlate) EffectUniforms.FLAG_CLEAN_PLATE else 0) or
                    (if (settings.releaseTime != null) EffectUniforms.FLAG_RELEASE else 0) or
                    (if (settings.usesTemporalMatte) EffectUniforms.FLAG_TEMPORAL_MATTE else 0) or
                    (if (given.depthMap) EffectUniforms.FLAG_DEPTH else 0),
                count = given.palette?.colors?.size ?: 0,
                time = time,
                intensity = if (settings.kind.descriptor.supportsIntensity) settings.number("intensity", sourceTime) else 1.0,
                duration = settings.end - settings.start,
                fps = frameRate,
                p = p,
                palette = given.palette?.colors.orEmpty().take(16).map { EffectColorMath.lab(EffectColorMath.linear(it)) },
                thresholds = given.palette?.thresholds.orEmpty().take(15),
            )
        }
    }

    /**
     * How much a Crash Zoom magnifies at a source second, and so where it
     * moves the points of the picture: the forward form of the shader's
     * sampling, shared with target picking in the original.
     * YouRec/VideoEffects/VideoEffectMath.swift:224-247.
     */
    fun crashZoomScale(effect: VideoEffectSettings, time: Double): Double {
        val offset = effect.phaseOffset ?: 0.0
        val t = maxOf(0.0, time - effect.start + offset)
        val push = effect.number("speed", time)
        val amount = effect.number("zoom", time)
        val release = effect.releaseTime?.let { it - effect.start + offset } ?: (push + 0.08 + effect.number("hold", time))
        return when {
            t < push -> 1 + (amount - 1) * (t / push).pow(5)
            t < push + 0.08 -> amount * (1 + 0.03 * sin((t - push) / 0.08 * PI))
            (effect.releaseTime != null || effect.toggle("return", time)) && t > release -> {
                val f = minOf(1.0, maxOf(0.0, (t - release) / push))
                amount + (1 - amount) * f * f * (3 - 2 * f)
            }
            else -> amount
        }
    }

    private fun crashZoom(effect: VideoEffectSettings, time: Double, target: Pair<Double, Double>): TargetTransform {
        val zoom = crashZoomScale(effect, time)
        return TargetTransform(zoom, target.first * (1 - zoom), target.second * (1 - zoom))
    }
}
