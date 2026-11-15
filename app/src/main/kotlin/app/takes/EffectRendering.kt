@file:OptIn(UnstableApi::class)

package app.takes

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.opengl.GLES20
import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import app.takes.core.EffectFrame
import app.takes.core.EffectUniforms
import app.takes.core.TimelineClip
import app.takes.core.VideoEffectID
import app.takes.core.VideoEffectSettings
import app.takes.core.VideoEffectTime
import app.takes.core.VideoEffectFormulas
import app.takes.core.VideoEffectGrain
import app.takes.core.VideoEffectShaderMath
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * The shader of the video effects, written once and worded for the export's
 * OpenGL and the preview's AGSL: one pass of one effect, a port of the
 * original's `ve_effect` and `effectPoint` (`YouRec/VideoEffects/VideoEffects.metal:44-86`,
 * `:304-418`) for the effects of the first batch. It reads and writes
 * sRGB-encoded pictures and works in linear light, as the original does,
 * reading between pixels in linear light;
 * positions are `uv`, 0…1 from the top left, and `q` the whole pixel.
 *
 * The block of numbers is the core's ([EffectUniforms]): `uKind` the
 * original's shader index, `uHead` intensity, frame, time and frame rate,
 * `uP0`…`uP11` the first twelve parameter slots.
 */
object EffectShader {
    /** The effects drawn: the first batch, by their reference formulas in the core. */
    val drawn: Set<VideoEffectID> = setOf(
        VideoEffectID.ColorCorrection, VideoEffectID.SilentTint, VideoEffectID.ThreeStrip, VideoEffectID.BleachBypass,
        VideoEffectID.DayForNight, VideoEffectID.Noir, VideoEffectID.Expressionism, VideoEffectID.ColorIsolation,
        VideoEffectID.RgbOffset, VideoEffectID.NegativeStrobe, VideoEffectID.Kaleidoscope,
    )

    /** How many parameter slots the shader takes. */
    const val SLOTS = 12

    private val UNIFORMS = "uniform float uKind;\nuniform vec4 uHead;\nuniform vec2 uSize;\n" + (0 until SLOTS).joinToString("\n") { "uniform vec4 uP$it;" }

    private const val HELPERS = """
        const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
        float luma(vec3 c) { return dot(c, LUMA); }
        vec3 above(vec3 c, float edge) { return step(vec3(edge), c) * (1.0 - step(c, vec3(edge))); }
        vec3 srgb(vec3 c) { return mix(c * 12.92, 1.055 * pow(max(c, 0.0), vec3(1.0 / 2.4)) - 0.055, above(c, 0.0031308)); }
        vec3 lin(vec3 c) { return mix(c / 12.92, pow(max((c + 0.055) / 1.055, 0.0), vec3(2.4)), above(c, 0.04045)); }
        float srgb1(float v) { return srgb(vec3(v)).x; }
        float lin1(float v) { return lin(vec3(v)).x; }
        vec3 lab(vec3 c) {
            vec3 v = pow(max(vec3(dot(c, vec3(0.4122214708, 0.5363325363, 0.0514459929)), dot(c, vec3(0.2119034982, 0.6806995451, 0.1073969566)), dot(c, vec3(0.0883024619, 0.2817188376, 0.6299787005))), 0.0), vec3(1.0 / 3.0));
            return vec3(dot(v, vec3(0.2104542553, 0.793617785, -0.0040720468)), dot(v, vec3(1.9779984951, -2.428592205, 0.4505937099)), dot(v, vec3(0.0259040371, 0.7827717662, -0.808675766)));
        }
        vec3 unlab(vec3 c) {
            vec3 v = vec3(c.x + 0.3963377774 * c.y + 0.2158037573 * c.z, c.x - 0.1055613458 * c.y - 0.0638541728 * c.z, c.x - 0.0894841775 * c.y - 1.291485548 * c.z);
            v = v * v * v;
            return vec3(dot(v, vec3(4.0767416621, -3.3077115913, 0.2309699292)), dot(v, vec3(-1.2684380046, 2.6097574011, -0.3413193965)), dot(v, vec3(-0.0041960863, -0.7034186147, 1.707614701)));
        }
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
        bool near(float a, float b) { return abs(a - b) < 0.5; }
    """

