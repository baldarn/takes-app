package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/** The recorder's live looks (C/LiveVideoEffects.kt), with the numbers of the original's code and tests. */
class LiveVideoEffectsTest {
    // YouRec/VideoEffects/VideoEffectCatalog.swift:60: every effect but the three made in the editor.
    @Test fun offersTwentyOneEffects() {
        assertEquals(21, LiveVideoEffects.offered.size)
        for (kind in listOf(VideoEffectID.NightFix, VideoEffectID.FreezeFrame, VideoEffectID.StepPrinting)) assertFalse(kind in LiveVideoEffects.offered)
        assertTrue(VideoEffectID.CrashZoom in LiveVideoEffects.offered && VideoEffectID.AutoBackground in LiveVideoEffects.offered)
    }

    // LiveVideoEffects.swift:291, 329-335: looks over the whole take with their view on the result, then the Crash Zooms.
    @Test fun recipeKeepsLooksOverTheTakeAndTheCrashes() {
        val key = VideoEffectSettings(VideoEffectID.ChromaKey).with("view", EffectValue.Choice("Matte"))
        val off = VideoEffectSettings(VideoEffectID.Noir).copy(enabled = false)
        val dolly = VideoEffectSettings(VideoEffectID.DollyZoom)
        val template = VideoEffectSettings(VideoEffectID.CrashZoom)
        val pressed = VideoEffectSettings(VideoEffectID.CrashZoom, start = 2.0, end = 3.0)
        val recipe = LiveVideoEffects.recipe(listOf(dolly, key, template, off), listOf(pressed))
        assertEquals(listOf(key.id, off.id, pressed.id), recipe.map { it.id })
        assertEquals("Result", recipe[0].choice("view"))
        assertFalse(recipe[1].enabled)
        assertEquals(0.0, recipe[0].start, 0.0)
        assertEquals(604800.0, recipe[0].end, 0.0)
        assertEquals(2.0, recipe[2].start, 0.0)
        // Tests/VideoEffectIntegrationTests.swift:409: the diagnostic view never becomes the recorded recipe.
        assertEquals("Matte", key.choice("view"))
    }

    // LiveVideoEffects.swift:154-167: a strobe shows only while tried, for its burst.
    @Test fun strobeShowsOnlyWhileTried() {
        val strobe = VideoEffectSettings(VideoEffectID.NegativeStrobe).with("timedBurst", EffectValue.Toggle(true))
        val noir = VideoEffectSettings(VideoEffectID.Noir)
        val effects = listOf(noir, strobe)
        assertEquals(listOf(noir.id), LiveVideoEffects.displayed(effects, 5.0, recording = false, audition = null).first.map { it.id })
        assertEquals(listOf(noir.id, strobe.id), LiveVideoEffects.displayed(effects, 5.0, recording = true, audition = null).first.map { it.id })
        val (shown, audition) = LiveVideoEffects.displayed(effects, 5.0, recording = false, audition = LiveVideoEffects.Audition(strobe.id))
        assertEquals(5.0, audition?.start)
        val burst = strobe.number("burstLength")
        assertEquals(5.0 + burst, shown.last().end, 1e-9)
        val (later, over) = LiveVideoEffects.displayed(effects, 5.0 + burst, recording = false, audition = audition)
        assertEquals(listOf(noir.id), later.map { it.id })
        assertNull(over)
    }

    // VideoEffectAssets.swift:74-83.
    @Test fun prefixStopsBeforeTheEffectWithoutQuantization() {
        val quant = VideoEffectSettings(VideoEffectID.Quantization)
        val under = VideoEffectSettings(VideoEffectID.Undercranking).with("speed", EffectValue.Number(2.0))
        val key = VideoEffectSettings(VideoEffectID.ChromaKey)
        val isolation = VideoEffectSettings(VideoEffectID.ColorIsolation)
        val prefix = LiveVideoEffects.prefix(listOf(isolation, quant, under, key), isolation.id)
        assertEquals(listOf(key.id), prefix.map { it.id })
        val all = LiveVideoEffects.prefix(listOf(quant, under, key), "none")
        assertEquals(1.0, all.first { it.kind == VideoEffectID.Undercranking }.number("speed"), 0.0)
        assertTrue(all.none { it.kind == VideoEffectID.Quantization })
    }

