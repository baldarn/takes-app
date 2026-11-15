package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Delete, copy, cut and paste of a marked range or a selection, the marks
 * themselves, and the tool scope.
 *
 * Tests marked TRANSLATED carry over an expectation of the iPhone app's
 * tests, with the Swift file and line; the others are this port's own, for
 * the places where the two models differ.
 */
class RangeEditingTest {
    private fun clip(source: String, start: Double, end: Double) = TimelineClip(sourceID = source, start = start, end = end)
    private fun range(start: Double, end: Double) = EditScope.Range(TimeRange(start, end))
    private fun selected(vararg clips: TimelineClip) = EditScope.Selected(EditTargets(clips = clips.mapTo(HashSet()) { it.id }))
    private fun ranges(state: EditState) = state.clips.map { it.start to it.end }
    private fun join(next: TimelineClip, style: TransitionStyle, duration: Double) = TransitionSettings(nextClipID = next.id, style = style, duration = duration)

    /** Two four-second clips as TransitionTests.swift:9-13, with [settings] on the join between them. */
    private fun pair(settings: TransitionSettings): EditState {
        val second = clip("B", 0.0, 4.0)
        return EditState(listOf(clip("A", 0.0, 4.0).copy(transition = settings.copy(nextClipID = second.id)), second))
    }

    /** Five two-second scenes of one ten-second source, as TimelineClipboardChecks.swift:9-11. */
    private val source = SourceMedia(filename = "clipboard.mp4", duration = 10.0)
    private val scenes = (0 until 5).map { clip(source.id, it * 2.0, it * 2.0 + 2) }
    private val base = EditState(scenes)
    private fun order(state: EditState) = state.clips.map { (it.start / 2).toInt() }

    private val sounds = listOf(
        SourceMedia(id = "M", filename = "music.m4a", duration = 20.0, audioOnly = true),
        SourceMedia(id = "V", filename = "voice.m4a", duration = 5.0, audioOnly = true),
    )

    private fun project(state: EditState): Project = Project(title = "Clipboard history", sources = listOf(source) + sounds).commit(state)

    private fun reloaded(project: Project): Project {
        val files = HashMap<String, ByteArray>()
        val manifest = project.manifest { name, data -> files[name] = data }
        return Project.readManifest(manifest) { files.getValue(it) }
    }

    /** A state with every identity replaced by its position, to compare content across a paste. */
    private fun content(clips: List<TimelineClip>): List<TimelineClip> = clips.mapIndexed { index, clip ->
        clip.copy(
            id = "clip$index",
            transition = clip.transition?.let { it.copy(id = "join$index", nextClipID = it.nextClipID?.let { "clip${index + 1}" }) },
            openingTransition = clip.openingTransition?.copy(id = "opening", nextClipID = "clip$index"),
            texts = clip.texts?.mapIndexed { n, text -> text.copy(id = "text$n") },
            bookmarks = clip.bookmarks?.mapIndexed { n, mark -> mark.copy(id = "mark$n") },
            color = clip.color?.copy(id = "color"), crop = clip.crop?.copy(id = "crop"), speed = clip.speed?.copy(id = "speed"),
            subtitleWords = clip.subtitleWords?.mapIndexed { n, word -> word.copy(id = "word$n", phraseID = "phrase") },
        )
    }

    // MARK: Frames

    /** TRANSLATED: TimelineEditing.swift:42-43, `(time * 30).rounded() / 30`. */
    @Test fun timesAreQuantisedToThirtiethsRoundingHalfAwayFromZero() {
        assertEquals(1.0 / 30, RangeEditing.FRAME, 0.0)
        assertEquals(16.0 / 30, RangeEditing.quantize(0.517), 0.0)
        assertEquals(0.0, RangeEditing.quantize(0.016), 0.0)
        assertEquals(1.0 / 30, RangeEditing.quantize(0.0167), 0.0)
        assertEquals(1.0 / 30, RangeEditing.quantize(0.5 / 30), 0.0)
        assertEquals(-2.0 / 30, RangeEditing.quantize(-1.5 / 30), 0.0)
        assertEquals(7.0, RangeEditing.quantize(7.0), 0.0)
    }

    // MARK: Delete

    /** TRANSLATED: CoreTests.swift:183-190. */
    @Test fun rippleDeletionCrossesClipBoundariesWithoutShiftingSourceCoordinates() {
        val whole = EditState(listOf(clip("A", 0.0, 4.0)))
        val split = TimelineEditing.split(whole, 2.0)!!
        val removed = RangeEditing.delete(split, range(1.0, 3.0))!!.state
        assertEquals(listOf(0.0 to 1.0, 3.0 to 4.0), ranges(removed))
        assertEquals(2.0, removed.duration, 0.0)
        // Whole selection can be removed.
        assertTrue(RangeEditing.delete(split, range(0.0, 4.0))!!.state.clips.isEmpty())
        // Subframe accidental selection is a no-op.
        assertNull(RangeEditing.delete(whole, range(0.001, 0.002)))
    }

    /** TRANSLATED: TrackTimelineTests.swift:14-17 and 83-88. */
    @Test fun rangeDeletionKeepsTheOuterPiecesAndCarriesThePlayhead() {
        val baseline = EditState(listOf(clip("A", 0.0, 2.0), clip("A", 2.0, 4.0)))
        val removed = RangeEditing.delete(baseline, range(0.5, 3.5), playhead = 3.75)!!
        assertEquals(0.75, removed.position!!, 1e-9)
        assertEquals(1.0, removed.state.duration, 1e-9)
        assertEquals(listOf(0.0 to 0.5, 3.5 to 4.0), ranges(removed.state))
        // The pieces that stay keep the ids the clips had.
        assertEquals(baseline.clips[0].id, removed.state.clips[0].id)
    }

    @Test fun deletionInsideOneClipLeavesTwoPiecesOfIt() {
        val title = TextOverlay(text = "Whole", start = 0.0, end = 6.0)
        val whole = clip("A", 0.0, 6.0).copy(
            texts = listOf(title), bookmarks = listOf(Bookmark(time = 1.0), Bookmark(time = 3.0), Bookmark(time = 5.0)),
            audio = ClipAudio(volumeDB = -3.0, fadeIn = 0.5, fadeOut = 0.5), color = ColorSettings(look = ColorLook.entries.last(), amount = 0.5),
            crop = CropSettings(zoom = 2.0),
        )
        val removed = RangeEditing.delete(EditState(listOf(whole)), range(2.0, 4.0))!!.state
        assertEquals(listOf(0.0 to 2.0, 4.0 to 6.0), ranges(removed))
        val (left, right) = removed.clips
        assertEquals(whole.id, left.id)
        assertNotEquals(whole.id, right.id)
        // Each piece keeps the title it shows, its own marks, the level and the look; a fade stays at its end.
        assertEquals(listOf(title), left.texts)
        assertEquals(listOf(title), right.texts)
        assertEquals(listOf(1.0), left.bookmarks!!.map { it.time })
        assertEquals(listOf(5.0), right.bookmarks!!.map { it.time })
        assertEquals(ClipAudio(volumeDB = -3.0, fadeIn = 0.5), left.audio)
        assertEquals(ClipAudio(volumeDB = -3.0, fadeOut = 0.5), right.audio)
        assertEquals(whole.color, left.color)
        assertEquals(whole.color, right.color)
        assertEquals(whole.crop, right.crop)
    }

    /** TRANSLATED: VideoToolsTests.swift:353-366. */
    @Test fun rangeDeletionAndToolScopeMapThroughSpeed() {
        val fast = clip("A", 0.0, 4.0).copy(speed = SpeedSettings(rate = 2.0), texts = listOf(TextOverlay(text = "Hello", start = 1.0, end = 3.0)))
        val edit = EditState(listOf(fast))
        val removed = RangeEditing.delete(edit, range(0.5, 1.5))!!.state
        assertEquals(listOf(0.0 to 1.0, 3.0 to 4.0), ranges(removed))
        assertEquals(1.0, removed.duration, 0.0)
        val scoped = RangeEditing.scoped(edit, range(0.5, 1.5))
        assertEquals(3, scoped.state.clips.size)
        assertEquals(listOf(scoped.state.clips[1].id), scoped.clips)
        assertEquals(1.0 to 3.0, scoped.state.clips[1].start to scoped.state.clips[1].end)
        assertEquals(edit.duration, scoped.state.duration, 0.0)
    }

    /** TRANSLATED: RecordingHistoryTests.swift:110-118. Real captured boundaries are not on a 30 fps grid. */
    @Test fun boundaryExactCutLeavesNoSliversAndPastingBackRestoresTheSequence() {
        val a = clip("A", 0.0, 10.017)
        val b = clip("B", 0.0, 5.013)
        val c = clip("C", 0.0, 7.0)
        val state = EditState(listOf(a, b, c))
        val cut = RangeEditing.cut(state, range(a.duration, a.duration + b.duration))!!
        assertEquals(listOf(a, c), cut.state.clips)
        assertEquals(listOf(b), cut.fragment.clips)
        val pasted = RangeEditing.paste(cut.state, cut.fragment, a.duration)!!.state
        assertEquals(listOf("A", "B", "C"), pasted.clips.map { it.sourceID })
        assertEquals(state.duration, pasted.duration, 0.0)
        assertEquals(a.duration, RangeEditing.snap(10.02, state.clips, 60.0), 0.0)
    }

    /** TRANSLATED: TransitionTests.swift:56-62. */
    @Test fun rangeEndsWithinAFrameOfACutUseTheCut() {
        val offGrid = EditState(listOf(clip("A", 0.0, 0.517), clip("B", 0.0, 4.0)))
        val pasted = RangeEditing.paste(offGrid, ClipFragment(listOf(clip("A", 0.0, 0.3))), 1.0)!!.state
        assertEquals(4, pasted.clips.size)
        val afterDelete = RangeEditing.delete(pasted, range(1.0, 1.3))!!.state
        assertEquals(offGrid.duration, afterDelete.duration, 0.00001)
        val nearCut = RangeEditing.delete(offGrid, range(0.517 + 0.001, offGrid.duration - 0.001))!!.state
        assertEquals(1, nearCut.clips.size)
        assertEquals(0.517, nearCut.duration, 0.00001)
    }

    /** TRANSLATED: TrackTimelineTests.swift:29-34. */
    @Test fun thePlayheadFollowsSurvivingFootageOrLandsOnTheCut() {
        val a = clip("A", 0.0, 2.0)
        val b = clip("A", 2.0, 4.0)
        val baseline = EditState(listOf(a, b))
        for ((before, after) in listOf(0.5 to 0.0, 2.0 to 0.0, 3.25 to 1.25, 4.0 to 2.0)) {
            assertEquals("from $before", after, RangeEditing.delete(baseline, selected(a), before)!!.position!!, 1e-9)
        }
        assertEquals(0.75, RangeEditing.delete(baseline, selected(b), 0.75)!!.position!!, 0.0)
        assertNull(RangeEditing.delete(baseline, selected(b))!!.position)
    }

