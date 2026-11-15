package app.takes.core

import app.takes.core.TrackParity.Answer
import app.takes.core.TrackParity.Run
import app.takes.core.TrackParity.Subject
import kotlin.math.abs

/** Where a call falls on the edit it is made on: what the kinds of difference are told apart by. */
internal object Tag {
    const val OFF_GRID = "off the 1/30 s grid"
    const val NEAR_EDGE = "within 0.1 s of a clip's edge"
    const val IN_TRANSITION = "inside a transition"
    const val UNDER_SOUND = "under a sound item"
    const val UNLINKED = "the clip's sound is unlinked"
    const val FADES = "the clip or the item has fades"
    const val MARKS = "the clip has bookmarks"
    const val TRANSITIONS = "the clip has a transition"
    const val MIDDLE_OPENING = "the clip has an opening and is not the first"
    const val MIDDLE_CLOSE = "the clip has a close and is not the last"
    const val SPEED = "the clip plays at another speed"
    const val NOISE = "the clip has noise reduction"
    const val SOUND_ITEMS = "the edit has sound items"
    const val LONG_FADE = "a fade longer than half the item"
    const val WORDS = "the clip has words"
    const val MUTED = "the clip is muted"
    const val SOUND_PAST_END = "sound runs past the end of the picture"
    const val SOME_UNLINKED = "a clip of the edit has its sound unlinked"
    const val LAST_SECOND = "at the last second of the clip"
    const val OUTWARDS = "an edge moved outwards"
    const val INWARDS = "an edge moved inwards"
    const val SOUND_ONLY = "limited to sound items"
    const val SOME_TRANSITION = "the edit has a transition"
    const val TRANSITION_START = "on the first instant of a transition"
    const val NEAR_TRANSITION_EDGE = "within a frame of where a transition begins or ends"
    const val PAST_PICTURE = "after the last clip, where only sound plays"
    const val SOUND_AFTER_PICTURE = "a sound item of the scope begins after the last clip"
    const val PLAYHEAD_HELD = "the playhead is in time a transition holds, where no clip plays"
}

/**
 * The edits the parity suite runs on (the golden projects with every step of
 * their history, small edits made for one situation each, and edits made by
 * seeded sequences of today's functions) and, for each function of today,
 * the calls it is tried with.
 */
internal object TrackParityCases {
    private val takes = listOf(12.0, 8.0, 6.0, 20.0).mapIndexed { index, seconds -> SourceMedia("S${index + 1}", "take-${index + 1}.mp4", seconds, 0L) }
    private val sounds = listOf(30.0, 5.0).mapIndexed { index, seconds -> SourceMedia("M${index + 1}", "sound-${index + 1}.m4a", seconds, 0L, audioOnly = true) }
    val sources = takes + sounds

    private val random = Regex("[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}")

    /** [state] with every id made at random replaced by a numbered one, the same wherever it appears. */
    fun stable(state: EditState, prefix: String): EditState {
        val names = HashMap<String, String>()
        val text = random.replace(documentJson.encodeToString(EditState.serializer(), state)) { names.getOrPut(it.value) { "$prefix${names.size + 1}" } }
        val renamed = documentJson.decodeFromString(EditState.serializer(), text)
        val settings = renamed.subtitles ?: return renamed
        return renamed.copy(subtitles = settings.copy(timingFingerprint = if (SubtitleEditing.groupingAvailable(state)) SubtitleEditing.fingerprint(renamed.clips) else ""))
    }

    private fun words(source: SourceMedia, text: String, from: Double, each: Double): List<SubtitleWord> =
        text.split(" ").mapIndexed { index, word -> SubtitleWord("W-${source.id}-$index", "P-${source.id}-${index / 3}", word, from + index * each, from + index * each + each * 0.8) }

    private val heard = mapOf(
        "S1" to words(takes[0], "one two three four five six seven eight nine ten", 0.5, 1.0),
        "S2" to words(takes[1], "alpha beta gamma delta epsilon", 0.4, 1.2),
        "S3" to words(takes[2], "red green blue", 1.0, 1.5),
    )

    private fun whole(index: Int, id: String) = TimelineClip(id, takes[index].id, 0.0, takes[index].duration)

    private fun build(name: String, state: EditState, vararg steps: (EditState) -> EditState?): Subject {
        var result = state
        steps.forEachIndexed { index, step -> result = checkNotNull(step(result)) { "$name: step $index did not apply" } }
        return Subject(name, stable(result, "N"), sources, small = true)
    }

    private fun dissolve(id: String, duration: Double = 1.0, style: TransitionStyle = TransitionStyle.Dissolve) = TransitionSettings(id = id, style = style, duration = duration)

