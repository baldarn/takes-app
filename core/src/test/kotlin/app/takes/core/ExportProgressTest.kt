package app.takes.core

import app.takes.core.ExportProgress.Label
import app.takes.core.ExportProgress.Phase
import app.takes.core.ExportProgress.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportProgressTest {
    private val exact = 1e-12

    // MediaRenderer.swift:81-83: the steps, in this order.
    @Test fun stepsFollowTheOriginalOrder() {
        assertEquals(
            listOf(
                Step.Opening, Step.AudioEffects, Step.Timeline, Step.AudioPeak, Step.Audio, Step.Picture, Step.AssembleVideo,
                Step.AssembleMovie, Step.MovieIndex, Step.SourceFiles, Step.Verifying, Step.Saving, Step.Complete,
            ),
            Step.entries,
        )
    }

    // MediaRenderer.swift:105-119: each step's share of the bar.
    @Test fun everyStepHasItsShareOfTheBar() {
        val shares = mapOf(
            Step.Opening to (0.0 to 0.0), Step.AudioEffects to (0.0 to 0.06), Step.Timeline to (0.0 to 0.06),
            Step.AudioPeak to (0.06 to 0.09), Step.Audio to (0.09 to 0.14), Step.Picture to (0.14 to 0.90),
            Step.AssembleVideo to (0.90 to 0.965), Step.AssembleMovie to (0.90 to 0.965), Step.MovieIndex to (0.965 to 0.97),
            Step.SourceFiles to (0.97 to 0.98), Step.Verifying to (0.98 to 0.99), Step.Saving to (0.99 to 0.99),
            Step.Complete to (1.0 to 1.0),
        )
        for (step in Step.entries) {
            val (lower, upper) = shares.getValue(step)
            assertEquals(step.name, lower, ExportProgress.of(step).fraction, exact)
            assertEquals(step.name, upper, ExportProgress.of(step, 1.0).fraction, exact)
        }
    }

    // MediaRenderer.swift:130-135: lower + value × (upper − lower).
    @Test fun progressInsideAStepIsLinear() {
        assertEquals(0.52, ExportProgress.of(Step.Picture, 0.5).fraction, exact)
        assertEquals(0.03, ExportProgress.of(Step.Timeline, 0.5).fraction, exact)
        assertEquals(0.075, ExportProgress.of(Step.AudioPeak, 0.5).fraction, exact)
        assertEquals(0.115, ExportProgress.of(Step.Audio, 0.5).fraction, exact)
        assertEquals(0.9325, ExportProgress.of(Step.AssembleMovie, 0.5).fraction, exact)
        assertEquals(0.985, ExportProgress.of(Step.Verifying, 0.5).fraction, exact)
    }

    // MediaRenderer.swift:133 and CoreTests.swift:910: values outside 0…1 and NaN cannot break the bar.
    @Test fun strayValuesAreBroughtIntoRange() {
        assertEquals(0.14, ExportProgress.of(Step.Picture, -3.0).fraction, exact)
        assertEquals(0.90, ExportProgress.of(Step.Picture, 7.0).fraction, exact)
        assertEquals(0.14, ExportProgress.of(Step.Picture, Double.NaN).fraction, exact)
        assertEquals(0.14, ExportProgress.of(Step.Picture, Double.POSITIVE_INFINITY).fraction, exact)
    }

    // CoreTests.swift:902: only the published movie reaches 100 %.
    @Test fun onlyCompleteIsOneHundredPercent() {
        for (step in Step.entries.filter { it != Step.Complete }) assertTrue(step.name, ExportProgress.of(step, 1.0).fraction < 1)
        assertEquals(1.0, ExportProgress.of(Step.Complete).fraction, exact)
        assertEquals(100, ExportProgress.of(Step.Complete).percent)
    }

    // MovieExportPanel.swift:120: whole percents, rounded down.
    @Test fun percentIsRoundedDown() {
        assertEquals(0, ExportProgress.of(Step.Opening).percent)
        assertEquals(52, ExportProgress.of(Step.Picture, 0.5).percent)
        assertEquals(89, ExportProgress.of(Step.Picture, 0.999).percent)
        assertEquals(99, ExportProgress.of(Step.Saving).percent)
    }

    // MediaRenderer.swift:85-101.
    @Test fun everyStepHasTheOriginalStatusText() {
        assertEquals(
            listOf(
                "Reading export settings", "Processing audio effects", "Building the video timeline", "Checking audio for clipping",
                "Encoding audio", "Rendering video", "Assembling video sections", "Combining video and audio", "Writing the movie index",
                "Checking source files", "Verifying the exported movie", "Saving the exported video", "Exported",
            ),
            Step.entries.map { ExportProgress.of(it).title },
        )
    }

    // MediaRenderer.swift:124-128: a resumed export names its first two steps differently.
    @Test fun aResumedExportSaysSo() {
        val resumed = ExportProgress.resumingAfter(ExportProgress.of(Step.Picture, 0.5))
        assertEquals(Step.Opening, resumed.step)
        assertEquals(0.52, resumed.fraction, exact)
        assertEquals(Label.Reopening, resumed.label)
        assertEquals("Reopening the saved export", resumed.title)

        val rebuilding = resumed.advanced(ExportProgress.of(Step.Timeline, 0.3))
        assertEquals(Step.Timeline, rebuilding.step)
        assertEquals(0.52, rebuilding.fraction, exact)
        assertEquals("Rebuilding the video timeline", rebuilding.title)

        // A fresh export keeps the plain texts, even with the timeline step full.
        assertEquals(Label.Opening, ExportProgress.resumingAfter(null).label)
        assertEquals(0.0, ExportProgress.resumingAfter(null).fraction, exact)
        assertEquals(Label.Timeline, ExportProgress.of(Step.Timeline, 1.0).label)
    }

    // MediaRenderer.swift:144-149 and CoreTests.swift:909-910.
    @Test fun lateCallbacksCannotRewindTheBarOrTheLabel() {
        val shown = ExportProgress.of(Step.Picture, 0.8)
        assertEquals(shown, shown.advanced(ExportProgress.of(Step.Timeline, 1.0)))
        // The same step going backwards keeps the higher value.
        assertEquals(shown, shown.advanced(ExportProgress.of(Step.Picture, 0.2)))
        // A later step takes over, and the bar keeps the larger of the two.
        val next = shown.advanced(ExportProgress.of(Step.AssembleMovie))
        assertEquals(Step.AssembleMovie, next.step)
        assertEquals(0.90, next.fraction, exact)
    }

    // CoreTests.swift:903-908: one bar that never decreases across a whole resumed export.
    @Test fun oneNondecreasingBarCoversAResume() {
        val floor = ExportProgress.of(Step.Picture, 12.0 / 31.12).fraction
        var displayed = ExportProgress.resumingAfter(ExportProgress.of(Step.Picture, 12.0 / 31.12))
        val events = listOf(
            ExportProgress.of(Step.Opening), ExportProgress.of(Step.AudioEffects, 0.5), ExportProgress.of(Step.Timeline, 1.0),
            ExportProgress.of(Step.Picture, 12.0 / 31.12), ExportProgress.picture(12.0, 0.5, 12.0, 31.12),
            ExportProgress.of(Step.Picture, 24.0 / 31.12), ExportProgress.of(Step.Picture, 1.0), ExportProgress.of(Step.AssembleMovie, 0.5),
            ExportProgress.of(Step.MovieIndex), ExportProgress.of(Step.SourceFiles, 0.5), ExportProgress.of(Step.Verifying),
            ExportProgress.of(Step.Saving), ExportProgress.of(Step.Complete),
        )
        for (event in events) {
            val next = displayed.advanced(event)
            assertTrue(next.fraction >= displayed.fraction && next.fraction >= floor)
            displayed = next
        }
        assertEquals(ExportProgress.of(Step.Complete), displayed)
    }

    // MovieExportPanel.swift:121-125: the colour of the bar follows three stretches.
    @Test fun stepsBelongToThreePhases() {
        assertEquals(listOf(Step.Opening, Step.AudioEffects, Step.Timeline, Step.AudioPeak), Step.entries.filter { it.phase == Phase.Preparing })
        assertEquals(listOf(Step.Audio, Step.Picture), Step.entries.filter { it.phase == Phase.Encoding })
        assertEquals(7, Step.entries.count { it.phase == Phase.Finishing })
    }

    // ExportCheckpoint.swift:133: a section's own progress placed in the whole movie.
    @Test fun aSectionReportsItsPlaceInTheMovie() {
        // Half of the second section of a 36 s movie: (12 + 0.5 × 12) / 36 = 0.5 of the picture step.
        assertEquals(0.52, ExportProgress.picture(12.0, 0.5, 12.0, 36.0).fraction, exact)
        // The short last section of a 30 s movie, finished.
        assertEquals(0.90, ExportProgress.picture(24.0, 1.0, 6.0, 30.0).fraction, exact)
    }

    // ExportCheckpoint.swift:186, 226 and 267: joining looks at every section first (5 %), then writes the pictures.
    @Test fun joiningIsFivePercentScanAndTheRestWriting() {
        assertEquals(0.05 / 3, ExportProgress.assemblyScan(0, 3), exact)
        assertEquals(0.05, ExportProgress.assemblyScan(2, 3), exact)
        assertEquals(0.05, ExportProgress.assemblyWrite(0, 720.0), exact)
        assertEquals(0.525, ExportProgress.assemblyWrite(360, 720.0), exact)
        assertEquals(1.0, ExportProgress.assemblyWrite(720, 720.0), exact)
        assertEquals(16, ExportProgress.ASSEMBLY_REPORT_EVERY)
    }

    // MediaRenderer.swift:787-789: checking the recordings counts files and the part of the current one.
    @Test fun checkingRecordingsCountsFiles() {
        assertEquals(0.97, ExportProgress.sourceFiles(0, 0.0, 4).fraction, exact)
        assertEquals(0.97 + 0.01 * 1.5 / 4, ExportProgress.sourceFiles(1, 0.5, 4).fraction, exact)
        assertEquals(0.98, ExportProgress.sourceFiles(3, 1.0, 4).fraction, exact)
    }

    // Project.swift:870-884: at most one update every 0.1 s, but 0 and 1 always pass.
    @Test fun updatesAreThrottledToTenASecond() {
        val throttle = ExportProgressThrottle()
        assertEquals(0.0, throttle.accept(0.0, 100.0)!!, 0.0)
        assertNull("Too soon", throttle.accept(0.2, 100.0625))
        assertEquals(0.3, throttle.accept(0.3, 100.125)!!, 0.0)
        assertNull("Unchanged value", throttle.accept(0.3, 100.5))
        assertEquals("Holding back the same value does not restart the wait", 0.4, throttle.accept(0.4, 100.5)!!, 0.0)
        assertNull("Too soon", throttle.accept(0.5, 100.5625))
        assertEquals("The end is never held back", 1.0, throttle.accept(1.0, 100.5626)!!, 0.0)
        assertNull("The same end twice", throttle.accept(1.0, 200.0))
        assertEquals("A restart is never held back", 0.0, throttle.accept(0.0, 200.01)!!, 0.0)
    }

    // Project.swift:877: reported values are bounded to 0…1 before they are compared.
    @Test fun throttleBoundsWhatItPasses() {
        val throttle = ExportProgressThrottle()
        assertEquals(1.0, throttle.accept(3.0, 0.0)!!, 0.0)
        assertNull(throttle.accept(1.5, 10.0))
        assertEquals(0.0, throttle.accept(-1.0, 10.0)!!, 0.0)
    }
}
