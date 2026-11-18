package app.takes.core

import kotlin.math.floor

/**
 * Copy frame: a long press on the editor's preview copies the edited frame showing there as a PNG
 * (YouRec/EditorView.swift:1795-1835, 1946-1952, 2044-2048).
 */
object FrameCopy {
    /** How long the feedback stays once the copy is done, in seconds (EditorView.swift:1828). */
    const val FEEDBACK_SECONDS = 2.0

    /**
     * The picture time of second [time] of a movie [end] seconds long, on a clock of [frameDuration]: held inside
     * the movie, before its last instant (a 60000th of a second before the end), and taken down to the frame that
     * shows there (YouRec/EditorController.swift:14-20, `pictureTime`). Without a usable clock, the instant itself.
     */
    fun pictureTime(time: Double, end: Double, frameDuration: Double?): Double {
        val lastInstant = end - 1.0 / 60_000
        val instant = maxOf(0.0, minOf(if (time.isFinite()) time else 0.0, lastInstant))
        if (frameDuration == null || !frameDuration.isFinite() || frameDuration <= 0) return instant
        // A hair over, so that a time already on the grid is not taken one frame down by rounding.
        return floor(instant / frameDuration + 1e-9) * frameDuration
    }

    /**
     * Whether the preview offers Copy frame: outside the full-screen preview and an effect's range, once the editor
     * has loaded a movie with some length (EditorView.swift:597).
     */
    fun enabled(fullPreview: Boolean, effectRange: Boolean, loading: Boolean, duration: Double): Boolean =
        !fullPreview && !effectRange && !loading && duration > 0
}
