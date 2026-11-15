package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The stops of the previous and next buttons.
 *
 * Tests marked TRANSLATED carry over an expectation of the iPhone app's
 * timelineNavigationTests (Tests/TrackTimelineTests.swift:732-807), rebuilt
 * on this port's model; the line of the original expectation is given. Where
 * the original uses a sound effect, which this port does not have, the clip's
 * level stands in for it: both are a treatment of the sound over the whole
 * piece. The others are this port's own, from the formulas of
 * YouRec/TimelineNavigation.swift.
 */
class TimelineNavigationTest {
    private fun clip(start: Double, end: Double) = TimelineClip(sourceID = "source", start = start, end = end)
    private val title = TextOverlay(text = "Title", start = 8.0, end = 10.0)

    private fun split(state: EditState, vararg times: Double): EditState = times.fold(state) { edit, time -> TimelineEditing.split(edit, time)!! }

    /** One 18 s take with a title from 8 to 10 and its sound raised, split at 6 and at 12. TrackTimelineTests.swift:733-741. */
    private fun edit(): EditState =
        split(EditState(listOf(clip(0.0, 18.0).copy(texts = listOf(title), audio = ClipAudio(volumeDB = 2.0)))), 6.0, 12.0)

    private fun with(state: EditState, index: Int, change: (TimelineClip) -> TimelineClip) =
        state.copy(clips = state.clips.toMutableList().also { it[index] = change(it[index]) })

    private fun has(time: Double, stops: List<Double>) = stops.any { abs(it - time) <= 1.0 / 30 }

    // MARK: The two lists

    /** TRANSLATED TrackTimelineTests.swift:743. */
    @Test fun aTapStepsThroughCutsAndTitleEdges() {
        assertEquals(listOf(0.0, 6.0, 8.0, 10.0, 12.0, 18.0), TimelineNavigation.of(edit()).cutsAndChanges)
    }

    /** TRANSLATED TrackTimelineTests.swift:744. */
    @Test fun aLongPressSkipsSplitsOfTheSameTreatment() {
        assertEquals(listOf(0.0, 8.0, 10.0, 18.0), TimelineNavigation.of(edit()).changes)
    }

    @Test fun anEmptyEditHasOnlyItsStart() {
        val navigation = TimelineNavigation.of(EditState())
        assertEquals(listOf(0.0), navigation.changes)
        assertEquals(listOf(0.0), navigation.cutsAndChanges)
        assertNull(navigation.next(0.0))
        assertNull(navigation.previous(0.0))
    }

    @Test fun aSingleUntouchedClipHasOnlyItsEnds() {
        val navigation = TimelineNavigation.of(EditState(listOf(clip(0.0, 5.0))))
        assertEquals(listOf(0.0, 5.0), navigation.changes)
        assertEquals(listOf(0.0, 5.0), navigation.cutsAndChanges)
    }

    /** TRANSLATED TrackTimelineTests.swift:805. */
    @Test fun derivingTheStopsLeavesTheEditAsItWas() {
        val state = edit()
        val copy = state.copy()
        val first = TimelineNavigation.of(state)
        assertEquals(copy, state)
        assertEquals(first.changes, TimelineNavigation.of(state).changes)
    }

    // MARK: Previous and next

    /** TRANSLATED TrackTimelineTests.swift:745. */
    @Test fun tapAndLongPressGoToDifferentStops() {
        val navigation = TimelineNavigation.of(edit())
        assertEquals(6.0, navigation.destination(0.0, forward = true)!!, 0.0)
        assertEquals(8.0, navigation.destination(0.0, forward = true, effectsOnly = true)!!, 0.0)
        assertEquals(6.0, navigation.next(0.0)!!, 0.0)
        assertEquals(8.0, navigation.next(0.0, effectsOnly = true)!!, 0.0)
    }

    /** TRANSLATED TrackTimelineTests.swift:746. */
    @Test fun previousReachesTheStartOfTheSectionThenTheOneBefore() {
        val navigation = TimelineNavigation.of(edit())
        assertEquals(8.0, navigation.destination(9.0, forward = false)!!, 0.0)
        assertEquals(6.0, navigation.destination(8.0, forward = false)!!, 0.0)
        assertEquals(6.0, navigation.previous(8.0)!!, 0.0)
    }

