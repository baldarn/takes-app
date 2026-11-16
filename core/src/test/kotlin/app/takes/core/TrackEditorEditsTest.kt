package app.takes.core

import app.takes.core.TimelinePasteTarget.Anchor
import app.takes.core.TimelinePasteTarget.Kind
import app.takes.core.TimelinePasteTarget.Label
import app.takes.core.TrackFixtures.uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Paste targets, adding video in a gap and bookmarks anywhere, on the edit
 * of a project (docs/TRACKS-PLAN.md, S12): what the editor asks for at the
 * playhead, the edits it commits, and the check of
 * `Tests/TimelineClipboardChecks.swift:66-72` (Undo of Cut and Paste across a
 * saved project) that waited for a project holding a timeline. The rest of
 * that file is in [TrackClipboardTest], on the timeline itself.
 */
class TrackEditorEditsTest {
    private fun t(seconds: Double) = EditTime.of(seconds)

    private val source = SourceMedia(id = uuid(1), filename = "clipboard.mov", duration = 10.0)
    private val sources = listOf(source)
    private val clips = (0 until 5).map { TimelineClip(uuid(10 + it), source.id, it * 2.0, it * 2.0 + 2) }
    private val state = EditState(clips)
    private val plain = state.toTimeline()

    private fun settled(edit: EditState) {
        val timeline = edit.toTimeline()
        assertNull(TrackInvariants.violation(timeline, sources))
        assertEquals(timeline, timeline.pruned())
    }

    private fun project(timeline: TrackTimeline) =
        Project(title = "Clipboard history", sources = sources, snapshots = listOf(EditSnapshot.of(timeline, 0)))

    private fun reopened(project: Project): Project {
        val files = HashMap<String, ByteArray>()
        val manifest = project.manifest { name, data -> files[name] = data }
        return Project.readManifest(manifest) { files.getValue(it) }.also { it.validate() }
    }

    // MARK: Tests/TimelineClipboardChecks.swift:66-72

    /** "Undo Paste", "Undo Cut restores the source and its edits after reopening", "Redo Cut and Paste". */
    @Test
    fun cutAndPasteAreUndoneAndRedoneAfterReopening() {
        val base = plain.addingBookmark(t(8.75)).let { marked ->
            marked.copy(video = marked.video.dropLast(1) + marked.video[4].let { it.copy(content = it.content.copy(texts = listOf(TextOverlay(id = uuid(30), text = "Last scene", start = 8.0, end = 9.0)))) })
        }
        var history = project(plain).commit(base.toEdit())
        val cut = TrackEditorEdits.cutting(history.edit, base.entireSelection(EditInterval.of(1.0, 3.0)), 8.0)!!
        assertEquals(6.0, cut.position, 0.0)
        history = history.commit(cut.edit)
        val pasted = TrackEditorEdits.pasting(history.edit, cut.clipboard, 0.5)!!
        assertEquals(0.5, pasted.position, 0.0)
        history = reopened(history.commit(pasted.edit))
        val range = pasted.edit.toTimeline()
        assertEquals(range, history.edit.toTimeline())
        history = history.undo()
        assertEquals("Undo Paste", t(8.0), history.edit.toTimeline().duration)
        history = history.undo()
        assertEquals("Undo Cut restores the source and its edits after reopening", base, history.edit.toTimeline())
        history = history.redo().redo()
        assertEquals("Redo Cut and Paste", range, history.edit.toTimeline())
    }

    // MARK: Paste at the playhead

