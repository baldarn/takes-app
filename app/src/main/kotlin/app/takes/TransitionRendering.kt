@file:OptIn(UnstableApi::class)

package app.takes

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.opengl.GLES20
import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import app.takes.core.EditState
import app.takes.core.Rect
import app.takes.core.RenderPlan
import app.takes.core.Size as FrameSize
import app.takes.core.TimelineClip
import app.takes.core.TrackRenderPlan
import app.takes.core.TransitionFrame
import app.takes.core.TransitionGeometry
import app.takes.core.TransitionLayout
import app.takes.core.TransitionScene
import app.takes.core.TransitionStyle

/**
 * One frame of the movie as numbers for the transition shader: what
 * [TransitionScene] says a moment holds, with the geometry turned top-down
 * (pixels from the top left, as both renderers count them).
 *
 * The preview and the export run the same shader text on the same numbers,
 * each in its own shading language, so a transition looks the same in both.
 */
class SceneDrawing(val mode: Int, val kindA: Float, val kindB: Float, val fill: FloatArray, val values: FloatArray, val blurScale: Double = 1.0) {
    /** The blur radius of a whip pan or a defocus, in pixels of the picture drawn; 0 for every other style. */
    val blurRadius: Float get() = when (mode) {
        WHIP -> values[6]
        DEFOCUS -> values[0]
        else -> 0f
    }

    /** Whether the shader blurs here: a whip pan or a defocus whose radius reaches half a pixel. */
    val blurs: Boolean get() = blurRadius >= 0.5f

    /**
     * The same drawing on the picture reduced from [width] × [height] to
     * [small]: the offsets and the radius in its pixels, as the original
     * multiplies them by the scale (`YouRec/TransitionRenderer.swift:228-233`, `:244-247`).
     */
    fun reduced(width: Int, height: Int, small: Pair<Int, Int>): SceneDrawing {
        val sx = small.first.toFloat() / width
        val sy = small.second.toFloat() / height
        val scaled = values.copyOf()
        when (mode) {
            WHIP -> {
                scaled[0] *= sx; scaled[1] *= sy; scaled[2] *= sx; scaled[3] *= sy
                scaled[6] *= if (values[5] > 0.5f) sy else sx
            }
            DEFOCUS -> scaled[0] *= (sx + sy) / 2
        }
        return SceneDrawing(mode, kindA, kindB, fill, scaled)
    }

    companion object {
        /** Where a side's picture comes from, as the shader's `uKind`: a deck (0 or 1), the fill color, or the frame kept of a deck (3 or 4). */
        const val FILL = 2f
        const val HELD = 3f

        const val SINGLE = 0
        const val DISSOLVE = 1
        const val FADE = 2
        const val WIPE = 3
        const val SPLIT = 4
        const val HITCHCOCK = 5
        const val WHIP = 6
        const val DEFOCUS = 7

        /**
         * The passes before a blur, each over one side ([kindA]): [REDUCE] makes the reduced picture, the average of
         * the pixels each of its pixels covers (`values` 0 and 1: how many across and down); [SPREAD] blurs it by a
         * Gaussian (`values` 0: the standard deviation in pixels, 1 and 2: the direction) before a defocus.
         */
        const val REDUCE = 8
        const val SPREAD = 9

        /** [REDUCE] of [kind] on a picture [across] by [down] pixels of the frame to each of its own. */
        fun reduce(kind: Float, across: Float, down: Float) = SceneDrawing(REDUCE, kind, kind, FloatArray(3), FloatArray(24).also { it[0] = across; it[1] = down })

        /** [SPREAD] of [kind] by [sigma] pixels along ([dx], [dy]). */
        fun spread(kind: Float, sigma: Float, dx: Float, dy: Float) = SceneDrawing(SPREAD, kind, kind, FloatArray(3), FloatArray(24).also { it[0] = sigma; it[1] = dx; it[2] = dy })

        /** The styles the shader draws, which are the ones the editor offers: all nine of the original. */
        val drawn: List<TransitionStyle> = listOf(TransitionStyle.Dissolve, TransitionStyle.FadeColor, TransitionStyle.Wipe, TransitionStyle.Split, TransitionStyle.Hitchcock, TransitionStyle.Whip, TransitionStyle.FlashCut, TransitionStyle.Still, TransitionStyle.Defocus)

        /**
         * The drawing of [moment] on a picture of [width] by [height] pixels.
         * [kind] says where the renderer has each side; [content] is where a
         * clip's own picture lies inside the frame, without the bars around it.
         */
        fun of(moment: TransitionScene.Moment, width: Float, height: Float, kind: (TransitionScene.Source) -> Float, content: (Int) -> Rect): SceneDrawing {
            val values = FloatArray(24)
            fun put(row: Int, vararg numbers: Double) = numbers.forEachIndexed { index, number -> values[row * 4 + index] = number.toFloat() }
            val fill = (listOf(moment.first, moment.second).filterIsInstance<TransitionScene.Source.Fill>().firstOrNull()?.color
                ?: (moment.frame as? TransitionFrame.FadeColor)?.color)?.let { floatArrayOf(it.red.toFloat(), it.green.toFloat(), it.blue.toFloat()) } ?: FloatArray(3)
            val first = kind(moment.first)
            val second = kind(moment.second)
            return when (val frame = moment.frame) {
                TransitionFrame.First -> SceneDrawing(SINGLE, first, first, fill, values)
                TransitionFrame.Second -> SceneDrawing(SINGLE, second, second, fill, values)
                is TransitionFrame.FadeColor -> {
                    put(0, frame.first, frame.solid, frame.second)
                    // A side that is not showing may not be playing at all: the fill stands in, weighted zero.
                    SceneDrawing(FADE, if (frame.first > 0) first else FILL, if (frame.second > 0) second else FILL, fill, values)
                }
                is TransitionFrame.Dissolve -> { put(0, frame.second); SceneDrawing(DISSOLVE, first, second, fill, values) }
                is TransitionFrame.Wipe -> {
                    // The band starts at p0, where the second picture ends, and runs along the direction of travel; y turned downward.
                    put(0, frame.edge.p0.x, height - frame.edge.p0.y, frame.edge.dx, -frame.edge.dy)
                    put(1, 2 * frame.edge.feather)
                    SceneDrawing(WIPE, first, second, fill, values)
                }
                is TransitionFrame.Split -> {
                    val whole = Rect(0.0, 0.0, width.toDouble(), height.toDouble())
                    // Each clip without the bars around it; a side with no clip is the fill, everywhere.
                    fun lies(source: TransitionScene.Source) = when (source) {
                        is TransitionScene.Source.Clip -> content(source.index)
                        is TransitionScene.Source.Held -> content(source.index)
                        is TransitionScene.Source.Fill -> whole
                    }
                    // The core counts y upward from the bottom; the renderers count it downward from the top.
                    fun put(row: Int, rect: Rect) = put(row, rect.x, height - rect.maxY, rect.width, rect.height)
                    put(0, frame.first)
                    put(1, frame.second)
                    put(2, frame.line)
                    val firstContent = lies(moment.first)
                    val secondContent = lies(moment.second)
                    val firstFramed = frame.firstFramed(firstContent.size)
                    val secondFramed = frame.secondFramed(secondContent.size)
                    put(3, firstContent.x + firstFramed.crop.x, firstContent.y + firstFramed.crop.y, 1 / firstFramed.scale)
                    put(4, secondContent.x + secondFramed.crop.x, secondContent.y + secondFramed.crop.y, 1 / secondFramed.scale)
                    put(5, frame.mix.first, frame.mix.pair, frame.mix.second, frame.lineColor.red)
                    SceneDrawing(SPLIT, first, second, fill, values)
                }
                is TransitionFrame.Hitchcock -> { put(0, frame.key.scale, frame.key.bias); SceneDrawing(HITCHCOCK, first, second, fill, values) }
                is TransitionFrame.Whip -> {
                    // Offsets are along the travel: to the right, or down the screen.
                    if (frame.vertical) put(0, 0.0, frame.firstOffset, 0.0, frame.secondOffset) else put(0, frame.firstOffset, 0.0, frame.secondOffset, 0.0)
                    put(1, if (frame.vertical) 0.0 else 1.0, if (frame.vertical) 1.0 else 0.0, frame.blurRadius, frame.mix)
                    SceneDrawing(WHIP, first, second, fill, values, frame.blurScale)
                }
                is TransitionFrame.Defocus -> {
                    put(0, frame.blurRadius, frame.mix, frame.ringAmount, frame.ringSize)
                    SceneDrawing(DEFOCUS, first, second, fill, values, frame.blurScale)
                }
            }
        }
    }
}

