package app.takes.core

import app.takes.core.TrackFixtures.clip
import app.takes.core.TrackFixtures.music
import app.takes.core.TrackFixtures.second
import app.takes.core.TrackFixtures.take
import app.takes.core.TrackFixtures.uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What plays in each stretch of a timeline, and the rows it is drawn on. */
class TrackCompileTest {
    private fun ticks(seconds: Double) = EditTime.of(seconds)
    private val a = clip(1, take, 0.0, 12.5)
    private val b = clip(2, second, 0.0, 8.25)
    private val c = clip(3, take, 20.0, 31.0)
    private fun sound(clip: TimelineClip) = TrackTimeline.counterpart(clip.id)

    /** The original's own way, item by item at every edge (YouRec/TrackTimeline.swift:986-999): what the sweep must equal. */
    private fun reference(timeline: TrackTimeline, marks: List<EditTime>): List<CompiledTimelineInterval> = marks.zipWithNext().map { (from, to) ->
        val pictures = timeline.video.filter { from in it.interval }.sortedWith(VideoTimelineItem.backToFront).map { it.id }
        CompiledTimelineInterval(
            EditInterval(from, to), pictures, timeline.audio.filter { from in it.interval }.map { it.id },
            timeline.placements.firstOrNull { it.participants == pictures.toSet() }?.id,
            timeline.transitions.filter { relation ->
                (relation.outgoing != null || relation.incoming != null) &&
                    from >= relation.origin + ticks(relation.timing.lower) && from < relation.origin + ticks(relation.timing.upper)
            }.map { it.id },
        )
    }

    private fun checkCovered(name: String, timeline: TrackTimeline): List<CompiledTimelineInterval> {
        val compiled = timeline.compile()
        if (timeline.duration == EditTime.ZERO) {
            assertTrue(name, compiled.isEmpty())
            return compiled
        }
        assertEquals(name, EditTime.ZERO, compiled.first().interval.start)
        assertEquals(name, timeline.duration, compiled.last().interval.end)
        compiled.zipWithNext().forEach { (before, after) -> assertEquals(name, before.interval.end, after.interval.start) }
        assertTrue(name, compiled.none { it.interval.isEmpty })
        assertEquals(name, reference(timeline, compiled.map { it.interval.start } + timeline.duration), compiled)
        return compiled
    }

    @Test
    fun aPlainSequenceIsOneStretchPerClip() {
        val compiled = EditState(listOf(a, b, c)).toTimeline().compile()
        assertEquals(
            listOf(
                CompiledTimelineInterval(EditInterval.of(0.0, 12.5), listOf(a.id), listOf(sound(a)), null, emptyList()),
                CompiledTimelineInterval(EditInterval.of(12.5, 20.75), listOf(b.id), listOf(sound(b)), null, emptyList()),
                CompiledTimelineInterval(EditInterval.of(20.75, 31.75), listOf(c.id), listOf(sound(c)), null, emptyList()),
            ),
            compiled,
        )
    }

    @Test
    fun aDissolveIsAStretchWithBothPicturesAndTheTransition() {
        val dissolve = TransitionSettings(id = uuid(10), nextClipID = b.id, duration = 1.0)
        val compiled = checkCovered("dissolve", EditState(listOf(a.copy(transition = dissolve), b)).toTimeline())
        val under = compiled.filter { it.transitionIDs.isNotEmpty() }
        assertEquals(ticks(11.5), under.first().interval.start)
        assertEquals(ticks(12.5), under.last().interval.end)
        assertTrue(under.all { it.video == listOf(a.id, b.id) && it.audio == listOf(sound(a), sound(b)) && it.transitionIDs == listOf(dissolve.id) })
        assertEquals(listOf(a.id), compiled.first().video)
        assertEquals(listOf(b.id), compiled.last().video)
    }

    @Test
    fun everyKindOfEditIsCoveredFromZeroToItsEndWithoutAHole() {
        for ((name, state) in TrackFixtures.all) checkCovered(name, state.toTimeline())
    }

    @Test
    fun emptyTimeIsAStretchWithNothingInIt() {
        val timeline = EditState(listOf(a, b)).toTimeline()
        val apart = timeline.copy(
            video = timeline.video.map { if (it.id == b.id) it.copy(start = ticks(20.0)) else it },
            audio = timeline.audio.map { if (it.followsVideo == b.id) it.copy(start = ticks(18.0), followsVideo = null) else it },
            links = timeline.links.take(1),
        )
        val compiled = checkCovered("apart", apart)
        assertEquals(listOf(emptyList(), emptyList(), listOf(b.id)), compiled.drop(1).take(3).map { it.video })
        assertEquals(listOf(emptyList(), listOf(sound(b)), listOf(sound(b))), compiled.drop(1).take(3).map { it.audio })
        assertEquals(EditInterval.of(12.5, 18.0), compiled[1].interval)
    }

