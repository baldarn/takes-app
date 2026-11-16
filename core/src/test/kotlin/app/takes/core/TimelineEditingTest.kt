package app.takes.core

import app.takes.core.TimelineEditing.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ShortBuffer

class TimelineEditingTest {
    private val a = TimelineClip(sourceID = "A", start = 0.0, end = 4.0)
    private val b = TimelineClip(sourceID = "B", start = 1.0, end = 3.0)
    private val state = EditState(listOf(a, b), captureBase = 2)

    @Test fun locatesTheClipUnderThePlayhead() {
        assertNull(TimelineEditing.locate(emptyList(), 0.0))
        assertNull(TimelineEditing.locate(state.clips, Double.NaN))
        assertEquals(Location(0, 0.0), TimelineEditing.locate(state.clips, -1.0))
        assertEquals(Location(0, 3.5), TimelineEditing.locate(state.clips, 3.5))
        assertEquals(Location(1, 0.0), TimelineEditing.locate(state.clips, 4.0))
        assertEquals(Location(1, 2.0), TimelineEditing.locate(state.clips, 6.0))
        assertEquals(Location(1, 2.0), TimelineEditing.locate(state.clips, 99.0))
        assertEquals(4.0, TimelineEditing.clipStart(state.clips, 1), 0.0)
    }

    @Test fun splitKeepsEverySourceFrameOnce() {
        val split = TimelineEditing.split(state, 5.0)!!
        assertEquals(listOf("A", "B", "B"), split.clips.map { it.sourceID })
        assertEquals(listOf(0.0 to 4.0, 1.0 to 2.0, 2.0 to 3.0), split.clips.map { it.start to it.end })
        assertEquals(state.duration, split.duration, 1e-12)
        assertEquals(b.id, split.clips[1].id)
        assertEquals(3, split.clips.map { it.id }.toSet().size)
        assertEquals(2, split.captureBase)
    }

    @Test fun splitNeedsRoomOnBothSides() {
        assertNull(TimelineEditing.split(state, 0.0))
        assertNull(TimelineEditing.split(state, 4.0))
        // Since S4 a piece of any length is left, as in the original (`split-refused-near-an-edge`).
        assertEquals(0.05, TimelineEditing.split(state, 3.95)!!.clips[1].duration, 1e-9)
        assertNull(TimelineEditing.split(state, 6.0))
        assertNull(TimelineEditing.split(EditState(), 1.0))
    }

    @Test fun deleteClosesTheGap() {
        assertEquals(listOf(b), TimelineEditing.delete(state, 0)!!.clips)
        assertEquals(emptyList<TimelineClip>(), TimelineEditing.delete(EditState(listOf(a)), 0)!!.clips)
        assertNull(TimelineEditing.delete(state, 2))
    }

    @Test fun moveReorders() {
        val c = TimelineClip(sourceID = "C", start = 0.0, end = 1.0)
        val three = EditState(listOf(a, b, c))
        assertEquals(listOf(b, a, c), TimelineEditing.move(three, 0, 1)!!.clips)
        assertEquals(listOf(c, a, b), TimelineEditing.move(three, 2, 0)!!.clips)
        assertNull(TimelineEditing.move(three, 1, 1))
        assertNull(TimelineEditing.move(three, 0, 3))
    }

    @Test fun editsSurviveTheProjectRoundTrip() {
        val take = SourceMedia(filename = "a.mp4", duration = 4.0)
        var project = Project("Trip").addRecording(take)
        project = project.commit(TimelineEditing.split(project.edit, 1.5)!!)
        project = project.commit(TimelineEditing.move(project.edit, 0, 1)!!)
        project.validate()
        assertEquals(listOf(1.5 to 4.0, 0.0 to 1.5), project.edit.clips.map { it.start to it.end })
        assertEquals(listOf(0.0 to 4.0), project.undo().undo().edit.clips.map { it.start to it.end })
    }

    @Test fun trimMovesOneEdgeAndRipples() {
        val shorter = TimelineEditing.trim(state, 0, 1.0, 4.0, 10.0)!!
        assertEquals(listOf(1.0 to 4.0, 1.0 to 3.0), shorter.clips.map { it.start to it.end })
        assertEquals(a.id, shorter.clips[0].id)
        assertEquals(3.0, TimelineEditing.clipStart(shorter.clips, 1), 0.0)
        val longer = TimelineEditing.trim(state, 1, 1.0, 5.5, 6.0)!!
        assertEquals(1.0 to 5.5, longer.clips[1].let { it.start to it.end })
        assertEquals(2, longer.captureBase)
        assertNull(TimelineEditing.trim(state, 0, 0.0, 4.0, 10.0))
        assertNull(TimelineEditing.trim(state, 5, 0.0, 1.0, 10.0))
    }

    @Test fun trimStaysInsideTheSourceAndKeepsAMinimum() {
        fun bounds(clip: TimelineClip) = clip.start to clip.end
        assertEquals(0.0 to 3.0, bounds(TimelineEditing.trimmed(b, -5.0, 3.0, 6.0)))
        assertEquals(2.9 to 3.0, bounds(TimelineEditing.trimmed(b, 9.0, 3.0, 6.0)))
        assertEquals(1.0 to 6.0, bounds(TimelineEditing.trimmed(b, 1.0, 99.0, 6.0)))
        assertEquals(1.0 to 1.1, bounds(TimelineEditing.trimmed(b, 1.0, 0.0, 6.0)))
        assertEquals(b, TimelineEditing.trimmed(b, Double.NaN, 3.0, 6.0))
        // A source reported shorter than the clip never pulls the untouched end in.
        assertEquals(1.5 to 3.0, bounds(TimelineEditing.trimmed(b, 1.5, 3.0, 2.0)))
        val project = Project("x").addRecording(SourceMedia(filename = "a.mp4", duration = 4.0))
        project.commit(TimelineEditing.trim(project.edit, 0, 0.5, 99.0, 4.0)!!).validate()
    }
}

