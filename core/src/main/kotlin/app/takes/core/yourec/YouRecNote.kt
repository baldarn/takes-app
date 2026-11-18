package app.takes.core.yourec

/**
 * Something a project from YouRec lost or changed on its way into Takes, to
 * be told to the person after the ZIP is opened (docs/IOS-INTEROP.md, 3.5).
 * [files] names the files concerned, when there are any.
 */
data class YouRecNote(val kind: Kind, val files: List<String> = emptyList()) {
    enum class Kind {
        /**
         * An edit took its frame from a take that was not its first picture, which the iPhone remembers
         * (YouRec/Project.swift:146-151) and Takes cannot: the frame is fixed at that take's size, as a Crop fixes it.
         */
        CanvasFixed,

        /** As [CanvasFixed], but the take's size could not be read: the frame follows the first picture. */
        CanvasDropped,

        /** Takes were being recorded at a chosen place; the next one is added at the end. */
        RecordingRunEnded,

        /** Recordings the ZIP names but does not hold. They stay in the project, ready to be found again. */
        MissingMedia,

        /** Files whose bytes did not match the ZIP's checksums. They were left out, as the iPhone leaves them out. */
        DamagedMedia,

        /** Sound in a CAF or AIFF file that is not plain PCM, which Android cannot play. It was left out. */
        AudioNotConverted,

        /** Videos whose format this phone cannot decode, such as ProRes. */
        UnplayableVideo,

        /**
         * Speech detail or studio restoration stages, whose models Takes downloads on request: until then that
         * sound plays unprocessed. [files] holds the ids of the models ([app.takes.core.AudioModel.id]).
         */
        NeuralStagesUnprocessed,

        /** Titles longer than Takes allows were shortened. */
        TitlesShortened,

        /** An edit saved before the track timeline had a sound effect over the whole movie, which was left out. */
        LegacyEffectsDropped,
    }
}
