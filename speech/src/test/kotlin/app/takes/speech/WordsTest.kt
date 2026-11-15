package app.takes.speech

import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** The arithmetic around the recognizer: no model and no native code. */
class WordsTest {
    private fun result(text: String, tokens: List<String>, starts: List<Float>) = OfflineRecognizerResult(
        text = text, tokens = tokens.toTypedArray(), timestamps = starts.toFloatArray(),
        lang = "en", emotion = "", event = "", durations = FloatArray(0),
    )

    private fun tone(seconds: Double, level: Float = 0.3f) = FloatArray((seconds * 16000).toInt()) { level * sin(2 * PI * 220 * it / 16000).toFloat() }
    private fun silence(seconds: Double) = FloatArray((seconds * 16000).toInt())

    @Test fun tokensBecomeWordsWithTheirPunctuation() {
        val words = SpeechRecognizer.words(
            result(" After nightfall, the lamps.", listOf(" After", " night", "fall", ",", " the", " lamps", "."), listOf(0f, 0.6f, 0.9f, 1.2f, 1.5f, 1.7f, 2.2f)),
            offset = 0.0, limit = 2.5,
        )!!
        assertEquals(listOf("After", "nightfall,", "the", "lamps."), words.map { it.text })
        assertEquals(listOf(0.0, 0.6, 1.5, 1.7), words.map { Math.round(it.start * 100) / 100.0 })
        // A word lasts until the next one starts, and the last one until the audio ends.
        assertEquals(listOf(0.6, 1.5, 1.7, 2.5), words.map { Math.round(it.end * 100) / 100.0 })
    }

    @Test fun timesAreMovedByTheOffsetAndKeptInOrderAndInside() {
        val words = SpeechRecognizer.words(result("a b c d", listOf(" a", " b", " c", " d"), listOf(0.5f, 0.2f, 3.0f, 9.0f)), offset = 10.0, limit = 14.0)!!
        assertEquals(listOf(10.5, 10.5, 13.0, 14.0), words.map { it.start })
        for (word in words) assertTrue(word.end >= word.start && word.end <= 14.0)
        for (i in 1 until words.size) assertTrue(words[i].start >= words[i - 1].end)
    }

    @Test fun noTokenTimesMeansNoWordTimes() {
        assertNull(SpeechRecognizer.words(result("a b", listOf(" a", " b"), emptyList()), 0.0, 1.0))
        assertNull(SpeechRecognizer.words(result("", emptyList(), emptyList()), 0.0, 1.0))
    }

    @Test fun theTextDecidesTheSpelling() {
        // "è" split over two tokens arrives broken in the tokens and whole in the text.
        val words = SpeechRecognizer.words(result(" perché no", listOf(" perch", "�", "�", " no"), listOf(0f, 0.3f, 0.35f, 0.5f)), 0.0, 1.0)!!
        assertEquals(listOf("perché", "no"), words.map { it.text })
    }

    @Test fun writingWithoutSpacesGivesOneWordForEachToken() {
        val words = SpeechRecognizer.words(result("今天天气很好。", listOf("今天", "天气", "很", "好", "。"), listOf(0f, 0.4f, 0.8f, 1.0f, 1.3f)), 0.0, 1.5)!!
        assertEquals(listOf("今天", "天气", "很", "好。"), words.map { it.text })
    }

    @Test fun silenceIsTakenOutOfTheWords() {
        // 0.5 s of nothing, a word, a pause of 1 s, a word, nothing.
        val samples = silence(0.5) + tone(0.5) + silence(1.0) + tone(0.4) + silence(0.6)
        val raw = listOf(Word("one", 0.0, 1.6), Word("two", 1.6, 3.0))
        val words = SpeechRecognizer.tightened(raw, samples, 16000)
        assertEquals(listOf("one", "two"), words.map { it.text })
        assertEquals(0.5, words[0].start, 0.05)
        assertEquals(1.0, words[0].end, 0.1)
        assertEquals(2.0, words[1].start, 0.05)
        assertEquals(2.4, words[1].end, 0.1)
    }

    @Test fun aWordThatSwallowedThePauseBeforeItStartsAfterThePause() {
        // "one" is spoken from 0.2 to 0.85 s and "two" from 1.25 to 1.55 s, but the alignment
        // put the border at 0.7 s: "two" holds the end of "one", the pause, and itself.
        val samples = silence(0.2) + tone(0.65) + silence(0.4) + tone(0.3) + silence(0.45)
        val words = SpeechRecognizer.tightened(listOf(Word("one", 0.0, 0.7), Word("two", 0.7, 2.0)), samples, 16000)
        assertEquals(0.2, words[0].start, 0.05)
        assertEquals(0.85, words[0].end, 0.1)
        assertEquals(1.25, words[1].start, 0.06)
        assertEquals(1.55, words[1].end, 0.1)

        // A whole word before the pause is not a leftover: nothing is moved across.
        val long = silence(0.2) + tone(1.0) + silence(0.4) + tone(0.3)
        val kept = SpeechRecognizer.tightened(listOf(Word("one", 0.0, 0.4), Word("two", 0.4, 1.9)), long, 16000)
        assertEquals(0.4, kept[0].end, 1e-9)
        assertEquals(0.4, kept[1].start, 1e-9)
    }

    @Test fun aWordWithoutSoundKeepsItsTimes() {
        val samples = silence(1.0) + tone(1.0)
        val raw = listOf(Word("quiet", 0.2, 0.8), Word("loud", 1.0, 2.0))
        assertEquals(raw[0], SpeechRecognizer.tightened(raw, samples, 16000)[0])
        assertEquals(emptyList<Word>(), SpeechRecognizer.tightened(emptyList(), samples, 16000))
    }

    @Test fun shortAudioIsOneWindow() {
        assertEquals(listOf(0 to 16000 * 24), SpeechRecognizer.windows(tone(24.0), 16000))
        assertEquals(listOf(0 to 1), SpeechRecognizer.windows(FloatArray(1), 16000))
    }

    @Test fun longAudioIsCutInThePause() {
        // 60 s of sound with 200 ms pauses. Only the last third of a window is searched, so
        // the first window (0–24 s) can use the pause at 20 s, not the one at 10 s.
        val pauses = listOf(10.0, 20.0, 30.0, 41.0, 55.0)
        val samples = tone(60.0)
        for (pause in pauses) samples.fill(0f, (pause * 16000).toInt(), ((pause + 0.2) * 16000).toInt())
        val windows = SpeechRecognizer.windows(samples, 16000)
        // Windows follow each other without gap or overlap and none is too long.
        assertEquals(0, windows.first().first)
        assertEquals(samples.size, windows.last().second)
        for (i in 1 until windows.size) assertEquals(windows[i - 1].second, windows[i].first)
        for ((from, to) in windows) assertTrue((to - from) / 16000.0 <= SpeechRecognizer.MAX_WINDOW_SECONDS)
        val cuts = windows.dropLast(1).map { it.second / 16000.0 }
        assertEquals(2, cuts.size)
        assertEquals(20.1, cuts[0], 0.06)
        assertEquals(41.1, cuts[1], 0.06)
    }

    @Test fun anotherSampleRateIsCutByTimeToo() {
        val windows = SpeechRecognizer.windows(FloatArray(48000 * 50), 48000)
        for ((from, to) in windows) assertTrue((to - from) / 48000.0 <= SpeechRecognizer.MAX_WINDOW_SECONDS)
        assertEquals(48000 * 50, windows.last().second)
    }
}