    /** Small edits, one situation each, so that every kind of difference has an example short enough to read. */
    val probes: List<Subject> by lazy {
        val plain = EditState(listOf(whole(0, "A"), whole(1, "B"), whole(2, "C")))
        listOf(
            build("three takes", plain),
            build("a dissolve", plain, { LegacyTimelineEditing.setJoin(it, 1, dissolve("T1")) }),
            build("an opening and a close", plain, { LegacyTimelineEditing.setJoin(it, 0, dissolve("T0", 0.5, TransitionStyle.FadeColor)) }, { LegacyTimelineEditing.setJoin(it, 3, dissolve("T3", 0.5, TransitionStyle.FadeColor)) }),
            build("an opening on a middle clip", plain, { LegacyTimelineEditing.setJoin(it, 0, dissolve("T0", 1.0, TransitionStyle.FadeColor)) }, { LegacyTimelineEditing.move(it, 2, 0) }),
            build("a close on a middle clip", plain, { LegacyTimelineEditing.setJoin(it, 3, dissolve("T3", 1.0, TransitionStyle.FadeColor)) }, { LegacyTimelineEditing.move(it, 0, 2) }),
            build("transitions on both sides", plain, { LegacyTimelineEditing.setJoin(it, 1, dissolve("T1", 2.0)) }, { LegacyTimelineEditing.setJoin(it, 2, dissolve("T2", 2.0, TransitionStyle.Wipe)) }),
            build(
                "fades and levels", plain,
                { LegacyTimelineEditing.setAudio(it, 0, ClipAudio(fadeIn = 1.0, fadeOut = 2.0)) }, { LegacyTimelineEditing.setAudio(it, 1, ClipAudio(volumeDB = -6.0)) },
                { LegacyTimelineEditing.setAudio(it, 2, ClipAudio(denoise = 0.5)) },
            ),
            build(
                "music and a voice", plain,
                { LegacyTimelineEditing.addAudio(it, "M1", 30.0, 0.0) }, { LegacyTimelineEditing.addAudio(it, "M2", 5.0, 3.0) },
                { state -> LegacyTimelineEditing.setAudioItemSound(state, state.audio!!.first { it.sourceID == "M1" }.id, ClipAudio(volumeDB = -12.0, fadeIn = 1.0, fadeOut = 2.0)) },
            ),
            build("unlinked sound", plain, { LegacyAudioUnlinking.unlink(it, 1) }),
            build("unlinked sound, moved", plain, { LegacyAudioUnlinking.unlink(it, 1) }, { LegacyTimelineEditing.moveAudio(it, "B", 13.5) }),
            build("captions", plain, { LegacySubtitleEditing.install(it, heard, "en") }),
            build("captions, then the sound of a clip unlinked", plain, { LegacySubtitleEditing.install(it, heard, "en") }, { LegacyAudioUnlinking.unlink(it, 1) }),
            build("captions on a muted clip", plain, { LegacySubtitleEditing.install(it, heard, "en") }, { LegacyTimelineEditing.setAudio(it, 1, ClipAudio(muted = true)) }),
            build("a speed", plain, { LegacyTimelineEditing.setJoin(it, 1, dissolve("T1")) }, { LegacyTimelineEditing.setSpeed(it, 1, 2.0) }),
            build("cuts off the grid", plain, { LegacyTimelineEditing.split(it, 3.123456) }, { LegacyTimelineEditing.split(it, 15.4321) }),
            build(
                "bookmarks and a title", plain,
                { LegacyTimelineEditing.toggleBookmark(it, 0, 2.0) }, { LegacyTimelineEditing.toggleBookmark(it, 0, 9.0) }, { LegacyTimelineEditing.toggleBookmark(it, 1, 4.0) },
                { LegacyTimelineEditing.setText(it, 0, TextOverlay(id = "TITLE", text = "Title", start = 1.0, end = 4.0)) },
            ),
            build("one take", EditState(listOf(whole(0, "A")))),
            build("two short clips", EditState(listOf(TimelineClip("A", "S1", 0.0, 1.0), TimelineClip("B", "S2", 0.0, 0.6)))),
        )
    }

    /** Every step of the history of every golden project but the long one. */
    val golden: List<Subject> by lazy {
        GoldenProjects.all.filter { !it.large }.flatMap { golden ->
            golden.project.snapshots.map { it.state }.distinct().mapIndexed { index, state -> Subject("golden ${golden.name}, step $index", state, golden.project.sources) }
        }.filter { it.state.clips.isNotEmpty() }
    }

    /** Edits made by sequences of today's functions, the same every run. */
    val generated: List<Subject> by lazy {
        val result = ArrayList<Subject>()
        for (seed in 1..SEQUENCES) {
            val rng = TrackFixtures.Random(seed * 7919L)
            var state = EditState((0 until 3 + rng.below(2)).map { whole(it, "G$seed-${it + 1}") })
            var made = 0
            var tried = 0
            while (made < STEPS && tried < STEPS * 6) {
                tried++
                val next = step(state, rng, "T$seed-$tried") ?: continue
                if (next.clips.isEmpty() || next == state) continue
                state = stable(next, "G$seed-n$tried-")
                made++
                result += Subject("generated $seed, step $made", state, sources)
            }
        }
        result
    }

    const val SEQUENCES = 30
    const val STEPS = 10

    private fun <T> TrackFixtures.Random.pick(vararg values: T): T = values[below(values.size)]

