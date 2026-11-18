@file:OptIn(UnstableApi::class)

package app.takes

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.SingleColorLut
import app.takes.core.ColorGrade
import app.takes.core.ColorSettings
import app.takes.core.GradeMotion
import app.takes.core.LUTInput
import app.takes.core.LocalRecovery
import app.takes.core.TimelineClip
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * The picture of a clip of an HDR movie carried between HLG and what an effect takes ([HdrMovie]): footage in PQ
 * into HLG, as Media3 itself maps PQ to HLG (its light divided by 1000 nits); an SDR picture into HLG, its white at
 * HLG's reference white; an HLG picture into SDR, for the effects that work on SDR only, its highlights tone-mapped
 * from 1000/203 times reference white down to it (`hlgToDisplaySdr`, core's HdrToneMap), as the original does before
 * its creative effects (YouRec/VideoEffects/MetalVideoEffects.swift:206-221, 605-613).
 */
class HdrSignalEffect(private val conversion: Conversion) : GlEffect {
    enum class Conversion(val expression: String) {
        PqToHlg("hlgEnc(pqLight(c) * 10.0 / HLG_WHITE)"),
        SdrToHlg("sdrToHlg(c)"),
        HlgToSdr("hlgToDisplaySdr(c)"),
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = HdrPass(
        """
        vec3 pqLight(vec3 e) {
            vec3 p = pow(clamp(e, 0.0, 1.0), vec3(1.0 / 78.84375));
            return pow(max(p - 0.8359375, 0.0) / (18.8515625 - 18.6875 * p), vec3(1.0 / 0.1593017578125));
        }
        vec3 converted(vec3 c) { return ${conversion.expression}; }
        """,
    )
}

/**
 * A clip's color table ([ColorLuts.table]) on an HLG picture, as the original applies a look to HDR footage
 * (`YouRec/VideoToolCompositor.swift:505-529`): the light, in BT.709's primaries, encoded by SDR video's curve, which
 * is the signal the table takes, looked up, and taken back. Past SDR white the table goes on as it ends, from the
 * slope of its last step, as Core Image's color cube extrapolates (`inputExtrapolate`), so a highlight stays one.
 */
class HlgColorLut(private val table: FloatArray) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = HdrPass(
        """
        ${lookup("uLut", "")}
        vec3 sdrLinX(vec3 v) { vec3 a = abs(v); return sign(v) * mix(a / 4.5, pow((a + 0.099) / 1.099, vec3(1.0 / 0.45)), step(0.081, a)); }
        vec3 converted(vec3 c) {
            vec3 v = sdrEnc(BT2020_TO_BT709 * hlgLin(c));
            return hlgEnc(BT709_TO_BT2020 * sdrLinX(looked(v)));
        }
        """,
        listOf(table),
    )

    companion object {
        /**
         * In GLSL, a table in [sampler] as `cube$suffix`, read inside 0…1, and `looked$suffix`, which goes on past its
         * ends from the slope of its last step.
         */
        fun lookup(sampler: String, suffix: String) = """
        uniform sampler2D $sampler;
        const float N$suffix = ${ColorGrade.DIMENSION}.0;
        // The table at a signal inside 0…1: blue slices side by side, red across each and green down, filtered.
        vec3 cube$suffix(vec3 v) {
            vec3 p = clamp(v, 0.0, 1.0) * (N$suffix - 1.0);
            float slice = floor(p.b);
            float next = min(slice + 1.0, N$suffix - 1.0);
            vec2 at = vec2(p.r + 0.5, p.g + 0.5);
            vec3 low = texture2D($sampler, vec2((at.x + slice * N$suffix) / (N$suffix * N$suffix), at.y / N$suffix)).rgb;
            vec3 high = texture2D($sampler, vec2((at.x + next * N$suffix) / (N$suffix * N$suffix), at.y / N$suffix)).rgb;
            return mix(low, high, p.b - slice);
        }
        vec3 looked$suffix(vec3 v) {
            vec3 inside = clamp(v, 0.0, 1.0);
            vec3 res = cube$suffix(inside);
            float h = 1.0 / (N$suffix - 1.0);
            for (int i = 0; i < 3; i++) {
                vec3 e = vec3(0.0);
                e[i] = h;
                if (v[i] > 1.0) res += (cube$suffix(inside) - cube$suffix(inside - e)) / h * (v[i] - 1.0);
                if (v[i] < 0.0) res += (cube$suffix(inside + e) - cube$suffix(inside)) / h * v[i];
            }
            return res;
        }
        """
    }
}

/**
 * A grade that moves between two ends ([GradeMotion]) in the export, as the original draws it
 * (`YouRec/VideoToolCompositor.swift:145-152`): the picture graded by A's table ([a]) and by B's ([b]), dissolved as
 * light by the fraction of the motion at each frame ([fraction], from the frame's time in microseconds), A alone
 * before the motion starts and B alone once it is there. On SDR video's signal ([hdr] false) the two are looked up
 * and mixed as SDR video's light ([GradeMotion.dissolve]); on an HLG picture each is looked up as [HlgColorLut] does,
 * past SDR white too, and mixed as the light that comes out, so that at either end it is that grade's [HlgColorLut].
 */
class MovingColorLut(private val a: FloatArray, private val b: FloatArray, private val hdr: Boolean, private val fraction: (Long) -> Double) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = HdrPass(
        if (hdr) """
        ${HlgColorLut.lookup("uLut", "")}
        ${HlgColorLut.lookup("uLutB", "B")}
        uniform float uFraction;
        vec3 sdrLinX(vec3 v) { vec3 a = abs(v); return sign(v) * mix(a / 4.5, pow((a + 0.099) / 1.099, vec3(1.0 / 0.45)), step(0.081, a)); }
        vec3 converted(vec3 c) {
            vec3 v = sdrEnc(BT2020_TO_BT709 * hlgLin(c));
            vec3 light = sdrLinX(looked(v));
            if (uFraction > 0.0) light = mix(light, sdrLinX(lookedB(v)), min(uFraction, 1.0));
            return hlgEnc(BT709_TO_BT2020 * light);
        }
        """ else """
        ${HlgColorLut.lookup("uLut", "")}
        ${HlgColorLut.lookup("uLutB", "B")}
        uniform float uFraction;
        vec3 converted(vec3 c) {
            if (uFraction <= 0.0) return cube(c);
            if (uFraction >= 1.0) return cubeB(c);
            return sdrEnc(mix(sdrLin(cube(c)), sdrLin(cubeB(c)), uFraction));
        }
        """,
        listOf(a, b),
    ) { program, time -> program.setFloatUniform("uFraction", fraction(time).toFloat()) }
}