class TransitionAndTitleTest {
    private val a = TimelineClip(sourceID = "A", start = 0.0, end = 4.0)
    private val b = TimelineClip(sourceID = "B", start = 1.0, end = 3.0)
    private val c = TimelineClip(sourceID = "C", start = 0.0, end = 0.4)
    private val state = EditState(listOf(a, b, c))

    @Test fun transitionBelongsToItsNeighbors() {
        val joined = TimelineEditing.setTransition(state, 0, 1.0, TransitionStyle.FadeColor)!!
        assertEquals(b.id, joined.clips[0].transition!!.nextClipID)
        assertEquals(1.0, TimelineEditing.transition(joined.clips, 0)!!.duration, 0.0)
        assertNull(TimelineEditing.setTransition(joined, 0, 1.0, TransitionStyle.FadeColor))
        assertNull(TimelineEditing.setTransition(state, 2, 1.0, TransitionStyle.FadeColor)) // nothing after the last clip
        assertNull(TimelineEditing.setTransition(state, 0, 0.0, TransitionStyle.FadeColor))
        assertNull(TimelineEditing.setTransition(state, 0, null, TransitionStyle.FadeColor))
        // Changing the duration keeps the transition's identity.
        val longer = TimelineEditing.setTransition(joined, 0, 2.0, TransitionStyle.FadeColor)!!
        assertEquals(joined.clips[0].transition!!.id, longer.clips[0].transition!!.id)
        assertNull(TimelineEditing.setTransition(joined, 0, null, TransitionStyle.FadeColor)!!.clips[0].transition)
        // Moving or deleting a neighbor removes the join instead of passing it on.
        assertNull(TimelineEditing.move(joined, 1, 2)!!.clips[0].transition)
        assertNull(TimelineEditing.delete(joined, 1)!!.clips[0].transition)
        assertEquals(joined.clips[0].transition, TimelineEditing.delete(joined, 2)!!.clips[0].transition)
        // Splitting the first clip hands the join to the piece that touches the next clip.
        val split = TimelineEditing.split(joined, 2.0)!!
        assertNull(split.clips[0].transition)
        assertEquals(b.id, TimelineEditing.transition(split.clips, 1)!!.nextClipID)
    }

    @Test fun fadesMeetAtBlackAndNeverOverlap() {
        val joined = TimelineEditing.setTransition(TimelineEditing.setTransition(state, 0, 1.0, TransitionStyle.FadeColor)!!, 1, 1.0, TransitionStyle.FadeColor)!!.clips
        // Each side of a fade takes half of it; the second one is shortened to what the 0.4 s clip has.
        assertEquals(0.5, TimelineEditing.join(joined, 1)!!.timing.motion / 2, 0.0)
        assertEquals(0.4, TimelineEditing.join(joined, 2)!!.timing.motion / 2, 1e-9)
        assertNull(TimelineEditing.join(joined, 3))
        assertEquals(0.0, TimelineEditing.overlap(joined, 0), 0.0)
        assertEquals(6.4, TimelineEditing.duration(joined), 1e-12)
        fun level(index: Int, offset: Double): Double {
            val moment = TransitionScene.moment(joined, TimelineEditing.clipStart(joined, index) + offset)
            val frame = moment.frame as? TransitionFrame.FadeColor ?: return 1.0
            return if ((moment.first as? TransitionScene.Source.Clip)?.index == index) frame.first else frame.second
        }
        assertEquals(1.0, level(0, 0.0), 0.0)   // no fade at the very start
        assertEquals(1.0, level(0, 3.5), 0.0)
        assertEquals(0.5, level(0, 3.75), 1e-12)
        assertEquals(0.0, level(1, 0.0), 0.0)
        assertEquals(0.5, level(1, 0.25), 1e-12)
        assertEquals(1.0, level(1, 1.0), 0.0)
        assertEquals(0.5, level(1, 1.8), 1e-9)
        assertEquals(0.5, level(2, 0.2), 1e-9)
        // Sound follows the picture down to silence and back.
        assertEquals(1.0, TimelineEditing.soundLevel(joined, 0, 3.5), 0.0)
        assertEquals(0.5, TimelineEditing.soundLevel(joined, 0, 3.75), 1e-12)
        assertEquals(0.0, TimelineEditing.soundLevel(joined, 0, 4.0), 0.0)
        assertEquals(0.0, TimelineEditing.soundLevel(joined, 1, 0.0), 0.0)
        assertEquals(0.5, TimelineEditing.soundLevel(joined, 1, 0.25), 1e-12)
        assertEquals(1.0, TimelineEditing.soundLevel(joined, 1, 1.0), 0.0)
        assertEquals(0.0, TimelineEditing.soundLevel(joined, 1, 2.0), 1e-9)
        assertEquals(0.5, TimelineEditing.soundLevel(joined, 2, 0.2), 1e-9)
        assertEquals(1.0, TimelineEditing.soundLevel(joined, 2, 0.4), 1e-9)   // the movie does not end on a fade
    }

