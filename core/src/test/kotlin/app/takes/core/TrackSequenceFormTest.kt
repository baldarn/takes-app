package app.takes.core

import app.takes.core.TrackFixtures.clip
import app.takes.core.TrackFixtures.music
import app.takes.core.TrackFixtures.second
import app.takes.core.TrackFixtures.take
import app.takes.core.TrackFixtures.uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Back from the track timeline to the sequence form, and there and back again (docs/TRACKS-PLAN.md, 3.3). */
class TrackSequenceFormTest {
    private fun ticks(seconds: Double) = EditTime.of(seconds)
    private val a = clip(1, take, 0.0, 12.5)
    private val b = clip(2, second, 0.0, 8.25)
    private val c = clip(3, take, 20.0, 31.0)
    private val tick = 1.0 / EditTime.SCALE

    private fun returned(state: EditState): EditState = state.toTimeline().toSequence() ?: error("The edit did not come back")

    private fun project(state: EditState) = Project("Test", sources = TrackFixtures.sources, snapshots = listOf(EditSnapshot(state, 0)))

    /** What must hold of any edit that went there and back. */
    private fun check(name: String, state: EditState): EditState {
        val timeline = state.toTimeline()
        val back = timeline.toSequence()
        assertNotNull(name, back)
        back!!
        // Going again changes nothing more.
        assertEquals(name, back, returned(back))
        // It is an edit a project may hold, and the movie is as long as it was.
        project(back).validate()
        assertEquals(name, state.duration, back.duration, 0.0)
        // The same footage plays at the same places, and every transition is the one it was, at the fit it had.
        assertEquals(name, state.clips.map { listOf(it.id, it.sourceID, it.start, it.end, it.speed, it.texts, it.color, it.crop, it.subtitleWords) },
            back.clips.map { listOf(it.id, it.sourceID, it.start, it.end, it.speed, it.texts, it.color, it.crop, it.subtitleWords) })
        assertEquals(name, TimelineEditing.layout(state.clips), TimelineEditing.layout(back.clips))
        // Every clip sounds as it did.
        state.clips.indices.forEach { index ->
            for (step in 0..8) {
                val offset = state.clips[index].duration * step / 8
                assertEquals(name, TimelineEditing.soundLevel(state.clips, index, offset), TimelineEditing.soundLevel(back.clips, index, offset), 1e-12)
            }
        }
        assertEquals(name, TimelineEditing.bookmarkTimes(state.clips), TimelineEditing.bookmarkTimes(back.clips))
        // Every sound item is there, within a tick of where it was, as loud as it was.
        val items = state.audio.orEmpty().associateBy { it.id }
        assertEquals(name, items.keys, back.audio.orEmpty().map { it.id }.toSet())
        for (item in back.audio.orEmpty()) {
            val before = items.getValue(item.id)
            assertTrue(name, abs(item.start - before.start) <= tick)
            assertEquals(name, listOf(before.sourceID, before.sourceStart, before.sourceEnd), listOf(item.sourceID, item.sourceStart, item.sourceEnd))
            for (step in 0..8) {
                val offset = item.duration * step / 8
                assertEquals(name, TimelineEditing.soundLevel(before, offset), TimelineEditing.soundLevel(item, offset), 1e-12)
            }
        }
        assertEquals(name, state.subtitles?.copy(timingFingerprint = ""), back.subtitles?.copy(timingFingerprint = ""))
        assertEquals(name, SubtitleEditing.cues(state), SubtitleEditing.cues(back))
        assertEquals(name, SubtitleEditing.groupingAvailable(state), SubtitleEditing.groupingAvailable(back))
        return back
    }

    // MARK: Round trips

    @Test
    fun everyKindOfEditComesBack() {
        for ((name, state) in TrackFixtures.all) check(name, state)
    }

    @Test
    fun anEditWithNothingTheTimelineCannotTellApartComesBackEqual() {
        // Built by the editor's own functions, with fits saved, and no sound of their own to round.
        for (name in listOf("empty", "one take", "plain", "unquantised", "shortened", "fades", "speed", "captions", "look", "clip sound") +
            TransitionStyle.entries.map { "transitions ${it.name}" }) {
            val state = TrackFixtures.all.getValue(name)
            assertEquals(name, state, returned(state))
        }
    }

