package app.takes.core

import app.takes.core.TrackEditingSupport.assertAtRest
import app.takes.core.TrackEditingSupport.move
import app.takes.core.TrackEditingSupport.ticks
import app.takes.core.TrackEditingSupport.unlink
import app.takes.core.TrackFixtures.uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The operations on sound items, and what the edits that cut and retime a
 * sound do to its fades, chains and censors. The checks that come from the
 * original's `Tests/TrackTimelineTests.swift` and `Tests/TrackMediaTests.swift`
 * name their lines and keep its numbers.
 */
class TrackAudioTest {
    // Tests/TrackTimelineTests.swift:13-17.
    private val source = SourceMedia(id = uuid(1), filename = "source.mp4", duration = 4.0)
    private val a = TimelineClip(uuid(2), source.id, 0.0, 2.0)
    private val b = TimelineClip(uuid(3), source.id, 2.0, 4.0)
    private val baseline = EditState(listOf(a, b)).toTimeline()

    // Tests/TrackMediaTests.swift:646-652: an imported sound of four seconds, alone on the timeline.
    private val imported = SourceMedia(id = uuid(10), filename = "voice.m4a", duration = 4.0, audioOnly = true)
    private val alone = TrackAudio.inserting(TrackTimeline(), imported, EditTime.ZERO)
    private val id = alone.id
    private val settings = AudioCensorSettings()
    private val effectID = uuid(11)
    private val scope = TimelineSelection(audio = setOf(id), range = EditInterval.of(1.0, 2.0))
    private val censored = TrackAudio.censoring(alone.timeline, settings, scope, effectID)

    private fun gain(amount: Double, number: Int) = AudioEffect.make(AudioEffectKind.Gain, uuid(number)).copy(amount = amount)
    private fun TrackTimeline.spans() = audio.flatMap { TrackAudio.censorSpans(this, it) }
    private fun TrackTimeline.sounds() = audio.mapTo(LinkedHashSet()) { it.id }

    // MARK: A new sound

    /** Tests/TrackMediaTests.swift:652-658: "Audio imports overlay without shifting existing items". */
    @Test
    fun aNewSoundIsLaidOverWhatIsThereAndMovesNothing() {
        assertEquals(1, alone.timeline.audio.size)
        val item = alone.timeline.audio[0]
        assertEquals(SourcePlaybackMap.single(ticks(4.0), 0.0, 4.0), item.mapping)
        assertNull(item.followsVideo)
        val overlapping = TrackAudio.inserting(alone.timeline, imported, ticks(1.0))
        assertEquals(item, overlapping.timeline.audio[0])
        assertEquals(ticks(5.0), overlapping.timeline.duration)
        assertNotEquals(id, overlapping.id)
        assertEquals(EditTime.ZERO, TrackAudio.inserting(baseline, imported, ticks(-3.0)).let { made -> made.timeline.audio.first { it.id == made.id }.start })
        val scored = TrackAudio.inserting(baseline, imported, ticks(1.0)).timeline
        assertEquals(baseline.video, scored.video)
        assertEquals(baseline.audio, scored.audio.dropLast(1))
        assertEquals(baseline.links, scored.links)
        assertAtRest("scored", scored, listOf(source, imported))
    }

    // MARK: Level and fades

    /** Tests/TrackMediaTests.swift:287-288: a level on a range is a level on the pieces the range cuts. */
    @Test
    fun aLevelIsSetOnTheSelectedSoundsAndKeptInRange() {
        val first = TrackEditing.selection(baseline, a.id)
        val loud = TrackAudio.settingVolume(baseline, 90.0, false, first)
        assertEquals(listOf(12.0, 0.0), loud.audio.map { it.volumeDB })
        assertEquals(baseline.video, loud.video)
        val quiet = TrackAudio.settingVolume(baseline, -200.0, true, TrackEditing.entire(baseline))
        assertEquals(listOf(-60.0, -60.0), quiet.audio.map { it.volumeDB })
        assertTrue(quiet.audio.all { it.muted && it.gain == 0.0 })
        val part = baseline.scoped(TrackEditing.entire(baseline, EditInterval.of(1.0, 3.0)))
        val boosted = TrackAudio.settingVolume(part.timeline, 6.0, false, part.selection)
        assertEquals(listOf(0.0, 6.0, 6.0, 0.0), boosted.audio.sortedBy { it.start }.map { it.volumeDB })
        assertAtRest("boosted", boosted, listOf(source))
    }