    @Test fun titlesFollowTheirFootage() {
        val title = TextOverlay(text = "Coast", start = 1.5, end = 2.5)
        val titled = TimelineEditing.setText(state, 1, title)!!
        assertEquals(listOf(title), TimelineEditing.textsAt(titled.clips[1], 1.5))
        assertEquals(emptyList<TextOverlay>(), TimelineEditing.textsAt(titled.clips[1], 2.5))
        assertNull(TimelineEditing.setText(titled, 1, title))
        assertNull(TimelineEditing.setText(state, 1, title.copy(end = 1.0)))
        assertNull(TimelineEditing.setText(state, 1, title.copy(text = "x".repeat(501))))
        val edited = TimelineEditing.setText(titled, 1, title.copy(text = "Sicilian coast"))!!
        assertEquals(listOf("Sicilian coast"), edited.clips[1].texts!!.map { it.text })
        assertEquals(titled.clips[1].texts, TimelineEditing.move(titled, 1, 0)!!.clips[0].texts)
        // A split leaves every title on both pieces, as the original does, whether a piece shows it or not.
        val through = TimelineEditing.split(titled, 4.0 + 1.0)!! // source time 2.0, inside the title
        assertEquals(listOf(title), through.clips[1].texts)
        assertEquals(listOf(title), through.clips[2].texts)
        assertEquals(5.0..5.5, TimelineEditing.titleRange(through.clips, 2, title))
        val after = TimelineEditing.split(titled, 4.0 + 1.8)!!   // source time 2.8, after the title
        assertEquals(listOf(title), after.clips[1].texts)
        assertEquals(listOf(title), after.clips[2].texts)
        // The second piece plays none of the title's footage: nothing is drawn, listed or moved there.
        assertEquals(emptyList<TextOverlay>(), TimelineEditing.textsAt(after.clips[2], 2.8))
        assertNull(TimelineEditing.titleRange(after.clips, 2, title))
        assertEquals(listOf(title to 1.0), TimelineEditing.titlesAt(after.clips, 4.0 + 1.2))
        assertEquals(emptyList<Pair<TextOverlay, Double>>(), TimelineEditing.titlesAt(after.clips, 4.0 + 1.9))
        val moving = title.copy(motion = TitleMotion(entrance = TitleMove.Fade))
        val parted = TimelineEditing.split(TimelineEditing.setText(state, 1, moving)!!, 4.0 + 1.8)!!.clips
        assertEquals(4.5..5.5, TitleMotionRanges(parted).range(moving, 5.0))
        assertNull(TitleMotionRanges(parted).range(moving, 5.9))
        // Extended back over that footage, the piece shows the title again.
        val extended = TimelineEditing.trim(after, 2, 2.0, after.clips[2].end, 10.0)!!
        assertEquals(listOf(title), TimelineEditing.textsAt(extended.clips[2], 2.2))
        assertEquals(5.8..6.3, TimelineEditing.titleRange(extended.clips, 2, title)!!.let { Math.round(it.start * 1e6) / 1e6..Math.round(it.endInclusive * 1e6) / 1e6 })
        assertNull(TimelineEditing.removeText(titled, 1, "missing"))
        assertNull(TimelineEditing.removeText(titled, 1, title.id)!!.clips[1].texts)
    }

    @Test fun projectsWithTitlesNeedTheNewSchemaAndStayValid() {
        val take = SourceMedia(filename = "a.mp4", duration = 4.0)
        var project = Project("Trip").addRecording(take).addRecording(SourceMedia(filename = "b.mp4", duration = 2.0))
        assertEquals(1, project.schemaVersion)
        val plain = project.snapshots[0].data().decodeToString()
        assertEquals("{\"state\":{\"clips\":[]},\"captureCursor\":0}", plain) // unchanged bytes for old history
        project = LegacyProject.commit(project, TimelineEditing.setTransition(project.edit, 0, 0.5, TransitionStyle.FadeColor)!!)
        assertEquals(2, project.schemaVersion)
        project = LegacyProject.commit(project, TimelineEditing.setText(project.edit, 0, TextOverlay(text = "Hi", start = 0.0, end = 4.0))!!)
        project.validate()
        val files = mutableMapOf<String, ByteArray>()
        val manifest = project.manifest { name, data -> files[name] = data }
        assertEquals(project, Project.readManifest(manifest) { files.getValue(it) })
        val late = TimelineEditing.setText(project.edit, 0, TextOverlay(start = 3.0, end = 9.0))!!
        org.junit.Assert.assertThrows(ProjectException::class.java) { LegacyProject.commit(project, late).validate() }
    }
}

class DissolveTest {
    private val a = TimelineClip(sourceID = "A", start = 0.0, end = 4.0)
    private val b = TimelineClip(sourceID = "B", start = 1.0, end = 3.0)
    private val c = TimelineClip(sourceID = "C", start = 0.0, end = 3.0)
    private val d = TimelineClip(sourceID = "D", start = 0.0, end = 1.0)
    private val plain = EditState(listOf(a, b, c, d))
    // a ~dissolve 1s~ b | cut | c ~dissolve 2s, shortened to the 1 s that d has~ d
    private val state = TimelineEditing.setTransition(
        TimelineEditing.setTransition(plain, 0, 1.0, TransitionStyle.Dissolve)!!, 2, 2.0, TransitionStyle.Dissolve)!!
    private val clips = state.clips

    @Test fun overlapShortensTheTimeline() {
        assertEquals(1.0, TimelineEditing.overlap(clips, 0), 0.0)
        assertEquals(0.0, TimelineEditing.overlap(clips, 1), 0.0)
        assertEquals(1.0, TimelineEditing.overlap(clips, 2), 1e-9)
        assertEquals(0.5, TimelineEditing.join(clips, 3)!!.factor, 1e-9)
        assertEquals(2.0, clips[2].transition!!.duration, 0.0) // the setting stays as entered
        assertEquals(listOf(0.0, 3.0, 5.0, 7.0), clips.indices.map { Math.round(TimelineEditing.clipStart(clips, it) * 1e6) / 1e6 })
        assertEquals(8.0, state.duration, 1e-9)
        assertEquals(10.0, plain.duration, 0.0)
    }

