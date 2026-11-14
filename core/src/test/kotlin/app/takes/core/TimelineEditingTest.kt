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