    /** Tests/TrackMediaTests.swift:545-555: "Whole-project fade targets are the first and last audio clips", "Intermediate audio keeps its level". */
    @Test
    fun aFadeBelongsToTheFirstSoundToBeginOrTheLastToEnd() {
        val whole = EditState(listOf(TimelineClip(uuid(20), source.id, 0.0, 4.0))).toTimeline()
        val base = whole.split(ticks(1.5), TrackEditing.entire(whole)).timeline.let { it.split(ticks(2.5), TrackEditing.entire(it)).timeline }
        val all = base.sounds()
        val intro = TrackAudio.fadeTarget(base, AudioFadeEdge.FadeIn, all)!!
        val outro = TrackAudio.fadeTarget(base, AudioFadeEdge.FadeOut, all)!!
        assertEquals(base.audio[0].id, intro.id)
        assertEquals(base.audio[2].id, outro.id)
        val edit = TrackAudio.settingFade(TrackAudio.settingFade(base, intro.id, AudioFadeEdge.FadeIn, 1.0), outro.id, AudioFadeEdge.FadeOut, 1.0)
        assertEquals(base.audio[1], edit.audio[1])
        assertEquals(AudioEdgeFade(1.0), edit.audio[0].fadeIn)
        assertEquals(1.0, edit.audio[2].fadeSeconds(AudioFadeEdge.FadeOut), 0.0)
        assertEquals(edit, TrackTimeline.decoded(edit.encoded()))
        // :565-569: "Ties target a single lowest-numbered audio clip", "The latest end wins even if a shorter clip starts later".
        var tied = edit.copy(audio = listOf(edit.audio[0], edit.audio[1].copy(start = EditTime.ZERO), edit.audio[2].copy(start = EditTime.ZERO)))
        assertEquals(tied.audio[0].id, TrackAudio.fadeTarget(tied, AudioFadeEdge.FadeIn, all)?.id)
        assertEquals(tied.audio[0].id, TrackAudio.fadeTarget(tied, AudioFadeEdge.FadeOut, all)?.id)
        tied = tied.copy(audio = listOf(tied.audio[0], tied.audio[1].copy(start = ticks(0.4)), tied.audio[2].copy(start = ticks(0.1))))
        assertEquals(tied.audio[2].id, TrackAudio.fadeTarget(tied, AudioFadeEdge.FadeOut, all)?.id)
        assertNull(TrackAudio.fadeTarget(base, AudioFadeEdge.FadeIn, emptySet()))
        // :574-577: "Marked ranges target their actual audible edges".
        val range = base.scoped(TimelineSelection(audio = all, range = EditInterval.of(0.4, 3.3)))
        assertEquals(ticks(0.4), TrackAudio.fadeTarget(range.timeline, AudioFadeEdge.FadeIn, range.selection.audio)?.start)
        assertEquals(ticks(3.3), TrackAudio.fadeTarget(range.timeline, AudioFadeEdge.FadeOut, range.selection.audio)?.end)
    }

    /** YouRec/TrackTimeline.swift:191-195. */
    @Test
    fun aFadeIsNoLongerThanItsSoundAndZeroRemovesIt() {
        val sound = baseline.audio[0]
        assertEquals(AudioEdgeFade(2.0), sound.settingFade(AudioFadeEdge.FadeIn, 30.0).fadeIn)
        assertEquals(AudioEdgeFade(0.5), sound.settingFade(AudioFadeEdge.FadeOut, 0.5).fadeOut)
        assertNull(sound.settingFade(AudioFadeEdge.FadeIn, 1.0).settingFade(AudioFadeEdge.FadeIn, 0.0).fadeIn)
        assertNull(sound.settingFade(AudioFadeEdge.FadeIn, -1.0).fadeIn)
        assertSame(baseline, TrackAudio.settingFade(baseline, uuid(999), AudioFadeEdge.FadeIn, 1.0))
        // Both fades may take the whole item: the level is the lower of the two.
        val both = sound.settingFade(AudioFadeEdge.FadeIn, 2.0).settingFade(AudioFadeEdge.FadeOut, 2.0)
        assertEquals(0.25, TrackAudio.level(both, ticks(0.5)), 0.000001)
        assertEquals(0.5, TrackAudio.level(both, ticks(1.0)), 0.000001)
        assertEquals(0.25, TrackAudio.level(both, ticks(1.5)), 0.000001)
        assertEquals(0.0, TrackAudio.level(both.copy(muted = true), ticks(1.0)), 0.0)
        assertEquals(AudioLevels.linear(-6.0) * 0.5, TrackAudio.level(both.copy(volumeDB = -6.0), ticks(1.0)), 0.000001)
    }