    /** One edit by one of today's functions, chosen at random. */
    private fun step(state: EditState, rng: TrackFixtures.Random, id: String): EditState? {
        val clips = state.clips
        val index = rng.below(clips.size)
        val clip = clips[index]
        val length = clip.end - clip.start
        return when (rng.below(18)) {
            0, 1 -> LegacyTimelineEditing.split(state, rng.next(0.0, state.duration))
            2 -> LegacyTimelineEditing.split(state, RangeEditing.quantize(rng.next(0.0, state.duration)))
            3 -> LegacyTimelineEditing.trim(state, index, clip.start + rng.next(0.0, 0.3) * length, clip.end - rng.next(0.0, 0.3) * length, takes.first { it.id == clip.sourceID }.duration)
            4 -> if (clips.size > 2) LegacyTimelineEditing.delete(state, index) else null
            5 -> LegacyTimelineEditing.move(state, index, rng.below(clips.size))
            6, 7 -> LegacyTimelineEditing.setJoin(state, rng.below(clips.size + 1), dissolve(id, rng.pick(0.3, 1.0, 2.5), TransitionStyle.entries[rng.below(TransitionStyle.entries.size)]))
            8 -> LegacyTimelineEditing.setSpeed(state, index, rng.pick(0.5, 1.5, 2.0))
            9 -> LegacyTimelineEditing.setAudio(state, index, rng.pick(ClipAudio(volumeDB = -6.0), ClipAudio(fadeIn = 0.5, fadeOut = 1.0), ClipAudio(muted = true), ClipAudio(denoise = 0.6)))
            10 -> LegacyTimelineEditing.toggleBookmark(state, index, clip.start + rng.next(0.1, 0.9) * length)
            11 -> rng.pick(sounds[0], sounds[1]).let { LegacyTimelineEditing.addAudio(state, it.id, it.duration, RangeEditing.quantize(rng.next(0.0, state.duration))) }
            12 -> state.audio?.let { items ->
                val item = items[rng.below(items.size)]
                when (rng.below(3)) {
                    0 -> LegacyTimelineEditing.moveAudio(state, item.id, RangeEditing.quantize(rng.next(0.0, state.duration)))
                    1 -> LegacyTimelineEditing.trimAudio(state, item.id, item.start + rng.next(0.2, 0.8) * item.duration, rng.below(2) == 0)
                    else -> LegacyTimelineEditing.setAudioItemSound(state, item.id, ClipAudio(volumeDB = -9.0, fadeIn = 0.5, fadeOut = 0.5))
                }
            }
            13 -> LegacyAudioUnlinking.unlink(state, index)
            14 -> rng.next(0.0, state.duration).let { from -> LegacyRangeEditing.delete(state, EditScope.Range(TimeRange(from, from + rng.next(0.2, 3.0))))?.state }
            15 -> LegacyRangeEditing.copy(state, EditScope.Selected(EditTargets(clips = setOf(clip.id))))?.let { LegacyRangeEditing.paste(state, it, rng.next(0.0, state.duration))?.state }
            16 -> if (state.subtitles == null) LegacySubtitleEditing.install(state, heard, "en") else LegacyTimelineEditing.setText(state, index, TextOverlay(id = "TITLE-$id", text = "Title", start = clip.start, end = clip.end))
            else -> LegacyTimelineEditing.removeIntervals(state, index, listOf(SourceInterval(clip.start + 0.3 * length, clip.start + 0.4 * length), SourceInterval(clip.start + 0.6 * length, clip.start + 0.7 * length)))
        }
    }

    // MARK: Answers

    private fun today(state: EditState?, vararg values: Pair<String, Any?>): Answer? = state?.let { Answer(it, values.toMap()) }

    /** What a function answers now that it runs on the track model, with the timeline its answer converts to, for the invariants. */
    private fun wrapped(state: EditState?, vararg values: Pair<String, Any?>): Answer? = state?.let { Answer(it, values.toMap(), timeline = it.toTimeline()) }

    private fun value(name: String, value: Any?) = Answer(values = mapOf(name to value))

    /** A copied fragment with its clips and sound items named by their place: a paste gives every one a new id, so the ones it has say nothing. */
    private fun nameless(state: EditState): EditState {
        val names = state.clips.mapIndexed { index, clip -> clip.id to "clip $index" }.toMap()
        return state.copy(
            clips = state.clips.map { clip -> clip.copy(id = names.getValue(clip.id), transition = clip.transition?.let { it.copy(nextClipID = it.nextClipID?.let { next -> names[next] ?: next }) }) },
            // The unlinked sound of a clip keeps the clip's name, which is what makes it that clip's sound.
            audio = state.audio?.sortedBy { it.start }?.mapIndexed { index, item -> item.copy(id = names[item.id] ?: "item $index") },
        )
    }

    private fun seconds(value: Double) = "%.3f".format(value).trimEnd('0').trimEnd('.')

    // MARK: Where a call falls

    private fun at(state: EditState, time: Double): Set<String> {
        val tags = LinkedHashSet<String>()
        if (abs(RangeEditing.quantize(time) - time) > 0.0000001) tags += Tag.OFF_GRID
        TimelineEditing.locate(state.clips, time)?.let { (index, offset) ->
            if (offset < TimelineEditing.MINIMUM_CLIP || state.clips[index].duration - offset < TimelineEditing.MINIMUM_CLIP) tags += Tag.NEAR_EDGE
            tags += on(state, index)
        }
        val transitions = TimelineEditing.transitions(state.clips)
        if (transitions.any { time > it.range.start && time < it.range.endInclusive }) tags += Tag.IN_TRANSITION
        if (transitions.any { abs(time - it.range.start) < INSTANT && it.range.endInclusive > it.range.start }) tags += Tag.TRANSITION_START
        if (transitions.any { join -> listOf(join.range.start, join.range.endInclusive).any { abs(time - it) >= INSTANT && abs(time - it) < RangeEditing.FRAME } }) tags += Tag.NEAR_TRANSITION_EDGE
        if (time > state.duration + INSTANT) tags += Tag.PAST_PICTURE
        if (state.audio.orEmpty().any { time > it.start && time < it.end }) tags += Tag.UNDER_SOUND
        return tags
    }

    /** Times this close are one instant. */
    private const val INSTANT = 0.000001

    /** True where no clip plays at [time] although the picture goes on: a still or a color held by a transition. */
    private fun held(state: EditState, time: Double): Boolean =
        time < state.duration && state.clips.indices.none { index -> TimelineEditing.clipStart(state.clips, index).let { time >= it - INSTANT && time < it + state.clips[index].duration } }

    private fun on(state: EditState, index: Int): Set<String> {
        val clip = state.clips.getOrNull(index) ?: return emptySet()
        val tags = LinkedHashSet<String>()
        if (AudioUnlinking.isDetached(state, index)) tags += Tag.UNLINKED
        if ((clip.audio?.fadeIn ?: 0.0) > 0 || (clip.audio?.fadeOut ?: 0.0) > 0) tags += Tag.FADES
        if ((clip.audio?.denoise ?: 0.0) > 0) tags += Tag.NOISE
        if (clip.audio?.muted == true) tags += Tag.MUTED
        if (!clip.bookmarks.isNullOrEmpty()) tags += Tag.MARKS
        if (!clip.subtitleWords.isNullOrEmpty()) tags += Tag.WORDS
        if (clip.rate != 1.0) tags += Tag.SPEED
        val layout = TimelineEditing.layout(state.clips)
        val entering = layout.entering(index)?.takeIf { it.transition != null }
        val leaving = layout.leaving(index)?.takeIf { it.transition != null }
        if (entering != null || leaving != null) tags += Tag.TRANSITIONS
        if (index > 0 && entering?.edge == -1) tags += Tag.MIDDLE_OPENING
        if (index < state.clips.lastIndex && leaving?.edge == 1) tags += Tag.MIDDLE_CLOSE
        if (!state.audio.isNullOrEmpty()) tags += Tag.SOUND_ITEMS
        return tags
    }