    @Test
    fun musicComesBackOnItsTracksAndStillMeetingItself() {
        val state = TrackFixtures.music()
        val back = check("music", state)
        assertEquals(state.clips, back.clips)
        assertEquals(state.audio!!.map { it.id to it.track }, back.audio!!.map { it.id to it.track })
        assertEquals(state.audio!!.map { it.audio }, back.audio!!.map { it.audio })
        val (first, rest) = back.audio!!.filter { it.track == 0 }
        assertEquals(first.end, rest.start, 0.0)
        // A sound begins on a tick now.
        assertEquals(ticks(state.audio!![0].start).seconds, back.audio!![0].start, 0.0)
    }

    @Test
    fun unlinkedSoundComesBackUnlinked() {
        val state = TrackFixtures.unlinked()
        val back = check("unlinked", state)
        assertTrue(AudioUnlinking.isDetached(back, 1))
        assertEquals(AudioUnlinking.syncOffset(state, 1)!!, AudioUnlinking.syncOffset(back, 1)!!, tick)
        assertEquals(state.clips[1].subtitleWords, back.clips[1].subtitleWords)
        // Put back under its picture, it is exactly there again and links.
        val synced = AudioUnlinking.restoreSync(state, 1)!!
        val again = check("synced", synced)
        assertEquals(TimelineEditing.clipStart(again.clips, 1), again.audio!!.single().start, 0.0)
        assertNotNull(AudioUnlinking.relink(again, 1))
    }

    @Test
    fun randomEditsComeBack() {
        val random = TrackFixtures.Random(11)
        val styles = TransitionStyle.entries
        repeat(4_000) { round ->
            val count = 1 + random.below(7)
            var clips = List(count) { index ->
                val source = if (random.next() < 0.5) take else second
                val start = random.next(0.0, 30.0)
                TimelineClip(
                    uuid(1000 + index), source.id, start, start + random.next(0.5, 9.0),
                    speed = if (random.next() < 0.2) SpeedSettings(uuid(2000 + index), random.next(0.25, 4.0)) else null,
                    audio = if (random.next() < 0.3) ClipAudio(volumeDB = random.next(-20.0, 6.0), fadeIn = random.next(0.0, 0.05), denoise = if (random.next() < 0.5) 0.5 else 0.0) else null,
                    bookmarks = if (random.next() < 0.3) listOf(Bookmark(uuid(3000 + index), start + 0.1, if (random.next() < 0.5) "Mark" else null)) else null,
                )
            }
            clips = clips.mapIndexed { index, item ->
                fun settings(id: Int, next: String?) = TransitionSettings(
                    id = uuid(id + index), nextClipID = next, style = styles[random.below(styles.size)], duration = random.next(0.1, 3.0),
                    hold = if (random.next() < 0.3) random.next(0.0, 1.0) else null, audioOffset = if (random.next() < 0.3) random.next(-0.5, 0.5) else null,
                )
                item.copy(
                    openingTransition = if (random.next() < 0.15) settings(4000, null) else null,
                    transition = if (random.next() < 0.4) settings(5000, if (random.next() < 0.75) clips.getOrNull(index + 1)?.id else null) else null,
                )
            }
            val length = TimelineEditing.duration(clips)
            val audio = List(random.below(3)) { index ->
                val from = random.next(0.0, 100.0)
                // One on each track, so they never collide.
                AudioItem(uuid(6000 + index), music.id, random.next(0.0, length), from, from + random.next(0.5, 20.0), track = index)
            }.sortedBy { it.start }
            val state = EditState(clips, audio = audio.ifEmpty { null })
            val back = check("round $round", state)
            // What a sequence can say, it says again: the clips are the ones given, with every fit saved.
            assertEquals("round $round", TimelineEditing.fitted(clips).map { it.copy(openingTransition = null, openingTransitionFit = null, transition = null, transitionFit = null) },
                back.clips.map { it.copy(openingTransition = null, openingTransitionFit = null, transition = null, transitionFit = null) })
            assertEquals("round $round", TimelineEditing.transitions(clips).map { it.transition to it.factor }, TimelineEditing.transitions(back.clips).map { it.transition to it.factor })
            assertEquals("round $round", back.clips, TimelineEditing.fitted(back.clips))
        }
    }

    // MARK: What comes back changed

    @Test
    fun settingsThatWereNotInPlayAreGone() {
        val stale = TransitionSettings(nextClipID = c.id, duration = 1.0)
        val shadowed = TransitionSettings(style = TransitionStyle.FadeColor, duration = 0.5)
        val into = TransitionSettings(id = uuid(14), nextClipID = c.id, duration = 0.5)
        val state = EditState(listOf(a.copy(transition = stale, transitionFit = TransitionFit(0.5)), b.copy(transition = into), c.copy(openingTransition = shadowed)))
        val back = returned(state)
        assertEquals(listOf(a, b.copy(transition = into, transitionFit = TransitionFit(1.0)), c), back.clips)
    }