    @Test fun deletionAcrossATransitionRemovesItAndKeepsTheOthers() {
        val c = clip("C", 0.0, 4.0)
        val b = clip("B", 0.0, 4.0).copy(transition = TransitionSettings(nextClipID = c.id, style = TransitionStyle.FadeColor, duration = 1.0))
        val a = clip("A", 0.0, 4.0).copy(transition = TransitionSettings(nextClipID = b.id, style = TransitionStyle.Dissolve, duration = 1.0))
        val state = EditState(listOf(a, b, c))
        assertEquals(11.0, state.duration, 0.0)
        // A plays 0-4 and B 3-7: the range takes the end of A and the first two seconds of B.
        val removed = RangeEditing.delete(state, range(2.0, 5.0))!!.state
        assertEquals(listOf(0.0 to 2.0, 2.0 to 4.0, 0.0 to 4.0), ranges(removed))
        assertNull(removed.clips[0].transition)
        // The join after the range still belongs to what is left of B and to C.
        assertEquals(b.transition, removed.clips[1].transition)
        assertEquals(8.0, removed.duration, 0.0)
    }

    @Test fun aRangeEndingInsideADissolveEndsAtItsJoin() {
        val b = clip("B", 0.0, 4.0)
        val a = clip("A", 0.0, 4.0).copy(transition = TransitionSettings(nextClipID = b.id, style = TransitionStyle.Dissolve, duration = 1.0))
        val state = EditState(listOf(a, b))
        // 3.5 is the middle of the overlap, where the editor aligns a mark.
        val removed = RangeEditing.delete(state, range(3.5, 7.0))!!.state
        // B goes whole; A is not cut and shows the second it lent to the dissolve again.
        assertEquals(listOf(a.copy(transition = null)), removed.clips)
        val kept = RangeEditing.delete(state, range(0.0, 3.5))!!.state
        assertEquals(listOf(b), kept.clips)
        // Both ends in the same overlap cover nothing.
        assertNull(RangeEditing.delete(state, range(3.1, 3.9)))
        assertNull(RangeEditing.copy(state, range(3.1, 3.9)))
    }

    @Test fun emptyInvertedAndMeaninglessRangesAreRefused() {
        for (bad in listOf(TimeRange(2.0, 2.0), TimeRange(3.0, 1.0), TimeRange(1.0, 1.0 + 0.02), TimeRange(Double.NaN, 2.0),
            TimeRange(1.0, Double.POSITIVE_INFINITY), TimeRange(-5.0, 0.01), TimeRange(10.0, 12.0))) {
            val scope = EditScope.Range(bad)
            assertNull("$bad", RangeEditing.delete(base, scope))
            assertNull("$bad", RangeEditing.copy(base, scope))
            assertNull("$bad", RangeEditing.cut(base, scope))
            assertTrue("$bad", RangeEditing.scoped(base, scope).let { it.state === base && it.clips.isEmpty() })
        }
        // One frame is enough: TimelineEditing.swift:70, `>= frame - 0.00001`.
        assertEquals(10.0 - RangeEditing.FRAME, RangeEditing.delete(base, range(1.0, 1.0 + RangeEditing.FRAME))!!.state.duration, 1e-9)
        assertNull(RangeEditing.delete(base, EditScope.Selected(EditTargets(clips = setOf("missing")))))
        assertNull(RangeEditing.copy(base, EditScope.Selected(EditTargets())))
        assertNull(RangeEditing.delete(EditState(), EditScope.Whole))
        assertNull(RangeEditing.paste(base, ClipFragment(), 1.0))
        assertNull(RangeEditing.paste(base, ClipFragment(listOf(scenes[0])), Double.NaN))
    }

    // MARK: Copy, cut and paste

    /** TRANSLATED: TimelineClipboardChecks.swift:16-41, every choice of scenes into every slot. */
    @Test fun copiedAndCutScenesPasteIntoEverySlot() {
        val indices = scenes.indices.toList()
        for (mask in 1 until (1 shl scenes.size)) {
            val chosen = indices.filter { mask and (1 shl it) != 0 }
            val kept = indices - chosen.toSet()
            val scope = EditScope.Selected(EditTargets(clips = chosen.mapTo(HashSet()) { scenes[it].id }))
            val copy = RangeEditing.copy(base, scope)!!
            // Disjoint scenes pack in playback order.
            assertEquals(chosen.size * 2.0, copy.duration, 0.0)
            for (slot in 0..scenes.size) {
                val pasted = RangeEditing.paste(base, copy, slot * 2.0)!!
                assertEquals("mask $mask slot $slot", indices.take(slot) + chosen + indices.drop(slot), order(pasted.state))
                assertTrue(pasted.clips.none { id -> scenes.any { it.id == id } })
                assertEquals(pasted.state.clips.size, pasted.state.clips.map { it.id }.toSet().size)
            }
            val cut = RangeEditing.cut(base, scope)!!
            assertEquals(kept, order(cut.state))
            for (slot in 0..kept.size) {
                val pasted = RangeEditing.paste(cut.state, cut.fragment, slot * 2.0)!!.state
                assertEquals("mask $mask slot $slot", kept.take(slot) + chosen + kept.drop(slot), order(pasted))
                assertEquals(base.duration, pasted.duration, 0.0)
            }
        }
    }

    /** TRANSLATED: TimelineClipboardChecks.swift:43-60. */
    @Test fun eachPasteHasItsOwnIdentities() {
        val title = TextOverlay(text = "Last scene", start = 8.0, end = 9.0)
        val word = SubtitleWord(text = "Last", start = 8.2, end = 8.8)
        val last = scenes[4].copy(
            texts = listOf(title), bookmarks = listOf(Bookmark(time = 8.75)), subtitleWords = listOf(word),
            audio = ClipAudio(volumeDB = -3.0, fadeIn = 0.25), speed = null, crop = CropSettings(zoom = 1.5), color = ColorSettings(amount = 0.5),
        )
        val settings = SubtitleSettings(timingFingerprint = "made here")
        val decorated = base.copy(clips = scenes.take(4) + last, subtitles = settings)
        val late = RangeEditing.copy(decorated, selected(last))!!
        val first = RangeEditing.paste(decorated, late, 0.0)!!
        val second = RangeEditing.paste(first.state, late, first.state.duration)!!
        assertTrue(first.clips.intersect(second.clips.toSet()).isEmpty())
        assertEquals(listOf(0.75, 10.75, 12.75), TimelineEditing.bookmarkTimes(second.state.clips))
        val copies = listOf(second.state.clips.first(), second.state.clips.last())
        assertEquals(first.clips.single(), copies[0].id)
        assertEquals(second.clips.single(), copies[1].id)
        for (copy in copies) {
            // Titles, captions, fades and looks arrive whole and editable on their own.
            assertEquals(last.audio, copy.audio)
            assertEquals("Last scene", copy.texts!!.single().text)
            assertEquals("Last", copy.subtitleWords!!.single().text)
            assertEquals(last.crop!!.copy(id = copy.crop!!.id), copy.crop)
            assertEquals(last.color!!.copy(id = copy.color!!.id), copy.color)
        }
        val originals = listOf(last.id, title.id, word.id, word.phraseID, last.bookmarks!![0].id, last.crop!!.id, last.color!!.id)
        val identities = copies.map { listOf(it.id, it.texts!![0].id, it.subtitleWords!![0].id, it.subtitleWords!![0].phraseID, it.bookmarks!![0].id, it.crop!!.id, it.color!!.id) }
        assertEquals(21, (originals + identities[0] + identities[1]).toSet().size)
        // The project already had captions: its settings stay as they are.
        assertSame(settings, second.state.subtitles)
        assertEquals(0, first.skippedAudio)
    }

    /** TRANSLATED: RecordingHistoryTests.swift:119-121, the same clip pasted next to itself. */
    @Test fun pastedEffectsEditIndependentlyFromTheirOriginals() {
        val decorated = clip("B", 0.0, 5.013).copy(texts = listOf(TextOverlay(text = "Copy", end = 3.0)), speed = SpeedSettings(rate = 2.0))
        val state = EditState(listOf(decorated))
        val twice = RangeEditing.paste(state, RangeEditing.copy(state, EditScope.Whole)!!, 0.0)!!.state
        assertEquals(2, twice.clips.size)
        assertNotEquals(twice.clips[0].texts!![0].id, twice.clips[1].texts!![0].id)
        assertNotEquals(twice.clips[0].speed!!.id, twice.clips[1].speed!!.id)
        assertEquals(decorated, twice.clips[1])
    }

    /** TRANSLATED: TimelineClipboardChecks.swift:61-72. */
    @Test fun cutAndPasteAreOneUndoStepEachAndSurviveReopening() {
        val piece = RangeEditing.cut(base, range(1.0, 3.0), playhead = 8.0)!!
        assertEquals(6.0, piece.position!!, 0.0)
        assertEquals(8.0, piece.state.duration, 0.0)
        assertEquals(2.0, piece.fragment.duration, 0.0)
        // Cut and the clipboard use the same exact range fragments.
        assertEquals(listOf(1.0 to 2.0, 2.0 to 3.0), piece.fragment.clips.map { it.start to it.end })
        val pasted = RangeEditing.paste(piece.state, piece.fragment, 0.5)!!
        var project = project(base)
        val before = project.edit
        project = project.commit(piece.state)
        project = project.commit(pasted.state)
        assertEquals(3, project.historyCursor)
        project = reloaded(project)
        project = project.undo()
        assertEquals(8.0, project.edit.duration, 0.0)
        assertEquals(piece.state.clips, project.edit.clips)
        project = project.undo()
        // One step back is the exact edit there was before the cut.
        assertEquals(before, project.edit)
        assertEquals(base.clips, project.edit.clips)
        project = project.redo().redo()
        assertEquals(pasted.state.clips, project.edit.clips)
        assertEquals(10.0, project.edit.duration, 0.0)
    }

    @Test fun deletingARangeIsOneUndoStep() {
        val music = AudioItem(sourceID = source.id, start = 0.0, sourceStart = 0.0, sourceEnd = 10.0)
        val before = project(base.copy(audio = listOf(music)))
        val after = before.commit(RangeEditing.delete(before.edit, range(3.0, 7.5))!!.state)
        assertEquals(before.historyCursor + 1, after.historyCursor)
        after.validate()
        assertEquals(5.5, after.edit.duration, 1e-9)
        assertEquals(before.edit, reloaded(after).undo().edit)
    }