    private fun onItem(item: AudioItem): Set<String> =
        if ((item.audio?.fadeIn ?: 0.0) > 0 || (item.audio?.fadeOut ?: 0.0) > 0) setOf(Tag.FADES) else emptySet()

    private fun about(state: EditState, scope: EditScope): Set<String> {
        val tags = LinkedHashSet<String>()
        if (!state.audio.isNullOrEmpty()) tags += Tag.SOUND_ITEMS
        when (scope) {
            is EditScope.Range -> {
                if (scope.targets != null && scope.targets.clips.isEmpty()) tags += Tag.SOUND_ONLY
                for (time in listOf(scope.range.start, scope.range.end)) {
                    val here = at(state, time)
                    if (Tag.IN_TRANSITION in here) tags += Tag.IN_TRANSITION
                    if (Tag.TRANSITION_START in here) tags += Tag.TRANSITION_START
                    if (Tag.NEAR_TRANSITION_EDGE in here) tags += Tag.NEAR_TRANSITION_EDGE
                    if (Tag.OFF_GRID in here) tags += Tag.OFF_GRID
                    if (Tag.NEAR_EDGE in here) tags += Tag.NEAR_EDGE
                    if (Tag.UNDER_SOUND in here) tags += Tag.UNDER_SOUND
                }
                state.clips.indices.forEach { index -> on(state, index).let { if (Tag.UNLINKED in it) tags += Tag.UNLINKED; if (Tag.FADES in it) tags += Tag.FADES; if (Tag.TRANSITIONS in it) tags += Tag.TRANSITIONS } }
            }
            is EditScope.Selected -> state.clips.forEachIndexed { index, clip -> if (clip.id in scope.targets.clips) tags += on(state, index) }.also { if (scope.targets.clips.isEmpty()) tags += Tag.SOUND_ONLY }
            EditScope.Whole -> Unit
        }
        // Whole items, with clips among them: where each sound goes in a copy depends on what plays before it.
        val whole = when (scope) { is EditScope.Range -> null; is EditScope.Selected -> scope.targets.audio.takeIf { scope.targets.clips.isNotEmpty() }; EditScope.Whole -> state.audio.orEmpty().map { it.id }.toSet() }
        if (state.audio.orEmpty().any { whole?.contains(it.id) == true && it.start >= state.duration - INSTANT }) tags += Tag.SOUND_AFTER_PICTURE
        if (state.audio.orEmpty().any { (it.audio?.fadeIn ?: 0.0) > 0 || (it.audio?.fadeOut ?: 0.0) > 0 }) tags += Tag.FADES
        return tags
    }

    private fun label(scope: EditScope, state: EditState): String = when (scope) {
        EditScope.Whole -> "everything"
        is EditScope.Selected -> "selected: " + (scope.targets.clips.map { id -> "clip ${state.clips.indexOfFirst { it.id == id } + 1}" } + scope.targets.audio.map { "a sound item" }).joinToString(", ")
        is EditScope.Range -> "range ${seconds(scope.range.start)}–${seconds(scope.range.end)}" +
            (scope.targets?.let { targets -> " of " + (targets.clips.map { id -> "clip ${state.clips.indexOfFirst { it.id == id } + 1}" } + targets.audio.map { "a sound item" }).joinToString(", ") } ?: "")
    }

    // MARK: The calls

    private class Maker(val subject: Subject, val rng: TrackFixtures.Random, val samples: Int) {
        val state = subject.state
        val clips = state.clips
        val runs = ArrayList<Run>()
        /** What is so of the whole edit, whatever the call. */
        val everywhere: Set<String> = setOfNotNull(
            Tag.SOME_UNLINKED.takeIf { clips.indices.any { AudioUnlinking.isDetached(state, it) } },
            Tag.SOME_TRANSITION.takeIf { TimelineEditing.transitions(clips).isNotEmpty() },
            Tag.SOUND_ITEMS.takeIf { !state.audio.isNullOrEmpty() },
        )
        fun add(function: String, label: String, tags: Set<String>, today: () -> Answer?, track: () -> Answer?) { runs += Run(function, label, tags + everywhere, today, track) }
        fun index() = rng.below(clips.size)
        fun indices(): List<Int> = if (clips.size <= samples + 1) clips.indices.toList() else List(samples) { index() }.distinct()
        fun time() = rng.next(0.0, state.duration)
        fun source(clip: TimelineClip) = subject.sources.first { it.id == clip.sourceID }
        fun begins(index: Int) = TimelineEditing.clipStart(clips, index)
        val transitions = TimelineEditing.transitions(clips)
        val items = state.audio.orEmpty()
        /** Times worth trying: some anywhere, one on the grid, one just inside a clip, one in a transition, one under a sound. */
        fun times(): List<Double> = (List(samples) { time() } + RangeEditing.quantize(time()) + (begins(index()) + 0.05) +
            listOfNotNull(transitions.firstOrNull()?.let { (it.range.start + it.range.endInclusive) / 2 }, items.firstOrNull()?.let { it.start + it.duration * 0.4 })).distinct()
    }

    /** The calls every function of today is tried with on [subject]: [samples] of each kind of argument, chosen by [rng], and those aimed at a known situation. */
    fun runs(subject: Subject, rng: TrackFixtures.Random, samples: Int): List<Run> = Maker(subject, rng, samples).apply {
        timeline()
        transitions()
        recipes()
        clipSound()
        soundItems()
        ranges()
        captions()
        unlinking()
        questions()
    }.runs

