package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The stabilisation path. Tests marked TRANSLATED carry over the iPhone app's
 * stabilizationTests (Tests/StabilizationTests.swift:4-55) against
 * YouRec/StabilizationPath.swift:4-196. Its persistence, split and media
 * checks (lines 57-156) need the saved model and the renderer, which hold no
 * stabilisation yet; the reference range they rely on is tested here alone.
 */
class StabilizationPathTest {
    private val frames = 240
    private val fps = 30.0
    private val size = Size(640.0, 360.0)
    private val pan = (0 until frames).map { StabilizationPose(x = it * 1.1, y = it * -0.2) }

    private fun analysis(poses: List<StabilizationPose>, tracked: Int = frames - 1, duration: Double = 8.0) =
        StabilizationAnalysis(640, 360, duration, poses.mapIndexed { i, p -> StabilizationSample(i / fps, p, 0) }, tracked)

    private val noisyPoses = pan.mapIndexed { i, p ->
        val t = i / fps
        p.copy(x = p.x + 6 * sin(2 * PI * 4.1 * t), y = p.y + 4 * sin(2 * PI * 3.3 * t), angle = 0.008 * sin(2 * PI * 2.7 * t))
    }
    private val noisy = analysis(noisyPoses)

    /** TRANSLATED StabilizationTests.swift:8-10. */
    @Test fun constantSpeedPansStayUnchangedIncludingTheEnds() {
        val plan = StabilizationPath.make(analysis(pan), StabilizationLevel.Strong)
        assertTrue(plan.samples.all { kotlin.math.abs(it.pose.x) < 0.00001 && kotlin.math.abs(it.pose.y) < 0.00001 })
    }

    /** TRANSLATED StabilizationTests.swift:11-37. */
    @Test fun strongerLevelsShakeLessAndNoCornerShowsBlack() {
        var previousResidual = Double.POSITIVE_INFINITY
        val corners = listOf(Point.ZERO, Point(size.width, 0.0), Point(0.0, size.height), Point(size.width, size.height))
        for (level in StabilizationLevel.entries.filter { it != StabilizationLevel.Off }) {
            val plan = StabilizationPath.make(noisy, level)
            val residual = plan.samples.indices.drop(30).dropLast(30).sumOf { i ->
                val stable = plan.samples[i].pose.applying(noisyPoses[i])
                (stable.x - pan[i].x).pow(2) + (stable.y - pan[i].y).pow(2)
            } / (frames - 60)
            assertTrue("$level residual $residual", residual < previousResidual + 0.05)
            assertTrue(plan.zoom <= level.maximumZoom + 0.000001)
            for (frame in 0 until frames * 3) {
                val inverse = plan.transform(frame / 90.0).inverted()
                for (corner in corners) {
                    val source = corner.applying(inverse)
                    assertTrue(source.x >= -0.001 && source.y >= -0.001 && source.x <= size.width + 0.001 && source.y <= size.height + 0.001)
                }
            }
            previousResidual = residual
        }
        assertTrue("strong residual $previousResidual", previousResidual < 2)
    }

    /** TRANSLATED StabilizationTests.swift:38-39. */
    @Test fun offIsTheExactIdentity() {
        val off = StabilizationPath.make(noisy, StabilizationLevel.Off)
        assertEquals(1.0, off.zoom, 0.0)
        assertEquals(Affine.IDENTITY, off.transform(1.0))
    }

    /** TRANSLATED StabilizationTests.swift:40-44. */
    @Test fun aRecordedZoomIsNeverSmoothedOrRetimed() {
        val zoomed = analysis((0 until frames).map { StabilizationPose(logScale = it / fps * ln(2.0) / 2) })
        val plan = StabilizationPath.make(zoomed, StabilizationLevel.Strong)
        assertEquals(1.0, plan.zoom, 0.0)
        assertTrue(plan.samples.all { kotlin.math.abs(it.pose.logScale) < 1e-10 })
    }