    /** TRANSLATED: TrackTimelineTests.swift:103-106, and TimelineClipboardChecks.swift:107-109. */
    @Test fun pastingInsideAClipSplitsItAndLeavesTheSoundTracksAlone() {
        val a = clip("A", 0.0, 2.0)
        val b = clip("A", 2.0, 4.0)
        val music = AudioItem(sourceID = "M", start = 0.0, sourceStart = 0.0, sourceEnd = 4.0)
        val baseline = EditState(listOf(a, b), audio = listOf(music))
        val copy = RangeEditing.copy(baseline, selected(a))!!
        assertTrue(copy.insertsTime)
        val pasted = RangeEditing.paste(baseline, copy, 1.0)!!
        assertEquals(6.0, pasted.state.duration, 0.0)
        assertEquals(listOf(0.0 to 1.0, 0.0 to 2.0, 1.0 to 2.0, 2.0 to 4.0), ranges(pasted.state))
        assertEquals(1.0, pasted.start, 0.0)
        assertEquals(listOf(pasted.state.clips[1].id), pasted.clips)
        assertEquals(a.id, pasted.state.clips[0].id)
        // Music on its own track runs on under the new picture, uncut and unmoved.
        assertEquals(listOf(music), pasted.state.audio)
    }

    /** TRANSLATED: TimelineClipboardChecks.swift:87-98 and 110-112. */
    @Test fun transitionsTravelWithBothPartnersAndBreakWhenSeparated() {
        val dissolve = TransitionSettings(nextClipID = scenes[2].id, style = TransitionStyle.Dissolve, duration = 0.5)
        val blend = base.copy(clips = scenes.mapIndexed { index, scene -> if (index == 1) scene.copy(transition = dissolve) else scene })
        assertEquals(9.5, blend.duration, 0.0)
        val late = RangeEditing.copy(blend, selected(scenes[4]))!!
        val join = RangeEditing.joinTimes(blend.clips)[2]
        assertEquals(3.75, join, 0.0)
        // Pasting picture into a dissolve recovers both source handles.
        val separated = RangeEditing.paste(blend, late, join)!!.state
        assertTrue(separated.clips.none { it.transition != null })
        assertEquals(12.0, separated.duration, 0.0)
        assertEquals(listOf(0, 1, 4, 2, 3, 4), order(separated))
        // A mid-transition paste retains all source handles too, and cuts the clip that was leaving.
        val inside = RangeEditing.paste(blend, late, join - 0.1)!!.state
        assertTrue(inside.clips.none { it.transition != null })
        assertEquals(12.0, inside.duration, 1e-9)
        assertEquals(listOf(0, 1, 4, 1, 2, 3, 4), order(inside))
        // An internal transition travels with both selected partners and keeps the real overlap.
        val both = RangeEditing.copy(blend, selected(scenes[1], scenes[2]))!!
        assertEquals(3.5, both.duration, 0.0)
        val copied = RangeEditing.paste(base, both, base.duration)!!.state
        val joins = copied.clips.mapNotNull { it.transition }
        assertEquals(1, joins.size)
        assertNotEquals(dissolve.id, joins[0].id)
        assertEquals(copied.clips[6].id, joins[0].nextClipID)
        assertEquals(13.5, copied.duration, 0.0)
        // One partner alone leaves the join behind.
        assertNull(RangeEditing.copy(blend, selected(scenes[1]))!!.clips.single().transition)
        // A range that cuts a partner on the far side still holds the whole join; one that cuts it at the join does not.
        assertEquals(dissolve, RangeEditing.copy(blend, range(3.0, 5.0))!!.clips[0].transition)
        assertNull(RangeEditing.copy(blend, range(2.0, 3.75))!!.clips.single().transition)
    }

    @Test fun pastingOnAJoinRemovesItsFade() {
        val fade = join(scenes[1], TransitionStyle.FadeColor, 1.0)
        val faded = base.copy(clips = listOf(scenes[0].copy(transition = fade)) + scenes.drop(1))
        val late = RangeEditing.copy(faded, selected(scenes[4]))!!
        for (at in listOf(2.0, 1.8, 2.3)) {
            val pasted = RangeEditing.paste(faded, late, at)!!.state
            assertTrue("at $at", pasted.clips.none { it.transition != null })
            assertEquals(12.0, pasted.duration, 1e-9)
        }
        // Away from the fade it stays.
        assertEquals(fade, RangeEditing.paste(faded, late, 6.0)!!.state.clips[0].transition)
    }

    @Test fun copyThenPasteReproducesTheContent() {
        val third = clip("C", 1.0, 5.0).copy(speed = SpeedSettings(rate = 0.5), crop = CropSettings(zoom = 2.0))
        val second = clip("B", 0.0, 3.0).copy(
            transition = join(third, TransitionStyle.FadeColor, 0.4), color = ColorSettings(amount = 0.3),
            texts = listOf(TextOverlay(text = "Two", start = 0.5, end = 2.0)), bookmarks = listOf(Bookmark(time = 1.0, label = "here")),
            subtitleWords = listOf(SubtitleWord(text = "Hi", start = 0.2, end = 0.6), SubtitleWord(text = "there", start = 0.7, end = 1.1)),
        )
        val first = clip("A", 2.0, 6.0).copy(
            transition = TransitionSettings(nextClipID = second.id, style = TransitionStyle.Dissolve, duration = 1.0),
            audio = ClipAudio(volumeDB = 6.0, muted = true, fadeIn = 1.0, fadeOut = 2.0, denoise = 0.5),
        )
        val music = AudioItem(sourceID = "M", start = 1.5, sourceStart = 3.0, sourceEnd = 9.0, track = 1, audio = ClipAudio(volumeDB = -12.0, fadeOut = 1.0))
        val state = EditState(listOf(first, second, third), audio = listOf(music), subtitles = SubtitleSettings(maxWords = 3, timingFingerprint = "x"))
        val copy = RangeEditing.copy(state, EditScope.Whole)!!
        assertEquals(state.clips, copy.clips)
        val pasted = RangeEditing.paste(EditState(), copy, 0.0)!!
        assertEquals(content(state.clips), content(pasted.state.clips))
        assertEquals(listOf(music.copy(id = pasted.audio.single())), pasted.state.audio)
        assertEquals(state.duration, pasted.state.duration, 0.0)
        // An empty project takes the caption look; regrouping waits for a transcription made here.
        assertEquals(state.subtitles!!.copy(timingFingerprint = ""), pasted.state.subtitles)
        assertFalse(SubtitleEditing.groupingAvailable(pasted.state))
        // The same through a range over everything, and through cut.
        val ranged = RangeEditing.copy(state, range(0.0, state.duration))!!
        assertEquals(copy.clips, ranged.clips)
        val cut = RangeEditing.cut(state, range(0.0, 20.0))!!
        assertTrue(cut.state.clips.isEmpty())
        assertNull(cut.state.audio)
        assertEquals(copy, cut.fragment)
    }

    /** TRANSLATED: SubtitleTests.swift:60-65. */
    @Test fun pastedSubtitlesOwnIndependentWords() {
        val phrase = newID()
        val words = listOf(SubtitleWord(phraseID = phrase, text = "Hello", start = 0.5, end = 1.0), SubtitleWord(phraseID = phrase, text = "world", start = 1.0, end = 1.5))
        val spoken = EditState(listOf(clip("A", 0.0, 12.0).copy(subtitleWords = words)), subtitles = SubtitleSettings())
        val pasted = RangeEditing.paste(spoken, RangeEditing.copy(spoken, EditScope.Whole)!!, 12.0)!!
        val cues = SubtitleEditing.cues(pasted.state)
        assertEquals(listOf(0.5, 12.5), cues.map { it.start })
        val copy = cues.single { it.audioID == pasted.clips.single() }
        // Words of one caption still share a phrase, a new one.
        val copied = pasted.state.clips[1].subtitleWords!!
        assertEquals(1, copied.map { it.phraseID }.toSet().size)
        assertNotEquals(phrase, copied[0].phraseID)
        val corrected = SubtitleEditing.correct(pasted.state, copy, "Independent copy")!!
        assertEquals(listOf("Hello world"), SubtitleEditing.cues(corrected).filter { it.audioID != copy.audioID }.map { it.text })
        assertEquals(words, corrected.clips[0].subtitleWords)
    }

    @Test fun aCopiedPieceCarriesOnlyWhatItShows() {
        val whole = clip("A", 0.0, 10.0).copy(
            texts = listOf(TextOverlay(text = "Early", start = 0.0, end = 2.0), TextOverlay(text = "Middle", start = 3.0, end = 5.0)),
            bookmarks = listOf(Bookmark(time = 1.0), Bookmark(time = 4.5), Bookmark(time = 9.0)),
            audio = ClipAudio(fadeIn = 1.0, fadeOut = 1.0),
        )
        val state = EditState(listOf(whole))
        val piece = RangeEditing.copy(state, range(4.0, 6.0))!!.clips.single()
        assertEquals(4.0 to 6.0, piece.start to piece.end)
        // The titles are those two ordinary splits leave on the middle piece, whatever the split's rule is.
        assertEquals(TimelineEditing.split(TimelineEditing.split(state, 6.0)!!, 4.0)!!.clips[1].texts, piece.texts)
        assertTrue(piece.texts!!.any { it.text == "Middle" })
        assertEquals(listOf(4.5), piece.bookmarks!!.map { it.time })
        // Neither end of the piece is an end of the clip, so it has neither fade.
        assertNull(piece.audio)
        // Copy never changes the edit.
        assertEquals(EditState(listOf(whole)), state)
        // A mark on footage trimmed away stays behind.
        val trimmed = EditState(listOf(whole.copy(start = 2.0, end = 8.0)))
        assertEquals(listOf(4.5), RangeEditing.copy(trimmed, EditScope.Whole)!!.clips.single().bookmarks!!.map { it.time })
    }

    // MARK: Sound on its own tracks

    @Test fun aRangeCutsSoundItemsAndNeverMovesWhatIsLeft() {
        val bed = AudioItem(sourceID = "M", start = 1.0, sourceStart = 5.0, sourceEnd = 13.0, audio = ClipAudio(volumeDB = -6.0, fadeIn = 1.0, fadeOut = 2.0))
        val inside = AudioItem(sourceID = "V", start = 4.5, sourceStart = 0.0, sourceEnd = 1.0, track = 1)
        val later = AudioItem(sourceID = "V", start = 7.0, sourceStart = 2.0, sourceEnd = 3.0, track = 1)
        val state = base.copy(audio = listOf(bed, inside, later))
        val removed = RangeEditing.delete(state, range(4.0, 6.0))!!.state
        assertEquals(8.0, removed.duration, 0.0)
        val (head, tail) = removed.audio!!.filter { it.sourceID == "M" }
        // The part before the range keeps the id and the fade in; the part after keeps its place and the fade out.
        assertEquals(bed.copy(sourceEnd = 8.0, audio = ClipAudio(volumeDB = -6.0, fadeIn = 1.0)), head)
        assertEquals(bed.copy(id = tail.id, start = 6.0, sourceStart = 10.0, audio = ClipAudio(volumeDB = -6.0, fadeOut = 2.0)), tail)
        assertNotEquals(bed.id, tail.id)
        assertEquals(listOf(later), removed.audio!!.filter { it.sourceID == "V" })
        project(state).commit(removed).validate()
        // Limited to the picture, the range leaves the sound tracks whole.
        val pictureOnly = RangeEditing.delete(state, EditScope.Range(TimeRange(4.0, 6.0), EditTargets(clips = scenes.mapTo(HashSet()) { it.id })))!!.state
        assertEquals(state.audio, pictureOnly.audio)
        assertEquals(8.0, pictureOnly.duration, 0.0)
        // Limited to one item, it leaves the picture whole.
        val soundOnly = RangeEditing.delete(state, EditScope.Range(TimeRange(4.0, 6.0), EditTargets(audio = setOf(inside.id))))!!.state
        assertEquals(state.clips, soundOnly.clips)
        assertEquals(listOf(bed, later), soundOnly.audio)
    }

