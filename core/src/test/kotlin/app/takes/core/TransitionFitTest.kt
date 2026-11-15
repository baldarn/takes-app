package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fit of a transition is saved, as the original saves it, and each edit
 * does to it what the original does (docs/TRANSITIONS-FIT.md). Swift lines
 * are those of the original at faf1815.
 */
class TransitionFitTest {
    private val tiny = 1e-9
    private fun clip(id: String, end: Double = 4.0, start: Double = 0.0) = TimelineClip(id = id, sourceID = "S", start = start, end = end)
    private fun dissolve(next: String, seconds: Double) = TransitionSettings(id = "J$next", nextClipID = next, duration = seconds)

    private fun layout(state: EditState) = TimelineEditing.layout(state.clips)
    private fun factors(state: EditState) = layout(state).joins.filter { it.transition != null }.map { it.factor }
    private fun starts(state: EditState) = layout(state).placements.map { it.start }
    private fun fits(state: EditState) = state.clips.map { it.transitionFit?.factor }
    private fun assertNumbers(expected: List<Double>, actual: List<Double>, delta: Double = tiny) {
        assertEquals("$expected / $actual", expected.size, actual.size)
        expected.indices.forEach { assertEquals("$expected / $actual", expected[it], actual[it], delta) }
    }

    /** Three 4 s clips with 3 s dissolves at both joins, as a project saved before fits were: no fit on either. */
    private val chain = EditState(listOf(clip("A").copy(transition = dissolve("B", 3.0)), clip("B").copy(transition = dissolve("C", 3.0)), clip("C")))

    /** Two 4 s clips with a 1 s dissolve set between them: the pair of TransitionTests.swift:10-15. */
    private val pair = TimelineEditing.setJoin(EditState(listOf(clip("A"), clip("B"))), 1, TransitionSettings(duration = 1.0))!!

    // MARK: A project without the saved fit

    @Test fun clipsSavedWithoutAFitAreFittedByTheSequenceRule() {
        // Transitions.swift:213-222, the conversion of TrackTimeline.swift:510-534.
        assertNumbers(listOf(2.0 / 3, 2.0 / 3), factors(chain))
        assertNumbers(listOf(0.0, 2.0, 4.0), starts(chain))
        assertEquals(8.0, chain.duration, tiny)
        assertEquals(listOf(null, null, null), fits(chain))
        val fitted = TimelineEditing.fitted(chain.clips)
        assertNumbers(listOf(2.0 / 3, 2.0 / 3), fitted.take(2).map { it.transitionFit!!.factor })
        // Fitted, it lays out as it did, and nothing more is found.
        assertEquals(layout(chain), TimelineEditing.layout(fitted))
        assertSame(fitted, TimelineEditing.fitted(fitted))
        // A fit without its transition means nothing, and one on a transition out of play is left alone.
        val stray = listOf(clip("A").copy(transitionFit = TransitionFit(0.5), openingTransitionFit = TransitionFit(0.5)), clip("B").copy(transition = dissolve("X", 1.0), transitionFit = TransitionFit(0.25)))
        assertEquals(8.0, TimelineEditing.duration(stray), tiny)
        assertSame(stray, TimelineEditing.fitted(stray))
    }

    @Test fun aSavedFitIsNeverWorkedOutAgain() {
        // The same clips with factor 1 and 1/3 saved: the layout plays them so, whatever the sequence rule would give.
        val saved = EditState(listOf(chain.clips[0].copy(transitionFit = TransitionFit(1.0)), chain.clips[1].copy(transitionFit = TransitionFit(1.0 / 3)), chain.clips[2]))
        assertNumbers(listOf(1.0, 1.0 / 3), factors(saved))
        assertNumbers(listOf(0.0, 1.0, 4.0), starts(saved))
        assertEquals(8.0, saved.duration, tiny)
    }

    @Test fun aListWithSomeFitsMissingIsFittedFromTheSavedOnes() {
        // The second dissolve has a fit and the first does not: the loop runs from 1 and 1/3 and shortens neither.
        val mixed = EditState(listOf(chain.clips[0], chain.clips[1].copy(transitionFit = TransitionFit(1.0 / 3)), chain.clips[2]))
        assertNumbers(listOf(1.0, 1.0 / 3), factors(mixed))
        assertNumbers(listOf(1.0, 1.0 / 3), TimelineEditing.fitted(mixed.clips).take(2).map { it.transitionFit!!.factor })
    }

    // MARK: Split

