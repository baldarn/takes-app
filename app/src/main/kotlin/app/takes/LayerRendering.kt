@file:OptIn(UnstableApi::class)

package app.takes

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.opengl.GLES20
import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import app.takes.core.EditRender
import app.takes.core.LayerPlan
import app.takes.core.PlacedPicture
import app.takes.core.Rect
import app.takes.core.Size as FrameSize
import app.takes.core.TimelineTransition
import app.takes.core.TitleMotionRanges
import app.takes.core.TransitionGeometry
import app.takes.core.TransitionScene

/** How the app draws an edit ([EditRender]): the pictures it decodes at once, and what it says when an edit needs more. */
object EditRendering {
    /**
     * The most pictures the preview and the export decode at once: two in a dissolve and one over them. Measured
     * on a Galaxy A54 (docs/LAYERS-PROBE.md, section 5); the export does four, the preview's memory is the limit.
     */
    const val LANES = 3

    /** What the editor and the export say of an edit that has more pictures at once than [LANES]. */
    fun tooMany(context: Context, refused: EditRender.TooManyLayers): String = refused.refused.let {
        context.getString(R.string.layers_too_many, timeLabel(it.at.start.seconds), timeLabel(it.at.end.seconds), it.pictures.size, it.limit)
    }
}

/**
 * One frame of an edit drawn by picture layers as numbers for the layer
 * shader: what [LayerPlan.moment] says the frame holds, back to front, over
 * black. A pair in transition is drawn first, over the whole canvas, by the
 * transition shader's own `scene()` ([scene]); it covers everything behind it,
 * so it is only ever the back layer. Every other layer is a picture drawn
 * whole and then moved into its window: [count] of them, each as a clip
 * rectangle (nothing is drawn outside it), the middle of its window, the
 * middle of the part of the picture shown, the canvas pixels per picture
 * pixel and where the renderer has the picture ([kind]).
 *
 * The preview and the export run the same shader text on the same numbers,
 * as they do for a transition ([SceneDrawing]), so a frame looks the same in
 * both. Geometry is in pixels from the top left.
 */
class LayerDrawing(val scene: SceneDrawing, val mixed: Boolean, val count: Int, val values: FloatArray) {
    companion object {
        /** The most layers in one frame, and the most lanes: [EditRendering.LANES]. A pair in transition takes two lanes and is one layer. */
        const val MOST = EditRendering.LANES

        /** Numbers each layer takes: clip rectangle, the two middles, then its kind and scale (and two unused). */
        const val STRIDE = 12

        /** Where a layer's picture comes from, as the layer shader's kind: a lane (0 and up), the fill color, or the frame kept of a lane. */
        const val FILL = -1f
        const val HELD = 10f

        /** The kind of the pair in transition blurred on a reduced picture and enlarged ([ReducedBlur]), in the export. */
        const val BLURRED = HELD + MOST

        private val none = SceneDrawing(SceneDrawing.SINGLE, FILL, FILL, FloatArray(3), FloatArray(24))

        /**
         * The drawing of [moment] on a canvas of [width] by [height] pixels,
         * the size it was asked for. [kind] says where the renderer has each
         * picture; [content] is where a picture's own footage lies in the
         * frame, without the bars around it, for a split screen.
         */
        fun of(moment: LayerPlan.Moment, width: Float, height: Float, kind: (TransitionScene.Source) -> Float, content: (Int) -> Rect): LayerDrawing {
            val layers = moment.layers
            val back = layers.indexOfLast { it is LayerPlan.Layer.Mixed }
            val pair = layers.getOrNull(back) as? LayerPlan.Layer.Mixed
            val scene = pair?.let {
                SceneDrawing.of(TransitionScene.Moment(it.first, it.second, it.frame, it.firstShare, it.secondShare), width, height, kind, content)
            } ?: none
            val values = FloatArray(MOST * STRIDE)
            var count = 0
            for (layer in layers.drop(back + 1)) {
                if (layer !is LayerPlan.Layer.Whole || count == MOST) continue
                val at = count * STRIDE
                val placed = layer.window
                if (placed == null) {
                    floatArrayOf(0f, 0f, width, height, width / 2, height / 2, width / 2, height / 2, kind(layer.source), 1f).copyInto(values, at)
                } else {
                    val window = placed.window
                    val shown = placed.source
                    val clip = shown(placed)
                    floatArrayOf(
                        clip.x.toFloat(), clip.y.toFloat(), clip.width.toFloat(), clip.height.toFloat(),
                        window.midX.toFloat(), window.midY.toFloat(), shown.midX.toFloat(), shown.midY.toFloat(),
                        kind(layer.source), placed.scale.toFloat(),
                    ).copyInto(values, at)
                }
                count++
            }
            return LayerDrawing(scene, pair != null, count, values)
        }

        /** [scene] alone over the whole canvas, with no titles: a pass of a blur, or a spread in the preview. */
        fun scene(scene: SceneDrawing): LayerDrawing = LayerDrawing(scene, true, 0, FloatArray(MOST * STRIDE))

        /** [lane] alone over the whole canvas: what an export keeps a frame of. */
        fun lane(lane: Int, width: Float, height: Float): LayerDrawing =
            LayerDrawing(none, false, 1, FloatArray(MOST * STRIDE).also { floatArrayOf(0f, 0f, width, height, width / 2, height / 2, width / 2, height / 2, lane.toFloat(), 1f).copyInto(it) })

        /** Where the canvas shows a placed picture: its framed part, scaled about the window's middle, inside the visible window. */
        fun shown(placed: PlacedPicture): Rect {
            val window = placed.window
            val source = placed.source
            return intersection(placed.clip, Rect(window.midX - source.width * placed.scale / 2, window.midY - source.height * placed.scale / 2, source.width * placed.scale, source.height * placed.scale))
        }

        private fun intersection(a: Rect, b: Rect): Rect {
            val x = maxOf(a.minX, b.minX)
            val y = maxOf(a.minY, b.minY)
            val width = minOf(a.maxX, b.maxX) - x
            val height = minOf(a.maxY, b.maxY) - y
            return if (width > 0 && height > 0) Rect(x, y, width, height) else Rect.ZERO
        }
    }
}