    /** Tests/TrackMediaTests.swift:570-573: "Splitting midway through a fade preserves its level and slope". */
    @Test
    fun aCutInsideAFadeKeepsItsSlopeAndItsLevelAtTheCut() {
        val faded = baseline.copy(audio = listOf(baseline.audio[0].settingFade(AudioFadeEdge.FadeIn, 1.0).settingFade(AudioFadeEdge.FadeOut, 1.0), baseline.audio[1]))
        val split = faded.split(ticks(0.4), TrackEditing.entire(faded)).timeline
        val before = split.audio.first { it.start == EditTime.ZERO }
        val after = split.audio.first { it.start == ticks(0.4) }
        assertEquals(0.4, after.fadeIn!!.gain(0.0), 0.00001)
        assertEquals(AudioEdgeFade(1.0, 0.4), after.fadeIn)
        assertEquals(0.6, after.fadeSeconds(AudioFadeEdge.FadeIn), 0.00001)
        assertEquals(AudioEdgeFade(1.0), before.fadeIn)
        // The fade out is all in the later piece; the earlier one has none of it.
        assertNull(before.fadeOut)
        assertEquals(AudioEdgeFade(1.0), after.fadeOut)
        // The level is the same before and after the cut, everywhere.
        for (time in listOf(0.1, 0.39, 0.4, 0.7, 1.0, 1.5, 1.9)) {
            val piece = if (time < 0.4) before else after
            assertEquals("$time", TrackAudio.level(faded.audio[0], ticks(time)), TrackAudio.level(piece, ticks(time) - piece.start), 0.000001)
        }
        // A cut inside the fade out leaves the earlier piece ending partway down.
        val late = faded.split(ticks(1.5), TrackEditing.entire(faded)).timeline
        assertEquals(AudioEdgeFade(1.0, 0.5), late.audio[0].fadeOut)
        assertNull(late.audio[1].fadeIn)
        // A range that takes the whole fade takes it away.
        val trimmed = TrackEditing.trimming(faded, a.id, left = true, delta = 1.0, sources = listOf(source))!!.timeline
        assertNull(trimmed.audio[0].fadeIn)
        // Revealing the footage again gives a cut-into fade back what the revealed time holds.
        val revealed = late.extending(TrackEditing.selection(late, late.video[0].id), left = false, by = ticks(0.25), sources = listOf(source))
        assertEquals(AudioEdgeFade(1.0, 0.25), revealed.audio[0].fadeOut)
        assertAtRest("revealed", revealed, listOf(source))
    }

    // MARK: Chains

    /** Tests/TrackTimelineTests.swift:89-98: "Partial enhancement replaces intersection without double processing", "Nonintersecting enhancement does not broaden to whole clip". */
    @Test
    fun aChainOnARangeReplacesWhatWasUnderIt() {
        val audioID = baseline.audio[0].id
        var enhanced = TrackAudio.enhancing(baseline, listOf(gain(-6.0, 30)), TimelineSelection(audio = setOf(audioID), range = EditInterval.of(0.5, 1.5)), uuid(31))
        enhanced = TrackAudio.enhancing(enhanced, listOf(gain(-3.0, 32)), TimelineSelection(audio = setOf(audioID), range = EditInterval.of(1.0, 2.0)), uuid(33))
        assertEquals(2, enhanced.audio[0].enhancements.size)
        assertEquals(EditInterval.of(0.5, 1.0), enhanced.audio[0].enhancements[0].interval)
        assertEquals(listOf(uuid(31), uuid(33)), enhanced.audio[0].enhancements.map { it.id })
        assertEquals(enhanced, TrackAudio.enhancing(enhanced, listOf(gain(-6.0, 30)), TimelineSelection(audio = setOf(audioID), range = EditInterval.of(3.0, 4.0))))
        enhanced.validate(listOf(source))
        assertEquals(listOf(-6.0), TrackAudio.chain(enhanced.audio[0], ticks(0.75)).map { it.amount })
        assertEquals(listOf(-3.0), TrackAudio.chain(enhanced.audio[0], ticks(1.0)).map { it.amount })
        assertTrue(TrackAudio.chain(enhanced.audio[0], ticks(0.25)).isEmpty())
        // A chain in the middle of another leaves it on both sides, under the same id; an empty chain only removes.
        val middle = TrackAudio.enhancing(enhanced, emptyList(), TimelineSelection(audio = setOf(audioID), range = EditInterval.of(1.25, 1.5)))
        assertEquals(listOf(EditInterval.of(0.5, 1.0), EditInterval.of(1.0, 1.25), EditInterval.of(1.5, 2.0)), middle.audio[0].enhancements.map { it.interval })
        assertEquals(listOf(uuid(31), uuid(33), uuid(33)), middle.audio[0].enhancements.map { it.id })
        // On the whole of each selected sound when there is no range.
        val whole = TrackAudio.enhancing(middle, listOf(gain(1.0, 34)), TrackEditing.entire(middle), uuid(35))
        assertEquals(listOf(listOf(EditInterval.of(0.0, 2.0)), listOf(EditInterval.of(0.0, 2.0))), whole.audio.map { sound -> sound.enhancements.map { it.interval } })
        assertEquals(baseline.audio, TrackAudio.enhancing(whole, emptyList(), TrackEditing.entire(whole)).audio)
    }