    @Test fun aSplitKeepsTheFitsOfTheTransitionsAroundIt() {
        // Three 4 s clips, two 3 s dissolves, split at 1.5 s: TransitionTests.swift:21-22, "Splits preserve the outgoing join and duration".
        val split = TimelineEditing.split(chain, 1.5)!!
        assertEquals(listOf(1.5, 2.5, 4.0, 4.0), split.clips.map { it.duration })
        assertEquals(listOf(null, "JB", "JC", null), split.clips.map { it.transition?.id })
        assertNumbers(listOf(2.0 / 3, 2.0 / 3), factors(split))
        assertNumbers(listOf(2.0 / 3, 2.0 / 3), split.clips.subList(1, 3).map { it.transitionFit!!.factor })
        assertNull(split.clips[0].transitionFit)
        assertNumbers(listOf(0.0, 1.5, 2.0, 4.0), starts(split))
        assertEquals(8.0, split.duration, tiny)
    }

    @Test fun theOriginalsSplitOfAPair() {
        // TransitionTests.swift:20-22.
        assertNull(TimelineEditing.split(pair, 3.25))
        val split = TimelineEditing.split(pair, 1.0)!!
        assertEquals(3, split.clips.size)
        assertEquals(7.0, split.duration, tiny)
        assertEquals(pair.clips[0].transition, split.clips[1].transition)
        assertEquals(TransitionFit(1.0), split.clips[1].transitionFit)
        assertNull(split.clips[0].transition)
        // The opening stays on the first piece with its fit, the close goes with the last.
        val ends = TimelineEditing.setJoin(TimelineEditing.setJoin(EditState(listOf(clip("A"))), 0, TransitionSettings(duration = 1.0))!!, 1, TransitionSettings(duration = 1.0))!!
        val cut = TimelineEditing.split(ends, 2.0)!!
        assertEquals(listOf(TransitionFit(1.0), null), cut.clips.map { it.openingTransitionFit })
        assertEquals(listOf(null, TransitionFit(1.0)), cut.clips.map { it.transitionFit })
        assertEquals(listOf(true, false), cut.clips.map { it.openingTransition != null })
        assertEquals(listOf(false, true), cut.clips.map { it.transition != null })
    }

    @Test fun deletingAClipLeavesTheOtherTransitionAsItWasFitted() {
        val deleted = TimelineEditing.delete(chain, 2)!!
        assertNumbers(listOf(2.0 / 3), factors(deleted))
        assertEquals(6.0, deleted.duration, tiny)
        assertEquals(2.0 / 3, deleted.clips[0].transitionFit!!.factor, tiny)
        // The clip between two transitions takes both with it: TrackTimelineTests.swift:142.
        val middle = TimelineEditing.delete(chain, 1)!!
        assertTrue(middle.clips.all { it.transition == null && it.transitionFit == null })
        assertEquals(8.0, middle.duration, tiny)
    }

    // MARK: Setting a transition

    @Test fun aTransitionIsFittedIntoWhatItsNeighborsLeaveAndTheyKeepTheirs() {
        // TransitionTests.swift:49-54: three 4 s clips, 3 s at join 1 and then at join 2.
        var state = EditState(listOf(clip("A"), clip("B"), clip("C")))
        state = TimelineEditing.setJoin(state, 1, TransitionSettings(duration = 3.0))!!
        state = TimelineEditing.setJoin(state, 2, TransitionSettings(duration = 3.0))!!
        assertNumbers(listOf(1.0, 1.0 / 3), factors(state))
        assertNumbers(listOf(1.0, 1.0 / 3), fits(state).take(2).map { it!! })
        assertNumbers(listOf(0.0, 1.0, 4.0), starts(state))
        val joins = layout(state).joins
        assertEquals(1.0, joins[1].range.start, tiny)
        assertEquals(4.0, joins[1].range.endInclusive, tiny)
        assertEquals(4.0, joins[2].range.start, tiny)
        assertEquals(5.0, joins[2].range.endInclusive, tiny)
        assertEquals(8.0, state.duration, tiny)
        // "Adjacent transitions never require three simultaneous clips", "Transition windows never cross".
        val middle = layout(state).placements[1]
        assertTrue(middle.incoming + middle.outgoing <= 4.000001)
        assertTrue(joins[1].range.endInclusive <= joins[2].range.start + 0.000001)
        // A split away from both keeps both; inside the first it is refused.
        assertNumbers(listOf(1.0, 1.0 / 3), factors(TimelineEditing.split(state, 0.5)!!))
        assertNull(TimelineEditing.split(state, 1.5))
        // The other order gives the other answer: fitting depends on the order of setting.
        var reversed = EditState(listOf(clip("A"), clip("B"), clip("C")))
        reversed = TimelineEditing.setJoin(reversed, 2, TransitionSettings(duration = 3.0))!!
        reversed = TimelineEditing.setJoin(reversed, 1, TransitionSettings(duration = 3.0))!!
        assertNumbers(listOf(1.0 / 3, 1.0), factors(reversed))
    }

