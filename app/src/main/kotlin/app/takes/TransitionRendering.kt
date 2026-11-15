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
class SceneDrawing(val mode: Int, val kindA: Float, val kindB: Float, val fill: FloatArray, val values: FloatArray) {
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
                    SceneDrawing(WHIP, first, second, fill, values)
                }
                is TransitionFrame.Defocus -> {
                    put(0, frame.blurRadius, frame.mix, frame.ringAmount, frame.ringSize)
                    SceneDrawing(DEFOCUS, first, second, fill, values)
                }
            }
        }
    }
}

/** The shader of every transition, written once and worded for each of the two renderers. */
object TransitionShader {
    // Mixes happen in linear light, as in the original: pixels are decoded from sRGB, combined, and encoded again.
    private const val BODY = """
        vec3 lin(vec3 c) {
            c = clamp(c, 0.0, 1.0);
            return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));
        }
        vec3 enc(vec3 c) {
            c = clamp(c, 0.0, 1.0);
            return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
        }
        // One side's picture at a pixel of the frame; past the frame its edge pixels go on.
        vec3 side(float kind, vec2 p) {
            if (kind > 2.5) return held(kind - 3.0, clamp(p, vec2(0.5), uSize - 0.5));
            if (kind > 1.5) return uFill;
            return deck(kind, clamp(p, vec2(0.5), uSize - 0.5));
        }
        // One side moved by an offset and blurred evenly along an axis, a radius to each side: in linear light.
        vec3 pan(float kind, vec2 q, vec2 offset, vec2 axis, float radius) {
            if (radius < 0.5) return lin(side(kind, q - offset));
            vec3 sum = vec3(0.0);
            for (int i = 0; i < 32; i++) {
                sum += lin(side(kind, q - offset + axis * radius * ((float(i) + 0.5) / 16.0 - 1.0)));
            }
            return sum / 32.0;
        }
        // One side through an aperture: the even disc a lens out of focus makes of every point, a little brighter
        // at its rim, in linear light so highlights spread as discs. The points lie on a sunflower spiral.
        vec3 disc(float kind, vec2 q, float radius, float ring, float rim) {
            if (radius < 0.5) return lin(side(kind, q));
            vec3 sum = vec3(0.0);
            float total = 0.0;
            vec2 turn = vec2(1.0, 0.0);
            for (int i = 0; i < 48; i++) {
                float r = sqrt((float(i) + 0.5) / 48.0);
                float weight = 1.0 + ring * step(1.0 - rim, r);
                sum += weight * lin(side(kind, q + turn * (r * radius)));
                total += weight;
                // The golden angle on from the last point.
                turn = vec2(turn.x * -0.737369 - turn.y * 0.675490, turn.x * 0.675490 + turn.y * -0.737369);
            }
            return sum / total;
        }
        // Whether a pixel is in a rectangle given as left, top, width, height.
        bool within(vec2 p, vec4 r) { return p.x >= r.x && p.y >= r.y && p.x < r.x + r.z && p.y < r.y + r.w; }
        vec3 scene(vec2 q) {
            if (uMode < 0.5) return side(uKind.x, q);
            if (uMode < 1.5) return enc(mix(lin(side(uKind.x, q)), lin(side(uKind.y, q)), uA.x));
            if (uMode < 2.5) return enc(uA.x * lin(side(uKind.x, q)) + uA.y * lin(uFill) + uA.z * lin(side(uKind.y, q)));
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
                vec3 key = clamp(side(uKind.x, q), 0.0, 1.0);
                float share = clamp(uA.x * dot(key, vec3(0.2126, 0.7152, 0.0722)) + uA.y, 0.0, 1.0);
                return enc(mix(lin(key), lin(side(uKind.y, q)), share));
            }
            // A split screen: each picture framed in its half over black, the second half over the first, then
            // the line between them; the whole blends from the first picture alone and to the second alone.
            vec3 pair = vec3(0.0);
            if (within(q, uA)) pair = lin(side(uKind.x, uD.xy + (q - uA.xy) * uD.z));
            if (within(q, uB)) pair = lin(side(uKind.y, uE.xy + (q - uB.xy) * uE.z));
            if (within(q, uC)) pair = lin(vec3(uF.w));
            vec3 sum = uF.y * pair;
            if (uF.x > 0.0) sum += uF.x * lin(side(uKind.x, q));
            if (uF.z > 0.0) sum += uF.z * lin(side(uKind.y, q));
            return enc(sum);
        }
    """

    private const val UNIFORMS = """
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
        uniform float uHalves;
        varying vec2 vTex;
        $UNIFORMS
        vec3 deck(float kind, vec2 p) { return texture2D(uTex, vec2(p.x / uSize.x, 1.0 - (p.y / uSize.y + kind) / uHalves)).rgb; }
        vec3 held(float which, vec2 p) {
            vec2 at = vec2(p.x / uSize.x, 1.0 - p.y / uSize.y);
            if (which > 0.5) return texture2D(uHeld1, at).rgb;
            return texture2D(uHeld0, at).rgb;
        }
        $BODY
        void main() { gl_FragColor = vec4(scene(vec2(vTex.x * uSize.x, (1.0 - vTex.y) * uSize.y)), 1.0); }
    """

