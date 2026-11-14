package app.takes.rnnoise

/**
 * Reduces background noise in a whole recording, in place. [samples] is one channel at
 * 48 kHz in −1…1. [amount] mixes the result with the original: 0 leaves the audio
 * untouched, 1 is fully cleaned. The length and timing of the audio do not change.
 *
 * Does what `OfflineAudioPipeline.denoise` does in the iPhone app (YouRec/
 * OfflineAudioPipeline.swift): 480-sample frames, the last one padded with silence, the
 * network's one-frame delay removed, and every cleaned sample mixed with the original
 * sample of the same instant. For stereo, call it once per channel, as the iPhone app
 * gives each channel its own state.
 *
 * [progress] is told how much is done, 0–1, after every frame.
 */
fun denoise(samples: FloatArray, amount: Float, progress: (Float) -> Unit = {}) {
    require(amount in 0f..1f) { "amount must be 0–1, was $amount" }
    // The iPhone app skips the effect at zero, so the audio stays bit for bit the same.
    if (amount == 0f || samples.isEmpty()) return progress(1f)
    val frame = RNNoise.FRAME_SIZE
    val input = FloatArray(frame)
    val wet = FloatArray(frame)
    RNNoise().use { rnnoise ->
        // What comes out of each call belongs to the frame before it. So the cleaned
        // audio is written one frame behind the read position, over samples that have
        // already been read, and one extra silent frame pushes out the end.
        var start = 0
        while (start - frame < samples.size) {
            val count = (samples.size - start).coerceIn(0, frame)
            if (count > 0) samples.copyInto(input, 0, start, start + count)
            input.fill(0f, count, frame)
            rnnoise.process(input, 0, wet, 0)
            val from = start - frame
            for (index in maxOf(from, 0) until minOf(start, samples.size)) {
                val dry = samples[index]
                samples[index] = dry + amount * (wet[index - from] - dry)
            }
            start += frame
            progress(minOf(start, samples.size).toFloat() / samples.size)
        }
    }
}
