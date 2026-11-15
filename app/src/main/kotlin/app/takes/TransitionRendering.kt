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
import app.takes.core.Rect
import app.takes.core.Size as FrameSize
import app.takes.core.TimelineClip
import app.takes.core.TimelineEditing
import app.takes.core.TransitionFrame
import app.takes.core.TransitionLayout
import app.takes.core.TransitionPicture
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

        /** The styles the shader draws. The editor offers these; any other is shown as a dissolve, and cannot be made here. */
        val drawn: List<TransitionStyle> = listOf(TransitionStyle.Dissolve, TransitionStyle.FadeColor)

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
                // A style this version cannot draw: a plain dissolve by its share of the incoming picture.
                else -> { put(0, moment.secondShare / maxOf(1e-9, moment.firstShare + moment.secondShare)); SceneDrawing(DISSOLVE, first, second, fill, values) }
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
        vec3 scene(vec2 q) {
            if (uMode < 0.5) return side(uKind.x, q);
            if (uMode < 1.5) return enc(mix(lin(side(uKind.x, q)), lin(side(uKind.y, q)), uA.x));
            return enc(uA.x * lin(side(uKind.x, q)) + uA.y * lin(uFill) + uA.z * lin(side(uKind.y, q)));
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
 * Lays the two sequences of an export one above the other in a frame twice
 * as tall, untouched, the first on top. [TransitionEffect] then makes the
 * movie's frame from the two, which Media3's own blend of sequences could not:
 * it only lays one over the other by opacity, on encoded pixels.
 */
class DecksCompositor(private val canvas: Size) : VideoCompositorSettings {
    override fun getOutputSize(inputSizes: List<Size>): Size = Size(canvas.width, canvas.height * 2)

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings = object : OverlaySettings {
        override fun getBackgroundFrameAnchor(): android.util.Pair<Float, Float> = android.util.Pair.create(0f, if (inputId == 0) 0.5f else -0.5f)
    }
}

/**
 * Draws every frame of an export as [TransitionScene] describes it: the
 * clip that is showing, or the two sides of a transition combined. With
 * [decks] 2 the input is the frame [DecksCompositor] makes; with 1 it is the
 * only sequence. [content] is where a clip's picture lies in the frame.
 */
class TransitionEffect(private val clips: List<TimelineClip>, private val canvas: Size, private val decks: Int, private val content: (Int) -> Rect) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program()

    /** A moment whose last frame before it is kept: the start of a freeze, or the end of a chain. */
    private class Keep(val at: Double, val deck: Int, val until: Double)

    private inner class Program : BaseGlShaderProgram(/* useHighPrecisionColorComponents = */ false, /* texturePoolCapacity = */ 1) {
        private val deckOf = TransitionScene.decks(clips)
        private val size = FrameSize(canvas.width.toDouble(), canvas.height.toDouble())
        private val layout = TimelineEditing.layout(clips)

        // A still shows the last frame its clip showed, which the sequence does not give again.
        private val freezes = layout.joins.filter { it.transition != null && it.edge != -1 && TransitionLayout.longEnough(it.timing) }.mapNotNull { join ->
            val placement = layout.placements[join.index - 1]
            TransitionPicture.heldFrame(join.timing, join.origin, placement.start, placement.duration, TransitionScene.FRAMES_PER_SECOND)
                ?.let { Keep(it.from, deckOf[placement.index], it.until) }
        }

        // Media3 ends a clip a frame or two early (its frame-rate limit holds one back and drops it
        // at the end), and the sequence is black from there. A transition still showing the clip
        // in those frames gets the last one that came instead.
        private val endings = TimelineEditing.chains(clips).mapIndexed { index, chain -> Keep(chain.end, index % 2, chain.end) }

        private var program = 0
        private var position = 0
        private val kept = IntArray(2)
        private val keptFramebuffers = IntArray(2)
        private val keptFor = DoubleArray(2) { Double.NaN }
        private var spare = 0
        private var spareFramebuffer = 0
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
         * sequence with nothing in it. False when it was black.
         */
        private fun keep(deck: Int, inputTexId: Int): Boolean {
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
            if (!empty) {
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
            val time = presentationTimeUs / 1e6
            gone.fill(false)
            for (deck in 0 until decks) {
                // While a still is holding a deck's frame, that frame stays.
                if (freezes.any { it.deck == deck && time >= it.at && time < it.until }) continue
                val coming = (freezes + endings).firstOrNull { it.deck == deck && time < it.at - 0.0001 && time >= it.at - KEEP_WINDOW } ?: continue
                if (keep(deck, inputTexId)) keptFor[deck] = coming.at else gone[deck] = keptFor[deck] == coming.at
            }
            val moment = TransitionScene.moment(clips, time, size, deckOf)
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
            for ((texture, framebuffer) in listOf(kept[0] to keptFramebuffers[0], kept[1] to keptFramebuffers[1], spare to spareFramebuffer)) {
                if (texture != 0) {
                    GlUtil.deleteTexture(texture)
                    GlUtil.deleteFbo(framebuffer)
                }
            }
        }
    }

    private companion object {
        /** How long before a freeze or the end of a chain the frames are kept, in seconds. */
        const val KEEP_WINDOW = 0.2

        /** Where a frame is looked at to tell a picture from the black of an empty sequence, as shares of its size. */
        val SAMPLES = listOf(0.5f to 0.5f, 0.21f to 0.27f, 0.83f to 0.31f, 0.29f to 0.77f, 0.71f to 0.69f)
    }
}
