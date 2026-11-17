package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

// YouRec/VideoEffects/VideoEffectSubjectTracking.swift:5-150, YouRec/VideoEffects/VideoEffectAssets.swift:476-486.
class EffectSubjectTrackingTest {
    private fun sample(time: Double, x: Double, y: Double = 0.4, lost: Boolean? = null) =
        EffectSubjectSample(time, x, y, 0.2, 0.2, 0.9, true, lost)

    // :25-42: between two samples the box moves as far as the time has gone; nothing past half a second or after a gap.
    @Test fun aTrackIsReadBetweenItsSamples() {
        val track = EffectSubjectTrack(listOf(sample(1.0, 0.1), sample(1.2, 0.3), sample(2.0, 0.5), sample(2.1, 0.6, lost = true), sample(2.2, 0.7)))
        assertNull(track.sample(0.99))
        assertEquals(0.2, track.sample(1.1)!!.x, 1e-12)
        assertEquals(1.1, track.sample(1.1)!!.time, 1e-12)
        // The next sample is 0.8 s on: the last one holds, for half a second.
        assertEquals(0.3, track.sample(1.6)!!.x, 1e-12)
        assertNull(track.sample(1.75))
        // Toward a lost sample nothing moves; at it, nothing at all.
        assertEquals(0.5, track.sample(2.05)!!.x, 1e-12)
        assertNull(track.sample(2.15))
        // Stepwise, each sample holds until the next.
        assertEquals(0.1, EffectSubjectTrack(track.samples, stepwise = true).sample(1.1)!!.x, 1e-12)
        assertEquals(Point(0.3, 0.5), sample(0.0, 0.2).center.let { Point(Math.round(it.x * 1e9) / 1e9, Math.round(it.y * 1e9) / 1e9) })
    }

    // :14-23: valid samples, in order, and back from their file.
    @Test fun aTrackIsKeptAndReadBack() {
        val track = EffectSubjectTrack(listOf(sample(0.0, 0.1), sample(0.5, 0.2)))
        assertTrue(track.valid)
        assertEquals(track, EffectSubjectTrack.decoded(track.encoded()))
        assertNull(EffectSubjectTrack.decoded(EffectSubjectTrack(listOf(sample(0.5, 0.1), sample(0.0, 0.2))).encoded()))
        assertNull(EffectSubjectTrack.decoded("{"))
        assertFalse(sample(0.0, 0.9).valid)
        assertTrue(EffectSubjectSample(0.0, 0.0, 0.5, 0.2, 0.2, 1.0, false).clipped)
    }

    private fun face(x: Double, y: Double, side: Double = 0.2, confidence: Double = 0.9) = EffectSubjectCandidate(Rect(x, y, side, side), confidence)

    // :74-81: at the start, the face under the point, else the nearest; without a point the largest.
    @Test fun theSubjectIsChosenFromThePoint() {
        val faces = listOf(face(0.1, 0.1), face(0.6, 0.6, 0.3), face(0.4, 0.1))
        assertEquals(0.4, EffectSubjectTracker(Point(0.45, 0.15)).update(0.0, { faces }, { emptyList() })!!.x, 1e-12)
        assertEquals(0.1, EffectSubjectTracker(Point(0.0, 0.0)).update(0.0, { faces }, { emptyList() })!!.x, 1e-12)
        assertEquals(0.6, EffectSubjectTracker().update(0.0, { faces }, { emptyList() })!!.x, 1e-12)
        // Under 0.5 a face is not considered.
        assertNull(EffectSubjectTracker(Point(0.5, 0.5)).update(0.0, { listOf(face(0.4, 0.4, confidence = 0.4)) }, { emptyList() }))
    }

    // :66-71: people only where no face is found.
    @Test fun peopleStandInForFaces() {
        var asked = false
        val tracker = EffectSubjectTracker(Point(0.5, 0.5))
        tracker.update(0.0, { listOf(face(0.4, 0.4)) }, { asked = true; emptyList() })
        assertFalse(asked)
        val person = EffectSubjectTracker(Point(0.5, 0.5)).update(0.0, { emptyList() }, { listOf(EffectSubjectCandidate(Rect(0.3, 0.2, 0.4, 0.7), 1.0)) })
        assertNotNull(person)
        assertFalse(person!!.face)
    }

