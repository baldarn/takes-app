package app.takes.core

/**
 * Captions on the track timeline. The words belong to the sound items, in
 * source seconds, each item holding its own by value; [TrackTimeline.subtitles]
 * says how the whole series looks, and without it the timeline has no
 * captions. Ports of the original's (YouRec/Subtitles.swift:154-247).
 *
 * What the editing operations do to the words follows from where they are
 * kept, and none of them touches a word:
 *
 *  - a split leaves every word on both pieces; each piece shows the words
 *    whose sound it plays, and correcting one never changes the other;
 *  - moving a sound, or unlinking it, moves its captions with it;
 *  - a change of speed or a trim changes the sound's map, and a caption is
 *    where its source seconds now play, or nowhere when they no longer do
 *    (the words stay, and show again when the footage is revealed);
 *  - deleting a sound deletes its words; muting it hides them;
 *  - every edit of the structure (what plays where, how fast, heard or not)
 *    changes the fingerprint, so regrouping is refused until the edit is
 *    undone; color, framing, level and caption edits leave it alone.
 */
object TrackSubtitles {
    /**
     * Every caption, in timeline seconds, in the order they begin. A sound
     * that is muted shows none, and neither does a stretch whose source sound
     * is silent or held. One caption per phrase per run of a sound's map: a
     * phrase is cut where the run ends, and broken where its words are more
     * than 0.4 s apart. YouRec/Subtitles.swift:155-180.
     */
    fun cues(timeline: TrackTimeline): List<SubtitleCue> {
        if (timeline.subtitles == null) return emptyList()
        val cues = ArrayList<SubtitleCue>()
        for (item in timeline.audio) {
            val words = item.subtitleWords
            if (item.muted || words.isNullOrEmpty()) continue
            var offset = 0.0
            item.mapping.runs.forEachIndexed { index, run ->
                if (!run.silent && !run.isHold) {
                    cues += SubtitleTiming.cues(item.id, words, item.start.seconds + offset, run.sourceStart, run.sourceEnd, run.duration.seconds, index)
                }
                offset += run.duration.seconds
            }
        }
        return cues.sortedWith(compareBy<SubtitleCue> { it.start }.thenBy { it.id })
    }

    /** The whole transcript as plain text, in playing order. */
    fun transcript(timeline: TrackTimeline): String = SubtitleTiming.joined(cues(timeline).map { it.text })

    /** The structure captions are made for ([TrackConversion.timingFingerprint]). YouRec/Subtitles.swift:183-189. */
    fun fingerprint(timeline: TrackTimeline): String = TrackConversion.timingFingerprint(timeline)

    /** False once the structure changed after transcription: the words per caption can then not be changed. Undo restores it. YouRec/Subtitles.swift:190. */
    fun groupingAvailable(timeline: TrackTimeline): Boolean = timeline.subtitles?.timingFingerprint == fingerprint(timeline)

    /** The timeline without captions: the settings and every word. YouRec/Subtitles.swift:191. */
    fun removing(timeline: TrackTimeline): TrackTimeline =
        timeline.copy(subtitles = null, audio = timeline.audio.map { if (it.subtitleWords == null) it else it.copy(subtitleWords = null) })

    /** Changes how the series looks. Null when there are no captions, the settings are not valid, or nothing changes. */
    fun restyling(timeline: TrackTimeline, settings: SubtitleSettings): TrackTimeline? =
        if (timeline.subtitles == null || !settings.valid || settings == timeline.subtitles) null else timeline.copy(subtitles = settings)

    /**
     * At most [count] words per caption, on every sound. Null when the
     * structure changed since transcription or [count] is not 1…16.
     * YouRec/Subtitles.swift:192-196. The original also gives a sound that
     * had no words an empty list of them; here none stays none.
     */
    fun regrouping(timeline: TrackTimeline, count: Int): TrackTimeline? {
        val settings = timeline.subtitles ?: return null
        if (!groupingAvailable(timeline) || count !in SubtitleSettings.MAX_WORDS) return null
        return timeline.copy(
            subtitles = settings.copy(maxWords = count),
            audio = timeline.audio.map { item -> item.subtitleWords?.let { item.copy(subtitleWords = SubtitleTiming.group(it, count)) } ?: item },
        )
    }

