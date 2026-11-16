package app.takes.core

/**
 * What the preview and the export draw for an edit, either way it is: the
 * one call the app makes.
 *
 *  - An edit that is a sequence ([EditState.isSequence]) has the plan of two
 *    decks it always had ([TrackRenderPlan.of]), with its sound: [Sequence].
 *  - One that is not (pictures over one another, gaps) has the plan of
 *    picture layers made from its timeline ([LayerPlan.of]): [Layers], or
 *    [TooManyLayers] where more pictures play at once than the device
 *    decodes. Its sound is not in that plan: until the sound lanes, the app
 *    plays none for it, or mixes it itself from the timeline.
 */
sealed interface EditRender {
    /** The length of the movie in seconds; 0 when it cannot be drawn. */
    val duration: Double

    data class Sequence(val plan: RenderPlan) : EditRender {
        override val duration: Double get() = plan.duration
    }

    data class Layers(val plan: LayerPlan) : EditRender {
        override val duration: Double get() = plan.duration
    }

    /** More pictures play at once than [limit]: where, and which. The editor says so and offers nothing to play. */
    data class TooManyLayers(val refused: LayerPlan.Outcome.TooManyLayers) : EditRender {
        override val duration: Double get() = 0.0
    }

    companion object {
        /**
         * The plan of [state]. [limit] is how many pictures the device decodes
         * at once (at least 1), asked only of an edit that is not a sequence;
         * [cleaned] as in [RenderPlan.of], for a sequence.
         */
        fun of(state: EditState, limit: Int, cleaned: Set<Int>? = null): EditRender {
            if (state.isSequence) return Sequence(TrackRenderPlan.of(state, cleaned))
            return when (val outcome = LayerPlan.of(state.toTimeline(), limit)) {
                is LayerPlan.Outcome.Planned -> Layers(outcome.plan)
                is LayerPlan.Outcome.TooManyLayers -> TooManyLayers(outcome)
            }
        }
    }
}
