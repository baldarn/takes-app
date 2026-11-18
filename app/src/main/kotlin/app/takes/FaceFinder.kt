package app.takes

import android.content.Context
import app.takes.core.FaceDetection
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Finds the faces in a picture with MediaPipe's full-range face detector on LiteRT's CPU, where the original asks
 * Vision for its face rectangles (YouRec/DualFaceDetector.swift:30-34, VideoEffectSubjectTracking.swift:65-67): for
 * the recorder's Follow face, with one camera and with two, the subject of its camera moves (CameraFrames.kt), and
 * the subject the editor's Crash Zoom and Dolly Zoom follow (SubjectTracks.kt). What goes in and comes out is
 * [FaceDetection]'s.
 *
 * The model finds faces turned to three quarters and in profile, and small ones, where Android's own
 * `android.media.FaceDetector` finds frontal faces by their eyes. It runs where the person masks do
 * ([PersonMasks.available]): LiteRT has a native library for every processor the app is built for.
 *
 * Not thread safe: one picture at a time, each finder on one thread.
 */
class FaceFinder(context: Context) : AutoCloseable {
    private val interpreter = Interpreter(model(context), Interpreter.Options().setNumThreads(THREADS))
    private val pixels = FloatArray(FaceDetection.SIDE * FaceDetection.SIDE * 3)
    private val input = ByteBuffer.allocateDirect(pixels.size * 4).order(ByteOrder.nativeOrder())
    private val boxes = FloatArray(FaceDetection.ANCHORS * FaceDetection.VALUES)
    private val scores = FloatArray(FaceDetection.ANCHORS)
    private val boxOutput = ByteBuffer.allocateDirect(boxes.size * 4).order(ByteOrder.nativeOrder())
    private val scoreOutput = ByteBuffer.allocateDirect(scores.size * 4).order(ByteOrder.nativeOrder())
    // The model gives the boxes and the scores as two outputs: told apart by their last dimension, 16 or 1.
    private val boxIndex = (0 until interpreter.outputTensorCount).first { interpreter.getOutputTensor(it).shape().last() == FaceDetection.VALUES }
    private val outputs: Map<Int, Any> = mapOf(boxIndex to boxOutput, (1 - boxIndex) to scoreOutput)

    // How long the looks took, in milliseconds, logged every hundred: the picture laid on the model's square, and the model.
    private var looks = 0
    private var filling = 0.0
    private var running = 0.0

    /**
     * The faces in the picture of [width] × [height] pixels that [rgb] reads, turned [upright] degrees clockwise to
     * stand as the model wants faces: boxes in fractions of the picture stood upright, top-down, best first.
     */
    fun faces(width: Int, height: Int, upright: Int, rgb: FaceDetection.Pixels): List<FaceDetection.Face> {
        if (width <= 0 || height <= 0) return emptyList()
        val started = System.nanoTime()
        val letterbox = FaceDetection.fill(pixels, width, height, upright, rgb)
        input.rewind()
        input.asFloatBuffer().put(pixels)
        val filled = System.nanoTime()
        boxOutput.rewind()
        scoreOutput.rewind()
        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)
        boxOutput.rewind()
        boxOutput.asFloatBuffer().get(boxes)
        scoreOutput.rewind()
        scoreOutput.asFloatBuffer().get(scores)
        val ran = System.nanoTime()
        filling += (filled - started) / 1e6
        running += (ran - filled) / 1e6
        if (++looks == 100) {
            android.util.Log.i("FaceFinder", "100 looks: %.1f ms to lay the picture, %.1f ms the model, on average".format(filling / looks, running / looks))
            looks = 0; filling = 0.0; running = 0.0
        }
        return FaceDetection.decode(boxes, scores, letterbox)
    }

    override fun close() = interpreter.close()

    companion object {
        /** The model, in the app's assets: provenance in res/raw/credits.txt. */
        const val MODEL = "face_detection_full_range.tflite"

        /** Two threads: the model is small, and it runs beside the camera's frames and the recording. */
        private const val THREADS = 2

        /** Whether faces can be found on this phone: where LiteRT's native library loads. */
        fun available(): Boolean = PersonMasks.available()

        @Volatile private var model: MappedByteBuffer? = null

        private fun model(context: Context): MappedByteBuffer = model ?: synchronized(this) {
            model ?: context.assets.openFd(MODEL).use { asset ->
                // Mapped where it lies in the APK, which stores it uncompressed (noCompress in build.gradle.kts).
                FileInputStream(asset.fileDescriptor).channel.use { it.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.declaredLength) }
            }.also { model = it }
        }
    }
}
