package app.takes.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.math.pow

/*
 * The original's track timeline (YouRec/TrackTimeline.swift): picture items
 * and sound items, each at its own place on the tick clock, with links,
 * bookmarks, placements and transitions stored as relations between items by
 * id. Key names, units and which keys may be absent are the original's.
 *
 * Nothing saves or reads these yet. The edit a project holds is still
 * [EditState]; [TrackConversion] goes from one to the other and back.
 *
 * Where the JSON differs from what Swift writes, by choice:
 *  - a set of ids ([TimelineLink.members], [VideoPlacementRegion.participants])
 *    is an array in sorted order, where Swift writes one in no defined order;
 *  - [VideoPlacementRegion.videos] is an object keyed by id, where Swift
 *    writes a dictionary keyed by UUID as one flat array of keys and values.
 * [BookmarkAnchor] is written in the shape Swift gives an enum with values.
 */

/** A set of ids, written as a sorted array so the same set always gives the same bytes. */
internal object IdSetSerializer : KSerializer<Set<String>> {
    private val list = ListSerializer(String.serializer())
    override val descriptor: SerialDescriptor = list.descriptor
    override fun serialize(encoder: Encoder, value: Set<String>) = list.serialize(encoder, value.sorted())
    override fun deserialize(decoder: Decoder): Set<String> = list.deserialize(decoder).toSet()
}

/** A placement per picture id, written as an object with its keys in sorted order. */
internal object PlacementMapSerializer : KSerializer<Map<String, VideoPlacement>> {
    private val map = MapSerializer(String.serializer(), VideoPlacement.serializer())
    override val descriptor: SerialDescriptor = map.descriptor
    override fun serialize(encoder: Encoder, value: Map<String, VideoPlacement>) = map.serialize(encoder, value.toSortedMap())
    override fun deserialize(decoder: Decoder): Map<String, VideoPlacement> = map.deserialize(decoder)
}

/**
 * A picture on the timeline. [content] is its recipe: source, source bounds,
 * speed, color, crop, titles. Inside a timeline the recipe's transitions,
 * fits, sound, bookmarks and words are null: they live on relations, on the
 * clip's sound item and in [TrackTimeline.bookmarks].
 *
 * The item's id is the recipe's and is not stored again. [occurrenceID] is
 * shared by the pieces of one split; a copy gets a new one. [layer] is the
 * stacking order, higher in front. [localOrigin] is how far into its
 * occurrence this piece begins. [mapping] is the timing in force.
 *
 * YouRec/TrackTimeline.swift:111-125.
 */
@Serializable
data class VideoTimelineItem(
    val occurrenceID: String,
    val start: EditTime,
    val content: TimelineClip,
    val layer: Int = 0,
    val localOrigin: EditTime = EditTime.ZERO,
    val mapping: SourcePlaybackMap,
) {
    val id: String get() = content.id
    val end: EditTime get() = start + mapping.duration
    val interval: EditInterval get() = EditInterval(start, end)

    companion object {
        /** A picture with the timing its recipe has by itself ([SourcePlaybackMap.of]). */
        fun of(occurrenceID: String, start: EditTime, content: TimelineClip, layer: Int = 0): VideoTimelineItem =
            VideoTimelineItem(occurrenceID, start, content, layer, mapping = SourcePlaybackMap.of(content))

        /** The order pictures are drawn in: by layer, equal layers by id (YouRec/TrackTimeline.swift:740-742). */
        val backToFront: Comparator<VideoTimelineItem> = compareBy<VideoTimelineItem> { it.layer }.thenBy { it.id }
    }
}

/**
 * One ordered chain over a stretch of a sound item. [interval] is in the
 * item's own ticks, independent of where the item is in the project.
 *
 * The original's `AudioEnhancementRegion` (YouRec/TrackTimeline.swift:126-131).
 * [AudioEnhancement] is an earlier port of it with bounds in seconds; that
 * one does not match the saved form and nothing saves it.
 */
@Serializable
data class AudioEnhancementRegion(
    val id: String = newID(),
    val interval: EditInterval,
    val effects: List<AudioEffect>,
)

/**
 * A sound on the timeline. [followsVideo] is the picture whose timing this
 * sound copies; it is only set inside a link with that picture, with the same
 * start and length. [enhancements] is always written, empty when there are
 * none; [censorRegions] is absent when there are none. [subtitleWords] are in
 * source seconds.
 *
 * YouRec/TrackTimeline.swift:166-212.
 */
