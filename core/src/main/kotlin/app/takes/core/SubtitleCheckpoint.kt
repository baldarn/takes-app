package app.takes.core

/**
 * What a transcription had finished when it was stopped by the app leaving
 * the screen, kept so it can go on from there: the footage it listened to
 * ([stretches]), the language asked for (a two-letter code, null for
 * automatic), the pieces it cut the speech into ([plan], source seconds), how
 * many of them were done and the words they gave, by recording id. A port of
 * the original's (YouRec/SubtitleTranscriber.swift:4-10), saved after every
 * piece (:78).
 */
data class SubtitleCheckpoint(
    val stretches: List<SubtitleStretch>,
    val language: String?,
    val plan: List<SubtitleStretch>,
    val completed: Int,
    val words: Map<String, List<SubtitleWord>>,
    /** The language the model said the first piece was in; empty when none was told. */
    val written: String = "",
) {
    /**
     * How many pieces of [plan] a job that listens to [stretches] in
     * [language] can skip: [completed] when it is the same footage, language
     * and plan, else none, and the job starts again from the first piece
     * (YouRec/SubtitleTranscriber.swift:45-50).
     */
    fun reusable(stretches: List<SubtitleStretch>, language: String?, plan: List<SubtitleStretch>): Int =
        if (stretches == this.stretches && language == this.language && plan == this.plan && completed in 0..plan.size) completed else 0

    companion object {
        /** [reusable] of a checkpoint that may not be there. */
        fun reusable(checkpoint: SubtitleCheckpoint?, stretches: List<SubtitleStretch>, language: String?, plan: List<SubtitleStretch>): Int =
            checkpoint?.reusable(stretches, language, plan) ?: 0
    }
}
