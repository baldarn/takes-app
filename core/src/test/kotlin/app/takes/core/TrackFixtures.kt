package app.takes.core

/**
 * Edits of the kinds a project holds today, built with the editing functions
 * the app calls, for the tests of the track timeline: each is converted,
 * checked and converted back.
 */
internal object TrackFixtures {
    val take = SourceMedia(id = uuid(9001), filename = "take.mp4", duration = 60.0)
    val second = SourceMedia(id = uuid(9002), filename = "second.mp4", duration = 45.0)
    val music = SourceMedia(id = uuid(9003), filename = "music.m4a", duration = 180.0, audioOnly = true)
    val sources = listOf(take, second, music)

    /**
     * A fixed id written as a UUID, as the app's own are. The last byte is
     * left alone: ids that differ only in its highest bits are each other's
     * derived ids ([TrackTimeline.counterpart]), which ids drawn at random never are.
     */
    fun uuid(number: Int): String = "00000000-0000-4000-8000-%010X00".format(number)

    fun clip(number: Int, source: SourceMedia, start: Double, end: Double) = TimelineClip(uuid(number), source.id, start, end)

    /** The same numbers on every run: the generator of the original's tests (Tests/TrackTimelineTests.swift:143-144). */
    class Random(private var state: Long = 21) {
        fun next(): Double {
            state = state * 6364136223846793005L + 1
            return (state ushr 11).toDouble() / (1L shl 53).toDouble()
        }

        fun next(from: Double, to: Double): Double = from + next() * (to - from)
        fun below(count: Int): Int = minOf(count - 1, (next() * count).toInt())
    }

    private val a = clip(1, take, 0.0, 12.5)
    private val b = clip(2, second, 0.0, 8.25)
    private val c = clip(3, take, 20.0, 31.0)

    /** Three takes one after another, as recorded. */
    fun plain() = EditState(listOf(a, b, c))

    private fun EditState.then(edit: (EditState) -> EditState?): EditState = edit(this) ?: error("The edit did not apply")

    /** Cuts, trims and a move at times that are on no grid. */
    fun unquantised(): EditState = plain()
        .then { TimelineEditing.split(it, 3.1234567) }
        .then { TimelineEditing.split(it, 17.000123) }
        .then { TimelineEditing.trim(it, 1, it.clips[1].start + 0.3337, it.clips[1].end - 0.2101, take.duration) }
        .then { TimelineEditing.move(it, 0, 2) }
        .then { TimelineEditing.split(it, 9.87654321) }

    private fun settings(style: TransitionStyle, duration: Double, next: String? = null) = TransitionSettings(
        nextClipID = next, style = style, duration = duration, hold = 0.4, audioOffset = -0.3, audioBlend = 1.0,
        stillDissolve = if (style == TransitionStyle.Still) 0.3 else null,
    )

    /** An opening, a transition between two clips, a plain cut and a close, all of [style], with sound timing of their own. */
    fun transitions(style: TransitionStyle): EditState {
        val clips = listOf(
            a.copy(openingTransition = settings(style, 0.7), transition = settings(style, 1.2, b.id)),
            b,
            c.copy(transition = settings(style, 0.7)),
        )
        return EditState(TimelineEditing.fitted(clips))
    }

    /** Transitions set through the editor's own function; the second asks for more of the short clip than the first left, so it is saved shortened. */
    fun shortened(): EditState = EditState(listOf(a, clip(4, second, 3.0, 6.0), c))
        .then { TimelineEditing.setJoin(it, 1, TransitionSettings(duration = 2.0)) }
        .then { TimelineEditing.setJoin(it, 2, TransitionSettings(style = TransitionStyle.FadeColor, duration = 4.0)) }

    /** An opening and a close that stayed on their clip when clips were put around it. */
    fun fades(): EditState {
        val middle = b.copy(openingTransition = settings(TransitionStyle.FadeColor, 0.6), transition = settings(TransitionStyle.FadeColor, 0.8))
        return EditState(TimelineEditing.fitted(listOf(a, middle, c)))
    }

    /** A clip at another speed, and the pieces left by removing pauses. */
    fun speed(): EditState = plain()
        .then { TimelineEditing.setSpeed(it, 0, 1.5) }
        .then { TimelineEditing.setSpeed(it, 2, 0.5) }
        .then { TimelineEditing.removeIntervals(it, 1, listOf(SourceInterval(1.013, 1.9871), SourceInterval(4.2, 5.31))) }

