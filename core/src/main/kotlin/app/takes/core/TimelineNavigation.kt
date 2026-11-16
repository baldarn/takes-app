package app.takes.core

import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * The stops of the previous and next buttons: the times where the movie
 * changes. They are derived from the edit each time, never stored.
 *
 * There are two lists. [cutsAndChanges] is what a tap steps through: every
 * cut and every change of treatment. [changes] is what a long press steps
 * through: only the times where what is seen or heard is treated differently,
 * so a plain split between two pieces that look and sound the same is skipped.
 *
 * A treatment is compared by what it does, not by who it is: two clips with
 * the same color settings are the same treatment even though each has its own
 * settings object. A treatment that ends where an equal one begins makes no
 * stop in [changes].
 *
 * Every stop is on a frame of a 30 frames per second movie: the first frame
 * that shows the new treatment. Both lists always hold the start and the end
 * of the movie, are in order and hold no time twice.
 *
 * Bookmarks are not stops. They have their own buttons
 * ([TimelineEditing.nextBookmark]) and are snap targets ([TimelineSnapping]).
 */
class TimelineNavigation private constructor(
    /** Seconds where a treatment begins or ends, plus the start and the end of the movie. */
    val changes: List<Double>,
    /** [changes] and every cut. */
    val cutsAndChanges: List<Double>,
) {
    /**
     * The stop to jump to from [from], or null when there is none on that
     * side: the ends never wrap around. A stop closer than [TOLERANCE] to
     * [from] is the one the playhead is on, so it is passed over rather than
     * jumped to again: a player rarely reports the exact second it was sent to.
     */
    fun destination(from: Double, forward: Boolean, effectsOnly: Boolean = false): Double? {
        val stops = if (effectsOnly) changes else cutsAndChanges
        return if (forward) stops.firstOrNull { it > from + TOLERANCE } else stops.lastOrNull { it < from - TOLERANCE }
    }

    fun next(from: Double, effectsOnly: Boolean = false): Double? = destination(from, true, effectsOnly)

    fun previous(from: Double, effectsOnly: Boolean = false): Double? = destination(from, false, effectsOnly)

    /**
     * Collects cuts and treatments and turns them into the two lists. Times
     * are timeline seconds; anything outside the movie is brought to its
     * nearer end.
     */
    class Builder(duration: Double) {
        private val duration = ticks(maxOf(0.0, duration))
        private val cuts = sortedSetOf(0L, this.duration)
        private val boundaries = sortedSetOf(0L, this.duration)
        private val events = HashMap<Long, HashMap<String, Int>>()

        /** The first frame of the movie at or after [time]. */
        private fun frame(time: Double): Long {
            val seconds = ticks(time) / SCALE
            return minOf(duration, maxOf(0L, ticks(ceil(seconds * FRAMES - FRAME_SLACK) / FRAMES)))
        }

        /**
         * A cut at [time]. One strictly inside any of [hiddenIn] is left out:
         * the footage a transition borrows on either side of its join is not a
         * cut the person made.
         */
        fun cut(time: Double, hiddenIn: List<ClosedFloatingPointRange<Double>> = emptyList()): Builder {
            if (hiddenIn.none { time > it.start + HIDDEN_SLACK && time < it.endInclusive - HIDDEN_SLACK }) cuts += frame(time)
            return this
        }

        /**
         * The treatment named [token] applies from [start] to [end]. Equal
         * tokens are one treatment. A stretch shorter than a frame is ignored.
         */
        fun change(token: String, start: Double, end: Double): Builder {
            val a = frame(start)
            val b = frame(end)
            if (b <= a) return this
            events.getOrPut(a) { HashMap() }.merge(token, 1, Int::plus)
            events.getOrPut(b) { HashMap() }.merge(token, -1, Int::plus)
            return this
        }

        /**
         * Where the first caption appears. Captions are one tool, not hundreds
         * of changes: the later ones are stepped through in the subtitle editor.
         */
        fun subtitles(firstStart: Double?): Builder {
            if (firstStart != null && firstStart.isFinite()) boundaries += frame(firstStart)
            return this
        }

        /**
         * A join laid out with the original's transition settings: the join is
         * a cut, and the picture and the sound of the transition are each a
         * treatment with its own edges, since sound may lead or follow the
         * picture. A join without a transition adds nothing.
         */
        fun transition(join: TransitionLayout.Join): Builder {
            val settings = join.transition ?: return this
            cuts += frame(join.time)
            change(recipe("transition.picture", TransitionSettings.serializer(), settings), join.videoRange.start, join.videoRange.endInclusive)
            val (a, b) = when (join.edge) {
                -1 -> join.audioIn.start to join.audioIn.endInclusive
                1 -> join.audioOut.start to join.audioOut.endInclusive
                else -> minOf(join.audioIn.start, join.audioOut.start) to maxOf(join.audioIn.endInclusive, join.audioOut.endInclusive)
            }
            return change(recipe("transition.sound", TransitionSettings.serializer(), settings), a, b)
        }

        fun build(): TimelineNavigation {
            val changes = boundaries.toSortedSet()
            for ((time, deltas) in events) if (deltas.values.any { it != 0 }) changes += time
            return TimelineNavigation(changes.map { it / SCALE }, (cuts + changes).toSortedSet().map { it / SCALE })
        }
    }

    companion object {
        /** Less than half a frame: closer than this to a stop is on it. */
        const val TOLERANCE = 1.0 / 120

        private const val FRAMES = 30.0
        private const val FRAME_SLACK = 0.00001
        private const val HIDDEN_SLACK = 0.00001

        /** The original's timeline clock: times closer than one tick are the same time. */
        private const val SCALE = 240_000.0
        private const val LONGEST = 604_800.0

        private fun ticks(seconds: Double): Long =
            if (seconds.isFinite()) (seconds.coerceIn(-LONGEST, LONGEST) * SCALE).roundToLong() else 0L

        private val UUID_TEXT = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        /**
         * A name for what [value] does: its [kind] and all of its settings,
         * without the ids that only tell one object from another and without
         * the fields in [omit]. Equal settings give equal names.
         */
        fun <T> recipe(kind: String, serializer: SerializationStrategy<T>, value: T, omit: Set<String> = emptySet()): String =
            kind + ":" + canonical(clean(documentJson.encodeToJsonElement(serializer, value), omit))

        private fun clean(element: JsonElement, omit: Set<String> = emptySet()): JsonElement = when (element) {
            is JsonObject -> JsonObject(
                element.filter { (key, value) ->
                    key !in omit && !(key == "id" && value is JsonPrimitive && value.isString && UUID_TEXT.matches(value.content))
                }.mapValues { clean(it.value) },
            )
            is JsonArray -> JsonArray(element.map { clean(it) })
            else -> element
        }

        private fun canonical(element: JsonElement): String = when (element) {
            is JsonObject -> element.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "\"${it.key}\":${canonical(it.value)}" }
            is JsonArray -> element.joinToString(",", "[", "]") { canonical(it) }
            else -> element.toString()
        }

        /**
         * The stops of [state] as this port stores an edit.
         *
         * Cuts: the start and the end of every clip and of every sound on the
         * audio tracks, and every join with a transition. A clip's start or
         * end strictly inside one of its own transitions is not a cut: the
         * join stands for it. Treatments, each over the stretch it applies to:
         *  - a clip's crop, its speed when not 1, its color when it has a look
         *    with an amount above zero, its pauses taken out, and a cut where
         *    each pause was;
         *  - each title with text, over the part of it the clip shows;
         *  - a clip's or a sound's mute, or else its level when not 0 dB; its
         *    fade in and its fade out, each over the fade; its noise reduction;
         *  - each transition, the opening and the close included: its picture
         *    over the picture effect, its sound over the sound ramps, which
         *    may begin or end elsewhere.
         * And the start of the first caption.
         */
        fun of(state: EditState): TimelineNavigation {
            val clips = state.clips
            val builder = Builder(state.duration)

            fun sound(audio: ClipAudio?, start: Double, end: Double) {
                if (audio == null) return
                if (audio.muted) builder.change("audio.muted", start, end)
                else if (audio.volumeDB != 0.0) builder.change("audio.volume:${audio.volumeDB}", start, end)
                // Over what is heard of the fade: no more than the piece, and less when a cut took part of it.
                val fades = audio.fades
                fades.fadeIn?.let { builder.change("audio.fadeIn:${audio.fadeIn}", start, start + it.remaining(end - start)) }
                fades.fadeOut?.let { builder.change("audio.fadeOut:${audio.fadeOut}", end - it.remaining(end - start), end) }
                if (audio.denoise > 0) builder.change("audio.enhancement:denoise=${audio.denoise}", start, end)
            }

            clips.forEachIndexed { index, clip ->
                val start = TimelineEditing.clipStart(clips, index)
                val end = start + clip.duration
                // The footage a transition borrows past its join is not a cut the person made.
                val borrowed = listOfNotNull(TimelineEditing.entering(clips, index)?.range, TimelineEditing.leaving(clips, index)?.range)
                builder.cut(start, borrowed).cut(end, borrowed)
                clip.crop?.let { builder.change(recipe("crop", CropSettings.serializer(), it), start, end) }
                clip.speed?.takeIf { it.rate != 1.0 }?.let { builder.change(recipe("speed", SpeedSettings.serializer(), it), start, end) }
                // Pauses taken out: the settings over the clip, and a cut where each one was (TimelineNavigation.swift:85-88, :100).
                clip.pauses?.let { builder.change(recipe("pauses", PauseSettings.serializer(), it, setOf("cuts", "candidates", "analyzed")), start, end) }
                if (clip.paused) for (piece in PicturePlayback.pieces(clip.playbackSpans).drop(1)) builder.cut(start + piece.from, borrowed)
                clip.color?.takeIf { it.look != ColorLook.Natural && it.amount > 0 }
                    ?.let { builder.change(recipe("color", ColorSettings.serializer(), it), start, end) }
                for (text in clip.texts.orEmpty()) {
                    if (text.text.isEmpty()) continue
                    val a = maxOf(text.start, clip.start)
                    val b = minOf(text.end, clip.end)
                    // A title is anchored to its footage: where it shows follows the clip's speed.
                    if (b > a) builder.change(recipe("text", TextOverlay.serializer(), text, setOf("start", "end")), start + clip.offset(a), start + clip.offset(b))
                }
                sound(clip.audio, start, end)
            }
            // Every join with a transition, the opening and the close of every clip included, wherever the clip is.
            TimelineEditing.transitions(clips).forEach(builder::transition)
            for (item in state.audio.orEmpty()) {
                builder.cut(item.start).cut(item.end)
                sound(item.audio, item.start, item.end)
            }
            builder.subtitles(SubtitleEditing.cues(state).minOfOrNull { it.start })
            return builder.build()
        }
    }
}