    /** TRANSLATED TrackTimelineTests.swift:747. */
    @Test fun aPlayheadAHairBeforeAStopIsOnIt() {
        assertEquals(10.0, TimelineNavigation.of(edit()).destination(7.999, forward = true)!!, 0.0)
    }

    /** TRANSLATED TrackTimelineTests.swift:748. */
    @Test fun theEndsNeverWrap() {
        val navigation = TimelineNavigation.of(edit())
        assertNull(navigation.destination(18.0, forward = true))
        assertNull(navigation.destination(0.0, forward = false))
        assertNull(navigation.destination(18.0, forward = true, effectsOnly = true))
        assertNull(navigation.destination(0.0, forward = false, effectsOnly = true))
    }

    /** TimelineNavigation.swift:163-164: closer than 1/120 s to a stop is on it, on either side. */
    @Test fun theToleranceIsUnderHalfAFrame() {
        val navigation = TimelineNavigation.of(edit())
        assertEquals(1.0 / 120, TimelineNavigation.TOLERANCE, 0.0)
        // Within the tolerance of 8: the stop is passed over in both directions.
        assertEquals(10.0, navigation.next(8.0 - 0.008)!!, 0.0)
        assertEquals(10.0, navigation.next(8.0 + 0.008)!!, 0.0)
        assertEquals(6.0, navigation.previous(8.0 + 0.008)!!, 0.0)
        assertEquals(6.0, navigation.previous(8.0 - 0.008)!!, 0.0)
        // Outside it: 8 is a stop to go to.
        assertEquals(8.0, navigation.next(8.0 - 0.009)!!, 0.0)
        assertEquals(8.0, navigation.previous(8.0 + 0.009)!!, 0.0)
    }

    @Test fun betweenStopsBothDirectionsGoToTheNeighbours() {
        val navigation = TimelineNavigation.of(edit())
        assertEquals(12.0, navigation.next(11.0)!!, 0.0)
        assertEquals(10.0, navigation.previous(11.0)!!, 0.0)
        assertEquals(18.0, navigation.next(11.0, effectsOnly = true)!!, 0.0)
        assertEquals(10.0, navigation.previous(11.0, effectsOnly = true)!!, 0.0)
        assertEquals(0.0, navigation.previous(7.0, effectsOnly = true)!!, 0.0)
        assertEquals(18.0, navigation.next(17.0)!!, 0.0)
        assertEquals(0.0, navigation.previous(3.0)!!, 0.0)
    }

    // MARK: Subtitles

    private fun captioned(words: List<SubtitleWord>): EditState =
        split(EditState(listOf(clip(0.0, 18.0).copy(texts = listOf(title), audio = ClipAudio(volumeDB = 2.0), subtitleWords = words)), subtitles = SubtitleSettings()), 6.0, 12.0)

    private val words = listOf(
        SubtitleWord(text = "First.", start = 2.0, end = 3.0),
        SubtitleWord(text = "Second.", start = 7.0, end = 8.0),
        SubtitleWord(text = "Last.", start = 14.0, end = 16.0),
    )

    /** TRANSLATED TrackTimelineTests.swift:750-753. */
    @Test fun subtitlesAddOnlyTheirFirstStart() {
        val navigation = TimelineNavigation.of(captioned(words))
        assertEquals(listOf(0.0, 2.0, 8.0, 10.0, 18.0), navigation.changes)
        assertEquals(listOf(0.0, 2.0, 6.0, 8.0, 10.0, 12.0, 18.0), navigation.cutsAndChanges)
    }

    /** TRANSLATED TrackTimelineTests.swift:754-755. */
    @Test fun groupingCaptionsDifferentlyChangesNoStop() {
        val onePhrase = words.map { it.copy(phraseID = "phrase") }
        assertEquals(TimelineNavigation.of(captioned(words)).changes, TimelineNavigation.of(captioned(onePhrase)).changes)
    }

    /** TRANSLATED TrackTimelineTests.swift:756-757. */
    @Test fun removingSubtitlesRemovesTheirStop() {
        assertEquals(TimelineNavigation.of(edit()).changes, TimelineNavigation.of(SubtitleEditing.remove(captioned(words))).changes)
    }

