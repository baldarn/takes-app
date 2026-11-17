@file:OptIn(UnstableApi::class)

package app.takes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
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
import app.takes.core.EffectFrameInputs
import app.takes.core.VideoEffectFormulasB
import app.takes.core.VideoEffectFormulasC
import app.takes.core.VideoEffectPasses
import app.takes.core.VideoEffectQuantization
import app.takes.core.EffectUniforms
import app.takes.core.Size as EffectSize
import app.takes.core.TimelineClip
import app.takes.core.VideoEffectID
import app.takes.core.VideoEffectMatteKind
import app.takes.core.VideoEffectSettings
import app.takes.core.VideoEffectTime
import app.takes.core.VideoEffectTiming
import app.takes.core.VideoEffectSequenceClock
import app.takes.core.VideoEffectFormulas
import app.takes.core.VideoEffectGrain
import app.takes.core.VideoEffectShaderMath
import java.nio.ByteBuffer
import kotlin.math.pow
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
        // The second batch, with the pictures drawn before its passes (EffectPasses.kt).
        VideoEffectID.Halation, VideoEffectID.SixteenMM, VideoEffectID.SuperEight, VideoEffectID.Widescreen, VideoEffectID.CrashZoom,
        // The third: Quantization with the palette from the scene, Chroma Key's spatial keyer, and the pixels of the
        // time effects: Freeze Frame's push-in and Print, Undercranking's flicker and weave (TimeEffectRendering.kt holds
        // their frames and blends Step Printing's).
        VideoEffectID.Quantization, VideoEffectID.ChromaKey, VideoEffectID.FreezeFrame, VideoEffectID.Undercranking,
        // Those that read the person in the frame (PersonMasks.kt): Auto Background; Color Isolation's subject, Chroma
        // Key's person hint and Quantization's face detail are parts of effects above. Dolly Zoom separates the subject by
        // the person, where the original can use imported depth.
        VideoEffectID.AutoBackground, VideoEffectID.DollyZoom,
    )

    /** How many parameter slots the shader takes. */
    const val SLOTS = 14

    /** How many palette colours, and luma steps between them, a Quantization's pass takes. */
    const val COLORS = 16

    private val UNIFORMS = "uniform float uKind;\nuniform vec4 uHead;\nuniform vec2 uSize;\nuniform vec4 uX0;\nuniform vec4 uX1;\nuniform float uGrainSize;\n" +
        "uniform vec3 uPalette[$COLORS];\nuniform float uThresholds[${COLORS - 1}];\nuniform float uCount;\n" + (0 until SLOTS).joinToString("\n") { "uniform vec4 uP$it;" }

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

    private val EFFECTS = """
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
        // Metal:333-335, :351-353. The subject: auxAt the person's refined mask, when uX0.x says the frame has one
        // (else the picture as it is); a colour by the temporal matte (uX0.y, algorithm 2): auxAt the window's matte;
        // a colour otherwise: by hue.
        vec3 colorIsolation(vec3 c, float y, vec2 uv) {
            if (uP0.x > 0.5 || uX0.y > 0.5) return uX0.x > 0.5 ? mix(vec3(y), c, auxAt(uv).r) : c;
            vec3 h = hsv(srgb(c));
            vec3 target = hsv(srgb(uP1.xyz));
            float r = uP2.x / 360.0;
            float soft = max(0.001, uP3.x * 0.1);
            float w = (1.0 - smoothstep(r, r + soft, hueDistance(h.x, target.x))) * smoothstep(0.08, 0.2, h.y);
            return mix(vec3(y), c, w);
        }
        vec3 grainy(vec3 c, vec3 noise, float strength) {
            float y = clamp(luma(c), 0.0, 1.0);
            float a = strength * 0.12 * pow(max(0.0, 4.0 * y * (1.0 - y)), 0.7);
            return clamp(c * exp(noise * a * 2.0), 0.0, 1.0);
        }
        // The frame's grain under the pixel q, of uGrainSize pixels at 1080: one number for each channel, or the monochrome one.
        vec3 grained(vec3 c, vec2 q, float pixelScale, float strength, bool mono) {
            vec4 n = grain(q / pixelScale / uGrainSize);
            return grainy(c, mono ? vec3(n.w) : n.xyz, strength);
        }
        // Metal:336, with the grain of Metal:137-142 at strength 0.25 and size 1.
        vec3 noir(vec3 c, vec2 q, float pixelScale) {
            float l = sCurve(srgb1(dot(c, vec3(0.4, 0.5, 0.1))), uP0.x);
            l = lin1(l * (1.0 - 2.0 * uP1.x * (1.0 - l)));
            vec3 o = vec3(l);
            if (uP3.x > 0.5) o *= mix(vec3(0.94, 0.97, 1.0), vec3(1.0), l);
            if (uP2.x > 0.5) o = grained(o, q, pixelScale, 0.25, true);
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
        // Metal:158-161: the film gate, corners rounded by radius.
        float gate(vec2 uv, float radius) {
            float dx = abs(uv.x - 0.5) - (0.5 - radius);
            float dy = abs(uv.y - 0.5) - (0.5 - radius);
            float outside = length(max(vec2(dx, dy), 0.0));
            return 1.0 - smoothstep(radius - 0.004, radius + 0.004, outside + min(max(dx, dy), 0.0));
        }
        // Metal:375-376: the glow (auxAt), the halo whose red fringes (backAt), the wide glow (extraAt), the flash.
        vec3 halation(vec3 c, float y, vec2 uv) {
            vec3 flash = vec3(1.0, 0.8, 0.6);
            if (uP3.x < 0.5) flash = vec3(1.0);
            else if (uP3.x > 1.5) flash = vec3(0.65, 0.8, 1.0);
            return c + (auxAt(uv) * 0.65 + extraAt(uv) * 0.35) * uP0.x + vec3(1.0, 0.25, 0.1) * backAt(uv).r * uP1.x + flash * uP2.x * 0.08 * (1.0 - y);
        }
        // Metal:115-118, :377-378. uX0.xy is the frame's weave in pixels; auxAt the picture blurred, when there is softness.
        vec3 sixteenMM(vec2 uv, vec2 q, float pixelScale) {
            vec2 point = (uv - 0.5) / 1.006 + 0.5 + uX0.xy / uSize;
            vec3 sharp = src(point);
            vec3 o = mix(sharp, uP2.x > 0.0 ? auxAt(point) : sharp, uP2.x);
            o /= max(o - 0.85, 0.0) * 0.15 + 1.0;
            bool mono = uP5.x > 0.5;
            if (mono) o = vec3(luma(o));
            o = grained(o, q, pixelScale, uP0.x, mono);
            if (uP4.x > 0.5) o *= gate(uv, 0.035);
            return o;
        }
        // Metal:119-122, :379-383, without the cadence. uX0.xy is the weave in pixels, uX1 the light leak (pulse, from the left).
        vec3 superEight(vec2 uv, vec2 q, float pixelScale) {
            vec2 point = (uv - 0.5) / 1.012 + 0.5 + uX0.xy / uSize;
            vec2 spread = (uv - 0.5) * pixelScale / uSize;
            vec3 o = vec3(auxAt(point + spread).r, auxAt(point).g, auxAt(point - spread).b);
            float fade = uP0.x;
            o = mix(o, o * 0.88 + 0.06, fade);
            o *= vec3(1.06, 1.0, 0.92);
            o.r += o.g * 0.025 * fade;
            o = mix(vec3(luma(o)), o, 0.8);
            o = grained(o, q, pixelScale, uP1.x, false);
            float edge = uX1.y > 0.5 ? uv.x : 1.0 - uv.x;
            o += vec3(1.0, 0.22, 0.035) * (exp(-edge * 7.0) * uX1.x * uP4.x);
            if (uP5.x > 0.5) o *= gate(uv, 0.055);
            return o;
        }
        // Metal:364-370, without the oval bokeh: the bars, black or the blurred picture (backAt) at 60 %, and the flares (auxAt).
        vec3 widescreen(vec3 c, vec2 uv) {
            float r = 1.33;
            if (uP0.x < 0.5) r = 2.39;
            else if (uP0.x < 1.5) r = 2.2;
            else if (uP0.x < 2.5) r = 1.85;
            float aspect = uSize.x / uSize.y;
            bool bar = aspect < r ? abs(uv.y - 0.5) > 0.5 * aspect / r : abs(uv.x - 0.5) > 0.5 * r / aspect;
            if (bar) return uP1.x > 0.5 ? backAt(uv) * 0.6 : vec3(0.0);
            vec3 tint = vec3(0.35, 0.6, 1.0);
            if (uP3.x > 1.5) tint = vec3(1.0);
            else if (uP3.x > 0.5) tint = vec3(1.0, 0.6, 0.25);
            return c + auxAt(uv) * tint * uP2.x;
        }
        // Metal:87-94, :361-366, the target set by hand: uX0.x the magnification, uX0.y the radial blur's reach.
        vec3 crashZoom(vec2 uv) {
            vec2 target = vec2(uP5.x, uP6.x);
            vec2 point = (uv - target) / uX0.x + target;
            vec3 sum = vec3(0.0);
            for (int i = 0; i < 8; i++) {
                float k = uX0.y * (float(i) / 7.0 - 0.5);
                sum += src(point + (point - target) * k) / 8.0;
            }
            return sum;
        }
        // Metal:162-166: the ordered dither's threshold of pixel q, from a Bayer matrix of size 4 or 8.
        float bayer(vec2 q, float size) {
            float value = 0.0;
            float scale = 1.0;
            for (int b = 0; b < 3; b++) {
                if (scale >= size) break;
                float bx = mod(floor(q.x / scale), 2.0);
                float by = mod(floor(q.y / scale), 2.0);
                value = value * 4.0 + abs(bx - by) * 2.0 + by;
                scale *= 2.0;
            }
            return (value + 0.5) / (size * size);
        }
        // Metal:313-316, :357-365: the palette (Oklab) and luma steps of the frame; auxAt the smoothed picture. Face detail
        // (Metal:359), when uX0.x says so and there are at most four colours: sharpened against backAt (the frame blurred)
        // where moreAt (the person's raw mask) is.
        vec3 quantization(vec3 original, vec2 uv, vec2 q) {
            if (uCount < 1.5) return original;
            vec3 c = uP3.x > 0.0 ? auxAt(uv) : original;
            float l0 = luma(c);
            vec3 contrasted = c + 0.2 * (l0 * l0 * (3.0 - 2.0 * l0) - l0);
            if (uX0.x > 0.5 && uCount < 4.5) contrasted += (contrasted - backAt(uv)) * (0.4 * moreAt(uv).r);
            vec3 v = lab(clamp(contrasted, 0.0, 1.0));
            float dithering = uP4.x;
            if (dithering > 0.5) {
                float spread = 0.0;
                for (int i = 1; i < $COLORS; i++) {
                    if (float(i) >= uCount) break;
                    spread += distance(uPalette[i - 1], uPalette[i]);
                }
                spread /= max(1.0, uCount - 1.0);
                v.x += (bayer(q, dithering < 1.5 ? 4.0 : 8.0) - 0.5) * spread;
            }
            bool mono = uP1.x > 0.5;
            vec3 chosen = uPalette[0];
            if (mono || (uP2.x > 0.5 && uP2.x < 3.5)) {
                float l = luma(clamp(unlab(v), 0.0, 1.0));
                float best = 0.0;
                for (int i = 0; i < ${COLORS - 1}; i++) {
                    if (float(i) >= uCount - 1.0) break;
                    if (l >= uThresholds[i]) best += 1.0;
                }
                if (mono) return lin(vec3(best / max(1.0, uCount - 1.0)));
                for (int i = 0; i < $COLORS; i++) if (float(i) == best) chosen = uPalette[i];
            } else {
                float closest = 1e10;
                for (int i = 0; i < $COLORS; i++) {
                    if (float(i) >= uCount) break;
                    vec3 d = v - uPalette[i];
                    float distance2 = dot(d, d);
                    if (distance2 < closest) {
                        closest = distance2;
                        chosen = uPalette[i];
                    }
                }
            }
            return unlab(chosen);
        }
        // Metal:400-416, the spatial keyer: auxAt the matte, backAt the new background and extraAt its light wrap, each times
        // uX0.w (its brightness); uX0.xyz the screen; uX1.xyz a solid background's colour, when uX1.w is 1.
        vec3 chromaKey(vec3 c, vec2 uv) {
            float a = auxAt(uv).r;
            vec3 screen = keyScreen();
            bool solid = uX1.w > 0.5;
            vec3 background = solid ? uX1.xyz : backAt(uv) * uX0.w;
            vec3 wrapped = solid ? uX1.xyz : extraAt(uv) * uX0.w;
            vec3 straight = clamp((c - screen * (1.0 - a)) / max(a, 0.0001), 0.0, 1.0);
            bool blue = uP1.x > 1.5 || (uP1.x < 0.5 && screen.b > screen.g);
            float before = blue ? straight.b : straight.g;
            float limit = blue ? (straight.r + straight.g) * 0.525 : (straight.r + straight.b) * 0.525;
            float after = min(before, mix(before, limit, uP9.x));
            if (blue) straight.b = after;
            else straight.g = after;
            straight += vec3(0.3, 0.6, 0.1) * ((before - after) * 0.5);
            vec3 o = straight * a + background * (1.0 - a);
            o += wrapped * (a * (1.0 - a) * 4.0 * uP10.x * 0.25);
            if (uP13.x > 1.5) return c;
            if (uP13.x > 0.5) return vec3(a);
            return o;
        }
        // Metal:400-415: auxAt the person's refined mask, moreAt the local background behind it (the estimate), backAt
        // the new background and extraAt its light wrap, each times uX0.w (its brightness), or uX1.xyz a solid colour
        // when uX1.w is 1; uX0.x the light wrap, uX0.y the view (Result, Matte, Original), uX0.z 1 when the frame has a
        // person mask (else the picture as it is).
        vec3 autoBackground(vec3 c, vec2 uv) {
            if (uX0.z < 0.5) return c;
            float a = auxAt(uv).r;
            bool solid = uX1.w > 0.5;
            vec3 background = solid ? uX1.xyz : backAt(uv) * uX0.w;
            vec3 wrapped = solid ? uX1.xyz : extraAt(uv) * uX0.w;
            vec3 straight = clamp((c - moreAt(uv) * (1.0 - a)) / max(a, 0.0001), 0.0, 1.0);
            vec3 o = straight * a + background * (1.0 - a);
            o += wrapped * (a * (1.0 - a) * 4.0 * uX0.x * 0.25);
            if (uX0.y > 1.5) return c;
            if (uX0.y > 0.5) return vec3(a);
            return o;
        }
        // Metal:366-369, the simulated two-layer dolly: the background behind the subject (moreAt, the estimate with its
        // fill) read magnified by uX0.x about the target (uP4, uP5), under the subject by its refined mask (auxAt); uX0.y
        // 1 when the frame has a person mask (else the picture as it is).
        vec3 dollyZoom(vec3 c, vec2 uv) {
            if (uX0.y < 0.5) return c;
            vec2 target = vec2(uP4.x, uP5.x);
            return mix(moreAt((uv - target) / uX0.x + target), c, auxAt(uv).r);
        }
        // Metal:96, :386-387: the held frame magnified about the centre by the push-in, uHead.z the hold's clock; Print's grey,
        // vignette and colour grain.
        vec3 freezeFrame(vec2 uv, vec2 q, float pixelScale) {
            float zoom = 1.0 + uP1.x * clamp(uHead.z / max(0.001, uP0.x), 0.0, 1.0);
            vec3 o = src((uv - 0.5) / zoom + 0.5);
            if (uP2.x > 0.5) {
                o = mix(vec3(luma(o)), o, 0.95) * (1.0 - 0.12 * smoothstep(0.25, 0.7, length(uv - 0.5)));
                o = grained(o, q, pixelScale, ${app.takes.core.VideoEffectFormulasC.FREEZE_PRINT_GRAIN}, false);
            }
            return o;
        }
        // Metal:97-100, :389: magnified by 1.01, moved by the weave (uX0.xy, pixels) and multiplied by the flicker (uX0.z).
        vec3 undercranking(vec2 uv) { return src((uv - 0.5) / 1.01 + 0.5 + uX0.xy / uSize) * uX0.z; }
        vec3 written(vec3 c) { return clamp(srgb(max(c, 0.0)), 0.0, 1.0); }
    """

    /** Each effect's branch of [effectPass], by the shader's index: what it makes of `c`, the pixel, into `o`. */
    private val BRANCHES: List<Pair<Int, String>> = listOf(
        23 to "vec2 reach = 0.12 * min(uSize.x, uSize.y) / uSize; o = vec3(src(uv - vec2(uP0.x, uP1.x) * reach).r, src(uv - vec2(uP2.x, uP3.x) * reach).g, src(uv - vec2(uP4.x, uP5.x) * reach).b);",
        24 to "o = lin(1.0 - srgb(clamp(c, 0.0, 1.0)));",
        20 to "o = colorCorrection(c);",
        0 to "o = silentTint(c);",
        1 to "o = threeStrip(c);",
        2 to "o = bleachBypass(c, y);",
        3 to "o = dayForNight(c, y, uv);",
        4 to "o = colorIsolation(c, y, uv);",
        5 to "o = noir(c, q, pixelScale);",
        6 to "o = expressionism(c, y, uv);",
        15 to "o = halation(c, y, uv);",
        16 to "o = sixteenMM(uv, q, pixelScale);",
        17 to "o = superEight(uv, q, pixelScale);",
        11 to "o = widescreen(c, uv);",
        9 to "o = crashZoom(uv);",
        7 to "o = quantization(c, uv, q);",
        18 to "o = chromaKey(c, uv);",
        19 to "o = autoBackground(c, uv);",
        8 to "o = dollyZoom(c, uv);",
        12 to "o = freezeFrame(uv, q, pixelScale);",
        14 to "o = undercranking(uv);",
    )

    /**
     * One pass: the encoded colour of the pixel q, at uv. The export's holds
     * every effect's branch ([kind] null); the preview's one shader for each
     * effect, which keeps each within what the platform compiles.
     */
    private fun effectPass(kind: Int?): String {
        val branches = BRANCHES.filter { kind == null || it.first == kind }
            .joinToString("\n            else ") { (index, body) -> "if (near(uKind, $index.0)) { $body }" }
        return """
        vec3 effectPass(vec2 uv, vec2 q) {
            float intensity = uHead.x;
            if (uKind < -0.5) return written(texel(q));
            if (near(uKind, 22.0)) {
                vec3 reflected = src(kaleidoscope(uv));
                return written(intensity >= 1.0 ? reflected : mix(src(uv), reflected, intensity));
            }
            vec3 c = src(uv);
            float y = luma(c);
            float pixelScale = min(uSize.x, uSize.y) / 1080.0;
            vec3 o = c;
            ${branches.ifEmpty { "{}" }}
            o = clamp(o, 0.0, 1.0);
            return written(intensity >= 1.0 ? o : mix(c, o, intensity));
        }
        """
    }

    /**
     * The film grain's noise, read from the frame's lattice (Metal:137-142): made by the core
     * ([VideoEffectGrain.lattice], the original's whole-number hash, Metal:32-33, :554-559) and
     * uploaded as a texture, so that it is the same on every GPU.
     */
    private const val GRAIN = """
        uniform sampler2D uLattice;
        uniform vec2 uLatticeSize;
        vec4 grain(vec2 point) {
            vec2 cell = floor(point);
            vec2 f = fract(point);
            f = f * f * (3.0 - 2.0 * f);
            return texture2D(uLattice, (cell + 0.5 + f) / uLatticeSize) * 2.0 - 1.0;
        }
    """

    private const val NO_GRAIN = "vec4 grain(vec2 point) { return vec4(0.0); }"

    /** The export's pictures drawn before a pass, linear light read between their pixels. */
    private const val AUXILIARY = """
        uniform sampler2D uAux;
        uniform sampler2D uBack;
        uniform sampler2D uExtra;
        uniform sampler2D uMore;
        vec3 auxAt(vec2 uv) { return texture2D(uAux, vec2(uv.x, 1.0 - uv.y)).rgb; }
        vec3 moreAt(vec2 uv) { return texture2D(uMore, vec2(uv.x, 1.0 - uv.y)).rgb; }
        vec3 backAt(vec2 uv) { return texture2D(uBack, vec2(uv.x, 1.0 - uv.y)).rgb; }
        vec3 extraAt(vec2 uv) { return texture2D(uExtra, vec2(uv.x, 1.0 - uv.y)).rgb; }
        vec3 keyScreen() { return uX0.xyz; }
    """

    /**
     * The preview's stand-ins for those pictures, worked out at the pixel
     * from a few reads of the deck's picture: a simplified version of the
     * export's blurs. uBlur holds the sigmas, in the deck's pixels, of the
     * pass's first, second and third picture.
     */
    private const val PREVIEW_AUXILIARY = """
        uniform vec4 uBlur;
        uniform shader backdrop;
        uniform vec4 uPlace;
        uniform vec2 uBackdrop;
        vec3 bright(vec3 c, float threshold, float softness) { return c * smoothstep(threshold - softness, threshold + softness, luma(c)); }
        vec3 spot(vec2 uv, float kind) {
            if (kind < 0.5) return src(uv);
            vec3 c = texel(clamp(floor(uv * uSize), vec2(0.0), uSize - 1.0));
            if (kind > 1.5) return bright(c, 0.9, 0.05);
            if (kind > 0.5) return bright(c, 0.75, 0.1);
            return c;
        }
        // A Gaussian of sigma s pixels from seventeen reads: the centre and two rings of eight.
        vec3 blurred(vec2 uv, float s, float kind) {
            vec3 sum = spot(uv, kind);
            float total = 1.0;
            for (int i = 0; i < 8; i++) {
                float a = float(i) * 0.785398 + 0.3927;
                vec2 d = vec2(cos(a), sin(a)) * s / uSize;
                sum += (spot(uv + d, kind) + spot(uv + d * 2.2, kind) * 0.297) * 0.607;
                total += 0.607 * 1.297;
            }
            return sum / total;
        }
        // Across only, from thirteen reads.
        vec3 across(vec2 uv, float s, float kind) {
            vec3 sum = vec3(0.0);
            float total = 0.0;
            for (int i = -6; i <= 6; i++) {
                float k = float(i) * 0.5;
                float w = exp(-0.5 * k * k);
                sum += spot(uv + vec2(k * s / uSize.x, 0.0), kind) * w;
                total += w;
            }
            return sum / total;
        }
        // Chroma Key's screen: set by hand, or (uBlur.w) the mean of the clearly green or blue of sixteen points on the border.
        vec3 keyScreen() {
            if (uBlur.w < 0.5) return uX0.xyz;
            vec3 greens = vec3(0.0);
            vec3 blues = vec3(0.0);
            float g = 0.0;
            float b = 0.0;
            for (int i = 0; i < 16; i++) {
                float t = (floor(float(i) / 4.0) * 4.0 + 2.0) / 16.0;
                float side = mod(float(i), 4.0);
                vec2 at = side < 0.5 ? vec2(t, 0.02) : side < 1.5 ? vec2(t, 0.98) : side < 2.5 ? vec2(0.02, t) : vec2(0.98, t);
                vec3 c = spot(at, 0.0);
                if (c.g - max(c.r, c.b) > 0.02) { greens += c; g += 1.0; }
                if (c.b - max(c.r, c.g) > 0.02) { blues += c; b += 1.0; }
            }
            bool blue = uP1.x > 1.5 || (uP1.x < 0.5 && b > g);
            if (blue && b >= 1.0) return blues / b;
            if (!blue && g >= 1.0) return greens / g;
            return uX0.xyz;
        }
        float keyAlpha(vec3 c, vec3 screen) {
            bool blue = uP1.x > 1.5 || (uP1.x < 0.5 && screen.b > screen.g);
            float key = blue ? c.b : c.g;
            float other = blue ? c.g : c.b;
            float sk = blue ? screen.b : screen.g;
            float so = blue ? screen.g : screen.b;
            bool even = abs(uP4.x - 0.5) < 0.0001;
            float d = key - (even ? max(c.r, other) : uP4.x * c.r + (1.0 - uP4.x) * other);
            float ds = sk - (even ? max(screen.r, so) : uP4.x * screen.r + (1.0 - uP4.x) * so);
            float a = 1.0 - clamp(uP3.x * d / max(ds, 0.0001), 0.0, 1.0);
            return clamp((a - uP5.x) / max(0.0001, uP6.x - uP5.x), 0.0, 1.0);
        }
        // The person mask (its alpha): uPersonX and uPersonY take a deck pixel to a pixel of the stored mask, uPerson.x is 1
        // when the frame has one and uPerson.yz is the mask's size. No person outside it.
        uniform shader person;
        uniform vec4 uPerson;
        uniform vec3 uPersonX;
        uniform vec3 uPersonY;
        float personAt(vec2 uv) {
            if (uPerson.x < 0.5) return 0.0;
            vec3 p = vec3(uv * uSize, 1.0);
            vec2 m = vec2(dot(uPersonX, p), dot(uPersonY, p));
            if (m.x < 0.0 || m.y < 0.0 || m.x > uPerson.y || m.y > uPerson.z) return 0.0;
            return person.eval(m).a;
        }
        // Studio, or Window when w is 2: AutoBackgroundFormulas.studioBackground, in linear light.
        vec3 studio(vec2 uv, float w) {
            vec2 p = vec2(uv.x * uSize.x, uSize.y - uv.y * uSize.y);
            vec3 light = lin(vec3(0.65, 0.58, 0.48));
            vec3 dark = lin(vec3(0.08, 0.10, 0.14));
            bool pane = w > 1.5 && p.y >= uSize.y * 0.18 && p.y < uSize.y * 0.83 &&
                ((p.x >= uSize.x * 0.64 && p.x < uSize.x * 0.74) || (p.x >= uSize.x * 0.80 && p.x < uSize.x * 0.90));
            if (pane) return lin(vec3(0.62, 0.73, 0.83));
            return mix(light, dark, clamp(length(p - vec2(uSize.x * 0.3, uSize.y * 0.7)) / (uSize.x * 0.8), 0.0, 1.0));
        }
        // An Image background: the picture at uPlace (its scale, its top left in the deck's pixels), black outside it.
        vec3 placed(vec2 uv) {
            vec2 p = (uv * uSize - uPlace.yz) / uPlace.x;
            if (p.x < 0.0 || p.y < 0.0 || p.x > uBackdrop.x || p.y > uBackdrop.y) return vec3(0.0);
            return lin(clamp(backdrop.eval(p).rgb, 0.0, 1.0));
        }
        // Dolly Zoom's background behind the person, a stand-in for the export's estimate and fill: the picture where the
        // person is not; where they are, the first point clear of them outward from the target (uP4, uP5), in up to
        // sixteen steps of a thirty-second of the deck.
        vec3 dollyFill(vec2 uv) {
            vec2 d = (uv - vec2(uP4.x, uP5.x)) * uSize;
            float n = length(d);
            vec2 step = (n > 0.001 ? d / n : vec2(0.0, 1.0)) * (max(uSize.x, uSize.y) / 32.0) / uSize;
            vec2 p = uv;
            for (int i = 0; i < 16; i++) {
                if (personAt(p) < 0.5) break;
                p = clamp(p + step, vec2(0.0), vec2(1.0));
            }
            return src(p);
        }
    """

    /** The preview's stand-ins for the pictures the pass of effect [kind] reads. */
    private fun previewPictures(kind: Int): String {
        val (aux, back, extra) = when (kind) {
            15 -> Triple("blurred(uv, uBlur.x, 1.0)", "blurred(uv, uBlur.y, 1.0)", "blurred(uv, uBlur.z, 1.0)")
            11 -> Triple("across(uv, uBlur.x, 2.0)", "blurred(uv, uBlur.y, 0.0)", "vec3(0.0)")
            16, 17 -> Triple("blurred(uv, uBlur.x, 0.0)", "vec3(0.0)", "vec3(0.0)")
            // The smoothed picture stands in as the picture itself; face detail sharpens against the picture blurred.
            7 -> Triple("spot(uv, 0.0)", "blurred(uv, uBlur.y, 0.0)", "vec3(0.0)")
            18 -> Triple("vec3(keyAlpha(spot(uv, 0.0), keyScreen()))", "uPlace.x > 0.0 ? placed(uv) : blurred(uv, uBlur.y, 0.0)", "backAt(uv)")
            // The person's mask as it is, without the guided filter.
            4 -> Triple("vec3(personAt(uv))", "vec3(0.0)", "vec3(0.0)")
            // The mask as it is; the new background (a picture, Studio or Window, or the picture blurred), its light wrap as itself.
            19 -> Triple(
                "vec3(personAt(uv))",
                "uPlace.x > 0.0 ? placed(uv) : uBlur.w > 0.5 ? studio(uv, uBlur.w) : blurred(uv, uBlur.y, 0.0)",
                "backAt(uv)",
            )
            // Dolly Zoom: the mask as it is.
            8 -> Triple("vec3(personAt(uv))", "vec3(0.0)", "vec3(0.0)")
            else -> Triple("vec3(0.0)", "vec3(0.0)", "vec3(0.0)")
        }
        // The raw person mask for face detail; the picture itself as the background behind the person (the estimate),
        // with Dolly Zoom's stand-in for the fill.
        val more = when (kind) {
            7 -> "vec3(personAt(uv))"
            19 -> "src(uv)"
            8 -> "dollyFill(uv)"
            else -> "vec3(0.0)"
        }
        return """
        vec3 auxAt(vec2 uv) { return $aux; }
        vec3 backAt(vec2 uv) { return $back; }
        vec3 extraAt(vec2 uv) { return $extra; }
        vec3 moreAt(vec2 uv) { return $more; }
        """
    }

    // Read between pixels in linear light, as the original reads its linear picture: four pixels, mixed after decoding.
    private const val SOURCE = """vec3 src(vec2 uv) {
            vec2 p = uv * uSize - 0.5;
            vec2 i = floor(p);
            vec2 f = p - i;
            vec2 last = uSize - 1.0;
            vec2 a = clamp(i, vec2(0.0), last);
            vec2 b = clamp(i + 1.0, vec2(0.0), last);
            return mix(mix(texel(a), texel(vec2(b.x, a.y)), f.x), mix(texel(vec2(a.x, b.y)), texel(b), f.x), f.y);
        }"""

    // The recorder's: between pixels by the texture's own filtering, in the picture's encoding, then decoded.
    private const val FAST_SOURCE = """vec3 src(vec2 uv) { return lin(clamp(texture2D(uTex, vec2(uv.x, 1.0 - uv.y)).rgb, 0.0, 1.0)); }"""

    /** The export's. Texture rows run from the bottom. */
    val FRAGMENT = fragment(null)

    /**
     * The export's shader holding the branch of the effect of shader index [kind] alone ([kind] null: all of
     * them, the export's), for the recorder's live effects, which draw each pass with its own: a phone's GPU runs
     * it several times faster than the shader that holds every effect, the more so as it reads between pixels with
     * the texture's filtering rather than mixing four of them in linear light (LiveEffectRendering.kt).
     */
    fun fragment(kind: Int?): String = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
        uniform sampler2D uTex;
        varying vec2 vTex;
        $UNIFORMS
        $HELPERS
        // A pixel, read a 1/1024 past its centre: a GPU that keeps the filter's weights in eight bits and truncates
        // them would otherwise mix in 1/256 of a neighbour where the division lands a hair before the centre.
        vec3 texel(vec2 i) { return lin(clamp(texture2D(uTex, vec2((i.x + 0.5009765625) / uSize.x, 1.0 - (i.y + 0.4990234375) / uSize.y)).rgb, 0.0, 1.0)); }
        ${if (kind == null) SOURCE else FAST_SOURCE}
        $GRAIN
        $AUXILIARY
        $EFFECTS
        ${effectPass(kind)}
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

    /**
     * The preview's for the effect of shader index [kind]: the deck's picture
     * as the content of a layer, read at its pixels and held at its edges. No grain.
     */
    fun preview(kind: Int): String = """
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
        $PREVIEW_AUXILIARY
        ${previewPictures(kind)}
        $EFFECTS
        ${effectPass(kind)}
        vec4 main(vec2 xy) {
            vec2 q = floor(xy);
            return vec4(effectPass((q + 0.5) / uSize, q), 1.0);
        }
    """

    /** The parameter slots of [u] the shader takes, four numbers each. */
    fun slot(u: EffectUniforms, index: Int): FloatArray = u.p[index].let { floatArrayOf(it.x.toFloat(), it.y.toFloat(), it.z.toFloat(), it.w.toFloat()) }

    /** The background a Chroma Key's pass is drawn against: the catalogue's choice. */
    fun background(u: EffectUniforms): String {
        val picker = u.effect.descriptor.parameters.firstOrNull { it.id == "background" }?.kind as? app.takes.core.EffectParameterKind.Picker
        return picker?.options?.getOrNull(u.number("background").toInt()) ?: "Solid"
    }

    /** A Quantization's palette in Oklab, three numbers a colour, for `uPalette`. */
    fun palette(u: EffectUniforms): FloatArray = FloatArray(COLORS * 3).also { out ->
        u.palette.take(COLORS).forEachIndexed { i, c -> out[i * 3] = c.x.toFloat(); out[i * 3 + 1] = c.y.toFloat(); out[i * 3 + 2] = c.z.toFloat() }
    }

    /** Its luma steps, for `uThresholds`. */
    fun thresholds(u: EffectUniforms): FloatArray = FloatArray(COLORS - 1).also { out -> u.thresholds.take(COLORS - 1).forEachIndexed { i, t -> out[i] = t.toFloat() } }

    /** How many colours its palette has, at most 16: none on another effect. */
    fun count(u: EffectUniforms): Float = minOf(COLORS, u.palette.size, maxOf(0, u.count)).toFloat()

    fun head(u: EffectUniforms): FloatArray = floatArrayOf(u.intensity.toFloat(), u.frame.toFloat(), u.time.toFloat(), u.fps.toFloat())

    /**
     * The passes of [effects] at a source second, in the order they are
     * drawn: the ones this shader draws. [inputs] is what the frame brings
     * for an effect, by its id: a Quantization's palette.
     */
    fun passes(
        effects: List<VideoEffectSettings>?, sourceTime: Double, frameRate: Double, inputs: Map<String, EffectFrameInputs> = emptyMap(),
        holdTime: Double? = null,
    ): List<EffectUniforms> {
        if (effects.isNullOrEmpty()) return emptyList()
        // On a held frame the grain is seeded by the hold's clock, so a freeze's Print grain moves (VideoEffectFrame.kt).
        val frame = VideoEffectTime.frameIndex(holdTime ?: sourceTime, frameRate)
        return EffectFrame.of(effects, sourceTime, frame, frameRate, holdTime = holdTime, inputs = inputs).filter { it.effect in drawn }
    }

    /**
     * The numbers of a pass worked out once for the frame rather than at
     * every pixel, as the original's renderer does: the weave of 16mm and
     * Super 8 in pixels of a picture [width] × [height] (`uX0.xy`), Super 8's
     * light leak (`uX1`: pulse, from the left), Crash Zoom's magnification
     * and the reach of its blur (`uX0`). Eight numbers, `uX0` then `uX1`.
     */
    fun extras(u: EffectUniforms, width: Int, height: Int): FloatArray {
        val size = EffectSize(width.toDouble(), height.toDouble())
        val out = FloatArray(8)
        when (u.effect) {
            VideoEffectID.SixteenMM -> VideoEffectFormulasB.sixteenMMWeave(u.frame, u.p[3].x, size).let { out[0] = it.x.toFloat(); out[1] = it.y.toFloat() }
            VideoEffectID.SuperEight -> {
                VideoEffectFormulasB.superEightWeave(u.frame, u.p[2].x, size).let { out[0] = it.x.toFloat(); out[1] = it.y.toFloat() }
                VideoEffectFormulasB.superEightLeak(u.time).let { out[4] = it.pulse.toFloat(); out[5] = if (it.fromLeft) 1f else 0f }
            }
            VideoEffectID.Undercranking -> {
                val frame = app.takes.core.VideoEffectTiming.undercrankingFrame(u.time, u.number("fps"))
                app.takes.core.VideoEffectTiming.undercrankingWeave(frame, u.number("weave"), size).let { out[0] = it.x.toFloat(); out[1] = it.y.toFloat() }
                out[2] = app.takes.core.VideoEffectTiming.undercrankingFlicker(frame, u.number("flicker")).toFloat()
            }
            VideoEffectID.CrashZoom -> {
                val release = if (u.flags and EffectUniforms.FLAG_RELEASE != 0) u.p[39].x else null
                val state = VideoEffectFormulasB.crashZoom(u.time, u.p[0].x, u.p[1].x, u.p[2].x, u.p[3].x > .5, release)
                out[0] = state.zoom.toFloat()
                out[1] = VideoEffectFormulasB.crashZoomBlur(state.velocity, u.p[4].x).toFloat()
            }
            // The background's magnification; whether the frame has a person mask (uX0.y) is set where there is one.
            VideoEffectID.DollyZoom -> out[0] = app.takes.core.DollyZoomFormulas.scale(u).toFloat()
            // The light wrap, the view and the brightness; a solid colour, or black for a picture not there. Whether the
            // frame has a person mask (uX0.z) is set where there is one.
            VideoEffectID.AutoBackground -> {
                val gain = 2.0.pow(u.number("backgroundBrightness"))
                out[0] = u.number("wrap").toFloat()
                out[1] = u.number("view").toFloat()
                out[3] = gain.toFloat()
                val background = background(u)
                if (background == "Solid" || background == "Video") {
                    val solid = if (background == "Solid") u.vector("backgroundColor").xyz * gain else app.takes.core.EffectVector.ZERO
                    out[4] = solid.x.toFloat()
                    out[5] = solid.y.toFloat()
                    out[6] = solid.z.toFloat()
                    out[7] = 1f
                }
            }
            // The screen set by hand and the background's brightness; a solid colour, or black for a background not drawn.
            VideoEffectID.ChromaKey -> {
                val screen = u.vector("screenColor")
                val gain = 2.0.pow(u.number("backgroundBrightness"))
                out[0] = screen.x.toFloat()
                out[1] = screen.y.toFloat()
                out[2] = screen.z.toFloat()
                out[3] = gain.toFloat()
                val background = background(u)
                if (background != "Image" && background != "Blur original") {
                    val solid = if (background == "Solid") u.vector("backgroundColor").xyz * gain else app.takes.core.EffectVector.ZERO
                    out[4] = solid.x.toFloat()
                    out[5] = solid.y.toFloat()
                    out[6] = solid.z.toFloat()
                    out[7] = 1f
                }
            }
            else -> Unit
        }
        return out
    }

    /** The size of the grain of a pass in pixels at 1080, and whether it is in colour; null when the pass draws none. */
    fun grain(u: EffectUniforms): Pair<Double, Boolean>? = when (u.effect) {
        VideoEffectID.Noir -> if (u.p[2].x > .5) Pair(VideoEffectFormulas.NOIR_GRAIN_SIZE, false) else null
        VideoEffectID.SixteenMM -> if (u.p[0].x > 0) Pair(VideoEffectFormulasB.sixteenMMGrainSizes[u.p[1].x.toInt().coerceIn(0, 2)], u.p[5].x < .5) else null
        VideoEffectID.SuperEight -> if (u.p[1].x > 0) Pair(VideoEffectFormulasB.SUPER_EIGHT_GRAIN_SIZE, true) else null
        VideoEffectID.FreezeFrame -> if (u.p[2].x > .5) Pair(app.takes.core.VideoEffectFormulasC.FREEZE_PRINT_GRAIN_SIZE, true) else null
        else -> null
    }

    /**
     * The sigmas, in pixels of a picture [width] wide, of the preview's
     * stand-ins for the pictures a pass reads (`uBlur`): the export's blurs
     * at full size ([VideoEffectPasses]).
     */
    fun previewBlurs(u: EffectUniforms, width: Float, height: Float): FloatArray {
        val scale = minOf(width, height) / 1080f
        return when (u.effect) {
            VideoEffectID.Halation -> floatArrayOf(width * 0.01f, width * 0.006f, width * 0.04f, 0f)
            VideoEffectID.Widescreen -> floatArrayOf(width / 24f * 1.4142f, 12f * scale, 0f, 0f)
            VideoEffectID.SixteenMM -> floatArrayOf(1.5f * scale, 0f, 0f, 0f)
            VideoEffectID.SuperEight -> floatArrayOf(2f * scale, 0f, 0f, 0f)
            VideoEffectID.ChromaKey -> floatArrayOf(0f, 20f * scale, 0f, if (u.number("autoScreen") > .5) 1f else 0f)
            VideoEffectID.Quantization -> floatArrayOf(0f, 8f * scale, 0f, 0f)
            // The portrait blur and the background's own blur, as one; w: 1 Studio, 2 Window.
            VideoEffectID.AutoBackground -> floatArrayOf(
                0f, (u.number("portraitBlur") + u.number("backgroundBlur")).toFloat() * scale, 0f,
                when (background(u)) { "Studio" -> 1f; "Window" -> 2f; else -> 0f },
            )
            else -> FloatArray(4)
        }
    }
}