/** The shader of picture layers, written once and worded for each of the two renderers, around the transition shader's. */
object LayerShader {
    // A lane, the fill, or a kept frame: kinds that leave room for lanes past the second.
    private const val SIDE = """
        vec3 side(float kind, vec2 p) {
            if (kind > 9.5) return held(kind - 10.0, clamp(p, vec2(0.5), uSize - 0.5));
            if (kind < -0.5) return uFill;
            return deck(kind, clamp(p, vec2(0.5), uSize - 0.5));
        }
    """

    private val uniforms = TransitionShader.UNIFORMS + """
        uniform float uMixed;
        uniform float uCount;
        uniform vec3 uTitled;
    """ + (0 until LayerDrawing.MOST).joinToString("") { "\n        uniform vec4 uClip$it;\n        uniform vec4 uMap$it;\n        uniform vec2 uLayer$it;" }

    // Back to front over black: the pair in transition over the whole frame, then each picture inside its clip rectangle,
    // the canvas point taken back to the picture's own pixel through its window. The titles of a layer, where the renderer
    // draws them here ([title], by the layer's place back to front), go over its picture before it is moved: they shrink with it.
    private val body = SIDE + TransitionShader.TAIL + """
        vec3 over(vec3 under, vec4 top) { return top.rgb + (1.0 - top.a) * under; }
        vec3 placed(vec2 q, vec3 under, vec4 clip, vec4 map, vec2 layer, float slot) {
            if (!within(q, clip)) return under;
            vec2 p = (q - map.xy) / layer.y + map.zw;
            return over(side(layer.x, p), title(slot, clamp(p, vec2(0.5), uSize - 0.5)));
        }
        vec3 layers(vec2 q) {
            vec3 c = vec3(0.0);
            if (uMixed > 0.5) c = over(scene(q), title(0.0, q));
    """ + (0 until LayerDrawing.MOST).joinToString("") { "\n            if (uCount > $it.5) c = placed(q, c, uClip$it, uMap$it, uLayer$it, $it.0 + uMixed);" } + """
            return c;
        }
    """

