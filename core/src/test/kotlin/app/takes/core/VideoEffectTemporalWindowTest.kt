package app.takes.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The finite-window matte history. The window's tests are the original's
 * Tests/VideoEffectModelTests.swift; the passes' are its
 * Tests/VideoEffectGPUTests.swift, which runs them on the GPU: here they run
 * on the reference, with its textures and tolerances.
 */
class VideoEffectTemporalWindowTest {
    private val kinds = listOf(VideoEffectMatteKind.ISOLATION, VideoEffectMatteKind.KEY)
    private val matte = VideoEffectTemporalMatte

    // YouRec/VideoEffects/VideoEffectMath.swift:26-30
    @Test fun theKinds() {
        assertEquals(1.0 / 3, VideoEffectMatteKind.ISOLATION.prior, 0.0)
        assertEquals(0.25, VideoEffectMatteKind.KEY.prior, 0.0)
        assertEquals(8, VideoEffectMatteKind.ISOLATION.capacity)
        assertEquals(6, VideoEffectMatteKind.KEY.capacity)
        for (kind in kinds) assertTrue(kind.capacity <= VideoEffectTemporalMatte.SLOTS)
    }

    // Tests/VideoEffectModelTests.swift:110-124
    @Test fun theWindow() {
        for (kind in kinds) {
            for (fps in listOf(24.0, 30.0, 60.0, 240.0)) {
                val times = (0 until 100).map { it / fps }
                val window = VideoEffectTemporalWindow.make(times, times[60], 0.0, kind)!!
                assertTrue(
                    "Temporal matte windows bound their EMA tail at every source rate",
                    window.indices.size == kind.capacity && abs(window.weights.sum() - 1) < 1e-12 && window.tailBound < 0.001,
                )
                val raw = window.indices.map { ((it * 17) % 29) / 28.0 }
                val recursive = raw.drop(1).fold(raw[0]) { acc, value -> acc * kind.prior + value * (1 - kind.prior) }
                assertEquals("Finite-window weights equal an EMA initialized at the oldest source frame", recursive, raw.zip(window.weights).sumOf { it.first * it.second }, 1e-12)
                val beginning = VideoEffectTemporalWindow.make(times, times[61], times[60] + 0.001, kind)!!
                assertEquals("A mid-frame effect starts on the frame already displayed", listOf(60, 61), beginning.indices)
                assertEquals(0.0, beginning.tailBound, 0.0)
                val cut = VideoEffectTemporalWindow.make(times, times[61], 0.0, kind, cuts = setOf(60))!!
                assertEquals("An internal scene cut closes the temporal window", listOf(60, 61), cut.indices)
                assertEquals(0.0, cut.tailBound, 0.0)
            }
            val gapTimes = listOf(0.0, 0.03, 0.08, 0.6, 0.64)
            val gap = VideoEffectTemporalWindow.make(gapTimes, 0.64, 0.0, kind)!!
            assertEquals("A capture discontinuity resets the matte history", listOf(3, 4), gap.indices)
            assertEquals(0.0, gap.tailBound, 0.0)
        }
    }

    // YouRec/VideoEffects/VideoEffectMath.swift:36, 43: no window without a frame on screen.
    @Test fun noWindow() {
        val times = listOf(0.0, 0.1, 0.2)
        val kind = VideoEffectMatteKind.ISOLATION
        assertNull(VideoEffectTemporalWindow.make(emptyList(), 0.0, 0.0, kind))
        assertNull(VideoEffectTemporalWindow.make(times, Double.NaN, 0.0, kind))
        assertNull(VideoEffectTemporalWindow.make(times, 0.1, 0.2, kind))
        assertNull(VideoEffectTemporalWindow.make(listOf(1.0), 0.5, 0.0, kind))
        assertEquals(listOf(0, 1, 2), VideoEffectTemporalWindow.make(times, 0.25, 0.0, kind)!!.indices)
    }

    // Tests/VideoEffectGPUTests.swift:80-85: a constant texture, r16Float unless said otherwise.
    private fun constant(value: Double, width: Int = 320, height: Int = 180, r8: Boolean = false) =
        MaskPlane.flat(width, height, if (r8) MaskStorage.r8(value.toFloat()) else MaskStorage.half(value.toFloat()))

    private fun temporal(current: MaskPlane, guide: MaskPlane, history: List<Pair<MaskPlane, MaskPlane>>, kind: VideoEffectMatteKind, detectCuts: Boolean = true, detectCurrentCut: Boolean = false) =
        matte.temporalMatte(current, guide, history, kind, detectCuts, detectCurrentCut).matte