/**
 * The video effects of [clip] in an export, drawn on its picture after its
 * color, its framing and its fit to the movie's frame, as the original draws
 * them (`VideoToolCompositor.swift:143-162`, `:361`): one pass for each
 * effect, each on what the one before made, with the pictures an effect
 * reads besides drawn just before its pass ([EffectGpu]). [begins] is where
 * the clip starts on its sequence, as for [FramingEffect]; the effects are
 * sampled at the source second each frame shows. [frameRate] is the movie's.
 * [assets] gives a Quantization's palette from the scene and a Chroma Key's
 * picture; without them a Quantization leaves the picture as it is and an
 * Image background is black.
 */
// The grain lattice of `VideoEffectGrain.lattice`, hashed by the GPU in whole numbers: the recorder's (VideoEffectChain.specialised).
private const val LATTICE_VERTEX = """#version 300 es
in vec4 aPosition;
void main() { gl_Position = aPosition; }
"""

private const val LATTICE_FRAGMENT = """#version 300 es
precision highp float;
precision highp int;
uniform uint uFrame;
uniform int uColour;
out vec4 fragColor;
uint hash32(uint value) {
    uint n = value;
    n ^= n >> 16u; n *= 0x7feb352du; n ^= n >> 15u; n *= 0x846ca68bu; n ^= n >> 16u;
    return n;
}
float random01(uint x, uint y, uint z) { return float(hash32(x ^ hash32(y + 0x9e3779b9u) ^ hash32(z + 0x85ebca6bu)) >> 8u) / 16777216.0; }
void main() {
    uint x = uint(gl_FragCoord.x);
    uint y = uint(gl_FragCoord.y);
    float base = random01(x, y, uFrame + 13u) * 2.0 - 1.0;
    vec4 n = vec4(base);
    if (uColour == 1) n = vec4(
        base * 0.7 + (random01(x, y, uFrame + 29u) * 2.0 - 1.0) * 0.3,
        base * 0.7 + (random01(x, y, uFrame + 59u) * 2.0 - 1.0) * 0.3,
        base * 0.7 + (random01(x, y, uFrame + 101u) * 2.0 - 1.0) * 0.3,
        base);
    fragColor = n * 0.5 + 0.5;
}
"""