@Serializable
data class AudioTimelineItem(
    val id: String = newID(),
    val occurrenceID: String,
    val sourceID: String,
    val start: EditTime,
    val mapping: SourcePlaybackMap,
    val localOrigin: EditTime = EditTime.ZERO,
    val followsVideo: String? = null,
    val volumeDB: Double = 0.0,
    val muted: Boolean = false,
    val fadeIn: AudioEdgeFade? = null,
    val fadeOut: AudioEdgeFade? = null,
    val enhancements: List<AudioEnhancementRegion> = emptyList(),
    val censorRegions: List<AudioCensorRegion>? = null,
    val subtitleWords: List<SubtitleWord>? = null,
) {
    val censors: List<AudioCensorRegion> get() = censorRegions.orEmpty()
    val end: EditTime get() = start + mapping.duration
    val interval: EditInterval get() = EditInterval(start, end)
    val gain: Double get() = if (muted) 0.0 else 10.0.pow(volumeDB / 20)

    /** Seconds of the fade at [edge] that are heard. */
    fun fadeSeconds(edge: AudioFadeEdge): Double =
        (if (edge == AudioFadeEdge.FadeIn) fadeIn else fadeOut)?.remaining(mapping.duration.seconds) ?: 0.0

    /** The item with its censors replaced; none are saved as no key at all. */
    fun withCensors(regions: List<AudioCensorRegion>): AudioTimelineItem = copy(censorRegions = regions.ifEmpty { null })

    /**
     * The piece [part] of the item, in the item's own ticks, under [id]: its
     * timing, what is left of each fade (a cut inside a fade keeps its slope
     * and its level at the cut), and the censors and chains that reach into it.
     */
    fun sliced(part: EditInterval, id: String): AudioTimelineItem = copy(
        id = id,
        start = start + part.start,
        localOrigin = localOrigin + part.start,
        mapping = mapping.sliced(part),
        fadeIn = fadeIn?.trimming(part.start),
        fadeOut = fadeOut?.trimming(mapping.duration - part.end),
        censorRegions = censors.mapNotNull { it.sliced(part) }.ifEmpty { null },
        enhancements = enhancements.mapNotNull { region ->
            region.interval.intersection(part)?.let { region.copy(interval = EditInterval(it.start - part.start, it.end - part.start)) }
        },
    )
}

/**
 * Items that are selected together. At least two; an item is in at most one
 * link. A link records membership only: it moves nothing by itself.
 * YouRec/TrackTimeline.swift:224-227.
 */
@Serializable
data class TimelineLink(
    val id: String = newID(),
    @Serializable(with = IdSetSerializer::class) val members: Set<String>,
)

/**
 * What a bookmark is attached to (YouRec/TrackTimeline.swift:341-345).
 *
 * Written as Swift writes an enum with values: an object with the case's name
 * as its one key, the labelled values under their labels and an unlabelled
 * one under `_0`:
 * `{"source":{"occurrence":…,"source":…,"time":…}}`,
 * `{"local":{"occurrence":…,"offset":{"ticks":n}}}`,
 * `{"project":{"_0":{"ticks":n}}}`.
 */
@Serializable(with = BookmarkAnchorSerializer::class)
sealed interface BookmarkAnchor {
    /** A source second of an occurrence: the mark follows that footage or sound. */
    data class Source(val occurrence: String, val source: String, val time: Double) : BookmarkAnchor

    /** A tick inside an occurrence that holds one frame, where no source second tells places apart. */
    data class Local(val occurrence: String, val offset: EditTime) : BookmarkAnchor

    /** A tick of the timeline, attached to nothing. */
    data class Project(val time: EditTime) : BookmarkAnchor
}

internal object BookmarkAnchorSerializer : KSerializer<BookmarkAnchor> {
    @Serializable
    private class Source(val occurrence: String, val source: String, val time: Double)

    @Serializable
    private class Local(val occurrence: String, val offset: EditTime)

    @Serializable
    private class Project(@SerialName("_0") val time: EditTime)

    @Serializable
    private class Cases(val source: Source? = null, val local: Local? = null, val project: Project? = null)

    override val descriptor: SerialDescriptor = Cases.serializer().descriptor

    override fun serialize(encoder: Encoder, value: BookmarkAnchor) = Cases.serializer().serialize(
        encoder,
        when (value) {
            is BookmarkAnchor.Source -> Cases(source = Source(value.occurrence, value.source, value.time))
            is BookmarkAnchor.Local -> Cases(local = Local(value.occurrence, value.offset))
            is BookmarkAnchor.Project -> Cases(project = Project(value.time))
        },
    )

    override fun deserialize(decoder: Decoder): BookmarkAnchor {
        val cases = Cases.serializer().deserialize(decoder)
        if (listOfNotNull(cases.source, cases.local, cases.project).size != 1) throw SerializationException("A bookmark anchor has exactly one case")
        cases.source?.let { return BookmarkAnchor.Source(it.occurrence, it.source, it.time) }
        cases.local?.let { return BookmarkAnchor.Local(it.occurrence, it.offset) }
        return BookmarkAnchor.Project(cases.project!!.time)
    }
}

