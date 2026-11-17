package app.takes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.FaceDetector
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.core.os.HandlerCompat
import app.takes.core.Affine
import app.takes.core.CrashLook
import app.takes.core.DualCameraLayout
import app.takes.core.FaceFraming
import app.takes.core.FaceTarget
import app.takes.core.HorizonEstimate
import app.takes.core.HorizonFraming
import app.takes.core.HorizonLine
import app.takes.core.LiveCrashZooms
import app.takes.core.EffectPalette
import app.takes.core.EffectRGB
import app.takes.core.LiveVideoEffects
import app.takes.core.VideoEffectSettings
import app.takes.core.VideoEffectTime
import app.takes.core.Point
import app.takes.core.RecorderFramePoints
import app.takes.core.Rect
import app.takes.core.SingleCameraCrop
import app.takes.core.Size
import app.takes.core.SubjectSample
import app.takes.core.SubjectTracker
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The recorder's own pass over each camera frame, for what the original does to its frames: the level horizon
 * turns and crops them, follow face crops them around a face, focus peaking marks sharp edges in the preview, and
 * the live camera moves follow a subject, Crash Zoom pushing the preview toward it (the take stays clean: the move
 * is saved beside it). It is put between the camera and its outputs only while one of these is on: otherwise the
 * camera library's own path records the take, untouched.
 *
 * Everything is worked out in the camera's own picture, as it arrives (`tc` below, top-left fractions), and each
 * output maps that picture its own way, so the preview and the recording show the same framing.
 *
 * The preview's frames and, while an assist shapes the take, the recording's come as two pictures of the camera,
 * each drawn with OpenGL on a thread and a context of its own ([Pass]), so that the looks drawn over the preview never
 * hold up a frame of the take. The framing is worked out with the recording's frames when they pass here, else with
 * the preview's, and the other follows it. Faces are found with the platform's [FaceDetector] on a small copy of the
 * picture, a few times a second, off those threads.
 */
class CameraFrames(context: Context) {
    /** What the frames are to do; set from the main thread, read with each frame. */
    data class Assists(
        /** The largest angle of the level horizon, or null when it is off. */
        val levelDegrees: Double? = null,
        val follow: Boolean = false,
        val peaking: Boolean = false,
        /** The camera already steadies the picture. */
        val stabilized: Boolean = false,
        /** Degrees the camera's picture turns clockwise to stand as it is recorded. */
        val upright: Int = 90,
        /** The zoom the lens delivers, its widest, and the zoom chosen when the crop makes it (follow face). */
        val inputZoom: Double = 1.0,
        val minimumZoom: Double = 1.0,
        val selectedZoom: Double? = null,
        /** A Crash Zoom or a Dolly Zoom is on: the subject is looked for, as the original tracks it for them. */
        val tracking: Boolean = false,
        /** The take is saved mirrored, the front camera's picture flipped. */
        val mirrored: Boolean = false,
    )

    /**
     * The Crash Zooms to draw over the preview, on a clock of seconds since [origin], a time of
     * [SystemClock.elapsedRealtimeNanos]: the segment's clock while one is recorded, the preview's otherwise.
     */
    data class Moves(val crash: LiveCrashZooms = LiveCrashZooms(), val origin: Long = 0L)

    /**
     * The subject found in a picture taken at [time], seconds of [SystemClock.elapsedRealtimeNanos]: [framed] in the
     * recorded frame, [whole] in the lens's whole picture turned as it is recorded, with the lens's [zoom] then.
     */
    class Subject(val framed: SubjectSample?, val whole: SubjectSample?, val time: Double, val zoom: Double)

    enum class FollowStatus { Searching, Following, Holding }

    @Volatile var assists = Assists()
    /** Called on the main thread when the face follow finds, holds or loses its face. */
    @Volatile var onFollowStatus: ((FollowStatus) -> Unit)? = null
    @Volatile var moves = Moves()

    /**
     * The live looks drawn over the preview (LiveEffectRendering.kt), on the clock of [moves]: [recording] while a
     * segment is written, when a Negative Strobe shows without being tried. The recording stays clean.
     */
    data class Looks(val effects: List<VideoEffectSettings> = emptyList(), val recording: Boolean = false, val tool: Boolean = false)

    @Volatile var looks = Looks()
    /** Android's thermal status, for the lighter processing when the phone is warm. */
    @Volatile var thermal = 0
    /** Called on the main thread when the note on the looks' quality changes, and whether the phone is warm. */
    @Volatile var onLookQuality: ((LiveVideoEffects.QualityNote?, Boolean) -> Unit)? = null
    /** Called on the main thread with a palette taken from one frame for a Quantization, or null when none could be. */
    @Volatile var onPalette: ((String, EffectPalette?) -> Unit)? = null

