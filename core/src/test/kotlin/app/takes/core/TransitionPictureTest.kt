package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Every style's frame at progress 0, 0.25, 0.5 and 1, against the original's
 * renderer (YouRec/TransitionRenderer.swift, `composite`, l. 189-267) and its
 * tests (Tests/TransitionTests.swift). Line numbers are the original's.
 */
class TransitionPictureTest {
    /** The canvas of the original's pixel tests, TransitionTests.swift:65. */
    private val canvas = Size(160.0, 90.0)
    private val wide = Size(200.0, 100.0)
    private val tiny = 1e-9

    private fun settings(style: TransitionStyle) = TransitionSettings(style = style, duration = 1.0)
    private fun frame(settings: TransitionSettings, progress: Double, edge: Int = 0) = TransitionPicture.frame(settings, progress, canvas, edge)

    // t·t·(3 − 2t), TransitionRenderer.swift:191.
    private val quarter = 0.15625
    private val threeQuarters = 0.84375

    @Test fun everyStyleShowsTheFirstPictureAtZeroAndTheSecondAtOne() {
        // "if t <= 0 { return first }; if t >= 1 { return second }", TransitionRenderer.swift:192.
        for (style in TransitionStyle.entries) {
            assertSame(TransitionFrame.First, frame(settings(style), 0.0))
            assertSame(TransitionFrame.First, frame(settings(style), -3.0))
            assertSame(TransitionFrame.Second, frame(settings(style), 1.0))
            assertSame(TransitionFrame.Second, frame(settings(style), 7.0))
        }
    }

    @Test fun dissolveIsEased() {
        // CIDissolveTransition with inputTime = eased, TransitionRenderer.swift:194-195.
        assertEquals(TransitionFrame.Dissolve(quarter), frame(settings(TransitionStyle.Dissolve), 0.25))
        assertEquals(TransitionFrame.Dissolve(0.5), frame(settings(TransitionStyle.Dissolve), 0.5))
        assertEquals(TransitionFrame.Dissolve(threeQuarters), frame(settings(TransitionStyle.Dissolve), 0.75))
        assertEquals(1 - quarter, TransitionPicture.dissolve(0.25).first, tiny)
    }

    @Test fun stillDissolvesOutOfTheHeldFrameWithTheSameEase() {
        // "case .dissolve, .still", TransitionRenderer.swift:194.
        assertEquals(TransitionFrame.Dissolve(quarter), frame(settings(TransitionStyle.Still), 0.25))
        assertEquals(TransitionFrame.Dissolve(0.5), frame(settings(TransitionStyle.Still), 0.5))
    }

    @Test fun fadeThroughColorIsLinearInTwoPhases() {
        val fade = settings(TransitionStyle.FadeColor)
        // t < 0.5: first to colour by min(1, 2t), TransitionRenderer.swift:198.
        assertEquals(TransitionFrame.FadeColor(TransitionColor.BLACK, 0.5, 0.5, 0.0), frame(fade, 0.25))
        // "Fade through black midpoint is black", TransitionTests.swift:80-81.
        assertEquals(TransitionFrame.FadeColor(TransitionColor.BLACK, 0.0, 1.0, 0.0), frame(fade, 0.5))
        // Otherwise colour to second by max(0, 2t − 1), TransitionRenderer.swift:199.
        assertEquals(TransitionFrame.FadeColor(TransitionColor.BLACK, 0.0, 0.5, 0.5), frame(fade, 0.75))
        val early = frame(fade, 0.1) as TransitionFrame.FadeColor
        assertEquals(0.8, early.first, tiny); assertEquals(0.2, early.solid, tiny); assertEquals(0.0, early.second, 0.0)
    }

