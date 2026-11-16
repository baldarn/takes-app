package app.takes.probe.segmentation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/** A person mask: [alpha] is row by row, 0 background to 1 person, [width] × [height]. */
class Mask(val width: Int, val height: Int, val alpha: FloatArray) {
    /** The mask resampled (bilinear) to [w] × [h], so masks of different models can be compared. */
    fun resized(w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val sy = ((y + .5f) * height / h - .5f).coerceIn(0f, height - 1f)
            val y0 = sy.toInt(); val y1 = minOf(y0 + 1, height - 1); val fy = sy - y0
            for (x in 0 until w) {
                val sx = ((x + .5f) * width / w - .5f).coerceIn(0f, width - 1f)
                val x0 = sx.toInt(); val x1 = minOf(x0 + 1, width - 1); val fx = sx - x0
                val top = alpha[y0 * width + x0] * (1 - fx) + alpha[y0 * width + x1] * fx
                val bottom = alpha[y1 * width + x0] * (1 - fx) + alpha[y1 * width + x1] * fx
                out[y * w + x] = top * (1 - fy) + bottom * fy
            }
        }
        return out
    }
}

/** One model on one runtime and accelerator. [segment] takes an upright frame of any size. */
interface Segmenter : AutoCloseable {
    val label: String
    fun segment(frame: Bitmap): Mask
}

/** How a model wants its 0–255 channels: 0…1 or −1…1. */
enum class Range { UNIT, SIGNED }

private fun pixels(frame: Bitmap, w: Int, h: Int): IntArray {
    val scaled = if (frame.width == w && frame.height == h) frame else Bitmap.createScaledBitmap(frame, w, h, true)
    return IntArray(w * h).also { scaled.getPixels(it, 0, w, 0, 0, w, h); if (scaled !== frame) scaled.recycle() }
}

private fun channel(value: Int, range: Range): Float = if (range == Range.UNIT) value / 255f else value / 127.5f - 1f

/**
 * MediaPipe's selfie segmenters on LiteRT 1.4's Interpreter, on the CPU (XNNPACK) or the GPU
 * delegate. (LiteRT 2.3's CompiledModel needs Kotlin 2.4 metadata, newer than the app's Kotlin.)
 * `selfie`: 256×256×3 in, 256×256×1 out, the person's probability. `multiclass`: 256×256×3 in,
 * 256×256×6 out, (background, hair, body skin, face skin, clothes, others); the person is one
 * minus background (after a softmax if the model gives scores). `selfie` is the model as
 * tools/standard-selfie.py rewrites it, with LiteRT's own transposed convolution in place of
 * MediaPipe's custom one. The frame is stretched to the square, as MediaPipe does.
 */
