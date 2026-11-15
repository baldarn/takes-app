package app.takes.core

import app.takes.core.TrackEditingSupport.assertAtRest
import app.takes.core.TrackEditingSupport.ticks
import app.takes.core.TrackFixtures.uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transitions on the track timeline: setting one, removing one, and what
 * every other edit does to them. The checks that come from the original's
 * `Tests/TransitionTests.swift` and `Tests/TrackTimelineTests.swift` name
 * their lines and keep its numbers.
 */
class TrackTransitionsTest {
    // Tests/TransitionTests.swift:9-15: two takes of four seconds, each from its own source.
    private val first = SourceMedia(id = uuid(1), filename = "source.mp4", duration = 4.0)
    private val second = SourceMedia(id = uuid(2), filename = "portrait.mp4", duration = 4.0)
    private val pair = listOf(TimelineClip(uuid(3), first.id, 0.0, 4.0), TimelineClip(uuid(4), second.id, 0.0, 4.0))
    private val original = EditState(pair).toTimeline()
    private val dissolve = TransitionSettings(id = uuid(5), nextClipID = pair[1].id, duration = 1.0)
    private val edit = original.settingTransition(dissolve, 1)
    private val sources = listOf(first, second)

    // Tests/TrackTimelineTests.swift:809-810: three clips of four seconds from one source of twenty.
    private val long = SourceMedia(id = uuid(20), filename = "source.mp4", duration = 20.0)
    private val clips = (0 until 3).map { TimelineClip(uuid(21 + it), long.id, it * 4.0, (it + 1) * 4.0) }
    private val three = EditState(clips).toTimeline()

    private fun TrackTimeline.picture(id: String) = video.first { it.id == id }
    private fun TrackTimeline.layout() = TrackLayout.of(this)
    private fun TrackTimeline.withSpeed(index: Int, rate: Double) =
        replacingVideoContents(projectedClips.toMutableList().also { it[index] = it[index].copy(speed = SpeedSettings(rate = rate)) })
    private fun settings(duration: Double, style: TransitionStyle = TransitionStyle.Dissolve) = TransitionSettings(style = style, duration = duration)

    // MARK: Setting

    /** Tests/TransitionTests.swift:14-17: "Overlap shifts the second clip and join by the correct amounts", "Adding an overlap never changes source trims". */
    @Test
    fun aDissolveOverlapsTheTwoClipsAndMovesTheSecond() {
        assertEquals(ticks(7.0), edit.duration)
        assertEquals(3.0, edit.layout().placements[1].start, 0.0)
        assertEquals(3.5, edit.layout().joins[1].time, 0.0)
        assertEquals(original.video.map { it.content.start to it.content.end }, edit.video.map { it.content.start to it.content.end })
        assertEquals(listOf(TimelineTransition(pair[0].id, pair[1].id, dissolve, ticks(3.0), 1.0)), edit.transitions)
        // The sound that follows the second picture went with it.
        assertEquals(ticks(3.0), edit.audio[1].start)
        assertAtRest("dissolve", edit, sources)
    }

    @Test
    fun whatCannotBeSetChangesNothing() {
        assertSame(original, original.settingTransition(dissolve, 9))
        assertSame(original, original.settingTransition(dissolve.copy(duration = -1.0), 1))
        // Under one frame of picture there is no transition (YouRec/Transitions.swift:369-370).
        assertEquals(original, original.settingTransition(settings(0.01), 1))
        // Between pictures that do not meet there is nothing to join.
        val gapped = TrackMove.moved(original, TrackLinking.selection(original, pair[1].id), ticks(1.0))
        assertSame(gapped, gapped.settingTransition(dissolve, 1))
    }

    /** Tests/TransitionTests.swift:20-22: "Splitting inside an overlap is refused without modifying footage", "Splits preserve the outgoing join and duration". */
    @Test
    fun aSplitInsideATransitionIsRefusedAndOutsideItTheJoinLeavesTheLaterPiece() {
        assertSame(edit, TrackEditing.splitAtPlayhead(edit, 3.25))
        val split = TrackEditing.splitAtPlayhead(edit, 1.0)
        val projected = split.projectedClips
        assertEquals(3, projected.size)
        assertEquals(ticks(7.0), split.duration)
        assertEquals(dissolve, projected[1].transition)
        assertNull(projected[0].transition)
        // The fit is the one it had (docs/TRANSITIONS-FIT.md, 2.3).
        assertEquals(edit.transitions.map { it.factor to it.origin }, split.transitions.map { it.factor to it.origin })
        assertAtRest("split", split, sources)
    }