    @Test fun fadeUsesTheChosenColorAndOtherStylesFillWithBlack() {
        // "Color fade midpoint uses the selected sRGB color", TransitionTests.swift:84-86.
        val green = TransitionColor(0.1, 0.8, 0.2)
        val fade = settings(TransitionStyle.FadeColor).copy(fadeColor = green)
        assertEquals(green, (frame(fade, 0.5) as TransitionFrame.FadeColor).color)
        // fill: the fade's colour for a fade, black for anything else, TransitionRenderer.swift:184-187.
        assertEquals(green, TransitionPicture.fill(fade))
        assertEquals(TransitionColor.BLACK, TransitionPicture.fill(fade.copy(style = TransitionStyle.Wipe)))
        assertEquals(TransitionColor.BLACK, TransitionPicture.fill(settings(TransitionStyle.FadeColor)))
    }

    @Test fun aFadeAtAnEdgeOfTheTimelineIsAnEasedDissolveWithTheColor() {
        // "if settings.style == .fadeColor && edge != 0 { settings.style = .dissolve }", VideoToolCompositor.swift:294 (and 247).
        val fade = settings(TransitionStyle.FadeColor).copy(fadeColor = TransitionColor(1.0, 0.0, 0.0))
        for (edge in listOf(-1, 1)) {
            assertEquals(TransitionStyle.Dissolve, TransitionPicture.drawnStyle(fade, edge))
            assertEquals(TransitionFrame.Dissolve(quarter), frame(fade, 0.25, edge))
            assertEquals(TransitionFrame.Dissolve(0.5), frame(fade, 0.5, edge))
        }
        assertEquals(TransitionStyle.FadeColor, TransitionPicture.drawnStyle(fade, 0))
        assertEquals(TransitionStyle.Wipe, TransitionPicture.drawnStyle(settings(TransitionStyle.Wipe), -1))
    }

    @Test fun wipeEdgeTravelsWithTheEase() {
        // span 160, feather max(1, 0.035·160) = 5.6, distance (span + 2·feather)·eased − span/2 − feather, TransitionRenderer.swift:201-204.
        val start = TransitionPicture.wipe(settings(TransitionStyle.Wipe), 0.0, canvas).edge
        assertEquals(-5.6, start.center.x, tiny); assertEquals(5.6, start.feather, tiny)
        val early = (frame(settings(TransitionStyle.Wipe), 0.25) as TransitionFrame.Wipe).edge
        assertEquals(80 + 171.2 * quarter - 85.6, early.center.x, tiny)
        val middle = (frame(settings(TransitionStyle.Wipe), 0.5) as TransitionFrame.Wipe).edge
        assertEquals(80.0, middle.center.x, tiny); assertEquals(45.0, middle.center.y, tiny)
        assertEquals(0.5, middle.secondShare(Point(80.0, 10.0)), tiny)
        assertEquals(1.0, middle.secondShare(Point(20.0, 10.0)), 0.0)
        assertEquals(0.0, middle.secondShare(Point(140.0, 10.0)), 0.0)
        assertEquals(165.6, TransitionPicture.wipe(settings(TransitionStyle.Wipe), 1.0, canvas).edge.center.x, tiny)
    }

    @Test fun hardWipeFollowsItsDiagonal() {
        // 45°, hard, p = 0.5: pixel (20, 80) is the second clip, (140, 10) the first, TransitionTests.swift:90-92.
        val wipe = (frame(TransitionSettings(style = TransitionStyle.Wipe, duration = 1.0, hardEdge = true, wipeAngle = 45.0), 0.5) as TransitionFrame.Wipe).edge
        assertEquals(0.5, wipe.feather, 0.0)
        assertEquals(1.0, wipe.secondShare(Point(20.5, 80.5)), 0.0)
        assertEquals(0.0, wipe.secondShare(Point(140.5, 10.5)), 0.0)
    }