    private fun Maker.timeline() {
        for (time in times()) add("TimelineEditing.split", "at ${seconds(time)}", at(state, time), { today(LegacyTimelineEditing.split(state, time)) }, { wrapped(TimelineEditing.split(state, time)) })
        for (index in indices()) add("TimelineEditing.delete", "clip ${index + 1}", on(state, index), { today(LegacyTimelineEditing.delete(state, index)) }, { wrapped(TimelineEditing.delete(state, index)) })
        val moves = (List(samples) { index() to index() } + (0 to clips.lastIndex) + (clips.lastIndex to 0)).distinct()
        for ((from, to) in moves) add("TimelineEditing.move", "clip ${from + 1} to place ${to + 1}", on(state, from), { today(LegacyTimelineEditing.move(state, from, to)) }, { wrapped(TimelineEditing.move(state, from, to)) })

        val extra = subject.sources.first { it.audioOnly != true }
        val added = listOf(TimelineClip("NEW", extra.id, 0.0, minOf(extra.duration, 2.5)))
        for (time in times() + begins(index()) + state.duration) {
            add("TimelineEditing.insert", "a clip of ${seconds(added[0].duration)} s at ${seconds(time)}", at(state, time), { today(LegacyTimelineEditing.insert(state, added, time)) }, { wrapped(TimelineEditing.insert(state, added, time)) })
        }
        for (index in indices()) {
            val clip = clips[index]
            val length = clip.end - clip.start
            val limit = source(clip).duration
            val bounds = listOf(
                "the end in by a third" to (clip.start to clip.end - length / 3), "the start in by a third" to (clip.start + length / 3 to clip.end),
                "the end out by 1 s" to (clip.start to clip.end + 1.0), "the start out by 1 s" to (clip.start - 1.0 to clip.end),
                "down to 0.2 s" to (clip.start to clip.start + 0.2 * clip.rate),
            )
            for ((what, range) in bounds) {
                val (start, end) = range
                // What the trim comes to: a clip shorter than the shortest there is grows when it is asked to shrink.
                val next = TimelineEditing.trimmed(clip, start, end, limit)
                val way = on(state, index) + (if (next.start < clip.start || next.end > clip.end) Tag.OUTWARDS else Tag.INWARDS)
                add("TimelineEditing.trim", "clip ${index + 1}, $what", way, { today(LegacyTimelineEditing.trim(state, index, start, end, limit)) }, { wrapped(TimelineEditing.trim(state, index, start, end, limit)) })
            }
            val pauses = listOf(
                "one stretch in the middle" to listOf(SourceInterval(clip.start + 0.4 * length, clip.start + 0.5 * length)),
                "two stretches" to listOf(SourceInterval(clip.start + 0.2 * length, clip.start + 0.3 * length), SourceInterval(clip.start + 0.6 * length, clip.start + 0.8 * length)),
                "all but 0.05 s at the start" to listOf(SourceInterval(clip.start + 0.05 * clip.rate, clip.start + 0.6 * length)),
                "most of the clip" to listOf(SourceInterval(clip.start + 0.1 * length, clip.start + 0.95 * length)),
            )
            for ((what, cuts) in pauses) {
                add("TimelineEditing.removeIntervals", "clip ${index + 1}, $what", on(state, index), { today(LegacyTimelineEditing.removeIntervals(state, index, cuts)) }, { wrapped(TimelineEditing.removeIntervals(state, index, cuts)) })
            }
            for (rate in listOf(0.5, 2.0, 4.0, 1.0)) {
                add("TimelineEditing.setSpeed", "clip ${index + 1} at ×${seconds(rate)}", on(state, index), { today(LegacyTimelineEditing.setSpeed(state, index, rate)) }, { wrapped(TimelineEditing.setSpeed(state, index, rate)) })
            }
            val marks = listOf(clip.start + rng.next(0.1, 0.9) * length, clip.end) + listOfNotNull(clip.bookmarks?.firstOrNull()?.time)
            for (time in marks) {
                val where = if (time == clip.end) " (its last second)" else ""
                add("TimelineEditing.toggleBookmark", "clip ${index + 1} at source second ${seconds(time)}$where", on(state, index) + setOfNotNull(Tag.LAST_SECOND.takeIf { time == clip.end }), { today(LegacyTimelineEditing.toggleBookmark(state, index, time)) }, { wrapped(TimelineEditing.toggleBookmark(state, index, time)) })
            }
        }
    }

    private fun Maker.transitions() {
        val count = clips.size
        val joins = ((0..count).toList() + TimelineEditing.layout(clips).fades.map { it.index } + (count + 1)).distinct()
        for (join in joins) {
            val pair = TrackSequenceView.joinClips(clips, join)
            val tags = LinkedHashSet<String>()
            pair?.first?.let { id -> tags += on(state, clips.indexOfFirst { it.id == id }) }
            pair?.second?.let { id -> tags += on(state, clips.indexOfFirst { it.id == id }) }
            val what = when {
                join == 0 -> "the opening"
                join < count -> "between clips $join and ${join + 1}"
                join == count -> "the close"
                join < 2 * count -> "the opening of clip ${join - count + 1}"
                else -> "the close of clip ${join - 2 * count + 1}"
            }
            val wanted = listOf(dissolve("T-NEW", 0.4), dissolve("T-NEW", 1.5, TransitionStyle.entries[rng.below(TransitionStyle.entries.size)]), dissolve("T-NEW", 30.0), dissolve("T-NEW", 1.0).copy(audioOffset = -0.4, audioBlend = 1.0))
            for (settings in wanted) {
                add("TimelineEditing.setJoin", "$what, ${settings.style.name.lowercase()} of ${seconds(settings.duration)} s", tags, { today(LegacyTimelineEditing.setJoin(state, join, settings)) }, { wrapped(TimelineEditing.setJoin(state, join, settings)) })
            }
            add("TimelineEditing.setJoin", "$what, removed", tags, { today(LegacyTimelineEditing.setJoin(state, join, null)) }, { wrapped(TimelineEditing.setJoin(state, join, null)) })
            TimelineEditing.joinSettings(clips, join)?.let { same ->
                add("TimelineEditing.setJoin", "$what, the same settings again", tags, { today(LegacyTimelineEditing.setJoin(state, join, same)) }, { wrapped(TimelineEditing.setJoin(state, join, same)) })
            }
        }
        for (index in 0 until count - 1) for (duration in listOf(0.5, 2.0, null)) {
            val style = TransitionStyle.entries[rng.below(TransitionStyle.entries.size)]
            add(
                "TimelineEditing.setTransition", "after clip ${index + 1}, ${duration?.let { "${style.name.lowercase()} of ${seconds(it)} s" } ?: "removed"}", on(state, index),
                { today(LegacyTimelineEditing.setTransition(state, index, duration, style)) }, { wrapped(TimelineEditing.setTransition(state, index, duration, style)) },
            )
        }
    }

