package app.takes.core

/*
 * `SubtitleEditing` as it was in release 0.8, before its functions became wrappers over the
 * track model (docs/TRACKS-PLAN.md, S2-B). Kept in the tests, unchanged, as
 * what the parity suite compares the wrappers with: nothing in the app or in
 * `core` calls it.
 */
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
internal object LegacySubtitleEditing {
    private fun speaks(clip: TimelineClip): Boolean = clip.audio?.muted != true

    /** Every caption of the edit in timeline seconds, in playback order. A muted clip shows none. */
    fun cues(state: EditState): List<SubtitleCue> {
        if (state.subtitles == null) return emptyList()
        val cues = mutableListOf<SubtitleCue>()
        state.clips.forEachIndexed { index, clip ->
            val words = clip.subtitleWords
            if (!words.isNullOrEmpty() && speaks(clip)) {
                cues += SubtitleTiming.cues(clip.id, words, LegacyTimelineEditing.clipStart(state.clips, index), clip.start, clip.end, clip.duration)
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
            "${clip.id}|${clip.sourceID}|${LegacyTimelineEditing.clipStart(clips, index)}|${clip.start}|${clip.end}|${clip.rate}|${!speaks(clip)}"
        }.joinToString("\n").toByteArray(),
    )

    /** False once the footage changed after transcription: the words per caption can then no longer be changed. Undo restores it. */
    fun groupingAvailable(state: EditState): Boolean =
        state.subtitles != null && state.subtitles.timingFingerprint == fingerprint(state.clips)

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
        val settings = (state.subtitles ?: SubtitleSettings()).copy(language = language)
        val clips = state.clips.map { clip ->
            val own = if (!speaks(clip)) emptyList() else words[clip.sourceID].orEmpty().mapNotNull { word ->
                val middle = (word.start + word.end) / 2
                if (middle < clip.start || middle >= clip.end) return@mapNotNull null
                word.copy(id = newID(), start = maxOf(clip.start, word.start), end = minOf(clip.end, word.end)).takeIf { it.valid }
            }
            clip.copy(subtitleWords = SubtitleTiming.group(own, settings.maxWords).ifEmpty { null })
        }
        return state.copy(clips = clips, subtitles = settings.copy(timingFingerprint = fingerprint(clips)))
    }

    /** Takes every caption out of the edit, with its corrections and timing. */
    fun remove(state: EditState): EditState =
        state.copy(clips = state.clips.map { if (it.subtitleWords == null) it else it.copy(subtitleWords = null) }, subtitles = null)

    /** Changes how the series looks. Null when the settings are not valid or nothing changes. */
    fun restyle(state: EditState, settings: SubtitleSettings): EditState? =
        if (state.subtitles == null || !settings.valid || settings == state.subtitles) null else state.copy(subtitles = settings)

    /** At most [count] words per caption. Null when the footage changed since transcription, or [count] is out of range. */
    fun regroup(state: EditState, count: Int): EditState? {
        val settings = state.subtitles ?: return null
        if (!groupingAvailable(state) || count !in SubtitleSettings.MAX_WORDS || count == settings.maxWords) return null
        return state.copy(
            clips = state.clips.map { clip -> clip.subtitleWords?.let { clip.copy(subtitleWords = SubtitleTiming.group(it, count)) } ?: clip },
            subtitles = settings.copy(maxWords = count),
        )
    }

    /** Retypes one caption. Words that stay keep their times; an empty text removes the caption. */
    fun correct(state: EditState, cue: SubtitleCue, text: String): EditState? {
        val index = state.clips.indexOfFirst { it.id == cue.audioID }
        if (index < 0) return null
        val clip = state.clips[index]
        val ids = cue.wordIDs.toSet()
        val (chosen, others) = clip.subtitleWords.orEmpty().partition { it.id in ids }
        if (chosen.isEmpty()) return null
        val next = (others + SubtitleTiming.corrected(chosen, text.take(SubtitleWord.MAXIMUM_TEXT)).filter { it.valid }).sortedBy { it.start }
        if (next == clip.subtitleWords) return null
        return state.copy(clips = state.clips.toMutableList().also { it[index] = clip.copy(subtitleWords = next.ifEmpty { null }) })
    }

    /** Removes one caption; the others stay as they are. */
    fun delete(state: EditState, cue: SubtitleCue): EditState? = correct(state, cue, "")
}

