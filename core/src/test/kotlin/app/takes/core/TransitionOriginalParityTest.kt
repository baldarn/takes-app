package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The numbers the original app's transition tests and renderer pin
 * (Tests/TransitionTests.swift, YouRec/Transitions.swift,
 * YouRec/TransitionRenderer.swift), checked on the pure core. Where the
 * original checks a whole timeline of two 4 s clips or a rendered pixel, the
 * same case is worked out here from the timing and the geometry behind it.
 */
class TransitionOriginalParityTest {
    /** The original's pixel tests draw on a 160 × 90 canvas, origin at the bottom left. */
    private val canvas = Size(160.0, 90.0)

    private fun pixel(x: Int, y: Int) = Point(x + 0.5, y + 0.5)
    private fun inside(rect: Rect, point: Point) = point.x >= rect.minX && point.x < rect.maxX && point.y >= rect.minY && point.y < rect.maxY

    /** Two clips of [first] and [second] seconds joined by [settings]: where things land, as TimelineLayout places them. */
    private class Pair2(first: Double, second: Double, settings: TransitionSettings) {
        val timing = TransitionTiming.of(settings)
        val origin = first - timing.leftEnd
        val secondStart = origin + timing.rightStart
        val duration = secondStart + second
        val join = origin + timing.cut
        val range = (origin + timing.lower)..(origin + timing.upper)
        val video = origin..(origin + timing.videoDuration)
    }

    // TransitionTests.swift:14-16, 44-48, 31-33.

    @Test fun dissolveOverlapShiftsTheSecondClipAndTheJoin() {
        val pair = Pair2(4.0, 4.0, TransitionSettings(duration = 1.0))
        assertEquals(7.0, pair.duration, 0.0)
        assertEquals(3.0, pair.secondStart, 0.0)
        assertEquals(3.5, pair.join, 0.0)
    }

