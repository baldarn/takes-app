package app.takes.core

/** A stretch of one recording's sound that the edit plays: what a transcription has to listen to. Source seconds. */
data class SubtitleStretch(val sourceID: String, val start: Double, val end: Double)

/**
 * Subtitles in the edit. The words belong to the clips, in source seconds, as
 * titles and bookmarks do: a split, trim, move, deletion or speed change
 * carries the text with the speech. [EditState.subtitles] says how the whole
 * series looks; without it the project has no subtitles.
 *
 * The original keeps the words on the items of its sound tracks. This port
 * has no separate track for a clip's own sound, so the clip owns them. Sound
 * on the audio tracks (music, a voice-over) is not transcribed.
 */
object SubtitleEditing {
    private fun speaks(clip: TimelineClip): Boolean = clip.audio?.muted != true

    /** Every caption of the edit in timeline seconds, in playback order. A muted clip shows none. */
    fun cues(state: EditState): List<SubtitleCue> {
        // Off a sequence: from the words on the timeline's sounds; a caption names the picture whose sound it is on ([TrackSequenceView.cues]).
        if (!state.isSequence) return TrackSequenceView.cues(state)
        if (state.subtitles == null) return emptyList()
        val cues = mutableListOf<SubtitleCue>()
        state.clips.forEachIndexed { index, clip ->
            val words = clip.subtitleWords
            if (!words.isNullOrEmpty() && speaks(clip)) {
                cues += SubtitleTiming.cues(clip.id, words, TimelineEditing.clipStart(state.clips, index), clip.start, clip.end, clip.duration)
            }
        }
        return cues.sortedWith(compareBy<SubtitleCue> { it.start }.thenBy { it.id })
    }

    /** The captions showing at [time]: one, or two while clips dissolve into each other. */
    fun active(cues: List<SubtitleCue>, time: Double): List<SubtitleCue> =
        cues.filter { it.start <= time && time < it.end }.take(2)

    /** The whole transcript as plain text, in playback order. */
    fun transcript(state: EditState): String = SubtitleTiming.joined(cues(state).map { it.text })

    /**
     * The structure the captions were made for: which footage plays where, at
     * what speed, and whether it is heard. Color, framing, level and caption
     * edits do not change it.
     */
    fun fingerprint(clips: List<TimelineClip>): String = sha256(
        clips.mapIndexed { index, clip ->
            "${clip.id}|${clip.sourceID}|${TimelineEditing.clipStart(clips, index)}|${clip.start}|${clip.end}|${clip.rate}|${!speaks(clip)}"
        }.joinToString("\n").toByteArray(),
    )

    /** False once the footage changed after transcription: the words per caption can then no longer be changed. Undo restores it. */
    fun groupingAvailable(state: EditState): Boolean =
        if (!state.isSequence) TrackSubtitles.groupingAvailable(state.toTimeline()) else state.subtitles != null && state.subtitles.timingFingerprint == fingerprint(state.clips)

    /**
     * What has to be transcribed: for each recording, the parts that clips
     * with sound play, merged where they touch or overlap, so footage used
     * twice or split in two is listened to once and in one piece.
     */
    fun stretches(clips: List<TimelineClip>): List<SubtitleStretch> {
        val order = mutableListOf<String>()
        val ranges = mutableMapOf<String, MutableList<SourceInterval>>()
        for (clip in clips) {
            if (!speaks(clip)) continue
            if (clip.sourceID !in ranges) order += clip.sourceID
            ranges.getOrPut(clip.sourceID) { mutableListOf() } += SourceInterval(clip.start, clip.end)
        }
        return order.flatMap { source ->
            val merged = mutableListOf<SourceInterval>()
            for (range in ranges.getValue(source).sortedBy { it.start }) {
                val last = merged.lastOrNull()
                if (last != null && range.start <= last.end + 0.001) merged[merged.lastIndex] = SourceInterval(last.start, maxOf(last.end, range.end))
                else merged += range
            }
            merged.map { SubtitleStretch(source, it.start, it.end) }
        }
    }

