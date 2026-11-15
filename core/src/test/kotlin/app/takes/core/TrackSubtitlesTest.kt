package app.takes.core

import app.takes.core.TrackEditingSupport.ticks
import app.takes.core.TrackFixtures.uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Captions on the track timeline and what the editing operations do to them.
 * The checks that come from the original's `Tests/SubtitleTests.swift` name
 * their lines and keep its numbers.
 */
class TrackSubtitlesTest {
    // Tests/SubtitleTests.swift:8-9, :23-26: three stretches of speech in one take of twelve seconds.
    private val speech = listOf(SourceInterval(0.2, 2.0), SourceInterval(4.0, 7.0), SourceInterval(9.0, 11.0))
    private val words = SubtitleTiming.estimate("Here is the first sentence. Then another sentence arrives. Finally we finish.", speech)
    private val source = SourceMedia(id = uuid(1), filename = "source.mp4", duration = 12.0)
    private val clip = TimelineClip(uuid(2), source.id, 0.0, 12.0)
    private val bare = EditState(listOf(clip)).toTimeline()
    private val original = TrackSubtitles.installing(bare, words, 0.0, 12.0, "English")
    private val firstAudio = original.audio[0].id

    private fun TrackTimeline.cues() = TrackSubtitles.cues(this)
    private fun tokens(cue: SubtitleCue) = SubtitleTiming.tokens(cue.text).size

    /** The same captions at the same times, to within what rounding to ticks can move one. */
    private fun assertSameCaptions(expected: List<SubtitleCue>, actual: List<SubtitleCue>, shift: (SubtitleCue) -> Double = { 0.0 }) {
        assertEquals(expected.map { it.text }, actual.map { it.text })
        for ((a, b) in expected.zip(actual)) {
            assertEquals(a.text, a.start + shift(a), b.start, 4 * TransitionLayout.TICK)
            assertEquals(a.text, a.end + shift(a), b.end, 4 * TransitionLayout.TICK)
        }
    }

    // MARK: Installing and grouping

    /** Tests/SubtitleTests.swift:26-29: "Initial captions obey word limit", "Regrouping uses stored transcript". */
    @Test
    fun aTranscriptionIsInstalledOnTheSoundAndRegroupedFromTheStoredWords() {
        assertTrue(TrackSubtitles.groupingAvailable(original))
        assertEquals("English", original.subtitles?.language)
        assertEquals(words.size, original.audio[0].subtitleWords?.size)
        assertTrue(original.cues().isNotEmpty() && original.cues().all { tokens(it) <= 6 })
        assertEquals("Here is the first sentence. Then another sentence arrives. Finally we finish.", TrackSubtitles.transcript(original))
        original.validate(listOf(source))
        val regrouped = TrackSubtitles.regrouping(original, 2)!!
        assertEquals(2, regrouped.subtitles?.maxWords)
        assertTrue(regrouped.cues().all { tokens(it) <= 2 })
        assertEquals(TrackSubtitles.transcript(original), TrackSubtitles.transcript(regrouped))
        assertNull(TrackSubtitles.regrouping(original, 0))
        assertNull(TrackSubtitles.regrouping(original, 17))
        assertNull(TrackSubtitles.regrouping(bare, 3))
        // Without settings a timeline shows no captions, whatever words its sounds hold.
        assertTrue(original.copy(subtitles = null).cues().isEmpty())
        assertEquals(original, TrackTimeline.decoded(original.encoded()))
    }

    /** Tests/SubtitleTests.swift:52-55: "Partial re-transcription preserves phrase identity outside the selection". */
    @Test
    fun transcribingAStretchAgainLeavesTheCaptionsOutsideItAsTheyWere() {
        val preserved = original.cues().filter { it.start > 4 }
        val partial = TrackSubtitles.installing(original, SubtitleTiming.estimate("New opening", listOf(SourceInterval(0.3, 1.5))), 0.0, 2.0, "English")
        assertEquals(preserved, partial.cues().filter { it.start > 4 })
        assertEquals(listOf("New opening"), partial.cues().filter { it.start < 4 }.map { it.text })
        assertTrue(TrackSubtitles.groupingAvailable(partial))
        partial.validate(listOf(source))
        // A stale fingerprint stays stale unless the new range covers every caption there was.
        val cut = original.split(ticks(5.0), TrackLinking.entireSelection(original)).timeline
        assertFalse(TrackSubtitles.groupingAvailable(TrackSubtitles.installing(cut, emptyList(), 0.0, 2.0, "English")))
        assertTrue(TrackSubtitles.groupingAvailable(TrackSubtitles.installing(cut, words, 0.0, 12.0, "English")))
    }