    const val VERTEX = """
        attribute vec4 aPosition;
        varying vec2 vTex;
        void main() { gl_Position = aPosition; vTex = aPosition.xy * 0.5 + 0.5; }
    """

    /** For the preview: the layer holds the two deck views one above the other; the frame is drawn over the upper one. */
    const val PREVIEW = """
        uniform shader content;
        $UNIFORMS
        vec3 deck(float kind, vec2 p) { return content.eval(vec2(p.x, p.y + kind * uSize.y)).rgb; }
        vec3 held(float which, vec2 p) { return deck(which, p); }
        $BODY
        vec4 main(vec2 xy) {
            if (xy.y >= uSize.y) return vec4(0.0);
            return vec4(scene(xy), 1.0);
        }
    """

    val names = listOf("uA", "uB", "uC", "uD", "uE", "uF")
}

/** The preview's side of the shader, on Android 13 and later. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class TransitionPreviewShader {
    private val shader = RuntimeShader(TransitionShader.PREVIEW)

    /** The effect for a layer [width] pixels wide holding the two decks, each [height] pixels tall. */
    fun effect(drawing: SceneDrawing, width: Float, height: Float): RenderEffect {
        shader.setFloatUniform("uSize", width, height)
        shader.setFloatUniform("uMode", drawing.mode.toFloat())
        shader.setFloatUniform("uKind", drawing.kindA, drawing.kindB)
        shader.setFloatUniform("uFill", drawing.fill)
        TransitionShader.names.forEachIndexed { row, name -> shader.setFloatUniform(name, drawing.values.copyOfRange(row * 4, row * 4 + 4)) }
        return RenderEffect.createRuntimeShaderEffect(shader, "content")
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
 * Lays the [decks] of an export, one or two, one above the other in a frame
 * as many times as tall, untouched, the first on top. [TransitionEffect] then
 * makes the movie's frame from them, which Media3's own blend of sequences
 * could not: it only lays one over the other by opacity, on encoded pixels.
 *
 * The first input is not a deck but the clock, the sequence whose frames
 * give the movie its frame times: it is not drawn. The decks are the inputs
 * after it.
 */
class DecksCompositor(private val canvas: Size, private val decks: Int) : VideoCompositorSettings {
    override fun getOutputSize(inputSizes: List<Size>): Size = Size(canvas.width, canvas.height * decks)

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings = object : OverlaySettings {
        private val deck = inputId - 1

        override fun getAlphaScale(): Float = if (deck < 0) 0f else 1f

        override fun getBackgroundFrameAnchor(): android.util.Pair<Float, Float> = android.util.Pair.create(0f, if (decks < 2) 0f else if (deck <= 0) 0.5f else -0.5f)
    }
}

/**
 * Draws every frame of an export as its [plan] describes it
 * ([RenderPlan.moment]): the clip that is showing, or the two sides of a
 * transition combined. The input is the frame [DecksCompositor] makes of the
 * [decks], one or two. [frames] is the frame rate of the movie
 * ([RenderPlan.frameRate]) and [content] where a clip's picture lies in the
 * frame.
 */
class TransitionEffect(
    private val plan: RenderPlan, private val canvas: Size, private val decks: Int,
    private val frames: (TransitionLayout.Join) -> Double, private val content: (Int) -> Rect,
) : GlEffect {
    /** The effect of [clips] by themselves, planned as an export plans them. */
    constructor(clips: List<TimelineClip>, canvas: Size, decks: Int, frames: (TransitionLayout.Join) -> Double, content: (Int) -> Rect) :
        this(TrackRenderPlan.of(EditState(clips)), canvas, decks, frames, content)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program()

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

    private inner class Program : BaseGlShaderProgram(/* useHighPrecisionColorComponents = */ false, /* texturePoolCapacity = */ 1) {
        private val size = FrameSize(canvas.width.toDouble(), canvas.height.toDouble())

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
            GLES20.glAttachShader(program, compiled(GLES20.GL_FRAGMENT_SHADER, TransitionShader.FRAGMENT))
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "Transition shader: " + GLES20.glGetProgramInfoLog(program) }
            position = GLES20.glGetAttribLocation(program, "aPosition")
        }

        private fun uniform(name: String) = GLES20.glGetUniformLocation(program, name)

        private fun draw(drawing: SceneDrawing, inputTexId: Int) {
            GLES20.glUseProgram(program)
            // A kept frame that does not exist yet is never read; the input stands in so the sampler has a texture.
            for ((unit, texture) in listOf(inputTexId, kept[0], kept[1]).withIndex()) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (texture != 0) texture else inputTexId)
            }
            GLES20.glUniform1i(uniform("uTex"), 0)
            GLES20.glUniform1i(uniform("uHeld0"), 1)
            GLES20.glUniform1i(uniform("uHeld1"), 2)
            GLES20.glUniform1f(uniform("uHalves"), decks.toFloat())
            GLES20.glUniform2f(uniform("uSize"), canvas.width.toFloat(), canvas.height.toFloat())
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
            draw(drawing, inputTexId)
        }

        override fun release() {
            super.release()
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