    /** For the export: the input holds the lanes in a column or a grid of [uCells] (columns, rows), the first at the top left. */
    val FRAGMENT = """
        precision highp float;
        uniform sampler2D uTex;
        uniform sampler2D uHeld0;
        uniform sampler2D uHeld1;
        uniform sampler2D uHeld2;
        uniform sampler2D uHeld3;
        uniform sampler2D uTitle0;
        uniform sampler2D uTitle1;
        uniform sampler2D uTitle2;
        uniform vec2 uCells;
        uniform vec3 uLinear;
        varying vec2 vTex;
        $uniforms
        ${TransitionShader.HEAD}
        vec3 deck(float kind, vec2 p) {
            float row = floor((kind + 0.5) / uCells.x);
            float column = kind - row * uCells.x;
            return texture2D(uTex, vec2((p.x / uSize.x + column) / uCells.x, 1.0 - (p.y / uSize.y + row) / uCells.y)).rgb;
        }
        // A kept frame of a lane, or with uLinear.x the reduced pictures of a blur in linear light, read filtered as
        // such; the fourth, the pair in transition blurred on its reduced picture, in linear light with uLinear.z.
        vec3 held(float which, vec2 p) {
            vec2 at = vec2(p.x / uSize.x, 1.0 - p.y / uSize.y);
            if (which > 2.5) {
                vec3 c = texture2D(uHeld3, at).rgb;
                return uLinear.z > 0.5 ? enc(c) : c;
            }
            vec3 c = which > 1.5 ? texture2D(uHeld2, at).rgb : which > 0.5 ? texture2D(uHeld1, at).rgb : texture2D(uHeld0, at).rgb;
            return uLinear.x > 0.5 ? enc(c) : c;
        }
        // A bitmap of titles, its first row at the top, with its colors already multiplied by their opacity.
        vec4 title(float slot, vec2 p) {
            vec2 at = p / uSize;
            if (slot > 1.5) return uTitled.z > 0.5 ? texture2D(uTitle2, at) : vec4(0.0);
            if (slot > 0.5) return uTitled.y > 0.5 ? texture2D(uTitle1, at) : vec4(0.0);
            return uTitled.x > 0.5 ? texture2D(uTitle0, at) : vec4(0.0);
        }
        $body
        // With uLinear.y the pixel is written in linear light, for a reduced picture of a blur.
        void main() {
            vec3 c = layers(vec2(vTex.x * uSize.x, (1.0 - vTex.y) * uSize.y));
            gl_FragColor = vec4(uLinear.y > 0.5 ? lin(c) : c, 1.0);
        }
    """

    /** For the preview: the layer holds the lane views one above the other; the frame is drawn over the first. */
    val PREVIEW = """
        uniform shader content;
        $uniforms
        vec3 deck(float kind, vec2 p) { return content.eval(vec2(p.x, p.y + kind * uSize.y)).rgb; }
        vec3 held(float which, vec2 p) { return deck(which, p); }
        // The preview draws a picture's titles on its lane's view.
        vec4 title(float slot, vec2 p) { return vec4(0.0); }
        ${TransitionShader.HEAD}
        $body
        vec4 main(vec2 xy) {
            // A spread is made of the lanes of the pair in transition, each in its place, for the frame drawn after
            // it; the other lanes pass as they are.
            if (uMode > 8.5) {
                float lane = floor(xy.y / uSize.y);
                vec2 p = xy - vec2(0.0, lane * uSize.y);
                if (abs(lane - uKind.x) < 0.5 || abs(lane - uKind.y) < 0.5) return vec4(spread(lane, p), 1.0);
                return vec4(deck(lane, p), 1.0);
            }
            if (xy.y >= uSize.y) return vec4(0.0);
            return vec4(layers(xy), 1.0);
        }
    """
}