    private const val EFFECTS = """
        float sCurve(float y, float contrast) {
            y = clamp(y, 0.0, 1.0);
            float a = pow(y, contrast);
            float b = pow(1.0 - y, contrast);
            return a / max(a + b, 1e-6);
        }
        float curve(float y, float contrast) { return lin1(clamp((srgb1(y) - 0.5) * contrast + 0.5, 0.0, 1.0)); }
        float reach(float d, float y, float amount) {
            if (d > 0.0) return min(amount, (1.0 - y) / d);
            if (d < 0.0) return min(amount, -y / d);
            return amount;
        }
        vec3 gradeGamut(vec3 c) {
            float y = clamp(luma(c), 0.0, 1.0);
            vec3 delta = c - y;
            return y + delta * max(0.0, reach(delta.r, y, reach(delta.g, y, reach(delta.b, y, 1.0))));
        }
        float gradeShoulder(float value, float maximum) {
            if (maximum <= 1.0 || value <= 0.8) return value;
            float a = (maximum - 0.8) / 0.2;
            float t = (value - 0.8) / (maximum - 0.8);
            return 0.8 + 0.2 * a * t / (1.0 + (a - 1.0) * t);
        }
        float ratioOf(float d, float target) { return d > 0.0 ? d / max(1.0 - target, 1e-7) : -d / max(target, 1e-7); }
        // Metal:251-284.
        vec3 colorCorrection(vec3 c) {
            if (uP0.x == 0.0 && uP1.x == 1.0 && uP2.x == 0.0 && uP3.x == 0.0 && uP4.x == 0.0 && uP5.x == 0.0 && uP7.x == 0.0 && uP8.x == 0.0 && uP9.x == 1.0 && uP10.x == 0.0 && uP11.x == 0.0) return c;
            float temperature = uP7.x;
            float tint = uP8.x;
            vec3 gains = exp2(vec3(0.45 * temperature + 0.2 * tint, -0.2 * tint, -0.45 * temperature + 0.2 * tint));
            c *= gains / dot(gains, LUMA) * exp2(uP0.x);
            float before = luma(c);
            float v = srgb1(max(before, 0.0));
            v = gradeShoulder(v, srgb1(exp2(uP0.x)));
            float contrast = uP1.x;
            float pivot = uP6.x;
            if (contrast != 1.0 && v > 0.0 && v < 1.0) {
                float odds = pivot / (1.0 - pivot);
                v = 1.0 / (1.0 + pow(odds, contrast - 1.0) * pow((1.0 - v) / v, contrast));
            }
            v += uP2.x * v * (1.0 - v) * (1.0 - v);
            v += uP3.x * v * v * (1.0 - v);
            v += 0.12 * uP4.x * pow(max(1.0 - v, 0.0), 4.0) + 0.12 * uP5.x * pow(max(v, 0.0), 4.0);
            v = gradeShoulder(v, 1.0 + 0.12 * uP5.x);
            v = 1.0 - gradeShoulder(1.0 - v, 1.0 - 0.12 * uP4.x);
            float target = lin1(v);
            c = before > 1e-7 ? c * (target / before) : vec3(target);
            vec3 perceptual = lab(max(c, 0.0));
            float chroma = length(perceptual.yz);
            vec3 h = hsv(srgb(max(c, 0.0)));
            float skin = smoothstep(0.025, 0.055, h.x) * (1.0 - smoothstep(0.12, 0.16, h.x)) * smoothstep(0.08, 0.2, h.y);
            float vibrance = uP10.x * (1.0 - clamp(chroma / 0.25, 0.0, 1.0));
            if (vibrance > 0.0) vibrance *= 1.0 - 0.6 * skin;
            if (uP11.x != 0.0 && chroma > 1e-5) {
                float angle = uP11.x * 3.14159265358979 / 180.0;
                float cs = cos(angle);
                float sn = sin(angle);
                perceptual.yz = vec2(cs * perceptual.y - sn * perceptual.z, sn * perceptual.y + cs * perceptual.z);
                c = unlab(perceptual);
                c *= target / max(luma(c), 1e-7);
            }
            c = gradeGamut(c);
            float gain = uP9.x * (1.0 + vibrance);
            if (gain > 1.0) {
                vec3 delta = c - target;
                float ratio = max(ratioOf(delta.r, target), max(ratioOf(delta.g, target), ratioOf(delta.b, target)));
                gain /= sqrt(1.0 + (gain * gain - 1.0) * ratio * ratio);
            }
            c = target + (c - target) * gain;
            return gradeGamut(c);
        }
        // Metal:314-318.
        vec3 silentTint(vec3 c) {
            vec3 dye = vec3(0.541176, 0.415686, 0.270588);
            if (uP0.x < 0.5) dye = vec3(0.243137, 0.435294, 0.658824);
            else if (uP0.x < 1.5) dye = vec3(0.85098, 0.643137, 0.254902);
            else if (uP0.x < 2.5) dye = vec3(0.721569, 0.262745, 0.184314);
            else if (uP0.x < 3.5) dye = vec3(0.431373, 0.560784, 0.305882);
            vec3 tint = lin(dye);
            float l = curve(luma(c), uP2.x);
            return uP1.x < 0.5 ? l * tint : mix(tint * 0.15, vec3(1.0), smoothstep(0.0, 1.0, l));
        }
        // Metal:319-327.
        vec3 threeStrip(vec3 c) {
            vec3 h = hsv(srgb(c));
            float skin = smoothstep(0.03, 0.055, h.x) * (1.0 - smoothstep(0.115, 0.15, h.x)) * smoothstep(0.12, 0.3, h.y);
            vec3 separation = c - (c.yzx + c.zxy) * 0.5;
            vec3 o = c + separation * (uP0.x - 1.0) * (1.0 - 0.65 * skin);
            float red = 1.0 - smoothstep(15.0 / 360.0, 25.0 / 360.0, hueDistance(h.x, 0.0));
            o.yz -= red * uP1.x * 0.15 * (1.0 - skin);
            float l = luma(o);
            float s = l * l * (3.0 - 2.0 * l);
            o += (s - l) * 0.14;
            o *= vec3(1.0 + uP2.x * 0.06, 1.0, 1.0 - uP2.x * 0.06);
            float high = max(o.r, max(o.g, o.b));
            if (high > 0.9) o = 0.9 + (o - 0.9) / (1.0 + max(0.0, high - 0.9));
            return mix(o, c, skin * 0.35);
        }
        // Metal:328.
        vec3 bleachBypass(vec3 c, float y) {
            vec3 overlay = y < 0.5 ? 2.0 * c * y : 1.0 - 2.0 * (1.0 - c) * (1.0 - y);
            vec3 bleached = mix(vec3(y), overlay, 0.5);
            bleached = mix(vec3(luma(bleached)), bleached, 1.0 - 0.3 * uP0.x);
            return mix(c, bleached, uP0.x);
        }
        // Metal:329-332.
        vec3 dayForNight(vec3 c, float y, vec2 uv) {
            vec3 h = hsv(srgb(c));
            float darkness = uP0.x;
            vec3 o = mix(vec3(y), c, 0.4) * exp2(-3.0 * darkness);
            o *= mix(vec3(1.0), vec3(0.55, 0.7, 1.0), uP1.x);
            float sky = (1.0 - smoothstep(0.25, 0.38, uv.y)) * smoothstep(0.5, 0.85, y) * (1.0 - smoothstep(0.08, 0.18, hueDistance(h.x, 0.61)));
            o *= 1.0 - 0.5 * sky;
            if (uP2.x > 0.5) o = mix(o, vec3(y * exp2(-0.7 * darkness)), smoothstep(0.94, 0.99, y) * 0.7);
            return o * (1.0 - 0.15 * smoothstep(0.2, 0.75, length(uv - 0.5)));
        }
        // Metal:333-335, by hue only: a matte of the subject does not exist here, and the picture is left as it is.
        vec3 colorIsolation(vec3 c, float y) {
            if (uP0.x > 0.5) return c;
            vec3 h = hsv(srgb(c));
            vec3 target = hsv(srgb(uP1.xyz));
            float r = uP2.x / 360.0;
            float soft = max(0.001, uP3.x * 0.1);
            float w = (1.0 - smoothstep(r, r + soft, hueDistance(h.x, target.x))) * smoothstep(0.08, 0.2, h.y);
            return mix(vec3(y), c, w);
        }
        vec3 grainy(vec3 c, float noise, float strength) {
            float y = clamp(luma(c), 0.0, 1.0);
            float a = strength * 0.12 * pow(max(0.0, 4.0 * y * (1.0 - y)), 0.7);
            return clamp(c * exp(vec3(noise) * a * 2.0), 0.0, 1.0);
        }
        // Metal:336, with the grain of Metal:137-142 at strength 0.25 and size 1.
        vec3 noir(vec3 c, vec2 q, float pixelScale) {
            float l = sCurve(srgb1(dot(c, vec3(0.4, 0.5, 0.1))), uP0.x);
            l = lin1(l * (1.0 - 2.0 * uP1.x * (1.0 - l)));
            vec3 o = vec3(l);
            if (uP3.x > 0.5) o *= mix(vec3(0.94, 0.97, 1.0), vec3(1.0), l);
            if (uP2.x > 0.5) o = grainy(o, grain(q / pixelScale), 0.25);
            return o;
        }
        // Metal:337-338.
        vec3 expressionism(vec3 c, float y, vec2 uv) {
            float perceptual = srgb1(y);
            float l = sCurve(perceptual, uP0.x);
            l = lin1(mix(l, smoothstep(0.35, 0.65, perceptual), 0.4));
            vec2 d = (uv - 0.5) * 2.0;
            if (uP2.x > 0.5) d.x *= uSize.x / uSize.y;
            float v = clamp(1.0 - pow(clamp(length(d) / 1.25, 0.0, 1.0), 2.5), 0.0, 1.0);
            l *= mix(1.0, v, uP1.x);
            vec3 o = vec3(l);
            if (uP3.x > 0.5) o *= lin(vec3(0.82, 0.84, 0.52));
            return o;
        }
        float roundHalfAway(float x) { return x < 0.0 ? -floor(-x + 0.5) : floor(x + 0.5); }
        // Metal:54-86.
        vec2 kaleidoscope(vec2 uv) {
            float count = clamp(roundHalfAway(uP0.x), 2.0, 9.0);
            vec2 center = vec2(uP1.x, uP2.x);
            vec2 d = (uv - center) * uSize;
            vec2 point = uv;
            if (dot(d, d) >= 1e-8) {
                float axis = uP3.x * (3.14159265358979 / 180.0) - 3.14159265358979 / 2.0;
                float turn = 2.0 * 3.14159265358979;
                float span = turn / count;
                float angle = fract((atan(d.y, d.x) - axis) / turn) * turn;
                float sector = min(floor(angle / span), count - 1.0);
                if (sector > 0.5) {
                    float local = angle - sector * span;
                    if (mod(count, 2.0) < 0.5 && mod(sector, 2.0) > 0.5) local = span - local;
                    float mapped = axis + local;
                    point = center + length(d) * vec2(cos(mapped), sin(mapped)) / uSize;
                }
            }
            if (uP6.x != 0.0) {
                float a = -uP6.x * (3.14159265358979 / 180.0);
                vec2 offset = (point - center) * uSize;
                float cs = cos(a);
                float sn = sin(a);
                point = center + vec2(cs * offset.x - sn * offset.y, sn * offset.x + cs * offset.y) / uSize;
            }
            point -= vec2(uP4.x, uP5.x);
            if (point.x >= 0.0 && point.y >= 0.0 && point.x <= 1.0 && point.y <= 1.0) return point;
            return 1.0 - abs(2.0 * fract(point * 0.5) - 1.0);
        }
        vec3 written(vec3 c) { return clamp(srgb(max(c, 0.0)), 0.0, 1.0); }
        // One pass: the encoded colour of the pixel q, at uv.
        vec3 effectPass(vec2 uv, vec2 q) {
            float intensity = uHead.x;
            if (uKind < -0.5) return written(src(uv));
            if (near(uKind, 22.0)) {
                vec3 reflected = src(kaleidoscope(uv));
                return written(intensity >= 1.0 ? reflected : mix(src(uv), reflected, intensity));
            }
            vec3 c = src(uv);
            float y = luma(c);
            float pixelScale = min(uSize.x, uSize.y) / 1080.0;
            vec3 o = c;
            if (near(uKind, 23.0)) {
                vec2 reach = 0.12 * min(uSize.x, uSize.y) / uSize;
                o = vec3(src(uv - vec2(uP0.x, uP1.x) * reach).r, src(uv - vec2(uP2.x, uP3.x) * reach).g, src(uv - vec2(uP4.x, uP5.x) * reach).b);
            }
            else if (near(uKind, 24.0)) o = lin(1.0 - srgb(clamp(c, 0.0, 1.0)));
            else if (near(uKind, 20.0)) o = colorCorrection(c);
            else if (near(uKind, 0.0)) o = silentTint(c);
            else if (near(uKind, 1.0)) o = threeStrip(c);
            else if (near(uKind, 2.0)) o = bleachBypass(c, y);
            else if (near(uKind, 3.0)) o = dayForNight(c, y, uv);
            else if (near(uKind, 4.0)) o = colorIsolation(c, y);
            else if (near(uKind, 5.0)) o = noir(c, q, pixelScale);
            else if (near(uKind, 6.0)) o = expressionism(c, y, uv);
            o = clamp(o, 0.0, 1.0);
            return written(intensity >= 1.0 ? o : mix(c, o, intensity));
        }
    """