    private val live = LiveEffectRendering(context).apply {
        onQuality = { note, warm -> main.post { onLookQuality?.invoke(note, warm) } }
        onPalette = { id, palette -> main.post { this@CameraFrames.onPalette?.invoke(id, palette) } }
    }
    // The looks' state, on the preview's thread.
    private var audition: LiveVideoEffects.Audition? = null
    private var looksShown = false

    /** Tries the Negative Strobe [id] in the preview, for its burst. LiveVideoEffects.swift:138-140. */
    fun tryStrobe(id: String) = preview.handler.post { audition = LiveVideoEffects.Audition(id) }

    /** Ends a try of a strobe. LiveVideoEffects.swift:141-146. */
    fun endStrobe() = preview.handler.post { audition = null }

    /** The colour of the preview's looks about ([x], [y]) of the recorded frame, given on the main thread. LiveVideoEffects.swift:256-263. */
    fun pickColor(x: Double, y: Double, picked: (EffectRGB?) -> Unit) = preview.handler.post {
        val color = if (looksShown) live.colorAt(x, y) else null
        main.post { picked(color) }
    }

    /** Takes a palette from the next frame for the Quantization [id]. */
    fun samplePalette(id: String) = live.samplePalette(id)

    /** The looks start afresh: a new chain, camera or take. */
    fun resetLooks() = preview.handler.post { live.reset() }

    /** Where the looks keep their picked pictures. */
    val effectsFolder: java.io.File get() = live.folder
    /** Called on the main thread with each look for the subject, while [Assists.tracking]. */
    @Volatile var onSubject: ((Subject) -> Unit)? = null

    /** The subject tapped, in the recorded frame, or none: the next looks start afresh from it. LiveVideoEffects.swift:346-353. */
    fun target(point: Point?) = synchronized(framingLock) {
        seed = point
        tracker = null
        tracked = null
        generation++
    }

    // The lens's zoom at each picture, from what the camera reports with it: the zoom of the picture measured, not
    // the latest asked for, as the original's zoom history gives it (CaptureZoomHistory.swift).
    private val zooms = LongArray(ZOOMS)
    private val zoomValues = FloatArray(ZOOMS)
    private var zoomCount = 0

    /** The camera reported [zoom] for the picture of [timestamp], from its own thread. */
    fun reportZoom(timestamp: Long, zoom: Float) = synchronized(zooms) {
        zooms[zoomCount % ZOOMS] = timestamp
        zoomValues[zoomCount % ZOOMS] = zoom
        zoomCount++
    }

    private fun zoomAt(timestamp: Long): Double? = synchronized(zooms) {
        var best = -1
        for (i in 0 until minOf(zoomCount, ZOOMS)) {
            if (zooms[i] <= timestamp && (best < 0 || zooms[i] > zooms[best])) best = i
        }
        if (best >= 0) zoomValues[best].toDouble() else null
    }

    private val main = Handler(context.mainLooper)
    private val preview = Pass("camera-frames-preview")
    private val recording = Pass("camera-frames-recording")
    /** The recording's frames pass here, and the framing is worked out with them while they come; else with the preview's. */
    @Volatile private var recordingLeads = false
    private val leader: Pass get() = if (recordingLeads) recording else preview
    /** When the recording's pass last worked out the framing, a time of [SystemClock.elapsedRealtimeNanos]. */
    @Volatile private var recordingFramedAt = 0L
    /** Guards the framing's state, worked out on the leading pass's thread, read by the analysis and the taps. */
    private val framingLock = Any()
    private val analysis = Executors.newSingleThreadExecutor { Thread(it, "camera-frames-analysis") }

    // MARK: Gravity, for the level horizon.

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gravitySensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    /** The phone can tell which way is down. */
    val levelAvailable: Boolean get() = gravitySensor != null
    @Volatile private var gravity: FloatArray? = null
    @Volatile private var gravityAt = 0L
    private var listening = false
    private val gravityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            gravity = floatArrayOf(event.values[0], event.values[1])
            gravityAt = SystemClock.elapsedRealtimeNanos()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Listens to gravity while the level horizon is on. Main thread. */
    fun listenToGravity(on: Boolean) {
        if (on == listening || gravitySensor == null) return
        listening = on
        if (on) sensors?.registerListener(gravityListener, gravitySensor, SensorManager.SENSOR_DELAY_GAME, preview.handler)
        else { sensors?.unregisterListener(gravityListener); gravity = null }
    }

