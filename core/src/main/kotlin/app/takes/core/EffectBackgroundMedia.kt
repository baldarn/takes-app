package app.takes.core

import kotlin.math.floor

/**
 * A Chroma Key's or an Auto Background's movie behind the subject, played
 * from the effect's start and looped over its own length: the last
 * [overlap] seconds dissolve into the first, two frames on one clock.
 * YouRec/VideoEffects/VideoEffectBackgroundAudio.swift:7-15.
 */
data class EffectBackgroundLoop(val duration: Double) {
    val overlap: Double get() = minOf(0.2, duration / 2)
    val period: Double get() = duration - overlap

    /** The movie's second shown, the one dissolved from during the overlap (null outside it), and how far the dissolve is. */
    data class Phase(val incoming: Double, val outgoing: Double?, val blend: Double)

    fun phase(time: Double): Phase {
        val t = maxOf(0.0, time)
        val cycle = floor(t / period)
        val phase = t - cycle * period
        return Phase(phase, if (cycle > 0 && phase < overlap) period + phase else null, minOf(1.0, phase / overlap))
    }

    companion object {
        /** The movie's clock at source second [time] of [effect]: from its start. VideoEffectAssets.swift:596. */
        fun time(effect: VideoEffectSettings, time: Double): Double = maxOf(0.0, time - effect.start)

        /** A dissolve of two encoded values, [from] to [to] by [blend], in linear light as Core Image's: CIDissolveTransition. */
        fun dissolve(from: Double, to: Double, blend: Double): Double =
            EffectColorMath.encoded(EffectColorMath.linear(from) * (1 - blend) + EffectColorMath.linear(to) * blend)
    }
}

/**
 * The recorder's capture of a Chroma Key's clean plate: one second of the
 * empty screen averaged as it streams, in linear light, without keeping the
 * frames. A gap of more than a quarter of a second, time going back, another
 * shape of frame or another dynamic range ends it. Done after a second and at
 * least eight frames. YouRec/VideoEffects/VideoEffectCleanPlate.swift:8-77.
 */
class CleanPlateAverage {
    enum class Failure(val message: String) {
        Orientation("The camera orientation changed. Capture the empty screen again."),
        Interrupted("Capture was interrupted. Capture the empty screen again."),
        Format("The camera format changed. Capture the empty screen again."),
    }

    class Failed(val failure: Failure) : Exception(failure.message)

    private var sum: FloatArray? = null
    private var firstTime: Double? = null
    private var lastTime: Double? = null
    private var hdr: Boolean? = null
    var width = 0; private set
    var height = 0; private set
    var count = 0; private set
    var progress = 0.0; private set

    /**
     * Adds a frame of [width] × [height] taken at [time] seconds: [rgba], four sRGB-encoded bytes a pixel. True once
     * the plate is complete. Throws [Failed] when the capture cannot go on.
     */
    fun append(rgba: ByteArray, width: Int, height: Int, time: Double, hdr: Boolean = false): Boolean {
        lastTime?.let { if (time - it > 0.25 || time < it) throw Failed(Failure.Interrupted) }
        this.hdr?.let { if (it != hdr) throw Failed(Failure.Format) }
        this.hdr = hdr
        lastTime = time
        if (sum == null) {
            sum = FloatArray(width * height * 3)
            this.width = width
            this.height = height
            firstTime = time
        }
        if (width != this.width || height != this.height) throw Failed(Failure.Orientation)
        count++
        // The running mean, each frame weighted 1/count: Core Image's dissolve to the new frame by 1/count.
        val mean = sum!!
        val weight = 1f / count
        for (i in 0 until width * height) {
            for (c in 0..2) {
                val value = LINEAR[rgba[i * 4 + c].toInt() and 0xff]
                mean[i * 3 + c] += (value - mean[i * 3 + c]) * weight
            }
        }
        progress = (time - (firstTime ?: time)).coerceIn(0.0, 1.0)
        return progress >= 1 && count >= 8
    }

    /** The plate, four sRGB-encoded bytes a pixel, rows as given; null before a frame. */
    fun encoded(): ByteArray? {
        val mean = sum ?: return null
        val out = ByteArray(width * height * 4)
        for (i in 0 until width * height) {
            for (c in 0..2) out[i * 4 + c] = (EffectColorMath.encoded(mean[i * 3 + c].toDouble()).coerceIn(0.0, 1.0) * 255 + .5).toInt().toByte()
            out[i * 4 + 3] = -1
        }
        return out
    }

    companion object {
        private val LINEAR = FloatArray(256) { EffectColorMath.linear(it / 255.0).toFloat() }
    }
}