    @Test fun wordsWithoutSubtitleSettingsMakeNoStop() {
        assertEquals(TimelineNavigation.of(edit()).changes, TimelineNavigation.of(captioned(words).copy(subtitles = null)).changes)
    }

    // MARK: What counts as a change

    /** TRANSLATED TrackTimelineTests.swift:758-760. */
    @Test fun theSameTreatmentWithOtherSettingsCounts() {
        val changed = with(edit(), 1) { it.copy(audio = ClipAudio(volumeDB = 4.0)) }
        assertEquals(listOf(0.0, 6.0, 8.0, 10.0, 12.0, 18.0), TimelineNavigation.of(changed).changes)
    }

    /** TRANSLATED TrackTimelineTests.swift:761-763 and 875-878. */
    @Test fun aNewIdentityAloneIsNotAChange() {
        val colored = EditState(listOf(clip(0.0, 4.0), clip(4.0, 8.0)).map { it.copy(color = ColorSettings(look = ColorLook.Warm, amount = 0.5)) })
        assertTrue(colored.clips[0].color!!.id != colored.clips[1].color!!.id)
        val navigation = TimelineNavigation.of(colored)
        assertEquals(listOf(0.0, 8.0), navigation.changes)
        assertEquals(listOf(0.0, 4.0, 8.0), navigation.cutsAndChanges)

        val cropped = EditState(listOf(clip(0.0, 4.0), clip(4.0, 8.0)).map { it.copy(crop = CropSettings(zoom = 2.0)) })
        assertEquals(listOf(0.0, 8.0), TimelineNavigation.of(cropped).changes)
    }

    @Test fun differentColorOrCropOnNeighboursIsAChangeAtTheCut() {
        val colored = EditState(listOf(clip(0.0, 4.0).copy(color = ColorSettings(look = ColorLook.Warm)), clip(4.0, 8.0).copy(color = ColorSettings(look = ColorLook.Mono))))
        assertEquals(listOf(0.0, 4.0, 8.0), TimelineNavigation.of(colored).changes)
        val cropped = EditState(listOf(clip(0.0, 4.0).copy(crop = CropSettings(zoom = 2.0)), clip(4.0, 8.0)))
        assertEquals(listOf(0.0, 4.0, 8.0), TimelineNavigation.of(cropped).changes)
    }

    /** TimelineNavigation.swift:98: a color counts only with a look and an amount above zero. Like TrackTimelineTests.swift:767-769 for a zero-strength effect. */
    @Test fun aLookAtZeroOrNoLookMakesNoStop() {
        val plain = EditState(listOf(clip(0.0, 4.0), clip(4.0, 8.0)))
        val zero = with(plain, 0) { it.copy(color = ColorSettings(look = ColorLook.Warm, amount = 0.0)) }
        assertEquals(listOf(0.0, 8.0), TimelineNavigation.of(zero).changes)
        val balanceOnly = with(plain, 0) { it.copy(color = ColorSettings(exposure = 1.0)) }
        assertEquals(listOf(0.0, 8.0), TimelineNavigation.of(balanceOnly).changes)
    }

    /** TimelineNavigation.swift:97: speed 1 is no treatment. */
    @Test fun speedOneMakesNoStop() {
        val state = EditState(listOf(clip(0.0, 4.0).copy(speed = SpeedSettings(rate = 1.0)), clip(4.0, 8.0)))
        assertEquals(listOf(0.0, 8.0), TimelineNavigation.of(state).changes)
        val fast = EditState(listOf(clip(0.0, 4.0).copy(speed = SpeedSettings(rate = 2.0)), clip(4.0, 8.0)))
        assertEquals(listOf(0.0, 2.0, 6.0), TimelineNavigation.of(fast).changes)
    }

    /** TimelineNavigation.swift:102: a title without text is not shown. */
    @Test fun anEmptyTitleMakesNoStop() {
        val state = EditState(listOf(clip(0.0, 18.0).copy(texts = listOf(title.copy(text = "")))))
        assertEquals(listOf(0.0, 18.0), TimelineNavigation.of(state).changes)
    }

