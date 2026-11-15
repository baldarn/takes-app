package app.takes.core

import app.takes.core.TrackEditingSupport.ticks
import app.takes.core.TrackFixtures.uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Transcribing the mix, or a marked range of it, onto the sound items. The
 * checks that come from the original's `Tests/SubtitleTests.swift` name their
 * lines.
 */
class TrackCaptionsTest {
    // Tests/SubtitleTests.swift:8-9, :23-26: three stretches of speech in one take of twelve seconds.
    private val speech = listOf(SourceInterval(0.2, 2.0), SourceInterval(4.0, 7.0), SourceInterval(9.0, 11.0))
    private val text = "Here is the first sentence. Then another sentence arrives. Finally we finish."
    private val source = SourceMedia(id = uuid(1), filename = "source.mp4", duration = 12.0)
    private val clip = TimelineClip(uuid(2), source.id, 0.0, 12.0)
    private val bare = EditState(listOf(clip)).toTimeline()
    private val whole = TrackCaptions.scope(bare)
    private val original = TrackCaptions.installing(bare, SubtitleTiming.estimate(text, speech), whole, "English")
    private val firstAudio = original.audio[0].id

    private fun TrackTimeline.cues() = TrackSubtitles.cues(this)

    // MARK: Scope

    /** YouRec/EditorView.swift:1011-1019; YouRec/SubtitleEditor.swift:54, :174, :251, :351, :370-374. */
    @Test
    fun theToolWorksOnTheMarkedRangeOrTheWholeProject() {
        assertEquals(EditInterval.of(0.0, 12.0), whole)
        assertEquals(EditInterval.of(3.0, 5.0), TrackCaptions.scope(original, TimelineSelection(range = EditInterval.of(3.0, 5.0))))
        assertEquals(EditInterval.of(0.0, 12.0), TrackCaptions.scope(original, TimelineSelection(audio = setOf(firstAudio))))
        assertEquals(whole, TrackCaptions.scope(original, TimelineSelection()))
        // The captions listed are those that show in the range at all; only those ask to be replaced.
        val inside = TrackCaptions.cues(original, EditInterval.of(4.5, 5.0))
        assertTrue(inside.isNotEmpty() && inside.all { it.start < 5.0 && it.end > 4.5 })
        assertTrue(TrackCaptions.replaces(original, EditInterval.of(4.5, 5.0)))
        assertFalse(TrackCaptions.replaces(original, EditInterval.of(2.5, 3.5)))
        assertFalse(TrackCaptions.replaces(bare, whole))
        // "Footage changed": offered once the structure moved after transcription.
        assertFalse(TrackCaptions.stale(original))
        assertFalse(TrackCaptions.stale(bare))
        assertTrue(TrackCaptions.stale(original.split(ticks(5.0), TrackLinking.entireSelection(original)).timeline))
    }

    /** "This range has no audio.": what the recognizer hears is what the mix plays there. */
    @Test
    fun theRecognizerHearsWhatTheMixPlaysInTheRange() {
        assertEquals(listOf(EditInterval.of(3.0, 5.0)), TrackCaptions.heard(original, EditInterval.of(3.0, 5.0)).map { it.local })
        val muted = TrackAudio.settingVolume(original, 0.0, true, TrackLinking.entireSelection(original))
        assertTrue(TrackCaptions.heard(muted, whole).isEmpty())
        assertTrue(TrackCaptions.heard(TrackTimeline(), EditInterval.of(0.0, 1.0)).isEmpty())
    }

    // MARK: Installing

    /** Tests/SubtitleTests.swift:52-55: "Partial re-transcription preserves phrase identity outside the selection". */
    @Test
    fun aRangeReplacesOnlyItsOwnWords() {
        val preserved = original.cues().filter { it.start > 4 }
        // The recognizer gives the words of the range from its start.
        val range = EditInterval.of(0.0, 2.0)
        val partial = TrackCaptions.installing(original, SubtitleTiming.estimate("New opening", listOf(SourceInterval(0.3, 1.5))), range, "English")
        assertEquals(preserved, partial.cues().filter { it.start > 4 })
        assertEquals(listOf("New opening"), partial.cues().filter { it.start < 4 }.map { it.text })
        partial.validate(listOf(source))
        // A range further on: the words heard at 0.5 s into it are at 9.5 s on the timeline.
        val late = TrackCaptions.installing(original, SubtitleTiming.estimate("Finally done", listOf(SourceInterval(0.0, 1.5))), EditInterval.of(9.0, 12.0), "English")
        val replaced = late.cues().filter { it.start >= 9.0 }
        assertEquals(listOf("Finally done"), replaced.map { it.text })
        assertEquals(9.0, replaced[0].start, 0.001)
        assertEquals(original.cues().filter { it.end < 9.0 }, late.cues().filter { it.end < 9.0 })
    }

