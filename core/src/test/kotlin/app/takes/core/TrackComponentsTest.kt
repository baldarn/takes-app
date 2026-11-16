package app.takes.core

import app.takes.core.TrackFixtures.uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Picture and sound as components in the editor's terms: the selection by
 * the editor's ids, Unlink and Link, Delete that closes the time of a linked
 * pair and leaves a gap for a component alone, the sync offset and Restore
 * sync, the rows of the sounds. With the original's gate for its stage 3
 * (docs/EDITOR-TRACKS-DESIGN.md:413-416; Tests/TrackMediaTests.swift:305-308,
 * :368-379): unlink is sample-identical, edits to one component never mutate
 * the other, a linked pair's deletion closes its gap and an unlinked
 * component's leaves one, a detached audio bed spanning a linked-pair
 * deletion stays where it is.
 */
class TrackComponentsTest {
    private val source = SourceMedia(id = uuid(1), filename = "multi.mp4", duration = 12.0)
    private val music = SourceMedia(id = uuid(2), filename = "bed.m4a", duration = 30.0)
    private val clips = (0 until 3).map { TimelineClip(uuid(10 + it), source.id, it * 4.0, (it + 1) * 4.0) }
    private val plain = EditState(clips)
    private val first = EditTargets(clips = setOf(clips[0].id))
    private val second = EditTargets(clips = setOf(clips[1].id))

    private fun unlinkedSecond(): TrackComponents.Linked = TrackComponents.unlinking(plain, second) ?: error("Unlink did not apply")

    /** The sound of the second clip as the editor names it, once unlinked. */
    private fun soundOfSecond(state: EditState): String =
        state.audio.orEmpty().single { TrackSequenceView.soundID(state, it.id) == TrackTimeline.counterpart(clips[1].id) }.id

    private fun picture(state: EditState, id: String) = state.toTimeline().video.single { it.id == id }
    private fun sound(state: EditState, id: String) = state.toTimeline().audio.single { it.id == id }

    @Test
    fun aClipSelectsItsPictureWithItsSoundAndSaysSo() {
        assertEquals(SelectionMaterial.VideoAndAudio, TrackComponents.material(plain, first))
        assertTrue(TrackComponents.isLinked(plain, first))
        val held = TrackComponents.selection(plain, first)
        assertEquals(setOf(clips[0].id), held.video)
        assertEquals(setOf(TrackTimeline.counterpart(clips[0].id)), held.audio)
        assertFalse(TrackComponents.leavesGap(plain, first))
        assertNull(TrackComponents.material(plain, EditTargets()))
    }

    /** OD:97-99: Unlink "changes no samples, position, duration, gain or effects". */
    @Test
    fun unlinkIsSampleIdenticalAndKeepsBothComponentsSelected() {
        val before = plain.toTimeline()
        val done = unlinkedSecond()
        val after = done.state.toTimeline()
        assertTrue("a row of clips holds an unlinked pair", done.state.isSequence)
        assertEquals(before.video, after.video)
        assertEquals(before.audio.map { if (it.id == TrackTimeline.counterpart(clips[1].id)) it.copy(followsVideo = null) else it }, after.audio)
        assertEquals(before.links.size - 1, after.links.size)
        // What was selected stays selected, each component now on its own.
        val sound = soundOfSecond(done.state)
        assertEquals(EditTargets(setOf(clips[1].id), setOf(sound)), done.targets)
        assertEquals(SelectionMaterial.VideoAndAudio, TrackComponents.material(done.state, done.targets))
        assertFalse(TrackComponents.isLinked(done.state, done.targets))
        assertEquals(SelectionMaterial.Video, TrackComponents.material(done.state, second))
        assertEquals(SelectionMaterial.Audio, TrackComponents.material(done.state, EditTargets(audio = setOf(sound))))
        assertTrue(TrackComponents.leavesGap(done.state, second))
        assertTrue(TrackComponents.leavesGap(done.state, EditTargets(audio = setOf(sound))))
        // Unlink applies only to a selection that is one link.
        assertNull(TrackComponents.unlinking(done.state, second))
        assertNull(TrackComponents.unlinking(plain, EditTargets(clips = setOf(clips[0].id, clips[1].id))))
    }