    /**
     * Retypes one caption on the sound it belongs to. Words that stay keep
     * their times; an empty text removes the caption. Only that sound's words
     * change: another piece of the same recording keeps its own. Null when
     * the caption's sound or words are no longer there, or nothing changes.
     * YouRec/Subtitles.swift:197-201.
     */
    fun correcting(timeline: TrackTimeline, cue: SubtitleCue, text: String): TrackTimeline? {
        val index = timeline.audio.indexOfFirst { it.id == cue.audioID }
        if (index < 0) return null
        val item = timeline.audio[index]
        val ids = cue.wordIDs.toSet()
        val (chosen, others) = item.subtitleWords.orEmpty().partition { it.id in ids }
        if (chosen.isEmpty()) return null
        val next = (others + SubtitleTiming.corrected(chosen, text.take(SubtitleWord.MAXIMUM_TEXT)).filter { it.valid }).sortedBy { it.start }
        if (next == item.subtitleWords) return null
        return timeline.copy(audio = timeline.audio.toMutableList().also { it[index] = item.copy(subtitleWords = next.ifEmpty { null }) })
    }

    /** Removes one caption; the others stay as they are. */
    fun deleting(timeline: TrackTimeline, cue: SubtitleCue): TrackTimeline? = correcting(timeline, cue, "")

    /**
     * Installs a transcription of the mix. [words] are in timeline seconds,
     * heard between [from] and [to]; each goes, in source seconds, to one
     * sound: of those that are not muted and play at the word's middle, the
     * loudest (of equally loud ones, the lowest id) whose run there is
     * neither silent nor held. A word is never put on two sounds, and is cut
     * at the edges of the run it lands in.
     *
     * The words every sound had inside [from]…[to] are replaced; those
     * outside stay, grouped as they were, so transcribing a stretch again
     * does not regroup captions corrected elsewhere. A word that is no longer
     * played by its sound is dropped. The fingerprint is taken anew when
     * there were no captions, when it still matched, or when the new range
     * covers every caption there was; otherwise it stays stale.
     *
     * YouRec/Subtitles.swift:205-246.
     */
    fun installing(timeline: TrackTimeline, words: List<SubtitleWord>, from: Double, to: Double, language: String): TrackTimeline {
        val previous = cues(timeline)
        var settings = (timeline.subtitles ?: SubtitleSettings()).copy(language = language)
        val coversExisting = previous.all { it.start >= from - 0.001 && it.end <= to + 0.001 }
        if (timeline.subtitles == null || groupingAvailable(timeline) || coversExisting) settings = settings.copy(timingFingerprint = fingerprint(timeline))
        val kept = timeline.audio.map { item ->
            item.subtitleWords.orEmpty().filter { word ->
                val local = item.mapping.local((word.start + word.end) / 2) ?: return@filter false
                (item.start.seconds + local.seconds).let { it < from || it > to }
            }.toMutableList()
        }
        val newIDs = words.mapTo(HashSet()) { it.id }
        for (word in words) {
            val midpoint = EditTime.of((word.start + word.end) / 2)
            val candidates = timeline.audio.indices.filter { !timeline.audio[it].muted && midpoint in timeline.audio[it].interval }
                .sortedWith(compareByDescending<Int> { timeline.audio[it].volumeDB }.thenBy { timeline.audio[it].id })
            for (index in candidates) {
                val item = timeline.audio[index]
                var cursor = item.start.seconds
                var attached = false
                for (run in item.mapping.runs) {
                    val start = cursor
                    cursor += run.duration.seconds
                    if (run.silent || run.isHold || midpoint.seconds < start || midpoint.seconds >= start + run.duration.seconds) continue
                    val a = maxOf(start, word.start)
                    val b = minOf(start + run.duration.seconds, word.end)
                    val value = word.copy(start = run.source(EditTime.of(a - start)), end = run.source(EditTime.of(b - start)))
                    if (value.end > value.start) { kept[index] += value; attached = true }
                    break
                }
                if (attached) break
            }
        }
        val audio = timeline.audio.mapIndexed { index, item ->
            val all = kept[index]
            val next = (all.filter { it.id !in newIDs } + SubtitleTiming.group(all.filter { it.id in newIDs }, settings.maxWords)).sortedBy { it.start }
            item.copy(subtitleWords = next.ifEmpty { null })
        }
        return timeline.copy(subtitles = settings, audio = audio)
    }

