package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs

/** The recorder's Crash Zoom and Dolly Zoom (C/CameraMoves.kt), with the numbers of the original's code and tests. */
class CameraMovesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun crash(returns: Boolean = false, hold: Double = 1.0) = VideoEffectSettings(VideoEffectID.CrashZoom)
        .with("return", EffectValue.Toggle(returns)).with("hold", EffectValue.Number(hold))

    // MARK: Dolly Zoom's follower. Tests/VideoEffectModelTests.swift:283-295, 314-326.

    @Test fun followerConvergesWithDelayedMeasurements() {
        for (latency in 0..4) {
            val follower = DollyZoomFollower(0.25, 1.0, 1.0..4.0, 0.0)
            val commands = DoubleArray(300) { 1.0 }
            for (frame in 1 until 300) {
                val time = frame / 30.0
                val measured = maxOf(0, frame - latency)
                val distance = 1 + minOf(4.0, measured / 30.0) * 0.2
                if (frame % 2 == 0) follower.observe(0.25 / distance * commands[measured], commands[measured], measured / 30.0)
                commands[frame] = follower.value(time)
            }
            assertEquals("latency $latency", 1.8, follower.zoom, 0.01)
            val before = follower.zoom
            for (frame in 300 until 350) follower.value(frame / 30.0)
            // A lost subject eases to a hold without resetting the framing.
            assertEquals(before, follower.zoom, 0.01)
            assertTrue(follower.isStopping)
        }
    }

    @Test fun followerStaysStableWithJitteredAndDroppedSamples() {
        for (actualDelay in 0..4) {
            val follower = DollyZoomFollower(0.25, 1.0, 1.0..4.0, 0.0)
            val commands = DoubleArray(300) { 1.0 }
            for (frame in 1 until 300) {
                val measured = maxOf(0, frame - (frame % 3 + 1))
                val actual = maxOf(0, measured - actualDelay)
                val estimated = maxOf(0, measured - 2)
                val distance = 1 + minOf(4.0, measured / 30.0) * 0.2
                if (frame % 2 == 0 && frame % 22 != 0) follower.observe(0.25 / distance * commands[actual], commands[estimated], measured / 30.0)
                commands[frame] = follower.value(frame / 30.0)
            }
            assertEquals(1.8, commands[180], 0.02)
            assertTrue(commands.max() < 1.8 * 1.03)
        }
    }

    @Test fun followerStaysInItsRangeAndSaysSoNearTheEnd() {
        // VideoEffectMath.swift:279-291: the subject twice as large asks for half the zoom, held at the range.
        val follower = DollyZoomFollower(0.3, 1.2, 1.0..1.5, 0.0)
        for (frame in 1..60) { follower.observe(0.6, 1.2, frame / 30.0); follower.value(frame / 30.0) }
        assertEquals(1.0, follower.zoom, 1e-9)
        assertTrue(follower.atLimit)
        follower.stop(2.0)
        assertFalse(follower.hasStopped(2.29))
        assertTrue(follower.hasStopped(2.31))
    }

    // MARK: Starting a Dolly Zoom. CaptureController.swift:2005-2051.

    @Test fun rangeIsTheWidestToHalfAgainTheCleanZoom() {
        assertEquals(1.0..1.5, DollyZoomMove.range(1.0, 10.0, 1.0))
        assertEquals(0.5..3.0, DollyZoomMove.range(0.5, 10.0, 2.0))
        assertEquals(1.0..1.3, DollyZoomMove.range(1.0, 1.3, 2.0))
        // Less than 1.3 times: "Choose 1080p or another lens for more zoom range".
        assertNull(DollyZoomMove.range(1.0, 1.29, 2.0))
    }

    @Test fun stretchFirstFramesTheSubjectAtSevenTenths() {
        // min(upper, max(current, min(2, 0.7/height*current)))
        assertEquals(1.4, DollyZoomMove.prepared(true, 1.0, 1.5, 0.5), 1e-12)
        assertEquals(1.5, DollyZoomMove.prepared(true, 1.0, 1.5, 0.2), 1e-12)
        assertEquals(2.0, DollyZoomMove.prepared(true, 1.0, 3.0, 0.1), 1e-12)
        assertEquals(1.2, DollyZoomMove.prepared(true, 1.2, 3.0, 0.8), 1e-12)
        assertEquals(1.0, DollyZoomMove.prepared(false, 1.0, 3.0, 0.1), 1e-12)
        assertTrue(DollyZoomMove.needsPreparation(1.06, 1.0))
        assertFalse(DollyZoomMove.needsPreparation(1.05, 1.0))
    }

    @Test fun aMoveNeedsTwelveHundredthsOfRoom() {
        val range = 1.0..1.5
        assertTrue(DollyZoomMove.hasRoom(true, 1.12, range))
        assertFalse(DollyZoomMove.hasRoom(true, 1.11, range))
        assertTrue(DollyZoomMove.hasRoom(false, 1.5 / 1.12, range))
        assertFalse(DollyZoomMove.hasRoom(false, 1.4, range))
        assertEquals(1.0, DollyZoomMove.remaining(true, 1.5, range), 1e-12)
        assertEquals(0.0, DollyZoomMove.remaining(true, 1.0, range), 1e-12)
        assertEquals(1.0, DollyZoomMove.remaining(false, 1.0, range), 1e-12)
    }

    @Test fun hintsFollowTheOriginalsOrder() {
        // CaptureController.swift:2083.
        assertEquals(DollyHint.Holding, DollyZoomMove.hint(true, false, 0.0, 9.0, 1.0, true))
        assertEquals(DollyHint.FollowingBody, DollyZoomMove.hint(false, false, 0.0, 9.0, 1.0, true))
        assertEquals(DollyHint.NearLimit, DollyZoomMove.hint(false, true, 0.07, 9.0, 1.0, true))
        assertEquals(DollyHint.Digital, DollyZoomMove.hint(false, true, 0.5, 1.03, 1.0, true))
        assertEquals(DollyHint.WalkToward, DollyZoomMove.hint(false, true, 0.5, 1.02, 1.0, true))
        assertEquals(DollyHint.WalkAway, DollyZoomMove.hint(false, null, 0.5, 1.0, 1.0, false))
        assertEquals("Tap a face, then start", DollyHint.TapFace.words)
        assertEquals("Start dolly", LiveDollyState.Ready.label)
        assertTrue(LiveDollyState.Stopping.moving)
        assertFalse(LiveDollyState.Holding.moving)
    }

    @Test fun measurementsAreWholeSureAndSteady() {
        // CaptureController.swift:2056-2073.
        fun sample(height: Double, face: Boolean = true, y: Double = 0.3, confidence: Double = 1.0) =
            SubjectSample(1.0, Rect(0.4, y, 0.2, height), confidence, face)
        val first = DollyMeasurement(sample(0.3), 1.0, 1.0, "0")
        assertTrue(DollyZoomMove.accepts(first, "0", null, false))
        assertFalse(DollyZoomMove.accepts(first, "1", null, false))
        assertFalse(DollyZoomMove.accepts(first.copy(subject = sample(0.03)), "0", null, false))
        assertFalse(DollyZoomMove.accepts(first.copy(subject = sample(0.81, y = 0.1)), "0", null, false))
        assertFalse(DollyZoomMove.accepts(first.copy(subject = sample(0.3, y = 0.01)), "0", null, false))
        assertFalse(DollyZoomMove.accepts(first.copy(subject = sample(0.3, confidence = 0.59)), "0", null, false))
        // While following: no change of kind, and no jump. 1/15 s later the size may change by less than e^0.25.
        val next = DollyMeasurement(sample(0.3 * 1.25), 1.0 + 1 / 15.0, 1.0, "0")
        assertTrue(DollyZoomMove.accepts(next, "0", first, true))
        assertFalse(DollyZoomMove.accepts(next.copy(subject = sample(0.3 * 1.3)), "0", first, true))
        assertFalse(DollyZoomMove.accepts(next.copy(subject = sample(0.3, face = false)), "0", first, true))
        // The zoom of each picture is taken out: a larger face at a larger zoom is the same size.
        assertTrue(DollyZoomMove.accepts(next.copy(subject = sample(0.6), zoom = 2.0), "0", first, true))
        assertTrue(DollyZoomMove.accepts(next.copy(subject = sample(0.3 * 1.3)), "0", first, false))
    }

    // MARK: The subject. VideoEffectSubjectTracking.swift:45-97.

    @Test fun facesComeBeforePeopleAndTheTapChooses() {
        val left = Rect(0.1, 0.2, 0.2, 0.3)
        val right = Rect(0.6, 0.2, 0.3, 0.4)
        val person = Rect(0.3, 0.1, 0.4, 0.8)
        val (_, largest) = SubjectTracker().update(listOf(left, right), listOf(person), 0.0)
        assertEquals(right.width, largest!!.box.width, 1e-12)
        assertTrue(largest.face)
        val (_, tapped) = SubjectTracker(Point(0.2, 0.3)).update(listOf(left, right), listOf(person), 0.0)
        assertEquals(left.minX, tapped!!.box.minX, 1e-12)
        // Neither under the tap: the nearest.
        val (_, near) = SubjectTracker(Point(0.55, 0.9)).update(listOf(left, right), emptyList(), 0.0)
        assertEquals(right.minX, near!!.box.minX, 1e-12)
        val (_, body) = SubjectTracker().update(emptyList(), listOf(person), 0.0)
        assertFalse(body!!.face)
        assertNull(SubjectTracker().update(emptyList(), emptyList(), 0.0).second)
    }

    @Test fun theSubjectIsKeptAndItsCentreEased() {
        val first = Rect(0.2, 0.2, 0.2, 0.2)
        val (tracker, a) = SubjectTracker().update(listOf(first), emptyList(), 0.0)
        // A larger face elsewhere does not take over within half a second; the moved one is followed.
        val moved = Rect(0.24, 0.2, 0.2, 0.2)
        val (next, b) = tracker.update(listOf(Rect(0.6, 0.5, 0.35, 0.35), moved), emptyList(), 0.1)
        val alpha = 1 - kotlin.math.exp(-0.1 / 0.12)
        assertEquals(a!!.center.x + (moved.midX - a.center.x) * alpha, b!!.center.x, 1e-12)
        assertEquals(0.2, b.box.width, 1e-12)
        // Not overlapping any more: nothing this time, and the subject is kept for the next.
        val (kept, none) = next.update(listOf(Rect(0.6, 0.5, 0.35, 0.35)), emptyList(), 0.2)
        assertNull(none)
        assertEquals(b, kept.previous)
        // After half a second the largest is chosen again.
        assertEquals(0.35, kept.update(listOf(Rect(0.6, 0.5, 0.35, 0.35)), emptyList(), 0.8).second!!.box.width, 1e-12)
    }

    @Test fun overlapIsSharedAgainstCovered() {
        assertEquals(1.0 / 3, SubjectTracker.overlap(Rect(0.0, 0.0, 0.2, 0.1), Rect(0.1, 0.0, 0.2, 0.1)), 1e-12)
        assertEquals(0.0, SubjectTracker.overlap(Rect(0.0, 0.0, 0.1, 0.1), Rect(0.2, 0.0, 0.1, 0.1)), 0.0)
        // EffectSubjectSample.clipped: within 0.015 of an edge.
        assertTrue(SubjectSample(0.0, Rect(0.01, 0.2, 0.2, 0.2), 1.0, true).clipped)
        assertFalse(SubjectSample(0.0, Rect(0.02, 0.2, 0.2, 0.2), 1.0, true).clipped)
    }

    // MARK: Crash Zoom. LiveVideoEffects.swift:339-392.

    @Test fun aTriggerPushesHoldsAndOnlyAHoldReturns() {
        val template = crash()
        val idle = LiveCrashZooms()
        assertEquals(LiveCrashState.Ready, idle.state(2.0))
        assertNull(idle.triggered(2.0, null, null))
        assertNull(idle.triggered(2.0, template.copy(enabled = false), null))
        val pushed = idle.triggered(2.0, template, Point(0.3, 0.6), id = "a")!!
        val move = pushed.moves.single()
        assertEquals(2.0, move.start, 0.0)
        assertEquals(0.0, move.phaseOffset!!, 0.0)
        assertEquals(VideoEffectSettings.MAXIMUM_TIME, move.end, 0.0)
        assertEquals(0.3, move.number("targetX"), 0.0)
        assertEquals(0.6, move.number("targetY"), 0.0)
        assertEquals(LiveCrashState.Pushing, pushed.state(2.0))
        assertEquals(LiveCrashState.Pushing, pushed.state(2.0 + 0.18 + 0.079))
        assertEquals(LiveCrashState.Holding, pushed.state(2.0 + 0.18 + 0.08))
        // Pressed again while pushing: nothing.
        assertNull(pushed.triggered(2.1, template, null))
        // Pressed while holding: it returns over the push's time.
        val returning = pushed.triggered(5.0, template, null)!!
        assertEquals(5.0, returning.moves.single().releaseTime!!, 0.0)
        assertEquals(5.18, returning.moves.single().end, 1e-12)
        assertEquals(LiveCrashState.Returning, returning.state(5.1))
        assertNull(returning.triggered(5.1, template, null))
        assertEquals(LiveCrashState.Ready, returning.state(5.18))
        // Once returned, a new one starts.
        assertEquals(2, returning.triggered(6.0, template, null)!!.moves.size)
    }

    @Test fun aReturningMoveEndsByItself() {
        val pushed = LiveCrashZooms().triggered(1.0, crash(returns = true, hold = 0.5), null)!!
        val move = pushed.moves.single()
        // time + 2 × speed + 0.08 + hold.
        assertEquals(1.0 + 0.36 + 0.08 + 0.5, move.end, 1e-12)
        assertEquals(LiveCrashState.Returning, pushed.state(1.0 + 0.18 + 0.08 + 0.5))
        assertEquals(LiveCrashState.Ready, pushed.state(move.end))
    }

    @Test fun aMoveIsCarriedOntoANewClock() {
        // Tests/VideoEffectGPUTests.swift:574-575: a move released at 2.1 and carried from 2 has an offset of 2 and a release at 0.1.
        val moves = LiveCrashZooms(listOf(crash().copy(start = 0.0, end = 10.0, releaseTime = 2.1, phaseOffset = 0.0)))
        val carried = moves.carried(2.0).moves.single()
        assertEquals(2.0, carried.phaseOffset!!, 1e-12)
        assertEquals(0.0, carried.start, 0.0)
        assertEquals(8.0, carried.end, 1e-12)
        assertEquals(0.1, carried.releaseTime!!, 1e-12)
        // The picture is the same either side of the change of clock.
        val before = moves.look(2.05)!!
        val after = LiveCrashZooms(listOf(carried)).look(0.05)!!
        assertEquals(before.zoom, after.zoom, 1e-12)
        // A move that has ended is not carried.
        assertTrue(moves.carried(10.0).moves.isEmpty())
        val open = LiveCrashZooms(listOf(crash().copy(start = 1.0, phaseOffset = 0.0))).carried(3.0).moves.single()
        assertEquals(VideoEffectSettings.MAXIMUM_TIME, open.end, 0.0)
    }

    // The moves brought from the effect clock to the movie's, as the original times a press by the frame its writer was
    // given (LiveVideoEffects.swift:354-358): each draws the same picture so much earlier.
    @Test fun movesAreShiftedOntoTheMoviesClock() {
        val pressed = LiveCrashZooms().triggered(4.9857, crash(returns = true), Point(0.3, 0.6))!!
        val released = LiveCrashZooms(listOf(crash().copy(start = 2.0, end = 10.0, releaseTime = 3.0, phaseOffset = 0.0)))
        val carried = LiveCrashZooms(listOf(crash().copy(start = 0.0, end = 5.0, releaseTime = null, phaseOffset = 0.5)))
        val by = 0.19
        for (moves in listOf(pressed, released, carried)) {
            val shifted = moves.shifted(by)
            assertTrue(shifted.moves.all { it.valid })
            for (t in listOf(0.0, 0.1, 1.0, 2.05, 2.5, 3.1, 4.9, 5.0, 5.1, 5.3, 7.0, 8.0)) {
                val before = moves.look(t + by)
                val after = shifted.look(t)
                assertEquals("at $t", before?.zoom, after?.zoom)
                assertEquals(before?.target, after?.target)
            }
        }
        val start = pressed.shifted(by).moves.single()
        assertEquals(4.7957, start.start, 1e-12)
        assertEquals(pressed.moves.single().end - by, start.end, 1e-12)
        // Under way at the movie's first frame: it starts there, the time it had run kept as its phase.
        val under = carried.shifted(by).moves.single()
        assertEquals(0.0, under.start, 0.0)
        assertEquals(0.69, under.phaseOffset!!, 1e-12)
        // One held for good keeps its end; one over before the movie's first frame is dropped.
        assertEquals(VideoEffectSettings.MAXIMUM_TIME, LiveCrashZooms().triggered(1.0, crash(), null)!!.shifted(by).moves.single().end, 0.0)
        assertTrue(LiveCrashZooms(listOf(crash().copy(start = 0.0, end = 0.1, phaseOffset = 0.0))).shifted(by).moves.isEmpty())
        assertEquals(pressed, pressed.shifted(0.0))
    }

    @Test fun theLookIsTheEditorsCrashZoom() {
        val moves = LiveCrashZooms().triggered(1.0, crash(), Point(0.25, 0.6))!!
        val effect = moves.moves.single()
        for (t in listOf(1.05, 1.18, 1.2, 1.5, 3.0)) {
            val look = moves.look(t)!!
            assertEquals(EffectFrame.crashZoomScale(effect, t), look.zoom, 1e-12)
        }
        // At the end of the push, 2.5 ×; the target set by hand while Follow face is off, else the subject.
        assertEquals(2.5, moves.look(1.18)!!.zoom, 1e-9)
        val tracked = LiveCrashZooms().triggered(1.0, crash().with("trackFace", EffectValue.Toggle(true)), null)!!
        assertEquals(Point(0.7, 0.4), tracked.look(1.5, Point(0.7, 0.4))!!.target)
        val manual = LiveCrashZooms().triggered(1.0, crash().with("trackFace", EffectValue.Toggle(false)), Point(0.2, 0.3))!!
        assertEquals(Point(0.2, 0.3), manual.look(1.5, Point(0.7, 0.4))!!.target)
        // Fast in the push, a blur; none in the hold.
        assertTrue(moves.look(1.17)!!.blur > 0)
        assertEquals(0.0, moves.look(2.0)!!.blur, 0.0)
        assertNull(LiveCrashZooms().look(1.0))
    }

    @Test fun theRecipeComesBackForTheTake() {
        val movie = File(temporary.root, "1700000000000-take.mp4")
        assertEquals(File(temporary.root, "1700000000000-take.effects.json"), CaptureEffectsFile.beside(movie))
        assertNull(CaptureEffectsFile.read(movie, 10.0))
        val moves = LiveCrashZooms().triggered(1.0, crash(), Point(0.3, 0.4))!!.triggered(3.0, crash(), null)!!
        CaptureEffectsFile.write(moves.moves, movie)
        // The second press returned the move held since 1 s.
        val read = CaptureEffectsFile.read(movie, 4.0)!!.single()
        assertEquals(3.18, read.end, 1e-12)
        assertEquals(3.0, read.releaseTime!!, 0.0)
        assertEquals(0.3, read.number("targetX"), 0.0)
        // Cut to the movie: the move ends with it, and a return after its end is dropped (VideoEffectModel.swift:342-349).
        val cut = CaptureEffectsFile.read(movie, 2.0)!!.single()
        assertEquals(2.0, cut.end, 0.0)
        assertNull(cut.releaseTime)
        // A move after the end of the movie is left out.
        assertNull(CaptureEffectsFile.read(movie, 0.5))
        CaptureEffectsFile.beside(movie).writeText("{")
        assertNull(CaptureEffectsFile.read(movie, 4.0))
    }

    @Test fun theTakesClipCarriesTheMoves() {
        val move = LiveCrashZooms().triggered(1.0, crash(), null)!!.moves.map { it.copy(end = 3.0) }
        val project = Project("P").addRecording(SourceMedia(filename = "a.mp4", duration = 3.0), videoEffects = move)
        assertEquals(move, project.captureLedger.single().videoEffects)
        assertTrue(project.schemaVersion >= 16)
        assertNull(Project("P").addRecording(SourceMedia(filename = "a.mp4", duration = 3.0), videoEffects = emptyList()).captureLedger.single().videoEffects)
    }

    @Test fun liveEffectsAreKeptOrNotAtAll() {
        val effects = listOf(crash(returns = true), VideoEffectSettings(VideoEffectID.DollyZoom).with("direction", EffectValue.Choice("Compress")))
        assertEquals(effects, LiveEffectsStore.decode(LiveEffectsStore.encode(effects)))
        assertTrue(LiveEffectsStore.decode(null).isEmpty())
        assertTrue(LiveEffectsStore.decode("[{").isEmpty())
        // CaptureController.swift:204-208: one effect out of its bounds and none is read.
        val wrong = LiveEffectsStore.encode(listOf(crash().copy(start = 5.0, end = 1.0)) + effects)
        assertTrue(LiveEffectsStore.decode(wrong).isEmpty())
    }

    // MARK: Points.

    @Test fun pointsGoBetweenTheFrameAndTheCamera() {
        for (upright in listOf(0, 90, 180, 270)) for (mirrored in listOf(false, true)) {
            val p = Point(0.2, 0.7)
            val back = RecorderFramePoints.fromCamera(RecorderFramePoints.toCamera(p, upright, mirrored), upright, mirrored)
            assertEquals(p.x, back.x, 1e-12)
            assertEquals(p.y, back.y, 1e-12)
        }
        // A camera turned a quarter clockwise: its top-left corner is the frame's top-right.
        assertEquals(Point(1.0, 0.0), RecorderFramePoints.fromCamera(Point(0.0, 0.0), 90, false))
        val box = RecorderFramePoints.fromCamera(Rect(0.1, 0.2, 0.3, 0.1), 90, false)
        assertEquals(0.1, box.width, 1e-12)
        assertEquals(0.3, box.height, 1e-12)
    }

    @Test fun aTouchFallsInTheFrameAsShown() {
        // A 9:16 frame filling a 1080 × 2000 view is cropped at the sides.
        val p = RecorderFramePoints.fromView(540.0, 1000.0, 1080.0, 2000.0, 9.0 / 16, fill = true, unmirror = false)!!
        assertEquals(0.5, p.x, 1e-12)
        assertEquals(0.5, p.y, 1e-12)
        val corner = RecorderFramePoints.fromView(0.0, 0.0, 1080.0, 2000.0, 9.0 / 16, fill = true, unmirror = false)!!
        assertEquals((1125.0 - 1080) / 2 / 1125, corner.x, 1e-12)
        // Fitted, the bands are outside the frame.
        assertNull(RecorderFramePoints.fromView(540.0, 10.0, 1080.0, 2400.0, 9.0 / 16, fill = false, unmirror = false))
        assertEquals(0.75, RecorderFramePoints.fromView(270.0, 1200.0, 1080.0, 2400.0, 9.0 / 16, fill = false, unmirror = true)!!.x, 1e-12)
        assertNotNull(RecorderFramePoints.fromView(1.0, 1.0, 10.0, 10.0, 1.0, fill = false, unmirror = false))
        assertTrue(abs(RecorderFramePoints.fromView(10.0, 10.0, 10.0, 10.0, 1.0, false, false)!!.x - 1) < 1e-12)
    }
}
