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
        // Half way through the dissolve each clip plays at half its level.
        assertEquals(ClipAudio(volumeDB = 6.0).gain / 2, plan.clipLevel(0, 3.5 - plan.pictures[0].start), 1e-6)
        assertEquals(0.5, plan.clipLevel(1, 3.5 - plan.pictures[1].start), 1e-6)
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
        assertEquals(TransitionScene.frameRate(25), plan.frameRate(25))
        assertEquals(30, plan.frameRate(30))
    }

    // MARK: What an export takes: sections, voices, envelopes

    /** The whole movie as a stretch, in the words of [RenderPlan.sequences]. */
    private fun asEntries(plan: RenderPlan, stretch: RenderPlan.Stretch): List<List<Entry>> = stretch.decks.map { deck ->
        deck.map { part ->
            when (part) {
                is RenderPlan.Part.Black -> Entry.Gap(part.start, part.micros)
                is RenderPlan.Part.Footage -> {
                    val picture = plan.pictures[part.index]
                    assertEquals(picture.start to picture.end, part.start to part.end)
                    assertEquals(picture.sourceStartUs to picture.sourceEndUs, part.sourceStartUs to part.sourceEndUs)
                    Entry.Clip(part.index)
                }
            }
        }
    }

    @Test fun theWholeMovieAsAStretchIsThePictureSequences() {
        val edits = TrackFixtures.all.values + GoldenProjects.all.filter { !it.large }.flatMap { it.project.history }
        var compared = 0
        for (state in edits) {
            val plan = RenderPlan.of(state)
            if (plan.pictures.any { it.sourceEndUs <= it.sourceStartUs }) continue
            assertEquals(plan.sequences.map { it.entries }, asEntries(plan, plan.stretch(0.0, plan.duration)))
            compared++
        }
        assertTrue(compared > 100)
    }

    @Test fun aStretchCutsTheClipsItHoldsPartOf() {
        val state = joined(EditState(listOf(a, TimelineEditing.setSpeed(EditState(listOf(b)), 0, 2.0)!!.clips[0], c)), 1, TransitionSettings(id = "T", duration = 1.0))
        val plan = RenderPlan.of(state)
        // A plays 0 to 4, B at double speed 3 to 5, C 5 to 9.
        assertEquals(listOf(0.0, 3.0, 5.0), plan.pictures.map { it.start })
        val stretch = plan.stretch(3.5, 6.25)
        assertEquals(3.5 to 6.25, stretch.from to stretch.to)
        assertEquals(
            listOf(
                listOf(RenderPlan.Part.Footage(0, 3.5, 4.0, 3_500_000, 4_000_000), RenderPlan.Part.Black(4.0, 2_250_000)),
                // Half a second into a clip at double speed is a second into its footage.
                listOf(RenderPlan.Part.Footage(1, 3.5, 5.0, 1_000_000, 4_000_000), RenderPlan.Part.Footage(2, 5.0, 6.25, 1_000_000, 2_250_000)),
            ),
            stretch.decks,
        )
        // A deck with nothing in the stretch is black for all of it, and a sliver of a clip holds no frame.
        assertEquals(listOf(RenderPlan.Part.Black(5.0, 1_000_000)), plan.stretch(5.0, 6.0).decks[0])
        assertEquals(listOf(RenderPlan.Part.Black(4.0 - 1e-6, 1_000_001)), plan.stretch(4.0 - 1e-6, 5.0).decks[0])
        // A cut is never later than the second it stands for, and a part always has footage.
        val third = RenderPlan.of(EditState(listOf(a))).stretch(1.0 / 3, 2.0 / 3).decks.single().single() as RenderPlan.Part.Footage
        assertEquals(333_333L to 666_666L, third.sourceStartUs to third.sourceEndUs)
    }

    @Test fun aSectionIsDrawnFromALittleBeforeItToALittleAfterIt() {
        val plan = RenderPlan.of(EditState(listOf(a, b, c, a.copy(id = "D"), b.copy(id = "E"), c.copy(id = "F"), a.copy(id = "G"))))
        assertEquals(28.0, plan.duration, tiny)
        // The first twelve seconds at 30 frames a second, and half a second after them.
        val first = plan.section(0, 360, 30)
        assertEquals(listOf(0, 360, 0, 375), listOf(first.first, first.count, first.opens, first.closes))
        assertEquals(0.0 to 12.5, first.stretch.from to first.stretch.to)
        val second = plan.section(360, 360, 30)
        assertEquals(345 to 735, second.opens to second.closes)
        assertEquals(11.5 to 24.5, second.stretch.from to second.stretch.to)
        // The last section ends with the movie.
        val last = plan.section(720, 120, 30)
        assertEquals(705 to 840, last.opens to last.closes)
        assertEquals(28.0, last.stretch.to, tiny)
        assertEquals(RenderPlan.Part.Footage(6, 24.0, 28.0, 0, 4_000_000), last.stretch.decks.single().last())
    }

    @Test fun aSectionThatBeginsInsideAFreezeIsDrawnFromBeforeTheFrameItHolds() {
        // A plays to second 4 and its last frame is held for twenty seconds.
        val state = joined(EditState(listOf(a, c)), 1, TransitionSettings(id = "T", style = TransitionStyle.Still, duration = 20.0))
        val plan = RenderPlan.of(state)
        val freeze = plan.freezes.single()
        assertEquals(4.0 to 24.0, freeze.from to freeze.until)
        val section = plan.section(360, 360, 30)
        // Half a second before the frame the freeze shows, the last of the clip.
        assertEquals(Math.floor((freeze.shown - 0.5) * 30).toInt(), section.opens)
        assertEquals(104, section.opens)
        assertTrue(section.stretch.decks[0].first() is RenderPlan.Part.Footage)
        // A section that begins after the freeze is drawn from half a second before itself.
        assertEquals(735, plan.section(750, 120, 30).opens)
    }

    @Test fun everyClipThatSoundsPlaysFromASequenceOfSoundAlone() {
        val quiet = b.copy(sourceID = "Q")
        val state = joined(EditState(listOf(a, quiet, c)), 1, TransitionSettings(id = "T", duration = 1.0))
        val plan = RenderPlan.of(state)
        // The clips of each chain, on the sequence of its deck.
        assertEquals(
            listOf(
                RenderPlan.Sequence(Role.Voice, 0, listOf(Entry.Clip(0), Entry.Gap(4.0, 7_000_000))),
                RenderPlan.Sequence(Role.Voice, 1, listOf(Entry.Gap(0.0, 3_000_000), Entry.Clip(1), Entry.Clip(2))),
            ),
            plan.voices { true },
        )
        // A recording without sound is left out, and a sequence with nothing in it is not made.
        assertEquals(
            listOf(
                RenderPlan.Sequence(Role.Voice, 0, listOf(Entry.Clip(0), Entry.Gap(4.0, 7_000_000))),
                RenderPlan.Sequence(Role.Voice, 1, listOf(Entry.Gap(0.0, 7_000_000), Entry.Clip(2))),
            ),
            plan.voices { it == "S" },
        )
        assertEquals(listOf(1), plan.voices { it == "Q" }.map { it.number })
        assertTrue(plan.voices { false }.isEmpty())
        // Cleaned sound is heard whatever the recording says, in place of the recorded one.
        val cleaned = RenderPlan.of(state.copy(clips = state.clips.map { if (it.id == "B") it.copy(audio = ClipAudio(denoise = 0.6)) else it }))
        assertEquals(listOf(RenderPlan.Sequence(Role.Voice, 1, listOf(Entry.Gap(0.0, 3_000_000), Entry.Clip(1), Entry.Gap(7.0, 4_000_000)))), cleaned.voices { false })
        assertEquals(cleaned.cleaned.map { it.entries }, cleaned.voices { false }.map { it.entries })
    }

    @Test fun theEnvelopesAreThoseOfTheEditSampleBySample() {
        val music = AudioItem(id = "M", sourceID = "X", start = 1.0, sourceStart = 0.0, sourceEnd = 10.0, track = 0, audio = ClipAudio(volumeDB = -6.0, fadeIn = 1.0))
        val state = joined(EditState(listOf(a.copy(audio = ClipAudio(volumeDB = 6.0, fadeOut = 1.0)), b, c.copy(audio = ClipAudio(muted = true))), audio = listOf(music)), 1, TransitionSettings(id = "T", duration = 1.0))
        val plan = RenderPlan.of(state)
        assertEquals(SoundEnvelope.segments(state), plan.envelopes())
        assertEquals(SoundEnvelope.clip(state.clips, 0), plan.clipEnvelope(0))
        // A muted clip is silence.
        assertNull(plan.clipEnvelope(2))
        // The music is cut off where the picture ends, and fades there as at a cut.
        assertEquals(SoundEnvelope.item(state.audio!!, music, state.duration), plan.soundEnvelope(music))
        assertEquals(plan.duration, plan.soundEnvelope(music)!!.audibleEnd, tiny)
        for (golden in GoldenProjects.all.filter { !it.large }) for (step in golden.project.history) assertEquals(SoundEnvelope.segments(step), RenderPlan.of(step).envelopes())
    }

    @Test fun theTitlesToDrawAreThoseOfTheClipsWithTheirPoses() {
        val title = TextOverlay(id = "X", text = "Hello", start = 2.0, end = 6.0, motion = TitleMotion(entrance = TitleMove.Drift, exit = TitleMove.Fade, inSeconds = 1.0, outSeconds = 1.0))
        val state = EditState(listOf(a, c.copy(texts = listOf(title))))
        val plan = RenderPlan.of(state)
        assertTrue(plan.titled)
        assertFalse(RenderPlan.of(EditState(listOf(a, c))).titled)
        val ranges = TitleMotionRanges(state.clips)
        for (step in 0..90) assertEquals(ranges.shown(state.clips, step / 10.0), plan.titlesShown(step / 10.0))
        assertEquals(1, plan.titlesShown(5.25).size)
        assertTrue(plan.titlesShown(5.25).single().pose != TitleMotion.Pose())
    }

    @Test fun whatADeckOfThePreviewShowsAndWaitsFor() {
        val state = joined(joined(EditState(listOf(a, b, c)), 1, TransitionSettings(id = "T", duration = 1.0)), 2, TransitionSettings(id = "U", style = TransitionStyle.Still, duration = 1.0))
        val plan = RenderPlan.of(state)
        // A 0 to 4 on deck 0, B 3 to 7 on deck 1, a second of B's last frame, C 8 to 12 on deck 0.
        assertEquals(listOf(0, 1, 0), plan.deckOf)
        assertEquals(0, plan.onDeck(0, 3.5)?.index)
        assertEquals(1, plan.onDeck(1, 3.5)?.index)
        assertNull(plan.onDeck(0, 5.0))
        assertNull(plan.onDeck(1, 7.5))
        assertEquals(1, plan.held(1, 7.5)?.index)
        assertNull(plan.held(0, 7.5))
        assertNull(plan.held(1, 5.0))
        // Deck 0 waits at its next chain; deck 1 has none to come.
        assertEquals(2, plan.coming(0, 5.0))
        assertNull(plan.coming(1, 5.0))
        assertEquals(1, plan.coming(1, 1.0))
        // The clock is the chain that began last, and nobody's during the freeze.
        assertEquals(0, plan.leading(2.0))
        assertEquals(1, plan.leading(3.5))
        assertNull(plan.leading(7.5))
        assertEquals(2, plan.leading(8.0))
    }
}