    @Test fun theMiddleOfADissolveIsTheBoundary() {
        assertEquals(Location(0, 2.9), TimelineEditing.locate(clips, 2.9))
        assertEquals(Location(0, 3.25), TimelineEditing.locate(clips, 3.25))
        assertEquals(Location(1, 0.5), TimelineEditing.locate(clips, 3.5))
        assertEquals(Location(2, 0.0), TimelineEditing.locate(clips, 5.0))
        assertEquals(2, TimelineEditing.locate(clips, 7.25)!!.index)
        assertEquals(3, TimelineEditing.locate(clips, 7.75)!!.index)
        assertEquals(0.75, TimelineEditing.locate(clips, 7.75)!!.offset, 1e-9)
        assertEquals(1.0, TimelineEditing.locate(clips, 8.0)!!.offset, 1e-9)
    }

    @Test fun sharesCrossEasedAndAlwaysSumToOne() {
        fun share(time: Double) = TransitionScene.moment(clips, time)
        assertEquals(TransitionFrame.First, share(0.0).frame)
        assertEquals(TransitionFrame.First, share(3.0).frame)
        // A quarter of the way the eased share of the incoming clip is 0.25² · (3 − 0.5).
        assertEquals(0.15625, (share(3.25).frame as TransitionFrame.Dissolve).second, 1e-12)
        assertEquals(0.5, (share(3.5).frame as TransitionFrame.Dissolve).second, 1e-12)
        assertEquals(0.84375, (share(3.75).frame as TransitionFrame.Dissolve).second, 1e-12)
        assertEquals(TransitionFrame.First, share(5.0).frame) // a cut, not a dissolve
        for (time in listOf(3.0, 3.3, 3.99, 7.0, 7.1, 7.99)) {
            val moment = share(time)
            assertEquals(1.0, moment.firstShare + moment.secondShare, 1e-12)
            if (moment.join != null) {
                assertEquals(moment.join!!.index - 1, (moment.first as TransitionScene.Source.Clip).index)
                assertEquals(moment.join!!.index, (moment.second as TransitionScene.Source.Clip).index)
            }
        }
        // The two clips of a dissolve are on different decks; clips joined by a cut share one.
        assertEquals(listOf(0, 1, 1, 0), TransitionScene.decks(clips))
    }

    @Test fun chainsOverlapByTheDissolve() {
        val chains = TimelineEditing.chains(clips)
        assertEquals(listOf(0 to 0, 1 to 2, 3 to 3), chains.map { it.first to it.last })
        assertEquals(listOf(0.0, 3.0, 7.0), chains.map { Math.round(it.start * 1e6) / 1e6 })
        assertEquals(listOf(4.0, 8.0, 8.0), chains.map { Math.round(it.end * 1e6) / 1e6 })
        assertEquals(listOf(TimelineEditing.Chain(0, 3, 0.0, 10.0)), TimelineEditing.chains(plain.clips))
        assertEquals(emptyList<TimelineEditing.Chain>(), TimelineEditing.chains(emptyList()))
    }

    @Test fun noSplitInsideADissolve() {
        assertNull(TimelineEditing.split(state, 3.5))  // a and b are both showing
        assertNull(TimelineEditing.split(state, 7.7))  // c and d are both showing
        val split = TimelineEditing.split(state, 2.0)!!
        assertEquals(1.0, TimelineEditing.overlap(split.clips, 1), 0.0) // the dissolve stays with the piece next to b
        assertEquals(state.duration, split.duration, 1e-12)
    }

    @Test fun titlesOfBothClipsShowThroughADissolve() {
        val early = TextOverlay(text = "A", start = 0.0, end = 4.0)
        val late = TextOverlay(text = "B", start = 1.0, end = 1.5)
        val titled = TimelineEditing.setText(TimelineEditing.setText(state, 0, early)!!, 1, late)!!.clips
        assertEquals(listOf(early to 1.0), TimelineEditing.titlesAt(titled, 2.0))
        assertEquals(listOf(early to 0.84375, late to 0.15625), TimelineEditing.titlesAt(titled, 3.25))
        assertEquals(listOf(early to 0.15625), TimelineEditing.titlesAt(titled, 3.75)) // B's title has ended, A is still leaving
        assertEquals(emptyList<Pair<TextOverlay, Double>>(), TimelineEditing.titlesAt(titled, 4.5))
        assertEquals(emptyList<Pair<TextOverlay, Double>>(), TimelineEditing.titlesAt(emptyList(), 0.0))
    }

    @Test fun changingStyleKeepsTheJoin() {
        val faded = TimelineEditing.setTransition(state, 0, 1.0, TransitionStyle.FadeColor)!!
        assertEquals(clips[0].transition!!.id, faded.clips[0].transition!!.id)
        assertEquals(0.0, TimelineEditing.overlap(faded.clips, 0), 0.0)
        assertEquals(1.0, TimelineEditing.join(faded.clips, 1)!!.timing.motion, 0.0)
        assertEquals(9.0, faded.duration, 1e-9)
        val project = Project("x").addRecording(SourceMedia(filename = "a.mp4", duration = 4.0)).addRecording(SourceMedia(filename = "b.mp4", duration = 4.0))
        val saved = project.commit(TimelineEditing.setTransition(project.edit, 0, 1.0, TransitionStyle.Dissolve)!!)
        saved.validate()
        val files = mutableMapOf<String, ByteArray>()
        val manifest = saved.manifest { name, data -> files[name] = data }
        assertEquals(7.0, Project.readManifest(manifest) { files.getValue(it) }.edit.duration, 0.0)
    }
}

class BookmarkTest {
    private val a = TimelineClip(sourceID = "A", start = 1.0, end = 5.0)
    private val b = TimelineClip(sourceID = "B", start = 0.0, end = 3.0)
    private val state = EditState(listOf(a, b))