    /**
     * Replaces every caption with a new transcription. [words] are the words
     * heard in each recording, by source id and in source seconds. Each clip
     * takes its own copy of those inside it, cut at its edges, so correcting
     * one clip never changes another that shows the same footage.
     */
    fun install(state: EditState, words: Map<String, List<SubtitleWord>>, language: String): EditState {
        // Off a sequence: the same words to the same sounds, and what they were timed for is the timeline's ([TrackSubtitles.fingerprint]).
        if (!state.isSequence) return TrackEditState.editing(state) { timeline ->
            val installed = TrackSubtitles.installingSourceWords(timeline, words, language, timeline.audio.filter { it.followsVideo != null }.mapTo(HashSet()) { it.id })
            installed.copy(subtitles = (installed.subtitles ?: SubtitleSettings()).copy(language = language, timingFingerprint = TrackSubtitles.fingerprint(installed)))
        } ?: state
        // The words go to the sounds of the timeline ([TrackSubtitles.installingSourceWords]). Kept until S8
        // (transcription-hears-sound-items): only to the sound that follows a clip's picture; a sound of its own gets none.
        val result = TrackWrapping.edit(state, sound = false) { timeline ->
            TrackSubtitles.installingSourceWords(timeline, words, language, timeline.audio.filter { it.followsVideo != null }.mapTo(HashSet()) { it.id })
        } ?: state
        // Kept until S8 (captions-fingerprint): what the captions were timed for is said by today's formula.
        return result.copy(subtitles = (result.subtitles ?: SubtitleSettings()).copy(language = language, timingFingerprint = fingerprint(result.clips)))
    }

    /** Takes every caption out of the edit, with its corrections and timing. */
    fun remove(state: EditState): EditState = TrackWrapping.edit(state, sound = false) { TrackSubtitles.removing(it) } ?: state

    /** Changes how the series looks. Null when the settings are not valid or nothing changes. */
    fun restyle(state: EditState, settings: SubtitleSettings): EditState? {
        if (state.subtitles == null || !settings.valid || settings == state.subtitles) return null
        // Off a sequence: the look alone; what the captions were timed for stays the timeline's.
        if (!state.isSequence) return TrackEditState.editing(state) { TrackSubtitles.restyling(it, settings.copy(timingFingerprint = it.subtitles?.timingFingerprint ?: "")) }
        val result = TrackWrapping.edit(state, sound = false) { timeline ->
            TrackSubtitles.restyling(timeline, settings.copy(timingFingerprint = timeline.subtitles?.timingFingerprint ?: ""))
        } ?: state
        // Kept until S8 (captions-fingerprint): the look says what it was timed for as it is given.
        return result.copy(subtitles = result.subtitles?.copy(timingFingerprint = settings.timingFingerprint))
    }

    /**
     * At most [count] words per caption ([TrackSubtitles.regrouping]). Null
     * when the footage changed since transcription, or [count] is out of
     * range, or is the count the captions have.
     */
    fun regroup(state: EditState, count: Int): EditState? {
        val settings = state.subtitles ?: return null
        // Kept until S8 (captions-fingerprint): whether the footage changed is asked of the edit, by today's formula.
        if (!groupingAvailable(state) || count !in SubtitleSettings.MAX_WORDS) return null
        // The track model groups the words again at the count they have (`regroup-at-the-count-it-has`), under new phrase
        // ids: an edit that shows nothing and would still be a step of Undo. It stays no edit.
        if (count == settings.maxWords) return null
        return TrackWrapping.edit(state, sound = false) { TrackSubtitles.regrouping(it, count) }
    }

    /** Retypes one caption. Words that stay keep their times; an empty text removes the caption. */
    fun correct(state: EditState, cue: SubtitleCue, text: String): EditState? {
        val clip = state.clips.firstOrNull { it.id == cue.audioID } ?: return null
        // A caption names its clip; its words are on the clip's sound ([TrackSubtitles.correcting]).
        return TrackWrapping.edit(state, sound = false) { timeline ->
            val sound = TrackSequenceView.sounds(timeline)[clip.id] ?: return@edit null
            TrackSubtitles.correcting(timeline, cue.copy(audioID = sound.id), text)
        }
    }

    /** Removes one caption; the others stay as they are. */
    fun delete(state: EditState, cue: SubtitleCue): EditState? = correct(state, cue, "")
}

/**
 * Where the caption series may sit. Captions are laid out once on a canvas
 * with a shorter side of [SHORT_SIDE], at [REFERENCE_SIZE]; resizing scales
 * that layout, so line breaks never change. [largest] is the biggest box any
 * moment of the movie needs, two stacked captions included: the series is
 * kept small enough, and far enough from the edges, for that box to show whole.
 */