    // Tests/VideoEffectModelTests.swift:359-402.
    @Test fun budgetLowersSizeBeforeLook() {
        val budget = VideoEffectLiveBudget()
        repeat(120) { budget.observe(0.009, 0.02, 1920.0, 1920.0) }
        assertEquals(VideoEffectQuality.Full, budget.quality)
        assertNull(budget.maximumLongSide)
        for (i in 0 until 90) budget.observe(if (i % 15 == 0) 0.015 else 0.005, 0.02, 1920.0, 1920.0)
        assertEquals(1280.0, budget.maximumLongSide)
        repeat(100) { budget.observe(0.014, 0.025, 1920.0, 1280.0) }
        assertEquals(960.0, budget.maximumLongSide)
        repeat(100) { budget.observe(0.0106, 0.018, 1920.0, 960.0) }
        assertEquals(864.0, budget.maximumLongSide)
        repeat(120) { budget.observe(0.005, 0.01, 1920.0, 864.0) }
        assertEquals(864.0, budget.maximumLongSide)
        for ((current, next) in listOf(864.0 to 768.0, 768.0 to 640.0)) {
            repeat(100) { budget.observe(0.018, 0.03, 1920.0, current) }
            assertEquals(next, budget.maximumLongSide)
            assertEquals(VideoEffectQuality.Full, budget.quality)
        }
        repeat(100) { budget.observe(0.018, 0.03, 1920.0, 640.0) }
        assertEquals(VideoEffectQuality.Lite, budget.quality)
        assertEquals(640.0, budget.maximumLongSide)
    }

    @Test fun budgetIgnoresSpikesAndSkipsUnaffordableSizes() {
        val isolated = VideoEffectLiveBudget()
        for (i in 0 until 180) isolated.observe(if (i % 30 == 0) 0.015 else 0.005, 0.02, 1920.0, 1920.0)
        assertNull(isolated.maximumLongSide)
        val overloaded = VideoEffectLiveBudget()
        repeat(24) { overloaded.observe(0.060, 0.070, 3840.0, 3840.0) }
        assertNull(overloaded.maximumLongSide)
        overloaded.observe(0.060, 0.070, 3840.0, 3840.0)
        assertEquals(1280.0, overloaded.maximumLongSide)
        assertEquals(VideoEffectQuality.Full, overloaded.quality)
        repeat(30) { overloaded.observe(0.060, 0.070, 3840.0, 3840.0) }
        assertEquals(1280.0, overloaded.maximumLongSide)
        repeat(120) { overloaded.observe(0.009, 0.020, 3840.0, 1280.0) }
        assertEquals(1280.0, overloaded.maximumLongSide)
        val cold = VideoEffectLiveBudget()
        for (i in 0 until 90) cold.observe(if (i == 0) 0.100 else 0.008, 0.015, 3840.0, 3840.0)
        assertNull(cold.maximumLongSide)
        val ramp = VideoEffectLiveBudget()
        for (i in 0 until 180) ramp.observe(if (i < 15) 0.022 else 0.009, 0.020, 1920.0, 1920.0)
        assertNull(ramp.maximumLongSide)
        val fixedCost = VideoEffectLiveBudget()
        repeat(25) { fixedCost.observe(0.030, 0.035, 1920.0, 1920.0) }
        val size = fixedCost.maximumLongSide ?: 1920.0
        assertTrue(fixedCost.quality == VideoEffectQuality.Full && 0.003 + 0.027 * (size / 1920).pow(2) <= 0.010)
    }

    // The recorder's allowance for a GPU timed at a low clock: 20 ms a frame keeps the size that the original's 10 ms would not.
    @Test fun anotherAllowanceKeepsTheSize() {
        val phone = VideoEffectLiveBudget(gpuSeconds = 0.026)
        val original = VideoEffectLiveBudget()
        repeat(200) {
            phone.observe(0.020, 0.020, 1920.0, 1920.0)
            original.observe(0.020, 0.020, 1920.0, 1920.0)
        }
        assertNull(phone.maximumLongSide)
        assertEquals(1280.0, original.maximumLongSide)
    }

