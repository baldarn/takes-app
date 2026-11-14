package app.takes.core

import app.takes.core.TimelineEditing.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertNull(TimelineEditing.split(state, 3.95))
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
        val joined = TimelineEditing.setTransition(state, 0, 1.0)!!
        assertEquals(b.id, joined.clips[0].transition!!.nextClipID)
        assertEquals(1.0, TimelineEditing.transition(joined.clips, 0)!!.duration, 0.0)
        assertNull(TimelineEditing.setTransition(joined, 0, 1.0))
        assertNull(TimelineEditing.setTransition(state, 2, 1.0)) // nothing after the last clip
        assertNull(TimelineEditing.setTransition(state, 0, 0.0))
        assertNull(TimelineEditing.setTransition(state, 0, null))
        // Changing the duration keeps the transition's identity.
        val longer = TimelineEditing.setTransition(joined, 0, 2.0)!!
        assertEquals(joined.clips[0].transition!!.id, longer.clips[0].transition!!.id)
        assertNull(TimelineEditing.setTransition(joined, 0, null)!!.clips[0].transition)
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
        val joined = TimelineEditing.setTransition(TimelineEditing.setTransition(state, 0, 1.0)!!, 1, 1.0)!!.clips
        assertEquals(0.5, TimelineEditing.fadeLength(joined, 0), 0.0)
        assertEquals(0.2, TimelineEditing.fadeLength(joined, 1), 1e-12) // limited by the 0.4 s clip
        assertEquals(0.0, TimelineEditing.fadeLength(joined, 2), 0.0)
        assertEquals(1.0, TimelineEditing.level(joined, 0, 0.0), 0.0)   // no fade at the very start
        assertEquals(1.0, TimelineEditing.level(joined, 0, 3.5), 0.0)
        assertEquals(0.5, TimelineEditing.level(joined, 0, 3.75), 1e-12)
        assertEquals(0.0, TimelineEditing.level(joined, 0, 4.0), 0.0)
        assertEquals(0.0, TimelineEditing.level(joined, 1, 0.0), 0.0)
        assertEquals(0.5, TimelineEditing.level(joined, 1, 0.25), 1e-12)
        assertEquals(1.0, TimelineEditing.level(joined, 1, 1.0), 0.0)
        assertEquals(0.0, TimelineEditing.level(joined, 1, 2.0), 1e-12)
        assertEquals(0.5, TimelineEditing.level(joined, 2, 0.1), 1e-12)
        assertEquals(1.0, TimelineEditing.level(joined, 2, 0.4), 0.0)   // the movie does not end on a fade
        assertEquals(1.0, TimelineEditing.level(joined, 9, 0.0), 0.0)
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
        // A split leaves the title on every piece that shows part of it.
        val through = TimelineEditing.split(titled, 4.0 + 1.0)!! // source time 2.0, inside the title
        assertEquals(listOf(title), through.clips[1].texts)
        assertEquals(listOf(title), through.clips[2].texts)
        val after = TimelineEditing.split(titled, 4.0 + 1.8)!!   // source time 2.8, after the title
        assertEquals(listOf(title), after.clips[1].texts)
        assertNull(after.clips[2].texts)
        assertNull(TimelineEditing.removeText(titled, 1, "missing"))
        assertNull(TimelineEditing.removeText(titled, 1, title.id)!!.clips[1].texts)
    }

    @Test fun projectsWithTitlesNeedTheNewSchemaAndStayValid() {
        val take = SourceMedia(filename = "a.mp4", duration = 4.0)
        var project = Project("Trip").addRecording(take).addRecording(SourceMedia(filename = "b.mp4", duration = 2.0))
        assertEquals(1, project.schemaVersion)
        val plain = project.snapshots[0].data().decodeToString()
        assertEquals("{\"state\":{\"clips\":[]},\"captureCursor\":0}", plain) // unchanged bytes for old history
        project = project.commit(TimelineEditing.setTransition(project.edit, 0, 0.5)!!)
        assertEquals(2, project.schemaVersion)
        project = project.commit(TimelineEditing.setText(project.edit, 0, TextOverlay(text = "Hi", start = 0.0, end = 4.0))!!)
        project.validate()
        val files = mutableMapOf<String, ByteArray>()
        val manifest = project.manifest { name, data -> files[name] = data }
        assertEquals(project, Project.readManifest(manifest) { files.getValue(it) })
        val late = TimelineEditing.setText(project.edit, 0, TextOverlay(start = 3.0, end = 9.0))!!
        org.junit.Assert.assertThrows(ProjectException::class.java) { project.commit(late).validate() }
    }
}

class DissolveTest {
    private val a = TimelineClip(sourceID = "A", start = 0.0, end = 4.0)
    private val b = TimelineClip(sourceID = "B", start = 1.0, end = 3.0)
    private val c = TimelineClip(sourceID = "C", start = 0.0, end = 3.0)
    private val d = TimelineClip(sourceID = "D", start = 0.0, end = 1.0)
    private val plain = EditState(listOf(a, b, c, d))
    // a ~dissolve 1s~ b | cut | c ~dissolve 2s, limited to 0.5 by d~ d
    private val state = TimelineEditing.setTransition(
        TimelineEditing.setTransition(plain, 0, 1.0, ClipTransition.Style.Dissolve)!!, 2, 2.0, ClipTransition.Style.Dissolve)!!
    private val clips = state.clips