    @Test
    fun aTransitionWithoutASavedFitComesBackFitted() {
        val long = TransitionSettings(id = uuid(13), nextClipID = b.id, duration = 30.0)
        val clips = listOf(a.copy(transition = long), b)
        assertEquals(TimelineEditing.fitted(clips), returned(EditState(clips)).clips)
    }

    @Test
    fun aFadeComesBackAsItWasSaved() {
        // Longer than half its clip, and longer than the clip: the same fade both ways.
        val back = returned(EditState(listOf(a.copy(audio = ClipAudio(fadeIn = 10.0, fadeOut = 20.0)))))
        assertEquals(ClipAudio(fadeIn = 10.0, fadeOut = 20.0), back.clips[0].audio)
        // Cut by a split: what is behind the edge of each piece comes back with it.
        val split = TimelineEditing.split(EditState(listOf(a.copy(audio = ClipAudio(volumeDB = -3.0, fadeIn = 4.0, fadeOut = 3.0)), b)), 1.0)!!
        assertEquals(ClipAudio(volumeDB = -3.0, fadeIn = 4.0, fadeInElapsed = 1.0, fadeOut = 3.0), split.clips[1].audio)
        assertEquals(split.clips.map { it.audio }, returned(split).clips.map { it.audio })
        val twice = TimelineEditing.split(split, 11.0)!!
        assertEquals(ClipAudio(volumeDB = -3.0, fadeIn = 4.0, fadeInElapsed = 1.0, fadeOut = 3.0, fadeOutElapsed = 1.5), twice.clips[1].audio)
        assertEquals(twice.clips.map { it.audio }, returned(twice).clips.map { it.audio })
        // And on a sound of its own.
        val item = AudioItem(uuid(30), music.id, 4.5, 10.0, 25.0, audio = ClipAudio(fadeIn = 2.0, fadeInElapsed = 0.5, fadeOut = 20.0, fadeOutElapsed = 7.25))
        assertEquals(item.audio, returned(EditState(listOf(a, b), audio = listOf(item))).audio!!.single().audio)
        // An elapsed part of nothing is saved as none.
        assertEquals(ClipAudio(fadeIn = 2.0), returned(EditState(listOf(a.copy(audio = ClipAudio(fadeIn = 2.0, fadeInElapsed = 0.0))))).clips[0].audio)
    }

    @Test
    fun whatSaysNothingComesBackAsNothing() {
        val state = EditState(
            listOf(a.copy(audio = ClipAudio(), bookmarks = emptyList()), b.copy(bookmarks = listOf(Bookmark(uuid(60), 1.0, "")))),
            audio = listOf(AudioItem(uuid(30), music.id, 1.0, 0.0, 5.0, audio = ClipAudio(denoise = 0.7))),
        )
        val back = returned(state)
        assertNull(back.clips[0].audio)
        assertNull(back.clips[0].bookmarks)
        assertNull(back.clips[1].bookmarks!!.single().label)
        assertNull(back.audio!!.single().audio)
        assertNull(returned(EditState(listOf(a), audio = emptyList())).audio)
    }

    @Test
    fun soundItemsComeBackOnTheFirstFreeTrack() {
        val alone = AudioItem(uuid(30), music.id, 1.0, 0.0, 5.0, track = 1)
        assertEquals(0, returned(EditState(listOf(a), audio = listOf(alone))).audio!!.single().track)
        val pair = listOf(AudioItem(uuid(31), music.id, 2.0, 0.0, 5.0, track = 1), AudioItem(uuid(32), music.id, 4.0, 0.0, 5.0, track = 0))
        assertEquals(listOf(0, 1), returned(EditState(listOf(a), audio = pair)).audio!!.map { it.track })
    }

    @Test
    fun aSoundItemAtAClipsEdgeComesBackExactlyThere() {
        // The edge of the second clip is on no tick.
        val clips = listOf(clip(1, take, 0.0, 3.3333333), b)
        val edge = TimelineEditing.clipStart(clips, 1)
        val back = returned(EditState(clips, audio = listOf(AudioItem(uuid(30), music.id, edge, 0.0, 5.0))))
        assertEquals(edge, back.audio!!.single().start, 0.0)
        // Anywhere else it is on the nearest tick.
        val free = returned(EditState(clips, audio = listOf(AudioItem(uuid(30), music.id, 1.0000011, 0.0, 5.0))))
        assertEquals(ticks(1.0000011).seconds, free.audio!!.single().start, 0.0)
    }

