@file:OptIn(UnstableApi::class)

package app.takes

import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import app.takes.core.NightFixMode
import app.takes.core.VideoEffectNightFusion
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Night fix's motion-aligned fusion on the export's OpenGL, Medium and Precise: each frame's guide and its pyramid,
 * the flow between two frames' guides, and a frame fused with its neighbours. The passes are the core's
 * ([VideoEffectNightFusion]) and so the original's kernels nf_guide, nf_seed, nf_fuse and nf_finish
 * (YouRec/VideoEffects/VideoEffects.metal:640-681) with the port's own flow where the original asks Vision
 * (YouRec/VideoEffects/NightFix.swift:263-276).
 *
 * A frame's picture is kept in eight bits as the chain gave it, its guide's pyramid in half floats: each level the
 * guide's value with its gradients across and down. The flows between two frames are kept while both are, as the
 * original keeps them (NightFix.swift:263-264, :294-295). The guide is read back to work out the gates.
 *
 * Every picture of its own is in rows from the top as its passes see them, the texture's rows from the bottom, as
 * [EffectPassShaders]' are. Lives on the GL thread of the export.
 */
internal class NightFusion(private val mode: NightFixMode, private val gpu: EffectGpu) {
    /** A frame the fusion holds: [order] counts the distinct frames of the piece, [segment] starts again where the footage jumps. */
    inner class Frame(val order: Long, val segment: Int, val time: Double, val width: Int, val height: Int) {
        internal val picture: EffectTarget = take(width, height, false)
        internal var levels: List<EffectTarget> = emptyList()
        internal lateinit var guide: VideoEffectNightFusion.Guide
        val source: EffectSource get() = EffectSource(picture.texture, width, height, encoded = false)
    }

    private val free = ArrayList<EffectTarget>()
    private val flows = HashMap<Pair<Long, Long>, EffectTarget>()
    private var copy: GlProgram? = null
    private var readback: ByteBuffer? = null

    /** Half floats, which the sums and the flow need: without them nothing is fused. */
    val available: Boolean by lazy { gpu.target(KEY + "probe", 1, 1).precise }

    private fun take(width: Int, height: Int, precise: Boolean): EffectTarget {
        val i = free.indexOfFirst { it.width == width && it.height == height && it.precise == precise }
        if (i >= 0) return free.removeAt(i)
        return EffectTarget(width, height, precise)
    }

    private fun give(target: EffectTarget) {
        free += target
        // A few spare pictures at most: the rest go.
        while (free.size > SPARE) free.removeAt(0).release()
    }

    /**
     * Keeps the chain's [texture] of [width] × [height] as frame [order] of [segment], at source second [time]: its
     * picture copied, its guide drawn, read back and made into its pyramid (NightFix.swift:246-262,
     * VideoEffects.metal:647-655).
     */
    fun add(texture: Int, width: Int, height: Int, order: Long, segment: Int, time: Double): Frame {
        val frame = Frame(order, segment, time, width, height)
        val bound = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, bound, 0)
        GlUtil.focusFramebufferUsingCurrentContext(frame.picture.framebuffer, width, height)
        TimeShaders.copy(copy ?: TimeShaders.program(TimeShaders.COPY).also { copy = it }, texture)