/** The shader of every transition, written once and worded for each of the two renderers. */
object TransitionShader {
    // Mixes happen in linear light, as in the original: pixels are decoded from sRGB, combined, and encoded again.
    // A color given as sRGB (the fill, the line of a split screen, a title) is the frame's own as it is ([sig]), a
    // title laid over a side by its opacity as it is encoded ([titled]), and a Hitchcock key read on the side as
    // encoded and held to 0…1 ([keyed]). For the preview, and for an SDR export ([HEAD]).
    internal const val PREVIEW_HEAD = """
        vec3 lin(vec3 c) {
            c = clamp(c, 0.0, 1.0);
            return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));
        }
        vec3 enc(vec3 c) {
            c = clamp(c, 0.0, 1.0);
            return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
        }
        vec3 sig(vec3 c) { return c; }
        vec3 titled(vec4 t, vec3 c) { return t.rgb + (1.0 - t.a) * c; }
        vec3 keyed(vec3 c) { return clamp(c, 0.0, 1.0); }
    """

    /**
     * The same for the export. Compiled with `HDR` defined ([HDR]), the pictures are HLG in BT.2020 ([HdrMovie]):
     * linear light is then counted from SDR white, which HLG puts at 75 % of its signal (ITU-R BT.2408), so a
     * highlight is above 1, as in the original's extended linear working space (`YouRec/VideoToolCompositor.swift:194`);
     * a color given as SDR is carried into HLG as SDR footage is ([HdrMovie.GLSL]); a Hitchcock key is read on the
     * side held to SDR white, as the original clamps HDR values for it (`YouRec/TransitionRenderer.swift:215-221`).
     * Without it, the program is the SDR one, line for line.
     */
    internal const val HEAD = """
        #ifdef HDR
        ${HdrMovie.GLSL}
        vec3 lin(vec3 c) { return hlgLin(c); }
        vec3 enc(vec3 c) { return hlgEnc(c); }
        vec3 sig(vec3 c) { return sdrToHlg(c); }
        vec3 titled(vec4 t, vec3 c) { return t.a > 0.0 ? sig(t.rgb / t.a) * t.a + (1.0 - t.a) * c : c; }
        vec3 keyed(vec3 c) { return hlgToSdr(c); }
        #else
        $PREVIEW_HEAD
        #endif
    """

    /** What goes before an export program's text to compile it for an HDR movie ([HEAD]). */
    const val HDR = "#define HDR 1\n"

    private const val SIDE = """
        // One side's picture at a pixel of the frame, with the titles of its deck over it before the sides are
        // combined, as the original draws a clip with its titles (VideoToolCompositor.swift:282); past the frame its
        // edge pixels go on.
        vec3 side(float kind, vec2 p) {
            if (kind > 1.5 && kind < 2.5) return sig(uFill);
            vec2 at = clamp(p, vec2(0.5), uSize - 0.5);
            float which = kind > 2.5 ? kind - 3.0 : kind;
            vec3 c = kind > 2.5 ? held(which, at) : deck(kind, at);
            return titled(title(which, at), c);
        }
    """

    // What comes after `side()`, in parts: the blurs, each a loop over the samples of a side, then the drawing of
    // every style from the two sides ([TAIL]).
    private const val PAN = """

        // One side moved by an offset and blurred evenly along an axis, a radius to each side: in linear light. The
        // samples lie a pixel apart, up to 128 of them: on the reduced picture the export blurs, at most 960 pixels
        // long, a smear without gaps. A larger picture drawn in one pass (a preview's view longer than that) takes
        // fewer, down to 32 at 3840 pixels, so its cost stays bounded (TransitionGeometry.panSamples); the preview
        // has then spread it along the axis to fill the space between them (TransitionGeometry.panSpread).
        vec3 pan(float kind, vec2 q, vec2 offset, vec2 axis, float radius) {
            if (radius < 0.5) return lin(side(kind, q - offset));
            float most = clamp(floor(128.0 * 960.0 / max(uSize.x, uSize.y)), 32.0, 128.0);
            float n = min(most, ceil(2.0 * radius));
            vec3 sum = vec3(0.0);
            for (int i = 0; i < 128; i++) {
                if (float(i) >= n) break;
                sum += lin(side(kind, q - offset + axis * radius * ((float(i) + 0.5) / n * 2.0 - 1.0)));
            }
            return sum / n;
        }
    """

