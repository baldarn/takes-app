package app.takes.core

import kotlin.math.abs

/**
 * The parity suite: every editing function of today, run as it is and run
 * through the track model ([TrackSequenceView]), on the same edit with the
 * same arguments, and the two answers compared.
 *
 * It does not judge. A difference is given a kind ([TrackParityKinds]) and
 * counted; what the suite produces is the list of kinds, with an example of
 * each, for whoever replaces a function of today by its port to read first
 * (docs/TRACKS-PARITY.md, written by [TrackParityTest]).
 */
internal object TrackParity {
    /** An edit to try the functions on, with the media of its project. */
    class Subject(val name: String, val state: EditState, val sources: List<SourceMedia>, val small: Boolean = false)

    /**
     * What one side answered: the edit it left, values beside it (where the
     * playhead goes, a list of times), or, for the track model, why its
     * result is no sequence.
     */
    class Answer(
        val state: EditState? = null,
        val values: Map<String, Any?> = emptyMap(),
        val obstacle: SequenceObstacle? = null,
        val notes: Set<SequenceNote> = emptySet(),
        val timeline: TrackTimeline? = null,
    )

    /** One call of one function: what it was called with, where on the edit that falls ([tags]), and the two ways to run it. */
    class Run(val function: String, val label: String, val tags: Set<String>, val today: () -> Answer?, val track: () -> Answer?)

    enum class Verdict { Identical, Different, NotExpressible }

    /**
     * A call that was run, with what came of it. The two answers are kept
     * only on the cases that are shown as examples ([drop]).
     */
    class Case(
        val subject: Subject, val function: String, val label: String, val tags: Set<String>,
        var today: Answer?, var track: Answer?, val verdict: Verdict, val aspects: Set<String>,
    ) {
        val obstacle: SequenceObstacle? = track?.obstacle
        val notes: Set<SequenceNote> = track?.notes.orEmpty()
        /** The rule the timeline the track model answered breaks, and the one today's answer breaks once converted. Null for none. */
        val refused: TrackInvariants.Violation? = track?.timeline?.let { TrackInvariants.violation(it, subject.sources) }
        val todayRefused: TrackInvariants.Violation? = today?.state?.let { TrackInvariants.violation(it.toTimeline(), subject.sources) }
        val todayAnswered: Boolean = today?.state != null
        val trackAnswered: Boolean = track?.timeline != null
        /** What the call differs in: the names of [aspects], or that its result is no sequence and why. */
        val signature: Set<String> = obstacle?.let { setOf("no sequence: ${it.name}") } ?: aspects
        private val explained = if (verdict == Verdict.Identical) emptyList<String>() to emptySet() else TrackParityKinds.explain(function, tags, signature)
        /** The kinds of difference the call shows ([TrackParityKinds]). */
        val kinds: List<String> = explained.first
        /** What no kind accounts for. */
        val unexplained: Set<String> = explained.second
        /** The kinds together, as one name, with what is left over. */
        val kind: String = (kinds + (if (unexplained.isEmpty()) emptyList() else listOf("unexplained: $function: " + unexplained.sorted().joinToString(", ")))).joinToString(" + ")
        val size: Int get() = subject.state.clips.size * 3 + subject.state.audio.orEmpty().size * 2 + label.length / 20 + (if (subject.small) 0 else 6)

        fun drop() {
            today = null
            track = null
        }
    }

    const val SAME_TIME = 3 * TransitionLayout.TICK
    private const val SAME_VALUE = 0.0001
    private val random = Regex("[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}")

    /** Ids made while a function ran are random; every other id of a subject is a fixed name. */
    fun isNew(id: String?) = id != null && random.matches(id)

    private fun near(a: Double, b: Double, slack: Double = SAME_TIME) = abs(a - b) <= slack

    // MARK: Comparing

    /** [state] as the track model gives a sequence back: fits saved, settings that do not play gone, fades as heard, sound tracks repacked. */
    fun canonical(state: EditState): EditState = TrackSequenceView.sequence(state.toTimeline()).state ?: state

    private fun name(id: String?): String? = if (isNew(id)) "~" else id

    private fun settings(value: TransitionSettings?, clips: List<TimelineClip>): TransitionSettings? =
        value?.copy(id = name(value.id)!!, nextClipID = value.nextClipID?.let { next -> clipName(clips, next) })

    private fun clipName(clips: List<TimelineClip>, id: String): String = if (isNew(id)) "~clip${clips.indexOfFirst { it.id == id }}" else id