    /** OD:16-25 rule 2: an unlinked picture deleted leaves its gap; its sound and everything after stay. */
    @Test
    fun deletingAnUnlinkedPictureLeavesAGapAndMovesNothing() {
        val unlinked = unlinkedSecond().state
        val sound = soundOfSecond(unlinked)
        val before = sound(unlinked, TrackSequenceView.soundID(unlinked, sound))
        val removed = TrackComponents.delete(unlinked, EditScope.Selected(second))
        assertNotNull(removed)
        val state = removed!!.state
        val timeline = state.toTimeline()
        assertTrue(timeline.video.none { it.id == clips[1].id })
        assertEquals(before, timeline.audio.single { it.id == before.id })
        assertEquals(8.0, picture(state, clips[2].id).start.seconds, 1e-9)
        assertEquals(listOf(TimeRange(4.0, 8.0)), TrackEditState.pictureGaps(state).map { TimeRange(it.start, it.end) })
    }

    /** OD:16-25 rule 2, for the sound: nothing of the picture changes, nothing moves. */
    @Test
    fun deletingAnUnlinkedSoundLeavesSilenceAndMovesNothing() {
        val unlinked = unlinkedSecond().state
        val sound = soundOfSecond(unlinked)
        val removed = TrackComponents.delete(unlinked, EditScope.Selected(EditTargets(audio = setOf(sound))))!!.state
        val timeline = removed.toTimeline()
        assertEquals(unlinked.toTimeline().video, timeline.video)
        assertTrue(timeline.audio.none { it.id == TrackSequenceView.soundID(unlinked, sound) })
        assertEquals(2, timeline.audio.size)
    }

    /** OD:16-25 rule 1, with a detached audio bed spanning the deletion (OD:415-416). */
    @Test
    fun deletingALinkedPairClosesItsGapAndLeavesADetachedBedWhereItIs() {
        val bed = AudioItem(uuid(40), music.id, 0.0, 0.0, 12.0)
        val state = plain.copy(audio = listOf(bed))
        val removed = TrackComponents.delete(state, EditScope.Selected(second))!!.state
        val timeline = removed.toTimeline()
        assertEquals(4.0, removed.clips.single { it.id == clips[2].id }.let { TrackEditState.clipStart(removed, removed.clips.indexOf(it)) }!!, 1e-9)
        val kept = removed.audio.orEmpty().single { it.sourceID == music.id }
        assertEquals(0.0, kept.start, 1e-9)
        assertEquals(12.0, kept.duration, 1e-9)
        // The bed plays on past the pictures, black there, and there only.
        assertTrue(TrackEditState.pictureGaps(removed).all { it.start >= 8.0 - 1e-9 })
        assertEquals(2, timeline.video.size)
    }

    /**
     * Edits to one component never mutate the other: the unlinked picture's
     * speed leaves its sound as it was. The pictures after it still follow
     * it, as a row of clips does (docs/PARITY.md, `unlinked-picture-does-not-ripple`).
     */
    @Test
    fun anEditOfTheUnlinkedPictureLeavesItsSoundAlone() {
        val unlinked = unlinkedSecond().state
        val id = TrackTimeline.counterpart(clips[1].id)
        val before = sound(unlinked, id)
        val faster = TimelineEditing.setSpeed(unlinked, 1, 2.0)!!
        assertEquals(before, sound(faster, id))
        assertEquals(2.0, picture(faster, clips[1].id).mapping.duration.seconds, 1e-6)
        val quieter = TimelineEditing.setAudio(unlinked, 0, ClipAudio(volumeDB = -6.0))!!
        assertEquals(before, sound(quieter, id))
    }

