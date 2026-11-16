@file:OptIn(UnstableApi::class)

package app.takes

import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import app.takes.core.GaussianKernel
import app.takes.core.EffectVector
import app.takes.core.VideoEffectFormulasC
import app.takes.core.VideoEffectPasses
import app.takes.core.VideoEffectQuantization

/**
 * The passes some effects draw before their own, on the export's OpenGL: the
 * bright parts of a picture at a smaller size, an area average to a smaller
 * size, and the separable Gaussian, each the core's ([VideoEffectPasses]) and
 * so the original's (`MetalVideoEffects.swift:292-326`, `VideoEffects.metal:419-453`, `:507-520`).
 *
 * Every picture they make is linear light in half floats where the device
 * draws into them, as the original's rgba16Float, else in eight bits. A
 * picture read is either the chain's own, sRGB-encoded in eight bits, or one
 * of these. Positions are 0…1 from the top left; the textures, as Media3's,
 * keep their rows from the bottom.
 */
internal object EffectPassShaders {
    const val TAPS = 24

    private const val HEAD = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
        uniform sampler2D uTex;
        uniform float uEncoded;
        uniform vec2 uIn;
        uniform vec2 uOut;
        varying vec2 vTex;
        const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
        float luma(vec3 c) { return dot(c, LUMA); }
        vec3 above(vec3 c, float edge) { return step(vec3(edge), c) * (1.0 - step(c, vec3(edge))); }
        vec3 lin(vec3 c) { return mix(c / 12.92, pow(max((c + 0.055) / 1.055, 0.0), vec3(2.4)), above(c, 0.04045)); }
        vec3 srgb(vec3 c) { return mix(c * 12.92, 1.055 * pow(max(c, 0.0), vec3(1.0 / 2.4)) - 0.055, above(c, 0.0031308)); }
        // The pixel at whole indices, held at the edges, in linear light.
        vec3 at(vec2 i) {
            vec2 p = clamp(i, vec2(0.0), uIn - 1.0);
            // A 1/1024 past the centre, as the effect's own reads (EffectShader).
            vec3 c = texture2D(uTex, vec2((p.x + 0.5009765625) / uIn.x, 1.0 - (p.y + 0.4990234375) / uIn.y)).rgb;
            return uEncoded > 0.5 ? lin(clamp(c, 0.0, 1.0)) : c;
        }
        // Read between pixel centres in linear light, held at the edges.
        vec3 read(vec2 uv) {
            if (uEncoded < 0.5) return texture2D(uTex, vec2(uv.x, 1.0 - uv.y)).rgb;
            vec2 p = uv * uIn - 0.5;
            vec2 i = floor(p);
            vec2 f = p - i;
            return mix(mix(at(i), at(i + vec2(1.0, 0.0)), f.x), mix(at(i + vec2(0.0, 1.0)), at(i + 1.0), f.x), f.y);
        }
        // Read between pixel centres from whole pixels, with exact weights: where a few levels matter, as in Night fix.
        vec3 exact(vec2 uv) {
            vec2 p = uv * uIn - 0.5;
            vec2 i = floor(p);
            vec2 f = p - i;
            return mix(mix(at(i), at(i + vec2(1.0, 0.0)), f.x), mix(at(i + vec2(0.0, 1.0)), at(i + 1.0), f.x), f.y);
        }
        vec2 pixel() { return vec2(floor(gl_FragCoord.x), uOut.y - 1.0 - floor(gl_FragCoord.y)); }
    """

    /** `ve_highlights`: [uStep] input pixels a side under each output pixel, read at whole pixels when [uIntegral]. */
    val HIGHLIGHTS = """
        $HEAD
        uniform vec2 uStep;
        uniform float uIntegral;
        uniform vec2 uThreshold;
        void main() {
            vec2 q = pixel();
            vec3 sum = vec3(0.0);
            float n = 0.0;
            for (int j = 0; j < 8; j++) {
                if (float(j) >= uStep.y) break;
                for (int i = 0; i < 8; i++) {
                    if (float(i) >= uStep.x) break;
                    vec2 k = vec2(float(i), float(j));
                    vec3 c = uIntegral > 0.5 ? at(q * uStep + k) : read((q + (k + 0.5) / uStep) / uOut);
                    sum += c * smoothstep(uThreshold.x - uThreshold.y, uThreshold.x + uThreshold.y, luma(c));
                    n += 1.0;
                }
            }
            gl_FragColor = vec4(sum / max(n, 1.0), 1.0);
        }
    """

    /** `ve_reduce`: a grid of [uCells] reads under each output pixel, or with [uPaired] one read between four pixels for each four. */
    val REDUCE = """
        $HEAD
        uniform vec2 uCells;
        uniform float uPaired;
        void main() {
            vec2 q = pixel();
            vec3 total = vec3(0.0);
            if (uPaired > 0.5) {
                for (int j = 0; j < 16; j++) {
                    float y = float(j) * 2.0;
                    if (y >= uCells.y) break;
                    for (int i = 0; i < 16; i++) {
                        float x = float(i) * 2.0;
                        if (x >= uCells.x) break;
                        total += read((q + (vec2(x, y) + 1.0) / uCells) / uOut);
                    }
                }
                total *= 4.0;
            } else {
                for (int j = 0; j < 32; j++) {
                    if (float(j) >= uCells.y) break;
                    for (int i = 0; i < 32; i++) {
                        if (float(i) >= uCells.x) break;
                        total += read((q + (vec2(float(i), float(j)) + 0.5) / uCells) / uOut);
                    }
                }
            }
            gl_FragColor = vec4(total / (uCells.x * uCells.y), 1.0);
        }
    """

    /** `ve_blur`: the centre and pairs of taps, each pair one bilinear read on both sides, along [uDir]. */
    val GAUSSIAN = """
        $HEAD
        uniform vec2 uDir;
        uniform float uCenter;
        uniform float uOffsets[$TAPS];
        uniform float uWeights[$TAPS];
        uniform float uCount;
        void main() {
            vec2 uv = (pixel() + 0.5) / uOut;
            vec3 result = read(uv) * uCenter;
            for (int i = 0; i < $TAPS; i++) {
                if (float(i) >= uCount) break;
                vec2 d = uDir * uOffsets[i];
                result += (read(uv + d) + read(uv - d)) * uWeights[i];
            }
            gl_FragColor = vec4(result, 1.0);
        }
    """

    /** `ve_bilateral`: up to ten taps each side at whole output pixels along [uDir], weighted by distance and by sRGB difference. */
    val BILATERAL = """
        $HEAD
        uniform vec2 uDir;
        uniform float uSigma;
        uniform float uRadius;
        void main() {
            vec2 uv = (pixel() + 0.5) / uOut;
            vec3 center = read(uv);
            vec3 reference = srgb(center);
            vec3 result = vec3(0.0);
            float total = 0.0;
            for (int k = -10; k <= 10; k++) {
                float i = float(k);
                if (abs(i) > uRadius) continue;
                vec3 c = read(uv + uDir * i / uOut);
                vec3 d = srgb(c) - reference;
                float w = exp(-i * i / (2.0 * uSigma * uSigma) - dot(d, d) / 0.02);
                result += c * w;
                total += w;
            }
            gl_FragColor = vec4(result / max(total, 1e-6), 1.0);
        }
    """

    /** Chroma Key's matte, `ve_keyAlpha`, in every channel: the colour difference against the screen [uScreen]. */
    val KEY = """
        $HEAD
        uniform vec3 uScreen;
        uniform vec4 uKey; // screen type, strength, balance, blue screen
        uniform vec2 uClip;
        void main() {
            vec2 uv = (pixel() + 0.5) / uOut;
            vec3 c = read(uv);
            bool blue = uKey.w > 0.5;
            float key = blue ? c.b : c.g;
            float other = blue ? c.g : c.b;
            float sk = blue ? uScreen.b : uScreen.g;
            float so = blue ? uScreen.g : uScreen.b;
            float balance = uKey.z;
            bool even = abs(balance - 0.5) < 0.0001;
            float d = key - (even ? max(c.r, other) : balance * c.r + (1.0 - balance) * other);
            float ds = sk - (even ? max(uScreen.r, so) : balance * uScreen.r + (1.0 - balance) * so);
            float a = 1.0 - clamp(uKey.y * d / max(ds, 0.0001), 0.0, 1.0);
            a = clamp((a - uClip.x) / max(0.0001, uClip.y - uClip.x), 0.0, 1.0);
            gl_FragColor = vec4(vec3(a), 1.0);
        }
    """

    /** `ve_isolationAlpha` (Metal:591-597): how much of its colour each pixel keeps, by hue, for the temporal matte. */
    val ISOLATION_ALPHA = """
        $HEAD
        uniform vec3 uColor;
        uniform vec2 uRange; // degrees, softness
        vec3 hsv(vec3 c) {
            float hi = max(c.r, max(c.g, c.b));
            float lo = min(c.r, min(c.g, c.b));
            float d = hi - lo;
            float h = 0.0;
            if (d > 1e-6) {
                if (hi == c.r) h = mod((c.g - c.b) / d + 6.0, 6.0);
                else if (hi == c.g) h = (c.b - c.r) / d + 2.0;
                else h = (c.r - c.g) / d + 4.0;
            }
            return vec3(h / 6.0, hi > 1e-6 ? d / hi : 0.0, hi);
        }
        float hueDistance(float a, float b) { float d = abs(a - b); return min(d, 1.0 - d); }
        void main() {
            vec3 h = hsv(srgb(at(pixel())));
            vec3 target = hsv(srgb(uColor));
            float r = uRange.x / 360.0;
            float soft = max(0.001, uRange.y * 0.1);
            float w = (1.0 - smoothstep(r, r + soft, hueDistance(h.x, target.x))) * smoothstep(0.08, 0.2, h.y);
            gl_FragColor = vec4(vec3(w), 1.0);
        }
    """

    /** `ve_morphology`: the largest (growing) or smallest value along [uDir] up to [uReach] pixels. */
    val MORPHOLOGY = """
        $HEAD
        uniform vec2 uDir;
        uniform float uReach;
        uniform float uGrow;
        void main() {
            vec2 uv = (pixel() + 0.5) / uOut;
            float a = read(uv).r;
            float r = floor(uReach);
            for (int k = -64; k <= 64; k++) {
                float i = float(k);
                if (abs(i) > r) continue;
                float b = read(uv + uDir * i).r;
                a = uGrow > 0.5 ? max(a, b) : min(a, b);
            }
            for (int k = -1; k <= 1; k += 2) {
                float b = read(uv + uDir * uReach * float(k)).r;
                a = uGrow > 0.5 ? max(a, b) : min(a, b);
            }
            gl_FragColor = vec4(vec3(a), 1.0);
        }
    """

    /**
     * The person mask [uTex] (its value in the first channel, rows from the bottom as Media3's)
     * laid on the movie's frame: [uToMask] takes a pixel's centre on the frame to a position
     * 0…1 on the mask, from the top left; no person outside it.
     */
    val MASK_PLACE = """
        $HEAD
        uniform mat3 uToMask;
        void main() {
            vec2 m = (uToMask * vec3(pixel() + 0.5, 1.0)).xy;
            float a = m.x < 0.0 || m.x > 1.0 || m.y < 0.0 || m.y > 1.0 ? 0.0 : texture2D(uTex, vec2(m.x, 1.0 - m.y)).r;
            gl_FragColor = vec4(vec3(a), 1.0);
        }
    """

    /**
     * `ve_guidedStats` ([app.takes.core.MaskRefinement.guidedStats]): over the square of
     * `2r + 1` output pixels, the means of the guide's perceptual luma (the sRGB encoding of
     * [uTex]'s linear luma) and of the mask [uMask], of their squares and product, both read
     * between pixels; `a` and `b` in the first two channels.
     */
    val GUIDED_STATS = """
        $HEAD
        uniform sampler2D uMask;
        uniform float uRadius;
        float perceptual(vec2 uv) { return srgb(vec3(luma(read(uv)))).x; }
        void main() {
            vec2 q = pixel();
            float si = 0.0;
            float sp = 0.0;
            float sii = 0.0;
            float sip = 0.0;
            float n = 0.0;
            for (int y = -6; y <= 6; y++) {
                if (abs(float(y)) > uRadius) continue;
                for (int x = -6; x <= 6; x++) {
                    if (abs(float(x)) > uRadius) continue;
                    vec2 uv = (q + vec2(float(x), float(y)) + 0.5) / uOut;
                    float i = perceptual(uv);
                    float p = texture2D(uMask, vec2(uv.x, 1.0 - uv.y)).r;
                    si += i;
                    sp += p;
                    sii += i * i;
                    sip += i * p;
                    n += 1.0;
                }
            }
            si /= n;
            sp /= n;
            sii /= n;
            sip /= n;
            float a = (sip - si * sp) / (max(0.0, sii - si * si) + 0.001);
            gl_FragColor = vec4(a, sp - a * si, 0.0, 1.0);
        }
    """

    /**
     * `ve_guidedApply` and, for a person, `ve_personCoverage`: `clamp(a · i + b, 0, 1)` with the
     * coefficients [uCoefficients] read between their pixels, then `smoothstep(black, white, m)`
     * when [uCoverage].z is 1. [app.takes.core.MaskRefinement.guidedApply], `personCoverage`.
     */
    val GUIDED_APPLY = """
        $HEAD
        uniform sampler2D uCoefficients;
        uniform vec3 uCoverage;
        void main() {
            vec2 uv = (pixel() + 0.5) / uOut;
            vec2 ab = texture2D(uCoefficients, vec2(uv.x, 1.0 - uv.y)).rg;
            float m = clamp(ab.x * srgb(vec3(luma(read(uv)))).x + ab.y, 0.0, 1.0);
            if (uCoverage.z > 0.5) m = smoothstep(uCoverage.x, uCoverage.y, m);
            gl_FragColor = vec4(vec3(m), 1.0);
        }
    """

    /**
     * Chroma Key's person hint on its matte [uTex] (`ve_keyAlpha`, Metal:474-479,
     * [app.takes.core.VideoEffectFormulasC.hinted]): cleared where the grown person [uDilated] is
     * under 0.005, made whole where the shrunk person [uEroded] passes 0.995 and the key keeps
     * more than 0.2.
     */
    val HINT = """
        $HEAD
        uniform sampler2D uDilated;
        uniform sampler2D uEroded;
        void main() {
            vec2 uv = (pixel() + 0.5) / uOut;
            float a = read(uv).r;
            vec2 t = vec2(uv.x, 1.0 - uv.y);
            if (texture2D(uDilated, t).r < 0.005) a = 0.0;
            if (texture2D(uEroded, t).r > 0.995 && a > 0.2) a = 1.0;
            gl_FragColor = vec4(vec3(a), 1.0);
        }
    """

    /**
     * `ve_bgWeight` (Metal:491-501, [app.takes.core.AutoBackgroundFormulas.backgroundWeight]):
     * what is known of the background at this smaller size, the picture [uTex] times one minus
     * the person [uMask], or with [uWeightOnly] that weight alone.
     */
    val BACKGROUND_WEIGHT = """
        $HEAD
        uniform sampler2D uMask;
        uniform float uWeightOnly;
        void main() {
            vec2 uv = (pixel() + 0.5) / uOut;
            float w = 1.0 - texture2D(uMask, vec2(uv.x, 1.0 - uv.y)).r;
            gl_FragColor = vec4(uWeightOnly > 0.5 ? vec3(w) : read(uv) * w, 1.0);
        }
    """

    /**
     * `ve_bgNormalize` ([app.takes.core.AutoBackgroundFormulas.normalize]): the blurred known
     * background [uTex] over its blurred weight [uWeight] where that passes 0.001, else the
     * picture [uFallback] (the chain's, sRGB-encoded) there.
     */
    val BACKGROUND_NORMALIZE = """
        $HEAD
        uniform sampler2D uWeight;
        uniform sampler2D uFallback;
        void main() {
            vec2 uv = (pixel() + 0.5) / uOut;
            vec2 t = vec2(uv.x, 1.0 - uv.y);
            float a = texture2D(uWeight, t).r;
            gl_FragColor = vec4(a > 0.001 ? read(uv) / a : lin(clamp(texture2D(uFallback, t).rgb, 0.0, 1.0)), 1.0);
        }
    """

    /**
     * Studio and, with [uWindow], Window ([app.takes.core.AutoBackgroundFormulas.studioBackground],
     * Assets:604-613): a radial light at 30 % across and 30 % down falling to the dark at 80 % of
     * the width, in linear light; Window's two panes. The colours come linear.
     */
    val STUDIO = """
        $HEAD
        uniform vec3 uLight;
        uniform vec3 uDark;
        uniform vec3 uPane;
        uniform float uWindow;
        void main() {
            vec2 q = pixel();
            float w = uOut.x;
            float h = uOut.y;
            float x = q.x + 0.5;
            float y = h - (q.y + 0.5);
            bool pane = uWindow > 0.5 && y >= h * 0.18 && y < h * 0.18 + h * 0.65 &&
                ((x >= w * 0.64 && x < w * 0.64 + w * 0.1) || (x >= w * 0.80 && x < w * 0.80 + w * 0.1));
            float t = clamp(length(vec2(x - w * 0.3, y - h * 0.7)) / (w * 0.8), 0.0, 1.0);
            gl_FragColor = vec4(pane ? uPane : mix(uLight, uDark, t), 1.0);
        }
    """

    /** An Image background: the picture [uTex] scaled by [uPlace].x, its top left at [uPlace].yz pixels, black outside it. */
    val PLACE = """
        $HEAD
        uniform vec3 uPlace;
        void main() {
            vec2 q = pixel();
            vec2 p = (q + 0.5 - uPlace.yz) / uPlace.x / uIn;
            gl_FragColor = vec4(p.x < 0.0 || p.x > 1.0 || p.y < 0.0 || p.y > 1.0 ? vec3(0.0) : read(p), 1.0);
        }
    """

    /**
     * Sixty-four reads of the picture where `ve_screenReference` looks, in
     * linear light, to be read back in eight bits: each in two pixels, the
     * whole 255ths and then what is left of them, for sixteen bits a channel.
     */
    val BORDER = """
        $HEAD
        void main() {
            float pixel = floor(gl_FragCoord.x);
            float i = floor(pixel / 2.0);
            float t = (floor(i / 4.0) + 0.5) / 16.0;
            float side = mod(i, 4.0);
            vec2 at = side < 0.5 ? vec2(t, 0.02) : side < 1.5 ? vec2(t, 0.98) : side < 2.5 ? vec2(0.02, t) : vec2(0.98, t);
            vec3 c = clamp(read(at), 0.0, 1.0) * 255.0;
            vec3 whole = floor(c);
            gl_FragColor = vec4(mod(pixel, 2.0) < 0.5 ? whole / 255.0 : c - whole, 1.0);
        }
    """

    // Night fix Fast works on the raw frame's BT.709-encoded values (VideoEffects.metal:640-727), read as they are.
    private const val REC709 = """
        vec3 encode709(vec3 x) { x = max(x, 0.0); return mix(4.5 * x, 1.099 * pow(x, vec3(0.45)) - 0.099, step(vec3(0.018), x)); }
        vec3 decode709(vec3 x) { x = max(x, 0.0); return mix(x / 4.5, pow((x + 0.099) / 1.099, vec3(1.0 / 0.45)), step(vec3(0.081), x)); }
    """

    /** `nf_adjust` with the fused picture the raw one: [uFix] is the reach of the taps, the mixing, the noise expected and the gain. */
    val NIGHT_ADJUST = """
        $HEAD
        $REC709
        uniform vec4 uFix;
        void main() {
            vec2 q = pixel();
            vec2 uv = (q + 0.5) / uOut;
            vec3 c = at(q);
            vec3 mean = vec3(0.0);
            vec3 square = vec3(0.0);
            for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) {
                vec3 n = exact(uv + vec2(float(i), float(j)) * uFix.x / uIn);
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

    /** `nf_chroma` along [uDir]: [uChroma] is the radius, the blend and the reduction. */
    val NIGHT_CHROMA = """
        $HEAD
        uniform vec2 uDir;
        uniform vec3 uChroma;
        void main() {
            vec2 q = pixel();
            vec3 c = at(q);
            float y = luma(c);
            float r = 0.0;
            float b = 0.0;
            float total = 0.0;
            for (int i = -30; i <= 30; i++) {
                float k = float(i);
                if (abs(k) > uChroma.x) continue;
                vec3 n = at(q + uDir * k);
                float ny = luma(n);
                float d = ny - y;
                float s = k / max(1.0, uChroma.x * 0.65);
                float w = exp(-d * d / (0.0012 * max(1.0, uChroma.z))) * exp(-0.5 * s * s);
                r += (n.r - ny) * w;
                b += (n.b - ny) * w;
                total += w;
            }
            float red = y + mix(c.r - y, r / max(total, 1e-6), uChroma.y);
            float blue = y + mix(c.b - y, b / max(total, 1e-6), uChroma.y);
            gl_FragColor = vec4(max(vec3(red, (y - 0.2126 * red - 0.0722 * blue) / 0.7152, blue), 0.0), 1.0);
        }
    """

    /** `nf_mix`: the restored picture [uRestored] over the raw one by [uMix], in linear light. */
    val NIGHT_MIX = """
        $HEAD
        $REC709
        uniform sampler2D uRestored;
        uniform float uMix;
        void main() {
            vec2 q = pixel();
            vec3 raw = at(q);
            vec3 restored = texture2D(uRestored, vec2((q.x + 0.5009765625) / uOut.x, 1.0 - (q.y + 0.4990234375) / uOut.y)).rgb;
            gl_FragColor = vec4(encode709(mix(decode709(raw), decode709(restored), uMix)), 1.0);
        }
    """

    val VERTEX = """
        attribute vec4 aFramePosition;
        varying vec2 vTex;
        void main() { gl_Position = aFramePosition; vTex = aFramePosition.xy * 0.5 + 0.5; }
    """
}

/** A picture a pass draws into: a texture of its own and its framebuffer. */
internal class EffectTarget(val width: Int, val height: Int, val precise: Boolean) {
    val texture: Int = GlUtil.createTexture(width, height, precise)
    val framebuffer: Int = GlUtil.createFboForTexture(texture)

