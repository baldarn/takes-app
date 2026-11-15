package app.takes.core

import app.takes.core.RenderPlan.Entry
import app.takes.core.RenderPlan.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The render plan: what the export and the preview arrange today, as the plan gives it. */
class RenderPlanTest {
    private val a = TimelineClip(id = "A", sourceID = "S", start = 0.0, end = 4.0)
    private val b = TimelineClip(id = "B", sourceID = "S", start = 0.0, end = 4.0)
    private val c = TimelineClip(id = "C", sourceID = "S", start = 1.0, end = 5.0)
    private val tiny = 1e-9

    private fun joined(state: EditState, join: Int, settings: TransitionSettings) = TimelineEditing.setJoin(state, join, settings)!!

    // MARK: Picture sequences

    @Test fun anEmptyEditHasNothingToPlay() {
        val plan = RenderPlan.of(EditState())
        assertEquals(0.0, plan.duration, tiny)
        assertTrue(plan.sequences.isEmpty() && plan.pictures.isEmpty() && plan.soundtracks.isEmpty() && plan.cleaned.isEmpty())
        assertEquals(listOf(RenderPlan.Deck(0, emptyList(), emptyList()), RenderPlan.Deck(1, emptyList(), emptyList())), plan.decks)
        assertFalse(plan.stacked || plan.drawsTransitions)
    }

    @Test fun clipsThatFollowOneAnotherAreOneSequenceOnOneDeck() {
        val plan = RenderPlan.of(EditState(listOf(a, b, c)))
        assertEquals(12.0, plan.duration, tiny)
        assertEquals(listOf(RenderPlan.Sequence(Role.Picture, 0, listOf(Entry.Clip(0), Entry.Clip(1), Entry.Clip(2)))), plan.sequences)
        assertEquals(listOf(0, 1, 2), plan.decks[0].clips)
        assertTrue(plan.decks[1].clips.isEmpty())
        assertFalse(plan.stacked)
        // No transition effect at all: the frames go to the encoder as they come.
        assertFalse(plan.drawsTransitions)
        assertTrue(plan.fits(3840, 2160, 2160))
        assertTrue(plan.freezes.isEmpty())
        assertEquals(listOf(RenderPlan.Ending(0, 12.0)), plan.endings)
        val third = plan.pictures[2]
        assertEquals(8.0, third.start, tiny)
        assertEquals(1_000_000L to 5_000_000L, third.sourceStartUs to third.sourceEndUs)
        assertFalse(third.shaped || third.soundRemoved)
    }

    @Test fun clipsThatPlayAtOnceAlternateBetweenTwoSequencesWithGaps() {
        val state = joined(EditState(listOf(a, b, c)), 1, TransitionSettings(id = "T", duration = 1.0))
        val plan = RenderPlan.of(state)
        assertEquals(11.0, plan.duration, tiny)
        assertEquals(
            listOf(
                RenderPlan.Sequence(Role.Picture, 0, listOf(Entry.Clip(0), Entry.Gap(4.0, 7_000_000))),
                RenderPlan.Sequence(Role.Picture, 1, listOf(Entry.Gap(0.0, 3_000_000), Entry.Clip(1), Entry.Clip(2))),
            ),
            plan.sequences,
        )
        assertEquals(listOf(0), plan.decks[0].clips)
        assertEquals(listOf(1, 2), plan.decks[1].clips)
        assertEquals(listOf(0, 1, 1), plan.deckOf)
        assertTrue(plan.stacked && plan.drawsTransitions)
        // The frame holding both sequences is twice as tall as the movie.
        assertFalse(plan.fits(1920, 1080, 2048))
        assertTrue(plan.fits(1920, 1080, 4096))
        // Both clips of the dissolve are shaped by it; the third plays as recorded.
        assertEquals(listOf(true, true, false), plan.pictures.map { it.shaped })
        assertEquals(listOf(RenderPlan.Ending(0, 4.0), RenderPlan.Ending(1, 11.0)), plan.endings)
        assertEquals(1, plan.transitions.size)
    }