        val (gw, gh) = VideoEffectNightFusion.guideSize(width, height, mode)
        val guide = gpu.draw(NightFusionShaders.GUIDE, frame.source, KEY + "guide", gw, gh, {}, eightBit = true)
        frame.guide = VideoEffectNightFusion.Guide(read(guide))
        val levels = ArrayList<EffectTarget>()
        var from = EffectSource(guide)
        var w = gw
        var h = gh
        while (true) {
            val level = take(w, h, true)
            gpu.drawInto(NightFusionShaders.LEVEL, from, level.framebuffer, w, h, {})
            levels += level
            from = EffectSource(level)
            w /= 2
            h /= 2
            if (minOf(w, h) < VideoEffectNightFusion.SMALLEST_LEVEL) break
        }
        frame.levels = levels
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bound[0])
        return frame
    }

    /** The eight-bit guide, read back as the original reads its pixel buffer (NightFix.swift:253-261). */
    private fun read(guide: EffectTarget): VideoEffectNightFusion.Plane {
        val w = guide.width
        val h = guide.height
        val bytes = readback?.takeIf { it.capacity() >= w * h * 4 } ?: ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder()).also { readback = it }
        bytes.clear()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, guide.framebuffer)
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
        GlUtil.checkGlError()
        return VideoEffectNightFusion.Plane.of(w, h) { x, y -> (bytes.get(((h - 1 - y) * w + x) * 4).toInt() and 0xff) / 255.0 }
    }

    /** Lets [frame] go, with the flows to and from it. */
    fun forget(frame: Frame) {
        give(frame.picture)
        frame.levels.forEach(::give)
        frame.levels = emptyList()
        val gone = flows.keys.filter { it.first == frame.order || it.second == frame.order }
        for (key in gone) give(flows.remove(key)!!)
    }

    /** The flow from [a] to [b], kept while both frames are: [VideoEffectNightFusion.flow] level by level. */
    fun flow(a: Frame, b: Frame): EffectTarget {
        flows[a.order to b.order]?.let { return it }
        val iterations = VideoEffectNightFusion.iterations(mode)
        var flow: EffectTarget? = null
        for (k in a.levels.indices.reversed()) {
            val level = a.levels[k]
            val w = level.width
            val h = level.height
            var current = gpu.draw(NightFusionShaders.FLOW_INIT, EffectSource(flow ?: level), "${KEY}flow.$k.init", w, h, { p ->
                p.setFloatsUniform("uScale", floatArrayOf(if (flow == null) 0f else 1f))
            })
            for (i in 0 until iterations) {
                val target = if (k == 0 && i == iterations - 1) take(w, h, true) else gpu.target("${KEY}flow.$k.${i % 2}", w, h)
                gpu.drawInto(NightFusionShaders.FLOW_STEP, EffectSource(current), target.framebuffer, w, h, { p ->
                    p.setSamplerTexIdUniform("uA", level.texture, 1)
                    p.setSamplerTexIdUniform("uB", b.levels[k].texture, 2)
                    p.setFloatsUniform("uFlow", floatArrayOf(VideoEffectNightFusion.FLOW_DAMPING.toFloat(), VideoEffectNightFusion.FLOW_STEP.toFloat()))
                })
                current = target
            }
            flow = current
        }
        flows[a.order to b.order] = flow!!
        return flow
    }

    /**
     * [frame] fused with [neighbours], those of its window whose guides pass the gates, in order: seeded with itself,
     * each neighbour added where the flow puts the pixel, then the mean, encoded, in half floats
     * (NightFix.swift:277-297, VideoEffects.metal:656-681).
     */
    fun fuse(frame: Frame, neighbours: List<Frame>): EffectTarget {
        val w = frame.width
        val h = frame.height
        val (gw, gh) = VideoEffectNightFusion.guideSize(w, h, mode)
        var sum = gpu.draw(NightFusionShaders.SEED, frame.source, "${KEY}sum.0", w, h, {})
        val chosen = neighbours.filter { VideoEffectNightFusion.comparable(frame.guide, it.guide) }
        chosen.forEachIndexed { i, other ->
            val forward = flow(frame, other)
            val backward = flow(other, frame)
            val into = gpu.target("${KEY}sum.${(i + 1) % 2}", w, h)
            val from = sum
            gpu.drawInto(NightFusionShaders.FUSE, frame.source, into.framebuffer, w, h, { p ->
                p.setSamplerTexIdUniform("uOther", other.picture.texture, 1)
                p.setSamplerTexIdUniform("uForward", forward.texture, 2)
                p.setSamplerTexIdUniform("uBackward", backward.texture, 3)
                p.setSamplerTexIdUniform("uAccum", from.texture, 4)
                p.setFloatsUniform("uGuide", floatArrayOf(gw.toFloat(), gh.toFloat()))
                p.setFloatsUniform("uOffset", floatArrayOf((other.order - frame.order).toFloat()))
            })
            sum = into
        }
        return gpu.draw(NightFusionShaders.FINISH, EffectSource(sum), "${KEY}fused", w, h, {})
    }

    fun release() {
        flows.values.forEach { it.release() }
        flows.clear()
        free.forEach { it.release() }
        free.clear()
        copy?.delete()
        copy = null
    }

    private companion object {
        const val KEY = "night."
        const val SPARE = 24
    }
}

