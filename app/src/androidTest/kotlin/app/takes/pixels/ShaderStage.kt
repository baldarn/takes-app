@file:OptIn(UnstableApi::class)

package app.takes.pixels

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.DefaultGlObjectsProvider
import androidx.media3.effect.GlShaderProgram
import app.takes.TransitionEffect
import app.takes.core.Rect
import app.takes.core.TimelineClip
import app.takes.core.TimelineEditing
import app.takes.core.TransitionLayout
import app.takes.core.TransitionScene
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToLong

/** A graphics context with no window, made current on the thread that makes it: real OpenGL ES, off screen. */
class Bench(val context: Context) {
    private val display: EGLDisplay = GlUtil.getDefaultEglDisplay()
    // OpenGL ES 3 where there is one, then 2, as Media3 makes the context of an export.
    private val egl: EGLContext = try {
        GlUtil.createEglContext(EGL14.EGL_NO_CONTEXT, display, 3, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888)
    } catch (_: GlUtil.GlException) {
        GlUtil.createEglContext(display)
    }
    private val surface: EGLSurface = GlUtil.createFocusedPlaceholderEglSurface(egl, display)
    val objects = DefaultGlObjectsProvider()

    /** What draws: on an emulator a software renderer, on a phone its GPU. */
    val renderer: String = "${GLES20.glGetString(GLES20.GL_RENDERER)} · ${GLES20.glGetString(GLES20.GL_VERSION)} · ${GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION)}"

    /** How many bits a `highp float` of a fragment shader has: the shader asks for it. */
    val fragmentPrecision: Int = IntArray(1).also { bits ->
        GLES20.glGetShaderPrecisionFormat(GLES20.GL_FRAGMENT_SHADER, GLES20.GL_HIGH_FLOAT, IntArray(2), 0, bits, 0)
    }[0]

    fun release() {
        GlUtil.destroyEglSurface(display, surface)
        GlUtil.destroyEglContext(display, egl)
    }
}

/**
 * An export in miniature: the real [TransitionEffect] of [clips], given the
 * frame its decks would hold at a moment and asked for the frame of the movie.
 *
 * The input is laid out as the export lays it out for the effect: one deck,
 * or two one above the other with the first on top, each showing the picture
 * of the clip its sequence plays at that moment and black where the sequence
 * has no clip. Textures are filtered and clamped as Media3 makes its own.
 * [picture] gives the picture of a clip at a second of the movie.
 */
