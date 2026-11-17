@file:OptIn(UnstableApi::class)

package app.takes

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import app.takes.core.ColorGrade
import app.takes.core.ColorSettings
import app.takes.core.LUTInput
import app.takes.core.LocalRecovery
import app.takes.core.LogColor

/**
 * The grade of a log recording with its local highlight recovery, in the export, as the original draws it
 * (`YouRec/LogColor.swift:129-180`, at `YouRec/VideoToolCompositor.swift:513`): the log luminance of the balanced
 * light, its mean and mean square over a box of the frame, the guided filter's coefficients, their mean over the same
 * box, and each pixel's gain ([LocalRecovery]). The gain is turned into the code values that give the same light
 * ([LocalRecovery.recode]) and the grade's table ([ColorLuts.table]) is looked up on those, so that the rest of the
 * grade is the table every log clip has.
 *
 * The moments and the coefficients are kept in half floats, eight bits being too coarse for a variance of 0.12 in
 * log luminance. A neighbourhood of more than 15 pixels is averaged on blocks of the frame ([LocalRecovery.block])
 * with the box's edges weighed by how much of a block they cover, and the coefficients are taken back to each pixel
 * by the GPU's filtering: the same neighbourhood as the original's to a fraction of a block, at a cost that does not
 * grow with the frame. Up to 15 pixels the box is exact.
 *
 * A grade that moves between two ends ([MovingColorLut]) has one end in [ends] each, their light mixed by [fraction]
 * as there, the filter run for each end with its own balance.
 */
