package app.takes.core

import kotlin.math.floor

/**
 * Where an export is: the step it is working on and how full the one bar is.
 *
 * Each step owns a share of the bar; inside its share it reports its own
 * progress from 0 to 1 (media time, sections or bytes). Only a movie that has
 * been published reaches 100 %.
 */
class ExportProgress private constructor(val step: Step, val fraction: Double) {
    /** The steps of an export, in the order they happen. A later step never gives way to an earlier one. */
    enum class Step(val label: Label, val lower: Double, val upper: Double) {
        Opening(Label.Opening, 0.0, 0.0),
        // Audio effects and the timeline are prepared together and share one stretch of the bar.
        AudioEffects(Label.AudioEffects, 0.0, 0.06),
        Timeline(Label.Timeline, 0.0, 0.06),
        AudioPeak(Label.AudioPeak, 0.06, 0.09),
        Audio(Label.Audio, 0.09, 0.14),
        Picture(Label.Picture, 0.14, 0.90),
        /** Joining the picture sections of a movie without sound. */
        AssembleVideo(Label.AssembleVideo, 0.90, 0.965),
        /** Joining the picture sections with the sound. */
        AssembleMovie(Label.AssembleMovie, 0.90, 0.965),
        MovieIndex(Label.MovieIndex, 0.965, 0.97),
        SourceFiles(Label.SourceFiles, 0.97, 0.98),
        Verifying(Label.Verifying, 0.98, 0.99),
        Saving(Label.Saving, 0.99, 0.99),
        Complete(Label.Complete, 1.0, 1.0);

        val phase: Phase
            get() = when {
                this <= AudioPeak -> Phase.Preparing
                this <= Picture -> Phase.Encoding
                else -> Phase.Finishing
            }
    }

    /** The three stretches the bar is coloured by. */
    enum class Phase { Preparing, Encoding, Finishing }

    /** What the status line says. [text] is the original's English; the app maps a label to its own string. */
    enum class Label(val text: String) {
        Opening("Reading export settings"),
        AudioEffects("Processing audio effects"),
        Timeline("Building the video timeline"),
        AudioPeak("Checking audio for clipping"),
        Audio("Encoding audio"),
        Picture("Rendering video"),
        AssembleVideo("Assembling video sections"),
        AssembleMovie("Combining video and audio"),
        MovieIndex("Writing the movie index"),
        SourceFiles("Checking source files"),
        Verifying("Verifying the exported movie"),
        Saving("Saving the exported video"),
        Complete("Exported"),
        /** The first step of an export that continues a saved one. */
        Reopening("Reopening the saved export"),
        /** The timeline of a resumed export: the bar already stands beyond the step's own share. */
        Rebuilding("Rebuilding the video timeline"),
    }

    val label: Label
        get() = when {
            step == Step.Opening && fraction > 0 -> Label.Reopening
            step == Step.Timeline && fraction > step.upper -> Label.Rebuilding
            else -> step.label
        }

    val title: String get() = label.text

    /** The whole percents shown beside the bar, rounded down: 99.9 % is not yet 100 %. */
    val percent: Int get() = floor(fraction * 100).toInt()

    /**
     * What to show after [next] arrives. A late callback of an earlier step
     * changes nothing, and the bar never moves back: on a resume the label
     * describes the work being redone while the bar keeps what was saved.
     */
    fun advanced(next: ExportProgress): ExportProgress =
        if (next.step < step) this else ExportProgress(next.step, maxOf(fraction, next.fraction))

    override fun equals(other: Any?): Boolean = other is ExportProgress && other.step == step && other.fraction == fraction
    override fun hashCode(): Int = 31 * step.hashCode() + fraction.hashCode()
    override fun toString(): String = "ExportProgress($step, $fraction)"

    companion object {
        /** [step] with [value] of it done, 0 to 1. Anything else, a NaN included, is brought into that range. */
        fun of(step: Step, value: Double = 0.0): ExportProgress {
            val unit = if (value.isFinite()) minOf(1.0, maxOf(0.0, value)) else 0.0
            return ExportProgress(step, step.lower + unit * (step.upper - step.lower))
        }

        /** The start of an export that continues after [saved]: the first step again, with the bar where it was. */
        fun resumingAfter(saved: ExportProgress?): ExportProgress = ExportProgress(Step.Opening, saved?.fraction ?: 0.0)

        /** The picture step while one section is being encoded: [value] of a section of [length] seconds beginning at [offset]. */
        fun picture(offset: Double, value: Double, length: Double, duration: Double): ExportProgress =
            of(Step.Picture, (offset + value * length) / duration)

        /** Joining the sections, first pass: section [index] of [count] has been looked at. The pass is 5 % of the step. */
        fun assemblyScan(index: Int, count: Int): Double = ASSEMBLY_SCAN * (index + 1) / count

        /** Joining the sections, second pass: [written] of [frames] pictures are in the movie. */
        fun assemblyWrite(written: Int, frames: Double): Double = ASSEMBLY_SCAN + (1 - ASSEMBLY_SCAN) * (written / frames)

        /** Pictures written to the movie are reported once in this many. */
        const val ASSEMBLY_REPORT_EVERY = 16

        private const val ASSEMBLY_SCAN = 0.05

        /** Checking the recordings: [value] of file [index] of [count] has been read. */
        fun sourceFiles(index: Int, value: Double, count: Int): ExportProgress =
            of(Step.SourceFiles, (index + value) / count)
    }
}

/**
 * Lets a busy encoder report as often as it likes. A value is passed on when
 * it differs from the last one passed on and a tenth of a second has gone by;
 * the two ends, 0 and 1, are never held back.
 */
class ExportProgressThrottle(private val interval: Double = 0.1) {
    private var lastTime = Double.NEGATIVE_INFINITY
    private var lastValue = -1.0

    /** The value to publish, or null to stay quiet. [now] is any steadily rising clock, in seconds. */
    @Synchronized
    fun accept(value: Double, now: Double): Double? {
        val bounded = minOf(1.0, maxOf(0.0, value))
        val publish = bounded != lastValue && (now - lastTime >= interval || bounded == 0.0 || bounded == 1.0)
        if (!publish) return null
        lastTime = now
        lastValue = bounded
        return bounded
    }
}
