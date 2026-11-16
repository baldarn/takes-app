@file:OptIn(UnstableApi::class)

package app.takes

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.PassthroughShaderProgram
import androidx.media3.transformer.Codec
import java.util.concurrent.CountDownLatch

/**
 * The input of an HDR movie's encoder, in ten bits a color. An export draws its movie as Media3 draws an SDR one, on
 * eight bits, because Media3 cannot lay HDR sequences over one another (its compositor refuses HDR); its pixels are
 * then the HLG signal itself ([HdrMovie]). Given those eight bits, the encoder writes eight-bit H.265 whatever
 * profile it was asked for. Here each frame is drawn again, as it is, onto the encoder's own surface in ten bits a
 * color, as Media3 draws an HDR movie, so the movie is ten-bit HLG.
 *
 * The frames are passed on in order, on a thread of their own, each with its time; the end of the stream is passed
 * on after the last of them. Media3 draws into a queue that keeps only the newest frame not yet taken, so it is held
 * back ([TenBitGate]) until each frame before is passed on: a frame let through sooner would be lost.
 */
class TenBitEncoderInput(private val codec: Codec) : Codec by codec {
    private val thread = HandlerThread("hdr-encoder-input").apply { start() }
    private val handler = Handler(thread.looper)
    private lateinit var display: EGLDisplay
    private lateinit var context: EGLContext
    private lateinit var window: EGLSurface
    private lateinit var frames: SurfaceTexture
    private lateinit var input: Surface
    private var texture = 0
    private var program = 0
    private val width = codec.configurationFormat.width
    private val height = codec.configurationFormat.height
    private val transform = FloatArray(16)
    private val quad = GlUtil.createBuffer(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 0f, 1f, -1f, 1f, 0f, 1f, 1f, 1f, 0f, 1f))

    // Frames passed on to the encoder, which TenBitGate waits for.
    private val lock = Object()
    private var drawn = 0

    init {
        current = this
        onThread {
            display = GlUtil.getDefaultEglDisplay()
            context = GlUtil.createEglContext(EGL14.EGL_NO_CONTEXT, display, 2, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_1010102)
            window = GlUtil.createEglSurface(display, codec.inputSurface, C.COLOR_TRANSFER_HLG, /* isEncoderInputSurface = */ true)
            GlUtil.focusEglSurface(display, context, window, width, height)
            texture = GlUtil.createExternalTexture()
            program = program()
            frames = SurfaceTexture(texture).apply {
                setDefaultBufferSize(width, height)
                setOnFrameAvailableListener({ draw() }, handler)
            }
            input = Surface(frames)
        }
    }

    override fun getInputSurface(): Surface = input

    override fun getMaxPendingFrameCount(): Int = codec.maxPendingFrameCount

    override fun signalEndOfInputStream() {
        // After the frames already queued, whose turn on the thread comes first.
        onThread {}
        codec.signalEndOfInputStream()
    }

    override fun release() {
        if (current === this) current = null
        runCatching {
            onThread {
                input.release()
                frames.release()
                if (program != 0) GLES20.glDeleteProgram(program)
                GlUtil.deleteTexture(texture)
                GlUtil.destroyEglSurface(display, window)
                GlUtil.destroyEglContext(display, context)
            }
        }
        thread.quitSafely()
        codec.release()
    }

    private fun draw() {
        frames.updateTexImage()
        frames.getTransformMatrix(transform)
        GlUtil.focusEglSurface(display, context, window, width, height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTransform"), 1, false, transform, 0)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 4, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, window, frames.timestamp)
        EGL14.eglSwapBuffers(display, window)
        synchronized(lock) {
            drawn++
            lock.notifyAll()
        }
    }

    /** Waits until [count] frames are passed on, at most [millis]; false when they are not by then. */
    fun awaitDrawn(count: Int, millis: Long): Boolean {
        val until = System.nanoTime() + millis * 1_000_000
        synchronized(lock) {
            while (drawn < count) {
                val left = (until - System.nanoTime()) / 1_000_000
                if (left <= 0) return false
                lock.wait(left)
            }
        }
        return true
    }

    private fun program(): Int {
        fun compiled(type: Int, source: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, source)
            GLES20.glCompileShader(it)
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, compiled(GLES20.GL_VERTEX_SHADER, VERTEX))
            GLES20.glAttachShader(it, compiled(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
            GLES20.glLinkProgram(it)
            val status = IntArray(1)
            GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "HDR encoder input: " + GLES20.glGetProgramInfoLog(it) }
        }
    }

    /** Runs [block] on the thread and waits for it; what it throws is thrown here. */
    private fun onThread(block: () -> Unit) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        handler.post {
            try {
                block()
            } catch (problem: Throwable) {
                failure = problem
            } finally {
                done.countDown()
            }
        }
        done.await()
        failure?.let { throw it }
    }

    companion object {
        /** The encoder input of the HDR movie being made, if one is. */
        @Volatile
        internal var current: TenBitEncoderInput? = null

        private const val VERTEX = """
            attribute vec4 aPosition;
            uniform mat4 uTransform;
            varying vec2 vTex;
            void main() { gl_Position = aPosition; vTex = (uTransform * vec4(aPosition.xy * 0.5 + 0.5, 0.0, 1.0)).xy; }
        """
        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            uniform samplerExternalOES uTex;
            varying vec2 vTex;
            void main() { gl_FragColor = vec4(texture2D(uTex, vTex).rgb, 1.0); }
        """
    }
}

/**
 * The last effect of an HDR movie's picture: holds each frame until the frames before it have reached the encoder
 * ([TenBitEncoderInput]), so that none is lost on the way. Should they not come within a few seconds, the frame goes
 * on, and the section, a frame short, is rendered again ([Exporter]).
 */
class TenBitGate : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = object : PassthroughShaderProgram() {
        private var passed = 0

        override fun queueInputFrame(glObjectsProvider: GlObjectsProvider, inputTexture: GlTextureInfo, presentationTimeUs: Long) {
            TenBitEncoderInput.current?.awaitDrawn(passed, WAIT_MILLIS)
            passed++
            super.queueInputFrame(glObjectsProvider, inputTexture, presentationTimeUs)
        }
    }

    private companion object {
        const val WAIT_MILLIS = 5000L
    }
}