    @Test fun overlapShortensTheTimeline() {
        assertEquals(1.0, TimelineEditing.overlap(clips, 0), 0.0)
        assertEquals(0.0, TimelineEditing.overlap(clips, 1), 0.0)
        assertEquals(0.5, TimelineEditing.overlap(clips, 2), 0.0)
        assertEquals(0.0, TimelineEditing.fadeLength(clips, 0), 0.0)
        assertEquals(listOf(0.0, 3.0, 5.0, 7.5), clips.indices.map { TimelineEditing.clipStart(clips, it) })
        assertEquals(8.5, state.duration, 0.0)
        assertEquals(10.0, plain.duration, 0.0)
    }

    @Test fun incomingClipOwnsTheOverlap() {
        assertEquals(Location(0, 2.9), TimelineEditing.locate(clips, 2.9))
        assertEquals(Location(1, 0.0), TimelineEditing.locate(clips, 3.0))
        assertEquals(Location(1, 0.5), TimelineEditing.locate(clips, 3.5))
        assertEquals(Location(2, 0.0), TimelineEditing.locate(clips, 5.0))
        assertEquals(Location(3, 0.25), TimelineEditing.locate(clips, 7.75))
        assertEquals(Location(3, 1.0), TimelineEditing.locate(clips, 8.5))
    }

    @Test fun weightsCrossAndAlwaysSumToOne() {
        assertEquals(1.0, TimelineEditing.weight(clips, 0, 0.0), 0.0)
        assertEquals(1.0, TimelineEditing.weight(clips, 0, 3.0), 0.0)
        assertEquals(0.75, TimelineEditing.weight(clips, 0, 3.25), 1e-12)
        assertEquals(0.25, TimelineEditing.weight(clips, 1, 0.25), 1e-12)
        assertEquals(1.0, TimelineEditing.weight(clips, 1, 2.0), 0.0) // a cut, not a dissolve
        for (time in listOf(3.0, 3.3, 3.99, 7.5, 7.6, 7.99)) {
            val (index, offset) = TimelineEditing.locate(clips, time)!!
            val before = clips[index - 1].duration - TimelineEditing.overlap(clips, index - 1) + offset
            assertEquals(1.0, TimelineEditing.weight(clips, index, offset) + TimelineEditing.weight(clips, index - 1, before), 1e-12)
        }
    }

    @Test fun chainsOverlapByTheDissolve() {
        assertEquals(
            listOf(TimelineEditing.Chain(0, 0, 0.0, 4.0), TimelineEditing.Chain(1, 2, 3.0, 8.0), TimelineEditing.Chain(3, 3, 7.5, 8.5)),
            TimelineEditing.chains(clips),
        )
        assertEquals(listOf(TimelineEditing.Chain(0, 3, 0.0, 10.0)), TimelineEditing.chains(plain.clips))
        assertEquals(emptyList<TimelineEditing.Chain>(), TimelineEditing.chains(emptyList()))
    }

    @Test fun noSplitInsideADissolve() {
        assertNull(TimelineEditing.split(state, 3.5))  // b's incoming overlap
        assertNull(TimelineEditing.split(state, 7.7))  // d's incoming overlap
        val split = TimelineEditing.split(state, 2.0)!!
        assertEquals(1.0, TimelineEditing.overlap(split.clips, 1), 0.0) // the dissolve stays with the piece next to b
        assertEquals(state.duration, split.duration, 1e-12)
    }

    @Test fun titlesOfBothClipsShowThroughADissolve() {
        val early = TextOverlay(text = "A", start = 0.0, end = 4.0)
        val late = TextOverlay(text = "B", start = 1.0, end = 1.5)
        val titled = TimelineEditing.setText(TimelineEditing.setText(state, 0, early)!!, 1, late)!!.clips
        assertEquals(listOf(early to 1.0), TimelineEditing.titlesAt(titled, 2.0))
        assertEquals(listOf(early to 0.75, late to 0.25), TimelineEditing.titlesAt(titled, 3.25))
        assertEquals(listOf(early to 0.25), TimelineEditing.titlesAt(titled, 3.75)) // B has ended, A is still leaving
        assertEquals(emptyList<Pair<TextOverlay, Double>>(), TimelineEditing.titlesAt(titled, 4.5))
        assertEquals(emptyList<Pair<TextOverlay, Double>>(), TimelineEditing.titlesAt(emptyList(), 0.0))
    }

    @Test fun changingStyleKeepsTheJoin() {
        val faded = TimelineEditing.setTransition(state, 0, 1.0, ClipTransition.Style.FadeBlack)!!
        assertEquals(clips[0].transition!!.id, faded.clips[0].transition!!.id)
        assertEquals(0.0, TimelineEditing.overlap(faded.clips, 0), 0.0)
        assertEquals(0.5, TimelineEditing.fadeLength(faded.clips, 0), 0.0)
        assertEquals(9.5, faded.duration, 0.0)
        val project = Project("x").addRecording(SourceMedia(filename = "a.mp4", duration = 4.0)).addRecording(SourceMedia(filename = "b.mp4", duration = 4.0))
        val saved = project.commit(TimelineEditing.setTransition(project.edit, 0, 1.0, ClipTransition.Style.Dissolve)!!)
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
        // A mark trimmed out of sight is kept, and comes back when the clip is extended again.
        val trimmed = TimelineEditing.trim(marked, 0, 2.5, 5.0, 10.0)!!
        assertEquals(listOf(1.5), TimelineEditing.bookmarkTimes(trimmed.clips))
        assertEquals(listOf(1.0, 3.0), TimelineEditing.bookmarkTimes(TimelineEditing.trim(trimmed, 0, 1.0, 5.0, 10.0)!!.clips))
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
