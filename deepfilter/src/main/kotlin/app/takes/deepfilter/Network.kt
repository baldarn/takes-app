package app.takes.deepfilter

/**
 * The neural part of DeepFilterNet3 for one stream: one frame of features in, the gains
 * and filter for the frame two before it out. It remembers what it has seen, so each
 * stream has its own and feeds it the frames in order.
 */
internal interface Network : AutoCloseable {
    /**
     * [erb] is 32 band levels and [spec] 96 real then 96 imaginary parts, both normalized.
     * Fills [mask] with 32 band gains and [coefs] with 5 taps × 96 bins × (real, imaginary).
     */
    fun run(erb: FloatArray, spec: FloatArray, mask: FloatArray, coefs: FloatArray)

    /** Forgets everything, as at the start of a recording. */
    fun reset()
}