    /** Tests/SubtitleTests.swift:46-49 and docs/SUBTITLES-REVIEW.md, finding 2: a voice-over is captioned, on its own sound, and its captions follow it. */
    @Test
    fun aVoiceOverIsCaptionedOnItsOwnSoundAndFollowsIt() {
        val voice = SourceMedia(id = uuid(5), filename = "voice.m4a", duration = 3.0, audioOnly = true)
        val silent = TrackAudio.settingVolume(bare, 0.0, true, TrackLinking.entireSelection(bare))
        val over = TrackAudio.inserting(silent, voice, ticks(6.0))
        val captioned = TrackCaptions.installing(over.timeline, SubtitleTiming.estimate("Narration here", listOf(SourceInterval(6.5, 8.0))), TrackCaptions.scope(over.timeline), "English")
        assertNull(captioned.audio[0].subtitleWords)
        val words = captioned.audio.first { it.id == over.id }.subtitleWords!!
        assertEquals(listOf("Narration", "here"), words.map { it.text })
        assertEquals(0.5, words[0].start, 0.001)
        val moved = TrackMove.moved(captioned, TimelineSelection(audio = setOf(over.id)), ticks(2.0))
        assertEquals(captioned.cues()[0].start + 2.0, moved.cues()[0].start, 0.00001)
    }

    /** Tests/SubtitleTests.swift:50-51 and finding 8: words heard in a sped-up mix are kept in source seconds. */
    @Test
    fun wordsHeardAtSpeedAreKeptInSourceSeconds() {
        val faster = bare.replacingVideoContents(bare.projectedClips.map { it.copy(speed = SpeedSettings(rate = 2.0)) })
        val heard = SubtitleTiming.estimate(text, speech.map { SourceInterval(it.start / 2, it.end / 2) })
        val captioned = TrackCaptions.installing(faster, heard, TrackCaptions.scope(faster), "English")
        val words = captioned.audio[0].subtitleWords!!
        for ((placed, played) in words.zip(heard)) {
            assertEquals(played.start * 2, placed.start, 0.001)
            assertEquals(played.end * 2, placed.end, 0.001)
        }
        val normal = captioned.replacingVideoContents(captioned.projectedClips.map { it.copy(speed = null) })
        assertEquals(text, TrackSubtitles.transcript(normal))
    }

    // MARK: The gate: sequences are captioned as they are today

    /**
     * Every edit of the sequence form shows, converted, the captions it shows
     * today: the same text, words and times. An edit with a sound unlinked
     * from its clip is the one difference (N10): today the muted clip hides
     * its words, on the timeline they belong to the sound and show where it
     * plays, as in the original. No button unlinks today.
     */
    @Test
    fun aConvertedSequenceShowsTodaysCaptions() {
        var compared = 0
        for ((name, state) in sequences()) {
            val expected = SubtitleEditing.cues(state)
            val actual = TrackSubtitles.cues(state.toTimeline())
            assertEquals(name, expected.map { it.text }, actual.map { it.text })
            assertEquals(name, expected.map { it.wordIDs }, actual.map { it.wordIDs })
            for ((a, b) in expected.zip(actual)) {
                assertEquals(name, a.start, b.start, 4 * TransitionLayout.TICK)
                assertEquals(name, a.end, b.end, 4 * TransitionLayout.TICK)
                assertEquals(name, TrackTimeline.counterpart(a.audioID), b.audioID)
            }
            compared += expected.size
        }
        assertTrue(compared > 0)
    }

