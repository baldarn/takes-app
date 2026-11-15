package app.takes.core

import app.takes.core.TrackFixtures.uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sequence view of the track timeline: the view and its merge, the way back to a sequence, and today's functions run through the track model. */
class TrackSequenceViewTest {
    private fun t(seconds: Double) = EditTime.of(seconds)

    private val source = SourceMedia(id = uuid(1), filename = "source.mp4", duration = 20.0)
    private val music = SourceMedia(id = uuid(2), filename = "music.m4a", duration = 30.0, audioOnly = true)
    private val sources = listOf(source, music)
    private val words = listOf(SubtitleWord(uuid(40), uuid(41), "hello", 0.5, 0.9), SubtitleWord(uuid(42), uuid(41), "there", 1.0, 1.4))
    private val clips = listOf(
        TimelineClip(uuid(10), source.id, 0.0, 4.0, audio = ClipAudio(volumeDB = -3.0, fadeIn = 0.5, denoise = 0.4), bookmarks = listOf(Bookmark(uuid(30), 1.0, "one")), subtitleWords = words),
        TimelineClip(uuid(11), source.id, 4.0, 8.0, color = ColorSettings(id = uuid(31), exposure = 0.2)),
        TimelineClip(uuid(12), source.id, 8.0, 12.0),
    )
    private val state = TimelineEditing.setJoin(
        EditState(clips, audio = listOf(AudioItem(uuid(20), music.id, 1.0, 0.0, 6.0, audio = ClipAudio(volumeDB = -6.0))), subtitles = SubtitleSettings(id = uuid(50))),
        2, TransitionSettings(id = uuid(32), duration = 1.0),
    )!!
    private val timeline = state.toTimeline()

    private fun done(result: SequenceResult?): EditState {
        assertNotNull(result)
        assertNull("${result!!.obstacle} ${result.detail}", result.obstacle)
        assertNull(TrackInvariants.violation(result.timeline, sources))
        return result.state!!
    }

    // MARK: Back to a sequence

    @Test
    fun everyStepOfEveryGoldenProjectComesBackAsItConverts() {
        for (golden in GoldenProjects.build(withLong = false)) {
            golden.project.snapshots.forEachIndexed { step, snapshot ->
                val converted = snapshot.state.toTimeline()
                val result = TrackSequenceView.sequence(converted)
                val back = result.state ?: error("${golden.name} step $step: ${result.obstacle} ${result.detail}")
                val again = back.toTimeline()
                assertEquals("${golden.name} step $step", converted.copy(audio = emptyList(), subtitles = null), again.copy(audio = emptyList(), subtitles = null))
                assertEquals("${golden.name} step $step", converted.audio.map { it.id }, again.audio.map { it.id })
                assertEquals("${golden.name} step $step", converted.subtitles, again.subtitles)
                assertEquals("${golden.name} step $step", back.clips, converted.toSequence()?.clips)
                assertTrue(SequenceNote.SilentPicture !in result.notes)
            }
        }
    }

    @Test
    fun aTimelineAnEditLeftWithNewIdsLayersAndOccurrencesIsStillASequence() {
        val cut = TrackEditing.splitAtPlayhead(timeline, 2.0)
        assertNull(cut.toSequence())
        val back = done(TrackSequenceView.sequence(cut))
        assertEquals(4, back.clips.size)
        assertEquals(listOf(0.0 to 2.0, 2.0 to 4.0), back.clips.take(2).map { it.start to it.end })
        // The mark and the words are on the piece that plays them; the fade stays at its edge.
        assertEquals(listOf(uuid(30)), back.clips[0].bookmarks?.map { it.id })
        assertNull(back.clips[1].bookmarks)
        assertEquals(0.5, back.clips[0].audio!!.fadeIn, 0.0)
        assertEquals(0.0, back.clips[1].audio!!.fadeIn, 0.0)
        assertEquals(0.4, back.clips[1].audio!!.denoise, 0.0)
        // The music was cut too: two pieces that meet, on one track.
        assertEquals(2, back.audio?.size)
        assertEquals(setOf(0), back.audio?.map { it.track }?.toSet())
        assertEquals(state.duration, back.duration, TransitionLayout.TICK)
    }