    @Test fun aTitleSplitInTwoIsStillOneTitle() {
        val state = split(EditState(listOf(clip(0.0, 18.0).copy(texts = listOf(title)))), 9.0)
        val navigation = TimelineNavigation.of(state)
        assertEquals(listOf(0.0, 8.0, 10.0, 18.0), navigation.changes)
        assertEquals(listOf(0.0, 8.0, 9.0, 10.0, 18.0), navigation.cutsAndChanges)
    }

    @Test fun aTitleMovedOrRestyledIsAnotherTitle() {
        val state = split(EditState(listOf(clip(0.0, 18.0).copy(texts = listOf(title)))), 9.0)
        val restyled = with(state, 1) { piece -> piece.copy(texts = piece.texts!!.map { it.copy(size = 0.1) }) }
        assertEquals(listOf(0.0, 8.0, 9.0, 10.0, 18.0), TimelineNavigation.of(restyled).changes)
    }

    /** TRANSLATED TrackTimelineTests.swift:777-779. */
    @Test fun aTitleFollowsItsFootageThroughASpeedChange() {
        val fast = clip(0.0, 18.0).copy(speed = SpeedSettings(rate = 2.0), texts = listOf(title))
        assertEquals(listOf(0.0, 4.0, 5.0, 9.0), TimelineNavigation.of(EditState(listOf(fast))).changes)
    }

    /** TRANSLATED TrackTimelineTests.swift:780-783. Here a removed pause leaves two clips, so it is a cut like any other. */
    @Test fun aRemovedPauseIsACutNotAChange() {
        val state = TimelineEditing.removeIntervals(EditState(listOf(clip(0.0, 18.0).copy(texts = listOf(title)))), 0, listOf(SourceInterval(4.0, 6.0)))!!
        val navigation = TimelineNavigation.of(state)
        assertEquals(listOf(0.0, 6.0, 8.0, 16.0), navigation.changes)
        assertTrue(4.0 in navigation.cutsAndChanges)
    }

    /** TRANSLATED TrackTimelineTests.swift:789-792, formula at TimelineNavigation.swift:16-18. */
    @Test fun aChangeInsideAFrameLandsOnTheFirstFrameShowingIt() {
        val state = EditState(listOf(clip(0.0, 18.0).copy(texts = listOf(title.copy(start = 8.001, end = 10.001)))))
        val changes = TimelineNavigation.of(state).changes
        assertEquals(241.0 / 30, changes[1], 0.00001)
        assertEquals(301.0 / 30, changes[2], 0.00001)
    }

    @Test fun aChangeShorterThanAFrameIsIgnored() {
        val state = EditState(listOf(clip(0.0, 18.0).copy(texts = listOf(title.copy(start = 8.001, end = 8.02)))))
        assertEquals(listOf(0.0, 18.0), TimelineNavigation.of(state).changes)
    }

    // MARK: Sound

    /** TimelineNavigation.swift:114-121. */
    @Test fun muteLevelAndFadesAreChanges() {
        val muted = EditState(listOf(clip(0.0, 4.0).copy(audio = ClipAudio(muted = true)), clip(4.0, 8.0)))
        assertEquals(listOf(0.0, 4.0, 8.0), TimelineNavigation.of(muted).changes)
        val bothMuted = EditState(listOf(clip(0.0, 4.0), clip(4.0, 8.0)).map { it.copy(audio = ClipAudio(muted = true, volumeDB = -6.0)) })
        assertEquals(listOf(0.0, 8.0), TimelineNavigation.of(bothMuted).changes)
        val faded = EditState(listOf(clip(0.0, 10.0).copy(audio = ClipAudio(fadeIn = 1.0, fadeOut = 2.0))))
        assertEquals(listOf(0.0, 1.0, 8.0, 10.0), TimelineNavigation.of(faded).changes)
        val denoised = EditState(listOf(clip(0.0, 4.0).copy(audio = ClipAudio(denoise = 0.5)), clip(4.0, 8.0)))
        assertEquals(listOf(0.0, 4.0, 8.0), TimelineNavigation.of(denoised).changes)
    }