    @Test
    fun aWordGoesToTheLoudestSoundPlayingAtItsMiddleAndToNoOther() {
        // A second sound over the first, louder, from 4 s on, playing another recording.
        val voice = SourceMedia(id = uuid(10), filename = "voice.m4a", duration = 8.0, audioOnly = true)
        val over = AudioTimelineItem(uuid(11), uuid(11), voice.id, ticks(4.0), SourcePlaybackMap.single(ticks(8.0), 0.0, 8.0), volumeDB = 3.0)
        val mixed = TrackSubtitles.installing(bare.copy(audio = bare.audio + over), words, 0.0, 12.0, "English")
        val under = mixed.audio[0].subtitleWords.orEmpty()
        val top = mixed.audio[1].subtitleWords.orEmpty()
        assertEquals(words.size, under.size + top.size)
        assertTrue(under.isNotEmpty() && under.all { it.end <= 2.0 + 0.000001 })
        // On the louder sound the words are in its own source seconds: four seconds earlier.
        assertEquals(words.filter { it.start >= 4 }.map { it.text }, top.map { it.text })
        for ((heard, kept) in words.filter { it.start >= 4 }.zip(top)) assertEquals(heard.start - 4, kept.start, 2 * TransitionLayout.TICK)
        assertEquals(TrackSubtitles.transcript(original), TrackSubtitles.transcript(mixed))
        mixed.validate(listOf(source, voice))
        // Muted, the louder sound takes none.
        val muted = TrackSubtitles.installing(bare.copy(audio = bare.audio + over.copy(muted = true)), words, 0.0, 12.0, "English")
        assertNull(muted.audio[1].subtitleWords)
        assertEquals(words.size, muted.audio[0].subtitleWords?.size)
        // A word whose sound is held or silent there is not put anywhere.
        val silent = bare.copy(audio = listOf(bare.audio[0].copy(followsVideo = null, mapping = SourcePlaybackMap(listOf(PlaybackRun(ticks(12.0), 0.0, 12.0, silent = true))))), links = emptyList())
        assertTrue(TrackSubtitles.installing(silent, words, 0.0, 12.0, "English").cues().isEmpty())
    }

    // MARK: Through the editing operations

    /** Tests/SubtitleTests.swift:34-42: a split keeps the captions, makes regrouping stale, and each piece owns its words. */
    @Test
    fun aSplitKeepsTheCaptionsAndEachPieceOwnsItsWords() {
        val divided = original.split(ticks(5.0), TrackLinking.entireSelection(original)).timeline
        // "Split preserves captions and marks regrouping stale".
        assertFalse(TrackSubtitles.groupingAvailable(divided))
        assertTrue(divided.cues().isNotEmpty())
        assertEquals(original.audio[0].subtitleWords.orEmpty().map { it.id }.toSet(), divided.cues().flatMap { it.wordIDs }.toSet())
        // Both pieces hold every word; each shows those its sound plays.
        assertEquals(divided.audio[0].subtitleWords, divided.audio[1].subtitleWords)
        assertTrue(divided.cues().filter { it.audioID == firstAudio }.all { it.end <= 5.0 })
        assertTrue(divided.cues().filter { it.audioID != firstAudio }.all { it.start >= 5.0 })
        // "Correcting a split caption cannot mutate its other half".
        val right = divided.cues().filter { it.audioID != firstAudio }.map { it.text }
        val corrected = TrackSubtitles.correcting(divided, divided.cues().first { it.audioID == firstAudio }, "Edited independently")!!
        assertEquals(right, corrected.cues().filter { it.audioID != firstAudio }.map { it.text })
        assertEquals("Edited independently", corrected.cues().first { it.audioID == firstAudio }.text)
        assertNotEquals(corrected.audio[0].subtitleWords, corrected.audio[1].subtitleWords)
        // "Stale transcript refuses regrouping".
        assertNull(TrackSubtitles.regrouping(divided, 9))
        corrected.validate(listOf(source))
    }

