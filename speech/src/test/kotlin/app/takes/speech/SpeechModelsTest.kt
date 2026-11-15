package app.takes.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which model a phone uses and offers, from its memory, what is on it and what the person picked. */
class SpeechModelsTest {
    private val qwen = SpeechModel.Qwen3Asr
    private val small = SpeechModel.WhisperSmall
    private val base = SpeechModel.WhisperBase

    // What Android reports as total memory on phones sold with 4, 6 and 8 GB.
    private val four = 3_700_000_000L
    private val six = 5_600_000_000L
    private val eight = 7_500_000_000L

    @Test fun theOriginalsModelWhereMemoryAllowsTheLighterElsewhere() {
        assertEquals(small, SpeechModels.recommended(four))
        assertEquals(small, SpeechModels.recommended(six))
        assertEquals(qwen, SpeechModels.recommended(eight))
        assertEquals(small, SpeechModels.recommended(SpeechModels.QWEN_MINIMUM_RAM_BYTES - 1))
        assertEquals(qwen, SpeechModels.recommended(SpeechModels.QWEN_MINIMUM_RAM_BYTES))
        assertEquals(small, SpeechModels.recommended(0)) // memory unknown
    }

    @Test fun twoModelsAreOfferedAndTheEarlierOneIsNot() {
        assertEquals(listOf(qwen, small), SpeechModels.offered)
        assertTrue(base !in SpeechModels.offered)
        assertTrue(base in SpeechModels.known)
        assertTrue(SpeechModel.WhisperTiny !in SpeechModels.known)
    }

    @Test fun withoutAChoiceANewPhoneGetsItsRecommendedModel() {
        assertEquals(qwen, SpeechModels.selected(null, emptySet(), eight))
        assertEquals(small, SpeechModels.selected(null, emptySet(), four))
        assertEquals(qwen, SpeechModels.selected(null, setOf(qwen.id, small.id), eight))
    }

    @Test fun aPhoneThatHasTheEarlierModelKeepsUsingItUntilThePersonChanges() {
        assertEquals(base, SpeechModels.selected(null, setOf(base.id), eight))
        assertEquals(base, SpeechModels.selected(null, setOf(base.id), four))
        // The person changes: the choice wins, downloaded or not.
        assertEquals(qwen, SpeechModels.selected(qwen.id, setOf(base.id), eight))
        assertEquals(small, SpeechModels.selected(small.id, setOf(base.id), eight))
        // And can go back to it while it is still there.
        assertEquals(base, SpeechModels.selected(base.id, setOf(base.id, qwen.id), eight))
    }

    @Test fun theEarlierModelIsGoneForGoodOnceRemoved() {
        assertEquals(qwen, SpeechModels.selected(base.id, emptySet(), eight))
        assertEquals(small, SpeechModels.selected(base.id, emptySet(), four))
        assertEquals(small, SpeechModels.selected(base.id, setOf(small.id), eight))
    }

    @Test fun aChoiceHoldsOnAnyPhoneAndAnUnknownOneIsIgnored() {
        // Qwen on a small phone is the person's decision; the tool says so if it fails.
        assertEquals(qwen, SpeechModels.selected(qwen.id, emptySet(), four))
        assertEquals(small, SpeechModels.selected(small.id, setOf(qwen.id), eight))
        assertEquals(qwen, SpeechModels.selected("a-model-of-another-version@1", emptySet(), eight))
        assertEquals(small, SpeechModels.selected(SpeechModel.WhisperTiny.id, emptySet(), four))
    }

    @Test fun theChoiceListsWhatIsOfferedAndTheEarlierModelOnlyWhileItIsThere() {
        assertEquals(listOf(qwen, small), SpeechModels.choices(emptySet(), qwen))
        assertEquals(listOf(qwen, small, base), SpeechModels.choices(setOf(base.id), base))
        assertEquals(listOf(qwen, small, base), SpeechModels.choices(setOf(base.id, small.id), small))
    }

    @Test fun aModelIsNotTriedWithLessMemoryThanItTakes() {
        assertTrue(SpeechModels.fits(qwen, 3_600_000_000L))
        assertTrue(!SpeechModels.fits(qwen, 1_500_000_000L))
        assertTrue(SpeechModels.fits(small, 1_500_000_000L))
        assertTrue(!SpeechModels.fits(small, 500_000_000L))
        // What a model takes grows with its size, and none is free.
        assertTrue(qwen.memory > small.memory && small.memory > base.memory && base.memory > 0)
        // The memory that makes Qwen the default leaves room for it beside everything else.
        assertTrue(SpeechModels.QWEN_MINIMUM_RAM_BYTES >= 3 * qwen.memory)
    }

    @Test fun theLighterModelToFallBackTo() {
        assertEquals(small, SpeechModels.lighter(qwen))
        assertNull(SpeechModels.lighter(small))
        assertNull(SpeechModels.lighter(base))
    }
}