class VideoEffectChain(
    clip: TimelineClip,
    private val begins: Double,
    private val frameRate: Double,
    private val assets: EffectAssets = EffectAssets.NONE,
    /** The frames a second of the clip's footage, for the frame a cadence holds. */
    private val sourceFPS: Double = 30.0,
) : GlEffect {
    /** The clip drawn. The recorder's live effects change it between frames (LiveEffectRendering.kt); an export never does. */
    @Volatile var clip: TimelineClip = clip
        set(value) {
            if (value != field) clock = VideoEffectSequenceClock.of(value)
            field = value
        }
    @Volatile private var clock = VideoEffectSequenceClock.of(clip)

    /** Each pass drawn with a shader of its effect's alone ([EffectShader.fragment]): the recorder's live effects. */
    @Volatile var specialised: Boolean = false

    /**
     * The recorder's lighter processing, its last step when the phone is warm or the preview cannot keep up: some
     * pictures a pass reads are made smaller (MetalVideoEffects.swift:453-515). An export is always full.
     */
    @Volatile var lite: Boolean = false

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = clip.videoEffects.orEmpty().none { it.kind in EffectShader.drawn }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program()

    /** A frame's grain: its lattice uploaded as a texture, for one grain size, in colour or not. */
    private class Lattice(val texture: Int) {
        var frame = -1
        var columns = 0
        var rows = 0
        /** Its framebuffer, where the GPU draws it ([specialised]); 0 while it is uploaded. */
        var framebuffer = 0
    }

    /** The pictures a pass reads besides its input, 0 for none, and the numbers worked out with them, which replace [EffectShader.extras]. */
    private class Auxiliary(val aux: Int = 0, val back: Int = 0, val extra: Int = 0, val extras: FloatArray? = null, val more: Int = 0)

    private inner class Program : BaseGlShaderProgram(/* useHighPrecisionColorComponents = */ false, /* texturePoolCapacity = */ 1) {
        private var program: GlProgram? = null
        private var size = Size(0, 0)
        private val textures = IntArray(2)
        private val framebuffers = IntArray(2)
        private val gpu = EffectGpu().also { it.fast = specialised }
        private val lattices = HashMap<Pair<Double, Boolean>, Lattice>()

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            if (inputWidth != size.width || inputHeight != size.height) releaseTargets()
            size = Size(inputWidth, inputHeight)
            return size
        }

        private fun prepared(): GlProgram = program ?: run {
            val made = GlProgram(EffectShader.VERTEX, EffectShader.FRAGMENT)
            made.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            program = made
            made
        }

        // The recorder's: one shader for each effect drawn.
        private val kinds = HashMap<Int, GlProgram>()

        private fun prepared(pass: EffectUniforms): GlProgram = if (!specialised) prepared() else kinds.getOrPut(pass.kind) {
            GlProgram(EffectShader.VERTEX, EffectShader.fragment(pass.kind)).also {
                it.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            }
        }

        private fun target(index: Int): Int {
            if (framebuffers[index] == 0) {
                textures[index] = GlUtil.createTexture(size.width, size.height, /* useHighPrecisionColorComponents = */ false)
                framebuffers[index] = GlUtil.createFboForTexture(textures[index])
            }
            return framebuffers[index]
        }

        /** A picture the shader reads; one shader for one effect ([specialised]) may not read them all. */
        private fun GlProgram.samplerIfPresent(name: String, texture: Int, unit: Int) {
            if (getUniformLocation(name) != -1) setSamplerTexIdUniform(name, texture, unit)
        }

        private fun draw(program: GlProgram, texture: Int, pass: EffectUniforms?, auxiliary: Auxiliary = Auxiliary(), lattice: Lattice? = null, grainSize: Double = 1.0, nearest: Boolean = false) {
            program.use()
            program.setSamplerTexIdUniform("uTex", texture, 0)
            program.setFloatsUniformIfPresent("uKind", floatArrayOf(pass?.kind?.toFloat() ?: -1f))
            program.setFloatsUniformIfPresent("uHead", if (pass == null) floatArrayOf(1f, 0f, 0f, 0f) else EffectShader.head(pass))
            program.setFloatsUniformIfPresent("uSize", floatArrayOf(size.width.toFloat(), size.height.toFloat()))
            for (slot in 0 until EffectShader.SLOTS) program.setFloatsUniformIfPresent("uP$slot", if (pass == null) FloatArray(4) else EffectShader.slot(pass, slot))
            val extras = auxiliary.extras ?: if (pass == null) FloatArray(8) else EffectShader.extras(pass, size.width, size.height)
            program.setFloatsUniformIfPresent("uX0", extras.copyOfRange(0, 4))
            program.setFloatsUniformIfPresent("uX1", extras.copyOfRange(4, 8))
            program.setFloatsUniformIfPresent("uGrainSize", floatArrayOf(grainSize.toFloat()))
            program.samplerIfPresent("uLattice", lattice?.texture ?: texture, 1)
            program.setFloatsUniformIfPresent("uLatticeSize", floatArrayOf(maxOf(1, lattice?.columns ?: 1).toFloat(), maxOf(1, lattice?.rows ?: 1).toFloat()))
            program.samplerIfPresent("uAux", auxiliary.aux.takeIf { it != 0 } ?: texture, 2)
            program.samplerIfPresent("uBack", auxiliary.back.takeIf { it != 0 } ?: texture, 3)
            program.samplerIfPresent("uExtra", auxiliary.extra.takeIf { it != 0 } ?: texture, 4)
            program.samplerIfPresent("uMore", auxiliary.more.takeIf { it != 0 } ?: texture, 5)
            program.setFloatsUniformIfPresent("uCount", floatArrayOf(if (pass == null) 0f else EffectShader.count(pass)))
            program.bindAttributesAndUniforms()
            // Arrays after the program's own binding, which knows only their first element.
            if (pass != null && pass.palette.isNotEmpty()) {
                GLES20.glUniform3fv(program.getUniformLocation("uPalette"), EffectShader.COLORS, EffectShader.palette(pass), 0)
                GLES20.glUniform1fv(program.getUniformLocation("uThresholds"), EffectShader.COLORS - 1, EffectShader.thresholds(pass), 0)
            }
            // The program binds its pictures filtered; the picture as it is is read without, after it.
            if (nearest) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                filtering(texture, GLES20.GL_NEAREST)
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            if (nearest) filtering(texture, GLES20.GL_LINEAR)
            GlUtil.checkGlError()
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            val program = prepared()
            val offset = (presentationTimeUs / 1e6 - begins).coerceIn(0.0, clip.duration)
            // A held frame, of a freeze or of a cadence, is drawn as it looks at the second it holds (TimeEffectRendering.kt).
            val shown = if (holdsFrames(clip)) VideoEffectTiming.shown(clip, movieFrameOffset(presentationTimeUs, begins, clip, frameRate), sourceFPS, clock)
            else app.takes.core.VideoEffectShown(clip.sourceTime(offset))
            val sourceTime = shown.sourceTime
            frameOffset = offset
            frameSourceTime = sourceTime
            frameNumber++
            personFrames.clear()
            placed.clear()
            val passes = EffectShader.passes(clip.videoEffects, sourceTime, frameRate, EffectAssets.inputs(assets, clip.videoEffects, sourceTime), shown.holdTime)
            if (passes.isEmpty()) {
                // The picture as it is, pixel for pixel: read without filtering, which some GPUs let a hair of a
                // neighbour into at a pixel's centre.
                draw(program, inputTexId, null, nearest = true)
                return
            }
            // Every pass but the last draws into a picture of its own; the last into the output.
            val bound = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, bound, 0)
            var reading = inputTexId
            passes.forEachIndexed { index, pass ->
                val last = index == passes.size - 1
                val auxiliary = auxiliary(pass, EffectSource(reading, size.width, size.height, encoded = true))
                val grain = EffectShader.grain(pass)
                val lattice = grain?.let { lattice(it.first, it.second, pass.frame) }
                GlUtil.focusFramebufferUsingCurrentContext(if (last) bound[0] else target(index % 2), size.width, size.height)
                draw(prepared(pass), reading, pass, auxiliary, lattice, grain?.first ?: 1.0)
                reading = textures[index % 2]
            }
        }

        private fun filtering(texture: Int, filter: Int) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        }

        /** The pictures [pass] reads, drawn from its input [source] as the original draws them. MetalVideoEffects.swift:459-482. */
        private fun auxiliary(pass: EffectUniforms, source: EffectSource): Auxiliary {
            val width = source.width
            val height = source.height
            val scale = VideoEffectPasses.pixelScale(width, height)
            val quarterWidth = VideoEffectPasses.quarter(width)
            val quarterHeight = VideoEffectPasses.quarter(height)
            return when (pass.effect) {
                VideoEffectID.Halation -> {
                    val small = EffectSource(gpu.highlights(source, "small", quarterWidth, quarterHeight, .75, .1))
                    val detail = if (lite) .5 else 1.0
                    Auxiliary(
                        aux = gpu.blur(small, "aux", width * 0.01 / 4 * detail).texture,
                        back = gpu.blur(small, "back", width * 0.006 / 4 * detail).texture,
                        extra = gpu.blur(small, "extra", width * 0.04 / 4 * detail).texture,
                    )
                }
                VideoEffectID.Widescreen -> {
                    val flares = if (pass.number("flares") > 0) {
                        val small = EffectSource(gpu.highlights(source, "small", quarterWidth, quarterHeight, .9, .05))
                        gpu.blur(small, "aux", width / 24.0 / 4 * (if (lite) .5 else 1.0), horizontalOnly = true).texture
                    } else {
                        0
                    }
                    val fill = if (pass.number("bars") > .5) gpu.blur(source, "back", 12 * scale, quarterWidth, quarterHeight).texture else 0
                    Auxiliary(aux = flares, back = fill)
                }
                VideoEffectID.SixteenMM -> if (pass.number("softness") > 0) {
                    Auxiliary(aux = (if (lite) gpu.blur(source, "aux", 1.5 * scale, maxOf(2, width / 2), maxOf(2, height / 2)) else gpu.blur(source, "aux", 1.5 * scale)).texture)
                } else {
                    Auxiliary()
                }
                VideoEffectID.SuperEight -> Auxiliary(aux = (if (lite) gpu.blur(source, "aux", 2 * scale, maxOf(2, width / 2), maxOf(2, height / 2)) else gpu.blur(source, "aux", 2 * scale)).texture)
                VideoEffectID.Quantization -> {
                    val smoothed = pass.number("smoothing").takeIf { it > 0 }?.let { smoothing ->
                        gpu.bilateral(source, "aux", VideoEffectQuantization.smoothingPlan(width, height, smoothing, lite)).texture
                    } ?: 0
                    // Face detail: the frame blurred by 8 pixels at 1080 at half its size, and the raw person mask (Renderer:459-461).
                    val person = if (pass.number("faceDetail") > .5 && pass.number("colors") <= 4) person(width, height) else null
                    if (person == null) {
                        Auxiliary(aux = smoothed)
                    } else {
                        val blurred = gpu.blur(source, "face", 8 * scale, VideoEffectPasses.half(width), VideoEffectPasses.half(height))
                        Auxiliary(aux = smoothed, back = blurred.texture, more = person.texture, extras = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
                    }
                }
                // The subject: the person's mask refined on the picture with the effect's softness; with the temporal
                // matte, that mask or the weight by hue, through the window (Renderer:483-491).
                VideoEffectID.ColorIsolation -> {
                    val temporal = settings(pass)?.usesTemporalMatte == true
                    val matte = if (pass.number("mode") > .5) {
                        person(width, height)?.let { person -> gpu.refined(EffectSource(person), source, "isolation", pass.number("softness"), person = true, lite = lite) }
                    } else if (temporal) {
                        val color = EffectShader.slot(pass, 1)
                        gpu.isolationAlpha(source, "isolation", app.takes.core.EffectVector(color[0].toDouble(), color[1].toDouble(), color[2].toDouble()), pass.p[2].x, pass.p[3].x)
                    } else {
                        null
                    }
                    if (matte == null) {
                        Auxiliary()
                    } else {
                        val finished = if (temporal) windowed(pass, matte, source, VideoEffectMatteKind.ISOLATION) else matte
                        Auxiliary(aux = finished.texture, extras = floatArrayOf(1f, if (temporal) 1f else 0f, 0f, 0f, 0f, 0f, 0f, 0f))
                    }
                }
                VideoEffectID.ChromaKey -> chromaKey(pass, source)
                VideoEffectID.AutoBackground -> autoBackground(pass, source)
                VideoEffectID.DollyZoom -> dollyZoom(pass, source)
                else -> Auxiliary()
            }
        }

        /** The settings of [pass] on this clip. */
        private fun settings(pass: EffectUniforms): VideoEffectSettings? = clip.videoEffects.orEmpty().firstOrNull { it.id == pass.id }

        /** The temporal matte of [pass]: its [raw] matte blended with the frames before through its window ([EffectMatteWindow]). */
        private fun windowed(pass: EffectUniforms, raw: EffectTarget, source: EffectSource, kind: VideoEffectMatteKind): EffectTarget {
            // Without OpenGL ES 3, or where its passes fail, the frame's own matte, as the original falls back to the raw one (Renderer:417-419).
            if (!windowsChecked) {
                windowsChecked = true
                windowsWork = EffectMatteWindow.supported()
            }
            if (!windowsWork) return raw
            val window = windows.getOrPut(pass.id) { EffectMatteWindow(kind) }
            val start = settings(pass)?.start ?: frameSourceTime
            return try {
                window.apply(gpu, raw, source, frameNumber, frameSourceTime, start, "window.${pass.id}")
            } catch (failure: GlUtil.GlException) {
                android.util.Log.w("EffectRendering", "No temporal matte on this device", failure)
                windowsWork = false
                raw
            }
        }

        /**
         * Chroma Key's pictures, as `VideoEffectFormulasC.chromaKeyPictures` makes them (MetalVideoEffects.swift:506-530):
         * the screen from the border or set by hand, the matte grown and softened, the new background and its light wrap.
         */
        private fun chromaKey(pass: EffectUniforms, source: EffectSource): Auxiliary {
            val width = source.width
            val height = source.height
            val scale = VideoEffectPasses.pixelScale(width, height)
            val manual = pass.vector("screenColor").xyz
            val type = pass.number("screenType").toInt()
            val screen = if (pass.number("autoScreen") > .5) VideoEffectFormulasC.screenReference(gpu.border(source), manual, type) else manual
            var matte = gpu.key(
                source, "matte", screen, VideoEffectFormulasC.blueScreen(screen, type),
                pass.number("strength"), pass.number("balance"), pass.number("blackClip"), pass.number("whiteClip"),
            )
            // The person hint, at a quarter of the size: the garbage matte outside the grown person and the holdout inside
            // the shrunk one (Renderer:513-519, Metal:474-479).
            if (pass.number("personHint") > .5) {
                val reduction = if (lite) 8 else 4
                val w = maxOf(2, width / reduction)
                val h = maxOf(2, height / reduction)
                person(width, height)?.let { person ->
                    val dilated = gpu.morph(EffectSource(person), "hint.dilated", h * .04, w, h)
                    val eroded = gpu.morph(EffectSource(person), "hint.eroded", -h * .03, w, h)
                    matte = gpu.hint(EffectSource(matte), EffectSource(dilated), EffectSource(eroded), "matte.hinted")
                }
            }
            val grow = pass.number("grow") * scale
            if (grow != 0.0) matte = gpu.morph(EffectSource(matte), "matte.grown", grow)
            val softness = pass.number("softness")
            // The temporal matte refines the key on the picture and blends it through the window (Renderer:524-527).
            if (settings(pass)?.usesTemporalMatte == true) {
                if (softness > 0) matte = gpu.refined(EffectSource(matte), source, "matte.refined", softness)
                matte = windowed(pass, matte, source, VideoEffectMatteKind.KEY)
            } else if (softness > 0) {
                matte = gpu.blur(EffectSource(matte), "matte.soft", softness * 1.5 * scale)
            }
            val gain = 2.0.pow(pass.number("backgroundBrightness"))
            val background = replaced(pass, source, 20.0)
            val extras = floatArrayOf(screen.x.toFloat(), screen.y.toFloat(), screen.z.toFloat(), gain.toFloat(), 0f, 0f, 0f, 0f)
            if (background == null) {
                // A solid colour, or black for a picture not there.
                val solid = if (EffectShader.background(pass) == "Solid") pass.vector("backgroundColor").xyz * gain else app.takes.core.EffectVector.ZERO
                extras[4] = solid.x.toFloat()
                extras[5] = solid.y.toFloat()
                extras[6] = solid.z.toFloat()
                extras[7] = 1f
                return Auxiliary(aux = matte.texture, extras = extras)
            }
            val wrap = if (pass.number("wrap") > 0) gpu.blur(EffectSource(background), "wrap", width * 0.02, VideoEffectPasses.quarter(width), VideoEffectPasses.quarter(height)) else background
            return Auxiliary(aux = matte.texture, back = background.texture, extra = wrap.texture, extras = extras)
        }

        /**
         * The new background of a Chroma Key or an Auto Background, as `AutoBackgroundFormulas.background` makes it
         * (Assets:587-627) but for its brightness, which the pass applies: the picked picture placed, the frame blurred by
         * [blurOriginal] pixels at 1080, or Studio or Window; then blurred by "backgroundBlur". Null for a solid colour, or
         * for a picture not there.
         */
        private fun replaced(pass: EffectUniforms, source: EffectSource, blurOriginal: Double): EffectTarget? {
            val width = source.width
            val height = source.height
            val scale = VideoEffectPasses.pixelScale(width, height)
            val blur = pass.number("backgroundBlur")
            fun blurred(target: EffectTarget) = if (blur > 0) gpu.blur(EffectSource(target), "background.blur", blur * scale) else target
            val settings = clip.videoEffects.orEmpty().firstOrNull { it.id == pass.id }
            return when (EffectShader.background(pass)) {
                "Image" -> settings?.let(assets::picture)?.let { picture ->
                    val image = pictureSource(picture)
                    val fill = pass.number("fit") < .5
                    val zoom = pass.number("backgroundZoom")
                    val fit = (if (fill) maxOf(width.toDouble() / image.width, height.toDouble() / image.height) else minOf(width.toDouble() / image.width, height.toDouble() / image.height)) * zoom
                    blurred(gpu.place(image, "background", width, height, fit, (width - image.width * fit) * pass.number("backgroundX"), (height - image.height * fit) * pass.number("backgroundY")))
                }
                // A radius of 0 is no blur in Core Image: the frame as it is, in linear light.
                "Blur original" -> blurred(if (blurOriginal > 0) gpu.blur(source, "background", blurOriginal * scale) else gpu.reduce(source, "background", width, height))
                "Studio", "Window" -> blurred(gpu.studio(source, "background", width, height, EffectShader.background(pass) == "Window"))
                else -> null
            }
        }

        /**
         * Auto Background's pictures, as `AutoBackgroundFormulas.pictures` makes them (Renderer:500-505): the person's
         * mask refined with the edge softness and grown, the background estimated behind it, the new background and its
         * light wrap. Without a person mask the pass leaves the picture as it is.
         */
        private fun autoBackground(pass: EffectUniforms, source: EffectSource): Auxiliary {
            val width = source.width
            val height = source.height
            // The smoothed stream for a recipe of algorithm 1, the raw masks for an older one (VideoEffectModel.swift:191-202).
            val stream = clip.videoEffects.orEmpty().firstOrNull { it.id == pass.id }?.usesAutoMaskStream == true
            val person = person(width, height, stream) ?: return Auxiliary(extras = FloatArray(8))
            val mask = gpu.refined(EffectSource(person), source, "auto", pass.number("softness"), pass.number("grow"), person = true, lite = lite)
            val estimate = gpu.backgroundEstimate(source, EffectSource(mask), "estimate")
            val gain = 2.0.pow(pass.number("backgroundBrightness"))
            val wrap = pass.number("wrap")
            val extras = floatArrayOf(wrap.toFloat(), pass.number("view").toFloat(), 1f, gain.toFloat(), 0f, 0f, 0f, 0f)
            val background = replaced(pass, source, pass.number("portraitBlur"))
            if (background == null) {
                val solid = if (EffectShader.background(pass) == "Solid") pass.vector("backgroundColor").xyz * gain else app.takes.core.EffectVector.ZERO
                extras[4] = solid.x.toFloat()
                extras[5] = solid.y.toFloat()
                extras[6] = solid.z.toFloat()
                extras[7] = 1f
                return Auxiliary(aux = mask.texture, more = estimate.texture, extras = extras)
            }
            val wrapped = if (wrap > 0) gpu.blur(EffectSource(background), "wrap", width * 0.02, VideoEffectPasses.quarter(width), VideoEffectPasses.quarter(height)) else background
            return Auxiliary(aux = mask.texture, back = background.texture, extra = wrapped.texture, more = estimate.texture, extras = extras)
        }

        /**
         * Dolly Zoom's pictures, as `DollyZoomFormulas.pictures` makes them (Renderer:492-499): the person's mask
         * refined with softness 0.3, and the background estimated behind it with the fill. Without a person mask the
         * pass leaves the picture as it is.
         */
        private fun dollyZoom(pass: EffectUniforms, source: EffectSource): Auxiliary {
            val extras = EffectShader.extras(pass, source.width, source.height)
            val person = person(source.width, source.height) ?: return Auxiliary(extras = extras)
            val mask = gpu.refined(EffectSource(person), source, "dolly", app.takes.core.DollyZoomFormulas.SOFTNESS, person = true, lite = lite)
            val estimate = gpu.backgroundEstimate(source, EffectSource(mask), "dolly.estimate", fill = true)
            extras[1] = 1f
            return Auxiliary(aux = mask.texture, more = estimate.texture, extras = extras)
        }

        // The person in the frame being drawn: its mask, asked for once a frame, uploaded once, laid on the frame at each size asked.
        private var frameOffset = 0.0
        private var frameSourceTime = 0.0
        private val personFrames = HashMap<Boolean, PersonFrame?>()
        private val windows = HashMap<String, EffectMatteWindow>()
        private var windowsWork = true
        private var windowsChecked = false
        private var frameNumber = 0L
        private var personTexture = 0
        private var personUploaded: app.takes.core.PersonMaskChunks.Mask? = null
        private val placed = HashMap<Triple<Int, Int, Boolean>, EffectTarget>()

        /**
         * The person mask of the frame laid on a picture of [width] × [height], as the picture is framed; with
         * [smoothed] Auto Background's smoothed stream (VideoEffectAssets.swift:402-433). Null without one.
         */
        private fun person(width: Int, height: Int, smoothed: Boolean = false): EffectTarget? {
            val frame = personFrames.getOrPut(smoothed) { assets.person(frameSourceTime, smoothed) } ?: return null
            return placed.getOrPut(Triple(width, height, smoothed)) {
                // Laid on the whole frame, then the frame brought to [width] × [height]: a smaller picture of the frame (a
                // quarter of 270 is 67) need not keep its shape, and framed there itself the mask would be fitted inside it.
                val frameSize = EffectSize(size.width.toDouble(), size.height.toDouble())
                val toMask = app.takes.core.Affine.scale(frameSize.width / width, frameSize.height / height)
                    .concatenating(app.takes.core.PersonMaskPlacement.toMask(clip, frameOffset, frame.upright, frameSize, frame.rotation))
                gpu.placeMask(personSource(frame.mask), "person.${width}x$height.$smoothed", width, height, toMask)
            }
        }

        // A stored mask uploaded as a texture: its value in every channel, rows from the bottom as the chain's textures.
        private fun personSource(mask: app.takes.core.PersonMaskChunks.Mask): EffectSource {
            if (mask !== personUploaded || personTexture == 0) {
                if (personTexture == 0) personTexture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
                val bytes = ByteBuffer.allocateDirect(mask.width * mask.height * 4)
                for (row in mask.height - 1 downTo 0) for (x in 0 until mask.width) {
                    val a = mask.rows[row * mask.width + x]
                    bytes.put(a).put(a).put(a).put(0xff.toByte())
                }
                bytes.flip()
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, personTexture)
                for (filter in listOf(GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_TEXTURE_MAG_FILTER)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, filter, GLES20.GL_LINEAR)
                for (wrap in listOf(GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_TEXTURE_WRAP_T)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, wrap, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, mask.width, mask.height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
                GlUtil.checkGlError()
                personUploaded = mask
            }
            return EffectSource(personTexture, mask.width, mask.height, encoded = false)
        }

        // The picked picture of a background, uploaded once: rows from the bottom, as the chain's textures.
        private var picture: Bitmap? = null
        private var pictureTexture = 0

        private fun pictureSource(bitmap: Bitmap): EffectSource {
            if (bitmap !== picture || pictureTexture == 0) {
                if (pictureTexture == 0) pictureTexture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val bytes = ByteBuffer.allocateDirect(pixels.size * 4)
                for (row in bitmap.height - 1 downTo 0) for (x in 0 until bitmap.width) {
                    val c = pixels[row * bitmap.width + x]
                    bytes.put((c shr 16).toByte()).put((c shr 8).toByte()).put(c.toByte()).put(0xff.toByte())
                }
                bytes.flip()
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pictureTexture)
                for (filter in listOf(GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_TEXTURE_MAG_FILTER)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, filter, GLES20.GL_LINEAR)
                for (wrap in listOf(GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_TEXTURE_WRAP_T)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, wrap, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, bitmap.width, bitmap.height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
                GlUtil.checkGlError()
                picture = bitmap
            }
            return EffectSource(pictureTexture, bitmap.width, bitmap.height, encoded = true)
        }

        /**
         * The lattice of the grain of [frame] for grain of [grainSize] pixels at 1080: four bytes a point, the
         * core's noise for red, green, blue and monochrome (`VideoEffectGrain.lattice`), −1…1 as 0…255, read
         * between its points by the texture's filtering. Monochrome grain alone needs only the last.
         */
        private fun lattice(grainSize: Double, colour: Boolean, frame: Int): Lattice {
            val lattice = lattices.getOrPut(Pair(grainSize, colour)) {
                val made = IntArray(1)
                GLES20.glGenTextures(1, made, 0)
                Lattice(made[0])
            }
            val (columns, rows) = VideoEffectGrain.latticeSize(size.width, size.height, grainSize)
            if (lattice.frame == frame && lattice.columns == columns && lattice.rows == rows) return lattice
            if (specialised && drawsLattice(lattice, columns, rows, colour, frame)) return lattice
            val bytes = ByteBuffer.allocateDirect(columns * rows * 4)
            fun level(noise: Double): Byte = ((noise * 0.5 + 0.5) * 255).roundToInt().toByte()
            val seed = frame.toUInt() + 13u
            for (y in 0 until rows) for (x in 0 until columns) {
                if (colour) {
                    val n = VideoEffectGrain.lattice(x, y, frame)
                    bytes.put(level(n.x)).put(level(n.y)).put(level(n.z)).put(level(n.w))
                } else {
                    val b = level(VideoEffectShaderMath.random01(x.toUInt(), y.toUInt(), seed) * 2 - 1)
                    bytes.put(b).put(b).put(b).put(b)
                }
            }
            bytes.flip()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lattice.texture)
            for (filter in listOf(GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_TEXTURE_MAG_FILTER)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, filter, GLES20.GL_LINEAR)
            for (wrap in listOf(GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_TEXTURE_WRAP_T)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, wrap, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, columns, rows, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
            GlUtil.checkGlError()
            lattice.frame = frame
            lattice.columns = columns
            lattice.rows = rows
            return lattice
        }

        // The recorder's grain: the same lattice, hashed by the GPU, where a frame's worth of points on the processor
        // would take longer than the frame (a 1080p picture's fine grain has about a million of them).
        private var latticeProgram = 0
        private var latticeWorks: Boolean? = null

        /** Draws the lattice of [frame] into [lattice] with the GPU: false where it cannot, OpenGL ES 3 being needed. */
        private fun drawsLattice(lattice: Lattice, columns: Int, rows: Int, colour: Boolean, frame: Int): Boolean {
            if (latticeWorks == null) latticeWorks = EffectMatteWindow.supported()
            if (latticeWorks != true) return false
            try {
                // A plain program: its numbers are whole ones, which GlProgram does not set.
                if (latticeProgram == 0) latticeProgram = CameraFrames.link(LATTICE_VERTEX, LATTICE_FRAGMENT)
                if (lattice.columns != columns || lattice.rows != rows || lattice.framebuffer == 0) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lattice.texture)
                    for (filter in listOf(GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_TEXTURE_MAG_FILTER)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, filter, GLES20.GL_LINEAR)
                    for (wrap in listOf(GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_TEXTURE_WRAP_T)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, wrap, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, columns, rows, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                    if (lattice.framebuffer != 0) GlUtil.deleteFbo(lattice.framebuffer)
                    lattice.framebuffer = GlUtil.createFboForTexture(lattice.texture)
                }
                GlUtil.focusFramebufferUsingCurrentContext(lattice.framebuffer, columns, rows)
                GLES20.glUseProgram(latticeProgram)
                android.opengl.GLES30.glUniform1ui(GLES20.glGetUniformLocation(latticeProgram, "uFrame"), frame.toUInt().toInt())
                GLES20.glUniform1i(GLES20.glGetUniformLocation(latticeProgram, "uColour"), if (colour) 1 else 0)
                val position = GLES20.glGetAttribLocation(latticeProgram, "aPosition")
                GLES20.glEnableVertexAttribArray(position)
                GLES20.glVertexAttribPointer(position, 4, GLES20.GL_FLOAT, false, 0, GlUtil.createBuffer(GlUtil.getNormalizedCoordinateBounds()))
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            } catch (failure: Exception) {
                android.util.Log.w("EffectRendering", "Grain on the processor", failure)
                latticeWorks = false
                return false
            }
            lattice.frame = frame
            lattice.columns = columns
            lattice.rows = rows
            return true
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
            windows.values.forEach { it.delete() }
            windows.clear()
            gpu.release()
            for (lattice in lattices.values) {
                GLES20.glDeleteTextures(1, intArrayOf(lattice.texture), 0)
                if (lattice.framebuffer != 0) GlUtil.deleteFbo(lattice.framebuffer)
            }
            if (latticeProgram != 0) GLES20.glDeleteProgram(latticeProgram)
            if (pictureTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(pictureTexture), 0)
            if (personTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(personTexture), 0)
            program?.delete()
            kinds.values.forEach { it.delete() }
        }
    }
}