    @Test fun splitScreenIsLinearWithShortEnds() {
        val pair = settings(TransitionStyle.Split).copy(divider = 0.35)
        // edge = 0.15: t/edge of the pair before it, the pair alone between, TransitionRenderer.swift:262-265.
        val entering = TransitionPicture.split(pair, 0.075, canvas)
        assertEquals(0.5, entering.mix.first, tiny); assertEquals(0.5, entering.mix.pair, tiny); assertEquals(0.0, entering.mix.second, 0.0)
        for (progress in listOf(0.25, 0.5, 0.75)) {
            assertEquals(TransitionGeometry.SplitMix(0.0, 1.0, 0.0), (frame(pair, progress) as TransitionFrame.Split).mix)
        }
        val leaving = TransitionPicture.split(pair, 0.925, canvas)
        assertEquals(0.5, leaving.mix.pair, tiny); assertEquals(0.5, leaving.mix.second, tiny)
        // Divider 0.35 on 160 × 90: outgoing left, incoming right, TransitionTests.swift:74-76.
        val middle = frame(pair, 0.5) as TransitionFrame.Split
        assertEquals(Rect(0.0, 0.0, 56.0, 90.0), middle.first)
        assertEquals(Rect(56.0, 0.0, 104.0, 90.0), middle.second)
        // Line width max(1, 0.002·min(W, H)) centred on the divider, colour 0.25 gray, TransitionRenderer.swift:257-260.
        assertEquals(Rect(55.5, 0.0, 1.0, 90.0), middle.line)
        assertEquals(TransitionColor(0.25, 0.25, 0.25), middle.lineColor)
        // Vertical: outgoing on top, TransitionTests.swift:77-79.
        val stacked = frame(pair.copy(axis = TransitionAxis.Vertical), 0.5) as TransitionFrame.Split
        assertEquals(58.5, stacked.first.minY, tiny); assertEquals(31.5, stacked.first.height, tiny); assertEquals(160.0, stacked.first.width, 0.0)
        assertEquals(Rect(0.0, 0.0, 160.0, 58.5), stacked.second)
    }

    @Test fun splitScreenFramesEachClipInItsHalf() {
        // Aspect fill of the half, centred: scale max(56/160, 90/90) = 1, so a 56 × 90 window, TransitionRenderer.swift:284-292.
        val pair = frame(settings(TransitionStyle.Split).copy(divider = 0.35, second = TransitionFraming(x = 0.0, y = 0.5, zoom = 2.0)), 0.5) as TransitionFrame.Split
        assertEquals(TransitionGeometry.Framed(Rect(52.0, 0.0, 56.0, 90.0), 1.0), pair.firstFramed(canvas))
        // Second half 104 × 90: scale max(0.65, 1)·2 = 2, window 52 × 45 pushed to the left edge.
        assertEquals(TransitionGeometry.Framed(Rect(0.0, 22.5, 52.0, 45.0), 2.0), pair.secondFramed(canvas))
    }

    @Test fun hitchcockThresholdSweepsWithTheEase() {
        // feather 0.08, threshold = eased·1.16 − 0.08, bias = threshold/feather + 0.5, TransitionRenderer.swift:211-214.
        val early = (frame(settings(TransitionStyle.Hitchcock), 0.25) as TransitionFrame.Hitchcock).key
        assertEquals(-12.5, early.scale, tiny)
        assertEquals((quarter * 1.16 - 0.08) / 0.08 + 0.5, early.bias, tiny)
        val middle = (frame(settings(TransitionStyle.Hitchcock), 0.5) as TransitionFrame.Hitchcock).key
        assertEquals(6.75, middle.bias, tiny)
        // "Hitchcock reveals shadows before highlights", TransitionTests.swift:97-98; HDR white keys as white.
        assertEquals(1.0, middle.secondShare(0.1, 0.1, 0.1), 0.0)
        assertEquals(0.0, middle.secondShare(3.0, 3.0, 3.0), 0.0)
        // "Hitchcock reverses to highlights first", TransitionTests.swift:101-103.
        val bright = (frame(settings(TransitionStyle.Hitchcock).copy(highlightsFirst = true), 0.5) as TransitionFrame.Hitchcock).key
        assertEquals(12.5, bright.scale, tiny); assertEquals(-5.75, bright.bias, tiny)
        assertEquals(1.0, bright.secondShare(3.0, 3.0, 3.0), 0.0); assertEquals(0.0, bright.secondShare(0.1, 0.1, 0.1), 0.0)
    }

