package app.takes.deepfilter

import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * One network run by ONNX Runtime, through its C API from this module's own small native
 * library (`src/main/cpp`). The runtime is the libonnxruntime.so the app already carries
 * for sherpa-onnx (`speech/`), so the app has a single copy of ONNX Runtime.
 *
 * [graph] is the ONNX file; [weights] are the tensors it keeps outside itself, copied into
 * the session, so their buffers may be dropped afterwards. [inputs] and [outputs] are the
 * names [run] takes and fills, in that order; all of them are 32-bit floats.
 */
internal class OnnxSession(
    graph: ByteArray,
    weights: List<Weight>,
    private val inputs: List<String>,
    private val outputs: List<String>,
) : AutoCloseable {
    /** A tensor kept outside the graph: [data] is a direct buffer of [shape] in ONNX's [type] (1 float, 7 int64). */
    class Weight(val name: String, val type: Int, val shape: LongArray, val data: ByteBuffer)

    // Runs may overlap one another, but none may overlap closing.
    private val lock = ReentrantReadWriteLock()
    private var handle: Long

    init {
        loadError?.let { throw it }
        handle = create(
            graph,
            weights.map { it.name }.toTypedArray(),
            weights.map { it.data }.toTypedArray(),
            weights.map { it.shape }.toTypedArray(),
            weights.map { it.type }.toIntArray(),
            inputs.toTypedArray(),
            outputs.toTypedArray(),
        )
    }

    /**
     * Runs the network on [inputValues] (direct buffers, of [inputShapes]) and copies its
     * results into [outputValues] (direct buffers, each of exactly the size of its result).
     * Safe to call from several threads at once.
     */
    fun run(inputValues: Array<FloatBuffer>, inputShapes: Array<LongArray>, outputValues: Array<FloatBuffer>) {
        require(inputValues.size == inputs.size && inputShapes.size == inputs.size) { "${inputs.size} inputs expected" }
        require(outputValues.size == outputs.size) { "${outputs.size} outputs expected" }
        lock.read {
            check(handle != 0L) { "the network is closed" }
            execute(handle, inputValues, inputShapes, outputValues)
        }
    }

    override fun close() {
        lock.write {
            if (handle != 0L) destroy(handle)
            handle = 0
        }
    }

    companion object {
        /**
         * Why ONNX Runtime cannot be used here, or null when it can. On a phone it is the
         * app's libonnxruntime.so; on 32-bit x86, for which neither it nor sherpa-onnx is
         * built, it is missing. The unit tests name the computer's copy with the system
         * property `takes.onnxruntime.library`.
         */
        private val loadError: Throwable? by lazy {
            runCatching {
                val library = System.getProperty("takes.onnxruntime.library")
                // Loaded by Java first on a phone, so that a missing library says so plainly.
                if (library == null) System.loadLibrary("onnxruntime")
                System.loadLibrary("deepfilter_jni")
                open(library)?.let { throw UnsatisfiedLinkError(it) }
            }.exceptionOrNull()
        }

        @JvmStatic private external fun open(path: String?): String?
        @JvmStatic private external fun create(
            graph: ByteArray, names: Array<String>, data: Array<ByteBuffer>, shapes: Array<LongArray>, types: IntArray,
            inputs: Array<String>, outputs: Array<String>,
        ): Long
        @JvmStatic private external fun execute(handle: Long, inputs: Array<FloatBuffer>, inputShapes: Array<LongArray>, outputs: Array<FloatBuffer>)
        @JvmStatic private external fun destroy(handle: Long)
    }
}
