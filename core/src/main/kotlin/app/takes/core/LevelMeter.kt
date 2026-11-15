package app.takes.core

import java.util.Locale
import kotlin.math.abs

/**
 * The recorder's microphone meter as the original app runs it
 * (YouRec/CaptureController.swift:2664-2669 and 2859-2879): the level in
 * decibels below full scale, rising at once and falling slowly, a clipping
 * mark that stays for a moment after a peak, and "no signal" once nothing
 * has arrived for a while.
 *
 * [heard] is called with the peak of every buffer of sound, [reading] is
 * what the screen shows at a given moment. Times are seconds on any clock
 * that never goes back.
 */
data class LevelMeter(
    /** The level held, in dBFS; [SILENCE] before any sound. */
    val level: Double = SILENCE,
    /** When sound last arrived; never, at first. */
    val heardAt: Double = Double.NEGATIVE_INFINITY,
    /** When a peak last reached [CLIP_PEAK]; never, at first. */
    val clippedAt: Double = Double.NEGATIVE_INFINITY,
) {
    /** What the meter shows at one moment. */
    data class Reading(
        /** dBFS; [SILENCE] while no sound arrives, whatever was held. */
        val level: Double,
        val arriving: Boolean,
        val clipping: Boolean,
    ) {
        /** How many of the [BARS] bars are lit. */
        val bars: Int get() = bars(level)

        /** The share of the gain panel's level bar that is filled. */
        val fraction: Double get() = fraction(level)

        /** What the gain panel writes beside its bar; clipping comes before everything else (YouRec/RecorderView.swift:1365). */
        val label: Label get() = if (clipping) Label.Clipping else if (arriving) Label.Level else Label.NoSignal
    }

    /** What the gain panel writes beside its bar: "Clipping", the level as [format] gives it, or "No signal". */
    enum class Label { Clipping, Level, NoSignal }

    /**
     * The meter after a buffer whose highest absolute sample is [peak], with
     * full scale at 1. The level jumps up to a louder buffer and otherwise
     * falls by [release] decibels (CaptureController.swift:2875-2878).
     *
     * The original falls [RELEASE] for every buffer. Where sound is reported
     * at another pace, [releaseOver] gives the fall for the time since the
     * last report.
     */
    fun heard(peak: Double, now: Double, release: Double = RELEASE): LevelMeter {
        val loudness = abs(peak)
        return LevelMeter(
            level = maxOf(decibels(loudness), level - release),
            heardAt = now,
            clippedAt = if (loudness >= CLIP_PEAK) now else clippedAt,
        )
    }

    /**
     * The meter after the recording gain changed: the held level and the
     * clipping mark belonged to the old gain (CaptureController.swift:2836).
     */
    fun cleared(): LevelMeter = copy(level = SILENCE, clippedAt = Double.NEGATIVE_INFINITY)

    /** What to show at [now] (CaptureController.swift:2666-2669). */
    fun reading(now: Double): Reading {
        val arriving = now - heardAt < NO_SIGNAL_AFTER
        return Reading(if (arriving) level else SILENCE, arriving, now - clippedAt < CLIP_HOLD)
    }

    /**
     * Whether a running take should be paused because no sound arrives: more
     * than [PAUSE_AFTER] seconds without any, and as long since the take was
     * asked to start, unless the microphone began restarting less than
     * [RESTART_GRACE] seconds ago (CaptureController.swift:2676). [restarting]
     * is when that began, or null.
     */
    fun shouldPause(now: Double, requestedAt: Double, restarting: Double? = null): Boolean =
        now - heardAt > PAUSE_AFTER && now - requestedAt > PAUSE_AFTER && !(restarting != null && now - restarting < RESTART_GRACE)

    companion object {
        /** The level shown for no sound at all, in dBFS (CaptureController.swift:32,2668). */
        const val SILENCE = -160.0

        /** The quietest peak that is measured; anything below reads as [SILENCE] (CaptureController.swift:2877). */
        const val QUIETEST_PEAK = 0.00000001

        /** Decibels the level falls for every buffer that is not louder (CaptureController.swift:2877). */
        const val RELEASE = 1.5

        /**
         * The frames per buffer the original asks its microphone tap for
         * (YouRec/LiveMicrophone.swift:44) and the rate it records at. At
         * that size a buffer lasts 21.3 ms and the level falls 70 dB a
         * second. iOS is free to deliver another size, so the pace per
         * second on an iPhone was not established; only [RELEASE] per buffer
         * is certain.
         */
        const val BUFFER_FRAMES = 1024
        const val SAMPLE_RATE = 48_000

        /** A peak from here up counts as clipping (CaptureController.swift:2878). */
        const val CLIP_PEAK = 0.988

        /** Seconds the clipping mark stays after such a peak (CaptureController.swift:2669). */
        const val CLIP_HOLD = 0.35

        /** Seconds without sound after which the meter shows no signal (CaptureController.swift:2667). */
        const val NO_SIGNAL_AFTER = 0.8

        /** Seconds without sound after which a running take is paused (CaptureController.swift:2676). */
        const val PAUSE_AFTER = 1.0

        /** Seconds a restarting microphone is given before the take is paused for silence (CaptureController.swift:2676). */
        const val RESTART_GRACE = 3.0

        /** The bars of the small meter (YouRec/RecorderView.swift:927). */
        const val BARS = 7

        /** The level above which the first bar is lit, in dBFS (RecorderView.swift:928). */
        const val BAR_FLOOR = -54.0

        /** Decibels between one bar and the next (RecorderView.swift:928). */
        const val BAR_STEP = 8.0

        /** The level at which the gain panel's bar is empty, in dBFS; it is full at 0 (RecorderView.swift:1364). */
        const val PANEL_FLOOR = -60.0

        /** A peak, with full scale at 1, in dBFS (CaptureController.swift:2877). */
        fun decibels(peak: Double): Double = AudioLevels.decibels(maxOf(peak, QUIETEST_PEAK))

        /** The fall for [seconds] without a louder buffer, at the pace of [RELEASE] for every [BUFFER_FRAMES] frames. */
        fun releaseOver(seconds: Double): Double = RELEASE * maxOf(0.0, seconds) * SAMPLE_RATE / BUFFER_FRAMES

        /** The level above which bar [index], counted from 0, is lit: −54, −46 … −6 dBFS (RecorderView.swift:928). */
        fun barThreshold(index: Int): Double = BAR_FLOOR + index * BAR_STEP

        /** How many bars are lit at [level]; a bar is lit above its threshold, not at it. */
        fun bars(level: Double): Int = (0 until BARS).count { level > barThreshold(it) }

        /** Whether bar [index] is drawn red when lit: the last one only, above −6 dBFS (RecorderView.swift:929). */
        fun isRed(index: Int): Boolean = index > 5

        /** The filled share of the gain panel's bar, from [PANEL_FLOOR] to full scale (RecorderView.swift:1364). */
        fun fraction(level: Double): Double = minOf(1.0, maxOf(0.0, (level - PANEL_FLOOR) / -PANEL_FLOOR))

        /** A level as the gain panel writes it: whole decibels, "-18 dBFS" (RecorderView.swift:1365). */
        fun format(level: Double): String = String.format(Locale.ROOT, "%.0f dBFS", level)
    }
}