    private fun Maker.recipes() {
        fun recipe(function: String, label: String, tags: Set<String>, legacy: (EditState) -> EditState?, edit: (EditState) -> EditState?) = add(function, label, tags, { today(legacy(state)) }, { wrapped(edit(state)) })
        for (index in indices()) {
            val clip = clips[index]
            val tags = on(state, index)
            val title = TextOverlay(id = "TITLE-NEW", text = "New", start = clip.start, end = minOf(clip.end, clip.start + 2.0))
            recipe("TimelineEditing.setText", "a title on clip ${index + 1}", tags, { LegacyTimelineEditing.setText(it, index, title) }) { TimelineEditing.setText(it, index, title) }
            clip.texts?.firstOrNull()?.let { text -> recipe("TimelineEditing.removeText", "from clip ${index + 1}", tags, { LegacyTimelineEditing.removeText(it, index, text.id) }) { TimelineEditing.removeText(it, index, text.id) } }
            recipe("TimelineEditing.setColor", "clip ${index + 1}", tags, { LegacyTimelineEditing.setColor(it, index, ColorSettings(id = "COLOR-NEW", exposure = 0.3)) }) { TimelineEditing.setColor(it, index, ColorSettings(id = "COLOR-NEW", exposure = 0.3)) }
            recipe("TimelineEditing.setCrop", "clip ${index + 1}", tags, { LegacyTimelineEditing.setCrop(it, index, CropSettings(id = "CROP-NEW", zoom = 1.4)) }) { TimelineEditing.setCrop(it, index, CropSettings(id = "CROP-NEW", zoom = 1.4)) }
        }
        repeat(samples) {
            val from = time()
            recipe("TimelineEditing.placeTitle", "over ${seconds(from)}–${seconds(from + 3.0)}", at(state, from), { LegacyTimelineEditing.placeTitle(it, TextOverlay(id = "TITLE-PLACED", text = "Placed"), from..from + 3.0) }) { TimelineEditing.placeTitle(it, TextOverlay(id = "TITLE-PLACED", text = "Placed"), from..from + 3.0) }
        }
    }

    private fun Maker.clipSound() {
        for (index in indices()) {
            val length = clips[index].duration
            val sounds = listOf(
                "6 dB down" to ClipAudio(volumeDB = -6.0), "muted" to ClipAudio(muted = true), "as recorded" to ClipAudio(),
                "fades of 0.3 s" to ClipAudio(fadeIn = 0.3, fadeOut = 0.3), "noise reduction" to ClipAudio(denoise = 0.5),
                "a fade in of ${seconds(length * 0.8)} s, four fifths of the clip" to ClipAudio(fadeIn = length * 0.8),
            )
            for ((what, audio) in sounds) {
                val tags = on(state, index) + (if (audio.fadeIn > length / 2) setOf(Tag.LONG_FADE) else emptySet())
                add("TimelineEditing.setAudio", "clip ${index + 1}, $what", tags, { today(LegacyTimelineEditing.setAudio(state, index, audio)) }, { wrapped(TimelineEditing.setAudio(state, index, audio)) })
                add("AudioUnlinking.setSound", "clip ${index + 1}, $what", tags, { today(LegacyAudioUnlinking.setSound(state, index, audio)) }, { wrapped(AudioUnlinking.setSound(state, index, audio)) })
            }
        }
    }

    private fun Maker.soundItems() {
        val sound = subject.sources.firstOrNull { it.audioOnly == true }
        if (sound != null) for (at in listOf(0.0, RangeEditing.quantize(time()), time())) {
            add("TimelineEditing.addAudio", "${seconds(sound.duration)} s of sound at ${seconds(at)}", setOfNotNull(Tag.SOUND_ITEMS.takeIf { items.isNotEmpty() }), { today(LegacyTimelineEditing.addAudio(state, sound.id, sound.duration, at)) }, { wrapped(TimelineEditing.addAudio(state, sound.id, sound.duration, at)) })
        }
        for (item in items.shuffledBy(rng).take(maxOf(2, samples))) {
            val tags = onItem(item) + (if (clips.any { it.id == item.id }) setOf(Tag.UNLINKED) else emptySet())
            val name = if (clips.any { it.id == item.id }) "the unlinked sound of clip ${clips.indexOfFirst { it.id == item.id } + 1}" else "the item at ${seconds(item.start)}"
            for (start in listOf(RangeEditing.quantize(time()), item.start + 0.5, 0.0)) {
                add("TimelineEditing.moveAudio", "$name to ${seconds(start)}", tags, { today(LegacyTimelineEditing.moveAudio(state, item.id, start)) }, { wrapped(TimelineEditing.moveAudio(state, item.id, start)) })
            }
            for ((time, leading) in listOf(item.start + item.duration * 0.3 to true, item.start + item.duration * 0.6 to false, item.start + 0.05 to true)) {
                add(
                    "TimelineEditing.trimAudio", "$name, ${if (leading) "its start" else "its end"} to ${seconds(time)}", tags,
                    { today(LegacyTimelineEditing.trimAudio(state, item.id, time, leading)) }, { wrapped(TimelineEditing.trimAudio(state, item.id, time, leading)) },
                )
            }
            val sounds = listOf("9 dB down with fades of 0.5 s" to ClipAudio(volumeDB = -9.0, fadeIn = 0.5, fadeOut = 0.5), "muted" to ClipAudio(muted = true), "a fade out of four fifths of it" to ClipAudio(fadeOut = item.duration * 0.8))
            for ((what, audio) in sounds) {
                val more = tags + (if (audio.fadeOut > item.duration / 2) setOf(Tag.LONG_FADE) else emptySet())
                add("TimelineEditing.setAudioItemSound", "$name, $what", more, { today(LegacyTimelineEditing.setAudioItemSound(state, item.id, audio)) }, { wrapped(TimelineEditing.setAudioItemSound(state, item.id, audio)) })
            }
            add("TimelineEditing.removeAudio", name, tags, { today(LegacyTimelineEditing.removeAudio(state, item.id)) }, { wrapped(TimelineEditing.removeAudio(state, item.id)) })
        }
    }

