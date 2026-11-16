package app.takes.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The person mask's temporal smoothing. Most are the original's own tests,
 * from Tests/VideoEffectGPUTests.swift, which runs them on the GPU on a
 * 320×180 mask: here they run on the reference, with its masks and tolerances.
 */
class PersonMaskSmootherTest {
    // Tests/VideoEffectGPUTests.swift:10-11
    private val width = 320
    private val height = 180

    // Tests/VideoEffectGPUTests.swift:505-512, 517: an 8-bit mask of one value.
    private fun constantMask(value: Int) = MaskPlane.flat(width, height, value / 255f)

    // Tests/VideoEffectGPUTests.swift:518: a grey of linear 0.2, whose luma is 0.2.
    private val staticGuide = MaskPlane.flat(width, height, 0.2f)
    private val white = MaskPlane.flat(width, height, 1f)

    // Tests/VideoEffectGPUTests.swift:536: 0.27 over the top half (rows from the top here), the grey below.
    private val movingGuide = MaskPlane.of(width, height) { _, y -> if (y < height / 2) 0.27f else 0.2f }

    private val low = constantMask(51)
    private val high = constantMask(204)
    private val nearMask = constantMask(66)
    private val ema = (51 + 0.3 * 15) / 255

    private fun at(mask: MaskPlane, x: Int = 0, y: Int = 0) = mask[x, y].toDouble()

    // YouRec/VideoEffects/MetalVideoEffects.swift:741: EMA alpha 0.3 at 30 Hz, none for no time.
    @Test fun theWeight() {
        assertEquals(0.3f, PersonMaskSmoother.weight(1.0 / 30), 1e-6f)
        assertEquals(1 - 0.7f * 0.7f, PersonMaskSmoother.weight(2.0 / 30), 1e-6f)
        assertEquals(0f, PersonMaskSmoother.weight(0.0), 0f)
        assertEquals(0f, PersonMaskSmoother.weight(-1.0), 0f)
    }

    // YouRec/VideoEffects/MetalVideoEffects.swift:740
    @Test fun theResetRule() {
        assertTrue(PersonMaskSmoother.resets(null, "front", 0.0, "front"))
        assertFalse(PersonMaskSmoother.resets(0.0, "front", 1.0 / 30, "front"))
        assertFalse(PersonMaskSmoother.resets(0.0, "front", 0.25, "front"))
        assertTrue(PersonMaskSmoother.resets(0.0, "front", 0.2501, "front"))
        assertTrue(PersonMaskSmoother.resets(0.1, "front", 0.1, "front"))
        assertTrue(PersonMaskSmoother.resets(0.1, "front", 0.0, "front"))
        assertTrue(PersonMaskSmoother.resets(0.0, "front", 1.0 / 30, "rear"))
    }

    // YouRec/VideoEffects/VideoEffects.metal:578-591, with the gates of YouRec/VideoEffects/MetalVideoEffects.swift:216.
    @Test fun thePixelRule() {
        val gate = PersonMaskSmoother.motionGateValue(true)
        val off = PersonMaskSmoother.motionGateValue(false)
        assertEquals(0.02f, gate)
        assertEquals(Float.POSITIVE_INFINITY, off)
        // Static, small change: mixed by the weight.
        assertEquals(0.2f + 0.3f * 0.1f, PersonMaskSmoother.temporal(0.3f, 0.5f, 0.2f, 0.5f, 0.3f, gate, false), 1e-6f)
        // The luma moved by the gate or more: the new alpha at once.
        assertEquals(0.3f, PersonMaskSmoother.temporal(0.3f, 0.53f, 0.2f, 0.5f, 0.3f, gate, false))
        // The alpha moved by 0.15 or more: at once when gated, mixed when not.
        assertEquals(0.8f, PersonMaskSmoother.temporal(0.8f, 0.5f, 0.2f, 0.5f, 0.3f, gate, false))
        assertEquals(0.2f + 0.3f * 0.6f, PersonMaskSmoother.temporal(0.8f, 0.5f, 0.2f, 0.5f, 0.3f, off, false), 1e-6f)
        // Without the gate any luma change still mixes.
        assertEquals(0.2f + 0.3f * 0.1f, PersonMaskSmoother.temporal(0.3f, 0.9f, 0.2f, 0.1f, 0.3f, off, false), 1e-6f)
        // A cut keeps the new alpha.
        assertEquals(0.3f, PersonMaskSmoother.temporal(0.3f, 0.5f, 0.2f, 0.5f, 0.3f, gate, true))
    }

