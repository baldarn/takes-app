package app.takes.core

import app.takes.core.AudioUnlinking.Refusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioUnlinkingTest {
    private val a = TimelineClip(sourceID = "A", start = 0.0, end = 4.0)
    private val b = TimelineClip(sourceID = "B", start = 1.0, end = 6.0)
    private val c = TimelineClip(sourceID = "C", start = 0.0, end = 3.0)
    private val state = EditState(listOf(a, b, c), captureBase = 3)

    private fun item(state: EditState, id: String) = state.audio!!.first { it.id == id }

    /** Everything heard of one clip's source at timeline second [time]: the clip's own sound plus its detached item. */
    private fun heard(state: EditState, id: String, time: Double): Double {
        val index = state.clips.indexOfFirst { it.id == id }
        val begins = TimelineEditing.clipStart(state.clips, index)
        val clip = state.clips[index]
        val own = if (time >= begins && time <= begins + clip.duration) TimelineEditing.soundLevel(state.clips, index, time - begins) else 0.0
        val detached = state.audio.orEmpty().filter { it.id == id && time >= it.start && time <= it.end }
            .sumOf { TimelineEditing.soundLevel(it, time - it.start) }
        return own + detached
    }

    private fun assertSameSound(before: EditState, after: EditState, id: String) {
        val index = before.clips.indexOfFirst { it.id == id }
        val begins = TimelineEditing.clipStart(before.clips, index)
        val length = before.clips[index].duration
        for (step in 0..200) {
            val time = begins + length * step / 200
            assertEquals("at $time", heard(before, id, time), heard(after, id, time), 1e-12)
        }
    }

    @Test fun unlinkPutsTheSoundUnderThePictureAndChangesNothingElse() {
        val unlinked = AudioUnlinking.unlink(state, 1)!!
        val sound = unlinked.audio!!.single()
        assertEquals(AudioItem(id = b.id, sourceID = "B", start = 4.0, sourceStart = 1.0, sourceEnd = 6.0, track = 0), sound)
        // The picture keeps its timing; only the clip's sound is silenced.
        assertEquals(listOf(a, b.copy(audio = ClipAudio(muted = true)), c), unlinked.clips)
        assertEquals(state.duration, unlinked.duration, 0.0)
        assertEquals(3, unlinked.captureBase)
        assertEquals(sound, AudioUnlinking.detachedSound(unlinked, 1))
        assertTrue(AudioUnlinking.isDetached(unlinked, 1))
        assertEquals(1, AudioUnlinking.clipIndex(unlinked, sound.id))
        assertNull(AudioUnlinking.detachedSound(unlinked, 0))
        assertNull(AudioUnlinking.detachedSound(state, 1))
        assertNull(AudioUnlinking.clipIndex(unlinked, "missing"))
        assertEquals(0.0, AudioUnlinking.syncOffset(unlinked, 1)!!, 0.0)
        assertTrue(AudioUnlinking.valid(unlinked))
        assertSameSound(state, unlinked, b.id)
    }

    @Test fun unlinkThenRelinkRestoresTheState() {
        val sounds = listOf(
            null, ClipAudio(volumeDB = -6.0), ClipAudio(muted = true), ClipAudio(volumeDB = 3.0, fadeIn = 1.0, fadeOut = 9.0),
        )
        for (sound in sounds) {
            val before = state.copy(clips = listOf(a, b.copy(audio = sound), c))
            val unlinked = AudioUnlinking.unlink(before, 1)!!
            assertEquals(sound, unlinked.audio!!.single().audio)
            assertTrue(unlinked.clips[1].audio!!.muted)
            assertSameSound(before, unlinked, b.id)
            assertTrue(AudioUnlinking.matches(unlinked, 1))
            assertEquals(before, AudioUnlinking.relink(unlinked, 1))
        }
        // Other sound on the timeline is left alone, in both directions.
        val music = TimelineEditing.addAudio(state, "M", 30.0, 0.0)!!
        val unlinked = AudioUnlinking.unlink(music, 2)!!
        assertEquals(listOf(0, 1), unlinked.audio!!.map { it.track })
        assertEquals(music, AudioUnlinking.relink(unlinked, 2))
    }

    @Test fun soundSettingsChangedWhileDetachedComeBackWithTheSound() {
        val unlinked = AudioUnlinking.unlink(state.copy(clips = listOf(a, b.copy(audio = ClipAudio(volumeDB = -6.0)), c)), 1)!!
        // The clip's settings are edited on the item, where the sound is.
        val louder = AudioUnlinking.setSound(unlinked, 1, ClipAudio(volumeDB = 2.0, fadeOut = 1.0))!!
        assertEquals(ClipAudio(volumeDB = 2.0, fadeOut = 1.0), item(louder, b.id).audio)
        assertEquals(unlinked.clips, louder.clips)
        assertEquals(ClipAudio(volumeDB = 2.0, fadeOut = 1.0), AudioUnlinking.relink(louder, 1)!!.clips[1].audio)
        assertNull(AudioUnlinking.relink(louder, 1)!!.audio)
        // Muting the detached sound mutes the item; relinking gives a muted clip.
        val muted = AudioUnlinking.setSound(unlinked, 1, ClipAudio(muted = true))!!
        assertEquals(0.0, heard(muted, b.id, 5.0), 0.0)
        assertEquals(ClipAudio(muted = true), AudioUnlinking.relink(muted, 1)!!.clips[1].audio)
        // Sound on its own track is not cleaned today, so noise reduction cannot be turned on there.
        assertNull(AudioUnlinking.setSound(unlinked, 1, ClipAudio(denoise = 0.5)))
        assertEquals(0.5, item(AudioUnlinking.setSound(unlinked, 1, ClipAudio(denoise = 0.5), itemsReduceNoise = true)!!, b.id).audio!!.denoise, 0.0)
        // A linked clip is set as before.
        assertEquals(TimelineEditing.setAudio(state, 0, ClipAudio(volumeDB = -3.0)), AudioUnlinking.setSound(state, 0, ClipAudio(volumeDB = -3.0)))
    }

    @Test fun refusals() {
        assertEquals(Refusal.NoClip, AudioUnlinking.refusal(state, 3))
        assertEquals(Refusal.NoClip, AudioUnlinking.refusal(state, -1))
        assertNull(AudioUnlinking.unlink(state, 3))
        assertNull(AudioUnlinking.unlink(EditState(), 0))
        assertNull(AudioUnlinking.refusal(state, 1))

        val unlinked = AudioUnlinking.unlink(state, 1)!!
        assertEquals(Refusal.AlreadyDetached, AudioUnlinking.refusal(unlinked, 1))
        assertNull(AudioUnlinking.unlink(unlinked, 1))

        val fast = TimelineEditing.setSpeed(state, 1, 2.0)!!
        assertEquals(Refusal.Speed, AudioUnlinking.refusal(fast, 1))
        assertNull(AudioUnlinking.unlink(fast, 1))
        assertNull(AudioUnlinking.refusal(fast, 0)) // only the clip's own speed matters

        // Both tracks busy somewhere under the clip (4–9): refused. One free track is enough.
        val one = TimelineEditing.addAudio(state, "M", 2.0, 8.0)!!
        assertEquals(1, AudioUnlinking.unlink(one, 1)!!.audio!!.first { it.id == b.id }.track)
        val both = TimelineEditing.addAudio(one, "V", 1.0, 8.5)!!
        assertEquals(listOf(0, 1), both.audio!!.map { it.track })
        assertEquals(Refusal.TracksBusy, AudioUnlinking.refusal(both, 1))
        assertNull(AudioUnlinking.unlink(both, 1))
        assertNull(AudioUnlinking.refusal(both, 0)) // 0–4 is free
        // Sound that only touches the clip's ends is not in the way.
        val touching = TimelineEditing.addAudio(TimelineEditing.addAudio(state, "M", 4.0, 0.0)!!, "V", 3.0, 9.0)!!
        assertEquals(0, AudioUnlinking.unlink(touching, 1)!!.audio!!.first { it.id == b.id }.track)

        // Relink needs detached sound.
        assertNull(AudioUnlinking.relink(state, 1))
        assertNull(AudioUnlinking.relink(unlinked, 0))
        assertNull(AudioUnlinking.relink(unlinked, 7))
    }

    @Test fun noiseReductionIsNeverLostSilently() {
        val clean = state.copy(clips = listOf(a, b.copy(audio = ClipAudio(volumeDB = -2.0, denoise = 0.5)), c))
        // Audio items are not cleaned by the player today, so the clip stays linked.
        assertEquals(Refusal.NoiseReduction, AudioUnlinking.refusal(clean, 1))
        assertNull(AudioUnlinking.unlink(clean, 1))
        // Once they are, the setting moves with the sound and comes back with it.
        val unlinked = AudioUnlinking.unlink(clean, 1, itemsReduceNoise = true)!!
        assertEquals(ClipAudio(volumeDB = -2.0, denoise = 0.5), unlinked.audio!!.single().audio)
        assertEquals(ClipAudio(volumeDB = -2.0, muted = true), unlinked.clips[1].audio) // nothing left to clean on the silent clip
        assertEquals(clean, AudioUnlinking.relink(unlinked, 1))
    }

    @Test fun transitionRampsBecomeTheSoundsOwnFades() {
        // A fade through black into B and a dissolve out of it.
        var joined = TimelineEditing.setTransition(state, 0, 1.0)!!
        joined = TimelineEditing.setTransition(joined, 1, 0.8, TransitionStyle.Dissolve)!!
        val unlinked = AudioUnlinking.unlink(joined, 1)!!
        val sound = unlinked.audio!!.single()
        assertEquals(ClipAudio(fadeIn = 0.5, fadeOut = 0.8), sound.audio)
        assertEquals(4.0, sound.start, 0.0)
        assertEquals(9.0, sound.end, 0.0)
        assertSameSound(joined, unlinked, b.id)
        assertEquals(0.5, heard(unlinked, b.id, 4.25), 1e-12)
        assertEquals(0.5, heard(unlinked, b.id, 8.6), 1e-12)
        // The picture keeps its transitions, and relinking hands the ramps back to them.
        assertEquals(joined.clips.map { it.transition }, unlinked.clips.map { it.transition })
        assertEquals(joined.duration, unlinked.duration, 0.0)
        assertEquals(joined, AudioUnlinking.relink(unlinked, 1))

        // The incoming side of a dissolve: C begins under the end of B, and so does its sound.
        val incoming = AudioUnlinking.unlink(joined, 2)!!
        assertEquals(8.2, incoming.audio!!.single().start, 1e-12)
        assertEquals(ClipAudio(fadeIn = 0.8), incoming.audio!!.single().audio)
        assertSameSound(joined, incoming, c.id)
        assertEquals(joined, AudioUnlinking.relink(incoming, 2))
        // Both sides of a dissolve detached: the sounds overlap, so they take one track each.
        val pair = AudioUnlinking.unlink(unlinked, 2)!!
        assertEquals(listOf(0, 1), pair.audio!!.map { it.track })
        assertSameSound(joined, pair, b.id)
        assertSameSound(joined, pair, c.id)

        // The clip's own fade where there is no transition is carried as it is.
        val faded = joined.copy(clips = joined.clips.toMutableList().also { it[0] = it[0].copy(audio = ClipAudio(fadeIn = 1.5)) })
        val first = AudioUnlinking.unlink(faded, 0)!!
        assertEquals(ClipAudio(fadeIn = 1.5, fadeOut = 0.5), first.audio!!.single().audio)
        assertSameSound(faded, first, a.id)
        assertEquals(faded, AudioUnlinking.relink(first, 0))
    }

    @Test fun ownFadeUnderATransitionIsApproximatedByTheLongerOne() {
        val joined = TimelineEditing.setTransition(state, 0, 1.0)!!
        val faded = joined.copy(clips = joined.clips.toMutableList().also { it[1] = it[1].copy(audio = ClipAudio(fadeIn = 2.0, fadeOut = 1.0)) })
        val unlinked = AudioUnlinking.unlink(faded, 1)!!
        assertEquals(ClipAudio(fadeIn = 2.0, fadeOut = 1.0), unlinked.audio!!.single().audio)
        // Inside the transition's half second the clip multiplied two ramps; the item has one. Never quieter, never by more than a quarter.
        for (step in 0..100) {
            val time = 4.0 + 5.0 * step / 100
            val before = heard(faded, b.id, time)
            val after = heard(unlinked, b.id, time)
            if (time >= 4.5) assertEquals(before, after, 1e-12) else assertTrue(after >= before && after - before <= 0.25)
        }
        assertEquals(faded, AudioUnlinking.relink(unlinked, 1))
    }

    @Test fun fadesChangedWhileDetachedBecomeTheClipsOwn() {
        val joined = TimelineEditing.setTransition(state, 0, 1.0)!!
        val unlinked = AudioUnlinking.unlink(joined, 1)!!
        assertEquals(ClipAudio(fadeIn = 0.5), item(unlinked, b.id).audio)
        val longer = TimelineEditing.setAudioItemSound(unlinked, b.id, ClipAudio(fadeIn = 2.0, fadeOut = 1.0))!!
        assertEquals(ClipAudio(fadeIn = 2.0, fadeOut = 1.0), AudioUnlinking.relink(longer, 1)!!.clips[1].audio)
        // A fade removed on the item cannot stay removed once linked: linked sound follows its picture's transition.
        val hard = TimelineEditing.setAudioItemSound(unlinked, b.id, ClipAudio())!!
        assertEquals(1.0, heard(hard, b.id, 4.1), 0.0)
        val relinked = AudioUnlinking.relink(hard, 1)!!
        assertNull(relinked.clips[1].audio)
        assertEquals(0.2, heard(relinked, b.id, 4.1), 1e-9)
    }

    @Test fun relinkNeedsTheSoundExactlyUnderItsPicture() {
        val unlinked = AudioUnlinking.unlink(state, 1)!!
        // Moved.
        val moved = TimelineEditing.moveAudio(unlinked, b.id, 4.5)!!
        assertFalse(AudioUnlinking.matches(moved, 1))
        assertNull(AudioUnlinking.relink(moved, 1))
        assertEquals(0.5, AudioUnlinking.syncOffset(moved, 1)!!, 1e-12)
        // Moved back: linked again, as if nothing happened.
        assertEquals(state, AudioUnlinking.relink(TimelineEditing.moveAudio(moved, b.id, 4.0)!!, 1))
        assertEquals(state, AudioUnlinking.relink(AudioUnlinking.restoreSync(moved, 1)!!, 1))
        // Trimmed at either end: in sync, but not the same range.
        for (leading in listOf(true, false)) {
            val cut = TimelineEditing.trimAudio(unlinked, b.id, 6.0, leading)!!
            assertEquals(0.0, AudioUnlinking.syncOffset(cut, 1)!!, 0.0)
            assertNull(AudioUnlinking.restoreSync(cut, 1))
            assertNull(AudioUnlinking.relink(cut, 1))
        }
        // The picture trimmed, or sped up, under its sound.
        assertNull(AudioUnlinking.relink(TimelineEditing.trim(unlinked, 1, 1.0, 5.0, 10.0)!!, 1))
        val fast = TimelineEditing.setSpeed(unlinked, 1, 2.0)!!
        assertNull(AudioUnlinking.relink(fast, 1))
        assertNull(AudioUnlinking.syncOffset(fast, 1))
        assertNull(AudioUnlinking.restoreSync(fast, 1))
        // Removed: the clip is simply muted, and can be unlinked again once it is given its sound back.
        val removed = TimelineEditing.removeAudio(unlinked, b.id)!!
        assertFalse(AudioUnlinking.isDetached(removed, 1))
        assertNull(AudioUnlinking.relink(removed, 1))
        assertEquals(0.0, heard(removed, b.id, 5.0), 0.0)
        assertEquals(state, AudioUnlinking.setSound(removed, 1, ClipAudio()))
        // A position within rounding of the clip's still counts.
        val near = unlinked.copy(audio = listOf(unlinked.audio!![0].copy(start = 4.0 + 1e-9)))
        assertEquals(state, AudioUnlinking.relink(near, 1))
    }

    @Test fun rippleMovesThePictureAndLeavesDetachedSoundInPlace() {
        val unlinked = AudioUnlinking.unlink(state, 1)!!
        val sound = unlinked.audio!!.single()
        // Trimming, deleting or reordering an earlier clip moves B's picture. Its sound does not follow.
        val trimmed = TimelineEditing.trim(unlinked, 0, 0.0, 2.5, 4.0)!!
        assertEquals(2.5, TimelineEditing.clipStart(trimmed.clips, 1), 0.0)
        assertEquals(listOf(sound), trimmed.audio)
        assertEquals(1.5, AudioUnlinking.syncOffset(trimmed, 1)!!, 1e-12) // the sound is now 1.5 s late
        assertNull(AudioUnlinking.relink(trimmed, 1))

        val deleted = TimelineEditing.delete(unlinked, 0)!!
        assertEquals(listOf(sound), deleted.audio)
        assertEquals(4.0, AudioUnlinking.syncOffset(deleted, 0)!!, 1e-12)

        val reordered = TimelineEditing.move(unlinked, 2, 0)!!
        assertEquals(listOf(sound), reordered.audio)
        assertEquals(-3.0, AudioUnlinking.syncOffset(reordered, 2)!!, 1e-12) // the picture moved later; the sound is early

        // A dissolve added before the clip pulls its picture back, too.
        val dissolved = TimelineEditing.setTransition(unlinked, 0, 1.0, TransitionStyle.Dissolve)!!
        assertEquals(1.0, AudioUnlinking.syncOffset(dissolved, 1)!!, 1e-12)

        // Putting it back is one explicit step, after which the pair links again.
        val synced = AudioUnlinking.restoreSync(trimmed, 1)!!
        assertEquals(2.5, synced.audio!!.single().start, 1e-12)
        assertEquals(0.0, AudioUnlinking.syncOffset(synced, 1)!!, 0.0)
        assertEquals(trimmed.clips, synced.clips)
        assertEquals(TimelineEditing.trim(state, 0, 0.0, 2.5, 4.0), AudioUnlinking.relink(synced, 1))

        // A linked clip's sound goes with its picture, as it always did.
        assertEquals(1.0, heard(trimmed, c.id, 7.5 + 1.0), 0.0)
    }

    @Test fun restoreSyncStopsAtTheStartOfTheTimelineAndAtBusyTracks() {
        // B's sound trimmed to begin 1 s in, then A deleted and B's picture cut by 4.5 s: in sync it would begin before 0.
        val unlinked = AudioUnlinking.unlink(state, 1)!!
        val alone = TimelineEditing.delete(unlinked, 0)!!
        val late = TimelineEditing.trim(alone, 0, 5.5, 6.0, 10.0)!!
        assertEquals(4.0 + 4.5, AudioUnlinking.syncOffset(late, 0)!!, 1e-12)
        assertNull(AudioUnlinking.restoreSync(late, 0))
        // Both tracks busy where it belongs.
        val trimmed = TimelineEditing.trim(unlinked, 0, 0.0, 2.5, 4.0)!!
        var busy = trimmed.copy(audio = trimmed.audio!! + AudioItem(sourceID = "M", start = 2.0, sourceStart = 0.0, sourceEnd = 1.0, track = 0) +
            AudioItem(sourceID = "V", start = 2.0, sourceStart = 0.0, sourceEnd = 1.0, track = 1))
        assertNull(AudioUnlinking.restoreSync(busy, 1))
        busy = TimelineEditing.removeAudio(busy, busy.audio!!.first { it.sourceID == "V" }.id)!!
        assertEquals(1, AudioUnlinking.restoreSync(busy, 1)!!.audio!!.first { it.id == b.id }.track)
    }

    @Test fun jCutSoundStartsBeforeThePicture() {
        // B's sound is heard one second before its picture appears.
        var edit = AudioUnlinking.unlink(AudioUnlinking.unlink(state, 0)!!, 1)!!
        assertEquals(listOf(0, 0), edit.audio!!.map { it.track }) // end to end on one track
        // A's sound makes room: it stops at 3.0, while A's picture runs on to 4.0.
        edit = TimelineEditing.trimAudio(edit, a.id, 3.0, leading = false)!!
        // B's picture loses its first second, so the cut at 4.0 now shows source second 2.0.
        edit = TimelineEditing.trim(edit, 1, 2.0, 6.0, 10.0)!!
        assertEquals(1.0, AudioUnlinking.syncOffset(edit, 1)!!, 1e-12)
        // Back in sync, the whole sound begins one second before the picture's cut.
        edit = AudioUnlinking.restoreSync(edit, 1)!!
        val lead = item(edit, b.id)
        assertEquals(listOf(3.0, 1.0, 6.0, 8.0), listOf(lead.start, lead.sourceStart, lead.sourceEnd, lead.end))
        assertEquals(0, lead.track)
        assertEquals(4.0, TimelineEditing.clipStart(edit.clips, 1), 0.0)
        assertEquals(1.0, TimelineEditing.clipStart(edit.clips, 1) - lead.start, 1e-12)
        // At the picture's cut the sound plays the source second the picture shows.
        assertEquals(edit.clips[1].sourceTime(0.0), lead.sourceStart + (4.0 - lead.start), 1e-12)
        assertEquals(edit.clips[1].sourceTime(4.0), lead.sourceEnd, 1e-12) // and they end together
        // Under A's last second only B is heard.
        assertNull(TimelineEditing.audioAt(edit.audio!!, 1, 3.5))
        assertEquals(b.id, TimelineEditing.audioAt(edit.audio!!, 0, 3.5)!!.id)
        assertEquals(a.id, TimelineEditing.audioAt(edit.audio!!, 0, 2.9)!!.id)
        assertEquals(0.0, heard(edit, a.id, 3.5), 0.0)
        assertEquals(11.0, edit.duration, 0.0) // A 4 s, B 4 s, C 3 s: the sound does not change the length
        // A cut sound is no longer the clip's whole sound, so the pair does not relink.
        assertNull(AudioUnlinking.relink(edit, 1))
        assertNull(AudioUnlinking.relink(edit, 0))
    }

    @Test fun lCutSoundEndsAfterThePicture() {
        // A's sound carries on for a second over the start of B's picture.
        var edit = AudioUnlinking.unlink(AudioUnlinking.unlink(state, 0)!!, 1)!!
        // A's picture ends a second early; B's picture ripples to 3.0. Neither sound moves.
        edit = TimelineEditing.trim(edit, 0, 0.0, 3.0, 4.0)!!
        assertEquals(3.0, TimelineEditing.clipStart(edit.clips, 1), 0.0)
        val tail = item(edit, a.id)
        assertEquals(listOf(0.0, 4.0), listOf(tail.start, tail.end))
        assertEquals(0.0, AudioUnlinking.syncOffset(edit, 0)!!, 0.0)          // still in sync with what is left of A's picture
        assertEquals(1.0, tail.end - TimelineEditing.clipStart(edit.clips, 1), 1e-12) // and one second past the cut
        // B's sound is back in sync on the other track, then gives way until A's has finished.
        assertEquals(1.0, AudioUnlinking.syncOffset(edit, 1)!!, 1e-12)
        edit = AudioUnlinking.restoreSync(edit, 1)!!
        assertEquals(1, item(edit, b.id).track)
        edit = TimelineEditing.trimAudio(edit, b.id, 4.0, leading = true)!!
        val rest = item(edit, b.id)
        assertEquals(listOf(4.0, 2.0, 6.0, 8.0), listOf(rest.start, rest.sourceStart, rest.sourceEnd, rest.end))
        assertEquals(0.0, AudioUnlinking.syncOffset(edit, 1)!!, 0.0)
        // 3.0–4.0: B's picture with A's sound only.
        assertEquals(1, TimelineEditing.locate(edit.clips, 3.5)!!.index)
        assertEquals(1.0, heard(edit, a.id, 3.5), 0.0)
        assertEquals(0.0, heard(edit, b.id, 3.5), 0.0)
        assertEquals(1.0, heard(edit, b.id, 4.5), 0.0)
        assertEquals(edit.clips[1].sourceTime(1.0), rest.sourceStart, 1e-12)
    }

    @Test fun splittingThePictureDoesNotCutDetachedSound() {
        val titled = state.copy(clips = listOf(a, b.copy(texts = listOf(TextOverlay(start = 1.0, end = 5.0)), bookmarks = listOf(Bookmark(time = 4.0))), c))
        val unlinked = AudioUnlinking.unlink(titled, 1)!!
        // Titles and bookmarks stay with the picture.
        assertEquals(titled.clips[1].texts, unlinked.clips[1].texts)
        assertEquals(titled.clips[1].bookmarks, unlinked.clips[1].bookmarks)
        val sound = unlinked.audio!!.single()
        val split = TimelineEditing.split(unlinked, 6.0)!!
        // Two silent pictures over one uncut sound: everything is heard once, as before.
        assertEquals(listOf(sound), split.audio)
        assertEquals(listOf(true, true), split.clips.subList(1, 3).map { it.audio!!.muted })
        for (time in listOf(4.5, 5.9, 6.0, 7.5, 8.9)) assertEquals(1.0, heard(split, b.id, time) + heard(split, split.clips[2].id, time), 0.0)
        // The first piece keeps the clip's id and with it the sound; the second is a plain muted clip.
        assertEquals(sound, AudioUnlinking.detachedSound(split, 1))
        assertNull(AudioUnlinking.detachedSound(split, 2))
        assertEquals(0.0, AudioUnlinking.syncOffset(split, 1)!!, 0.0)
        assertTrue(AudioUnlinking.valid(split))
        // The sound is longer than the first piece now, so they do not relink until it is cut to match.
        assertNull(AudioUnlinking.relink(split, 1))
        val cut = TimelineEditing.trimAudio(split, b.id, 6.0, leading = false)!!
        val relinked = AudioUnlinking.relink(cut, 1)!!
        assertNull(relinked.audio)
        assertNull(relinked.clips[1].audio)
        assertEquals(listOf(1.0 to 3.0, 3.0 to 6.0), relinked.clips.subList(1, 3).map { it.start to it.end })
    }

    @Test fun deletingThePictureLeavesItsDetachedSound() {
        val unlinked = AudioUnlinking.unlink(state, 1)!!
        val sound = unlinked.audio!!.single()
        val deleted = TimelineEditing.delete(unlinked, 1)!!
        // The picture is gone and later clips close the gap; the sound stays as free-standing sound.
        assertEquals(listOf(a, c), deleted.clips)
        assertEquals(listOf(sound), deleted.audio)
        assertEquals(7.0, deleted.duration, 0.0)
        assertNull(AudioUnlinking.clipIndex(deleted, sound.id))
        assertTrue(deleted.clips.indices.none { AudioUnlinking.isDetached(deleted, it) })
        assertTrue(AudioUnlinking.valid(deleted))
        // It is ordinary sound now and is removed like any other.
        assertNull(TimelineEditing.removeAudio(deleted, sound.id)!!.audio)
        // Deleting the sound instead leaves the picture, silent, and moves nothing.
        val silent = TimelineEditing.removeAudio(unlinked, sound.id)!!
        assertEquals(unlinked.clips, silent.clips)
        assertEquals(state.duration, silent.duration, 0.0)
    }

    @Test fun pauseRemovalCutsThePictureOnly() {
        val unlinked = AudioUnlinking.unlink(state, 1)!!
        val sound = unlinked.audio!!.single()
        val cut = TimelineEditing.removeIntervals(unlinked, 1, listOf(SourceInterval(2.0, 3.0), SourceInterval(4.0, 4.5)))!!
        assertEquals(listOf(1.0 to 2.0, 3.0 to 4.0, 4.5 to 6.0), cut.clips.subList(1, 4).map { it.start to it.end })
        assertTrue(cut.clips.subList(1, 4).all { it.audio == ClipAudio(muted = true) })
        // The sound is whole and where it was: it now runs 1.5 s longer than its pictures.
        assertEquals(listOf(sound), cut.audio)
        assertEquals(sound, AudioUnlinking.detachedSound(cut, 1))
        assertNull(AudioUnlinking.detachedSound(cut, 2))
        assertEquals(0.0, AudioUnlinking.syncOffset(cut, 1)!!, 0.0) // the first piece still plays under its own sound
        assertNull(AudioUnlinking.relink(cut, 1))
        assertEquals(state.duration - 1.5, cut.duration, 1e-12)
    }

    @Test fun validationFindsBrokenPairs() {
        val unlinked = AudioUnlinking.unlink(state, 1)!!
        assertTrue(AudioUnlinking.valid(state))
        assertTrue(AudioUnlinking.valid(unlinked))
        assertTrue(AudioUnlinking.valid(TimelineEditing.moveAudio(unlinked, b.id, 20.0)!!))
        // The item plays some other source under the clip's name.
        assertFalse(AudioUnlinking.valid(unlinked.copy(audio = listOf(unlinked.audio!![0].copy(sourceID = "M")))))
        // The clip was unmuted behind its item: its sound would play twice, and it no longer counts as detached.
        val doubled = TimelineEditing.setAudio(unlinked, 1, ClipAudio())!!
        assertFalse(AudioUnlinking.valid(doubled))
        assertNull(AudioUnlinking.detachedSound(doubled, 1))
        assertEquals(2.0, heard(doubled, b.id, 5.0), 0.0)
        assertEquals(Refusal.AlreadyDetached, AudioUnlinking.refusal(doubled, 1))
        assertNull(AudioUnlinking.relink(doubled, 1))
    }

    @Test fun projectsKeepDetachedSoundThroughSavingAndUndo() {
        val first = SourceMedia(filename = "a.mp4", duration = 4.0)
        val second = SourceMedia(filename = "b.mp4", duration = 6.0)
        var project = Project("Cuts").addRecording(first).addRecording(second)
        val linked = project.edit
        project = project.commit(TimelineEditing.setAudio(project.edit, 1, ClipAudio(volumeDB = -6.0, fadeOut = 1.0))!!)
        project = project.commit(AudioUnlinking.unlink(project.edit, 1)!!)
        // Nothing new is saved: a muted clip and a sound item, which schema 5 readers already play correctly.
        assertEquals(5, project.schemaVersion)
        project.validate()
        assertTrue(project.history.all(AudioUnlinking::valid))
        val sound = AudioUnlinking.detachedSound(project.edit, 1)!!
        assertEquals(listOf(second.id, 4.0, 0.0, 6.0), listOf(sound.sourceID, sound.start, sound.sourceStart, sound.sourceEnd))
        assertEquals(ClipAudio(volumeDB = -6.0, fadeOut = 1.0), sound.audio)

        // A J cut, saved and read back.
        project = project.commit(TimelineEditing.trim(project.edit, 1, 1.0, 6.0, second.duration)!!)
        project = project.commit(AudioUnlinking.restoreSync(project.edit, 1)!!)
        project.validate()
        val files = mutableMapOf<String, ByteArray>()
        val manifest = project.manifest { name, data -> files[name] = data }
        val restored = Project.readManifest(manifest) { files.getValue(it) }
        assertEquals(project, restored)
        assertEquals(3.0, AudioUnlinking.detachedSound(restored.edit, 1)!!.start, 0.0)
        assertEquals(0.0, AudioUnlinking.syncOffset(restored.edit, 1)!!, 0.0)

        // Undo walks back through the cut to the linked clip; a take recorded meanwhile leaves the sound alone.
        val earlier = restored.undo().undo()
        assertTrue(AudioUnlinking.matches(earlier.edit, 1))
        assertNull(earlier.undo().edit.audio)
        assertEquals(linked, earlier.undo().undo().edit)
        val grown = restored.addRecording(SourceMedia(filename = "c.mp4", duration = 2.0))
        assertEquals(3, grown.edit.clips.size)
        assertEquals(restored.edit.audio, grown.edit.audio)

        // Relinking is an ordinary edit, too.
        val relinked = earlier.commit(AudioUnlinking.relink(earlier.edit, 1)!!)
        assertNull(relinked.edit.audio)
        assertEquals(ClipAudio(volumeDB = -6.0, fadeOut = 1.0), relinked.edit.clips[1].audio)
        relinked.validate()

        // The project's own validation still guards the item: a range outside its recording is not a valid document.
        val broken = project.copy(snapshots = project.snapshots.dropLast(1) +
            EditSnapshot(project.edit.copy(captureBase = null, audio = listOf(sound.copy(sourceEnd = 9.0))), 2))
        assertThrows(ProjectException::class.java) { broken.validate() }
    }
}