/** The passes of [NightFusion] and the adjust pass with a fused picture. */
internal object NightFusionShaders {
    // A picture of its own read at a position from the top left: by the texture's filtering ([own]), or exactly
    // between the pixels of an [n]-pixel picture, held at the edges ([exactOf]), as the effects' own reads ([texel]).
    private const val OWN = """
        vec4 own(sampler2D s, vec2 uv) { return texture2D(s, vec2(uv.x, 1.0 - uv.y)); }
        vec4 texel(sampler2D s, vec2 n, vec2 i) {
            vec2 p = clamp(i, vec2(0.0), n - 1.0);
            return texture2D(s, vec2((p.x + 0.5009765625) / n.x, 1.0 - (p.y + 0.4990234375) / n.y));
        }
        vec4 exactOf(sampler2D s, vec2 n, vec2 uv) {
            vec2 p = uv * n - 0.5;
            vec2 i = floor(p);
            vec2 f = p - i;
            return mix(mix(texel(s, n, i), texel(s, n, i + vec2(1.0, 0.0)), f.x), mix(texel(s, n, i + vec2(0.0, 1.0)), texel(s, n, i + 1.0), f.x), f.y);
        }
    """

    /** `nf_guide`: the mean luma of nine reads 0.65 guide pixels apart, its square root (VideoEffectNightFusion.guide). */
    val GUIDE = """
        ${EffectPassShaders.HEAD}
        void main() {
            vec2 q = pixel();
            vec2 uv = (q + 0.5) / uOut;
            float y = 0.0;
            for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) y += luma(exact(uv + vec2(float(i), float(j)) * 0.65 / uOut)) / 9.0;
            gl_FragColor = vec4(vec3(sqrt(clamp(y, 0.0, 1.0))), 1.0);
        }
    """

    /** A level of the pyramid from the one above, read at its pixel centres, with its central differences (VideoEffectNightFusion.pyramid, .step). */
    val LEVEL = """
        ${EffectPassShaders.HEAD}
        float level(vec2 i) { i = clamp(i, vec2(0.0), uOut - 1.0); return exact((i + 0.5) / uOut).r; }
        void main() {
            vec2 q = pixel();
            float a = level(q);
            float ax = (level(q + vec2(1.0, 0.0)) - level(q - vec2(1.0, 0.0))) / 2.0;
            float ay = (level(q + vec2(0.0, 1.0)) - level(q - vec2(0.0, 1.0))) / 2.0;
            gl_FragColor = vec4(a, ax, ay, 1.0);
        }
    """

    /** The flow of the coarser level, scaled to this one; nothing at the coarsest ([uScale] 0). */
    val FLOW_INIT = """
        ${EffectPassShaders.HEAD}
        uniform float uScale;
        void main() {
            vec2 q = pixel();
            vec2 f = exact((q + 0.5) / uOut).rg * (uOut / uIn) * uScale;
            gl_FragColor = vec4(f, 0.0, 1.0);
        }
    """

    /** A step of the flow at a level (VideoEffectNightFusion.step): [uA] the first guide's level with its gradients, [uB] the second's. */
    val FLOW_STEP = """
        ${EffectPassShaders.HEAD}
        $OWN
        uniform sampler2D uA;
        uniform sampler2D uB;
        uniform vec2 uFlow;
        void main() {
            vec2 q = pixel();
            vec2 f = texel(uTex, uOut, q).rg;
            float gxx = 0.0;
            float gxy = 0.0;
            float gyy = 0.0;
            float bx = 0.0;
            float by = 0.0;
            for (int j = -${VideoEffectNightFusion.FLOW_REACH}; j <= ${VideoEffectNightFusion.FLOW_REACH}; j++) for (int i = -${VideoEffectNightFusion.FLOW_REACH}; i <= ${VideoEffectNightFusion.FLOW_REACH}; i++) {
                vec2 t = clamp(q + vec2(float(i), float(j)), vec2(0.0), uOut - 1.0);
                vec3 a = texel(uA, uOut, t).rgb;
                float e = own(uB, (t + f + 0.5) / uOut).r - a.r;
                gxx += a.g * a.g;
                gxy += a.g * a.b;
                gyy += a.b * a.b;
                bx += e * a.g;
                by += e * a.b;
            }
            gxx += uFlow.x;
            gyy += uFlow.x;
            float det = gxx * gyy - gxy * gxy;
            vec2 s = clamp(-vec2(gyy * bx - gxy * by, gxx * by - gxy * bx) / det, -uFlow.y, uFlow.y);
            gl_FragColor = vec4(f + s, 0.0, 1.0);
        }
    """

