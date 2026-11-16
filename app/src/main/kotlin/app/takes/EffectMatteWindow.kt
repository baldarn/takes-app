@file:OptIn(UnstableApi::class)

package app.takes

import android.opengl.GLES20
import android.opengl.GLES30
import androidx.annotation.OptIn
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import app.takes.core.VideoEffectMatteKind
import app.takes.core.VideoEffectTemporalMatte
import app.takes.core.VideoEffectTemporalWindow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The temporal matte of Color Isolation and Chroma Key, algorithm 2, on the
 * export's OpenGL: the original's temporalMatte (MetalVideoEffects.swift:221-251,
 * `Renderer`) with ve_matteGuide, ve_matteCuts and ve_matteWindow
 * (VideoEffects.metal:598-639, `Metal`), whose CPU reference is
 * [VideoEffectTemporalMatte].
 *
 * The history is the source frames this chain drew before, as an export reads
 * them in order: each frame's raw matte in eight bits and its guide in half
 * floats, as `VideoEffectMatteCache` keeps them (Renderer:826-838). The frames
 * the window takes are [VideoEffectTemporalWindow]'s over their source times.
 * The history lives in two texture arrays of eight layers, so the window reads
 * every frame with four samplers where GL ES guarantees sixteen; Media3's
 * GlProgram binds no array, so these passes have a program of their own.
 */
internal class EffectMatteWindow(private val kind: VideoEffectMatteKind) {
    private class Entry(val time: Double, val layer: Int)

    private val ring = ArrayList<Entry>()
    private var width = 0
    private var height = 0
    private var mattes = 0
    private var guides = 0
    private var framebuffer = 0
    private var lastTime = Double.NEGATIVE_INFINITY
    private var lastFrame = Long.MIN_VALUE
    private var programs: Programs? = null