    @Test fun toggleAddsThenRemoves() {
        val marked = TimelineEditing.toggleBookmark(state, 0, 2.0)!!
        assertEquals(listOf(2.0), marked.clips[0].bookmarks!!.map { it.time })
        assertEquals(listOf(1.0), TimelineEditing.bookmarkTimes(marked.clips)) // source 2.0 shows one second into the clip
        // Close enough to the mark removes it; farther away adds another, kept in order.
        assertNull(TimelineEditing.toggleBookmark(marked, 0, 2.1)!!.clips[0].bookmarks)
        assertEquals(listOf(1.5, 2.0), TimelineEditing.toggleBookmark(marked, 0, 1.5)!!.clips[0].bookmarks!!.map { it.time })
        assertNull(TimelineEditing.toggleBookmark(state, 0, 0.5)) // outside the clip
        assertNull(TimelineEditing.toggleBookmark(state, 5, 1.0))
    }

    @Test fun navigationSkipsTheMarkUnderThePlayhead() {
        var marked = TimelineEditing.toggleBookmark(state, 0, 2.0)!!   // timeline 1.0
        marked = TimelineEditing.toggleBookmark(marked, 1, 0.5)!!      // timeline 4.5
        marked = TimelineEditing.toggleBookmark(marked, 1, 2.5)!!      // timeline 6.5
        val clips = marked.clips
        assertEquals(listOf(1.0, 4.5, 6.5), TimelineEditing.bookmarkTimes(clips))
        assertEquals(1.0, TimelineEditing.nextBookmark(clips, 0.0)!!, 0.0)
        assertEquals(4.5, TimelineEditing.nextBookmark(clips, 1.0)!!, 0.0)
        assertNull(TimelineEditing.nextBookmark(clips, 6.5))
        assertEquals(4.5, TimelineEditing.previousBookmark(clips, 6.5)!!, 0.0)
        assertNull(TimelineEditing.previousBookmark(clips, 1.0))
    }

    @Test fun marksFollowTheirFootage() {
        val marked = TimelineEditing.toggleBookmark(TimelineEditing.toggleBookmark(state, 0, 2.0)!!, 0, 4.0)!!
        val split = TimelineEditing.split(marked, 2.0)!! // source 3.0
        assertEquals(listOf(2.0), split.clips[0].bookmarks!!.map { it.time })
        assertEquals(listOf(4.0), split.clips[1].bookmarks!!.map { it.time })
        assertEquals(listOf(1.0, 3.0), TimelineEditing.bookmarkTimes(split.clips))
        // A mark trimmed out of sight is kept with its footage. The head of the first clip has no room before it to be
        // extended into (TrackTimeline.swift:1636).
        val trimmed = TimelineEditing.trim(marked, 0, 2.5, 5.0, 10.0)!!
        assertEquals(listOf(1.5), TimelineEditing.bookmarkTimes(trimmed.clips))
        assertNull(TimelineEditing.trim(trimmed, 0, 1.0, 5.0, 10.0))
        assertEquals(listOf(4.0, 6.0), TimelineEditing.bookmarkTimes(TimelineEditing.move(marked, 0, 1)!!.clips))
    }

    @Test fun recordingMarksArriveOnTheTimeline() {
        val take = SourceMedia(filename = "a.mp4", duration = 6.0, events = listOf(
            CaptureEvent(time = 1.0, kind = "camera", detail = "Front"),
            CaptureEvent(time = 2.5, kind = Project.BOOKMARK_EVENT, detail = ""),
        ))
        var project = Project("Trip").addRecording(SourceMedia(filename = "b.mp4", duration = 2.0))
        assertEquals(1, project.schemaVersion)
        project = project.addRecording(take)
        assertEquals(3, project.schemaVersion) // older readers would silently drop the marks
        assertEquals(listOf(4.5), TimelineEditing.bookmarkTimes(project.edit.clips))
        project.validate()
        val files = mutableMapOf<String, ByteArray>()
        val manifest = project.manifest { name, data -> files[name] = data }
        assertEquals(project, Project.readManifest(manifest) { files.getValue(it) })
        val late = project.edit.clips.toMutableList().also { it[1] = it[1].copy(bookmarks = listOf(Bookmark(time = 9.0))) }
        org.junit.Assert.assertThrows(ProjectException::class.java) { project.commit(EditState(late, 2)).validate() }
    }
}

class ClipAudioTest {
    private val a = TimelineClip(sourceID = "A", start = 0.0, end = 4.0)
    private val b = TimelineClip(sourceID = "B", start = 0.0, end = 4.0)
    private val state = EditState(listOf(a, b))

    @Test fun levelIsDecibelsAndMuteIsSilence() {
        assertEquals(1.0, ClipAudio().gain, 0.0)
        assertEquals(0.5012, ClipAudio(volumeDB = -6.0).gain, 1e-4)
        assertEquals(3.981, ClipAudio(volumeDB = 12.0).gain, 1e-3)
        assertEquals(0.0, ClipAudio(volumeDB = 12.0, muted = true).gain, 0.0)
        assertNull(TimelineEditing.setAudio(state, 0, ClipAudio(volumeDB = 13.0)))
        assertNull(TimelineEditing.setAudio(state, 0, ClipAudio(fadeIn = -1.0)))
        assertNull(TimelineEditing.setAudio(state, 0, ClipAudio()))  // nothing to change
        val quiet = TimelineEditing.setAudio(state, 0, ClipAudio(volumeDB = -6.0))!!
        assertNull(TimelineEditing.setAudio(quiet, 0, ClipAudio())!!.clips[0].audio) // back to the default stores nothing
    }

