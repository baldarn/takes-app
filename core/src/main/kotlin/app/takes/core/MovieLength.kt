package app.takes.core

/**
 * How long the movie of an edit is (docs/TRACKS-PLAN.md, N6 and D3).
 *
 * The original's movie is as long as its longest item, picture or sound
 * (YouRec/TrackTimeline.swift:503): sound that runs past the last picture
 * plays on over black. A sequence here used to end with its picture and leave
 * that sound out. A project saved before the movie took its sound's length
 * keeps the picture's length until the person has been shown the longer one
 * once and chosen to keep the sound ([withSound]) or to end with the picture
 * ([trimmed]); a project made since takes the longer length from the start.
 * An edit that is not a sequence has always been as long as its timeline.
 */
object MovieLength {
    /** Sound shorter than this past the picture is the picture's own rounding, not sound past it. */
    const val MARGIN = 0.001

    /** The length the movie of [state] has: with its sound when [adopted], else as long as its picture. */
    fun of(state: EditState, adopted: Boolean): Double = if (adopted) withSound(state) else state.duration

    /** As long as its longest item, picture or sound. */
    fun withSound(state: EditState): Double {
        if (!state.isSequence) return state.duration
        val sound = state.audio.orEmpty().maxOfOrNull { it.end } ?: 0.0
        return if (sound > state.duration + MARGIN) sound else state.duration
    }

    /** How many seconds of sound run past the last picture of [state]; 0 when none does. */
    fun pastPicture(state: EditState): Double = withSound(state) - state.duration

    /**
     * [state] with its sound ending with the picture: every sound item that
     * runs past the last picture cut there as a split cuts it (its fades with it),
     * and one that begins at or after the end left out. Null when nothing
     * runs past it.
     */
    fun trimmed(state: EditState): EditState? {
        if (!state.isSequence || pastPicture(state) <= 0) return null
        val end = state.duration
        val items = state.audio.orEmpty().mapNotNull { item ->
            when {
                item.end <= end + MARGIN -> item
                item.start >= end - MARGIN -> null
                else -> {
                    val length = end - item.start
                    item.copy(sourceEnd = item.sourceStart + length, audio = item.audio?.sliced(0.0, length, item.duration))
                }
            }
        }
        return state.copy(audio = items.ifEmpty { null })
    }
}