/**
 * The preview's side of the effects, on Android 13 and later: a deck's
 * picture through the passes of its clip, one shader for each. Grain is not
 * drawn; positions are those of the deck's view, which shows the clip fitted,
 * before its crop. The pictures the export draws before a pass (blurs,
 * highlights, a Quantization's smoothing, a Chroma Key's grown and softened
 * matte) are stand-ins worked out at each pixel from a few reads: the
 * preview shows a simplified version of Halation, 16mm's softness, Super 8,
 * Widescreen's flares and blur fill, Quantization's smoothing and Chroma
 * Key's edges, screen detection and background blur.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class EffectPreview {
    private val shaders = mutableListOf<Pair<Int, RuntimeShader>>()
    private val none by lazy { BitmapShader(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    private var shown: Pair<Bitmap, BitmapShader>? = null

    /**
     * The effect for a deck [width] by [height] pixels showing [passes];
     * null when there are none. [picture] gives a Chroma Key's Image
     * background by the effect's id.
     */
    fun effect(
        passes: List<EffectUniforms>, width: Float, height: Float, picture: (String) -> Bitmap? = { null },
        /** The person mask of the frame, raw or (true) Auto Background's smoothed stream; null without one. */
        person: (Boolean) -> PersonFrame? = { null },
    ): RenderEffect? {
        var effect: RenderEffect? = null
        passes.forEachIndexed { index, pass ->
            if (shaders.size <= index) shaders += Pair(pass.kind, RuntimeShader(EffectShader.preview(pass.kind)))
            if (shaders[index].first != pass.kind) shaders[index] = Pair(pass.kind, RuntimeShader(EffectShader.preview(pass.kind)))
            val shader = shaders[index].second
            shader.setFloatUniform("uKind", pass.kind.toFloat())
            shader.setFloatUniform("uHead", EffectShader.head(pass))
            shader.setFloatUniform("uSize", width, height)
            for (slot in 0 until EffectShader.SLOTS) shader.setFloatUniform("uP$slot", EffectShader.slot(pass, slot))
            val extras = EffectShader.extras(pass, width.roundToInt(), height.roundToInt())
            val frame = when {
                pass.effect == VideoEffectID.AutoBackground -> person(true)
                pass.effect == VideoEffectID.ColorIsolation && pass.number("mode") > .5 -> person(false)
                pass.effect == VideoEffectID.Quantization && pass.number("faceDetail") > .5 && pass.number("colors") <= 4 -> person(false)
                pass.effect == VideoEffectID.DollyZoom -> person(false)
                else -> null
            }
            personInput(shader, frame, width, height)
            if (frame != null) {
                when (pass.effect) {
                    VideoEffectID.AutoBackground -> extras[2] = 1f
                    VideoEffectID.DollyZoom -> extras[1] = 1f
                    else -> extras[0] = 1f
                }
            }
            shader.setFloatUniform("uX0", extras.copyOfRange(0, 4))
            shader.setFloatUniform("uX1", extras.copyOfRange(4, 8))
            shader.setFloatUniform("uGrainSize", 1f)
            shader.setFloatUniform("uBlur", EffectShader.previewBlurs(pass, width, height))
            shader.setFloatUniform("uPalette", EffectShader.palette(pass))
            shader.setFloatUniform("uThresholds", EffectShader.thresholds(pass))
            shader.setFloatUniform("uCount", EffectShader.count(pass))
            backdrop(shader, pass, width, height, if (pictured(pass)) picture(pass.id) else null)
            val drawn = RenderEffect.createRuntimeShaderEffect(shader, "content")
            effect = effect?.let { RenderEffect.createChainEffect(drawn, it) } ?: drawn
        }
        return effect
    }

    /** Whether [pass] is a Chroma Key or an Auto Background with an Image background. */
    private fun pictured(pass: EffectUniforms): Boolean =
        (pass.effect == VideoEffectID.ChromaKey || pass.effect == VideoEffectID.AutoBackground) && EffectShader.background(pass) == "Image"

    private var personShown: Pair<app.takes.core.PersonMaskChunks.Mask, BitmapShader>? = null

    /**
     * The person mask as the pass's `person` input, laid on the deck as the deck shows the clip: upright, fitted,
     * before its crop and mirror, which the deck's view applies after the effects.
     */
    private fun personInput(shader: RuntimeShader, frame: PersonFrame?, width: Float, height: Float) {
        if (frame == null) {
            shader.setInputShader("person", none)
            shader.setFloatUniform("uPerson", 0f, 1f, 1f, 0f)
            shader.setFloatUniform("uPersonX", 0f, 0f, 0f)
            shader.setFloatUniform("uPersonY", 0f, 0f, 0f)
            return
        }
        val mask = frame.mask
        val kept = personShown?.takeIf { it.first === mask } ?: run {
            val bitmap = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ALPHA_8)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(mask.rows))
            Pair(mask, BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR })
        }.also { personShown = it }
        shader.setInputShader("person", kept.second)
        val deck = EffectSize(width.toDouble(), height.toDouble())
        val toMask = app.takes.core.PersonMaskPlacement.toMask(app.takes.core.PictureFraming.fit(frame.upright, deck), frame.upright, frame.rotation)
            .concatenating(app.takes.core.Affine.scale(mask.width.toDouble(), mask.height.toDouble()))
        shader.setFloatUniform("uPerson", 1f, mask.width.toFloat(), mask.height.toFloat(), 0f)
        shader.setFloatUniform("uPersonX", toMask.a.toFloat(), toMask.c.toFloat(), toMask.tx.toFloat())
        shader.setFloatUniform("uPersonY", toMask.b.toFloat(), toMask.d.toFloat(), toMask.ty.toFloat())
    }

    /** A Chroma Key's or an Auto Background's Image background placed as the export places it, or nothing. */
    private fun backdrop(shader: RuntimeShader, pass: EffectUniforms, width: Float, height: Float, bitmap: Bitmap?) {
        if (bitmap == null) {
            shader.setInputShader("backdrop", none)
            shader.setFloatUniform("uPlace", if (pictured(pass)) 1f else 0f, 0f, 0f, 0f)
            shader.setFloatUniform("uBackdrop", 0f, 0f)
            return
        }
        val kept = shown?.takeIf { it.first === bitmap } ?: Pair(bitmap, BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)).also { shown = it }
        shader.setInputShader("backdrop", kept.second)
        val fill = pass.number("fit") < .5
        val scale = (if (fill) maxOf(width / bitmap.width, height / bitmap.height) else minOf(width / bitmap.width, height / bitmap.height)) * pass.number("backgroundZoom").toFloat()
        shader.setFloatUniform("uPlace", scale, (width - bitmap.width * scale) * pass.number("backgroundX").toFloat(), (height - bitmap.height * scale) * pass.number("backgroundY").toFloat(), 0f)
        shader.setFloatUniform("uBackdrop", bitmap.width.toFloat(), bitmap.height.toFloat())
    }

    private val nightFix by lazy { RuntimeShader(NIGHT_FIX) }

    /**
     * Night fix Fast on a deck's raw picture, before its color: the adjust
     * pass and the mix, without the chroma cleanup, a simplified version of
     * the export's. Null where nothing is drawn.
     */
    fun nightFix(effects: List<VideoEffectSettings>?, sourceTime: Double): RenderEffect? {
        val effect = NightFixPasses.active(effects, sourceTime) ?: return null
        val mix = effect.number("intensity", sourceTime)
        val amount = effect.number("reduction", sourceTime)
        val stops = effect.number("brightness", sourceTime)
        if (mix <= 0 || amount <= 0 && stops == 0.0) return null
        nightFix.setFloatUniform("uFix", NightFixPasses.adjust(amount, stops, effect.number("detail", sourceTime)))
        nightFix.setFloatUniform("uMix", mix.toFloat())
        return RenderEffect.createRuntimeShaderEffect(nightFix, "content")
    }

    private companion object {
        const val NIGHT_FIX = """
            uniform shader content;
            uniform vec4 uFix;
            uniform float uMix;
            const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
            vec3 encode709(vec3 x) { x = max(x, 0.0); return mix(4.5 * x, 1.099 * pow(x, vec3(0.45)) - 0.099, step(vec3(0.018), x)); }
            vec3 decode709(vec3 x) { x = max(x, 0.0); return mix(x / 4.5, pow((x + 0.099) / 1.099, vec3(1.0 / 0.45)), step(vec3(0.081), x)); }
            vec4 main(vec2 xy) {
                vec3 raw = content.eval(xy).rgb;
                vec3 mean = vec3(0.0);
                vec3 square = vec3(0.0);
                for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) {
                    vec3 n = content.eval(xy + vec2(float(i), float(j)) * uFix.x).rgb;
                    mean += n / 9.0;
                    square += n * n / 9.0;
                }
                vec3 keep = clamp(1.0 - uFix.z / max(square - mean * mean, vec3(1e-8)), 0.0, 1.0);
                vec3 c = decode709(mix(raw, mean + keep * (raw - mean), uFix.y));
                float y = max(0.0, dot(c, LUMA));
                c = max(c * (y <= 1.0 ? uFix.w / (1.0 + (uFix.w - 1.0) * y) : 1.0), 0.0);
                return vec4(encode709(mix(decode709(raw), c, uMix)), 1.0);
            }
        """
    }
}