    @Test fun copiedSoundKeepsItsDistanceFromThePictureAndFindsAFreeTrack() {
        val effect = AudioItem(sourceID = "V", start = 4.5, sourceStart = 0.0, sourceEnd = 1.0)
        val bed = AudioItem(sourceID = "M", start = 0.0, sourceStart = 0.0, sourceEnd = 10.0, track = 1)
        val wall = AudioItem(sourceID = "V", start = 9.6, sourceStart = 2.0, sourceEnd = 2.4)
        val state = base.copy(audio = listOf(effect, bed, wall))
        val copy = RangeEditing.copy(state, range(4.0, 6.0))!!
        assertEquals(listOf(4.0 to 6.0), copy.clips.map { it.start to it.end })
        assertEquals(listOf(bed.copy(start = 0.0, sourceStart = 4.0, sourceEnd = 6.0), effect.copy(start = 0.5)), copy.audio)
        // Track 1 is busy for the whole movie and track 0 at 9.6, so the two-second piece of the bed has nowhere to go.
        val pasted = RangeEditing.paste(state, copy, 8.0)!!
        assertEquals(1, pasted.skippedAudio)
        val added = pasted.state.audio!!.single { it.id in pasted.audio }
        assertEquals(effect.copy(id = added.id, start = 8.5), added)
        assertEquals(12.0, pasted.state.duration, 0.0)
        // The items that were there did not move, though the picture after 8 did.
        assertTrue(pasted.state.audio!!.containsAll(listOf(effect, bed, wall)))
        project(state).commit(pasted.state).validate()
        // Where its own track is busy the item takes the other one.
        val alone = base.copy(audio = listOf(effect))
        val moved = RangeEditing.paste(alone, RangeEditing.copy(alone, range(4.0, 6.0))!!, 4.0)!!
        assertEquals(0, moved.skippedAudio)
        assertEquals(1, moved.state.audio!!.single { it.id in moved.audio }.track)
    }

    /** TRANSLATED: TrackTimelineTests.swift:107-111. */
    @Test fun soundAloneIsLaidOverWithoutMovingThePicture() {
        val first = AudioItem(sourceID = "V", start = 1.0, sourceStart = 0.0, sourceEnd = 1.0)
        val second = AudioItem(sourceID = "V", start = 5.0, sourceStart = 1.0, sourceEnd = 2.0)
        val state = base.copy(audio = listOf(first, second))
        val both = EditScope.Selected(EditTargets(audio = setOf(first.id, second.id)))
        val packed = RangeEditing.copy(state, both)!!
        assertFalse(packed.insertsTime)
        // Unrelated selections pack; a marked range keeps the silence between them.
        assertEquals(listOf(0.0, 1.0), packed.audio.map { it.start })
        assertEquals(listOf(0.0, 4.0), RangeEditing.copy(state, EditScope.Range(TimeRange(0.0, 10.0), EditTargets(audio = setOf(first.id, second.id))))!!.audio.map { it.start })
        val pasted = RangeEditing.paste(state, packed, 7.0)!!
        assertEquals(state.clips, pasted.state.clips)
        assertTrue(pasted.clips.isEmpty())
        assertEquals(listOf(7.0, 8.0), pasted.state.audio!!.filter { it.id in pasted.audio }.map { it.start })
        assertEquals(7.0, pasted.start, 0.0)
        // With both tracks busy there, nothing can be pasted.
        val full = base.copy(audio = listOf(first.copy(start = 0.0, sourceEnd = 10.0), second.copy(start = 0.0, sourceEnd = 10.0, track = 1)))
        assertNull(RangeEditing.paste(full, packed, 3.0))
    }

    @Test fun aDetachedSoundStaysPairedWithItsPictureThroughCopyAndPaste() {
        val unlinked = AudioUnlinking.unlink(base, 2)!!
        val copy = RangeEditing.copy(unlinked, range(4.5, 5.5))!!
        val pasted = RangeEditing.paste(unlinked, copy, 10.0)!!
        val index = pasted.state.clips.lastIndex
        assertEquals(pasted.clips.single(), pasted.audio.single())
        assertEquals(AudioItem(id = pasted.clips.single(), sourceID = source.id, start = 10.0, sourceStart = 4.5, sourceEnd = 5.5, track = 0), AudioUnlinking.detachedSound(pasted.state, index))
        assertTrue(AudioUnlinking.matches(pasted.state, index))
        assertTrue(AudioUnlinking.valid(pasted.state))
        // The original pair is untouched.
        assertTrue(AudioUnlinking.matches(pasted.state, 2))
    }

    // MARK: Marks

    /** TRANSLATED: EditorView.swift:1117-1126, the Mark button. */
    @Test fun markingStartThenEndMakesARangeInEitherDirection() {
        var marks = RangeMarks()
        assertEquals(RangeMarks.Step.MarkStart, marks.step)
        assertFalse(marks.canDelete)
        marks = marks.marked(4.0)
        assertEquals(RangeMarks.Step.MarkEnd, marks.step)
        assertEquals(4.0, marks.start!!, 0.0)
        // Copy and Cut wait for the end; the tool scope is still the whole project.
        assertFalse(marks.canCopy)
        assertSame(EditScope.Whole, marks.scope)
        // "Choose an end at least one frame away".
        assertEquals(RangeMarks.Refusal.TooShort, marks.refusal(4.02))
        assertSame(marks, marks.marked(4.02))
        assertSame(marks, marks.marked(Double.NaN))
        marks = marks.marked(1.0)
        assertEquals(TimeRange(1.0, 4.0), marks.range)
        assertNull(marks.start)
        assertEquals(RangeMarks.Step.MoveEnd, marks.step)
        assertTrue(marks.canCopy && marks.canDelete)
        assertEquals(EditScope.Range(TimeRange(1.0, 4.0)), marks.scope)
        // "Place the end after the start".
        assertEquals(RangeMarks.Refusal.EndBeforeStart, marks.refusal(1.0))
        assertEquals(RangeMarks.Refusal.EndBeforeStart, marks.refusal(0.5))
        assertSame(marks, marks.marked(0.5))
        assertEquals(TimeRange(1.0, 1.0 + RangeEditing.FRAME), marks.marked(1.0 + RangeEditing.FRAME).range)
        assertEquals(TimeRange(1.0, 6.0), marks.marked(6.0).range)
        assertEquals(RangeMarks(), marks.cleared())
    }

    /** TRANSLATED: EditorView.swift:519-522, 607-614 and 998-1002: selection, range targets and the tool scope. */
    @Test fun selectionAndRangeReplaceEachOtherAndDecideTheScope() {
        var marks = RangeMarks().selecting(clip = "a")
        assertEquals(EditScope.Selected(EditTargets(clips = setOf("a"))), marks.scope)
        assertTrue(marks.canCopy)
        marks = marks.togglingClip("b").togglingAudio("m")
        assertEquals(EditTargets(setOf("a", "b"), setOf("m")), marks.selected)
        assertEquals(setOf("a", "b", "m"), marks.selected.ids)
        assertEquals(setOf("b"), marks.togglingClip("a").togglingAudio("m").selected.ids)
        // A range marked with a selection in place applies to those items only.
        val ranged = marks.marked(2.0).marked(5.0)
        assertEquals(EditScope.Range(TimeRange(2.0, 5.0), marks.selected), ranged.scope)
        // A long press during a range starts a new selection; a tap replaces everything.
        assertEquals(RangeMarks(selected = EditTargets(clips = setOf("c"))), ranged.togglingClip("c"))
        assertEquals(RangeMarks(selected = EditTargets(clips = setOf("c"))), marks.marked(2.0).togglingClip("c"))
        assertEquals(RangeMarks(selected = EditTargets(audio = setOf("m"))), ranged.selecting(audio = "m"))
        assertEquals(RangeMarks(), ranged.selecting())
        // With nothing selected the range covers everything.
        assertNull(RangeMarks().marked(2.0).marked(5.0).rangeTargets)
    }

    @Test fun marksDropWhatAnEditRemoved() {
        val marks = RangeMarks(range = TimeRange(7.0, 9.5), selected = EditTargets(setOf(scenes[0].id, scenes[4].id), setOf("gone")),
            rangeTargets = EditTargets(clips = setOf(scenes[4].id)))
        val shorter = EditState(scenes.take(4))
        val kept = marks.retained(shorter)
        assertEquals(TimeRange(7.0, 8.0), kept.range)
        assertEquals(EditTargets(clips = setOf(scenes[0].id)), kept.selected)
        assertEquals(EditTargets(), kept.rangeTargets)
        assertNull(marks.retained(EditState(scenes.take(3))).range)
        assertNull(marks.retained(EditState(scenes.take(3))).rangeTargets)
        assertNull(RangeMarks(start = 9.0).retained(shorter).start)
        assertEquals(marks, marks.retained(base.copy(audio = listOf(AudioItem(id = "gone", sourceID = "M", start = 0.0, sourceStart = 0.0, sourceEnd = 1.0)))))
    }