    /** A fade longer than half its piece is cut to the half, as in [TimelineEditing.soundLevel]. */
    @Test fun aFadeStopsAtHalfThePiece() {
        val faded = EditState(listOf(clip(0.0, 4.0).copy(audio = ClipAudio(fadeIn = 3.0))))
        assertEquals(listOf(0.0, 2.0, 4.0), TimelineNavigation.of(faded).changes)
    }

    /** TRANSLATED TrackTimelineTests.swift:772-776: sound placed on its own has stops on the movie's clock. */
    @Test fun soundOnTheAudioTracksHasItsOwnStops() {
        val music = AudioItem(sourceID = "music", start = 7.0, sourceStart = 0.0, sourceEnd = 6.0, audio = ClipAudio(volumeDB = -3.0))
        val navigation = TimelineNavigation.of(edit().copy(audio = listOf(music)))
        assertTrue(7.0 in navigation.changes && 13.0 in navigation.changes)
        val plain = TimelineNavigation.of(edit().copy(audio = listOf(music.copy(audio = null))))
        assertEquals(listOf(0.0, 8.0, 10.0, 18.0), plain.changes)
        assertEquals(listOf(0.0, 6.0, 7.0, 8.0, 10.0, 12.0, 13.0, 18.0), plain.cutsAndChanges)
    }

    @Test fun soundPastTheEndOfTheMovieStopsAtItsEnd() {
        val music = AudioItem(sourceID = "music", start = 15.0, sourceStart = 0.0, sourceEnd = 30.0, audio = ClipAudio(muted = true))
        val navigation = TimelineNavigation.of(edit().copy(audio = listOf(music)))
        assertEquals(listOf(0.0, 8.0, 10.0, 15.0, 18.0), navigation.changes)
        assertEquals(18.0, navigation.cutsAndChanges.last(), 0.0)
    }

    // MARK: Transitions

    private fun pair() = EditState(listOf(clip(0.0, 8.0), clip(8.0, 18.0)))

    /** TimelineNavigation.swift:142-151 on a fade through black: its two edges are changes, the cut in its middle a stop for a tap. */
    @Test fun aFadeHasItsEdgesAndItsCut() {
        val state = TimelineEditing.setTransition(pair(), 0, 2.0, TransitionStyle.FadeColor)!!
        val navigation = TimelineNavigation.of(state)
        assertEquals(listOf(0.0, 7.0, 9.0, 18.0), navigation.changes)
        assertEquals(listOf(0.0, 7.0, 8.0, 9.0, 18.0), navigation.cutsAndChanges)
    }

    /**
     * A 2 s dissolve out of 8 s into 10 s. Transitions.swift:124-153: the
     * outgoing clip needs all 2 s, the incoming none, so the effect starts at
     * 8 − 2 = 6 (Transitions.swift:373), where the second clip also starts,
     * and the movie is 16 s. Stops: the clips' edges 6 and 8, which sit on the
     * edges of the transition and so are not hidden (TimelineNavigation.swift:56-67);
     * the join at 6 + 2 / 2 = 7 (Transitions.swift:155 and 291), a cut for
     * every style (TimelineNavigation.swift:144); picture and sound from 6 to
     * 8 as changes (TimelineNavigation.swift:145-150). The join is not a change.
     */
    @Test fun aDissolveHasTheEdgesOfItsOverlapAndItsJoin() {
        val state = TimelineEditing.setTransition(pair(), 0, 2.0, TransitionStyle.Dissolve)!!
        assertEquals(16.0, state.duration, 0.0)
        val navigation = TimelineNavigation.of(state)
        assertEquals(listOf(0.0, 6.0, 8.0, 16.0), navigation.changes)
        assertEquals(listOf(0.0, 6.0, 7.0, 8.0, 16.0), navigation.cutsAndChanges)
    }

    /** Every style but the fade through color and the freeze takes its footage as a dissolve does (Transitions.swift:124-126), so its stops are the same. */
    @Test fun aWipeHasTheStopsOfADissolve() {
        val navigation = TimelineNavigation.of(TimelineEditing.setTransition(pair(), 0, 2.0, TransitionStyle.Wipe)!!)
        assertEquals(listOf(0.0, 6.0, 8.0, 16.0), navigation.changes)
        assertEquals(listOf(0.0, 6.0, 7.0, 8.0, 16.0), navigation.cutsAndChanges)
    }