/**
 * The preview's side of the layer shader, on Android 13 and later. As
 * [TransitionPreviewShader] does for a sequence, it draws on the view, about
 * the size the original reduces a picture to before a blur, and spreads the
 * lanes of a pair in a defocus first, across and then down.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class LayerPreviewShader {
    private val shader = RuntimeShader(LayerShader.PREVIEW)
    private val across = RuntimeShader(LayerShader.PREVIEW)
    private val down = RuntimeShader(LayerShader.PREVIEW)

    private fun RuntimeShader.set(drawing: LayerDrawing, width: Float, height: Float): RenderEffect {
        val scene = drawing.scene
        setFloatUniform("uSize", width, height)
        setFloatUniform("uMode", scene.mode.toFloat())
        setFloatUniform("uKind", scene.kindA, scene.kindB)
        setFloatUniform("uFill", scene.fill)
        TransitionShader.names.forEachIndexed { row, name -> setFloatUniform(name, scene.values.copyOfRange(row * 4, row * 4 + 4)) }
        setFloatUniform("uMixed", if (drawing.mixed) 1f else 0f)
        setFloatUniform("uCount", drawing.count.toFloat())
        setFloatUniform("uTitled", 0f, 0f, 0f)
        for (layer in 0 until LayerDrawing.MOST) {
            val at = layer * LayerDrawing.STRIDE
            setFloatUniform("uClip$layer", drawing.values.copyOfRange(at, at + 4))
            setFloatUniform("uMap$layer", drawing.values.copyOfRange(at + 4, at + 8))
            setFloatUniform("uLayer$layer", drawing.values.copyOfRange(at + 8, at + 10))
        }
        return RenderEffect.createRuntimeShaderEffect(this, "content")
    }

    /** The effect for a layer [width] pixels wide holding the lanes, each [height] pixels tall. */
    fun effect(drawing: LayerDrawing, width: Float, height: Float): RenderEffect {
        val frame = shader.set(drawing, width, height)
        val scene = drawing.scene
        if (!drawing.mixed || scene.mode != SceneDrawing.DEFOCUS || !scene.blurs) return frame
        // The shader takes half the samples of a disc on a picture longer than 1920 pixels.
        val samples = if (maxOf(width, height) > 1920f) TransitionGeometry.DEFOCUS_SAMPLES / 2 else TransitionGeometry.DEFOCUS_SAMPLES
        val sigma = TransitionGeometry.defocusSpread(scene.blurRadius.toDouble(), samples).toFloat()
        fun spread(dx: Float, dy: Float) = LayerDrawing.scene(
            SceneDrawing(SceneDrawing.SPREAD, scene.kindA, scene.kindB, scene.fill, FloatArray(24).also { it[0] = sigma; it[1] = dx; it[2] = dy }),
        )
        val spread = RenderEffect.createChainEffect(down.set(spread(0f, 1f), width, height), across.set(spread(1f, 0f), width, height))
        return RenderEffect.createChainEffect(frame, spread)
    }
}

/**
 * Draws every frame of an export of an edit drawn by picture layers as its
 * [plan] describes it ([LayerPlan.moment]): over black, back to front, a pair
 * in transition mixed once, and every other picture drawn whole and moved
 * into its window. The input is the frame [DecksCompositor] makes of the
 * [lanes], laid in [columns]. [frames] is the frame rate of the movie, which a
 * flash cut counts in, and [content] where a picture's footage lies in the
 * frame.
 *
 * As [TransitionEffect] does for its two decks, it keeps the frame a lane
 * showed before a still holds it, or before the lane's run of pictures ends,
 * for a transition still showing it; and it works out a whip pan or a defocus
 * of the pair on a reduced picture ([ReducedBlur]), enlarged as the back layer.
 *
 * With [titled], the titles of each layer are drawn here, over its picture
 * before it is moved into its window, by [TitlePainter] as the preview draws
 * them. They are not drawn on the lanes themselves: an effect after a lane's
 * `Presentation` made Media3 give one lane the frames of another
 * (docs/EXPORT-TWO-SEQUENCES.md, section 8; seen here with three lanes).
 */