class SubtitlePlacement(val canvas: Size, val largest: Size) {
    val maximumSize: Double =
        maxOf(0.0001, minOf(0.30, REFERENCE_SIZE * minOf(canvas.width * 0.94 / largest.width, canvas.height * 0.94 / largest.height)))
    val minimumSize: Double get() = minOf(0.012, maximumSize)

    fun constrained(value: SubtitleSettings): SubtitleSettings {
        val size = value.size.coerceIn(minimumSize, maximumSize)
        val scale = size / REFERENCE_SIZE
        val halfWidth = largest.width * scale / canvas.width / 2 + 0.025
        val halfHeight = largest.height * scale / canvas.height / 2 + 0.025
        return value.copy(size = size, x = minOf(1 - halfWidth, maxOf(halfWidth, value.centerX)), y = minOf(1 - halfHeight, maxOf(halfHeight, value.y)))
    }

    /**
     * The box of the largest caption at the series' place, as shares of the
     * frame from its top left: where a drag or a pinch takes hold of the
     * captions, and the outline shown while placing them
     * (YouRec/SubtitleRenderer.swift:64-70).
     */
    fun envelope(value: SubtitleSettings): Rect {
        val settings = constrained(value)
        val scale = settings.size / REFERENCE_SIZE
        val width = largest.width * scale / canvas.width
        val height = largest.height * scale / canvas.height
        return Rect(settings.centerX - width / 2, settings.y - height / 2, width, height)
    }

    /** The series moved by [dx] and [dy], shares of the frame, within the limits (YouRec/SubtitleEditor.swift:190). */
    fun moved(value: SubtitleSettings, dx: Double, dy: Double): SubtitleSettings =
        constrained(value.copy(x = value.centerX + dx, y = value.y + dy))

    /**
     * The series resized by [factor] around the fingers, which went from
     * ([fromX], [fromY]) to ([toX], [toY]), shares of the frame: the point
     * under them stays under them (YouRec/SubtitleEditor.swift:191-195).
     */
    fun pinched(value: SubtitleSettings, factor: Double, fromX: Double, fromY: Double, toX: Double, toY: Double): SubtitleSettings {
        if (!factor.isFinite() || factor <= 0) return constrained(value)
        val target = (value.size * factor).coerceIn(minimumSize, maximumSize)
        val ratio = target / value.size
        return constrained(value.copy(x = toX + (value.centerX - fromX) * ratio, y = toY + (value.y - fromY) * ratio, size = target))
    }

    /**
     * The top edge of each caption showing together, as a share of the frame
     * height: the stack is centered on the series' position. [heights] are
     * canvas units at the reference size.
     */
    fun tops(heights: List<Double>, value: SubtitleSettings): List<Double> {
        val settings = constrained(value)
        val scale = settings.size / REFERENCE_SIZE
        val total = heights.sum() + if (heights.size > 1) GAP * (heights.size - 1) else 0.0
        var top = settings.y - total * scale / canvas.height / 2
        return heights.map { height -> top.also { top += (height + GAP) * scale / canvas.height } }
    }

    companion object {
        const val REFERENCE_SIZE = 0.052
        const val SHORT_SIDE = 1080.0

        /** Canvas units between two captions showing together. */
        const val GAP = 12.0

        /** A caption never takes more than this share of the canvas width. */
        const val WIDTH_SHARE = 0.84

        /** The layout canvas for a picture of [width] by [height] pixels. */
        fun canvas(width: Double, height: Double): Size {
            val factor = SHORT_SIDE / maxOf(1.0, minOf(width, height))
            return Size(maxOf(1.0, width * factor), maxOf(1.0, height * factor))
        }

        /**
         * The biggest box needed at any moment: the widest caption, and the
         * tallest stack of captions showing together. [measure] gives a
         * caption's box for its text.
         */
        fun largest(cues: List<SubtitleCue>, measure: (String) -> Size): Size {
            var width = 1.0
            var height = 1.0
            val events = cues.indices.flatMap { listOf(Triple(cues[it].start, true, it), Triple(cues[it].end, false, it)) }
                .sortedWith(compareBy<Triple<Double, Boolean, Int>> { it.first }.thenBy { it.second })
            val active = sortedSetOf<Int>()
            for ((_, starting, index) in events) {
                if (starting) active += index else active -= index
                val boxes = active.take(2).map { measure(cues[it].text) }
                width = maxOf(width, boxes.maxOfOrNull { it.width } ?: 0.0)
                height = maxOf(height, boxes.sumOf { it.height } + if (boxes.size > 1) GAP else 0.0)
            }
            return Size(width, height)
        }
    }
}