    @Test fun fadeThroughBlackKeepsTheTimelineLength() {
        val pair = Pair2(4.0, 4.0, TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0))
        assertEquals(8.0, pair.duration, 0.0)
        assertEquals(3.5..4.5, pair.range)
    }

    @Test fun openingAndClosingFadesKeepTheWholeProject() {
        val opening = TransitionTiming.of(TransitionSettings(duration = 1.0), edge = -1)
        // The first clip still starts at 0 and the fade covers 0…1.
        assertEquals(0.0, opening.rightStart, 0.0); assertEquals(0.0, opening.lower, 0.0); assertEquals(1.0, opening.upper, 0.0)
        val closing = TransitionTiming.of(TransitionSettings(duration = 1.0), edge = 1)
        // Its origin is the end of the last clip minus leftEnd: 8 − 1 = 7, so it covers 7…8.
        assertEquals(1.0, closing.leftEnd, 0.0); assertEquals(0.0, closing.lower, 0.0); assertEquals(1.0, closing.upper, 0.0)
    }

    @Test fun transitionsExtendBeyondTenSecondsUpToTheFootage() {
        // Two 4 s clips at a quarter speed are 16 s each; a 12 s dissolve leaves 20 s.
        assertTrue(TransitionSettings(duration = 12.0).valid)
        assertEquals(20.0, Pair2(16.0, 16.0, TransitionSettings(duration = 12.0)).duration, 0.0)
    }

    // TransitionTests.swift:279-289.

    @Test fun holdsColorPlateausAndFreezesGiveTheOriginalLengths() {
        assertEquals(5.0, Pair2(4.0, 4.0, TransitionSettings(duration = 1.0, hold = 2.0)).duration, 0.00001)
        val color = TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0, hold = 2.0, fadeColor = TransitionColor(0.2, 0.7, 0.4))
        assertEquals(10.0, Pair2(4.0, 4.0, color).duration, 0.0)
        val freeze = TransitionSettings(style = TransitionStyle.Still, duration = 2.0, stillDissolve = 0.5)
        val still = Pair2(4.0, 4.0, freeze)
        assertEquals(10.0, still.duration, 0.0)
        assertEquals(4.0..6.5, still.video)
        // The frozen frame needs no footage; the second clip gives only its dissolve.
        assertEquals(0.0, still.timing.leftCost, 0.0); assertEquals(0.5, still.timing.rightCost, 0.0)
        assertEquals(0.0..0.0, still.timing.audioOut); assertEquals(2.0..2.0, still.timing.audioIn)
        // Opening still: the first clip starts after one second of black, 8 + 1 = 9 s.
        val opening = TransitionTiming.of(TransitionSettings(style = TransitionStyle.Still, duration = 1.0), edge = -1)
        assertEquals(1.0, opening.rightStart, 0.0); assertEquals(0.0, opening.rightCost, 0.0); assertEquals(1.0..1.0, opening.audioIn)
        // Closing still: 8 − leftEnd + upper = 10.5 s.
        val closing = TransitionTiming.of(freeze, edge = 1)
        assertEquals(10.5, 8 - closing.leftEnd + closing.upper, 0.0)
    }

    // TransitionTests.swift:326-335: every style accepts a J-cut and an L-cut.

    @Test fun everyStyleAllowsSoundToLeadOrTrailThePicture() {
        for (style in TransitionStyle.entries) {
            val lead = Pair2(4.0, 4.0, TransitionSettings(style = style, duration = 1.0, audioOffset = -1.0, audioBlend = 0.5))
            assertEquals(lead.timing.audioIn, lead.timing.audioOut)
            assertEquals(-1.0..-0.5, lead.timing.audioIn)
            assertTrue(lead.origin + lead.timing.audioIn.endInclusive < lead.video.start)
            val trail = Pair2(4.0, 4.0, TransitionSettings(style = style, duration = 1.0, audioOffset = 1.5, audioBlend = 0.5))
            assertEquals(trail.timing.audioIn, trail.timing.audioOut)
            assertEquals(1.5..2.0, trail.timing.audioOut)
            assertTrue(trail.origin + trail.timing.audioOut.start > trail.video.endInclusive)
        }
    }

    // TransitionTests.swift:74-103: the rendered pixels, on the geometry behind them.

    @Test fun splitScreenPutsTheOutgoingClipLeftOrOnTop() {
        val pair = TransitionSettings(style = TransitionStyle.Split, duration = 1.0, divider = 0.35)
        assertEquals(TransitionGeometry.SplitMix(0.0, 1.0, 0.0), TransitionGeometry.splitMix(0.5))
        val side = TransitionGeometry.splitRects(pair, canvas)
        assertTrue(inside(side.first, pixel(20, 40)) && inside(side.second, pixel(100, 40)))
        // Bottom-up: y = 80 is near the top of the picture.
        val stacked = TransitionGeometry.splitRects(pair.copy(axis = TransitionAxis.Vertical), canvas)
        assertTrue(inside(stacked.first, pixel(50, 80)) && inside(stacked.second, pixel(50, 20)))
    }

    @Test fun hardWipeFollowsItsDiagonal() {
        val edge = TransitionGeometry.wipe(TransitionSettings(style = TransitionStyle.Wipe, duration = 1.0, hardEdge = true, wipeAngle = 45.0), 0.5, canvas)
        assertEquals(250 * sqrt(0.5), edge.span, 1e-9)
        assertEquals(0.5, edge.feather, 0.0)
        assertEquals(80.0, edge.center.x, 1e-9); assertEquals(45.0, edge.center.y, 1e-9)
        // Top left is already the second clip, bottom right still the first.
        assertEquals(1.0, edge.secondShare(pixel(20, 80)), 0.0)
        assertEquals(0.0, edge.secondShare(pixel(140, 10)), 0.0)
    }

    @Test fun hitchcockKeysTheFirstClipsBrightness() {
        val shadows = TransitionGeometry.hitchcock(TransitionSettings(style = TransitionStyle.Hitchcock, duration = 1.0), 0.5)
        assertEquals(-12.5, shadows.scale, 1e-9); assertEquals(6.75, shadows.bias, 1e-9)
        assertEquals(1.0, shadows.secondShare(0.1, 0.1, 0.1), 0.0)
        assertEquals(0.0, shadows.secondShare(3.0, 3.0, 3.0), 0.0)
        val late = TransitionGeometry.hitchcock(TransitionSettings(style = TransitionStyle.Hitchcock), 0.99)
        assertEquals(1.0, late.secondShare(3.0, 3.0, 3.0), 0.0)
        val highlights = TransitionGeometry.hitchcock(TransitionSettings(style = TransitionStyle.Hitchcock, highlightsFirst = true), 0.5)
        assertEquals(12.5, highlights.scale, 1e-9); assertEquals(-5.75, highlights.bias, 1e-9)
        assertEquals(1.0, highlights.secondShare(3.0, 3.0, 3.0), 0.0)
        assertEquals(0.0, highlights.secondShare(0.1, 0.1, 0.1), 0.0)
    }

    // TransitionRenderer.swift:191, 200-207, 223-251, 268-272: the formulas at a point that is not a round one.

    @Test fun rendererFormulasAtAQuarter() {
        assertEquals(0.15625, TransitionGeometry.eased(0.25), 0.0)
        assertEquals(0.84375, TransitionGeometry.eased(0.75), 0.0)
        val soft = TransitionGeometry.wipe(TransitionSettings(style = TransitionStyle.Wipe), 0.25, canvas)
        assertEquals(5.6, soft.feather, 1e-9)
        // (span + 2·feather)·eased − span/2 − feather, from the centre.
        assertEquals(80 + 171.2 * 0.15625 - 85.6, soft.center.x, 1e-9)
        val whip = TransitionGeometry.whip(TransitionSettings(style = TransitionStyle.Whip), 0.25, canvas)
        assertEquals(-16.25, whip.firstOffset, 1e-9); assertEquals(87.75, whip.secondOffset, 1e-9)
        assertEquals(10.8 * sin(PI / 4), whip.blurRadius, 1e-9); assertEquals(0.15625, whip.mix, 0.0)
        val down = TransitionGeometry.whip(TransitionSettings(style = TransitionStyle.Whip, axis = TransitionAxis.Vertical, reverse = true), 0.25, canvas)
        assertEquals(58.5 * 0.15625, down.firstOffset, 1e-9); assertEquals(-58.5 * 0.84375, down.secondOffset, 1e-9)
        val defocus = TransitionGeometry.defocus(TransitionSettings(style = TransitionStyle.Defocus), 0.25, canvas)
        assertEquals(90 * 0.05 * 0.75 * sin(PI / 4), defocus.blurRadius, 1e-9); assertEquals(0.0, defocus.mix, 0.0)
        assertEquals(0.5, TransitionGeometry.defocus(TransitionSettings(), 0.5, canvas).mix, 1e-9)
        assertEquals(1.0, TransitionGeometry.defocus(TransitionSettings(), 0.65, canvas).mix, 1e-9)
        // A 4K whip at its peak works at a quarter size; a small canvas never shrinks.
        val wide = Size(3840.0, 2160.0)
        assertEquals(0.25, TransitionGeometry.blurScale(TransitionGeometry.whip(TransitionSettings(), 0.5, wide).blurRadius, wide), 0.0)
        assertEquals(1.0, TransitionGeometry.blurScale(TransitionGeometry.whip(TransitionSettings(), 0.5, canvas).blurRadius, canvas), 0.0)
    }

    // Transitions.swift:39-67 and TransitionEditor.swift:313-329: what an untouched transition is.

    @Test fun defaultsAreTheOriginals() {
        val t = TransitionSettings()
        assertEquals(TransitionStyle.Dissolve, t.style)
        assertEquals(0.5, t.duration, 0.0); assertEquals(0.5, t.divider, 0.0)
        assertEquals(TransitionAxis.Horizontal, t.axis); assertEquals(TransitionAudio.Crossfade, t.audio)
        assertEquals(false, t.reverse)
        assertEquals(TransitionFraming(0.5, 0.5, 1.0), t.first); assertEquals(t.first, t.second)
        assertEquals(TransitionColor.BLACK, t.resolvedFadeColor)
        assertEquals(0.0, t.holdDuration, 0.0)
        assertEquals(t.copy(flashCount = 3, flashAcceleration = 2.0).flashFrames(1.0), t.flashFrames(1.0))
        assertEquals(TransitionGeometry.defocus(t.copy(blurAmount = 0.75), 0.5, canvas), TransitionGeometry.defocus(t, 0.5, canvas))
        assertEquals(
            listOf("Dissolve", "Fade through color", "Wipe", "Split screen", "Hitchcock", "Whip pan", "Flash Cut", "still", "Defocus"),
            TransitionStyle.entries.map { it.title },
        )
        assertEquals(listOf("Left / right", "Top / bottom"), TransitionAxis.entries.map { it.title })
        assertEquals(listOf("Crossfade", "First clip", "Second clip"), TransitionAudio.entries.map { it.title })
    }

    // Transitions.swift:112-155: fitting scales the picture and the sound together.

    @Test fun aFittedTransitionCostsItsShareOfEachClip() {
        // Two 3 s dissolves around a 4 s clip fit at two thirds: 2 s each (TransitionTests.swift:49-54).
        val fitted = TransitionTiming.of(TransitionSettings(duration = 3.0), factor = 2.0 / 3)
        assertEquals(2.0, fitted.leftCost, 1e-12); assertEquals(2.0, fitted.rightCost, 1e-12)
        assertEquals(0.0..fitted.videoDuration, fitted.audioIn)
        assertEquals(0.5, fitted.progress(1.0), 1e-12)
        // A fade through color between two clips takes half its length from each.
        val fade = TransitionTiming.of(TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0))
        assertEquals(0.5, fade.leftCost, 0.0); assertEquals(0.5, fade.rightCost, 0.0); assertEquals(0.0, fade.overlap, 0.0)
        assertEquals(0.0..0.5, fade.audioOut); assertEquals(0.5..1.0, fade.audioIn)
        // At an edge it is a plain fade of the whole length.
        val opening = TransitionTiming.of(TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0), edge = -1)
        assertEquals(0.0..1.0, opening.audioIn); assertEquals(1.0, opening.rightCost, 0.0)
    }
}
