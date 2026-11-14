package app.takes.rnnoise

/**
 * One RNNoise noise-reduction state: the C library and network from the iPhone app.
 *
 * Audio is one channel at 48 kHz, in the usual float range of −1…1, 480 samples (10 ms)
 * per call. Each instance remembers what it heard before, so use one per channel and per
 * recording, feed it the frames in order, and [close] it to free the native memory.
 *
 * The output is 480 samples late: the frame returned by a call is the cleaned version of
 * the frame passed to the call before it. [denoise] hides that delay.
 */
class RNNoise : AutoCloseable {
    private var state = create()

    init { if (state == 0L) throw OutOfMemoryError("RNNoise state") }

    /**
     * Cleans 480 samples of [input] starting at [inputOffset] into [output] at
     * [outputOffset]; the two may be the same array and range. Returns how likely the
     * input frame is to contain speech, 0–1.
     */
    // Synchronized so that closing from another thread cannot free the state mid-frame.
    @Synchronized
    fun process(input: FloatArray, inputOffset: Int = 0, output: FloatArray = input, outputOffset: Int = inputOffset): Float {
        check(state != 0L) { "RNNoise is closed" }
        require(inputOffset >= 0 && inputOffset <= input.size - FRAME_SIZE) { "input needs $FRAME_SIZE samples from $inputOffset" }
        require(outputOffset >= 0 && outputOffset <= output.size - FRAME_SIZE) { "output needs $FRAME_SIZE samples from $outputOffset" }
        return process(state, input, inputOffset, output, outputOffset)
    }

    @Synchronized
    override fun close() {
        if (state != 0L) destroy(state)
        state = 0
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAME_SIZE = 480

        init { System.loadLibrary("rnnoise_jni") }

        @JvmStatic private external fun create(): Long
        @JvmStatic private external fun destroy(state: Long)
        @JvmStatic private external fun process(state: Long, input: FloatArray, inputOffset: Int, output: FloatArray, outputOffset: Int): Float
    }
}
