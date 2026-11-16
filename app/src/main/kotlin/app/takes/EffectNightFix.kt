@file:OptIn(UnstableApi::class)

package app.takes

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import app.takes.core.TimelineClip
import app.takes.core.VideoEffectID
import app.takes.core.VideoEffectSettings
import kotlin.math.pow

/**
 * Night fix in its Fast mode on [clip]'s raw frames in an export, before
 * their color and framing, as the original restores them
 * (`VideoToolCompositor.swift:324`): the single-frame passes of
 * `VideoEffectNightFix.fast`, adjust, the chroma cleanup across then down,
 * and the mix by intensity. The motion-aligned Medium and Precise modes need
 * optical flow, which Android does not have here: a recipe asking for them
 * is drawn Fast. [begins] is where the clip starts on its sequence.
 */
class NightFixEffect(private val clip: TimelineClip, private val begins: Double) : GlEffect {
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = clip.videoEffects.orEmpty().none { it.kind == VideoEffectID.NightFix && it.enabled }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program()

    private inner class Program : BaseGlShaderProgram(/* useHighPrecisionColorComponents = */ false, /* texturePoolCapacity = */ 1) {
        private var size = Size(0, 0)
        private val gpu = EffectGpu()

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            size = Size(inputWidth, inputHeight)
            return size
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            val bound = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, bound, 0)
            val raw = EffectSource(inputTexId, size.width, size.height, encoded = false)
            val sourceTime = clip.sourceTime((presentationTimeUs / 1e6 - begins).coerceIn(0.0, clip.duration))
            val effect = NightFixPasses.active(clip.videoEffects, sourceTime)
            val mix = effect?.number("intensity", sourceTime) ?: 0.0
            val amount = effect?.number("reduction", sourceTime) ?: 0.0
            val stops = effect?.number("brightness", sourceTime) ?: 0.0
            if (effect == null || mix <= 0 || amount <= 0 && stops == 0.0) {
                // The frame as it is.
                return gpu.drawInto(EffectPassShaders.NIGHT_MIX, raw, bound[0], size.width, size.height, { p ->
                    p.setSamplerTexIdUniform("uRestored", inputTexId, 1)
                    p.setFloatsUniform("uMix", floatArrayOf(0f))
                })
            }
            val adjusted = gpu.draw(EffectPassShaders.NIGHT_ADJUST, raw, "adjusted", size.width, size.height, { p ->
                p.setFloatsUniform("uFix", NightFixPasses.adjust(amount, stops, effect.number("detail", sourceTime)))
            })
            fun chroma(from: EffectTarget, key: String, horizontal: Boolean) = gpu.draw(EffectPassShaders.NIGHT_CHROMA, EffectSource(from), key, size.width, size.height, { p ->
                p.setFloatsUniform("uDir", if (horizontal) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f))
                p.setFloatsUniform("uChroma", NightFixPasses.chroma(amount))
            })
            val restored = chroma(chroma(adjusted, "across", true), "down", false)
            gpu.drawInto(EffectPassShaders.NIGHT_MIX, raw, bound[0], size.width, size.height, { p ->
                p.setSamplerTexIdUniform("uRestored", restored.texture, 1)
                p.setFloatsUniform("uMix", floatArrayOf(mix.toFloat()))
            })
        }

        override fun release() {
            super.release()
            gpu.release()
        }
    }
}

/** The numbers of Night fix Fast's passes, as `VideoEffectNightFix` works them out. */
object NightFixPasses {
    /** The Night fix drawn at a source second: the first enabled one whose range holds it. */
    fun active(effects: List<VideoEffectSettings>?, sourceTime: Double): VideoEffectSettings? =
        effects.orEmpty().firstOrNull { it.kind == VideoEffectID.NightFix && it.enabled && sourceTime >= it.start && sourceTime < it.end }

    /** `uFix` of the adjust pass: the reach of its taps, its mixing, the noise it expects and the gain. VideoEffectNightFix.adjust. */
    fun adjust(reduction: Double, brightness: Double, detail: Double): FloatArray {
        val reach = 0.85 + maxOf(0.0, reduction - 1) * 1.3
        val noise2 = (6.0 / 255).pow(2) / 3 * reduction * reduction * (1.5 + (0.2 - 1.5) * detail)
        return floatArrayOf(reach.toFloat(), minOf(1.0, reduction).toFloat(), noise2.toFloat(), 2.0.pow(brightness).toFloat())
    }

    /** `uChroma` of the chroma passes: the radius, the blend and the reduction. VideoEffectNightFix.chroma. */
    fun chroma(reduction: Double): FloatArray =
        floatArrayOf((10.0 * reduction).coerceIn(1.0, 30.0).toInt().toFloat(), minOf(.98, .85 * reduction).toFloat(), reduction.toFloat())
}