    private const val DISC = """
        // One side through an aperture: the even disc a lens out of focus makes of every point, a little brighter
        // at its rim, in linear light so highlights spread as discs. The 96 points lie on a sunflower spiral; the
        // side has been spread by a Gaussian as wide as the space between them, so they make a disc and not dots.
        // A picture larger than 1920 pixels drawn in one pass (a preview's view that long) takes 48.
        vec3 disc(float kind, vec2 q, float radius, float ring, float rim) {
            if (radius < 0.5) return lin(side(kind, q));
            float n = max(uSize.x, uSize.y) > 1920.0 ? 48.0 : 96.0;
            vec3 sum = vec3(0.0);
            float total = 0.0;
            vec2 turn = vec2(1.0, 0.0);
            for (int i = 0; i < 96; i++) {
                if (float(i) >= n) break;
                float r = sqrt((float(i) + 0.5) / n);
                float weight = 1.0 + ring * step(1.0 - rim, r);
                sum += weight * lin(side(kind, q + turn * (r * radius)));
                total += weight;
                // The golden angle on from the last point.
                turn = vec2(turn.x * -0.737369 - turn.y * 0.675490, turn.x * 0.675490 + turn.y * -0.737369);
            }
            return sum / total;
        }
    """

    private const val REDUCE = """
        // One side reduced: the average, in linear light, of the uA.x by uA.y pixels a pixel of the reduced picture
        // covers, each read at its middle (a read between pixels would mix them as they are encoded), up to 8 by 8.
        vec3 reduce(float kind, vec2 q) {
            vec2 reads = clamp(ceil(uA.xy), 1.0, 8.0);
            vec3 sum = vec3(0.0);
            for (int j = 0; j < 8; j++) {
                for (int i = 0; i < 8; i++) {
                    if (float(i) >= reads.x || float(j) >= reads.y) continue;
                    sum += lin(side(kind, q + ((vec2(float(i), float(j)) + 0.5) / reads - 0.5) * uA.xy));
                }
            }
            return enc(sum / (reads.x * reads.y));
        }
    """

    private const val SPREAD = """
        // One side blurred along the direction uA.yz by a Gaussian of uA.x pixels, in linear light, out to three of them.
        vec3 spread(float kind, vec2 q) {
            float sigma = uA.x;
            if (sigma < 0.25) return side(kind, q);
            float reach = min(20.0, ceil(3.0 * sigma));
            vec3 sum = vec3(0.0);
            float total = 0.0;
            for (int i = -20; i <= 20; i++) {
                float x = float(i);
                if (abs(x) > reach) continue;
                float weight = exp(-0.5 * x * x / (sigma * sigma));
                sum += weight * lin(side(kind, q + uA.yz * x));
                total += weight;
            }
            return enc(sum / total);
        }
    """

    private const val REST = """
        // Whether a pixel is in a rectangle given as left, top, width, height.
        bool within(vec2 p, vec4 r) { return p.x >= r.x && p.y >= r.y && p.x < r.x + r.z && p.y < r.y + r.w; }
        vec3 scene(vec2 q) {
            if (uMode < 0.5) return side(uKind.x, q);
            if (uMode > 8.5) return spread(uKind.x, q);
            if (uMode > 7.5) return reduce(uKind.x, q);
            if (uMode < 1.5) return enc(mix(lin(side(uKind.x, q)), lin(side(uKind.y, q)), uA.x));
            if (uMode < 2.5) return enc(uA.x * lin(side(uKind.x, q)) + uA.y * lin(sig(uFill)) + uA.z * lin(side(uKind.y, q)));
            if (uMode < 3.5) {
                // A wipe: the second picture behind the edge, the first ahead of it, a ramp across its band.
                float share = clamp(1.0 - dot(q - uA.xy, uA.zw) / uB.x, 0.0, 1.0);
                return enc(mix(lin(side(uKind.x, q)), lin(side(uKind.y, q)), share));
            }
            if (uMode > 6.5) {
                // Defocus: both pictures out of focus; one replaces the other inside the blur. Only the one showing is worked out.
                if (uA.y <= 0.0) return enc(disc(uKind.x, q, uA.x, uA.z, uA.w));
                if (uA.y >= 1.0) return enc(disc(uKind.y, q, uA.x, uA.z, uA.w));
                return enc(mix(disc(uKind.x, q, uA.x, uA.z, uA.w), disc(uKind.y, q, uA.x, uA.z, uA.w), uA.y));
            }
            if (uMode > 5.5) {
                // A whip pan: both pictures slide along one axis, smeared along it, and change over on the way.
                return enc(mix(pan(uKind.x, q, uA.xy, uB.xy, uB.z), pan(uKind.y, q, uA.zw, uB.xy, uB.z), uB.w));
            }
            if (uMode > 4.5) {
                // Hitchcock: the second picture comes through the first by the first's own brightness, as it is encoded.
                vec3 first = side(uKind.x, q);
                float share = clamp(uA.x * dot(keyed(first), vec3(0.2126, 0.7152, 0.0722)) + uA.y, 0.0, 1.0);
                return enc(mix(lin(first), lin(side(uKind.y, q)), share));
            }
            // A split screen: each picture framed in its half over black, the second half over the first, then
            // the line between them; the whole blends from the first picture alone and to the second alone.
            vec3 pair = vec3(0.0);
            if (within(q, uA)) pair = lin(side(uKind.x, uD.xy + (q - uA.xy) * uD.z));
            if (within(q, uB)) pair = lin(side(uKind.y, uE.xy + (q - uB.xy) * uE.z));
            if (within(q, uC)) pair = lin(sig(vec3(uF.w)));
            vec3 sum = uF.y * pair;
            if (uF.x > 0.0) sum += uF.x * lin(side(uKind.x, q));
            if (uF.z > 0.0) sum += uF.z * lin(side(uKind.y, q));
            return enc(sum);
        }
        """

    /** What comes after `side()`: the drawing of every style from the two sides. */
    internal const val TAIL = PAN + DISC + REDUCE + SPREAD + REST