    // YouRec/VideoEffects/VideoEffects.metal:565-574: the mean luma change over the 32×18 grid, above 0.12.
    @Test fun theSceneCutRule() {
        fun state(luma: MaskPlane) = PersonMaskState(MaskPlane.flat(luma.width, luma.height, 0f), luma)
        val grey = state(staticGuide)
        assertTrue(PersonMaskSmoother.sceneCut(staticGuide, grey, reset = true))
        assertTrue(PersonMaskSmoother.sceneCut(staticGuide, null, reset = false))
        assertFalse(PersonMaskSmoother.sceneCut(staticGuide, grey, reset = false))
        assertFalse(PersonMaskSmoother.sceneCut(MaskPlane.flat(width, height, 0.31f), grey, reset = false))
        assertTrue(PersonMaskSmoother.sceneCut(MaskPlane.flat(width, height, 0.33f), grey, reset = false))
        // Only the grid's pixels count: a change everywhere else is not seen.
        val grid = HashSet<Int>()
        for (y in 0 until 18) for (x in 0 until 32) grid += ((y + .5f) * height / 18f).toInt() * width + ((x + .5f) * width / 32f).toInt()
        val offGrid = MaskPlane(width, height, FloatArray(width * height) { if (it in grid) 0.2f else 1f })
        assertFalse(PersonMaskSmoother.sceneCut(offGrid, grey, reset = false))
        // On a mask smaller than the grid the samples are held inside it.
        val tiny = MaskPlane.flat(4, 3, 0.2f)
        assertFalse(PersonMaskSmoother.sceneCut(tiny, state(tiny), reset = false))
        assertTrue(PersonMaskSmoother.sceneCut(MaskPlane.flat(4, 3, 0.5f), state(tiny), reset = false))
    }

    @Test fun theStorageRoundings() {
        assertEquals(1f, MaskStorage.half(1f))
        assertEquals(0.7001953f, MaskStorage.half(0.7f))
        assertEquals(0.2f.toDouble(), MaskStorage.half(0.2f).toDouble(), 1.0 / 2048 / 8)
        assertEquals(65504f, MaskStorage.half(65504f))
        assertEquals(Float.POSITIVE_INFINITY, MaskStorage.half(65520f))
        assertEquals(5.9604645e-8f, MaskStorage.half(5.9604645e-8f))
        assertEquals(0f, MaskStorage.half(2.9e-8f))
        assertEquals(-0.7001953f, MaskStorage.half(-0.7f))
        // Ties go to the even half: 1 + 2^-11 lies between 1 and 1 + 2^-10.
        assertEquals(1f, MaskStorage.half(1f + 1f / 2048))
        assertEquals(51 / 255f, MaskStorage.r8(0.2f))
        assertEquals(0f, MaskStorage.r8(-1f))
        assertEquals(1f, MaskStorage.r8(2f))
    }

    // Tests/VideoEffectGPUTests.swift:519-523
    @Test fun aSilhouetteChangeFollowsAtOnceOnEqualLuma() {
        val equalLumaMotion = PersonMaskSmoother()
        equalLumaMotion.push(low, staticGuide, 0.0, "equal-luma", motionGate = true)
        val changedSilhouette = equalLumaMotion.push(high, staticGuide, 1.0 / 30, "equal-luma", motionGate = true)
        assertEquals("A strong silhouette change must follow immediately even when foreground and background have the same luma", 0.8, at(changedSilhouette), 0.005)
    }

    // Tests/VideoEffectGPUTests.swift:517, 524-535
    @Test fun theSmootherHoldsAndResets() {
        val smoother = PersonMaskSmoother()
        val heldMask = smoother.push(low, staticGuide, 0.0, "front", motionGate = true)
        val smoothed = smoother.push(nearMask, staticGuide, 1.0 / 30, "front", motionGate = true)
        assertEquals("Static mask EMA uses alpha 0.3", ema, at(smoothed), 0.005)
        val heldCopy = heldMask.data.copyOf()
        val smoothedCopy = smoothed.data.copyOf()
        smoother.push(nearMask, staticGuide, 2.0 / 30, "front", motionGate = true)
        assertEquals("Published CV masks remain immutable across later frames", 0.2, at(heldMask), 0.005)
        assertEquals(ema, at(smoothed), 0.005)
        assertArrayEquals(heldCopy, heldMask.data, 0f)
        assertArrayEquals(smoothedCopy, smoothed.data, 0f)
        val jump = smoother.push(high, white, 0.1, "front", motionGate = true)
        assertEquals("Scene change resets the whole mask history", 0.8, at(jump), 0.005)
        val backward = smoother.push(low, staticGuide, 0.0, "front", motionGate = true)
        val lens = smoother.push(high, staticGuide, 1.0 / 30, "rear", motionGate = true)
        val gap = smoother.push(low, staticGuide, 1.0, "rear", motionGate = true)
        assertEquals("Seek resets temporal masks", 0.2, at(backward), 0.005)
        assertEquals("Lens change resets temporal masks", 0.8, at(lens), 0.005)
        assertEquals("Interruption resets temporal masks", 0.2, at(gap), 0.005)
        val moving = smoother.push(nearMask, movingGuide, 1 + 1.0 / 30, "rear", motionGate = true)
        assertEquals("Moving top edge follows immediately", 66.0 / 255, at(moving, width / 2, height / 4), 0.005)
        assertEquals("Stationary bottom edge is smoothed without coordinate inversion", ema, at(moving, width / 2, height * 3 / 4), 0.005)
    }