    @Test
    fun aMutedClipForgetsWhatItRememberedForRelinking() {
        val state = EditState(
            listOf(a.copy(audio = ClipAudio(volumeDB = -6.0, muted = true, fadeIn = 1.0)), b),
            audio = listOf(AudioItem(a.id, take.id, 0.0, 0.0, 12.5, audio = ClipAudio(volumeDB = -3.0))),
        )
        val back = returned(state)
        assertEquals(ClipAudio(muted = true), back.clips[0].audio)
        assertEquals(ClipAudio(volumeDB = -3.0), back.audio!!.single().audio)
        assertEquals(a.id, back.audio!!.single().id)
    }

    @Test
    fun aSoundItemUnderAClipsIdKeepsThatId() {
        val state = EditState(listOf(a, b), audio = listOf(AudioItem(a.id, music.id, 1.0, 0.0, 5.0)))
        assertEquals(state, returned(state))
    }

    @Test
    fun aStaleCaptionFingerprintComesBackEmpty() {
        val state = TrackFixtures.captions().let { it.copy(subtitles = it.subtitles!!.copy(timingFingerprint = "stale")) }
        assertEquals("", returned(state).subtitles!!.timingFingerprint)
        assertFalse(SubtitleEditing.groupingAvailable(returned(state)))
    }

    // MARK: What is not a sequence

    // Without captions: a fingerprint would go stale with every change below and refuse them all by itself.
    private val timeline = TrackFixtures.captions().copy(subtitles = null).toTimeline()

    @Test
    fun aSequentialTimelineAnswersAndConvertsToItself() {
        for ((name, state) in TrackFixtures.all) {
            val converted = state.toTimeline()
            assertTrue(name, TrackSequenceForm.isSequential(converted))
            // Exactly, where no sound of its own had to be given seconds again.
            if (state.audio == null) assertEquals(name, converted, converted.toSequence()!!.toTimeline())
        }
    }

    @Test
    fun aTimelineThatCouldNotHaveBeenASequenceIsNotOne() {
        fun refused(changed: TrackTimeline) = assertNull(changed.toSequence())
        val first = timeline.video[0]
        val sound = timeline.audio[0]
        // A gap, an overlap, a picture on its own, a picture moved alone.
        refused(timeline.copy(video = timeline.video.map { if (it.id == c.id) it.copy(start = it.start + ticks(1.0)) else it }))
        refused(timeline.copy(video = timeline.video.map { if (it.id == b.id) it.copy(start = it.start - EditTime(1)) else it }))
        refused(timeline.copy(audio = timeline.audio.drop(1), links = timeline.links.drop(1)))
        refused(timeline.copy(video = listOf(first.copy(start = ticks(5.0))) + timeline.video.drop(1)))
        // A cut a tick apart is not a cut.
        refused(timeline.copy(video = listOf(first.copy(mapping = SourcePlaybackMap.single(first.mapping.duration - EditTime(1), 0.0, 12.5))) + timeline.video.drop(1)))
        // Pictures placed side by side.
        refused(timeline.copy(placements = listOf(VideoPlacementRegion("R", setOf(a.id, b.id), mapOf(a.id to VideoPlacement(), b.id to VideoPlacement())))))
        // A bookmark on the project's clock, on a sound, or on footage that is not there.
        refused(timeline.copy(bookmarks = timeline.bookmarks + TimelineBookmark("P", anchor = BookmarkAnchor.Project(ticks(1.0)))))
        refused(timeline.copy(bookmarks = timeline.bookmarks + TimelineBookmark("L", anchor = BookmarkAnchor.Local(a.id, ticks(1.0)))))
        refused(timeline.copy(bookmarks = timeline.bookmarks + TimelineBookmark("M", anchor = BookmarkAnchor.Source("gone", take.id, 1.0))))
        // A sound cut on its own, in two runs, with a chain on part of it, or a censor.
        refused(timeline.copy(audio = listOf(sound.copy(mapping = SourcePlaybackMap.single(sound.mapping.duration - ticks(1.0), 0.0, 11.5))) + timeline.audio.drop(1)))
        refused(timeline.copy(audio = listOf(sound.copy(mapping = SourcePlaybackMap(listOf(PlaybackRun(ticks(6.0), 0.0, 6.0), PlaybackRun(ticks(6.5), 6.0, 12.5))))) + timeline.audio.drop(1)))
        refused(timeline.copy(audio = listOf(sound.copy(enhancements = listOf(AudioEnhancementRegion(sound.id, EditInterval.of(0.0, 3.0), listOf(AudioEffect.make(AudioEffectKind.VoiceIsolation, sound.id)))))) + timeline.audio.drop(1)))
        refused(timeline.copy(audio = listOf(sound.copy(enhancements = listOf(AudioEnhancementRegion(sound.id, EditInterval(EditTime.ZERO, sound.mapping.duration), listOf(AudioEffect.make(AudioEffectKind.Gain, sound.id)))))) + timeline.audio.drop(1)))
        refused(timeline.copy(audio = listOf(sound.withCensors(listOf(AudioCensorRegion("X", EditInterval.of(1.0, 2.0), ticks(1.0), AudioCensorSettings())))) + timeline.audio.drop(1)))
        // A fade already under way is one a clip can hold since a split keeps it.
        assertEquals(ClipAudio(fadeIn = 2.0, fadeInElapsed = 0.5), timeline.copy(audio = listOf(sound.copy(fadeIn = AudioEdgeFade(2.0, 0.5))) + timeline.audio.drop(1)).toSequence()!!.clips[0].audio)
        // A sound of its own with words, at another speed, or a third one playing at once.
        val extra = AudioTimelineItem(id = uuid(30), occurrenceID = uuid(30), sourceID = music.id, start = ticks(1.0), mapping = SourcePlaybackMap.single(ticks(5.0), 0.0, 5.0))
        assertNotNull(timeline.copy(audio = timeline.audio + extra).toSequence())
        refused(timeline.copy(audio = timeline.audio + extra.copy(subtitleWords = listOf(SubtitleWord("W", "P", "word", 1.0, 2.0)))))
        refused(timeline.copy(audio = timeline.audio + extra.copy(mapping = SourcePlaybackMap.single(ticks(2.5), 0.0, 5.0))))
        refused(timeline.copy(audio = timeline.audio + extra + extra.copy(id = uuid(31), occurrenceID = uuid(31)) + extra.copy(id = uuid(32), occurrenceID = uuid(32))))
        // An occurrence or an offset that a sequence does not have.
        refused(timeline.copy(video = listOf(first.copy(localOrigin = ticks(1.0))) + timeline.video.drop(1)))
        refused(timeline.copy(video = listOf(first.copy(occurrenceID = "other")) + timeline.video.drop(1)))
        // A stretch that is not its transition's, and a transition between pictures that are not neighbours.
        refused(timeline.copy(generated = listOf(TimelineGeneratedRegion("G", "T", EditInterval.of(1.0, 2.0)))))
        refused(timeline.copy(transitions = listOf(TimelineTransition(a.id, c.id, TransitionSettings(nextClipID = c.id), ticks(11.5)))))
    }