    /**
     * Transcribing the mix of a converted sequence, when the recognizer hears
     * each word where today's caption shows it, puts every word back on the
     * sound of its clip at its source seconds, with the same transcript but
     * for a word a cut runs through, which today shows on both sides. A
     * word where two sounds play at once (a dissolve) goes to one of them
     * only, as in the original; those are left out of the comparison.
     */
    @Test
    fun transcribingTheMixOfASequenceGivesBackTodaysWords() {
        var compared = 0
        var transcripts = 0
        for ((name, state) in sequences()) {
            val timeline = state.toTimeline()
            if (timeline.subtitles == null) continue
            val heard = ArrayList<SubtitleWord>()
            val expected = HashMap<String, Pair<String, SubtitleWord>>()
            for (item in timeline.audio) {
                if (item.muted) continue
                var cursor = item.start.seconds
                for (run in item.mapping.runs) {
                    val start = cursor
                    cursor += run.duration.seconds
                    if (run.silent || run.isHold) continue
                    val scale = run.duration.seconds / (run.sourceEnd - run.sourceStart)
                    for (word in item.subtitleWords.orEmpty()) {
                        val middle = (word.start + word.end) / 2
                        if (middle < run.sourceStart || middle >= run.sourceEnd) continue
                        val a = maxOf(run.sourceStart, word.start)
                        val b = minOf(run.sourceEnd, word.end)
                        val at = word.copy(start = start + (a - run.sourceStart) * scale, end = start + (b - run.sourceStart) * scale)
                        val playing = timeline.audio.count { !it.muted && EditTime.of((at.start + at.end) / 2) in it.interval }
                        if (playing != 1 || word.id in expected) continue
                        heard += at
                        expected[word.id] = item.id to word.copy(start = a, end = b)
                    }
                }
            }
            val scope = TrackCaptions.scope(timeline)
            val local = heard.map { it.copy(start = it.start - scope.start.seconds, end = it.end - scope.start.seconds) }
            val installed = TrackCaptions.installing(timeline, local, scope, timeline.subtitles!!.language)
            for ((id, owner) in expected) {
                val (itemID, word) = owner
                val placed = installed.audio.flatMap { item -> item.subtitleWords.orEmpty().filter { it.id == id }.map { item.id to it } }
                assertEquals("$name: ${word.text}", 1, placed.size)
                assertEquals("$name: ${word.text}", itemID, placed[0].first)
                assertEquals(word.text, placed[0].second.text)
                assertTrue("$name: ${word.text}", abs(word.start - placed[0].second.start) < 0.001 && abs(word.end - placed[0].second.end) < 0.001)
                compared++
            }
            val shown = SubtitleEditing.cues(state)
            if (shown.isNotEmpty() && shown.all { cue -> cue.wordIDs.all { it in expected } }) {
                transcripts++
                // A word a cut runs through shows on both sides today and once in the mix, on the side its middle plays.
                val words = shown.flatMap { it.wordIDs }.distinct().map { expected.getValue(it).second.text }
                assertEquals(name, SubtitleTiming.joined(words), TrackSubtitles.transcript(installed))
            }
        }
        assertTrue(compared > 0 && transcripts > 0)
    }

    /** N10: the words of an unlinked sound show on it, where it plays. */
    @Test
    fun theWordsOfAnUnlinkedSoundShowWhereItPlays() {
        val state = TrackFixtures.unlinked()
        val today = SubtitleEditing.cues(state)
        val cues = TrackSubtitles.cues(state.toTimeline())
        assertEquals(today.map { it.text } + "Second", cues.map { it.text })
        val sound = state.audio!!.single { it.id == state.clips[1].id }
        val second = cues.single { it.text == "Second" }
        assertEquals(sound.start + (3.0 - sound.sourceStart), second.start, 4 * TransitionLayout.TICK)
    }

    /** The edits whose captions are today's: those with no sound unlinked from its clip. */
    private fun sequences(): List<Pair<String, EditState>> = edits().filter { (_, state) ->
        val clips = state.clips.mapTo(HashSet()) { it.id }
        state.audio.orEmpty().none { it.id in clips }
    }

    private fun edits(): List<Pair<String, EditState>> =
        TrackFixtures.all.map { (name, state) -> "fixture $name" to state } +
            GoldenProjects.all.filter { !it.large }.flatMap { golden -> golden.project.history.mapIndexed { index, state -> "golden ${golden.name}, step $index" to state } }
}
