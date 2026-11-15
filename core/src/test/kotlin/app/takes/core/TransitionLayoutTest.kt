package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where transitions land and how they are fitted, against the original's
 * `EditState.settingTransition` (YouRec/Transitions.swift:335-386), its
 * sequence layout `TimelineLayout(clips:)` (l. 198-252) and the timelines its
 * tests build (Tests/TransitionTests.swift). Line numbers are the original's.
 */
class TransitionLayoutTest {
    private val tiny = 1e-9

    /** A fit may run over the footage by the original's allowance of 0.00000001 s (Transitions.swift:361), so a fitted number is that loose. */
    private val fitted = 1e-7

    /** The original's tests start from two clips of 4 s, TransitionTests.swift:9-12. */
    private val two = TransitionLayout.arrangement(listOf("A", "B"), listOf(4.0, 4.0))
    private val three = TransitionLayout.arrangement(listOf("A", "B", "C"), listOf(4.0, 4.0, 4.0))

    private fun assertRange(range: ClosedFloatingPointRange<Double>, lower: Double, upper: Double) {
        assertEquals(lower, range.start, fitted); assertEquals(upper, range.endInclusive, fitted)
    }

    private fun starts(arrangement: TransitionLayout.Arrangement) = arrangement.chronological.map { it.start }

    // MARK: Placement

    @Test fun aDissolvePullsTheNextClipBack() {
        // "edit.duration == 7 && placements[1].start == 3 && joins[1].time == 3.5", TransitionTests.swift:14-16.
        val edit = TransitionLayout.setting(two, TransitionSettings(duration = 1.0), 1)
        assertEquals(7.0, edit.duration, 0.0)
        assertEquals(listOf(0.0, 3.0), starts(edit))
        val join = edit.joins[1]
        assertEquals(3.5, join.time, 0.0); assertEquals(3.0, join.origin, 0.0); assertEquals(1.0, join.factor, 0.0); assertEquals(0, join.edge)
        assertRange(join.videoRange, 3.0, 4.0)
        // The transition knows the clip it leads to, Transitions.swift:348.
        assertEquals("B", edit.relations.single().settings.nextClipID)
        // The same timeline from the clips alone.
        val sequence = TransitionLayout.sequence(listOf(4.0, 4.0), listOf(null, TransitionSettings(duration = 1.0), null))
        assertEquals(7.0, sequence.duration, 0.0); assertEquals(3.0, sequence.placements[1].start, 0.0); assertEquals(3.5, sequence.joins[1].time, 0.0)
        assertEquals(1.0, sequence.placements[0].outgoing, 0.0); assertEquals(1.0, sequence.placements[1].incoming, 0.0)
        assertEquals(3.5, sequence.placements[0].upper, 0.0)
    }

    @Test fun removingKeepsPositionsAndAStraightCutRestoresTheLength() {
        val edit = TransitionLayout.setting(two, TransitionSettings(duration = 1.0), 1)
        // "Removing a transition retains explicit clip positions", TransitionTests.swift:23-24.
        val removed = TransitionLayout.setting(edit, null, 1)
        assertTrue(removed.relations.isEmpty()); assertEquals(starts(edit), starts(removed))
        // "Explicit straight-cut restoration recovers sequence duration", TransitionTests.swift:25.
        val restored = TransitionLayout.restoringStraightCut(edit, 1)
        assertTrue(restored.relations.isEmpty()); assertEquals(8.0, restored.duration, 0.0); assertEquals(listOf(0.0, 4.0), starts(restored))
    }

