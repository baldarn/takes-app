package app.takes.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * What the preview and the export draw and play, and when: everything the
 * two decide from an edit, as plain values. The app makes players and
 * compositions from a plan and decides nothing itself; two edits that give
 * equal plans look and sound the same.
 *
 * It holds the arrangement both renderers use today:
 *
 *  - [pictures]: each clip as a piece of footage with its place, speed, color
 *    and crop, and what becomes of its own sound;
 *  - [decks]: the two players of the preview, each with the clips of every
 *    other chain as its playlist;
 *  - [sequences]: the picture sequences of an export, one when the clips
 *    follow one another and two, drawn from a frame holding both, when clips
 *    play at once or time lies between them, with their gaps;
 *  - [transitions], [freezes] and [endings]: what the transition effect draws
 *    and which frames it has to keep;
 *  - [titles] and [captions]: what is written over the finished frame;
 *  - [soundtracks] and [cleaned]: the sequences of sound alone, for the two
 *    sound tracks and for the cleaned sound of clips with noise reduction.
 *
 * Levels are functions of time ([clipLevel], [soundLevel]) and the picture of
 * a moment is [moment]; both answer from the same edit the plan was made of.
 *
 * An export draws its picture a section at a time, from a clock, with decks
 * that carry no sound. What it takes for that is worked out from the fields
 * above and is not a field itself: the part of the decks a section is drawn
 * from ([section], [stretch]), the sound of every clip on sequences of sound
 * alone ([voices]) and the level of each run of sound sample by sample
 * ([clipEnvelope], [soundEnvelope], [envelopes]).
 */
