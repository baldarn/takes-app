@file:OptIn(UnstableApi::class)

package app.takes.probe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.PassthroughShaderProgram
import androidx.media3.effect.StaticOverlaySettings

/**
 * Lays the two decks one above the other in a frame twice as tall, the first on top, as the app's
 * DecksCompositor does. With [clock] the first input is the still that gives the frame times: it is
 * not drawn, and the decks are the inputs after it.
 */
class StackCompositor(private val canvas: Size, private val clock: Boolean) : VideoCompositorSettings {
    override fun getOutputSize(inputSizes: List<Size>): Size = Size(canvas.width, canvas.height * 2)

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings = object : OverlaySettings {
        private val deck = inputId - if (clock) 1 else 0
        override fun getAlphaScale(): Float = if (deck < 0) 0f else 1f
        override fun getBackgroundFrameAnchor(): android.util.Pair<Float, Float> = android.util.Pair.create(0f, if (deck <= 0) 0.5f else -0.5f)
    }
}

/**
 * Gives a deck's frames times [micros] later than their own. Media3 draws, for each frame of the
 * clock, the frame of a deck whose time is nearest. A file gives its frames times cut down to the
 * microsecond and the clock gives its own rounded, so footage at half the movie's frame rate has
 * two frames equally near every other tick, a microsecond deciding between them, and one tick in
 * six takes the frame that has not begun yet. A few microseconds later, the frame that is showing
 * is always the nearer one; footage at the movie's own rate is not affected.
 */
class LaterEffect(private val micros: Long) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = object : PassthroughShaderProgram() {
        override fun queueInputFrame(glObjectsProvider: GlObjectsProvider, inputTexture: GlTextureInfo, presentationTimeUs: Long) =
            super.queueInputFrame(glObjectsProvider, inputTexture, presentationTimeUs + micros)
    }
}

/**
 * Makes the movie's frame from the stacked decks: the deck that is playing, or, while both play,
 * the left half of the first beside the right half of the second, so the frame numbers of both
 * stay readable where the app would mix them. [from] is where the rendered stretch begins in the
 * movie: frame times arrive counted from the start of the stretch.
 */
class SplitEffect(private val plan: Plan, private val from: Long, private val canvas: Size) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program()

    private inner class Program : BaseGlShaderProgram(/* useHighPrecisionColorComponents = */ false, /* texturePoolCapacity = */ 1) {
        private var program = 0
        private var position = 0
        private val quad = GlUtil.createBuffer(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 0f, 1f, -1f, 1f, 0f, 1f, 1f, 1f, 0f, 1f))
        private var drawn = -1f
        private var telling = 0

        override fun configure(inputWidth: Int, inputHeight: Int): Size = canvas

        private fun compiled(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "Split shader: " + GLES20.glGetShaderInfoLog(shader) }
            return shader
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            if (program == 0) {
                program = GLES20.glCreateProgram()
                GLES20.glAttachShader(program, compiled(GLES20.GL_VERTEX_SHADER, VERTEX))
                GLES20.glAttachShader(program, compiled(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
                GLES20.glLinkProgram(program)
                position = GLES20.glGetAttribLocation(program, "aPosition")
            }
            // Frame times arrive cut to whole microseconds, a hair before the instant they stand for.
            val time = from + presentationTimeUs + 1
            val first = plan.playing(0, time)
            val second = plan.playing(1, time)
            val mode = if (first && second) 2f else if (second) 1f else if (first) 0f else 3f
            // The frames around a change of what is drawn are written to the log, to tell a wrong frame's cause.
            if (mode != drawn) telling = 4
            drawn = mode
            if (telling-- > 0) android.util.Log.i("TakesProbe", "split at $presentationTimeUs + $from: drawing $mode")
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uMode"), mode)
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

    private companion object {
        const val VERTEX = """
            attribute vec4 aPosition;
            varying vec2 vTex;
            void main() { gl_Position = aPosition; vTex = aPosition.xy * 0.5 + 0.5; }
        """

        // The first deck is the upper half of the input, which is the upper half of its texture.
        const val FRAGMENT = """
            precision highp float;
            uniform sampler2D uTex;
            uniform float uMode;
            varying vec2 vTex;
            vec3 deck(float which) { return texture2D(uTex, vec2(vTex.x, (vTex.y + 1.0 - which) / 2.0)).rgb; }
            void main() {
                vec3 color = vec3(0.0);
                if (uMode < 0.5) color = deck(0.0);
                else if (uMode < 1.5) color = deck(1.0);
                else if (uMode < 2.5) color = vTex.x < 0.5 ? deck(0.0) : deck(1.0);
                gl_FragColor = vec4(color, 1.0);
            }
        """
    }
}

/**
 * Stands for the titles and subtitles: an overlay that changes with every frame. It writes the
 * number of the movie's frame, as [framesPerSecond] counts them, along the bottom ninth of the
 * picture: 20 blocks, the first white, the second black, then 18 bits, lowest first. [from] is
 * where the rendered stretch begins in the movie.
 */
class CounterOverlay(private val framesPerSecond: Int, private val from: Long, private val canvas: Size) : BitmapOverlay() {
    private val strip = Bitmap.createBitmap(canvas.width, canvas.height / 9, Bitmap.Config.ARGB_8888)
    private val paint = Paint().apply { color = Color.WHITE }
    private val settings = StaticOverlaySettings.Builder().setOverlayFrameAnchor(0f, -1f).setBackgroundFrameAnchor(0f, -1f).build()

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val frame = Math.round((from + presentationTimeUs) * framesPerSecond / 1e6)
        val block = canvas.width / 20f
        strip.eraseColor(Color.BLACK)
        val drawing = Canvas(strip)
        for (index in 0 until 20) {
            val lit = when (index) {
                0 -> true
                1 -> false
                else -> (frame shr (index - 2)) and 1L == 1L
            }
            if (lit) drawing.drawRect(index * block, 0f, (index + 1) * block, strip.height.toFloat(), paint)
        }
        return strip
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
}