    /**
     * The effects to bind with the use cases, for [targets] of [CameraEffect]: one for each, so that the preview's
     * pictures, with their looks, are drawn apart from the recording's.
     */
    fun effects(targets: Int): List<CameraEffect> {
        recordingLeads = targets and CameraEffect.VIDEO_CAPTURE != 0
        return listOfNotNull(
            preview.takeIf { targets and CameraEffect.PREVIEW != 0 }?.effect(CameraEffect.PREVIEW),
            recording.takeIf { targets and CameraEffect.VIDEO_CAPTURE != 0 }?.effect(CameraEffect.VIDEO_CAPTURE),
        )
    }

    /** One picture of the camera, the preview's or the recording's, drawn on its own thread with its own OpenGL context. */
    private inner class Pass(name: String) : SurfaceProcessor {
        private val thread = HandlerThread(name).apply { start() }
        val handler: Handler = HandlerCompat.createAsync(thread.looper)
        private val executor = Executor { handler.post(it) }

        fun effect(target: Int): CameraEffect = object : CameraEffect(target, executor, this, { _ -> }) {}

        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var config: EGLConfig? = null
        private var idle: EGLSurface = EGL14.EGL_NO_SURFACE
        private var program = 0
        private var texture = 0
        private var input: SurfaceTexture? = null
        private var inputSurface: Surface? = null
        var inputSize = android.util.Size(1920, 1080)
            private set
        private val textureMatrix = FloatArray(16)
        private val outputMatrix = FloatArray(16)

        private inner class Output(val output: SurfaceOutput, val surface: EGLSurface, val video: Boolean)
        private val outputs = mutableListOf<Output>()

        private fun setUp() {
            if (display != EGL14.EGL_NO_DISPLAY) return
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(display, version, 0, version, 1)
            // OpenGL ES 3 where the phone has it, for the temporal mattes of the live looks (EffectMatteWindow); else 2.
            for (version in intArrayOf(3, 2)) {
                val attributes = intArrayOf(
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, if (version == 3) EGLExt.EGL_OPENGL_ES3_BIT_KHR else EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE,
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val found = IntArray(1)
                if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, found, 0) || found[0] == 0) continue
                val made = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, version, EGL14.EGL_NONE), 0)
                if (made == null || made == EGL14.EGL_NO_CONTEXT) continue
                config = configs[0]
                eglContext = made
                break
            }
            if (config == null) error("No EGL configuration")
            idle = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(display, idle, idle, eglContext)
            program = link(VERTEX, FRAGMENT)
            val names = IntArray(1)
            GLES20.glGenTextures(1, names, 0)
            texture = names[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        override fun onInputSurface(request: SurfaceRequest) {
            setUp()
            input?.release()
            inputSurface?.release()
            inputSize = request.resolution
            val surfaceTexture = SurfaceTexture(texture).apply { setDefaultBufferSize(inputSize.width, inputSize.height) }
            val surface = Surface(surfaceTexture)
            input = surfaceTexture
            inputSurface = surface
            if (this === leader) restart()
            surfaceTexture.setOnFrameAvailableListener({ draw(it) }, handler)
            request.provideSurface(surface, executor) {
                surfaceTexture.setOnFrameAvailableListener(null)
                surfaceTexture.release()
                surface.release()
                if (input === surfaceTexture) { input = null; inputSurface = null }
            }
        }

        override fun onOutputSurface(output: SurfaceOutput) {
            setUp()
            val surface = output.getSurface(executor) { event ->
                if (event.eventCode == SurfaceOutput.Event.EVENT_REQUEST_CLOSE) {
                    outputs.firstOrNull { it.output === output }?.let { remove(it) }
                    output.close()
                }
            }
            val egl = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            outputs += Output(output, egl, output.targets and CameraEffect.VIDEO_CAPTURE != 0)
        }

        private fun remove(output: Output) {
            outputs.remove(output)
            EGL14.eglMakeCurrent(display, idle, idle, eglContext)
            EGL14.eglDestroySurface(display, output.surface)
        }

        private fun draw(surfaceTexture: SurfaceTexture) {
            if (surfaceTexture !== input) return
            EGL14.eglMakeCurrent(display, idle, idle, eglContext)
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(textureMatrix)
            val nanos = SystemClock.elapsedRealtimeNanos()
            val now = nanos / 1e9
            // The framing is worked out with the recording's frames while they come, which the camera may give only
            // while a segment is written, else with the preview's; the other pass draws the latest.
            val leads = if (this === recording) recordingLeads else !recordingLeads || nanos - recordingFramedAt > HANDOVER
            val effect = if (leads) synchronized(framingLock) {
                if (this === recording) recordingFramedAt = nanos
                transform(SystemClock.elapsedRealtimeNanos() / 1e9, surfaceTexture.timestamp, this)
            } else framed
            val wanted = assists
            val previewing = outputs.any { !it.video }
            // The preview's Crash Zoom, about its target in the camera's picture before the crop: the recording is left clean.
            val moving = moves
            val look = if (previewing) moving.crash.look((nanos - moving.origin) / 1e9, tracked?.takeIf { now - it.second <= 0.5 }?.first) else null
            val crash = look?.let { CrashLook(it.zoom, it.blur, RecorderFramePoints.toCamera(it.target, wanted.upright, wanted.mirrored)) }
            // The looks, drawn on the frame standing as it is recorded, for the preview alone. LiveVideoEffects.swift:409-689.
            val sideways = (wanted.upright % 180 + 180) % 180 != 0
            val uprightWidth = if (sideways) inputSize.height else inputSize.width
            val uprightHeight = if (sideways) inputSize.width else inputSize.height
            var drewLooks = false
            if (previewing) {
                val looking = looks
                val lookTime = VideoEffectTime.captureTime((nanos - moving.origin) / 1e9)
                val (shown, tried) = LiveVideoEffects.displayed(looking.effects, lookTime, looking.recording, audition)
                audition = tried
                // A frame tool draws the picture it reads from, with no effect on it if need be.
                drewLooks = (looking.tool || shown.any { it.enabled }) && try {
                    live.draw(shown, lookTime, uprightWidth, uprightHeight, thermal, looking.tool) { width, height ->
                        GLES20.glViewport(0, 0, width, height)
                        render(uprightMatrix(wanted.upright, wanted.mirrored), effect, peaking = false, crash = crash)
                    }
                } catch (problem: Exception) {
                    android.util.Log.w("LiveEffects", "Live effect preview", problem)
                    false
                }
                looksShown = drewLooks
            }
            for (output in outputs.toList()) {
                if (!EGL14.eglMakeCurrent(display, output.surface, output.surface, eglContext)) continue
                output.output.updateTransformMatrix(outputMatrix, textureMatrix)
                GLES20.glViewport(0, 0, output.output.size.width, output.output.size.height)
                if (drewLooks && !output.video) present(outputMatrix, wanted.upright, wanted.mirrored)
                else render(outputMatrix, effect, peaking = wanted.peaking && !output.video, crash = if (output.video) null else crash)
                if (output.video) EGLExt.eglPresentationTimeANDROID(display, output.surface, surfaceTexture.timestamp)
                EGL14.eglSwapBuffers(display, output.surface)
            }
            if (drewLooks) live.shown((SystemClock.elapsedRealtimeNanos() - nanos) / 1e9, maxOf(uprightWidth, uprightHeight).toDouble())
        }

        private var presenter = 0

        /** The looks drawn on the upright picture, onto an output that maps itself onto the frame's picture with [output]. */
        private fun present(output: FloatArray, upright: Int, mirrored: Boolean) {
            if (presenter == 0) presenter = link(VERTEX, PRESENT)
            GLES20.glUseProgram(presenter)
            val position = GLES20.glGetAttribLocation(presenter, "aPosition")
            val coordinate = GLES20.glGetAttribLocation(presenter, "aCoordinate")
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, QUAD)
            GLES20.glEnableVertexAttribArray(coordinate)
            GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 0, CORNERS)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(presenter, "uOutput"), 1, false, output, 0)
            // The frame's picture to the upright one, rows from the bottom.
            fun at(x: Double, y: Double) = RecorderFramePoints.fromCamera(Point(x, y), upright, mirrored).let { Point(it.x, 1 - it.y) }
            val o = at(0.0, 0.0)
            val x = at(1.0, 0.0)
            val y = at(0.0, 1.0)
            val columns = floatArrayOf(
                (x.x - o.x).toFloat(), (x.y - o.y).toFloat(), 0f,
                (y.x - o.x).toFloat(), (y.y - o.y).toFloat(), 0f,
                o.x.toFloat(), o.y.toFloat(), 1f,
            )
            GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(presenter, "uEffect"), 1, false, columns, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, live.resultTexture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(presenter, "uTexture"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private var frameBuffer = 0
        private var frameTexture = 0
        private var frameSize = android.util.Size(0, 0)

        /** The camera's picture as it arrives, [width] by [height], rows from the top, four bytes a pixel. */
        fun readBack(width: Int, height: Int): ByteBuffer {
            if (frameSize.width != width || frameSize.height != height) {
                if (frameBuffer != 0) {
                    GLES20.glDeleteFramebuffers(1, intArrayOf(frameBuffer), 0)
                    GLES20.glDeleteTextures(1, intArrayOf(frameTexture), 0)
                }
                val names = IntArray(1)
                GLES20.glGenTextures(1, names, 0)
                frameTexture = names[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTexture)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glGenFramebuffers(1, names, 0)
                frameBuffer = names[0]
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBuffer)
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, frameTexture, 0)
                frameSize = android.util.Size(width, height)
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBuffer)
            GLES20.glViewport(0, 0, width, height)
            // The first row drawn, at the bottom, is the picture's top row, and the first row read back.
            render(IDENTITY, Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0), peaking = false, crash = null)
            val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            return pixels
        }

        private fun render(output: FloatArray, effect: Affine, peaking: Boolean, crash: CrashLook?) {
            GLES20.glUseProgram(program)
            val position = GLES20.glGetAttribLocation(program, "aPosition")
            val coordinate = GLES20.glGetAttribLocation(program, "aCoordinate")
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, QUAD)
            GLES20.glEnableVertexAttribArray(coordinate)
            GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 0, CORNERS)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uOutput"), 1, false, output, 0)
            val columns = floatArrayOf(
                effect.a.toFloat(), effect.b.toFloat(), 0f,
                effect.c.toFloat(), effect.d.toFloat(), 0f,
                effect.tx.toFloat(), effect.ty.toFloat(), 1f,
            )
            GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(program, "uEffect"), 1, false, columns, 0)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uTexel"), 1f / inputSize.width, 1f / inputSize.height)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uPeaking"), if (peaking) 1 else 0)
            GLES20.glUniform4f(
                GLES20.glGetUniformLocation(program, "uCrash"),
                (crash?.target?.x ?: 0.5).toFloat(), (crash?.target?.y ?: 0.5).toFloat(), (crash?.zoom ?: 1.0).toFloat(), (crash?.blur ?: 0.0).toFloat(),
            )
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
    }

    // MARK: The framing, worked out with each frame of the leading pass, under [framingLock]

    private var horizon = HorizonEstimate()
    private var framing = DualCameraLayout.Framing()
    private var reserve = 1.0
    private var target = FaceTarget()
    private var status = FollowStatus.Searching
    private var previousTime: Double? = null
    private var lastLevel: Double? = null
    private var lastFollow = false
    private var lastAnalysis = Double.NEGATIVE_INFINITY
    private var analysing = false
    private var generation = 0

    /** A new picture, camera or turn: the framing starts afresh. */
    private fun restart() = synchronized(framingLock) {
        generation++
        horizon = HorizonEstimate()
        framing = DualCameraLayout.Framing()
        target = FaceTarget()
        previousTime = null
        setStatus(FollowStatus.Searching)
    }

    private fun setStatus(next: FollowStatus) {
        if (status == next) return
        status = next
        main.post { onFollowStatus?.invoke(next) }
    }

    /**
     * From the corners of the upright picture, rows from the bottom, to the frame's picture in the camera's own
     * orientation, as [render] reads it.
     */
    private fun uprightMatrix(upright: Int, mirrored: Boolean): FloatArray {
        fun at(s: Double, t: Double) = RecorderFramePoints.toCamera(Point(s, 1 - t), upright, mirrored)
        val o = at(0.0, 0.0)
        val x = at(1.0, 0.0)
        val y = at(0.0, 1.0)
        return floatArrayOf(
            (x.x - o.x).toFloat(), (x.y - o.y).toFloat(), 0f, 0f,
            (y.x - o.x).toFloat(), (y.y - o.y).toFloat(), 0f, 0f,
            0f, 0f, 1f, 0f,
            o.x.toFloat(), o.y.toFloat(), 0f, 1f,
        )
    }

    /**
     * From the frame's picture to the camera's, in top-left fractions of the camera's picture, for the frame of
     * [timestamp] drawn by [pass] at [now], read under the lock so that it never goes back; kept for the other pass as [framed].
     */
    private fun transform(now: Double, timestamp: Long, pass: Pass): Affine {
        frameStamp = timestamp
        val inputSize = pass.inputSize
        val wanted = assists
        val dt = SingleCameraCrop.elapsed(previousTime, now)
        if (SingleCameraCrop.restarts(true, previousTime, now)) restart()
        previousTime = now
        val size = Size(inputSize.width.toDouble(), inputSize.height.toDouble())
        // The level horizon, from gravity and now and then the picture.
        var level: HorizonFraming? = null
        val maximum = wanted.levelDegrees
        if (maximum != lastLevel) { horizon = HorizonEstimate(); lastLevel = maximum }
        if (maximum != null) {
            val g = gravity?.takeIf { SystemClock.elapsedRealtimeNanos() - gravityAt < 250_000_000L }
            // The platform's gravity points up from the ground, the original's down; both in units of g there.
            val roll = g?.let { HorizonEstimate.roll(-it[0] / SensorManager.GRAVITY_EARTH.toDouble(), -it[1] / SensorManager.GRAVITY_EARTH.toDouble(), 90.0, false) }
            val angle = horizon.correction(roll, now, maximum, wanted.stabilized)
            level = HorizonFraming(angle, HorizonFraming.scale(maximum, size))
            if (roll != null && abs(roll) < 13 * Math.PI / 180) analyse(now, roll, wanted, level = true, pass)
        }
        // Follow face: a crop of the picture that eases toward the face.
        if (wanted.follow != lastFollow) { lastFollow = wanted.follow; target = FaceTarget(); setStatus(FollowStatus.Searching) }
        reserve = SingleCameraCrop.reserve(reserve, wanted.follow, manual = false, dt = dt)
        framing = framing.copy(zoom = SingleCameraCrop.zoom(wanted.inputZoom, wanted.selectedZoom, wanted.minimumZoom, reserve))
        if (wanted.tracking && !wanted.follow) analyse(now, null, wanted, level = false, pass)
        if (wanted.follow) {
            analyse(now, null, wanted, level = false, pass)
            val face = target.current(now)
            setStatus(if (face != null) FollowStatus.Following else if (status == FollowStatus.Searching) FollowStatus.Searching else FollowStatus.Holding)
            framing = FaceFraming.follow(face, size, Rect(0.0, 0.0, size.width, size.height), framing, dt)
        } else if (reserve <= 1.0 && wanted.selectedZoom == null) {
            // Back to the whole picture, centred, for the next time.
            framing = DualCameraLayout.Framing()
        }
        val crop = SingleCameraCrop.crop(framing, null, wanted.inputZoom, wanted.minimumZoom, 0.0, false).rectangle
        val mapping = Affine(crop.width, 0.0, 0.0, crop.height, crop.minX, crop.minY)
        return (level?.sourceTransform(size)?.concatenating(mapping) ?: mapping).also { framed = it }
    }

    // MARK: The subject of a camera move

    private var seed: Point? = null
    private var tracker: SubjectTracker? = null
    /** The subject's centre in the recorded frame, and when it was seen: Crash Zoom's target while it follows a face. */
    @Volatile private var tracked: Pair<Point, Double>? = null
    private var frameStamp = 0L
    /** The latest framing worked out, which the pass that does not lead draws. */
    @Volatile private var framed = Affine.IDENTITY

    /**
     * The subject among [faces] (boxes of the camera's picture), followed in the lens's whole picture turned as it is
     * recorded, and reported in that picture and in the recorded frame, which [effect] crops from it.
     */
    private fun track(faces: List<Rect>, now: Double, zoom: Double, effect: Affine, wanted: Assists) {
        val upright = wanted.upright
        val mirrored = wanted.mirrored
        val current = tracker ?: SubjectTracker(seed?.let { point ->
            // From the recorded frame to the camera's picture, through the crop, and turned as it is recorded.
            RecorderFramePoints.fromCamera(RecorderFramePoints.toCamera(point, upright, mirrored).applying(effect), upright, mirrored)
        })
        val (next, whole) = current.update(faces.map { RecorderFramePoints.fromCamera(it, upright, mirrored) }, emptyList(), now)
        tracker = next
        val inverse = effect.inverted()
        fun framed(p: Point) = RecorderFramePoints.fromCamera(RecorderFramePoints.toCamera(p, upright, mirrored).applying(inverse), upright, mirrored)
        val framed = whole?.let { sample ->
            val a = framed(Point(sample.box.minX, sample.box.minY))
            val b = framed(Point(sample.box.maxX, sample.box.maxY))
            SubjectTracker.intersectUnit(Rect(min(a.x, b.x), min(a.y, b.y), abs(a.x - b.x), abs(a.y - b.y)))?.let { sample.copy(box = it) }
        }
        if (framed != null) tracked = framed.center to now
        val report = Subject(framed, whole, now, zoom)
        main.post { onSubject?.invoke(report) }
    }

    // MARK: Looking at the picture

    private var faceDetector: FaceDetector? = null
    private var faceSize = android.util.Size(0, 0)

    /**
     * Copies a small picture now and then and looks at it off this thread: a long straight edge for the level
     * horizon, at most every quarter of a second, and faces for the follow, at most five times a second.
     */
    private fun analyse(now: Double, roll: Double?, wanted: Assists, level: Boolean, pass: Pass) {
        // The subject of a camera move is looked for fifteen times a second, as the original tracks it.
        if (analysing || now - lastAnalysis < (if (level) 0.24 else if (wanted.tracking) 1.0 / 15 else 0.2)) return
        analysing = true
        lastAnalysis = now
        val long = if (level && !wanted.follow && !wanted.tracking) 256 else 480
        val zoom = zoomAt(frameStamp) ?: wanted.inputZoom
        val effect = framed
        val inputSize = pass.inputSize
        val width = (if (inputSize.width >= inputSize.height) long else long * inputSize.width / inputSize.height) and 1.inv()
        val height = (if (inputSize.width >= inputSize.height) long * inputSize.height / inputSize.width else long) and 1.inv()
        val pixels = pass.readBack(width, height)
        val token = generation
        analysis.execute {
            val luma = if (wanted.levelDegrees != null && roll != null) ByteArray(width * height) { i ->
                val p = i * 4
                (((pixels.get(p).toInt() and 0xFF) * 54 + (pixels.get(p + 1).toInt() and 0xFF) * 183 + (pixels.get(p + 2).toInt() and 0xFF) * 19) shr 8).toByte()
            } else null
            val line = luma?.let { sample(it, width, height, wanted.upright) }
            val faces = if (wanted.follow || wanted.tracking) faces(pixels, width, height, wanted.upright) else emptyList()
            synchronized(framingLock) {
                analysing = false
                if (token != generation) return@execute
                if (roll != null && luma != null) horizon.observe(line, roll, now)
                if (wanted.follow) target = target.observing(faces, now)
                if (wanted.tracking) track(faces, now, zoom, effect, wanted)
            }
        }
    }

    /** The straight edge, on the picture at most 256 wide, turned as it is recorded. */
    private fun sample(luma: ByteArray, width: Int, height: Int, upright: Int): Double? {
        val ratio = min(1.0, 256.0 / max(width, height))
        val w = (width * ratio).toInt()
        val h = (height * ratio).toInt()
        val small = if (ratio >= 1.0) luma else ByteArray(w * h) { i -> luma[(i / w) * height / h * width + (i % w) * width / w] }
        return if (upright % 180 != 0) HorizonLine.estimateSideways(small, w, h) else HorizonLine.estimate(small, w, h)
    }

    /** The faces in the picture, as boxes in fractions of the camera's picture. */
    private fun faces(pixels: ByteBuffer, width: Int, height: Int, upright: Int): List<Rect> {
        val colours = IntArray(width * height)
        for (i in colours.indices) {
            val p = i * 4
            colours[i] = (0xFF shl 24) or ((pixels.get(p).toInt() and 0xFF) shl 16) or ((pixels.get(p + 1).toInt() and 0xFF) shl 8) or (pixels.get(p + 2).toInt() and 0xFF)
        }
        val picture = Bitmap.createBitmap(colours, width, height, Bitmap.Config.ARGB_8888)
        // The detector finds faces standing up, in pictures stored in 16 bits.
        val turned = Bitmap.createBitmap(picture, 0, 0, width, height, android.graphics.Matrix().apply { postRotate(upright.toFloat()) }, false)
            .copy(Bitmap.Config.RGB_565, false)
        val w = turned.width and 1.inv()
        val h = turned.height
        if (faceDetector == null || faceSize.width != w || faceSize.height != h) {
            faceDetector = FaceDetector(w, h, MAX_FACES)
            faceSize = android.util.Size(w, h)
        }
        val input = if (turned.width == w) turned else Bitmap.createBitmap(turned, 0, 0, w, h)
        val found = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
        val count = runCatching { faceDetector!!.findFaces(input, found) }.getOrDefault(0)
        val middle = PointF()
        return (0 until count).mapNotNull { index ->
            val face = found[index]?.takeIf { it.confidence() >= FaceDetector.Face.CONFIDENCE_THRESHOLD } ?: return@mapNotNull null
            face.getMidPoint(middle)
            val eyes = face.eyesDistance().toDouble()
            // From the eyes to a box around the face, on the picture standing up.
            val box = Rect((middle.x - eyes * 1.1) / w, (middle.y - eyes * 0.9) / h, eyes * 2.2 / w, eyes * 2.6 / h)
            camera(box, upright)
        }
    }

    /** A box on the picture standing up, turned back into the camera's picture. */
    private fun camera(box: Rect, upright: Int): Rect {
        fun back(x: Double, y: Double): Pair<Double, Double> = when ((upright % 360 + 360) % 360) {
            90 -> y to 1 - x
            180 -> 1 - x to 1 - y
            270 -> 1 - y to x
            else -> x to y
        }
        val corners = listOf(back(box.minX, box.minY), back(box.maxX, box.maxY))
        val minX = min(corners[0].first, corners[1].first)
        val minY = min(corners[0].second, corners[1].second)
        return Rect(minX, minY, abs(corners[0].first - corners[1].first), abs(corners[0].second - corners[1].second))
    }

    internal companion object {
        /** A program of the two shaders. */
        fun link(vertex: String, fragment: String): Int {
            fun shader(type: Int, source: String) = GLES20.glCreateShader(type).also {
                GLES20.glShaderSource(it, source)
                GLES20.glCompileShader(it)
                val ok = IntArray(1)
                GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) error("Shader: " + GLES20.glGetShaderInfoLog(it))
            }
            return GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, vertex))
                GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
                GLES20.glLinkProgram(it)
            }
        }

        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val MAX_FACES = 4
        private const val ZOOMS = 32
        /** The preview's pass works out the framing again once the recording's has had no frame for this long. */
        private const val HANDOVER = 100_000_000L
        private val IDENTITY = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }

        private fun buffer(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }

        internal val QUAD = buffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val CORNERS = buffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        private const val VERTEX = """
            attribute vec4 aPosition;
            attribute vec4 aCoordinate;
            uniform mat4 uOutput;
            varying vec2 vCoordinate;
            void main() {
                gl_Position = aPosition;
                vCoordinate = (uOutput * aCoordinate).xy;
            }
        """

        // Focus peaking as the original's `recorderFocusPeaking` (VideoEffects.metal): a sharp edge is a strong
        // gradient with high-frequency detail, extended by one pixel, mixed into the picture in red.
        // The looks drawn on the upright picture, read where the frame's picture falls in it.
        private const val PRESENT = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform mat3 uEffect;
            varying vec2 vCoordinate;
            void main() { gl_FragColor = texture2D(uTexture, (uEffect * vec3(vCoordinate, 1.0)).xy); }
        """

        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            uniform samplerExternalOES uTexture;
            uniform mat3 uEffect;
            uniform vec2 uTexel;
            uniform int uPeaking;
            // Crash Zoom: its target in the camera's picture, the magnification and the reach of the radial blur.
            uniform vec4 uCrash;
            varying vec2 vCoordinate;
            float luma(vec2 p) { return dot(texture2D(uTexture, p).rgb, vec3(0.2126, 0.7152, 0.0722)); }
            float sharp(vec2 p) {
                float l = luma(p - vec2(uTexel.x, 0.0));
                float r = luma(p + vec2(uTexel.x, 0.0));
                float t = luma(p - vec2(0.0, uTexel.y));
                float b = luma(p + vec2(0.0, uTexel.y));
                float c = luma(p);
                float gradient = length(vec2(r - l, b - t));
                float detail = max(abs(l + r - 2.0 * c), abs(t + b - 2.0 * c));
                return smoothstep(0.065, 0.16, gradient) * smoothstep(0.017, 0.06, detail);
            }
            void main() {
                // Crash Zoom as the editor draws it (Metal:87-94, 361-366): the picture magnified about the target,
                // averaged at eight points along the line from the target, before the crop.
                vec2 target = uCrash.xy;
                vec2 point = (vCoordinate - target) / uCrash.z + target;
                vec2 p = (uEffect * vec3(point, 1.0)).xy;
                vec4 colour = texture2D(uTexture, p);
                if (uCrash.w > 0.0) {
                    vec3 sum = vec3(0.0);
                    for (int i = 0; i < 8; i++) {
                        float k = uCrash.w * (float(i) / 7.0 - 0.5);
                        sum += texture2D(uTexture, (uEffect * vec3(point + (point - target) * k, 1.0)).xy).rgb / 8.0;
                    }
                    colour.rgb = sum;
                }
                if (uPeaking == 1) {
                    float strength = max(sharp(p), 0.9 * max(sharp(p + vec2(uTexel.x, 0.0)), sharp(p + vec2(0.0, uTexel.y))));
                    colour.rgb = mix(colour.rgb, vec3(1.0, 0.045, 0.025), strength);
                }
                gl_FragColor = colour;
            }
        """
    }
}
