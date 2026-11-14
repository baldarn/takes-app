package app.takes.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlesTest {
    private val speech = listOf(SourceInterval(0.2, 2.0), SourceInterval(4.0, 7.0), SourceInterval(9.0, 11.0))
    private val words = SubtitleTiming.estimate("Here is the first sentence. Then another sentence arrives. Finally we finish.", speech)

    private fun ordered(words: List<SubtitleWord>) = words.zipWithNext().all { (a, b) -> a.end <= b.start + 0.000001 }
    private fun phrases(words: List<SubtitleWord>) = words.groupBy { it.phraseID }.values

    // Translated from the original app's SubtitleTests.swift.

    @Test fun wordEstimatesNeverBridgeASilence() {
        assertTrue(words.isNotEmpty())
        assertTrue(words.all { word -> speech.any { word.start >= it.start && word.end <= it.end } })
    }

    @Test fun wordEstimatesAreOrdered() {
        assertTrue(ordered(words))
    }

    @Test fun unchangedWordsRetainTimingAfterCorrection() {
        val corrected = SubtitleTiming.corrected(words.take(4), "Here is a replacement")
        assertEquals(words.take(2).map { it.start }, corrected.take(2).map { it.start })
        // Corrected words stay within their phrase.
        assertTrue(corrected.all { it.start >= words[0].start && it.end <= words[3].end })
    }

    @Test fun punctuationCorrectionsRetainTimingsExactly() {
        val punctuated = SubtitleTiming.corrected(words.take(3), "Here, is the!")
        assertEquals(words.take(3).map { it.start }, punctuated.map { it.start })
        assertEquals(words.take(3).map { it.end }, punctuated.map { it.end })
    }

    @Test fun insertedWordsBorrowBoundedTimingWithoutOverlaps() {
        val inserted = SubtitleTiming.corrected(words.take(2), "Here really is")
        assertEquals(3, inserted.size)
        assertTrue(ordered(inserted))
    }

    // In the original these two go through the timeline (installSubtitles and
    // regroupSubtitles). Here they exercise the grouping those functions call.

    @Test fun initialCaptionsObeyWordLimit() {
        val grouped = SubtitleTiming.group(words, 6)
        assertEquals(words.map { it.id }, grouped.map { it.id })
        assertTrue(phrases(grouped).all { it.size <= 6 })
    }

    @Test fun regroupingUsesStoredTranscript() {
        val regrouped = SubtitleTiming.group(SubtitleTiming.group(words, 6), 2)
        assertEquals(words.map { it.text }, regrouped.map { it.text })
        assertTrue(phrases(regrouped).all { it.size <= 2 })
    }

    @Test fun fontAndPlacementRoundTrip() {
        val placement = SubtitleSettings(font = SubtitleFont.Classic, x = 0.3, size = 0.13, y = 0.2)
        val encoded = documentJson.encodeToString(SubtitleSettings.serializer(), placement)
        assertEquals(placement, documentJson.decodeFromString(SubtitleSettings.serializer(), encoded))
        // Existing subtitles load with a centered system font.
        val legacy = JsonObject(documentJson.parseToJsonElement(encoded).jsonObject - "x" - "font")
        val old = documentJson.decodeFromJsonElement(SubtitleSettings.serializer(), legacy)
        assertEquals(0.5, old.centerX, 0.0)
        assertEquals(SubtitleFont.System, old.resolvedFont)
    }

    // The tests below are this port's own.

    @Test fun punctuationStaysWithItsWord() {
        assertEquals(listOf("Here", "is", "the", "first", "sentence."), SubtitleTiming.tokens("  Here is the\nfirst   sentence. "))
        assertEquals(listOf("Here,", "is", "the!"), SubtitleTiming.tokens("Here, is the!"))
        // Leading punctuation belongs to the first word; an apostrophe does not split one.
        assertEquals(listOf("«Ciao»,", "perché", "don't", "you?"), SubtitleTiming.tokens("«Ciao», perché don't you?"))
        assertEquals(listOf("Take", "2", "costs", "3.5"), SubtitleTiming.tokens("Take 2 costs 3.5"))
    }

    @Test fun textWithoutWordsIsOneToken() {
        assertEquals(emptyList<String>(), SubtitleTiming.tokens(" \n "))
        assertEquals(listOf("..."), SubtitleTiming.tokens(" ... "))
    }

    @Test fun unspacedScriptsLoseNoText() {
        // Where the breaks fall depends on the runtime's dictionary, so only
        // what must hold everywhere is checked: nothing is dropped or reordered.
        for (text in listOf("今天天气很好。我们去公园吧！", "こんにちは、世界。カタカナとひらがな")) {
            val tokens = SubtitleTiming.tokens(text)
            assertEquals(text, tokens.joinToString(""))
            assertTrue(tokens.size >= 2)
            assertTrue(tokens.none { it.first() in "。！、" })
        }
    }

    @Test fun longerWordsTakeLonger() {
        val estimated = SubtitleTiming.estimate("a extraordinary", listOf(SourceInterval(1.0, 2.0)))
        assertEquals(listOf("a", "extraordinary"), estimated.map { it.text })
        assertEquals(1.0, estimated[0].start, 0.0)
        assertEquals(estimated[0].end, estimated[1].start, 0.0)
        assertEquals(2.0, estimated[1].end, 1e-9)
        assertTrue(estimated[1].end - estimated[1].start > 3 * (estimated[0].end - estimated[0].start))
        // Less than proportionally: thirteen letters do not take thirteen times one.
        assertTrue(estimated[1].end - estimated[1].start < 13 * (estimated[0].end - estimated[0].start))
    }

    @Test fun estimateNeedsWordsAndVoicedTime() {
        assertTrue(SubtitleTiming.estimate("", speech).isEmpty())
        assertTrue(SubtitleTiming.estimate("Hello", emptyList()).isEmpty())
        assertTrue(SubtitleTiming.estimate("Hello", listOf(SourceInterval(2.0, 2.0))).isEmpty())
    }

    @Test fun captionsEndAtTheWordLimit() {
        val even = (0 until 5).map { SubtitleWord(text = "w$it", start = it * 0.3, end = it * 0.3 + 0.3) }
        val grouped = SubtitleTiming.group(even.reversed(), 2)
        assertEquals(even.map { it.id }, grouped.map { it.id })
        assertEquals(listOf(2, 2, 1), phrases(grouped).map { it.size })
    }

    @Test fun captionsEndAtAPause() {
        val spoken = listOf(
            SubtitleWord(text = "one", start = 0.0, end = 0.3),
            SubtitleWord(text = "two", start = 0.6, end = 0.9),
            SubtitleWord(text = "three", start = 1.3, end = 1.6),
        )
        val grouped = SubtitleTiming.group(spoken, 6)
        assertEquals(grouped[0].phraseID, grouped[1].phraseID)
        assertNotEquals(grouped[1].phraseID, grouped[2].phraseID)
    }

    @Test fun captionsEndAfterASentence() {
        val texts = listOf("Done.", "Really?", "Yes!", "好。", "ok,", "then")
        val spoken = texts.mapIndexed { i, text -> SubtitleWord(text = text, start = i * 0.2, end = i * 0.2 + 0.2) }
        val grouped = SubtitleTiming.group(spoken, 16)
        assertEquals(listOf(1, 1, 1, 1, 2), phrases(grouped).map { it.size })
        // Estimated words run on from each other inside an island, so there only
        // a sentence divides them; the silence between islands divides them too.
        assertEquals(listOf(4, 1, 3, 1, 3), phrases(SubtitleTiming.group(words, 16)).map { it.size })
    }

    @Test fun correctionKeepsIdentityAndPhrase() {
        val phrase = SubtitleTiming.group(words, 16).take(4)
        val corrected = SubtitleTiming.corrected(phrase, "Here was the first")
        assertEquals(phrase.map { it.id } - phrase[1].id, corrected.map { it.id } - corrected[1].id)
        assertNotEquals(phrase[1].id, corrected[1].id)
        assertEquals("was", corrected[1].text)
        // The replacement takes exactly the interval of the word it replaces.
        assertEquals(phrase[1].start, corrected[1].start, 0.0)
        assertEquals(phrase[1].end, corrected[1].end, 1e-9)
        assertTrue(corrected.all { it.phraseID == phrase[0].phraseID })
    }

    @Test fun deletedWordsFreeTheirTime() {
        val phrase = words.take(5)
        val corrected = SubtitleTiming.corrected(phrase, "Here the sentence.")
        assertEquals(listOf(phrase[0], phrase[2], phrase[4]), corrected)
    }

    @Test fun fullRewriteRetainsThePhraseInterval() {
        val phrase = words.take(5)
        val rewritten = SubtitleTiming.corrected(phrase, "Something else entirely")
        assertEquals(3, rewritten.size)
        assertEquals(phrase.first().start, rewritten.first().start, 0.0)
        assertEquals(phrase.last().end, rewritten.last().end, 1e-9)
        assertTrue(ordered(rewritten))
    }

    @Test fun appendedWordsBorrowFromTheLastWord() {
        val phrase = words.take(2)
        val appended = SubtitleTiming.corrected(phrase, "Here is more")
        assertEquals(listOf("Here", "is", "more"), appended.map { it.text })
        assertEquals(phrase[0], appended[0])
        assertTrue(appended[1].end < phrase[1].end)
        assertEquals(appended[1].end, appended[2].start, 0.0)
        assertEquals(phrase[1].end, appended[2].end, 1e-9)
    }

    @Test fun emptyCorrectionRemovesTheCaption() {
        assertTrue(SubtitleTiming.corrected(words.take(3), "  ").isEmpty())
        assertTrue(SubtitleTiming.corrected(emptyList(), "Hello").isEmpty())
    }

    @Test fun pastedParagraphIsBoundedAndStaysInThePhrase() {
        val phrase = words.take(5)
        val pasted = SubtitleTiming.corrected(phrase, (1..300).joinToString(" ") { "word$it" })
        assertEquals(SubtitleTiming.MATCH_LIMIT, pasted.size)
        assertTrue(ordered(pasted))
        assertTrue(pasted.all { it.phraseID == phrase[0].phraseID && it.start >= phrase.first().start && it.end <= phrase.last().end + 1e-9 })
    }

    @Test fun settingsUseTheOriginalFieldNamesAndRawValues() {
        val json = """{"id":"A","maxWords":4,"style":"highlight","color":"mint","size":0.06,"y":0.8,"language":"Italian","timingFingerprint":"abc","font":"condensed","appearance":{"face":"system","weight":700},"motion":{"entrance":"none"}}"""
        val settings = documentJson.decodeFromString(SubtitleSettings.serializer(), json)
        assertEquals(SubtitleSettings.Style.Highlight, settings.style)
        assertEquals(SubtitleSettings.Color.Mint, settings.color)
        assertEquals(SubtitleFont.Condensed, settings.font)
        assertEquals(4, settings.maxWords)
        assertTrue(settings.valid)
        // A design this port does not model survives being saved again.
        assertEquals(documentJson.parseToJsonElement(json), documentJson.parseToJsonElement(documentJson.encodeToString(SubtitleSettings.serializer(), settings)))
    }

    @Test fun wordsUseTheOriginalFieldNames() {
        val json = """{"id":"A","phraseID":"B","text":"Ciao","start":1.5,"end":2.0}"""
        val word = documentJson.decodeFromString(SubtitleWord.serializer(), json)
        assertEquals(SubtitleWord("A", "B", "Ciao", 1.5, 2.0), word)
        assertEquals(json, documentJson.encodeToString(SubtitleWord.serializer(), word))
    }

    @Test fun settingsOutsideTheirRangesAreInvalid() {
        assertTrue(SubtitleSettings().valid)
        assertFalse(SubtitleSettings(maxWords = 0).valid)
        assertFalse(SubtitleSettings(maxWords = 17).valid)
        assertFalse(SubtitleSettings(size = 0.31).valid)
        assertFalse(SubtitleSettings(y = Double.NaN).valid)
        assertFalse(SubtitleSettings(x = 1.2).valid)
    }

    private val spoken = listOf(
        SubtitleWord(phraseID = "P1", text = "Hello", start = 1.0, end = 1.5),
        SubtitleWord(phraseID = "P1", text = "there.", start = 1.5, end = 2.0),
        SubtitleWord(phraseID = "P2", text = "How", start = 6.0, end = 6.5),
        SubtitleWord(phraseID = "P2", text = "are", start = 6.5, end = 7.0),
        SubtitleWord(phraseID = "P2", text = "you?", start = 7.0, end = 7.5),
    )

    @Test fun cuesFollowTheItemOnTheTimeline() {
        val cues = SubtitleTiming.cues("item", spoken.reversed(), timelineStart = 3.0, sourceStart = 0.0, sourceEnd = 12.0)
        assertEquals(listOf("Hello there.", "How are you?"), cues.map { it.text })
        assertEquals(4.0, cues[0].start, 1e-9)
        assertEquals(5.0, cues[0].end, 1e-9)
        assertEquals(spoken.take(2).map { it.id }, cues[0].wordIDs)
        assertEquals("item-0-${spoken[0].id}", cues[0].id)
        // Twice as fast: the same words in half the time.
        val faster = SubtitleTiming.cues("item", spoken, timelineStart = 0.0, sourceStart = 0.0, sourceEnd = 12.0, duration = 6.0)
        assertEquals(cues[1].start - 3.0, faster[1].start * 2, 1e-9)
    }

    @Test fun cuesAreCutAtTheEdgesOfWhatPlays() {
        val cues = SubtitleTiming.cues("item", spoken, timelineStart = 0.0, sourceStart = 1.25, sourceEnd = 7.0)
        assertEquals(listOf("Hello there.", "How are"), cues.map { it.text })
        assertEquals(0.0, cues[0].start, 0.0)
        assertEquals(0.75, cues[0].end, 1e-9)
        assertEquals(4.75, cues[1].start, 1e-9)
        assertEquals(5.75, cues[1].end, 1e-9)
        assertTrue(SubtitleTiming.cues("item", spoken, timelineStart = 0.0, sourceStart = 3.0, sourceEnd = 3.0).isEmpty())
    }

    @Test fun aPhraseWithAHoleBecomesTwoCues() {
        // "are" was removed by an edit; its neighbors are now half a second apart.
        val cues = SubtitleTiming.cues("item", spoken - spoken[3], timelineStart = 0.0, sourceStart = 0.0, sourceEnd = 12.0)
        assertEquals(listOf("Hello there.", "How", "you?"), cues.map { it.text })
    }
}