    /**
     * One part of the preview's program, for Android 13, whose shader compiler refuses a program past a size it counts
     * with every loop unrolled ("program is too large"): the drawing of every style but the blurs ([PLAIN]), of a
     * whip pan ([WHIP]), of a defocus ([DEFOCUS]), or a spread before a blur ([SPREAD]). The others' blurs are
     * stood in for by a side as it is, which their modes never reach; the pixels are those of the whole program.
     */
    enum class Part { PLAIN, WHIP, DEFOCUS, SPREAD }

    private const val PAN_STUB = "vec3 pan(float kind, vec2 q, vec2 offset, vec2 axis, float radius) { return lin(side(kind, q - offset)); }\n"
    private const val DISC_STUB = "vec3 disc(float kind, vec2 q, float radius, float ring, float rim) { return lin(side(kind, q)); }\n"
    private const val REDUCE_STUB = "vec3 reduce(float kind, vec2 q) { return side(kind, q); }\n"
    private const val SPREAD_STUB = "vec3 spread(float kind, vec2 q) { return side(kind, q); }\n"

    /** [TAIL] with only the blur [part] draws; the preview never reduces a picture. */
    internal fun tail(part: Part): String =
        (if (part == Part.WHIP) PAN else PAN_STUB) + (if (part == Part.DEFOCUS) DISC else DISC_STUB) + REDUCE_STUB +
            (if (part == Part.SPREAD) SPREAD else SPREAD_STUB) + REST

    /** Whether the preview's program is made in [Part]s: on Android 13, where the whole one is too large to compile. */
    val previewInParts: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** The part of the preview's program that draws [mode] of [SceneDrawing]. */
    fun part(mode: Int): Part = when (mode) {
        SceneDrawing.WHIP -> Part.WHIP
        SceneDrawing.DEFOCUS -> Part.DEFOCUS
        SceneDrawing.SPREAD -> Part.SPREAD
        else -> Part.PLAIN
    }

    internal const val UNIFORMS = """
        uniform vec2 uSize;
        uniform float uMode;
        uniform vec2 uKind;
        uniform vec3 uFill;
        uniform vec4 uA;
        uniform vec4 uB;
        uniform vec4 uC;
        uniform vec4 uD;
        uniform vec4 uE;
        uniform vec4 uF;
    """

    /** For the export: the input holds the decks one above the other ([uHalves] of them), the first on top. */
    const val FRAGMENT = """
        precision highp float;
        uniform sampler2D uTex;
        uniform sampler2D uHeld0;
        uniform sampler2D uHeld1;
        uniform sampler2D uTitle0;
        uniform sampler2D uTitle1;
        uniform vec3 uTitled;
        uniform float uHalves;
        uniform vec2 uLinear;
        varying vec2 vTex;
        $UNIFORMS
        $HEAD
        vec3 deck(float kind, vec2 p) { return texture2D(uTex, vec2(p.x / uSize.x, 1.0 - (p.y / uSize.y + kind) / uHalves)).rgb; }
        // With uLinear.x the kept pictures are the reduced ones of a blur, in linear light: read filtered as such.
        vec3 held(float which, vec2 p) {
            vec2 at = vec2(p.x / uSize.x, 1.0 - p.y / uSize.y);
            vec3 c = which > 0.5 ? texture2D(uHeld1, at).rgb : texture2D(uHeld0, at).rgb;
            return uLinear.x > 0.5 ? enc(c) : c;
        }
        // The titles of a deck (uTitled.x and .y say whether each has any here): a bitmap, its first row at the top,
        // its colors already multiplied by their opacity.
        vec4 title(float which, vec2 p) {
            if (which > 0.5) return uTitled.y > 0.5 ? texture2D(uTitle1, p / uSize) : vec4(0.0);
            return uTitled.x > 0.5 ? texture2D(uTitle0, p / uSize) : vec4(0.0);
        }
        $SIDE
        $TAIL
        // With uLinear.y the pixel is written in linear light, for a reduced picture of a blur. With uTitled.z the
        // first bitmap of titles goes over the finished frame instead: the titles of both clips of a split screen.
        void main() {
            vec2 q = vec2(vTex.x * uSize.x, (1.0 - vTex.y) * uSize.y);
            vec3 c = scene(q);
            if (uTitled.z > 0.5) c = titled(texture2D(uTitle0, q / uSize), c);
            gl_FragColor = vec4(uLinear.y > 0.5 ? lin(c) : c, 1.0);
        }
    """

    const val VERTEX = """
        attribute vec4 aPosition;
        varying vec2 vTex;
        void main() { gl_Position = aPosition; vTex = aPosition.xy * 0.5 + 0.5; }
    """

    /** For the preview: the layer holds the two deck views one above the other; the frame is drawn over the upper one; [tail] is the whole program's or a [Part]'s. */
    fun preview(tail: String = TAIL): String = """
        uniform shader content;
        $UNIFORMS
        vec3 deck(float kind, vec2 p) { return content.eval(vec2(p.x, p.y + kind * uSize.y)).rgb; }
        vec3 held(float which, vec2 p) { return deck(which, p); }
        // The preview draws a clip's titles on its deck's view.
        vec4 title(float which, vec2 p) { return vec4(0.0); }
        $PREVIEW_HEAD
        $SIDE
        $tail
        vec4 main(vec2 xy) {
            // A spread is made of both decks, each in its half, for the frame drawn after it.
            if (uMode > 8.5) {
                float deckAt = min(floor(xy.y / uSize.y), 1.0);
                return vec4(spread(deckAt, xy - vec2(0.0, deckAt * uSize.y)), 1.0);
            }
            if (xy.y >= uSize.y) return vec4(0.0);
            return vec4(scene(xy), 1.0);
        }
    """

    val names = listOf("uA", "uB", "uC", "uD", "uE", "uF")
}