    @Test fun fadesShapeTheSound() {
        val clips = TimelineEditing.setAudio(state, 0, ClipAudio(volumeDB = -6.0, fadeIn = 1.0, fadeOut = 2.0))!!.clips
        val gain = ClipAudio(volumeDB = -6.0).gain
        assertEquals(0.0, TimelineEditing.soundLevel(clips, 0, 0.0), 0.0)
        assertEquals(gain / 2, TimelineEditing.soundLevel(clips, 0, 0.5), 1e-12)
        assertEquals(gain, TimelineEditing.soundLevel(clips, 0, 1.5), 1e-12)
        assertEquals(gain / 2, TimelineEditing.soundLevel(clips, 0, 3.0), 1e-12)
        assertEquals(1.0, TimelineEditing.soundLevel(clips, 1, 2.0), 0.0)
        assertEquals(0.0, TimelineEditing.soundLevel(clips, 5, 0.0), 0.0)
        // A fade may be as long as the clip and no longer, as the original sets one (TrackTimeline.swift:191-195): where two
        // cross, the lower counts (CutAudioEnvelope.swift:38, TrackTimeline.swift:150).
        val long = TimelineEditing.setAudio(state, 0, ClipAudio(fadeIn = 4.0, fadeOut = 10.0))!!.clips
        assertEquals(4.0, long[0].audio!!.fadeOut, 0.0)
        assertEquals(0.5, TimelineEditing.soundLevel(long, 0, 2.0), 1e-12)
        assertEquals(0.25, TimelineEditing.soundLevel(long, 0, 1.0), 1e-12)
        assertEquals(0.25, TimelineEditing.soundLevel(long, 0, 3.0), 1e-12)
    }

    @Test fun soundFollowsTransitionsToo() {
        var clips = TimelineEditing.setAudio(state, 0, ClipAudio(volumeDB = 6.0))!!
        clips = TimelineEditing.setTransition(clips, 0, 2.0, TransitionStyle.Dissolve)!!
        val boosted = ClipAudio(volumeDB = 6.0).gain
        assertEquals(boosted, TimelineEditing.soundLevel(clips.clips, 0, 1.0), 1e-12)
        assertEquals(boosted / 2, TimelineEditing.soundLevel(clips.clips, 0, 3.0), 1e-12) // half way through the dissolve
        assertEquals(0.5, TimelineEditing.soundLevel(clips.clips, 1, 1.0), 1e-12)
        val faded = TimelineEditing.setTransition(state, 0, 2.0, TransitionStyle.FadeColor)!!.clips
        assertEquals(0.5, TimelineEditing.soundLevel(faded, 0, 3.5), 1e-12)
    }

    @Test fun splitKeepsEachFadeAtItsEnd() {
        val shaped = TimelineEditing.setAudio(state, 0, ClipAudio(volumeDB = -3.0, fadeIn = 1.0, fadeOut = 1.0))!!
        val split = TimelineEditing.split(shaped, 2.0)!!.clips
        assertEquals(ClipAudio(volumeDB = -3.0, fadeIn = 1.0), split[0].audio)
        assertEquals(ClipAudio(volumeDB = -3.0, fadeOut = 1.0), split[1].audio)
        val plain = TimelineEditing.split(TimelineEditing.setAudio(state, 0, ClipAudio(fadeIn = 1.0))!!, 2.0)!!.clips
        assertNull(plain[1].audio)
    }

    @Test fun noiseReductionIsAnAmountAndNeedsSchema6() {
        assertNull(TimelineEditing.setAudio(state, 0, ClipAudio(denoise = 1.5)))
        assertNull(TimelineEditing.setAudio(state, 0, ClipAudio(denoise = Double.NaN)))
        val cleaned = TimelineEditing.setAudio(state, 0, ClipAudio(denoise = 0.5))!!
        assertEquals(6, cleaned.clips[0].schema)
        assertEquals(1.0, TimelineEditing.soundLevel(cleaned.clips, 0, 2.0), 0.0) // it changes the sound, not its level
        // Both pieces of a split stay cleaned.
        assertEquals(listOf(0.5, 0.5), TimelineEditing.split(cleaned, 2.0)!!.clips.take(2).map { it.audio!!.denoise })
        var project = Project("x").addRecording(SourceMedia(filename = "a.mp4", duration = 4.0))
        project = LegacyProject.commit(project, TimelineEditing.setAudio(project.edit, 0, ClipAudio(denoise = 1.0))!!)
        assertEquals(6, project.schemaVersion)
        project.validate()
    }

    @Test fun projectsWithClipSoundUseSchema4() {
        var project = Project("x").addRecording(SourceMedia(filename = "a.mp4", duration = 4.0))
        project = LegacyProject.commit(project, TimelineEditing.setAudio(project.edit, 0, ClipAudio(muted = true))!!)
        assertEquals(4, project.schemaVersion)
        project.validate()
        val files = mutableMapOf<String, ByteArray>()
        val manifest = project.manifest { name, data -> files[name] = data }
        assertEquals(project, Project.readManifest(manifest) { files.getValue(it) })
    }
}

class AudioItemTest {
    private val clip = TimelineClip(sourceID = "A", start = 0.0, end = 20.0)
    private val state = EditState(listOf(clip))