    // Tests/VideoEffectGPUTests.swift:92-108
    @Test fun theWindowOnTheGpuIsTheFiniteEma() {
        val matteGuide = constant(0.25, 80, 45)
        val currentMatte = constant(0.7)
        for (kind in kinds) {
            val values = (0 until kind.capacity - 1).map { ((it * 11) % 19) / 18.0 }
            val history = values.map { constant(it, r8 = true) to matteGuide }
            val output = temporal(currentMatte, matteGuide, history, kind)
            var expected = Math.rint(values.last() * 255) / 255
            for (value in values.dropLast(1).reversed()) expected = expected * kind.prior + Math.rint(value * 255) / 255 * (1 - kind.prior)
            expected = expected * kind.prior + 0.7 * (1 - kind.prior)
            assertEquals("GPU matte window matches the finite EMA including R8 history rounding", expected, output[0, 0].toDouble(), 0.001)
            // The same weights as the window's (YouRec/VideoEffects/VideoEffectMath.swift:49), oldest first.
            val times = (0 until kind.capacity).map { it / 30.0 }
            val window = VideoEffectTemporalWindow.make(times, times.last(), 0.0, kind)!!
            val raw = (values.reversed().map { Math.rint(it * 255) / 255 }) + 0.7
            assertEquals(raw.zip(window.weights).sumOf { it.first * it.second }, output[160, 90].toDouble(), 0.001)
            val repeated = temporal(currentMatte, matteGuide, history, kind)
            assertEquals("Matte history has no hidden render-order state", output, repeated)
            val empty = temporal(currentMatte, matteGuide, emptyList(), kind)
            assertEquals("A new matte stream preserves its current value", 0.7, empty[0, 0].toDouble(), 0.001)
            val cutGuide = constant(0.8, 80, 45)
            val cut = temporal(currentMatte, cutGuide, history, kind)
            assertEquals("An internal scene cut discards earlier matte history", empty, cut)
            // The window keeps capacity − 1 frames of a longer history.
            val longer = history + List(4) { constant(1.0, r8 = true) to matteGuide }
            assertEquals(output, temporal(currentMatte, matteGuide, longer, kind))
        }
    }

    // Tests/VideoEffectGPUTests.swift:109-116
    @Test fun recordedAndSkippedPreviewCuts() {
        val matteGuide = constant(0.25, 80, 45)
        val currentMatte = constant(0.7)
        val lateCutGuide = constant(0.8, 80, 45)
        val oldCuts = listOf(constant(0.1, r8 = true) to matteGuide, constant(1.0, r8 = true) to lateCutGuide)
        val recordedOnly = temporal(currentMatte, matteGuide, oldCuts, VideoEffectMatteKind.ISOLATION, detectCuts = false)
        val newBoundaryOnly = temporal(currentMatte, matteGuide, oldCuts, VideoEffectMatteKind.ISOLATION, detectCuts = false, detectCurrentCut = true)
        assertEquals("Skipped-preview export frames preserve recorded decisions for older boundaries", recordedOnly, newBoundaryOnly)
        val newCut = temporal(currentMatte, lateCutGuide, oldCuts, VideoEffectMatteKind.ISOLATION, detectCuts = false, detectCurrentCut = true)
        val noHistory = temporal(currentMatte, lateCutGuide, emptyList(), VideoEffectMatteKind.ISOLATION)
        assertEquals("A cut on a frame skipped by live preview still closes export history", noHistory, newCut)
        // The decisions themselves (YouRec/VideoEffects/VideoEffects.metal:612-613).
        val guides = listOf(matteGuide, matteGuide, lateCutGuide)
        assertArrayEquals(booleanArrayOf(false, true, false, false, false, false, false, false), matte.cuts(guides))
        assertArrayEquals(BooleanArray(8), matte.cuts(guides, detection = VideoEffectTemporalMatte.CutDetection.RECORDED))
        assertArrayEquals(BooleanArray(8), matte.cuts(guides, detection = VideoEffectTemporalMatte.CutDetection.CURRENT))
        assertArrayEquals(BooleanArray(8), matte.cuts(guides, count = 2))
        val latest = listOf(lateCutGuide, matteGuide, lateCutGuide)
        assertArrayEquals(booleanArrayOf(true, false, false, false, false, false, false, false), matte.cuts(latest, detection = VideoEffectTemporalMatte.CutDetection.CURRENT))
    }

    // YouRec/VideoEffects/VideoEffects.metal:620-623: a large mean change, or a smaller one that moves the histogram.
    @Test fun theCutRule() {
        val grey = constant(0.25, 80, 45)
        assertFalse(matte.cut(constant(0.32, 80, 45), grey))
        assertTrue(matte.cut(constant(0.46, 80, 45), grey))
        // A mean change of 0.1: a cut when it moves every sample to another bin,
        // not when a quarter of the picture moves by 0.4 (a histogram change of 0.25).
        assertTrue(matte.cut(constant(0.35, 80, 45), grey))
        val quarter = MaskPlane.of(80, 45) { x, _ -> MaskStorage.half(if (x < 20) 0.65f else 0.25f) }
        assertFalse(matte.cut(quarter, grey))
        // A different size alone is not a cut: the guides are sampled at the same places.
        assertFalse(matte.cut(constant(0.25, 40, 23), grey))
    }