    /** In what the two edits differ, as a set of names; empty when they are the same edit. Ids made by the call count as equal where they stand in the same place. */
    fun aspects(a: EditState, b: EditState): Set<String> {
        val result = LinkedHashSet<String>()
        if (a.clips.size != b.clips.size) result += if (a.clips.size > b.clips.size) "clips: more today" else "clips: more through the track model"
        else for ((x, y) in a.clips.zip(b.clips)) {
            if (clipName(a.clips, x.id) != clipName(b.clips, y.id)) result += "clip: identity"
            if (x.sourceID != y.sourceID) result += "clip: source"
            if (!near(x.start, y.start) || !near(x.end, y.end)) result += "clip: source bounds"
            if (!near(x.rate, y.rate, SAME_VALUE)) result += "clip: speed"
            for ((word, s, t, f, g) in listOf(
                Quint("transition", settings(x.transition, a.clips), settings(y.transition, b.clips), x.transitionFit, y.transitionFit),
                Quint("opening", settings(x.openingTransition, a.clips), settings(y.openingTransition, b.clips), x.openingTransitionFit, y.openingTransitionFit),
            )) {
                if (s == null && t == null) continue
                if (s == null || t == null) { result += "$word: only ${if (s != null) "today" else "through the track model"}"; continue }
                if (s != t) result += "$word: settings"
                if (!near(f?.factor ?: 1.0, g?.factor ?: 1.0, SAME_VALUE)) result += "$word: fit"
            }
            sound(x.audio, y.audio, "clip", result)
            // A bookmark is on a source second; through the track model that second is put on a tick of the timeline.
            fun marks(clip: TimelineClip) = clip.bookmarks.orEmpty().sortedBy { it.time }
            val (m, n) = marks(x) to marks(y)
            if (m.size != n.size || m.zip(n).any { (p, q) -> name(p.id) != name(q.id) || p.label != q.label || !near(p.time, q.time, SAME_TIME * maxOf(1.0, x.rate)) }) result += "clip: bookmarks"
            fun words(clip: TimelineClip): List<Any> {
                val phrases = clip.subtitleWords.orEmpty().map { it.phraseID }.distinct()
                return clip.subtitleWords.orEmpty().map { listOf(it.text, Math.round(it.start * 1000), Math.round(it.end * 1000), phrases.indexOf(it.phraseID)) }
            }
            if (words(x) != words(y)) result += "clip: words"
            if (x.texts.orEmpty().map { it.copy(id = name(it.id)!!) } != y.texts.orEmpty().map { it.copy(id = name(it.id)!!) }) result += "clip: titles"
            if (x.color?.let { it.copy(id = name(it.id)!!) } != y.color?.let { it.copy(id = name(it.id)!!) }) result += "clip: color"
            if (x.crop?.let { it.copy(id = name(it.id)!!) } != y.crop?.let { it.copy(id = name(it.id)!!) }) result += "clip: framing"
        }
        // By what they play before where they are, so that an item that begins a moment later on one side is still compared with itself.
        val order = compareBy<AudioItem> { it.sourceID }.thenBy { Math.round(it.start * 1000) }.thenBy { it.sourceStart }
        val p = a.audio.orEmpty().sortedWith(order)
        val q = b.audio.orEmpty().sortedWith(order)
        if (p.size != q.size) result += if (p.size > q.size) "sound items: more today" else "sound items: more through the track model"
        else for ((x, y) in p.zip(q)) {
            if (name(x.id) != name(y.id)) result += "sound item: identity"
            if (x.sourceID != y.sourceID) result += "sound item: source"
            if (!near(x.start, y.start)) result += "sound item: place"
            if (!near(x.sourceStart, y.sourceStart) || !near(x.sourceEnd, y.sourceEnd)) result += "sound item: source bounds"
            sound(x.audio, y.audio, "sound item", result)
        }
        if (a.subtitles?.copy(timingFingerprint = "", id = "") != b.subtitles?.copy(timingFingerprint = "", id = "")) result += "captions: settings"
        if (SubtitleEditing.groupingAvailable(a) != SubtitleEditing.groupingAvailable(b)) result += "captions: regrouping allowed"
        return result
    }