class LayersEffect(
    private val plan: LayerPlan, private val canvas: Size, private val lanes: Int, private val columns: Int,
    private val frames: (TimelineTransition) -> Double, private val content: (Int) -> Rect, private val titled: Boolean = false,
) : GlEffect {
    init {
        require(lanes in 1..LayerDrawing.MOST) { "At most ${LayerDrawing.MOST} lanes" }
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(context.assets)

    /** A moment a frame of [lane] is kept for, as in [TransitionEffect]: the start of a freeze or the end of a run, at [at]. */
    private class Keep(val at: Double, val lane: Int, val until: Double, val last: Double)

    private val kept = IntArray(LayerDrawing.MOST)
    private val keptFramebuffers = IntArray(LayerDrawing.MOST)
    private val keptFor = DoubleArray(LayerDrawing.MOST) { Double.NaN }
    private var spare = 0
    private var spareFramebuffer = 0

    private inner class Program(assets: android.content.res.AssetManager) : BaseGlShaderProgram(/* useHighPrecisionColorComponents = */ false, /* texturePoolCapacity = */ 1) {
        private val titles = if (titled) LayerTitles(plan, canvas.width, canvas.height, assets) else null
        private val size = FrameSize(canvas.width.toDouble(), canvas.height.toDouble())
        private val rows = (lanes + columns - 1) / columns
        private val freezes = plan.freezes.map { Keep(it.from, it.lane, it.until, it.shown + EDGE) }
        private val endings = plan.endings.map { Keep(it.at, it.deck, it.at, it.at - EDGE) }
        private var program = 0
        private var position = 0
        private var drawnAt = Double.NaN
        private val gone = BooleanArray(LayerDrawing.MOST)
        private val pixel = java.nio.ByteBuffer.allocateDirect(4)
        private val quad = GlUtil.createBuffer(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 0f, 1f, -1f, 1f, 0f, 1f, 1f, 1f, 0f, 1f))

        override fun configure(inputWidth: Int, inputHeight: Int): Size = canvas

        private fun compiled(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "Layer shader: " + GLES20.glGetShaderInfoLog(shader) }
            return shader
        }

        private fun prepare() {
            if (program != 0) return
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, compiled(GLES20.GL_VERTEX_SHADER, TransitionShader.VERTEX))
            GLES20.glAttachShader(program, compiled(GLES20.GL_FRAGMENT_SHADER, LayerShader.FRAGMENT))
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "Layer shader: " + GLES20.glGetProgramInfoLog(program) }
            position = GLES20.glGetAttribLocation(program, "aPosition")
        }

        private fun uniform(name: String) = GLES20.glGetUniformLocation(program, name)

        /**
         * Draws [drawing] on a picture [width] by [height] pixels, into the framebuffer in focus; a lane kept alone
         * or a pass of a blur ([withTitles] false) carries no titles. The kept frames are [held] (the reduced
         * pictures, in the passes of a blur), read as linear light with [linearIn]; the pixels are written in linear
         * light with [linearOut]. [blurred] is the pair blurred on its reduced picture, in linear light with [blurredLinear].
         */
        private fun draw(
            drawing: LayerDrawing, inputTexId: Int, withTitles: Boolean = true, held: IntArray = kept, width: Int = canvas.width, height: Int = canvas.height,
            linearIn: Boolean = false, linearOut: Boolean = false, blurred: Int = 0, blurredLinear: Boolean = false,
        ) {
            GLES20.glUseProgram(program)
            // A kept frame that does not exist yet is never read; the input stands in so the sampler has a texture.
            val textures = listOf(inputTexId) + held.toList() + (titles?.textures ?: IntArray(LayerDrawing.MOST)).toList() + blurred
            for ((unit, texture) in textures.withIndex()) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (texture != 0) texture else inputTexId)
            }
            GLES20.glUniform1i(uniform("uTex"), 0)
            for (lane in 0 until LayerDrawing.MOST) GLES20.glUniform1i(uniform("uHeld$lane"), lane + 1)
            for (slot in 0 until LayerDrawing.MOST) GLES20.glUniform1i(uniform("uTitle$slot"), LayerDrawing.MOST + slot + 1)
            GLES20.glUniform1i(uniform("uHeld${LayerDrawing.MOST}"), 2 * LayerDrawing.MOST + 1)
            val titledSlots = titles?.drawn.takeIf { withTitles } ?: BooleanArray(LayerDrawing.MOST)
            GLES20.glUniform3f(uniform("uTitled"), if (titledSlots[0]) 1f else 0f, if (titledSlots[1]) 1f else 0f, if (titledSlots[2]) 1f else 0f)
            GLES20.glUniform3f(uniform("uLinear"), if (linearIn) 1f else 0f, if (linearOut) 1f else 0f, if (blurredLinear) 1f else 0f)
            GLES20.glUniform2f(uniform("uCells"), columns.toFloat(), rows.toFloat())
            GLES20.glUniform2f(uniform("uSize"), width.toFloat(), height.toFloat())
            val scene = drawing.scene
            GLES20.glUniform1f(uniform("uMode"), scene.mode.toFloat())
            GLES20.glUniform2f(uniform("uKind"), scene.kindA, scene.kindB)
            GLES20.glUniform3fv(uniform("uFill"), 1, scene.fill, 0)
            TransitionShader.names.forEachIndexed { row, name -> GLES20.glUniform4fv(uniform(name), 1, scene.values, row * 4) }
            GLES20.glUniform1f(uniform("uMixed"), if (drawing.mixed) 1f else 0f)
            GLES20.glUniform1f(uniform("uCount"), drawing.count.toFloat())
            for (layer in 0 until LayerDrawing.MOST) {
                val at = layer * LayerDrawing.STRIDE
                GLES20.glUniform4fv(uniform("uClip$layer"), 1, drawing.values, at)
                GLES20.glUniform4fv(uniform("uMap$layer"), 1, drawing.values, at + 4)
                GLES20.glUniform2fv(uniform("uLayer$layer"), 1, drawing.values, at + 8)
            }
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

        private val blur = ReducedBlur(LayerDrawing.FILL, LayerDrawing.HELD)

        /**
         * [drawing] with its pair in a whip pan or a defocus worked out on the reduced picture ([ReducedBlur]), as the
         * export of a sequence does, then the frame drawn with that picture enlarged, filtered, in the pair's place.
         */
        private fun drawBlurred(drawing: LayerDrawing, inputTexId: Int) {
            val linear = blur.linear
            val blurred = blur.draw(
                drawing.scene, canvas.width, canvas.height,
                reduce = { draw(LayerDrawing.scene(it), inputTexId, withTitles = false, linearOut = linear) },
                pass = { scene, held, width, height ->
                    draw(LayerDrawing.scene(scene), inputTexId, withTitles = false, held = intArrayOf(held[0], held[1], 0), width = width, height = height, linearIn = linear, linearOut = linear)
                },
            )
            val pair = SceneDrawing(SceneDrawing.SINGLE, LayerDrawing.BLURRED, LayerDrawing.BLURRED, FloatArray(3), FloatArray(24))
            draw(LayerDrawing(pair, drawing.mixed, drawing.count, drawing.values), inputTexId, blurred = blurred, blurredLinear = linear)
        }

        /** Keeps the picture [lane] is showing now, unless it is the black of a lane with nothing on it; as [TransitionEffect] keeps a deck's. */
        private fun keep(lane: Int, inputTexId: Int, replace: Boolean = true): Boolean {
            val bound = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, bound, 0)
            if (spare == 0) target().let { spare = it.first; spareFramebuffer = it.second }
            GlUtil.focusFramebufferUsingCurrentContext(spareFramebuffer, canvas.width, canvas.height)
            draw(LayerDrawing.lane(lane, canvas.width.toFloat(), canvas.height.toFloat()), inputTexId, withTitles = false)
            val empty = SAMPLES.all { (x, y) ->
                GLES20.glReadPixels((x * (canvas.width - 1)).toInt(), (y * (canvas.height - 1)).toInt(), 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixel.rewind())
                pixel.get(0).toInt() == 0 && pixel.get(1).toInt() == 0 && pixel.get(2).toInt() == 0
            }
            if (!empty && replace) {
                val (texture, framebuffer) = kept[lane] to keptFramebuffers[lane]
                kept[lane] = spare
                keptFramebuffers[lane] = spareFramebuffer
                spare = texture
                spareFramebuffer = framebuffer
            }
            GlUtil.focusFramebufferUsingCurrentContext(bound[0], canvas.width, canvas.height)
            return !empty
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            prepare()
            val time = (presentationTimeUs + 1) / 1e6
            val window = maxOf(KEEP_WINDOW, if (time > drawnAt) KEEP_FRAMES * (time - drawnAt) else 0.0)
            drawnAt = time
            gone.fill(false)
            for (lane in 0 until lanes) {
                val holding = freezes.firstOrNull { it.lane == lane && time > it.last && time < it.until }
                if (holding != null) {
                    if (time < holding.at && keptFor[lane] == holding.at) gone[lane] = !keep(lane, inputTexId, replace = false)
                    continue
                }
                val coming = (freezes + endings).firstOrNull { it.lane == lane && time <= it.last && time >= it.last - window } ?: continue
                if (keep(lane, inputTexId)) keptFor[lane] = coming.at else gone[lane] = keptFor[lane] == coming.at
            }
            val moment = plan.moment(time, size, frames)
            val drawing = LayerDrawing.of(moment, canvas.width.toFloat(), canvas.height.toFloat(), { source ->
                when (source) {
                    is TransitionScene.Source.Clip -> if (gone[source.deck]) LayerDrawing.HELD + source.deck else source.deck.toFloat()
                    is TransitionScene.Source.Held -> if (keptFor[source.deck] == source.from) LayerDrawing.HELD + source.deck else source.deck.toFloat()
                    is TransitionScene.Source.Fill -> LayerDrawing.FILL
                }
            }, content)
            titles?.prepare(moment)
            if (drawing.mixed && drawing.scene.blurs) drawBlurred(drawing, inputTexId) else draw(drawing, inputTexId)
        }

        override fun release() {
            super.release()
            blur.release()
            if (program != 0) GLES20.glDeleteProgram(program)
            titles?.release()
        }
    }

    private companion object {
        const val EDGE = 0.0001
        const val KEEP_WINDOW = 0.2
        const val KEEP_FRAMES = 2.5
        val SAMPLES = listOf(0.5f to 0.5f, 0.21f to 0.27f, 0.83f to 0.31f, 0.29f to 0.77f, 0.71f to 0.69f)
    }
}