    /** TRANSLATED: EditorView.swift:1118-1122 and TransitionTests.swift:18, 23: a mark inside a transition goes to its join. */
    @Test fun marksLandOnFramesAndOnTheJoinOfATransition() {
        val second = clip("B", 0.0, 4.0)
        val first = clip("A", 0.0, 4.0)
        val dissolved = EditState(listOf(first.copy(transition = TransitionSettings(nextClipID = second.id, style = TransitionStyle.Dissolve, duration = 1.0)), second))
        assertEquals(listOf(0.0, 3.5, 7.0), RangeEditing.joinTimes(dissolved.clips))
        assertEquals(3.5, RangeEditing.snap(3.51, dissolved.clips, 60.0), 0.0)
        assertEquals(3.2, RangeEditing.snap(3.2, dissolved.clips, 60.0), 0.0)
        assertEquals(3.5, RangeEditing.markTime(dissolved, 3.2), 0.0)
        assertEquals(3.5, RangeEditing.markTime(dissolved, 3.95), 0.0)
        // The overlap ends at 4: a mark that rounds to it is outside.
        assertEquals(4.0, RangeEditing.markTime(dissolved, 3.99), 0.0)
        assertEquals(2.0, RangeEditing.markTime(dissolved, 2.01), 1e-12)
        // Transitions.swift:256, `time >= range.lowerBound && time < range.upperBound`. Join 1 leads into the second clip.
        assertEquals(1, RangeEditing.transitionAt(dissolved.clips, 3.0)!!.index)
        assertNull(RangeEditing.transitionAt(dissolved.clips, 2.99))
        assertNull(RangeEditing.transitionAt(dissolved.clips, 4.0))
        // A fade through black keeps the timeline's length and runs half before, half after the cut.
        val faded = EditState(listOf(first.copy(transition = join(second, TransitionStyle.FadeColor, 1.0)), second))
        assertEquals(listOf(0.0, 4.0, 8.0), RangeEditing.joinTimes(faded.clips))
        // TransitionTests.swift:39, `faded.layout.joins[1].range == 3.5...4.5`.
        assertEquals(3.5..4.5, RangeEditing.transitionAt(faded.clips, 3.5)!!.range)
        assertNull(RangeEditing.transitionAt(faded.clips, 4.5))
        assertEquals(4.0, RangeEditing.markTime(faded, 3.7), 0.0)
        assertEquals(4.0, RangeEditing.markTime(faded, 4.4), 0.0)
        // Off the grid, snapping to cuts keeps the exact recorded boundary; without it the mark is on a frame.
        val offGrid = EditState(listOf(clip("A", 0.0, 10.017), clip("B", 0.0, 5.0)))
        assertEquals(10.017, RangeEditing.markTime(offGrid, 10.02, pointsPerSecond = 60.0), 0.0)
        assertEquals(RangeEditing.quantize(10.02), RangeEditing.markTime(offGrid, 10.02), 0.0)
        assertEquals(offGrid.duration, RangeEditing.markTime(offGrid, 99.0), 0.0)
        assertEquals(0.0, RangeEditing.markTime(EditState(), 3.0), 0.0)
    }

    // MARK: Tool scope

    /** TRANSLATED: TransitionTests.swift:14-19 and 55-56. */
    @Test fun aToolScopeInsideAnOverlapAppliesToNothing() {
        val second = clip("B", 0.0, 4.0)
        val first = clip("A", 0.0, 4.0).copy(transition = TransitionSettings(nextClipID = second.id, style = TransitionStyle.Dissolve, duration = 1.0))
        val edit = EditState(listOf(first, second))
        assertEquals(7.0, edit.duration, 0.0)
        // Clip scope follows the overlap layout.
        assertEquals(TimeRange(3.5, 7.0), RangeEditing.interval(edit, null, second.id))
        assertEquals(TimeRange(0.0, 3.5), RangeEditing.interval(edit, null, first.id))
        assertEquals(TimeRange(0.0, 7.0), RangeEditing.interval(edit, null, null))
        assertEquals(TimeRange(0.0, 7.0), RangeEditing.interval(edit, TimeRange(-1.0, 9.0), second.id))
        // A selection inside overlap cannot silently modify excess footage.
        val bad = RangeEditing.scoped(edit, range(3.2, 5.0))
        assertSame(edit, bad.state)
        assertTrue(bad.clips.isEmpty())
        // On the join it applies to the incoming clip from its first frame.
        val good = RangeEditing.scoped(edit, range(3.5, 5.0))
        assertEquals(listOf(second.id), good.clips)
        assertEquals(listOf(0.0 to 4.0, 0.0 to 2.0, 2.0 to 4.0), ranges(good.state))
        assertEquals(first.transition, good.state.clips[0].transition)
        assertEquals(edit.duration, good.state.duration, 0.0)
    }

    @Test fun theScopeDecidesWhichClipsAToolAppliesTo() {
        assertEquals(scenes.map { it.id }, RangeEditing.scoped(base, EditScope.Whole).clips)
        assertSame(base, RangeEditing.scoped(base, EditScope.Whole).state)
        // Selected clips come in playback order whatever order they were picked in; unknown ids are ignored.
        val picked = EditScope.Selected(EditTargets(clips = setOf(scenes[3].id, "missing", scenes[1].id), audio = setOf("m")))
        assertEquals(listOf(scenes[1].id, scenes[3].id), RangeEditing.scoped(base, picked).clips)
        assertSame(base, RangeEditing.scoped(base, picked).state)
        // A range cuts at its ends and applies to the pieces inside: whole clips keep their ids.
        val ranged = RangeEditing.scoped(base, range(3.0, 7.0))
        assertEquals(listOf(2.0 to 3.0, 3.0 to 4.0, 4.0 to 6.0, 6.0 to 7.0, 7.0 to 8.0), ranges(ranged.state).subList(1, 6))
        assertEquals(ranged.state.clips.subList(2, 5).map { it.id }, ranged.clips)
        assertEquals(scenes[2].id, ranged.clips[1])
        assertEquals(scenes[3].id, ranged.clips[2])
        assertEquals(base.duration, ranged.state.duration, 0.0)
        // On clip boundaries nothing is cut.
        val exact = RangeEditing.scoped(base, range(2.0, 6.0))
        assertEquals(base.clips, exact.state.clips)
        assertEquals(listOf(scenes[1].id, scenes[2].id), exact.clips)
        // A range marked over a selection applies to the selected clips' parts only.
        val limited = RangeEditing.scoped(base, EditScope.Range(TimeRange(3.0, 7.0), EditTargets(clips = setOf(scenes[3].id))))
        assertEquals(listOf(scenes[3].id), limited.clips)
        assertEquals(listOf(6.0 to 7.0, 7.0 to 8.0), ranges(limited.state).subList(3, 5))
        assertEquals(6, limited.state.clips.size)
        val deleted = RangeEditing.delete(base, EditScope.Range(TimeRange(3.0, 7.0), EditTargets(clips = setOf(scenes[3].id))))!!.state
        assertEquals(listOf(0.0 to 2.0, 2.0 to 4.0, 4.0 to 6.0, 7.0 to 8.0, 8.0 to 10.0), ranges(deleted))
    }

    // MARK: Transitions of every style

    private fun join(state: EditState, index: Int) = TimelineEditing.join(state.clips, index)!!
    private val insert = ClipFragment(listOf(clip("C", 0.0, 2.0)))

    /**
     * The rules that do not depend on the style, for all nine: where a
     * transition runs is read from the layout, never from its style.
     * TRANSLATED where noted; the styles and their timing are Transitions.swift:6-10 and 108-156.
     */
    @Test fun everyStyleFollowsTheSameRangeRules() {
        assertEquals(9, TransitionStyle.entries.size)
        for (style in TransitionStyle.entries) {
            val state = pair(TransitionSettings(style = style, duration = 1.0))
            val settings = state.clips[0].transition!!
            val running = join(state, 1)
            val inside = running.range.start + (running.range.endInclusive - running.range.start) / 4
            assertNotEquals("$style", running.time, inside, 0.01)
            // EditorView.swift:1113-1115: a mark inside a transition goes to its join.
            assertEquals("$style", 1, RangeEditing.transitionAt(state.clips, inside)!!.index)
            assertEquals("$style", running.time, RangeEditing.markTime(state, inside), 0.0)
            assertNull("$style", RangeEditing.transitionAt(state.clips, running.range.start - 0.1))
            assertNull("$style", RangeEditing.transitionAt(state.clips, running.range.endInclusive + 0.1))
            // VideoTools.swift:299-303: a tool scope ending inside it applies to nothing.
            assertTrue("$style", RangeEditing.scoped(state, range(inside, state.duration)).let { it.state === state && it.clips.isEmpty() })
            // A range edit ending inside it ends at its join: the second clip goes whole and the first is not cut.
            assertEquals("$style", listOf(state.clips[0].copy(transition = null)), RangeEditing.delete(state, range(inside, state.duration))!!.state.clips)
            // TimelineClipboardChecks.swift:110-112: a paste inside it retains all source handles, and cuts the clip showing there.
            val within = RangeEditing.paste(state, insert, inside)!!.state
            assertEquals("$style", 4, within.clips.size)
            assertTrue("$style", within.clips.none { it.transition != null })
            assertEquals("$style", 10.0, within.duration, 1e-9)
            // TimelineClipboardChecks.swift:87-91: on its join the fragment goes between the two clips.
            val between = RangeEditing.paste(state, insert, running.time)!!.state
            assertEquals("$style", listOf("A", "C", "B"), between.clips.map { it.sourceID })
            assertTrue("$style", between.clips.none { it.transition != null })
            assertEquals("$style", 10.0, between.duration, 0.0)
            // Away from it a deletion leaves it alone, on the piece that ends the first clip.
            val away = RangeEditing.delete(state, range(1.0, 2.0))!!.state
            assertEquals("$style", listOf(null, settings, null), away.clips.map { it.transition })
            assertEquals("$style", state.duration - 1, away.duration, 1e-9)
            // TimelineClipboardChecks.swift:92-97: it travels with both partners and arrives with an identity of its own.
            val copy = RangeEditing.copy(state, EditScope.Whole)!!
            assertEquals("$style", state.duration, copy.duration, 0.0)
            val pasted = RangeEditing.paste(EditState(), copy, 0.0)!!.state
            assertEquals("$style", content(state.clips), content(pasted.clips))
            assertNotEquals("$style", settings.id, pasted.clips[0].transition!!.id)
            assertEquals("$style", pasted.clips[1].id, pasted.clips[0].transition!!.nextClipID)
            assertEquals("$style", state.duration, pasted.duration, 0.0)
            // One partner alone leaves it behind, and so does removing one.
            assertNull("$style", RangeEditing.copy(state, selected(state.clips[0]))!!.clips.single().transition)
            assertNull("$style", RangeEditing.delete(state, selected(state.clips[1]))!!.state.clips.single().transition)
        }
    }