    @Test fun aFreezeBeforeTheFirstClipOrAfterTheLastIsAGapInTheOnlySequence() {
        val opening = RenderPlan.of(joined(EditState(listOf(a)), 0, TransitionSettings(id = "T", style = TransitionStyle.Still, duration = 1.0)))
        assertEquals(listOf(RenderPlan.Sequence(Role.Picture, 0, listOf(Entry.Gap(0.0, 1_000_000), Entry.Clip(0)))), opening.sequences)
        assertTrue(opening.drawsTransitions && !opening.stacked)
        // A freeze with no clip before it holds the fill, not a frame.
        assertTrue(opening.freezes.isEmpty())

        val close = RenderPlan.of(joined(EditState(listOf(a)), 1, TransitionSettings(id = "T", style = TransitionStyle.Still, duration = 2.0, stillDissolve = 0.5)))
        assertEquals(6.5, close.duration, tiny)
        assertEquals(listOf(RenderPlan.Sequence(Role.Picture, 0, listOf(Entry.Clip(0), Entry.Gap(4.0, 2_500_000)))), close.sequences)
        val freeze = close.freezes.single()
        assertEquals(0, freeze.clip)
        assertEquals(0, freeze.deck)
        assertEquals(4.0, freeze.from, tiny)
        assertEquals(6.5, freeze.until, tiny)
        // The last frame of the clip on the grid of thirtieths.
        assertEquals(119.0 / 30, freeze.shown, tiny)
        assertEquals(119.0 / 30, freeze.sourceTime, tiny)
        assertFalse(freeze.sounding)
    }

    @Test fun aFreezeBetweenClipsPutsThemOnDifferentDecksAndKeepsTheFrameOfTheFirst() {
        val state = joined(EditState(listOf(a, c)), 1, TransitionSettings(id = "T", style = TransitionStyle.Still, duration = 1.0))
        val plan = RenderPlan.of(state)
        assertEquals(9.0, plan.duration, tiny)
        assertEquals(
            listOf(
                RenderPlan.Sequence(Role.Picture, 0, listOf(Entry.Clip(0), Entry.Gap(4.0, 5_000_000))),
                RenderPlan.Sequence(Role.Picture, 1, listOf(Entry.Gap(0.0, 5_000_000), Entry.Clip(1))),
            ),
            plan.sequences,
        )
        val freeze = plan.freezes.single()
        assertEquals(listOf(1, 0, 0), listOf(freeze.join, freeze.clip, freeze.deck))
        assertEquals(4.0 to 5.0, freeze.from to freeze.until)
        // A still whose sound has a timing of its own keeps its clip playing under the freeze.
        val under = RenderPlan.of(joined(EditState(listOf(a, c)), 1, TransitionSettings(id = "T", style = TransitionStyle.Still, duration = 1.0, audioOffset = 0.0, audioBlend = 0.5)))
        assertTrue(under.freezes.single().sounding)
    }

    @Test fun cutsAreNeverLaterThanTheSecondTheyStandFor() {
        assertEquals(1_999_999L, RenderPlan.cutMicros(1.9999999))
        assertEquals(300_000L, RenderPlan.cutMicros(0.1 + 0.2))
        assertEquals(33_333L, RenderPlan.cutMicros(1.0 / 30))
        val plan = RenderPlan.of(EditState(listOf(a.copy(start = 1.0 / 30, end = 2.0 / 3))))
        assertEquals(33_333L to 666_666L, plan.pictures[0].sourceStartUs to plan.pictures[0].sourceEndUs)
    }

    @Test fun speedIsTheClipsRateAndItsPlaceIsInMovieSeconds() {
        val state = TimelineEditing.setSpeed(EditState(listOf(a, b)), 0, 2.0)!!
        val plan = RenderPlan.of(state)
        assertEquals(2.0, plan.pictures[0].rate, tiny)
        assertEquals(2.0, plan.pictures[0].duration, tiny)
        assertEquals(0L to 4_000_000L, plan.pictures[0].sourceStartUs to plan.pictures[0].sourceEndUs)
        assertEquals(2.0, plan.pictures[1].start, tiny)
        assertEquals(6.0, plan.duration, tiny)
    }