    @Test fun whipPanSlidesAndMixesEasedButBlursLinearly() {
        // distance 0.65·W, radius 0.12·min(W, H)·sin(π·t), offsets −eased·d and (1 − eased)·d, TransitionRenderer.swift:224-226, 234.
        val early = TransitionPicture.frame(settings(TransitionStyle.Whip), 0.25, wide) as TransitionFrame.Whip
        assertEquals(-quarter * 130, early.firstOffset, tiny)
        assertEquals(threeQuarters * 130, early.secondOffset, tiny)
        assertEquals(quarter, early.mix, tiny)
        assertEquals(12 * sin(PI * 0.25), early.blurRadius, tiny)
        val middle = TransitionPicture.frame(settings(TransitionStyle.Whip), 0.5, wide) as TransitionFrame.Whip
        // 200 × 100 at the midpoint: offsets −65 and +65, radius 12, half mixed.
        assertEquals(-65.0, middle.firstOffset, tiny); assertEquals(65.0, middle.secondOffset, tiny); assertEquals(12.0, middle.blurRadius, tiny)
        assertEquals(0.5, middle.mix, tiny); assertEquals(0.0, middle.blurAngle, 0.0); assertEquals(1.0, middle.blurScale, 0.0)
        // Vertical blurs at 90°, TransitionRenderer.swift:231; reverse flips the travel, l. 224.
        val down = TransitionPicture.frame(settings(TransitionStyle.Whip).copy(axis = TransitionAxis.Vertical, reverse = true), 0.5, wide) as TransitionFrame.Whip
        assertTrue(down.vertical); assertEquals(90.0, down.blurAngle, 0.0)
        assertEquals(32.5, down.firstOffset, tiny); assertEquals(-32.5, down.secondOffset, tiny)
        // A 4K canvas is shrunk for the blur: min(1, max(960/3840, 2/radius)), TransitionRenderer.swift:268-272.
        assertEquals(0.25, (TransitionPicture.frame(settings(TransitionStyle.Whip), 0.5, Size(3840.0, 2160.0)) as TransitionFrame.Whip).blurScale, tiny)
    }

    @Test fun flashCutShowsWholeFrames() {
        // One second at 30 fps with the defaults flashes on frames 13, 22 and 27, Transitions.swift:72-82;
        // "Flash Cut uses exact single frames of the next scene", TransitionTests.swift:104-109.
        val flashes = settings(TransitionStyle.FlashCut)
        assertEquals(listOf(13, 22, 27), flashes.flashFrames(1.0))
        for (index in 1 until 30) {
            val expected = if (index in listOf(13, 22, 27)) TransitionFrame.Second else TransitionFrame.First
            assertSame(expected, TransitionPicture.frame(flashes, index / 30.0, canvas, duration = 1.0))
        }
        assertSame(TransitionFrame.First, frame(flashes, 0.25))
        assertSame(TransitionFrame.First, frame(flashes, 0.5))
        // The fitted length and the output frame rate decide the frames, TransitionRenderer.swift:236-237.
        assertEquals(listOf(6, 11, 13), flashes.flashFrames(0.5))
        assertSame(TransitionFrame.Second, TransitionPicture.frame(flashes, 6 / 15.0, canvas, duration = 0.5))
        assertSame(TransitionFrame.First, TransitionPicture.frame(flashes, 6 / 15.0, canvas, duration = 1.0))
    }

    @Test fun defocusBlursAndChangesOverLinearly() {
        // radius 0.05·min(W, H)·amount·sin(π·t), blend (t − 0.35)/0.3, TransitionRenderer.swift:242, 249.
        val early = TransitionPicture.frame(settings(TransitionStyle.Defocus), 0.25, wide) as TransitionFrame.Defocus
        assertEquals(3.75 * sin(PI * 0.25), early.blurRadius, tiny); assertEquals(0.0, early.mix, 0.0)
        val middle = TransitionPicture.frame(settings(TransitionStyle.Defocus), 0.5, wide) as TransitionFrame.Defocus
        assertEquals(3.75, middle.blurRadius, tiny); assertEquals(0.5, middle.mix, tiny); assertEquals(1.0, middle.blurScale, 0.0)
        val late = TransitionPicture.frame(settings(TransitionStyle.Defocus), 0.75, wide) as TransitionFrame.Defocus
        assertEquals(1.0, late.mix, 0.0)
        assertEquals(0.25, (TransitionPicture.frame(settings(TransitionStyle.Defocus), 0.425, wide) as TransitionFrame.Defocus).mix, tiny)
        assertEquals(5.0, (TransitionPicture.frame(settings(TransitionStyle.Defocus).copy(blurAmount = 1.0), 0.5, wide) as TransitionFrame.Defocus).blurRadius, tiny)
        // CIBokehBlur ring amount 0.12, ring size 0.1, softness 0.25, TransitionRenderer.swift:246.
        assertEquals(0.12, middle.ringAmount, 0.0); assertEquals(0.1, middle.ringSize, 0.0); assertEquals(0.25, middle.softness, 0.0)
    }