/**
 * The titles of each layer of an export's frame, as a bitmap a layer drawn by
 * [TitlePainter] and given to the layer shader as a texture: the titles a
 * picture shows at the second of its footage it shows, as a clip by itself
 * ([TitleMotionRanges]), each as opaque as its picture's share of a pair in
 * transition. A layer's bitmap is drawn again only when what it shows changes.
 */
private class LayerTitles(private val plan: LayerPlan, private val width: Int, private val height: Int, assets: android.content.res.AssetManager) {
    private val painter = TitlePainter(assets)
    private val ranges = HashMap<Int, TitleMotionRanges>()
    private val bitmaps = arrayOfNulls<android.graphics.Bitmap>(LayerDrawing.MOST)
    private val shown = Array(LayerDrawing.MOST) { emptyList<TitleMotionRanges.Shown>() }

    /** The texture of each layer's titles, 0 before it is first drawn. */
    val textures = IntArray(LayerDrawing.MOST)

    /** Whether each layer, back to front, has titles in this frame. */
    val drawn = BooleanArray(LayerDrawing.MOST)

    private fun of(source: TransitionScene.Source, share: Double): List<TitleMotionRanges.Shown> {
        if (share <= 0) return emptyList()
        val (index, offset) = when (source) {
            is TransitionScene.Source.Clip -> source.index to source.offset
            is TransitionScene.Source.Held -> source.index to source.offset
            is TransitionScene.Source.Fill -> return emptyList()
        }
        val clip = plan.pictures[index].content
        if (clip.texts.isNullOrEmpty()) return emptyList()
        val alone = listOf(clip)
        // Opacity in steps, so a steady title is drawn once, as the export draws the titles of a sequence.
        return ranges.getOrPut(index) { TitleMotionRanges(alone) }.shown(alone, offset)
            .map { it.copy(share = (it.share * share * 64).toInt() / 64.0) }.filter { it.share > 0 }
    }