    @Test fun whereAClipsPictureLiesInTheFrame() {
        val canvas = Size(1920.0, 1080.0)
        assertEquals(Rect(0.0, 0.0, 1920.0, 1080.0), RenderPlan.content(canvas, Size(3840.0, 2160.0), cropped = false))
        // Upright footage in a wide frame has bars beside it.
        assertEquals(Rect(656.25, 0.0, 607.5, 1080.0), RenderPlan.content(canvas, Size(1080.0, 1920.0), cropped = false))
        // A crop fills the frame, and so does footage of unknown size.
        assertEquals(Rect(0.0, 0.0, 1920.0, 1080.0), RenderPlan.content(canvas, Size(1080.0, 1920.0), cropped = true))
        assertEquals(Rect(0.0, 0.0, 1920.0, 1080.0), RenderPlan.content(canvas, null, cropped = false))
    }

    // MARK: Sound

    @Test fun eachSoundTrackIsASequenceOfItsOwnThatEndsWithThePicture() {
        val music = AudioItem(id = "M", sourceID = "X", start = 1.0, sourceStart = 0.0, sourceEnd = 10.0, track = 0)
        val voice = AudioItem(id = "V", sourceID = "Y", start = 2.0, sourceStart = 1.0, sourceEnd = 3.0, track = 1)
        val second = AudioItem(id = "W", sourceID = "Y", start = 5.0, sourceStart = 0.5, sourceEnd = 1.5, track = 1)
        // Begins a hair before the picture ends: left out.
        val late = AudioItem(id = "L", sourceID = "Y", start = 7.995, sourceStart = 0.0, sourceEnd = 1.0, track = 1)
        val plan = RenderPlan.of(EditState(listOf(a, b), audio = listOf(music, voice, second, late)))
        assertEquals(8.0, plan.duration, tiny)
        assertEquals(
            listOf(
                // The music runs past the end of the picture and is cut there.
                RenderPlan.Sequence(Role.Soundtrack, 0, listOf(Entry.Gap(0.0, 1_000_000), Entry.Sound(music, 1.0, 7.0, 0, 7_000_000))),
                RenderPlan.Sequence(
                    Role.Soundtrack, 1,
                    listOf(
                        Entry.Gap(0.0, 2_000_000), Entry.Sound(voice, 2.0, 2.0, 1_000_000, 3_000_000), Entry.Gap(4.0, 1_000_000),
                        Entry.Sound(second, 5.0, 1.0, 500_000, 1_500_000), Entry.Gap(6.0, 2_000_000),
                    ),
                ),
            ),
            plan.soundtracks,
        )
        // The preview holds the sound that plays, or the next to come, and hears nothing between them.
        assertEquals(voice, plan.cued(1, 0.0))
        assertEquals(voice, plan.cued(1, 3.0))
        assertEquals(second, plan.cued(1, 4.5))
        assertEquals(late, plan.cued(1, 6.5))
        assertNull(plan.cued(0, 11.5))
        assertEquals(0.0, plan.trackLevel(1, 4.5), tiny)
        assertEquals(1.0, plan.trackLevel(1, 3.0), tiny)
        // A track with nothing on it has no sequence.
        assertEquals(listOf(0), RenderPlan.of(EditState(listOf(a), audio = listOf(music))).soundtracks.map { it.number })
    }