/**
 * The color of a clip's picture along its chain of effects, into [effects]: in an HDR movie ([hdr]) every clip ends
 * in HLG ([HdrMovie]). Footage in PQ ([transfer]) is carried into HLG at once, SDR footage once the effects that
 * need it are drawn ([inHdr]); an effect that works on SDR only is given HLG footage as SDR ([asSdr]). In an SDR
 * movie nothing is added but the look, as before.
 */
class ClipColor(private val effects: MutableList<Effect>, private val hdr: Boolean, transfer: Int?) {
    private var sdr = !hdr || transfer == null

    init {
        if (hdr && transfer == C.COLOR_TRANSFER_ST2084) effects += HdrSignalEffect(HdrSignalEffect.Conversion.PqToHlg)
    }

    /** Before an effect that works on SDR only: the picture tone-mapped from its HDR headroom ([HdrSignalEffect]). */
    fun asSdr() {
        if (!sdr) effects += HdrSignalEffect(HdrSignalEffect.Conversion.HlgToSdr)
        sdr = true
    }

    /**
     * Before the video effects of [clip]: SDR ([asSdr]) unless every one enabled is a Kaleidoscope, which only moves
     * pixels and keeps the picture's HDR, as the original keeps it (YouRec/VideoEffects/MetalVideoEffects.swift:596-606).
     */
    fun beforeEffects(clip: TimelineClip) {
        if (!geometryOnly(clip)) asSdr()
    }

    /** Where the picture is to be the movie's: in HLG in an HDR movie. */
    fun inHdr() {
        if (hdr && sdr) effects += HdrSignalEffect(HdrSignalEffect.Conversion.SdrToHlg)
        sdr = !hdr
    }