    private fun <T> List<T>.shuffledBy(rng: TrackFixtures.Random): List<T> = map { it to rng.next() }.sortedBy { it.second }.map { it.first }

    private fun Maker.scopes(): List<EditScope> {
        val result = ArrayList<EditScope>()
        repeat(samples) {
            val from = time()
            result += EditScope.Range(TimeRange(from, from + rng.next(0.2, 4.0)))
        }
        val from = RangeEditing.quantize(time())
        result += EditScope.Range(TimeRange(from, from + 2.0))
        transitions.firstOrNull()?.let { result += EditScope.Range(TimeRange(maxOf(0.0, it.range.start - 1.0), (it.range.start + it.range.endInclusive) / 2)) }
        val index = index()
        result += EditScope.Range(TimeRange(begins(index), begins(index) + clips[index].duration))
        result += EditScope.Range(TimeRange(begins(index) + 0.02, begins(index) + clips[index].duration * 0.5))
        result += EditScope.Range(TimeRange(begins(index) + clips[index].duration * 0.25, begins(index) + clips[index].duration * 0.75), EditTargets(clips = setOf(clips[index].id)))
        items.firstOrNull()?.let { result += EditScope.Range(TimeRange(it.start + it.duration * 0.25, it.start + it.duration * 0.5), EditTargets(audio = setOf(it.id))) }
        for (clip in indices()) result += EditScope.Selected(EditTargets(clips = setOf(clips[clip].id)))
        if (clips.size > 2) result += EditScope.Selected(EditTargets(clips = setOf(clips[0].id, clips[2].id)))
        items.firstOrNull()?.let { result += EditScope.Selected(EditTargets(audio = setOf(it.id))) }
        result += EditScope.Whole
        return result
    }

    private fun Maker.ranges() {
        // What was copied, as an edit of its own that begins at zero. A fragment's sound is placed from where its first clip begins, which an opening that holds time puts later than the fragment's start.
        fun copied(fragment: ClipFragment): EditState {
            val lead = if (fragment.clips.isEmpty()) 0.0 else TimelineEditing.clipStart(fragment.clips, 0)
            return nameless(EditState(fragment.clips, audio = fragment.audio.map { it.copy(start = it.start + lead) }.ifEmpty { null }, subtitles = fragment.subtitles))
        }
        for (scope in scopes()) {
            val tags = about(state, scope)
            val name = label(scope, state)
            val playhead = time()
            val moved = tags + setOfNotNull(Tag.PLAYHEAD_HELD.takeIf { held(state, playhead) })
            add(
                "RangeEditing.delete", "$name, the playhead at ${seconds(playhead)}", moved,
                { LegacyRangeEditing.delete(state, scope, playhead)?.let { today(it.state, "playhead" to it.position) } },
                { RangeEditing.delete(state, scope, playhead)?.let { wrapped(it.state, "playhead" to it.position) } },
            )
            add("RangeEditing.copy", name, tags, { LegacyRangeEditing.copy(state, scope)?.let { today(copied(it)) } }, { RangeEditing.copy(state, scope)?.let { wrapped(copied(it)) } })
            add(
                "RangeEditing.cut", "$name, the playhead at ${seconds(playhead)}", moved,
                { LegacyRangeEditing.cut(state, scope, playhead)?.let { today(it.state, "playhead" to it.position) } },
                { RangeEditing.cut(state, scope, playhead)?.let { wrapped(it.state, "playhead" to it.position) } },
            )
            for (at in listOf(time(), 0.0, state.duration)) {
                add(
                    "RangeEditing.paste", "a copy of $name, at ${seconds(at)}", tags + at(state, at),
                    { LegacyRangeEditing.copy(state, scope)?.let { fragment -> LegacyRangeEditing.paste(state, fragment, at)?.let { today(it.state, "begins at" to it.start) } } },
                    { RangeEditing.copy(state, scope)?.let { fragment -> RangeEditing.paste(state, fragment, at)?.let { wrapped(it.state, "begins at" to it.start) } } },
                )
            }
            add(
                "RangeEditing.scoped", name, tags,
                { LegacyRangeEditing.scoped(state, scope).let { scoped -> today(scoped.state, "clips a tool applies to" to scoped.clips.map { id -> scoped.state.clips.indexOfFirst { it.id == id } + 1 }) } },
                { RangeEditing.scoped(state, scope).let { scoped -> wrapped(scoped.state, "clips a tool applies to" to scoped.clips.map { id -> scoped.state.clips.indexOfFirst { it.id == id } + 1 }) } },
            )
        }
    }