    @Test
    fun whatASequenceCannotHoldIsNamed() {
        fun obstacle(timeline: TrackTimeline) = TrackSequenceView.sequence(timeline).obstacle
        val second = timeline.selection(clips[1].id)
        assertEquals(SequenceObstacle.Gap, obstacle(timeline.unlinked(second).let { it.removing(it.selection(clips[1].id)).timeline }))
        // The mark of a clip that was deleted shows nowhere: the timeline keeps it, the sequence has no place for it.
        val gone = TrackSequenceView.sequence(timeline.removing(timeline.selection(clips[0].id)).timeline)
        assertEquals(setOf(SequenceNote.HiddenBookmark), gone.notes)
        assertTrue(gone.state!!.clips.all { it.bookmarks == null })
        assertEquals(SequenceObstacle.Overlap, obstacle(timeline.moved(timeline.selection(clips[1].id), t(-1.0))))
        val sound = timeline.audio[0].id
        // A fade cut by a split, and one longer than half its item, are fades a sequence holds.
        assertNull(obstacle(TrackEditing.splitAtPlayhead(timeline, 0.25)))
        assertNull(obstacle(TrackAudio.settingFade(timeline, sound, AudioFadeEdge.FadeOut, 3.0)))
        assertEquals(SequenceObstacle.Sound, obstacle(TrackAudio.censoring(timeline, AudioCensorSettings(), TimelineSelection(audio = setOf(sound), range = EditInterval.of(1.0, 2.0)))))
        assertEquals(SequenceObstacle.Bookmark, obstacle(timeline.copy(bookmarks = timeline.bookmarks + TimelineBookmark(uuid(60), "", BookmarkAnchor.Project(t(1.0))))))
        assertEquals(SequenceObstacle.Links, obstacle(timeline.joining(timeline.selection(setOf(clips[0].id, clips[1].id))).valueOrNull!!.timeline))
        val window = VideoPlacementRegion(participants = setOf(clips[0].id, clips[1].id), videos = mapOf(clips[0].id to VideoPlacement(), clips[1].id to VideoPlacement(width = 0.5)))
        assertEquals(SequenceObstacle.Placement, obstacle(timeline.copy(placements = listOf(window))))
        val third = (0 until 3).fold(timeline) { result, _ -> TrackAudio.inserting(result, music, t(1.0)).timeline }
        assertEquals(SequenceObstacle.SoundTracks, obstacle(third))
    }

    @Test
    fun aPictureWhoseSoundWasDeletedIsAMutedClip() {
        val unlinked = timeline.unlinked(timeline.selection(clips[1].id))
        val silent = unlinked.removing(TimelineSelection(audio = setOf(TrackTimeline.counterpart(clips[1].id)))).timeline
        val result = TrackSequenceView.sequence(silent)
        assertEquals(setOf(SequenceNote.SilentPicture), result.notes)
        assertEquals(ClipAudio(muted = true), result.state!!.clips[1].audio)
        assertEquals(state.audio, result.state!!.audio)
    }

    // MARK: The view and its merge

