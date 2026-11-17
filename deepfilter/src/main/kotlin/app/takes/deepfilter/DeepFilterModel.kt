package app.takes.deepfilter

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest

/** The model file is missing, damaged, or not the version this module was written for. */
class DeepFilterModelException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The DeepFilterNet3 network, loaded once and shared by every stream that uses it. Each
 * [DeepFilter] made from it has its own memory, so several channels can be cleaned with
 * one model, also at the same time. [close] it after the streams.
 *
 * The weights are the file [FILE_NAME] published at [SOURCE_URL], [SIZE] bytes: the app
 * carries them ([bundled]), or a caller supplies them. Any other file is refused, as
 * the iPhone app refuses a model that is not the one it was tested with.
 *
 * That file computes a whole recording in one go, which would need memory in proportion
 * to its length. So the network is run from `streaming-graph.onnx`, shipped inside this
 * module: the same layers and the same weights, read from the supplied file, with the
 * memory of the recurrent layers and of the convolutions passed in and out on every 10 ms
 * frame. It gives the same numbers as the published file.
 */
class DeepFilterModel(bytes: ByteArray) : AutoCloseable {
    /** Reads the model from [file]. */
    constructor(file: File) : this(read(file))

    /** Reads the model from [stream] to its end. The stream is not closed. */
    constructor(stream: InputStream) : this(read(stream))

    // Checked first, so a wrong file is refused before ONNX Runtime is even started.
    init {
        if (bytes.size != SIZE) throw DeepFilterModelException("The DeepFilterNet3 model must be $SIZE bytes, not ${bytes.size}")
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (hash != SHA256) throw DeepFilterModelException("The DeepFilterNet3 model is not the expected version")
    }

    private val session: OnnxSession
    private var closed = false

    init {
        // Resources are named by absolute path, which a minified build keeps when it renames this class.
        val graph = DeepFilterModel::class.java.getResourceAsStream("/app/takes/deepfilter/streaming-graph.onnx")?.use { it.readBytes() }
            ?: throw DeepFilterModelException("streaming-graph.onnx is missing from the app")
        val weights = externalTensors(graph).map { tensor ->
            if (tensor.offset < 0 || tensor.length < 0 || tensor.offset + tensor.length > bytes.size) {
                throw DeepFilterModelException("streaming-graph.onnx points outside the model")
            }
            if (tensor.type != 1 && tensor.type != 7) throw DeepFilterModelException("streaming-graph.onnx has weights of unknown type ${tensor.type}")
            // Direct, so ONNX Runtime reads the bytes where they are; it takes its own copy for the session.
            val data = ByteBuffer.allocateDirect(tensor.length.toInt()).order(ByteOrder.nativeOrder())
            data.put(bytes, tensor.offset.toInt(), tensor.length.toInt()).rewind()
            OnnxSession.Weight(tensor.name, tensor.type, tensor.dims, data)
        }
        // The original pins the processor so the result does not depend on the phone's
        // accelerators. One 10 ms frame is too small for threads to help: the session runs
        // on one thread (OnnxSession), with every optimization of the graph.
        session = try {
            OnnxSession(graph, weights, INPUTS, OUTPUTS)
        } catch (error: Exception) {
            throw DeepFilterModelException("DeepFilterNet3 could not be loaded", error)
        } catch (error: LinkageError) {
            // ONNX Runtime is missing, as on 32-bit x86, for which the app carries none.
            throw DeepFilterModelException("DeepFilterNet3 could not be loaded", error)
        }
    }

    internal fun newNetwork(): Network {
        synchronized(this) { check(!closed) { "DeepFilterModel is closed" } }
        return OnnxNetwork()
    }

    /** Frees the network. Close the streams made from it first. */
    @Synchronized
    override fun close() {
        if (!closed) session.close()
        closed = true
    }