    @Test fun theOriginalsPairOpeningAndClose() {
        // TransitionTests.swift:14-17.
        assertEquals(7.0, pair.duration, tiny)
        assertEquals(3.0, layout(pair).placements[1].start, tiny)
        assertEquals(3.5, layout(pair).joins[1].time, tiny)
        assertEquals(listOf(0.0 to 4.0, 0.0 to 4.0), pair.clips.map { it.start to it.end })
        assertEquals(TransitionFit(1.0), pair.clips[0].transitionFit)
        // TransitionTests.swift:43-48.
        val plain = EditState(listOf(clip("A"), clip("B")))
        val faded = TimelineEditing.setJoin(plain, 1, TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0))!!
        assertEquals(8.0, faded.duration, tiny)
        assertEquals(3.5, layout(faded).joins[1].range.start, tiny)
        assertEquals(4.5, layout(faded).joins[1].range.endInclusive, tiny)
        val ends = TimelineEditing.setJoin(TimelineEditing.setJoin(plain, 0, TransitionSettings(duration = 1.0))!!, 2, TransitionSettings(duration = 1.0))!!
        assertEquals(8.0, ends.duration, tiny)
        assertEquals(0.0..1.0, layout(ends).joins[0].range)
        assertEquals(7.0, layout(ends).joins[2].range.start, tiny)
        assertEquals(TransitionFit(1.0), ends.clips[0].openingTransitionFit)
        assertEquals(TransitionFit(1.0), ends.clips[1].transitionFit)
        // Removing it clears the fit with it and the clips meet at a cut again (restoringStraightCut, TransitionTests.swift:25).
        val removed = TimelineEditing.setJoin(pair, 1, null)!!
        assertEquals(EditState(listOf(clip("A"), clip("B"))), removed)
        assertNull(TimelineEditing.setJoin(removed, 1, null))
    }

    @Test fun settingTheSameSettingsAgainFitsThemAgain() {
        // A 1 s clip and a 4 s clip: a 3 s dissolve gets the whole short clip.
        var state = TimelineEditing.setJoin(EditState(listOf(clip("A", 1.0), clip("B"))), 1, TransitionSettings(id = "J", duration = 3.0))!!
        assertNumbers(listOf(1.0 / 3), factors(state))
        assertEquals(4.0, state.duration, tiny)
        // The halvings take no more than is free: the second clip never begins before zero.
        assertTrue(starts(state)[1] >= 0)
        assertNull(TimelineEditing.setJoin(state, 1, TransitionSettings(id = "J", duration = 3.0)))
        // The clip is extended: the transition stays as it was fitted (TrackTimeline.swift:1370-1385, neither branch).
        state = TimelineEditing.trim(state, 0, 0.0, 4.0, 4.0)!!
        assertNumbers(listOf(1.0 / 3), factors(state))
        assertNumbers(listOf(0.0, 3.0), starts(state))
        assertEquals(7.0, state.duration, tiny)
        // Applying the same settings fits them again from their full length: TransitionEditor.swift:64, 76-78.
        state = TimelineEditing.setJoin(state, 1, TransitionSettings(id = "J", duration = 3.0))!!
        assertNumbers(listOf(1.0), factors(state))
        assertEquals(5.0, state.duration, tiny)
        assertNull(TimelineEditing.setJoin(state, 1, TransitionSettings(id = "J", duration = 3.0)))
    }

    @Test fun applyingTheSettingsOfAFittedTransitionUnchangedChangesNothing() {
        // The fit of a project saved without one, found by the sequence rule, and the same fit found by setting it again.
        val fitted = EditState(TimelineEditing.fitted(chain.clips))
        for (join in 1..2) {
            assertNull(TimelineEditing.setJoin(chain, join, chain.clips[join - 1].transition))
            assertNull(TimelineEditing.setJoin(fitted, join, fitted.clips[join - 1].transition))
        }
        val split = TimelineEditing.split(chain, 1.5)!!
        assertNull(TimelineEditing.setJoin(split, 2, split.clips[1].transition))
    }

    @Test fun aTransitionUnderOneFrameIsNotSetAndNothingChanges() {
        val state = TimelineEditing.setJoin(EditState(listOf(clip("A"), clip("B", 1.0), clip("C"))), 1, TransitionSettings(duration = 3.0))!!
        assertNull(TimelineEditing.setJoin(state, 2, TransitionSettings(duration = 3.0)))
        assertNull(TimelineEditing.setJoin(pair, 1, TransitionSettings(duration = 0.01)))
    }

    // MARK: Trim

    @Test fun aTrimThatTakesTheFootageOfATransitionRemovesItForGood() {
        // TransitionTests.swift:40-42.
        val trimmed = TimelineEditing.trim(pair, 0, 0.0, 0.5, 4.0)!!
        assertTrue(trimmed.clips.all { it.transition == null && it.transitionFit == null })
        assertNumbers(listOf(0.0, 0.5), starts(trimmed))
        assertEquals(4.5, trimmed.duration, tiny)
        val extended = TimelineEditing.trim(trimmed, 0, 0.0, 4.0, 4.0)!!
        assertTrue(extended.clips.all { it.transition == null && it.transitionFit == null })
        assertEquals(8.0, extended.duration, tiny)
        // The incoming clip trimmed to half a second: the same (TrackTimeline.swift:1362-1375).
        val incoming = TimelineEditing.trim(pair, 1, 3.5, 4.0, 4.0)!!
        assertTrue(incoming.clips.all { it.transition == null })
        assertEquals(4.5, incoming.duration, tiny)
    }

    @Test fun aTrimThatLeavesTheFootageLeavesTheTransition() {
        val trimmed = TimelineEditing.trim(pair, 0, 0.0, 2.0, 4.0)!!
        assertNumbers(listOf(1.0), factors(trimmed))
        assertNumbers(listOf(0.0, 1.0), starts(trimmed))
        assertEquals(5.0, trimmed.duration, tiny)
        // Exactly the footage it costs is enough: the test is "greater than" (TrackTimeline.swift:1370).
        val exact = TimelineEditing.trim(pair, 0, 0.0, 1.0, 4.0)!!
        assertNumbers(listOf(1.0), factors(exact))
        assertEquals(4.0, exact.duration, tiny)
    }

    @Test fun aClipTooShortForBothItsTransitionsLosesBoth() {
        // Three clips with 1 s dissolves; the middle one trimmed to 1.5 s costs 2 s: TrackTimeline.swift:1370-1375.
        var state = EditState(listOf(clip("A"), clip("B"), clip("C")))
        state = TimelineEditing.setJoin(state, 1, TransitionSettings(duration = 1.0))!!
        state = TimelineEditing.setJoin(state, 2, TransitionSettings(duration = 1.0))!!
        val trimmed = TimelineEditing.trim(state, 1, 0.0, 1.5, 4.0)!!
        assertTrue(trimmed.clips.all { it.transition == null && it.transitionFit == null })
        assertEquals(9.5, trimmed.duration, tiny)
        // The opening and the close count as the transitions into and out of their clip.
        val ends = TimelineEditing.setJoin(TimelineEditing.setJoin(EditState(listOf(clip("A"))), 0, TransitionSettings(duration = 1.0))!!, 1, TransitionSettings(duration = 1.0))!!
        val short = TimelineEditing.trim(ends, 0, 0.0, 1.5, 4.0)!!
        assertNull(short.clips[0].openingTransition)
        assertNull(short.clips[0].openingTransitionFit)
        assertNull(short.clips[0].transition)
        assertNotNull(TimelineEditing.trim(ends, 0, 0.0, 2.0, 4.0)!!.clips[0].openingTransition)
    }

    @Test fun aTrimOfAProjectSavedWithoutFitsStartsFromTheSequenceRule() {
        // Factors 2/3 and 2/3 cost the middle clip all of its 4 s; trimmed to 3.5 s it loses both.
        val trimmed = TimelineEditing.trim(chain, 1, 0.0, 3.5, 4.0)!!
        assertTrue(trimmed.clips.all { it.transition == null })
        // The first clip trimmed to 3 s still has the 2 s its dissolve costs: both stay as they were fitted.
        val kept = TimelineEditing.trim(chain, 0, 0.0, 3.0, 4.0)!!
        assertNumbers(listOf(2.0 / 3, 2.0 / 3), factors(kept))
        assertEquals(7.0, kept.duration, tiny)
    }

    // MARK: Insert and paste

    @Test fun footagePutInsideATransitionRemovesItAndCutsTheClipThere() {
        // TimelinePaste.swift:176-186; TimelineClipboardChecks.swift:112.
        val take = clip("X", 2.0)
        val inside = TimelineEditing.insert(pair, listOf(take), 3.4)!!
        assertEquals(listOf("A", "X", "S", "B"), inside.clips.mapIndexed { index, clip -> if (index == 2) clip.sourceID else clip.id })
        assertNumbers(listOf(0.0, 3.4, 0.0, 2.0, 3.4, 4.0, 0.0, 4.0), inside.clips.flatMap { listOf(it.start, it.end) })
        assertTrue(inside.clips.all { it.transition == null && it.transitionFit == null })
        assertEquals(3.4, starts(inside)[1], tiny)
        assertEquals(10.0, inside.duration, tiny)
        // On the join: between the two clips. TimelinePaste.swift:161-163; TimelineClipboardChecks.swift:92.
        val onJoin = TimelineEditing.insert(pair, listOf(take), 3.5)!!
        assertEquals(listOf("A", "X", "B"), onJoin.clips.map { it.id })
        assertTrue(onJoin.clips.all { it.transition == null && it.transitionFit == null })
        assertEquals(4.0, starts(onJoin)[1], tiny)
        assertEquals(10.0, onJoin.duration, tiny)
        // Away from it: the clip is cut and the dissolve goes with the second piece as it was.
        val away = TimelineEditing.insert(pair, listOf(take), 2.0)!!
        assertEquals(listOf(null, null, TransitionFit(1.0), null), away.clips.map { it.transitionFit })
        assertEquals(9.0, away.duration, tiny)
    }

    @Test fun insertAndPasteAgreeInsideATransition() {
        val take = clip("X", 2.0)
        val fragment = ClipFragment(listOf(take), emptyList(), null)
        for (state in listOf(pair, chain)) for (at in listOf(0.0, 0.5, 2.0, 2.5, 3.0, 3.4, 3.5, 3.6, 4.0, 5.0, 6.5, state.duration)) {
            val inserted = TimelineEditing.insert(state, listOf(take), at)!!
            val pasted = RangeEditing.paste(state, fragment, at)!!.state
            // To the microsecond: the two cut a clip by their own arithmetic.
            val shape = { edit: EditState -> edit.clips.map { Triple(Math.round(it.start * 1e6), Math.round(it.end * 1e6), it.transition?.id to it.transitionFit) } }
            assertEquals("at $at", shape(inserted), shape(pasted))
        }
    }

    @Test fun aPasteInsideOneTransitionLeavesTheOtherAsItWasFitted() {
        val inserted = TimelineEditing.insert(chain, listOf(clip("X", 2.0)), 3.5)!!
        assertEquals(listOf("A", "X", "S", "B", "C"), inserted.clips.mapIndexed { index, clip -> if (index == 2) clip.sourceID else clip.id })
        assertNumbers(listOf(2.0 / 3), factors(inserted))
        assertEquals(12.0, inserted.duration, tiny)
    }

    @Test fun aRecordedTakeAndAnUnusedTakeGoInAsAPasteDoes() {
        val first = SourceMedia(id = "S", filename = "a.mp4", duration = 4.0)
        val second = SourceMedia(id = "T", filename = "b.mp4", duration = 4.0)
        val extra = SourceMedia(id = "U", filename = "c.mp4", duration = 2.0)
        var project = Project("Takes").addRecording(first).addRecording(second)
        project = project.commit(TimelineEditing.setJoin(project.edit, 1, TransitionSettings(duration = 1.0))!!)
        // Recorded at 3.4 s, inside the dissolve running 3…4.
        val recorded = project.beginRecordingInsertion(3.4).addRecording(extra)
        assertEquals(listOf("S" to (0.0 to 3.4), "U" to (0.0 to 2.0), "S" to (3.4 to 4.0), "T" to (0.0 to 4.0)), recorded.edit.clips.map { it.sourceID to (it.start to it.end) })
        assertTrue(recorded.edit.clips.all { it.transition == null && it.transitionFit == null })
        assertEquals(10.0, recorded.edit.duration, tiny)
        // Undo brings the dissolve back as it was fitted.
        assertEquals(project.edit.clips, recorded.undo().edit.clips)
        // An unused take added at the same place.
        val unused = project.addSource(extra.copy(id = "V", filename = "d.mp4"))
        val added = UnusedTakes.add(unused, listOf(unused.sources.last()), 3.4)
        assertNotNull(added)
        assertEquals(listOf(0.0 to 3.4, 0.0 to 2.0, 3.4 to 4.0, 0.0 to 4.0), added!!.project.edit.clips.map { it.start to it.end })
        assertTrue(added.project.edit.clips.all { it.transition == null })
        assertEquals(3.4, added.start, tiny)
    }

    // MARK: Speed and pauses

    @Test fun aFasterClipScalesItsTransitionAndASlowerOneDoesNotGiveItBack() {
        // TrackTimelineTests.swift:832-837: three 4 s clips, a 4 s dissolve at join 1, the first clip at 4×.
        var state = TimelineEditing.setJoin(EditState(listOf(clip("A"), clip("B"), clip("C"))), 1, TransitionSettings(duration = 4.0))!!
        assertNumbers(listOf(1.0), factors(state))
        state = TimelineEditing.setSpeed(state, 0, 4.0)!!
        assertNumbers(listOf(0.25), factors(state), 1e-9)
        assertNumbers(listOf(0.0, 0.0, 4.0), starts(state), 1e-9)
        assertEquals(8.0, state.duration, 1e-9)
        // TrackTimeline.swift:1376-1385: otherwise unchanged.
        state = TimelineEditing.setSpeed(state, 0, 1.0)!!
        assertNumbers(listOf(0.25), factors(state), 1e-9)
        assertNumbers(listOf(0.0, 3.0, 7.0), starts(state), 1e-9)
        assertEquals(11.0, state.duration, 1e-9)
    }

    @Test fun bothTransitionsOfAFasterClipAreScaledTogether() {
        var state = EditState(listOf(clip("A"), clip("B"), clip("C")))
        state = TimelineEditing.setJoin(state, 1, TransitionSettings(duration = 2.0))!!
        state = TimelineEditing.setJoin(state, 2, TransitionSettings(duration = 1.0))!!
        // The middle clip at 2× lasts 2 s and its transitions cost 3: both to 2/3.
        state = TimelineEditing.setSpeed(state, 1, 2.0)!!
        assertNumbers(listOf(2.0 / 3, 2.0 / 3), factors(state), 1e-9)
        val middle = layout(state).placements[1]
        assertTrue(middle.incoming + middle.outgoing <= 2.0 + 1e-9)
        // TransitionTests.swift:30-33: both clips at a quarter speed take a 12 s dissolve whole.
        var slow = EditState(listOf(clip("A").copy(speed = SpeedSettings(rate = 0.25)), clip("B").copy(speed = SpeedSettings(rate = 0.25))))
        slow = TimelineEditing.setJoin(slow, 1, TransitionSettings(duration = 12.0))!!
        assertEquals(16.0, layout(slow).joins[1].maximum, tiny)
        assertEquals(20.0, slow.duration, tiny)
        assertEquals(32.0, TimelineEditing.setJoin(slow, 1, null)!!.duration, tiny)
    }

    @Test fun pausesTakenOutOfAClipLeaveEachTransitionOnItsPieceScaledToIt() {
        var state = EditState(listOf(clip("A"), clip("B"), clip("C")))
        state = TimelineEditing.setJoin(state, 1, TransitionSettings(duration = 1.0))!!
        state = TimelineEditing.setJoin(state, 2, TransitionSettings(duration = 1.0))!!
        // The middle clip keeps 0…0.5, 1.5…2.5 and 3…4: the dissolve into it is left on half a second.
        val cut = TimelineEditing.removeIntervals(state, 1, listOf(SourceInterval(0.5, 1.5), SourceInterval(2.5, 3.0)))!!
        assertEquals(listOf(4.0, 0.5, 1.0, 1.0, 4.0), cut.clips.map { it.duration })
        assertNumbers(listOf(0.5, 1.0), factors(cut))
        assertEquals(listOf("B", "C"), cut.clips.mapNotNull { it.transition?.nextClipID })
        assertEquals(0.5, cut.clips[0].transitionFit!!.factor, tiny)
        assertEquals(1.0, cut.clips[3].transitionFit!!.factor, tiny)
        assertNumbers(listOf(0.0, 3.5, 4.0, 5.0, 5.0), starts(cut))
        // One piece left: both together, as for speed.
        val one = TimelineEditing.removeIntervals(state, 1, listOf(SourceInterval(0.0, 2.5)))!!
        assertEquals(listOf(4.0, 1.5, 4.0), one.clips.map { it.duration })
        assertNumbers(listOf(0.75, 0.75), factors(one))
        // Pieces with room for them change nothing.
        val roomy = TimelineEditing.removeIntervals(state, 1, listOf(SourceInterval(1.5, 2.5)))!!
        assertNumbers(listOf(1.0, 1.0), factors(roomy))
    }

    // MARK: Move

    @Test fun movingAClipRemovesTheTransitionsItLeavesAndKeepsTheOthers() {
        val four = EditState(chain.clips + clip("D"))
        // The last clip to the front: both dissolves are between neighbors that stay neighbors.
        val moved = TimelineEditing.move(four, 3, 0)!!
        assertNumbers(listOf(2.0 / 3, 2.0 / 3), factors(moved))
        assertNumbers(listOf(2.0 / 3, 2.0 / 3), moved.clips.subList(1, 3).map { it.transitionFit!!.factor })
        // The first clip to the end: its dissolve goes, the other keeps its fit and gets nothing back.
        val away = TimelineEditing.move(chain, 0, 2)!!
        assertEquals(listOf("B", "C", "A"), away.clips.map { it.id })
        assertNumbers(listOf(2.0 / 3), factors(away))
        assertNull(away.clips[2].transition)
        assertNull(away.clips[2].transitionFit)
        assertEquals(10.0, away.duration, tiny)
    }

    // MARK: Saving

    @Test fun aFitIsWrittenBesideItsTransitionUnderTheOriginalsName() {
        val state = TimelineEditing.split(chain, 1.5)!!
        val written = EditSnapshot(EditState(state.clips.takeLast(2).map { it.copy(id = if (it.id == "B") "B" else "C") }), 0).data().decodeToString()
        val factor = state.clips[2].transitionFit!!.factor
        assertEquals(
            """{"state":{"clips":[{"id":"B","sourceID":"S","start":0.0,"end":4.0,"transition":{"id":"JC","nextClipID":"C","style":"dissolve","duration":3.0},""" +
                """"transitionFit":{"factor":$factor}},{"id":"C","sourceID":"S","start":0.0,"end":4.0}]},"captureCursor":0}""",
            written,
        )
        val opening = TimelineEditing.setJoin(EditState(listOf(clip("A"))), 0, TransitionSettings(id = "O", duration = 1.0))!!
        assertTrue(EditSnapshot(opening, 0).data().decodeToString().contains(""""openingTransitionFit":{"factor":1.0}"""))
    }

    @Test fun fitsSurviveSavingReadingUndoAndRedo() {
        // TransitionTests.swift:26-28, "Transition parameters and undo/redo survive reopening".
        val source = SourceMedia(id = "S", filename = "take.mp4", duration = 4.0)
        var project = Project("Fits", sources = listOf(source)).commit(chain)
        val split = TimelineEditing.split(project.edit, 1.5)!!
        project = project.commit(split).undo()
        val files = HashMap<String, ByteArray>()
        val manifest = project.manifest { name, data -> files[name] = data }
        var reopened = Project.readManifest(manifest) { files.getValue(it) }
        // The step saved without fits is read without them.
        assertEquals(chain.clips, reopened.edit.clips)
        reopened = reopened.redo()
        assertEquals(split.clips, reopened.edit.clips)
        assertNumbers(listOf(2.0 / 3, 2.0 / 3), factors(reopened.edit))
        assertEquals(chain.clips, reopened.undo().edit.clips)
        // A project with old-style transitions and their fits stays in the old format's schema.
        assertEquals(2, reopened.schemaVersion)
    }

    @Test fun anEditThatMovesNoFootageSavesNoFit() {
        val source = SourceMedia(id = "S", filename = "take.mp4", duration = 4.0)
        val project = Project("Fits", sources = listOf(source)).commit(chain)
        val titled = project.commit(TimelineEditing.setText(project.edit, 0, TextOverlay(text = "Hello", start = 0.0, end = 1.0))!!)
        assertEquals(listOf(null, null, null), fits(titled.edit))
        assertTrue(!EditSnapshot(titled.edit, 0).data().decodeToString().contains("Fit"))
        val colored = TimelineEditing.setColor(project.edit, 0, ColorSettings(exposure = 0.3))
        assertEquals(listOf(null, null, null), fits(colored!!))
    }

    @Test fun aFactorOutOfRangeIsRefused() {
        // TrackTimeline.swift:1216-1217.
        val source = SourceMedia(id = "S", filename = "take.mp4", duration = 4.0)
        for (factor in listOf(1.5, -0.1, Double.NaN, Double.POSITIVE_INFINITY)) {
            val state = EditState(listOf(chain.clips[0].copy(transitionFit = TransitionFit(factor)), chain.clips[1], chain.clips[2]))
            val project = Project("Fits", sources = listOf(source), snapshots = listOf(EditSnapshot(state, 0)))
            assertThrows("$factor", ProjectException::class.java) { project.validate() }
            val opening = EditState(listOf(clip("A").copy(openingTransition = TransitionSettings(duration = 1.0), openingTransitionFit = TransitionFit(factor))))
            assertThrows("$factor", ProjectException::class.java) { Project("Fits", sources = listOf(source), snapshots = listOf(EditSnapshot(opening, 0))).validate() }
        }
        Project("Fits", sources = listOf(source), snapshots = listOf(EditSnapshot(EditState(TimelineEditing.fitted(chain.clips)), 0))).validate()
    }

    // MARK: Copy

    @Test fun aCopiedTransitionTravelsWithItsFit() {
        // TimelineClipboardChecks.swift:97.
        val copy = RangeEditing.copy(chain, EditScope.Selected(EditTargets(clips = setOf("A", "B"))))!!
        assertEquals(2.0 / 3, copy.clips[0].transitionFit!!.factor, tiny)
        assertNull(copy.clips[1].transition)
        assertNull(copy.clips[1].transitionFit)
        val pasted = RangeEditing.paste(EditState(), copy, 0.0)!!.state
        assertNumbers(listOf(2.0 / 3), factors(pasted))
        assertEquals(6.0, pasted.duration, tiny)
        // A range delete away from them leaves both as they were fitted.
        val shorter = RangeEditing.delete(chain, EditScope.Range(TimeRange(0.5, 1.5)))!!.state
        assertNumbers(listOf(2.0 / 3, 2.0 / 3), factors(shorter))
        // A tool scope that cuts nothing is the edit it was given, without fits saved.
        assertSame(chain, RangeEditing.scoped(chain, EditScope.Whole).state)
        assertSame(chain, RangeEditing.scoped(chain, EditScope.Range(TimeRange(0.0, 8.0))).state)
    }

    /**
     * What the tool offers as the longest duration: the shorter clip's whole
     * length (Transitions.swift:294, TransitionEditor.swift:11), not what the
     * neighboring transitions leave free. What does not fit is kept as
     * entered and plays shortened (Transitions.swift:359-370, TransitionEditor.swift:298).
     */
    @Test fun theToolOffersTheWholeLengthOfTheShorterClip() {
        fun clip(id: String, seconds: Double) = TimelineClip(id = id, sourceID = "S", start = 0.0, end = seconds)
        val three = EditState(listOf(clip("A", 4.0), clip("B", 4.0), clip("C", 4.0)))
        val first = TimelineEditing.setJoin(three, 1, TransitionSettings(duration = 1.0))!!
        // The second join, with a second of the middle clip held by the first: 4 s offered, where the layout leaves 3 s free.
        assertEquals(3.0, TimelineEditing.layout(first.clips).joins[2].maximum, 1e-9)
        assertEquals(4.0, TimelineEditing.joinMaximum(first.clips, 2), 1e-9)
        val asked = TimelineEditing.setJoin(first, 2, TransitionSettings(duration = 4.0))!!.clips
        assertEquals(4.0, asked[1].transition!!.duration, 1e-9)
        assertEquals(0.75, asked[1].transitionFit!!.factor, 1e-9)
        assertEquals(3.0, TimelineEditing.join(asked, 2)!!.timing.videoDuration, 1e-9)
        // A fade through color between two clips takes half from each: still 4 s offered, not 8.
        val fade = TimelineEditing.setJoin(EditState(three.clips.take(2)), 1, TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0))!!.clips
        assertEquals(8.0, TimelineEditing.layout(fade).joins[1].maximum, 1e-9)
        assertEquals(4.0, TimelineEditing.joinMaximum(fade, 1), 1e-9)
        // The shorter of the two; one clip alone for an opening and a close.
        val uneven = listOf(clip("A", 2.5), clip("B", 4.0))
        assertEquals(2.5, TimelineEditing.joinMaximum(uneven, 1), 1e-9)
        assertEquals(2.5, TimelineEditing.joinMaximum(uneven, 0), 1e-9)
        assertEquals(4.0, TimelineEditing.joinMaximum(uneven, 2), 1e-9)
        assertEquals(0.0, TimelineEditing.joinMaximum(uneven, 9), 1e-9)
        // A new transition starts at half a second, or at all of a shorter clip (TransitionEditor.swift:64).
        assertEquals(0.5, TransitionDefaults.new(TimelineEditing.joinMaximum(uneven, 1)).duration, 1e-9)
        assertEquals(0.2, TransitionDefaults.new(TimelineEditing.joinMaximum(listOf(clip("A", 0.2), clip("B", 4.0)), 1)).duration, 1e-9)
    }
}