    @Test fun easingPerStyle() {
        // Eased: dissolve, still, wipe, Hitchcock, and a whip's slide and mix. Linear: fade, split, flash cut, defocus, and a whip's blur.
        val p = 0.3
        val eased = p * p * (3 - 2 * p)
        assertEquals(eased, (frame(settings(TransitionStyle.Dissolve), p) as TransitionFrame.Dissolve).second, tiny)
        assertEquals(eased, (frame(settings(TransitionStyle.Still), p) as TransitionFrame.Dissolve).second, tiny)
        assertEquals(80 + 171.2 * eased - 85.6, (frame(settings(TransitionStyle.Wipe), p) as TransitionFrame.Wipe).edge.center.x, tiny)
        assertEquals((eased * 1.16 - 0.08) / 0.08 + 0.5, (frame(settings(TransitionStyle.Hitchcock), p) as TransitionFrame.Hitchcock).key.bias, tiny)
        val whip = frame(settings(TransitionStyle.Whip), p) as TransitionFrame.Whip
        assertEquals(eased, whip.mix, tiny); assertEquals(-eased * 104, whip.firstOffset, tiny)
        assertEquals(90 * 0.12 * sin(PI * p), whip.blurRadius, tiny)
        assertEquals(2 * p, (frame(settings(TransitionStyle.FadeColor), p) as TransitionFrame.FadeColor).solid, tiny)
        assertEquals(0.03 / 0.15, (frame(settings(TransitionStyle.Split), 0.03) as TransitionFrame.Split).mix.pair, tiny)
        assertEquals(90 * 0.05 * 0.75 * sin(PI * p), (frame(settings(TransitionStyle.Defocus), p) as TransitionFrame.Defocus).blurRadius, tiny)
        assertEquals((0.45 - 0.35) / 0.3, (frame(settings(TransitionStyle.Defocus), 0.45) as TransitionFrame.Defocus).mix, tiny)
    }

    @Test fun aHoldKeepsTheMidpointFrame() {
        // "Midpoint hold adds to the moving overlap and retains the 50/50 mix": progress(at: 1) == 0.5 && progress(at: 2) == 0.5, TransitionTests.swift:279-281.
        val hold = TransitionSettings(duration = 1.0, hold = 2.0)
        val timing = TransitionTiming.of(hold)
        for (elapsed in listOf(0.5, 1.0, 2.0, 2.5)) assertEquals(TransitionFrame.Dissolve(0.5), TransitionPicture.frame(hold, timing, elapsed, canvas))
        assertEquals(TransitionFrame.Dissolve(quarter), TransitionPicture.frame(hold, timing, 0.25, canvas))
        assertEquals(TransitionFrame.Dissolve(threeQuarters), TransitionPicture.frame(hold, timing, 2.75, canvas))
        assertSame(TransitionFrame.Second, TransitionPicture.frame(hold, timing, 3.0, canvas))
    }