    /** The memory of one stream, handed to the shared session and taken back on every frame. */
    private inner class OnnxNetwork : Network {
        private val erbBuffer = floats(DeepFilter.ERB_BANDS)
        private val specBuffer = floats(2 * DeepFilter.DF_BINS)
        private val maskBuffer = floats(DeepFilter.ERB_BANDS)
        private val coefsBuffer = floats(DeepFilter.DF_ORDER * DeepFilter.DF_BINS * 2)
        // What the network remembers goes in from one set and comes back into the other,
        // which then goes in on the next frame.
        private var states = Array(STATES.size) { floats(STATES[it].size) }
        private var nextStates = Array(STATES.size) { floats(STATES[it].size) }

        override fun run(erb: FloatArray, spec: FloatArray, mask: FloatArray, coefs: FloatArray) {
            erbBuffer.clear(); erbBuffer.put(erb).rewind()
            specBuffer.clear(); specBuffer.put(spec).rewind()
            session.run(arrayOf(erbBuffer, specBuffer, *states), INPUT_SHAPES, arrayOf(maskBuffer, coefsBuffer, *nextStates))
            maskBuffer.rewind(); maskBuffer.get(mask)
            coefsBuffer.rewind(); coefsBuffer.get(coefs)
            states = nextStates.also { nextStates = states }
        }

        override fun reset() {
            for (state in states) {
                state.clear()
                while (state.hasRemaining()) state.put(0f)
                state.rewind()
            }
        }

        override fun close() {}
    }

    private class State(val name: String, vararg val shape: Long) {
        val size = shape.fold(1L) { size, dim -> size * dim }.toInt()
    }