    // :72-73, :97-102: the one followed is kept by its overlap, and its centre follows with a time constant of 0.12 s.
    @Test fun theSubjectFollowedIsKeptAndSmoothed() {
        val tracker = EffectSubjectTracker(Point(0.15, 0.15))
        val first = tracker.update(0.0, { listOf(face(0.1, 0.1), face(0.6, 0.6)) }, { emptyList() })!!
        assertEquals(0.1, first.x, 1e-12)
        // The face moved a little; a larger one now covers the point, and is not taken: the one overlapping most is.
        val step = 1.0 / 15
        val second = tracker.update(step, { listOf(face(0.05, 0.05, 0.25), face(0.14, 0.1)) }, { emptyList() })!!
        val alpha = 1 - exp(-step / 0.12)
        assertTrue(EffectSubjectTracker.overlap(Rect(0.14, 0.1, 0.2, 0.2), first.box) > EffectSubjectTracker.overlap(Rect(0.05, 0.05, 0.25, 0.25), first.box))
        val centre = 0.2 + (0.24 - 0.2) * alpha
        assertEquals(centre - 0.1, second.x, 1e-12)
        assertEquals(0.1, second.y, 1e-12)
        // Nothing that overlaps by more than a tenth: held where it was, as the original's tracker would go on, for a second.
        assertEquals(second.copy(time = 2 * step), tracker.update(2 * step, { listOf(face(0.7, 0.7)) }, { emptyList() }))
        assertEquals(second.x, tracker.update(0.5, { emptyList() }, { emptyList() })!!.x, 1e-12)
        assertEquals(second.x, tracker.update(0.9, { emptyList() }, { emptyList() })!!.x, 1e-12)
        assertNull(tracker.update(1.1, { emptyList() }, { emptyList() }))
        assertNull(tracker.update(1.3, { listOf(face(0.7, 0.7)) }, { emptyList() }))
        // Half a second after the last, it starts again from the point.
        assertEquals(0.7, tracker.update(1.5, { listOf(face(0.7, 0.7)) }, { emptyList() })!!.x, 1e-12)
    }

    // :57-61.
    @Test fun overlapIsIntersectionOverUnion() {
        assertEquals(1.0, EffectSubjectTracker.overlap(Rect(0.0, 0.0, 0.2, 0.2), Rect(0.0, 0.0, 0.2, 0.2)), 1e-12)
        assertEquals(1.0 / 7, EffectSubjectTracker.overlap(Rect(0.0, 0.0, 0.2, 0.2), Rect(0.1, 0.1, 0.2, 0.2)), 1e-12)
        assertEquals(0.0, EffectSubjectTracker.overlap(Rect(0.0, 0.0, 0.1, 0.1), Rect(0.5, 0.5, 0.1, 0.1)), 0.0)
    }

    @Test fun aMasksPersonIsItsBox() {
        val rows = ByteArray(20 * 20)
        for (y in 5 until 15) for (x in 8 until 12) rows[y * 20 + x] = 200.toByte()
        val box = EffectSubjectTracker.maskBox(rows, 20, 20)!!.box
        assertEquals(Rect(0.4, 0.25, 0.2, 0.5), box)
        assertNull(EffectSubjectTracker.maskBox(ByteArray(400), 20, 20))
    }

    // VideoEffectAssets.swift:476: a Dolly Zoom always, a Crash Zoom when it follows a face; the analysis named by its span and point.
    @Test fun whatIsFollowedAndUnderWhatName() {
        val dolly = VideoEffectSettings(VideoEffectID.DollyZoom, 1.0, 5.0).with("targetX", EffectValue.Number(0.3))
        val crash = VideoEffectSettings(VideoEffectID.CrashZoom, 0.0, 3.0)
        assertTrue(EffectSubjectAnalysis.follows(dolly, 2.0))
        assertEquals(crash.toggle("trackFace"), EffectSubjectAnalysis.follows(crash, 1.0))
        assertFalse(EffectSubjectAnalysis.follows(crash.with("trackFace", EffectValue.Toggle(false)), 1.0))
        val clip = TimelineClip(sourceID = "s", start = 0.0, end = 4.0)
        assertEquals(1.0..4.0, EffectSubjectAnalysis.span(dolly, clip))
        assertEquals(Point(0.3, 0.5), EffectSubjectAnalysis.seed(dolly, clip))
        assertTrue(EffectSubjectAnalysis.key("a.mov", dolly, clip) != EffectSubjectAnalysis.key("a.mov", dolly.with("targetX", EffectValue.Number(0.4)), clip))
    }

    // VideoEffectAssets.swift:476-486: a followed subject is the move's target, else the one set by hand.
    @Test fun theFollowedSubjectIsTheTarget() {
        val dolly = VideoEffectSettings(VideoEffectID.DollyZoom, 0.0, 5.0)
        val slots = dolly.kind.descriptor.parameters.withIndex().associate { it.value.id to it.index }
        val plain = EffectFrame.of(listOf(dolly), 1.0, 30, 30.0).single()
        assertEquals(dolly.number("targetX", 1.0), plain.p[slots.getValue("targetX")].x, 1e-12)
        val followed = EffectFrame.of(listOf(dolly), 1.0, 30, 30.0, inputs = mapOf(dolly.id to EffectFrameInputs(target = Point(0.25, 0.75)))).single()
        assertEquals(0.25, followed.p[slots.getValue("targetX")].x, 1e-12)
        assertEquals(0.75, followed.p[slots.getValue("targetY")].x, 1e-12)
    }
}