    @Test
    fun aChainGoesWithTheSoundThroughACutAndADeletion() {
        val audioID = baseline.audio[0].id
        val enhanced = TrackAudio.enhancing(baseline, listOf(gain(-6.0, 30)), TimelineSelection(audio = setOf(audioID), range = EditInterval.of(0.5, 1.5)), uuid(31))
        val (split, children) = enhanced.split(ticks(1.0), TrackEditing.selection(enhanced, a.id))
        assertEquals(listOf(EditInterval.of(0.5, 1.0)), split.audio.first { it.id == audioID }.enhancements.map { it.interval })
        assertEquals(listOf(EditInterval.of(0.0, 0.5)), split.audio.first { it.id == children[audioID] }.enhancements.map { it.interval })
        // A range taken out of the middle leaves the chain on what is left of the sound it was on.
        val cut = TrackEditing.removing(enhanced, 0.75, 1.25).timeline
        assertEquals(listOf(listOf(EditInterval.of(0.5, 0.75)), listOf(EditInterval.of(0.0, 0.25)), emptyList()), cut.audio.map { sound -> sound.enhancements.map { it.interval } })
        assertAtRest("cut", cut, listOf(source))
    }

    /** Tests/TrackTimelineTests.swift:907-913, without the muting effect: "Left Extend retains audio enhancement source coordinates". */
    @Test
    fun revealingOnTheLeftKeepsAChainOnItsSound() {
        val long = SourceMedia(id = uuid(40), filename = "source.mp4", duration = 20.0)
        var timeline = EditState(listOf(TimelineClip(uuid(41), long.id, 1.0, 3.0))).toTimeline()
        timeline = move(timeline, TrackEditing.entire(timeline), ticks(2.0))
        timeline = TrackAudio.enhancing(timeline, listOf(AudioEffect.make(AudioEffectKind.HighPass, uuid(42))), TrackEditing.entire(timeline))
        timeline = timeline.extending(TrackEditing.entire(timeline), left = true, by = ticks(1.0), sources = listOf(long))
        timeline.validate(listOf(long))
        assertEquals(EditInterval.of(1.0, 3.0), timeline.audio[0].enhancements[0].interval)
    }

    // MARK: Censors

    /** Tests/TrackMediaTests.swift:660-678. */
    @Test
    fun aCensorIsCutWithItsSoundRemovedFromOnePieceAndMovesWithIt() {
        assertEquals(listOf(AudioCensorRegion(effectID, EditInterval.of(1.0, 2.0), ticks(1.0), settings, EditInterval.of(1.0, 2.0))), censored.audio[0].censors)
        assertEquals(censored.audio[0].censors, TrackTimeline.decoded(censored.encoded()).audio[0].censors)
        // "Splitting preserves censor on both halves".
        var split = censored.split(ticks(1.5), TimelineSelection(audio = setOf(id))).timeline
        assertEquals(2, split.audio.size)
        assertTrue(split.audio.all { it.censors.size == 1 })
        // "Removing a split censor affects only its own audio section".
        val right = split.audio[1].id
        split = TrackAudio.censoring(split, null, TimelineSelection(audio = setOf(right)), removingOnly = effectID)
        assertEquals(1, split.audio[0].censors.size)
        assertTrue(split.audio[1].censors.isEmpty())
        assertNull(split.audio[1].censorRegions)
        // "Censor follows moved audio".
        split = move(split, TimelineSelection(audio = setOf(id)), ticks(2.0))
        assertEquals(3.0, TrackAudio.censorSpans(split, split.audio[0])[0].start, 0.0)
        split.validate(listOf(imported))
    }

