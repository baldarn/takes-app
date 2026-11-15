package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionGeometryTest {
    private val canvas = Size(200.0, 100.0)
    private fun timing(settings: TransitionSettings, edge: Int = 0, factor: Double = 1.0) = TransitionTiming.of(settings, factor, edge)

    // Translated from TransitionTests.swift. The original checks rendered pixels and whole-timeline
    // lengths (two 4 s clips); here the same cases are checked on the numbers behind them.

    @Test fun flashCutUsesExactSingleFramesOfTheNextScene() {
        val flashes = TransitionSettings(style = TransitionStyle.FlashCut, duration = 1.0)
        for (frame in 0 until 30) {
            assertEquals(frame in flashes.flashFrames(1.0), TransitionGeometry.flashShowsSecond(flashes, frame / 30.0))
        }
    }

    @Test fun midpointHoldAddsToTheMovingOverlapAndKeepsTheEvenMix() {
        val join = timing(TransitionSettings(duration = 1.0, hold = 2.0))
        assertEquals(0.5, join.progress(1.0), 0.0)
        assertEquals(0.5, join.progress(2.0), 0.0)
        // 4 + 4 − 3 = 5 s.
        assertEquals(3.0, join.overlap, 0.00001)
    }

    @Test fun solidColorHoldAddsTimeWithoutConsumingFootage() {
        val color = TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0, hold = 2.0, fadeColor = TransitionColor(0.2, 0.7, 0.4))
        // 4 + 4 + 2 = 10 s: each clip only gives half the fade.
        assertEquals(-2.0, timing(color).overlap, 0.0)
        assertEquals(0.5, timing(color).leftCost, 0.0)
        assertEquals(0.5, timing(color).rightCost, 0.0)
    }

    @Test fun stillHoldsTheFinalFrameBeforeDissolvingIntoTheNextScene() {
        val freeze = TransitionSettings(style = TransitionStyle.Still, duration = 2.0, stillDissolve = 0.5)
        val join = timing(freeze)
        assertEquals(-2.0, join.overlap, 0.0)
        // The picture effect runs from the end of the first clip, 4 s, to 6.5 s.
        assertEquals(0.0, join.leftEnd, 0.0)
        assertEquals(2.5, join.videoDuration, 0.0)
        // Opening still holds black before source footage: 8 + 1 = 9 s.
        assertEquals(1.0, timing(TransitionSettings(style = TransitionStyle.Still, duration = 1.0), edge = -1).rightStart, 0.0)
        // Closing still includes the final fade to black: 8 + 2.5 = 10.5 s.
        val closing = timing(freeze, edge = 1)
        assertEquals(2.5, closing.upper - closing.leftEnd, 0.0)
    }

    // Own tests.

    @Test fun flashFramesAreSingleSeparatedAndInsideTheTransition() {
        assertEquals(listOf(13, 22, 27), TransitionSettings(style = TransitionStyle.FlashCut).flashFrames(1.0))
        // Too short for a flash between a first and a last frame.
        assertEquals(emptyList<Int>(), TransitionSettings().flashFrames(0.05))
        assertEquals(listOf(1), TransitionSettings().flashFrames(0.1))
        for (count in listOf(1, 2, 3, 7, 20)) for (acceleration in listOf(0.25, 1.0, 2.0, 4.0)) for (duration in listOf(0.1, 0.2, 0.5, 1.0, 3.0)) for (fps in listOf(24.0, 30.0, 60.0)) {
            val frames = maxOf(1, Math.round(duration * fps).toInt())
            val flashes = TransitionSettings(flashCount = count, flashAcceleration = acceleration).flashFrames(duration, fps)
            assertEquals(minOf(count, (frames - 1) / 2), flashes.size)
            assertTrue(flashes.all { it in 1..frames - 2 })
            assertTrue(flashes.zipWithNext().all { (a, b) -> b - a >= 2 })
        }
        // Stronger acceleration puts the flashes earlier.
        val gentle = TransitionSettings(flashAcceleration = 1.0).flashFrames(2.0)
        val strong = TransitionSettings(flashAcceleration = 4.0).flashFrames(2.0)
        assertTrue(gentle.zip(strong).all { (a, b) -> b >= a } && gentle != strong)
    }

    @Test fun flashCutEndsOnTheSecondClip() {
        val flashes = TransitionSettings(style = TransitionStyle.FlashCut, duration = 1.0)
        assertFalse(TransitionGeometry.flashShowsSecond(flashes, 0.0))
        assertTrue(TransitionGeometry.flashShowsSecond(flashes, 1.0))
        // Fitted to half a second, the frames are those of the real length.
        assertEquals(TransitionSettings().flashFrames(0.5), (0 until 15).filter { TransitionGeometry.flashShowsSecond(flashes, it / 15.0, duration = 0.5) })
    }

    @Test fun wipeTravelsRightUncoveringTheSecondClipBehindIt() {
        val settings = TransitionSettings(style = TransitionStyle.Wipe)
        val half = TransitionGeometry.wipe(settings, 0.5, canvas)
        assertEquals(200.0, half.span, 1e-9)
        assertEquals(7.0, half.feather, 1e-9)
        assertEquals(100.0, half.center.x, 1e-9); assertEquals(50.0, half.center.y, 1e-9)
        assertEquals(1.0, half.secondShare(Point(50.0, 50.0)), 0.0)
        assertEquals(0.5, half.secondShare(Point(100.0, 20.0)), 1e-9)
        assertEquals(0.0, half.secondShare(Point(150.0, 50.0)), 0.0)
        // The soft band is wholly outside the picture at both ends.
        val start = TransitionGeometry.wipe(settings, 0.0, canvas)
        val end = TransitionGeometry.wipe(settings, 1.0, canvas)
        for (x in listOf(0.0, 100.0, 200.0)) {
            assertEquals(0.0, start.secondShare(Point(x, 50.0)), 1e-9)
            assertEquals(1.0, end.secondShare(Point(x, 50.0)), 1e-9)
        }
        // Eased: a quarter of the time is less than a quarter of the way.
        assertTrue(TransitionGeometry.wipe(settings, 0.25, canvas).center.x < 50)
    }

    @Test fun wipeDirectionFollowsAxisReverseAndAngle() {
        assertEquals(0.0, TransitionSettings().angle, 0.0)
        assertEquals(90.0, TransitionSettings(axis = TransitionAxis.Vertical).angle, 0.0)
        assertEquals(180.0, TransitionSettings(reverse = true).angle, 0.0)
        assertEquals(217.0, TransitionSettings(wipeAngle = 37.0, reverse = true, axis = TransitionAxis.Vertical).angle, 0.0)
        // Reversed, the right side is uncovered first.
        val reversed = TransitionGeometry.wipe(TransitionSettings(reverse = true), 0.5, canvas)
        assertEquals(1.0, reversed.secondShare(Point(150.0, 50.0)), 0.0)
        assertEquals(0.0, reversed.secondShare(Point(50.0, 50.0)), 0.0)
        // Vertical travels down the screen: the top (high y, bottom-up) is uncovered first.
        val down = TransitionGeometry.wipe(TransitionSettings(axis = TransitionAxis.Vertical), 0.5, canvas)
        assertEquals(100.0, down.span, 1e-9)
        assertEquals(1.0, down.secondShare(Point(100.0, 90.0)), 0.0)
        assertEquals(0.0, down.secondShare(Point(100.0, 10.0)), 0.0)
        // A diagonal spans both dimensions, so its corners are covered too.
        val diagonal = TransitionGeometry.wipe(TransitionSettings(wipeAngle = 45.0), 1.0, canvas)
        assertEquals(300 * Math.sqrt(0.5), diagonal.span, 1e-9)
        for (corner in listOf(Point(0.0, 0.0), Point(200.0, 0.0), Point(0.0, 100.0), Point(200.0, 100.0))) {
            assertEquals(1.0, diagonal.secondShare(corner), 1e-9)
            assertEquals(0.0, TransitionGeometry.wipe(TransitionSettings(wipeAngle = 45.0), 0.0, canvas).secondShare(corner), 1e-9)
        }
    }

    @Test fun hardEdgeIsOnePixelWide() {
        val hard = TransitionGeometry.wipe(TransitionSettings(hardEdge = true), 0.5, canvas)
        assertEquals(0.5, hard.feather, 0.0)
        assertEquals(1.0, hard.secondShare(Point(99.0, 50.0)), 0.0)
        assertEquals(0.0, hard.secondShare(Point(101.0, 50.0)), 0.0)
        // On a tiny canvas a soft edge is still at least two pixels.
        assertEquals(1.0, TransitionGeometry.wipe(TransitionSettings(), 0.5, Size(20.0, 10.0)).feather, 0.0)
    }

    @Test fun splitScreenDividesAtTheDivider() {
        val side = TransitionSettings(style = TransitionStyle.Split, divider = 0.35)
        assertEquals(Rect(0.0, 0.0, 70.0, 100.0) to Rect(70.0, 0.0, 130.0, 100.0), TransitionGeometry.splitRects(side, canvas))
        assertEquals(Rect(69.5, 0.0, 1.0, 100.0), TransitionGeometry.splitLine(side, canvas))
        // Top / bottom: the first clip is on top, which is the high end in bottom-up coordinates.
        val stacked = side.copy(axis = TransitionAxis.Vertical)
        assertEquals(Rect(0.0, 65.0, 200.0, 35.0) to Rect(0.0, 0.0, 200.0, 65.0), TransitionGeometry.splitRects(stacked, canvas))
        assertEquals(Rect(0.0, 64.5, 200.0, 1.0), TransitionGeometry.splitLine(stacked, canvas))
        assertEquals(4.32, TransitionGeometry.splitLine(side, Size(3840.0, 2160.0)).width, 1e-9)
    }

    @Test fun splitScreenFadesInHoldsAndFadesOut() {
        assertEquals(TransitionGeometry.SplitMix(1.0, 0.0, 0.0), TransitionGeometry.splitMix(0.0))
        val entering = TransitionGeometry.splitMix(0.075)
        assertEquals(0.5, entering.first, 1e-9); assertEquals(0.5, entering.pair, 1e-9)
        assertEquals(TransitionGeometry.SplitMix(0.0, 1.0, 0.0), TransitionGeometry.splitMix(0.15))
        assertEquals(TransitionGeometry.SplitMix(0.0, 1.0, 0.0), TransitionGeometry.splitMix(0.5))
        val leaving = TransitionGeometry.splitMix(0.925)
        assertEquals(0.5, leaving.pair, 1e-9); assertEquals(0.5, leaving.second, 1e-9)
        assertEquals(1.0, TransitionGeometry.splitMix(1.0).second, 1e-9)
    }

    @Test fun eachHalfShowsItsOwnFramingOfItsClip() {
        val source = Size(1920.0, 1080.0)
        val half = Rect(0.0, 0.0, 960.0, 1080.0)
        assertEquals(TransitionGeometry.Framed(Rect(480.0, 0.0, 960.0, 1080.0), 1.0), TransitionGeometry.framed(TransitionFraming(), source, half))
        // Panned fully left and zoomed in twice.
        assertEquals(TransitionGeometry.Framed(Rect(0.0, 270.0, 480.0, 540.0), 2.0), TransitionGeometry.framed(TransitionFraming(x = 0.0, zoom = 2.0), source, half))
    }

    @Test fun hitchcockRevealsShadowsBeforeHighlightsOrTheReverse() {
        val shadows = TransitionGeometry.hitchcock(TransitionSettings(style = TransitionStyle.Hitchcock), 0.5)
        assertEquals(1.0, shadows.secondShare(0.0), 0.0)
        assertEquals(0.5, shadows.secondShare(0.5), 1e-9)
        assertEquals(0.0, shadows.secondShare(1.0), 0.0)
        // HDR highlights are keyed as white and reveal before the final frame, without a last-frame pop.
        val late = TransitionGeometry.hitchcock(TransitionSettings(), 0.99)
        assertEquals(1.0, late.secondShare(3.0, 3.0, 3.0), 0.0)
        val highlights = TransitionGeometry.hitchcock(TransitionSettings(highlightsFirst = true), 0.5)
        assertEquals(0.0, highlights.secondShare(0.0), 0.0)
        assertEquals(0.5, highlights.secondShare(0.5), 1e-9)
        assertEquals(1.0, highlights.secondShare(1.0), 0.0)
        for (order in listOf(null, true)) for (luma in listOf(0.0, 0.3, 1.0)) {
            assertEquals(0.0, TransitionGeometry.hitchcock(TransitionSettings(highlightsFirst = order), 0.0).secondShare(luma), 1e-9)
            assertEquals(1.0, TransitionGeometry.hitchcock(TransitionSettings(highlightsFirst = order), 1.0).secondShare(luma), 1e-9)
        }
        // Green weighs most in brightness.
        assertTrue(shadows.secondShare(0.0, 0.8, 0.0) < shadows.secondShare(0.8, 0.0, 0.0))
        assertEquals(shadows.secondShare(0.5), shadows.secondShare(0.5, 0.5, 0.5), 1e-9)
    }

    @Test fun whipPanSweepsBothClipsTheSameWay() {
        val settings = TransitionSettings(style = TransitionStyle.Whip)
        assertEquals(TransitionGeometry.Whip(false, -65.0, 65.0, 12.0, 0.5), TransitionGeometry.whip(settings, 0.5, canvas))
        val start = TransitionGeometry.whip(settings, 0.0, canvas)
        assertEquals(0.0, start.firstOffset, 0.0); assertEquals(130.0, start.secondOffset, 0.0); assertEquals(0.0, start.blurRadius, 1e-9)
        val end = TransitionGeometry.whip(settings, 1.0, canvas)
        assertEquals(-130.0, end.firstOffset, 0.0); assertEquals(0.0, end.secondOffset, 0.0); assertEquals(0.0, end.blurRadius, 1e-9)
        val back = TransitionGeometry.whip(settings.copy(reverse = true), 0.5, canvas)
        assertEquals(65.0, back.firstOffset, 0.0); assertEquals(-65.0, back.secondOffset, 0.0)
        val vertical = TransitionGeometry.whip(settings.copy(axis = TransitionAxis.Vertical), 0.0, canvas)
        assertTrue(vertical.vertical)
        assertEquals(65.0, vertical.secondOffset, 0.0)
    }

    @Test fun defocusChangesClipInsideTheBlur() {
        val settings = TransitionSettings(style = TransitionStyle.Defocus)
        val middle = TransitionGeometry.defocus(settings, 0.5, canvas)
        assertEquals(3.75, middle.blurRadius, 1e-9)
        assertEquals(0.5, middle.mix, 1e-9)
        assertEquals(0.0, TransitionGeometry.defocus(settings, 0.35, canvas).mix, 0.0)
        assertEquals(1.0, TransitionGeometry.defocus(settings, 0.66, canvas).mix, 0.0)
        assertEquals(0.0, TransitionGeometry.defocus(settings, 0.0, canvas).blurRadius, 1e-9)
        assertEquals(5.0, TransitionGeometry.defocus(settings.copy(blurAmount = 1.0), 0.5, canvas).blurRadius, 1e-9)
    }

    @Test fun largeBlursWorkOnFewerPixels() {
        val hd = Size(1920.0, 1080.0)
        assertEquals(1.0, TransitionGeometry.blurScale(0.0, hd), 0.0)
        assertEquals(1.0, TransitionGeometry.blurScale(2.0, hd), 0.0)
        assertEquals(0.5, TransitionGeometry.blurScale(12.0, hd), 0.0)
        assertEquals(0.25, TransitionGeometry.blurScale(100.0, Size(3840.0, 2160.0)), 0.0)
        assertEquals(1.0, TransitionGeometry.blurScale(100.0, Size(640.0, 360.0)), 0.0)
    }

    @Test fun progressRunsThroughMotionAndHold() {
        val plain = timing(TransitionSettings(duration = 1.0))
        assertEquals(0.25, plain.progress(0.25), 0.0)
        assertEquals(0.75, plain.progress(0.75), 0.0)
        assertEquals(0.0, plain.progress(-1.0), 0.0)
        assertEquals(1.0, plain.progress(2.0), 0.0)
        assertEquals(0.5, plain.cut, 0.0)
        val held = timing(TransitionSettings(duration = 1.0, hold = 2.0))
        assertEquals(0.75, held.progress(2.75), 1e-12)
        assertEquals(1.5, held.cut, 0.0)
        val freeze = timing(TransitionSettings(style = TransitionStyle.Still, duration = 2.0, stillDissolve = 0.5))
        assertEquals(0.0, freeze.progress(1.0), 0.0)
        assertEquals(0.5, freeze.progress(2.25), 1e-12)
        assertEquals(1.0, freeze.progress(2.5), 0.0)
        assertEquals(2.0, freeze.cut, 0.0)
        val cut = timing(TransitionSettings(style = TransitionStyle.Still, duration = 2.0))
        assertEquals(0.0, cut.progress(1.99), 0.0)
        assertEquals(1.0, cut.progress(2.0), 0.0)
        // No transition at all.
        assertNull(TransitionTiming.of(null).style)
        assertEquals(0.0, TransitionTiming.of(null).videoDuration, 0.0)
    }

    @Test fun holdOnlyAppliesToStylesWithAMidpoint() {
        assertEquals(2.0, TransitionSettings(style = TransitionStyle.Wipe, hold = 2.0).holdDuration, 0.0)
        assertEquals(0.0, TransitionSettings(style = TransitionStyle.FlashCut, hold = 2.0).holdDuration, 0.0)
        assertEquals(0.0, TransitionSettings(style = TransitionStyle.Still, hold = 2.0).holdDuration, 0.0)
        assertEquals(1.0, timing(TransitionSettings(style = TransitionStyle.FlashCut, duration = 1.0, hold = 2.0)).videoDuration, 0.0)
        assertEquals(TransitionStyle.entries.toSet() - TransitionStyle.FlashCut - TransitionStyle.Still, TransitionStyle.entries.filter { it.supportsHold }.toSet())
    }

    @Test fun soundFollowsThePictureUnlessSet() {
        assertEquals(3.0, TransitionSettings(duration = 1.0, hold = 2.0).defaultAudioBlend, 0.0)
        assertEquals(1.0, TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0, hold = 2.0).defaultAudioBlend, 0.0)
        assertEquals(0.0, TransitionSettings(style = TransitionStyle.Still, duration = 1.0).defaultAudioBlend, 0.0)
        val plain = timing(TransitionSettings(duration = 1.0))
        assertEquals(0.0..1.0, plain.audioIn)
        assertEquals(plain.audioIn, plain.audioOut)
        // A fade goes silent before its hold and comes back after it.
        val fade = timing(TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0, hold = 2.0))
        assertEquals(0.0..0.5, fade.audioOut)
        assertEquals(2.5..3.0, fade.audioIn)
        // A J-cut: the next clip's sound starts half a second before the picture changes.
        val lead = timing(TransitionSettings(duration = 1.0, audioOffset = -0.5, audioBlend = 0.0))
        assertTrue(TransitionSettings(audioOffset = -0.5).customAudio && !TransitionSettings().customAudio)
        assertEquals(-0.5..-0.5, lead.audioIn)
        assertEquals(-0.5, lead.rightStart, 0.0); assertEquals(-0.5, lead.lower, 0.0); assertEquals(1.0, lead.upper, 0.0)
        assertEquals(1.5, lead.overlap, 0.0); assertEquals(1.5, lead.leftCost, 0.0); assertEquals(1.5, lead.rightCost, 0.0)
        // Split screen with one side's sound switches in a few milliseconds.
        val split = TransitionSettings(style = TransitionStyle.Split, duration = 1.0)
        assertEquals(0.992..1.0, timing(split.copy(audio = TransitionAudio.First)).audioIn)
        assertEquals(0.0..0.008, timing(split.copy(audio = TransitionAudio.Second)).audioIn)
        assertEquals(0.0..1.0, timing(split).audioIn)
    }

    @Test fun edgesAndFittingScaleTheTransition() {
        val edgeHold = TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0, hold = 10.0)
        val opening = timing(edgeHold, edge = -1)
        assertEquals(11.0, opening.videoDuration, 0.0)
        assertEquals(0.0, opening.rightStart, 0.0); assertEquals(11.0, opening.rightCost, 0.0); assertEquals(0.0, opening.leftCost, 0.0)
        val closing = timing(edgeHold, edge = 1)
        assertEquals(11.0, closing.leftEnd, 0.0); assertEquals(11.0, closing.leftCost, 0.0); assertEquals(0.0, closing.rightCost, 0.0)
        // Between two clips a silent color hold is not shortened with the fade; at an edge it is.
        assertEquals(10.0, timing(edgeHold, factor = 0.5).hold, 0.0)
        assertEquals(0.5, timing(edgeHold, factor = 0.5).motion, 0.0)
        assertEquals(5.0, timing(edgeHold, edge = -1, factor = 0.5).hold, 0.0)
        assertEquals(1.0, timing(TransitionSettings(duration = 2.0), factor = 0.5).videoDuration, 0.0)
        // Shorter than a frame is no transition.
        val tiny = timing(TransitionSettings(duration = 0.01))
        assertEquals(0.0, tiny.videoDuration, 0.0)
        assertEquals(1.0, tiny.progress(0.0), 0.0)
    }

    @Test fun validationRangesMatchTheOriginal() {
        val t = TransitionSettings()
        assertTrue(t.valid)
        assertTrue(t.copy(duration = 604800.0).valid)
        assertFalse(t.copy(duration = 0.0).valid); assertFalse(t.copy(duration = 604801.0).valid); assertFalse(t.copy(duration = Double.NaN).valid)
        assertFalse(t.copy(divider = 0.14).valid); assertFalse(t.copy(divider = 0.86).valid)
        assertFalse(t.copy(fadeColor = TransitionColor(1.1, 0.0, 0.0)).valid)
        assertFalse(t.copy(first = TransitionFraming(zoom = 4.1)).valid); assertFalse(t.copy(second = TransitionFraming(x = -0.1)).valid)
        assertTrue(t.copy(first = TransitionFraming(zoom = 4.0)).valid)
        assertFalse(t.copy(hold = -1.0).valid); assertFalse(t.copy(audioBlend = Double.POSITIVE_INFINITY).valid); assertFalse(t.copy(stillDissolve = -0.1).valid)
        assertTrue(t.copy(audioOffset = -2.0).valid); assertFalse(t.copy(audioOffset = -604801.0).valid)
        assertTrue(t.copy(wipeAngle = -360.0).valid); assertFalse(t.copy(wipeAngle = 361.0).valid)
        assertFalse(t.copy(flashCount = 0).valid); assertFalse(t.copy(flashCount = 21).valid); assertTrue(t.copy(flashCount = 20).valid)
        assertFalse(t.copy(flashAcceleration = 0.2).valid); assertFalse(t.copy(flashAcceleration = 4.1).valid)
        assertFalse(t.copy(blurAmount = 1.1).valid); assertTrue(t.copy(blurAmount = 0.0).valid)
    }

    @Test fun newFeaturesAreThoseBeyondTheFirstFourStyles() {
        for (style in listOf(TransitionStyle.Dissolve, TransitionStyle.FadeColor, TransitionStyle.Wipe, TransitionStyle.Split)) {
            assertFalse(TransitionSettings(style = style).newFeatures)
        }
        for (style in listOf(TransitionStyle.Hitchcock, TransitionStyle.Whip, TransitionStyle.FlashCut, TransitionStyle.Still, TransitionStyle.Defocus)) {
            assertTrue(TransitionSettings(style = style).newFeatures)
        }
        assertTrue(TransitionSettings(hold = 0.0).newFeatures)
        assertTrue(TransitionSettings(wipeAngle = 0.0).newFeatures)
        assertTrue(TransitionSettings(hardEdge = true).newFeatures)
        assertFalse(TransitionSettings(hardEdge = false).newFeatures)
        assertTrue(TransitionSettings(fadeColor = TransitionColor(1.0, 1.0, 1.0)).newFeatures)
        assertFalse(TransitionSettings(fadeColor = TransitionColor.BLACK).newFeatures)
    }

    @Test fun settingsWriteTheOriginalFieldsAndReadTheExistingTransition() {
        val framing = """{"x":0.5,"y":0.5,"zoom":1.0}"""
        assertEquals(
            """{"id":"A","nextClipID":"B","style":"dissolve","duration":0.5,"axis":"horizontal","divider":0.5,"reverse":false,""" +
                """"audio":"crossfade","first":$framing,"second":$framing}""",
            documentJson.encodeToString(TransitionSettings.serializer(), TransitionSettings(id = "A", nextClipID = "B")),
        )
        val full = TransitionSettings(
            style = TransitionStyle.Wipe, axis = TransitionAxis.Vertical, audio = TransitionAudio.Second, fadeColor = TransitionColor(0.2, 0.7, 0.4),
            hold = 1.0, audioOffset = -0.5, audioBlend = 0.25, wipeAngle = 37.0, hardEdge = true, highlightsFirst = true, flashCount = 5,
            flashAcceleration = 3.0, stillDissolve = 0.5, blurAmount = 0.6,
        )
        assertEquals(full, documentJson.decodeFromString(TransitionSettings.serializer(), documentJson.encodeToString(TransitionSettings.serializer(), full)))
        assertEquals(
            listOf("dissolve", "fadeBlack", "wipe", "split", "hitchcock", "whip", "flashCut", "still", "defocus"),
            TransitionStyle.entries.map { documentJson.encodeToString(TransitionStyle.serializer(), it).trim('"') },
        )
        // What the port saves today is read unchanged, and its two styles are read back by it.
        val existing = ClipTransition(id = "A", nextClipID = "B", style = ClipTransition.Style.FadeBlack, duration = 1.0)
        val read = documentJson.decodeFromString(TransitionSettings.serializer(), documentJson.encodeToString(ClipTransition.serializer(), existing))
        assertEquals(TransitionSettings(id = "A", nextClipID = "B", style = TransitionStyle.FadeColor, duration = 1.0), read)
        assertEquals(existing, documentJson.decodeFromString(ClipTransition.serializer(), documentJson.encodeToString(TransitionSettings.serializer(), read)))
    }
}