/** A mark with a label of at most 500 characters. YouRec/TrackTimeline.swift:346-350. */
@Serializable
data class TimelineBookmark(
    val id: String = newID(),
    val label: String = "",
    val anchor: BookmarkAnchor,
)

/** A bookmark with the tick of the timeline it is at now. */
data class ResolvedBookmark(val bookmark: TimelineBookmark, val time: EditTime) {
    val id: String get() = bookmark.id
}

/**
 * The windows of pictures that play at the same time. It belongs to exactly
 * the set [participants]; where they overlap is derived, not stored.
 * [videos] has one placement per participant.
 * YouRec/TrackTimeline.swift:457-461.
 */
@Serializable
data class VideoPlacementRegion(
    val id: String = newID(),
    @Serializable(with = IdSetSerializer::class) val participants: Set<String>,
    @Serializable(with = PlacementMapSerializer::class) val videos: Map<String, VideoPlacement>,
)

/**
 * A transition as a relation between pictures: from [outgoing] into
 * [incoming]. An opening has only [incoming], a close only [outgoing]. Its
 * id is its settings'. [origin] is where the picture effect starts and
 * [factor] the fit, 0…1; both are saved, and rendering never works them out.
 * YouRec/TrackTimeline.swift:462-470.
 */
@Serializable
data class TimelineTransition(
    val outgoing: String? = null,
    val incoming: String? = null,
    val settings: TransitionSettings,
    val origin: EditTime,
    val factor: Double = 1.0,
) {
    val id: String get() = settings.id

    /** −1 for an opening, +1 for a close, 0 between two pictures. */
    val edge: Int get() = if (outgoing == null) -1 else if (incoming == null) 1 else 0

    val timing: TransitionTiming get() = TransitionTiming.of(settings, factor, edge)

    /**
     * The stretch this transition occupies, as every edit rewrites it: from
     * the start of its picture and sound to their end, never before zero. A
     * still holds the picture it leads out of.
     * YouRec/TrackTimeline.swift:889-895.
     */
    val generated: TimelineGeneratedRegion
        get() = timing.let {
            TimelineGeneratedRegion(
                TrackTimeline.counterpart(id), id,
                EditInterval(maxOf(EditTime.ZERO, origin + EditTime.of(it.lower)), maxOf(EditTime.ZERO, origin + EditTime.of(it.upper))),
                if (settings.style == TransitionStyle.Still) outgoing else null,
            )
        }
}

/**
 * The stretch a transition occupies, kept with the timeline and rewritten
 * from the transitions by every edit. [freezeVideo] is the picture a still holds.
 * YouRec/TrackTimeline.swift:471-476.
 */
@Serializable
data class TimelineGeneratedRegion(
    val id: String,
    val owner: String,
    val interval: EditInterval,
    val freezeVideo: String? = null,
)

/**
 * The edit as independent items. There is no magnetic track: closing a gap is
 * an edit of its own, and a link records what is selected together and
 * nothing else. Gaps are empty time; no black or silent media stands in for
 * them.
 *
 * YouRec/TrackTimeline.swift:487-503. The original also carries
 * `captureBase`, runtime provenance that is not saved; here that stays on
 * [EditState] until a project holds a timeline.
 */