data class RenderPlan(
    val clips: List<TimelineClip>,
    val sounds: List<AudioItem>,
    /** The length of the movie: that of its picture. */
    val duration: Double,
    val pictures: List<Picture>,
    val chains: List<TimelineEditing.Chain>,
    val decks: List<Deck>,
    val sequences: List<Sequence>,
    val transitions: List<TransitionLayout.Join>,
    val freezes: List<Freeze>,
    val endings: List<Ending>,
    val titles: List<TitleSpan>,
    /** How the captions look; null when the movie has none. */
    val subtitles: SubtitleSettings?,
    val captions: List<SubtitleCue>,
    val soundtracks: List<Sequence>,
    val cleaned: List<Sequence>,
) {
    /** One clip as something to play. Times of the movie are in seconds; the cuts in the footage also in the microseconds a player is given. */
    data class Picture(
        val index: Int,
        val id: String,
        val sourceID: String,
        /** Where it plays in the movie, and for how long. */
        val start: Double,
        val duration: Double,
        /** The footage it plays, in source seconds. */
        val sourceStart: Double,
        val sourceEnd: Double,
        /** The same cuts as a player takes them: never later than the second they stand for ([cutMicros]). */
        val sourceStartUs: Long,
        val sourceEndUs: Long,
        /** How fast it plays, picture and sound, with the pitch kept. */
        val rate: Double,
        /** The chain it belongs to and the deck that shows it. */
        val chain: Int,
        val deck: Int,
        val color: ColorSettings?,
        val crop: CropSettings?,
        /** Whether its sound is shaped over time ([clipLevel]): it has sound settings of its own, or a transition at one of its ends. */
        val shaped: Boolean,
        /** Whether its recorded sound is left out because its cleaned sound plays instead, from a sequence of [RenderPlan.cleaned]. */
        val soundRemoved: Boolean,
    ) {
        val end: Double get() = start + duration
    }

    /** One player of the preview: the clips it holds, in order, and the chains they form. */
    data class Deck(val number: Int, val clips: List<Int>, val chains: List<Int>)

    /** What a sequence holds, one after another. */
    sealed interface Entry {
        /** Time with nothing in it: black, and silence. [start] is where it begins in the movie. */
        data class Gap(val start: Double, val micros: Long) : Entry

        /** The clip at [index] of [pictures]: its picture and sound in a picture sequence, its cleaned sound in a cleaned one. */
        data class Clip(val index: Int) : Entry

        /**
         * A sound of the sound tracks, from [start] for [seconds]: the part of
         * it the movie plays, which ends with the picture. The cuts in its file
         * are in microseconds, to the nearest.
         */
        data class Sound(val item: AudioItem, val start: Double, val seconds: Double, val sourceStartUs: Long, val sourceEndUs: Long) : Entry
    }

    enum class Role { Picture, Soundtrack, Cleaned, Voice }

    /** One sequence of an export. [number] is the deck of a picture sequence, the track of a soundtrack, the deck whose clips a cleaned one or one of voices carries. */
    data class Sequence(val role: Role, val number: Int, val entries: List<Entry>)

    /**
     * A frame a still holds. From [from] to [until] the clip at [clip] shows
     * the frame it showed at [shown], which is [offset] seconds into it and
     * second [sourceTime] of its footage. An export keeps the last frame
     * drawn before [from]; the preview seeks to it. [sounding] says the clip
     * plays on under the freeze for its sound, so the preview has to draw the
     * frame over a deck that is moving.
     */
    data class Freeze(
        val join: Int, val clip: Int, val deck: Int, val from: Double, val until: Double,
        val shown: Double, val offset: Double, val sourceTime: Double, val sounding: Boolean,
    )

    /** The end of a chain on [deck] at second [at]: a sequence may end a frame early, and the last frame that came is kept for a transition still showing it. */
    data class Ending(val deck: Int, val at: Double)

    /** A title over the seconds of the movie its clip shows it. Pieces of one clip carry the same title one after another. */
    data class TitleSpan(val clip: Int, val title: TextOverlay, val start: Double, val end: Double)

    /** What a deck holds in a stretch of the movie, one after another. */
    sealed interface Part {
        /** Black from [start] for [micros] microseconds: no clip plays on the deck. */
        data class Black(val start: Double, val micros: Long) : Part

        /**
         * The clip at [index] of [pictures] from second [start] to second [end]
         * of the movie: all of it, or the part of it the stretch holds. The
         * cuts in its footage are in microseconds, never later than the second
         * they stand for ([cutMicros]), and at least one apart.
         */
        data class Footage(val index: Int, val start: Double, val end: Double, val sourceStartUs: Long, val sourceEndUs: Long) : Part
    }

    /** The seconds [from] to [to] of the movie as the decks hold them: each deck filled from one end to the other. */
    data class Stretch(val from: Double, val to: Double, val decks: List<List<Part>>)

    /**
     * The picture of a section of an export: [count] frames of the movie from
     * frame [first]. It is drawn from frame [opens] up to frame [closes], a
     * little more than it holds, and the frames outside it are left out;
     * [stretch] is what the decks hold between those two.
     */
    data class Section(val first: Int, val count: Int, val opens: Int, val closes: Int, val stretch: Stretch)

    // MARK: The picture of a moment

    /** The deck showing each clip. */
    val deckOf: List<Int> get() = pictures.map { it.deck }

    /** Whether the picture sequences are two, laid one above the other in a frame twice as tall for the transition effect. */
    val stacked: Boolean get() = sequences.size > 1

    /** Whether the transition effect draws the frames at all: there are two sequences, or a transition in the only one. */
    val drawsTransitions: Boolean get() = stacked || transitions.isNotEmpty()

    /** Whether a frame [width] by [height] can be drawn on a GPU whose largest texture is [largestSide] pixels a side. */
    fun fits(width: Int, height: Int, largestSide: Int): Boolean = !stacked || (width <= largestSide && height * sequences.size <= largestSide)

    /** What the movie shows at [time], for both renderers ([TransitionScene.moment]). */
    fun moment(
        time: Double, canvas: Size = Size(1920.0, 1080.0),
        frames: (TransitionLayout.Join) -> Double = { TransitionScene.FRAMES_PER_SECOND },
    ): TransitionScene.Moment = TransitionScene.moment(clips, time, canvas, deckOf, frames)

    /** The frames per second of the movie, which are the same during every transition ([TransitionScene.frameRate]). */
    fun frameRate(footage: List<Double?>, ceiling: Int): Int = TransitionScene.frameRate(footage, ceiling)

    /** The titles showing at [time], each with its opacity. */
    fun titlesAt(time: Double, frames: (TransitionLayout.Join) -> Double = { TransitionScene.FRAMES_PER_SECOND }): List<Pair<TextOverlay, Double>> =
        TimelineEditing.titlesAt(clips, time, frames)

    /** The captions showing at [time]. */
    fun captionsAt(time: Double): List<SubtitleCue> = SubtitleEditing.active(captions, time)

    /** Whether any clip carries a title, so that titles are drawn over the movie at all. */
    val titled: Boolean get() = clips.any { !it.texts.isNullOrEmpty() }

    private val titleRuns by lazy { TitleMotionRanges(clips) }

    /** The titles to draw at [time], each once, with its opacity and the pose its motion gives it ([TitleMotionRanges.shown]). */
    fun titlesShown(time: Double, frames: (TransitionLayout.Join) -> Double = { TransitionScene.FRAMES_PER_SECOND }): List<TitleMotionRanges.Shown> =
        titleRuns.shown(clips, time, frames)

    /** The clip whose own time on [deck] holds [time]; null where the deck plays none. */
    fun onDeck(deck: Int, time: Double): Picture? = pictures.lastOrNull { it.deck == deck && time >= it.start && time < it.end }

    /** The still [deck] has to keep showing at [time]: the frame of a freeze, of a clip that may be over. */
    fun held(deck: Int, time: Double): TransitionScene.Source.Held? =
        moment(time).let { listOf(it.first, it.second) }.filterIsInstance<TransitionScene.Source.Held>().firstOrNull { it.deck == deck }

    /** The chain that begins on [deck] after [time], where the deck waits when it plays nothing; null when none is to come. */
    fun coming(deck: Int, time: Double): Int? = chains.indices.firstOrNull { it % DECKS == deck && chains[it].start > time }

    /** The chain whose deck is the clock of the preview at [time]: the one that began last; null where no clip plays. */
    fun leading(time: Double): Int? = chains.indices.lastOrNull { time >= chains[it].start && time < chains[it].end }

    /** The clip [deck] is playing at [time], and its chain; null where the deck plays nothing. */
    fun playing(deck: Int, time: Double): Pair<Int, Int>? {
        val chain = chains.indices.lastOrNull { it % DECKS == deck && time >= chains[it].start && time < chains[it].end } ?: return null
        val clip = (chains[chain].first..chains[chain].last).lastOrNull { pictures[it].start <= time } ?: chains[chain].first
        return chain to clip
    }

    // MARK: Sound

    /** How loud the clip at [index] is [offset] seconds into it: its own level and fades, and what its transitions leave of it. */
    fun clipLevel(index: Int, offset: Double): Double = TimelineEditing.soundLevel(clips, index, offset)

    /** How loud a sound of the sound tracks is [offset] seconds into it. */
    fun soundLevel(item: AudioItem, offset: Double): Double = TimelineEditing.soundLevel(item, offset)

    /** How loud [deck] is at [time] in the preview, where a player has one volume: the level of the clip it plays, 0 where it plays none. */
    fun deckLevel(deck: Int, time: Double): Double {
        val clip = onDeck(deck, time) ?: return 0.0
        return clipLevel(clip.index, time - clip.start)
    }

    /** How loud sound track [track] is at [time] in the preview. */
    fun trackLevel(track: Int, time: Double): Double = TimelineEditing.audioAt(sounds, track, time)?.let { soundLevel(it, time - it.start) } ?: 0.0

    /** The sound the preview's player of [track] holds at [time]: the one playing, or the next to come. */
    fun cued(track: Int, time: Double): AudioItem? =
        TimelineEditing.audioAt(sounds, track, time) ?: sounds.filter { it.track == track && it.start > time }.minByOrNull { it.start }

    // MARK: The sound of an export

    /**
     * The sound of the clips in an export, on sequences of sound alone: the
     * decks carry the picture and no sound. [voiced] says whether a recording
     * has sound. A clip is heard when it has, or when its cleaned sound plays
     * in its place ([Picture.soundRemoved]); each plays at its own time, and
     * the chains alternate between two sequences as they do between the decks,
     * since clips that dissolve into each other sound at once.
     */
    fun voices(voiced: (sourceID: String) -> Boolean): List<Sequence> = (0 until DECKS).mapNotNull { deck ->
        val heard = pictures.filter { it.chain % DECKS == deck && (it.soundRemoved || voiced(it.sourceID)) }
        if (heard.isEmpty()) return@mapNotNull null
        Sequence(Role.Voice, deck, spaced(heard, duration, { it.start }, { it.start + it.duration }) { listOf(Entry.Clip(it.index)) })
    }

    /**
     * The level of the sound of the clip at [index] sample by sample, on the
     * movie's clock, with the few milliseconds of fade at a cut
     * ([SoundEnvelope.clip]); null for a muted clip, which is silent.
     */
    fun clipEnvelope(index: Int): AudioEnvelopeSegment? = SoundEnvelope.clip(clips, index)

    /** The same for a sound of the sound tracks, which ends with the picture ([SoundEnvelope.item]). */
    fun soundEnvelope(item: AudioItem): AudioEnvelopeSegment? = SoundEnvelope.item(sounds, item, duration)

    /** Every run of sound of the movie, the clips first: what the loudest sample of its mix is measured through ([SoundEnvelope.segments]). */
    fun envelopes(): List<AudioEnvelopeSegment> = clips.indices.mapNotNull(::clipEnvelope) + sounds.mapNotNull(::soundEnvelope)

    // MARK: A part of the movie

    /**
     * What the decks hold from second [from] to second [to] of the movie.
     * Chains alternate between the decks; a clip that lies partly in the
     * stretch is cut where the stretch cuts it, and a part of one shorter
     * than [SHORTEST_PART] is left out. Where a deck has no clip it is black.
     */
    fun stretch(from: Double, to: Double): Stretch {
        val decks = List(sequences.size) { mutableListOf<Part>() }
        val filled = DoubleArray(decks.size) { from }
        fun black(deck: Int, until: Double) {
            val micros = ((until - filled[deck]) * 1e6).roundToLong()
            if (micros > 0) decks[deck] += Part.Black(filled[deck], micros)
            filled[deck] = maxOf(filled[deck], until)
        }
        chains.forEachIndexed { number, chain ->
            val deck = number % decks.size
            for (index in chain.first..chain.last) {
                val picture = pictures[index]
                val begins = picture.start
                val ends = begins + picture.duration
                val starts = maxOf(begins, from)
                val stops = minOf(ends, to)
                if (stops - starts < SHORTEST_PART) continue
                black(deck, starts)
                // The whole clip is cut where the edit cuts it; a part of it where the stretch does.
                val first = if (starts <= begins) picture.sourceStartUs else cutMicros(picture.sourceStart + (starts - begins) * picture.rate)
                val last = if (stops >= ends) picture.sourceEndUs else cutMicros(picture.sourceStart + (stops - begins) * picture.rate)
                decks[deck] += Part.Footage(index, starts, stops, first, maxOf(first + 1, last))
                filled[deck] = stops
            }
        }
        for (deck in decks.indices) black(deck, to)
        return Stretch(from, to, decks)
    }

    /**
     * The first frame to draw for a section that begins at frame [first] of
     * a movie of [rate] frames a second: [SECTION_MARGIN] before it, so that
     * the frame nearest to the section's first one may be one that began
     * before it and a frame kept for a transition is among those drawn. A
     * section that begins inside a freeze is drawn from before the frame the
     * freeze holds, which the deck does not give again.
     */
    private fun lead(first: Int, rate: Int): Int {
        val begins = first.toDouble() / rate
        val held = freezes.filter { it.shown - SECTION_MARGIN < begins && begins < it.until + SECTION_MARGIN }.minOfOrNull { it.shown }
        val from = minOf(begins, held ?: begins) - SECTION_MARGIN
        return maxOf(0, minOf(first, floor(from * rate).toInt()))
    }

    /**
     * The section of [count] frames from frame [first], of a movie of [rate]
     * frames a second. The movie has a whole number of frames
     * ([ExportSections.pictures]), so its last one may begin where its clips
     * have ended: a section that reaches it is drawn to the end of the movie.
     */
    fun section(first: Int, count: Int, rate: Int): Section {
        val frames = ExportSections.pictures(duration, rate)
        val opens = lead(first, rate)
        val closes = minOf(frames, first + count + ceil(SECTION_MARGIN * rate).toInt())
        return Section(first, count, opens, closes, stretch(opens.toDouble() / rate, if (closes == frames) duration else closes.toDouble() / rate))
    }

    companion object {
        /** How much of the movie is drawn before and after a section of an export, in seconds. */
        const val SECTION_MARGIN = 0.5

        /** A part of a clip shorter than this many seconds is left out of a stretch: it holds no frame. */
        const val SHORTEST_PART = 0.000002

        /** The players of the preview, and the most picture sequences of an export. */
        const val DECKS = 2

        /** A sound beginning later than this before the end of the picture is left out of the movie. */
        const val SOUND_MARGIN = 0.01

        /** A chain beginning or ending nearer than this to the ends of the movie needs no gap around it. */
        const val GAP_MARGIN = 0.0005

        /**
         * The microsecond a clip is cut at, for a source second: never later
         * than it. A file gives each frame its time rounded down to the
         * microsecond; a cut rounded to the nearest one would fall just after
         * its frame one time in three.
         */
        fun cutMicros(seconds: Double): Long = floor(seconds * 1e6).toLong()

        /**
         * Where a clip's own picture lies in a frame of [canvas] pixels, from
         * the top left: all of it when the clip is cropped or the size of its
         * footage is not known, else the footage fitted inside it.
         */
        fun content(canvas: Size, source: Size?, cropped: Boolean): Rect {
            if (cropped || source == null) return Rect(0.0, 0.0, canvas.width, canvas.height)
            val scale = minOf(canvas.width / source.width, canvas.height / source.height)
            return Rect((canvas.width - source.width * scale) / 2, (canvas.height - source.height * scale) / 2, source.width * scale, source.height * scale)
        }

        /** The entries of a sequence that plays [pieces] at their times and nothing between them, up to [total]. */
        private fun <T> spaced(pieces: List<T>, total: Double, start: (T) -> Double, end: (T) -> Double, entry: (T) -> List<Entry>): List<Entry> {
            val entries = mutableListOf<Entry>()
            var filled = 0.0
            fun gap(until: Double) {
                val micros = ((until - filled) * 1e6).roundToLong()
                if (micros > 0) entries += Entry.Gap(filled, micros)
            }
            for (piece in pieces) {
                gap(start(piece))
                entries += entry(piece)
                filled = end(piece)
            }
            gap(total)
            return entries
        }

        /**
         * The plan of [state]. [cleaned] are the clips whose cleaned sound is
         * at hand; null takes every clip that asks for noise reduction. A
         * clip that asks and is not among them plays its recorded sound.
         */
        fun of(state: EditState, cleaned: Set<Int>? = null): RenderPlan {
            val clips = state.clips
            val sounds = state.audio.orEmpty()
            val layout = TimelineEditing.layout(clips)
            val total = layout.duration
            val chains = TimelineEditing.chains(clips)
            val deckOf = TransitionScene.decks(clips)
            val chainOf = MutableList(clips.size) { 0 }
            chains.forEachIndexed { chain, run -> for (index in run.first..run.last) chainOf[index] = chain }
            val clean = clips.indices.filter { (clips[it].audio?.denoise ?: 0.0) > 0 && (cleaned == null || it in cleaned) }.toSet()

            val pictures = clips.mapIndexed { index, clip ->
                Picture(
                    index, clip.id, clip.sourceID, layout.placements[index].start, clip.duration,
                    clip.start, clip.end, cutMicros(clip.start), cutMicros(clip.end), clip.rate, chainOf[index], deckOf[index], clip.color, clip.crop,
                    shaped = clip.audio != null || TimelineEditing.entering(clips, index) != null || TimelineEditing.leaving(clips, index) != null,
                    soundRemoved = index in clean,
                )
            }
            val decks = List(DECKS) { deck ->
                val own = chains.indices.filter { it % DECKS == deck }
                Deck(deck, own.flatMap { chains[it].first..chains[it].last }, own)
            }
            fun run(chain: TimelineEditing.Chain): List<Entry> = (chain.first..chain.last).map { Entry.Clip(it) }
            val sequences = when {
                chains.isEmpty() -> emptyList()
                chains.size == 1 -> {
                    val chain = chains[0]
                    // A freeze before the first clip or after the last one is time with no clip in it.
                    val entries = if (chain.start < GAP_MARGIN && total - chain.end < GAP_MARGIN) run(chain) else buildList {
                        ((chain.start * 1e6).roundToLong()).takeIf { it > 0 }?.let { add(Entry.Gap(0.0, it)) }
                        addAll(run(chain))
                        (((total - chain.end) * 1e6).roundToLong()).takeIf { it > 0 }?.let { add(Entry.Gap(chain.end, it)) }
                    }
                    listOf(Sequence(Role.Picture, 0, entries))
                }
                // Chains alternate between two sequences, since neighbors play at once or with a pause between them.
                else -> List(DECKS) { deck ->
                    Sequence(Role.Picture, deck, spaced(chains.filterIndexed { index, _ -> index % DECKS == deck }, total, { it.start }, { it.end }, ::run))
                }
            }

            val joins = layout.all.filter { it.transition != null && TransitionLayout.longEnough(it.timing) }
            val freezes = joins.filter { it.outgoing != null }.mapNotNull { join ->
                val placement = layout.placements[join.outgoing!!]
                TransitionPicture.heldFrame(join.timing, join.origin, placement.start, placement.duration, TransitionScene.FRAMES_PER_SECOND)?.let { held ->
                    Freeze(
                        join.index, placement.index, deckOf[placement.index], held.from, held.until, held.shown, held.offset,
                        clips[placement.index].sourceTime(held.offset), sounding = placement.end > held.from + 0.001,
                    )
                }
            }
            val endings = chains.mapIndexed { index, chain -> Ending(index % DECKS, chain.end) }

            val titles = clips.flatMapIndexed { index, clip ->
                clip.texts.orEmpty().mapNotNull { title -> TimelineEditing.titleRange(clips, index, title)?.let { TitleSpan(index, title, it.start, it.endInclusive) } }
            }
            val captions = SubtitleEditing.cues(state)

            // Sound on its own tracks: one sequence a track, silent between its sounds. The movie ends with its picture.
            val soundtracks = (0 until AudioItem.TRACKS).mapNotNull { track ->
                val items = sounds.filter { it.track == track && it.start < total - SOUND_MARGIN }.sortedBy { it.start }
                if (items.isEmpty()) return@mapNotNull null
                Sequence(Role.Soundtrack, track, spaced(items, total, { it.start }, { it.start + (minOf(it.end, total) - it.start) }) { item ->
                    val seconds = minOf(item.end, total) - item.start
                    listOf(Entry.Sound(item, item.start, seconds, (item.sourceStart * 1e6).roundToLong(), ((item.sourceStart + seconds) * 1e6).roundToLong()))
                })
            }
            // Cleaned sound in place of the recorded one, at the clips' own times: chains overlap, so they alternate here too.
            val cleanedSound = (0 until DECKS).mapNotNull { deck ->
                val indices = clean.sorted().filter { chainOf[it] % DECKS == deck }
                if (indices.isEmpty()) return@mapNotNull null
                Sequence(Role.Cleaned, deck, spaced(indices, total, { pictures[it].start }, { pictures[it].end }) { listOf(Entry.Clip(it)) })
            }
            return RenderPlan(
                clips, sounds, total, pictures, chains, decks, sequences, TimelineEditing.transitions(clips), freezes, endings,
                titles, state.subtitles?.takeIf { captions.isNotEmpty() }, captions, soundtracks, cleanedSound,
            )
        }
    }
}