    // Tests/VideoEffectGPUTests.swift:117-124
    @Test fun keyedAlphaReleasesHistoryWithMotion() {
        val matteGuide = constant(0.25, 80, 45)
        val currentMatte = constant(0.7)
        val oldMatte = constant(0.2, r8 = true)
        val motionValues = listOf(0.0, 0.008, 0.015, 0.019, 0.021).map { difference ->
            val oldGuide = constant(0.25 - difference, 80, 45)
            temporal(currentMatte, matteGuide, listOf(oldMatte to oldGuide), VideoEffectMatteKind.KEY)[0, 0]
        }
        assertTrue(
            "Keyed alpha smoothly releases history between 0.01 and 0.02 perceptual-luma motion: $motionValues",
            motionValues.zipWithNext().all { (a, b) -> a <= b + 0.001f } && abs(motionValues[2] - 0.6375) < 0.004 && abs(motionValues.last() - 0.7) < 0.001,
        )
        // Color Isolation has no such release.
        val isolation = temporal(currentMatte, matteGuide, listOf(oldMatte to constant(0.25 - 0.021, 80, 45)), VideoEffectMatteKind.ISOLATION)[0, 0]
        assertEquals(0.2 / 3 + 0.7 * 2 / 3, isolation.toDouble(), 0.001)
    }

    // Tests/VideoEffectGPUTests.swift:125-130
    @Test fun matteGuidesKeepDarkSceneContrast() {
        val guideInput = EffectImage.flat(320, 180, EffectVector(0.01))
        val darkGuide = matte.guide(guideInput)
        assertEquals(80, darkGuide.width)
        assertEquals(45, darkGuide.height)
        assertEquals("Matte guides preserve dark-scene contrast in perceptual light", EffectColorMath.encoded(0.01), darkGuide[0, 0].toDouble(), 0.001)
        // A quarter, rounded up; the blocks at the edge average what they hold.
        val edge = EffectImage.of(5, 5) { x, y -> EffectVector(if (x == 4 || y == 4) 1.0 else 0.0) }
        val guide = matte.guide(edge)
        assertEquals(2, guide.width)
        assertEquals(MaskStorage.half(0f), guide[0, 0])
        assertEquals(1f, guide[1, 1])
        assertEquals(VideoEffectTemporalMatte.guideSize(1, 1), 1 to 1)
    }

    // Tests/VideoEffectGPUTests.swift:182-197: a fresh stream agrees with its spatial matte; a static raw matte repeated keeps it within R8 precision.
    @Test fun aFreshOrStaticStreamKeepsTheMatte() {
        val guide = constant(0.4, 80, 45)
        val spatial = MaskPlane.of(320, 180) { x, y -> MaskStorage.half(x / 319f * (y / 179f)) }
        for (kind in kinds) {
            assertEquals("A fresh temporal stream agrees with its spatial matte", spatial, temporal(spatial, guide, emptyList(), kind))
            val blended = temporal(spatial, guide, listOf(MaskStorage.r8(spatial) to guide), kind)
            for (i in spatial.data.indices) assertEquals("Repeating a static raw matte preserves the image within R8-history precision", spatial.data[i], blended.data[i], 0.002f)
        }
    }

    // Tests/VideoEffectGPUTests.swift:199-216: missed previews, a resize and a real scene cut; a long gap resets.
    @Test fun liveFramesKeepTheirHistoryAcrossResizeAndCloseItAtCuts() {
        val kind = VideoEffectMatteKind.ISOLATION
        val times = ArrayList<Double>()
        val guides = ArrayList<MaskPlane>()
        val cuts = HashSet<Int>()
        for (index in 0 until 20) {
            if (index % 3 == 1) continue // Deliberately missed/held previews.
            val time = index / 30.0
            val picture = EffectImage.flat(if (index < 10) 320 else 160, if (index < 10) 180 else 90, EffectVector(if (index < 15) 0.2 else 1.0))
            val guide = matte.guide(picture)
            val history = VideoEffectTemporalWindow.make(times + time, time, 0.0, kind, cuts)!!.indices.dropLast(1)
            val cut = guides.isNotEmpty() && matte.cuts(listOf(guide, guides.last()))[0]
            if (cut) cuts += times.size
            val reset = history.isEmpty() || cut
            assertEquals("Live rings retain accepted frames across resize and persist real scene cuts (frame $index)", index == 0 || index == 15, reset)
            assertTrue("Live history remains bounded", history.size < kind.capacity)
            times += time
            guides += guide
        }
        val gap = VideoEffectTemporalWindow.make(times + 1.0, 1.0, 0.0, kind, cuts)!!
        assertEquals("Long source gaps reset live matte history", listOf(times.size), gap.indices)
    }
}