    @Test fun cleanedSoundPlaysFromSequencesOfItsOwnInPlaceOfTheRecordedOne() {
        val clean = ClipAudio(denoise = 0.6)
        val state = joined(EditState(listOf(a.copy(audio = clean), b, c.copy(audio = clean))), 1, TransitionSettings(id = "T", duration = 1.0))
        val plan = RenderPlan.of(state)
        assertEquals(listOf(true, false, true), plan.pictures.map { it.soundRemoved })
        assertEquals(
            listOf(
                RenderPlan.Sequence(Role.Cleaned, 0, listOf(Entry.Clip(0), Entry.Gap(4.0, 7_000_000))),
                RenderPlan.Sequence(Role.Cleaned, 1, listOf(Entry.Gap(0.0, 7_000_000), Entry.Clip(2))),
            ),
            plan.cleaned,
        )
        // A clip whose cleaned sound is not at hand plays its recorded sound.
        val partly = RenderPlan.of(state, cleaned = setOf(2))
        assertEquals(listOf(false, false, true), partly.pictures.map { it.soundRemoved })
        assertEquals(listOf(1), partly.cleaned.map { it.number })
        assertTrue(RenderPlan.of(state, cleaned = emptySet()).cleaned.isEmpty())
    }

    @Test fun levelsAreThoseOfTheClipsAndTheSoundsAtEveryMoment() {
        val state = joined(EditState(listOf(a.copy(audio = ClipAudio(volumeDB = 6.0, fadeIn = 1.0)), b)), 1, TransitionSettings(id = "T", duration = 1.0))
        val plan = RenderPlan.of(state)
        for (step in 0..70) {
            val time = step / 10.0
            for (index in 0..1) assertEquals(TimelineEditing.soundLevel(state.clips, index, time - plan.pictures[index].start), plan.clipLevel(index, time - plan.pictures[index].start), 0.0)
        }
        // Half way through the dissolve each deck plays its clip at half its level.
        assertEquals(ClipAudio(volumeDB = 6.0).gain / 2, plan.deckLevel(0, 3.5), 1e-6)
        assertEquals(0.5, plan.deckLevel(1, 3.5), 1e-6)
        // A deck with no clip at that moment is silent.
        assertEquals(0.0, plan.deckLevel(1, 1.0), tiny)
        assertEquals(0.0, plan.deckLevel(0, 5.0), tiny)
        assertEquals(1 to 1, plan.playing(1, 5.0))
        assertNull(plan.playing(0, 5.0))
        assertEquals(0 to 0, plan.playing(0, 3.5))
    }

    // MARK: Titles and captions

    @Test fun titlesAndCaptionsAreSpansOfTheMovie() {
        val title = TextOverlay(id = "X", text = "Hello", start = 2.0, end = 6.0)
        val words = listOf(SubtitleWord("W1", "P", "one", 1.5, 2.0), SubtitleWord("W2", "P", "two", 2.0, 2.5))
        val state = EditState(listOf(a, c.copy(texts = listOf(title), subtitleWords = words)), subtitles = SubtitleSettings(id = "SUB"))
        val plan = RenderPlan.of(state)
        // The clip plays footage 1 to 5 from second 4: the title shows over footage 2 to 5.
        assertEquals(listOf(RenderPlan.TitleSpan(1, title, 5.0, 8.0)), plan.titles)
        assertEquals(listOf(title to 1.0), plan.titlesAt(6.0))
        assertTrue(plan.titlesAt(4.5).isEmpty())
        assertEquals(SubtitleEditing.cues(state), plan.captions)
        assertEquals("one two", plan.captionsAt(4.75).single().text)
        assertEquals("SUB", plan.subtitles?.id)
        // Words without a look are not captions, and a look without words draws nothing.
        assertTrue(RenderPlan.of(state.copy(subtitles = null)).captions.isEmpty())
        assertNull(RenderPlan.of(EditState(listOf(a), subtitles = SubtitleSettings(id = "SUB"))).subtitles)
    }

    @Test fun theMomentIsTheOneBothRenderersDraw() {
        val state = joined(EditState(listOf(a, b, c)), 2, TransitionSettings(id = "T", style = TransitionStyle.Wipe, duration = 1.0))
        val plan = RenderPlan.of(state)
        for (step in 0..110) assertEquals(TransitionScene.moment(state.clips, step / 10.0), plan.moment(step / 10.0))
        val join = plan.transitions.single()
        assertEquals(TransitionScene.frameRate(state.clips, join, listOf(60.0, 60.0, 24.0), 30), plan.frameRate(join, listOf(60.0, 60.0, 24.0), 30), 0.0)
    }
}
