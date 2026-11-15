@file:OptIn(UnstableApi::class)

package app.takes.probe.layers

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.PassthroughShaderProgram
import java.util.concurrent.atomic.AtomicLong

/**
 * Where the [lanes] lie in the frame the compositor makes: [columns] side by side (1, a column as
 * the app's DecksCompositor lays its two decks; 2, a grid), as many rows as needed, each cell the
 * size of the movie's [canvas]. Lane 0 is the top left cell.
 */
class LaneGrid(val canvas: Size, val lanes: Int, val columns: Int) {
    val rows = (lanes + columns - 1) / columns
    val size = Size(canvas.width * columns, canvas.height * rows)
    fun column(lane: Int) = lane % columns
    fun row(lane: Int) = lane / columns
}

/**
 * The app's DecksCompositor for any number of lanes: every lane untouched in its cell of [grid].
 * The first input is the clock, the still whose frames give the movie its times: it is not drawn,
 * and the lanes are the inputs after it.
 */
class LanesCompositor(private val grid: LaneGrid) : VideoCompositorSettings {
    override fun getOutputSize(inputSizes: List<Size>): Size = grid.size

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings = object : OverlaySettings {
        private val lane = inputId - 1
        override fun getAlphaScale(): Float = if (lane < 0) 0f else 1f
        override fun getBackgroundFrameAnchor(): android.util.Pair<Float, Float> {
            if (lane < 0) return android.util.Pair.create(0f, 0f)
            val x = -1f + (2f * grid.column(lane) + 1f) / grid.columns
            val y = 1f - (2f * grid.row(lane) + 1f) / grid.rows
            return android.util.Pair.create(x, y)
        }
    }
}

/**
 * The window each lane is drawn in, as shares of the movie's frame from its top left corner: lane
 * 0 fills the frame, the others are squares of the frame's proportions in a row along the bottom,
 * a third of the frame each (smaller when there are more than four lanes), drawn over lane 0.
 */
fun windows(lanes: Int): List<FloatArray> {
    val side = 1f / maxOf(3, lanes - 1)
    return List(lanes) { lane -> if (lane == 0) floatArrayOf(0f, 0f, 1f, 1f) else floatArrayOf((lane - 1) * side, 1f - side, side, side) }
}

/**
 * Makes the movie's frame from the composed lanes: each lane in its window ([windows]), back to
 * front, in one pass with a layer loop, as the plan's picture-in-picture shader would.
 */
class LayersEffect(private val grid: LaneGrid) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program()

    private inner class Program : BaseGlShaderProgram(false, 1) {
        private var program = 0
        private var position = 0
        private val quad = GlUtil.createBuffer(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 0f, 1f, -1f, 1f, 0f, 1f, 1f, 1f, 0f, 1f))
        private val placed = windows(grid.lanes).flatMap { it.toList() }.toFloatArray().copyOf(4 * MAX)

        override fun configure(inputWidth: Int, inputHeight: Int): Size = grid.canvas

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            if (program == 0) {
                program = linked(VERTEX, FRAGMENT)
                position = GLES20.glGetAttribLocation(program, "aPosition")
            }
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLanes"), grid.lanes.toFloat())
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uGrid"), grid.columns.toFloat(), grid.rows.toFloat())
            GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "uWindow"), MAX, placed, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 4, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        }

        override fun release() {
            super.release()
            if (program != 0) GLES20.glDeleteProgram(program)
        }
    }

    companion object {
        const val MAX = 8

        const val VERTEX = """
            attribute vec4 aPosition;
            varying vec2 vTex;
            void main() { gl_Position = aPosition; vTex = aPosition.xy * 0.5 + 0.5; }
        """

        // Textures have their top at 1; windows are counted from the top.
        const val FRAGMENT = """
            precision highp float;
            uniform sampler2D uTex;
            uniform float uLanes;
            uniform vec2 uGrid;
            uniform vec4 uWindow[8];
            varying vec2 vTex;
            void main() {
                vec2 p = vec2(vTex.x, 1.0 - vTex.y);
                vec3 color = vec3(0.0);
                for (int i = 0; i < 8; i++) {
                    if (float(i) >= uLanes) break;
                    vec4 w = uWindow[i];
                    vec2 q = (p - w.xy) / w.zw;
                    if (q.x >= 0.0 && q.x <= 1.0 && q.y >= 0.0 && q.y <= 1.0) {
                        float column = mod(float(i), uGrid.x);
                        float row = floor(float(i) / uGrid.x);
                        color = texture2D(uTex, vec2((column + q.x) / uGrid.x, 1.0 - (row + q.y) / uGrid.y)).rgb;
                    }
                }
                gl_FragColor = vec4(color, 1.0);
            }
        """
    }
}