    /** Tests/SubtitleTests.swift:46-51: "Captions follow independent speech moves", "Captions follow pitch-preserved speed changes". */
    @Test
    fun captionsFollowTheirSoundWhenItMovesAndWhenItsSpeedChanges() {
        val moved = TrackMove.moved(TrackLinking.unlinked(original, TrackLinking.entireSelection(original)), TimelineSelection(audio = setOf(firstAudio)), ticks(3.0))
        assertTrue(abs(moved.cues()[0].start - original.cues()[0].start - 3) < 0.00001)
        val faster = original.replacingVideoContents(original.projectedClips.map { it.copy(speed = SpeedSettings(rate = 2.0)) })
        assertTrue(abs(faster.cues()[0].start * 2 - original.cues()[0].start) < 0.0001)
        assertEquals(original.cues().map { it.text }, faster.cues().map { it.text })
        assertFalse(TrackSubtitles.groupingAvailable(faster))
        // Time opened before a caption moves it; time opened inside one cuts it at the place.
        val opened = original.insertingTime(ticks(1.0), ticks(3.0))
        assertSameCaptions(original.cues(), opened.cues()) { if (it.start >= 3) 1.0 else 0.0 }
    }

    /** Tests/SubtitleTests.swift:56-59: "Style changes do not invalidate timing", "Gain changes do not invalidate timing". */
    @Test
    fun lookAndLevelLeaveTheFingerprintAlone() {
        val styled = TrackSubtitles.restyling(original, original.subtitles!!.copy(style = SubtitleSettings.Style.Bold))!!
        assertTrue(TrackSubtitles.groupingAvailable(styled))
        assertNull(TrackSubtitles.restyling(original, original.subtitles!!))
        assertNull(TrackSubtitles.restyling(original, original.subtitles!!.copy(size = 9.0)))
        assertNull(TrackSubtitles.restyling(bare, SubtitleSettings()))
        assertTrue(TrackSubtitles.groupingAvailable(TrackAudio.settingVolume(original, -3.0, false, TrackLinking.entireSelection(original))))
        val chained = TrackAudio.enhancing(original, listOf(AudioEffect.make(AudioEffectKind.HighPass, uuid(20))), TrackLinking.entireSelection(original))
        assertTrue(TrackSubtitles.groupingAvailable(chained))
        // Muting is structure: what is heard changed.
        assertFalse(TrackSubtitles.groupingAvailable(TrackAudio.settingVolume(original, 0.0, true, TrackLinking.entireSelection(original))))
    }

    @Test
    fun aTrimHidesTheCaptionsOfWhatItTookAndRevealingShowsThemAgain() {
        val trimmed = TrackEditing.trimming(original, clip.id, left = false, delta = -6.0, sources = listOf(source))!!.timeline
        assertSameCaptions(original.cues().filter { it.end <= 2.0 }, trimmed.cues().filter { it.end <= 2.0 })
        assertTrue(trimmed.cues().any { it.start >= 4.0 })
        assertTrue(trimmed.cues().all { it.end <= 6.0 })
        // The words are still on the sound.
        assertEquals(words.size, trimmed.audio[0].subtitleWords?.size)
        val revealed = trimmed.extending(TrackLinking.entireSelection(trimmed), left = false, by = ticks(6.0), sources = listOf(source))
        // The revealed footage is a run of its own, so a caption the cut at 6 s went through shows in two parts; every other is as it was.
        fun clear(cues: List<SubtitleCue>) = cues.filter { it.end <= 6.0 - 0.001 || it.start >= 6.0 + 0.001 }
        assertSameCaptions(clear(original.cues()), clear(revealed.cues()))
        assertEquals(original.cues().last().end, revealed.cues().last().end, 4 * TransitionLayout.TICK)
        revealed.validate(listOf(source))
    }