class LiteSegmenter(file: File, private val multiclass: Boolean, gpu: Boolean, private val range: Range) : Segmenter {
    override val label = (if (multiclass) "multiclass" else "selfie") + ":" + (if (gpu) "gpu" else "cpu") + ":" + range.name.lowercase()
    private val delegate = if (gpu) GpuDelegate() else null
    private val interpreter = Interpreter(file, Interpreter.Options().apply { numThreads = 4; delegate?.let { addDelegate(it) } })
    private val side = 256
    private val input = ByteBuffer.allocateDirect(side * side * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(interpreter.getOutputTensor(0).numBytes()).order(ByteOrder.nativeOrder())
    val shapes = "in ${interpreter.getInputTensor(0).shape().contentToString()} out ${interpreter.getOutputTensor(0).shape().contentToString()}"

    override fun segment(frame: Bitmap): Mask {
        val pixels = pixels(frame, side, side)
        input.rewind()
        for (p in pixels) {
            input.putFloat(channel(p shr 16 and 255, range))
            input.putFloat(channel(p shr 8 and 255, range))
            input.putFloat(channel(p and 255, range))
        }
        input.rewind()
        output.rewind()
        interpreter.run(input, output)
        output.rewind()
        val raw = FloatArray(output.capacity() / 4).also { output.asFloatBuffer().get(it) }
        val classes = raw.size / (side * side)
        val alpha = FloatArray(side * side)
        if (classes == 1) {
            for (i in alpha.indices) alpha[i] = raw[i]
        } else if ((0 until classes).sumOf { raw[it].toDouble() }.let { kotlin.math.abs(it - 1) < .01 } && raw.all { it >= 0f }) {
            // Already probabilities (the model ends in a softmax).
            for (i in alpha.indices) alpha[i] = 1f - raw[i * classes]
        } else {
            for (i in alpha.indices) {
                var top = Float.NEGATIVE_INFINITY
                for (k in 0 until classes) top = max(top, raw[i * classes + k])
                var sum = 0f
                for (k in 0 until classes) sum += exp(raw[i * classes + k] - top)
                alpha[i] = 1f - exp(raw[i * classes] - top) / sum
            }
        }
        return Mask(side, side, alpha)
    }

    override fun close() {
        interpreter.close()
        delegate?.close()
    }
}

/**
 * MODNet (ZHKKKe/MODNet, Apache 2.0, the ONNX of Xenova/modnet) on ONNX Runtime: 1×3×H×W in
 * −1…1, 1×1×H×W alpha out. The frame keeps its shape, its long side [long] rounded to 32.
 */
class OnnxSegmenter(file: File, private val long: Int, xnnpack: Boolean) : Segmenter {
    override val label = "${file.nameWithoutExtension}:${if (xnnpack) "xnnpack" else "cpu"}:$long"
    private val environment = OrtEnvironment.getEnvironment()
    private val session = environment.createSession(
        file.absolutePath,
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            if (xnnpack) addXnnpack(mapOf("intra_op_num_threads" to "4"))
        },
    )
    private val name = session.inputNames.first()

    override fun segment(frame: Bitmap): Mask {
        val scale = long.toFloat() / max(frame.width, frame.height)
        val w = max(32, (frame.width * scale / 32f).roundToInt() * 32)
        val h = max(32, (frame.height * scale / 32f).roundToInt() * 32)
        val pixels = pixels(frame, w, h)
        val plane = w * h
        val input = FloatBuffer.allocate(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            input.put(i, channel(p shr 16 and 255, Range.SIGNED))
            input.put(plane + i, channel(p shr 8 and 255, Range.SIGNED))
            input.put(2 * plane + i, channel(p and 255, Range.SIGNED))
        }
        OnnxTensor.createTensor(environment, input, longArrayOf(1, 3, h.toLong(), w.toLong())).use { tensor ->
            session.run(mapOf(name to tensor)).use { result ->
                val out = (result[0] as OnnxTensor).floatBuffer
                val alpha = FloatArray(plane)
                out.get(alpha)
                return Mask(w, h, alpha)
            }
        }
    }

    override fun close() = session.close()
}

/**
 * `name:backend[:extra]` → a segmenter whose model is in [models]:
 * `selfie:cpu|gpu[:unit|signed]`, `multiclass:cpu|gpu[:unit|signed]`,
 * `modnet:cpu|xnnpack[:long side]`, `modnet_quantized:cpu|xnnpack[:long side]`.
 */
fun segmenter(spec: String, models: File): Segmenter {
    val parts = spec.split(":")
    val name = parts[0]
    val backend = parts.getOrElse(1) { "cpu" }
    return when (name) {
        "selfie" -> LiteSegmenter(File(models, "selfie_standard.tflite"), false, backend == "gpu", Range.valueOf(parts.getOrElse(2) { "unit" }.uppercase()))
        "multiclass" -> LiteSegmenter(File(models, "selfie_multiclass_256x256.tflite"), true, backend == "gpu", Range.valueOf(parts.getOrElse(2) { "signed" }.uppercase()))
        "modnet", "modnet_quantized" -> OnnxSegmenter(File(models, "$name.onnx"), parts.getOrElse(2) { "512" }.toInt(), backend == "xnnpack")
        else -> error("unknown model $name")
    }
}