    fun release() {
        GlUtil.deleteFbo(framebuffer)
        GlUtil.deleteTexture(texture)
    }
}

/** A picture a pass reads: a texture, its size, and whether it holds sRGB-encoded colour (the chain's own) or linear light. */
internal data class EffectSource(val texture: Int, val width: Int, val height: Int, val encoded: Boolean) {
    constructor(target: EffectTarget) : this(target.texture, target.width, target.height, encoded = false)
}

/**
 * The multi-pass work of one chain, on the GL thread of the export: the
 * targets by name, reused from frame to frame while their size holds, and the
 * pass programs. Half-float targets are tried once; where the device cannot
 * draw into them, every target is eight bits.
 */
internal class EffectGpu {
    private val targets = HashMap<String, EffectTarget>()
    private val programs = HashMap<String, GlProgram>()
    private var precise: Boolean? = null

    fun target(key: String, width: Int, height: Int, eightBit: Boolean = false): EffectTarget {
        targets[key]?.let { if (it.width == width && it.height == height && (!eightBit || !it.precise)) return it else it.release() }
        val made = when {
            eightBit -> EffectTarget(width, height, false)
            else -> when (precise) {
            null -> halfFloat(width, height)?.also { precise = true } ?: EffectTarget(width, height, false).also { precise = false }
            else -> EffectTarget(width, height, precise == true)
            }
        }
        targets[key] = made
        return made
    }