    /** Tests/TransitionTests.swift:23-25: "Removing a transition retains explicit clip positions", "Explicit straight-cut restoration recovers sequence duration". */
    @Test
    fun removingLeavesThePicturesWhereTheyAreAndRestoringClosesTheTimeline() {
        val removed = edit.settingTransition(null, 1)
        assertTrue(removed.transitions.isEmpty())
        assertEquals(edit.video.map { it.start }, removed.video.map { it.start })
        assertEquals(original.duration, edit.restoringStraightCut(1).duration)
        assertEquals(original, edit.restoringStraightCut(1))
        assertEquals(original, TrackTransitions.removing(edit, dissolve.id))
        assertSame(edit, TrackTransitions.removing(edit, uuid(999)))
    }

    /** Tests/TransitionTests.swift:30-33, :37: "Transitions extend beyond ten seconds up to available footage", "Removing a long overlap restores all footage". */
    @Test
    fun aTransitionMayBeAsLongAsTheFootageThereIs() {
        val slow = EditState(pair.map { it.copy(speed = SpeedSettings(rate = 0.25)) }).toTimeline()
        val longEdit = slow.settingTransition(settings(12.0), 1)
        assertEquals(16.0, longEdit.layout().joins[1].maximum, 0.0)
        assertEquals(ticks(20.0), longEdit.duration)
        assertEquals(ticks(32.0), longEdit.restoringStraightCut(1).duration)
        longEdit.validate(sources)
    }

    /** Tests/TransitionTests.swift:38-39: "Reordering payload storage never reassigns a transition or changes participant identity". */
    @Test
    fun theOrderClipsAreKeptInNeverReassignsATransition() {
        val reordered = edit.replacingVideoContents(edit.projectedClips.reversed())
        assertEquals(edit.transitions, reordered.transitions)
        assertEquals(edit.video.toSet(), reordered.video.toSet())
    }

    /** Tests/TransitionTests.swift:40-42: "A trim that removes required transition handles removes the invalid relation", "Extending does not resurrect removed transitions". */
    @Test
    fun aTrimThatTakesATransitionsFootageRemovesItForGood() {
        val trimmed = TrackEditing.settingSourceBounds(edit, pair[0].id, 0.0, 0.5)!!
        assertTrue(trimmed.transitions.isEmpty())
        assertTrue(trimmed.generated.isEmpty())
        assertEquals(ticks(0.5), trimmed.picture(pair[1].id).start)
        val back = TrackEditing.settingSourceBounds(trimmed, pair[0].id, 0.0, 4.0)!!
        assertTrue(back.transitions.isEmpty())
        assertEquals(original.video, back.video)
        // A trim that leaves the footage keeps the transition as it was fitted, in its place after the clip.
        val less = TrackEditing.settingSourceBounds(edit, pair[0].id, 0.0, 3.0)!!
        assertEquals(listOf(1.0), less.transitions.map { it.factor })
        assertEquals(ticks(2.0), less.picture(pair[1].id).start)
        assertAtRest("less", less, sources)
    }

    /** Tests/TransitionTests.swift:43-48: "Fade through black retains timeline length", "Black opening/closing retain the whole project". */
    @Test
    fun aFadeThroughColorAnOpeningAndACloseKeepTheLength() {
        val faded = original.settingTransition(dissolve.copy(style = TransitionStyle.FadeColor), 1)
        assertEquals(ticks(8.0), faded.duration)
        assertEquals(3.5..4.5, faded.layout().joins[1].range)
        val ends = original.settingTransition(settings(1.0), 0).settingTransition(settings(1.0), 2)
        assertEquals(ticks(8.0), ends.duration)
        assertEquals(0.0..1.0, ends.layout().joins[0].range)
        assertEquals(7.0..8.0, ends.layout().joins[2].range)
        assertEquals(listOf(-1, 1), ends.transitions.map { it.edge })
        assertAtRest("ends", ends, sources)
    }