/**
 * The preview's side of the shader, on Android 13 and later. It draws on the
 * view, at the view's size: a phone's in portrait is about the size the
 * original reduces a picture to before a blur (960 pixels on its long side),
 * one in landscape or full screen, or a tablet's, two or three times that.
 * It is not reduced, as a render effect cannot change the size it draws at
 * and the decks' views would have to be laid out smaller while they play.
 * Instead the shader takes fewer samples of a blur on a longer view and the
 * decks are spread first to fill the space between them, each pass an effect
 * of its own under the frame's: along the axis of a whip pan
 * ([TransitionGeometry.panSpread]), across and then down for the disc of a
 * defocus ([TransitionGeometry.defocusSpread]).
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class TransitionPreviewShader {
    // On Android 13 a program of each part, made when first needed ([TransitionShader.Part]); later, the whole one.
    private val parts = HashMap<TransitionShader.Part, RuntimeShader>()
    private val whole by lazy { RuntimeShader(TransitionShader.preview()) }
    private val across by lazy { RuntimeShader(if (TransitionShader.previewInParts) TransitionShader.preview(TransitionShader.tail(TransitionShader.Part.SPREAD)) else TransitionShader.preview()) }
    private val down by lazy { RuntimeShader(if (TransitionShader.previewInParts) TransitionShader.preview(TransitionShader.tail(TransitionShader.Part.SPREAD)) else TransitionShader.preview()) }

    /** The program that draws [drawing]'s frame. */
    private fun frame(drawing: SceneDrawing): RuntimeShader =
        if (!TransitionShader.previewInParts) whole
        else TransitionShader.part(drawing.mode).let { part -> parts.getOrPut(part) { RuntimeShader(TransitionShader.preview(TransitionShader.tail(part))) } }

    private fun RuntimeShader.set(drawing: SceneDrawing, width: Float, height: Float): RenderEffect {
        setFloatUniform("uSize", width, height)
        setFloatUniform("uMode", drawing.mode.toFloat())
        setFloatUniform("uKind", drawing.kindA, drawing.kindB)
        setFloatUniform("uFill", drawing.fill)
        TransitionShader.names.forEachIndexed { row, name -> setFloatUniform(name, drawing.values.copyOfRange(row * 4, row * 4 + 4)) }
        return RenderEffect.createRuntimeShaderEffect(this, "content")
    }

    /** The effect for a layer [width] pixels wide holding the two decks, each [height] pixels tall. */
    fun effect(drawing: SceneDrawing, width: Float, height: Float): RenderEffect {
        val frame = frame(drawing).set(drawing, width, height)
        if (drawing.mode == SceneDrawing.WHIP && drawing.blurs) {
            // On a view longer than 960 pixels the smear's samples lie more than a pixel apart: both decks are spread
            // along its axis first to fill the space between them, as a defocus's are for its disc.
            val sigma = TransitionGeometry.panSpread(drawing.blurRadius.toDouble(), maxOf(width, height).toDouble()).toFloat()
            if (sigma < 0.25f) return frame
            val vertical = drawing.values[5] > 0.5f
            return RenderEffect.createChainEffect(frame, down.set(SceneDrawing.spread(0f, sigma, if (vertical) 0f else 1f, if (vertical) 1f else 0f), width, height))
        }
        if (drawing.mode != SceneDrawing.DEFOCUS || !drawing.blurs) return frame
        // The shader takes half the samples of a disc on a picture longer than 1920 pixels.
        val samples = if (maxOf(width, height) > 1920f) TransitionGeometry.DEFOCUS_SAMPLES / 2 else TransitionGeometry.DEFOCUS_SAMPLES
        val sigma = TransitionGeometry.defocusSpread(drawing.blurRadius.toDouble(), samples).toFloat()
        val spread = RenderEffect.createChainEffect(
            down.set(SceneDrawing.spread(0f, sigma, 0f, 1f), width, height),
            across.set(SceneDrawing.spread(0f, sigma, 1f, 0f), width, height),
        )
        return RenderEffect.createChainEffect(frame, spread)
    }
}

/**
 * A whip pan or a defocus worked out as the original works it out (`YouRec/TransitionRenderer.swift:223-251`): each
 * side reduced by the drawing's [SceneDrawing.blurScale] (`:268-275`), spread for a defocus, then blurred and mixed
 * on the reduced picture by the transition shader's `scene()`. The renderer enlarges the result to the frame,
 * filtered: [TransitionEffect] as the frame, [LayersEffect] as the pair in transition behind the pictures in front of
 * it, as the original draws the pair of an edit of tracks (`YouRec/VideoToolCompositor.swift:295`). The reduced
 * picture is at most 960 pixels on its long side, so the shader's samples lie no more than a pixel apart on the
 * smear of a whip pan.
 *
 * It keeps the reduced pictures on the export's graphics context until [release]. [fill] and [held] are the
 * renderer's kinds of the fill color and of its first kept frame, the second kept frame the kind after it.
 */
internal class ReducedBlur(private val fill: Float, private val held: Float) {
    /**
     * Whether the reduced pictures are half floats in linear light, where the GPU draws into them (OpenGL ES 3.2, or
     * 3 with the extension), so a reduced picture is filtered as the original's linear working space filters it;
     * else eight bits, encoded.
     */
    val linear by lazy {
        val version = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
        version.startsWith("OpenGL ES 3.2") || (version.startsWith("OpenGL ES 3") && ("GL_EXT_color_buffer_float" in extensions || "GL_EXT_color_buffer_half_float" in extensions))
    }

    // The two sides, one to spread a side into, and the result. Made again when the size changes, which it does only
    // near the ends of a blur, where the radius is small.
    private var size = 0 to 0
    private val work = IntArray(4)
    private val framebuffers = IntArray(4)