    @Test
    fun linkingTheUnlinkedPairAgainGivesTheRowOfClipsBack() {
        val unlinked = unlinkedSecond()
        assertEquals(TimelineLinkError.TooFew, TrackComponents.linkPlan(unlinked.state, second).errorOrNull)
        val plan = TrackComponents.linkPlan(unlinked.state, unlinked.targets).valueOrNull!!
        assertFalse(plan.needsIntersection)
        val linked = TrackComponents.linking(unlinked.state, unlinked.targets).valueOrNull!!
        assertTrue(linked.state.isSequence)
        val back = linked.state.toTimeline()
        assertEquals(plain.toTimeline().video, back.video)
        assertEquals(plain.toTimeline().audio, back.audio)
        assertEquals(plain.toTimeline().links.map { it.members }.toSet(), back.links.map { it.members }.toSet())
        assertEquals(second, linked.targets)
        assertTrue(TrackComponents.isLinked(linked.state, linked.targets))
    }

    /** A displaced pair: the offset is shown, Link asks about the shared time, Restore sync puts the sound back. */
    @Test
    fun aDisplacedSoundShowsItsOffsetAndIsPutBackInSync() {
        val unlinked = unlinkedSecond()
        val id = TrackTimeline.counterpart(clips[1].id)
        val timeline = unlinked.state.toTimeline()
        val moved = timeline.copy(audio = timeline.audio.map { if (it.id == id) it.copy(start = it.start + EditTime.of(0.5)) else it }).toEdit()
        val sound = EditTargets(audio = setOf(id))
        val offset = TrackComponents.offset(moved, sound)!!
        assertEquals(id, offset.audio)
        assertEquals(0.5, offset.seconds, 1e-9)
        assertNull(TrackComponents.offset(moved, second))
        assertNull(TrackComponents.offset(unlinked.state, EditTargets(audio = setOf(id))))

        // Drawn on a sound track of the row of clips, by the editor's id, with its offset.
        val shown = soundOfSecond(moved)
        val rows = TrackComponents.soundRows(moved)
        assertEquals(AudioItem.TRACKS, rows.count)
        assertEquals(listOf(shown), rows.items.map { it.id })
        assertEquals(4.5, rows.items.single().start, 1e-9)
        assertEquals(0.5, rows.offsets.getValue(shown), 1e-9)
        assertEquals(offset, TrackComponents.offset(moved, EditTargets(audio = setOf(shown))))
        assertEquals(setOf(clips[1].id), TrackComponents.soundless(moved.toTimeline()))

        val both = EditTargets(setOf(clips[1].id), setOf(id))
        assertEquals(TimelineLinkError.ConfirmationRequired, TrackComponents.linking(moved, both).errorOrNull)
        val shared = TrackComponents.linkPlan(moved, both).valueOrNull!!.intersection!!
        assertEquals(4.5, shared.start.seconds, 1e-9)
        assertEquals(8.0, shared.end.seconds, 1e-9)
        val joined = TrackComponents.linking(moved, both, intersectionOnly = true).valueOrNull!!
        assertTrue(TrackComponents.isLinked(joined.state, joined.targets))

        val restored = TrackComponents.restoringSync(moved, id)!!
        assertEquals(4.0, sound(restored, id).start.seconds, 1e-9)
        assertNull(TrackComponents.offset(restored, sound))
        assertNull(TrackComponents.restoringSync(restored, id))
    }

    @Test
    fun aRowOfClipsDrawsItsSoundItemsOnTheirTracks() {
        val bed = AudioItem(uuid(40), music.id, 1.0, 0.0, 5.0, track = 1)
        val rows = TrackComponents.soundRows(plain.copy(audio = listOf(bed)))
        assertEquals(AudioItem.TRACKS, rows.count)
        assertEquals(listOf(bed), rows.items)
        assertTrue(rows.offsets.isEmpty())
        assertEquals(0, TrackComponents.soundRows(plain).count)
        assertFalse(TrackComponents.detached(plain.toTimeline()))
    }
}