    /** Tests/TransitionTests.swift:49-54: "Adjacent transitions never require three simultaneous clips", "Transition windows never cross". */
    @Test
    fun aSecondTransitionGetsOnlyWhatTheFirstLeftFree() {
        val third = pair[0].copy(id = uuid(6))
        val chain = EditState(pair + third).toTimeline().settingTransition(settings(3.0), 1).settingTransition(settings(3.0), 2)
        val layout = chain.layout()
        assertTrue(layout.placements[1].incoming + layout.placements[1].outgoing <= 4.000001)
        assertTrue(layout.joins[1].range.endInclusive <= layout.joins[2].range.start + 0.000001)
        // The first keeps its fit; the second finds one second free in the middle clip (docs/TRANSITIONS-FIT.md, 2.1).
        assertEquals(1.0, chain.transitions[0].factor, 0.0)
        assertEquals(1.0 / 3, chain.transitions[1].factor, 0.000001)
        assertEquals(ticks(8.0), chain.duration)
        assertAtRest("chain", chain, sources)
    }

    /** Tests/TransitionTests.swift:110-112: "Opening color holds cannot collide with the next transition". */
    @Test
    fun anOpeningThatHoldsCannotReachIntoTheNextTransition() {
        val hold = TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0, hold = 10.0)
        val layout = original.settingTransition(hold, 0).settingTransition(dissolve, 1).layout()
        assertTrue(layout.joins[0].range.endInclusive <= layout.joins[1].range.start + 0.000001)
    }

    /** Tests/TransitionTests.swift:352-373: ninety runs of varied footprints, "Long holds and custom sound fit available footage". */
    @Test
    fun variedTransitionsOnEveryJoinFitTheFootageThereIs() {
        var seed = 71L
        fun random(): Double {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return (seed ushr 11).toDouble() / 9007199254740992.0
        }
        val styles = TransitionStyle.entries
        repeat(90) { run ->
            val ends = List(3) { 0.2 + random() * 3.8 }
            var timeline = EditState(listOf(pair[0].copy(end = ends[0]), pair[1].copy(end = ends[1]), pair[0].copy(id = uuid(6), end = ends[2]))).toTimeline()
            for (boundary in 0..3) {
                val style = styles[(run + boundary) % styles.size]
                val duration = 0.1 + random() * 3
                val hold = random() * 10
                val offset = random() * 4 - 2
                val blend = random() * 2
                timeline = timeline.settingTransition(
                    TransitionSettings(style = style, duration = duration, hold = hold, audioOffset = offset, audioBlend = blend, stillDissolve = if (style == TransitionStyle.Still) random() else null),
                    boundary,
                )
            }
            assertAtRest("run $run", timeline, sources)
            val layout = timeline.layout()
            for (placement in layout.placements) {
                val (incoming, outgoing) = layout.joins(placement)
                assertTrue(
                    "run $run, clip ${placement.index}, incoming ${incoming.timing.rightCost} (${incoming.transition?.style}, ${incoming.factor}), " +
                        "outgoing ${outgoing.timing.leftCost} (${outgoing.transition?.style}, ${outgoing.factor}), duration ${placement.clip.duration}, " +
                        "${timeline.transitions.map { Triple(it.outgoing?.takeLast(4), it.incoming?.takeLast(4), it.factor) }} ${timeline.video.map { it.interval }}",
                    // The original measures against the clip's length rounded to a tick. A picture here is as long as the
                    // conversion made it, the distance between two rounded boundaries, which can be a tick more.
                    incoming.timing.rightCost + outgoing.timing.leftCost <= timeline.picture(placement.clip.id).mapping.duration.seconds + 1.0 / EditTime.SCALE,
                )
            }
            assertEquals("run $run", timeline, TrackTimeline.decoded(timeline.encoded()))
        }
    }

    // MARK: With links

    /** Tests/TrackTimelineTests.swift:819-828: "A transition inside a horizontal link keeps its outgoing picture fixed", "Removing a linked transition restores the straight cut". */
    @Test
    fun aTransitionInsideOneLinkKeepsItsOutgoingPictureFixed() {
        val members = setOf(clips[0].id, clips[1].id, TrackTimeline.counterpart(clips[0].id), TrackTimeline.counterpart(clips[1].id))
        val linked = three.copy(links = three.links.filter { link -> link.members.none { it in members } } + TimelineLink(uuid(30), members))
        linked.validate(listOf(long))
        val dissolved = linked.settingTransition(settings(1.0), 1)
        dissolved.validate(listOf(long))
        assertEquals(EditTime.ZERO, dissolved.video[0].start)
        assertEquals(ticks(3.0), dissolved.video[1].start)
        assertEquals(ticks(11.0), dissolved.duration)
        val straight = dissolved.restoringStraightCut(1)
        straight.validate(listOf(long))
        assertEquals(three.video.map { it.start }, straight.video.map { it.start })
        assertEquals(ticks(12.0), straight.duration)
    }

    /** Tests/TrackTimelineTests.swift:133-142: "Transition handles do not cause playhead drift after deletion", "Deleting a linked clip between two transitions closes only the newly vacated gap". */
    @Test
    fun deletingAClipBetweenTwoTransitionsClosesOnlyWhatItLeft() {
        val source = SourceMedia(id = uuid(40), filename = "source.mp4", duration = 4.0)
        val a = TimelineClip(uuid(41), source.id, 0.0, 2.0)
        val b = TimelineClip(uuid(42), source.id, 2.0, 4.0)
        val c = TimelineClip(uuid(43), source.id, 0.0, 2.0)
        val timeline = EditState(listOf(a, b, c)).toTimeline().settingTransition(settings(0.5), 1).settingTransition(settings(0.5), 2)
        assertEquals(ticks(5.0), timeline.duration)
        val priorLast = timeline.video[2].start
        val removal = timeline.removing(TrackLinking.selection(timeline, b.id), priorLast + ticks(1.0))
        val after = removal.timeline
        assertEquals(after.picture(c.id).start + ticks(1.0), removal.position)
        assertEquals(after.video[0].end, after.video[1].start)
        assertEquals(ticks(4.0), after.duration)
        // Both relations named the removed picture (docs/TRANSITIONS-FIT.md, 2.7).
        assertTrue(after.transitions.isEmpty() && after.generated.isEmpty())
        assertAtRest("removed", after, listOf(source))
    }

    @Test
    fun deletingAClipLeavesTheTransitionsOfTheOthersAsTheyWereFitted() {
        val timeline = three.settingTransition(settings(3.0), 1).settingTransition(settings(3.0), 2)
        val shortened = timeline.transitions[1]
        assertTrue(shortened.factor < 1)
        val without = timeline.removing(TrackLinking.selection(timeline, clips[0].id)).timeline
        assertEquals(listOf(shortened.id), without.transitions.map { it.id })
        // It keeps the fit it was given, though the middle clip now has room for all of it.
        assertEquals(shortened.factor, without.transitions[0].factor, 0.0)
        assertEquals(EditTime.ZERO, without.picture(clips[1].id).start)
        assertEquals(ticks(7.0), without.duration)
        assertAtRest("without", without, listOf(long))
    }

    // MARK: Speed

    /** Tests/TrackTimelineTests.swift:830-835: "Speed refits a full-length transition without negative time or dropping it", "Shrinking footage already inside an overlap does not move the next cut". */
    @Test
    fun speedRefitsATransitionThatNoLongerFits() {
        val full = three.settingTransition(settings(4.0), 1)
        assertEquals(ticks(8.0), full.duration)
        val compressed = full.withSpeed(0, 4.0)
        compressed.validate(listOf(long))
        assertEquals(1, compressed.transitions.size)
        assertEquals(EditTime.ZERO, compressed.video[1].start)
        assertEquals(ticks(8.0), compressed.duration)
        assertEquals(0.25, compressed.transitions[0].factor, 0.000001)
        // Slower again: the transition does not get its length back (docs/TRANSITIONS-FIT.md, 2.5).
        val slow = compressed.withSpeed(0, 1.0)
        assertEquals(compressed.transitions[0].factor, slow.transitions[0].factor, 0.0)
        assertAtRest("slow", slow, listOf(long))
    }

    @Test
    fun speedOnAClipKeepsTheTransitionsAroundItInPlace() {
        val timeline = three.settingTransition(settings(1.0), 1).settingTransition(settings(1.0), 2)
        val fast = timeline.withSpeed(1, 2.0)
        // The middle clip is two seconds now; both dissolves still fit it and keep their fit.
        assertEquals(listOf(1.0, 1.0), fast.transitions.map { it.factor })
        assertEquals(ticks(3.0), fast.picture(clips[1].id).start)
        assertEquals(ticks(4.0), fast.picture(clips[2].id).start)
        assertEquals(ticks(8.0), fast.duration)
        assertAtRest("fast", fast, listOf(long))
    }

    // MARK: Openings and closes stay with their clip

    /** Tests/TrackTimelineTests.swift:926-931: "Moved fades are anchored to their own picture", "A moved opening fade leaves the adjacent cut available". */
    @Test
    fun anOpeningGoesWhereItsPictureGoes() {
        val faded = three.settingTransition(settings(1.0), 0)
        val moved = TrackMove.moved(faded, TrackLinking.selection(faded, clips[0].id), ticks(12.0))
        val layout = moved.layout()
        val fade = layout.joins.first { it.transition != null }
        assertEquals(12.0, fade.time, 0.0)
        assertNull(layout.boundary(0.0))
        assertEquals(clips[2].id, layout.boundary(12.0)?.outgoingID)
        assertEquals(clips[0].id, layout.boundary(12.0)?.incomingID)
        assertEquals(ticks(12.0), moved.transitions[0].origin)
        assertAtRest("moved", moved, listOf(long))
    }

    @Test
    fun anOpeningAndACloseSurviveClipsPutAroundTheirPictureAndGoWithIt() {
        val ends = three.settingTransition(settings(1.0), 0).settingTransition(settings(1.0), 3)
        val opening = ends.transitions.first { it.edge == -1 }
        val close = ends.transitions.first { it.edge == 1 }
        // A take put before the first clip and one after the last: both fades stay on their pictures.
        val extra = TimelineClip(uuid(50), long.id, 12.0, 14.0)
        val before = TrackEditing.inserting(ends, listOf(extra), 0.0)
        assertEquals(ticks(2.0), before.transitions.first { it.id == opening.id }.origin)
        assertEquals(clips[0].id, before.transitions.first { it.id == opening.id }.incoming)
        val after = TrackEditing.inserting(before, listOf(extra), before.duration.seconds)
        assertEquals(2, after.transitions.size)
        assertEquals(clips[2].id, after.transitions.first { it.id == close.id }.outgoing)
        assertEquals(before.transitions.first { it.id == close.id }.origin, after.transitions.first { it.id == close.id }.origin)
        assertAtRest("after", after, listOf(long))
        // A split leaves the opening on the first piece and takes the close to the last.
        val split = ends.split(ticks(2.0), TrackLinking.entireSelection(ends)).timeline.split(ticks(10.0), TrackLinking.entireSelection(ends)).timeline
        assertEquals(clips[0].id, split.transitions.first { it.id == opening.id }.incoming)
        assertEquals(split.video.last().id, split.transitions.first { it.id == close.id }.outgoing)
        // Deleting the picture deletes its fade; the other stays.
        val gone = ends.removing(TrackLinking.selection(ends, clips[0].id)).timeline
        assertEquals(listOf(close.id), gone.transitions.map { it.id })
        assertEquals(ticks(7.0), gone.transitions[0].origin)
        // An opening is never dropped for want of footage: its picture cut to less than it lasts keeps it.
        val short = ends.scoped(TrackLinking.selection(ends, clips[0].id, range = EditInterval.of(0.5, 4.0))).let { it.timeline.removing(it.selection).timeline }
        assertTrue(short.transitions.any { it.id == opening.id })
        assertAtRest("short", short, listOf(long))
    }

    /** Tests/TrackTimelineTests.swift:940-954: a transition between two pictures takes the place of the fades on those sides, and of two in one slot the last stays. */
    @Test
    fun aTransitionTakesThePlaceOfTheFadesOnItsSides() {
        var fades = three.settingTransition(settings(1.0, TransitionStyle.FadeColor), 0).settingTransition(settings(1.0, TransitionStyle.FadeColor), 3)
        fades = TrackMove.moved(fades, TrackLinking.selection(fades, clips[0].id), ticks(12.0))
        val replaced = fades.settingTransition(settings(1.0), fades.layout().boundary(12.0)!!.index)
        replaced.validate(listOf(long))
        assertEquals(1, replaced.transitions.size)
        assertEquals(clips[2].id, replaced.transitions[0].outgoing)
        assertEquals(clips[0].id, replaced.transitions[0].incoming)
        val id = replaced.transitions[0].id
        assertTrue(replaced.compile().filter { it.transitionIDs.isNotEmpty() }.all { it.transitionIDs == listOf(id) })
        // "Validation rejects competing transition slots".
        assertEquals(TrackInvariants.Violation.TransitionSlot, TrackInvariants.violation(replaced.copy(transitions = replaced.transitions + fades.transitions), listOf(long)))
        // "Pruning deterministically retains the latest transition in each slot".
        assertEquals(listOf(id), replaced.copy(transitions = fades.transitions + replaced.transitions).pruned().transitions.map { it.id })
    }

    /** YouRec/Transitions.swift:343-347, :369-370: under one frame nothing is added, and what the slot rule removed stays removed. */
    @Test
    fun aTransitionUnderOneFrameStillTakesTheFadesOnItsSidesAway() {
        var fades = three.settingTransition(settings(1.0, TransitionStyle.FadeColor), 0).settingTransition(settings(1.0, TransitionStyle.FadeColor), 3)
        fades = TrackMove.moved(fades, TrackLinking.selection(fades, clips[0].id), ticks(12.0))
        assertEquals(2, fades.generated.size)
        val none = fades.settingTransition(settings(0.01), fades.layout().boundary(12.0)!!.index)
        assertTrue(none.transitions.isEmpty())
        // The original leaves the stretches of the removed fades behind here; this port prunes them.
        assertTrue(none.generated.isEmpty())
        assertEquals(fades.video, none.video)
        assertAtRest("none", none, listOf(long))
    }

    // MARK: After a move

    /** Tests/TrackTimelineTests.swift:843-846: "Transition after Move edits the correct cut". */
    @Test
    fun aTransitionSetAfterAMoveJoinsThePicturesThatMeetThere() {
        var reordered = TrackMove.moved(three, TrackLinking.selection(three, clips[0].id), ticks(12.0))
        reordered = TrackMove.moved(reordered, TrackLinking.selection(reordered, clips[2].id), ticks(-8.0))
        reordered = TrackMove.moved(reordered, TrackLinking.selection(reordered, clips[0].id), ticks(-4.0))
        val dissolved = reordered.settingTransition(settings(1.0), reordered.layout().boundary(4.0)!!.index)
        dissolved.validate(listOf(long))
        assertEquals(clips[2].id, dissolved.transitions.first().outgoing)
        assertEquals(clips[1].id, dissolved.transitions.first().incoming)
    }

    /** Tests/TrackTimelineTests.swift:847-851, :923-925: "A third overlay between participant starts cannot hide a transition", "Background sound envelopes follow participant IDs". */
    @Test
    fun aPictureLaidOverATransitionDoesNotHideIt() {
        val dissolved = three.settingTransition(settings(1.0), 1)
        var extra = EditState(listOf(TimelineClip(uuid(50), long.id, 0.0, 1.0))).toTimeline()
        extra = TrackMove.moved(extra, TrackLinking.entireSelection(extra), ticks(1.0))
        val covered = dissolved.copy(video = dissolved.video + extra.video, audio = dissolved.audio + extra.audio, links = dissolved.links + extra.links)
        val layout = covered.layout()
        assertTrue(layout.joins.any { it.transition?.id == dissolved.transitions[0].id && it.outgoingID == clips[0].id })
        assertNotNull(layout.joins(layout.placements.first { it.clip.id == clips[0].id }).second.transition)
    }

    // MARK: Drawn as a join

    /** Tests/TrackTimelineTests.swift:667-675: "Twenty sequential crossfades remain one picture row and one linked sound row". */
    @Test
    fun twentyDissolvesInARowAreOnePictureRowAndOneSoundRow() {
        var timeline = EditState((0 until 20).map { TimelineClip(uuid(100 + it), long.id, it * 0.6, (it + 1) * 0.6) }).toTimeline()
        for (boundary in 1 until 20) timeline = timeline.settingTransition(settings(0.2), boundary)
        assertEquals(19, timeline.transitions.size)
        val lanes = timeline.lanes()
        assertEquals(1, lanes.filter { !it.audio }.map { it.row }.toSet().size)
        assertEquals(1, lanes.filter { it.audio }.map { it.row }.toSet().size)
        lanes.forEachIndexed { index, lane ->
            assertTrue(lanes.drop(index + 1).all { it.row != lane.row || it.interval.intersection(lane.interval) == null })
        }
        assertAtRest("twenty", timeline, listOf(long))
    }

    /** Tests/TrackTimelineTests.swift:680-701: every style, with and without sound timing of its own, is drawn compactly and is removed by identity to a plain cut. */
    @Test
    fun everyStyleIsDrawnAsAJoinAndRemovedToAPlainCut() {
        val two = EditState(clips.take(2)).toTimeline()
        for (style in TransitionStyle.entries) {
            for (offset in listOf(null, -1.0, 0.0, 1.0)) {
                val name = "$style, $offset"
                val joined = two.settingTransition(TransitionSettings(style = style, duration = 1.0, hold = 0.4, audioOffset = offset, audioBlend = if (offset == null) null else 1.5), 1)
                val lanes = joined.lanes()
                assertEquals(name, 1, lanes.filter { !it.audio }.map { it.row }.toSet().size)
                assertEquals(name, 1, lanes.filter { it.audio }.map { it.row }.toSet().size)
                for (lane in lanes) {
                    val content = if (lane.audio) joined.audio.first { it.id == lane.id }.interval else joined.video.first { it.id == lane.id }.interval
                    assertEquals(name, content, lane.contentInterval)
                    assertTrue(name, !lane.interval.isEmpty && lane.interval.start >= content.start && lane.interval.end <= content.end)
                }
                val removed = TrackTransitions.removing(joined, joined.layout().joins[1].transition!!.id)
                assertTrue(name, removed.transitions.isEmpty())
                assertEquals(name, ticks(8.0), removed.duration)
                assertEquals(name, removed.video[0].end, removed.video[1].start)
                assertEquals(name, joined.video.map { it.mapping }, removed.video.map { it.mapping })
                assertEquals(name, joined.audio.map { it.mapping }, removed.audio.map { it.mapping })
                assertAtRest(name, joined, listOf(long))
            }
        }
    }

    /** Tests/TrackTimelineTests.swift:703-727. */
    @Test
    fun aDissolveIsDrawnMeetingAtItsMiddleWithTheHandlesKept() {
        val two = EditState(clips.take(2)).toTimeline()
        val joined = two.settingTransition(settings(2.0), 1)
        val lanes = joined.lanes()
        val outgoing = lanes.first { it.id == clips[0].id }
        val incoming = lanes.first { it.id == clips[1].id }
        // "Two four-second clips with a two-second dissolve meet at 3 s in a six-second timeline".
        assertEquals(ticks(3.0), outgoing.interval.end)
        assertEquals(ticks(3.0), incoming.interval.start)
        assertEquals(ticks(6.0), joined.duration)
        // "The incoming thumbnail/waveform starts at its correct source position, not the hidden transition handle".
        assertEquals(5.25, incoming.mapping.source(ticks(3.25) - incoming.contentInterval.start)!!, 0.0)
        // "Internal transition handles cannot masquerade as ordinary trim edges".
        assertTrue(outgoing.trimsLeft && !outgoing.trimsRight && !incoming.trimsLeft && incoming.trimsRight)
        // "Independently editable overlapping sound keeps separate lanes".
        val detached = TrackLinking.unlinked(joined, TrackLinking.selection(joined, clips[0].id))
        assertEquals(2, detached.lanes().filter { it.audio }.map { it.row }.toSet().size)
        // "A deliberate overlay remains a complete separate card above the compact transition".
        val cover = TimelineClip(uuid(60), long.id, 8.0, 10.0)
        val overlay = joined.copy(video = joined.video + VideoTimelineItem.of(cover.id, ticks(2.0), cover, 99))
        val layers = overlay.lanes()
        assertEquals(EditInterval.of(2.0, 4.0), layers.first { it.id == cover.id }.interval)
        assertEquals(2, layers.filter { !it.audio }.map { it.row }.toSet().size)
        // "Move exposes full source extents for accurate placement".
        assertTrue(joined.lanes(TrackLinking.selection(joined, clips[0].id).ids).all { it.interval == it.contentInterval })
        // "Removing the transition reveals the actual overlap instead of concealing a layer".
        assertEquals(2, joined.copy(transitions = emptyList()).pruned().lanes().filter { !it.audio }.map { it.row }.toSet().size)
    }

    // MARK: Generated edits

    @Test
    fun generatedEditSequencesWithTransitionsStayValidAndAtRest() {
        val random = TrackFixtures.Random(11)
        val all = TrackFixtures.sources
        val styles = TransitionStyle.entries
        repeat(120) { round ->
            var timeline = TrackFixtures.plain().toTimeline()
            repeat(30) { step ->
                val time = ticks(random.next(0.0, maxOf(1.0, timeline.duration.seconds)))
                fun item(): String? = timeline.allIDs.toList().let { if (it.isEmpty()) null else it[random.below(it.size)] }
                fun picture(): String? = timeline.video.let { if (it.isEmpty()) null else it[random.below(it.size)].id }
                fun join(): Int? = TrackLayout.of(timeline).joins.let { if (it.isEmpty()) null else it[random.below(it.size)].index }
                val operation = random.below(12)
                timeline = when (operation) {
                    0, 1, 2 -> join()?.let {
                        timeline.settingTransition(
                            TransitionSettings(
                                style = styles[random.below(styles.size)], duration = random.next(0.05, 4.0),
                                hold = if (random.next() < 0.4) random.next(0.0, 1.5) else null,
                                audioOffset = if (random.next() < 0.4) random.next(-0.7, 0.7) else null,
                                audioBlend = if (random.next() < 0.4) random.next(0.0, 1.0) else null,
                                stillDissolve = if (random.next() < 0.3) 0.3 else null,
                            ),
                            it,
                        )
                    } ?: timeline
                    3 -> join()?.let { timeline.restoringStraightCut(it) } ?: timeline
                    4 -> TrackEditing.splitAtPlayhead(timeline, time.seconds)
                    5 -> item()?.let { timeline.removing(TrackLinking.selection(timeline, it), time).timeline } ?: timeline
                    6 -> picture()?.let { TrackEditing.settingSpeed(timeline, it, SpeedSettings(rate = random.next(0.25, 4.0))) } ?: timeline
                    7 -> item()?.let { TrackEditing.trimming(timeline, it, random.next() < 0.5, random.next(-2.0, 2.0), all)?.timeline } ?: timeline
                    8 -> time.seconds.let { TrackEditing.removing(timeline, it, it + random.next(0.0, 3.0)).timeline }
                    9 -> TrackEditing.inserting(timeline, listOf(TimelineClip(sourceID = TrackFixtures.second.id, start = 1.0, end = 1.0 + random.next(0.2, 3.0))), time.seconds)
                    10 -> picture()?.let { id ->
                        val item = timeline.video.first { it.id == id }
                        TrackEditing.settingSourceBounds(timeline, id, item.content.start, item.content.start + (item.content.end - item.content.start) * random.next(0.2, 1.0))
                    } ?: timeline
                    else -> item()?.let { TrackMove.moved(timeline, TrackLinking.selection(timeline, it), ticks(random.next(-5.0, 5.0))) } ?: timeline
                }
                assertAtRest("round $round, step $step, operation $operation", timeline, all)
                assertFalse(timeline.transitions.any { relation -> timeline.generated.none { it.owner == relation.id } })
            }
        }
    }

    // MARK: With Unlink

    /** Tests/TransitionTests.swift:270-277: "Re-editing an opening J-cut stays anchored without gaps or detached audio drift". */
    @Test
    fun anOpeningWhoseSoundLeadsIsSetAgainAndAgainWithoutMovingAnything() {
        var opening = TrackLinking.unlinked(original, TrackLinking.selection(original, original.video[0].id))
        val starts = opening.video.map { it.start }
        val soundStarts = opening.audio.map { it.start }
        val duration = opening.duration
        for (offset in listOf(-0.5, -0.2, 0.0, -0.5)) {
            opening = opening.settingTransition(TransitionSettings(duration = 1.0, audioOffset = offset, audioBlend = 0.0), 0)
            assertEquals("$offset", starts, opening.video.map { it.start })
            assertEquals("$offset", soundStarts, opening.audio.map { it.start })
            assertEquals("$offset", duration, opening.duration)
            assertEquals(1, opening.transitions.size)
            assertAtRest("opening $offset", opening, sources)
        }
    }
}