    /** Tests/TrackMediaTests.swift:756-758: "A pure split inside a censor does not restart its oscillator or envelope". */
    @Test
    fun aCutInsideACensorLeavesOneContinuousTone() {
        val pulse = settings.copy(envelope = CensorEnvelope.Pulse)
        val whole = TrackAudio.censoring(alone.timeline, pulse, scope, effectID)
        val split = whole.split(ticks(1.5), TimelineSelection(audio = setOf(id))).timeline
        val before = whole.spans()
        val after = split.spans()
        assertEquals(listOf(1.0 to 2.0), before.map { it.start to it.end })
        assertEquals(listOf(1.0 to 1.5, 1.5 to 2.0), after.map { it.start to it.end })
        assertTrue(after.all { it.phaseOrigin == 1.0 && it.envelopeStart == 1.0 && it.envelopeEnd == 2.0 && it.emitsTone })
        for (frame in 0 until 96000 step 7) {
            val time = 0.5 + frame / 48000.0
            assertEquals(AudioCensor.tone(time, before, 48000.0), AudioCensor.tone(time, after, 48000.0), 0.0)
            assertEquals(AudioCensor.originalGain(time, before), AudioCensor.originalGain(time, after), 0.0)
        }
        // A trim that cuts into the censor leaves the rest of the same tone.
        val trimmed = TrackEditing.trimming(whole, id, left = true, delta = 1.25, sources = listOf(imported))!!.timeline
        assertEquals(ticks(1.25), trimmed.audio[0].start)
        val rest = trimmed.spans()
        assertEquals(listOf(1.25 to 2.0), rest.map { it.start to it.end })
        assertEquals(AudioCensor.tone(1.6, before, 48000.0), AudioCensor.tone(1.6, rest, 48000.0), 0.0)
        trimmed.validate(listOf(imported))
    }

    /** Tests/TrackMediaTests.swift:683-688, :715-718: one censor over several sounds is one tone, carried by the loudest. */
    @Test
    fun oneCensorOverSeveralSoundsPlaysOneTone() {
        var layered = TrackAudio.inserting(alone.timeline, imported, EditTime.ZERO).timeline
        layered = TrackAudio.censoring(layered, settings, TimelineSelection(audio = layered.sounds(), range = EditInterval.of(1.0, 2.0)), effectID)
        // "One censor across overlapping selected tracks emits one beep".
        assertEquals(1, layered.spans().count { it.emitsTone })
        assertTrue(TrackAudio.censorSpans(layered, layered.audio[0])[0].emitsTone)
        // "The loudest selected track carries a shared beep, independent of UUID ordering".
        val quieter = layered.copy(audio = listOf(layered.audio[0].copy(volumeDB = -18.0), layered.audio[1]))
        assertFalse(TrackAudio.censorSpans(quieter, quieter.audio[0])[0].emitsTone)
        assertTrue(TrackAudio.censorSpans(quieter, quieter.audio[1])[0].emitsTone)
        // "Editing one overlapping track's censor must remain audible independently".
        val retuned = TrackAudio.censoring(quieter, settings.copy(frequency = 2000.0), TimelineSelection(audio = setOf(quieter.audio[1].id), range = EditInterval.of(1.0, 2.0)), effectID)
        assertEquals(2, retuned.spans().count { it.emitsTone })
        // The same censor set again with another tone replaces it on every sound.
        val again = TrackAudio.censoring(layered, settings.copy(frequency = 440.0), TimelineSelection(audio = layered.sounds(), range = EditInterval.of(1.0, 2.0)), effectID)
        assertEquals(1, again.spans().count { it.emitsTone })
        assertTrue(again.audio.all { sound -> sound.censors.map { it.settings.frequency } == listOf(440.0) })
        assertTrue(TrackAudio.censoring(again, null, TimelineSelection(audio = again.sounds())).audio.all { it.censors.isEmpty() })
    }