    /** TRANSLATED: TrackTimeline.swift:1058-1060 (a cut passes the outgoing join to the second piece) and 853-874 (it stays while the pieces hold its footage). */
    @Test fun aWipeStaysWholeWhenARangeEndsWhereItBegins() {
        val state = pair(TransitionSettings(style = TransitionStyle.Wipe, duration = 1.0, wipeAngle = 30.0, hardEdge = true))
        val (a, b) = state.clips
        val wipe = a.transition!!
        // As a dissolve of one second: TransitionTests.swift:16, `duration == 7 && placements[1].start == 3 && joins[1].time == 3.5`.
        assertEquals(7.0, state.duration, 0.0)
        assertEquals(listOf(0.0, 3.5, 7.0), RangeEditing.joinTimes(state.clips))
        assertEquals(3.0..4.0, join(state, 1).range)
        // Up to where it begins: the second of the first clip under it stays, with it.
        val before = RangeEditing.delete(state, range(1.0, 3.0))!!.state
        assertEquals(listOf(0.0 to 1.0, 3.0 to 4.0, 0.0 to 4.0), ranges(before))
        assertEquals(listOf(null, wipe, null), before.clips.map { it.transition })
        assertEquals(5.0, before.duration, 0.0)
        assertEquals(1.0..2.0, join(before, 2).range)
        // From where it ends: the second of the second clip under it stays, and keeps the clip's identity.
        val after = RangeEditing.delete(state, range(4.0, 5.0))!!.state
        assertEquals(listOf(0.0 to 4.0, 0.0 to 1.0, 2.0 to 4.0), ranges(after))
        assertEquals(listOf(wipe, null, null), after.clips.map { it.transition })
        assertEquals(b.id, after.clips[1].id)
        assertEquals(6.0, after.duration, 0.0)
        // Within a frame of either edge, from outside, is the edge.
        assertEquals(ranges(before), ranges(RangeEditing.delete(state, range(1.0, 2.99))!!.state))
        assertEquals(ranges(after), ranges(RangeEditing.delete(state, range(4.01, 5.0))!!.state))
        // A fragment that reaches exactly as far as the wipe holds it whole.
        val copy = RangeEditing.copy(state, range(2.0, 4.0))!!
        assertEquals(listOf(2.0 to 4.0, 0.0 to 1.0), copy.clips.map { it.start to it.end })
        assertEquals(wipe, copy.clips[0].transition)
        assertEquals(2.0, copy.duration, 0.0)
        val pasted = RangeEditing.paste(EditState(listOf(clip("C", 0.0, 2.0))), copy, 2.0)!!.state
        assertEquals(wipe.copy(id = pasted.clips[1].transition!!.id, nextClipID = pasted.clips[2].id), pasted.clips[1].transition)
        assertEquals(4.0, pasted.duration, 0.0)
        // A tool applied from where it ends changes only the footage after it.
        val scoped = RangeEditing.scoped(state, range(4.0, 7.0))
        assertEquals(listOf(0.0 to 4.0, 0.0 to 1.0, 1.0 to 4.0), ranges(scoped.state))
        assertEquals(listOf(scoped.state.clips[2].id), scoped.clips)
        assertEquals(wipe, scoped.state.clips[0].transition)
        assertEquals(7.0, scoped.state.duration, 0.0)
        assertEquals(a.id, scoped.state.clips[0].id)
    }

    /**
     * The port's own: the editor aligns a mark inside a transition to its
     * join (EditorView.swift:1113-1115), so a range never ends there. One that
     * does, ends at the join.
     */
    @Test fun aRangeEdgeInsideATransitionIsItsJoin() {
        val state = pair(TransitionSettings(style = TransitionStyle.Wipe, duration = 1.0))
        val (a, b) = state.clips
        // Beginning inside: from the join, so the second clip from its first frame. The wipe goes with that piece.
        val from = RangeEditing.delete(state, range(3.2, 5.0))!!.state
        assertEquals(listOf(a.copy(transition = null)), from.clips.take(1))
        assertEquals(listOf(0.0 to 4.0, 2.0 to 4.0), ranges(from))
        assertEquals(6.0, from.duration, 0.0)
        assertEquals(RangeEditing.delete(state, range(3.5, 5.0))!!.state.clips.map { it.start to it.end }, ranges(from))
        // Ending inside: up to the join, so the first clip to its last frame.
        val until = RangeEditing.delete(state, range(1.0, 3.8))!!.state
        assertEquals(listOf(a.copy(end = 1.0, transition = null), b), until.clips)
        assertEquals(5.0, until.duration, 0.0)
        assertEquals(listOf(0.0 to 2.0), RangeEditing.copy(state, range(3.2, 5.0))!!.clips.map { it.start to it.end })
        assertNull(RangeEditing.copy(state, range(3.2, 5.0))!!.clips.single().transition)
        // TransitionTests.swift:53-54: a tool scope there applies to nothing.
        assertTrue(RangeEditing.scoped(state, range(3.2, 5.0)).clips.isEmpty())
        assertTrue(RangeEditing.scoped(state, range(1.0, 3.8)).clips.isEmpty())
    }