    /**
     * A layer decides only which of two pictures that play together is in
     * front. The original leaves a picture on the layer a Move gave it once
     * the overlap is gone (YouRec/TrackTimeline.swift:726-739, :847-897), and
     * keeps its lists in the order things were added: neither stops a
     * timeline from being a sequence. Two pictures of a transition the other
     * way round do.
     */
    @Test
    fun layersThatDecideNothingAndTheOrderOfListsAreNotRead() {
        val expected = timeline.toSequence()!!
        assertEquals(expected, timeline.copy(video = listOf(timeline.video[0].copy(layer = 5)) + timeline.video.drop(1)).toSequence())
        assertEquals(expected, timeline.copy(video = timeline.video.map { it.copy(layer = 7) }).toSequence())
        assertEquals(expected, timeline.copy(video = timeline.video.reversed(), audio = timeline.audio.reversed(), links = timeline.links.reversed()).toSequence())
        // A sequence is read as before: the layers of its conversion are still the ones it has.
        for ((name, state) in TrackFixtures.all) assertEquals(name, state.toTimeline().video.indices.toList(), state.toTimeline().video.map { it.layer })

        val dissolve = TrackFixtures.transitions(TransitionStyle.Dissolve).toTimeline()
        val read = dissolve.toSequence()!!
        val relation = dissolve.transitions.first { it.outgoing != null && it.incoming != null }
        val layers = dissolve.video.associate { it.id to it.layer }
        // The two of the dissolve play together: the one it leads into stays in front.
        fun swapped(outgoing: Int, incoming: Int) = dissolve.copy(video = dissolve.video.map {
            when (it.id) { relation.outgoing -> it.copy(layer = outgoing); relation.incoming -> it.copy(layer = incoming); else -> it }
        })
        val low = layers.getValue(relation.outgoing!!)
        val high = layers.getValue(relation.incoming!!)
        assertTrue(low < high)
        assertEquals(read, swapped(low, high + 10).toSequence())
        assertNull(swapped(high, low).toSequence())
    }
}
