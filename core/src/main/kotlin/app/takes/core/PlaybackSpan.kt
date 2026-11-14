package app.takes.core

import kotlinx.serialization.Serializable

/** A stretch of source seconds played at one rate. A clip's playback is a list of these, in order. */
data class EffectPlaybackSpan(val start: Double, val end: Double, val rate: Double) {
    /** Seconds it takes on the timeline. */
    val duration: Double get() = (end - start) / rate
}

/**
 * A hold is an inserted timeline segment referencing one original frame. It
 * never removes or replaces the footage following the playhead.
 */
@Serializable
data class FrozenFrameSegment(val sourceTime: Double, val duration: Double, val offset: Double = 0.0) {
    val valid: Boolean
        get() = sourceTime.isFinite() && sourceTime >= 0 && duration.isFinite() && duration > 0 && duration <= 10 &&
            offset.isFinite() && offset >= 0 && offset <= 10
}