    /** A half-float target, or null where the device cannot make one or draw into it. */
    private fun halfFloat(width: Int, height: Int): EffectTarget? {
        val made = try {
            EffectTarget(width, height, true)
        } catch (_: GlUtil.GlException) {
            return null
        }
        val bound = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, bound, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, made.framebuffer)
        val complete = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bound[0])
        if (complete) return made
        made.release()
        return null
    }

    fun program(fragment: String): GlProgram = programs.getOrPut(fragment) {
        GlProgram(EffectPassShaders.VERTEX, fragment).also {
            it.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }
    }

    /** Draws [fragment] reading [source] into the target [key] of [width] × [height]. [set] gives the program's own uniforms. */
    fun draw(fragment: String, source: EffectSource, key: String, width: Int, height: Int, set: (GlProgram) -> Unit, arrays: (GlProgram) -> Unit = {}, eightBit: Boolean = false): EffectTarget {
        val target = target(key, width, height, eightBit)
        drawInto(fragment, source, target.framebuffer, width, height, set, arrays)
        return target
    }

    /** Draws [fragment] reading [source] into [framebuffer], [width] × [height]. */
    fun drawInto(fragment: String, source: EffectSource, framebuffer: Int, width: Int, height: Int, set: (GlProgram) -> Unit, arrays: (GlProgram) -> Unit = {}) {
        val program = program(fragment)
        GlUtil.focusFramebufferUsingCurrentContext(framebuffer, width, height)
        program.use()
        // A pass that draws without reading a picture (Studio, Window) has no uTex once compiled.
        if (program.getUniformLocation("uTex") != -1) program.setSamplerTexIdUniform("uTex", source.texture, 0)
        program.setFloatsUniformIfPresent("uEncoded", floatArrayOf(if (source.encoded) 1f else 0f))
        program.setFloatsUniformIfPresent("uIn", floatArrayOf(source.width.toFloat(), source.height.toFloat()))
        program.setFloatsUniformIfPresent("uOut", floatArrayOf(width.toFloat(), height.toFloat()))
        set(program)
        program.bindAttributesAndUniforms()
        // Arrays after the program's own binding, which knows only their first element.
        arrays(program)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError()
    }

    /** [VideoEffectPasses.highlights] of [source] at [width] × [height], into [key]. */
    fun highlights(source: EffectSource, key: String, width: Int, height: Int, threshold: Double, softness: Double): EffectTarget {
        val rx = source.width.toDouble() / width
        val ry = source.height.toDouble() / height
        val integral = rx == kotlin.math.floor(rx) && ry == kotlin.math.floor(ry)
        val step = floatArrayOf(maxOf(1, kotlin.math.ceil(rx).toInt()).toFloat(), maxOf(1, kotlin.math.ceil(ry).toInt()).toFloat())
        return draw(EffectPassShaders.HIGHLIGHTS, source, key, width, height, { p ->
            p.setFloatsUniform("uStep", step)
            p.setFloatsUniform("uIntegral", floatArrayOf(if (integral) 1f else 0f))
            p.setFloatsUniform("uThreshold", floatArrayOf(threshold.toFloat(), softness.toFloat()))
        })
    }

    /** [VideoEffectPasses.reduce] of [source] to [width] × [height], into [key]. */
    fun reduce(source: EffectSource, key: String, width: Int, height: Int): EffectTarget {
        val rx = source.width.toDouble() / width
        val ry = source.height.toDouble() / height
        val cx = maxOf(1.0, kotlin.math.ceil(rx)).toInt()
        val cy = maxOf(1.0, kotlin.math.ceil(ry)).toInt()
        val paired = rx == kotlin.math.floor(rx) && ry == kotlin.math.floor(ry) && cx % 2 == 0 && cy % 2 == 0
        return draw(EffectPassShaders.REDUCE, source, key, width, height, { p ->
            p.setFloatsUniform("uCells", floatArrayOf(cx.toFloat(), cy.toFloat()))
            p.setFloatsUniform("uPaired", floatArrayOf(if (paired) 1f else 0f))
        })
    }

    /** One [VideoEffectPasses.gaussian] pass of [source], at its size, into [key]. */
    fun gaussian(source: EffectSource, key: String, kernel: GaussianKernel, horizontal: Boolean): EffectTarget {
        val count = minOf(EffectPassShaders.TAPS, kernel.offsets.size)
        val offsets = FloatArray(EffectPassShaders.TAPS).also { for (i in 0 until count) it[i] = kernel.offsets[i].toFloat() }
        val weights = FloatArray(EffectPassShaders.TAPS).also { for (i in 0 until count) it[i] = kernel.weights[i].toFloat() }
        val direction = if (horizontal) floatArrayOf(1f / source.width, 0f) else floatArrayOf(0f, 1f / source.height)
        return draw(EffectPassShaders.GAUSSIAN, source, key, source.width, source.height, { p ->
            p.setFloatsUniform("uDir", direction)
            p.setFloatsUniform("uCenter", floatArrayOf(kernel.center.toFloat()))
            p.setFloatsUniform("uCount", floatArrayOf(count.toFloat()))
        }, { p ->
            GLES20.glUniform1fv(p.getUniformLocation("uOffsets"), EffectPassShaders.TAPS, offsets, 0)
            GLES20.glUniform1fv(p.getUniformLocation("uWeights"), EffectPassShaders.TAPS, weights, 0)
        })
    }

    /**
     * [VideoEffectPasses.blur] of [source] by [sigma] of its pixels, to a
     * picture of [width] × [height] at most, into [key]: reduced first when
     * the plan is smaller, then across, then down (or across again).
     */
    fun blur(source: EffectSource, key: String, sigma: Double, width: Int = source.width, height: Int = source.height, horizontalOnly: Boolean = false): EffectTarget {
        val plan = VideoEffectPasses.blurPlan(source.width, source.height, sigma, width, height)
        val start = if (plan.reduced) EffectSource(reduce(source, "$key.reduced", plan.width, plan.height)) else source
        val across = gaussian(start, "$key.across", plan.kernel, true)
        return gaussian(EffectSource(across), key, plan.kernel, horizontalOnly)
    }

    /** Two [VideoEffectQuantization.bilateral] passes of [source] by [plan], across then down, into [key]. */
    fun bilateral(source: EffectSource, key: String, plan: VideoEffectQuantization.SmoothingPlan): EffectTarget {
        val sigma = maxOf(.5, plan.sigma)
        val radius = minOf(10, kotlin.math.ceil(sigma * 2).toInt())
        fun pass(from: EffectSource, into: String, horizontal: Boolean) = draw(EffectPassShaders.BILATERAL, from, into, plan.width, plan.height, { p ->
            p.setFloatsUniform("uDir", if (horizontal) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f))
            p.setFloatsUniform("uSigma", floatArrayOf(sigma.toFloat()))
            p.setFloatsUniform("uRadius", floatArrayOf(radius.toFloat()))
        })
        return pass(EffectSource(pass(source, "$key.across", true)), key, false)
    }

    /** Chroma Key's matte of [source] against [screen] (linear), into [key]: [VideoEffectFormulasC.keyAlpha] at every pixel. */
    fun key(source: EffectSource, key: String, screen: EffectVector, blue: Boolean, strength: Double, balance: Double, blackClip: Double, whiteClip: Double): EffectTarget =
        draw(EffectPassShaders.KEY, source, key, source.width, source.height, { p ->
            p.setFloatsUniform("uScreen", floatArrayOf(screen.x.toFloat(), screen.y.toFloat(), screen.z.toFloat()))
            p.setFloatsUniform("uKey", floatArrayOf(0f, strength.toFloat(), balance.toFloat(), if (blue) 1f else 0f))
            p.setFloatsUniform("uClip", floatArrayOf(blackClip.toFloat(), whiteClip.toFloat()))
        })

    /** Color Isolation's weight at every pixel of [source] ([app.takes.core.VideoEffectFormulas.colorIsolationWeight]), into [key]. */
    fun isolationAlpha(source: EffectSource, key: String, color: EffectVector, range: Double, softness: Double): EffectTarget =
        draw(EffectPassShaders.ISOLATION_ALPHA, source, key, source.width, source.height, { p ->
            p.setFloatsUniform("uColor", floatArrayOf(color.x.toFloat(), color.y.toFloat(), color.z.toFloat()))
            p.setFloatsUniform("uRange", floatArrayOf(range.toFloat(), softness.toFloat()))
        })

    /**
     * [VideoEffectFormulasC.morph]: the matte [source] grown (or shrunk, below zero) by [radius] pixels of a picture
     * of [width] × [height], across then down, into [key]: at a smaller size each pass reads the source between its
     * pixels along whole output pixels, as `ve_morphology` does into a smaller texture (Metal:481-490).
     */
    fun morph(source: EffectSource, key: String, radius: Double, width: Int = source.width, height: Int = source.height): EffectTarget {
        fun pass(from: EffectSource, into: String, horizontal: Boolean) = draw(EffectPassShaders.MORPHOLOGY, from, into, width, height, { p ->
            p.setFloatsUniform("uDir", if (horizontal) floatArrayOf(1f / width, 0f) else floatArrayOf(0f, 1f / height))
            p.setFloatsUniform("uReach", floatArrayOf(minOf(64.0, kotlin.math.abs(radius)).toFloat()))
            p.setFloatsUniform("uGrow", floatArrayOf(if (radius >= 0) 1f else 0f))
        })
        return pass(EffectSource(pass(source, "$key.across", true)), key, false)
    }

    /** An Image background: [picture] (sRGB-encoded) placed on a picture of [width] × [height] by [scale] with its top left at [left], [top], into [key]. */
    fun place(picture: EffectSource, key: String, width: Int, height: Int, scale: Double, left: Double, top: Double): EffectTarget =
        draw(EffectPassShaders.PLACE, picture, key, width, height, { p -> p.setFloatsUniform("uPlace", floatArrayOf(scale.toFloat(), left.toFloat(), top.toFloat())) })

    /**
     * The person [mask] laid on a picture of [width] × [height], into [key]: [toMask] takes a
     * pixel of that picture to a position 0…1 on the mask (rows of the matrix: x, y).
     */
    fun placeMask(mask: EffectSource, key: String, width: Int, height: Int, toMask: app.takes.core.Affine): EffectTarget =
        draw(EffectPassShaders.MASK_PLACE, mask, key, width, height, { p ->
            // Column-major, as GL takes a mat3: the columns are (a, b, 0), (c, d, 0), (tx, ty, 1) for x' = a·x + c·y + tx.
            p.setFloatsUniform("uToMask", floatArrayOf(
                toMask.a.toFloat(), toMask.b.toFloat(), 0f,
                toMask.c.toFloat(), toMask.d.toFloat(), 0f,
                toMask.tx.toFloat(), toMask.ty.toFloat(), 1f,
            ))
        })

    /**
     * The original's `refinedMask` ([app.takes.core.MaskRefinement.refinedMask]) of [mask], at
     * [guide]'s size, on [guide] (the chain's picture): the guided filter's coefficients at the
     * plan's size, blurred, applied at the guide's size, the coverage clip for a [person], then
     * grown or shrunk by [grow] pixels at 1080. Into [key].
     */
    fun refined(mask: EffectSource, guide: EffectSource, key: String, softness: Double, grow: Double = 0.0, person: Boolean = false, lite: Boolean = false): EffectTarget {
        val plan = app.takes.core.MaskRefinement.plan(guide.width, guide.height, softness, lite, person)
        val stats = draw(EffectPassShaders.GUIDED_STATS, guide, "$key.stats", plan.width, plan.height, { p ->
            p.setSamplerTexIdUniform("uMask", mask.texture, 1)
            p.setFloatsUniform("uRadius", floatArrayOf(minOf(plan.radius, app.takes.core.MaskRefinement.GUIDED_RADIUS_LIMIT).toFloat()))
        })
        val smooth = blur(EffectSource(stats), "$key.smooth", plan.sigma)
        val applied = draw(EffectPassShaders.GUIDED_APPLY, guide, if (grow != 0.0) "$key.applied" else key, guide.width, guide.height, { p ->
            p.setSamplerTexIdUniform("uCoefficients", smooth.texture, 1)
            p.setFloatsUniform("uCoverage", floatArrayOf(plan.black.toFloat(), plan.white.toFloat(), if (person) 1f else 0f))
        })
        if (grow == 0.0) return applied
        return morph(EffectSource(applied), key, grow * minOf(guide.width, guide.height) / 1080)
    }

    /** Chroma Key's [matte] bounded by the person: grown [dilated] and shrunk [eroded] masks. Into [key]. */
    fun hint(matte: EffectSource, dilated: EffectSource, eroded: EffectSource, key: String): EffectTarget =
        draw(EffectPassShaders.HINT, matte, key, matte.width, matte.height, { p ->
            p.setSamplerTexIdUniform("uDilated", dilated.texture, 1)
            p.setSamplerTexIdUniform("uEroded", eroded.texture, 2)
        })

    /**
     * The original's `backgroundEstimate` without fill ([app.takes.core.AutoBackgroundFormulas.backgroundEstimate]):
     * the background known around the person [mask] in [source], blurred by 4 pixels at 1080 at a quarter of the
     * size, over its blurred weight; the picture itself where nothing of the background is known. Into [key].
     */
    fun backgroundEstimate(source: EffectSource, mask: EffectSource, key: String): EffectTarget {
        val width = VideoEffectPasses.quarter(source.width)
        val height = VideoEffectPasses.quarter(source.height)
        fun weight(only: Boolean, into: String) = draw(EffectPassShaders.BACKGROUND_WEIGHT, source, into, width, height, { p ->
            p.setSamplerTexIdUniform("uMask", mask.texture, 1)
            p.setFloatsUniform("uWeightOnly", floatArrayOf(if (only) 1f else 0f))
        })
        val sigma = 4.0 * minOf(source.width, source.height) / 1080
        val color = blur(EffectSource(weight(false, "$key.color")), "$key.color.blur", sigma)
        val known = blur(EffectSource(weight(true, "$key.weight")), "$key.weight.blur", sigma)
        return draw(EffectPassShaders.BACKGROUND_NORMALIZE, EffectSource(color), key, width, height, { p ->
            p.setSamplerTexIdUniform("uWeight", known.texture, 1)
            p.setSamplerTexIdUniform("uFallback", source.texture, 2)
        })
    }

    /** Studio, or with [window] Window, at [width] × [height]. Into [key]. */
    fun studio(source: EffectSource, key: String, width: Int, height: Int, window: Boolean): EffectTarget {
        fun linear(c: app.takes.core.EffectRGB) = app.takes.core.EffectColorMath.linear(c).let { floatArrayOf(it.x.toFloat(), it.y.toFloat(), it.z.toFloat()) }
        return draw(EffectPassShaders.STUDIO, source, key, width, height, { p ->
            p.setFloatsUniform("uLight", linear(app.takes.core.AutoBackgroundFormulas.STUDIO_LIGHT))
            p.setFloatsUniform("uDark", linear(app.takes.core.AutoBackgroundFormulas.STUDIO_DARK))
            p.setFloatsUniform("uPane", linear(app.takes.core.AutoBackgroundFormulas.WINDOW_PANE))
            p.setFloatsUniform("uWindow", floatArrayOf(if (window) 1f else 0f))
        })
    }

    /** The 64 colours on the border of [source] that `ve_screenReference` reads, in its order, linear. */
    fun border(source: EffectSource): List<EffectVector> {
        draw(EffectPassShaders.BORDER, source, "border", 128, 1, {}, eightBit = true)
        val bytes = java.nio.ByteBuffer.allocateDirect(128 * 4).order(java.nio.ByteOrder.nativeOrder())
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        GLES20.glReadPixels(0, 0, 128, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
        GlUtil.checkGlError()
        fun level(sample: Int, channel: Int): Double {
            val whole = bytes.get(sample * 8 + channel).toInt() and 0xff
            val rest = bytes.get(sample * 8 + 4 + channel).toInt() and 0xff
            return (whole + rest / 255.0) / 255.0
        }
        return (0 until 64).map { EffectVector(level(it, 0), level(it, 1), level(it, 2)) }
    }

    fun release() {
        targets.values.forEach { it.release() }
        targets.clear()
        programs.values.forEach { it.delete() }
        programs.clear()
    }
}