    private data class Quint<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)

    private fun sound(a: ClipAudio?, b: ClipAudio?, word: String, into: MutableSet<String>) {
        val x = a ?: ClipAudio()
        val y = b ?: ClipAudio()
        val found = LinkedHashSet<String>()
        if (!near(x.volumeDB, y.volumeDB, SAME_VALUE) || x.muted != y.muted) found += "$word: level or mute"
        if (!near(x.fadeIn, y.fadeIn, SAME_VALUE) || !near(x.fadeOut, y.fadeOut, SAME_VALUE)) found += "$word: fades"
        else if (!near(x.fadeInElapsed ?: 0.0, y.fadeInElapsed ?: 0.0, SAME_VALUE) || !near(x.fadeOutElapsed ?: 0.0, y.fadeOutElapsed ?: 0.0, SAME_VALUE)) found += "$word: how far into a fade it begins"
        if (!near(x.denoise, y.denoise, SAME_VALUE)) found += "$word: noise reduction"
        // Where neither is heard, what differs is what would be heard once it is unmuted.
        if (x.muted && y.muted && found.isNotEmpty()) into += "$word: what a muted sound remembers" else into += found
    }

    private fun sameValue(a: Any?, b: Any?): Boolean = when {
        a is Double && b is Double -> near(a, b, SAME_VALUE)
        a is List<*> && b is List<*> -> a.size == b.size && a.indices.all { sameValue(a[it], b[it]) }
        else -> a == b
    }

    /** Runs [run] on [subject] and says what came of it. */
    fun judge(subject: Subject, run: Run): Case {
        val before = canonical(subject.state)
        fun settled(answer: Answer?): Answer? {
            // An answer that leaves the edit as it was, and says nothing else, is no answer.
            val state = answer?.state ?: return answer
            return if (answer.values.isEmpty() && answer.obstacle == null && aspects(before, state).isEmpty()) null else answer
        }
        // A function that throws has answered: that is a finding, not a failure of the suite.
        fun answered(call: () -> Answer?): Answer? = try {
            call()
        } catch (failure: RuntimeException) {
            Answer(values = mapOf("throws" to failure.javaClass.simpleName))
        }
        val today = settled(answered(run.today)?.let { answer -> Answer(answer.state?.let(::canonical), answer.values) })
        val track = settled(answered(run.track))
        val aspects = LinkedHashSet<String>()
        val verdict = when {
            today == null && track == null -> Verdict.Identical
            track?.obstacle != null -> Verdict.NotExpressible
            today == null -> { aspects += "applies only through the track model"; Verdict.Different }
            track == null -> { aspects += "applies only today"; Verdict.Different }
            else -> {
                if (today.state != null && track.state != null) aspects += aspects(today.state, track.state)
                for (key in today.values.keys + track.values.keys) if (!sameValue(today.values[key], track.values[key])) aspects += "answer: $key"
                if (aspects.isEmpty()) Verdict.Identical else Verdict.Different
            }
        }
        return Case(subject, run.function, run.label, run.tags, today, track, verdict, aspects)
    }

    // MARK: Showing

    private fun seconds(value: Double) = "%.3f".format(value).trimEnd('0').trimEnd('.')

    /** Short names for the ids of one example, in the order they are first seen: c1, c2 for clips, a1 for sound items. */
    class Names {
        private val clips = LinkedHashMap<String, String>()
        private val items = LinkedHashMap<String, String>()
        fun clip(id: String) = clips.getOrPut(id) { "c${clips.size + 1}" }
        fun item(id: String) = clips[id]?.let { "sound of $it" } ?: items.getOrPut(id) { "a${items.size + 1}" }
    }

    private fun describe(value: TransitionSettings, fit: TransitionFit?): String =
        "${value.style.name.lowercase()} ${seconds(value.duration)}" + (fit?.factor?.takeIf { it < 0.9999 }?.let { " ×" + "%.2f".format(it) } ?: "")

    private fun describe(audio: ClipAudio?): String {
        val value = audio ?: return ""
        return listOfNotNull(
            if (value.muted) "muted" else null,
            if (value.volumeDB != 0.0) "${seconds(value.volumeDB)} dB" else null,
            if (value.fadeIn > 0 || value.fadeOut > 0) "fades ${seconds(value.fadeIn)}/${seconds(value.fadeOut)}" else null,
            if ((value.fadeInElapsed ?: 0.0) > 0 || (value.fadeOutElapsed ?: 0.0) > 0) "begun ${seconds(value.fadeInElapsed ?: 0.0)}/${seconds(value.fadeOutElapsed ?: 0.0)} into them" else null,
            if (value.denoise > 0) "noise reduction ${seconds(value.denoise)}" else null,
        ).joinToString(", ").let { if (it.isEmpty()) "" else " [$it]" }
    }

    /** An edit on a few lines: each clip with its source seconds and what is on it, each sound item with its place. */
    fun describe(state: EditState, names: Names): String {
        val starts = state.clips.indices.map { TimelineEditing.clipStart(state.clips, it) }
        val clips = state.clips.mapIndexed { index, clip ->
            "${names.clip(clip.id)} @${seconds(starts[index])} ${clip.sourceID} ${seconds(clip.start)}–${seconds(clip.end)}" +
                (if (clip.rate != 1.0) " at ×${seconds(clip.rate)}" else "") +
                (clip.openingTransition?.let { " (opens: ${describe(it, clip.openingTransitionFit)})" } ?: "") +
                (clip.transition?.let { " (${if (it.nextClipID == null) "closes" else "into next"}: ${describe(it, clip.transitionFit)})" } ?: "") +
                describe(clip.audio) +
                (clip.bookmarks?.takeIf { it.isNotEmpty() }?.let { marks -> " marks " + marks.joinToString(",") { seconds(it.time) } } ?: "") +
                (clip.subtitleWords?.takeIf { it.isNotEmpty() }?.let { " ${it.size} words" } ?: "") +
                (clip.texts?.takeIf { it.isNotEmpty() }?.let { " ${it.size} titles" } ?: "")
        }
        val items = state.audio.orEmpty().sortedBy { it.start }.map { item ->
            "${names.item(item.id)} @${seconds(item.start)} ${item.sourceID} ${seconds(item.sourceStart)}–${seconds(item.sourceEnd)}${describe(item.audio)}"
        }
        return (clips.ifEmpty { listOf("no clips") } + items).joinToString("\n") { "    $it" } + "\n    (${seconds(state.duration)} s of picture${if (state.subtitles != null) ", with the look of its captions" else ""})"
    }

    fun describe(answer: Answer?, names: Names): String {
        if (answer == null) return "    does not apply"
        val values = answer.values.entries.joinToString("") { (key, value) -> "\n    $key: ${show(value)}" }
        answer.obstacle?.let { return "    no sequence: ${it.meaning}" + (answer.timeline?.let { timeline -> "\n" + describe(timeline, names) } ?: "") + values }
        return (answer.state?.let { describe(it, names) } ?: "").ifEmpty { "   " }.let { if (answer.state == null) values.trimStart('\n') else it + values }
    }

    private fun show(value: Any?): String = when (value) {
        is Double -> seconds(value)
        is List<*> -> value.joinToString(", ", "[", "]") { show(it) }
        else -> value.toString()
    }

    /** A timeline that is no sequence, on a few lines: its pictures and sounds with their places. */
    fun describe(timeline: TrackTimeline, names: Names): String {
        val pictures = timeline.chronologicalVideo.map { "    picture ${names.clip(it.id)} ${seconds(it.start.seconds)}–${seconds(it.end.seconds)} layer ${it.layer}" }
        val owner = TrackSequenceView.sounds(timeline).entries.associate { it.value.id to it.key }
        val sounds = timeline.audio.map { sound ->
            "    sound ${owner[sound.id]?.let { "of " + names.clip(it) } ?: names.item(sound.id)} ${seconds(sound.start.seconds)}–${seconds(sound.end.seconds)}" +
                (if (sound.followsVideo == null) " (follows nothing)" else "") +
                listOfNotNull(sound.fadeIn?.let { "fade in ${seconds(it.seconds)} from ${seconds(it.elapsed)}" }, sound.fadeOut?.let { "fade out ${seconds(it.seconds)} from ${seconds(it.elapsed)}" })
                    .joinToString(", ").let { if (it.isEmpty()) "" else " [$it]" }
        }
        val relations = timeline.transitions.map { "    transition ${it.outgoing?.let(names::clip) ?: "–"} → ${it.incoming?.let(names::clip) ?: "–"} from ${seconds(it.origin.seconds)}" }
        return (pictures + sounds + relations).joinToString("\n")
    }

    fun example(case: Case): String {
        val names = Names()
        return buildString {
            appendLine("`${case.function}(${case.label})` on `${case.subject.name}`:")
            appendLine()
            appendLine("```")
            appendLine("before")
            appendLine(describe(case.subject.state, names))
            appendLine("today")
            appendLine(describe(case.today, names))
            appendLine("through the track model")
            appendLine(describe(case.track, names))
            append("```")
        }
    }
}