    /** TRANSLATED StabilizationTests.swift:45-54. */
    @Test fun aZoomAfterAFinishedPanAddsNoFalseTranslation() {
        val samples = (0 until 450).map { i ->
            val t = i / fps
            val cameraX = min(t, 4.0) * 200
            val zoom = 1 + min(1.0, max(0.0, (t - 7) / 2))
            StabilizationSample(t, StabilizationPose(x = -zoom * cameraX, logScale = ln(zoom)), 0)
        }
        val panZoom = StabilizationAnalysis(640, 360, 15.0, samples, 449)
        for (level in listOf(StabilizationLevel.Standard, StabilizationLevel.Strong)) {
            val result = StabilizationPath.make(panZoom, level)
            assertTrue(result.samples.filter { it.time >= 6.5 }.all { kotlin.math.abs(it.pose.x) < 0.0001 && kotlin.math.abs(it.pose.y) < 0.0001 })
        }
    }

    /** TRANSLATED StabilizationTests.swift:55-59. */
    @Test fun smoothingNeverCrossesACut() {
        val samples = noisy.samples.mapIndexed { i, s -> if (i >= 120) s.copy(segment = 1, pose = StabilizationPose.IDENTITY) else s }
        val plan = StabilizationPath.make(StabilizationAnalysis(640, 360, 8.0, samples, 238), StabilizationLevel.Strong)
        assertTrue(plan.samples.drop(120).all { kotlin.math.abs(it.pose.x) < 0.000001 && kotlin.math.abs(it.pose.y) < 0.000001 })
    }

    @Test fun poseAlgebra() {
        val pose = StabilizationPose(3.0, -2.0, 0.1, 0.05)
        val round = pose.applying(pose.inverse)
        assertEquals(0.0, round.x, 1e-12); assertEquals(0.0, round.y, 1e-12)
        assertEquals(0.0, round.angle, 1e-12); assertEquals(0.0, round.logScale, 1e-12)
        assertEquals(1.0, StabilizationPose.IDENTITY.requiredZoom(size), 0.0)
        assertEquals(Double.POSITIVE_INFINITY, StabilizationPose(x = 400.0).requiredZoom(size), 0.0)
        assertEquals(320.0 / 310.0, StabilizationPose(x = 10.0).requiredZoom(size), 1e-12)
        assertFalse(StabilizationPose(logScale = 9.0).valid)
    }

    @Test fun theTransformHoldsTheFrameBefore() {
        val plan = StabilizationPlan(size, listOf(
            StabilizationSample(0.0, StabilizationPose(x = 1.0), 0),
            StabilizationSample(1.0, StabilizationPose(x = 2.0), 0),
        ), 1.0, false, 1.0)
        assertEquals(1.0, plan.transform(-1.0).tx, 1e-12)
        assertEquals(1.0, plan.transform(0.99).tx, 1e-12)
        assertEquals(2.0, plan.transform(1.0).tx, 1e-12)
        assertEquals(9, StabilizationPlan(size, emptyList(), 1.1, false, 1.0).cropPercent)
    }

    /** The reference range of StabilizationPath.swift:35-40, which splits inherit. */
    @Test fun theReferenceRangeOutlivesASplit() {
        val whole = StabilizationSettings.stabilizing(StabilizationLevel.Strong, 0.0, 4.0, null)
        val second = StabilizationSettings.stabilizing(StabilizationLevel.Light, 2.0, 4.0, whole)
        assertEquals(0.0, second.referenceStart!!, 0.0)
        assertEquals(4.0, second.referenceEnd!!, 0.0)
        assertEquals(StabilizationLevel.Light, second.level)
    }

    @Test fun analysisValidity() {
        assertTrue(noisy.valid)
        assertEquals(1.0, noisy.trackingFraction, 1e-12)
        assertFalse(noisy.copy(samples = noisy.samples.reversed()).valid)
        assertFalse(noisy.copy(duration = 1.0).valid)
    }
}