    @Test fun itemsTakeTheFirstFreeTrack() {
        val music = TimelineEditing.addAudio(state, "M", 10.0, 2.0)!!
        assertEquals(listOf(0), music.audio!!.map { it.track })
        assertEquals(12.0, music.audio!![0].end, 0.0)
        val voice = TimelineEditing.addAudio(music, "V", 3.0, 5.0)!!   // overlaps the music: second track
        assertEquals(listOf(0, 1), voice.audio!!.map { it.track })
        assertNull(TimelineEditing.addAudio(voice, "X", 3.0, 6.0))     // both tracks busy there
        assertEquals(0, TimelineEditing.addAudio(voice, "X", 3.0, 12.0)!!.audio!!.last().track) // touching is not overlapping
        assertNull(TimelineEditing.addAudio(state, "M", 10.0, -1.0))
        assertNull(TimelineEditing.addAudio(state, "M", 0.0, 1.0))
        assertEquals(state.duration, voice.duration, 0.0)              // sound does not lengthen the movie
    }

    @Test fun moveAndTrimKeepTheSoundInPlace() {
        val added = TimelineEditing.addAudio(state, "M", 10.0, 2.0)!!
        val id = added.audio!![0].id
        assertEquals(4.0, TimelineEditing.moveAudio(added, id, 4.0)!!.audio!![0].start, 0.0)
        assertNull(TimelineEditing.moveAudio(added, id, 2.0))
        assertNull(TimelineEditing.moveAudio(added, "missing", 4.0))
        // Trimming the start to 5.0 drops the first three seconds; the rest still plays at the same moments.
        val head = TimelineEditing.trimAudio(added, id, 5.0, leading = true)!!.audio!![0]
        assertEquals(listOf(5.0, 3.0, 10.0), listOf(head.start, head.sourceStart, head.sourceEnd))
        val tail = TimelineEditing.trimAudio(added, id, 5.0, leading = false)!!.audio!![0]
        assertEquals(listOf(2.0, 0.0, 3.0), listOf(tail.start, tail.sourceStart, tail.sourceEnd))
        assertNull(TimelineEditing.trimAudio(added, id, 2.0, leading = true))   // at its edge: nothing to cut
        assertNull(TimelineEditing.trimAudio(added, id, 30.0, leading = false)) // outside it
        // A move onto a busy stretch changes track; when no track is free it is refused.
        val two = TimelineEditing.addAudio(added, "V", 3.0, 14.0)!!
        val voice = two.audio!!.first { it.sourceID == "V" }
        assertEquals(1, TimelineEditing.moveAudio(two, voice.id, 5.0)!!.audio!!.first { it.id == voice.id }.track)
    }

    @Test fun soundSettingsAndLookup() {
        val added = TimelineEditing.addAudio(state, "M", 10.0, 2.0)!!
        val id = added.audio!![0].id
        val quiet = TimelineEditing.setAudioItemSound(added, id, ClipAudio(volumeDB = -6.0, fadeIn = 2.0))!!
        val item = quiet.audio!![0]
        assertEquals(ClipAudio(volumeDB = -6.0).gain / 2, TimelineEditing.soundLevel(item, 1.0), 1e-12)
        assertEquals(ClipAudio(volumeDB = -6.0).gain, TimelineEditing.soundLevel(item, 5.0), 1e-12)
        assertNull(TimelineEditing.setAudioItemSound(quiet, id, ClipAudio(volumeDB = -6.0, fadeIn = 2.0)))
        assertNull(TimelineEditing.setAudioItemSound(quiet, id, ClipAudio())!!.audio!![0].audio)
        assertEquals(item, TimelineEditing.audioAt(quiet.audio!!, 0, 2.0))
        assertNull(TimelineEditing.audioAt(quiet.audio!!, 0, 12.0))
        assertNull(TimelineEditing.audioAt(quiet.audio!!, 1, 5.0))
        assertNull(TimelineEditing.removeAudio(quiet, id)!!.audio)
        assertNull(TimelineEditing.removeAudio(state, id))
    }

    @Test fun projectKeepsSoundThroughUndoAndRecording() {
        val take = SourceMedia(filename = "a.mp4", duration = 20.0)
        val song = SourceMedia(filename = "song.m4a", duration = 10.0, audioOnly = true)
        var project = Project("x").addRecording(take).addSource(song)
        assertEquals(1, project.edit.clips.size)          // a sound file does not become a clip
        project = LegacyProject.commit(project, TimelineEditing.addAudio(project.edit, song.id, song.duration, 2.0)!!)
        assertEquals(5, project.schemaVersion)
        project.validate()
        assertEquals(1, project.edit.audio!!.size)
        // A take recorded afterwards joins the clips and leaves the sound alone.
        project = project.addRecording(SourceMedia(filename = "b.mp4", duration = 5.0))
        assertEquals(2, project.edit.clips.size)
        assertEquals(2.0, project.edit.audio!![0].start, 0.0)
        assertNull(project.undo().edit.audio)
        assertEquals(project.edit, project.undo().redo().edit)
        val files = mutableMapOf<String, ByteArray>()
        val manifest = project.manifest { name, data -> files[name] = data }
        assertEquals(project, Project.readManifest(manifest) { files.getValue(it) })
        // Overlapping items on one track are not a valid document.
        val broken = project.edit.audio!! + project.edit.audio!![0].copy(id = "other", start = 3.0)
        org.junit.Assert.assertThrows(ProjectException::class.java) { LegacyProject.commit(project, project.edit.copy(audio = broken)).validate() }
    }
}

class RemoveIntervalsTest {
    private val a = TimelineClip(sourceID = "A", start = 1.0, end = 11.0)
    private val b = TimelineClip(sourceID = "B", start = 0.0, end = 2.0)

