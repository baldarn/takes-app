package app.takes.core

import app.takes.core.TrackFixtures.uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A project whose edit is a track timeline that no sequence can be
 * ([TrackEditState]): made by Move, committed as one Undo step, undone,
 * redone, saved, reopened, archived, restored and copied; a gap made and
 * closed; and every editing function of the sequence form on it.
 */
class TrackEditStateTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun t(seconds: Double) = EditTime.of(seconds)
    private fun timeline(clips: List<TimelineClip>) = EditState(clips).toTimeline()

    private val source = SourceMedia(id = uuid(6), filename = "multi.mp4", duration = 12.0)
    private val clips = (0 until 3).map { TimelineClip(uuid(40 + it), source.id, it * 4.0, (it + 1) * 4.0) }
    private val original = timeline(clips)

    private fun project(base: TrackTimeline) =
        Project(title = "Overlap regression", sources = listOf(source), snapshots = listOf(EditSnapshot.of(base, 0)))

    private fun reopened(project: Project): Project {
        val files = HashMap<String, ByteArray>()
        val manifest = project.manifest { name, data -> files[name] = data }
        return Project.readManifest(manifest) { files.getValue(it) }
    }

    /** The first clip dragged two seconds later, over the second: an overlap, committed. */
    private fun overlapped(): Pair<Project, TrackTimeline> {
        val base = project(original)
        val session = TrackMoveSession.begin(base.edit.toTimeline(), original.selection(clips[0].id), 0.0, 60.0)!!.dragged(2.0, snapping = false)
        val outcome = session.done(base.edit.toTimeline())
        val moved = (outcome as TrackMoveSession.Outcome.Moved).timeline
        return base.commit(outcome) to moved
    }

    private fun layered(): EditState = overlapped().first.edit.also { check(!it.isSequence) }

    /** Where each picture of [state] is, by id. */
    private fun places(state: EditState): Map<String, EditInterval> = state.toTimeline().video.associate { it.id to it.interval }

    // MARK: Tests/TrackTimelineTests.swift:601-610

    /**
     * Tests/TrackTimelineTests.swift:574-610: seven selections, linked and
     * unlinked, moved by twelve offsets, each committed into a project from
     * the timeline before: saved and reopened it is the moved timeline, with
     * every overlap and identity; Undo gives the exact arrangement before and
     * Redo the moved one again.
     */
    @Test
    fun aMoveCommittedSurvivesReopeningAndUndoAndRedo() {
        var count = 0
        var overlaps = 0
        for (detached in listOf(false, true)) {
            val base = if (detached) original.unlinked(original.entireSelection()) else original
            val selections = listOf(
                base.selection(clips[0].id), base.selection(clips[1].id), base.selection(clips[2].id),
                TimelineSelection(audio = setOf(base.audio[1].id)),
                base.selection(setOf(clips[0].id, clips[2].id)), base.entireSelection(),
                base.selection(clips[1].id, range = EditInterval.of(5.0, 7.0)),
            )
            for (selection in selections) {
                val scoped = base.scoped(selection)
                for (offset in listOf(-30.0, -8.0, -4.0, -2.0, -0.1, 0.0, 0.1, 2.0, 4.0, 6.0, 8.0, 12.0)) {
                    val moved = scoped.timeline.moved(scoped.selection, t(offset))
                    val committed = project(base).commit(moved)
                    val reopened = reopened(committed)
                    assertEquals("Overlap and item identities survive project reopening", moved, reopened.edit.toTimeline())
                    if (!reopened.edit.isSequence) overlaps++
                    if (reopened.canUndo) {
                        val undone = reopened.undo()
                        assertEquals("Undo restores the exact prior arrangement", base, undone.edit.toTimeline())
                        assertEquals("Redo restores all overlapping tracks", moved, undone.redo().edit.toTimeline())
                    }
                    count++
                }
            }
        }
        assertEquals(168, count)
        assertTrue(overlaps > 0)
    }

    @Test
    fun aMoveIsOneUndoStepAndNothingElse() {
        val base = project(original)
        val (moved, timeline) = overlapped()
        assertEquals(2, moved.snapshots.size)
        assertEquals(1, moved.historyCursor)
        assertFalse(moved.edit.isSequence)
        assertEquals(timeline, moved.edit.layered)
        assertEquals(timeline, moved.snapshots.last().timeline)
        assertEquals(TrackTimeline.LAYERED_SCHEMA, moved.schemaVersion)
        // Saved as the timeline alone, as every step since S3b.
        assertTrue(moved.snapshots.last().data().decodeToString().startsWith("{\"state\":{\"timeline\":"))
        // Unchanged and Stale commit nothing; the same timeline again is no step.
        assertSame(base, base.commit(TrackMoveSession.Outcome.Unchanged))
        assertSame(base, base.commit(TrackMoveSession.Outcome.Stale))
        assertSame(moved, moved.commit(timeline))
        assertEquals(original, moved.undo().edit.toTimeline())
        assertTrue(moved.undo().edit.isSequence)
    }

    @Test
    fun anOverlapIsArchivedRestoredAndCopied() {
        val root = File(temporary.root, "Projects")
        val disk = ProjectDisk(root)
        val (moved, timeline) = overlapped()
        disk.save(moved)
        val folder = disk.folder(moved.id)
        File(folder, source.filename).writeBytes("media".toByteArray())
        val zip = ByteArrayOutputStream()
        ProjectArchive.write(folder, moved, zip)
        val restored = ProjectRestore.restore(ByteArrayInputStream(zip.toByteArray()), root, Long.MAX_VALUE)
        val copy = ProjectDuplicate.duplicate(moved, root, "Copy")
        for (other in listOf(disk.read(folder), restored, copy, disk.read(disk.folder(restored.id)), disk.read(disk.folder(copy.id)))) {
            assertEquals(moved.snapshots, other.snapshots)
            assertEquals(moved.history, other.history)
            assertEquals(timeline, other.edit.toTimeline())
            assertEquals(original, other.undo().edit.toTimeline())
            assertEquals(moved.snapshots.map { it.data().toList() }, other.snapshots.map { it.data().toList() })
        }
    }

    @Test
    fun aGapMadeByMoveIsClosedAsOneEdit() {
        val base = project(original)
        val session = TrackMoveSession.begin(base.edit.toTimeline(), original.selection(clips[1].id), 0.0, 60.0)!!.dragged(8.0, snapping = false)
        val gapped = base.commit(session.done(base.edit.toTimeline()))
        val edit = gapped.edit
        assertFalse(edit.isSequence)
        assertEquals(listOf(TimeRange(4.0, 8.0)), TrackEditState.gaps(edit))
        assertEquals(listOf(TimeRange(4.0, 8.0)), TrackEditState.pictureGaps(edit))
        assertNull(TrackEditState.closingGap(edit, 2.0))
        val closed = TrackEditState.closingGap(edit, 5.0)!!
        assertEquals(emptyList<TimeRange>(), TrackEditState.gaps(closed))
        assertEquals(listOf(0.0, 4.0, 8.0), closed.toTimeline().chronologicalVideo.map { it.start.seconds })
        val project = gapped.commit(closed)
        assertEquals(3, project.snapshots.size)
        assertEquals(closed.toTimeline(), reopened(project).edit.toTimeline())
        assertEquals(listOf(TimeRange(4.0, 8.0)), TrackEditState.gaps(project.undo().edit))
        // A sequence has no gap to close.
        assertEquals(emptyList<TimeRange>(), TrackEditState.gaps(base.edit))
        assertNull(TrackEditState.closingGap(base.edit, 5.0))
    }

    // MARK: Back to a sequence

    /** Move [id] of [project]'s edit by [seconds], unsnapped, committed. */
    private fun moving(project: Project, id: String, seconds: Double): Project {
        val timeline = project.edit.toTimeline()
        val session = TrackMoveSession.begin(timeline, timeline.selection(id), 0.0, 60.0)!!.dragged(seconds, snapping = false)
        return project.commit(session.done(timeline))
    }

    /**
     * The original keeps a picture's layer when its overlap is gone
     * (YouRec/TrackTimeline.swift:728-739 raises it only for a new overlap,
     * and `pruneRelations` never touches layers): a layer says nothing where
     * the picture plays over nothing. A Move over the next picture and back
     * leaves pictures edge to edge again, which is a sequence, for every tool
     * and on disk, with Undo and Redo across.
     */
    @Test
    fun aMoveOverAndBackIsASequenceAgain() {
        val over = moving(project(original), clips[0].id, 2.0)
        assertFalse(over.edit.isSequence)
        assertTrue(over.edit.toTimeline().video.single { it.id == clips[0].id }.layer >= clips.size)
        val back = moving(over, clips[0].id, -2.0)
        assertEquals(3, back.snapshots.size)
        assertTrue(back.edit.isSequence)
        assertEquals(clips.map { it.id }, back.edit.clips.map { it.id })
        assertEquals(EditState(clips).clips, back.edit.clips)
        assertNull(TrackEditState.unavailable(back.edit, SequenceOnly.Reorder))
        assertNull(TrackEditState.unavailable(back.edit, SequenceOnly.Transition))
        assertNotNull(TimelineEditing.move(back.edit, 0, 2))
        assertFalse(back.undo().edit.isSequence)
        assertTrue(back.undo().undo().edit.isSequence)
        assertTrue(back.undo().redo().edit.isSequence)
        assertEquals(back.edit, back.undo().redo().edit)
        assertTrue(reopened(back).edit.isSequence)
        assertTrue(reopened(back).undo().redo().edit.isSequence)
        assertFalse(reopened(back).undo().edit.isSequence)
    }

    /** A picture moved out past the end and the gap it left closed: the pictures in a new order, edge to edge, a sequence. */
    @Test
    fun aMoveOutAndCloseGapIsASequence() {
        val out = moving(project(original), clips[1].id, 8.0)
        assertFalse(out.edit.isSequence)
        val closed = out.commit(TrackEditState.closingGap(out.edit, 5.0)!!)
        assertEquals(3, closed.snapshots.size)
        assertTrue(closed.edit.isSequence)
        assertEquals(listOf(clips[0].id, clips[2].id, clips[1].id), closed.edit.clips.map { it.id })
        assertNull(TrackEditState.unavailable(closed.edit, SequenceOnly.Reorder))
        assertFalse(closed.undo().edit.isSequence)
        assertTrue(closed.undo().undo().edit.isSequence)
        assertTrue(closed.undo().redo().edit.isSequence)
        assertTrue(reopened(closed).edit.isSequence)
        assertFalse(reopened(closed).undo().edit.isSequence)
        // Over the next picture first, then out, then the gap closed: the layer it took is forgotten too.
        val over = moving(project(original), clips[0].id, 2.0)
        val past = moving(over, clips[0].id, 10.0)
        val again = past.commit(TrackEditState.closingGap(past.edit, 2.0)!!)
        assertTrue(again.edit.isSequence)
        assertEquals(listOf(clips[1].id, clips[2].id, clips[0].id), again.edit.clips.map { it.id })
        assertTrue(again.undo().redo().edit.isSequence)
    }

    // MARK: Schema

    /**
     * A step that is no sequence takes schema 18, which release 0.10 (it
     * reads 17, timelines that are sequences only) refuses as "saved by a
     * newer version" instead of opening an older revision in its place
     * (docs/TRACKS-PLAN.md, 3.4). A step that is a sequence stays at 17, and
     * the project keeps the highest number of what it has held, as it does
     * for 17: its history still names the step that is no sequence.
     */
    @Test
    fun aStepThatIsNoSequenceTakesSchema18() {
        assertEquals(18, TrackTimeline.LAYERED_SCHEMA)
        assertEquals(18, Project.LATEST_READABLE_SCHEMA)
        val base = project(original)
        val sequence = base.commit(TimelineEditing.move(base.edit, 0, 2)!!)
        assertTrue(sequence.edit.isSequence)
        assertEquals(TrackTimeline.SCHEMA, sequence.schemaVersion)
        assertEquals(TrackTimeline.SCHEMA, sequence.snapshots.last().schema)
        val (moved, _) = overlapped()
        assertEquals(TrackTimeline.LAYERED_SCHEMA, moved.snapshots.last().schema)
        assertEquals(TrackTimeline.LAYERED_SCHEMA, moved.schemaVersion)
        assertEquals(TrackTimeline.LAYERED_SCHEMA, Project.schemaOf(moved.manifest { _, _ -> }))
        assertEquals(TrackTimeline.LAYERED_SCHEMA, reopened(moved).schemaVersion)
        // Back to a sequence: the step is 17, the project stays 18 while its history holds the overlap.
        val back = moving(moved, clips[0].id, -2.0)
        assertTrue(back.edit.isSequence)
        assertEquals(TrackTimeline.SCHEMA, back.snapshots.last().schema)
        assertEquals(TrackTimeline.LAYERED_SCHEMA, back.schemaVersion)
        // A gap is no sequence either; a take recorded at a place onto a layered edit is a layered step.
        val gapped = moving(base, clips[1].id, 8.0)
        assertEquals(TrackTimeline.LAYERED_SCHEMA, gapped.schemaVersion)
        val take = SourceMedia(id = uuid(7), filename = "later.mp4", duration = 3.0)
        assertEquals(TrackTimeline.SCHEMA, base.beginRecordingInsertion(1.0).addRecording(take).schemaVersion)
        val overInserted = moved.beginRecordingInsertion(1.0).addRecording(take)
        assertFalse(overInserted.edit.isSequence)
        assertEquals(TrackTimeline.LAYERED_SCHEMA, overInserted.snapshots[overInserted.historyCursor].schema)
        assertEquals(TrackTimeline.LAYERED_SCHEMA, overInserted.schemaVersion)
        // A reader of 17 is told the project is newer.
        val files = HashMap<String, ByteArray>()
        val manifest = moved.manifest { name, data -> files[name] = data }
        assertTrue(Project.isNewer(manifest.decodeToString().replaceFirst("\"schemaVersion\":18", "\"schemaVersion\":19").toByteArray()))
        assertFalse(Project.isNewer(manifest))
    }

    /**
     * Leaves, under `TAKES_LAYERED_OUT`, libraries for a core of release 0.10
     * to open: a project whose head is a Move over a picture (`overlap`), one
     * with a gap (`gap`), and one moved over and back, a sequence again with
     * the overlap in its history (`back`). Each has been saved twice, so its
     * kept revisions hold a manifest of 17 that 0.10 could open.
     */
    @Test
    fun writeLayeredProjectsForAnOlderCore() {
        val out = System.getenv("TAKES_LAYERED_OUT")?.let(::File) ?: return
        out.deleteRecursively()
        out.mkdirs()
        val base = project(original).let { it.commit(TimelineEditing.setColor(it.edit, 2, ColorSettings(exposure = 0.5))!!) }
        assertTrue(base.edit.isSequence)
        val overlap = moving(base, clips[0].id, 2.0)
        val projects = mapOf("overlap" to overlap, "gap" to moving(base, clips[1].id, 8.0), "back" to moving(overlap, clips[0].id, -2.0))
        for ((name, project) in projects) {
            val disk = ProjectDisk(File(out, name))
            disk.save(base)
            disk.save(project)
            File(disk.folder(project.id), source.filename).writeBytes("media".toByteArray())
            assertEquals(TrackTimeline.LAYERED_SCHEMA, Project.schemaOf(File(disk.folder(project.id), "project.json").readBytes()))
        }
    }

    @Test
    fun aTakeRecordedLaterGoesWherePicturesEndWithoutMovingAnything() {
        val (moved, timeline) = overlapped()
        val take = SourceMedia(id = uuid(7), filename = "later.mp4", duration = 3.0)
        val recorded = moved.addRecording(take)
        val edit = recorded.edit
        assertFalse(edit.isSequence)
        val placed = places(edit)
        for (picture in timeline.video) assertEquals(picture.interval, placed[picture.id])
        val added = edit.toTimeline().video.single { it.content.sourceID == take.id }
        assertEquals(timeline.video.maxOf { it.end }, added.start)
        assertEquals(t(3.0), added.mapping.duration)
        // Every step gains it, and Undo still gives the sequence before, with the take at its end.
        assertEquals(4, recorded.undo().edit.clips.size)
        assertTrue(recorded.undo().edit.isSequence)
        assertEquals(edit.toTimeline(), reopened(recorded).edit.toTimeline())
    }

    // MARK: Sequences are as they were

    @Test
    fun anEditThatIsASequenceIsSavedAsBefore() {
        val state = EditState(clips)
        assertTrue(state.isSequence)
        assertEquals(original, original.toEdit().toTimeline())
        assertFalse(documentJson.encodeToString(EditState.serializer(), state).contains("timeline"))
        // A layered edit says what it is when written, and reads back the same.
        val layered = layered()
        val text = documentJson.encodeToString(EditState.serializer(), layered)
        assertEquals(layered, documentJson.decodeFromString(EditState.serializer(), text))
        // A timeline that is a sequence is the sequence.
        assertTrue(original.toEdit().isSequence)
    }

    // MARK: Rendering

    @Test
    fun oneEntryGivesTheRenderPlanOfASequenceAndTheLayersOfATimeline() {
        val sequence = EditState(clips)
        assertEquals(EditRender.Sequence(TrackRenderPlan.of(sequence)), EditRender.of(sequence, limit = 1))
        val layered = layered()
        val drawn = EditRender.of(layered, limit = 4) as EditRender.Layers
        assertEquals(LayerPlan.planned(layered.toTimeline(), 4), drawn.plan)
        assertEquals(2, drawn.plan.lanes)
        assertTrue(EditRender.of(layered, limit = 1) is EditRender.TooManyLayers)
    }

    // MARK: Every function of the sequence form, off a sequence

    @Test
    fun timelineEditingOffASequence() {
        val state = layered()
        val before = places(state)
        val front = state.clips.indexOfFirst { it.id == clips[0].id }
        val back = state.clips.indexOfFirst { it.id == clips[1].id }

        // Split: every picture under the playhead, on the grid.
        val split = TimelineEditing.split(state, 3.0)!!
        assertEquals(4, split.toTimeline().video.size)
        assertNull(TimelineEditing.split(state, Double.NaN))
        // Delete: the picture with its sound, closing their time; what was under it shows again, untrimmed.
        val deleted = TimelineEditing.delete(state, front)!!
        assertTrue(deleted.clips.none { it.id == clips[0].id })
        val under = state.toTimeline().video.first { it.id == clips[1].id }
        val shown = deleted.toTimeline().video.first { it.id == clips[1].id }
        assertEquals(under.content, shown.content)
        assertEquals(under.mapping, shown.mapping)
        // Reordering and transitions are off.
        assertNull(TimelineEditing.move(state, 0, 1))
        assertNull(TimelineEditing.setJoin(state, 1, TransitionSettings()))
        assertNull(TimelineEditing.setTransition(state, 0, 1.0, TransitionStyle.entries.first()))
        assertEquals(SequenceOnly.Reorder, TrackEditState.unavailable(state, SequenceOnly.Reorder))
        assertNull(TrackEditState.unavailable(EditState(clips), SequenceOnly.Reorder))
        // Insert keeps the ids it is given and opens time.
        val take = TimelineClip(uuid(90), source.id, 0.0, 1.0)
        val inserted = TimelineEditing.insert(state, listOf(take), 1.0)!!
        assertEquals(t(1.0), places(inserted)[take.id]?.start)
        assertNull(TimelineEditing.insert(state, listOf(state.clips[0]), 1.0))
        // Trim and pauses on the picture.
        val trimmed = TimelineEditing.trim(state, back, 4.5, 8.0, source.duration)!!
        assertEquals(4.5, trimmed.clips.first { it.id == clips[1].id }.start, 1e-9)
        assertNotNull(TimelineEditing.removeIntervals(state, back, listOf(SourceInterval(5.0, 6.0))))
        // Recipes: titles, color, crop, effects, speed; the places of the others do not change.
        val titled = TimelineEditing.setText(state, back, TextOverlay(text = "Hi", start = 4.0, end = 5.0))!!
        assertEquals("Hi", titled.clips[back].texts?.single()?.text)
        assertEquals(before, places(titled))
        assertNull(TimelineEditing.removeText(state, back, "none"))
        assertEquals(null, TimelineEditing.removeText(titled, back, titled.clips[back].texts!!.single().id)!!.clips[back].texts)
        val colored = TimelineEditing.setColor(state, back, ColorSettings(exposure = 0.5))!!
        assertEquals(0.5, colored.toTimeline().video.first { it.id == clips[1].id }.content.color?.exposure)
        assertEquals(before, places(colored))
        assertNotNull(TimelineEditing.setCrop(state, back, CropSettings(zoom = 1.5)))
        val effected = VideoEffectEditing.setEffects(state, mapOf(clips[1].id to listOf(VideoEffectSettings(VideoEffectID.ColorCorrection))))!!
        assertEquals(1, effected.toTimeline().video.first { it.id == clips[1].id }.content.videoEffects?.size)
        val titledHere = TimelineEditing.placeTitle(state, TextOverlay(text = "Over"), 4.0..5.0, scope = setOf(clips[0].id))!!
        // The front picture starts at 2: seconds 4..5 of the movie are its source seconds 2..3.
        assertEquals(2.0, titledHere.clips[front].texts!!.single().start, 1e-9)
        val faster = TimelineEditing.setSpeed(state, back, 2.0)!!
        assertEquals(2.0, faster.clips.first { it.id == clips[1].id }.rate, 1e-9)
        // Bookmarks and the sound of a clip.
        assertEquals(1, TimelineEditing.toggleBookmark(state, back, 5.0)!!.toTimeline().bookmarks.size)
        val quiet = TimelineEditing.setAudio(state, back, ClipAudio(volumeDB = -6.0))!!
        assertEquals(-6.0, quiet.clips.first { it.id == clips[1].id }.audio?.volumeDB)
        // Sound of its own: add, move, trim, set, remove.
        val music = SourceMedia(id = uuid(8), filename = "music.m4a", duration = 6.0, audioOnly = true)
        val added = TimelineEditing.addAudio(state, music.id, music.duration, 1.0)!!
        val item = added.audio!!.single()
        assertEquals(1.0, item.start, 1e-9)
        assertEquals(3.0, TimelineEditing.moveAudio(added, item.id, 3.0)!!.audio!!.single().start, 1e-9)
        assertEquals(5.0, TimelineEditing.trimAudio(added, item.id, 5.0, leading = false)!!.audio!!.single().end, 1e-9)
        assertEquals(-3.0, TimelineEditing.setAudioItemSound(added, item.id, ClipAudio(volumeDB = -3.0))!!.audio!!.single().audio?.volumeDB)
        assertNull(TimelineEditing.removeAudio(added, item.id)!!.audio)
        // Where things are.
        assertEquals(2.0, TrackEditState.clipStart(state, front)!!, 1e-9)
        assertEquals(front, TrackEditState.locate(state, 5.0)?.index)
        assertEquals(back, TrackEditState.locate(state, 6.5)?.index)
    }

    @Test
    fun rangeEditingOffASequence() {
        val state = layered()
        assertEquals(1.0, RangeEditing.markTime(state, 1.01), 1e-9)
        assertEquals(2.0, RangeEditing.markTime(state, 2.05, pointsPerSecond = 60.0), 1e-9)
        // Delete a range from every lane; a selected picture with its sound.
        val removed = RangeEditing.delete(state, EditScope.Range(TimeRange(9.0, 10.0)), playhead = 9.5)!!
        assertEquals(t(11.0), removed.state.toTimeline().duration)
        assertEquals(9.0, removed.position!!, 1e-9)
        assertTrue(RangeEditing.delete(state, EditScope.Selected(EditTargets(clips = setOf(clips[0].id))))!!.state.clips.none { it.id == clips[0].id })
        assertNull(RangeEditing.delete(state, EditScope.Range(TimeRange(1.0, 1.01))))
        // The clipboard of clips is off.
        assertNull(RangeEditing.copy(state, EditScope.Whole))
        assertNull(RangeEditing.cut(state, EditScope.Whole))
        assertNull(RangeEditing.paste(state, RangeEditing.copy(EditState(clips), EditScope.Whole)!!, 1.0))
        // A tool's scope: cut at the ends of the range, wherever those fall.
        val scoped = RangeEditing.scoped(state, EditScope.Range(TimeRange(3.0, 5.0)))
        assertTrue(scoped.clips.isNotEmpty())
        assertSame(state, RangeEditing.scoped(state, EditScope.Whole).state)
        assertEquals(TimeRange(2.0, 6.0), RangeEditing.interval(state, null, clips[0].id))
        assertEquals(TimeRange(0.0, state.duration), RangeEditing.interval(state, null, null))
        // The marks keep what is there.
        val marks = RangeMarks(selected = EditTargets(clips = setOf(clips[0].id, "gone")))
        assertEquals(setOf(clips[0].id), marks.retained(state).selected.clips)
    }

    @Test
    fun subtitleEditingOffASequence() {
        val state = layered()
        val words = mapOf(source.id to (0 until 12).map { SubtitleWord(text = "w$it", start = it + 0.2, end = it + 0.8) })
        val captioned = SubtitleEditing.install(state, words, "en")
        assertFalse(captioned.isSequence)
        assertEquals("en", captioned.subtitles?.language)
        assertTrue(SubtitleEditing.groupingAvailable(captioned))
        val cues = SubtitleEditing.cues(captioned)
        assertTrue(cues.isNotEmpty())
        assertTrue(SubtitleEditing.transcript(captioned).contains("w0"))
        val restyled = SubtitleEditing.restyle(captioned, captioned.subtitles!!.copy(size = 0.06))!!
        assertEquals(captioned.subtitles!!.timingFingerprint, restyled.subtitles!!.timingFingerprint)
        assertNotNull(SubtitleEditing.regroup(captioned, captioned.subtitles!!.maxWords - 1))
        val corrected = SubtitleEditing.correct(captioned, cues.first(), "fixed")!!
        assertTrue(SubtitleEditing.transcript(corrected).startsWith("fixed"))
        assertTrue(SubtitleEditing.cues(SubtitleEditing.delete(captioned, cues.first())!!).size < cues.size)
        assertNull(SubtitleEditing.remove(captioned).subtitles)
    }

    @Test
    fun audioUnlinkingOffASequence() {
        val state = layered()
        val back = state.clips.indexOfFirst { it.id == clips[1].id }
        assertNull(AudioUnlinking.refusal(state, back))
        val unlinked = AudioUnlinking.unlink(state, back)!!
        assertEquals(AudioUnlinking.Refusal.AlreadyDetached, AudioUnlinking.refusal(unlinked, back))
        assertEquals(0.0, AudioUnlinking.syncOffset(unlinked, back)!!, 1e-9)
        assertNotNull(AudioUnlinking.relink(unlinked, back))
        assertNotNull(AudioUnlinking.setSound(unlinked, back, ClipAudio(volumeDB = -4.0)))
    }
}