    /**
     * What a transcription that listens to the recordings, not to the mix,
     * has to hear: for each source, the stretches that sounds which are not
     * muted play of it, merged where they touch or overlap, so footage used
     * twice or cut in two is listened to once. [sounds] limits it to those
     * sounds; null is every sound. This is the track form of
     * [SubtitleEditing.stretches], for as long as the app transcribes
     * recordings (docs/TRACKS-PLAN.md, S8).
     */
    fun stretches(timeline: TrackTimeline, sounds: Set<String>? = null): List<SubtitleStretch> {
        val order = ArrayList<String>()
        val ranges = HashMap<String, MutableList<SourceInterval>>()
        for (item in timeline.audio) {
            if (item.muted || (sounds != null && item.id !in sounds)) continue
            for (run in item.mapping.runs) {
                if (run.silent || run.isHold) continue
                if (item.sourceID !in ranges) order += item.sourceID
                ranges.getOrPut(item.sourceID) { mutableListOf() } += SourceInterval(run.sourceStart, run.sourceEnd)
            }
        }
        return order.flatMap { source ->
            val merged = ArrayList<SourceInterval>()
            for (range in ranges.getValue(source).sortedBy { it.start }) {
                val last = merged.lastOrNull()
                if (last != null && range.start <= last.end + 0.001) merged[merged.lastIndex] = SourceInterval(last.start, maxOf(last.end, range.end)) else merged += range
            }
            merged.map { SubtitleStretch(source, it.start, it.end) }
        }
    }

    /**
     * Replaces every caption with a transcription of the recordings: [words]
     * are the words heard in each source, by source id and in source seconds.
     * Every sound that is not muted (among [sounds], when given) takes its
     * own copy, under new ids, of the words whose middle one of its audible
     * runs plays, cut at that run's edges. So a recording played by two
     * sounds is captioned on both, and correcting one never changes the
     * other. Every other sound loses its words. The fingerprint is taken anew.
     *
     * This is the rule of [SubtitleEditing.install] on sound items, for as
     * long as the app transcribes recordings; the original's rule is
     * [installing].
     */
    fun installingSourceWords(timeline: TrackTimeline, words: Map<String, List<SubtitleWord>>, language: String, sounds: Set<String>? = null): TrackTimeline {
        val settings = (timeline.subtitles ?: SubtitleSettings()).copy(language = language)
        val audio = timeline.audio.map { item ->
            val heard = if (item.muted || (sounds != null && item.id !in sounds)) emptyList() else words[item.sourceID].orEmpty()
            val own = heard.mapNotNull { word ->
                val middle = (word.start + word.end) / 2
                val run = item.mapping.runs.firstOrNull { !it.silent && !it.isHold && middle >= it.sourceStart && middle < it.sourceEnd } ?: return@mapNotNull null
                word.copy(id = newID(), start = maxOf(run.sourceStart, word.start), end = minOf(run.sourceEnd, word.end)).takeIf { it.valid }
            }
            item.copy(subtitleWords = SubtitleTiming.group(own, settings.maxWords).ifEmpty { null })
        }
        val result = timeline.copy(audio = audio)
        return result.copy(subtitles = settings.copy(timingFingerprint = fingerprint(result)))
    }
}