    @Test fun pausesBecomeCutsAndTheGapsClose() {
        val state = EditState(listOf(a, b))
        val cut = TimelineEditing.removeIntervals(state, 0, listOf(SourceInterval(3.0, 4.5), SourceInterval(7.0, 8.0)))!!
        assertEquals(listOf(1.0 to 3.0, 4.5 to 7.0, 8.0 to 11.0, 0.0 to 2.0), cut.clips.map { it.start to it.end })
        assertEquals(state.duration - 2.5, cut.duration, 1e-12)
        assertEquals(a.id, cut.clips[0].id)
        assertEquals(4, cut.clips.map { it.id }.toSet().size)
        // Cuts outside the clip, or leaving it whole, change nothing.
        assertNull(TimelineEditing.removeIntervals(state, 0, listOf(SourceInterval(0.0, 0.5), SourceInterval(20.0, 30.0))))
        assertNull(TimelineEditing.removeIntervals(state, 0, emptyList()))
        assertNull(TimelineEditing.removeIntervals(state, 0, listOf(SourceInterval(0.0, 99.0)))) // would remove everything
        assertNull(TimelineEditing.removeIntervals(state, 7, listOf(SourceInterval(3.0, 4.0))))
    }

    @Test fun leadingAndTrailingSilenceAndSliversGo() {
        val state = EditState(listOf(a))
        // Silence at both ends, and a 0.05 s sliver between two pauses that cannot stand alone.
        val cut = TimelineEditing.removeIntervals(state, 0, listOf(SourceInterval(0.0, 2.0), SourceInterval(5.0, 6.0), SourceInterval(6.05, 7.0), SourceInterval(10.0, 12.0)))!!
        assertEquals(listOf(2.0 to 5.0, 7.0 to 10.0), cut.clips.map { it.start to it.end })
    }

    @Test fun everythingAttachedToTheClipStaysWithItsFootage() {
        var state = EditState(listOf(a, b))
        state = TimelineEditing.setTransition(state, 0, 1.0, TransitionStyle.FadeColor)!!
        state = TimelineEditing.setText(state, 0, TextOverlay(text = "T", start = 2.0, end = 5.0))!!
        state = TimelineEditing.toggleBookmark(state, 0, 9.0)!!
        state = TimelineEditing.setAudio(state, 0, ClipAudio(volumeDB = -3.0, fadeIn = 1.0, fadeOut = 1.0, denoise = 1.0))!!
        val cut = TimelineEditing.removeIntervals(state, 0, listOf(SourceInterval(3.0, 4.0), SourceInterval(6.0, 8.0)))!!.clips
        assertEquals(4, cut.size)
        assertNull(cut[0].transition); assertNull(cut[1].transition)
        assertEquals(b.id, TimelineEditing.transition(cut, 2)!!.nextClipID) // the join to the next clip moves to the last piece
        assertEquals(listOf(1, 1, 1), cut.take(3).map { it.texts.orEmpty().size })  // every piece keeps the title
        // It shows where its footage is played: across the first cut, and nowhere on the last piece.
        assertEquals(listOf(true, true, false), (0..2).map { TimelineEditing.titleRange(cut, it, cut[it].texts!![0]) != null })
        assertEquals(1.0, TimelineEditing.titlesAt(cut, 1.5).single().second, 0.0)
        assertEquals(1.0, TimelineEditing.titlesAt(cut, 2.5).single().second, 0.0)
        assertTrue(TimelineEditing.titlesAt(cut, 3.5).isEmpty())
        assertEquals(listOf(0, 0, 1), cut.take(3).map { it.bookmarks.orEmpty().size })
        assertEquals(ClipAudio(volumeDB = -3.0, fadeIn = 1.0, denoise = 1.0), cut[0].audio)
        assertEquals(ClipAudio(volumeDB = -3.0, denoise = 1.0), cut[1].audio)
        assertEquals(ClipAudio(volumeDB = -3.0, fadeOut = 1.0, denoise = 1.0), cut[2].audio)
        val project = Project("x").addRecording(SourceMedia(filename = "a.mp4", duration = 11.0)).addRecording(SourceMedia(filename = "b.mp4", duration = 2.0))
        val edit = project.edit
        val removed = TimelineEditing.removeIntervals(edit, 0, listOf(SourceInterval(3.0, 4.0)))!!
        project.commit(removed).validate()
    }
}

class WaveformTest {
    @Test fun onePeakPerSliceAcrossChannels() {
        val builder = WaveformBuilder(sampleRate = 100, channels = 2, peaksPerSecond = 10)
        val samples = ShortArray(100 * 2 * 2) // two seconds, stereo
        samples[3] = 16384               // slice 0, right channel
        samples[2 * 15] = -32768         // slice 1, left channel, most negative value
        samples[2 * 199 + 1] = 32767     // last frame
        // Chunk boundaries must not matter, even inside a frame.
        builder.add(ShortBuffer.wrap(samples, 0, 33))
        builder.add(ShortBuffer.wrap(samples, 33, samples.size - 33))
        val peaks = builder.finish()
        assertEquals(20, peaks.size)
        assertEquals(127, peaks[0].toInt() and 0xFF)
        assertEquals(255, peaks[1].toInt() and 0xFF)
        assertEquals(0, peaks[2].toInt())
        assertEquals(255, peaks[19].toInt() and 0xFF)
        assertEquals(1f, WaveformBuilder.level(peaks, 0.1, 0.15, 10), 0f)
        assertEquals(0f, WaveformBuilder.level(peaks, 0.2, 1.85, 10), 0f)
        assertEquals(1f, WaveformBuilder.level(peaks, 1.8, 99.0, 10), 0f)
        assertEquals(0f, WaveformBuilder.level(ByteArray(0), 0.0, 1.0, 10), 0f)
    }

    @Test fun partialLastSliceIsKept() {
        val builder = WaveformBuilder(sampleRate = 48000, channels = 1)
        builder.add(ShortBuffer.wrap(ShortArray(48000 + 100) { 1000 }))
        assertEquals(51, builder.finish().size)
        assertEquals(0, WaveformBuilder(44100, 2).finish().size)
    }
}