    @Test
    fun overlappingPicturesAreBackToFrontAndNameTheirPlacement() {
        val timeline = EditState(listOf(a, b)).toTimeline()
        val region = VideoPlacementRegion("R", setOf(a.id, b.id), mapOf(a.id to VideoPlacement(), b.id to VideoPlacement()))
        // The second picture is put over the first, in front of it; then behind it.
        val over = timeline.copy(video = timeline.video.map { if (it.id == b.id) it.copy(start = ticks(2.0)) else it }, placements = listOf(region))
        val compiled = checkCovered("over", over)
        assertEquals(listOf(listOf(a.id), listOf(a.id, b.id), listOf(a.id)), compiled.take(3).map { it.video })
        assertEquals(listOf(null, "R", null), compiled.take(3).map { it.placementID })
        val behind = over.copy(video = over.video.map { if (it.id == b.id) it.copy(layer = -1) else it })
        assertTrue(checkCovered("behind", behind).any { it.video == listOf(b.id, a.id) && it.placementID == "R" })
        // Equal layers are ordered by id.
        val level = over.copy(video = over.video.map { it.copy(layer = 0) })
        assertTrue(checkCovered("level", level).any { it.video == listOf(a.id, b.id) })
    }

    @Test
    fun aChainAndACensorOfASoundCutTheTimeline() {
        val timeline = EditState(listOf(a)).toTimeline()
        val item = timeline.audio[0].copy(
            enhancements = listOf(AudioEnhancementRegion("E", EditInterval.of(2.0, 5.0), listOf(AudioEffect.make(AudioEffectKind.Gain)))),
            censorRegions = listOf(AudioCensorRegion("X", EditInterval.of(7.0, 7.5), ticks(7.0), AudioCensorSettings())),
        )
        val compiled = checkCovered("regions", timeline.copy(audio = listOf(item)))
        assertEquals(listOf(0.0, 2.0, 5.0, 7.0, 7.5).map(::ticks), compiled.map { it.interval.start })
    }

    @Test
    fun aCloseThatReachesPastThePictureIsCompiledToItsEnd() {
        val close = TransitionSettings(id = uuid(12), style = TransitionStyle.Still, duration = 2.0)
        val timeline = EditState(listOf(a.copy(transition = close))).toTimeline()
        val compiled = checkCovered("close", timeline)
        assertTrue(timeline.duration > timeline.video[0].end)
        assertTrue(compiled.last().video.isEmpty())
        assertEquals(listOf(close.id), compiled.last().transitionIDs)
    }

    // MARK: Lanes

    private fun rows(lanes: List<TimelineLane>) = lanes.associate { it.id to it.row }

    @Test
    fun aPlainSequenceIsOneRowOfPicturesAndOneOfSound() {
        val timeline = EditState(listOf(a, b, c)).toTimeline()
        val lanes = timeline.lanes()
        assertEquals(mapOf(a.id to 0, b.id to 0, c.id to 0, sound(a) to 1, sound(b) to 1, sound(c) to 1), rows(lanes))
        assertEquals(mapOf(a.id to 1, b.id to 2, c.id to 3, sound(a) to 1, sound(b) to 2, sound(c) to 3), lanes.associate { it.id to it.number })
        assertTrue(lanes.all { it.interval == it.contentInterval && it.trimsLeft && it.trimsRight })
        assertEquals(listOf(false, false, false, true, true, true), lanes.map { it.audio })
        val first = lanes.first { it.id == a.id }
        assertEquals(take.id, first.source)
        assertEquals(timeline.video[0].mapping, first.mapping)
    }

    @Test
    fun aTransitionIsDrawnAsAJoinNotAsTwoLayers() {
        val dissolve = TransitionSettings(id = uuid(10), nextClipID = b.id, duration = 1.0)
        val other = TransitionSettings(id = uuid(11), nextClipID = c.id, duration = 2.0)
        val timeline = EditState(listOf(a.copy(transition = dissolve), b.copy(transition = other), c)).toTimeline()
        val lanes = timeline.lanes().associateBy { it.id }
        assertEquals(setOf(0), listOf(a, b, c).map { lanes.getValue(it.id).row }.toSet())
        assertEquals(setOf(1), listOf(a, b, c).map { lanes.getValue(sound(it)).row }.toSet())
        // Each card ends at the middle of the dissolve; the item itself reaches under it.
        val cut = timeline.transitions[0].let { ticks(it.origin.seconds + it.timing.cut) }
        assertEquals(ticks(12.0), cut)
        assertEquals(cut, lanes.getValue(a.id).interval.end)
        assertEquals(cut, lanes.getValue(b.id).interval.start)
        assertEquals(timeline.video[0].interval, lanes.getValue(a.id).contentInterval)
        assertFalse(lanes.getValue(a.id).trimsRight)
        assertFalse(lanes.getValue(b.id).trimsLeft)
        assertTrue(lanes.getValue(a.id).trimsLeft)
        // The following sounds fold with their pictures.
        assertEquals(cut, lanes.getValue(sound(a)).interval.end)
        assertEquals(cut, lanes.getValue(sound(b)).interval.start)
    }