@Serializable
data class TrackTimeline(
    val video: List<VideoTimelineItem> = emptyList(),
    val audio: List<AudioTimelineItem> = emptyList(),
    val links: List<TimelineLink> = emptyList(),
    val bookmarks: List<TimelineBookmark> = emptyList(),
    val placements: List<VideoPlacementRegion> = emptyList(),
    val transitions: List<TimelineTransition> = emptyList(),
    val generated: List<TimelineGeneratedRegion> = emptyList(),
    val subtitles: SubtitleSettings? = null,
) {
    /** As long as the last item, picture or sound, or the stretch of a transition when that reaches further. */
    val duration: EditTime
        get() = maxOf(
            video.maxOfOrNull { it.end } ?: EditTime.ZERO,
            audio.maxOfOrNull { it.end } ?: EditTime.ZERO,
            generated.maxOfOrNull { it.interval.end } ?: EditTime.ZERO,
        )

    val allIDs: Set<String> get() = (video.map { it.id } + audio.map { it.id }).toSet()

    /**
     * The pictures in the order they start, for controls that think in a
     * sequence. The stacking order is separate and does not change with it.
     * YouRec/TrackTimeline.swift:1254-1258.
     */
    val chronologicalVideo: List<VideoTimelineItem> get() = video.sortedBy { it.start }

    /**
     * Where each bookmark is now, in time order and by id within one tick.
     * One whose footage or sound is no longer on the timeline is left out.
     * YouRec/TrackTimeline.swift:901-914.
     */
    val resolvedBookmarks: List<ResolvedBookmark>
        get() = bookmarks.mapNotNull { bookmark ->
            val time = when (val anchor = bookmark.anchor) {
                is BookmarkAnchor.Project -> anchor.time
                is BookmarkAnchor.Source ->
                    video.firstNotNullOfOrNull { item ->
                        if (item.occurrenceID == anchor.occurrence && item.content.sourceID == anchor.source) item.mapping.local(anchor.time)?.let { item.start + it } else null
                    } ?: audio.firstNotNullOfOrNull { item ->
                        if (item.occurrenceID == anchor.occurrence && item.sourceID == anchor.source) item.mapping.local(anchor.time)?.let { item.start + it } else null
                    }
                is BookmarkAnchor.Local ->
                    video.firstOrNull { it.occurrenceID == anchor.occurrence && anchor.offset >= it.localOrigin && anchor.offset < it.localOrigin + it.mapping.duration }
                        ?.let { it.start + anchor.offset - it.localOrigin }
            }
            time?.let { ResolvedBookmark(bookmark, it) }
        }.sortedWith(compareBy<ResolvedBookmark> { it.time }.thenBy { it.id })

    /**
     * Adds the marks made while [source] was recorded, as source bookmarks on
     * the first picture playing it. A mark already there (by id) is not added
     * again, and one past the recorded length is left out.
     * YouRec/TrackTimeline.swift:1463-1469.
     */
    fun includingCaptureBookmarks(source: SourceMedia): TrackTimeline {
        val item = video.firstOrNull { it.content.sourceID == source.id } ?: return this
        val added = mutableListOf<TimelineBookmark>()
        for (event in source.events.orEmpty()) {
            if (event.kind != Project.BOOKMARK_EVENT || !(event.time >= 0 && event.time < source.duration)) continue
            if (bookmarks.any { it.id == event.id } || added.any { it.id == event.id }) continue
            added += TimelineBookmark(event.id, anchor = BookmarkAnchor.Source(item.occurrenceID, source.id, event.time))
        }
        return if (added.isEmpty()) this else copy(bookmarks = bookmarks + added)
    }

    /** The timeline as the JSON a step will hold it in. */
    fun encoded(): String = documentJson.encodeToString(serializer(), this)

    companion object {
        fun decoded(json: String): TrackTimeline = documentJson.decodeFromString(serializer(), json)

        /**
         * The id of what is made from the thing [id] names: a clip's sound
         * from the clip, a transition's stretch from the transition. A fixed
         * function, so making it twice, on any device, gives the same id.
         *
         * For an id written as a UUID this is the original's: the highest bit
         * of its last byte flipped (YouRec/TrackTimeline.swift:535-539), and
         * so its own inverse. Ids here are strings and need not be UUIDs; any
         * other gets [COUNTERPART_SUFFIX] appended.
         */
        fun counterpart(id: String): String = derived(id, 8, COUNTERPART_SUFFIX)

        /**
         * A second id made from [id], different from it and from its
         * [counterpart], for the one thing the original never needed: a sound
         * item saved under the id of a clip.
         */
        fun alias(id: String): String = derived(id, 4, ALIAS_SUFFIX)

        /**
         * The schema number a project takes when it saves a timeline: the
         * next one free when this was written. Nothing reads it yet, since
         * nothing saves a timeline; it is here so the number is in one place
         * when something does, and it moves if another format step lands first.
         */
        const val SCHEMA = 15

        const val COUNTERPART_SUFFIX = "~sound"
        const val ALIAS_SUFFIX = "~item"

        private const val HEX = "0123456789abcdef"

        private fun isUUID(id: String): Boolean =
            id.length == 36 && id.indices.all { if (it == 8 || it == 13 || it == 18 || it == 23) id[it] == '-' else id[it].lowercaseChar() in HEX }

        /** [id] with [bit] of the high half of its last byte flipped when it is a UUID, with [suffix] otherwise. */
        private fun derived(id: String, bit: Int, suffix: String): String {
            if (!isUUID(id)) return id + suffix
            val flipped = HEX[HEX.indexOf(id[34].lowercaseChar()) xor bit]
            // Letters keep the case the id is written in.
            val upper = id.none { it in 'a'..'f' }
            return id.substring(0, 34) + (if (upper) flipped.uppercaseChar() else flipped) + id.substring(35)
        }
    }
}
