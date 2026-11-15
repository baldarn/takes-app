package app.takes.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The numbers of the recorder's focus controls, as in the original app
 * (YouRec/RecorderView.swift:949-1041 and 1191-1214,
 * YouRec/CaptureController.swift:2422-2503, 2702-2709 and 3318-3329).
 *
 * The lens position runs from 0, the nearest the lens focuses, to 1, the
 * farthest, as the original's does. Android measures focus in dioptres, the
 * reciprocal of the distance in metres, from the nearest focus of the lens
 * down to 0 at infinity: the position is laid evenly over that scale, which
 * is not the original's own scale but has the same ends and the same order.
 */
object FocusControl {
    /** What the focus button shows. */
    enum class Mode { Fixed, Auto, Locked, Manual }

    /**
     * The state of the focus control (RecorderView.swift:972, CaptureController.swift:2441).
     * A lens with neither autofocus nor a position to set has fixed focus;
     * a locked lens whose position can be set is in manual focus.
     */
    fun mode(autofocus: Boolean, adjustable: Boolean, locked: Boolean): Mode = when {
        !autofocus && !adjustable -> Mode.Fixed
        locked -> if (adjustable) Mode.Manual else Mode.Locked
        else -> Mode.Auto
    }

    /** Whether a tap on the focus button changes between automatic and locked (RecorderView.swift:959). */
    fun canToggle(lockSupported: Boolean, autofocus: Boolean): Boolean = lockSupported && autofocus

    /** Points a touch on the focus button travels before it counts as a swipe (RecorderView.swift:995). */
    const val SWIPE_START = 7.0

    /** The first points of a swipe on MF move the lens finely, the rest faster (RecorderView.swift:998). */
    const val FINE_TRAVEL = 40.0
    const val FINE_POINTS = 320.0
    const val FAST_POINTS = 160.0

    /** How much of the lens range a vertical swipe of [travel] points covers (RecorderView.swift:998). */
    fun swipeAmount(travel: Double): Double {
        val distance = abs(travel)
        return minOf(distance, FINE_TRAVEL) / FINE_POINTS + maxOf(0.0, distance - FINE_TRAVEL) / FAST_POINTS
    }

    /**
     * The lens position after a swipe that began at [start] and has moved
     * [translation] points down: up focuses farther, down nearer
     * (RecorderView.swift:999).
     */
    fun swiped(start: Double, translation: Double): Double =
        bounded(start + if (translation < 0) swipeAmount(translation) else -swipeAmount(translation))

    /** One step of the screen reader's "farther" and "nearer" (RecorderView.swift:1031-1032). */
    const val STEP = 0.025

    fun bounded(position: Double): Double = if (position.isFinite()) minOf(1.0, maxOf(0.0, position)) else DEFAULT

    /** The position a locked lens takes when none was saved (CaptureController.swift:1765). */
    const val DEFAULT = 0.5

    /** The position as the bubble and the screen reader give it: "Focus 42%" (RecorderView.swift:979). */
    fun percent(position: Double): Int = (bounded(position) * 100).roundToInt()

    /** The focus distance in dioptres for [position] on a lens whose nearest focus is [nearest] dioptres. */
    fun dioptres(position: Double, nearest: Double): Double = maxOf(0.0, nearest) * (1 - bounded(position))

    /** The position of a lens focused at [dioptres], on a lens whose nearest focus is [nearest] dioptres. */
    fun position(dioptres: Double, nearest: Double): Double =
        if (nearest > 0 && dioptres.isFinite()) bounded(1 - dioptres / nearest) else DEFAULT

    /** The side of the yellow box drawn where the picture was tapped (CaptureController.swift:3321). */
    const val BOX_SIDE = 48.0

    /** The box stays at least this long, so a lens that is already sharp still shows it (CaptureController.swift:2705). */
    const val BOX_MINIMUM_SECONDS = 0.85

    /** The box goes after this long even when the lens never settles (CaptureController.swift:2705). */
    const val BOX_MAXIMUM_SECONDS = 4.0

    /** Seconds the box takes to fade (CaptureController.swift:3329). */
    const val BOX_FADE_SECONDS = 0.22

    /** Whether the box drawn [age] seconds ago goes now (CaptureController.swift:2705). */
    fun boxEnds(age: Double, settled: Boolean): Boolean = (age >= BOX_MINIMUM_SECONDS && settled) || age >= BOX_MAXIMUM_SECONDS

    /** Seconds a focus hint stays (CaptureController.swift:2427). */
    const val HINT_SECONDS = 1.8
}