    @Test
    fun theViewShowsWhatAClipHoldsAndTheMergeWritesBackOnlyWhatChanged() {
        val view = TrackSequenceView.clips(timeline)
        assertEquals(state.clips, view)
        assertSame(timeline, TrackSequenceView.merging(timeline, view))
        // What the view does not show stays: a censor on the first clip's sound, through a change of color on it.
        val sound = timeline.audio[0].id
        val censored = TrackAudio.censoring(timeline, AudioCensorSettings(), TimelineSelection(audio = setOf(sound), range = EditInterval.of(1.0, 2.0)))
        val colored = TrackSequenceView.merging(censored, TrackSequenceView.clips(censored).map { if (it.id == clips[0].id) it.copy(color = ColorSettings(id = uuid(61), warmth = 0.3)) else it })
        assertEquals(censored.audio, colored.audio)
        assertEquals(0.3, colored.video[0].content.color!!.warmth!!, 0.0)
        assertNull(colored.video[0].content.audio)
        // A level, a mark and a word changed on the clip go to the sound and to the timeline.
        val edited = view.toMutableList()
        edited[0] = edited[0].copy(
            audio = edited[0].audio!!.copy(volumeDB = 2.0, fadeOut = 1.0, denoise = 0.0),
            bookmarks = listOf(Bookmark(uuid(30), 1.0, "renamed"), Bookmark(uuid(62), 3.0)),
            subtitleWords = words.take(1),
        )
        val merged = TrackSequenceView.merging(censored, edited)
        val after = merged.audio[0]
        assertEquals(2.0, after.volumeDB, 0.0)
        assertEquals(censored.audio[0].fadeIn, after.fadeIn)
        assertEquals(AudioEdgeFade(1.0), after.fadeOut)
        assertTrue(after.enhancements.isEmpty())
        assertEquals(censored.audio[0].censors, after.censors)
        assertEquals(words.take(1), after.subtitleWords)
        assertEquals(listOf(uuid(30) to "renamed", uuid(62) to ""), merged.bookmarks.map { it.id to it.label })
        assertEquals(edited, TrackSequenceView.clips(merged))
        assertNull(TrackInvariants.violation(merged, sources))
    }

    // MARK: Today's functions through the track model

    @Test
    fun aRecipeEditThroughTheViewIsTheEditOfToday() {
        val color = ColorSettings(id = uuid(63), exposure = -0.4)
        assertEquals(TimelineEditing.setColor(state, 2, color), done(TrackSequenceView.editingClips(state) { TimelineEditing.setColor(it, 2, color) }))
        assertNull(TrackSequenceView.editingClips(state) { TimelineEditing.setColor(it, 9, color) })
        val title = TextOverlay(id = uuid(64), text = "Title", start = 1.0, end = 2.0)
        assertEquals(TimelineEditing.setText(state, 0, title), done(TrackSequenceView.editingClips(state) { TimelineEditing.setText(it, 0, title) }))
    }

    @Test
    fun aMoveKeepsTheIdsAndTakesTheClipsSoundAndMarksAlong() {
        val moved = done(TrackSequenceView.move(state, 0, 2))
        assertEquals(listOf(clips[1].id, clips[2].id, clips[0].id), moved.clips.map { it.id })
        assertEquals(state.clips[0].copy(transition = null, transitionFit = null), moved.clips[2])
        // The transition between the second and the third clip is still theirs.
        assertEquals(uuid(32), moved.clips[0].transition?.id)
        assertEquals(TimelineEditing.move(state, 0, 2)!!.clips.map { it.id }, moved.clips.map { it.id })
        assertNull(TrackSequenceView.move(state, 1, 1))
    }

    @Test
    fun aJoinIsFoundByItsTwoClips() {
        val settings = TransitionSettings(id = uuid(65), duration = 0.5)
        val today = TimelineEditing.setJoin(state, 1, settings)!!
        val through = done(TrackSequenceView.setJoin(state, 1, settings))
        assertEquals(today.clips.map { it.transition?.id }, through.clips.map { it.transition?.id })
        assertEquals(today.duration, through.duration, 2 * TransitionLayout.TICK)
        val removed = done(TrackSequenceView.setJoin(state, 2, null))
        assertEquals(12.0, removed.duration, TransitionLayout.TICK)
        assertNull(TrackSequenceView.setJoin(state, 1, null))
        assertNull(TrackSequenceView.setJoin(state, 99, settings))
        assertEquals(clips[1].id to clips[2].id, TrackSequenceView.joinClips(state.clips, 2))
        assertEquals(null to clips[1].id, TrackSequenceView.joinClips(state.clips, 4))
        assertEquals(clips[0].id to null, TrackSequenceView.joinClips(state.clips, 6))
    }