    /**
     * The film grain's noise, read from the frame's lattice (Metal:137-142): made by the core
     * ([VideoEffectGrain.lattice], the original's whole-number hash, Metal:32-33, :554-559) and
     * uploaded as a texture, so that it is the same on every GPU.
     */
    private const val GRAIN = """
        uniform sampler2D uLattice;
        uniform vec2 uLatticeSize;
        float grain(vec2 point) {
            vec2 cell = floor(point);
            vec2 f = fract(point);
            f = f * f * (3.0 - 2.0 * f);
            return texture2D(uLattice, (cell + 0.5 + f) / uLatticeSize).r * 2.0 - 1.0;
        }
    """

    private const val NO_GRAIN = "float grain(vec2 point) { return 0.0; }"

    /** The export's. Texture rows run from the bottom. */
    val FRAGMENT = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
        uniform sampler2D uTex;
        varying vec2 vTex;
        $UNIFORMS
        $HELPERS
        vec3 texel(vec2 i) { return lin(clamp(texture2D(uTex, vec2((i.x + 0.5) / uSize.x, 1.0 - (i.y + 0.5) / uSize.y)).rgb, 0.0, 1.0)); }
        // Read between pixels in linear light, as the original reads its linear picture: four pixels, mixed after decoding.
        vec3 src(vec2 uv) {
            vec2 p = uv * uSize - 0.5;
            vec2 i = floor(p);
            vec2 f = p - i;
            vec2 last = uSize - 1.0;
            vec2 a = clamp(i, vec2(0.0), last);
            vec2 b = clamp(i + 1.0, vec2(0.0), last);
            return mix(mix(texel(a), texel(vec2(b.x, a.y)), f.x), mix(texel(vec2(a.x, b.y)), texel(b), f.x), f.y);
        }
        $GRAIN
        $EFFECTS
        void main() {
            vec2 q = vec2(floor(gl_FragCoord.x), uSize.y - 1.0 - floor(gl_FragCoord.y));
            gl_FragColor = vec4(effectPass((q + 0.5) / uSize, q), 1.0);
        }
    """

    val VERTEX = """
        attribute vec4 aFramePosition;
        varying vec2 vTex;
        void main() { gl_Position = aFramePosition; vTex = aFramePosition.xy * 0.5 + 0.5; }
    """

    /** The preview's: the deck's picture as the content of a layer, read at its pixels and held at its edges. No grain. */
    val PREVIEW = """
        uniform shader content;
        $UNIFORMS
        $HELPERS
        vec3 texel(vec2 i) { return lin(clamp(content.eval(i + 0.5).rgb, 0.0, 1.0)); }
        // Read between pixels in linear light, as the original reads its linear picture: four pixels, mixed after decoding.
        vec3 src(vec2 uv) {
            vec2 p = uv * uSize - 0.5;
            vec2 i = floor(p);
            vec2 f = p - i;
            vec2 last = uSize - 1.0;
            vec2 a = clamp(i, vec2(0.0), last);
            vec2 b = clamp(i + 1.0, vec2(0.0), last);
            return mix(mix(texel(a), texel(vec2(b.x, a.y)), f.x), mix(texel(vec2(a.x, b.y)), texel(b), f.x), f.y);
        }
        $NO_GRAIN
        $EFFECTS
        vec4 main(vec2 xy) {
            vec2 q = floor(xy);
            return vec4(effectPass((q + 0.5) / uSize, q), 1.0);
        }
    """

    /** The parameter slots of [u] the shader takes, four numbers each. */
    fun slot(u: EffectUniforms, index: Int): FloatArray = u.p[index].let { floatArrayOf(it.x.toFloat(), it.y.toFloat(), it.z.toFloat(), it.w.toFloat()) }

    fun head(u: EffectUniforms): FloatArray = floatArrayOf(u.intensity.toFloat(), u.frame.toFloat(), u.time.toFloat(), u.fps.toFloat())

    /** The passes of [effects] at a source second, in the order they are drawn: the ones this shader draws. */
    fun passes(effects: List<VideoEffectSettings>?, sourceTime: Double, frameRate: Double): List<EffectUniforms> {
        if (effects.isNullOrEmpty()) return emptyList()
        return EffectFrame.of(effects, sourceTime, VideoEffectTime.frameIndex(sourceTime, frameRate), frameRate).filter { it.effect in drawn }
    }
}

/**
 * The video effects of [clip] in an export, drawn on its picture after its
 * color, its framing and its fit to the movie's frame, as the original draws
 * them (`VideoToolCompositor.swift:143-162`, `:361`): one pass for each
 * effect, each on what the one before made. [begins] is where the clip starts
 * on its sequence, as for [FramingEffect]; the effects are sampled at the
 * source second each frame shows. [frameRate] is the movie's.
 */
class VideoEffectChain(private val clip: TimelineClip, private val begins: Double, private val frameRate: Double) : GlEffect {
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = clip.videoEffects.orEmpty().none { it.kind in EffectShader.drawn }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program()

    private inner class Program : BaseGlShaderProgram(/* useHighPrecisionColorComponents = */ false, /* texturePoolCapacity = */ 1) {
        private var program: GlProgram? = null
        private var size = Size(0, 0)
        private val textures = IntArray(2)
        private val framebuffers = IntArray(2)

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            if (inputWidth != size.width || inputHeight != size.height) releaseTargets()
            size = Size(inputWidth, inputHeight)
            return size
        }

        // The grain's lattice of the frame drawn last, and which frame it is of.
        private var lattice = 0
        private var latticeFrame = -1
        private var latticeSize = Pair(0, 0)

        private fun prepared(): GlProgram = program ?: run {
            val made = GlProgram(EffectShader.VERTEX, EffectShader.FRAGMENT)
            made.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            program = made
            made
        }

        private fun target(index: Int): Int {
            if (framebuffers[index] == 0) {
                textures[index] = GlUtil.createTexture(size.width, size.height, /* useHighPrecisionColorComponents = */ false)
                framebuffers[index] = GlUtil.createFboForTexture(textures[index])
            }
            return framebuffers[index]
        }

        private fun draw(program: GlProgram, texture: Int, pass: EffectUniforms?) {
            program.use()
            program.setSamplerTexIdUniform("uTex", texture, 0)
            program.setFloatUniform("uKind", pass?.kind?.toFloat() ?: -1f)
            program.setFloatsUniform("uHead", if (pass == null) floatArrayOf(1f, 0f, 0f, 0f) else EffectShader.head(pass))
            program.setFloatsUniform("uSize", floatArrayOf(size.width.toFloat(), size.height.toFloat()))
            for (slot in 0 until EffectShader.SLOTS) program.setFloatsUniform("uP$slot", if (pass == null) FloatArray(4) else EffectShader.slot(pass, slot))
            program.setSamplerTexIdUniform("uLattice", if (lattice != 0) lattice else texture, 1)
            program.setFloatsUniform("uLatticeSize", floatArrayOf(latticeSize.first.toFloat().coerceAtLeast(1f), latticeSize.second.toFloat().coerceAtLeast(1f)))
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            val program = prepared()
            val offset = (presentationTimeUs / 1e6 - begins).coerceIn(0.0, clip.duration)
            val passes = EffectShader.passes(clip.videoEffects, clip.sourceTime(offset), frameRate)
            if (passes.isEmpty()) return draw(program, inputTexId, null)
            passes.firstOrNull { it.effect == VideoEffectID.Noir && it.p[2].x > 0.5 }?.let { grain(it.frame) }
            // Every pass but the last draws into a picture of its own; the last into the output.
            val bound = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, bound, 0)
            var reading = inputTexId
            passes.forEachIndexed { index, pass ->
                val last = index == passes.size - 1
                GlUtil.focusFramebufferUsingCurrentContext(if (last) bound[0] else target(index % 2), size.width, size.height)
                draw(program, reading, pass)
                reading = textures[index % 2]
            }
        }

        /**
         * Makes the lattice of Noir's grain for [frame] (size 1, monochrome): one byte a point,
         * the noise −1…1 as 0…255, read between its points by the texture's filtering.
         */
        private fun grain(frame: Int) {
            val (columns, rows) = VideoEffectGrain.latticeSize(size.width, size.height, VideoEffectFormulas.NOIR_GRAIN_SIZE)
            if (frame == latticeFrame && latticeSize == Pair(columns, rows) && lattice != 0) return
            val bytes = ByteBuffer.allocateDirect(columns * rows)
            val seed = frame.toUInt() + 13u
            for (y in 0 until rows) for (x in 0 until columns) {
                val noise = VideoEffectShaderMath.random01(x.toUInt(), y.toUInt(), seed) * 2 - 1
                bytes.put(((noise * 0.5 + 0.5) * 255).roundToInt().toByte())
            }
            bytes.flip()
            if (lattice == 0) {
                val made = IntArray(1)
                GLES20.glGenTextures(1, made, 0)
                lattice = made[0]
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lattice)
            for (filter in listOf(GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_TEXTURE_MAG_FILTER)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, filter, GLES20.GL_LINEAR)
            for (wrap in listOf(GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_TEXTURE_WRAP_T)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, wrap, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, columns, rows, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, bytes)
            GlUtil.checkGlError()
            latticeFrame = frame
            latticeSize = Pair(columns, rows)
        }

        private fun releaseTargets() {
            for (i in 0..1) {
                if (framebuffers[i] != 0) GlUtil.deleteFbo(framebuffers[i])
                if (textures[i] != 0) GlUtil.deleteTexture(textures[i])
                framebuffers[i] = 0
                textures[i] = 0
            }
        }

        override fun release() {
            super.release()
            releaseTargets()
            if (lattice != 0) GLES20.glDeleteTextures(1, intArrayOf(lattice), 0)
            program?.delete()
        }
    }
}

/**
 * The preview's side of the effects, on Android 13 and later: a deck's
 * picture through the passes of its clip, one shader for each. Grain is not
 * drawn; positions are those of the deck's view, which shows the clip fitted,
 * before its crop.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class EffectPreview {
    private val shaders = mutableListOf<RuntimeShader>()

    /** The effect for a deck [width] by [height] pixels showing [passes]; null when there are none. */
    fun effect(passes: List<EffectUniforms>, width: Float, height: Float): RenderEffect? {
        var effect: RenderEffect? = null
        passes.forEachIndexed { index, pass ->
            while (shaders.size <= index) shaders += RuntimeShader(EffectShader.PREVIEW)
            val shader = shaders[index]
            shader.setFloatUniform("uKind", pass.kind.toFloat())
            shader.setFloatUniform("uHead", EffectShader.head(pass))
            shader.setFloatUniform("uSize", width, height)
            for (slot in 0 until EffectShader.SLOTS) shader.setFloatUniform("uP$slot", EffectShader.slot(pass, slot))
            val drawn = RenderEffect.createRuntimeShaderEffect(shader, "content")
            effect = effect?.let { RenderEffect.createChainEffect(drawn, it) } ?: drawn
        }
        return effect
    }
}