    @Test
    fun whileItemsMoveNothingIsFoldedAndAChainOfDissolvesTakesTwoRows() {
        val clips = listOf(a, b, c, clip(4, second, 10.0, 18.0), clip(5, take, 40.0, 47.0))
        val joined = clips.mapIndexed { index, item ->
            if (index < clips.lastIndex) item.copy(transition = TransitionSettings(id = uuid(10 + index), nextClipID = clips[index + 1].id, duration = 1.0)) else item
        }
        val timeline = EditState(joined).toTimeline()
        val lanes = timeline.lanes(moving = emptySet())
        assertTrue(lanes.all { it.interval == it.contentInterval })
        // Partners blend, so they alternate between two rows instead of taking one more per cut. Drawn from the front.
        assertEquals(listOf(0, 1, 0, 1, 0), clips.reversed().map { rows(lanes).getValue(it.id) })
        // The sounds overlap too, and take the first free row under the pictures.
        assertEquals(setOf(2, 3), clips.map { rows(lanes).getValue(sound(it)) }.toSet())
    }

    @Test
    fun aPictureInFrontIsAboveTheOneItCovers() {
        val timeline = EditState(listOf(a, b)).toTimeline()
        val over = timeline.copy(
            video = timeline.video.map { if (it.id == b.id) it.copy(start = ticks(2.0)) else it },
            audio = timeline.audio.map { if (it.followsVideo == b.id) it.copy(start = ticks(2.0)) else it },
        )
        // b is on the higher layer: it is in front and on the first row; its sound comes first as well.
        assertEquals(mapOf(b.id to 0, a.id to 1, sound(b) to 2, sound(a) to 3), rows(over.lanes()))
        val behind = over.copy(video = over.video.map { if (it.id == b.id) it.copy(layer = -1) else it })
        assertEquals(mapOf(a.id to 0, b.id to 1, sound(a) to 2, sound(b) to 3), rows(behind.lanes()))
    }

    @Test
    fun movingItemsHaveRowsOfTheirOwnAboveTheRestOfTheirKind() {
        val timeline = EditState(listOf(a, b, c)).toTimeline()
        val lanes = timeline.lanes(moving = setOf(b.id, sound(b)))
        assertEquals(mapOf(b.id to 0, a.id to 1, c.id to 1, sound(b) to 2, sound(a) to 3, sound(c) to 3), rows(lanes))
    }

    @Test
    fun soundWithoutPictureStartsOnTheSecondRow() {
        val item = AudioTimelineItem(id = "M", occurrenceID = "M", sourceID = music.id, start = ticks(1.0), mapping = SourcePlaybackMap.single(ticks(5.0), 0.0, 5.0))
        val lanes = TrackTimeline(audio = listOf(item, item.copy(id = "N", start = ticks(3.0)), item.copy(id = "O", start = ticks(6.0)))).lanes()
        assertEquals(mapOf("M" to 1, "N" to 2, "O" to 1), rows(lanes))
        assertTrue(TrackTimeline().lanes().isEmpty())
        assertNull(TrackTimeline().compile().firstOrNull())
    }

    // MARK: A long project

    @Test
    fun aLongProjectCompilesAndIsLaidOutWithinItsBudget() {
        val timeline = TrackFixtures.long(2_000).toTimeline()
        assertEquals(2_000, timeline.video.size)
        assertTrue(timeline.audio.size >= 2_000)
        checkCovered("long", timeline)
        val lanes = timeline.lanes()
        assertEquals(timeline.video.size + timeline.audio.size, lanes.size)
        assertEquals(setOf(0), lanes.filter { !it.audio }.map { it.row }.toSet())
        // Warm, the best of several runs: what an editor that keeps the result with its timeline pays once per edit.
        fun best(work: () -> Unit): Double {
            repeat(100) { work() }
            return (0 until 30).minOf {
                val begun = System.nanoTime()
                work()
                (System.nanoTime() - begun) / 1e6
            }
        }
        val compiling = best { timeline.compile() }
        val laying = best { timeline.lanes() }
        // docs/TRACKS-PLAN.md, 7.2: under 5 ms for 2,000 pictures and 2,000 sounds, on a machine that is free. The same
        // run on a machine busy with other builds takes several times as long, so the budget is stretched by how much
        // longer than usual a fixed piece of arithmetic takes there.
        var sum = 0.0
        val yardstick = best { sum = 0.0; for (index in 0 until 1_000_000) sum += Math.sqrt(index.toDouble()) }
        val allowed = BUDGET_MILLISECONDS * maxOf(1.0, yardstick / YARDSTICK_MILLISECONDS)
        assertTrue("compile took $compiling ms and lanes $laying ms; $allowed ms allowed (yardstick $yardstick ms, $sum)", compiling + laying < allowed)
    }

    private companion object {
        const val BUDGET_MILLISECONDS = 5.0

        /** What a million square roots, summed, take on a free machine of the kind the budget was written for. */
        const val YARDSTICK_MILLISECONDS = 1.5
    }
}