class Stage(
    private val bench: Bench, val clips: List<TimelineClip>, val width: Int, val height: Int,
    framesPerSecond: Double = 30.0, content: ((Int) -> Rect)? = null, titled: Boolean = false,
    /** Whether the decks are an HDR movie's, HLG in BT.2020: the effect compiled for HDR, as [app.takes.ExportMovie] makes it. */
    hdr: Boolean = false,
    /** The project's folder, which holds the pictures laid over clips. */
    folder: java.io.File? = null,
    private val picture: (clip: Int, time: Double) -> Picture,
) {
    val layout: TransitionLayout.Sequence = TimelineEditing.layout(clips)
    private val deckOf = TransitionScene.decks(clips)

    /** One sequence, or two when clips play at once or time lies between them: as the export decides it. */
    val decks: Int = if (TimelineEditing.chains(clips).size > 1) 2 else 1

    private val program: GlShaderProgram = TransitionEffect(clips, Size(width, height), decks, { framesPerSecond }, content ?: { Rect(0.0, 0.0, width.toDouble(), height.toDouble()) }, titled, hdr, folder)
        .toGlShaderProgram(bench.context, /* useHdr = */ false)
    private var output: GlTextureInfo? = null
    private var failure: VideoFrameProcessingException? = null
    private val texture = IntArray(1)
    private val bytes: ByteBuffer = ByteBuffer.allocateDirect(width * height * decks * 4).order(ByteOrder.nativeOrder())
    private val read: ByteBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())

    init {
        program.setOutputListener(object : GlShaderProgram.OutputListener {
            override fun onOutputFrameAvailable(outputTexture: GlTextureInfo, presentationTimeUs: Long) { output = outputTexture }
        })
        program.setErrorListener(Runnable::run) { failure = it }
        GLES20.glGenTextures(1, texture, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
        for (filter in listOf(GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_TEXTURE_MAG_FILTER)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, filter, GLES20.GL_LINEAR)
        for (wrap in listOf(GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_TEXTURE_WRAP_T)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, wrap, GLES20.GL_CLAMP_TO_EDGE)
    }

    /** The clip [deck] is playing at [time], or null where its sequence is empty. */
    private fun playing(deck: Int, time: Double): Int? =
        layout.placements.firstOrNull { deckOf[it.index] == deck && time >= it.start && time < it.end }?.index

    /** The frame of the movie at [time], a second of the timeline. */
    fun frame(time: Double): Picture = frameAt((time * 1e6).roundToLong() - 1)

    /**
     * The frame for a frame of footage stamped [timeUs] microseconds, as a file
     * stamps it: the effect takes it to stand for the moment a microsecond later.
     */
    fun frameAt(timeUs: Long): Picture {
        val time = (timeUs + 1) / 1e6
        val shown = List(decks) { deck -> playing(deck, time)?.let { picture(it, time) } }
        // OpenGL counts rows from the bottom: the last deck's last row comes first.
        bytes.clear()
        for (row in 0 until height * decks) {
            val source = shown[decks - 1 - row / height]
            val y = height - 1 - row % height
            for (x in 0 until width) {
                val color = source?.at(x, y) ?: 0
                bytes.put((color shr 16).toByte()).put((color shr 8).toByte()).put(color.toByte()).put(0xff.toByte())
            }
        }
        bytes.flip()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height * decks, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
        GlUtil.checkGlError()
        output = null
        program.queueInputFrame(bench.objects, GlTextureInfo(texture[0], C.INDEX_UNSET, C.INDEX_UNSET, width, height * decks), timeUs)
        failure?.let { throw it }
        val drawn = checkNotNull(output) { "The effect gave no frame" }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, drawn.fboId)
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        read.clear()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, read)
        GlUtil.checkGlError()
        program.releaseOutputFrame(drawn)
        val pixels = IntArray(width * height)
        for (row in 0 until height) {
            val y = height - 1 - row
            for (x in 0 until width) {
                val at = (row * width + x) * 4
                pixels[y * width + x] = rgb(read.get(at).toInt() and 0xff, read.get(at + 1).toInt() and 0xff, read.get(at + 2).toInt() and 0xff)
            }
        }
        return Picture(width, height, pixels)
    }

    /**
     * How long the effect takes to make the frame at [time], in milliseconds: the median of [times] draws of the
     * same input, each waited for to the end. The pictures are uploaded once, before.
     */
    fun cost(time: Double, times: Int = 9): Double {
        frame(time)
        val timeUs = (time * 1e6).roundToLong() - 1
        val took = List(times) {
            output = null
            GLES20.glFinish()
            val began = System.nanoTime()
            program.queueInputFrame(bench.objects, GlTextureInfo(texture[0], C.INDEX_UNSET, C.INDEX_UNSET, width, height * decks), timeUs)
            GLES20.glFinish()
            val milliseconds = (System.nanoTime() - began) / 1e6
            failure?.let { throw it }
            program.releaseOutputFrame(checkNotNull(output) { "The effect gave no frame" })
            milliseconds
        }.sorted()
        return took[times / 2]
    }

    fun release() {
        program.release()
        GLES20.glDeleteTextures(1, texture, 0)
    }

    companion object {
        /** The microsecond a file stamps a frame at second [time] with: cut, not rounded. */
        fun stamp(time: Double): Long = floor(time * 1e6 + 1e-6).toLong()
    }
}