    private fun Maker.captions() {
        val tags = LinkedHashSet<String>()
        clips.indices.forEach { index -> on(state, index).let { if (Tag.UNLINKED in it) tags += Tag.UNLINKED; if (Tag.MUTED in it) tags += Tag.MUTED; if (Tag.SPEED in it) tags += Tag.SPEED } }
        if (items.isNotEmpty()) tags += Tag.SOUND_ITEMS
        val spoken = subject.sources.filter { it.audioOnly != true }.associate { source ->
            source.id to "what is said in this take".split(" ").mapIndexed { index, word -> SubtitleWord("W-${source.id}-$index", "P-${source.id}", word, 0.3 + index * source.duration / 8, 0.3 + (index + 0.7) * source.duration / 8) }
        }
        add("SubtitleEditing.install", "a transcription of every take", tags, { today(LegacySubtitleEditing.install(state, spoken, "en")) }, { wrapped(SubtitleEditing.install(state, spoken, "en")) })
        add("SubtitleEditing.cues", "the captions", tags, { value("captions", LegacySubtitleEditing.cues(state).map { listOf(it.text, it.start, it.end) }) }, { value("captions", SubtitleEditing.cues(state).map { listOf(it.text, it.start, it.end) }) })
        add("SubtitleEditing.transcript", "the text", tags, { value("transcript", LegacySubtitleEditing.transcript(state)) }, { value("transcript", SubtitleEditing.transcript(state)) })
        add("SubtitleEditing.groupingAvailable", "whether regrouping is allowed", tags, { value("allowed", LegacySubtitleEditing.groupingAvailable(state)) }, { value("allowed", SubtitleEditing.groupingAvailable(state)) })
        add(
            "SubtitleEditing.stretches", "what a transcription listens to", tags,
            { value("stretches", LegacySubtitleEditing.stretches(clips).map { listOf(it.sourceID, it.start, it.end) }) },
            { value("stretches", SubtitleEditing.stretches(clips).map { listOf(it.sourceID, it.start, it.end) }) },
        )
        if (state.subtitles == null) return
        add("SubtitleEditing.remove", "every caption", tags, { today(LegacySubtitleEditing.remove(state)) }, { wrapped(SubtitleEditing.remove(state)) })
        val restyled = state.subtitles.copy(style = SubtitleSettings.Style.Bold, size = 0.07)
        add("SubtitleEditing.restyle", "bold, larger", tags, { today(LegacySubtitleEditing.restyle(state, restyled)) }, { wrapped(SubtitleEditing.restyle(state, restyled)) })
        for (count in listOf(2, state.subtitles.maxWords, 12)) {
            add("SubtitleEditing.regroup", "$count words a caption", tags, { today(LegacySubtitleEditing.regroup(state, count)) }, { wrapped(SubtitleEditing.regroup(state, count)) })
        }
        val chosen = LegacySubtitleEditing.cues(state).shuffledBy(rng).take(samples)
        for (cue in chosen) {
            val index = clips.indexOfFirst { it.id == cue.audioID }
            val more = tags + on(state, index)
            add("SubtitleEditing.correct", "the caption \"${cue.text}\" of clip ${index + 1}", more, { today(LegacySubtitleEditing.correct(state, cue, "Typed again")) }, { wrapped(SubtitleEditing.correct(state, cue, "Typed again")) })
            add("SubtitleEditing.delete", "the caption \"${cue.text}\" of clip ${index + 1}", more, { today(LegacySubtitleEditing.delete(state, cue)) }, { wrapped(SubtitleEditing.delete(state, cue)) })
        }
    }

    private fun Maker.unlinking() {
        for (index in indices()) {
            val tags = on(state, index)
            add("AudioUnlinking.unlink", "clip ${index + 1}", tags, { today(LegacyAudioUnlinking.unlink(state, index)) }, { wrapped(AudioUnlinking.unlink(state, index)) })
            add("AudioUnlinking.relink", "clip ${index + 1}", tags, { today(LegacyAudioUnlinking.relink(state, index)) }, { wrapped(AudioUnlinking.relink(state, index)) })
            add("AudioUnlinking.restoreSync", "clip ${index + 1}", tags, { today(LegacyAudioUnlinking.restoreSync(state, index)) }, { wrapped(AudioUnlinking.restoreSync(state, index)) })
            add("AudioUnlinking.syncOffset", "clip ${index + 1}", tags, { value("seconds late", LegacyAudioUnlinking.syncOffset(state, index)) }, { value("seconds late", AudioUnlinking.syncOffset(state, index)) })
        }
    }

    /** What is asked of an edit without changing it: the questions stay questions about the sequence, and are compared so that they stay the answers they were. */
    private fun Maker.questions() {
        add("TimelineEditing.duration", "how long the movie is", emptySet(), { value("seconds", LegacyTimelineEditing.duration(clips)) }, { value("seconds", state.duration) })
        add("TimelineEditing.clipStart", "where each clip begins", emptySet(), { value("starts", clips.indices.map { LegacyTimelineEditing.clipStart(clips, it) }) }, { value("starts", clips.indices.map { TimelineEditing.clipStart(clips, it) }) })
        add("RangeEditing.joinTimes", "the start, every join and the end", emptySet(), { value("times", LegacyRangeEditing.joinTimes(clips)) }, { value("times", RangeEditing.joinTimes(clips)) })
        add("TimelineEditing.bookmarkTimes", "where the bookmarks are", emptySet(), { value("times", LegacyTimelineEditing.bookmarkTimes(clips)) }, { value("times", TimelineEditing.bookmarkTimes(clips)) })
        for (time in times()) {
            fun name(join: TransitionLayout.Join?) = join?.let { "${it.outgoing?.plus(1) ?: "–"} → ${it.incoming?.plus(1) ?: "–"}" }
            add("RangeEditing.transitionAt", "at ${seconds(time)}", at(state, time), { value("transition", name(LegacyRangeEditing.transitionAt(clips, time))) }, { value("transition", name(RangeEditing.transitionAt(clips, time))) })
        }
    }
}