    @Test fun heatLowersTheSizeThenTheLook() {
        val warm = VideoEffectLiveBudget()
        warm.thermal(serious = true, critical = false)
        assertEquals(VideoEffectQuality.Full, warm.quality)
        assertEquals(1280.0, warm.maximumLongSide)
        warm.thermal(serious = false, critical = true)
        assertEquals(VideoEffectQuality.Lite, warm.quality)
        assertEquals(960.0, warm.maximumLongSide)
        assertEquals(LiveVideoEffects.QualityNote.WarmLite, LiveVideoEffects.note(true, warm, lite = true))
        assertEquals(LiveVideoEffects.QualityNote.Reduced, LiveVideoEffects.note(false, VideoEffectLiveBudget().apply { thermal(true, false) }, lite = false))
        assertNull(LiveVideoEffects.note(false, VideoEffectLiveBudget(), lite = false))
        assertTrue(LiveVideoEffects.serious(3) && !LiveVideoEffects.serious(2) && LiveVideoEffects.critical(4) && !LiveVideoEffects.critical(3))
    }

    // Tests/VideoEffectModelTests.swift:342-358.
    @Test fun backgroundPlacementFollowsTheFingers() {
        var placement = VideoEffectBackgroundPlacement(canvas = Size(1080.0, 1080.0), media = Size(1920.0, 1080.0), fill = true)
        placement = placement.applying(VideoEffectPlacementGesture.Pan(Point(0.1, 0.2)))
        assertTrue(abs(placement.x - (0.5 - 0.1 / (16.0 / 9 - 1))) < 1e-9 && placement.y == 0.5)
        placement = placement.applying(VideoEffectPlacementGesture.Pan(Point(100.0, -100.0)))
        assertTrue(placement.x == 0.0 && placement.y == 0.5)
        var fit = VideoEffectBackgroundPlacement(canvas = Size(1080.0, 1080.0), media = Size(1920.0, 1080.0), fill = false)
        fit = fit.applying(VideoEffectPlacementGesture.Pan(Point(0.1, 0.1)))
        assertTrue(fit.x == 0.5 && abs(fit.y - (0.5 + 0.1 / (1 - 9.0 / 16))) < 1e-9)
        var anchored = VideoEffectBackgroundPlacement(zoom = 2.0, canvas = Size(1000.0, 1000.0), media = Size(1000.0, 1000.0), fill = true)
        anchored = anchored.applying(VideoEffectPlacementGesture.Pinch(1.5, Point(0.3, 0.7), Point(0.35, 0.65)))
        val imageX = (0.3 + 0.5) / 2
        val imageY = (0.7 + 0.5) / 2
        assertTrue(abs(-2 * anchored.x + imageX * 3 - 0.35) < 1e-9 && abs(-2 * anchored.y + imageY * 3 - 0.65) < 1e-9)
        anchored = anchored.applying(VideoEffectPlacementGesture.Pinch(0.01, Point(0.5, 0.5), Point(0.5, 0.5)))
        assertTrue(anchored.zoom == 1.0 && anchored.movementLocked)
        var locked = VideoEffectBackgroundPlacement(x = 0.2, y = 0.3, canvas = Size(100.0, 100.0), media = Size(100.0, 100.0), fill = true)
        locked = locked.applying(VideoEffectPlacementGesture.Pan(Point(0.1, 0.2)))
        assertTrue(locked.x == 0.2 && locked.y == 0.3)
    }

    // VideoEffectAssets.swift:121-132: the median of each channel of the 9 × 9 patch, so a stray pixel does not count.
    @Test fun pickedColourIsTheMedianOfThePatch() {
        val bytes = ByteArray(9 * 9 * 4) { i -> if (i % 4 == 1) 200.toByte() else 10 }
        bytes[0] = 255.toByte()
        val color = LiveVideoEffects.medianColor(bytes, 9, 9)
        assertEquals(10 / 255.0, color.red, 1e-9)
        assertEquals(200 / 255.0, color.green, 1e-9)
        assertTrue(LiveVideoEffects.patch(0.0, 1.0, 100, 50).contentEquals(intArrayOf(0, 45, 5, 50)))
        assertTrue(LiveVideoEffects.patch(0.5, 0.5, 100, 50).contentEquals(intArrayOf(45, 20, 54, 29)))
    }
}