    /** `nf_seed`: the frame's own value in linear light, weight 1. */
    val SEED = """
        ${EffectPassShaders.HEAD}
        ${EffectPassShaders.REC709}
        void main() { gl_FragColor = vec4(decode709(at(pixel())), 1.0); }
    """

    /**
     * `nf_fuse`: a neighbour [uOther] added to the sums [uAccum] where the flow puts the pixel (VideoEffectNightFusion.fuse).
     * Every read is made before the tests, which then only choose what is written, where the original returns early
     * (Metal:667-669): a read in control flow that differs between the pixels of a 2 × 2 quad has no defined
     * derivatives, and SwiftShader, the emulators' GL, gave the pixels still in it wrong values beside a pixel that
     * had left, a row and a column of them along the edges past which a neighbour is not added.
     */
    val FUSE = """
        ${EffectPassShaders.HEAD}
        ${EffectPassShaders.REC709}
        $OWN
        uniform sampler2D uOther;
        uniform sampler2D uForward;
        uniform sampler2D uBackward;
        uniform sampler2D uAccum;
        uniform vec2 uGuide;
        uniform float uOffset;
        vec3 smoothOf(sampler2D s, vec2 uv) {
            vec2 d = 1.2 / uIn;
            vec3 sum = vec3(0.0);
            for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) sum += own(s, uv + vec2(float(i), float(j)) * d).rgb / 9.0;
            return sum;
        }
        void main() {
            vec2 q = pixel();
            vec2 uv = (q + 0.5) / uOut;
            vec4 sum = texel(uAccum, uOut, q);
            vec2 f = own(uForward, uv).rg;
            vec2 at = uv + f / uGuide;
            bool inside = !any(lessThan(at, 1.0 / uGuide)) && !any(greaterThan(at, 1.0 - 2.0 / uGuide));
            vec2 b = own(uBackward, at).rg;
            float agreement = length(f + b);
            vec3 d = smoothOf(uTex, uv) - smoothOf(uOther, at);
            vec3 other = decode709(exactOf(uOther, uIn, at).rgb);
            float t = uOffset / 3.0;
            float noise = 6.0 / 255.0 * 1.5;
            float w = exp(-agreement * agreement / (0.8 * 0.8)) * exp(-dot(d, d) / (3.0 * noise * noise)) * exp(-0.5 * t * t);
            gl_FragColor = inside && agreement <= 3.0 ? sum + vec4(other * w, w) : sum;
        }
    """

    /** `nf_finish`: the mean, encoded. */
    val FINISH = """
        ${EffectPassShaders.HEAD}
        ${EffectPassShaders.REC709}
        $OWN
        void main() {
            vec4 a = texel(uTex, uIn, pixel());
            gl_FragColor = vec4(encode709(a.rgb / max(1.0, a.a)), 1.0);
        }
    """

    /** `nf_adjust` with the fused picture [uFused] taken over the raw one by the mixing (VideoEffectNightFix.adjust). */
    val ADJUST = """
        ${EffectPassShaders.HEAD}
        ${EffectPassShaders.REC709}
        $OWN
        uniform vec4 uFix;
        uniform sampler2D uFused;
        void main() {
            vec2 q = pixel();
            vec2 uv = (q + 0.5) / uOut;
            vec3 c = mix(at(q), texel(uFused, uIn, q).rgb, uFix.y);
            vec3 mean = vec3(0.0);
            vec3 square = vec3(0.0);
            for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) {
                vec2 p = uv + vec2(float(i), float(j)) * uFix.x / uIn;
                vec3 n = mix(exact(p), exactOf(uFused, uIn, p).rgb, uFix.y);
                mean += n / 9.0;
                square += n * n / 9.0;
            }
            vec3 variance = max(square - mean * mean, vec3(1e-8));
            vec3 keep = clamp(1.0 - uFix.z / variance, 0.0, 1.0);
            c = decode709(mix(c, mean + keep * (c - mean), uFix.y));
            float y = max(0.0, luma(c));
            float scale = y <= 1.0 ? uFix.w / (1.0 + (uFix.w - 1.0) * y) : 1.0;
            gl_FragColor = vec4(encode709(max(c * scale, 0.0)), 1.0);
        }
    """
}