    /**
     * A 1 s freeze: the last frame of the first clip is held from 8 to 9 and
     * the second clip starts after it (Transitions.swift:120, 126, 145-153),
     * so the movie is 19 s. The join is the end of the freeze
     * (Transitions.swift:155), where the second clip starts.
     */
    @Test fun aFreezeHasItsTwoEnds() {
        val state = TimelineEditing.setTransition(pair(), 0, 1.0, TransitionStyle.Still)!!
        assertEquals(19.0, state.duration, 0.0)
        val navigation = TimelineNavigation.of(state)
        assertEquals(listOf(0.0, 8.0, 9.0, 19.0), navigation.changes)
        assertEquals(listOf(0.0, 8.0, 9.0, 19.0), navigation.cutsAndChanges)
    }

    /**
     * A 2 s fade through color holding the color for 1 s. Transitions.swift:121-153:
     * the first clip gives 1 s and ends at 8, the color holds, the second clip
     * starts at 9; the effect runs from 7 to 10 and the movie is 19 s. Both
     * clip edges are strictly inside the transition, so they are not stops
     * (TimelineNavigation.swift:56-67); the join at 7 + 3 / 2 = 8.5
     * (Transitions.swift:155) is the one a tap reaches. The sound goes down
     * from 7 to 8 and comes up from 9 to 10: one treatment from 7 to 10, the
     * silence in it is no stop (TimelineNavigation.swift:146-150).
     */
    @Test fun aFadeHoldingItsColorHidesTheClipEdgesInsideIt() {
        val state = TimelineEditing.setJoin(pair(), 1, TransitionSettings(style = TransitionStyle.FadeColor, duration = 2.0, hold = 1.0))!!
        assertEquals(19.0, state.duration, 0.0)
        assertEquals(9.0, TimelineEditing.clipStart(state.clips, 1), 0.0)
        val navigation = TimelineNavigation.of(state)
        assertEquals(listOf(0.0, 7.0, 10.0, 19.0), navigation.changes)
        assertEquals(listOf(0.0, 7.0, 8.5, 10.0, 19.0), navigation.cutsAndChanges)
    }

    /** A treatment of a clip still ends where the clip does, also inside a transition (TimelineNavigation.swift:94-95): only the cut is hidden. */
    @Test fun aTreatmentEndingInsideATransitionIsStillAChange() {
        val cropped = with(pair(), 0) { it.copy(crop = CropSettings(zoom = 2.0)) }
        val state = TimelineEditing.setJoin(cropped, 1, TransitionSettings(style = TransitionStyle.FadeColor, duration = 2.0, hold = 1.0))!!
        assertEquals(listOf(0.0, 7.0, 8.0, 10.0, 19.0), TimelineNavigation.of(state).changes)
    }

    /**
     * A 2 s fade out of a 1 s clip into a 10 s one. Between two clips a fade
     * takes half its length from each (Transitions.swift:125), 1 s here, which
     * the short clip has: it fits as entered (Transitions.swift:359-368). The
     * effect starts at 1 − 1 = 0 and ends at 2; the movie stays 11 s. The
     * start of the effect is the start of the movie; the join is at 1.
     */
    @Test fun aFadeTakesAllOfAShortClip() {
        val state = TimelineEditing.setTransition(EditState(listOf(clip(0.0, 1.0), clip(1.0, 11.0))), 0, 2.0, TransitionStyle.FadeColor)!!
        assertEquals(11.0, state.duration, 0.0)
        val navigation = TimelineNavigation.of(state)
        assertEquals(listOf(0.0, 2.0, 11.0), navigation.changes)
        assertEquals(listOf(0.0, 1.0, 2.0, 11.0), navigation.cutsAndChanges)
    }