/** What a lane showed in one movie frame, read from its row of blocks. */
sealed interface Seen {
    data object Black : Seen
    data object Unreadable : Seen
    data class Frame(val number: Int) : Seen
}

/**
 * Counts the movie's frames into [frames] and, with [lanesToRead] > 0, reads back from every
 * frame the row of blocks of each lane's picture in its window ([windows]) and hands what it
 * read to [seen] with the frame's time. [content] is where a lane's footage lies in its own frame
 * (x, y, width, height as shares), the rest being the bars of fitting.
 */
class CheckEffect(
    private val canvas: Size, private val lanesToRead: Int, private val content: FloatArray,
    private val frames: AtomicLong, private val seen: (Long, List<Seen>) -> Unit,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = object : PassthroughShaderProgram() {
        private val framebuffers = HashMap<Int, Int>()
        private val placed = windows(maxOf(1, lanesToRead))
        private val row = java.nio.ByteBuffer.allocateDirect(canvas.width * 4)

        override fun queueInputFrame(glObjectsProvider: GlObjectsProvider, inputTexture: GlTextureInfo, presentationTimeUs: Long) {
            frames.incrementAndGet()
            if (lanesToRead > 0) {
                val framebuffer = framebuffers.getOrPut(inputTexture.texId) { GlUtil.createFboForTexture(inputTexture.texId) }
                val bound = IntArray(1)
                GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, bound, 0)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
                val read = HashMap<Int, ByteArray>()
                val result = placed.map { window ->
                    val top = ((window[1] + (content[1] + content[3] / 24f) * window[3]) * canvas.height).toInt().coerceIn(0, canvas.height - 1)
                    val pixels = read.getOrPut(top) {
                        GLES20.glReadPixels(0, canvas.height - 1 - top, canvas.width, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, row.rewind())
                        ByteArray(canvas.width * 4).also { row.rewind(); row.get(it) }
                    }
                    val lit = BooleanArray(20) { block ->
                        val x = ((window[0] + (content[0] + content[2] * (block + 0.5f) / 20f) * window[2]) * canvas.width).toInt().coerceIn(0, canvas.width - 1)
                        val g = pixels[x * 4 + 1].toInt() and 0xff
                        g > 128
                    }
                    when {
                        lit.none { it } -> Seen.Black
                        !lit[0] || lit[1] || lit[2] || lit[3] -> Seen.Unreadable
                        else -> Seen.Frame((0 until 16).sumOf { if (lit[4 + it]) 1 shl it else 0 })
                    }
                }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bound[0])
                seen(presentationTimeUs, result)
            }
            super.queueInputFrame(glObjectsProvider, inputTexture, presentationTimeUs)
        }

        override fun release() {
            super.release()
            for (framebuffer in framebuffers.values) GlUtil.deleteFbo(framebuffer)
        }
    }
}

internal fun linked(vertex: String, fragment: String): Int {
    fun compiled(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Layers shader: " + GLES20.glGetShaderInfoLog(shader) }
        return shader
    }
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, compiled(GLES20.GL_VERTEX_SHADER, vertex))
    GLES20.glAttachShader(program, compiled(GLES20.GL_FRAGMENT_SHADER, fragment))
    GLES20.glLinkProgram(program)
    val status = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
    check(status[0] == GLES20.GL_TRUE) { "Layers shader: " + GLES20.glGetProgramInfoLog(program) }
    return program
}

/**
 * The longest side of a texture the GPU takes, and its name, asked on a graphics context of its
 * own: the app's `largestTextureSide()` (TransitionRendering.kt), with the renderer's name added.
 */
fun graphics(): String = try {
    val display = GlUtil.getDefaultEglDisplay()
    val context = GlUtil.createEglContext(display)
    try {
        val surface = GlUtil.createFocusedPlaceholderEglSurface(context, display)
        try {
            val side = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, side, 0)
            val viewport = IntArray(2)
            GLES20.glGetIntegerv(GLES20.GL_MAX_VIEWPORT_DIMS, viewport, 0)
            val renderbuffer = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_RENDERBUFFER_SIZE, renderbuffer, 0)
            "GL_MAX_TEXTURE_SIZE=${side[0]} GL_MAX_VIEWPORT_DIMS=${viewport[0]}x${viewport[1]} GL_MAX_RENDERBUFFER_SIZE=${renderbuffer[0]} " +
                "renderer=${GLES20.glGetString(GLES20.GL_RENDERER)} version=${GLES20.glGetString(GLES20.GL_VERSION)}"
        } finally {
            GlUtil.destroyEglSurface(display, surface)
        }
    } finally {
        GlUtil.destroyEglContext(display, context)
    }
} catch (problem: Exception) {
    "graphics not readable: $problem"
}