    @Test
    fun theTargetsAtThePlayheadAreNamedByKeys() {
        val one = TrackEditorEdits.pasteTargets(state)
        assertEquals(TrackClipboard.pasteTargets(plain), one)
        // On the cut at 2 there is one target, the picture's.
        val clip = plain.copied(plain.selection(clips[4].id))
        val atCut = TrackEditorEdits.pasteChoices(state, clip, 2.0)
        assertEquals(listOf(Anchor.Between(clips[0].id, clips[1].id)), atCut.targets.map { it.anchor })
        assertEquals(Label(Label.Key.Video, 1, 2), atCut.targets[0].labelKey(plain))
        assertEquals("Video · V1 | V2", atCut.targets[0].label(plain))
        // A frame later on the display clock it is still on the cut; between frames, on none: the playhead pastes.
        assertEquals(1, TrackEditorEdits.pasteChoices(state, clip, 2.0 + 0.00009).targets.size)
        assertEquals(emptyList<TimelinePasteTarget>(), TrackEditorEdits.pasteChoices(state, clip, 3.0).targets)
        assertEquals(listOf(Label(Label.Key.Start)), TrackEditorEdits.pasteChoices(state, clip, 0.0).targets.map { it.labelKey(plain) })
        assertEquals(listOf(Label(Label.Key.End)), TrackEditorEdits.pasteChoices(state, clip, 10.0).targets.map { it.labelKey(plain) })
        // Unlinked, a cut of the picture and of the sound on one tick are two targets: the menu.
        val detached = plain.unlinked(plain.entireSelection())
        val two = TrackEditorEdits.pasteChoices(detached.toEdit(), clip, 4.0).targets
        assertEquals(listOf(Kind.Picture, Kind.Sound), two.map { it.kind })
        assertEquals(listOf(Label(Label.Key.Video, 2, 3), Label(Label.Key.Audio, 2, 3)), two.map { it.labelKey(detached) })
        // A score going on after the picture: the picture's end and the project's end.
        val score = AudioTimelineItem(uuid(40), uuid(41), source.id, t(4.0), SourcePlaybackMap.single(t(10.0), 0.0, 10.0))
        val scored = plain.copy(audio = plain.audio + score)
        val ends = TrackClipboard.pasteTargets(scored).filter { it.anchor == Anchor.End }
        assertEquals(listOf(Kind.Picture to t(10.0), Kind.Project to t(14.0)), ends.map { it.kind to it.time })
        assertEquals(listOf(Label(Label.Key.End)), TrackClipboard.targetsAt(scored, t(14.0)).map { it.labelKey(scored) })
        // With no picture, the edges are the sound's.
        val sound = TrackTimeline(audio = listOf(score.copy(start = t(1.0))))
        assertEquals(listOf(Kind.Sound to Anchor.Start, Kind.Sound to Anchor.End), TrackClipboard.pasteTargets(sound).map { it.kind to it.anchor })
    }

    @Test
    fun pasteSaysWhenItReplacesATransition() {
        val dissolve = TransitionSettings(id = uuid(50), duration = 0.5)
        val blend = TimelineEditing.setJoin(state, 2, dissolve)!!
        val timeline = blend.toTimeline()
        val join = TrackLayout.of(timeline).joins.first { it.transition != null }
        val scene = plain.copied(plain.selection(clips[4].id))
        val choices = TrackEditorEdits.pasteChoices(blend, scene, join.time)
        assertEquals(dissolve.id, choices.targets.single().transition)
        assertTrue(choices.replacesTransition)
        // Inside the dissolve, off its join: no target, and a scene opens time there.
        assertTrue(TrackEditorEdits.pasteChoices(blend, scene, join.time - 0.1).let { it.targets.isEmpty() && it.replacesTransition })
        // A sound alone is laid over: nothing is replaced.
        val detached = plain.unlinked(plain.entireSelection())
        val sound = detached.copied(detached.selection(detached.audio[4].id))
        assertFalse(TrackEditorEdits.pasteChoices(blend, sound, join.time).replacesTransition)
        // Away from the dissolve, nothing either.
        assertFalse(TrackEditorEdits.pasteChoices(blend, scene, 8.0).replacesTransition)
        val made = TrackEditorEdits.pasting(blend, scene, join.time, choices.targets.single())!!
        assertTrue(made.edit.toTimeline().transitions.isEmpty())
        assertEquals(t(12.0), made.edit.toTimeline().duration)
        settled(made.edit)
    }

    /**
     * YouRec/TimelinePaste.swift:138-142: with no target, a picture whose
     * sound was unlinked goes in at the playhead on a layer in front of
     * everything, and nothing moves.
     */
    @Test
    fun anUnlinkedPictureWithNoTargetIsLaidOverAtThePlayheadInFront() {
        val detached = plain.unlinked(plain.entireSelection())
        val picture = detached.copied(TimelineSelection(video = setOf(clips[4].id)))
        assertFalse(picture.insertsTime)
        val made = TrackEditorEdits.pasting(detached.toEdit(), picture, 3.0)!!
        val result = made.edit.toTimeline()
        assertFalse(made.edit.isSequence)
        assertEquals(3.0, made.position, 0.0)
        val added = result.video.single { it.id in made.selection.video }
        assertEquals(t(3.0), added.start)
        assertTrue(result.video.all { it.id == added.id || it.layer < added.layer })
        assertEquals(detached.video.map { it.interval }, result.video.filter { it.id != added.id }.map { it.interval })
        assertEquals(detached.audio, result.audio)
        assertEquals(detached.duration, result.duration)
        settled(made.edit)
        // Kept inside the timeline: at a playhead past the end it goes at the end.
        assertEquals(10.0, TrackEditorEdits.pasting(detached.toEdit(), picture, 30.0)!!.position, 0.0)
        assertNull(TrackEditorEdits.pasting(state, TimelineClipboard(TrackTimeline(), false), 1.0))
    }

