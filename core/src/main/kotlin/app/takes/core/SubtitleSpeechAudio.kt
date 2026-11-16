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
            if (plain.startsWith("(") && plain.endsWith(")") && plain.indexOf(')') == plain.lastIndex) return ""
            return if (invented(plain)) "" else plain
        }

        /**
         * Whether [text] is what Whisper writes for music or noise rather than speech: the whole of it one of the
         * stock phrases it learned from subtitled videos ([STOCK]), or one phrase of two words or more said three
         * times or more, as sentences or in a row (a single word only [SAID_ALONE] times: "no, no, no" is speech). This
         * goes beyond the original, which uses its recognizer's text as it is (`O/SubtitleTranscriber.swift:69-77`):
         * docs/SUBTITLES-REVIEW.md, finding 10. A sentence that merely contains such a phrase is kept.
         */
        fun invented(text: String): Boolean {
            val whole = normal(text)
            if (whole.isEmpty()) return false
            if (whole in STOCK) return true
            fun looped(phrase: String, times: Int) = times >= if (' ' in phrase) 3 else SAID_ALONE
            val sentences = text.split(SENTENCE).map(::normal).filter { it.isNotEmpty() }
            if (sentences.groupingBy { it }.eachCount().any { (phrase, times) -> looped(phrase, times) }) return true
            val loop = REPEATED.matchEntire(whole) ?: return false
            val phrase = loop.groupValues[1]
            return looped(phrase, (whole.length + 1) / (phrase.length + 1))
        }

        /** Lower case, the punctuation and the spaces around it gone, so "Thanks for watching!" is "thanks for watching". */
        private fun normal(text: String): String =
            text.lowercase().replace(PUNCTUATION, " ").replace(SPACES, " ").trim()

        private val SENTENCE = Regex("[.!?…。]+")
        private val PUNCTUATION = Regex("[\\p{P}\\p{S}&&[^']]")
        private val REPEATED = Regex("^(.+?)(?: \\1){2,}$")
        private const val SAID_ALONE = 6

        /**
         * The stock phrases, normalised, that Whisper is known to write over music and noise: the credits and
         * closings of the subtitled videos it was trained on. Only phrases nobody says as the whole of a stretch of
         * speech in a recording of their own: a bare "thank you" or "grazie a tutti" can be meant, so it is kept.
         */
        private val STOCK = setOf(
            "thanks for watching", "thank you for watching", "thank you so much for watching",
            "thanks for watching and see you next time", "please subscribe", "please like and subscribe",
            "don't forget to like and subscribe", "subtitles by the amara org community", "you",
            "sottotitoli creati dalla comunità amara org", "sottotitoli e revisione a cura di qtss",
            "sottotitoli a cura di qtss", "grazie per la visione", "iscriviti al canale",
            "sous titres réalisés par la communauté d'amara org", "merci d'avoir regardé cette vidéo",
            "subtítulos realizados por la comunidad de amara org", "gracias por ver el video", "suscríbete al canal",
            "untertitel der amara org community", "untertitel im auftrag des zdf für funk 2017",
            "untertitel im auftrag des zdf 2017", "untertitel von stephanie geiges", "vielen dank fürs zuschauen",
            "legendas pela comunidade amara org", "obrigado por assistir",
            "ondertiteling door de amara org gemeenschap", "bedankt voor het kijken",
        )
    }
}
