package app.takes.deepfilter

/**
 * Reduces background noise in a whole recording with DeepFilterNet3, in place. [samples]
 * is one channel at 48 kHz in −1…1. [amount] mixes the result with the original: 0 leaves
 * the audio untouched, 1 is fully cleaned. The length and timing of the audio do not
 * change.
 *
 * Does what `DeepFilterVoiceProcessor.process` does in the iPhone app for each channel
 * (YouRec/DeepFilterVoiceProcessor.swift): 480-sample hops, the last one padded with
 * silence, the look-ahead drained at the end, and every cleaned sample mixed with the
 * original sample of the same instant. For stereo, call it once per channel with the same
 * [model], as the iPhone app gives each channel its own state.
 *
 * [progress] is told how much is done, 0–1, after every hop. Throwing from it stops the
 * work; the samples are then partly cleaned and should be discarded.
 */
fun deepFilter(model: DeepFilterModel, samples: FloatArray, amount: Float, progress: (Float) -> Unit = {}) {
    checkAmount(amount)
    // The iPhone app skips the effect at zero, so the audio stays bit for bit the same.
    if (amount == 0f || samples.isEmpty()) return progress(1f)
    DeepFilter(model).use { deepFilter(it, samples, amount, progress) }
}

internal fun deepFilter(stream: DeepFilter, samples: FloatArray, amount: Float, progress: (Float) -> Unit = {}) {
    checkAmount(amount)
    if (amount == 0f || samples.isEmpty()) return progress(1f)
    // Refused before anything is changed, since the audio is cleaned in place.
    require(samples.all { it.isFinite() }) { "DeepFilterNet3 received a sample that is not a number" }
    val hop = FloatArray(DeepFilter.HOP_SIZE)
    // The cleaned audio comes out later than it goes in. It is written behind the read
    // position, over samples that have already been read, so the original of each
    // instant is still there to be mixed with.
    var written = 0
    fun write(wet: FloatArray) {
        val count = minOf(wet.size, samples.size - written)
        for (index in 0 until count) {
            val value = mix(samples[written + index], wet[index], amount)
            check(value.isFinite()) { "DeepFilterNet3 produced a sample that is not a number" }
            samples[written + index] = value
        }
        written += count
    }
    var start = 0
    while (start < samples.size) {
        val count = minOf(samples.size - start, hop.size)
        samples.copyInto(hop, 0, start, start + count)
        hop.fill(0f, count, hop.size)
        start += count
        write(stream.processHop(hop))
        progress(start.toFloat() / samples.size)
    }
    write(stream.flush())
    check(written == samples.size) { "DeepFilterNet3 returned $written of ${samples.size} samples" }
}

internal fun checkAmount(amount: Float) = require(amount in 0f..1f) { "amount must be 0–1, was $amount" }

/** The iPhone app's mix: at 1 the cleaned sample itself, otherwise a blend with the original. */
internal fun mix(dry: Float, wet: Float, amount: Float) = if (amount == 1f) wet else dry + amount * (wet - dry)
