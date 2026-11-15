package app.takes.core

import java.nio.ShortBuffer

/**
 * Interleaved 16-bit sound of any rate and channel count as one channel at
 * 16 kHz, the form the recognizer takes. Each output sample is the average of
 * the input it covers, which keeps the pitches above 8 kHz from folding into
 * the speech when the rate goes down.
 */
class SubtitleSpeechAudio(private val channels: Int, rate: Int) {
    private val step = rate.toDouble() / RATE // input frames in one output sample
    private var filled = 0.0
    private var sum = 0.0

    /** The samples produced by the latest [read]. */
    var samples = FloatArray(0); private set

    /** Mixes and resamples a block. Returns how many of [samples] are valid. */
    fun read(block: ShortBuffer): Int {
        val frames = block.remaining() / channels
        val start = block.position()
        val room = (frames / step).toInt() + 2
        if (samples.size < room) samples = FloatArray(room)
        var written = 0
        for (frame in 0 until frames) {
            var mixed = 0f
            for (channel in 0 until channels) mixed += block.get(start + frame * channels + channel)
            val value = mixed / channels / 32768.0
            var left = 1.0
            while (filled + left >= step) {
                val part = step - filled
                samples[written++] = ((sum + value * part) / step).toFloat()
                sum = 0.0
                filled = 0.0
                left -= part
            }
            sum += value * left
            filled += left
        }
        return written
    }

    companion object {
        const val RATE = 16_000

        private val NOTE = Regex("\\[[^\\]]*]")
        private val SPACES = Regex("\\s+")

        /**
         * What was said, without the notes a recognizer writes for sounds that
         * are not speech, such as "[BLANK_AUDIO]", "[Music]" or "(applause)".
         * Words in round brackets inside a sentence are kept: people say those.
         */
        fun spoken(text: String): String {
            val plain = text.replace(NOTE, " ").replace(SPACES, " ").trim()
            return if (plain.startsWith("(") && plain.endsWith(")") && plain.indexOf(')') == plain.lastIndex) "" else plain
        }
    }
}
