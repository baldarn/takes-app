package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class SubtitleSpeechAudioTest {
    private fun convert(rate: Int, channels: Int, frames: Int, blocks: Int = 1, sample: (frame: Int, channel: Int) -> Double): FloatArray {
        val audio = SubtitleSpeechAudio(channels, rate)
        val result = mutableListOf<Float>()
        val each = frames / blocks
        for (block in 0 until blocks) {
            val from = block * each
            val to = if (block == blocks - 1) frames else from + each
            val data = ShortArray((to - from) * channels) { (sample(from + it / channels, it % channels) * 32767).toInt().toShort() }
            val count = audio.read(ShortBuffer.wrap(data))
            result += audio.samples.take(count)
        }
        return result.toFloatArray()
    }

    private fun rms(samples: FloatArray) = sqrt(samples.sumOf { (it * it).toDouble() } / samples.size)

    @Test fun oneSecondBecomesSixteenThousandSamples() {
        for (rate in listOf(8_000, 16_000, 22_050, 44_100, 48_000, 96_000)) {
            val out = convert(rate, 1, rate) { _, _ -> 0.25 }
            assertTrue("$rate Hz gave ${out.size}", abs(out.size - 16_000) <= 1)
            assertTrue(out.all { abs(it - 0.25f) < 0.001f })
        }
    }

    @Test fun theSameSoundInAnyNumberOfBlocks() {
        val whole = convert(44_100, 2, 44_100) { frame, _ -> sin(2 * PI * 300 * frame / 44_100) * 0.5 }
        val pieces = convert(44_100, 2, 44_100, blocks = 37) { frame, _ -> sin(2 * PI * 300 * frame / 44_100) * 0.5 }
        assertEquals(whole.size, pieces.size)
        for (index in whole.indices) assertEquals(whole[index], pieces[index], 1e-4f)
    }

    @Test fun channelsAreAveraged() {
        // Opposite channels cancel; one silent channel halves the other.
        assertTrue(convert(48_000, 2, 4_800) { frame, channel -> (if (channel == 0) 1 else -1) * sin(2 * PI * 440 * frame / 48_000) * 0.5 }.all { abs(it) < 0.001f })
        val half = convert(16_000, 2, 1_600) { _, channel -> if (channel == 0) 0.5 else 0.0 }
        assertTrue(half.all { abs(it - 0.25f) < 0.001f })
    }

    @Test fun aVoicePitchKeepsItsLevelAndAHissIsReduced() {
        val voice = convert(48_000, 1, 48_000) { frame, _ -> sin(2 * PI * 300 * frame / 48_000) * 0.5 }
        assertEquals(0.5 / sqrt(2.0), rms(voice), 0.01)
        // 15 kHz cannot be written at 16 kHz; averaging takes most of it away instead of turning it into a 1 kHz tone.
        val hiss = convert(48_000, 1, 48_000) { frame, _ -> sin(2 * PI * 15_000 * frame / 48_000) * 0.5 }
        assertTrue("left ${rms(hiss)}", rms(hiss) < 0.5 / sqrt(2.0) / 2.5)
    }

    @Test fun notesForSoundsThatAreNotSpeechAreLeftOut() {
        assertEquals("", SubtitleSpeechAudio.spoken(" [BLANK_AUDIO] "))
        assertEquals("", SubtitleSpeechAudio.spoken("(applause)"))
        assertEquals("", SubtitleSpeechAudio.spoken("[Music] (laughing)"))
        assertEquals("Hello there.", SubtitleSpeechAudio.spoken(" Hello [Music]  there. "))
        assertEquals("He said (twice) no.", SubtitleSpeechAudio.spoken("He said (twice) no."))
        assertEquals("(one) and (two)", SubtitleSpeechAudio.spoken("(one) and (two)"))
        assertEquals("", SubtitleSpeechAudio.spoken("   "))
    }

    // Beyond the original, which uses the recognizer's text as it is (O/SubtitleTranscriber.swift:69-77):
    // docs/SUBTITLES-REVIEW.md, finding 10.
    @Test fun aPieceThatIsOneOfWhispersStockPhrasesGivesNoWords() {
        assertEquals("", SubtitleSpeechAudio.spoken("Thanks for watching!"))
        assertEquals("", SubtitleSpeechAudio.spoken(" Sottotitoli creati dalla comunità Amara.org "))
        assertEquals("", SubtitleSpeechAudio.spoken("Sous-titres réalisés par la communauté d'Amara.org"))
        assertEquals("", SubtitleSpeechAudio.spoken("Untertitel im Auftrag des ZDF für funk, 2017"))
        assertEquals("", SubtitleSpeechAudio.spoken("[Music] Thank you for watching. [Music]"))
    }

    @Test fun aSentenceThatOnlyContainsAStockPhraseIsKept() {
        assertEquals("Thanks for watching my video about bread.", SubtitleSpeechAudio.spoken("Thanks for watching my video about bread."))
        assertEquals("Ho letto: sottotitoli creati dalla comunità Amara.org, ecco.", SubtitleSpeechAudio.spoken("Ho letto: sottotitoli creati dalla comunità Amara.org, ecco."))
        assertEquals("Thank you.", SubtitleSpeechAudio.spoken("Thank you."))
        assertEquals("Grazie a tutti!", SubtitleSpeechAudio.spoken("Grazie a tutti!"))
    }

    @Test fun aPhraseSaidThreeTimesOrMoreGivesNoWords() {
        assertEquals("", SubtitleSpeechAudio.spoken("I'm going to go. I'm going to go. I'm going to go."))
        assertEquals("", SubtitleSpeechAudio.spoken("e poi e poi e poi e poi"))
        assertEquals("", SubtitleSpeechAudio.spoken("Okay. Okay. Okay. Okay. Okay. Okay."))
        assertEquals("", SubtitleSpeechAudio.spoken("Va bene, va bene, va bene."))
    }

    @Test fun aPhraseSaidTwiceOrAShortWordSaidAFewTimesIsSpeech() {
        assertEquals("No, no, no.", SubtitleSpeechAudio.spoken("No, no, no."))
        assertEquals("Wait. Wait. Wait.", SubtitleSpeechAudio.spoken("Wait. Wait. Wait."))
        assertEquals("Very good, very good.", SubtitleSpeechAudio.spoken("Very good, very good."))
        assertEquals("Go, go and go back home.", SubtitleSpeechAudio.spoken("Go, go and go back home."))
    }
}