    /**
     * A 2 s dissolve out of a 1 s clip does not fit: it needs 2 s of the
     * first clip (Transitions.swift:124, 153). The largest factor that fits is
     * one half (Transitions.swift:213-222 and 359-368), so it lasts 1 s, from
     * 0 to 1 with its join at 0.5, and the movie is 10 s.
     */
    @Test fun aDissolveShortenedToFitHasTheStopsOfWhatIsLeft() {
        val state = TimelineEditing.setTransition(EditState(listOf(clip(0.0, 1.0), clip(1.0, 11.0))), 0, 2.0, TransitionStyle.Dissolve)!!
        assertEquals(10.0, state.duration, 0.0)
        val navigation = TimelineNavigation.of(state)
        assertEquals(listOf(0.0, 1.0, 10.0), navigation.changes)
        assertEquals(listOf(0.0, 0.5, 1.0, 10.0), navigation.cutsAndChanges)
    }

    /**
     * TRANSLATED TrackTimelineTests.swift:801-804: an opening whose sound comes
     * up later than its picture. Transitions.swift:133-138: the picture changes
     * from 0 to 2, the sound from 3 to 5, and only the incoming ramp counts
     * (TimelineNavigation.swift:148-149). The join is the start of the movie.
     */
    @Test fun anOpeningHasItsPictureAndItsOwnSoundEdges() {
        val state = TimelineEditing.setJoin(EditState(listOf(clip(0.0, 18.0))), 0, TransitionSettings(duration = 2.0, audioOffset = 3.0, audioBlend = 2.0))!!
        val navigation = TimelineNavigation.of(state)
        assertEquals(listOf(0.0, 2.0, 3.0, 5.0, 18.0), navigation.changes)
        assertEquals(navigation.changes, navigation.cutsAndChanges)
        val plain = TimelineEditing.setJoin(EditState(listOf(clip(0.0, 18.0))), 0, TransitionSettings(style = TransitionStyle.FadeColor, duration = 2.0))!!
        assertEquals(listOf(0.0, 2.0, 18.0), TimelineNavigation.of(plain).cutsAndChanges)
    }

    /** A 2 s fade closing the movie. Transitions.swift:139-143: it takes the last 2 s of the last clip, so it starts at 18 − 2 = 16 and the movie is no longer. */
    @Test fun aCloseHasTheStartOfItsFade() {
        val state = TimelineEditing.setJoin(EditState(listOf(clip(0.0, 18.0))), 1, TransitionSettings(style = TransitionStyle.FadeColor, duration = 2.0))!!
        assertEquals(18.0, state.duration, 0.0)
        val navigation = TimelineNavigation.of(state)
        assertEquals(listOf(0.0, 16.0, 18.0), navigation.changes)
        assertEquals(listOf(0.0, 16.0, 18.0), navigation.cutsAndChanges)
    }

    /**
     * The transition of TrackTimelineTests.swift:794: a 2 s dissolve whose
     * sound starts 1 s before the picture and blends for 3 s. Transitions.swift:148-153:
     * the second clip starts with its sound, at 5; the picture changes from 6
     * to 8 with its join at 7; the movie is 15 s. The sound's start is a stop
     * of its own (TimelineNavigation.swift:146-150).
     */
    @Test fun soundLeadingThePictureHasItsOwnStop() {
        val state = TimelineEditing.setJoin(pair(), 1, TransitionSettings(duration = 2.0, audioOffset = -1.0, audioBlend = 3.0))!!
        assertEquals(15.0, state.duration, 0.0)
        val navigation = TimelineNavigation.of(state)
        assertEquals(listOf(0.0, 5.0, 6.0, 8.0, 15.0), navigation.changes)
        assertEquals(listOf(0.0, 5.0, 6.0, 7.0, 8.0, 15.0), navigation.cutsAndChanges)
    }

    /**
     * The same with the sound 1 s after the picture: the first clip plays on
     * under the second until its sound is gone, at 8, so the picture changes
     * from 4 to 6 and the sound from 5 to 8 (Transitions.swift:148-153). The
     * join at 5 is also where the sound begins to change.
     */
    @Test fun soundFollowingThePictureHasItsOwnStops() {
        val state = TimelineEditing.setJoin(pair(), 1, TransitionSettings(duration = 2.0, audioOffset = 1.0, audioBlend = 3.0))!!
        assertEquals(14.0, state.duration, 0.0)
        val navigation = TimelineNavigation.of(state)
        assertEquals(listOf(0.0, 4.0, 5.0, 6.0, 8.0, 14.0), navigation.changes)
        assertEquals(listOf(0.0, 4.0, 5.0, 6.0, 8.0, 14.0), navigation.cutsAndChanges)
    }