    @Test fun aFadeThroughBlackKeepsTheLength() {
        // "faded.duration == 8 && joins[1].range == 3.5...4.5", TransitionTests.swift:43-45.
        val faded = TransitionLayout.setting(two, TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0), 1)
        assertEquals(8.0, faded.duration, 0.0)
        assertRange(faded.joins[1].range, 3.5, 4.5)
        assertEquals(4.0, faded.joins[1].time, 0.0)
    }

    @Test fun openingAndClosingKeepTheWholeProject() {
        // "ends.duration == 8 && joins[0].range == 0...1 && joins[2].range == 7...8", TransitionTests.swift:46-48.
        var ends = TransitionLayout.setting(two, TransitionSettings(duration = 1.0), 0)
        ends = TransitionLayout.setting(ends, TransitionSettings(duration = 1.0), 2)
        assertEquals(8.0, ends.duration, 0.0)
        assertRange(ends.joins[0].range, 0.0, 1.0); assertRange(ends.joins[2].range, 7.0, 8.0)
        assertEquals(-1, ends.joins[0].edge); assertEquals(1, ends.joins[2].edge)
        assertEquals(listOf(null, "B"), ends.relations.map { it.outgoing }); assertEquals(listOf("A", null), ends.relations.map { it.incoming })
        // A closing transition leads to no clip.
        assertNull(ends.relations[1].settings.nextClipID)
        val sequence = TransitionLayout.sequence(listOf(4.0, 4.0), listOf(TransitionSettings(duration = 1.0), null, TransitionSettings(duration = 1.0)))
        assertEquals(8.0, sequence.duration, 0.0); assertRange(sequence.joins[0].range, 0.0, 1.0); assertRange(sequence.joins[2].range, 7.0, 8.0)
        assertEquals(0.0, sequence.joins[0].time, 0.0); assertEquals(8.0, sequence.joins[2].time, 0.0)
    }

    @Test fun lengthsCountPlayedSecondsSoSlowClipsTakeLongTransitions() {
        // Two 4 s clips at 0.25× are 16 s each: "joins[1].maximum == 16 && duration == 20", TransitionTests.swift:30-33.
        val slow = TransitionLayout.arrangement(listOf("A", "B"), listOf(4.0 / 0.25, 4.0 / 0.25))
        val edit = TransitionLayout.setting(slow, TransitionSettings(duration = 12.0), 1)
        assertEquals(16.0, edit.joins[1].maximum, 0.0); assertEquals(20.0, edit.duration, 0.0); assertEquals(1.0, edit.joins[1].factor, 0.0)
        // "Removing a long overlap restores all footage", TransitionTests.swift:37.
        assertEquals(32.0, TransitionLayout.restoringStraightCut(edit, 1).duration, 0.0)
        val sequence = TransitionLayout.sequence(listOf(16.0, 16.0), listOf(null, TransitionSettings(duration = 12.0), null))
        assertEquals(16.0, sequence.joins[1].maximum, 0.0); assertEquals(20.0, sequence.duration, 0.0)
    }

    @Test fun holdsAndFreezes() {
        // "Midpoint hold adds to the moving overlap": 5 s, TransitionTests.swift:279-281.
        val held = TransitionLayout.setting(two, TransitionSettings(duration = 1.0, hold = 2.0), 1)
        assertEquals(5.0, held.duration, tiny)
        assertEquals(0.5, held.joins[1].progress(held.joins[1].origin + 1), 0.0); assertEquals(0.5, held.joins[1].progress(held.joins[1].origin + 2), 0.0)
        // "Solid color hold adds time without consuming footage": 10 s, TransitionTests.swift:282-284.
        val plateau = TransitionLayout.setting(two, TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0, hold = 2.0), 1)
        assertEquals(10.0, plateau.duration, 0.0)
        // "still.edit.duration == 10 && joins[1].videoRange == 4...6.5", TransitionTests.swift:285-287.
        val freeze = TransitionSettings(style = TransitionStyle.Still, duration = 2.0, stillDissolve = 0.5)
        val still = TransitionLayout.setting(two, freeze, 1)
        assertEquals(10.0, still.duration, 0.0); assertRange(still.joins[1].videoRange, 4.0, 6.5)
        assertEquals(listOf(0.0, 6.0), starts(still)); assertEquals(6.0, still.joins[1].time, 0.0)
        // "Opening still holds black before source footage": 9 s, TransitionTests.swift:288.
        val opening = TransitionLayout.setting(two, TransitionSettings(style = TransitionStyle.Still, duration = 1.0), 0)
        assertEquals(9.0, opening.duration, 0.0); assertEquals(listOf(1.0, 5.0), starts(opening))
        // "Closing still includes the final fade to black": 10.5 s, TransitionTests.swift:289.
        val closing = TransitionLayout.setting(two, freeze, 2)
        assertEquals(10.5, closing.duration, 0.0); assertEquals(listOf(0.0, 4.0), starts(closing))
        // The clips-only layout agrees on all of them.
        fun length(index: Int, settings: TransitionSettings) = TransitionLayout.sequence(listOf(4.0, 4.0), List(3) { if (it == index) settings else null }).duration
        assertEquals(5.0, length(1, TransitionSettings(duration = 1.0, hold = 2.0)), tiny)
        assertEquals(10.0, length(1, TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0, hold = 2.0)), 0.0)
        assertEquals(10.0, length(1, freeze), 0.0); assertEquals(10.5, length(2, freeze), 0.0)
        assertEquals(9.0, length(0, TransitionSettings(style = TransitionStyle.Still, duration = 1.0)), 0.0)
    }

    @Test fun reEditingAnOpeningJCutStaysAnchored() {
        // Offsets −0.5, −0.2, 0, −0.5 with blend 0: starts and duration never move, TransitionTests.swift:273-278.
        // The lead is reserved at time zero: origin = max(0, start − rightStart), Transitions.swift:373-374.
        var opening = two
        for (offset in listOf(-0.5, -0.2, 0.0, -0.5)) {
            opening = TransitionLayout.setting(opening, TransitionSettings(duration = 1.0, audioOffset = offset, audioBlend = 0.0), 0)
            assertEquals(listOf(0.0, 4.0), starts(opening)); assertEquals(8.0, opening.duration, 0.0)
            assertEquals(-offset, opening.relations.single().origin, tiny)
        }
        // origins[0] = −min(0, rightStart), Transitions.swift:226.
        val sequence = TransitionLayout.sequence(listOf(4.0, 4.0), listOf(TransitionSettings(duration = 1.0, audioOffset = -0.5, audioBlend = 0.0), null, null))
        assertEquals(0.5, sequence.joins[0].origin, 0.0); assertEquals(0.0, sequence.placements[0].start, 0.0); assertEquals(8.0, sequence.duration, 0.0)
    }

    // MARK: Fitting

    @Test fun aTransitionThatFitsKeepsFactorOne() {
        val fit = TransitionLayout.fit(TransitionSettings(duration = 1.0), 0, 4.0, 4.0)!!
        assertEquals(1.0, fit.factor, 0.0); assertEquals(1.0, fit.timing.videoDuration, 0.0)
        // Exactly the whole clip still fits: a clip can give all of itself, Transitions.swift:361.
        assertEquals(1.0, TransitionLayout.fit(TransitionSettings(duration = 4.0), 0, 4.0, 4.0)!!.factor, 0.0)
    }

    @Test fun aShortClipShrinksPictureAndSoundTogether() {
        // 48 halvings for the largest factor whose costs fit, Transitions.swift:363-368.
        val fit = TransitionLayout.fit(TransitionSettings(duration = 2.0), 0, 0.5, 4.0)!!
        assertEquals(0.25, fit.factor, fitted)
        assertEquals(0.5, fit.timing.videoDuration, fitted)
        assertRange(fit.timing.audioOut, 0.0, 0.5)
        assertTrue(fit.timing.leftCost <= 0.5 + 0.00000001)
        // Sound that reaches further than the picture is part of what must fit, Transitions.swift:150-154.
        val trail = TransitionLayout.fit(TransitionSettings(duration = 1.0, audioOffset = 1.0, audioBlend = 1.0), 0, 1.0, 4.0)!!
        assertEquals(0.5, trail.factor, fitted)
        assertEquals(0.5, trail.timing.videoDuration, fitted); assertRange(trail.timing.audioOut, 0.5, 1.0)
        // A fade between two clips takes half from each side.
        val fade = TransitionLayout.fit(TransitionSettings(style = TransitionStyle.FadeColor, duration = 2.0), 0, 0.25, 4.0)!!
        assertEquals(0.25, fade.factor, fitted); assertEquals(0.5, fade.timing.videoDuration, fitted)
    }

    @Test fun aColorHoldAndAFreezeAreNotShortened() {
        // "Solid gaps and silent freezes need no source handles", Transitions.swift:117-121.
        val fade = TransitionLayout.fit(TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0, hold = 10.0), 0, 0.25, 4.0)!!
        assertEquals(0.5, fade.factor, fitted); assertEquals(10.0, fade.timing.hold, 0.0); assertEquals(0.5, fade.timing.motion, fitted)
        val freeze = TransitionLayout.fit(TransitionSettings(style = TransitionStyle.Still, duration = 30.0, stillDissolve = 1.0), 0, 0.2, 0.5)!!
        assertEquals(0.5, freeze.factor, fitted); assertEquals(30.0, freeze.timing.hold, 0.0); assertEquals(0.5, freeze.timing.motion, fitted)
        // A freeze with no exit dissolve needs nothing at all.
        assertEquals(1.0, TransitionLayout.fit(TransitionSettings(style = TransitionStyle.Still, duration = 30.0), 0, 0.0, 0.0)!!.factor, 0.0)
        // With custom sound the footage runs under the hold, so the hold is shortened with the rest.
        val custom = TransitionLayout.fit(TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0, hold = 3.0, audioOffset = 0.0, audioBlend = 4.0), 0, 2.0, 8.0)!!
        assertEquals(0.5, custom.factor, fitted); assertEquals(1.5, custom.timing.hold, fitted)
    }

    @Test fun underOneFrameThereIsNoTransition() {
        // "guard t.videoDuration >= TimelineEditing.frame - 0.000001", Transitions.swift:370.
        assertNull(TransitionLayout.fit(TransitionSettings(duration = 1.0), 0, 0.0, 4.0))
        assertNull(TransitionLayout.fit(TransitionSettings(duration = 1.0), 0, 0.02, 4.0))
        assertNotNull(TransitionLayout.fit(TransitionSettings(duration = 1.0), 0, 1.0 / 30, 4.0))
        assertNotNull(TransitionLayout.fit(TransitionSettings(duration = 1.0 / 30), 0, 4.0, 4.0))
        val edit = TransitionLayout.setting(TransitionLayout.arrangement(listOf("A", "B"), listOf(0.02, 4.0)), TransitionSettings(duration = 1.0), 1)
        assertTrue(edit.relations.isEmpty()); assertEquals(4.02, edit.duration, fitted)
    }

    @Test fun chainedNeighboursNeverShareFootage() {
        // Three 4 s clips, a 3 s dissolve at each join, set one after the other, TransitionTests.swift:49-54.
        // The first is in place and keeps its 3 s; the second gets the second left over in the middle clip:
        // leftFree = duration − rightCost of the incoming transition, Transitions.swift:354-356.
        val chain = TransitionLayout.setting(TransitionLayout.setting(three, TransitionSettings(duration = 3.0), 1), TransitionSettings(duration = 3.0), 2)
        val first = chain.joins[1]
        val second = chain.joins[2]
        assertEquals(1.0, first.factor, 0.0); assertEquals(3.0, first.timing.videoDuration, 0.0)
        assertEquals(1.0 / 3, second.factor, fitted); assertEquals(1.0, second.timing.videoDuration, fitted)
        assertEquals(listOf(0.0, 1.0, 4.0), starts(chain).map { Math.round(it * 1e6) / 1e6 }); assertEquals(8.0, chain.duration, fitted)
        // "Adjacent transitions never require three simultaneous clips".
        assertTrue(chain.entering("B")!!.timing.rightCost + chain.leaving("B")!!.timing.leftCost <= 4.000001)
        // "Transition windows never cross".
        assertTrue(first.range.endInclusive <= second.range.start + 0.000001)
    }

    @Test fun theClipsOnlyLayoutSharesTheShortfallBetweenNeighbours() {
        // The same request laid out from the clips alone: both shrink by one factor, Transitions.swift:213-222.
        val settings = TransitionSettings(duration = 3.0)
        val layout = TransitionLayout.sequence(listOf(4.0, 4.0, 4.0), listOf(null, settings, settings, null))
        assertEquals(2.0 / 3, layout.joins[1].factor, fitted); assertEquals(2.0 / 3, layout.joins[2].factor, fitted)
        assertEquals(2.0, layout.joins[1].timing.videoDuration, fitted); assertEquals(2.0, layout.joins[2].timing.videoDuration, fitted)
        assertEquals(8.0, layout.duration, fitted)
        assertTrue(layout.placements[1].incoming + layout.placements[1].outgoing <= 4.000001)
        assertTrue(layout.joins[1].range.endInclusive <= layout.joins[2].range.start + 0.000001)
        assertEquals(2.0, layout.placements[1].start, fitted); assertEquals(4.0, layout.placements[2].start, fitted)
        // Room left for each join: the clip minus what the transition on its other side occupies, Transitions.swift:238-243.
        assertEquals(2.0, layout.joins[1].maximum, fitted)
        val fade = TransitionLayout.sequence(listOf(4.0, 4.0), listOf(null, TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0), null))
        assertEquals(8.0, fade.joins[1].maximum, 0.0)
        assertEquals(604800.0, TransitionLayout.sequence(listOf(4.0, 4.0), listOf(null, TransitionSettings(style = TransitionStyle.Still), null)).joins[1].maximum, 0.0)
    }

    @Test fun anOpeningHoldCannotCollideWithTheNextTransition() {
        // Opening fade 1 s with a 10 s hold, then a 1 s dissolve at the first cut, TransitionTests.swift:110-112.
        val opening = TransitionLayout.setting(two, TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0, hold = 10.0), 0)
        // At an edge the hold is shortened with the fade: 11 s into a 4 s clip.
        assertEquals(4.0 / 11, opening.joins[0].factor, fitted); assertRange(opening.joins[0].range, 0.0, 4.0)
        val both = TransitionLayout.setting(opening, TransitionSettings(duration = 1.0), 1)
        assertTrue(both.joins[0].range.endInclusive <= both.joins[1].range.start + 0.000001)
        // The first clip has nothing left, so the dissolve is not created.
        assertEquals(1, both.relations.size); assertNull(both.joins[1].transition)
    }

    @Test fun aNewTransitionTakesItsTwoSlots() {
        // "Each picture has one incoming and one outgoing transition slot", Transitions.swift:343-347.
        val dissolve = TransitionLayout.setting(three, TransitionSettings(duration = 1.0), 1)
        val replaced = TransitionLayout.setting(dissolve, TransitionSettings(style = TransitionStyle.Wipe, duration = 2.0), 1)
        assertEquals(TransitionStyle.Wipe, replaced.relations.single().settings.style)
        assertEquals(listOf(0.0, 2.0, 6.0), starts(replaced)); assertEquals(10.0, replaced.duration, 0.0)
        // The clips after the join move with it, and so do their transitions.
        val later = TransitionLayout.setting(TransitionLayout.setting(three, TransitionSettings(duration = 1.0), 2), TransitionSettings(duration = 1.0), 3)
        val moved = TransitionLayout.setting(later, TransitionSettings(duration = 2.0), 1)
        assertEquals(listOf(0.0, 2.0, 5.0), starts(moved)); assertEquals(9.0, moved.duration, 0.0)
        assertEquals(5.0, moved.leaving("B")!!.origin, 0.0); assertEquals(8.0, moved.leaving("C")!!.origin, 0.0)
        assertRange(moved.joins[3].range, 8.0, 9.0)
    }

    @Test fun aTrimThatTakesTheFootageTakesTheTransition() {
        // "A trim that removes required transition handles removes the invalid relation" and
        // "Extending does not resurrect removed transitions", TransitionTests.swift:40-42; refit in TrackTimeline.swift:856-887.
        val edit = TransitionLayout.setting(two, TransitionSettings(duration = 1.0), 1)
        fun withFirst(duration: Double, from: TransitionLayout.Arrangement) = from.copy(pictures = from.pictures.map { if (it.id == "A") it.copy(duration = duration) else it })
        val trimmed = TransitionLayout.refitted(withFirst(0.5, edit))
        assertTrue(trimmed.relations.isEmpty())
        assertTrue(TransitionLayout.refitted(withFirst(4.0, trimmed)).relations.isEmpty())
        // When the clips still overlap, by less, the factor follows the overlap.
        val closer = TransitionLayout.refitted(edit.copy(pictures = edit.pictures.map { if (it.id == "B") it.copy(start = 3.5) else it }))
        assertEquals(0.5, closer.relations.single().factor, 1e-6); assertEquals(3.5, closer.relations.single().origin, 1e-6)
        // A transition whose clip is gone goes with it.
        assertTrue(TransitionLayout.refitted(edit.copy(pictures = edit.pictures.filter { it.id != "B" })).relations.isEmpty())
        // Untouched pictures keep their transition exactly.
        assertEquals(edit, TransitionLayout.refitted(edit))
    }

    @Test fun variedFootprintsAlwaysFitTheFootage() {
        // The original's 90 deterministic runs: three clips of 0.2…4 s, every join set in turn with a random
        // style, duration, hold and custom sound, TransitionTests.swift:352-374.
        var seed = 71UL
        fun random(): Double {
            seed = seed * 6364136223846793005UL + 1442695040888963407UL
            return (seed shr 11).toDouble() / 9007199254740992.0
        }
        val styles = TransitionStyle.entries
        for (run in 0 until 90) {
            var edit = TransitionLayout.arrangement(listOf("A", "B", "C"), List(3) { 0.2 + random() * 3.8 })
            for (boundary in 0..3) {
                val style = styles[(run + boundary) % styles.size]
                var settings = TransitionSettings(style = style, duration = 0.1 + random() * 3)
                settings = settings.copy(hold = random() * 10, audioOffset = random() * 4 - 2, audioBlend = random() * 2)
                if (style == TransitionStyle.Still) settings = settings.copy(stillDissolve = random())
                assertTrue(settings.valid)
                edit = TransitionLayout.setting(edit, settings, boundary)
            }
            for (picture in edit.pictures) {
                val incoming = edit.entering(picture.id)?.timing?.rightCost ?: 0.0
                val outgoing = edit.leaving(picture.id)?.timing?.leftCost ?: 0.0
                assertTrue("run $run, clip ${picture.id}: $incoming + $outgoing > ${picture.duration}", incoming + outgoing <= picture.duration + TransitionLayout.TICK)
            }
            // Nothing starts before zero and no two pictures other than neighbours overlap.
            val ordered = edit.chronological
            assertTrue(ordered.first().start >= -tiny)
            assertTrue("run $run", ordered[2].start >= ordered[0].end - 0.000001)
            for (relation in edit.relations) assertTrue(TransitionLayout.longEnough(relation.timing))
        }
    }

    @Test fun invalidAndMisplacedSettingsAreIgnoredByTheClipsOnlyLayout() {
        // "if let value, value.valid, index == 0 || value.nextClipID == right?.id", Transitions.swift:207.
        val durations = listOf(4.0, 4.0)
        assertEquals(8.0, TransitionLayout.sequence(durations, listOf(null, TransitionSettings(duration = -1.0), null)).duration, 0.0)
        val ids = listOf("A", "B")
        assertEquals(8.0, TransitionLayout.sequence(durations, listOf(null, TransitionSettings(duration = 1.0, nextClipID = "X"), null), ids).duration, 0.0)
        assertEquals(7.0, TransitionLayout.sequence(durations, listOf(null, TransitionSettings(duration = 1.0, nextClipID = "B"), null), ids).duration, 0.0)
        // A closing transition names no clip; an opening one is not checked.
        assertEquals(1.0, TransitionLayout.sequence(durations, listOf(TransitionSettings(nextClipID = "X"), null, TransitionSettings(duration = 1.0)), ids).joins[2].timing.videoDuration, 0.0)
        assertEquals(0.5, TransitionLayout.sequence(durations, listOf(TransitionSettings(nextClipID = "X"), null, null), ids).joins[0].timing.videoDuration, 0.0)
        assertEquals(0.0, TransitionLayout.sequence(emptyList(), emptyList()).duration, 0.0)
        // Missing entries are plain cuts.
        assertEquals(8.0, TransitionLayout.sequence(durations, emptyList()).duration, 0.0)
    }

    @Test fun theTransitionUnderTheTime() {
        val layout = TransitionLayout.sequence(listOf(4.0, 4.0), listOf(null, TransitionSettings(duration = 1.0), null))
        assertNull(layout.transition(2.9)); assertEquals(1, layout.transition(3.0)?.index); assertEquals(1, layout.transition(3.9)?.index); assertNull(layout.transition(4.0))
    }

    // MARK: Editor defaults

    @Test fun aNewTransitionIsAHalfSecondDissolveOrShorter() {
        // "ClipTransition(duration: min(0.5, join.maximum))", TransitionEditor.swift:64; default style dissolve, Transitions.swift:45.
        assertEquals(TransitionStyle.Dissolve, TransitionDefaults.new(4.0).style)
        assertEquals(0.5, TransitionDefaults.new(4.0).duration, 0.0)
        assertEquals(0.3, TransitionDefaults.new(0.3).duration, 0.0)
        // The axis follows the canvas the first time, TransitionEditor.swift:106.
        assertEquals(TransitionAxis.Vertical, TransitionDefaults.new(4.0, Size(1080.0, 1920.0)).axis)
        assertEquals(TransitionAxis.Horizontal, TransitionDefaults.new(4.0, Size(1920.0, 1080.0)).axis)
        assertEquals(TransitionAxis.Horizontal, TransitionDefaults.new(4.0, Size(1080.0, 1080.0)).axis)
    }

    @Test fun whipAndStillGetTheirOwnDurationTheFirstTimeTheyAreChosen() {
        // selectStyle: whip 8/30 s, still 1 s, once per style, TransitionEditor.swift:52-59.
        val start = TransitionDefaults.new(4.0)
        val visited = setOf(TransitionStyle.Dissolve)
        val whip = TransitionDefaults.choosing(start, TransitionStyle.Whip, visited)
        assertEquals(TransitionStyle.Whip, whip.style); assertEquals(8.0 / 30, whip.duration, 0.0)
        assertEquals(1.0, TransitionDefaults.choosing(start, TransitionStyle.Still, visited).duration, 0.0)
        assertEquals(0.5, TransitionDefaults.choosing(start, TransitionStyle.Wipe, visited).duration, 0.0)
        // Coming back to it keeps what was set meanwhile.
        val longer = whip.copy(duration = 2.0)
        assertEquals(2.0, TransitionDefaults.choosing(longer, TransitionStyle.Whip, visited + TransitionStyle.Whip).duration, 0.0)
        assertEquals(start.id, whip.id)
    }

    @Test fun whatTheSlidersOffer() {
        // maximum: still ? max(60, project length, duration) : join.maximum, TransitionEditor.swift:11.
        assertEquals(4.0, TransitionDefaults.durationMaximum(TransitionSettings(), 4.0, 100.0), 0.0)
        assertEquals(100.0, TransitionDefaults.durationMaximum(TransitionSettings(style = TransitionStyle.Still), 4.0, 100.0), 0.0)
        assertEquals(60.0, TransitionDefaults.durationMaximum(TransitionSettings(style = TransitionStyle.Still), 4.0, 8.0), 0.0)
        // audioMaximum: min(60, max(2, longest clip)), TransitionEditor.swift:14.
        assertEquals(2.0, TransitionDefaults.audioMaximum(1.0), 0.0); assertEquals(60.0, TransitionDefaults.audioMaximum(90.0), 0.0); assertEquals(2.0, TransitionDefaults.audioMaximum(null), 0.0)
        // displayedAudioBlend, TransitionEditor.swift:15-17.
        val fade = TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0, hold = 2.0)
        assertEquals(1.0, TransitionDefaults.displayedAudioBlend(fade, 0), 0.0); assertEquals(3.0, TransitionDefaults.displayedAudioBlend(fade, -1), 0.0)
        assertEquals(0.25, TransitionDefaults.displayedAudioBlend(fade.copy(audioBlend = 0.25), 1), 0.0)
        // Hold slider 0…max(10, min(60, maximum)), TransitionEditor.swift:332.
        assertEquals(10.0, TransitionDefaults.holdMaximum(4.0), 0.0); assertEquals(60.0, TransitionDefaults.holdMaximum(100.0), 0.0)
        // "Dissolve" after the freeze starts at 0.3 s, TransitionEditor.swift:321.
        assertEquals(0.3, TransitionDefaults.STILL_DISSOLVE, 0.0)
    }
}