    // MARK: Adding video in a gap

    /** Picture from 0 to 4, nothing from 4 to 8, picture from 8 to 14. */
    private val gapped = plain.moved(plain.selection(setOf(clips[2].id, clips[3].id, clips[4].id)), t(4.0))

    @Test
    fun aGapHasItsOwnAddButtonAndPasteTarget() {
        assertEquals(listOf(VideoInsertionSite.Start, VideoInsertionSite.End), InsertionSites.sites(state))
        assertEquals(listOf(VideoInsertionSite.Start), InsertionSites.sites(EditState(emptyList())))
        val edit = gapped.toEdit()
        assertFalse(edit.isSequence)
        assertEquals(listOf(VideoInsertionSite.Start, VideoInsertionSite.Gap(4.0), VideoInsertionSite.End), InsertionSites.sites(edit))
        assertEquals(4.0, InsertionSites.time(edit, 0.0, VideoInsertionSite.Gap(4.0))!!, 0.0)
        assertEquals(14.0, InsertionSites.time(edit, 0.0, VideoInsertionSite.End)!!, 0.0)
        assertEquals(0.0, InsertionSites.time(edit, 7.0, VideoInsertionSite.Start)!!, 0.0)
        assertEquals(listOf(InsertionSites.Span(0.0, 4.0), InsertionSites.Span(8.0, 14.0)), InsertionSites.coverage(edit))
        // The add buttons' paste targets.
        assertEquals(TimelinePasteTarget(Kind.Picture, t(4.0), Anchor.Gap), TrackEditorEdits.pasteTarget(edit, VideoInsertionSite.Gap(4.0)))
        assertEquals(TimelinePasteTarget(Kind.Picture, EditTime.ZERO, Anchor.Start), TrackEditorEdits.pasteTarget(edit, VideoInsertionSite.Start))
        assertEquals(TimelinePasteTarget(Kind.Picture, t(14.0), Anchor.End), TrackEditorEdits.pasteTarget(edit, VideoInsertionSite.End))
        assertNull(TrackEditorEdits.pasteTarget(EditState(emptyList()), VideoInsertionSite.Start))
        // A two-second scene pasted in the gap fills it from its left edge; nothing moves.
        val scene = plain.copied(plain.selection(clips[0].id))
        val fits = TrackEditorEdits.pasting(edit, scene, 0.0, TrackEditorEdits.pasteTarget(edit, VideoInsertionSite.Gap(4.0)))!!
        assertEquals(4.0, fits.position, 0.0)
        assertEquals(gapped.video.map { it.start }, fits.edit.toTimeline().video.filter { it.id !in fits.selection.video }.map { it.start })
        settled(fits.edit)
        // Six seconds: the picture after the gap moves by what does not fit.
        val long = plain.copied(plain.selection(setOf(clips[0].id, clips[1].id, clips[2].id)))
        val over = TrackEditorEdits.pasting(edit, long, 0.0, TrackEditorEdits.pasteTarget(edit, VideoInsertionSite.Gap(4.0)))!!
        assertEquals(listOf(0.0, 2.0, 4.0, 6.0, 8.0, 10.0, 12.0, 14.0).map(::t), over.edit.toTimeline().video.map { it.start }.sorted())
        settled(over.edit)
    }