    /** Tests/TrackMediaTests.swift:708-714: "Clips beginning inside one censor share its phase and envelope clock". */
    @Test
    fun soundsThatBeginInsideOneCensorShareItsClock() {
        var staggered = TrackAudio.inserting(alone.timeline, imported, ticks(1.0)).timeline
        staggered = TrackAudio.censoring(staggered, settings, TimelineSelection(audio = staggered.sounds(), range = EditInterval.of(0.5, 3.5)))
        val spans = staggered.spans()
        assertTrue(spans.isNotEmpty())
        for (span in spans) {
            assertTrue(abs(span.phaseOrigin - 0.5) < 0.00001 && abs(span.envelopeStart - 0.5) < 0.00001 && abs(span.envelopeEnd - 3.5) < 0.00001)
        }
        staggered.validate(listOf(imported))
        // A range that misses a sound leaves it alone.
        assertEquals(staggered, TrackAudio.censoring(staggered, settings, TimelineSelection(audio = staggered.sounds(), range = EditInterval.of(6.0, 7.0))))
    }

    /** Tests/TrackMediaTests.swift:719-724: "Censor follows speech through a linked speed change". */
    @Test
    fun aCensorFollowsItsSoundThroughAChangeOfSpeed() {
        val video = SourceMedia(id = uuid(50), filename = "take.mp4", duration = 4.0)
        var linked = EditState(listOf(TimelineClip(uuid(51), video.id, 0.0, 4.0))).toTimeline()
        linked = TrackAudio.censoring(linked, settings, TimelineSelection(audio = setOf(linked.audio[0].id), range = EditInterval.of(1.0, 2.0)), effectID)
        val faster = linked.replacingVideoContents(linked.projectedClips.map { it.copy(speed = SpeedSettings(rate = 2.0)) })
        assertEquals(EditInterval.of(0.5, 1.0), faster.audio[0].censors[0].interval)
        // Its length changed, so the tone starts afresh there (YouRec/TrackTimeline.swift:1322).
        assertEquals(ticks(0.5), faster.audio[0].censors[0].phaseOrigin)
        assertNull(faster.audio[0].censors[0].envelopeInterval)
        faster.validate(listOf(video))
    }

    /** Tests/TrackMediaTests.swift:725-729 ("Horizontal linking keeps both split censor regions intact"), and what a retime does to two pieces that touch. */
    @Test
    fun piecesOfOneCensorThatTouchAfterARetimeAreOneAgain() {
        val video = SourceMedia(id = uuid(50), filename = "take.mp4", duration = 4.0)
        var linked = EditState(listOf(TimelineClip(uuid(51), video.id, 0.0, 4.0))).toTimeline()
        val sound = linked.audio[0]
        // Two pieces of one censor that meet at 1.5, as a cut and a join of the sound would leave them.
        val pieces = listOf(
            AudioCensorRegion(effectID, EditInterval.of(1.0, 1.5), ticks(1.0), settings, EditInterval.of(1.0, 2.0)),
            AudioCensorRegion(effectID, EditInterval.of(1.5, 2.0), ticks(1.0), settings, EditInterval.of(1.0, 2.0)),
        )
        linked = linked.copy(audio = listOf(sound.withCensors(pieces)))
        linked.validate(listOf(video))
        // Nothing that leaves the timing alone touches them.
        assertEquals(pieces, linked.synchronizingFollowers().audio[0].censors)
        assertEquals(pieces, TrackAudio.settingVolume(linked, -3.0, false, TrackEditing.entire(linked)).audio[0].censors)
        val slower = linked.replacingVideoContents(linked.projectedClips.map { it.copy(speed = SpeedSettings(rate = 0.5)) })
        assertEquals(listOf(AudioCensorRegion(effectID, EditInterval.of(2.0, 4.0), ticks(2.0), settings, null)), slower.audio[0].censors)
        // An unlinked sound does not follow the picture: its censor stays where it is.
        val separate = unlink(linked, TrackEditing.entire(linked))
        assertEquals(pieces, separate.replacingVideoContents(separate.projectedClips.map { it.copy(speed = SpeedSettings(rate = 0.5)) }).audio[0].censors)
        slower.validate(listOf(video))
    }
}