    /** TimelineNavigation.swift:144 and 152-156: the join is a cut, so a long press goes from one edge of a transition to the other. */
    @Test fun aLongPressPassesOverTheJoinOfATransition() {
        val navigation = TimelineNavigation.of(TimelineEditing.setTransition(pair(), 0, 2.0, TransitionStyle.Dissolve)!!)
        assertEquals(6.0, navigation.next(0.0, effectsOnly = true)!!, 0.0)
        assertEquals(8.0, navigation.next(6.0, effectsOnly = true)!!, 0.0)
        assertEquals(6.0, navigation.previous(8.0, effectsOnly = true)!!, 0.0)
        assertEquals(7.0, navigation.next(6.0)!!, 0.0)
        assertEquals(7.0, navigation.previous(8.0)!!, 0.0)
        assertEquals(16.0, navigation.next(8.0, effectsOnly = true)!!, 0.0)
    }

    /**
     * TRANSLATED TrackTimelineTests.swift:793-800, with the original's own
     * settings laid out by [TransitionLayout]: picture and sound edges are
     * stops of their own when the sound leads the picture.
     */
    @Test fun aLaidOutJoinGivesPictureSoundAndJoinStops() {
        val ids = listOf("a", "b")
        val settings = TransitionSettings(nextClipID = "b", duration = 2.0, audioOffset = -1.0, audioBlend = 3.0)
        val sequence = TransitionLayout.sequence(listOf(8.0, 10.0), listOf(null, settings, null), ids)
        val join = sequence.joins.first { it.transition != null }
        val builder = TimelineNavigation.Builder(sequence.duration)
        for (placement in sequence.placements) {
            // TimelineNavigation.swift:56-67: the footage a transition borrows is not a cut.
            builder.cut(placement.start, listOf(join.range)).cut(placement.end, listOf(join.range))
        }
        sequence.joins.forEach { builder.transition(it) }
        val navigation = builder.build()
        assertTrue(has(join.videoRange.start, navigation.changes) && has(join.videoRange.endInclusive, navigation.changes))
        assertTrue(has(join.audioIn.start, navigation.changes) && has(join.audioIn.endInclusive, navigation.changes))
        assertTrue(has(join.time, navigation.cutsAndChanges))
        assertEquals(0.0, navigation.changes.first(), 0.0)
        assertEquals(sequence.duration, navigation.changes.last(), 0.00001)
    }

    // MARK: The builder

    @Test fun aCutInsideAHiddenStretchIsLeftOut() {
        val navigation = TimelineNavigation.Builder(10.0).cut(4.0, listOf(3.0..5.0)).cut(3.0, listOf(3.0..5.0)).cut(6.0).build()
        assertEquals(listOf(0.0, 3.0, 6.0, 10.0), navigation.cutsAndChanges)
        assertEquals(listOf(0.0, 10.0), navigation.changes)
    }

    @Test fun equalTreatmentsMeetingMakeNoStopAndTimesOutsideAreBroughtIn() {
        val navigation = TimelineNavigation.Builder(10.0)
            .change("a", 2.0, 4.0).change("a", 4.0, 6.0).change("b", 8.0, 99.0).change("c", -5.0, 1.0).subtitles(3.0).build()
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0, 6.0, 8.0, 10.0), navigation.changes)
    }

    @Test fun aRecipeIgnoresIdentityAndOmittedFields() {
        val one = TimelineNavigation.recipe("text", TextOverlay.serializer(), title, setOf("start", "end"))
        val other = TimelineNavigation.recipe("text", TextOverlay.serializer(), title.copy(id = newID(), start = 1.0, end = 2.0), setOf("start", "end"))
        assertEquals(one, other)
        assertTrue(one != TimelineNavigation.recipe("text", TextOverlay.serializer(), title.copy(text = "Other"), setOf("start", "end")))
        assertTrue(one != TimelineNavigation.recipe("text", TextOverlay.serializer(), title))
    }
}