    /**
     * The window's matte for the frame [frame] of the chain at source second
     * [time], of an effect starting at [start]: the [raw] matte blended with
     * the history, into [gpu]'s target [key]. [picture] is the pass's input,
     * the guide's source. The history starts again after a frame the effect
     * did not draw, a step back, or a step longer than a quarter second.
     */
    fun apply(gpu: EffectGpu, raw: EffectTarget, picture: EffectSource, frame: Long, time: Double, start: Double, key: String): EffectTarget {
        allocate(raw.width, raw.height)
        val step = ring.lastOrNull()?.let { time - it.time } ?: 0.0
        if (frame != lastFrame + 1 || time < lastTime || step > VideoEffectTemporalWindow.LONGEST_STEP) ring.clear()
        // A held frame is the same source frame again: its window is the one it had.
        if (ring.lastOrNull()?.time == time) ring.removeAt(ring.size - 1)
        lastFrame = frame
        lastTime = time
        val layer = (0 until LAYERS).first { free -> ring.none { it.layer == free } }
        val times = ring.map { it.time } + time
        val window = VideoEffectTemporalWindow.make(times, time, start, kind)?.indices ?: listOf(times.size - 1)
        // Newest first, as the passes read them.
        val layers = window.reversed().take(kind.capacity).map { if (it == times.size - 1) layer else ring[it].layer }.toIntArray()
        val count = layers.size

        val (gw, gh) = VideoEffectTemporalMatte.guideSize(width, height)
        val made = programs ?: Programs(
            link(MatteWindowShaders.GUIDE), link(MatteWindowShaders.CUTS), link(MatteWindowShaders.WINDOW), link(MatteWindowShaders.STORE),
        ).also { programs = it }
        layered(guides, layer)
        draw(made.guide, framebuffer, gw, gh) { p ->
            texture(p, "uTex", GLES20.GL_TEXTURE_2D, picture.texture, 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uEncoded"), if (picture.encoded) 1f else 0f)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uIn"), picture.width.toFloat(), picture.height.toFloat())
            GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uOut"), gw.toFloat(), gh.toFloat())
        }
        val cuts = gpu.target("$key.cuts", VideoEffectTemporalMatte.SLOTS, 1)
        draw(made.cuts, cuts.framebuffer, VideoEffectTemporalMatte.SLOTS, 1) { p ->
            texture(p, "uGuides", GLES30.GL_TEXTURE_2D_ARRAY, guides, 0)
            GLES20.glUniform1iv(GLES20.glGetUniformLocation(p, "uLayers"), count, layers, 0)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uCount"), count)
        }
        val out = gpu.target(key, width, height)
        draw(made.window, out.framebuffer, width, height) { p ->
            texture(p, "uCurrent", GLES20.GL_TEXTURE_2D, raw.texture, 0)
            texture(p, "uMattes", GLES30.GL_TEXTURE_2D_ARRAY, mattes, 1)
            texture(p, "uGuides", GLES30.GL_TEXTURE_2D_ARRAY, guides, 2)
            texture(p, "uCuts", GLES20.GL_TEXTURE_2D, cuts.texture, 3)
            GLES20.glUniform1iv(GLES20.glGetUniformLocation(p, "uLayers"), count, layers, 0)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uCount"), count)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uPrior"), kind.prior.toFloat())
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uKey"), if (kind == VideoEffectMatteKind.KEY) 1f else 0f)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uOut"), width.toFloat(), height.toFloat())
        }
        // The raw matte joins the history in eight bits (ve_reduce into the cache's r8Unorm, Renderer:408-410).
        layered(mattes, layer)
        draw(made.store, framebuffer, width, height) { p -> texture(p, "uTex", GLES20.GL_TEXTURE_2D, raw.texture, 0) }

        ring.add(Entry(time, layer))
        while (ring.size > kind.capacity - 1) ring.removeAt(0)
        return out
    }

    private fun allocate(w: Int, h: Int) {
        if (w == width && h == height && mattes != 0) return
        release()
        width = w
        height = h
        val (gw, gh) = VideoEffectTemporalMatte.guideSize(w, h)
        val ids = IntArray(1)
        GLES20.glGenFramebuffers(1, ids, 0)
        framebuffer = ids[0]
        mattes = array(w, h, GLES30.GL_R8)
        // The guide in half floats where the device draws into them, as the cache's r16Float; else in eight bits.
        guides = array(gw, gh, GLES30.GL_R16F)
        if (!complete(guides)) {
            GLES20.glDeleteTextures(1, intArrayOf(guides), 0)
            guides = array(gw, gh, GLES30.GL_R8)
        }
        GlUtil.checkGlError()
    }

    private fun array(w: Int, h: Int, format: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, ids[0])
        GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY, 1, format, w, h, LAYERS)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun complete(texture: Int): Boolean {
        layered(texture, 0)
        return GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
    }

    private fun layered(texture: Int, layer: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTextureLayer(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, texture, 0, layer)
    }

    fun release() {
        if (mattes != 0) GLES20.glDeleteTextures(2, intArrayOf(mattes, guides), 0)
        if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        mattes = 0
        guides = 0
        framebuffer = 0
        width = 0
        height = 0
        ring.clear()
        lastFrame = Long.MIN_VALUE
        lastTime = Double.NEGATIVE_INFINITY
    }

    /** Releases the history and the programs, on the GL thread before its context goes. */
    fun delete() {
        release()
        programs?.delete()
        programs = null
    }

    /** The three passes' programs and the store's, made once on the GL thread of the export. */
    class Programs(val guide: Int, val cuts: Int, val window: Int, val store: Int) {
        fun delete() = listOf(guide, cuts, window, store).forEach(GLES20::glDeleteProgram)
    }

    companion object {
        const val LAYERS = VideoEffectTemporalMatte.SLOTS

        /** Whether the current context is OpenGL ES 3 or later, which the texture arrays need. */
        fun supported(): Boolean = GLES20.glGetString(GLES20.GL_VERSION)?.let { version ->
            Regex("""OpenGL ES (\d+)""").find(version)?.groupValues?.get(1)?.toIntOrNull()?.let { it >= 3 }
        } ?: false

        private val quad: ByteBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).also {
            it.asFloatBuffer().put(GlUtil.getNormalizedCoordinateBounds())
        }

        private fun link(fragment: String): Int {
            fun shader(type: Int, source: String): Int {
                val shader = GLES20.glCreateShader(type)
                GLES20.glShaderSource(shader, source)
                GLES20.glCompileShader(shader)
                val ok = IntArray(1)
                GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) throw GlUtil.GlException(GLES20.glGetShaderInfoLog(shader) + "\n" + source)
                return shader
            }
            val program = GLES20.glCreateProgram()
            val vertex = shader(GLES20.GL_VERTEX_SHADER, MatteWindowShaders.VERTEX)
            val pixels = shader(GLES20.GL_FRAGMENT_SHADER, fragment)
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, pixels)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(pixels)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) throw GlUtil.GlException(GLES20.glGetProgramInfoLog(program))
            return program
        }

        private fun texture(program: Int, name: String, target: Int, texture: Int, unit: Int) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
            GLES20.glBindTexture(target, texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, name), unit)
        }

        private fun draw(program: Int, framebuffer: Int, width: Int, height: Int, set: (Int) -> Unit) {
            GlUtil.focusFramebufferUsingCurrentContext(framebuffer, width, height)
            GLES20.glUseProgram(program)
            set(program)
            val position = GLES20.glGetAttribLocation(program, "aFramePosition")
            GLES20.glEnableVertexAttribArray(position)
            quad.position(0)
            GLES20.glVertexAttribPointer(position, 4, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(position)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GlUtil.checkGlError()
        }
    }
}

/**
 * The window's shaders, GLSL ES 3.00 for the texture arrays. Rows are kept
 * from the bottom as Media3's textures keep them; the guide is made from the
 * top, as ve_matteGuide's blocks are, and the cut test's 32 × 18 grid is the
 * same set of samples either way up.
 */
internal object MatteWindowShaders {
    const val VERTEX = """#version 300 es
        in vec4 aFramePosition;
        void main() { gl_Position = aFramePosition; }
    """

    private const val HEAD = """#version 300 es
        precision highp float;
        precision highp int;
        precision highp sampler2D;
        precision highp sampler2DArray;
        out vec4 o;
    """