    private fun at(wanted: Pair<Int, Int>) {
        if (wanted == size) return
        release()
        for (index in work.indices) {
            work[index] = GlUtil.createTexture(wanted.first, wanted.second, /* useHighPrecisionColorComponents = */ linear)
            // Read filtered and with its edge going on, as a reduced picture is enlarged and blurred.
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, work[index])
            for (filter in listOf(GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_TEXTURE_MAG_FILTER)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, filter, GLES20.GL_LINEAR)
            for (wrap in listOf(GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_TEXTURE_WRAP_T)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, wrap, GLES20.GL_CLAMP_TO_EDGE)
            framebuffers[index] = GlUtil.createFboForTexture(work[index])
        }
        size = wanted
    }

    fun release() {
        for (index in work.indices) if (work[index] != 0) {
            GlUtil.deleteFbo(framebuffers[index])
            GlUtil.deleteTexture(work[index])
            work[index] = 0
        }
        size = 0 to 0
    }

    /**
     * Works out [drawing], made for a frame [width] by [height], on its reduced picture and gives the texture that
     * holds it, in linear light when [linear]. [reduce] draws a [SceneDrawing.reduce] of a side at the frame's size
     * into the framebuffer in focus, writing linear light when [linear]; [pass] draws a drawing on the reduced picture
     * from the kept frames given, reading and writing linear light when [linear]. The framebuffer bound before is in
     * focus again after, at the frame's size.
     */
    fun draw(drawing: SceneDrawing, width: Int, height: Int, reduce: (SceneDrawing) -> Unit, pass: (SceneDrawing, IntArray, Int, Int) -> Unit): Int {
        val bound = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, bound, 0)
        val reduced = TransitionGeometry.blurCanvas(FrameSize(width.toDouble(), height.toDouble()), drawing.blurScale)
        at(reduced)
        val (across, down) = reduced
        val small = drawing.reduced(width, height, reduced)
        // A defocus works out only the side showing outside its change-over; a whip pan always both.
        val mix = drawing.values[if (drawing.mode == SceneDrawing.WHIP) 7 else 1]
        val kinds = listOf(drawing.kindA, drawing.kindB).mapIndexed { index, kind ->
            val shown = drawing.mode == SceneDrawing.WHIP || (if (index == 0) mix < 1f else mix > 0f)
            if (kind != fill && shown) {
                GlUtil.focusFramebufferUsingCurrentContext(framebuffers[index], across, down)
                reduce(SceneDrawing.reduce(kind, width.toFloat() / across, height.toFloat() / down))
                if (drawing.mode == SceneDrawing.DEFOCUS) {
                    val sigma = TransitionGeometry.defocusSpread(small.blurRadius.toDouble()).toFloat()
                    GlUtil.focusFramebufferUsingCurrentContext(framebuffers[2], across, down)
                    pass(SceneDrawing.spread(held, sigma, 1f, 0f), intArrayOf(work[index], 0), across, down)
                    GlUtil.focusFramebufferUsingCurrentContext(framebuffers[index], across, down)
                    pass(SceneDrawing.spread(held, sigma, 0f, 1f), intArrayOf(work[2], 0), across, down)
                }
            }
            if (kind == fill) kind else held + index
        }
        GlUtil.focusFramebufferUsingCurrentContext(framebuffers[3], across, down)
        pass(SceneDrawing(small.mode, kinds[0], kinds[1], small.fill, small.values), intArrayOf(work[0], work[1]), across, down)
        GlUtil.focusFramebufferUsingCurrentContext(bound[0], width, height)
        return work[3]
    }
}

/**
 * The longest side of a texture the device's GPU takes, in pixels; null when
 * it cannot be asked. Asked on a graphics context of its own, made and let go here.
 */
fun largestTextureSide(): Int? = try {
    val display = GlUtil.getDefaultEglDisplay()
    val context = GlUtil.createEglContext(display)
    try {
        val surface = GlUtil.createFocusedPlaceholderEglSurface(context, display)
        try {
            val side = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, side, 0)
            side[0].takeIf { it > 0 }
        } finally {
            GlUtil.destroyEglSurface(display, surface)
        }
    } finally {
        GlUtil.destroyEglContext(display, context)
    }
} catch (_: Exception) {
    null
}

/**
 * Lays the [decks] of an export one above the other in a frame as many times
 * as tall, untouched, the first on top; or, with [columns] 2, in a grid two
 * movies wide, row by row, for lanes that would make a column taller than the
 * GPU's largest texture (docs/LAYERS-PROBE.md, section 4.2). [TransitionEffect]
 * or [LayersEffect] then makes the movie's frame from them, which Media3's own
 * blend of sequences could not: it only lays one over the other by opacity,
 * on encoded pixels.
 *
 * The first input is not a deck but the clock, the sequence whose frames
 * give the movie its frame times: it is not drawn. The decks are the inputs
 * after it.
 */
class DecksCompositor(private val canvas: Size, private val decks: Int, private val columns: Int = 1) : VideoCompositorSettings {
    private val rows = (decks + columns - 1) / columns

    override fun getOutputSize(inputSizes: List<Size>): Size = Size(canvas.width * columns, canvas.height * rows)

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings = object : OverlaySettings {
        private val deck = inputId - 1
        private val cell = maxOf(deck, 0)

        override fun getAlphaScale(): Float = if (deck < 0) 0f else 1f

        // The middle of the deck's cell, from -1 to 1 across the frame and upward: for one or two decks in a column 0, 0.5 and -0.5.
        override fun getBackgroundFrameAnchor(): android.util.Pair<Float, Float> = android.util.Pair.create(
            if (columns < 2) 0f else (2f * (cell % columns) + 1f) / columns - 1f,
            if (rows < 2) 0f else 1f - (2f * (cell / columns) + 1f) / rows,
        )
    }

    companion object {
        /** The columns of the grid the decks are laid in: 1, a column, unless [decks] movies [height] pixels tall exceed [largestSide]. */
        fun columns(decks: Int, height: Int, largestSide: Int?): Int = if (decks > 1 && largestSide != null && height.toLong() * decks > largestSide) 2 else 1

        /** Whether [decks] movies of [width] by [height] laid in [columns] fit on a GPU whose largest texture is [largestSide]. */
        fun fits(decks: Int, columns: Int, width: Int, height: Int, largestSide: Int): Boolean =
            decks <= 1 || (width.toLong() * columns <= largestSide && height.toLong() * ((decks + columns - 1) / columns) <= largestSide)
    }
}

/**
 * Draws every frame of an export as its [plan] describes it
 * ([RenderPlan.moment]): the clip that is showing, or the two sides of a
 * transition combined. The input is the frame [DecksCompositor] makes of the
 * [decks], one or two. [frames] is the frame rate of the movie
 * ([RenderPlan.frameRate]) and [content] where a clip's picture lies in the
 * frame.
 *
 * With [titled], the titles are drawn here, by [TitlePainter] as the preview
 * draws them ([TitleTextures]): each clip's into the picture of its side before
 * the two are combined, as the original draws them for every style but a split
 * screen (`YouRec/VideoToolCompositor.swift:282`, `:409-411`), so a title is
 * wiped, keyed, slid and blurred with its clip; those of both clips of a split
 * screen once over the pair (`:298-301`). They are not drawn on the decks
 * themselves: an effect after a deck's `Presentation` can make Media3 give one
 * deck the frames of another (docs/EXPORT-TWO-SEQUENCES.md, section 8).
 */