    /** TRANSLATED: TransitionTests.swift:282-284, "Solid color hold adds time without consuming footage". */
    @Test fun aFadeThroughColorWithAHoldIsOneStretchWithNoClipInTheMiddle() {
        val state = pair(TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0, hold = 2.0, fadeColor = TransitionColor(0.2, 0.7, 0.4)))
        val (a, b) = state.clips
        val fade = a.transition!!
        assertEquals(10.0, state.duration, 0.0)
        // Half a second of each clip fades; the color holds for two seconds between them, from 4 to 6.
        assertEquals(3.5..6.5, join(state, 1).range)
        assertEquals(listOf(0.0, 5.0, 10.0), RangeEditing.joinTimes(state.clips))
        assertEquals(6.0, TimelineEditing.clipStart(state.clips, 1), 0.0)
        for (time in listOf(3.5, 4.2, 5.5, 6.4)) {
            assertEquals("at $time", 1, RangeEditing.transitionAt(state.clips, time)!!.index)
            assertEquals("at $time", 5.0, RangeEditing.markTime(state, time), 0.0)
        }
        assertNull(RangeEditing.transitionAt(state.clips, 6.5))
        assertEquals(TimeRange(0.0, 5.0), RangeEditing.interval(state, null, a.id))
        // From the join on: the second clip goes, and the hold with it.
        assertEquals(listOf(a.copy(transition = null)), RangeEditing.delete(state, range(5.0, 10.0))!!.state.clips)
        // Around it, it stays.
        val before = RangeEditing.delete(state, range(1.0, 3.5))!!.state
        assertEquals(listOf(0.0 to 1.0, 3.5 to 4.0, 0.0 to 4.0), ranges(before))
        assertEquals(fade, before.clips[1].transition)
        assertEquals(7.5, before.duration, 0.0)
        val after = RangeEditing.delete(state, range(6.5, 7.5))!!.state
        assertEquals(listOf(0.0 to 4.0, 0.0 to 0.5, 1.5 to 4.0), ranges(after))
        assertEquals(fade, after.clips[0].transition)
        assertEquals(b.id, after.clips[1].id)
        assertEquals(9.0, after.duration, 0.0)
        // TimelinePaste.swift:176-184: a paste while the color holds removes the fade; the clips meet again and the one showing there is cut.
        val pasted = RangeEditing.paste(state, insert, 5.5)!!
        assertEquals(listOf(0.0 to 4.0, 0.0 to 1.5, 0.0 to 2.0, 1.5 to 4.0), ranges(pasted.state))
        assertTrue(pasted.state.clips.none { it.transition != null })
        assertEquals(10.0, pasted.state.duration, 0.0)
        assertEquals(5.5, pasted.start, 0.0)
        project(EditState()).copy(sources = listOf("A", "B", "C").map { SourceMedia(id = it, filename = "$it.mp4", duration = 4.0) }).commit(state).commit(pasted.state).validate()
    }

    /**
     * Sound that leads the picture (a J-cut) or trails it (an L-cut) leaves
     * the picture effect where it was and as long, and widens what the
     * original treats as the transition: `range`, Transitions.swift:150-153
     * and 256. The settings are those of TrackTimelineTests.swift:793-794.
     */
    @Test fun soundOffsetsWidenTheTransitionAndNotItsPicture() {
        val second = clip("A", 8.0, 18.0)
        val first = clip("A", 0.0, 8.0)
        fun with(settings: TransitionSettings) = EditState(listOf(first.copy(transition = settings.copy(nextClipID = second.id)), second))
        val plain = with(TransitionSettings(duration = 2.0))
        assertEquals(6.0..8.0, join(plain, 1).range)
        assertEquals(6.0..8.0, join(plain, 1).videoRange)
        // The next clip's sound starts a second before the dissolve and takes three: the clips play together from 5.
        val lead = with(TransitionSettings(duration = 2.0, audioOffset = -1.0, audioBlend = 3.0))
        val early = join(lead, 1)
        assertEquals(6.0..8.0, early.videoRange)
        assertEquals(5.0..8.0, early.range)
        assertEquals(7.0, early.time, 0.0)
        assertEquals(5.0, TimelineEditing.clipStart(lead.clips, 1), 0.0)
        assertEquals(15.0, lead.duration, 0.0)
        assertEquals(1, RangeEditing.transitionAt(lead.clips, 5.5)!!.index)
        assertEquals(7.0, RangeEditing.markTime(lead, 5.5), 0.0)
        assertEquals(4.9, RangeEditing.markTime(lead, 4.9), 1e-12)
        assertTrue(RangeEditing.scoped(lead, range(5.5, 9.0)).clips.isEmpty())
        // Up to where the sound begins to change, and from where the picture is done: the whole of it stays.
        val before = RangeEditing.delete(lead, range(1.0, 5.0))!!.state
        assertEquals(listOf(0.0 to 1.0, 5.0 to 8.0, 8.0 to 18.0), ranges(before))
        assertEquals(first.copy(transition = lead.clips[0].transition).transition, before.clips[1].transition)
        assertEquals(11.0, before.duration, 0.0)
        assertEquals(2.0..4.0, join(before, 2).videoRange)
        val after = RangeEditing.delete(lead, range(8.0, 9.0))!!.state
        assertEquals(listOf(0.0 to 8.0, 8.0 to 11.0, 12.0 to 18.0), ranges(after))
        assertEquals(14.0, after.duration, 0.0)
        assertEquals(early.range, join(after, 1).range)
        // A paste while only the sound is changing separates them all the same.
        val pasted = RangeEditing.paste(lead, insert, 5.5)!!.state
        assertEquals(listOf(0.0 to 5.5, 0.0 to 2.0, 5.5 to 8.0, 8.0 to 18.0), ranges(pasted))
        assertTrue(pasted.clips.none { it.transition != null })
        assertEquals(20.0, pasted.duration, 0.0)
        // The first clip's sound lingers a second past the dissolve: the picture changes from 5 to 7, the transition lasts to 8.
        val trail = with(TransitionSettings(duration = 2.0, audioOffset = 1.0, audioBlend = 2.0))
        val late = join(trail, 1)
        assertEquals(5.0..7.0, late.videoRange)
        assertEquals(5.0..8.0, late.range)
        assertEquals(6.0, late.time, 0.0)
        assertEquals(15.0, trail.duration, 0.0)
        assertEquals(6.0, RangeEditing.markTime(trail, 7.5), 0.0)
        assertEquals(8.0, RangeEditing.markTime(trail, 8.0), 0.0)
    }

    /** TRANSLATED: TransitionTests.swift:40-46, "Adjacent transitions never require three simultaneous clips" and "Transition windows never cross". */
    @Test fun aShortenedTransitionIsMeasuredAsItWasFitted() {
        val c = clip("C", 0.0, 4.0)
        val b = clip("B", 0.0, 4.0).copy(transition = join(c, TransitionStyle.Dissolve, 3.0))
        val a = clip("A", 0.0, 4.0).copy(transition = join(b, TransitionStyle.Dissolve, 3.0))
        val chain = EditState(listOf(a, b, c))
        // Two dissolves of three seconds share the four seconds of the middle clip: two each.
        assertEquals(8.0, chain.duration, 1e-9)
        assertEquals(2.0 / 3, join(chain, 1).factor, 1e-9)
        assertEquals(listOf(2.0, 4.0, 4.0, 6.0), listOf(1, 2).flatMap { listOf(join(chain, it).range.start, join(chain, it).range.endInclusive) }.map { RangeEditing.quantize(it) })
        assertEquals(listOf(0.0, 3.0, 5.0, 8.0), RangeEditing.joinTimes(chain.clips).map { RangeEditing.quantize(it) })
        assertNull(RangeEditing.transitionAt(chain.clips, 1.9))
        assertEquals(1, RangeEditing.transitionAt(chain.clips, 3.9)!!.index)
        assertEquals(2, RangeEditing.transitionAt(chain.clips, 4.1)!!.index)
        assertNull(RangeEditing.transitionAt(chain.clips, 6.1))
        // Where the entered three seconds would have begun, at 1, nothing is running.
        assertEquals(1.5, RangeEditing.markTime(chain, 1.5), 1e-12)
        assertEquals(3.0, RangeEditing.markTime(chain, 2.5), 1e-9)
        assertEquals(5.0, RangeEditing.markTime(chain, 4.5), 1e-9)
        // Between the two the middle clip can be cut, each piece wholly under one of them.
        val scoped = RangeEditing.scoped(chain, range(4.0, 8.0))
        assertEquals(4, scoped.state.clips.size)
        assertEquals(scoped.state.clips.drop(2).map { it.id }, scoped.clips)
        assertEquals(2.0, scoped.state.clips[1].end, 1e-9)
        assertEquals(b.id, scoped.state.clips[1].id)
        assertEquals(listOf(a.transition, null, b.transition, null), scoped.state.clips.map { it.transition })
        assertEquals(8.0, scoped.state.duration, 1e-9)
        // A deletion before the first one leaves both. How long they are then is the layout's answer for the
        // footage that is left, the same as after two splits and a delete: nothing here keeps a fitted length,
        // where the original stores the factor (TrackTimeline.swift:853-874).
        val shorter = RangeEditing.delete(chain, range(0.5, 1.5))!!.state
        assertEquals(listOf(null, a.transition, b.transition, null), shorter.clips.map { it.transition })
        val bySplits = TimelineEditing.delete(TimelineEditing.split(TimelineEditing.split(chain, 1.5)!!, 0.5)!!, 1)!!
        assertEquals(ranges(bySplits), ranges(shorter))
        assertEquals(bySplits.duration, shorter.duration, 0.0)
        assertEquals(join(bySplits, 2).range, join(shorter, 2).range)
        // A paste inside the first removes it. The second then has the whole middle clip and is three seconds
        // again: the layout fits a transition to the footage there is (TransitionLayout.sequence), where the
        // original keeps the factor it stored (TrackTimeline.swift:853-874).
        val pasted = RangeEditing.paste(chain, insert, 3.5)!!.state
        assertEquals(listOf("A", "C", "A", "B", "C"), pasted.clips.map { it.sourceID })
        assertEquals(listOf(null, null, null, b.transition!!.id, null), pasted.clips.map { it.transition?.id })
        assertEquals(1.0, join(pasted, 4).factor, 0.0)
        assertEquals(11.0, pasted.duration, 1e-9)
    }

    /**
     * TRANSLATED: TransitionTests.swift:285-287, "Still holds the final frame
     * before dissolving into the next scene".
     *
     * When the freeze goes the original leaves its two seconds empty between
     * the clips (TrackTimeline.swift:784-790 closes only the footage that was
     * removed). Here clips always follow one another, so that time closes.
     */
    @Test fun aFreezeGoesWithTheEndOfItsClip() {
        val state = pair(TransitionSettings(style = TransitionStyle.Still, duration = 2.0, stillDissolve = 0.5))
        val (a, b) = state.clips
        val still = a.transition!!
        assertEquals(10.0, state.duration, 0.0)
        assertEquals(4.0..6.5, join(state, 1).videoRange)
        // The second clip begins when the freeze ends, and that is the join.
        assertEquals(listOf(0.0, 6.0, 10.0), RangeEditing.joinTimes(state.clips))
        assertEquals(6.0, RangeEditing.markTime(state, 5.0), 0.0)
        assertEquals(6.0, RangeEditing.markTime(state, 6.3), 0.0)
        // The frozen frame is the last of the first clip: without that frame there is no freeze.
        val trimmed = RangeEditing.delete(state, range(2.0, 4.0))!!.state
        assertEquals(listOf(a.copy(end = 2.0, transition = null), b), trimmed.clips)
        assertEquals(6.0, trimmed.duration, 0.0)
        // It needs no footage of the first clip, so a range reaching its start is a range reaching the join.
        assertEquals(listOf(a.copy(transition = null)), RangeEditing.delete(state, range(6.0, 10.0))!!.state.clips)
        // After its dissolve the second clip can be cut and the freeze stays.
        val after = RangeEditing.delete(state, range(6.5, 7.5))!!.state
        assertEquals(listOf(0.0 to 4.0, 0.0 to 0.5, 1.5 to 4.0), ranges(after))
        assertEquals(still, after.clips[0].transition)
        assertEquals(9.0, after.duration, 0.0)
        // A paste during the freeze removes it: the fragment lands at the cut that is left.
        val pasted = RangeEditing.paste(state, insert, 5.0)!!
        assertEquals(listOf("A", "B", "C", "B"), pasted.state.clips.map { it.sourceID })
        assertEquals(listOf(0.0 to 4.0, 0.0 to 1.0, 0.0 to 2.0, 1.0 to 4.0), ranges(pasted.state))
        assertEquals(10.0, pasted.state.duration, 0.0)
    }

    /** TRANSLATED: TrackTimeline.swift:849-852, a transition goes with either of its clips and is never handed to another pair. */
    @Test fun clipsThatBecomeNeighborsMeetAtAPlainCut() {
        val c = clip("C", 0.0, 4.0)
        val b = clip("B", 0.0, 4.0).copy(transition = join(c, TransitionStyle.Still, 1.0))
        val a = clip("A", 0.0, 4.0).copy(transition = join(b, TransitionStyle.Wipe, 1.0))
        val state = EditState(listOf(a, b, c))
        assertEquals(12.0, state.duration, 0.0)
        for (removed in listOf(RangeEditing.delete(state, selected(b))!!.state, RangeEditing.delete(state, range(3.5, 8.0))!!.state, RangeEditing.cut(state, selected(b))!!.state)) {
            assertEquals(listOf(a.copy(transition = null), c), removed.clips)
            assertEquals(8.0, removed.duration, 0.0)
        }
        // The middle of the middle clip can go: both joins still have their clips.
        val hollow = RangeEditing.delete(state, range(5.0, 6.0))!!.state
        assertEquals(listOf(a.transition, null, b.transition, null), hollow.clips.map { it.transition })
        assertEquals(11.0, hollow.duration, 0.0)
        // Cut out and pasted back between the same clips, the clip returns without them: the joins were its neighbors'.
        val lifted = RangeEditing.cut(state, selected(b))!!
        assertNull(lifted.fragment.clips.single().transition)
        assertTrue(RangeEditing.paste(lifted.state, lifted.fragment, 4.0)!!.state.clips.none { it.transition != null })
    }

    // MARK: The opening and the close

    /** TRANSLATED: TransitionTests.swift:46-48, "Black opening/closing retain the whole project". */
    private fun ends(): EditState {
        var state = EditState(listOf(clip("A", 0.0, 4.0), clip("B", 0.0, 4.0)))
        state = TimelineEditing.setJoin(state, 0, TransitionSettings(duration = 1.0))!!
        state = TimelineEditing.setJoin(state, 2, TransitionSettings(duration = 1.0))!!
        assertEquals(8.0, state.duration, 0.0)
        assertEquals(0.0..1.0, join(state, 0).range)
        assertEquals(7.0..8.0, join(state, 2).range)
        return state
    }

    @Test fun marksInsideTheOpeningAndTheCloseGoToTheEndsOfTheTimeline() {
        val state = ends()
        assertEquals(0, RangeEditing.transitionAt(state.clips, 0.5)!!.index)
        assertEquals(2, RangeEditing.transitionAt(state.clips, 7.5)!!.index)
        assertNull(RangeEditing.transitionAt(state.clips, 1.0))
        assertNull(RangeEditing.transitionAt(state.clips, 6.9))
        assertEquals(0.0, RangeEditing.markTime(state, 0.4), 0.0)
        assertEquals(8.0, RangeEditing.markTime(state, 7.6), 0.0)
        assertEquals(1.0, RangeEditing.markTime(state, 1.0), 0.0)
        assertTrue(RangeEditing.scoped(state, range(0.5, 3.0)).clips.isEmpty())
        assertTrue(RangeEditing.scoped(state, range(3.0, 7.5)).clips.isEmpty())
        assertEquals(state.clips.map { it.id }, RangeEditing.scoped(state, range(0.0, 8.0)).clips)
    }

    /**
     * TRANSLATED: TrackTimeline.swift:849-852 and 1058-1060. The opening is
     * the first clip's incoming transition and the close the last clip's
     * outgoing one: each goes with the piece that holds that end of the clip.
     */
    @Test fun deletingTheStartOrTheEndTakesTheOpeningOrTheCloseWithIt() {
        val state = ends()
        val (a, b) = state.clips
        val opening = a.openingTransition!!
        val close = b.transition!!
        // The first clip, or its beginning: what is first now was never opened.
        assertEquals(listOf(b), RangeEditing.delete(state, selected(a))!!.state.clips)
        val headless = RangeEditing.delete(state, range(0.0, 2.0))!!.state
        assertEquals(listOf(2.0 to 4.0, 0.0 to 4.0), ranges(headless))
        assertEquals(listOf(null, null), headless.clips.map { it.openingTransition })
        assertEquals(close, headless.clips[1].transition)
        assertEquals(6.0, headless.duration, 0.0)
        // A range from inside the opening is a range from the start.
        assertEquals(ranges(headless), ranges(RangeEditing.delete(state, range(0.5, 2.0))!!.state))
        // From where the opening ends, it stays.
        val opened = RangeEditing.delete(state, range(1.0, 2.0))!!.state
        assertEquals(listOf(0.0 to 1.0, 2.0 to 4.0, 0.0 to 4.0), ranges(opened))
        assertEquals(listOf(opening, null, null), opened.clips.map { it.openingTransition })
        assertEquals(0.0..1.0, join(opened, 0).range)
        assertEquals(7.0, opened.duration, 0.0)
        // The last clip, or its end.
        assertEquals(listOf(a), RangeEditing.delete(state, selected(b))!!.state.clips)
        val tailless = RangeEditing.delete(state, range(6.0, 8.0))!!.state
        assertEquals(listOf(0.0 to 4.0, 0.0 to 2.0), ranges(tailless))
        assertEquals(listOf(null, null), tailless.clips.map { it.transition })
        assertEquals(opening, tailless.clips[0].openingTransition)
        assertEquals(ranges(tailless), ranges(RangeEditing.delete(state, range(6.0, 7.5))!!.state))
        // Up to where the close begins, it stays.
        val closed = RangeEditing.delete(state, range(5.0, 7.0))!!.state
        assertEquals(listOf(0.0 to 4.0, 0.0 to 1.0, 3.0 to 4.0), ranges(closed))
        assertEquals(listOf(null, null, close), closed.clips.map { it.transition })
        assertEquals(5.0..6.0, join(closed, 3).range)
        // The middle leaves both.
        val middle = RangeEditing.delete(state, range(3.0, 5.0))!!.state
        assertEquals(opening, middle.clips.first().openingTransition)
        assertEquals(close, middle.clips.last().transition)
    }

    /**
     * In the original the opening and the close are relations of their clip
     * and travel with it in the clipboard (TimelinePaste.swift:70-75,
     * TrackTimeline.swift:875-879). They arrive here when the clip arrives at
     * that end of the timeline, where this model can hold them.
     */
    @Test fun theOpeningAndTheCloseTravelWithTheirClipToTheSameEnd() {
        val state = ends()
        val (a, b) = state.clips
        val copy = RangeEditing.copy(state, EditScope.Whole)!!
        assertEquals(state.clips, copy.clips)
        val pasted = RangeEditing.paste(EditState(), copy, 0.0)!!.state
        assertEquals(content(state.clips), content(pasted.clips))
        assertNotEquals(a.openingTransition!!.id, pasted.clips[0].openingTransition!!.id)
        assertNotEquals(b.transition!!.id, pasted.clips[1].transition!!.id)
        assertEquals(pasted.clips[0].id, pasted.clips[0].openingTransition!!.nextClipID)
        assertEquals(8.0, pasted.duration, 0.0)
        // Cut and put back where it was, each end is as before.
        val first = RangeEditing.cut(state, selected(a))!!
        assertEquals(a.openingTransition, first.fragment.clips.single().openingTransition)
        assertEquals(content(state.clips), content(RangeEditing.paste(first.state, first.fragment, 0.0)!!.state.clips))
        val last = RangeEditing.cut(state, range(4.0, 8.0))!!
        assertEquals(b.transition, last.fragment.clips.single().transition)
        assertEquals(content(state.clips), content(RangeEditing.paste(last.state, last.fragment, 4.0)!!.state.clips))
        // A piece carries the end of the clip it holds, and no other.
        assertNull(RangeEditing.copy(state, range(1.0, 3.0))!!.clips.single().openingTransition)
        assertEquals(a.openingTransition, RangeEditing.copy(state, range(0.0, 3.0))!!.clips.single().openingTransition)
        assertNull(RangeEditing.copy(state, range(5.0, 7.0))!!.clips.single().transition)
        assertEquals(b.transition, RangeEditing.copy(state, range(5.0, 8.0))!!.clips.single().transition)
        // Anywhere else they are left behind: in the middle, or at the other end.
        val swapped = RangeEditing.paste(first.state, first.fragment, 4.0)!!.state
        assertEquals(listOf("B", "A"), swapped.clips.map { it.sourceID })
        assertTrue(swapped.clips.none { it.openingTransition != null || it.transition != null })
        val twice = RangeEditing.paste(state, copy, 4.0)!!.state
        assertEquals(listOf(a.openingTransition, null, null, null), twice.clips.map { it.openingTransition })
        assertEquals(listOf(null, null, null, b.transition), twice.clips.map { it.transition })
    }

    /**
     * Where the original and this model part ways: the original leaves an
     * opening on its clip when another is pasted before it, and a close when
     * one is pasted after (TimelinePaste.swift:176-190 moves the clips;
     * TrackTimeline.swift:875-879 moves the relation with its clip), and they
     * play on in the middle of the movie. Here they count at the ends only,
     * and [TimelineEditing.insert] removes one that is displaced: a paste
     * does what an insert does.
     */
    @Test fun aFragmentPastedAtAnEndReplacesTheOpeningOrTheClose() {
        val state = ends()
        val (a, b) = state.clips
        val added = clip("C", 0.0, 2.0)
        fun joins(edit: EditState) = edit.clips.map { Triple(it.sourceID, it.openingTransition, it.transition) }
        val before = RangeEditing.paste(state, insert, 0.0)!!.state
        assertEquals(listOf(Triple("C", null, null), Triple("A", null, null), Triple("B", null, b.transition)), joins(before))
        assertEquals(joins(TimelineEditing.insert(state, listOf(added), 0.0)!!), joins(before))
        assertEquals(10.0, before.duration, 0.0)
        val after = RangeEditing.paste(state, insert, 8.0)!!.state
        assertEquals(listOf(Triple("A", a.openingTransition, null), Triple("B", null, null), Triple("C", null, null)), joins(after))
        assertEquals(joins(TimelineEditing.insert(state, listOf(added), 8.0)!!), joins(after))
        // In the middle both stay.
        val between = RangeEditing.paste(state, insert, 4.0)!!.state
        assertEquals(listOf(Triple("A", a.openingTransition, null), Triple("C", null, null), Triple("B", null, b.transition)), joins(between))
        // TimelinePaste.swift:177-184: a paste while the opening or the close is running removes it, and cuts the clip there.
        val opening = RangeEditing.paste(state, insert, 0.5)!!.state
        assertEquals(listOf(0.0 to 0.5, 0.0 to 2.0, 0.5 to 4.0, 0.0 to 4.0), ranges(opening))
        assertTrue(opening.clips.none { it.openingTransition != null })
        assertEquals(b.transition, opening.clips.last().transition)
        assertEquals(10.0, opening.duration, 0.0)
        val closing = RangeEditing.paste(state, insert, 7.5)!!.state
        assertEquals(listOf(0.0 to 4.0, 0.0 to 3.5, 0.0 to 2.0, 3.5 to 4.0), ranges(closing))
        assertTrue(closing.clips.none { it.transition != null })
        assertEquals(a.openingTransition, closing.clips.first().openingTransition)
        assertEquals(10.0, closing.duration, 0.0)
    }

    /** TRANSLATED: TransitionTests.swift:288, "Opening still holds black before source footage". */
    @Test fun soundCopiedUnderAnOpeningFreezeKeepsItsDistanceFromThePicture() {
        var state = EditState(listOf(clip("A", 0.0, 4.0), clip("B", 0.0, 4.0)))
        state = TimelineEditing.setJoin(state, 0, TransitionSettings(style = TransitionStyle.Still, duration = 1.0))!!
        assertEquals(9.0, state.duration, 0.0)
        // The first clip begins after the freeze, at 1; the effect lies one second into it.
        val effect = AudioItem(sourceID = "V", start = 2.0, sourceStart = 0.0, sourceEnd = 1.0)
        state = state.copy(audio = listOf(effect))
        // The join of an opening is the start of the timeline (Transitions.swift:231).
        assertEquals(0.0, RangeEditing.markTime(state, 0.5), 0.0)
        val copy = RangeEditing.copy(state, range(0.0, 5.0))!!
        assertEquals(1.0, copy.audio.single().start, 0.0)
        // At the start the freeze comes too and the picture begins at 1; later it does not and the picture begins at the paste.
        val alone = RangeEditing.paste(EditState(), copy, 0.0)!!
        assertEquals(1.0, alone.start, 0.0)
        assertEquals(2.0, alone.state.audio!!.single().start, 0.0)
        assertEquals(5.0, alone.state.duration, 0.0)
        val later = RangeEditing.paste(EditState(listOf(clip("C", 0.0, 2.0))), copy, 2.0)!!
        assertEquals(2.0, later.start, 0.0)
        assertEquals(3.0, later.state.audio!!.single().start, 0.0)
        assertEquals(6.0, later.state.duration, 0.0)
    }

    // MARK: Cutting as a split does

    /**
     * A range cuts a clip with [TimelineEditing.split] itself, so what each
     * piece keeps (its titles, marks, fades and the join at its own end) is
     * whatever an ordinary split leaves there.
     * TRANSLATED: TrackTimeline.swift:1080-1089, a range is two splits.
     */
    @Test fun aRangeCutsAClipExactlyAsTwoSplitsDo() {
        val whole = clip("A", 0.0, 10.0).copy(
            texts = listOf(TextOverlay(text = "Early", start = 0.0, end = 2.0), TextOverlay(text = "Middle", start = 3.0, end = 5.0), TextOverlay(text = "Late", start = 7.0, end = 9.0)),
            bookmarks = listOf(Bookmark(time = 1.0), Bookmark(time = 4.5), Bookmark(time = 9.0)),
            audio = ClipAudio(volumeDB = -3.0, fadeIn = 1.0, fadeOut = 1.0), color = ColorSettings(amount = 0.5), speed = SpeedSettings(rate = 2.0),
        )
        var state = EditState(listOf(whole))
        state = TimelineEditing.setJoin(state, 0, TransitionSettings(duration = 0.5))!!
        state = TimelineEditing.setJoin(state, 1, TransitionSettings(style = TransitionStyle.FadeColor, duration = 0.5))!!
        fun plain(clips: List<TimelineClip>) = clips.mapIndexed { index, clip -> clip.copy(id = "$index") }
        val scoped = RangeEditing.scoped(state, range(2.0, 3.0))
        val split = TimelineEditing.split(TimelineEditing.split(state, 3.0)!!, 2.0)!!
        assertEquals(plain(split.clips), plain(scoped.state.clips))
        assertEquals(listOf(0.0 to 4.0, 4.0 to 6.0, 6.0 to 10.0), ranges(scoped.state))
        assertEquals(whole.id, scoped.state.clips[0].id)
        assertEquals(listOf(scoped.state.clips[1].id), scoped.clips)
        assertEquals(3, scoped.state.clips.map { it.id }.toSet().size)
        // The opening stays at the start of the clip and the close at its end.
        assertEquals(listOf(state.clips[0].openingTransition, null, null), scoped.state.clips.map { it.openingTransition })
        assertEquals(listOf(null, null, state.clips[0].transition), scoped.state.clips.map { it.transition })
        // A split refuses a piece shorter than a tenth of a second; a range may leave two frames (TrackTimeline.swift:1015, one tick is enough).
        val bare = EditState(listOf(whole.copy(speed = null)))
        assertNull(TimelineEditing.split(bare, 0.07))
        val short = RangeEditing.delete(bare, range(0.07, 3.0))!!.state
        assertEquals(2, short.clips.size)
        assertEquals(whole.id, short.clips[0].id)
        assertEquals(0.0, short.clips[0].start, 0.0)
        assertEquals(0.07, short.clips[0].end, 1e-12)
        assertEquals(3.0 to 10.0, short.clips[1].start to short.clips[1].end)
        assertEquals(7.07, short.duration, 1e-12)
        assertEquals(ClipAudio(volumeDB = -3.0, fadeIn = 1.0), short.clips[0].audio)
        assertNull(short.clips[0].bookmarks)
        assertEquals(listOf(4.5, 9.0), short.clips[1].bookmarks!!.map { it.time })
        val late = RangeEditing.delete(bare, range(3.0, 9.95))!!.state
        assertEquals(listOf(3.0, 9.95), listOf(late.clips[0].end, late.clips[1].start))
        assertEquals(10.0, late.clips[1].end, 0.0)
        assertEquals(3.05, late.duration, 1e-12)
    }
}