    /** ve_matteGuide (Metal:598-604): the mean linear luma of each 4 × 4 block, held in 0…1 and sRGB-encoded. */
    const val GUIDE = HEAD + """
        uniform sampler2D uTex;
        uniform float uEncoded;
        uniform vec2 uIn;
        uniform vec2 uOut;
        float lin(float c) { return c > 0.04045 ? pow((c + 0.055) / 1.055, 2.4) : c / 12.92; }
        float srgb(float c) { return c > 0.0031308 ? 1.055 * pow(c, 1.0 / 2.4) - 0.055 : c * 12.92; }
        void main() {
            ivec2 size = ivec2(uIn);
            int qx = int(gl_FragCoord.x);
            int qy = int(uOut.y) - 1 - int(gl_FragCoord.y);
            float sum = 0.0;
            int count = 0;
            for (int dy = 0; dy < 4; dy++) for (int dx = 0; dx < 4; dx++) {
                int x = qx * 4 + dx;
                int y = qy * 4 + dy;
                if (x < size.x && y < size.y) {
                    vec3 c = texelFetch(uTex, ivec2(x, size.y - 1 - y), 0).rgb;
                    if (uEncoded > 0.5) c = vec3(lin(clamp(c.r, 0.0, 1.0)), lin(clamp(c.g, 0.0, 1.0)), lin(clamp(c.b, 0.0, 1.0)));
                    sum += c.r * 0.2126 + c.g * 0.7152 + c.b * 0.0722;
                    count++;
                }
            }
            o = vec4(vec3(srgb(clamp(sum / float(max(1, count)), 0.0, 1.0))), 1.0);
        }
    """

    /** ve_matteCuts (Metal:605-625), a fresh analysis: boundary i, between guide i and the older i + 1, is a cut. */
    const val CUTS = HEAD + """
        uniform sampler2DArray uGuides;
        uniform int uLayers[8];
        uniform int uCount;
        void main() {
            int i = int(gl_FragCoord.x);
            if (i + 1 >= uCount) { o = vec4(0.0); return; }
            float a = float(uLayers[i]);
            float b = float(uLayers[i + 1]);
            float difference = 0.0;
            int ha[16];
            int hb[16];
            for (int k = 0; k < 16; k++) { ha[k] = 0; hb[k] = 0; }
            for (int y = 0; y < 18; y++) for (int x = 0; x < 32; x++) {
                vec2 uv = (vec2(float(x), float(y)) + 0.5) / vec2(32.0, 18.0);
                float current = texture(uGuides, vec3(uv, a)).r;
                float previous = texture(uGuides, vec3(uv, b)).r;
                difference += abs(current - previous);
                ha[min(15, int(current * 16.0))]++;
                hb[min(15, int(previous * 16.0))]++;
            }
            float histogram = 0.0;
            for (int k = 0; k < 16; k++) histogram += abs(float(ha[k] - hb[k]));
            difference /= 576.0;
            histogram /= 1152.0;
            o = vec4(difference > 0.20 || (difference > 0.08 && histogram > 0.30) ? 1.0 : 0.0);
        }
    """

    /** ve_matteWindow (Metal:626-639): the EMA of the window's mattes, newest first, none across a cut; for the key, faded by motion. */
    const val WINDOW = HEAD + """
        uniform sampler2D uCurrent;
        uniform sampler2DArray uMattes;
        uniform sampler2DArray uGuides;
        uniform sampler2D uCuts;
        uniform int uLayers[8];
        uniform int uCount;
        uniform float uPrior;
        uniform float uKey;
        uniform vec2 uOut;
        float matte(int i, ivec2 q) { return i == 0 ? texelFetch(uCurrent, q, 0).r : texelFetch(uMattes, ivec3(q, uLayers[i]), 0).r; }
        void main() {
            ivec2 q = ivec2(gl_FragCoord.xy);
            vec2 uv = gl_FragCoord.xy / uOut;
            float sum = 0.0;
            float remaining = 1.0;
            for (int i = 0; i + 1 < uCount; i++) {
                float prior = texelFetch(uCuts, ivec2(i, 0), 0).r > 0.5 ? 0.0 : uPrior;
                if (uKey > 0.5) {
                    float motion = abs(texture(uGuides, vec3(uv, float(uLayers[i]))).r - texture(uGuides, vec3(uv, float(uLayers[i + 1]))).r);
                    prior *= 1.0 - smoothstep(0.01, 0.02, motion);
                }
                sum += remaining * (1.0 - prior) * matte(i, q);
                remaining *= prior;
                if (remaining == 0.0) break;
            }
            sum += remaining * matte(uCount - 1, q);
            o = vec4(vec3(clamp(sum, 0.0, 1.0)), 1.0);
        }
    """

    /** The raw matte as it is, into a layer of eight bits. */
    const val STORE = HEAD + """
        uniform sampler2D uTex;
        void main() { o = vec4(texelFetch(uTex, ivec2(gl_FragCoord.xy), 0).r); }
    """
}