class TransitionEffect(
    private val plan: RenderPlan, private val canvas: Size, private val decks: Int,
    private val frames: (TransitionLayout.Join) -> Double, private val content: (Int) -> Rect, private val titled: Boolean = false,
    /** Whether the decks are HLG in BT.2020, an HDR movie's ([HdrMovie]): the fill and the titles are carried into it. */
    private val hdr: Boolean = false,
) : GlEffect {
    /** The effect of [clips] by themselves, planned as an export plans them. */
    constructor(clips: List<TimelineClip>, canvas: Size, decks: Int, frames: (TransitionLayout.Join) -> Double, content: (Int) -> Rect, titled: Boolean = false) :
        this(TrackRenderPlan.of(EditState(clips)), canvas, decks, frames, content, titled)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(context.assets)

    /**
     * A moment a frame of [deck] is kept for: the start of a freeze, or the end of a chain, at [at]. The frame
     * kept is the last one the movie shows by [last]; a freeze holds it until [until].
     */
    private class Keep(val at: Double, val deck: Int, val until: Double, val last: Double)

    // The kept frames belong to the export, not to one program. They go with the export's
    // graphics context when it ends.
    private val kept = IntArray(2)
    private val keptFramebuffers = IntArray(2)
    private val keptFor = DoubleArray(2) { Double.NaN }
    private var spare = 0
    private var spareFramebuffer = 0

    private inner class Program(assets: android.content.res.AssetManager) : BaseGlShaderProgram(/* useHighPrecisionColorComponents = */ false, /* texturePoolCapacity = */ 1) {
        private val size = FrameSize(canvas.width.toDouble(), canvas.height.toDouble())

        // A bitmap of titles for each deck; for a split screen the first holds those over the pair.
        private val titles = if (titled) TitleTextures(2, canvas.width, canvas.height, assets) else null

        // Where the titles of this frame go, as the shader's uTitled: into deck 0's side, into deck 1's, over the frame.
        private val titledAs = FloatArray(3)

        // A still shows a frame its clip showed, which the sequence does not give again: the one on screen at the
        // moment the model names (RenderPlan.Freeze, its `shown`), which the preview holds as well. That
        // is the last frame before the freeze in a movie of 30 frames a second, the rate the model counts it in.
        private val freezes = plan.freezes.map { Keep(it.from, it.deck, it.until, it.shown + EDGE) }

        // A deck is black after the last clip of a chain, and can be so a frame early: each frame of the movie
        // takes the frame of the deck nearest to it in time, and in the last half interval of footage slower than
        // the movie that is the black one after it. A transition still showing the clip in those frames gets the
        // last one that came instead.
        private val endings = plan.endings.map { Keep(it.at, it.deck % decks, it.at, it.at - EDGE) }

        private var program = 0
        private var position = 0
        private var drawnAt = Double.NaN  // the time of the frame drawn before this one
        private val gone = BooleanArray(2)
        private val pixel = java.nio.ByteBuffer.allocateDirect(4)
        private val quad = GlUtil.createBuffer(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 0f, 1f, -1f, 1f, 0f, 1f, 1f, 1f, 0f, 1f))

        override fun configure(inputWidth: Int, inputHeight: Int): Size = canvas

        private fun compiled(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "Transition shader: " + GLES20.glGetShaderInfoLog(shader) }
            return shader
        }

        private fun prepare() {
            if (program != 0) return
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, compiled(GLES20.GL_VERTEX_SHADER, TransitionShader.VERTEX))
            GLES20.glAttachShader(program, compiled(GLES20.GL_FRAGMENT_SHADER, (if (hdr) TransitionShader.HDR else "") + TransitionShader.FRAGMENT))
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "Transition shader: " + GLES20.glGetProgramInfoLog(program) }
            position = GLES20.glGetAttribLocation(program, "aPosition")
        }

        private fun uniform(name: String) = GLES20.glGetUniformLocation(program, name)

        /**
         * Draws [drawing] on a picture [width] by [height] pixels, into the framebuffer in focus. The kept
         * frames are [held] (the reduced pictures, in the passes of a blur); with [linearIn] they hold linear
         * light, and with [linearOut] the picture drawn does. With [withTitles] the titles of the frame are drawn
         * where [titledAs] says; a deck kept alone and the passes on a reduced picture, which has them already, carry none.
         */
        private fun draw(
            drawing: SceneDrawing, inputTexId: Int, held: IntArray = kept, width: Int = canvas.width, height: Int = canvas.height,
            linearIn: Boolean = false, linearOut: Boolean = false, withTitles: Boolean = false,
        ) {
            GLES20.glUseProgram(program)
            // A kept frame or a bitmap of titles that does not exist yet is never read; the input stands in so the sampler has a texture.
            val titleTextures = titles?.textures ?: IntArray(2)
            for ((unit, texture) in listOf(inputTexId, held[0], held[1], titleTextures[0], titleTextures[1]).withIndex()) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (texture != 0) texture else inputTexId)
            }
            GLES20.glUniform1i(uniform("uTex"), 0)
            GLES20.glUniform1i(uniform("uHeld0"), 1)
            GLES20.glUniform1i(uniform("uHeld1"), 2)
            GLES20.glUniform1i(uniform("uTitle0"), 3)
            GLES20.glUniform1i(uniform("uTitle1"), 4)
            if (withTitles) GLES20.glUniform3fv(uniform("uTitled"), 1, titledAs, 0) else GLES20.glUniform3f(uniform("uTitled"), 0f, 0f, 0f)
            GLES20.glUniform1f(uniform("uHalves"), decks.toFloat())
            GLES20.glUniform2f(uniform("uLinear"), if (linearIn) 1f else 0f, if (linearOut) 1f else 0f)
            GLES20.glUniform2f(uniform("uSize"), width.toFloat(), height.toFloat())
            GLES20.glUniform1f(uniform("uMode"), drawing.mode.toFloat())
            GLES20.glUniform2f(uniform("uKind"), drawing.kindA, drawing.kindB)
            GLES20.glUniform3fv(uniform("uFill"), 1, drawing.fill, 0)
            TransitionShader.names.forEachIndexed { row, name -> GLES20.glUniform4fv(uniform(name), 1, drawing.values, row * 4) }
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 4, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GlUtil.checkGlError()
        }

        private fun target(): Pair<Int, Int> {
            val texture = GlUtil.createTexture(canvas.width, canvas.height, /* useHighPrecisionColorComponents = */ false)
            return texture to GlUtil.createFboForTexture(texture)
        }

        private val blur = ReducedBlur(SceneDrawing.FILL, SceneDrawing.HELD)

        /**
         * A whip pan or a defocus worked out on its reduced picture ([ReducedBlur]), which is then enlarged to the
         * frame, filtered. Each side is reduced with its titles, which are then smeared or blurred with it, as the
         * original blurs a clip drawn with its titles (`YouRec/VideoToolCompositor.swift:282`, `YouRec/TransitionRenderer.swift:223-251`).
         */
        private fun drawBlurred(drawing: SceneDrawing, inputTexId: Int) {
            val linear = blur.linear
            val blurred = blur.draw(
                drawing, canvas.width, canvas.height,
                reduce = { draw(it, inputTexId, linearOut = linear, withTitles = true) },
                pass = { scene, held, width, height -> draw(scene, inputTexId, held, width, height, linear, linear) },
            )
            draw(SceneDrawing(SceneDrawing.SINGLE, SceneDrawing.HELD, SceneDrawing.HELD, FloatArray(3), FloatArray(24)), inputTexId, intArrayOf(blurred, 0), linearIn = linear)
        }

        /**
         * Keeps the picture [deck] is showing now, unless it is the black of a
         * sequence with nothing in it. False when it was black. With [replace]
         * false it only looks, and the frame kept before stays.
         */
        private fun keep(deck: Int, inputTexId: Int, replace: Boolean = true): Boolean {
            // Before anything else: making a framebuffer binds it.
            val bound = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, bound, 0)
            if (spare == 0) target().let { spare = it.first; spareFramebuffer = it.second }
            GlUtil.focusFramebufferUsingCurrentContext(spareFramebuffer, canvas.width, canvas.height)
            draw(SceneDrawing(SceneDrawing.SINGLE, deck.toFloat(), deck.toFloat(), FloatArray(3), FloatArray(24)), inputTexId)
            // A frame of a sequence with nothing in it is black to the last bit, everywhere.
            val empty = SAMPLES.all { (x, y) ->
                GLES20.glReadPixels((x * (canvas.width - 1)).toInt(), (y * (canvas.height - 1)).toInt(), 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixel.rewind())
                pixel.get(0).toInt() == 0 && pixel.get(1).toInt() == 0 && pixel.get(2).toInt() == 0
            }
            if (!empty && replace) {
                val (texture, framebuffer) = kept[deck] to keptFramebuffers[deck]
                kept[deck] = spare
                keptFramebuffers[deck] = spareFramebuffer
                spare = texture
                spareFramebuffer = framebuffer
            }
            GlUtil.focusFramebufferUsingCurrentContext(bound[0], canvas.width, canvas.height)
            return !empty
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            prepare()
            // Frame times arrive cut to whole microseconds, a hair before the thirtieths they stand for;
            // a flash cut counts frames from them, so they are taken a microsecond later.
            val time = (presentationTimeUs + 1) / 1e6
            // Footage whose frames come further apart than the window (a few frames a second, or slowed down)
            // would keep nothing: the window reaches back over the last interval between frames as well.
            val window = maxOf(KEEP_WINDOW, if (time > drawnAt) KEEP_FRAMES * (time - drawnAt) else 0.0)
            drawnAt = time
            gone.fill(false)
            for (deck in 0 until decks) {
                // While a still is holding a deck's frame, that frame stays. It stays from the frame after it: in a
                // movie of more than 30 frames a second the clip shows a frame or two more before the freeze, and
                // should the deck be black in them already, the frame kept stands in.
                val holding = freezes.firstOrNull { it.deck == deck && time > it.last && time < it.until }
                if (holding != null) {
                    if (time < holding.at && keptFor[deck] == holding.at) gone[deck] = !keep(deck, inputTexId, replace = false)
                    continue
                }
                val coming = (freezes + endings).firstOrNull { it.deck == deck && time <= it.last && time >= it.last - window } ?: continue
                if (keep(deck, inputTexId)) keptFor[deck] = coming.at else gone[deck] = keptFor[deck] == coming.at
            }
            val moment = plan.moment(time, size, frames)
            val drawing = SceneDrawing.of(moment, canvas.width.toFloat(), canvas.height.toFloat(), { source ->
                when (source) {
                    is TransitionScene.Source.Clip -> if (gone[source.deck]) SceneDrawing.HELD + source.deck else source.deck.toFloat()
                    is TransitionScene.Source.Held -> if (keptFor[source.deck] == source.from) SceneDrawing.HELD + source.deck else source.deck.toFloat()
                    is TransitionScene.Source.Fill -> SceneDrawing.FILL
                }
            }, content)
            titledAs.fill(0f)
            titles?.let { titles ->
                val sides = plan.titleSides(moment, time)
                if (sides.over.isNotEmpty()) {
                    titles.prepare(listOf(sides.over))
                    titledAs[2] = 1f
                } else {
                    titles.prepare(sides.onDecks(moment))
                    for (deck in 0..1) titledAs[deck] = if (titles.drawn[deck]) 1f else 0f
                }
            }
            if (drawing.blurs) drawBlurred(drawing, inputTexId) else draw(drawing, inputTexId, withTitles = true)
        }

        override fun release() {
            super.release()
            titles?.release()
            blur.release()
            if (program != 0) GLES20.glDeleteProgram(program)
        }
    }

    private companion object {
        /** How near a frame's time may be to a moment and still count as before it or as on it, in seconds. */
        const val EDGE = 0.0001

        /** How long before a freeze or the end of a chain the frames are kept, in seconds; longer when frames come further apart ([KEEP_FRAMES]). */
        const val KEEP_WINDOW = 0.2

        /** The frames are kept at least this many frame intervals before a freeze, so the last one is among them however far apart they come. */
        const val KEEP_FRAMES = 2.5

        /** Where a frame is looked at to tell a picture from the black of an empty sequence, as shares of its size. */
        val SAMPLES = listOf(0.5f to 0.5f, 0.21f to 0.27f, 0.83f to 0.31f, 0.29f to 0.77f, 0.71f to 0.69f)
    }
}
