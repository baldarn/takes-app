package app.takes.core

import app.takes.core.TrackFixtures.uuid
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The mix plan of a track timeline and the reference mixer. The checks that
 * come from the original's `Tests/TrackMediaTests.swift` name its lines.
 */
class TrackMixTest {
    private val rate = 48_000
    private val voice = uuid(100)
    private val music = uuid(101)

    private fun sound(number: Int, source: String, start: Double, seconds: Double, sourceStart: Double = 0.0) = AudioTimelineItem(
        id = uuid(number), occurrenceID = uuid(number), sourceID = source, start = EditTime.of(start),
        mapping = SourcePlaybackMap.single(EditTime.of(seconds), sourceStart, sourceStart + seconds),
    )

    private fun constant(value: Float, seconds: Double = 10.0) = FloatArray((seconds * rate).toInt()) { value }
    private val sine = FloatArray(10 * rate) { (0.5 * sin(it * Math.PI / 24)).toFloat() }

    private fun mix(plan: TrackMixPlan, sources: Map<String, FloatArray>) = TrackMixer.mix(plan, rate, 1) { sources[it.sourceID] }

    private fun rms(values: FloatArray, at: Double, duration: Double = 0.2): Double {
        val a = minOf(values.size, (at * rate).toInt())
        val b = minOf(values.size, ((at + duration) * rate).toInt())
        return if (a < b) sqrt((a until b).sumOf { values[it].toDouble() * values[it] } / (b - a)) else 0.0
    }

    /** No two pieces of a lane overlap, and each lane is in time order. */
    private fun assertLanesApart(plan: TrackMixPlan) {
        for (lane in plan.lanes) for ((a, b) in lane.pieces.zipWithNext()) assertTrue("lane ${lane.index}", a.end <= b.start)
    }