    companion object {
        /** The name the weights are published under. */
        const val FILE_NAME = "deepfilter.onnx"
        /** Where the weights are published: the DeepFilterNet3 v0.5.6 network as an ONNX file, MIT license. */
        const val SOURCE_URL = "https://huggingface.co/soniqo/DeepFilterNet3-ONNX/resolve/63d8ba442ba900143c468b798e94a04009b2f0c9/deepfilter.onnx"
        const val SIZE = 8_608_859
        const val SHA256 = "e1157049059434ae0d5857e32c812abea227b975e946b2eb64d001abbce156d3"

        /**
         * The model the app carries: the weights packed beside this class by the build
         * (`bundleModel` in this module's build file). Null when this build has none.
         */
        fun bundled(): DeepFilterModel? =
            DeepFilterModel::class.java.getResourceAsStream("/app/takes/deepfilter/$FILE_NAME")?.use { DeepFilterModel(it) }

        private val ERB_SHAPE = longArrayOf(1, 1, 1, DeepFilter.ERB_BANDS.toLong())
        private val SPEC_SHAPE = longArrayOf(1, 2, 1, DeepFilter.DF_BINS.toLong())

        // What the network remembers between frames: five recurrent layers and the last
        // frames three convolutions still need.
        private val STATES = listOf(
            State("h0", 1, 1, 256), State("h1", 1, 1, 256), State("h2", 1, 1, 256), State("h3", 1, 1, 256), State("h4", 1, 1, 256),
            State("erb_cache", 1, 1, 2, 32), State("spec_cache", 1, 2, 2, 96), State("df_cache", 1, 64, 4, 96),
        )
        private val INPUTS = listOf("feat_erb", "feat_spec") + STATES.map { it.name }
        private val OUTPUTS = listOf("erb_mask", "df_coefs") + STATES.map { it.name + "_out" }
        private val INPUT_SHAPES = arrayOf(ERB_SHAPE, SPEC_SHAPE) + STATES.map { it.shape }

        private fun floats(count: Int): FloatBuffer =
            ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        private fun read(file: File): ByteArray {
            if (!file.isFile) throw DeepFilterModelException("The DeepFilterNet3 model is missing: $file")
            // Checked before reading, so a wrong file of any size is never loaded into memory.
            if (file.length() != SIZE.toLong()) throw DeepFilterModelException("The DeepFilterNet3 model must be $SIZE bytes, not ${file.length()}")
            return file.readBytes()
        }

        private fun read(stream: InputStream): ByteArray {
            // One byte more than the model, to notice a longer file without reading all of it.
            val bytes = ByteArray(SIZE + 1)
            var count = 0
            while (count < bytes.size) {
                val read = stream.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
            if (count != SIZE) throw DeepFilterModelException("The DeepFilterNet3 model must be $SIZE bytes")
            return bytes.copyOf(SIZE)
        }
    }
}

/** A tensor of the graph whose values are kept in another file. */
internal class ExternalTensor(val name: String, val type: Int, val dims: LongArray, val offset: Long, val length: Long)

/**
 * Lists the weights an ONNX file leaves outside itself: for each, where in the other file
 * its bytes are. Reads just enough of the format (protocol buffers) for that.
 */
internal fun externalTensors(model: ByteArray): List<ExternalTensor> {
    val found = ArrayList<ExternalTensor>()
    // ModelProto.graph = 7, GraphProto.initializer = 5.
    fields(model, 0, model.size) { field, _, start, end ->
        if (field == 7) fields(model, start, end) { inner, _, from, to ->
            if (inner == 5) tensor(model, from, to)?.let { found += it }
        }
    }
    return found
}

private fun tensor(data: ByteArray, start: Int, end: Int): ExternalTensor? {
    val dims = ArrayList<Long>()
    var type = 0
    var name = ""
    var external = false
    var offset = 0L
    var length = -1L
    fields(data, start, end) { field, value, from, to ->
        when (field) {
            // dims: one number per field, or all of them packed into one.
            1 -> if (from < 0) dims += value else {
                var position = from
                while (position < to) {
                    val (number, next) = varint(data, position, to)
                    dims += number
                    position = next
                }
            }
            2 -> type = value.toInt()
            8 -> name = String(data, from, to - from, Charsets.UTF_8)
            // external_data: key and value strings.
            13 -> {
                var key = ""
                var text = ""
                fields(data, from, to) { part, _, first, last ->
                    if (part == 1) key = String(data, first, last - first, Charsets.UTF_8)
                    if (part == 2) text = String(data, first, last - first, Charsets.UTF_8)
                }
                if (key == "offset") offset = text.toLong()
                if (key == "length") length = text.toLong()
            }
            14 -> external = value == 1L
        }
    }
    return if (external) ExternalTensor(name, type, dims.toLongArray(), offset, length) else null
}

/**
 * Walks the fields of one protocol-buffers message. [visit] gets the field number and, for
 * a plain number, its value with −1 as the range; for bytes, the range they occupy.
 */
private fun fields(data: ByteArray, start: Int, end: Int, visit: (field: Int, value: Long, from: Int, to: Int) -> Unit) {
    var position = start
    while (position < end) {
        val (tag, afterTag) = varint(data, position, end)
        position = afterTag
        val field = (tag ushr 3).toInt()
        when ((tag and 7).toInt()) {
            0 -> {
                val (value, next) = varint(data, position, end)
                position = next
                visit(field, value, -1, -1)
            }
            1 -> position += 8
            5 -> position += 4
            2 -> {
                val (size, next) = varint(data, position, end)
                require(size >= 0 && next + size <= end) { "damaged ONNX file" }
                visit(field, 0, next, next + size.toInt())
                position = next + size.toInt()
            }
            else -> throw IllegalArgumentException("damaged ONNX file")
        }
    }
    require(position == end) { "damaged ONNX file" }
}

private fun varint(data: ByteArray, start: Int, end: Int): Pair<Long, Int> {
    var value = 0L
    var position = start
    var shift = 0
    while (shift < 70) {
        require(position < end) { "damaged ONNX file" }
        val byte = data[position++].toInt()
        value = value or ((byte and 127).toLong() shl shift)
        if (byte >= 0) return value to position
        shift += 7
    }
    throw IllegalArgumentException("damaged ONNX file")
}