class RecoveredColorLut(
    private val ends: List<End>,
    private val fraction: (Long) -> Double = { 0.0 },
) : GlEffect {
    /** One end of the grade: its table, its settings and the recording's log profile. */
    class End(val table: FloatArray, val settings: ColorSettings, val profile: LUTInput)

    init {
        require(ends.size in 1..2)
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = RecoveryPasses(ends, fraction)
}

private class RecoveryPasses(
    private val ends: List<RecoveredColorLut.End>,
    private val fraction: (Long) -> Double,
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents = */ false, /* texturePoolCapacity = */ 1) {
    private var moments: GlProgram? = null
    private var blur: GlProgram? = null
    private var final: GlProgram? = null
    private var luts = IntArray(0)
    private var width = 0
    private var height = 0
    private var radius = 1
    private var block = 1
    private var blocksWide = 1
    private var blocksHigh = 1

    // For each end, two half-float textures of the frame's blocks and their framebuffers.
    private var textures = IntArray(0)
    private var framebuffers = IntArray(0)

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        if (inputWidth != width || inputHeight != height) {
            releaseTextures()
            width = inputWidth
            height = inputHeight
            radius = LocalRecovery.radius(width, height)
            block = LocalRecovery.block(radius)
            blocksWide = (width + block - 1) / block
            blocksHigh = (height + block - 1) / block
            textures = IntArray(ends.size * 2) { GlUtil.createTexture(blocksWide, blocksHigh, /* useHighPrecisionColorComponents = */ true) }
            framebuffers = IntArray(textures.size) { GlUtil.createFboForTexture(textures[it]) }
            for (fbo in framebuffers) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
                val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) { "Half-float frame buffer not supported ($status)" }
            }
        }
        return Size(inputWidth, inputHeight)
    }

    private fun program(fragment: String) = GlProgram(VERTEX, PRELUDE + fragment).also {
        it.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
    }

    private fun GlProgram.balance(end: RecoveredColorLut.End, suffix: String = "") {
        val m = LogColor.matrix(end.profile)
        // GLSL's matrices are given column by column.
        setFloatsUniform("uMatrix$suffix", FloatArray(9) { m[(it % 3) * 3 + it / 3].toFloat() })
        setFloatsUniform("uGains$suffix", ColorGrade.logGains(end.settings).map { it.toFloat() }.toFloatArray())
        setFloatUniform("uHighlights$suffix", (end.settings.highlights ?: 0.0).coerceIn(-1.0, 1.0).toFloat())
    }

    private fun draw(program: GlProgram) {
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val moments = moments ?: program(MOMENTS).also { moments = it }
        val blur = blur ?: program(BLUR).also { blur = it }
        val final = final ?: program(if (ends.size == 1) FINAL_ONE else FINAL_TWO).also {
            final = it
            luts = IntArray(ends.size) { index -> lutTexture(ends[index].table) }
        }
        val output = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, output, 0)
        val blocks = floatArrayOf(blocksWide.toFloat(), blocksHigh.toFloat())
        ends.forEachIndexed { index, end ->
            if (LocalRecovery.strength(end.settings) == 0.0) return@forEachIndexed
            val one = index * 2
            val two = one + 1
            GlUtil.focusFramebufferUsingCurrentContext(framebuffers[one], blocksWide, blocksHigh)
            moments.use()
            moments.setSamplerTexIdUniform("uTex", inputTexId, 0)
            moments.setFloatsUniform("uSize", floatArrayOf(width.toFloat(), height.toFloat()))
            moments.setFloatUniform("uBlock", block.toFloat())
            moments.balance(end)
            draw(moments)
            // Across and down for the moments, the coefficients made from their means; across and down again for those.
            blur.use()
            blur.setFloatsUniform("uBlocks", blocks)
            blur.setFloatUniform("uBlock", block.toFloat())
            blur.setFloatUniform("uRadius", radius.toFloat())
            for ((step, down) in listOf(false, true, false, true).withIndex()) {
                val from = if (step % 2 == 0) one else two
                val into = if (step % 2 == 0) two else one
                GlUtil.focusFramebufferUsingCurrentContext(framebuffers[into], blocksWide, blocksHigh)
                blur.setSamplerTexIdUniform("uSrc", textures[from], 0)
                blur.setFloatsUniform("uDir", if (down) floatArrayOf(0f, 1f) else floatArrayOf(1f, 0f))
                blur.setFloatUniform("uCoefficients", if (step == 1) 1f else 0f)
                draw(blur)
            }
        }
        GlUtil.focusFramebufferUsingCurrentContext(output[0], width, height)
        final.use()
        final.setSamplerTexIdUniform("uTex", inputTexId, 0)
        final.setFloatsUniform("uScale", floatArrayOf(width / (block * blocksWide).toFloat(), height / (block * blocksHigh).toFloat()))
        ends.forEachIndexed { index, end ->
            val suffix = if (index == 0) "" else "B"
            final.setSamplerTexIdUniform("uLut$suffix", luts[index], 1 + index * 2)
            final.setSamplerTexIdUniform("uCoef$suffix", textures[index * 2], 2 + index * 2)
            final.setFloatUniform("uStrength$suffix", LocalRecovery.strength(end.settings).toFloat())
            final.balance(end, suffix)
        }
        if (ends.size == 2) final.setFloatUniform("uFraction", fraction(presentationTimeUs).toFloat())
        draw(final)
        GlUtil.checkGlError()
    }

    private fun releaseTextures() {
        for (fbo in framebuffers) GlUtil.deleteFbo(fbo)
        for (texture in textures) GlUtil.deleteTexture(texture)
        framebuffers = IntArray(0)
        textures = IntArray(0)
    }

    override fun release() {
        super.release()
        moments?.delete()
        blur?.delete()
        final?.delete()
        for (lut in luts) GlUtil.deleteTexture(lut)
        releaseTextures()
    }

    private companion object {
        const val VERTEX = """
            attribute vec4 aFramePosition;
            varying vec2 vTex;
            void main() { gl_Position = aFramePosition; vTex = aFramePosition.xy * 0.5 + 0.5; }
        """

        // The Apple Log curve, Highlights and its inverse, and the luminance before Highlights, as in LogColor.
        const val PRELUDE = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
        varying vec2 vTex;
        const float LA = 47.28711236;
        const float LB = 0.05641088;
        const float LC = 0.69336945;
        const float LD = 0.08550479;
        const float LE = 0.00964052;
        const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
        const float KNEE = 0.35;
        // Log luminances are kept less this, where the bright ones are, for the precision of half floats.
        const float SHIFT = -1.5;
        float logLight(float x) { return x >= LA * (0.01 + LB) * (0.01 + LB) ? exp2((x - LC) / LD) - LE : sqrt(max(0.0, x) / LA) - LB; }
        float logCode(float x) { float s = max(0.0, x + LB); return x >= 0.01 ? log2(max(x + LE, 0.00000001)) * LD + LC : LA * s * s; }
        vec3 logLight3(vec3 c) { return vec3(logLight(c.r), logLight(c.g), logLight(c.b)); }
        float hlForward(float y, float a) {
            if (a == 0.0 || y <= KNEE) return y;
            float d = y - KNEE;
            if (a < 0.0) { float s = -a * 6.0; return KNEE + log(1.0 + s * d) / s; }
            return KNEE + d * (1.0 + 2.0 * a * d / (d + KNEE));
        }
        float hlInverse(float m, float a) {
            if (a == 0.0 || m <= KNEE) return m;
            float d = m - KNEE;
            if (a < 0.0) { float s = -a * 6.0; return KNEE + (exp(s * d) - 1.0) / s; }
            float q = 1.0 + 2.0 * a;
            return KNEE + (d - KNEE + sqrt((KNEE - d) * (KNEE - d) + 4.0 * q * d * KNEE)) / (2.0 * q);
        }
        float before(vec3 light, mat3 m, vec3 gains) { return max(0.0, dot(max(m * light, 0.0) * gains, LUMA)); }
        """

        // The block's mean log luminance of the balanced light, less SHIFT, and its mean square.
        val MOMENTS = """
        uniform sampler2D uTex;
        uniform vec2 uSize;
        uniform float uBlock;
        uniform mat3 uMatrix;
        uniform vec3 uGains;
        uniform float uHighlights;
        void main() {
            vec2 origin = floor(gl_FragCoord.xy) * uBlock;
            vec2 sum = vec2(0.0);
            for (int dy = 0; dy < $LARGEST_BLOCK; dy++) {
                if (float(dy) >= uBlock) break;
                for (int dx = 0; dx < $LARGEST_BLOCK; dx++) {
                    if (float(dx) >= uBlock) break;
                    vec2 at = min(origin + vec2(float(dx), float(dy)), uSize - 1.0) + 0.5;
                    vec3 light = logLight3(texture2D(uTex, at / uSize).rgb);
                    float l = log2(max(0.000001, hlForward(before(light, uMatrix, uGains), uHighlights))) - SHIFT;
                    sum += vec2(l, l * l);
                }
            }
            gl_FragColor = vec4(sum / (uBlock * uBlock), 0.0, 1.0);
        }
        """

        // The mean over the box of 2r + 1 pixels along uDir, of blocks of uBlock pixels, its edges extended. With
        // uCoefficients, the means of the moments made into the guided filter's coefficients (LogColor.swift:141-146).
        val BLUR = """
        uniform sampler2D uSrc;
        uniform vec2 uBlocks;
        uniform vec2 uDir;
        uniform float uBlock;
        uniform float uRadius;
        uniform float uCoefficients;
        void main() {
            float count = dot(uBlocks, uDir);
            float at = dot(floor(gl_FragCoord.xy), uDir);
            vec2 across = floor(gl_FragCoord.xy) * (1.0 - uDir) + 0.5;
            float low = (at + 0.5) * uBlock - uRadius - 0.5;
            float high = low + 2.0 * uRadius + 1.0;
            float first = floor(low / uBlock);
            vec2 sum = vec2(0.0);
            for (int i = 0; i < $LARGEST_SPAN; i++) {
                float k = first + float(i);
                if (k * uBlock >= high) break;
                float cover = min((k + 1.0) * uBlock, high) - max(k * uBlock, low);
                vec2 texel = across + uDir * (clamp(k, 0.0, count - 1.0) + 0.5 - across);
                sum += cover * texture2D(uSrc, texel / uBlocks).rg;
            }
            vec2 mean = sum / (2.0 * uRadius + 1.0);
            if (uCoefficients > 0.5) {
                float variance = max(0.0, mean.y - mean.x * mean.x);
                float a = variance / (variance + ${LocalRecovery.EPSILON});
                mean = vec2(a, (mean.x + SHIFT) * (1.0 - a));
            }
            gl_FragColor = vec4(mean, 0.0, 1.0);
        }
        """

        // The code values of a pixel recoded for its recovered light (LogColor.swift:149-166, LocalRecovery.recode).
        const val RECODE = """
        uniform vec2 uScale;
        vec3 recoded(vec3 c, sampler2D coef, float strength, mat3 m, vec3 gains, float hl) {
            if (strength <= 0.0) return c;
            vec3 light = logLight3(c);
            float y0 = before(light, m, gains);
            if (y0 <= 0.000001) return c;
            float yb = hlForward(y0, hl);
            vec2 ab = texture2D(coef, vTex * uScale).rg;
            float y = max(0.000001, yb);
            float l = log2(y);
            float baseLog = ab.x * l + ab.y;
            float base = exp2(baseLog);
            float adjusted = base <= 0.35 ? base : 0.35 + log(1.0 + 12.0 * (base - 0.35)) / 12.0;
            float excess = max(0.0, adjusted - 0.6);
            float mappedBase = adjusted <= 0.6 ? adjusted : 0.6 + 0.4 * excess / (excess + 0.4);
            float local = mappedBase * exp2(l - baseLog);
            float spike = max(0.0, local - 0.9);
            local = local <= 0.9 ? local : 0.9 + 0.1 * spike / (spike + 0.1);
            float originalExcess = max(0.0, y - 0.6);
            float original = y <= 0.6 ? y : 0.6 + 0.4 * originalExcess / (originalExcess + 0.4);
            float target = mix(original, local, strength);
            float sceneY = target <= 0.6 ? target : 0.6 + 0.4 * (target - 0.6) / max(0.000001, 1.0 - target);
            float scale = hlInverse(sceneY / y * yb, hl) / y0;
            return vec3(logCode(light.r * scale), logCode(light.g * scale), logCode(light.b * scale));
        }
        uniform sampler2D uTex;
        """

        val FINAL_ONE = """
        $RECODE
        ${HlgColorLut.lookup("uLut", "")}
        uniform sampler2D uCoef;
        uniform float uStrength;
        uniform mat3 uMatrix;
        uniform vec3 uGains;
        uniform float uHighlights;
        void main() {
            vec3 c = texture2D(uTex, vTex).rgb;
            gl_FragColor = vec4(cube(recoded(c, uCoef, uStrength, uMatrix, uGains, uHighlights)), 1.0);
        }
        """

        // Two ends mixed as SDR video's light, as MovingColorLut mixes them.
        val FINAL_TWO = """
        $RECODE
        ${HdrMovie.GLSL}
        ${HlgColorLut.lookup("uLut", "")}
        ${HlgColorLut.lookup("uLutB", "B")}
        uniform sampler2D uCoef;
        uniform float uStrength;
        uniform mat3 uMatrix;
        uniform vec3 uGains;
        uniform float uHighlights;
        uniform sampler2D uCoefB;
        uniform float uStrengthB;
        uniform mat3 uMatrixB;
        uniform vec3 uGainsB;
        uniform float uHighlightsB;
        uniform float uFraction;
        void main() {
            vec3 c = texture2D(uTex, vTex).rgb;
            vec3 a = uFraction >= 1.0 ? vec3(0.0) : cube(recoded(c, uCoef, uStrength, uMatrix, uGains, uHighlights));
            vec3 b = uFraction <= 0.0 ? vec3(0.0) : cubeB(recoded(c, uCoefB, uStrengthB, uMatrixB, uGainsB, uHighlightsB));
            vec3 mixed = uFraction <= 0.0 ? a : uFraction >= 1.0 ? b : sdrEnc(mix(sdrLin(a), sdrLin(b), uFraction));
            gl_FragColor = vec4(mixed, 1.0);
        }
        """

        /** The largest block the moments average and the most blocks a box spans, as loop bounds GLSL ES needs. */
        const val LARGEST_BLOCK = 64
        const val LARGEST_SPAN = 64
    }
}