    // Tests/VideoEffectGPUTests.swift:537-540
    @Test fun autoBackgroundFollowsMovingEdges() {
        val autoSmoother = PersonMaskSmoother()
        autoSmoother.push(low, staticGuide, 0.0, "auto", motionGate = true)
        val autoEMA = autoSmoother.push(nearMask, movingGuide, 1.0 / 30, "auto", motionGate = true)
        val message = "Auto Background follows moving edges without leaving the old silhouette behind, while static edges stay smoothed"
        assertEquals(message, 66.0 / 255, at(autoEMA, width / 2, height / 4), 0.005)
        assertEquals(message, ema, at(autoEMA, width / 2, height * 3 / 4), 0.005)
    }

    // YouRec/VideoEffects/MetalVideoEffects.swift:707, 711-718, 744: reset() and a new size start afresh.
    @Test fun resetAndANewSizeStartAfresh() {
        val smoother = PersonMaskSmoother()
        smoother.push(low, staticGuide, 0.0, "front")
        smoother.reset()
        assertEquals(0.8, at(smoother.push(high, staticGuide, 1.0 / 30, "front")), 0.005)
        // Without the motion gate even a large change is mixed.
        assertEquals((204 + 0.3 * (51 - 204)) / 255, at(smoother.push(low, staticGuide, 2.0 / 30, "front")), 0.005)
        val small = smoother.push(MaskPlane.flat(160, 90, 0.8f), MaskPlane.flat(160, 90, 0.2f), 3.0 / 30, "front")
        assertEquals(0.8, at(small), 0.005)
        assertEquals(160, small.width)
    }

    // YouRec/VideoEffects/MetalVideoEffects.swift:710: the original refuses such a mask.
    @Test fun anUnavailableMaskIsRefused() {
        val smoother = PersonMaskSmoother()
        assertThrows(IllegalArgumentException::class.java) { smoother.push(MaskPlane.flat(1025, 1, 0f), MaskPlane.flat(1025, 1, 0f), 0.0, "front") }
        assertThrows(IllegalArgumentException::class.java) { smoother.push(low, staticGuide, Double.NaN, "front") }
        assertThrows(IllegalArgumentException::class.java) { smoother.push(low, MaskPlane.flat(80, 45, 0f), 0.0, "front") }
    }

    // YouRec/VideoEffects/VideoEffects.metal:594-595: the output is 8-bit, the history half floats of (alpha, luma).
    @Test fun theStepRoundsAsTheTexturesDo() {
        val step = PersonMaskSmoother.step(nearMask, MaskPlane.flat(width, height, 0.3f), null, 0.3f, reset = true, motionGate = false)
        assertTrue(step.sceneCut)
        assertEquals(MaskStorage.r8(MaskStorage.half(66 / 255f)), step.output[0, 0])
        assertEquals(MaskStorage.half(66 / 255f), step.state.alpha[0, 0])
        assertEquals(MaskStorage.half(0.3f), step.state.luma[0, 0])
        val next = PersonMaskSmoother.step(low, MaskPlane.flat(width, height, 0.3f), step.state, 0.3f, reset = false, motionGate = false)
        assertFalse(next.sceneCut)
        val old = MaskStorage.half(66 / 255f)
        val mixed = old + (MaskStorage.half(51 / 255f) - old) * 0.3f
        assertEquals(MaskStorage.half(mixed), next.state.alpha[5, 7])
        assertEquals(MaskStorage.r8(MaskStorage.half(mixed)), next.output[5, 7])
    }
}