    @Test
    fun unlinkingAndLinkingAgainThroughTheTrackModel() {
        val unlinked = done(TrackSequenceView.unlink(state, 1))
        assertEquals(ClipAudio(muted = true), unlinked.clips[1].audio)
        assertEquals(clips[1].id, unlinked.audio!!.first { it.sourceID == source.id }.id)
        assertEquals(0.0, TrackSequenceView.syncOffset(unlinked, 1)!!, 0.0)
        assertNull(TrackSequenceView.syncOffset(state, 1))
        val relinked = done(TrackSequenceView.relink(unlinked, 1))
        assertEquals(state, relinked)
        assertNull(TrackSequenceView.relink(state, 1))
    }

    @Test
    fun aRangeMarkedOnACutThatIsATickAwayOnTheTimelineCutsNothingThere() {
        // Each clip is 120000.504 ticks long and is kept as 120001: the second one ends on tick 240002, where its end in seconds is tick 240001.
        val length = 0.5000021
        val close = TransitionSettings(id = uuid(60), style = TransitionStyle.FadeColor, duration = 0.2)
        val edit = TimelineEditing.setJoin(EditState(listOf(TimelineClip(uuid(61), source.id, 0.0, length), TimelineClip(uuid(62), source.id, 1.0, 1.0 + length))), 2, close)!!
        val start = TimelineEditing.clipStart(edit.clips, 1)
        val range = EditScope.Range(TimeRange(start, start + edit.clips[1].duration))
        val converted = edit.toTimeline()
        assertEquals(1L, converted.video[1].end.ticks - t(range.range.end).ticks)
        assertEquals(converted.video[1].end, TrackSequenceView.onTimeline(converted, range.range.end))
        // Three ticks away is another place.
        assertEquals(t(0.25), TrackSequenceView.onTimeline(converted, 0.25))
        assertEquals(EditTime(converted.video[1].end.ticks - 3), TrackSequenceView.onTimeline(converted, (converted.video[1].end.ticks - 3).toDouble() / EditTime.SCALE))

        val scoped = TrackSequenceView.scoped(edit, range)
        assertEquals(listOf(uuid(62)), scoped.clips)
        assertEquals(2, done(scoped.result).clips.size)
        // The whole clip is copied, with the close that is on its end.
        val copied = TrackSequenceView.copy(edit, range)!!
        assertEquals(converted.video[1].mapping.duration, copied.clipboard.timeline.video.single().mapping.duration)
        assertEquals(close.id, done(copied.fragment).clips.single().transition?.id)
        // And deleted whole, leaving no piece of it.
        assertEquals(listOf(uuid(61)), done(TrackSequenceView.delete(edit, range)!!.result).clips.map { it.id })
    }

    @Test
    fun aRangeDeletedThroughTheTrackModelCarriesThePlayhead() {
        val removed = TrackSequenceView.delete(state, EditScope.Range(TimeRange(1.0, 3.0)), playhead = 5.0)!!
        val back = done(removed.result)
        assertEquals(3.0, removed.position!!, 0.0)
        assertEquals(state.duration - 2.0, back.duration, TransitionLayout.TICK)
        assertNull(TrackSequenceView.delete(state, EditScope.Range(TimeRange(1.0, 1.01))))
        val copied = TrackSequenceView.copy(state, EditScope.Selected(EditTargets(clips = setOf(clips[1].id))))!!
        assertEquals(listOf(clips[1].id), copied.fragment.state?.clips?.map { it.id })
        val pasted = TrackSequenceView.paste(state, copied.clipboard, 0.0)!!
        assertEquals(4, done(pasted.result).clips.size)
        assertEquals(0.0, pasted.start!!, 0.0)
    }
}