    /** Titles, bookmarks with and without a label, and captions that still match the footage. */
    fun captions(): EditState {
        val clips = listOf(
            a.copy(
                texts = listOf(TextOverlay(id = uuid(50), text = "Opening", start = 1.0, end = 4.0), TextOverlay.fresh().copy(id = uuid(51), text = "Designed", start = 5.0, end = 7.5)),
                bookmarks = listOf(Bookmark(uuid(60), 2.25), Bookmark(uuid(61), 9.0, "Retake")),
                subtitleWords = listOf(SubtitleWord(uuid(70), uuid(80), "Hello", 0.5, 0.9), SubtitleWord(uuid(71), uuid(80), "there", 0.95, 1.4)),
            ),
            b.copy(subtitleWords = listOf(SubtitleWord(uuid(72), uuid(81), "Second", 3.0, 3.6))),
            c.copy(bookmarks = listOf(Bookmark(uuid(62), 25.5))),
        )
        return EditState(clips, subtitles = SubtitleSettings(id = uuid(90), timingFingerprint = SubtitleEditing.fingerprint(clips)))
    }

    /** Color and framing on the clips. */
    fun look(): EditState = EditState(
        listOf(a.copy(color = ColorSettings(id = uuid(40), amount = 0.8, warmth = 0.2)), b.copy(crop = CropSettings(id = uuid(41), x = 0.4, zoom = 1.6)), c),
    )

    /** A clip's own level, mute, fades and noise reduction. */
    fun clipSound(): EditState = plain()
        .then { TimelineEditing.setAudio(it, 0, ClipAudio(volumeDB = -6.0, fadeIn = 1.5, fadeOut = 0.75)) }
        .then { TimelineEditing.setAudio(it, 1, ClipAudio(muted = true)) }
        .then { TimelineEditing.setAudio(it, 2, ClipAudio(volumeDB = 3.0, denoise = 0.6)) }

    /**
     * Music on both tracks: a piece with fades and a level, a piece cut in
     * two that still meets itself, one under them on the second track, and
     * one that runs past the end of the picture.
     */
    fun music(): EditState {
        var state = plain().then { TimelineEditing.addAudio(it, music.id, 20.0, 2.0000013) }
        val first = state.audio!![0]
        state = state.then { TimelineEditing.setAudioItemSound(it, first.id, ClipAudio(volumeDB = -12.0, fadeIn = 2.0, fadeOut = 3.5)) }
            .then { TimelineEditing.trimAudio(it, first.id, 14.7000004, leading = false) }
        val cut = state.audio!![0]
        // The rest of the same piece, beginning where the first part ends.
        val rest = AudioItem(uuid(30), music.id, cut.end, cut.sourceEnd, 20.0)
        val under = AudioItem(uuid(31), music.id, 4.123456789, 100.0, 109.5, track = 1)
        val past = AudioItem(uuid(32), music.id, 26.0, 30.0, 45.0, audio = ClipAudio(fadeOut = 1.0))
        return state.copy(audio = (state.audio!! + rest + under + past).sortedBy { it.start })
    }

    /** The sound of the middle clip unlinked and moved a little late. */
    fun unlinked(): EditState = captions()
        .then { AudioUnlinking.unlink(it, 1) }
        .then { state -> TimelineEditing.moveAudio(state, state.clips[1].id, state.audio!![0].start + 0.25) }

    /** [count] clips of uneven lengths with a dissolve at every seventh cut and a piece of music every thirtieth. */
    fun long(count: Int): EditState {
        val random = Random(7)
        var clips = List(count) { index ->
            val start = random.next(0.0, 30.0)
            TimelineClip(uuid(100_000 + index), if (index % 2 == 0) take.id else second.id, start, start + random.next(1.0, 9.0))
        }
        clips = clips.mapIndexed { index, clip ->
            if (index % 7 == 3 && index + 1 < count) clip.copy(transition = TransitionSettings(id = uuid(300_000 + index), nextClipID = clips[index + 1].id, duration = 0.4)) else clip
        }
        clips = TimelineEditing.fitted(clips)
        val audio = (0 until count step 30).map { index ->
            AudioItem(uuid(200_000 + index), music.id, TimelineEditing.clipStart(clips, index), 0.0, random.next(5.0, 40.0), track = (index / 30) % 2)
        }
        return EditState(clips, audio = audio.ifEmpty { null })
    }

    /** Every kind of edit above, by name. */
    val all: Map<String, EditState> by lazy {
        linkedMapOf(
            "empty" to EditState(),
            "one take" to EditState(listOf(a)),
            "plain" to plain(),
            "unquantised" to unquantised(),
            "shortened" to shortened(),
            "fades" to fades(),
            "speed" to speed(),
            "captions" to captions(),
            "look" to look(),
            "clip sound" to clipSound(),
            "music" to music(),
            "unlinked" to unlinked(),
            "long" to long(240),
        ) + TransitionStyle.entries.associate { "transitions ${it.name}" to transitions(it) }
    }
}
