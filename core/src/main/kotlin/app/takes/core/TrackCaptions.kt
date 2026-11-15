package app.takes.core

/**
 * Transcribing the mix, or a marked range of it, on the track timeline: what
 * the Subtitles tool works on, what it shows and asks, and putting what the
 * recognizer heard on the sound items (N10). The words then belong to the
 * sounds and follow them ([TrackSubtitles]).
 *
 * The recognizer hears the edited mix of the range as it plays, at 16 kHz,
 * with speed, level, fades, chains and censors in it
 * (YouRec/SubtitleTranscriber.swift:85-96), so a voice-over is captioned and a
 * sound turned far down may not be (docs/SUBTITLES-REVIEW.md, findings 2 and 8).
 * Transcribing a range replaces only the words heard in it (finding 3).
 */
object TrackCaptions {
    /**
     * What the tool works on: the range of [selection] when it has one, the
     * span of its items when it holds some, the whole timeline otherwise.
     * YouRec/EditorView.swift:1011-1019.
     */
    fun scope(timeline: TrackTimeline, selection: TimelineSelection? = null): EditInterval =
        selection?.let { TrackLinking.interval(timeline, it) } ?: EditInterval(EditTime.ZERO, timeline.duration)

    /** The captions the tool lists for [range]: those that show in it at all. YouRec/SubtitleEditor.swift:54, :351. */
    fun cues(timeline: TrackTimeline, range: EditInterval): List<SubtitleCue> =
        TrackSubtitles.cues(timeline).filter { it.start < range.end.seconds && it.end > range.start.seconds }

    /**
     * Whether transcribing [range] must first ask to replace captions: when
     * some show in it ("Replace subtitles in this range?").
     * YouRec/SubtitleEditor.swift:370-374.
     */
    fun replaces(timeline: TrackTimeline, range: EditInterval): Boolean = cues(timeline, range).isNotEmpty()

    /** Whether the footage changed since the captions were timed, so the tool offers to transcribe the whole project again. YouRec/SubtitleEditor.swift:251-252. */
    fun stale(timeline: TrackTimeline): Boolean = timeline.subtitles != null && !TrackSubtitles.groupingAvailable(timeline)

    /**
     * The sound the recognizer hears in [range]: every stretch of every sound
     * that is not muted, as the mixer plays it ([TrackAudioRegions.pieces]).
     * Empty when the range has no sound, which the tool reports instead of
     * transcribing ("This range has no audio.").
     */
    fun heard(timeline: TrackTimeline, range: EditInterval): List<AudioRenderPiece> =
        timeline.audio.flatMap { TrackAudioRegions.pieces(it, range) }

    /**
     * Installs what the recognizer heard in the mix of [range]: [words] in
     * seconds from the range's start, as a recording of the range gives
     * them. Each goes to the loudest sound playing at its middle, in that
     * sound's source seconds; the words every sound had in the range are
     * replaced and those outside it stay as they were
     * ([TrackSubtitles.installing], YouRec/Subtitles.swift:205-246).
     */
    fun installing(timeline: TrackTimeline, words: List<SubtitleWord>, range: EditInterval, language: String): TrackTimeline {
        val offset = range.start.seconds
        val placed = words.map { it.copy(start = it.start + offset, end = it.end + offset) }
        return TrackSubtitles.installing(timeline, placed, range.start.seconds, range.end.seconds, language)
    }
}