    @Test fun aFadesHoldIsTheColorAlone() {
        // The hold sits at progress 0.5 (Transitions.swift:161), where the fade is all colour (TransitionRenderer.swift:199);
        // "Color plateau and still hold remain visually constant", TransitionTests.swift:309.
        val color = TransitionColor(0.2, 0.7, 0.4)
        val fade = TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0, hold = 2.0, fadeColor = color)
        val timing = TransitionTiming.of(fade)
        assertEquals(TransitionFrame.FadeColor(color, 0.5, 0.5, 0.0), TransitionPicture.frame(fade, timing, 0.25, canvas))
        for (elapsed in listOf(0.5, 1.0, 2.0, 2.5)) assertEquals(TransitionFrame.FadeColor(color, 0.0, 1.0, 0.0), TransitionPicture.frame(fade, timing, elapsed, canvas))
        assertEquals(TransitionFrame.FadeColor(color, 0.0, 0.5, 0.5), TransitionPicture.frame(fade, timing, 2.75, canvas))
        // At an opening the hold is a half mix of the colour and the clip instead.
        val opening = TransitionTiming.of(fade, edge = -1)
        assertEquals(TransitionFrame.Dissolve(0.5), TransitionPicture.frame(fade, opening, 1.0, canvas, edge = -1))
    }

    @Test fun aStillHoldsThenCutsOrDissolves() {
        // Progress is 0 for the freeze, then runs over the exit dissolve, Transitions.swift:158.
        val freeze = TransitionSettings(style = TransitionStyle.Still, duration = 2.0, stillDissolve = 0.5)
        val timing = TransitionTiming.of(freeze)
        assertSame(TransitionFrame.First, TransitionPicture.frame(freeze, timing, 1.0, canvas))
        assertSame(TransitionFrame.First, TransitionPicture.frame(freeze, timing, 2.0, canvas))
        assertEquals(TransitionFrame.Dissolve(0.5), TransitionPicture.frame(freeze, timing, 2.25, canvas))
        assertSame(TransitionFrame.Second, TransitionPicture.frame(freeze, timing, 2.5, canvas))
        val cut = TransitionSettings(style = TransitionStyle.Still, duration = 2.0)
        assertSame(TransitionFrame.First, TransitionPicture.frame(cut, TransitionTiming.of(cut), 1.999, canvas))
        assertSame(TransitionFrame.Second, TransitionPicture.frame(cut, TransitionTiming.of(cut), 2.0, canvas))
    }

    @Test fun aStillHoldsTheLastFrameActuallyShown() {
        // Two 4 s clips, still 2 s + 0.5 s: the effect runs 4…6.5 (TransitionTests.swift:285-287) and the held
        // frame is the one at 4 − 1/30 ("Still freezes the actual final visible frame", TransitionTests.swift:311-319).
        // lastShown = max(start, (ceil(freezeStart·fps − 1e-6) − 1) / fps), TrackRenderer.swift:112.
        val timing = TransitionTiming.of(TransitionSettings(style = TransitionStyle.Still, duration = 2.0, stillDissolve = 0.5))
        val held = TransitionPicture.heldFrame(timing, origin = 4.0, clipStart = 0.0, clipDuration = 4.0)!!
        assertEquals(4.0, held.from, 0.0); assertEquals(6.5, held.until, 0.0)
        assertEquals(4 - 1.0 / 30, held.shown, tiny); assertEquals(4 - 1.0 / 30, held.offset, tiny)
        // Off the frame grid, the frame before the freeze: ceil(3.99·30) − 1 = 119.
        assertEquals(119.0 / 30, TransitionPicture.heldFrame(timing, 3.99, 0.0, 3.99)!!.shown, tiny)
        // At 60 fps it is the frame one sixtieth before.
        assertEquals(4 - 1.0 / 60, TransitionPicture.heldFrame(timing, 4.0, 0.0, 4.0, 60.0)!!.shown, tiny)
        // A clip that starts later, shorter than a frame: never before its start, never past its footage (TrackRenderer.swift:112-113).
        val short = TransitionPicture.heldFrame(timing, 10.02, 10.0, 0.02)!!
        assertEquals(10.0, short.shown, 0.0); assertEquals(0.0, short.offset, 0.0)
        assertEquals(1.0 - 1.0 / 60000, TransitionPicture.heldFrame(timing, 1.0, 0.0, 1.0, 1000000.0)!!.offset, tiny)
        // Other styles hold nothing.
        assertNull(TransitionPicture.heldFrame(TransitionTiming.of(TransitionSettings(duration = 1.0)), 3.0, 0.0, 4.0))
    }
}