    @Test
    fun deletingOrMutingASoundTakesItsCaptionsAway() {
        val divided = original.split(ticks(5.0), TrackLinking.entireSelection(original)).timeline
        val without = divided.removing(TrackLinking.selection(divided, firstAudio)).timeline
        assertSameCaptions(divided.cues().filter { it.audioID != firstAudio }, without.cues()) { -5.0 }
        val muted = TrackAudio.settingVolume(divided, 0.0, true, TimelineSelection(audio = setOf(firstAudio)))
        assertTrue(muted.cues().none { it.audioID == firstAudio })
        assertEquals(divided.audio[0].subtitleWords, muted.audio[0].subtitleWords)
        // A caption is deleted from its own sound only.
        val cue = divided.cues().first { it.audioID == firstAudio }
        val deleted = TrackSubtitles.deleting(divided, cue)!!
        assertEquals(divided.cues().size - 1, deleted.cues().size)
        assertNull(TrackSubtitles.deleting(deleted, cue))
        assertNull(TrackSubtitles.correcting(divided, cue.copy(audioID = uuid(999)), "Nothing"))
    }

    /** Tests/SubtitleTests.swift:66-67: "Removal deletes transcript and timing together". */
    @Test
    fun removingCaptionsRemovesTheSettingsAndEveryWord() {
        val removed = TrackSubtitles.removing(original)
        assertTrue(removed.cues().isEmpty())
        assertTrue(removed.audio.all { it.subtitleWords == null })
        assertNull(removed.subtitles)
        assertEquals(bare, removed)
    }

    // MARK: Transcribing recordings

    @Test
    fun theStretchesToTranscribeAreWhatUnmutedSoundsPlayOfEachSource() {
        val state = TrackFixtures.plain()
        val timeline = state.toTimeline()
        assertEquals(SubtitleEditing.stretches(state.clips), TrackSubtitles.stretches(timeline))
        // A cut does not divide what is listened to, and a muted sound is not listened to.
        val cut = timeline.split(ticks(3.0), TrackLinking.entireSelection(timeline)).timeline
        assertEquals(TrackSubtitles.stretches(timeline), TrackSubtitles.stretches(cut))
        val quiet = TrackAudio.settingVolume(timeline, 0.0, true, TimelineSelection(audio = setOf(timeline.audio[1].id)))
        assertEquals(setOf(TrackFixtures.take.id), TrackSubtitles.stretches(quiet).map { it.sourceID }.toSet())
        assertEquals(TrackSubtitles.stretches(quiet), TrackSubtitles.stretches(timeline, setOf(timeline.audio[0].id, timeline.audio[2].id)))
    }

    @Test
    fun wordsHeardInARecordingAreInstalledOnEverySoundThatPlaysThem() {
        val heard = mapOf(source.id to words)
        val installed = TrackSubtitles.installingSourceWords(bare, heard, "English")
        assertTrue(TrackSubtitles.groupingAvailable(installed))
        assertSameCaptions(original.cues(), installed.cues())
        // Each sound takes its own copy, under ids of its own.
        val cut = bare.split(ticks(5.0), TrackLinking.entireSelection(bare)).timeline
        val both = TrackSubtitles.installingSourceWords(cut, heard, "English")
        assertEquals(words.size, both.audio.sumOf { it.subtitleWords.orEmpty().size })
        assertTrue(both.audio[0].subtitleWords.orEmpty().map { it.id }.intersect(both.audio[1].subtitleWords.orEmpty().map { it.id }.toSet()).isEmpty())
        assertEquals(TrackSubtitles.transcript(original), TrackSubtitles.transcript(both))
        // Only the sounds named take words; the others lose theirs.
        val one = TrackSubtitles.installingSourceWords(both, heard, "English", setOf(both.audio[1].id))
        assertNull(one.audio[0].subtitleWords)
        assertEquals(both.audio[1].subtitleWords?.map { it.text }, one.audio[1].subtitleWords?.map { it.text })
        both.validate(listOf(source))
    }

    /** The captions of a converted edit are those the sequence form shows, at the same times. */
    @Test
    fun theCaptionsOfAConvertedEditAreThoseOfItsClips() {
        val state = TrackFixtures.captions()
        val expected = SubtitleEditing.cues(state)
        val actual = TrackSubtitles.cues(state.toTimeline())
        assertTrue(expected.isNotEmpty())
        assertEquals(expected.map { it.text }, actual.map { it.text })
        assertEquals(expected.map { it.wordIDs }, actual.map { it.wordIDs })
        assertSameCaptions(expected, actual)
        for ((a, b) in expected.zip(actual)) assertEquals(TrackTimeline.counterpart(a.audioID), b.audioID)
    }
}