    @Test
    fun footageAddedInAGapUsesTheFreeTimeAndGetsIdsOfItsOwn() {
        val edit = gapped.toEdit()
        val take = SourceMedia(id = uuid(60), filename = "take.mp4", duration = 3.0)
        val all = sources + take
        val clip = TimelineClip(uuid(61), take.id, 0.0, 3.0)
        val made = TrackEditorEdits.insertingFootage(edit, listOf(clip), 0.0, VideoInsertionSite.Gap(4.0))!!
        val result = made.edit.toTimeline()
        assertEquals(4.0, made.position, 0.0)
        val picture = result.video.single { it.id in made.selection.video }
        assertTrue("insert-new-identity: the clip is a copy with an id of its own", picture.id != clip.id)
        assertEquals(take.id, picture.content.sourceID)
        assertEquals(1, made.selection.audio.size)
        assertEquals(t(4.0), picture.start)
        assertTrue(result.video.all { it.id == picture.id || it.layer < picture.layer })
        assertEquals(gapped.video.map { it.interval }, result.video.filter { it.id != picture.id }.map { it.interval })
        assertNull(TrackInvariants.violation(result, all))
        // Five seconds in a gap of four: the picture after it moves by one.
        val longer = TrackEditorEdits.insertingFootage(edit, listOf(clip, TimelineClip(uuid(62), take.id, 0.0, 2.0)), 0.0, VideoInsertionSite.Gap(4.0))!!
        assertEquals(t(9.0), longer.edit.toTimeline().video.filter { it.id !in longer.selection.video }.map { it.start }.sorted()[2])
        // A photo has no sound; at the end it goes after the last picture.
        val photo = TrackEditorEdits.insertingFootage(edit, listOf(clip), 0.0, VideoInsertionSite.End, pictureOnlySources = setOf(take.id))!!
        assertEquals(14.0, photo.position, 0.0)
        assertTrue(photo.selection.audio.isEmpty())
        // At the playhead, without a site, time is opened there.
        val atPlayhead = TrackEditorEdits.insertingFootage(state, listOf(clip), 3.0)!!
        assertEquals(3.0, atPlayhead.position, 0.0)
        assertEquals(t(13.0), atPlayhead.edit.toTimeline().duration)
        assertNull(TrackEditorEdits.insertingFootage(state, emptyList(), 3.0))
    }

    // MARK: Bookmarks anywhere

    @Test
    fun aBookmarkGoesOnPictureOnASoundOrOnTheTimelineItself() {
        val edit = gapped.toEdit()
        // On a picture: its footage.
        val onPicture = TrackEditorEdits.addingBookmark(edit, 1.0)!!
        assertTrue(onPicture.toTimeline().bookmarks.single().anchor is BookmarkAnchor.Source)
        // In empty time: the timeline itself, kept at its time.
        val empty = TrackEditorEdits.addingBookmark(edit, 6.0, label = "Gap")!!
        val mark = empty.toTimeline().bookmarks.single()
        assertEquals(BookmarkAnchor.Project(t(6.0)), mark.anchor)
        assertEquals("Gap", mark.label)
        assertEquals(listOf(t(6.0)), TrackEditorEdits.bookmarks(empty).map { it.time })
        // On a sound: chosen, or the only thing playing there.
        val detached = plain.unlinked(TimelineSelection(video = setOf(clips[1].id), audio = setOf(plain.audio[1].id)))
        val sound = detached.audio[1]
        val onSound = TrackEditorEdits.addingBookmark(detached.toEdit(), 3.0, preferred = sound.id)!!.toTimeline().bookmarks.single()
        assertEquals(BookmarkAnchor.Source(sound.occurrenceID, sound.sourceID, 3.0), onSound.anchor)
        val unlinked = plain.unlinked(plain.selection(clips[4].id))
        val past = unlinked.copy(video = unlinked.video.filter { it.id != clips[4].id })
        val alone = TrackEditorEdits.addingBookmark(past.toEdit(), 9.0)!!.toTimeline()
        assertEquals(past.audio.first { it.start == t(8.0) }.occurrenceID, (alone.bookmarks.single().anchor as BookmarkAnchor.Source).occurrence)
        // bookmark-at-the-last-second: at the very end it marks the timeline, a mark a sequence has no place for.
        val last = TrackEditorEdits.addingBookmark(state, 10.0)!!
        assertEquals(BookmarkAnchor.Project(t(10.0)), last.toTimeline().bookmarks.single().anchor)
        assertFalse(last.isSequence)
        assertEquals(listOf(10.0), TrackEditState.bookmarkTimes(last))
        // Renamed, cut to the editor's length, and removed.
        val id = mark.id
        assertEquals("x".repeat(TrackBookmarks.EDITOR_LABEL), TrackEditorEdits.renamingBookmark(empty, id, "x".repeat(500))!!.toTimeline().bookmarks.single().label)
        assertNull(TrackEditorEdits.renamingBookmark(empty, "other", "y"))
        val removed = TrackEditorEdits.removingBookmark(empty, id)!!
        assertTrue(removed.toTimeline().bookmarks.isEmpty())
        assertNull(TrackEditorEdits.removingBookmark(removed, id))
        assertNull(TrackEditorEdits.addingBookmark(state, Double.NaN))
        // The step with a mark in empty time is saved and read back.
        val saved = reopened(project(gapped).commit(empty))
        assertEquals(empty.toTimeline(), saved.edit.toTimeline())
        assertNotNull(saved.edit.layered)
    }
}