    /** Draws the titles of [moment]'s layers, in the order [LayerDrawing.of] places them. */
    fun prepare(moment: LayerPlan.Moment) {
        val layers = moment.layers
        val back = layers.indexOfLast { it is LayerPlan.Layer.Mixed }
        val wanted = ArrayList<List<TitleMotionRanges.Shown>>()
        (layers.getOrNull(back) as? LayerPlan.Layer.Mixed)?.let { pair ->
            wanted += of(pair.first, pair.firstShare) + if (pair.second == pair.first) emptyList() else of(pair.second, pair.secondShare)
        }
        for (layer in layers.drop(back + 1)) if (layer is LayerPlan.Layer.Whole && wanted.size < LayerDrawing.MOST) wanted += of(layer.source, 1.0)
        for (slot in 0 until LayerDrawing.MOST) {
            val titles = wanted.getOrNull(slot).orEmpty()
            drawn[slot] = titles.isNotEmpty()
            if (titles.isEmpty() || titles == shown[slot]) continue
            shown[slot] = titles
            val bitmap = bitmaps[slot] ?: android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888).also { bitmaps[slot] = it }
            bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
            painter.draw(android.graphics.Canvas(bitmap), width.toFloat(), height.toFloat(), titles)
            if (textures[slot] == 0) {
                val made = IntArray(1)
                GLES20.glGenTextures(1, made, 0)
                textures[slot] = made[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, made[0])
                for (filter in listOf(GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_TEXTURE_MAG_FILTER)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, filter, GLES20.GL_LINEAR)
                for (wrap in listOf(GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_TEXTURE_WRAP_T)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, wrap, GLES20.GL_CLAMP_TO_EDGE)
                android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            } else {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[slot])
                android.opengl.GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
            }
            GlUtil.checkGlError()
        }
    }

    fun release() {
        textures.filter { it != 0 }.forEach { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }
        textures.fill(0)
        bitmaps.forEach { it?.recycle() }
    }
}