    /**
     * The look of [settings], on the picture as it is here ([ColorLuts]). A grade that moves ([GradeMotion]) is drawn
     * at each frame of [clip], which begins at second [begins] of its sequence, as its framing is ([FramingEffect]).
     */
    fun look(settings: ColorSettings, folder: File, log: LUTInput?, clip: TimelineClip? = null, begins: Double = 0.0) {
        val ends = GradeMotion.ends(settings)
        // Log footage with a local highlight recovery: the filter's passes before the table ([RecoveredColorLut]).
        val grades = if (ends != null && clip != null) ends.toList() else listOf(settings)
        if (log != null && sdr && grades.any { LocalRecovery.strength(it) > 0 }) {
            effects += RecoveredColorLut(grades.map { RecoveredColorLut.End(ColorLuts.table(it, folder, log), it, log) }) { us ->
                if (clip == null) 0.0 else GradeMotion.fraction(clip, us / 1e6 - begins, settings) ?: 0.0
            }
            return
        }
        if (ends != null && clip != null) {
            val a = ColorLuts.table(ends.first, folder, log)
            val b = ColorLuts.table(ends.second, folder, log)
            effects += MovingColorLut(a, b, hdr = !sdr) { us -> GradeMotion.fraction(clip, us / 1e6 - begins, settings) ?: 0.0 }
            return
        }
        effects += if (sdr) SingleColorLut.createFromCube(ColorLuts.cube(settings, folder, log)) else HlgColorLut(ColorLuts.table(settings, folder, log))
    }
}

/** Whether the enabled video effects of [clip] only move pixels: all of them Kaleidoscopes (MetalVideoEffects.swift:596). */
internal fun geometryOnly(clip: TimelineClip): Boolean =
    clip.videoEffects.orEmpty().filter { it.enabled }.all { it.kind == app.takes.core.VideoEffectID.Kaleidoscope }

/**
 * One pass over the picture by `converted(c)`, with [tables] in the samplers `uLut` and `uLutB` ([HlgColorLut]) and
 * [uniforms] set for each frame from its time.
 */
private class HdrPass(
    body: String,
    private val tables: List<FloatArray> = emptyList(),
    private val uniforms: (GlProgram, Long) -> Unit = { _, _ -> },
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents = */ false, /* texturePoolCapacity = */ 1) {
    private val fragment = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
        uniform sampler2D uTex;
        varying vec2 vTex;
        ${HdrMovie.GLSL}
        $body
        void main() { gl_FragColor = vec4(converted(texture2D(uTex, vTex).rgb), 1.0); }
    """
    private var program: GlProgram? = null
    private var luts = IntArray(0)

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val program = program ?: GlProgram(VERTEX, fragment).also {
            it.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            program = it
            luts = IntArray(tables.size) { index -> lutTexture(tables[index]) }
        }
        program.use()
        program.setSamplerTexIdUniform("uTex", inputTexId, 0)
        luts.forEachIndexed { index, lut -> program.setSamplerTexIdUniform(SAMPLERS[index], lut, index + 1) }
        uniforms(program, presentationTimeUs)
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError()
    }

    override fun release() {
        super.release()
        program?.delete()
        for (lut in luts) GlUtil.deleteTexture(lut)
    }

    private companion object {
        val SAMPLERS = listOf("uLut", "uLutB")
        const val VERTEX = """
            attribute vec4 aFramePosition;
            varying vec2 vTex;
            void main() { gl_Position = aFramePosition; vTex = aFramePosition.xy * 0.5 + 0.5; }
        """
    }
}

/** A colour table as a texture of its blue slices side by side, red across each and green down, eight bits as Media3's table. */
internal fun lutTexture(table: FloatArray): Int {
    val n = ColorGrade.DIMENSION
    val bytes = ByteBuffer.allocateDirect(n * n * n * 4).order(ByteOrder.nativeOrder())
    for (green in 0 until n) for (blue in 0 until n) for (red in 0 until n) {
        val index = ((blue * n + green) * n + red) * 3
        for (channel in 0..2) bytes.put((table[index + channel] * 255).roundToInt().coerceIn(0, 255).toByte())
        bytes.put(255.toByte())
    }
    bytes.rewind()
    val texture = GlUtil.generateTexture()
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, n * n, n, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
    for (filter in listOf(GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_TEXTURE_MAG_FILTER)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, filter, GLES20.GL_LINEAR)
    for (wrap in listOf(GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_TEXTURE_WRAP_T)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, wrap, GLES20.GL_CLAMP_TO_EDGE)
    GlUtil.checkGlError()
    return texture
}