    @Test fun fourOverlappingSoundsTakeFourLanesAndAddUp() {
        val sounds = listOf(sound(1, voice, 0.0, 2.0), sound(2, voice, 0.5, 2.0), sound(3, voice, 1.0, 2.0), sound(4, voice, 1.5, 2.0), sound(5, voice, 2.1, 1.0))
        val plan = TrackMix.plan(TrackTimeline(audio = sounds))
        assertLanesApart(plan)
        assertEquals(4, plan.lanes.size)
        assertEquals(listOf(0, 1, 2, 3, 0), sounds.map { item -> plan.pieces.single { it.owner == item.id }.lane })
        assertEquals(sounds.take(4).map { it.id }, plan.at(EditTime.of(1.75)).map { it.owner })
        assertEquals(EditTime.of(3.5), plan.duration)
        val stretch = plan.stretches.single { EditTime.of(1.75) in it.interval }
        assertEquals(EditInterval.of(1.5, 2.0), stretch.interval)
        assertEquals(4, stretch.pieces.size)

        // Each sound at its own level, and the mix is the sum of each mixed alone.
        val levels = listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f)
        val sources = sounds.mapIndexed { index, item -> item.copy(sourceID = uuid(200 + index)) }
        val timeline = TrackTimeline(audio = sources)
        val all = mix(TrackMix.plan(timeline), sources.mapIndexed { index, item -> item.sourceID to constant(levels[index]) }.toMap())
        assertEquals(1.0, all[(1.75 * rate).toInt()].toDouble(), 1e-6)
        val alone = sources.mapIndexed { index, item ->
            mix(TrackMix.plan(TrackTimeline(audio = listOf(item))), mapOf(item.sourceID to constant(levels[index])))
        }
        for (frame in all.indices) assertEquals(alone.sumOf { it.getOrElse(frame) { 0f }.toDouble() }, all[frame].toDouble(), 1e-6)
    }

    @Test fun theMovieIsAsLongAsItsLongestItem() {
        val clip = TimelineClip(uuid(1), voice, 0.0, 2.0)
        val timeline = EditState(listOf(clip)).toTimeline().let { it.copy(audio = it.audio + sound(9, music, 1.0, 4.0)) }
        val plan = TrackMix.plan(timeline)
        assertEquals(EditTime.of(5.0), plan.duration)
        val mixed = mix(plan, mapOf(voice to constant(0.0f), music to constant(0.5f)))
        assertEquals(5 * rate, mixed.size)
        assertEquals(0.5, rms(mixed, 4.5, 0.1), 1e-6)
    }

    // Tests/TrackMediaTests.swift:362: "Splitting continuous independent audio introduces no fade or volume dip".
    @Test fun splittingContinuousIndependentAudioIntroducesNoDip() {
        val whole = TrackTimeline(audio = listOf(sound(1, voice, 0.0, 4.0)))
        val split = TrackEditing.split(whole, EditTime.of(2.0), TimelineSelection(audio = setOf(uuid(1)))).timeline
        assertEquals(2, split.audio.size)
        val plan = TrackMix.plan(split)
        assertEquals(listOf(false, true), plan.pieces.map { it.continues })
        assertEquals(listOf(0.0, 0.0), listOf(plan.pieces[0].envelope.fadeOut, plan.pieces[1].envelope.fadeIn))
        val native = mix(TrackMix.plan(whole), mapOf(voice to sine))
        val cut = mix(plan, mapOf(voice to sine))
        assertEquals(1.0, rms(cut, 1.994, 0.012) / rms(native, 1.994, 0.012), 0.01)
        assertArrayEquals(native, cut, 0f)

        // Also where an earlier lane is free when the later piece starts: it stays on the lane it continues.
        val busy = TrackTimeline(audio = listOf(sound(5, music, 0.0, 1.0)) + split.audio)
        val lanes = TrackMix.plan(busy)
        assertLanesApart(lanes)
        assertEquals(listOf(1, 1), lanes.pieces.filter { it.sourceID == voice }.map { it.lane })
        assertTrue(lanes.pieces.last { it.sourceID == voice }.continues)
        val sources = mapOf(voice to sine, music to constant(0f))
        assertArrayEquals(mix(TrackMix.plan(TrackTimeline(audio = listOf(sound(5, music, 0.0, 1.0), sound(1, voice, 0.0, 4.0)))), sources), mix(lanes, sources), 0f)
    }

    @Test fun aCutBetweenTwoSoundsFadesOverEightMilliseconds() {
        val plan = TrackMix.plan(TrackTimeline(audio = listOf(sound(1, voice, 0.0, 2.0), sound(2, music, 2.0, 2.0))))
        assertEquals(1, plan.lanes.size)
        assertFalse(plan.pieces[1].continues)
        val mixed = mix(plan, mapOf(voice to constant(1f), music to constant(1f)))
        assertEquals(0.5, mixed[(2.004 * rate).toInt()].toDouble(), 1e-6)
        assertEquals(0.5, mixed[(1.996 * rate).toInt()].toDouble(), 1e-6)
        assertEquals(0.0, mixed[2 * rate].toDouble(), 1e-6)
        assertEquals(1.0, mixed[(1.5 * rate).toInt()].toDouble(), 1e-6)
    }

    // A fade may be as long as its item (YouRec/TrackTimeline.swift:191-195), and a split leaves the later piece the rest of it (:201-202).
    @Test fun aFadeAsLongAsItsItemAndInheritedAcrossASplit() {
        val item = sound(1, voice, 0.0, 4.0).settingFade(AudioFadeEdge.FadeIn, 10.0)
        assertEquals(4.0, item.fadeIn!!.seconds, 1e-9)
        val whole = TrackTimeline(audio = listOf(item))
        val plan = TrackMix.plan(whole)
        val mixed = mix(plan, mapOf(voice to constant(1f)))
        for (time in listOf(0.5, 1.0, 2.0, 3.0, 3.9)) assertEquals(time / 4, mixed[(time * rate).toInt()].toDouble(), 1e-4)

        val split = TrackEditing.split(whole, EditTime.of(2.0), TimelineSelection(audio = setOf(item.id))).timeline
        assertEquals(2.0, split.audio[1].fadeIn!!.elapsed, 1e-9)
        assertArrayEquals(mixed, mix(TrackMix.plan(split), mapOf(voice to constant(1f))), 1e-6f)

        // Both ends faded over the whole item: the lower of the two counts.
        val both = TrackMix.plan(TrackTimeline(audio = listOf(item.settingFade(AudioFadeEdge.FadeOut, 4.0))))
        val peaked = mix(both, mapOf(voice to constant(1f)))
        assertEquals(0.5, peaked[2 * rate].toDouble(), 1e-4)
        assertEquals(0.25, peaked[3 * rate].toDouble(), 1e-4)
    }

    // Tests/TrackMediaTests.swift:183-187, measured on the mix of overlapping lanes.
    @Test fun headroomCountsTheOtherLanes() {
        fun headroom(selected: Float, remainder: Float): AudioHeadroom {
            val timeline = TrackTimeline(audio = listOf(sound(1, voice, 0.0, 4.0), sound(2, music, 1.0, 2.0)))
            val plan = TrackMix.plan(timeline)
            assertEquals(2, plan.lanes.size)
            val sources = mapOf(voice to constant(selected), music to constant(remainder))
            val selection = AudioGainSelection(setOf(uuid(1)), EditInterval.of(1.0, 3.0))
            val s = mix(plan.gainMix(selection, AudioGainMix.Mode.Selected), sources)
            val u = mix(plan.gainMix(selection, AudioGainMix.Mode.Remainder), sources)
            val frames = checkNotNull(AudioHeadroom.frames(1.0..3.0, plan.duration.seconds))
            val from = frames.first.toInt()
            val count = (frames.last - frames.first + 1).toInt()
            return AudioHeadroom().including(s.copyOfRange(from, from + count), u.copyOfRange(from, from + count))
        }
        val bounds = headroom(0.5f, 0.25f)
        assertTrue("Headroom includes other tracks rather than positive-dB heuristics", !bounds.clips(3.0) && bounds.clips(4.0))
        val cancellation = headroom(-0.5f, 1.2f)
        assertTrue("Opposite-polarity overlap has a lower safe-gain bound", cancellation.clips(-12.0) && !cancellation.clips(0.0))
        val silent = headroom(0f, 1.2f)
        assertTrue("Unselected audio overload remains visible when selected audio is silent", silent.clips(-40.0))
    }

    /** What the engine does per lane, [AudioEnvelope.fill] over the decoded sound, is the reference mixer sample for sample. */
    @Test fun theEnvelopeOfALaneIsTheMixersLevel() {
        val first = sound(1, voice, 0.25, 3.0).settingFade(AudioFadeEdge.FadeIn, 1.0).copy(volumeDB = -6.0)
        val second = sound(2, voice, 3.25, 1.0, sourceStart = 3.0).settingFade(AudioFadeEdge.FadeOut, 0.5)
        val plan = TrackMix.plan(TrackTimeline(audio = listOf(first, second)))
        val lane = plan.lanes.single()
        assertTrue(lane.pieces[1].continues)
        val mixed = mix(plan, mapOf(voice to constant(1f)))
        val gains = DoubleArray(mixed.size)
        lane.envelope().fill(0.0, mixed.size, rate.toDouble(), gains)
        for (frame in mixed.indices) assertEquals(gains[frame], mixed[frame].toDouble(), 1e-6)
    }

    @Test fun aMutedSoundTakesNoLane() {
        val plan = TrackMix.plan(TrackTimeline(audio = listOf(sound(1, voice, 0.0, 2.0).copy(muted = true), sound(2, music, 0.5, 1.0))))
        assertEquals(listOf(uuid(2)), plan.pieces.map { it.owner })
        assertEquals(0, plan.pieces.single().lane)
        assertEquals(EditTime.of(2.0), plan.duration)
    }

    @Test fun aChainCutsTheSoundIntoPiecesThatReadAnotherInput() {
        val chain = listOf(AudioEffect.make(AudioEffectKind.Gain, uuid(50)).copy(amount = 3.0))
        val item = sound(1, voice, 0.0, 4.0)
        val timeline = TrackAudio.enhancing(TrackTimeline(audio = listOf(item)), chain, TimelineSelection(audio = setOf(item.id), range = EditInterval.of(1.0, 2.0)))
        val plan = TrackMix.plan(timeline)
        assertEquals(listOf(EditInterval.of(0.0, 1.0), EditInterval.of(1.0, 2.0), EditInterval.of(2.0, 4.0)), plan.pieces.map { it.interval })
        assertEquals(listOf(false, false, false), plan.pieces.map { it.continues })
        assertEquals(listOf(0, 1, 0), plan.pieces.map { it.chain.size })
        assertEquals(1, plan.pieces.map { it.input }.toSet().size - 1)
    }

    @Test fun theSourceCutsOfARetimedPiece() {
        val item = sound(1, voice, 1.0, 2.0).copy(mapping = SourcePlaybackMap.single(EditTime.of(2.0), 0.0, 4.0))
        val piece = TrackMix.plan(TrackTimeline(audio = listOf(item))).pieces.single()
        assertEquals(2.0, piece.runs.single().rate, 1e-9)
        val mixed = mix(TrackMix.plan(TrackTimeline(audio = listOf(item))), mapOf(voice to FloatArray(10 * rate) { it.toFloat() / rate }))
        // Second 2 of the timeline is second 2 of the source at twice the speed from second 1.
        assertEquals(2.0, mixed[2 * rate].toDouble(), 1e-4)
        assertTrue(abs(mixed[(2.5 * rate).toInt()] - 3.0f) < 1e-3)
    }
}
